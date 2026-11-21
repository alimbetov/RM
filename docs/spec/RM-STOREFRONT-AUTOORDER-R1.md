# RM-STOREFRONT-AUTOORDER-R1 — витрина организации, email-first автозаказ и отслеживание

**Статус:** PROPOSED / на утверждение перед реализацией. **Дата:** 2026-11-21.
**Ветка:** `feat/rm-kms-r1-implementation`. Документ проектный; новые таблицы/REST по этому ТЗ не созданы.
**Связанные ТЗ:** [implementation baseline](RM-IMPLEMENTATION-BASELINE-R1.md), [domain map](RM-DOMAIN-MAP-R1.md), [i18n](RM-I18N-R1.md), [fields/privacy/delete](RM-DOMAIN-FIELDS-PRIVACY-DELETE-R1.md), [files](RM-FILES-R1.md).

## 1. Обязательные продуктовые правила

- Каждая организация-продавец (`tenant_id UUID`) может настроить публичную витрину `/store/{slug}`, каталог, корзину, оформление заказа и отслеживание состояния.
- **В любом автозаказе email покупателя обязателен**, проверяется формат/ограничение длины и нормализуется по согласованным правилам; телефон опционален до отдельного правила способа доставки.
- **Карточка клиента и дополнительные контакты необязательны при автозаказе.** Минимальный checkout: email + товары + явно выбранный способ исполнения/условия; имя, фамилия, телефон, дополнительные адреса, контактные лица и описание компании покупатель может добавить после подтверждения email в личном кабинете. Метод доставки может требовать адрес как данные конкретного заказа, но это не делает заполнение постоянной CRM-карточки обязательным.
- **Если пользователя с проверенным email нет, система автоматически создаёт учётную запись** при оформлении, в состоянии `PENDING_VERIFICATION`. Не заставлять покупателя проходить ручную регистрацию до оформления.
- **Если проверенная учётная запись существует**, не создавать дубликат и **не выдавать доступ к ней или заказам только потому, что посетитель ввёл email**. После подтверждения владения email/login можно привязать новый заказ к существующему user ID.
- Первый доступ к кабинету, сведениям о заказах и персональным данным — только **после подтверждения владения email/аутентификации**. Наличие email или знания номера заказа не даёт право чтения.
- Заказ создаётся в tenant продавца. `tenant_id` не является buyer ID, префиксом КАТО или public security secret. У покупателя один глобальный `iam_user_account.id` (`user_id`), покупки у нескольких tenants связаны с одним подтверждённым пользователем; покупатель **не становится `ORG_MEMBER` продавца**.
- Не отправлять рекламные письма без отдельного согласия; подтверждение email, создание заказа и уведомления о его состоянии — транзакционные сообщения с отдельной политикой.

## 2. Архитектура: новые модульные таблицы с префиксами

| Модуль | Новая таблица (целевое имя) | Важные поля |
|---|---|---|
| STOREFRONT `store_` | `crm.store_front` | `tenant_id`, `id`, `slug` unique, `visibility` DRAFT/PUBLISHED/PAUSED, `logo_file_id`, `row_version`, `default_locale` |
| STOREFRONT | `crm.store_setting` | `tenant_id`, `store_id`, `accept_orders`, `pickup_enabled`, `delivery_enabled`, `email_required=TRUE`, `auto_provision_buyer=TRUE`, `checkout_policy_revision` |
| STOREFRONT | `crm.store_front_i18n` | `(tenant_id,store_id,locale)` unique, `display_name`, `description` |
| IAM `iam_` | `iam_user_email_identity` (проектный новый агрегат) | `user_id`, email **ciphertext**, tenant-independent lookup keyed blind index, `verified_at`, identity state; see security note |
| IAM | `iam_email_verification_challenge` | `user_id`, hashed single-use token, `expires_at`, `consumed_at`, throttling fields |
| CRM `crm_` | `crm.crm_buyer_link` | `tenant_id`, `customer_id`, `buyer_user_id`, `verified_at`; seller-local identity link |
| SALES `sales_` | `crm.sales_order` / `crm.sales_order_line` | seller `tenant_id`, buyer link (nullable while unverified), `order_status`, immutable item/price/tax/currency/region snapshot; email protected separately |
| SALES | `crm.sales_checkout_intent` (when required for unverified existing email) | `tenant_id`, `id`, `order_id`, protected normalized email / keyed lookup, `verification_state`, expiry; **NOT publicly queryable** |
| SALES | `crm.sales_order_status_event` | `tenant_id`, `order_id`, `occurred_at`, status and safe provenance; immutable |
| FILES `file_` | `crm.file_object` and `crm.file_attachment` | tenant-scoped approved product photos and private order attachments |

**Important nuance:** global account email uniqueness must be based on a **global IAM lookup/index**, with a separately managed purpose-specific keyed HMAC (or securely designed identity provider lookup) and collision-safe normalization. This is **not** the same as tenant-specific CRM contact blind indexes, and does not allow one seller to search buyers of another seller. IAM is sole owner of identity/account lookup; storefront must call a narrow IAM port without exposing account-existence information.

**Do not store plaintext email in `store_setting`, order, status events, public URLs, logs or error bodies.** Order confirmation delivery uses the protected email reference through internal IAM/notification service.

## 3. Checkout, existing-user and new-user flows

### 3.1. Shared

1. Anonymous visitor loads a published store by `slug`, sees only published products and approved public media, composes cart.
2. `POST /api/public/stores/{slug}/checkout` accepts mandatory email, cart, delivery/pickup choice, checkout terms version, idempotency key. **Ignore all client-provided `tenant_id`, `user_id`, SKU prices, order status.**
3. Server validates active seller, configured fulfillment, catalog publication, stock policy, current price/currency, item limits; recomputes totals with `BigDecimal`, snapshots names/price/tax/locale, and creates seller-scoped placed order with tracking gated.
4. In one orchestrated application use case, IAM is asked to **find-or-provision** by normalized email. It may return an authenticated user ID or an opaque pending identity reference. The public response never says whether that email belongs to an existing account.
5. Use transactional outbox for verify/sign-in and order receipt notifications; retries must be idempotent and must not create duplicate users/orders. Email provider failure does not silently mark `verified`.

### 3.2. New email / no account

- IAM creates `PENDING_VERIFICATION` account (or reserved identity) with secure verified-challenge flow; no tenant membership granted.
- Create order and keep its link in a protected pending state. No customer dashboard access before successful verification; optional staff-only access via seller membership.
- Send a single-use, short-lived, rate-limited confirmation/sign-in challenge, store only hash of token; protect against replay and expiration.
- On verification, mark email verified, activate account subject to IAM onboarding rules and atomically bind order/customer to exact authenticated user ID; only then allow buyer tracking.

### 3.3. Existing email / account

- Do not auto-authenticate, reset credentials, create a new user or attach a guest order to an existing account solely from a supplied email.
- Issue the **same generic public checkout response** and a secure sign-in/link-challenge to the address (subject to abuse controls). Order may exist in pending-association state until verified session confirms the same identity; seller sees the order but buyer cannot access it yet.
- Once authenticated with proof of email possession, complete buyer link; use stable IAM UUID. Avoid duplicate `crm_customer` links when same buyer already belongs to seller tenant.

### 3.4. Failures, duplicates and privacy

- Require `Idempotency-Key` scoped to seller tenant and checkout requester; replay returns the same order result without duplicating users, order lines or email sends. Reuse with changed payload is a conflict.
- If IAM account lookup or email transport is down, define retryable order state and durable outbox; never claim access/verification completed.
- No user enumeration via response/status code/timing/sending behavior beyond reasonable practical controls. Per-IP + per-email throttling, CAPTCHA/abuse controls where appropriate, CSRF for cookie-based checkout, no plaintext tokens in database or logs.
- No raw order UUID or public sequential number can authorize access. Use signed, expiring, scope-limited link only as part of an authenticated/verified verification process; never expose complete order solely through bearer receipt link by default.

## 4. Buyer account and seller access

- `/account/orders`: list all orders for authenticated `buyer_user_id` across sellers using a dedicated query/read-model with strict account identity and per-seller projection; do not accidentally enable cross-tenant CRM browsing.
- `/store/{slug}/account/orders`: only orders of that seller and that authenticated buyer.
- Order details: check `order.tenant_id == store.tenant_id` and `order.buyer_user_id == authenticatedUser.id`; seller staff access separately checks active seller membership + authorized permission.
- `PLATFORM_AUDITOR` metadata role never automatically decrypts order personal details, contact emails or addresses.
- Status displayed to buyer: PLACED, CONFIRMED, PREPARING, READY_FOR_PICKUP, OUT_FOR_DELIVERY, COMPLETED, CANCELLED/REJECTED; internal stock/margin/fraud/internal notes remain hidden. Transactional order notifications only to verified channel or safely bounded unverified checkout email flow.
- Store may be PAUSED while historical buyer tracking remains allowed. Buyer account closure does not automatically destroy commercial records under legal retention.

## 5. Store settings UI

**Organization settings → Storefront** includes:
- page visibility, public slug, logo/cover files, business name/description in `kk/ru/en/zh-Hans`;
- catalog visibility, enable/disable checkout, published assortment, item ordering;
- fulfillment regions `region_zone_id`, pickup locations `location_id`, delivery rules and accepted currency;
- payment options: initially pay-on-delivery/manual bank transfer; online acquiring separate provider integration;
- **mandatory locked business controls:** email required and auto-provision buyer are **always enabled in AutoOrder mode**, not switches to bypass;
- approved order terms/policy revision for each supported document locale, customer notice, order tracking visibility, transactional notifications;
- security: staff rights, rate limits, limits on order quantity/amount, audit of config updates.

Store settings are one-per-seller-tenant, unpartitioned master data with unique `slug`. Config write from authenticated seller admin only; public read allowlisted.

## 6. Four languages, soft delete, files, partitioning

- UI messages: `kk,ru,en,zh-Hans`; `locale VARCHAR(12)`. Missing catalog translation uses declared source locale; order terms/consent require expressly approved language text, no silent fallback.
- Buyer identity, email and address are PII; encrypted tenant-scoped for CRM/order delivery (IAM global email protection separately). Country/regional code independent of language.
- `store_front` may be PAUSED/CLOSED; customer and catalog archive hides from new orders, but confirmed orders and status events are preserved and never soft-deleted. Account privacy erasure is distinct from order retention.
- FILES uses stable `file_id`, local disk initially then RustFS; public product images must be explicitly approved. Personal files need KMS-backed encryption before production.
- Order event history may use `RANGE(occurred_at)→HASH(tenant_id)` after PostgreSQL profiling. Store settings and IAM identity are unpartitioned R1. Query indexes: `(tenant_id,buyer_user_id,created_at DESC)`, `(buyer_user_id,created_at DESC)` where actual read access and isolation are enforced.

## 7. Acceptance matrix

1. Mandatory email: absent/blank/invalid email fails validation **before order creation**; no bypass by settings.
2. New email: account automatically provisioned in `PENDING_VERIFICATION`; order created exactly once and cannot be read before verification.
3. Existing verified account: no duplicate user, no implicit attachment/access until authenticated proof; same non-enumerating response.
4. Email verified challenge: token one-time, expires, rate-limited; wrong, expired and replayed tokens fail; seller cannot manipulate verification.
5. Two sellers and one user: one global `user_id`, seller-local customer links, distinct tenant-scoped orders, accurate permitted cross-seller buyer history.
6. Guessed UUID/order number/tenant slug cannot expose other buyers' order, phone, email, address or document.
7. Duplicate checkout, timeouts and concurrent calls yield one order/user/link; outbox delivery retries don't duplicate side effects.
8. Price, currency, item availability and delivery region recalculated on server; immutable order snapshot after catalog changes.
9. Store PAUSED rejects checkout but still allows the buyer to track previously placed orders.
10. Four locales incl. `zh-Hans` render product labels and statuses; legal terms approved; no PII leakage in UI/errors/logs.
11. Unsubscribe/withdrawn marketing consent does not block strictly necessary order communications, and does not grant marketing permission.
12. Integration CI: PostgreSQL + H2 for core flows, PostgreSQL concurrency, 2-pod race/authorization; no claim of implementation until passed.

## 8. Implementation stages

1. **STORE-R1.0**: approve public store config and seller/buyer domain boundaries, unique slug, product publication contract.
2. **AUTOORDER-R1.0**: secure IAM email identity and verify/find-or-provision port, normalized duplicate handling, idempotent order placement + outbox.
3. **BUYER-R1.0**: authenticated buyer dashboard, seller-specific and cross-seller history, status feed; unauthorized read negatives.
4. **STORE-I18N-R1.0**: four-language storefront and legal approved terms.
5. **STORE-PERF-R2**: order event indexes and conditional partitioning.

## 9. AUTOORDER-R1.0A — минимальная регистрация и добровольная карточка покупателя

**Подтверждённое UX-требование:** покупатель не обязан заполнять `Customer`/`Contact` при оформлении. Единственный обязательный идентификационный атрибут — **email**. Имя, телефон, дополнительные контакты и профиль организации остаются добровольными; специальные обязательные реквизиты могут потребоваться **только для конкретного исполнения заказа** (адрес доставки, ФИО получателя при выбранной службе доставки), с объяснением причины.

1. `iam_user_account` — глобальная учётная запись, `status=PENDING` до подтверждения email. Запрещено делать пользовательскую `display_name` обязательным полем публичной формы; технический placeholder в legacy IAM не является реальным ФИО и не должен попадать в счета/рассылки.
2. `crm.crm_buyer_link` — связь `tenant_id` продавца и глобального `buyer_user_id`; link может появиться после верификации, но не является обязательной CRM-карточкой. `crm.crm_customer` создаётся по инициативе покупателя/при явно согласованной бизнес-процедуре, **не автоматически из email без подтверждения**.
3. `sales_order` содержит `buyer_user_id` **только после доказанной привязки**, либо `pending_buyer_identity_id` до неё. Не копировать email в открытый SQL заказа и не приписывать заказ аккаунту по одному email.
4. `buyer_profile` — опциональный профиль; `display_name`, `phone`, `delivery_addresses`, `additional_contacts` зашифрованы и заполняются позже. Дополнительные контактные лица без необходимости или согласия не создавать.
5. Продажи/отслеживание доступны после подтверждения почты; у одного пользователя может быть много заказов из разных seller tenants. Статус PENDING email не даёт ни ORG_MEMBER, ни права чтения истории.
6. `marketing_consent` по умолчанию FALSE. Письма подтверждения аккаунта, статусы заказа и реклама разделены.
7. **Приёмочные сценарии:** email-only checkout создаёт заказ без customer/contact; повтор с тем же ключом идемпотентен; существующий email не даёт доступа без доказательства владения; заполнение/редактирование профиля позднее не изменяет историческую цену и ID заказа; отсутствие имени/телефона не прерывает самовывоз.

**IAM boundary:** текущая `iam_user_account` использует `display_name NOT NULL` и `email_normalized` plaintext. Реализация AUTOORDER требует отдельной квалифицированной миграции IAM identity/verification и privacy, не использовать это старое поле для хранения открытого пользовательского email из автозаказа без пересмотра требований к шифрованию.

## 10. Фактический статус первой реализации (2026-11-21)

**Выполнено в ветке (code, не product-ready):**
- Liquibase `rm:0014-autoorder-r1-foundation` + `rm:0015-iam-email-verification-durable-delivery`: `iam_email_identity`, `iam_email_verification_challenge`, `crm.store_front`, `crm.store_offer` (временная серверная витринная цена до CATALOG), `crm.sales_order`, `crm.sales_order_line`, `crm.sales_checkout_receipt`, `crm.sales_order_status_event`.
- `EmailIdentityCrypto`: независимые global IAM AES-256-GCM / HMAC-SHA256 keys из secrets конфигурации, email-only normalization, PII не сохраняется plaintext в новых identity таблицах.
- `AutoOrderService`: серверный расчёт по offer, email-only создание `PENDING` пользователя, одноразовый email challenge с зашифрованным токеном и durable delivery-outbox `iam_email_delivery_outbox`, order placement + idempotency receipt, verified buyer history. CRM карточка и контакты **не создаются** автоматически.
- Existing IAM legacy `email_normalized` account lookup с повторным использованием стабильного `user_id`, но **без** доступа/авторизации до нового подтверждения email.
- H2 и PostgreSQL qualification tests (включая existing account и повторный заказ) через CI; фактический результат последнего коммита проверять перед заявлением GREEN.

**Не реализовано / blocked for public exposure:**
1. SMTP/email provider, безопасный sender/worker с lease/fencing, генерация/доставка корректных ссылок подтверждения и обработка ошибок. **Durable DB outbox уже создан в 0015**, но worker ещё отсутствует. `CheckoutResult` содержит только `orderId` и статус; verification token зашифрован и остаётся в IAM-таблицах, не возвращается из checkout и не должен логироваться.
2. Реальный OIDC/PKCE login/account claim для auto-provisioned пользователей и authenticated principal extraction. Подтверждение email само по себе ещё не заменяет полноценную аутентификацию.
3. Anti-abuse rate-limit, account enumeration checks, CAPTCHA, per-email attempt limits; durable retry/outbox on mail-provider failures.
4. Конкурентный multi-pod find-or-create/idempotency winner/loser/retry semantics и fencing; уникальные ключи предотвращают двойной commit при конфликте, но вызывающий слой обязан корректно обрабатывать гонки и повторять транзакцию.
5. CATALOG price publication / SKU / inventory / stock reservation, доставка, платежи, legal terms and consent revision snapshots, buyer address.
6. Рабочий REST API и UI витрины/кабинета; **не запускать публичный checkout** до завершения пунктов 1–5.
7. Добровольный buyer profile/contact CRUD, поле `preferred_locale`, encrypted PII for optional profile, optional delivery recipient per order.

**Доменное правило необязательного профиля:** `iam_user_account` создаётся по email; профиль покупателя ведётся отдельно и может вообще отсутствовать. Вне checkout пользователь позже может добавить `display_name`, `phone`, дополнительные контакты и адреса; они не участвуют в hash/idempotency и их отсутствие не препятствует `PLACED`.

**Следующая безопасная вертикаль:** AUTOORDER-R1.1 — durable verification email outbox + sender, OIDC account claim, suspended/deleted account checks и rate limiting, затем REST с чёткой границей публичного ответа и Q/A по многоподовой идемпотентности.
