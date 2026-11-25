package kz.rm.iam;

import kz.rm.intelligence.*;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

final class IntelligenceMetricQualification {
    static MetricQuery order(MetricRegistry.Code c,MetricSelection.PeriodChoice period){
        return new MetricQuery(c,1,new MetricSelection.OrderPeriod(period));
    }
    static MetricQuery movement(MetricRegistry.Code c,MetricSelection.PeriodChoice p,UUID warehouse,UUID sku){
        return new MetricQuery(c,1,new MetricSelection.MovementPeriod(p,warehouse,sku));
    }
    static void assertValue(MetricResult r,String expected,DataQuality quality){
        assertThat(r.quality()).isEqualTo(quality);
        assertThat(r.cells()).hasSize(1);
        assertThat(r.cells().getFirst().value()).isEqualByComparingTo(expected);
        assertThat(r.coverage().complete()).isEqualTo(quality==DataQuality.READY);
    }
    static void allEight(MetricQueryService service,VerifiedActor staff,UUID tenant,UUID warehouse,UUID sku,MetricSelection.PeriodChoice p){
        var c=MetricRegistry.Code.values();
        String[] expected={"2","375","2","1","1","10","3","7"};
        for(int n=0;n<8;n++){
            var q=n<5?order(c[n],p):n<7?movement(c[n],p,warehouse,sku):new MetricQuery(c[n],1,new MetricSelection.LivePosition(warehouse,sku));
            var r=service.calculate(staff,tenant,q);
            assertValue(r,expected[n],n==7?DataQuality.READY:DataQuality.PARTIAL);
            if(n==1)assertThat(r.cells().getFirst().currency()).isEqualTo("KZT");
        }
    }
}
