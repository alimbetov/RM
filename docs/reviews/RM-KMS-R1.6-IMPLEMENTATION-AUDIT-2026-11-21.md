# RM-KMS R1.6 — аудит реализации и CI Qualification

**Дата:** 2026-11-21  
**Ветка:** `feat/rm-kms-r1-implementation`  
**PR:** https://github.com/alimbetov/RM/pull/1 (draft, не merged)  
**Проверенный commit:** `d30644fde8c03ef05e02506be37172761742dfdb`  
**GitHub Actions:** https://github.com/alimbetov/RM/actions/runs/44868803264  
**Итог:** **R1.6 BASELINE GREEN / FULL KMS QUALIFICATION HOLD**.

## 1. Доказанные результаты

В реальных логах GitHub Actions run #8:
- `h2-smoke` Java 21 / `dev` / H2 memory: **BUILD SUCCESS**, `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`.
- `postgres-integration` Java 21 / `test` / isolated PostgreSQL 17: **BUILD SUCCESS**, `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`.
- В каждой СУБД Liquibase **5 changesets**, повторная инициализация: **0 новых changesets**, JPA `ddl-auto=validate` прошёл.
- Исправлен установленный типовой дефект `active_slot/ready_slot` `CHAR(1)` ↔ Hibernate `VARCHAR(1)` через `@JdbcTypeCode(SqlTypes.CHAR)`.
- Базовые AES-256-GCM round-trip, UTF-8, изменение nonce/tag, wrong tenant/field/id, wrap/unwrap и SQL CHECK тестировались.
- `14/14` — общее число тестов в каждом задании (не 14 из 112 полностью реализованных в одной среде); матрица из 112 сценариев остаётся планом.

## 2. Найденные проблемы и пробелы

| ID | Приоритет | Наблюдение | Риск / необходимо |
|---|---|---|---|
| AUD-01 | P0 для production | `TenantCryptoService.decrypt()` может получить ключ из Caffeine без проверки текущего статуса generation при cache hit | После будущего REVOKED cached DEK может оставаться используемым; до реализации revoke/promotion блокировать эти операции и квалифицировать транзакционную invalidation/admission fence |
| AUD-02 | P0 для production | Нет save/load codec для EncryptedValue, CRM encrypted fields и round-trip через БД | Криптография проверена только в памяти; сделать binary envelope codec + persisted entity E2E |
| AUD-03 | P0 для production | `security.tenant_key` (старые версии) и `security.kms_tenant_key` существуют одновременно; live migration отсутствует | Существующим tenant заблокирован provisioning нового DEK; нужен нетеряющий legacy migration и replay tests, в т.ч. >3 поколений |
| AUD-04 | P0 для rotation | `rotate()` намеренно UnsupportedOperationException, нет nightly batch/recovery/Archive KEK | Правильно оставлено HOLD; не заявлять функциональность ротации/восстановления |
| AUD-05 | P1 | `provision()` делает `existsById` и затем `saveAndFlush` без атомарного upsert/retry | Одновременные регистрации одного tenant могут приводить к PK conflict; обеспечить идемпотентный retry в новой транзакции и concurrent test |
| AUD-06 | P1 | `encrypt()` вызывает `keys.active()` перед каждым encrypt | Каждое шифрование читает KMS-строку из БД; cache hit не исключает SQL метаданных ACTIVE; benchmark нужен |
| AUD-07 | P1 | `resolve()` в encrypt не сверяет `wrappingKeyId`, `resolveUncached()` использует hardcoded `root-v1` | При будущем root KEK rotation нужен key-id→versioned KEK resolver, единая проверка для HIT/MISS |
| AUD-08 | P1 | `EncryptedValue` — record из byte[] без постоянного codec/согласованного максимума CRM полей | На следующем этапе определить bounded binary representation и DB storage contract |
| AUD-09 | P1 | В SQL есть CHECK active/ready, но нет CHECK `next_generation > max(existing generations)` | Некорректное ручное состояние может нарушить monotonic generation; добавить отдельный Liquibase changeset, не править применённые |
| AUD-10 | P1 | `KeyWrappingService.rootKey()` декодирует base64 при каждом wrap/unwrap; корневой секрет хранится как String | Не влияет на успешность, но увеличивает аллокации; версия KEK и config validation должны стать единым config component |
| AUD-11 | P1 | Нет независимых криптографических KAT, coverage JaCoCo, property-based, crypto failure injection, parallel tests | 14 GREEN тестов не удовлетворяют QUAL matrix; добавить настоящие gates поэтапно |
| AUD-12 | P2 | Archive table не HASH partitioned | Не блокирует базовый R1.6, но будущая архивная qualification должна тестировать PG-specific partitioning либо обоснованно отказаться |
| AUD-13 | P1 | CI использует 2 задания с одинаковым Maven suite вместо tag-specific smoke/integration classes | Пока корректная проверка запуска приложения в двух БД, но ещё нет тестов PostgreSQL locking, migration, recovery |

## 3. Аудит безопасного дизайна

**Accept:** AES-256-GCM, SecureRandom nonce 96 бит, authentication tag 128 бит, AAD tenant/entity/id/field/generation, защитные копии массивов в `EncryptedValue`, три слота с версиями, Caffeine keyed by (tenantId,generation), legacy source preserved.

**HOLD:** обычный доступ к archived/REVOKED DEK, key promotion/reuse, KEK rotation, nightly migration, recovery, legacy cutover, multi-JVM. Штатный `encrypt/decrypt` использовать только в ограниченном UAT с надёжной проверкой object authorization на вызывающей стороне.

## 4. Технический план следующего среза

1. `R1.6A` закрыть audit-05 и audit-09: concurrent provisioning, new SQL CHECK changeset, H2+PG CI.
2. `R1.6B` versioned encrypted envelope codec, JPA persistence round-trip, 1k property/integrity tests и KAT; PostgreSQL E2E.
3. `R1.7` единый KEK resolver и Caffeine state admission design; no enabled rotation.
4. `R1.8` legacy data-safe migration с несколькими поколениями и доказательством обратимости/backup.
5. Только после этого внедрять nightly/rotate/recovery вместе с transactional fence, full scan и DR tests.

## 5. Итоговое gate-решение

- H2 schema, JPA validate, базовый AES-GCM и PostgreSQL compatibility: **PASS** на проверенном commit.
- R1.6 **базовый technical smoke: PASS**.
- Полное соответствие `RM-KMS-SPEC-R1` и `RM-KMS-QUAL-R1`: **NOT PROVEN**.
- Авто-ротация, архивный restore, CRM production шифрование: **HOLD**.
- Merge в main: **HOLD до устранения P0, тестов storage + legacy migration и явного review**.

Все замечания — исходя из фактического кодового среза; runtime proof ограничен 14 тестами в каждой БД, а не всей матрицей.
