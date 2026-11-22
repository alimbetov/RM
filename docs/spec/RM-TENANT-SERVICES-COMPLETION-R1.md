# RM-TENANT-SERVICES-COMPLETION-R1 — согласование и развитие бизнес-сервисов

**Тип:** комплексное ТЗ / dependency-aware implementation backlog (service-first)  
**Дата:** 2026-11-22  
**Репозиторий:** `alimbetov/RM` · **ветка:** `base_dev`  
**База ревью:** `154acf8cda2f989d2f51c14b668d887b44aae4ef` (срез перед созданием ТЗ); актуальность кода и тестов перепроверять на SHA реализации.  
**Статус:** `PROPOSED FOR PHASED IMPLEMENTATION`; соглашение о границах и предлагаемых контрактах, **не** свидетельство реализованности, прохождения CI или утверждения открытых бизнес-решений.  
**Внешний доступ:** HTTP/REST/OIDC, `main` merge и production **HOLD**. Реализация — внутренние Java service/application facades, Liquibase, PostgreSQL qualification.

## 0. Назначение и нормативная иерархия

Цель — сделать RM реально работающей CRM-first мультитенантной бизнес-платформой: владелец организации может организовать процессы от клиента и задачи до товара, заказа, исполнения, файла и счёта; сотрудники работают в **своём явно выбранном tenant** только с разрешёнными функциями и объектами. Не превращать домены в стандартный CRUD над таблицами.

**Источники, обязательные при реализации:**
1. `docs/architecture/RM-PLATFORM-STANDARD-R2.md` — единственный нормативный стандарт идентичности, RBAC, доменных границ, DTO и security;
2. `docs/spec/RM-TENANT-CRUD-COMPLETION-R1.md` — TCRUD-00/02/03 и gap-based критерии без дублирования готового кода;
3. `docs/spec/RM-SVC-R2-CONSOLIDATED-IMPLEMENTATION-PLAN.md` и `RM-SVC-R2-SERVICE-COMPLETION-CROSS-DOMAIN-CONSISTENCY.md` — hybrid JPA/JDBC и междоменные инварианты;
4. `docs/spec/RM-SVC-R2A-FILTERING-PAGINATION-CONTRACT.md` — фильтры, порядок, cursor;
5. `docs/spec/RM-CRM-PERSIST-R1.2-CATEGORIES-CONTRACTS.md`, `RM-CRM-R1.3-CUSTOMER-CONTRACT-CRUD.md`, `RM-CRM-R1.4-CONTACT-CRUD-LIFECYCLE.md` — действующие CRM lifecycle;
6. `docs/reviews/RM-TCRUD-00-METHOD-INVENTORY-2026-11-22.md` и `RM-TCRUD-03-TENANT-ADVERSARIAL-2026-11-22.md` — текущие методные данные/неполная cross-tenant квалификация.

**В случае расхождения:** R2 стандарт имеет приоритет; старые PROPOSED схемы R2B не заменяют уже реализованные R1.2/R1.3 сущности. Настоящее ТЗ **не уполномочивает** код менять принятые security-правила; решения с меткой `DECISION REQUIRED` оформляются поправкой к R2 или отдельным утверждённым предметным контрактом **до** Java/DDL.

**Технологии:** Java 21, Spring Boot 4.1.3, PostgreSQL/Liquibase; модульный монолит. JPA для изменяемых агрегатов, если нужен `@Version`, JDBC/native SQL для поиска и критичных транзакционных путей. Не переводить без причины работающий JDBC код в JPA, не создавать микросервисы, Redis, «generic CRUD engine», глобальный ABAC/workflow framework, контроллеры или новые ветки.

## 1. Актуальное фактическое состояние (не roadmap)

| Контур | Реальные внутренние элементы в `base_dev` | Ограничение |
|---|---|---|
| IAM / OWNER | `TenantAuthorization`, `TenantRoleService`, `PlatformTenantBootstrapService`, `MyAccessService`, `VerifiedTenantRoleCommands`, status/transfer/recovery | OWNER — неделегируемое RBAC administration, не wildcard бизнес-доступ; self-assignment роли запрещён |
| CRM | `AuthenticatedCrmCommands/Queries`, `CrmCustomerService`, `CrmCategoryService`, `CrmContractService`, `CrmHistoryService`, `CrmSearchService` | Customer/Contact update/archive/restore и Category/Contract **уже есть**; полного interaction pipeline и части фильтров нет |
| ORG | `AuthenticatedOrganizationLocations` с create/get/search | Нет квалифицированного edit/deactivate/restore |
| SALES | `AutoOrderService` checkout, `AuthenticatedSalesCommands` confirm/cancel/fulfill, bounded search | Staff draft/detail/history, CRM↔SALES Customer mapping не завершены |
| INVENTORY | `AuthenticatedInventoryCommands` receive/correct, reservation/fulfill/release, stock query | Нет полного transfer/stocktake и per-location scope |
| FILES | upload intent/completion, scanner, LOCAL/RustFS, typed CUSTOMER/COMPANY attachments, binary read/recovery | Не делать generic fileId read; другие owner types требуют новой policy |
| CATALOG | `CatalogItem` domain record; `crm.cat_item`, `crm.cat_item_i18n`, `crm.cat_price`, публикация существующего store offer | Нет квалифицированных CATALOG service facades и permissions |
| GEO / KMS / AUDIT | KATO references; tenant crypto; доменные события; `AuditRetentionService` | GEO — глобальный справочник, KMS rotate закрыт; audit query не равен retention worker |
| TASKS / FINANCE / MARKETING / SUBSCRIPTION | records/enums/доменные контракты | Профессиональные service + persistence вертикали ещё не заявлены как готовые |
| STOREFRONT | Existing storefront/offer tables и public checkout | Нет полноценного tenant management facade |

**Статус:** `PRESENT` означает наличие кода, а не `QUALIFIED` на всех tenant/role/negative случаях. `TCRUD-03` на дату baseline имеет статус **PARTIAL**. Новый код не дублирует существующие CRM/FILES/IAM транзакционные функции.

## 2. Единая модель tenant и бизнес-прав

1. `tenantId = public.iam_workspace.id`; `organizationId` связан с workspace, но не является его идентификатором. `userId`/внешний OIDC subject глобальны; KATO/warehouse/region/project/customer — **не** tenant.
2. Application entrypoint принимает `VerifiedActor actor`, выбранный `UUID tenantId`, типизированные параметры; никому нельзя доверять `actorId`, `tenantId` из непросмотренного тела HTTP, скрытому header или UUID объекта. Реальный transport — отдельный stage.
3. Для **каждого** действия: active account + workspace + membership, потом `DomainPermission`, потом object/state/owner policy. Бизнес-мутация — `TenantAuthorization.requireForCommand` в транзакции с действующим lock-order/revocation-fence.
4. Tenant-owned root запрос использует `WHERE tenant_id=?`, tenant-owned JOIN — `child.tenant_id=parent.tenant_id`; локальный FK `(tenant_id, parent_id)` и tenant-local unique business code. ORG location использует `workspace_id`.
5. `CRM_READ/CRM_WRITE` никогда не подразумевает `CRM_PII_READ`; FILES требует и FILES-permission, и право на **объект-владелец**. Ошибки, DTO, count, cursor, event, storage I/O не раскрывают чужие записи/секреты.
6. В рамках текущей политики функциональное разрешение действует **tenant-wide**. Портфель «только мои клиенты» и склады «только закреплённые» — дополнительные, несуществующие пока object-level политики: `DEC-SCOPE-01/02`; не обещать их из одного tenant ID.
7. Бизнес-роли конфигурируются организацией (директор/менеджер/бухгалтер/кладовщик). Новый permission **deny by default** до явного grant и активации; нельзя выдавать новые разрешения всем существующим владельцам молча. `TENANT_RBAC_MANAGE` только OWNER, не делегировать.
8. Системные процессы (аудиторское удержание, отправка, KMS, migration) требуют ограниченной service identity, явно заданного tenant context или отдельной платформенной операционной политики. Глобальный `PLATFORM_ADMIN` не читает автоматически бизнес CRM.
9. Защита PII — tenant key + AAD для CRM; глобальный IAM email/profile защищён отдельным IAM crypto. Не сохранять свободный текст обращений, комментарии задач, тела email или адреса в plaintext/audit без отдельной PII classification и шифрования.
10. Отказ, retry, revoke, stale version и race должны быть **fail closed** без частично сохранённых предметных эффектов.

### 2.1 OWNER bootstrap — блокирующее решение, не разрешение на wildcard

`PlatformTenantBootstrapService.provision` создаёт OWNER membership, а `TenantRoleService.assign` запрещает self-assignment. Применить один из вариантов **после** явного продуктового решения:
- **A — без изменения политики:** первый OWNER только администрирует роли и назначает сотрудников; для собственного доступа требуется надёжный отдельный утверждённый путь.
- **B — рекомендуемый:** доверенный one-time bootstrap создаёт **конечную** функциональную роль и назначает первому OWNER заранее согласованный список неперсональных бизнес-permissions; это атомарно, идемпотентно, аудитируемо, без общего wildcard, без автоматического `CRM_PII_READ`, KMS и PLATFORM_ADMIN. Нужна поправка к R2 и тестовый gate для запрета самоназначения вне bootstrap.
- **C — ручное self-assignment** только по отдельному утверждённому security контракту; не менять текущий `assign` напрямую.

До решения оставить текущую self-assignment policy, **не внедрять** OWNER allowAll. У владельца остаётся рабочая возможность управлять сотрудниками, а собственное операционное получение прав требует принятого процесса.

### 2.2 Планируемые DomainPermission (ещё НЕ реализованы)

| Контур | Предлагаемые коды | Уточнение |
|---|---|---|
| CATALOG | `CATALOG_READ`, `CATALOG_WRITE`, `CATALOG_PRICE_MANAGE`, `CATALOG_PUBLISH` | Существующий enum их не содержит. Grant только на tenant роли; PUBLISH отдельно от WRITE |
| CRM Interaction | `CRM_READ`, `CRM_WRITE`, `CRM_PII_READ` по полю | В R1 не заводить новый interaction-permission без доказанной необходимости |
| TASKS | `TASK_READ`, `TASK_WRITE`, `TASK_ASSIGN` | Assigned user обязан состоять в том же tenant |
| STOREFRONT | `STOREFRONT_READ`, `STOREFRONT_MANAGE`, `STOREFRONT_PUBLISH` | Только staff-путь; public browsing/checkout не требуют tenant staff роли |
| AUDIT | `AUDIT_READ` | Только очищенная tenant-история, без системных retention/hold управлений |
| FINANCE | `FINANCE_INVOICE_READ`, `FINANCE_INVOICE_WRITE`, `FINANCE_PAYMENT_RECORD`, `FINANCE_ALLOCATION_MANAGE` | Платежи — **запись факта**, не платёжный шлюз; суммы/валюты по policy |
| MARKETING | `MARKETING_READ`, `MARKETING_WRITE`, `MARKETING_SEND` | SEND не отменяет действующее CRM согласие/отзыв |
| SUBSCRIPTION | `SUBSCRIPTION_READ` и отдельный **platform-governed** plan assignment | Tenant OWNER не вправе сам выдавать себе оплаченные entitlements |
| ORG/SALES/INVENTORY/FILES | Сохранить существующие `*_READ/WRITE`, `SALES_ORDER_*`, `INVENTORY_*`, `FILES_*` | Не добавлять новое разрешение, если уже существующее однозначно покрывает операцию |

**Admission rule:** новые коды добавлять **в том же инкременте**, что и проверенный бизнес-сервис; для старых tenant — явная миграция/выдача grants, а не auto-allow. `TENANT_RBAC_MANAGE` не включать ни в один функциональный шаблон. Каталог permission и входы не появляются от наличия enum/Entity.

## 3. Контракт сервисов и persistence

Для каждой новой tenant-owned команды:
- Только доверенный application facade `VerifiedActor + tenantId + typed Command`; raw package-private worker/DAO никогда не является transport entrypoint.
- Create возвращает **минимальный** `id,status,rowVersion`, без PII; read проходит независимый read permission. Не возвращать JPA Entity напрямую.
- Для изменяемого агрегата `expectedVersion` / CAS; `NOT_FOUND`, `DENIED`, `CONFLICT`, `INVALID_STATE`, `VALIDATION`, `RETRYABLE` и `IDEMPOTENCY_CONFLICT` разграничиваются внутренними typed exceptions без подсказок о чужом ID.
- Domain transitions вместо универсального update всех полей. Изменение прав/статуса/цены/суммы сопровождается immutable event/revision с actor, tenant, occurredAt; никакого plaintext PII в audit.
- Retryable append/payment/stock/dispatch/checkout: `(tenant_id, request_key, canonical payload fingerprint)` + неизменяемый receipt; один requestKey с иным payload = конфликт. Для глобального ID/онбординга — отдельный канонический scope.
- New Liquibase только **additive**, с unique/composite FK, индексами по `(tenant_id, status, created_at, id)` согласно реальным фильтрам. Применённые changesets не изменять. Заполненная база и old-schema migration — отдельный gate.
- `Page` / existing bounded `page/size` допустимы как совместимость (1..100), сортировка стабильна; новые cursor — R2A, привязка token к tenant/user/scope/filter и повторная авторизация на каждой странице. COUNT не равен консистентному экспортному snapshot.
- Транзакция — короткая; внешние email/storage/scan операции вне долгой DB row lock, с последующей повторной проверкой entitlement/ownership и атомарной фиксацией результата.
- Никаких JOIN, передающих одну `tenant_id` из непроверенного внешнего owner ID; глобальные IAM/GEO справочники имеют собственную область владения.

## 4. Матрица согласованности между доменами

| Источник истины | Кого использует | Правило взаимодействия |
|---|---|---|
| IAM: `user/membership/role/grant` | все staff services | `VerifiedActor`, tenant choice, active membership/permission, revocation fence |
| CRM: `customer/contact/category/contract/interaction` | TASKS, FILES, SALES, MARKETING | Узкий CRM read/owner-access port; никакого независимого `customer` clone |
| TASKS: `task/assignment/event` | CRM Interaction, SALES | Хранит `targetType,targetId` и tenant; validate target через типизированный owner-port; не меняет CRM напрямую |
| CATALOG: `item/SKU/i18n/price revision` | STOREFRONT, SALES, INVENTORY, FILES | Publish snapshot через explicit port; не менять history цены после заказа |
| STOREFRONT: `front/offers/publication` | public checkout (`AutoOrderService`) | Public slug — маршрутизация, не право на чужие приватные данные; опубликованные офферы только своего tenant |
| SALES: `order/line/status/price snapshot` | INVENTORY, FINANCE, TASKS | Confirm/cancel/fulfill через уже существующие dual-permission ports; `buyer_user_id != crm.customer_id` |
| INVENTORY: `balance/reservation/movement` | SALES, CATALOG | Stocks не CRUD; transfer/stocktake только через атомарные движения с row locking |
| FILES: `object/storage/attachment` | CRM, CATALOG, SALES, TASKS | FILES checks + typed object access; никаких произвольных `ownerType+ownerId` обходов |
| FINANCE: `invoice/payment/allocation` | SALES, CRM | Invoice хранит зафиксированные суммы/валюту/заказ; фактическую оплату не выводить из `SALES.COMPLETED` |
| MARKETING: `campaign/send attempt` | CRM Consent, FILES templates | Проверка consent на отправку (purpose/channel/policyVersion) и повторная проверка при revoke; fail-closed |
| AUDIT: `event/retention/hold` | все домены | domain audit в owner aggregate; tenant-scoped sanitized query, retention по отдельной системной политике |
| SUBSCRIPTION: `plan/entitlement/tenant assignment` | все функции | Feature entitlement **дополняет**, но не выдаёт `DomainPermission` |
| ORG: `location` / GEO: `KATO` | INVENTORY, SALES, CRM | Локация — tenant-owned, KATO — global read-only reference; закрытие склада требует проверки зависимостей |

**Запрещённые shortcut:** прямые domain-cross SQL UPDATE, переименование legacy таблиц ради стиля, два разных источника цены, конструирование CRM Customer из IAM buyer, разрешение FILES только по fileId, немедленная «оплата» на SALES fulfillment.

## 5. Сервисные задачи R1 — детализированный контракт

### SVC-CRM-01 — завершить существующие CRM read/lifecycle (P1, доработка)

**Не создавать заново:** Customer/Contact CRUD, Category, Contract, PII, история, archive/restore уже существуют.  
**Добавить:** поиск клиентов с `primaryCategory` и рассчитанным `contract expiry/status asOf` без N+1; фильтрацию по периодам и typed criteria согласно R2A; status/archived semantics. Сохранять один primary category на customer, несколько Contract на customer, текущие exact expiry/inclusive `LocalDate` решения. **Не** считать archived contact активным, не восстанавливать consent автоматически.

**Acceptance:** A/B tenant + одинаковые category/contractCode; tenant-safe JOIN, count/page, CRM_READ denial, PERSON PII masked, exact asOf date; concurrency create contract vs archive customer; no new table duplicate.

### SVC-CRM-02 — `CrmInteractionService` (P1, новая минимальная вертикаль)

Модель `CrmInteraction` / `CrmInteractionEvent` (tenant_id, id, customer_id, optional contact_id, kind, status, responsible_user_id?, occurred_at, next_action_at?, row_version, created_by). В R1 ограничить виды `CALL/MEETING/EMAIL/NOTE` и статусы `OPEN/COMPLETED/CANCELLED` (согласовать enum с реальными UX до миграции).

Операции: `record`, `get`, `search(customerId/date/responsible/status)`, `assignResponsible`, `setNextAction`, `complete`, `cancel`, `history`. Ответственный — активный member **того же tenant**, проверенный IAM. Все связанные Customer/Contact — того же tenant, при записи только ACTIVE, при чтении исторических объектов соблюсти object policy.

**PII:** свободный текст/notes либо encrypted tenant PII с AAD и отдельным `CRM_PII_READ` для возврата, либо не входит в первый релиз. Нельзя писать свободный текст в audit, `description` metadata или глобальные task refs.  
**Связь с TASKS:** в R1 можно сохранить `nextActionAt`, затем создавать отдельную TASK через task-owned port в согласованной транзакции; не создавать две конкурирующие сущности задачи. `responsible_user_id` в R1 — назначение ответственности, **не** автоматическая политика `only my customers`.

**Acceptance:** две организации, actor AB, чужой contact/assignee reject, archived owner reject, revoke race, stale version, no duplicate event, encrypted note denial, history tenant scoped.

### SVC-TASK-01 — `TaskService` (P1, новая вертикаль)

Модель: tenant-owned `Task(id, tenant_id, target_type, target_id, assigned_to, created_by, status, due_at, priority, version)` и append-only `TaskEvent`. Первый target allowlist — `CRM_CUSTOMER` / `CRM_INTERACTION`; расширение SALES отдельно.

Команды: `create`, `assign`, `reschedule`, `start`, `block`, `complete`, `archive`, `get`, `search(assignee/status/due/target)`, `history`. Переходы: `OPEN→IN_PROGRESS/BLOCKED/COMPLETED`, `IN_PROGRESS↔BLOCKED`, `OPEN/IN_PROGRESS/BLOCKED→COMPLETED`, `COMPLETED→ARCHIVED`; re-open только отдельным решением. Удаление истории запрещено.

`TASK_ASSIGN` требуется для назначения другим, `TASK_WRITE` — допустимые свои изменения (политика self vs manager **DEC-TASK-01**). Активное членство исполнителя проверяется в момент назначения; если позже suspended, новые работы исполнителя запрещены, задача остаётся в истории и требует переназначения. `target` валидировать через typed domain port; свободный текст — PII policy.

**Acceptance:** чужой target/assignee deny, no stale completion, no cross-tenant list/count, reassignment audit, owner archive не оставляет скрытого доступа через task.

### SVC-ORG-01 — завершить `OrganizationLocationService` (P1, доработка)

Операции: `updateDetails(name,address,KATO,expectedVersion)`, `deactivate`, `reactivate`, tenant-scoped get/search/archived visibility. Подписывать отдельный `ORGANIZATION_LOCATION_WRITE`, не дублировать сервис в GEO. При deactivate отказать при активных резервах, незавершённых складских/заказных зависимостях до утверждённой процедуры переноса; не архивировать склад с активным резервом.

**Acceptance:** KATO eligibility и tenant scoped JPA, FK, stale update, два конкурентных writer, reject при active stock/reservation, no foreign location mutation. Прямой наружный вызов `JpaRepository.findAll/findById` запрещён boundary architecture test.

### SVC-CAT-01 — `CatalogItemService` и `CatalogQueryService` (P1, новая вертикаль)

Использовать **существующие** `crm.cat_item`, `crm.cat_item_i18n`, `crm.cat_price` и `CatalogItem` как foundation; не создавать конкурирующую `product` таблицу. Additive column/migration только для реально отсутствующих полей (`model_code` при принятом R2B решении). SKU уникален **в tenant**, не глобально; PRODUCT/SERVICE различаются, локали `kk/ru/en/zh-Hans`.

Операции: `createDraft`, `updateMetadata`, `upsertTranslation`, `setPriceRevision`, `publish`, `archive`, `get`, `search(status/sku/modelCode/kind/name/price/asOf)`, `priceHistory`. `CATALOG_PUBLISH` отдельно от WRITE; публикация требует валидной актуальной цены (BigDecimal, currency, validFrom) и необходимых локализаций по утверждённому minimum. Прошлые effective price revisions неизменяемы; заменить цену — новой revision.

`CatalogItem` не владеет stock и не выполняет reserve. `STOREFRONT` получает publishable immutable offer/projection; `SALES` сохраняет immutable `title/unit_price/currency/sku/revision` snapshot до изменения цены. Не менять ранее оформленные заказы при новой цене.

**Acceptance:** A/B с одинаковым SKU/modelCode; unique `(tenant_id,sku)`, tenant-safe i18n/price JOIN; unauthorized publish/price manage denied; concurrent CAS и price revision conflict; на дату `asOf` только одна effective price по утверждённой precedence; absence of price rejects publish.

### SVC-STORE-01 — `StorefrontManagementService` (P2, новая tenant-owned management вертикаль)

Работает поверх существующих `crm.store_front`, `crm.store_offer` и `AutoOrderService`, без создания параллельного checkout engine. Методы: `getSettings`, `updateSettings`, `publish`, `pause`, `resume`, `publishOffer`, `unpublishOffer`, `listPublishedOffers`.

Owner staff facade: `VerifiedActor + tenantId + STOREFRONT_*`, checks. Public lookup по slug/offer — **отдельный ограниченный anti-enumeration контракт**, не staff RBAC; не раскрывает PII, prices/availability вне опубликованных полей. При публикации получать данные CATALOG через typed published snapshot. Обновление source item/price не меняет прошлые SALES snapshots и не создает cross-tenant offer. Привязка storefront→tenant должна быть проверена и уникальна по утверждённой slug policy.

**Acceptance:** tenant A не редактирует B slug/offer; pause blocks new checkout but not historical orders; concurrently unpublish/check-out uses defined admission snapshot; response no private keys.

### SVC-SALES-01 — дополнить SALES staff workflow (P1, доработка)

Сохранить `AutoOrderService` guest checkout и `AuthenticatedSalesCommands.confirm/cancel/fulfill`, dual SALES+INVENTORY permit и stock port. Новые операции только через сервис-владелец: `createDraft` (staff only, после согласования источника офферов), `add/remove/updateDraftLine`, `getDetail`, `orderHistory`, `submitDraft`; существующий search дополнить утверждёнными фильтрами.

Не позволять редактировать immutable line snapshot после submit/confirm. `buyer_user_id` — глобальный IAM buyer, не `crm.customer_id`. Связь с CRM Customer нужна в виде отдельного tenant-qualified explicit mapping contract **DEC-SALES-01**; пока нет — `customerId` filter/CRM order link не выпускать. `COMPLETED` (inventory fulfillment) не означает оплачено/доставлено.

**Acceptance:** two tenants; price snapshot fidelity; cancel/fulfill/reserve no double effect; foreign order & cross-tenant offer denied; stale draft conflict, idempotent submit; no inventory update via raw SQL.

### SVC-INV-01 — `InventoryTransferService` и `InventoryStocktakeService` (P1, add-on)

Сохранить существующие `InventoryMovementService`, `InventoryReservationService`, balances, event ledger. Новые команды: `transfer(sourceLocation,targetLocation,item,qty,requestKey)`, `startStocktake`, `recordCount`, `commitAdjustment(reason,requestKey)`, read movement/history by tenant/location/item/type.

Перемещение между **складами одного tenant**, только положительное quantity, source!=target, оба location ACTIVE и разрешены. Lock order `(tenant,location,item)` детерминирован, исходный available stock sufficient; transfer = парные append-only движения в **одной** транзакции, нулевой суммарный net quantity по tenant/item, без изменения reserved. В новой модели не вводить произвольный `updateBalance`.

**Acceptance:** oversell/negative stock impossible; opposing transfer concurrency no deadlock; rollback one side rolls back both; exactly-once same requestKey, different payload rejects; foreign location/SKU/tenant fails.

### SVC-FILES-01 — расширение owner ACL + media search (P1, доработка)

Не создавать второй реестр; использовать `file_object/file_attachment/file_storage`, `FileStoragePort`, LOCAL/RustFS, ClamAV и существующие recovery gates. Утверждённая текущая owner policy для `CRM_CUSTOMER` + COMPANY сохраняется.

Каждый новый owner type (`CRM_PERSON`, `CATALOG_ITEM`, `SALES_ORDER`, `TASK`) — отдельный typed `FileOwnerAccessPort` адаптер с проверкой tenant, status, permissions, PII/classification и purpose. Новый bounded `searchAttachments(actor,tenant,OwnerRef,filter)` возможен только после allowlist qualification; **не** универсальный unchecked `owner_type,owner_id`.

`attach`/`detach`/read проверяют FILES + domain-owner доступ. Скачивание авторизуется **до** чтения binary; storage key не выдаётся пользователю. Ранее прикреплённые файлы живут по собственному lifecycle; detach не удаляет объект, если есть другие ссылки. На recovery CAS сверять pointer/version/hash после IO. При выбранном в проекте решении binary хранится без прикладного шифрования; это **не** отменяет PII CRM/IAM и отдельный production privacy gate.

**Acceptance:** zero foreign bytes/read/metadata, denied before storage IO, scanner ERROR fail-closed, shared file detach safe, wrong owner type fail closed, RustFS/LOCAL restart/recovery on exact SHA.

### SVC-AUD-01 — `AuditQueryService` (P2, новая безопасная read вертикаль)

`listEvents(actor,tenant,from,to,action,entityType,actorId,PageWindow)` и `getEvent(actor,tenant,id)`, только sanitized metadata, bounded paging, `AUDIT_READ`. Данные из утверждённых audit источников, без дублирования contract/contact histories и без неопределённого union, который скрыто смешивает tenant. Если canonical cross-domain audit event отсутствует — сначала согласовать projection из существующих событий, не копировать всё в новое хранилище.

`AuditRetentionService` уже существует и остаётся **отдельной системной политикой**; обычный OWNER с AUDIT_READ не может менять legal hold/retention или удалять immutable evidence. Read не выдаёт encrypted envelope, token, email, note, storage key.

**Acceptance:** tenant A only, empty foreign UUID, bounded filter/count, legal hold unaffected, no audit mutation through query, no PII leakage.

### SVC-FIN-01 — `FinanceInvoiceService`, `PaymentService`, `PaymentAllocationService` (P2, новая вертикаль)

Минимальный контур **учёта**, не шлюз оплаты: `Invoice` с tenant/order reference/immutable line amounts/currency/status/version; `Payment` с tenant/external reference/amount/currency/date/status; `Allocation` связывает payment↔invoice одного tenant и одной currency. Договорная сумма берётся из квалифицированного SALES order snapshot; `sales_order.COMPLETED` не означает PAID.

Предложенные команды: `issueInvoice`, `cancelUnpostedInvoice`, `get/search`, `recordPayment`, `reversePayment`, `allocate`, `unallocateByCompensation`, `reconcile` с явными source+requestKey. No hard delete, cancellation/reversal через append-only entries. `BigDecimal`/scale, single currency, sum allocations <= payment amount, invoice outstanding not negative. Exchange conversion, taxes, accounting postings, refund/gateway — отдельные решения.

**Acceptance:** no cross-tenant payment allocation, duplicate external payment cannot double-record, FX mismatch reject, paid state only after committed allocation, rollback and parallel allocation no over-apply.

### SVC-MKT-01 — `MarketingCampaignService` (P2, новая consent-gated вертикаль)

Минимум: сегмент tenant-local CRM Contact с отдельным разрешённым scope, версия шаблона, draft→approved→scheduled→sending→completed/cancelled, delivery receipt/attempt, unsubscribe/withdraw. Команды: `create`, `updateDraft`, `approve`, `schedule`, `cancel`, `list`, `dispatchEligibleBatch` (service identity).

**Security:** наличие `MARKETING_SEND` **не отменяет** актуальное CRM consent: purpose + channel + policyVersion проверяются по последнему committed событию непосредственно перед dispatch; revoked/absent → DENY. Сегмент не означает разрешение читать PII каждому маркетологу: email/contact lookup через ограниченный CRM delivery port с purpose-specific выходом, без доступа к глобальному IAM email профилю. Повтор отправки дедуплицируется по tenant,campaign,contact,templateVersion,attempt key. Не отправлять ничего в production до real mail/outbox/consent acceptance.

**Acceptance:** revoke-before-send never sends; A recipients not in B campaign; consent withheld and resend duplicate denied; no plaintext PII in event/logs.

### SVC-SUB-01 — `SubscriptionService` (P3, новая вертикаль)

Разделить глобальный `Plan` (платформа утверждает цену/entitlements) и tenant-owned `Subscription/EntitlementRevision`. Команды для платформенного оператора: assign/replace/suspend plan, enforce limit revision with audit; tenant read: `myPlan/entitlements/usage` без чужих данных. Subscription entitlement не выдаёт IAM permission; доступ к функции = **RBAC grant AND valid entitlement AND object policy**. Tenant OWNER не может бесплатно назначить себе оплаченные функции. Billing engine/платёжный провайдер — позднее.

**Acceptance:** independent tenant plans and limits, mid-operation revoke consistent with active session policy, cannot use entitlements as RBAC bypass, admin action audit, no foreign tenant reads.

## 6. Единый приоритетный backlog и dependency gates

| Work ID | Priority | Результат | Предусловие | Release gate |
|---|---|---|---|---|
| P0-01 / TCRUD-00 | P0 | Полная трассировка public facade → auth → SQL/JPA → tests | Текущая `base_dev`, не старые audits | Method inventory **без непроверенных claims** |
| P0-02 / DEC-OWNER | P0 | Утвердить OWNER bootstrap decision и ограниченный permission набор | Поправка к R2 и IAM review | No self-grant bypass; replay/races |
| P0-03 / TCRUD-03 | P0 | Cross-domain two-tenant mixed-role adversarial matrix | Утверждённые scope/perms | Zero leak and PostgreSQL tests |
| P0-04 | P0 | Проверить raw service/JPA repository bypass + least privilege boundary | Архитектурная трассировка | No new accessible UUID bypass |
| CRM-01 | P1 | Категория/договор/expiry search | CRM-R1.2/R1.3 + R2A | Tenant join/filter/count |
| CRM-02 | P1 | Interaction/responsible/next action | CRM-01; TASK design согласован | State/version/PII tests |
| ORG-01 | P1 | Location edit/deactivate/reactivate | INV deps/object policy | Active stock/ reservation block |
| CAT-01 | P1 | CATALOG CRUD/read/publish/price revisions | Новые DomainPermission, existing DDL | SKU/price/locale/order snapshot |
| TASK-01 | P1 | Task CRUD/assign/history | CRM owner port, DEC-TASK-01 | Same tenant assignee + owner |
| SALES-01 | P1 | Staff draft/detail/history | CATALOG price snapshot, DEC-SALES-01 | Immutable order + stock E2E |
| INV-01 | P1 | Transfer/stocktake/history | ORG location eligibility | paired ledger atomicity |
| FILES-01 | P1 | Typed owner ACL/media search | Каждый новый owner port | Denied bytes + live infra |
| STORE-01 | P2 | Tenant staff storefront settings/publish | CAT-01 publish contract | Public vs staff separation |
| AUD-01 | P2 | Tenant read of sanitized audit | Audit source policy approved | No secret/foreign data |
| FIN-01 | P2 | Invoices/payments/allocations | SALES immutable contract | Reconcile/idempotence |
| MKT-01 | P2 | Consent-gated campaigns | CRM consent semantics + mail runtime | Revocation-before-send |
| SUB-01 | P3 | Plans/entitlements/tenant limits | Platform governance contract | RBAC AND entitlement |

**Независимые потоки:** CRM-01 и CAT-01 могут идти параллельно после P0; TASK-01 ждёт CRM target port; STORE-01 и SALES draft зависят от CATALOG publish contract; FIN-01 зависит от SALES snapshots; MARKETING — от CRM consent/mail qualification. Не объединять все вертикали в один релиз или огромный Liquibase changeset.

## 7. Порядок implementation waves и acceptance artifacts

- **Wave 0 — security-admission (обязательный):** P0-01, P0-03, P0-04; P0-02 документально решить прежде OWNER code. Закрыть TCRUD-02 PII mutation response regression, которая уже имеет код, не переписывать её.
- **Wave 1 — реальная CRM работа:** CRM-01, CRM-02, TASK-01, ORG-01; пройти Customer → Interaction → Task → Complete с правильным tenant.
- **Wave 2 — товар и заказ:** CAT-01 → STORE-01 / SALES-01 → INV-01; пройти SKU → Price Revision → Publish → Checkout → Confirm/Reserve → Fulfill, без финансового ложного PAID.
- **Wave 3 — связанный контент и отчётность:** FILES-01, AUD-01 с owner-specific checking; не ослаблять RustFS/ClamAV qualified path.
- **Wave 4 — новые поздние вертикали:** FIN-01 → MKT-01 → SUB-01 (между ними нет жёсткой зависимости, порядок по продуктовой потребности), каждую независимо допускать после собственного service test matrix.

Каждый инкремент завершать: a) код/миграции, b) нет новых Controller, c) список изменённых методов, d) exact SHA, e) H2 smoke, PostgreSQL integration/concurrency, f) populated database upgrade, g) relevant RustFS/ClamAV test при файловых изменениях, h) документация matrix/status, i) reviewer approval. Не называть `PRESENT` = `TESTED` или `QUALIFIED`; production допуск отдельно.

## 8. Обязательная security test matrix (для каждой новой вертикали)

**Fixtures:** два tenant A/B с одинаковыми SKU, company/category/contract code, location names; user A, user B, user AB с различными permissions по tenant, OWNER без бизнес-grants, suspended member/workspace, worker со scope.

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| ISO-01 | User A `get/search/count` по B object UUID | DENY или пустой результат, **0** B rows/PII/bytes |
| ISO-02 | User AB имеет write в A и read-only в B, запрашивает mutation B | DENY, 0 side effects |
| ISO-03 | Cross-tenant category/contract/task/offer/payment/file reference | Composite FK/object port reject, без частичной записи |
| ISO-04 | Детальная page, history, archived, sort и cursor | Только разрешённый tenant/scope, без чужих total/cursors |
| ISO-05 | PII: CRM_WRITE без PII_READ, неверный AAD, чужой tenant key | No plaintext DTO/audit/log, fail closed |
| ISO-06 | Role revoke/suspend одновременно с бизнес-командой | Две PostgreSQL очередности корректны, stale JPA не допускается |
| ISO-07 | Два concurrent update одной версии | Exactly one winner и один audit event |
| ISO-08 | Replay того же requestKey/другой payload | Идентичный receipt / IDEMPOTENCY_CONFLICT |
| ISO-09 | Crash/rollback на середине multi-aggregate change | Нет половины reserve/transfer/allocation/price publish |
| ISO-10 | Invalid state transition / archived owner / disabled member | DENY, нет orphan refs |
| ISO-11 | FILES foreign read, scanner ERROR, recovery pointer race | 0 bytes до admission, no false AVAILABLE/corruption |
| ISO-12 | Consent revoked before scheduled batch | Нет отправки, неизменяемый event |
| ISO-13 | Plan entitlement есть, а permission нет | DENY; permission есть, entitlement отсутствует → DENY только для licensed feature |
| ISO-14 | Direct runtime DB role пытается DDL/privileged audit mutation | Отказ по least privilege; RLS **не утверждать**, пока не квалифицировано |

**H2 не заменяет PostgreSQL:** row-lock races, PG-specific immutable triggers, concurrent workers и восстановление заполненной базы требуют real PG. Для FILES — актуальные storage/scanner проверки без подмены synthetic scanner при production qualification.

## 9. Product decisions (не изменять код до решения)

| ID | Вопрос | Безопасный default до решения |
|---|---|---|
| DEC-OWNER-01 | OWNER получает рабочие grants при one-time bootstrap или только через сотрудников? | Текущая no self-assignment, no wildcard; вариант B рекомендован, но не утверждён |
| DEC-SCOPE-01 | CRM весь портфель tenant или только клиентов назначенного менеджера? | Tenant-wide для имеющих CRM_READ/PII_READ, `responsible` пока metadata |
| DEC-SCOPE-02 | INVENTORY все склады tenant или только assigned locations? | Tenant-wide с Inventory permission |
| DEC-TASK-01 | Менеджер может менять чужие задачи? Исполнитель — только свои? | Без `TASK_ASSIGN` нельзя назначать другому; до решения закрыть чужие mutations |
| DEC-CAT-01 | Набор обязательных локализаций и цена на дату публикации? | Публикация запрещена без базовой валидной locale+effective price |
| DEC-SALES-01 | Как связывается tenant CRM Customer с отдельным IAM buyer/order? | Никакого `buyer_user_id = customer_id` |
| DEC-FIN-01 | Налоги/возвраты/частичная оплата и бухгалтерская проводка? | Только учёт external fact и одновалютные allocations, без gateway |
| DEC-MKT-01 | Правовая цель, канал, шаблон и срок consent? | Нет согласия => нет рассылки |
| DEC-FILES-01 | Кто может читать/прикладывать документы PERSON и связанные с заказом? | Только уже проверенная COMPANY typed owner policy |
| DEC-AUDIT-01 | Кто видит cross-domain audit и legal hold? | Только минимальный sanitized audit read; legal hold только system governance |
| DEC-SUB-01 | Кто меняет планы tenant и подписки? | Только явно уполномоченный platform-governance, не tenant OWNER |
| DEC-RET-01 | Сроки хранения/удаление PII и документов | No hard-delete истории до privacy/legal gate |

**Принятие решения:** запись в нормативном R2 или одобренном domain contract, владелец продукта, security review (если затрагивает полномочия/PII), миграция только после тестируемого принятого перехода. ТЗ предлагает минимальные defaults, но не выдаёт их за утверждённую функциональность.

## 10. Engineering tickets и DoD шаблон для Codex

**Каждая карточка начинается с HEAD и diff:** `git branch --show-current` = `base_dev`, `git rev-parse HEAD`, методный audit на актуальном SHA. Не создавать новые ветки, не делать merge в `main`, не изменять HTTP/контроллеры. Проверить, что ранее реализованное не переписано.

Карточка содержит:
1. Target user journey + domain owner; existing code reuse и exact missing operations.
2. Service methods + typed DTO/commands, permission/action/object visibility matrix.
3. SQL/JPA tenant predicates, FK/unique, state CAS, audit, idempotency и migration plan.
4. PostgreSQL positive/negative/race fixture, H2 smoke, для FILES при необходимости real RustFS/ClamAV.
5. Evidence on exact SHA, CI run links, failed/blocked cases, update TCRUD-00/03 matrix.
6. Review before `main`; никакого заявления production-ready без полноценного OIDC/DB role/backup/recovery/external security gates.

**Рекомендуемая первая задача:** `P0-01 + P0-03` завершить per-method трассировку и проверки в IAM/ORG/SALES/INVENTORY/FILES, пока CRM часть уже более подробно описана; затем независимыми инкрементами `CRM-01` и `CAT-01`. После них — `CRM-02/TASK-01`. Первый OWNER bootstrap до явного DEC-OWNER-01 остаётся документационной задачей.

## 11. Явные нецели и финальный критерий

**Не делать:** общий super-tenant, second identity/customer table, generic entity CRUD, автоматическую выдачу всех прав OWNER, implicit portfolio ACL, legacy SQL schema rename, migration checksum rewrite, fake payment delivery, global file read, автоматический маркетинговый контакт без согласия, RLS «на словах», новые контроллеры, Redis/microservices.

**Программа закрыта только тогда**, когда выбранная организация выполняет квалифицированные user journeys через проверенный внутренний actor/tenant boundary, каждая операция ограничена tenant и object permission, новые сервисы имеют exact-head PostgreSQL evidence, старые функции не регрессировали, а неутверждённые политики не были молча внедрены. Это не означает автоматического допуска в production.


## 12. Implementation increment CRM-01A (2026-11-22)

**Code scope:** extend existing `CustomerSearch` with tenant-local category code, half-open creation interval and explicitly dated `expiresWithinDays` for ACTIVE started finite contracts. Legacy 4-argument constructor remains source compatible. `CrmSearchService` uses correlated tenant-scoped `EXISTS` before count and pagination; PERSON PII is not projected or decrypted. Add two-tenant/category-code collision and 0/7/60-day tests in `CrmR1aQualificationTest.crm01aTenantScopedCategoryCreatedWindowAndActiveContractExpiryFilters`.

**No new DDL, entities, controllers or permissions.** This is a **partial CRM-01 implementation**, not a complete expiry-indicator summary. Remaining: `contractIndicator` aggregation/precedence, nearest-contract ID/summary, explicit end-date filters, signed cursor/keyset and validated tenant business date. Do not claim these implemented. **Qualification:** exact-head CI H2/PostgreSQL/populated-PII must pass and real-DB query plan should be checked before marking CRM-01A QUALIFIED.
