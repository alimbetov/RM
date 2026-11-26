package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.BusinessInterpretationEngine.*;

class BusinessInterpretationQualificationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SKU=UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID WAREHOUSE=UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW=Instant.parse("2026-01-05T00:00:00Z");
    private static final Policy POLICY=new Policy(1,new BigDecimal("5"),new BigDecimal("0.05"));
    private static Scope scope(UUID tenant){
        return new Scope(tenant,ACTOR,AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS,1,
            PeriodResolver.Code.D,null,null,SKU,List.of(WAREHOUSE),null,"PRODUCT_UNITS",1);
    }
    private static Point point(Scope scope,int day,String value,DataQuality quality,long revision){
        var period=new PeriodResolver.Period(Instant.parse("2026-01-0"+day+"T00:00:00Z"),
            Instant.parse("2026-01-0"+(day+1)+"T00:00:00Z"),"Etc/UTC",1);
        return new Point(scope,new AuthorizedAnalyticalReadService.Result(scope.metric(),1,period,
            value==null?null:new BigDecimal(value),quality,AuthorizedAnalyticalReadService.Route.L0,"FIXTURE","FIXTURE",
            scope.unit(),null,null,null,revision,NOW));
    }
    private static Point point(int day,String value,DataQuality quality){return point(scope(TENANT),day,value,quality,7);}
    private static Outcome outcome(Point... points){
        var o=evaluate(List.of(points),POLICY);
        assertThat(o.decisionEligibility()).isEqualTo(DecisionEligibility.BLOCKED);
        assertThat(o.sustainedTrend()).isFalse();
        return o;
    }
    @Test void bs065DeclineCannotIdentifyCause(){
        var o=outcome(point(1,"100",DataQuality.READY),point(2,"60",DataQuality.READY));
        assertThat(o.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(o.delta()).isEqualByComparingTo("-40");
        assertThat(o.growth()).isEqualByComparingTo("-0.4");
        assertThat(o.hypotheses()).containsExactly(Hypothesis.DEMAND_DECLINE,Hypothesis.STOCKOUT_CONSTRAINT);
    }
    @Test void bs093ZeroIsNotMissing(){
        var zero=outcome(point(1,"10",DataQuality.READY),point(2,"0",DataQuality.READY));
        assertThat(zero.delta()).isEqualByComparingTo("-10");
        var missing=outcome(point(1,"10",DataQuality.READY),point(2,null,DataQuality.MISSING));
        assertThat(missing.delta()).isNull();assertThat(missing.reason()).isEqualTo(Reason.MISSING_OBSERVATION);
        var growth=outcome(point(1,"0",DataQuality.READY),point(2,"10",DataQuality.READY));
        assertThat(growth.delta()).isEqualByComparingTo("10");assertThat(growth.growth()).isNull();
    }
    @Test void bs095OpenAndNonAdjacentPeriodsRejected(){
        assertThat(outcome(point(4,"100",DataQuality.READY),point(5,"60",DataQuality.PARTIAL)).reason()).isEqualTo(Reason.OPEN_PERIOD);
        assertThat(outcome(point(1,"100",DataQuality.READY),point(3,"60",DataQuality.READY)).status()).isEqualTo(Status.CONFLICTING_EVIDENCE);
    }
    @Test void bs097ShortAndPartialHistory(){
        assertThat(outcome(point(1,"100",DataQuality.READY)).reason()).isEqualTo(Reason.INSUFFICIENT_HISTORY);
        var partial=outcome(point(1,"100",DataQuality.PARTIAL),point(2,"60",DataQuality.PARTIAL));
        assertThat(partial.quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(partial.status()).isEqualTo(Status.INSUFFICIENT_DATA);
        assertThat(partial.delta()).isEqualByComparingTo("-40");assertThat(partial.hypotheses()).isEmpty();
    }
    @Test void bs099MatchedSymptomsAbstain(){
        // Independent latent labels never enter the production input.
        var observed=List.of(point(1,"100",DataQuality.READY),point(2,"60",DataQuality.READY));
        var hiddenCauses=List.of("demand decline","stockout");
        for(String hidden:hiddenCauses){
            assertThat(hidden).isNotBlank();
            var actual=evaluate(observed,POLICY);
            assertThat(actual.status()).isEqualTo(Status.AMBIGUOUS);
            assertThat(actual.hypotheses()).hasSize(2);
        }
    }
    @Test void bs112MaterialityBoundary(){
        assertThat(outcome(point(1,"100",DataQuality.READY),point(2,"96",DataQuality.READY)).status()).isEqualTo(Status.NO_MATERIAL_CHANGE);
        assertThat(outcome(point(1,"100",DataQuality.READY),point(2,"95",DataQuality.READY)).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(outcome(point(1,"0",DataQuality.READY),point(2,"0",DataQuality.READY)).status()).isEqualTo(Status.NO_MATERIAL_CHANGE);
        assertThat(outcome(point(1,"100",DataQuality.PARTIAL),point(2,"100",DataQuality.PARTIAL)).status()).isEqualTo(Status.INSUFFICIENT_DATA);
    }
    @Test void incompatibleTenantAndRevisionFailClosed(){
        var a=point(1,"100",DataQuality.READY);
        assertThat(outcome(a,point(scope(ACTOR),2,"60",DataQuality.READY,7)).status()).isEqualTo(Status.CONFLICTING_EVIDENCE);
        assertThat(outcome(a,point(scope(TENANT),2,"60",DataQuality.READY,8)).delta()).isNull();
    }
}
