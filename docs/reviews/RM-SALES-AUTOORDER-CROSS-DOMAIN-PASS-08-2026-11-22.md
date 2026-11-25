# RM SALES / AutoOrder — PASS 08/12: cross-domain ownership, immutable offer and stock contract

**Source SHA:** `e66a82fbda82ae0d1272d64ec09c593cf961a411`, branch `base_dev`, date 2026-11-22.
**Status (current):** PASS 08.1–08.3 `IMPLEMENTED / POSTGRESQL QUALIFIED` on exact SHA `cb490556e56d74770ee86f839a849a354702673f`; PASS 08.4 seller-offer lifecycle and PASS 09–12 `SPEC PROPOSED / HOLD`. Earlier source-only sections below are historical review evidence, not the current implementation state. INVENTORY quantity Q1–Q9 is independently PostgreSQL-qualified on `534aa18008bc54f77a4b1f0277d0f041bfa12bae`, see [inventory performance/closure](RM-INVENTORY-Q7-Q9-PG-PERFORMANCE-CLOSURE-2026-11-22.md).
**Scope:** existing `AutoOrderService.checkout/verifyEmail/buyerOrders`, `SalesInventoryCommandService.confirm/cancel/fulfill`, `InventoryReservationService.reserve`, `CatalogItemService.publish`, `crm.store_offer`, `crm.sales_order_line`, `crm.cat_item`, `crm.inv_stock_balance`. No service rearchitecture.

## 1. Real source-to-consumer map

| Producer → consumer | Current verified code | Actual boundary / problem |
|---|---|---|
| CATALOG → INVENTORY | `CatalogItemService.publish` changes `crm.cat_item.state`, `StockEligibility.requireReservable` rejects non-PUBLISHED/SERVICE SKU | Correct physical admission, **not** a storefront publishing action |
| CATALOG → STOREFRONT | `cat_item` and append-only `cat_price`; separate `crm.store_offer(title,unit_price,currency,state,catalog_item_id)` | No linked seller-owned offer publication/synchronization; catalogue price revision does not determine checkout price |
| STOREFRONT → checkout | `AutoOrderService.checkout` requires published `store_front` and each `store_offer.state='PUBLISHED'`, reads server price/title/currency | No validation of stockability or non-null catalogue SKU; checkout may yield PLACED order not confirmable |
| checkout → SALES order line | `sales_order_line` stores `offer_id,title_snapshot,quantity,unit_price_snapshot,currency` | **Missing immutable `catalog_item_id_snapshot`**: SKU identity is not anchored at buyer acceptance |
| SALES → INVENTORY | `SalesInventoryCommandService.lines` SELECTs `s.catalog_item_id` from *live* `crm.store_offer`; `InventoryReservationService.reserve` reads same live offer | If offer-to-SKU mapping changes after PLACED, confirm could reserve another SKU without buyer consent. Composite tenant FK alone does not solve temporal integrity |
| SALES transaction → inventory | `OrderInventoryPort` requires same actor/tenant with paired `SALES_ORDER_CONFIRM` + `INVENTORY_RESERVE`; cancel/fulfill similarly paired | Proven quantity transaction should be reused, not reimplemented |
| IAM email proof → SALES buyer | `EmailIdentityService.findOrProvision`, durable outbox; `verifyEmail` binds `buyer_user_id` | Email verification is not OIDC login, delivery/transport and anti-abuse remain gates |
| ORGANIZATION → stock | `StockEligibility` requires ACTIVE WAREHOUSE and valid tenant PRODUCT before new inventory mutation | Sales must show explicit insufficient stock/unmappable/not stockable errors, not silently choose another location |
| FINANCE → SALES | `fin_invoice` schema exists, no qualified payment posting | No payment completion claim based on `COMPLETED` inventory fulfillment |

## 2. P0 minimum completion contract (no over-engineering)

**SALE-08-A — immutable stock identity:** add nullable `crm.sales_order_line.catalog_item_id_snapshot` with composite FK `(tenant_id,catalog_item_id_snapshot) → crm.cat_item(tenant_id,id)`. Existing historical NULL remains NULL, **never** backfill from current mutable `store_offer` without a verified historical receipt. During new stock-backed checkout resolve required SKU and store it atomically with title/unit price/currency/quantity; reject a stock-backed published offer if SKU cannot be resolved. In `confirm` use **order_line.snapshot** for SKU; `InventoryReservationService.reserve` cross-validates order line snapshot and location inventory, not mutable offer. Treat legacy NULL as typed `UNMAPPED_LEGACY_ORDER` and fail closed. **Do not** add a new `OrderInventorySnapshotEntity` or duplicate price ledger.

**SALE-08-B — unambiguous offer owner:** keep `crm.store_offer` as the sellable offer price source for checkout R1, while `cat_price` remains a catalogue price history. Introduce a minimal tenant-authorized seller offer command and approval semantics, not implicit price mirroring every revision. Published `PRODUCT` needed for stock-backed offer; `SERVICE` is NOT inventory-reservable and must have a separate sales fulfillment path before advertised (otherwise hold SERVICE offers). State transitions of published SKU/offer require explicit policy for existing PLACED orders.

**SALE-08-C — immutable commercial acceptance:** title, unit price, line quantity and currency stay as accepted snapshots. Once PLACED, seller price edit cannot alter existing financial totals; same checkout idempotency key must return original order and not refresh offer data. Protect `store_offer` concurrently with checkout via consistent row lock or version admission before immutable insertion, rather than relying on mutable state at later confirm.

**SALE-08-D — request owner / security:** verified buyer session is not inferred from email identity; checkout public transport rate-limit/abuse gate remains HOLD. Tenant-scoped seller operations retain `VerifiedActor` and paired permissions for physical stock.

**SALE-08-E — return types and failures:** `UNMAPPED_LEGACY_ORDER`, `SKU_NOT_PUBLISHED`, `SERVICE_NOT_STOCKABLE`, `LOCATION_NOT_ELIGIBLE`, `INSUFFICIENT_AVAILABLE`, `OFFER_UNAVAILABLE` map to stable service errors; do not leak foreign tenant counts/IDs. Do not invent successful fulfillment while stock unavailable.

## 3. Minimal proposed implementation sequence

| Stage | Work | Proof |
|---|---|---|
| 08.1 | Additive nullable SKU snapshot migration, populated PG upgrade, legacy NULL interpretation | No historical substitution; old checksum untouched |
| 08.2 | Checkout locks/resolves published tenant offer + SKU, records immutable snapshot | Same idempotency key cannot change item/price/currency |
| 08.3 | Confirm/Reserve consumes snapshot, rejects changed live offer mappings; existing quantity Q9 remains green | Checkout→confirm and changed-offer negative E2E |
| 08.4 | Seller offer commands with tenant permissions and clear catalogue/offer price ownership | CATALOG price revision not retroactive to order |
| 08.5 | Qualified Stockability of PRODUCT vs SERVICE + legacy/archived behavior | No non-stockable orders silently passed to inventory |

Do *not* add new REST controllers during feature freeze, split orders across multiple seller tenants, implement payment/COGS or integrate generic workflow/bus to solve source-identity contract.

## 4. Required PostgreSQL negative and race cases

- **SALE-08-01:** published offer without SKU → stock-backed checkout denied before order/outbox side effect.
- **SALE-08-02:** tenant A offer points to tenant B SKU → composite FK/admission refuses; no cross-tenant inventory reads.
- **SALE-08-03:** PLACED line immutable SKU S1, seller remaps offer to S2 before confirm → reserve still S1 or reject mismatch by explicit policy, **never reserve S2 silently**.
- **SALE-08-04:** seller changes offer price after PLACED → accepted `unit_price_snapshot`, total and currency unchanged.
- **SALE-08-05:** concurrent checkout / pause offer → deterministic published/paused admission, no partially created order.
- **SALE-08-06:** same checkout key with changed offer quantity → idempotency conflict; exact retry same receipt.
- **SALE-08-07:** archived SKU after confirmed reserve → idempotent terminal fulfillment preserved, no new reservation.
- **SALE-08-08:** service SKU offer → no physical reserve without explicit SERVICE lifecycle.
- **SALE-08-09:** active tenant warehouse, mapped product, receipt and seller confirm → Q9 full-line reserve and fulfillment, immutable quantity evidence.
- **SALE-08-10:** insufficient stock in one of several lines → rollback *all* order/reservation changes.
- **SALE-08-11:** old PLACED order with NULL snapshot → explicit fail closed, never backfill current mutable link.
- **SALE-08-12:** user with SELLER permission A but INVENTORY permission B → no dual-grant operation in either tenant.

## 5. Review disposition

**PASS 08/12 = SOURCE REVIEWED / SPEC PROPOSED.** No new SALES implementation is claimed by this review; release qualification stays closed. Continue PASS 09/12 tenant/permission/PII/anonymous boundary and PASS 10/12 concurrency/throughput only after source snapshot contract has an accepted design. Keep single Sales 12-pass spec as authoritative follow-up.


## 6. Реализация SALES-08.1–08.3 — точная фиксация SKU (2026-11-22)

**Статус:** `IMPLEMENTED / POSTGRESQL CI PENDING` до зелёного результата *на актуальном кодовом SHA*. Этот раздел новее исторического исходного source review выше. Не объявлять PASS 08 целиком квалифицированным, пока не завершены все выбранные тесты и отдельный seller offer lifecycle 08.4.

**Контракт зафиксирован:**
- `crm.sales_order_line.catalog_item_id_snapshot UUID NULL`, tenant FK `(tenant_id,catalog_item_id_snapshot)->crm.cat_item(tenant_id,id)`. NULL старых строк остаётся неизвестной историей, никакого автозаполнения.
- `AutoOrderService.checkout` после идемпотентного запроса выбирает **PUBLISHED store offer + PUBLISHED PRODUCT** с блокировкой строк `FOR UPDATE`, одним серверным снимком фиксирует `offer_id,title_snapshot,unit_price_snapshot,currency,quantity,catalog_item_id_snapshot`. Если предложение без маппинга, SERVICE или архивный SKU — отказ **до** `EmailIdentityService.findOrProvision` и создания order/email-outbox.
- `SalesInventoryCommandService.lines` получает `catalog_item_id_snapshot` **из строки заказа**, а не `store_offer`. Новый PLACED с историческим NULL отклоняет `UNMAPPED_LEGACY_ORDER` до смены статуса; pre-existing CONFIRMED retry / fulfillment по зарегистрированным резервам не заставляет backfill SKU.
- `InventoryReservationService.reserve` независимо сверяет `order_line.catalog_item_id_snapshot` с запрошенным SKU, не повторяя JOIN к изменяемому офферу. Все прежние SALES + INVENTORY permissions и одна транзакция сохранены.
- Migration `0076` — только additive nullable поле + tenant FK + обычный индекс, совместимый с H2/PostgreSQL; `0077` — PostgreSQL BEFORE UPDATE/DELETE immutable accepted line (повторное применение или редактирование не изменяет бизнес-снимок). Старые changeset checksum не меняются.
- PRICE ownership: checkout продолжает доверять серверной `store_offer.unit_price`, `cat_price` не зеркалируется автоматически и не перезаписывает историческую сумму.

**Тестовые контракты:** `AutoOrderR1QualificationTest.sale08CheckoutCapturesImmutableSkuAndAcceptedCommercialPrice`, `sale08RejectsUnmappedAndArchivedOffersBeforeEmailOrOrderSideEffects`, `sale08ServiceSkuCannotEnterPhysicalStockCheckout`, `sale08CrossTenantSkuMappingIsRejectedByCompositeForeignKey`, `sale08PostgresConcurrentOfferPauseFencesCheckoutBeforeAnyIdentityCreation`; `SalesInventoryRbacQualificationTest.sale08RemappedOfferCannotSubstituteSkuDuringOrderConfirmation`, `sale08HistoricalNullSkuMustNotResolveFromLiveOffer`, `sale08PostgresRejectsModificationOfAcceptedOrderLine`; существующие SALES/INVENTORY/CRM email lifecycle and migration regressions.

**Предметные решения:** PRODUCT-only stock-backed offer в R1; SERVICE продажи **не** считаются успешно поддержанными до отдельного service fulfillment. Для старых `NULL` — отказ в новом confirm, корректирующий административный workflow остаётся отдельным будущим ТЗ. Remap оффера после checkout не меняет резервируемый SKU. Изменение цены после checkout не меняет принятую сумму/валюту/строку.

**История устранённых дефектов в процессе:** первая версия `0076` использовала PostgreSQL partial index `WHERE ... IS NOT NULL`, из-за чего H2 failed at migration; заменён обычным индексом до release. Старые fixtures, создававшие заказ без каталога или новый заказ после архивации SKU, обновлены, так как новый контракт запрещает эти операции. Итоговый CI рассматривается только на последнем кодовом SHA после всех исправлений.

**Остаётся за рамками этого изменения:** seller-authorized owner CRUD и state machine для `store_offer` (08.4), реальные SERVICE orders, PII/transport/anti-abuse, delivery/invoice/COGS, full 09–12 and production admission. **Production HOLD**.


## 7. Qualification exact SHA — VERIFIED

**Exact qualified code SHA:** [`cb490556e56d74770ee86f839a849a354702673f`](https://github.com/alimbetov/RM/commit/cb490556e56d74770ee86f839a849a354702673f).

| Exact-SHA CI | Result |
|---|---|
| [44989823109](https://github.com/alimbetov/RM/actions/runs/44989823109), H2 + PostgreSQL + populated IAM PII | **SUCCESS** |
| [44989827422](https://github.com/alimbetov/RM/actions/runs/44989827422), second H2 + PostgreSQL + IAM PII | **SUCCESS** |
| [44989823098](https://github.com/alimbetov/RM/actions/runs/44989823098), CRM R1.2 populated PostgreSQL upgrade | **SUCCESS** |
| [44989823157](https://github.com/alimbetov/RM/actions/runs/44989823157), RustFS real infrastructure E2E | **SUCCESS** |

PostgreSQL integration: **326 tests / 0 failures / 0 errors / 18 skipped**. Named active suites executed without skipped: `AutoOrderR1QualificationTest` **15/15**, `SalesInventoryRbacQualificationTest` **19/19**, `InventoryReservationQualificationTest` **6/6**, `SalesOrderQueryQualificationTest` **4/4**, `IamEmailChangeOrderHistoryQualificationTest` **1/1**.

**Fixed regressions:** H2 rejected partial index in first unqualified 0076; changed it to portable plain index. Two previously valid fixtures tried to checkout after SKU archiving or without a catalog mapping; adjusted them to preserve the historical reservation test and genuine email-history test under the new admission rule. Production HOLD and full PASS08/09–12 HOLD remain unchanged. This is **a source+test-qualified snapshot contract**, not a public checkout release.
