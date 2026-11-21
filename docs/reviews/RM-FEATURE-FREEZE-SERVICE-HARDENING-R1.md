# RM — Feature Freeze / Service Hardening R1

**Decision date:** 2026-11-21.
**Frozen code baseline:** `bc27ac8bd8fb9a4a82db13788529f0f0e0c47a2f`.
**Branch:** `feat/sec-r1-unified-domain-authorization`; **PR #2 remains Draft**.
**CI baseline:** H2 and PostgreSQL run `44892016345` PASS on the frozen commit.
**Release status:** production admission HOLD, merge main HOLD until remediation gate.

## 1. Scope and change-control rule

Freeze new features, new domains, new CRUD surfaces, new valuation algorithms, broad DB schema expansion, new frontends, Redis, KMS rotation, public API exposures and new role concepts. The target is to stabilize current IAM/tenant RBAC, CRM, ORGANIZATION, SALES, INVENTORY and the existing read services.

**Allowed:** bug fixes, security/tenant isolation, transactional invariants, service/repository separation, DTO/MapStruct consistency, indexes where justified by EXPLAIN, regression tests, observability, migration safety and documentation. Any necessary additive migration requires an exact incident/defect ID and PostgreSQL upgrade test. No rewrite of applied Liquibase changesets.

No merging PR #2 into main solely on CI GREEN: assess the following gates independently.

## 2. Current implementation inventory

| Service | Current capability | Qualification caveat |
|---|---|---|
| `TenantAuthorization` | active actor, active organization, membership, tenant-scoped permission query | actorId trusted internal-only, revocation/commit race not fenced |
| `TenantRoleService` | roles, grants, assignments, activation, append-only events | no initial-owner self-onboarding; separation-of-duty and policy change workflow incomplete |
| `CrmCustomerService` | tenant-scoped encrypted PII/customer/contact/consent | monolithic JDBC service; distinguish metadata versus PII access on all paths |
| `OrganizationLocationService` | guarded command create | no update/deactivate command |
| `OrganizationLocationQueryService` | typed MapStruct DTO, paging/filter | JPQL optional predicates must be PG-tested |
| `StockBalanceQueryService/Repository` | bounded tenant stock pages, availableOnly | COUNT and page can be different snapshots; offset paging on mutable stock |
| `SalesOrderQueryService/Repository` | seller order pages/status/date, no buyer PII | read permissions currently broad for any active member; require business approval |
| `SalesInventoryCommandService` | confirm + reserve, cancel + release, fulfill + debit in one transaction | full lifecycle beyond internal commands not covered; direct underlying service calls remain possible |
| `InventoryAuthorizedCommandService` | RBAC received quantity/correction | lacks landed costs, no booked valuation, no financial COGS |
| `InventoryReservationService` | request/row locking, reservation events | publicly injectable low-level component has no authorization internally |
| `InventoryMovementService` | immutable adjustments/receipt movements | idempotency retry checks stock row before checking existing request, yielding confusing errors when stock changed |

## 3. Findings / action register

### P0 — security and data integrity

**FRZ-P0-01 — trusted principal boundary.** `actorId` in public service signatures must come from validated identity; current services are safe only under documented internal trust assumption. No REST controllers until OIDC adapter, subject binding, no actor spoofing tests, and no request-provided tenant bypass.

**FRZ-P0-02 — revocation race.** RBAC check occurs prior to locking the business row, so a concurrent OWNER grant revoke, actor suspension, or tenant suspension can commit while an already-authorized command is executing. Require a transactional admission barrier and test two sessions (revoke/command in both interleavings) before using critical order/stock commands in production.

**FRZ-P0-03 — bypass of business orchestration.** `InventoryReservationService` and `InventoryMovementService` are Spring-managed public internal services without actor verification; another component can invoke `fulfill` without `SalesInventoryCommandService`. Define package/module port boundary and add architecture test forbidding unapproved dependencies, not just JavaDoc warning.

**FRZ-P0-04 — terminal-state invariants and out-of-band SQL.** Order state changes are checked in orchestrator, but status can be changed from other code or ad hoc SQL. Ensure only canonical transition service writes `sales_order.status`, assert reservation counts, and reconcile order state versus stock movement evidence.

### P1 — service contracts and performance

**FRZ-P1-01 — error taxonomy / idempotency.** Distinguish not found vs forbidden vs conflict vs exhausted stock; request key replays must compare original business payload **before** accessing target stock position where feasible.

**FRZ-P1-02 — multi-line order concurrency.** Review deterministic per-SKU lock order for two multi-line orders listing items in opposite line-ID order; order row locks only serialize within an order, not across orders. Add PostgreSQL tests for two-order opposite-SKU ordering, check SQLSTATE 40P01 absence or bounded retry.

**FRZ-P1-03 — manager/member read policy.** `SALES_ORDER_READ` and `INVENTORY_STOCK_READ` currently allow all active members in compatibility mode; review need-to-know confidentiality and warehouse scope before public release.

**FRZ-P1-04 — pagination.** Spring Page offset+COUNT with mutable source data can be inconsistent across queries; explicitly label as transient read model. For stable large-scale exports use keyset/cursor + revision or snapshot. Do not prematurely add generic pagination framework.

**FRZ-P1-05 — service boundary quality.** Favor immutable request/result DTOs, MapStruct only for JPA entity mapping, native JDBC rowmapper for projections. Avoid adding redundant generic mapper/repository layers; split oversized services only along a measurable responsibility boundary.

**FRZ-P1-06 — authorization tests.** Cross-tenant and PII denial, suspended membership, role revocation, self-escalation, OWNER lockout prevention, separate actor permissions on every existing service; test permission changes when command is blocked on database row locks.

**FRZ-P1-07 — migration safety.** Fresh PostgreSQL != populated-DB upgrade. Add staged migration trial, event row counts/hash, rollback strategy and backup/PITR readiness for historical changesets, especially audit partitioning.

### P2 — follow-on after gates

Simplify redundant SQL and dead imports, annotate metrics for RBAC auth latency, SQL plan checks for high-volume tenant+status queries, and add selective DB indexes only after profiling.

## 4. Execution stages (no feature scope growth)

| Stage | Work package | Exit criteria |
|---|---|---|
| FRZ-0 | Baseline, freeze scope and CI lock | latest PR head and tests recorded, Draft unchanged |
| FRZ-1 | SEC authorization audit | negative IAM/tenant tests; no unauthorized domain commands; threat model signed |
| FRZ-2 | SALES-INVENTORY correctness | double debit=0, orphan reserve=0, no oversell, deadlock/race tests PG |
| FRZ-3 | DTO/repository/query hardening | stable page limits, Pg plans, no PII leaks, no raw tenant bypass |
| FRZ-4 | regression, migrations, operational qualification | reproducible H2 + PG CI, filled-DB upgrade, two-session PG race evidence |
| FRZ-5 | architecture review | harden and release from Draft only when P0=0; evaluate outstanding P1 in accepted risk register |

## 5. Qualification matrix

- **Security:** outsider, actor spoofing, suspended account/workspace/membership, VIEWER PII, grant/revoke and changed tenant UUID, role self-assignment and unauthorized RBAC writes.
- **Order:** confirm/confirm retry/cancel/cancel retry/fulfill/fulfill retry, out-of-stock multi-line rollback, conflicting warehouse retry, terminal-state audit evidence.
- **Concurrency:** same order double-click, two orders same SKU, two multi-line orders opposing SKU lock sequence, revoke during waiting transaction, server interruption after each mutation step.
- **Stock:** after any committed transaction: `on_hand>=reserved>=0`; immutable movement once per reservation; `CONFIRMED`→complete active reservations; `CANCELLED`→none active; `COMPLETED`→all fulfilled.
- **Database:** fresh H2/PG, populated PostgreSQL upgrade, EXPLAIN on tenant-scoped paginated queries, least-privilege DB users.
- **Operations:** request correlation, sanitized audit, p95/p99 profiles, resource leaks and connections checked under bounded concurrency.

## 6. Done definition

No P0 security/data-integrity defect, no unqualified direct entry point, deterministic tests for races (not time.sleep-based), documented rollback, separate reviewer signoff on state machine and accounting boundary, H2+PG green on **exact** proposed merge SHA, and main CI gate configured. No automatic public endpoint enablement.

**The freeze is a work-prioritization decision documented in this branch, not a GitHub server-side branch lock or protected branch setting.** Restricting pushes/merges requires ruleset administration outside this document.
