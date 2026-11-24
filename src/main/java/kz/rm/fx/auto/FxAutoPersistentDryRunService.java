package kz.rm.fx.auto;

import kz.rm.fx.NbrkRateSnapshotReader;
import kz.rm.fx.SkuFxCandidateCalculator;
import kz.rm.fx.FxCalendarEvidenceService;
import kz.rm.iam.VerifiedActor;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.DomainPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** Persistent diagnostic simulation. No commercial writes or live execution authority.
 * Until canonical unit/tax context and history qualification are available, positive
 * evaluator decisions are explicitly withheld. */
@Service
public class FxAutoPersistentDryRunService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final FxAutoOwnerConsentService consent;
    private final NbrkRateSnapshotReader rates;
    private final SkuFxCandidateCalculator calculator;
    private final FxCalendarEvidenceService calendar;
    public FxAutoPersistentDryRunService(JdbcTemplate jdbc,TenantAuthorization authorization,
            FxAutoOwnerConsentService consent,NbrkRateSnapshotReader rates,
            SkuFxCandidateCalculator calculator,FxCalendarEvidenceService calendar) {
        this.jdbc=jdbc;this.authorization=authorization;this.consent=consent;
        this.rates=rates;this.calculator=calculator;this.calendar=calendar;
    }
    public record Command(UUID policyId,UUID approvalId,long expectedEpoch,String currency,
                          LocalDate validDate,UUID sourceSnapshotId,String requestKey) {
        public Command {
            Objects.requireNonNull(policyId);Objects.requireNonNull(approvalId);
            Objects.requireNonNull(validDate);Objects.requireNonNull(sourceSnapshotId);
            if(expectedEpoch<0 || currency==null || !Set.of("USD","EUR","CNY").contains(currency)
                || requestKey==null || !requestKey.matches("[A-Za-z0-9_.:-]{1,100}"))
                throw new IllegalArgumentException("FX_AUTO_SIM_COMMAND_INVALID");
        }
    }
    public record Receipt(UUID simulationId,String resultSha256,int itemCount) {}
    private record Approval(UUID revision,UUID author,UUID membership,Instant expiry,String scope,String state,long epoch) {}
    private record History(UUID id,long version,Instant at,BigDecimal oldPrice,BigDecimal newPrice,String oldCurrency,String newCurrency) {}
    private record Window(BigDecimal baseline,List<FxAutoRollingLimitEvaluator.Change> changes,List<UUID> evidence) {}
    private record PolicyLimits(long seconds,BigDecimal increase,BigDecimal decrease,BigDecimal variation,long publications, FxAutoDryRun.Budget budget) {}
    private record Target(UUID offer,UUID anchor,UUID guard,BigDecimal current,UUID priceRevision,
                          BigDecimal anchorAmount,BigDecimal buffer,BigDecimal markup,BigDecimal step,
                          Instant reviewDue,boolean eligible,int maxAge,BigDecimal increase,BigDecimal decrease,BigDecimal min,BigDecimal max) {}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt simulate(VerifiedActor actor,UUID tenant,Command c) {
        if(actor==null || tenant==null)throw new SecurityException("FX_AUTO_VERIFIED_ACTOR_REQUIRED");
        Objects.requireNonNull(c);
        if(!"PostgreSQL".equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                x->x.getMetaData().getDatabaseProductName())) ||
                !"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)))
            throw new IllegalStateException("FX_AUTO_POSTGRES_READ_COMMITTED_REQUIRED");
        UUID user=actor.userId();
        authorization.requireForCommand(user,tenant,DomainPermission.TENANT_RBAC_MANAGE);
        authorization.requireForCommand(user,tenant,DomainPermission.STOREFRONT_OFFER_MANAGE);
        // Align source/currency/storefront lock order with legacy FX seller commands.
        if(jdbc.query("SELECT provider_code FROM crm.fx_rate_provider_lock WHERE provider_code='NBRK' FOR SHARE",
                rs->rs.next()?rs.getString(1):null)==null)throw new IllegalStateException("FX_AUTO_SOURCE_LOCK_MISSING");
        consent.requireProductConsentAuthority(actor,tenant,List.of(c.currency()));
        if(jdbc.query("SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE",
                rs->rs.next()?rs.getObject(1,UUID.class):null,tenant)==null)
            throw new SecurityException("FX_AUTO_STOREFRONT_UNAVAILABLE");
        Approval a=jdbc.query("""
            SELECT p.policy_revision_id,e.owner_user_id,e.membership_id,e.expires_at,trim(e.scope_sha256),p.state,p.execution_epoch
            FROM crm.fx_auto_policy_activation p JOIN crm.fx_auto_approval_evidence e
              ON e.tenant_id=p.tenant_id AND e.id=p.approval_id AND e.policy_revision_id=p.policy_revision_id
            JOIN public.iam_workspace w ON w.id=p.tenant_id
            JOIN public.iam_org_membership m ON m.id=e.membership_id AND m.organization_id=w.organization_id
              AND m.user_id=e.owner_user_id AND m.role='OWNER' AND m.status='ACTIVE'
            WHERE p.tenant_id=? AND p.policy_id=? AND p.approval_id=? AND e.owner_user_id=?
              AND trim(e.scope_sha256)=crm.fx_auto_scope_sha_v1(e.tenant_id,e.policy_revision_id)
            FOR UPDATE OF p
            """,rs->rs.next()?new Approval(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant(),rs.getString(5),rs.getString(6),rs.getLong(7)):null,
                tenant,c.policyId(),c.approvalId(),user);
        if(a==null || !"PAUSED".equals(a.state()) || a.epoch()!=c.expectedEpoch())
            throw new SecurityException("FX_AUTO_AUTHORITY_HOLD");
        FxAutoCommercialBinding.requireCurrent(jdbc,tenant,a.revision(),false);
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        if(!a.expiry().isAfter(now))throw new SecurityException("FX_AUTO_CONSENT_EXPIRED");
        String request="""
            {"schema":"PRODUCT_DIAGNOSTIC_V1","actor":"%s","policy":"%s","approval":"%s","epoch":%d,
             "currency":"%s","date":"%s","source":"%s","scope":"%s"}""".formatted(user,c.policyId(),c.approvalId(),
                c.expectedEpoch(),c.currency(),c.validDate(),c.sourceSnapshotId(),a.scope());
        String hash=jdbc.queryForObject("SELECT crm.fx_auto_sha_v1(CAST(? AS jsonb))",String.class,request);
        Receipt replay=jdbc.query("""
            SELECT s.id,trim(s.input_sha256),trim(z.result_sha256),s.item_count
            FROM crm.fx_auto_simulation s LEFT JOIN crm.fx_auto_simulation_seal z ON z.tenant_id=s.tenant_id AND z.simulation_id=s.id
            WHERE s.tenant_id=? AND s.request_key=?
            """,rs->{
                if(!rs.next())return null;
                if(!hash.equals(rs.getString(2)))throw new IllegalStateException("FX_AUTO_IDEMPOTENCY_CONFLICT");
                if(rs.getString(3)==null)throw new IllegalStateException("FX_AUTO_SIM_UNSEALED");
                return new Receipt(rs.getObject(1,UUID.class),rs.getString(3),rs.getInt(4));
            },tenant,c.requestKey());
        actor.userId();
        if(replay!=null)return replay;
        var rate=rates.latest(c.currency(),c.validDate()).filter(x->x.snapshotId().equals(c.sourceSnapshotId()))
                .orElseThrow(()->new IllegalArgumentException("FX_AUTO_SOURCE_NOT_ADMITTED"));
        LocalDate today=now.atZone(ZoneId.of("Asia/Almaty")).toLocalDate();
        if(c.validDate().isAfter(today))throw new IllegalArgumentException("FX_AUTO_FUTURE_RATE");
        UUID calendarId=calendar.requireAdmittedCalendar(tenant,c.currency(),c.validDate(),today);
        List<Target> targets=jdbc.query("""
            SELECT o.id,a.id,g.id,o.unit_price,h.id,a.anchor_amount,a.buffer_pct,a.markup_pct,a.rounding_step_kzt,
              e.anchor_review_due_at,
              (o.state='PUBLISHED' AND o.currency='KZT' AND o.unit_price>0 AND i.kind='PRODUCT' AND i.state='PUBLISHED'
               AND h.new_price=o.unit_price AND h.new_currency=o.currency
               AND a.revision=(SELECT max(revision) FROM crm.sku_fx_policy_version WHERE tenant_id=o.tenant_id AND offer_id=o.id)
               AND g.revision=(SELECT max(revision) FROM crm.sku_fx_guardrail_version WHERE tenant_id=o.tenant_id AND offer_id=o.id)),
              g.max_rate_calendar_days,g.max_increase_pct,g.max_decrease_pct,g.min_price_kzt,g.max_price_kzt
            FROM crm.fx_auto_target_enrollment e
            JOIN crm.store_offer o ON o.tenant_id=e.tenant_id AND o.id=e.offer_id
            JOIN crm.cat_item i ON i.tenant_id=o.tenant_id AND i.id=o.catalog_item_id
            JOIN crm.sku_fx_policy_version a ON a.tenant_id=e.tenant_id AND a.id=e.anchor_id AND a.offer_id=e.offer_id
            JOIN crm.sku_fx_guardrail_version g ON g.tenant_id=e.tenant_id AND g.id=e.guardrail_id AND g.offer_id=e.offer_id
            LEFT JOIN LATERAL (SELECT id,new_price,new_currency FROM crm.store_offer_price_revision
              WHERE tenant_id=o.tenant_id AND offer_id=o.id ORDER BY offer_row_version DESC LIMIT 1) h ON true
            WHERE e.tenant_id=? AND e.policy_revision_id=? AND a.anchor_currency=? ORDER BY o.id
            """,(rs,n)->new Target(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getBigDecimal(4),rs.getObject(5,UUID.class),rs.getBigDecimal(6),rs.getBigDecimal(7),
                rs.getBigDecimal(8),rs.getBigDecimal(9),rs.getTimestamp(10).toInstant(),rs.getBoolean(11),rs.getInt(12),rs.getBigDecimal(13),rs.getBigDecimal(14),rs.getBigDecimal(15),rs.getBigDecimal(16)),
                tenant,a.revision(),c.currency());
        if(targets.isEmpty())throw new IllegalArgumentException("FX_AUTO_EMPTY_CURRENCY_SCOPE");
        if(targets.stream().anyMatch(t->t.priceRevision()==null))
            throw new IllegalStateException("FX_AUTO_CANONICAL_PRICE_REVISION_REQUIRED");
        PolicyLimits limits=jdbc.query("""
            SELECT l.rolling_seconds,l.max_rolling_increase_pct,l.max_rolling_decrease_pct,
                   l.max_rolling_variation_kzt,l.max_auto_publications,l.tenant_delta_cap_kzt,
                   l.tenant_count_cap,l.run_delta_cap_kzt,l.run_count_cap,l.max_targets
            FROM crm.fx_auto_policy_revision p JOIN crm.fx_auto_limit_policy_version l
              ON l.tenant_id=p.tenant_id AND l.id=p.limit_id
            WHERE p.tenant_id=? AND p.id=?
            """,rs->rs.next()?new PolicyLimits(rs.getLong(1),rs.getBigDecimal(2),rs.getBigDecimal(3),
                rs.getBigDecimal(4),rs.getLong(5),new FxAutoDryRun.Budget(rs.getBigDecimal(6),
                    rs.getLong(7),rs.getBigDecimal(8),rs.getLong(9),rs.getInt(10))):null,tenant,a.revision());
        if(limits==null)throw new IllegalStateException("FX_AUTO_LIMITS_UNAVAILABLE");
        // No AUTO writer exists in this release. Budget is a hypothetical scenario,
        // not a durable tenant ledger or a promise of spendable capacity.
        Map<UUID,BigDecimal> candidates=new HashMap<>();
        Map<UUID,Window> windows=new HashMap<>();
        List<FxAutoDryRun.Item> items=new ArrayList<>();
        for(Target t:targets){
            BigDecimal candidate=calculator.preview(c.currency(),t.anchorAmount(),t.buffer(),t.markup(),t.step(),rate).suggestedKzt();
            candidates.put(t.offer(),candidate);
            Window window=history(tenant,t,now,limits.seconds());
            windows.put(t.offer(),window);
            if(calendarId!=null && java.time.temporal.ChronoUnit.DAYS.between(c.validDate(),today)<=t.maxAge()
                    && t.eligible() && t.reviewDue().isAfter(now) && window.baseline()!=null){
                var guard=new FxAutoRollingLimitEvaluator.Limits(t.increase(),t.decrease(),t.min(),t.max(),
                    limits.increase(),limits.decrease(),limits.variation(),limits.publications());
                items.add(new FxAutoDryRun.Item(new FxAutoTargetRef(tenant,FxAutoTargetRef.TargetType.PRODUCT_OFFER,
                    FxAutoTargetRef.OwnerType.STOREFRONT,t.offer().toString()),t.current(),candidate,window.baseline(),window.changes(),guard));
            }
        }
        var report=FxAutoDryRun.simulate(new FxAutoDryRun.Snapshot(tenant,c.currency(),rate.snapshotId(),
            rate.revision(),now,limits.budget(),items));
        Map<UUID,FxAutoDryRun.Row> hypothetical=new HashMap<>();
        for(var row:report.rows())hypothetical.put(UUID.fromString(row.target().canonicalKey()),row);
        UUID simulation=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.fx_auto_simulation(tenant_id,id,policy_revision_id,approval_id,source_snapshot_id,
              request_key,evaluation_as_of,input_payload,input_sha256,item_count) VALUES (?,?,?,?,?,?,?,?,?,?)
            """,tenant,simulation,a.revision(),c.approvalId(),rate.snapshotId(),c.requestKey(),Timestamp.from(now),request,hash,targets.size());
        for(Target t:targets){
            BigDecimal candidate=candidates.get(t.offer());
            Window window=windows.get(t.offer());
            FxAutoDryRun.Row evaluated=hypothetical.get(t.offer());
            String outcome; String reason;
            if(calendarId==null || java.time.temporal.ChronoUnit.DAYS.between(c.validDate(),today)>t.maxAge()){
                outcome="SOURCE_NOT_ADMITTED";reason="CALENDAR_OR_RATE_AGE";
            }else if(!t.eligible() || !t.reviewDue().isAfter(now)){
                outcome="BLOCKED_POLICY_CONFLICT";reason="STALE_TARGET_CONTEXT";
            }else if(window.baseline()==null){
                outcome="BASELINE_UNAVAILABLE";reason="CANONICAL_HISTORY_UNPROVEN";
            }else if(evaluated!=null && evaluated.outcome()!=FxAutoDryRun.Outcome.WOULD_UPDATE && evaluated.outcome()!=FxAutoDryRun.Outcome.NO_CHANGE){
                outcome=evaluated.outcome().name();reason="HYPOTHETICAL_LIMIT_REJECTION";
            }else{
                outcome="BLOCKED_POLICY_CONFLICT";reason="CANONICAL_UNIT_TAX_AND_BUDGET_NOT_QUALIFIED";
            }
            String input="""
                {"schema":"PRODUCT_DIAGNOSTIC_V1","offer":"%s","anchor":"%s","guardrail":"%s","priceRevision":"%s",
                 "currentKzt":"%s","candidateKzt":"%s","rate":"%s","nominal":"%s","sourceRevision":%d,
                 "calendar":"%s","reason":"%s","baselineKzt":"%s","historyIds":"%s",
                 "hypotheticalOutcome":"%s","hypotheticalDeltaKzt":"%s"}""".formatted(t.offer(),t.anchor(),t.guard(),t.priceRevision(),
                    t.current().toPlainString(),candidate.toPlainString(),rate.rateKzt().toPlainString(),
                    rate.nominal().toPlainString(),rate.revision(),calendarId==null?"NONE":calendarId.toString(),reason,
                    window.baseline()==null?"NONE":window.baseline().toPlainString(),window.evidence(),
                    evaluated==null?"NOT_EVALUATED":evaluated.outcome().name(),
                    evaluated==null?"0":evaluated.virtualDelta().toPlainString());
            jdbc.update("INSERT INTO crm.fx_auto_simulation_item VALUES (?,?,?,?,?,?,?,?)",
                tenant,simulation,a.revision(),t.offer(),t.priceRevision(),input,outcome,BigDecimal.ZERO);
        }
        String seal=jdbc.queryForObject("SELECT crm.fx_auto_sim_sha_v1(?,?)",String.class,tenant,simulation);
        jdbc.update("INSERT INTO crm.fx_auto_simulation_seal VALUES (?,?,?)",tenant,simulation,seal);
        actor.userId();
        return new Receipt(simulation,seal,targets.size());
    }

    /** Conservative history oracle: require a recorded boundary and an unbroken
     * KZT chain from initial revision through the current canonical price. */
    private Window history(UUID tenant,Target t,Instant now,long seconds){
        Window missing=new Window(null,List.of(),List.of());
        Instant boundary;
        try{boundary=now.minusSeconds(seconds);}catch(DateTimeException|ArithmeticException e){return missing;}
        List<History> rows=jdbc.query("""
            SELECT id,offer_row_version,recorded_at,old_price,new_price,trim(old_currency),trim(new_currency)
            FROM crm.store_offer_price_revision WHERE tenant_id=? AND offer_id=?
            ORDER BY offer_row_version LIMIT 10001
            """,(rs,n)->new History(rs.getObject(1,UUID.class),rs.getLong(2),rs.getTimestamp(3).toInstant(),
                rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getString(6),rs.getString(7)),tenant,t.offer());
        if(rows.isEmpty() || rows.size()>10000)return missing;
        BigDecimal baseline=null,previous=null;
        Instant prior=null;
        List<FxAutoRollingLimitEvaluator.Change> changes=new ArrayList<>();
        List<UUID> evidence=new ArrayList<>();
        for(History h:rows){
            if(h.at().isAfter(now) || (prior!=null && h.at().isBefore(prior))
                || !"KZT".equals(h.newCurrency()) || h.newPrice().signum()<=0)return missing;
            if(previous==null){
                if(h.version()!=0 || h.oldPrice()!=null || h.oldCurrency()!=null)return missing;
            }else{
                if(h.oldPrice()==null || h.oldPrice().compareTo(previous)!=0 || !"KZT".equals(h.oldCurrency()))return missing;
                if(h.at().isAfter(boundary))changes.add(new FxAutoRollingLimitEvaluator.Change(previous,h.newPrice(),false));
            }
            if(!h.at().isAfter(boundary))baseline=h.newPrice();
            previous=h.newPrice();prior=h.at();evidence.add(h.id());
        }
        if(!rows.getLast().id().equals(t.priceRevision()) || previous.compareTo(t.current())!=0 || baseline==null)return missing;
        return new Window(baseline,List.copyOf(changes),List.copyOf(evidence));
    }
}
