# RM-DOMAIN-IMPLEMENTATION-PLAN-R1 — создание всех доменов по ТЗ

**Дата:** 2026-11-21. **Ветка:** `feat/rm-kms-r1-implementation`. **Статус:** Java domain foundation implemented, persistence/REST still staged.

## Coverage

All 15 bounded contexts are registered in `kz.rm.architecture.DomainModule`, each with one unique table prefix and canonical package:

| Домен | Префикс | Java foundation now | Persistence status |
|---|---|---|---|
| IAM | `iam_` | existing UserAccount, membership, Workspace | existing IAM tables; tenant naming migration pending |
| ORG | `org_` | `organization.OrganizationLocation` contract; existing OrganizationEntity in IAM module | existing `org_organization`, `org_location` |
| GEO | `geo_` | KATO directory/entity | legacy `ref_kato_territory` |
| FILES | `file_` | `files.FileObject` domain metadata/state | planned; **no FILES tables created** |
| CRM | `crm_` | existing `CrmCustomerService` | experimental `crm.customer/contact`, `crm_pii.*` |
| CATALOG | `cat_` | `catalog.CatalogItem` domain contract | planned; no catalog tables |
| SALES | `sales_` | `sales.OrderState` and AUTOORDER service | provisional `crm.sales_order`, lines, status events |
| INVENTORY | `inv_` | `inventory.StockPosition` validated invariant | planned; no stock tables |
| FINANCE | `fin_` | `finance.Money` currency-safe value object | planned; no billing tables |
| MARKETING | `mkt_` | `marketing.CampaignState` | planned; no campaign tables |
| TASKS | `task_` | `tasks.TaskState` | planned; no task tables |
| AUDIT | `audit_` | `audit.AuditAction` metadata-only contract | planned; no audit table |
| KMS | `kms_` | existing CryptoContext / TenantCryptoPort | existing KMS tables and compatibility barriers |
| SUBSCRIPTION | `sub_` | `subscription.PlanEntitlement` | planned; no subscription tables |
| STOREFRONT | `store_` | `storefront.StorefrontSettings`; `autoorder.AutoOrderService` application flow | experimental `crm.store_front`, `crm.store_offer` |

**No JPA entity annotations on new domain contracts:** this is deliberate. Their lifecycle and invariants are defined first, then module-specific PostgreSQL DDL, adapters and APIs are implemented only with corresponding integration tests. Not all business entities listed in functional TЗ exist yet.

## Cross-domain invariants

- `tenant_id UUID` is seller/business data owner. `region_zone_id VARCHAR(2)` is geography, not owner, auth identity or KMS key.
- Business tables follow module prefix regardless of common schema. Existing physical table names are legacy until safe subsequent migration, without modifying prior Liquibase changesets.
- Sensitive fields encrypted via KMS; unique global identity email has independent IAM-only AES/HMAC keys; no plaintext in domain/audit events.
- FILES objects are metadata identities, actual binary storage initially LOCAL durable server disk, later RustFS; clients and business links use file_id only.
- Storefront emails mandatory for autoorders. Buyer Customer/Contact/phone/name/profile optional. Ownership proof before buyer order tracking.
- All user-facing locales: `kk,ru,en,zh-Hans`; no locale partition key.
- Archive mutable entities; ledger, consent, order history and audit use immutable corrective events and retention policies.
- Define partition candidates at design time, benchmark PostgreSQL before native partition DDL. Keep small reference/master tables indexed.

## Done vs not done

**Done**: module registry, unique prefixes, initial Java contracts/invariants and tests, all 15 modules have a source-code home or existing implementation.

**Not done**: complete domain JPA entity relationships, all module schemas and Liquibase changesets, module APIs, separate buyer profile CRUD, KMS-backed file storage, i18n resources, advanced consent/document/stock workflows, true regional partitioning, performance qualification and production access.

## Next engineering gates

1. Fix any H2/PostgreSQL CI regression in feature branch; do not merge main.
2. Complete IAM-auth authenticated identity and legacy naming migrations; KMS encryption compatibility round-trip.
3. FILES-R1.0 PostgreSQL DDL/LOCAL adapter with tenant owner access.
4. CRM-R1A voluntary buyer profile fields + checkout optional metadata and encryption.
5. CATALOG/Sales publication and immutable order snapshot with composite FKs.
6. INVENTORY+FINANCE, then MARKETING/TASKS/AUDIT and SUBSCRIPTION persistence in independently testable vertical slices.

This document records the boundary of implementation; **a registered domain is not a production-ready business module**.


## Ревизия DOMAIN-REVIEW-R1 — 2026-11-21

Проведён жёсткий анализ actual Java/Liquibase/CI: [RM-DOMAIN-TECHNICAL-AUDIT-2026-11-21](../reviews/RM-DOMAIN-TECHNICAL-AUDIT-2026-11-21.md). **Реестр DomainModule больше не хранит Boolean о готовности модулей**; единственная его ответственность — идентичность модуля, пакет и префикс. Проверены двойные префиксы имён таблиц и лимит PostgreSQL идентификаторов. Остаются P0-gates: проверка actor в OrganizationLocationService, конкурентный checkout, защита legacy email identity и OIDC/outbox sender. Все новые модули CONTRACT ONLY до собственных persistence и integration test.
