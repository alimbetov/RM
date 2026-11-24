# RM — Банк продуктовых идей / RM Idea Bank R1

**Дата:** 2026-11-24  
**Репозиторий:** `alimbetov/RM` · **рабочая Git-ветка:** `base_dev` · **логический раздел:** `docs/ideas/`  
**Source-review baseline:** `5549c80abe09be19efd9f9b1bfbd81e5a4fa52a4` (не release verdict).  
**Назначение:** единый каталог продуктовых идей и решений пользователя, не backlog немедленной разработки.  
**Статус:** `IDEA BANK / POST-MVP PARKED / NO IMPLEMENTATION AUTHORIZATION / PRODUCTION HOLD`.

> **Норматив:** [RM-MVP-CLOSURE-FREEZE-R1](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) и [RM-PRODUCT-STRATEGY-R2-SMB-QR](../strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md). Действующих продуктовых вертикалей две: **RM Business** и **RM QR Commerce**. RM Network, отдельная универсальная ERP/финансовая система и новые функции, не нужные MVP, сюда не включаются как активный roadmap. Не создавать новые ветки, не менять `main`, не открывать production на основании этого документа.

## 1. Как пользоваться банком

Каждая карточка содержит **ID**, потребность пользователя, ожидаемый результат, существующий доменный источник/ТЗ, зависимости, критерий продуктовой проверки и приоритет. Идея считается зарегистрированной, но не реализованной. Ранее утверждённые спецификации не переопределяются описанием в банке.

Состояния:
- `IDEA` — концепция сохранена; нужно определить аудиторию/метрику пользы.
- `DISCOVERY` — проверяются рынок, данные и ограничения.
- `SPEC_EXISTS` — есть отдельное техническое задание, но наличие ТЗ не доказывает реализацию.
- `FOUNDATION_PARTIAL` — уже есть некоторый код или сервисные предшественники, не полная функция.
- `PARKED` — не реализовать до отдельного post-MVP разрешения.
- `RELEASED` — только с внешним UAT, exact-SHA qualification и допуском; **ни одной идее в этом банке этот статус не присвоен**.

Каждое будущее изменение требует ссылки на Issue/ТЗ, владельца домена, scope decision, реальный SQL/Java evidence, стоимость/риск и отдельный approval. Здесь записаны продуктовые направления, не обещания сроков.

## 2. Сводный каталог идей

| ID | Инициатива | Пользовательский результат | Статус / очередь | Источник |
|---|---|---|---|---|
| **IDEA-01** | **RM-INSIGHT-R2 — Customer 360** | Статистика, активность, покупки и договоры по конкретному клиенту | IDEA / Post-MVP, после INSIGHT-R1 | [INSIGHT-R1](../spec/RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md) |
| **IDEA-02** | **RM-INSIGHT-R2 — Two-Layer Aggregates** | Неделя, декада, месяц без долгих запросов к истории | IDEA / после корректных daily facts | [INSIGHT-R1](../spec/RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md) |
| **IDEA-03** | **RM-INSIGHT-R2 — Warehouse 360** | Остатки, резервы, движения и эффективность складов/SKU | IDEA / Post-MVP | [INSIGHT-R1](../spec/RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md) |
| **IDEA-04** | **RM-INSIGHT-R3 — ABC/Pareto + XYZ** | Найти ключевые и нестабильные товары, излишки и риск дефицита | IDEA / после Warehouse 360 | INSIGHT-R2 |
| **IDEA-05** | **RM-INSIGHT-R4 — Tribuo ML** | Прогноз спроса, stockout risk, аномалии и объяснимые рекомендации | IDEA / после качества статистики | INSIGHT-R3 |
| **IDEA-06** | **RM-INSIGHT-R5 — Intelligence → EVT** | Уведомления по проверенным изменениям KPI/прогноза | IDEA / после квалификации обоих контуров | INSIGHT-R4 + EVT |
| **IDEA-07** | **RM-EVT-R1 — Event-Driven Notification & Campaign Builder** | Общий конструктор триггеров, расписаний и шаблонов уведомлений | IDEA / Post-MVP | [AUC Issue #11](https://github.com/alimbetov/RM/issues/11), [TASK-R3.2B](../spec/RM-TASK-01B-R3.2B-SLA-POLICY-DURABLE-OUTBOX-FOUNDATION.md) |
| **IDEA-08** | **RM-AUC-R1 — Multi-Mode Auction** | Закрытые, таймированные и английские торги на лот | IDEA / Post-MVP | [AUC Issue #11](https://github.com/alimbetov/RM/issues/11) |
| **IDEA-09** | **RM-AUC-R1 — Deal Chat** | Общение покупателя и продавца, связанное с лотом, торгом, заказом | IDEA / Post-MVP | [AUC Issue #11](https://github.com/alimbetov/RM/issues/11) |
| **IDEA-10** | **CUR-R2C — Smart FX Shield FX_AUTO** | Массовое ценовое обновление в KZT в утверждённых пределах | SPEC_EXISTS + FOUNDATION_PARTIAL / Post-MVP | [CUR-R2C-R1.1](../spec/RM-CUR-R2C-SMART-FX-SHIELD-CONTROLLED-AUTO-PRICING-R1.md) |
| **IDEA-11** | **CRM Leads / Pipeline / Kanban** | Управлять воронкой, лидами и этапами продаж в интерфейсе | IDEA / CRM competitiveness | Product discovery |
| **IDEA-12** | **CRM Communication Timeline** | Единая история взаимодействий, задач, заказов, писем и Deal Chat | IDEA / после User Journey | CRM/TASK services + EVT |
| **IDEA-13** | **CRM Integration / Import Hub** | Импорт клиентов/каталога и проверяемые интеграции | IDEA / конкурентное развитие | [KF Strategy (historical)](../strategy/RM-PRODUCT-TARGETS-AND-KILLER-FEATURES-R1.md) |
| **IDEA-14** | **Sales KPI / Role Dashboards** | Обзоры продаж, продавцов, склада и customer segments | IDEA / INSIGHT + UI | INSIGHT-R1 / P1 CRM |
| **IDEA-15** | **KF-01 InstantDeal & KF-02 Promise Engine** | Быстрый B2B offer→order и достоверное обещание доступности | PARKED / отдельный review | [Post-stabilization KF](../spec/RM-COMPETE-R1-POST-STABILIZATION-KILLER-FEATURES.md) |
| **IDEA-16** | **KF-03 Relationship Radar & KF-04 Zero-Entry Order** | Следующее действие по клиенту, ввод заказа из CSV/XLSX | PARKED / отдельный review | [Post-stabilization KF](../spec/RM-COMPETE-R1-POST-STABILIZATION-KILLER-FEATURES.md) |

**Неактивные направления:** Business Network / межорганизационная торговая сеть (старый KF-05), полный бухгалтерский/налоговый ledger, масштабная маркетинговая платформа без канонических данных, автономное изменение остатков ML — **не включать в текущий план**.

## 3. IDEA-01 — Customer 360: клиентская аналитика

**Потребность:** руководитель или продавец открывает карточку клиента и видит динамику по неделям/декадам/месяцам и отчётным периодам без ручного экспорта.

**Варианты показателей:**
- количество новых, подтверждённых, отменённых и завершённых заказов;
- `booked_order_amount` по immutable accepted KZT price snapshots, частота, средняя стоимость заказа;
- клиенты по повторным покупкам, периодам без заказов, динамика спроса;
- история CRM interaction/task events и статус follow-up;
- срок действия существующих CRM contracts и даты обновления;
- QR-конверсия/доля успешных согласованных предложений только если доказуемы отдельные source facts.

**Важный IAM-барьер:** CRM customer UUID **не равен** IAM buyer UUID. Приписывать SALES покупку CRM-клиенту можно только по канонической подтверждённой `CrmBuyerLinkService` связи. Неподтверждённое — `UNATTRIBUTED`; отзыв/спор связи должен приводить к безопасной переатрибуции без пересчёта чужих клиентов. Не сопоставлять по email/телефону. Не выдавать суммы заказов за paid revenue.

**Критерий ценности:** продавец на одной карточке клиента объясняет, когда и какие заказы оформлялись, какие задачи просрочены, как изменилась активность; каждая цифра сходится с авторитетными SQL-фактами.

## 4. IDEA-02 — точная двухслойная агрегация L1 → L2

**Предложение пользователя:** агрегирование недельных, декадных и месячных клиентских/складских/продажных показателей с PostgreSQL storage optimization и партиционированием там, где это оправдано.

- **L1 = дневные непротиворечивые показатели**: `tenant + business_date + domain + customer/sku/location + metric + currency + methodologyVersion` — только допустимые для данной метрики измерения; не создавать единственную широкую таблицу всех ненужных комбинаций.
- **L2 = периодные материализованные результаты**: `period_type = ISO_WEEK | DECADE | MONTH`, `period_start`, `period_end`, `is_complete`, `as_of`, `projection_version`, `data_freshness`; неделю определять явно (например, ISO Mon–Sun), декады 1–10/11–20/21–конец календарного месяца.
- Неделя **не вычисляется корректно только из декад**; агрегировать неделю/декаду/месяц из одного дневного L1. Временная зона — versioned tenant policy, R1 source design использует day_utc.
- `SUM` и `COUNT` можно сворачивать с учётом недопущения дублей; `AVG` хранить через сумму+число; `RATIO` через числитель/знаменатель; `DISTINCT` — через ID-aware snapshot или точный пересчёт; `AS_OF` gauges не суммировать по дням.
- Исправление источника, поздняя проводка, возврат, восстановление из outbox или смена customer link пересчитывают **только затронутые дни и зависящие периоды**. Shadow rebuild новой методики + сравнение с SQL + atomic activation.
- Партиционирование PostgreSQL по времени только после измерений (EXPLAIN ANALYZE, BUFFERS, WAL, populated benchmark), без «партиции на клиента». Tenant-first индексы, разумная кардинальность измерений, retention, backfill и applied-event dedup.

**Критерий:** сумма за неделю/декаду/месяц совпадает с независимым SQL по immutable facts, включая события на границах месяцев, недель, декад и поздние коррекции; нет двойного начисления.

## 5. IDEA-03 — Warehouse 360: вся складская аналитика

**Потребность:** единая панель по каждому складу и каждому SKU с истинным запасом и историями.

Обязательные разрезы: `tenant × warehouse/location × SKU × period × movement_kind`; далее категория, подтверждённый клиент, регион **только при доказуемом source snapshot**. Целевые показатели:
- `on_hand`, `reserved`, `available = on_hand - reserved` — **текущие gauges** читаются из авторитетного `inv_stock_balance`, а не из вчерашнего L2;
- receipt, write-off, adjustment, transfer IN/OUT, physical fulfillment, reservations/releases, quarantine/return posting — **раздельные типизированные движения**, без смешивания логического резерва с физическим расходом;
- оборачиваемость, days-of-cover, dead stock и застарелые остатки **только после утверждения точных правил**: исторический on-hand snapshot/period baseline, источник расхода, unit-of-measure, валютная стоимость и отсутствие невалидных сокращений;
- контроль неснижаемого остатка, stockout windows, сравнение складов, возможные перемещения в режиме рекомендаций;
- движения между двумя складами не становятся фиктивным общим приходом/расходом компании; при консолидации отражается 0 net company stock delta.

**Инвариант:** аналитика не пишет `inv_stock_balance`, не резервирует товар и не обещает ATP вместо реального INVENTORY admission. Одна единица SKU на двух разных складах — два склада, а не две продажи.

## 6. IDEA-04 — ABC / Pareto и XYZ

**ABC/Pareto:** сортировать SKU или клиентов по выбранному **подтверждённому бизнес-показателю** (например, стоимость оформленных/завершённых заказов), считать долю и накопленную долю в указанной валюте. Пороги A/B/C, например 80/15/5, — настраиваемая методика, не универсальное правило. Не именовать booked amount прибылью, маржой или оплаченной выручкой.

**XYZ:** изменчивость спроса в равных временных интервалах, методика coefficient of variation и пороги X/Y/Z должны быть явно утверждены; период без доступного товара требует отдельной метки, интермиттирующий спрос — специализированной обработки. Нельзя строить XYZ на несопоставимых `неделя vs декада` периодах.

**Пересечение:** 9 сегментов AX, AY, AZ, BX, BY, BZ, CX, CY, CZ. Продуктовые предложения: `AX: высокий приоритет доступности`, `AZ: консервативное пополнение и анализ отклонений`, `CZ: проверить медленно оборачиваемые запасы`. Это рекомендации к анализу, не автономные операции склада.

**Acceptance:** стабильная детерминированная сортировка ties, пустые периоды и zero denominators, единый data cut-off, одинаковые SKU/UOM/currency, SQL golden oracle и rollback новой версии methodology.

## 7. IDEA-05 — Oracle Tribuo: ML поверх достоверной аналитики

**Технологический выбор пользователя:** **Oracle Tribuo / Java ML** после точной SQL-аналитики, не замена статистическим агрегациям.

1. **Demand Forecast:** прогноз спроса SKU × склад на 7/14/30 дней: лаги, rolling-window metrics, weekday/month effects, сезонность, история заказов и фактического исполнения.
2. **Stockout Risk:** риск дефицита с учётом текущего доступного запаса и подтверждённого lead time пополнения; при отсутствии lead time не заявлять достоверную вероятность.
3. **Inventory Anomaly:** необычные списания, корректировки, возвраты, колебания складской активности.
4. **Later:** CRM/customer segmentation, недавние изменения спроса и рекомендованный контакт с клиентом — только после согласования данных и privacy.

**Проверки:** offline training, reproducible feature snapshot, train/test split по времени с предотвращением data leakage, baseline naive/seasonal, WAPE/MAE/MASE (метрики согласовать), ошибки по группам ABC/XYZ и stockout periods, drift, версионирование модели/features, объяснение результата, возможность выключить ML.

**Базовая политика:** `SQL = факты`; `Tribuo = прогнозы`; `сотрудник = решение`. ML не создаёт закупки, не меняет цену и не производит inventory movement без отдельной бизнес-политики и приёмки.

## 8. IDEA-06 / IDEA-07 — аналитические уведомления и Event Builder

**RM-EVT-R1** — один общий конструктор `Business Event / Deadline → Audience → Filter/Rule → Versioned Template → Channel → Durable Outbox → Delivery Evidence`.

Сценарии из обсуждений:
- новые аукционы, персональные приглашения и QR публичной карточки товара/лота;
- приближение конца торгов, изменение дедлайна, проигранная ставка, приглашение победителя;
- индивидуальное коммерческое предложение через placeholders (`{{customer.displayName}}`, `{{product.publicQrUrl}}`, `{{auction.endsAt}}`, `{{offer.priceKzt}}`);
- напоминание о скором окончании договора — CRM контракт уже имеет сроки, но delivery contract отдельно не реализован;
- напоминание о счёте, оплате и просрочке **только после появления авторитетного BILLING/PAYMENT обязательства**, а не по общей сумме заказа;
- напоминание CRM/TASK о сроке и следующем действии;
- будущие аналитические сигналы (снижение спроса, stockout risk, необычные движения, клиент без заказов N дней) — только при известных владельце события, методике и пороге.

Свойство отправки: маркетинговое согласие отдельно от сервисных уведомлений, channel preferences/отписка, typed placeholder allowlist, tenant/PII protection, QR без секретов, valid recipient, schedule timezone, anti-spam, bounded retry, provider ACK + immutable delivery receipt. Участие в аукционе не даёт разрешения на массовый маркетинг.

**Архитектурно** EVT и INSIGHT — два **потребителя канонических событий**, не следует строить аналитику из журналов отправленных уведомлений или запускать два независимых общих event bus. Уведомления могут зависеть от результатов INSIGHT, но INSIGHT не должен зависеть от доступности email/push.

**Связанные объекты:** [AUC Issue #11 с AUC-NOTIFY-01](https://github.com/alimbetov/RM/issues/11), [TASK-R3.2B](../spec/RM-TASK-01B-R3.2B-SLA-POLICY-DURABLE-OUTBOX-FOUNDATION.md). Они остаются post-MVP PAUSE, это не согласование запуска SMTP/SMS/PUSH.

## 9. IDEA-08 / IDEA-09 — аукционы и Deal Chat

**Аукционы:**
- `SEALED_BID`: закрытая ставка и раскрытие после дедлайна;
- `SCOTTISH_TIMED`: рабочее название; пользовательский смысл должен быть уточнён (термин встречается и в значении закрытой подачи к дедлайну);
- `ENGLISH_ASCENDING`: открытые повышающие ставки, шаг и опциональный anti-sniping.

**Deal Chat:** сообщения привязываются к `offer/auction/order`, но **текст в переписке не становится ценовым предложением, аукционной ставкой или акцептом**. Для сделки использовать существующий структурированный `SalesPriceProposalService`; для многопользовательского аукциона нужен отдельный SALES-owned aggregate. Победа → отдельный trusted SALES checkout, не автоматическая отметка PAID/COMPLETED.

**Точки риска:** выставленный складской лот защищён от двойной продажи, KZT price/quantity snapshot фиксирован на старте, bidder fairness, tenant isolation, confidential bids, dueAt server time, idempotency/replay, consumer rules and legal admission. FX_AUTO не меняет начатые торги.

**Канонический GitHub объект:** [Issue #11 — RM-AUC-R1](https://github.com/alimbetov/RM/issues/11). Этот банк — каталог, а не альтернативное ТЗ аукционов.

## 10. IDEA-10 — Smart FX Shield Controlled Auto Pricing

**Бизнес-идея:** продавец единожды утверждает FX-политику USD/EUR/CNY и защитные пороги; RM может автоматически предлагать/публиковать новые KZT-цены для разрешённых offers. Массовое применение, группы, исключения, dry-run, cumulative cap, cooldown, rollback/replay, источник НБРК и trusted calendar gate.

**Текущий контур:** CUR-R2A/R2B содержит seller approval для PRODUCT offer и калькулятор; CUR-R2C имеет R1.1 ТЗ, часть offline/consent foundation, но **нет допущенного production AUTO**, услуг через PRODUCT-only publication не публиковать. Публичная цена KZT, старый заказ и согласованная quote не изменяются.

**Главный связанный документ:** [CUR-R2C — Controlled Auto Pricing](../spec/RM-CUR-R2C-SMART-FX-SHIELD-CONTROLLED-AUTO-PRICING-R1.md). Идея в банке не отменяет отдельные G0–G7 admissions.

## 11. IDEA-11…16 — функциональные пробелы CRM и ранее обсуждённые улучшения

- **Leads / Kanban / Sales Pipeline:** этапы лидов и сделок, ответственные, конверсия, понятный UI; проверить, что новый pipeline не дублирует `sales_order` и `SalesPriceProposalService`.
- **Customer Communication Timeline:** CRM interactions, follow-up tasks, заказы, предложения, будущие email, Deal Chat с разрешённым обзором и identity linking.
- **Import / Integration:** качественный CSV/XLSX импорт товаров/клиентов, idempotency, validation, дедупликация и mappings, API партнеров только с scoped IAM.
- **Role-specific Dashboards:** руководитель, менеджер, склад — отдельные метрики с минимально необходимыми полномочиями.
- **InstantDeal/Promise Engine:** персональный коммерческий оффер; реальная доступность на складе без ложного обещания резерва. Исходные KF-01/KF-02 — **PARKED**.
- **Relationship Radar / Zero-Entry Order:** next best action по клиенту и быстрый ввод заказов; исходные KF-03/KF-04 — **PARKED**, не расширяют текущий MVP.

**Не запускать до MVP:** универсальный messenger, social network, marketplace many-to-many checkout, сквозную сложную бухгалтерию/доставку, независимый order engine, финансовый ledger, платёжный шлюз и бесконтрольную кампанийную автоматизацию.

## 12. Точки пересечения и единые правила

| Инвариант | Применение |
|---|---|
| ONE AUTHORITY | CRM владеет customer/contract, SALES — order/accepted quote, STOREFRONT — published price, INVENTORY — stock. Ни BI, ни EVT, ни ML не становятся альтернативным владельцем. |
| Транзакционный источник | Domain event/outbox + идемпотентное применение; `audit_event` не использовать как универсальный аналитический источник. |
| Tenant security | Scope по реальному actor/tenant и объекту; не читать все tenants ради глобального dashboard; worker не подменяет VerifiedActor. |
| Period correctness | Daily L1 first; ISO week / декада / месяц из одного L1; business timezone и methodology version фиксированы. |
| Source vs projection | Истинный stock из `inv_stock_balance`, сумма заказа из immutable SALES; snapshot/ML может запаздывать и обязан показывать freshness. |
| Financial semantics | Booked ≠ paid revenue; physical movements ≠ резерв; warehouse transfer ≠ продажи. |
| No automatic commercial action | ML recommendation, EVT reminder и аукционная ставка не делают незаметных price, inventory или payment writes. |
| Test gates | PostgreSQL real concurrency, 2 tenants, crash/replay, populated Liquibase, IAM/PII scope, exact SHA; затем отдельный user-facing OIDC/UAT. |

## 13. Рекомендуемый порядок, без открытия scope

```text
ТЕКУЩИЙ ПРИОРИТЕТ: MVP G0 → G1 → G2 (OIDC + UI) → G3 (пилот) → G4
       |
       v
POST-MVP DISCOVERY: RM-INSIGHT-R1 source/producer inventory и SQL baseline
       |
       v
RM-INSIGHT-R2: Customer 360 + Warehouse 360 + L1 Daily / L2 Week-Decade-Month
       |
       v
RM-INSIGHT-R3: ABC/Pareto, XYZ, оборот, days-of-cover, data quality
       |
       v
RM-INSIGHT-R4: Tribuo Forecast / Stockout / Anomaly, shadow evaluation
       |
       +--------------------+
       |                    |
       v                    v
RM-EVT-R1: Template + Outbox   RM-AUC-R1: Auction + Deal Chat
       |                    |
       +---------+----------+
                 v
RM-INSIGHT-R5: проверенные аналитические события → разрешённые уведомления
```

**Порядок проектов — продуктовая гипотеза, не строгая runtime-зависимость.** Пользователь рассматривает аналитику после RM-EVT; архитектурно корректнее разрабатывать инсайты и доставку независимо на общих источниках. Итоговый порядок решается после пилота и оценки пользы.

**Первые 4 измеримые исхода будущих пилотов:** (1) Customer 360 отвечает на запрос клиента быстрее ручной выгрузки; (2) Warehouse 360 достоверен по каждому складу; (3) ABC/XYZ и Tribuo действительно улучшают качество решений относительно SQL-only baseline; (4) EVT уведомления не создают спам и не отправляются по устаревшему или чужому источнику.

## 14. Связанные документы и история решений

- [Активная стратегия RM Business + QR Commerce](../strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md)
- [MVP Closure Freeze — обязательные ограничения](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md)
- [RM-INSIGHT-R1 — Operational Analytics & Performance](../spec/RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md)
- [CUR-R2C Smart FX Shield — спецификация R1.1](../spec/RM-CUR-R2C-SMART-FX-SHIELD-CONTROLLED-AUTO-PRICING-R1.md)
- [TASK-R3.2B — SLA & Durable Notification Outbox, parked](../spec/RM-TASK-01B-R3.2B-SLA-POLICY-DURABLE-OUTBOX-FOUNDATION.md)
- [RM-AUC-R1 + AUC-NOTIFY-01 — GitHub Issue #11](https://github.com/alimbetov/RM/issues/11)
- [Прежний список Killer Features — superseded для активной стратегии](../strategy/RM-PRODUCT-TARGETS-AND-KILLER-FEATURES-R1.md)
- [Текущие функциональные пробелы и стратегия](../strategy/RM-STRATEGY-R4-FUNCTIONAL-GAP-PRIORITIZATION.md)

**Источники новых идей:** обсуждения пользователя 2026-11-24 и приложенный текст с требованиями Customer/Sales/Inventory/ABC/XYZ/Tribuo; ссылки выше использованы для проверки пересечений с существующими планами. Приложенный текст — концепция, **не evidence реализации**.

## 15. Изменения банка

| Дата | Ревизия | Изменение |
|---|---|---|
| 2026-11-24 | R1.0 | Создан единый банк 16 направлений: Customer 360, L1/L2, Warehouse 360, Pareto/ABC/XYZ, Tribuo, EVT, Auction, Deal Chat, FX_AUTO и CRM improvements. Объединены ранее обсуждённые идеи без изменения MVP. |

**Новый feature request:** добавить строку с новым `IDEA-XX`, ссылку на issue/spec и owner decision; не присваивать `IMPLEMENTED` на основании текста, тестового плана или наличия исходника.