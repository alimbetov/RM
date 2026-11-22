
# RM Platform — ORGANIZATION: глубокий аудит и ТЗ на функциональное завершение

**Документ:** RM-ORG-FUNC-R1. **Проходы:** 01/12…07/12 — SPECIFIED; **новый этапный рубеж пройден на уровне ТЗ**. Handoff следующему домену разрешён; 08–12 — DEFERRED/NOT DONE, не production admission. **Ветка:** base_dev.
**Исходный код на момент аудита:** c776baec5c82e0dfd4c7930aa6a68629202fa5fd (22.11.2026).
**Статус:** ТЗ 01–07 СФОРМИРОВАНО; **ORG-R1.1 source implementation and 12 new tests committed** (6 lifecycle + 6 PG races). **Exact-SHA CI GREEN:** `69f757cf4d79c84d0198a6f2af432067a17007b2`, [workflow #44989157721](https://github.com/alimbetov/RM/actions/runs/44989157721). 314 test cases discovered, PostgreSQL 0 failures/0 errors/18 skips, H2 0 failures/0 errors/66 skips. Full production admission remains HOLD; 08–12 pending. No controllers/merge main.

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
| AuthenticatedOrganizationLocations | VerifiedActor-фасад create/get/search, typed update/deactivate/reactivate/close (code present R1.1) | Runtime PG E2E и повторные конкурентные проверки |
| OrganizationLocationService | Transaction-bound WRITE, normalized create/update, status CAS, dependency blockers, same-TX audit (code present) | PG QUALIFICATION, failure injection, migration preflight |
| OrganizationLocationQueryService | Tenant-scoped get/search, READ, Page size 1..100 | Экранирование LIKE; защита от глубокого OFFSET |
| OrganizationLocationEntity | @Version, immutable tenant/type/identity; explicit replaceProfile/moveTo (code present) | Verify JPA version increments on real PostgreSQL |
| OrganizationLocationRepository | Scoped get/search + tenant/id @Lock(PESSIMISTIC_WRITE) (code present) | Literal LIKE and deep-page improvements deferred |
| KatoLocationPolicy | Отказ для неизвестного/удалённого КАТО, level > 3 | Повторная проверка при изменении/реактивации |
| OrganizationLocationQueryQualificationTest + LifecycleQualificationTest + R11ConcurrencyQualificationTest | Existing A/B/revoke + 6 lifecycle and 6 PG concurrency methods; GREEN on exact SHA 69f757 | Broader P03..P07 qualification and 08–12 release gates pending |

**Реестр GAP:**
- ORG-01 **P0 → CODE PRESENT / QA PENDING:** typed transitions ACTIVE/INACTIVE/CLOSED committed; PG race/rollback gates remain.
- ORG-02 **P0 → CODE PRESENT / QA PENDING:** INVENTORY location guard and ORG.deactivate now share the location row fence; comprehensive PG mixed-command concurrency and lock throughput not yet qualified.
- ORG-03 **P0 → PARTIALLY IMPLEMENTED:** reserve requires ACTIVE WAREHOUSE, ORG lifecycle blockers now present; cross-domain confirm/deactivate PG and legacy INACTIVE fulfill gap remain OPEN.
- ORG-04 **P1 → CODE PRESENT:** full normalized name/address/KATO update, changed-KATO history guard; QA pending.
- ORG-05 **P1 → CODE PRESENT:** update and lifecycle require expectedVersion with scoped PESSIMISTIC_WRITE; QA pending.
- ORG-06 **P1:** % и _ в параметре LIKE интерпретируются как шаблон; требуется буквальный поиск. Это не доказанная SQL injection: значение параметризовано.
- ORG-07 **P1:** безлимитный глубокий OFFSET; существующий id ASC детерминирован, но отличается от целевого createdAt DESC/id DESC.
- ORG-08 **P1 → CODE PRESENT:** create/profile/state changes append tenant+actor+location+action to crm.audit_event in same TX; immutable/partitioned PG QA pending.
- ORG-09 **P1:** недостаточно PG-проверок двух tenants, mixed membership и конкурентных складских операций.
- ORG-10 **P2:** в Liquibase имеются два логически эквивалентных unique constraint на (workspace_id,id); отдельно изучить, не удалять во время freeze.

Вывод: исходная зрелость **1/5** была оценена до реализации. Сегодня CRUD/lifecycle и шесть новых PostgreSQL конкурентных сценариев получили **GREEN на точном SHA 69f757**. Это значительное укрепление implementation confidence, но оценка полной production-зрелости требует populated migration, оставшейся P03–P07 матрицы и 08–12 gates; production HOLD.

## 3. Методика 12 последовательных проходов

После каждого прохода фиксируем: точный SHA, бизнес-сценарии, реальные методы и таблицы, права, отрицательные случаи, связи с доменами, дефекты, изменения ТЗ, доказательства тестами. Статус ТЗ не означает runtime GREEN. **Изменение последовательности 22.11.2026:** после прохода 07/12 готовится промежуточный итог ORGANIZATION с реестром остаточных рисков, и допускается переход к следующему домену по новому требованию пользователя. Проходы 08–12 останутся DEFERRED/NOT DONE, а не станут закрытыми автоматически; итоговую зрелость не повышать без runtime qualification.

| Проход | Тема | Результат | Сейчас |
|---|---|---|---|
| 01 | Исходники + перекрёстный аудит | Карта функций, GAP, данное ТЗ | SPECIFIED |
| 02 | Бизнес-сценарии | Пути администратора, кладовщика, продавца и отрицательные действия | SPECIFIED |
| 03 | State machine | Политика складских зависимостей, переходы, ограничения | SPECIFIED |
| 04 | Create/update | Валидация, изменение адреса, КАТО, optimistic CAS | SPECIFIED |
| 05 | INVENTORY | Receive/correct/writeOff/transfer/reserve/stocktake, blocker матрица, deadlock Q7 | SPECIFIED |
| 06 | SALES | Confirm/cancel/fulfill, per-line reserve evidence, warehouse state × order races | SPECIFIED |
| 07 | IAM и RBAC | VerifiedActor, tenant role/permission, A/B/AB, revoke races, onboarding | SPECIFIED |
| 08 | Поиск и пагинация | Буквальный LIKE, фильтры, предсказуемые страницы | DEFERRED after 07 |
| 09 | PostgreSQL и audit | Заполненная БД, атомарный журнал действий, DDL | DEFERRED after 07 |
| 10 | Конкурентность и recovery | deactivate-vs-reserve, edit-vs-edit, rollback | DEFERRED after 07 |
| 11 | Нагрузочная и регрессионная квалификация | Exact tests, EXPLAIN, профиль нагрузки | DEFERRED after 07 |
| 12 | Закрытие сервиса | Полная release-матрица доказательств и квалификация после возврата | DEFERRED after 07 |

## 4. Требования к операциям ORG-R1.1

Сохранить один публичный сервисный фасад AuthenticatedOrganizationLocations. Внутренние UUID-методы оставить package-private; не добавлять контроллеры. Каждая команда: VerifiedActor, selected tenant, ORGANIZATION_LOCATION_WRITE и TenantAuthorization.requireForCommand в транзакции. Чтение: ORGANIZATION_LOCATION_READ и действующий транзакционный security fence. Все SELECT/UPDATE только по (workspace_id,id).

| Метод фасада | Аргументы/результат | Правило |
|---|---|---|
| create | actor, tenant, LocationCreateRequest(type,name,kato,address) → LocationDto; совместимый legacy overload | ACTIVE по умолчанию, нормализовать, audit CREATE; сервер выдаёт UUID |
| update | actor, tenant, id, LocationUpdateRequest(name,kato,address,expectedVersion) → LocationDto | **Полное замещение только редактируемых полей**, не generic PATCH; JPA scoped lock + @Version CAS; no-op без audit |
| deactivate | actor, tenant, id, expectedVersion → LocationDto | ACTIVE → INACTIVE, проверка использования, audit |
| reactivate | actor, tenant, id, expectedVersion → LocationDto | INACTIVE → ACTIVE, повторная проверка КАТО, audit |
| close | actor, tenant, id, expectedVersion → LocationDto | INACTIVE → CLOSED, проверка использования, audit |
| get | actor, tenant, id → LocationDto | Административное чтение в том числе исторической закрытой локации |
| search | actor, tenant, filter, page, size → Page | Административная выдача включает все статусы; операционный выбор склада — только ACTIVE WAREHOUSE |

**Правила атрибутов (DEC-ORG-04-A):** name: `strip()+NFC`, 1..200 Unicode code points, без control/bidi override; addressLine: `null`/blank→NULL, иначе `strip()+NFC`, максимум 500 code points. `katoCode` — точная строка `[0-9]{2,16}` с ведущими нулями, при create/реальном изменении проверяется GEO; сохранённый неизменённый deprecated KATO не препятствует редактированию имени/адреса. При смене КАТО — запрет при ненулевом stock, любой истории inv_stock_movement (включая transfer) или любой inv_stock_reservation. Тип, tenant, id, createdAt нельзя изменять. Дубликаты названия разрешены, update CLOSED запрещён. Физический delete не поддерживать.

**Оптимистическая конкурентность:** expectedVersion обязателен для update/deactivate/reactivate/close; в 04 `update` выполняется после `TenantAuthorization.requireForCommand`, через scoped `lockByWorkspaceIdAndId` с `PESSIMISTIC_WRITE`, явное сравнение rowVersion и JPA @Version. При устаревшей версии — STALE_VERSION и rollback даже если новые значения совпадают. Если полное нормализованное содержимое совпадает и expectedVersion актуален — no-op: не меняются updatedAt/rowVersion/audit. При фактическом изменении — ровно +1 version, один audit. CREATE без stable requestKey не является идемпотентным: два вызова создают две записи с разными UUID, и дубликаты name разрешены.

## 5. Состояния и правила переходов

| Откуда → куда | Разрешение |
|---|---|
| ACTIVE → INACTIVE | Разрешено при любом свободном onHand, если reserved=0 по всем SKU, нет ACTIVE reservations и нет OPEN stocktakes; полноценный location-row fence (DEC-ORG-05-B) |
| INACTIVE → ACTIVE | Workspace ACTIVE; сохранённый KATO существует: прежний deprecated код не препятствует возврату склада с остатком, новое значение КАТО требует GEO eligible |
| INACTIVE → CLOSED | onHand=reserved=0 по всем SKU, нет ACTIVE reservations и нет OPEN stocktakes; исторические ссылки/ledger не удалять |
| ACTIVE → CLOSED | Нет: предварительно деактивировать |
| CLOSED → любое состояние | Нет, CLOSED — финальный |
| Повтор текущего состояния | Только явно определённая no-op семантика, после permission/version check |

**Решения DEC-ORG-03-A + DEC-ORG-05-B (проходы 03/05):** склад с ненулевым свободным остатком **можно** временно деактивировать при reserved=0, отсутствии ACTIVE reservations и отсутствии OPEN stocktakes. Новые receive/correct/reserve/confirm запрещены до реактивации. **Закрыть** склад с onHand>0 нельзя: сначала выполнить законный вывод товара после реактивации (или будущий qualified transfer/stocktake). Запрещена фиктивная корректировка ради закрытия. Причины/SQL/гонки — в ревью прохода 03.

## 6. Перекрёстная согласованность и блокировки

1. ORGANIZATION при переходе состояния блокирует именно свою строку org_location выбранного tenant; для использования вызывает **типизированный read-only порт INVENTORY** (hasOnHand/hasReservedBalance/hasActiveReservation/hasOpenStocktake). SALES adapter добавляется только если имеется достоверная привязка открытого заказа к локации.
2. INVENTORY receive/correct/reserve и SALES confirm уже используют внутренний `StockEligibility` INVENTORY для проверки ACTIVE WAREHOUSE/PRODUCT, блокируя `org_location` в той же транзакции (`FOR UPDATE` на текущем HEAD). Не создавать параллельный проверяющий фасад в ORG: согласовать существующий admission с будущим deactivate/close; `FOR SHARE` рассматривать только после PG race/throughput tests.
3. Учитывать реальные lock order: standalone INVENTORY = IAM → request-key stripe → location eligibility → catalog product → stock balance; SALES confirm/reserve = IAM paired decisions → sales order → location eligibility → catalog product → stock balance → reservation; **Q7 transfer P05-01 уже исправлен кодом текущего HEAD**: stripe → оба location row locks по UUID через `StockEligibility.requireTransferWarehouses` → PRODUCT ONCE → stock rows по UUID. В тестах существует `receiptAtTransferDestinationDoesNotCycleWithWarehouseOwnerLocks`; не считать это proof будущих ORG lifecycle гонок. ORG deactivate/close = IAM → location FOR UPDATE → только не-блокирующие dependency EXISTS (включая OPEN stocktake) → JPA state/audit; не приобретать обратным порядком sales order / request stripe / stock balance locks.
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
| ORG-R1.1B | Typed INVENTORY usage flags (вкл. OPEN stocktake), ORG lifecycle admission; INV-Q7 P05-01 уже исправлен кодом; P05-02 fulfill guard остаётся | Q05, Q07–Q09 + P05-Q01..Q20 |
| ORG-R1.1C | Scoped search, literal LIKE, page safety | Q02, Q10–Q11 и EXPLAIN |
| ORG-R1.1D | Populated PostgreSQL, поимённый TCRUD, exact-head CI | Q01–Q12 + ревью |

**Definition of Done:** полный сервисный путь управления локацией, enforce actor/tenant/permissions, корректная невозможность движения по неактивному складу, CAS и history safety, атомарный audit, PostgreSQL concurrency и регрессии. После доказанной реализации возможна переоценка зрелости в сторону 3–4/5; production approval остаётся отдельным решением.

**Не реализовывать без обоснования:** иерархия филиалов; ACL сотрудников на каждый склад; геокодирование; transfer/stocktake/valuation внутри ORG; multi-warehouse shipping; универсальная saga/event bus; удаление исторических локаций; повторный legal-org CRUD; контроллеры/UI; field-diff аудит.

## 10. Следующий пакет: проход 02/12

Разобрать реальные пути сотрудника склада, менеджера филиала/POS и подтверждающего заказ продавца. Уточнить семантику временной остановки склада с остатком, все ошибочные переходы, SQL usage-count и lock ordering до изменения runtime-кода. **Историческое правило «только после 12/12» заменено milestone 07/12** (см. §15).


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

**Проход 04/12 закрыт на уровне ТЗ:** точный full-profile update, DTO, tenant-scoped locked JPA CAS, правила KATO history и 20 запланированных P04 tests. Следующий 05/12 — сопряжение с INVENTORY/transfer, не реализации Java в текущем документе.


---

## 13. Дополнение после прохода 04/12 — Create, Update & Data Integrity

**Детальное ревью/нормативная конкретизация:** [RM-ORG-FUNC-R1-P04](../reviews/RM-ORG-FUNCTIONAL-DEPTH-PASS04-2026-11-22.md). **Source SHA:** `5d3eff7362ea895eb6af3b78a7fa478937569662`, `base_dev`. **Статус:** DESIGN SPECIFIED, **не новый реализованный CRUD / не выполнены P04 tests**.

### DEC-ORG-04-A: минимальный контракт без generic PATCH

- `LocationCreateRequest(Type type,String name,String katoCode,String addressLine)`; `LocationUpdateRequest(String name,String katoCode,String addressLine,long expectedVersion)`. Два typed records, без универсальной PATCH-модели. Обязательные `name/katoCode` всегда передаются полностью, `addressLine=null/blank` очищает адрес; `id/tenant/type/status` неизменяемы и не могут попасть в update payload.
- `AuthenticatedOrganizationLocations.create(VerifiedActor,UUID,LocationCreateRequest)` и `update(VerifiedActor,UUID,UUID,LocationUpdateRequest)`; сохранить existing legacy create overload для квалификационных тестов. Actor ID никогда не принимать из DTO. Операционная WRITE авторизация должна опережать чтение чужого ID или GEO.
- Normalize once for create and update: Unicode NFC+`strip`, name 1..200 codepoints, optional address 0..500 codepoints, reject controls/invalid surrogate; exact ASCII digits KATO 2..16, no integer conversion/leading-zero loss. Общих BeanValidation framework/extensions для трех полей не вводить.
- KATO eligibility: только create или **changed code**. Для неизменённого сохранённого (возможно deprecated) КАТО разрешать name/address correction, согласованную с проходом 03.
- Changed KATO запрещён при любой `inv_stock_movement` истории (включая IN/OUT INV-Q7 transfers), любой `inv_stock_reservation` истории или положительных `on_hand/reserved`; проверять `EXISTS` внутри transaction после `org_location FOR UPDATE`. Простой нулевой `inv_stock_balance` без ledger допускает корректировку. Это предотвращает перепривязку истории товара к новой географии, не блокируя исправление текста адреса.
- Создание `id=UUID.randomUUID()`, `status=ACTIVE`, `rowVersion=0`, audit `ORG_LOCATION_CREATE`. Name не уникален; network retry create **не exactly-once** без request-id. Отдельный idempotency store и уникальность name в R1 не вводить.
- Update `@Transactional`: `requireForCommand(WRITE) → scoped JPA @Lock(PESSIMISTIC_WRITE) → version/status check → normalize → changed KATO history/GEO → domain applyProfile → @Version flush → crm.audit_event UPDATE → commit`. Stale version conflict **даже если** значения такие же. Успешный no-op при актуальной версии — без `rowVersion/updatedAt/audit` изменений; успешная реальная мутация — increment version once и одно audit evidence.
- Hibernate JPA-generated UPDATE по `id+rowVersion` может физически **не включать workspace_id**: tenant-изоляцию доказывает **только scoped locked SELECT и immutable workspaceId + глобальный PRIMARY KEY id**. Не создавать unscoped `findById` write-flow. Это явный выбор scoped JPA/R1 без нового SQL-CAS-engine; проверить emitted SQL в PG.
- Существующая DDL `org_location` уже поддерживает профиль, nullable address, version, FK и enum CHECK. Дополнительную таблицу не вводить. Blank-name DB constraint/заполненные данные — отдельно в 09 (populated-safe); существующий JPA constructor пока не нормализует значения.
- Для update CLOSED всегда отказ, INACTIVE редактируется, ACTIVE редактируется. Переходы статуса по-прежнему отдельные команды из 03.

### Изменения исходников при будущей реализации

`OrganizationLocationRepository.lockByWorkspaceIdAndId` — scoped JPA query с `@Lock(PESSIMISTIC_WRITE)`. `OrganizationLocationEntity.applyProfile` — package-bound method, меняет только `name,katoCode,addressLine,updatedAt`; не делать публичные setters для tenant/type/status. `OrganizationLocationService` — без нового микросервиса: нормализация, версия, changed-KATO proof, транзакционный audit. Для changed-KATO history использовать минимум scoped EXISTS по INVENTORY таблицам после согласования internal owner-port; не отдавать staff SKU/quantities без `INVENTORY_STOCK_READ`.

### Приёмка

План `ORG-P04-Q01…Q20` включён в подробное ревью: create трех типов, Unicode/границы, blank/invalid KATO, duplicate names, A/B tenant authorization, update/full replacement, null-address clearing, no-op/stale/no audit, changed-KATO before/after movements/reservations/transfers, INACTIVE/CLOSED, two-connection update-vs-update и update-vs-deactivate/receive, rollback + revoke fence. **Все новые P04 tests — NOT RUN.**

**Домен ORGANIZATION остаётся 1/5. Следующий проход 05/12:** INVENTORY owner-boundary, receipt/correct/transfer, влияние INACTIVE на операции и SQL lock/concurrency; без контроллеров и реализации ранее утверждённых ТЗ во время аудита.


---

## 14. Дополнение после прохода 05/12 — INVENTORY Integration & Warehouse Integrity

**Детальное ревью:** [RM-ORG-FUNC-R1-P05 — реальные методы, SQL, locks, Q7/Q8](../reviews/RM-ORG-FUNCTIONAL-DEPTH-PASS05-2026-11-22.md). **Verified source HEAD:** `1185d5554ae4bd07aa83109d12237ee0eec34846`, `base_dev`. **Статус:** SOURCE REVIEWED / SPECIFIED, NOT IMPLEMENTED для новых ORG lifecycle и междоменных исправлений.

### 14.1 Действующий код, не дублировать

- `AuthenticatedInventoryCommands`/`InventoryAuthorizedCommandService` уже предоставляют защищённые `receive/correct/writeOff/transfer`, а также `startStocktake/recordCount/postStocktake/cancelStocktake`. `StockEligibility` делает scoped `org_location FOR UPDATE` и `cat_item FOR UPDATE` на новых командах.
- `InventoryMovementService.adjust` имеет request-stripe, replay старой проводки до eligibility, first RECEIPT zero-init, баланс `FOR UPDATE`, immutable movement; исправление не должно вводить новые таблицы stock.
- `InventoryTransferService.transfer` формирует один `crm.inv_stock_transfer` и две immutable `TRANSFER_OUT/IN` ledger legs; проверяет tenant/source/destination/item, quantity и existing transfer receipt прежде новой записи. Исторический replay разрешён после INACTIVE/CLOSED, но **новый** transfer из или в такой склад запрещается.
- `InventoryReservationService.reserve` проверяет ACTIVE WAREHOUSE/PUBLISHED PRODUCT для **новых** резервов; `release` остаётся cleanup. `fulfill` пока не имеет отдельного ACTIVE location check для anomaly-пути. Не мешать идемпотентному terminal replay.
- `InventoryStocktakeService` уже имеет OPEN/POSTED/CANCELLED с snapshot/version, posted adjustment и отменой без location eligibility. Не считать новое `OPEN` отсутствие чисто исторической записи.

### 14.2 DEC-ORG-05-A — операционная матрица

Новая запись `receive/correct/writeOff`, `transfer FROM/TO`, `reserve/confirm`, `startStocktake/postStocktake` — **только ACTIVE WAREHOUSE** в одном tenant при требуемых business permissions. `CLOSED/INACTIVE/BRANCH/POS` — DENY до mutating SQL; transfer отказывает целиком, не создает target zero stock row и движения.

Точно совпадающий старый `requestKey` для committed movement/transfer возвращает прежнюю квитанцию после IAM admission и без изменения stock **даже после CLOSED**. `cancel/release` разрешает освобождение старых резервов по статусу заказа; для legacy inconsistent `ACTIVE reservation + INACTIVE location` новую `fulfill` нужно запрещать, но terminal replay не трогать.

### 14.3 P05-01 / P0 — подтверждённый lock-order defect в текущем INV-Q7

`transfer` сортирует UUID двух складов, но затем вызывает `StockEligibility.requireReceivable(first,item)`, который блокирует `location(first)→PRODUCT(item)`; второй вызов пытается взять `location(second)`. Параллельный `receive(location(second), item)` берет `location(second)`, затем ждёт `PRODUCT(item)`. Это **возможный цикл**, не уже измеренный deadlock; существующий тест только для противоположных transfers его не устраняет.

**Исправление внутри INVENTORY, без архитектурного слоя:** сделать package-private `StockEligibility.requireWarehouses(tenant, sortedLocationIds)`, которое сначала блокирует **оба** склада в ascending UUID, проверяет ACTIVE/type, только затем один раз блокирует PRODUCT и проверяет пригодность. Далее lock stock rows в том же порядке. `request-key stripe` и committed replay остаются раньше. Для детерминированного PostgreSQL теста выбрать разные request-stripe buckets и синхронизировать транзакции барьерами. `FOR UPDATE→FOR SHARE` отдельно, только после корректностной приёмки.

### 14.4 DEC-ORG-05-B — новый blocker из INVENTORY Q8

**OPEN stocktake блокирует `ACTIVE→INACTIVE` и `INACTIVE→CLOSED`** наряду с `reserved>0` / `ACTIVE reservation`. Менеджер сначала должен POST или CANCEL инвентаризацию законной командой, чтобы не оставлять незавершенный snapshot на остановленном складе. `POSTED/CANCELLED` stocktakes — история и не блокируют. `onHand>0` без reserves и OPEN stocktake по-прежнему **не препятствует** временной паузе.

Форма порта (только признаки, без раскрытия stock details):

~~~java
public record LocationUsageBlockers(
    boolean hasOnHand,
    boolean hasReservedBalance,
    boolean hasActiveReservation,
    boolean hasOpenStocktake
) {}
~~~

**Deactivation:** reserved OR ACTIVE reservation OR OPEN stocktake → deny; onHand>0 alone → allow. **Close:** additionally onHand>0 → deny. Техническая реализация — tenant/location scoped `EXISTS` через существующие таблицы `inv_stock_balance`, `inv_stock_reservation` и `inv_stocktake`. Вызов под `org_location FOR UPDATE` после IAM WRITE, **без** `FOR UPDATE` stock/reservation/stocktake rows, иначе возникает новый обратный lock-order. `inv_stocktake_location(tenant,location,status,started_at)` уже индексирован; location-reservation index — только после EXPLAIN.

### 14.5 P05-02 / P1 и PostgreSQL qualification

Для нового `fulfill` при legacy неактивной локации выполнить проверку ACTIVE **до** stock row lock, после order row lock, сохранив старый terminal retry и cleanup release; точную форму повторов/грантов согласовать с SALES в 06/12.

Запланированные, **НЕ запускались**: `ORG-P05-Q01…Q20` в подробном ревью — INACTIVE negative cases для 5 команд; transfer source/target, rollback; mixed transfer×receive lock cycle и inverse/three-location transfers; positive stock pause; open stocktake blockers; revoke/foreign tenant; cancel/release/replay/fulfill; concurrent ORG/INVENTORY. H2 smoke недостаточен, нужны 2–3 независимые PostgreSQL sessions и exact code SHA.

**Не менять архитектуру:** никакой отдельной системы блокировок, микросервисного Warehouse, нового enum статуса или собственных ORG-инвентарных таблиц. Домен ORGANIZATION остаётся **1/5**; полноценный сервисный lifecycle ещё **NOT IMPLEMENTED**. **Следующий проход 06/12 — SALES orchestration и состояния заказов в связи с warehouse status.**


---

## 15. Дополнение после прохода 06/12 — SALES Integration & Order Lifecycle

**Полное source-reviewed ревью:** [RM-ORG-FUNC-R1-P06](../reviews/RM-ORG-FUNCTIONAL-DEPTH-PASS06-2026-11-22.md). **Runtime source SHA:** `cf0ae73c22d881d703f072afcacd7c5de8f5deff` (base_dev). **Статус:** 06/12 SPECIFIED; runtime ORG lifecycle **NOT IMPLEMENTED**, новые PostgreSQL P06 tests **NOT RUN**.

### DEC-ORG-06-A: статус склада не перезаписывает историю SALES

- Новый `confirm(PLACED,warehouse)` должен вызывать `OrderInventoryPort.reserve` для всех order lines внутри **одной** транзакции и только при ACTIVE WAREHOUSE+PUBLISHED PRODUCT; ошибку на любом SKU откатывать вместе с статусом, резервами, событием. Не дублировать `StockEligibility` вторым ORG/Sales admission port.
- `PLACED` ещё **не связан со складом**, у `crm.sales_order` отсутствует `location_id`; пока order не CONFIRMED, не утверждать наличие «заказов, ожидающих склад L». Checkout/PLACED не должен создавать статусную зависимость от warehouse.
- `ACTIVE→INACTIVE` с `onHand>0` допускается только когда `reserved=0`, нет ACTIVE reservations и OPEN stocktake; подтверждённый заказ с живыми резервами блокирует паузу. `CLOSED` только после нулевых остатков/резервов, без deletion заказа и immutable ledger.
- `cancel/release` — разрешённый по IAM cleanup исторического ACTIVE reservation даже в legacy INACTIVE/CLOSED; не автоматическая отмена при выключении склада. `NEW fulfill` с ACTIVE reservation на INACTIVE/CLOSED — deny. `COMPLETED/CANCELLED` exact terminal replays не изменяют stock.
- `CONFIRMED` replay допускается на INACTIVE/CLOSED только как **read-only** verified committed result; ни одного нового reserve/movement. Ограничиться проверкой aggregate count недостаточно.

### DEC-ORG-06-B: доказательство каждой позиции заказа

`SalesInventoryCommandService.confirm/fulfill` сегодня сверяют ACTIVE/FULFILLED reserve counts, `confirm` дополнительно только location mismatch. `lines()` получает SKU из **изменяемого** `store_offer.catalog_item_id`; `sales_order_line` не имеет защищённого immutable SKU snapshot на изученном HEAD. Согласовать ранее предложенную **SALES pass 03** additive migration `catalog_item_id_snapshot`, не делать второй snapshot в ORGANIZATION. Для каждого подтверждения, повтора и отгрузки требуется equality по `tenant/order/line/SKU_snapshot/location/quantity/state`; для COMPLETED сверять `FULFILLMENT movement`. Legacy NULL snapshot нельзя угадывать по live offer при новом confirm; историю читать с честным marker UNKNOWN.

### DEC-ORG-06-C: узкий guard существующей отгрузки

Текущий `InventoryReservationService.transition` делает `order FOR UPDATE → stock FOR UPDATE → reservation FOR UPDATE`; новая `fulfill` не проверяет ACTIVE WAREHOUSE, и terminal replay поздно выявляет terminal state (после stock lock). Для **NEW** `fulfill`: after order row lock, verify terminal replay without mutation, затем `requireExistingActiveWarehouse(tenant, location)` через tenant-scoped `org_location FOR UPDATE` (без повторного PRODUCT PUBLISHED check), затем stock/reservation row lock и атомарная запись. `release` не добавлять ACTIVE warehouse condition: cleanup обязателен даже для legacy anomaly. Не менять tenant grants или OrderInventoryPort paired IAM boundary.

**P1 least-privilege:** `cancel(PLACED)` сегодня требует `INVENTORY_RELEASE` до проверки order state. После доказательного zero-reservation при locked PLACED целевой contract — только `SALES_ORDER_CANCEL`; `CONFIRMED/PREPARING` c ACTIVE reserves требуют paired `INVENTORY_RELEASE`. Доказывать IAM lock ordering и revocation на PG; не убирать INVENTORY_RELEASE из всех cancel.

### Concurrency and rollback: обязательный инвариант

`confirm×deactivate`: confirm committed → reserved blocker запрещает деактивацию; deactivate committed → confirm откатывает PLACED; никогда CONFIRMED без full reserve. `cancel×deactivate`: cancel first releases then pause may succeed, otherwise ACTIVE reservation blocks pause. `fulfill×deactivate`: fulfill first frees reserved and may permit pause, otherwise live reservation blocks pause; manual legacy INACTIVE + ACTIVE reservation cannot be newly fulfilled. `cancel×fulfill`: one order-row winner, no both RELEASED/FULFILLED. ORG location `FOR UPDATE` then **only nonlocking EXISTS** to stock/reservations/stocktake avoids adding reverse order→location→order cycles.

**Приёмка:** `ORG-P06-Q01..Q20` в подробном документе: new confirm on paused/closed, historical replay, cancel cleanup/least privilege, new fulfill legacy anomaly, line-by-line snapshot, race ×deactivate, rollback second SKU, user A/B/AB and revoke. Это **planned tests**, не новая runtime functionality.

### Обновление source status по P05-01

P05-01 «transfer locks first location → SKU → second location» относился к исходному SHA P05. На `cf0ae73...` `StockEligibility.requireTransferWarehouses` последовательно берёт оба location locks **до SKU** и `InventoryTransferService` его вызывает; дополнительно присутствует `InventoryTransferStocktakeQualificationTest.receiptAtTransferDestinationDoesNotCycleWithWarehouseOwnerLocks`. **Считать адресную кодовую правку реализованной**, а PG-qualifying совместного `transfer×ORG.deactivate` — открытым. Не выдавать старое замечание P05 за действующую ошибку на текущем HEAD.

### Изменение очередности пользователя: переход после 07/12

После следующего прохода **07/12** (IAM/RBAC/tenant, VerifiedActor, A/B/AB, revoke race) подготовить промежуточный closure summary с актуальным code/QA evidence, unresolved risk list и конкретными задачами на внедрение. Пользователь разрешил затем перейти к новому домену **до** проработки 08–12. **08–12 → DEFERRED / NOT DONE**, а не VERIFIED или GREEN. ORG maturity остается **1/5**, пока runtime lifecycle отсутствует, независимо от числа написанных ТЗ.

**Следующий проход:** 07/12 IAM + tenant-scoped RBAC boundary; только после его фиксации сменить предметный домен по новой очередности. Ни контроллеров, ни production admission до отдельных release gates.


---

## 16. Дополнение после прохода 07/12 — IAM, Tenant RBAC & Security Boundary и промежуточное закрытие

**Детальное IAM/source ревью:** [RM-ORG-FUNC-R1-P07](../reviews/RM-ORG-FUNCTIONAL-DEPTH-PASS07-2026-11-22.md). **Промежуточное заключение с deferred backlog:** [RM-ORG-INTERIM-R1-07](../reviews/RM-ORG-INTERIM-CLOSURE-07-2026-11-22.md). **Source HEAD:** `1f24bba79b228dff7a8b4df75f0f80fd700766e0` (`base_dev`, 22.11.2026). **Вывод:** 07/12 SPECIFIED; новая функциональность и новые P07 PostgreSQL E2E не реализованы.

### 16.1 Проверенная доверенная цепочка

`OIDC JWT` проверяется через `OidcJwtConfiguration` с HTTPS JWKS/issuer/audience и `OidcActorResolver` с ACTIVE-linked user; `VerifiedActor` package-private constructor и expiry check. Конфигурация OIDC conditional, не означает готового HTTP ingress или реального deployment IdP.

`AuthenticatedOrganizationLocations` передаёт только `VerifiedActor.userId()`. `OrganizationLocationService.create` выполняет `@Transactional`, IAM `requireForCommand(...ORGANIZATION_LOCATION_WRITE)` **до** обращения к GEO/DB. `OrganizationLocationQueryService.get/search` отдельно требуют `ORGANIZATION_LOCATION_READ`. При реализации `update/deactivate/reactivate/close` использовать **эту же границу** без публичного actor UUID и без новых hardcoded доменных ролей.

`TenantAuthorization.requireForCommand` удерживает PostgreSQL `iam_user_account FOR SHARE → iam_workspace FOR SHARE`, затем перечитывает JDBC active membership и проверяет exact permission при `rbac_config.enforced=TRUE`. `TenantRoleService` mutates OWNER-only с workspace `FOR UPDATE` и append-only `iam_tenant_rbac_event`. Так коммит разрешённой команды должен предшествовать коммиту параллельного revoke, а revoke-first запрещает новую команду. `OrderInventoryPort` требует парные transaction-bound `SALES_*/INVENTORY_*` decisions одного actor/tenant.

**Existing evidence:** `TenantRbacQualificationTest`, `TenantRbacRevocationQualificationTest` (включая обе последовательности revoke-vs-admitted command), `OrganizationLocationQueryQualificationTest.postgresLocationCreationFencesConcurrentPermissionRevocation` и `tcrud03SameGlobalMemberDifferentLocationGrantsAndForeignIdDenied`, `IamUserOnboardingProcessQualificationTest`, `SalesInventoryRbacQualificationTest`, `OidcJwtCryptoQualificationTest`. **Присутствие этих тестов ≠ запуск новых P07 cases на текущем HEAD.**

### 16.2 Решения/открытые продуктовые границы

- **DEC-ORG-07-A:** tenant membership & `ORG_READ/WRITE` авторизуют только свою выбранную организацию. Один global user с A WRITE и B READ не может редактировать B и не видит чужих UUID/stock counts в A.
- **New tenant:** `PlatformTenantBootstrapService.provision` включает `RBAC enforced=TRUE` **без функциональных прав**. Путь OWNER create role → grant → invite employee → employee accept → OWNER assign → employee commands работает (сервисное существующее evidence). Публичное назначение роли себе запрещено: `TenantRoleService.assign` отвергает `userId.equals(actorId)`.
- **P07-F01 / proposed product decision, НЕ утверждённое внедрение:** solo-owner микробизнес не может прямо создать свой первый склад в enforced tenant без staff/другого утверждённого процесса. Рекомендован только **явный аудируемый OWNER self-assignment заранее существующей функциональной роли**, без `TENANT_RBAC_MANAGE` delegation и wildcard. Для этого потребуется additive audit CHECK event; не обходить текущие запреты несанкционированным grant.
- **P07-F02 / legacy transition:** `TenantAuthorization.require` имеет membership-role fallback для `enforced=FALSE` в старых tenant. Современные provisioned tenants `TRUE`; inventory+backfill и controlled enable старых tenant надо квалифицировать на populated DB, не объявлять universal exact RBAC без проверки.
- **P07-F03 / API hardening:** `TenantAuthorization.requireOrganizationWriter` public helper использует `require` без транзакционного fence; текущий CREATE его **не вызывает**. Не использовать helper в новых mutations; deprecate после проверки реальных внутренних callers.
- **Global admin:** `PLATFORM_ADMIN` не даёт tenant ORG/INVENTORY/SALES business grants; восстановление owner/tenant отдельно регулируется IAM, не переводить эти права в локальные функциональные роли.
- **Closed/INACTIVE:** `ORG_READ` не равен `INVENTORY_STOCK_READ`; cancel/release cleanup защищены действующими SALES+INVENTORY permissions, новый confirm/fulfill — active warehouse admission, без обхода IAM из-за старого заказа.

### 16.3 Приёмка и изменение календаря

**План:** `ORG-P07-Q01…Q24`: signed/forged/expired JWT, stale minted principal, invite/accept/role, A/B/AB, foreign UUID, platform admin non-bypass, account/member/tenant suspend, owner self-grant, role revoke first/command first for ORG/INV/SALES, inactive location cleanup/replay permissions, forced legacy `enforced=FALSE` cutover, immutable RBAC evidence. **NOT RUN** (новые PG tests; существующие тесты перечислены отдельно).

**Промежуточный closure:** все 07 проходов исходного анализа/ТЗ `SPECIFIED`; жизненный цикл ORGANIZATION `NOT IMPLEMENTED`, зрелость по прежней шкале **1/5**, production HOLD. **08–12** сознательно `DEFERRED / NOT DONE` и возвращаются в release backlog. Handoff к следующему предметному сервису допустим **только как переключение аудита**, не как знак зрелости ORGANIZATION. Предпочтительная кандидатура следующего обследования — CATALOG: PRODUCT state, catalog identity/offer SKU, publisher permission непосредственно влияют на INVENTORY/Sales.

**Реализация по итогам семи проходов:** `ORG-IMP-01..05`, `ORG-QA-ALL`, `IAM-DEC-01`, `IAM-QUAL-02`, `INV-IMP-01`, `SALE-IMP-01` определены в промежуточном заключении. Перед merge или production required functional CRUD lifecycle E2E, initialized/current permissions, populated PG upgrade, tests 08–12/release evidence.


---

## 17. ORG-R1.1 — начало реализации после семи проектных проходов

**Протокол и точные source paths:** [RM-ORG-IMPLEMENTATION-R1.1-2026-11-22.md](../reviews/RM-ORG-IMPLEMENTATION-R1.1-2026-11-22.md). **Состояние:** CODE COMMITTED / 12 INTEGRATION TEST METHODS / **PostgreSQL GREEN on SHA 69f757** / PRODUCTION HOLD.

### Реализованные публичные контракты на уровне service facade

~~~java
LocationDto create(VerifiedActor actor, UUID tenant, LocationCreateRequest request);
LocationDto update(VerifiedActor actor, UUID tenant, UUID location, LocationUpdateRequest request);
LocationDto deactivate(VerifiedActor actor, UUID tenant, UUID location, long expectedVersion);
LocationDto reactivate(VerifiedActor actor, UUID tenant, UUID location, long expectedVersion);
LocationDto close(VerifiedActor actor, UUID tenant, UUID location, long expectedVersion);
~~~

Старый `create(actor,tenant,type,name,kato,address)` и `get/search` сохранены, контроллеров нет. `LocationFields` нормализует поля, `OrganizationLocationRepository.lockByWorkspaceIdAndId` берёт tenant-scoped exclusive row lock, `InventoryLocationUsagePort` read-only `EXISTS` по остаткам, активным резервам, OPEN инвентаризации и истории. Каждое успешное **изменение** добавляет `ORG_LOCATION_*` в `crm.audit_event` вместе с JPA mutation, в одной transaction. No-op в актуальной версии не создаёт событие.

### Политика и остающиеся блокировки

- Положительный свободный stock разрешает temporary INACTIVE; `reserved>0`, ACTIVE reservation или OPEN stocktake блокируют. `CLOSED` требует дополнительно `onHand=0`.
- Для changed KATO добавлен консервативный guard не только stock/movement/reservation history, но и `inv_stocktake` history, предотвращая перепривязку старой инвентаризации к другой географии. Неизменённый deprecated KATO при reactivate не проверяется повторно.
- Вся мутация через tenant/actor `ORGANIZATION_LOCATION_WRITE`, без отложенного авторизационного check после обработки чужих UUID.
- **Открыто:** P05/P06 legacy fulfillment eligibility в INVENTORY/SALES, immutable SALES SKU snapshot, literal LIKE/deep paging, solo-owner IAM role UX, comprehensive 2–3 connection PG tests, populated-db migration/audit and load.
- **Статус:** 6 lifecycle methods в `OrganizationLocationLifecycleQualificationTest` и 6 real-PostgreSQL race methods в `OrganizationLocationR11ConcurrencyQualificationTest` проверены исходниками и успешно завершились в CI #44989157721. 314 test cases, 0 failures/errors, 18 skipped in PG; все 6 новых PG races executed. Production admission остаётся HOLD; не переименовывать отложенные 08–12 в DONE.

**Следующий технический шаг:** хранить шесть race tests как обязательную регрессию при изменении ORGANIZATION/INVENTORY; до расширения предметного scope выполнить отдельные populated migration/recovery/load и release-гейты. SALES/AutoOrder продолжает разрабатываться параллельно и не менялся.


---

## 18. ORG-R1.1 — PostgreSQL concurrency qualification на точном commit

**Source:** `69f757cf4d79c84d0198a6f2af432067a17007b2`, **workflow:** [#44989157721 — SUCCESS](https://github.com/alimbetov/RM/actions/runs/44989157721), **детальное evidence:** [R1.1 implementation & concurrency](../reviews/RM-ORG-IMPLEMENTATION-R1.1-2026-11-22.md). Проверены оба доменных состояния после фиксации и блокировки, не только HTTP status / thrown exception.

| Реальные новые PG тесты | Доказанный инвариант |
|---|---|
| `confirm first → deactivate` | COMMITTED reservation blocks INACTIVE, status CONFIRMED and stock reserved agree |
| `deactivate first → confirm` | INACTIVE rejects NEW reserve, seller remains PLACED, no partial events |
| `transfer first → deactivate` | 2 movement legs, conservation 10→6+4, free stock does not block temporary INACTIVE |
| `deactivate first → transfer` | denies new transfer, no stock/movement side effects |
| `update × update` on same expectedVersion | one winning profile, rowVersion increment once, exactly one audit |
| `revoke ORG_WRITE × admitted deactivate` | command commit before permission revocation; follow-up unauthorized edit denied |

**Evidence counts:** PostgreSQL integration: 314 discovered / 18 skipped / 0 failures / 0 errors; all 6 additional PostgreSQL race tests executed. H2 smoke: 314 discovered / 66 skipped / 0 failures / 0 errors; all 6 PG-only race tests deliberately skipped. Populated IAM/PII job passed; this is **not** proof of populated ORGANIZATION migration. `CI GREEN` is attached to the tested SHA, not to all later concurrent commits on `base_dev`.

**Release HOLD remains:** pass 08 literal search and pagination, pass 09 populated ORG migration/audit, pass 10 wider failure/recovery interleavings, pass 11 lock throughput/p95/p99, pass 12 full admission. Neither new SALES/AutoOrder logic nor independent legacy inactive fulfilment gate is delivered in this R1.1 qualification.
