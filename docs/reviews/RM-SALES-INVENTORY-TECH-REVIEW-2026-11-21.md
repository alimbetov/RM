# RM SALES + INVENTORY — technical review and R1 hardening

**Date:** 2026-11-21. **Branch:** `base_dev`. **Scope:** transaction boundaries, retries/idempotency, concurrent authorization revocation, DTOs, MapStruct, repositories, pagination and filters. **Public HTTP / production:** HOLD. **Independent PR #9 security review:** still required.

## Code review findings

| ID | Severity | Finding | Action |
|---|---|---|---|
| SI-01 | High | Tenant query service entrypoints accepted arbitrary `UUID authenticatedActorId` publicly, while mutations used `VerifiedActor` façades. This is unsafe as a future transport-integration boundary even if the current code is service-only. | Added `AuthenticatedSalesQueries` and `AuthenticatedInventoryQueries` accepting `VerifiedActor`; made raw query beans and JDBC repositories package-private. Architecture test rejects public re-exposure. |
| SI-02 | Medium | Read authorization used `TenantAuthorization.require` without the account/tenant shared-lock fence used by writes; read operations could race with account suspension or RBAC revocation. | Read services now call `requireForCommand` inside `@Transactional` (not read-only: PostgreSQL shared row locks need a writable transaction). Confirm PostgreSQL lock impact under production-like load; monitor p95/p99 and avoid long-running read transactions. |
| SI-03 | Medium | Permission-revocation tests covered IAM admission and raw permissions but not a real SALES transaction suspended before commit. | Added PostgreSQL two-session test for real `AuthenticatedSalesCommands.confirm` vs `TenantRoleService.revoke` and post-revocation denial. |
| SI-04 | Medium | Filter/pagination tests did not cover trusted INVENTORY read entry, sparse stock, available-only and multi-page tenant isolation end-to-end. | Added `StockBalanceQueryQualificationTest`; extended seller read façade qualification. |
| SI-05 | Informational | JDBC repositories map six-column read projections directly to immutable records; MapStruct is already a project dependency but adds no value for these 1:1 SQL projections. | Keep direct `RowMapper` to records; introduce MapStruct only for a genuine entity-to-DTO or multi-field business translation with explicit null/security policies. |
| SI-06 | Open | Offset pagination executes separate count and page queries; concurrent writes can cause count/content skew or moving page boundaries. | Accept for back-office V1 bounded pages (`size<=100`), but qualify snapshot consistency or adopt keyset cursor when clients require stable infinite traversal. |
| SI-07 | Open | Most tests use synthetic small fixtures, not a throughput/lock contention qualification. | Before production measure concurrent confirm/cancel/fulfill and read/revoke load p95/p99, deadlocks, retries, and blocking duration on PostgreSQL. |

## Existing invariants verified by source review

- `SalesInventoryCommandService`: authenticated command path requires domain-specific SALES and INVENTORY permits, then locks seller order; confirms order and reserves exact lines in a single transaction; cancel releases; fulfill posts negative stock movement; terminal retries check persisted reservation state.
- `InventoryReservationService`: order row, stock row and reservation lock ordering; immutable reservation events; one reservation per line; no split warehouse within R1.
- `InventoryMovementService`: tenant/key-striped advisory row locking, immutable ledger request keys, bounded quantity precision; conflicting replay denied; direct writes package-private.
- `SalesOrderRepository` and `StockBalanceRepository`: parameterized tenant predicates, allowlisted filter clauses, deterministic sorts, bounded PageRequest, immutable DTO records, no buyer PII in order summary.

## Test evidence and admission policy

The baseline before this round was `786f97bf4af0adc76e03103c84c98cb2e1211198` with three successful CI jobs ([run 44920113776](https://github.com/alimbetov/RM/actions/runs/44920113776)). This code review includes additional changes; **they are NOT accepted until H2, PG integration and synthetic populated PG CI are green on the precise new HEAD**.

Tests to verify:
1. `DomainCommandBoundaryQualificationTest.publicReadBoundariesRequireVerifiedActorAndRawBeansAreNotPublic`
2. `SalesOrderQueryQualificationTest.verifiedReadFacadeEnforcesActorTenantAndPagination`
3. `StockBalanceQueryQualificationTest.verifiedStockSearchPaginatesFiltersAndDeniesCrossTenant`
4. `SalesInventoryRbacQualificationTest.postgresSalesConfirmationFencesConcurrentPermissionRevocation`
5. Regression: `TenantRbacRevocationQualificationTest`, idempotent confirm/cancel/fulfill, stock movement and reservation suites.

**Merge:** HOLD until independent security approval and explicit integration decision. **Production:** HOLD until operational DB privileges, real populated restore, OIDC/HTTP admission and performance qualification. No new controllers, schema changes, migration activation, or live email providers as part of this patch.
