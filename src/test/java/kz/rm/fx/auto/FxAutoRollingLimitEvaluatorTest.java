package kz.rm.fx.auto;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.fx.auto.FxAutoRollingLimitEvaluator.*;

class FxAutoRollingLimitEvaluatorTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static Limits limits(String rollingIncrease, String variation, long publications) {
        return new Limits(d("100"), d("100"), d("1"), d("999999"),
                d(rollingIncrease), d("100"), d(variation), publications);
    }

    @Test void secondTenPercentStepCannotEscapeFifteenPercentRollingCeiling() {
        var result = evaluate(d("110"), d("121"), d("100"),
                List.of(new Change(d("100"), d("110"), true)), limits("15","100",10));
        assertThat(result.decision()).isEqualTo(Decision.BLOCKED_ROLLING_LIMIT);
    }

    @Test void revertingPriceCannotEraseAbsoluteVariation() {
        var result = evaluate(d("110"), d("100"), d("100"),
                List.of(new Change(d("100"), d("110"), true)), limits("100","15",10));
        assertThat(result.decision()).isEqualTo(Decision.BLOCKED_ROLLING_LIMIT);
        assertThat(result.projectedVariation()).isEqualByComparingTo("20");
    }

    @Test void manualHistoryCountsTowardVariationButNotAutoPublications() {
        var result = evaluate(d("105"), d("110"), d("100"),
                List.of(new Change(d("100"), d("105"), false)), limits("20","10",1));
        assertThat(result.decision()).isEqualTo(Decision.WOULD_UPDATE);
        assertThat(result.projectedAutoPublications()).isEqualTo(1);
    }

    @Test void exactBoundaryIsAllowedAndExceedingItIsBlocked() {
        assertThat(evaluate(d("100"), d("115"), d("100"), List.of(), limits("15","15",1))
                .decision()).isEqualTo(Decision.WOULD_UPDATE);
        assertThat(evaluate(d("100"), d("115.0000000001"), d("100"), List.of(),
                limits("15","100",2)).decision()).isEqualTo(Decision.BLOCKED_ROLLING_LIMIT);
    }

    @Test void missingBaselineFailsClosed() {
        assertThat(evaluate(d("100"), d("101"), null, List.of(), limits("15","100",2))
                .decision()).isEqualTo(Decision.BASELINE_UNAVAILABLE);
    }

    @Test void noChangeDoesNotConsumePublicationBudget() {
        var result = evaluate(d("100"), d("100"), null, List.of(), limits("0","0",0));
        assertThat(result.decision()).isEqualTo(Decision.NO_CHANGE);
        assertThat(result.projectedAutoPublications()).isZero();
    }

    @Test void priceLimitAndPublicationCountEnforced() {
        assertThat(evaluate(d("100"), d("150"), d("100"), List.of(),
                new Limits(d("20"),d("100"),d("1"),d("999999"),d("100"),d("100"),d("999"),10))
                .decision()).isEqualTo(Decision.BLOCKED_PRICE_LIMIT);
        assertThat(evaluate(d("100"), d("101"), d("100"), List.of(
                new Change(d("99"), d("100"),true)), limits("50","100",1))
                .decision()).isEqualTo(Decision.BLOCKED_ROLLING_LIMIT);
    }

    @Test void typedTargetsCannotSwapOwnersOrTenantlessKeys() {
        var tenant=java.util.UUID.randomUUID();
        assertThatThrownBy(()->new FxAutoTargetRef(tenant,FxAutoTargetRef.TargetType.PRODUCT_OFFER,
                FxAutoTargetRef.OwnerType.SALES_PACKAGING,java.util.UUID.randomUUID().toString()))
                .hasMessage("FX_AUTO_OWNER_TYPE_MISMATCH");
        assertThatThrownBy(()->new FxAutoTargetRef(tenant,FxAutoTargetRef.TargetType.PRODUCT_OFFER,
                FxAutoTargetRef.OwnerType.STOREFRONT,"SRV-PACK"))
                .hasMessage("FX_AUTO_PRODUCT_OFFER_KEY_INVALID");
    }
}
