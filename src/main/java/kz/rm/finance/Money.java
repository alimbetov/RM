package kz.rm.finance;
import java.math.BigDecimal;
import java.util.Currency;
/** Never add monetary amounts in different currencies. */
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        if(amount==null||currency==null)throw new IllegalArgumentException("Money requires currency");
    }
    public Money add(Money other) {
        if(other==null||!currency.equals(other.currency))throw new IllegalArgumentException("Currency mismatch");
        return new Money(amount.add(other.amount),currency);
    }
}
