package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.autoorder.PublishedQrCheckoutService;
import kz.rm.catalog.CatalogItem;
import kz.rm.catalog.CatalogItemService;
import kz.rm.iam.*;
import kz.rm.inventory.AuthenticatedInventoryCommands;
import kz.rm.organization.CommerceCurrencyAdmission;
import kz.rm.organization.TenantCurrencyPolicyService;
import kz.rm.storefront.PublishedOfferCardQuery;
import kz.rm.storefront.PublishedOfferQrService;
import kz.rm.storefront.StorefrontManagementService;
import kz.rm.storefront.StorefrontOfferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * QR-MVP-J1 strict service journeys. The buyer identity is a synthetic
 * verified IAM fixture; all offer, QR, checkout, reservation, pickup and
 * return transitions use production application services, not SQL writers.
 */
@SpringBootTest(properties={
    "rm.mvp.service-mode.enabled=true",
    "rm.currency.commerce-admission.enabled=true",
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class QrMvpServiceJourneyQualificationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired WarehouseStaffScopeService scopes;
    @Autowired TenantCurrencyPolicyService currencies;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired CatalogItemService catalog;
    @Autowired StorefrontManagementService stores;
    @Autowired StorefrontOfferService offers;
    @Autowired PublishedOfferQrService qr;
    @Autowired PublishedOfferCardQuery publicCards;
    @Autowired PublishedQrCheckoutService listCheckout;
    @Autowired SalesPriceProposalService proposals;
    @Autowired AutoOrderService quoteCheckout;
    @Autowired AuthenticatedInventoryCommands inventory;
    @Autowired AuthenticatedSalesCommands sales;
    @Autowired SalesOrderPreparationService prep;
    @Autowired SalesPickupHandoverService pickup;
    @Autowired SalesReturnRequestService returns;
    @Autowired SalesReturnReviewService review;
    @Autowired SalesReturnReceptionProofService receptionProof;
    @Autowired SalesReturnPhysicalService physical;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired kz.rm.crm.AuthenticatedCrmCommands crm;
    @Autowired kz.rm.crm.CrmBuyerLinkService links;
    @Autowired kz.rm.security.kms.TenantKeyService tenantKeys;

    private record Scenario(UUID tenant,UUID warehouse,UUID sku,UUID offer,
                            String publicQr, long offerVersion,
                            VerifiedActor owner,VerifiedActor staff,
                            VerifiedActor buyer) {}

    private static String key(){return "QR_MVP_"+UUID.randomUUID().toString().replace("-","");}

    private static JourneySignedActorFixture signedActors;
    @org.junit.jupiter.api.BeforeAll static void startIssuer() throws Exception { signedActors=new JourneySignedActorFixture(); }
    @org.junit.jupiter.api.AfterAll static void stopIssuer(){ if(signedActors!=null)signedActors.close(); }
    private VerifiedActor actor(UUID user){ return signedActors.resolve(jdbc,users,user); }

    private Scenario setup() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"QR MVP Seller LLP","QR MVP Store","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID ownerId=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"qr-mvp-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID staffId=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"qr-mvp-staff-"+UUID.randomUUID(),"Staff")).getId();
        UUID buyerId=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"qr-mvp-buyer-"+UUID.randomUUID(),"Buyer")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),ownerId,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),staffId,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);

        VerifiedActor owner=actor(ownerId),staff=actor(staffId),buyer=actor(buyerId);
        String email="qr-mvp-"+UUID.randomUUID()+"@example.kz";
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO public.iam_email_identity
                 (id,user_id,lookup_hmac,email_envelope,verified_at,created_at,
                  lifecycle_state,active_lookup_hmac,current_owner_id)
                VALUES (?,?,?,?,?,?,'CURRENT',?,?)
                """,UUID.randomUUID(),buyerId,crypto.lookup(email),
                crypto.encrypt(email),now,now,crypto.lookup(email),buyerId);

        var role=roles.createRole(owner,tenant,
                "QRMVP_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "QR seller and warehouse operator");
        for(var permission:List.of(
                DomainPermission.CATALOG_WRITE,DomainPermission.CATALOG_PRICE_MANAGE,
                DomainPermission.CATALOG_PUBLISH,
                DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH,
                DomainPermission.SALES_PROPOSAL_RESPOND,
                DomainPermission.INVENTORY_RECEIVE,DomainPermission.INVENTORY_STOCK_READ,
                DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_READ,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL,
                DomainPermission.SALES_RETURN_REVIEW,
                DomainPermission.SALES_RETURN_RECEIVE,
                DomainPermission.SALES_RETURN_INSPECT,
                DomainPermission.SALES_RETURN_POST,
                DomainPermission.INVENTORY_ADJUST,DomainPermission.CRM_WRITE,DomainPermission.CRM_READ))
            roles.grant(owner,tenant,role.id(),permission);
        roles.assign(owner,tenant,staffId,role.id());
        roles.enable(owner,tenant);
        currencies.initialize(owner,tenant);
        tenantKeys.provision(tenant);

        String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        UUID warehouse=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.org_location
                 (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
                VALUES (?,?,'WAREHOUSE','QR strict warehouse',?,'ACTIVE',?,?,0)
                """,warehouse,tenant,kato,now,now);
        scopes.assign(owner,tenant,warehouse,staffId);

        var draft=catalog.createDraft(staff,tenant,"QRMVP-"+UUID.randomUUID(),
                CatalogItem.Kind.PRODUCT,"ru","Published QR product",null);
        var priced=catalog.addPriceRevision(staff,tenant,draft.id(),
                new BigDecimal("100.0000"),"KZT",Instant.now().minusSeconds(100),
                draft.rowVersion());
        var product=catalog.publish(staff,tenant,priced.id(),priced.rowVersion());
        var sfDraft=stores.createDraft(staff,tenant,"qr-mvp-"+UUID.randomUUID());
        stores.publish(staff,tenant,sfDraft.id(),sfDraft.rowVersion());
        var draftOffer=offers.createDraft(staff,tenant,product.id(),"QR item",
                new BigDecimal("100.0000"),"KZT");
        var published=offers.publish(staff,tenant,draftOffer.id(),draftOffer.rowVersion());
        inventory.receive(staff,tenant,warehouse,product.id(),key(),new BigDecimal("10"));
        var reference=qr.issueOrGet(staff,tenant,published.id(),published.rowVersion(),key());
        assertThat(publicCards.findPublished(reference.publicRef(),"ru").currency()).isEqualTo("KZT");
        return new Scenario(tenant,warehouse,product.id(),published.id(),
                reference.publicRef(),published.rowVersion(),owner,staff,buyer);
    }

    private void linkBuyer(Scenario s,UUID order){
        var customer=crm.createCompany(s.staff(),s.tenant(),"QR linked customer",null);
        var link=links.propose(s.staff(),s.tenant(),customer.id(),s.buyer().userId(),key());
        assertThat(links.orderHistory(s.staff(),s.tenant(),customer.id(),20)).isEmpty();
        long eventsBefore=jdbc.queryForObject("SELECT count(*) FROM crm.customer_buyer_link_event WHERE tenant_id=? AND link_id=?",Long.class,s.tenant(),link.id());
        assertThatThrownBy(()->links.confirm(s.staff(),s.tenant(),link.id(),0))
            .isInstanceOf(SecurityException.class).hasMessage("Only linked buyer can confirm");
        assertThat(links.orderHistory(s.staff(),s.tenant(),customer.id(),20)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.customer_buyer_link_event WHERE tenant_id=? AND link_id=?",Long.class,s.tenant(),link.id())).isEqualTo(eventsBefore);
        assertThat(links.confirm(s.buyer(),s.tenant(),link.id(),0).state()).isEqualTo(kz.rm.crm.CrmBuyerLinkService.State.CONFIRMED);
        assertThat(links.orderHistory(s.staff(),s.tenant(),customer.id(),20))
            .extracting(kz.rm.crm.CrmBuyerLinkService.BuyerOrder::orderId).containsExactly(order);
    }

    private long orderVersion(Scenario s,UUID orderId) {
        return jdbc.queryForObject("""
                SELECT row_version FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,Long.class,s.tenant(),orderId);
    }

    private BigDecimal sellable(Scenario s) {
        return jdbc.queryForObject("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,s.tenant(),s.warehouse(),s.sku());
    }

    private String orderRef(Scenario s,UUID orderId) {
        return jdbc.queryForObject("""
                SELECT public_ref FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,String.class,s.tenant(),orderId);
    }

    private UUID handover(Scenario s,UUID orderId,int qty,boolean genericFirst) throws Exception {
        assertThat(sales.confirm(s.staff(),s.tenant(),orderId,s.warehouse()).status())
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT fulfillment_mode FROM crm.sales_order WHERE tenant_id=? AND id=?",String.class,s.tenant(),orderId))
            .isEqualTo("PICKUP_PROOF_REQUIRED");
        assertGenericDeniedWithoutEffects(s,orderId);
        prep.startPreparing(s.staff(),s.tenant(),orderId,s.warehouse(),
                orderVersion(s,orderId),key());
        UUID lineId=jdbc.queryForObject("""
                SELECT id FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                """,UUID.class,s.tenant(),orderId);
        assertGenericDeniedWithoutEffects(s,orderId);
        prep.confirmPicked(s.staff(),s.tenant(),orderId,s.warehouse(),
                lineId,qty,orderVersion(s,orderId),key());
        prep.markReadyForPickup(s.staff(),s.tenant(),orderId,s.warehouse(),
                orderVersion(s,orderId),key());
        var issued=pickup.issue(s.buyer(),orderRef(s,orderId),key());
        assertThat(pickup.preview(s.staff(),s.tenant(),s.warehouse(),
                issued.rawProof()).status()).isEqualTo("READY_FOR_PICKUP");
        long before=orderVersion(s,orderId);
        var beforeDenied=JourneySqlEvidence.orderAndStock(jdbc,s.tenant(),orderId,s.warehouse(),s.sku());
        assertThatThrownBy(()->pickup.complete(s.staff(),s.tenant(),s.warehouse(),issued.rawProof(),false,before,key()))
            .isInstanceOf(SecurityException.class).hasMessage("EXPLICIT_HANDOVER_CONFIRM_REQUIRED");
        assertThat(JourneySqlEvidence.orderAndStock(jdbc,s.tenant(),orderId,s.warehouse(),s.sku())).isEqualTo(beforeDenied);
        String handoverKey=key();
        assertGenericDeniedWithoutEffects(s,orderId);
        var committedRef=new java.util.concurrent.atomic.AtomicReference<SalesPickupHandoverService.Handover>();
        Runnable secure=()->committedRef.set(pickup.complete(s.staff(),s.tenant(),s.warehouse(),
            issued.rawProof(),true,before,handoverKey));
        Runnable generic=()->sales.fulfill(s.staff(),s.tenant(),orderId);
        boolean pg;
        try(var connection=dataSource.getConnection()){
            pg=connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        }
        if(pg){
            if(genericFirst)PgCommitOrderEvidence.runDeniedFirst(jdbc,transactionManager,generic,secure,
                IllegalStateException.class,"ORDER_FULFILL_REQUIRES_PICKUP_PROOF");
            else PgCommitOrderEvidence.run(jdbc,transactionManager,secure,generic,
                IllegalStateException.class,"ORDER_FULFILL_REQUIRES_PICKUP_PROOF");
        }else secure.run();
        var committed=committedRef.get();
        assertThat(committed).isNotNull();
        assertThat(orderVersion(s,orderId)).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("SELECT fulfillment_mode FROM crm.sales_order WHERE tenant_id=? AND id=?",String.class,s.tenant(),orderId))
            .isEqualTo("PICKUP_PROOF_REQUIRED");
        assertGenericDeniedWithoutEffects(s,orderId);
        assertThat(committed.status()).isEqualTo("COMPLETED");
        var beforeReplay=JourneySqlEvidence.orderAndStock(jdbc,s.tenant(),orderId,s.warehouse(),s.sku());
        assertThat(pickup.complete(s.staff(),s.tenant(),s.warehouse(),
                issued.rawProof(),true,before,handoverKey).handoverId())
                .isEqualTo(committed.handoverId());
        assertThat(JourneySqlEvidence.orderAndStock(jdbc,s.tenant(),orderId,s.warehouse(),s.sku())).isEqualTo(beforeReplay);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover WHERE tenant_id=? AND order_id=?
                """,Integer.class,s.tenant(),orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND movement_kind='FULFILLMENT'
                  AND source_type='SALES_ORDER' AND source_id=?
                """,Integer.class,s.tenant(),orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover h
                JOIN crm.sales_pickup_credential p ON p.tenant_id=h.tenant_id AND p.id=h.credential_id
                JOIN crm.sales_pickup_handover_line hl ON hl.tenant_id=h.tenant_id AND hl.handover_id=h.id
                JOIN crm.inv_stock_reservation r ON r.tenant_id=hl.tenant_id AND r.id=hl.reservation_id
                JOIN crm.inv_stock_movement m ON m.tenant_id=hl.tenant_id AND m.id=hl.movement_id
                JOIN crm.sales_order_status_event e ON e.tenant_id=h.tenant_id AND e.id=h.status_event_id
                WHERE h.tenant_id=? AND h.order_id=? AND h.id=?
                  AND p.order_id=h.order_id AND p.state='CONSUMED'
                  AND r.order_id=h.order_id AND r.state='FULFILLED'
                  AND r.quantity=hl.quantity AND hl.quantity=?
                  AND m.reservation_id=r.id AND m.movement_kind='FULFILLMENT'
                  AND m.delta_quantity=-hl.quantity AND m.item_id=r.item_id
                  AND m.location_id=r.location_id AND r.location_id=h.warehouse_id
                  AND p.warehouse_id=h.warehouse_id AND p.consumed_at IS NOT NULL
                  AND e.order_id=h.order_id AND e.event_type='COMPLETED'
                """,Integer.class,s.tenant(),orderId,committed.handoverId(),qty)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=? AND event_type='COMPLETED'",Integer.class,s.tenant(),orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",BigDecimal.class,s.tenant(),s.warehouse(),s.sku())).isEqualByComparingTo("0");
        assertThat(sellable(s)).isEqualByComparingTo(BigDecimal.TEN.subtract(BigDecimal.valueOf(qty)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=? AND order_id=? AND state='ACTIVE'",Integer.class,s.tenant(),orderId)).isZero();
        return lineId;
    }

    private void assertGenericDeniedWithoutEffects(Scenario s,UUID orderId){
        var before=JourneySqlEvidence.orderAndStock(jdbc,s.tenant(),orderId,s.warehouse(),s.sku());
        var credentials=jdbc.queryForList("SELECT id,state,row_version,consumed_at FROM crm.sales_pickup_credential WHERE tenant_id=? AND order_id=? ORDER BY id",s.tenant(),orderId);
        var reservations=jdbc.queryForList("SELECT id,quantity,state,row_version FROM crm.inv_stock_reservation WHERE tenant_id=? AND order_id=? ORDER BY id",s.tenant(),orderId);
        assertThatThrownBy(()->sales.fulfill(s.staff(),s.tenant(),orderId))
            .isInstanceOf(IllegalStateException.class).hasMessage("ORDER_FULFILL_REQUIRES_PICKUP_PROOF");
        assertThat(JourneySqlEvidence.orderAndStock(jdbc,s.tenant(),orderId,s.warehouse(),s.sku())).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT id,state,row_version,consumed_at FROM crm.sales_pickup_credential WHERE tenant_id=? AND order_id=? ORDER BY id",s.tenant(),orderId)).isEqualTo(credentials);
        assertThat(jdbc.queryForList("SELECT id,quantity,state,row_version FROM crm.inv_stock_reservation WHERE tenant_id=? AND order_id=? ORDER BY id",s.tenant(),orderId)).isEqualTo(reservations);
    }

    private void returnAndInspect(Scenario s,UUID orderId,UUID lineId,int qty,
                                  SalesReturnPhysicalService.Disposition disposition) {
        String ref=orderRef(s,orderId);
        var claim=returns.request(s.buyer(),ref,lineId,qty,"DAMAGED","UAT_1",key());
        review.startReview(s.staff(),s.tenant(),claim.requestId(),0,"UAT_1",key());
        review.approve(s.staff(),s.tenant(),claim.requestId(),1,"UAT_1",key());
        var proof=receptionProof.issue(s.buyer(),s.tenant(),claim.requestId(),key());
        physical.receive(s.staff(),s.tenant(),s.warehouse(),claim.requestId(),2,
                proof.rawProof(),key());
        physical.inspect(s.staff(),s.tenant(),s.warehouse(),claim.requestId(),4,
                disposition,"INSPECT_MVP",key());
        String postingKey=key();
        var posted=physical.post(s.staff(),s.tenant(),s.warehouse(),
                claim.requestId(),5,postingKey);
        assertThat(physical.post(s.staff(),s.tenant(),s.warehouse(),
                claim.requestId(),5,postingKey).evidenceId())
                .isEqualTo(posted.evidenceId());
        assertThat(jdbc.queryForObject("""
                SELECT status FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),orderId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_posting
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,s.tenant(),claim.requestId())).isEqualTo(1);
    }

    @Test void qrMvpJ1VerifiedListPriceToHandoverAndRestockPg() throws Exception {
        Scenario s=setup();
        String requestKey=key();
        UUID order=listCheckout.checkoutVerified(s.buyer(),s.publicQr(),3,
                s.offerVersion(),new BigDecimal("100.0000"),"KZT",requestKey)
                .order().orderId();
        UUID placed=kz.rm.iam.CheckoutCanonicalEvidence.assertPlaced(jdbc,s.tenant(),order);
        assertThat(listCheckout.checkoutVerified(s.buyer(),s.publicQr(),3,
                s.offerVersion(),new BigDecimal("100.0000"),"KZT",requestKey).order().orderId()).isEqualTo(order);
        assertThat(kz.rm.iam.CheckoutCanonicalEvidence.assertPlaced(jdbc,s.tenant(),order)).isEqualTo(placed);
        assertThat(jdbc.queryForObject("""
                SELECT buyer_user_id FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,UUID.class,s.tenant(),order)).isEqualTo(s.buyer().userId());
        assertThat(jdbc.queryForObject("""
                SELECT fulfillment_mode FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),order)).isEqualTo("PICKUP_PROOF_REQUIRED");
        var invoicesBefore=JourneySqlEvidence.invoices(jdbc,s.tenant());
        linkBuyer(s,order);
        kz.rm.iam.IntelligenceSourceOracle.assertTenantFacts(jdbc, s.tenant(), s.warehouse(), s.sku(),
                1, "300", 0, 0, 0, "10", "0", "10");
        UUID line=handover(s,order,3,true);
        assertThat(sellable(s)).isEqualByComparingTo("7");
        returnAndInspect(s,order,line,2,SalesReturnPhysicalService.Disposition.RESTOCK);
        assertThat(sellable(s)).isEqualByComparingTo("9");
        returnAndInspect(s,order,line,1,SalesReturnPhysicalService.Disposition.WRITE_OFF);
        assertThat(sellable(s)).isEqualByComparingTo("9");
        kz.rm.iam.IntelligenceSourceOracle.assertTenantFacts(jdbc, s.tenant(), s.warehouse(), s.sku(),
                1, "300", 1, 1, 0, "12", "3", "9");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_return_posting p JOIN crm.sales_return_request r ON r.tenant_id=p.tenant_id AND r.id=p.return_id WHERE p.tenant_id=? AND r.order_id=? AND p.disposition='WRITE_OFF' AND p.movement_id IS NULL AND p.quarantine_movement_id IS NULL",Integer.class,s.tenant(),order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_posting p
                JOIN crm.sales_return_request r
                  ON r.tenant_id=p.tenant_id AND r.id=p.return_id
                WHERE p.tenant_id=? AND r.order_id=?
                """,Integer.class,s.tenant(),order)).isEqualTo(2);
        assertThat(JourneySqlEvidence.invoices(jdbc,s.tenant())).isEqualTo(invoicesBefore);
    }

    @Test void qrMvpJ1NegotiatedPriceToHandoverAndQuarantinePg() throws Exception {
        Scenario s=setup();
        var proposed=proposals.submit(s.buyer(),s.publicQr(),2,
                new BigDecimal("80.0000"),"KZT",key());
        var accepted=proposals.sellerRespond(s.staff(),s.tenant(),proposed.proposalId(),
                proposed.revision(),SalesPriceProposalService.Action.ACCEPT,null,key());
        String requestKey=key();
        // Accepted quote snapshots the revision being accepted, not the incremented terminal proposal revision.
        var ack=new AutoOrderService.AcceptedQuoteAck(proposed.revision(),2,new BigDecimal("80.0000"),"KZT");
        UUID order=quoteCheckout.checkoutAcceptedQuote(s.buyer(),s.tenant(),accepted.quoteId(),ack,requestKey).order().orderId();
        UUID placed=kz.rm.iam.CheckoutCanonicalEvidence.assertPlaced(jdbc,s.tenant(),order);
        assertThat(quoteCheckout.checkoutAcceptedQuote(s.buyer(),s.tenant(),accepted.quoteId(),ack,requestKey)
                .order().orderId()).isEqualTo(order);
        assertThat(kz.rm.iam.CheckoutCanonicalEvidence.assertPlaced(jdbc,s.tenant(),order)).isEqualTo(placed);
        assertThat(jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,BigDecimal.class,s.tenant(),order)).isEqualByComparingTo("80.0000");
        var invoicesBefore=JourneySqlEvidence.invoices(jdbc,s.tenant());
        linkBuyer(s,order);
        kz.rm.iam.IntelligenceSourceOracle.assertTenantFacts(jdbc, s.tenant(), s.warehouse(), s.sku(),
                1, "160", 0, 0, 0, "10", "0", "10");
        UUID line=handover(s,order,2,false);
        assertThat(sellable(s)).isEqualByComparingTo("8");
        returnAndInspect(s,order,line,1,SalesReturnPhysicalService.Disposition.QUARANTINE);
        assertThat(sellable(s)).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,BigDecimal.class,s.tenant(),s.warehouse(),s.sku()))
                .isEqualByComparingTo("1");
        kz.rm.iam.IntelligenceSourceOracle.assertTenantFacts(jdbc, s.tenant(), s.warehouse(), s.sku(),
                1, "160", 1, 1, 0, "10", "2", "8");
        assertThat(JourneySqlEvidence.invoices(jdbc,s.tenant())).isEqualTo(invoicesBefore);
    }
}
