# RM CRM — аудит 99 бизнес-симуляций и карта сервисных улучшений

**Ветка:** `base_dev` · **Контекст:** Java/Spring service layer · **Контроллеры, REST, BFF, UI: STOP** · **Production: HOLD**

## 1. Методика: что действительно проверено

99 сценариев ниже — **99 независимых предметных бизнес-симуляций на уровне требований и трассировки существующих сервисов и JUnit**, а **не 99 исполненных PostgreSQL E2E**. Каждый сценарий содержит наблюдаемое поведение пользователя, предполагаемый source of truth, ссылку на подтверждающий компонентный тест (если найден), пробел и предлагаемое действие. `COMPONENT_TESTED` — доказательство операции компонента, **не** успешное прохождение полного пути сотрудника.

Для реального закрытия сценария нужны отдельные JUnit со счётчиками побочных записей, отказами, ролями, tenant A/B, retry, CAS, rollback и подтверждением exact-SHA Surefire. Машинный источник: `RM-CRM-99-BUSINESS-SIMULATIONS-v1.json`. Проверка целостности 99 позиций: `.github/scripts/verify_rm_crm_99_simulations.py` и негативные unit-тесты.

## 2. Сводная оценка

| Оценка | Сценариев | Значение |
|---|---:|---|
| COMPONENT_TESTED | 52 | Существуют релевантные компонентные тесты, но E2E не доказан |
| PARTIAL | 16 | Требуется полноценная сервисная последовательность и дополнительные отрицательные ветки |
| NEW_PG_TEST | 2 | Новые JUnit добавлены; факт исполнения определяется последним SHA |
| MISSING_FEATURE | 21 | Отсутствует нужный сервис/контракт источника истины |
| BLOCKED_PROVENANCE | 4 | Нельзя достоверно связать CRM customer с покупателем/заказом |
| POLICY_GAP | 2 | Требуется предметное решение и отрицательные тесты |
| EXTERNAL_HOLD | 2 | Нужен отдельный внешний IdP/email-provider E2E |
| **Итого** | **99** | Доля не равна code coverage или production readiness |

**Из исходных 75 функциональных требований R3-v1:** 46 реализованы/квалифицированы по существующим каноническим proof, 29 пока PLANNED. Эти базовые цифры не меняются от документа и новых тестов.

## 3. Детализация 99 бизнес-ситуаций

| Код | Контур | Бизнес-ситуация | Желаемый исход | Оценка | Приоритет |
|---|---|---|---|---|---|
| CRM-SIM-001 | IDENTITY | Valid confirmed principal creates first organization | Create one owner/workspace; no implicit CRM grants | COMPONENT_TESTED | P0 |
| CRM-SIM-002 | IDENTITY | Unverified email attempts first organization | Reject with zero workspace/membership writes | COMPONENT_TESTED | P0 |
| CRM-SIM-003 | IDENTITY | Same provisioning key replay | Return same tenant without second audit event | COMPONENT_TESTED | P0 |
| CRM-SIM-004 | IDENTITY | Two simultaneous first-tenant requests | Exactly one owner tenant and no orphans | COMPONENT_TESTED | P0 |
| CRM-SIM-005 | IDENTITY | Suspended principal selects former tenant | Reject before business writes | COMPONENT_TESTED | P0 |
| CRM-SIM-006 | IDENTITY | Foreign employee guesses tenant UUID | Reject tenant access without leaking ownership | COMPONENT_TESTED | P0 |
| CRM-SIM-007 | IDENTITY | OIDC signing key rotated at external issuer | Validate rotated issuer key via real HTTPS test IdP | EXTERNAL_HOLD | P0 |
| CRM-SIM-008 | IDENTITY | Email verification transport is unavailable | No verified status until delivered proof; bounded retry | EXTERNAL_HOLD | P1 |
| CRM-SIM-009 | IDENTITY | Former owner loses organization membership | Do not rebootstrap first tenant or inherit grants | COMPONENT_TESTED | P0 |
| CRM-SIM-010 | CUSTOMER | Employee creates company | ACTIVE company appears in tenant-bound search | COMPONENT_TESTED | P0 |
| CRM-SIM-011 | CUSTOMER | Employee registers a person with PII | Encrypt name, safe command response, authorized read | COMPONENT_TESTED | P0 |
| CRM-SIM-012 | CUSTOMER | Customer name is changed with correct version | Update data, revision and immutable history | COMPONENT_TESTED | P0 |
| CRM-SIM-013 | CUSTOMER | Two employees update same customer version | Exactly one accepted version, no lost update | COMPONENT_TESTED | P0 |
| CRM-SIM-014 | CUSTOMER | Known customer UUID supplied from tenant B | No foreign customer record or oracle leak | COMPONENT_TESTED | P0 |
| CRM-SIM-015 | CUSTOMER | Employee archives a customer with active contract | Reject and keep history unchanged | COMPONENT_TESTED | P0 |
| CRM-SIM-016 | CUSTOMER | Employee restores archived customer | Restore lifecycle without fabricating consent | PARTIAL | P0 |
| CRM-SIM-017 | CUSTOMER | Search contains SQL wildcard characters | Literal matching; tenant isolation unchanged | COMPONENT_TESTED | P1 |
| CRM-SIM-018 | CUSTOMER | Large tenant searches customers by page | Bounded stable pagination and measured p95 | PARTIAL | P2 |
| CRM-SIM-019 | CONTACT_PII | Employee adds contact to active company | Encrypted fields, active parent FK and audit | COMPONENT_TESTED | P0 |
| CRM-SIM-020 | CONTACT_PII | Create contact under archived customer | No contact row and no plaintext side effects | COMPONENT_TESTED | P0 |
| CRM-SIM-021 | CONTACT_PII | Read-only CRM reader opens a private contact | Require distinct PII_READ permission | COMPONENT_TESTED | P0 |
| CRM-SIM-022 | CONTACT_PII | Write-only employee creates a private contact | Mutation response contains no decrypted PII | COMPONENT_TESTED | P0 |
| CRM-SIM-023 | CONTACT_PII | Contact encryption envelope is swapped | AAD mismatch fails closed | COMPONENT_TESTED | P0 |
| CRM-SIM-024 | CONTACT_PII | Two writers modify one contact version | At most one succeeds, history correct | COMPONENT_TESTED | P0 |
| CRM-SIM-025 | CONTACT_PII | Foreign tenant reads a known contact UUID | Reject without data or PII | COMPONENT_TESTED | P0 |
| CRM-SIM-026 | CONTACT_PII | Contact is archived while search runs | No archived contact in active result | PARTIAL | P1 |
| CRM-SIM-027 | CONTACT_PII | Phone/email normalization and duplicate detection | Define matching, verification and conflict policy | MISSING_FEATURE | P2 |
| CRM-SIM-028 | CONSENT | New contact has no consent | Eligibility false by default | COMPONENT_TESTED | P0 |
| CRM-SIM-029 | CONSENT | Consent granted for purpose A | Purpose A true, purpose B false | COMPONENT_TESTED | P0 |
| CRM-SIM-030 | CONSENT | Grant repeated with same key | One immutable event and same receipt | COMPONENT_TESTED | P0 |
| CRM-SIM-031 | CONSENT | Same key used for conflicting revoke | Reject with no event delta | COMPONENT_TESTED | P0 |
| CRM-SIM-032 | CONSENT | Revoke accepted after grant | Eligibility false and immutable history | COMPONENT_TESTED | P0 |
| CRM-SIM-033 | CONSENT | Foreign tenant queries private consent | Permission deny, not a customer lookup leak | COMPONENT_TESTED | P0 |
| CRM-SIM-034 | CONSENT | Previously consented contact is archived | Eligibility false; existing grant never authorizes archived contact | NEW_PG_TEST | P0 |
| CRM-SIM-035 | CONSENT | Parent customer archived after consent | All child contact eligibility false while parent archived | NEW_PG_TEST | P0 |
| CRM-SIM-036 | CONSENT | Restored contact after historic opt-in | Explicit re-consent versus auto-resurrection policy and test | POLICY_GAP | P0 |
| CRM-SIM-037 | INTERACTIONS | Employee records CALL for active contact | OPEN record and immutable event | COMPONENT_TESTED | P0 |
| CRM-SIM-038 | INTERACTIONS | Employee records MEETING and follow-up | Due date saved with correct revision | COMPONENT_TESTED | P0 |
| CRM-SIM-039 | INTERACTIONS | CALL request key replay | Return same interaction ID, no duplicate event | COMPONENT_TESTED | P0 |
| CRM-SIM-040 | INTERACTIONS | Request key reused for a different CALL | Reject conflicting fingerprint | COMPONENT_TESTED | P0 |
| CRM-SIM-041 | INTERACTIONS | Manager assigns active responsible employee | Tenant membership and role validated | COMPONENT_TESTED | P0 |
| CRM-SIM-042 | INTERACTIONS | Manager assigns foreign or suspended employee | Deny with zero interaction/event changes | PARTIAL | P0 |
| CRM-SIM-043 | INTERACTIONS | Reader tries to complete an interaction | Permission deny; no event appended | COMPONENT_TESTED | P0 |
| CRM-SIM-044 | INTERACTIONS | Two employees complete same row version | One terminal outcome, no double history | PARTIAL | P0 |
| CRM-SIM-045 | INTERACTIONS | Employee writes confidential NOTE body | Encrypted body + purpose AAD, never plaintext | MISSING_FEATURE | P0 |
| CRM-SIM-046 | CONTRACTS | Create draft contract under active customer | Tenant-owned contract and baseline revision | COMPONENT_TESTED | P0 |
| CRM-SIM-047 | CONTRACTS | Activate valid contract version | Status ACTIVE and immutable revision | COMPONENT_TESTED | P0 |
| CRM-SIM-048 | CONTRACTS | Activate contract under archived customer | Deny without status/history side effects | COMPONENT_TESTED | P0 |
| CRM-SIM-049 | CONTRACTS | Extend contract end date | New revision retains old snapshot | COMPONENT_TESTED | P0 |
| CRM-SIM-050 | CONTRACTS | Two employees change same revision | Optimistic version fence prevents lost update | COMPONENT_TESTED | P0 |
| CRM-SIM-051 | CONTRACTS | Read contract expiry in a bounded window | Correct timezone and inclusive/exclusive date semantics | PARTIAL | P1 |
| CRM-SIM-052 | CONTRACTS | Customer archival while active contract exists | Reject without history event | COMPONENT_TESTED | P0 |
| CRM-SIM-053 | CONTRACTS | Cancel contract then archive customer | Domain transition policy and no stale references | PARTIAL | P0 |
| CRM-SIM-054 | CONTRACTS | Counterparty contract belongs to tenant B | Tenant-qualified FK and read rejection | COMPONENT_TESTED | P0 |
| CRM-SIM-055 | SALES_LINK | Buyer orders published product | Immutable quote and seller order saved | COMPONENT_TESTED | P0 |
| CRM-SIM-056 | SALES_LINK | Employee requests orders for known CRM customer | Cannot join without verified buyer/customer identity | BLOCKED_PROVENANCE | P0 |
| CRM-SIM-057 | SALES_LINK | Two CRM customers share normalized email | Never infer identical buyer from email alone | BLOCKED_PROVENANCE | P0 |
| CRM-SIM-058 | SALES_LINK | Customer record has same display name as buyer | No name-based order association | BLOCKED_PROVENANCE | P0 |
| CRM-SIM-059 | SALES_LINK | Buyer tenant differs from seller tenant | No mistaken cross-tenant customer link | BLOCKED_PROVENANCE | P0 |
| CRM-SIM-060 | SALES_LINK | Checkout replay after seller status change | Same receipt without duplicate order | COMPONENT_TESTED | P0 |
| CRM-SIM-061 | SALES_LINK | Seller loses READ permission mid timeline request | No cross-domain partial data exposure | PARTIAL | P0 |
| CRM-SIM-062 | SALES_LINK | Buyer verified identity linked by explicit proof | Implement immutable identity binding with consent and source | MISSING_FEATURE | P0 |
| CRM-SIM-063 | SALES_LINK | Customer timeline includes payment status | Do not infer PAID from FULFILLED | MISSING_FEATURE | P1 |
| CRM-SIM-064 | FILES | Employee attaches clean document to customer | Typed customer owner, scan and immutable reference | COMPONENT_TESTED | P0 |
| CRM-SIM-065 | FILES | EICAR upload arrives for same customer | Reject quarantine, never expose bytes | COMPONENT_TESTED | P0 |
| CRM-SIM-066 | FILES | ClamAV service times out | Fail closed without AVAILABLE object | COMPONENT_TESTED | P0 |
| CRM-SIM-067 | FILES | Foreign tenant tries to read known file | Zero binary bytes read | COMPONENT_TESTED | P0 |
| CRM-SIM-068 | FILES | Reader lacks CRM_PII_READ for customer document | Denied even with general FILES_READ | COMPONENT_TESTED | P0 |
| CRM-SIM-069 | FILES | FILES_READ revoked during download | No bytes after admission revoked | PARTIAL | P0 |
| CRM-SIM-070 | FILES | Owner customer archived after attachment | Define historical document visibility and retention | PARTIAL | P1 |
| CRM-SIM-071 | FILES | Duplicate attachment request retried | Idempotent owner relationship and receipt | PARTIAL | P1 |
| CRM-SIM-072 | FILES | File is restored after infrastructure restart | Real RustFS/ClamAV and metadata consistency | COMPONENT_TESTED | P1 |
| CRM-SIM-073 | SECURITY_AUDIT | Owner grants CRM_READ to employee | Employee can read, cannot write | COMPONENT_TESTED | P0 |
| CRM-SIM-074 | SECURITY_AUDIT | Owner revokes CRM_READ | Following read rejected, no history modifications | COMPONENT_TESTED | P0 |
| CRM-SIM-075 | SECURITY_AUDIT | Suspended tenant attempts any CRM mutation | Denied with zero business/audit delta | COMPONENT_TESTED | P0 |
| CRM-SIM-076 | SECURITY_AUDIT | Viewer tries to update private contact | Denied before ciphertext changed | COMPONENT_TESTED | P0 |
| CRM-SIM-077 | SECURITY_AUDIT | RBAC revoke races with CRM interaction completion | Both transaction orders must be deterministic | PARTIAL | P0 |
| CRM-SIM-078 | SECURITY_AUDIT | Auditor queries sanitized customer history | No PII in bounded timeline | PARTIAL | P1 |
| CRM-SIM-079 | SECURITY_AUDIT | Audit export crosses tenant boundary | Never reveal other tenant objects or counts | MISSING_FEATURE | P1 |
| CRM-SIM-080 | SECURITY_AUDIT | Retention job runs during customer archive | Legal hold and event immutability preserved | PARTIAL | P1 |
| CRM-SIM-081 | SECURITY_AUDIT | CRM role matrix for all 5 membership roles | Cover each operation including deny/zero deltas | PARTIAL | P0 |
| CRM-SIM-082 | TASKS | CALL schedules next action without task | Timestamp retained; not a TASKS entity | PARTIAL | P0 |
| CRM-SIM-083 | TASKS | Employee creates tenant-owned task | TASKS source of truth and RBAC required | MISSING_FEATURE | P0 |
| CRM-SIM-084 | TASKS | Manager assigns task to active employee | Tenant-qualified owner and assignee | MISSING_FEATURE | P0 |
| CRM-SIM-085 | TASKS | Inactive employee receives task | Reject without dangling assignment | MISSING_FEATURE | P0 |
| CRM-SIM-086 | TASKS | Employee marks task completed | Immutable task transition and history | MISSING_FEATURE | P0 |
| CRM-SIM-087 | TASKS | Two employees close task concurrently | One completion and idempotent retry | MISSING_FEATURE | P0 |
| CRM-SIM-088 | TASKS | CRM interaction links to task | Typed immutable link, not a shadow task table | MISSING_FEATURE | P0 |
| CRM-SIM-089 | TASKS | Worker is suspended with pending tasks | Reassignment policy and no leaked reminders | POLICY_GAP | P1 |
| CRM-SIM-090 | TASKS | Reminder job crashes after publishing | Outbox retry and dedup guarantee | MISSING_FEATURE | P1 |
| CRM-SIM-091 | FINANCE_GROWTH | Seller issues invoice for order | FINANCE-owned immutable invoice snapshot | MISSING_FEATURE | P1 |
| CRM-SIM-092 | FINANCE_GROWTH | Buyer makes partial payment | Allocation to invoice, overpayment rejected | MISSING_FEATURE | P1 |
| CRM-SIM-093 | FINANCE_GROWTH | Payment webhook retries | Receipt dedup and no duplicate ledger | MISSING_FEATURE | P1 |
| CRM-SIM-094 | FINANCE_GROWTH | Order fulfilled but not paid | Do not emit paid status without payment proof | MISSING_FEATURE | P0 |
| CRM-SIM-095 | FINANCE_GROWTH | Finance event races with order cancellation | Explicit compensating policy and rollback | MISSING_FEATURE | P1 |
| CRM-SIM-096 | FINANCE_GROWTH | Campaign targets consented contacts only | Consent snapshot and send admission required | MISSING_FEATURE | P2 |
| CRM-SIM-097 | FINANCE_GROWTH | Consent revoked while campaign queued | No dispatch after revocation commit | MISSING_FEATURE | P1 |
| CRM-SIM-098 | FINANCE_GROWTH | Tenant exceeds subscription quota | Fail before write; idempotent usage meter | MISSING_FEATURE | P2 |
| CRM-SIM-099 | FINANCE_GROWTH | Subscription renewal fails | Suspend entitlement without deleting CRM customer | MISSING_FEATURE | P2 |

## 4. Где нужно реально усиливать сервисы и тесты

### P0 — в ближайшем сервисном инкременте

1. **CRM-99-SAFE-01: Consent admission.** Исправлено: проверка `hasEmailConsent` теперь требует активного contact **и** customer, иначе старый grant больше не разрешает коммуникацию. Новая обязательная PostgreSQL проверка `crm99ArchivedContactAndParentNeverRemainCommunicationEligible`. **Не закрыто:** гарантии после `restoreContact`/`restoreCustomer`: допустима ли реанимация старого grant? Без утверждённой политики требуется отдельное безопасное fail-closed правило.
2. **CRM-99-SAFE-02: Responsible assignment.** Новый обязательный PostgreSQL JUnit `crm99ForeignAndSuspendedAssigneeProduceZeroInteractionSideEffects`: чужому или suspended сотруднику запрещено назначение; `row_version` и число interaction events неизменны.
3. **CRM-99-IDENTITY-03: CRM customer ↔ buyer binding.** **Не реализовано намеренно**, потому что в CRM нет подтверждённой связи `customer_id` и `buyer_user_id`. Нужны trusted provenance/consent, tenant FK, idempotent linking receipt, журнал источника, обе гонки `revoke × link`, `cancel × link`. Нельзя использовать совпадение email/имени или tenant как доказательство.
4. **CRM-99-EMPLOYEE-04: User/role matrix.** Для customer/contact/interaction/contract связать действия в одно PG service-E2E и проверить OWNER/ADMIN/MANAGER/MEMBER/VIEWER, `CRM_READ`, `CRM_WRITE`, `CRM_PII_READ`, архив, отмену, отзыв прав и отсутствие побочных записей.
5. **CRM-99-CONSENT-05:** Блокировать маркетинговую отправку при отзыве, даже если задача была ранее поставлена в очередь. Не реализовывать dispatch до определения authoritative consent decision и блокировок.

### P1 — после подтверждения P0

6. **INT-03 — защищённые NOTE/outcome.** Шифрование body с tenant/purpose/record AAD, key lifecycle, отсутствие plaintext в таблицах, событиях и ответах read-only пользователя; негативные crypto/tamper/retention кейсы.
7. **TASKS-01/02, INT-04 — задачи.** Отдельный tenant-owned task lifecycle с typed CRM interaction reference, CAS, assignment guards, напоминаниями через outbox и idempotent replay. Не хранить дублированные задания в CRM.
8. **FILES-06/AUD-03.** Сквозная CRM attachment и отложенное чтение после revoke, restore, tenant isolation на реальном RustFS/ClamAV; отдельный PII-safe, paginated audit timeline.
9. **FINANCE-01..04/SAL-07.** Только FINANCE владеет invoice/payment/allocation; не объявлять оплаченной сделку из статуса handover или fulfillment.

### P2 — после реального customer/order workflow

10. Оптимизация поиска, дубликатов и SLA `p95/p99` на 1k/10k клиентов, проверка pagination stability.
11. Маркетинговые кампании, подписки и квоты после доказательной consent + payment архитектуры.

## 5. Защита CI и границы допущения

- Два новых метода включены в обязательный `kms-postgres-surefire` exact-SHA `RM-QA-01B`: **только их успешное исполнение без skip** подтверждает локальные регрессионные свойства.
- Отдельный валидатор проверяет ровно 99 уникальных сценариев в 11 группах, существование указанного JUnit-метода, согласованность классификации и запрет ложного утверждения `99 PG PASS`. Негативные фикстуры запрещают выбрасывать строки, выдумывать тесты и открывать UI/production.
- Сервисные и внешние проверки должны иметь независимые gate. Наличие 99 сценариев не означает гарантии всех пользовательских путей или готовности к запуску.
- Все изменения только в `base_dev`; никакой новый контроллер, REST endpoint, UI, shadow CRM/Sales owner не создан.

## 6. Рекомендованная следующая задача

**CRM-99-R1 — Critical User Behavior Qualification:** закрыть P0 в следующем порядке: lifecycle consent restoration policy → `revoke × interaction-assignment` два порядка на реальном PostgreSQL → пять ролевых профилей для одного сквозного CRM journey → customer/buyer provenance ADR. Затем тестовые acceptance gates для INT-03 и TASKS. Сохранять `PRODUCTION HOLD`.
