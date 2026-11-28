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
    @Autowired kz.rm.catalog.CatalogGroupService groups;
    @Autowired kz.rm.intelligence.MultidimensionalReadService dimensions;
    @Autowired StorefrontManagementService stores;
    @Autowired StorefrontOfferService offers;
    @Autowired AuthenticatedInventoryCommands stock;
    @Autowired AutoOrderService checkout;
    @Autowired AuthenticatedSalesCommands sales;
    @Autowired AuthenticatedSalesQueries orderReads;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired kz.rm.intelligence.CanonicalFactAppender canonicalFacts;
    @Autowired kz.rm.intelligence.CanonicalFactDeliveryService canonicalDelivery;
    @Autowired kz.rm.intelligence.L1ProjectionService l1;
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

    private enum CanonicalCase { FULL, DIGEST, ADMISSION_ROLLBACK, CONCURRENCY, SEALING, TENANT, DELIVERY, L1, DIMENSIONS }

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

    @Test void p2CanonicalDurableDiscoveryReplayAndBackfillPg() {
        runNamedPostgresCase(CanonicalCase.DELIVERY);
    }

    @Test void p3L1ProjectionParityAndLateDuplicatePg() {
        runNamedPostgresCase(CanonicalCase.L1);
    }

    @Test void b4RegionAndNomenclatureSnapshotsPg(){
        runNamedPostgresCase(CanonicalCase.DIMENSIONS);
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

        kz.rm.catalog.CatalogGroupService.Node root=null,leaf=null;
        if(scenario==CanonicalCase.DIMENSIONS){
            root=groups.appendNode(staff,tenant,CatalogItem.Kind.PRODUCT,"01","Products",null,0);
            leaf=groups.appendNode(staff,tenant,CatalogItem.Kind.PRODUCT,"01.01","Original group",root.id(),0);
            groups.assign(staff,tenant,published.id(),leaf.id(),0);
            var serviceGroup=groups.appendNode(staff,tenant,CatalogItem.Kind.SERVICE,"02","Services",null,0);
            assertThatThrownBy(()->groups.assign(staff,tenant,published.id(),serviceGroup.id(),1))
                .hasMessage("CAT_ASSIGNMENT_GROUP_NOT_FOUND");
            var serviceDraft=catalog.createDraft(staff,tenant,"SVC-"+UUID.randomUUID(),CatalogItem.Kind.SERVICE,"ru","Service",null);
            assertThat(groups.assign(staff,tenant,serviceDraft.id(),serviceGroup.id(),0).groupId()).isEqualTo(serviceGroup.id());
        }
        stock.receive(staff,tenant,warehouse.id(),published.id(),
                "bus-receipt-"+UUID.randomUUID(),new BigDecimal("10"));
        String checkoutEmail="bus-buyer-"+UUID.randomUUID()+"@example.kz";
        String checkoutKey="bus-order-"+UUID.randomUUID();
        var checkoutLines=List.of(new AutoOrderService.Line(offer.id(),3));
        UUID orderId=checkout.checkout(store.slug(),checkoutEmail,checkoutKey,checkoutLines).order().orderId();
        UUID placedEvent=CheckoutCanonicalEvidence.assertPlaced(jdbc,tenant,orderId);
        assertThat(checkout.checkout(store.slug(),checkoutEmail,checkoutKey,checkoutLines).order().orderId()).isEqualTo(orderId);
        assertThat(CheckoutCanonicalEvidence.assertPlaced(jdbc,tenant,orderId)).isEqualTo(placedEvent);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,tenant,orderId)).isZero();

        kz.rm.catalog.CatalogGroupService.Node revisedRoot=null,revisedLeaf=null;
        if(scenario==CanonicalCase.DIMENSIONS){
            revisedRoot=groups.appendNode(staff,tenant,CatalogItem.Kind.PRODUCT,"01","Products revised",null,1);
            revisedLeaf=groups.appendNode(staff,tenant,CatalogItem.Kind.PRODUCT,"01.01","Revised group",revisedRoot.id(),1);
            groups.assign(staff,tenant,published.id(),revisedLeaf.id(),1);
        }
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
        UUID confirmedFact=CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,orderId,"CONFIRMED");
        assertThat(sales.confirm(staff,tenant,orderId,warehouse.id()).affectedLines()).isZero();
        assertThat(CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,orderId,"CONFIRMED")).isEqualTo(confirmedFact);
        assertThat(sales.fulfill(staff,tenant,orderId).status()).isEqualTo("COMPLETED");
        UUID completedFact=CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,orderId,"COMPLETED");
        assertThat(sales.fulfill(staff,tenant,orderId).affectedLines()).isZero();
        assertThat(CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,orderId,"COMPLETED")).isEqualTo(completedFact);
        CheckoutCanonicalEvidence.assertAbsent(jdbc,tenant,orderId,"CANCELLED");

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
        CheckoutCanonicalEvidence.assertPlaced(jdbc,tenant,cancelOrder);
        assertThat(sales.confirm(staff,tenant,cancelOrder,warehouse.id()).status()).isEqualTo("CONFIRMED");
        CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,cancelOrder,"CONFIRMED");
        assertThat(sales.cancel(staff,tenant,cancelOrder).status()).isEqualTo("CANCELLED");
        UUID cancelledFact=CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,cancelOrder,"CANCELLED");
        assertThat(sales.cancel(staff,tenant,cancelOrder).affectedLines()).isZero();
        assertThat(CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,cancelOrder,"CANCELLED")).isEqualTo(cancelledFact);
        CheckoutCanonicalEvidence.assertAbsent(jdbc,tenant,cancelOrder,"COMPLETED");
        assertThat(jdbc.queryForObject("SELECT reserved FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",BigDecimal.class,tenant,warehouse.id(),published.id())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT on_hand FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",BigDecimal.class,tenant,warehouse.id(),published.id())).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=? AND source_type='SALES_ORDER' AND source_id=?",Integer.class,tenant,cancelOrder)).isZero();
        assertThat(orderReads.history(staff,tenant,cancelOrder,0,20)).hasSize(3);
        assertThat(JourneySqlEvidence.invoices(jdbc,tenant)).isEqualTo(invoicesBefore);
        IntelligenceSourceOracle.assertTenantFacts(jdbc, tenant, warehouse.id(), published.id(),
                2, "375", 2, 1, 1, "10", "3", "7");
        // Fresh owner tenant, before synthetic historical fixtures.
        CanonicalProducerEvidence.assertSales(jdbc,tenant);
        CanonicalProducerEvidence.assertInventory(jdbc,tenant);
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
        if(scenario==CanonicalCase.DIMENSIONS){
            Instant from=Instant.now().minusSeconds(86400),until=Instant.now().plusSeconds(86400);
            var booked=dimensions.booked(staff,tenant,from,until,31);
            assertThat(booked).hasSize(2);
            UUID originalRoot=root.id(),newRoot=revisedRoot.id();
            assertThat(booked).anySatisfy(row->{assertThat(row.group().id()).isEqualTo(originalRoot);
                assertThat(row.group().revision()).isEqualTo(1);assertThat(row.amount()).isEqualByComparingTo("225");
                assertThat(row.distinctOrders()).isEqualTo(1);assertThat(row.distinctLines()).isEqualTo(1);
                assertThat(row.distinctSkus()).isEqualTo(1);assertThat(row.productOrderUnits()).isEqualByComparingTo("3");});
            assertThat(booked).anySatisfy(row->{assertThat(row.group().id()).isEqualTo(newRoot);
                assertThat(row.group().revision()).isEqualTo(2);assertThat(row.amount()).isEqualByComparingTo("150");
                assertThat(row.distinctOrders()).isEqualTo(1);assertThat(row.distinctLines()).isEqualTo(1);
                assertThat(row.distinctSkus()).isEqualTo(1);assertThat(row.productOrderUnits()).isEqualByComparingTo("2");});
            var fulfilled=dimensions.fulfilled(staff,tenant,List.of(warehouse.id()),from,until,31);
            assertThat(fulfilled).hasSize(1);
            assertThat(fulfilled.getFirst().region()).isEqualTo(kato);
            assertThat(fulfilled.getFirst().group().id()).isEqualTo(originalRoot);
            assertThat(fulfilled.getFirst().amount()).isEqualByComparingTo("225");
            assertThat(fulfilled.getFirst().distinctOrders()).isEqualTo(1);
            assertThat(fulfilled.getFirst().distinctLines()).isEqualTo(1);
            assertThat(fulfilled.getFirst().distinctSkus()).isEqualTo(1);
            assertThat(fulfilled.getFirst().productOrderUnits()).isEqualByComparingTo("3");
            var first=dimensions.stock(staff,tenant,List.of(warehouse.id()),from,until,31);
            assertThat(first).hasSize(2);
            assertThat(first).anySatisfy(row->{assertThat(row.region()).isEqualTo(kato);
                assertThat(row.group().id()).isEqualTo(originalRoot);assertThat(row.in()).isEqualByComparingTo("10");});
            assertThat(first).anySatisfy(row->{assertThat(row.group().id()).isEqualTo(newRoot);
                assertThat(row.out()).isEqualByComparingTo("3");});
            String secondKato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory WHERE is_deleted=FALSE AND level_no=2 AND code<>?
                ORDER BY code LIMIT 1
                """,String.class,kato);
            String childKato=jdbc.query("""
                SELECT c.code FROM public.ref_kato_territory c JOIN public.ref_kato_territory p
                  ON p.source_id=c.parent_source_id
                WHERE p.code=? AND c.level_no=3 AND c.is_deleted=FALSE ORDER BY c.code LIMIT 1
                """,rs->rs.next()?rs.getString(1):null,secondKato);
            var second=locations.create(staff,tenant,OrganizationLocationEntity.Type.WAREHOUSE,
                "Second regional warehouse",childKato==null?secondKato:childKato,"Second warehouse");
            assertThatThrownBy(()->dimensions.stock(staff,tenant,List.of(warehouse.id(),second.id()),from,until,31))
                .isInstanceOf(SecurityException.class);
            var b55=applicationContext.getBean(kz.rm.intelligence.BusinessConclusionService.class);
            var endDay=java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            var days=List.of(endDay.minusDays(1),endDay);
            long b55Calendar=calendar.append(owner,tenant,"Etc/UTC",0).version();
            var mixedWarehouses=new kz.rm.intelligence.AuthorizedAnalyticalReadService.Query(
                kz.rm.intelligence.AuthorizedAnalyticalReadService.Metric.STOCK_IN,b55Calendar,
                kz.rm.intelligence.PeriodResolver.Code.D,endDay,null,null,published.id(),List.of(warehouse.id(),second.id()),null);
            assertThatThrownBy(()->b55.concludePattern(staff,tenant,mixedWarehouses,days,1,BigDecimal.ONE,BigDecimal.ZERO,
                1,new BigDecimal("3"),new BigDecimal("0.5"))).isInstanceOf(SecurityException.class);
            warehouseScope.assign(owner,tenant,second.id(),staffId);
            stock.receive(staff,tenant,second.id(),published.id(),"b4-second-region-"+UUID.randomUUID(),new BigDecimal("5"));
            var regions=dimensions.stock(staff,tenant,List.of(warehouse.id(),second.id()),from,until,31);
            assertThat(regions).hasSize(3);
            assertThat(regions).anySatisfy(row->{assertThat(row.region()).isEqualTo(secondKato);
                assertThat(row.group().id()).isEqualTo(newRoot);assertThat(row.in()).isEqualByComparingTo("5");});
            assertThatThrownBy(()->dimensions.stock(otherOwner,otherTenant,List.of(warehouse.id()),from,until,31))
                .isInstanceOf(SecurityException.class);
            // The second owner has no operational role: authorization is checked before lookup.
            assertThatThrownBy(()->groups.assign(otherOwner,otherTenant,published.id(),newRoot,0))
                .isInstanceOf(SecurityException.class).hasMessage("Tenant RBAC permission denied");
            // Separately prove the data boundary AFTER successful admission within tenant B.
            UUID otherStaffId=account("bus-other-dimension-staff");
            VerifiedActor otherStaff=actor(otherStaffId);
            assertThat(enrollment.invite(otherOwner,otherTenant,otherStaffId).status()).isEqualTo("INVITED");
            assertThat(enrollment.accept(otherStaff,otherTenant).status()).isEqualTo("ACTIVE");
            var otherRole=roles.createRole(otherOwner,otherTenant,"DIMENSION_TEST","Dimension isolation test");
            for(var permission:List.of(DomainPermission.CATALOG_WRITE,DomainPermission.SALES_ORDER_READ,
                    DomainPermission.INVENTORY_STOCK_READ))
                roles.grant(otherOwner,otherTenant,otherRole.id(),permission);
            roles.assign(otherOwner,otherTenant,otherStaffId,otherRole.id());
            roles.enable(otherOwner,otherTenant);
            assertThat(dimensions.booked(otherStaff,otherTenant,from,until,31)).isEmpty();
            assertThatThrownBy(()->groups.assign(otherStaff,otherTenant,published.id(),newRoot,0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CAT_ASSIGNMENT_ITEM_NOT_FOUND");
            var otherItem=catalog.createDraft(otherStaff,otherTenant,"OTHER-"+UUID.randomUUID(),
                CatalogItem.Kind.PRODUCT,"ru","Other tenant item",null);
            assertThatThrownBy(()->groups.assign(otherStaff,otherTenant,otherItem.id(),newRoot,0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CAT_ASSIGNMENT_GROUP_NOT_FOUND");
            var otherGroup=groups.appendNode(otherStaff,otherTenant,CatalogItem.Kind.PRODUCT,"01","Other tenant group",null,0);
            assertThat(groups.assign(otherStaff,otherTenant,otherItem.id(),otherGroup.id(),0).groupId()).isEqualTo(otherGroup.id());
            var foreignSku=new kz.rm.intelligence.AuthorizedAnalyticalReadService.Query(
                kz.rm.intelligence.AuthorizedAnalyticalReadService.Metric.STOCK_IN,b55Calendar,
                kz.rm.intelligence.PeriodResolver.Code.D,endDay,null,null,otherItem.id(),List.of(warehouse.id()),null);
            var foreignGroup=new kz.rm.intelligence.AuthorizedAnalyticalReadService.Query(
                kz.rm.intelligence.AuthorizedAnalyticalReadService.Metric.STOCK_IN,b55Calendar,
                kz.rm.intelligence.PeriodResolver.Code.D,endDay,null,otherGroup.id(),published.id(),List.of(warehouse.id()),null);
            for(var invalidScope:List.of(foreignSku,foreignGroup))
                assertThatThrownBy(()->b55.concludePattern(staff,tenant,invalidScope,days,1,BigDecimal.ONE,BigDecimal.ZERO,
                    1,new BigDecimal("3"),new BigDecimal("0.5"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOT_FOUND");
            long otherB55Calendar=calendar.append(otherOwner,otherTenant,"Etc/UTC",0).version();
            var qA=new kz.rm.intelligence.AuthorizedAnalyticalReadService.Query(
                kz.rm.intelligence.AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS,b55Calendar,
                kz.rm.intelligence.PeriodResolver.Code.D,endDay,null,null,null,List.of(),null);
            var qB=new kz.rm.intelligence.AuthorizedAnalyticalReadService.Query(
                kz.rm.intelligence.AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS,otherB55Calendar,
                kz.rm.intelligence.PeriodResolver.Code.D,endDay,null,null,null,List.of(),null);
            ConclusionConcurrencyEvidence.twoTenants(b55,staff,tenant,qA,otherStaff,otherTenant,qB,days);
            var r02=new kz.rm.intelligence.SalesReportMaterializationEvidence(applicationContext,jdbc,canonicalTransactions);
            var lastClosed=endDay.withDayOfMonth(1).minusMonths(1);
            r02.denied(otherStaff,otherTenant,otherB55Calendar,lastClosed);
            roles.grant(otherOwner,otherTenant,otherRole.id(),DomainPermission.REPORT_GENERATE);
            r02.denied(otherStaff,tenant,b55Calendar,lastClosed);
            r02.emptyTenant(otherStaff,otherTenant,tenant,otherB55Calendar,lastClosed);
            roles.revoke(otherOwner,otherTenant,otherRole.id(),DomainPermission.SALES_ORDER_READ);
            r02.deniedReplay(otherStaff,otherTenant,otherB55Calendar,lastClosed);
            r02.denied(otherStaff,otherTenant,otherB55Calendar,lastClosed);
            roles.grant(otherOwner,otherTenant,otherRole.id(),DomainPermission.SALES_ORDER_READ);
            roles.revoke(otherOwner,otherTenant,otherRole.id(),DomainPermission.REPORT_GENERATE);
            r02.deniedReplay(otherStaff,otherTenant,otherB55Calendar,lastClosed);
            r02.denied(otherStaff,otherTenant,otherB55Calendar,lastClosed);

            assertThatThrownBy(()->dimensions.stock(otherStaff,otherTenant,List.of(warehouse.id()),from,until,31))
                .isInstanceOf(SecurityException.class).hasMessage("WAREHOUSE_SCOPE_REQUIRED");
            assertThatThrownBy(()->jdbc.update("UPDATE crm.cat_group_node SET label=label WHERE tenant_id=? AND id=?",tenant,originalRoot))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE crm.int_movement_dimension SET region_code=region_code WHERE tenant_id=?",tenant))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            // A synthetic legacy movement is backfilled without current category/geography.
            UUID legacy=UUID.randomUUID();
            new org.springframework.transaction.support.TransactionTemplate(canonicalTransactions).executeWithoutResult(status->{
                assertThat(jdbc.update("""
                    INSERT INTO crm.inv_stock_movement(tenant_id,id,location_id,item_id,request_key,delta_quantity,balance_before,balance_after,
                      occurred_at,recorded_at,reason_code,movement_kind,actor_user_id,source_type,source_id,business_reason_code,posting_version)
                    SELECT tenant_id,?,location_id,item_id,?,delta_quantity,balance_before,balance_after,
                      occurred_at,recorded_at,reason_code,movement_kind,actor_user_id,source_type,source_id,business_reason_code,NULL
                    FROM crm.inv_stock_movement WHERE tenant_id=? AND location_id=? AND reason_code='RECEIPT' ORDER BY id LIMIT 1
                    """,legacy,"b4-legacy-"+legacy,tenant,warehouse.id())).isEqualTo(1);
                canonicalFacts.append(new kz.rm.intelligence.CanonicalFactKey(tenant,kz.rm.intelligence.CanonicalFactKey.Owner.INVENTORY,
                    kz.rm.intelligence.CanonicalFactKey.Kind.STOCK_MOVEMENT,legacy));
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_movement_dimension WHERE tenant_id=? AND movement_id=?",
                Long.class,tenant,legacy)).isZero();
            // Same-tenant IDs are insufficient: the snapshot must match its source.
            var wrongSku=catalog.createDraft(staff,tenant,"B4-WRONG-"+UUID.randomUUID(),
                CatalogItem.Kind.PRODUCT,"ru","Wrong same-tenant SKU",null);
            String insertDim="""
                INSERT INTO crm.int_movement_dimension(tenant_id,movement_id,item_id,item_kind,
                    location_id,kato_code,region_code,geo_quality)
                VALUES (?,?,?,'PRODUCT',?,?,?,'RESOLVED')
                """;
            assertThatThrownBy(()->jdbc.update(insertDim,tenant,legacy,wrongSku.id(),warehouse.id(),kato,kato))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("fk_int_movement_dim_source");
            assertThatThrownBy(()->jdbc.update(insertDim,tenant,legacy,published.id(),second.id(),
                childKato==null?secondKato:childKato,secondKato))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("fk_int_movement_dim_source");
            assertThatThrownBy(()->jdbc.update(insertDim,tenant,legacy,published.id(),warehouse.id(),secondKato,secondKato))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("INT_MOVEMENT_DIM_KATO_MISMATCH");
            assertThatThrownBy(()->jdbc.update(insertDim,tenant,legacy,published.id(),warehouse.id(),kato,secondKato))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("INT_MOVEMENT_DIM_REGION_MISMATCH");
            UUID bookedLine=jdbc.queryForObject("SELECT id FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?",
                UUID.class,tenant,orderId);
            // BEFORE INSERT guard rejects false UNKNOWN and different SKU before
            // duplicate-key handling for the already captured line.
            assertThatThrownBy(()->jdbc.update("""
                INSERT INTO crm.int_sales_line_dimension(tenant_id,line_id) VALUES (?,?)
                """,tenant,bookedLine)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("INT_SALES_DIM_SOURCE_ITEM_MISMATCH");
            assertThatThrownBy(()->jdbc.update("""
                INSERT INTO crm.int_sales_line_dimension(tenant_id,line_id,item_id,item_kind)
                VALUES (?,?,?,'PRODUCT')
                """,tenant,bookedLine,wrongSku.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("INT_SALES_DIM_SOURCE_ITEM_MISMATCH");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_movement_dimension WHERE tenant_id=? AND movement_id=?",
                Long.class,tenant,legacy)).isZero();
            var geoCoverage=dimensions.stockCoverage(staff,tenant,List.of(warehouse.id()),from,until);
            assertThat(geoCoverage.sourceMovements()).isEqualTo(3);
            assertThat(geoCoverage.missingSnapshots()).isEqualTo(1);
            assertThat(geoCoverage.unknownRegion()).isZero();
            assertThat(geoCoverage.resolvedRegion()).isEqualTo(2);
            assertThat(geoCoverage.allClassified()).isFalse();
            assertThatThrownBy(()->dimensions.stockCoverage(otherStaff,otherTenant,List.of(warehouse.id()),from,until))
                .isInstanceOf(SecurityException.class);
            assertThat(dimensions.stock(staff,tenant,List.of(warehouse.id()),from,until,31)).anySatisfy(row->{
                assertThat(row.region()).isNull();assertThat(row.group().id()).isNull();assertThat(row.in()).isEqualByComparingTo("10");
            });
            roles.grant(owner,tenant,role.id(),DomainPermission.REPORT_GENERATE);
            roles.grant(otherOwner,otherTenant,otherRole.id(),DomainPermission.REPORT_GENERATE);
            r02.historicalAndConcurrent(staff,tenant,b55Calendar,otherStaff,otherTenant,otherB55Calendar,lastClosed,orderId);
            r02.accountingPolicyRevision(otherOwner,otherStaff,otherTenant,otherB55Calendar,lastClosed);
            roles.revoke(owner,tenant,role.id(),DomainPermission.REPORT_GENERATE);
            roles.revoke(otherOwner,otherTenant,otherRole.id(),DomainPermission.REPORT_GENERATE);
            return;
        }
        if(scenario==CanonicalCase.L1){
            long calendarVersion=calendar.append(owner,tenant,"Etc/UTC",0).version();
            L1ProjectionJourneyEvidence.qualify(jdbc,canonicalTransactions,canonicalFacts,canonicalDelivery,
                l1,tenant,otherTenant,orderId,warehouse.id(),published.id(),calendarVersion,applicationContext,staff,
                ()->warehouseScope.revoke(owner,tenant,warehouse.id(),staffId),
                ()->warehouseScope.assign(owner,tenant,warehouse.id(),staffId),otherOwner);
            return;
        }
        if(scenario==CanonicalCase.DELIVERY){
            CanonicalDeliveryJourneyEvidence.qualify(jdbc,canonicalTransactions,canonicalFacts,canonicalDelivery,
                tenant,otherTenant,orderId);
            return;
        }
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
            CanonicalFactJourneyEvidence.rejectUnsealedSourceOnPostgres(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId);
            String faultKey="bus-fact-failure-"+UUID.randomUUID();
            String faultEmail="bus-fact-failure-"+UUID.randomUUID()+"@example.kz";
            CanonicalFactJourneyEvidence.checkoutAppendFailureOnPostgres(jdbc,canonicalTransactions,tenant,faultKey,
                ()->checkout.checkout(store.slug(),faultEmail,faultKey,List.of(new AutoOrderService.Line(offer.id(),1))).order().orderId());
            NativeBookedCaptureJourneyEvidence.qualify(jdbc,canonicalTransactions,
                applicationContext.getBean(kz.rm.intelligence.NativeBookedCaptureService.class),
                owner,staff,otherOwner,tenant,otherTenant,orderId,
                ()->checkout.checkout(store.slug(),"native-"+UUID.randomUUID()+"@example.kz",
                    "native-"+UUID.randomUUID(),List.of(new AutoOrderService.Line(offer.id(),1))).order().orderId());
            long nativeCalendar=calendar.append(owner,tenant,"Asia/Almaty",0).version();
            NativeBookedClosureJourneyEvidence.qualify(jdbc,canonicalTransactions,canonicalFacts,
                applicationContext.getBean(kz.rm.intelligence.NativeBookedClosureService.class),
                staff,otherOwner,tenant,otherTenant,orderId,nativeCalendar,
                ()->checkout.checkout(store.slug(),"closure-"+UUID.randomUUID()+"@example.kz",
                    "closure-"+UUID.randomUUID(),List.of(new AutoOrderService.Line(offer.id(),1))).order().orderId());
            return;
        }
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
        CanonicalFactJourneyEvidence.concurrentReplayOnPostgres(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId,cancelOrder);
        CanonicalFactJourneyEvidence.concurrentRollbackOnPostgres(jdbc,canonicalTransactions,canonicalFacts,tenant,cancelOrder);
        CanonicalFactJourneyEvidence.sealingRace(jdbc,canonicalTransactions,tenant,orderId);
        CanonicalFactJourneyEvidence.sealingPermutations(jdbc,canonicalTransactions,tenant,orderId);
        CanonicalFactJourneyEvidence.rejectInconsistentSnapshots(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId);
        CanonicalFactJourneyEvidence.tenantCollision(jdbc,canonicalTransactions,canonicalFacts,tenant,orderId,otherTenant);
    }
}
