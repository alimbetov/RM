# RM-BSR-R1 — Business Scenario Reliability: сценарная квалификация сервисного ядра RM

**Редакция:** R1.0 / Итерация 1 · 2026-11-24 · **ветка:** base_dev.  
**Статус:** SPECIFICATION / SERVICE-ONLY / NOT EXECUTED / G1 HOLD / PRODUCTION HOLD.  
**Цель:** доказать завершённость BUS-MVP-J1 и QR-MVP-J1 на реальных сервисных entrypoints и PostgreSQL, без UI/HTTP, ручного SQL для выполнения бизнес-операций и подмены доменных владельцев. Документ не разрешает новую функциональность, не изменяет main, не отменяет Feature Freeze.

## 1. Нормативы и приоритет

- [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md) — норматив архитектуры/безопасности.
- [MVP Closure Freeze](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) — scope G0–G4, TASKS basic IN, SLA B1+ и FX_AUTO parked.
- [Core Functional Closure](RM-CORE-FUNCTIONAL-CLOSURE-R1.md), §11.6 — 54 канонических CORE-P0 ID; blob 5bff4938d2ab7d59deec245e470b79e52d51e918.
- [Dual Product Capabilities](../strategy/RM-DUAL-PRODUCT-CAPABILITIES-R1.json) — исторические source/test anchors; blob 3bf0a4a95fa2ad0208aa96502b89435c2f6271e8.
- [CRM journey registry](../strategy/RM-CRM-SERVICE-JOURNEY-REGISTRY-v1.json), [FX R2C](RM-CUR-R2C-SMART-FX-SHIELD-CONTROLLED-AUTO-PRICING-R1.md), действующие QR/DEC-FUL-01/DEC-LOC-03 и owner specs.
- При расхождении — более строгая безопасность, зарегистрировать DECISION REQUIRED, не переписывать контракт. Устаревшее TASKS AFTER CORE в старых документах не отменяет Freeze. В документах отмечены условные legacy controllers; в настоящей квалификации они не используются, новые REST/UI STOP до G1.

## 2. Scope и глоссарий

**Business:** verified identity→tenant/staff→CRM/contact/basic task→PRODUCT SKU→KZT offer→order→confirm/reserve→prepare/handover→history/cancel. **QR:** public offer QR→list либо negotiation→verified checkout→reserve→pick→READY→credential→handover→RMA→inspection/posting. Реальная внешняя идентификация и физическая выдача пользователя заменяются только допустимой синтетической идентичностью и сервисными доказательствами на G1; полноценный UAT не заявляется.

| Термин | Определение |
|---|---|
| Scenario ID | Стабильное бизнес-поведение, не имя JUnit; один ID может требовать несколько tests/variants. |
| Entrypoint | Доверенный production service facade/typed port; не repository и не прямой внутренний bypass. |
| Owner | Авторитетный владелец данных и мутаций, не обязательно инициатор команды. |
| Oracle | Независимое ожидаемое состояние, полученное SQL read-only + подтверждённым контрактом; response — дополнительный сигнал. |
| Linearization point | Момент, определяющий победителя конкурирующих действий согласно транзакционному контракту. |
| Exact SHA | Один immutable commit для source, built artifacts, executed tests, manifest; blob SHA не является release SHA. |
| Receipt / idempotency | Каноническое подтверждение операции и правило повтора в соответствующем scope. |
| Fault seam | Контролируемая точка отказа, не меняющая production business logic. |
| P0 | Нарушение критического сценария MVP/безопасности/инварианта; блокирует G1. |
| G1/G2/G3/G4 | Backend closure / secure usable slice / real pilot / release admission. |
| PASS / FAIL / NOT RUN / BLOCKED / N/A | Verdict отдельно от maturity; N/A только с причиной, reviewer и доказательством отсутствия эффекта. |

### 2.1 Шкала зрелости

| Уровень | Состояние | Требуемое доказательство |
|---|---|---|
| 1 | SPECIFIED | Контракт, owner, сценарий, oracle и открытые вопросы. |
| 2 | IMPLEMENTED | Source review на candidate SHA, production entrypoint и все необходимые owner paths. |
| 3 | SERVICE TESTED | Executed service journey + positive/negative evidence, не unit-test одного метода. |
| 4 | PG QUALIFIED | Реальный PostgreSQL, обязательные security/concurrency/recovery variants и SQL oracles на одном SHA. |
| 5 | USER ACCEPTED | BLOCKED до G2/G3: реальный OIDC/REST/web/phone, роли и UAT. |
| 6 | RELEASE ADMITTED | BLOCKED до G4: отдельная security/legal/ops/capacity приёмка. |

В итерации 1 все строки имеют уровень 1 и verdict NOT RUN: исторические anchors не подтверждают исполнение на выбранном SHA. Потолок G1 = 4. Регрессия на новом SHA требует повторного подтверждения; не терять историю старых результатов.

## 3. Техники квалификации (A)

Префикс техники с дефисом используется в Test ID, например CONC-CORE-SAL-05-A-FIRST; стабильный scenario ID не переименовывается. Несколько техник могут ссылаться на один executable test при доказательстве каждого oracle.

| Test ID prefix | Суть | Когда | Что проверяется | Артефакты |
| --- | --- | --- | --- | --- |
| INV- | Инварианты/независимый SQL | Каждая мутация; fresh connection после commit | Сверить ожидаемое состояние и дельты по owner tables, FK и сумму ledger; не использовать тот же repository как oracle | SQL, параметры, before/after, rows |
| CONC- | Конкуренция | Два независимых соединения/транзакции, барьеры до lock/commit | Оба порядка A→B/B→A, lost update, write skew, phantom при предикате, SQLSTATE 40001/40P01, retry whole tx; не требовать SERIALIZABLE если иной механизм доказан | Isolation, lock graph, timestamps, futures, SQLSTATE, обе дельты |
| TEN- | Tenant isolation | Акторы A/B/AB, одинаковые business keys и чужие UUID | Service read/write/list/count/history, FK и tenant filters; DB role/RLS только если утверждены; raw DB admin не является tenant endpoint | Actor/grants, SQL scoped/unscoped контроль, zero effects |
| IDEM- | Идемпотентность | Повтор команды/потерянный ответ | Same key+payload возвращает канонический receipt; same key+different payload conflict; different key проверяется по business uniqueness, не универсальный dedup; restart | Key hash, receipt IDs, counts, retry log |
| CRASH- | Crash recovery | До commit, внутри tx, после commit до ответа/события | Прервать процесс/соединение в детерминированной точке; partial commit внутри одной tx запрещён, между tx допустим только документированный durable state и recovery | Failpoint, process exit, SQL before/after, restart trace |
| MIG- | Миграции | Fresh и populated PG, expand/contract при заявленной совместимости | Liquibase checksums, ограничения, preservation старых записей; old/new binaries только если контракт допускает, rollback посредством restore/forward fix | Versions, schema diff, historical fixtures, restore output |
| IMM- | Append-only | История цен, движений, receipts, audit | Старые строки/хэши не изменяются; correction = новое событие; проверить deny на service и DB grants если применимо | Ordered rows/hash before/after |
| LOCK- | Locks/CAS/unique | Изменение aggregate и hot key | Проверить реально используемый row/advisory lock, CAS/unique constraint, stale version, lock release при rollback; не навязывать advisory locks | pg_locks, SQLSTATE, version, DDL |
| EVT- | At-least-once/order | Только существующий durable event route | Duplicate, reorder, gap; порядок per aggregate если заявлен, отсутствие глобального порядка не дефект | Event IDs/sequence, attempts, consumer state |
| OUT- | Outbox/inbox | Внешние эффекты при существующем outbox | Business+outbox atomic, dispatch after commit, inbox unique; crash before ACK и redelivery | Outbox/inbox rows, commit/ACK trace |
| CONS- | Idempotent consumer | Повтор доставки | Один эффект на event identity, payload conflict по контракту, restart dedup | Consumer receipts, unique keys, effect count |
| POIS- | Poison/backoff | Невалидный event/provider failure | Bounded attempts, backoff, terminal/quarantine согласно policy; healthy events progress, no secret logs | Clock, retry schedule, sanitized error, terminal state |
| DENY- | Fail closed | Невалидный state/permission/inputs | Нулевые business/receipt/outbox success deltas; отдельный security denial audit допустим по policy | Exception/result code, SQL deltas, denial audit |
| TIME- | Timeout/lost response | Commit response lost, lock timeout | Retry with original key, distinguish unknown outcome from rollback, bounded timeout; no duplicate | Timeout cause, receipt query, replay trace |
| LEASE- | Lease/expiry | Резерв/credential/worker lease только если policy exists | Boundary clock, owner fencing and stale worker; expiry/release exactly once; no invented reserve TTL | Clock, lease epoch, rows, release movements |
| PART- | Partial failure | SALES→INVENTORY→QR/другой owner | Fault on each actual owner boundary, single tx rollback or explicit durable recovery, no orphan | Boundary trace, independent per-owner SQL |
| COMP- | Компенсация/сага | Отмена after pick/return, multi-tx workflow | Only approved reversal, no deletion of historical evidence, idempotent compensation; no fake rollback of physical fact | Original/reversal IDs, state and ledger |
| RBAC- | Отзыв прав | Revoke/suspend × command | Fresh authority at mutation linearization; winner semantics, revoked command no success effects; AB different grants | Grant snapshot, lock/commit schedule, SQL |
| ESC- | Privilege escalation | Nested port/system actor/owner | No caller UUID as authority, no implicit OWNER wildcard, typed trust boundary, no generic fulfill bypass | Call graph, denied attempts, authority provenance |
| PII- | PII | CRM, files, public card, events | Mask/omit by role; no plaintext secrets in logs/events/artifacts, verify byte read not called on deny | Sanitized captured logs, payload schema, storage spy |
| AUD- | Audit | Every auditable change | Actor/tenant/action/correlation/version correct and durable; no success audit on rollback; separate denial audit by policy; not require fabricated event for reads | Audit rows, schema, correlation |
| OWN- | Owner boundary | Every cross-domain command | One authoritative write path, typed port, no duplicate current price/order/stock; inspect source and SQL | Call graph, architecture test, owner deltas |
| XD- | Cross-domain consistency | All critical joins and two J1 paths | Price snapshot, interaction-task, reserve/pick/return, cancel×fulfill, revoke×confirm, return×posting | Multi-owner SQL, correlation, states |
| BOUND- | Границы | Empty/zero/max/null, pages, temporal boundaries | Reject invalid qty/price, exact date cutoff, empty results, stable sorting; no accidental side effect | Inputs, expected/actual, SQL |
| PROP- | Property-based/fuzz | Money/quantity and state transitions | Seeded generator, decimal exactness/rounding per policy, overflow, negative, conservation, shrink counterexample | Seed, generator version, minimal counterexample |
| REAL- | No bypass | All service journeys | Production Spring beans/typed facade; fixtures only prerequisite data, no SQL writes to manufacture outcome, no mocked owner ports; synthetic signed JWT allowed | Bean graph, fixture manifest, trace, SQL read-only oracle |
| OBS- | Evidence reproducibility | Every qualification | SHA, DB/version/isolation, suite/test XML, fixture/seed, no unexpected skip; rerun failed cases not hide flakiness | Manifest, artifact hashes, run/job URLs |

## 4. Протокол исполнения и доказательств

1. G0: freeze candidate SHA, перечень обязательных CI jobs, PostgreSQL version/config/role/isolation, fixture seed, source & test mapping. H2 только compatibility, не PG concurrency evidence.
2. Fixtures: tenants A/B, actor AB с разными grants, owner, seller, warehouse A/B, buyer, anonymous; 2 locations, SKU с нулём/1/несколькими остатками, draft/published/paused offers, verified/unverified links. Synthetic RSA JWT через настоящий resolver; неверная signature/issuer/audience/expiry и ID token deny согласно действующему контракту. SQL разрешён только для setup предпосылок и read-only oracle; запрещён для создания проверяемого результата. Не выводить PII/credentials/keys.
3. SQL mapping перед исполнением: resolve реальные schema.table/columns/constraints из Liquibase + owner source; сохранить queries и параметры. Псевдо-таблицы в матрице — **логические сущности**, не утверждение физического имени. На отдельных соединениях после commit снять before/after, scoped and foreign controls, aggregate counts и immutable hashes; для multi-query oracle использовать согласованный snapshot либо явно описать checkpoint. Пример шаблона: SELECT count(*) FROM <owner_table> WHERE tenant_id=:tenant AND <business_key>=:key; проверить и чужой tenant, negative deltas и orphan anti-join. Не исполнять placeholders.
4. Конкуренция: independent transactions/connections, barrier/latch и bounded timeout; добиться обоих commit/linearization schedules, не sleep-only; при невозможности второго успешного commit доказать блокировку/отказ и корректное состояние. Проверять SQLSTATE и retry всей транзакции, не скрывать deadlock/serialization exception. Для cross-JVM заявленных сценариев минимум два процесса.
5. Recovery: fault before transaction/after first write/before commit/after durable commit before response or dispatch; для внешнего consumer after effect before ACK. Fault injection exception не эквивалентен kill: отдельно пометить simulated vs process crash. Restart с той же PG, повтор через service; compare durable receipt and ledger. No partial commit внутри одной tx; multi-tx only explicit durable state + approved recovery.
6. Security: repeat denied paths with foreign UUID, A/B/AB, revoked grants, inactive membership/workspace, wrong warehouse and PII scopes. Success audit/receipt/outbox delta=0 при deny; разрешён отдельный denial/security audit по контракту. Check actual authorization linearization, not only precheck.
7. Events: только действующие owner events/receipts/outbox по контракту; не изобретать событие там, где предусмотрена лишь история. Если event route не существует и deferred — N/A с review; если обязательный эффект теряется — FAIL. Separate append-only, event order, dedup, retry/lease evidence.
8. Money/quantity: BigDecimal/decimal as contracted; exact expected values from independent oracle, never float; quantity unit/scale, overflow and negative boundaries. Record property seed and minimized counterexample.
9. Evidence manifest per variant: scenarioId, techniqueTestId, tier, journeyStep, candidateSha, sourcePath/line/method, owner, testClass/method, runUrl/job, DB/version/isolation, fixture/seed, schedule/fault, SQL queries + sanitized outputs/hashes, events, expected/actual, verdict, maturity, skipReason, reviewer, artifact hash. JUnit XML must map executed names, counts, failures/errors/skips; missing evidence => NOT RUN/HOLD. Preserve historical evidence append-only.
10. G1 gate: all applicable P0 PG QUALIFIED same SHA, full mandatory H2/PG/security/populated/infra regression per scope; zero unexpected critical skips, zero open P0 defects. G2/G3/G4 separately. No automatic promotion.

## 5. Classification & traceability

- All 54 existing CORE IDs are retained once, provisionally REQUIRED_FOR_MVP (protecting safety unless reviewer proves non-exposed and no shared invariant). Cross-domain links are references, not extra counts. CORE-QR-03 advisory and other potential scope exclusions require written review; no silent removal.
- 120 is existing **target inventory**, not 120 independently enumerated/executed cases in reviewed §FC-60: 15 IAM + 20 CRM + 15 Catalog/Storefront + 25 Sales/Inventory + 15 QR + 20 Pickup/Return/Packaging + 10 cross-domain. Do not fabricate remaining IDs or add 54 to 120. Map each existing test/registry row by semantic equivalence; fill gaps only after review; no new tests simply to reach a number.
- Supplemental BSR rows below are candidate overlays for changed scope/TASKS and uncovered module risks, not automatic new CORE IDs or additive target count. REQUIRED_IF_EXPOSED must be resolved before G1. REGRESSION_IF_PRESENT retains existing mandatory jobs without authorizing new features. POST_MVP is specification only. FINANCE/INSIGHT not delivered by this spec.
- Historical source anchors are candidates, not verified current signatures. An unknown entrypoint/schema/event marks level 1 and a mapping blocker. No new public port purely for test access.
- Source test mapping: reuse capability registry testSourceAnchors and CRM journey registry; for each case determine EXACT / SUPPORTING_PARTIAL / GAP; evidence from a narrower method cannot qualify a wider journey.
- Iteration stability: existing IDs immutable, never recycle; new behavior uses new BSR-<module>-NN or variant ID. Correct errors via changelog and supersession (no deletion). Preserve scope and historical evidence.

## 6. Matrix template (B)

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| <stable ID> | <prefixes> | <Given→When→Then> | <path#method or TBD> | <owner> | happy/neg/concurrent/recovery/security | <contract> | <independent SQL> | <event/receipt or justified N/A> | 1 / NOT RUN | <blocker> |

For each module below, the rows together with the shared variant protocol constitute the initial qualification scope. Every required row gets happy where meaningful, denial/tenant/role, applicable concurrency both orders, lost response/crash/retry/lease and PII checks; read-only queries use repeat/foreign/empty tests, not invented write events. For inapplicable variants record N/A with reason/reviewer, not PASS. Cross-domain scenario executes actual second owner service/typed port and validates both owners. Module sufficiency: all applicable P0 level 4 on same SHA, security invariants, both conflict orders and recovery proven, mandatory regression passes, no unresolved P0. Deferred modules cannot be declared qualified products from absence of implementation.


### 6.1 IAM / Tenant / RBAC / ORGANIZATION

**Границы:** VerifiedActor, tenant choice, active membership, explicit permission, warehouse scope. Organization location belongs to ORG, not IAM.  
**Авторитетные данные:** IAM (account, membership, grants); ORGANIZATION (locations).  
**Роли:** owner, tenant admin, employee, warehouse staff, buyer, trusted system.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Live issuer/JWKS transport, login/session refresh, BFF, invitations via real mail and browser onboarding.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-IAM-01 | IDEM,TEN,INV,REAL | Verified signed principal/email proof → create first tenant twice/restart → one workspace/org/owner, no implicit business grants | SelfServiceTenantOnboardingService.createFirstTenant/selectTenant; OidcActorResolver.resolve [historical anchor] | IAM (account, membership, grants); ORGANIZATION (locations) | happy | atomic workspace+org+owner; proof of verified email; exact replay one tenant | workspace/org/membership by principal + receipt; count=1; no unrelated grants | onboarding receipt/audit if contracted | 1 / NOT RUN | exact proof mechanism/current signature |
| CORE-IAM-02 | DENY,TEN,RBAC,PII | Unverified/disabled/foreign principal → onboarding → deny, no orphan | SelfServiceTenantOnboardingService.createFirstTenant/selectTenant; OidcActorResolver.resolve [historical anchor] | IAM (account, membership, grants); ORGANIZATION (locations) | security | zero tenant/member/orphan rows | delta workspace/org/member/grants=0 | no success event | 1 / NOT RUN | actual exception codes |
| CORE-IAM-03 | RBAC,ESC,TEN,AUD | Owner with explicit rights invites → employee accepts → grant → real business command; AB grants differ → only allowed action | TenantMemberEnrollmentService.invite/accept; TenantAuthorization.requireForCommand; WarehouseStaffScopeService.assign/revoke/requireAssigned [historical anchor] | IAM (account, membership, grants); ORGANIZATION (locations) | security | employee active with explicit scoped permissions only | invitation/member/grant and business owner rows; no implicit roles | membership/grant/domain audit | 1 / NOT RUN | approved bootstrap grant matrix |
| CORE-IAM-04 | CONC,RBAC,LOCK,DENY | Active employee/order → revoke and confirm A→B/B→A → committed authorized winner only | TenantMemberEnrollmentService.invite/accept; TenantAuthorization.requireForCommand; WarehouseStaffScopeService.assign/revoke/requireAssigned [historical anchor] | IAM (account, membership, grants); ORGANIZATION (locations) | concurrent | no unauthorized committed mutation, no deadlock | membership/grant + order/reserve/event deltas | revocation/confirmation if committed | 1 / NOT RUN | lock linearization |
| CORE-IAM-05 | CONC,RBAC,PII,XD | Linked buyer → suspend/delete races link read/checkout → no post-revoke new access, retain permitted immutable evidence | TenantMemberEnrollmentService.invite/accept; TenantAuthorization.requireForCommand; WarehouseStaffScopeService.assign/revoke/requireAssigned [historical anchor] | IAM (account, membership, grants); ORGANIZATION (locations) | concurrent | fresh account status, retained immutable references, no new owner reads | account status, link, orders, history; no orphan | status/link event per contract | 1 / NOT RUN | retention and deletion policy |
| CORE-IAM-06 | CONC,RBAC,TEN,ESC | Warehouse A grant only → attempt B; revoke × pick/handover both orders → scope enforced | TenantMemberEnrollmentService.invite/accept; TenantAuthorization.requireForCommand; WarehouseStaffScopeService.assign/revoke/requireAssigned [historical anchor] | IAM (account, membership, grants); ORGANIZATION (locations) | concurrent | revoked warehouse B cannot be used under A grant | warehouse grant, pick, receipt, movement deltas | scope and successful action audit | 1 / NOT RUN | DEC-LOC-03 current revision |
| CORE-ORG-01 | INV,BOUND,IMM,AUD | Authorized staff create/update/deactivate/reactivate/close location; stale version and active stock deny | AuthenticatedOrganizationLocations.create/update/deactivate/reactivate/close/search; OrganizationLocationService.close [historical anchor] | ORGANIZATION location; INVENTORY/SALES dependencies | happy | row version & recorded audit, warehouse inventory blockers | location/version + stock/reservation dependency + audit | location history | 1 / NOT RUN | approved close blockers |
| CORE-ORG-02 | CONC,LOCK,XD,DENY | Close/deactivate × receipt/transfer/confirm both orders → valid winner, no stranded stock | AuthenticatedOrganizationLocations.create/update/deactivate/reactivate/close/search; OrganizationLocationService.close [historical anchor] | ORGANIZATION location; INVENTORY/SALES dependencies | concurrent | zero stock stranded on inactive warehouse; both orders of commits | location state + balances/reserves/order deltas | location/stock/order events | 1 / NOT RUN | lock ordering |


### 6.2 CRM Customers / Contacts

**Границы:** PERSON/COMPANY, contacts, archive, contracts; no inferred identity link; PII encrypted/masked by policy.  
**Авторитетные данные:** CRM (customer, contact, contract, consent).  
**Роли:** CRM writer, CRM reader, PII reader, owner with explicit grants.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Forms, visible masking, customer portfolio UX, real consent channels.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-CRM-01 | TEN,PII,INV,REAL | Create PERSON/COMPANY and contacts as allowed actor; foreign contact/name duplicate → correct ownership, no inferred merge | AuthenticatedCrmCommands.createPerson/createCompany/addContact/archiveCustomer; CrmContractService (method TBD); CrmInteractionService.record/setNextAction/complete/history [historical anchor] | CRM (customer, contact, contract, consent) | security | encrypted required PII, tenant A/B isolation, no name-based dedup | customer/contact tenant FK, encrypted PII fields, scoped counts | CRM creation audit | 1 / NOT RUN | actual encrypted columns/role policy |
| CORE-CRM-02 | LOCK,TEN,BOUND,PII | Two updates with same version, search empty/bounded/filter → one correct version, no foreign rows | AuthenticatedCrmCommands.createPerson/createCompany/addContact/archiveCustomer; CrmContractService (method TBD); CrmInteractionService.record/setNextAction/complete/history [historical anchor] | CRM (customer, contact, contract, consent) | security | optimistic CAS, consistent permissions, proper bounded page/filter | customer/contact version, independently sorted IDs/count | update history, no read mutation | 1 / NOT RUN | pagination snapshot semantics |
| CORE-CRM-03 | CONC,IMM,XD,COMP | Archive customer/contract × open order creation both orders → approved policy, order snapshot retained | AuthenticatedCrmCommands.createPerson/createCompany/addContact/archiveCustomer; CrmContractService (method TBD); CrmInteractionService.record/setNextAction/complete/history [historical anchor] | CRM (customer, contact, contract, consent) | concurrent | immutable SALES order preserved, proposed policy enforced | customer/contract state + order snapshot hash | archive/history per winner | 1 / NOT RUN | current open order policy |


### 6.3 CRM Interactions / TASKS

**Границы:** CRM owns nextAction; TASKS owns lifecycle; T00 cancellation snapshot only; SLA/outbox B1+ parked.  
**Авторитетные данные:** CRM (interaction); TASKS (task, assignment, event).  
**Роли:** CRM staff, task creator, assignee, manager.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Task board, browser workflows, notifications/SLA automation outside MVP.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-CRM-04 | INV,IDEM,AUD,PII | Record interaction→assign→nextAction→complete; repeated request and inactive assignee → correct history | AuthenticatedCrmCommands.createPerson/createCompany/addContact/archiveCustomer; CrmContractService (method TBD); CrmInteractionService.record/setNextAction/complete/history [historical anchor] | CRM interaction (TASKS separate BSR-TASK rows) | security | no TaskService dependency; correct event history | interaction status/assignee/version, event count | interaction events | 1 / NOT RUN | historic row does not prove TASKS integration |
| BSR-TASK-01 (REQUIRED_FOR_MVP) | INV,REAL,XD,AUD | CRM interaction/customer→create/assign/start/reschedule/complete task, read history; invalid target or suspended assignee denies | TASKS authenticated commands (exact class/method TBD); CrmInteractionService | TASKS + CRM | happy | task target same tenant; only legal transitions; CRM not second task owner | task/assignment/version/history and CRM refs; zero denied deltas | task lifecycle history | 1 / NOT RUN | verify current service source and existing tests |
| BSR-TASK-02 (REQUIRED_FOR_MVP) | CONC,RBAC,IDEM,PART | CRM cancellation/target invalidation × task complete/assign; both orders; retry/restart canonical T00 snapshot | TASKS owner port / CRM canonical cancellation (TBD) | TASKS + CRM | concurrent | approved T00 semantics; no duplicate event or fabricated automatic cancellation | task state and source cancellation snapshot/version/hash | canonical CRM and task events as contracted | 1 / NOT RUN | T00 gate; no B1 expansion |


### 6.4 CATALOG / SKU / Prices

**Границы:** PRODUCT vs SERVICE, revision history, catalog publish not implicit storefront publish.  
**Авторитетные данные:** CATALOG (SKU, translations, catalog price revisions).  
**Роли:** catalog writer/publisher, inventory staff, seller.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Catalog editor, upload widgets, bulk import.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-CAT-01 | BOUND,PROP,TEN,INV | Create draft PRODUCT, translation, price→publish; invalid price/currency/foreign SKU denied | CatalogItemService.createDraft/addPriceRevision/publish/archive [historical anchor] | CATALOG (SKU, translations, catalog price revisions) | happy | only valid product/tenant/currency/price publish | SKU state, i18n, price revision rows | catalog publish history | 1 / NOT RUN | approved currency/scale rules |
| CORE-CAT-02 | IMM,OWN,XD,CONC | Publish/checkout, then change catalog/offer price × new checkout → coherent price, old order unchanged | CatalogItemService.createDraft/addPriceRevision/publish/archive [historical anchor] | CATALOG (SKU, translations, catalog price revisions) | concurrent | accepted order immutable price+SKU; no cross-currency inference | catalog/offer revisions + accepted order price hash | owner price histories | 1 / NOT RUN | authoritative offer price rules |
| CORE-CAT-03 | CONC,LOCK,DENY,XD | Archive × receive/stocktake/publish both orders → no invalid active SKU use | CatalogItemService.createDraft/addPriceRevision/publish/archive [historical anchor] | CATALOG (SKU, translations, catalog price revisions) | concurrent | archive blocked on relevant committed usage, both orders | SKU state, inventory/offer refs | archive or usage event | 1 / NOT RUN | approved blockers |


### 6.5 STOREFRONT

**Границы:** Public offer snapshot and price authority; no duplicated current price in SALES.  
**Авторитетные данные:** STOREFRONT (offer/current commercial price, visibility).  
**Роли:** seller publisher, public reader, buyer, trusted system only if authorized.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Public HTTP/card rendering, browser seller workspace.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-STO-01 | LOCK,RBAC,TEN,AUD | Draft→publish→pause/resume with correct roles; stale version/disabled workspace deny | StorefrontManagementService.createDraft/publish; StorefrontOfferService.createDraft/updatePrice/publish/pause/hide/priceHistory [historical anchor] | STOREFRONT (offer/current commercial price, visibility) | happy | role/CAS/version and workspace status enforced | storefront state/version and tenant | publication audit | 1 / NOT RUN | actual resume method |
| CORE-STO-02 | IDEM,IMM,BOUND,OWN | Offer draft→price→publish→pause/hide; duplicate/stale revisions reject | StorefrontManagementService.createDraft/publish; StorefrontOfferService.createDraft/updatePrice/publish/pause/hide/priceHistory [historical anchor] | STOREFRONT (offer/current commercial price, visibility) | happy | PRODUCT/PUBLISHED only, CAS, historical price retained | offer version/current revision and unchanged prior hashes | offer history | 1 / NOT RUN | duplicate request semantics |
| CORE-STO-03 | CONC,RBAC,XD,DENY | Revoke×publish and pause×checkout both orders → no stale grant or mixed snapshot | StorefrontManagementService.createDraft/publish; StorefrontOfferService.createDraft/updatePrice/publish/pause/hide/priceHistory [historical anchor] | STOREFRONT (offer/current commercial price, visibility) | concurrent | both commit orders, no stale grant or mixed snapshot | grants/offer state/order receipts and deltas | committed publication/checkout | 1 / NOT RUN | linearization |


### 6.6 SALES

**Границы:** One seller/order; immutable accepted amount; PLACED no reserve; proof-required fulfillment fence.  
**Авторитетные данные:** SALES (order, lines, accepted price, state); INVENTORY (reservation/movement).  
**Роли:** buyer, seller, warehouse staff.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Order screens, payment/delivery (post-MVP), browser errors.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-SAL-01 | IDEM,TIME,CRASH,INV | List checkout same key/payload, conflict and lost response/restart → one order per contract | AutoOrderService.checkout/checkoutPublishedQr/checkoutAcceptedQuote; PublishedQrCheckoutService.checkout [historical anchor] | SALES (order, lines, accepted price, state); INVENTORY (reservation/movement) | recovery | one order/receipt/event; conflict denies and creates no extras | order/lines/receipt/event by scoped key, count | checkout receipt/event | 1 / NOT RUN | business uniqueness for different key |
| CORE-SAL-02 | CONC,XD,DENY,IMM | Price/pause change×checkout both orders → acknowledged version or reconfirm, no new order on deny | AutoOrderService.checkout/checkoutPublishedQr/checkoutAcceptedQuote; PublishedQrCheckoutService.checkout [historical anchor] | SALES (order, lines, accepted price, state); INVENTORY (reservation/movement) | concurrent | rejected/reconfirm, zero order/email-outbox if new request | offer versions, order/receipt/outbox deltas | only committed checkout | 1 / NOT RUN | stale response code |
| CORE-SAL-03 | INV,PART,TEN,REAL | PLACED order with multiple lines → confirm selected active warehouse → full reserves only now | OrderInventoryPort.reserve/release/fulfill; InventoryMovementService (method TBD); InventoryReservationService.reserve/release/fulfill; InventoryStocktakeService (method TBD) [historical anchor] | SALES order; INVENTORY reserve/balance | happy | atomic full reserves, order state, original SKU mapping | order status + reservation per line; onHand unchanged | confirmation/reserve evidence | 1 / NOT RUN | actual quantity model |
| CORE-SAL-04 | PART,CRASH,DENY,INV | Last line shortage or injected failure → confirm → all writes rollback | OrderInventoryPort.reserve/release/fulfill; InventoryMovementService (method TBD); InventoryReservationService.reserve/release/fulfill; InventoryStocktakeService (method TBD) [historical anchor] | SALES order; INVENTORY reserve/balance | recovery | all order/reservation/ledger effects rolled back | all order/reserve/movement/audit success deltas=0 | no success event | 1 / NOT RUN | fault seam |
| CORE-SAL-05 | CONC,LOCK,PROP,INV | Two orders compete for last units and reversed SKU ordering → no oversell/deadlock leak | OrderInventoryPort.reserve/release/fulfill; InventoryMovementService (method TBD); InventoryReservationService.reserve/release/fulfill; InventoryStocktakeService (method TBD) [historical anchor] | SALES order; INVENTORY reserve/balance | concurrent | no oversell/deadlock, at most available quantity reserved | sum active reserve <= eligible onHand; per order all-or-nothing | winner reserve/confirm | 1 / NOT RUN | bounded retry policy |
| CORE-SAL-06 | CONC,COMP,IDEM,XD | Cancel×confirm and cancel×fulfill both orders → one valid final state/release/debit | AuthenticatedSalesCommands.confirm/cancel/fulfill; SalesInventoryCommandService.confirm/cancel/fulfill [historical anchor] | SALES (order, lines, accepted price, state); INVENTORY (reservation/movement) | concurrent | one coherent terminal state, no reserved imbalance | state, reserve, ledger and receipt counts | winner lifecycle events | 1 / NOT RUN | transition policy |
| CORE-SAL-07 | CONC,COMP,IMM,XD | PREPARING pick×cancel both orders → deny or approved physical reversal, preserve pick | AuthenticatedSalesCommands.confirm/cancel/fulfill; SalesInventoryCommandService.confirm/cancel/fulfill [historical anchor] | SALES (order, lines, accepted price, state); INVENTORY (reservation/movement) | concurrent | approved unpick/deny policy, preserved physical trace | pick rows unchanged; reversal/stock if policy | pick/cancel/reversal history | 1 / NOT RUN | DEC-FUL-01 current policy |
| CORE-SAL-08 | ESC,DENY,OWN,INV | Proof-required order → generic fulfill; allowed nonpickup separate → deny bypass | AuthenticatedSalesCommands.confirm/cancel/fulfill; SalesInventoryCommandService.confirm/cancel/fulfill [historical anchor] | SALES (order, lines, accepted price, state); INVENTORY (reservation/movement) | security | **DENY** without receipt/movement/state; allowed non-pickup still qualified separately | no state/receipt/movement delta for bypass | no success event | 1 / NOT RUN | channel classification |
| CORE-SAL-09 | CONC,ESC,IDEM,XD | Generic fulfill×secure pickup both orders → exactly one legal debit with proof | AuthenticatedSalesCommands.confirm/cancel/fulfill; SalesInventoryCommandService.confirm/cancel/fulfill [historical anchor] | SALES (order, lines, accepted price, state); INVENTORY (reservation/movement) | concurrent | never two debits; a proof-required COMPLETED always carries receipt | COMPLETED proof order has receipt; debit once | handover history | 1 / NOT RUN | proof fence |
| CORE-SAL-10 | RBAC,TEN,CONC,ESC | Warehouse grants A not B; revoke × each confirm/prepare/fulfill both orders | AuthenticatedSalesCommands.confirm/cancel/fulfill; SalesInventoryCommandService.confirm/cancel/fulfill [historical anchor] | SALES (order, lines, accepted price, state); INVENTORY (reservation/movement) | concurrent | explicitly approved location policy; no unauthorized warehouse | scope/grants + per-operation stock/order deltas | only authorized action | 1 / NOT RUN | DEC-LOC-03 |


### 6.7 INVENTORY

**Границы:** Quantity ledger only; no implicit COGS; one selected warehouse, owner-only stock changes.  
**Авторитетные данные:** INVENTORY (balance, reservation, movement, transfer/stocktake).  
**Роли:** warehouse staff, stock manager, authorized SALES port.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Warehouse UI, physical stocktake UX, valuation.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-INV-01 | INV,CONC,LOCK,PROP | Receive/adjust/transfer/stocktake while reserve active; reversed conflicts and invalid negative → conservation | OrderInventoryPort.reserve/release/fulfill; InventoryMovementService (method TBD); InventoryReservationService.reserve/release/fulfill; InventoryStocktakeService (method TBD) [historical anchor] | INVENTORY (balance, reservation, movement, transfer/stocktake) | concurrent | ledger and stock conserved, no stranded reservation | balances=opening+signed movements; reservation and transfer pair reconciled | movement/stocktake audit | 1 / NOT RUN | eligible stock semantics |
| CORE-INV-02 | BOUND,CONC,TEN,INV | Paged reconciliation concurrent movement; empty page, foreign location → bounded result per declared consistency | OrderInventoryPort.reserve/release/fulfill; InventoryMovementService (method TBD); InventoryReservationService.reserve/release/fulfill; InventoryStocktakeService (method TBD) [historical anchor] | INVENTORY (balance, reservation, movement, transfer/stocktake) | concurrent | no accidental tenant-wide snapshot claim; version/checkpoint consistency | ordered IDs/checkpoint and independent ledger totals | no read mutation | 1 / NOT RUN | checkpoint/snapshot contract |


### 6.8 QR Commerce

**Границы:** Public QR never credential; shared checkout, secure pickup, physical return and disposition.  
**Авторитетные данные:** STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting).  
**Роли:** anonymous, verified buyer, seller, scoped warehouse staff.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Phone camera, browser navigation, real physical handover UAT, payment refund.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-QR-01 | IDEM,CONC,IMM,TEN | Issue/reprint/revoke/reissue QR; revoke×checkout both orders → old ref no new orders | PublishedOfferQrService.issueOrGet/reprint/revoke/reissue; PublishedOfferCardQuery.findPublished; CatalogPublicProductMedia.listPublished [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | concurrent | reprint stable, revoked ref cannot create new order | QR reference/version/status, order counts | QR lifecycle audit | 1 / NOT RUN | reference lifetime |
| CORE-QR-02 | PII,TEN,DENY,OWN | Anonymous public card with clean public/foreign/private/quarantined media → safe projection only | PublishedOfferQrService.issueOrGet/reprint/revoke/reissue; PublishedOfferCardQuery.findPublished; CatalogPublicProductMedia.listPublished [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | security | published-only sanitized projection; no PRIVATE data, no mutation | offer visibility and attachment/scan states; no writes; denied bytes not read | no business event | 1 / NOT RUN | typed media policy |
| CORE-QR-03 | BOUND,INV,OWN | Available/partial/unknown region preview including empty → advisory only | PublishedOfferQrService.issueOrGet/reprint/revoke/reissue; PublishedOfferCardQuery.findPublished; CatalogPublicProductMedia.listPublished [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | happy | does not reserve or promise delivery | inventory rows/reserve/order deltas=0 | no mutation event | 1 / NOT RUN | availability algorithm |
| CORE-QR-04 | CONC,IMM,IDEM,XD | QR list checkout with exact ACK; price drift/retry → snapshot or reconfirm | PublishedQrCheckoutService.checkout; AutoOrderService.checkoutPublishedQr [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | concurrent | correct price/qty/currency or explicit reconfirm; no silent reprice | offer revision + order amount/qty/currency/receipt | checkout evidence | 1 / NOT RUN | ACK shape |
| CORE-QR-05 | CONC,LOCK,RBAC,IMM | Alternate buyer/seller counter/accept/reject; stale revision/self-accept deny | SalesPriceProposalService.submit/buyerRespond/sellerRespond; AutoOrderService.checkoutAcceptedQuote [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | concurrent | actor turn and revision CAS, no self-accept | quote version/turn/status and event counts | negotiation history | 1 / NOT RUN | transition/expiry |
| CORE-QR-06 | IDEM,CONC,TIME,OWN | Accepted quote → two keys/concurrent checkout/restart → at most one order | SalesPriceProposalService.submit/buyerRespond/sellerRespond; AutoOrderService.checkoutAcceptedQuote [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | concurrent | at most one order, immutable agreed price, request-key namespace | quote consumption + order unique relation/receipt | accepted checkout event | 1 / NOT RUN | key namespace |
| CORE-QR-07 | CONC,LEASE,RBAC,DENY | Expire/withdraw/revoke/suspend×checkout both orders, exact cutoff → winner only | SalesPriceProposalService.submit/buyerRespond/sellerRespond; AutoOrderService.checkoutAcceptedQuote [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | concurrent | no order after winning revocation, retry semantics coherent | quote/account state, order/receipt deltas | quote terminal/checkout history | 1 / NOT RUN | clock semantics |
| CORE-PICK-01 | INV,PART,DENY,XD | Confirm→prepare→all picks→ready; incomplete pick deny; no debit | SalesOrderPreparationService.startPreparing/confirmPicked/markReadyForPickup; SalesPickupHandoverService.issue/preview [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | security | no stock debit before handover, incomplete pick DENY | order/pick quantity/reservations, movement unchanged | pick/ready history | 1 / NOT RUN | pick physical semantics |
| CORE-PICK-02 | TEN,RBAC,PII,BOUND | Buyer issues credential; scoped staff previews; foreign/expired deny | SalesOrderPreparationService.startPreparing/confirmPicked/markReadyForPickup; SalesPickupHandoverService.issue/preview [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | security | ownership + warehouse scope; preview zero stock effects | credential owner/version + zero inventory effects | issue audit only if required | 1 / NOT RUN | credential TTL/secret handling |
| CORE-PICK-03 | IDEM,LEASE,IMM,INV | Reissue then old credential deny; new credential handover/retry → one receipt/debit | SalesPickupHandoverService.complete; OrderInventoryPort.fulfill [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | happy | old token denied, one receipt and one set movements | credential consumed + receipt unique + ledger debit once | handover evidence | 1 / NOT RUN | replay response rules |
| CORE-PICK-04 | CONC,RBAC,LOCK,IDEM | Two scans on separate JVMs and revoke×handover both orders → one physical posting | SalesPickupHandoverService.complete; OrderInventoryPort.fulfill [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | concurrent | one winner, same proof, zero second debit | receipt/token/order/movement counts and grants | one handover event | 1 / NOT RUN | cross JVM harness |
| CORE-PICK-05 | CRASH,TIME,PART,IDEM | Kill before/after consume/receipt/commit; restart retry → atomic rollback or durable receipt | SalesPickupHandoverService.complete; OrderInventoryPort.fulfill [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | recovery | total rollback or durable success; safe idempotent recovery | token/receipt/order/reserve/movement all consistent | one durable success event if committed | 1 / NOT RUN | real kill seam |
| CORE-RET-01 | BOUND,CONC,TEN,PROP | Buyer requests RMA for issued quantity; concurrent excess/foreign → bounded accepted qty | SalesReturnRequestService.request; SalesReturnReviewService.approve/reject; SalesReturnPhysicalService.receive/inspect/post; SalesReturnQuarantineResolutionService.resolve; ReturnInventoryPostingPort.restock/quarantine [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | concurrent | no returned qty greater than issued less already accepted | sum accepted returns <= issued qty; original order hash | RMA request history | 1 / NOT RUN | which RMA states count |
| CORE-RET-02 | RBAC,IDEM,IMM,XD | Seller approve/reject, scoped reception proof and retry; no early onHand | SalesReturnRequestService.request; SalesReturnReviewService.approve/reject; SalesReturnPhysicalService.receive/inspect/post; SalesReturnQuarantineResolutionService.resolve; ReturnInventoryPostingPort.restock/quarantine [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | happy | immutable receipt and correct tenant/warehouse; no early on_hand | RMA/reception receipt, stock unchanged | review/reception history | 1 / NOT RUN | proof requirements |
| CORE-RET-03 | INV,OWN,IMM,PROP | Inspect all dispositions then authorized post → sellable delta only RESTOCK | SalesReturnRequestService.request; SalesReturnReviewService.approve/reject; SalesReturnPhysicalService.receive/inspect/post; SalesReturnQuarantineResolutionService.resolve; ReturnInventoryPostingPort.restock/quarantine [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | happy | only RESTOCK moves sellable stock; evidence immutable | disposition, ledger, sellable/quarantine deltas, evidence | inspection/posting history | 1 / NOT RUN | WRITE_OFF accounting-free semantics |
| CORE-RET-04 | CONC,LOCK,RBAC,INV | Concurrent quarantine resolution in both orders → one resolution/conserved qty | SalesReturnRequestService.request; SalesReturnReviewService.approve/reject; SalesReturnPhysicalService.receive/inspect/post; SalesReturnQuarantineResolutionService.resolve; ReturnInventoryPostingPort.restock/quarantine [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | concurrent | one resolution, balances conserved, authorized posting only | quarantine balance, resolution receipt, ledger | resolution event | 1 / NOT RUN | current quarantine service |
| CORE-RET-05 | CRASH,IDEM,TIME,PART | Duplicate reception/posting and crash before/after commit → one posting, order remains COMPLETED | SalesReturnRequestService.request; SalesReturnReviewService.approve/reject; SalesReturnPhysicalService.receive/inspect/post; SalesReturnQuarantineResolutionService.resolve; ReturnInventoryPostingPort.restock/quarantine [historical anchor] | STOREFRONT (public QR/offer); SALES (quote, pickup, RMA); INVENTORY (posting) | recovery | no duplicate movement, original order stays COMPLETED | RMA receipt/posting counts + order state + ledger | one successful posting event | 1 / NOT RUN | failure seams |


### 6.9 CRM Buyer Link

**Границы:** CRM customer UUID != IAM buyer UUID; no inference by email/name; historical order stays intact.  
**Авторитетные данные:** CRM (verified link, dispute, revocation); SALES (order history).  
**Роли:** buyer, CRM reader, SALES reader, authorized reviewer.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Buyer consent/identity interaction in browser, legal deletion approval.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-CRM-05 | TEN,PII,OWN,XD | Verified buyer link propose/confirm→history; matching unverified email and missing one read grant deny | CrmBuyerLinkService.propose/confirm/orderHistory/revokeByBuyer/adjudicateDispute [historical anchor] | CRM (verified link, dispute, revocation); SALES (order history) | security | no inferred email match, proof and both CRM_READ/SALES_ORDER_READ | link proof/status + order tenant/buyer and scope | link history | 1 / NOT RUN | proof protocol and dual permission |
| CORE-CRM-06 | CONC,RBAC,PII,IMM | Dispute/revoke/delete × buyer history in both orders; no new unauthorized reads | CrmBuyerLinkService.propose/confirm/orderHistory/revokeByBuyer/adjudicateDispute [historical anchor] | CRM (verified link, dispute, revocation); SALES (order history) | concurrent | fail-closed, immutable link and order evidence, no foreign PII | link/account state, order hashes unchanged | dispute/revocation audit | 1 / NOT RUN | read linearization/retention |


### 6.10 FILES / Media

**Границы:** No public private bytes; clean only; existing real infra jobs mandatory if applicable.  
**Авторитетные данные:** FILES (object/scan/attachment); typed owner (visibility/ACL).  
**Роли:** uploader, typed owner reader, public reader, trusted scanner.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** Browser upload/render, live provider operational acceptance.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BSR-FILES-01 (REQUIRED_IF_EXPOSED) | PII,TEN,DENY,REAL | Upload staged object→scan→attach→read; foreign owner/private/quarantine denied before storage IO | FILES typed owner service (TBD); CatalogPublicProductMedia.listPublished | FILES + typed owner | security | clean-only, tenant and object scope; never publish private bytes | object/scan/attachment rows, storage call evidence | scan/attachment history if contracted | 1 / NOT RUN | reuse CORE-QR-02; split only if different behavior |
| BSR-FILES-02 (REGRESSION_IF_PRESENT) | CRASH,TIME,IDEM,PART | Storage/scan fails or response lost; restart cleanup/retry→no phantom clean attachment | FILES upload/recovery service (TBD) | FILES | recovery | fail closed, orphan cleanup only per policy | staging/scan/attachment states and storage object inventory | recovery evidence | 1 / NOT RUN | real RustFS/ClamAV and existing jobs |


### 6.11 FX / Smart FX Shield

**Границы:** MVP KZT-only admission; AUTO off/parked unless explicit scope change; no retroactive accepted price.  
**Авторитетные данные:** CURRENCY (rates/admission/policy/budget per current spec); STOREFRONT (price revision).  
**Роли:** seller, owner approver, SYSTEM_AUTO if separately approved.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** FX AUTO production pilot, schedule activation, external rate provider operational approval.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BSR-FX-01 (REQUIRED_FOR_MVP) | BOUND,PROP,DENY,XD | Public KZT offer/checkout allowed; unsupported currency/disabled policy or historical invalid offer cannot bypass KZT gate | currency admission port TBD; PublishedQrCheckoutService.checkout | CURRENCY + STOREFRONT + SALES | security | KZT-only server side; no unauthorized order; immutable old snapshot | offer/order currency/version and receipt delta | checkout only if allowed | 1 / NOT RUN | verify flag and historical admission decision |
| BSR-FX-02 (REGRESSION_IF_PRESENT) | CONC,CRASH,IDEM,OWN,PROP | AUTO budget race, consent revoke/epoch change × price revision and crash/retry; both orders, no user impersonation | trusted STOREFRONT AUTO port TBD; R2C.2B spec | CURRENCY + STOREFRONT | concurrent | SYSTEM_AUTO; canonical price delta budget; receipt+revision+consumption atomic; accepted order unchanged | policy/epoch/budget/revision/receipt rows; exact signed delta | SYSTEM_AUTO receipt/audit | 1 / NOT RUN | post-MVP; scope authorization conflict; do not activate |


### 6.12 FINANCE / Payment

**Границы:** No PAID/REFUNDED inference; no finance implementation authorization; MVP payment external.  
**Авторитетные данные:** Future FINANCE (invoice/payment/allocation); SALES owns fulfillment.  
**Роли:** future finance staff, seller.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** All payment/refund/accounting functionality, provider and financial sign-off.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BSR-FIN-01 (REQUIRED_FOR_MVP) | OWN,INV,DENY | Fulfill and return order → no synthetic PAID/REFUNDED or money posting | AuthenticatedSalesCommands / SalesReturnPhysicalService | SALES; future FINANCE not invoked | negative | fulfillment/return != money settlement | order/return states; finance rows unchanged if tables exist | no payment/refund event | 1 / NOT RUN | payment out of scope |
| BSR-FIN-02 (POST_MVP) | IDEM,PROP,CONC,CRASH | Future invoice→payment→allocation; duplicate and partial allocation failure | TBD (not asserted implemented) | future FINANCE | recovery | currency and allocation conservation; one provider reference; no double settlement | future schema/oracle TBD | future payment events TBD | 1 / NOT RUN | separate approved finance spec |


### 6.13 INSIGHT / Analytics

**Границы:** Post-MVP; booked amount != revenue; verified link required for attribution; no source writes.  
**Авторитетные данные:** Future INSIGHT (derived projection); domain owners own facts.  
**Роли:** future manager, analyst, tenant reader.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** All analytics dashboard/ML, performance and business validation.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BSR-INS-01 (POST_MVP) | OWN,TEN,PII,INV | Future Customer 360 from confirmed links and source facts; revoke/dispute reattributes | TBD (not asserted implemented) | future INSIGHT projection; CRM/SALES facts | security | no inferred buyer link; no foreign or PII leakage; no source writes | future projection vs independent scoped SQL | future projection history TBD | 1 / NOT RUN | approved metric definition and data contract |
| BSR-INS-02 (POST_MVP) | PROP,IDEM,EVT,CRASH | Future daily→period aggregate replay, late fact and restart→same results | TBD (not asserted implemented) | future INSIGHT | recovery | booked != paid; ratios from numerator/denominator; dedup, lineage | future projection vs recompute SQL | future projection/checkpoint events | 1 / NOT RUN | no MVP analytics implementation |


### 6.14 Общие cross-domain gates (не отдельный модуль)

**Границы:** Оба J1, G1 exact SHA and platform regression; no new orchestrator required.  
**Авторитетные данные:** Каждый доменный owner сохраняет свои данные.  
**Роли:** all scoped roles and trusted workers.  
**Минимум:** применить общий variant protocol §4 и §6 к каждой строке; все cross-domain dependencies проверяются на реальных owner ports.  
**Достаточность:** общий module gate §6; для POST_MVP — N/A release scope с подтверждением, не функциональный PASS.  
**Отложено до G2/G3 либо Post-MVP:** G2 UI/OIDC and G3 real UAT.

| ID | Техника(и) | Given / When / Then | Entrypoint сервиса | Владелец данных | Тип | Инварианты | SQL-проверка | Ожидаемые события | Уровень зрелости (1–6) | Открытый вопрос |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CORE-XD-01 | REAL,XD,INV,OWN | First tenant→staff→CRM/customer/task→SKU/offer→order→reserve→handover→history via service; include approved identity link | AutoOrderService.checkout; SalesPickupHandoverService.complete; CrmBuyerLinkService.confirm; OrderInventoryPort.reserve/fulfill [historical anchor] | Каждый доменный owner сохраняет свои данные | happy | physical/identity truth from actual service calls, not fabricated full journey | all owner rows/refs and immutable price, correct stock, task history | correlated owner events | 1 / NOT RUN | task port and service-only identity proof |
| CORE-XD-02 | REAL,XD,IDEM,PART | QR list and negotiated paths separately→pickup→RMA, replay/crash variants | AutoOrderService.checkout; SalesPickupHandoverService.complete; CrmBuyerLinkService.confirm; OrderInventoryPort.reserve/fulfill [historical anchor] | Каждый доменный owner сохраняет свои данные | happy | same owner aggregate and inventory ledger, no double transaction | same shared SALES/INV owners, one debit/return per receipt | correlated owner histories | 1 / NOT RUN | both checkout variants |
| CORE-XD-03 | TEN,ESC,PII,RBAC | A/B/AB actor tries foreign customer/offer/order/stock/QR/media using UUIDs and list filters | AutoOrderService.checkout; SalesPickupHandoverService.complete; CrmBuyerLinkService.confirm; OrderInventoryPort.reserve/fulfill [historical anchor] | Каждый доменный owner сохраняет свои данные | security | no foreign UUID enumeration or cross-domain leakage | all owner scoped reads and zero unauthorized deltas | no success event | 1 / NOT RUN | permission matrix |
| CORE-XD-04 | MIG,CRASH,IMM,INV | Populated old schema→Liquibase→restart→read and replay historical orders/link/receipt/stock | AutoOrderService.checkout; SalesPickupHandoverService.complete; CrmBuyerLinkService.confirm; OrderInventoryPort.reserve/fulfill [historical anchor] | Каждый доменный owner сохраняет свои данные | recovery | historical price/link/receipt/stock and owner scope stable | before/after canonical hashes, FK, ledger totals | no fabricated business event | 1 / NOT RUN | supported upgrade baseline |
| CORE-XD-05 | OBS,REAL,INV | Frozen candidate SHA → mandatory H2/PG/security/infra jobs and per-ID evidence → no unexpected skip | AutoOrderService.checkout; SalesPickupHandoverService.complete; CrmBuyerLinkService.confirm; OrderInventoryPort.reserve/fulfill [historical anchor] | Каждый доменный owner сохраняет свои данные | happy | mandatory tests and non-skips manifest PASS | manifest validates SHA, XML, SQL oracle references | none; qualification artifact | 1 / NOT RUN | exact current mandatory job list |


## 7. Cross-domain execution plans

| Conflict / journey | Existing IDs / overlays | Independent acceptance |
|---|---|---|
| IAM ↔ SALES/INVENTORY | CORE-IAM-04/06, CORE-SAL-10, CORE-PICK-04 | Both commit orders, fresh grant, no forbidden stock mutation |
| CRM ↔ TASKS | CORE-CRM-04, BSR-TASK-01/02, CORE-XD-01 | Real owner task and interaction history, T00 snapshot, no B1 expansion |
| CRM ↔ IAM ↔ SALES | CORE-IAM-05, CORE-CRM-05/06 | Explicit proof, revoke/dispute, no inferred email attribution |
| CATALOG ↔ STOREFRONT ↔ SALES | CORE-CAT-02/03, CORE-STO-03, CORE-SAL-02, CORE-QR-04 | One price authority, old order immutable, stale/pause race |
| SALES ↔ INVENTORY | CORE-SAL-03..10, CORE-INV-01 | Atomic reserve/release/debit, no oversell, physical pick retained |
| STOREFRONT ↔ FILES ↔ QR | CORE-QR-01/02, BSR-FILES-01/02 | Public projection, deny private bytes, safe retry |
| QR ↔ SALES ↔ INVENTORY | CORE-QR-04..07, CORE-PICK-01..05, CORE-RET-01..05, CORE-XD-02 | Shared order, proof exactly once, bounded return, posting crash/retry |
| FX ↔ STOREFRONT ↔ SALES | BSR-FX-01/02, CORE-CAT-02, CORE-SAL-02 | KZT admission required; AUTO qualification only for already authorized scope, no activation |
| SALES ↔ FINANCE / INSIGHT | BSR-FIN-01/02, BSR-INS-01/02 | No fictitious payment; derived facts only in future approved scope |

**BUS-MVP-J1 service sequence:** use CORE-IAM-01/03, CORE-ORG-01, CORE-CRM-01/02/04 + BSR-TASK-01, CORE-CAT-01, CORE-STO-01/02, BSR-FX-01, CORE-SAL-01/03/06/07/08/10, CORE-PICK-01..05 when proof required, CORE-CRM-05 and CORE-XD-01. Execute baseline happy and negative branches separately; do not use generic fulfill for proof-required order. Record role, service call, receipt, independent SQL per step. Verify seller views via service queries; UI not claimed.

**QR-MVP-J1 service sequence:** CORE-QR-01/02/04..07, CORE-SAL-01..05, CORE-PICK-01..05, CORE-RET-01..05, CORE-XD-02. Run list and quote variants independently; proof and return outcomes RESTOCK/QUARANTINE/WRITE_OFF in separate fixtures. Public preview is advisory only. Negative cases include revoked QR, foreign buyer, price drift, stale credential, double scan, return qty overflow. Real physical/phone proof remains G2/G3.

## 8. Evidence gate and work order

BSR-00 inventory of existing tests and exact candidate; BSR-10 owner/source/SQL mapping; BSR-20 reuse existing suites; BSR-30 fill genuinely missing variants with minimal corrections only; BSR-40 exact-SHA manifest + independent reviewer; BSR-50 hand off G2 blockers. Stop if source/contract unclear, report OPEN; no broad feature additions. P0 failure report: scenario/variant, SHA, reproducible fixture, expected/actual, SQL and schedule, impacted invariant, minimal owner fix, regression IDs. Retest both orders and full mandatory suites. Existing source-included jobs remain mandatory even for deferred feature if CI contract says so.

## 9. Сводная таблица итерации 1

| Модуль | Existing CORE | Candidate overlays | L1 | L2 | L3 | L4 | L5/L6 | Блокер перехода |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| IAM / Tenant / RBAC / ORGANIZATION | 8 | 0 | 8 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| CRM Customers / Contacts | 3 | 0 | 3 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| CRM Interactions / TASKS | 1 | 2 | 3 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| CATALOG / SKU / Prices | 3 | 0 | 3 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| STOREFRONT | 3 | 0 | 3 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| SALES | 10 | 0 | 10 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| INVENTORY | 2 | 0 | 2 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| QR Commerce | 17 | 0 | 17 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| CRM Buyer Link | 2 | 0 | 2 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| FILES / Media | 0 | 2 | 2 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| FX / Smart FX Shield | 0 | 2 | 2 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| FINANCE / Payment | 0 | 2 | 2 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| INSIGHT / Analytics | 0 | 2 | 2 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| Общие cross-domain gates (не отдельный модуль) | 5 | 0 | 5 | 0 | 0 | 0 | BLOCKED | current source/test mapping + exact-SHA execution; post-MVP scope decision where applicable |
| Итого | 54 | 10 | 64 | 0 | 0 | 0 | BLOCKED | no executed qualification in this iteration |
 
54 canonical IDs and 10 candidate overlay rows are distinct document records, **not** 64 new tests or change to 120-case target. No formal G1 verdict can be upgraded from this document.

## 10. Что добавлено / изменено / осталось открытым

**Добавлено:** R1.0, glossary, maturity and evidence protocol, 27 technique definitions, matrix template, all 13 modules + shared gate, 54 preserved CORE rows, 10 overlay candidate rows, dual J1 execution plans and summary.

**Изменено:** ничего в существующих нормативных файлах или коде; historic registry interpreted using current Freeze (basic TASKS IN; advanced SLA/FX AUTO parked). No previous scenario ID changed.

**Осталось открытым:** current exact HEAD and executed evidence, complete semantic mapping against existing tests/120 target, actual SQL schema/entrypoints/events especially TASKS/FILES/FX, current DEC-FUL/LOC policy and scope authorization for R2C, optional scenario disposition, real infrastructure and G2/G3/G4. All rows SPECIFIED/NOT RUN; no code or CI execution. Future iteration updates this same file with preserved IDs and revision-specific changelog; do not replace prior evidence. Main and production unchanged.
