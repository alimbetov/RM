package kz.rm.organization.dto;

import kz.rm.organization.OrganizationLocationEntity;

/** Create only a new physical location; identity and status are server-owned. */
public record LocationCreateRequest(
        OrganizationLocationEntity.Type type,
        String name,
        String katoCode,
        String addressLine) {}
