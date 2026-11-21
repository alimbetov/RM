package kz.rm.organization;

import kz.rm.iam.TenantAuthorization;
import kz.rm.geo.KatoLocationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * ORGANIZATION owns locations; GEO supplies only location eligibility policy.
 * Access must be authorized by IAM before even checking the KATO reference.
 * Only trusted application adapters may supply authenticatedActorId.
 */
@Service
@RequiredArgsConstructor
class OrganizationLocationService {
    private final TenantAuthorization authorization;
    private final KatoLocationPolicy kato;
    private final OrganizationLocationRepository locations;

    @Transactional
    public OrganizationLocationEntity create(UUID authenticatedActorId, UUID tenantId,
                                             OrganizationLocationEntity.Type type,
                                             String name, String katoCode, String addressLine) {
        authorization.requireForCommand(authenticatedActorId, tenantId,
                kz.rm.iam.DomainPermission.ORGANIZATION_LOCATION_WRITE);
        kato.requireOrganizationLocationEligible(katoCode);
        return locations.saveAndFlush(new OrganizationLocationEntity(
                UUID.randomUUID(),tenantId,type,name,katoCode,addressLine));
    }
}
