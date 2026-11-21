# QUAL-RM-DOMAIN-R1.2 — стандартизация IAM Email Identity / AUTOORDER

**Дата:** 2026-11-21. **Ветка:** `feat/rm-kms-r1-implementation`. **Статус:** HARDENING IN PROGRESS, публичный admission и production = HOLD.
**Связанные документы:** [R1.1](QUAL-RM-DOMAIN-R1.1.md), [AUTOORDER ТЗ](../spec/RM-STOREFRONT-AUTOORDER-R1.md), [аудит](../reviews/RM-DOMAIN-TECHNICAL-AUDIT-2026-11-21.md).

## 1. Единый ownership и код

| Объект/правило | Владелец | Физическое размещение |
|---|---|---|
| Глобальный пользователь, email identity и challenge | IAM | `iam_user_account`, `iam_email_identity`, `iam_email_verification_challenge` |
| Глобальное AEAD/HMAC email | IAM | `kz.rm.iam.EmailIdentityCrypto` |
| Email find-or-provision, одноразовая верификация, durable outbox | IAM | `kz.rm.iam.EmailIdentityService`, `iam_email_delivery_outbox` |
| Витрина, её состояние/slug | STOREFRONT | `crm.store_front` (legacy physical schema, `store_` prefix) |
| Заказ, строки, receipt, status event | SALES | `crm.sales_*` |
| Checkout orchestration/связь заказа с подтверждённым покупателем | Application layer AUTOORDER | `kz.rm.autoorder.AutoOrderService` |
| Покупательская карточка и контакты | CRM, добровольные | checkout **не** создаёт записи `crm.customer/contact` |

`EmailIdentityService.findOrProvision` выполняет глобальный lookup под DB-level row lock; возвращает только IAM Identity metadata. Challenge создаётся и помещается в БД/outbox одной транзакцией с заказом. `verifyEmail` отмечает challenge использованным и защищает от использования suspended/deleted учётной записи.

**Proof vs login:** подтверждение владения email не выдаёт ни session, ни право читать историю заказов; REST adapter ОБЯЗАН получать userId из проверенного OIDC principal. Ни одно публичное REST API для этого слоя не создано.

## 2. Сохраняемые инварианты

1. Email всегда обязателен для AUTOORDER; полное имя, телефон, CRM-карточка и дополнительные контакты — опциональны; адрес может быть нужен при конкретной доставке.
2. Покупатель имеет глобальный userId; `tenant_id` заказа — продавца, а не покупателя. `ORG_MEMBER` продавца не выдаётся автоматически.
3. Один seller/Idempotency-Key создаёт один заказ; один глобальный email lookup создаёт единственную IAM identity. Используются фиксированные lock stripes для первых нагрузок, не обещание бесконечного throughput.
4. Account status `SUSPENDED`/`DELETED` блокирует email verification и новые checkout operations; PENDING → ACTIVE допустим только после доказанной email-верификации.
5. Клиент не передаёт userId/tenantId как authority. Номер заказа и slug не авторизуют доступ к персональным данным.
6. Ключи GLOBAL IAM email AEAD/HMAC независимы от tenant KMS; шифрование и lookup не должны использовать один и тот же секрет.
7. `kk/ru/en/zh-Hans` — locale для UI, не identity key. Политика email normalisation требует отдельной migration qualification.
8. Опубликованный заказ имеет immutable price/currency snapshot, статус меняется только по разрешённой state machine; в R1 SALES полнота ещё не достигнута.

## 3. Открытые P0 / технический долг

### A. Legacy plaintext и нормализация

Текущая `iam_user_account.email_normalized` может содержать plaintext; новый `iam_email_identity` — ciphertext и keyed hash. **Не удалять старый столбец или перегенерировать HMAC ключи прямо в feature-миграции без backfill.** Требуется отдельная процедура с dry-run, проверкой дубликатов, сохранением `external_subject`, ссылок заказов и KMS integrity:

1. Inventory всех legacy email с агрегированной статистикой; никаких PII в report/logs.
2. Определить canonicalisation policy. Сейчас lowercase применяется к local-part (не всегда гарантирует идентичность mailbox), поэтому изменение политики требует versioning `lookup_policy_version` и backfill, а не молчаливого изменения алгоритма.
3. Подготовить версионированные IAM HMAC/AES secrets в secret manager; проверить multi-pod consistency и backup restore.
4. Создать или сопоставить единственную IAM email identity, зашифровать legacy email, сверить `userId`, unique collision cases и точность.
5. Только после shadow-read/dual-read проверки и rollback gates убрать чтение/возможность записывать `email_normalized`, а затем очистить старые plaintext записи по отдельной миграции.
6. Негативные тесты: конфликт case/Unicode/email, same-email two tenants, suspended/deleted identity, corrupted ciphertext, wrong AEAD key, key unavailable, concurrent migration vs new checkout.

### B. Outbox delivery / OIDC

- Не реализован реальный email sender/SMTP integration. Нужен worker с `lease_until`, `lease_owner`, `fence_token`, `attempt_count`, backoff, retries, deduplication и fail-closed expiry. Не возвращать токен подтверждения в HTTP.
- Нужна защита от спама, enumeration, грубой силы, challenge replay и массовых регистраций; rate limits per IP / lookup hash. Не объявлять безопасность только по one-time token.
- Нужны OIDC/PKCE cookie BFF или строгая API session-validation, связывание `external_subject` с accountId и обнаружение блокировки при каждом access.
- Шифрованные токены/почтовые адреса после завершения delivery/expiry должны очищаться по retention.
- Публичный checkout/verify/buyer-orders endpoints **не включать** до завершения admission tests.

### C. Большие домены

Физический FILES ещё не реализован. CATALOG price source временно `crm.store_offer`. Для SALES нужны state machine, versioned price/terms, INVENTORY reservation, delivery, reason-codes. Партиции PostgreSQL только после реальных нагрузочных испытаний, без обязательных partitions для IAM.

## 4. Квалификация

- H2 и PostgreSQL интеграция должна проходить на точном head commit.
- Негативные tests на suspended account и concurrent autoorder; IAM encrypted identity round-trip.
- Двухпроцессный тест под 2 pods нужен отдельно: текущие concurrency tests используют 2 connections in one process.
- Нет утверждения production-ready, пока P0 остаются открытыми.

**Следующий порядок:** IAM legacy privacy plan/backfill → verify sender worker + security throttle → OIDC account claim → 2-pod load and permission tests → только тогда public REST endpoints.
