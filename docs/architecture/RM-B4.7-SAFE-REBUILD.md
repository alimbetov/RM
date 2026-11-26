# B4.7 — Safe Rebuild & Recovery

Internal only. Production admission remains HOLD.

- Caller supplies immutable tenant, generation UUID, calendar and date interval.
- Replay L1 and L2 from durable canonical receipts, with a bounded fact budget per consumer.
- Refresh only absent or outdated full calendar periods, with a bounded period budget.
- Every owner service owns its existing transaction; interruption never requires resetting a cursor or deleting receipts.
- Reconstruct progress from committed receipts and period stamps. No volatile checkpoint is authoritative.
- Independent multidimensional proof and current revision check are mandatory for READY; READY does not activate.
- Missing owner facts or rejected receipts are fail closed; use existing P2 backfill and source remediation, then retry.
- Activation is a separate B4.6 compare-and-switch with expected predecessor and proof.
- Previous active generation remains intact throughout rebuild; after source drift it is STALE, not falsely current. B4.8 must provide authorized L0 fallback; this package does not promise uninterrupted materialized reads.
- No auto-scheduler, public route, production database-role provisioning or ML admission.
- Qualification: bounded replay, service recreation, partial rollup, no-op retry, proof, explicit activation, preservation of previous generation and other tenant.
