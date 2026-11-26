package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import kz.rm.intelligence.*;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired GlobalAccountStatusService accountStatus;
    @Autowired GlobalAccountDeletionService accountDeletion;
    @Autowired NativeBookedCaptureService nativeCapture;
    @Autowired BookedSourceReconciliationService reconciliation;
    @Autowired CanonicalFactDeliveryService delivery;

    private record Buyer(VerifiedActor actor,UUID user,String email) {}
    private record Fixture(UUID tenant,UUID offer,UUID item,String slug,String qrRef,
                           VerifiedActor owner,VerifiedActor seller,Buyer buyer) {}
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
    private Fixture setup(){return setup(false);}
    private Fixture setup(boolean nativeRead){
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
        if(nativeRead)roles.grant(admin,tenant,role.id(),DomainPermission.SALES_ORDER_READ);
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
        return new Fixture(tenant,offer,item,slug,ref,admin,seller,buyer(true));
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

    private Instant nativeClock(){
        return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
    }
    private long nativeRevision(Fixture f){
        return jdbc.queryForObject("""
            SELECT coalesce((SELECT revision FROM crm.int_source_revision WHERE tenant_id=?),0)
              +(SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?)
            """,Long.class,f.tenant(),f.tenant());
    }
    private UUID nativeRun(Fixture f,Instant from,Instant until){
        UUID run=UUID.randomUUID();
        delivery.openBackfill(new CanonicalFactDeliveryService.Slice(f.tenant(),run,CanonicalFactKey.Owner.SALES,
            CanonicalFactKey.Kind.ORDER_PLACED,from,until));
        delivery.backfillBatch(f.tenant(),run,10);
        return run;
    }
    private void assertNativeOrder(Fixture f,UUID order,UUID quote,Instant boundary){
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order o JOIN crm.sales_order_status_event e
              ON e.tenant_id=o.tenant_id AND e.order_id=o.id AND e.event_type='PLACED' AND e.occurred_at=o.created_at
            JOIN crm.int_canonical_fact c ON c.tenant_id=e.tenant_id AND c.source_id=e.id AND c.subject_id=o.id
              AND c.owner_module='SALES' AND c.source_kind='ORDER_PLACED' AND c.schema_version=2
            JOIN crm.sales_checkout_receipt r ON r.tenant_id=o.tenant_id AND r.order_id=o.id
            JOIN crm.sales_order_line l ON l.tenant_id=o.tenant_id AND l.order_id=o.id
            JOIN crm.sales_accepted_quote q ON q.tenant_id=o.tenant_id AND q.id=l.accepted_quote_id AND q.consumed_order_id=o.id
            WHERE o.tenant_id=? AND o.id=? AND o.created_at>=? AND o.booked_snapshot_sealed
              AND l.price_source='NEGOTIATED' AND l.unit_price_snapshot=750 AND l.quantity=2
              AND o.total_amount=1500 AND q.id=? AND q.state='CONSUMED'
            """,Long.class,f.tenant(),order,Timestamp.from(boundary),quote)).isEqualTo(1L);
    }

    @Test void nativeNegotiatedCaptureAndCrossTenantRollbackPg() throws Exception{
        requirePostgres();
        var a=setup(true);var b=setup(true);
        var qa=agreed(a,2,"750");var qb=agreed(b,2,"750");
        var ba=nativeCapture.activate(a.owner(),a.tenant());
        var bb=nativeCapture.activate(b.owner(),b.tenant());
        long revisionA=nativeRevision(a);
        String keyA=key(),keyB=key();
        var staged=new java.util.concurrent.CompletableFuture<UUID>();
        var release=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        UUID runB;
        UUID aborted;
        try{
            var writing=workers.submit(()->new TransactionTemplate(transactionManager).execute(st->{
                UUID order=pay(a,qa.quoteId(),1,2,"750",keyA).order().orderId();
                staged.complete(order);
                try{if(!release.await(60,TimeUnit.SECONDS))throw new AssertionError("S1_NEGOTIATED_RELEASE");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                st.setRollbackOnly();
                return order;
            }));
            aborted=staged.get(20,TimeUnit.SECONDS);
            var independent=workers.submit(()->{
                UUID order=pay(b,qb.quoteId(),1,2,"750",keyB).order().orderId();
                assertNativeOrder(b,order,qb.quoteId(),bb.startedAt());
                assertThat(pay(b,qb.quoteId(),1,2,"750",keyB).order().orderId()).isEqualTo(order);
                UUID run=nativeRun(b,bb.startedAt(),nativeClock());
                var result=reconciliation.inspectNativeRange(b.seller(),b.tenant(),run);
                assertThat(result.state()).isEqualTo(NativeBookedClosure.State.SHADOW_QUALIFIED_VALUE);
                assertThat(result.shadowValue()).isEqualTo(1L);
                assertThat(result.admitted()).isFalse();
                return run;
            });
            try{
                // B must complete while A's actual checkout transaction is still open.
                runB=independent.get(40,TimeUnit.SECONDS);
                assertThat(writing.isDone()).isFalse();
                assertThat(orders(a)).isZero();
                assertThat(nativeRevision(a)).isEqualTo(revisionA);
            }finally{release.countDown();}
            assertThat(writing.get(20,TimeUnit.SECONDS)).isEqualTo(aborted);
        }finally{
            release.countDown();workers.shutdownNow();
            assertThat(workers.awaitTermination(60,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(orders(a)).isZero();
        assertThat(nativeRevision(a)).isEqualTo(revisionA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?",Long.class,a.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_line WHERE tenant_id=?",Long.class,a.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND event_type='PLACED'",Long.class,a.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED'",Long.class,a.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM crm.sales_accepted_quote WHERE tenant_id=? AND id=?",String.class,a.tenant(),qa.quoteId())).isEqualTo("AVAILABLE");
        UUID zero=nativeRun(a,ba.startedAt(),nativeClock());
        var z=reconciliation.inspectNativeRange(a.seller(),a.tenant(),zero);
        assertThat(z.state()).isEqualTo(NativeBookedClosure.State.SHADOW_QUALIFIED_ZERO);
        assertThat(z.shadowValue()).isZero();
        assertThat(z.admitted()).isFalse();
        assertThatThrownBy(()->reconciliation.inspectNativeRange(b.seller(),a.tenant(),zero)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->reconciliation.inspectNativeRange(a.seller(),a.tenant(),runB)).isInstanceOf(IllegalArgumentException.class);
        // Retrying the same quote/key after rollback must commit exactly once.
        UUID committed=pay(a,qa.quoteId(),1,2,"750",keyA).order().orderId();
        assertNativeOrder(a,committed,qa.quoteId(),ba.startedAt());
        assertThat(pay(a,qa.quoteId(),1,2,"750",keyA).order().orderId()).isEqualTo(committed);
        assertThat(orders(a)).isEqualTo(1);
        assertThat(orders(b)).isEqualTo(1);
        UUID positive=nativeRun(a,ba.startedAt(),nativeClock());
        var result=reconciliation.inspectNativeRange(a.seller(),a.tenant(),positive);
        assertThat(result.state()).isEqualTo(NativeBookedClosure.State.SHADOW_QUALIFIED_VALUE);
        assertThat(result.shadowValue()).isEqualTo(1L);
        assertThat(result.admitted()).isFalse();
        assertThat(reconciliation.inspectNativeRange(b.seller(),b.tenant(),runB).shadowValue()).isEqualTo(1L);
        System.out.println("S1_NATIVE_NEGOTIATED_CROSS_TENANT_PASS");
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
        try(var issuer=new JourneySignedActorFixture()){
            qualifyWithdrawalBeforeOrAfterAcceptance(issuer);
            qualifyBuyerRevocationBothOrders(issuer);
            qualifyQuoteCutoffBothOrders(issuer);
        }
    }

    private Fixture signedFixture(JourneySignedActorFixture issuer){
        var f=setup();
        var buyer=new Buyer(issuer.resolve(jdbc,users,f.buyer().user()),f.buyer().user(),f.buyer().email());
        return new Fixture(f.tenant(),f.offer(),f.item(),f.slug(),f.qrRef(),
                issuer.resolve(jdbc,users,f.owner().userId()),issuer.resolve(jdbc,users,f.seller().userId()),buyer);
    }

    // Independent committed rows. Project only business evidence, not secrets
    // or bearer tokens. The fixture has no stock receipt and checkout must not reserve.
    private List<Object> checkoutEvidence(Fixture f){
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return List.of(
            jdbc.queryForList("SELECT id,status,buyer_user_id,total_amount,currency,fulfillment_mode,row_version FROM crm.sales_order WHERE tenant_id=? ORDER BY id",f.tenant()),
            jdbc.queryForList("SELECT id,order_id,offer_id,quantity,unit_price_snapshot,currency,price_source,accepted_quote_id FROM crm.sales_order_line WHERE tenant_id=? ORDER BY id",f.tenant()),
            jdbc.queryForList("SELECT id,order_id,event_type,occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? ORDER BY id",f.tenant()),
            jdbc.queryForList("SELECT idempotency_key,order_id,created_at FROM crm.sales_checkout_receipt WHERE tenant_id=? ORDER BY idempotency_key",f.tenant()),
            jdbc.queryForList("SELECT id,order_id,state,row_version FROM crm.sales_order_qr_reference WHERE tenant_id=? ORDER BY id",f.tenant()),
            jdbc.queryForList("SELECT id,order_id,quantity,state,row_version FROM crm.inv_stock_reservation WHERE tenant_id=? ORDER BY id",f.tenant()),
            jdbc.queryForList("SELECT item_id,location_id,on_hand,reserved,row_version FROM crm.inv_stock_balance WHERE tenant_id=? ORDER BY item_id,location_id",f.tenant()),
            jdbc.queryForList("SELECT id,item_id,delta_quantity FROM crm.inv_stock_movement WHERE tenant_id=? ORDER BY id",f.tenant()),
            JourneySqlEvidence.invoices(jdbc,f.tenant()));
    }
    private void assertOneQuoteOrder(Fixture f,UUID quote,UUID order){
        assertThat(order).isNotNull();
        assertThat(orders(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_accepted_quote q
            JOIN crm.sales_order o ON o.tenant_id=q.tenant_id AND o.id=q.consumed_order_id
            JOIN crm.sales_order_line l ON l.tenant_id=o.tenant_id AND l.order_id=o.id
            JOIN crm.sales_checkout_receipt r ON r.tenant_id=o.tenant_id AND r.order_id=o.id
            JOIN crm.sales_order_status_event e ON e.tenant_id=o.tenant_id AND e.order_id=o.id
            JOIN crm.sales_order_qr_reference qr ON qr.tenant_id=o.tenant_id AND qr.order_id=o.id
            WHERE q.tenant_id=? AND q.id=? AND o.id=? AND q.state='CONSUMED'
              AND o.status='PLACED' AND o.fulfillment_mode='PICKUP_PROOF_REQUIRED'
              AND o.buyer_user_id=q.buyer_user_id AND o.total_amount=q.quantity*q.agreed_unit_price
              AND l.accepted_quote_id=q.id AND l.price_source='NEGOTIATED'
              AND l.quantity=q.quantity AND l.unit_price_snapshot=q.agreed_unit_price
              AND l.currency=q.currency AND l.catalog_item_id_snapshot=?
              AND e.event_type='PLACED' AND qr.state='ACTIVE'
            """,Integer.class,f.tenant(),quote,order,f.item())).isEqualTo(1);
        for(String table:List.of("sales_order_line","sales_checkout_receipt","sales_order_status_event","sales_order_qr_reference"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm."+table+" WHERE tenant_id=?",Integer.class,f.tenant())).as(table).isEqualTo(1);
        for(String table:List.of("inv_stock_reservation","inv_stock_movement","inv_stock_balance","fin_invoice"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm."+table+" WHERE tenant_id=?",Integer.class,f.tenant())).as(table).isZero();
    }
    private void qualifyWithdrawalBeforeOrAfterAcceptance(JourneySignedActorFixture issuer) throws Exception {
        for(boolean withdrawFirst:List.of(false,true)){
            var f=signedFixture(issuer);var p=submit(f,2,"700",key());
            var before=checkoutEvidence(f);
            var quote=new java.util.concurrent.atomic.AtomicReference<UUID>();
            var order=new java.util.concurrent.atomic.AtomicReference<UUID>();
            String buyKey=key(),withdrawKey=key();
            Runnable withdraw=()->buyer(f,p.proposalId(),p.revision(),SalesPriceProposalService.Action.WITHDRAW,null,withdrawKey);
            Runnable acceptAndCheckout=()->{
                var agreed=seller(f,p.proposalId(),p.revision(),SalesPriceProposalService.Action.ACCEPT,null,key());
                quote.set(agreed.quoteId());
                order.set(pay(f,agreed.quoteId(),p.revision(),2,"700",buyKey).order().orderId());
            };
            PgCommitOrderEvidence.run(jdbc,transactionManager,withdrawFirst?withdraw:acceptAndCheckout,
                withdrawFirst?acceptAndCheckout:withdraw,IllegalStateException.class,"PROPOSAL_VERSION_CONFLICT");
            if(withdrawFirst){
                assertThat(checkoutEvidence(f)).isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_accepted_quote WHERE tenant_id=?",Integer.class,f.tenant())).isZero();
                assertThat(proposals.buyerDetail(f.buyer().actor(),f.tenant(),p.proposalId()).current().state()).isEqualTo("WITHDRAWN");
            }else{
                assertOneQuoteOrder(f,quote.get(),order.get());
                var committed=checkoutEvidence(f);
                assertThatThrownBy(()->buyer(f,p.proposalId(),p.revision()+1,SalesPriceProposalService.Action.WITHDRAW,null,key()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("PROPOSAL_TERMINAL");
                assertThat(pay(f,quote.get(),p.revision(),2,"700",buyKey).order().orderId()).isEqualTo(order.get());
                assertThat(checkoutEvidence(f)).isEqualTo(committed);
            }
        }
    }
    private VerifiedActor signedAdmin(JourneySignedActorFixture issuer){
        UUID admin=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "neg-admin-"+UUID.randomUUID(),"Admin")).getId();
        jdbc.update("INSERT INTO public.iam_global_role_assignment(id,user_id,role,granted_at) VALUES (?,?,'PLATFORM_ADMIN',?)",
                UUID.randomUUID(),admin,Timestamp.from(Instant.now()));
        return issuer.resolve(jdbc,users,admin);
    }
    private void qualifyBuyerRevocationBothOrders(JourneySignedActorFixture issuer) throws Exception {
        for(boolean delete:List.of(false,true))for(boolean revokeFirst:List.of(false,true)){
            var f=signedFixture(issuer);var a=agreed(f,2,"700");var admin=signedAdmin(issuer);
            String buyKey=key(),deleteKey=key();var before=checkoutEvidence(f);
            var order=new java.util.concurrent.atomic.AtomicReference<UUID>();
            Runnable buy=()->order.set(pay(f,a.quoteId(),1,2,"700",buyKey).order().orderId());
            Runnable revoke=()->{
                if(delete)accountDeletion.deleteAccount(admin,f.buyer().user(),deleteKey);
                else accountStatus.suspendAccount(admin,f.buyer().user());
            };
            PgCommitOrderEvidence.run(jdbc,transactionManager,revokeFirst?revoke:buy,revokeFirst?buy:revoke,
                SecurityException.class,revokeFirst?"VERIFIED_BUYER_REQUIRED":null);
            assertThat(jdbc.queryForObject("SELECT status FROM public.iam_user_account WHERE id=?",String.class,f.buyer().user())).isEqualTo(delete?"DELETED":"SUSPENDED");
            if(revokeFirst){
                assertThat(checkoutEvidence(f)).isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT state FROM crm.sales_accepted_quote WHERE tenant_id=? AND id=?",String.class,f.tenant(),a.quoteId())).isEqualTo("AVAILABLE");
            }else assertOneQuoteOrder(f,a.quoteId(),order.get());
            var after=checkoutEvidence(f);
            // Current identity is required even for exact replay. Never leak a
            // historical receipt to a suspended or deleted buyer.
            assertThatThrownBy(buy::run).isInstanceOf(SecurityException.class).hasMessage("VERIFIED_BUYER_REQUIRED");
            assertThatThrownBy(()->pay(f,a.quoteId(),1,2,"700",key())).isInstanceOf(SecurityException.class).hasMessage("VERIFIED_BUYER_REQUIRED");
            assertThat(checkoutEvidence(f)).isEqualTo(after);
        }
    }
    private void qualifyQuoteCutoffBothOrders(JourneySignedActorFixture issuer) throws Exception {
        for(boolean expiryFirst:List.of(false,true)){
            var f=signedFixture(issuer);var a=agreed(f,2,"700");String buyKey=key();
            // Synthetic short-lived quote, set before competing transactions.
            // Do not rewrite state or expiry after consumption. The real clock
            // crosses this cutoff while the second PostgreSQL backend waits.
            Instant cutoff=Instant.now().plusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            assertThat(jdbc.update("UPDATE crm.sales_accepted_quote SET expires_at=? WHERE tenant_id=? AND id=? AND state='AVAILABLE'",
                    Timestamp.from(cutoff),f.tenant(),a.quoteId())).isEqualTo(1);
            var before=checkoutEvidence(f);
            var order=new java.util.concurrent.atomic.AtomicReference<UUID>();
            Runnable buy=()->order.set(pay(f,a.quoteId(),1,2,"700",buyKey).order().orderId());
            Runnable lock=()->assertThat(jdbc.queryForObject("SELECT state FROM crm.sales_accepted_quote WHERE tenant_id=? AND id=? FOR UPDATE",String.class,f.tenant(),a.quoteId())).isEqualTo("AVAILABLE");
            Runnable fresh=()->pay(f,a.quoteId(),1,2,"700",key());
            PgCommitOrderEvidence.runAcrossCutoff(jdbc,transactionManager,expiryFirst?lock:buy,
                expiryFirst?buy:fresh,expiryFirst?"QUOTE_EXPIRED":"QUOTE_ALREADY_CONSUMED_OR_INVALID",
                ()->awaitQuoteCutoff(cutoff));
            if(expiryFirst){
                assertThat(checkoutEvidence(f)).isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT state FROM crm.sales_accepted_quote WHERE tenant_id=? AND id=?",String.class,f.tenant(),a.quoteId())).isEqualTo("AVAILABLE");
            }else{
                assertOneQuoteOrder(f,a.quoteId(),order.get());
                var committed=checkoutEvidence(f);
                assertThat(pay(f,a.quoteId(),1,2,"700",buyKey).order().orderId()).isEqualTo(order.get());
                assertThat(checkoutEvidence(f)).isEqualTo(committed);
            }
        }
    }
    private void awaitQuoteCutoff(Instant cutoff){
        long timeout=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(Instant.now().isBefore(cutoff)||jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant().isBefore(cutoff)){
            if(System.nanoTime()>=timeout)throw new AssertionError("Quote cutoff not reached");
            try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
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
