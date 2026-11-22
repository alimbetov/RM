# RM Platform — CATALOG: функциональный аудит, проход 01/12

**Audit ID:** RM-CAT-FUNC-R1-P01. **Runtime source HEAD:** `77dffdf43eb179e6b355764204ce79b775f7e586`, `base_dev`, 22.11.2026. **Режим:** SOURCE REVIEW / SPECIFICATION ONLY; Java/DDL tests NOT RUN in this pass, no controller work, no merge main or production admission. **Previous domain:** [ORGANIZATION interim closure 07/12](RM-ORG-INTERIM-CLOSURE-07-2026-11-22.md).

## 1. Владелец и реальные границы CATALOG

CATALOG is tenant-owned identity, lifecycle, metadata/i18n and effective price history of a catalog item. `crm.cat_item` is source of stable SKU identity. `crm.cat_item_i18n` stores localizations. `crm.cat_price` stores append-only effective price revisions. `crm.cat_item_event` is immutable lifecycle/version evidence. IAM owns identity, tenant, role permissions. INVENTORY owns physical positions, reservation and movement. STOREFRONT `crm.store_offer` owns commercial offer visibility and sale-facing title/price. AUTOORDER / SALES own immutable buyer order and fulfillment. Do **not** create new parallel catalog aggregate, inventory tables, actor identity or generic status engine.

**Source files examined:** `src/main/java/kz/rm/catalog/{CatalogItem.java,CatalogItemService.java,CatalogQueryService.java}`; `src/test/java/kz/rm/catalog/CatalogQualificationTest.java`; `src/main/java/kz/rm/inventory/StockEligibility.java`; `src/main/java/kz/rm/autoorder/AutoOrderService.java`; `src/main/java/kz/rm/sales/SalesInventoryCommandService.java`; Liquibase `src/main/resources/db/changelog/db.changelog-master.sql` changesets `0017?` (initial cat item/price/i18n; verify exact changeSet label before migration), `0026` (offer→catalog item FK), `0068/0069` (model_code/events/immutable PG triggers). Existing cross-domain tests `InventoryReservationQualificationTest`, `SalesInventoryRbacQualificationTest` and `AutoOrderR1QualificationTest` are regression references.

## 2. Текущая функциональная матрица CRUD / lifecycle

| Method (actual) | Permission + TX | SQL write/read effect | Current policy / limitation |
|---|---|---|---|
| `CatalogItemService.createDraft` | VerifiedActor, `CATALOG_WRITE`, `@Transactional`, `requireForCommand` | INSERT `cat_item` state DRAFT, source `cat_item_i18n`, CREATED event version 0 | SKU normalized NFC/uppercase max 80; kind PRODUCT/SERVICE; source locale ru/kk/en/zh-Hans; no client tenant override |
| `updateModelCode` | CATALOG_WRITE | item `FOR UPDATE`, expectedVersion; update `model_code` + version and event | DRAFT only; no change to immutable SKU/kind; writes version even if same value |
| `upsertTranslation` | CATALOG_WRITE | locked DRAFT; UPDATE/INSERT locale name, bump item version, event | DRAFT only; no source name edit once published; description field exists but no writer; same-value update still bumps |
| `addPriceRevision` | `CATALOG_PRICE_MANAGE` | locked item; INSERT `cat_price`, bump version, event | Allowed DRAFT/PUBLISHED, forbidden ARCHIVED; amount >=0, scale<=4, currency 3 uppercase, validFrom nonnull; application disallows equal validFrom for same item |
| `publish` | `CATALOG_PUBLISH` | locked DRAFT, source name and *any* valid effective price by now; state PUBLISHED+version+event | No check of specific currency/offer binding/stock; does not publish store_offer |
| `archive` | CATALOG_PUBLISH | locked non-ARCHIVED; ARCHIVED+version+event | No check of stock/reservation/active offer/placed orders; terminal state |
| `CatalogQueryService.get(actor,tenant,item,asOf)` | CATALOG_READ, TX | typed item, source name, latest price `valid_from <= asOf` | Highest validFrom across **all currencies**; no country/price-list dimension |
| `search` | CATALOG_READ | count + LIMIT/OFFSET, kind,state, exact sku/model, createdAt DESC/id DESC | No name/locale search; no deep page cap; two SELECT snapshots not necessarily equal |
| `priceHistory` | CATALOG_READ | paged `cat_price` by tenant/item | Read-only; historical prices preserved |
| `history` | CATALOG_READ | last 1..100 events `cat_item_event` | Event code/version/time; internal actor stored but not exposed to read model |

`CatalogItem` is a small domain record with `Kind PRODUCT/SERVICE`, `State DRAFT/PUBLISHED/ARCHIVED` and `orderable() == PUBLISHED`. **Caution:** `orderable()` ignores kind and store_offer state; it is not sufficient to authorize a real stock-backed checkout or reservation.

**Unsupported features in source:** no public SKU edit, kind edit, sourceLocale change, description write, explicit service order/fulfillment flow, publish reversal/reopen from ARCHIVED, complete storefront offer publish/hide command, catalog→offer price synchronization, photo/file attachment aggregate, concrete category taxonomy or per-region price lists. No need to invent those in R1 without product demand.

## 3. PostgreSQL/data model facts (observed)

| Table | Principal schema conditions |
|---|---|
| `crm.cat_item` | PK `(tenant_id,id)`; unique `(tenant_id,sku)`, Kind CHECK `PRODUCT/SERVICE`, State CHECK `DRAFT/PUBLISHED/ARCHIVED`, locale CHECK; `row_version` and optional `model_code VARCHAR(80)` |
| `crm.cat_item_i18n` | PK `(tenant_id,item_id,locale)`; name ≤240, nullable description TEXT; composite FK tenant+item |
| `crm.cat_price` | PK `(tenant_id,id)`; money `NUMERIC(19,4)`, currency CHAR(3), valid_from timestamp, composite item FK, positive-price CHECK; index by `(tenant_id,item_id,valid_from)` |
| `crm.cat_item_event` | PK `(tenant_id,id)`; unique item/new_version, actor FK, action enum CHECK; PG immutable update/delete trigger from 0069 |
| `crm.store_offer` | PK `(tenant_id,id)`, title/`unit_price`/currency/state `PUBLISHED/HIDDEN`, **nullable** `catalog_item_id` added by 0026; composite FK `(tenant_id,catalog_item_id)` |
| `crm.sales_order_line` | immutable *values* `title_snapshot,unit_price_snapshot,quantity,currency,offer_id` at checkout; **no immutable `catalog_item_id_snapshot`** at reviewed HEAD |
| `crm.inv_stock_balance` | FK `(tenant_id,item_id)` → catalog item; no foreign tenant stock. `crm.inv_stock_reservation` also item/line/warehouse-scoped |

**Important distinction:** DB does not have UNIQUE `(tenant_id,item_id,valid_from)` on `cat_price`, although application checks count under `cat_item FOR UPDATE`; direct imports/legacy SQL may produce timestamp ties. The business query then breaks ties by created_at/id, not currency. Do not claim DB guarantees that application-only uniqueness. `CatalogItemService.addPriceRevision` lacks precision<=19 explicit guard; NUMERIC(19,4) catches overflow after SQL attempt.

**Current price model is a single latest effective revision for any currency**, rather than a currency-specific price list. The UI must not silently choose «the correct KZT price» if a later USD revision supersedes it: first confirm R1 price model (one currency per item vs independent series per currency). The conservative R1 choice is single active currency per SKU, with explicit currency-change/versioning policy. This decision is P1 and needs test before change, not a migration in pass 01.

## 4. Cross-domain source trace and concrete risks

### CATALOG → INVENTORY

`StockEligibility.requireProduct` uses `SELECT kind,state FROM crm.cat_item WHERE tenant_id=? AND id=? FOR UPDATE` after location row lock. For `receive/correct/writeOff/transfer` it requires `PRODUCT` and `state != ARCHIVED` (DRAFT/PUBLISHED accepted). For new `reserve` it additionally requires `PUBLISHED`. Existing terminal `release/fulfill` reuses reservation and does not currently re-check PRODUCT, though ORG pass 06 required a narrow active warehouse check for **new** fulfillment.

**CAT-01 P0: stranded physical inventory.** `CatalogItemService.archive` only locks catalog row; it does not reject onHand>0, reserved>0, ACTIVE reservations, OPEN stocktakes, or PUBLISHED store offers. If item with positive stock is archived, new transfer/correct/writeOff are denied by StockEligibility, leaving stock that cannot be lawfully moved to zero without special workflow. Active reserved fulfillment/release cleanup may still proceed, but unreserved onHand is stuck. Existing FK prevents deletion, NOT bad archival status. Fix policy: archive requires no positive onHand/reserved and no ACTIVE reservation / OPEN stocktake, checked by inventory-owned **read-only tenant/item scoped** usage port in same cat_item `FOR UPDATE` TX (no stock/order FOR UPDATE inside catalog). If held stock, direct operator to lawful stock operations while PRODUCT eligible, then archive. Historic movements/reservations need not block archive after zero stock and no live obligations.

**CAT-02 P0: pending orders.** Published SKU may be offered even if it has since been archived; CATALOG archive does not hide offers and AUTOORDER checkout does not join cat_item. In R1 prior to archiving a SKU with PUBLISHED store offers, require deterministic pause/hide of offer or transactionally refuse archive if currently listed. But a safe **offer hide** application command does not yet exist in this source; mark as STOREFRONT owner dependency. Do not let catalog mutate store_offer directly just to satisfy archive.

**Lock-order requirement:** new inventory-owned item-usage port must be nonlocking EXISTS, because INVENTORY mutations obtain **location row then cat_item FOR UPDATE**, whereas archive holds cat_item. If CATALOG archive then locks stock/location FOR UPDATE it can create a cycle. All inventory writes are required to lock cat_item before stock mutation; EXISTS after item lock should see committed old changes in PostgreSQL READ COMMITTED. Qualification with multi-SKU, transfer and stocktake is required. No new broker, saga or distributed mutex.

### CATALOG → STORE_OFFER → AUTOORDER

`crm.store_offer` is a separate **sale offer** with independent PUBLISHED/HIDDEN and price/title/currency. There is no source-verified method in `CatalogItemService` that writes offers. `AutoOrderService.checkout` looks up published `store_front` and published `store_offer`, reads only `title,unit_price,currency`; does **not** check `catalog_item_id`, `cat_item.kind/state`, or `cat_price`. It snapshots title/price/currency in `sales_order_line`; the source price is the offer, not `cat_price`.

**CAT-03 P0:** new checkout may accept (a) unmapped legacy offer (catalog_item_id NULL), (b) archived/DRAFT item linked to a PUBLISHED offer, or (c) SERVICE mapped to stock-required orders, and return PLACED even when SALES confirmation cannot reserve stock. Need explicit admission for *stock-backed PRODUCT offer* at checkout: mapped, `PRODUCT/PUBLISHED` in same tenant, subject to current published offer, lock offer and SKU coherently for the entire checkout (archiving cannot commit between checking and order snapshot). For SERVICE, either separate service-only order lifecycle is implemented and tested, or reject its entry into the inventory-required order path with a truthful domain reason; **publishing a service card in CATALOG remains allowed**, it simply cannot enter a PRODUCT reserve workflow. Do not silently treat service as stock.

**CAT-04 P1:** appending catalog price revision does not update `store_offer.unit_price`, and `cat_price` may use a different currency than the store_offer. Decide product policy: catalog price as reference and seller offer as the authoritative checkout sale price (recommended minimal R1), with explicit offer price update service + snapshot; OR adopt a single pricing owner and atomic offer activation. Never retroactively recalculate placed orders or assert that catalog price determines checkout price.

### STORE_OFFER → SALES confirm

`AutoOrderService.checkout` writes `sales_order_line.offer_id,title_snapshot,unit_price_snapshot,currency,quantity` but **no SKU snapshot**. `SalesInventoryCommandService.lines()` later executes `JOIN crm.store_offer s ON ...` and reads mutable `s.catalog_item_id` to choose inventory SKU for new confirm. `InventoryReservationService.reserve` repeats live join to offer SKU. Concurrent offer remapping can substitute a different product from the one originally offered. Existing confirmed retry compares only count of ACTIVE reservation rows and location, not per-line item/quantity. This is the already documented SALES pass 03/04 P0 contract; reuse it, no duplicate catalog snapshot entity.

**CAT-05 P0:** additive nullable `sales_order_line.catalog_item_id_snapshot`, new checkout writes immutable resolved SKU; NEW confirm reserves that snapshot, checks against reservation item and quantity. Historical NULL cannot be backfilled from current offer without evidence. Confirm/fulfill replay must verify per-line mapping and movement. Do not change old order title/price/quantity on catalog edits or price revisions. Catalog archival must not rewrite any old order or reservation.

## 5. Preliminary decisions for CATALOG R1, pending pass 02+ cross-check

| Decision | Proposed small contract | Why |
|---|---|---|
| DEC-CAT-01-A identity | SKU, item ID, tenant ID and kind immutable after create; modelCode/i18n names only DRAFT; archived terminal | Preserves stock ledger/confirmed orders and avoids global SKU migration |
| DEC-CAT-01-B publish | DRAFT→PUBLISHED only with title and effective price; price price-list/currency policy explicit; PUBLISHED not same as storefront PUBLISHED | Separate catalog admission from commercial offer availability |
| DEC-CAT-01-C archival | Reject while positive onHand/reserved, ACTIVE reservation, OPEN stocktake, or currently published dependent offers; leave all historical records intact | Prevent stock lockout and new unfulfillable orders |
| DEC-CAT-01-D service | SERVICE can exist and publish in CATALOG, but must not flow into inventory reserve/fulfillment; service order workflow later | No overengineering or phantom stock |
| DEC-CAT-01-E snapshot | Checkout snapshots immutable product SKU + offer title/price/currency and quantity; no offer remapping affects already placed orders | Product and financial integrity |
| DEC-CAT-01-F price | Authoritative buyer price is seller `store_offer` snapshot, NOT automatically latest `cat_price`; catalog price revisions do not mutate active offers | Retains current R1 behavioral reality while designing explicit offer update |
| DEC-CAT-01-G RBAC | tenant-selected VerifiedActor + command `requireForCommand`, with READ/WRITE/PRICE_MANAGE/PUBLISH separated | Match existing IAM and A/B isolated tests |

**Important qualification:** P01 decisions are **proposed architectural rules**, not changes deployed to running system. Detailed migration and definitive error contracts come in later passes.

## 6. Functional gaps and minimal implementation backlog

| Priority | ID | Source-backed gap | Minimal implementation target |
|---|---|---|---|
| P0 | CAT-G01 | archive no stock/offer dependency guard | inventory owner item-usage EXISTS (onHand/reserved/ACTIVE/open stocktake); storefront owner published offer EXISTS |
| P0 | CAT-G02 | checkout accepts PUBLISHED offer regardless catalog PRODUCT/PUBLISHED/mapping | validate mapped product before NEW checkout; separate service admission |
| P0 | CAT-G03 | mutable offer SKU used for confirm | immutable SKU snapshot + per-line verified reserve, reuse SALES pass 03 |
| P1 | CAT-G04 | catalogue revision price vs offer sale price decoupled without command contract | document reference vs sale price, create minimal offer price+publish/hide operations separately |
| P1 | CAT-G05 | name/description editing only draft, no description command | define explicit draft profile/translation update, avoid generic PATCH |
| P1 | CAT-G06 | price currency series ambiguous; no DB unique date; input precision | choose R1 per-item currency rule, validate numeric precision, effective date uniqueness |
| P1 | CAT-G07 | same-value model/translation update bumps version/event | normalized no-op at current expectedVersion, stale version still conflict |
| P1 | CAT-G08 | archive can orphan PLACED order operationally even if offer hidden afterward | define pending-order treatment, no automatic cancel/rewrite, explicit reject/cancel path |
| P1 | CAT-G09 | no name search/localized listing, no cursor/deep-page bound | add real business need driven search, avoid Elasticsearch at R1 |
| P1 | CAT-G10 | existing test coverage lacks archive×receive/offer/checkout, photo/folder links | independent PG concurrent domain tests and immutable E2E |
| P2 | CAT-G11 | published card metadata fix requires impossible draft update | specify audited correction vs commercial rename version; do not rewrite old orders |

**No overengineering:** don't add product variants/options, SKU family taxonomy, dynamic schema/attributes engine, geospatial pricing, Kafka event bus, product CMS, multi-currency smart pricing, offer synchronization triggers or another stock engine in pass 01.

## 7. Proposed next 7/12 depth and release qualification roadmap

| Pass | Area | Target |
|---|---|---|
| 01 | Source map, lifecycle, product/service, tenant, order impact | This review; SPECIFIED |
| 02 | Method-by-method create/update/i18n/price/publish/archive | Exact DTO/input, SQL/CAS/errors |
| 03 | PostgreSQL integrity and pricing/locale schema | Immutable constraints, populated upgrade, effective price policies |
| 04 | PRODUCT vs SERVICE and publication/archival state machine | dependency guards, corrections, legacy |
| 05 | STOREFRONT / AutoOrder offer integration | offer publication, SKU snapshot, price vs offer, checkout |
| 06 | INVENTORY / SALES and concurrency | admission, archive×stock, confirm/replay/fulfill, lock graph |
| 07 | IAM, tenant RBAC, integration and interim closure | A/B/AB, revoke, final backlog; then optionally next service |
| 08–12 | Deep search, SQL migration, recovery, load, final release gates | DEFERRED if user retains same milestone as ORGANIZATION |

**Working convention:** keep named 12-pass format, but prior user opted to switch domains after 07 passes of ORGANIZATION. For CATALOG this is a proposed working stage—not an assertion that its remaining tests are complete after seven.

## 8. Acceptance criteria CAT-P01-Q01…Q18 (planned, NOT RUN)

| ID | Scenario | Required outcome |
|---|---|---|
| P01-Q01 | A/B same SKU code | Two independent catalog item IDs, no cross-tenant conflict |
| P01-Q02 | Unsupported SKU/name/locale/type | Fail, zero item/event writes |
| P01-Q03 | DRAFT PRODUCT and SERVICE | Correct immutable kind/tenant; stock forbids SERVICE |
| P01-Q04 | DRAFT updateModelCode and upsertTranslation, stale expectedVersion | One atomic version/event, stale conflicts |
| P01-Q05 | PRICE append/repeated validFrom/precision/currency | Deterministic effective price and controlled validation |
| P01-Q06 | PUBLISH with no effective price or source name | Denied, state remains DRAFT |
| P01-Q07 | PUBLISH with valid price, then ARCHIVE | Exact state/versions/events, never unarchive |
| P01-Q08 | Archived item with positive onHand but zero reserved | New archival must be denied; previous code reproduces GAP |
| P01-Q09 | Archive with reserved/ACTIVE reservation or OPEN stocktake | Deny without inventory mutation |
| P01-Q10 | Archive item referenced by PUBLISHED offer | Deny until offer is hidden; no auto-delete offer |
| P01-Q11 | Checkout linked to ARCHIVED/DRAFT/NULL catalog SKU or SERVICE | Deny new stock-backed PLACED, no orphan order/email outbox |
| P01-Q12 | Checkout vs archive race with PG barriers | Atomic order snapshot or blocked checkout, never unfulfillable new order due TOCTOU |
| P01-Q13 | Rebind store_offer catalog SKU between PLACED and confirm | Reservation must use immutable checkout SKU, no silent substitution |
| P01-Q14 | Catalog price revision after checkout | Historical order price unchanged; seller offer price only via explicit owner workflow |
| P01-Q15 | A/B mixed permissions: READ, WRITE, PRICE_MANAGE, PUBLISH distinct | No unauthorized mutation or cross-tenant leakage |
| P01-Q16 | Revoke/suspend CATALOG permission concurrently with publish/archive | IAM admission lock semantics, no partial side effects |
| P01-Q17 | PostgreSQL immutable `cat_price`/`cat_item_event` UPDATE and DELETE | Rejected by existing triggers; no history loss |
| P01-Q18 | Transfer/writeOff/reserve/archive mixed 2–3 PG sessions | No deadlock, no frozen nonzero stock after archive, no confirmed wrong SKU |

**Evidence standard:** exact source SHA; ≥2 PostgreSQL sessions for races; before/after counts `cat_item`, `cat_item_i18n`, `cat_price`, `cat_item_event`, `inv_stock_balance`, `inv_stock_reservation`, `store_offer`, `sales_order_line`; per-tenant FK and permission trace; no skipped tests. Current `CatalogQualificationTest` has 3 existing test methods for two-tenant operations, CAS/translation and PG immutable evidence. No new P01 cases were executed in this documentation pass.

## 9. Preliminary maturity verdict

**CATALOG = 2/5 (working core, incomplete cross-domain integrity).** This is an *audit judgment*, not a measured acceptance result. Catalog CRUD partial operations, publication, price history and tenant permission enforcement exist. Item archival vs stock, storefront mapping/product vs service admission, order SKU snapshot and pricing ownership remain material maturity gaps; production release qualification HOLD.

**Next pass 02/12:** strict method-by-method source trace and complete R1 DTO/mutation contracts for draft metadata, translation/description, price revisions, publication and archive; no new controller, no implementation until contract approval.
