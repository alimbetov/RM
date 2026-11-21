# RM-KMS-SPEC-R1 — Консолидированное техническое задание

**Репозиторий:** `alimbetov/RM`  
**Ветка проекта ТЗ:** `docs/rm-kms-consolidated-spec`  
**Дата:** 2026-11-21  
**Статус:** PROPOSED R1.1 / упрощённый MVP-контракт; реализация модели не начата  
**Зона ответственности:** Embedded Tenant KMS для CRM MVP  
**Приоритет:** сначала исправление модели данных и испытания H2, затем Fast Path, потом миграция, только затем авто-ротация и PostgreSQL qualification.

## 1. Цель и границы

Разработать собственный безопасный сервис шифрования **внутри одного Spring Boot приложения**. При регистрации компании создаётся индивидуальный случайный AES-256 data encryption key (DEK). CRM шифрует чувствительные поля посредством AES-256-GCM и использует Caffeine для быстрого доступа к распакованным разрешённым DEK.

Две рабочие таблицы:
1. `security.kms_tenant_key` — **ровно одна строка на `tenant_id`**, три физических ключевых слота A/B/C, статусы, поколения, сроки, статистика.
2. `security.kms_tenant_key_archive` — зашифрованные исторические поколения ключей, которые остаются необходимыми для восстановления резервных копий / архивов.

Не создаём таблицы для кэша, журнала ротаций, ночных заданий и отдельный KMS-микросервис. Не применяем самодельную криптографию, Redis или Vault в MVP. Отдельные таблицы CRM (клиенты, контакты, заказы и т.д.) будут разработаны по бизнес-требованиям позднее: запрет касается раздувания **KMS**, а не нормализации финансовых данных.

## 2. Технологический стек

- Java 21, Spring Boot 4.1.x, Spring Data JPA/Hibernate, Lombok, Maven.
- Два профиля Spring Boot: `dev` = H2 in-memory (локальные smoke-тесты), `test` = PostgreSQL (разработка, интеграционные тесты, нативные SQL и квалификация). PostgreSQL остаётся целевой СУБД.
- Liquibase **formatted SQL** — единственный механизм схемных миграций; `spring.jpa.hibernate.ddl-auto=validate`.
- Caffeine `LoadingCache` для разрешённых рабочих ключей.
- Java JCA/JCE `AES/GCM/NoPadding`, 256-bit DEK, 96-bit уникальный случайный nonce для каждой операции, 128-bit authentication tag.
- Для поиска по email/телефону отдельные HMAC blind indexes с отдельным защищённым ключом (будущий CRM этап).

## 3. Главный инвариант: три слота — не три вечных ключа

- Слоты A, B, C являются **переиспользуемыми ячейками хранения**, а не постоянными криптографическими ключами.
- При каждом повторном использовании слота генерируется **новый случайный DEK**.
- Каждое поколение имеет строго возрастающий `generation` (`BIGINT`) в пределах tenant; поколение никогда не переиспользуется.
- Каждая зашифрованная запись обязана содержать generation ID, версию формата, nonce, ciphertext и tag.
- Выбор DEK по одной букве слота запрещён: соответствие `tenant_id + generation` должно быть однозначным.
- В штатном режиме **ровно один ACTIVE**, **не более одного DECRYPT_ONLY**, **не более одного READY**.
- READY никогда не используется для обычного шифрования или расшифровки. DECRYPT_ONLY может расшифровывать существующие данные, но не шифрует новые.
- RETIRED / архивированное поколение не может обслуживать обычные онлайн-запросы.
- REVOKED означает запрет использования, а не «успешно мигрирован»; отзыв не должен приводить к скрытому уничтожению данных.

## 4. Модель данных

### 4.1 `security.kms_tenant_key` — одна строка на компанию

Предполагаемые колонки:
```text
tenant_id           UUID PRIMARY KEY
key_a               BYTEA NULL
generation_a        BIGINT NULL
status_a            VARCHAR(20) NOT NULL DEFAULT 'EMPTY'
remaining_a         BIGINT NOT NULL DEFAULT 0
migrated_a          BIGINT NOT NULL DEFAULT 0
last_scan_a         TIMESTAMPTZ NULL

key_b               BYTEA NULL
generation_b        BIGINT NULL
status_b            VARCHAR(20) NOT NULL DEFAULT 'EMPTY'
remaining_b         BIGINT NOT NULL DEFAULT 0
migrated_b          BIGINT NOT NULL DEFAULT 0
last_scan_b         TIMESTAMPTZ NULL

key_c               BYTEA NULL
generation_c        BIGINT NULL
status_c            VARCHAR(20) NOT NULL DEFAULT 'EMPTY'
remaining_c         BIGINT NOT NULL DEFAULT 0
migrated_c          BIGINT NOT NULL DEFAULT 0
last_scan_c         TIMESTAMPTZ NULL

active_slot         CHAR(1) NOT NULL
ready_slot          CHAR(1) NULL
next_generation     BIGINT NOT NULL
rotate_after        TIMESTAMPTZ NOT NULL
wrapping_key_id_a   VARCHAR(128) NULL
wrapping_key_id_b   VARCHAR(128) NULL
wrapping_key_id_c   VARCHAR(128) NULL
row_version         BIGINT NOT NULL DEFAULT 0
updated_at          TIMESTAMPTZ NOT NULL
```

Точные имена и типы дорабатываются при DDL review, но семантика и «одна строка на компанию» обязательны. Constraints:
- слот с EMPTY: `key_X`, `generation_X`, `wrapping_key_id_X` = NULL;
- любой непустой слот: эти три значения NOT NULL, поколение > 0;
- уникальность непустых поколений внутри строки;
- CHECK допустимых статусов (EMPTY/ACTIVE/DECRYPT_ONLY/READY; архивирование освобождает слот);
- `active_slot` соответствует единственному ACTIVE; `ready_slot` соответствует READY или NULL;
- `remaining_X` / `migrated_X` >= 0;
- изменения `row_version` с optimistic locking;
- индексы: PK по tenant_id; отдельные дополнительные индексы только после измерений.

Предостережение: счётчики — **телеметрия прогресса на момент последней сверки**, а не абсолютная истина между проверками. Их нельзя использовать как единственное основание для перезаписи ключевого материала.

### 4.2 `security.kms_tenant_key_archive` — архив поколений

```sql
CREATE TABLE security.kms_tenant_key_archive (
    tenant_id UUID NOT NULL,
    key_generation BIGINT NOT NULL,
    wrapped_key BYTEA NOT NULL,
    wrapping_key_id VARCHAR(128) NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, key_generation)
);
```

Архивный DEK остаётся зашифрованным. Архив не доступен через обычный KMS Fast Path. Восстановление — отдельная авторизованная и аудируемая операция. Для защиты от компрометации рабочей инфраструктуры предпочтительно **переупаковывать архив под отдельный архивный KEK**, а не просто переносить ciphertext, зашифрованный повседневным KEK. Архив и независимые backup-артефакты должны сохраняться согласованно с соответствующими версиями KEK.

Важное условие: сам по себе архив внутри той же БД не переживёт её полную потерю; нужен независимый бэкап архивных ключей и корневых/архивных KEK.

## 5. Root KEK и безопасное хранение

- Root KEK не сохраняется открытым в PostgreSQL, Git, `application.yml`, логах или Docker image.
- Для локальной разработки — защищённый секрет ОС, передаваемый приложению как конфигурация; не использовать постоянный известный тестовый ключ вне тестов.
- В production — изолированное хранилище секрета/оборудование с разграничением прав и резервным копированием; конкретный backend принимается отдельно.
- DEK в рабочей строке хранится как versioned envelope `nonce || ciphertext || tag` с аутентифицированными `tenant_id`, `generation`, назначением `tenant-key-wrap`.
- Идентификаторы wrapping key сохраняются **для каждого занятого слота**; root KEK rotation отдельно от tenant DEK rotation.
- Запрет логирования plaintext, ключей и дампов памяти в production; учитывать, что Java GC не гарантирует физического зануления памяти.

## 6. Регистрация компании и provisioning

1. Проверить пользователя, полномочия, данные компании и идемпотентность регистрационного запроса.
2. Получить `tenant_id` из доверенного контекста регистрации.
3. Сгенерировать AES-256 DEK криптостойким RNG, поколение `1`.
4. Упаковать под корневой KEK, поместить в слот A со статусом ACTIVE, B/C EMPTY.
5. Сохранить tenant и `security.tenant_key` в одной PostgreSQL-транзакции (или эквивалентный согласованный механизм, если IAM отдельно).
6. Публиковать ключ в локальный кэш только после commit. Повторная регистрация не должна приводить к второму ключу или дублированию компании.

Условия отказа: нет KEK, ошибка упаковки, конфликт регистрации, отказ БД → не активировать tenant. Ни при каких обстоятельствах не подменять отсутствующий старый KEK новым «по умолчанию».

## 7. Быстрый путь

- **Единый** `TenantCryptoService` для encrypt/decrypt с доверенным tenant context; CRM-контроллер сначала проверяет право доступа, затем вызывает криптосервис. Нет отдельных сервисов дешифрования для каждого модуля.
- Caffeine key: `(tenantId, generation)`; максимум 1 000 записей и `expireAfterAccess=10m` как стартовые тестовые параметры.
- Cache HIT: **нет SQL за ключом**, только локальная проверка допуска и AES-GCM.
- Cache MISS: загрузить занятую версию из единственной строки `security.kms_tenant_key`, проверить generation и status, unwrap, single-flight загрузка.
- Нормальное шифрование берёт только текущее поколение ACTIVE; старое DECRYPT_ONLY используется исключительно для расшифровки.
- AAD шифрования CRM: `tenant_id + entityType + entityId + fieldName + generation + formatVersion`; nonce уникальный для каждой операции с конкретным ключом.
- События смены ACTIVE немедленно инвалидируют active-generation metadata и неподходящие cache entries.
- Для MVP допускается **один экземпляр приложения**; для нескольких инстансов потребуются согласованные контрольные переходы и подтверждённое распространение инвалидации.
- Роль Caffeine — ускорять, но не заменять проверку разрешений/RLS.

## 8. Политика ротации и трёхслотовый цикл

Плановый срок: 90 дней как стартовая политика, конфигурируемый. Истечение срока **не означает разрешения перезаписать занятый слот**.

Переходы:
1. Создание: A=v1 ACTIVE; B,C EMPTY.
2. Первая ротация: создать B=v2 ACTIVE; A=v1 DECRYPT_ONLY.
3. Ночная миграция переводит все живые ссылки с v1 на v2.
4. После достоверной проверки живых ссылок, незавершённых задач и архива: архивировать v1; освободить A; создать C=v3 READY.
5. Следующая ротация: C=v3 ACTIVE; B=v2 DECRYPT_ONLY. После полного переноса v2 и архивирования B — A=v4 READY.
6. Далее A→B→C→A в смысле **циклического переиспользования слотов** с новыми поколениями.

Если старое поколение всё ещё требуется для актуальных данных или не обеспечено восстановление архивов — повторное использование слота откладывается. При невыполнении безопасных условий ночная задача фиксирует просроченную ротацию, но не уничтожает старые ключи.

Транзакционный control path при переходах обязан учитывать конкурирующие записи с предыдущим ACTIVE, версии данных, статусы слотов и кэш. Сама блокировка одной строки KMS недостаточна для атомарности по всем CRM таблицам; нужен протокол прекращения старых writers и финальной сверки.

## 9. Ночная миграция: 100 клиентов за пакет

Начальные настройки:
```yaml
rm:
  kms:
    migration:
      enabled: false
      cron: "0 0 2 * * *"
      zone: "Asia/Almaty"
      batch-size: 100
      window-end: "05:00"
      pause-between-batches: 200ms
      max-retries: 3
```
Настройки начальные; `enabled=false` до готовности защищённых клиентских полей и успешной тестовой приёмки.

**Пакет — максимум 100 различных клиентов**, включая их относящиеся к данному ключевому поколению зашифрованные поля и контакты. Не путать «100 клиентов» со «100 полей».

Алгоритм:
1. Worker выбирает компанию с DECRYPT_ONLY поколением и доступным ACTIVE; единственный worker в MVP.
2. Выбрать до 100 ещё нуждающихся в миграции клиентов по `tenant_id + old generation`, с устойчивым порядком, без `OFFSET` над изменяющейся выборкой.
3. Для каждого клиента перечитать envelope и row version; decrypt(old), encrypt(active) с новым nonce и корректным AAD; сохранить с защитой от конкурентной перезаписи.
4. **Один пакет — одна транзакция**. Счётчики в `security.kms_tenant_key` обновлять один раз на пакет исходя из реального количества успешно изменённых *полей*, в той же транзакции. На rollback счётчики не меняются. При повторе ранее перенесённые поля не учитываются заново.
5. После commit сделать паузу (предварительно 200 мс), затем следующий пакет. На ошибке — ограниченные повторы; после исчерпания попыток зафиксировать отказ, не уничтожать старые ключи.
6. В 05:00 прекратить запуск новых пакетов, уже начатую транзакцию корректно завершить; на следующую ночь продолжить с фактических оставшихся ссылок в БД. Никакого доверия к исключительно in-memory cursor.
7. После предполагаемого `remaining_old=0` провести финальную сверку всех CRM таблиц, сообщений/outbox и активных writers под согласованной защитой. Только подтверждённая проверка плюс доступность необходимых архивов позволяет освободить слот.
8. Миграция полей за пределами «клиентского агрегата» (финансовые документы, самостоятельные сообщения, логи) выполняется отдельными сканерами той же ночной задачи; отсутствие таких сканеров блокирует освобождение поколения.

Нормальные CRUD-запросы не должны блокироваться на всю ночь. Один транзакционный batch, а не общая огромная транзакция. Счётчики приблизительны между периодическими сверками, но каждое подтверждённое batch-изменение учтено атомарно.

## 10. Статистика в той же строке

Для каждого слота:
- `remaining_X` — число актуальных field envelopes на конкретном поколении по последней согласованной статистике;
- `migrated_X` — число действительно мигрированных field envelopes этого поколения;
- `last_scan_X` — время последней полной сверки.

`remaining` — **не число клиентов**, и не гарантия завершения. При перезаписи слота статистика обнуляется только после архивации и генерации нового generation. Для прогресса UI могут дополнительно показываться `clientsProcessed`, вычисленные из запросов/метрик, но не обязательно отдельной колонкой.

Не обновлять строку `tenant_key` при каждом чтении/криптооперации. В случае обычных новых записей текущие оперативные счётчики могут быть snapshot-оценкой до периодического reconciliation. Для безопасного освобождения — полный scan с координацией writers.

## 11. Партиционирование по tenant_id

- В H2: **обе таблицы обычные**, эквивалентные по колонкам и поведению; H2 PostgreSQL compatibility mode не эмулирует declarative partitioning.
- В PostgreSQL: `tenant_key_archive PARTITION BY HASH(tenant_id)`, начать с 8 фиксированных партиций; распределение новых tenant автоматически, без DDL при регистрации.
- `tenant_key`: **пока обычная таблица** с PK `tenant_id`; одна строка на компанию не оправдывает физическое партиционирование до реальных данных. Возможность HASH на 8 bucket позднее, если доказана необходимость.
- Не делать `LIST`-партицию на каждую компанию: тысячи партиций увеличат издержки планирования и миграций.
- Архивный PK `(tenant_id, key_generation)` включает partition key.
- Партиционирование не обеспечивает разграничение прав: необходимо tenant-aware authZ и в PostgreSQL RLS.
- Раздельные Liquibase **SQL-only** changesets с `dbms`-ограничениями для H2 / PostgreSQL. Не изменять ранее применённые changesets; `DATABASECHANGELOG` и checksum контролируются Liquibase.

## 12. Контроль безопасности

- Нельзя расшифровать чужим tenant key, подменённым generation, nonce или AAD.
- Минимальные привилегии приложения и отдельные права на архив; никакого публичного key retrieval API.
- Ошибка разблокировки или несогласованность статуса → fail closed.
- Нельзя считать cache eviction доказательством завершённого отзыва ключа.
- Отзыв требует строгой последовательности блокировки новых crypto admissions, завершения in-flight операций и фиксации устойчивого запрета; кластерная реализация — отдельный релизный gate.
- Изоляция tenant в PostgreSQL через RLS в сочетании с проверками Spring Security.
- Все ключи только wrapped at rest; отдельные root/archive KEK для изоляции полномочий.
- Аудит событий ротации/восстановления нужен, но при требовании «только две таблицы KMS» отправлять его в общий системный аудит, а не создавать третью специализированную таблицу.

## 13. Надёжность и восстановление

- Старые поколения архивируются **до** освобождения соответствующего слота.
- Проверить независимый backup архивов и root/archive KEK.
- Тестировать восстановление старого среза данных с архивными поколениями ключей и отдельной авторизацией recovery.
- Если архивный KEK потерян, восстановление не гарантируется; нельзя обещать безусловное восстановление при полной компрометации/потере всех ключевых секретов.
- Восстановление из старого backup не должно позволять незаметно продолжать live-шифрование уже отозванным поколением.

## 14. План внедрения

- **R1.6 (schema)**: новый SQL changeset миграции прежней per-version модели к single-row three-slot, JPA `TenantKeyEntity` (одна строка), archive Entity, ограничения, rollback strategy. Не править уже применённый `rm:0003`. Если существующие данные неизвестны — остановить destructive migration и квалифицировать путь переноса.
- **R1.7 (cryptography)**: проверяемый generation envelope, Fast Path Caffeine, AAD, root wrapping key version, negative tests.
- **R1.8 (batch)**: один nightly worker по 100 клиентов, stats по полям, crash/retry/resume/optimistic conflict; без lazy re-encryption.
- **R1.9 (rotation)**: безопасное переключение трёх слотов только после реализации миграции и проверок; без дополнительных фоновых координаторов.
- **R2.0 (recovery)**: отдельная авторизованная процедура чтения архивных поколений, backups, root-key rotation scenario.
- **QUAL-R2.1**: H2 smoke + PostgreSQL Testcontainers, concurrency, cross-tenant, crypto correctness, migration proof, failure recovery, JMH/p95/p99.

## 15. Матрица приёмки

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| KMS-01 | Регистрация компании | 1 строка tenant, A=v1 ACTIVE, B/C EMPTY |
| KMS-02 | Повтор регистрации | Идемпотентно, нет второго DEK/строки |
| KMS-03 | Две компании | Разные DEK; cross-tenant decrypt отклоняется |
| KMS-04 | Первый rotate | v1 DECRYPT_ONLY, v2 ACTIVE |
| KMS-05 | Старое содержимое | v1 читается до verified migration |
| KMS-06 | 100 клиентов | Не более 100 client aggregates за batch |
| KMS-07 | 101 клиент | Два batch, без пропусков и двойного учёта |
| KMS-08 | Ошибка commit | Откат envelopes и счётчиков |
| KMS-09 | Concurrent contact edit | Нет lost update, безопасный retry |
| KMS-10 | Restart посреди batch | Продолжение из persisted generation refs |
| KMS-11 | Remaining=0, но осталась ссылка | Slot reuse запрещён |
| KMS-12 | Архив и backup | Восстановление старого snapshot успешно |
| KMS-13 | Перезапись слота | Новый ключ и монотонный generation |
| KMS-14 | READY при отсутствии миграции | Не создаётся/не перезаписывает старое |
| KMS-15 | Утерян Root KEK | Чёткий отказ, без случайной замены |
| KMS-16 | Cache hit | Ноль SQL за ключом |
| KMS-17 | Гонка ротации | Не больше одного ACTIVE |
| KMS-18 | Досрочный отзыв | Новые операции отклонены после подтверждённого control transition |
| KMS-19 | H2 / PG schema | Обе версии Liquibase проходят и JPA validate проходит |
| KMS-20 | PostgreSQL archive HASH | `tenant_id` маршрутизируется в одну из 8 партиций, PK уникален |

## 16. Ограничения и открытые вопросы перед кодом

1. Root/archive KEK production backend и RTO/RPO восстановления должны быть выбраны отдельно.
2. Уточнить, когда требуется строгая блокировка штатного шифрования: сейчас MVP — один экземпляр JVM.
3. Уточнить срок хранения backup; пока ключи из архива нельзя удалять автоматически.
4. Конкретные CRM-таблицы с encrypted envelope ещё не разработаны. Полноценная `remaining=0` qualification возможна только после каталога всех зашифрованных полей.
5. История GitHub: `main` содержит старую per-version JPA/SQL модель. Этот документ не свидетельствует, что новый код уже реализован или тесты выполнены.

## 17. Решение на старт разработки

**ТЗ единое, исходный код менять только после ревью DDL и миграционного пути.** До выполнения QUAL-R2.1 НЕ утверждать production-ready. На первом этапе оставить `migration.enabled=false`, без автоматической перезаписи ключевых слотов.


## 18. Среды выполнения: два профиля Spring Boot (новое согласованное решение)

| Параметр | `dev` | `test` |
|---|---|---|
| СУБД | H2 in-memory | PostgreSQL `rm_test` |
| Назначение | Быстрый запуск и smoke | Разработка, интеграция, SQL/JPA qualification |
| Hibernate DDL | `validate` | `validate` |
| Миграции | Liquibase formatted SQL | Liquibase formatted SQL |
| H2 console | Только локально | Выключена |
| Данные | Эфемерные | Сохраняются между перезапусками |
| Партиционирование | Нет, структурный аналог | PostgreSQL HASH по tenant_id в архиве (после реализации) |

Файлы: `application.yml` (общее), `application-dev.yml`, `application-test.yml`. Старый отдельный профиль `postgres` упразднён. Параметры подключения к test PostgreSQL — `RM_TEST_DB_URL`, `RM_TEST_DB_USERNAME`, `RM_TEST_DB_PASSWORD`; никогда не в Git. Значение по умолчанию остаётся `dev`. Все SQL changesets должны быть совместимы с обеими СУБД либо явно ограничены по `dbms`; **PostgreSQL-native SQL подтверждается на test, а не H2**. CI требуется явный запуск PostgreSQL интеграционного набора, иначе обычный `mvn test` с dev-профилем не доказывает квалификацию PostgreSQL.

**Статус:** конфигурационные файлы профилей обновлены в этой ветке; реальный запуск H2/PostgreSQL и тестов не подтверждён. Это изменение профилей не означает выполнения миграции старой KMS-модели в трёхслотовую.


## 19. Единая конвенция имён таблиц по модулям RM

**Новое решение:** все прикладные таблицы получают обязательный префикс функционального модуля, независимо от физической PostgreSQL schema. Формат — `<module_prefix>_<business_entity>` в `lower_snake_case`; префиксы выбираются заранее и стабильны. Не включать в имя номер версии, tenant UUID или название окружения.

Предлагаемый реестр имён (не означает, что таблицы уже созданы):

| Модуль | Префикс | Примеры таблиц |
|---|---|---|
| Компании / tenant | `ten_` | `ten_company`, `ten_membership` |
| Клиенты / CRM | `crm_` | `crm_client`, `crm_contact` |
| Заказы | `ord_` | `ord_order`, `ord_order_line` |
| Сметы | `est_` | `est_estimate`, `est_estimate_line` |
| Управленческие финансы | `fin_` | `fin_transaction`, `fin_budget` |
| Рассылки / коммуникации | `msg_` | `msg_campaign`, `msg_delivery` |
| Задачи | `tsk_` | `tsk_task`, `tsk_task_comment` |
| Аудит | `aud_` | `aud_event` |
| KMS / криптография | `kms_` | `security.kms_tenant_key`, `security.kms_tenant_key_archive` |

KMS remains isolated in `security` schema; `security` is a namespace, **not** a replacement for the `kms_` module prefix. One-row-per-company and archive constraints stay unchanged. The table names in sections 1/4/8/10/11/13 and all earlier design proposals are **legacy design aliases**; canonical implementation names are `security.kms_tenant_key` and `security.kms_tenant_key_archive`. No ambiguity should remain in Java Entity/Table annotations or new SQL changesets. The current deployed `security.tenant_key` is not to be renamed in-place without a controlled migration.

Conventions:
1. Table: `<prefix>_<singular_noun>`. All PK names `pk_<table>`, FK names `fk_<table>_<target>`, unique names `uk_<table>_<columns>`, index names `idx_<table>_<columns>`, CHECK names `ck_<table>_<rule>`. Respect PostgreSQL's 63-byte identifier limit; abbreviate consistently.
2. Shared column names: `id`, `tenant_id`, `created_at`, `updated_at`, `row_version`; use composite PK when dictated by immutable key history or domain constraints. `tenant_id` is mandatory for tenant-owned tables, and tenant filtering/authorization must be enforced independently of naming.
3. Join tables carry the owner module prefix, e.g. `crm_client_tag`; audit and outbox names belong to their owning module unless designed as a shared platform module.
4. Java packages should follow the same module boundaries, e.g. `kz.rm.security.kms`, `kz.rm.crm`, `kz.rm.orders`; API endpoints remain resource-oriented without SQL prefixes.
5. Liquibase SQL-only: new tables use canonical names. Existing applied `rm:0003-tenant-key` must remain intact. A follow-up, data-safe changeset migrates `security.tenant_key` (per-version rows) into `security.kms_tenant_key` (single-row three-slot) with version/account reconciliation, archive `security.kms_tenant_key_archive`, constraint revalidation and tested rollback plan. Never silently drop old wrapped keys or alter checksums.
6. Prefix naming is an organizational convention and **not** a security boundary or performance optimization. Existing Liquibase metadata tables `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK` are infrastructure-owned and exempt.

**Acceptance gate:** all new DDL, JPA `@Table`, SQL queries, tests and docs reference canonical prefixed tables; H2 `dev` smoke and PostgreSQL `test` integration pass. This section documents a design decision only; migration/code not yet implemented.


## 20. Упрощённый контракт дешифрования и план реализации RM-KMS MVP (решение R1.1)

Этот раздел **уточняет и имеет приоритет** при расхождениях с более ранними разделами. Цель: один проверяемый путь шифрования, один путь дешифрования и один ночной worker, без дополнительных сервисов, автоматических эвристик и перерасчётов при обычном редактировании.

### 20.1 Минимальный набор компонентов

| Компонент | Назначение | Не делает |
|---|---|---|
| `TenantCryptoService` | `encrypt/decrypt`, выбор поколения, AES-GCM | Не принимает решений об авторизации пользователя |
| `TenantKeyService` | Читает три слота tenant, выдаёт только разрешённый DEK | Не расшифровывает CRM-поля |
| `TenantKeyCache` (Caffeine) | Кэширует распакованный DEK по `(tenantId, generation)` | Не хранит архивные ключи |
| `NightlyKeyMigrationJob` | Ночью переводит поля старого поколения пакетами по 100 клиентов | Не запускается при обычном CRUD |
| `TenantKeyRecoveryService` | Отдельная, строго авторизованная операция восстановления | Не используется в обычных запросах CRM |

Не создавать дополнительные сервисы по модулю или entity. Один Spring Boot процесс и один рабочий экземпляр JVM в MVP. Разрешены небольшие внутренние классы (envelope codec, repository, crypto utilities), но никакие отдельные сетевые компоненты.

### 20.2 Минимальный Java API и доверенная граница

```java
public interface TenantCryptoService {
    EncryptedValue encrypt(
        CryptoContext context,
        String plaintext
    );

    String decrypt(
        CryptoContext context,
        EncryptedValue encrypted
    );
}

public record CryptoContext(
    UUID tenantId,
    String entityType,
    UUID entityId,
    String fieldName
) {}
```

`CryptoContext` создаётся **только из проверенного backend-контекста** и идентификатора реально загруженной записи, а не напрямую из непроверенного JSON запроса. Пользователь не имеет API для выбора key slot, DEK или ключевого поколения. Вызывающий application service обязан проверить доступ к компании и объекту до вызова. Дешифрование само проверяет совпадение tenant, generation и AAD; ошибка — fail closed.

**Почему в API есть контекст:** вариант `decrypt(tenantId, encrypted)` недостаточен для защиты от перестановки корректного шифротекста между клиентами и полями одной компании. `CryptoContext` остаётся маленьким, но обязательным.

### 20.3 Контракт EncryptedValue (независим от физического слота)

```java
public record EncryptedValue(
    int formatVersion,
    long keyGeneration,
    byte[] nonce,
    byte[] ciphertextAndTag
) {}
```

- `formatVersion = 1`; явно определённый, сериализация и десериализация строго детерминированны.
- `keyGeneration > 0` и монотонен внутри компании.
- `nonce`: 12 байт от `SecureRandom`, новый для каждого шифрования с данным DEK.
- `ciphertextAndTag`: результат `AES/GCM/NoPadding` с tag 128 бит; библиотека Java хранит tag в конце результата.
- AAD: однозначно закодированные с границами байтов версии формата, `tenantId`, `entityType`, `entityId`, `fieldName`, `keyGeneration`. Не использовать простую конкатенацию без разграничителей или длины.
- Проверка формата, размера nonce, generation и алгоритма выполняется **до** GCM decrypt.
- В БД каждая защищённая колонка/значение имеет неизменяемый формат envelope; запрещено хранить plaintext fallback при ошибках.

### 20.4 Точный алгоритм encrypt/decrypt

**encrypt:**
1. Проверить доверенный `CryptoContext`; вызывающий сервис уже проверил права.
2. Определить текущий ACTIVE generation компании, получить DEK из Caffeine или распаковать из рабочего слота.
3. Сгенерировать новый 96-bit nonce и построить AAD.
4. Выполнить AES-256-GCM, вернуть `EncryptedValue`; plaintext не записывать в лог.
5. Сохранение бизнес-объекта и конверта относится к транзакции вызывающего сервиса.

**decrypt:**
1. Проверить разрешения на уровне вызывающего CRM-сервиса и доверенный `CryptoContext`.
2. Из `EncryptedValue` взять `keyGeneration`. **Никогда не перебрать A/B/C**.
3. Сначала найти `(tenantId,generation)` в Caffeine; при MISS — одну строку `security.kms_tenant_key`, соответствующий занятый слот, допустимые `ACTIVE/DECRYPT_ONLY`, unwrap под KEK.
4. Построить тот же AAD; проверить 12-byte nonce и выполнить GCM decrypt с проверкой tag.
5. При любом несоответствии или отсутствии разрешённого поколения — отказ, без fallback на архив, другой слот или plaintext.

Fast Path означает **нет SQL именно за DEK при cache HIT**, но не обещает отсутствия SQL на чтение клиентской записи/проверку доступа. Для активного поколения допускается локальный кэш метаданных текущего ACTIVE в одном экземпляре JVM; смена ACTIVE должна немедленно инвалидировать его **после commit**.

### 20.5 Упрощённая политика ротации без гонок

- `READY` заранее хранит **только wrapped DEK** и не используется до promotion.
- Переключение ACTIVE и его версии — одна короткая DB-транзакция под блокировкой строки компании.
- **Критичная тонкость:** проверка актуальности ACTIVE для операции encrypt должна быть согласована с commit записи. Если часть операций уже выбрала старое поколение, переход обязан дождаться их завершения либо отклонить/перезапустить запись; одного `@Transactional` на `TenantKeyService.rotate` недостаточно. В MVP — простой согласованный write admission внутри одного экземпляра JVM плюс повторная проверка generation при сохранении. Без реализации и тестирования этой гарантии автоматически переключать ACTIVE запрещено.
- После commit инвалидация Caffeine active metadata; DECRYPT_ONLY остаётся доступен для чтения.
- Запрет повторного использования слота, пока он имеет живые ссылки, пока не проверена сохранность архивного поколения и пока выполняются прежние writers.
- Если контрольные условия не выполнены к 90-му дню — статус `OVERDUE` как состояние мониторинга, а **не** насильственная перезапись ключа. Не добавлять новое состояние ключевого слота ради этого.

### 20.6 Nightly job: однозначный batch-контракт

- Запуск `02:00 Asia/Almaty`, остановка создания новых пакетов в `05:00`; оба значения настраиваемые.
- Не более **100 уникальных клиентов** за пакет; включить все зашифрованные поля их клиентского агрегата и контактов, которые используют old generation.
- Одна транзакция на пакет. Строгий порядок клиентов, select старого поколения без `OFFSET`, row-version compare / optimistic lock, один commit.
- Для каждого изменённого поля выполняются `decrypt(old)` и `encrypt(active)`; plaintext не сохраняется и не логируется. **Не делать lazy re-encryption** при CRUD или обычном чтении.
- В рамках той же транзакции один раз обновить `remaining_old` / `migrated_old` на количество **реально изменённых field envelopes**. Ошибочный/повторный пакет не прибавляет счётчики.
- На ошибке транзакции: rollback данных и stats, максимум три попытки для временных ошибок, затем остановка по проблемной компании и сигнал оператору.
- Незаполненный `remaining` или значение 0 **не считается** разрешением на освобождение ключа. Финальный полный scan всех CRM-модулей / jobs, блокировка новых зависимостей от old generation, проверка архива и только затем освобождение слота.
- После рестарта выборка снова опирается на реальные версии envelope; отдельная таблица checkpoint в MVP не нужна.
- Когда появятся независимые зашифрованные документы/сообщения за пределами клиента, их миграционные выборки добавляются в общий `NightlyKeyMigrationJob` **до** разрешения safe-reuse. Иначе safe-reuse блокируется.

### 20.7 Минимальная операция Recovery

- В `security.kms_tenant_key_archive` хранить `tenantId`, поколение, wrapped DEK, `wrappingKeyId`, время архивации. **Архивный KEK и восстановление отдельно от обычного crypto path**.
- Recovery запускается только отдельным авторизованным административным сценарием с audit trail.
- Загрузить требуемое поколение, расшифровать архивный wrapped DEK под историческим/архивным KEK, проверить envelope из архивного snapshot и расшифровать только в контролируемой recovery-сессии.
- Архивные поколения **не кладутся в Caffeine обычного криптосервиса**. Завершение recovery очищает временные данные и закрывает доступ.
- Перед освобождением слота подтвердить, что его зашифрованная архивная копия **транзакционно сохранена** и её независимая backup/restore процедура проверена. Не удалять архивные ключи по TTL.

### 20.8 Что НЕ реализовывать в MVP

1. Lazy re-encryption при изменении или чтении клиента.
2. Отдельный микросервис, Redis, distributed locking, несколько JVM.
3. Публичный REST API получения DEK или выбора конкретного key slot.
4. Новые служебные KMS таблицы ради progress, scheduler, cache.
5. Автоматическую очистку архивных ключей или форсированную ротацию при незавершённой миграции.
6. Самописные криптографические примитивы; использовать JCA/JCE.

### 20.9 Расширение матрицы приёмки (обязательные P0)

| ID | Проверка | PASS |
|---|---|---|
| KMS-21 | Подмена encrypted поля между клиентами одного tenant | GCM/AAD отвергает |
| KMS-22 | Подмена между полями `email` и `phone` | GCM/AAD отвергает |
| KMS-23 | Ciphertext generation=v2, slot физически занят v4 | Нет попытки decrypt v4, controlled fail |
| KMS-24 | 100 клиентов, часть без старых полей | Stats равны фактически перенесённым field envelopes |
| KMS-25 | 101 клиент и restart между батчами | Все данные мигрированы ровно один раз |
| KMS-26 | Ночное задание и конкурентный edit | Нет lost update и устаревшего envelope после commit |
| KMS-27 | Строка KMS с remaining=0, но есть ссылка в msg | Safe-reuse запрещён |
| KMS-28 | Архивный ключ доступен только в Recovery | Обычный decrypt не читает архив |
| KMS-29 | Неправильный Root KEK или GCM tag | Fail closed, без plaintext fallback |
| KMS-30 | Cache hit | Нет SQL за DEK, но допустим SQL за самим CRM объектом |
| KMS-31 | Rotation while encrypt transaction in-flight | Не возникает новых committed данных на выведенном поколении |
| KMS-32 | Batch rollback и повтор | Нет двойного счёта, нет частично мигрированного пакета |

**Граница готовности:** проектирование R1.1 не означает работающий код. Сначала DDL/JPA и envelope, потом тесты decrypt и Caffeine, потом nightly, затем promotion и recovery. `migration.enabled=false` и автоматическая перезапись слотов запрещена до подтверждения KMS-21..KMS-32 на H2 и PostgreSQL.


## 21. Обязательная квалификация внутреннего шифрования (RM-KMS-QUAL-R1)

**Нормативное приложение:** [RM-KMS-QUAL-R1 — матрица 80 новых тестовых сценариев](RM-KMS-QUAL-R1.md). Оно является неотъемлемой частью этого ТЗ и расширяет ранее определённые `KMS-01..32` до **112 сценариев** (идентификаторов; один сценарий может иметь несколько параметризованных реализаций).

Группы:
- `CRYPT-01..20` — AES-256-GCM, UTF-8, round-trip, nonce/tag/AAD, tamper, известные KAT и property-based проверки;
- `KEY-01..12` — генерация DEK, Root KEK wrapping, ошибки конфигурации, Caffeine, TTL, запрет архивного ключа в штатном пути;
- `SLOT-01..12` — один ACTIVE, три слота и поколения, SQL constraints, Liquibase миграция старой модели, crash/rollback;
- `BATCH-01..16` — 0/1/99/100/101/201 клиентов, stats по полям, идемпотентность, concurrency, ночное окно, rollback/retry;
- `REC-01..10` — отдельный Archive KEK, rewrap, независимый backup, восстановление старого snapshot, authorization;
- `ENV-01..10` — профили `dev`/H2 и `test`/PostgreSQL, HASH, изоляция интеграционных тестов, DB failures, CI evidence.

**Минимальные пороги test coverage:** ≥90% line и ≥85% branch для внутреннего пакета `kz.rm.security.kms` через JaCoCo, без исключения критичных crypto/rotation/recovery классов. Пороги — вспомогательная проверка, не доказательство стойкости AES или безопасности: все негативные сценарии и независимый test vector обязательны даже при 100% coverage.

**Gate:** G0 Crypto → G1 Schema → G2 Data integrity → G3 Restore → G4 Release. Перед включением nightly worker и auto rotation required negative/concurrency tests должны быть GREEN в PostgreSQL, а восстановление из независимого архива подтверждено. До этого `migration.enabled=false`, safe-reuse запрещён, productionAdmission=HOLD.

**Необязательные для dev smoke, но обязательные до production:** измерения p95/p99 на PostgreSQL, восстановление при потере локальной БД, анализ race-condition и тесты сохранности historical KEK. Не заявлять GREEN только по наличию спецификации.


## 22. Готовность RM-KMS к микросервисной архитектуре

**Нормативный ADR:** [ADR-RM-KMS-004](../adr/ADR-RM-KMS-004-microservice-readiness.md).

**Решение:** строить KMS как автономный bounded context с чистым прикладным портом `TenantCryptoPort`, но развёртывать пока внутри **одного** Spring Boot процесса, без HTTP API с plaintext/DEK, без изменения владельцев данных и без второй базы. Ключи, версии, archive и wrapping принадлежат KMS; CRM/ORD/FIN используют порт и сохраняют envelope в собственных бизнес-таблицах. Ключи через таблицы других модулей не запрашиваются.

Кодовый контракт:
- `TenantCryptoPort.encrypt(CryptoContext, String)`
- `TenantCryptoPort.decrypt(CryptoContext, EncryptedValue)`
- `TenantCryptoService implements TenantCryptoPort` — текущий внутренний адаптер; `CryptoContext` разрешается только после backend-авторизации.
- `KmsDeploymentGuard`: `rm.kms.deployment.mode=single-jvm`; `rm.kms.rotation.enabled=false`; `rm.kms.migration.enabled=false`; любое несовместимое значение блокирует старт. Сам guard **не обнаруживает реальное количество реплик**: оно дополнительно обеспечивается deployment policy.

**Дальнейшее выделение в микросервис НЕ автоматически одобрено.** До этого отдельно проектируются: workload identity/mTLS, tenant-scoped authorization, сериализация/версия API, отсутствие выдачи сырого DEK, изменение границы plaintext, устойчивость к сетевым отказам, retries/idempotency, outbox для tenant provisioning, распределённый generation admission и revocation barrier, кластера Caffeine, каталог зашифрованных полей во всех сервисах. Одновременно активировать несколько JVM запрещено до подтверждения всех инвариантов.

**Новые acceptance tests:** MICRO-01 интерфейсный контракт внедряется без зависимости потребителя от JPA; MICRO-02 single-jvm допускается; MICRO-03 multi-jvm конфигурация fail closed; MICRO-04 rotation/migration flags fail closed; MICRO-05 незарегистрированный tenant и неверный CryptoContext не дают plaintext; MICRO-06 после будущего выделения версионированный encrypted envelope переносится побайтно и проходит decrypt в другой реализации порта; MICRO-07 конкурентные реплики + crash/partition после введения распределённого контракта не допускают устаревшей записи/отзыва. MICRO-06/07 — будущие gated тесты, не утверждать выполнение сейчас.

**Приоритет работ:** сначала R1.6B persisted encrypt→reload→decrypt и legacy-key migration, затем transactional fencing/restore; только потом рассматривать физическое разделение и горизонтальное масштабирование.
