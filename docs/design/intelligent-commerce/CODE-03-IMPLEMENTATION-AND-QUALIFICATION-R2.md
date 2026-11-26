# CODE-03 · Изменения по этапам и проверка кода
R2.1 · 2026-11-25 · проект для согласования. [Проверенный снимок base_dev](https://github.com/alimbetov/RM/tree/f7910269df73564a6b1bf7d8f45bcc2766cc1b32).
Этот документ уточняет D07/D08. [Execution DAG](../../spec/RM-INTELLIGENT-COMMERCE-EXECUTION-DAG-R1.json) задаёт зависимости. [Реестр](CODE-TRACE-R2.json) связывает 40 записей D07 с этапами и предлагаемыми методами проверки. Параметризованные варианты раскрываются отдельными cases. Названия будущих методов описывают назначение и подтверждаются при реализации.

## 1. Каждый этап как ограниченный diff
| Этап | Изменения кода и схемы | Условие сдачи |
|---|---|---|
| A1/IE-00B | PostgreSQL schema/source/producer manifest и fixtures | Оба PLACED producer paths, variants sale status, movement/return lineage и current CRM/IAM scope сверены с исходниками и мигрированной БД |
| A2/A3 | ADR ownership/prefix, grants/read fence, calendar, eligibility, feed discovery и workload | Одобрены контракты допускаемого среза. Oracle читает owner tables независимо от проекций |
| B1 | PeriodResolver, MetricRegistry, MetricQueryService, SALES и INVENTORY direct-read SQL | 8 паспортов связаны с SQL и авторизацией. Ручной пример D02 выдаёт 2/5000/2/1/1/13/3/10 |
| B2 | CanonicalFact, owner appender/adapter и committed feed | Owner ledger/receipt и feed сериализованы одной transaction. Stable key сохраняется при повторе |
| E1 | Typed predicates/template, EventRuleService, preview, versioned config при допуске схемы | Список разрешённых полей и expected revision, preview даёт ноль delivery effects |
| B3 | Generation, Receipt, DailyBucket, apply + discovery worker | Parity с direct SQL, один эффект на key, конфликт digest и late commit явно обработаны |
| B4 | RollupBucket, dirty revision, shadow rebuild и switch | Смена generation атомарна. Late changes попадают в пересчёт, coverage сравнивается по независимому oracle |
| C1/C2 | CRM read composition, SALES attribution and TASK bounded read, затем подключение L1/L2 | Текущая связь CRM проверяется в summary и drilldown. Проверены permissions, scope и keyset |
| W1/W2 | Current balance/history scope, затем historical rollups и reconciliation | Live parity, both assignment/revoke paths. Opening/closing, transfer, returns и legacy coverage |
| W3 | ABC/XYZ pure classifiers и квалификация SQL входа | Approved thresholds/inputs, hand CV, currency, UNKNOWN/zero cases |
| E2 | RuleOccurrence, bounded admission, Lease/DeliveryWorker и in-app/mock receipt | Version binding, duplicate/retry/crash/revoke, один visible effect на intent |
| E3 | Допущенные order triggers и deadline schedule worker | Current owner revision/time, invalidation, expiry and missed window test |
| X1/G1 | MC-01…05, actual overlap mixed load, candidate manifests | Owner parity/receipt proof, все применимые suites на финальном SHA |
| G2/G3/G4 | Real OIDC и UI journeys, UAT/restore, независимый release verdict | Критерии действующего master/DAG |
| R0–R7 | Квалификация существующего TASK Radar и будущие owner providers | Каждый новый Radar slice проходит отдельный допуск |

## 2. Повторное использование реального кода
SignedJwtTestAuthority и IAM resolver дают достоверные test principals. CrmBuyerLinkQualificationTest даёт lifecycle link. SellerWarehouseScopeR0QualificationTest даёт assignment/revoke fixtures. QrNegotiatedCheckoutQualificationTest и QrWarehouseHandoverQualificationTest дают оба пути PLACED, receipts и физический ledger. SalesInventoryRbacQualificationTest и InventoryTransferStocktakeQualificationTest покрывают confirm/fulfill/cancel и transfer. TenantRbacRevocationQualificationTest даёт паттерн обоих порядков commit. IamOidcRevocationOutboxQualificationTest даёт паттерн fencing/restart. TaskTimingCompletionQualificationTest даёт управляемый Clock. Точные пути в реестре. Новые downstream проверки наблюдают собственные rows и result sets.

## 3. Оракулы по уровню реализации
- **Producer.** Варианты checkout и state transition выполняются owner entrypoints. После commit независимый SQL сверяет order/status/receipt/reservation и feed по (tenant, source_id). При rollback сверить весь изменённый ledger. Точный повтор checkout сохраняет idempotency identity и source event cardinality. Existing feed должен дать эквивалентное доказательство перечисления committed facts.
- **Projection.** Измерить receipt/delta cardinality для двух workers, replay, rollback, late commit с меньшим UUID и record time. Сверить суммы из owner rows отдельно от калькулятора проекции. Для B4 проверить dirty windows, гонку revision и активный указатель после crash.
- **ACL.** Построить A-only/B-only/A+B tenants с разными grants и отдельно inactive workspace. Для обоих commit orders записывать барьеры и наблюдаемые lock waits. Проверять доступ к секции, суммам, cursor и evidence. Warehouses с истёкшим scope не раскрываются в count. Проверять текущий видимый status/amount отдельно от PII.
- **Delivery.** Два workers с одной occurrence, lease expiry/late ACK, owner change и pause/revoke. Проверить ровно одну видимую запись и один receipt. Crash отдельного процесса до/после commit проверяется с SQL snapshot. Для exhausted intent подтвердить terminal disposition и сохранение logical identity при разрешённом retry.
- **ABC/XYZ.** Pure methods проверяют hand examples и точные границы. Отдельный PG сценарий проверяет входные факты: snapshot SKU/price, currency, eligible fulfillment subset и complete versus censored buckets. Успех pure методов сам по себе не закрывает W3.
- **Миграции и нагрузка.** A1 снимает текущую schema. Каждый будущий changeset проверяется на clean/populated миграции, составных FK и applied checksums. До mixed run согласовать SQL budgets и traffic mix. Сохранять baseline/mixed p95/p99, plans, waits, throughput, backlog и фактические интервалы overlap.

G1 manifest связывает requirement ID, suite/test case, engine/version, exact SHA, artifact hash и observed result. Уже принятые allowances и semantic obligations действующих проверок берутся из оперативных нормативов RM. Проверка этого документа подтверждает структуру проекта и наличие исходных файлов. Runtime evidence появится в соответствующих этапах.

## 4. Первый diff после A3
B1 можно опубликовать как pure PeriodResolver + MetricRegistry, authorized direct reads в SALES/INVENTORY и независимые проверки покрытий. Это минимальный срез аналитики, который даёт проверяемый результат по восьми метрикам. После B1 идут C1/W1, а B3 требует ещё B2. B2 и E1 имеют свои самостоятельные diffs после A3.

При сверке base_dev на `f7910269df73564a6b1bf7d8f45bcc2766cc1b32` изменения относительно последнего снимка D00 затронули отчёт W6 в CI verifier и его тесты. Сверка diff подтвердила прежние Java producer paths и схему. PR #9 оставался открыт. Проект продолжает действующие Q0/Q1 → IE-00B/A3. PostgreSQL evidence и фактические номера миграций относятся к этапам реализации.

## Обязательное дополнение MIC-R1 (2026-11-26)
[RM Mathematical Integrity Contract R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md). MIC-Q01–Q10 обязательны по применимости; при mismatch исправлять production Java/DDL, а не expected values. Exact-SHA admission сохраняется.

## RM-IC-R2 source alignment and roadmap (2026-11-26)
[Project alignment and roadmap](RM-IC-R2-PROJECT-ALIGNMENT-ROADMAP.md) is the current design handoff against base_dev a6bf99107aa64f4eb255b4997ef9417748b05607. Existing S0–S6 and operative DAG R1.2 (33 nodes/54 edges) preserved; older descriptive counts are historical, not current topology. MPC-1 in D06E.1 and D06E.4 JSON covers 53 registered entries without runtime admission. B5.5 NEXT; coverage before bulk/pattern/report publication; C/W/E lanes retain own gates, D08 OPEN, Radar/production HOLD. Only design branch updated.
