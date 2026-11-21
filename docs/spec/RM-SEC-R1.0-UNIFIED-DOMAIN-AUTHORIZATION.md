# RM SEC-R1.0 — единая система безопасности доменов

**Дата:** 2026-11-21. **База:** main `f2e32bc053c6c0f3e9c5d1eb14af97978ab1eca8`.
**Ветка:** `feat/sec-r1-unified-domain-authorization`.
**Состояние:** SEC-R1.0 internal authorization foundation implemented; **HTTP/OIDC admission and production S2 HOLD**.

## 1. Цель и границы

Все 15 bounded contexts используют одно значение `tenant_id = iam_workspace.id`; регион/KATO не служит tenant. Запретить любые обращения к tenant-данным без надёжно аутентифицированного субъекта, активных account/workspace/membership и явно разрешённой операции. Принцип **deny by default**, least privilege, separation of duties.

Разделение ролей:
- **Global IAM identity**: устойчивый user UUID, статус аккаунта, OIDC subject binding (не готово).
- **Tenant membership**: роль и статус в конкретной организации; не равна глобальной роли IAM.
- **Domain permission**: конкретное действие `domain:resource:action` (не «любая запись организации»).
- **Data ownership**: `tenant_id` вычисляется доверенным adapter/context и остаётся в каждом predicate/FK; ни URI/body UUID, ни email, ни роль клиента не могут менять границу.
- **Data access policy**: field-level confidential data, owner-scoped read/download, consent и immutable evidence — независимые проверки после общей authorisation.

## 2. SEC-R1.0 контракт

`TenantAuthorization.require(actorId,tenantId,DomainPermission)` возвращает `Decision` с user/tenant/org/role/action или бросает SecurityException.

Проверки (строго по порядку): not null actor/tenant/permission → user существует, `ACTIVE` → workspace существует, `ORGANIZATION/ACTIVE` → подтверждённая membership именно в его `organization_id`, `ACTIVE` → разрешённая роль для operation. Никаких implicit allow для новой операции. `PENDING,SUSPENDED,DELETED` account и `PROVISIONING,SUSPENDED,CLOSED` workspace обязаны быть отвергнуты.

Поддержанные **конкретные** permissions (R1.0):
| Permission | OWNER | ADMIN | MANAGER | MEMBER | VIEWER |
|---|---:|---:|---:|---:|---:|
| CRM_READ | Да | Да | Да | Да | Да |
| CRM_WRITE | Да | Да | Да | Нет | Нет |
| ORGANIZATION_LOCATION_WRITE | Да | Да | Да | Нет | Нет |
| INVENTORY_SETTINGS_MANAGE | Да | Да | Да | Нет | Нет |

В R1.0 список permissions намеренно ограничен **реально существующими** internal application commands. Например, `InventoryReservationService` пока *не* получил публичное разрешение: без principal adapter и stock staff policy его нельзя безопасно экспонировать. Управление учётной политикой INV позже следует сузить до finance-officer approvals / dual control.

## 3. Матрица доменов — обязательные будущие permission contracts

| Домен | Минимальные операции (контракт R2; не считать реализованными) |
|---|---|
| IAM | USER_READ/CREATE/BLOCK/RECOVER, MEMBERSHIP_INVITE/REVOKE, ROLE_ASSIGN (separation of duties) |
| ORGANIZATION | PROFILE_READ/UPDATE, LOCATION_READ/WRITE |
| GEO | PUBLIC_KATO_READ, KATO_MAINTAIN (system admin only) |
| FILES | FILE_UPLOAD/DOWNLOAD/DELETE; target ownership and PII policy |
| CRM | CUSTOMER_READ/WRITE, CONTACT_READ/WRITE, CONSENT_RECORD/READ |
| CATALOG | ITEM_READ/PUBLISH/PRICE_CHANGE, price approval |
| SALES | ORDER_CREATE/READ/CONFIRM/CANCEL/FULFILL, seller/buyer distinct |
| INVENTORY | STOCK_READ/RECEIVE/ADJUST/RESERVE/RELEASE/FULFILL, POLICY_APPROVE |
| FINANCE | INVOICE_READ/ISSUE/VOID, PAYMENT_ALLOCATE/REVERSE, AUDIT_EXPORT |
| MARKETING | TEMPLATE_EDIT/APPROVE, SEND, CONSENT_CHECK |
| TASKS | ASSIGN/READ/COMPLETE, assignee authorization |
| AUDIT | EVIDENCE_APPEND/READ/RETENTION_ADMIN, no regular user delete |
| KMS | KEY_READ_METADATA/PROVISION/ROTATE/REVOKE with special S2 and fencing |
| SUBSCRIPTION | PLAN_READ/MANAGE, TENANT_SUBSCRIBE, ENTITLEMENT_EVALUATE |
| STOREFRONT | SETTINGS_READ/WRITE, PUBLIC_CATALOG_READ, anonymous checkout abuse gate |

Матрица выше — **scope для разработки**, не автоматически разрешённые действия. По умолчанию запрещены, пока не добавлены в enum/политику, тесты и доверенный вход.

## 4. Архитектурные границы

- **Transport:** OAuth 2.1/OIDC authorization code + PKCE (в web — BFF httpOnly secure cookie), validated JWT issuer/audience/expiry/subject; external claim maps to `iam_user_account.external_subject`. Не брать `actorId` из body/query/path.
- **IAM authorization service:** проверка status/tenant/membership/action, fail closed, deny reason sanitized. Для критичных операций после revocation учитывать свежесть snapshot, транзакционное повторное решение/защиту от TOCTOU и негативные тесты гонок.
- **Domain application services:** получают `ActorContext` от trusted layer и проверяют явное действие *до* доступа к PII; после этого применяют `tenant_id` predicates и ownership constraints.
- **Persistence:** composite FK `(tenant_id,id)`, unique keys scoped by tenant, optional PostgreSQL RLS as additional defense-in-depth (not present yet), least-privilege DB roles and separate encrypted PII schema.
- **Outbox/workers:** authenticated service identity, explicit tenant context, scoped permissions, idempotent delivery. System/retention/KMS tasks имеют отдельные права; ни `OWNER`, ни `ADMIN` не означают KMS root access.
- **Audit:** record actor, tenant, action, decision, correlationId, sanitized object reference, timestamp; без decrypted PII и секретов. Denials rate-limited/monitored. Универсального event sink пока нет.
- **Public storefront:** анонимный listing read и checkout имеют отдельный public policy, rate limit, anti-enumeration, challenge/verified buyer identity и legal consent; не подменяются организационной ролью.

## 5. Реализация в текущем изменении

- `kz.rm.iam.DomainPermission` — отдельные разрешения реализованных operations; нет автоматического wildcard.
- `TenantAuthorization.require` — центральная проверка user ACTIVE + workspace ORGANIZATION/ACTIVE + membership ACTIVE + operation/role, возвращает Decision.
- `TenantAuthorization.requireOrganizationWriter` — обратная совместимость ORG; переводится на `ORGANIZATION_LOCATION_WRITE`.
- `CrmCustomerService` — полностью заменяет собственную member/role проверку на `CRM_READ` / `CRM_WRITE` (закрывает audit P0-01).
- `InventoryTenantSettingsService` — явное `INVENTORY_SETTINGS_MANAGE`.
- `CrmR1aQualificationTest` — активные workspace в fixtures, негативные user SUSPENDED, workspace SUSPENDED, membership SUSPENDED, MEMBER/VIEWER write DENY, межтенантский READ DENY. H2/PostgreSQL CI gate.
- **Новой Liquibase миграции нет по технической причине:** для R1.0 используются уже существующие IAM user/workspace/membership статусы и FK. Новая таблица permissions без сервиса и утверждённой матрицы дала бы ложный уровень защищённости. Версионированная ABAC/RBAC schema — отдельный SEC-R1.1 proposal.

## 6. Критерии приёмки

1. Попытка читать/менять данные неактивным, неизвестным или чужим user — DENY.
2. Workspace неактивен/личный вместо организации — DENY.
3. Membership отсутствует, приостановлено или удалено — DENY.
4. MEMBER/VIEWER может просматривать разрешённые CRM объекты, но не изменять CRM, ORG или INVENTORY settings.
5. OWNER/ADMIN/MANAGER может выполнять только явно предусмотренные в R1.0 операции.
6. Межтенантская подмена UUID не возвращает PII/результат и не меняет БД.
7. Crypto/decrypted PII не появляется в исключениях/логах; доменные операции запрещаются **до** дешифрования и выдачи ключа.
8. H2 + PostgreSQL fresh tests PASS; отдельно требуется PostgreSQL upgrade-filled and 2-process/stale-token qualification.
9. Ни один публичный HTTP endpoint не принимает caller-provided actorId. Пока нет OIDC mapping, публичный admission остаётся HOLD.
10. После изменения membership или статуса пользователя новые операции отклоняются. Гарантия revocation *внутри уже выполняющегося коммита* требует SEC-R1.1 transactional guard.

## 7. Follow-up R1.1/R1.2

**SEC-R1.1:** verified principal context (OIDC PKCE/BFF), authorization adapters, mandatory domain action contract for SALES/INVENTORY, fail-closed multi-tenant tests, security audit sink, permission matrix versioning, legal finance approvals.

**SEC-R1.2:** revocation races, 2-pod/2-process qualification, PostgreSQL RLS/grants, step-up MFA for critical approvals, expiry/replay tests; enforce required CI checks on main. No production S2 until all gates pass.

**Release judgement:** SEC-R1.0 hardens existing internal services but **does not grant production-readiness** to remaining domain skeletons.
