# RM SELLER-EDIT-R0 — диагностика сервисов и доказательств до Excel-импорта

**Дата ревизии:** 2026-11-24. **Ветка:** `base_dev`. **Тип:** preparatory service qualification, **не** реализация `RM-MVP-ONBOARD-01/R1`.
**Нормативная спецификация:** `docs/spec/RM-MVP-ONBOARD-01-XLSX-SELLER-EDITS-TENANT-RBAC-R1.md`.
**Доказанная исходная CI baseline до новых тестов:** `b58e7d2bc8705b58a64b876ae4ffaaab1d36af04`, восемь обязательных доменных групп и exact-SHA admission `SUCCESS` (https://github.com/alimbetov/RM/actions/runs/45319770060).
**Новые проверки:** `CatalogQualificationTest.sellerEditR0*`, добавлены в `base_dev`; статус PASS присваивать только после выполнения PostgreSQL XML на **конечном SHA**.

**ПРЯМОЙ ЗАПРЕТ:** не создавать XLSX renderer/parser, ingestion DTO, HTTP-контроллеры, batch persistence, import receipt, новых пользователей, новые роли, исправления «на всякий случай» или новую бизнес-модель. Текущая работа: read-only source review, тесты существующих сервисных команд, PostgreSQL evidence, дефектный реестр.

## 1. Жёсткие факты по коду

| Домен/сервис | Подтверждено кодом | Ограничение / диагностический вывод |
|---|---|---|
| `TenantAuthorization.requireForCommand` | Сверяет ACTIVE actor, ACTIVE organization workspace и ACTIVE membership; когда `iam_tenant_rbac_config.enforced=TRUE`, использует явные grants по активным ролям сотрудника | При `enforced=FALSE` работает **legacy membership-role fallback**. Для будущего импорта это **не** достаточное admission; требуется обязательное строгое `enforced=TRUE`. |
| `VerifiedTenantRoleCommands` / `TenantRoleService` | Tenant owner управляет ролями, grant/revoke, assign/unassign, enable; права разграничены по tenant | `TENANT_RBAC_MANAGE` не делегируется обычной ролью. Будущий импорт не должен получать «IMPORT_ALL» или SYSTEM actor. |
| `CatalogItemService` | `createDraft`, `updateModelCode`, `upsertTranslation`, `addPriceRevision`, `publish`, `archive` с tenant и domain permission; `rowVersion` CAS и append-only price/events | Модель и перевод изменяются только у `DRAFT` (`lockedDraft`). Редактирование названия уже опубликованного SKU **не поддержано данным сервисом**: не обещать это в будущих шаблонах. SKU — стабильный ключ и не массово переименовывается. |
| `StorefrontOfferService` | `createDraft`, `updatePrice`, `publish`, `pause`, `hide` с отдельными `STOREFRONT_OFFER_MANAGE` и `STOREFRONT_OFFER_PUBLISH`, tenant locking, price revisions, immutable accepted order snapshots | Seller offer price **не равен** catalog price. Никакого обхода явной публикации или price confirmation. |
| `InventoryAuthorizedCommandService.receive/correct` | Требует `INVENTORY_RECEIVE` или `INVENTORY_ADJUST`, использует `InventoryMovementService.adjust`, который проверяет tenant/location/item и ведёт ledger/idempotent request-key | **P0-BLOCKER-R0-01:** на уровне `InventoryAuthorizedCommandService` отсутствует вызов `WarehouseStaffScopeService.requireAssigned` для receive/correct. Tenant grant ещё не гарантирует право сотрудника на конкретный склад. Не допускать будущий import stock до отдельного red-test, минимального исправления с lock DAG и PostgreSQL двух порядков revoke. |
| `WarehouseStaffScopeService` | Существующая scoped authorisation с записью assignment/revocation и транзакционными блокировками | Ужесточение не требует нового IAM домена; нужно переиспользовать на штатном inventory command boundary, не в обход через Excel. |
| `CommerceCurrencyAdmission` | Strict-service mode требует валютный admission; KZT-only policy квалифицирована для commerce | XLSX KZT enforcement и синхронизация preview/commit ещё не существуют; квалифицировать только после seller-edit gate. |
| `CatalogItemService.createDraft` | SKU tenant-owned, uniqueness и immutable event | Нет самостоятельного batch request receipt на все команды. Нельзя автоматически утверждать exactly-once для импорта из одной SKU-уникальности. |
| `Task/CRM/Sales` | Отдельно проверены W1–W6, в том числе Business и QR journeys | На подготовительном этапе **не** расширять эти домены. Проверять, что seller price change не переписывает order/quote snapshots. |

## 2. Реестр тестового покрытия по 24 SEC-сценариям

**Статусы:**
- **CANDIDATE** — в репозитории найден существующий подходящий тест, фактический PASS на конечном R0 SHA требует evidence;
- **NEW** — добавлен новый тест `sellerEditR0*`, подлежит исполнению на PostgreSQL;
- **PARTIAL** — существующий тест покрывает отдельный инвариант, но не весь SEC-сценарий;
- **BLOCKER** — требование не обеспечено текущей service boundary; нужен red-test и точечное исправление отдельно от Excel;
- **DEFERRED** — сценарий требует ещё не реализованных XLSX/preview/batch контрактов; **не** считать покрытием текущего этапа.

| Сценарий | Статус | Точное доказательство или отсутствующий контракт |
|---|---|---|
| `SEC-01` чужой tenant item/offerId | NEW + CANDIDATE | `CatalogQualificationTest.sellerEditR0SameSkuAcrossTenantsUsesEmployeeSpecificGrants`; `StorefrontManagementQualificationTest.grantsAreRequiredAndForeignStorefrontIsNotAddressable` |
| `SEC-02` одинаковый SKU в A/B | NEW | `CatalogQualificationTest.sellerEditR0SameSkuAcrossTenantsUsesEmployeeSpecificGrants`, SQL oracle по обоим tenant |
| `SEC-03` один сотрудник, разные grants A/B | NEW | `CatalogQualificationTest.sellerEditR0SameSkuAcrossTenantsUsesEmployeeSpecificGrants`, price deny в B и allow в A |
| `SEC-04` catalog editor без price grant | NEW + CANDIDATE | `CatalogQualificationTest.sellerEditR0RevokedPriceGrantCannotAppendAnotherRevision`; `draftTranslationsAndVersionCasCannotLeakOtherTenant` |
| `SEC-05` offer manage без publish | CANDIDATE | `StorefrontManagementQualificationTest.managementAndPublicationPermissionsAreIndependent` |
| `SEC-06` readonly preview→apply | DEFERRED | Preview и apply в рамках R0 намеренно не существуют; прямые service-deny тесты: `UserRoleMatrixR31QualificationTest.urR31AllFiveMembershipRolesHaveFallbackReadButNoImplicitWrite` |
| `SEC-07` owner не обходит доменные grants | PARTIAL | `UserRoleMatrixR31QualificationTest.urR31ExplicitGrantsAuthorizeEachRoleAndRevocationFencesNextCommand`; отдельный owner-only-negative test ещё требуется |
| `SEC-08` нет складского назначения | **BLOCKER** | `InventoryAuthorizedCommandService.receive/correct` не вызывает `requireAssigned`; ранее защищённые `SalesInventoryCommandService` и QR операции не подменяют этот контракт |
| `SEC-09` отзыв роли между preview/commit | PARTIAL | `CatalogQualificationTest.sellerEditR0RevokedPriceGrantCannotAppendAnotherRevision`, `StorefrontManagementQualificationTest.pass09RevocationIsEffectiveAcrossBothOfferEntryPoints`; preview нет |
| `SEC-10` revoke против массового apply | PARTIAL | `StorefrontManagementQualificationTest.postgresAdmittedSellerCommandCompletesBeforePermissionRevoke` и другие per-command race tests; массового commit нет |
| `SEC-11` двое продавцов, одна price version | NEW | `CatalogQualificationTest.sellerEditR0PostgresTwoEditorsOneCatalogPriceRevisionWins`; `StorefrontManagementQualificationTest.concurrentSellerPriceUpdateAndCheckoutCannotMixCommercialSnapshot` |
| `SEC-12` цена против QR/quote snapshot | CANDIDATE | `StorefrontManagementQualificationTest.offerVersionCasAndCheckoutSnapshotStayImmutable`, `curR1cPostgresCheckoutVsApprovedPriceHasSingleSnapshot` |
| `SEC-13` foreign offer не меняет чужой QR | PARTIAL | `PublishedOfferQrQualificationTest.qrP02SameSkuAcrossSellersAndForeignTenantMutationsRemainIsolated`; нет batch path |
| `SEC-14` две строки одной цены в workbook | DEFERRED | Отсутствует XLSX parser и rowset normalization |
| `SEC-15` lost-response повтор импорта | DEFERRED | Per-domain stock replay: `InventoryMovementQualificationTest.concurrentSameKeyPostsExactlyOneMovement`; batch receipt отсутствует |
| `SEC-16` запрос того же requestKey иного tenant/actor | DEFERRED | Batch payload identity и сквозной receipt отсутствуют |
| `SEC-17` устаревший rowVersion при записи | CANDIDATE | `CatalogQualificationTest.draftTranslationsAndVersionCasCannotLeakOtherTenant`, `StorefrontManagementQualificationTest.offerVersionCasAndCheckoutSnapshotStayImmutable` |
| `SEC-18` неверный KZT/qty/warehouse | PARTIAL | `CommerceCurrencyAdmissionQualificationTest.curc03UsdPublishedLegacyOfferRejectedWithoutOrder`, `InventoryMovementQualificationTest.cannotReduceBelowReservedOrCrossTenantReference`; workbook validation отсутствует |
| `SEC-19` формулы, внешние ссылки, zip bombs | DEFERRED | XLSX parser отсутствует; не создавать его в R0 |
| `SEC-20` CRM PII в workbook | DEFERRED | CRM XLSX находится после отдельного privacy gate |
| `SEC-21` склад закрыт между preview/apply | PARTIAL | `OrganizationLocationR11ConcurrencyQualificationTest.postgresDeactivationWinsThenReceiptCannotInitializeOrPost`; импорт отсутствует |
| `SEC-22` rollback середины XLSX batch | DEFERRED | Нет общей batch boundary; не обещать all-or-nothing |
| `SEC-23` валютная policy меняется после preview | PARTIAL | `CommerceCurrencyAdmissionQualificationTest.curc06PostgresPolicyChangeVsCheckoutLinearizes`; preview отсутствует |
| `SEC-24` spreadsheet formula injection export→import | DEFERRED | Экспорт XLSX ещё не существует |

## 3. Матрица шести пользовательских EDIT-сценариев

| ID | Статус | Тесты/разрыв |
|---|---|---|
| `EDIT-01` draft→изменение→цена→publish | PARTIAL | `CatalogQualificationTest.twoTenantCatalogSkuPricePublicationAndReadOnlyBoundary` и `draftTranslationsAndVersionCasCannotLeakOtherTenant`; нужен единый сценарий с раздельными actor grants и SQL audit |
| `EDIT-02` seller price revision не пересчитывает старый заказ | CANDIDATE | `StorefrontManagementQualificationTest.offerVersionCasAndCheckoutSnapshotStayImmutable` |
| `EDIT-03` scoped warehouse receive и readonly-deny | **BLOCKER** | `InventoryAuthorizedCommandService.receive/correct` не проверяет warehouse assignment; доменные stock grants уже квалифицированы отдельно |
| `EDIT-04` 1000 строк, один ошибочный SKU, full rollback | DEFERRED | Импорта нет; требования к batch зафиксированы без ложного PASS |
| `EDIT-05` два редактора, одна версия товара | NEW | `CatalogQualificationTest.sellerEditR0PostgresTwoEditorsOneCatalogPriceRevisionWins` |
| `EDIT-06` export→edit→preview→apply→export | DEFERRED | XLSX-контракт не реализован |

## 4. Очередь подготовительных работ без функционального расширения

| Порядок | Код | Операция | Результат |
|---|---|---|---|
| 1 | `R0-DIAG-01` | Прогнать новые `sellerEditR0*` вместе с прежним `CatalogQualificationTest` на PostgreSQL, проверить SQL oracle/skip=0 | Подтверждённые SEC-01/02/03/04/09/11/17 и EDIT-05 в границах реальных методов |
| 2 | `R0-DIAG-02` | Собрать exact SHA evidence по `StorefrontManagementQualificationTest` и tenant role matrix | Не утверждать snapshot/race PASS без исполняемого XML |
| 3 | `R0-DIAG-03` | Изолированный red-test для `InventoryAuthorizedCommandService.receive/correct` × warehouse assignment, два склада/две tenant | Воспроизводимый P0 security blocker, затем **отдельное минимальное исправление** existing owner service и повторные PG тесты |
| 4 | `R0-DIAG-04` | Owner without domain grant и staff revoke/disable/foreign tenant негативные тесты | IAM RBAC proof для будущего seller-edit |
| 5 | `R0-DIAG-05` | Price CAS race с параллельным checkout; проверить immutable order snapshots, историю, inventory | Нет новых API/модулей |
| 6 | `R0-REPORT` | Один evidence manifest: source SHA, run URLs, suite/method, tests/fail/error/skip, SQL oracle, P0 HOLD/QUALIFIED | Протокол готовности **к проектированию**, но **не** к импорту |

### Stop/Go

- `STOP`: отсутствие warehouse scope при receive/correct; FKs чужого tenant; роль отозвана, но запись успешна; двойная price revision; изменение уже принятой цены заказа; ошибки/пропуски обязательных PG методов.
- `GO` только для **следующего подготовительного red-test/patch review**, когда соответствующий P0 закрыт и CI выполнен на конечном SHA.
- **Не ставить `SELLER_EDIT_SECURITY_PASS`, `XLSX_IMPORT_PASS` или `ONBOARD_SERVICE_QUALIFIED`** по этому документу: это реестр обнаруженного покрытия и блокеров.
- W1–W6 baseline остаётся завершённым на ранее подтверждённом SHA; любое изменение branch HEAD требует отдельного exact-SHA подтверждения. Никакого merge в `main` или production admission.

**Итог:** сейчас тестируем обычную работу продавца и сотрудника, а не импорт. Сначала доказываем, что сервисы невозможно использовать для записи в чужую организацию/склад и без полномочий. Только после отдельного разрешения можно вернуться к концепции XLSX.
