package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import kz.rm.storefront.PublishedOfferQrService;
import kz.rm.storefront.StorefrontOfferService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** QR-04A/B real SQL owner tests; synthetic OIDC tokens only for service
 * qualification, NOT proof of issuer/JWKS external admission. */
@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class QrNegotiatedCheckoutQualificationTest {
    @Autowired SalesPriceProposalService proposals;
    @Autowired AutoOrderService checkout;
    @Autowired PublishedOfferQrService qr;
    @Autowired StorefrontOfferService sellerOffers;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private record Buyer(VerifiedActor actor,UUID user,String email) {}
    private record Fixture(UUID tenant,UUID offer,UUID item,String slug,String qrRef,
                           VerifiedActor seller,Buyer buyer) {}
    private VerifiedActor verified(UUID user){
        String sub=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
                .header("alg","RS256").subject(sub).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-qr-04");
    }
    private Buyer buyer(boolean verifiedEmail){
        var row=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "neg-buyer-"+UUID.randomUUID(),"Buyer"));
        UUID user=row.getId();
        String email="neg-"+UUID.randomUUID()+"@example.kz";
        if(verifiedEmail){
            Timestamp now=Timestamp.from(Instant.now());
            jdbc.update("""
                    INSERT INTO public.iam_email_identity
                     (id,user_id,lookup_hmac,email_envelope,verified_at,created_at,
                      lifecycle_state,active_lookup_hmac,current_owner_id)
                    VALUES (?,?,?,?,?,?,'CURRENT',?,?)
                    """,UUID.randomUUID(),user,crypto.lookup(email),
                    crypto.encrypt(email),now,now,crypto.lookup(email),user);
        }
        return new Buyer(verified(user),user,email);
    }
    private Fixture setup(){
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Negotiation Seller Ltd","Seller","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "neg-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "neg-staff-"+UUID.randomUUID(),"Seller")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        VerifiedActor admin=verified(owner);
        var role=roles.createRole(admin,tenant,
                "NEG_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Price negotiation seller");
        roles.grant(admin,tenant,role.id(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.grant(admin,tenant,role.id(),DomainPermission.SALES_PROPOSAL_RESPOND);
        roles.assign(admin,tenant,staff,role.id());
        roles.enable(admin,tenant);
        UUID item=UUID.randomUUID(),offer=UUID.randomUUID(),store=UUID.randomUUID();
        String slug="negs-"+UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item
                 (tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,item,"NEG-"+UUID.randomUUID());
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
                VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,store,slug);
        jdbc.update("""
                INSERT INTO crm.store_offer
                 (tenant_id,id,title,unit_price,currency,state,catalog_item_id,row_version)
                VALUES (?,?,'Negotiated Item',1000,'KZT','PUBLISHED',?,0)
                """,tenant,offer,item);
        VerifiedActor seller=verified(staff);
        String ref=qr.issueOrGet(seller,tenant,offer,0,
                "NEG_"+UUID.randomUUID().toString().replace("-","")).publicRef();
        return new Fixture(tenant,offer,item,slug,ref,seller,buyer(true));
    }
    private static String key(){return "NEG_"+UUID.randomUUID().toString().replace("-","");}
    private SalesPriceProposalService.Result submit(Fixture f,int qty,String price,String requestKey){
        return proposals.submit(f.buyer().actor(),f.qrRef(),qty,new BigDecimal(price),
                "KZT",requestKey);
    }
    private SalesPriceProposalService.Result seller(Fixture f,UUID proposal,long rev,
                  SalesPriceProposalService.Action action,String counter,String requestKey){
        return proposals.sellerRespond(f.seller(),f.tenant(),proposal,rev,action,
                counter==null?null:new BigDecimal(counter),requestKey);
    }
    private SalesPriceProposalService.Result buyer(Fixture f,UUID proposal,long rev,
                  SalesPriceProposalService.Action action,String counter,String requestKey){
        return proposals.buyerRespond(f.buyer().actor(),f.tenant(),proposal,rev,action,
                counter==null?null:new BigDecimal(counter),requestKey);
    }
    private SalesPriceProposalService.Result agreed(Fixture f,int qty,String amount){
        var started=submit(f,qty,amount,key());
        return seller(f,started.proposalId(),started.revision(),
                SalesPriceProposalService.Action.ACCEPT,null,key());
    }
    private AutoOrderService.CheckoutResult pay(Fixture f,UUID quote,long revision,int qty,
                                               String price,String requestKey){
        return checkout.checkoutAcceptedQuote(f.buyer().actor(),f.tenant(),quote,
                new AutoOrderService.AcceptedQuoteAck(revision,qty,
                        new BigDecimal(price),"KZT"),requestKey);
    }
    private int orders(Fixture f){
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
                """,Integer.class,f.tenant());
    }
    private void requirePostgres() throws Exception{
        try(var c=dataSource.getConnection()){
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
    }

    @Test void qr04aCounterThenBuyerAcceptanceCreatesOneQuoteAndImmutableEvents(){
        var f=setup();
        var p=submit(f,10,"700",key());
        assertThat(p.state()).isEqualTo("AWAITING_SELLER");
        assertThat(p.unitPrice()).isEqualByComparingTo("700.0000");
        var counter=seller(f,p.proposalId(),1,
                SalesPriceProposalService.Action.COUNTER,"750",key());
        assertThat(counter.state()).isEqualTo("AWAITING_BUYER");
        assertThat(counter.revision()).isEqualTo(2);
        assertThat(counter.roundCount()).isEqualTo(1);
        var accepted=buyer(f,p.proposalId(),2,
                SalesPriceProposalService.Action.ACCEPT,null,key());
        assertThat(accepted.quoteId()).isNotNull();
        assertThat(accepted.state()).isEqualTo("AGREED");
        assertThat(proposals.buyerDetail(f.buyer().actor(),f.tenant(),p.proposalId())
                .history()).extracting(SalesPriceProposalService.Event::eventType)
                .containsExactly("SUBMITTED","COUNTERED","ACCEPTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND id=? AND state='AVAILABLE'
                  AND agreed_unit_price=750 AND quantity=10
                """,Integer.class,f.tenant(),accepted.quoteId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT unit_price FROM crm.store_offer WHERE tenant_id=? AND id=?
                """,BigDecimal.class,f.tenant(),f.offer())).isEqualByComparingTo("1000");
        assertThat(orders(f)).isZero();
    }

    @Test void qr04aWrongTurnStaleRevisionAndImmutableTerminalStatesRejected(){
        var f=setup();
        var initial=submit(f,2,"800",key());
        assertThatThrownBy(()->buyer(f,initial.proposalId(),1,
                SalesPriceProposalService.Action.ACCEPT,null,key()))
                .hasMessage("PROPOSAL_TURN_REQUIRED");
        var counter=seller(f,initial.proposalId(),1,
                SalesPriceProposalService.Action.COUNTER,"850",key());
        assertThatThrownBy(()->seller(f,initial.proposalId(),2,
                SalesPriceProposalService.Action.ACCEPT,null,key()))
                .hasMessage("PROPOSAL_TURN_REQUIRED");
        assertThatThrownBy(()->buyer(f,initial.proposalId(),1,
                SalesPriceProposalService.Action.ACCEPT,null,key()))
                .hasMessage("PROPOSAL_VERSION_CONFLICT");
        var rejected=buyer(f,initial.proposalId(),2,
                SalesPriceProposalService.Action.REJECT,null,key());
        assertThat(rejected.state()).isEqualTo("REJECTED");
        assertThatThrownBy(()->seller(f,initial.proposalId(),3,
                SalesPriceProposalService.Action.ACCEPT,null,key()))
                .hasMessage("PROPOSAL_TERMINAL");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND proposal_id=?
                """,Integer.class,f.tenant(),initial.proposalId())).isZero();
    }

    @Test void qr04aCommandReceiptsExactReplayAndConflictArePersistent(){
        var f=setup();
        String submitKey=key(),acceptKey=key();
        var p=submit(f,3,"650",submitKey);
        var accept=seller(f,p.proposalId(),1,
                SalesPriceProposalService.Action.ACCEPT,null,acceptKey);
        assertThat(submit(f,3,"650.0000",submitKey)).isEqualTo(p);
        assertThat(seller(f,p.proposalId(),1,
                SalesPriceProposalService.Action.ACCEPT,null,acceptKey))
                .isEqualTo(accept);
        assertThatThrownBy(()->submit(f,4,"650",submitKey))
                .hasMessage("PROPOSAL_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->seller(f,p.proposalId(),1,
                SalesPriceProposalService.Action.REJECT,null,acceptKey))
                .hasMessage("PROPOSAL_IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_proposal_command_receipt
                WHERE tenant_id=? AND proposal_id=?
                """,Integer.class,f.tenant(),p.proposalId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_price_proposal_event
                WHERE tenant_id=? AND proposal_id=?
                """,Integer.class,f.tenant(),p.proposalId())).isEqualTo(2);
    }

    @Test void qr04aThreeCountersLimitAndWithdrawalNeverAcceptOwnPrice(){
        var f=setup();
        var p=submit(f,1,"300",key());
        var c1=seller(f,p.proposalId(),1,
                SalesPriceProposalService.Action.COUNTER,"350",key());
        var c2=buyer(f,p.proposalId(),2,
                SalesPriceProposalService.Action.COUNTER,"325",key());
        var c3=seller(f,p.proposalId(),3,
                SalesPriceProposalService.Action.COUNTER,"340",key());
        assertThat(c3.roundCount()).isEqualTo(3);
        assertThatThrownBy(()->buyer(f,p.proposalId(),4,
                SalesPriceProposalService.Action.COUNTER,"330",key()))
                .hasMessage("PROPOSAL_ROUND_LIMIT");
        // Seller who authored the last counter is allowed to withdraw it.
        var a=buyer(f,p.proposalId(),4,
                SalesPriceProposalService.Action.ACCEPT,null,key());
        assertThat(a.quoteId()).isNotNull();
        // Distinct pending proposal: originating buyer may withdraw before seller acts.
        var other=submit(f,1,"200",key());
        var withdraw=buyer(f,other.proposalId(),1,
                SalesPriceProposalService.Action.WITHDRAW,null,key());
        assertThat(withdraw.state()).isEqualTo("WITHDRAWN");
    }

    @Test void qr04aExpiredProposalEffectiveStateAndMaterialization(){
        var f=setup();var p=submit(f,1,"600",key());
        Instant past=Instant.now().minusSeconds(5);
        jdbc.update("""
                UPDATE crm.sales_price_proposal
                SET created_at=?,expires_at=?,updated_at=?
                WHERE tenant_id=? AND id=?
                """,Timestamp.from(past.minusSeconds(3600)),Timestamp.from(past),
                Timestamp.from(past.minusSeconds(3600)),f.tenant(),p.proposalId());
        assertThat(proposals.buyerDetail(f.buyer().actor(),f.tenant(),p.proposalId())
                .current().state()).isEqualTo("EXPIRED");
        assertThatThrownBy(()->seller(f,p.proposalId(),1,
                SalesPriceProposalService.Action.ACCEPT,null,key()))
                .hasMessage("PROPOSAL_EXPIRED");
        assertThat(proposals.expireDue(f.tenant(),p.proposalId())).isEqualTo(1);
        assertThat(proposals.expireDue(f.tenant(),p.proposalId())).isZero();
        assertThat(proposals.buyerDetail(f.buyer().actor(),f.tenant(),p.proposalId())
                .history()).extracting(SalesPriceProposalService.Event::eventType)
                .containsExactly("SUBMITTED","EXPIRED");
    }

    @Test void qr04aOtherBuyerAndForeignSellerCannotChangeProposal(){
        var f=setup();var g=setup();
        var p=submit(f,1,"750",key());
        assertThatThrownBy(()->proposals.buyerRespond(g.buyer().actor(),f.tenant(),
                p.proposalId(),1,SalesPriceProposalService.Action.WITHDRAW,null,key()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->proposals.sellerRespond(g.seller(),f.tenant(),
                p.proposalId(),1,SalesPriceProposalService.Action.ACCEPT,null,key()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->proposals.sellerRespond(g.seller(),g.tenant(),
                p.proposalId(),1,SalesPriceProposalService.Action.ACCEPT,null,key()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->proposals.submit(buyer(false).actor(),f.qrRef(),
                1,new BigDecimal("800"),"KZT",key()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_price_proposal_event
                WHERE tenant_id=? AND proposal_id=?
                """,Integer.class,f.tenant(),p.proposalId())).isEqualTo(1);
    }

    @Test void qr04bAcceptedPriceCreatesOnePlacedOrderWithImmutableQuoteSource(){
        var f=setup();
        var a=agreed(f,10,"750");
        sellerOffers.updatePrice(f.seller(),f.tenant(),f.offer(),
                "Negotiated Item new list",new BigDecimal("1200"),"KZT",0);
        String buyKey=key();
        var placed=pay(f,a.quoteId(),1,10,"750",buyKey);
        var replay=pay(f,a.quoteId(),1,10,"750",buyKey);
        assertThat(replay.order().orderId()).isEqualTo(placed.order().orderId());
        assertThat(placed.order().status()).isEqualTo("PLACED");
        UUID id=placed.order().orderId();
        assertThat(jdbc.queryForObject("""
                SELECT fulfillment_mode FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,String.class,f.tenant(),id)).isEqualTo("PICKUP_PROOF_REQUIRED");
        assertThat(jdbc.queryForObject("""
                SELECT total_amount FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,BigDecimal.class,f.tenant(),id)).isEqualByComparingTo("7500");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order
                WHERE tenant_id=? AND id=? AND pending_email_identity_id IS NOT NULL
                  AND buyer_user_id=?
                """,Integer.class,f.tenant(),id,f.buyer().user())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=? AND price_source='NEGOTIATED'
                 AND accepted_quote_id=? AND unit_price_snapshot=750
                 AND quantity=10 AND catalog_item_id_snapshot=?
                """,Integer.class,f.tenant(),id,a.quoteId(),f.item())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND id=? AND state='CONSUMED'
                  AND consumed_order_id=?
                """,Integer.class,f.tenant(),a.quoteId(),id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Integer.class,f.tenant(),id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=?
                """,Integer.class,f.tenant())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT unit_price FROM crm.store_offer WHERE tenant_id=? AND id=?
                """,BigDecimal.class,f.tenant(),f.offer())).isEqualByComparingTo("1200");
    }

    @Test void qr04bWrongAcknowledgementAndHiddenOfferCannotCreateOrder(){
        var f=setup();var a=agreed(f,2,"800");
        assertThatThrownBy(()->pay(f,a.quoteId(),1,2,"801",key()))
                .hasMessage("QUOTE_RECONFIRM_REQUIRED");
        assertThatThrownBy(()->pay(f,a.quoteId(),999,2,"800",key()))
                .hasMessage("QUOTE_RECONFIRM_REQUIRED");
        assertThatThrownBy(()->pay(f,a.quoteId(),1,1,"800",key()))
                .hasMessage("QUOTE_RECONFIRM_REQUIRED");
        jdbc.update("""
                UPDATE crm.store_offer SET state='HIDDEN',row_version=row_version+1
                WHERE tenant_id=? AND id=?
                """,f.tenant(),f.offer());
        assertThatThrownBy(()->pay(f,a.quoteId(),1,2,"800",key()))
                .hasMessage("QUOTE_OFFER_UNAVAILABLE");
        assertThat(orders(f)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?
                """,Integer.class,f.tenant())).isZero();
    }

    @Test void qr04bQuoteConsumedOnceAndSharedCheckoutKeyConflictsAcrossModes(){
        var f=setup();var a=agreed(f,1,"500");
        String shared=key();
        var placed=pay(f,a.quoteId(),1,1,"500",shared);
        assertThatThrownBy(()->pay(f,a.quoteId(),1,1,"500",key()))
                .hasMessage("QUOTE_ALREADY_CONSUMED_OR_INVALID");
        assertThatThrownBy(()->checkout.checkout(f.slug(),f.buyer().email(),shared,
                List.of(new AutoOrderService.Line(f.offer(),1))))
                .hasMessageContaining("Idempotency key reused");
        assertThat(orders(f)).isEqualTo(1);
        var g=setup();
        var ordinary=checkout.checkout(g.slug(),g.buyer().email(),shared,
                List.of(new AutoOrderService.Line(g.offer(),1)));
        var ag=agreed(g,1,"800");
        assertThatThrownBy(()->pay(g,ag.quoteId(),1,1,"800",shared))
                .hasMessageContaining("Idempotency key reused");
        assertThat(ordinary.order().status()).isEqualTo("PLACED");
        assertThat(orders(g)).isEqualTo(1);
    }

    @Test void qr04bExpiredQuoteFailsWithoutAnyOrderOrReceipt(){
        var f=setup();var a=agreed(f,2,"700");
        jdbc.update("""
                UPDATE crm.sales_accepted_quote
                SET expires_at=? WHERE tenant_id=? AND id=? AND state='AVAILABLE'
                """,Timestamp.from(Instant.now().minusSeconds(1)),
                f.tenant(),a.quoteId());
        assertThatThrownBy(()->pay(f,a.quoteId(),1,2,"700",key()))
                .hasMessage("QUOTE_EXPIRED");
        assertThat(orders(f)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?
                """,Integer.class,f.tenant())).isZero();
    }

    @Test void qr04bDifferentBuyerCannotConsumeAnotherCustomersQuote(){
        var f=setup();var g=setup();var a=agreed(f,2,"700");
        assertThatThrownBy(()->checkout.checkoutAcceptedQuote(g.buyer().actor(),
                f.tenant(),a.quoteId(),
                new AutoOrderService.AcceptedQuoteAck(1,2,new BigDecimal("700"),"KZT"),
                key())).isInstanceOf(SecurityException.class);
        assertThat(orders(f)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_accepted_quote WHERE tenant_id=? AND id=?
                """,String.class,f.tenant(),a.quoteId())).isEqualTo("AVAILABLE");
    }

    @Test void qr04bConcurrentDistinctKeysAtMostOneOrderOnRealPostgres() throws Exception{
        requirePostgres();
        var f=setup();var a=agreed(f,10,"750");
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                try{return pay(f,a.quoteId(),1,10,"750",key()).order().orderId();}
                catch(IllegalStateException collision){return null;}
            });
            var two=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                try{return pay(f,a.quoteId(),1,10,"750",key()).order().orderId();}
                catch(IllegalStateException collision){return null;}
            });
            start.countDown();
            UUID first=one.get(30,TimeUnit.SECONDS),second=two.get(30,TimeUnit.SECONDS);
            assertThat(first!=null||second!=null).isTrue();
            assertThat(first==null||second==null).isTrue();
        }
        assertThat(orders(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?
                """,Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void qr04aConcurrentAcceptCreatesOneQuoteAndOneTerminalEventInPostgres() throws Exception{
        requirePostgres();
        var f=setup();var p=submit(f,5,"600",key());
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                try{return seller(f,p.proposalId(),1,
                        SalesPriceProposalService.Action.ACCEPT,null,key()).quoteId();}
                catch(IllegalStateException stale){return null;}
            });
            var b=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                try{return seller(f,p.proposalId(),1,
                        SalesPriceProposalService.Action.ACCEPT,null,key()).quoteId();}
                catch(IllegalStateException stale){return null;}
            });
            start.countDown();
            UUID first=a.get(30,TimeUnit.SECONDS),second=b.get(30,TimeUnit.SECONDS);
            assertThat(first!=null||second!=null).isTrue();
            assertThat(first==null||second==null).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND proposal_id=?
                """,Integer.class,f.tenant(),p.proposalId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_price_proposal_event
                WHERE tenant_id=? AND proposal_id=? AND event_type='ACCEPTED'
                """,Integer.class,f.tenant(),p.proposalId())).isEqualTo(1);
    }

    @Test void qr04aPostgresProposalEventsAndReceiptsCannotBeAltered() throws Exception{
        requirePostgres();var f=setup();var p=submit(f,1,"500",key());
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.sales_price_proposal_event SET unit_price=999
                WHERE tenant_id=? AND proposal_id=?
                """,f.tenant(),p.proposalId())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.sales_proposal_command_receipt
                WHERE tenant_id=? AND proposal_id=?
                """,f.tenant(),p.proposalId())).isInstanceOf(Exception.class);
    }
}
