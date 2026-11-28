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
