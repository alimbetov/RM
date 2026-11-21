# RM DOMAIN-REVIEW-R1 — технический аудит и ревизия 15 доменов

**Дата:** 2026-11-21; **ветка:** `feat/rm-kms-r1-implementation`. **Статус:** архитектурный аудит завершён; production = HOLD до закрытия P0.
Аудит основан на фактических Java, SQL/Liquibase и тестах. Успешная CI не равна завершённой предметной реализации.

## 1. Матрица зрелости

| Домен | Фактический код | Persistence | Зрелость |
|---|---|---|---|
| IAM | UserAccount, Workspace, Membership, email identity/challenge | IAM tables + email outbox | foundation, нет OIDC/browser user admission |
| ORG | OrganizationEntity расположен в iam; OrganizationLocationEntity/Service в geo; новый record в organization | org_organization/org_location | foundation; неверная ownership |
| GEO | KATO entity/directory | ref_kato_territory, неполные 100 записей | foundation |
| FILES | FileObject record | нет | contract only |
| CRM | CrmCustomerService | crm.customer/contact, crm_pii, consent | preliminary vertical |
| CATALOG | CatalogItem record | нет; store_offer — временная цена | contract only |
| SALES | OrderState + AutoOrderService в autoorder | sales_order/line/receipt/status_event | preliminary vertical |
| INVENTORY | StockPosition record | нет | contract only |
| FINANCE | Money record | нет | contract only |
| MARKETING | CampaignState enum | нет | contract only |
| TASKS | TaskState enum | нет | contract only |
| AUDIT | AuditAction record | нет | contract only |
| KMS | Existing crypto/key/fencing services | security.kms_tenant_key + archive + legacy | foundation |
| SUBSCRIPTION | PlanEntitlement record | нет | contract only |
| STOREFRONT | StorefrontSettings record; checkout via autoorder | crm.store_front/store_offer | preliminary vertical |

**Заключение:** все 15 модулей имеют package/contract, но их persistence, API, security and business workflows не завершены. AUTOORDER — orchestration, не отдельный 16-й владелец данных.

## 2. Critical findings

### P0 — нельзя открывать публичные операции до исправления

- **AUTHZ:** `geo.OrganizationLocationService.create(workspaceId,...)` проверяет существование и тип tenant, **не** активного actor/membership/permission. Публичный API запрещён до введения доверенного principal-derived authorization и tenant-B negative tests.
- **AUTOORDER race:** `AutoOrderService.checkout()` проверяет idempotency receipt, затем без multi-pod lock/loser recovery создаёт user/email identity/order/receipt. Конкурирующие запросы могут вернуть DB constraint failure вместо того же orderId. Требуются PostgreSQL transactional keyed claim/locking, concurrent tests и conflict recovery.
- **IAM email legacy:** `iam_user_account.email_normalized` содержит открытый email, новая `iam_email_identity` шифрует и HMAC-индексирует email. Есть два пути идентификации; нужна миграция единственного глобального owner с безопасной дедупликацией и ключевой политикой. Нормализация local part к lowercase может слить отличающиеся адреса: требуется явная и тестируемая email normalization policy.
- **ACCOUNT SECURITY:** не реализованы подтверждённая OIDC/browser session, привязка subject, SMTP sender, outbox worker, throttle и anti-enumeration. `buyerOrders(userId)` безопасен только в вызове с идентификатором из доверенной аутентификации. Не открывать как API с переданным клиентом UUID.
- **FILES:** KMS encrypted binary/admission, access checks, malware and restore отсутствуют; private upload недоступен как готовая функция.

### P1 — обязательная доменная ревизия

- **Owner ORG/GEO:** OrganizationLocationEntity/Service в GEO и OrganizationEntity в IAM, хотя location/organization принадлежат ORG. Перенести постепенно, без изменения UUID, FK, исторических Liquibase checksum; GEO владеет только КАТО и validation port.
- **Owner SALES/STOREFRONT:** временные `crm.store_offer` и `crm.sales_order` реализованы в AutoOrderService. Привести к модульным application ports, store_offer заменить publish-contract каталога при завершении CATALOG.
- **DomainModule readiness:** Boolean `hasExistingImplementation` ошибочно маркировал SALES/STOREFRONT как реализованные. Удалён: реестр описывает **ownership**, зрелость фиксируется отдельно в этом аудите и implementation plan.
- **Double prefix:** ранее `DomainModule.FILES.table("file_object")` давал `file_file_object` и тест считал это нормой. Исправлено: reject double-prefix и имён длиннее 63 ASCII байт; добавлен отрицательный тест.
- **Names:** legacy `crm.customer`, `crm.contact`, `crm_pii.customer_secret`, `ref_kato_territory` не соответствуют итоговому префиксу. Выполнить только новую миграцию Liquibase + SQL/JPA update, не править уже исполненные changesets.
- **Optional buyer profile:** AUTOORDER не обязан создавать CRM Customer/Contact; существующие CRM person/contact service требует name — не использовать его в email-only checkout. Отдельный optional buyer profile с tenant-level encrypted PII.
- **Sales order state:** нет полноценной state machine, stock reservation, price revision, tax, fulfillment/delivery and legal snapshot. Нет достаточных контрактов для production order.
- **IAM/PII:** `crm_pii` схема без верифицированного отдельного DB grant не доказывает permission isolation. KMS rotation и archive hold отдельно.
- **I18N:** четыре языка `kk/ru/en/zh-Hans` зафиксированы, перевод ресурсов и UI не реализован.
- **Soft delete:** статус ARCHIVED и методика privacy erasure описаны, unified lifecycle implementation нет. Immutable events не должны удаляться скрыто.
- **Partitioning:** только ТЗ, физических PostgreSQL partitions не создано. Composite PK/FK и region/time benchmark обязательны до развёртывания.

## 3. Revised domain ownership

| Aggregate | Sole owning module | External integration |
|---|---|---|
| UserAccount, EmailIdentity, VerificationChallenge, Tenant, Membership | IAM | authorized principal and identity port |
| Organization, OrganizationLocation | ORG | GEO KATO validation; IAM membership |
| KatoTerritory | GEO | code, parent and region |
| FileObject, FileAttachment | FILES | fileId, authorization by owner domain |
| Customer, Contact, Consent | CRM | tenant-scoped customerId, optional buyer profile |
| CatalogItem, SKU, effective Price | CATALOG | published offer snapshot |
| CustomerOrder, Lines, OrderStatusEvent, Estimate | SALES | immutable order snapshot, transitions |
| StockBalance, Movement, Reservation | INVENTORY | location and SKU |
| Invoice, Payment, Allocation | FINANCE | currency + immutable posting |
| Campaign, TemplateRevision, DeliveryEvent | MARKETING | purpose-specific consent |
| Task, Activity | TASKS | stable reference IDs |
| AuditEvent | AUDIT | safe metadata, no raw PII |
| TenantKey | KMS | tenant-scoped crypto, no exported DEK |
| Plan, Subscription, Entitlement | SUBSCRIPTION | entitlements separate from IAM roles |
| Storefront, Settings, Public Page | STOREFRONT | tenant slug + localizations |

## 4. Revised implementation gates

1. **QUAL-RM-DOMAIN-R1.1 / security:** authenticated actor on ORG mutations, email identity single-owner migration, multi-pod idempotent AUTOORDER with durable sender/limiting; new PostgreSQL race and cross-tenant negative tests.
2. **QUAL-RM-DOMAIN-R1.2 / structure:** move ORG-owned classes out of geo/iam with stable schema, enforce package direction tests; preserve encrypted AAD, UUIDs and Liquibase history.
3. **QUAL-RM-DOMAIN-R1.3 / business:** FILES→CATALOG→SALES→INVENTORY→FINANCE vertical slices; MARKETING/TASKS/AUDIT and subscriptions after. Each vertical adds DDL, service, permissions, H2+PostgreSQL tests.
4. **QUAL-RM-DOMAIN-R2 / ops:** upload RustFS migration, 4-locale E2E, privacy erasure and optional partitions following 1m/5m/20m benchmarks, p95/p99 and backup qualification.

**Not a production readiness claim:** the registry/record tests prove isolated invariants, not complete module behavior or authorization.
