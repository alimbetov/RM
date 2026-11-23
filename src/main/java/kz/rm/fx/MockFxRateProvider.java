package kz.rm.fx;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Explicitly synthetic fixtures. No HTTP, no scheduler, no public price writes.
 * Fixed dates/rates avoid accidentally representing a real NBRK quote.
 */
@Component
@Profile({"dev", "test"})
public final class MockFxRateProvider implements FxRateProvider {
    private static final LocalDate FIRST = LocalDate.of(2026, 11, 20);
    private static final LocalDate SECOND = LocalDate.of(2026, 11, 21);
    private static final List<Rate> FIXTURES = List.of(
            new Rate("USD","KZT",new BigDecimal("500.00"),BigDecimal.ONE,FIRST,"MOCK"),
            new Rate("CNY","KZT",new BigDecimal("700.00"),new BigDecimal("10"),FIRST,"MOCK"),
            new Rate("EUR","KZT",new BigDecimal("550.00"),BigDecimal.ONE,FIRST,"MOCK"),
            new Rate("USD","KZT",new BigDecimal("505.00"),BigDecimal.ONE,SECOND,"MOCK"),
            new Rate("CNY","KZT",new BigDecimal("715.00"),new BigDecimal("10"),SECOND,"MOCK"),
            new Rate("EUR","KZT",new BigDecimal("555.00"),BigDecimal.ONE,SECOND,"MOCK")
    );

    @Override public String code() { return "MOCK"; }

    @Override public List<Rate> fetch(LocalDate effectiveDate) {
        if (effectiveDate == null)
            throw new IllegalArgumentException("Effective FX date required");
        return FIXTURES.stream().filter(rate->!rate.validDate().isAfter(effectiveDate))
                .collect(java.util.stream.Collectors.toMap(Rate::currency,
                        rate->rate,(a,b)->a.validDate().isAfter(b.validDate())?a:b,
                        java.util.TreeMap::new))
                .values().stream().toList();
    }
}
