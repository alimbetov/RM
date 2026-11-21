# RM-ALL-DOMAINS-STANDARDIZATION-R1 — унификация 15 модулей

**Дата:** 2026-11-21. **Ветка:** `feat/rm-kms-r1-implementation`. **Статус:** DATABASE FOUNDATION / ARCHITECTURE STANDARDIZED; **business E2E + public production = HOLD**.
**База:** [главное ТЗ](../spec/RM-IMPLEMENTATION-BASELINE-R1.md), [план](../spec/RM-DOMAIN-IMPLEMENTATION-PLAN-R1.md), [аудит](../reviews/RM-DOMAIN-TECHNICAL-AUDIT-2026-11-21.md), [IAM admission](QUAL-RM-DOMAIN-R1.2-IAM-IDENTITY-STANDARD.md).

## 1. Фактический результат

1. Все 15 доменов имеют уникальный `DomainModule` (owner, пакет и prefix) и политику `DomainStandards` (`DataScope`, `Privacy`, `Retention`, `PartitionPlan`). `SupportedLocale` задаёт `kk,ru,en,zh-Hans`.
2. Существующий фундамент IAM/ORG/GEO/CRM/KMS/SALES/STOREFRONT **сохранён**, без переписывания предыдущих Liquibase changesets.
3. **Новый changeset 0017** создал tenant-scoped **базовые DB-контракты** для 8 отсутствовавших доменов (и вспомогательные связи): `file_object`, `file_storage`, `file_attachment`; `cat_item`, `cat_item_i18n`, `cat_price`; `inv_stock_balance`; `fin_invoice`; `mkt_campaign`; `task_item`; `audit_event`; `sub_plan`, `sub_subscription`. Все новые бизнес-таблицы физически расположены в schema `crm`, но *принадлежат* модулям по префиксу.
4. `cat_item_i18n` имеет `PRIMARY KEY(tenant_id,item_id,locale)`; `zh-Hans` поддерживается `VARCHAR(12)`. SKU tenant-scoped unique. Цены и количества остаются числовыми.
5. FILES таблицы имеют composite FK `(tenant_id,file_id)`; физические бинарные файлы в PostgreSQL **не хранятся**, нет работающего LOCAL/RustFS adapter.
6. INVENTORY баланс ссылается на `org_location` по `(workspace_id,id)` и на `cat_item` по `(tenant_id,id)`. `CHECK(on_hand>=0, reserved>=0, reserved<=on_hand)`; движения/резервы/транзакционный ledger ещё не реализованы.
7. FINANCE счёт связан с заказом через `(tenant_id,order_id)` и имеет tenant-scoped document number; платежей/проводок нет.
8. MARKETING/ TASKS имеют проверяемые состояния; не реализованы campaign sender/consent enforcement, task workflow; AUDIT — только метаданные событий с time index, ещё нет append-only DB roles/triggers.
9. SUBSCRIPTION разделяет глобальный план (`sub_plan`) и tenant subscription (`sub_subscription`); оплаты/entitlements enforcement нет.
10. Тесты `DomainPersistenceBaselineQualificationTest` проверяют наличие таблиц, cross-tenant negative FK, недопустимые состояния и локали, проверку остатков. `DomainModuleQualificationTest` проверяет 15 правил и locale allowlist.

## 2. Матрица стандартизации (НЕ функциональной готовности)

| Домен | Ownership/контракт | PostgreSQL baseline | Реальные бизнес-процессы |
|---|---|---|---|
| IAM | Да | Да, legacy privacy debt | Частично |
| ORGANIZATION | Да | Да | Частично |
| GEO | Да | Да, КАТО partial | Частично |
| CRM | Да | Да | Частично |
| KMS | Да | Да | Частично |
| SALES | Да | Да (autoorder skeleton) | Частично |
| STOREFRONT | Да | Да (settings partial) | Частично |
| FILES | Да | Да (0017) | Нет IO |
| CATALOG | Да | Да (0017) | Нет CRUD/pub service |
| INVENTORY | Да | Да (0017) | Нет movement/reservation |
| FINANCE | Да | Да (0017) | Нет payment/posting |
| MARKETING | Да | Да (0017) | Нет dispatch |
| TASKS | Да | Да (0017) | Нет workflow |
| AUDIT | Да | Да (0017) | Нет tamper-resistant sink |
| SUBSCRIPTION | Да | Да (0017) | Нет billing/entitlements service |

**Общее правило:** `есть базовая DB-схема` ≠ `реализован бизнес-модуль`. Полностью production-qualified доменов по всем критериям по-прежнему **0**.

## 3. Что остаётся блокирующим

- P0 IAM: plaintext email legacy migration и single global identity, OIDC/PKCE principal, verified email worker с lease/fencing, rate limits и account anti-enumeration. Не открывать публичный checkout.
- P0 FILES: private LOCAL adapter, KMS encryption для sensitive blob, owner ACL, virus/type/size gate, reconciliation, backup/restore.
- P1 CRM: optional encrypted buyer profile CRUD, PII isolation, consent and privacy erasure.
- P1 CATALOG/SALES: item publish state, price revision, proper snapshot, permitted order transitions, delivery/stock policy; временный `store_offer` заменить CATALOG-контрактом.
- P1 INVENTORY: append-only stock movement/reservation, concurrent no-oversell, lease/fencing, state reconciliation.
- P1 FINANCE: invoice issuance immutable snapshot, payment allocation and reversal, normalized currency and refund.
- P1 MARKETING: 4-locale approved templates, consent gate, delivery result events; PII-safe recipient resolution.
- P1 TASKS: assignee authorization, tenant-safe links, activity revision history.
- P1 AUDIT: atomic append-only event sink with no decrypted PII, grants, retention policy and tamper detection.
- P1 SUBSCRIPTION: plans/entitlement versions, payment gates, subscription lifecycle.
- Operational: PostgreSQL roles/grants beyond schema naming; partitioning remains only candidate until EXPLAIN/metrics, 2-pod load, restart/recovery; `main` stays unchanged.

## 4. Recommended qualification order

1. **QUAL-DOMAIN-DB-R1:** validate new 0017 tables across fresh and upgraded databases, negative tenant tests and H2/PostgreSQL.
2. **IAM-R1.2B** close public admission / email migration.
3. **FILES-R1.0** close encrypted private IO + owner ACL.
4. **CATALOG-R1.0 → SALES-R1.1 → INVENTORY-R1.0 → FINANCE-R1.0** end-to-end workflows.
5. **MARKETING/TASKS/AUDIT/SUB-R1.0** with permissions, outbox and lifecycle.
6. **PERF-R2** PostgreSQL performance/partitions and 2-pod qualification; independent security/business release gates.

**Итог:** теперь у всех 15 модулей есть единая нормативная архитектура и базовый persistence, но полную предметную готовность каждого доказывают только его отдельные бизнес-сценарии, security-negative и PostgreSQL E2E.
