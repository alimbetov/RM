# RM-BSR-R1 — Business Scenario Reliability: сценарная квалификация сервисного ядра RM

**Редакция:** R1.1 / Итерация 2 · 2026-11-24 · **ветка:** base_dev.

**Статус:** STATIC INVENTORY + INITIAL TRACEABILITY / SERVICE-ONLY / QUALIFICATION NOT RUN / G1 HOLD / PRODUCTION HOLD.

**Цель:** доказать завершённость BUS-MVP-J1 и QR-MVP-J1 на реальных сервисных entrypoints и PostgreSQL, без UI/HTTP, ручного SQL для выполнения бизнес-операций и подмены доменных владельцев. Документ не разрешает новую функциональность, не изменяет main, не отменяет Feature Freeze.

**Актуальная сверка проекта:** sections 11–17; [карта source/schema/test R1.1](RM-BSR-R1-TRACEABILITY-R1.1.json). Sections 9–10 сохраняют исторический результат итерации 1.

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

Матрицы R1.0 ниже сохраняют исходные требования и исторические anchors. Подтверждённые текущие entrypoints и обнаруженные расхождения имеют отдельную привязку в §12–13; `TBD` в исходной колонке не означает отсутствие найденного source. Не исполнять исторические API names как готовый call plan.

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

## 10. Changelog R1.0 — что добавлено / изменено / осталось открытым

**Добавлено:** R1.0, glossary, maturity and evidence protocol, 27 technique definitions, matrix template, all 13 modules + shared gate, 54 preserved CORE rows, 10 overlay candidate rows, dual J1 execution plans and summary.

**Изменено:** ничего в существующих нормативных файлах или коде; historic registry interpreted using current Freeze (basic TASKS IN; advanced SLA/FX AUTO parked). No previous scenario ID changed.

**Осталось открытым:** current exact HEAD and executed evidence, complete semantic mapping against existing tests/120 target, actual SQL schema/entrypoints/events especially TASKS/FILES/FX, current DEC-FUL/LOC policy and scope authorization for R2C, optional scenario disposition, real infrastructure and G2/G3/G4. All rows SPECIFIED/NOT RUN; no code or CI execution. Future iteration updates this same file with preserved IDs and revision-specific changelog; do not replace prior evidence. Main and production unchanged.

## 11. Итерация 2 / R1.1 — сверка с текущим проектом

**Baseline source SHA:** `a09c53fd301838e04e2ffe5e518346764a2c51ef`, `base_dev`, 2026-11-24. Это SHA изученного source, а не SHA будущего квалифицированного релиза. Коммит настоящей редакции будет другим; доказательства нельзя автоматически переносить на него даже при docs-only изменении.

**Результат:** BSR-00 — статическая инвентаризация выполнена; BSR-10 — составлена карта всех 64 записей с текущими owner/source/schema и кандидатами тестов. Полная проверка каждой техники/варианта и исполнение остаются открытыми. BSR-20/30/40 не объявляются выполненными. Изменены документация и приложение к ней; Java, Liquibase, CI, прежняя executable evidence map и нормативные CORE-ID не изменены.

[Машиночитаемое приложение R1.1](RM-BSR-R1-TRACEABILITY-R1.1.json) содержит baseline SHA, точные пути, строки и сигнатуры, owner tables, все 64 сценария, кандидаты методов, прежние привязки, пробелы, перечень test source файлов с SHA-256 и список workflow. Это **статическая карта**, не execution manifest и не подключённый к CI новый gate. На baseline проверены существование ссылок и полный состав ID; `EXACT` и `PG QUALIFIED` не присвоены ни одной полной BSR-записи.

### 11.1 Фактический объём инвентаризации

| Объект на baseline | Количество | Что означает |
|---|---:|---|
| Production Java files | 238 | Файлы исходников, не число бизнес-сервисов |
| Java test source files | 95 | Включая 2 process worker и decoder support |
| Классы с `@Test` | 92 | Статически найденные объявления |
| Методы `@Test` | 866 | Не executions, не гарантии, не 866 бизнес-сценариев |
| Workflow | 29 | Все `.github/workflows/*.yml` |
| Workflow в действующем Exact-SHA admission | 9 | Именной список из workflow и `verify_rm_qa_01b.py` |
| Канонические CORE / candidate overlays | 54 / 10 | Состав R1.0 сохранён |
| Полные BSR-сценарии с новым PG evidence | 0 | Квалификация в этой итерации не запускалась |

Целевые 120 случаев остаются плановым объёмом §FC-60, а не исполняемым перечнем. Ни 866 методов, ни 54 CORE, ни 10 overlays нельзя складывать с 120 или выдавать за процент функциональной надёжности. Реестры CRM-99, user-role и dual-product — дополнительные индексы; одинаковые числа и пересечение методов не означают семантическую эквивалентность.

### 11.2 Критический дефект прежней evidence map

В `RM-MVP-CORE-P0-SERVICE-EVIDENCE-v1.json` сохранены строки всех 54 ID, но **как минимум 18 привязок проверяют другое центральное поведение** относительно канонического §11.6 Core Closure. Тела этих 18 привязок просмотрены; это подтверждённый дефект traceability. Остальные 36 рассматриваются только как supporting: прежняя карта не доказывает весь расширенный контракт BSR.

| Канонический ID | Что требуется | Что проверяет прежняя привязка |
|---|---|---|
| CORE-IAM-04 | revoke/suspend × order confirm | STOREFRONT после заранее записанного SQL suspension |
| CORE-IAM-05 | suspend/delete покупателя × link/checkout | warehouse assignment для confirm/fulfill |
| CORE-IAM-06 | warehouse revoke × pick/handover | tenant A/B grants при создании storefront |
| CORE-CRM-02 | update CAS и customer/contact search | readonly interaction и отзыв CRM_READ |
| CORE-CRM-03 | archive с сохранением заказа | email consent grant/revoke |
| CORE-CRM-04 | interaction record/assign/nextAction/complete | customer archive/restore и active contract guard |
| CORE-SAL-02 | price/pause × checkout | два checkout с одним ключом |
| CORE-SAL-05 | competing orders / hot SKU | последовательное fulfillment и его replay |
| CORE-SAL-06 | cancel × confirm/fulfill | denied confirm без прав |
| CORE-SAL-09 | generic fulfill × secure pickup | BUS happy path STANDARD fulfillment |
| CORE-SAL-10 | warehouse grants × lifecycle | запрет cancel после SQL-created pick |
| CORE-QR-03 | regional availability preview | private order locator access |
| CORE-QR-07 | expiry/withdraw/revoke/suspend × checkout | конкурентное потребление quote разными ключами |
| CORE-PICK-02 | credential issue и preview | deny подготовки при неверном warehouse |
| CORE-RET-04 | quarantine resolution race | первоначальные WRITE_OFF/QUARANTINE dispositions |
| CORE-XD-03 | cross-domain A/B/AB isolation | negotiated happy path до карантина |
| CORE-XD-04 | populated migration + restart | competing confirmations / oversell |
| CORE-XD-05 | общий Exact-SHA evidence gate | seller suspension × checkout |

**Правило BSR:** совпадение ID и успешный JUnit из прежней карты не повышают maturity BSR. `CORE_SERVICE_EVIDENCE_QUALIFIED` из существующего отчёта трактуется только в пределах его фактически исполнявшихся методов, не как «54 канонических CORE квалифицированы». Исправление executable map и её проверки — отдельный минимальный шаг BSR-20, с сохранением старых артефактов и явным mapping version. В этой редакции CI не ослаблен и исторические результаты не переписаны.

Нельзя исправлять расхождение переименованием поведения у стабильного CORE-ID. Нужно перепривязать доказательства к каноническому поведению; сохранить полезный прежний тест как supporting другого подходящего ID. Недостающий вариант остаётся `GAP/NOT RUN`, а не получает формальную зелёную привязку.

## 12. Текущие контракты, которые уточняют R1.0

### 12.1 Доверенный entrypoint и фактический уровень J1

- Основной list-price QR entrypoint — `PublishedQrCheckoutService.checkoutVerified(VerifiedActor, String, int, long, BigDecimal, String, String)` → `AutoOrderService.checkoutVerifiedPublishedQr`. Email-first QR API сохранён для legacy compatibility, по умолчанию закрыт; его happy test не доказывает текущий verified QR route.
- `AutoOrderService.checkout(slug,email,key,lines)` остаётся существующим Business route. Его нельзя называть signed buyer checkout; identity и разрешение этого route описываются отдельно.
- `BusMvpServiceJourneyQualificationTest` создаёт `VerifiedActor` напрямую в IAM test package. Он выполняет реальные onboarding/enrollment/grants/CRM/SKU/stock/order, но создаёт **обычную** `TaskService.create`, без `crm.task_crm_followup`; не создаёт buyer link к CRM-клиенту и не проверяет cancellation branch в том же journey.
- `QrMvpServiceJourneyQualificationTest` имеет list→RESTOCK и quote→QUARANTINE, реальные commercial transitions и SQL counts. Но `JwtDecoder` — lambda stub; tenant/membership/email identity/warehouse создаются через repositories/SQL. Это допустимые явно отмеченные prerequisites для commerce-среза, а не доказательство onboarding/email verification. Полный WRITE_OFF journey и signed identity chain отсутствуют в этом классе.
- Signed access-token admission уже имеет кандидатов `IamSellerAuthenticatedTenantAdmissionQualificationTest` и `R4LocalJwksIntegrationQualificationTest`. Их нельзя арифметически сложить с J1 и объявить одним сквозным доказательством: нужен вариант, где именно полученный resolver actor проходит бизнес-команду. Синтетический локальный issuer/JWKS допустим на G1; реальная IdP-сессия остаётся G2/G3.
- В части legacy tests вызываются package-private `SalesInventoryCommandService`/owner methods и SQL создаёт `PREPARING`/pick. Они полезны для локального инварианта, но не подтверждают REAL-вариант пользовательского пути. Новые public ports ради теста запрещены.

### 12.2 SALES, warehouse scope и физические доказательства

`crm.sales_order.fulfillment_mode` **уже существует**: `STANDARD` либо `PICKUP_PROOF_REQUIRED`; PostgreSQL запрещает изменение mode. Старое утверждение Core Closure §11.3 об отсутствии discriminator устарело относительно baseline. Для BSR использовать физическую схему и текущий source; это не разрешение менять mode/миграцию.

`AuthenticatedSalesCommands` — публичный verified facade; `SalesInventoryCommandService.confirm/cancel/fulfill` — внутренние команды. Generic fulfill отказывает proof-required заказу, включая replay уже завершённой выдачи. Для STANDARD сохраняется разрешённый путь без physical pick. После любой pick confirmation отмена отказывает `ORDER_CANCEL_REQUIRES_REVERSE_PICK`; готового unpick/reversal workflow из этого требования не следует.

Для confirm/fulfill и освобождения активного резерва при cancel warehouse assignment усиливается при `rm.mvp.service-mode.enabled=true`; QR preparation/handover имеют собственный scope admission. BSR должен фиксировать effective properties и отдельно проверять strict mode. PASS legacy-default контекста не доказывает MVP warehouse policy. Для каждой операции указывать не только роль, но и user status, tenant status, membership, domain permissions, выбранный warehouse и его assignment.

### 12.3 ORGANIZATION: не перепутать pause и close

Текущий `OrganizationLocationService.transition`:

| Переход | Фактический guard |
|---|---|
| ACTIVE → INACTIVE | Нет reserved balance, ACTIVE reservations и OPEN stocktake; `on_hand > 0` сам по себе допустим |
| INACTIVE → ACTIVE | Восстановление доступности; история и существующий KATO сохраняются |
| INACTIVE → CLOSED | Дополнительно запрещён ненулевой `on_hand`; CLOSED terminal |

Поэтому «zero stock on inactive warehouse» — некорректный общий oracle для CORE-ORG-02. Правильная проверка существующего контракта: сохранённый ledger/остаток, запрещённые новые stock operations после деактивации, блокировка деактивации активными обязательствами, невозможность закрыть склад с остатком. `stockBearingWarehouseCanPauseButCannotCloseUntilLawfulWriteOff` явно закрепляет различие.

**BSR-DEC-01 / нормативная сверка:** формулировка «zero stock stranded on inactive warehouse» в Core Closure требует уточнения владельцем норматива. Не менять production behavior и не списывать остатки ради соответствия буквальному, более широкому oracle. До разрешения расхождения соответствующий вариант — `CONTRACT_CONFLICT/HOLD`; snapshot исходного текста сохранён в R1.0/Git history.

### 12.4 CRM и TASKS

CRM customer и IAM buyer — разные identity. В заказе используется `buyer_user_id`; CRM-доступ к заказам проходит `CrmBuyerLinkService`. Нельзя изобрести `sales_order.customer_id`/`contract_id` либо автоматически связать одинаковые email. `archiveCustomer` блокируется ACTIVE contract; прямой guard «есть любой открытый SALES order» в просмотренном методе отсутствует. CORE-CRM-03 должен определить поведение через существующий verified link, сохранить immutable order snapshot и отдельно зафиксировать решение об архивировании; отсутствие прямого FK не является дефектом.

Действующие TASKS boundaries: `TaskCrmFollowupService.create/getMine`, `TaskService` lifecycle, `TaskCrmReconciliationService.reconcileCancelled`, `TaskReconciliationSnapshotService.capture/page/verifyIntegrity`. T00 cancellation event принадлежит CRM; TASKS snapshot и явная reconciliation не означают автоматической отмены всех задач. Не требовать notification outbox/B1 для базового follow-up.

`BUS-TASK-01/02` из старой executable map сохраняются. `BUS-TASK-01` поддерживает BSR-TASK-01; `BUS-TASK-02` проверяет отзыв прав выполнения и **не эквивалентен** BSR-TASK-02 cancellation/snapshot. Никаких новых канонических CORE-ID или увеличения 120 из-за этих aliases.

### 12.5 FILES, валюты, AUTO, FINANCE и INSIGHT

- FILES public photos входят в QR-J1, поэтому BSR-FILES-01 для этого surface — REQUIRED_FOR_MVP; остальные уже существующие attachment маршруты сохраняют обязательную регрессию. Exact source и schema доступны в приложении. `FileRecoveryService` — package-private trusted worker; не открывать его как пользовательский entrypoint. Real RustFS/ClamAV workflow уже обязателен.
- Для G1 фиксировать `rm.mvp.service-mode.enabled=true` и `rm.currency.commerce-admission.enabled=true`. Первый флаг запрещает старт при выключенном втором; без strict mode прежний default admission=false не гарантирует KZT-only. Выполнить существующий `docs/operations/RM-MVP-SERVICE-CURRENCY-PREFLIGHT.sql` на populated fixtures и его negative case. Отсутствие path-filtered отдельного W2-W5 workflow не равно отсутствию J1: методы также обязательны в `kms-postgres-surefire`.
- R2C содержит consent/enrollment/approval/revocation, simulation, budget и `FxAutoExecutionPreparationService.assess`. Последний требует PostgreSQL READ COMMITTED и существующей транзакции, возвращает диагностический `Assessment`, **не публикует цену**. Прямых integration-test references к этому классу в `src/test` на baseline не найдено. `0171-storefront-system-auto-closed` отвергает SYSTEM_AUTO insert с `FX_AUTO_EXECUTION_DISABLED`. Atomic trusted publish не считать реализованным на основании таблиц receipt/budget.
- Для BSR-FX-02 разделить варианты: существующие regressions и fail-closed disabled-execution обязательны по текущему CI; будущие B.5/B.6 publication/revision/consumption/receipt — `NOT IMPLEMENTED / scope decision pending`, не повод включать AUTO. Из source видно `actualDeltaKzt = abs(candidate-old)` для budget consumption; направленное изменение цены и абсолютный расход бюджета — разные поля/oracles.
- **BSR-DEC-02 / R2C scope:** наличие активных R2C работ и обязательного R2C workflow не отменяет Freeze и не включает feature в J1. Текущее ТЗ разрешает инвентаризацию и регрессию существующего кода; продолжение executor work должно иметь отдельную явную scope-запись. Не блокировать этой записью BSR-00/10 и не заявлять, что она уже утверждена.
- `crm.fin_invoice` уже существует как foundation (`DRAFT/ISSUED/VOID`), в Java FINANCE найден `Money`. Нет заявленного payment/allocation service path. BSR-FIN-01 должен проверять нулевую дельту существующей invoice table при handover/return; проверка packaging-only для этого недостаточна. `COMPLETED` не означает PAID, возврат не означает REFUNDED.
- Production INSIGHT projection service в просмотренном tree не найден. BSR-INS-01/02 и BSR-FIN-02 остаются POST_MVP/SPECIFIED; отсутствие сервиса не является PASS.

### 12.6 Реальные source/schema anchors

Таблицы ниже подтверждены Liquibase/source; учитывать **всю** цепочку ALTER, не только первоначальный CREATE. Для IAM/ORG старые unqualified имена разрешаются в `public`. Группы — технические сокращения приложения, не новые модули или scenario ID. Точные сигнатуры и пути каждого метода приведены в JSON; source hints не заменяют review транзакционных границ.

| Группа | Текущие сервисы (точные методы — JSON) | Основные физические таблицы |
|---|---|---|
| IAM | [SelfServiceTenantOnboardingService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/iam/SelfServiceTenantOnboardingService.java#L32); [TenantMemberEnrollmentService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/iam/TenantMemberEnrollmentService.java#L30); [VerifiedTenantRoleCommands](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/iam/VerifiedTenantRoleCommands.java#L23); [OidcActorResolver](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/iam/OidcActorResolver.java#L21) | `public.iam_user_account`, `public.iam_workspace`, `public.org_organization`, `public.iam_org_membership`, `public.iam_tenant_rbac_event`, `public.iam_tenant_role_permission`, `public.iam_tenant_user_role` |
| IDENTITY | [GlobalAccountStatusService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/iam/GlobalAccountStatusService.java#L26); [GlobalAccountDeletionService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/iam/GlobalAccountDeletionService.java#L32) | `public.iam_user_account`, `public.iam_account_deletion_event`, `public.iam_deletion_oidc_revocation_outbox`, `public.iam_email_identity` |
| SCOPE | [WarehouseStaffScopeService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/iam/WarehouseStaffScopeService.java#L70) | `public.iam_warehouse_staff_scope`, `public.org_location` |
| ORG | [AuthenticatedOrganizationLocations](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/organization/AuthenticatedOrganizationLocations.java#L25) | `public.org_location`, `crm.audit_event` |
| CRM | [AuthenticatedCrmCommands](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/crm/AuthenticatedCrmCommands.java#L15); [AuthenticatedCrmQueries](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/crm/AuthenticatedCrmQueries.java#L31); [CrmContractService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/crm/CrmContractService.java#L51) | `crm.customer`, `crm.contact`, `crm.customer_contract`, `crm.contract_revision`, `crm.customer_change_event`, `crm.contact_change_event`, `crm_pii.customer_secret`, `crm_pii.contact_secret` |
| INTERACTION | [CrmInteractionService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/crm/CrmInteractionService.java#L54) | `crm.customer_interaction`, `crm.customer_interaction_event` |
| LINK | [CrmBuyerLinkService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/crm/CrmBuyerLinkService.java#L54) | `crm.customer_buyer_link`, `crm.customer_buyer_link_event`, `crm.customer_buyer_dispute_decision` |
| TASK | [TaskCrmFollowupService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/tasks/TaskCrmFollowupService.java#L43); [TaskService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/tasks/TaskService.java#L65); [TaskCrmReconciliationService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/tasks/TaskCrmReconciliationService.java#L112); [TaskReconciliationSnapshotService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/tasks/TaskReconciliationSnapshotService.java#L53) | `crm.task_item`, `crm.task_event`, `crm.task_crm_followup`, `crm.task_reconciliation_snapshot`, `crm.task_reconciliation_snapshot_item`, `crm.task_reconciliation_snapshot_seal` |
| CAT | [CatalogItemService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/catalog/CatalogItemService.java#L47) | `crm.cat_item`, `crm.cat_item_i18n`, `crm.cat_price`, `crm.cat_item_event` |
| STO | [StorefrontManagementService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/storefront/StorefrontManagementService.java#L37); [StorefrontOfferService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/storefront/StorefrontOfferService.java#L58) | `crm.store_front`, `crm.store_offer`, `crm.store_offer_price_revision` |
| SAL | [AutoOrderService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/autoorder/AutoOrderService.java#L72); [PublishedQrCheckoutService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/autoorder/PublishedQrCheckoutService.java#L37); [AuthenticatedSalesCommands](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/AuthenticatedSalesCommands.java#L13) | `crm.sales_order`, `crm.sales_order_line`, `crm.sales_checkout_receipt`, `crm.sales_order_status_event` |
| INV | [AuthenticatedInventoryCommands](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/inventory/AuthenticatedInventoryCommands.java#L17) | `crm.inv_stock_balance`, `crm.inv_stock_movement`, `crm.inv_stock_reservation`, `crm.inv_reservation_event`, `crm.inv_stock_transfer`, `crm.inv_stocktake`, `crm.inv_stocktake_line` |
| RECON | [AuthenticatedInventoryReconciliation](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/inventory/AuthenticatedInventoryReconciliation.java#L58); [AuthenticatedInventoryReconciliationRuns](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/inventory/AuthenticatedInventoryReconciliationRuns.java#L58) | `crm.inv_reconciliation_run`, `crm.inv_reconciliation_finding` |
| QR | [PublishedOfferQrService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/storefront/PublishedOfferQrService.java#L61); [PublishedOfferCardQuery](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/storefront/PublishedOfferCardQuery.java#L52) | `crm.store_offer_qr_ref`, `crm.store_offer_qr_receipt`, `crm.store_offer`, `crm.sales_order_qr_reference` |
| NEGOTIATION | [SalesPriceProposalService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/SalesPriceProposalService.java#L75); [AutoOrderService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/autoorder/AutoOrderService.java#L328) | `crm.sales_price_proposal`, `crm.sales_price_proposal_event`, `crm.sales_accepted_quote`, `crm.sales_proposal_command_receipt` |
| PICK | [SalesOrderPreparationService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/SalesOrderPreparationService.java#L52); [SalesPickupHandoverService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/SalesPickupHandoverService.java#L90) | `crm.sales_order_pick_confirmation`, `crm.sales_preparation_receipt`, `crm.sales_pickup_credential`, `crm.sales_pickup_handover`, `crm.sales_pickup_handover_line` |
| RETURN | [SalesReturnRequestService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/SalesReturnRequestService.java#L42); [SalesReturnReviewService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/SalesReturnReviewService.java#L48); [SalesReturnReceptionProofService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/SalesReturnReceptionProofService.java#L35); [SalesReturnPhysicalService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/SalesReturnPhysicalService.java#L44); [SalesReturnQuarantineResolutionService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/sales/SalesReturnQuarantineResolutionService.java#L49) | `crm.sales_return_request`, `crm.sales_return_line`, `crm.sales_return_event`, `crm.sales_return_reception_proof`, `crm.sales_return_reception`, `crm.sales_return_inspection`, `crm.sales_return_posting`, `crm.sales_return_quarantine_resolution`, `crm.inv_return_quarantine_balance`, `crm.inv_return_quarantine_movement` |
| FILES | [AuthenticatedFilesCommands](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/files/AuthenticatedFilesCommands.java#L11); [FileUploadCompletionService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/files/FileUploadCompletionService.java#L26); [AuthenticatedFilesAttachments](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/files/AuthenticatedFilesAttachments.java#L12); [AuthenticatedFilesBinaryReads](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/files/AuthenticatedFilesBinaryReads.java#L22); [CatalogPublicProductMedia](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/files/CatalogPublicProductMedia.java#L56); [FileRecoveryService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/files/FileRecoveryService.java#L81) | `crm.file_object`, `crm.file_storage`, `crm.file_attachment`, `crm.file_catalog_public_variant` |
| CURRENCY | [TenantCurrencyPolicyService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/organization/TenantCurrencyPolicyService.java#L37); [CommerceCurrencyAdmission](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/organization/CommerceCurrencyAdmission.java#L44) | `public.tenant_currency_policy`, `public.tenant_allowed_currency`, `public.tenant_currency_policy_event` |
| FXAUTO | [FxAutoPolicyEnrollmentService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/fx/auto/FxAutoPolicyEnrollmentService.java#L78); [FxAutoDraftApprovalService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/fx/auto/FxAutoDraftApprovalService.java#L48); [FxAutoConsentRevocationService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/fx/auto/FxAutoConsentRevocationService.java#L40); [FxAutoPersistentDryRunService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/fx/auto/FxAutoPersistentDryRunService.java#L54); [FxAutoExecutionPreparationService](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/main/java/kz/rm/fx/auto/FxAutoExecutionPreparationService.java#L57) | `crm.fx_auto_policy_revision`, `crm.fx_auto_policy_activation`, `crm.fx_auto_approval_evidence`, `crm.fx_auto_revocation_evidence`, `crm.fx_auto_budget_consumption`, `crm.fx_auto_execution_receipt`, `crm.fx_auto_tenant_budget_guard` |
| FIN | Нет production service route | `crm.fin_invoice` |
| INSIGHT | Нет production service route | Не обнаружены в выбранном scope |

## 13. Карта 54 CORE + 10 overlays на baseline

Все строки — SPECIFIED / NOT RUN. Кандидаты ниже найдены в source; **не заявляется**, что два перечисленных метода исчерпывают строку. Полный перечень existing methods доступен через инвентаризированные test classes. Сначала прочитать тела кандидатов и helpers, проверить каждый обязательный variant и только затем добавить недостающее.

Колонка «Прежняя карта» сравнивает прежнюю executable привязку с каноническим сценарием: GAP — другое центральное поведение; PARTIAL — supporting, не полная квалификация; NEW — overlay; значения не являются runtime PASS/FAIL. Для XD-04/05 использовать workflow evidence, а не выдуманный JUnit. Точные прежние requiredProofs сохранены в JSON.

| Scenario ID | Owner groups | Прежняя карта | Кандидаты source tests / evidence | Незакрытое требование |
|---|---|---|---|---|
| CORE-IAM-01 | IAM | PARTIAL | [R4FirstTenantOnboardingQualificationTest#verifiedEmailFirstTenantIsAtomicAndExactRetryIsIdempotent](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/R4FirstTenantOnboardingQualificationTest.java#L66); [R4FirstTenantOnboardingQualificationTest#concurrentFirstTenantRequestsProduceOneOwnerAndNoOrphanRows](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/R4FirstTenantOnboardingQualificationTest.java#L135) | Signed access token → resolver → onboarding; restart/replay и независимый подсчёт org/member/orphan. |
| CORE-IAM-02 | IAM | PARTIAL | [R4FirstTenantOnboardingQualificationTest#unverifiedEmailSuspensionAndForeignTenantRejectWithoutWrites](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/R4FirstTenantOnboardingQualificationTest.java#L92); [IamSellerAuthenticatedTenantAdmissionQualificationTest#iamSellerR0SignedIdRefreshWrongPurposeAndBadJwtNeverMintActor](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/IamSellerAuthenticatedTenantAdmissionQualificationTest.java#L222) | Отказы onboarding и JWT пока в разных тестах; проверить все owner-дельты. |
| CORE-IAM-03 | IAM | PARTIAL | [BusMvpServiceJourneyQualificationTest#busMvpJ1OrganizationEmployeeCustomerTaskSkuOrderStockHistoryPg](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/BusMvpServiceJourneyQualificationTest.java#L92); [UserRoleMatrixR31QualificationTest#urR31DualTenantMemberHasDifferentPermissionsAndRevocationIsLocal](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/UserRoleMatrixR31QualificationTest.java#L167) | Связать invite/accept/grants и бизнес-команду с signed identity и AB-grants в одном варианте. |
| CORE-IAM-04 | IAM, SAL | GAP | [SalesInventoryRbacQualificationTest#postgresSalesConfirmationFencesConcurrentPermissionRevocation](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L525); [TenantRbacRevocationQualificationTest#commandCannotProceedUntilEarlierRevocationCommits](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/TenantRbacRevocationQualificationTest.java#L115) | Текущая привязка проверяет STOREFRONT после SQL suspension, а не оба порядка revoke × confirm; дополнить verified SALES маршрут. |
| CORE-IAM-05 | IDENTITY, LINK, SAL | GAP | [PublishedQrCheckoutQualificationTest#qrMvpSuspendOrDeleteVersusCheckoutBothCommitOrders](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/autoorder/PublishedQrCheckoutQualificationTest.java#L331); [CrmBuyerLinkQualificationTest#postgresBothCommitOrdersForDeleteVersusConfirm](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmBuyerLinkQualificationTest.java#L687) | Старая привязка к складу не относится к блокировке покупателя; раздельно checkout/link/read и retention. |
| CORE-IAM-06 | SCOPE, PICK | GAP | [QrWarehouseHandoverQualificationTest#qr06bPgHandoverCommitPrecedesConcurrentScopeRevocation](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L562); [QrWarehouseHandoverQualificationTest#qr06bPgScopeRevocationCommitPrecedesHandoverAdmission](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L615) | Старая привязка к общим grants не доказывает warehouse scope; отдельно оба порядка pick × revoke. |
| CORE-ORG-01 | ORG | PARTIAL | [OrganizationLocationLifecycleQualificationTest#typedCreateUpdateNoOpStaleAndUnicodeAreAuditedAtomically](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/organization/OrganizationLocationLifecycleQualificationTest.java#L145); [OrganizationLocationLifecycleQualificationTest#stockBearingWarehouseCanPauseButCannotCloseUntilLawfulWriteOff](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/organization/OrganizationLocationLifecycleQualificationTest.java#L186) | Разделить INACTIVE с остатком и CLOSED без остатка; нормативное расхождение §12.3. |
| CORE-ORG-02 | ORG, INV, SAL | PARTIAL | [OrganizationLocationR11ConcurrencyQualificationTest#postgresReceiptWinsThenDeactivationWaitsAndPreservesPostedMovement](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/organization/OrganizationLocationR11ConcurrencyQualificationTest.java#L360); [OrganizationLocationR11ConcurrencyQualificationTest#postgresDeactivationWinsThenReceiptCannotInitializeOrPost](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/organization/OrganizationLocationR11ConcurrencyQualificationTest.java#L407) | Дополнить остальные пары transfer/confirm/close из того же класса; сохранить остаток при законной деактивации. |
| CORE-CRM-01 | CRM | PARTIAL | [CrmR1aQualificationTest#personAndContactSecretsStayEncryptedAndReloadAcrossInstance](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmR1aQualificationTest.java#L212); [CrmR1aQualificationTest#tenantIsoR1CustomersContactsJPAFiltersCountsAndAllPagesRemainScoped](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmR1aQualificationTest.java#L65) | Нужны PERSON и COMPANY, same-name без merge, независимые encrypted/foreign-row oracles. |
| CORE-CRM-02 | CRM | GAP | [CrmR1aQualificationTest#crmR14TwoWritersSameContactVersionExactlyOneCommits](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmR1aQualificationTest.java#L1147); [CrmR1aQualificationTest#customerAndContactSearchAreBoundedTenantScopedAndPrivacyMinimized](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmR1aQualificationTest.java#L482) | Старая привязка к readonly interaction не доказывает customer/contact CAS и поиск. |
| CORE-CRM-03 | CRM, LINK, SAL | GAP | [CrmInteractionQualificationTest#crmSvc05CustomerArchiveContractGuardAndRestorePreserveTimeline](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java#L321); [CrmBuyerLinkQualificationTest#archivedCustomerCannotBeLinkedOrExposeOrderHistory](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmBuyerLinkQualificationTest.java#L175) | Старая привязка к consent ошибочна; заказ связан с IAM buyer, не прямо с CRM customer/contract; нужен контракт архивации и hash заказа. |
| CORE-CRM-04 | INTERACTION | GAP | [CrmInteractionQualificationTest#interactionLifecycleIsIdempotentTenantScopedAndHistoryImmutable](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java#L64); [CrmInteractionQualificationTest#crmR21bPostgresBothTerminalCommitOrdersAreLinearizable](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java#L865) | Старая привязка к архиву не покрывает record/assign/nextAction/complete; проверить каждый шаг и history. |
| CORE-CAT-01 | CAT | PARTIAL | [CatalogQualificationTest#twoTenantCatalogSkuPricePublicationAndReadOnlyBoundary](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/catalog/CatalogQualificationTest.java#L63); [CatalogQualificationTest#draftTranslationsAndVersionCasCannotLeakOtherTenant](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/catalog/CatalogQualificationTest.java#L122) | Дополнить точные price/quantity bounds, invalid currency и negative zero-effect oracles. |
| CORE-CAT-02 | CAT, STO, SAL | PARTIAL | [StorefrontManagementQualificationTest#sellerEditR1CatalogPriceAndSellerOfferPriceHaveIndependentHistories](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/StorefrontManagementQualificationTest.java#L280); [SellerOfferQualificationTest#acceptedOrderPriceAndSkuSurviveSellerPriceRevision](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/SellerOfferQualificationTest.java#L575) | Catalog CAS сам по себе не доказывает коммерческую цену; проверить обе истории и old-order hash при drift. |
| CORE-CAT-03 | CAT, INV, STO | PARTIAL | [CommercialCoreE2EQualificationTest#postgresArchiveAndOfferPublicationAreSerializedInBothOrders](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/integration/CommercialCoreE2EQualificationTest.java#L626); [CommercialCoreE2EQualificationTest#postgresArchiveWinsThenConcurrentReceiptCannotStrandStock](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/integration/CommercialCoreE2EQualificationTest.java#L526) | В том же классе есть receipt-first; stocktake race и обе стороны всех конфликтов требуют отдельной привязки. |
| CORE-STO-01 | STO | PARTIAL | [StorefrontManagementQualificationTest#sellerCanCreatePublishPauseAndResumeWithAuditAndStaleCheck](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/StorefrontManagementQualificationTest.java#L79); [SellerOfferQualificationTest#w1InactiveSellerWorkspaceRejectsNewCheckoutButPreservesExactReplay](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/SellerOfferQualificationTest.java#L379) | Resume выполняется publish с актуальной версией; отдельно revoked role, inactive workspace и tenant. |
| CORE-STO-02 | STO, CAT | PARTIAL | [SellerOfferQualificationTest#r10PriceHistoryIsImmutableAndNoOpOrStaleCasCannotAppend](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/SellerOfferQualificationTest.java#L93); [SellerOfferQualificationTest#draftPublicationPauseResumeAndCas](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/SellerOfferQualificationTest.java#L536) | У price update CAS/no-op, а не универсальный request-key receipt; привязать hide и invalid product. |
| CORE-STO-03 | STO, SAL, IAM | PARTIAL | [SellerOfferQualificationTest#postgresRevokeVersusSellerCommandHasOneAuthorizationOrder](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/SellerOfferQualificationTest.java#L195); [SellerOfferQualificationTest#postgresCheckoutPauseSerializesWithoutPartialOrders](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/SellerOfferQualificationTest.java#L502) | Оба теста кандидаты; доказать именно publication и оба управляемых порядка pause × checkout. |
| CORE-SAL-01 | SAL | PARTIAL | [AutoOrderR1QualificationTest#retryIsIdempotentAndChangesAreRejected](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/autoorder/AutoOrderR1QualificationTest.java#L121); [AutoOrderR1QualificationTest#concurrentSameCheckoutKeyCreatesOneOrderAndOneIdentity](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/autoorder/AutoOrderR1QualificationTest.java#L183) | Сквозной happy path из старой карты не доказывает конфликт ключа; отдельно lost response и process restart. |
| CORE-SAL-02 | SAL, STO | GAP | [PublishedQrCheckoutQualificationTest#qr03SellerPriceChangeRejectsStaleAcceptanceBeforeBuyerProvisioning](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/autoorder/PublishedQrCheckoutQualificationTest.java#L468); [SellerOfferQualificationTest#postgresCheckoutPauseSerializesWithoutPartialOrders](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/SellerOfferQualificationTest.java#L502) | Старая привязка к duplicate checkout не покрывает stale price/pause; отдельно verified QR и обычный route. |
| CORE-SAL-03 | SAL, INV | PARTIAL | [SalesInventoryRbacQualificationTest#confirmAndCancelAreAtomicAndRetriesAreIdempotent](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L150); [SalesInventoryRbacQualificationTest#sale08RemappedOfferCannotSubstituteSkuDuringOrderConfirmation](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L942) | Кандидаты вызывают внутренние методы; нужен verified facade, multi-line snapshot и один склад в strict mode. |
| CORE-SAL-04 | SAL, INV | PARTIAL | [SalesInventoryRbacQualificationTest#insufficientStockRollsBackStatusAndEveryReservation](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L292); [SalesInventoryRbacQualificationTest#r13RollbackAfterSuccessfulDomainCallsCanBeSafelyRetried](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L690) | Shortage/outer rollback не равны hard crash; подтвердить last-line failure и отсутствие всех success-эффектов. |
| CORE-SAL-05 | SAL, INV | GAP | [SalesInventoryRbacQualificationTest#concurrentOrdersCannotOversellAndCrossTenantCannotCommand](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L330); [SalesInventoryRbacQualificationTest#opposingMultiSkuOrdersReserveWithoutDeadlock](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L356) | Старая проверка fulfillment не является hot-SKU race; нужны управляемые schedules и отрицательные ledger oracles. |
| CORE-SAL-06 | SAL, INV | GAP | [SalesInventoryRbacQualificationTest#r13PostgresCancelVsFulfillRaceHasExactlyOneTerminalOutcome](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L748); [SalesInventoryRbacQualificationTest#postgresMixedConcurrentOrderLifecyclePreservesLedgerAndIdempotency](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L584) | Старая привязка к permission deny ошибочна; random race не заменяет оба заданных порядка cancel × confirm/fulfill. |
| CORE-SAL-07 | SAL, PICK | PARTIAL | [SalesInventoryRbacQualificationTest#fc40PickedOrderCannotCancelAndReservationStaysIntact](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L223); [QrWarehouseHandoverQualificationTest#qr05PreparingAndReadyRequireFullPickWithoutDebitingStock](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L233) | Есть последовательный picked-deny; не найден самостоятельный детерминированный pick × cancel в обоих порядках. |
| CORE-SAL-08 | SAL, PICK | PARTIAL | [SalesInventoryRbacQualificationTest#decFul01PickupProofRequiredBlocksGenericFulfillBeforePreparing](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L176); [PublishedQrCheckoutQualificationTest#decFul01QrCheckoutPersistsPickupProofRequiredBeforePreparation](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/autoorder/PublishedQrCheckoutQualificationTest.java#L402) | SQL fixture proof-mode допустим для локального fence; полный путь должен создавать mode через verified QR checkout. |
| CORE-SAL-09 | SAL, PICK | GAP | [SalesInventoryRbacQualificationTest#decFul01PickupProofRequiredBlocksGenericFulfillBeforePreparing](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/SalesInventoryRbacQualificationTest.java#L176); [QrWarehouseHandoverQualificationTest#qr06PostgresConcurrentScansWithDifferentKeysFulfillOnlyOnce](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L394) | Старая привязка к BUS happy path неверна; double-scan не заменяет generic fulfill × secure pickup на одном заказе. |
| CORE-SAL-10 | SAL, SCOPE, PICK | GAP | [MvpServiceWarehouseAdmissionQualificationTest#mvpW3StrictWarehouseAssignmentRequiredForConfirmAndFulfill](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/MvpServiceWarehouseAdmissionQualificationTest.java#L133); [MvpServiceWarehouseAdmissionQualificationTest#mvpW3ReleaseCannotUseTenantGrantAfterWarehouseScopeRevocation](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/MvpServiceWarehouseAdmissionQualificationTest.java#L167) | Старая привязка к picked cancel неверна; квалифицировать strict flag и оба revoke orders для каждой операции. |
| CORE-INV-01 | INV | PARTIAL | [InventoryMovementQualificationTest#cannotReduceBelowReservedOrCrossTenantReference](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/inventory/InventoryMovementQualificationTest.java#L109); [InventoryTransferStocktakeQualificationTest#stocktakePostsOnlyMeasuredDeltaAndRejectsStaleOrBelowReserved](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/inventory/InventoryTransferStocktakeQualificationTest.java#L642) | Один same-key movement не покрывает transfer/stocktake; расширить реальные команды и ledger baseline. |
| CORE-INV-02 | RECON, INV | PARTIAL | [InventoryTransferStocktakeQualificationTest#postgresInv16DurableAdvanceAndConcurrentReceivingHaveNoFalseDrift](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/inventory/InventoryTransferStocktakeQualificationTest.java#L569); [InventoryTransferStocktakeQualificationTest#postgresInv16ConcurrentAdvanceCannotDoubleCommitCursorOrFindings](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/inventory/InventoryTransferStocktakeQualificationTest.java#L487) | Отличать per-page REPEATABLE_READ и durable cursor от tenant-wide immutable snapshot; foreign/empty/end cases. |
| CORE-QR-01 | QR, SAL | PARTIAL | [PublishedOfferQrQualificationTest#qr01bRevokeReissueAndExactRetriesProduceOneGenerationEach](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/storefront/PublishedOfferQrQualificationTest.java#L262); [PublishedQrCheckoutQualificationTest#qr03RevokeBlocksNewCheckoutYetExactCommittedReplayWorks](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/autoorder/PublishedQrCheckoutQualificationTest.java#L487) | Стабильный reprint недостаточен; два revoke × checkout schedules и exact committed replay. |
| CORE-QR-02 | QR, FILES | PARTIAL | [CatalogPublicProductMediaQualificationTest#qr02aApprovedDerivativeIsPublicPNGButOriginalStaysPrivate](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/files/CatalogPublicProductMediaQualificationTest.java#L139); [CatalogPublicProductMediaQualificationTest#qr02aSellerMustExplicitlyApproveAndNeverPublishCrmAttachment](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/files/CatalogPublicProductMediaQualificationTest.java#L174) | Старая привязка проверяет visibility без bytes; negative storage invocation и public derivative отделить от original. |
| CORE-QR-03 | QR, INV | GAP | [RegionalPublicAvailabilityQualificationTest#qr02bQuantityMustFitOneWarehouseNotSumAcrossTwo](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/inventory/RegionalPublicAvailabilityQualificationTest.java#L122); [RegionalPublicAvailabilityQualificationTest#qr02bUnknownKatoQtyAndRetiredQrCannotLeakInventory](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/inventory/RegionalPublicAvailabilityQualificationTest.java#L215) | Старая привязка к private order read не относится к regional advisory; проверить zero reservation и отсутствие warehouse leakage. |
| CORE-QR-04 | QR, SAL | PARTIAL | [PublishedQrCheckoutQualificationTest#qrMvpVerifiedListCheckoutCreatesOneBuyerOwnedOrder](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/autoorder/PublishedQrCheckoutQualificationTest.java#L160); [PublishedQrCheckoutQualificationTest#qrMvpStaleVersionRevokeCrossBuyerAndSuspendedReplayHaveZeroEffects](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/autoorder/PublishedQrCheckoutQualificationTest.java#L226) | Основной entrypoint checkoutVerified; email-first checkout по умолчанию закрыт; price race отдельно. |
| CORE-QR-05 | NEGOTIATION | PARTIAL | [QrNegotiatedCheckoutQualificationTest#qr04aCounterThenBuyerAcceptanceCreatesOneQuoteAndImmutableEvents](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrNegotiatedCheckoutQualificationTest.java#L161); [QrNegotiatedCheckoutQualificationTest#qr04aWrongTurnStaleRevisionAndImmutableTerminalStatesRejected](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrNegotiatedCheckoutQualificationTest.java#L189) | Добавить точную привязку reject, self-accept, request conflict и конкурентный revision CAS. |
| CORE-QR-06 | NEGOTIATION, SAL | PARTIAL | [QrNegotiatedCheckoutQualificationTest#qr04bConcurrentDistinctKeysAtMostOneOrderOnRealPostgres](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrNegotiatedCheckoutQualificationTest.java#L418); [QrNegotiatedCheckoutQualificationTest#qr04bQuoteConsumedOnceAndSharedCheckoutKeyConflictsAcrossModes](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrNegotiatedCheckoutQualificationTest.java#L371) | Accepted happy path не доказывает разные ключи/namespace/restart; scope receipt tenant+key, quote uniqueness отдельно. |
| CORE-QR-07 | NEGOTIATION, SAL, IDENTITY | GAP | [QrNegotiatedCheckoutQualificationTest#qr04bExpiredQuoteFailsWithoutAnyOrderOrReceipt](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrNegotiatedCheckoutQualificationTest.java#L391); [QrNegotiatedCheckoutQualificationTest#qr04aExpiredProposalEffectiveStateAndMaterialization](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrNegotiatedCheckoutQualificationTest.java#L264) | Старая привязка к double consumption ошибочна; для expire/withdraw/QR revoke/suspend нужны свои состояния и оба порядка. |
| CORE-PICK-01 | PICK, INV | PARTIAL | [QrWarehouseHandoverQualificationTest#qr05PreparingAndReadyRequireFullPickWithoutDebitingStock](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L233); [QrMvpServiceJourneyQualificationTest#qrMvpJ1VerifiedListPriceToHandoverAndRestockPg](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrMvpServiceJourneyQualificationTest.java#L247) | Полный pick/ready/no early debit есть как кандидат; сохранить отдельный incomplete-pick отрицательный oracle. |
| CORE-PICK-02 | PICK, SCOPE | GAP | [QrWarehouseHandoverQualificationTest#qr06CredentialRequiresVerifiedBuyerAndReadyState](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L285); [QrWarehouseHandoverQualificationTest#qr06PreviewDoesNotConsumeAndCompletedHandoverDebitsOnce](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L307) | Старая привязка к preparation не доказывает issue/preview; wrong warehouse/expiry и zero IO/stock отдельно. |
| CORE-PICK-03 | PICK, INV | PARTIAL | [QrWarehouseHandoverQualificationTest#qr06ReissueInvalidatesOldSecretAndExpiredProofNeverDebits](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L354); [QrWarehouseHandoverQualificationTest#qr06PreviewDoesNotConsumeAndCompletedHandoverDebitsOnce](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L307) | Issue-only не доказывает debit; reissue через issue с новым ключом; старый proof, replay и unique receipt. |
| CORE-PICK-04 | PICK, SCOPE | PARTIAL | [QrWarehouseHandoverQualificationTest#qr06bPgTwoIndependentJVMsCompeteForOneHandover](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L1141); [QrWarehouseHandoverQualificationTest#qr06bPgScopeRevocationCommitPrecedesHandoverAdmission](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L615) | Двухпоточный scan не равен two-JVM; добавить revoke-first и handover-first, account и permission variants. |
| CORE-PICK-05 | PICK, INV | PARTIAL | [QrWarehouseHandoverQualificationTest#qr06bPgJVMKilledBeforeCommitRollsBackAndRetrySucceeds](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L1073); [QrWarehouseHandoverQualificationTest#qr06bPgJVMKilledAfterCommitReplayHasNoSecondDebit](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L1106) | Реальные Runtime.halt workers существуют; отдельно SQL fault при receipt/credential и run logs с кодами выхода. |
| CORE-RET-01 | RETURN | PARTIAL | [QrWarehouseHandoverQualificationTest#qr07R0ConcurrentPartialClaimsCannotExceedFulfilledQuantity](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L2346); [QrWarehouseHandoverQualificationTest#qr07R0RejectsUncompletedOrForeignBuyerBeforeAnyReturnWrite](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L2326) | Capacity учитывает все незакрытые claims кроме REJECTED/CANCELLED_BY_BUYER; проверить состояния и independent sum. |
| CORE-RET-02 | RETURN | PARTIAL | [QrWarehouseHandoverQualificationTest#qr07R1ReviewApproveAndImmutableDecisionReplay](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L2393); [QrWarehouseHandoverQualificationTest#qr07R25ProofRejectsForgeryAndRotationAndConsumesOnce](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L2901) | Review не равен physical reception; proof issue/consume, wrong warehouse, replay и no early stock. |
| CORE-RET-03 | RETURN, INV | PARTIAL | [QrWarehouseHandoverQualificationTest#qr07R2RestockPostsOneLinkedStockMovementOnlyAfterInspection](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L2606); [QrWarehouseHandoverQualificationTest#qr07R2WriteOffAndQuarantineNeverIncreaseSellableStock](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L2648) | Связать RESTOCK movement по posting.movement_id; QUARANTINE отдельный ledger, WRITE_OFF без sellable credit. |
| CORE-RET-04 | RETURN, INV | GAP | [QrWarehouseHandoverQualificationTest#qr07R25ConcurrentQuarantineRestockVsDisposeSettlesExactlyOnce](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L3156); [QrWarehouseHandoverQualificationTest#qr07R25QuarantineResolutionTenantAndWarehouseScopeCannotCross](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L3214) | Старая проверка disposition не доказывает resolution race; проверить два заданных порядка RESTOCK/DISPOSE и immutable original. |
| CORE-RET-05 | RETURN, INV | PARTIAL | [QrWarehouseHandoverQualificationTest#qr07R2JvmHardCrashBeforePostCommitRollsBackAndRetryPostsOnce](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L2783); [QrWarehouseHandoverQualificationTest#qr07R2JvmHardCrashAfterPostCommitReplaysOriginalMovement](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java#L2817) | Two-JVM duplicate не заменяет crash; добавить reception duplicate и исходный COMPLETED hash. |
| CORE-CRM-05 | LINK, SAL | PARTIAL | [CrmBuyerLinkQualificationTest#verifiedBuyerConfirmsOrderAndSellerSeesOnlyTenantOrders](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmBuyerLinkQualificationTest.java#L131); [CrmBuyerLinkQualificationTest#tenantSeparationAndOneToOneConstraintsPreventSilentIdentityMerge](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmBuyerLinkQualificationTest.java#L154) | Проверить оба независимых CRM_READ/SALES_ORDER_READ и отсутствие infer-by-email; source test body не заменяет run. |
| CORE-CRM-06 | LINK, IDENTITY | PARTIAL | [CrmBuyerLinkQualificationTest#postgresOrderedConfirmVersusRevokeAdmitsOneCoherentHistory](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmBuyerLinkQualificationTest.java#L387); [CrmBuyerLinkQualificationTest#deletedAccountFailClosedWithoutDeletingLinkedHistory](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmBuyerLinkQualificationTest.java#L464) | Последовательный dispute supporting; confirm × revoke не равен orderHistory × revoke; read linearization отдельно. |
| BSR-TASK-01 | TASK, INTERACTION | NEW | [TaskServiceQualificationTest#t18CrmFollowupCreationRetryAndScopedLinkRead](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/tasks/TaskServiceQualificationTest.java#L544); [TaskServiceQualificationTest#t01ManagerCreatesAssignedTaskAndEmployeeCompletesHistory](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/tasks/TaskServiceQualificationTest.java#L85) | BUS-TASK-01 сохраняется как alias-support, не новый CORE; связать followup и весь lifecycle в BUS-J1. |
| BSR-TASK-02 | TASK, INTERACTION | NEW | [TaskServiceQualificationTest#t52PostgresqlCrmStatusRaceSerializesWithSnapshotCapture](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/tasks/TaskServiceQualificationTest.java#L1634); [CrmInteractionQualificationTest#t00PgPortFailureRollsBackCancellationAndEvent](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java#L1290) | BUS-TASK-02 про revoked execute не эквивалентен CRM cancellation × TASK complete; T00 snapshot и явный reconcile без auto-cancel. |
| BSR-FILES-01 | FILES | NEW | [FilesR10bQualificationTest#crmCustomerAttachmentLifecycleRequiresTypedOwnerAndSeparatePermissions](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/files/FilesR10bQualificationTest.java#L487); [FilesR10bQualificationTest#tcrud03BinaryReadDoesNotWriteAnyBytesForWrongOwnerOrMissingPiiPermission](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/files/FilesR10bQualificationTest.java#L584) | REQUIRED_IF_EXPOSED разрешён как REQUIRED_FOR_MVP для public photos; private CRM attachments — regression в имеющемся scope. |
| BSR-FILES-02 | FILES | NEW | [FilesR10bQualificationTest#crashAfterPromoteBeforeSqlCommitIsReconciledAndRetrySucceeds](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/files/FilesR10bQualificationTest.java#L343); [RustFsLiveInfrastructureE2ETest#crashAfterCopyBeforeCommitSweepOldTokenAndCompleteRetry](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/files/RustFsLiveInfrastructureE2ETest.java#L198) | Сверить simulated fault, process/container restart и реальный ClamAV отдельно; recovery — internal worker, не public facade. |
| BSR-FX-01 | CURRENCY, STO, SAL | NEW | [CommerceCurrencyAdmissionQualificationTest#curc03UsdPublishedLegacyOfferRejectedWithoutOrder](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/organization/CommerceCurrencyAdmissionQualificationTest.java#L165); [MvpServiceCurrencyStartupQualificationTest#mvpW2StrictServiceModeCannotStartWithPublicCurrencyAdmissionDisabled](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/organization/MvpServiceCurrencyStartupQualificationTest.java#L9) | G1 профиль обязан включать service-mode и currency admission; populated preflight, public media-before-currency deny. |
| BSR-FX-02 | FXAUTO, STO | NEW | [FxAutoOwnerAdmissionQualificationTest#budgetTwoSixtyOperationsOneWinnerReplayRollbackAndPolicyChange](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/fx/auto/FxAutoOwnerAdmissionQualificationTest.java#L839); [FxAutoOwnerAdmissionQualificationTest#lifecycleApprovalAndGrantRevocationSerializeBothOrders](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/fx/auto/FxAutoOwnerAdmissionQualificationTest.java#L617) | ASSESSMENT_ONLY: assess не публикует, SYSTEM_AUTO запрещён 0171; direct assess integration test не найден. Будущий executor отдельно от existing regression. |
| BSR-FIN-01 | SAL, RETURN, FIN | NEW | [OrderPackagingQualificationTest#r0PackagingAcceptRejectCancelCannotCreateInventoryOrFinancialPosting](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/OrderPackagingQualificationTest.java#L389); [QrMvpServiceJourneyQualificationTest#qrMvpJ1VerifiedListPriceToHandoverAndRestockPg](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrMvpServiceJourneyQualificationTest.java#L247) | crm.fin_invoice уже есть, но negative finance oracle в handover/return ещё надо привязать; packaging test только supporting. |
| BSR-FIN-02 | FIN | NEW | Нет production/executable anchor; POST_MVP | Только schema foundation crm.fin_invoice и Money, нет invoice/payment/allocation service route; POST_MVP, не N/A PASS. |
| BSR-INS-01 | INSIGHT | NEW | Нет production/executable anchor; POST_MVP | Не найден production INSIGHT service; POST_MVP, утверждённая модель метрик отсутствует. |
| BSR-INS-02 | INSIGHT | NEW | Нет production/executable anchor; POST_MVP | Не найден production projection/replay route; POST_MVP, не строить analytics ради BSR. |
| CORE-XD-01 | IAM, CRM, TASK, CAT, STO, SAL, INV, LINK | PARTIAL | [BusMvpServiceJourneyQualificationTest#busMvpJ1OrganizationEmployeeCustomerTaskSkuOrderStockHistoryPg](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/BusMvpServiceJourneyQualificationTest.java#L92); [TaskServiceQualificationTest#t18CrmFollowupCreationRetryAndScopedLinkRead](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/tasks/TaskServiceQualificationTest.java#L544) | BUS использует new VerifiedActor, обычную TASK и email-first checkout; CRM buyer link, cancellation и signed chain не доказаны одним journey. |
| CORE-XD-02 | QR, NEGOTIATION, PICK, RETURN | PARTIAL | [QrMvpServiceJourneyQualificationTest#qrMvpJ1VerifiedListPriceToHandoverAndRestockPg](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrMvpServiceJourneyQualificationTest.java#L247); [QrMvpServiceJourneyQualificationTest#qrMvpJ1NegotiatedPriceToHandoverAndQuarantinePg](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/sales/QrMvpServiceJourneyQualificationTest.java#L270) | Два маршрута существуют, decoder stub; tenant/email/warehouse setup через SQL/repos; нет WRITE_OFF полного J1. |
| CORE-XD-03 | IAM, CRM, STO, SAL, INV, QR, FILES | GAP | [UserRoleMatrixR31QualificationTest#urR31DualTenantMemberHasDifferentPermissionsAndRevocationIsLocal](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/iam/UserRoleMatrixR31QualificationTest.java#L167); [FilesR10bQualificationTest#tcrud03BinaryReadDoesNotWriteAnyBytesForWrongOwnerOrMissingPiiPermission](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/src/test/java/kz/rm/files/FilesR10bQualificationTest.java#L584) | Старая привязка к negotiated happy path неверна; собрать A/B/AB по всем owner reads/list/count/history/bytes. |
| CORE-XD-04 | MIGRATION | GAP | Populated workflows §15.3 + historical fixture/schema/restore artifacts | Старая привязка к oversell не относится к upgrade; использовать populated workflows, baseline schema/fixtures, restore и hash исторических rows. |
| CORE-XD-05 | EVIDENCE | GAP | Exact-SHA workflow + verify_rm_qa_01b.py; semantic gate §15.1 | Старая привязка к seller suspend × checkout неверна; gate является workflow+verifier+artifact set, не одним JUnit методом. |

## 14. Независимые SQL-oracles на реальных таблицах

Ниже — контракты проверок для реализации BSR-20, **не результат исполнения SQL**. Named parameters связывает test harness; не подставлять строки конкатенацией. Before/after снимаются на отдельных соединениях в read-only REPEATABLE READ после завершения соответствующих service transactions. Между снимками допускается только записанный schedule; cross-tenant control и общий count не должны захватывать параллельные чужие fixtures.

### 14.1 Checkout, подтверждение и immutable snapshots

```sql
-- SQL-BSR-01: один receipt для конкретного tenant/request key.
SELECT r.order_id, r.request_sha256, o.status, o.total_amount, o.currency,
       o.buyer_user_id, o.fulfillment_mode
FROM crm.sales_checkout_receipt r
JOIN crm.sales_order o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
WHERE r.tenant_id = :tenant AND r.idempotency_key = :request_key;

-- SQL-BSR-02: после успешного confirm вернуть НОЛЬ несогласованных строк.
SELECT l.id
FROM crm.sales_order_line l
LEFT JOIN crm.inv_stock_reservation r
  ON r.tenant_id = l.tenant_id AND r.order_line_id = l.id
WHERE l.tenant_id = :tenant AND l.order_id = :order_id
  AND (r.id IS NULL OR r.order_id <> l.order_id OR r.state <> 'ACTIVE'
       OR r.location_id <> :warehouse OR r.quantity <> l.quantity
       OR r.item_id IS DISTINCT FROM l.catalog_item_id_snapshot);

-- SQL-BSR-03: canonical accepted-line snapshot; хранить отсортированный hash.
SELECT id, offer_id, catalog_item_id_snapshot, quantity,
       unit_price_snapshot, currency, title_snapshot
FROM crm.sales_order_line
WHERE tenant_id = :tenant AND order_id = :order_id
ORDER BY id;
```

Для SQL-BSR-01: same-key/same-payload повтор возвращает тот же order/receipt; conflicting payload — проверенный отказ с неизменным результатом before/after. Проверить также новые order/status-event/email-outbox дельты, а не только count receipt. Другой ключ не универсально duplicate: unique accepted quote квалифицируется отдельно.

Для SQL-BSR-02 дополнительно `line_count > 0`, `active_reservation_count = line_count`, один warehouse, order CONFIRMED, `on_hand` неизменён. Иначе пустой заказ даст ложное «0 нарушений». После PLACED reservation count=0; после отмены active count=0; после fulfillment все исходные reservations FULFILLED, не удалены. Для replay читать прежний receipt согласно **текущему security contract**: committed outcome не даёт отозванному actor права читать PII.

### 14.2 Ledger и proof-required fulfillment

```sql
-- SQL-BSR-04: before/after по одной позиции; не предполагать нулевую opening balance.
SELECT b.on_hand, b.reserved,
       COALESCE((SELECT SUM(m.delta_quantity)
                 FROM crm.inv_stock_movement m
                 WHERE m.tenant_id=b.tenant_id AND m.location_id=b.location_id
                   AND m.item_id=b.item_id), 0) AS movement_sum,
       COALESCE((SELECT SUM(r.quantity)
                 FROM crm.inv_stock_reservation r
                 WHERE r.tenant_id=b.tenant_id AND r.location_id=b.location_id
                   AND r.item_id=b.item_id AND r.state='ACTIVE'), 0) AS active_reserved
FROM crm.inv_stock_balance b
WHERE b.tenant_id=:tenant AND b.location_id=:warehouse AND b.item_id=:item;

-- SQL-BSR-05: завершённый proof-required заказ без handover — нарушение.
SELECT o.id
FROM crm.sales_order o
LEFT JOIN crm.sales_pickup_handover h
  ON h.tenant_id=o.tenant_id AND h.order_id=o.id
WHERE o.tenant_id=:tenant AND o.id=:order_id
  AND o.status='COMPLETED' AND o.fulfillment_mode='PICKUP_PROOF_REQUIRED'
  AND h.id IS NULL;

-- SQL-BSR-06: stock effects конкретного заказа, не tenant-wide count=1.
SELECT item_id, COUNT(*) AS movements, SUM(delta_quantity) AS delta
FROM crm.inv_stock_movement
WHERE tenant_id=:tenant AND source_type='SALES_ORDER'
  AND source_id=:order_id AND movement_kind='FULFILLMENT'
GROUP BY item_id ORDER BY item_id;
```

SQL-BSR-04: `after.on_hand-before.on_hand = after.movement_sum-before.movement_sum`; `reserved=active_reserved` и `0<=reserved<=on_hand`. Исторические movement rows неизменны. Нельзя требовать `on_hand=SUM(all movements)` без baseline reconciliation: старые fixture balances могли существовать до ledger. Для transfer проверять обе ноги и их общую сумму=0, а не только одну позицию. Карантин проверяется отдельно от sellable stock.

SQL-BSR-05 дополнить `count(handover)=1`, ссылками credential→handover→status event, handover lines→reservation→movement, количествами и складом. SQL-BSR-06 сверить с полным набором reservation IDs и принятых линий. Multi-line заказ не обязан иметь один movement на весь заказ; канонические одноразовые эффекты определяются reservation/line и уникальными receipts. Success response без этих связей не PASS.

### 14.3 Возврат, TASKS и отсутствие финансового эффекта

```sql
-- SQL-BSR-07: posting с источником и состоянием исходного заказа.
SELECT p.posting_id, p.disposition, p.quantity, p.movement_id,
       p.quarantine_movement_id, r.source_handover_id, o.status
FROM crm.sales_return_posting p
JOIN crm.sales_return_request r ON r.tenant_id=p.tenant_id AND r.id=p.return_id
JOIN crm.sales_order o ON o.tenant_id=r.tenant_id AND o.id=r.order_id
WHERE p.tenant_id=:tenant AND p.return_id=:return_id;

-- SQL-BSR-08: связь follow-up с реальным CRM interaction, не просто рядом созданная TASK.
SELECT t.id, t.status, t.assignee_user_id, t.row_version,
       f.interaction_id, i.customer_id
FROM crm.task_item t
JOIN crm.task_crm_followup f ON f.tenant_id=t.tenant_id AND f.task_id=t.id
JOIN crm.customer_interaction i ON i.tenant_id=f.tenant_id AND i.id=f.interaction_id
WHERE t.tenant_id=:tenant AND t.id=:task_id;

-- SQL-BSR-09: до/после выдачи и возврата invoice rows не появляются и не меняются.
SELECT id, order_id, amount, currency, state, row_version
FROM crm.fin_invoice
WHERE tenant_id=:tenant AND order_id=:order_id
ORDER BY id;
```

SQL-BSR-07: ровно одна строка; order остаётся COMPLETED. RESTOCK — linked sellable movement с правильным delta; QUARANTINE — только `inv_return_quarantine_movement/balance`; WRITE_OFF — оба movement pointers NULL и отсутствие sellable credit. После quarantine resolution проверить отдельный resolution receipt и уменьшение quarantine quantity; первоначальная posting evidence не переписывается. Capacity RMA суммируется по `sales_return_line` с исключением только `REJECTED/CANCELLED_BY_BUYER`, затем сравнивается с подтверждённой handover quantity. Не брать текущую offer quantity или просто total ordered.

SQL-BSR-08: ровно один scoped link, ожидаемый assignee/state, полная история `task_event`. Для T00 сверить canonical CRM cancellation event и seal/count/digest snapshot; сохранённый снимок не является текущей очередью и не обещает auto-cancel. SQL-BSR-09 — before/after совпадает; отсутствие invoice/payment сервиса не отменяет проверку существующей таблицы.

Для IAM/CRM/FILES отдельные oracles из приложения дополняются безопасными проекциями: count/status/version и hash encrypted envelopes, без выгрузки plaintext, raw JWT, OTP, pickup secret или исходных private bytes. Проверка «не вызван storage read» требует instrumented IO assertion, одним SQL её не доказать. Hash PII/малых словарных секретов не публиковать как безопасную замену редактированию: артефакты должны содержать синтетические данные и минимально необходимые показатели.

## 15. Уточнённый протокол BSR-20/40 и связь с CI

### 15.1 Разделить пять независимых признаков

Для каждого `scenarioId + variantId` хранить отдельно:

1. **Scope:** REQUIRED_FOR_MVP / REGRESSION_IF_PRESENT / POST_MVP / approved N/A; причина и норматив. Approval N/A нельзя заменить отсутствием test class.
2. **Mapping:** EXACT / SUPPORTING_PARTIAL / GAP; точное Given/When/Then, полный oracle, service boundary и дополнительные dependencies. `GAP_WRONG_BEHAVIOR` в R1.1 — причина GAP прежней карты, а не новый verdict исполнения.
3. **Implementation:** реализованный production путь, отсутствующий путь либо CONTRACT_CONFLICT. Наличие метода и таблицы само по себе не IMPLEMENTED полного сценария.
4. **Execution:** NOT RUN / BLOCKED / PASS / FAIL / approved N/A, конкретный run/attempt и DB. Test source discovery не execution.
5. **Maturity:** по §2.1; уровень всего сценария равен минимуму его обязательных вариантов. Один happy-path PASS не закрывает concurrency/recovery/security. Общее G1 отдельно от локальной зрелости.

Вариант имеет один стабильный ID вида `CONC-CORE-SAL-09-GENERIC-FIRST`; варианты не увеличивают количество CORE. Для одной бизнес-операции несколько техник могут ссылаться на один метод только при явном assertion mapping. Пустой requiredVariants, отсутствующий SQL artifact, неожиданно skipped test или неизвестный DB product дают HOLD. Новый BSR gate должен проверять **смысл и полноту утверждённой карты**, а не только presence строк ID.

### 15.2 REAL, concurrency и recovery

- Fixture manifest разделяет prerequisites и проверяемые transitions. Нельзя выдавать SQL-made pick, confirmed order или verified email за пройденную сервисную операцию. Для локального guard-test такая fixture допустима с явно узким scope.
- На квалифицируемом маршруте реальные Spring beans и transaction proxies; synthetic signed access tokens проходят настоящий decoder/resolver и local JWKS. `new VerifiedActor`, mocked decoder/owner port допустимы лишь supporting tests. HTTP controllers для этого не нужны.
- Service call должен реально commit. SQL на том же test-managed rollback transaction либо чтение через тот же persistence context не является независимым post-commit доказательством. Указать auto-commit, propagation, pool budget и tx ownership.
- Для A-FIRST/B-FIRST зафиксировать PID/connection, admission lock, barrier, successful commit/rollback, observed wait. Одного общего start latch и многократного random race недостаточно для заявления обоих порядков. Другой порядок не обязан давать два успеха; ожидания задаются state machine и authorization linearization.
- Отзыв разрешений после допустимого admission может ждать завершения уже допущенной транзакции. «Ни одной операции после времени начала revoke» — ошибочная гарантия; проверять commit/lock order. Для чтений указывать snapshot/read linearization и отсутствие нового доступа после победившего revoke.
- Не квалифицировать несуществующий переход. Например, WITHDRAW применим к допустимому состоянию negotiation, а не к произвольно изменённому уже consumed quote. Expiry boundary проверять явными `now<expiresAt`, `now=expiresAt`, `now>expiresAt`, часовым поясом и точностью БД.
- Зафиксировать точные ожидаемые domain codes/SQLSTATE. Catch любого `IllegalStateException` как «допустимого проигравшего» способен скрыть техническую ошибку; unexpected exceptions — FAIL. Timeout — самостоятельный результат, не автоматически успех проигравшей стороны.
- Различать exception rollback, connection termination, JVM halt и infrastructure restart. Process workers `QrHandoverProcessWorker` и `QrReturnProcessWorker` уже содержат `Runtime.halt(86/87)` для до/после commit. Переиспользовать, собрать exit code/startup/SQL/replay evidence; не заменять исключением в unit test.
- «Ровно один эффект» ограничен authoritative DB state/receipt. При внешнем IO сначала определить durable intent/dedup/ACK; не обещать exactly-once network delivery. Не внедрять saga/outbox в монолит только ради заполнения техники COMP/OUT.

### 15.3 Обязательные workflow текущего admission

| Workflow | Required artifacts |
|---|---|
| [CRM R1.2 Populated PostgreSQL Upgrade Qualification](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/crm-r12-populated-upgrade.yml) | `crm-r12-populated-upgrade-surefire` |
| [INVENTORY 0081 Populated PostgreSQL Upgrade Qualification](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/inventory-0081-populated-upgrade.yml) | `inventory-0081-populated-postgresql` |
| [RM Domain Security and PostgreSQL Qualification](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/kms-r1-qualification.yml) | `kms-h2-surefire`, `kms-postgres-surefire`, `iam-pii-populated-pg-surefire` |
| [RM CUR-R2C Policy Schema PostgreSQL Qualification](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/rm-cur-r2c-policy-postgresql.yml) | `cur-r2c-policy-pg-{sha}`, `cur-r2c-populated-pg-{sha}`, `cur-r2c-anchor-populated-pg-{sha}`, `cur-r2c-revoke-populated-pg-{sha}` |
| [RM TASK R2 Service Qualification](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/rm-task-r2-service.yml) | `rm-task-h2-{sha}`, `rm-task-postgresql-{sha}` |
| [RM TASK R3.2A Populated PostgreSQL Upgrade Qualification](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/rm-task-r32a-populated-upgrade.yml) | `rm-task-r32a-populated-pg-{sha}` |
| [RM TASK R32B T00 PostgreSQL Contract Qualification](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/rm-task-r32b-t00-postgresql.yml) | `rm-task-t00-postgresql-{sha}` |
| [RustFS Real Infrastructure E2E](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/rustfs-real-infra-e2e.yml) | `rustfs-real-infra-e2e-surefire` |
| [SALES 08.4 Populated PostgreSQL Upgrade Qualification](https://github.com/alimbetov/RM/blob/a09c53fd301838e04e2ffe5e518346764a2c51ef/.github/workflows/sales-084-populated-upgrade.yml) | `sales-084-populated-postgresql` |

Источники списка: `.github/workflows/rm-exact-sha-admission.yml` и `.github/scripts/verify_rm_qa_01b.py`; их состав сверён. Отдельные path-filtered workflows могут не стартовать на docs-only SHA; это не разрешает снять их обязательные методы из общего artifact. Для G1 нужны фактические XML, не только conclusion=success.

При read-only проверке Actions для baseline были видны успешные отдельные workflow и незавершённые Domain Security/Exact-SHA runs. Это временное наблюдение, не финальный verdict; скачивание/полная верификация artifacts и SQL evidence в R1.1 не выполнялись. Например, [Exact-SHA run baseline](https://github.com/alimbetov/RM/actions/runs/45400338393). Финальный gate должен заново проверить конкретные run attempts и не смешивать повторные запуски.

Существующий verifier уже проверяет provenance, XML и migration fingerprints; его переиспользовать. Но BSR дополнительно требует corrected semantic mapping, вариантные oracles, strict profile, evidence of both schedules и реальные crash kinds. Статическая R1.1 JSON пока не подключена к verifier; не выдавать её за автоматическую защиту CI.

**Минимальный вариантный manifest:** scenarioId/variantId, mappingRevision+hash, source/test/build SHA, workflow path+runId+runAttempt+jobId, suite+method, DB product/version/isolation, schema and config fingerprints, fixture seed, actor role/tenant scope без секретов, schedule/fault kind, SQL query+parameters+sanitized before/after artifacts, expected/actual, artifact SHA-256, verdict, reviewer. Для migration — baseline schema/changeset, old populated fixture hash, target schema, restore/startup evidence и replay исторического receipt.

H2-only предположения имеют явный allowlist, PG mandatory skips=0. Например, существующий TASKS verifier различает 53 discovered H2 cases с 18 PG-only skips и 53 executed PG cases; это scoped правило, не общий пропуск 18 тестов. Нельзя переносить totals между разными artifacts или считать один JUnit, запущенный в нескольких jobs, несколькими бизнес-сценариями. Повторный запуск не стирает первую ошибку: хранить причины flaky/retry и все attempts.

## 16. Конкретный порядок работ после R1.1

| Пакет | Действие на существующем коде | Критерий завершения |
|---|---|---|
| BSR-10A — semantic repair | Перепроверить и исправить 18 неправильных executable mappings; разложить 36 supporting CORE и 10 overlays на обязательные variants; не менять смыслы CORE-ID | Каждое требование связано с телом теста/assertion/SQL либо имеет честный GAP; новая версия карты и проверка её консистентности |
| BSR-10B — contract reconciliation | Разрешить BSR-DEC-01; определить archive-with-open-order через buyer link; записать границы R2C scope по BSR-DEC-02 | Нет неоднозначного expected outcome; будущие функции не включены молча |
| BSR-20A — reuse | Подключить имеющиеся signed-JWT, TASK followup, warehouse, crash workers, populated и RustFS evidence | Reuse matrix до написания новых тестов; отсутствующий artifact не прикрывается наличием source |
| BSR-20B — J1 proof | Дополнить существующие BUS/QR journey tests: signed actor chain, CRM-linked TASK/order proof, cancel branch, WRITE_OFF, independent SQL | Оба J1 выполняются production services; setup transitions не присвоены journey; legacy STANDARD route описан отдельно |
| BSR-30A — narrow missing variants | Только после полного поиска: pick × cancel, generic fulfill × pickup, quote invalidation × checkout, CRM read × revoke, нужные обе стороны warehouse races | Детерминированные schedules/negative deltas; исправления source только для воспроизведённого P0 |
| BSR-30B — included AUTO regression | Квалифицировать имеющийся assess и запрет неразрешённой публикации в текущем объёме | Assessment не разрешает запись; существующие R2C mandatory tests PASS; executor activation не выполняется по этому ТЗ |
| BSR-40 — frozen candidate | Один release candidate после завершения исправлений, обязательные jobs+artifacts и reviewed semantic map | Все применимые P0 variants PG QUALIFIED, zero unexpected skip/P0, independently checked SQL; G1 verdict отдельно |
| BSR-50 | Передать список оставшихся G2/G3/G4 требований | Не объявлять USER ACCEPTED/RELEASE ADMITTED по сервисным результатам |

В этой итерации не требуется создавать новый orchestration framework, расширять REST/UI, строить FINANCE/INSIGHT или начинать новую ветку. Статическая карта обнаруживает пробел; runtime defect регистрируется только после доказательства неверного production behavior либо самостоятельного убедительного source proof.

## 17. Changelog R1.1 и оставшиеся ограничения

**Добавлено:** baseline-проверка текущей base_dev; полный статический inventory 95 test files/866 declarations и 29 workflows; companion JSON со всеми 64 ID, source signatures, физическими таблицами, candidate test anchors и legacy mapping; 18 подтверждённых semantic mismatches; текущие J1 limitations; 9 конкретных SQL-oracle templates; строгий variant/evidence protocol и ограниченный backlog.

**Уточнено:** verified QR checkout; warehouse strict flags; существующий immutable fulfillment_mode; picked-cancel DENY; distinction INACTIVE/CLOSED; CRM buyer link вместо выдуманного order customer FK; TASK followup/T00 boundaries; private recovery FILES; KZT admission/preflight; существующий диагностический FX assess и blocked SYSTEM_AUTO; absolute budget delta; существующая fin_invoice foundation. Исторические sections 9/10 относятся к R1.0; актуальная проверка source и ограничения описаны в sections 11–17.

**Не изменено:** все 54 CORE-ID, 10 overlay IDs, цель 120; application source, migrations, workflows, прежняя executable evidence map, base_dev/main policy. Новый документ не подтверждает устранение traceability дефектов в CI — он фиксирует конкретное задание на их исправление.

**Проверка редакции:** сохранены 54 канонических ID и 10 overlays; проверены все source/test/table anchors, ссылки, SHA-256 95 test source files, состав 9 mandatory workflows и JSON. `git diff --check` — PASS; `python -m unittest discover -s .github/scripts -p 'test_*.py'` — 50 tests, OK. Это проверки документации/существующего verifier, не запуск 866 Java-тестов и не PostgreSQL qualification.

**Открыто:** полный semantic review всех выбранных method bodies/variants, нормативные расхождения, недостающие реальные service chains, execution/SQL artifacts, independent review и финальный Exact-SHA. 64 записи остаются SPECIFIED; наличие production anchors отдельно зафиксировано и не обнуляет уже реализованную функциональность. `BSR-00 STATIC INVENTORY COMPLETE / BSR-10 INITIAL CROSSWALK COMPLETE / SEMANTIC QUALIFICATION PENDING / QUALIFICATION NOT RUN / G1 HOLD / PRODUCTION HOLD`.
