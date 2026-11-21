# RM-KMS-SPEC-R1 — независимое техническое ревью / Architecture Gate

**Дата:** 2026-11-21  
**Исходный документ:** `docs/spec/RM-KMS-SPEC-R1.md` (ветка `docs/rm-kms-consolidated-spec`)  
**Результат:** **CONDITIONAL APPROVAL**. Начинать безопасные инкрементальные изменения схемы и Java-контрактов можно. Автоматическая ротация / освобождение слотов: **HOLD** до P0 acceptance.  
**Метод:** документарное ревью актуального GitHub ТЗ + существующих `TenantKeyEntity`, `TenantKeyService`, `db.changelog-master.sql`, `application-test.yml`. Выполнение Maven, подключение PostgreSQL, нагрузочные тесты, криптоэкспертиза **не проводились**.  
**Экспертная оценка:** архитектура 8.5/10, готовность к разработке около 75%, это субъективная оценка покрытия решений, не измеренный KPI.

## 1. Положительные решения

- Неизменяемая идентичность поколения (`tenant_id` + `generation`) вместо адресации только слотами A/B/C.
- Один ACTIVE, один возможный DECRYPT_ONLY и один READY; слот переиспользуется только со **свежим AES-256 DEK**.
- Envelope с GCM nonce/tag, версией и AAD, включающим tenant/entity/id/field/generation.
- Caffeine fast path в единственном экземпляре JVM. Отказ от plaintext fallback и обычного доступа к архивному DEK.
- Nightly batch <= 100 клиентов, одна транзакция на batch, rollback включает счётчики, рестарт из реального состояния.
- Исторические wrapped DEK для восстановления backup; недопустимость уничтожения старых поколений по одному `remaining=0`.
- Согласованное разделение dev=H2 smoke / test=PostgreSQL integration, Liquibase SQL-only.
- Префиксы по модулям; канонические имена KMS: `security.kms_tenant_key` и `security.kms_tenant_key_archive`.

## 2. Блокирующие замечания (P0)

### P0-01 — Generation admission ↔ транзакция бизнес-записи

Секция 20.5 отмечает риск, но не определяет завершённый протокол. Кэш ACTIVE может устареть между encrypt и commit бизнес-объекта. Простой JVM lock вокруг encrypt **не достаточен**, если отпущен до DB commit. Нужен минимальный протокол, гарантирующий, что:
- при переходе ACTIVE старые транзакции записи завершены либо отклонены;
- новые транзакции уже используют новый ACTIVE;
- запрет на новый старый ciphertext сохраняется на всех маршрутах записи, включая batch/import;
- после аварии и рестарта не остаётся несогласованного состояния.

**Минимально рекомендуемое MVP-решение:** один in-process per-tenant read/write admission lock с удержанием read lease **до завершения DB-транзакции** (или эквивалентный transactional DB barrier), а promotion получает write lease, ждёт завершения старых транзакций, сохраняет переключение, и лишь после commit открывает admission. Важно: actual implementation must bind lock lifetime to transaction afterCompletion, not method return. Недопустимо использовать это решение на нескольких экземплярах. Если инвариант не может быть доказан тестами, auto-promotion выключен.

PASS: конкурирующие запросы записи и promotion, delayed commit, rollback, crash/restart, отсутствие новых committed envelopes со старым generation после успешного promotion.

### P0-02 — Архивация и wrapping key id

Секции 4.2/5/20.7 рекомендуют отдельный Archive KEK, но нет точного обязательного контракта его переупаковки и восстановления. Зафиксировать:
- исходный DEK unwrap под штатным Root KEK, затем **re-wrap под Archive KEK** с собственным AAD purpose `rm:archived-dek`, `tenant_id`, `generation`, `archiveFormat`;
- `wrapping_key_id` архивной записи обязан однозначно указывать на доступную версию архивного KEK;
- запись архива и освобождение слота в **одной транзакции**, а внешняя копия архива и архивных KEK защищена, проверена restore test;
- отказ архивного KEK/restore → слот **не освобождать**.

PASS: restore v1 snapshot после переиспользования A, wrong Archive KEK error, missing Archive KEK error; запрет ordinary decrypt из архива.

### P0-03 — Нулевая статистика != отсутствие данных

Нужно оформить closed inventory of encrypted columns across CRM modules. Незарегистрированная защищённая сущность, сообщение, отложенное задание, импортированная копия делает скан неполным. Ввести минимальный registry в Java (не отдельную таблицу) с обязательным сканером ссылок generation для каждого модуля; закрыть admission старого поколения перед итоговой проверкой, сканировать в согласованном режиме, затем архивировать и безопасно освобождать слот. Миграция только 100 клиентов не покрывает автоматически независимые заказы, документы или сообщения.

PASS: при искусственной ссылке старого generation в другом модуле safe-reuse блокируется даже при `remaining=0`.

### P0-04 — Управление Root/Archive KEK

Секция 16 помечает как открытый вопрос. До автоматической потери доступа к поколениям выбрать и проверить режим доставки секретов, disaster recovery, восстановление KEK версий и недопустимость KEK rotation без сохранения старых обёрток. Root KEK в environment допустим для локального H2 smoke, но production должен иметь отдельно утверждённое хранение/бэкап и ограниченные привилегии.

PASS: backup + restore с историческим KEK, сценарий отсутствующего KEK fail closed.

## 3. Существенные замечания (P1)

### P1-01 — Несовпадение текущего кода и будущей схемы

Факт: `TenantKeyEntity` всё ещё `@Table(name="tenant_key",schema="security")` и `@EmbeddedId (tenant_id,version)`. Changelog `rm:0003-tenant-key` создаёт прежнюю `security.tenant_key`. Требуется **новая** Liquibase formatted-SQL migration, без изменения уже применённого changeset. Поскольку прежняя модель может иметь >3 старых версий на tenant, migration must archive extra generations **without loss** before reducing to three slots. Сделать rehearsal на H2 и PostgreSQL с synthetic existing data, включая >3 versions и повреждённую историю: fail closed без destructive fallback.

### P1-02 — Терминология и legacy aliases

В секции 6 ещё указана `security.tenant_key` вместо канонической `security.kms_tenant_key`; раньше встречаются описания про старую таблицу. Явно пометить old name as migration source, а новые DDL/JPA/query требуют `kms_`. Не допускать двух разных таблиц как параллельных активных источников истины.

### P1-03 — Размер батча: 100 клиентов != 100 SQL-строк

Контакты, дополнительные записи и связи могут резко увеличить обработку. Закрепить максимальную длительность транзакции и rollback/retry policy по измерениям. Ключевое требование — лимит **100 client aggregates**, не 100 updates. Учитывать optimistic lock и гарантировать, что при перезапуске тот же old envelope не учитывается повторно.

### P1-04 — Профиль `test` и CI

`application-test.yml` направляет контекст в реальный PostgreSQL. Spring automated tests часто активируют `test`, что может привести к неявной зависимости от локальной БД или destructive fixtures. Явно определить: unit tests без DB; H2 smoke через `dev`; PostgreSQL integration через `test` и уникальную disposable database/Testcontainers, не общую ручную БД. Любые очищающие тесты запрещены для shared `rm_test`. Убрать реальные учётные данные из репозитория. Нужны запуск и результаты проверок, а не только настройка.

### P1-05 — Статистическая модель

`remaining_a/b/c` одновременно описывается как пакетный счётчик и snapshot последнего scan. Нужно строго разделить semantic: `remaining` = advisory reconciled snapshot+committed deltas с timestamp, `migrated` = сумма подтверждённых повторно проверенных transits поколения. На старте статусы unknown/unverified не должны выглядеть как подтверждённый 0. После смены generation счётчики сбрасывать только в одной транзакции с установкой нового ключа. После crash при сомнении пересканировать.

### P1-06 — Cache ACL и invalidation

На cache hit сам по себе `(tenant,generation)` не доказывает действующий статус ACTIVE/DECRYPT_ONLY (revocation). Следует иметь локальную допускающую epoch/state запись, инвалидируемую **after commit** и fail-closed на контролируемой смене статуса; при рестарте восстановить статус из БД. Модель single JVM обязательна для этой гарантии. `expireAfterAccess` не является гарантией своевременного revocation.

## 4. Оптимизации (P2)

- Не требовать HASH partitioning `kms_tenant_key` до подтверждения нагрузки. Для `kms_tenant_key_archive` 8 HASH buckets — рабочая гипотеза, т.к. исторических ключей обычно мало; допускается сначала обычная таблица с PK при отсутствии миграции/нагрузки.
- KMS-счётчики в одной строке компании — сознательный компромисс; не делать update при каждом encrypt/decrypt; мониторить lock contention и p95.
- Код JCA/GCM и envelope codec должны иметь отрицательные тесты tampering и AAD.
- Отказ от lazy re-encryption — уместен для уменьшения MVP complexity; в будущем нужна отдельная ADR если потребуется.

## 5. Результат review gate

| Gate | Решение |
|---|---|
| Архитектурная концепция | ACCEPT |
| Имена модулей и две KMS-таблицы | ACCEPT |
| DDL/JPA разработка в отдельной ветке | GO с P1 migration-review |
| Crypto envelope/Caffeine | GO, после контрактных тестов |
| Nightly worker включён в prod | HOLD до P0-01/P0-03 |
| Auto-rotation/promote/reuse | HOLD до всех P0 |
| Recovery/archive availability | HOLD до P0-02/P0-04 |
| Production admission | HOLD до выполнения всей матрицы в PostgreSQL |

## 6. Короткий обязательный план корректировок ТЗ

1. Закрепить единственный алгоритм DB transaction ↔ key generation admission (P0-01), добавить concurrent commit test.
2. Определить архивный re-wrap, Archive KEK version и recoverability (P0-02/P0-04).
3. Зафиксировать исчерпывающий реестр зашифрованных полей и финальный scan-gate (P0-03).
4. Спроектировать безопасную migrate-from-old changeset, включая старые tenants с >3 поколениями (P1-01).
5. Развести запуск unit/H2 smoke/PostgreSQL integration без destructive shared DB fixture (P1-04).
6. Уточнить counters and cache revocation semantics (P1-05/P1-06).

**Ограничение:** настоящая криптографическая/production security qualification требует динамических тестов и независимого ревью; этот документ — архитектурное заключение по текущему ТЗ, а не сертификат безопасности.
