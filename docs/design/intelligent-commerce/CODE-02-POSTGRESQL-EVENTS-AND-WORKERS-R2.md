# CODE-02 · PostgreSQL, проекции и доставка
R2.1 · 2026-11-25 · проект для согласования. Источники закреплены на [base_dev](https://github.com/alimbetov/RM/tree/f7910269df73564a6b1bf7d8f45bcc2766cc1b32). Документ уточняет D01, D02 и D05. Префиксы int_, evt_ подтверждены DomainModule/A2; worker grants и очередь требуют допуска.

## 1. Canonical producer B2
~~~java
record SourceFactKey(UUID tenantId, OwnerModule owner, SourceKind kind,
                     UUID sourceId) {}
record CanonicalFact(SourceFactKey key, UUID subjectId,
                     OptionalLong sourceVersion, Instant occurredAt,
                     Optional<Instant> recordedAt, int schemaVersion,
                     ByteDigest digest, SourceFactRef ref) {}

interface CanonicalFactAppender {
    void append(CanonicalFact fact);
}
~~~

CanonicalFactAppender вызывается только из одобренных owner transactions с propagation MANDATORY. Он фиксирует fact/outbox на том же соединении, которое пишет статус или движение. При несовпадающем digest для существующего key transaction завершается конфликтом. Точное совпадение ключа и digest допускает повтор без второго обязательства. Digest считается по канонизации immutable owner fact с именованной версией формата. Значения decimal, Instant, null и поле порядка канонизируются явно. Нестабильный toString объекта для хэша не используется.

| Источник | Точка в коде для A1/B2 | Идентификатор |
|---|---|---|
| PLACED | AutoOrderService.checkoutInternal и checkoutAcceptedQuote. Первому соответствуют checkout, checkoutPublishedQr и checkoutVerifiedPublishedQr | Тот же UUID, который записан в sales_order_status_event. Проверить early return по checkout receipt |
| CONFIRMED/CANCELLED/COMPLETED | SalesInventoryCommandService.appendEvent и SalesPickupHandoverService.complete для QR | Один status_event.id из owner transaction |
| READY_FOR_PICKUP | SalesOrderPreparationService.markReadyForPickup через общую ветку записи stage | status_event.id и безопасная ссылка на order |
| Складское движение | InventoryMovementService, InventoryReservationService при FULFILLED, InventoryTransferService.postLeg | inv_stock_movement.id. Карантинное поступление и sellable movement различаются |
| RESTOCK/QUARANTINE/DISPOSE | SalesReturnPhysicalService.post и SalesReturnQuarantineResolutionService через ReturnInventoryPostingPort | Return posting/resolution — lineage, обычное движение учитывается по movement.id |
| Срок договора или задачи | ContractExpiryPolicy, текущая contract revision, TASK due state/version | Ключ schedule occurrence из scope+subject+revision+window+policy, отдельный от source status_event |

Кандидат интеграции B2: общий PostgreSQL durable feed с новым appender для источника, у которого нет доказанного commit-safe discovery. Переиспользование существующей immutable owner таблицы возможно при том же контракте перечисления и replay. A1 измеряет coverage и atomicity по каждому пути. Наличие status_event не доказывает полный feed. Идентификаторы запроса, fingerprint и дата факта остаются полями доказательства. Дедупликация контракта использует source event key.

## 2. Предлагаемые ключи PostgreSQL
Таблица ниже задаёт минимальную логическую схему. Физические имена, тип UUID generation или bigint generation, формат key dimensions, новые номера changeset и размер batch утверждаются в профильных этапах. Tenant-local FK содержат tenant_id. BYTEA digest фиксируется через length check; money/quantity используют NUMERIC.

| Этап | Row / ключ | Поля, ограничения и назначение |
|---|---|---|
| B2 | CanonicalFeed PK (tenant, owner, kind, source_id) | schema_version, source_version nullable по контракту, subject_id, occurred_at, recorded_at, digest, safe_ref. Immutable. Указатель на устойчивый scope для bounded discovery |
| B3 | ProjectionGeneration PK (tenant, projection_code, generation) | Lifecycle BUILDING, ACTIVE, RETIRED, FAILED. Method/calendar versions, coverage, proof revision, work state |
| B3/B4 | ActiveGeneration PK (tenant, projection_code) | Составной FK к Generation. Единственная точка выбора активной версии для чтения |
| B3 | ProjectionReceipt PK (tenant, projection_code, generation, owner, kind, source_id) | digest, disposition, applied_at. FK к generation. Подтверждает ровно одно применение в эту generation |
| B3 | DailyBucket PK (tenant, projection_code, generation, metric, grain, grain_key, local_day, unit_key, currency_key) | NUMERIC numerator/sum, count/denominator, quality bits. Dimension keys строго типизированы и NOT NULL. Для групп без currency/unit использовать отдельно зарезервированное каноническое значение, которое не совпадает с реальным |
| B4 | RollupBucket PK (tenant, projection_code, generation, metric, grain, grain_key, period_code, period_start, unit_key, currency_key) | Rollup revision и effective/source coverage. Reroll immutable-day value from L1 |
| E1 | RuleHead PK (tenant, rule_id), RuleRevision PK (tenant, rule_id, revision), TemplateRevision PK (tenant, template_id, revision) | Current immutable revision + activation epoch и state в head. Composite FK к template revision |
| E2 | RuleOccurrence PK (tenant, rule_id, owner, kind, source_id) | Captured rule revision, activation epoch, audience snapshot, digest, terminal match/disposition. Для schedules отдельный typed occurrence identity в E3 |
| E2 | DeliveryIntent PK (tenant, intent_id) и UNIQUE (tenant, rule_id, occurrence_key, policy_revision, recipient_id, channel) | state, attempt_count, next_attempt_at, expiry_at, lease_owner, lease_generation, lease_until, error_code. Due/expired indexes ограничивают claim |
| E2 | InAppNotification и DeliveryReceipt PK (tenant, intent_id) | Составная связь с intent. Atomic notification + receipt + state. MOCK имеет отдельный receipt type |

В индексах scoped read начинает с tenant, разрешённого scope и совпадающих exact predicates, затем идёт range/order. Очередь проверяет план для pending, retry и leased expiration как отдельных кандидатов либо через эквивалентный bounded selector. Контракты PostgreSQL-specific запросов квалифицируются отдельно от H2. Keep dual-dialect SQL минимальным. Liquibase выполняет append-only changesets с clean/populated fixtures и rollback/disable rehearsal. Runtime schema и все номера проверяются после Q1. Структуры первых slices могут создаваться независимо. E1 не требует таблиц доставки, B1 не требует проекций.

## 3. INT consumer, late commits и пересборка
~~~java
record ProjectionTarget(UUID tenantId, String projectionCode, long generation) {}
enum ApplyOutcome { APPLIED, ALREADY_APPLIED, OUTSIDE_COVERAGE, FACT_CONFLICT }

interface ProjectionApplier {
    ApplyOutcome apply(ProjectionTarget target, CanonicalFact fact);
}
interface GenerationRebuildService {
    RebuildRef start(VerifiedActor actor, UUID tenantId, RebuildRequest request);
    RebuildProgress status(VerifiedActor actor, UUID tenantId, RebuildRef ref);
}
~~~

1. Процессор выбирает committed facts из durable feed или bounded baseline chunks. Прогресс feed учитывает pending obligations либо anti-join committed rows к receipts для target. Seek по ключу разрешён для стабильного bounded chunk. Restart повторно сканирует unfinished obligations. Поздний commit с меньшим UUID или timestamp остаётся видимым через durable pending work.
2. Одна транзакция берёт общий read lock на маршрут поколения, вставляет ProjectionReceipt с tenant/generation/scope и сверяет digest при конфликте ключа. Новая запись receipt запускает один UPSERT всех затронутых daily buckets. Нестандартный конфликт или ошибка откатывает receipt вместе с buckets. Если у одного source event несколько bucket effects, их порядок стабилен. Selector отдельно выделяет UNKNOWN/invalid facts с устойчивым статусом и причиной, чтобы заведомо неподдерживаемый факт не зацикливал очередь.
3. В B4 затронутые окна L2 маркируются dirty в той же транзакции, что L1. Dirty key включает generation, metric и период. Worker хранит monotonically increasing dirty revision. Он строит новый rollup по согласованному snapshot L1 и завершает только ту dirty revision, которую прочитал. Late fact создаёт следующую revision, которую worker дочитывает. Во время dirty запрещено выдавать устаревший L2 как READY. Ratio хранит числитель и знаменатель, а period rollup считается по точным дням.
4. Rebuild открывает BUILDING generation и включает её в live feed. Backfill берёт согласованные bounded source slices. Live и backfill подают один canonical key в один apply pipeline. Для older sources, представленных только snapshot, A1 задаёт тот же ID или обязательное соответствие ID до допуска. По каждому slice сохраняются coverage, reconciliation proof и список конфликтов. Срок хранения feed покрывает полный rebuild, включая повтор после сбоя.
5. Switch выполняется короткой транзакцией. Все INT apply writers и переключение используют одну serialization boundary на tenant/projection, которую проверяет B4. Быстрая проверка сверяет актуальную proof revision и то, что shadow покрывает факты, учтённые в active в пределах общей области. Для сравнений с другой методикой проверяется coverage и независимый oracle по новой методике. Дорогую сверку выполнить заранее и инвалидировать её при изменении затронутых slices. Если доказательство устарело, operator видит состояние ожидания повторной сверки. Затем указатель меняется атомарно. Новые owner commits продолжают поступать в durable feed, а новый reader видит одно поколение и его freshness. Эта блокировка принадлежит INT consumer и не должна задерживать checkout до завершения rebuild.
6. Retired generation сохраняется в утверждённом rollback window. Возвращать её можно после catch-up либо с явным STALE, как разрешает owner policy. При неподходящей generation cursor выдаёт restart. Публичное поле asOf означает bound evidence и свежесть, привязанные к источнику. Указатель в feed сам по себе полноту не доказывает.

### SQL-пример: транзакционная идемпотентность проекции

Внутри apply transaction после проверки target generation:

~~~sql
INSERT INTO int_projection_receipt
    (tenant_id, projection_code, generation, owner_module, source_kind,
     source_id, fact_digest, applied_at)
VALUES (:tenant, :projection, :generation, :owner, :kind,
        :sourceId, :digest, :appliedAt)
ON CONFLICT (tenant_id, projection_code, generation, owner_module,
             source_kind, source_id)
DO NOTHING
RETURNING fact_digest
~~~

Возвращена строка → рассчитать и записать buckets + dirty markers в той же транзакции. Строки нет → отдельный point read по полному key на READ COMMITTED сверяет committed digest. Одинаковый digest даёт ALREADY_APPLIED. Разный digest даёт FACT_CONFLICT и блокирует продвижение спорного slice. Запись технического incident выполняется после rollback, отдельно от метрики. Неприменимость к конкретной generation описывается disposition receipt. Isolation и deadlock retry политика подтверждаются PostgreSQL suite.

## 4. EVT Java entrypoints и config E1
~~~java
record RuleKey(UUID ruleId, long revision) {}
record PublishRule(UUID ruleId, long expectedHeadRevision) {}
record PreviewRequest(RuleKey rule, PreviewFactRef sample) {}
record Lease(UUID tenantId, UUID intentId, UUID workerId, long generation) {}
enum DeliveryOutcome { DELIVERED, CANCELLED, RETRY, DEAD, LEASE_LOST, NO_WORK }

interface EventRuleService {
    RuleKey saveDraft(VerifiedActor actor, UUID tenantId, RuleDraft draft);
    RuleActivation enable(VerifiedActor actor, UUID tenantId, PublishRule request);
    RuleActivation pause(VerifiedActor actor, UUID tenantId, PublishRule request);
}
interface EventPreviewService {
    PreviewResult preview(VerifiedActor actor, UUID tenantId, PreviewRequest request);
}
interface DeliveryWorker {
    DeliveryOutcome processOne(WorkerPrincipal principal, UUID tenantId);
}
~~~

Типизированный RuleDraft выбирает Trigger, PredicateField и перечисление операторов. Для поля задаются ValueKind и legal operators. CurrencyAmount сравнивается только внутри одной валюты, Quantity требует совместимый SKU/unit. RenderTemplate принимает SafeTemplateContext с фиксированными name/value типами. PreviewResolution проводит source read authorization, visibility и template rendering. Preview возвращает безопасный результат, сохраняя количество строк intent/schedule/delivery неизменным. Draft/config mutations проверяют expected revision. Permit model для управления, human retry и WorkerPrincipal согласуется с IAM A2.

## 5. Admission E2 и linearization
- Первый срез использует PLACED и in-app с отдельным MOCK outcome. При получении source event создаётся одна RuleOccurrence на stable rule + source identity. Вывод revision и activation epoch защищён блокировкой RuleHead. При replay читается уже зафиксированное решение. RuleOccurrence хранит даже no-match/no-audience disposition там, где replay мог бы переоценить активную версию. Зритель, вошедший в роль позднее, не попадает в старый snapshot получателей.
- Момент первой оценки правила и правило возобновления из D05 требуют согласования. Кандидат: durable admission использует активную revision и разрешает только occurrences с событием в current activation window. Delta между event time и commit time явно участвует в тестах. Если точное время пересечения недоказуемо, policy выбирает безопасное пропускание или отказ до уточнения. Replays ранее принятых occurrence не меняют её revision.
- Выборка одного получателя для final transaction опирается на текущий IAM recipient/account/tenant/domain scope и утверждённый warehouse policy. Отдельный IAM worker port проверяет эту eligibility без создания или сохранения пользовательского bearer. Идентификатор worker процесса служит lease identity и сам полномочий не даёт.
- Предложенный порядок final delivery: IAM account/workspace fence → RuleHead lock → intent row → текущее owner occurrence validation для типов, где это требуется. E3 добавляет source current-revision check только после согласования lock graph, чтобы не удерживать очередь при ожидании owner commands. После захвата блокировок берётся текущее время из БД, затем проверяются lease/expiry и eligibility перед effect. Pause/stop держат совместимую serialization boundary. Claim transaction захватывает только queue row и завершает commit до IAM admission.
- Граница revoke: revoke первым commit должен запретить новый effect. Если delivery committed первым, запись остаётся в истории, а просмотр содержимого проходит текущую авторизацию. Timeout/lock ordering ошибки классифицируются и планируют bounded retry. Повтор не меняет logical delivery ID.

## 6. PostgreSQL claim и финальная запись
SQL — эскиз для одного tenant, schema/table имена условны. Используется один DB timestamp на claim. Временной лимит очереди утверждается отдельно.

~~~sql
WITH clock AS (SELECT clock_timestamp() AS now_at),
candidate AS (
  SELECT i.tenant_id, i.intent_id
  FROM evt_delivery_intent i CROSS JOIN clock c
  WHERE i.tenant_id = :tenant
    AND i.expiry_at > c.now_at
    AND i.attempt_count < :max_attempts
    AND (
      (i.state IN ('PENDING', 'RETRY') AND i.next_attempt_at <= c.now_at)
      OR (i.state = 'LEASED' AND i.lease_until <= c.now_at)
    )
  ORDER BY i.next_attempt_at, i.intent_id
  LIMIT 1 FOR UPDATE OF i SKIP LOCKED
)
UPDATE evt_delivery_intent i
   SET state = 'LEASED', lease_owner = :worker,
       lease_generation = i.lease_generation + 1,
       lease_until = c.now_at + :lease_interval,
       attempt_count = i.attempt_count + 1
  FROM candidate q CROSS JOIN clock c
 WHERE i.tenant_id = q.tenant_id AND i.intent_id = q.intent_id
RETURNING i.tenant_id, i.intent_id, i.lease_generation;
~~~

Final transaction inserts notification и receipt с одинаковым logical intent identity. После fence checks status UPDATE имеет WHERE на tenant/intent/state/lease_owner/lease_generation/validity и допускает ровно одну запись. Lost lease откатывает весь final transaction. Потерянный ответ после COMMIT возвращает прежний результат. Generation никогда не уменьшается при operator retry. Для истёкших и исчерпавших попытки задач выделяется bounded sweep: EXPIRED → CANCELLED, исчерпанные attempts без живого lease → DEAD. Иначе последняя lease attempt могла бы навсегда оставить строку вне claim. Состояния и eligibility повторно проверяются в locked row. CLAIM и cleanup гоняются на PostgreSQL. [OidcRevocationOutboxWorker](https://github.com/alimbetov/RM/blob/f7910269df73564a6b1bf7d8f45bcc2766cc1b32/src/main/java/kz/rm/iam/OidcRevocationOutboxWorker.java#L38) показывает separation claim/ack с fencing. EVT использует собственный ledger и собственную security semantics.

## 7. E3 сроки и эксплуатация
ScheduleOccurrence содержит tenant, type, subject, ownerRevision, rule revision, activation epoch, windowStart/end, due, calendarVersion, expiresAt, state. DeadlineWorker выбирает due bounded rows и проверяет текущее owner состояние. ContractExpiryPolicy сохраняет inclusive endsOn, TASK использует TASK due contract. Уведомление о просроченном/изменённом окне получает явный disposition. Restart, tenant stop, повтор lease и late clock фиксируются в operational evidence.
В алертах и audit допускаются code/status/event refs и безопасные counters. Retention contract связывает rule occurrences, notification receipts, projection receipts и фактический replay horizon. Monitor lag отдельно по каждому потребителю. DDL, SQL, retention и worker topology входят в A2/B2/E2 review с approved workload budgets.

## Обязательное дополнение MIC-R1 (2026-11-26)
[RM Mathematical Integrity Contract R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md). Scalar daily counts недостаточны для EXACT_DISTINCT по пересекающимся измерениям. Хранить membership либо эквивалентное точное mergeable состояние; additive measures агрегировать отдельно от membership joins. Независимый proof, revision fence и повторная квалификация обязательны.

## Final technical reconciliation — 2026-11-26
DomainModule and A2 confirm int_/evt_ prefixes. Actual CanonicalFactAppender/Key/Repository/DeliveryService and int_* L1/L2/L3 DDL take precedence over historical examples. No duplicate feed/ledger/projection. Report DDL int_ prefix; EVT worker permissions remain pending. B4 defect-driven freeze.
