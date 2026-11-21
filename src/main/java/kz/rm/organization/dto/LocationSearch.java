package kz.rm.organization.dto;

import kz.rm.organization.OrganizationLocationEntity;

/** Optional, bounded list filters. Null means no filter. */
public record LocationSearch(OrganizationLocationEntity.Type type,
                             OrganizationLocationEntity.Status status, String name) {
    public LocationSearch {
        if (name != null) {
            name = name.trim();
            if (name.length() > 100) throw new IllegalArgumentException("Name filter too long");
            if (name.isEmpty()) name = null;
        }
    }
}
