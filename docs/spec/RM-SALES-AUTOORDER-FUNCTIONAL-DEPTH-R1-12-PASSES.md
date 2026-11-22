# RM — SALES / AutoOrder Functional Depth R1: 12 проходов и ТЗ функциональной зрелости

**ID:** RM-SALES-AUTO-FUNC-R1. **Сервис:** SALES / AutoOrder (единый заказ продавца, не отдельные микросервисы). **Ветка:** `base_dev`. **Source-review baseline:** `169e4e2a0d641463984837ad76cea8aa70d9f265` (2026-11-22); перед реализацией обязательно повторно сверить HEAD. **Статус:** PASS 01/12 SOURCE REVIEWED; PASS 02/12 SOURCE TRACED; PASS 03/12 DDL SOURCE VERIFIED / MIGRATION SPEC PROPOSED, **NOT IMPLEMENTED / NOT QUALIFIED**. **Условия:** Feature Freeze, без REST-контроллеров, новых веток, merge `main`, production admission.

## 1. Бизнес-цель и конкретный результат

Продавец управляет заказом от поступления до выполнения; покупатель оформляет заказ через опубликованную витрину с обязательным email и получает доступ к истории **после доказательства email и отдельного OIDC-входа**. Один `sales_order` принадлежит ровно одному tenant-продавцу; сумма/валюта/название/цена строки фиксируются на момент checkout; заказ подтверждается только при законной доступности склада и товаров; отмена освобождает резерв, завершение списывает остаток один раз; клиент другого tenant ничего не видит. Назначенный сотрудник продавца может читать состав заказа и историю для исполнения.

**Не объявлять действующим:** online payment, доставки/трек-номера, invoice/accounting, CRM Customer auto-link, автоматическая рассылка email (outbox != delivered), предложения цены/торг, полноценная витрина с управлением офферами, много-складной split, складская стоимостная оценка.

## 2. Проверенная цепочка: от частного к общему

| Действие | Реальная точка входа → эффект | Есть сегодня? | Что отсутствует/ограничено |
|---|---|---|---|
| Публичный checkout | `AutoOrderService.checkout(slug,email,key,lines)` → PUBLISHED `store_front`, PUBLISHED `store_offer`, `sales_order`, `sales_order_line`, `sales_checkout_receipt`, `sales_order_status_event`, IAM email identity/outbox | Реализован, покрыт тестами | Нет transport anti-abuse, storefront owner CRUD/active policy, складской preflight, catalog projection, delivery choice |
| Email proof → owner | `AutoOrderService.verifyEmail(challenge,token)` → `EmailIdentityService.verifyEmail`, `sales_order.buyer_user_id` | Реализован, тестируется | Это email proof, **не OIDC**, нет реального квалифицированного email delivery |
| Покупатель просматривает заказы | `AuthenticatedBuyerOrders.mine(VerifiedActor)` → internal `buyerOrders(userId)` | Реализован | Только `id/status`, без pagination, line details, истории/срока/канала исполнения |
| Сотрудник видит список | `AuthenticatedSalesQueries.search(VerifiedActor,tenant,filter,page,size)` → `SalesOrderQueryService` → SQL tenant WHERE и count | Реализован | Нет `get(order)`, строк, event timeline; только status/date filters и summary |
| Сотрудник подтверждает | `AuthenticatedSalesCommands.confirm` → SALES_CONFIRM + INVENTORY_RESERVE, order row lock, reserve каждой line, status event | Реализован | SKU берётся **из текущего** `store_offer.catalog_item_id`; нет immutable SKU snapshot; нет location ACTIVE WAREHOUSE policy, настройки auto-reserve не потребляются |
| Сотрудник отменяет | `cancel` → SALES_CANCEL + INVENTORY_RELEASE, release ACTIVE, status event | Реализован | Запрещён после fulfill; нет `reject` с причиной, клиентского cancellation contract, reason actor в event |
| Сотрудник исполняет | `fulfill` → SALES_FULFILL + INVENTORY_FULFILL, 1 stock movement/reservation, order `COMPLETED` | Реализован | `COMPLETED` означает физическое списание, **не доказанную доставку/оплату**; нет промежуточного picking/delivery lifecycle |
| Продавец редактирует строки | Нет dedicated seller draft/update/add/remove | **Нет** | Не смешивать изменения уже размещённого покупателем заказа с immutable checkout snapshot |
| Продавец публикует оффер | `CatalogItemService.publish` и отдельный `store_offer` без живого writer/публикации из каталога | **Нет связанного процесса** | Источник истины цены и наличия, pause/unpublish, CAS и междоменный event |

Ни один метод `confirm/cancel/fulfill` не является пустым. Проблема — **незамкнутый бизнес-процесс**, несовпадение владельцев данных и недостаточные read operations, а не необходимость переписать всё.

## 3. Сквозной перекрёстный анализ зависимостей

| Владелец данных | Поставщик/контракт | Потребитель | GAP и граница |
|---|---|---|---|
| IAM | `VerifiedActor`, tenant grant/command decision, encrypted email proof | seller commands, buyer history, checkout | Не принимать actorId от клиента; email proof != OIDC; реальная доставка/anti-abuse отдельно |
| CATALOG | `cat_item` PUBLISHED + versioned `cat_price` + locale | STOREFRONT `store_offer` | Сейчас `CatalogItemService.publish` не создаёт/обновляет оффер; предлагается явная tenant-scoped projection, без второго прайс-движка |
| STOREFRONT | `store_front` PUBLISHED + `store_offer` PUBLISHED/HIDDEN | AutoOrder checkout | Нет штатного authenticated management, pause/offer workflow, optimistic version; не считать DDL управляемым продуктом |
| AUTOORDER | Receipt + `sales_order` immutable line price/title, email identity | SALES confirmations, buyer history | Нужен immutable `catalog_item_id_snapshot` на строке; текущие order lines привязаны к mutable offer |
| SALES | `sales_order` status, rowVersion; `sales_order_status_event` | INVENTORY/CRM/buyer read | Нет seller order detail/history и actor/reason; `CONFIRMED` requires FULL reservation |
| INVENTORY | balance, reservations, movement ledger | `SalesInventoryCommandService.confirm/fulfill/cancel` | Reservation использует JOIN к `store_offer`; R1 требует 1 location/1 line reservation, no negative stock |
| ORGANIZATION / GEO | `org_location` ACTIVE WAREHOUSE + KATO | SALES confirm and inventory stock ops | Composite tenant FK не проверяет status/type; квалифицировать shared location guard+locks с ORG-R1 |
| CRM | `Customer`, Contact + PII permission | optional link to SELLER order | **Не создавать** CRM customer при checkout; future explicit tenant-scoped link verified by CRM owner |
| FINANCE | Invoice/Payment future contracts | SALES | `COMPLETED` не равно `PAID`; no invoice generation until finance service actually exists |
| FILES | typed-owner attachment policy | SALES document attachments later | Не допускать SALES_ORDER owner без отдельного типизированного owner policy и негативного binary-stream теста |

## 4. Двенадцатипроходная карта (реальный статус каждого шага)

| Проход | Фокус | Конкретная приёмка | Статус |
|---:|---|---|---|
| **01** | User journey + cross-domain source review | Разделы 1–4, gaps ниже, связанные реальные файлы/тесты | **SOURCE REVIEWED; SPEC PROPOSED** |
| **02** | Каждый метод, DTO и пустые бизнес-ветви | [Подробный method trace pass 02](../reviews/RM-SALES-AUTOORDER-METHOD-TRACE-PASS-02-2026-11-22.md): method → SQL → actor → state → consumer → тест | **SOURCE TRACED / SPEC REFINED** |
| **03** | Aggregate/schema/DDL | [Schema and populated-safe migration evidence](../reviews/RM-SALES-AUTOORDER-POSTGRESQL-SCHEMA-PASS-03-2026-11-22.md): six actual tables, PK/FK/CHECK/index, legacy provenance | **DDL SOURCE VERIFIED / SPEC PROPOSED** |
| 04 | Команды и state machine | confirm/cancel/fulfill + approved reject & status progression; legal transitions | OPEN |
| 05 | Read model и история | seller detail/lines/events, buyer own detail, literal filters and stable paging | OPEN |
| 06 | Idempotency/конкурентность | checkout same-key, cross-seller keys, revoke vs confirm, cancel vs fulfill, no double stock | OPEN |
| 07 | Реальность настроек | settings autoReserve/picking/publish state реально потребляются либо явно неактивны | OPEN |
| 08 | CATALOG/STOREFRONT/INVENTORY/ORG | publish-to-offer, seller warehouse, immutable snapshot and stock eligibility | OPEN |
| 09 | IAM/PII/tenant ACL | A/B/AB, verified buyer, revoked staff, seller/buyer separation, read/write | OPEN |
| 10 | Ошибки и объём | load/lock stripes/large baskets/timeout/retry, bounded lists; measured PG not invented | OPEN |
| 11 | Договорные/финансовые границы | statuses, refund/payment/delivery/tracking/CRM mapping explicitly deferred or owned | OPEN |
| 12 | Финальная functional acceptance | 100% approved method/use-case matrix, qualified SHA, tests, sign-off and unresolved backlog | OPEN |

**Правило:** проход 01 выполнен как анализ, но не как функциональная реализация. Проходы 02–12 **не закрыты** общим наличием этого ТЗ. Следующий домен — только после решения 12/12; самостоятельные блокеры CATALOG/ORG/EMAIL остаются зависимостями.

## 5. Реестр разрывов с приоритетами

| ID | Severity | Дефект/незрелость | Решение без overengineering |
|---|---|---|---|
| SALES-P0-01 | **P0** | `SalesInventoryCommandService.lines` и `InventoryReservationService.reserve` читают `catalog_item_id` из **текущего** `crm.store_offer`, хотя цена/title snapshot уже в order line. При изменении/отсутствии mapping исполнение не опирается на исходный предмет заказа. | Ввести immutable `catalog_item_id_snapshot` в order line, писать его на checkout; SALES и INVENTORY используют snapshot. Для старых orders — только доказуемый backfill с fail-closed неоднозначностями |
| SALES-P0-02 | **P0** | `store_offer` PUBLISHED может не иметь `catalog_item_id`; checkout принимает его, confirm позже провалится и заказ останется PLACED | Проверять активный PRODUCT+tenant SKU и фиксировать snapshot при checkout; исторические legacy offer без SKU не терять, но блокировать новые неподтверждаемые заказы |
| SALES-P0-03 | **P0** | `confirm` получает любой locationId с stock, не проверяя ACTIVE WAREHOUSE; ORG closure race | Через общий ORG typed admission в той же транзакции + ожидаемый lock order; не копировать rules в SALES |
| SALES-P0-04 | **P0 product truth** | `INV.auto_reserve_on_confirm` редактируем, но `confirm` всегда резервирует; настройка ложно влияет на UI ожидания | Зафиксировать R1 **всегда резервировать при confirm**; удалить/запретить изменение autoReserve либо не представлять как действующую опцию до отдельного R2 flow |
| SALES-P0-05 | **P0 admission** | Email outbox есть, квалифицированной реальной доставки/HTTP anti-abuse нет | Не публиковать checkout как готовый публичный продукт; IAM delivery отдельный gate, не менять почтовую криптографию ради SALES |
| SALES-P1-01 | P1 | Нет seller `get`, order lines, status history (только Page summary); невозможно полноценно обработать заказ | В одном `AuthenticatedSalesQueries` добавить tenant-scoped detail + bounded history + lines, без buyer PII |
| SALES-P1-02 | P1 | Нет сервиса управления `store_front` и `store_offer`; CATALOG publication не отражается в checkout | Малый `StorefrontManagementService`: publish/pause, offer publish/hide, bounded list/read, CAS; явный catalog effective-price projection |
| SALES-P1-03 | P1 | Нельзя штатно отклонить PLACED с reason; enum REJECTED существует, бизнес-команды нет | `reject(VerifiedActor,tenant,orderId,reasonCode,expectedVersion)` на PLACED, никакого stock reserve, 1 event |
| SALES-P1-04 | P1 | Нет reason/actor у `sales_order_status_event`; нет operational SLA | Добавить необязательные actor_user_id/reason_code при новых переходах, сохранить legacy event и PII-free audit |
| SALES-P1-05 | P1 | `fulfill` переводит CONFIRMED в COMPLETED мгновенно; промежуточные states декларированы, но не исполняются | В R1 честно документировать COMPLETED=stock handed-over/fulfilled; добавить 1 минимальный `markPreparing` при подтверждённом решении; delivery/payment отдельно |
| SALES-P1-06 | P1 | Покупатель `buyerOrders` без page/limit/detail; история может расти без bound | `mine(page,size)` + `ownDetail(orderId)`, default bounded, ONLY authenticated user, verified email and ACTIVE user |
| SALES-P1-07 | P1 | `store_offer` price/title не имеют версии и published item binding; изменения цены/статуса в конкурентных checkout не зафиксированы правилом чтения | Явный lock order и immutable committed snapshot; при одновременной публикации price согласован для всех строк одного checkout; без global saga |
| SALES-P1-08 | P1 | Нет seller-owned manual draft flow | После основного checkout E2E: typed draft order + add/update/remove lines до submit; **нельзя** менять confirmed/placed checkout lines |
| SALES-P2-01 | P2 | Нет CRM customer explicit link и портфельного фильтра | Неблокирующая связь `tenant_id + crm_customer_id` только после seller permission; без создания CRM Customer по email |
| SALES-P2-02 | P2 | Нет invoice/payment/delivery integration | Отдельные FINANCE/LOGISTICS фичи, не имитировать их заказными статусами |
| SALES-P2-03 | P2 | Простая offset пагинация и отсутствие поиска по SKU/customer | Сначала фильтры createdAt/status/id + индексы по EXPLAIN; курсор позже при доказанной нагрузке |

## 6. Предлагаемые точные контракты сервиса (subject to passes 02–04)

Сохранять существующие сервисы и значения разрешений. Новый слой только при реальном владельце данных; не создавать отдельный OrderEngine, workflow framework, Kafka/RabbitMQ, distributed saga, payment abstraction или второй каталог.

### SALES read side (`AuthenticatedSalesQueries`)

`get(VerifiedActor actor,UUID tenant,UUID orderId) -> SalesOrderDetail`: permission SALES_ORDER_READ, scoped aggregate, header, immutable lines (offer id, catalog item snapshot, title, qty, price, currency, line total), status and rowVersion; no buyer email/name/identity without separate PII contract. Foreign UUID → NOT_FOUND, не отличать от отсутствующего.

`history(VerifiedActor actor,UUID tenant,UUID orderId,int limit) -> List<OrderStatusEvent>`: permission SALES_ORDER_READ; tenant+order JOIN, limit 1..200, occurredAt DESC,id DESC, no email/PII. Audit immutable, no direct UPDATE/DELETE.

`search(...)`: сохранить существующий `status/fromInclusive/toExclusive/page/size` и default createdAt DESC,id DESC. Добавить orderId exact только при необходимости; total count с тем же tenant predicate. Размер 1..100 и fail fast для чрезмерного OFFSET. Не создавать универсальный search DSL.

### SALES commands (`AuthenticatedSalesCommands`)

Existing `confirm`, `cancel`, `fulfill` сохраняют двоичный SALES+INVENTORY permission admission, atomicity, idempotent terminal retry, status/event ledger. `reject(actor,tenant,orderId,reasonCode,expectedVersion)` только для PLACED, с SALES_ORDER_CANCEL либо специально согласованной permission (решение требуется), без INVENTORY_RELEASE когда резерва не было; reject code bounded, без free text; event atomic.

**State machine R1**: PLACED→CONFIRMED with all-line active reserves; PLACED→CANCELLED or REJECTED; CONFIRMED→CANCELLED (release) or COMPLETED (fulfill); optionally CONFIRMED→PREPARING→COMPLETED if pass 04 approves. COMPLETED/CANCELLED/REJECTED terminal for these commands. READY_FOR_PICKUP/OUT_FOR_DELIVERY are **not to be advertised** until an explicit delivery/pickup process exists. Status `COMPLETED` does not mean buyer delivery or paid invoice.

### AutoOrder (`AutoOrderService`)

`checkout(slug,email,idempotencyKey,lines)` retained; snapshot server-owned catalog SKU, title, effective price/currency at accepted order transaction; basket validation: 1..100 distinct offers, 1..1000 per line and bounded sum quantity; no client-controlled amount. `sales_checkout_receipt` remains transaction+idempotency authority. **Decision needed**: replay returns current status as today or original checkout receipt PLACED; do not silently switch both semantics.

`verifyEmail(challengeId,token)` remains IAM-proof flow, no implicit CRM customer, no OIDC session. `AuthenticatedBuyerOrders.mine(actor,page,size)` and `ownDetail(actor,orderId)` return only buyer_user_id=actor.userId() orders after current verified email; no tenant-wide listings.

### CATALOG→STOREFRONT

New/extended typed adapter publishes a **qualified** offer only from `cat_item.state=PUBLISHED`, valid tenant PRODUCT, source locale title and currently effective price (explicit effective timestamp), fixed currency. A catalog price revision changes **future checkout** prices, not old order snapshots. Hide/PAUSE prevents new checkout; existing PLACED/CONFIRMED may still cancel/fulfill from their frozen lines. Do not overwrite SKU identity on a published offer. No automatic storefront publication merely because SKU was published unless user explicitly enables offer.

## 7. Persistence / migration rules

1. Existing `crm.sales_order_line` retains its primary key, `offer_id`, `title_snapshot`, `quantity`, `unit_price_snapshot`, `currency`. Add nullable `catalog_item_id_snapshot` initially with tenant-composite FK to `crm.cat_item` where feasible. Backfill via `store_offer.catalog_item_id` only for unambiguous, verifiable bindings; collect rejected legacy cases without synthetic SKU.
2. New checkout requires nonnull snapshot for every line; after qualified populated upgrade enforce conditional `NOT NULL` for new rows via service admission or separate migration gate. Do not make historical rows invisible or invent missing SKU for already created orders.
3. `sales_order` total equals SUM(line qty*price) and single ISO currency in application; distinct order rows + receipt and status event must commit atomically. No direct mutable price re-evaluation on confirm.
4. Order status event optional actor and reason columns additive + backwards-compatible; indexes on tenant/status/createdAt/order event only after EXPLAIN; all SQL with bound params and tenant root.
5. Existing composite FKs and immutable stock ledger must remain. No destructive changes to old Liquibase changesets. Idempotent receipt uniqueness `(tenant,idempotency_key)` and `(tenant,order_line_id)` reservation invariant preserved.

## 8. PostgreSQL qualification matrix (proposed)

| Test ID | Scenario / independent assertion |
|---|---|
| SALE-Q01 | Published storefront + valid 2-SKU basket, checkout → order/lines/receipt/event and exact total/currency |
| SALE-Q02 | Duplicate offer IDs and merged qty, max qty/limit/overflow, invalid email/key, cross-tenant offer UUID rejected before persistent order |
| SALE-Q03 | Checkout same key+same payload → one order; changed payload → conflict; concurrent 2 threads / 2 tenants |
| SALE-Q04 | Price/title changes after checkout cannot change seller detail, reservation SKU or historic total; offer unpublish after checkout still permits authorized fulfill |
| SALE-Q05 | Catalog item absent/non-PRODUCT/ARCHIVED/missing mapping → checkout rejected; no orphan email identity/outbox/receipt |
| SALE-Q06 | Seller get/lines/history filtered by tenant; foreign order ID, revoked permissions, user in A+B with different roles, page/count correctness |
| SALE-Q07 | Confirm from PLACED succeeds only with full valid stock and ACTIVE WAREHOUSE; no double reservation, same-location retry idempotent |
| SALE-Q08 | Confirm vs warehouse deactivate/offer unpublish/price change, no deadlock; deterministic stock lock order and no wrong-SKU reserve |
| SALE-Q09 | Confirm insufficient stock rolls back order version, reservations, stock, status event |
| SALE-Q10 | Cancel PLACED/CONFIRMED releases once; cancel vs fulfill exactly one terminal outcome; repeat doesn't duplicate ledger |
| SALE-Q11 | Fulfill only reserved order; exactly one terminal movement/reservation; immutable event, no double debit |
| SALE-Q12 | Email proof purpose, consumed token, OIDC separate login, buyer A cannot read buyer B, historical orders after email change |
| SALE-Q13 | SELLER staff lacks PII unless separately granted; buyer cannot use seller commands; revoked user/tenant, mixed membership A/B, foreign UUID |
| SALE-Q14 | Reject PLACED reason + CAS, no reserve; reject confirmed denied; immutable event and no duplicate |
| SALE-Q15 | Existing populated PostgreSQL migration, existing checkout rows retained, ambiguous SKU cannot be force-backfilled |
| SALE-Q16 | CI exact SHA H2, PG including non-skipped races, populated IAM/CRM, RustFS infra; EXPLAIN/latency only when actually measured |

**Already existing evidence:** `AutoOrderR1QualificationTest` 9 named cases, `SalesInventoryRbacQualificationTest` 13 named cases, `SalesOrderQueryQualificationTest` 4 named cases, plus `InventoryReservationQualificationTest`, `TenantRbacRevocationQualificationTest`, `IamEmailChangeOrderHistoryQualificationTest`. Эти тесты **не** проверяют Q04/Q05/Q06 seller detail/Q14/Q15 новой реализации. Не помечать несуществующие tests GREEN.

## 9. Приоритет реализации после предметных решений

| Slice | Минимальная работа | Acceptance |
|---|---|---|
| SALE-R1.A | Immutable order-line SKU snapshot + checkout admission + populated migration | Q01–Q05/Q15, no existing data loss |
| SALE-R1.B | Seller detail, immutable lines/history, bounded buyer history | Q06/Q12/Q13 |
| SALE-R1.C | ORGANIZATION ACTIVE WAREHOUSE guard + INVENTORY reserve semantics alignment | Q07–Q11, race/rollback |
| SALE-R1.D | Storefront owner management + catalog offer publication bridge | Q04/Q05/Q08 + catalog source-of-truth |
| SALE-R1.E | Narrow reject/status semantics, actor/reason evidence; optional manual draft deferred | Q10/Q14/Q16 |

Не начинать SALE-R1.D с нового marketplace, пока R1.A/B не доказаны: важнее иметь правдивый заказ, чем богатую витрину с неподтверждаемыми товарами.

## 10. Предметные решения к согласованию (не скрытые assumptions)

- **D-01:** Подтверждение заказа R1 всегда резервирует **все строки**. `auto_reserve_on_confirm` не может обещать обратное, пока не появится отдельный workflow. Рекомендация: утвердить «always reserve» и убрать ложный toggle из активных опций.
- **D-02:** `COMPLETED` = складское исполнение/списание; **не** оплата и не доказанное вручение покупателю. Позже для логистики отдельный статусный контракт.
- **D-03:** Разрешён REJECTED из PLACED с reasonCode; отдельная permission vs существующая SALES_ORDER_CANCEL — рекомендация переиспользовать CANCEL на R1.
- **D-04:** У уже размещённого заказа нельзя редактировать строки и цены. Seller draft станет отдельным состоянием/контуром, не меняет историю покупательского checkout.
- **D-05:** Идентификатор товара в заказе фиксируется при checkout; отсутствие SKU mapping для нового stock-required заказа — fail-closed.
- **D-06:** `idempotencyKey` replay: текущий status order vs первоначальный receipt. Рекомендация: возвращать текущий status, но документировать как `get-or-replay`, не выдавать за immutable response.
- **D-07:** R1 резерв и исполнение из **одного склада**, без split.
- **D-08:** Когда SKU опубликован в CATALOG, оффер не должен автоматически появляться без отдельного seller publish/pause.

До утверждения D-01/D-02/D-05 не менять текущий lifecycle. Прочие решения уточняются во 2–4 проходах, чтобы не раздувать MVP.

## 11. Что строго не делать

Не добавлять REST, BFF, UI, Kafka/outbox для SALES ради уже локально атомарных операций, новый заказный агрегат параллельно `crm.sales_order`, универсальную state-machine library, payment placeholder с «PAID», автоматическое создание CRM Customer из email, Redis reservation, microservice boundary или valuation в SALES. Не переписывать квалифицированные reservation/revocation locks без доказанного дефекта.

## 12. Следующий проход (03/12)

Методный протокол завершён на source SHA `75a1fc96a732b8dcacf3b63d5e2a9bbf36627bbd`: [PASS 02 — method trace](../reviews/RM-SALES-AUTOORDER-METHOD-TRACE-PASS-02-2026-11-22.md). **PASS 03:** установить точную схему/nullable/FK/indexes и повторно сравнить с текущим HEAD, спроектировать безопасный populated-backfill неизменяемого SKU snapshot для старых заказов. Нельзя превращать документ 02 в SQL-миграцию без отдельного ревью и PostgreSQL proof.

**Вердикт 01–02/12:** SALES/AutoOrder имеет работающее транзакционное ядро, уровень зрелости **2/5 по исходному аудиту**. Его нельзя повысить до 3/5 только написанием ТЗ. Важнейший минимальный шаг — связать immutable SKU snapshot и seller read/detail, не расширяя платежи или логистику.


## 13. PASS 02 / Method trace — дополнения к ТЗ (2026-11-22)

**Source SHA:** `75a1fc96a732b8dcacf3b63d5e2a9bbf36627bbd`. Полный реестр: [RM-SALES-AUTOORDER-METHOD-TRACE-PASS-02](../reviews/RM-SALES-AUTOORDER-METHOD-TRACE-PASS-02-2026-11-22.md). Текущий `store_offer.catalog_item_id` nullable по Liquibase rm:0026, а `sales_order_line` содержит только `offer_id/title_snapshot/quantity/unit_price_snapshot/currency`. Сами `confirm` и `InventoryReservationService.reserve` берут SKU через изменяемую связь.

**Обязательные уточнения до реализации:**

- **SA2-05 / least privilege:** `cancel(PLACED)` сейчас требует одновременно SALES_ORDER_CANCEL и INVENTORY_RELEASE, хотя резервов может не быть. В проходе 04 утвердить право на отмену до подтверждения и не ослаблять защиту release после резерва.
- **SA2-07 / price consistency:** `checkout` читает `store_offer` раздельно и без явной блокировки, поэтому нужно испытать конкурентную публикацию/изменение цены. Не заменять snapshot на живые цены.
- **SA2-08 / basket validation:** лимит 1000 проверяется на входной позиции **до** merge; суммарное количество дубликатов может превысить лимит. Установить предельную агрегированную quantity с контролируемой ошибкой.
- **SA2-09 / buyer linking:** `verifyEmail` обновляет потенциально несколько заказов разных продавцов для одной глобальной identity; это **намеренное** связывание по пользователю, но не дает tenant employee дополнительных прав. Сохранить и квалифицировать.
- **SA2-11 / tenant operational status:** наличие PUBLISHED `store_front` само по себе не проверяет ACTIVE workspace. Предусмотреть отказ checkout для suspended tenant и race с pause.
- **SA2-12 / read consistency:** отдельные запросы COUNT и page при конкуренции могут наблюдать разные состояния. Не выдавать stable ordering за атомарный snapshot.

**Зафиксированы факты:** `AuthenticatedBuyerOrders.mine` защищён VerifiedActor, но список неограничен; seller `search` PII-free и page size 1..100, но нет get/lines/history; внутренние SALES команды не пустые и уже имеют негативные/конкурентные тесты. Pass 02 не добавляет нового рабочего кода и не меняет уровень 2/5.


## 14. PASS 03 — PostgreSQL integrity / безопасный immutable SKU snapshot (2026-11-22)

**Source SHA:** `1cc19715e82551ec22dd451427ea34b2a8b0c86d`. Полная проверка: [RM-SALES-AUTOORDER-POSTGRESQL-SCHEMA-PASS-03](../reviews/RM-SALES-AUTOORDER-POSTGRESQL-SCHEMA-PASS-03-2026-11-22.md). Изучены фактические changesets rm:0014/0019/0025/0026/0027/0028/0068/0069 для `store_offer`, `sales_order`, `sales_order_line`, `sales_checkout_receipt`, `sales_order_status_event`, `inv_stock_reservation`, включая соседние `store_front`, `cat_item`, stock balance, reservation evidence и их индексы.

**Принято как проектное требование к следующей реализации (не действующий код):** добавить nullable `catalog_item_id_snapshot` на `sales_order_line` с tenant-composite FK; новый checkout должен записывать непустой SKU из PUBLISHED PRODUCT catalog и immutable price/title; SALES confirm и INVENTORY reserve обязаны читать snapshot, а не живой `store_offer.catalog_item_id`. Нельзя автоматически backfill исторические PLACED по текущему offer; значение reservation.item_id доказательно характеризует фактический резерв, но не автоматически SKU в момент checkout.

**Принцип миграции:** read-only preflight/pg_catalog → additive nullable schema → writer/consumer cutover с запретом старых NULL writers → строго доказательный backfill либо исторический NULL → independent populated-DB qualification → только затем решение о DB NOT NULL. Не редактировать старые Liquibase changesets, не удалять historical orders, не использовать COALESCE(snapshot,current offer).

**Гарантии БД:** tenant-composite FK, CHECK quantity/price/status, unique receipt и unique reservation per line, linked reservation/stock, допустимая иммутабельность событий. **Гарантии Java:** active warehouse/catalog state, VerifiedActor/RBAC, single currency, total math, state machine, whole-line reserves, transaction lock order, idempotency. Существующий `sales_order_status_event` пока без immutable PG trigger, а `reservation.item_id` не связан FK с SKU заказа — оба пробела отмечены.

**Новые тестовые требования:** SALE-DB03-01..10 в evidence report. Нельзя считать их существующими или GREEN: это проектируемые квалификационные проверки. **Статус PASS 03 = SOURCE VERIFIED/SPEC PROPOSED; runtime не менялся, зрелость остаётся 2/5.** Следующий проход **04/12 — команды и state transitions**.
