package kz.rm.organization;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * CUR-R1C staged admission. Enable only after existing tenant policies are
 * backfilled and a strict checkout/price lock-order qualification is green.
 * Default OFF means this is not an admitted production enforcement mechanism.
 */
@Service
public class CommerceCurrencyAdmission {
    private final JdbcTemplate jdbc;
    private final TenantCurrencyPolicyService policies;
    private final boolean enabled;

    public CommerceCurrencyAdmission(JdbcTemplate jdbc, TenantCurrencyPolicyService policies,
            @Value("${rm.currency.commerce-admission.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;
        this.policies=policies;
        this.enabled=enabled;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void lockKzMarket(UUID tenant) {
        if (!enabled) return;
        if (tenant==null) throw new IllegalArgumentException("CUR_TENANT_REQUIRED");
        // Reuse the canonical organization currency policy and its FOR UPDATE
        // lock; commerce never defines a second source of public KZT rules.
        try {
            policies.requirePublicKzCurrency(tenant,"KZT");
        } catch (IllegalStateException rejected) {
            throw new IllegalStateException("CUR_PUBLIC_POLICY_NOT_ACTIVE",rejected);
        }
    }

    /**
     * Non-public currency is allowed only for a configured internal sales
     * purpose. This does not authorize foreign-currency public offers.
     * Caller already holds the policy lock acquired before storefront rows.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public void assertDraftCurrency(UUID tenant,String currency) {
        if (!enabled || "KZT".equals(currency)) return;
        if (tenant==null || currency==null || !currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("CUR_INTERNAL_CURRENCY_INVALID");
        try {
            policies.requireCurrencyForPurpose(tenant,currency,"SALES_INTERNAL");
        } catch (IllegalStateException rejected) {
            throw new IllegalStateException("CUR_INTERNAL_CURRENCY_NOT_ALLOWED",rejected);
        }
    }

    public void assertKzt(String currency) {
        if (enabled && !"KZT".equals(currency))
            throw new IllegalStateException("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
    }

    /** Read-only public card check; does not acquire UPDATE locks. */
    @Transactional(readOnly=true)
    public void assertPublicRead(UUID tenant,String currency) {
        if(!enabled)return;
        assertKzt(currency);
        if(tenant==null)throw new IllegalArgumentException("CUR_TENANT_REQUIRED");
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM public.tenant_currency_policy p
            JOIN public.tenant_allowed_currency a ON a.tenant_id=p.tenant_id
            WHERE p.tenant_id=? AND p.market_country='KZ'
              AND p.public_price_currency='KZT' AND p.status='ACTIVE'
              AND a.currency_code='KZT' AND a.purpose='PUBLIC_PRICE'
              AND a.state='ENABLED'
            """,Integer.class,tenant);
        if(count==null || count!=1)
            throw new IllegalStateException("CUR_PUBLIC_POLICY_NOT_ACTIVE");
    }

    public boolean enabled() { return enabled; }
}
