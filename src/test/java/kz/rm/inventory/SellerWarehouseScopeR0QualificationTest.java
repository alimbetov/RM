package kz.rm.inventory;

import kz.rm.iam.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * SELLER-EDIT-R0: strict warehouse admission for employee receipt/correction.
 * Business writes MUST go through AuthenticatedInventoryCommands; direct SQL
 * here is test fixture construction and independent ledger/oracle reading.
 */
@SpringBootTest(properties = {
        "rm.mvp.service-mode.enabled=true",
        "rm.currency.commerce-admission.enabled=true",
        "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class SellerWarehouseScopeR0QualificationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired WarehouseStaffScopeService scopes;
    @Autowired AuthenticatedInventoryCommands inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;

    private record Fixture(UUID tenant, UUID staffId, UUID roleId, UUID warehouseA,
                           UUID warehouseB, UUID itemId, VerifiedActor owner, VerifiedActor staff) {}

    private VerifiedActor actor(UUID user) {
        String subject=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("seller-edit-r0-synthetic");
    }

    private Fixture setup() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Seller warehouse R0 LLP","Service qualification","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID ownerId=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"r0-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID staffId=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"r0-staff-"+UUID.randomUUID(),"Warehouse employee")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),ownerId,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),staffId,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);

        String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        UUID warehouseA=UUID.randomUUID(),warehouseB=UUID.randomUUID(),itemId=UUID.randomUUID();
        for(UUID warehouse:new UUID[]{warehouseA,warehouseB}) {
            jdbc.update("""
                    INSERT INTO public.org_location
                     (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
                    VALUES (?,?,'WAREHOUSE','R0 warehouse',?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                    """,warehouse,tenant,kato);
        }
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,itemId,"R0-"+UUID.randomUUID());

        VerifiedActor owner=actor(ownerId),staff=actor(staffId);
        var role=roles.createRole(owner,tenant,
                "R0_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Scoped inventory employee");
        roles.grant(owner,tenant,role.id(),DomainPermission.INVENTORY_RECEIVE);
        roles.grant(owner,tenant,role.id(),DomainPermission.INVENTORY_ADJUST);
        roles.assign(owner,tenant,staffId,role.id());
        roles.enable(owner,tenant);
        return new Fixture(tenant,staffId,role.id(),warehouseA,warehouseB,itemId,owner,staff);
    }

    private static String key(){return "r0-"+UUID.randomUUID();}

    private BigDecimal onHand(Fixture f,UUID warehouse) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(on_hand),0) FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,f.tenant(),warehouse,f.itemId());
    }

    private int movementCount(Fixture f) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND item_id=?
                """,Integer.class,f.tenant(),f.itemId());
    }

    private void pgOnly() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .contains("PostgreSQL"),"Requires real PostgreSQL row locks");
        }
    }

    private static void await(CountDownLatch signal) {
        try {
            if(!signal.await(15,TimeUnit.SECONDS))throw new AssertionError("Barrier timed out");
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test void sellerR0ReceiptAndCorrectionRequireAssignedWarehouseDespiteTenantGrants() {
        Fixture f=setup();
        assertThatThrownBy(()->inventory.receive(f.staff(),f.tenant(),f.warehouseA(),
                f.itemId(),key(),new BigDecimal("5")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThatThrownBy(()->inventory.correct(f.staff(),f.tenant(),f.warehouseA(),
                f.itemId(),key(),new BigDecimal("1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThat(movementCount(f)).isZero();
        assertThat(onHand(f,f.warehouseA())).isEqualByComparingTo("0");

        scopes.assign(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
        assertThat(inventory.receive(f.staff(),f.tenant(),f.warehouseA(),f.itemId(),
                key(),new BigDecimal("5")).after()).isEqualByComparingTo("5");
        assertThat(inventory.correct(f.staff(),f.tenant(),f.warehouseA(),f.itemId(),
                key(),new BigDecimal("-1")).after()).isEqualByComparingTo("4");
        assertThat(movementCount(f)).isEqualTo(2);
        assertThat(onHand(f,f.warehouseA())).isEqualByComparingTo("4");

        assertThatThrownBy(()->inventory.receive(f.staff(),f.tenant(),f.warehouseB(),
                f.itemId(),key(),new BigDecimal("7")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThatThrownBy(()->inventory.correct(f.staff(),f.tenant(),f.warehouseB(),
                f.itemId(),key(),new BigDecimal("1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThat(onHand(f,f.warehouseB())).isEqualByComparingTo("0");
        assertThat(movementCount(f)).isEqualTo(2);
    }

    @Test void sellerR0WarehouseScopeAndRoleRevocationBothBlockNewInventoryWrites() {
        Fixture f=setup();
        scopes.assign(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
        inventory.receive(f.staff(),f.tenant(),f.warehouseA(),f.itemId(),key(),
                new BigDecimal("3"));
        scopes.revoke(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
        assertThatThrownBy(()->inventory.receive(f.staff(),f.tenant(),f.warehouseA(),
                f.itemId(),key(),new BigDecimal("2")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThatThrownBy(()->inventory.correct(f.staff(),f.tenant(),f.warehouseA(),
                f.itemId(),key(),new BigDecimal("-1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        scopes.assign(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
        roles.revoke(f.owner(),f.tenant(),f.roleId(),DomainPermission.INVENTORY_RECEIVE);
        assertThatThrownBy(()->inventory.receive(f.staff(),f.tenant(),f.warehouseA(),
                f.itemId(),key(),BigDecimal.ONE)).isInstanceOf(SecurityException.class);
        assertThat(movementCount(f)).isEqualTo(1);
        assertThat(onHand(f,f.warehouseA())).isEqualByComparingTo("3");
    }

    @Test void sellerR0PostgresAdmittedReceiptCommitsBeforeWarehouseScopeRevocation() throws Exception {
        pgOnly();
        Fixture f=setup();
        scopes.assign(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
        var admitted=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var attempted=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var receipt=pool.submit(()->tx.execute(status->{
                inventory.receive(f.staff(),f.tenant(),f.warehouseA(),f.itemId(),
                        key(),new BigDecimal("2"));
                admitted.countDown();
                await(finish);
                return true;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{
                attempted.countDown();
                scopes.revoke(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
                return true;
            });
            assertThat(attempted.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->revoke.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(receipt.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
        } finally { finish.countDown(); }
        assertThat(onHand(f,f.warehouseA())).isEqualByComparingTo("2");
        assertThat(movementCount(f)).isEqualTo(1);
        assertThatThrownBy(()->inventory.receive(f.staff(),f.tenant(),f.warehouseA(),
                f.itemId(),key(),BigDecimal.ONE)).isInstanceOf(SecurityException.class);
    }

    @Test void sellerR0PostgresScopeRevocationCommitsBeforeReceiptWithZeroLedger() throws Exception {
        pgOnly();
        Fixture f=setup();
        scopes.assign(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
        var locked=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->tx.execute(status->{
                scopes.revoke(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
                locked.countDown();
                await(finish);
                return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var receipt=pool.submit(()->{
                started.countDown();
                try {
                    inventory.receive(f.staff(),f.tenant(),f.warehouseA(),f.itemId(),
                            key(),BigDecimal.ONE);
                    return true;
                } catch(SecurityException denied) {return false;}
            });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->receipt.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally { finish.countDown(); }
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(receipt.get(20,TimeUnit.SECONDS)).isFalse();
        } finally {finish.countDown();}
        assertThat(onHand(f,f.warehouseA())).isEqualByComparingTo("0");
        assertThat(movementCount(f)).isZero();
    }

    @Test void sellerR0PostgresRoleRevocationCommitsBeforeReceiptWithZeroLedger() throws Exception {
        pgOnly();
        Fixture f=setup();
        scopes.assign(f.owner(),f.tenant(),f.warehouseA(),f.staffId());
        var locked=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->tx.execute(status->{
                roles.revoke(f.owner(),f.tenant(),f.roleId(),DomainPermission.INVENTORY_RECEIVE);
                locked.countDown();
                await(finish);
                return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var receipt=pool.submit(()->{
                started.countDown();
                try {
                    inventory.receive(f.staff(),f.tenant(),f.warehouseA(),f.itemId(),
                            key(),BigDecimal.ONE);
                    return true;
                } catch(SecurityException denied) {return false;}
            });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->receipt.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally { finish.countDown(); }
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(receipt.get(20,TimeUnit.SECONDS)).isFalse();
        } finally {finish.countDown();}
        assertThat(onHand(f,f.warehouseA())).isEqualByComparingTo("0");
        assertThat(movementCount(f)).isZero();
    }
}
