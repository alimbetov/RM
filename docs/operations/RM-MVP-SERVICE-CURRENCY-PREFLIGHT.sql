-- RM-MVP W2: read-only populated PostgreSQL KZT-only preflight.
-- Execute with: psql -v ON_ERROR_STOP=1 -U <operator> -d <restored-uat-db> -f docs/operations/RM-MVP-SERVICE-CURRENCY-PREFLIGHT.sql
-- Does NOT mutate historical orders, accepted quotes, offers, or policies.
-- A nonzero exit blocks MVP admission pending explicit legacy cleanup/review.
BEGIN;
SET TRANSACTION READ ONLY;

-- No person-identifying data; aid tenant-by-tenant review.
SELECT
  sf.tenant_id,
  sf.id AS storefront_id,
  p.status AS currency_policy_state,
  p.market_country,
  p.public_price_currency,
  coalesce(allowed.state,'MISSING') AS kzt_public_permission,
  count(o.id) FILTER (WHERE o.state='PUBLISHED' AND o.currency<>'KZT') AS foreign_public_offers
FROM crm.store_front sf
LEFT JOIN public.tenant_currency_policy p ON p.tenant_id=sf.tenant_id
LEFT JOIN public.tenant_allowed_currency allowed
  ON allowed.tenant_id=sf.tenant_id
 AND allowed.currency_code='KZT' AND allowed.purpose='PUBLIC_PRICE'
LEFT JOIN crm.store_offer o ON o.tenant_id=sf.tenant_id
WHERE sf.state='PUBLISHED'
GROUP BY sf.tenant_id,sf.id,p.status,p.market_country,
         p.public_price_currency,allowed.state
ORDER BY sf.tenant_id,sf.id;

-- Unresolved historical standard orders are for manual DEC-FUL-01 provenance
-- review, not for automatic relabeling or stock mutation.
SELECT tenant_id,id,status,fulfillment_mode,created_at
FROM crm.sales_order
WHERE fulfillment_mode='STANDARD'
  AND status NOT IN ('CANCELLED','COMPLETED','REJECTED')
ORDER BY tenant_id,created_at,id;

DO $mvp_preflight$
DECLARE unsafe_count bigint;
BEGIN
    SELECT count(*) INTO unsafe_count
    FROM crm.store_front sf
    LEFT JOIN public.tenant_currency_policy p ON p.tenant_id=sf.tenant_id
    LEFT JOIN public.tenant_allowed_currency ac
      ON ac.tenant_id=sf.tenant_id AND ac.currency_code='KZT'
     AND ac.purpose='PUBLIC_PRICE'
    WHERE sf.state='PUBLISHED'
      AND (p.tenant_id IS NULL OR p.status<>'ACTIVE'
           OR p.market_country<>'KZ' OR p.public_price_currency<>'KZT'
           OR ac.state IS DISTINCT FROM 'ENABLED');
    IF unsafe_count>0 THEN
        RAISE EXCEPTION 'MVP_CURRENCY_POLICY_PREFLIGHT_HOLD: % unsafe published storefronts',
            unsafe_count;
    END IF;

    SELECT count(*) INTO unsafe_count
    FROM crm.store_offer o
    JOIN crm.store_front sf ON sf.tenant_id=o.tenant_id
    WHERE o.state='PUBLISHED' AND sf.state='PUBLISHED'
      AND (o.currency IS DISTINCT FROM 'KZT');
    IF unsafe_count>0 THEN
        RAISE EXCEPTION 'MVP_FOREIGN_PUBLIC_OFFERS_HOLD: % foreign published offers',
            unsafe_count;
    END IF;

    SELECT count(*) INTO unsafe_count
    FROM crm.sales_accepted_quote q
    JOIN crm.store_offer o ON o.tenant_id=q.tenant_id AND o.id=q.offer_id
    JOIN crm.store_front sf ON sf.tenant_id=o.tenant_id
    WHERE q.state='AVAILABLE' AND sf.state='PUBLISHED'
      AND q.currency IS DISTINCT FROM 'KZT';
    IF unsafe_count>0 THEN
        RAISE EXCEPTION 'MVP_FOREIGN_ACTIVE_QUOTES_HOLD: % unconsumed foreign quotes',
            unsafe_count;
    END IF;
END
$mvp_preflight$;
COMMIT;
