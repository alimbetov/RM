package kz.rm.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Exchange-rate provider boundary. MOCK is synthetic and is never official
 * NBRK evidence. Live NBRK/Kurs24 adapters require independent qualification.
 */
public interface FxRateProvider {
    String code();

    record Rate(String currency, String quoteCurrency, BigDecimal rateKzt,
                BigDecimal nominal, LocalDate validDate, String provider) {
        public Rate {
            if (currency == null || !currency.matches("[A-Z]{3}")
                    || !"KZT".equals(quoteCurrency) || rateKzt == null || nominal == null
                    || rateKzt.signum() <= 0 || nominal.signum() <= 0
                    || validDate == null || provider == null || provider.isBlank())
                throw new IllegalArgumentException("Invalid synthetic FX rate");
        }
    }

    List<Rate> fetch(LocalDate effectiveDate);
}
