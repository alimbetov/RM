-- RM Audit Partition Preflight (READ ONLY): run against TARGET PostgreSQL before promoting changeset 0018.
-- Never execute an audit table swap until zero inbound foreign keys and an approved backup/PITR window.
SELECT con.conname,
       con.conrelid::regclass::text AS referencing_table,
       con.confrelid::regclass::text AS referenced_table
FROM pg_constraint con
WHERE con.contype = 'f' AND con.confrelid = 'crm.audit_event'::regclass;
-- REQUIRED RESULT: zero rows. Otherwise abort the release, redesign inbound references.

SELECT count(*) AS source_row_count,
       min(occurred_at) AS earliest_event,
       max(occurred_at) AS latest_event
FROM crm.audit_event;

SELECT date_trunc('year',occurred_at) AS event_year, count(*) AS events
FROM crm.audit_event
GROUP BY date_trunc('year',occurred_at)
ORDER BY event_year;

SELECT tenant_id, count(*) AS events
FROM crm.audit_event
GROUP BY tenant_id
ORDER BY events DESC
LIMIT 20;

-- Inspect current indexes and foreign-key constraints before deciding whether PK(tenant,occurred_at,id)
-- is compatible with application queries and any external integrations.
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'crm' AND tablename = 'audit_event'
ORDER BY indexname;

-- After applying the migration, verify row count against the captured preflight count,
-- confirm 16 leaves, and test partition pruning under prepared JDBC workload.
-- SELECT * FROM pg_partition_tree('crm.audit_event'::regclass);
-- SELECT count(*) FROM crm.audit_event;
