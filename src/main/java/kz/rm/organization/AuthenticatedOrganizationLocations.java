package kz.rm.organization;

import kz.rm.iam.VerifiedActor;
import kz.rm.organization.dto.LocationDto;
import kz.rm.organization.dto.LocationSearch;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import java.util.UUID;

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
    public LocationDto create(VerifiedActor actor,UUID tenant,OrganizationLocationEntity.Type type,
                              String name,String kato,String address) {
        return mapper.toDto(commands.create(id(actor),tenant,type,name,kato,address));
    }
    public Page<LocationDto> search(VerifiedActor actor,UUID tenant,LocationSearch filter,int page,int size) {
        return queries.search(id(actor),tenant,filter,page,size);
    }
    public LocationDto get(VerifiedActor actor,UUID tenant,UUID location) {
        return queries.get(id(actor),tenant,location);
    }
}
