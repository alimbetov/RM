package kz.rm.geo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * GEO application port for organization location placement.
 * Consumers do not access GEO persistence repositories directly.
 */
@Service
@RequiredArgsConstructor
public class KatoLocationPolicy {
    private final KatoTerritoryRepository kato;

    public void requireOrganizationLocationEligible(String katoCode) {
        if (katoCode == null || katoCode.isBlank())
            throw new IllegalArgumentException("KATO code required");
        var territory=kato.findByCodeAndDeletedFalse(katoCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or deleted KATO code"));
        if (territory.getLevel()>3)
            throw new IllegalArgumentException("KATO selection limited to oblast/district level");
    }
}
