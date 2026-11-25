package kz.rm.intelligence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Null value explicitly means unavailable; provisional observed values are never READY. */
public record MetricResult(MetricRegistry.Code code,int methodVersion,MetricRegistry.Basis basis,
    Instant evaluatedAt,PeriodResolver.Period period,DataQuality quality,List<Cell> cells,List<String> reasons,Coverage coverage) {
    public record Cell(BigDecimal value,String currency,UUID warehouseId,UUID skuId) {}
    public MetricResult {
        Objects.requireNonNull(code);Objects.requireNonNull(basis);Objects.requireNonNull(evaluatedAt);Objects.requireNonNull(quality);
        Objects.requireNonNull(coverage);
        if(quality==DataQuality.READY&&!coverage.complete())throw new IllegalArgumentException("READY requires complete coverage");
        cells=List.copyOf(cells);reasons=List.copyOf(reasons);
        if(quality==DataQuality.READY && (cells.isEmpty() || cells.stream().anyMatch(c->c.value()==null)))
            throw new IllegalArgumentException("READY requires values");
    }
}
