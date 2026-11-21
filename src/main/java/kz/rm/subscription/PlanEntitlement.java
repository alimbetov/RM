package kz.rm.subscription;
import java.util.UUID;
/** Paid/free entitlements never become IAM role types. */
public record PlanEntitlement(UUID tenantId, String capabilityCode, boolean enabled) {
    public PlanEntitlement {
        if(tenantId==null||capabilityCode==null||!capabilityCode.matches("[A-Z][A-Z0-9_]{2,79}"))
            throw new IllegalArgumentException("Invalid entitlement");
    }
}
