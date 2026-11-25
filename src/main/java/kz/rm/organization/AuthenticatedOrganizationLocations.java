package kz.rm.organization;

import kz.rm.iam.VerifiedActor;
import kz.rm.organization.dto.LocationCreateRequest;
import kz.rm.organization.dto.LocationDto;
import kz.rm.organization.dto.LocationSearch;
import kz.rm.organization.dto.LocationUpdateRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Trusted entrypoint: never accept an unverified actor UUID from client code. */
@Service
public class AuthenticatedOrganizationLocations {
    private final OrganizationLocationService commands;
    private final OrganizationLocationQueryService queries;
    private final LocationMapper mapper;
    public AuthenticatedOrganizationLocations(OrganizationLocationService commands,
            OrganizationLocationQueryService queries,LocationMapper mapper) {
        this.commands=commands;this.queries=queries;this.mapper=mapper;
    }
    private UUID id(VerifiedActor actor) {
        if(actor==null) throw new SecurityException("Verified actor required");
        return actor.userId();
    }
    public LocationDto create(VerifiedActor actor,UUID tenant,LocationCreateRequest request) {
        return mapper.toDto(commands.create(id(actor),tenant,request));
    }
    /** Backward-compatible create entrypoint for existing service callers/tests. */
    public LocationDto create(VerifiedActor actor,UUID tenant,OrganizationLocationEntity.Type type,
                              String name,String kato,String address) {
        return create(actor,tenant,new LocationCreateRequest(type,name,kato,address));
    }
    public LocationDto update(VerifiedActor actor,UUID tenant,UUID location,LocationUpdateRequest request) {
        return mapper.toDto(commands.update(id(actor),tenant,location,request));
    }
    public LocationDto deactivate(VerifiedActor actor,UUID tenant,UUID location,long expectedVersion) {
        return mapper.toDto(commands.deactivate(id(actor),tenant,location,expectedVersion));
    }
    public LocationDto reactivate(VerifiedActor actor,UUID tenant,UUID location,long expectedVersion) {
        return mapper.toDto(commands.reactivate(id(actor),tenant,location,expectedVersion));
    }
    public LocationDto close(VerifiedActor actor,UUID tenant,UUID location,long expectedVersion) {
        return mapper.toDto(commands.close(id(actor),tenant,location,expectedVersion));
    }
    public Page<LocationDto> search(VerifiedActor actor,UUID tenant,LocationSearch filter,int page,int size) {
        return queries.search(id(actor),tenant,filter,page,size);
    }
    public LocationDto get(VerifiedActor actor,UUID tenant,UUID location) {
        return queries.get(id(actor),tenant,location);
    }
}
