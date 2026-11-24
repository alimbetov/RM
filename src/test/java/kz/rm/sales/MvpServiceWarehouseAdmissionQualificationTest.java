package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import kz.rm.organization.TenantCurrencyPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Strict service-only MVP admission. This class intentionally runs with
 * currency and warehouse gates ON; legacy synthetic test profiles are not
 * evidence of safe public market admission.
 */
@SpringBootTest(properties = {
    "rm.mvp.service-mode.enabled=true",
    "rm.currency.commerce-admission.enabled=true",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class MvpServiceWarehouseAdmissionQualificationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired WarehouseStaffScopeService warehouseScope;
    @Autowired TenantCurrencyPolicyService currency;
    @Autowired AutoOrderService checkout;
    @Autowired AuthenticatedSalesCommands sales;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(UUID tenant,UUID owner,UUID staff,UUID warehouse,
                           UUID sku,UUID offer,String slug) {}

    private VerifiedActor verified(UUID user) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token).header("alg","RS256")
                .subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-service-test");
    }

    private Fixture setup() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"MVP Warehouse LLP","Warehouse admission","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID tenant=workspace.getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"mvp-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID staff=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"mvp-operator-"+UUID.randomUUID(),"Operator")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        currency.initialize(verified(owner),tenant);

        String kato=jdbc.queryForObject("""
                SELECT code FROM ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        UUID warehouse=UUID.randomUUID(),sku=UUID.randomUUID(),offer=UUID.randomUUID();
        String slug="mvp-warehouse-"+UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.org_location
                (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
                VALUES (?,?,'WAREHOUSE','Strict warehouse',?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """,warehouse,tenant,kato);
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,sku,"MVP-"+UUID.randomUUID());
        jdbc.update("""
                INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
                VALUES (?,?,?,10,0)
                """,tenant,warehouse,sku);
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
                VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,UUID.randomUUID(),slug);
        jdbc.update("""
                INSERT INTO crm.store_offer
                (tenant_id,id,title,unit_price,currency,state,catalog_item_id)
                VALUES (?,?,'MVP product',100.0000,'KZT','PUBLISHED',?)
                """,tenant,offer,sku);

        VerifiedActor ownerActor=verified(owner);
        var role=roles.createRole(ownerActor,tenant,
                "MVP_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Strict warehouse qualification");
        for(var permission: List.of(
                DomainPermission.SALES_ORDER_CONFIRM,
                DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_CANCEL,
                DomainPermission.INVENTORY_RELEASE,
                DomainPermission.SALES_ORDER_FULFILL,
                DomainPermission.INVENTORY_FULFILL))
            roles.grant(ownerActor,tenant,role.id(),permission);
        roles.assign(ownerActor,tenant,staff,role.id());
        roles.enable(ownerActor,tenant);
        return new Fixture(tenant,owner,staff,warehouse,sku,offer,slug);
    }

    private UUID placed(Fixture f,int quantity) {
        return checkout.checkout(f.slug(),"mvp-buyer-"+UUID.randomUUID()+"@example.kz",
                "mvp-checkout-"+UUID.randomUUID(),
                List.of(new AutoOrderService.Line(f.offer(),quantity)))
                .order().orderId();
    }

    private BigDecimal quantity(Fixture f,String column) {
        if(!List.of("on_hand","reserved").contains(column))throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT "+column+
                " FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",
                BigDecimal.class,f.tenant(),f.warehouse(),f.sku());
    }

    @Test void mvpW3StrictWarehouseAssignmentRequiredForConfirmAndFulfill() {
        Fixture f=setup();
        UUID id=placed(f,3);
        VerifiedActor staff=verified(f.staff());
        assertThatThrownBy(()->sales.confirm(staff,f.tenant(),id,f.warehouse()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThat(quantity(f,"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,String.class,f.tenant(),id)).isEqualTo("PLACED");

        warehouseScope.assign(verified(f.owner()),f.tenant(),f.warehouse(),f.staff());
        assertThat(sales.confirm(staff,f.tenant(),id,f.warehouse()).status()).isEqualTo("CONFIRMED");
        assertThat(quantity(f,"reserved")).isEqualByComparingTo("3");

        warehouseScope.revoke(verified(f.owner()),f.tenant(),f.warehouse(),f.staff());
        assertThatThrownBy(()->sales.fulfill(staff,f.tenant(),id))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThat(quantity(f,"on_hand")).isEqualByComparingTo("10");
        assertThat(quantity(f,"reserved")).isEqualByComparingTo("3");

        warehouseScope.assign(verified(f.owner()),f.tenant(),f.warehouse(),f.staff());
        assertThat(sales.fulfill(staff,f.tenant(),id).status()).isEqualTo("COMPLETED");
        assertThat(sales.fulfill(staff,f.tenant(),id).affectedLines()).isZero();
        assertThat(quantity(f,"on_hand")).isEqualByComparingTo("7");
        assertThat(quantity(f,"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND item_id=?
                """,Integer.class,f.tenant(),f.sku())).isEqualTo(1);
    }

    @Test void mvpW3ReleaseCannotUseTenantGrantAfterWarehouseScopeRevocation() {
        Fixture f=setup();
        UUID id=placed(f,2);
        VerifiedActor staff=verified(f.staff());
        warehouseScope.assign(verified(f.owner()),f.tenant(),f.warehouse(),f.staff());
        sales.confirm(staff,f.tenant(),id,f.warehouse());
        warehouseScope.revoke(verified(f.owner()),f.tenant(),f.warehouse(),f.staff());

        long events=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=?
                """,Long.class,f.tenant(),id);
        assertThatThrownBy(()->sales.cancel(staff,f.tenant(),id))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThat(quantity(f,"on_hand")).isEqualByComparingTo("10");
        assertThat(quantity(f,"reserved")).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=?
                """,Long.class,f.tenant(),id)).isEqualTo(events);

        warehouseScope.assign(verified(f.owner()),f.tenant(),f.warehouse(),f.staff());
        assertThat(sales.cancel(staff,f.tenant(),id).status()).isEqualTo("CANCELLED");
        assertThat(sales.cancel(staff,f.tenant(),id).affectedLines()).isZero();
        assertThat(quantity(f,"reserved")).isEqualByComparingTo("0");
        assertThat(quantity(f,"on_hand")).isEqualByComparingTo("10");
    }
}
