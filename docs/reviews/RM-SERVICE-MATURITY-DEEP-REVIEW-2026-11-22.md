# RM — повторная инвентаризация, матрица зрелости и cross-domain consistency (R3)

**Дата:** 2026-11-22. **Ветка:** `base_dev`. **Тип:** source-and-schema review + квалификационный реестр, **НЕ** новый полный прогон тестов.
**Текущий кодовый кандидат STOREFRONT:** commit `4edb0d9ce5c11e1cec8347cfd662ed2cc8f96cc4` (последний кодовый коммит перед этим обзором); перед release повторно получить actual branch HEAD и точный CI head_sha.
**Нормативный источник:** [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md). Исполняемый backlog: [RM-SVC-MATURITY-COMPLETION-R2](../spec/RM-SVC-MATURITY-COMPLETION-R2.md).
**Важное ограничение:** отдельные source review и исторический exact-SHA CI не являются доказательством качества нового HEAD. HTTP/OIDC / production — HOLD.

## 1. Методика и покрытие

Повторно прочитаны `DomainModule`, `DomainPermission`, `TenantAuthorization`, `AutoOrderService`, `SalesInventoryCommandService`, `SalesOrderQueryService`, `AuthenticatedSalesQueries`, `CatalogItemService`, `CatalogQualificationTest`, `OrganizationLocationService`, `CrmInteractionService`, `EmailIdentityService`, `AuthenticatedInventoryReconciliation`, `StorefrontSettings`, новый `StorefrontManagementService` и `StorefrontOfferService`, тесты `StorefrontManagementQualificationTest` и Liquibase `db.changelog-master.sql` (включая 0076–0080). Исторические результаты и остальные контуры сверены по ранее утверждённым ревью, **не** по полному исходному дереву/новому прогону всех тестов.

`DomainModule` регистрирует **15 bounded contexts**. Для продуктовой матрицы выделен `CRM Interaction` как отдельная функциональная capability CRM, поэтому **16 строк** — не 16 микросервисов.

### Шкала

0 — объявлен; 1 — отдельный фрагментарный функционал; 2 — самостоятельные бизнес-операции/процессы; 3 — PostgreSQL-проверенное функциональное ядро; 4 — полный **пользовательский** E2E с trusted external admission; 5 — независимое подтверждение production readiness. `CANDIDATE` не повышает подтверждённую оценку.

## 2. Матрица зрелости на данном обзоре

| Контур | Verified /5 | Кодовая способность / candidate | Проверенный источник и недостающие доказательства |
|---|---:|---|---|
| IAM / RBAC | **3** | Account/member/tenant roles/revocation, protected global profile and email proof; no genuine login path | Synthetic JWT/service tests and past exact-SHA PG; real OIDC/BFF/email provider, populated authorized PII cutover, full per-method TCRUD missing |
| CRM Core | **3** | Customer/Contact/Category/Contract, consent, expiry search, PII | Past exact-SHA PG; incomplete operational funnel, portfolio-scope decisions and cursor/search coverage |
| CRM Interaction | **2** | Record/assign/next-action/complete/cancel/history | Existing `CrmInteractionService`; NOTE body/meaningful communication result and TASKS join absent |
| CATALOG | **2** | PRODUCT/SERVICE, local SKU, translations, effective price revisions, publish/archive | Catalog tests and transactional service; archive still lacks INVENTORY usage + active-offer guard |
| SALES / AutoOrder | **2** | Checkout proof, immutable line SKU/price, confirm/reserve/cancel/fulfill | PASS 08.1–08.3 `DONE-QUALIFIED@cb490556`; staff detail/history/read, all 09–12/security and seller lifecycle need independent evidence |
| INVENTORY | **3** | Quantity Q1–Q9, stocktake/transfer, staff ledger, reserve, `AuthenticatedInventoryReconciliation.scan/inspect` | Quantity R1 `DONE-QUALIFIED@534aa180`; R1.5 diagnostic code present, 0080 durable run/finding DDL present but durable run lifecycle unqualified in this review |
| FILES | **3** | Staging, metadata, guarded bytes, LOCAL/RustFS, ClamAV adapter | Past qualified subset; additional typed owners / media queries / real production AV and recovery required |
| ORGANIZATION | **2** | Lifecycle update/deactivate/reactivate/close with warehouse blockers | Six PostgreSQL races `DONE-QUALIFIED@69f757cf`; populated ORG upgrade and full cross-domain 08–12 still HOLD. Level-3 candidate for qualified subset only |
| GEO | **2** | KATO reference/eligibility | Full source freshness and nationwide data upgrade unverified |
| KMS | **2** | Tenant encryption DEK / fenced policy; `rotate` intentionally unsupported | Full runtime rotation, recovery and migration not qualified |
| AUDIT | **1** | Append-only events, retention/legal hold infrastructure | Tenant-safe, cross-domain sanitized query and all producer coverage absent |
| STOREFRONT | **1** (**2 candidate**) | Previously lookup only; now create/publish/pause/get and draft/price/publish/pause/hide offer with row-version CAS | New code `CODE PRESENT`, CI not confirmed, no comprehensive seller revoke×mutation + loaded PG qualification; not at level 3 |
| TASKS | **0** | `TaskState` and `task_item` DDL | No accepted `TaskService` lifecycle |
| FINANCE | **0** | Money and invoice schema | No accepted invoice/payment/allocation/reconciliation service |
| MARKETING | **0** | Campaign enum and DDL | No consent-gated dispatch |
| SUBSCRIPTION | **0** | Entitlement and subscription schema | No enforcement/usage/billing governance |

**Суммарное состояние:** 0/16 domains подтверждены на уровне 4/5, 4 verified at level 3/5 (IAM, CRM Core, Inventory, FILES), остальные — ниже. ORGANIZATION имеет квалифицированную подсистему гонок, но сохраняет 2/5 до общей доменной приёмки; STOREFRONT — 1/5 до новых тестов.

## 3. STOREFRONT R1.1A/B — кодовая инвентаризация и source review

### 3.1 Повторно обнаруженная схема (важное исправление к прежнему ТЗ)

- `crm.store_front` **уже имеет** `row_version BIGINT NOT NULL DEFAULT 0`, `uk_store_tenant UNIQUE(tenant_id)`, `uk_store_slug UNIQUE(slug)`. Следовательно, **одна storefront на tenant** и никакой новой миграции под row version не нужно.
- `crm.store_offer` привязан FK через `tenant_id`; `catalog_item_id` имеет same-tenant FK. Changeset `0078` уже добавил `row_version` и `DRAFT/PUBLISHED/PAUSED/HIDDEN`; changeset `0079` (PostgreSQL) ограничивает remap SKU.
- `AutoOrderService.checkout` блокирует Stripe→`store_front FOR UPDATE`→published offer+catalog `FOR UPDATE`, проверяет PRODUCT/PUBLISHED, сохраняет `sales_order_line.catalog_item_id_snapshot`, title/price/qty/currency и email outbox. Existing idempotency receipt проверяется до запрета PAUSED (replay принятого заказа допустим).
- Ранняя R1.1A имела state-only CAS, ошибочно названный строгим. Исправлено: `StorefrontManagementService` теперь проверяет `expectedVersion` и атомарно увеличивает `row_version`, включая защиту от ABA.

### 3.2 Добавленный функционал (только CODE PRESENT до CI)

- `StorefrontManagementService`: `createDraft`, `publish`, `pause`, `get` с `VerifiedActor`, `STOREFRONT_OFFER_MANAGE/PUBLISH`, `TenantAuthorization.requireForCommand`, row-lock, monotonic CAS, action audit; PUBLISHED→PAUSED→PUBLISHED.
- `StorefrontOfferService`: `createDraft`, `updatePrice`, `publish`, `pause`, `hide`, `get`. Цена принадлежит seller offer, а не автоматической `cat_price` projection; запрещён SERVICE, FOREIGN и ARCHIVED SKU, PUBLISHED offer требует PUBLISHED PRODUCT; catalog binding не меняется; price update and transitions advance `row_version`; accepted order snapshot не обновляется.
- `StorefrontManagementQualificationTest`: positive lifecycle, A/B tenant denial, missing publish grant, stale/ABA, immutable line price/SKU after live price update, SERVICE and foreign offer negatives, concurrent price×checkout (two workers; order snapshot only old or new coherent offer price/title).
- Миграций, второй витрины, второго CATALOG, контроллеров и новых permissions **не добавлено**.

### 3.3 Статусы тестов и ограничения доказательности

**CI:** для test commit `e4e2f78...` обнаружен GitHub Actions run [44991446904](https://github.com/alimbetov/RM/actions/runs/44991446904) с jobs `h2-smoke`, `postgres-integration`, `postgres-pii-populated` в статусе `queued` при проверке; source commit `36480d5f...` имеет [run 44991544050](https://github.com/alimbetov/RM/actions/runs/44991544050) также `queued`. Это **НЕ GREEN** и не тесты latest `4edb0d9c...`; exact latest-SHA qualification остаётся OPEN.

**P0 gaps:** full two-transaction `revoke×seller command` both interleavings, `pause storefront×checkout` with deterministic PG barriers, duplicate slug race, rollback/failure injection, `offer publish×CATALOG archive` and stock admission, full populated 0078–0080 migration, seller offer search/history, PII/permission read. `StorefrontOfferService` хранит action audit, но не отдельный неизменяемый price-delta journal; историю конкретной цены оффера пока нельзя считать полномасштабной.

**Security note:** `STOREFRONT_OFFER_*` permissions в текущем `DomainPermission` отнесены к `DomainModule.SALES` несмотря на отдельный `DomainModule.STOREFRONT`. Existing role grants use explicit codes and work across packages, но ownership map нужно согласовать без ломки historical grants; это P1 design debt, не подтверждённая уязвимость.

## 4. Междоменная согласованность

| Граница | Источник истины | Текущая оценка | Открытый риск |
|---|---|---|---|
| IAM→все commands | Global actor, workspace/member, explicit tenant permission | Working internal admission | Real HTTP/OIDC, revoke-vs-new commands across all facades, system worker identity |
| CRM→SALES | CRM Customer отдельно от global buyer | Correct separation | Не реализован утверждённый optional mapping, не подменять UUID |
| CATALOG→STOREFRONT | SKU/kind/state у CATALOG; цена и state оффера у STOREFRONT | New writer present, unqualified | CAT.archive vs stock/offer, no catalog auto-publication; strict serializer via PG |
| STOREFRONT→AutoOrder | Storefront published + offer published + PRODUCT; immutable order snapshot | Code coherence present | Real concurrent pause/price×checkout exact PG, current SHA |
| SALES→INVENTORY | SALES line snapshot; INVENTORY reserve/stock/ledger | Quantity core qualified previously | Replay per-line identity/warehouse, cancel least privilege, historical locations |
| ORGANIZATION→INVENTORY | Location status; INV stock commitments | Qualified selected R1.1 pairs | Full lifetime/deactivate×all movement types, loaded migration |
| CRM Interaction→TASKS | Interaction owner CRM; Task aggregate owner TASKS | Not yet implemented | No Task service |
| SALES→FINANCE | SALES order vs independent invoice/payment | Not yet implemented | No invoice/payment/allocation |
| FILES→CRM/CAT/SALES | FILES metadata, each domain owner ACL | COMPANY integration qualified subset | PERSON/CATALOG/SALES typed ACL absent |
| INVENTORY→AUDIT/RECON | Immutable movements + reservation history | Diagnostic code present | Durable end-to-end reconciliation run with findings not qualified, no auto-fix |
| IAM→MAIL | Encrypted identity/challenge/outbox | Internal only | No qualified external provider+anti-abuse |

## 5. Инвентаризация дефектов / severity

| ID | Severity | Проблема | Исправление и gate |
|---|---|---|---|
| MAT-P0-01 | P0 | Latest STOREFRONT R1.1B не имеет exact-SHA H2/PG GREEN | Read Actions job outcomes and failure logs; fix actual failures without weakening tests |
| MAT-P0-02 | P0 | CATALOG.archive не проверяет onHand/reserved/open stocktake и published offers | Tenant item lock + typed read-only Inventory/Storefront dependencies; PG archive×stock/offer race |
| MAT-P0-03 | P0 | Storefront/offer CAS races vs checkout and revoke не полны | Two independent PG transactions and deterministic lock order; A/B/AB; both winner orders |
| MAT-P0-04 | P0 | End-to-end transactional business evidence не объединено на одном SHA | SKU→offer→checkout→confirm→reserve→fulfill, historical snapshot+reconciliation PG |
| MAT-P1-01 | P1 | SALES staff read/history and buyer pagination limited | Guarded get/detail/history, bounded list with PII separate |
| MAT-P1-02 | P1 | Inventory diagnostic durable run/finding orchestration не квалифицирован | Run lifecycle, checkpoint/resume/failure, complete ledger scan/no mutation, PG |
| MAT-P1-03 | P1 | Full ORM/ORG lifecycle populated migration and distributed race set not closed | ORG R1.2 qualified full test matrix |
| MAT-P1-04 | P1 | Catalog i18n search/price policy and storefront commercial price history incomplete | Explicit approved method and immutable sale history; no forced catalog sync |
| MAT-P1-05 | P1 | STOREFRONT ownership permissions mapped SALES module | Document compatibility, owner-domain resolution without changing existing granted codes silently |
| MAT-P1-06 | P1 | CRM Interaction NOTE doesn't store meaningful encrypted result; TASKS missing | Purpose-scoped encrypted notes and typed TASKS linkage after decision |
| MAT-P1-07 | P1 | Real MAIL/PII migration and read owner ACL open | Independent security and integration gates |
| MAT-P2-01 | P2 | FINANCE/MARKETING/SUBSCRIPTION declared only | Each own service lifecycle after commercial core, no fake readiness |

## 6. Приёмка и следующий инкремент

**Code completion acceptance:**
1. Full H2 + PostgreSQL + populated migration runs **same exact HEAD SHA**, no failures/errors and explicit skip accounting; tests actually executed.
2. Seller publish/pause + offer price/state CAS with monotonic version; concurrent checkout coherent price+SKU snapshot.
3. revoke×seller command both commit orders; foreign tenant UUID denial; SERVICE and ARCHIVED fail closed; failure injection no partial order/ledger.
4. CAT.archive×new physical stock/new published offer contention; no stranded stock.
5. Service-only commercial E2E one exact SHA; no claim of user-facing 4/5 until real trusted HTTP/OIDC and email.
6. Independently qualify DB least privilege, backup/restore, observability and performance before 5/5.

**Decision:** STOREFRONT has meaningful new code but **verified maturity remains 1/5 pending CI**. INVENTORY/CRM/IAM/FILES verified core stays 3/5 on historical qualified SHAs; newly changed commercial chain remains production HOLD. Next safe code work is actual CI failure handling and deterministic two-transaction security/checkout race gates; no blanket maturity promotion from compiling classes.
