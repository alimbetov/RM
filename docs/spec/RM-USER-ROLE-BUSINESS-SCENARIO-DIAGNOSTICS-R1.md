# RM-QUAL-USER-R1 — Диагностика бизнес-сценариев по пользователям, ролям и правам

**Дата:** 2026-11-22  
**Репозиторий:** `alimbetov/RM`; **ветка разработки:** `base_dev`  
**Статус:** SPECIFIED / IMPLEMENTATION AND ACCEPTANCE PENDING / PRODUCTION HOLD  
**Приоритет:** P0 (авторизация, изоляция, доказательство отказов), P1 (сквозной пользовательский UX/E2E)  
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
- **DENY_AUTHN:** отсутствие токена/неверная подпись/issuer/audience/expiry → HTTP 401 (когда HTTP реализован), без вызова бизнес-команды.
- **DENY_AUTHZ:** валидный субъект, но нет membership/grant/tenant ACTIVE → HTTP 403, без запрещённого изменения.
- **NOT_FOUND_MASKED:** чужой объект внутри доступного tenant — одинаковое внешнее поведение с отсутствующим объектом; запрещено подтверждать существование чужого заказа.
- **CAS_CONFLICT:** HTTP 409 или эквивалентный типизированный доменный outcome, версии и история неизменны.
- **INVALID_INPUT:** HTTP 400, нет side effects.
- **IDEMPOTENT_REPLAY:** идентичный receipt/state, новых business rows = 0.
- **NOT_IMPLEMENTED:** сценарий не проходит production gate; никогда не интерпретировать HTTP 404 отсутствующего маршрута как успешный security test.
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
| **R1.4** | L3 staging OIDC + поддержанный checkout/buyer verification/adapters + sandbox email | Реальные JWT и внешний пользовательский journey PASS |
| **R1.5** | QA-01B same SHA manifest, негативная security qualification и финальный maturity review по сервисам | Итоговый machine-readable `QUALIFIED`, только затем кандидат L4 |

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
10. L4 присваивается только при полном L3-проходе с настоящим тестовым IdP и поддержанным транспортом; L5 и Production остаются отдельными воротами.

**Нормативное решение:** диагностический статус сервисов нельзя повышать на основании synthetic roles, статического списка endpoint или количества JUnit тестов. Показатель готовности считается из исполненных обязательных сценариев, а не из числа строк кода. **PRODUCTION HOLD** до отдельной security/operational admission.
