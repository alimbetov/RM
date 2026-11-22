# RM Platform — CATALOG: функциональное завершение, пошаговое ТЗ R1

**Document ID:** RM-CAT-FUNC-R1-12. **Старт:** 22.11.2026, `base_dev` source SHA `77dffdf43eb179e6b355764204ce79b775f7e586`. **Охват:** каталог PRODUCT/SERVICE, SKU, i18n, цены, публикация и архивация; перекрёстная согласованность с IAM, INVENTORY, STOREFRONT, AUTOORDER и SALES. **Режим:** Feature Freeze, анализ и проектирование; без REST-контроллеров, без лишних микросервисов, без merge main до квалификации.

**Основное исходное evidence:** [CATALOG pass 01 — source/method/schema/cross-domain review](../reviews/RM-CATALOG-FUNCTIONAL-DEPTH-PASS01-2026-11-22.md). Предыдущий [ORGANIZATION interim closure](../reviews/RM-ORG-INTERIM-CLOSURE-07-2026-11-22.md).

## 1. Бизнес-цель и границы ответственности

Одна организация создаёт стабильные SKU-карточки, продаёт товары и услуги с локализованными названиями и актуальными ценами, публикует карточки осознанно, не теряет историю заказов, не архивирует физические запасы так, что ими невозможно управлять, и не использует меняющуюся ссылку оффера для подмены SKU в уже оформленном заказе.

**Ownership:**
- **IAM:** глобальный пользователь, workspace/member, `VerifiedActor`, tenant roles `CATALOG_READ/WRITE/PRICE_MANAGE/PUBLISH`.
- **CATALOG:** `crm.cat_item`, `cat_item_i18n`, `cat_price`, `cat_item_event` и предметная политика `Kind/State`.
- **STOREFRONT:** `crm.store_offer`, seller commercial offer state, sale price/title/currency; не считать его таблицей CATALOG item.
- **INVENTORY:** `inv_stock_balance`, `inv_stock_movement`, `inv_stock_reservation`, quantity and fulfillment; SKU stored by tenant+id.
- **AUTOORDER/SALES:** order checkout snapshot, order line, immutable price/quantity, reserve, cancel/fulfill; не обновлять созданный заказ из каталога.

## 2. Что реализовано на исходном source

| Service | Реальные методы | Состояние |
|---|---|---|
| `CatalogItemService` | `createDraft`, `updateModelCode`, `upsertTranslation`, `addPriceRevision`, `publish`, `archive` | IMPLEMENTED, проверены исходники; 3 тест-метода в `CatalogQualificationTest` |
| `CatalogQueryService` | `get(asOf)`, `search(kind,state,sku,model,page,size)`, `priceHistory`, `history` | IMPLEMENTED; tenant-scoped, READ permission |
| `CatalogItem` | PRODUCT/SERVICE, DRAFT/PUBLISHED/ARCHIVED, `orderable` | Implemented type/record; `orderable` alone is NOT storefront admission |
| SQL migrations | `0017` item/i18n/price; `0026` store_offer catalog FK; `0068` model/events; `0069` PostgreSQL immutable triggers | EXISTING, not evidence of product-level warehouse eligibility |
| `Inventory.StockEligibility` | SKU row `FOR UPDATE`, only PRODUCT, reject ARCHIVED for new movement, require PUBLISHED for reserve | IMPLEMENTED |
| `AutoOrderService.checkout` | reads PUBLISHED offer price/title, snapshots them to order line | IMPLEMENTED, DOES NOT check catalog state/kind/mapping and DOES NOT snapshot SKU |
| `SalesInventoryCommandService.lines` | currently joins live `store_offer.catalog_item_id` | IMPLEMENTED, historical SKU substitution risk |
| `CatalogQualificationTest` | 3 tests: A/B price/publish, translation+CAS, PG append-only price/event | PRESENT, not newly rerun in this audit |

**Current professional assessment: 2/5 CATALOG.** A functioning tenant-scoped lifecycle and price ledger exist, but the highest business-integrity risks are on archive and checkout/order bindings. This is a **source-reviewed maturity estimate**, not a release certification.

## 3. Минимальное R1 целевое поведение

### 3.1 Identity and profile

- `tenant_id,id,sku,kind,source_locale,created_at` immutable after create. SKU normalized NFC, uppercase, max 80. Uniqueness `UNIQUE(tenant_id,sku)`, duplicate SKU across different tenants is legal.
- `model_code` optional; name mandatory in source locale, 1..240; `description` nullable and currently unwritten. For R1 add only a small explicit draft translation/profile method if business case confirmed in pass 02, no generic attribute engine.
- `row_version` one optimistic state; all mutations lock `crm.cat_item(tenant_id,id) FOR UPDATE` and require expectedVersion, with exactly one version step and event on actual mutation. Preserve stable old event history.

### 3.2 Kind and status

- `DRAFT`: may correct model code/names, add prices, internal stock receipt for PRODUCT if existing INVENTORY admission permits DRAFT (no reserve yet); SERVICE never participates in quantity inventory.
- `PUBLISHED`: new PRODUCT reserve allowed under ACTIVE WAREHOUSE. Existing name/sku/kind immutable; price revisions append-only. Public sale still requires independently PUBLISHED `store_offer` with mapped and eligible SKU.
- `ARCHIVED`: terminal status; history still readable; no new price, stock mutation/reservation. **Prerequisite**: zero positive stock/reserved, no ACTIVE reservation, OPEN stocktake or active PUBLISHED offers. Do not silently destroy/deactivate other domains' records.
- `SERVICE` can be created/published as catalog card, but **cannot be used in stock-based confirmation** until a separately proven service order workflow exists. New checkout on SERVICE in current stock-required path should reject, rather than create unfulfillable PLACED order.

### 3.3 Effective prices and offers

- `cat_price` append-only revisions with `valid_from`, currency and amount, versions/events. No retroactive mutation of `sales_order_line.unit_price_snapshot` or `store_offer` on catalog price change.
- **R1 proposal**: `store_offer.unit_price/currency` remains authoritative sale price at checkout; catalogue price is reference and requires explicit seller offer update to affect future orders. No new pricing engine.
- Pass 03 must settle currency semantics: existing effective query chooses latest record across currencies. Choose either a single currency per item or explicit per-currency series; do not silently mix KZT and USD or claim latest effective KZT when none is selected.
- DB `cat_price` has no unique key on `(tenant,item,valid_from)`; current Java count check is transactionally fenced by item lock, not enforced against external SQL. Additive populated-safe DB validation only if supported by source data.

### 3.4 Cross-domain admission and invariant

- **Archive stock blocker:** CATALOG item locked first; invoke inventory-owned read-only tenant+item usage flags via `EXISTS`, **never** lock location/balance after cat_item (would invert current INVENTORY location→cat_item order). Reject if positive onHand/reserved, ACTIVE reservation or OPEN stocktake. Preserve old movements.
- **Archive offer blocker:** reject if a currently PUBLISHED offer references item; seller must hide offer first through STOREFRONT-owner command. No catalog direct edit of offer.
- **New checkout stock-backed PRODUCT:** require PUBLISHED offer with non-null mapped catalog item, state=PUBLISHED, kind=PRODUCT, and coherent offer/catalog state under appropriate PG locks; distinct SERVICE sale path deferred. Seller payment price/title snapshots and mapped SKU snapshot belong to AUTOORDER.
- **Already PLACED order:** new column `sales_order_line.catalog_item_id_snapshot` (additive nullable legacy) as per SALES pass 03. Confirm/reserve use immutable snapshot; no substitution from live offer.
- **Confirmed/fulfilled historical order:** catalog archive or title/price change must not mutate order line snapshots/reservation/movement. Any requested terminal replay checks committed source evidence per line.
- **Tenant:** all PK/FK and SQL scoped tenant, IAM permission before business reads, platform administrator without membership no implicit CATALOG write.

## 4. GAP / решения прохода 01

| ID | Priority | Requirement |
|---|---|---|
| CAT-G01 | P0 | Safe archive with stock/reserve/stocktake+offer guard |
| CAT-G02 | P0 | Checkout must verify mapped eligible PRODUCT vs SERVICE |
| CAT-G03 | P0 | Add immutable order-line SKU snapshot; existing live JOIN must not choose SKU |
| CAT-G04 | P1 | Price catalog vs seller offer ownership and update contract |
| CAT-G05 | P1 | Controlled draft description and translation input, no generic CMS |
| CAT-G06 | P1 | Currency/effective-date/precision invariants |
| CAT-G07 | P1 | Idempotent normalized no-op (fresh CAS), no version/event for unchanged draft |
| CAT-G08 | P1 | Pending PLACED order behaviour when seller hides/archives items; no orphan workflow |
| CAT-G09 | P1 | Name/locale search, bounded deep paging only if needed |
| CAT-G10 | P1 | PG archive×stock and checkout×archive mixed concurrency tests |
| CAT-G11 | P2 | Audited published metadata corrections if genuine use case, no historical rewrite |

**No overengineering R1:** no dynamic attributes/variants, category graph, elasticsearch, automatic offer sync, shared generic workflow, Redis lock for local PG transaction, new microservices or artificial archival cascade.

## 5. Проходы по сервису и критерии готовности

| Pass | Обследование | Статус |
|---|---|---|
| 01/12 | SOURCE/CRUD/state/SKU and STOCK/SALES cross-domain map | SPECIFIED |
| 02/12 | Every command DTO, normalization, CAS, SQL, errors, item/service differences | OPEN |
| 03/12 | PostgreSQL schema, price effective semantics, i18n/description, populated migrations | OPEN |
| 04/12 | DRAFT→PUBLISHED→ARCHIVED state machine; stock/offer guards | OPEN |
| 05/12 | STOREFRONT offer publish/hide; checkout product+SKU+price snapshots | OPEN |
| 06/12 | INVENTORY/SALES concurrency, reservation, fulfillment, archival and replay | OPEN |
| 07/12 | IAM A/B/AB RBAC, revocation/owner onboarding, interim closure | OPEN |
| 08/12 | Search UX, locale/filter/page/indexes | DEFERRED milestone |
| 09/12 | Populated PG migration/audit qualification | DEFERRED milestone |
| 10/12 | Mixed-concurrency and recovery | DEFERRED milestone |
| 11/12 | Performance/load and EXPLAIN p95/p99 | DEFERRED milestone |
| 12/12 | Final evidence matrix and production release gate | DEFERRED milestone |

User approved a 07-pass **interim closure for previous ORGANIZATION**, not a claim of complete CATALOG qualification. Use the same tentative seven-pass audit milestone while preserving 08–12 as explicit not-done release gates unless user changes plan again.

## 6. Source-to-acceptance baseline

[Full CAT-P01-Q01…Q18 matrix](../reviews/RM-CATALOG-FUNCTIONAL-DEPTH-PASS01-2026-11-22.md) covers same SKU A/B, PRODUCT vs SERVICE, create/stale edit/price, publishing, archive with stock/reservations/OPEN stocktake/active offer, checkout of missing/archived/service SKU, checkout×archive, rebinding offer after PLACED, price change after checkout, revocation, immutable audit and PG transfer×archive races.

**Status:** all new `CAT-P01-Q01…Q18` are PLANNED/NOT RUN. Existing 3 `CatalogQualificationTest` test methods not rerun. No new Java code, DDL or database mutation was performed by this audit. No code merged to main. Production HOLD.

## 7. Следующий шаг

Pass 02/12 = **метод за методом** `CatalogItemService.createDraft/updateModelCode/upsertTranslation/addPriceRevision/publish/archive` and `CatalogQueryService`: exact input DTO, normalization, typed error reasons, optimistic CAS, SQL emitted, no-op, immutable attributes, validation boundaries, currency decision and how business-service requests obtain trust. Only after source/rule agreement write implementation task.

**Контроль соответствия:** вся будущая реализация обязана быть проверяемой PostgreSQL concurrency/regression tests, а не «зелёной» только потому, что описана в спецификации.
