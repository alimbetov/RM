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
    @Autowired JdbcTemplate jdbc;
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
        assertThat(onHand(p)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
            """,Integer.class,p.tenant())).isEqualTo(1);
        assertThatThrownBy(()->movements.adjust(p.tenant(),p.location(),p.item(),
                "stock-req-001",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
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
