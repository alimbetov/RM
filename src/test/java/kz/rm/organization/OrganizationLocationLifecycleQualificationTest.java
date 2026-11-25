package kz.rm.organization;

import kz.rm.iam.*;
import kz.rm.inventory.AuthenticatedInventoryCommands;
import kz.rm.inventory.InventoryMovementService;
import kz.rm.organization.dto.LocationCreateRequest;
import kz.rm.organization.dto.LocationUpdateRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/** R1.1 integration contract. PostgreSQL race uses separate actual connections. */
@SpringBootTest
class OrganizationLocationLifecycleQualificationTest {
    @Autowired AuthenticatedOrganizationLocations locations;
    @Autowired AuthenticatedInventoryCommands inventory;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private record Tenant(UUID id, UUID employee, UUID owner) {}

    private Tenant tenant() {
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Location Lifecycle LLP","Lifecycle","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        var owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "org-r1-owner-"+UUID.randomUUID(),"Lifecycle owner"));
        var employee=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "org-r1-employee-"+UUID.randomUUID(),"Lifecycle staff"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),
                owner.getId(),OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),
                employee.getId(),OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());

        var ownerActor=actor(owner.getId());
        var role=roles.createRole(ownerActor,workspace.getId(),
                "ORG_"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(),
                "Location and stock operator");
        for(var permission:List.of(DomainPermission.ORGANIZATION_LOCATION_WRITE,
                DomainPermission.ORGANIZATION_LOCATION_READ,
                DomainPermission.INVENTORY_RECEIVE,DomainPermission.INVENTORY_ADJUST))
            roles.grant(ownerActor,workspace.getId(),role.id(),permission);
        roles.assign(ownerActor,workspace.getId(),employee.getId(),role.id());
        roles.enable(ownerActor,workspace.getId());
        return new Tenant(workspace.getId(),employee.getId(),owner.getId());
    }

    private VerifiedActor actor(UUID id) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                        .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("test-only");
    }

    private String kato() {
        return jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
    }

    private UUID product(Tenant t) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,t.id(),id,"PRODUCT-"+UUID.randomUUID());
        return id;
    }

    private int audit(Tenant t,UUID location) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.audit_event
                WHERE tenant_id=? AND entity_id=? AND action_code LIKE 'ORG_LOCATION_%'
                """,Integer.class,t.id(),location);
    }

    @Test
    void urR32OrganizationLocationGrantRevocationAndForeignTenantHaveZeroEffects() {
        var a=tenant();
        var b=tenant();
        int before=jdbc.queryForObject("""
                SELECT count(*) FROM public.org_location WHERE workspace_id=?
                """,Integer.class,a.id());
        var location=locations.create(actor(a.employee()),a.id(),
                new LocationCreateRequest(OrganizationLocationEntity.Type.WAREHOUSE,
                        "UR R3.2 depot",kato(),null));
        assertThat(location.id()).isNotNull();
        assertThat(audit(a,location.id())).isEqualTo(1);
        assertThatThrownBy(()->locations.create(actor(b.employee()),a.id(),
                new LocationCreateRequest(OrganizationLocationEntity.Type.WAREHOUSE,
                        "Foreign attempt",kato(),null))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->locations.update(actor(b.employee()),a.id(),location.id(),
                new LocationUpdateRequest("Foreign rename",kato(),null,0)))
                .isInstanceOf(SecurityException.class);
        assertThat(audit(a,location.id())).isEqualTo(1);

        UUID role=jdbc.queryForObject("""
                SELECT id FROM public.iam_tenant_role
                WHERE tenant_id=? AND role_code LIKE 'ORG_%'
                """,UUID.class,a.id());
        roles.revoke(actor(a.owner()),a.id(),role,DomainPermission.ORGANIZATION_LOCATION_WRITE);
        assertThatThrownBy(()->locations.update(actor(a.employee()),a.id(),location.id(),
                new LocationUpdateRequest("Revoked rename",kato(),null,0)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->locations.create(actor(a.employee()),a.id(),
                new LocationCreateRequest(OrganizationLocationEntity.Type.WAREHOUSE,
                        "Revoked create",kato(),null))).isInstanceOf(SecurityException.class);
        assertThat(locations.get(actor(a.employee()),a.id(),location.id()).name())
                .isEqualTo("UR R3.2 depot");
        assertThat(audit(a,location.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.org_location WHERE workspace_id=?
                """,Integer.class,a.id())).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.org_location WHERE workspace_id=?
                """,Integer.class,b.id())).isZero();
    }

    @Test
    void typedCreateUpdateNoOpStaleAndUnicodeAreAuditedAtomically() {
        var t=tenant();
        var who=actor(t.employee());
        String code=kato();
        var created=locations.create(who,t.id(),
                new LocationCreateRequest(OrganizationLocationEntity.Type.WAREHOUSE,
                        "  Cafe\u0301  ",code,"  Warehouse  "));
        assertThat(created.name()).isEqualTo("Café");
        assertThat(created.addressLine()).isEqualTo("Warehouse");
        assertThat(created.rowVersion()).isZero();
        assertThat(audit(t,created.id())).isEqualTo(1);

        var changed=locations.update(who,t.id(),created.id(),
                new LocationUpdateRequest("Café depot",code,"",0));
        assertThat(changed.name()).isEqualTo("Café depot");
        assertThat(changed.addressLine()).isNull();
        assertThat(changed.rowVersion()).isEqualTo(1);
        assertThat(audit(t,created.id())).isEqualTo(2);
        // PostgreSQL stores timestamp microseconds; compare persisted values, not
        // the unflushed in-memory nanosecond precision of Instant.now().
        var persistedTimestamp=locations.get(who,t.id(),created.id()).updatedAt();

        var same=locations.update(who,t.id(),created.id(),
                new LocationUpdateRequest(" Café depot ",code,null,1));
        assertThat(same.rowVersion()).isEqualTo(1);
        assertThat(same.updatedAt()).isEqualTo(persistedTimestamp);
        assertThat(audit(t,created.id())).isEqualTo(2);

        assertThatThrownBy(()->locations.update(who,t.id(),created.id(),
                new LocationUpdateRequest("new",code,null,0)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->locations.create(who,t.id(),
                new LocationCreateRequest(OrganizationLocationEntity.Type.BRANCH,
                        "bad\u202Ename",code,null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->locations.update(who,t.id(),created.id(),
                new LocationUpdateRequest("new","00 1",null,1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stockBearingWarehouseCanPauseButCannotCloseUntilLawfulWriteOff() {
        var t=tenant();
        var who=actor(t.employee());
        var created=locations.create(who,t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"Physical depot",kato(),null));
        UUID item=product(t);
        var receipt=inventory.receive(who,t.id(),created.id(),item,
                "receiveOrgR101",BigDecimal.TEN);
        var paused=locations.deactivate(who,t.id(),created.id(),created.rowVersion());
        assertThat(paused.status()).isEqualTo(OrganizationLocationEntity.Status.INACTIVE);
        assertThat(paused.rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->inventory.receive(who,t.id(),created.id(),item,
                "receiveOrgR102",BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(inventory.receive(who,t.id(),created.id(),item,
                "receiveOrgR101",BigDecimal.TEN).id()).isEqualTo(receipt.id());
        assertThatThrownBy(()->locations.close(who,t.id(),created.id(),1))
                .isInstanceOf(IllegalStateException.class);
        var active=locations.reactivate(who,t.id(),created.id(),1);
        assertThat(active.status()).isEqualTo(OrganizationLocationEntity.Status.ACTIVE);
        inventory.writeOff(who,t.id(),created.id(),item,"writeOffOrgR1",
                BigDecimal.TEN,InventoryMovementService.WriteOffCause.DAMAGE);
        var inactive=locations.deactivate(who,t.id(),created.id(),2);
        var closed=locations.close(who,t.id(),created.id(),inactive.rowVersion());
        assertThat(closed.status()).isEqualTo(OrganizationLocationEntity.Status.CLOSED);
        assertThat(locations.close(who,t.id(),created.id(),closed.rowVersion())
                .rowVersion()).isEqualTo(closed.rowVersion());
        assertThatThrownBy(()->locations.reactivate(who,t.id(),created.id(),closed.rowVersion()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(audit(t,created.id())).isEqualTo(5); // CREATE, DEACTIVATE, REACTIVATE, DEACTIVATE, CLOSE
    }

    @Test
    void reservationsAndOpenStocktakeBlockDeactivation() {
        var t=tenant();
        var who=actor(t.employee());
        var loc=locations.create(who,t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"Audit warehouse",kato(),null));
        UUID item=product(t);
        inventory.receive(who,t.id(),loc.id(),item,"receiveOrgR103",BigDecimal.TEN);
        jdbc.update("""
                UPDATE crm.inv_stock_balance SET reserved=2
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,t.id(),loc.id(),item);
        assertThatThrownBy(()->locations.deactivate(who,t.id(),loc.id(),0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active inventory commitments");
        jdbc.update("""
                UPDATE crm.inv_stock_balance SET reserved=0
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,t.id(),loc.id(),item);

        var count=inventory.startStocktake(who,t.id(),loc.id(),List.of(item),"stocktakeOrgR1");
        assertThatThrownBy(()->locations.deactivate(who,t.id(),loc.id(),0))
                .isInstanceOf(IllegalStateException.class);
        inventory.cancelStocktake(who,t.id(),count.id());
        assertThat(locations.deactivate(who,t.id(),loc.id(),0).status())
                .isEqualTo(OrganizationLocationEntity.Status.INACTIVE);
    }

    @Test
    void katoCannotRewriteWarehouseHistoryAndCrossTenantUpdatesAreScoped() {
        var a=tenant();
        var b=tenant();
        var who=actor(a.employee());
        var location=locations.create(who,a.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"Warehouse A",kato(),null));
        var alt=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 AND code<>?
                ORDER BY code LIMIT 1
                """,String.class,kato());
        var renamed=locations.update(who,a.id(),location.id(),
                new LocationUpdateRequest("Warehouse A",alt,null,0));
        assertThat(renamed.katoCode()).isEqualTo(alt);
        UUID item=product(a);
        inventory.receive(who,a.id(),location.id(),item,"receiveOrgR104",BigDecimal.ONE);
        inventory.writeOff(who,a.id(),location.id(),item,"writeOffOrgR2",
                BigDecimal.ONE,InventoryMovementService.WriteOffCause.DAMAGE);
        assertThatThrownBy(()->locations.update(who,a.id(),location.id(),
                new LocationUpdateRequest("Moved history",kato(),null,1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KATO_CHANGE_BLOCKED_BY_HISTORY");
        assertThat(locations.update(who,a.id(),location.id(),
                new LocationUpdateRequest("Readable address",alt,null,1)).rowVersion())
                .isEqualTo(2);
        assertThatThrownBy(()->locations.update(who,b.id(),location.id(),
                new LocationUpdateRequest("Foreign",alt,null,1)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->locations.update(actor(b.employee()),b.id(),location.id(),
                new LocationUpdateRequest("Foreign",alt,null,1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateRollbackDoesNotPersistAuditOrProfile() {
        var t=tenant();
        var who=actor(t.employee());
        var loc=locations.create(who,t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.BRANCH,"Main branch",kato(),null));
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(s->{
            locations.update(who,t.id(),loc.id(),
                    new LocationUpdateRequest("Uncommitted",kato(),null,0));
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(locations.get(who,t.id(),loc.id()).name()).isEqualTo("Main branch");
        assertThat(locations.get(who,t.id(),loc.id()).rowVersion()).isZero();
        assertThat(audit(t,loc.id())).isEqualTo(1);
    }

    @Test
    void postgresDeactivationFencesConcurrentReceipt() throws Exception {
        try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var loc=locations.create(actor(t.employee()),t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"Racing location",kato(),null));
        UUID item=product(t);
        var held=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var disable=pool.submit(()->tx.execute(s->{
                locations.deactivate(actor(t.employee()),t.id(),loc.id(),0);
                held.countDown();
                try {
                    if(!finish.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Timeout");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var started=new CountDownLatch(1);
            var receive=pool.submit(()->{
                started.countDown();
                try {
                    inventory.receive(actor(t.employee()),t.id(),loc.id(),item,
                            "receiptRaceOrg1",BigDecimal.ONE);
                    return true;
                } catch(IllegalArgumentException denied) {
                    return false;
                }
            });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->receive.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(disable.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(receive.get(20,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=?
                """,Integer.class,t.id(),loc.id())).isZero();
    }

    /**
     * INV-R1.4: a committed pause must fence a later transfer even when
     * on_hand remains positive. The target cannot acquire an invented leg.
     */
    @Test
    void postgresDeactivateBeforeTransferRejectsBothLedgerLegs() throws Exception {
        try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var who=actor(t.employee());
        var source=locations.create(who,t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"Pause before transfer source",kato(),null));
        var target=locations.create(who,t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"Pause before transfer target",kato(),null));
        UUID item=product(t);
        inventory.receive(who,t.id(),source.id(),item,"r14PreTransferReceipt",BigDecimal.valueOf(5));
        var held=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var pause=pool.submit(()->tx.execute(s->{
                locations.deactivate(actor(t.employee()),t.id(),source.id(),0);
                held.countDown();
                try {
                    if(!finish.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Pause timeout");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var entered=new CountDownLatch(1);
            var transfer=pool.submit(()->{
                entered.countDown();
                try {
                    inventory.transfer(actor(t.employee()),t.id(),source.id(),target.id(),
                            item,BigDecimal.valueOf(2),"r14TransferAfterPause");
                    return true;
                } catch(IllegalArgumentException denied) {
                    return false;
                }
            });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->transfer.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(pause.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(transfer.get(20,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(locations.get(who,t.id(),source.id()).status())
                .isEqualTo(OrganizationLocationEntity.Status.INACTIVE);
        assertThat(jdbc.queryForObject("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,t.id(),source.id(),item)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_transfer
                WHERE tenant_id=? AND request_key='r14TransferAfterPause'
                """,Integer.class,t.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND reason_code IN ('TRANSFER_IN','TRANSFER_OUT')
                  AND (location_id=? OR location_id=?)
                """,Integer.class,t.id(),source.id(),target.id())).isZero();
    }

    /**
     * INV-R1.3/R1.4: warehouse status update waits for an admitted physical
     * transfer. After a full drain, the source can be paused without losing
     * ledger legs; the target remains active and receives exactly five units.
     */
    @Test
    void postgresTransferBeforeDeactivateCommitsAndPauseSeesDrainedStock() throws Exception {
        try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var who=actor(t.employee());
        var source=locations.create(who,t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"Drain before pause",kato(),null));
        var target=locations.create(who,t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"Drain target",kato(),null));
        UUID item=product(t);
        inventory.receive(who,t.id(),source.id(),item,"r14DrainReceipt",BigDecimal.valueOf(5));
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var transfer=pool.submit(()->tx.execute(s->{
                inventory.transfer(actor(t.employee()),t.id(),source.id(),target.id(),
                        item,BigDecimal.valueOf(5),"r14DrainTransfer");
                held.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Transfer timeout");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var started=new CountDownLatch(1);
            var pause=pool.submit(()->{
                started.countDown();
                return locations.deactivate(actor(t.employee()),t.id(),source.id(),0);
            });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->pause.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(transfer.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(pause.get(20,TimeUnit.SECONDS).status())
                    .isEqualTo(OrganizationLocationEntity.Status.INACTIVE);
        }
        assertThat(jdbc.queryForObject("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,t.id(),source.id(),item)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,t.id(),target.id(),item)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND source_type='TRANSFER'
                  AND reason_code IN ('TRANSFER_OUT','TRANSFER_IN')
                """,Integer.class,t.id())).isEqualTo(2);
    }

    @Test
    void r14ConcurrentProfileVersionCannotOverwriteCommittedPause() {
        var t=tenant();
        var who=actor(t.employee());
        var location=locations.create(who,t.id(),new LocationCreateRequest(
                OrganizationLocationEntity.Type.WAREHOUSE,"CAS pause warehouse",kato(),null));
        var paused=locations.deactivate(who,t.id(),location.id(),0);
        assertThat(paused.rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->locations.update(who,t.id(),location.id(),
                new LocationUpdateRequest("Stale warehouse rename",kato(),null,0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STALE_LOCATION_VERSION");
        assertThat(locations.get(who,t.id(),location.id()).name())
                .isEqualTo("CAS pause warehouse");
        assertThat(locations.get(who,t.id(),location.id()).rowVersion()).isEqualTo(1);
    }
}
