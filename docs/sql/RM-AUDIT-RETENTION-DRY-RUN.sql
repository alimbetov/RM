-- RM retention: PostgreSQL READ-ONLY preflight (no DELETE, DROP or ALTER).
-- Execute under authorized operations account; outputs contain counts only, no PII.

-- Approved retention period and estimated expired rows per tenant.
SELECT p.tenant_id,
       p.retention_years,
       p.enabled,
       p.approved_at IS NOT NULL AS approved,
       COUNT(a.id) FILTER (
         WHERE a.occurred_at < CURRENT_TIMESTAMP - make_interval(years => p.retention_years)
       ) AS expired_all,
       COUNT(a.id) FILTER (
         WHERE a.occurred_at < CURRENT_TIMESTAMP - make_interval(years => p.retention_years)
           AND NOT EXISTS (
             SELECT 1 FROM crm.audit_retention_hold h
             WHERE h.tenant_id=a.tenant_id AND h.event_id=a.id AND h.released_at IS NULL
           )
       ) AS eligible_for_cleanup
FROM crm.audit_retention_policy p
LEFT JOIN crm.audit_event a ON a.tenant_id=p.tenant_id
GROUP BY p.tenant_id,p.retention_years,p.enabled,p.approved_at
ORDER BY eligible_for_cleanup DESC;

-- Number of active legal holds by tenant.
SELECT tenant_id,COUNT(*) AS active_holds
FROM crm.audit_retention_hold
WHERE released_at IS NULL
GROUP BY tenant_id ORDER BY active_holds DESC;

-- Partition occupancy; do not infer that partition DROP is safe from row count alone.
SELECT tableoid::regclass::text AS leaf_partition,COUNT(*) AS retained_rows,
       MIN(occurred_at) AS oldest_event,MAX(occurred_at) AS newest_event
FROM crm.audit_event
GROUP BY tableoid ORDER BY leaf_partition;

-- Last retention attempts and batch counts.
SELECT id,started_at,finished_at,deleted_rows,batch_count,outcome
FROM crm.audit_retention_run
ORDER BY started_at DESC LIMIT 30;

-- If a tenant has NO approved policy, rows are deliberately retained.
-- Batch capacity is bounded by configured batch-size * max-batches per nightly run.
