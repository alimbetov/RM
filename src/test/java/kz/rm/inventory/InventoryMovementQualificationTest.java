package kz.rm.inventory;

import kz.rm.iam.OrganizationEntity;
import kz.rm.iam.OrganizationRepository;
import kz.rm.iam.WorkspaceEntity;
import kz.rm.iam.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class InventoryMovementQualificationTest {
    @Autowired InventoryMovementService movements;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;

    private record Position(UUID tenant, UUID location, UUID item) {}
    private Position position() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Movement LLP","Movement","KZ"));
        UUID tenant=workspaces.saveAndFlush(WorkspaceEntity.organization(
                UUID.randomUUID(),org.getId())).getId();
        String kato=jdbc.queryForObject("""
            SELECT code FROM public.ref_kato_territory
            WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
            """,String.class);
        UUID location=UUID.randomUUID(),item=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO public.org_location
              (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
            VALUES (?,?,'WAREHOUSE','Stock location',?,'ACTIVE',?,?,0)
            """,location,tenant,kato,now,now);
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',?)
            """,tenant,item,"sku-"+UUID.randomUUID(),now);
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?,10,3)
            """,tenant,location,item);
        return new Position(tenant,location,item);
    }
    private BigDecimal onHand(Position p) {
        return jdbc.queryForObject("""
            SELECT on_hand FROM crm.inv_stock_balance
            WHERE tenant_id=? AND location_id=? AND item_id=?
            """,BigDecimal.class,p.tenant(),p.location(),p.item());
    }

    @Test void p1PostgresMovementAppendFailureRestoresBalanceAndRetry() throws Exception {
        try(var con=dataSource.getConnection()){
            Assumptions.assumeTrue(con.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
        }
        var p=position();
        var before=jdbc.queryForList("SELECT on_hand,reserved,row_version FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",p.tenant(),p.location(),p.item());
        assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
            jdbc.execute("ALTER TABLE crm.int_canonical_fact ADD CONSTRAINT ck_p1_movement_failure CHECK (false) NOT VALID");
            movements.adjust(p.tenant(),p.location(),p.item(),"p1-movement-retry",BigDecimal.ONE);
        })).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("ck_p1_movement_failure");
        assertThat(jdbc.queryForList("SELECT on_hand,reserved,row_version FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",p.tenant(),p.location(),p.item())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?",Integer.class,p.tenant())).isZero();
        kz.rm.iam.CanonicalProducerEvidence.assertInventory(jdbc,p.tenant());
        var recovered=movements.adjust(p.tenant(),p.location(),p.item(),"p1-movement-retry",BigDecimal.ONE);
        assertThat(movements.adjust(p.tenant(),p.location(),p.item(),"p1-movement-retry",BigDecimal.ONE).id()).isEqualTo(recovered.id());
        assertThat(onHand(p)).isEqualByComparingTo("11");
        kz.rm.iam.CanonicalProducerEvidence.assertInventory(jdbc,p.tenant());
    }

    @Test void adjustmentIsAtomicAndIdempotent() {
        var p=position();
        var a=movements.adjust(p.tenant(),p.location(),p.item(),"stock-req-001",
                new BigDecimal("-4"));
        assertThat(a.before()).isEqualByComparingTo("10");
        assertThat(a.after()).isEqualByComparingTo("6");
        assertThat(onHand(p)).isEqualByComparingTo("6");
        var retry=movements.adjust(p.tenant(),p.location(),p.item(),"stock-req-001",
                new BigDecimal("-4"));
        assertThat(retry.id()).isEqualTo(a.id());
        kz.rm.iam.CanonicalProducerEvidence.assertInventory(jdbc,p.tenant());
        assertThat(onHand(p)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
            """,Integer.class,p.tenant())).isEqualTo(1);
        assertThatThrownBy(()->movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-req-001",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void receiptWriteOffAndCorrectionCarryImmutableReasons() {
        var p=position();
        var receipt=movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-receipt-001",new BigDecimal("4"),InventoryMovementService.Reason.RECEIPT);
        assertThat(receipt.after()).isEqualByComparingTo("14");
        var writeOff=movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-writeoff-001",new BigDecimal("-2"),InventoryMovementService.Reason.WRITE_OFF);
        assertThat(writeOff.after()).isEqualByComparingTo("12");
        var correction=movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-correct-001",new BigDecimal("-1"),InventoryMovementService.Reason.CORRECTION);
        assertThat(correction.after()).isEqualByComparingTo("11");
        kz.rm.iam.CanonicalProducerEvidence.assertInventory(jdbc,p.tenant());
        assertThat(jdbc.queryForObject("""
            SELECT reason_code FROM crm.inv_stock_movement WHERE tenant_id=? AND id=?
            """,String.class,p.tenant(),correction.id())).isEqualTo("CORRECTION");
        assertThatThrownBy(()->movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-correct-001",new BigDecimal("-1"),InventoryMovementService.Reason.WRITE_OFF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-reverse-001",BigDecimal.ONE,InventoryMovementService.Reason.WRITE_OFF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-reverse-002",new BigDecimal("-1"),InventoryMovementService.Reason.RECEIPT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cannotReduceBelowReservedOrCrossTenantReference() {
        var p=position();
        assertThatThrownBy(()->movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-req-002",new BigDecimal("-8"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(onHand(p)).isEqualByComparingTo("10");
        assertThatThrownBy(()->movements.adjust(UUID.randomUUID(),p.location(),p.item(),
                "stock-req-003",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.inv_stock_movement
              (tenant_id,id,location_id,item_id,request_key,delta_quantity,
              balance_before,balance_after,occurred_at,recorded_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),UUID.randomUUID(),p.location(),p.item(),
                "invalid-cross",BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ONE,
                Timestamp.from(Instant.now()),Timestamp.from(Instant.now())))
                .isInstanceOf(Exception.class);
    }

    @Test void postgresForbidsChangingOrDeletingPostedMovement() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var p=position();
        var movement=movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-immutable-001",BigDecimal.ONE);
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.inv_stock_movement SET delta_quantity=2
            WHERE tenant_id=? AND id=?
            """,p.tenant(),movement.id())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.inv_stock_movement WHERE tenant_id=? AND id=?
            """,p.tenant(),movement.id())).isInstanceOf(Exception.class);
        assertThat(onHand(p)).isEqualByComparingTo("11");
    }

    @Test void postingVersionIsMonotonicPerPositionAndReplayDoesNotAllocateAnother() {
        var p=position();
        var first=movements.adjust(p.tenant(),p.location(),p.item(),
            "stock-posting-001",BigDecimal.ONE);
        var second=movements.adjust(p.tenant(),p.location(),p.item(),
            "stock-posting-002",BigDecimal.ONE);
        long a=jdbc.queryForObject("""
            SELECT posting_version FROM crm.inv_stock_movement
            WHERE tenant_id=? AND id=?
            """,Long.class,p.tenant(),first.id());
        long b=jdbc.queryForObject("""
            SELECT posting_version FROM crm.inv_stock_movement
            WHERE tenant_id=? AND id=?
            """,Long.class,p.tenant(),second.id());
        assertThat(a).isPositive();
        assertThat(b).isGreaterThan(a);
        assertThat(b).isEqualTo(jdbc.queryForObject("""
            SELECT row_version FROM crm.inv_stock_balance
            WHERE tenant_id=? AND location_id=? AND item_id=?
            """,Long.class,p.tenant(),p.location(),p.item()));
        assertThat(movements.adjust(p.tenant(),p.location(),p.item(),
            "stock-posting-002",BigDecimal.ONE).id()).isEqualTo(second.id());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement
            WHERE tenant_id=? AND location_id=? AND item_id=?
              AND posting_version IS NOT NULL
            """,Integer.class,p.tenant(),p.location(),p.item())).isEqualTo(2);
    }

    @Test void postgresConcurrentDifferentKeysHaveStrictlyIncreasingPostingVersions() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var p=position();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return movements.adjust(p.tenant(),p.location(),p.item(),
                    "stock-pos-race-01",BigDecimal.ONE);
            });
            var right=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return movements.adjust(p.tenant(),p.location(),p.item(),
                    "stock-pos-race-02",BigDecimal.ONE);
            });
            start.countDown();
            left.get(30,TimeUnit.SECONDS);
            right.get(30,TimeUnit.SECONDS);
        }
        var versions=jdbc.query("""
            SELECT posting_version FROM crm.inv_stock_movement
            WHERE tenant_id=? AND location_id=? AND item_id=?
            ORDER BY posting_version
            """,(rs,n)->rs.getLong(1),p.tenant(),p.location(),p.item());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0)).isPositive();
        assertThat(versions.get(1)).isGreaterThan(versions.get(0));
        assertThat(onHand(p)).isEqualByComparingTo("12");
    }

    @Test void concurrentSameKeyPostsExactlyOneMovement() throws Exception {
        var p=position();
        var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                return movements.adjust(p.tenant(),p.location(),p.item(),
                        "stock-race-001",BigDecimal.ONE).id();
            });
            var second=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                return movements.adjust(p.tenant(),p.location(),p.item(),
                        "stock-race-001",BigDecimal.ONE).id();
            });
            release.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(second.get(20,TimeUnit.SECONDS));
        }
        assertThat(onHand(p)).isEqualByComparingTo("11");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
            """,Integer.class,p.tenant())).isEqualTo(1);
    }
}
