package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import kz.rm.inventory.AuthenticatedInventoryCommands;
import kz.rm.inventory.OrderInventoryPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import kz.rm.iam.OidcActorResolver;
import kz.rm.iam.VerifiedActor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class SalesInventoryRbacQualificationTest {
    @Autowired SalesInventoryCommandService commands;
    @Autowired AuthenticatedSalesCommands authenticatedCommands;
    @Autowired AuthenticatedInventoryCommands warehouse;
    @Autowired AutoOrderService checkout;
    @Autowired TenantRoleService roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantAuthorization authorization;
    @Autowired OrderInventoryPort crossDomainPort;
    @Autowired PlatformTransactionManager transactionManager;

    private VerifiedActor verified(UUID userId) {
        String subject=jdbc.queryForObject(
            "SELECT external_subject FROM iam_user_account WHERE id=?",
            String.class,userId);
        // Synthetic verified decoder ONLY in a test. Production uses JWKS/issuer/aud.
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
            .header("alg","RS256").subject(subject).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("test-only-token");
    }

    private record Context(UUID tenant,UUID owner,UUID backupOwner,UUID staff,
                           UUID location,UUID item,UUID offer,String slug) {}
    private UUID user(String label) {
        return users.saveAndFlush(new UserAccountEntity(
            UUID.randomUUID(),label+"-"+UUID.randomUUID(),label)).getId();
    }
    private Context context(int initialStock) {
        var org=organizations.saveAndFlush(new OrganizationEntity(
            UUID.randomUUID(),"Sales Inventory RBAC LLP","RBAC Shop","KZ"));
        var workspace=workspaces.saveAndFlush(
            WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID owner=user("owner"),backup=user("owner-backup"),staff=user("staff");
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            org.getId(),backup,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        String kato=jdbc.queryForObject("""
            SELECT code FROM ref_kato_territory
            WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
            """,String.class);
        UUID location=UUID.randomUUID(),item=UUID.randomUUID(),offer=UUID.randomUUID();
        String slug="rbac-shop-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO org_location
            (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
            VALUES (?,?,'WAREHOUSE','RBAC warehouse',?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
            """,location,workspace.getId(),kato);
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,workspace.getId(),item,"sku-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?, ?,0)
            """,workspace.getId(),location,item,initialStock);
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
            """,workspace.getId(),UUID.randomUUID(),slug);
        jdbc.update("""
            INSERT INTO crm.store_offer
            (tenant_id,id,title,unit_price,currency,state,catalog_item_id)
            VALUES (?,?,'RBAC Item',100,'KZT','PUBLISHED',?)
            """,workspace.getId(),offer,item);
        return new Context(workspace.getId(),owner,backup,staff,location,item,offer,slug);
    }
    private UUID order(Context c,int qty) {
        return checkout.checkout(c.slug(),"buyer-"+UUID.randomUUID()+"@example.kz",
            "checkout-"+UUID.randomUUID(),List.of(new AutoOrderService.Line(c.offer(),qty)))
            .order().orderId();
    }
    private void allow(Context c,DomainPermission... permissions) {
        var role=roles.createRole(c.owner(),c.tenant(),
            "OPS_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Warehouse and order operator");
        for(var permission:permissions)roles.grant(c.owner(),c.tenant(),role.id(),permission);
        roles.assign(c.owner(),c.tenant(),c.staff(),role.id());
        roles.assign(c.owner(),c.tenant(),c.backupOwner(),role.id());
        roles.enable(c.owner(),c.tenant());
    }
    private BigDecimal scalar(Context c,String field) {
        if(!field.equals("on_hand") && !field.equals("reserved"))
            throw new IllegalArgumentException("Unknown stock column");
        return jdbc.queryForObject("SELECT "+field+
             " FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",
             BigDecimal.class,c.tenant(),c.location(),c.item());
    }
    private String status(Context c,UUID order) {
        return jdbc.queryForObject("""
            SELECT status FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,String.class,c.tenant(),order);
    }

    @Test void confirmAndCancelAreAtomicAndRetriesAreIdempotent() {
        Context c=context(10);
        UUID id=order(c,3);
        assertThatThrownBy(()->commands.confirm(c.staff(),c.tenant(),id,c.location()))
            .isInstanceOf(SecurityException.class);
        assertThat(status(c,id)).isEqualTo("PLACED");
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
             DomainPermission.SALES_ORDER_CANCEL,DomainPermission.INVENTORY_RELEASE);
        var first=commands.confirm(c.staff(),c.tenant(),id,c.location());
        assertThat(first.affectedLines()).isEqualTo(1);
        assertThat(commands.confirm(c.staff(),c.tenant(),id,c.location()).affectedLines())
            .isZero();
        assertThat(status(c,id)).isEqualTo("CONFIRMED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("3");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("10");
        assertThat(commands.cancel(c.staff(),c.tenant(),id).affectedLines()).isEqualTo(1);
        assertThat(commands.cancel(c.staff(),c.tenant(),id).affectedLines()).isZero();
        assertThat(status(c,id)).isEqualTo("CANCELLED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
        assertThatThrownBy(()->commands.confirm(c.staff(),c.tenant(),id,c.location()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=?
            """,Integer.class,c.tenant(),id)).isEqualTo(3);
    }

    @Test void insufficientStockRollsBackStatusAndEveryReservation() {
        Context c=context(2);
        UUID id=order(c,3);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        assertThatThrownBy(()->commands.confirm(c.staff(),c.tenant(),id,c.location()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(status(c,id)).isEqualTo("PLACED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=? AND order_id=?
            """,Integer.class,c.tenant(),id)).isZero();
    }

    @Test void fulfillmentDebitsOnceAndRequiresBothDomainPermissions() {
        Context c=context(10);
        UUID id=order(c,4);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_FULFILL);
        commands.confirm(c.staff(),c.tenant(),id,c.location());
        assertThatThrownBy(()->commands.fulfill(c.staff(),c.tenant(),id))
            .isInstanceOf(SecurityException.class);
        assertThat(status(c,id)).isEqualTo("CONFIRMED");
        // A different functional role may add the missing permission.
        var role=roles.createRole(c.owner(),c.tenant(),"SHIP_"+UUID.randomUUID().toString()
                .replace("-","").substring(0,12).toUpperCase(),"Shipping");
        roles.grant(c.owner(),c.tenant(),role.id(),DomainPermission.INVENTORY_FULFILL);
        roles.assign(c.owner(),c.tenant(),c.staff(),role.id());
        assertThat(commands.fulfill(c.staff(),c.tenant(),id).affectedLines()).isEqualTo(1);
        assertThat(commands.fulfill(c.staff(),c.tenant(),id).affectedLines()).isZero();
        assertThat(status(c,id)).isEqualTo("COMPLETED");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("6");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement
            WHERE tenant_id=? AND movement_kind='FULFILLMENT'
            """,Integer.class,c.tenant())).isEqualTo(1);
    }

    @Test void concurrentOrdersCannotOversellAndCrossTenantCannotCommand() throws Exception {
        Context c=context(5),other=context(5);
        UUID first=order(c,4),second=order(c,4);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {commands.confirm(c.staff(),c.tenant(),first,c.location());return true;}
                catch(IllegalStateException e){return false;}
            });
            var right=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {commands.confirm(c.staff(),c.tenant(),second,c.location());return true;}
                catch(IllegalStateException e){return false;}
            });
            start.countDown();
            assertThat((left.get(30,TimeUnit.SECONDS)?1:0)
                +(right.get(30,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("4");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("5");
        assertThatThrownBy(()->commands.confirm(c.staff(),other.tenant(),order(other,1),other.location()))
            .isInstanceOf(SecurityException.class);
    }

    @Test void opposingMultiSkuOrdersReserveWithoutDeadlock() throws Exception {
        Context c=context(10);
        UUID otherItem=UUID.randomUUID(),otherOffer=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,c.tenant(),otherItem,"sku-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?,10,0)
            """,c.tenant(),c.location(),otherItem);
        jdbc.update("""
            INSERT INTO crm.store_offer
              (tenant_id,id,title,unit_price,currency,state,catalog_item_id)
            VALUES (?,?,'Second SKU',100,'KZT','PUBLISHED',?)
            """,c.tenant(),otherOffer,otherItem);
        UUID orderA=checkout.checkout(c.slug(),"buyer-"+UUID.randomUUID()+"@example.kz",
            "checkout-"+UUID.randomUUID(),List.of(
                new AutoOrderService.Line(c.offer(),2),
                new AutoOrderService.Line(otherOffer,2))).order().orderId();
        UUID orderB=checkout.checkout(c.slug(),"buyer-"+UUID.randomUUID()+"@example.kz",
            "checkout-"+UUID.randomUUID(),List.of(
                new AutoOrderService.Line(otherOffer,2),
                new AutoOrderService.Line(c.offer(),2))).order().orderId();
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return commands.confirm(c.staff(),c.tenant(),orderA,c.location());
            });
            var right=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return commands.confirm(c.staff(),c.tenant(),orderB,c.location());
            });
            start.countDown();
            assertThat(left.get(30,TimeUnit.SECONDS).affectedLines()).isEqualTo(2);
            assertThat(right.get(30,TimeUnit.SECONDS).affectedLines()).isEqualTo(2);
        }
        assertThat(status(c,orderA)).isEqualTo("CONFIRMED");
        assertThat(status(c,orderB)).isEqualTo("CONFIRMED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("""
            SELECT reserved FROM crm.inv_stock_balance
            WHERE tenant_id=? AND location_id=? AND item_id=?
            """,BigDecimal.class,c.tenant(),c.location(),otherItem)).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND state='ACTIVE'
            """,Integer.class,c.tenant())).isEqualTo(4);
    }

    @Test void staleSalesAndStockPermitsCannotMutateAnotherTransaction() {
        Context c=context(5);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        var template=new TransactionTemplate(transactionManager);
        TenantAuthorization.Decision[] stale=template.execute(tx->new TenantAuthorization.Decision[] {
            authorization.requireForCommand(c.staff(),c.tenant(),DomainPermission.SALES_ORDER_CONFIRM),
            authorization.requireForCommand(c.staff(),c.tenant(),DomainPermission.INVENTORY_RESERVE)
        });
        assertThat(stale).isNotNull();
        assertThatThrownBy(()->template.execute(tx->{
            crossDomainPort.reserve(stale[0],stale[1],
                UUID.randomUUID(),UUID.randomUUID(),c.location(),c.item(),BigDecimal.ONE);
            return null;
        })).isInstanceOf(SecurityException.class);
    }

    @Test void verifiedSalesFacadeIsThePublicCommandEntry() {
        Context c=context(6);
        UUID id=order(c,2);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        assertThatThrownBy(()->authenticatedCommands.confirm(null,c.tenant(),id,c.location()))
            .isInstanceOf(SecurityException.class);
        var actor=verified(c.staff());
        assertThat(authenticatedCommands.confirm(actor,c.tenant(),id,c.location()).status())
            .isEqualTo("CONFIRMED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("2");
        assertThatThrownBy(()->authenticatedCommands.confirm(actor,UUID.randomUUID(),id,c.location()))
            .isInstanceOf(SecurityException.class);
    }

    @Test void standaloneInventoryOperationsRequireExplicitGrants() {
        Context c=context(3);
        allow(c,DomainPermission.INVENTORY_RECEIVE);
        assertThat(warehouse.receive(verified(c.staff()),c.tenant(),c.location(),c.item(),
            "receive-001",BigDecimal.valueOf(2)).after()).isEqualByComparingTo("5");
        assertThatThrownBy(()->warehouse.correct(verified(c.staff()),c.tenant(),c.location(),c.item(),
            "correct-001",BigDecimal.valueOf(-1))).isInstanceOf(SecurityException.class);
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("5");
        assertThatThrownBy(()->warehouse.receive(verified(c.owner()),c.tenant(),c.location(),c.item(),
            "receive-002",BigDecimal.ONE)).isInstanceOf(SecurityException.class);
    }

    @Test void finalSecGateRejectsMixedActorTenantAndMissingInventoryPermits() {
        Context first=context(7), other=context(7);
        allow(first,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        allow(other,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        var tx=new TransactionTemplate(transactionManager);
        // Cross-bean @Transactional(MANDATORY) rejects mark the outer transaction rollback-only.
        // Verify the full rollback as part of the denial contract.
        assertThatThrownBy(()->tx.execute(status->{
            var sales=authorization.requireForCommand(first.staff(),first.tenant(),
                    DomainPermission.SALES_ORDER_CONFIRM);
            var inventoryDifferentActor=authorization.requireForCommand(first.backupOwner(),
                    first.tenant(),DomainPermission.INVENTORY_RESERVE);
            var inventoryDifferentTenant=authorization.requireForCommand(other.staff(),
                    other.tenant(),DomainPermission.INVENTORY_RESERVE);
            var wrongAction=authorization.requireForCommand(first.staff(),first.tenant(),
                    DomainPermission.SALES_ORDER_CONFIRM);
            assertThatThrownBy(()->crossDomainPort.reserve(sales,inventoryDifferentActor,
                    UUID.randomUUID(),UUID.randomUUID(),first.location(),first.item(),BigDecimal.ONE))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->crossDomainPort.reserve(sales,inventoryDifferentTenant,
                    UUID.randomUUID(),UUID.randomUUID(),first.location(),first.item(),BigDecimal.ONE))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->crossDomainPort.reserve(sales,wrongAction,
                    UUID.randomUUID(),UUID.randomUUID(),first.location(),first.item(),BigDecimal.ONE))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->crossDomainPort.reserve(null,inventoryDifferentActor,
                    UUID.randomUUID(),UUID.randomUUID(),first.location(),first.item(),BigDecimal.ONE))
                    .isInstanceOf(SecurityException.class);
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(scalar(first,"reserved")).isEqualByComparingTo("0");
        assertThat(scalar(other,"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id IN (?,?)",
                Integer.class,first.tenant(),other.tenant())).isZero();
    }

}
