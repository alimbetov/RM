package kz.rm.intelligence;
import java.util.Objects;
public record MetricQuery(MetricRegistry.Code code,int methodVersion,MetricSelection selection) {
    public MetricQuery {
        Objects.requireNonNull(selection);
        MetricRegistry.require(code,methodVersion,selection.kind());
    }
}
