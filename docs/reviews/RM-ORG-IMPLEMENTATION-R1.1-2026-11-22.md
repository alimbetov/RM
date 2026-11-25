# RM — ORGANIZATION ORG-R1.1: implementation evidence and qualification gates

**Date:** 2026-11-22. **Branch:** `base_dev`. **Status:** IMPLEMENTATION COMMITTED / FULL QUALIFICATION PENDING. **Business scope:** chosen after 07-pass interim closure; SALES / AutoOrder code NOT modified in this series. **Feature Freeze:** service layer only, no HTTP/controllers, no merge main.

## 1. New actual source (not just a specification)

| File | Implemented behavior |
|---|---|
| `organization/dto/LocationCreateRequest.java` | Typed create profile: type/name/KATO/address, immutable request |
| `organization/dto/LocationUpdateRequest.java` | Full edit profile and mandatory `expectedVersion`, no tenant/status/type mutation |
| `organization/LocationFields.java` | Shared `strip()+Unicode NFC`, limits name 200 codepoints/address 500, blank address=null; KATO strict ASCII `[0-9]{2,16}`, malformed surrogate/control/bidi rejection |
| `organization/OrganizationLocationEntity.java` | Explicit mutators `replaceProfile/moveTo`, JPA `@Version`, timestamp only on real mutation; immutable ID/tenant/type/createdAt |
| `organization/OrganizationLocationRepository.java` | New tenant+ID scoped `@Lock(PESSIMISTIC_WRITE)` repository method |
| `inventory/InventoryLocationUsagePort.java` | Tenant/location `EXISTS` blockers: onHand, reserved, ACTIVE reservation, OPEN stocktake; history (movement/reservation/stocktake) read-only. `MANDATORY` existing TX, no stock/record locks |
| `organization/OrganizationLocationService.java` | Authorize WRITE → scoped row lock → CAS → mutation/usage/geo → JPA flush → `crm.audit_event` same TX. Create remains compatible with legacy signature |
| `organization/AuthenticatedOrganizationLocations.java` | VerifiedActor facade adds typed create/update/deactivate/reactivate/close; keeps original create/get/search |
| `test/.../OrganizationLocationLifecycleQualificationTest.java` | 6 integration scenarios: Unicode/no-op/CAS/audit; free-stock pause/closure/real inventory; reserved/OPEN stocktake blocker; changed-KATO/history/tenant; rollback; PostgreSQL concurrent deactivate×receive |

**Service status machine in actual code:**
- New create = `ACTIVE`, rowVersion 0, event `ORG_LOCATION_CREATED`.
- `ACTIVE → INACTIVE` allowed with positive unreserved `onHand` but only when reserved=0, no ACTIVE reservation, no OPEN stocktake.
- `INACTIVE → ACTIVE` allowed without automatic stock changes and without revalidating unchanged GEO KATO (stale GEO reference does not trap stock).
- `INACTIVE → CLOSED` only if no positive onHand/reserved, no ACTIVE reservation, no OPEN stocktake. `CLOSED` terminal, physical delete absent.
- Same-state call with current `expectedVersion` is no-op (no event/version bump); stale expected version is rejected even for same-state call.
- `update` changes only name/KATO/address (full replacement) on non-CLOSED item. Changed KATO is denied when any remaining physical usage or warehouse movement/reservation/stocktake history exists. New KATO requires current GEO policy validation; changing only name/address does not revalidate unchanged deprecated KATO.
- Current actor and tenant required; only registered `ORGANIZATION_LOCATION_WRITE` permission, via transaction-bound `TenantAuthorization.requireForCommand`. No admin bypass.
- No new table or new lock manager. Reuse `crm.audit_event` with explicit `ORG_LOCATION_*` action names.

## 2. Concurrency correctness assumptions to prove in PostgreSQL

**Lock order for ORG:** `IAM actor FOR SHARE → tenant FOR SHARE → org_location (tenant,id) FOR UPDATE → INVENTORY read-only EXISTS → JPA UPDATE → audit`. Inventory standalone `location→catalog SKU→stock`, Q7 transfer `both locations→catalog SKU→stock`; SALES confirm `order→location→SKU→balance/reservation`. ORG never locks stock/order/stocktake headers itself, avoiding the known reverse-lock cycle.

Proof still needed:
1. Real two-connection `receive/transfer/stocktake/reserve×deactivate`, including both possible COMMIT orders and full rollback.
2. `cancel/fulfill/revoke×deactivate` with consistent reservations and permissions.
3. `update×update` and stale `expectedVersion` interleavings.
4. Populated PostgreSQL migration/data preflight, `EXPLAIN` on new EXISTS and audit event partition; full regression.
5. P04 requirement on changed KATO now additionally guards historical stocktake headers; treat as conservative integrity restriction, not an implicit KATO transfer.

## 3. What is NOT claimed complete

- Exact test executions/CI green: **not yet independently confirmed by this report**. Six new test methods are committed; CI status must be attached to precise commit before release claims.
- ORG pass 08–12 (search literal filter, populated DDL audit, recovery, lock performance, final release gate) are **DEFERRED / NOT DONE**. Existing `search` still treats `%` and `_` as LIKE wildcards.
- Cross-domain SALES `fulfill` guard for legacy inactive warehouse and immutable SKU snapshots remain separately owned by SALES/INVENTORY teams; neither modified.
- IAM solo-owner functional role assignment remains proposed product decision, no bypass added.
- No claim of production readiness or elevated maturity until new regression and PostgreSQL barrier tests are actually executed.

## 4. Candidate test commands in a checked-out repo

~~~bash
mvn -Dtest=OrganizationLocationLifecycleQualificationTest,OrganizationLocationQueryQualificationTest test
# Then run existing integration regressions with a real PostgreSQL configuration:
mvn -Dtest=InventoryMovementQualificationTest,InventoryReservationQualificationTest,InventoryTransferStocktakeQualificationTest,SalesInventoryRbacQualificationTest test
~~~

The repository has a root pom.xml and no mvnw/gradlew in the reviewed tree; use a Maven-capable environment with the project's required JDK and PostgreSQL test configuration. A command in this report is not evidence it has been executed. Attach GitHub Actions run ID, exact commit SHA, test counts and failure logs to move `ORG-R1.1` from `CODE PRESENT` to `GREEN/QUALIFIED`.


## 5. First CI evidence and correction

**CI run:** [RM Domain Security and PostgreSQL Qualification #44988252109](https://github.com/alimbetov/RM/actions/runs/44988252109) on source commit `3bcfa52e6827b668709479a05651daea1218c4d1`. **Result: FAILURE** in both H2 smoke and PostgreSQL integration, not compilation failure. PostgreSQL summary `308 tests, 1 failure, 0 errors, 18 skipped` (H2 `308 tests, 1 failure, 0 errors, 60 skipped`). The single failure was `OrganizationLocationLifecycleQualificationTest.typedCreateUpdateNoOpStaleAndUnicodeAreAuditedAtomically:126` because the test compared a Java in-memory `Instant` with nanoseconds against the same timestamp read from PostgreSQL after microsecond truncation.

**Code correction:** commit `18e65ed07f1d6208e24fc457276d7700d6478d2d` updates the test to record `locations.get(...).updatedAt()` persisted value before the no-op and compare it with the persisted value after. This preserves the intended business invariant (no timestamp change), without relying on DB nanoscale precision. At document update, the new CI on corrected SHA may be queued; do **not** call ORG-R1.1 GREEN until it completes successfully. The remaining tests on the failed run were reported without errors, but the whole run was **not GREEN**.

**Scope:** no production Java semantics modified by the precision correction; the adjustment is test-only.


## 6. PostgreSQL R1.1 concurrency qualification — GREEN on an exact tested SHA

**Source SHA:** `69f757cf4d79c84d0198a6f2af432067a17007b2` on `base_dev`. **CI evidence:** [RM Domain Security and PostgreSQL Qualification #44989157721](https://github.com/alimbetov/RM/actions/runs/44989157721) — **completed SUCCESS** (postgres-integration, h2-smoke, postgres-pii-populated all green). **New source-verified suite:** [OrganizationLocationR11ConcurrencyQualificationTest.java](https://github.com/alimbetov/RM/blob/base_dev/src/test/java/kz/rm/organization/OrganizationLocationR11ConcurrencyQualificationTest.java). No SALES/AutoOrder production code was modified; the suite uses their existing service API as test fixtures and real paired permissions.

**CI result by environment:**

| Job | Maven result | Execution detail |
|---|---|---|
| `postgres-integration` | `BUILD SUCCESS` | 314 test cases discovered; 0 failures, 0 errors, 18 skipped; **6/6 new ORG PostgreSQL concurrency cases executed and PASS** |
| `h2-smoke` | `BUILD SUCCESS` | 314 test cases discovered; 0 failures, 0 errors, 66 skipped; **6/6 new PostgreSQL-specific race tests intentionally skipped**, because H2 does not prove PostgreSQL row locks |
| `postgres-pii-populated` | SUCCESS | Existing isolated populated IAM/PII upgrade job passed; not evidence of ORGANIZATION populated upgrade |
 
**New PostgreSQL barrier test evidence (all PASS):**

| ID | Test method | Transaction order | Observable invariants |
|---|---|---|---|
| `ORG-R11-CON-01` | `postgresConfirmedOrderWinsThenDeactivationIsBlockedByReservation` | seller `confirm` commits first; `deactivate` waits for warehouse lock, then denies | CONFIRMED with exactly 1 ACTIVE reservation for order and 3 reserved units; location ACTIVE, no DEACTIVATED audit |
| `ORG-R11-CON-02` | `postgresDeactivationWinsThenNewConfirmationRollsBackToPlaced` | `deactivate` locks location first; `confirm` waits, then fails on INACTIVE | seller order remains PLACED; zero reserve, zero CONFIRMED events; warehouse INACTIVE |
| `ORG-R11-CON-03` | `postgresCommittedTransferWinsAndPauseRetainsPhysicalLedgerConservation` | `transfer` commits first; warehouse pause waits | 2 immutable transfer movement legs, 6 source + 4 target = initial 10, destination INACTIVE with free stock |
| `ORG-R11-CON-04` | `postgresPausedTargetWinsThenTransferCannotCreateAnyLeg` | pause target first; transfer waits then refuses | source still 10, target balance absent, 0 transfer legs |
| `ORG-R11-CON-05` | `postgresSameVersionCompetingProfilesOnlyOneMutationAndAuditCommit` | first update holds `org_location FOR UPDATE`, second same expectedVersion waits | one current profile, rowVersion 1, exactly 1 UPDATED audit event |
| `ORG-R11-CON-06` | `postgresRevokeWaitsForAdmittedMutationAndSubsequentWriteIsDenied` | admitted deactivate holds IAM workspace `FOR SHARE`, owner revoke waits for `FOR UPDATE` | first operation commits and has one DEACTIVATED audit, next post-revoke mutation fails with SecurityException and no UPDATED audit |

The tests use **two independent worker threads with transaction templates and CountDownLatch barriers**, assert a lock wait via bounded Future timeout, release a chosen transaction, and then verify the committed persistent DB state. This qualifies the tested **commit order** for each pair, not every possible multi-tenant scheduling, starvation, outage or throughput scenario.

**Status of first failed run:** [#44988252109](https://github.com/alimbetov/RM/actions/runs/44988252109) had a single incorrect nanosecond-vs-microsecond timestamp assertion; corrected with test-only commit `18e65ed0`. Baseline CI [#44988578245](https://github.com/alimbetov/RM/actions/runs/44988578245) had already qualified 308 cases; the new **six additional real-PG cases** bring the new qualifying run to 314 test cases.

### Qualification decision

**ORG-R1.1 code + defined six PostgreSQL concurrency regressions: GREEN on SHA `69f757cf...`.** All requested pairs (`reserve/confirm×deactivate`, `transfer×deactivate`, `update×update`, `RBAC revoke×mutation`) have at least one deterministic real-PG winning-order test. No code hotfix was required after adding the six race cases.

**Residual HOLD outside this narrowly qualified gate:**

1. Full `ORG-P03…P07` proposed larger matrix remains unexecuted; six implemented tests do not substitute for ~98 originally planned broader cases.
2. PostgreSQL populated-data migration, literal LIKE/deep-paging validation, comprehensive recovery/retry, load p95/p99 and full pass 08–12 release gates are not closed.
3. `SALES` immutable order-line SKU snapshot and legacy INACTIVE warehouse fulfillment remain owned by other parallel changes; no modification or implied validation under this ORGANIZATION patch.
4. IAM solo-owner self-functional-role policy is not changed or approved here.
5. A later `base_dev` HEAD may include unrelated concurrent commits. Claim GREEN for the **tested SHA** and inspect subsequent diffs/CI before adopting a later HEAD as qualified.

**Next recommended work:** no expansion until a separate release admission decision. Preserve these six tests as mandatory regression gate for subsequent ORGANIZATION and INVENTORY changes. Production remains **HOLD**, not because these six tests failed, but because the larger evidence and release gates are still pending.
