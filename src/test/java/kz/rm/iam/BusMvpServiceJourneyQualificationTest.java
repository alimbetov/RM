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
    @Autowired kz.rm.tasks.TaskCrmFollowupService followups;
    @Autowired kz.rm.crm.CrmInteractionService interactions;
    @Autowired CatalogItemService catalog;
    @Autowired StorefrontManagementService stores;
    @Autowired StorefrontOfferService offers;
    @Autowired AuthenticatedInventoryCommands stock;
    @Autowired AutoOrderService checkout;
    @Autowired AuthenticatedSalesCommands sales;
    @Autowired AuthenticatedSalesQueries orderReads;
    @Autowired JdbcTemplate jdbc;
    @Autowired kz.rm.intelligence.CanonicalFactAppender canonicalFacts;
    @Autowired org.springframework.transaction.PlatformTransactionManager canonicalTransactions;
    @Autowired kz.rm.intelligence.MetricQueryService intelligence;
    @Autowired kz.rm.intelligence.CalendarPolicyService calendar;

    private UUID account(String prefix) {
        return users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),prefix+"-"+UUID.randomUUID(),prefix)).getId();
    }

    private static JourneySignedActorFixture signedActors;
    @org.junit.jupiter.api.BeforeAll static void startIssuer() throws Exception { signedActors=new JourneySignedActorFixture(); }
    @org.junit.jupiter.api.AfterAll static void stopIssuer(){ if(signedActors!=null)signedActors.close(); }
    private VerifiedActor actor(UUID user){ return signedActors.resolve(jdbc,users,user); }

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

    private enum CanonicalCase { FULL, DIGEST, ADMISSION_ROLLBACK, CONCURRENCY, SEALING, TENANT }

    @Test void busMvpJ1OrganizationEmployeeCustomerTaskSkuOrderStockHistoryPg() {
        runBusJourney(CanonicalCase.FULL);
    }

    @Test void canonicalSalesAndInventoryDigestOraclePg() {
        runBusJourney(CanonicalCase.DIGEST);
    }

    @Test void canonicalSourceAdmissionAndOwnerRollbackPg() {
        runNamedPostgresCase(CanonicalCase.ADMISSION_ROLLBACK);
    }

    @Test void canonicalConcurrentReplayAndRollbackPg() {
        runNamedPostgresCase(CanonicalCase.CONCURRENCY);
    }

    @Test void canonicalSourceSealingLockOrdersPg() {
        runNamedPostgresCase(CanonicalCase.SEALING);
    }

    @Test void canonicalTenantSourceCollisionPg() {
        runNamedPostgresCase(CanonicalCase.TENANT);
    }

    private void runNamedPostgresCase(CanonicalCase scenario){
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        // The strict PostgreSQL workflow and central verifier require these named cases
        // without skips. H2 must never present a successful PostgreSQL locking test.
        org.junit.jupiter.api.Assumptions.assumeTrue(pg,"CANONICAL_POSTGRESQL_REQUIRED");
        runBusJourney(scenario);
    }

    private void runBusJourney(CanonicalCase scenario) {
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
                DomainPermission.SALES_ORDER_READ,DomainPermission.SALES_ORDER_CANCEL,DomainPermission.INVENTORY_RELEASE))
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

        var interaction=interactions.record(staff,tenant,customer.id(),contact.id(),kz.rm.crm.CrmInteractionService.Kind.CALL,staffId,Instant.now(),Instant.now().plusSeconds(3600),"bus-interaction-"+UUID.randomUUID());
        var task=followups.create(staff,tenant,interaction.id(),"Prepare customer call",staffId,
                Instant.now().plusSeconds(3600),"bus-task-"+UUID.randomUUID());
        var started=tasks.start(staff,tenant,task.id(),task.version());
        var finished=tasks.complete(staff,tenant,task.id(),started.version());
        assertThat(finished.state().name()).isEqualTo("COMPLETED");
        assertThat(tasks.historyMine(staff,tenant,task.id(),20)).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.task_crm_followup f JOIN crm.customer_interaction i ON i.tenant_id=f.tenant_id AND i.id=f.interaction_id WHERE f.tenant_id=? AND f.task_id=? AND i.id=? AND i.customer_id=?",Integer.class,tenant,task.id(),interaction.id(),customer.id())).isEqualTo(1);

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

        var beforeDenied=JourneySqlEvidence.orderAndStock(jdbc,tenant,orderId,warehouse.id(),published.id());
        var invoicesBefore=JourneySqlEvidence.invoices(jdbc,tenant);
        for(var invalid:JourneySignedActorFixture.InvalidToken.values()){
            assertThatThrownBy(()->sales.confirm(signedActors.resolveInvalid(jdbc,users,staffId,invalid),tenant,orderId,warehouse.id()))
                .isInstanceOf(SecurityException.class).hasMessage("OIDC token verification failed");
        }
        var foreign=actor(account("bus-foreign"));
        assertThatThrownBy(()->sales.confirm(foreign,tenant,orderId,warehouse.id())).isInstanceOf(SecurityException.class);
        roles.revoke(owner,tenant,role.id(),DomainPermission.SALES_ORDER_CONFIRM);
        assertThatThrownBy(()->sales.confirm(staff,tenant,orderId,warehouse.id())).isInstanceOf(SecurityException.class);
        assertThat(JourneySqlEvidence.orderAndStock(jdbc,tenant,orderId,warehouse.id(),published.id())).isEqualTo(beforeDenied);
        roles.grant(owner,tenant,role.id(),DomainPermission.SALES_ORDER_CONFIRM);
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
        UUID cancelOrder=checkout.checkout(store.slug(),"bus-cancel-"+UUID.randomUUID()+"@example.kz",
                "bus-cancel-order-"+UUID.randomUUID(),List.of(new AutoOrderService.Line(offer.id(),2))).order().orderId();
        assertThat(sales.confirm(staff,tenant,cancelOrder,warehouse.id()).status()).isEqualTo("CONFIRMED");
        assertThat(sales.cancel(staff,tenant,cancelOrder).status()).isEqualTo("CANCELLED");
        assertThat(sales.cancel(staff,tenant,cancelOrder).affectedLines()).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",BigDecimal.class,tenant,warehouse.id(),published.id())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT on_hand FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",BigDecimal.class,tenant,warehouse.id(),published.id())).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=? AND source_type='SALES_ORDER' AND source_id=?",Integer.class,tenant,cancelOrder)).isZero();
        assertThat(orderReads.history(staff,tenant,cancelOrder,0,20)).hasSize(3);
        assertThat(JourneySqlEvidence.invoices(jdbc,tenant)).isEqualTo(invoicesBefore);
        IntelligenceSourceOracle.assertTenantFacts(jdbc, tenant, warehouse.id(), published.id(),
                2, "375", 2, 1, 1, "10", "3", "7");
        if(scenario==CanonicalCase.DIGEST){
            CanonicalFactJourneyEvidence.digestReplayContract(jdbc,canonicalTransactions,canonicalFacts,tenant,cancelOrder);
            CanonicalFactJourneyEvidence.inventoryDigestOracle(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId);
            return;
        }
        if(scenario==CanonicalCase.CONCURRENCY){
            CanonicalFactJourneyEvidence.concurrentReplayOnPostgres(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId,cancelOrder);
            CanonicalFactJourneyEvidence.concurrentRollbackOnPostgres(jdbc,canonicalTransactions,canonicalFacts,tenant,cancelOrder);
            return;
        }
        if(scenario==CanonicalCase.SEALING){
            CanonicalFactJourneyEvidence.sealingRace(jdbc,canonicalTransactions,tenant,orderId);
            CanonicalFactJourneyEvidence.sealingPermutations(jdbc,canonicalTransactions,tenant,orderId);
            return;
        }
        UUID otherOwnerId=account("bus-other-owner");
        proveInitialOwnerEmail(otherOwnerId);
        VerifiedActor otherOwner=actor(otherOwnerId);
        UUID otherTenant=UUID.randomUUID();
        assertThat(onboarding.createFirstTenant(otherOwner,otherTenant,
                "RM Canonical Isolation LLP","RM Canonical Isolation","KZ").tenantId()).isEqualTo(otherTenant);
        currencies.initialize(otherOwner,otherTenant);
        CanonicalFactJourneyEvidence.verify(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId,otherTenant);
        if(scenario==CanonicalCase.TENANT){
            CanonicalFactJourneyEvidence.tenantCollision(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId,otherTenant);
            return;
        }
        String rollbackKey="bus-rollback-"+UUID.randomUUID();
        CanonicalFactJourneyEvidence.ownerRollback(jdbc,canonicalTransactions,canonicalFacts,tenant,rollbackKey,
            ()->checkout.checkout(store.slug(),"bus-rollback-"+UUID.randomUUID()+"@example.kz",
                rollbackKey,List.of(new AutoOrderService.Line(offer.id(),1))).order().orderId());
        if(scenario==CanonicalCase.ADMISSION_ROLLBACK){
            CanonicalFactJourneyEvidence.rejectInconsistentSnapshots(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId);
            return;
        }
        CanonicalFactJourneyEvidence.concurrentReplayOnPostgres(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId,cancelOrder);
        CanonicalFactJourneyEvidence.concurrentRollbackOnPostgres(jdbc,canonicalTransactions,canonicalFacts,tenant,cancelOrder);
        calendar.append(owner,tenant,"Etc/UTC",0);
        var period=new kz.rm.intelligence.MetricSelection.PeriodChoice(
            kz.rm.intelligence.PeriodResolver.Code.Y,java.time.LocalDate.now(java.time.ZoneOffset.UTC),1);
        IntelligenceMetricQualification.allEight(intelligence,staff,tenant,warehouse.id(),published.id(),period);
        var live=new kz.rm.intelligence.MetricQuery(kz.rm.intelligence.MetricRegistry.Code.CURRENT_AVAILABLE,1,
            new kz.rm.intelligence.MetricSelection.LivePosition(warehouse.id(),published.id()));
        assertThatThrownBy(()->intelligence.calculate(foreign,tenant,live)).isInstanceOf(SecurityException.class);
        warehouseScope.revoke(owner,tenant,warehouse.id(),staffId);
        assertThatThrownBy(()->intelligence.calculate(staff,tenant,live)).isInstanceOf(SecurityException.class);
        warehouseScope.assign(owner,tenant,warehouse.id(),staffId);
        roles.revoke(owner,tenant,role.id(),DomainPermission.SALES_ORDER_READ);
        assertThatThrownBy(()->intelligence.calculate(staff,tenant,IntelligenceMetricQualification.order(
            kz.rm.intelligence.MetricRegistry.Code.PLACED_ORDERS,period))).isInstanceOf(SecurityException.class);
        assertThat(tasks.getMine(staff,tenant,task.id()).state().name())
                .isEqualTo("COMPLETED");
        CanonicalFactJourneyEvidence.sealingRace(jdbc,canonicalTransactions,tenant,orderId);
        CanonicalFactJourneyEvidence.sealingPermutations(jdbc,canonicalTransactions,tenant,orderId);
        CanonicalFactJourneyEvidence.rejectInconsistentSnapshots(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId);
        CanonicalFactJourneyEvidence.tenantCollision(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId,otherTenant);
    }
}
