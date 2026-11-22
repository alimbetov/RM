package kz.rm.inventory;

import kz.rm.iam.*;
import kz.rm.inventory.dto.InventoryMovementSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Q7 and Q8: no direct balance initialization, no alternative quantity ledger. */
@SpringBootTest
class InventoryTransferStocktakeQualificationTest {
    @Autowired AuthenticatedInventoryCommands commands;
    @Autowired AuthenticatedInventoryQueries queries;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;

    private record Fixture(UUID tenant,UUID actor,UUID source,UUID destination,UUID item) {}
    private UUID newUser() {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "inv-q78-"+UUID.randomUUID(),"Warehouse employee")).getId();
    }
    private VerifiedActor actor(UUID user) {
        String sub=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token).header("alg","RS256")
                .subject(sub).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(360)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }
    private Fixture fixture() {
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Inventory Q78 LLP","Warehouse","KZ"));
        UUID tenant=workspaces.saveAndFlush(WorkspaceEntity.organization(
                UUID.randomUUID(),org.getId())).getId();
        UUID owner=newUser(),employee=newUser();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),employee,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        UUID src=UUID.randomUUID(),dst=UUID.randomUUID(),sku=UUID.randomUUID();
        for(UUID location:new UUID[]{src,dst}) {
            jdbc.update("""
                    INSERT INTO public.org_location
                       (id,workspace_id,location_type,name,kato_code,status,
                        created_at,updated_at,row_version)
                    VALUES (?,?,'WAREHOUSE','Q78 warehouse',?,'ACTIVE',
                            CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                    """,location,tenant,kato);
        }
        jdbc.update("""
                INSERT INTO crm.cat_item
                   (tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','DRAFT',CURRENT_TIMESTAMP)
                """,tenant,sku,"q78-"+UUID.randomUUID());
        var ownerActor=actor(owner);
        var role=roles.createRole(ownerActor,tenant,"Q78_STAFF","Inventory Q78");
        roles.grant(ownerActor,tenant,role.id(),DomainPermission.INVENTORY_RECEIVE);
        roles.grant(ownerActor,tenant,role.id(),DomainPermission.INVENTORY_ADJUST);
        roles.grant(ownerActor,tenant,role.id(),DomainPermission.INVENTORY_STOCK_READ);
        roles.assign(ownerActor,tenant,employee,role.id());
        roles.enable(ownerActor,tenant);
        return new Fixture(tenant,employee,src,dst,sku);
    }
    private BigDecimal stock(Fixture f,UUID location) {
        return queries.getPosition(actor(f.actor()),f.tenant(),location,f.item()).onHand();
    }
    private void receive(Fixture f,UUID location,String key,int qty) {
        commands.receive(actor(f.actor()),f.tenant(),location,f.item(),
                key,BigDecimal.valueOf(qty));
    }

    @Test void transferWritesExactlyTwoLegsAndReplaysWithoutDuplicateBalances() {
        var f=fixture();receive(f,f.source(),"stockQ7R1001",10);
        assertThatThrownBy(()->commands.transfer(actor(f.actor()),f.tenant(),
                f.source(),f.source(),f.item(),BigDecimal.ONE,"sameLocation001"))
                .isInstanceOf(IllegalArgumentException.class);
        var receipt=commands.transfer(actor(f.actor()),f.tenant(),
                f.source(),f.destination(),f.item(),new BigDecimal("4"),"transferUserQ701");
        assertThat(receipt.quantity()).isEqualByComparingTo("4");
        assertThat(receipt.postedBy()).isEqualTo(f.actor());
        assertThat(stock(f,f.source())).isEqualByComparingTo("6");
        assertThat(stock(f,f.destination())).isEqualByComparingTo("4");
        assertThat(commands.transfer(actor(f.actor()),f.tenant(),
                f.source(),f.destination(),f.item(),new BigDecimal("4"),
                "transferUserQ701").id()).isEqualTo(receipt.id());
        assertThatThrownBy(()->commands.transfer(actor(f.actor()),f.tenant(),
                f.source(),f.destination(),f.item(),BigDecimal.ONE,"transferUserQ701"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->commands.transfer(actor(f.actor()),f.tenant(),
                f.source(),f.destination(),f.item(),new BigDecimal("7"),"transferUserQ702"))
                .isInstanceOf(IllegalStateException.class);
        var moves=queries.searchMovements(actor(f.actor()),f.tenant(),
                new InventoryMovementSearch(null,f.item(),null,null,null),0,20).getContent();
        var legs=moves.stream().filter(m->receipt.id().equals(m.sourceId())).toList();
        assertThat(legs).hasSize(2).extracting(m->m.reasonCode())
                .containsExactlyInAnyOrder("TRANSFER_OUT","TRANSFER_IN");
        assertThat(moves.stream().filter(m->receipt.id().equals(m.sourceId()))
                .map(m->m.delta()).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo("0");
        assertThatThrownBy(()->commands.transfer(actor(f.actor()),f.tenant(),
                f.source(),f.destination(),f.item(),BigDecimal.ONE,
                "fulfill-foreign"))
                .isInstanceOf(IllegalArgumentException.class);
        var foreign=fixture();
        assertThatThrownBy(()->commands.transfer(actor(f.actor()),f.tenant(),
                f.source(),foreign.source(),f.item(),BigDecimal.ONE,"transferForeign01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void inverseConcurrentTransfersDoNotDeadlockAndConserveStock() throws Exception {
        var f=fixture();receive(f,f.source(),"stockQ7A0001",10);
        receive(f,f.destination(),"stockQ7B0001",10);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ab=pool.submit(()->{
                gate.await(10,TimeUnit.SECONDS);
                return commands.transfer(actor(f.actor()),f.tenant(),f.source(),
                        f.destination(),f.item(),BigDecimal.valueOf(3),"oppositeAB0001");
            });
            var ba=pool.submit(()->{
                gate.await(10,TimeUnit.SECONDS);
                return commands.transfer(actor(f.actor()),f.tenant(),f.destination(),
                        f.source(),f.item(),BigDecimal.valueOf(3),"oppositeBA0001");
            });
            gate.countDown();
            assertThat(ab.get(30,TimeUnit.SECONDS).id()).isNotNull();
            assertThat(ba.get(30,TimeUnit.SECONDS).id()).isNotNull();
        }
        assertThat(stock(f,f.source())).isEqualByComparingTo("10");
        assertThat(stock(f,f.destination())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_transfer WHERE tenant_id=?
                """,Integer.class,f.tenant())).isEqualTo(2);
    }

    @Test void stocktakePostsOnlyMeasuredDeltaAndRejectsStaleOrBelowReserved() {
        var f=fixture();receive(f,f.source(),"stockQ8R0001",10);
        var employee=actor(f.actor());
        var opened=commands.startStocktake(employee,f.tenant(),f.source(),
                List.of(f.item()),"physicalCount01");
        assertThat(opened.status()).isEqualTo("OPEN");
        assertThat(opened.lines()).hasSize(1);
        assertThat(opened.lines().getFirst().bookOnHand()).isEqualByComparingTo("10");
        assertThat(commands.startStocktake(employee,f.tenant(),f.source(),
                List.of(f.item()),"physicalCount01").id()).isEqualTo(opened.id());
        var counted=commands.recordCount(employee,f.tenant(),opened.id(),f.item(),
                BigDecimal.valueOf(9),0);
        assertThat(counted.lines().getFirst().rowVersion()).isEqualTo(1L);
        assertThat(stock(f,f.source())).isEqualByComparingTo("10"); // no early delta
        var posted=commands.postStocktake(employee,f.tenant(),opened.id(),"postCount001");
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(stock(f,f.source())).isEqualByComparingTo("9");
        assertThat(commands.postStocktake(employee,f.tenant(),opened.id(),
                "postCount001").id()).isEqualTo(opened.id());
        assertThatThrownBy(()->commands.cancelStocktake(employee,f.tenant(),opened.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(queries.stocktake(employee,f.tenant(),opened.id()).postedBy())
                .isEqualTo(f.actor());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND source_type='STOCKTAKE' AND source_id=?
                  AND reason_code='STOCKTAKE_ADJUSTMENT' AND actor_user_id=?
                """,Integer.class,f.tenant(),opened.id(),f.actor())).isEqualTo(1);
        assertThatThrownBy(()->commands.recordCount(employee,f.tenant(),opened.id(),
                f.item(),BigDecimal.TEN,1)).isInstanceOf(IllegalStateException.class);

        var stale=commands.startStocktake(employee,f.tenant(),f.source(),
                List.of(f.item()),"physicalCount02");
        commands.recordCount(employee,f.tenant(),stale.id(),f.item(),BigDecimal.valueOf(8),0);
        receive(f,f.source(),"stockQ8R0002",1);
        assertThatThrownBy(()->commands.postStocktake(employee,f.tenant(),
                stale.id(),"postCount002"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOUNT_REQUIRED");
        assertThat(stock(f,f.source())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND source_type='STOCKTAKE' AND source_id=?
                """,Integer.class,f.tenant(),stale.id())).isZero();
        var cancelled=commands.cancelStocktake(employee,f.tenant(),stale.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledBy()).isEqualTo(f.actor());
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThatThrownBy(()->commands.postStocktake(employee,f.tenant(),
                stale.id(),"postCount003")).isInstanceOf(IllegalStateException.class);
    }

    @Test void postgresPostedStocktakeAndTransferAreImmutable() throws Exception {
        try(var con=source.getConnection()){
            Assumptions.assumeTrue(con.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture();receive(f,f.source(),"stockQ8Immutable01",5);
        var who=actor(f.actor());
        var take=commands.startStocktake(who,f.tenant(),f.source(),
                List.of(f.item()),"takeImmutable01");
        commands.recordCount(who,f.tenant(),take.id(),f.item(),BigDecimal.valueOf(4),0);
        commands.postStocktake(who,f.tenant(),take.id(),"postImmutable01");
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.inv_stocktake_line SET counted_on_hand=3
                WHERE tenant_id=? AND stocktake_id=?
                """,f.tenant(),take.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var receipt=commands.transfer(who,f.tenant(),f.source(),f.destination(),
                f.item(),BigDecimal.ONE,"transferImmutable01");
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.inv_stock_transfer WHERE tenant_id=? AND id=?
                """,f.tenant(),receipt.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void stocktakeRejectsCountsBelowReservationsAndNoChangeEmitsNoFakeMovement() {
        var f=fixture();receive(f,f.source(),"stockQ8Reserve001",10);
        jdbc.update("""
                UPDATE crm.inv_stock_balance
                SET reserved=3,row_version=row_version+1
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,f.tenant(),f.source(),f.item());
        var who=actor(f.actor());
        var take=commands.startStocktake(who,f.tenant(),f.source(),
                List.of(f.item()),"countReserved001");
        commands.recordCount(who,f.tenant(),take.id(),f.item(),BigDecimal.valueOf(2),0);
        assertThatThrownBy(()->commands.postStocktake(who,f.tenant(),take.id(),
                "postBelowRes001")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("below active reservations");
        assertThat(stock(f,f.source())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND source_type='STOCKTAKE' AND source_id=?
                """,Integer.class,f.tenant(),take.id())).isZero();
        var other=commands.startStocktake(who,f.tenant(),f.source(),
                List.of(f.item()),"countNoChange001");
        commands.recordCount(who,f.tenant(),other.id(),f.item(),BigDecimal.TEN,0);
        assertThat(commands.postStocktake(who,f.tenant(),other.id(),"postNoChange001")
                .status()).isEqualTo("POSTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND source_type='STOCKTAKE' AND source_id=?
                """,Integer.class,f.tenant(),other.id())).isZero();
        assertThat(stock(f,f.source())).isEqualByComparingTo("10");
    }

    @Test void stocktakeRequiresFullCountAndDeniesForeignLocation() {
        var f=fixture();
        var foreign=fixture();
        receive(f,f.source(),"stockQ8Guard0001",6);
        var who=actor(f.actor());
        assertThatThrownBy(()->commands.startStocktake(who,f.tenant(),foreign.source(),
                List.of(f.item()),"foreignTake001"))
                .isInstanceOf(IllegalArgumentException.class);
        var started=commands.startStocktake(who,f.tenant(),f.source(),
                List.of(f.item()),"fullTake0001");
        assertThatThrownBy(()->commands.postStocktake(who,f.tenant(),started.id(),
                "incompletePost01")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uncounted SKU");
        assertThatThrownBy(()->commands.startStocktake(who,f.tenant(),f.destination(),
                List.of(f.item()),"fullTake0001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->queries.stocktake(who,f.tenant(),UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(commands.cancelStocktake(who,f.tenant(),started.id()).status())
                .isEqualTo("CANCELLED");
        assertThat(commands.cancelStocktake(who,f.tenant(),started.id()).status())
                .isEqualTo("CANCELLED");
    }

    @Test void receiptAtTransferDestinationDoesNotCycleWithWarehouseOwnerLocks()
            throws Exception {
        var f=fixture();receive(f,f.source(),"stockQ7Race0001",10);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var movement=pool.submit(()->{
                gate.await(10,TimeUnit.SECONDS);
                return commands.transfer(actor(f.actor()),f.tenant(),f.source(),
                        f.destination(),f.item(),BigDecimal.valueOf(4),"transferRace0001");
            });
            var incoming=pool.submit(()->{
                gate.await(10,TimeUnit.SECONDS);
                return commands.receive(actor(f.actor()),f.tenant(),f.destination(),f.item(),
                        "receiptRace0001",BigDecimal.ONE);
            });
            gate.countDown();
            assertThat(movement.get(30,TimeUnit.SECONDS).id()).isNotNull();
            assertThat(incoming.get(30,TimeUnit.SECONDS).id()).isNotNull();
        }
        assertThat(stock(f,f.source())).isEqualByComparingTo("6");
        assertThat(stock(f,f.destination())).isEqualByComparingTo("5");
    }

}
