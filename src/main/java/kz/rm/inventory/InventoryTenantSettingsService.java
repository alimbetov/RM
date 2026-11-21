package kz.rm.inventory;

import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.DomainPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * Tenant-level inventory configuration.
 * Cost formula is an approved immutable revision, NOT a mutable setting.
 * Internal application port only: authenticatedActorId must come from a
 * trusted principal, never from an untrusted HTTP request field.
 */
@Service
public class InventoryTenantSettingsService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public InventoryTenantSettingsService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    public enum ValuationMethod { FIFO, MOVING_WEIGHTED_AVERAGE, SPECIFIC_IDENTIFICATION }
    public enum PickingStrategy { FIFO, FEFO, MANUAL }

    public record Settings(UUID tenantId, PickingStrategy pickingStrategy,
                           boolean autoReserveOnConfirm, long rowVersion,
                           UUID activePolicyId, ValuationMethod valuationMethod,
                           String functionalCurrency, int policyRevision) {}

    @Transactional
    public Settings initialize(UUID authenticatedActorId, UUID tenantId) {
        authorize(authenticatedActorId,tenantId);
        // Lock the tenant row to serialize simultaneous initialization across pods.
        jdbc.queryForObject("SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE",
                UUID.class,tenantId);
        Integer exists=jdbc.queryForObject(
                "SELECT count(*) FROM crm.inv_tenant_setting WHERE tenant_id=?",
                Integer.class,tenantId);
        if(exists==0) {
            Timestamp now=Timestamp.from(Instant.now());
            jdbc.update("""
                INSERT INTO crm.inv_tenant_setting(tenant_id,picking_strategy,
                    auto_reserve_on_confirm,allow_negative_stock,created_at,updated_at)
                VALUES (?,'FIFO',FALSE,FALSE,?,?)
                """,tenantId,now,now);
        }
        return load(tenantId,false);
    }

    @Transactional(readOnly=true)
    public Settings get(UUID authenticatedActorId, UUID tenantId) {
        authorize(authenticatedActorId,tenantId);
        return load(tenantId,false);
    }

    @Transactional
    public Settings updatePicking(UUID authenticatedActorId,UUID tenantId,
                                  PickingStrategy strategy,boolean autoReserve,long expectedVersion) {
        authorize(authenticatedActorId,tenantId);
        if(strategy==null || expectedVersion<0)
            throw new IllegalArgumentException("Picking strategy and version required");
        Settings old=load(tenantId,true);
        if(old.rowVersion()!=expectedVersion)
            throw new IllegalStateException("Stale inventory setting revision");
        jdbc.update("""
            UPDATE crm.inv_tenant_setting
            SET picking_strategy=?,auto_reserve_on_confirm=?,
                row_version=row_version+1,updated_at=?
            WHERE tenant_id=? AND row_version=?
            """,strategy.name(),autoReserve,Timestamp.from(Instant.now()),tenantId,expectedVersion);
        return load(tenantId,false);
    }

    @Transactional
    public Settings approveCostPolicy(UUID authenticatedActorId,UUID tenantId,
                                      ValuationMethod method,String currency,long expectedVersion) {
        authorize(authenticatedActorId,tenantId);
        if(method==null || currency==null || expectedVersion<0)
            throw new IllegalArgumentException("Method, currency and revision required");
        String iso=Currency.getInstance(currency).getCurrencyCode();
        if(!iso.equals(currency))
            throw new IllegalArgumentException("Canonical ISO 4217 currency required");
        Settings current=load(tenantId,true);
        if(current.rowVersion()!=expectedVersion)
            throw new IllegalStateException("Stale inventory setting revision");

        // Until historic stock valuation/backfill is implemented, changing or
        // creating a policy for non-empty, unvalued inventory is prohibited.
        Integer unvalued=jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_balance
            WHERE tenant_id=? AND (on_hand<>0 OR reserved<>0)
            """,Integer.class,tenantId);
        Integer movements=jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
            """,Integer.class,tenantId);
        if(unvalued!=0 || movements!=0)
            throw new IllegalStateException("Existing stock requires qualified valuation migration");

        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_cost_policy_version WHERE tenant_id=?
            """,Integer.class,tenantId);
        int revision=Math.addExact(count,1);
        UUID policy=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.inv_cost_policy_version
              (tenant_id,id,revision_no,valuation_method,functional_currency,
               effective_from,approved_by,approved_at,created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,tenantId,policy,revision,method.name(),iso,now,authenticatedActorId,now,now);
        int changed=jdbc.update("""
            UPDATE crm.inv_tenant_setting
            SET active_policy_version_id=?,row_version=row_version+1,updated_at=?
            WHERE tenant_id=? AND row_version=?
            """,policy,now,tenantId,expectedVersion);
        if(changed!=1)throw new IllegalStateException("Concurrent settings update");
        return load(tenantId,false);
    }

    private void authorize(UUID actor,UUID tenant) {
        authorization.require(actor,tenant,DomainPermission.INVENTORY_SETTINGS_MANAGE);
    }

    private Settings load(UUID tenant,boolean lock) {
        String query="""
            SELECT s.picking_strategy,s.auto_reserve_on_confirm,s.row_version,
                   s.active_policy_version_id,p.valuation_method,
                   p.functional_currency,p.revision_no
            FROM crm.inv_tenant_setting s
            LEFT JOIN crm.inv_cost_policy_version p
              ON p.tenant_id=s.tenant_id AND p.id=s.active_policy_version_id
            WHERE s.tenant_id=?
            """+(lock?" FOR UPDATE OF s":"");
        // FOR UPDATE OF alias is PostgreSQL-specific. Lock the settings table in
        // a separate query for H2 compatibility, prior to the common read.
        if(lock) {
            jdbc.queryForObject("""
                SELECT row_version FROM crm.inv_tenant_setting WHERE tenant_id=? FOR UPDATE
                """,Long.class,tenant);
            query=query.replace(" FOR UPDATE OF s","");
        }
        return jdbc.query(query,rs->{
            if(!rs.next())throw new IllegalArgumentException("Inventory settings not initialized");
            UUID policy=rs.getObject(4,UUID.class);
            return new Settings(tenant,PickingStrategy.valueOf(rs.getString(1)),
                    rs.getBoolean(2),rs.getLong(3),policy,
                    policy==null?null:ValuationMethod.valueOf(rs.getString(5)),
                    policy==null?null:rs.getString(6),policy==null?0:rs.getInt(7));
        },tenant);
    }
}
