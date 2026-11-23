# RM FX/CUR technical review — 2026-11-23

**Branch:** `base_dev`; **main:** unchanged. **Production:** HOLD.
**Reviewed scope:** FX-R1A.3 trusted NBRK, CUR-R1 policy, CUR-R2 preview.
**Evidence standard:** only GitHub Actions conclusions for the exact committed head prove qualification.

## Reviewed implementation and defects

| Finding | Severity | Resolution / disposition |
|---|---|---|
| Spring CGLIB proxy could not subclass final `NbrkRateSnapshotReader` | P0 / observed on ace0bcf2 | FIXED: removed `final`; FX H2+PG passed at bc547acf |
| Legacy FX test required rejecting all official NBRK rows | P0 / intermediate revision | FIXED model: immutable NBRK snapshot is admitted only with originating immutable run FK; old direct fake insert fails |
| No bounded transient HTTP retries | P1 | FIXED: 3 attempts for 429/500/502/503/504 and transient IOException; no retry for SSL, redirect, bad payload or other 4xx |
| No transport adversarial tests | P1 | FIXED: 7 deterministic tests covering fixed URI, redirect, retry, 400, TLS failure, compression/size, IO |
| FX source revision not traceable to a specific ingestion batch | P1 | FIXED: additive changeset 0128 + source_import_run_id FK, immutable run and rate revision |
| CUR-R1 owner could not enable/disable currency by purpose | P1 | FIXED: service method, CAS and event; 7 test cases; dedicated currency CI passed at 5240711f |
| CUR-R1 public commerce policy across publication, QR, checkout, quote | P0 | OPEN: only internal admission port, NOT integrated across all paths. Do not assert CUR-R1 complete |
| CUR-R1 customer FX provider preference and source availability | P1 | OPEN: source trust NBRK vs synthetic MOCK separated globally; tenant provider preference not yet qualified |
| CUR-R2 exact deterministic proposed SKU prices | P1 | STARTED: pure `SkuFxCandidateCalculator`, 9 mathematical cases; no offer writes |
| CUR-R2 approval, stale-offer CAS, negotiation and price snapshots | P0 | OPEN: do not publish until checkout/price lock DAG, account permission and append-only audit proven |
| Live NBRK end-to-end TLS and weekend/holiday semantics | P1 | OPEN: simulated transport tests do NOT establish live upstream compatibility or deployed egress controls |

## Immutable business invariants
1. `DEC-FUL-01` is retained: FX is reference evidence, fulfillment executes only existing accepted terms.
2. No HTTP/controller, scheduler, payment, inventory valuation, order or ledger mutations were added.
3. Global NBRK source rows are never tenant-controlled, no MOCK fallback and no implicit rate-based commerce price.
4. KZT public market policy, seller approval and legal assessment remain gates before any SKU pricing activation.
5. Existing `crm.store_offer_price_revision` is the canonical commercial price revision, not a new parallel price catalog.

## Qualification matrix
- FX-R1A.3 persistence: 15 cases; parser: 8; transport: 7; legacy FX persistence: 6; CUR-R2 pure calculator: 9. Target combined suite: 45 tests (H2 2 approved PG-only skips, PostgreSQL 0 skips).
- CUR-R1 policy: 7 cases (H2 1 approved PG-only skip, PostgreSQL 0 skips).
- Exact SHA: all mandatory RM workflows and required evidence artifacts, not just the FX/CUR targeted jobs.
- Full source/transport coverage remains incomplete for real HTTPS cert chain, corporate egress, source availability and calendar rules.
- Populated PostgreSQL upgrade must preserve MOCK evidence, immutable historical money and existing order/fulfillment records.

## Remaining implementation order
**FX-R1A.3 QA:** require successful target workflow for current head; execute live read-only NBRK contract smoke on restricted network with OS/JVM truststore, explicit operator approval; approved effective date/holiday policy; failure telemetry and provenance fidelity.

**CUR-R1C:** tenant provider preference/availability gate, service policy mutation request-key idempotency, privacy/permission checks, then publication / QR / quote / checkout admission with verified lock DAG and dual-order PostgreSQL races. No silent migration of foreign historical published offers; classify and quarantine.

**CUR-R2:** contract for fixed KZT merchant price vs internal FX anchor; persist immutable seller policy and calculation evidence; MANUAL / SUGGESTED only, explicit seller approval and CAS to existing offer price revision. Snapshot KZT in accepted order. AUTO remains STOP until a separate opt-in and independent qualification.

## Release disposition
No production/main admission. Targeted CI success on an earlier SHA is evidence only for that SHA. At time of writing, the current `base_dev` head has not been independently qualified by the full exact-SHA matrix.
