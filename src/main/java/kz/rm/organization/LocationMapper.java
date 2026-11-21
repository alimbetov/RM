package kz.rm.organization;

import kz.rm.organization.dto.LocationDto;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface LocationMapper {
    LocationDto toDto(OrganizationLocationEntity entity);
}
