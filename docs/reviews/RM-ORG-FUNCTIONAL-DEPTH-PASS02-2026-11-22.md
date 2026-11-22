# RM — ORGANIZATION: предметное обследование бизнес-операций, проход 02/12

**Документ:** RM-ORG-FUNC-R1-P02. **Ветка:** `base_dev`. **Source review base:** `2b4afbbabc6be0193def86a933d7947163f964ae`, 22.11.2026 (коды доменов не изменены с `c776baec5c82e0dfd4c7930aa6a68629202fa5fd`).
**Результат:** REVIEW / SPEC ONLY — не реализация, не запуск новых тестов. Feature Freeze, без контроллеров, production HOLD.
**Основное ТЗ:** [RM-ORG-FUNC-R1 — 12 проходов](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md).

## 1. Методика: бизнес-путь → операции → авторитетный домен → фактическая реализация

Проанализированы три рабочих пути: сотрудник склада, менеджер филиала, сотрудник торговой точки/продавец; а также сквозные предусловия создания tenant. Проверены код фасадов и реализация `OrganizationLocation*`, `PlatformTenantBootstrapService`, `TenantAuthorization`, `KatoLocationPolicy`, `AuthenticatedInventoryCommands`, `InventoryAuthorizedCommandService`, `InventoryMovementService`, `InventoryReservationService`, `OrderInventoryPort`, `SalesInventoryCommandService`, `AutoOrderService`, DDL и существующие qualification tests.

Обозначения: **READY** — есть прикладной код для указанного шага, но не production approval; **PARTIAL** — есть часть функциональности; **MISSING** — нет предметной операции; **OUT OF SCOPE** — владелец другой домен или функция ещё не утверждена.

### 1.1 Предусловие: новая организация и роли

| Шаг | Реальный метод | Статус | Вывод |
|---|---|---|---|
| Создать юридическую организацию и workspace | `PlatformTenantBootstrapService.provision` | READY (IAM) | Администратор платформы создаёт tenant и OWNER; не функция ORGANIZATION Location |
| Выдать права на локации | `TenantRoleService`, `VerifiedTenantRoleCommands` | READY (IAM), обязателен настроенный процесс | Новый workspace получает `iam_tenant_rbac_config.enforced=TRUE` без бизнес-grants; OWNER не получает wildcard |
| Создать локацию | `AuthenticatedOrganizationLocations.create` → `OrganizationLocationService.create` | READY | Требуется рабочий `ORGANIZATION_LOCATION_WRITE` в выбранном tenant |
| Прочитать список | `AuthenticatedOrganizationLocations.get/search` | READY | Требуется `ORGANIZATION_LOCATION_READ`; оба права назначаются независимо |

**Важный нюанс:** `TenantRoleService.assign` запрещает self-assignment; у нового tenant нельзя считать, что OWNER автоматически может создать первый склад. Процесс назначения сотруднику/owner бизнес-права должен быть проверен в IAM, не обходом в ORG. Этот пункт — внешний блокирующий prerequisite для полного реального E2E.

## 2. Путь BJ-01 — создание и работа склада

| № | Действие сотрудника | Реальный вызов и persistence | Сегодня | Целевой результат и владелец |
|---|---|---|---|---|
| 1 | Найти регион | `KatoTerritoryDirectory.eligibleForOrganizationLocation` | READY | GEO даёт список КАТО; ORG хранит только код |
| 2 | Создать склад | `AuthenticatedOrganizationLocations.create(…,WAREHOUSE,…)` → `org_location` | READY | ACTIVE, scoped tenant/READ, audit CREATE — доработать аудит |
| 3 | Открыть карточку / выбрать склад | `get/search`; фильтр type/status | PARTIAL | Отдельный ограниченный операционный выбор только ACTIVE WAREHOUSE |
| 4 | Создать нулевую SKU-позицию склада | Нет authenticated inventory create/initialize operation; тесты делают `INSERT inv_stock_balance` напрямую | MISSING | **INVENTORY** реализует безопасную идемпотентную initialize-if-absent с tenant+item+location и нулевым балансом; ORG не пишет stock |
| 5 | Принять товар | `AuthenticatedInventoryCommands.receive` → `InventoryMovementService.adjust` | PARTIAL | Сейчас требует предварительно существующую позицию; не проверяет ACTIVE/WAREHOUSE. Добавить ORG eligibility guard внутри INVENTORY |
| 6 | Проверить доступный остаток | `AuthenticatedInventoryQueries.search` / `StockBalanceQueryService` | READY | Отдельное право INVENTORY_STOCK_READ; ORG не возвращает количество без него |
| 7 | Принять заказ к исполнению | `AuthenticatedSalesCommands.confirm` → `SalesInventoryCommandService.confirm` → `OrderInventoryPort.reserve` | PARTIAL | Уже требуются SALES_ORDER_CONFIRM + INVENTORY_RESERVE, количество и SKU; сейчас не проверен status/type локации |
| 8 | Отменить подтверждённый заказ | `AuthenticatedSalesCommands.cancel` → `InventoryReservationService.release` | READY для существующей модели | Освобождение резерва должно быть возможно как **cleanup**, даже при некорректном/legacy INACTIVE — права и история обязательны |
| 9 | Отгрузить | `AuthenticatedSalesCommands.fulfill` → `InventoryReservationService.fulfill` | PARTIAL | В нормальном flow склад с ACTIVE reservations нельзя деактивировать; для новой отгрузки — проверить допустимость, для повторного terminal result не мутировать историю |
| 10 | Остановить / возобновить / закрыть склад | Нет команд в ORG | MISSING | ORG state machine, dependency guard, CAS, audit; история сохраняется |

**Дополнительная блокирующая находка ORG-11:** `InventoryMovementService.adjust` выбрасывает `Stock position not initialized`, если нет строки `(tenant_id,location_id,item_id)`. Квалификационные тесты `InventoryMovementQualificationTest`, `InventoryReservationQualificationTest` и `SalesInventoryRbacQualificationTest` предварительно создают эту строку SQL INSERT. Следовательно, полный сервисный сценарий «новый склад → новая SKU-позиция → первая приёмка» не квалифицирован. Ответственность INVENTORY, но ORG-E2E должен требовать его успешного исполнения. Не подменять production-flow тестовым SQL.

### 2.1 Бизнес-исключения склада

| Ситуация | Целевая реакция | Побочные эффекты |
|---|---|---|
| Невалидный КАТО | Deny before create/update | 0 строк и audit |
| Другая организация / чужой склад | Deny в выбранном tenant, без подтверждения существования чужого объекта | 0 |
| WAREHOUSE уже INACTIVE/CLOSED | Новые receive/correct/reserve/confirm запрещены | 0 в stock/order |
| Склад ACTIVE, on_hand > 0 или reserved > 0 | deactivate/close запрещены в консервативном R1 | 0 |
| Конкурентные deactivate и receive/confirm | Эксклюзивный переход против совместимого lock eligibility | Коммит одного сценария и согласованный отказ другого |
| Повтор receipt по старому requestKey после закрытия | После актуальной IAM-проверки допустим **только read-only replay** существующего immutable движения с совпадающим payload | 0 новых движений, 0 изменения остатков |
| Cancel требует RELEASE резерва неактивной legacy локации | Cleanup разрешён с dual permission; не выполнять новое резервирование | Резерв освобождён, история сохранена |
| Не хватает товара | Atomic rollback confirm/резервирования | Исходный статус заказа и остаток |
| Заявка на close с несовпадающим expectedVersion | STALE_VERSION до изменения | 0 |
| В складе нет остатка, но есть исторические движения | Разрешено закрытие после отсутствия живых зависимостей | История/ссылки остаются |

**Уточнение к первому ТЗ:** блокировки для автономного движения уже включают `inv_movement_request_lock` (request stripe) перед stock balance. Новый location eligibility lock должен согласовываться с этим существующим порядком, а не вводиться как новая универсальная блокировка перед всеми request locks. Отдельно проверить deadlock у deactivate-vs-receive и multi-SKU SALES.

## 3. Путь BJ-02 — управление филиалом

| Действие | Код сегодня | Решение |
|---|---|---|
| Создать филиал в выбранном регионе | `create(BRANCH)` работает | Сохранить |
| Найти филиал / прочитать реквизиты локации | `search/get` работают, но фильтры ограничены | Сохранить tenant-scoped list, улучшить literal name/KATO |
| Переименовать / исправить адрес | Нет update | Добавить `update(name,address,kato,expectedVersion)` |
| Временно закрыть на ремонт | Нет deactivate | Добавить ACTIVE→INACTIVE с CAS; запретить новые операции только при наличии реального доменного consumer |
| Открыть после ремонта | Нет reactivate | Добавить INACTIVE→ACTIVE с GEO revalidation |
| Закрыть филиал окончательно | Нет close | Добавить INACTIVE→CLOSED, сохранить исторические ссылки |
| Список сотрудников филиала | Отсутствует location→staff membership | OUT OF SCOPE: IAM выдаёт tenant roles; location-specific ACL не создавать без сценария |
| Филиал как родитель складов | Нет parent_location_id в модели | OUT OF SCOPE R1: не добавлять иерархию без утверждённой пользы |

**Практическое правило:** BRANCH — самостоятельная физическая точка справочника, **не** workspace и не владелец ролей. Не создавать «пользователи филиала», «администратор филиала» как новые hardcoded security-роли. Переходы филиала проверяют используемые зависимости, которые действительно существуют; не выдумывать stock/order связность.

## 4. Путь BJ-03 — торговая точка и заказ

| Действие | Код сегодня | Решение |
|---|---|---|
| Создать точку продаж | `create(POINT_OF_SALE)` | READY как location data |
| Найти по статусу/городу/КАТО | `search` type/status/name, без KATO filter | Добавить КАТО, activeOnly, literal contains |
| Поддерживать рабочее расписание/кассу | Нет расписания, смен, кассовой модели | OUT OF SCOPE: не заявлять готовым POS runtime |
| Связать онлайн-витрину с POS | `crm.store_front` имеет один tenant store, нет location_id; `store_offer` нет POS scope | **Нет связи**. Не менять STOREFRONT в задаче по ORG без отдельного контракта |
| Подтвердить онлайн-заказ | SALES confirm принимает склад-UUID, не POS-UUID | Рабочая складская зона — WAREHOUSE; POS автоматически не является складом |
| Остановить торговую точку | Нет deactivate | Добавить lifecycle в ORG; остановка POS **не** меняет автоматически `store_front.state` |
| Закрыть торговую точку | Нет close | Терминальный CLOSED, сохранение данных. Не удалять офферы/заказы |

**ORG-12:** создание POINT_OF_SALE не открывает торговую точку в полноценном коммерческом смысле. Это **не дефект географической локации**; интеграция `POS ↔ STOREFRONT / касса` — отдельная спецификация после завершения текущего scope.

## 5. Сквозные решения минимального контракта

### 5.1 Один реестр локаций, три типа

Не заводить отдельные таблицы филиалов, складов, POS. Поля `org_location` остаются: id, workspace_id, location_type, name, kato_code, address_line, status, timestamps, row_version. Не добавлять parentId, tax identifiers, working hours, pricing or responsible staff в ORG-R1.

### 5.2 Матрица операционного допуска

| Операция | ACTIVE WAREHOUSE | INACTIVE/CLOSED WAREHOUSE | BRANCH/POS |
|---|---|---|---|
| Новый receipt/correct affecting stock | Allowed с INVENTORY permission и позицией | Denied | Denied в текущей складской модели |
| Новый reserve / SALES confirm | Allowed c двумя grants и quantity | Denied | Denied |
| Order cancel + reservation release | Allowed | Cleanup allowed with permissions | Неприменимо |
| Fulfill новой активной reservation | Allowed | Violation, investigate; terminal replay без новых изменений | Denied |
| Inventory stock read | Allowed с INVENTORY_STOCK_READ | История/остатки доступны уполномоченным | По решению INVENTORY, без фиктивного POS stock |
| ORG get/search | Allowed с ORG READ | Allowed для административных целей | Allowed |
| ORG update name/address | Allowed с ORG WRITE + version | Allowed до CLOSED (после CLOSED no edit) | Allowed до CLOSED |
| ORG deactivate/close | Check living dependencies | CLOSED terminal | Check only actual dependencies |

### 5.3 Информационный контракт по зависимостям

- Предлагаемый минимальный internal `LocationUsageBlockers(tenantId,locationId,hasOnHand,hasReserved,hasActiveReservations)` от INVENTORY — **не новый CRUD и не полномасштабный stock service**. Возвращать агрегированные признаки, а не складывать inventory business logic внутрь ORG.
- Точное значение количества не добавлять в `LocationDto` автоматически: INVENTORY_STOCK_READ — самостоятельное право. Мутирующая ORG-команда вправе получить код причины отказа (например `LOCATION_HAS_STOCK`) без раскрытия SKU и сумм.
- Проверить историческое использование локации перед изменением КАТО: если уже были movements/reservations, ретроспективная география не должна бесконтрольно меняться.
- Для административного read использовать базовые `ORG READ/WRITE`; сотрудник с READ без WRITE не может деактивировать.

### 5.4 Existing contract and exact edges

- `CRM customer` и филиал — не один объект. У организации может быть несколько BRANCH и множество клиентов CRM, но существующий код не моделирует «клиент филиала».
- `SALES order` пока не сохраняет warehouse_id в собственном заголовке; привязка появляется в INVENTORY reservations при confirm. Поэтому закрытие не должно делать фиктивный поиск незавершённых PLACED orders «на этом складе» — их локация ещё не выбрана.
- `crm.store_front` связан с tenant, не с POS; закрытие POS не означает `PAUSED` storefront.
- `KatoTerritoryDirectory` возвращает глобальный справочник; значение `kato_code` tenant-local в org_location; не копировать КАТО целиком в каждого tenant.

## 6. Уточнения к разработке после прохода 02

| Task | Владелец | Приоритет | Исполнение |
|---|---|---|---|
| ORG-02A | IAM | P0 предусловие | Доказать создание нового tenant, выдачу ORG_READ/WRITE реальному сотруднику без wildcard и self-assignment |
| ORG-02B | ORGANIZATION | P0 | CRUD edit+deactivate/reactivate/close; state + expectedVersion + atomic audit |
| ORG-02C | INVENTORY | P0 E2E блокер | `initializePosition(tenant,location,item,requestKey)` с нулевыми значениями, authorization и idempotent replay; ORG не вставляет inv_stock_balance |
| ORG-02D | ORGANIZATION/INVENTORY | P0 | Typed ACTIVE WAREHOUSE eligibility/admission + usage blockers, lock discipline |
| ORG-02E | SALES/INVENTORY | P0 | Проверить eligibility для confirm/reserve, сохранить возможность terminal read-only retry и cleanup cancel/release |
| ORG-02F | ORGANIZATION | P1 | literal name search, KATO, bounded Page, active-only warehouse picker |
| ORG-02G | Cross-domain QA | P0 | Создать настоящий service E2E без прямого SQL seeding stock balance, parallel state/movement race |
| ORG-02H | STOREFRONT | P2, только запись | Отдельный будущий POS association contract, без реализации сейчас |

`initializePosition` проектный метод, **сейчас отсутствует**. Конкретная форма входа должна быть согласована с INVENTORY при проходе 05; не вводить отдельный ORG write-adapter для stock.

## 7. Тестовые сценарии BJ-02, дополнение к ORG-Q01..Q12

| ID | Реальный путь | Критерий |
|---|---|---|
| ORG-BJ01 | Новый tenant → назначенные ORG grants → create WAREHOUSE → search/get | Только выбранный tenant, только явные grants |
| ORG-BJ02 | Новый WAREHOUSE → создать нулевую stock position → receive → query | Нет прямых INSERT из тестового arrange в основной путь; ledger and balance consistent |
| ORG-BJ03 | PLACED order с valid SKU → confirm at ACTIVE WAREHOUSE → cancel/release | Два permissions, резервы/события атомарны |
| ORG-BJ04 | Тот же заказ → confirm → fulfill | Резерв и immutable movement отражают фактическую отгрузку |
| ORG-BJ05 | Warehouse with positive onHand → deactivate/close | Блокировка без side effects |
| ORG-BJ06 | Warehouse empty → deactivate → reactivate → close | CAS, audit, never resurrect CLOSED |
| ORG-BJ07 | Concurrent deactivate vs initial receipt/confirm/reserve | Нет новой операции на неактивном складе при любом порядке коммитов |
| ORG-BJ08 | Retry identical inventory request key after location CLOSED | Исходное movement receipt, не новое движение |
| ORG-BJ09 | BRANCH address update and suspension/resume | Изменение only scope fields, нет влияния на IAM or tenants |
| ORG-BJ10 | POINT_OF_SALE create, search, close | Не создаёт store_front, не ставит PAUSED, история сохранена |
| ORG-BJ11 | Shared user in A+B with different grants | А не видит локацию B; роли и права не перетекают |
| ORG-BJ12 | Wrong KATO / closed location / race staleVersion | Fail closed with scoped errors, no partial audit |

Тесты в этом документе — **план**, а не уже исполненный E2E. Действующие `KatoTerritoryQualificationTest` и `OrganizationLocationQueryQualificationTest` показывают отдельные create/query ограничения, а `SalesInventoryRbacQualificationTest` и inventory qualification tests используют прямую SQL setup для stock rows.

## 8. Решения и границы зрелости после 02/12

1. **Склад:** требует нового минимального cross-domain шага INVENTORY initialize; без него «создание склада» нельзя считать законченной бизнес-операцией.
2. **Филиал:** завершение ORG-lifecycle достаточно для R1; отдельная иерархия и персональные роли филиала не нужны.
3. **POS:** R1 — location lifecycle, не кассовый движок и не синхронизация storefront; отсутствие коммерческого режима записать в capabilities.
4. **Зависимости:** при закрытии проверяются реально материализованные stock/reservations; не моделировать связи, которых нет в схеме.
5. **Безопасность:** новый tenant bootstrap fail-closed; нельзя открывать ORGANIZATION_OWNER wildcard.
6. **Повторные вызовы:** исторические безопасные idempotent reads и cleanup должны сохраняться даже после закрытия локации; новые складские мутации — запрещены.
7. **Объём реализации:** никакой новой микросервисной структуры, универсальных workflows, новых UI/REST и широкого переименования схем.

**Статус прохода 02/12: ANALYZED / SPECIFIED.** Закрыта предметная карта сценариев на уровне исходников; runtime реализация и новая qualification не выполнялись. Общая зрелость остаётся **ORGANIZATION 1/5**.

**Следующий проход 03/12:** строгая матрица state transitions, доказательства зависимостей из `inv_stock_balance` и `inv_stock_reservation`, lock ordering на конкретных SQL и политика temporary suspension склада с stock. До выполнения — не объявлять state machine продуманной до уровня E2E.
