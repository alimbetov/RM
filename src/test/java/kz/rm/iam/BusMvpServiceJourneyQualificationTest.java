package kz.rm.iam;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.catalog.CatalogItem;
import kz.rm.catalog.CatalogItemService;
import kz.rm.crm.AuthenticatedCrmCommands;
import kz.rm.crm.AuthenticatedCrmQueries;
import kz.rm.inventory.AuthenticatedInventoryCommands;
import kz.rm.organization.AuthenticatedOrganizationLocations;
import kz.rm.organization.OrganizationLocationEntity;
import kz.rm.organization.TenantCurrencyPolicyService;
import kz.rm.sales.AuthenticatedSalesCommands;
import kz.rm.sales.AuthenticatedSalesQueries;
import kz.rm.security.kms.TenantKeyService;
import kz.rm.storefront.StorefrontManagementService;
import kz.rm.storefront.StorefrontOfferService;
import kz.rm.tasks.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * BUS-MVP-J1: executed service-to-service without HTTP or direct business SQL
 * mutations. Synthetic accounts are fixtures; actual first-tenant onboarding,
 * enrollment, grants, location, CRM, TASKS, product, stock and SALES use services.
 */
@SpringBootTest(properties={
    "rm.mvp.service-mode.enabled=true",
    "rm.currency.commerce-admission.enabled=true",
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class BusMvpServiceJourneyQualificationTest {
    @Autowired UserAccountRepository users;
    @Autowired EmailIdentityService emails;
    @Autowired EmailIdentityCrypto emailCrypto;
    @Autowired SelfServiceTenantOnboardingService onboarding;
    @Autowired TenantMemberEnrollmentService enrollment;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired TenantCurrencyPolicyService currencies;
    @Autowired AuthenticatedOrganizationLocations locations;
    @Autowired WarehouseStaffScopeService warehouseScope;
    @Autowired TenantKeyService tenantKeys;
    @Autowired AuthenticatedCrmCommands crm;
    @Autowired AuthenticatedCrmQueries crmReads;
    @Autowired TaskService tasks;
    @Autowired CatalogItemService catalog;
    @Autowired StorefrontManagementService stores;
    @Autowired StorefrontOfferService offers;
    @Autowired AuthenticatedInventoryCommands stock;
    @Autowired AutoOrderService checkout;
    @Autowired AuthenticatedSalesCommands sales;
    @Autowired AuthenticatedSalesQueries orderReads;
    @Autowired JdbcTemplate jdbc;

    private UUID account(String prefix) {
        return users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),prefix+"-"+UUID.randomUUID(),prefix)).getId();
    }

    private VerifiedActor actor(UUID userId) {
        // Only available because this qualification is inside IAM test package.
        return new VerifiedActor(userId,Instant.now().plusSeconds(600));
    }

    private void proveInitialOwnerEmail(UUID ownerId) {
        emails.requestInitialVerification(actor(ownerId),
                "bus-owner-"+UUID.randomUUID()+"@example.kz");
        Object[] challenge=jdbc.query("""
                SELECT c.id,c.token_envelope
                FROM public.iam_email_verification_challenge c
                JOIN public.iam_email_identity e ON e.id=c.email_identity_id
                WHERE e.user_id=? AND c.purpose='REGISTRATION'
                ORDER BY c.created_at DESC
                """,rs->{
                    if(!rs.next())throw new AssertionError("Missing registration proof");
                    return new Object[]{rs.getObject(1,UUID.class),rs.getBytes(2)};
                },ownerId);
        assertThat(emails.verifyRegistration((UUID)challenge[0],
                emailCrypto.decrypt((byte[])challenge[1]))).isTrue();
    }

    @Test void busMvpJ1OrganizationEmployeeCustomerTaskSkuOrderStockHistoryPg() {
        UUID ownerId=account("bus-owner"),staffId=account("bus-staff");
        proveInitialOwnerEmail(ownerId);
        VerifiedActor owner=actor(ownerId),staff=actor(staffId);
        UUID tenant=UUID.randomUUID();
        var onboarded=onboarding.createFirstTenant(owner,tenant,
                "RM MVP Service Business LLP","RM MVP Service","KZ");
        assertThat(onboarded.tenantId()).isEqualTo(tenant);
        assertThat(onboarding.selectTenant(owner,tenant).tenantId()).isEqualTo(tenant);
        assertThat(enrollment.invite(owner,tenant,staffId).status()).isEqualTo("INVITED");
        assertThat(enrollment.accept(staff,tenant).status()).isEqualTo("ACTIVE");

        var role=roles.createRole(owner,tenant,
                "BUS_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "MVP Business seller and warehouse");
        for(var permission:List.of(
                DomainPermission.ORGANIZATION_LOCATION_WRITE,
                DomainPermission.CRM_WRITE,DomainPermission.CRM_READ,
                DomainPermission.CRM_PII_READ,
                DomainPermission.TASK_MANAGE,DomainPermission.TASK_READ,
                DomainPermission.TASK_EXECUTE,
                DomainPermission.CATALOG_WRITE,DomainPermission.CATALOG_PRICE_MANAGE,
                DomainPermission.CATALOG_PUBLISH,
                DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH,
                DomainPermission.INVENTORY_RECEIVE,DomainPermission.INVENTORY_STOCK_READ,
                DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL,
                DomainPermission.SALES_ORDER_READ))
            roles.grant(owner,tenant,role.id(),permission);
        roles.assign(owner,tenant,staffId,role.id());
        roles.enable(owner,tenant);

        currencies.initialize(owner,tenant);
        tenantKeys.provision(tenant);
        String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        var warehouse=locations.create(staff,tenant,OrganizationLocationEntity.Type.WAREHOUSE,
                "RM Business stock",kato,"Business warehouse 1");
        warehouseScope.assign(owner,tenant,warehouse.id(),staffId);

        var customer=crm.createCompany(staff,tenant,"MVP Buyer Company LLP",kato);
        var contact=crm.addContact(staff,tenant,customer.id(),"Buyer Contact",
                "bus-contact-"+UUID.randomUUID()+"@example.kz",null);
        assertThat(crmReads.getCustomer(staff,tenant,customer.id()).id()).isEqualTo(customer.id());
        assertThat(crmReads.getContact(staff,tenant,contact.id()).customerId()).isEqualTo(customer.id());

        var task=tasks.create(staff,tenant,"Prepare customer call",staffId,
                Instant.now().plusSeconds(3600),"bus-task-"+UUID.randomUUID());
        var started=tasks.start(staff,tenant,task.id(),task.version());
        var finished=tasks.complete(staff,tenant,task.id(),started.version());
        assertThat(finished.state().name()).isEqualTo("COMPLETED");
        assertThat(tasks.historyMine(staff,tenant,task.id(),20)).hasSize(3);

        var draft=catalog.createDraft(staff,tenant,
                "BUS-"+UUID.randomUUID(),CatalogItem.Kind.PRODUCT,
                "ru","RM business product",null);
        var priced=catalog.addPriceRevision(staff,tenant,draft.id(),
                new BigDecimal("50.0000"),"KZT",Instant.now().minusSeconds(100),
                draft.rowVersion());
        var published=catalog.publish(staff,tenant,priced.id(),priced.rowVersion());
        var storeDraft=stores.createDraft(staff,tenant,
                "bus-service-"+UUID.randomUUID());
        var store=stores.publish(staff,tenant,storeDraft.id(),storeDraft.rowVersion());
        var offerDraft=offers.createDraft(staff,tenant,published.id(),
                "RM business product",new BigDecimal("75.0000"),"KZT");
        var offer=offers.publish(staff,tenant,offerDraft.id(),offerDraft.rowVersion());

        stock.receive(staff,tenant,warehouse.id(),published.id(),
                "bus-receipt-"+UUID.randomUUID(),new BigDecimal("10"));
        UUID orderId=checkout.checkout(store.slug(),
                "bus-buyer-"+UUID.randomUUID()+"@example.kz",
                "bus-order-"+UUID.randomUUID(),
                List.of(new AutoOrderService.Line(offer.id(),3))).order().orderId();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,tenant,orderId)).isZero();

        assertThat(sales.confirm(staff,tenant,orderId,warehouse.id()).status())
                .isEqualTo("CONFIRMED");
        assertThat(sales.fulfill(staff,tenant,orderId).status()).isEqualTo("COMPLETED");
        assertThat(sales.fulfill(staff,tenant,orderId).affectedLines()).isZero();

        assertThat(jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,BigDecimal.class,tenant,orderId)).isEqualByComparingTo("75.0000");
        assertThat(jdbc.queryForObject("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,tenant,warehouse.id(),published.id()))
                .isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("""
                SELECT reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,tenant,warehouse.id(),published.id())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND item_id=? AND movement_kind='FULFILLMENT'
                  AND source_type='SALES_ORDER' AND source_id=?
                """,Integer.class,tenant,published.id(),orderId)).isEqualTo(1);
        assertThat(orderReads.history(staff,tenant,orderId,0,20)).hasSize(3);
        assertThat(orderReads.detail(staff,tenant,orderId)).isNotNull();
        assertThat(tasks.getMine(staff,tenant,task.id()).state().name())
                .isEqualTo("COMPLETED");
    }
}
