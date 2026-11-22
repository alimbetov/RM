
# RM Platform — ORGANIZATION Functional Depth / Detailed TZ

**ID:** RM-ORG-FUNC-R1; **review:** 01/12; **branch:** base_dev; **reviewed source SHA:** c776baec5c82e0dfd4c7930aa6a68629202fa5fd (2026-11-22).
**State:** SPECIFIED, NOT IMPLEMENTED. Feature Freeze: no HTTP controllers, no changes to main, production HOLD.

## 1. Scope and business result

ORGANIZATION must manage the lifecycle of physical locations: branch (BRANCH), warehouse (WAREHOUSE), point of sale (POINT_OF_SALE). A verified tenant employee can create, read, search, edit, suspend, reactivate and close a location. Inventory/order operations must never accept an ineligible location. Stock and historical orders are preserved.

No duplication of legal organization registration: IAM owns org_organization, iam_workspace, tenant memberships, RBAC and VerifiedActor; GEO owns KATO reference eligibility; ORGANIZATION owns public.org_location; INVENTORY owns balances, movements, reservations; SALES owns order status. A location is not a tenant. No tenant or type reassignment.

## 2. Source-verified baseline

| Code | What actually works | Gap |
|---|---|---|
| AuthenticatedOrganizationLocations | Public VerifiedActor facade with create/get/search | Missing update, deactivate, reactivate, close |
| OrganizationLocationService | package-private create + WRITE permission + GEO policy + JPA insert | no state update and no optimistic command contract |
| OrganizationLocationQueryService | tenant scoped get/search, READ permission, Page size 1..100 | pattern LIKE, deep OFFSET, incomplete filters |
| OrganizationLocationEntity | @Version, ACTIVE/INACTIVE/CLOSED, timestamps | status mutations absent |
| OrganizationLocationRepository | scoped get/search, createdAt DESC/id ASC | need literal LIKE escape; planned sort is createdAt DESC/id DESC |
| KatoLocationPolicy | rejects unknown/deleted KATO, level > 3 | needs revalidation on change/reactivation |
| OrganizationLocationQueryQualificationTest | tenant A/B, rollback, null actor, revoke race, list filters | lacks lifecycle and cross-domain closure races |

**Findings:** ORG-01 P0 missing state transitions; ORG-02 P0 INVENTORY receive/correct lacks active-warehouse guard; ORG-03 P0 SALES confirm lacks location type/status guard; ORG-04 P1 missing editable profile; ORG-05 P1 unused @Version for command CAS; ORG-06 P1 unescaped percent/underscore in bound LIKE (not SQL injection); ORG-07 P1 deep OFFSET and different tie-break sort; ORG-08 P1 no emitted location change audit; ORG-09 P1 insufficient mixed-membership/concurrent dependency tests; ORG-10 P2 duplicate logical unique index/constraint in Liquibase (review, do not drop during freeze).

These are source findings, not production exploits; the existing deterministic id ASC ordering is not random, but inconsistent with the SEARCH-R2 target.

## 3. Twelve-pass protocol (complete ORGANIZATION before another domain)

| # | Audit depth | Required result | Current |
|---|---|---|---|
| 01 | Source map + cross-domain review | This defect inventory and detailed TZ | SPECIFIED |
| 02 | Operational user journeys | Warehouse / branch / POS / seller journey mapped to methods and errors | OPEN |
| 03 | State machine and dependencies | Stock, reservations, open orders; exact closure policy | OPEN |
| 04 | Create + profile edit | Input validation, immutable fields, KATO, optimistic version | OPEN |
| 05 | INVENTORY integration | No receiving/correction/reserving through ineligible location | OPEN |
| 06 | SALES integration | Correct confirm/replay/cancel/fulfill behavior and locks | OPEN |
| 07 | IAM boundary + RBAC | VerifiedActor, A/B/AB grants, revoke race, foreign UUID | OPEN |
| 08 | Search and pagination | Literal filters, bounded pages, consistent sorted reads | OPEN |
| 09 | PostgreSQL and audit | Populated migration, transactional actor/action evidence | OPEN |
| 10 | Concurrency + rollback | update/update, deactivate/reserve, crash/retry | OPEN |
| 11 | Regression/performance | Method-to-test trace, actual EXPLAIN and workload proof | OPEN |
| 12 | Final acceptance | Evidence verdict, residual risks, handoff | OPEN |

Status is per pass, not per document. A specification does not make implementation GREEN.

## 4. Required service contract: ORG-R1.1

Retain one public application facade AuthenticatedOrganizationLocations; keep internal UUID actor services package-private. Every command needs VerifiedActor, selected tenant, ORGANIZATION_LOCATION_WRITE and transaction-bound TenantAuthorization.requireForCommand. Every get/search requires ORGANIZATION_LOCATION_READ, without disclosing foreign object existence.

| Command | Inputs / output | Semantics |
|---|---|---|
| create | actor, tenant, type, name, kato, address -> LocationDto | ACTIVE initially; validate and audit CREATE |
| update | actor, tenant, locationId, patch, expectedVersion -> LocationDto | Only name/address/KATO; @Version CAS; audit UPDATE |
| deactivate | actor, tenant, locationId, expectedVersion -> LocationDto | ACTIVE -> INACTIVE after dependency check; audit DEACTIVATE |
| reactivate | actor, tenant, locationId, expectedVersion -> LocationDto | INACTIVE -> ACTIVE after fresh GEO check; audit REACTIVATE |
| close | actor, tenant, locationId, expectedVersion -> LocationDto | INACTIVE -> CLOSED after dependency check; audit CLOSE |
| get | actor, tenant, locationId -> LocationDto | Scoped read; historical inactive/closed visible to authorized staff |
| search | actor, tenant, filters, page, size -> Page<LocationDto> | Admin search all states for backward compatibility; operational warehouse selector only ACTIVE WAREHOUSE |

The facade must not expose arbitrary actorId or unrestricted JPA Entity writes. Do not add generic delete, bulk updates, type conversions, tenant moving, or an independent organization microservice.

**Validation:** trim/Unicode NFC name/address; name nonblank, max 200; address nullable max 500; KATO string checked by existing GEO policy. Reject control characters. Type immutable for R1. KATO may be changed only before historical inventory/order use or positive stock; otherwise create a new physical location. Names and addresses can be corrected without rewriting immutable historical stock movements.

**CAS semantics:** expectedVersion required on all edit/state commands; stale version means conflict and no write/audit. Do not advertise retry idempotency without a persisted receipt; repeat with stale version is a conflict. Successful transaction increments rowVersion and updatedAt once; failed transaction increments neither.

## 5. Lifecycle policy

| From | To | Rule |
|---|---|---|
| ACTIVE | INACTIVE | Only if no on_hand > 0, reserved > 0, ACTIVE reservations or bound unfinished operation |
| INACTIVE | ACTIVE | Only if tenant still active and KATO eligible |
| INACTIVE | CLOSED | Only if same zero-usage conditions hold; retain all historical references |
| ACTIVE | CLOSED | Forbidden: deactivate first |
| CLOSED | anything | Forbidden: CLOSED is terminal |
| unchanged | unchanged | Explicit no-op only if permitted and version checked; no silent audit duplicates |

This conservative R1 policy is aligned with RM-SVC-R2. If users must temporarily disable warehouses holding stock, refine the policy in pass 03 with a distinct operational suspension semantics and tests. Current INVENTORY transfer/stocktake is unfinished; do not simulate depletion or erase nonzero balances to close a warehouse.

**Cross-domain transaction rules:**
1. ORGANIZATION obtains a row lock for the tenant-scoped location before changing lifecycle state. It calls a small typed read-only INVENTORY usage port (on-hand, reserved, active reservations) and optionally SALES location-bound status when modeled. No direct writes into another domain.
2. INVENTORY receive/correct/reserve and SALES confirm obtain an ORGANIZATION-owned ACTIVE WAREHOUSE admission guard in the *same transaction*, with a compatible lock preventing concurrent deactivation. A foreign key to (workspace_id,id) proves ownership, NOT active status.
3. Lock protocol to review: IAM actor/workspace admission -> SALES order row if involved -> location eligibility lock -> stock balance -> reservation. Closing locks location, then checks counts without acquiring an order row (avoid inverse ordering).
4. The current PLACED sales order does not hold a location binding before confirm; do not invent an "open orders by warehouse" query. Confirm-time admission must block a closed warehouse. Existing historical movement/fulfilled reservation never causes data deletion.
5. Any SELECT-only check without concurrency fencing fails the race requirement: deactivation vs receive/confirm must serialize.

**Type policy:** R1 stock fulfillment and receiving use WAREHOUSE; any existing BRANCH/POS stock records in populated DB must be inventoried before enabling restriction. POS stocking is a separate product policy, not implicit by a UUID.

## 6. Search, persistence and audit

- Extend LocationSearch with optional katoCode and activeOnly while retaining its old 3-argument constructor. Reject contradictions between status and activeOnly.
- Name filter means a *literal substring*. Escape percent, underscore, and escape character with an explicit SQL/JPQL ESCAPE clause. Bound parameters remain mandatory. Validate length <=100 and unicode.
- Default admin search continues to include closed locations. Staff operational selector must explicitly demand ACTIVE WAREHOUSE. Sort createdAt DESC, id DESC. Keep size 1..100; reject deep offset beyond documented cap to avoid expensive queries. Cursor/HMAC remains SEARCH-R2 work, not part of current quick completion.
- Existing org_location has status, @Version, timestamps, tenant FK, KATO FK and composite tenant/ID uniqueness. Prefer JPA update; no new location tables, no destructive migration. Index changes only after EXPLAIN.
- Use existing crm.audit_event atomically for action codes ORG_LOCATION_CREATE/UPDATE/DEACTIVATE/REACTIVATE/CLOSE, actor_user_id, tenant_id, entity_id, occurred_at. That journal contains no before/after payload, so do NOT claim detailed attribute history without a separately approved extension. Do not introduce a duplicate audit_event table.
- Preserve no-controller Feature Freeze and production HOLD.

## 7. Qualification matrix / minimum tests

| ID | Mandatory test | Expected |
|---|---|---|
| ORG-Q01 | Create, get, search, persist event | Tenant-scoped positive flow, one audited event |
| ORG-Q02 | Null/foreign actor, wrong tenant/location, revoked permission | Deny before read/write evidence; no foreign leak |
| ORG-Q03 | Edit name/address/KATO, invalid fields, immutable type/tenant | Strict payload, version + timestamp update |
| ORG-Q04 | Two updates with identical expectedVersion | Exactly one succeeds; one stale-version conflict |
| ORG-Q05 | Positive stock, reserved, ACTIVE reservation | Inactivate/close denied, no partial writes |
| ORG-Q06 | Empty ACTIVE->INACTIVE->ACTIVE and INACTIVE->CLOSED | Legal transitions only; CLOSED terminal |
| ORG-Q07 | Race: deactivate vs receive/correct/confirm | Correct row lock serialization; no mutation at inactive location |
| ORG-Q08 | Inventory/Sales commands on CLOSED, INACTIVE, BRANCH, foreign location | Denied before any order or quantity mutation |
| ORG-Q09 | Zero stock with existing historical movement | Close allowed only by approved policy; history and FK preserved |
| ORG-Q10 | Literal LIKE %, _, backslash, unicode, same-timestamp pagination, bounds | Accurate filter/count/order and no duplicates |
| ORG-Q11 | Shared global user A+B with unequal grants; revoke during command | Effective per-tenant permission and transaction fence |
| ORG-Q12 | Populated PostgreSQL, H2 smoke, GEO/CRM/SALES/INVENTORY regressions | No data loss, exact-head non-skipped evidence |

Existing OrganizationLocationQueryQualificationTest partially covers Q01/Q02/Q11; it does not qualify Q03-Q10/Q12. Record the precise test class, method, commit SHA, database and observed SQL results; CI GREEN is not automatically full functional qualification.

## 8. Implementation slices and Definition of Done

| Slice | Work | Gate |
|---|---|---|
| ORG-R1.1A | Entity operations, facade, CAS, lifecycle, audit | Q01-Q04 and Q06 |
| ORG-R1.1B | Typed INVENTORY usage/admission + SALES confirm guard | Q05, Q07-Q09 |
| ORG-R1.1C | Scoped search fixes and bounded pagination | Q02, Q10-Q11; PostgreSQL EXPLAIN |
| ORG-R1.1D | Populated PostgreSQL + source-method trace + exact-head CI | Q01-Q12 and independent review |

A location is qualified only when its real create/edit/deactivate/reactivate/close journey and blocked dependency operations are proven on PostgreSQL, authorization and tenant constraints hold under concurrent transitions, and history/audit remain consistent. This can raise the domain from 1/5 toward 3-4/5 for its declared scope; production approval is separate.

**Explicitly deferred:** hierarchy of branches; location-level staff ACL; geocoding; inventory transfers and valuations; multi-warehouse fulfillment; generic saga/event bus; deleted-location purging; duplicate legal-org CRUD; HTTP/UI; full audit field diffs.

## 9. Iteration 02/12 input

Trace three concrete staff journeys (warehouse operator, branch/POS manager, seller fulfillment) through exact methods and tests. Resolve operational suspension with stock, location usage SQL/locks, and all negative transition outcomes. Keep the same domain until twelve passes reach evidenced closure.
