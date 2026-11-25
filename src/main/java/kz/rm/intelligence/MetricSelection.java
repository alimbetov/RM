package kz.rm.intelligence;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public sealed interface MetricSelection {
    record PeriodChoice(PeriodResolver.Code code,LocalDate anchor,long calendarVersion) {
        public PeriodChoice {
            Objects.requireNonNull(code);Objects.requireNonNull(anchor);
            if(calendarVersion<1 || anchor.getYear()<1 || anchor.getYear()>9998)
                throw new IllegalArgumentException("Invalid period choice");
        }
    }
    record OrderPeriod(PeriodChoice period) implements MetricSelection {
        public OrderPeriod {Objects.requireNonNull(period);}
    }
    record MovementPeriod(PeriodChoice period,UUID warehouseId,UUID skuId) implements MetricSelection {
        public MovementPeriod {Objects.requireNonNull(period);Objects.requireNonNull(warehouseId);Objects.requireNonNull(skuId);}
    }
    record LivePosition(UUID warehouseId,UUID skuId) implements MetricSelection {
        public LivePosition {Objects.requireNonNull(warehouseId);Objects.requireNonNull(skuId);}
    }
    default MetricRegistry.Selection kind(){
        return switch(this){
            case OrderPeriod ignored -> MetricRegistry.Selection.ORDER_PERIOD;
            case MovementPeriod ignored -> MetricRegistry.Selection.MOVEMENT_PERIOD;
            case LivePosition ignored -> MetricRegistry.Selection.LIVE_POSITION;
        };
    }
}
