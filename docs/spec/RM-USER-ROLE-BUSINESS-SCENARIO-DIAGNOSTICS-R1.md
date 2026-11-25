# RM-QUAL-USER-R1 — Диагностика бизнес-сценариев по пользователям, ролям и правам

**Дата:** 2026-11-22  
**Репозиторий:** `alimbetov/RM`; **ветка разработки:** `base_dev`  
**Статус:** SPECIFIED / JUNIT SERVICE-LEVEL QUALIFICATION ONLY / IMPLEMENTATION PENDING / PRODUCTION HOLD  
**Приоритет:** P0 (авторизация, изоляция, доказательство отказов), P1 (сквозные бизнес-сценарии на уровне Java-сервисов)  
**Связи:** RM-SEC-09A; RM-STORE-09B; RM-INV-16; RM-QA-01B; RM-SALES-10A; RM-STORE-10A; RM-E2E-04; RM-R2.1.

## 1. Цель

Создать **воспроизводимую диагностическую систему бизнес-сценариев RM**, проверяющую реальное поведение системы для разных пользователей, организационных ролей, назначенных tenant-grants, статусов аккаунтов и разных организаций. Предмет диагностики — вызов доверенного Java-сервисного фасада с настроенной тестовой личностью и правами до итоговых изменений в авторитетных таблицах. REST и HTTP не участвуют.

Целевое утверждение: **«конкретный субъект с конкретным набором прав может совершить только разрешённое бизнес-действие над принадлежащим ему объектом, а отказ не создаёт запрещённых побочных эффектов»**.

Не добавлять альтернативный IAM, теневые полномочия, дублирующие предметные таблицы, глобальную роль «суперпользователь всех tenants» или обход domain services через диагностический код.

## 2. Зафиксированная фактическая модель RM

- **Global identity:** `iam_user_account`, связь OIDC `sub → external_subject`, `ACTIVE` и прочие состояния глобального аккаунта; токен не предоставляет tenant permissions сам по себе.
- **Workspace/tenant:** `iam_workspace.id`; для коммерческого tenant требуется `kind=ORGANIZATION`, `status=ACTIVE`. Tenant идентификаторы в URL не служат доказательством доступа.
- **Membership:** `iam_org_membership`, роли `OWNER, ADMIN, MANAGER, MEMBER, VIEWER`; статусы `INVITED, ACTIVE, SUSPENDED, LEFT`.
- **Функциональный RBAC:** `iam_tenant_rbac_config.enforced`, `iam_tenant_role`, `iam_tenant_user_role`, `iam_tenant_role_permission`, явный `DomainPermission`. При `enforced=true` нужны активная membership, активная роль и явно назначенный grant. При `enforced=false` действует текущая программная политика `TenantAuthorization.permitted`: многие коммерческие записи закрыты даже для OWNER; не предполагать автоматическое «OWNER=все действия».
- **Особый non-delegable access:** `TENANT_RBAC_MANAGE` требует OWNER membership и не должен делегироваться обычным role grant.
- **Buyer:** покупатель не обязан входить в membership организации продавца; доступ к собственной истории — по подтверждённой email-идентичности и доверенному `VerifiedActor`. Создание checkout — отдельная публичная/покупательская политика, не seller permission.
- **Транзакционная защита:** `TenantAuthorization.requireForCommand` сериализует доступ с account/workspace locks и проверяет актуальные права в команде.
- **HTTP/W5 вне текущего ТЗ:** какие-либо существующие или будущие контроллеры не используются для этой диагностики. Проверяются исключительно application/service contracts; пользовательский HTTP E2E имеет отдельный gate.
- **Reconciliation:** per-transaction `REPEATABLE_READ`, `posting_version` новых проводок и `UNVERIFIED_MOVEMENT_ORDER` старых. `COMPLETED` диагностического run **не означает** единого сертифицированного tenant-wide snapshot.

Объектные источники истины: IAM → субъект и права; CATALOG → SKU; STOREFRONT → текущая карточка и цена; SALES → принятая цена/позиции/заказ/история статусов; INVENTORY → складские позиции/резервы/проводки; AUDIT → доказательные события.

## 3. Модель участника теста

Каждый сценарий определяется как кортеж:

`(subject, authentication, accountState, tenant, workspaceState, membershipRole, membershipState, rbacEnforced, grantedPermissions, targetObjectOwner, operation, inputs, concurrencyOrder, expectedDecision, expectedStateDelta)`.

Для каждого запуска формировать независимые записи:

| Субъект | Смысл | Условие |
|---|---|---|
| U0 | Анонимный посетитель | Нет bearer token |
| U1 | Покупатель | Тестовый доверенный субъект с подтверждённой email identity, без seller membership |
| U2 | Владелец организации A | ACTIVE + OWNER membership A |
| U3 | Администратор A | ACTIVE + ADMIN membership A |
| U4 | Менеджер A | ACTIVE + MANAGER membership A |
| U5 | Сотрудник A | ACTIVE + MEMBER membership A |
| U6 | Наблюдатель A | ACTIVE + VIEWER membership A |
| U7 | Сотрудник без membership | Глобальный ACTIVE, но не состоит в A |
| U8 | Участник B | Membership только в организации B |
| U9 | Участник A и B | Разные роли и grants для каждой организации |
| U10 | Блокированный субъект | Глобальный SUSPENDED |
| U11 | Отозванный субъект | ACTIVE user, но role/grant revoked или membership SUSPENDED/LEFT |
| U12 | Недостоверный субъект | Неверный/истёкший JWT, issuer, audience, signature или чужой `sub` |

Оба tenants A и B содержат реальные независимые warehouses, SKU, offers, buyers, orders и их истории. Ни одна проверка не должна автоматически создавать привилегии за счёт роли OWNER без соответствующего active grant при `enforced=true`.

## 4. Матрица разрешений — базовый исполняемый контракт

Обозначения: **G** — положительный сценарий только при активном явном grant; **D** — DENY; **O** — non-delegable OWNER authorization; **S** — строго owner-scoped buyer read; **P** — отдельно квалифицированная public/checkout policy; **R** — разрешено в режиме `enforced=false` только если это подтверждено текущим `TenantAuthorization.permitted`. Положительный тест G необходимо паровать негативным тестом для той же membership без grant.

| Бизнес-операция | Permission/условие | OWNER | ADMIN | MANAGER | MEMBER | VIEWER | Buyer без membership |
|---|---|---|---|---|---|---|---|
| Настроить tenant роли/grants | TENANT_RBAC_MANAGE | O | D | D | D | D | D |
| Просмотр неперсонального CRM | CRM_READ | G/R | G/R | G/R | G/R | G/R | D |
| Просмотр CRM PII | CRM_PII_READ + policy/consent | G/R | G/R | G/R | D | D | D |
| Изменение CRM | CRM_WRITE | G/R | G/R | G/R | D | D | D |
| Создать/изменить товар | CATALOG_WRITE | G | G | G | G | G | D |
| Опубликовать SKU | CATALOG_PUBLISH | G | G | G | G | G | D |
| Создать/изменить offer/цену | STOREFRONT_OFFER_MANAGE | G | G | G | G | G | D |
| Публикация/pause витрины и offer | STOREFRONT_OFFER_PUBLISH | G | G | G | G | G | D |
| Просмотр заказов продавца | SALES_ORDER_READ | G/R | G/R | G/R | G/R | G/R | D |
| Confirm seller order | SALES_ORDER_CONFIRM + INVENTORY_RESERVE | G | G | G | G | G | D |
| Cancel seller order | SALES_ORDER_CANCEL (+ applicable INVENTORY_RELEASE) | G | G | G | G | G | D |
| Fulfill seller order | SALES_ORDER_FULFILL + INVENTORY_FULFILL | G | G | G | G | G | D |
| Приход на склад | INVENTORY_RECEIVE | G | G | G | G | G | D |
| Корректировка склада | INVENTORY_ADJUST | G | G | G | G | G | D |
| Сверка склада | INVENTORY_STOCK_READ | G/R | G/R | G/R | G/R | G/R | D |
| Buyer own history | Verified buyer, own `buyer_user_id` | S | S | S | S | S | S |
| Checkout опубликованной витрины | public anti-abuse/checkout policy | P | P | P | P | P | P |

**Важно:** G не означает, что конкретная роль по умолчанию имеет право. Матрица специально проверяет независимость organizational membership и tenant grants; при `enforced=false` применяются **реальные**, а не предполагаемые fallback правила. Неподдерживаемый endpoint или операция = `NOT_IMPLEMENTED`, а не PASS/DENY.

Проверять комбинированные разрешения отдельно: только SALES_ORDER_CONFIRM без INVENTORY_RESERVE → DENY; только INVENTORY_RESERVE без SALES_ORDER_CONFIRM → DENY; аналогично fulfillment.

## 5. Диагностические бизнес-истории

Каждая история исполняется минимум с успешным actor и одним запрещённым actor; версии A/B, RBAC on/off и error/rollback не исключаются.

| ID | История и исходные условия | Успешный исход | Обязательные отрицательные варианты |
|---|---|---|---|
| BS-01 | Service principal / subject mapping | ACTIVE test subject маппится на единственный RM user | Null actor, unknown subject, blocked; JWT validator — отдельный unit-level test, без REST |
| BS-02 | OWNER создаёт роль и выдаёт grants | Иммутабельное RBAC evidence, tenant-scoped назначение | ADMIN и MANAGER не становятся OWNER; grant без membership; чужой tenant |
| BS-03 | Seller создаёт и публикует SKU | CATALOG authoritative PUBLISHED, 1 expected audit | Нет CATALOG_WRITE/PUBLISH, archived SKU, suspended tenant |
| BS-04 | Создание storefront и offer | Единственная tenant storefront, привязка offer к SKU | Чужой SKU, дублирующий storefront, слабый grant |
| BS-05 | Публикация/pause offer | CAS, согласованный фасад, верная версия | stale CAS, no grant, revoked, paused storefront |
| BS-06 | Изменение цены | Ровно один price revision + один ожидаемый audit на изменение | same price no-op; CAS conflict; чужой tenant; missing grant; старые snapshots неизменны |
| BS-07 | Buyer checkout | Order PLACED, иммутабельные price/SKU snapshots, корректный receipt | Suspended seller, invalid basket, mixed currency, idempotency mismatch, pre-confirm reservation |
| BS-08 | Seller detail/история статусов | Только заказ своего tenant, неперсональные поля | Forged orderId, tenant B, no SALES_ORDER_READ, revoked, invalid page |
| BS-09 | Seller confirm + stock reserve | CONFIRMED + ACTIVE reservation, stock reserved | Только одно из двух разрешений, insufficient stock, simultaneous confirm, suspended |
| BS-10 | Seller fulfill | COMPLETED + ровно одна проводка, reserved released, no duplicate replay | Wrong state, missing permission, double fulfillment, race with cancel |
| BS-11 | Buyer history | Только свои заказы, deterministic pagination | Пользователь A запрашивает заказ B, unverified email, blocked subject |
| BS-12 | Stock receive/transfer/correction | Quantity equation, два transfer legs, monotonic posting_version | Negative stock, duplicate key conflict, wrong warehouse, missing grants |
| BS-13 | Reconciliation | Stable per-position diagnostic, no stock repair | Race with receive/fulfill/transfer; legacy ORDER_UNVERIFIED; 500+ history |
| BS-14 | Tenant/account lifecycle | Новые команды после suspend/revoke отвергаются | Обе сериализации `checkout × suspend`, `fulfill × revoke`, `price CAS × revoke` |
| BS-15 | Recovery / retries | Idempotent retry не дублирует эффект | Timeout, rollback after mutation, crash/restart, repeated step, stale token |

BS-07 выполняется через Java `AutoOrderService.checkout` и всегда квалифицируется как `SERVICE_ONLY`, не удовлетворяя W5. Внешние IdP/email/HTTP не являются зависимостью текущей диагностики.

## 6. Диагностическая матрица исходов и побочных эффектов

Для каждой операции фиксировать два независимых доказательства: **response** и **persisted result**.

- **ALLOW:** ожидаемый статус/DTO; правильные tenant + object ownership; ровно заявленный delta строк/версий; бизнес-инварианты удовлетворены.
- **DENY_AUTHN:** на текущем сервисном уровне недоверенный/отсутствующий VerifiedActor или неверная синтетическая JWT-проверка отвергается до бизнес-команды; HTTP 401 не проверяется этим ТЗ.
- **DENY_AUTHZ:** доверенный VerifiedActor, но нет membership/grant/tenant ACTIVE → SecurityException/доменный отказ без запрещённых изменений; HTTP 403 вне объёма.
- **NOT_FOUND_MASKED:** чужой объект внутри доступного tenant — одинаковое внешнее поведение с отсутствующим объектом; запрещено подтверждать существование чужого заказа.
- **CAS_CONFLICT:** типизированный доменный конфликт/исключение, версии и история неизменны; HTTP 409 вне объёма.
- **INVALID_INPUT:** валидационное исключение Java-сервиса, нет side effects; HTTP 400 вне объёма.
- **IDEMPOTENT_REPLAY:** идентичный receipt/state, новых business rows = 0.
- **NOT_IMPLEMENTED:** отсутствующий сервисный контракт/операция не проходит gate; отсутствие REST-маршрута не является ни успешной, ни отрицательной проверкой.
- **INFRA_UNAVAILABLE:** недоступность IdP/JWKS, email, DB, KMS, RustFS — отказ по явному fail-closed контракту, не автоматический GRANT/PASS.

Для каждого отрицательного теста сохранять before/after:
`sales_order`, `sales_order_line`, `sales_order_status_event`, `inv_stock_balance`, `inv_stock_reservation`, `inv_stock_movement`, `store_offer`, `store_offer_price_revision`, `audit_event` и соответствующие outbox/verification rows. Набор таблиц выбирать по операции: утверждать «нулевые побочные эффекты» только после сравнения **релевантных** авторитетных данных; диагностическое событие DENY допустимо, бизнес-эффект DENY — нет.

## 7. Конкурентные сценарии: обязательные контролируемые порядки

Для каждой пары команд зафиксировать **W1 commits first**, **W2 commits first**, конфликт/rollback, попытку retry и исход на 2 независимых соединениях PostgreSQL (для P0 — дополнительно два процесса/pod при наличии среды):

1. `checkout × workspace.suspend` — до новой записи заказа, с no-side-effect check.
2. `confirm × confirm` — одна reservation на line, повтор = no-op или документированный deterministic conflict.
3. `confirm × cancel` — не допускается reservation у terminal/cancelled order.
4. `fulfill × cancel` — не допускается двойное списание или неконсистентная state history.
5. `receive × reconciliation.inspect` — валидная commit-цепочка не выдаёт ложный mismatch.
6. `transfer(A→B) × transfer(B→A)` — отсутствие deadlock, сумма на двух позициях сохраняется.
7. `price change × checkout` — accepted snapshot точно равен одной из допустимых линеаризованных версий.
8. `role revoke × confirm/fulfill/price change` — commit либо строго до revoke, либо DENY после; невозможно запрещённое состояние.
9. `account.suspend × domain command` — тот же fail-closed fencing.
10. `reconciliation.advance × advance` — checkpoint/finding count сохраняются ровно один раз, retry безопасен.

Случайный `CountDownLatch` старт без фиксации SQL read/write boundaries — лишь **probabilistic smoke**, не доказательство обеих сериализаций. Для детерминированного уровня использовать transaction barriers, контролируемые lock holders и независимые JDBC sessions; не размещать test hooks в production path без явной opt-in test-only защиты.

## 8. Протокол диагностического запуска

**Идентификаторы:** `qualificationRunId`, `scenarioId`, `caseId`, `actorAlias`, `tenantAlias`, `requestCorrelationId`, `gitSha`; реальные actor UUID и PII не выводить в общий отчёт.

**Фазы:** `ARRANGE → AUTHENTICATE → AUTHORIZE → EXECUTE → ASSERT_RESPONSE → ASSERT_PERSISTENCE → ASSERT_INVARIANTS → CLEANUP`.

**Результаты:** `PASS`, `FAIL`, `SKIPPED_OPTIONAL`, `BLOCKED_REQUIRED`, `NOT_IMPLEMENTED`, `NOT_RUN`. Переход `NOT_IMPLEMENTED→PASS` возможен только после реализации входа и исполнения проверки. Каждый case хранит причину, ожидаемый/полученный result, снимки счётчиков/delta без PII и ссылки на test report/log.

Диагностика *не должна* записывать в доменные таблицы вне обычных команд (за исключением изолированной фазы подготовки fixtures и явно маркированной corruption-injection в test DB). Запрещены production test users, production callbacks и автоматическое отправление настоящих писем при квалификации.

## 9. Тестовая структура и проверяемость

Три раздельных доказательных уровня:

| Уровень | Среда | Что подтверждает | Закрывает текущий service gate? |
|---|---|---|---|
| L1 | JUnit unit tests и Spring/H2 service tests с доверенным тестовым субъектом | Доменная матрица permissions, валидации | Частично |
| L2 | JUnit/Spring Boot + реальный PostgreSQL, Java-сервисы, независимые JDBC transactions | SQL locks, CAS, transaction rollback, tenant isolation, service authorization | Да, совместно с L1 |
| L3 | **Вне объёма: отдельный проект Real User E2E** | Реальный внешний пользовательский путь | Нет; не реализуется этим ТЗ |

Для L2 проверять полномочия доверенного `VerifiedActor` непосредственно через сервисные фасады; криптографию JWT допускается проверять отдельно JUnit-тестами `OidcJwtConfiguration` без HTTP. L3 не входит в реализацию.

**CI artefacts:** `rm-user-role-scenario-matrix.json`, `rm-user-role-scenario-results.json`, JUnit XML, PostgreSQL concurrency logs, `rm-exact-sha-qualification-manifest.json`, migration fingerprints и checksums. Отчёты агрегируются для **одного** HEAD SHA; пропущенные обязательные suites = FAIL/HOLD. Отдельно зафиксировать результаты H2, PG, populated migration, RustFS, SALES, IAM/PII и внешнего IdP gate.

## 10. Машинный формат строки результата

```json
{
  "schema": "RM-QUAL-USER-R1/case-v1",
  "scenarioId": "BS-09",
  "caseId": "BS-09-DENY-MISSING-INVENTORY-RESERVE",
  "executionTier": "L2_POSTGRESQL",
  "actorAlias": "U5",
  "tenantAlias": "A",
  "membershipRole": "MEMBER",
  "rbacEnforced": true,
  "grants": ["SALES_ORDER_CONFIRM"],
  "operation": "SALES.CONFIRM",
  "expected": "DENY_AUTHZ",
  "actual": "DENY_AUTHZ",
  "sideEffects": {
    "salesOrderVersionDelta": 0,
    "reservationDelta": 0,
    "movementDelta": 0
  },
  "outcome": "PASS",
  "gitSha": "set-by-ci",
  "evidence": "set-by-runner"
}
```

В итоговом JSON обязательны реальные `gitSha`, `testCaseId`, `timestamp`, `durationMs`, `expectedServiceOutcome`, `actualServiceOutcome`, `decisionCode`, `assertionEvidence`. Поля `expectedHttp` и `actualHttp` в этой версии запрещены; нельзя автоматически генерировать `PASS` без исполнения теста.

## 11. Этапы реализации

| Этап | Содержимое | Gate |
|---|---|---|
| **QUAL-USER-R1.0** | Версионированный реестр actor/tenant/role/grant/case; mapping на существующие `DomainPermission` | 100% mandatory cases имеют owner, expected, доказуемый fixture |
| **R1.1** | L1/L2 матрица IAM, RBAC on/off, cross-tenant, blocked/revoke; негативные after-state assertions | Ноль неавторизованных side effects; H2 + PG GREEN |
| **R1.2** | L2 коммерческие цепочки BS-03..BS-13 с CAS, history, inventory consistency | Авторитетные состояния подтверждены; no fake history |
| **R1.3** | Детерминированные SQL-barrier concurrency + crash/retry, полный pre/post-state diff | Обе сериализации проверены, без необъяснённого flaky |
| **R1.4** | Расширенные JUnit business journeys: роли/tenant/grants, история заказа, повтор, rollback и права после revocation | H2+PG на одном SHA, без REST/HTTP |
| **R1.5** | QA-01B same SHA manifest, негативная service-level security qualification и maturity review | `SERVICE_QUALIFIED` для проверенных контуров; никакого автоматического L4 |

P0 тесты R1.0–R1.3 не следует откладывать до UI/production; L3 исключён из объёма и согласуется отдельно.

## 12. Приёмка

1. Все акторы U0–U12 имеют воспроизводимые fixtures и корректную tenant role/grant конфигурацию.
2. Для всех BS-01..BS-15 существует однозначный expected business outcome; пропуски маркируются `BLOCKED_REQUIRED`, а не `PASS`.
3. Для каждого ALLOW проверены конкретные authority row deltas, order snapshots, CAS/version, inventory invariants и read ownership.
4. Для каждого DENY подтверждено отсутствие бизнес side effects и невозможность межтенантского чтения/PII leak.
5. OWNER не получает скрытых wildcard полномочий; `TENANT_RBAC_MANAGE` остаётся non-delegable.
6. Два несовместимых grants не превращаются в право совершить комбинированную операцию.
7. `checkout × suspend`, `price × checkout`, `revoke × mutation` и `transfer × reconciliation` имеют доказательства обоих порядков на PostgreSQL.
8. Ошибки, закрытые HTTP маршруты, неработающий IdP и отсутствующий checkout transport не трактуются как успешный пользовательский сценарий.
9. Каждый обязательный test case и workflow подтверждены на одном SHA; манифест отвергает stale/partial evidence.
10. Успех JUnit/H2/PostgreSQL даёт только `SERVICE_QUALIFIED`; L4 (реальный пользовательский E2E), L5 и Production относятся к отдельным будущим воротам.

**Нормативное решение:** диагностический статус сервисов нельзя повышать на основании synthetic roles, статического списка endpoint или количества JUnit тестов. Показатель готовности считается из исполненных обязательных сценариев, а не из числа строк кода. **PRODUCTION HOLD** до отдельной security/operational admission.


---

## 13. R1.1 — уточнение способа тестирования (нормативно)

**Решение:** вся реализация в рамках RM-QUAL-USER-R1 выполняется **только через JUnit-тесты Java-сервисов**. REST controllers, HTTP request/response, MockMvc, browser, API client, реальный Keycloak/IdP и публичные checkout endpoints **не разрабатываются и не являются зависимостью** этой квалификации. Если старые разделы упоминают HTTP/L3, применять ограничение настоящего раздела. Уровень пользовательского E2E остаётся отдельным будущим ТЗ.

Сохраняем существующие \`AuthenticatedSalesCommands\`, \`AuthenticatedSalesQueries\`, \`AuthenticatedBuyerOrders\`, \`AuthenticatedInventoryCommands\`, \`AuthenticatedInventoryReconciliation\`, \`AuthenticatedInventoryReconciliationRuns\`, \`SellerOfferService\`, \`StorefrontOfferService\`, \`StorefrontManagementService\`, CATALOG, CRM, IAM и их сервисные контракты. Никаких дополнительных диагностических контроллеров и дублей бизнес-логики.

Три уровня **внутри текущей работы**:

| Уровень | Технология | Доказательство | Статус |
|---|---|---|---|
| UT | JUnit 5 + Mockito/AssertJ, без запуска Spring/БД, где возможно | Чистые разрешения, комбинации grants, валидаторы, mapping, реакция на exception | Обязателен для выделяемых pure-logic policy |
| IT-H2 | \`@SpringBootTest\`, H2, реальные application services, test-only trusted actors | Позитивные и негативные бизнес-операции, tenant isolation, no-op/CAS, persistence deltas | Обязательный smoke, но **не** доказательство PostgreSQL concurrency |
| IT-PG | \`@SpringBootTest\`, PostgreSQL, независимые подключения/транзакции | Реальная блокировка, rollback, dual-tenant, revocation, interleaving, миграции и инварианты | Обязательный P0 gate |

**Важно:** тесты с \`new OidcActorResolver(testDecoder,users)\` и синтетическими JWT являются квалификацией **сервисной границы доверенного субъекта**, но не доказывают реальную аутентификацию пользователя. JUnit с моками не подменяет PostgreSQL integration. Не называть набор unit-only тестов сквозным Real User E2E.

### 13.1. Фикстуры пользователей и ролей

Исполняемый реестр test profiles; каждый профиль включает actor alias, account status, tenant membership role/status, конфигурацию \`rbacEnforced\`, набор точных \`DomainPermission\` и области видимости объектов.

| Alias | Membership | Grants | Проверяемое поведение |
|---|---|---|---|
| U0 | отсутствует | нет | Null/untrusted principal отказ |
| U1 | покупатель, без seller membership | verified buyer identity | Чтение **своих** заказов, но не seller mutations |
| U2 | OWNER A | владелец управляет RBAC; прочие grants только явные | \`TENANT_RBAC_MANAGE\` остаётся неделегируемым |
| U3 | ADMIN A | SALES_ORDER_READ | Читает, не подтверждает заказ |
| U4 | MANAGER A | SALES_ORDER_CONFIRM + INVENTORY_RESERVE | Подтверждает заказ, но не меняет цену |
| U5 | MEMBER A | INVENTORY_RECEIVE + INVENTORY_STOCK_READ | Приход, сверка без корректировки |
| U6 | VIEWER A | INVENTORY_STOCK_READ | Читает склад, не проводит движения |
| U7 | глобальный ACTIVE, без A membership | любые внешние claims | Полный отказ в tenant командах |
| U8 | MEMBER B | grants только B | Нет доступа к A |
| U9 | MEMBER A и ADMIN B | независимые grants A/B | Проверка двух независимых tenant policy |
| U10 | SUSPENDED global account | ранее назначенные grants | Полный отказ новым операциям |
| U11 | ACTIVE, но role revoked/unassigned | stale actor context | Отказ после изменения RBAC в БД |
| U12 | INVITED/SUSPENDED/LEFT membership | ранее назначенные grants | Нет действующего членства |

**Роль не означает неявный доступ.** При \`rbacEnforced=true\` любое управляемое permission тестируется в вариантах grant present, grant absent, revoked. При \`rbacEnforced=false\` expected выводится из фактического \`TenantAuthorization.permitted\`, а не из условного «OWNER может всё». Если на тестовом fixture появилось \`TENANT_RBAC_MANAGE\` у ADMIN, операция всё равно обязана отказать.

### 13.2. Реестр разрешений как тестовые данные

Разместить реестры в \`src/test/resources/qualification/user-role/\`: \`actors.v1.yaml\`, \`operations.v1.yaml\`, \`scenario-cases.v1.yaml\`, \`side-effects.v1.yaml\`, \`interleavings.v1.yaml\`. Реестры **не используются в production для принятия решений**; источник права — действующий \`DomainPermission\` + \`TenantAuthorization\`.

Каждая запись операции содержит \`operationId\`, \`ownerService\`, \`javaMethod\`, \`requiredGrantsAllOf\`, \`objectTenantPredicate\`, \`prerequisites\`, \`businessEffect\`, \`negativeCases\`, \`testMethod\`, \`databaseTier\`, \`mandatory\`, \`implementationStatus\`. Регистратор должен проверять, что все указанные \`DomainPermission\` существуют и обязательные \`testMethod\` действительно исполнены; отсутствие теста → \`NOT_RUN\`, а не \`PASS\`.

Пример:

\`\`\`yaml
schema: RM-QUAL-USER-R1/operation-v1
operationId: SALES.CONFIRM
ownerService: AuthenticatedSalesCommands
javaMethod: confirm(VerifiedActor, UUID, UUID, UUID)
requiredGrantsAllOf:
  - SALES_ORDER_CONFIRM
  - INVENTORY_RESERVE
tenantPredicate: sales_order.tenant_id == tenantId
testLevel: IT_PG
mandatory: true
negativeCases:
  - MISSING_SALES_GRANT
  - MISSING_INVENTORY_GRANT
  - OTHER_TENANT
  - REVOKED_ROLE
  - SUSPENDED_ACTOR
expectedOnDeny:
  salesOrderVersionDelta: 0
  stockReservationDelta: 0
  stockMovementDelta: 0
\`\`\`

Набор грантов для других multi-permission операций формируется из действующих сервисных проверок, не придумывается по названию метода.

## 14. Матрица тестов бизнес-операций

Для каждой строки нужны **ALLOW + DENY_NO_GRANT + DENY_FOREIGN_TENANT + DENY_INACTIVE + REPLAY/CAS (если применимо)**. Фактический статус \`IMPLEMENTED\` определяется наличием Java-service метода, а \`QUALIFIED\` — исключительно тестовым доказательством.

| Код | Сервисная операция | Основное доказательство |
|---|---|---|
| IAM-01 | OWNER role create/grant/revoke/assign | Неделегируемость OWNER, re-admission после revoke |
| IAM-02 | Workspace/actor/membership suspend | Новые команды отвергаются и не меняют authority tables |
| CAT-01 | SKU create/publish/archive | Только свой tenant, blocked archive при зависимостях |
| STORE-01 | Storefront create/publish/pause | Permission, CAS, no-op, audit delta |
| STORE-02 | Offer create/publish/pause/hide | SKU tenant ownership, compatible facade semantics |
| STORE-03 | Price edit/history | 0 revisions при no-op/reject, ровно 1 при update, immutable snapshot |
| BUY-01 | Service-level checkout | One seller, immutable snapshot, idempotency, seller workspace ACTIVE |
| BUY-02 | Authenticated buyer history | Verified principal, own orders only, bounded pagination |
| SALES-01 | Seller order search/detail/events | \`SALES_ORDER_READ\`, no buyer PII, tenant predicates |
| SALES-02 | Confirm | \`SALES_ORDER_CONFIRM ∧ INVENTORY_RESERVE\`, active reservation |
| SALES-03 | Fulfill | \`SALES_ORDER_FULFILL ∧ INVENTORY_FULFILL\`, one posting |
| SALES-04 | Cancel | Seller permission + applicable inventory rollback, zero orphan reservations |
| INV-01 | Receive/correct/write-off | No oversell, idempotency, movement posting_version |
| INV-02 | Transfer | Two atomic legs, consistent balances, no deadlocks |
| INV-03 | Reconciliation inspect/advance | No false mismatches from legal concurrent writes, legacy UNVERIFIED |
| CRM-01 | Read/write/PII | Separate CRM_READ/WRITE/PII_READ and data classification |
| ORG-01 | Warehouse/location read/write | Scope by tenant, no cross-tenant FK |
| FILE-01 | Attach/read/delete | Permission + ownership, no PII leak, no external RustFS requirement for unit suite |

Неподдерживаемую операцию маркировать \`NOT_IMPLEMENTED\` и исключать из **numerator**, но не из mandatory **denominator**.

## 15. Достоверная диагностика изменений и отказов

Тест считается \`PASS\` только если подтверждены **три независимые составляющие**: (1) доменный результат/исключение, (2) ожидаемое изменение авторитетных таблиц, (3) сохранение критических бизнес-инвариантов.

Пример \`SALES.CONFIRM\`: даже если сервис выбросил \`SecurityException\`, assert-before/after сравнивает \`crm.sales_order\`, \`crm.sales_order_status_event\`, \`crm.inv_stock_reservation\`, \`crm.inv_stock_balance\`, \`crm.inv_stock_movement\` и релевантные receipt/outbox/audit. Разрешено событие *диагностики отказа* вне business mutation; нельзя создавать success audit/event/quantity movement при DENY.

Нормализованные ожидания:
- \`ALLOW\`: точные \`status\`, версии CAS, количества строк, quantity equation и immutable snapshots.
- \`DENY_AUTHZ\`: \`SecurityException\`, нулевые запрещённые domain deltas.
- \`DENY_OBJECT\`: \`not found in tenant\` или эквивалентный masked domain outcome без раскрытия чужих данных.
- \`CONFLICT\`: понятное состояние версии/заказа и нулевой side effect.
- \`REPLAY\`: не создаёт вторую запись, возвращает детерминированный receipt/решение.
- \`INVALID_ARGUMENT\`: вход отвергнут без записи.
- \`BLOCKED_REQUIRED\`, \`NOT_IMPLEMENTED\`, \`NOT_RUN\`: **не** являются успешными тестами.

Проверять данные после commit/rollback новой транзакцией/connection, чтобы test-level \`@Transactional\` и JPA persistence context не скрывали ошибки commit. Все негативные тесты сравнивают **последовательные read-only snapshots** релевантных данных одного fixture, а не только общие \`count(*)\` по всей базе.

### 15.1. Конкурентные доказательства PostgreSQL

JUnit IT-PG должен исполнять оба порядка для \`checkout × suspend\`, \`confirm × revoke\`, \`fulfill × cancel\`, \`price CAS × checkout\`, \`transfer × reconciliation\`, \`advance × advance\`. Управление порядком: JDBC row locks / barriers на отдельных connection и \`TransactionTemplate\`, не \`Thread.sleep\` как доказательство.

Для каждого сценария сохранять две независимые проверки: **serializable business outcome** и **transactionally correct persistence**. Повтор после rollback/crash не дублирует receipt/movement/event; для \`REPEATABLE_READ\` возможный PostgreSQL \`40001\` квалифицируется как отдельный retry outcome, не как повреждение ledger.

## 16. Отчёты по функциональному покрытию и готовности

**Два процента, которые запрещено смешивать:**

1. \`implementationCoverage = implementedMandatoryCases / totalMandatoryCases * 100\` — у операции существует вызываемый контракт и подготовлен тест.
2. \`verifiedCoverage = passedMandatoryCases / totalMandatoryCases * 100\` — тест реально исполнен и все его утверждения прошли на требуемой СУБД и SHA.

\`NOT_IMPLEMENTED\`, \`NOT_RUN\`, \`BLOCKED_REQUIRED\`, \`FAILED\` учитываются в знаменателе и **не** увеличивают \`verifiedCoverage\`. Дополнительно отчитывать \`negativeCoverage\`, \`crossTenantCoverage\`, \`concurrencyCoverage\`, \`rolesCovered\` и \`operationsCovered\` (каждый с собственным denominator). Не выводить «готово 95%» только из числа тестов JUnit без мэппинга на сценарии.

Машинный файл \`target/qualification/rm-user-role-matrix.json\`:

\`\`\`json
{
  "schema": "RM-QUAL-USER-R1/v2",
  "level": "JAVA_SERVICE_UNIT_AND_INTEGRATION",
  "repository": "alimbetov/RM",
  "branch": "base_dev",
  "commitSha": "injected-by-ci",
  "totals": {
    "mandatory": 0,
    "implemented": 0,
    "passed": 0,
    "failed": 0,
    "blocked": 0,
    "notRun": 0
  },
  "coverage": {
    "implementationPercent": null,
    "verifiedPercent": null
  },
  "cases": [],
  "gate": "HOLD"
}
\`\`\`

Поля \`0\` и \`null\` выше — **образец формата**, не фактическая статистика. \`gate=SERVICE_QUALIFIED\` допустим только если \`mandatory>0\`, \`passed==mandatory\`, нет пропусков, проверены H2 и PostgreSQL по обязательной матрице и SHA совпадает с RM-QA-01B. Генератор никогда не выставляет PASS по декларации реестра.

## 17. Выполнение и критерии приёмки

| Релиз | Содержание | Обязательное подтверждение |
|---|---|---|
| QUAL-USER-R1.0 | Реестр ролей, разрешений и 18 операций; автоматическая проверка completeness | Ни одной незарегистрированной mandatory операции |
| R1.1 | UT+IT-H2: role/grant/tenant/subject матрица, side-effect assertions | Все P0 cases исполняются; неверные права не меняют DB |
| R1.2 | IT-PG: SQL/JDBC real transactions, role revocation, CAS, owner/read, dual-tenant | Все P0 PostgreSQL cases PASS |
| R1.3 | IT-PG: двухпорядковые concurrency + crash/retry + long movement ledger | Отдельные воспроизводимые тесты на каждый order |
| R1.4 | Сквозная **сервисная** цепочка CATALOG → STOREFRONT → CHECKOUT → SALES → INVENTORY → RECONCILIATION, пользовательские роли меняются между шагами | Доказанные authority state transitions и отсутствие forbidden side effects |
| R1.5 | Пооперационный coverage report + QA-01B exact-SHA gate и отчёт зрелости | \`SERVICE_QUALIFIED\` для реально проверенных кейсов; **не** Level 4 |

**Не входить** в работу с REST controllers, OAuth login-flow, MockMvc, браузером, frontend или email transport. Даже если в репозитории уже есть HTTP-код, он не используется и не расширяется данным ТЗ.

**Production HOLD** остаётся независимо от результатов JUnit. L4 Real User E2E и production admission потребуют отдельной будущей спецификации и пользовательского транспорта.


---

## 18. R1.2 — интеграция RM-STRATEGY-R3 и RM-QA-01B (нормативное расширение)

**Основание:** \`docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json\`, \`RM-R3-FUNCTIONAL-COVERAGE-R1.md\`, \`.github/scripts/verify_rm_qa_01b.py\`, \`.github/workflows/rm-exact-sha-admission.yml\`.

**Решение по объёму:** это **JUnit-only** диагностика Java application/service contracts, H2 и реального PostgreSQL. Никаких дополнительных REST controllers, MockMvc, browser, внешнего OIDC/Keycloak или новых бизнес-сервисов. Даже если HTTP endpoints уже существуют, тесты этой квалификации должны вызывать доверенные сервисные фасады и проверять авторитетные таблицы. Реальный пользовательский E2E остаётся отдельным \`RM-E2E-04\`, не условием запуска R1.2.

### 18.1. Базовая матрица R3-v1 — неизменяемая точка сравнения

На исходной редакции реестра: **16 контуров, 75 требований, 46 IMPLEMENTED, 29 PLANNED; 41 реализованное требование снабжено индивидуальной proof-привязкой**. Это свойства текущего каталога, **не автоматическая констатация PASS на HEAD документа**. Подтверждённость устанавливается только после успешного exact-SHA \`RM-QA-01B\` и реального PostgreSQL Surefire-доказательства. Базовые доли: \`46/75=61,33%\` с реализацией; \`41/75=54,67%\` с индивидуальной PostgreSQL proof-привязкой при успешном gate; \`29/75=38,67%\` не реализовано. Отчёт \`857 executions / 743 passed / 114 skipped / 0 failed\` относится к переданной исторической QA-квалификации, включает дублируемые тестовые исполнения и **не используется как число уникальных сценариев и не наследуется новым SHA**.

| Контур / R3 ID prefix | Всего | Реализовано | Proof в R3-v1 | Запланировано | Диагностический фокус |
|---|---:|---:|---:|---:|---|
| IAM / RBAC (\`IAM-\`) | 6 | 5 | 5 | 1 | identity, membership, роль, grant/revoke, command fencing |
| CRM Core (\`CRM-\`) | 6 | 5 | 5 | 1 | ownership, read/write, PII, consent |
| CRM Interaction (\`INT-\`) | 4 | 2 | 2 | 2 | tenant-scoped interaction lifecycle |
| CATALOG (\`CAT-\`) | 4 | 3 | 3 | 1 | SKU, публикация, archiving constraints |
| STOREFRONT (\`STO-\`) | 5 | 4 | 4 | 1 | фасады, CAS, цена и история |
| SALES / AutoOrder (\`SAL-\`) | 7 | 5 | 5 | 2 | buyer/seller, snapshot, reserve, fulfill |
| INVENTORY (\`INV-\`) | 7 | 6 | 5 | 1 | stock/ledger, reservations, reconciliation |
| FILES (\`FIL-\`) | 6 | 5 | 4 | 1 | tenant-scoped file rights and storage invariants |
| ORGANIZATION (\`ORG-\`) | 5 | 4 | 3 | 1 | membership/location access |
| GEO (\`GEO-\`) | 3 | 2 | 2 | 1 | reference data and protected modifications |
| KMS (\`KMS-\`) | 4 | 3 | 1 | 1 | envelope, guard, key operation rights |
| AUDIT (\`AUD-\`) | 4 | 2 | 2 | 2 | protected evidence, immutability, retention |
| TASKS (\`TSK-\`) | 4 | 0 | 0 | 4 | future planned contract, \`NOT_IMPLEMENTED\` |
| FINANCE (\`FIN-\`) | 4 | 0 | 0 | 4 | future planned contract, \`NOT_IMPLEMENTED\` |
| MARKETING (\`MKT-\`) | 3 | 0 | 0 | 3 | future planned contract, \`NOT_IMPLEMENTED\` |
| SUBSCRIPTION (\`SUB-\`) | 3 | 0 | 0 | 3 | future planned contract, \`NOT_IMPLEMENTED\` |
| **ИТОГО** | **75** | **46** | **41** | **29** | **Реестр не является полным перечнем CRM/ERP** |

**Scope invariant:** новые пользовательские истории, отсутствующие в 75 требованиях, получают \`strategyMappingStatus=UNMAPPED_CANDIDATE\`; их нельзя просто не учитывать. После согласования расширяют **версию каталога** отдельным reviewed change, а не изменяют знаменатель задним числом. Исторические проценты сравнимы **только при одинаковых catalogue ID/version/denominator**.

### 18.2. Уникальные идентификаторы и двунаправленная трассировка

Существующие \`IAM-01\`, \`INV-02\` и аналогичные ID в R3 — **идентификаторы функциональных требований**. Локальные коды тестовой матрицы из §14 не должны конфликтовать с ними: код операции стандартизировать как \`OP:IAM.ROLE_GRANT\`, \`OP:SALES.CONFIRM\`, \`OP:INVENTORY.RESERVE\` и т.п.; ID диагностического случая — \`UR-<DOMAIN>-<SEQ>-<VARIANT>\`. Старыe \`BS-01..BS-15\` остаются ID **историй**, а не требований. Ни один случай не выдаёт себя за требование \`RM-R3\`.

Для каждой строки реестра R3 должны быть указаны или явно отсутствовать:

\`requirementId → capabilityTitle → domainOwner → delivery → serviceContract → requiredPermissionsAllOf → objectOwnership → actorProfiles → businessStoryIds → unitCaseIds → h2CaseIds → postgresCaseIds → negativeCaseIds → concurrencyCaseIds → R3Proof → exactShaRun → verdict\`.

Связь много-ко-многим допускается между требованиями и историями/кейсами; **старый R3-v1 proof по-прежнему один уникальный \`artifact/suite/test\` на реализованное требование** согласно существующему валидатору. Дополнительные regression tests, запреты и гонки записываются в отдельный расширенный реестр и **не подменяют** каноническую proof-привязку R3. Нельзя автоматически приписать proof по совпадению префикса, имени Java-класса или факту запуска suite.

\`R3 proof verified\` и \`role scenario verified\` — два самостоятельных измерения. Положительный single-case \`INV-02\` не доказывает отсутствие утечки данных по всем ролям. У каждого обязательного role-сценария есть собственные assertions, а агрегатор ссылается на **конкретные** Surefire testcases и digest артефакта.

### 18.3. Матрица обязательных измерений для каждого реализованного требования

Обязательные измерения не сводятся к декартову произведению всех пользователей на все операции; применяется **risk-based minimal complete matrix**, с зафиксированным генератором и обоснованным сокращением:

| Измерение | Обязательные варианты |
|---|---|
| Actor status | ACTIVE, SUSPENDED, unknown, null trusted actor |
| Membership | OWNER, ADMIN, MANAGER, MEMBER, VIEWER; отсутствует / INVITED / SUSPENDED / LEFT — где применимо |
| RBAC | enforced=false fallback, enforced=true grant, enforced=true missing grant, revoke/unassign |
| Object ownership | свой tenant A, чужой tenant B, actor A+B с разными правами, неизвестный objectId |
| Permission conjunction | все нужные grants; нет каждого обязательного grant по отдельности |
| Data confidentiality | публичные поля, секретные PII, ссылки на чужие объекты, отсутствие секретов в exceptions |
| Mutable operations | valid CAS, stale CAS, no-op, retry, rejected operation and persisted zero delta |
| Monetary/stock history | immutable accepted price, version, posting order, reservation balance, evidence retention |
| Concurrency P0 | оба линеаризованных порядка + rollback/retry на отдельных PostgreSQL connections |
| Fault | failed DB statement, transaction rollback, missing dependency, restart where in scope |

**Минимальная обязательность:** каждый реально исполняемый domain command должен иметь один ALLOW, missing-grant DENY, foreign-tenant DENY и inactive-user DENY; для заявленных sensitive/multi-grant/concurrent операций обязательны соответствующие дополнительные варианты. Для \`PLANNED\` отсутствующий сервис отмечается \`NOT_IMPLEMENTED\`, не имитируется искусственным \`SecurityException\`. Будущий код из TASKS/FINANCE/MARKETING/SUBSCRIPTION не может быть объявлен реализованным от наличия схемы или enum.

### 18.4. Пять не квалифицированных индивидуально требований

Первые приоритеты для **доказательного** связывания, а не механического повышения процентов:

| R3 ID | Требование | Необходимое самостоятельное доказательство |
|---|---|---|
| \`INV-02\` | Резервы/освобождение запасов | reserve/release, двойной retry, concurrency, quantity invariants, owner/grant deny, zero unauthorized delta |
| \`FIL-05\` | RustFS и проверка файлов | результат live RustFS + antivirus E2E в *подходящем обязательном artifact*; разделить тест с fake adapter и real infrastructure |
| \`ORG-04\` | Поиск/чтение локаций склада | bounded search/list, tenant A/B object isolation, no-grant, SUSPENDED tenant и negative no data leak |
| \`KMS-01\` | Envelope encryption + key wrapping | реальные crypto round-trip, key context separation, wrong-key rejection, отсутствие plaintext и grant bypass |
| \`KMS-03\` | Защита конфигурации крипто-инфраструктуры | fail-fast on invalid/missing config, запрещённый downgrade, подтверждение guard при старте; не путать unit config check с production HSM |

**Правило:** выделить независимые \`@Test\` методы/уточнить существующие только если они действительно покрывают требование, проверить реальные PostgreSQL test executions и назначить неповторяющуюся каноническую proof. Если подходящего доказательства нет, R3 status остаётся \`IMPLEMENTED_WITHOUT_VERIFIED_PROOF\`; нельзя повысить процент по одному имени тестового класса. Для RustFS не выдавать JUnit/H2 unit adapter за реальную infra-квалификацию, и не менять политику валидатора скрыто: если нужна поддержка infra-proof, сначала утвердить и реализовать **явный отдельный тип доказательства** с SHA и artifact digest.

### 18.5. Совместимость с RM-QA-01B exact-SHA evidence

\`RM-QA-01B\` остаётся **верхнеуровневым gate**. Входы нового JUnit role-qualification дополнительно, не взамен пяти уже обязательных workflow:

- \`rm-r3-functional-coverage.json\` + \`rm-r3-functional-coverage.md\` (75 capabilities, 16 assessment rows);
- \`rm-exact-sha-manifest.json\` с \`qa01bGate\`, workflow SHA, именованными обязательными артефактами, Surefire XML, skip reasons, SHA-256 evidence hashes и Liquibase fingerprint;
- \`rm-user-role-scenario-results.json\` (только реальные JUnit результаты по \`UR-...\` кейсам);
- \`rm-user-role-coverage.json\` (раздельное actor, role, permission, tenant, negative, concurrency coverage).

**Требуемая проверка:** \`qa01bGate=PASS\` И все обязательные user-role cases исполнены со статусом PASS на том же commit SHA/каталоге/миграциях. Глобальный QA-01B PASS **не** означает \`USER_ROLE_SERVICE_QUALIFIED\`, если новая ролевая матрица ещё не интегрирована в обязательный CI. Сначала включить проверку новых тестов/артефактов как обязательный шаг workflow, затем принимать новый gate; запуск тестового класса отдельно не считается admission.

Обязательные идентификаторы evidence: \`catalogueSchema\`, \`catalogueSha256\`, \`gitSha\`, \`migrationFingerprint\`, \`artifactName\`, \`artifactSha256\`, \`junitSuite\`, \`junitTest\`, \`database\`, \`testStatus\`, \`skipReason\`, \`assertionCategory\`, \`actorAlias\`, \`tenantAlias\`, \`scenarioId\`, \`requirementIds\`, \`runUrl\`. Не писать персональные данные, реальные токены и шифровальные ключи в evidence.

При изменении каталога, test names или suite mapping должен выполняться explicit validation: unknown R3 ID, duplicate canonical proof, missing suite, skipped PG test, mixed commit, changed migration set, incorrect catalogue hash → **FAIL/HOLD**, не silent fallback. Новый документ может ссылаться на исторический успешный SHA, но текущие показатели квалификации — только из фактического текущего HEAD.

### 18.6. Правила процентов и запрет накрутки покрытия

Сохраняются показатели R3-v1:

- \`r3ImplementationPct = implementedR3 / totalR3 × 100\`;
- \`r3VerifiedPct = admittedR3Proofs / totalR3 × 100\` только при \`qa01bGate=PASS\`;
- \`r3MissingPct = plannedR3 / totalR3 × 100\`.

Параллельно, **отдельно**:

- \`roleCaseVerifiedPct = passedMandatoryRoleCases / totalMandatoryRoleCases × 100\`;
- \`rolePermissionCoverage = exercisedPermissionVariants / requiredPermissionVariants × 100\`;
- \`roleNegativeCoverage = passedDenyCasesWithZeroForbiddenDelta / mandatoryDenyCases × 100\`;
- \`businessJourneyCoverage = completePassedServiceJourneys / mandatoryServiceJourneys × 100\`.

Ни один тест не засчитывается дважды в **уникальное количество кейсов** из-за повторного запуска H2/PostgreSQL, но database-specific executions учитываются в собственных отчётах. \`857 executions\` не является знаменателем функциональной готовности. \`NOT_RUN\`, \`BLOCKED_REQUIRED\`, \`SKIPPED\`, \`NOT_IMPLEMENTED\`, \`FAILED\` всегда остаются в обязательном знаменателе.

Показывать три явно разных вывода: **(A) RM-R3 capability coverage**, **(B) RM-QUAL-USER role/business case coverage**, **(C) release admission**. Отчёты A и B не означают (C) и не заменяют S2/security или production approval.

### 18.7. Пошаговая реализация без REST

| Wave | Реализация | Приёмка |
|---|---|---|
| \`UR-R3.0\` | Ревизия 75 R3 ID; crosswalk 16 domains → 18 групп операций/15 историй; versioned actor/role/case catalogue | Каждый R3 ID размечен как \`MAPPED\`/\`PLANNED\`/\`UNMAPPED\`, дубликаты ID запрещены |
| \`UR-R3.1\` | JUnit unit + Spring/H2 service tests для allOf grants, membership states, RBAC on/off, A/B/AB | Отказ без запрещённых side effects, coverage denominator фиксирован |
| \`UR-R3.2\` | Реальный PostgreSQL JUnit integration по seller/buyer, price CAS, reserve/fulfill, CRM/FILES/ORG/KMS | Positive/negative persisted assertions и schema constraints |
| \`UR-R3.3\` | Named PostgreSQL concurrency: revoke × command, checkout × suspend, transfer × reconciliation, retry/rollback | Два детерминированных порядка и source-of-truth invariants |
| \`UR-R3.4\` | Разбор 5 unmapped proofs, привязка конкретных подтверждённых JUnit methods | У каждого из 5 либо доказанный unique proof, либо честный HOLD |
| \`UR-R3.5\` | Итоговый crosswalk, новые JSON/Markdown артефакты, интеграция с точным QA-01B SHA | \`qa01bGate=PASS\` + \`USER_ROLE_SERVICE_QUALIFIED\`, Production HOLD |

**Не расширять** 75 требований до «полного CRM/ERP» автоматически. Полный каталог пользовательских возможностей RM-STRATEGY-R3 — отдельная ревизия с владельцами, приоритетами, функциональными acceptance cases и согласованным изменением знаменателя.

### 18.8. Definition of Done этого расширения

1. Все 75 R3 requirements представлены в crosswalk, включая все 29 \`PLANNED\`; 16 контуров не пропадают из итогового отчёта.
2. Каждый существующий \`IMPLEMENTED\` имеет source и доказанный primary proof либо помечен как proof gap; доказательства прав/грантов дополнительно связаны с отдельными JUnit role cases.
3. Для каждого P0 command есть actor/membership/grant/cross-tenant/denied-side-effect qualification.
4. Для каждого P0 race имеются контролируемые обе сериализации на реальном PostgreSQL, не случайный \`CountDownLatch\` smoke.
5. В отчёте имеются три раздельных измерения: R3 capability, service role/scenario, QA/release.
6. При любом недостающем обязательном test/case, skipped PostgreSQL, несовпадающем SHA или неверном checksum gate выдаёт FAIL/HOLD.
7. Документ и каталоги хранятся в \`base_dev\`; новые ветки и merge в \`main\` не требуются для разработки.
8. **Реальные HTTP, OAuth/OIDC login, браузер, email delivery, frontend, tenant-wide certified reconciliation и production admission не включены в данный объём.**

**Нормативный итог:** завершённый \`RM-QA-01B\` удостоверяет технические evidence на квалифицированном SHA; \`RM-QUAL-USER\` доказывает **правильные бизнес-эффекты для разных ролей и tenants**, а \`RM-STRATEGY-R3\` измеряет **долю пользовательских возможностей из явно перечисленного каталога**. Эти три утверждения запрещено взаимно подменять. **Production HOLD** сохраняется.
