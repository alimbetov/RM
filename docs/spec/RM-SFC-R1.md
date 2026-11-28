# RM-SFC-R1 — Scenario-driven Functional Completion & Documentation Alignment

**Дата:** 2026-11-28. **Branch:** base_dev. **Reviewed source:** `17324dfd5a5a1fe32dd60f9ec60806cec3e4616c`.  
**Status:** DOCUMENTATION ALIGNMENT / INITIAL STRUCTURED INDEX / SEMANTIC REVIEW OPEN / PRODUCTION HOLD.

## 1. Цель и приоритет нормативов
Полная реализация всех утверждённых пользовательских сценариев RM с согласованными сервисными контрактами, данными, полномочиями, транзакциями и квалификацией. Раздельно оценивать полный roadmap, текущий MVP и qualification. Не расширять MVP молча.

Приоритет: [Platform Standard R2](../architecture/RM-PLATFORM-STANDARD-R2.md) → [MVP Freeze и явные CR](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) → утверждённые domain DEC/spec → действующие BSR/IE/Reporting/FX/Radar планы → SFC как индекс. При конфликте сохранять строгую безопасность, записывать DECISION_REQUIRED. Не менять исторические CI-input JSON для косметического GREEN.

## 2. Управляющие реестры
- [Master Registry](RM-USER-SCENARIO-MASTER-REGISTRY-v1.json): 383 записей из 7 структурированных источников. Это не число уникальных пользовательских сценариев; BSR совпадающие IDs связаны, межсемейные aliases и варианты ещё проверить.
- [Service Capability Matrix](RM-SERVICE-CAPABILITY-MATRIX-v1.json): 321 Java-файлов, 102 Service-suffix классов, 147 тестовых файлов. Source presence не доказательство поведения.
- [Gap Register](RM-FUNCTIONAL-GAP-REGISTER-v1.json): 12 начальных пунктов сверки, не доказанные дефекты.
- Evidence сохраняется в существующих BSR, R3, UR, IE, Exact-SHA, PG Surefire и real-infrastructure артефактах. Не создавать второй release gate.

R3/R4 75/46/29, CRM 99, CRM-U 12, CORE 54+2, BSR 64, B5 112 — разные исторические знаменатели. TASKS source реализован в части функций, но старый PLANNED не меняется без проверки точных требований и evidence. CRM-U08 требует проверенной связи. FX_AUTO PARKED описывает scope, а не отсутствие кода. Документальный статус, реализация и admission различаются.

## 3. Контракт сценария и модель статусов
Идентичность: source namespace + ID + variant; ссылка на исходную версию. Для каждого сценария: actor, tenant, grants, target owner, предусловия, inputs, expected decision/state delta, запрещённые side effects; для каждого шага — owner aggregate, Java class/method/DTO, tables/constraints, transaction, idempotency key, event/receipt, independent oracle, test suite/method, artifact/run/job, actual SHA/result.

Независимые оси:
- Scope: CORE_MVP / APPROVED_EXTENSION / POST_MVP / DECISION_REQUIRED.
- Contract: INVENTORIED / REVIEWED / APPROVED / CONFLICT.
- Implementation: MISSING / PARTIAL / IMPLEMENTED_REVIEWED.
- Evidence: NOT_RUN / SUPPORTING_PARTIAL / PASS_AT_SHA / FAIL / BLOCKED / justified N/A.
- Admission: HOLD / SERVICE_QUALIFIED / G2 external / G3 pilot / G4 production по действующим gates.

Все применимые positive/negative/concurrency/recovery варианты обязательны. QUALIFIED только по реальному exact-SHA evidence; наличие теста, старый PASS или doc-only commit не переносит допуск.

## 4. План без дублирования существующих работ
| Этап | Результат |
|---|---|
| SFC-R1.0 | Полный JSON/Markdown inventory, aliases, scope, противоречия |
| SFC-R1.1 | Method-level capability mapping, роли U0..U12, permissions, persistence |
| SFC-R1.2 | Diff-first gap analysis: missing behavior vs evidence vs stale docs vs decision |
| SFC-R1.3 | Минимальное исправление подтверждённых разрывов в разрешённом scope |
| SFC-R1.4 | Horizontal integration, owner boundaries and durable recovery |
| SFC-R1.5 | Independent mathematical qualification: distinct, currency, period, completeness, no false zero |
| SFC-R1.6 | Tenant isolation, IAM/PII, concurrent/multi-pod and performance |
| SFC-R1.7 | Executable PostgreSQL user journeys and G1–G4 as applicable |
| SFC-R1.8 | Backend functional freeze for accepted scope, stable API contracts |

Использовать существующий [12-pass protocol](RM-SERVICE-12-PASS-REVIEW-PROTOCOL-R1.md) и BSR. Пакеты P1 IAM/KMS/ORG/GEO/Audit; P2 CRM/Catalog/Files/Tasks; P3 Sales/Inventory/Storefront/QR; P4 FX/Intelligence/Radar/Reporting; P5 approved EVT-01; P6 Finance/Marketing/Subscription. Это группировка для сверки, не новый release gate и не замена IE DAG. P6 не входит автоматически в текущий MVP.

Сохранить текущий приоритет **P4.1A.5-Q → P4.2 после GREEN**. Scheduler P1–P8, Radar R0.1, exact-SHA и mandatory regression остаются обязательными в применимом scope. Не активировать FX_AUTO, внешние рассылки, TASK SLA, Radar R0.2+, ML или P6 без применимого CR. PostgreSQL Outbox first; Redis deferred. Не менять main, applied migrations, gates или создавать ветки.

## 5. Definition of Done
100% утверждённых сценариев выбранного scope имеют reviewed contract; 100% обязательных вариантов реализованы и квалифицированы; 0 P0/обязательных gaps и unauthorized side effects; independent mathematical oracle где применимо; existing Exact-SHA GREEN. Отдельно coverage=implemented/approved и qualification=qualified/approved. Неопределённый знаменатель = NOT_MEASURED. Не использовать экспертные баллы вместо исполнения.

## 6. Граница обновления и следующий шаг
Первичный индекс структурированных источников создан. Очередь остальных Markdown/JSON документов (118) не означает их полную семантическую проверку. Другие design branches, method bodies, actual CI artifacts и external integrations не исчерпывающе проверены. Следующий шаг SFC-R1.0 semantic reconciliation; затем SFC-R1.1 method mapping. Ни одного нового PASS, изменений бизнес-кода, CI policy или production admission.


## Reporting lifecycle alignment (2026-11-28)
See [RM-REPORTING-RADAR-EVT-INTEGRATION-R1.md](RM-REPORTING-RADAR-EVT-INTEGRATION-R1.md). Preserve P4.1A.5-Q → P4.2; P4.1B is a planning alias only. RP-J design IDs require existing scenario mapping, no registry denominator or qualification changes.


## SFC-R1.0B.1 — fixed-SHA inventory and initial semantic reconciliation
Snapshot 0cae504294a810363dd8396bd164d314b8c51c77: 298 tracked Markdown/JSON documents under docs/, 12 selected contract reviews, 286 pending semantic review. [Document inventory](RM-SFC-DOCUMENT-INVENTORY-v1.json) binds each path to blob SHA; [normative conflict register](RM-SFC-NORMATIVE-CONFLICT-REGISTER-v1.json) records six findings. The old 118-document queue is a historical subset; 383 scenario records are not a unique requirements denominator. No claim of exhaustive semantic extraction. Preserve P4.1A.5-Q → P4.2 after actual GREEN; Radar F2H deferred until measured need and explicit CR; no runtime/CI changes. Next: SFC-R1.0B.2 section-level requirement extraction, aliases and scope crosswalk, prioritizing P4 without delaying it.


## SFC-R1.0B.2A — initial requirements extraction and scope crosswalk
[Requirements crosswalk](RM-SFC-REQUIREMENTS-CROSSWALK-v1.json) on 1eb27b97cbdd00ccfe5751ea48b9bba3b996bfa5 extracts 35 grouped requirements from selected normative sections, preserves all 383 source scenario records, proposes related links for 76 records; 307 unmapped. No alias equivalence or unique requirement denominator claimed. Scope classifications apply to extracted groups only, not entire related scenarios. Reporting P4.1A.5-Q → P4.2 after GREEN; reporting CR trace remains open, no new scope. F2H deferred. Next B.2B: section-level extraction across remaining documents and semantic alias/variant adjudication; no gate change.


## SFC-R1.0B.2B.1 — source obligations, semantic identity and P4 section extraction
[Atomic requirements register](RM-SFC-ATOMIC-REQUIREMENTS-v1.json), reviewed a464b0694dd471345f873b0cfcb9a31a238b3833: 85 obligation records (56 CORE/TASK source scenarios and 29 selected MIC/Radar/Reporting section obligations), 36 explicit same-source identity matches, 112 B5 source variants with expected behavior and missing evidence preserved. No cross-family equivalence or new unique denominator. Mandatory variant and method qualification remain OPEN. Reporting P4.1A.5-Q → P4.2 after GREEN; F2H deferred. Next: CRM/R3/DUAL/BSR overlay semantic extraction and variant adjudication. No runtime, migration, CI or admission change.


## SFC-R1.0B.2B.2 — CRM/IAM/ORG journey crosswalk
[CRM journey crosswalk](RM-SFC-CRM-JOURNEY-CROSSWALK-v1.json), reviewed dd65bb44c6fd27d09083555e132fd484be25511a: 99 source simulations, 12 journeys, 45 related R3 requirements, 11 operation groups, 23 explicit journey-to-R3 source references and six reconciliation findings. Candidate variants and semantic links need owner method/SQL review; zero merged IDs or new PASS. Preserve scope and regression, Reporting priority, Radar F2H deferred. Next B.2B.3 commerce/inventory/QR and remaining domain sections. No runtime/CI change.


## SFC-R1.0B.2B.3 — commercial semantic crosswalk
[Commerce crosswalk](RM-SFC-COMMERCE-CROSSWALK-v1.json), reviewed f784f6f59a2b9adbc62b2fc33e8322d6a1f0b3e1: {"qrRules":50,"coreScenarios":37,"r3":26,"dual":12,"sectionObligations":9,"findings":6,"variantFamilies":8}. Preserve draft status, explicit owner scope, physical versus financial effects, one order engine and canonical fact identity. Candidate links not equivalence; no new PASS. Reporting P4.1A.5-Q → P4.2 after GREEN, F2H deferred. Next B.2B.4 FX/Intelligence/EVT/Scheduler. No runtime or CI change.


## SFC-R1.0B.2B.4 — FX / Intelligence / EVT / Scheduler semantic and mathematical crosswalk
[Crosswalk](RM-SFC-ANALYTICS-FX-EVT-CROSSWALK-v1.json), reviewed 9fa5bd9b34ac4b2900527b0fc48fbdf7c3c6cd8d: {"fxRequirements":24,"fxVariants":80,"fxSourceLinks":108,"schedulerPackages":8,"sectionObligations":26,"findings":8}. FX 24 requirements and 80 proposed cases preserve declared trace links; scheduler P1–P8 existing acceptance not modified; selected sections of MIC-R2/B4/B5/EVT/Reporting extracted. No new qualification, runtime, scope or CI change. Reporting P4.1A.5-Q → P4.2 after GREEN; F2H deferred. Next B.2B.5 remaining domains and canonical closure; full semantic document coverage still open.


## Reporting FC-R1.0A (2026-11-28)
[Report inventory and source audit](RM-REPORTING-FC-R1.0.md), [registry](RM-REPORTING-FC-REGISTER-v1.json): all 383 index records and 85 atoms screened, with commerce and FX/Scheduler/EVT crosswalk candidates; 12 design templates, 9 bounded bindings, 3 gated diagnostic files, 12 operational candidates. No new denominator, approval, N/A or PASS. P4.1A.5-Q → P4.2 after GREEN; C02/C06 OPEN; controllers STOP until service freeze. Next FC-R1.0B semantic scope/artifact adjudication.
