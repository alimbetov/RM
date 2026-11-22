
# RM Platform — ORGANIZATION: глубокий аудит и ТЗ на функциональное завершение

**Документ:** RM-ORG-FUNC-R1. **Проходы:** 01/12, 02/12 и 03/12. **Ветка:** base_dev.
**Исходный код на момент аудита:** c776baec5c82e0dfd4c7930aa6a68629202fa5fd (22.11.2026).
**Статус:** ТЗ СФОРМИРОВАНО; lifecycle ORGANIZATION НЕ РЕАЛИЗОВАН, смежные INVENTORY INV-Q1..Q3 уже реализованы/квалифицированы отдельно. Feature Freeze: без HTTP-контроллеров, merge в main и production admission.

## 1. Цель и границы

Сделать зрелый сервис управления физическими локациями организации: филиал (BRANCH), склад (WAREHOUSE), точка продаж (POINT_OF_SALE). Полный путь: создать → найти/прочитать → изменить допустимые данные → временно деактивировать → повторно активировать → окончательно закрыть. При этом операции склада и заказов не должны использовать недоступную локацию; история движений и заказов сохраняется.

**Разграничение ответственности:**
- IAM — юридическая организация (org_organization), workspace (iam_workspace), членство, VerifiedActor, роли и RBAC. Не создавать вторую систему регистрации/администрирования организации в ORGANIZATION.
- ORGANIZATION — public.org_location, тип, адрес, КАТО, статус и допустимость применения локации.
- GEO — каталог и правила выбора КАТО.
- INVENTORY — остатки, движения, резервы, складские команды.
- SALES — заказы и подтверждение/отгрузка.
- Локация не является tenant; смена workspace_id или типа созданной локации запрещена в R1.

## 2. Проверенное состояние исходников

| Компонент | Фактически реализовано | Недостающее |
|---|---|---|
| AuthenticatedOrganizationLocations | Публичный VerifiedActor-фасад create/get/search | update/deactivate/reactivate/close |
| OrganizationLocationService | Package-private create, WRITE permission, GEO validation, JPA save | Изменение профиля, статусы, CAS |
| OrganizationLocationQueryService | Tenant-scoped get/search, READ, Page size 1..100 | Экранирование LIKE; защита от глубокого OFFSET |
| OrganizationLocationEntity | @Version, timestamps, ACTIVE/INACTIVE/CLOSED | Нет методов переходов и изменения полей |
| OrganizationLocationRepository | Scoped get/search, createdAt DESC и id ASC | Поиск literal name, целевой порядок id DESC |
| KatoLocationPolicy | Отказ для неизвестного/удалённого КАТО, level > 3 | Повторная проверка при изменении/реактивации |
| OrganizationLocationQueryQualificationTest | A/B tenant, rollback, VerifiedActor, revoke race, фильтры | Нет lifecycle/stock closure/update-race испытаний |

**Реестр GAP:**
- ORG-01 **P0:** нет явных переходов статуса.
- ORG-02 **P0 → частично закрыт с INV-Q1..Q3:** новые INVENTORY receive/correct уже используют StockEligibility ACTIVE WAREHOUSE; не квалифицированы гонки с ещё не реализованным deactivate и пропускная способность row lock.
- ORG-03 **P0 → частично закрыт с INV-Q1..Q3:** reserve вызывается через StockEligibility ACTIVE WAREHOUSE и откатывает confirm при отказе; отдельная квалификация нового ORG lifecycle и fulfillment на legacy INACTIVE остаётся OPEN.
- ORG-04 **P1:** отсутствие update(name/address/KATO).
- ORG-05 **P1:** имеющийся rowVersion не используется как обязательное предусловие новых команд.
- ORG-06 **P1:** % и _ в параметре LIKE интерпретируются как шаблон; требуется буквальный поиск. Это не доказанная SQL injection: значение параметризовано.
- ORG-07 **P1:** безлимитный глубокий OFFSET; существующий id ASC детерминирован, но отличается от целевого createdAt DESC/id DESC.
- ORG-08 **P1:** операции не пишут предметное доказательство изменения в аудит.
- ORG-09 **P1:** недостаточно PG-проверок двух tenants, mixed membership и конкурентных складских операций.
- ORG-10 **P2:** в Liquibase имеются два логически эквивалентных unique constraint на (workspace_id,id); отдельно изучить, не удалять во время freeze.

Вывод: текущая зрелость **1/5** по исходной методике. Наличие статуса в enum — ещё не работающий lifecycle; source GAP — не утверждение об эксплуатации уязвимости.

## 3. Методика 12 последовательных проходов

После каждого прохода фиксируем: точный SHA, бизнес-сценарии, реальные методы и таблицы, права, отрицательные случаи, связи с доменами, дефекты, изменения ТЗ, доказательства тестами. Статус ТЗ не означает runtime GREEN. На следующий домен переходим только после 12-го прохода и подписанной оценки остаточных рисков.

| Проход | Тема | Результат | Сейчас |
|---|---|---|---|
| 01 | Исходники + перекрёстный аудит | Карта функций, GAP, данное ТЗ | SPECIFIED |
| 02 | Бизнес-сценарии | Пути администратора, кладовщика, продавца и отрицательные действия | SPECIFIED |
| 03 | State machine | Политика складских зависимостей, переходы, ограничения | SPECIFIED |
| 04 | Create/update | Валидация, изменение адреса, КАТО, optimistic CAS | OPEN |
| 05 | INVENTORY | Допуск активного склада для приёмки/коррекции/резерва | OPEN |
| 06 | SALES | Подтверждение, повторы, отмена, отгрузка, порядок блокировок | OPEN |
| 07 | IAM и RBAC | VerifiedActor, A/B/AB, чужие UUID, revoke race | OPEN |
| 08 | Поиск и пагинация | Буквальный LIKE, фильтры, предсказуемые страницы | OPEN |
| 09 | PostgreSQL и audit | Заполненная БД, атомарный журнал действий, DDL | OPEN |
| 10 | Конкурентность и recovery | deactivate-vs-reserve, edit-vs-edit, rollback | OPEN |
| 11 | Нагрузочная и регрессионная квалификация | Exact tests, EXPLAIN, профиль нагрузки | OPEN |
| 12 | Закрытие сервиса | Матрица доказательств, уровень зрелости, готовность следующего домена | OPEN |

## 4. Требования к операциям ORG-R1.1

Сохранить один публичный сервисный фасад AuthenticatedOrganizationLocations. Внутренние UUID-методы оставить package-private; не добавлять контроллеры. Каждая команда: VerifiedActor, selected tenant, ORGANIZATION_LOCATION_WRITE и TenantAuthorization.requireForCommand в транзакции. Чтение: ORGANIZATION_LOCATION_READ и действующий транзакционный security fence. Все SELECT/UPDATE только по (workspace_id,id).

| Метод фасада | Аргументы/результат | Правило |
|---|---|---|
| create | actor, tenant, type, name, kato, address → LocationDto | ACTIVE по умолчанию, валидировать, audit CREATE |
| update | actor, tenant, id, patch, expectedVersion → LocationDto | Только name/address/KATO, CAS, audit UPDATE |
| deactivate | actor, tenant, id, expectedVersion → LocationDto | ACTIVE → INACTIVE, проверка использования, audit |
| reactivate | actor, tenant, id, expectedVersion → LocationDto | INACTIVE → ACTIVE, повторная проверка КАТО, audit |
| close | actor, tenant, id, expectedVersion → LocationDto | INACTIVE → CLOSED, проверка использования, audit |
| get | actor, tenant, id → LocationDto | Административное чтение в том числе исторической закрытой локации |
| search | actor, tenant, filter, page, size → Page | Административная выдача включает все статусы; операционный выбор склада — только ACTIVE WAREHOUSE |

**Правила атрибутов:** name: trim + Unicode NFC, не пустое, максимум 200, без управляющих символов. addressLine: null допустим, максимум 500, trim + NFC. КАТО проверяется через GEO. Тип, tenant, id, createdAt нельзя изменять. Для смены КАТО при наличии исторических складских операций или текущих остатков — отказ; создать новую физическую локацию. Исправление имени/адреса не переписывает immutable историю INVENTORY/SALES. Физический delete не поддерживать.

**Оптимистическая конкурентность:** expectedVersion обязателен для update/deactivate/reactivate/close; проверять rowVersion и изменение только через JPA @Version/CAS. При устаревшей версии — STALE_VERSION, rollback, никакого audit-event. Повтор с устаревшей версией — конфликт, а не обещание exactly-once: отдельную receipt-таблицу без необходимости не вводить. updatedAt меняется один раз при успешном изменении.

## 5. Состояния и правила переходов

| Откуда → куда | Разрешение |
|---|---|
| ACTIVE → INACTIVE | Разрешено при любом свободном onHand, если reserved=0 по всем SKU и нет ACTIVE reservations; полноценный row-lock fence |
| INACTIVE → ACTIVE | Workspace ACTIVE; сохранённый KATO существует: прежний deprecated код не препятствует возврату склада с остатком, новое значение КАТО требует GEO eligible |
| INACTIVE → CLOSED | Зависимости отсутствуют; исторические ссылки не удалять |
| ACTIVE → CLOSED | Нет: предварительно деактивировать |
| CLOSED → любое состояние | Нет, CLOSED — финальный |
| Повтор текущего состояния | Только явно определённая no-op семантика, после permission/version check |

**Решение DEC-ORG-03-A (проход 03):** склад с ненулевым свободным остатком **можно** временно деактивировать при reserved=0 и отсутствии ACTIVE reservations. Новые receive/correct/reserve/confirm запрещены до реактивации. **Закрыть** склад с onHand>0 нельзя: сначала выполнить законный вывод товара после реактивации (или будущий qualified transfer/stocktake). Запрещена фиктивная корректировка ради закрытия. Причины/SQL/гонки — в ревью прохода 03.

## 6. Перекрёстная согласованность и блокировки

1. ORGANIZATION при переходе состояния блокирует именно свою строку org_location выбранного tenant; для использования вызывает **типизированный read-only порт INVENTORY** (onHand/reserved/activeReservation). SALES adapter добавляется только если имеется достоверная привязка открытого заказа к локации.
2. INVENTORY receive/correct/reserve и SALES confirm внутри общей транзакции проверяют через ORGANIZATION typed-port, что location действительно ACTIVE и WAREHOUSE; разделяемая блокировка строки не даёт deactivate вклиниться между admission и записью склада/заказа.
3. Согласовать порядок lock: IAM actor/workspace → SALES order (когда есть) → location admission → stock balance → reservation. Close блокирует location, потом читает счётчики зависимостей **без обратного блокирования SALES order**, иначе возможен deadlock.
4. Текущий PLACED заказ до confirm не сохраняет выбранный locationId: нельзя изображать существование SQL связи «все открытые заказы этого склада». Правильная защита — запрет confirm на неактивной локации. Исторические движения и FULFILLED/RELEASED резервы сохраняются по старым FK.
5. Простого SELECT status недостаточно; конкурентные close-vs-confirm/receive должны быть синхронизированы транзакционной блокировкой, с доказательством PostgreSQL.

**Уточнение типов:** для R1 коммерческая резервация и приёмка используются только с WAREHOUSE. Перед вводом ограничения проверить заполненные данные: нет ли stock balance у BRANCH/POS. Если такие сценарии нужны, утвердить POS-склад как отдельное бизнес-правило на проходе 05, не игнорировать существующие записи.

## 7. Поиск, БД, аудит

- Расширить LocationSearch необязательными katoCode и activeOnly, не сломав старый конструктор (type,status,name); исключить противоречивые комбинации status/activeOnly.
- Поиск по name — *буквальное вхождение*: экранирование %, _, escape-символа с явным SQL/JPQL ESCAPE, все значения строго параметризованы; фильтр 0..100 символов. Тест Unicode.
- Сортировка createdAt DESC, id DESC. Page size 1..100, верхняя граница page×size (обоснованный лимит, прежде чем запускать дорогой OFFSET). HMAC cursor оставлять SEARCH-R2, сейчас не строить второй search-framework.
- Для административной выдачи закрытые локации остаются видимыми. Для выпадающего выбора склада необходим **явный** фильтр ACTIVE WAREHOUSE, а не текущая выдача всех статусов.
- Существующие FK, check constraints, rowVersion и composite (workspace_id,id) переиспользовать; без новой таблицы локаций и destructive migration. Новые индексы — лишь после EXPLAIN и реальной необходимости.
- Писать в существующий crm.audit_event (tenant, actor, location entityId, occurredAt, action): ORG_LOCATION_CREATE/UPDATE/DEACTIVATE/REACTIVATE/CLOSE в одной транзакции с изменением. Существующая структура **не хранит значения до/после**, поэтому не объявлять подробную историю полей готовой; отдельный журнал не вводить в R1.

## 8. Обязательная квалификация

| Test ID | Сценарий | Условие успеха |
|---|---|---|
| ORG-Q01 | create/get/search и атомарный audit | Правильные tenant, persisted entity и ровно один event |
| ORG-Q02 | null actor, foreign tenant/location, отсутствие/отзыв READ/WRITE | Отказ до данных, нет раскрытия чужого объекта |
| ORG-Q03 | edit name/address/KATO, invalid/type/tenant change | Только допустимые поля, rowVersion/updatedAt |
| ORG-Q04 | Два edit с одним expectedVersion | Один commit, один STALE_VERSION; без ложного audit |
| ORG-Q05 | onHand>0 при reserved=0 и отсутствии ACTIVE; отдельно reserved>0/ACTIVE reservation | Deactivate разрешён только в первом случае, close не разрешён при onHand>0; без частичной записи |
| ORG-Q06 | ACTIVE→INACTIVE→ACTIVE; INACTIVE→CLOSED | Все допустимые переходы и закрытый финальный статус |
| ORG-Q07 | Одновременно deactivate и receive/correct/confirm | Сериализация, нет записи на неактивном складе |
| ORG-Q08 | INVENTORY/SALES с INACTIVE/CLOSED/BRANCH/foreign | Отказ до движения и изменения заказа |
| ORG-Q09 | Остаток=0, исторические movements/reservations | История/FK сохранены, нет физического каскада |
| ORG-Q10 | %, _, backslash, Unicode, 201 одинаковых timestamps и page bounds | Литеральный поиск, устойчивый порядок, корректный count |
| ORG-Q11 | Один глобальный пользователь A+B, разные grants, revoke race | Effective RBAC только нужного tenant |
| ORG-Q12 | Populated PG, H2, GEO/CRM/SALES/INVENTORY regression | Нет потери данных, exact HEAD и не пропущенный PG |

Исходный OrganizationLocationQueryQualificationTest частично подтверждает только Q01/Q02/Q11 в пределах существующих сценариев. Он **не** доказывает Q03–Q10 или Q12. Для каждого метода фиксировать точный SHA, тест, СУБД, результаты независимого SQL-чтения и гонки на PostgreSQL.

## 9. Этапы реализации и DoD

| Этап | Объём | Gate |
|---|---|---|
| ORG-R1.1A | JPA update, validation, state methods, facade, CAS, audit | Q01–Q04, Q06 |
| ORG-R1.1B | Typed INVENTORY usage/admission и SALES confirm guard | Q05, Q07–Q09 |
| ORG-R1.1C | Scoped search, literal LIKE, page safety | Q02, Q10–Q11 и EXPLAIN |
| ORG-R1.1D | Populated PostgreSQL, поимённый TCRUD, exact-head CI | Q01–Q12 + ревью |

**Definition of Done:** полный сервисный путь управления локацией, enforce actor/tenant/permissions, корректная невозможность движения по неактивному складу, CAS и history safety, атомарный audit, PostgreSQL concurrency и регрессии. После доказанной реализации возможна переоценка зрелости в сторону 3–4/5; production approval остаётся отдельным решением.

**Не реализовывать без обоснования:** иерархия филиалов; ACL сотрудников на каждый склад; геокодирование; transfer/stocktake/valuation внутри ORG; multi-warehouse shipping; универсальная saga/event bus; удаление исторических локаций; повторный legal-org CRUD; контроллеры/UI; field-diff аудит.

## 10. Следующий пакет: проход 02/12

Разобрать реальные пути сотрудника склада, менеджера филиала/POS и подтверждающего заказ продавца. Уточнить семантику временной остановки склада с остатком, все ошибочные переходы, SQL usage-count и lock ordering до изменения runtime-кода. Следующий домен — только после 12/12.


---

## 11. Дополнение после прохода 02/12: реальные бизнес-сценарии

**Подробный источник:** [RM-ORG-FUNC-R1-P02 — предметное обследование](../reviews/RM-ORG-FUNCTIONAL-DEPTH-PASS02-2026-11-22.md). Снимок исходников `base_dev`: `2b4afbbabc6be0193def86a933d7947163f964ae`, доменный runtime не изменён относительно начального аудита. **Статус:** сценарии описаны и сверены с исходным кодом; новых сервисов и E2E ещё нет.

### 11.1 Уточнение трёх целевых бизнес-путей

| Роль / объект | Действующая последовательность | Достроить минимально |
|---|---|---|
| Сотрудник склада / WAREHOUSE | IAM выдаёт grants → ORG.create → ORG.get/search → INVENTORY.receive/correct → SALES.confirm → reserve → fulfill/cancel | Первый RECEIPT теперь автоматически создаёт нулевую SKU-позицию через INVENTORY (INV-Q2); осталось edit/status/CAS и guard при закрытии |
| Менеджер филиала / BRANCH | IAM grants → ORG.create → ORG.get/search | Редактирование, временная деактивация, повторная активация, закрытие; без локальных hardcoded IAM-ролей |
| Сотрудник торговой точки / POINT_OF_SALE | ORG.create → ORG.get/search | Lifecycle локации; **не** приравнивать к настоящей кассе или интернет-магазину, потому что `store_front` tenant-scoped без location_id |

### 11.2 Новые междоменные риски и приоритеты

| ID | Приоритет | Проверенное наблюдение | Обязательное решение |
|---|---|---|---|
| ORG-11 | **RESOLVED for first RECEIPT by INV-Q2** | В исходном снимке 02/12 требовалась предварительная stock-row; в актуальном коде `InventoryMovementService.adjust` создаёт нулевую позицию атомарно после StockEligibility и idempotency fence | Не вводить новый `initializePosition`; добавить реальный ORG+INVENTORY service E2E без прямого stock INSERT |
| ORG-12 | P2, separate STOREFRONT scope | `crm.store_front` связан с tenant, не location, а POS не привязан к offer | В R1 не обещать POS checkout/кассу; отдельное решение после ORGANIZATION |
| ORG-13 | **P0, prerequisite IAM** | `PlatformTenantBootstrapService.provision` включает RBAC enforced для нового tenant, но не выдаёт функциональных прав; `TenantRoleService.assign` блокирует self-assignment | До create location провести обычное подтверждённое назначение staff нужных ORG_READ/WRITE без wildcard; не менять security ради обхода |
| ORG-14 | **P1** | Нет типизированного location usage summary для команды close | Минимальный read-only INVENTORY blocker (onHand/reserved/active reservation), без деталей SKU и без нового ORG stock ledger |

### 11.3 Уточнённая политика операций со складами

- Новый `receive/correct` и резервирование требуют ACTIVE WAREHOUSE внутри общей транзакции. Для SALES confirm дополнительно обязательны `SALES_ORDER_CONFIRM` + `INVENTORY_RESERVE`.
- **Cancel/release** — это cleanup существующего резерва, его нельзя запрещать только из-за legacy INACTIVE location, иначе возможны зависшие резервы; обязательны действующие права и корректная идемпотентность.
- Успешно выполненная раньше операция movement с прежним requestKey может быть безопасно возвращена **без повторной записи** после CLOSED, но только после проверки актуального IAM, identity и полного payload. Новый requestKey при CLOSED отклоняется. Для реализации проверить lock order уже существующего request stripe.
- Fulfill с ACTIVE reservation у INACTIVE/CLOSED указывает на нарушение инварианта; не делать новую negative movement. Terminal replay допускается без новых побочных эффектов.
- По DEC-ORG-03-A `deactivate` разрешён с положительным **свободным** остатком, но запрещён при `reserved>0` или ACTIVE reservation; `close` требует `onHand=reserved=0` и отсутствие ACTIVE reservations. Историю сохранять без каскадной очистки.
- `SALES` не содержит warehouse_id у заказа до confirm, поэтому «незавершённые PLACED orders на складе» сегодня не определены как связанная зависимость.
- Новый location lock нужно вводить с учётом существующего `inv_movement_request_lock`, `sales_order FOR UPDATE`, `stock_balance FOR UPDATE` и `reservation`; слепое внедрение глобального lock первым запрещено до теста deadlock.
- Для ORG административного `get/search` не раскрывать количество товара без `INVENTORY_STOCK_READ`. Команда close может получить ограниченный код блокировки без инвентарных сумм.

### 11.4 Изменение очереди реализации

**Синхронизация с INV-Q1..Q3:** отдельно реализовывать `initializePosition` больше не нужно: первый trusted RECEIPT сам создаёт нулевой balance. Для `ORG-R1.1B` остаются переходы состояния с dependency guard, конкурентная проверка location locks и E2E «создал склад → первый RECEIVE → временно отключил с товаром». Не писать `crm.inv_stock_balance` из ORGANIZATION.

**Дополнительные приёмочные сценарии:** `ORG-BJ01..ORG-BJ12` в подробном ревью 02/12, включая create/edit/close BRANCH, lifecycle POS без побочных эффектов на STOREFRONT, исторический idempotent retry, concurrent deactivate-vs-receive/confirm, onboarding с реальным IAM grant.

**Историческая граница 02/12 заменена решением прохода 03:** INACTIVE разрешён при свободном stock, close требует zero stock; SQL и lock-order описаны в новом ревью. **ORGANIZATION 1/5; runtime его lifecycle не менялся.**


---

## 12. Дополнение после прохода 03/12 — State Machine & Dependency Integrity

**Развёрнутое ревью:** [RM-ORG-FUNC-R1-P03 — переходы, ограничения, SQL и блокировки](../reviews/RM-ORG-FUNCTIONAL-DEPTH-PASS03-2026-11-22.md).
**Source runtime:** `1cc19715e82551ec22dd451427ea34b2a8b0c86d`. **Стадия:** ANALYZED / CONTRACT SPECIFIED, **НЕ IMPLEMENTED** в ORGANIZATION.

### DEC-ORG-03-A — утверждённое проектное правило R1

`ACTIVE → INACTIVE` **разрешить** с `on_hand > 0` (склад физически хранит товар), если `reserved=0` на всех SKU и **нет `inv_stock_reservation.state='ACTIVE'`**. Пауза исключает **новые** receipt/correct/reserve/confirm/fulfill, но сохраняет stock, историю, безопасные read-only request-key replays и cleanup release/cancel по фактическому состоянию заказа.

`INACTIVE → CLOSED` **только** при `on_hand=reserved=0` на всех SKU и без ACTIVE reservation. `CLOSED` терминален, нет физического удаления stock/ledger/order истории. `ACTIVE → CLOSED` запрещён. Same-state retry при актуальном `expectedVersion` — no-op, без дополнительного события; stale version всегда конфликт.

**Особое исключение GEO:** устаревший, но уже сохранённый в `org_location.kato_code` справочный код не должен безвозвратно блокировать реактивацию склада с товаром. Повторная активация допускает **неизменённое существующее FK-значение** KATO, даже если впоследствии `is_deleted=TRUE`; любое **новое** KATO-значение при update обязано пройти `KatoLocationPolicy.requireOrganizationLocationEligible`. Не разрешать физическое перемещение склада через «исправление KATO», если склад имеет историческую inventory-активность.

### Уточнение по source-level gaps

- Ранее зафиксированный ORG-11 («нет возможности первой приёмки») закрыт **кодом INVENTORY INV-Q2**; в актуальном `InventoryMovementService.adjust` первый RECEIPT сам создаёт `inv_stock_balance`. `StockEligibility` уже проверяет ACTIVE WAREHOUSE и PRODUCT для новых операций, а `InventoryReservationService.reserve` подтверждает повтор старого резерва без нового side effect. Не дублировать это.
- `StockEligibility` сейчас использует `SELECT ... FOR UPDATE` по `org_location` даже для проверок ACTIVE, последовательно блокируя операции разных SKU на одном складе. В рамках 03 это P1 PERFORMANCE QUALIFICATION GAP, **не** доказанный провал SLA. После корректностных PG tests рассмотреть `FOR SHARE` в PostgreSQL, оставив H2-совместимый fallback.
- `InventoryReservationService.transition` должен сохранять cancel/release cleanup и terminal replay; дополнительный guard для **новой** fulfillment на legacy INACTIVE location нужен с аккуратным соблюдением `order → location → stock` lock-order.
- Для нового ORG lifecycle реализовать небольшой типизированный `InventoryLocationUsagePort` только по tenant/location с триадой `hasOnHand / hasReservedBalance / hasActiveReservation`. Не вводить третий stock service и не выводить SKU/quantity через `ORG_READ`.

### PostgreSQL acceptance

**Деактивация:** `location FOR UPDATE → EXISTS(reserved>0) → EXISTS(active reservations) → JPA status/CAS → audit → COMMIT`. Под row lock все новые RECEIPT/reserve обязаны брать совместимую блокировку той же location (существующий `StockEligibility` уже берёт эксклюзивную, но нуждается в deadlock/throughput проверке).

**Закрытие:** добавить `EXISTS(onHand>0)`, остальные guards те же. Все SQL учитывают tenant и location, не опрашивают всю историю/все заказы. `PLACED` sales_order не содержит `location_id` — не имитировать привязку к складу до confirm.

**Тесты:** `P03-Q01…P03-Q14` в ревью (пауза с stock, с reserve, несогласованность, закрытие, deprecated KATO, optimistic conflicts, deactivate-vs-receive/confirm, cleanup/idempotent replay, PG lock wait), совместно с `InventoryQ123QualificationTest` и существующими SALES/ORG/GEO регрессиями. Не выставлять P03 тестам GREEN без запуска на точном runtime SHA.

**Масштаб:** существующие типы/статусы/JPA/DDL сохраняются. Минимальная доработка — сервисные state methods, shared row lock, inventory usage read port, атомарный audit и тесты. Не создавать новые микросервисы/дополнительные состояния/саги.

**Следующий проход 04/12:** точный patch-контракт name/address/KATO, неизменяемость tenant/type, CAS и оптимистические обновления с подтверждением PostgreSQL. Приоритет завершения ORGANIZATION сохраняется.
