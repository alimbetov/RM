package kz.rm.iam;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * QR-05/06: explicit warehouse-specific authorization in addition to the
 * tenant's SALES/INVENTORY grants. Assignment is governed only by the
 * non-delegable IAM TENANT_RBAC_MANAGE capability (organization owner).
 *
 * App commands acquire order row before this scope and org_location SHARE
 * fence. Scope revocations use UPDATE and block new admission. The scope
 * check is a SECOND gate, not a replacement for dual-domain permission.
 */
@Service
public class WarehouseStaffScopeService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private String scopeLockSql;
    private String locationLockSql;

    public WarehouseStaffScopeService(JdbcTemplate jdbc,
                                      TenantAuthorization authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @PostConstruct
    void init() {
        String db=jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        String mode=(db!=null &&
                db.toLowerCase(java.util.Locale.ROOT).contains("postgresql"))
                ?" FOR SHARE":" FOR UPDATE";
        scopeLockSql="""
                SELECT state FROM public.iam_warehouse_staff_scope
                WHERE tenant_id=? AND warehouse_id=? AND actor_user_id=?
                """+mode;
        locationLockSql="""
                SELECT status FROM public.org_location
                WHERE workspace_id=? AND id=? AND location_type='WAREHOUSE'
                """+mode;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void requireAssigned(UUID actor,UUID tenant,UUID warehouse) {
        if(actor==null||tenant==null||warehouse==null)
            throw new SecurityException("WAREHOUSE_SCOPE_REQUIRED");
        // Operation must have passed its domain-specific transaction-scoped
        // TenantAuthorization gates before calling this scope check.
        String status=jdbc.query(scopeLockSql,rs->rs.next()?rs.getString(1):null,
                tenant,warehouse,actor);
        if(!"ACTIVE".equals(status))
            throw new SecurityException("WAREHOUSE_SCOPE_REQUIRED");
        String location=jdbc.query(locationLockSql,
                rs->rs.next()?rs.getString(1):null,tenant,warehouse);
        if(!"ACTIVE".equals(location))
            throw new SecurityException("WAREHOUSE_INACTIVE");
    }

    @Transactional
    public void assign(VerifiedActor owner,UUID tenant,UUID warehouse,UUID staff) {
        ownerPermit(owner,tenant);
        if(warehouse==null||staff==null)
            throw new IllegalArgumentException("Warehouse and staff required");
        String type=jdbc.query("""
                SELECT location_type||':'||status FROM public.org_location
                WHERE workspace_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant,warehouse);
        if(!"WAREHOUSE:ACTIVE".equals(type))
            throw new IllegalArgumentException("ACTIVE_WAREHOUSE_REQUIRED");
        UUID organization=jdbc.queryForObject("""
                SELECT organization_id FROM public.iam_workspace
                WHERE id=? AND kind='ORGANIZATION' AND status='ACTIVE'
                """,UUID.class,tenant);
        Integer member=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_org_membership
                WHERE organization_id=? AND user_id=? AND status='ACTIVE'
                """,Integer.class,organization,staff);
        if(member==null||member!=1)
            throw new SecurityException("WAREHOUSE_STAFF_MEMBERSHIP_REQUIRED");
        String old=jdbc.query("""
                SELECT state FROM public.iam_warehouse_staff_scope
                WHERE tenant_id=? AND warehouse_id=? AND actor_user_id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant,warehouse,staff);
        if("ACTIVE".equals(old))return;
        Timestamp now=Timestamp.from(Instant.now());
        if(old==null)
            jdbc.update("""
                    INSERT INTO public.iam_warehouse_staff_scope
                      (tenant_id,warehouse_id,actor_user_id,state,granted_by,
                       granted_at,changed_at,row_version)
                    VALUES (?,?,?,'ACTIVE',?,?,?,0)
                    """,tenant,warehouse,staff,owner.userId(),now,now);
        else
            jdbc.update("""
                    UPDATE public.iam_warehouse_staff_scope
                    SET state='ACTIVE',granted_by=?,granted_at=?,changed_at=?,
                        row_version=row_version+1
                    WHERE tenant_id=? AND warehouse_id=? AND actor_user_id=?
                    """,owner.userId(),now,now,tenant,warehouse,staff);
        audit(tenant,owner.userId(),warehouse,"WAREHOUSE_SCOPE_ASSIGNED");
    }

    @Transactional
    public void revoke(VerifiedActor owner,UUID tenant,UUID warehouse,UUID staff) {
        ownerPermit(owner,tenant);
        if(warehouse==null||staff==null)
            throw new IllegalArgumentException("Warehouse and staff required");
        // No location FOR UPDATE here: in-flight staff command locks the
        // scope row before org_location. Only scope row is modified.
        int modified=jdbc.update("""
                UPDATE public.iam_warehouse_staff_scope
                SET state='REVOKED',changed_at=?,row_version=row_version+1
                WHERE tenant_id=? AND warehouse_id=? AND actor_user_id=?
                  AND state='ACTIVE'
                """,Timestamp.from(Instant.now()),tenant,warehouse,staff);
        if(modified==1)
            audit(tenant,owner.userId(),warehouse,"WAREHOUSE_SCOPE_REVOKED");
    }

    private void ownerPermit(VerifiedActor owner,UUID tenant) {
        if(owner==null||tenant==null)
            throw new SecurityException("OWNER_REQUIRED");
        authorization.requireForCommand(owner.userId(),tenant,
                DomainPermission.TENANT_RBAC_MANAGE);
    }

    private void audit(UUID tenant,UUID actor,UUID warehouse,String action){
        jdbc.update("""
                INSERT INTO crm.audit_event
                 (tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),actor,warehouse,action,
                Timestamp.from(Instant.now()));
    }
}
