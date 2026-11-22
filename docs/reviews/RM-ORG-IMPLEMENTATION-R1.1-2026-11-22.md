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
