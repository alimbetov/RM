# RM-IC-SINGLE-ENGINE-CONTRACT-R1 — One analytical engine; evidence is not calculation

2026-11-27 | OWNER AGREED IN CONVERSATION | DESIGN CONTRACT | IMPLEMENTATION BY ADMITTED SLICE | PRODUCTION HOLD
Normative clarification of D06E.4, D06C, CODE-03, D08 and S1–S6. No new domain or engine.

## Responsibility and data flow
Owner business services -> Canonical Fact V2 -> existing L1 / L2 / L3 projections -> authorized analytical read -> B5 interpretation -> report payload / XLSX / PDF / Radar.
DQR independently qualifies source completeness, coverage, revision, calendar and evidence; it is a control plane, not a second business calculation engine. Independent source oracles may read primary tables to prove correctness and zero, but only in bounded qualification/reconciliation jobs, not each report read. A qualified value must come from the authorized existing metric path with matched evidence, not an oracle result substituted for the aggregate.
Reuse AuthorizedAnalyticalReadService, L3DerivedSeriesService.readPeriods, MultidimensionalCoverageService, existing generation lifecycle and canonical/revision contracts. Existing qualified route for closed periods preferred; L0 fallback only where existing approved semantics explicitly permit it, provisional/open values not READY. B4 FREEZE; no mutation of ACTIVE generations.
BOOKED_ORDERS already registered in D06E.4: L3 + int_l3_cell_order exact order_id union, tenant/group/SKU permitted; no booked geography; distinct never summed across cells. PLACED_ORDERS alias requires independent population/basis/method parity before admission. Existing proof of L2/L3 parity alone does not establish historical completeness.

## Quality binding
Before any runtime admission, define minimal reuse-first evidence linkage: tenant, authorized scope, metric/method, calendar version, half-open period, source revision, active generation and coverage proof, asOf, invalidation and provenance. These are required semantics, not a prescription for a new ledger/table. No caller-injected proof, no retrospective claim based only on owner row parity. Qualified zero requires independent closure/completeness and exact aggregate zero, not absent rows. Stale/foreign/missing evidence blocks qualification. Late data invalidates affected results under existing revision/rebuild contract; targeted partition invalidation requires actual contract proof, not assumption.
Do not hold operational transactions while rendering reports. Historical bulk reads reuse the authorized set-based L3 path; no new report-specific raw SQL or full-history scan. D06C 3h cumulative current-day L0 provisional contract preserved until separately approved L2 refresh integration; no blanket full history rebuild or ACTIVE mutation. Nightly closed-period reports use admitted historical aggregate route. No change to B5 roadmap, DAG, product scope or D08/production HOLD.

## Acceptance
Demonstrate exact aggregate vs independent source oracle (including duplicate IDs and true zero), revision/generation mismatch rejection, tenant/scope/calendar constraints, late-event invalidation, and that ordinary report reads do not execute independent source scans. Prove any needed operational locks minimal and qualified; do not optimize redundant computations. Approve concrete gap and minimal implementation before any new schema/authority or service path.
