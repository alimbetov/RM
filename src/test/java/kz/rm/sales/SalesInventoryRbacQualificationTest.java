package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import kz.rm.inventory.AuthenticatedInventoryCommands;
import kz.rm.inventory.AuthenticatedInventoryQueries;
import kz.rm.inventory.dto.InventoryMovementSearch;
import kz.rm.inventory.AuthenticatedInventorySettings;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.Collections;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class SalesInventoryRbacQualificationTest {
    @Autowired SalesInventoryCommandService commands;
    @Autowired kz.rm.organization.AuthenticatedOrganizationLocations locationCommands;
    @Autowired AuthenticatedSalesCommands authenticatedCommands;
    @Autowired AuthenticatedInventoryCommands warehouse;
    @Autowired AuthenticatedInventoryQueries inventoryReads;
    @Autowired AuthenticatedInventorySettings inventorySettings;
    @Autowired AutoOrderService checkout;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantAuthorization authorization;
    @Autowired OrderInventoryPort crossDomainPort;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

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
        var role=roles.createRole(verified(c.owner()),c.tenant(),
            "OPS_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Warehouse and order operator");
        for(var permission:permissions)roles.grant(verified(c.owner()),c.tenant(),role.id(),permission);
        roles.assign(verified(c.owner()),c.tenant(),c.staff(),role.id());
        roles.assign(verified(c.owner()),c.tenant(),c.backupOwner(),role.id());
        roles.enable(verified(c.owner()),c.tenant());
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

    @Test void decFul01PickupProofRequiredBlocksGenericFulfillBeforePreparing() {
        Context c=context(10);
        UUID id=order(c,3);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        // Simulates the persistent checkout provenance independently of QR transport.
        jdbc.update("""
            UPDATE crm.sales_order SET fulfillment_mode='PICKUP_PROOF_REQUIRED'
            WHERE tenant_id=? AND id=? AND status='PLACED'
            """,c.tenant(),id);
        commands.confirm(c.staff(),c.tenant(),id,c.location());
        long before=jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
            """,Long.class,c.tenant());
        assertThatThrownBy(()->commands.fulfill(c.staff(),c.tenant(),id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("ORDER_FULFILL_REQUIRES_PICKUP_PROOF");
        assertThat(status(c,id)).isEqualTo("CONFIRMED");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("10");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
            """,Long.class,c.tenant())).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_pickup_handover WHERE tenant_id=? AND order_id=?
            """,Long.class,c.tenant(),id)).isZero();
    }

    @Test void fc40PickedOrderCannotCancelAndReservationStaysIntact() {
        Context c=context(10);
        UUID id=order(c,3);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_CANCEL,DomainPermission.INVENTORY_RELEASE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        commands.confirm(c.staff(),c.tenant(),id,c.location());
        jdbc.update("""
            UPDATE crm.sales_order SET status='PREPARING',row_version=row_version+1
            WHERE tenant_id=? AND id=?
            """,c.tenant(),id);
        UUID orderLine=jdbc.queryForObject("""
            SELECT id FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
            """,UUID.class,c.tenant(),id);
        jdbc.update("""
            INSERT INTO crm.sales_order_pick_confirmation
             (tenant_id,id,order_id,order_line_id,warehouse_id,item_id,quantity,picked_by,picked_at)
            VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
            """,c.tenant(),UUID.randomUUID(),id,orderLine,c.location(),c.item(),3,c.staff());
        long movementsBefore=jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
            """,Long.class,c.tenant());
        long eventsBefore=jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=?
            """,Long.class,c.tenant(),id);
        assertThatThrownBy(()->commands.cancel(c.staff(),c.tenant(),id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("ORDER_CANCEL_REQUIRES_REVERSE_PICK");
        assertThatThrownBy(()->commands.fulfill(c.staff(),c.tenant(),id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("ORDER_FULFILL_REQUIRES_PICKUP_PROOF");
        assertThat(status(c,id)).isEqualTo("PREPARING");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("10");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
            """,Long.class,c.tenant(),id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
            """,Long.class,c.tenant())).isEqualTo(movementsBefore);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=?
            """,Long.class,c.tenant(),id)).isEqualTo(eventsBefore);
    }

    @Test void q123LegacyAutoReserveFalseCannotDisableRequiredSalesConfirmationReserve() {
        Context c=context(10);
        allow(c,DomainPermission.INVENTORY_SETTINGS_MANAGE,
                DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        var actor=verified(c.staff());
        var operational=inventorySettings.initialize(actor,c.tenant());
        assertThat(operational.reservationMode())
                .isEqualTo(AuthenticatedInventorySettings.ReservationMode.MANDATORY_ON_CONFIRM);
        assertThat(jdbc.queryForObject("""
                SELECT auto_reserve_on_confirm FROM crm.inv_tenant_setting WHERE tenant_id=?
                """,Boolean.class,c.tenant())).isFalse();
        UUID id=order(c,3);
        var confirmed=authenticatedCommands.confirm(actor,c.tenant(),id,c.location());
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Integer.class,c.tenant(),id)).isEqualTo(1);
        assertThat(inventorySettings.get(actor,c.tenant()).pickingReadiness())
                .isEqualTo(AuthenticatedInventorySettings.PickingReadiness.NOT_IMPLEMENTED);
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
        var role=roles.createRole(verified(c.owner()),c.tenant(),"SHIP_"+UUID.randomUUID().toString()
                .replace("-","").substring(0,12).toUpperCase(),"Shipping");
        roles.grant(verified(c.owner()),c.tenant(),role.id(),DomainPermission.INVENTORY_FULFILL);
        roles.assign(verified(c.owner()),c.tenant(),c.staff(),role.id());
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

    @Test void urR32SalesConfirmDenyPreservesOrderReservationAndStatusHistory() {
        Context a=context(8),b=context(8);
        UUID orderId=order(a,2);
        assertThat(status(a,orderId)).isEqualTo("PLACED");
        int events=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,a.tenant(),orderId);
        int reservations=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,a.tenant(),orderId);
        assertThatThrownBy(()->authenticatedCommands.confirm(
                verified(a.staff()),a.tenant(),orderId,a.location()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->authenticatedCommands.confirm(
                verified(b.staff()),a.tenant(),orderId,a.location()))
                .isInstanceOf(SecurityException.class);
        assertThat(status(a,orderId)).isEqualTo("PLACED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,a.tenant(),orderId)).isEqualTo(events);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,a.tenant(),orderId)).isEqualTo(reservations);
        assertThat(scalar(a,"reserved")).isEqualByComparingTo("0");
        assertThat(scalar(a,"on_hand")).isEqualByComparingTo("8");
        allow(a,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        assertThat(authenticatedCommands.confirm(
                verified(a.staff()),a.tenant(),orderId,a.location()).affectedLines())
                .isEqualTo(1);
        assertThat(status(a,orderId)).isEqualTo("CONFIRMED");
        assertThat(scalar(a,"reserved")).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Integer.class,a.tenant(),orderId)).isEqualTo(1);
    }

    @Test void urR32InventoryGrantRevokeAndForeignActorPreserveLedger() {
        Context a=context(3),b=context(3);
        allow(a,DomainPermission.INVENTORY_RECEIVE);
        var allowed=warehouse.receive(verified(a.staff()),a.tenant(),
                a.location(),a.item(),"ur-r32-receive-"+UUID.randomUUID(),BigDecimal.TWO);
        assertThat(allowed.after()).isEqualByComparingTo("5");
        int movements=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,Integer.class,a.tenant(),a.location(),a.item());
        assertThat(movements).isEqualTo(1);
        assertThatThrownBy(()->warehouse.correct(verified(a.staff()),a.tenant(),
                a.location(),a.item(),"ur-r32-correct-"+UUID.randomUUID(),
                BigDecimal.ONE)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->warehouse.receive(verified(b.staff()),a.tenant(),
                a.location(),a.item(),"ur-r32-foreign-"+UUID.randomUUID(),
                BigDecimal.ONE)).isInstanceOf(SecurityException.class);
        UUID role=jdbc.queryForObject("""
                SELECT id FROM public.iam_tenant_role
                WHERE tenant_id=? AND role_code LIKE 'OPS_%'
                """,UUID.class,a.tenant());
        roles.revoke(verified(a.owner()),a.tenant(),role,DomainPermission.INVENTORY_RECEIVE);
        assertThatThrownBy(()->warehouse.receive(verified(a.staff()),a.tenant(),
                a.location(),a.item(),"ur-r32-revoked-"+UUID.randomUUID(),
                BigDecimal.ONE)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,Integer.class,a.tenant(),a.location(),a.item())).isEqualTo(movements);
        assertThat(scalar(a,"on_hand")).isEqualByComparingTo("5");
        assertThat(scalar(a,"reserved")).isEqualByComparingTo("0");
        assertThat(scalar(b,"on_hand")).isEqualByComparingTo("3");
    }

    @Test void postgresSalesConfirmationFencesConcurrentPermissionRevocation() throws Exception {
        try (var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Context c=context(8);
        UUID id=order(c,2);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        UUID role=jdbc.queryForObject("""
                SELECT id FROM public.iam_tenant_role
                WHERE tenant_id=? AND role_code LIKE 'OPS_%'
                """,UUID.class,c.tenant());
        var commandAdmitted=new CountDownLatch(1);
        var commitCommand=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var confirm=pool.submit(()->tx.execute(status->{
                authenticatedCommands.confirm(verified(c.staff()),c.tenant(),id,c.location());
                commandAdmitted.countDown();
                try {
                    if(!commitCommand.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("Confirmation latch timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(commandAdmitted.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{
                roles.revoke(verified(c.owner()),c.tenant(),role,DomainPermission.SALES_ORDER_CONFIRM);
                return true;
            });
            try {
                assertThatThrownBy(()->revoke.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                commitCommand.countDown();
            }
            assertThat(confirm.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(status(c,id)).isEqualTo("CONFIRMED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Integer.class,c.tenant(),id)).isEqualTo(1);
        assertThatThrownBy(()->authenticatedCommands.confirm(
                verified(c.staff()),c.tenant(),id,c.location()))
                .isInstanceOf(SecurityException.class);
    }


    /**
     * R1.2 synthetic contention qualification: actual PostgreSQL row locks,
     * double-submit retries and mixed terminal states. Timings are diagnostic
     * micro-sample metrics, not SLA or deployment-load evidence.
     */
    @Test void postgresMixedConcurrentOrderLifecyclePreservesLedgerAndIdempotency() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Context c=context(40);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_CANCEL,DomainPermission.INVENTORY_RELEASE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<12;i++) ids.add(order(c,1));

        List<Long> confirmMicros=Collections.synchronizedList(new ArrayList<>());
        List<Callable<Integer>> confirmations=new ArrayList<>();
        for(UUID id:ids) for(int retry=0;retry<2;retry++) {
            confirmations.add(()->{
                long start=System.nanoTime();
                try {
                    return commands.confirm(c.staff(),c.tenant(),id,c.location()).affectedLines();
                } finally {
                    confirmMicros.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-start));
                }
            });
        }
        int confirmed=executeConcurrent(confirmations,6);
        assertThat(confirmed).isEqualTo(12);
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("40");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("12");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND state='ACTIVE'
                """,Integer.class,c.tenant())).isEqualTo(12);

        List<Long> terminalMicros=Collections.synchronizedList(new ArrayList<>());
        List<Callable<Integer>> terminals=new ArrayList<>();
        for(int i=0;i<ids.size();i++) {
            UUID id=ids.get(i);
            boolean cancellation=i<6;
            for(int retry=0;retry<2;retry++) {
                terminals.add(()->{
                    long start=System.nanoTime();
                    try {
                        return cancellation
                                ?commands.cancel(c.staff(),c.tenant(),id).affectedLines()
                                :commands.fulfill(c.staff(),c.tenant(),id).affectedLines();
                    } finally {
                        terminalMicros.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-start));
                    }
                });
            }
        }
        assertThat(executeConcurrent(terminals,6)).isEqualTo(12);
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("34");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND state='FULFILLED'
                """,Integer.class,c.tenant())).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND state='RELEASED'
                """,Integer.class,c.tenant())).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND movement_kind='FULFILLMENT'
                """,Integer.class,c.tenant())).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=?
                """,Integer.class,c.tenant())).isEqualTo(36);
        for(int i=0;i<ids.size();i++)
            assertThat(status(c,ids.get(i))).isEqualTo(i<6?"CANCELLED":"COMPLETED");

        System.out.printf("R1.2 synthetic PG contention: confirm count=%d p95_us=%d p99_us=%d; "
                        +"terminal count=%d p95_us=%d p99_us=%d%n",
                confirmMicros.size(),percentile(confirmMicros,0.95),percentile(confirmMicros,0.99),
                terminalMicros.size(),percentile(terminalMicros,0.95),percentile(terminalMicros,0.99));
    }

    private static int executeConcurrent(List<Callable<Integer>> tasks,int workers) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(workers)) {
            List<Future<Integer>> futures=new ArrayList<>();
            for(Callable<Integer> task:tasks)
                futures.add(pool.submit(()->{
                    if(!start.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("Contention start barrier timed out");
                    return task.call();
                }));
            start.countDown();
            int affected=0;
            for(Future<Integer> future:futures) affected+=future.get(45,TimeUnit.SECONDS);
            return affected;
        }
    }

    private static long percentile(List<Long> samples,double quantile) {
        List<Long> sorted=new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get(Math.max(0,(int)Math.ceil(quantile*sorted.size())-1));
    }


    /**
     * R1.3: simulate a process failure after the domain method returns but before
     * the outer transaction commits. The retry must recreate exactly one effect.
     */
    @Test void r13RollbackAfterSuccessfulDomainCallsCanBeSafelyRetried() {
        Context c=context(10);
        UUID id=order(c,3);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_CANCEL,DomainPermission.INVENTORY_RELEASE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        var tx=new TransactionTemplate(transactionManager);

        assertThatThrownBy(()->tx.execute(s->{
            assertThat(commands.confirm(c.staff(),c.tenant(),id,c.location()).affectedLines())
                    .isEqualTo(1);
            throw new IllegalStateException("synthetic failure before confirm commit");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synthetic failure");
        assertThat(status(c,id)).isEqualTo("PLACED");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("10");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
        assertThat(reservationCount(c,id,"ACTIVE")).isZero();
        assertThat(eventCount(c,id,"CONFIRMED")).isZero();

        assertThat(commands.confirm(c.staff(),c.tenant(),id,c.location()).affectedLines())
                .isEqualTo(1);
        assertThat(commands.confirm(c.staff(),c.tenant(),id,c.location()).affectedLines())
                .isZero();
        assertThat(reservationCount(c,id,"ACTIVE")).isEqualTo(1);
        assertThat(eventCount(c,id,"CONFIRMED")).isEqualTo(1);

        assertThatThrownBy(()->tx.execute(s->{
            assertThat(commands.cancel(c.staff(),c.tenant(),id).affectedLines())
                    .isEqualTo(1);
            throw new IllegalStateException("synthetic failure before cancel commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(status(c,id)).isEqualTo("CONFIRMED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("3");
        assertThat(reservationCount(c,id,"ACTIVE")).isEqualTo(1);
        assertThat(reservationCount(c,id,"RELEASED")).isZero();
        assertThat(eventCount(c,id,"CANCELLED")).isZero();

        assertThatThrownBy(()->tx.execute(s->{
            assertThat(commands.fulfill(c.staff(),c.tenant(),id).affectedLines()).isEqualTo(1);
            throw new IllegalStateException("synthetic failure before fulfill commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(status(c,id)).isEqualTo("CONFIRMED");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("10");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("3");
        assertThat(reservationCount(c,id,"ACTIVE")).isEqualTo(1);
        assertThat(fulfillmentMovementCount(c,id)).isZero();
        assertThat(eventCount(c,id,"COMPLETED")).isZero();

        assertThat(commands.fulfill(c.staff(),c.tenant(),id).affectedLines()).isEqualTo(1);
        assertThat(commands.fulfill(c.staff(),c.tenant(),id).affectedLines()).isZero();
        assertThat(status(c,id)).isEqualTo("COMPLETED");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("7");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
        assertThat(fulfillmentMovementCount(c,id)).isEqualTo(1);
        assertThat(eventCount(c,id,"COMPLETED")).isEqualTo(1);
    }

    @Test void r13PostgresCancelVsFulfillRaceHasExactlyOneTerminalOutcome() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Context c=context(10);
        UUID id=order(c,3);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_CANCEL,DomainPermission.INVENTORY_RELEASE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        commands.confirm(c.staff(),c.tenant(),id,c.location());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var cancellation=pool.submit(()->{
                if(!start.await(10,TimeUnit.SECONDS))
                    throw new IllegalStateException("Start barrier");
                try {
                    return commands.cancel(c.staff(),c.tenant(),id).affectedLines()==1;
                } catch(IllegalStateException expected) {
                    return false;
                }
            });
            var fulfillment=pool.submit(()->{
                if(!start.await(10,TimeUnit.SECONDS))
                    throw new IllegalStateException("Start barrier");
                try {
                    return commands.fulfill(c.staff(),c.tenant(),id).affectedLines()==1;
                } catch(IllegalStateException expected) {
                    return false;
                }
            });
            start.countDown();
            boolean cancelled=cancellation.get(30,TimeUnit.SECONDS);
            boolean fulfilled=fulfillment.get(30,TimeUnit.SECONDS);
            assertThat((cancelled?1:0)+(fulfilled?1:0)).isEqualTo(1);
            assertThat(status(c,id)).isEqualTo(cancelled?"CANCELLED":"COMPLETED");
            assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
            assertThat(scalar(c,"on_hand")).isEqualByComparingTo(cancelled?"10":"7");
            assertThat(reservationCount(c,id,"ACTIVE")).isZero();
            assertThat(reservationCount(c,id,cancelled?"RELEASED":"FULFILLED"))
                    .isEqualTo(1);
            assertThat(fulfillmentMovementCount(c,id)).isEqualTo(fulfilled?1:0);
            assertThat(eventCount(c,id,"CANCELLED")).isEqualTo(cancelled?1:0);
            assertThat(eventCount(c,id,"COMPLETED")).isEqualTo(fulfilled?1:0);
        }
    }

    private int reservationCount(Context c,UUID orderId,String state) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state=?
                """,Integer.class,c.tenant(),orderId,state);
    }

    private int eventCount(Context c,UUID orderId,String event) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=? AND event_type=?
                """,Integer.class,c.tenant(),orderId,event);
    }

    private int fulfillmentMovementCount(Context c,UUID orderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement m
                JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=m.tenant_id AND r.id=m.reservation_id
                WHERE r.tenant_id=? AND r.order_id=? AND m.movement_kind='FULFILLMENT'
                """,Integer.class,c.tenant(),orderId);
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


    @Test void q4SalesReserveAndFulfillKeepStaffAttributionAndSourceOrder() {
        Context c=context(10);
        UUID id=order(c,3);
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        var actor=verified(c.staff());
        authenticatedCommands.confirm(actor,c.tenant(),id,c.location());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_reservation_event e
                JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=e.tenant_id AND r.id=e.reservation_id
                WHERE e.tenant_id=? AND r.order_id=? AND e.actor_user_id=?
                """,Integer.class,c.tenant(),id,c.staff())).isEqualTo(1);
        authenticatedCommands.fulfill(actor,c.tenant(),id);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_reservation_event e
                JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=e.tenant_id AND r.id=e.reservation_id
                WHERE e.tenant_id=? AND r.order_id=? AND e.actor_user_id=?
                """,Integer.class,c.tenant(),id,c.staff())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND source_type='SALES_ORDER'
                  AND source_id=? AND actor_user_id=? AND movement_kind='FULFILLMENT'
                """,Integer.class,c.tenant(),id,c.staff())).isEqualTo(1);
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("7");
    }


    @Test void q9WarehouseFirstReceiptToSellerFulfillmentProducesAuditableQuantityChain() {
        Context c=context(0);
        // Remove legacy synthetic zero position: the real verified receipt must
        // initialize the new tenant/warehouse/SKU itself.
        jdbc.update("""
                DELETE FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,c.tenant(),c.location(),c.item());
        allow(c,DomainPermission.INVENTORY_RECEIVE,DomainPermission.INVENTORY_STOCK_READ,
                DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        var actor=verified(c.staff());
        var first=warehouse.receive(actor,c.tenant(),c.location(),c.item(),
                "e2eReceipt0001",BigDecimal.TEN);
        assertThat(first.before()).isEqualByComparingTo("0");
        assertThat(first.after()).isEqualByComparingTo("10");
        assertThat(inventoryReads.getPosition(actor,c.tenant(),c.location(),c.item())
                .available()).isEqualByComparingTo("10");
        UUID order=order(c,3);
        var confirmed=authenticatedCommands.confirm(actor,c.tenant(),order,c.location());
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(inventoryReads.getPosition(actor,c.tenant(),c.location(),c.item())
                .reserved()).isEqualByComparingTo("3");
        var fulfilled=authenticatedCommands.fulfill(actor,c.tenant(),order);
        assertThat(fulfilled.status()).isEqualTo("COMPLETED");
        var position=inventoryReads.getPosition(actor,c.tenant(),c.location(),c.item());
        assertThat(position.onHand()).isEqualByComparingTo("7");
        assertThat(position.reserved()).isEqualByComparingTo("0");
        assertThat(position.available()).isEqualByComparingTo("7");
        var history=inventoryReads.searchMovements(actor,c.tenant(),
                new InventoryMovementSearch(c.location(),c.item(),null,null,null),0,20);
        assertThat(history.getTotalElements()).isEqualTo(2);
        assertThat(history.getContent()).extracting(m->m.reasonCode())
                .containsExactlyInAnyOrder("RECEIPT","ORDER_FULFILLMENT");
        assertThat(history.getContent()).allSatisfy(m->
                assertThat(m.actorUserId()).isEqualTo(c.staff()));
        assertThat(history.getContent()).anySatisfy(m->{
            if("SALES_ORDER".equals(m.sourceType())) {
                assertThat(m.sourceId()).isEqualTo(order);
                assertThat(m.delta()).isEqualByComparingTo("-3");
            }
        });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_reservation_event e
                JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=e.tenant_id AND r.id=e.reservation_id
                WHERE e.tenant_id=? AND r.order_id=? AND e.actor_user_id=?
                """,Integer.class,c.tenant(),order,c.staff())).isEqualTo(2);
        assertThat(authenticatedCommands.fulfill(actor,c.tenant(),order).affectedLines())
                .isZero();
        assertThat(inventoryReads.searchMovements(actor,c.tenant(),
                new InventoryMovementSearch(c.location(),c.item(),null,null,null),0,20)
                .getTotalElements()).isEqualTo(2);
    }


    @Test void sale08RemappedOfferCannotSubstituteSkuDuringOrderConfirmation() {
        Context c=context(10);
        UUID oldOrder=order(c,3);
        UUID replacement=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,c.tenant(),replacement,"sale08-remap-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance
                (tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?,10,0)
            """,c.tenant(),c.location(),replacement);
        // Post-08.4: the attempted remap must fail at PostgreSQL DB boundary.
        // H2 does not run the PostgreSQL-only immutability trigger.
        try(var connection=dataSource.getConnection()) {
            if(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql")) {
                assertThatThrownBy(()->jdbc.update("""
                    UPDATE crm.store_offer
                    SET catalog_item_id=?,title='Replacement',unit_price=999
                    WHERE tenant_id=? AND id=?
                    """,replacement,c.tenant(),c.offer()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
        } catch(java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
        UUID fixed=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,UUID.class,c.tenant(),oldOrder);
        assertThat(fixed).isEqualTo(c.item());
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        assertThat(commands.confirm(c.staff(),c.tenant(),oldOrder,c.location()).affectedLines())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT item_id FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,UUID.class,c.tenant(),oldOrder)).isEqualTo(c.item());
        assertThat(jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,BigDecimal.class,c.tenant(),oldOrder)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("""
                SELECT reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,c.tenant(),c.location(),replacement))
                .isEqualByComparingTo("0");
        assertThat(commands.fulfill(c.staff(),c.tenant(),oldOrder).affectedLines())
                .isEqualTo(1);
        assertThat(scalar(c,"on_hand")).isEqualByComparingTo("7");
    }

    @Test void sale08HistoricalNullSkuMustNotResolveFromLiveOffer() {
        Context c=context(10);
        UUID current=order(c,1);
        UUID historical=UUID.randomUUID(),lineId=UUID.randomUUID();
        // Simulate a pre-migration order without ever reconstructing an
        // accepted SKU from today's potentially changed store_offer mapping.
        jdbc.update("""
                INSERT INTO crm.sales_order
                  (tenant_id,id,pending_email_identity_id,buyer_user_id,status,
                   total_amount,currency,created_at)
                SELECT tenant_id,?,pending_email_identity_id,NULL,'PLACED',
                       total_amount,currency,CURRENT_TIMESTAMP
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,historical,c.tenant(),current);
        jdbc.update("""
                INSERT INTO crm.sales_order_line
                  (tenant_id,id,order_id,offer_id,title_snapshot,quantity,
                   unit_price_snapshot,currency)
                VALUES (?,?,?,?,'Legacy accepted SKU',1,100,'KZT')
                """,c.tenant(),lineId,historical,c.offer());
        allow(c,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        assertThatThrownBy(()->commands.confirm(c.staff(),c.tenant(),historical,c.location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNMAPPED_LEGACY_ORDER");
        assertThat(status(c,historical)).isEqualTo("PLACED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,c.tenant(),historical)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),lineId)).isNull();
    }

    @Test void sale08PostgresRejectsModificationOfAcceptedOrderLine() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(java.util.Locale.ROOT).contains("postgresql"));
        }
        Context c=context(10);
        UUID order=order(c,1);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.sales_order_line SET catalog_item_id_snapshot=NULL
                WHERE tenant_id=? AND order_id=?
                """,c.tenant(),order))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.sales_order_line SET unit_price_snapshot=999
                WHERE tenant_id=? AND order_id=?
                """,c.tenant(),order))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,UUID.class,c.tenant(),order)).isEqualTo(c.item());
    }


    /** INV-R1.4: pause wins the warehouse row fence, so no order can reserve it. */
    @Test void postgresDeactivateBeforeConfirmRejectsReservationAndRollsBackOrder() throws Exception {
        try(var con=dataSource.getConnection()) {
            Assumptions.assumeTrue(con.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Context c=context(5);
        UUID id=order(c,3);
        allow(c,DomainPermission.ORGANIZATION_LOCATION_WRITE,
                DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        var gate=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var pause=pool.submit(()->tx.execute(s->{
                locationCommands.deactivate(verified(c.staff()),c.tenant(),c.location(),0);
                gate.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Pause timed out");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(gate.await(10,TimeUnit.SECONDS)).isTrue();
            var started=new CountDownLatch(1);
            var confirm=pool.submit(()->{
                started.countDown();
                try {
                    commands.confirm(c.staff(),c.tenant(),id,c.location());
                    return true;
                } catch(IllegalArgumentException rejected) {
                    return false;
                }
            });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->confirm.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(pause.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(confirm.get(20,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(status(c,id)).isEqualTo("PLACED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND order_id=?
            """,Integer.class,c.tenant(),id)).isZero();
    }

    /** INV-R1.3/R1.4: admitted reserve wins; deactivation sees active commitment. */
    @Test void postgresConfirmBeforeDeactivateFencesPauseAndLeavesActiveWarehouse() throws Exception {
        try(var con=dataSource.getConnection()) {
            Assumptions.assumeTrue(con.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Context c=context(5);
        UUID id=order(c,3);
        allow(c,DomainPermission.ORGANIZATION_LOCATION_WRITE,
                DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        var gate=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var confirm=pool.submit(()->tx.execute(s->{
                commands.confirm(c.staff(),c.tenant(),id,c.location());
                gate.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("Confirm timed out");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(gate.await(10,TimeUnit.SECONDS)).isTrue();
            var started=new CountDownLatch(1);
            var pause=pool.submit(()->{
                started.countDown();
                try {
                    locationCommands.deactivate(verified(c.staff()),c.tenant(),c.location(),0);
                    return true;
                } catch(IllegalStateException blocked) {
                    return false;
                }
            });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->pause.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(confirm.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(pause.get(20,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(status(c,id)).isEqualTo("CONFIRMED");
        assertThat(scalar(c,"reserved")).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("""
            SELECT status FROM public.org_location
            WHERE workspace_id=? AND id=?
            """,String.class,c.tenant(),c.location())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
            """,Integer.class,c.tenant(),id)).isEqualTo(1);
    }
}
