# RM-CURRENCY-R1 — Tenant & Market Currency Policy
## Единая валютная политика RM Business и RM QR Commerce

**Дата:** 2026-11-23. **Ветка:** `base_dev`. **Тип:** итоговое техническое задание (service-first).  
**Статус:** SPECIFIED / IMPLEMENTATION NOT STARTED / PRODUCTION HOLD.  
**Очередность:** завершающий контракт для текущего пакета двойной продуктовой основы; выполнять после FC-00 exact-SHA admission и критического FC-40/DEC-FUL-01, либо в рамках FC-30 только после отдельного approval. Это не новый обязательный блокер для уже существующего QR-08/R0 gate.  
**Нормативная иерархия:** `docs/architecture/RM-PLATFORM-STANDARD-R2.md` > доменные решения > `docs/spec/RM-CORE-FUNCTIONAL-CLOSURE-R1.md` > данный currency contract. Изменения правовых правил утверждаются отдельно; код не заменяет юридический аудит.

### 1. Задача и правовая основа

Один tenant (организация) ведёт операции с товарами, услугами и закупками в нескольких валютах; один и тот же публичный канал может быть доступен в разных странах. **Нельзя путать разрешённые валюты tenant с валютой публичной цены.** Для локального B2C-рынка РК публичные цены товаров, работ и услуг указываются в KZT; внутренний учёт и разрешённые операции с контрагентами в иностранной валюте имеют самостоятельные правовые ограничения.

Источники для юридической квалификации на дату ТЗ:
- Закон РК «О защите прав потребителей», ст. 24, обязанность указывать стоимость товара в тенге: https://adilet.kz/ru/laws/doc-52483/st-24/
- Закон РК «О рекламе», требование указывать цены рекламируемых товаров/работ/услуг в тенге: https://adilet.kz/laws/doc-17905/
- Гражданский кодекс РК, ст. 282 — денежные обязательства на территории РК выражаются в тенге, кроме случаев, предусмотренных законом: https://adilet.zan.kz/rus/docs/K940001000_

**Юридическая граница:** конкретные исключения для определённых сделок, нерезидентов, экспортных и иных регулируемых операций требуют заключения legal/compliance. `tenant_allowed_currency` НЕ является разрешением на расчёт или публичную цену в иностранной валюте. Никаких автоматических исключений по признаку B2B, статуса покупателя или наличия валютного счёта.

### 2. Действующая реализация и обнаруженные gaps

- `crm.inv_tenant_setting` связывает tenant с активной `crm.inv_cost_policy_version`, где `functional_currency` относится **только к оценке запасов**. `InventoryTenantSettingsService.approveCostPolicy` записывает immutable версию, проверяя ISO-4217 и блокируя смену при остатках/движениях без квалифицированной миграции.
- `StorefrontOfferService.validatePrice` проверяет синтаксис и существование ISO 4217, но **не** tenant allowlist, географию рынка и правовую валюту публикации.
- `AutoOrderService.checkout`, `checkoutPublishedQr`, `checkoutAcceptedQuote` фиксируют валюту текущего предложения / accepted quote в order + line snapshots. Запрещивают смешанную валюту в одном заказе, но отсутствует единый tenant/market policy gate.
- `SalesPriceProposalService` проверяет равенство валюты offer и proposal; не заменяет market admission.
- `crm.sales_order`, `crm.sales_order_line`, `crm.store_offer`, `crm.sales_packaging_catalog_version` уже содержат валютные поля; ничего не пересчитывать задним числом.
- Само наличие `sales_order_qr_reference` **не** доказывает QR-покупку: обычный checkout тоже создаёт reference. Не вводить отдельный QR order/ledger.

### 3. Предметные определения — разные независимые измерения

| Измерение | Значение | Владелец |
|---|---|---|
| `tenantId` | зарегистрированная организация/рабочее пространство | IAM/ORGANIZATION |
| `accountingCurrency` | функциональная валюта предприятия для финансовой отчётности (R1: KZT для локальной компании РК; юридические исключения отдельно) | общая TENANT POLICY, с учётом Finance later |
| `inventoryValuationCurrency` | валюта версии метода оценки запасов | INVENTORY, `inv_cost_policy_version.functional_currency` |
| `marketCountry` | рынок публикации, НЕ страна регистрации tenant и НЕ язык пользовательского интерфейса | STOREFRONT контекст, `KZ` в первой реализации |
| `publicPriceCurrency` | обязательная валюта публичной цены на конкретном рынке | currency market rules |
| `allowedCurrency` | включена tenant для конкретной операционной цели | tenant allowlist |
| `transactionCurrency` | согласованная валюта конкретной сделки или закупки | соответствующий владелец контракта |
| `snapshotCurrency` | неизменяемая валюта опубликованного revision/accepted quote/order | STOREFRONT/SALES |
| `fxRate` | подтверждённый источник/дата/пара/версия курса, только для расчёта | будущий FX-контракт, НЕ `currency policy` |

**R1 решает authorisation и коммерческий admission, не строит валютные проводки, обменный сервис, курсовые разницы, payment, налоговые ставки, COGS или FIN.**

### 4. Архитектура и владение

Новый **тонкий owner component** `TenantCurrencyPolicyService` внутри существующего модульного монолита (предпочтительно ORGANIZATION/shared domain), без микросервиса, дублей IAM или QR order engine. IAM отвечает за actor/tenant membership и права, POLICY — за валютные правила, STOREFRONT — за публикацию и проверку юридической цены, SALES — за неизменяемое исполнение согласованного snapshot, INVENTORY — за собственную версионированную методику оценки.

**Не вводить** `DomainModule.CURRENCY`, если это требует изменения архитектурного стандарта / глобального role registry. Использовать существующий governance boundary и явную разрешённую permission или оформить отдельное RBAC-решение до API реализации. Ни tenantId из запроса, ни владелец SKU, ни currency из клиента не являются полномочием.

#### 4.1 Минимальная SQL-модель, R1

**`public.tenant_currency_policy`**
- `tenant_id UUID PRIMARY KEY REFERENCES public.iam_workspace(id)`
- `accounting_currency CHAR(3) NOT NULL` — ISO 4217; `KZT` для new KZ tenant по утверждённому default
- `market_country CHAR(2) NOT NULL DEFAULT 'KZ'` — R1 один поддерживаемый публичный рынок (для нескольких рынков далее отдельный `market_id` и scoped rules, не multiple tenant)
- `public_price_currency CHAR(3) NOT NULL DEFAULT 'KZT'`
- `status VARCHAR(16) NOT NULL` — `ACTIVE` / `REVIEW_REQUIRED`
- `policy_version BIGINT NOT NULL`, `row_version BIGINT NOT NULL`, `approved_by UUID`, `approved_at TIMESTAMPTZ`, `created_at`, `updated_at`
- Композитная consistency проверка: `market_country='KZ' AND public_price_currency='KZT'` для R1; поддержка иных рынков только новой утверждённой ревизией правил. Не разрешать свободно изменять `public_price_currency` на USD.

**`public.tenant_allowed_currency`**
- `tenant_id UUID NOT NULL`, `currency_code CHAR(3) NOT NULL`, `purpose VARCHAR(24) NOT NULL`
- `purpose` из `PURCHASE`, `ACCOUNTING`, `INVENTORY_VALUATION`, `SALES_INTERNAL`, `PUBLIC_PRICE` (остальные через утверждённую миграцию)
- `state VARCHAR(12) NOT NULL` из `ENABLED`, `DISABLED`; `row_version BIGINT`; `created_at`, `updated_at`
- PK `(tenant_id,currency_code,purpose)` + tenant FK; INDEX `(tenant_id,purpose,state)`
- Для `market_country='KZ'`: `PUBLIC_PRICE` разрешён только для `KZT`. **Нельзя** разрешать публичную иностранную валюту через прямое изменение строки allowlist. Результирующий admission всегда проверяет market rule и purpose, а не только exists in allowlist.
- **Нельзя смешивать** `PUBLIC_PRICE` с юридическим разрешением расчётов. `PURCHASE` и `SALES_INTERNAL` обозначают только конфигурационные возможности; реальный договор/операция должны пройти самостоятельный compliance gate.

**`public.tenant_currency_policy_event`** (если отсутствует уже пригодный immutable audit owner):
- `(tenant_id,id,revision_no,actor_id,action,old_policy_hash,new_policy_hash,occurred_at)`, tenant scoped, append-only, без PII; события изменений allowlist включены. История действий необходима для доказательного расследования. Не записывать в audit секреты или произвольные клиентские строки.

**Стартовые данные:** для нового tenant с юридическим рынком KZ создать `KZT` в `ACCOUNTING`, `PUBLIC_PRICE`, `INVENTORY_VALUATION`; `PURCHASE KZT` допускается; иностранные валюты не активировать автоматически. Инициализация атомарно при первом доверенном onboarding или лениво под tenant lock, с единственным tenant row и idempotent replay.

### 5. Правила поведения и инварианты

- `C-01`: public KZ listing, product, service, packaging catalogue (если опубликовано публично) и QR card — цена в `KZT`. `USD/EUR/CNY` может существовать во внутренних операциях, но не публикуется.
- `C-02`: запрет на уровне команды `publish`, `resume`, `updatePrice` публичного предложения, `PublishedOfferQrService.issueOrGet`, `PublishedOfferCardQuery` и двух вариантов `AutoOrder`; проверка также на `SalesPriceProposalService.submit/respond` по текущему market price. Fail-closed на legacy foreign-currency offer (не скрывать SQL-архив).
- `C-03`: seller может хранить DRAFT иностранную цену для внутренних сценариев **только** при отдельном `SALES_INTERNAL` допуске; нельзя использовать draft валюту как публичную.
- `C-04`: каждый checkout линейризуется со сменой политики в одной PostgreSQL транзакции: `tenant currency policy row FOR UPDATE` / lock stripe + existing order locks с документированным lock DAG; политика и active offer читаются под блокировкой; no TOCTOU and deadlock.
- `C-05`: несовместимая смена публичной валюты, страны рынка или отключение валюты при PUBLISHED offers, pending proposals, active quotes/checkout, неисполненных заказах требует явного staged `REVIEW_REQUIRED` и запрета новых коммерческих операций; старые заказы не переписываются. Существующий QR token не становится правом обходить currency policy.
- `C-06`: `SALES` order and line money currency/amount/price source immutable. Смена валютной политики не меняет accepted price, `status`, `reservation`, возврат, ledger или preview.
- `C-07`: price in KZT is explicitly seller-approved snapshot. Не подменять цену живым `FX`; если закупка USD и продажа KZT, цена в KZT устанавливается отдельно и фиксируется как price revision.
- `C-08`: запрещена смешанная валюта в одном текущем SALE order. Дополнительная многовалютная закупка или документы — отдельный контракт, не автоматически разрешённые.
- `C-09`: сумма/округление используют `BigDecimal`; `currency CHAR(3)`; отображение KZT пользователю может иметь 2 десятичных знака, но исторический NUMERIC(19,4) не округлять при миграции. Currency numeric precision/scale и downstream snapshots защищать тестами.
- `C-10`: никакого `UPDATE sales_order SET currency=...` при изменении политики; никаких массовых конвертаций и пересчётов старых QR/accepted quote/packaging служебных строк.
- `C-11`: country of **market** is explicit source-of-truth; browser locale, IP, телефон, GPS, страна покупателя и страна регистрации tenant не определяют legal currency без утверждённого market context.
- `C-12`: данные иностранной цены, уже опубликованной до миграции, идут в `NEEDS_REVIEW` ledger/report; публикация/checkout ограничены, исторические записи сохраняются. Никаких silent KZT rewrites или автоматического курсирования.

### 6. Application contracts (service-only)

- `TenantCurrencyPolicyService.initialize(actor, tenant)`: идемпотентная инициализация под IAM/tenant lock.
- `get(actor,tenant)`: текущая version/status/разрешённые purpose (только authorized).
- `allowCurrency(actor,tenant,currency,purpose,expectedVersion,requestKey)`, `disableCurrency(...)`: IAM-gated admin commands + CAS + immutable event; `PUBLIC_PRICE` KZ foreign currency fails irrespective of caller grants.
- `assertAllowedForPurpose(tenant,currency,purpose)`: trusted domain port (без произвольного публичного вызова), tenant scoped.
- `assertPublicPriceAllowed(tenant, marketCountry, currency)`: external authoritative read under transaction/lock context; public-card read fail-closed but no side effects.
- `InventoryTenantSettingsService.approveCostPolicy`: не переписывать версии; `inventoryValuationCurrency` проверяется against approved `INVENTORY_VALUATION`, независимая business rule stock/ledger remains.
- `StorefrontOfferService` / `PublishedOfferQrService` / `PublishedOfferCardQuery` / `PublishedQrCheckoutService` / `AutoOrderService` / `SalesPriceProposalService`: один typed currency policy port; никакого dups per product.

**Authorization:** secure commands require `VerifiedActor` + fresh tenant/membership/grants. Buyers not members of seller tenant: checkout invokes trusted server-side market-policy admission without tenant-admin permission. Cross-tenant actor cannot edit policy or view private allowlist.

### 7. Migration strategy

1. Preflight inventory at exact SHA: `DISTINCT currency` by tenant in `crm.store_offer`, `crm.sales_order`, `crm.sales_order_line`, accepted quotes, pricing revisions, packaging; compare mismatches, NULLs, prices/tenants. Report **never** overwrite.
2. Add *new* Liquibase changesets for policy/allowed/event structures. **Never edit** historical changeset SQL bodies/checksums; support PostgreSQL and H2 independently where necessary. Composite FK; clean schema and populated DB upgrade.
3. Backfill KZ tenant policy KZT with guarded `INSERT ... WHERE NOT EXISTS`; classify existing non-KZT PUBLIC offers as `REVIEW_REQUIRED` (or separate immutable migration exception registry with per-offer state) and prevent new commerce, while preserving immutable historical order and price.
4. Existing `inv_cost_policy_version.functional_currency` may be non-KZT. Do not overwrite; surface conflict for approval and separate qualified inventory valuation migration. No false assertion that accountingCurrency was already implemented.
5. Install server validation in all create/update/publish/QR/quote/checkout paths; avoid a post-hoc table constraint on historical `crm.store_offer.currency`.
6. PostgreSQL concurrency matrix and compatibility: one SHA, clean+populated, compare order snapshot/ledger/checksum before/after, migration rollback plan.
7. Admission only after legal review and exact-SHA evidence. If existing KZ historical offers or policy cannot be conclusively classified, HOLD publication; preserve data.

### 8. Acceptance matrix — mandatory service and PostgreSQL tests

| ID | Scenario | Oracle |
|---|---|---|
| CUR-01 | Onboard KZ tenant, idempotent replay | KZT policy and allowlist one per tenant, no duplicate audit |
| CUR-02 | Tenant A/B different allowed PURCHASE list | A cannot write/read B's policy |
| CUR-03 | Public KZ PRODUCT publish with KZT | allowed, snapshot KZT |
| CUR-04 | Public KZ PRODUCT publish with USD despite PURCHASE-USD | denied; zero public/sales/stock changes |
| CUR-05 | Public service/packaging price USD | denied at public offer/QR route, R0 no material/finance posting |
| CUR-06 | DRAFT internal foreign price | permitted only with explicit scope, not returned on public card |
| CUR-07 | QR-issue/reprint of legacy foreign-price published offer | denied/no public leakage; QR reference not proof |
| CUR-08 | Publish/price update vs policy change both commit orders | serializable outcome; no currency drift or deadlock |
| CUR-09 | QR price acknowledgment before/after policy change | exact snapshot or `PRICE_RECONFIRM_REQUIRED`; no partial order |
| CUR-10 | Seller proposal/counter/accepted quote in foreign public currency | deny or block checkout; no quote consumption |
| CUR-11 | Checkout same idempotency key, currency changed | fail conflict, no duplicate order |
| CUR-12 | Normal Business KZ order and QR order | same SALES order model; public paths KZT |
| CUR-13 | Inventory valuation USD vs accounting KZT | no silent change, explicit versioned policy conflict |
| CUR-14 | Disable currency with active offers/orders | deny/stage review; historical order untouched |
| CUR-15 | Invalid ISO/precision/mixed currencies | fail closed without side effects |
| CUR-16 | Suspend/revoke policy admin vs settings change both orders | no unauthorized committed mutation |
| CUR-17 | Populated PG backfill with historic KZT and USD price data | no data loss, USD legacy flags, checksum untouched |
| CUR-18 | H2 smoke + PG partial indexes/FKs + migration checksums | same contract, no unexpected skips |
| CUR-19 | Two JVMs concurrent tenant policy bootstrap/change | one successful linearization, CAS and audit exact-once |
| CUR-20 | Restart/retry and policy row delete/forged SQL mutation | fail-closed; database constraints and owner-only mutations |
| CUR-21 | KZT sale after USD PURCHASE and optional approved FX reference | fixed KZT sales price; no live rate mutation |
| CUR-22 | Historical `crm.sales_order`, quote, return, reservation and inventory | monetary snapshots and stock ledger unchanged |

Minimum qualification is **22 scenario IDs**, not 22 assumed JUnit tests. Each ID requires class.method, exact SHA, PostgreSQL/H2 environment, executed Surefire XML, errors/failures/skips, zero-side-effects oracle, migration evidence and link to run/artifact. Any missing P0 scenario => `RM-CURRENCY-R1 HOLD`.

### 9. Delivery slices and STOP

- **CUR-R0:** legal semantics, market/tenant owner and explicit per-purpose policy, existing source mapping; publish this ТЗ. **No schema or service implementation under R0.**
- **CUR-R1A:** SQL model + clean/populated migration + KZT initial policy, 2 tenants, immutable audit.
- **CUR-R1B:** service management and IAM/revoke/races; `TenantCurrencyPolicyService`.
- **CUR-R1C:** STOREFRONT CATALOG public admission, QR card, negotiation, packaging price and unified checkout; target domain tests.
- **CUR-R1D:** inventory policy compatibility, comprehensive PG race/retry and legacy scenario classification.
- **CUR-R1E:** exact-SHA admission, legal confirmation, operational readiness and documented exceptions; never mark production PASS from service UAT alone.

**No new controllers, UI, TASKS, FX-provider, external finance payments, second order aggregate, new stock engine, runtime Redis or settlement subsystem. `main` untouched. Production HOLD.**


### 10. Follow-on product contract: Smart FX Pricing (separate scope)

Валютная привязка SKU/offer к USD/CNY, загрузка официального курса НБРК и расчёт новой публичной **KZT** цены вынесены в **[RM-FX-PRICING-R1](RM-FX-PRICING-R1-SMART-FX-SELLING-PRICE.md)**. Валютная настройка CUR-R1 определяет **разрешение/назначение** валют, но не является FX-калькулятором или отдельным owner текущих продажных цен.

Для FX якоря запланирован отдельный purpose `PRICING_ANCHOR` в новой миграции после стабильной приёмки CUR-R1. Не расширять роль `PUBLIC_PRICE` на иностранные валюты и не подменять `PURCHASE` этим назначением. Для рынка KZ публичная цена остаётся `KZT`; `InventoryTenantSettingsService` и уже существующие финансовые snapshots не изменяются автоматически.

**Порядок:** CUR-R1 qualified → FX-R1A ingestion → FX-R1B suggestions → FX-R1C guarded publication → FX-R1D conditional auto → FX-R1E admission. Создание follow-on ТЗ не означает реализацию FX, не снимает текущие HOLD и не затрагивает `main`.
