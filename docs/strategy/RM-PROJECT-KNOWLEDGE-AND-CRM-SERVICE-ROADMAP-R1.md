# RM — единый технический реестр состояния и программа гарантированного CRM service behavior

**Дата составления:** 2026-11-22 · **Ветка:** `base_dev` · **Режим:** Java/Spring service-only, PostgreSQL evidence. **Контроллеры, REST, BFF, UI: STOP. Production: HOLD.**

## 1. Источники истины и принцип обновления

Этот документ **не заменяет** канонические машиночитаемые источники:
- `docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json` — 75 требований/16 доменов; **46 IMPLEMENTED, 29 PLANNED**.
- `docs/strategy/RM-STRATEGY-R4-GAP-PRIORITY-v1.json` — ранжированный backlog ровно 29 gaps; это план, не доказательство реализации.
- `docs/strategy/RM-CRM-SERVICE-JOURNEY-REGISTRY-v1.json` — **12** пользовательских CRM-сценариев, evidence-пути, гарантии и пробелы.
- `docs/reviews/RM-CRM-SERVICE-FUNCTIONAL-AND-TEST-ADEQUACY-2026-11-22.md` — предметное ревью публичных Java API и тестов.
- `docs/spec/RM-UR-R3.5-MATURITY-REPORT-R1.md` — схема автоматически вычисляемой зрелости.
- `.github/scripts/verify_rm_qa_01b.py` — обязательные PostgreSQL/real-infra Surefire proof и fail-closed admission.
- `docs/architecture/RM-PLATFORM-STANDARD-R2.md` — архитектурные ограничения и data ownership.
- `docs/spec/RM-QR-R1.4-CANONICAL-FUNCTIONAL-TECHNICAL-CONTRACT.md` — контракты QR-вертикали.

**Изменение функциональной зрелости разрешено только после реального exact-SHA GitHub CI.** Наличие класса или метода теста в исходниках не делает тест успешно исполненным. Доказательства старого SHA никогда не распространяются на новый commit автоматически.

## 2. Снимок функционального покрытия всех 16 контуров

| Контур | R3 реализовано | Планируемые требования |
|---|---:|---|
| IAM / RBAC | 5/6 | IAM-06 |
| CRM Core | 5/6 | CRM-06 |
| CRM Interaction | 2/4 | INT-03, INT-04 |
| CATALOG | 3/4 | CAT-04 |
| STOREFRONT | 4/5 | STO-05 |
| SALES / AutoOrder | 5/7 | SAL-06, SAL-07 |
| INVENTORY | 6/7 | INV-07 |
| FILES | 5/6 | FIL-06 |
| ORGANIZATION | 4/5 | ORG-05 |
| GEO | 2/3 | GEO-03 |
| KMS | 3/4 | KMS-04 |
| AUDIT | 2/4 | AUD-03, AUD-04 |
| TASKS | 0/4 | TSK-01, TSK-02, TSK-03, TSK-04 |
| FINANCE | 0/4 | FIN-01, FIN-02, FIN-03, FIN-04 |
| MARKETING | 0/3 | MKT-01, MKT-02, MKT-03 |
| SUBSCRIPTION | 0/3 | SUB-01, SUB-02, SUB-03 |

**Сумма:** 46/75 реализовано (61,33%). Это охват зафиксированного R3-v1 *первого релизного набора*, не 61,33% всех возможностей конкурирующих CRM/ERP. Новый набор CRM user-journey tests **не меняет** эти 46/75.

## 3. Фактически существующие сервисные возможности

**IAM/ORGANIZATION:** `OidcActorResolver` и загрузка JWKS (в локальном интеграционном тесте), `EmailIdentityService` с токеном подтверждения, `SelfServiceTenantOnboardingService`, `MyAccessService`, ролевая матрица/tenant authorization, глобальная блокировка. Внешний HTTPS IdP и provider email delivery **не квалифицированы**.

**CRM:** `AuthenticatedCrmCommands` и `AuthenticatedCrmQueries`; создание человека и компании, encrypted customer/contact PII, поиск с tenant-фильтрами, purpose-bound email consent event, ревокация, CRUD/архивирование/восстановление, категории, договоры и immutable history. `CrmInteractionService` поддерживает CALL/MEETING/EMAIL/NOTE **без защищённого NOTE body**; record, assign, due date, complete/cancel и event history.

**SALES/STOREFRONT/CATALOG:** публикация предложения, checkout, постоянный QR, buyer- and seller-views, согласованная персональная цена, quote consumption, идемпотентность checkout. **SALES хранит свой order и buyer_user_id; CRM хранит свой customer**, без подтверждённого customer↔buyer identity binding. Не объединять их через email или название.

**INVENTORY/FILES:** резерв/движения/transfer/stocktake, durable reconciliation с отдельными MVCC доказательствами, warehouse pickup; real RustFS и ClamAV с clean/EICAR/error тестами. **Не подтверждён общий as-of tenant-wide snapshot между всеми страницами сверки.**

**AUDIT/KMS/GEO:** retention и immutable event history, tenant envelope encryption/guarded key rotation, KATO reference. Доказательства локальной Java конфигурации не равнозначны внешнему HSM или disaster recovery.

**TASKS/FINANCE/MARKETING/SUBSCRIPTION:** полноценные application services ещё не готовы; наличие enum/DTO не следует считать реализованным пользовательским сценарием.

## 4. Уровень гарантии 12 пользовательских CRM-сценариев

| Journey | Текущий реестр | Требования | Поддерживающих JUnit | Что блокирует полноценную приёмку |
|---|---|---|---:|---|
| CRM-U01 | PARTIAL | IAM-06, ORG-05 | 2 | Real external OIDC/email and complete principal-to-employee role journey |
| CRM-U02 | PARTIAL | CRM-01, CRM-06 | 1 | Full write/read/PII role matrix, revocation and persistence after process restart |
| CRM-U03 | PARTIAL | CRM-02, MKT-01 | 1 | Marketing dispatch admission, consent-vs-send race, quota and no delivery after revoke |
| CRM-U04 | PARTIAL | INT-01, INT-04 | 2 | Active/inactive reassignee, both concurrency orders, task owner lifecycle |
| CRM-U05 | GAP | INT-03 | 0 | Encrypted NOTE/outcome body with AAD and purpose-scoped read permissions |
| CRM-U06 | GAP | TSK-01, INT-04 | 0 | Tenant-owned TASKS lifecycle and typed interaction reference |
| CRM-U07 | PARTIAL | CRM-04, CRM-06 | 1 | Contract deadline, archived parent and all assigned role negative cases |
| CRM-U08 | BLOCKED_PROVENANCE | SAL-06, CRM-06 | 1 | No verified crm.customer→sales_order identity link; never infer from email, name, tenant or line |
| CRM-U09 | GAP | FIN-01, SAL-07 | 0 | Finance owner invoice/payment/allocation, no fulfillment-as-payment inference |
| CRM-U10 | PARTIAL | FIL-05, FIL-06 | 1 | End-to-end CRM attach→read→revoke→restore with actual RustFS/ClamAV |
| CRM-U11 | PARTIAL | CRM-03, CRM-06 | 1 | More multi-resource failure injection, concurrent archive against contract creation, reload |
| CRM-U12 | GAP | AUD-03, AUD-04 | 0 | Bounded cross-domain read model, redaction, isolation, retention proof |

**Результат:** `PARTIAL` — 7, `GAP` — 4, `BLOCKED_PROVENANCE` — 1; **полностью аттестованных user journeys: 0** на уровне полного описанного сценария. Это не означает, что базовые CRUD-сервисы сломаны: недостаточно сквозной доказательной приёмки.

## 5. Реально добавленные CRM service journey tests

Все методы находятся в `src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java` и включены как обязательные в `.github/scripts/verify_rm_qa_01b.py`:

| Метод | Какое наблюдаемое поведение доказывает | Граница |
|---|---|---|
| `crmSvc01CustomerContactInteractionAndContractJourneyPersistsAcrossReads` | Customer→contact→поиск→CALL→nextAction→complete→contract→history, tenant negative | Нет отдельного процесса перезапуска и полной RBAC Cartesian matrix |
| `crmSvc03ReadonlyEmployeeCannotCompleteOrReadAfterPermissionRevoke` | CRM_READ, запрет записи, revoke, неизменность количества событий | Не весь набор ролей и операций |
| `crmSvc04ConsentLifecycleRevocationOverridesGrantWithoutDuplicateEvents` | Grant/replay/revoke, purpose isolation и отсутствие чужих событий | Не означает, что MARKETING dispatch существует |
| `crmSvc05CustomerArchiveContractGuardAndRestorePreserveTimeline` | Active contract запрещает archive без побочных event; разрешённое archive/restore сохраняет contact/history | Не проверены все конкурирующие действия contract×archive |

Три последних метода либо новые, либо расширяющие предыдущий срез. Их **CI verdict нужно читать на конкретном HEAD** после завершения PostgreSQL + exact-SHA; не повышать статусы по факту коммита.

## 6. Договорённость по CRM-SVC-04 — происхождение связи customer ↔ order

**Нельзя реализовывать** query `customerOrders(customerId)` простым поиском `crm.sales_order` по `buyer_email`, `buyer_user_id` без CRM linkage, совпадению имени или tenant. Это создаёт ложные связи и утечки.

Минимально допустимый контракт для следующего среза:
1. **Authoritative relationship proof**: явный подтверждённый `(tenant_id,crm_customer_id,order_id,buyer_user_id,source,revision)`, созданный только после проверяемой процедуры linking. Нельзя доверять произвольной паре IDs, присланной сотрудником.
2. CRM владеет только **связью**, SALES по-прежнему владеет order state, totals, lines и payment-neutral snapshots. Привязка не может переписать order/buyer/quote.
3. Контроль `CRM_READ` и `SALES_ORDER_READ` для read; `CRM_WRITE` + дополнительная policy ownership/consent при linking; обе проверки до побочных записей. Разные buyers/tenants, архивные профили и неподтверждённое сопоставление — DENY.
4. Unique tenant-qualified FK и idempotency; immutable provenance и re-link policy. Два порядка `revoke × link` и `order cancel × link` проверяются PostgreSQL-транзакциями.
5. Пока proof не спроектирован и не испытан, `CRM-U08=BLOCKED_PROVENANCE`, никаких фиктивных customers/orders или SQL-проекций в CRM.

**Решение:** не добавлять преждевременную таблицу связи, пока не утверждена семантика CRM customer → buyer identity; это снижает риск недостоверной клиентской истории.

## 7. Исполняемая дорожная карта (только сервисы)

| Этап | Приоритет | Что делать | Приёмка |
|---|---|---|---|
| SVC-A | P0 | Прогнать 4 перечисленных CRM user-journey метода на PostgreSQL; устранить ошибки, проверить exact-SHA | 4 метода **executed, 0 skipped**, без изменения чисел 75/46/29 |
| SVC-B | P0 | CRM-U01: principal→tenant→сотрудник с явными grants; CRM-U02/U04/U07: полная матрица ролей, stale CAS, concurrency, rollback/restart | PostgreSQL service-E2E, wrong tenant, zero forbidden deltas |
| SVC-C | P0 | Определить owner и безопасную proof-привязку CRM↔SALES, создать только после формального data ownership review | Identity link proof, both race orders, immutable source |
| SVC-D | P1 | CRM-U10 FILES attach/revoke real infra, CRM-U03 consent versus send admission | Real storage/scan, no bytes after deny, no sends after revoke |
| SVC-E | P1 | `INT-03` encrypted NOTE/outcome, `TSK-01/02` + `INT-04` task lifecycle | Encryption AAD, typed links, CAS, retry, rights |
| SVC-F | P2 | `AUD-03` sanitized history, `FIN-01..04` source contracts и расчёты без «оплачен=исполнен» | audited read, payment ledger after independent FIN gate |
| SVC-G | Separate | External IdP/email, production HSM, tenant-wide inventory snapshot | Independent real-infra and operational evidence |

**Стандарт тестов:** allowed and deny paths, actor/tenant A/B, zero rows on deny (включая history/outbox), CAS and concurrent orders, same-key replay and conflicting retry, rollback, read-after-restart, PII redaction; PG proof with exact-SHA Surefire XML. H2 — только smoke.

## 8. Управление изменениями

- Только `base_dev`; `main` не изменять.
- Контроллеры, REST, BFF и frontend **STOP**. Используем существующие application-service boundaries.
- Для связывания данных не вводить второй Sales/customer/inventory owner.
- `docs/strategy/RM-CRM-SERVICE-JOURNEY-REGISTRY-v1.json` — единый машинный source пользовательских сцен; `.github/scripts/verify_rm_crm_journey_registry.py` и его негативные self-tests проверяются в exact-SHA workflow.
- Любой следующий рефакторинг сохраняет старые canonical R3 requirements и обязательную роль/конкурентную матрицу.
- До отдельного security и operational admission **PRODUCTION HOLD**.

## 9. Текущая последовательность действий

**Сначала доказательно закрыть SVC-A/B**, затем согласовать семантику customer ↔ buyer ↔ SALES order для SVC-C. Без этого последующие CRM-дэшборды, маркетинг и финансовая аналитика будут строиться на потенциально ложной идентичности клиента.
