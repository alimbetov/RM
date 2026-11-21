package kz.rm.geo;

import kz.rm.iam.TenantAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * Legacy GEO-package facade for organization-owned location.
 * Access must be authorized by IAM before even checking the KATO reference.
 * Only trusted application adapters may supply authenticatedActorId.
 */
@Service
@RequiredArgsConstructor
public class OrganizationLocationService {
    private final TenantAuthorization authorization;
    private final KatoTerritoryRepository kato;
    private final OrganizationLocationRepository locations;

    @Transactional
    public OrganizationLocationEntity create(UUID authenticatedActorId, UUID tenantId,
                                             OrganizationLocationEntity.Type type,
                                             String name, String katoCode, String addressLine) {
        authorization.requireOrganizationWriter(authenticatedActorId, tenantId);
        var territory=kato.findByCodeAndDeletedFalse(katoCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown or deleted KATO code"));
        if(territory.getLevel()>3)
            throw new IllegalArgumentException("KATO selection limited to oblast/district level");
        return locations.saveAndFlush(new OrganizationLocationEntity(
                UUID.randomUUID(),tenantId,type,name,katoCode,addressLine));
    }
}
