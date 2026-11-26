# D05 — Event & Notification Builder
R2.0-draft · SPECIFIED / PROPOSED · Source SHA d9242e539a07ef1843c0e72aba9c651204bf42ae. Owners: source domains + IAM + future EVT owner. New grants, retention and numeric limits OPEN.
Основание: [D01](D01-SEMANTIC-SECURITY-EVENT-CONTRACTS-R2.md), [scope](../../spec/RM-MVP-INTELLIGENCE-EVT-SCOPE-R1.md), PostgreSQL-first transport.

## 1. Граница первого среза
E1: typed rule/template/audience management и preview без delivery. E2: admitted ORDER_PLACED → in-app + mock channel. E3: CONFIRMED/READY_FOR_PICKUP, затем отдельно квалифицированные deadline triggers. Маркетинг, real SMTP/SMS/push, payment/auction/ML/Radar triggers не входят. Нового generic scheduler до proof of clock/recovery не строить.

Rule logical contract: tenant, stableRuleId, immutable revision, triggerType, schemaVersion, closed predicates, authorized scope, audience definition, template revision, channel, active interval/calendar, caps/expiry, createdBy and audit.
Configuration lifecycle proposal: DRAFT → VALIDATED → ENABLED ↔ PAUSED → RETIRED; редактирование опубликованной версии создаёт новую. Preview разрешён только при source permissions и не сохраняет intent, schedule или delivery. Допустим отдельный sanitized configuration audit, не business mutation.

Пользовательские операции: createDraft, validate, preview, enable, pause, retire, deliveryHistory, authorizedRetry. Все названия conceptual; management permissions отсутствуют в текущем registry и требуют A2. Enable scheduled dispatch сохраняет отдельный stage admission из R1; данная документация его не предоставляет.

## 2. Закрытые типы условий и шаблонов
| Trigger | Входная authority | Начальные условия | Особые запреты |
|---|---|---|---|
| ORDER_PLACED | SALES status_event.id + order | source kind, approved scope; amount threshold только при currency | Не использовать email proof/checkout secret |
| ORDER_CONFIRMED | CONFIRMED event + owner order/reservation | scope warehouse лишь с доказанной привязкой | Не подтверждать заказ и не создавать резерв |
| READY_FOR_PICKUP | owner transition + warehouse | allowed warehouse scope | Никаких pickup credentials в payload/link |
| CONTRACT_EXPIRY | contract revision + ContractExpiryPolicy | approved days/window/zone | Renewal/close/cancel инвалидируют schedule |
| TASK_DEADLINE | owner version/due + timing scope | approved window/calendar | Reschedule/complete/cancel инвалидируют occurrence; нет TASK mutation |

Условие — набор разрешённых операторов EQ/IN/GT/GTE/LT/LTE над typed allowlist. Null operands не превращаются в 0; unknown required fact блокирует intent/даёт диагностический статус. Максимальные число условий/IN-size/template length/recipient count утверждаются до E1; произвольные SQL, SpEL, JavaScript, вложенные шаблоны, HTTP callbacks не допускаются.

Template placeholders берутся только из safe typed projection. Минимальное сообщение — event code + безопасная подпись + owner reference. Цена/имя/детали не копируются в общий intent без обоснования permissions и retention. Escape зависит от будущего renderer; plain text baseline. Ссылка несёт opaque reference, authorization выполняется при открытии. Read notification после отзыва прав также не должен раскрывать сохранённый закрытый payload.

## 3. Кому и когда разрешена доставка
Audience из разрешённых tenant roles или explicit eligible users; правило не расширяет domain grant. Creator authorization проверяется при config mutation, recipient — при intent admission, final in-app commit и открытии detail. Не пересылать credential пользователя worker-у. Trusted worker identity имеет узкий approved protocol, не общий admin bypass.

Предлагаемая семантика первого среза: событие связывается с одной активной revision данного stableRuleId при durable admission. Новая revision не переигрывает прошлые события автоматически. Recipient set определяется для этого admission и сохраняется без избыточного PII; новые участники роли не получают старые события без отдельного явно разрешённого replay режима. Ушедший из роли/заблокированный recipient отсекается при final delivery.

Revision в dedup key сама по себе недостаточна: повтор source event после правки rule не должен создавать новую business notification. Нужен immutable binding (tenant,stableRuleId,canonicalOccurrence) → chosen policyRevision/audience selection. Уникальность delivery затем включает occurrence+rule+revision+recipient+channel. Конкретные constraints выбираются на E2 после A2/B2; семантика policy cutover OPEN для owner review.
Один и тот же sourceEventId с изменённым digest → conflict/security diagnostic, не новая доставка.

Service consent и marketing consent различаются; существующий email_consent_event не даёт автоматического права на любое сообщение. Для in-app достаточно утверждённой service eligibility policy; real external delivery потребует собственного scope/provider/consent решения.

## 4. Durable intent state machine
| Состояние | Допустимое действие | Результат и доказательство |
|---|---|---|
| PENDING | Worker claim | LEASED, увеличенная leaseGeneration, leaseUntil |
| LEASED | Eligible in-app insert + receipt | DELIVERED в одной DB transaction |
| LEASED | Transient error | RETRY с nextAttemptAt и safe error class |
| LEASED | Permanent error / attempts limit | DEAD с terminal evidence |
| PENDING/RETRY/LEASED | Disable/revoke/expiry/tenant stop до final effect | CANCELLED; stale worker fenced |
| RETRY | Due + still admitted | LEASED с новой generation |
| LEASED expired | Recovery claim | Новая generation; прежний worker теряет право ACK |
| DEAD | Authorized retry | Тот же logical delivery identity, новая attempt; повтор eligibility |
| DELIVERED/CANCELLED | Replay | Без изменения бизнес-эффекта; delivered history сохраняется |

DEAD retry не создаёт новый notification ID. CANCELLED не оживает автоматически после re-enable; повторное событие той же occurrence не отменяет терминальное решение. Resume policy для существующей очереди явно версионируется; рекомендуемый первый вариант — отменить pending старой activation и принимать только новые occurrences.

Claim короткой транзакцией с SKIP LOCKED/limit; processing не держит owner locks во время ожидания. Final in-app transaction заново проверяет tenant/account/permission/policy и generation. Точный lock DAG совместно с IAM должен быть проверен до реализации: claim не удерживает delivery row lock при входе в конфликтующий identity lock path. Rule pause/tenant stop и dispatch должны иметь общий fencing protocol.

Условный UPDATE leaseGeneration в финале обязателен. Stale ACK, heartbeat или failure update не меняет результат нового владельца. Leases используют авторитетное DB time либо доказанную clock policy; DST касается расписаний, не elapsed lease arithmetic.

## 5. Сбои и atomicity
- Owner rollback → нет committed event и доставки.
- Crash до intent commit → повтор admission создаёт тот же binding/intent.
- Crash после intent commit → claim продолжает существующий intent.
- Crash до in-app transaction commit → notification и receipt отсутствуют.
- Crash после commit до ответа → replay находит единственный notification/receipt.
- Analytics consumer может завершить при недоступном EVT; общей mutable receipt нет.
- Повтор owner command не должен производить новый canonical event для того же перехода.
- Pause/revoke первым commit запрещает subsequent final delivery. Уже допущенная transaction может завершиться до pause/revoke по согласованной сериализации.
- Notification open всегда reauthorized; ранее совершённая доставка не является бессрочным read grant.

Exactly-once здесь — единственный атомарный in-app эффект по logical key. Не обещается exactly-once внешней сети. MOCK означает фиксацию симуляции, не реальный provider ACK.

## 6. Deadline design (E3, gated)
Schedule хранит subject/source revision, due date/Instant, calendar/policy revision, occurrence window, expiry и state. Источник времени контролируемый Clock, доменные правила неизменны. Contract endsOn inclusive; TASK timing берётся owner projection.
На delivery повторно проверяются revision/current state и окно. Renewal/reschedule создаёт новую occurrence по owner policy и отменяет устаревшую. Нельзя отправить старое предупреждение после завершения задачи.
Missed-window/catch-up policy OPEN: предложено не отправлять просроченные предупреждения вне validity, сохранять EXPIRED disposition. DST overlap/gap выбор локального времени, restart/backlog, изменение timezone и paused interval — отдельные случаи D07.

## 7. Эксплуатация, retention и exit
Метрики: pending/retry/dead/cancelled counts в разрешённом tenant scope, oldest eligible age, attempts, stale-lease rejects, processing lag и classified errors. Counts не обходят domain privacy. Max batch/concurrency/retry/TTL/backoff/recipient fanout и quotas OPEN; должны быть положительными bounded policies, approved до квалификации.
Retention для payload, receipts и occurrence bindings согласуется с source replay horizon. Нельзя удалить дедупликацию, оставив возможность replay с прежним ключом. Диагностика без bearer/email/phone/QR secret.
Exit E1 — безопасный preview/permissions/closed DSL; E2 — B2 source atomicity + concurrent PG workers + crash/revoke proof; E3 — отдельно clock/schedule cancellation. IE-Q20…28, MC-03/04/05, existing owner regressions.

## Обязательное дополнение MIC-R1 (2026-11-26)
[RM Mathematical Integrity Contract R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md). Предикаты метрик используют methodVersion, cohort, UOM, scope и quality. MISSING не равен zero; distinct counts не суммируются.


## D06B cross-reference
[Ночные микроотчёты и оперативные FX-триггеры](D06B-NIGHT-REPORTS-OPERATIONAL-TRIGGERS-R2.md) используют существующий EVT после квалификации; не вводят второй event bus и не разрешают автоматические команды в обход owner gates.
