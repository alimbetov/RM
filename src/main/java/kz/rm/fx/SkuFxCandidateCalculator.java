package kz.rm.fx;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * CUR-R2/R0 pure preview mathematics. No price publication, DB writes,
 * offer mutation, stock valuation or sales-order changes.
 * NBRK official quote is KZT per nominal units of anchor currency.
 */
@Service
public final class SkuFxCandidateCalculator {
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED=new BigDecimal("100");

    public record Candidate(UUID rateSnapshotId, String anchorCurrency,
                            BigDecimal anchorAmount, BigDecimal nominal,
                            BigDecimal rateKzt, BigDecimal convertedKzt,
                            BigDecimal bufferedKzt, BigDecimal unroundedKzt,
                            BigDecimal suggestedKzt) {}

    public Candidate preview(String anchorCurrency,BigDecimal anchorAmount,
                             BigDecimal bufferPercent,BigDecimal markupPercent,
                             BigDecimal roundingStepKzt,
                             NbrkRateSnapshotReader.Revision rate) {
        Objects.requireNonNull(rate,"Official FX revision required");
        if(anchorCurrency==null || !anchorCurrency.matches("[A-Z]{3}")
                || "KZT".equals(anchorCurrency)
                || !anchorCurrency.equals(rate.currency())
                || rate.snapshotId()==null || rate.importRunId()==null
                || rate.validDate()==null || rate.revision()<1
                || rate.semanticSha256()==null || rate.rawPayloadSha256()==null)
            throw new IllegalArgumentException("CUR_R2_RATE_PROVENANCE_INVALID");
        requirePositive(anchorAmount,"anchor");
        requirePositive(rate.rateKzt(),"rate");
        requirePositive(rate.nominal(),"nominal");
        requirePositive(roundingStepKzt,"rounding step");
        requirePercent(bufferPercent,"buffer");
        requirePercent(markupPercent,"markup");
        if(anchorAmount.scale()>4 || anchorAmount.precision()>19
                || roundingStepKzt.scale()>4 || roundingStepKzt.precision()>19
                || roundingStepKzt.compareTo(new BigDecimal("1000000"))>0)
            throw new IllegalArgumentException("CUR_R2_INPUT_PRECISION_INVALID");

        BigDecimal converted=anchorAmount.multiply(rate.rateKzt(),MC)
                .divide(rate.nominal(),MC);
        BigDecimal buffered=converted.multiply(
                BigDecimal.ONE.add(bufferPercent.divide(HUNDRED,MC)),MC);
        BigDecimal unrounded=buffered.multiply(
                BigDecimal.ONE.add(markupPercent.divide(HUNDRED,MC)),MC);
        BigDecimal ticks=unrounded.divide(roundingStepKzt,0,RoundingMode.CEILING);
        BigDecimal suggested=ticks.multiply(roundingStepKzt);
        if(suggested.signum()<=0 || suggested.precision()>19 || suggested.scale()>4)
            throw new IllegalArgumentException("CUR_R2_CANDIDATE_OVERFLOW");
        return new Candidate(rate.snapshotId(),anchorCurrency,anchorAmount,
                rate.nominal(),rate.rateKzt(),converted,buffered,
                unrounded,suggested);
    }

    private static void requirePositive(BigDecimal value,String field) {
        if(value==null||value.signum()<=0)
            throw new IllegalArgumentException("CUR_R2_INVALID_"+field);
    }

    private static void requirePercent(BigDecimal value,String field) {
        if(value==null || value.signum()<0
                || value.compareTo(new BigDecimal("1000"))>0
                || value.scale()>4 || value.precision()>8)
            throw new IllegalArgumentException("CUR_R2_INVALID_"+field);
    }
}
