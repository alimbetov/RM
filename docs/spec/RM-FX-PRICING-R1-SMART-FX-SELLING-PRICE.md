# RM-FX-PRICING-R1 — Smart FX Pricing для RM Business и RM QR Commerce

**Тип:** самостоятельное функциональное ТЗ / service-first contract / architecture decision proposal.  
**Дата:** 2026-11-23. **Репозиторий:** `alimbetov/RM`; **ветка:** `base_dev`.  
**Статус:** **R0M/R1A MOCK PERSISTENCE IMPLEMENTED AND TARGETED PG/H2 QUALIFIED; REMAINING FX CONTRACT NOT IMPLEMENTED / PRODUCTION HOLD**.  
**Последовательность:** **только после квалификации RM-CURRENCY-R1 (CUR-R1A..R1E)** и открытых FC-00/FC-40 admission gates. Новый контракт **не** превращает FX в обязательную зависимость уже существующих QR-08/R0 работ.  
**Scope:** Java 21 / Spring services, PostgreSQL, Liquibase, H2 smoke, JUnit/Testcontainers, официальные курсы НБРК, immutable snapshots и проверяемые бизнес-переходы. **Controllers/UI/TASKS/Redis/ledger/COGS/payment/FIN — STOP** до отдельного решения. `main` не затрагивать.

## 1. Бизнес-идея и конкурентное преимущество

Импортёр закупает SKU за USD/CNY, ведёт бухгалтерский/складской учёт в KZT и хочет сохранять целевую прибыльность при движении курсов. Продавец задаёт **якорную валюту ценообразования** и ценовую стратегию; RM рассчитывает и, по правилам продавца, предлагает или утверждает **новую опубликованную цену строго в KZT**. Для покупателя не показывается иностранная валюта как коммерческая цена и не производится «плавающий расчёт на checkout».

**Ключевая разница:** это сервис **pricing**, а не сервис обмена валют, складской переоценки или платёжных обязательств. Даже если фактическая себестоимость исторически была `52 000 KZT`, USD-якорь SKU может быть `100 USD`: эти суммы описывают разные экономические сущности. Значение anchor не есть бухгалтерская стоимость, закупочная накладная или доказательство валидности маржи.

**R1 продукт:** `MANUAL` (без автоматического пересчёта), `FX_SUGGESTED` (расчёт и явное утверждение), `FX_AUTO` (только после отдельной строгой admission квалификации и tenant opt-in). Рекомендованный launch scope: **R1A/R1B = FX_SUGGESTED**, автоматическая публикация **OFF по умолчанию**.

## 2. Источники истины и связи с текущим RM

- **IAM / ORGANIZATION:** `tenant_id`, полномочия продавца, существующий membership/revocation lock и RBAC, без нового identity domain.
- **RM-CURRENCY-R1:** `tenant_currency_policy`, `tenant_allowed_currency`. Валюта привязки должна быть `ENABLED` в `PURCHASE` или в новом специально согласованном purpose `PRICING_ANCHOR`; **не** использовать `PUBLIC_PRICE` для USD/CNY, не маскировать назначение `PURCHASE` под прайсинг. **Рекомендуемое решение:** расширить валютный контракт последующим changeset purpose `PRICING_ANCHOR`; отдельный CUR-R1B допуск и тесты, не править historical changeset.
- **CATALOG:** владеет идентичностью `crm.cat_item` и действующей историей `crm.cat_price`; anchor — конфигурация цены SKU, не замена SKU/каталожного прайса.
- **STOREFRONT:** `crm.store_offer` — seller-owned публичное предложение; `crm.store_offer_price_revision` — текущая append-only история изменения цены. **Только STOREFRONT** публикует/изменяет фактическую KZT-цену; FX engine не пишет в `store_offer` или `cat_price` обходным SQL.
- **SALES / AutoOrder:** `checkoutPublishedQr`, `checkoutAcceptedQuote`, legacy checkout используют одну модель `crm.sales_order`. Изменение FX не меняет уже принятые цены `sales_order_line`, согласованные `sales_price_proposal` или выданные товары.
- **INVENTORY:** `crm.inv_cost_policy_version.functional_currency` остаётся независимой утверждённой **валютой оценки запасов**. FX никогда не переписывает stock/movement/valuation и не создаёт COGS.
- **QR:** постоянный QR ведёт к тому же опубликованному offer; QR reference не является независимой ценой.

**Важная текущая техническая особенность:** `StorefrontOfferService.updatePrice` меняет цену опубликованного offer и пишет её ревизию в одной транзакции. `AutoOrderService` читает и блокирует authoritative published offer, сверяет acknowledgement. Поэтому встраивать FX надо **через существующий доменный port и optimistic revision**, не создавать вторую таблицу «публичных цен».

## 3. Регуляторные границы Казахстана

- Публичная стоимость товаров/услуг на локальном потребительском рынке Казахстана отображается в KZT; в RM-KZ policy это mandatory. Привязка к USD/CNY служит **только формулой внутреннего ценообразования**.
- Внешний курс **не** является суммой оплаты, валютой сделки или правом выставить иностранную публичную цену. Любые валютные договоры, платежи или исключения требуют отдельной legal/compliance квалификации.
- Продавец отвечает за корректность финальной KZT-цены, акцепта оферты, уведомления и применимых норм защиты потребителей; FX engine не вправе незаметно менять цену уже принятого заказа.
- НБРК: официальные курсы и историческая дата через **https://nationalbank.kz/ru/page/rss**, `https://nationalbank.kz/rss/rates_all.xml` и документированный `get_rates.cfm?fdate=DD.MM.YYYY`. Официальный курс применяется по **дате действия**, а не времени скачивания; для валюты может указываться **номинал** (например, N единиц за указанное число тенге), который обязательно входит в формулу.
- Расписание публикации, праздники/нерабочие дни, revision/corrections и сетевые проблемы должны трактоваться как факты source snapshots, **не как основание синтетически создавать курс**. Правила выбора последней действительной даты оформляются тестами.

Справочные источники (обязательна повторная проверка при implementation/legal gate):
- НБРК RSS / исторический сервис: https://nationalbank.kz/ru/page/rss
- НБРК официальные рыночные курсы: https://nationalbank.kz/ru/exchangerates/ezhednevnye-oficialnye-rynochnye-kursy-valyut/
- Закон РК «О защите прав потребителей», ст. 24: https://adilet.kz/ru/laws/o-zashchite-prav-potrebiteley-04-05-2010-no-274-iv/st-24/

## 4. UX-поведение, определённое на сервисном уровне

1. Продавец выбирает SKU/offer, якорь `USD` или `CNY`, `anchorAmount`, режим `FX_SUGGESTED`, допустимый процент буфера/наценки, округление и защитные пороги. Пользовательская форма — вне R1 scope; реализуется бизнес-сервисный контракт.
2. Система берёт последнюю **действующую и утверждённую** ревизию курса НБРК по currency+validDate, считает candidate в KZT и фиксирует immutable calculation с входными параметрами.
3. `FX_SUGGESTED`: продавец видит proposed old/new KZT, изменение %, курс/дату, объяснение формулы, **принимает** или **отклоняет**. Предложение никогда не публикуется само.
4. `FX_AUTO`: только явный tenant opt-in + per-SKU opt-in; проверяет все guardrails и создаёт **обычную ревизию** KZT offer price. Ошибки/отказы/повторный event не создают дубли.
5. На публичной карточке и в QR показывается **конкретная опубликованная KZT-цена**; ни USD, ни CNY, ни расчётные параметры не предоставляются как публичная стоимость. Продавцу в приватной аналитике можно показывать якорь и explain.
6. На начатом checkout, переговорах и accepted quote цена не меняется неявно: существует консервативная модель согласования с ценой/версией, а заказ всегда фиксирует KZT snapshot.

## 5. Финансовая методика R1 (детерминированная, параметризуемая)

### 5.1 Input contract

`anchorAmount` — **не** историческая закупочная стоимость, а seller-approved ориентир цены в `anchorCurrency` за **единицу** SKU/offer. Переход от фактической себестоимости к anchor является явно одобряемой операцией, не происходит автоматически.

`officialRate` — число KZT за `nominal` единиц anchorCurrency; `nominal > 0`, `officialRate > 0`. `validDate`, `publishedAt`, `fetchedAt`, `source`, `sourcePayloadSha256` фиксируются отдельно.

`bufferPercent` — дополнительный валютный риск-буфер, `markupPercent` — **наценка на базу**, не маржа от выручки. Нельзя называть это «гарантированной чистой маржой»: НДС, комиссия, логистика, доставка, скидки, возвраты и налоги здесь не учтены.

Формула для R1:
```
convertedKzt = anchorAmount × officialRate / nominal
bufferedKzt  = convertedKzt × (1 + bufferPercent/100)
unroundedKzt = bufferedKzt × (1 + markupPercent/100)
candidateKzt = ceil(unroundedKzt / roundingStepKzt) × roundingStepKzt
```
Все шаги на `BigDecimal` (без float/double). `MathContext`, precision, overflow, scales, rounding policies и предельные величины утверждаются как `formulaVersion`. Без округления до конечного шага; `roundingStepKzt` — positive bounded `BigDecimal`. `ceil` **для положительной цены**, не для отрицательных/нулевых якорей: нули/отрицательные цены запрещены, если отдельная legacy policy не разрешает free items.

**Пример, исключительно условный (не текущий курс НБРК):** `100 USD × 500 KZT/USD × 1.00 × 1.25 = 62 500 KZT`. Для `CNY` с номиналом 10: `200 CNY × (700 KZT / 10 CNY) = 14 000 KZT` до буфера/наценки. Никакого курсирования «на лету» при запросе публичной карточки.

### 5.2 Продавец может выбрать источник якоря

- `FIXED_ANCHOR`: ручная фиксированная foreign-currency price basis (R1 core).
- `PROCUREMENT_REFERENCE`: отдельная будущая интеграция с версионированным закупочным документом и проверенной фактической себестоимостью. **R1 не делает автоматического превращения складской себестоимости в USD.**
- `COST_PLUS` с налогами/логистикой и мультивалютными партиями — **after R1**, требуется финансовая методика и независимые эталоны.

### 5.3 Guardrails

`minAllowedPriceKzt`, `maxAllowedPriceKzt`, `maxPercentChangePerRevision`, `maxCumulativeChangePerDay`, `minimumChangeAmountKzt`, `minimumChangePercent`, `rateFreshnessHours`, `priceUpdateCooldown`, `marketStatus=ACTIVE`, отсутствие конфликтующей переговорной активности, `tenantCurrencyPolicy=ACTIVE`, `catalogItem=PUBLISHED`, `offer=PUBLISHED`.

Изменение не проходит ни один guardrail => immutable calculation `BLOCKED` с кодом причины, **опубликованная KZT-цена неизменна**. Значения guardrails и первый auto release — отдельное бизнес-утверждение, а не произвольные defaults. Даже если после `ceil` цена не изменилась: `NO_CHANGE`, никакой price revision.

## 6. Предлагаемая SQL-модель (новые Liquibase changesets только после CUR-R1)

### 6.1 `crm.fx_official_rate_snapshot`

`(id UUID, provider='NBRK', base_currency CHAR(3), quote_currency='KZT', nominal NUMERIC(19,6), rate NUMERIC(24,10), valid_date DATE, provider_published_at TIMESTAMPTZ NULL, fetched_at TIMESTAMPTZ, source_uri, payload_sha256 CHAR(64), source_revision INTEGER, validation_status, created_at)`.

- Уникальность идентичности источника и версии: `(provider,base_currency,quote_currency,valid_date,source_revision)`; идентичные повторные публикации дедуплицировать по hash/semantic key.
- Снимки **append-only**; исправление НБРК создаёт новую ревизию с полным происхождением и audit evidence.
- Не устанавливать курс на future date до его фактической публикации/подтверждённой доступности.
- `source_uri` валидируется against allowlist; no untrusted HTTP redirects, no XML external entities, no SSRF.
- Дата действия / источник / номинал / rate являются обязательными. Перечень поддерживаемых кодов — ISO-4217 из tenant policy, но сами rates global read-only reference data; tenants не редактируют официальные курсы.

### 6.2 `crm.sku_fx_pricing_policy`

`(tenant_id UUID, id UUID, catalog_item_id UUID, store_offer_id UUID NULL, anchor_currency CHAR(3), anchor_amount NUMERIC(19,4), mode VARCHAR, buffer_pct NUMERIC, markup_pct NUMERIC, rounding_step_kzt NUMERIC, min_price_kzt NUMERIC, max_price_kzt NUMERIC, max_revision_change_pct NUMERIC, max_daily_change_pct NUMERIC, min_trigger_change_pct NUMERIC, min_trigger_change_kzt NUMERIC, rate_freshness_hours INTEGER, cooldown_seconds INTEGER, policy_revision BIGINT, row_version BIGINT, approved_by UUID, approved_at, state VARCHAR, created_at, updated_at)`.

- Tenant-composite foreign keys к `crm.cat_item` и `crm.store_offer` **где существует соответствующий композитный ключ**; если ключа нет — добавить адекватную квалифицированную constraint миграцию (не имитировать FK).
- Один активный `tenant+offer` policy; SKU policy может служить default, **offer-level override** определён детерминированно. Не создавать «второго каталога цен» или хранить отдельно актуальную публичную цену.
- Политики ревизионны и auditable. Внешний курс никогда не повышает `policy_revision`; оно меняется только от одобренного действия продавца.
- `FX_AUTO` запрещён, пока нет tenant-level opt-in и отдельного gate. `MANUAL` и `FX_SUGGESTED` доступны после администрирования/qualification.

### 6.3 `crm.fx_price_calculation`

`(tenant_id, id, policy_id, policy_revision, rate_snapshot_id, formula_version, offer_row_version_at_calculation, current_price_kzt, candidate_price_kzt, anchor_amount_snapshot, anchor_currency_snapshot, nominal_snapshot, rate_snapshot, buffer_snapshot, markup_snapshot, rounding_snapshot, input_digest_sha256, state, reason_code, calculated_at, expires_at, approved_by, approved_at, published_offer_row_version, published_price_revision_id)`.

- `state` transition: `CALCULATED -> SUGGESTED -> APPROVED -> PUBLISHED` / `REJECTED` / `EXPIRED` / `STALE` / `BLOCKED` / `NO_CHANGE`. `PUBLISHED` только если существующий offer CAS успешно создал ровно одну обычную ценовую ревизию.
- Calculation snapshot неизменяем после создания; mutable lifecycle переходы и согласования **в отдельной append-only event history**; итоговое состояние — проекция (не перезапись inputs).
- Unique `(tenant_id, input_digest_sha256)` для идемпотентности одинакового расчёта с контекстом; намеренные независимые пересчёты имеют явный `calculation_intent_id`.
- `requestKey` со связанным намерением защищён от повторной публикации; разные payload при одинаковом key = `IDEMPOTENCY_CONFLICT`.
- Tenant-scoped references ко всем entities и links к точной версии `crm.store_offer_price_revision`, без cross-tenant ссылок.

### 6.4 `crm.fx_pricing_event` и scheduler intent

Append-only журнал `(tenant_id, event_id, aggregate_id, action, actor, from_state, to_state, occurred_at, reason, source_sha)`. Для auto не держать сетевые вызовы в бизнес-транзакции: отдельно забрать/проверить курс, сохранить snapshot, сформировать локальный immutable candidate, выполнить publish в короткой транзакции с idempotency/CAS. При crash/retry использовать существующий доказанный outbox/poller pattern, не новый task engine.

## 7. Командные контракты

`FxRateImportService.fetchValidateAndPersistNBRK(date)` — trusted scheduled service, no seller-facing arbitrary URL; segregated trusted credentials, bounded timeouts, retry/backoff, secure parsing. Отсутствующая валюта `CNY` не «угадывается» через USD, если такого cross-rate path нет в утверждённой методике.

`SkuFxPricingPolicyService.configure(actor,tenant,item,offer,request,expectedVersion)` — проверка `VerifiedActor`, active tenant, `STOREFRONT_OFFER_MANAGE` или разрешённого `CATALOG_PRICE_MANAGE` в соответствующем owner context, `PRICING_ANCHOR` tenant allowlist, finite bounds, CAS, audit. User/tenant ID никогда не берутся из недоверенного body как authentication.

`FxPricingCalculationService.calculate(tenant,policy,rateSnapshotId,rateValidDate)` — pure BigDecimal domain math и immutable explanation, no publication, no stock movement, tenant-specific approval input.

`FxPriceApprovalService.approve(actor,tenant,calculationId,expectedOfferVersion,requestKey)` — role check + account/tenant fences, shared lock order, active market currency gate, offer and catalog validation, stale-rate policy, guardrails, quote/pending proposal coexistence policy, optimistic CAS. **Publishes through the existing STOREFRONT price-update path**, creates one price revision and one approval/publish event.

`FxPriceApprovalService.reject(...)` — idempotent and auditable. `FxAutoPublishWorker` — separate gated R1D release with tenant opt-in, hardened lease/fencing/outbox and crash recovery; never bypasses domain actor/permission model by spoofing a human actor. Define separate trusted system-principal permission contract before auto.

`FxPricingExplainQuery` (private, authorized) — inputs, rate provenance, proposed/public value and reason code. Public query is **existing** published KZT price, not exposure of anchor or floating calculated value.

## 8. Concurrency, price agreement and QR negotiation

**Lock order (must be verified against current source before coding):** IAM actor -> tenant/workspace -> tenant currency policy -> store_front -> store_offer -> cat_item -> active proposals/quote/order. Existing code paths currently lock store_front/offer/item; do **not** introduce ad hoc `currency policy FOR UPDATE` after offer lock in one path and before it in another. Resolve with explicit lock DAG and two-order PG tests.

**Checkout:** published offer `KZT` value + revision is authoritative. Buyer acknowledgement includes current price + ref; if FX update committed earlier, stale confirmation fails with `PRICE_RECONFIRM_REQUIRED` (or existing canonical stale-price error) and makes zero order/stock/quote writes. If checkout holds locks first, FX publish waits and changes price only after checkout commits. No refresh during a checkout transaction.

**Negotiation:** accepted quote has immutable negotiated KZT price/quantity/revision and TTL. FX price changes must not silently revise accepted quote or its checkout terms. Proposed R1 conservative rule: while active PENDING negotiation/accepted quote binds the same offer, FX auto-publish blocks (`ACTIVE_NEGOTIATION`), manual suggested price requires explicit seller decision with documented treatment of the existing offer and pending offers. The actual blocking condition must be derived from qualified `SalesPriceProposalService` state machine; no blanket tenant-level lock of all trade.

**Existing order:** never mutate `sales_order.currency`, `sales_order.total_amount`, `sales_order_line.unit_price_snapshot`, `fulfillment_mode`, reservation, stock movement, return or financial status. One seller/one order remains canonical.

**Manual changes:** if human changes offer while FX calculation is pending, approve fails `STALE_PRICE_REVISION` even if price returns numerically to same value. Recompute from a fresh source revision; never silently rebase old suggestion.

## 9. Rate lifecycle, fault handling, observability

- Official rate ingestion is distinct from publish: every rate snapshot records provider payload hash, `fetchedAt`, `validDate`, source nominal and parser version; original provider payload may be retained only with bounded retention and security review.
- Rates on weekends/holidays: use **most recent provider-approved validDate <= policy effective date** within approved freshness horizon, never future or synthetic dates. Valid-date gaps, revised same-day rate, network failures are observable `NO_FRESH_RATE` / `RATE_REVISION_AVAILABLE`.
- **Network outage:** existing published KZT offer price stays; no price update and no checkout availability regression attributable solely to FX source outage.
- **Outlier:** official correction, 10x/100x jump, nominal change, spoofed XML, duplicate data, multi-currency outage -> quarantine candidate, human review. Maximum variation thresholds configured and tested.
- Important telemetry: `rate_age`, `last_successful_import`, `source_revision`, `calculation_count`, `suggestion_approval_latency`, `blocked_change_reason`, `price_change_amount`, `stale_approval_conflicts`, `autopublish_retries`, no buyer PII or unbounded raw payload logs.
- Config/version trace for forensic explanation even after policy reconfiguration; rate and calculation fully reproducible offline from immutable inputs.

## 10. Mandatory qualification matrix

| ID | Проверяемое поведение | Обязательный oracle |
|---|---|---|
| FX-01 | SKU USD anchor; accounting/stock KZT | valuation/movement never changes |
| FX-02 | CNY rate with nominal 10 | correct divide by nominal |
| FX-03 | USD example and controlled KZT rounding | exact BigDecimal mathematical reference |
| FX-04 | disallow USD/CNY if `PRICING_ANCHOR` disabled | fail closed, no calculation/publish |
| FX-05 | tenant A/B same SKU code and policy | no cross-tenant read/write |
| FX-06 | only trusted NBRK source/valid date/sha | SSRF/XXE/redirect denied |
| FX-07 | weekend/no rate/new future valid date | previous valid or block per horizon |
| FX-08 | NBRK published correction | new append-only rate revision; old calculation reproducible |
| FX-09 | duplicate rate imports / crash retry | unique semantic snapshot, no duplicate suggestions |
| FX-10 | FX_SUGGESTED without approval | public offer KZT old value |
| FX-11 | accepted suggestion | one existing price revision + immutable evidence |
| FX-12 | rejected/expired suggestion | public price unchanged |
| FX-13 | manual price update during suggestion | stale approve rejected; no overwrite |
| FX-14 | buyer checkout vs price publish order A | exact accepted KZT order, FX waits |
| FX-15 | price publish vs buyer checkout order B | buyer reconfirmation, no stale order |
| FX-16 | pending/accepted negotiations | no mutated quote/illegal autopublish |
| FX-17 | historical KZT orders under FX change | exact monetary snapshot equality |
| FX-18 | private anchor vs public QR/card | public only KZT; no leak of rate/anchor |
| FX-19 | seller suspend/revoke vs approve both orders | IAM fence and no unauthorized write |
| FX-20 | tenant policy KZ cannot public USD | both DB/application deny, independent of anchor |
| FX-21 | minimum / maximum / per-day change and cooldown | block reason, no offer update |
| FX-22 | 10x outlier and nominal jump | quarantine before approval/auto |
| FX-23 | FX_AUTO default OFF and no delegated principal | no write by unprivileged worker |
| FX-24 | FX_AUTO with gated opt-in, concurrent workers | exactly one published revision |
| FX-25 | worker crashes after publish before ACK | idempotent replay, no duplicate revision |
| FX-26 | rate provider downtime during commerce | checkout uses last committed KZT price |
| FX-27 | clean PostgreSQL and H2 schema qualification | no SQL incompatibility/checksum drift |
| FX-28 | populated PG upgrade with KZT/USD historical data | no mutation of old orders, offers, stock |
| FX-29 | two JVMs approval/price update races | durable CAS/exactly-once audit |
| FX-30 | zero/negative/overflow/fraction rounding boundaries | explicit typed error/no partial writes |
| FX-31 | service / packaging price when affected by anchor | same KZT public rules, no double charge |
| FX-32 | rollback/retry of policy approval and store price revision | all-or-nothing, immutable audit |

**Evidence requirement:** tests source-present **не** равно passed; для каждой строки фиксировать exact SHA, class.method, environment, выполненный Surefire XML, failures/errors/skips, negative side effects, ссылки на Actions, импортированные source fixtures. `FX-01..FX-32` — 32 бизнес-сценария, не обязательно 32 одиночных JUnit. Нельзя заявлять complete business qualification до реальных двухпорядковых PG гонок, populated migration и отдельных FX_AUTO admission.

## 11. Delivery roadmap and dependencies

1. **FX-R0 (сейчас):** это ТЗ, формула, owner split, proposed flags; **только документация**, не migration/implementation.
2. **FX-R1A:** добавить `PRICING_ANCHOR` purpose через новую CUR migration/tenant service, immutable `fx_official_rate_snapshot` и parser с локальными offline NBRK fixtures; запрет SSRF/XXE и защита дат/номинала.
3. **FX-R1B:** `sku_fx_pricing_policy` + deterministic BigDecimal calculator + approval limits + explain + property/metamorphic mathematical tests. **FX_SUGGESTED**.
4. **FX-R1C:** committed suggestions/approvals/rejections through STOREFRONT native price revision; QR/checkout/negotiation two-order PG matrix and immutable snapshots.
5. **FX-R1D (conditional):** **FX_AUTO** только с tenant opt-in, explicit trusted-system identity, worker lease/fencing, restart/retry and no duplicate publish; production still HOLD until full legal/risk gating.
6. **FX-R1E:** clean+populated PostgreSQL migration, exact-SHA successful mandatory suites, IAM/tenant isolation, risk approval, legal review of public pricing and production admission. 
7. **Post-R1:** cost-plus procurement facts, true margin-floor from actual cost, multi-supplier/batches, tax/discount pricing, multiple markets, exchange-rate provider diversification, forecasting. **Not implicit scope**.

**Entry gate:** completed `RM-CURRENCY-R1` and required `FC-00/FC-40` evidence.  
**Exit gate:** fixed KZT publication, reproducible calculation, safe approvals, no sales/stock corruption, complete required PG/CI evidence, explicitly approved FX_AUTO or kept disabled.  
**Do not** create an FX payment engine, independent price catalogue, currency ledger, crypto-market rate feed, controller/UI, second Order, inventory valuation rewriter, or finance subsystem. `main` unchanged; **production HOLD**.


## 12. FX-R0M: offline mock exchange-rate refresh (2026-11-23)

**Scope implemented separately:** `kz.rm.fx.FxRateProvider` interface, synthetic `MockFxRateProvider`, `FxRateRefreshService.simulateRefresh(date)`, deterministic fixtures USD/CNY/EUR on 2026-11-20/21, explicit `MOCK` source and `synthetic=true`, offline unit tests and exact-SHA workflow `.github/workflows/rm-fx-r0-mock-qualification.yml`. Source validation, official rates ingestion, immutable PostgreSQL rates, tenant policies for `PRICING_ANCHOR`, persistent scheduler and public price publication **NOT IMPLEMENTED**.

**Default setting:** `rm.fx.rate.provider=DISABLED`. For controlled `dev` or `test` profiles only, set environment `RM_FX_RATE_PROVIDER=MOCK`; `FxRateRefreshService.simulateRefresh(LocalDate)` can then be called exclusively by trusted application/service test code. Production profile will fail closed even with `MOCK` configured, and mock component is absent from production context. No automatic refresh job, HTTP, database write, customer-facing FX quote, price revision or stock movement is performed.

**Adapter plan** (each needs qualification before enabling):
- `NBRK_RSS`: canonical official KZT rates, XML schema/nominal/valid-date/signature or hash verification, secure XML parser, verified host and retries.
- `NBRK_DATA`: assess published REST dataset contract and currency-series coverage; official provenance does not mean it supports the exact required endpoint.
- `KURS24`: separate external aggregator with rates categories (official NBRK **versus** cash exchange/bank market rates); KURS24 observations are **never silently promoted** to official NBRK. Confirm API stability, terms/attribution, licensing, allowable caching and stale behavior before any use.
- `MOCK`: exclusively offline test data; no market value and not a pricing approval source.
- `DISABLED`: safe default, last published KZT public price remains untouched.

**Selection policy:** never silently fall back from official NBRK to market/bank rate; provider identity, valid date, nominal, observed timestamps and source lineage must survive snapshots. If approved official feed unavailable, keep the existing published KZT price. `FX_AUTO` remains disabled until explicitly accepted in its later independent release gate.

Reference providers: https://nationalbank.kz/ru/news/tehnicheskie-resursy/7469 and https://kurs24.kz/developers ; open data https://data.nationalbank.kz/api-docs . This document is a design record, not a claim about third-party SLA, no-key status or legal entitlement.


## 13. FX-R1A evidence — synthetic rate persistence (2026-11-23)

**Implementation source SHA:** `b1074ef58f2778c62362a850de071d0f0fa66906`. **Qualification:** [RM FX R1A Rate Persistence Qualification #45109891293](https://github.com/alimbetov/RM/actions/runs/45109891293), both `fx-r1a-h2` and `fx-r1a-postgresql` **SUCCESS**; 6 testcases on PG (0 skipped) and 6 H2 (1 expected PG-only skip), zero failures/errors. GitHub artifacts include the exact-SHA Surefire XML. This only qualifies the **mock-persistence slice**.

Implemented append-only Liquibase changesets `0125-fx-r1a-rate-snapshot-and-refresh-history` and PG `0126-fx-r1a-immutable-snapshots`; `crm.fx_official_rate_snapshot` (explicit `synthetic=TRUE`, `provider_code=MOCK`, nominal, valid date, source revision, SHA-256 over canonical versioned rate payload), `crm.fx_rate_import_run` (one immutable row per completed refresh, inserted/replayed counts), `crm.fx_rate_provider_lock` (serializes mock imports), and `FxRateSnapshotService.importMock`.

**Limitations:** Real NBRK/KURS24 adapters, provider source payload retention, source corrections beyond revision 1, parallel importer race on independent JVMs, crash/recovery stress, populated-DB exact-checksum admission, provider authenticity, live schedules, FX-based SKU calculations, public KZT repricing and auto mode **remain NOT IMPLEMENTED/NOT QUALIFIED**. The `fx_official_rate_snapshot` name is a future-capable schema convention; its R1A rows are unambiguously **MOCK synthetic**, never official evidence. Production remains HOLD, `main` unchanged.

**Next step:** after CUR-R1 gates, add the trusted NBRK adapter with safe XML parsing, full provider-sourced metadata and revised-rate append-only semantics. KURS24 remains a distinct optional non-authoritative/qualified adapter; no silent provider fallback.


## 14. FX-R1A.2 — Agile change control following DEC-FUL-01 and CUR-R1 (2026-11-23)

**Scope rule:** currency policy and FX pricing belong to pre-order **pricing/publication admission**, not order execution. `DEC-FUL-01` governs post-confirmation order fulfillment and immutable `fulfillment_mode`; neither FX calculations nor a currency-policy update changes the fulfillment mode, warehouse reservations, handover credentials, `sales_order_line` snapshot or return posting. No schema mutation to DEC-FUL-01 changesets `0121/0122`. Ownership and lock order remain explicit; currency gate is added at offer/checkout admission only after CUR-R1C.

**Agile change delivery / DoR:** issue/story references this ТЗ and CUR-R1, an explicit migration version, affected service owner, business scenario IDs, rollback/fail-closed plan, required exact-SHA PostgreSQL and H2 jobs. DoD requires executed Surefire XML and two-order PostgreSQL races where mutable price and checkout interact. No accidental cross-cutting scope expansion. Already accepted seller orders retain immutable KZT monetary snapshots; FX changes only *future* offered prices.

**FX-R1A.2 first implementation slice:** `NbrkRssXmlParser`, a pure **offline** secure XML/RSS decoder supporting `<rss><channel><item>` with `title` ISO currency, `pubDate` as `dd.MM.yyyy` or `dd.MM.yy`, `description` decimal rate KZT, `quant` nominal. It rejects DOCTYPE/entities, invalid structure, duplicate codes, nonpositive/oversized rates, malformed dates and effective dates beyond the requested date, with bounded XML size/item count. Output is tagged `NBRK_RSS` and is **unverified parsed input** until the trusted HTTPS transport is independently qualified.

**Source documentation verified:** the NBRK technical service lists `https://nationalbank.kz/rss/rates_all.xml` and date-scoped `https://nationalbank.kz/rss/get_rates.cfm?fdate=DD.MM.YYYY`. Use the historical endpoint for reproducible date queries. NBRK's published explanatory answer indicates that daily RSS is available after 17:00; this is **not** a fixed scheduler SLA or licence guarantee and must not be assumed for every day. See https://nationalbank.kz/ru/news/tehnicheskie-resursy/7469 and https://nationalbank.kz/ru/gb/vopros-otvet?page=31 .

**Required before enabling NBRK persistence:** a trusted HTTPS client with fixed host/path and no redirected hosts, TLS validation, bounded response, timeout and retry/backoff; source content SHA-256 and provenance; record raw payload hash and metadata; a **new** Liquibase change to enable `NBRK` while preserving `MOCK synthetic=TRUE`, allowing an immutable corrected-date source revision, and resolving source-version races. Never edit the historical MOCK-only changesets `0125/0126`. Parsing alone is not authenticity; neither the `provider='NBRK'` field nor an XML item makes a quote legally official without qualified source ingestion. Refuse to auto-publish until CUR-R1 admission and legal/risk gates are green.

**Independent next sequence:** secure feed source adapter + offline HTTP boundary tests -> append-only NBRK rate/source provenance migration + populated PG tests -> CUR-R1 tenant `PRICING_ANCHOR` -> deterministic `FX_SUGGESTED` service -> guarded native STOREFRONT price revision -> independent `FX_AUTO` gate. `KURS24` must have a distinct provider code and cannot silently substitute its market quotes for official NBRK.


**FX-R1A.2 targeted CI evidence:** code + workflow exact SHA `00717f7ec201e4510c9ea5e5df10e92327e0c587`; [GitHub Actions #45110337697](https://github.com/alimbetov/RM/actions/runs/45110337697) `nbrk-offline-xml` **SUCCESS**, eight Surefire testcases **executed**, zero failed, errored or skipped. This is a **parser-only** qualification; source authenticity, HTTPS fetch, NBRK persistence and integrated FC-00 exact-SHA admission are NOT thereby green.
