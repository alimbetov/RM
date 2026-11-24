# RM-MVP-ONBOARD-01/R1 — XLSX-шаблоны и безопасные массовые редакции продавца

**Решение:** ТЗ принято к подготовке как **MVP Adoption / Data Onboarding**, не как дополнительная бизнес-подсистема RM.  
**Дата:** 2026-11-24. **Ветка:** `base_dev`. **Статус:** `SPEC APPROVED / IMPLEMENTATION STOP UNTIL SERVICE_FUNCTIONAL_COMPLETE@SHA`.  
**Предшествующее ТЗ:** `docs/spec/RM-MVP-SERVICE-FUNCTIONAL-CLOSURE-SIX-PACKAGES-R1.md` (W1–W6).  
**Владелец правил:** IAM (идентичность/роли), CATALOG (SKU), STOREFRONT (предложения/цены), INVENTORY (физические движения), CRM (контакты/PII).  
**Интерфейс текущего этапа:** только **Java service layer**, DTO, XLSX parser/renderer, PostgreSQL tests. REST/UI — отдельная работа G2.  
**Не входят:** собственная номенклатурная БД, direct SQL import, ETL-платформа, сторонние хранилища, массовая публикация без контроля, массовое удаление, автоматическое списание по Excel, новые CRM/IAM/SALES сущности, внешняя платёжная/FX логика.

## 0. Нормативное условие старта и завершения

**GATE-0 (обязателен до реализации):** на одной ревизии `base_dev` получен `SERVICE_FUNCTIONAL_COMPLETE@SHA` с W1–W6, восемью успешными группами CI, QA manifest, доказательствами 54 CORE-P0 и двух TASKS. При `HOLD` этот документ **не разблокирует разработку импорта и не меняет RC**.

**Главный принцип:** XLSX — только внешний формат последовательности существующих команд с уже подтверждённой авторизацией и доменными проверками. Никаких `repository.save`, `JdbcTemplate.update` или JPA `merge` из импортёра в `crm.cat_item`, `crm.store_offer`, `crm.inv_stock_balance`, `crm.customer`. **Прямые изменения базы из тестов — только подготовка/SQL oracle, не операция импорта.**

**Первое условие качества: прежде чем открывать Excel-загрузку, пройти в хвост и в гриву весь набор обычных редакций продавца через существующие сервисы.** Права/tenant/склад, цены, SKU и история должны работать одинаково как при индивидуальном изменении, так и при batch-загрузке. Если исходный сервис не проходит этот P0 gate — исправить **его точечный дефект** с тестом до подключения XLSX, а не обходить инвариант новым import API.

### Definition of Done

- `SELLER_EDIT_SECURITY_PASS@SHA`: все P0 одиночные изменения и отрицательные сценарии, tenant A/B, роли, revocation/concurrency и immutable history доказаны на PostgreSQL.
- `XLSX_TEMPLATE_PASS@SHA`: версии XLSX проверены, все строки преобразуются в точные команды, шаблоны доступны для скачивания/генерации в сервисном API без REST.
- `XLSX_IMPORT_PASS@SHA`: dry-run ничего не записывает; commit повторно проверяет права/версии; ledger и history сохраняются; точный retry не дублирует сущности/движения/цены.
- `ONBOARD_SERVICE_QUALIFIED@SHA`: оба предыдущих результата + полные позитивные/негативные PostgreSQL proof + same-SHA gate. Отдельный G2 может затем подключить HTTP/UI.
- **Production admission остаётся `HOLD`**, пока не пройдены live IAM/OIDC и эксплуатационная приёмка.

## 1. Разрешённая модель продавца, tenant и сотрудников

1. **Seller boundary = организация/tenant RM.** Товар, предложение, цены, склад, CRM и история всегда существуют в определённом tenant. `tenantId` выбирается только из серверного авторизованного контекста, который получен через IAM/membership; столбец из Excel никогда не становится источником tenantId. Даже когда сотрудник работает в двух tenant, импорт одного запуска привязан ровно к одному.
2. **Внутри tenant сотрудников делегирует owner/уполномоченный администратор через существующий `VerifiedTenantRoleCommands` / `TenantRoleService`**. Импортёр не создаёт «импорт-администратора» и не принимает `role`, `permissions`, `owner`, `actorId` или `userId` из XLSX. Он получает `VerifiedActor` от доверенной границы приложения.
3. **Проверка прав на каждой доменной операции**, а не только при загрузке файла или генерации preview. Решение о предварительном допуске не является долгосрочным capability. Смена роли, membership, account status, tenant status, warehouse assignment, price/offer version после preview должна приводить к отказу при commit.
4. **Связь со складом:** сотрудник, который фактически делает `INVENTORY.receive/correct`, должен иметь соответствующий `DomainPermission` и разрешённый `WarehouseStaffScopeService.requireAssigned` на *фактическом* warehouse. Не допускается отсутствие назначения, чужой warehouse, paused/closed location или подмена `locationId` посредством XLSX. Если текущий обычный inventory receive не проверяет assignment, это **P0 для seller-edit gate**: добавить/квалифицировать узкий mandatory enforcement в допустимой MVP-конфигурации до импорта.
5. **Дополнительный storefront внутри tenant:** `storefrontId`/`offerId` выбираются и сверяются в tenant. Общие tenant grants **не равны** праву на запись в чужой tenant; если в будущем появится storefront-granularity delegation, изменение требуется оформить отдельным решением. Здесь новый storefront ACL не изобретается.
6. **Cross-tenant отрицательный oracle:** tenant A и tenant B могут иметь одинаковые `SKU`, названия, цену, город/KATO и названия складов; это не объединяет их id. Доступ по `itemId/offerId/locationId` другого tenant запрещён, даже если значения документа идентичны.
7. **PII:** для контактов/клиентов `CRM_WRITE` не подразумевает `CRM_PII_READ`; все чтения, duplicate decisions, preview и error reports не должны показывать email/телефон сотруднику без соответствующего права. CRM импорт отложен до выполнения отдельного privacy gate.

## 2. Ролевой контракт — только существующие IAM permissions

| Роль сотрудника в пользовательской документации | Существующие точные permissions (выдаются явно владельцем tenant) | Разрешено | Запрещено |
|---|---|---|---|
| Owner/tenant-admin | `TENANT_RBAC_MANAGE` для делегирования; доменные grants проверять отдельно | назначать/отзывать роли и warehouse assignments | считать управление ролями безусловным правом изменять stock/price |
| Catalog editor | `CATALOG_WRITE`; отдельно `CATALOG_PRICE_MANAGE`; при публикации `CATALOG_PUBLISH` | новые draft SKU, допустимые редактирования/переводы, отдельные price revisions | изменять чужой tenant; писать цены без `CATALOG_PRICE_MANAGE`; auto-publish |
| Seller storefront editor | `STOREFRONT_OFFER_MANAGE` и отдельно `STOREFRONT_OFFER_PUBLISH` | редактировать предложение и цену, публиковать только с publish grant | использовать право на редактирование для публикации |
| Warehouse employee | `INVENTORY_RECEIVE` для прихода, `INVENTORY_ADJUST` только для разрешённой коррекции + точное active warehouse assignment | отразить документированный физический приход; корректировки при отдельном праве | менять баланс напрямую; списывать/переносить на другой warehouse |
| CRM employee (P1) | `CRM_WRITE`; `CRM_READ`; `CRM_PII_READ` только для разрешённого просмотра | CRM operations по действующим consent и tenant policy | массовое копирование чужого клиента, раскрытие PII в preview |
| Read-only / employee without grant | соответствующие `*_READ` | только разрешённые read-only данные/экспорт | любая команда изменения, включая `dryRun→commit` |

**Нормативные сервисы по текущему коду:**
- `CatalogItemService.createDraft/updateModelCode/upsertTranslation/addPriceRevision/publish/archive` (в каждом методе своя проверка permission и `rowVersion`); изменение `sku` опубликованного товара отдельным сервисным контрактом **не поддерживается** — импорт его отклоняет.
- `StorefrontOfferService.createDraft/updatePrice/publish/pause/hide`, `priceHistory`; публикация отдельно от price update.
- `AuthenticatedInventoryCommands.receive/correct` и `WarehouseStaffScopeService`; stock ledger — источник истины, а не значения колонок «остаток после» в шаблоне.
- `VerifiedTenantRoleCommands` / `TenantAuthorization.requireForCommand`; никогда не оборачивать эти вызовы `SYSTEM`-actor ради скорости импорта.

Реализация должна сперва проверить, что требуемые permission/warehouse checks действительно применяются внутри существующего owner service. **Если нет, это red-test + минимальный исправляющий patch; не расширять enum ролей ради XLSX.**

## 3. Обязательный gate до импорта — SELLER-EDIT-R0

**Релиз Excel запрещён, пока не выполнена полноценная матрица ручных сервисных редакций.** Требуется 3 уровня: допуск (IAM), фактическая доменная операция (CATALOG/STOREFRONT/INVENTORY) и доказательство результата (SQL oracle, audit, version, ledger).

### R0.A — каталог и предложения

- Создание draft товара; повторный SKU в том же tenant → контроль существующего источника, no duplicates; тот же SKU в tenant B разрешён.
- Изменение модели, наименования/перевода, цены каталога; проверка `expectedVersion`, immutable price history, повтор с устаревшей ревизией → fail closed.
- Публикация только отдельной командой `CATALOG_PUBLISH`. Seller editor только с `CATALOG_WRITE` не публикует.
- Создание и редакция seller offer, `StorefrontOfferService.updatePrice`, публикация только с `STOREFRONT_OFFER_PUBLISH`; accepted SALES order/quote сохраняет *старую согласованную* цену и не пересчитывается при последующем изменении.
- Скрытие/пауза предложения и storefront: нельзя выдавать новому покупателю ошибочную текущую цену или реанимировать старый QR.
- Два сотрудника одновременно правят тот же price/offer `rowVersion`: ровно одна revision; второй получает conflict, без двойного audit/event.

### R0.B — tenant и сотрудники

- 2 tenant A/B, по два склада и две витрины; один сотрудник tenant A, второй tenant B, третий состоит в обоих с **разными** permissions.
- Точное совпадение SKU, имени, цены и адреса склада в двух tenant не даёт связи между записями; все mass read/edit результаты помечены серверным tenant, и в БД нет tenant-cross FK.
- Отзыв domain permission, role, account, membership **до commit**: 0 записей; отзыв **после зафиксированного commit**: прежнее корректное изменение сохраняется, следующая команда отвергается.
- Злоумышленное значение `tenantId`, `warehouseId`, `offerId`, `actorId`, `roleCode` в workbook/malformed DTO не меняет серверный security context; opaque foreign IDs fail closed без раскрытия существования объекта.
- Warehouse сотрудник с одним назначенным складом не изменяет другой; revoke assignment × receive/correct одновременно в обоих порядках сериализуются.

### R0.C — INVENTORY

- Приход через сервис создаёт **один** stock movement и версионирует позицию; exact retry не создаёт второй приход.
- Коррекция отличима от начального прихода и требует отдельного права; Excel-значение «остаток 100» **никогда** не интерпретируется как инкремент `+100` без явного режима/основания.
- Нельзя сделать отрицательный sellable, обнулить чужой резерв, подменить источник движения, списать ранее выданное или дважды провести одинаковый исходный документ.
- Получение/корректировка против stocktake, location deactivation, assignment revoke и inventory reconciliation → проверенные PostgreSQL commit orders, no deadlock, one immutable ledger.
- Ограничение одного физического склада на заказ остается; загрузка не создаёт `SALES`-заказы и не резервирует товары.

**Выход R0:** `SELLER_EDIT_SECURITY_PASS@SHA` только при полном наборе P0 PostgreSQL тестов с 0 skips, независимыми `SELECT`-oracles, оценкой lock ordering и подтверждённой неизменностью исторических order/price snapshots.

## 4. Версии XLSX шаблонов и границы данных

**R1 scope, в этом порядке:**

| Код | Шаблон XLSX | Минимальные столбцы | Действие |
|---|---|---|---|
| `RM-CAT-XLSX-01` | Номенклатура | `sku`, `kind=PRODUCT`, `sourceLocale`, `title`, `modelCode?`, `operation=CREATE/UPDATE`, `expectedVersion?` | `CatalogItemService`; только draft/edit |
| `RM-PRICE-XLSX-01` | Каталожные цены | `sku`, `unitPrice`, `currency=KZT`, `effectiveAt`, `expectedVersion`, `operation=ADD_REVISION` | `CatalogItemService.addPriceRevision` |
| `RM-OFFER-XLSX-01` | Предложения продавца | `sku`, `storefrontCode` (разрешённый в tenant), `title`, `unitPrice`, `currency=KZT`, `operation=CREATE_DRAFT/UPDATE_PRICE`, `expectedVersion?` | `StorefrontOfferService`; без silent publish |
| `RM-STOCK-XLSX-01` | Начальные поступления склада | `sku`, `warehouseCode`, `quantityDelta` (>0), `businessDocumentRef`, `operation=RECEIVE` | `AuthenticatedInventoryCommands.receive` |

`RM-CRM-XLSX-01`: компании/контакты **P1 после** отдельного PII/consent/duplicate privacy gate; не обязательный критерий первого XLSX релиза. Для первого пакета не включать импорт IAM пользователей, employee membership или roles через Excel — сотрудников создает/делегирует только штатный IAM.

**Общие правила шаблонов:**
- Название файла произвольное, содержимое `.xlsx` с листом `RM_DATA` и машинно-читаемой версией `templateId/templateVersion=1` в отдельно закреплённой metadata области/листе. Нельзя полагаться на русский заголовок как на schema version.
- Пользовательские подписи `ru/kk/en` допустимы; машинные ключи столбцов, перечисления и форматы чисел/дат неизменны. Отдельный лист с примерами и справочником допустимых значений; никакого макроса/VBA.
- Предпочитать точный `sku` + серверный tenant; для правки существующего товара/предложения — искать канонический `id` **внутри tenant**, использовать обязательный `expectedVersion`. Не подменять ID названием/совпадением локализованного наименования.
- Для цены KZT зафиксировать поддерживаемую БД шкалу `BigDecimal` и точность без `double`; запрещены неявная конвертация FX, отрицательная цена, округление без ошибки и валюты кроме KZT.
- Исторические цены, одобренные quotes, order line snapshots не переписывать; операции `UPDATE` требуют explicit user acknowledgement.
- Для stock `warehouseCode` должен однозначно разрешаться в один active `org_location` текущего tenant, иначе ошибка строки. Business document reference становится частью `requestKey`-identity; табличная «остаток на дату» не является инструкцией прямого UPDATE.
- Установить начальные ресурсоограничения: **до 5 MiB, 1000 data rows на файл, до 50 колонок, 1 рабочий лист + metadata/examples**. Это проектные максимумы, уточняемые тестом; превышение → отказ до side effects.

## 5. Публичный контракт сервисного import adapter (не REST)

Рекомендуемый минимальный интерфейс `SellerSpreadsheetImportService` и `SellerSpreadsheetTemplateService`:

`Template exportTemplate(VerifiedActor actor, UUID selectedTenant, TemplateId id, Locale locale)`  
`ImportPreview validate(VerifiedActor actor, UUID selectedTenant, TemplateId id, byte[] xlsx)`  
`ImportResult apply(VerifiedActor actor, UUID selectedTenant, ImportPreviewToken previewToken, String requestKey)`

Это **концептуальные сигнатуры**, их можно конкретизировать при реализации. `selectedTenant` всегда авторизуется через IAM tenant membership; при передаче из будущего UI он должен совпасть с доверенным selected-tenant context. `previewToken` — непрозрачный, ограниченный по времени идентификатор **проверенных данных и security context**, а не разрешение на запись.

**Двухфазный процесс:**

1. **Parse:** защитить XLSX-загрузку от zip bombs, огромных shared strings/rows, external links, embedded formulas/macros; обрабатывать как данные, **не вычислять формулы** и не раскрывать содержимое наружу. UTF-8/unicode safety. POI — только если его версия уже входит в подтверждённый dependency baseline; если нет, явно квалифицировать новую зависимость и её ресурсные лимиты.
2. **Validate/dry-run:** прочесть **все** строки, нормализовать SKU/currency/date/quantity, проверить tenant и employee grants, warehouse scope, business invariants, duplicate SKU и повторные строки внутри workbook. **Ноль бизнес-записей**, включая no staging в CRM/CATALOG/INVENTORY. Выдать `rowNo,field,code,severity` и сводку `CREATE/UPDATE/REJECT/UNCHANGED`.
3. **Preview snapshot:** связать `tenantId,actorId,templateId,templateVersion,fileSha256,rowSetDigest,operation,expectedVersions,sourceWarehouse`. Предусмотреть краткий TTL и защищённый от replay binding; хранение в рамках синхронного сервиса до реализации persistent asynchronous batch. Не делать из `previewToken` автономный bearer для чужого аккаунта.
4. **Apply:** проверять IAM account/membership/grants и warehouse assignment **заново внутри пишущих транзакций**, затем SKU/offer/price versions и KZT policy; сортировать locks по каноническим `tenant→catalog item→offer→warehouse` с учетом реального DAG отдельных сервисов. **Не** создавать внешнюю транзакцию, которая противоречит текущему lock order. Явно согласовать packet granularity с уже принятыми сервисными методами.
5. **Idempotency:** повтор `requestKey` + тот же `tenantId/actorId/fileSha256/templateId/operation` возвращает подтверждённые результаты без повторных price revisions/receipts/stock movements. Другой payload/actor/tenant с тем же ключом → deny/conflict. Если текущие сервисы не обеспечивают такую атомарность на уровне batch, допустим **только минимальный технический receipt/idempotency registry** (additive Liquibase, tenant qualified, без дублирования товаров/остатков), после отдельного PostgreSQL crash/retry доказательства. Не утверждать «идемпотентно», полагаясь только на совпадение SKU.
6. **Partial success policy:** для первого выпуска **all-or-nothing для одного файла** по бизнес-операциям; если один commit fails/revoke/version conflict, весь файл rollback. Если существующие сервисы используют независимые транзакции или `REQUIRES_NEW`, сперва доказать атомарность; если невозможно — отказаться от batch apply, ограничиться 1-row commit с отдельным явным подтверждением, **не обещать ложно общий rollback**.
7. **Result/audit:** результат ограничен tenant и actor visibility: input row number, операция, публичный/внутренний ID в разрешённом контексте, status, count, diagnostic code, `committedAt`, receipt reference. Не писать raw email/телефоны/секреты в файл ошибок или логи; не показывать названия чужих объектов в отказах.
8. **Export:** только разрешённые записи текущего tenant/employee; экспортировать действующий `rowVersion`, SKU и предложение для последующего редактирования, защищая spreadsheet injection (любая пользовательская строка начинающаяся с `=,+,-,@` — escaped to literal text), сохранять шаблон и локаль. Export не делает read bypass `CATALOG_READ`/STOREFRONT/PII.

## 6. Приёмочная матрица безопасности — R0 и R1

Минимальная P0 matrix для PostgreSQL, без `@Disabled` и `Assumptions` в обязательных случаях:

| ID | Атака/сценарий | Обязательное доказательство |
|---|---|---|
| `SEC-01` | tenant A employee импортирует `sku`/`offerId` tenant B | deny, B rowVersion и history не меняются |
| `SEC-02` | A/B содержат одинаковые SKU, price, warehouse names | только A меняется; B hash/ledger untouched |
| `SEC-03` | Сотрудник состоит в A и B, но имеет разные grants | выбор A/B не повышает разрешения |
| `SEC-04` | Только `CATALOG_WRITE` пытается импортировать price | deny без price revision |
| `SEC-05` | Только `STOREFRONT_OFFER_MANAGE` пытается publish | deny без state transition |
| `SEC-06` | Read-only employee вызывает `apply` | deny; даже успешный preview не даёт права записи |
| `SEC-07` | Owner `TENANT_RBAC_MANAGE` без необходимого domain grant | запрещённое изменение не выполняется |
| `SEC-08` | Складский сотрудник без assignment/с чужим warehouse | deny, onHand/reserved/ledger unchanged |
| `SEC-09` | Сотруднику отзывают роль после dry-run, до apply | no writes |
| `SEC-10` | `apply` гоняется с role/account/membership/warehouse revoke, оба commit orders | согласованная сериализация, no stale authorization |
| `SEC-11` | Два сотрудника одновременно меняют одну цену с одинаковым expectedVersion | одна новая revision, второй conflict |
| `SEC-12` | Продавец правит цену во время подтверждения QR/торга | неизменность accepted quote и order snapshot |
| `SEC-13` | Изменение цены/offer в tenant A не влияет на чужой опубликованный QR | B snapshots unchanged |
| `SEC-14` | У одного SKU две разные строки price в workbook | детерминированный conflict до записи |
| `SEC-15` | Один requestKey повторяется после timeout/lost response | zero duplicate SKU/revisions/movements |
| `SEC-16` | Тот же requestKey с другим fileSha256, actor или tenant | deny, no information leakage |
| `SEC-17` | Новый fileSha256 с устаревшим price/catalog version | stale conflict, no writes |
| `SEC-18` | Отрицательные qty, запрещённые KZT/FX, неверные KATO/warehouse | fail closed, full rollback |
| `SEC-19` | Файл имеет external links, formula injection, zip bomb, macros/invalid type | отказ до бизнес-записей |
| `SEC-20` | Customer/PII в Excel без CRM permissions | denied preview/export и no log PII |
| `SEC-21` | Второй склад закрывается между preview и import receipt | no wrong-location posting |
| `SEC-22` | Batch transaction падает после середины (exception/crash) | no orphan draft/offer/price/stock movement |
| `SEC-23` | Повторный import при изменённом KZT tenant policy | re-admission и fail-closed |
| `SEC-24` | Экспорт содержит строку `=HYPERLINK(...)`, затем повторный импорт | Excel воспринимает строку как текст; no formula execution |

**Тесты реального пользовательского поведения редактора (не только парсера):**
- `EDIT-01` сотрудник создаёт draft SKU → меняет описание → price revision → публикует под отдельным grant; SQL state/history соответствует каждому шагу.
- `EDIT-02` seller изменяет offer price, у существующего заказа остаётся старая agreed price; новый verified checkout должен подтвердить новую цену.
- `EDIT-03` warehouse employee выполняет физический приход, второй сотрудник с read-only не может исправить остаток.
- `EDIT-04` один seller редактирует 1000 допустимых строк, один ошибочный SKU → корректный запрет всего apply, без half-import.
- `EDIT-05` при редактировании одного товара разными сотрудниками replay и CAS гарантируют единственную победившую ревизию.
- `EDIT-06` экспорт → изменение разрешённых колонок → dry-run → apply → повторный экспорт: содержимое и history согласованы, identity/tenant неизменны.

**Требование по доказательствам:** каждая строка `SEC/EDIT` должна иметь исполняемый JUnit method, независимый SQL oracle, exact SHA, Surefire XML, 0 skipped; переиспользование уже работающих тестов допускается только при точном совпадении утверждаемого инварианта. Не увеличивать счётчик тестов ради вида.

## 7. Исполнимая очередь и stop rules

| Порядок | Ticket | Выходной результат | Gate |
|---|---|---|---|
| 0 | `ONBOARD-G0` | W6 завершён по существующему ТЗ, backend зафиксирован | `SERVICE_FUNCTIONAL_COMPLETE@SHA` |
| 1 | `ONBOARD-R0` | Security/tenant/RBAC/warehouse + seller-edit concurrency proof **без XLSX** | `SELLER_EDIT_SECURITY_PASS` |
| 2 | `ONBOARD-R1A` | Генерация 3–4 версионированных шаблонов, строгий parser и DTO validation | `TEMPLATE_PASS` |
| 3 | `ONBOARD-R1B` | Dry-run, preview, CAS, repeat admission, tenant-owned diagnostics | `PREVIEW_PASS` |
| 4 | `ONBOARD-R1C` | Коммит через действующие сервисы, receipt/retry/rollback/ledger proof | `IMPORT_PASS` |
| 5 | `ONBOARD-R1D` | Security matrix + 1000-row stress + populated PostgreSQL + exact-SHA | `ONBOARD_SERVICE_QUALIFIED` |
| позже | `ONBOARD-R2` | CRM customers/contacts после privacy/consent gate, API/UI в отдельном G2 | `POST INITIAL XLSX MVP` |

**Стоп-условия без обсуждения:** затронуты чужой tenant, неверный warehouse, privilege escalation, двойная price revision/stock movement, повреждение accepted price/order, утечка PII, игнорирование role revocation, full-rollback promise без доказательства, пропущенный обязательный PostgreSQL test. Любой случай → `HOLD` и исправление минимального owner-contract; не «временно открыть импорт».

## 8. Архитектурные решения, которые не пересматриваем

- **Не** создавать новый универсальный источник истины или общую `ImportEntity` бизнес-домена.
- **Не** вводить `IMPORT_ALL` permission. Использовать точный пересекающийся набор существующих `DomainPermission` для каждой строки/операции; прямое право сотрудника может быть меньше, чем у другого в том же tenant.
- **Не** запускать full import с системной/администраторской identity вместо employee `VerifiedActor`.
- **Не** добавлять REST/UI и отдельную очередь в этом ТЗ; разработка будущего транспорта не должна ослабить ролевой контракт.
- **Не** менять структуру заказа, negotiated quote, pickup/return, ledger, PII/consent или валютную политику.
- **Не** принимать XLSX layout за разрешение сделать операцию, которой нет в текущем сервисе. Такую колонку/действие отклонять.
- **Не** считать документ утверждением фактического `ONBOARD_SERVICE_QUALIFIED`; статусы меняются только после исполняемых CI и артефактов.

**Итоговое решение архитектора:** функциональный RM MVP закрывается прежним W6; после этого отдельный ограниченный seller-onboarding extension начинается **с жёсткой проверки существующих редакций продавца и tenant employee RBAC**, и только затем подключает XLSX-шаблоны, dry-run и commit через существующие DTO и сервисные команды.
