package kz.rm.organization.dto;

/** Full replacement of editable profile fields; not a partial PATCH. */
public record LocationUpdateRequest(
        String name,
        String katoCode,
        String addressLine,
        long expectedVersion) {}
