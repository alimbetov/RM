# RM FINAL-SEC-GATE — execution evidence and SQL review (2026-11-21)

**Scope:** feature-frozen tenant RBAC, verified application actor, protected SALES/INVENTORY mutations, status revocation. **Branch:** `base_dev` only. **Merge/production:** HOLD.

## Source and CI evidence

- Baseline `1719b143a35684941d3173304dc4732ea8b4e493`: [Actions run 44919042656](https://github.com/alimbetov/RM/actions/runs/44919042656) SUCCESS. All three jobs (H2 smoke, PostgreSQL integration, populated PostgreSQL PII) SUCCESS. Two new PostgreSQL two-session tests exercise *actual* `GlobalAccountStatusService.suspendAccount`: admitted command before suspension, and committed suspension before waiting command.
- New negative test at `e3a20d04315e5f63e5bfc2cf4851382cf5864753`, `SalesInventoryRbacQualificationTest.finalSecGateRejectsMixedActorTenantAndMissingInventoryPermits`, exercises mixed actor, mixed tenant, wrong permission, missing decision; asserts no inventory reservation or balance changes. **Do not mark PASS until CI for this SHA completes.**
- Existing `TenantRbacRevocationQualificationTest` covers role revocation, account/member/tenant suspension interleavings; `DomainCommandBoundaryQualificationTest` checks visibility of raw mutation entry points; `OidcJwtCryptoQualificationTest` covers synthetic crypto. All are library/service scope, **not** live issuer/BFF/HTTP qualification.
- `VerifiedActor` has no public constructor; its `userId()` checks expiry. `OrderInventoryPort` checks tenant, actor, expected SALES/INVENTORY permission and both transaction markers. Unauthorized attempts are rejected before stock mutation.

## SQL / database access review

1. Application configuration: `application-dev.yml` uses in-memory H2 and default `sa` (development only); `application-test.yml` uses `RM_TEST_DB_USERNAME`/`RM_TEST_DB_PASSWORD`. GitHub CI PostgreSQL runs as the schema-owning `rm` account and executes Liquibase and application queries with that same account. **This does not prove least-privilege runtime role.**
2. Current `application.yml` has `spring.liquibase.enabled=true`, with no independently evidenced migration-user separation. Do **not** deploy this configuration to production with a high-privilege runtime login. A reviewed deployment configuration must separate schema migration credentials from runtime DML credentials and verify denied DDL/unauthorized table access.
3. `db.changelog-master.sql` applies additive migrations through `0051`. This clean-database CI does **not** prove rollback/restore from a real populated production-like snapshot. The isolated `postgres-pii-populated` job uses 200 synthetic accounts and validates PII-specific behavior; it is not full historical upgrade/PITR.
4. `RM-IAM-PII-R2.5D-POSTGRES-SEAL.sql` is explicitly a **manual** offline operation, not executed automatically. It seals legacy plaintext columns with a validated CHECK and REVOKE PUBLIC on `iam_pii`; neither runtime role grants nor all legacy direct SQL writers are exhaustively qualified. Backups/logs predating sealing can still contain plaintext.
5. A privileged SQL principal can bypass application-layer tenant authorization. Runtime schema grants, SELECT/UPDATE scope, privileged SQL auditing and (if required by threat model) RLS must be reviewed independently before public/production admission. Avoid claiming that Java RBAC enforces privileged DBA queries.

## Outstanding verification tasks

| ID | Required evidence | State |
|---|---|---|
| G1 | latest head H2/PG/populated-PG result including cross-actor/tenant permit test | PENDING at new test SHA |
| G2 | independent reviewer approval of exact proposed merge diff, negative tests and lock protocol | OPEN; PR #9 had zero submitted reviews when checked |
| G3 | enumerate all UUID-based internal mutators, ensure future transport can reach only verified-actor façades | OPEN for complete platform; current SALES/INVENTORY boundary reviewed |
| G4 | runtime PostgreSQL least-privilege credentials and deny-tests against actual deployment grants | OPEN |
| G5 | isolated authorized populated DB Liquibase upgrade + tested backup/restore/PITR and recovery evidence | OPEN |
| G6 | live issuer/JWKS/OIDC/BFF, public HTTP abuse checks and real provider integration | NOT IN SERVICE-ONLY GATE; production HOLD |

## Independent reviewer checklist

Reviewer other than implementer must inspect: `TenantAuthorization.requireForCommand` lock and fresh-read semantics, `GlobalAccountStatusService`, `TenantAccessStatusService`, `TenantRoleService` lock order, `AuthenticatedSalesCommands`, `AuthenticatedInventoryCommands`, `OrderInventoryPort`, raw service visibility, all negative tests, PII migration/DB seal and deployment privilege separation. Review both interleavings, stale or switched membership, cross-tenant spoofing, and transaction-bound decisions. Submit an actual PR review (APPROVE or REQUEST_CHANGES) identifying commit SHA and residual risks; a comment, automation, or author self-review is **not** independent approval.

**Decision: NO MERGE** until G1 and G2 are satisfied, plus any unresolved P0 findings. **PRODUCTION HOLD** until G3–G6 are independently qualified. `RM-MAIL-OTP-R1.1` remains design-only until the requested FINAL-SEC-GATE decision.
