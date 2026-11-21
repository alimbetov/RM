# QUAL-RM-DOMAIN-R1.1 — реализация P0 hardening и ревизия границ доменов

**Дата:** 2026-11-21. **Ветка:** `feat/rm-kms-r1-implementation` (без merge в main).
**Статус:** PARTIAL P0 CLOSURE / public admission HOLD. Отдельная CI-проверка каждого коммита указана в GitHub Actions.
**Основа:** [аудит доменов](../reviews/RM-DOMAIN-TECHNICAL-AUDIT-2026-11-21.md), [AUTOORDER](../spec/RM-STOREFRONT-AUTOORDER-R1.md).

## 1. Сделано в текущей вертикали

### P0-01 — авторизация ORG location

- Добавлен `kz.rm.iam.TenantAuthorization.requireOrganizationWriter(actorId,tenantId)`: проверяет существующего активного actor, активный organization tenant, членство в **точной организации** и роль OWNER/ADMIN/MANAGER.
- Изменена сигнатура `OrganizationLocationService.create(authenticatedActorId,tenantId,...)`: без активного writer нельзя изменять локации. Нельзя передавать произвольный actorId из HTTP-контроллера: adapter должен извлечь его из подтверждённой серверной principal.
- Отрицательные тесты для outsider, VIEWER, PERSONAL workspace, неподдерживаемого и удалённого КАТО.
- Отдельно **не реализован** OIDC filter для внешних вызовов; до этого публичные endpoints с таким API запрещены.

### P0-02 — DB-level конкурентная идемпотентность

- Liquibase **новый changeset 0016** создаёт `crm.sales_checkout_lock` и `iam_email_identity_lock`: 256 фиксированных lock stripes в каждой таблице; без динамических таблиц per tenant.
- `AutoOrderService.checkout` внутри PostgreSQL/H2 транзакции последовательно берёт `FOR UPDATE` на полосе `tenantId + idempotencyKey`, затем — на полосе нормализованного глобального email. Порядок блокировок фиксированный: checkout → IAM.
- После первого lock повторный idempotency receipt возвращает исходный order; после IAM lock создаётся или повторно используется только одна глобальная IAM identity. Один email у разных продавцов не порождает два аккаунта; order остаётся seller-owned по tenantId.
- Добавлены параллельные тесты двух независимых транзакций: одинаковый seller/key/email и разные продавцы/один email. Это **single-JVM multi-connection DB race**, а не полноценная 2-pod нагрузочная квалификация.
- 256 lock stripes обеспечивают **корректность**, но ограничивают параллелизм. Требуются throughput benchmark/p95,p99, deadlock/timeout tests прежде чем считать архитектуру масштабируемой до большого количества checkout/s. R2 может перейти к отдельным lock-claim rows/строгой reservation схеме.

### P1 — ORG/GEO ownership

- `OrganizationLocationEntity`, `OrganizationLocationRepository`, `OrganizationLocationService` физически перенесены из `kz.rm.geo` в `kz.rm.organization`. Схема `org_location`, поля, UUID и существующие Liquibase changesets **не менялись**.
- Добавлен узкий GEO port `KatoLocationPolicy`. ORG вызывает validation port, не GEO JPA repository.
- Отдельный перенос `OrganizationEntity` из `iam` в `organization` сознательно не объединён с этой задачей: потребует независимой проверки IAM внешних ссылок.

## 2. Открытые P0 до production

1. **OIDC/PKCE + principal-derived actor**, защищённый login/account claim и buyer order endpoint — ещё не реализованы. `buyerOrders(userId)` разрешён исключительно из доверенного adapter.
2. **Email delivery:** таблица durable outbox есть, но нет lease/fencing-aware sender, SMTP/provider/отказоустойчивого dispatch, rate-limit, replays/abuse mitigation, anti-enumeration tests.
3. **Global email IAM:** legacy `iam_user_account.email_normalized` всё ещё хранит plaintext email. Необходимо завершить privacy migration/backfill/unique global HMAC ownership с сохранением текущих OIDC subject и авторизаций. Определить политику local-part case-folding и key rotation.
4. **Sensitive FILES:** пока нет шифрованного физического хранилища, проверки owner permission, malware gate и backup recovery.
5. **2-pod qualification:** DB-level race покрывается двумя конкурентными соединениями в тесте, но тест реальных двух процессов и измерение p95/p99 не проведены.
6. **Order completeness:** inventory reservation, seller pricing revision, delivery and legal snapshots не реализованы; provisional `store_offer` должен уступить CATALOG.

## 3. Граница новой функциональности

- Новые публичные контроллеры checkout / verification / buyer-orders **не вводились**. Возврат verification token в ответе заказа запрещён, адрес/токен хранятся в защищённом IAM контуре.
- Email для заказа обязателен; карточка CRM/контакты покупателя остаются добровольными.
- Все 15 доменов зарегистрированы, но большинство остаётся на уровне первичных контрактов; ORM/persistence не создавались массово без предметного допуска.
- Java 21, Spring Boot, единый PostgreSQL, modular monolith; четыре locale `kk/ru/en/zh-Hans`, без языковых партиций.

## 4. Test gates

- `KatoTerritoryQualificationTest`: active writer succeeds, outsider/VIEWER/PERSONAL denied, KATO checks.
- `AutoOrderR1QualificationTest`: email-only checkout, encrypted identity, no default CRM profile, idempotency, verification proof, existing legacy user, **concurrent same-key and cross-tenant shared-email**.
- `DomainModuleQualificationTest`: no duplicate prefixes, correct money/inventory invariants.
- H2 and PostgreSQL: check GitHub Actions for **final** feature branch head. A GREEN result attests only these tests, not full P0/production completion.

## 5. Следующий необходимый рубеж

**QUAL-RM-DOMAIN-R1.2 (security admission):** real authenticated principal/OIDC, IAM single-owner email migration and account claim, verified email sender with lease/fencing/backoff and throttle; multi-pod race + throughput qualification; open HTTP only after all P0 gates are green.

**Non-goals of R1.1:** production-ready storefront, all modules ORM, dynamic partitions, RustFS delivery, synchronous SMTP while checkout transaction is open.
