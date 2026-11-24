package kz.rm.fx.auto;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** Called under the tenant storefront lock. Legacy drafts remain diagnostic only. */
final class FxAutoCommercialBinding {
    private FxAutoCommercialBinding(){}
    static void requireCurrent(JdbcTemplate jdbc,UUID tenant,UUID revision,boolean requireAll){
        Boolean valid=jdbc.queryForObject("""
            SELECT NOT EXISTS(
              SELECT 1 FROM crm.fx_auto_policy_revision p
              CROSS JOIN LATERAL jsonb_array_elements(CAST(p.payload AS jsonb)->'targets') t
              WHERE p.tenant_id=? AND p.id=? AND (
                (NOT (t ? 'commercialContextId') AND ?)
                OR ((t ? 'commercialContextId') AND NOT EXISTS(
                  SELECT 1 FROM crm.store_offer_commercial_context c
                  WHERE c.tenant_id=p.tenant_id AND c.offer_id=CAST(t->>'offer' AS uuid)
                  AND c.id=CAST(t->>'commercialContextId' AS uuid)
                  AND c.unit_code=t->>'unit' AND c.tax_basis=t->>'tax'
                  AND c.revision=(SELECT max(z.revision) FROM crm.store_offer_commercial_context z
                    WHERE z.tenant_id=c.tenant_id AND z.offer_id=c.offer_id)
                ))
              ))
            """.replace("t ? 'commercialContextId'","jsonb_exists(t,'commercialContextId')"),Boolean.class,tenant,revision,requireAll);
        if(!Boolean.TRUE.equals(valid))throw new IllegalStateException("FX_AUTO_COMMERCIAL_CONTEXT_STALE");
    }
}
