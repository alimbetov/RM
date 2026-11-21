# RM — Domain Layer Implementation Baseline (post PR #2)

**Normative architecture/security standard:** [RM-PLATFORM-STANDARD-R2](RM-PLATFORM-STANDARD-R2.md). This page is a SHA-tied implementation snapshot, **not** a competing normative policy.

**Baseline merge:** `126e312e82791a68d689cbcbef287163858e7859` (2026-11-21).
**Status:** merged **internal modular-monolith implementation**, not production/public-API approval.

## Actual verified architecture

- Java 21, Spring Boot 4.1.3, Spring MVC/JPA/JDBC, Liquibase SQL migrations; H2 dev/test and PostgreSQL integration tests.
- Multi-tenant organization workspaces, global IAM user accounts and organization memberships.
- Tenant-scoped functional RBAC: `TenantAuthorization`, `TenantRoleService`, `DomainPermission`; explicit permissions, OWNER-only nondelegable administration; compatibility mode before RBAC enforcement.
- Verified OIDC actor boundary: `OidcJwtConfiguration`, `OidcActorResolver`, `VerifiedActor`. Synthetic RSA JWT verification exists; HTTP/BFF real-issuer integration **not** delivered.
- Commands: `AuthenticatedSalesCommands` → `SalesInventoryCommandService` → `OrderInventoryPort` → package-private reservation operations and stock movement persistence. **Exception in this main snapshot:** the six-argument `InventoryMovementService.adjust(..., Reason)` was public; P0 addressed in unmerged PR #4, not yet accepted in main. Standalone commands: `AuthenticatedInventoryCommands` → `InventoryAuthorizedCommandService`.
- Read models: `OrganizationLocationQueryService`, `StockBalanceQueryService`, `SalesOrderQueryService`; bounded paging and tenant filtering. CRM customer and organization location commands have permission checks.
- Business invariants: single-tenant orders, atomic confirm/reserve, cancel/release, fulfill/debit, immutable evidence, idempotent terminal retries, canonical SKU lock ordering, stock non-negativity qualification.

## Responsibility map

| Domain | Implemented service responsibility | Limit |
|---|---|---|
| IAM / ORGANIZATION | Active actor/tenant/member checks, tenant role administration, location read/write | Account/membership status-changing admin flows not yet qualified under shared RBAC lock convention |
| CRM | Tenant-scoped customer data/PII permission checks | Verified HTTP adapter and full PII lifecycle not qualified |
| SALES | Order list and transactional confirm/cancel/fulfill | No public controller; completion is not settlement or shipment proof |
| INVENTORY | Stock read, receive/adjust, reservation/release/debit behind narrow port | No valuation/COGS, multi-warehouse partial fulfill or financial ledger |
| FINANCE, MARKETING, WORKFLOW and other planned contexts | Roadmap only unless separately evidenced by source/tests | Do not label as delivered |

## Security/transaction rules

1. Never accept actor UUID from untrusted HTTP body/path/query. Resolve token identity through a verified authentication boundary.
2. Every mutating domain transaction requires `requireForCommand`: acquire tenant workspace lock before permission checks. On PostgreSQL ordinary commands use `FOR SHARE`; role changes take `FOR UPDATE` on the same row. H2 tests use `FOR UPDATE`.
3. Every account suspension, tenant/membership revocation admin operation must implement the same lock ordering **before public exposure**. This is **not** established end-to-end in this SHA; see unmerged PR #4 for proposed account/member/tenant fixes.
4. SALES order-linked stock operations require paired transaction-bound SALES and INVENTORY permissions from the same actor and tenant. Do not use raw stock services outside the inventory package.
5. No direct SQL manipulation of business state in runtime APIs. Least-privilege PostgreSQL grants and migration credentials separation still pending.
6. Do not alter already-applied Liquibase changesets or destroy persisted tenant data as a documentation cleanup.

## Evidence and outstanding gates

PR #2 exact HEAD `02c426dc5503f3e928e3b130c8475dbd884924bc`: GitHub Actions workflow `44895369000` **H2 and PostgreSQL successful**. Merge SHA above preserves this reviewed source. An independent security review, populated-database migration rehearsal, real OIDC/BFF HTTP E2E, least-privilege database controls, admin status/revocation concurrency and performance qualification remain open for deployment.

## Documentation policy

- This page is the historical **implemented** code baseline at the stated SHA; for normative decisions use RM-PLATFORM-STANDARD-R2.
- `docs/spec/` records design decisions, not automatic production acceptance.
- `docs/reviews/` records findings tied to historical SHA; do **not** treat earlier HOLD/Draft statements as current Git state.
- Superseded claims are removed from active documents; historical evidence remains in Git history and immutable audit trails.
