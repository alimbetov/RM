package kz.rm.organization;

import kz.rm.geo.KatoLocationPolicy;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.inventory.InventoryLocationUsagePort;
import kz.rm.organization.dto.LocationCreateRequest;
import kz.rm.organization.dto.LocationUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * ORGANIZATION owns location identity/profile/state, INVENTORY owns physical usage.
 * All mutations are tenant-authorized before accessing location or GEO data.
 * The same locked org_location row fences concurrent warehouse commands.
 */
@Service
@RequiredArgsConstructor
class OrganizationLocationService {
    private final TenantAuthorization authorization;
    private final KatoLocationPolicy kato;
    private final OrganizationLocationRepository locations;
    private final InventoryLocationUsagePort inventoryUsage;
    private final JdbcTemplate jdbc;

    /** Existing internal signature retained for compatibility with prior callers/tests. */
    @Transactional
    public OrganizationLocationEntity create(UUID actorId,UUID tenantId,
            OrganizationLocationEntity.Type type,String name,String katoCode,String addressLine) {
        return create(actorId,tenantId,new LocationCreateRequest(type,name,katoCode,addressLine));
    }

    @Transactional
    public OrganizationLocationEntity create(UUID actorId,UUID tenantId,LocationCreateRequest request) {
        authorize(actorId,tenantId);
        if(request==null || request.type()==null)
            throw new IllegalArgumentException("Location create type required");
        var data=LocationFields.profile(request.name(),request.katoCode(),request.addressLine());
        kato.requireOrganizationLocationEligible(data.katoCode());
        var created=locations.saveAndFlush(new OrganizationLocationEntity(
                UUID.randomUUID(),tenantId,request.type(),data.name(),
                data.katoCode(),data.addressLine()));
        audit(tenantId,actorId,created.getId(),"ORG_LOCATION_CREATED");
        return created;
    }

    @Transactional
    public OrganizationLocationEntity update(UUID actorId,UUID tenantId,UUID id,
                                               LocationUpdateRequest request) {
        authorize(actorId,tenantId);
        if(request==null) throw new IllegalArgumentException("Location update required");
        var current=locked(tenantId,id,request.expectedVersion());
        if(current.getStatus()==OrganizationLocationEntity.Status.CLOSED)
            throw new IllegalStateException("Closed location cannot be edited");
        var fields=LocationFields.profile(request.name(),request.katoCode(),request.addressLine());
        if(!current.getKatoCode().equals(fields.katoCode())) {
            var blockers=inventoryUsage.blockers(tenantId,id);
            if(blockers.hasOnHand() || blockers.hasReservedBalance()
                    || blockers.hasActiveReservation() || blockers.hasOpenStocktake()
                    || inventoryUsage.hasMovementOrReservationHistory(tenantId,id))
                throw new IllegalStateException("KATO_CHANGE_BLOCKED_BY_HISTORY");
            kato.requireOrganizationLocationEligible(fields.katoCode());
        }
        if(!current.replaceProfile(fields.name(),fields.katoCode(),fields.addressLine()))
            return current; // verified up-to-date no-op; no new version or audit
        var saved=locations.saveAndFlush(current);
        audit(tenantId,actorId,id,"ORG_LOCATION_UPDATED");
        return saved;
    }

    @Transactional
    public OrganizationLocationEntity deactivate(UUID actorId,UUID tenantId,UUID id,long expectedVersion) {
        return transition(actorId,tenantId,id,expectedVersion,OrganizationLocationEntity.Status.INACTIVE);
    }

    @Transactional
    public OrganizationLocationEntity reactivate(UUID actorId,UUID tenantId,UUID id,long expectedVersion) {
        return transition(actorId,tenantId,id,expectedVersion,OrganizationLocationEntity.Status.ACTIVE);
    }

    @Transactional
    public OrganizationLocationEntity close(UUID actorId,UUID tenantId,UUID id,long expectedVersion) {
        return transition(actorId,tenantId,id,expectedVersion,OrganizationLocationEntity.Status.CLOSED);
    }

    private OrganizationLocationEntity transition(UUID actorId,UUID tenantId,UUID id,
            long expectedVersion,OrganizationLocationEntity.Status target) {
        authorize(actorId,tenantId);
        var current=locked(tenantId,id,expectedVersion);
        var state=current.getStatus();
        if(state==target) return current; // fresh expectedVersion required even for replay
        if(state==OrganizationLocationEntity.Status.CLOSED)
            throw new IllegalStateException("CLOSED is terminal");
        boolean deactivation=state==OrganizationLocationEntity.Status.ACTIVE
                && target==OrganizationLocationEntity.Status.INACTIVE;
        boolean activation=state==OrganizationLocationEntity.Status.INACTIVE
                && target==OrganizationLocationEntity.Status.ACTIVE;
        boolean closure=state==OrganizationLocationEntity.Status.INACTIVE
                && target==OrganizationLocationEntity.Status.CLOSED;
        if(!deactivation && !activation && !closure)
            throw new IllegalStateException("Invalid location state transition");
        if(deactivation || closure) {
            var blockers=inventoryUsage.blockers(tenantId,id);
            if(blockers.hasReservedBalance() || blockers.hasActiveReservation()
                    || blockers.hasOpenStocktake())
                throw new IllegalStateException("Location has active inventory commitments");
            if(closure && blockers.hasOnHand())
                throw new IllegalStateException("Cannot close location with stock");
        }
        // Reactivation retains the existing KATO reference. GEO may have deprecated
        // it since creation; changing KATO is a separate guarded update.
        current.moveTo(target);
        var saved=locations.saveAndFlush(current);
        String action=switch(target) {
            case INACTIVE -> "ORG_LOCATION_DEACTIVATED";
            case ACTIVE -> "ORG_LOCATION_REACTIVATED";
            case CLOSED -> "ORG_LOCATION_CLOSED";
        };
        audit(tenantId,actorId,id,action);
        return saved;
    }

    private void authorize(UUID actorId,UUID tenantId) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.ORGANIZATION_LOCATION_WRITE);
    }

    private OrganizationLocationEntity locked(UUID tenantId,UUID id,long expectedVersion) {
        if(id==null || expectedVersion<0)
            throw new IllegalArgumentException("Location identity and version required");
        var current=locations.lockByWorkspaceIdAndId(tenantId,id)
                .orElseThrow(()->new IllegalArgumentException("Location not found in tenant"));
        if(current.getRowVersion()!=expectedVersion)
            throw new IllegalStateException("STALE_LOCATION_VERSION");
        return current;
    }

    private void audit(UUID tenantId,UUID actorId,UUID id,String action) {
        jdbc.update("""
                INSERT INTO crm.audit_event
                   (tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenantId,UUID.randomUUID(),actorId,id,action,
                Timestamp.from(Instant.now()));
    }
}
