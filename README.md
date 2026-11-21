# RM — modular CRM monolith

**Approved architectural standard:** [RM-PLATFORM-STANDARD-R2](docs/architecture/RM-PLATFORM-STANDARD-R2.md) — the sole normative design/security reference.
**Current implemented code baseline:** [Domain Layer Baseline](docs/architecture/RM-DOMAIN-LAYER-BASELINE.md).
**Integration:** PR #2 was merged to `main` at `126e312e82791a68d689cbcbef287163858e7859`.
**Deployment:** production and public HTTP/OIDC admission **HOLD**.

## Implemented
- Java 21; Spring Boot 4.1.3; PostgreSQL/H2; Liquibase SQL; JDBC/JPA; selective MapStruct.
- ORGANIZATION/IAM: tenant organization and membership checks, configurable tenant roles, explicit permission grants, immutable role audit.
- CRM: customer/PII security and tenant boundaries.
- SALES + INVENTORY: guarded seller order confirmation/reservation, cancellation/release, fulfillment/debit, stock receiving/correction; bounded query services.
- Verified-actor OIDC library boundary and synthetic RSA JWT tests. **No real public login or HTTP endpoint admission yet.**

## Current entrypoints
`AuthenticatedSalesCommands` and `AuthenticatedInventoryCommands` are typed internal adapters. Order-linked stock operations flow through `OrderInventoryPort`; raw reservation methods are package-private; the public reason-specific stock adjustment overload is a known P0 in `main`, fixed by proposed [PR #4](https://github.com/alimbetov/RM/pull/4). `TenantAuthorization.requireForCommand` holds a tenant lock during business mutations.

## Not yet delivered or qualified
- Real issuer/BFF/HTTP security chain and actual onboarding;
- coordinated user/membership suspension under the transactional authorization barrier;
- least-privilege DB role, filled-DB Liquibase rehearsal, production recovery and performance benchmarks;
- settlement/valuation/COGS and full delivery workflow.

## Documentation
- [RM-R2 unified platform standard — normative](docs/architecture/RM-PLATFORM-STANDARD-R2.md)
- [Current domain baseline](docs/architecture/RM-DOMAIN-LAYER-BASELINE.md)
- [FRZ-1 security review](docs/reviews/RM-FRZ1-SECURITY-REVIEW-2026-11-21.md)
- [SEC-R1.1 tenant roles](docs/spec/RM-SEC-R1.1-TENANT-RBAC.md)

**Document authority:** RM-PLATFORM-STANDARD-R2 defines approved rules; domain docs/ADRs provide detail; reviews document historical evidence; code and tests establish what is implemented. A standard approval is not production approval.

**Cleanup rule:** archive superseded plans in Git history; do not remove applied migration records, tenant data, financial history or immutable audit.
