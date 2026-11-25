package kz.rm.fx;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SkuFxCandidateCalculatorTest {
    private final SkuFxCandidateCalculator calc=new SkuFxCandidateCalculator();

    private static NbrkRateSnapshotReader.Revision rate(String code,String nominal,String quote) {
        return new NbrkRateSnapshotReader.Revision(
                UUID.randomUUID(),UUID.randomUUID(),code,new BigDecimal(nominal),
                new BigDecimal(quote),LocalDate.of(2026,11,23),1,
                "a".repeat(64),"b".repeat(64));
    }
    private SkuFxCandidateCalculator.Candidate preview(String code,String amount,
            String buffer,String markup,String step,NbrkRateSnapshotReader.Revision rate) {
        return calc.preview(code,new BigDecimal(amount),new BigDecimal(buffer),
                new BigDecimal(markup),new BigDecimal(step),rate);
    }

    @Test void fxSku01UsdAnchorWithMarkup() {
        var result=preview("USD","100","0","25","1",rate("USD","1","500"));
        assertThat(result.suggestedKzt()).isEqualByComparingTo("62500");
        assertThat(result.convertedKzt()).isEqualByComparingTo("50000");
    }

    @Test void fxSku02CnyNominalTenIsDividedNotMultiplied() {
        var result=preview("CNY","200","0","0","1",rate("CNY","10","700"));
        assertThat(result.suggestedKzt()).isEqualByComparingTo("14000");
    }

    @Test void fxSku03BufferThenMarkupAndCeiling() {
        var result=preview("USD","1","10","20","5",rate("USD","1","101"));
        assertThat(result.unroundedKzt()).isEqualByComparingTo("133.32");
        assertThat(result.suggestedKzt()).isEqualByComparingTo("135");
    }

    @Test void fxSku04TinyPositiveAmountAlwaysRoundsUp() {
        var result=preview("USD","0.0001","0","0","1",rate("USD","1","0.1"));
        assertThat(result.suggestedKzt()).isEqualByComparingTo("1");
    }

    @Test void fxSku05WrongAnchorCurrencyRefused() {
        assertThatThrownBy(()->preview("EUR","1","0","0","1",rate("USD","1","500")))
                .hasMessage("CUR_R2_RATE_PROVENANCE_INVALID");
    }

    @Test void fxSku06NullOrZeroAmountsAndNominalFailClosed() {
        assertThatThrownBy(()->preview("USD","0","0","0","1",rate("USD","1","500")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->preview("USD","1","0","0","1",rate("USD","0","500")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->preview("USD","1","0","0","0",rate("USD","1","500")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fxSku07ExcessiveRateAndInputOverflowRejected() {
        assertThatThrownBy(()->preview("USD","999999999999999.9999","0","0",
                "1",rate("USD","1","9999999999999999")))
                .hasMessage("CUR_R2_CANDIDATE_OVERFLOW");
    }

    @Test void fxSku08InvalidGuardrailsRejected() {
        assertThatThrownBy(()->preview("USD","1","-1","0","1",rate("USD","1","500")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->preview("USD","1","0","1001","1",rate("USD","1","500")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->preview("USD","1","0","0","0.00001",rate("USD","1","500")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fxSku09SameInputsReturnSameCandidateButNoBusinessWrites() {
        var r=rate("USD","1","505.5");
        var one=preview("USD","3","2","5","1",r);
        var two=preview("USD","3","2","5","1",r);
        assertThat(one).isEqualTo(two);
        assertThat(one.rateSnapshotId()).isEqualTo(r.snapshotId());
    }
}
