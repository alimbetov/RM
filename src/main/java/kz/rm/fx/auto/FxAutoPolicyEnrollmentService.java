package kz.rm.fx.auto;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.*;

/**
 * D.2 inert PRODUCT draft authoring. PostgreSQL only. No consent or price writes.
 * Account/workspace -> currency policy -> storefront. Currency policy UPDATE
 * serializes authoring across processes for a tenant, including request replay.
 */
@Service
public class FxAutoPolicyEnrollmentService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final FxAutoOwnerConsentService consent;

    public FxAutoPolicyEnrollmentService(JdbcTemplate jdbc, TenantAuthorization authorization,
                                         FxAutoOwnerConsentService consent) {
        this.jdbc=jdbc; this.authorization=authorization; this.consent=consent;
    }

    public record Limits(long rollingSeconds, BigDecimal increasePct, BigDecimal decreasePct,
                         BigDecimal variationKzt, long publications, long tenantWindowSeconds,
                         BigDecimal tenantDeltaKzt, long tenantCount,
                         BigDecimal runDeltaKzt, int runCount, int maxTargets) {
        public Limits {
            increasePct=decimal(increasePct,8); decreasePct=decimal(decreasePct,8);
            variationKzt=decimal(variationKzt,19); tenantDeltaKzt=decimal(tenantDeltaKzt,19);
            runDeltaKzt=decimal(runDeltaKzt,19);
            if(rollingSeconds<=0 || tenantWindowSeconds<=0 || publications<0 || tenantCount<0 ||
                    runCount<0 || maxTargets<1 || maxTargets>10000 || runCount>maxTargets ||
                    decreasePct.compareTo(BigDecimal.valueOf(100))>0)
                throw new IllegalArgumentException("FX_AUTO_INVALID_LIMITS");
        }
    }
    public enum TaxBasis { TAX_INCLUDED, TAX_EXCLUDED, NONE }
    public record Target(UUID offerId, UUID anchorId, UUID guardrailId, Instant reviewDueAt,
                         String unitCode, TaxBasis taxBasis, UUID commercialContextId) {
        public Target(UUID offerId,UUID anchorId,UUID guardrailId,Instant reviewDueAt,String unitCode,TaxBasis taxBasis){
            this(offerId,anchorId,guardrailId,reviewDueAt,unitCode,taxBasis,null);
        }
        public Target {
            Objects.requireNonNull(offerId); Objects.requireNonNull(anchorId);
            Objects.requireNonNull(guardrailId); Objects.requireNonNull(reviewDueAt);
            Objects.requireNonNull(taxBasis);
            if(unitCode==null || !unitCode.matches("[A-Z0-9_]{1,64}") ||
                    !reviewDueAt.equals(reviewDueAt.truncatedTo(ChronoUnit.MICROS)))
                throw new IllegalArgumentException("FX_AUTO_INVALID_BINDING");
        }
    }
    public record Command(UUID policyId,long expectedRevision,String requestKey,Limits limits,List<Target> targets) {
        public Command {
            Objects.requireNonNull(policyId); Objects.requireNonNull(limits);
            if(expectedRevision<0 || expectedRevision==Long.MAX_VALUE || requestKey==null ||
                    !requestKey.matches("[A-Za-z0-9_.:-]{1,100}") || targets==null ||
                    targets.isEmpty() || targets.size()>limits.maxTargets())
                throw new IllegalArgumentException("FX_AUTO_INVALID_COMMAND");
            targets=targets.stream().sorted(Comparator.comparing(t->t.offerId().toString())).toList();
            if(targets.stream().map(Target::offerId).distinct().count()!=targets.size())
                throw new IllegalArgumentException("FX_AUTO_DUPLICATE_TARGET");
        }
    }
    public record Receipt(UUID policyId,UUID revisionId,long revision,String payloadSha256,int targetCount) {}
    private record Existing(UUID id,UUID policy,long revision,String sha) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt createPolicyRevision(VerifiedActor actor,UUID tenant,Command command) {
        if(actor==null || tenant==null) throw new SecurityException("FX_AUTO_VERIFIED_ACTOR_REQUIRED");
        Objects.requireNonNull(command);
        // Fail closed on H2 or caller-provided incompatible outer transaction.
        if(!"PostgreSQL".equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                c->c.getMetaData().getDatabaseProductName())) ||
                !"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)))
            throw new IllegalStateException("FX_AUTO_POSTGRES_READ_COMMITTED_REQUIRED");
        UUID user=actor.userId();
        authorization.requireForCommand(user,tenant,DomainPermission.TENANT_RBAC_MANAGE);
        authorization.requireForCommand(user,tenant,DomainPermission.STOREFRONT_OFFER_MANAGE);
        var currencies=new TreeSet<String>();
        for(Target t:command.targets()) {
            String currency=jdbc.query("""
                    SELECT anchor_currency FROM crm.sku_fx_policy_version
                    WHERE tenant_id=? AND offer_id=? AND id=?
                    """,rs->rs.next()?rs.getString(1):null,tenant,t.offerId(),t.anchorId());
            if(currency==null) throw new SecurityException("FX_AUTO_TARGET_BINDING_UNAVAILABLE");
            currencies.add(currency.trim());
        }
        // Acquires tenant currency UPDATE lock, held until commit.
        consent.requireProductConsentAuthority(actor,tenant,currencies);
        UUID storefront=jdbc.query("SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE",
                rs->rs.next()?rs.getObject(1,UUID.class):null,tenant);
        if(storefront==null) throw new SecurityException("FX_AUTO_TARGET_BINDING_UNAVAILABLE");
        for(Target t:command.targets()){
            if(t.commercialContextId()!=null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM crm.store_offer_commercial_context c
                WHERE c.tenant_id=? AND c.offer_id=? AND c.id=? AND c.unit_code=? AND c.tax_basis=?
                  AND c.revision=(SELECT max(x.revision) FROM crm.store_offer_commercial_context x
                    WHERE x.tenant_id=c.tenant_id AND x.offer_id=c.offer_id))
                """,Boolean.class,tenant,t.offerId(),t.commercialContextId(),t.unitCode(),t.taxBasis().name())))
                throw new IllegalArgumentException("FX_AUTO_COMMERCIAL_CONTEXT_STALE");
        }
        String payload=payload(user,command);
        if(payload.length()>1000000) throw new IllegalArgumentException("FX_AUTO_PAYLOAD_TOO_LARGE");
        String hash=jdbc.queryForObject("SELECT crm.fx_auto_sha_v1(CAST(? AS jsonb))",String.class,payload);
        // Tenant-wide request identity scoped to the verified actor.
        Existing old=jdbc.query("""
                SELECT id,policy_id,revision,payload_sha256 FROM crm.fx_auto_policy_revision
                WHERE tenant_id=? AND CAST(payload AS jsonb)->>'author'=? AND CAST(payload AS jsonb)->>'requestKey'=?
                """,rs->{
                    if(!rs.next())return null;
                    var e=new Existing(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getString(4).trim());
                    if(rs.next())throw new IllegalStateException("FX_AUTO_DUPLICATE_REQUEST_EVIDENCE");
                    return e;
                },tenant,user.toString(),command.requestKey());
        actor.userId();
        if(old!=null) {
            if(!hash.equals(old.sha())) throw new IllegalStateException("FX_AUTO_IDEMPOTENCY_CONFLICT");
            return new Receipt(old.policy(),old.id(),old.revision(),old.sha(),command.targets().size());
        }
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        for(Target t:command.targets()) {
            // Exact latest owner bindings, PRODUCT only, positive KZT offer.
            Integer ok=jdbc.queryForObject("""
                    SELECT count(*) FROM crm.store_offer o
                    JOIN crm.cat_item c ON c.tenant_id=o.tenant_id AND c.id=o.catalog_item_id
                    JOIN crm.sku_fx_policy_version a ON a.tenant_id=o.tenant_id AND a.offer_id=o.id
                    JOIN crm.sku_fx_guardrail_version g ON g.tenant_id=o.tenant_id AND g.offer_id=o.id
                    WHERE o.tenant_id=? AND o.id=? AND a.id=? AND g.id=?
                    AND o.currency='KZT' AND o.unit_price>0 AND o.state='PUBLISHED'
                    AND c.kind='PRODUCT' AND c.state='PUBLISHED'
                    AND a.revision=(SELECT max(x.revision) FROM crm.sku_fx_policy_version x WHERE x.tenant_id=o.tenant_id AND x.offer_id=o.id)
                    AND g.revision=(SELECT max(x.revision) FROM crm.sku_fx_guardrail_version x WHERE x.tenant_id=o.tenant_id AND x.offer_id=o.id)
                    """,Integer.class,tenant,t.offerId(),t.anchorId(),t.guardrailId());
            if(ok==null || ok!=1 || !t.reviewDueAt().isAfter(now))
                throw new IllegalArgumentException("FX_AUTO_TARGET_BINDING_INVALID");
        }
        Long head=jdbc.queryForObject("SELECT coalesce(max(revision),0) FROM crm.fx_auto_policy_revision WHERE tenant_id=? AND policy_id=?",
                Long.class,tenant,command.policyId());
        if(head==null || head!=command.expectedRevision()) throw new IllegalStateException("FX_AUTO_STALE_POLICY_REVISION");
        UUID limit=UUID.randomUUID(),revisionId=UUID.randomUUID();
        long revision=head+1; Limits l=command.limits();
        actor.userId();
        jdbc.update("""
                INSERT INTO crm.fx_auto_limit_policy_version
                (tenant_id,id,rolling_seconds,max_rolling_increase_pct,max_rolling_decrease_pct,max_rolling_variation_kzt,
                max_auto_publications,tenant_window_seconds,tenant_delta_cap_kzt,tenant_count_cap,run_delta_cap_kzt,run_count_cap,max_targets)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,limit,l.rollingSeconds(),l.increasePct(),l.decreasePct(),l.variationKzt(),l.publications(),
                l.tenantWindowSeconds(),l.tenantDeltaKzt(),l.tenantCount(),l.runDeltaKzt(),l.runCount(),l.maxTargets());
        jdbc.update("""
                INSERT INTO crm.fx_auto_policy_revision(tenant_id,id,policy_id,revision,limit_id,payload,payload_sha256,created_at)
                VALUES (?,?,?,?,?,?,?,clock_timestamp())
                """,tenant,revisionId,command.policyId(),revision,limit,payload,hash);
        for(Target t:command.targets()) jdbc.update("""
                INSERT INTO crm.fx_auto_target_enrollment(tenant_id,policy_revision_id,offer_id,anchor_id,guardrail_id,anchor_review_due_at,unit_code,tax_basis)
                VALUES (?,?,?,?,?,?,?,?)
                """,tenant,revisionId,t.offerId(),t.anchorId(),t.guardrailId(),Timestamp.from(t.reviewDueAt()),t.unitCode(),t.taxBasis().name());
        actor.userId();
        return new Receipt(command.policyId(),revisionId,revision,hash,command.targets().size());
    }

    private static BigDecimal decimal(BigDecimal n,int precision) {
        if(n==null || n.signum()<0)throw new IllegalArgumentException("FX_AUTO_INVALID_LIMITS");
        n=n.stripTrailingZeros();
        if(n.scale()>4 || (long)n.precision()-n.scale()>precision-4)
            throw new IllegalArgumentException("FX_AUTO_INVALID_LIMITS");
        return n;
    }

    // All interpolated strings are restricted enum/UUID/ASCII tokens or ISO instants.
    // Unit/tax are declared draft context, not yet authoritative commercial evidence.
    private static String payload(UUID actor,Command c) {
        Limits l=c.limits();
        StringJoiner targets=new StringJoiner(",","[","]");
        for(Target t:c.targets()) targets.add("""
                {"offer":"%s","anchor":"%s","guardrail":"%s","reviewDue":"%s","unit":"%s","tax":"%s"}""".formatted(
                t.offerId(),t.anchorId(),t.guardrailId(),t.reviewDueAt(),t.unitCode(),t.taxBasis())
                .replace("}",t.commercialContextId()==null?"}":",\"commercialContextId\":\""+t.commercialContextId()+"\"}"));
        return """
                {"schema":"PRODUCT_DRAFT_V1","author":"%s","policy":"%s","expectedRevision":%d,"requestKey":"%s",
                "includeFuture":false,"source":"NBRK","formula":"SKU_FX_R2A_V1","purpose":"PRICING_ANCHOR",
                "limits":{"rollingSeconds":%d,"increasePct":"%s","decreasePct":"%s","variationKzt":"%s","publications":%d,
                "tenantWindowSeconds":%d,"tenantDeltaKzt":"%s","tenantCount":%d,"runDeltaKzt":"%s","runCount":%d,"maxTargets":%d},
                "targets":%s}""".formatted(actor,c.policyId(),c.expectedRevision(),c.requestKey(),
                l.rollingSeconds(),l.increasePct().toPlainString(),l.decreasePct().toPlainString(),l.variationKzt().toPlainString(),
                l.publications(),l.tenantWindowSeconds(),l.tenantDeltaKzt().toPlainString(),l.tenantCount(),
                l.runDeltaKzt().toPlainString(),l.runCount(),l.maxTargets(),targets);
    }
}
