# RM-R2.1 — Commercial Core Hardening & E2E Admission Specification

**Date:** 2026-11-22  
**Repository:** `alimbetov/RM`  
**Target branch:** `base_dev` (no new branch)  
**Baseline SHA:** `8acb99a3a752f4912a22f9574c239c8854dc2994`  
**Status:** SPECIFIED / IMPLEMENTATION PENDING / PRODUCTION HOLD  
**Priority:** P0 correctness and evidence; P1 commercial read model; user E2E separately gated.

## 1. Objective and existing evidence

Close the remaining commercial-core gaps identified by the 2026-11-22 maturity review without resetting already qualified work. Current baseline is 15 bounded contexts (CRM Interaction shown separately as a sixteenth line), eight maturity-3, four maturity-2, four maturity-0, none at maturity-4/5.

At baseline, service-level `CATALOG → STOREFRONT → CHECKOUT → SALES → INVENTORY → RECONCILIATION` was exercised under synthetic identity (commercial PostgreSQL 5/5). H2 284 passed/85 skipped, PostgreSQL 351 passed/18 skipped were reported in CI for current SHA. The SALES populated migration qualification was reported only for a preceding SHA and MUST NOT be inherited as same-SHA evidence. These are audit inputs, not fresh executions performed by this specification.

**Normative sources:** [RM Platform Standard R2](../architecture/RM-PLATFORM-STANDARD-R2.md), [SVC completion](RM-SVC-MATURITY-COMPLETION-R2.md), [SALES 12 passes](RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md), [PASS 09 review](../reviews/RM-SALES-PASS-09-IAM-RBAC-ACCESS-BOUNDARY-2026-11-22.md), [Durable reconciliation](../reviews/RM-INVENTORY-R1.5B-DURABLE-RECONCILIATION.md). Review evidence precedes new tests only as historical snapshot, not current proof.

## 2. Invariants and exclusions

1. IAM owns global user identity, tenant status and RBAC; CATALOG owns SKU; STOREFRONT owns mutable offer state/current offer price; SALES owns accepted immutable order terms and state transitions; INVENTORY owns stock quantity/reservations/movement ledger; AUDIT stores protected action evidence. No shadow owner tables, cross-domain write shortcuts or duplicated prices as authorities.
2. One order has one seller tenant. Buyer is not required to be seller-tenant member. Actor must be verified per trusted entry policy; seller authorization must stay distinct from buyer admission. Tenant A/B/AB isolation and object ownership are mandatory.
3. Checkout shall retain immutable offer revision, SKU, quantity, accepted unit price and currency. Subsequent price changes never rewrite existing orders. Reservation happens on seller confirmation; no double debit on replay.
4. `COMPLETED` currently means inventory fulfillment, not payment or delivery.
5. Command failures fail closed; no external provider/KMS unavailability may be silently treated as active tenant or authorized actor. Never log secrets/PII.
6. No new microservice, generalized policy engine, public controller as part of P0, Redis, valuation computation, financial booking, new UI product surface, or production release. Do not rewrite applied Liquibase changesets; migrations additive and rollback/recovery tested. Changes exclusively on existing `base_dev`; no merge to `main`.

## 3. Work package RM-SEC-09A — Seller tenant checkout admission (P0)

**Problem:** `AutoOrderService` checks catalog/offer/storefront readiness but does not require active seller workspace state. Suspended sellers may continue to receive new orders while storefront remains published.

**Functional contract:**
- Every *new* order creation must resolve seller workspace from the authoritative IAM workspace state, not client-supplied status or cached view.
- Admit only explicitly designated active/operational workspace state; reject suspended, disabled, deleted, missing or otherwise non-operational seller state. Map exact existing enum values before coding; do not invent status values.
- Check occurs before order/line creation or any durable side effect. Concurrency with `suspend` must be fenced by the same existing IAM workspace lock/transaction protocol used for tenant commands: serializable business outcome—either checkout is admitted first or suspension wins and checkout is denied. Record actual lock order and ensure no deadlocks with SKU/offer/inventory locks.
- Buyer must not be required to have seller-tenant membership; authorization to view/purchase must be distinct from permission to manage the seller workspace.
- Existing accepted orders remain intact after suspension. Decide separately (do not silently change) whether and which existing seller fulfillment commands are allowed while suspended. Exact idempotent replay of an already committed order must not create another order; document whether replay returns the original receipt under inactive seller, with strict payload matching and caller authorization.
- Avoid online-status TOCTOU: a standalone precheck without transaction serialization is insufficient. Do not silently auto-pause storefronts when tenant suspends.
- Denial type must be stable at service level, with transport HTTP mapping deferred until qualified public adapter exists.

**Required qualification:** ACTIVE seller success; SUSPENDED/DELETED/missing reject; A/B seller mismatch; buyer with no seller membership allowed; checkout × suspend race both lock orders; checkout × offer-pause race; failed admission yields zero new orders, lines, reservations, movement, audit-of-success and outbox; existing accepted snapshot unaffected; exact replay unchanged.

**Acceptance:** named H2 smoke and real PostgreSQL race tests verify single consistent linearization and no orphaned writes.

## 4. Work package RM-STORE-09B — Canonical offer/storefront facade semantics (P0)

**Problem:** `StorefrontManagementService.pause` and `SellerOfferService.changeStorefrontState(...,PAUSED)` require different grant sequences (PUBLISH alone vs MANAGE then PUBLISH). Repeated unchanged-price behavior may diverge.

**Contract:**
- Inventory actual method-to-grant matrix of `StorefrontManagementService`, `SellerOfferService` and `StorefrontOfferService` before implementation.
- Canonical application command for each storefront/offer state transition. Legacy facade strictly delegates; it MUST NOT independently persist storefront, offer, price or audit changes.
- Adopt one policy for equivalent transition across both entry points. Proposed explicit policy: `PUBLISH` for PUBLISHED↔PAUSED (including pause), `MANAGE` for metadata CRUD, `MANAGE` for price edit, with separate `PUBLISH` for publication. If existing normative grants disagree, record a policy decision first; do not silently broaden grants.
- CAS requires expected version on state-changing operations. A stale version fails deterministically, including requests to set an already-current value when version is stale; matching version + exact same state/price is a well-defined no-op (no version increment, no mutation audit). Price equality is decimal numeric equality after canonical precision/currency validation; decide canonical scale from existing Money contract. Changed value increments version exactly once and creates exactly one audit action.
- A single tenant-scoped locking strategy across both facades; check membership, permission, object ownership and state before mutation. Revocation racing with mutation must produce one serialized permitted/denied outcome.
- Offer transition cannot publish while storefront is paused. Existing CATALOG archive and inventory blockers remain intact.
- Verify legacy API compatibility; where signatures differ, normalize through adapter without additional permission.

**Tests:** permission matrix (`PUBLISH` only, `MANAGE` only, both, none, revoked, foreign tenant); both facades × all legal/illegal transitions; no-op stale/current version; same-price numeric scale; concurrent CAS one winner; exactly one persisted audit event; no write on deny; offer publish while paused denied.

## 5. Work package RM-INV-16 — Concurrency-correct durable stock reconciliation (P0)

**Problem:** `crm.inv_reconciliation_run` currently advances over keysets while reading balance, active reservations and ledger in separate `READ COMMITTED` statements. Concurrent movement may introduce false mismatches; `recorded_at,id` is not a defensible global posting order.

**Design decisions and staged implementation:**
- **R16.1, consistency of a single position:** choose and document an explicit PostgreSQL isolation/locking strategy. Recommended first qualification: lock the position's authoritative balance/serializing entity for the duration of one reconciliation transaction, in the same lock order as writers, then read reserved, movements and balance in one coherent transaction. If a lock cannot cover the absence of a position/key or writers do not respect it, use an independently verifiable snapshot (PostgreSQL `REPEATABLE READ` per transaction) plus conflict/retry semantics; never assume multiple `READ COMMITTED` SELECTs are one snapshot. Prove correctness of whichever approach is implemented with actual writer/concurrent-reader tests.
- **R16.2, movement ordering:** add an immutable monotonic posting sequence scoped to the canonical stock position (tenant, SKU, location), or equivalent database-issued ordering guaranteed to agree with serialization of stock commits. Sequence assignment must occur under the actual position lock in the same transaction as balance/ledger update. Sequence gaps may exist on rollback if using external DB sequences; gaps must not imply corruption. Preserve legacy immutable events; use additive Liquibase and explicitly designate historical rows as `ORDER_UNVERIFIED` / `BASELINE_UNVERIFIED` until an independently justified opening balance/order is established. Do NOT reconstruct a false historical posting order by sorting `UUID` or timestamp, and do not rewrite immutable financial/stock facts.
- **R16.3, cross-page consistency:** durable `advance` currently does NOT offer an atomic tenant-wide as-of view. Document two proof levels: `DIAGNOSTIC` per-position consistent check versus `CERTIFIED` tenant-wide as-of run. A `CERTIFIED` run requires a validated watermark/protocol that prevents missing keys before cursor and relates each finding to a consistent cut; do not mark `CERTIFIED` from a set of independent page transactions without this proof. Implementing the certification protocol may be deferred; the service must expose status honestly.
- Preserve existing `RUNNING / COMPLETED / FAILED`, cursor/checkpoint, bounded page and finding semantics. Consider additive `consistency_level` and watermark provenance rather than overloading `COMPLETED`.
- Findings are diagnostic and immutable per run; no stock auto-repair. `NO_MOVEMENT_BASELINE` remains an unresolved classification, not automatically corruption.
- Prevent concurrent `advance` of the same run by fencing/row lock; crash or retry must not duplicate findings or lose checkpoint. Keep all run/findings tenant-scoped and restricted by `INVENTORY_STOCK_READ`.
- Benchmark long ledger chains and bound statement/transaction behavior. Performance targets only become release gates after measured baseline, not arbitrary invented p95 figures.

**Deterministic PostgreSQL tests:** transfer/receive/confirm/cancel (as applicable) racing reconciliation with barriers at each SQL read boundary; both commit orders; multiple JVM writer simulation/independent connections; same timestamp concurrent events; rollback and retry; missing-balance and late key insertion; concurrent `advance`; 500+ movements with bounded fetch; false-positive=0 on valid stable/serialized histories; real injected chain or reservation corruption detected; historical unverifiable lines never misrepresented as certified.

**Acceptance:** no false corruption findings from legal concurrent writes in the *advertised* consistency mode; monotonic posting contract verified for new records; provenance for legacy; exact-SHA H2+PG+populated migration qualification.

## 6. Work package RM-QA-01B — Single-head evidence manifest (P0)

The CI qualification must produce a machine-readable manifest, e.g. `build/qualification/rm-commercial-admission.json`, uploaded as a workflow artifact. All required jobs reference the identical git SHA and migration set. Required evidence fields:
- `repository, branch, commitSha, migrationFingerprint, workflowRunUrl, jobName, jobConclusion, startedAt, completedAt`;
- `testSuite, testsRun, passed, failed, skipped, explicitSkipReason`;
- `database: H2 | PostgreSQL | populated-PostgreSQL`, runner environment and relevant runtime flags;
- named required gates: H2 baseline, PostgreSQL baseline, SALES 08.1–08.4 commercial 5/5 plus new regression suites, IAM/revoke, RustFS-specific jobs, offer/storefront permissions and CAS, tenant status admission, inventory concurrency, SALES populated migration, schema change migration and restart/recovery as applicable;
- `overallAdmission = PASS` only if all required jobs succeeded at identical `commitSha`; missing/skipped required tests are FAIL, not implicit passes. Optional skips explicitly distinguished from mandatory suites.
- The aggregator MUST fail closed for absent artifact/job, wrong SHA, partial matrix, inaccessible log or unqualified migration. Historical successful workflow runs can be linked for context but never satisfy same-SHA mandatory gate.

Do not fabricate test counts or claim populated migration GREEN based on earlier commit. Protect integrity of manifest through CI-generated artifact, test report hashes and immutable run links.

## 7. Work package RM-SALES-10A — Bounded seller detail and buyer history (P1)

- Seller tenant-authorized detail query by tenant+orderId returns order header, immutable line snapshots (SKU and accepted unit price/currency), order version/status, reservations/fulfillment references where available, and chronological status events (actor/type/timestamp with no unnecessary PII).
- History must derive from persisted authoritative transitions; no fabricated events from current state. If legacy orders have no historical events, explicitly indicate history unavailable rather than synthesize.
- Buyer `mine` history uses bounded keyset pagination (default 20, maximum 100 proposed; align existing standardized paging contract), stable sort `created_at DESC, id DESC`, deterministic cursor and tenant-safe ownership filter. Separate seller listing and buyer projections; buyer must never receive foreign order by supplied UUID or cursor.
- No unbounded `findAll`, no N+1 line/transition fetch; count/pagination must not leak foreign-tenant records. Explicit permissions for seller read vs verified buyer ownership; no public unauthenticated history.
- H2/PG tests: A/B/AB membership, forged ID/cursor, revocation, blocked actor, multiple pages with identical timestamps, empty page, deleted/changed live offer while immutable order history remains correct.

## 8. Work package RM-STORE-10A — Offer price history (P1)

- Persist canonical immutable price transition event `offerId, tenantId, oldAmount, newAmount, currency, oldVersion, newVersion, actorId, occurredAt, actionId`; derive from same transaction and accepted CAS update. Avoid plaintext buyer PII and reuse existing AUDIT capabilities where they already meet requirements.
- Append exactly one event for successful change, zero for matching no-op or denied/stale command. Historical events need stable ordering based on persisted transition version, not wall-clock alone.
- Read access tenant-scoped with seller read permission and bounded pagination; no events exposed across tenants. Historical order price remains independent of current offer history.
- Existing price audit entries without old/new amounts are `DETAIL_UNAVAILABLE`, not retroactively invented transitions. Migration additive; rollback of failed price edit removes event as well.

## 9. Work package RM-E2E-04 — Level-4 user journey gate (P1 / separate release work)

Start only after P0 same-head GREEN. Exercise a deployed, supported trust boundary—not direct service calls with synthetic `VerifiedActor`: real OIDC/JWT identity and seller RBAC, buyer login/onboarding as applicable, catalog publish, storefront/offer creation and publication, checkout via supported external entry, seller order read/confirm, inventory reserve/fulfill and reconciliation read. Include tenant suspension before checkout (deny), resumed flow per policy, permission revoke during mutation, customer/buyer own history, price change after accepted order, duplicate request and crash/retry.

Every step must have traceable request ID, transport response, authoritative DB state and negative-tenant proof. Real email verification/delivery and PII migration must pass their independent gates before any claim that onboarding is production-ready. No implied public HTTP support until adapters exist and are separately tested.

**Level 4** only after a complete representative authenticated journey and negative cases pass in an integrated environment. **Level 5** requires separate operations/security/performance/recovery qualification; never inferred from Level 4.

## 10. Delivery order and dependency gates

| Wave | Scope | Exit criterion |
|---|---|---|
| W0 | Snapshot current HEAD, source/DDL/permission mapping; decide conflicting role semantics | Change inventory and decision record |
| W1 | RM-SEC-09A; RM-STORE-09B | H2+real PG negative/race GREEN, no regressions in SALES PASS 08 |
| W2 | RM-INV-16 position snapshot & new posting order; legacy policy | Real PG concurrent writer/read proof, additive populated migration |
| W3 | RM-QA-01B evidence aggregation | All mandatory jobs + populated SALES migration successful on ONE exact SHA |
| W4 | RM-SALES-10A and RM-STORE-10A | Tenant-safe bounded reads; durable history, full rerun same SHA |
| W5 | RM-E2E-04 separate qualification | Actual authenticated external journey; Level 4 only if proven |

Parallel work allowed when independent, but no signoff before same SHA reconvergence. Never downgrade the prior five commercial integration tests or change accepted price/reserve/double-debit semantics.

## 11. Required delivery artifacts

1. Code changes in existing domain modules with method/permission/SQL transaction trace.
2. Additive Liquibase files and populated PostgreSQL migration/restart proof where schemas change.
3. Named unit, H2, PostgreSQL and concurrent interleaving tests; logs containing exact SHA, numbers and skip reasons.
4. Updated `RM-SEC-09`, `RM-SALES-09`, inventory reconciliation review and `RM-QA-01` evidence registry, referencing canonical implementation rather than duplicating conflicting policy.
5. Updated maturity matrix with levels unchanged until new evidence; explicit disposition of known residual risks.
6. Final concise release record: SHA, CI URLs, tests, migrations, known gaps, Level-4 decision, `PRODUCTION HOLD` until separately authorized.

## 12. Explicit stop conditions

Stop promotion and mark BLOCKED if: seller suspended can checkout; facade permission differs for the same action; a reconciliation run claims certified state from mixed `READ COMMITTED` reads; legacy movement chronology is invented; two checkout/stock updates survive one idempotency key; foreign-tenant data/count leaks; any mandatory job is skipped/missing or from different SHA; populated migration remains older-SHA only; or an HTTP/OIDC claim rests only on direct service tests.

**Decision:** development may proceed in `base_dev`, but production admission remains HOLD.
