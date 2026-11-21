# RM-AUDIT-NIGHTLY-RETENTION-R1 — автоматическая ночная очистка истории

**Дата:** 2026-11-21. **Ветка:** `feat/rm-kms-r1-implementation`.
**Статус:** реализована кодовая вертикаль retention для AUDIT, PostgreSQL qualification; **production enablement = HOLD** до юридической и операционной приёмки. Другие домены не очищаются автоматически этим worker.

## 1. Решение и срок хранения

- Период хранения истории задаётся **по tenant**: `retention_years = 3, 4 или 5`. Для нового tenant, у которого нет утверждённой политики, **ничего не удалять**.
- При согласованной политике рекомендуется default **5 лет**, срок **3–4 года** разрешать лишь после определения класса документов и применимого обязательного минимального срока. Нельзя очистить финансовые документы, доказательства согласия или историю безопасности по обычному общему CRM TTL.
- Отдельные записи могут иметь `legal hold` (расследование, спор, судебное дело); такие записи не удаляются независимо от срока. Основание хранится как код, не свободный текст с PII.
- Разделить **retention expiry** и **privacy erasure**: запрос удалить PII требует собственного workflow, а не скрытого сокращения финансового срока. Backup/архивы имеют отдельные правила.
- Время удаления считается в PostgreSQL от `CURRENT_TIMESTAMP` с календарным интервалом `N years`, строго **раньше** cutoff; пограничная дата сохраняется. Время поля истории — `occurred_at`, не `created_at` базы.

## 2. SQL / Liquibase

**Новый changeset `rm:0020-audit-retention-governance`**:
- `crm.audit_retention_policy`: tenant UUID, срок 3–5 лет, `enabled`, `approved_at`, `updated_at`, `row_version`; CHECK не допускает enabled без approval.
- `crm.audit_retention_hold`: `(tenant_id,event_id)`, `reason_code`, `created_at`, `released_at`. Не удалять запись при `released_at IS NULL`.
- `crm.audit_retention_run`: id, начала/завершения, удалено, число батчей, исход RUNNING/DONE/FAILED/SKIPPED.

**Базовое audit partitioning** уже создано в `rm:0018`: годовые `RANGE(occurred_at)` 2025–2027 и DEFAULT, по четыре `HASH(tenant_id)` leaf each (16 leaf partitions). Новая retention политика **не** меняет partition key, PK, KMS или старые changesets.

## 3. Ночной алгоритм

1. При явном `RM_RETENTION_ENABLED=true` Spring Scheduler запускает очистку ежедневно в **02:30 Asia/Almaty**. Можно переопределить cron и time zone.
2. Каждая короткая транзакция пытается получить общий `pg_try_advisory_xact_lock(namespace,key)`. Если другой pod держит его — пропустить batch (SKIPPED), без перекрывающихся bulk deletes.
3. Выбирать не более `RM_RETENTION_BATCH_SIZE=500` событий, просроченных по разрешённой политике tenant, без активного hold. `FOR UPDATE OF a SKIP LOCKED`.
4. Составлять `DELETE FROM crm.audit_event` через `(tableoid,ctid)` выбранных физических строк; `ctid` применяется **только в рамках одной транзакции**, не сохраняется как identity. Это работает при `RANGE→HASH`.
5. Повторить до `RM_RETENTION_MAX_BATCHES=10` батчей, не более **5000 удалений за ночь** при исходных настройках. При большом backlog остаток перейдёт на следующую ночь; p95/p99, WAL, autovacuum и нагрузка должны определять дальнейший лимит.
6. Записать результаты в `crm.audit_retention_run`; логировать только runId и счётчики — никаких ФИО, email, токенов, payload.
7. Не изменять/не отзывать user session, order statuses, payments или KMS. Публичных endpoints для purge нет.

Настройки:
```yaml
rm:
  retention:
    enabled: ${RM_RETENTION_ENABLED:false}
    cron: "${RM_RETENTION_CRON:0 30 2 * * *}"
    time-zone: "${RM_RETENTION_TIME_ZONE:Asia/Almaty}"
    batch-size: ${RM_RETENTION_BATCH_SIZE:500}
    max-batches: ${RM_RETENTION_MAX_BATCHES:10}
```

**Важно:** ночной batch DELETE — не то же самое, что `DROP PARTITION`. В пределах одной временной секции могут сосуществовать 3- и 5-летние политики и legal holds. Грубый `DROP` удалил бы неистекшую историю. Для безопасного `DETACH/DROP PARTITION` требуется отдельный DBA job с доказательством, что все строки/сроки/holds разрешают удаление, backup/restore и самостоятельный release-gate. Не включать drop в основном worker до этой квалификации. При необходимости реального бесшовного drop группировать будущие события в разные retention-class partitions на этапе новой схемы, а не менять сроки существующих секций задним числом.

## 4. Технические и юридические ограничения

- `RM_RETENTION_ENABLED` **false по умолчанию**, чтобы CI, локальный ноутбук и неподтверждённое production окружение ничего не удаляли.
- Политики создаются/утверждаются привилегированным DB/service operator, не публичным контроллером. Изменения `enabled,years,approved_at` и `hold/released_at` должны аудироваться отдельным защищённым контуром. До него policy administration — ручная, с ограниченной ролью.
- Конкурентная установка hold и delete требует строгой governance-процедуры: сначала остановка/фенсинг retention, затем изменение hold, затем возобновление. Без такого протокола **нет** гарантии сохранения записи при одновременной admin-вставке hold. Для production нужен совместный advisory lock в hold-admin сервисе.
- Нельзя выдавать общему приложению произвольный `DROP PARTITION`, `TRUNCATE` или DDL.
- При будущем append-only/tamper-resistant audit требуются обособленные права: write-only ingest vs retention deleter; нельзя использовать обычную DB роль приложения с неограниченным `DELETE` как доказательство неизменяемости.
- 2028+ новые annual partitions должны создаваться заранее отдельным migration/admin job, иначе записи попадут в DEFAULT. Не создавать секции из транзакции заказа/просмотра.

## 5. PostgreSQL E2E

`AuditRetentionQualificationTest` проверяет: 3 года vs 5 лет; устаревшие записи исчезают, неустаревшие остаются; legal hold сохраняет старое событие; tenant без политики не чистится; неподтверждённая политика не может быть включена; ограничения по batch size/maxBatches; журнал запусков фиксирует результат.

**Обязательные остаточные gates:**
1. Fresh + populated PostgreSQL upgrade; migration rollback/PITR, неизменные timestamps/timezone.
2. Multi-pod scheduler race / latency / WAL / autovacuum, репликационный лаг, длинные транзакции.
3. Security DB GRANT на update policy/hold и delete audit отдельно от обычного бизнес-пользователя.
4. S2/юридическое утверждение политики сроков 3/4/5 лет по классам данных и закону, retention и backup/archives.
5. Фактическое включение feature flag только после выпускной приёмки и dry-run/statistical inventory.

## 6. Следующее развитие

**RETENTION-R1.1:** admin approval API с principal-derived actor, shared lock при hold change, READ ONLY dry-run count по tenant и классу хранения, owner-scoped metrics/alerts и PostgreSQL failover tests.

**RETENTION-R1.2:** измерение 3–5-летнего масштаба, управление range partitions за пределами 2027, при наличии чистых целых buckets — DBA-qualified `DETACH/DROP` по отдельной maintenance процедуре.
