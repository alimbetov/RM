package kz.rm.organization.dto;

import kz.rm.organization.OrganizationLocationEntity;
import java.time.Instant;
import java.util.UUID;

public record LocationDto(UUID id, UUID workspaceId, OrganizationLocationEntity.Type type,
                          String name, String katoCode, String addressLine,
                          OrganizationLocationEntity.Status status, Instant createdAt,
                          Instant updatedAt, long rowVersion) {}
