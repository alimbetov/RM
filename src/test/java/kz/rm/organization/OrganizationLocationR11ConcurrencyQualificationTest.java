package kz.rm.organization;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import kz.rm.inventory.AuthenticatedInventoryCommands;
import kz.rm.organization.dto.LocationCreateRequest;
import kz.rm.organization.dto.LocationUpdateRequest;
import kz.rm.sales.AuthenticatedSalesCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * ORG-R1.1 PostgreSQL concurrency admission. No changes to SALES / AutoOrder.
 * Two independent thread-bound transactions and explicit barriers; each test
 * checks both the state transition and its physical/domain side effects.
 */
@SpringBootTest
@TestPropertySource(properties={
        "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class OrganizationLocationR11ConcurrencyQualificationTest {
    @Autowired AuthenticatedOrganizationLocations locations;
    @Autowired AuthenticatedInventoryCommands inventory;
    @Autowired AuthenticatedSalesCommands sales;
    @Autowired AutoOrderService checkout;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;

    private record Fixture(UUID tenant, UUID owner, UUID staff, UUID role,
                           UUID first, UUID second, UUID item, String kato,
                           UUID offer, String slug) {}

    private VerifiedActor verified(UUID user) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }

    private void postgresOnly() throws Exception {
        try(var connection=source.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(java.util.Locale.ROOT).contains("postgresql"),
                    "Requires real PostgreSQL row locks");
        }
    }

    private Fixture fixture() {
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "R11 Concurrency LLP","R11 Concurrency","KZ"));
        var tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "org-r11-owner-"+UUID.randomUUID(),"R11 owner")).getId();
        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "org-r11-staff-"+UUID.randomUUID(),"R11 staff")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);

        var ownerActor=verified(owner);
        var role=roles.createRole(ownerActor,tenant,
                "R11_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "R11 shared warehouse and sales operator");
        for(DomainPermission permission:List.of(
                DomainPermission.ORGANIZATION_LOCATION_READ,
                DomainPermission.ORGANIZATION_LOCATION_WRITE,
                DomainPermission.INVENTORY_RECEIVE,
                DomainPermission.INVENTORY_ADJUST,
                DomainPermission.SALES_ORDER_CONFIRM,
                DomainPermission.INVENTORY_RESERVE)) {
            roles.grant(ownerActor,tenant,role.id(),permission);
        }
        roles.assign(ownerActor,tenant,staff,role.id());
        roles.enable(ownerActor,tenant);

        String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        var who=verified(staff);
        UUID first=locations.create(who,tenant,new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,
                "R11 Source warehouse",kato,null)).id();
        UUID second=locations.create(who,tenant,new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,
                "R11 Destination warehouse",kato,null)).id();
        UUID item=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item
                  (tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,item,"r11-"+UUID.randomUUID());
        String slug="org-r11-"+UUID.randomUUID();
        UUID offer=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
                VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,UUID.randomUUID(),slug);
        jdbc.update("""
                INSERT INTO crm.store_offer
                  (tenant_id,id,title,unit_price,currency,state,catalog_item_id)
                VALUES (?,?,'R11 Product',100,'KZT','PUBLISHED',?)
                """,tenant,offer,item);
        inventory.receive(who,tenant,first,item,"orgR11Receipt01",BigDecimal.TEN);
        return new Fixture(tenant,owner,staff,role.id(),first,second,item,kato,offer,slug);
    }

    private UUID placedOrder(Fixture f,int quantity) {
        return checkout.checkout(f.slug(),"org-r11-buyer-"+UUID.randomUUID()+"@example.kz",
                "orgR11Checkout01",List.of(new AutoOrderService.Line(f.offer(),quantity)))
                .order().orderId();
    }

    private BigDecimal balance(Fixture f,UUID warehouse,String field) {
        if(!field.equals("on_hand") && !field.equals("reserved"))
            throw new IllegalArgumentException("Unknown balance field");
        return jdbc.queryForObject("""
                SELECT %s FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """.formatted(field),BigDecimal.class,f.tenant(),warehouse,f.item());
    }

    private String orderState(Fixture f,UUID order) {
        return jdbc.queryForObject("""
                SELECT status FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,String.class,f.tenant(),order);
    }

    private long activeReservations(Fixture f,UUID order) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Long.class,f.tenant(),order);
    }

    private long auditActions(Fixture f,UUID location,String action) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.audit_event
                WHERE tenant_id=? AND entity_id=? AND action_code=?
                """,Long.class,f.tenant(),location,action);
    }

    private static void await(CountDownLatch latch) {
        try {
            if(!latch.await(15,TimeUnit.SECONDS))
                throw new IllegalStateException("Concurrency test barrier timed out");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted concurrency test",interrupted);
        }
    }

    @Test
    void postgresConfirmedOrderWinsThenDeactivationIsBlockedByReservation() throws Exception {
        postgresOnly();
        var f=fixture();
        UUID order=placedOrder(f,3);
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var confirm=pool.submit(()->tx.execute(s->{
                sales.confirm(verified(f.staff()),f.tenant(),order,f.first());
                held.countDown();
                await(release);
                return true;
            }));
            assertThat(held.await(15,TimeUnit.SECONDS)).isTrue();
            var deactivate=pool.submit(()->{
                started.countDown();
                return locations.deactivate(verified(f.staff()),f.tenant(),f.first(),0);
            });
            await(started);
            try {
                assertThatThrownBy(()->deactivate.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(confirm.get(20,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->deactivate.get(20,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(orderState(f,order)).isEqualTo("CONFIRMED");
        assertThat(activeReservations(f,order)).isEqualTo(1);
        assertThat(balance(f,f.first(),"reserved")).isEqualByComparingTo("3");
        assertThat(balance(f,f.first(),"on_hand")).isEqualByComparingTo("10");
        assertThat(locations.get(verified(f.staff()),f.tenant(),f.first()).status())
                .isEqualTo(OrganizationLocationEntity.Status.ACTIVE);
        assertThat(auditActions(f,f.first(),"ORG_LOCATION_DEACTIVATED")).isZero();
    }

    @Test
    void postgresDeactivationWinsThenNewConfirmationRollsBackToPlaced() throws Exception {
        postgresOnly();
        var f=fixture();
        UUID order=placedOrder(f,2);
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var pause=pool.submit(()->tx.execute(s->{
                locations.deactivate(verified(f.staff()),f.tenant(),f.first(),0);
                held.countDown();
                await(release);
                return true;
            }));
            await(held);
            var confirm=pool.submit(()->{
                started.countDown();
                return sales.confirm(verified(f.staff()),f.tenant(),order,f.first());
            });
            await(started);
            try {
                assertThatThrownBy(()->confirm.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(pause.get(20,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->confirm.get(20,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(orderState(f,order)).isEqualTo("PLACED");
        assertThat(activeReservations(f,order)).isZero();
        assertThat(balance(f,f.first(),"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=? AND event_type='CONFIRMED'
                """,Long.class,f.tenant(),order)).isZero();
        assertThat(locations.get(verified(f.staff()),f.tenant(),f.first()).status())
                .isEqualTo(OrganizationLocationEntity.Status.INACTIVE);
    }

    @Test
    void postgresCommittedTransferWinsAndPauseRetainsPhysicalLedgerConservation() throws Exception {
        postgresOnly();
        var f=fixture();
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var transfer=pool.submit(()->tx.execute(s->{
                var receipt=inventory.transfer(verified(f.staff()),f.tenant(),
                        f.first(),f.second(),f.item(),BigDecimal.valueOf(4),
                        "orgR11Transfer01");
                held.countDown();
                await(release);
                return receipt.id();
            }));
            await(held);
            var pause=pool.submit(()->{
                started.countDown();
                return locations.deactivate(verified(f.staff()),f.tenant(),f.second(),0);
            });
            await(started);
            try {
                assertThatThrownBy(()->pause.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            UUID receipt=transfer.get(20,TimeUnit.SECONDS);
            assertThat(pause.get(20,TimeUnit.SECONDS).status())
                    .isEqualTo(OrganizationLocationEntity.Status.INACTIVE);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.inv_stock_movement
                    WHERE tenant_id=? AND source_type='TRANSFER' AND source_id=?
                    """,Long.class,f.tenant(),receipt)).isEqualTo(2);
        }
        assertThat(balance(f,f.first(),"on_hand")).isEqualByComparingTo("6");
        assertThat(balance(f,f.second(),"on_hand")).isEqualByComparingTo("4");
        assertThat(balance(f,f.second(),"reserved")).isEqualByComparingTo("0");
    }

    @Test
    void postgresPausedTargetWinsThenTransferCannotCreateAnyLeg() throws Exception {
        postgresOnly();
        var f=fixture();
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var pause=pool.submit(()->tx.execute(s->{
                locations.deactivate(verified(f.staff()),f.tenant(),f.second(),0);
                held.countDown();
                await(release);
                return true;
            }));
            await(held);
            var transfer=pool.submit(()->{
                started.countDown();
                return inventory.transfer(verified(f.staff()),f.tenant(),
                        f.first(),f.second(),f.item(),BigDecimal.valueOf(4),
                        "orgR11Transfer02");
            });
            await(started);
            try {
                assertThatThrownBy(()->transfer.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(pause.get(20,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->transfer.get(20,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(balance(f,f.first(),"on_hand")).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND source_type='TRANSFER'
                """,Long.class,f.tenant())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=?
                """,Long.class,f.tenant(),f.second())).isZero();
    }

    @Test
    void postgresReceiptWinsThenDeactivationWaitsAndPreservesPostedMovement() throws Exception {
        postgresOnly();
        var f=fixture();
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var receipt=pool.submit(()->tx.execute(s->{
                inventory.receive(verified(f.staff()),f.tenant(),f.first(),f.item(),
                        "orgR14ReceiptRace01",BigDecimal.valueOf(2));
                held.countDown();
                await(release);
                return true;
            }));
            await(held);
            var deactivate=pool.submit(()->{
                started.countDown();
                return locations.deactivate(verified(f.staff()),f.tenant(),f.first(),0);
            });
            await(started);
            try {
                assertThatThrownBy(()->deactivate.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(receipt.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(deactivate.get(20,TimeUnit.SECONDS).status())
                    .isEqualTo(OrganizationLocationEntity.Status.INACTIVE);
        }
        assertThat(balance(f,f.first(),"on_hand")).isEqualByComparingTo("12");
        assertThat(balance(f,f.first(),"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=?
                  AND request_key='orgR14ReceiptRace01' AND reason_code='RECEIPT'
                """,Long.class,f.tenant(),f.first(),f.item())).isEqualTo(1);
        assertThat(auditActions(f,f.first(),"ORG_LOCATION_DEACTIVATED")).isEqualTo(1);
        assertThatThrownBy(()->inventory.receive(verified(f.staff()),f.tenant(),
                f.first(),f.item(),"orgR14ReceiptAfter",BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Active tenant WAREHOUSE required");
        assertThat(balance(f,f.first(),"on_hand")).isEqualByComparingTo("12");
    }

    @Test
    void postgresDeactivationWinsThenReceiptCannotInitializeOrPost() throws Exception {
        postgresOnly();
        var f=fixture();
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var deactivate=pool.submit(()->tx.execute(s->{
                locations.deactivate(verified(f.staff()),f.tenant(),f.second(),0);
                held.countDown();
                await(release);
                return true;
            }));
            await(held);
            var receipt=pool.submit(()->{
                started.countDown();
                return inventory.receive(verified(f.staff()),f.tenant(),f.second(),
                        f.item(),"orgR14BlockedReceipt",BigDecimal.ONE);
            });
            await(started);
            try {
                assertThatThrownBy(()->receipt.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(deactivate.get(20,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->receipt.get(20,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=?
                """,Long.class,f.tenant(),f.second())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=?
                """,Long.class,f.tenant(),f.second())).isZero();
        assertThat(auditActions(f,f.second(),"ORG_LOCATION_DEACTIVATED")).isEqualTo(1);
    }

    @Test
    void postgresSameVersionCompetingProfilesOnlyOneMutationAndAuditCommit() throws Exception {
        postgresOnly();
        var f=fixture();
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var winner=pool.submit(()->tx.execute(s->{
                locations.update(verified(f.staff()),f.tenant(),f.first(),
                        new LocationUpdateRequest("First update",f.kato(),null,0));
                held.countDown();
                await(release);
                return true;
            }));
            await(held);
            var loser=pool.submit(()->{
                started.countDown();
                return locations.update(verified(f.staff()),f.tenant(),f.first(),
                        new LocationUpdateRequest("Second update",f.kato(),null,0));
            });
            await(started);
            try {
                assertThatThrownBy(()->loser.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(winner.get(20,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->loser.get(20,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        var current=locations.get(verified(f.staff()),f.tenant(),f.first());
        assertThat(current.name()).isEqualTo("First update");
        assertThat(current.rowVersion()).isEqualTo(1);
        assertThat(auditActions(f,f.first(),"ORG_LOCATION_UPDATED")).isEqualTo(1);
    }

    @Test
    void postgresRevokeWaitsForAdmittedMutationAndSubsequentWriteIsDenied() throws Exception {
        postgresOnly();
        var f=fixture();
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var operation=pool.submit(()->tx.execute(s->{
                locations.deactivate(verified(f.staff()),f.tenant(),f.second(),0);
                held.countDown();
                await(release);
                return true;
            }));
            await(held);
            var revoke=pool.submit(()->{
                started.countDown();
                roles.revoke(verified(f.owner()),f.tenant(),f.role(),
                        DomainPermission.ORGANIZATION_LOCATION_WRITE);
                return true;
            });
            await(started);
            try {
                assertThatThrownBy(()->revoke.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(operation.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(locations.get(verified(f.staff()),f.tenant(),f.second()).status())
                .isEqualTo(OrganizationLocationEntity.Status.INACTIVE);
        assertThat(auditActions(f,f.second(),"ORG_LOCATION_DEACTIVATED")).isEqualTo(1);
        assertThatThrownBy(()->locations.update(verified(f.staff()),f.tenant(),f.second(),
                new LocationUpdateRequest("Should not change",f.kato(),null,1)))
                .isInstanceOf(SecurityException.class);
        assertThat(auditActions(f,f.second(),"ORG_LOCATION_UPDATED")).isZero();
    }
}
