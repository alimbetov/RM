# CRM-99-R2 — независимый повторный набор 99 бизнес-симуляций

**Repository:** alimbetov/RM; **branch:** base_dev; **date:** 2026-11-22.
**Authority:** [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md). **Type:** scoped source-informed business scenarios and proposed acceptance tests; **not** an executed test run.
**Boundary:** service layer / PostgreSQL; no HTTP controllers or UI; production HOLD. No mutation of main.

## Verification boundary
Reviewed directly: `src/main/java/kz/rm/crm/CrmInteractionService.java`, `src/main/java/kz/rm/crm/CrmCustomerService.java`, `src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java` and `docs/reviews/RM-SVC-FUNCTIONAL-DEPTH-AUDIT-2026-11-22.md` from base_dev. Previous reported first-pass counts were 52 component-relevant, 16 partial, 21 missing, 4 buyer mapping, 2 policy, 2 external and 2 newly added PG tests; these **must not** be automatically mapped to the new scenarios. New 99 were independently authored as different failure modes/journeys; uniqueness is checked within **this** matrix, not against a previous matrix whose complete content was not compared.

**Status taxonomy:** SCOPE=existing capability relevant but full user path unverified; PARTIAL=code exists with incomplete journey or proof; POLICY=business decision/security policy required; MISSING=target service capability absent or cross-domain linkage not yet qualified. These are gap classifications, not numerical verified coverage. **Every PG scenario status is NOT RUN until exact-SHA execution evidence exists.**

## Mandatory test recipe
For each PG-ID implement a named Java test with real PostgreSQL. For mutations assert resulting rows and independent query, audit count, unchanged version on rejection, tenant A/B separation, verified actor and RBAC, duplicate key/retry, and transaction rollback. For races use two connections with barriers/latches and bounded timeouts, not sequential mocks. For external transport qualify a real adapter separately; mere unit mocks cannot show delivery.

## 99 distinct scenarios

| ID | Business situation | Required outcome / invariant | Owner | Baseline assessment | Proposed executable test | Run |
|---|---|---|---|---|---|---|
| CRM-R2-A01 | Повторное создание COMPANY с одинаковым названием в одном tenant | Не сливать юридически разные записи по одному имени | CRM Customer | SCOPE | PG-CRM-R2-A01 | NOT RUN |
| CRM-R2-A02 | Два клиента PERSON с одинаковым именем | Не раскрывать и не объединять PII по совпадению имени | CRM Customer | SCOPE | PG-CRM-R2-A02 | NOT RUN |
| CRM-R2-A03 | Архивация клиента с открытым взаимодействием | Новые действия блокировать по явно заданному lifecycle, историю сохранять | CRM Customer | SCOPE | PG-CRM-R2-A03 | NOT RUN |
| CRM-R2-A04 | Повторная архивация клиента с тем же ключом | Не создавать новую историю при идентичном повторе | CRM Customer | SCOPE | PG-CRM-R2-A04 | NOT RUN |
| CRM-R2-A05 | Восстановление клиента с изменёнными контактами | Не восстанавливать старые права и согласия автоматически | CRM Customer | SCOPE | PG-CRM-R2-A05 | NOT RUN |
| CRM-R2-A06 | Смена KATO у клиента при наличии исторических заказов | Сохранять исходные исторические snapshots | CRM Customer | SCOPE | PG-CRM-R2-A06 | NOT RUN |
| CRM-R2-A07 | Конкурентное архивирование и создание взаимодействия | Нельзя получить открытое взаимодействие у архивированного клиента | CRM Customer | SCOPE | PG-CRM-R2-A07 | NOT RUN |
| CRM-R2-A08 | Поиск COMPANY с символами процента и подчёркивания | Литералы поиска не должны расширять результаты | CRM Customer | SCOPE | PG-CRM-R2-A08 | NOT RUN |
| CRM-R2-A09 | Клиент, созданный после начала пагинации | Не обещать snapshot-consistency при offset-пагинации | CRM Customer | SCOPE | PG-CRM-R2-A09 | NOT RUN |
| CRM-R2-A10 | Клиент отсутствует в выбранном tenant, UUID известен | Ответ не должен раскрывать чужой объект | CRM Customer | SCOPE | PG-CRM-R2-A10 | NOT RUN |
| CRM-R2-A11 | Чтение клиента сразу после миграции существующей заполненной БД | PII расшифровывается только по корректному AAD и ключу | CRM Customer | SCOPE | PG-CRM-R2-A11 | NOT RUN |
| CRM-R2-B01 | Добавление контакта к чужому клиенту | Нет нового контакта и PII leakage | CRM Contact | SCOPE | PG-CRM-R2-B01 | NOT RUN |
| CRM-R2-B02 | Контакт принадлежит клиенту A, interaction создаётся у клиента B | Пара customer/contact должна отвергаться | CRM Contact | SCOPE | PG-CRM-R2-B02 | NOT RUN |
| CRM-R2-B03 | Удаление или архивация контакта при существующей истории звонков | Историю не перепривязывать и не удалять | CRM Contact | SCOPE | PG-CRM-R2-B03 | NOT RUN |
| CRM-R2-B04 | Одновременное изменение email контакта и выдача согласия | Согласие должно относиться к конкретному адресу и версии | CRM Contact | SCOPE | PG-CRM-R2-B04 | NOT RUN |
| CRM-R2-B05 | Дублирующиеся email у разных контактов одного клиента | Не считать автоматически одним субъектом | CRM Contact | SCOPE | PG-CRM-R2-B05 | NOT RUN |
| CRM-R2-B06 | Смена основного контакта и запланированный follow-up | Follow-up не должен менять адресата без подтверждённого правила | CRM Contact | SCOPE | PG-CRM-R2-B06 | NOT RUN |
| CRM-R2-B07 | Пустой email и попытка email-consent | Действующее email-согласие без адреса запрещено | CRM Contact | SCOPE | PG-CRM-R2-B07 | NOT RUN |
| CRM-R2-B08 | Проверка consent через контакт другой организации | Жёсткий запрет cross-tenant | CRM Contact | SCOPE | PG-CRM-R2-B08 | NOT RUN |
| CRM-R2-B09 | Массовое чтение контактов без CRM_PII_READ | PII не возвращается даже косвенно | CRM Contact | SCOPE | PG-CRM-R2-B09 | NOT RUN |
| CRM-R2-B10 | Контакт архивирован, но ранее подписал согласие | hasEmailConsent должен быть false | CRM Contact | SCOPE | PG-CRM-R2-B10 | NOT RUN |
| CRM-R2-B11 | Конкурентное архивирование контакта и создание нового call | Нельзя зафиксировать новую связку к архивированному контакту | CRM Contact | SCOPE | PG-CRM-R2-B11 | NOT RUN |
| CRM-R2-C01 | Отзыв согласия между подготовкой сообщения и отправкой | Повторная проверка непосредственно на dispatch boundary | CRM Consent | POLICY | PG-CRM-R2-C01 | NOT RUN |
| CRM-R2-C02 | Повторное согласие после восстановления клиента | DECISION REQUIRED: новая верификация либо формальная политика переноса | CRM Consent | POLICY | PG-CRM-R2-C02 | NOT RUN |
| CRM-R2-C03 | Один контакт согласился на сервисные письма, но не на маркетинг | Purpose-scoped consent без расширения назначения | CRM Consent | POLICY | PG-CRM-R2-C03 | NOT RUN |
| CRM-R2-C04 | Изменение email после выдачи consent на старый адрес | Старое подтверждение не покрывает новый адрес | CRM Consent | POLICY | PG-CRM-R2-C04 | NOT RUN |
| CRM-R2-C05 | Повтор одинакового события отзыва | Без дублирования состояния и истории | CRM Consent | POLICY | PG-CRM-R2-C05 | NOT RUN |
| CRM-R2-C06 | Согласие истекло ровно на границе timestamp | Однозначное правило [from,to) и injected clock | CRM Consent | POLICY | PG-CRM-R2-C06 | NOT RUN |
| CRM-R2-C07 | Контакт отозвал согласие одновременно с запуском кампании | Отправка не проходит после зафиксированного отзыва | CRM Consent | POLICY | PG-CRM-R2-C07 | NOT RUN |
| CRM-R2-C08 | Конфликт двух consent версий | CAS/порядок событий исключают resurrection отозванного consent | CRM Consent | POLICY | PG-CRM-R2-C08 | NOT RUN |
| CRM-R2-C09 | Согласие клиента PERSON не должно переходить к COMPANY | Нет неявного наследования между субъектами | CRM Consent | POLICY | PG-CRM-R2-C09 | NOT RUN |
| CRM-R2-C10 | Чтение consent сотрудником без CRM_READ | Отказ до чтения данных | CRM Consent | POLICY | PG-CRM-R2-C10 | NOT RUN |
| CRM-R2-C11 | Доказательство согласия без раскрытия PII | Метаданные evidence доступны только по назначенным правам | CRM Consent | POLICY | PG-CRM-R2-C11 | NOT RUN |
| CRM-R2-D01 | Два параллельных record с одним requestKey и одинаковым payload | Одна запись и одно CREATED событие | CrmInteractionService | PARTIAL | PG-CRM-R2-D01 | NOT RUN |
| CRM-R2-D02 | Тот же requestKey используется с другим payload | Конфликт без побочных строк | CrmInteractionService | PARTIAL | PG-CRM-R2-D02 | NOT RUN |
| CRM-R2-D03 | Разные tenant используют одинаковый requestKey | Независимые взаимодействия | CrmInteractionService | PARTIAL | PG-CRM-R2-D03 | NOT RUN |
| CRM-R2-D04 | Одновременные complete и cancel одной версии | Единственный успешный terminal transition | CrmInteractionService | PARTIAL | PG-CRM-R2-D04 | NOT RUN |
| CRM-R2-D05 | Повтор complete после terminal transition | Нет второго COMPLETED event | CrmInteractionService | PARTIAL | PG-CRM-R2-D05 | NOT RUN |
| CRM-R2-D06 | setNextAction с устаревшей версией | Без изменения срока и истории | CrmInteractionService | PARTIAL | PG-CRM-R2-D06 | NOT RUN |
| CRM-R2-D07 | assignResponsible с отрицательной версией | Отклонение до мутации | CrmInteractionService | PARTIAL | PG-CRM-R2-D07 | NOT RUN |
| CRM-R2-D08 | Завершение взаимодействия после архивации клиента | DECISION REQUIRED: разрешение исторического закрытия или запрет | CrmInteractionService | PARTIAL | PG-CRM-R2-D08 | NOT RUN |
| CRM-R2-D09 | Встреча с occurredAt в будущем | DECISION REQUIRED: разрешать плановое событие или запретить | CrmInteractionService | PARTIAL | PG-CRM-R2-D09 | NOT RUN |
| CRM-R2-D10 | Поиск по responsible и status на последней странице | Стабильный порядок, корректный page/count в пределах одной версии БД | CrmInteractionService | PARTIAL | PG-CRM-R2-D10 | NOT RUN |
| CRM-R2-D11 | Запрос истории взаимодействия чужого tenant | Не возвращать событий или existence leak | CrmInteractionService | PARTIAL | PG-CRM-R2-D11 | NOT RUN |
| CRM-R2-E01 | Назначение взаимодействия сотруднику чужого tenant | Отказ без изменения версии | CRM + IAM | PARTIAL | PG-CRM-R2-E01 | NOT RUN |
| CRM-R2-E02 | Назначение заблокированному сотруднику | Отказ без события | CRM + IAM | PARTIAL | PG-CRM-R2-E02 | NOT RUN |
| CRM-R2-E03 | Сотрудник исключён из организации одновременно с assign | Запрет назначения после подтверждённого revoke | CRM + IAM | PARTIAL | PG-CRM-R2-E03 | NOT RUN |
| CRM-R2-E04 | Отзыв CRM_WRITE одновременно с complete | Определённый serializable порядок доступа и мутации | CRM + IAM | PARTIAL | PG-CRM-R2-E04 | NOT RUN |
| CRM-R2-E05 | CRM_READ без CRM_WRITE: попытка переноса follow-up | Только чтение, без изменения | CRM + IAM | PARTIAL | PG-CRM-R2-E05 | NOT RUN |
| CRM-R2-E06 | CRM_WRITE без CRM_PII_READ: чтение личного контакта | Нет раскрытия PII через DTO и журнал | CRM + IAM | PARTIAL | PG-CRM-R2-E06 | NOT RUN |
| CRM-R2-E07 | OWNER без назначенных CRM grants | Не получать универсальный wildcard доступ | CRM + IAM | PARTIAL | PG-CRM-R2-E07 | NOT RUN |
| CRM-R2-E08 | PLATFORM_ADMIN читает чужой CRM tenant | Не выдавать tenant PII без явного права | CRM + IAM | PARTIAL | PG-CRM-R2-E08 | NOT RUN |
| CRM-R2-E09 | Сотрудник нескольких организаций выбирает другой tenant | Права и объект сверяются с выбранным tenant | CRM + IAM | PARTIAL | PG-CRM-R2-E09 | NOT RUN |
| CRM-R2-E10 | Suspend workspace во время создания interaction | Не записывать после завершённой блокировки | CRM + IAM | PARTIAL | PG-CRM-R2-E10 | NOT RUN |
| CRM-R2-E11 | Изменение роли после начала длинного списка клиентов | Для mutation admission повторять, для query задокументировать consistency | CRM + IAM | PARTIAL | PG-CRM-R2-E11 | NOT RUN |
| CRM-R2-F01 | Создание follow-up task из завершённого звонка | Typed link и одна задача на подтверждённый запрос | TASKS | MISSING | PG-CRM-R2-F01 | NOT RUN |
| CRM-R2-F02 | Переназначение задачи сотруднику другой организации | Reject и zero changes | TASKS | MISSING | PG-CRM-R2-F02 | NOT RUN |
| CRM-R2-F03 | Просроченная задача со сменой часового пояса сотрудника | SLA по Instant, локальное представление отдельно | TASKS | MISSING | PG-CRM-R2-F03 | NOT RUN |
| CRM-R2-F04 | Отмена interaction при незавершённой задаче | DECISION REQUIRED: отменять задачу или оставлять с explicit link | TASKS | MISSING | PG-CRM-R2-F04 | NOT RUN |
| CRM-R2-F05 | Одновременная отметка задачи done и cancel | Один терминальный исход с CAS | TASKS | MISSING | PG-CRM-R2-F05 | NOT RUN |
| CRM-R2-F06 | Повтор создания задачи после сетевого timeout | Idempotency не плодит две задачи | TASKS | MISSING | PG-CRM-R2-F06 | NOT RUN |
| CRM-R2-F07 | Массовая смена ответственного у открытых задач | Audit каждого изменения и tenant guard | TASKS | MISSING | PG-CRM-R2-F07 | NOT RUN |
| CRM-R2-F08 | Восстановление задачи после ошибки worker | Не дублировать уведомления и события | TASKS | MISSING | PG-CRM-R2-F08 | NOT RUN |
| CRM-R2-F09 | Задача создана для архивированного клиента | Reject нового действия | TASKS | MISSING | PG-CRM-R2-F09 | NOT RUN |
| CRM-R2-F10 | Просроченные задачи двух tenant на одной очереди | Tenant isolation в worker и выдаче | TASKS | MISSING | PG-CRM-R2-F10 | NOT RUN |
| CRM-R2-F11 | История задачи после удаления пользователя | Сохранять actor reference/audit без новых прав доступа | TASKS | MISSING | PG-CRM-R2-F11 | NOT RUN |
| CRM-R2-G01 | Заказ гостя с email как у CRM контакта | Нельзя связывать только по совпадению email | CRM + SALES | MISSING | PG-CRM-R2-G01 | NOT RUN |
| CRM-R2-G02 | Подтверждённая buyer identity совпадает с несколькими клиентами | Ambiguous link требует явного разрешения | CRM + SALES | MISSING | PG-CRM-R2-G02 | NOT RUN |
| CRM-R2-G03 | CRM-клиент привязан к неверному buyer | Исправление только audit/relink, без переписывания заказов | CRM + SALES | MISSING | PG-CRM-R2-G03 | NOT RUN |
| CRM-R2-G04 | Заказ принадлежит другому seller tenant | Не виден из чужой CRM | CRM + SALES | MISSING | PG-CRM-R2-G04 | NOT RUN |
| CRM-R2-G05 | Архивация CRM-клиента после заказа | История заказа и immutable snapshot сохраняются | CRM + SALES | MISSING | PG-CRM-R2-G05 | NOT RUN |
| CRM-R2-G06 | Клиент сделал заказ до появления CRM карточки | Backfill после verified link с evidence | CRM + SALES | MISSING | PG-CRM-R2-G06 | NOT RUN |
| CRM-R2-G07 | Buyer сменил подтверждённый email | Связь identity стабильна, email не ключ идентичности | CRM + SALES | MISSING | PG-CRM-R2-G07 | NOT RUN |
| CRM-R2-G08 | Две конкурентные операции linking одного buyer | Unique constraints / compare-and-set предотвращают конфликт | CRM + SALES | MISSING | PG-CRM-R2-G08 | NOT RUN |
| CRM-R2-G09 | CRM_READ без SALES_READ получает order summary | Нет обхода SALES permissions через CRM | CRM + SALES | MISSING | PG-CRM-R2-G09 | NOT RUN |
| CRM-R2-G10 | Отмена SALES заказа при открытом CRM follow-up | Событие состояния отражается без ложного auto-complete | CRM + SALES | MISSING | PG-CRM-R2-G10 | NOT RUN |
| CRM-R2-G11 | У buyer есть заказы в двух независимых организациях | Истории tenant не смешиваются | CRM + SALES | MISSING | PG-CRM-R2-G11 | NOT RUN |
| CRM-R2-H01 | Создание NOTE с произвольным текстом | До encrypted body/AAD контракт должен быть недоступен как полноценная заметка | CRM + FILES | MISSING | PG-CRM-R2-H01 | NOT RUN |
| CRM-R2-H02 | Шифрованный outcome доступен CRM_READ без PII grant | Запрет расшифровки sensitive outcome | CRM + FILES | MISSING | PG-CRM-R2-H02 | NOT RUN |
| CRM-R2-H03 | Поиск по фрагменту защищённой заметки | Не создавать plaintext index автоматически | CRM + FILES | MISSING | PG-CRM-R2-H03 | NOT RUN |
| CRM-R2-H04 | Прикрепление файла к клиенту PERSON | Owner-specific ACL до storage read | CRM + FILES | MISSING | PG-CRM-R2-H04 | NOT RUN |
| CRM-R2-H05 | Файл прикреплён к customer A, скачивание через customer B | Нет получения bytes по подменённому owner | CRM + FILES | MISSING | PG-CRM-R2-H05 | NOT RUN |
| CRM-R2-H06 | Сканер AV недоступен при публикации файла | Fail closed; no published downloadable bytes | CRM + FILES | MISSING | PG-CRM-R2-H06 | NOT RUN |
| CRM-R2-H07 | Удалённое вложение остаётся в старой CRM истории | Audit metadata сохраняется, скачивание соответствует retention policy | CRM + FILES | MISSING | PG-CRM-R2-H07 | NOT RUN |
| CRM-R2-H08 | Смена tenant crypto key во время чтения заметки | Без расшифровки чужим DEK и без потери old envelope | CRM + FILES | MISSING | PG-CRM-R2-H08 | NOT RUN |
| CRM-R2-H09 | Экспорт взаимодействий без CRM_PII_READ | Redacted content, no raw notes/contacts | CRM + FILES | MISSING | PG-CRM-R2-H09 | NOT RUN |
| CRM-R2-H10 | Ошибка внешнего RustFS после записи attachment metadata | Recoverable staged lifecycle без ложного AVAILABLE | CRM + FILES | MISSING | PG-CRM-R2-H10 | NOT RUN |
| CRM-R2-H11 | Повтор attachment команды после timeout | Не создавать вторую логическую связь | CRM + FILES | MISSING | PG-CRM-R2-H11 | NOT RUN |
| CRM-R2-I01 | Кампания стартует при неизвестном consent purpose | Запрет рассылки | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I01 | NOT RUN |
| CRM-R2-I02 | Email outbox повторно доставлен провайдером | Нет повторного бизнес-перехода из-за duplicate callback | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I02 | NOT RUN |
| CRM-R2-I03 | CRM read model отстаёт от SALES event | Явная freshness/offset, не ложный полный отчёт | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I03 | NOT RUN |
| CRM-R2-I04 | Кросс-доменный timeline смешивает события одинаковых UUID | Типизированный owner и tenant в каждом событии | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I04 | NOT RUN |
| CRM-R2-I05 | Системная дата меняется между страницами списка due tasks | As-of snapshot либо явное отсутствие гарантий | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I05 | NOT RUN |
| CRM-R2-I06 | Экспорт 10 000 клиентов под нагрузкой | Keyset/snapshot strategy, bounded memory и permission | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I06 | NOT RUN |
| CRM-R2-I07 | Удаление клиента при legal hold | Удержание аудита и PII policy с отказом незаконного удаления | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I07 | NOT RUN |
| CRM-R2-I08 | Восстановление PostgreSQL после rollback migration | Данные клиентов, consent и события целостны | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I08 | NOT RUN |
| CRM-R2-I09 | Сбой после CRM commit до публикации cross-domain event | Transactional outbox/replay не теряет событие | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I09 | NOT RUN |
| CRM-R2-I10 | Две организации со схожими KATO и именами | Агрегации и отчёты строго tenant scoped | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I10 | NOT RUN |
| CRM-R2-I11 | Повторная обработка событий не в порядке поступления | Версионирование/ordering исключает rollback state | CRM / Outbox / Analytics | MISSING | PG-CRM-R2-I11 | NOT RUN |

## Execution waves and acceptance
- **P0 CRM-R2.1 identity/security:** A03,A05,A07,B04,B10,B11,C01,C02,C04,C07,C08,D04,E03,E04,E06,E08,E10,G01,G02,G04,G08,G09,G11. Before implementing buyer-link, decide authoritative buyer identity, evidence strength, link uniqueness, revocation, correction/relink history; never match by display name/email alone.
- **P0 CRM-R2.2 consent:** C01–C11; policy decision **REQUIRED** on re-consent after reactivation, effective-time inclusivity, consent purpose; no marketing dispatch until genuine transport/consent gate.
- **P1 CRM-R2.3 interactions/tasks:** D01–D11,E01–E11,F01–F11; implement minimal tenant-scoped TaskService only with accepted lifecycle, immutable events, CAS and typed CRM references. Do not create an unneeded generic workflow engine.
- **P1 CRM-R2.4 privacy/files:** B09,H01–H11; approve encrypted note purpose, AAD, retention, read grants, exact owner ACL, scanner fail-closed tests before exposing NOTE body.
- **P1 CRM-R2.5 reporting/recovery:** G03,G05–G10,I01–I11; immutable timelines, independent-domain READ permissions, outbox integrity and populated-DB migration.
- **Decision hold:** D08,D09,F04 and C02 cannot be silently treated as accepted policy. Existing CRM methods cannot prove new TASKS/SALES/FILES semantics by themselves.

## Evidence admission
Maintain per case: code path, named test method, fixture/source SHA, PostgreSQL version, CI workflow URL/run id, test count and skip/failure count, independent SQL assertions, negative/race result. Advance from **NOT RUN** to **PASS** only after actual execution on exact SHA. A simple registry validator proves only schema/count/uniqueness, never product behavior. Overall “99/99 passed” requires all 99 executable service tests passing; release admission additionally requires genuine OIDC, security, migration and external integration gates.

## Service-test implementation trace (increment CRM-R2.1A)

The following tests have been added to `src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java`. All are **AUTHORED / NOT YET EXECUTION VERIFIED**; a test may cover a subset of the business scenario, not automatically the entire journey.

| Scenario IDs | JUnit test | Assertion scope | Remaining evidence |
|---|---|---|---|
| A03, A07 | `crmR2ArchivedCustomerFencesNewAndChangedInteractionsButKeepsReplay` | archival denies new work and follow-up edits; identical old request replay; event count/revision unchanged | PostgreSQL exact-SHA run; archive × record race |
| D02 | `crmR2ChangedReplayPayloadKeepsSingleInteractionAndEvent` | conflicting idempotency key rejected; one row and event | concurrent duplicate request on PG |
| D04, D05 | `crmR2TerminalTransitionRejectsOppositeAndDuplicateWithoutSideEffects` | cancel terminal; reject complete/re-cancel; no extra event | two-thread complete × cancel on PG |
| D06, D07 | `crmR2InvalidAndStaleMutationPreservesFollowupAndAudit` | negative version and stale follow-up rejected; deadline and event count stay constant | two-thread reassignment × deadline race |

**Repository source changes:** `84818bdf33dd417ea4e5614c46db33ae0ef2a6f1`, `db7e236bd5247f1d93b3a14134e265384f8642a5`. Do not label these as PostgreSQL PASS without workflow evidence for the final SHA.

## Current deliverable status
- 99 scenario designs: **AUTHORED** (9 groups × 11; unique IDs and situation titles within this document).
- Four new Java service tests for this second pass: **AUTHORED; no verified exact-SHA PostgreSQL results yet**.
- Existing tested CRM core scope does not prove missing TASKS, buyer-link or protected NOTE functionality.
- Source-confirmed: interaction idempotency, tenant-scoped rows, rowVersion transitions, active member checks; CRM PII boundary and existing interaction JUnit class.
- Do not infer 99-case PostgreSQL PASS, production-ready, or zero gaps from this document.


## CRM-R2.1B — PostgreSQL concurrency regression slice (2026-11-22)

Three **PostgreSQL-gated** two-thread JUnit tests have been authored in `CrmInteractionQualificationTest`, protected by `Assumptions.assumeTrue(databaseProductName.contains("PostgreSQL"))`. They use a start latch, separate Spring service transactions / JDBC connections, and bounded Future waits; sequential H2 execution is not qualifying evidence.

| Case | Added test | Scope | Evidence status |
|---|---|---|---|
| D04 | `crmR21bPostgresConcurrentCompleteAndCancelAreSingleWinner` | Same row-version complete × cancel; exactly one terminal result; one terminal event | CODE AUTHORED, PG NOT VERIFIED |
| A07 | `crmR21bPostgresConcurrentArchiveAndRecordFenceNewActivity` | Archive × create; persisted archived status, no new post-archive record and count consistency | CODE AUTHORED, PG NOT VERIFIED |
| E03 | `crmR21bPostgresConcurrentAssignAndMemberSuspendRejectsSubsequentAssignment` | Concurrent direct membership suspension × assignment; any pre-suspension assignment is admissible; subsequent assignment rejected | SMOKE AUTHORED, PG NOT VERIFIED |

**Strict E03 qualification remains OPEN:** This third test intentionally allows either initial assignment race outcome and does not prove linearization of the membership change and assignment commit. Need two-connection lock-order orchestration with a controlled pending revoke, real trusted IAM suspension service rather than direct SQL, strict commit-order invariants and deadlock/timeout evidence.

**Release evidence requirements:** exact final source commit SHA; PostgreSQL environment version; run URL / exact job / Surefire report; zero skips for the 3 PG-specific tests; repeated runs with distinct schedule order; unchanged audit and revisions on denied commands. Existing prior tests and 99 scenario definitions do not substitute for executed PG tests. Production and controller admission remain HOLD.
