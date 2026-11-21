# RM FRZ-1 — Security Hardening / Code Review

**Date:** 2026-11-21. **Baseline:** PR #2, branch `feat/sec-r1-unified-domain-authorization`.
**Scope:** trusted principal admission **without controllers**, transactional RBAC revocation, internal SALES/INVENTORY call boundary. No new tables, no new business functions, no merge to main.

## Implemented (in this PR, feature freeze)

1. **Typed verified OIDC principal**:
   - `OidcJwtConfiguration` activates only with `rm.security.oidc.enabled=true`; requires HTTPS JWKS URI, issuer and audience. `NimbusJwtDecoder` verifies JWT signature against configured JWKS; default validator also verifies token lifetime and issuer and explicit `aud` validator rejects other audiences.
   - `OidcActorResolver` maps verified token `sub` to `iam_user_account.external_subject`; account must be `ACTIVE`. Returns `VerifiedActor` with inaccessible public constructor and an expiry guard. Invalid token/subject rejected without logging token.
   - `AuthenticatedSalesCommands` and `AuthenticatedInventoryCommands` are the **public application entrypoints for commands**; require a `VerifiedActor`, not an arbitrary user UUID. No HTTP/OIDC filter, controller, real issuer credentials or external deployment has been enabled.
   - JCA RSA-signed JWT tests cover valid token and bad signature, issuer, audience, expiry. Separate subject status tests cover suspended and unknown user.
2. **RBAC revocation consistency**:
   - `TenantAuthorization.requireForCommand` requires an active Spring transaction and acquires a tenant workspace row lock **before checking user/membership/permission**.
   - PostgreSQL: `FOR SHARE` lets commands for the same organization proceed in parallel; owner-managed RBAC mutations already use `FOR UPDATE` on the same workspace. H2 uses `FOR UPDATE` for CI compatibility. No cache or extra schema.
   - PostgreSQL two-connection tests prove both interleavings: first-admitted transaction blocks later revocation until commit; revocation committed first makes the waiting command fail.
   - IAM `Decision` command permits are transaction-bound (Spring synchronization marker), constructor is private, and the internal inventory port refuses reuse in a later transaction.
3. **No cross-package raw inventory bypass**:
   - Raw `InventoryReservationService.reserve/release/fulfill` and `InventoryMovementService.adjust` are package-private, as are UUID-based command methods in `SalesInventoryCommandService` and `InventoryAuthorizedCommandService`.
   - `OrderInventoryPort` validates two IAM-issued transaction-bound permits for the **same actor/tenant**, with matching SALES and INVENTORY actions. Untrusted pairs or stale permissions fail closed.
   - Architecture regression tests inspect Java method access modifiers and public entry signatures.
   - `SalesInventoryCommandService`, `InventoryAuthorizedCommandService`, `CrmCustomerService`, `OrganizationLocationService` and inventory settings mutation paths now request `requireForCommand` to protect RBAC membership decisions during writes.

## Review results / explicit remaining limitations

**No production admission or merge authorization from these unit/E2E passes.** Current code is an internal modular-monolith implementation and still has the following separate gates:

| Risk | Priority | Status / needed follow-up |
|---|---|---|
| External OIDC session / BFF / HTTP security chain | P0 for public deployment | Not implemented; only fail-closed internal JWT resolver. Real trusted issuer+JWKS integration and end-to-end principal propagation are required before any public API |
| Membership/user suspension concurrent with active command | P0 for production | Only *TenantRoleService* RBAC role edits use the workspace lock convention. Other account/membership status admin paths must use the same lock ordering; ad-hoc DB updates are not fenced |
| Legacy public internal services with UUID actor arguments (CRM, RBAC admin, inventory settings) | P1 integration | No controllers today; future transport adapters must use verified actor mapping. Typed facades have been added to the most critical SELLER and STOCK command paths, not to all 15 contexts |
| Privileged SQL/DBA bypass of application permission checks | P1 infrastructure | PostgreSQL least-privilege service role, migration credentials separation, RLS defense in depth and audit ingress not yet qualified |
| Tenant row-lock contention / latency qualification | P1 performance | PG SHARE locks allow parallel commands; measure p95/p99 under concurrent role edits and 1k/10k users prior to production |
| Filled-database Liquibase upgrade + restoration/PITR | P1 release | Separate pre-production qualification, not proven by clean CI database |

## Regression matrix

- Fresh H2 and PostgreSQL Maven verify; no public endpoint or network dependency for default environment.
- Real crypto: bad JWT signature, issuer, audience, expiry, unknown/suspended account.
- RBAC: ACTIVE user/workspace/membership; tenant-scoped role grants and explicit denials; OWNER-only role admin.
- Command transaction: grant revoked in separate transaction; two interleavings; stale command decision reused after commit → deny.
- Boundaries: cross-domain permission pair, package-private unsafe entrypoints, typed actor ports, rejected unauthorized commands.
- Existing SALES/INVENTORY E2E: confirmed/cancelled/fulfilled, idempotency, oversell, two-SKU lock order and rollback.

## Merge recommendation

**Keep PR #2 Draft and main unchanged.** Once exact-head H2+PostgreSQL CI is GREEN, FRZ-1 source changes can be accepted as **internal security hardening**, but production/public admission is still HOLD. For a release merge, obtain independent review of actor mapping, account/membership revocation convention, DB privileges, and filled-DB migration safety. Do not represent a real OIDC login as tested when only synthetic JWTs are used.

**No overengineering:** one permission catalog, one TenantAuthorization, one TenantRoleService, one narrow inventory port, no Redis, no separate policy service, and zero new tables for FRZ-1.

## Post-review defect correction

The verified SALES facade negative scenario exposed a nonuniform denial for a nonexistent tenant: the transaction admission code used `JdbcTemplate.queryForObject` and emitted `EmptyResultDataAccessException`. The query now uses a nullable extractor and returns the same `SecurityException` as other invalid tenant inputs, without leaking persistence topology. The regression test verifies this path.

Synthetic cryptographic JWT tests are **not** proof of identity-provider deployment; only the library-level verifier is qualified. Likewise, synthetic PG two-session tests prove the `TenantRoleService` lock protocol, not every legacy IAM status mutation. Remaining production gates above remain unchanged.
