package kz.rm.intelligence;

import java.math.BigDecimal;
import java.util.Objects;

/** Candidate parity policy; exact values are never compared by floating point. */
final class DerivedNumericParity {
    private DerivedNumericParity(){}
    static final int VERSION=1;
    private static final BigDecimal RELATIVE=new BigDecimal("1E-14");
    private static final BigDecimal ABSOLUTE=new BigDecimal("1E-18");
    static boolean equivalent(DerivedMetricEngine.Operator operator,BigDecimal javaValue,BigDecimal sqlValue){
        Objects.requireNonNull(operator);
        if(javaValue==null||sqlValue==null)return javaValue==null&&sqlValue==null;
        return switch(operator){
            case MIN,MAX,PERIOD_CHANGE -> javaValue.compareTo(sqlValue)==0;
            default -> javaValue.subtract(sqlValue).abs().compareTo(
                ABSOLUTE.max(javaValue.abs().max(sqlValue.abs()).multiply(RELATIVE)))<=0;
        };
    }
}
