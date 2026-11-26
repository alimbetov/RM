package kz.rm.intelligence;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.util.*;

/** Internal pure computation, after authorized aggregation at the target grain.
 * Caller must supply qualified, point-in-time period values; not an admission boundary. */
final class DerivedMetricEngine {
    private DerivedMetricEngine() {}
    private static final MathContext MC = MathContext.DECIMAL128;
    static final int METHOD_VERSION = 1;
    static final int DEFAULT_WINDOW = 3;
    enum Operator { AVG, SMA, PERIOD_CHANGE, PERIOD_GROWTH, TREND, STDDEV_POP, CV, MIN, MAX, EMA, FORECAST_NEXT }
    record Scope(UUID tenant, UUID generation, String metric, int methodVersion,
                 String dimensionKey, String authorizationScopeKey, String unitKey, String currencyKey,
                 PeriodResolver.Code periodCode, PeriodResolver.Policy calendar, long sourceRevision) {
        Scope {
            Objects.requireNonNull(tenant); Objects.requireNonNull(generation);
            Objects.requireNonNull(periodCode); Objects.requireNonNull(calendar);
            for (String key : List.of(metric, dimensionKey, authorizationScopeKey, unitKey, currencyKey))
                if (key.isBlank()) throw new IllegalArgumentException("DERIVED_SCOPE_KEY");
            if (methodVersion < 1 || sourceRevision < 0) throw new IllegalArgumentException("DERIVED_VERSION");
        }
    }
    record Point(Scope scope, PeriodResolver.Period period, BigDecimal value, DataQuality quality,
                 Instant observedAt) {
        Point {
            Objects.requireNonNull(scope); Objects.requireNonNull(period);
            Objects.requireNonNull(quality); Objects.requireNonNull(observedAt);
            if (quality == DataQuality.READY && value == null)
                throw new IllegalArgumentException("DERIVED_READY_WITHOUT_VALUE");
        }
    }
    record Result(Operator operator, int methodVersion, int window, DataQuality quality,
                  BigDecimal value, String reason) {}
    static List<PeriodResolver.Period> closedWindow(Scope scope, LocalDate anchor, Instant asOf, int size) {
        Objects.requireNonNull(scope); Objects.requireNonNull(asOf);
        if (size < 2 || size > 120) throw new IllegalArgumentException("DERIVED_WINDOW_SIZE");
        var calendar = scope.calendar();
        var period = PeriodResolver.resolve(scope.periodCode(), anchor, calendar);
        if (period.endExclusive().isAfter(asOf))
            throw new IllegalArgumentException("DERIVED_OPEN_OR_FUTURE_PERIOD");
        var periods = new ArrayList<PeriodResolver.Period>();
        for (int i=0; i<size; i++) {
            periods.add(period);
            if (i+1<size) {
                LocalDate prior = period.startInclusive().atZone(calendar.zone()).toLocalDate().minusDays(1);
                period = PeriodResolver.resolve(scope.periodCode(), prior, calendar);
            }
        }
        Collections.reverse(periods);
        return List.copyOf(periods);
    }
    static Result calculate(Scope scope, Operator op, LocalDate anchor, Instant asOf, List<Point> input) {
        return calculate(scope, op, anchor, asOf, DEFAULT_WINDOW, input);
    }
    static Result calculate(Scope scope, Operator op, LocalDate anchor, Instant asOf, int size, List<Point> input) {
        Objects.requireNonNull(op); Objects.requireNonNull(input);
        var expected = closedWindow(scope, anchor, asOf, size);
        var byPeriod = new HashMap<PeriodResolver.Period,Point>();
        for (Point p : input) {
            if (!scope.equals(p.scope()) || !expected.contains(p.period()) ||
                p.observedAt().isAfter(asOf) || p.observedAt().isBefore(p.period().endExclusive()))
                throw new IllegalArgumentException("DERIVED_INCOMPATIBLE_POINT");
            if (byPeriod.putIfAbsent(p.period(), p)!=null)
                throw new IllegalArgumentException("DERIVED_DUPLICATE_PERIOD");
        }
        var values = new ArrayList<BigDecimal>();
        for (var period : expected) {
            Point p = byPeriod.get(period);
            if (p == null) return unavailable(op,size,DataQuality.MISSING,"INSUFFICIENT_HISTORY");
            if (p.quality()!=DataQuality.READY)
                return unavailable(op,size,p.quality(),"UNQUALIFIED_PERIOD");
            values.add(p.value());
        }
        BigDecimal n = BigDecimal.valueOf(size);
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(n,MC);
        BigDecimal last = values.get(size-1), prior = values.get(size-2);
        BigDecimal delta = last.subtract(prior);
        BigDecimal value;
        switch(op) {
            case AVG, SMA, FORECAST_NEXT -> value=mean;
            case EMA -> {
                BigDecimal alpha=BigDecimal.valueOf(2).divide(n.add(BigDecimal.ONE),MC);
                value=values.get(0);
                for(int i=1;i<size;i++) value=alpha.multiply(values.get(i),MC).add(BigDecimal.ONE.subtract(alpha).multiply(value,MC),MC);
            }
            case PERIOD_CHANGE -> value=delta;
            case PERIOD_GROWTH -> {
                if (prior.signum()<=0) return unavailable(op,size,DataQuality.NOT_MEASURABLE,"NONPOSITIVE_BASE");
                value=delta.divide(prior,MC);
            }
            case MIN -> value=Collections.min(values);
            case MAX -> value=Collections.max(values);
            case STDDEV_POP, CV -> {
                BigDecimal squares=BigDecimal.ZERO;
                for(var v:values) squares=squares.add(v.multiply(v));
                // Exact numerator avoids cancellation from prematurely rounded mean.
                BigDecimal variance=squares.multiply(n).subtract(sum.multiply(sum))
                    .divide(n.multiply(n),MC);
                BigDecimal sd=variance.sqrt(MC);
                if(op==Operator.CV && mean.signum()<=0)
                    return unavailable(op,size,DataQuality.NOT_MEASURABLE,"NONPOSITIVE_MEAN");
                value=op==Operator.CV?sd.divide(mean,MC):sd;
            }
            case TREND -> {
                // OLS slope against period ordinal, not elapsed days.
                BigDecimal sx=BigDecimal.ZERO, sxx=BigDecimal.ZERO, sxy=BigDecimal.ZERO;
                for(int i=0;i<size;i++){
                    BigDecimal x=BigDecimal.valueOf(i);
                    sx=sx.add(x); sxx=sxx.add(x.multiply(x)); sxy=sxy.add(x.multiply(values.get(i)));
                }
                value=n.multiply(sxy).subtract(sx.multiply(sum))
                    .divide(n.multiply(sxx).subtract(sx.multiply(sx)),MC);
            }
            default -> throw new IllegalArgumentException("DERIVED_OPERATOR");
        }
        return new Result(op,METHOD_VERSION,size,DataQuality.READY,value,null);
    }
    private static Result unavailable(Operator op,int size,DataQuality quality,String reason) {
        return new Result(op,METHOD_VERSION,size,quality,null,reason);
    }
}
