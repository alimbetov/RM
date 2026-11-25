package kz.rm.fx.auto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static kz.rm.fx.auto.FxAutoRollingLimitEvaluator.*;

/** Offline simulation of precomputed candidates. Not an admission or publication service. */
public final class FxAutoDryRun {
    private FxAutoDryRun() {}
    public record Budget(BigDecimal remainingTenantDelta, long remainingTenantCount,
                         BigDecimal runDeltaCap, long runCountCap, int maxTargets) {
        public Budget {
            Objects.requireNonNull(remainingTenantDelta);
            Objects.requireNonNull(runDeltaCap);
            if (remainingTenantDelta.signum()<0 || runDeltaCap.signum()<0
                    || remainingTenantCount<0 || runCountCap<0 || maxTargets<1 || maxTargets>10000)
                throw new IllegalArgumentException("FX_AUTO_INVALID_BUDGET");
        }
    }
    public record Item(FxAutoTargetRef target, BigDecimal current, BigDecimal candidate,
                       BigDecimal baseline, List<Change> history, Limits limits) {
        public Item {
            Objects.requireNonNull(target);
            Objects.requireNonNull(current);
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(limits);
            history=List.copyOf(history);
        }
    }
    public record Snapshot(UUID tenant, String currency, UUID sourceSnapshotId,
                           long sourceRevision, Instant evaluationAsOf,
                           Budget budget, List<Item> items) {
        public Snapshot {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(sourceSnapshotId);
            Objects.requireNonNull(evaluationAsOf);
            Objects.requireNonNull(budget);
            if (!Set.of("USD","EUR","CNY").contains(currency) || sourceRevision<1)
                throw new IllegalArgumentException("FX_AUTO_INVALID_SOURCE");
            items=List.copyOf(items);
            if(items.size()>budget.maxTargets()) throw new IllegalArgumentException("LIMIT_EXCEEDED");
            Set<FxAutoTargetRef> seen=new HashSet<>();
            for(Item item:items) {
                if(!tenant.equals(item.target().tenantId())) throw new SecurityException("FX_AUTO_SCOPE_INVALID");
                if(!seen.add(item.target())) throw new IllegalArgumentException("FX_AUTO_DUPLICATE_TARGET");
            }
        }
    }
    public enum Outcome { WOULD_UPDATE, NO_CHANGE, BLOCKED_PRICE_LIMIT, BLOCKED_ROLLING_LIMIT,
        BASELINE_UNAVAILABLE, BLOCKED_UNSUPPORTED_TARGET, BLOCKED_BUDGET_EXHAUSTED }
    public record Row(FxAutoTargetRef target, Outcome outcome, BigDecimal virtualDelta) {}
    public record Report(List<Row> rows, BigDecimal virtualConsumption, long virtualPublications) {
        public Report { rows=List.copyOf(rows); }
    }
    public static Report simulate(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var ordered=snapshot.items().stream().sorted(Comparator
                .comparing((Item i)->i.target().targetType().name())
                .thenComparing(i->i.target().ownerType().name())
                .thenComparing(i->i.target().canonicalKey())).toList();
        BigDecimal spent=BigDecimal.ZERO;
        long count=0;
        List<Row> rows=new ArrayList<>();
        for(Item item:ordered) {
            Outcome outcome;
            BigDecimal charge=BigDecimal.ZERO;
            if(item.target().targetType()!=FxAutoTargetRef.TargetType.PRODUCT_OFFER) {
                outcome=Outcome.BLOCKED_UNSUPPORTED_TARGET;
            } else {
                var result=evaluate(item.current(),item.candidate(),item.baseline(),item.history(),item.limits());
                outcome=Outcome.valueOf(result.decision().name());
                if(outcome==Outcome.WOULD_UPDATE) {
                    var next=spent.add(result.candidateDelta().abs());
                    var b=snapshot.budget();
                    if(next.compareTo(b.remainingTenantDelta())>0 || next.compareTo(b.runDeltaCap())>0
                            || count>=b.remainingTenantCount() || count>=b.runCountCap()) {
                        outcome=Outcome.BLOCKED_BUDGET_EXHAUSTED;
                    } else {
                        charge=result.candidateDelta().abs();
                        spent=next;
                        count++;
                    }
                }
            }
            rows.add(new Row(item.target(),outcome,charge));
        }
        return new Report(rows,spent,count);
    }
}
