package kz.rm.inventory;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Only verified actor settings. No fake picking or optional-reservation switches. */
@Service
public class AuthenticatedInventorySettings {
    public enum ReservationMode { MANDATORY_ON_CONFIRM }
    public enum PickingReadiness { NOT_IMPLEMENTED }
    public enum ValuationReadiness { NOT_CONFIGURED, POLICY_ONLY }
    public enum ValuationMethod { FIFO, MOVING_WEIGHTED_AVERAGE, SPECIFIC_IDENTIFICATION }
    public record OperationalSettings(UUID tenantId,ReservationMode reservationMode,
           PickingReadiness pickingReadiness,ValuationReadiness valuationReadiness,
           UUID costPolicyId,String functionalCurrency,int policyRevision,long rowVersion) {}
    private final InventoryTenantSettingsService settings;
    public AuthenticatedInventorySettings(InventoryTenantSettingsService settings) {
        this.settings=settings;
    }
    public OperationalSettings initialize(VerifiedActor actor,UUID tenant){
        return project(settings.initialize(who(actor),tenant));
    }
    public OperationalSettings get(VerifiedActor actor,UUID tenant){
        return project(settings.get(who(actor),tenant));
    }
    /** Approves policy metadata, not a costing engine or COGS. */
    public OperationalSettings approveCostPolicy(VerifiedActor actor,UUID tenant,
                     ValuationMethod method,String currency,long expectedVersion){
        if(method==null)throw new IllegalArgumentException("Valuation method required");
        return project(settings.approveCostPolicy(who(actor),tenant,
                InventoryTenantSettingsService.ValuationMethod.valueOf(method.name()),
                currency,expectedVersion));
    }
    private static UUID who(VerifiedActor actor){
        if(actor==null)throw new SecurityException("Verified actor required");
        return actor.userId();
    }
    private static OperationalSettings project(InventoryTenantSettingsService.Settings s){
        return new OperationalSettings(s.tenantId(),ReservationMode.MANDATORY_ON_CONFIRM,
                PickingReadiness.NOT_IMPLEMENTED,
                s.activePolicyId()==null?ValuationReadiness.NOT_CONFIGURED:
                        ValuationReadiness.POLICY_ONLY,
                s.activePolicyId(),s.functionalCurrency(),s.policyRevision(),s.rowVersion());
    }
}
