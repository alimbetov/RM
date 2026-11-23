package kz.rm.fx;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * FX-R0 mock refresh simulation. No persistence or public price changes.
 * Production/default behavior is disabled (fail closed).
 */
@Service
public final class FxRateRefreshService {
    public record RefreshResult(String source, boolean synthetic, LocalDate requestedDate,
                                List<FxRateProvider.Rate> rates) {}

    private final List<FxRateProvider> providers;
    private final String mode;
    private final boolean isNonProductionProfile;

    @Autowired
    public FxRateRefreshService(List<FxRateProvider> providers,
                                @Value("${rm.fx.rate.provider:DISABLED}") String mode,
                                Environment environment) {
        this(providers, mode, environment.acceptsProfiles(Profiles.of("dev", "test")));
    }

    // Package-private test seam; cannot be called by HTTP.
    FxRateRefreshService(List<FxRateProvider> providers, String mode, boolean nonProductionProfile) {
        this.providers=List.copyOf(providers);
        this.mode=mode;
        this.isNonProductionProfile=nonProductionProfile;
    }

    /**
     * Explicit trusted caller only; a scheduled job is deliberately absent.
     * Synthetic refresh is never represented as an authoritative official rate.
     */
    public RefreshResult simulateRefresh(LocalDate date) {
        if (!"MOCK".equals(mode) || !isNonProductionProfile)
            throw new IllegalStateException("FX_RATE_REFRESH_DISABLED");
        if (date == null)throw new IllegalArgumentException("Effective FX date required");
        FxRateProvider provider=providers.stream()
                .filter(p->"MOCK".equals(p.code())).findFirst()
                .orElseThrow(()->new IllegalStateException("FX_MOCK_PROVIDER_UNAVAILABLE"));
        List<FxRateProvider.Rate> rates=List.copyOf(provider.fetch(date));
        if(rates.isEmpty())throw new IllegalStateException("FX_MOCK_RATE_NOT_FOUND");
        if(rates.stream().anyMatch(r->!"MOCK".equals(r.provider()) ||
                !"KZT".equals(r.quoteCurrency()) || r.validDate().isAfter(date)))
            throw new IllegalStateException("FX_MOCK_PROVIDER_INVALID");
        return new RefreshResult("MOCK",true,date,rates);
    }
}
