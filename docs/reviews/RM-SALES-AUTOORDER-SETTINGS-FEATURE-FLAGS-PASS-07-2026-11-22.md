# RM Platform — SALES / AutoOrder: PASS 07/12 — Settings & Feature Flags Functional Truth

**Source SHA:** `7d8189bff795956a56b0f14608b82c2e9bd0d113` (`base_dev`, 2026-11-22). **Status:** SOURCE VERIFIED / SETTINGS TZ SPECIFIED, NOT IMPLEMENTED, NOT NEWLY QUALIFIED. **Scope:** SALES checkout/confirm/cancel/fulfill, STOREFRONT state/offer, INVENTORY settings/picking/reserve/cost policy, CATALOG price publication dependencies. Feature Freeze; no HTTP controllers, no new microservices, no main merge; production HOLD.

## 1. Метод проверки: setting → writer → storage → consumer → observable effect

Не считать operational feature реальным по enum, DTO, SQL-колонке или сохранению значения: нужен доказанный потребитель и changed-value E2E. Проверены `StorefrontSettings`, `AutoOrderService`, `SalesInventoryCommandService`, `AuthenticatedInventorySettings`, `InventoryTenantSettingsService`, `CatalogItemService`, `StockEligibility` и changesets rm:0014, rm:0029/0030. Тесты `InventoryTenantSettingsQualificationTest`, `InventoryQ123QualificationTest`, `SalesInventoryRbacQualificationTest` сверены по исходникам. Статические исходники не доказывают, что внешний UI действительно показывает переключатель: рекомендации ниже относятся к будущему продукту и публичной проекции, не к уже обнаруженному UI-дефекту.

| ID | Setting / storage | Writer and reader | Runtime consumer → observable effect | Classification / action |
|---|---|---|---|---|
| SET-01 | `crm.store_front.state` CHECK DRAFT/PUBLISHED/PAUSED | В проверенном Java source нет authenticated storefront state writer; тестовые fixtures вставляют напрямую. `AutoOrderService.checkout` SELECT by `slug=? AND state='PUBLISHED'` | **YES**: PUBLISHED допускает новый checkout; DRAFT/PAUSED не допускают. Receipt retry тоже блокируется, т.к. gate до receipt (PASS 06). | **EFFECTIVE DB state / WRITE WORKFLOW MISSING.** Минимальный `publish/pause` owner command позже с CAS, tenant auth и race |
| SET-02 | `crm.store_offer.state` CHECK PUBLISHED/HIDDEN | В проверенном Java source нет seller-owned offer lifecycle writer; данные создают fixtures. Checkout SELECT `state='PUBLISHED'` | **YES**: только PUBLISHED offer принимается в новую корзину. Смена состояния после checkout не переписывает frozen price/title | **EFFECTIVE DB state / MANAGER MISSING.** Scoped publish/hide + catalog admission; не считать CATALOG.publish созданием оффера |
| SET-03 | `StorefrontSettings.acceptOrders` Java boolean | Только аргумент record / `canCheckout()` возвращает `state==PUBLISHED && acceptOrders`; в `crm.store_front` такой колонки **нет** | **NO**: `AutoOrderService.checkout` не вызывает `canCheckout`, смотрит только persisted state. | **INERT AS CHECKOUT CONFIG**. Не рекламировать второй toggle; R1 store PAUSED/PUBLISHED достаточно. Если нужен отдельный ручной acceptance-switch — отдельное решение с persisted column, writer и E2E, не сейчас |
| SET-04 | `StorefrontSettings.defaultLocale` | Record validates `SupportedLocale.fromCode`; в `crm.store_front` нет `default_locale` | **NO** in verified checkout/offer rendering path; prices and names read from `store_offer` | **DESCRIPTIVE/UNWIRED**. Не обещать tenant-localized storefront. Локаль для future presentation решать при каталожной projection |
| SET-05 | `StorefrontSettings.emailRequired()` | always `true`; checkout invokes IAM `normalize(email)` | **YES as unconditional product rule**; нет переключателя | **MANDATORY INVARIANT**, not setting. Do not make configurable without separate identity/security TZ |
| SET-06 | `StorefrontSettings.autoProvisionBuyer()` | always `true`; checkout always invokes `EmailIdentityService.findOrProvision` | **YES as unconditional product rule**; no configurable branch | **MANDATORY INVARIANT**, not setting |
| SET-07 | `crm.inv_tenant_setting.auto_reserve_on_confirm` BOOLEAN default FALSE | `InventoryTenantSettingsService.initialize` writes FALSE; deprecated `updatePicking(strategy,autoReserve,version)` can alter stored value; `AuthenticatedInventorySettings` hides flag and returns fixed enum `MANDATORY_ON_CONFIRM` | **NO effect of stored value on SALES confirm**: `SalesInventoryCommandService.confirm` always calls `OrderInventoryPort.reserve` for every line. Test `q123LegacyAutoReserveFalseCannotDisableRequiredSalesConfirmationReserve` proves FALSE still reserves | **LEGACY INERT FLAG**. R1 keep column for populated compatibility, remove from operational command surface; deprecate/restrict writer in a separate qualified change; no alternate reserve-on/off workflow |
| SET-08 | `crm.inv_tenant_setting.picking_strategy` enum FIFO/FEFO/MANUAL default FIFO | initialize and deprecated `updatePicking` store value; `AuthenticatedInventorySettings` projects `PickingReadiness.NOT_IMPLEMENTED` | **NO** picking strategy consumer in SALES reserve/fulfill, nor lot/batch-selection engine in checked R1 path. | **LEGACY INERT FOR PICKING**. Retain for migrations; do not render selectable FIFO/FEFO/MANUAL; label picking unavailable until there is a real batch/expiry model + E2E |
| SET-09 | `crm.inv_tenant_setting.allow_negative_stock` BOOLEAN DEFAULT FALSE + `ck_inv_setting_no_negative CHECK(allow_negative_stock=FALSE)` | initialize always FALSE; no operational setter in verified service | **YES always-off safety**: `crm.inv_stock_balance` additionally checks on_hand>=0 and reserved<=on_hand; Java stock operations fail insufficient. | **FIXED SECURITY/QUANTITY INVARIANT**, not feature flag. Do not expose editable toggle |
| SET-10 | `active_policy_version_id`, `inv_cost_policy_version.valuation_method/functional_currency/revision_no` | `AuthenticatedInventorySettings.approveCostPolicy` → `InventoryTenantSettingsService.approveCostPolicy`: CAS, permissions, requires zero non-valued stock and no movements; inserts immutable revision; reads `POLICY_ONLY` | **REAL configuration/audit effect**, **NO proven costing/COGS valuation engine** behind it. `FIFO/MOVING_WEIGHTED_AVERAGE/SPECIFIC_IDENTIFICATION` are policy metadata, not lot costs. | **POLICY-ONLY**, expose separately with truthful readiness; do not show as computed gross margin, cost of sales or implemented FIFO valuation |
| SET-11 | `cat_item.state` DRAFT/PUBLISHED/ARCHIVED and `cat_price.valid_from` | `CatalogItemService.publish/archive/addPriceRevision` are real transactional commands with tenant grants and immutable revision | **PARTIAL**: `StockEligibility.requireReservable` requires PUBLISHED PRODUCT for **new stock reserves**; `AutoOrderService.checkout` reads independently maintained `store_offer` price/title and does NOT project current catalog price/state. | **CATALOG state effective for reserve, not auto-publishing/updating offer**. Add qualified CATALOG→STOREFRONT projection only as explicit seller action; never claim future checkout price follows cat_price automatically |
| SET-12 | SALES order status / `OrderState` enum | `checkout` PLACED; `confirm/cancel/fulfill` writes CONFIRMED/CANCELLED/COMPLETED | **YES for implemented commands**, but PREPARING/REJECTED/READY_FOR_PICKUP/OUT_FOR_DELIVERY lack full command lifecycle | **STATE MACHINE, NOT FEATURE FLAGS.** Do not add toggles to unlock unimplemented transitions; use typed commands from PASS 04 |
| SET-13 | `StorefrontSettings.canCheckout()` | In-record derived pure method from state+acceptOrders | **Only pure computed result**, no demonstrated checkout callsite. | **NOT effective checkout admission**; avoid two competing sources of truth |

**Missing SALES-specific settings:** no `SalesSettingsService`, no `sales_tenant_setting`, no payment/delivery/refund toggles in verified source. Absence is sensible for R1; do not create a settings table just to add flags. Tenant RBAC permissions are authorizations, not feature toggles.

## 2. High-priority discrepancies and correction of earlier audits

| Gap | Severity | Verified source-level fact | Smallest R1 remedy |
|---|---|---|---|
| SA7-01 | P1 truthfulness | `auto_reserve_on_confirm=FALSE` while SALES always reserves. Public operational facade **already correctly reports MANDATORY_ON_CONFIRM**; internal legacy writer remains | Preserve public fixed-mode view; isolate deprecated mutator, test no caller through trusted operational facade can disable reservation |
| SA7-02 | P1 truthfulness | `picking_strategy` editable by deprecated package-private method but no picker; public facade **already reports NOT_IMPLEMENTED** | Keep DB for legacy, remove/deny operational updates when safe; don't add batch/lot/FEFO just to activate a setting |
| SA7-03 | P1 dead config | `StorefrontSettings.acceptOrders` has no persisted source, and checkout doesn't consult it | Remove from UI/contract as independent operational toggle for R1; rely on PUBLISHED/PAUSED; do not persist a redundant flag |
| SA7-04 | P1 source-of-truth | `StorefrontSettings.defaultLocale` is validated but unpersisted; CATALOG price revisions don't update STOREFRONT | Explicit future publication projection, effective price snapshot and locale handling; do not claim locale-driven checkout until E2E |
| SA7-05 | P1 storefront operations | `store_front.state` and `store_offer.state` are effective at checkout, but verified Java has no authenticated tenant-owned publish/pause/hide/publish writer | Narrow VerifiedActor+tenant+version checked commands with one state event and race test, no generic flag service |
| SA7-06 | P1 valuation truth | Cost policy immutable approval exists, but readiness `POLICY_ONLY`; no stock valuation calculations confirmed | Keep separate `PolicyApproved` and `ValuationEngineReady` semantics. Prevent producing financial values from merely selecting FIFO |
| SA7-07 | P0 operational | Checkout uses storefront PUBLISHED without explicit IAM active-workspace admission and policy race; still no offer row lock | R1 storefront lifecycle must serialize publish/pause with checkout without freezing existing receipt replay; preserve PASS 06 proposed policy |
| SA7-08 | P2 immutable rule | `allow_negative_stock` is DB CHECK false | No dynamic switch, no extra config or runtime flag parser |

**Important corrections to general audit:** it is inaccurate now to say a public inventory settings facade promises optional reservation or working FEFO — its fixed `ReservationMode.MANDATORY_ON_CONFIRM` and `PickingReadiness.NOT_IMPLEMENTED` already prevent that. Remaining problem is legacy internal DB/writer surface and missing storefront lifecycle. Also do not assert a real storefront settings UI exists: it has not been verified.

## 3. Minimal production-facing settings contract (proposal, not code)

```java
record SalesOperationalCapabilities(
    boolean mandatoryEmail,
    boolean autoProvisionPendingBuyer,
    boolean reserveAllOnConfirm,
    boolean allowNegativeStock,
    boolean pickingImplemented,
    boolean paymentImplemented,
    boolean deliveryImplemented
) {}
```

**Recommendation:** do not implement this new record by default. The facts should stay in their existing owner facades: `AuthenticatedInventorySettings` reports mandatory reserve and readiness; STOREFRONT read (when implemented) reports `state` and derived `acceptingNewOrders = (state==PUBLISHED && active tenant)`; CATALOG displays actual catalog status separately; SALES commands enforce their own permissions. Avoid a cross-domain duplicate configuration cache.

**Storefront management slice:** `publish(VerifiedActor actor,tenant,expectedVersion)`, `pause(...)`, `publishOffer(...)`, `hideOffer(...)` only if state belongs to verified tenant; use `row_version` on `store_front` (already exists). `store_offer` has no rowVersion in checked DDL: decide whether lightweight `row_version` additive column is justified by mutation races, not an automatic second configuration system. Pausing blocks new orders, not fulfill/cancel of accepted orders.

**Stock:** keep real invariant `reserveAllOnConfirm`; catalog archive/warehouse deactivation blocks **new** reserve, not historical fulfillment. Strict quantity on hand>=reserved remains fixed.

**Valuation:** `approveCostPolicy` is real metadata workflow, but no COGS. Later cost engine must use immutable revision and independent financial qualification. Never conflate choosing FIFO valuation with operating FEFO picking.

## 4. Test plan — set → observe effect, with negative tenant and concurrency

| ID | Acceptance criterion; NEW unless named existing |
|---|---|
| SALE-SET07-01 | PUBLISHED storefront+offer accepts checkout; PAUSED/DRAFT/HIDDEN blocks **new** checkout, no identity/order/outbox side effects |
| SALE-SET07-02 | Changing in-memory `StorefrontSettings.acceptOrders` without persistent state cannot change checkout; eliminate unsupported public toggle |
| SALE-SET07-03 | Storefront pause versus checkout races serializable; accepted order preserved, new orders denied; same-key receipt retry follows PASS 06 approved rule |
| SALE-SET07-04 | `auto_reserve_on_confirm` TRUE/FALSE does **not** disable mandatory full reserve; public facade always shows MANDATORY_ON_CONFIRM |
| SALE-SET07-05 | No public/future operational call can set optional reservation; deprecated writer isolated or removed only after populated compatibility |
| SALE-SET07-06 | Setting FIFO/FEFO/MANUAL changes no stock picking result; read facade says NOT_IMPLEMENTED; do not show choice as effective |
| SALE-SET07-07 | Negative stock forbidden by DB+Java for insufficient stock even if someone attempts legacy flag TRUE; no stock below zero |
| SALE-SET07-08 | Cost policy approval writes immutable revision/CAS, denies existing unvalued stock, returns POLICY_ONLY and no cost/COGS values |
| SALE-SET07-09 | CATALOG published SKU eligible for new reserve; archived/service item denied, existing reservation replay/fulfill remains valid |
| SALE-SET07-10 | CATALOG price revision cannot silently change old order prices, nor automatically change storefront offer; only approved offer publication changes future checkout price |
| SALE-SET07-11 | Pending email and auto-provision are mandatory; no bypass to anonymous order without email, no client-supplied buyer ID |
| SALE-SET07-12 | Tenant A/B/AB asymmetric STORE/INVENTORY settings grants, revoke during publish/pause/approve; no cross-tenant changes |
| SALE-SET07-13 | Readiness/feature representation checked by contract/serialization: no `allowNegativeStock=true`, `optionalReserve`, implemented FEFO/COGS until actual consumer |
| SALE-SET07-14 | Exact source SHA + PostgreSQL populated migration (if any), H2 smoke, explicit PG tests and observed SQL; no inferred GREEN from docs |

**Existing relevant source tests:** `SalesInventoryRbacQualificationTest.q123LegacyAutoReserveFalseCannotDisableRequiredSalesConfirmationReserve`; `InventoryQ123QualificationTest.firstReceiptCreatesExactlyOnePositionAndOldReceiptReplaysAfterArchive`; `InventoryTenantSettingsQualificationTest.oneTenantSettingWithDefaultSafeSwitchesAndApprovedCostPolicy`, `newApprovalCreatesImmutableRevisionInsteadOfRewritingHistory`, `viewerAndOutsiderCannotChangeOrReadPrivateSettings`, `rejectsLifoUnknownCurrencyAndUnvaluedExistingStock`, `postgresBlocksMutationOfApprovedPolicy`; `InventoryReservationQualificationTest.q123ReservationRejectsInactiveWarehouseServiceAndArchiveButHonorsOldCommit`. They do not constitute the new storefront state-management or catalog-price projection E2E tests.

## 5. Proposed smallest implementation slices and DoD

| Slice | Implementation scope | Gate |
|---|---|---|
| **SALE-R1.7A** | Operational contract truth: preserve mandatory reserve/readiness; deny unwanted mutation of legacy toggles in public facade; keep old columns for populated compatibility | SET07-04..08, SET07-13 |
| **SALE-R1.7B** | Minimal tenant-owned storefront state transition commands `publish/pause`, offer `publish/hide`; permission admission + concurrency + decision whether row_version on offer | SET07-01/03/12 |
| **SALE-R1.7C** | Qualified explicit catalog→offer price/SKU projection + future checkout immutable snapshot (depends PASS 03/06) | SET07-09/10 |
| **SALE-R1.7D** | Negative PostgreSQL test matrix + integration review, no extra flags or second config service | SET07-01..14 |

**Product decisions pending before coding:** whether storefront PUBLISHED itself suffices vs independent acceptOrders (R1 recommendation YES); immutable mandatory reserve (YES); picker strategies not operational (YES); policy-only valuation truth (YES); no payment/delivery toggles (YES). Storefront pause/checkout exact race and retry rules from PASS 06 must be explicitly approved.

**Verdict:** **PASS 07/12 SOURCE REVIEWED / SPEC PROPOSED**, JAVA/DDL not changed; SALES/AutoOrder maturity **2/5**, production HOLD. Next **PASS 08/12:** cross-domain contract matrix and ownership CATALOG/STOREFRONT/SALES/INVENTORY/ORG/IAM, snapshot and foreign-key tests.
