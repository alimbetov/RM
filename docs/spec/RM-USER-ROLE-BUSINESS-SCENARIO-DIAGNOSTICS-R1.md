# RM-QUAL-USER-R1 — Диагностика бизнес-сценариев по пользователям, ролям и правам

**Дата:** 2026-11-22  
**Репозиторий:** `alimbetov/RM`; **ветка разработки:** `base_dev`  
**Статус:** SPECIFIED / JUNIT SERVICE-LEVEL QUALIFICATION ONLY / IMPLEMENTATION PENDING / PRODUCTION HOLD  
**Приоритет:** P0 (авторизация, изоляция, доказательство отказов), P1 (сквозные бизнес-сценарии на уровне Java-сервисов)  
**Связи:** RM-SEC-09A; RM-STORE-09B; RM-INV-16; RM-QA-01B; RM-SALES-10A; RM-STORE-10A; RM-E2E-04; RM-R2.1.

## 1. Цель

Создать **воспроизводимую диагностическую систему бизнес-сценариев RM**, проверяющую реальное поведение системы для разных пользователей, организационных ролей, назначенных tenant-grants, статусов аккаунтов и разных организаций. Предмет диагностики — путь от аутентификации до итоговых изменений в авторитетных таблицах, а не только единичный ответ HTTP 200/403.

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
- **Действующая HTTP-граница W5:** opt-in `rm.security.oidc.enabled`; защищённые buyer history, seller detail/status history, seller confirm/fulfill. **Полный публичный checkout и реальный external-issuer E2E ещё не считаются квалифицированными.**
- **Reconciliation:** per-transaction `REPEATABLE_READ`, `posting_version` новых проводок и `UNVERIFIED_MOVEMENT_ORDER` старых. `COMPLETED` диагностического run **не означает** единого сертифицированного tenant-wide snapshot.

Объектные источники истины: IAM → субъект и права; CATALOG → SKU; STOREFRONT → текущая карточка и цена; SALES → принятая цена/позиции/заказ/история статусов; INVENTORY → складские позиции/резервы/проводки; AUDIT → доказательные события.

## 3. Модель участника теста

Каждый сценарий определяется как кортеж:

`(subject, authentication, accountState, tenant, workspaceState, membershipRole, membershipState, rbacEnforced, grantedPermissions, targetObjectOwner, operation, inputs, concurrencyOrder, expectedDecision, expectedStateDelta)`.

Для каждого запуска формировать независимые записи:

| Субъект | Смысл | Условие |
|---|---|---|
| U0 | Анонимный посетитель | Нет bearer token |
| U1 | Покупатель | Подтверждённый OIDC + email, нет seller membership |
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
| BS-01 | OIDC login/subject mapping | ACTIVE subject маппится на единственный RM user | Отсутствующий, изменённый, expired, invalid iss/aud, unknown `sub`, blocked |
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

Для BS-07 **до реализации безопасного checkout transport** допускается отдельный `SERVICE_ONLY` уровень, который не удовлетворяет W5 admission. Аналогично `EXTERNAL_IDP_REQUIRED` и `EMAIL_PROVIDER_REQUIRED` не подменяются синтетическими fixtures.

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

| Уровень | Среда | Что подтверждает | Может удовлетворить W5? |
|---|---|---|---|
| L1 | JUnit/H2, synthetic VerifiedActor | Доменная матрица permissions, валидации | Нет |
| L2 | Реальный PostgreSQL + подписанные JWT + Spring Security MockMvc/HTTP | SQL locks, CAS, transaction rollback, tenant isolation, HTTP boundary | Нет, без внешнего IdP |
| L3 | Deployed RM + реальный тестовый OIDC issuer/JWKS + PostgreSQL + поддержанный checkout transport + sandbox email | Полная authenticated buyer/seller business journey | Да, при всех обязательных PASS |

Для L2 проверять реальные issuer/audience/signature/expiry с локальной тестовой подписью; mock decoder без криптографической проверки считать только L1/transport smoke. Для L3 использовать отдельный IdP staging realm и настоящую выдачу access token, а не вручную созданный `VerifiedActor`.

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

В итоговом JSON обязательны реальные `gitSha`, полные `testCaseId`, `timestamp`, `durationMs`, `expectedHttp`, `actualHttp`, `decisionCode`, `assertionEvidence`; запрещено автоматически генерировать `PASS` без исполнения теста.

## 11. Этапы реализации

| Этап | Содержимое | Gate |
|---|---|---|
| **QUAL-USER-R1.0** | Версионированный реестр actor/tenant/role/grant/case; mapping на существующие `DomainPermission` | 100% mandatory cases имеют owner, expected, доказуемый fixture |
| **R1.1** | L1/L2 матрица IAM, RBAC on/off, cross-tenant, blocked/revoke; негативные after-state assertions | Ноль неавторизованных side effects; H2 + PG GREEN |
| **R1.2** | L2 коммерческие цепочки BS-03..BS-13 с CAS, history, inventory consistency | Авторитетные состояния подтверждены; no fake history |
| **R1.3** | Детерминированные SQL-barrier concurrency + crash/retry, полный pre/post-state diff | Обе сериализации проверены, без необъяснённого flaky |
| **R1.4** | Расширенные JUnit business journeys: роли/tenant/grants, история заказа, повтор, rollback и права после revocation | H2+PG на одном SHA, без REST/HTTP |
| **R1.5** | QA-01B same SHA manifest, негативная service-level security qualification и maturity review | `SERVICE_QUALIFIED` для проверенных контуров; никакого автоматического L4 |

P0 тесты R1.0–R1.3 не следует откладывать до UI/production; L3 начинается только после закрытия P0.

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
