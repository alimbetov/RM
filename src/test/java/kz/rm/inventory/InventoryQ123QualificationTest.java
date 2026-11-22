package kz.rm.inventory;

import kz.rm.iam.*;
import kz.rm.inventory.dto.StockSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/** INV-Q1/Q2/Q3: real authenticated service entrypoints, no direct stock setup. */
@SpringBootTest
class InventoryQ123QualificationTest {
    @Autowired AuthenticatedInventoryCommands commands;
    @Autowired AuthenticatedInventoryQueries queries;
    @Autowired AuthenticatedInventorySettings settings;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource source;

    private record Tenant(UUID tenant,UUID staff,UUID location,UUID item,
                          UUID owner,UUID roleId) {}

    private UUID user(String prefix) {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                prefix+"-"+UUID.randomUUID(),"Inventory operator")).getId();
    }
    private VerifiedActor verified(UUID user) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token).header("alg","RS256")
                .subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(360)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }
    private Tenant tenant(UUID staff,DomainPermission... permits) {
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Q123 Inventory LLP","Inventory","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID owner=user("inv-owner");
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        UUID t=workspace.getId(),location=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",t);
        String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        jdbc.update("""
                INSERT INTO public.org_location
                 (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
                VALUES (?,?,'WAREHOUSE','Inventory R1 warehouse',?,'ACTIVE',CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,0)
                """,location,t,kato);
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','DRAFT',CURRENT_TIMESTAMP)
                """,t,item,"inv-"+UUID.randomUUID());
        var ownerActor=verified(owner);
        var role=roles.createRole(ownerActor,t,"WAREHOUSE_R1","Warehouse R1 qualified staff");
        for(var permission:permits)roles.grant(ownerActor,t,role.id(),permission);
        roles.assign(ownerActor,t,staff,role.id());
        roles.enable(ownerActor,t);
        return new Tenant(t,staff,location,item,owner,role.id());
    }
    private long balanceCount(Tenant t) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,Long.class,t.tenant(),t.location(),t.item());
    }
    private BigDecimal stock(Tenant t) {
        return jdbc.queryForObject("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,t.tenant(),t.location(),t.item());
    }

    @Test void firstReceiptCreatesExactlyOnePositionAndOldReceiptReplaysAfterArchive() {
        UUID staff=user("inv-first");
        Tenant a=tenant(staff,DomainPermission.INVENTORY_RECEIVE,
                DomainPermission.INVENTORY_ADJUST,DomainPermission.INVENTORY_STOCK_READ,
                DomainPermission.INVENTORY_SETTINGS_MANAGE);
        Tenant b=tenant(staff,DomainPermission.INVENTORY_STOCK_READ);
        VerifiedActor actor=verified(staff);
        assertThat(balanceCount(a)).isZero();

        var initial=settings.initialize(actor,a.tenant());
        assertThat(initial.reservationMode())
                .isEqualTo(AuthenticatedInventorySettings.ReservationMode.MANDATORY_ON_CONFIRM);
        assertThat(initial.pickingReadiness())
                .isEqualTo(AuthenticatedInventorySettings.PickingReadiness.NOT_IMPLEMENTED);
        assertThat(initial.valuationReadiness())
                .isEqualTo(AuthenticatedInventorySettings.ValuationReadiness.NOT_CONFIGURED);
        var approved=settings.approveCostPolicy(actor,a.tenant(),
                AuthenticatedInventorySettings.ValuationMethod.FIFO,"KZT",initial.rowVersion());
        assertThat(approved.valuationReadiness())
                .isEqualTo(AuthenticatedInventorySettings.ValuationReadiness.POLICY_ONLY);
        assertThat(settings.get(actor,a.tenant()).reservationMode())
                .isEqualTo(AuthenticatedInventorySettings.ReservationMode.MANDATORY_ON_CONFIRM);

        var first=commands.receive(actor,a.tenant(),a.location(),a.item(),
                "firstReceipt001",new BigDecimal("10"));
        assertThat(first.before()).isEqualByComparingTo("0");
        assertThat(first.after()).isEqualByComparingTo("10");
        assertThat(balanceCount(a)).isEqualTo(1);
        assertThat(commands.receive(actor,a.tenant(),a.location(),a.item(),
                "firstReceipt001",new BigDecimal("10")).id()).isEqualTo(first.id());
        assertThat(queries.search(actor,a.tenant(),
                new StockSearch(a.location(),a.item(),false),0,10).getContent())
                .singleElement().satisfies(row->{
                    assertThat(row.onHand()).isEqualByComparingTo("10");
                    assertThat(row.reserved()).isEqualByComparingTo("0");
                });
        assertThatThrownBy(()->commands.receive(actor,b.tenant(),b.location(),b.item(),
                "foreignWrite001",BigDecimal.ONE)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->settings.get(actor,b.tenant())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->commands.receive(actor,a.tenant(),b.location(),a.item(),
                "foreignLocation001",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->commands.correct(actor,a.tenant(),a.location(),b.item(),
                "foreignSku001",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(queries.search(actor,a.tenant(),new StockSearch(b.location(),null,false),
                0,10).getTotalElements()).isZero();

        jdbc.update("""
                UPDATE crm.cat_item SET state='ARCHIVED'
                WHERE tenant_id=? AND id=?
                """,a.tenant(),a.item());
        assertThatThrownBy(()->commands.receive(actor,a.tenant(),a.location(),a.item(),
                "archivedReceipt01",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands.receive(actor,a.tenant(),a.location(),a.item(),
                "firstReceipt001",new BigDecimal("10")).id()).isEqualTo(first.id());
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(balanceCount(b)).isZero();
    }

    @Test void serviceInactiveWarehouseAndMissingCorrectionCannotCreateStock() {
        UUID staff=user("inv-negative");
        Tenant t=tenant(staff,DomainPermission.INVENTORY_RECEIVE,
                DomainPermission.INVENTORY_ADJUST);
        VerifiedActor actor=verified(staff);
        assertThatThrownBy(()->commands.correct(actor,t.tenant(),t.location(),t.item(),
                "correctionAbsent01",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceCount(t)).isZero();

        jdbc.update("UPDATE crm.cat_item SET kind='SERVICE' WHERE tenant_id=? AND id=?",
                t.tenant(),t.item());
        assertThatThrownBy(()->commands.receive(actor,t.tenant(),t.location(),t.item(),
                "serviceReceive01",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceCount(t)).isZero();
        jdbc.update("UPDATE crm.cat_item SET kind='PRODUCT' WHERE tenant_id=? AND id=?",
                t.tenant(),t.item());

        jdbc.update("UPDATE public.org_location SET status='INACTIVE' WHERE workspace_id=? AND id=?",
                t.tenant(),t.location());
        assertThatThrownBy(()->commands.receive(actor,t.tenant(),t.location(),t.item(),
                "inactiveReceipt01",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("""
                UPDATE public.org_location SET status='ACTIVE',location_type='BRANCH'
                WHERE workspace_id=? AND id=?
                """,t.tenant(),t.location());
        assertThatThrownBy(()->commands.receive(actor,t.tenant(),t.location(),t.item(),
                "branchReceipt001",BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceCount(t)).isZero();
    }

    @Test void postgresSettingsReadFencesConcurrentRoleRevocation() throws Exception {
        try(var con=source.getConnection()){
            Assumptions.assumeTrue(con.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        UUID staff=user("inv-read-fence");
        Tenant t=tenant(staff,DomainPermission.INVENTORY_SETTINGS_MANAGE);
        VerifiedActor staffActor=verified(staff);
        VerifiedActor ownerActor=verified(t.owner());
        settings.initialize(staffActor,t.tenant());
        var admission=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var read=pool.submit(()->new TransactionTemplate(transactionManager).execute(tx->{
                var result=settings.get(staffActor,t.tenant());
                admission.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("Read fence was never released");
                }catch(InterruptedException ex){
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return result;
            }));
            assertThat(admission.await(15,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{
                roles.revoke(ownerActor,t.tenant(),t.roleId(),
                        DomainPermission.INVENTORY_SETTINGS_MANAGE);
                return true;
            });
            try{
                assertThatThrownBy(()->revoke.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            }finally{
                release.countDown();
            }
            assertThat(read.get(20,TimeUnit.SECONDS).reservationMode())
                    .isEqualTo(AuthenticatedInventorySettings.ReservationMode.MANDATORY_ON_CONFIRM);
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThatThrownBy(()->settings.get(staffActor,t.tenant()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void concurrentFirstReceiptsDoNotDoubleCreateOrLoseQuantity() throws Exception {
        UUID staff=user("inv-concurrent");
        Tenant t=tenant(staff,DomainPermission.INVENTORY_RECEIVE);
        VerifiedActor actor=verified(staff);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return commands.receive(actor,t.tenant(),t.location(),t.item(),
                        "firstConcurrentA",new BigDecimal("3"));
            });
            var b=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return commands.receive(actor,t.tenant(),t.location(),t.item(),
                        "firstConcurrentB",new BigDecimal("4"));
            });
            start.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS).id())
                    .isNotEqualTo(b.get(30,TimeUnit.SECONDS).id());
        }
        assertThat(balanceCount(t)).isEqualTo(1L);
        assertThat(stock(t)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,Long.class,t.tenant(),t.location(),t.item())).isEqualTo(2L);
    }
}
