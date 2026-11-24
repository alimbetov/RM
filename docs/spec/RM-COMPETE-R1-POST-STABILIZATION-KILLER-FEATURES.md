# RM-COMPETE-R1 — Post-Stabilization Competitive Features & Killer Features

**Тип:** продуктово-техническое задание (future feature reserve / staged backlog).  
**Дата:** 2026-11-22.  
**Репозиторий:** `alimbetov/RM`; **целевая ветка документа:** `base_dev`.  
**Исходная точка ревью:** `base_dev` SHA `4699d640915198e58ece747b30dcc662952c2611` (исходная проверка; актуальный код перепроверять на SHA реализации).  
**Статус:** **PROPOSED / PARKED — NO IMPLEMENTATION AUTHORIZATION, EXCEPT KF-03 R0.1 (CR-MVP-KF-RADAR-01)**.  
**Охват:** конкурентные функции после функциональной стабилизации RM; не заменяет текущий сервисный backlog и не разрешает открытие production/API.

**Owner exception 2026-11-24:** [RM-KF-RADAR-R0.1](RM-KF-RADAR-R0.1-OVERDUE-TASK-SIGNAL-R1.md) — Overdue Task Signal only, service-first, no business DML, no new tables or endpoints. The later [MVP Freeze §0A](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) and R0.1 spec govern this slice; general PARKED statements and FS launch timing remain applicable to all other features. Security, regression and production HOLD remain.

## 0. Executive decision

RM позиционируется как CRM-first B2B business operations platform с непрерывным циклом `клиент → предложение → заказ → резерв → исполнение → счёт/оплата`. Конкурентная цель — доказуемое сокращение времени, ошибок и стоимости выполнения типовых задач по сравнению с выбранными альтернативами (Bitrix24, Odoo, Zoho CRM, 1С, МойСклад) **в конкретных сегментах B2B торговли и дистрибуции**, а не абстрактное превосходство над всеми CRM/ERP.

За исключением отдельно разрешённого KF-03 R0.1, это ТЗ **исключительно задел**. Нельзя создавать по нему Java-сервисы, таблицы, контроллеры, новые ветки, feature flags, внешние API или миграции до отдельного решения о запуске стадии. Нельзя считать утверждение ТЗ утверждением security/production admission.

## 1. Authority, scope и non-goals

**Приоритет источников:**
1. `docs/architecture/RM-PLATFORM-STANDARD-R2.md` — единственный нормативный стандарт архитектуры, IAM, PII, RBAC и tenant.
2. `docs/spec/RM-TENANT-SERVICES-COMPLETION-R1.md` — текущий service-first backlog (CRM Interactions, Tasks, Catalog, Sales, Inventory, Files, Storefront, Finance и пр.).
3. `docs/spec/RM-TENANT-CRUD-COMPLETION-R1.md` + `docs/reviews/RM-TCRUD-03-COVERAGE-CLOSURE-TCRUD-04-BOUNDARY-2026-11-22.md` — границы, негативные кейсы и незакрытые проверки.
4. `docs/spec/RM-STOREFRONT-AUTOORDER-R1.md`, `docs/spec/RM-SVC-R2A-FILTERING-PAGINATION-CONTRACT.md` — checkout, immutable prices, фильтры и tenant-safe pagination.

Если предложение настоящего документа противоречит утверждённому стандарту, действует R2; противоречие помечается `DECISION REQUIRED`. Описанные далее имена классов — **предлагаемые**; не утверждают, что они существуют.

**Вне scope:** создание новой универсальной ERP, собственной полноценной бухгалтерии, налогового движка, платёжного шлюза, универсального workflow DSL, микросервисов, Redis/AI-агентов как prerequisites, автоматического раскрытия чужих tenant-данных, широкой реорганизации PostgreSQL.

## 2. Launch gates: когда разрешено начинать Killer Features

| Gate | Обязательное подтверждение | Правило |
|---|---|---|
| **FS-01 Domain completion** | CRM Customer/Contact/Category/Contract, CATALOG publish/price, staff SALES, inventory reserve/fulfill, relevant ORG/FILES operations поддерживают согласованный end-to-end contract; взаимодействия/задачи — до KF-03 | Функция открывается только при готовности **её** обязательных зависимостей; разработку остальных доменов не блокирует |
| **FS-02 Security** | TCRUD-03/04 закрыты на входных путях и объектах конкретной функции; real OIDC/VerifiedActor admission для staff; изоляция multi-tenant и owner scope; role revocation tests | Никаких публичных ссылок/операций без отдельного signed proof/verification policy |
| **FS-03 Reliability** | H2 + PostgreSQL exact-SHA CI, ключевые race/idempotency проверки, populated-db migration rehearsal для задействованных таблиц, storage recovery для FILES-ветки | GREEN старого коммита не достаточен |
| **FS-04 UX integration** | Реальный frontend или согласованный пилотный интерфейс, который демонстрирует journey целиком, включая ошибки/повторы/отказ | Внутренние сервисные тесты ≠ пользовательская готовность |
| **FS-05 Commercial baseline** | 5–10 проверяемых сценариев, сравнимая исходная процедура в действующих альтернативах, метрики времени/действий/ошибок, 2–3 потенциальных пилотных клиента | Конкурентные гипотезы должны быть измеримыми, без недоказанных маркетинговых обещаний |

**Двухступенчатый admission:** `DESIGN APPROVED` разрешает только проектирование; `IMPLEMENTATION APPROVED` — отдельное решение после gate review; `PILOT APPROVED` и `PRODUCTION APPROVED` — независимые решения после соответствующих security, эксплуатационных и UX-тестов. После FS-01–FS-04 функциональные фичи допускаются поэтапно, не требуя полного внедрения всех будущих доменов.

## 3. Killer Feature portfolio

| ID | Название | Ценность | Стадия | Предусловия |
|---|---|---|---|---|
| **KF-01** | **RM InstantDeal** | Из персонального коммерческого предложения в проверенный заказ с минимумом ручных действий | Wave 1 | CRM, Catalog, Sales, Storefront, AutoOrder, Auth |
| **KF-02** | **RM Promise Engine** | Доступность к обещанию с учётом реального склада, резервов и ограничений исполнения | Wave 1/2 | Inventory, Organization Location, Sales |
| **KF-03** | **RM Relationship Radar** | Объяснимая очередь действий по клиентам и договорам | Wave 2 | CRM Contract/Interaction, Task, authorized query |
| **KF-04** | **RM Zero-Entry Order** | Заказ из XLSX/CSV без повторного набора, с проверкой человеком | Wave 3 | Files, Catalog matching, CRM, Sales Draft |
| **KF-05** | **RM Business Network** | Двустороннее B2B взаимодействие компаний без смешивания их рабочих пространств | Wave 4 | Mature IAM/partner contract, Storefront, Sales, integration protocol |

## 4. KF-01 — RM InstantDeal (первый приоритет)

### 4.1 Бизнес-сценарий

Сотрудник tenant A формирует версионированное предложение для конкретного покупателя: состав, SKU, валюта, количества, индивидуальная цена, срок действия, условия. Получатель открывает **ограниченную по полномочиям ссылку**, просматривает предложение и принимает его с подтверждением email/допустимым proof. Система фиксирует immutable snapshot, создаёт заказ в существующем SALES/AutoOrder контуре без дублей. Продавец подтверждает заказ; только **после квалифицированного подтверждения** происходит атомарное резервирование через existing OrderInventoryPort. До этого отображаемая доступность — справочная, не гарантированная бронь.

### 4.2 Предлагаемые компоненты (не создавать сейчас)

- `QuotationService`: draft, revise, publish, withdraw, expire, accept, history; snapshot line items.
- `QuotationShareService`: создание/отзыв короткоживущего scoped proof; хранить **хэш** токена, не исходный token; rate limits.
- `InstantDealOrchestrator`: одна идемпотентная команда `acceptQuote` → tenant-qualified order creation, без второго движка checkout.
- `InstantDealReadModel`: ограниченная публичная projection без CRM PII и внутренних финансов/скидок, не разрешённых покупателю.

**Модель:** `quote(tenant_id,id,customer_id?,status,revision,expires_at,currency,...)`, `quote_line(tenant_id,quote_id,...snapshot)`, `quote_share(tenant_id,quote_id,proof_hash,scope,expiry,revoked_at)`, `quote_acceptance(tenant_id,quote_id,revision,request_key,order_id)`. Предметные поля и DDL утверждать только при implementation review. `buyer_user_id` — глобальный IAM buyer, **не** `crm.customer_id`; mapping — отдельный утверждённый tenant-local контракт.

### 4.3 Инварианты и негативные тесты

- Заказ содержит только согласованную **версию** предложения и неизменяемые суммы, количество, валюту и цену; изменение Catalog после принятия не переписывает заказ.
- Истёкшая/отозванная ссылка не позволяет принять; владение email нельзя вывести из введённого адреса; ссылка сама не открывает CRM-профиль или чужие заказы.
- Повтор `request_key` с тем же payload возвращает тот же orderId; с другим payload — отказ.
- Два параллельных accept/withdraw, accept/expire, catalog price change и reserve конкурируют без двойного заказа, двойного резерва и oversell.
- Не поддерживаем обещание «гарантированного наличия» до подтверждения продавцом; отображать точный статус.
- A/B tenant и смешанные роли: нельзя получить/подписать B quote, прочесть чужие PII, отобразить storage keys, обойти `VerifiedActor`.
- Никакого автоматического счёта, оплаты или доставки от одного факта `SALES.COMPLETED`.

**DoD:** клиент получает одно ограниченное предложение; принимает его; продавец видит заказ; подтверждение создаёт корректный резерв; повторные события/ошибки не создают дубликаты; сценарий показан в пользовательском интерфейсе и PostgreSQL E2E.

## 5. KF-02 — RM Promise Engine (available-to-promise)

**Задача:** точно сообщить доступное к продаже количество и честный предполагаемый срок исполнения.

**Предлагаемый сервис:** `AvailabilityPromiseService.quoteAvailability(actorOrPublicScope,tenant,item,quantity,location?,asOf)`; обращается к утверждённым Inventory Read Model, Order reservations и Location eligibility. Не владеет остатками, резервами и движениями, не пишет в inventory ledger.

**R1:** только `onHand - activeReserved`, склад, SKU, доступное к заказу количество и явно заданная политика поставки. Если срок пополнения не подтверждён, вывести `UNKNOWN`, не выдумывать дату. R2 после получения проверяемых данных: ожидаемые поставки, межскладская маршрутизация, вероятность дефицита.

**Правила:** query носит справочный характер; проверка наличия повторяется в атомарном confirm/reserve; никакого внешнего `reserve` из preview; публичная projection не раскрывает внутреннюю себестоимость/остатки закрытых складов; scope tenant+item/location; stale state не создаёт заказа с отрицательным остатком.

**DoD:** concurrency с параллельной продажей, перемещением и отменой; статусы `AVAILABLE`, `PARTIAL`, `UNAVAILABLE`, `UNKNOWN` проверены; ни одно обещание не считается бронью без подтверждённой операции.

## 6. KF-03 — RM Relationship Radar (rules first, ML later)

**Задача:** показать сотруднику ранжированную очередь подтверждённых поводов к действию, не общие AI-рекомендации без объяснения.

**Начальные правила:** договор истекает через N дней; нет запланированного следующего контакта; задача просрочена; заказ ожидает подтверждения; потенциально потерянный клиент по отсутствию активности после явно согласованного окна. `N` и окна tenant-configurable, но не глобальные hardcoded thresholds.

**Предлагаемые компоненты:** `RelationshipSignalEvaluator` (детерминированное правило), `RelationshipActionQuery` (tenant/assignee scope), `SignalDismissalService` (snooze/dismiss with audit). Отдельная запись `signal` допустима лишь при доказанной необходимости дедупликации и историографии; по умолчанию вычисляемая projection.

**Обязательные ограничения:** только разрешённые CRM/Tasks/SALES данные, никакого обхода `CRM_PII_READ`; если модель «только мои клиенты» не утверждена, не делать вид, что она реализована; задача создаётся только по явному действию сотрудника или отдельной авторизованной автоматизации; рекомендация объяснима набором фактов. ML scoring возможен только после data-quality/privacy/bias review.

**DoD:** воспроизводимые сигналы на synthetic fixture, корректная дата в часовом поясе/согласованном tenant business date, отсутствие дублей, корректный revoke, безопасное закрытие задачи при archived customer.

## 7. KF-04 — RM Zero-Entry Order (human-in-the-loop)

**Задача:** заказ из файла клиента с минимальным ручным вводом.

**Порядок расширения:** R1 — структурированные `XLSX/CSV` с явной картой колонок, preview, SKU match, валюта/количество/цены и ручное подтверждение; R2 — PDF extraction; R3 — OCR/AI-классификация (только при подтверждённой экономике и проверке качества).

**Предлагаемые компоненты:** `OrderImportBatchService`, `OrderLineMatchService`, `OrderImportReviewService`; существующий FILES scanner/owner ACL сохраняется, без отдельного незащищённого blob storage. `upload → scan → parse → validate → match → review → createDraft`. Автосоздание подтверждённого заказа/резерва запрещено.

**Инварианты:** tenant-specific SKU и pricing revision, неизвестные строки не сопоставлять молча; каждая строка имеет `MATCHED/AMBIGUOUS/UNKNOWN/INVALID`, причину и предложенную цену как reference (не автоматическое изменение заказа); `request_key` и file hash делают повторный импорт безопасным; ограничение размера/строк, отказ по zip-bomb и опасным форматам; формулы Excel не исполнять; PII/logs защищены.

**DoD:** набор золотых файлов (валидные, дубли, разные валюты, неверный SKU, скрытая строка, испорченный файл, чужой tenant), статистика точных матчей и проверенный оператором draft.

## 8. KF-05 — RM Business Network (стратегический, не MVP)

**Цель:** компания A (покупатель) и компания B (продавец) добровольно обмениваются отдельными согласованными бизнес-артефактами: предложением, заказом, статусом исполнения, счётом — без прямого доступа A к таблицам/CRM B.

**Планируемый протокол:** `PartnerAgreement` с двусторонним opt-in, scope, сроком действия, revocation; `PartnerOrderBridge` с immutable shared snapshot + dedupe/outbox + correlation ID; `PartnerStatusProjection` с allowlist полей. Cross-tenant FK, расшаренная транзакция между двумя tenant и глобальный `canReadOtherTenant` запрещены.

**MVP:** договорённость A↔B, размещение заказа в витрине B, доступ A лишь к собственному receipt/видимым статусам. Позднее согласованные order/finance документы и интеграции. Партнёрское соглашение **не эквивалентно** membership или роли в B. Разрыв партнёрства блокирует новые обмены и не удаляет законно сохранённые исторические snapshots.

**DoD:** A/B/C tenants, чужие UUID, подмена контракта, отзыв соглашения во время сообщения, повтор/out-of-order delivery, двойной заказ, правомерное хранение каждой стороны; replay/recovery после отказа узла. Go/no-go только после реального подтверждения спроса у нескольких независимых организаций.

## 9. Dependencies and delivery sequencing

| Wave | Что реализовать **после gate** | Что не делать одновременно |
|---|---|---|
| **Wave 0** | Закрыть текущую стабилизацию: R2, TCRUD, CRM/Sales/Catalog/Inventory contract, UI + auth; собрать benchmark | Никаких Killer Features |
| **Wave 1** | KF-01 minimal vertical + интерфейс и пользовательский тест | Ни ML, ни network, ни расчётов финансового учёта сверх требуемого |
| **Wave 2** | KF-02 inventory promise R1 и KF-03 deterministic rules | Не внедрять predictive ETA без фактов |
| **Wave 3** | KF-04 XLSX/CSV workflow | Не запускать OCR/AI auto-order без human review |
| **Wave 4** | KF-05 bilateral network после пилотов | Никаких сквозных DB permissions между tenant |

`Wave 1` может стартовать только после `FS-01...FS-04` именно для её зависимостей. Gate `FS-05` нужен для оценки конкурентной гипотезы и пилотного решения. Любое отклонение — explicit product decision + security review.

## 10. Common technical contract

1. **Архитектура:** Java 21 / Spring Boot 4.1.3 modular monolith, PostgreSQL, Liquibase; JPA для версионируемых агрегатов, JDBC для query/критичных операций там, где уже оправдан. Не копировать CATALOG, Sales, CRM, Inventory в «killer» схемы.
2. **Trusted identity:** staff commands принимает `VerifiedActor` + явно выбранный `tenantId`; публичный proof/checkout — изолированная минимальная surface с challenge/anti-abuse, без доверия к actor UUID в payload.
3. **Authorization:** active global account + workspace + membership, DomainPermission и object-level policy. Новый permission создаётся только вместе с конкретной квалифицированной командой; deny-by-default, никаких automatic wildcard grant.
4. **Tenant:** `tenant_id=iam_workspace.id`; каждый tenant-owned join, unique, count, cursor, history, audit и file owner проверяется; поперечные ссылки через typed ports, не через wildcard entity_type/id.
5. **Concurrency:** optimistic version/CAS + существующий tenant lock order/revocation fencing, уникальные `request_key`, atomic outbox только при реальной межтранзакционной потребности.
6. **Security/privacy:** token hash, expiry/revoke, PII encryption/classification, предотвращение enumeration, zero-denied-bytes for FILES, sanitized audit, no plaintext secret in logs. Public share допускает только опубликованные бизнес-поля.
7. **Data integrity:** snapshot immutable после принятия; валюта не смешивается; `COMPLETE` в SALES не означает PAID или DELIVERED; inventory authority остаётся у INVENTORY.
8. **QA:** exact-head H2 + PostgreSQL, cross-tenant A/B/C adversarial, concurrent write/revoke, migration populated DB, restarts/recovery, negative validation, E2E + usability.
9. **Operations:** на каждую фичу — request correlation, измерение ошибок и p95/p99, конфигурация ограничений, раздельные visibility и rollout gates; не раскрывать конфиденциальную информацию в телеметрии.
10. **No hard date/SLA without measurement:** количественные пороги производительности фиксируются после baseline workload. Нельзя считать гипотезы доказанными конкурентными преимуществами.

## 11. Competitive benchmark & business acceptance

Сравнивать **одинаковые** пользовательские сценарии RM и доступных конкурентов, при одинаковых тестовых данных и обученности исполнителей. Фиксировать версию продукта и тариф/модули; не утверждать отсутствие функции у конкурента без воспроизводимой проверки.

**Сценарии:** (B1) завести клиента и контакт; (B2) сформировать индивидуальное предложение; (B3) получить заказ, зарезервировать товар; (B4) обнаружить и устранить дефицит; (B5) обработать просроченный договор; (B6) импортировать Excel-заказ.  
**Метрики:** median и p90 времени задачи, действия/экраны, доля ошибок, ручные корректировки, успешное завершение, TCO внедрения и поддержки, NPS/CSAT после пилота. 

**Гипотезы/ориентиры (не обещания):** целиться минимум в 30% меньше ручных действий в B2/B3/B6 и ≥95% завершения типовых задач без помощи разработчика. Реальные значения — только после испытаний. Разрешение на расширение требует минимум 2–3 пилотных организации и явной обратной связи.

**Коммерческие stop conditions:** если измеримый результат не превосходит альтернативу, стоимость поддержки растёт или пользователи не возвращаются, изменить UX/процесс до создания новых модулей.

## 12. Decisions required before implementation

| ID | Вопрос | Default до решения |
|---|---|---|
| DEC-KF-01 | Можно ли принять quote анонимно, либо нужен email ownership proof? | Нужен проверяемый proof, link alone не grant CRM |
| DEC-KF-02 | Какие условия и сроки допустимо публично обещать? | Только реальный на момент запроса остаток и явная неопределённость; не гарантия |
| DEC-KF-03 | Tenant-wide CRM или назначенный portfolio scope? | Принятый R2 tenant-wide permission, не мнимый per-manager ACL |
| DEC-KF-04 | Можно ли использовать ML/OCR на коммерческих документах? | Только XLSX/CSV детерминированно; ML отдельный privacy/quality gate |
| DEC-KF-05 | Какие данные передаются между организациями? | Минимальный opt-in receipt/status allowlist, без cross-tenant membership |
| DEC-KF-06 | Как обеспечивается legally acceptable acceptance/audit и локализация договоров? | Не обещать электронную подпись и юридическую эквивалентность без отдельного правового review |
| DEC-KF-07 | Какая конкурентная ниша и критерий победы? | Первые пилоты в B2B торговле/дистрибуции; измерение, не декларация |
| DEC-KF-08 | Как новые фичи тарифицируются? | Никаких платных entitlement/grant до отдельной Subscription governance stage |

## 13. Definition of Done for each feature

`DESIGN` — user journey, матрица источников истины, зависимости и security policy, негативные кейсы, измеримый business KPI, разногласия/decisions.  
`CODE` — отдельный approved implementation ticket, change set в `base_dev`, backward compatibility и формализованные тесты.  
`QUALIFIED` — exact-SHA CI, populated PostgreSQL migration, concurrency, tenant/owner/revocation, restart/retry и подтверждённая UX-приёмка.  
`PILOT` — измеренное бизнес-преимущество на одинаковых сценариях, SLA и support readiness.  
`PRODUCTION` — отдельное разрешение, эксплуатационная квалификация, безопасность, privacy/legal review, recovery runbooks.

**Итоговое правило:** до стабилизации RM разрешено дополнять только этот документ и исследовательские критерии benchmark. Подготовленный задел не должен разблокировать feature development, новый контроллер, production admission или merge в `main`.
