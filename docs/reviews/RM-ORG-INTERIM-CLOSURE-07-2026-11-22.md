# RM — ORGANIZATION: промежуточное заключение после проходов 01–07/12

**Документ:** RM-ORG-INTERIM-R1-07. **As-of:** 22.11.2026, исходный проверенный runtime `1f24bba79b228dff7a8b4df75f0f80fd700766e0` (`base_dev`). **Статус:** *INTERIM SOURCE REVIEW COMPLETED / IMPLEMENTATION PENDING / RELEASE HOLD*. Не итоговая эксплуатационная приёмка и не полный цикл 12/12.

**Нормативные документы:** [единое ТЗ ORGANIZATION](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md), [последний IAM pass 07](RM-ORG-FUNCTIONAL-DEPTH-PASS07-2026-11-22.md), [полный реестр сервисов RM](RM-SVC-FUNCTIONAL-DEPTH-AUDIT-2026-11-22.md).

## 1. Резюме для владельца RM

**Вывод:** ORGANIZATION имеет действующий минимальный сервисный фундамент для создания и чтения физических локаций внутри выбранного tenant, полноценную IAM permission boundary и использующий его INVENTORY/SALES. **Зрелость по исходной шкале остаётся 1/5**: пользователь пока не может полноценно изменить, деактивировать, восстановить или окончательно закрыть склад через доверенный бизнес-сервис; DB rowVersion и enum сами по себе этого не реализуют.

Семь последовательных предметных проходов **SPECIFIED**, не семь выполненных технических релизов. Никакого runtime изменения ORGANIZATION по `ORG-R1.1`, нулевого переходного E2E, а также новой production admission в рамках этой серии не сделано. Новые P03–P07 QA cases ещё **NOT RUN**; уже существующие tests и их описания — не подмена этого evidence.

**Решение по последовательности:** по изменённому запросу пользователя аудит ORGANIZATION можно **приостановить после 07/12** и начать следующий домен; 08–12 остаются отдельным `DEFERRED / NOT DONE` backlog и будут нужны при подготовке реализации/release. Никакого «100% готово» не утверждать. Переключение предметного аудита ≠ production acceptance.

## 2. Карта доказательств по семи проходам

| Pass | Что разобрано | Evidence | Проход |
|---|---|---|---|
| 01 | Source map, CRUD GAP, cross-domain boundaries | [основной документ/снимок](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md) | SPECIFIED |
| 02 | Реальный путь BRANCH/WAREHOUSE/POS, onboarding, first receipt, store frontage | [P02](RM-ORG-FUNCTIONAL-DEPTH-PASS02-2026-11-22.md) | SPECIFIED; исторические gaps обновлены |
| 03 | Статусы ACTIVE/INACTIVE/CLOSED, свободный stock при паузе, outstanding reservations, concurrency | [P03](RM-ORG-FUNCTIONAL-DEPTH-PASS03-2026-11-22.md) | SPECIFIED |
| 04 | Create/edit DTO, normalized name/address/KATO, tenant-scoped optimistic CAS, no-op, KATO history | [P04](RM-ORG-FUNCTIONAL-DEPTH-PASS04-2026-11-22.md) | SPECIFIED |
| 05 | Receive/correct/writeOff/transfer/reserve/stocktake, stock/replay/locks, OPEN stocktake blocker | [P05](RM-ORG-FUNCTIONAL-DEPTH-PASS05-2026-11-22.md) | SPECIFIED; Q7 lock fix later present in code |
| 06 | SALES confirm/cancel/fulfill, reserve per order line, terminal replay, ORG×SALES races | [P06](RM-ORG-FUNCTIONAL-DEPTH-PASS06-2026-11-22.md) | SPECIFIED |
| 07 | VerifiedActor, OIDC, A/B/AB permissions, RBAC assignment, revoke/member/account/tenant fences | [P07](RM-ORG-FUNCTIONAL-DEPTH-PASS07-2026-11-22.md) | SPECIFIED |

**Не переоценивать:** INACTIVE/CLOSED сейчас существуют в DDL/enum и тестовых direct SQL updates, но **не** в работающей публичной ORG state-command API. P03–P07 tests плановые и не имеют GREEN доказательства на новом коде.

## 3. Что реально уже существует и сохраняется

- `AuthenticatedOrganizationLocations.create/get/search` с `VerifiedActor` и `TenantAuthorization.requireForCommand` (WRITE/READ) для tenant-scoped workflows; entity `org_location` с `@Version rowVersion` и KATO FK. Нет controller transport, и это не дефект в текущем Feature Freeze.
- IAM `OidcJwtConfiguration`/`OidcActorResolver` проверяют подписанный token issuer/audience/exp при включённой конфигурации; tenant permissions/членство проверяются отдельно, actor UUID из клиента не является основанием доступа. `TenantRoleService`, `VerifiedTenantRoleCommands`, `TenantMemberEnrollmentService`, `TenantAccessStatusService` и `PlatformTenantBootstrapService` предоставляют реальную внутреннюю последовательность выдачи/отзыва прав. `TENANT_RBAC_MANAGE` — право активного OWNER, не functional grant.
- `InventoryMovementService` теперь разрешает first RECEIPT с автоматической нулевой stock row; `StockEligibility` запрещает новые записи на INACTIVE/CLOSED и не-WAREHOUSE; `InventoryTransferService` переносит запасы между двумя складами с ledger; `InventoryStocktakeService` поддерживает OPEN/POSTED/CANCELLED.
- **Исправлено между P05 и P06 в INV-Q7:** `StockEligibility.requireTransferWarehouses` получает оба location row locks по UUID до PRODUCT. Текущий исходный алгоритм более не содержит обнаруженного старого `location A→PRODUCT→location B` порядка; смешанная concurrency проверка с future ORG.deactivate ещё нужна.
- SALES `confirm/cancel/fulfill` транзакционно резервирует/освобождает/списывает товар через `OrderInventoryPort` и paired IAM decisions. Существующие PostgreSQL tests проверяют значительную часть stock arithmetic, duplicate requests и revoke-vs-command, но не отсутствующий пока ORG lifecycle.

## 4. Утверждённые проектные правила, без новых архитектурных подсистем

| Рубрика | Конкретное правило |
|---|---|
| Tenant | Один `org_location.workspace_id`; identity/type immutable. Не создавать legal organization второй раз. |
| КАТО | Новый КАТО только по eligible GEO; changed code запрещён при stock или **любой** reservation/movement history. |
| Create/update | Нормализация `strip+NFC`, name ≤200 codepoints, optional address ≤500, atomic full-profile update, required `expectedVersion` и JPA row lock. |
| Временная пауза | `ACTIVE→INACTIVE` при **любом свободном onHand**, если `reserved=0`, нет ACTIVE reservation и OPEN stocktake. |
| Возврат | `INACTIVE→ACTIVE` без автоматических stock changes; сохранённый deprecated KATO не должен запереть склад навсегда. |
| Окончательное закрытие | Только `INACTIVE→CLOSED`, `onHand=reserved=0`, нет ACTIVE reservations/OPEN stocktake. История не удаляется. |
| Новые stock writes | Только ACTIVE WAREHOUSE; обе стороны transfer должны быть ACTIVE. |
| SALES | Новый confirm/reserve и новая fulfill — только ACTIVE. Cancel/release cleanup legacy — legal по IAM; terminal replay после CLOSED — read-only. |
| Конкурентность | IAM(actor→tenant) lock, затем ORG location row; INVENTORY/SALES writers удерживают тот же location lock, ORG blockers только `EXISTS` без обратных order/stock locks. |
| Permission | ORG_READ не раскрывает глобальные stock quantities, ORG_WRITE не даёт INVENTORY_ADJUST, global PLATFORM_ADMIN не является tenant owner/member. |

## 5. Реестр реализации после семи проходов

| Priority | Work ID | Изменение | Критерий выхода |
|---|---|---|---|
| **P0** | ORG-IMP-01 | Добавить `LocationCreateRequest/LocationUpdateRequest`, общую нормализацию и typed `AuthenticatedOrganizationLocations.update` | Immutable identity, no-op, stale CAS, tenant A/B |
| **P0** | ORG-IMP-02 | Реализовать `deactivate/reactivate/close` c scoped location lock, `expectedVersion`, state transitions, audit | Пауза с onHand>0, блокировка reserve/OPEN stocktake, strict close; transactional atom |
| **P0** | ORG-IMP-03 | Реализовать inventory-owned `LocationUsageBlockers`: hasOnHand,hasReservedBalance,hasActiveReservation,hasOpenStocktake | Read-only tenant/location EXISTS под location lock, не раскрывает amounts |
| **P0** | ORG-IMP-04 | Cross-domain qualification `confirm/receive/transfer/startStocktake×deactivate` | DB COMMIT order proof, no stock mutation on INACTIVE, no orphan confirmed order |
| **P0 (dependency SALES)** | SALE-IMP-01 | Immutable order line SKU snapshot; per-line reserve/fulfilled movement verification | Нет live offer substitution, rollback multi-line |
| **P1** | INV-IMP-01 | NEW fulfillment ACTIVE-WAREHOUSE guard перед stock lock; terminal retry/cleanup preserved | Legacy inactive cannot newly debit, permission correct |
| **P1** | IAM-DEC-01 | Решение о solo-owner функциональных правах: audited explicit owner self-functional-role assignment **или** утверждённый staff-only workflow | Без wildcard, nondelegable TENANT_RBAC_MANAGE, DDL audit additive if allowed |
| **P1** | IAM-QUAL-02 | Populated tenants с `RBAC enforced=FALSE`: inventory, explicit cutover, no silent access escalation | После переключения только точные grants и A/B qualification |
| **P1** | ORG-IMP-05 | Literal search/escape, bounded page, audit evidence, closed location history, KATO history guard | Exact filters, stable order, joined tenant proof |
| **P1** | ORG-QA-ALL | P03-Q01..14 + P04-Q01..20 + P05-Q01..20 + P06-Q01..20 + P07-Q01..24 | PostgreSQL barrier tests, H2 regression, no skip, exact SHA, qualified populated migration |
| **P2** | IAM-CLEAN-03 | Проверить legacy `requireOrganizationWriter` and all trusted call sites, deprecate only if safe | No un-fenced commands, no breaking tests |

**Итого 98 уникальных новых acceptance scenarios** на P03–P07: 14+20+20+20+24; этот объём — критерии QA, **не** выполненные тесты. Дополнительно есть отдельные ORG-Q и бизнес-сценарии P02; не складывать их как доказанные tests. Число может уменьшиться при объединении дублирующих проверок в реальные test methods, но ни один обязательный инвариант не должен исчезнуть.

**Правильная последовательность будущей реализации:** org lifecycle/normalization + usage port → verify all warehouse writers' location locks → exact SKU snapshots in SALES → full positive+negative PG E2E → populated migrations/indexes/audit qualification → load+recovery. Менять сразу все сервисы без промежуточных PostgreSQL gates не требуется.

## 6. Какие проходы сознательно оставлены на потом

| Pass | Тема | Статус и незавершённый критерий |
|---|---|---|
| 08/12 | Search/filter/page | `DEFERRED / NOT DONE`: escape LIKE %, _, bounded OFFSET, active-only picker, stable sort, Unicode names, pagination under revoke |
| 09/12 | PostgreSQL + audit + populated migration | `DEFERRED / NOT DONE`: reproducible populated DB, strict SQL FK/constraints, no PII leak, atomic `crm.audit_event`, audit immutability, rollback |
| 10/12 | Concurrency/recovery | `DEFERRED / NOT DONE`: 2–3 connection ORG×INV×SALES, timeouts/deadlocks, crashes/retries, stale versions, no duplicate movements |
| 11/12 | Load qualification | `DEFERRED / NOT DONE`: lock wait/multi-SKU throughput, p95/p99, indexing EXPLAIN, no unsupported performance numbers |
| 12/12 | Final functional qualification | `DEFERRED / NOT DONE`: evidence matrix all CRUD transitions, tenant permission negative cases, release gates, explicit residual risks |

**Release HOLD:** Бизнес-цепочка «создать склад → отгрузить → временно остановить с товаром → восстановить → вывести остаток → закрыть» не может быть признана выполненной, пока доверенные lifecycle методы отсутствуют и нет настоящих PostgreSQL E2E. Ни один аудит 01–07 не даёт разрешения на merge main / production.

## 7. Промежуточный вердикт и следующий предмет аудита

- **Scope maturity: 1/5.** Это не показатель качества всего RM; только полнота business lifecycle ORGANIZATION.
- **Security foundation:** доверенная авторизация реализована и частично квалифицирована existing source tests. Не утверждать единый продуктовый SSO/production ingress без реального adapter/issuer E2E.
- **Документация:** 07/12 source-level passes SPECIFIED; 08–12 отложены и связаны с re-entry conditions.
- **Владелец следующего аудита:** предлагается **CATALOG**: `cat_item PRODUCT/DRAFT/PUBLISHED/ARCHIVED`, `store_offer.catalog_item_id`, publisher rights и immutable SKU snapshot зависимы от CATALOG при INVENTORY/Sales. Логичен перекрёстный разбор его собственной CRUD-зрелости, после чего можно связать решение SALES snapshot с реальным publish/archive flow. Это **предложенный handoff**, не утверждение, что CATALOG уже проверен или выбран пользователем.

**Decision record:** завершить *предметное обследование* ORGANIZATION на 07/12 и перейти к следующему сервису, **не** объявляя реализацию, 08–12 или production readiness закрытыми.


---

## Актуализация handoff: следующий домен выбран — CATALOG

Пользователь утвердил CATALOG как следующий домен и запустил source-grounded pass 01. Организационные проходы 08–12 по-прежнему **DEFERRED / NOT DONE**, жизненный цикл ORGANIZATION не объявлен реализованным. Источник нового доменного аудита: [RM-CATALOG-FUNCTIONAL-DEPTH-PASS01-2026-11-22.md](RM-CATALOG-FUNCTIONAL-DEPTH-PASS01-2026-11-22.md); [основное ТЗ CATALOG](../spec/RM-CATALOG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). Handoff подтверждает смену последовательности **аудита**, не release/merge ORGANIZATION.
