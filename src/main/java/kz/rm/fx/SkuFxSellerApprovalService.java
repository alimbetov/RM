package kz.rm.fx;

import kz.rm.iam.VerifiedActor;
import kz.rm.organization.TenantCurrencyPolicyService;
import kz.rm.storefront.StorefrontOfferService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * CUR-R2A service-only seller FX suggestion with immutable PostgreSQL evidence.
 * Price ownership remains in STOREFRONT. Not a rate worker, checkout path,
 * controller, automatic repricing mechanism or order/fulfillment engine.
 */
@Service
public class SkuFxSellerApprovalService {
    private static final String FORMULA="SKU_FX_R2A_V1";
    public record Policy(UUID id,UUID tenant,UUID offer,long revision,String currency,
                         BigDecimal anchor,BigDecimal buffer,BigDecimal markup,BigDecimal step) {}
    public record Calculation(UUID id,UUID tenant,UUID offer,UUID policy,long policyRevision,
                              UUID rateId,long offerVersion,BigDecimal currentKzt,
                              BigDecimal suggestedKzt,String digest,UUID guardrailId,long guardrailRevision) {}
    public record Guardrail(UUID id,UUID tenant,UUID offer,long revision,
                       BigDecimal maxIncreasePct,BigDecimal maxDecreasePct,
                       BigDecimal minPriceKzt,BigDecimal maxPriceKzt,
                       int maxRateCalendarDays,int calculationTtlSeconds) {}
    public record Decision(UUID id,UUID calculationId,String action,UUID priceRevisionId) {}

    private final JdbcTemplate jdbc;
    private final StorefrontOfferService offers;
    private final TenantCurrencyPolicyService currencies;
    private final SkuFxCandidateCalculator calculator;
    private final FxCalendarEvidenceService calendarEvidence;
    private String officialRateReadLockSql;
    private final Clock clock;

    @PostConstruct
    void configureSourceLock() {
        String db=jdbc.execute((ConnectionCallback<String>)
                connection->connection.getMetaData().getDatabaseProductName());
        officialRateReadLockSql="""
            SELECT provider_code FROM crm.fx_rate_provider_lock
            WHERE provider_code='NBRK'
            """+(db!=null && db.toLowerCase(java.util.Locale.ROOT).contains("postgresql")
                    ?" FOR SHARE":" FOR UPDATE");
    }

    /**
     * The trusted writer holds NBRK FOR UPDATE until its whole revision batch
     * commits. This shared lock guarantees that a new correction cannot cross
     * the calculation/approval's latest-revision check before commit.
     */
    private void lockOfficialRateSource() {
        String row=jdbc.query(officialRateReadLockSql,
                rs->rs.next()?rs.getString(1):null);
        if(!"NBRK".equals(row))
            throw new IllegalStateException("CUR_R2A_TRUSTED_RATE_SOURCE_UNAVAILABLE");
    }

    public SkuFxSellerApprovalService(JdbcTemplate jdbc, StorefrontOfferService offers,
                 TenantCurrencyPolicyService currencies, SkuFxCandidateCalculator calculator,
                 Clock clock,FxCalendarEvidenceService calendarEvidence) {
        this.jdbc=jdbc;this.offers=offers;this.currencies=currencies;this.calculator=calculator;
        this.clock=java.util.Objects.requireNonNull(clock,"FX evaluation clock required");
        this.calendarEvidence=java.util.Objects.requireNonNull(calendarEvidence);
    }

    /**
     * New immutable policy revision. Exact seller authority is delegated to
     * existing offer management, while the tenant purpose is independently checked.
     * Lock DAG: IAM/workspace -> tenant currency policy -> store_front -> offer.
     */
    @Transactional
    public Policy configure(VerifiedActor actor,UUID tenant,UUID offerId,String anchorCurrency,
                 BigDecimal anchor,BigDecimal buffer,BigDecimal markup,BigDecimal roundingStep,
                 long expectedPolicyRevision) {
        requireActor(actor,tenant,offerId);
        if(expectedPolicyRevision<0 || anchorCurrency==null)
            throw new IllegalArgumentException("CUR_R2A_POLICY_INVALID");
        offers.get(actor,tenant,offerId);
        currencies.requireCurrencyForPurpose(tenant,anchorCurrency,"SALES_INTERNAL");
        lockStorefront(tenant);
        var current=offer(tenant,offerId);
        requirePublishedKzt(current);
        // Validate exact computation bounds without writing any rate evidence.
        validatePolicy(anchorCurrency,anchor,buffer,markup,roundingStep);
        Long last=jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision),0) FROM crm.sku_fx_policy_version
            WHERE tenant_id=? AND offer_id=?
            """,Long.class,tenant,offerId);
        long revision=last==null?0:last;
        if(revision!=expectedPolicyRevision)
            throw new IllegalStateException("CUR_R2A_POLICY_STALE");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.sku_fx_policy_version
              (tenant_id,id,offer_id,revision,anchor_currency,anchor_amount,
               buffer_pct,markup_pct,rounding_step_kzt,approved_by,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,tenant,id,offerId,revision+1,anchorCurrency,anchor,buffer,markup,
                roundingStep,actor.userId(),Timestamp.from(clock.instant()));
        return new Policy(id,tenant,offerId,revision+1,anchorCurrency,anchor,buffer,
                markup,roundingStep);
    }

    /** CUR-R2B: append-only, seller-authorized price guardrails for one offer. */
    @Transactional
    public Guardrail configureGuardrails(VerifiedActor actor,UUID tenant,UUID offerId,
                  BigDecimal maxIncrease,BigDecimal maxDecrease,BigDecimal minPrice,
                  BigDecimal maxPrice,int rateCalendarDays,int ttlSeconds,long expectedRevision) {
        requireActor(actor,tenant,offerId);
        offers.get(actor,tenant,offerId);
        lockStorefront(tenant);
        requirePublishedKzt(offer(tenant,offerId));
        if(maxIncrease==null||maxDecrease==null||minPrice==null||maxPrice==null
                ||maxIncrease.signum()<0||maxIncrease.compareTo(new BigDecimal("1000"))>0
                ||maxDecrease.signum()<0||maxDecrease.compareTo(new BigDecimal("100"))>0
                ||minPrice.signum()<=0||maxPrice.compareTo(minPrice)<0
                ||maxIncrease.scale()>4||maxDecrease.scale()>4
                ||minPrice.scale()>4||maxPrice.scale()>4
                ||minPrice.precision()>19||maxPrice.precision()>19
                ||rateCalendarDays<0||rateCalendarDays>365
                ||ttlSeconds<1||ttlSeconds>604800||expectedRevision<0)
            throw new IllegalArgumentException("CUR_R2B_GUARDRAIL_INVALID");
        long revision=latestGuardrailRevision(tenant,offerId);
        if(revision!=expectedRevision)throw new IllegalStateException("CUR_R2B_GUARDRAIL_STALE");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.sku_fx_guardrail_version
              (tenant_id,id,offer_id,revision,max_increase_pct,max_decrease_pct,
               min_price_kzt,max_price_kzt,max_rate_calendar_days,
               calculation_ttl_seconds,approved_by,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,tenant,id,offerId,revision+1,maxIncrease,maxDecrease,minPrice,maxPrice,
                rateCalendarDays,ttlSeconds,actor.userId(),Timestamp.from(clock.instant()));
        return new Guardrail(id,tenant,offerId,revision+1,maxIncrease,maxDecrease,
                minPrice,maxPrice,rateCalendarDays,ttlSeconds);
    }

    /**
     * A calculation is evidence, not an offer mutation. Exact NBRK snapshot
     * and the captured offer version are persisted for forensic replay.
     */
    @Transactional
    public Calculation calculate(VerifiedActor actor,UUID tenant,UUID policyId,UUID rateId) {
        if(actor==null||tenant==null||policyId==null||rateId==null)
            throw new IllegalArgumentException("CUR_R2A_CALC_INPUT_INVALID");
        Policy policy=policy(tenant,policyId);
        offers.get(actor,tenant,policy.offer());
        lockOfficialRateSource();
        currencies.requireCurrencyForPurpose(tenant,policy.currency(),"SALES_INTERNAL");
        lockStorefront(tenant);
        Offer offer=offer(tenant,policy.offer());
        requirePublishedKzt(offer);
        if(policy.revision()!=latestPolicyRevision(tenant,policy.offer()))
            throw new IllegalStateException("CUR_R2A_POLICY_STALE");
        Rate rate=rate(rateId,policy.currency());
        if(rate.date().isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Almaty")))))
            throw new IllegalStateException("CUR_R2A_FUTURE_RATE_FORBIDDEN");
        if(rate.revision()!=latestRateRevision(rate))
            throw new IllegalStateException("CUR_R2A_RATE_SUPERSEDED");
        var ref=new NbrkRateSnapshotReader.Revision(rate.id(),rate.importRunId(),
                rate.currency(),rate.nominal(),rate.value(),rate.date(),
                rate.revision(),rate.semanticSha(),rate.rawSha());
        Guardrail guardrail=latestGuardrail(tenant,policy.offer());
        var result=calculator.preview(policy.currency(),policy.anchor(),policy.buffer(),
                policy.markup(),policy.step(),ref);
        String digest=sha("CUR-R2A|"+tenant+"|"+policy.id()+"|"+policy.revision()
                +"|"+rate.id()+"|"+offer.version()+"|"+offer.price().toPlainString()
                +"|"+result.suggestedKzt().toPlainString()+"|"+FORMULA
                +"|"+(guardrail==null?"NONE":guardrail.id()+":"+guardrail.revision()));
        List<Calculation> existing=jdbc.query("""
            SELECT id FROM crm.sku_fx_calculation
            WHERE tenant_id=? AND request_sha256=?
            """,(rs,n)->new Calculation(rs.getObject(1,UUID.class),tenant,policy.offer(),
                policy.id(),policy.revision(),rate.id(),offer.version(),offer.price(),
                result.suggestedKzt(),digest,guardrail==null?null:guardrail.id(),
                guardrail==null?0:guardrail.revision()),tenant,digest);
        if(!existing.isEmpty())return existing.getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.sku_fx_calculation
              (tenant_id,id,offer_id,policy_id,policy_revision,rate_snapshot_id,
               offer_row_version,current_price_kzt,candidate_price_kzt,
               anchor_currency,anchor_amount,nominal,rate_kzt,valid_date,
               source_revision,source_sha256,buffer_pct,markup_pct,
               rounding_step_kzt,formula_version,request_sha256,calculated_at,
               guardrail_id,guardrail_revision)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,tenant,id,policy.offer(),policy.id(),policy.revision(),rate.id(),
                offer.version(),offer.price(),result.suggestedKzt(),policy.currency(),
                policy.anchor(),rate.nominal(),rate.value(),Date.valueOf(rate.date()),
                rate.revision(),rate.semanticSha(),policy.buffer(),policy.markup(),
                policy.step(),FORMULA,digest,Timestamp.from(clock.instant()),
                guardrail==null?null:guardrail.id(),guardrail==null?null:guardrail.revision());
        return new Calculation(id,tenant,policy.offer(),policy.id(),policy.revision(),
                rate.id(),offer.version(),offer.price(),result.suggestedKzt(),digest,
                guardrail==null?null:guardrail.id(),guardrail==null?0:guardrail.revision());
    }

    /**
     * Approve or reject immutable calculation evidence. STOREFRONT's existing
     * method performs the only public price mutation, guarded by its CAS.
     * Decision and commercial revision commit together or both roll back.
     */
    @Transactional
    public Decision decide(VerifiedActor actor,UUID tenant,UUID calculationId,
                           boolean approve,String requestKey) {
        if(actor==null||tenant==null||calculationId==null||requestKey==null
                ||!requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("CUR_R2A_DECISION_INVALID");
        Calculation calculation=calculation(tenant,calculationId);
        offers.get(actor,tenant,calculation.offer()); // tenant and RBAC fence
        String action=approve?"APPROVED":"REJECTED";
        Decision existing=decision(tenant,calculationId);
        if(existing!=null) {
            // A receipt is the immutable result of the first decision.
            record Receipt(String key,UUID actorId) {}
            Receipt receipt=jdbc.query("""
                SELECT request_key,actor_user_id FROM crm.sku_fx_seller_decision
                WHERE tenant_id=? AND calculation_id=?
                """,rs->rs.next()?new Receipt(rs.getString(1),rs.getObject(2,UUID.class)):null,
                    tenant,calculationId);
            if(receipt==null || !actor.userId().equals(receipt.actorId())
                    || !action.equals(existing.action())||!requestKey.equals(receipt.key()))
                throw new IllegalStateException("CUR_R2A_DECISION_CONFLICT");
            return existing;
        }
        Integer claimed=jdbc.queryForObject("""
            SELECT count(*) FROM crm.sku_fx_seller_decision
            WHERE tenant_id=? AND request_key=?
            """,Integer.class,tenant,requestKey);
        if(claimed!=null&&claimed>0)
            throw new IllegalStateException("CUR_R2A_REQUEST_KEY_CONFLICT");

        // For a rejection, no currency policy or commercial change is needed.
        // For approval, the canonical STOREFRONT mutation obtains currency
        // -> storefront -> offer in the established order.
        UUID priceRevision=null;
        UUID calendarSourceId=null;
        if(approve) {
            lockOfficialRateSource();
            // Recheck latest policy/rate and block negotiation while holding
            // seller storefront lock acquired by the following price mutation.
            if(!"KZT".equals(offer(tenant,calculation.offer()).currency()))
                throw new IllegalStateException("CUR_R2A_PUBLIC_KZT_REQUIRED");
            var approved=offers.approveSuggestedKzt(actor,tenant,calculation.offer(),
                    calculation.suggestedKzt(),calculation.offerVersion());
            if(approved.rowVersion()!=calculation.offerVersion()+1)
                throw new IllegalStateException("CUR_R2A_NO_PRICE_REVISION");
            // STOREFRONT lock is now held until transaction commit.
            if(latestPolicyRevision(tenant,calculation.offer())!=calculation.policyRevision())
                throw new IllegalStateException("CUR_R2A_POLICY_STALE");
            Policy active=policy(tenant,calculation.policy());
            Rate verified=rate(calculation.rateId(),active.currency());
            if(verified.revision()!=latestRateRevision(verified))
                throw new IllegalStateException("CUR_R2A_RATE_SUPERSEDED");
            calendarSourceId=verifyGuardrails(tenant,calculation,verified);
            Integer negotiations=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_price_proposal
                WHERE tenant_id=? AND offer_id=?
                  AND state IN ('AWAITING_SELLER','AWAITING_BUYER')
                """,Integer.class,tenant,calculation.offer());
            Integer quotes=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND offer_id=? AND state='AVAILABLE'
                  AND expires_at>CURRENT_TIMESTAMP
                """,Integer.class,tenant,calculation.offer());
            if((negotiations!=null&&negotiations>0)||(quotes!=null&&quotes>0))
                throw new IllegalStateException("CUR_R2A_ACTIVE_NEGOTIATION");
            priceRevision=jdbc.queryForObject("""
                SELECT id FROM crm.store_offer_price_revision
                WHERE tenant_id=? AND offer_id=? AND offer_row_version=?
                """,UUID.class,tenant,calculation.offer(),approved.rowVersion());
            if(priceRevision==null)
                throw new IllegalStateException("CUR_R2A_PRICE_REVISION_MISSING");
            // Same amount means the canonical store service did not append a price
            // revision; reject rather than manufacturing an approval record.
            if(calculation.currentKzt().compareTo(calculation.suggestedKzt())==0)
                throw new IllegalStateException("CUR_R2A_NO_PRICE_CHANGE");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.sku_fx_seller_decision
              (tenant_id,id,calculation_id,offer_id,actor_user_id,request_key,
               action,price_revision_id,decided_at,calendar_source_id)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,tenant,id,calculationId,calculation.offer(),actor.userId(),
                requestKey,action,priceRevision,Timestamp.from(clock.instant()),calendarSourceId);
        return new Decision(id,calculationId,action,priceRevision);
    }

    private void lockStorefront(UUID tenant) {
        UUID row=jdbc.query("""
            SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenant);
        if(row==null)throw new IllegalStateException("CUR_R2A_STOREFRONT_MISSING");
    }

    private record Offer(BigDecimal price,String currency,String state,long version) {}
    private Offer offer(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT unit_price,currency,state,row_version FROM crm.store_offer
            WHERE tenant_id=? AND id=?
            """,rs->rs.next()?new Offer(rs.getBigDecimal(1),rs.getString(2).trim(),
                rs.getString(3),rs.getLong(4)):null,tenant,id);
    }
    private void requirePublishedKzt(Offer offer) {
        if(offer==null || !"PUBLISHED".equals(offer.state())
                || !"KZT".equals(offer.currency()))
            throw new IllegalStateException("CUR_R2A_OFFER_NOT_PUBLIC_KZT");
    }
    private static void requireActor(VerifiedActor actor,UUID tenant,UUID offer) {
        if(actor==null||tenant==null||offer==null)
            throw new SecurityException("CUR_R2A_SELLER_REQUIRED");
    }
    private static void validatePolicy(String currency,BigDecimal anchor,
                           BigDecimal buffer,BigDecimal markup,BigDecimal step) {
        if(!List.of("USD","EUR","CNY").contains(currency)
                ||anchor==null||anchor.signum()<=0||anchor.scale()>4
                ||anchor.precision()>19||step==null||step.signum()<=0
                ||step.scale()>4||step.precision()>19
                ||buffer==null||markup==null||buffer.signum()<0||markup.signum()<0
                ||buffer.compareTo(new BigDecimal("1000"))>0
                ||markup.compareTo(new BigDecimal("1000"))>0
                ||buffer.scale()>4||markup.scale()>4)
            throw new IllegalArgumentException("CUR_R2A_POLICY_VALUES_INVALID");
    }
    private Policy policy(UUID tenant,UUID id) {
        Policy row=jdbc.query("""
            SELECT offer_id,revision,anchor_currency,anchor_amount,buffer_pct,
                   markup_pct,rounding_step_kzt
            FROM crm.sku_fx_policy_version WHERE tenant_id=? AND id=?
            """,rs->rs.next()?new Policy(id,tenant,rs.getObject(1,UUID.class),rs.getLong(2),
                rs.getString(3).trim(),rs.getBigDecimal(4),rs.getBigDecimal(5),
                rs.getBigDecimal(6),rs.getBigDecimal(7)):null,tenant,id);
        if(row==null)throw new SecurityException("CUR_R2A_POLICY_NOT_ACCESSIBLE");
        return row;
    }

    private long latestGuardrailRevision(UUID tenant,UUID offer) {
        Long value=jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision),0) FROM crm.sku_fx_guardrail_version
            WHERE tenant_id=? AND offer_id=?
            """,Long.class,tenant,offer);
        return value==null?0:value;
    }

    private Guardrail latestGuardrail(UUID tenant,UUID offer) {
        return jdbc.query("""
            SELECT id,revision,max_increase_pct,max_decrease_pct,min_price_kzt,
                   max_price_kzt,max_rate_calendar_days,calculation_ttl_seconds
            FROM crm.sku_fx_guardrail_version WHERE tenant_id=? AND offer_id=?
            ORDER BY revision DESC LIMIT 1
            """,rs->rs.next()?new Guardrail(rs.getObject(1,UUID.class),tenant,offer,
                rs.getLong(2),rs.getBigDecimal(3),rs.getBigDecimal(4),
                rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getInt(7),rs.getInt(8))
                :null,tenant,offer);
    }

    private UUID verifyGuardrails(UUID tenant,Calculation calc,Rate rate) {
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Almaty")));
        UUID calendarSourceId=calendarEvidence.requireAdmittedCalendar(tenant,rate.currency(),rate.date(),today);
        Guardrail guard=latestGuardrail(tenant,calc.offer());
        if(guard==null) {
            if(calc.guardrailId()!=null)
                throw new IllegalStateException("CUR_R2B_GUARDRAIL_STALE");
            if(calendarSourceId!=null)
                throw new IllegalStateException("CUR_R2B_GUARDRAIL_REQUIRED");
            return null; // legacy R2A tenants remain gated by CUR-R1C
        }
        if(!guard.id().equals(calc.guardrailId())
                ||guard.revision()!=calc.guardrailRevision())
            throw new IllegalStateException("CUR_R2B_GUARDRAIL_STALE");
        Instant calculated=jdbc.queryForObject("""
            SELECT calculated_at FROM crm.sku_fx_calculation
            WHERE tenant_id=? AND id=?
            """,Timestamp.class,tenant,calc.id()).toInstant();
        if(!clock.instant().isBefore(calculated.plusSeconds(guard.calculationTtlSeconds())))
            throw new IllegalStateException("CUR_R2B_CALCULATION_EXPIRED");
        long age=ChronoUnit.DAYS.between(rate.date(),today);
        if(age<0||age>guard.maxRateCalendarDays())
            throw new IllegalStateException("CUR_R2B_RATE_TOO_OLD");
        if(calc.suggestedKzt().compareTo(guard.minPriceKzt())<0
                ||calc.suggestedKzt().compareTo(guard.maxPriceKzt())>0)
            throw new IllegalStateException("CUR_R2B_PRICE_OUTSIDE_BOUNDS");
        BigDecimal delta=calc.suggestedKzt().subtract(calc.currentKzt());
        BigDecimal tolerance=(delta.signum()>=0
                ?guard.maxIncreasePct():guard.maxDecreasePct());
        if(delta.abs().multiply(new BigDecimal("100"))
                .compareTo(calc.currentKzt().multiply(tolerance))>0)
            throw new IllegalStateException("CUR_R2B_PRICE_DELTA_EXCEEDED");
        return calendarSourceId;
    }

    private long latestPolicyRevision(UUID tenant,UUID offer) {
        Long n=jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision),0) FROM crm.sku_fx_policy_version
            WHERE tenant_id=? AND offer_id=?
            """,Long.class,tenant,offer);
        return n==null?0:n;
    }
    private record Rate(UUID id,UUID importRunId,String currency,BigDecimal nominal,
                        BigDecimal value,LocalDate date,int revision,String semanticSha,String rawSha) {}
    private Rate rate(UUID id,String currency) {
        Rate row=jdbc.query("""
            SELECT s.id,s.source_import_run_id,s.base_currency,s.nominal,s.rate,
                   s.valid_date,s.source_revision,s.source_payload_sha256,
                   r.raw_payload_sha256
            FROM crm.fx_official_rate_snapshot s
            JOIN crm.fx_rate_import_run r ON r.id=s.source_import_run_id
            WHERE s.id=? AND s.provider_code='NBRK' AND s.synthetic=FALSE
              AND r.provider_code='NBRK' AND r.synthetic=FALSE
              AND s.quote_currency='KZT' AND s.base_currency=?
            """,rs->rs.next()?new Rate(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getString(3).trim(),rs.getBigDecimal(4),
                rs.getBigDecimal(5),rs.getDate(6).toLocalDate(),rs.getInt(7),
                rs.getString(8).trim(),rs.getString(9).trim()):null,id,currency);
        if(row==null)throw new IllegalStateException("CUR_R2A_TRUSTED_RATE_REQUIRED");
        return row;
    }
    private int latestRateRevision(Rate rate) {
        Integer value=jdbc.queryForObject("""
            SELECT MAX(source_revision) FROM crm.fx_official_rate_snapshot
            WHERE provider_code='NBRK' AND base_currency=?
              AND quote_currency='KZT' AND valid_date=?
            """,Integer.class,rate.currency(),Date.valueOf(rate.date()));
        return value==null?0:value;
    }
    private Calculation calculation(UUID tenant,UUID id) {
        Calculation row=jdbc.query("""
            SELECT offer_id,policy_id,policy_revision,rate_snapshot_id,
                   offer_row_version,current_price_kzt,candidate_price_kzt,request_sha256,
                   guardrail_id,guardrail_revision
            FROM crm.sku_fx_calculation WHERE tenant_id=? AND id=?
            """,rs->rs.next()?new Calculation(id,tenant,rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getLong(3),rs.getObject(4,UUID.class),
                rs.getLong(5),rs.getBigDecimal(6),rs.getBigDecimal(7),
                rs.getString(8).trim(),rs.getObject(9,UUID.class),rs.getLong(10)):null,tenant,id);
        if(row==null)throw new SecurityException("CUR_R2A_CALC_NOT_ACCESSIBLE");
        return row;
    }
    private Decision decision(UUID tenant,UUID calculation) {
        return jdbc.query("""
            SELECT id,action,price_revision_id FROM crm.sku_fx_seller_decision
            WHERE tenant_id=? AND calculation_id=?
            """,rs->rs.next()?new Decision(rs.getObject(1,UUID.class),calculation,
                rs.getString(2),rs.getObject(3,UUID.class)):null,tenant,calculation);
    }
    private static String sha(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
