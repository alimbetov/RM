package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.BusinessFactorAssessment.*;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.Metric.*;

class BusinessFactorQualificationTest {
    private static final UUID T=new UUID(0,1),A=new UUID(0,2),SKU=new UUID(0,3),W=new UUID(0,4);
    private static final Instant NOW=Instant.parse("2026-06-01T00:00:00Z");
    private static final BusinessInterpretationEngine.Policy POLICY=new BusinessInterpretationEngine.Policy(1,new BigDecimal("5"),new BigDecimal("0.05"));
    private static List<BusinessInterpretationEngine.Point> pair(AuthorizedAnalyticalReadService.Metric metric,
            UUID tenant,UUID warehouse,long revision,DataQuality quality,String before,String after){
        var scope=new BusinessInterpretationEngine.Scope(tenant,A,metric,1,PeriodResolver.Code.D,null,null,SKU,List.of(warehouse),null,
            metric==FULFILLED_PRODUCT_UNITS?"PRODUCT_UNITS":"SKU_QUANTITY",1);
        var calendar=new PeriodResolver.Policy(ZoneId.of("Etc/UTC"),1);
        var values=Arrays.asList(before,after);var points=new ArrayList<BusinessInterpretationEngine.Point>();
        for(int i=0;i<2;i++){
            var p=PeriodResolver.resolve(PeriodResolver.Code.D,LocalDate.of(2026,1,1).plusDays(i),calendar);
            var e=new AuthorizedAnalyticalReadService.Result(metric,1,p,values.get(i)==null?null:new BigDecimal(values.get(i)),
                quality,AuthorizedAnalyticalReadService.Route.L0,"FIXTURE","FIXTURE",scope.unit(),null,null,null,revision,NOW);
            points.add(new BusinessInterpretationEngine.Point(scope,e));
        }
        return List.copyOf(points);
    }
    private static List<BusinessInterpretationEngine.Point> pair(AuthorizedAnalyticalReadService.Metric metric,String a,String b){
        return pair(metric,T,W,7,DataQuality.READY,a,b);
    }
    private static Result check(List<BusinessInterpretationEngine.Point> sales,List<BusinessInterpretationEngine.Point> in,
                               List<BusinessInterpretationEngine.Point> out){
        var r=evaluate(sales,in,out,POLICY);assertThat(r.decisionEligibility()).isEqualTo("BLOCKED");return r;
    }
    @Test void bs065StockFlowsDoNotIdentifyAvailability(){
        var r=check(pair(FULFILLED_PRODUCT_UNITS,"100","60"),pair(STOCK_IN,"100","0"),pair(STOCK_OUT,"100","60"));
        assertThat(r.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(r.reason()).isEqualTo(Reason.FLOW_IS_NOT_AVAILABILITY);
        assertThat(r.fulfilledDelta()).isEqualByComparingTo("-40");
        assertThat(r.stockInDelta()).isEqualByComparingTo("-100");
        assertThat(r.stockOutDelta()).isEqualByComparingTo("-40");
        assertThat(r.hypotheses()).containsExactly(Hypothesis.DEMAND_DECLINE,Hypothesis.AVAILABILITY_CONSTRAINT,Hypothesis.OTHER_FACTORS);
        assertThat(r.checks()).contains(Check.VERIFY_STOCK_AVAILABILITY,Check.VERIFY_UNFULFILLED_DEMAND);
    }
    @Test void bs098DifferentFlowDirectionIsNotCausalConflict(){
        var r=check(pair(FULFILLED_PRODUCT_UNITS,"100","60"),pair(STOCK_IN,"10","200"),pair(STOCK_OUT,"20","30"));
        assertThat(r.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(r.stockInDelta()).isEqualByComparingTo("190");
        assertThat(r.stockOutDelta()).isEqualByComparingTo("10");
    }
    @Test void bs068PartialAndMissingNeverPromoted(){
        for(var q:List.of(DataQuality.PARTIAL,DataQuality.MISSING,DataQuality.STALE)){
            var r=check(pair(FULFILLED_PRODUCT_UNITS,"100","60"),pair(STOCK_IN,T,W,7,q,null,null),pair(STOCK_OUT,"100","60"));
            assertThat(r.status()).isEqualTo(Status.INSUFFICIENT_DATA);
            assertThat(r.fulfilledDelta()).isNull();assertThat(r.hypotheses()).isEmpty();
        }
    }
    @Test void bs106ForeignTenantOrWarehouseRejected(){
        for(var in:List.of(pair(STOCK_IN,A,W,7,DataQuality.READY,"10","20"),
            pair(STOCK_IN,T,A,7,DataQuality.READY,"10","20"))){
            assertThat(check(pair(FULFILLED_PRODUCT_UNITS,"100","60"),in,pair(STOCK_OUT,"100","60")).status()).isEqualTo(Status.CONFLICTING_EVIDENCE);
        }
    }
    @Test void bs108RevisionMismatchInvalidatesFactorEvidence(){
        var r=check(pair(FULFILLED_PRODUCT_UNITS,"100","60"),pair(STOCK_IN,T,W,8,DataQuality.READY,"10","20"),pair(STOCK_OUT,"100","60"));
        assertThat(r.status()).isEqualTo(Status.CONFLICTING_EVIDENCE);
        assertThat(r.checks()).containsExactly(Check.VERIFY_SOURCE_REVISION);
    }
    @Test void bs112StableSalesNoUnsupportedRecommendation(){
        var r=check(pair(FULFILLED_PRODUCT_UNITS,"100","101"),pair(STOCK_IN,"10","200"),pair(STOCK_OUT,"20","30"));
        assertThat(r.status()).isEqualTo(Status.NO_MATERIAL_CHANGE);
        assertThat(r.hypotheses()).isEmpty();assertThat(r.checks()).isEmpty();
    }
    @Test void growthDoesNotIdentifyPromotionOrDemand(){
        var r=check(pair(FULFILLED_PRODUCT_UNITS,"100","140"),pair(STOCK_IN,"10","200"),pair(STOCK_OUT,"20","30"));
        assertThat(r.status()).isEqualTo(Status.OBSERVED_CHANGE);
        assertThat(r.reason()).isEqualTo(Reason.CAUSE_NOT_ESTABLISHED);
        assertThat(r.hypotheses()).isEmpty();
    }
}
