# RM-TCRUD-R2 — End-to-End Business Operation Completeness

Дата: 2026-11-26 | Версия: 0.1-draft | Статус: DESIGN ONLY / PROPOSED / IMPLEMENTATION NOT ADMITTED / PRODUCTION HOLD.
Ветка проектирования: `design/intelligent-commerce-r2`. Design parent: `10834168e76c381d85b165f1df0d1e4953416844`. Исходный срез сервисов: `base_dev beaf431a06b83f2210cdde72d8ff04c04f59f629` (выборочная сверка, не полный method inventory).

## 1. Цель и нормативы

Обеспечить завершенность разрешенных пользовательских бизнес-процессов от регистрации до исполнения и анализа результата. CRUD — не универсальный доступ к таблицам: операции определяет владелец агрегата, роль, состояние и предметные инварианты.

Нормативные документы:
- [Platform Standard R2](../../architecture/RM-PLATFORM-STANDARD-R2.md)
- [TCRUD R1](../../spec/RM-TENANT-CRUD-COMPLETION-R1.md) и [method inventory](../../reviews/RM-TCRUD-00-METHOD-INVENTORY-2026-11-22.md)
- [MVP Freeze](../../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) и [Six packages](../../spec/RM-MVP-SERVICE-FUNCTIONAL-CLOSURE-SIX-PACKAGES-R1.md)
- [Design Charter](README.md), [D08](D08-DECISIONS-AND-ADMISSION-R2.md), [DAG](../../spec/RM-INTELLIGENT-COMMERCE-EXECUTION-DAG-R1.json)
- [MIC-R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md)

R2 дополняет, не заменяет TCRUD-00..14, W1..W6, BUS-MVP-J1, QR-MVP-J1, действующие аналитические B4/B5, 112 семейств сценариев (число подлежит сверке). Не расширять утвержденный MVP без CR. Не менять Java, SQL, CI, тесты, ветки реализации, PR или deployment. REST/UI описываются как будущие контракты; действующий transport freeze сохраняется.

## 2. Источники и статус

PRESENT = подтвержден метод в исходниках; SPECIFIED = требование; PARTIAL = неполный lifecycle/evidence; UNKNOWN = не проверено; N/A = запрещено или неприменимо; DECISION_REQUIRED = нужна продуктовая развилка.
Отдельные оси: SOURCE_PRESENT, TEST_PRESENT, SERVICE_TESTED, PG_QUALIFIED, HTTP_QUALIFIED, UI_ACCEPTED, RELEASE_ADMITTED. Ни одна не выводится автоматически из другой. Исторический GREEN не переносится на новый SHA.

| Область | Подтверждено выборочно на base_dev | Не доказано этим документом |
|---|---|---|
| CatalogItemService | createDraft, updateModelCode, upsertTranslation, addPriceRevision, publish, archive | все атрибуты, restore, bulk |
| CatalogQueryService | get, search, priceHistory, history | фильтры, export и UI |
| CatalogGroupService | appendNode, assign | изменение иерархии и историческая семантика |
| TaskService | create, assign, reschedule, requeue, cancel, start, block, resume, complete, archive, getMine, listMine, managerQueue, historyMine | все переходы, повторение и UI |
| SellerOfferService | createStorefrontDraft, createDraft, publish, pause, resume, changePrice, changeStorefrontState | полный seller journey |
| CommercialAuthenticatedReadsController | ownOrders, sellerDetail, sellerStatusHistory | полный read API |
| SalesAuthenticatedCommandController | confirm, fulfill | все command endpoints |
| CRM/IAM/Inventory/Files | исторические TCRUD отчеты и существующие контракты | повторная проверка полного HEAD |

## 3. Целевая матрица операций (семейства, не подсчет тестов)

Обозначения scope: CORE = сверить с действующим MVP, не автоматическое включение; EXT = кандидат после CR; DI = проект аналитики по действующему scope либо будущий CR. Каждое семейство раскладывается на атомарные операции в R2.0–R2.1. Read подразумевает допустимые get/list/search/history, не доступ ко всем объектам.

| ID | Владелец / объект | Пользовательские операции | Scope / ограничения |
|---|---|---|---|
| IAM-01 | Identity | регистрация, подтверждение, профиль, смена email, recovery, блокировка | CORE; purpose-bound proofs, не raw UUID |
| IAM-02 | Workspace/membership | provision, приглашение, принятие, выбор tenant, suspend/reactivate | CORE; owner bootstrap decision |
| IAM-03 | Role/grant | create/read, grant/revoke, assign/unassign, deactivate | CORE; no wildcard/self-assignment |
| IAM-04 | Ownership | передача, аварийное восстановление | CORE; last-owner protection |
| ORG-01 | Organization | реквизиты, просмотр, допустимое изменение, деактивация | CORE/EXT по freeze |
| ORG-02 | Location/warehouse | create/read, rename/address/KATO, close/reopen | CORE/EXT; active stock/orders/reservations fence |
| ORG-03 | Staff scope | назначение склада, отзыв назначения | CORE; DEC-LOC-03 |
| ORG-04 | Settings | язык, зона, валюта, должности | EXT; версионирование календаря/валюты |
| CRM-01 | Customer | create PERSON/COMPANY, read/search, update, archive/restore | CORE; PII grants, CAS |
| CRM-02 | Contact | create/read/update/archive/restore | CORE; parent lifecycle |
| CRM-03 | Category | create/read/rename/assign/archive | CORE; restore только после решения |
| CRM-04 | Contract | create/read, changeDates/status, activate/renew/close/cancel | CORE; immutable revisions |
| CRM-05 | Consent/history | append, withdraw, read/check/history | CORE; no arbitrary update/delete |
| CRM-06 | Interaction/task link | register interaction, follow-up, history | CORE; owner services |
| CRM-07 | Portfolio/notes | assign/reassign, notes, segments, merge duplicates | EXT; PII and merge provenance |
| CAT-01 | Item/SKU | draft/read/search, model code, translation, publish/archive | CORE; reuse CatalogItemService |
| CAT-02 | Group | append node, assign, read hierarchy, proposed reclassification | CORE/EXT; versioned historical grouping |
| CAT-03 | Price | append effective revision, read history, scheduled price | CORE/EXT; exact currency, no overwrite history |
| CAT-04 | Attributes/media | units, attributes, images, ordering, detach | CORE/EXT; files owner policy |
| STO-01 | Storefront/offer | draft/read/publish/pause/resume, price/state change | CORE; canonical offer snapshot |
| STO-02 | QR | issue/read public card, activate/revoke/replace if supported | CORE/EXT; public proof != private authorization |
| SAL-01 | Checkout | verified buyer, list-price or accepted quote, create receipt/order | CORE; QR-MVP-J1, idempotency |
| SAL-02 | Order/lines | seller read/detail/history; staff draft/edit before confirmation | CORE/EXT; no duplicate order engine |
| SAL-03 | Quote | propose/counter/accept/reject/expire per existing contract | CORE; fixed accepted snapshot |
| SAL-04 | Execution | confirm/reserve, cancel/release, pick/READY, authorized handover/fulfill | CORE; DEC-FUL-01/DEC-SAL-02 |
| SAL-05 | Return | request/inspect/resolve full or partial return | CORE; RESTOCK/QUARANTINE/WRITE_OFF once |
| SAL-06 | Extended commerce | partial fulfillment, payments, documents, delivery | EXT; no silent MVP expansion |
| INV-01 | Stock balance | read/search, receive/adjust via commands | CORE; derived balance, no generic CRUD |
| INV-02 | Reservation | reserve/release/fulfill via SALES owner | CORE; atomic cross-domain transaction |
| INV-03 | Movement | append/read/history, reconcile | CORE; immutable ledger |
| INV-04 | Transfer/stocktake | request/confirm transfer, count, approve discrepancy | EXT unless already admitted; inspect actual source |
| INV-05 | Planning | thresholds, write-off, purchase planning | CORE/EXT; distinguish return disposition |
| TASK-01 | Task | create/read/assign/reschedule/requeue/start/block/resume/complete/cancel/archive | CORE; CAS/idempotency, assignee scope |
| TASK-02 | Queue/calendar | my tasks, manager queue, history, timing/calendar | CORE/EXT per approved R3 scope |
| TASK-03 | Collaboration | comments, attachments, recurring tasks, reminders | EXT; existing EVT not duplicated |
| FILE-01 | File | stage/upload/scan/complete/attach/read/detach/reclaim | CORE; pre-I/O authorization, owner scope |
| DI-01 | Metric/observation | read/filter/drill-down/explain/export | DI; immutable results, generation/coverage/MIC |
| DI-02 | Scenario settings | configure/activate/pause/reset allowed parameters | DI; versioning and explicit permission |
| DI-03 | Customer/Warehouse 360 | read timeline, segments, ABC/XYZ, evidence | DI; D03/D04, no source mutation |
| DI-04 | EVT rule/template | draft/edit/validate/preview/publish/disable/history | DI; D05, consent/occurrence binding |
| DI-05 | Dashboard/report | saved filters, preferences, export | EXT; no unauthorized derived leakage |
| DI-06 | Finding/recommendation | review, acknowledge, defer, reject, accept | EXT; separate disposition from immutable finding |
| DI-07 | Simulation/decision | save parameters, compare, approve, propose action | EXT; no automatic business command |
| DI-08 | Outcome/Radar | observe outcome, evidence, signal disposition | EXT; Radar resumption gates remain |
| X-01 | Bulk operations | import preview/validate/commit, export, bulk assign/archive | CORE only for approved onboarding scope; otherwise EXT |
| X-02 | Search UX | typed filter, sort, cursor, history, saved view | CORE/EXT; bounded queries and current access |

FINANCE, MARKETING, SUBSCRIPTION and new external integrations are separate verticals, not implied by CRUD completeness. A physical DELETE is never mandated merely to fill a matrix cell. Privacy erasure/retention requires approved policy; use archive, terminal transition or compensating event as appropriate.

## 4. User journeys and acceptance oracles

| ID | Actor and journey | Required observable outcome |
|---|---|---|
| J01 | Admin/owner: verified identity → tenant → membership → role → location | intended grants only; no cross-tenant or implicit owner rights |
| J02 | CRM staff: customer → contact → category/contract → follow-up | correct current state, immutable revisions and history |
| J03 | Seller: SKU → price → offer → QR | correct published snapshot; private data not exposed |
| J04 | Warehouse: receipt → available stock | ledger/balance reconciliation; duplicate receipt no double credit |
| J05 | Buyer/seller: verified checkout or quote → confirm/reserve → fulfill | single order and stock reflection; existing BUS/QR contracts |
| J06 | Staff: cancel or return → inventory reconciliation | released reservation or approved disposition exactly once |
| J07 | Task assignee/manager: assign → start → complete → history | allowed state and actor; no unrelated task visibility |
| J08 | Analyst: selected scope → observation → evidence | exact measure, generation, freshness, current permissions; UNKNOWN != zero |
| J09 | EVT administrator: rule draft → preview → publish → disable | approved version/recipient, idempotent delivery; scope per D05 |
| J10 | Future decision owner: finding → accept → proposed command → result | no business change before approval; owner reauthorizes; EXT |
| J11 | Onboarding operator: import preview → validation → commit/report | row-level errors, explicit atomicity, no duplicate imports; scope per approved XLSX |
| J12 | Multi-tenant member: switch A→B, filter, cursor, export | no retained A data or grants in B; revoke enforced |

J01–J12 are design crosswalks, not replacements for existing acceptance families. Link each applicable step to BUS-MVP-J1, QR-MVP-J1, CORE-P0, TCRUD, IE-Q/BV/MC, RAD and other existing IDs before adding tests.

## 5. Authorization and state contracts

Actor identity resolved from trusted boundary, selected tenant = iam_workspace.id; explicit active account/membership and exact permission per command/query. Apply object scope (including assigned warehouse and PII) after tenant fence. OWNER has no automatic wildcard; initial operational grants require approved decision. Buyer accesses own identity-linked orders, not seller-wide data. Proposed UI persona names (owner, manager, warehouse worker, analyst, buyer) are not grants. Service identities cannot impersonate user. Reauthorize at execution, every cursor page, export and file read; deny before side effects/storage I/O. Audit actor/tenant/operation/version without leaking secrets.

State transition registry: aggregate, source state, command, target state, role/grant, guard, expectedVersion, side effects, compensating transition, terminal policy, event. Read existing enums and owner methods before specifying actual transitions. Never add synthetic RESERVED status if confirm atomically reserves. Never treat COMPLETED as PAID/DELIVERED. Illegal transition, stale CAS, double confirm, cancel-vs-fulfill, revoke races must leave consistent state.

## 6. API/UI contract (future design only)

For each admitted operation: typed request/response, authenticated actor derivation, tenant selection, state/permission errors, validation errors, concurrency and idempotency contract, bounded pagination, current authorization, PII masking, stable operationId. No generic PATCH of status, balance, grants or computed metrics. No new controllers in design branch. UI must reflect available actions, but backend remains authority; display clear rejection, empty/unknown/loading/stale states and confirmation for destructive/irreversible actions. No client-only permission enforcement. UI and HTTP tests belong to future transport admission, not service qualification.

For bulk actions: explicit preview, validation, authorized row scope, duplicate policy, idempotency key + payload digest, defined atomic/chunked behavior, progress/result, retry semantics, row error reporting and safe export. Do not silently choose partial success. Existing import contracts take precedence.

## 7. Registry contract and coverage

One stable row per atomic user operation; no duplicate IDs. Required fields:
```text
operationId, familyId, domain, aggregate, journeyIds, scope, sourceRequirementIds,
actorPersona, permission, tenantPredicate, objectPolicy, sourceState, targetState,
facade, serviceMethod, repositoryOrSql, requestContract, responseContract,
transactionBoundary, lockOrder, expectedVersion, idempotency, auditEvent,
apiOperationId, uiActionId, sourceSha, testIds, executedEvidence,
serviceStatus, pgStatus, apiStatus, uiStatus, admissionStatus, gap, decisionId
```
Machine-readable registry proposed after complete inventory; this document contains family IDs only. Denominator = approved atomic operations applicable to release slice. Coverage = evidenced qualifying operations / approved applicable operations; UNKNOWN stays in denominator, N/A needs rationale, deferred scope reported separately. Do not count test methods or service names as completed operations. Report service, PG, HTTP and UI coverage separately.

## 8. Qualification requirements

Positive and negative case for each applicable operation; foreign UUID and mixed membership A/B; PII read/write permutations; revoked actor and role races; invalid state/version; retry same payload/different payload; rollback with zero unauthorized business side effects; append-only history; two-session PostgreSQL concurrency and independent SQL oracle for affected cross-domain invariants. Migration with populated database where schema changes. Verify search/count/cursor/export scope and no bytes before authorization. For analytics: exact distinct, hierarchy version, late events, coverage, generation and MIC-R1. H2 smoke does not replace PG. Preserve mandatory exact-SHA workflows and skip policy; no weakening gates. Follow operative CI impact/admission policy rather than inventing a new gate. Release target 100% approved applicable operations at required layer; no fabricated current percentage.

## 9. Work packages (proposal, not implementation admission)

| Package | Deliverable | Dependency |
|---|---|---|
| R2.0 | exact-HEAD method inventory, reconcile TCRUD-00..14 and W1..W6 | first; docs only initially |
| R2.1 | atomic operation + state registry, gap matrix | R2.0 |
| R2.2 | role/permission/object scope crosswalk | R2.0 |
| R2.3 | lifecycle guard and negative evidence mapping | R2.1/2 |
| R2.4 | cross-domain journeys and independent oracles | R2.3 |
| R2.5 | bulk/import/export scope and contract | R2.1/2; CR if new |
| R2.6 | intelligence user-operation contract, evidence and disposition | B5/D01–D08 reconciliation |
| R2.7 | REST contract and security admission plan | explicit transport gate |
| R2.8 | UI action and user journey acceptance plan | R2.7 and UI admission |
| R2.9 | slice-based exact-SHA qualification and release evidence | applicable gates and approved scope |

R2.0–R2.6 design may run alongside B5 without changing implementation sequence. Actual code work only after scope approval, drift review and operative admission. R2.7/8 design does not lift freeze. No new branch or automatic merge.

## 10. Open decisions / next steps

- TCRUD-R2-DEC-01: initial OWNER operational grant (reuse existing TCRUD-01 decision; check whether already resolved).
- DEC-02: location close/reopen with active stock/order and warehouse assignment.
- DEC-03: staff order draft, partial execution and return permissions versus current freeze.
- DEC-04: catalog hierarchy revision, archive/restore and historical attribution.
- DEC-05: duplicate merge/PII and retention policy.
- DEC-06: bulk atomicity, authorized preview and import limits.
- DEC-07: recommendation dispositions, approval and canonical owner command boundaries.
- DEC-08: analytics permissions and export scope; reuse existing D08 decisions.
- DEC-09: HTTP/UI cutover and role-specific acceptance.
All are OPEN until reconciled with existing approved decisions; do not re-open settled rules without CR.

Next: R2.0 source/contract inventory on latest base_dev; classify existing and missing atomic operations, link existing tests, record scope and decisions. Only then prioritize gap closure. Production HOLD and Radar resumption gates unchanged.

## 11. Limitations

Draft based on existing design charter, historic TCRUD evidence, MVP six-package spec and selective Java method inspection. Not a full source audit, no runtime tests, no current CI qualification claim, no exhaustive operation counts. Proposed family names, package names and persona examples are non-normative until review. No Java, DDL, CI or runtime change authorized.


## Report lifecycle operations (RQ-03)
Within DI-05 existing family: list/preview/download under current tenant+audience+entire actual scope+file ACL; authorized audience grant/revoke audited; regenerate new immutable report under fresh auth/revision; delete/expire only admitted owner retention policy; no destructive action implied merely by CRUD coverage. File owner binding gap tracked in D06E.5.2; no new family.

## RM-IC-R2 source alignment and roadmap (2026-11-26)
[Project alignment and roadmap](RM-IC-R2-PROJECT-ALIGNMENT-ROADMAP.md) is the current design handoff against base_dev a6bf99107aa64f4eb255b4997ef9417748b05607. Existing S0–S6 and operative DAG R1.2 (33 nodes/54 edges) preserved; older descriptive counts are historical, not current topology. MPC-1 in D06E.1 and D06E.4 JSON covers 53 registered entries without runtime admission. B5.5 NEXT; coverage before bulk/pattern/report publication; C/W/E lanes retain own gates, D08 OPEN, Radar/production HOLD. Only design branch updated.

## Final technical reconciliation — 2026-11-26
DI-05 atomic operation inventory proposal, no new family: definition list/get, instance request, job status, list/search/get metadata, preview, download XLSX/PDF, audience grant/revoke/list, regenerate, retention expire/delete, authorized detail/latest query, cancel own admitted job. Each records actor/tenant/scope/permission, state preconditions, idempotency/revision, response/error, audit, negative ACL/test evidence. EVT/tickets expand in existing families per slice; no claim of complete TCRUD.
