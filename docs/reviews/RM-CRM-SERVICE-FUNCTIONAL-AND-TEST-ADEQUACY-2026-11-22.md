# RM — аудит функциональной полноты сервисов и достаточности доказательных тестов CRM

**Дата:** 2026-11-22  
**Ветка аудита:** `base_dev`  
**Исходный просмотренный commit:** `ab5604e7ad39e71138521e7f0370fc21438bc35e`  
**Режим:** SERVICE-LAYER FIRST / NO NEW CONTROLLERS / NO NEW UI / PRODUCTION HOLD  
**Статус:** code-and-test evidence review + план работ; **не акт приёмки полной CRM**.

## 1. Управленческое заключение

RM уже содержит действующее ядро CRM, а не только набор DTO/таблиц: клиент, компания/физлицо, контакт, PII, согласие, классификаторы, договор и ревизии, поиск, история, взаимодействия, назначения ответственного и следующие действия. Эти операции преимущественно реализованы на Java/Spring и имеют PostgreSQL JUnit-подтверждения локальных инвариантов.

**Но текущий exact-SHA PASS не гарантирует все ожидаемые пользовательские процессы CRM.** В частности, отсутствие полных `CRM-06`, `INT-03`, `INT-04`, `TSK-01..04`, `AUD-03`, `FIN-01..04` означает, что часть сервисных пользовательских маршрутов ещё не может быть завершена без ручного перехода между независимыми сервисами или отсутствующего владельца данных. Недостаток теста не тождественен дефекту кода; наличие отдельных успешных тестов не тождественно доказанному end-to-end бизнес-процессу.

**База RM-R3-v1:** 75 требований, 16 контуров, 46 IMPLEMENTED/с каноническими proof и 29 PLANNED. Это **каталог первой волны**, а не полный объём функций любой CRM. Зафиксированный на исходном SHA `RM Exact-SHA Qualification Admission` завершён `success` ([run 45030039263](https://github.com/alimbetov/RM/actions/runs/45030039263)); профиль PostgreSQL ([run 45030039275](https://github.com/alimbetov/RM/actions/runs/45030039275)) и real RustFS ([run 45030039248](https://github.com/alimbetov/RM/actions/runs/45030039248)) также `success`. **После добавления настоящего документа нужен новый exact-SHA прогон на новом HEAD** — proof предыдущего SHA не переносится.

## 2. Что считать «гарантией пользовательского поведения»

Гарантия здесь **ограниченная и проверяемая**, не абсолютная гарантия отсутствия дефектов. Любой бизнес-сценарий должен иметь тесты одновременно на:

1. **Happy path:** точный результат и реальные persisted rows, а не только отсутствие исключения.
2. **Authorization:** OWNER/ADMIN/MANAGER/MEMBER/VIEWER как поддерживаемые профили, tenant A/B, бывший сотрудник, suspended/deleted, невалидный actor; проверка ролей и object-policy независимо.
3. **Transitions / validation:** допустимые и запрещённые состояния, CAS/revision, граничные значения, отсутствие утечки PII.
4. **Atomicity:** запрет оставляет ноль непредусмотренных изменений во всех затронутых таблицах (включая audit/outbox и бизнес-регистры).
5. **Concurrency/retry:** два порядка коммитов, повтор с тем же request key, конфликтный повтор, rollback/crash/restart, отсутствие дубликатов.
6. **Integration across data owners:** CRM не пишет в SALES/INVENTORY/FILES/TASKS напрямую, используются авторизованные сервисные контракты и неизменяемые snapshots.
7. **PostgreSQL evidence:** реальная БД с миграциями, exact-SHA Surefire XML и обязательный fail-closed admission; H2 — ускоренный smoke, а не доказательство PG-locking.
8. **User acceptance at service boundary:** тест действует как конкретный сотрудник, покупатель, руководитель или аудитор и проверяет видимые данные, историю и итог после повторного чтения.

**Маркировка:** `LOCAL_PROVED` — предметный сервисный путь имеет тест; `PARTIAL` — отдельные тесты есть, но полного пользовательского маршрута нет; `GAP` — требуемая сервисная операция ещё отсутствует; `EXTERNAL_HOLD` — нужны провайдер/браузер/операционные подтверждения. Никаких выдуманных процентов «достаточности тестов».

## 3. Реальная карта функциональности: все 16 контуров

| Контур | Обнаруженные Java-сервисы / фактические операции | Доказательная база, характер | Оставшийся пользовательский пробел | Оценка |
|---|---|---|---|---|
| **IAM / RBAC** | `OidcActorResolver`, `EmailIdentityService`, `SelfServiceTenantOnboardingService`, `MyAccessService`, `TenantAuthorization`, `VerifiedTenantRoleCommands`: subject, email proof, tenant list/selection, RBAC, revoke, bootstrap | `R4LocalJwksIntegrationQualificationTest`, `R4FirstTenantOnboardingQualificationTest`, `IamUserOnboardingProcessQualificationTest`, `TenantRbacRevocationQualificationTest`; PG и negative paths | Внешний OIDC/JWKS провайдер, реальная доставка email, привязка жизненного цикла аккаунта и login; `IAM-06` остаётся PLANNED | PARTIAL / EXTERNAL_HOLD |
| **CRM_CORE** | `AuthenticatedCrmCommands/Queries`, `CrmCustomerService`, `CrmSearchService`, `CrmCategoryService`, `CrmContractService`, `CrmHistoryService`: person/company/contact CRUD, encrypted PII, consent, status, category, contract revision/renewal, customer/contact search | `CrmR1aQualificationTest` — 37 предметных методов, включая tenant filters, PII/AAD, consent/revoke, optimistic conflicts, cross-tenant, contract and audit; только перечисленные проверки, не полная UAT-матрица | Консистентный путь сотрудника `создать → связаться → назначить действие → предложение/заказ → история` ещё не квалифицирован; `CRM-06` PLANNED | LOCAL_PROVED / PARTIAL E2E |
| **CRM_INTERACTION** | `CrmInteractionService`: CALL/MEETING/EMAIL/NOTE, record/get/search, responsible, nextAction, complete/cancel/history | `CrmInteractionQualificationTest`: lifecycle, replay, tenant ownership, read-only, immutable events | `INT-03`: purpose-bound encrypted NOTE body/communication outcome; `INT-04`: typed TASKS linkage. Отдельного полноценного CRM interaction journey proof нет | PARTIAL |
| **CATALOG** | `CatalogItemService/QueryService`: tenant SKU, draft, publication, price history, archive guards | `CatalogQualificationTest`; commercial E2E использует опубликованный SKU | `CAT-04` bulk migration + reconciliation/invalid rows не реализован | LOCAL_PROVED / PARTIAL |
| **STOREFRONT** | `SellerOfferService`, `StorefrontManagementService`, `StorefrontOfferService`, `PublishedOfferQrService`: публикация, пауза, цены, QR, CAS | `SellerOfferQualificationTest`, `StorefrontManagementQualificationTest`, `PublishedOfferQrQualificationTest` | UI `STO-05` намеренно STOP; без UI не блокирует сервисный этап, но не является завершённым пользовательским интерфейсом | LOCAL_PROVED |
| **SALES / AUTOORDER** | `AutoOrderService`, `SalesInventoryCommandService`, `SalesPriceProposalService`, `SalesOrderQrReferenceService`, `AuthenticatedSalesCommands/Queries`: checkout, negotiation, immutable quote, buyer/seller order, confirm, cancel, fulfillment, QR history | `CommercialCoreE2EQualificationTest`, `SalesInventoryRbacQualificationTest`, `QrNegotiatedCheckoutQualificationTest`, `SalesOrderQueryQualificationTest`: PG races, accepted price, idempotency, reservation | `SAL-06` real OIDC browser E2E не подтверждён; `SAL-07` invoice/payment отсутствует. CRM→SALES link требует отдельного сквозного service contract | LOCAL_PROVED / PARTIAL |
| **INVENTORY** | `InventoryReservationService`, `InventoryMovementService`, `InventoryTransferService`, `InventoryStocktakeService`, `AuthenticatedInventoryReconciliationRuns`, запросы остатков | `InventoryReservationQualificationTest`, `InventoryTransferStocktakeQualificationTest`, `InventoryQ123QualificationTest`; lock ordering, replay, durable reconciliation, rollback | `INV-07`: единый tenant-wide as-of snapshot между страницами **не квалифицирован**; нельзя переносить доказательства snapshot одной транзакции на всю страницу/весь tenant | LOCAL_PROVED / PARTIAL |
| **FILES** | `AuthenticatedFilesCommands/Attachments/BinaryReads`, `FileUploadCompletionService`, `RustFsFileStorage`, `ClamAvFileScanner`; CRM attachment, storage and scan | `FilesR10a/bQualificationTest`, `RustFsRealClamAvE2ETest`, restart/restore; negative byte consumption/tenant/scan | `FIL-06` typed ACL для владельцев catalog/sales пока не полон | LOCAL_PROVED / PARTIAL |
| **ORGANIZATION** | `SelfServiceTenantOnboardingService`, `AuthenticatedOrganizationLocations`, `OrganizationLocationService/QueryService`: first tenant, select, warehouse/location | `R4FirstTenantOnboardingQualificationTest`, `OrganizationLocationLifecycleQualificationTest`, `OrganizationLocationQueryQualificationTest` | `ORG-05` — полная first-tenant qualification включает внешний IdP и email transport, пока PLANNED | LOCAL_PROVED / EXTERNAL_HOLD |
| **GEO** | `KatoTerritoryDirectory`, `KatoLocationPolicy` — проверка/чтение справочника КАТО | `KatoTerritoryQualificationTest` | `GEO-03` — обновление полного КЗ справочника, version/source rollback не завершено | PARTIAL |
| **KMS** | `TenantCryptoService`, `TenantKeyService`, `KmsFencedJobService`, `KmsDeploymentGuard` — envelope, persisted decrypt, fencing | `KmsPersistedRoundTripTest`, `KmsFencedJobQualificationTest`, `KmsDeploymentGuardTest` | `KMS-04` — distributed rotation/recovery на реальной инфраструктуре не допущен; HSM не квалифицирован | LOCAL_PROVED / EXTERNAL_HOLD |
| **AUDIT** | `AuditRetentionService/Policy/Scheduler` — retention, legal hold, защищённое управление | `AuditRetentionQualificationTest`, `PostgresAuditPartitionQualificationTest` | `AUD-03`: tenant-safe sanitized cross-domain history query; `AUD-04` UI/export — STOP в этом этапе | PARTIAL |
| **TASKS** | `TaskState` enum; **нет полноценного tenant-owned task application service** в просмотренном main-java tree | Отдельной `TaskServiceQualificationTest` нет; `CrmInteractionService.setNextAction` не заменяет TASKS | `TSK-01..04`, связь `INT-04`; сервисный контракт и persistence требуются | GAP |
| **FINANCE** | `Money` value object; **нет сервиса invoice/payment/allocation** | Нет полноценного финансового JUnit workflow | `FIN-01..04`, `SAL-07`; важно не считать FULFILLED=PAID | GAP |
| **MARKETING** | `CampaignState` enum; **нет campaign/dispatch service** | Нет end-to-end consent suppression provider tests | `MKT-01..03`; нужен purpose-bound consent contract CRM | GAP |
| **SUBSCRIPTION** | `PlanEntitlement` тип; **нет сервиса usage/quota/billing** | Нет concurrency metering/renewal qualification | `SUB-01..03` | GAP |

**Важно:** `LOCAL_PROVED` означает наличие локального доказательного набора, не утверждение, что весь контур испытан во всех сочетаниях роли × tenant × действие. Тотальная Cartesian-матрица RBAC не измерена. Точный список тестов следует сверять по Surefire XML конкретного SHA, а не по числу файлов.

## 4. Критические пользовательские сценарии CRM и тестовая достаточность

Здесь **сценарий** — последовательность вызовов существующих Java-сервисов с реальными `VerifiedActor` и `tenantId`. Новые REST/controller/UI **запрещены**.

| ID | Кто и какой результат ожидает | Реальная точка входа | Имеющееся доказательство | Недостающий acceptance test | Итог |
|---|---|---|---|---|---|
| `CRM-U01` | Владелец впервые создаёт tenant, выбирает его, даёт сотруднику только CRM_READ/WRITE | `SelfServiceTenantOnboardingService`, `MyAccessService`, `VerifiedTenantRoleCommands` | First-tenant + onboarding + role tests | **Один PG service-E2E**: JWKS-validated actor → email proof → tenant → worker membership → READ/WRITE → revoke; tenant B и все нулевые побочные эффекты | PARTIAL |
| `CRM-U02` | Сотрудник регистрирует компанию/человека и контакт, затем ищет и открывает без утечки PII | `AuthenticatedCrmCommands/Queries` | `crmR14ContactCrudEncryptionTenantIsolationAndImmutableAudit`, `customerAndContactSearchAreBoundedTenantScopedAndPrivacyMinimized` | Сервисный маршрут с поиском после persistence/reload, уровнями CRM_READ/PII_READ, suspended и tenant B | PARTIAL |
| `CRM-U03` | Сотрудник фиксирует согласие на коммуникации, отзыв немедленно запрещает использование | `recordEmailConsentOnce`, `hasEmailConsent` | `consentIsOptInAndRevocationWins`, `postgresConcurrentConsentSubmissionCreatesExactlyOneEvent` | Cross-domain dispatch must be denied after revoke; пока MARKETING отсутствует, отдельно доказать decision contract | PARTIAL |
| `CRM-U04` | Менеджер записывает CALL/MEETING/EMAIL, назначает сотрудника, next action, закрывает и видит историю | `CrmInteractionService` | `interactionLifecycleIsIdempotentTenantScopedAndHistoryImmutable` | Полная service-цепочка с активным/заблокированным ответственным, cross-tenant, CAS, immutable history; выбор разных ролей | PARTIAL |
| `CRM-U05` | Сотрудник сохраняет confidential NOTE и outcome без plaintext | `CrmInteractionService` | Interaction kind NOTE существует, но **не доказывает** защищённое тело | `INT-03`: encrypted body + tenant and purpose AAD + read/write permission matrix, tamper and retention checks | GAP |
| `CRM-U06` | После контакта сотрудник создаёт задачу, получает напоминание, закрывает её из CRM | Пока нет полного `TASKS` owner | Нет | `TSK-01/02` + `INT-04`: single task source of truth, assignment, transition, negative tenant tests, retry/outbox | GAP |
| `CRM-U07` | Менеджер создаёт/продлевает договор и получает историю ревизий/сигнал о скором истечении | `CrmContractService`, `CrmHistoryService` | `crmR13ContractRenewalImmutableRevisionsAndArchiveGuard`, `crmR12LifecycleVersionAndDateEdgeCases` | Full worker path, deadline boundaries/timezone, archive/restore and history after reload | PARTIAL |
| `CRM-U08` | Клиент из CRM покупает опубликованный товар; продавец подтверждает, резервирует, исполняет заказ; CRM сохраняет связь | `AutoOrderService`, `AuthenticatedSalesCommands`, `InventoryReservationService` | `skuToSellerOfferToCheckoutToFulfillmentToReconciliation` | **Нет доказанного CRM-customer → buyer/order association policy**. Нужен typed read-only CRM commercial timeline; нельзя создавать второй sales ledger | PARTIAL |
| `CRM-U09` | Менеджер видит полный путь клиента: interaction → contract → order → payment | CRM + SALES + FINANCE | Раздельные CRM history и sales read tests | Финансовый контур не готов; пока квалифицировать timeline только до order/fulfillment без ложного PAID | GAP |
| `CRM-U10` | Пользователь прикрепляет документ к клиенту; другой tenant или без PII grant не читает bytes | `AuthenticatedFilesAttachments/BinaryReads` | `FilesR10bQualificationTest`, RustFS E2E | Сквозной CRM customer-file flow, infected/error/no bytes, revoke × read, restart; typed owner обязательно | PARTIAL |
| `CRM-U11` | Администратор архивирует клиента с контрактами/контактами и видит корректный history после восстановления | CRM lifecycle and contracts | `crmR14CustomerRestoreAndContactParentStatusGuard`, `crmR13ContractRenewalImmutableRevisionsAndArchiveGuard` | Cross-resource rollback/restart, запрещённые операции с archived parent, отрицательные события | PARTIAL |
| `CRM-U12` | Аудитор видит только разрешённые и обезличенные действия по клиенту и договору | `CrmHistoryService` и retention services | Customer/contact/contract history tests, audit retention | `AUD-03`: единый tenant-safe sanitized read model, bounded page, PII redaction, retention/legal hold | GAP |

**Уровни доказательности:** местный unit/H2 тест, PostgreSQL integration, PostgreSQL service journey, external integration E2E. Последний относится к будущей отдельной приёмке и не должен быть «зелёным» по результату первых трёх.

## 5. Приоритетная карта работ (только сервисный слой)

| Очередь | Work package | Что делать в коде | Нужная доказательная приёмка | Предпосылки | Статус |
|---|---|---|---|---|---|
| **P0.1** | `CRM-SVC-01` Business Journey Harness | Создать переиспользуемые JUnit fixtures ролей, tenant A/B, customer/person/contact, email, interaction, sales, inventory и таблицу эффектов; без новых production сущностей | Метод на каждый `CRM-U01/U02/U04/U07/U08/U10/U11`: before/after SQL и tenant negative | существующие service facades | TODO |
| **P0.2** | `CRM-SVC-02` Customer & Contact Journey | Проверить full create/search/get/update/archive/restore; explicit PII reading; корректность выборок при permission revoke | user-role matrix; CAS/stale; encrypted data; persisted re-read; no side effects on 403-equivalent service deny | CRM-SVC-01 | TODO |
| **P0.3** | `CRM-SVC-03` Interaction Journey | Вызов → запись → assign → nextAction → complete/cancel → immutable history в одной пользовательской последовательности | concurrent revision; cross-tenant, inactive assignee, rollback/retry, history ordering, redaction | CRM-SVC-01 | TODO |
| **P0.4** | `CRM-SVC-04` Commercial Timeline Policy | Специфицировать и реализовать только **read-only** CRM→SALES связку, основанную на owner и проверенных ссылках, без новой authority для заказов | CRM customer A/B isolation, buyer/seller access, accepted quote untouched, order replay no duplicate CRM events | CRM-SVC-02, existing SALES | TODO |
| **P0.5** | `CRM-SVC-05` Full Service E2E Qualification | Сшить `U01→U02→U04→U07→U08→U10`; задать role permissions и single actor/tenant provenance | real PG, post-restart re-read, injected failure rollback, snapshot invariants, distinct buyer/seller; exact-SHA job required | P0.1..P0.4 | TODO |
| **P1.1** | `CRM-SVC-06` Encrypted NOTE/Outcome | Реализовать `INT-03`: purpose-specific global/tenant key semantics, NOTE ciphertext and immutable event metadata | plaintext absent, AAD tampering fail-closed, tenant/role leak denied, optimistic revision and backfill/migration | IAM/KMS and interaction | TODO |
| **P1.2** | `CRM-SVC-07` Minimal TASKS Core | `TSK-01/02` и `INT-04`: tenant-owned task lifecycle and typed interaction link; no duplicate task storage in CRM | assignment, due date, completion, idempotency, stale CAS, revoke/tenant A/B, rollback/read-after-restart | CRM-SVC-03 | TODO |
| **P1.3** | `CRM-SVC-08` Consent Decision Boundary | Укрепить разрешение на отправку коммуникаций по purpose, expiry/revoke, immutable consent snapshot; **не создавать маркетинговую рассылку** | consent×revoke two orders, no send admission without consent, dedup and privacy | existing CRM consent | TODO |
| **P1.4** | `CRM-SVC-09` CRM-Safe Audit Read | `AUD-03`: санкционированная cross-domain sanitized история, pagination/source-trace, PII minimization | immutable source logs, isolation A/B, no inference via filters/counts, bounded export-neutral read | audit & CRM history | TODO |
| **P2.1** | `CRM-SVC-10` Catalog/Sales file owner ACL | `FIL-06`: typed owner read/write policy for catalog and sales references | real RustFS + ClamAV, failed scans, file access after tenant/revoke, no byte consumption on deny | FILES + owner ports | TODO |
| **P2.2** | `CRM-SVC-11` Financial Handoff Contract | Только специфицировать источники данных `SALES→FINANCE`; без маскировки fulfillment под payment | contract tests on invoice read-only boundaries and financial HOLD | FINANCE planned | TODO |
| **P2.3** | `CRM-SVC-12` Cross-page Reconciliation | Отдельно выполнить `INV-07` as-of tenant-wide snapshot with fencing, not based on isolated MVCC checks | long paging chain, concurrent receive/transfer, crash/restart/retry, no false drift | inventory | TODO |

### Предлагаемый первый инкремент

Начать **`CRM-SVC-01 + CRM-SVC-02 + CRM-SVC-03`**: имеющиеся сервисы позволяют доказать полезный пользовательский путь без новых сущностей. Затем `CRM-SVC-04/05` связывает CRM с SALES; до этого не вводить новый TASKS/FINANCE код, чтобы сначала увидеть настоящие разрывы взаимодействия.

## 6. Матрица полноты QA и требования к новым JUnit

| Обязательный класс тестов | Уже есть | Где нужно усиление | Exit criterion |
|---|---|---|---|
| Tenant A/B, known foreign UUID | Есть в CRM/FI/ORG/SALES | Многосервисный `CRM customer → interaction → order` без смешивания tenants | 0 внешних строк и PII; отсутствие SQL-oracle |
| Roles / revoked / suspended | Есть `UR-R3.1..R3.3` и domain fixtures | Реальные CRM пользовательские последовательности разных ролей; не только one-command grants | Allowed vs denied and unchanged snapshots |
| Persisted state/immutable history | Есть на customer/contact, contract, interaction, order | Единый re-read и история после каждой фазы сценария | Immutable timelines/counts and exact source refs |
| Idempotency/retry | Есть у consent/order/reservation/transfer | Повтор полного CRM user workflow, replay после частичного отказа | No duplicate customer/event/order/outbox |
| Optimistic concurrency / PG locks | Есть на contact/contract, suspend×checkout, inventory | CRM contact/interaction case vs revoke and close; сценарии с асинхронной обработкой | оба порядка коммитов и отсутствие side effects |
| PII/consent | Есть crypto, AAD negative, consent revoke | NOTE purpose + outcome, combined search/history, exposed DTO minimization | plaintext absent, denial no data leakage |
| Errors and rollback | Есть на отдельные команды | Multi-service fault injection and restart, no orphan links | atomicity or explicit compensating outcome |
| Real infra/external | RustFS/ClamAV real infra, JWKS loopback | External OIDC/email, actual eventual jobs | EXTERNAL_HOLD until individually verified |
| Performance and throughput | Некоторые bounded query tests | p95/p99 и N+1 на customer/contact/interaction searches, tenant-size 1k/10k | recorded reproducible thresholds, not guessed |
| Scenario catalogue completeness | 75 curated R3 requirements, 19 curated UR tests | Отдельный пользовательский CRM registry `CRM-U01..U12` с traceability | Every scenario has JUnit method + exact-SHA report, or explicit GAP |

## 7. Формальный стандарт приёмки

Для перехода отдельного `CRM-Uxx` из PARTIAL/GAP в **SERVICE_QUALIFIED** нужны одновременно:

- утверждённый контракт нужного пользователя, роли, прав на объект и ожидаемого outcome;
- один или несколько **именно сценарных** JUnit тестов, реально выполненных с PostgreSQL (не synthetic pass);
- проверки фактов в БД и отсутствия побочных эффектов для каждого отказа;
- конкурентные/retry/cancel/revoke ветки, где операции могут пересекаться;
- ссылка на конкретный canonical Surefire XML `testsuite/name/testcase`, SHA, Liquibase fingerprint и неизменяемый CI artifact;
- независимый `NOT_ASSESSED` для отсутствующей ветки, а не автоматическое повышение покрытия по факту существования сервиса.

**Service_qualified не означает production-ready.** Внешний IdP, реальный SMTP, UI/UAT и отдельное security admission остаются в HOLD. Новые контроллеры, API, BFF, frontend и изменение прав без матрицы **STOP**.

## 8. Change control и технические границы

- Изменения только в `base_dev`; существующий `main` и 75/16 реестр **не трогать**.
- Не увеличивать функциональное покрытие `46/75` после добавления этого отчёта; разработанные, но не квалифицированные сервисы остаются `PLANNED`.
- Не создавать дублированные customer/order/stock/task источники истины; задача должна принадлежать TASKS, заказ SALES, деньги FINANCE, PII CRM/IAM.
- Существующие файлы с именем `*Controller.java` обнаружены в кодовой базе, но **эта программа не создаёт и не расширяет их**.
- Составить отдельные JUnit case registry и CI gate, когда первые CRM-U сценарии реализованы; не изменять `rm-r3-maturity-report` так, чтобы считалось 100% coverage при missing user-journey tests.
- Следующая работа — прежде всего доказательная доработка текущих CRM application services и тестов, а не новая инфраструктура.

## 9. Материалы, по которым проведено ревью

**Реальные сервисы:** `AuthenticatedCrmCommands`, `AuthenticatedCrmQueries`, `CrmCustomerService`, `CrmInteractionService`, `CrmContractService`, `CrmCategoryService`, `CrmHistoryService`, `SelfServiceTenantOnboardingService`, `AutoOrderService`, `SalesPriceProposalService`, `InventoryReservationService`, `AuthenticatedFilesAttachments`, `AuditRetentionService`; полный Java tree `src/main/java/kz/rm`.

**Фактические тесты:** `CrmR1aQualificationTest`, `CrmInteractionQualificationTest`, `IamUserOnboardingProcessQualificationTest`, `R4FirstTenantOnboardingQualificationTest`, `R4LocalJwksIntegrationQualificationTest`, `SalesInventoryRbacQualificationTest`, `QrNegotiatedCheckoutQualificationTest`, `CommercialCoreE2EQualificationTest`, `InventoryTransferStocktakeQualificationTest`, `FilesR10bQualificationTest` и остальные JUnit classes в `src/test/java/kz/rm`.

**Нормативные рамки:** `docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json`; `docs/spec/RM-UR-R3.5-MATURITY-REPORT-R1.md`; `docs/strategy/RM-STRATEGY-R4-GAP-PRIORITY-v1.json`; `docs/architecture/RM-PLATFORM-STANDARD-R2.md`.

**Граница достоверности:** это ревью публичных сигнатур, состава исходников, именованных предметных JUnit-методов и GitHub workflow conclusions, **а не построчная формальная верификация всех сервисов**. Для точной атрибуции каждого пользовательского CRM-кейса требуется новый evidence registry и запуск на SHA с новыми тестами. Сценарии в таблице 4 — предметное целевое ТЗ, не утверждение о наличии соответствующих JUnit.
