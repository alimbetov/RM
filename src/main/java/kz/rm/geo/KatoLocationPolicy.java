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
        if (katoCode == null || !katoCode.matches("[0-9]{9}"))
            throw new IllegalArgumentException("KATO code must contain exactly nine ASCII digits");
        var territory=kato.findByCodeAndDeletedFalse(katoCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or deleted KATO code"));
        if (!KatoHierarchy.eligible(territory,id->kato.findBySourceId(id).orElse(null)))
            throw new IllegalArgumentException("KATO selection limited to approved administrative territories");
    }
}
