package kz.rm.organization;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CUR-R1A: tenant-scoped currency configuration, not FX or a financial ledger.
 * Only a trusted verified actor may initialize/read private tenant policy.
 * KZ public price currency is KZT regardless of foreign PURCHASE allowlist.
 * Currency admission for commerce will be wired in CUR-R1C.
 */
@Service
public class TenantCurrencyPolicyService {
    public record Policy(UUID tenantId, String accountingCurrency, String marketCountry,
                         String publicPriceCurrency, String status, long rowVersion) {}
    public record Allowed(String currency, String purpose, String state) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public TenantCurrencyPolicyService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc=jdbc;
        this.authorization=authorization;
    }

    @Transactional
    public Policy initialize(VerifiedActor actor, UUID tenantId) {
        UUID user=actorId(actor);
        authorization.requireForCommand(user,tenantId,DomainPermission.TENANT_RBAC_MANAGE);
        // All initializers serialize on the shared, trusted workspace row.
        jdbc.queryForObject("SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE",
                UUID.class,tenantId);
        Policy existing=load(tenantId,false);
        if(existing!=null)return existing;

        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO public.tenant_currency_policy
             (tenant_id,accounting_currency,market_country,public_price_currency,
              status,policy_version,row_version,approved_by,approved_at,created_at,updated_at)
            VALUES (?,'KZT','KZ','KZT','ACTIVE',1,0,?,?,?,?)
            """,tenantId,user,now,now,now);
        for(String purpose:List.of("ACCOUNTING","PUBLIC_PRICE","INVENTORY_VALUATION","PURCHASE")) {
            jdbc.update("""
                INSERT INTO public.tenant_allowed_currency
                  (tenant_id,currency_code,purpose,state,row_version,created_at,updated_at)
                VALUES (?,'KZT',?,'ENABLED',0,?,?)
                """,tenantId,purpose,now,now);
        }
        jdbc.update("""
            INSERT INTO public.tenant_currency_policy_event
              (tenant_id,id,revision_no,actor_user_id,action_code,currency_code,
               purpose,occurred_at)
            VALUES (?,?,1,?,'INITIALIZED','KZT','PUBLIC_PRICE',?)
            """,tenantId,UUID.randomUUID(),user,now);
        return loadRequired(tenantId,false);
    }

    @Transactional
    public Policy get(VerifiedActor actor,UUID tenantId) {
        authorization.requireForCommand(actorId(actor),tenantId,DomainPermission.TENANT_RBAC_MANAGE);
        return loadRequired(tenantId,false);
    }

    @Transactional
    public List<Allowed> listAllowed(VerifiedActor actor,UUID tenantId) {
        authorization.requireForCommand(actorId(actor),tenantId,DomainPermission.TENANT_RBAC_MANAGE);
        loadRequired(tenantId,false);
        return jdbc.query("""
            SELECT currency_code,purpose,state FROM public.tenant_allowed_currency
            WHERE tenant_id=? ORDER BY purpose,currency_code
            """,(rs,n)->new Allowed(rs.getString(1).trim(),rs.getString(2),rs.getString(3)),tenantId);
    }

    /**
     * Trusted service-to-service admission, only within a transaction.
     * The FOR UPDATE policy lock is held to commit, preventing a policy edit
     * from crossing a public commercial operation.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requirePublicKzCurrency(UUID tenantId,String currency) {
        if(tenantId==null || currency==null)
            throw new IllegalArgumentException("Public currency and tenant required");
        Policy policy=loadRequired(tenantId,true);
        if(!"ACTIVE".equals(policy.status())
                || !"KZ".equals(policy.marketCountry())
                || !"KZT".equals(policy.publicPriceCurrency())
                || !policy.publicPriceCurrency().equals(currency))
            throw new IllegalStateException("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
        Integer enabled=jdbc.queryForObject("""
            SELECT count(*) FROM public.tenant_allowed_currency
            WHERE tenant_id=? AND currency_code=? AND purpose='PUBLIC_PRICE'
              AND state='ENABLED'
            """,Integer.class,tenantId,currency);
        if(enabled==null || enabled!=1)
            throw new IllegalStateException("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
    }


    /** CUR-R1B: owner-only configuration change with tenant CAS and immutable event. */
    @Transactional
    public Policy setCurrencyEnabled(VerifiedActor actor, UUID tenantId, String currency,
                                     String purpose, boolean enabled, long expectedVersion) {
        UUID user=actorId(actor);
        if(tenantId==null || currency==null || !currency.matches("[A-Z]{3}")
                || purpose==null || !List.of("PURCHASE","ACCOUNTING","INVENTORY_VALUATION",
                    "SALES_INTERNAL","PUBLIC_PRICE","PRICING_ANCHOR").contains(purpose) || expectedVersion<0)
            throw new IllegalArgumentException("TENANT_CURRENCY_CHANGE_INVALID");
        validateAnchorCurrency(currency,purpose);
        try { java.util.Currency.getInstance(currency); }
        catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("TENANT_CURRENCY_ISO_INVALID",invalid);
        }
        if(("PUBLIC_PRICE".equals(purpose)||"ACCOUNTING".equals(purpose))
                && (!"KZT".equals(currency)||!enabled))
            throw new IllegalStateException("TENANT_CURRENCY_MANDATORY_KZT");
        authorization.requireForCommand(user,tenantId,DomainPermission.TENANT_RBAC_MANAGE);
        // Shared currency row is locked before any allowlist read/write.
        Policy policy=loadRequired(tenantId,true);
        if(!"ACTIVE".equals(policy.status()))
            throw new IllegalStateException("TENANT_CURRENCY_POLICY_REVIEW_REQUIRED");
        if(policy.rowVersion()!=expectedVersion)
            throw new IllegalStateException("TENANT_CURRENCY_STALE_VERSION");
        String state=enabled?"ENABLED":"DISABLED";
        String current=jdbc.query("""
            SELECT state FROM public.tenant_allowed_currency
            WHERE tenant_id=? AND currency_code=? AND purpose=?
            """,rs->rs.next()?rs.getString(1):null,tenantId,currency,purpose);
        if(state.equals(current))return policy;
        Timestamp now=Timestamp.from(Instant.now());
        if(current==null) {
            jdbc.update("""
                INSERT INTO public.tenant_allowed_currency
                (tenant_id,currency_code,purpose,state,row_version,created_at,updated_at)
                VALUES (?,?,?,?,0,?,?)
                """,tenantId,currency,purpose,state,now,now);
        } else {
            jdbc.update("""
                UPDATE public.tenant_allowed_currency
                SET state=?,row_version=row_version+1,updated_at=?
                WHERE tenant_id=? AND currency_code=? AND purpose=?
                """,state,now,tenantId,currency,purpose);
        }
        int changed=jdbc.update("""
            UPDATE public.tenant_currency_policy
            SET row_version=row_version+1,policy_version=policy_version+1,
                approved_by=?,approved_at=?,updated_at=?
            WHERE tenant_id=? AND row_version=?
            """,user,now,now,tenantId,expectedVersion);
        if(changed!=1)throw new IllegalStateException("TENANT_CURRENCY_STALE_VERSION");
        jdbc.update("""
            INSERT INTO public.tenant_currency_policy_event
            (tenant_id,id,revision_no,actor_user_id,action_code,currency_code,purpose,occurred_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,tenantId,UUID.randomUUID(),expectedVersion+2,user,
                enabled?"CURRENCY_ENABLED":"CURRENCY_DISABLED",currency,purpose,now);
        return loadRequired(tenantId,false);
    }

    /**
     * Internal purpose-specific admission; caller must own an outer business
     * transaction and follow global tenant-policy-before-commercial-row lock order.
     * A configured PURCHASE currency is not an assertion of legal settlement rights.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireCurrencyForPurpose(UUID tenantId,String currency,String purpose) {
        if(tenantId==null||currency==null||!currency.matches("[A-Z]{3}")||
                purpose==null||!List.of("PURCHASE","ACCOUNTING","INVENTORY_VALUATION",
                    "SALES_INTERNAL","PUBLIC_PRICE","PRICING_ANCHOR").contains(purpose))
            throw new IllegalArgumentException("TENANT_CURRENCY_PURPOSE_INVALID");
        validateAnchorCurrency(currency,purpose);
        if("PUBLIC_PRICE".equals(purpose)) {
            requirePublicKzCurrency(tenantId,currency);
            return;
        }
        Policy p=loadRequired(tenantId,true);
        if(!"ACTIVE".equals(p.status()))
            throw new IllegalStateException("TENANT_CURRENCY_POLICY_REVIEW_REQUIRED");
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM public.tenant_allowed_currency
            WHERE tenant_id=? AND currency_code=? AND purpose=? AND state='ENABLED'
            """,Integer.class,tenantId,currency,purpose);
        if(count==null||count!=1)
            throw new IllegalStateException("TENANT_CURRENCY_PURPOSE_NOT_ALLOWED");
    }

    /** Owner-only opt-in. No implicit grant from PURCHASE or legacy SALES_INTERNAL. */
    @Transactional
    public Policy setPricingAnchorEnabled(VerifiedActor actor, UUID tenantId, String currency,
                                          boolean enabled, long expectedVersion) {
        return setCurrencyEnabled(actor,tenantId,currency,"PRICING_ANCHOR",enabled,expectedVersion);
    }

    /** Internal admission retains the shared tenant currency lock until outer commit.
     * This is not an authorization to publish, nor a substitute for OWNER consent.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requirePricingAnchor(UUID tenantId,String currency) {
        requireCurrencyForPurpose(tenantId,currency,"PRICING_ANCHOR");
        requirePublicKzCurrency(tenantId,"KZT");
    }

    private static void validateAnchorCurrency(String currency,String purpose) {
        if ("PRICING_ANCHOR".equals(purpose) && !List.of("USD","EUR","CNY").contains(currency))
            throw new IllegalArgumentException("TENANT_PRICING_ANCHOR_CURRENCY_INVALID");
    }

    private Policy loadRequired(UUID tenantId,boolean lock) {
        Policy p=load(tenantId,lock);
        if(p==null)throw new IllegalStateException("TENANT_CURRENCY_POLICY_NOT_INITIALIZED");
        return p;
    }

    private Policy load(UUID tenantId,boolean lock) {
        if(tenantId==null)throw new IllegalArgumentException("Tenant required");
        return jdbc.query("""
            SELECT tenant_id,accounting_currency,market_country,public_price_currency,
                   status,row_version FROM public.tenant_currency_policy
            WHERE tenant_id=?
            """+(lock?" FOR UPDATE":""),
            rs->rs.next()?new Policy(rs.getObject(1,UUID.class),rs.getString(2).trim(),
                    rs.getString(3).trim(),rs.getString(4).trim(),
                    rs.getString(5),rs.getLong(6)):null,tenantId);
    }

    private static UUID actorId(VerifiedActor actor) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return actor.userId();
    }
}
