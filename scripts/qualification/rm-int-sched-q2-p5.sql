-- RM-INT-SCHED-R1.3-Q2-FINAL / P5: exploratory SQL plan experiment.
-- Run with psql in a disposable, migrated, populated PostgreSQL clone.
-- NOT a migration, not a full P1-P8 qualification. No production execution.
-- Supply -v isolated_fixture=yes -v tenant_id=... -v day_start=... -v cutoff=...
-- Day start and cutoff must be offset-qualified timestamps from the same business day.
\set ON_ERROR_STOP on
\if :{?isolated_fixture}
\else
  \echo 'Refusing: require -v isolated_fixture=yes on a disposable test database'
  \quit 2
\endif
SELECT :'isolated_fixture' = 'yes' AS fixture_confirmed \gset
\if :fixture_confirmed
\else
  \quit 2
\endif

-- A single transaction prevents persistence of experimental indexes. Run against
-- quiescent fixture data, with fresh ANALYZE statistics prepared before this script.
BEGIN ISOLATION LEVEL REPEATABLE READ;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '300s';
SELECT current_database(), version(), current_setting('TimeZone'), current_setting('work_mem');
SELECT :'day_start'::timestamptz < :'cutoff'::timestamptz AS valid_bounds \gset
\if :valid_bounds
\else
  \echo 'Invalid half-open interval'
  ROLLBACK;
  \quit 2
\endif
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes WHERE schemaname='crm' AND tablename IN
('sales_order_status_event','sales_order_line','int_source_revision_event','int_l2_contribution',
 'int_l3_cell','int_l3_cell_order','int_analytics_job_run','int_intraday_result','int_dim_coverage_proof')
ORDER BY tablename,indexname;

-- Do not hide ambiguity by only grouping events inside the time window.
CREATE TEMP VIEW q2_p5_ambiguity AS
WITH candidates AS (
 SELECT DISTINCT order_id FROM crm.sales_order_status_event
 WHERE tenant_id=:'tenant_id'::uuid AND event_type='PLACED'
 AND occurred_at>=:'day_start'::timestamptz AND occurred_at<:'cutoff'::timestamptz
)
SELECT EXISTS (
 SELECT 1 FROM candidates c
 WHERE (SELECT count(*) FROM crm.sales_order_status_event e
 WHERE e.tenant_id=:'tenant_id'::uuid AND e.order_id=c.order_id AND e.event_type='PLACED')<>1
 OR NOT EXISTS (SELECT 1 FROM crm.sales_order_line l
 WHERE l.tenant_id=:'tenant_id'::uuid AND l.order_id=c.order_id)
) AS ambiguous;

CREATE TEMP VIEW q2_p5_metric AS
SELECT CASE WHEN count(*)>0 THEN count(DISTINCT order_id)::numeric END AS value
FROM crm.int_l2_expected_source
WHERE tenant_id=:'tenant_id'::uuid AND stream_code='BOOKED'
AND event_at>=:'day_start'::timestamptz AND event_at<:'cutoff'::timestamptz;

-- Independent event oracle: count all visible PLACED events per order before
-- applying the cutoff, with existence of a tenant-matched line.
CREATE TEMP VIEW q2_p5_oracle AS
SELECT CASE WHEN count(*)>0 THEN count(*)::numeric END AS value
FROM (
 SELECT e.order_id
 FROM crm.sales_order_status_event e
 WHERE e.tenant_id=:'tenant_id'::uuid AND e.event_type='PLACED'
 GROUP BY e.order_id
 HAVING count(*)=1 AND min(e.occurred_at)>=:'day_start'::timestamptz
 AND min(e.occurred_at)<:'cutoff'::timestamptz
) e WHERE EXISTS (SELECT 1 FROM crm.sales_order_line l
 WHERE l.tenant_id=:'tenant_id'::uuid AND l.order_id=e.order_id);

SELECT NOT ambiguous AND (m.value IS NOT DISTINCT FROM o.value) AS parity_ok,
 m.value AS metric_value,o.value AS oracle_value,ambiguous
FROM q2_p5_metric m CROSS JOIN q2_p5_oracle o CROSS JOIN q2_p5_ambiguity \gset
\if :parity_ok
\else
  \echo 'Baseline ambiguity or mathematical mismatch; stop and inspect fixture'
  ROLLBACK;
  \quit 3
\endif

CREATE TEMP TABLE q2_p5_baseline AS SELECT * FROM q2_p5_metric;
CREATE TEMP VIEW q2_p5_parity AS
SELECT NOT a.ambiguous AND (m.value IS NOT DISTINCT FROM o.value)
 AND (m.value IS NOT DISTINCT FROM b.value) AS parity_ok
FROM q2_p5_metric m CROSS JOIN q2_p5_oracle o
CROSS JOIN q2_p5_ambiguity a CROSS JOIN q2_p5_baseline b;

-- Warmup plus one captured plan per query per arm. Repeat full experiments
-- with controlled cache/order and collect raw output; one plan is not p95/p99.
\echo 'BASELINE'
SELECT * FROM q2_p5_metric;
SELECT * FROM q2_p5_ambiguity;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) SELECT * FROM q2_p5_metric;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) SELECT * FROM q2_p5_ambiguity;

\echo 'CANDIDATE A: time covering'
CREATE INDEX q2_p5_exp_placed_time ON crm.sales_order_status_event
(tenant_id,occurred_at) INCLUDE(order_id) WHERE event_type='PLACED';
SELECT pg_relation_size('crm.q2_p5_exp_placed_time'::regclass) AS index_bytes;
SELECT * FROM q2_p5_parity \gset
\if :parity_ok
\else
  ROLLBACK;
  \quit 3
\endif
SELECT * FROM q2_p5_metric;
SELECT * FROM q2_p5_ambiguity;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) SELECT * FROM q2_p5_metric;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) SELECT * FROM q2_p5_ambiguity;
DROP INDEX crm.q2_p5_exp_placed_time;

\echo 'CANDIDATE B: order partial'
CREATE INDEX q2_p5_exp_placed_order ON crm.sales_order_status_event
(tenant_id,order_id) WHERE event_type='PLACED';
SELECT pg_relation_size('crm.q2_p5_exp_placed_order'::regclass) AS index_bytes;
SELECT * FROM q2_p5_parity \gset
\if :parity_ok
\else
  ROLLBACK;
  \quit 3
\endif
SELECT * FROM q2_p5_metric;
SELECT * FROM q2_p5_ambiguity;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) SELECT * FROM q2_p5_metric;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) SELECT * FROM q2_p5_ambiguity;

\echo 'CANDIDATES A+B'
CREATE INDEX q2_p5_exp_placed_time ON crm.sales_order_status_event
(tenant_id,occurred_at) INCLUDE(order_id) WHERE event_type='PLACED';
SELECT * FROM q2_p5_parity \gset
\if :parity_ok
\else
  ROLLBACK;
  \quit 3
\endif
SELECT * FROM q2_p5_metric;
SELECT * FROM q2_p5_ambiguity;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) SELECT * FROM q2_p5_metric;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) SELECT * FROM q2_p5_ambiguity;
ROLLBACK;
\echo 'Experiment complete; no index admitted. Compare captured plans and run full qualification.'
