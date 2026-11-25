# RM-COMMERCE-INVENTORY-COMPLETION-R1 — единое ТЗ исполнения

**Дата:** 2026-11-22  
**Ветка:** `base_dev` (одна рабочая ветка)  
**Статус:** SPEC / NOT IMPLEMENTED BY THIS DOCUMENT  
**Production:** HOLD  
**Нормативный стандарт:** `docs/architecture/RM-PLATFORM-STANDARD-R2.md`.  
**Детализация:** `docs/spec/RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md`, `docs/spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md`, `docs/reviews/RM-SALES-AUTOORDER-CROSS-DOMAIN-PASS-08-2026-11-22.md`, `docs/reviews/RM-INVENTORY-Q7-Q9-PG-PERFORMANCE-CLOSURE-2026-11-22.md`. Не заменяет эти документы.

## 1. Цель и критерий законченного сценария

Tenant A создаёт PRODUCT SKU, публикует его, создаёт и публикует собственный storefront offer, принимает товар на ACTIVE WAREHOUSE, покупатель размещает заказ по проверенной серверной цене, уполномоченный продавец подтверждает заказ с атомарным резервом полного количества по каждой строке, затем отменяет/отгружает заказ. После повторного чтения видны неизменяемые условия покупки, остатки, резервы, movement/reservation events и история статусов. Для tenant B ни один идентификатор A не становится доступным. Для недостаточных остатков, невалидного SKU, неактивного склада и отозванных прав не создаются частичные изменения.

R1 — physical goods only. `FULFILLED/COMPLETED` обозначает передачу товара в складском контуре, НЕ оплату или доставку. Обход HTTP/OIDC и публичная эксплуатация не допускаются на стадии service-only Feature Freeze.

## 2. Неприкосновенные границы

- Существующие tenant PK/FK, `VerifiedActor`, `TenantAuthorization.requireForCommand`, `OrderInventoryPort` и совместная SALES+INVENTORY транзакция — KEEP.
- INVENTORY — единый ledger `inv_stock_movement`; резерв управляется только бизнес-командами. `on_hand >= reserved >= 0`, `available=on_hand-reserved`; никакого direct balance set/delete.
- CATALOG владеет SKU и историей `cat_price`; STOREFRONT владеет sellable offer `store_offer` и актуальной checkout-ценой; SALES владеет неизменяемыми order snapshots.
- Нельзя брать SKU для `confirm` из изменяемого `store_offer.catalog_item_id`; обязательно использовать snapshot строки заказа.
- Никаких новых микросервисов, общих CRUD/workflow engines, дополнительных event buses, HTTP-контроллеров, split-reservations, lot/expiry/FEFO, valuation, payments, returns или transport tracking в этой фазе.
- Миграции additive only; applied changesets не изменять, старую историю и PII не переписывать без достоверного источника.

## 3. Факт и состояние

INVENTORY Q1–Q9 Quantity R1 PostgreSQL QUALIFIED на `534aa18008bc54f77a4b1f0277d0f041bfa12bae` (301 PG tests, 0 failures/errors, 18 skipped в указанном CI), это не production approval. R1.2–R1.5 warehouse lifecycle / race / reconciliation требуют проверки именно текущего HEAD; этот документ не утверждает их GREEN. SALES PASS 08.1–08.3 — **IMMUTABLE SKU SNAPSHOT IMPLEMENTED / POSTGRESQL QUALIFIED** на `cb490556e56d74770ee86f839a849a354702673f` (326 PostgreSQL тестов, 0 failures/errors, 18 skipped; профильные SALE/INVENTORY suites без пропусков). PASS 08.4 storefront offer management и PASS 09–12 остаются SPEC / HOLD. Доказательства: `docs/reviews/RM-SALES-AUTOORDER-CROSS-DOMAIN-PASS-08-2026-11-22.md` и четыре точных CI runs 44989823109, 44989827422, 44989823098, 44989823157. При изменении HEAD повторить discovery до реализации.

## 4. Функциональные требования

### INV-C1 — warehouse lifecycle и конкурентность (P0)
- Сервис ORGANIZATION владеет `org_location`. Реализовать или квалифицировать update/deactivate/reactivate (по текущему HEAD).
- Принятая действующая семантика R1.4: переход ACTIVE→INACTIVE — пауза склада; допустим при `on_hand>0`, но запрещён при `reserved>0`, ACTIVE reservation или OPEN stocktake. INACTIVE запрещает новые receive/reserve/transfer. Переход INACTIVE→CLOSED требует дополнительно `on_hand=0`. Любое расширение запрета на паузу с on_hand — отдельное продуктовое решение, не менять существующую квалифицированную семантику без согласования. Реализовать транзакционную проверку внутри общего корректно упорядоченного lock/admission protocol. Если доказана необходимость отдельной проверки активных заказов, добавить с точным SQL и тестом, иначе не сканировать все заказы.
- В каждой новой складской операции обязательны ACTIVE+WAREHOUSE, PRODUCT и разрешённый SKU state. `receive` допускает DRAFT/PUBLISHED PRODUCT, `reserve` — PUBLISHED PRODUCT. Отдельно специфицировать разрешённые compensating operations для архивных SKU / закрываемого склада без потери ledger.
- Race tests: `reserve×deactivate`, `transfer×deactivate`, `receive×deactivate`, `update×update`, revoke×mutation. Commit order определяет безопасный исход: если deactivate committed first — новое движение denied; если mutation committed first — deactivate проверяет свежие остатки/резервы и отказывает при препятствии. Ни deadlock, ни write-after-close.
- Сохранить `FOR SHARE` PostgreSQL owner admission с корректным fencing status changes, `ON CONFLICT DO NOTHING` first position, сортировку lock order и H2 fallback. Повторная квалификация исходных Q1–Q9 обязательна.

### INV-C2 — quantity / idempotency / reconciliation (P0/P1)
- `receive/correct/writeOff/transfer/stocktake/reserve/release/fulfill` доступны только через уже предусмотренные guarded ports.
- requestKey: same key + same normalized payload → exactly same result; same key + different payload → typed conflict; внутренние ключи зарезервированы и не принимаются как внешние.
- Ledger append-only. Reconciliation проверяет отдельно суммы posted physical movements → on_hand и active reservations → reserved для (tenant,warehouse,SKU), выявляет несоответствия без автоматического изменения баланса. Для legacy/migration gaps выводит discrepancy, а не выдумывает проводки.
- Состояния lease/worker и financial valuation в scope не входят. `reservationMode=MANDATORY`; legacy `auto_reserve_on_confirm` и `picking_strategy=FEFO/MANUAL` не рекламировать как исполняемые, `valuationReadiness=POLICY_ONLY`.

### CAT-C1 — catalog SKU lifecycle (P0)
- Существующие draft/edit/i18n, publish/archive, `cat_price` и history — KEEP.
- Новый offer допускает только tenant-owned published PRODUCT SKU; действующие offer publication и checkout должны повторно проверить eligibility. Архивация/изменение SKU не меняет историю уже размещённых заказов.
- Item identity immutable; item description translations и price revision не мутируют `sales_order_line` snapshots.
- Для SERVICE SKU не вызывать reserve. В R1 публикацию sellable SERVICE offer удерживать/отклонять, пока не утверждён самостоятельный service-order fulfillment.

### STORE-C1 — storefront management (P0)
- Реализовать минимальные авторизованные команды `createStorefront/updateStorefront/publishStorefront/pauseStorefront` и `createOffer/updateOffer/publishOffer/pauseOffer/archiveOffer`; расширять лишь по фактически отсутствующим операциям.
- `store_offer.catalog_item_id` для нового stock-backed offer NOT NULL на уровне business admission; одна tenant-owned ссылка на PRODUCT. Не менять её в опубликованном offer после первого использования в заказе: рекомендуется immutable mapping после публикации, исправление — новый offer/version, старые связки остаются для истории.
- `unit_price` — неотрицательная денежная сумма с currency; в R1 только single-currency per order. Явно определить precision/scale по текущей SQL схеме, не конвертировать валюты и не смешивать с `cat_price`.
- Publication требует valid storefront + offer + SKU; paused/archived offer не допускается к новому checkout; старые PLACED/CONFIRMED заказы сохраняют принятые snapshots и продолжают подчиняться выбранной stock/lifecycle policy.
- Edit/paused vs checkout сериализуются через согласованный row lock/version; ни покупка unpublished оффера, ни частично созданный заказ невозможны.
- Queries: tenant-scoped storefront/offer detail, search with stable pagination, public projection only of explicitly published non-PII fields.

### SALE-C1 — immutable acceptance (P0)
- Additive nullable `crm.sales_order_line.catalog_item_id_snapshot` + composite FK `(tenant_id,catalog_item_id_snapshot)` to tenant item, compatible with populated DB. Historical NULL remains NULL, do NOT auto-backfill from live mutable offer.
- During checkout lock/admit published storefront+offer, resolve tenant-owned published PRODUCT SKU, atomically store SKU snapshot together with existing title, unit price, quantity, currency, total, offer_id. If stock-backed offer lacks valid SKU — fail before order/outbox.
- Idempotent checkout key captures normalized immutable selection; exact retry same receipt, modified quantity/price selection with same key typed conflict. Seller changing price after checkout has no retroactive effect.
- `confirm` resolves stock from order-line `catalog_item_id_snapshot`, never current offer SKU. For legacy NULL: `UNMAPPED_LEGACY_ORDER` fail closed and manual remediation, no silent reinterpretation.
- `confirm` reserves full quantity of each line atomically under paired SALES_CONFIRM + INVENTORY_RESERVE permissions; all-or-nothing multiple lines. `cancel` releases once; `fulfill` debits once, cannot exceed reserve. Cross-tenant and mixed-role authorization denied.
- SELLER workflow: existing status machine retained; complete service-only detail/history/search, add staff draft/line-edit/submit only after reviewing existing semantics and without changing already PLACED buyer acceptance. Any staff order must use same immutable acceptance model before confirm.
- Stable service failures: `OFFER_UNAVAILABLE`, `SKU_NOT_PUBLISHED`, `SERVICE_NOT_STOCKABLE`, `UNMAPPED_LEGACY_ORDER`, `LOCATION_NOT_ELIGIBLE`, `INSUFFICIENT_AVAILABLE`, `IDEMPOTENCY_CONFLICT`, `CONCURRENT_MODIFICATION`, `ACCESS_DENIED`. Do not leak foreign tenant IDs/counts.

### IAM-C1 — actor/permissions (P0)
- No raw UUID external trust ports, no implicit email-as-auth. Staff commands use `VerifiedActor`, selected tenant and explicit permissions, including dual SALES+INVENTORY.
- Public checkout remains public-policy/verified-email service-only where existing; real provider delivery, anti-abuse and OIDC admission remain separate release gates.
- PostgreSQL tests: A/B/AB, foreign SKU/offer/location/order IDs, permission revocation while lock held, tenant suspension and no unauthorized PII.

## 5. Milestones / release gates

| Step | Deliverable | Blocker / Proof |
|---|---|---|
| R1.3 | PG concurrency, retries, rollback | reserve/transfer/receive races no corruption |
| R1.4 | ORG WAREHOUSE lifecycle | deactivate fences operations, compensating policy explicit |
| R1.5 | Balance/reservation/ledger reconciliation | deliberate discrepancy detected, no silent repair |
| CAT-01 | Eligibility and publishing contract | SKU lifecycle x active order matrix |
| STORE-01 | Tenant seller storefront/offer commands | CRUD/lifecycle/tenant and concurrent publish |
| SALE-08.1 | Additive immutable SKU column migration | populated PG old NULL untouched |
| SALE-08.2 | Checkout locked offer+SKU snapshot | concurrent pause/price edit; idempotency |
| SALE-08.3 | Confirm/reserve by snapshot | changed live offer cannot substitute SKU |
| SALE-08.4 | Commercial governance, price ownership | independent cat_price and store_offer semantics |
| SALE-08.5 | PRODUCT/SERVICE/legacy negative qualification | unsafe offers fail closed |
| E2E-01 | Full seller workflow | catalog→offer→receipt→checkout→confirm→fulfill, history |
| QUAL-01 | Security/load/migration readiness | exact SHA + named H2/PG/filled-DB/infra runs |

Implementation can execute CAT-01 and INV-C1 in parallel only if SQL lock ordering and cross-domain tests are coordinated. SALES and INVENTORY remain one transaction within the monolith.

## 6. Mandatory PostgreSQL acceptance suite

- E2E-01: tenant A creates published PRODUCT S1, published offer O1, warehouse W1, receives 10; checkout 3 → PLACED; confirm → on_hand=10,reserved=3,available=7; fulfill → on_hand=7,reserved=0,available=7; ledger contains receipt 10 and fulfillment -3; no double event.
- E2E-02: cancel after confirm → reserve=0, on_hand unchanged; repeat cancel no duplicate.
- E2E-03: two buyers competing for one unit → at most one successful full reserve; other typed insufficient stock, no partial lines.
- E2E-04: seller offer price changes after PLACED, accepted price/currency/total unchanged.
- E2E-05: seller remaps offer S1→S2 after checkout (including direct DB corruption fixture if mapping forbidden) → old order never silently reserves S2.
- E2E-06: published offer without SKU, SERVICE offer, archived SKU, foreign-tenant SKU → checkout rejected without order/outbox.
- E2E-07: checkout vs pause / publish / price edit → deterministic serializable outcome; accepted snapshot coherent.
- E2E-08: one line insufficient in multi-line order → confirm rollback ALL reservations/status changes.
- E2E-09: old PLACED NULL SKU snapshot → explicit fail closed; no inferred history.
- E2E-10: warehouse deactivate vs receive/reserve/transfer, concurrent warehouse updates → no write after inactive, rollback clean, invariants pass.
- E2E-11: user with SALES in A and INVENTORY in B → no combined grant; revoked grants/workspace → denied at transaction fence.
- E2E-12: same requestKey replay exact/mismatched payload and concurrent replay → exactly-once/typed conflict.
- E2E-13: reconcile intentionally corrupted synthetic fixture → discrepancy reported; untouched immutable journal.
- E2E-14: populated PostgreSQL Liquibase upgrade preserves old tenant IDs, order lines and ledger; no fabricated snapshots.
- E2E-15: restart/retry after transactional failure; no orphaned outbox/reservations; no phantom fulfillment.
- E2E-16: stable pagination and no PII/foreign tenant exposure via queries.

Tests must assert via independent DB read, not only return DTOs; run PostgreSQL concurrency with separate connections and deterministic barriers/timeouts. Retain existing Q1–Q9 security/quantity tests. Logs include test IDs, source SHA, exact CI run IDs, skipped tests and measurement limits.

## 7. Definition of Done / non-goals

Complete only when every mandatory stage is IMPLEMENTED + TESTED + QUALIFIED (service-only) on the **same exact SHA**, E2E-01..16 pass, historical migration rehearsed on populated PG, no regressions in CRM/IAM/FILES/INVENTORY, and all unresolved decisions are explicit. If a step is still spec-only, mark HOLD; no green-by-document.

Production public HTTP/OIDC, actual mail provider, least-privilege SQL, full security and backup/restore approval are independent gates. Financial valuation/COGS, payments, delivery tracking, splits, returns, service SKUs, marketplace multi-seller, FEFO/lot, multi-warehouse allocation and new technologies are **out of scope**.

## 8. Execution instruction

Do not create a new branch or merge into main. Start with latest `base_dev` source discovery; map each declared operation to actual method/SQL and existing tests. Change only gaps. Each stage produces focused commit, migration if needed, exact-head H2 and PostgreSQL CI, negative tests, and updated evidence. No extra REST controllers while Feature Freeze holds. If an architectural or product decision conflicts with normative R2, stop that particular implementation, record decision and keep fail-closed behavior.

## 9. INV-R1.3/R1.4 implementation evidence (2026-11-22)

По source review на `base_dev` уже существовали `OrganizationLocationService.update/deactivate/reactivate/close`, CAS `row_version`, `InventoryLocationUsagePort.blockers`, guarded `StockEligibility` c PostgreSQL owner `FOR SHARE`, а также `postgresDeactivationFencesConcurrentReceipt`. Повторно создавать lifecycle и переписывать существующие складские сервисы не требуется.

**Новые тесты добавлены (не путать с GREEN):**
- `OrganizationLocationLifecycleQualificationTest.postgresDeactivateBeforeTransferRejectsBothLedgerLegs`
- `OrganizationLocationLifecycleQualificationTest.postgresTransferBeforeDeactivateCommitsAndPauseSeesDrainedStock`
- `OrganizationLocationLifecycleQualificationTest.r14ConcurrentProfileVersionCannotOverwriteCommittedPause`
- `SalesInventoryRbacQualificationTest.postgresDeactivateBeforeConfirmRejectsReservationAndRollsBackOrder`
- `SalesInventoryRbacQualificationTest.postgresConfirmBeforeDeactivateFencesPauseAndLeavesActiveWarehouse`

Последние два случая квалифицируют reserve через реальный SALES confirm и единый `OrderInventoryPort`, не через прямое изменение складских таблиц. Приоритет коммита проверяется PostgreSQL transactions с отдельными соединениями и latch. Ожидаются сохранность заказов, обеих ног transfer, резервов и status; отсутствие частичных коммитов.

**Изменения:** тестовые файлы и этот документ; production Java/SQL не менялись. Коммиты `791e4b8`, `5deb320`. Точные CI workflow runs на итоговом HEAD пока не подтверждены; `INV-R1.3/R1.4 = IMPLEMENTED TEST CASES / QUALIFICATION PENDING`. Также остаются доп. проверки `update×update` с двумя конкурирующими соединениями, `revoke×mutation` и частные negative rollback/retry сценарии. Production HOLD.

## 10. INV-R1.3 / INV-R1.4 exact-head qualification — GREEN (2026-11-22)

**Source SHA:** `f6bf8b214c1db0cdb13bcdb15e018b7a8f0d1f69` (the immediately preceding commit is documentation-only; production Java remains unchanged).  
**Workflow:** [RM Domain Security and PostgreSQL Qualification / 44990149504](https://github.com/alimbetov/RM/actions/runs/44990149504).

| Job | Result | Evidence |
|---|---|---|
| `h2-smoke` | SUCCESS | CI job 137109206163 |
| `postgres-integration` | SUCCESS | CI job 137109205850; Maven **331 tests / 0 failures / 0 errors / 18 skipped**, BUILD SUCCESS; least-privilege role step SUCCESS |
| `postgres-pii-populated` | SUCCESS | CI job 137109206113; migration, populated data and synthetic dump/restore SUCCESS |

**Named PostgreSQL suites, no skips:**
- `OrganizationLocationLifecycleQualificationTest`: **9/9**; includes `postgresDeactivationFencesConcurrentReceipt`, new `postgresDeactivateBeforeTransferRejectsBothLedgerLegs`, `postgresTransferBeforeDeactivateCommitsAndPauseSeesDrainedStock`, CAS stale-version protection.
- `SalesInventoryRbacQualificationTest`: **21/21**; includes new `postgresDeactivateBeforeConfirmRejectsReservationAndRollsBackOrder` and `postgresConfirmBeforeDeactivateFencesPauseAndLeavesActiveWarehouse`.
- `InventoryQ123QualificationTest`: **9/9**; `InventoryTransferStocktakeQualificationTest`: **9/9**; `InventoryReservationQualificationTest`: **6/6**; `InventoryMovementQualificationTest`: **5/5**; `InventoryTenantSettingsQualificationTest`: **5/5**.

**Disposition:** INV-R1.3/R1.4 quantity warehouse-lifecycle and selected rollback/race scenarios **QUALIFIED at service-only scope on the exact SHA above**. This includes commit-order fencing and no partial transfer/reservation, not blanket proof of deadlock-freedom under arbitrary load. In particular, new tests plus existing `OrganizationLocationR11ConcurrencyQualificationTest` (8/8) demonstrate tested concurrency cases, but production p95/p99/load remains HOLD. The source review also found ACTIVE→INACTIVE allowing positive on_hand (pause), but blocking active commitments; INACTIVE→CLOSED requires zero physical stock. No additional production code was needed for these qualified cases.

**R1.5 START PERMITTED**, but not yet implemented or qualified. It must compare balance against posted immutable physical movements and active reservation facts; report discrepancies without auto-correcting financial/quantity history. Its own PostgreSQL exact-head qualification is mandatory. Production, public OIDC, FIN valuation and multi-warehouse split remain HOLD.
