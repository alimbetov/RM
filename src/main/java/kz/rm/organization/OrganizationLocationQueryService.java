package kz.rm.organization;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.organization.dto.LocationDto;
import kz.rm.organization.dto.LocationSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Internal service: actor must come from validated principal, not request parameters. */
@Service
@RequiredArgsConstructor
class OrganizationLocationQueryService {
    private final TenantAuthorization authorization;
    private final OrganizationLocationRepository repository;
    private final LocationMapper mapper;

    @Transactional
    public Page<LocationDto> search(UUID actorId, UUID tenantId, LocationSearch filters,
                                    int page, int size) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.ORGANIZATION_LOCATION_READ);
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("Page must be nonnegative, size 1..100");
        var query = filters == null ? new LocationSearch(null,null,null) : filters;
        return repository.search(tenantId,query.type(),query.status(),query.name()==null?"":query.name(),
                PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt")
                        .and(Sort.by("id")))).map(mapper::toDto);
    }

    @Transactional
    public LocationDto get(UUID actorId,UUID tenantId,UUID id) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.ORGANIZATION_LOCATION_READ);
        if(id==null) throw new IllegalArgumentException("Location required");
        return repository.findByIdAndWorkspaceId(id,tenantId)
                .map(mapper::toDto).orElseThrow(()->new IllegalArgumentException("Location not found"));
    }
}
