# D06B — Ночные микроотчёты и оперативные триггеры: интеграция существующих контуров
2026-11-26 | DESIGN ONLY | PRODUCT DIRECTION CONFIRMED | IMPLEMENTATION NOT ADMITTED | PRODUCTION HOLD.
Design parent d87b68e2f289c2682f7503b75c1215a64dcd7215; выборочная сверка base_dev b013b5d13e3fa0bd205503e73b8ec8bc189869c0.

## 1. Решение владельца
Не расширять архитектуру RM. Ночные микроотчёты и оперативная реакция — сквозные сценарии существующих Intelligence, EVT, Currency/Pricing, File Service, Radar, IAM и TASK. Не вводить B6/B7, отдельный REPORTING, новый Dynamic Pricing Engine, параллельный event bus/scheduler или хранилище. B4 FREEZE, B5 roadmap неизменен. Только доказанные интеграционные пробелы после inventory; MVP CR и действующие admission gates сохраняются. Не считать эту запись разрешением на коммерческую мутацию, Radar resumption или production.

## 2. Source alignment
| Контур | Выборочно обнаружено в base_dev | Не доказано |
|---|---|---|
| Intelligence | BusinessObservationService, BusinessAnomalyAssessment, AuthorizedAnalyticalReadService | полнота исторической READY-coverage, ночной pipeline |
| FX | FxRateSnapshotService, TrustedNbrkIngestionService, NbrkRateSnapshotReader, SkuFxCandidateCalculator, SkuFxSellerApprovalService | production ingestion и весь lifecycle автоматизации |
| FX AUTO | FxAutoPersistentDryRunService, FxAutoExecutionPreparationService, FxAutoOwnerConsentService, FxAutoTenantBudgetGuard | фактический auto apply: проверенный код явно не даёт execution authority или price mutation |
| Files | FileOwnerAccessPort, FileUploadCompletionService, FileAttachmentService, RustFsFileStorage | report owner binding, worker identity и разрешённые MIME |
| EVT/Radar/TASK | [D05](D05-EVENT-BUILDER-DELIVERY-R2.md), [D06A](D06A-RADAR-ADAPTATION-AFTER-R2.md), [TCRUD](RM-TCRUD-R2.md) | полный method inventory и runtime интеграция |
| PDF/XLSX | требуемые форматы | наличие генераторов и зависимостей не проверено |
Классификация PRESENT/PARTIAL/MISSING/UNKNOWN; наличие класса не означает runtime admission. Не создавать новый домен ради непроверенного метода.

## 3. INT-R2-NIGHT — ночная обработка
Запуск после доказанного завершения агрегации для tenant и закрытого бизнес-периода, не по одному wall-clock. Использовать существующий scheduler/worker после квалификации. Зафиксировать tenant, calendar/version, period, scope, currency/UOM, method, generation/proof, source revision, coverage, policy revision, evidence. Не смешивать разные snapshot/revision. B5 анализирует наблюдения, тренды и аномалии; значимые выводы, допустимые гипотезы и рекомендации оформляются в микроотчёт. PARTIAL/MISSING не превращать в READY/zero/причину/прогноз. Только диагностические рекомендации проверить данные при недостаточной полноте.

Pipeline: existing aggregation completion → B5 → report admission → PDF и/или XLSX с диаграммами → File Service/RustFS → Radar краткий вывод, приоритет, ссылка на авторизованную деталь. Morning Brief — представление, не сервис. Tenant timezone/calendar/schedule. Значимые события или явно запрошенные отчёты; стабильные результаты в сводку; повторный сигнал дедуплицировать. Не генерировать декартово произведение SKU × склад × город × область × группа × период. Бюджеты/top-K по измерениям. Не объявлять SLA без нагрузки.

PDF: компактный вывод, период, значения/единицы, диаграмма, доказательства и ограничения, рекомендованные проверки. XLSX: summary, observations, calculations, charts, разрешённая детализация, evidence/metadata. Единый immutable payload для обоих форматов, без повторной независимой арифметики; числа и графики сверяются с B5. Новая ревизия при late event/rebuild, старый документ неизменяем. Не обещать все 112 семейств: только отдельно квалифицированные источники/методы.

## 4. EVT-R2-FX — оперативная реакция
Не ждать ночи. Committed qualified FX revision → существующие currency/price policies → зависимые товары и услуги (только owner-supported scope) → existing candidate/dry-run/approval → Radar summary + при необходимости отчёт. FX effective/observed time, rate source, revision, quality обязательны. Stale/synthetic/unapproved блокируют коммерческую запись. Existing EVT occurrence/dispatch — кандидат после проверки реальных контрактов. [CUR-R2C](../../spec/RM-CUR-R2C-SMART-FX-SHIELD-CONTROLLED-AUTO-PRICING-R1.md) нормативен; не дублировать формулы, consent, budget guard или price history.
Фактическая автоматическая корректировка только после отдельного допуска существующего canonical owner command: reauthorize, действующее согласие, версия политики/offer/price, лимиты, бюджет, rounding, currency, договорные ограничения, идемпотентность, audit. Подтверждённые заказы/принятые предложения не переоценивать молча. Повтор события не порождает повторное изменение. Отзыв полномочий, устаревшая ревизия, гонка цены блокируют. Откат — только разрешённая компенсирующая owner операция. Не выдавать preparation/dry-run за исполнение. Auto apply HOLD, scope услуг отдельно проверить. Другие операционные триггеры только через действующие EVT/owner contracts.

## 5. Общий delivery/security контракт
В Radar хранить report reference, не публичный постоянный URL. При просмотре summary, списка, count, evidence, detail и binary download заново проверять tenant/current permissions/warehouse scope и owner lifecycle. Не публиковать полный tenant report сотруднику с частичным складским доступом; нельзя решить утечку одним фильтром UI. Worker identity/least privilege требует действующего owner admission; не имитировать VerifiedActor. File Service применяет свои upload/completion/scan/attachment/retention contracts; ссылки временные либо authenticated owner download, только если уже поддержаны. Не обходить scanning, ACL или приватное хранилище. PDF/XLSX исключают неразрешённые PII, формулы из недоверенного текста, внешние ссылки и активное содержимое. Аудит: immutable payload/file digest, policy/source revisions, timestamps, actor/service identity, status. Logical key связывает tenant + scenario + scope + period/event + revision + policy + format; повтор не создаёт дубликат. Сначала подтверждённый доступный артефакт, потом ссылка Radar; failure/retry/recovery/reclaim по существующим механизмам, не обещать атомарность object storage/DB. При stale report явно помечать или отзывать публикацию по owner policy. Не выполнять TASK/коммерческие команды из аналитики напрямую.

## 6. Acceptance proposals (не новые обязательные family counts)
| ID | Проверка |
|---|---|
| NIGHT-01 | Агрегаты не готовы/late event: нет ложного READY, новая immutable ревизия после пересчёта |
| NIGHT-02 | PDF/XLSX/Radar числа, единицы, scope, графики и evidence совпадают с независимым oracle |
| NIGHT-03 | Два worker, retry, crash before/after upload/attach/publish: один logical effect, нет битой ссылки |
| NIGHT-04 | Незначимые/повторные события подавлены; bounded candidate workload; tenant timezone |
| SEC-01 | Чужой tenant, warehouse revoke, user suspend, stale link, report attachment owner mismatch: deny без утечки |
| FX-01 | Fresh accepted revision → допустимый candidate/approval; duplicate, stale/synthetic, policy change → blocked/no duplicate |
| FX-02 | Auto execute только после отдельной квалификации CUR-R2C owner gate; consent revoke, concurrent price revision, limit/budget, agreed order preserved; independent owner ledger oracle |
| FX-03 | Radar/файл различают proposed, approved, executed, blocked; actual applied amounts только из owner ledger |
| PERF-01 | Night × interactive queries × FX event overlap, bounded queues, p95/p99, backlog, resource budgets после baseline |
Связать с действующими D07/traceability и 112-bank после method inventory; не прибавлять к 88/112 как новые семейства и не считать тесты исполненными. Existing exact-SHA gates и Radar resumption остаются. Не менять DAG или CI в design branch.

## 7. Handoff
После B5 и соответствующих owner gates: inventory actual service/worker/file/radar contracts → минимальная gap matrix → интеграция в имеющиеся owners → independent service/PG/security/replay/performance evidence → отдельный UAT/admission. Никаких новых архитектурных контуров. Не прерывать текущий B5. Открытые решения: версии и совместимость выбранных библиотек (§8), конкретные шаблоны и форматные профили, schedule и limits, report audience/retention, supported service-price scope, existing publication/worker identity; не утверждать их автоматически.


## 8. Технологический стек — решение владельца, 2026-11-26
Статус: TECHNOLOGY SELECTION CONFIRMED / DESIGN ONLY. Выбор библиотек согласован владельцем; версии, dependency admission, реализация и production qualification остаются отдельными. P0/P1 относятся к приоритету интеграции микроотчётов после необходимых gates, а не к изменению текущего roadmap B5.

| Технология | Назначение | Статус |
|---|---|---|
| Spring Boot / Java | Существующий backend | Существующая |
| PostgreSQL | Существующие агрегаты, данные и механизмы обработки | Существующая |
| RM Intelligence B4/B5 | Расчёты, интерпретации, выводы и рекомендации | Существующая |
| RustFS / File Service | Хранение и авторизованная выдача артефактов | Переиспользовать |
| RM Radar | Краткий вывод, приоритет, ссылка на отчёт | Переиспользовать |
| React | Визуальное представление единого payload | Визуальное представление |
| Apache ECharts | Интерактивные диаграммы React и печатное представление | P0 |
| Playwright Java | HTML → PDF, специализированный/fallback renderer | P1 — уточнено D06C |
| Apache POI | XLSX: таблицы, формулы, нативные диаграммы | P0 |
| Spring Batch | Предпочтительный кандидат ночной оркестрации, до 10 jobs на кластер | P0 target — уточнено D06C |
| LibreOffice Headless | XLSX → PDF | P0 qualification candidate — D06C |

### 8.1. Единый контракт представления
Один неизменяемый MicroReportPayload (рабочее имя, не утверждённое имя Java-типа) из квалифицированных результатов B5 и owner evidence: показатели, единицы, периоды, качество, scope, графические ряды, выводы, допустимые рекомендации, provenance и версии. React/ECharts, PDF и XLSX используют одинаковый payload, не пересчитывают бизнес-метрики независимо. Формулы в Excel допустимы как проверяемое представление, а не новый источник истины. Оперативный триггер использует тот же контракт после соответствующего owner gate; FX proposed/approved/executed не смешиваются.

### 8.2. Требования к реализациям P0
- Apache ECharts: единые chart specifications для React и печатного режима; line/bar/heatmap и другие виды только по допущенной методике и grain. Для PDF предпочтителен SVG, фиксированный layout, отключённая анимация. MISSING не изображается как zero; легенды, оси, единицы и периоды должны совпадать с payload.
- Playwright Java: локально подготовленный доверенный HTML/React, Chromium PDF, A4/print CSS, готовность диаграмм и шрифтов, русский/казахский текст, bounded workers и timeout. Изолированный BrowserContext на задание; не делить Playwright-объекты между потоками без соблюдения его модели. Запрет произвольных внешних URL, внешних скриптов и передачи tenant payload в чужой context. Контролируемый lifecycle браузера, очистка при ошибках. Не запускать renderer внутри долгой DB-транзакции. Browser binaries/fonts/container resources подлежат отдельной проверке.
- Apache POI: XSSFWorkbook и XDDF для компактных XLSX; SXSSFWorkbook для больших выгрузок только после проверки диаграмм и memory/disk budget. Сложные диаграммы могут использовать изображения с сохранением исходных рядов. Проверить отображение чисел/точность, формулы, Unicode, форматирование и отсутствие внешних ссылок или недоверенных формул; закрытие workbook и очистка временных файлов.

### 8.3. Spring Batch — уточнение владельца
Spring Batch предпочтителен для исполнения ночных отчётных jobs с начальным кластерным лимитом 10, не chunk(10). Запуск через существующий scheduler/worker и completion contract; не вводить второй scheduler, очередь, event bus или дублирующий orchestration lifecycle. Batch metadata/schema и dependencies требуют отдельного admission. Оперативный FX-триггер не должен ждать ночного Batch. Детали в D06C.

### 8.4. Квалификация перед подключением
Сверить actual pom.xml/frontend package manifest, Java/Spring compatibility, dependency/security/license checks, версии Chromium и runtime resources; не фиксировать непроверенные версии. Для NIGHT-02 дополнительно сравнить React/PDF/XLSX с независимым oracle и единым payload (числа, диаграммы, качество, locale, pagination, читаемость); для NIGHT-03 и SEC-01 проверить concurrent tenant render, retry/crash, ACL и публикацию только доступного файла; для PERF-01 измерить heap/native memory, disk, CPU, throughput, backlog и p95/p99 при совместной ночной/оперативной нагрузке. Не считать эти критерии новыми семействами тестов и не заявлять PASS до runtime evidence. B4 FREEZE, roadmap B5, Radar resumption, CUR-R2C execution gate, production HOLD и запрет новых доменов B6/B7 сохраняются.


## 9. Template First и трёхчасовые срезы — уточнение 2026-11-26
[Детализированное ТЗ D06C](D06C-TEMPLATE-FIRST-REPORTING-INTRADAY-R2.md) определяет библиотеку из 12 шаблонов (3 pilot), единый payload, 24-месячный горизонт только при qualified coverage, Spring Batch до 10 jobs на кластер, Excel → PDF trial, intraday L0 с начала дня каждые 3 часа. D06C уточняет приоритеты §8 и не снимает ни один admission gate. Никаких новых доменов и изменения B4.


## D06D — Unified Analytical Dataset & Pattern Tickets (2026-11-26)
[Сквозной архитектурный контракт](D06D-UNIFIED-ANALYTICAL-PATTERN-TICKETS-R2.md) фиксирует согласованное направление B5.5/D06B/D06C/Radar: 24M history / 12M display, допустимые многомерные scope, относительные паттерны, Pattern Ticket → Information Ticket, единые labels и template bindings, night + 3h intraday. Не меняет текущий roadmap, B4 FREEZE, MIC, existing gates, descriptive-only intraday до квалификации сопоставимого cutoff и отдельный Radar admission. DESIGN ONLY / PRODUCTION HOLD.

## Current metric baseline — D06E.4 reconciliation (2026-11-26)
[Audited inventory](D06E.4-AGGREGATE-CAPABILITY-INVENTORY-R2.md) / [JSON](D06E.4-AGGREGATE-CAPABILITY-INVENTORY-R2.json) is the current aggregate capability catalog: 12 B4.8 pilot codes, 8 owner registry codes (7 L1 materialized, 1 live), 11 numerical operator codes, 22 unadmitted candidates; 53 catalog entries, not 53 unique business metrics. Pilot is not a ceiling. Additional registered capabilities are admitted individually after semantic, source, applicability, authorization and independent oracle qualification. No new architecture or automatic runtime admission. Reuse existing L3DerivedSeriesService.readPeriods set-based extraction within a qualified authorized bulk contract; existing 12-anchor/3-metric factor path remains unchanged. Product concept and B4 FROZEN; D08 global admission OPEN; B5.5 NEXT; Radar and production HOLD.

## Final technical reconciliation — 2026-11-26
Durable reporting lifecycle, initial cluster-wide ceiling 10 jobs, fairness, bounded queue, retry/recovery and verified completion mandatory. Spring Batch preferred candidate conditional on existing job inventory and dependency/schema admission; if existing admitted mechanism meets same contract reuse it. One job authority, no second scheduler/queue. Supersedes unconditional library wording. 3h cumulative analytical refresh does not require XLSX/PDF per tenant/scope/slot; files scheduled, on demand or admitted event only.
