# RM-SVC-MATURITY-COMPLETION-R2 — единый пул задач зрелости и согласованности сервисов

**Тип:** сквозное техническое задание / dependency-aware execution backlog (не дублирующий доменные ТЗ).
**Дата:** 2026-11-22. **Репозиторий/ветка:** `alimbetov/RM` / `base_dev`.
**Статус:** PLANNED / NOT IMPLEMENTED BY THIS DOCUMENT. **Release:** SERVICE-ONLY FEATURE FREEZE; HTTP/OIDC transport, production и merge в `main` — HOLD.
**База:** повторный аудит RM-SVC-MATURITY-R2; документы ниже; результат GitHub fetch в `base_dev` на дату составления. Перед каждым изменением записать фактические `git rev-parse HEAD`, diff и CI, не считать этот документ проверкой самого нового HEAD.

## 0. Приоритет источников и инварианты

Нормативный источник: [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md). Детальные действующие ТЗ: [RM-TENANT-SERVICES-COMPLETION-R1](RM-TENANT-SERVICES-COMPLETION-R1.md), [RM-INVENTORY-FUNCTIONAL-COMPLETION-R1](RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md), [RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES](RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md), [RM-SERVICE-12-PASS-REVIEW-PROTOCOL-R1](RM-SERVICE-12-PASS-REVIEW-PROTOCOL-R1.md); доказательный аудит [RM-SVC-FUNCTIONAL-DEPTH-AUDIT-R1](../reviews/RM-SVC-FUNCTIONAL-DEPTH-AUDIT-2026-11-22.md). При конфликте принимается R2-стандарт, затем предметное утверждённое решение; обнаруженный разрыв не устранять молчаливой сменой бизнес-политики.

**Не делать:** дублирующие entity/aggregate, микросервисы, новые ветки, Redis, универсальные CRUD/ACL/workflow engines, новые контроллеры, преждевременные маркетинговые/AI-возможности, прямые SQL-изменения чужих агрегатов, автоматические OWNER wildcard/PII grants, переписывание применённых Liquibase changesets. Соблюдать Java 21/Spring Boot 4.1.3, PostgreSQL+H2, additive Liquibase, modular monolith, VerifiedActor/trusted service identity, scoped tenant/permission/object admission и PostgreSQL бизнес-транзакции.

**Статусы:** `DONE-QUALIFIED@SHA` означает подтверждение только на указанном SHA; `PARTIAL` — реализация/проверка не завершена; `OPEN` — отсутствует квалифицированный результат; `DECISION` — бизнес-решение необходимо до кода. Переход на более поздний HEAD требует повторного соответствующего CI.

## 1. Исходная матрица зрелости (оценка, не процент тестов)

| Домен | База /5 | Неполная функциональность и release gate |
|---|---:|---|
| IAM / RBAC | 3 | реальный OIDC/BFF, email delivery, owner bootstrap, все TCRUD boundaries |
| CRM Core | 3 | портфельные ACL, часть поиска/expiry, полноценный CRM journey |
| CRM Interaction | 2 | encrypted note/result, TASKS linkage |
| CATALOG | 2 | archive blockers, price/offer ownership, SERVICE policy |
| SALES / AutoOrder | 2 | seller storefront/offer lifecycle, staff detail/history, PASS 09–12 |
| INVENTORY | 3 | current-HEAD concurrence, R1.5 reconciliation, legacy remediation, valuation отдельно |
| FILES | 3 | typed owner ACL beyond COMPANY, media query, production AV |
| ORGANIZATION | 2 | R1.1 race GREEN, оставшиеся интеграционные/loaded-DB тесты |
| GEO | 2 | полнота/KATO update process |
| KMS | 2 | rotation/fencing/migration, runtime key governance |
| AUDIT | 1 | bounded sanitized cross-domain query + producer coverage |
| STOREFRONT | 1 | tenant-managed publish/pause/offer CRUD/CAS |
| TASKS | 0 | service commands/queries/lifecycle |
| FINANCE | 0 | invoice/payment/allocation/reconciliation |
| MARKETING | 0 | consent-gated campaign/segment/dispatch |
| SUBSCRIPTION | 0 | plan assignment, entitlements, usage/limits |

**Не откатывать сделанное:** INVENTORY quantity Q1–Q9 `DONE-QUALIFIED@534aa180` ([evidence](../reviews/RM-INVENTORY-Q7-Q9-PG-PERFORMANCE-CLOSURE-2026-11-22.md)); ORG R1.1 шесть PostgreSQL гонок `DONE-QUALIFIED@69f757cf` ([evidence](../reviews/RM-ORG-IMPLEMENTATION-R1.1-2026-11-22.md)); SALES PASS 08.1–08.3 immutable SKU/price snapshots, PG `DONE-QUALIFIED@cb490556` (раздел 20 SALES ТЗ). Остальная квалификация не переносится автоматически на текущий HEAD.

## 2. Единые критерии каждой инженерной задачи

Для каждого ticket до начала реализации: (а) HEAD и затронутые классы/таблицы; (б) проверка, что код уже не реализован; (в) один concrete user journey и owner aggregate; (г) команда/query DTO и permissions; (д) owner/tenant FK, state/CAS, transaction/rollback, idempotency, immutable audit; (е) H2 и реальный PG positive/negative A/B/AB, foreign object UUID, revoked-role races и failure injection при изменяющих операциях; (ж) named tests, точный SHA и ссылки на CI, включая populated upgrade по необходимости. Тестировать независимым чтением DB; не объявлять `GREEN` на основании документа или mock-only теста.

Обязательные сквозные правила:
- CATALOG владеет SKU/kind, STOREFRONT — предложением и текущей ценой оффера, SALES — immutable agreed line/snapshot, INVENTORY — физическим quantity/ledger, CRM — клиентом/контактами, FINANCE — платежными фактами.
- Один order — один seller tenant; `buyer_user_id` не равен `crm_customer_id`. Нет кросс-tenant foreign references, нет PII в логах/аудите.
- Новые PRODUCT checkout допускают только PUBLISHED catalog PRODUCT/offer с immutable snapshot; SERVICE не резервируется INVENTORY до отдельного решения; legacy NULL SKU не восстанавливать по live offer.
- Reserve on confirm остаётся `MANDATORY_ON_CONFIRM`, пока не принято иное нормативное решение; `picking_strategy` и `valuation policy` — не действующий picking/COGS.
- ORDER `COMPLETED` означает складское fulfillment, не доказанную доставку или оплату.

## 3. Wave 0 — стабилизация и входные квалификационные gates (P0)

| ID | Приоритет | Задача / действие | Зависимости | Definition of Done |
|---|---|---|---|---|
| RM-QA-01 | P0 | Сверить общий HEAD `base_dev`, diff с последними квалифицированными SHA, конфликтующие Liquibase и параллельные SALES/INV/ORG работы | нет | Одна SHA-matrix изменённых сервисов/миграций/тестов; no false GREEN |
| RM-INV-13 | P0 | Закрыть остаточные R1.3 concurrency/retry/rollback (reserve/confirm × deactivate, transfer × deactivate, receive × deactivate) без регрессии Q1–Q9 | QA-01 | Все конкретные PG двухтранзакционные тесты, отсутствие deadlock/oversell/partial ledger, H2/PG exact-SHA GREEN |
| RM-INV-14 | P0 | Проверить stock movement/reservation при archive/deactivate, idempotency same-key/different-payload, точное состояние requestKey | INV-13 | Реальные DB invariants и корректный terminal replay; failures do not mutate stock |
| RM-INV-15 | P0 | Reconciliation onHand/reserved/available и immutable movements/transfer/reservation, отдельная typed read/diagnostic операция | INV-13, INV-14 | Нулевые расхождения на seeded/rollbacks/races; negative tamper case detected, без пересчёта истории на запись |
| RM-SEC-01 | P0 | TCRUD-00/03/04 per-method evidence всех имеющихся фасадов, IAM/KMS/AUDIT/system, скрыть raw UUID bypass где нужно | QA-01 | Method→permission→SQL→tests register; foreign ID, A/B/AB, revoke/disabled tenant; никакого blanket PASS |
| RM-SEC-02 | P0-DECISION | Утвердить solo-OWNER functional role bootstrap без wildcard и CRM_PII_READ | SEC-01 | Явное решение, auditable controlled grant, no self-escalation tests; до решения существующая политика fail-closed |
| RM-MAIL-01 | P0 для реального onboarding | Outbox worker + email provider/SMTP, retries/fencing, delivery proof и anti-abuse | IAM proof, нормативное mail ТЗ | PENDING→SEND→ACK/RETRY/poison, token not leaked, реальные negative и outage tests; не считать outbox доставкой |

**Gate W0:** коммерческий код может развиваться параллельно, но никакой интеграционный release/signoff без общей SHA-матрицы. IAM внешняя доставка — отдельный transport gate, не блокер внутренних синтетических service-only проверок.

## 4. Wave 1 — завершить CATALOG → STOREFRONT → SALES → INVENTORY (P0)

| ID | Приоритет | Scope | Зависимости | Приёмка |
|---|---|---|---|---|
| RM-CAT-01 | P0 | Safe `archive`: read-only Inventory usage blockers (onHand, reserved, active reservation, open stocktake) + published offer blocker, deterministic lock order | INV-14, ownership contract | Архивация не блокирует штатное перемещение оставшегося склада; два порядка race archive×receive/offer publish, foreign tenant, rollback |
| RM-CAT-02 | P0 | Документировать и проверить PRODUCT/SERVICE policy, archived prior-order treatment, category price/offer semantics | CAT-01 | PRODUCT stock-backed admission; SERVICE checkout/fulfill отдельное DECISION, не подменять stock SKU |
| RM-STORE-01 | P0 | `AuthenticatedStorefrontCommands`: tenant-authorized create/update/publish/pause; storefront slug unique by policy, CAS/version, immutable audit | SEC-01, CATALOG | Permission denied for other tenant; published→paused blocks new checkout; no-op does not fabricate event |
| RM-STORE-02 | P0 | Tenant-owned create/update price/hide/publish `store_offer`, offer→published catalog PRODUCT same tenant; price/currency validation и CAS | STORE-01, CAT-02 | Exactly one of concurrent updates wins, foreign SKU/offer rejected; no silent price overwrite from catalog |
| RM-STORE-03 | P0 | Explicit catalog→offer handoff: authoritative SKU id/kind vs independently seller-set offer price; current offer revision in checkout; no auto-sync ambiguity | STORE-02 | Publish SKU alone does not magically publish offer; checkout snapshots remain stable after catalog/offer edits |
| RM-SALE-084 | P0 | Завершить SALES PASS 08.4: защитить seller offer, storefront/offer commands и checkout price/state race; reuse 08.1–08.3 snapshot | STORE-01..03 | PG simultaneous publish/pause, price CAS×checkout, cross-tenant ownership; snapshot matches locked accepted offer; legacy snapshot tests remain GREEN |
| RM-SALE-09 | P0 | SALES PASS 09/12: actor, seller tenant RBAC, multi-tenant A/B/AB, permissions, revoke×checkout/confirm/cancel/fulfill, foreign UUID/PII | SALE-084, SEC-01 | All named adversarial PG tests; no unauthorized writes or foreign page/count totals |
| RM-SALE-10 | P1 | Staff order get/detail/line/status history, bounded page/count, buyer mine pagination, sanitized PII | SALE-09 | Seller can view exact lines/status/reservations; buyer only own, no tenant leakage, stable sort |
| RM-SALE-11 | P1 | Per-line confirm/fulfill reconciliation + exact replay (SKU, qty, location, reservation, movement), cancel(PLACED) without unnecessary INVENTORY_RELEASE if zero reservation | INV-15, SALE-09 | Replay does not create ledger entries; PLACED cancel least privilege; corrupted line/reservation detected |
| RM-SALE-12 | P1 | Принять минимальный staff draft workflow отдельно от immutable placed order; not in-place edits to checkout snapshots | SALE-10 | Valid draft→submit legal transitions, history, rejected stale CAS; if unresolved policy, DECISION not code |
| RM-COM-E2E | P0 release gate | Service E2E: SKU draft→publish→offer publish→checkout→confirm reserve→fulfill→ledger, and cancel/rollback/price/archive/revoke races | INV-15,CAT-01,STORE-03,SALE-084,SALE-09 | Exact SHA H2/PG real transactions; immutable buyer price/SKU, 0 excess debit, 0 cross-tenant reads; named trace + CI evidence |

**Не дублировать:** `sales_order_line.catalog_item_id_snapshot`, changesets 0076/0077, PostgreSQL immutable line trigger и PASS 08.1–08.3 уже реализованы/квалифицированы на `cb490556`. Задача — интеграционные регрессии и коммерческий seller-offer lifecycle.

## 5. Wave 2 — связность CRM, ORGANIZATION, TASKS и FILES (P1)

| ID | Задача | Зависимости | Приёмка |
|---|---|---|---|
| RM-ORG-12 | Завершить ORG R1.1→R1.2 lifecycle, populated migrations, архив/закрытие и SALES/INV races, фильтры и pagination | INV-15, SALE-11 | ACTIVE/INACTIVE/CLOSED, onHand/reserve/open stocktake blockers, history, both race orders, exact PG |
| RM-CRM-01 | Customer/Contact/Category/Contract: закрыть expiry filters, nearest-contract/read summary, scope/PII, pagination | SEC-01 | Tenant+responsible visibility policy explicit, A/B/AB, consent/PII separate, bounded stable search |
| RM-CRM-02 | CRM Interaction: encrypted NOTE/result and purposeful decrypt, next-action semantics | CRM-01, KMS policy | No plaintext in SQL/log/audit; wrong AAD/foreign tenant fail-closed; valid completed interaction results |
| RM-TASK-01 | Minimal `TaskService` assign/accept/start/complete/cancel/list/history with tenant RBAC and due date; CRM interaction linkage | CRM-02, owner scope DECISION | Real task actions & state machine, no cross-tenant contacts, authorization for reassignment, CAS/race |
| RM-FILE-01 | Typed attachment owner policies for CRM PERSON, CATALOG ITEM, SALES ORDER; media query | COM-E2E, CRM-01 | Per-owner ACL, permission and PII; no bytes read before admission; real scanner/storage negative tests |
| RM-AUD-01 | Sanitized tenant audit query + evidence-source coverage, bounded filtering and legal hold governance | SEC-01 | Every audited command traceable; no unredacted PII; global admin not implicitly granted tenant data |
| RM-GEO-01 | KATO completeness/source/version and deterministic validation for stored locations | ORG-12 | Reference fixtures and updates do not rewrite historical locations |

## 6. Wave 3 — полноценная коммерческая учётная цепочка (P2)

| ID | Задача | Зависимости | Приёмка |
|---|---|---|---|
| RM-FIN-01 | Invoice issue/cancel/immutable references to fulfilled or agreed seller order, currency and amount validation | COM-E2E, finance decisions | One seller tenant, no foreign order, idempotent issue/void history |
| RM-FIN-02 | Payment capture of **external fact** + allocations to invoices, partial/duplicate/reversal policy explicitly decided | FIN-01 | No fake payment gateway, exact allocations and reconciliation, no direct stock mutation |
| RM-FIN-03 | Payment reconciliation and customer-readable financial statuses (not merged with order fulfillment) | FIN-02 | Invoice/payment balances consistent, exceptions identifiable, negative cross-tenant tests |
| RM-MKT-01 | Consent-gated CRM segment/campaign, worker rechecks consent at send-time | MAIL-01, CRM-01 | Revoked consent blocks send, idempotent send records; no bulk dispatch before qualified channel |
| RM-SUB-01 | Tenant subscription plan assignment, feature entitlements + usage limits, platform governance | SEC-01, billing policy DECISION | Feature allowed iff entitlement AND permission; owner cannot grant billing governance to self |
| RM-KMS-01 | Encryption key rotation only after fencing, completed data migration/recovery and source-key availability | IAM/PII migration gates | Re-encryption, rollback/restore, corrupted envelope fail closed, rotation not advertised earlier |

## 7. Wave 4 — transport, эксплуатация и maturity 4→5 (P0 release gate, после Waves 0–2)

| ID | Задача | Acceptance |
|---|---|---|
| RM-REL-01 | Real OIDC issuer/BFF/HTTP admission, email proof, invite/recovery and anti-abuse | User-path E2E and trust boundary; no synthetic principal used as real proof |
| RM-REL-02 | Populated PG Liquibase rehearsal / verified backups and restore / least privilege runtime DB | Data preserved, old checksums untouched, DB privilege negative tests and rollback |
| RM-REL-03 | Load p95/p99, lock contention, pool pressure, retry/deadlock, concurrent tenant and SKU fanout | Defined workload ≥1000 operations, repeatable PG samples, no invented SLA, documented thresholds DECISION |
| RM-REL-04 | Production telemetry, alerting, audit retention, external delivery/storage recovery + independent security signoff | On-call runbook, recovery drill, approved SLO, approval per exact release candidate |
| RM-REL-05 | Product-facing E2E from signed-in seller to buyer order and staff fulfillment (after transport gate) | Full UI/HTTP and service chain, QA evidence, then separate production admission decision |

## 8. Матрица отрицательных сценариев — обязательный минимальный набор

| Test group | Сценарии | Инвариант |
|---|---|---|
| SEC-AB | A/B/AB membership, different grants, guessed foreign UUID, page/count/cursor | Никакой чужой row/PII/объект не доступен |
| SEC-REV | revoke/suspend concurrent command, membership workspace closure | Корректный ordered commit / deny, no stale authorization |
| CAT-ARCH | archive×receipt/transfer/confirm, active reserve/stocktake | No stranded onHand and no new inadmissible stock movement |
| STORE-CAS | two price writers, publish×pause, offer remap×checkout | One current version, sale snapshot stable |
| SALE-LINE | live-offer change after PLACED; NULL legacy SKU; replay, cancel×fulfill | Exact line→reservation→movement, no duplicate debit |
| INV-REC | partial transfer failure, duplicate key with different payload, 2 warehouses | Conservation, rollback, immutable ledger reconciliation |
| ORG-LIFE | deactivate×receive/transfer/confirm, close with free onHand, update×update | Legal lifecycle and CAS only |
| FILE-OWNER | unknown owner, foreign tenant, absent PII permission, scanner ERROR | Deny before byte stream; no misleading AVAILABLE |
| MAIL-OUT | provider down/retry/duplicate/poison, expired email challenge | No fake verified email, no duplicate business effect |
| FIN-MONEY | wrong seller/currency, allocation overflow/duplicate/reversal | No money mismatch, no fake PAID |

Названия в таблице — **группы запланированных проверок**, не уже существующие test method IDs. Реализация назначает полные JUnit имена и CI ссылки.

## 9. Реестр решений до кодирования

| Decision | Содержание | Безопасная текущая граница |
|---|---|---|
| DEC-OWNER-01 | Solo owner business-role self-assignment и начальные grants | No wildcard/self-assign until approval |
| DEC-STORE-01 | Границы изменений цены оффера, currency, valid-from и seller offer revisions | Current `store_offer` price is offer truth; catalog price is reference |
| DEC-CAT-SVC-01 | SERVICE purchase without Inventory and fulfillment semantics | No stock-backed SERVICE checkout |
| DEC-CAT-ARCH-01 | Historical remediation for archived SKU with real stock | Do not archive unsafely or mutate immutable ledger |
| DEC-CRM-SCOPE-01 | Tenant-wide vs responsible-only customer portfolio | Explicit tenant-wide permissions as existing baseline; no imagined portfolio ACL |
| DEC-TASK-01 | Who may reassign/manage another employee's tasks | Deny unauthorized cross-user modifications |
| DEC-SALE-DRAFT-01 | Seller draft and buyer placed order contract | Do not edit immutable placed lines |
| DEC-FIN-01 | Tax/partial payments/reversals and invoice cancellation | External fact only; no payment gateway assumption |
| DEC-REL-01 | Actual SLO, load capacity, risk signoff and rollout mode | Production remains HOLD |

## 10. Очередность и release milestones

1. **M0 / CONSISTENCY:** QA-01, INV-13/14/15, SEC-01, catalog/archive risk; точный baseline. Параллельную SALES работу не переписывать.
2. **M1 / SELLER OFFER:** STORE-01/02/03 + SALE-084 (PASS 08.4) с CAS, permission и price/state test.
3. **M2 / COMMERCIAL CORE:** SALE-09, SALE-11 и COM-E2E на одной общей SHA. Это **service-only завершение коммерческой цепочки**, не maturity 4 по пользовательской шкале.
4. **M3 / CRM OPERATIONS:** ORG-12, CRM-01/02, TASK-01, SALES detail/history, FILES ACL and AUDIT query.
5. **M4 / BUSINESS ACCOUNTING:** FIN-01/02/03; последующие MARKETING/SUBSCRIPTION после своих gates.
6. **M5 / PRODUCT & PRODUCTION:** REL-01..05; admission level 4 then 5 separately, independent approval.

## 11. Строгая шкала зрелости

- **0:** объявлен тип/DDL, прикладные команды отсутствуют.
- **1:** фрагментарные service операции, нет завершённого предметного сценария.
- **2:** отдельные рабочие процессы, но journey/связи разорваны.
- **3:** проверенное внутреннее функциональное ядро, transactions+state+negative PG tests; доказательство на exact SHA.
- **4:** полный **реальный пользовательский E2E**, включая доступную trusted границу transport и все нужные домены; service-only E2E не повышает автоматически до 4.
- **5:** доказанный production-ready release, independent security, resilience, operability, least privilege, backup/restore, metrics/SLO; отдельное signoff.

**Общий статус на 2026-11-22:** код этим ТЗ не менялся; tasks OPEN, если не указано DONE-QUALIFIED@SHA для предыдущего конкретного среза; full commercial E2E и production **HOLD**. Следующий практический этап — проверить HEAD, закрыть опасные P0 расхождения, затем `SALES PASS 08.4` и `PASS 09/12` с обязательной интеграцией CATALOG/STOREFRONT/INVENTORY.
