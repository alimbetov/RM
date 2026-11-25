# RM-MVP-SVC-CLOSURE-R1 — единое ТЗ завершения функциональности сервисов (6 рабочих пакетов)

**Дата:** 2026-11-24. **Репозиторий:** alimbetov/RM. **Ветка:** `base_dev`.  
**Проверенная исходная ревизия:** `0477115ea2294a91f35c601de6dce7adb48fd260`.  
**Тип:** исполняемое техническое задание, архитектурное решение и приёмочная матрица.  
**Статус:** `APPROVED MVP SCOPE / SPECIFICATION; IMPLEMENTATION & SERVICE-JOURNEY ADMISSION OPEN`.  
**Область:** только Java 21 / Spring Boot service/application layer, PostgreSQL 17/Liquibase, JUnit/Surefire и CI. **Новые REST controllers, BFF, React/PWA, real OIDC provider integration, новые микросервисы — STOP на этом этапе.**  
**Граница:** `main`/production не изменять; R3.2B B1–B4, R3.2C, SLA/outbox/notifications, FX auto-pricing, packaging materials, finance/payments, delivery, ML — `POST-MVP`.

## 0. Единственная цель, нормативность и условие остановки

Завершить без дополнительных возможностей **две сервисные вертикали**:

- `BUS-MVP-J1`: verified identity → организация → сотрудник и права → клиент/контакт → задача/история → PRODUCT/цена/витрина → приход/остаток → одно-продавцовый заказ → подтверждение и полный резерв на одном складе → разрешённое исполнение → заказ/stock/CRM/TASK история.
- `QR-MVP-J1`: опубликованный KZT PRODUCT_OFFER → стабильный QR → безопасная анонимная карточка → verified buyer → покупка **или** торг → один SALES заказ/снимок → полный резерв → pick/READY → одноразовое подтверждение выдачи → частичный/полный возврат → инспекция → RESTOCK/QUARANTINE/WRITE_OFF и одно складское отражение.

**Остановить backend feature development**, как только оба пути доказаны настоящими PostgreSQL service-integration tests, все `CORE-P0` критические проверки связаны с исполняемыми evidence, а восемь обязательных exact-SHA workflows зелёные на **одной последней** ревизии. Никаких попыток достроить полную ERP.

**Приоритет нормативов:** `docs/architecture/RM-PLATFORM-STANDARD-R2.md` → `docs/strategy/RM-MVP-CLOSURE-FREEZE-R1.md` → утверждённые `DEC-FUL-01/DEC-SAL-02`, IAM/INVENTORY/QR/CUR контракты → это ТЗ. Старые планы, в которых TASKS «после P0/P1», не отменяют уже реализованный `TaskService`. При конфликте фиксировать `DECISION REQUIRED`, не менять поведение молча.

**Разделение готовности:** `CI PASS` не означает `BUS SERVICE PASS`, `QR SERVICE PASS`, настоящий OIDC, HTTP/browser UAT или production. `COMPLETED` не означает `PAID`, `DELIVERED` или `REFUNDED`. Внутренний `VerifiedActor` в synthetic tests не подменяет real OIDC gate G2.

## 1. Результаты жёсткой исходной ревизии (не допущения)

### 1.1. Доказанная baseline и статус

На `0477115ea2294a91f35c601de6dce7adb48fd260` GitHub Actions run [45239831075](https://github.com/alimbetov/RM/actions/runs/45239831075) имеет `SUCCESS`, восемь обязательных workflow `SUCCESS`, exact-SHA Surefire verifier `qa01bGate=PASS`: **1792 тестовых выполнения, 0 failures, 0 errors, 260 разрешённых skips**; Liquibase fingerprint и артефакты проверены. Это агрегат выполнений, **не число уникальных тестов или полных пользовательских путей**. Эта ревизия является **квалифицированной CI baseline / RC candidate**, но комплексная сервисная приемка `BUS-MVP-J1` и `QR-MVP-J1` пока отдельно не засвидетельствована.

После добавления/исправления любого файла текущий HEAD меняется. Предыдущая квалификация остается исторической: **новый SHA обязан пройти полный gate заново**. Нельзя написать `PASS@newSHA` заранее.

### 1.2. Найденные разрывы и уже закрытые инварианты

| ID | Факт в коде/контракте | Вывод | Пакет |
|---|---|---|---|
| `AUD-01 / P0` | `PublishedQrCheckoutService.checkout(publicRef,email,...)` → `AutoOrderService.checkoutPublishedQr` → `findOrProvision(email)` → `PLACED` с `buyer_user_id=NULL` и pending email. `QR-03A` это сознательно разрешает; `RM-MVP-CLOSURE-FREEZE-R1` требует verified buyer **до приватного checkout**. | **Подтверждённое несовпадение продуктовых правил**, не заявлять существующий QR list-price как verified. Нужен узкий verified сервисный путь. | W1 |
| `AUD-02 / P0` | `checkoutAcceptedQuote(VerifiedActor,...)` уже требует ACTIVE account и один CURRENT verified email; пишет `buyer_user_id`. | Образец для W1; **не** переписывать quote engine и не создавать другой SALES aggregate. | W1/W5 |
| `AUD-03 / P0 deployment` | `CommerceCurrencyAdmission` подключён к публикации/QR/checkout/quote, но `rm.currency.commerce-admission.enabled=false` по умолчанию; при `false` методы проверки no-op. | Тесты `strict-on` есть; **публичная KZT-only безопасность не доказана при default-off**, нужен fail-closed режим и preflight истории. | W2 |
| `AUD-04 / P0 authorization` | `SalesInventoryCommandService.confirm/cancel/fulfill` проверяет tenant-domain permissions, но в самом сервисе **не вызывает** `WarehouseStaffScopeService.requireAssigned` для затронутого склада. QR preparation/handover отдельную warehouse scope проверяют. | Не считать tenant-wide grants правом на произвольный склад. Утвердить и проверить `DEC-LOC-03` по конкретным операциям. | W3 |
| `AUD-05 / implemented` | `SalesInventoryCommandService.fulfill` отклоняет `PICKUP_PROOF_REQUIRED`; `cancel` отклоняет после append-only pick confirmation; `SalesPickupHandoverService.complete` проводит атомарный receipt+fulfill. | `DEC-FUL-01` и консервативный `DEC-SAL-02` **не реализовывать повторно**; доказать `cancel×pick`, generic-fulfill bypass и двойное сканирование. | W3/W5 |
| `AUD-06 / traceability` | `TaskService` уже имеет create, assign, reschedule, start, complete, cancel, queue/history, включая PG qualification; старый `RM-DUAL-PRODUCT-CAPABILITIES-R1.json` ошибочно помечает TASKS `SCAFFOLD_ONLY` и `AFTER_CORE_P0_P1`. | Это **устаревший документальный статус**, не отсутствие сервиса. Исправить реестр на уровне доказанной способности, не придумывая новых задач. | W4/W6 |
| `AUD-07 / evidence gap` | `CommercialCoreE2EQualificationTest.skuToSellerOfferToCheckoutToFulfillmentToReconciliation` и специализированные QR/return тесты существуют. | Они не эквивалентны одному тесту `organization→employee→CRM→TASK→order` и двум завершённым QR journey. Требуются **интеграционные оркестрации**, а не дублирование существующих функциональных тестов. | W4/W5 |
| `AUD-08 / migration` | `DEC-FUL-01` ввёл immutable `fulfillment_mode`: `STANDARD` / `PICKUP_PROOF_REQUIRED`. Историческим заказам устанавливается `STANDARD`, происхождение старого QR может быть неоднозначным. | **Нельзя автоматически считать исторический незавершённый QR заказ безопасным**: инвентаризация/quarantine/manual review без переписывания оплаченных/исполненных фактов. | W3/W6 |

**Тесты, уже найденные в исходниках:** `PublishedQrCheckoutQualificationTest` (18 методов), `QrNegotiatedCheckoutQualificationTest` (14), `QrWarehouseHandoverQualificationTest` (58, включая QR-07), `CommerceCurrencyAdmissionQualificationTest` (11), `CommercialCoreE2EQualificationTest` (9), `TaskServiceQualificationTest` (53). Это **число исходных методов**, а не утверждение о прохождении каждого метода на любом произвольном SHA.

### 1.3. Что нельзя менять

Единственные владельцы: IAM — идентичность и grants; ORGANIZATION — locations; CRM — customers/consent/link provenance; CATALOG — SKU; STOREFRONT — offer/current publish price; SALES — checkout/quote/order/status/pickup/return; INVENTORY — reservation/balance/ledger; TASKS — task lifecycle; FILES — безопасные media. Сохранить tenant-qualified FK, order/quote price snapshot, request-idempotency receipts, атомарность same-DB транзакций, PostgreSQL row locks и append-only audit. **Нельзя вводить второй order/stock/quote engine.**

## 2. Жёсткие правила реализации шести пакетов

1. Любая работа начинается с `source inspection + red test`; минимальный patch существующего owner; затем точечный H2/PG test; затем полный CI. Исходное наличие теста не означает достаточность доказательства.
2. Поведение отклонённой команды: **0 новых orders, order lines, receipts, reservations, stock movements, history events, email challenges и других бизнес-побочных эффектов**. Даже после rollback, conflict, revoke, timeout, deadlock retry.
3. Для mutable actor: свежий ACTIVE account, tenant/membership, exact domain grants и warehouse assignment проверяются **в исполняющей транзакции**, с установленным lock DAG; `VerifiedActor` не является grant. Никакой клиентский `userId`/email/publicRef не заменяет proof.
4. Для каждого Wn: позитивная, негативная, tenant A/B, parallel commit A→B / B→A, retry/rollback и independent SQL oracle. Обязательный PostgreSQL `0 skipped` для специфических P0 методов. H2 не заменяет PG для гонок.
5. Новые миграции — только additive и только при доказанной необходимости; **не редактировать применённые changesets**, не менять checksum, никогда не «чинить» CI отключением обязательных тестов.
6. Изменение бизнес-правила документируется `CR-MVP-ID` с точным `J1 step / violated invariant`, проверенным исходником, тестом, решением владельца и SHA. Любое предложение без связи с J1 или P0 безопасности — `POST_MVP`.
7. В рамках этого ТЗ нельзя создавать controllers, gateway, FE, provider integration, scheduler, новый outbox (кроме уже действующего IAM email proof), networking/hosting. Реальный OIDC и UI **только следующий этап**.

## 3. W1 — Verified QR list-price checkout

**Приоритет:** P0 / `AUD-01`. **Owner:** IAM + AUTOORDER/SALES. **Не трогать:** переговорный quote engine, order schema и INVENTORY.

**Нормативное решение QRV-01:** анонимное чтение `PRODUCT_OFFER` остаётся разрешено и не создаёт identity/order. **Новый MVP list-price QR checkout доступен исключительно после `VerifiedActor` (валидный internal principal), ACTIVE OIDC-linked account и CURRENT verified email, принадлежащего тому же `userId`.** Ветка с согласованной ценой уже пользуется таким admission. Подтверждение email без действительного субъекта IAM **не достаточно**.

**Реализация (целевой сервисный контракт, имя уточняется по принятому кодстайлу):**
- Узкий метод `PublishedQrCheckoutService.checkoutVerified(VerifiedActor buyer, String publicRef, int quantity, long acknowledgedVersion, BigDecimal acknowledgedPrice, String acknowledgedCurrency, String requestKey)`.
- В существующем `AutoOrderService` переиспользовать seller checkout stripe, STOREFRONT/offer/product/QR lookup, immutable line/status/receipt и `PICKUP_PROOF_REQUIRED`; buyer principal и CURRENT email брать **из IAM внутри транзакции**, не из request payload.
- Писать `crm.sales_order.buyer_user_id=buyer.userId`, связанный verified `pending_email_identity_id`, единственный `PLACED`, SNAPSHOT unit price/currency/SKU, event, checkout receipt и order locator в **одной** транзакции.
- Guard для account suspension/deletion: buyer lock + проверка ACTIVE до новообразования заказа; фиксированный порядок `checkout stripe → buyer account → seller workspace → tenant currency policy → storefront → offer/PRODUCT/QR → order writes`. Сравнить с quote DAG; исключить обратные захваты. При риске блокировки — документировать и тестировать обе линейные последовательности.
- Для **NEW** checkout требуются ACTIVE QR, published PRODUCT/OFFER, точная версия/цена/валюта KZT. `PRICE_RECONFIRM_REQUIRED`/revoke/expired actor → ни одной записи. `publicRef` — locator, **не grant**.
- Точный retry того же `buyerId + seller/offer + key + terms + mode` возвращает **тот же** order без записи; конфликтующий payload/иной buyer/QR vs list/quote — отказ. Replay чужому buyer никогда не раскрывает orderId. Повтор после отмены публикации допускается **только** для той же ранее авторизованной операции.
- Старую `PublishedQrCheckoutService.checkout(publicRef,email,...)` / `checkoutPublishedQr` трактовать как **QR-03A legacy email-first behavior**. Она не должна оставаться доступным новым приватным QR admission в MVP. Решить через узкую internal-only compatibility boundary или fail-closed strict service gate, не разрушая исторические order receipts. Простое добавление перегрузки при сохранённом обходном публичном method **не является закрытием W1**.
- Существующая `AutoOrderService.checkout` для стандартных ранее принятых business/legacy сценариев сохраняет доказанную семантику; **не переделывать весь checkout ради W1**.

**Новые доказательства (минимальный набор):**
- `qrMvpVerifiedListCheckoutCreatesOneBuyerOwnedOrder`.
- `qrMvpAnonymousAndUnverifiedEmailCannotCreateNewOrder`.
- `qrMvpSuspendOrDeleteVersusCheckoutBothCommitOrders` (2 PG interleavings).
- `qrMvpStaleVersionPriceRevokeAndCrossBuyerReplayHaveZeroEffects`.
- `qrMvpSameKeyTwoJvmExactlyOneOrderAndReceipt` при необходимости; можно прикрепить существующий метод при доказанном равенстве входного контракта.

**Приёмка W1:** нет reachable QR list-price user path, создающего new `PLACED` без verified buyer; у обоих QR checkout modes buyer owner подтверждён по `userId`; не изменились статусы и snapshots старых заказов. Дополнительный actor/PII leak test обязателен.

## 4. W2 — KZT-only public commerce admission

**Приоритет:** P0 / `AUD-03`. **Owner:** ORGANIZATION/CUR + STOREFRONT + SALES. **Не входит:** NBRK auto rates, FX approvals, бухгалтерская переоценка.

**Политика CUR-MVP-01:** любой **NEW public KZ transaction**: published offer, QR issue/reissue, public card, offer price publish/change, proposal/respond/accepted quote checkout, list-price/verified QR checkout — строго `KZT` и tenant policy `market_country=KZ, public_price_currency=KZT, status=ACTIVE` с enabled `PUBLIC_PRICE/KZT` allowlist. Покупатель не может расширить валюту через query/payload.

**Реализация/эксплуатационная квалификация:**
- Проверить все реальные сервисные call sites `CommerceCurrencyAdmission.lockKzMarket/assertKzt/assertPublicRead`, включая verified W1 route и quote, и отсутствие «второго» валютного источника.
- Default `rm.currency.commerce-admission.enabled=false` **не подходит для допуска публичного MVP**. Для сервисного UAT и будущего публичного deployment обязательный `true` + проверяемый preflight. Пока preflight не пройден — `PUBLIC_MARKET_HOLD`, а не «auto-fallback в false».
- Перед включением прочитать **заполненную** БД: tenant policies, missing/duplicate rows, published foreign offers, QR refs, proposals/accepted quotes и активные старые orders. Исторические суммы/price snapshots и ledger **не менять**. Foreign legacy publication → review/quarantine/no new public sale, с трассируемым решением; историческое исполнение допустимых заказов и immutable receipt replay не блокировать без отдельного доказанного запрета.
- Критично: anonymous public card read — advisory и `nonlocking`; нельзя подменять им atomic `checkout` validation. Во время изменения price/policy возможен только пересмотр условий, не молчаливое принятие нового total.
- Зафиксировать PG порядок `IAM/workspace → currency policy → storefront/offer`; buyer checkout — свой stripe и buyer lock перед workspace. Для deny не должно быть новых price revision, order/receipt, reservation, email challenge.
- Обязательно test `enabled=false` как **НЕ admitted market state**. Не выдавать `strict-on` результаты за гарантию default-off. Подготовить stop/start операционный check и отказ при несогласованной policy; конфигурация UAT не равна юридическому production approval.

**Привязка существующих тестов:** `CommerceCurrencyAdmissionQualificationTest.curc01MissingPolicyRejectsCheckoutWithoutSideEffects`, `curc02AllowedKztCreatesImmutableOrderSnapshot`, `curc03UsdPublishedLegacyOfferRejectedWithoutOrder`, `curc05PublicReadGateCannotExposeForeignCurrency`, `curc06PostgresPolicyChangeVsCheckoutLinearizes`, `curc08LegacyForeignQrCardRejectedBeforeMediaExposure`, `curc10PostgresThreeWayCheckoutPriceAndPolicyRaceIsAtomic`. Выполнять на PG без пропуска (11/11 в dedicated suite), но отдельное доказательство исторического UAT preflight и нового W1 метода обязательно.

**Приёмка W2:** все public KZT service entries fail closed при false/missing/review policy/foreign; нет конвертации или изменения принятых исторических money snapshots; два результата гонки policy×checkout подтверждены; migration/backfill с отдельным baseline/restore и raw SQL verification.

## 5. W3 — IAM × SALES × INVENTORY и утверждение DEC-LOC-03

**Приоритет:** P0 / `AUD-04/05/08`. **Owner:** IAM (grants/scope) + SALES (state/orchestration) + INVENTORY (ledger). **Не входит:** reverse-pick, split shipment, новая RBAC.

**DEC-LOC-03/MVP — решение к реализации:** tenant-grant `SALES_ORDER_CONFIRM / INVENTORY_RESERVE` даёт право на операцию, **но не заменяет scope выбранного warehouse**. Для сотрудника warehouse-level assignment должен быть ACTIVE непосредственно перед stock-affecting command. `WarehouseStaffScopeService.requireAssigned` — имеющийся владелец. Для сервисов `confirm` проверять выбранный склад; `fulfill STANDARD` получать **фактический** единый склад из активных reservations (не client input), проверять scope **до** stock debit; `cancel` с release должен проверять склад, из которого освобождаются резервы. Если разные резервы/warehouse или неопределённое местоположение — fail closed; без неявного переноса резерва. Владелец/менеджер без складского назначения не получает wildcard: исключение — только если предметная IAM-политика явно утверждена и защищена отдельными тестами. `QR-05/06` warehouse gate не ослаблять.

**Lock DAG:** `TenantAuthorization` актуальных permission/actor/workspace → `crm.sales_order FOR UPDATE` → warehouse assignment/location lock в согласованном с текущими QR-сервисами порядке → reservation/stock ledger. Конфликт с уже существующими lock order **решать тестом и документированным DAG, не догадкой**. После `scope revoke`, location pause/close, account/role revoke нет новой складской операции; ранее коммитнутый законный заказ/движение остаётся неизменным.

**DEC-SAL-02:** существующий `cancel(PREPARING)` после хотя бы одного подтверждённого PICK — **DENY**, без release/reservation/receipt/history mutation. До PICK отмена допустима с полным возвратом всех active reservations; `READY_FOR_PICKUP/COMPLETED` не отменять через generic cancel. Не внедрять reverse-pick в MVP. Запрет `generic fulfill` для `PICKUP_PROOF_REQUIRED` оставить и при retry после COMPLETED.

**Исторические QR-origin заказы:** перед populated admission выявить незавершённые заказы с `STANDARD`, происхождение которых невозможно удостоверить; при сомнении **не предоставлять generic stock debit** до reviewed quarantine/classification. Никакого автоматического «переименования» `STANDARD` по одному `ORDER_REFERENCE` — он выдаётся и стандартному заказу.

**Обязательные проверки:**
- `iamSalesWarehouseConfirmRequiresAssignmentAndTenantGrant`.
- `iamSalesWarehouseFulfillAndReleaseRespectScopedReservationWarehouse`.
- `iamSalesWarehouseRevokeVsConfirmAndFulfillBothCommitOrders` (PG, обе последовательности).
- `salesMvpCancelBeforePickReleasesOnceAndAfterPickDeniesWithoutEffects`.
- `salesMvpGenericFulfillCannotCompleteProofRequiredOrderEvenAfterRetry`.
- `salesMvpInventoryReservationMovementAndHistoryConserveQuantities`.
- Существующие `QrWarehouseHandoverQualificationTest.qr06bPg...`, `CommercialCoreE2EQualificationTest.postgresSec09...`, `OrganizationLocationR11ConcurrencyQualificationTest.postgres...` и `SalesInventoryRbacQualificationTest` переиспользовать при доказанной эквивалентности. Не умножать дублирующиеся тесты.

**Приёмка W3:** нет warehouse-object bypass, oversell, negative sellable balance, двойного debit, «терянного» release, невалидного generic QR fulfillment, успешной отмены после irreversible PICK; результаты независимого SQL ledger oracle равны расчетным ожиданиям.

## 6. W4 — BUS-MVP-J1: одна полная PostgreSQL сервисная вертикаль

**Приоритет:** P0 / `AUD-06/07`. **Owner:** integration QA + IAM/ORG/CRM/TASKS/CATALOG/STOREFRONT/SALES/INVENTORY.

**Новый интеграционный contract:** `BusMvpServiceJourneyQualificationTest` (или соседний существующий `CommercialCoreE2EQualificationTest`, если минимальная доработка). **Не** один giant test, скрывающий причину поломки: один полный позитивный метод и 2–4 узких отрицательных/race метода, разделяющих общий воспроизводимый fixture.

**Сервисный путь, вызываемый методами Java без REST и ручного SQL в качестве команд:**
1. Synthetic trusted `VerifiedActor`/ACTIVE OIDC-linked and verified-email setup **только как тестовый fixture**; владелец `SelfServiceTenantOnboardingService.createFirstTenant` и `selectTenant` → 1 active organization.
2. Добавить seller staff и warehouse staff через `TenantMemberEnrollmentService`/`TenantRoleService`; предоставить **явные** grants и складское назначение; tenant B + warehouse B для denied-вызовов.
3. CRM через существующие authenticated фасады: customer, contact, optional contract/interaction; follow-up через `TaskCrmFollowupService`/`TaskService.create`, assign → start → complete → `historyMine`. Нет автоматической TASK SLA отмены.
4. Создать и опубликовать `PRODUCT` SKU KZT, затем продавцовый STOREFRONT/OFFER. Приход на permitted warehouse через INVENTORY service, прочитать stock balance.
5. Один заказ через существующий `AutoOrderService`/trusted existing Business checkout, а не второй direct-SQL SALES writer; проверить immutable accepted unitPrice/SKU/quantity/currency и `PLACED` без резерва.
6. Seller/warehouse actor подтверждает order и один склад; INVENTORY создаёт full active reservations; PREPARING/READY path используется **только если совместим** с `DEC-FUL-01`: нельзя применять generic `STANDARD fulfill` после физического PICK. Для `STANDARD` разрешён отдельный `confirm→fulfill` без PICK; для `PICKUP_PROOF_REQUIRED` используется защищённый pickup путь. **Не создавать тест с нелегальным смешением каналов ради зелёного результата.**
7. Проверить `COMPLETED` как физическое fulfillment, ровно один ledger decrement, reserved=0, immutable order/status/CRM/TASK/stock history, read-after-restart. Отдельным сценарием `PLACED/CONFIRMED→cancel` до PICK: release ровно один.
8. После revoke/foreign tenant/foreign warehouse любые следующие commands deny и не создают новых business events.

**Минимальные тесты:** `busMvpJ1OrganizationEmployeeCustomerTaskSkuOrderStockHistoryPg`, `busMvpJ1CancelBeforePickAndReplayNoSecondLedgerPg`, `busMvpJ1ForeignTenantRevokedActorAndWrongWarehouseZeroEffectsPg`; использовать существующие per-domain tests для remaining P0 without duplication.

**Приёмка W4:** один PostgreSQL run доказал связь IAM→ORG→CRM/TASKS→CATALOG/STOREFRONT→SALES→INVENTORY→история. Ручные SQL INSERT для шага бизнес-команды запрещены; SQL SELECT допустим **только** как независимый oracle и setup/cleanup fixture. Все затронутые tenant IDs/снимки/receipt/ledger проверены.

## 7. W5 — QR-MVP-J1: покупка и торг до возврата

**Приоритет:** P0 / `AUD-01/02/03/05/07`. **Owner:** integration QA + STOREFRONT/SALES/INVENTORY/IAM/FILES.

**Новый интеграционный contract:** `QrMvpServiceJourneyQualificationTest`, **две независимые ветки** над одним canonical SALES/INVENTORY engine, без HTTP:
- **A — LIST_PRICE:** seller publish KZT PRODUCT+OFFER → `PublishedOfferQrService.issueOrGet` → `PublishedOfferCardQuery.findPublished` (anonymous, sanitized approved media, no identity/PII leak) → VERIFIED buyer W1 current price/version ack → один `PLACED` с `buyer_user_id` и `PICKUP_PROOF_REQUIRED` → seller confirm/all-or-nothing stock reserve.
- **B — NEGOTIATED:** той же модели PRODUCT_OFFER → `SalesPriceProposalService.submit/buyerRespond/sellerRespond` и ровно одно accepted quote → `AutoOrderService.checkoutAcceptedQuote(VerifiedActor,...)` → один заказ, согласованная (не переписанная live list price) immutable цена, quote `CONSUMED` ровно один.
- **Общее завершение обеих:** `SalesOrderPreparationService.startPreparing/confirmPicked/markReadyForPickup` → `SalesPickupHandoverService.issue/preview/complete` → credential one-time consumption, immutable handover receipt, stock decrement exactly once; `SalesReturnRequestService`/`SalesReturnReviewService`/`SalesReturnPhysicalService` + `SalesReturnReceptionProofService` + quarantine resolution where applicable → ORIGINAL `COMPLETED` stays, disposition RESTOCK/QUARANTINE/WRITE_OFF, return-linked movement max once.
- Все return paths должны иметь доказуемую provenance к original handover, предел количества возвращённого, запрещённый преждевременный sellable restock и net stock conservation. Для QUARANTINE выполняется учёт несбыточного остатка отдельно от sellable.
- QR reference, order reference, pickup credential и return proof **разные сущности и полномочия**. Нельзя читать private order по публичному QR, сканировать как pickup по order ref или логировать raw secret.

**Минимальные новые методы:** `qrMvpJ1VerifiedListPriceToHandoverAndRestockPg`, `qrMvpJ1NegotiatedPriceToHandoverAndQuarantineOrWriteOffPg`, `qrMvpJ1DoubleScanCrashRetryAndCrossTenantNoSecondDebitPg`, `qrMvpJ1CurrencyPriceRevokeAndForeignSkuAllDenyWithoutEffectsPg`. При наличии точного существующего `QrWarehouseHandoverQualificationTest.qr07R25...` и `QrNegotiatedCheckoutQualificationTest.qr04b...` — ссылаться на его evidence, а не копировать ту же race реализацию.

**Приёмка W5:** обе ветки полностью заканчиваются корректным возвратом и проверенным инвентарным движением; competing employee scan и return replay не проводят второй stock posting, suspended buyer/staff и tenant B не видят/не изменяют private order/credential. Никакого refund/paid/ship state.

## 8. W6 — final service admission, 54 CORE-P0 и release SHA

**Приоритет:** P0 для каждого сценария, затрагивающего MVP безопасность или J1; owner QA/architecture. **Новый продуктовый функционал в W6 запрещён.**

### 8.1. Coverage registry: ровно 54 исходных CORE-P0 IDs

Использовать `docs/strategy/RM-DUAL-PRODUCT-CAPABILITIES-R1.json` как **первоначальный список ID**, не как источник PASS. Регистру присущи исторические `reviewedSourceSha`, `TASKS SCAFFOLD_ONLY` и `taskPhase AFTER_CORE_P0_P1`: актуализировать эти утверждения **после фактического анализа**, сохранив историю изменений и не переписывая доказательства.

| IDs | Кол-во | Какие пакеты проверяют |
|---|---:|---|
| `CORE-IAM-01..06` | 6 | W3/W4/W5 |
| `CORE-ORG-01..02` | 2 | W3/W4 |
| `CORE-CRM-01..06` | 6 | W4/W5 (link/history) |
| `CORE-CAT-01..03` | 3 | W4/W5 |
| `CORE-STO-01..03` | 3 | W2/W4/W5 |
| `CORE-SAL-01..10` | 10 | W1/W3/W4/W5 |
| `CORE-INV-01..02` | 2 | W3/W4/W5 |
| `CORE-QR-01..07` | 7 | W1/W2/W5 |
| `CORE-PICK-01..05` | 5 | W3/W5 |
| `CORE-RET-01..05` | 5 | W5 |
| `CORE-XD-01..05` | 5 | W4/W5/W6 |
| **Итого** | **54** | **Ни один ID не пропадает без reviewed scope decision** |

**Обязательная запись для каждого ID:** `ID | business step | owner | status REQUIRED_FOR_MVP / ALREADY_COVERED_BY_EXECUTED_TEST / OUT_OF_SCOPE | severity | source service method | positive test | negative test | PG/race/rollback method | test suite/artifact | exact SHA | result | SQL oracle | reviewer | reason`. `ALREADY_COVERED` означает тест **фактически выполнился на admission SHA**, не просто существует в Java. `OUT_OF_SCOPE` допустим только если конкретная функция не включена и тест не защищает общее P0; безопасность, IAM, stock, price, currency, migrated old data исключить невозможно. Незакрытый `REQUIRED_FOR_MVP` → `HOLD`.

**TASKS:** кроме 54 исходных CORE-P0 добавить явный `BUS-TASK-01` (create/assign/start/complete/history), `BUS-TASK-02` (revoked/foreign staff/tenant deny), с источником `TaskServiceQualificationTest`; **не искусственно переписывать знаменатель 54**.

### 8.2. Последний exact-SHA CI gate

Без новой девятой обязательной группы: интегрировать W1–W5 новые JUnit методы в существующую полную H2/PG `RM Domain Security and PostgreSQL Qualification`; дополнять `.github/scripts/verify_rm_qa_01b.py` списком **названных обязательных** PG journey методов и конкретных safety cases, чтобы зелёный workflow не скрывал `skip`. Targeted CUR/QR workflows могут быть дополнительным доказательством; они не заменяют восемь обязательных:

1. `RM Domain Security and PostgreSQL Qualification` (H2 + full PG + IAM populated).
2. `CRM R1.2 Populated PostgreSQL Upgrade Qualification`.
3. `INVENTORY 0081 Populated PostgreSQL Upgrade Qualification`.
4. `SALES 08.4 Populated PostgreSQL Upgrade Qualification`.
5. `RustFS Real Infrastructure E2E`.
6. `RM TASK R2 Service Qualification`.
7. `RM TASK R3.2A Populated PostgreSQL Upgrade Qualification`.
8. `RM TASK R32B T00 PostgreSQL Contract Qualification`.

Для каждого required method: `tests≥1, failures=0, errors=0, skipped=0` **в PostgreSQL Surefire XML**; H2 PG-only skips допускаются только явным списком. Текущий `verify_rm_qa_01b.py` уже проверяет workflows, expected artifact, SHA, REQUIRED_CASES, Liquibase fingerprints, но **W4/W5 complete journeys ещё не являются отдельными обязательными named methods**: включить после реализации тестов.

Отдельно: populated PostgreSQL upgrade с legacy QR `STANDARD` review, historical foreign KZT policy scan, true currency flag, сохранение existing CRM/SALES/INVENTORY/history, backup→restore/digest equality. На SQL oracle проверять warehouse authorization, one seller/order, unique idempotency, stock onHand/reserved/fulfilled, order state, handover/return receipts и связанные события. `SHA-256` гарантирует целостность артефакта/снимка, **не** правильность бизнес-процесса без assertions.

### 8.3. Допуск и стоп-условия

| Verdict | Когда ставить |
|---|---|
| `SOURCE IMPLEMENTED` | метод существует, но нет исполненного доказательства |
| `QUALIFIED@SHA` | конкретные обязательные тесты и артефакты на одном SHA |
| `BUS_SERVICE_PASS@SHA` | W1–W4/W6 необходимые positive/negative/race/rollback, BUS-J1 end-to-end |
| `QR_SERVICE_PASS@SHA` | W1–W3/W5/W6 необходимые positive/negative/race/rollback, оба QR пути/returns |
| `SERVICE_FUNCTIONAL_COMPLETE@SHA` | **обе** PASS + 54 CORE-P0 disposition + TASK addendum + full PG/H2/populated + 8/8 exact-SHA + no unresolved P0 |
| `G2 / PILOT / PRODUCTION` | **HOLD** до отдельной live OIDC/API/UI, реальной эксплуатации, UAT и security/legal admission |

Остановить работу при `SERVICE_FUNCTIONAL_COMPLETE`. Не добавлять статистические dashboards, email automation, новые CRM/TASKS capabilities, multiwarehouse, FEngine/FIN или оплату в scope сервисного MVP.

## 9. Исполнимая очередь задач и dependencies

| Порядок | Ticket | Содержимое deliverable | Выходной gate |
|---|---|---|---|
| 0 | `MVP-SVC-AUD` | Проверка source method, red tests и lock DAG для AUD-01/03/04; одно решение DEC-LOC-03 | Уточнён P0 и отсутствует скрытый новый scope |
| 1 | `MVP-SVC-W1` | Verified QR checkout в текущем AutoOrder + backward-compatible legacy policy + PG security/replay | W1 PASS |
| 2 | `MVP-SVC-W2` | CUR strict-on + populated preflight/legacy quarantine + PG policy races | W2 PASS |
| 3 | `MVP-SVC-W3` | warehouse scope for generic SALES + DEC-SAL-02/DEC-FUL-01 safety + PG races | W3 PASS |
| 4 | `MVP-SVC-W4` | BUS-J1 через IAM/CRM/TASK/PRODUCT/ORDER/STOCK/HISTORY | BUS service journey PASS |
| 5 | `MVP-SVC-W5` | QR-J1 LIST_PRICE и NEGOTIATED → pickup → return | QR service journey PASS |
| 6 | `MVP-SVC-W6` | 54-ID evidence, mandatory named test gate, populated migration, full exact-SHA, immutable candidate | SERVICE FUNCTIONAL COMPLETE или blocking finding |

W1/W2/W3 могут готовиться параллельно по исходникам и тестам, но **не** вводить несовместимые изменения в checkout/warehouse lock DAG одновременно. W4/W5 запускаются после W1–W3; W6 — по конечному commit. Для каждого ticket одна целевая ветка `base_dev`, один законченный минимальный patch и test evidence. Не плодить release branches без отдельного решения.

## 10. Definition of Done (приёмочный протокол архитектора)

- [ ] W1: QR list-price/new order проверяет `VerifiedActor` + ACTIVE + current verified email; анонимная public card ничего приватного не создаёт; order buyer-owned; replay не раскрывает чужого order.
- [ ] W2: public KZ `KZT-only` принудителен в UAT; disabled/missing policy = not admitted; historical foreign offers classified, accepted old receipts не переписаны.
- [ ] W3: tenant grant **и** warehouse scope проверяются перед confirm/stock movement/release; revoke races в обоих порядках; cancel-after-pick deny; `PICKUP_PROOF_REQUIRED` generic fulfillment запрещён.
- [ ] W4: BUS-MVP-J1 положительный + отрицательный + rollback/race тесты исполняются на PG, клиент/задача/снимок/склад/история согласованы.
- [ ] W5: QR-MVP-J1 обе price paths, оба заканчиваются proof handover и корректным disposition; stock/receipt/posting idempotent; PII защищена.
- [ ] W6: 54 CORE-P0 ID без молчаливых пропусков, 2 TASK addendum; явные Surefire method names; independent SQL oracle; populated upgrade/restore; восемь exact-SHA workflow `SUCCESS`.
- [ ] Один SHA, GitHub Actions URLs, SHA-256 digest артефактов, verification manifest, diff of Liquibase, zero unresolved P0.
- [ ] Документально выдано `BUS_SERVICE_PASS@SHA` + `QR_SERVICE_PASS@SHA` → `SERVICE_FUNCTIONAL_COMPLETE@SHA`. После этого **STOP feature development**, следующий отдельный G2.
- [ ] `main` не менялся, production stays `HOLD`, внешние OIDC/controllers/React не добавлены.

## 11. Передача следующему исполнителю / Codex

Работать только в `base_dev`. Первым выполнить `MVP-SVC-AUD` и W1, затем W2/W3, потом два J1 и W6. Перед любой модификацией прочитать текущий HEAD и соответствующий method+test. Для каждого PR/commit приложить **было → стало**, точный invariant, Java code path, test method, concurrent commit order, independent SQL verification, run ID/SHA/artifact и одно из `PASS / HOLD`. Не писать полные обзоры заново, не плодить дополнительные ТЗ: это **единственный активный service functional closure plan** до G2.

**Особо важное ограничение:** само сохранение этого Markdown — `DOCUMENTATION ONLY`. Оно не реализует W1–W6 и не выдаёт нового `SERVICE_FUNCTIONAL_COMPLETE`. Предыдущий verified CI SHA остаётся историческим; квалификация следующего SHA устанавливается **только после завершения CI**.
