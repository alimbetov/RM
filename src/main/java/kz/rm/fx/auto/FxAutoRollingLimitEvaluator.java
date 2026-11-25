package kz.rm.fx.auto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * R2C.0 pure, fail-closed mathematical oracle. No authorization, clock, database,
 * candidate calculation, or publication. Caller MUST supply a verified canonical
 * history under a future transactional owner/budget lock.
 */
public final class FxAutoRollingLimitEvaluator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private FxAutoRollingLimitEvaluator() {}

    public enum Decision { WOULD_UPDATE, NO_CHANGE, BLOCKED_PRICE_LIMIT,
        BLOCKED_ROLLING_LIMIT, BASELINE_UNAVAILABLE }

    public record Limits(BigDecimal maxStepIncreasePct, BigDecimal maxStepDecreasePct,
                         BigDecimal minPriceKzt, BigDecimal maxPriceKzt,
                         BigDecimal maxRollingIncreasePct, BigDecimal maxRollingDecreasePct,
                         BigDecimal maxRollingAbsoluteVariationKzt, long maxAutoPublications) {
        public Limits {
            positiveOrZero(maxStepIncreasePct); positiveOrZero(maxStepDecreasePct);
            positiveOrZero(maxRollingIncreasePct); positiveOrZero(maxRollingDecreasePct);
            positiveOrZero(maxRollingAbsoluteVariationKzt);
            positive(minPriceKzt); positive(maxPriceKzt);
            if (minPriceKzt.compareTo(maxPriceKzt) > 0 || maxAutoPublications < 0)
                throw new IllegalArgumentException("FX_AUTO_INVALID_LIMITS");
        }
    }

    /** Immutable canonical change, including MANUAL changes in the rolling window. */
    public record Change(BigDecimal oldPrice, BigDecimal newPrice, boolean auto) {
        public Change { positive(oldPrice); positive(newPrice); }
    }
    public record Result(Decision decision, BigDecimal candidateDelta,
                         BigDecimal projectedVariation, long projectedAutoPublications) {}

    public static Result evaluate(BigDecimal current, BigDecimal candidate,
                                  BigDecimal baseline, List<Change> windowChanges,
                                  Limits limits) {
        positive(current); positive(candidate);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(windowChanges, "windowChanges");
        BigDecimal delta = candidate.subtract(current);
        BigDecimal variation = delta.abs();
        long publications = 1;
        for (Change change : windowChanges) {
            Objects.requireNonNull(change, "change");
            variation = variation.add(change.newPrice().subtract(change.oldPrice()).abs());
            if (change.auto()) publications = Math.addExact(publications, 1);
        }
        if (candidate.compareTo(current) == 0)
            return new Result(Decision.NO_CHANGE, delta, variation.subtract(delta.abs()), publications - 1);
        if (baseline == null || baseline.signum() <= 0)
            return new Result(Decision.BASELINE_UNAVAILABLE, delta, variation, publications);
        if (candidate.compareTo(limits.minPriceKzt()) < 0
                || candidate.compareTo(limits.maxPriceKzt()) > 0
                || exceedsPct(delta.max(BigDecimal.ZERO), current, limits.maxStepIncreasePct())
                || exceedsPct(delta.negate().max(BigDecimal.ZERO), current, limits.maxStepDecreasePct()))
            return new Result(Decision.BLOCKED_PRICE_LIMIT, delta, variation, publications);
        BigDecimal net = candidate.subtract(baseline);
        if (exceedsPct(net.max(BigDecimal.ZERO), baseline, limits.maxRollingIncreasePct())
                || exceedsPct(net.negate().max(BigDecimal.ZERO), baseline, limits.maxRollingDecreasePct())
                || variation.compareTo(limits.maxRollingAbsoluteVariationKzt()) > 0
                || publications > limits.maxAutoPublications())
            return new Result(Decision.BLOCKED_ROLLING_LIMIT, delta, variation, publications);
        return new Result(Decision.WOULD_UPDATE, delta, variation, publications);
    }

    /** Exact cross multiplication; never round a percentage before comparing. */
    private static boolean exceedsPct(BigDecimal delta, BigDecimal base, BigDecimal cap) {
        return delta.multiply(HUNDRED).compareTo(base.multiply(cap)) > 0;
    }
    private static void positive(BigDecimal n) {
        if (n == null || n.signum() <= 0) throw new IllegalArgumentException("FX_AUTO_POSITIVE_REQUIRED");
    }
    private static void positiveOrZero(BigDecimal n) {
        if (n == null || n.signum() < 0) throw new IllegalArgumentException("FX_AUTO_NONNEGATIVE_REQUIRED");
    }
}
