# RM DOMAIN-SERVICES-R1 — current read layer

**Status:** implemented in `main` as an internal domain read model after PR #2, **not** a public API.

- `OrganizationLocationQueryService`: `LocationDto`, MapStruct entity mapping, tenant-scoped JPA query, bounded paging.
- `StockBalanceQueryService` / `StockBalanceRepository`: stock DTO, tenant predicates and parameter-bound JDBC, availability = on_hand − reserved; COUNT and page are separate queries and may reflect different concurrent snapshots.
- `SalesOrderQueryService` / `SalesOrderRepository`: seller-side order list, status/date filtering, stable ordering and no buyer PII.
- All read services check active actor, workspace, membership and appropriate `DomainPermission` via `TenantAuthorization.require`; do not pass client-supplied actor UUID.
- Page size is capped at 100 and page indexes are zero-based. H2 + PostgreSQL tests protect dialect differences (including null search filtering).

**Limitations:** no real OIDC HTTP adapter, no consistent cross-query snapshot export, no warehouse-level ABAC restrictions; legacy read compatibility rights need business approval before external exposure.

Normative architecture and security policy: [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md). For the SHA-tied post-merge implementation map and release gates see [RM Domain Layer Baseline](../architecture/RM-DOMAIN-LAYER-BASELINE.md). Earlier branch-specific and future-tense statements have been removed.
