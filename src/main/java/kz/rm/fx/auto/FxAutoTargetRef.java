package kz.rm.fx.auto;

import java.util.Objects;
import java.util.UUID;

/** Typed, tenant-qualified identity. Never interpret a service code as an offer UUID. */
public record FxAutoTargetRef(UUID tenantId, TargetType targetType,
                              OwnerType ownerType, String canonicalKey) {
    public enum TargetType { PRODUCT_OFFER, GENERIC_SERVICE, PACKAGING_SERVICE }
    public enum OwnerType { STOREFRONT, SALES_PACKAGING, GENERIC_SERVICE }

    public FxAutoTargetRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(ownerType, "ownerType");
        if (canonicalKey == null || canonicalKey.isBlank() || !canonicalKey.equals(canonicalKey.trim())
                || canonicalKey.length() > 256) {
            throw new IllegalArgumentException("FX_AUTO_INVALID_TARGET_KEY");
        }
        if (targetType == TargetType.PRODUCT_OFFER && ownerType != OwnerType.STOREFRONT
                || targetType == TargetType.PACKAGING_SERVICE && ownerType != OwnerType.SALES_PACKAGING
                || targetType == TargetType.GENERIC_SERVICE && ownerType != OwnerType.GENERIC_SERVICE) {
            throw new IllegalArgumentException("FX_AUTO_OWNER_TYPE_MISMATCH");
        }
        if (targetType == TargetType.PRODUCT_OFFER) {
            try {
                UUID id = UUID.fromString(canonicalKey);
                if (!id.toString().equals(canonicalKey)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("FX_AUTO_PRODUCT_OFFER_KEY_INVALID", ex);
            }
        }
    }
}
