# RM-TENANT-CRUD-COMPLETION-R1 — аудит и адресное завершение tenant-isolated сервисов

**Тип:** техническое задание / gap-based implementation backlog  
**Статус:** REVIEWED SPEC / implementation NOT STARTED by this document; не означает GREEN или production admission  
**Репозиторий:** `alimbetov/RM`; **единственная рабочая ветка:** `base_dev`  
**База сверки:** `base_dev` HEAD `343805f3f7f48a226f83e00c025c987b4a4ff345` (2026-11-22); Java application facades и сервисы, существующие ТЗ/аудиты. После любого изменения HEAD пересчитать фактическую матрицу.  
**Режим:** Feature Freeze для transport; никаких REST-контроллеров, публичного HTTP/OIDC допуска, новых веток, merge в `main` или production admission в рамках данного ТЗ.

## 0. Место документа и исходные источники

Этот документ **не заменяет** `docs/architecture/RM-PLATFORM-STANDARD-R2.md` и не объявляет реализацию нового функционала. Он уточняет практический backlog поверх существующих:
- `docs/spec/RM-SVC-R2-CONSOLIDATED-IMPLEMENTATION-PLAN.md` — hybrid JPA/JDBC, tenant boundary;
- `docs/spec/RM-SVC-R2-SERVICE-COMPLETION-CROSS-DOMAIN-CONSISTENCY.md` — междоменные инварианты;
- `docs/spec/RM-SVC-R2A-FILTERING-PAGINATION-CONTRACT.md` — фильтрация и keyset/cursor;
- `docs/spec/RM-CRM-PERSIST-R1.2-CATEGORIES-CONTRACTS.md`, `RM-CRM-R1.3-CUSTOMER-CONTRACT-CRUD.md`, `RM-CRM-R1.4-CONTACT-CRUD-LIFECYCLE.md` — действующие CRM-сервисы и lifecycle;
- `docs/reviews/RM-SVC-R2-TENANT-ISOLATION-SEARCH-AUDIT-2026-11-22.md` — CONDITIONAL PASS на существующую выборку, не full E2E;
- `docs/reviews/RM-FINAL-SEC-GATE-EXECUTION-2026-11-21.md` — остаточные security/release gates.

При конфликте: утверждённый R2 > утверждённые доменные решения > это gap-ТЗ. Старые snapshot-описания «не реализовано» нельзя переносить в новое ТЗ без повторной проверки кода.

## 1. Цель и границы

Цель: у каждой организации — отдельные бизнес-данные и работающие *разрешённые* процессы; владелец организации управляет членством/ролями и может организовать использование функций, не обходя полномочия, PII и object policy.

Идентификаторы: `tenantId = public.iam_workspace.id`; это **не** `organizationId`, `userId`, `customerId`, `sellerId` или KATO. Один глобальный пользователь может быть активным членом нескольких tenant, но каждая конкретная команда/выборка работает в **одном явно выбранном** tenant.

Это **не** задача «добавить одинаковые CRUD-методы всем таблицам». Для изменяемого справочника нужны create/get/search/update/archive; для договора — create/activate/renew/close и revisions; для остатков — команды движения и резерва, но не произвольный update/delete; для файлов — staging/scan/attach/read/detach/reclaim; для согласий и audit — append-only.

Не включать в эту работу полноценные FINANCE/MARKETING/TASKS/SUBSCRIPTION workflows, settlement, публичный storefront, новые пользовательские контроллеры, generic CRUD framework, Redis, отдельный policy-engine, тотальный перевод JDBC в JPA.

## 2. Оценка предыдущего ТЗ и исправления

1. Ранее «FILES отсутствует» было неверным для текущей `base_dev`: существуют `FileMetadataService`, `FileUploadCompletionService`, `FileAttachmentService`, authenticated FILES facades и backend LOCAL/RustFS. Это **частичная сервисная реализация**, не универсальный media CRUD и не production approval.
2. CRM Customer/Contact имеют `update/archive/restore` после R1.3/R1.4; Category имеет `create/rename/archive/get/list/assignPrimary`; Contract имеет `create/changeDates/changeStatus/renew/get/list` и immutable revisions. Не реализовывать их заново.
3. Исторический `R2B` и ранний аудиторский отчёт могут помечать category/contracts как TARGET; для статуса использовать свежие Java-классы и точный SHA.
4. Предыдущее предложение «OWNER получает базовые grants автоматически» **не согласовано** с R2: OWNER не имеет wildcard; `TenantRoleService.assign` запрещает self-assignment. Нужна отдельная продуктовая развилка, см. §5.
5. CRUD-матрица должна отражать действия **application service**, а не существование Entity/DTO/таблицы.
6. Отдельно различать: `PRESENT` (метод реально есть), `TESTED` (тест привязан к SHA и запущен), `QUALIFIED` (негативные/конкурентные PG E2E), `NOT IMPLEMENTED` и `DECISION REQUIRED`. Здесь `PRESENT` — наличие исходников, а не результат запуска CI.

## 3. Фактическая матрица внутренней функциональности на базовом SHA

Обозначения: `✓` — подтверждён исходный код; `част.` — имеются отдельные операции, но нет полного предметного lifecycle; `—` — соответствующий сервисный контракт не подтверждён; `n/a` — физическое изменение/удаление запрещено предметной моделью. **Статус CI не присваивается этой таблицей.**

| Домен / агрегат | Create | Read / Search | Update / Transition | Archive / Delete | Конкретное состояние / пробел |
|---|---|---|---|---|---|
| IAM global User | част. | ✓ | част. | status lifecycle | Глобальная сущность, не tenant-owned; onboarding/recovery отдельно |
| IAM Workspace / Membership | ✓ | ✓ | status/roles | deactivate lifecycle | Bootstrap, enrollment, governance; не универсальный CRUD |
| IAM Tenant Role | ✓ | ✓ | grant/revoke/activate/assign | deactivate | `TenantRoleService`; self-assignment запрещён |
| ORGANIZATION Location | ✓ | ✓ | — | — | `AuthenticatedOrganizationLocations`: create/get/search; нет edit/close |
| CRM Customer | ✓ | ✓ | ✓ | archive/restore | `AuthenticatedCrmCommands`; PERSON/COMPANY, `@Version`/CAS, encrypted PII |
| CRM Contact | ✓ | ✓ | ✓ | archive/restore | R1.4 с AAD re-encryption и audit |
| CRM Category | ✓ | ✓ | rename/assign | archive | Restore не установлен; одна primary category |
| CRM Contract | ✓ | ✓ | dates/status/renew | terminal states | DRAFT/ACTIVE/CLOSED/CANCELLED, immutable revisions; не generic archive |
| CRM Consent | append | latest/check | n/a | n/a | Keyed append-once есть; историческое согласие не переписывать |
| CRM History | append | ✓ | n/a | n/a | События/ревизии append-only; частичный historical coverage |
| SALES Order | ✓ | част. | confirm/cancel/fulfill | n/a | `AutoOrderService` checkout и SALES transactions; полноценный staff draft/detail/history отдельно |
| SALES Order Line | част. | част. | state-dependent | n/a | Snapshot/terminal state, не свободный CRUD |
| INVENTORY Balance | derived | ✓ | domain commands | n/a | receive/adjust/reserve/release/fulfill; прямой CRUD запрещён |
| INVENTORY Movement | append | част. | n/a | n/a | Неизменяемая история, transfer/stocktake отдельно |
| FILES Object / Attachment | ✓ | част. | stage/scan/attach/detach | част. | Рабочие внутренние сервисы; owner access сегодня ограничен утверждёнными типами, в т.ч. COMPANY |
| CATALOG SKU / Price | schema | — | — | — | Наличие `cat_item`/`cat_price` не означает functional CRUD |
| FINANCE / TASKS / MARKETING / SUBSCRIPTION | — | — | — | — | Отдельные вертикали, не притворяться реализованными |

**Правило:** отсутствие `restoreCategory`, `deleteContract` или `updateMovement` не автоматически дефект. Сначала подтвердить реальный пользовательский сценарий и допустимый lifecycle.

## 4. Обязательные invariant и security contract

- **TI-01 — Trusted actor.** Внешняя граница когда-либо получает `VerifiedActor` только из проверенного issuer/audience/signature/expiry/subject. Ни один бизнес-фасад не принимает caller-supplied actor UUID как доказательство полномочий. Legacy UUID-методы — только внутренние/package-private.
- **TI-02 — Selected tenant.** Actor выбирает tenant явно, membership/activity проверяются в IAM; membership в A и B не даёт права смешивать их в одном запросе. При смене tenant нет унаследованного filter/cursor/role cache другого tenant.
- **TI-03 — Query predicate.** Для root `tenant_id = :tenant`; для каждого tenant-owned JOIN `(parent.tenant_id=child.tenant_id=:tenant)`, либо эквивалентная доказуемая связь; composite FK/unique `(tenant_id,id/code)` там, где это возможно. `organization_location.workspace_id` сопоставляется с tenant.
- **TI-04 — Object policy.** Доменные owner service проверяют статус и объектные права **после** проверки tenant. Сам `CRM_READ` не означает PII_READ и не гарантирует portfolio/assignee policy. Не вводить per-customer или per-warehouse ACL без решения продукта.
- **TI-05 — Zero leakage.** Cross-tenant ID в get/search/filter, sort, count, page, cursor, attachment, history или error не раскрывает чужие данные, идентификаторы, PII, storage key или бинарные bytes. Неприметные `NOT_FOUND` и `FORBIDDEN` допустимы по договорённой политике без oracle на существование.
- **TI-06 — Transactions.** Все business mutations выполняются атомарно с авторизацией, CAS/rowVersion и audit/revision; PostgreSQL transaction-bound `TenantAuthorization.requireForCommand`, lock ordering совместим с revoke. В долгих storage/AV IO не удерживать database locks.
- **TI-07 — PII.** PERSON и Contact секреты хранятся и обновляются шифрованно, tenant+record+field AAD. Расшифрованное имя в **ответах create/update** требует отдельного `CRM_PII_READ` либо утверждённой безопасной masked-result policy; `CRM_WRITE` сам по себе этого права не даёт.
- **TI-08 — Immutable history.** Consent/audit/stock movement/contract revisions не имеют произвольного UPDATE/DELETE, даже при OWNER; исправления — новым подтверждённым событием или переходом.
- **TI-09 — Source-of-truth.** CRM владеет customer/contact, SALES — order, INVENTORY — reservations/balance, FILES — files/links, IAM — membership/grants. Междоменные изменения только через узкие ports; не создавать дубль CRM Customer в SALES и не приравнивать IAM buyerUserId к CRM customerId.
- **TI-10 — Migration.** Только новые Liquibase changesets; migration с заполненной БД и проверка старых ссылок/PII; не менять ранее применённый checksum. PostgreSQL на runtime role не должен иметь DDL и произвольного обхода защищённых схем.
- **TI-11 — Idempotency.** Для retryable side effects нужен request key + payload hash + результат; replay того же payload не дублирует события, другой payload отклоняется. Не требовать искусственный key от чистых read.
- **TI-12 — Read boundary.** Legacy `Page`/OFFSET допускается при bounded 1..100 и детерминированной сортировке; для нового cursor — подписанный tenant/actor-scope/filter/asOf контекст и повторная авторизация на **каждой** странице. `COUNT` + page — не консистентный export snapshot.

## 5. Решение OWNER: отдельная продуктовая развилка (P0-DECISION)

**Текущая утверждённая модель:** OWNER имеет только неделегируемый `TENANT_RBAC_MANAGE`; обычные бизнес permissions выдаются через tenant role, wildcard запрещён. `TenantRoleService.assign` отвергает назначение роли самому себе. `PlatformTenantBootstrapService.provision` создаёт workspace/OWNER, но не назначает автоматически бизнес-grants.

**Проблема:** единственный первый OWNER может создать роль, выдать ей permissions и назначить сотруднику, но не может самостоятельно назначить её себе. Нельзя обещать, что «владелец автоматически делает всё», без корректировки процесса.

| Вариант | Поведение | Ограничения |
|---|---|---|
| A. Сохранить текущую политику | OWNER администрирует; выполнение бизнес-операций — сотрудниками с явно назначенными ролями | OWNER не получает личного бизнес-доступа до назначения другим уполномоченным OWNER |
| B. **Рекомендуется к продуктовому решению:** управляемый initial owner grant | При *доверенном* tenant provisioning по отдельному явно санкционированному bootstrap-сценарию назначить первому OWNER заранее утверждённую функциональную роль с **конечным списком** permissions и immutable audit | Не wildcard; без автоматического PII/KMS/PLATFORM_ADMIN; требует утверждённой поправки к R2, тестов повтора/конкуренции и ownership recovery |
| C. Явный self-assignment OWNER | Специальная команда самоназначения с дополнительными safeguards, историей и revocation-инвариантами | Меняет существующее правило «no self-assignment»; НЕ внедрять до формального approval и полноценной security qualification |

**До принятия решения** не менять `TenantRoleService.assign`, не делать hardcoded `OWNER -> allowAll`, не выдавать CRM_PII_READ автоматически. В рамках Wave 0 разрешено проверить текущий onboarding end-to-end и оформить короткий ADR/изменение одного раздела нормативного R2 с выбранным вариантом. Права другого tenant и платёжные/KMS/PII полномочия отдельно не наследуются.

## 6. Приоритетный пул задач (не дублировать готовый код)

| ID | Pri | Работы и реальный результат | Зависимость / Gate |
|---|---|---|---|
| TCRUD-00 | P0 | Автоматизированный **method-level inventory**: facade → service → repo/SQL → permission → tenant predicate → object policy → tests; определить PRESENT/TESTED/QUALIFIED на HEAD | Первый этап; полный список методов всех актуальных доменов |
| TCRUD-01 | P0 | **OWNER onboarding decision** по §5; при approval — минимальная отдельная доработка, без wildcard | ADR и правка R2 прежде Java |
| TCRUD-02 | P0 | **CRM PII mutation-response policy**: createPerson/updatePerson/addContact/updateContact без plaintext при `CRM_WRITE` only; либо требовать PII_READ, либо safe DTO | Негативные tests + AAD, rollback, consent audit |
| TCRUD-03 | P0 | **Cross-tenant adversarial matrix** для каждого реализованного facade/get/list/update/archive/restore, JPA/JDBC, JOIN/count/history/files; добавить отсутствующие тесты, а не переписывать GREEN | Two-tenant PG fixtures, mixed-membership actor, permission revoke races |
| TCRUD-04 | P0 | **Public entrypoint boundary**: запрет новых exposed UUID mutators, проверка `VerifiedActor` и IAM revoke order в CRM/ORG/FILES и новых доменах | Architecture tests; без REST |
| TCRUD-05 | P1 | **CRM search completion**: category/contract status/expiry filters, tenant-safe join, typed criteria, bounded pagination; `count` совпадает по scope | Отдельно подтвердить семантику expiry и active/archived |
| TCRUD-06 | P1 | **CRM lifecycle QA** R1.3/R1.4: archive customer vs active contracts, restore parent/contact, stale revision, double writes, no plaintext audit | Не писать второй update/archive сервис |
| TCRUD-07 | P1 | **CRM Category & Contract QA**: archive category при assignment, renew history, concurrent customer archive/new contract, cross-tenant FK | Restore category и delete contract не добавлять без сценария |
| TCRUD-08 | P1 | **ORGANIZATION Location completion**: rename/address/KATO edit, deactivate/reactivate с блокировкой при active stock/orders/reservations | Продуктовое правило зависимостей; PG locks |
| TCRUD-09 | P1 | **SALES staff lifecycle**: разница buyer checkout vs tenant staff; draft/detail/history и связь CRM customer только по утверждённому контракту | Не смешивать buyer IAM id с CRM customerId |
| TCRUD-10 | P1 | **INVENTORY workflow completion**: history/stocktake/transfer, reconciliation по необходимости, owner/location scope decision | Без прямого balance CRUD или удаления movement |
| TCRUD-11 | P1 | **FILES typed owner policy**: customer COMPANY существующий контракт, новые PERSON/CATALOG/SALES adapters только по owner-specific allowlist; file-delete/recovery без orphan/cross-tenant leak | Учитывать LOCAL/RustFS и scan, pre-I/O denial |
| TCRUD-12 | P2 | **CATALOG minimum operational lifecycle**: item, translation, effective price, permission, search и archive | Пока QUERY NOT IMPLEMENTED по аудиту; отдельный маленький spec |
| TCRUD-13 | P2 | **OTHER domains** FINANCE/TASKS/MARKETING/SUBSCRIPTION — отдельная вертикаль с admission/consent/retention, не ложный CRUD | Только после явного scope и business owner |
| TCRUD-14 | P0 | **Final exact-HEAD evidence**: H2, PostgreSQL, populated DB migration, RBAC races, FILES live RustFS/ClamAV если затронут, независимый review | Без GREEN на точном SHA задача HOLD |

### Очерёдность

**Wave 0 (blocker):** TCRUD-00 → TCRUD-02/03/04; TCRUD-01 product decision параллельно, но его код только после approval.  
**Wave 1 (CRM quality):** TCRUD-05/06/07, максимальное повторное использование R1.2–R1.4.  
**Wave 2 (business):** TCRUD-08/09/10/11 — отдельные инкременты, без смешивания в одном большом changeset.  
**Wave 3 (new domains):** TCRUD-12/13 отдельными согласованными вертикалями.  
**Wave 4:** TCRUD-14 при каждом инкременте и повторно на final SHA.

Не считать весь P2 блоком до окончания CRM: приоритет каждого нового домена определяется реальным сценарием, а не процентом заполнения CRUD-таблицы.

## 7. Задачи в формате инженерных карточек

### TCRUD-00 — Method-level Inventory
**Inputs:** все `Authenticated*` facade, domain service, repository/JDBC, `DomainPermission`, Liquibase, tests.  
**Deliverable:** Markdown или CSV матрица: `domain | aggregate | operation | entrypoint | service | tenant predicate | permission | object policy | transaction | idempotency | audit | test class | code SHA | status | missing evidence`.  
**Acceptance:** ни один публично доступный application entrypoint не остался без строки; ложные «не реализовано» исключены; не считать Entity/Repository отдельной CRUD-функцией.

### TCRUD-02 — PII response
**Policy options:** (a) require `CRM_PII_READ` before returning plaintext from mutation, сохраняя отдельную возможность безопасной записи; (b) mutation returns sanitized identifiers/status without PII, detailed PII-get требует отдельного permission. Выбрать единый совместимый контракт и покрыть tests для `CRM_WRITE` without `CRM_PII_READ`.  
**Acceptance:** ни success DTO, ни exception, лог, audit/history не раскрывают name/email/phone; tenant encryption unaffected; failed crypto/state update rolls back.

### TCRUD-03 — Tenant negative qualification
**Fixtures:** Tenant A и B; Actor A only, Actor B only, Actor AB с разными ролями в A/B, actor с revoked/suspended membership, OWNER without operational role. Коллизии названий/кодов/SKU в A/B разрешены.  
**Coverage:** create/read/search/count/filter/update/archive/restore, composite FK assignment, category contract join, permission revoke before/after admission, concurrent version conflict, file owner read/stream (байты не должны выдаваться до проверки).  
**Acceptance:** чужой объект не раскрывается даже при известном UUID; нет смешанных выдач/page totals/cursors, сервисный rollback сохраняет ноль частичных изменений; доказательство real PostgreSQL (не только H2). Если требуется портфельный scope — сначала соответствующее решение, не считать отсутствие ACL дырой tenant isolation.

### TCRUD-06/07 — CRM requalification
**Acceptance:** защита ACTIVE Contract от архивирования Customer; независимо от архива сохраняются consent, contract revision, contact history; restore Customer сам по себе не «разархивирует» контакты/договоры и не восстанавливает consent; contract renewal не меняет прошлые условия; Category assignment и Contract создаются строго внутри tenant; one-winner CAS и append-only DB trigger проверены.

### TCRUD-11 — FILES qualification
**Acceptance:** доступ к file bytes/metadata/attachments зависит от `FILES_*` permission **и** авторизации owner из владеющего домена; RustFS key не служит credential; foreign-tenant reject до storage I/O; scanner ERROR не делает объект доступным; `detach` не уничтожает файл, используемый другими ссылками; recovery/delete повторно сверяют storage pointer/version после IO.

## 8. Приёмка, ошибки и эксплуатационные критерии

1. **Gate A — Architecture:** code inventory и ссылки на подтверждённые контракты, отсутствие новых public UUID bypass и generic CRUD engine.
2. **Gate B — Unit/H2:** validation, DTO privacy, state-machine, idempotency and denied roles; эти тесты не доказывают PostgreSQL locks/triggers.
3. **Gate C — PostgreSQL:** реальные two-session revoke / stale JPA / CAS, cross-tenant JOIN/FK, append-only triggers, rollback. Положительные и негативные пути для каждого изменённого service.
4. **Gate D — Migration:** аддитивная Liquibase миграция поверх *заполненной* synthetic production-like PostgreSQL базы; проверить сохранность tenant IDs, PII envelopes, связей, историй; restore rehearsal для release.
5. **Gate E — Operational:** least-privilege runtime SQL user vs migration user, deny DDL and table bypass; оценка RLS отдельно, не объявлять включённым; p95/p99 и lock contention измерить до установки SLA, не придумывать цифры.
6. **Gate F — File Infra:** при изменении FILES подтвердить реальные RustFS/ClamAV/recovery tests на том же SHA; синтетические сканеры не считаются production AV.
7. **Gate G — Release:** точный commit SHA + CI run IDs H2/PG/populated/infra + независимый reviewer. В main и production **HOLD** до отдельного решения; GitHub commit ТЗ не является подтверждением кода/тестов.

**Severity:** P0 = потенциальный bypass безопасности, PII leak, cross-tenant data mix или незавершённый OWNER security decision; P1 = необходимый рабочий сценарий или race/consistency gap без доказанного bypass; P2 = новая функциональная вертикаль, не обещанная существующим сервисом.

**Definition of Done для карточки:** (1) точный список изменённых методов, (2) подготовленная migration при необходимости, (3) существующие плюс новые negative/PG tests, (4) evidence на точном SHA, (5) updated matrix, (6) известные ограничения, (7) запрет на ложный QUALIFIED/production-ready.

## 9. Открытые продуктовые решения

| ID | Требуется утвердить | До решения |
|---|---|---|
| DEC-OWNER-01 | Как первый OWNER получает личный операционный доступ: A/B/C (§5)? Какие базовые permissions и отдельно PII? | Сохранить запрет self-assignment и no wildcard |
| DEC-CRM-SCOPE | CRM_READ/CRM_PII_READ открывают весь tenant-портфель или только назначенных клиентов? | Сохранять документированное tenant-wide поведение для назначенных permissions |
| DEC-INV-SCOPE | Склад: все локации tenant или только закреплённые? | Не обещать per-warehouse ACL |
| DEC-CRM-CATEGORY | Требуется ли восстановление архивной категории или несколько категорий на клиента? | Одна primary category, архив без restore |
| DEC-CRM-CONTRACT | Нужны ли полное расторжение, fee/amount, документ и архив договора сверх текущих статусов? | Сохранять текущую модель DRAFT/ACTIVE/CLOSED/CANCELLED |
| DEC-SALES-LINK | Как Customer CRM связан с SALES order? | Не соединять по `buyer_user_id` |
| DEC-FILES-OWNER | Разрешать ли PERSON/CATALOG/SALES владельцев, кто получает binary read? | Только квалифицированные typed owner policy |
| DEC-RETENTION | Сроки хранения, legal hold, физическое удаление PII, документов и аудита | Запрет hard delete значимой истории |

## 10. Ближайшая исполняемая задача Codex

**TCRUD-00 + TCRUD-02 discovery:** проанализировать Java в текущем `base_dev`, вывести method-level inventory из §7 и проверить PII responses в `AuthenticatedCrmCommands`/`CrmCustomerService`. Не менять контроллеры, роли или бизнес-правила до утверждения DEC-OWNER-01. Подготовить один адресный PR/commit в `base_dev` только с доказанным P0 fix и PostgreSQL negative tests, затем обновить этот документ с фактическими CI evidence. Никакого merge в `main`.


## 11. Wave 0 implementation record — TCRUD-00 / TCRUD-02

- **TCRUD-00 discovery:** [method inventory](../reviews/RM-TCRUD-00-METHOD-INVENTORY-2026-11-22.md) enumerates the public facade/selected service signatures captured from source baseline `81c6a4a`. Entries are labeled `PRESENT` only. Full service-to-SQL-to-test qualification remains an **open evidence task**; this inventory must not be described as final TCRUD-00 completion.
- **TCRUD-02 chosen response contract:** keep the current `Customer`/`Contact` Java return types to avoid a broad breaking API change. `AuthenticatedCrmCommands.createPerson/updatePerson` redact `Customer.name`; `addContact/updateContact` redact `Contact.name/email/phone` **for every caller**, regardless of their permissions. A null response field means **withheld**, not cleared in the database. `createCompany/updateCompany` may return public organization labels. Future adapters should use explicit mutation-result DTOs with no PII members.
- Read-back of PERSON name or Contact fields continues to require `CRM_PII_READ` on the independently authorized query path. A role with only `CRM_WRITE` can mutate but cannot read PII; a negative regression test qualifies that distinction. Encrypted AAD, JDBC writes, consent, audit and historic revisions remain unchanged.
- **Gate:** this record only states code intent. Mark `TESTED`/`QUALIFIED` once actual GitHub Actions H2, PostgreSQL, populated migration and RustFS jobs succeed on the **same exact SHA**. Existing historical GREEN does not qualify a new commit.
