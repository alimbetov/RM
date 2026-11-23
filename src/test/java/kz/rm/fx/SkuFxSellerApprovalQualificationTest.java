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
}
