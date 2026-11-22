# RM — modular CRM monolith

**Approved architectural standard:** [RM-PLATFORM-STANDARD-R2](docs/architecture/RM-PLATFORM-STANDARD-R2.md) — the sole normative design/security reference.
**Current implemented code baseline:** [Domain Layer Baseline](docs/architecture/RM-DOMAIN-LAYER-BASELINE.md).
**Integration:** PR #2 (domain baseline), PR #4 (IAM/RBAC security hardening), and the approved RM-R2 standard are merged into `main`. This does not imply production admission.
**Deployment:** production and public HTTP/OIDC admission **HOLD**.

## Implemented
- Java 21; Spring Boot 4.1.3; PostgreSQL/H2; Liquibase SQL; JDBC/JPA; selective MapStruct.
- ORGANIZATION/IAM: tenant organization and membership checks, configurable tenant roles, explicit permission grants, immutable role audit.
- CRM: customer/PII security and tenant boundaries.
- SALES + INVENTORY: guarded seller order confirmation/reservation, cancellation/release, fulfillment/debit, stock receiving/correction; bounded query services.
- Verified-actor OIDC library boundary and synthetic RSA JWT tests. **No real public login or HTTP endpoint admission yet.**

## Current entrypoints
`AuthenticatedSalesCommands` and `AuthenticatedInventoryCommands` are typed internal adapters. Order-linked stock operations flow through `OrderInventoryPort`; raw reservation methods are package-private; all raw stock movement adjustment overloads are package-private after merged [PR #4](https://github.com/alimbetov/RM/pull/4). `TenantAuthorization.requireForCommand` holds a tenant lock during business mutations.

## Not yet delivered or qualified
- Real issuer/BFF/HTTP security chain and actual onboarding;
- independent production security sign-off for IAM suspension/recovery, even though internal transaction tests are GREEN;
- least-privilege DB role, filled-DB Liquibase rehearsal, production recovery and performance benchmarks;
- settlement/valuation/COGS and full delivery workflow.

## Branch workflow
- `main`: reviewed integration baseline; do not develop directly in it.
- `base_dev`: **single active development branch** for RM-R2 tasks (starting with Inventory), with automatic H2 + PostgreSQL checks on push.
- Merge from `base_dev` to `main` through a reviewed pull request after exact-head CI; keep historical feature/audit branches read-only. No new feature branches unless explicitly approved for a risky or isolated experiment.

## Documentation
- [RM-R2 unified platform standard — normative](docs/architecture/RM-PLATFORM-STANDARD-R2.md)
- [Current domain baseline](docs/architecture/RM-DOMAIN-LAYER-BASELINE.md)
- [FRZ-1 security review](docs/reviews/RM-FRZ1-SECURITY-REVIEW-2026-11-21.md)
- [SEC-R1.1 tenant roles](docs/spec/RM-SEC-R1.1-TENANT-RBAC.md)
- [RM-MAIL-OTP-R1.1 — email OTP, KumoMTA mail outbox and localized templates (spec only)](docs/spec/RM-MAIL-OTP-R1.md)
- [RM-FILES-R1.1 — CRM media registry, entity attachments, access control and storage lifecycle (spec only)](docs/spec/RM-FILES-R1.md)
- [RM-TENANT-CRUD-COMPLETION-R1 — verified CRUD matrix, tenant isolation and gap-based service backlog](docs/spec/RM-TENANT-CRUD-COMPLETION-R1.md)
- [RM-TENANT-SERVICES-COMPLETION-R1 — coordinated service development, tenant permissions, domain contracts and QA gates](docs/spec/RM-TENANT-SERVICES-COMPLETION-R1.md)
- [RM-SVC-FUNCTIONAL-DEPTH-AUDIT-R1 — source-based business functionality, stubs and service completion gaps](docs/reviews/RM-SVC-FUNCTIONAL-DEPTH-AUDIT-2026-11-22.md)
- [RM-SERVICE-12-PASS-REVIEW-PROTOCOL-R1 — one domain at a time, 12 quality passes](docs/spec/RM-SERVICE-12-PASS-REVIEW-PROTOCOL-R1.md)
- [RM-INVENTORY-FUNCTIONAL-COMPLETION-R1 — 12-pass INVENTORY audit, domain alignment and phased implementation specification](docs/spec/RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md)
- [RM-INVENTORY-Q7-Q9-PG-PERFORMANCE-CLOSURE — exact-SHA PostgreSQL evidence, lock contention and 12-pass quantity sign-off](docs/reviews/RM-INVENTORY-Q7-Q9-PG-PERFORMANCE-CLOSURE-2026-11-22.md)

**Document authority:** RM-PLATFORM-STANDARD-R2 defines approved rules; domain docs/ADRs provide detail; reviews document historical evidence; code and tests establish what is implemented. A standard approval is not production approval.

**Cleanup rule:** archive superseded plans in Git history; do not remove applied migration records, tenant data, financial history or immutable audit.
