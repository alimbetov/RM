package kz.rm.fx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FxRateRefreshMockQualificationTest {
    private final MockFxRateProvider mock=new MockFxRateProvider();

    @Test void fxMock01RatesAreSyntheticAndNominalIsPreserved() {
        var service=new FxRateRefreshService(List.of(mock),"MOCK",true);
        var result=service.simulateRefresh(LocalDate.of(2026,11,20));
        assertThat(result.source()).isEqualTo("MOCK");
        assertThat(result.synthetic()).isTrue();
        assertThat(result.rates()).hasSize(3);
        var cny=result.rates().stream().filter(r->r.currency().equals("CNY"))
                .findFirst().orElseThrow();
        assertThat(cny.rateKzt()).isEqualByComparingTo("700.00");
        assertThat(cny.nominal()).isEqualByComparingTo("10");
        assertThat(cny.rateKzt().divide(cny.nominal()))
                .isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test void fxMock02DeterministicRefreshAndValidDateSelection() {
        var service=new FxRateRefreshService(List.of(mock),"MOCK",true);
        var date=LocalDate.of(2026,11,21);
        var first=service.simulateRefresh(date);
        var replay=service.simulateRefresh(date);
        assertThat(replay).isEqualTo(first);
        assertThat(first.rates()).allMatch(rate->!rate.validDate().isAfter(date));
        assertThat(first.rates()).anySatisfy(rate->{
            if(rate.currency().equals("USD"))
                assertThat(rate.rateKzt()).isEqualByComparingTo("505");
        });
    }

    @Test void fxMock03NeverClaimsFutureProviderDate() {
        var service=new FxRateRefreshService(List.of(mock),"MOCK",true);
        assertThatThrownBy(()->service.simulateRefresh(LocalDate.of(2026,11,19)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FX_MOCK_RATE_NOT_FOUND");
    }

    @Test void fxMock04DefaultAndProductionModesAreDisabled() {
        assertThatThrownBy(()->new FxRateRefreshService(List.of(mock),"DISABLED",true)
                .simulateRefresh(LocalDate.of(2026,11,21)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FX_RATE_REFRESH_DISABLED");
        assertThatThrownBy(()->new FxRateRefreshService(List.of(mock),"MOCK",false)
                .simulateRefresh(LocalDate.of(2026,11,21)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FX_RATE_REFRESH_DISABLED");
    }

    @Test void fxMock05UntrustedProviderOrMissingMockIsRejected() {
        var date=LocalDate.of(2026,11,21);
        assertThatThrownBy(()->new FxRateRefreshService(List.of(),"MOCK",true)
                .simulateRefresh(date))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FX_MOCK_PROVIDER_UNAVAILABLE");
        FxRateProvider spoof=new FxRateProvider() {
            public String code() { return "MOCK"; }
            public List<Rate> fetch(LocalDate day) {
                return List.of(new Rate("USD","KZT",BigDecimal.ONE,BigDecimal.ONE,
                        date,"NBRK"));
            }
        };
        assertThatThrownBy(()->new FxRateRefreshService(List.of(spoof),"MOCK",true)
                .simulateRefresh(date))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FX_MOCK_PROVIDER_INVALID");
    }
}
