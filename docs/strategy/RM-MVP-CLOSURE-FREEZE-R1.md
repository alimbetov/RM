# RM-MVP-CLOSURE-FREEZE-R1 — завершение функциональности и выпуск первого MVP

**Дата решения:** 2026-11-23. **Ветка:** `base_dev`. **Исходный HEAD при формировании:** `422ba55eaff6fb36a4fda6465bc91f54e0a8370f`.  
**Статус:** **ACTIVE MVP SCOPE DECISION / ENGINEERING FREEZE POLICY / NOT IMPLEMENTATION PASS**. **Product:** один MVP RM с двумя сценариями: **RM Business** и **RM QR Commerce**.  
**Normative:** `docs/architecture/RM-PLATFORM-STANDARD-R2.md` и утверждённые IAM/SALES/INVENTORY/QR state/consistency/security решения не ослабляются.  
**Change control:** только `base_dev` до специального выпуска; `main` не трогать. Все производственные активации, реальные клиенты и security/legal approval — отдельные явные gates.

## 0. Решение о прекращении расширения scope

**С этого решения прекращаем добавление функций «на всякий случай».** Цель — добиться работающего пользовательского результата на существующей архитектуре и провести реальный пилот, а не достраивать условную ERP до 100%.

MVP = **один продавец/tenant**, реальные сотрудники, простая CRM, SKU/цена/остаток, один заказ от одного продавца, подтверждение и резерв на одном выбранном складе, выдача, история, базовые задачи; плюс **публичный QR опубликованного товара**, покупка по цене или простой торг, защищённая выдача и оформленный возврат. Тот же `SALES` и `INVENTORY` используются в обоих продуктах.

**Правило:** новая функция разрешена до MVP только если без неё нельзя безопасно завершить **один из двух описанных пользовательских путей**, пройти обязательную безопасность/регрессию или начать пилот. Желание улучшить архитектурную полноту, количество тестов, UI или коммерческие возможности само по себе недостаточно.

**Немедленно PAUSE:** R3.2B B1–B4 SLA Policy/Notification Outbox Foundation и R3.2C external delivery; полная operational notification automation, CRM notes/KMS расширения, продвинутая FX/курсами автоматизация и auto-repricing, packaging consumables, analytics/ML, universal reports, delivery/shipping, online payment/refund, multi-seller checkout, multiple warehouses per order, complex group/holding network, native Flutter/desktop app. Не удалять уже реализованные классы и миграции; их текущие обязательные regression tests всё равно должны проходить.

**R3.2A / R32B-T00:** ограничить доказательным закрытием уже введённых canonical CRM cancellation event, snapshot/immutability and PG CI. Поскольку T00-сервисы и mandatory CI уже вошли в HEAD, безопасно завершить/исправить их в текущем объёме; **не переходить к B1**, не создавать новые SLA таблицы или notification dispatcher. Все новые изменения — только минимальные P0 corrections.

## 1. Два обязательных пользовательских пути, каждый выполняется без ручного SQL

### BUS-MVP-J1 — RM Business

1. Владелец проходит реальный trusted login/verification и создаёт/выбирает свою активную организацию; назначает роли сотрудникам.
2. В CRM создаёт клиента и контакт; создаёт типовой follow-up TASK, назначает его активному сотруднику, сотрудник завершает задачу; без календарного SLA или уведомлений.
3. Создаёт не менее одного опубликованного PRODUCT SKU и цену **в KZT**, один собственный storefront/offer.
4. Оформляет заказ одного продавца, в том числе из продавцового кабинета существующим AutoOrder route; цены и SKU фиксируются immutable snapshot.
5. Сотрудник с разрешениями подтверждает заказ и склад; INVENTORY атомарно резервирует полные количества; `PLACED` не резервирует.
6. Склад комплектует и выполняет разрешённое исполнение согласно `DEC-FUL-01`; stock movement ровно один, статус и история читаются в рабочем кабинете.
7. Может отменить допустимый ещё не выданный заказ с корректным освобождением резервов; после физического PICK применяются утверждённые restrictions, без молчаливого обнуления pick evidence.
8. У сотрудника нет доступа к чужому tenant, недействительному складу и чужим персональным данным. Локальные роли не подменяют глобальную IAM identity.

**Основной результат:** человек может управлять клиентом, задачей, товаром, остатком и заказом от начала до конца.

### QR-MVP-J1 — RM QR Commerce

1. Продавец публикует один KZT offer в уже существующей STOREFRONT и получает стабильную `PRODUCT_OFFER` QR-ссылку.
2. Покупатель открывает публичную карточку на обычном смартфоне/в браузере без авторизации: только разрешённые опубликованные данные и безопасные фото, никаких PII.
3. Выбирает **купить по цене** либо **сделать предложение**; продавец принимает/отклоняет или отвечает встречной ценой в рамках существующего NEGOTIATION contract (без новой сложной pricing policy).
4. Покупатель входит через существующий verified identity flow перед приватным оформлением; один `AutoOrderService` создаёт один заказ и immutable accepted amount. Подтверждение покупателя защищает от stale price/version.
5. Продавец подтверждает заказ, INVENTORY резервирует; сотрудник собирает заказ и переводит в `READY_FOR_PICKUP`.
6. Покупатель показывает одноразовый `PICKUP_CREDENTIAL`; сотрудник подтверждает физическую выдачу через минимальный мобильный браузер/камеру или ручной ввод с тем же backend admission. **Профессиональное сканирующее оборудование не требуется для первого пилота**.
7. Покупатель инициирует возврат; продавец рассматривает, сотрудник принимает и инспектирует, INVENTORY получает ровно одно отражение `RESTOCK / QUARANTINE / WRITE_OFF`. **Возврат денег не моделируется и не обещается как функция MVP**.
8. Отзыв QR, изменение цены, конкурентное сканирование или потерянный ответ не создают второй заказ, второй debit или доступ к чужому заказу.

**Основной результат:** реальный покупатель сканирует QR, заказывает/торгуется, получает товар и оформляет возврат в одном приложении.

## 2. В MVP входит / не входит

| Область | MVP IN | POST-MVP |
|---|---|---|
| IAM/ORG | реальный OIDC/JWKS вход, employee/tenant scope, основные роли | расширенный enterprise IAM governance и SSO federation |
| CRM | клиент, контакт, простая история/договор при необходимости заказа | campaigns, сложная сегментация и текстовые confidential notes |
| TASKS | существующие create/assign/reschedule/start/complete/cancel/queue и CRM follow-up | R3.2B SLA/outbox, R3.2C notifications, reminders, automation |
| Catalog | PRODUCT SKU + базовая категория/цена, фото, публикация | массовые продвинутые импорты и расширенный search |
| Inventory | receipt, onHand, reserve, release, fulfill, односкладская выдача и базовая история | расширенный stocktake/transfer UI, split shipment и планирование |
| SALES | single seller order, immutable price, confirm/cancel/prepare/fulfill | invoice, online payment, tax accounting, delivery and settlement |
| QR | public card, list/quote checkout, pickup proof, return | native scanners, offline mode, QR packaging material costing |
| FX/CUR | KZ public commerce **KZT-only** с server-side проверкой при публичном admission; ручное изменение продавцом KZT цены | NBRK-driven seller FX suggestions/approvals, AUTO FX, international retail currencies |
| Client | **один responsive web/PWA**, три роли (seller/warehouse/buyer), минимальные verified service adapters | отдельные native Flutter/mobile/desktop приложения |
| Operations | postgres backup/restore, error logs, simple runbook, essential rate/health checks | advanced observability, multi-region, autoscaling |

**Важная оговорка CUR:** если действующая `rm.currency.commerce-admission.enabled=false` не гарантирует KZT-only в новых публичных путях, публичный MVP **не активировать**, пока server-side policy и квалификация исторических опубликованных предложений не закрыты. Не запускать AUTO FX вместо устранения admission gap.

**Оплата:** «заказ принят» и `SALES.COMPLETED` означают исполнение выдачи, а **не** оплату. Оплата вне платформы/при получении — организационный процесс пилота, без фиктивного статуса PAID, списания денег или автоматического refund. Ясно указывать ограничение в интерфейсе и условиях пилота.

## 3. Минимальные интерфейсы, ни одной лишней подсистемы

Только после backend/security gate разрешаются **узкие REST adapters** к действующим verified service facades; не писать новые бизнес-сервисы ради удобства UI. Предлагаемая реализация — один `React + TypeScript` responsive web client/PWA для продавца, склада и покупателя (продуктовое решение для пилота, не уже существующий клиент). Предусмотреть:

| Surface | Минимальный экран / операция |
|---|---|
| Login / tenant | OIDC session, организация, tenant switch только с правами, logout |
| CRM + TASKS | список клиентов, создать/открыть, простая задача, назначить/выполнить |
| Catalogue & stock | SKU/offer/price + приёмка и текущий остаток |
| Seller orders | входящие заказы, принятый offer, confirm/cancel/status |
| Warehouse | pick, READY, credential check, handover, receive/inspect return |
| Public product | QR URL → безопасная публичная карточка, купить/предложить цену |
| Buyer private | авторизованный checkout, статус собственного заказа, pickup code, запрос возврата |

Это **7 экранных групп**, не новый дизайн-проект или dashboard. Не добавлять навигационные конструкторы, универсальный spreadsheet UI, внутренний чат, отчётность и тяжёлые кастомные компоненты до первой UAT-сессии. Использовать UI-компоненты с существующими accessibility и mobile-first решениями, без самостоятельного дизайн-фреймворка.

**Existing HTTP controllers do not imply OIDC admission:** `CommercialAuthenticatedReadsController` и `SalesAuthenticatedCommandController` условно существуют; публикация любых маршрутов возможна только после реального security review, permission/object-scope tests и OIDC session correctness. Не расширять контроллеры до закрытия G1.

## 4. Реалистичный маршрут завершения — четыре gates вместо бесконечного roadmap

| Gate | Этап | Что сдаём | P0 proof |
|---|---|---|---|
| **G0** | Freeze | фиксируем этот scope, один backlog, frozen candidate SHA; классификация открытых задач MUST_FIX / POST_MVP | ни одной новой функции вне J1; актуальные tests и документация сопоставлены |
| **G1** | Backend closure | TASK-R3.2A/T00 evidence, full-domain regression и FC-40/50 safety contracts; не пишем R3.2B B1 | все обязательные H2/PG/Security/Populated tests и exact-SHA PASS на одном release SHA, zero P0 defects; independent source-of-truth SQL checks |
| **G2** | Secure usable slice | real OIDC + minimal trusted REST + один responsive web client, **оба** пути BUS-MVP-J1/QR-MVP-J1 до end state | negative auth/tenant/PII, idempotent retries, no dangerous cross-domain effects, manual real-browser UAT |
| **G3** | Real pilot | одна пилотная организация, реальные сотрудники и смартфоны, в том числе отмены, pickup double-scan и возврат | signed UAT scripts, real DB backups/restore drill, operator runbook, live identity and endpoint tests, fixed known issues |
| **G4** | Release admission | security/legal/operations, privacy, rollback, monitoring, capacity qualified for actual agreed pilot load | explicit release decision; **no automatic production promotion from prior PASS** |

**MVP PILOT READY** после G3 (в контролируемом пилотном окружении). **MVP RELEASE READY** после G4. **SERVICE FUNCTIONAL PASS** после G1 — не обещает пользовательский MVP. Real OIDC/UI/phones становятся обязательными после G1; ранее действовавший `CONTROLLERS/UI STOP` превращается в **stage gate**, а не вечный запрет.

### MVP acceptance fixtures (не throughput benchmark)

Для пилота минимум: 1 организация, 1 owner, 1 seller staff, 1 warehouse worker, 1 buyer; 10 SKU, 2 locations для проверки чужого warehouse rejection, минимум 20 сквозных заказов (включая часть через QR+negotiation), 10 независимых pickup proof, 3 return dispositions, 2 повторных клиентских запроса и 2 конкурентных scan/stock сценария, 1 полноценно восстановленная PostgreSQL backup snapshot. Числа — **UAT checks**, не обещание p95, RPS или доступности. Полноценный scale SLA будет согласован отдельно.

## 5. Классификация задач и запрет scope creep

**MUST_FIX (P0):** inter-tenant data/PII leak; incorrect IAM/revocation; double stock debit/oversell; broken order/quote price; direct fulfil bypass of proof-required pickup; incorrect return posting; non-idempotent duplicate request; stale/invalid public currency admissions; migration checksum/data corruption; mandatory CI fail or missing critical artifact; credible attacker ability to use public QR as private credential; inability to complete either defined J1 end-to-end.

**MVP_BLOCKER (P1):** user cannot finish one of the seven screens' vital actions, session renewal/login breaks checkout, seller cannot reliably see incoming order, warehouse cannot complete proof in real mobile browser, empty/500 screens block pilot. Fix before G3.

**POST_MVP (P2+):** new TASK SLA/outbox/email automation, FX forecasting, richer catalog UX, statistical dashboards, partial pickup, multiwarehouse order orchestration, advanced reports, optional QR08/R1 packing stock, performance features not needed for agreed pilot, pure architecture refactors. Document and defer; no PR scope enlargement.

**Change request rule:** require written `CR-MVP-ID`, exact broken J1 step or P0 invariant, existing method/source, minimal patch, negative test and release SHA. If it cannot be connected to J1 or release safety, **REJECT/DEFER**. Existing jobs and migration safety cannot be skipped simply because feature is deferred; source-included mandatory tests remain required.

## 6. Test strategy — targeted proof rather than adding hundreds of cases

`docs/spec/RM-CORE-FUNCTIONAL-CLOSURE-R1.md` has 54 high-risk `CORE-P0` requirements and a 120-case extended planning target. **MVP release selection does not waive applicable P0 security and stock invariants.** Map each of 54 to `REQUIRED_FOR_MVP` (must PASS), `ALREADY_COVERED_BY_EXISTING_TEST` (link exact method) or `OUT_OF_SCOPE` (business functionality not exposed and no cross-path safety impact), with reason and reviewer approval. No arbitrary green by dropping mandatory CI jobs or skipping common regression.

At minimum validate under real PostgreSQL: two tenants, two locations, seller/buyer roles, CRM+TASK, SKU/current price/quote, exact one order, all-or-nothing reservation, pick/no early debit, generic fulfill-proof fence, one credential one handover, return restock/quarantine/write-off, stale price, quote-idempotency, IAM/scope revoke × checkout/fulfill, crash/retry, populated Liquibase upgrade. Then live browser+OIDC and normal-phone QR tests in G2/G3.

One final `G1` SHA gets independent mandatory **TASKS + T00 + H2 + PG + security + populated upgrade + RustFS** results, Surefire XML, digest/manifest and zero unexpected skips. Previous SHA PASS is historical evidence only; do not repeatedly commit while the candidate is qualifying, except to repair verified MUST_FIX.

## 7. Exit: definite MVP state and what happens next

**Done means** BUS-MVP-J1 and QR-MVP-J1 can be completed by distinct real users on mobile/desktop web without SQL fixtures or developer console, with correct order/stock/source evidence and return process. If a company can use both paths and G3 pilot has a signed verdict, stop feature work and prioritize bug repair and feedback triage.

**Immediately after scope freeze:** finish existing R3.2A/T00 qualification (not B1/B2/B3/B4), select immutable RC candidate and finish G1 → implement minimum auth/API/UI G2 → pilot G3 → release G4. SLA/notification/R3.2C, sophisticated currency logic and optional enhancements are **separate post-MVP backlog**, not reasons to postpone first product.

**Non-negotiable:** no false "production-ready", no paid/financial accounting claims, no weakening tenant/privacy/security, no second order/stock engine, no new branch or migration rewrite. First real customer workflow beats another broad design document.
