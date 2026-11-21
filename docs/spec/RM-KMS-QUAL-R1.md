# RM-KMS-QUAL-R1 — обязательная матрица тестов внутреннего криптомодуля

**Статус:** нормативное приложение к `docs/spec/RM-KMS-SPEC-R1.md` (тесты определены; не выполнены).  
**Среды:** Java 21 / JUnit 5 / AssertJ, H2 profile `dev`, PostgreSQL profile `test`, PostgreSQL Testcontainers для изолированного CI.  
**Граница:** модульный монолит, 1 JVM, AES-256-GCM, два KMS SQL table, три циклических слота; без Redis и lazy re-encryption.

## 1. Стратегия

- В дополнение к существующим **KMS-01..32** специфицировать тесты `CRYPT-*`, `KEY-*`, `SLOT-*`, `BATCH-*`, `REC-*`, `ENV-*`. Каждый идентификатор — отдельный named JUnit test / parameterized test с надёжным oracle. Тесты на локальной H2 **не** заменяют PostgreSQL.
- UNIT: детерминированные тесты JCA, envelope codec, типизированных контрактов, инъекция clock/secure test fixtures без мокирования самой AES-GCM логики. Известные AES-GCM vectors должны сверяться с независимым эталоном (NIST KAT или независимая проверенная реализация).
- PROPERTY: seed фиксируется и публикуется в ошибке; 1000 рандомизированных прогонов для crypto invariants, отдельно mutation tests (nonce/tag/AAD/ciphertext). Непреднамеренная коллизия nonce не доказывается отсутствием в 1000 случаях — гарантия обеспечивается корректной CSPRNG и протоколом.
- H2: Liquibase + JPA validation, provision, transitions, nightly worker smoke/rollback. PG: production-grade SQL locking, concurrency, transactional boundaries, recovery, partitioning, isolation; Testcontainers database per test suite.
- Не хранить тестовые приватные Root KEK в production config и не публиковать plaintext/decrypted values в CI logs.
- Все тесты MUST иметь expected outcome (ниже), negative tests должны проверять конкретный отказ, отсутствие тихого plaintext fallback, отсутствие частично сохранённых транзакций и отсутствие двойных счётчиков.

## 2. Матрица сценариев

### A. AES-256-GCM и envelope

**Исполнение:** UNIT

| ID | Сценарий | PASS oracle |
|---|---|---|
| CRYPT-01 | UTF-8 кириллица/казахский/emoji | encrypt→decrypt возвращает побайтно исходный UTF-8; одинаковый контекст |
| CRYPT-02 | Пустая строка | round-trip пустой строки корректен; NULL отдельно от пустой строки |
| CRYPT-03 | 1 байт, 16 байт, 4096 байт, 1 МБ | round-trip совпадает; верхний лимит поля CRM задаёт домен |
| CRYPT-04 | Unicode NUL/комбинируемые символы | нет тихой нормализации или обрезания |
| CRYPT-05 | Повторное шифрование одинакового plaintext | nonce и ciphertext различаются, оба расшифровываются |
| CRYPT-06 | Nonce 12 байт и тег 16 байт | точные длины, генерация свежего nonce для каждого encrypt |
| CRYPT-07 | Повреждение одного байта ciphertext | AEADBadTagException/доменное CryptoIntegrityException; никакого plaintext |
| CRYPT-08 | Повреждение authentication tag | отказ, никаких частичных данных |
| CRYPT-09 | Подмена nonce | отказ GCM tag |
| CRYPT-10 | Подмена generation в envelope | отказ, без перебора слотов/подстановки ACTIVE |
| CRYPT-11 | Подмена formatVersion / неизвестная версия | отказ до расшифрования |
| CRYPT-12 | Nonce длины 0/11/13 и усечённый tag | отказ валидации; без необработанных массивов |
| CRYPT-13 | Конверт с экстремальными размерами/отрицательным generation | отказ до выделения опасных буферов |
| CRYPT-14 | Перенос между двумя tenant | отказ AAD/несовпадение tenant |
| CRYPT-15 | Перенос между clientId A и B внутри tenant | отказ аутентификации AAD |
| CRYPT-16 | Перенос между fieldName phone/email | отказ аутентификации AAD |
| CRYPT-17 | Перенос между entityType client/contact | отказ аутентификации AAD |
| CRYPT-18 | Воспроизводимый test vector с фиксированными key/nonce/AAD | точный ciphertext/tag совпадает с независимым JCA или опубликованным AES-GCM KAT; fixed nonce только в тесте |
| CRYPT-19 | Сериализация/десериализация envelope | строгий бинарный контракт round-trip; некорректные длины/поля отвергаются |
| CRYPT-20 | Шифрование случайных значений в property-based 1000 прогонов | round-trip, уникальность nonce (в выборке), неправильный AAD/битовая порча отвергаются |

### B. KEK, wrapping и Caffeine

**Исполнение:** UNIT/H2

| ID | Сценарий | PASS oracle |
|---|---|---|
| KEY-01 | Генерация DEK | длина ровно 256 бит; 1000 генераций без совпадений в испытании; нет предсказуемого seed |
| KEY-02 | Wrap/unwrap под корректным Root KEK | побайтная эквивалентность DEK |
| KEY-03 | Неверный Root KEK | fail closed и отсутствие DEK в кэше |
| KEY-04 | Wrap payload с нарушенным tenant/generation/purpose | отказ аутентификации |
| KEY-05 | Нет Root KEK / неверная длина / неверный base64 | start-up/config validation fail closed |
| KEY-06 | Cache MISS + два параллельных запроса | однократная загрузка ключа по cache key; корректные результаты |
| KEY-07 | Cache HIT после первого доступа | 0 дополнительных SQL к KMS; не обещать 0 SQL CRM |
| KEY-08 | TTL/size eviction и повторный запрос | повторная загрузка только разрешённого поколения; корректность сохраняется |
| KEY-09 | READY/REVOKED/архивный generation | ordinary encrypt/decrypt не использует эти поколения даже при записи в cache |
| KEY-10 | Promotion + afterCommit invalidation | после commit encrypt использует new ACTIVE; после rollback сохраняет old ACTIVE |
| KEY-11 | Секреты в log/error/metrics | нет DEK, Root KEK, ciphertext/plaintext и ключевых байтов в сообщениях |
| KEY-12 | Тест Root KEK rotation с историческим wrapping_key_id | старые wrapped DEK открываются соответствующей сохранённой версией KEK или безопасный отказ |

### C. Three-slot state и миграция схемы

**Исполнение:** H2/PG

| ID | Сценарий | PASS oracle |
|---|---|---|
| SLOT-01 | Provision tenant без ключей | ровно одна строка: A=ACTIVE v1, B/C=EMPTY |
| SLOT-02 | Повторный provision и конкурентное создание | идемпотентно; unique PK; не потерян существующий DEK |
| SLOT-03 | Первый rotate | v1 DECRYPT_ONLY, v2 ACTIVE; строго одна ACTIVE |
| SLOT-04 | Создание READY после VERIFIED миграции | свежий v3 READY; не доступен ordinary crypto |
| SLOT-05 | Цикл A→B→C→A с генерацией v4 | версии возрастают; старый слот переиспользуется только после архивации |
| SLOT-06 | Неполная миграция и срок 90 дней | нет принудительного удаления; rotation overdue |
| SLOT-07 | Два одновременных rotate | один согласованный переход без двойного ACTIVE/потери поколения |
| SLOT-08 | Ошибка commit archive или rotation | полный rollback, старый ключ остаётся доступным |
| SLOT-09 | Недопустимые состояния слотов, дубли generation, отрицательные counters | CHECK/валидация отклоняют запись |
| SLOT-10 | Миграция legacy security.tenant_key с >3 поколениями | лишние поколения архивированы без потери; правильный ACTIVE и доступные данные |
| SLOT-11 | Legacy corruption / overlapping ACTIVE | migration отказывает без удаления и с диагностикой |
| SLOT-12 | Повторный запуск Liquibase и JPA validate | идемпотентность changesets, совпадение schema/entity H2 и PostgreSQL |

### D. Nightly worker 100 клиентов

**Исполнение:** H2/PG

| ID | Сценарий | PASS oracle |
|---|---|---|
| BATCH-01 | 0 клиентов | 0 обновлений, stats unchanged, no errors |
| BATCH-02 | 1/99/100/101/201 клиентов | соответственно 1/1/1/2/3 batch; <=100 distinct clients в транзакции |
| BATCH-03 | Клиент имеет несколько зашифрованных контактов | перенесены все eligible encrypted fields, счетчик учитывает поля, не клиентов |
| BATCH-04 | Смешанные generations в полях одного клиента | обновлены только OLD, current/READY не меняются |
| BATCH-05 | Rollback после частичного обновления 100-го клиента | данные и stats не изменены; повтор чистый |
| BATCH-06 | Повтор успешного batch | нет повторного изменения уже ACTIVE envelope и double-count |
| BATCH-07 | Crash между commit и следующим batch | после рестарта продолжение по persisted old-generation refs |
| BATCH-08 | Параллельное редактирование контактного поля | optimistic conflict→retry; ни одного lost update |
| BATCH-09 | Конкурентный rotate при активном CRUD | после успешного promotion нет новых committed old-generation envelopes |
| BATCH-10 | Остановка окна 05:00 Asia/Almaty | новые batch не стартуют; начатый batch завершён/откачен безопасно |
| BATCH-11 | Повторная transient DB ошибка | <=3 retry с backoff; после исчерпания ошибка и безопасный stop tenant |
| BATCH-12 | Одна компания с ошибкой, другая исправна | нет cross-tenant миграции; политика продолжения tenant ясно отображена в audit |
| BATCH-13 | Remaining=0, но OLD ciphertext в msg/order | slot reuse запрещён при полном scan |
| BATCH-14 | Снятый/не зарегистрированный module scanner | verification gate FAIL CLOSED |
| BATCH-15 | Батч из клиентов с большим количеством контактов | ограничен по времени/ресурсам; rollback без частичной миграции; метрики latency |
| BATCH-16 | Статистика после успешного батча | delta совпадает с количеством фактически обновлённых envelope в одной DB transaction |

### E. Archive, backup, recovery

**Исполнение:** PG/RECOVERY

| ID | Сценарий | PASS oracle |
|---|---|---|
| REC-01 | Rewrap old DEK Root KEK→Archive KEK | после unwrap архивный DEK совпадает; binding tenant+generation+purpose |
| REC-02 | Archived key с неверным Archive KEK | отказ без plaintext; слот не освобождён |
| REC-03 | Архивация и очистка слота с откатом | безопасный rollback обеих операций |
| REC-04 | Восстановление старого DB snapshot после reuse slot | decrypt старых envelope через контролируемый архив |
| REC-05 | Обычный CRM decrypt archived generation | отказ, archive не используется ordinary path |
| REC-06 | Отсутствует версия archive wrapping KEK | fail closed + recoverability incident |
| REC-07 | Независимая копия архива утрачена | перезапись старого слота запрещена по recovery gate |
| REC-08 | Повреждённая запись архива или подмена tenant/version | GCM/constraints отвергают восстановление |
| REC-09 | Отзыв/revocation в восстановленном старом snapshot | не допускается возобновление штатного шифрования отозванным generation |
| REC-10 | Recovery role/authorization + audit | обычный пользователь не может инициировать операцию; аудит без секретов |

### F. Профили, PostgreSQL и безопасность

**Исполнение:** H2/PG/CI

| ID | Сценарий | PASS oracle |
|---|---|---|
| ENV-01 | dev profile H2 boot + Liquibase + health | smoke pass; только ephemeral H2; ddl-auto=validate |
| ENV-02 | test profile PostgreSQL boot + Liquibase | schema validate; отказ без DB credentials; не подключается к production |
| ENV-03 | PostgreSQL HASH archive по tenant_id | 8 partitions, маршрутизация, PK tenant+generation уникален |
| ENV-04 | Unit/H2 tests без внешней PostgreSQL | детерминированно выполняются локально; test DB не используется |
| ENV-05 | PG интеграционные тесты с отдельной disposable DB | нет удаления или изменения общей rm_test; Testcontainers предпочтительно |
| ENV-06 | Сбой БД во время crypto cache miss | чёткий отказ; без fallback к неподтвержденному ключу |
| ENV-07 | Сбой БД при работающем cache hit | существующие разрешения не расширяются; поведение документировано |
| ENV-08 | Параллельные разные tenants | нет пересечения кэша, counters и транзакций |
| ENV-09 | Бенчмарк p95/p99 crypto cache hit, batch duration, DB lock | измерения собраны на PostgreSQL; пороги устанавливаются до production gate |
| ENV-10 | CI evidence | команда, версия Java, DB, commit, результаты, skipped tests и логи без секретов сохранены |

## 3. Обязательные quality gates

**Всего новых сценариев:** 80 (A: 20; B: 12; C: 12; D: 16; E: 10; F: 10). Совместно с KMS-01..32 — **112 идентификаторов сценариев**. Это инвентарь требований, не количество уже работающих JUnit тестов.

| Gate | Требование |
|---|---|
| G0 Crypto | Все CRYPT и KEY GREEN; любое tampering / неправильное назначение отклонено; тест-векторы AES-GCM |
| G1 Schema | SLOT/ENV schema GREEN на H2 и PostgreSQL; legacy migration сохранена; уникальная ACTIVE |
| G2 Data integrity | BATCH GREEN на PostgreSQL; ни одного lost update, double-count или необоснованного slot reuse |
| G3 Restore | REC GREEN на восстановленном независимом snapshot; все нужные Root/Archive KEK версии доступны |
| G4 Release | Все P0 и KMS-01..32 GREEN на предназначенных средах; 0 unexplained skipped; security review; p95/p99 и recovery time проверены |

**Не путать coverage и функциональную правильность**: цель line coverage ≥ 90% и branch coverage ≥ 85% для `kz.rm.security.kms` (JaCoCo) — минимальные измеримые пороги; для критичных веток error handling, decrypt, nonce/tag verification, key transitions, archive recovery — обязательны адресные негативные тесты, даже при 100% coverage. Не исключать из отчёта классы crypto, codec, KMS transitions, recovery или worker. Пороги не заменяют инварианты и независимую security review.

**Отдельные запускные группы:** `unit` (без БД), `dev-smoke` (H2), `pg-integration` (PostgreSQL Testcontainers), `recovery` (только изолированные backup fixtures), `performance` (не блокирует локальный быстрый smoke, но обязательна до production). Рекомендуется JUnit tags или Maven Failsafe, а не новый Spring profile. Spring profile `test` относится к PostgreSQL; не смешивать его с JUnit-тегом `unit`.

**CI evidence:** сохранять commit SHA, Java/Maven/PG versions, exact test count, passed/failed/skipped, duration, JaCoCo XML+HTML, анонимизированные причины failures. Не заявлять GREEN, пока задания реально не запускались.

## 4. Failure injection и недетерминированные гонки

- Clock injection: границы 02:00, 05:00 и timezone transition; `Asia/Almaty` использовать через `ZoneId`, не кодировать смещение вручную.
- Failure injection points: **до открытия batch tx**, **после чтения**, **после decrypt**, **после обновления части клиентов**, **до commit**, **после commit**, **при очистке cache**, **при архивном rewrap**, **при записи backup**.
- Concurrent tests должны использовать барьеры (`CountDownLatch`/`Phaser`), заставляющие конкретный interleaving, а не `Thread.sleep` с надеждой поймать гонку. PostgreSQL testcontainers > H2 для проверок DB locks.
- Все recovery tests выполняются на копиях, а не на рабочем `rm_test`. Вести cryptographic restore oracle — HMAC/sha256 от ожидаемого исходного тестового файла **вне** восстанавливаемой БД, сверять побайтно.
- Условия для безопасной перезаписи слота: `verifiedZeroOldReferences == true`, `oldWritersDrained == true`, `archiveRestoreVerified == true`; сами счётчики `remaining=0` НЕ являются достаточными.

## 5. Definition of Done

1. Для каждого сценария есть тестовый класс/метод, входные данные, ожидаемый oracle и машина/DB исполнения.
2. Все P0 требования ревью покрыты обязательными негативными и concurrency tests.
3. Результаты H2, PostgreSQL и Recovery в CI воспроизводимы; пропущенные тесты запрещены без оформленного release waiver.
4. Отдельно доказаны encryption/decryption round-trip, tamper rejection, cross-tenant/context isolation, safe rotation, batch restart, archive restoration и отказ при отсутствии KEK.
5. `productionAdmission = HOLD` пока G0..G4 не завершены.
