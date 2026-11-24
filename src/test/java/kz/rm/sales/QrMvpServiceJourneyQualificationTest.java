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

    private record Scenario(UUID tenant,UUID warehouse,UUID sku,UUID offer,
                            String publicQr, long offerVersion,
                            VerifiedActor owner,VerifiedActor staff,
                            VerifiedActor buyer) {}

    private static String key(){return "QR_MVP_"+UUID.randomUUID().toString().replace("-","");}

    private VerifiedActor actor(UUID user) {
        String subject=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("qr-mvp-fixture");
    }

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
                DomainPermission.INVENTORY_ADJUST))
            roles.grant(owner,tenant,role.id(),permission);
        roles.assign(owner,tenant,staffId,role.id());
        roles.enable(owner,tenant);
        currencies.initialize(owner,tenant);

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
        scopes.assign(owner,tenant,warehouse,staff);

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

    private UUID handover(Scenario s,UUID orderId,int qty) {
        assertThat(sales.confirm(s.staff(),s.tenant(),orderId,s.warehouse()).status())
                .isEqualTo("CONFIRMED");
        prep.startPreparing(s.staff(),s.tenant(),orderId,s.warehouse(),
                orderVersion(s,orderId),key());
        UUID lineId=jdbc.queryForObject("""
                SELECT id FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                """,UUID.class,s.tenant(),orderId);
        prep.confirmPicked(s.staff(),s.tenant(),orderId,s.warehouse(),
                lineId,qty,orderVersion(s,orderId),key());
        prep.markReadyForPickup(s.staff(),s.tenant(),orderId,s.warehouse(),
                orderVersion(s,orderId),key());
        var issued=pickup.issue(s.buyer(),orderRef(s,orderId),key());
        assertThat(pickup.preview(s.staff(),s.tenant(),s.warehouse(),
                issued.rawProof()).status()).isEqualTo("READY_FOR_PICKUP");
        long before=orderVersion(s,orderId);
        String handoverKey=key();
        var committed=pickup.complete(s.staff(),s.tenant(),s.warehouse(),
                issued.rawProof(),true,before,handoverKey);
        assertThat(committed.status()).isEqualTo("COMPLETED");
        assertThat(pickup.complete(s.staff(),s.tenant(),s.warehouse(),
                issued.rawProof(),true,before,handoverKey).handoverId())
                .isEqualTo(committed.handoverId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover WHERE tenant_id=? AND order_id=?
                """,Integer.class,s.tenant(),orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND movement_kind='FULFILLMENT'
                  AND source_type='SALES_ORDER' AND source_id=?
                """,Integer.class,s.tenant(),orderId)).isEqualTo(1);
        return lineId;
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

    @Test void qrMvpJ1VerifiedListPriceToHandoverAndRestockPg() {
        Scenario s=setup();
        UUID order=listCheckout.checkoutVerified(s.buyer(),s.publicQr(),3,
                s.offerVersion(),new BigDecimal("100.0000"),"KZT",key())
                .order().orderId();
        assertThat(jdbc.queryForObject("""
                SELECT buyer_user_id FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,UUID.class,s.tenant(),order)).isEqualTo(s.buyer().userId());
        assertThat(jdbc.queryForObject("""
                SELECT fulfillment_mode FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),order)).isEqualTo("PICKUP_PROOF_REQUIRED");
        UUID line=handover(s,order,3);
        assertThat(sellable(s)).isEqualByComparingTo("7");
        returnAndInspect(s,order,line,2,SalesReturnPhysicalService.Disposition.RESTOCK);
        assertThat(sellable(s)).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_posting
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,s.tenant(),order)).isEqualTo(1);
    }

    @Test void qrMvpJ1NegotiatedPriceToHandoverAndQuarantinePg() {
        Scenario s=setup();
        var proposed=proposals.submit(s.buyer(),s.publicQr(),2,
                new BigDecimal("80.0000"),"KZT",key());
        var accepted=proposals.sellerRespond(s.staff(),s.tenant(),proposed.proposalId(),
                proposed.revision(),SalesPriceProposalService.Action.ACCEPT,null,key());
        UUID order=quoteCheckout.checkoutAcceptedQuote(s.buyer(),s.tenant(),
                accepted.quoteId(),
                new AutoOrderService.AcceptedQuoteAck(accepted.revision(),2,
                        new BigDecimal("80.0000"),"KZT"),
                key()).order().orderId();
        assertThat(jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,BigDecimal.class,s.tenant(),order)).isEqualByComparingTo("80.0000");
        UUID line=handover(s,order,2);
        assertThat(sellable(s)).isEqualByComparingTo("8");
        returnAndInspect(s,order,line,1,SalesReturnPhysicalService.Disposition.QUARANTINE);
        assertThat(sellable(s)).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,BigDecimal.class,s.tenant(),s.warehouse(),s.sku()))
                .isEqualByComparingTo("1");
    }
}
