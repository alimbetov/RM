package kz.rm.fx;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import kz.rm.storefront.StorefrontOfferService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.junit.jupiter.api.AfterEach;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class SkuFxSellerApprovalQualificationTest {
    @Autowired SkuFxSellerApprovalService fx;
    @Autowired NbrkRateRevisionWriter writer;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired StorefrontOfferService offers;
    @Autowired AutoOrderService orders;
    @Autowired DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired VerifiedTenantRoleCommands roleCommands;
    @Autowired MutableClock clock;
    @Autowired FxCalendarEvidenceService calendarEvidence;

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current=new AtomicReference<>(Instant.now());
        void advance(Duration duration) { current.updateAndGet(t->t.plus(duration)); }
        void reset() { current.set(Instant.now()); }
        void set(Instant at) { current.set(at); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return current.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean @Primary MutableClock fxTestClock() { return new MutableClock(); }
    }
    @AfterEach void resetFxClock() { clock.reset(); }

    private record Fixture(UUID tenant,UUID offer,UUID item,String slug,
                           VerifiedActor actor,UUID buyer) {}
    private Fixture fixture() {
        var owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"fxowner-"+UUID.randomUUID(),"FX owner"));
        var buyer=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"fxbuyer-"+UUID.randomUUID(),"Buyer"));
        var seller=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"fxseller-"+UUID.randomUUID(),"Seller"));
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"FX Approval LLP","FX store","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID tenant=workspace.getId(),item=UUID.randomUUID(),offer=UUID.randomUUID();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner.getId(),
                OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),seller.getId(),
                OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        String slug="fx-r2a-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item
              (tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,tenant,item,"FX-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',?)
            """,tenant,UUID.randomUUID(),slug,Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm.store_offer
              (tenant_id,id,title,unit_price,currency,state,catalog_item_id,row_version)
            VALUES (?,?,?,100.0000,'KZT','PUBLISHED',?,0)
            """,tenant,offer,"FX gadget",item);
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO public.tenant_currency_policy
             (tenant_id,accounting_currency,market_country,public_price_currency,
              status,policy_version,row_version,created_at,updated_at)
            VALUES (?,'KZT','KZ','KZT','ACTIVE',1,0,?,?)
            """,tenant,now,now);
        for(String purpose:List.of("PUBLIC_PRICE","SALES_INTERNAL")) {
            jdbc.update("""
                INSERT INTO public.tenant_allowed_currency
                 (tenant_id,currency_code,purpose,state,row_version,created_at,updated_at)
                VALUES (?,?,?,'ENABLED',0,?,?)
                """,tenant,"SALES_INTERNAL".equals(purpose)?"USD":"KZT",
                purpose,now,now);
        }
        String subject=jdbc.queryForObject("""
            SELECT external_subject FROM public.iam_user_account WHERE id=?
            """,String.class,owner.getId());
        JwtDecoder decoder=token->Jwt.withTokenValue(token).header("alg","RS256")
                .subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        VerifiedActor ownerActor=new OidcActorResolver(decoder,users).resolve("fx-owner");
        var role=roleCommands.createRole(ownerActor,tenant,
                "FX_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "FX seller test role");
        roleCommands.grant(ownerActor,tenant,role.id(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        roleCommands.assign(ownerActor,tenant,seller.getId(),role.id());
        roleCommands.enable(ownerActor,tenant);
        String sellerSubject=jdbc.queryForObject("""
            SELECT external_subject FROM public.iam_user_account WHERE id=?
            """,String.class,seller.getId());
        JwtDecoder sellerDecoder=token->Jwt.withTokenValue(token).header("alg","RS256")
                .subject(sellerSubject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        VerifiedActor actor=new OidcActorResolver(sellerDecoder,users).resolve("fx-staff");
        return new Fixture(tenant,offer,item,slug,actor,buyer.getId());
    }

    private LocalDate effectiveDate() {
        return LocalDate.now(ZoneId.of("Asia/Almaty")).minusDays(1);
    }
    private UUID rate(LocalDate date,String amount) {
        writer.persist(date,List.of(new FxRateProvider.Rate(
                "USD","KZT",new BigDecimal(amount),BigDecimal.ONE,date,"NBRK")),
                "f".repeat(64));
        return jdbc.queryForObject("""
            SELECT id FROM crm.fx_official_rate_snapshot
            WHERE provider_code='NBRK' AND base_currency='USD' AND valid_date=?
            ORDER BY source_revision DESC LIMIT 1
            """,UUID.class,java.sql.Date.valueOf(date));
    }
    private SkuFxSellerApprovalService.Policy policy(Fixture f) {
        return fx.configure(f.actor(),f.tenant(),f.offer(),"USD",
                new BigDecimal("1.0000"),BigDecimal.ZERO,BigDecimal.ZERO,
                BigDecimal.ONE,0);
    }
    private SkuFxSellerApprovalService.Calculation candidate(Fixture f,UUID rate) {
        return fx.calculate(f.actor(),f.tenant(),policy(f).id(),rate);
    }
    private int revisions(Fixture f) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM crm.store_offer_price_revision
            WHERE tenant_id=? AND offer_id=?
            """,Integer.class,f.tenant(),f.offer());
    }
    private int decisions(Fixture f) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM crm.sku_fx_seller_decision WHERE tenant_id=?
            """,Integer.class,f.tenant());
    }

    @Test void r2a01PolicyVersionsNeverOverwriteAndStaleCasFails() {
        var f=fixture();
        var first=policy(f);
        assertThat(first.revision()).isEqualTo(1);
        assertThatThrownBy(()->policy(f)).hasMessage("CUR_R2A_POLICY_STALE");
        var second=fx.configure(f.actor(),f.tenant(),f.offer(),"USD",
                new BigDecimal("2"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,1);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sku_fx_policy_version
            WHERE tenant_id=? AND offer_id=?
            """,Integer.class,f.tenant(),f.offer())).isEqualTo(2);
    }

    @Test void r2a02TrustedRateAndPolicyProduceImmutableIdempotentCalculation() {
        var f=fixture();UUID rate=rate(effectiveDate(),"500");
        var p=policy(f);
        var c=fx.calculate(f.actor(),f.tenant(),p.id(),rate);
        assertThat(c.suggestedKzt()).isEqualByComparingTo("500");
        assertThat(c.currentKzt()).isEqualByComparingTo("100");
        var replay=fx.calculate(f.actor(),f.tenant(),p.id(),rate);
        assertThat(replay.id()).isEqualTo(c.id());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sku_fx_calculation WHERE tenant_id=?
            """,Integer.class,f.tenant())).isEqualTo(1);
        assertThat(revisions(f)).isZero();
    }

    @Test void r2a03ApprovedOfferRevisionHasRatePolicyCalculationAndDecisionChain() {
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"501"));
        var d=fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-approve-001");
        assertThat(d.action()).isEqualTo("APPROVED");
        assertThat(d.priceRevisionId()).isNotNull();
        assertThat(revisions(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM crm.sku_fx_seller_decision d
            JOIN crm.sku_fx_calculation c
              ON c.tenant_id=d.tenant_id AND c.id=d.calculation_id
            JOIN crm.sku_fx_policy_version p
              ON p.tenant_id=c.tenant_id AND p.id=c.policy_id
            JOIN crm.fx_official_rate_snapshot r ON r.id=c.rate_snapshot_id
            JOIN crm.store_offer_price_revision pr
              ON pr.tenant_id=d.tenant_id AND pr.id=d.price_revision_id
            WHERE d.tenant_id=? AND d.id=? AND c.policy_revision=p.revision
              AND r.provider_code='NBRK' AND pr.new_currency='KZT'
              AND pr.new_price=c.candidate_price_kzt
            """,Integer.class,f.tenant(),d.id())).isEqualTo(1);
        assertThat(fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-approve-001").id())
                .isEqualTo(d.id());
        assertThat(revisions(f)).isEqualTo(1);
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-approve-002"))
                .hasMessage("CUR_R2A_DECISION_CONFLICT");
    }

    @Test void r2a04RejectedCandidateDoesNotMutatePrice() {
        var f=fixture();var c=candidate(f,rate(effectiveDate(),"503"));
        var d=fx.decide(f.actor(),f.tenant(),c.id(),false,"fx-reject-001");
        assertThat(d.priceRevisionId()).isNull();
        assertThat(revisions(f)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT unit_price FROM crm.store_offer WHERE tenant_id=? AND id=?
            """,BigDecimal.class,f.tenant(),f.offer())).isEqualByComparingTo("100");
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-reject-001"))
                .hasMessage("CUR_R2A_DECISION_CONFLICT");
    }

    @Test void r2a05StaleOfferPriceCausesRollbackWithoutDecision() {
        var f=fixture();var c=candidate(f,rate(effectiveDate(),"504"));
        offers.approveSuggestedKzt(f.actor(),f.tenant(),f.offer(),new BigDecimal("150"),0);
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-stale-001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("STALE_OFFER_VERSION");
        assertThat(decisions(f)).isZero();
        assertThat(revisions(f)).isEqualTo(1);
    }

    @Test void r2a06NBRKCorrectionInvalidatesUnapprovedCandidate() {
        var f=fixture();var date=effectiveDate();
        var c=candidate(f,rate(date,"505"));
        rate(date,"506");
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-rate-001"))
                .hasMessage("CUR_R2A_RATE_SUPERSEDED");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2a07ActiveNegotiationBlocksPriceAndPreservesOrderTerms() {
        var f=fixture();var c=candidate(f,rate(effectiveDate(),"507"));
        var now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.sales_price_proposal
              (tenant_id,id,offer_id,catalog_item_id,buyer_user_id,quantity,
               list_price_at_submit,current_unit_price,currency,state,revision,
               next_actor,round_count,expires_at,created_at,updated_at,row_version)
            VALUES (?,?,?,?,?,1,100,90,'KZT','AWAITING_SELLER',1,'SELLER',0,?,?,?,0)
            """,f.tenant(),UUID.randomUUID(),f.offer(),f.item(),f.buyer(),
                Timestamp.from(Instant.now().plusSeconds(3600)),now,now);
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-neg-001"))
                .hasMessage("CUR_R2A_ACTIVE_NEGOTIATION");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2a08CheckoutSnapshotUnchangedAfterManualFxApproval() {
        var f=fixture();
        var receipt=orders.checkout(f.slug(),"fx-buyer-"+UUID.randomUUID()+"@example.kz",
                "fx-order-"+UUID.randomUUID().toString().substring(0,15),
                List.of(new AutoOrderService.Line(f.offer(),1)));
        var c=candidate(f,rate(effectiveDate(),"508"));
        fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-order-appr-001");
        assertThat(jdbc.queryForObject("""
            SELECT unit_price_snapshot FROM crm.sales_order_line
            WHERE tenant_id=? AND order_id=?
            """,BigDecimal.class,f.tenant(),receipt.order().orderId()))
                .isEqualByComparingTo("100");
    }

    @Test void r2a09TenantIsolationBlocksForeignCalculationAndDecision() {
        var a=fixture();var b=fixture();
        var c=candidate(a,rate(effectiveDate(),"509"));
        assertThatThrownBy(()->fx.decide(b.actor(),b.tenant(),c.id(),true,"fx-cross-001"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->fx.configure(a.actor(),b.tenant(),b.offer(),"USD",
                BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,0))
                .isInstanceOf(SecurityException.class);
        assertThat(decisions(a)).isZero();
    }

    @Test void r2a11PostgresqlNegotiationCommitBeforeApprovalDeniesAtomicPriceChange()
            throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"511"));
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var negotiate=pool.submit(()->tx.execute(status->{
                jdbc.queryForObject("""
                    SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                    """,UUID.class,f.tenant());
                locked.countDown();
                try {
                    if(!release.await(5,TimeUnit.SECONDS))
                        throw new IllegalStateException("CUR_R2A_NEGOTIATION_RACE_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                Timestamp now=Timestamp.from(Instant.now());
                jdbc.update("""
                    INSERT INTO crm.sales_price_proposal
                      (tenant_id,id,offer_id,catalog_item_id,buyer_user_id,quantity,
                       list_price_at_submit,current_unit_price,currency,state,revision,
                       next_actor,round_count,expires_at,created_at,updated_at,row_version)
                    VALUES (?,?,?,?,?,1,100,95,'KZT','AWAITING_SELLER',1,'SELLER',0,?,?,?,0)
                    """,f.tenant(),UUID.randomUUID(),f.offer(),f.item(),f.buyer(),
                    Timestamp.from(Instant.now().plusSeconds(3600)),now,now);
                return true;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var approve=pool.submit(()->{
                try {
                    fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-race-neg-001");
                    return "APPROVED";
                } catch(IllegalStateException expected) {
                    return expected.getMessage();
                }
            });
            release.countDown();
            assertThat(negotiate.get(8,TimeUnit.SECONDS)).isTrue();
            assertThat(approve.get(8,TimeUnit.SECONDS))
                    .isEqualTo("CUR_R2A_ACTIVE_NEGOTIATION");
            assertThat(revisions(f)).isZero();
            assertThat(decisions(f)).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test void r2a12PostgresqlRateCorrectionVsApprovalUsesOneSourceRevision()
            throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture();
        LocalDate day=effectiveDate();
        var c=candidate(f,rate(day,"520"));
        var correctionLocked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var correction=pool.submit(()->tx.execute(status->{
                writer.persist(day,List.of(new FxRateProvider.Rate(
                        "USD","KZT",new BigDecimal("521"),BigDecimal.ONE,day,"NBRK")),
                        "e".repeat(64));
                correctionLocked.countDown();
                try {
                    if(!release.await(5,TimeUnit.SECONDS))
                        throw new IllegalStateException("CUR_R2A_SOURCE_RACE_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(correctionLocked.await(5,TimeUnit.SECONDS)).isTrue();
            var approval=pool.submit(()->{
                try {
                    fx.decide(f.actor(),f.tenant(),c.id(),true,"fx-correct-race-001");
                    return "APPROVED";
                } catch(IllegalStateException expected) {
                    return expected.getMessage();
                }
            });
            release.countDown();
            assertThat(correction.get(8,TimeUnit.SECONDS)).isTrue();
            assertThat(approval.get(8,TimeUnit.SECONDS))
                    .isEqualTo("CUR_R2A_RATE_SUPERSEDED");
            assertThat(revisions(f)).isZero();
            assertThat(decisions(f)).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test void r2a10PostgresqlEvidenceRejectsUpdateOrDelete() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture();var c=candidate(f,rate(effectiveDate(),"510"));
        var d=fx.decide(f.actor(),f.tenant(),c.id(),false,"fx-immut-001");
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.sku_fx_seller_decision WHERE tenant_id=? AND id=?
            """,f.tenant(),d.id())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.sku_fx_calculation SET candidate_price_kzt=999
            WHERE tenant_id=? AND id=?
            """,f.tenant(),c.id())).isInstanceOf(Exception.class);
        assertThat(decisions(f)).isEqualTo(1);
    }
    @Test void r2b01PostgresqlConcurrentIdenticalCalculationsShareOneReceipt() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture(); var p=policy(f); UUID rate=rate(effectiveDate(),"540");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return fx.calculate(f.actor(),f.tenant(),p.id(),rate);
            });
            var second=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return fx.calculate(f.actor(),f.tenant(),p.id(),rate);
            });
            start.countDown();
            assertThat(first.get(12,TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(12,TimeUnit.SECONDS).id());
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sku_fx_calculation WHERE tenant_id=?
                """,Integer.class,f.tenant())).isEqualTo(1);
        }
    }

    @Test void r2b02GuardrailRejectsOutOfBoundsIncreaseWithoutPriceMutation() {
        var f=fixture();
        fx.configureGuardrails(f.actor(),f.tenant(),f.offer(),
                new BigDecimal("10"),new BigDecimal("10"),
                new BigDecimal("50"),new BigDecimal("1000"),7,3600,0);
        var c=candidate(f,rate(effectiveDate(),"550"));
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-bound-001"))
                .hasMessage("CUR_R2B_PRICE_DELTA_EXCEEDED");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2b03GuardrailRejectsStaleRateWithoutPriceMutation() {
        var f=fixture();
        fx.configureGuardrails(f.actor(),f.tenant(),f.offer(),
                new BigDecimal("1000"),new BigDecimal("100"),
                new BigDecimal("1"),new BigDecimal("100000"),1,3600,0);
        var c=candidate(f,rate(effectiveDate().minusDays(10),"550"));
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-rate-001"))
                .hasMessage("CUR_R2B_RATE_TOO_OLD");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2b04ExpiredCalculationNeverPublishesPrice() throws Exception {
        var f=fixture();
        fx.configureGuardrails(f.actor(),f.tenant(),f.offer(),
                new BigDecimal("1000"),new BigDecimal("100"),
                new BigDecimal("1"),new BigDecimal("100000"),30,1,0);
        var c=candidate(f,rate(effectiveDate(),"551"));
        clock.advance(Duration.ofSeconds(2));
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-expired-001"))
                .hasMessage("CUR_R2B_CALCULATION_EXPIRED");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2b05SupersededGuardrailCannotApproveEarlierCalculation() {
        var f=fixture();
        fx.configureGuardrails(f.actor(),f.tenant(),f.offer(),
                new BigDecimal("1000"),new BigDecimal("100"),
                new BigDecimal("1"),new BigDecimal("100000"),30,3600,0);
        var c=candidate(f,rate(effectiveDate(),"552"));
        fx.configureGuardrails(f.actor(),f.tenant(),f.offer(),
                new BigDecimal("10"),new BigDecimal("10"),
                new BigDecimal("1"),new BigDecimal("100000"),30,3600,1);
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-policy-001"))
                .hasMessage("CUR_R2B_GUARDRAIL_STALE");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2b06ExactMaxIncreaseAndPriceBoundsAreInclusive() {
        var f=fixture();
        fx.configureGuardrails(f.actor(),f.tenant(),f.offer(),
                new BigDecimal("10"),new BigDecimal("0"),
                new BigDecimal("110"),new BigDecimal("110"),7,3600,0);
        var c=candidate(f,rate(effectiveDate(),"110"));
        var d=fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-bound-equal-001");
        assertThat(d.action()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("""
            SELECT unit_price FROM crm.store_offer WHERE tenant_id=? AND id=?
            """,BigDecimal.class,f.tenant(),f.offer())).isEqualByComparingTo("110");
        assertThat(revisions(f)).isEqualTo(1);
    }

    @Test void r2b07PostgresqlFxApprovalCommitsBeforeCheckoutPreservingNewSnapshot()
            throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"560"));
        var approved=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var approval=pool.submit(()->tx.execute(status->{
                jdbc.queryForObject("""
                    SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                    """,UUID.class,f.tenant());
                fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-approve-first-001");
                approved.countDown();
                try {
                    if(!release.await(8,TimeUnit.SECONDS))
                        throw new IllegalStateException("CUR_R2B_APPROVAL_WAIT_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(approved.await(8,TimeUnit.SECONDS)).isTrue();
            var checkout=pool.submit(()->orders.checkout(f.slug(),
                "fx-buyer-"+UUID.randomUUID()+"@example.kz",
                "r2b-order-"+UUID.randomUUID().toString().substring(0,15),
                List.of(new AutoOrderService.Line(f.offer(),1))));
            release.countDown();
            assertThat(approval.get(12,TimeUnit.SECONDS)).isTrue();
            var receipt=checkout.get(12,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,BigDecimal.class,f.tenant(),receipt.order().orderId()))
                .isEqualByComparingTo("560");
            assertThat(revisions(f)).isEqualTo(1);
            assertThat(decisions(f)).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test void r2b08PostgresqlFxApprovalCommitsBeforeNewNegotiation() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"561"));
        var approved=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var approval=pool.submit(()->tx.execute(status->{
                jdbc.queryForObject("""
                    SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                    """,UUID.class,f.tenant());
                fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-approve-neg-first-001");
                approved.countDown();
                try {
                    if(!release.await(8,TimeUnit.SECONDS))
                        throw new IllegalStateException("CUR_R2B_APPROVAL_NEG_WAIT_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(approved.await(8,TimeUnit.SECONDS)).isTrue();
            var negotiation=pool.submit(()->tx.execute(status->{
                jdbc.queryForObject("""
                    SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                    """,UUID.class,f.tenant());
                var price=jdbc.queryForObject("""
                    SELECT unit_price FROM crm.store_offer WHERE tenant_id=? AND id=?
                    """,BigDecimal.class,f.tenant(),f.offer());
                Timestamp now=Timestamp.from(Instant.now());
                jdbc.update("""
                    INSERT INTO crm.sales_price_proposal
                      (tenant_id,id,offer_id,catalog_item_id,buyer_user_id,quantity,
                       list_price_at_submit,current_unit_price,currency,state,revision,
                       next_actor,round_count,expires_at,created_at,updated_at,row_version)
                    VALUES (?,?,?,?,?,1,?,?,'KZT','AWAITING_SELLER',1,'SELLER',0,?,?,?,0)
                    """,f.tenant(),UUID.randomUUID(),f.offer(),f.item(),f.buyer(),
                    price,price,Timestamp.from(Instant.now().plusSeconds(3600)),now,now);
                return price;
            }));
            release.countDown();
            assertThat(approval.get(12,TimeUnit.SECONDS)).isTrue();
            assertThat(negotiation.get(12,TimeUnit.SECONDS)).isEqualByComparingTo("561");
            assertThat(revisions(f)).isEqualTo(1);
            assertThat(decisions(f)).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test void r2b09WeekendGraceUsesPublishedValidDateAndInjectedAlmatyClock() {
        // This verifies a configured calendar-day allowance, NOT an invented
        // official holiday schedule or inferred NBRK publication date.
        clock.set(Instant.parse("2026-11-22T08:00:00Z")); // Sunday in Almaty
        var f=fixture();
        fx.configureGuardrails(f.actor(),f.tenant(),f.offer(),
                new BigDecimal("1000"),new BigDecimal("100"),
                new BigDecimal("1"),new BigDecimal("100000"),2,604800,0);
        UUID rate=rate(LocalDate.of(2026,11,20),"565");
        var c=candidate(f,rate);
        clock.advance(Duration.ofDays(1)); // Monday; same immutable evidence now 3 days old
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,
                "r2b-weekend-001")).hasMessage("CUR_R2B_RATE_TOO_OLD");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2b10PostgresqlAvailableQuoteCommitsBeforeFxAndBlocksPriceChange() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"570"));
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var quoteWriter=pool.submit(()->tx.execute(status->{
                jdbc.queryForObject("""
                    SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                    """,UUID.class,f.tenant());
                Timestamp now=Timestamp.from(Instant.now());
                UUID proposal=UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO crm.sales_price_proposal
                      (tenant_id,id,offer_id,catalog_item_id,buyer_user_id,quantity,
                       list_price_at_submit,current_unit_price,currency,state,revision,
                       next_actor,round_count,expires_at,created_at,updated_at,row_version)
                    VALUES (?,?,?,?,?,1,100,95,'KZT','AGREED',1,'NONE',0,?,?,?,0)
                    """,f.tenant(),proposal,f.offer(),f.item(),f.buyer(),
                    Timestamp.from(Instant.now().plusSeconds(7200)),now,now);
                jdbc.update("""
                    INSERT INTO crm.sales_accepted_quote
                      (tenant_id,id,proposal_id,agreed_revision,offer_id,buyer_user_id,
                       quantity,agreed_unit_price,currency,state,expires_at,created_at)
                    VALUES (?,?,?,1,?,?,1,95,'KZT','AVAILABLE',?,?)
                    """,f.tenant(),UUID.randomUUID(),proposal,f.offer(),f.buyer(),
                    Timestamp.from(Instant.now().plusSeconds(3600)),now);
                locked.countDown();
                try {
                    if(!release.await(8,TimeUnit.SECONDS))
                        throw new IllegalStateException("CUR_R2B_QUOTE_WAIT_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(locked.await(8,TimeUnit.SECONDS)).isTrue();
            var approval=pool.submit(()->{
                try {
                    fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-quote-race-001");
                    return "APPROVED";
                } catch(IllegalStateException expected) {
                    return expected.getMessage();
                }
            });
            release.countDown();
            assertThat(quoteWriter.get(12,TimeUnit.SECONDS)).isTrue();
            assertThat(approval.get(12,TimeUnit.SECONDS))
                    .isEqualTo("CUR_R2A_ACTIVE_NEGOTIATION");
            assertThat(revisions(f)).isZero();
            assertThat(decisions(f)).isZero();
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND offer_id=? AND state='AVAILABLE'
                """,Integer.class,f.tenant(),f.offer())).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test void r2b11PostgresqlSellerSuspensionCommitsBeforeFxApproval() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"571"));
        var suspended=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var disable=pool.submit(()->tx.execute(status->{
                jdbc.update("""
                    UPDATE public.iam_user_account SET status='SUSPENDED'
                    WHERE id=?
                    """,f.actor().userId());
                suspended.countDown();
                try {
                    if(!release.await(8,TimeUnit.SECONDS))
                        throw new IllegalStateException("CUR_R2B_IAM_WAIT_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(suspended.await(8,TimeUnit.SECONDS)).isTrue();
            var approval=pool.submit(()->{
                try {
                    fx.decide(f.actor(),f.tenant(),c.id(),true,"r2b-suspend-race-001");
                    return "APPROVED";
                } catch(SecurityException expected) {
                    return "DENIED";
                }
            });
            release.countDown();
            assertThat(disable.get(12,TimeUnit.SECONDS)).isTrue();
            assertThat(approval.get(12,TimeUnit.SECONDS)).isEqualTo("DENIED");
            assertThat(revisions(f)).isZero();
            assertThat(decisions(f)).isZero();
        } finally {
            release.countDown();
        }
    }

    private String calendarDigest(byte[] source) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(source));
        } catch(java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private UUID stageCalendar(NbrkTradingCalendarPolicy.TradingStatus status,
                               LocalDate date) {
        byte[] source=("RM synthetic calendar qualification fixture "+UUID.randomUUID()).getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        String sha=calendarDigest(source);
        String url="https://kase.kz/ru/members/trading-and-clearing-regulation";
        return calendarEvidence.stageSource(url,source,sha,List.of(
                new NbrkTradingCalendarPolicy.Day(date,"USD/KZT",status,url,sha)));
    }

    @Test void r2b12CalendarGateFailsClosedOnMissingDateWithoutPriceChange() {
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"580"));
        UUID sourceId=stageCalendar(NbrkTradingCalendarPolicy.TradingStatus.CLOSED,
                effectiveDate());
        calendarEvidence.enableForTenant(f.actor(),f.tenant(),sourceId,0);
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,
                "r2b-calendar-missing-001"))
                .hasMessage("CUR_R2B_CALENDAR_EVIDENCE_MISSING");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2b13ClosedSessionAllowsApprovalWhenCalendarGateEnabled() {
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"581"));
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Almaty")));
        UUID sourceId=stageCalendar(NbrkTradingCalendarPolicy.TradingStatus.CLOSED,today);
        calendarEvidence.enableForTenant(f.actor(),f.tenant(),sourceId,0);
        var receipt=fx.decide(f.actor(),f.tenant(),c.id(),true,
                "r2b-calendar-closed-001");
        assertThat(receipt.action()).isEqualTo("APPROVED");
        assertThat(revisions(f)).isEqualTo(1);
        assertThat(decisions(f)).isEqualTo(1);
    }

    @Test void r2b14TradingSessionAgeFailsClosedWithZeroAllowance() {
        var f=fixture();
        var c=candidate(f,rate(effectiveDate(),"582"));
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Almaty")));
        UUID sourceId=stageCalendar(NbrkTradingCalendarPolicy.TradingStatus.TRADING,today);
        calendarEvidence.enableForTenant(f.actor(),f.tenant(),sourceId,0);
        assertThatThrownBy(()->fx.decide(f.actor(),f.tenant(),c.id(),true,
                "r2b-calendar-trading-001"))
                .hasMessage("CUR_R2B_TRADING_DAY_AGE_EXCEEDED");
        assertThat(revisions(f)).isZero();
        assertThat(decisions(f)).isZero();
    }

    @Test void r2b15AlteredCalendarSourceBytesCannotBeStaged() {
        byte[] source="verified sample".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String url="https://kase.kz/ru/members/trading-and-clearing-regulation";
        String claimed="f".repeat(64);
        assertThatThrownBy(()->calendarEvidence.stageSource(url,source,claimed,List.of(
                new NbrkTradingCalendarPolicy.Day(effectiveDate(),"USD/KZT",
                    NbrkTradingCalendarPolicy.TradingStatus.CLOSED,url,claimed))))
                .hasMessage("CUR_R2B_CALENDAR_SOURCE_HASH_MISMATCH");
    }

}
