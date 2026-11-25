# Банк идей RM / RM Idea Bank

**Дата открытия:** 2026-11-24 · **Git-ветка:** `base_dev` · **Каталог:** `docs/ideas/`  
**Статус:** SCOPED MVP SELECTION (CR-MVP-INTELLIGENCE-EVT-01) / OTHER IDEAS PARKED / PRODUCTION HOLD. Только явные scope decisions разрешают staged разработку; документы сами по себе не доказывают release.

## Центральный реестр

**[RM-IDEA-BANK-R1 — Единый банк продуктовых идей](RM-IDEA-BANK-R1.md)**

В реестре собрано **18 направлений** из обсуждений пользователя и существующей стратегии RM:

| Направление | Что внутри |
|---|---|
| **Business Intelligence** | Customer 360, продажи/KPI, история активности клиента |
| **Data Aggregation** | Дневные L1 и недельные/декадные/месячные L2 проекции, PostgreSQL/партиции |
| **Inventory Intelligence** | Warehouse 360, остатки, резервы, движения, оборачиваемость |
| **ABC / Pareto / XYZ** | Вклад в объём заказов, стабильность спроса, рекомендации по ассортименту |
| **Oracle Tribuo ML** | Прогноз спроса, риск дефицита, обнаружение аномалий |
| **RM-EVT-R1** | Конструктор рассылок, события, email placeholders, QR, напоминания по договору/оплате |
| **RM-AUC-R1** | Закрытые, таймированные и английские аукционы, Deal Chat |
| **Smart FX Shield** | Управляемая массовая автоматическая корректировка цен в KZT |
| **CRM improvements** | Kanban/лиды, коммуникации, интеграции и KPI |
| **Earlier Killer Features** | InstantDeal, Promise Engine, Relationship Radar, Zero-Entry Order (parked) |
| **FIN → RM Analytics Adaptation (IDEA-17)** | Period Resolver, Metric Registry, Native SQL, lineage, Methodology & Results, Metric Graph, Reports, Tribuo contract — без зависимости от FIN |
| **Business Radar (IDEA-18)** | TASK, Sales, Customer, Inventory, Financial/FX, Predictive Radar, Action Advisor; [RM-RADAR-VISION-R1](../spec/RM-RADAR-VISION-R1-INTELLIGENT-BUSINESS-CONTROL-CENTER.md); POST-MVP PARKED |

## Отобранная архитектура из FIN Analyzer

**[RM-INSIGHT-FIN-ADAPT-R1 — адаптация расчётных механизмов FIN для RM](../spec/RM-INSIGHT-FIN-ADAPT-R1-CRM-ANALYTICS-COMPUTATION.md)**. Отобраны 9 логических компонентов, расписаны границы доменных владельцев и 32 будущие PostgreSQL/functional acceptance проверки. Статус **SPEC_EXISTS / POST-MVP PARKED**, без кода.

## Общее техническое задание Intelligent Commerce

[RM-INTELLIGENT-COMMERCE-MASTER-R1](../spec/RM-INTELLIGENT-COMMERCE-MASTER-R1.md) — основной объединённый документ: Intelligence, Customer 360, Warehouse 360/ABC/XYZ, EVT и полное расширение Business Radar. 80 исходных planned qualification families + 8 proposed cross-domain integration families, общий roadmap и acceptance gates. Radar остаётся DEFERRED_ANALYTICS_FIRST; no new code authorization beyond CR-MVP-INTELLIGENCE-EVT-01.

## Выбранные направления MVP (2026-11-25)

[RM-MVP-INTELLIGENCE-EVT-SCOPE-R1](../spec/RM-MVP-INTELLIGENCE-EVT-SCOPE-R1.md): RM Intelligence (IDEA-17/02), Customer 360 (01), Warehouse 360 + ABC/XYZ (03/04), Event & Notification Builder (07). IE-00…09, 32 будущие проверки, дополнительный BUS-MVP-I1. Только bounded scope после stage gates, внешние отправки mock, остальное PARKED.

## Стратегическое развитие Radar

[RM-RADAR-VISION-R1](../spec/RM-RADAR-VISION-R1-INTELLIGENT-BUSINESS-CONTROL-CENTER.md) — отдельное ТЗ R1.1, 48 будущих семейств приёмки. **DEFERRED / ANALYTICS FIRST:** плановые доработки отложены до квалификации соответствующих источников INSIGHT/IDEA-17 и отдельного возобновления. R0.1 код и обязательные gates сохранены; R0.1D не считается закрытым. R0.2, новые Radar features, UI, ML, автоматизация и реализация аналитики не разрешены этим документом.

## Канонические документы

- [Активная стратегия RM](../strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md)
- [MVP Scope Freeze](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md)
- [RM-INSIGHT-R1 — аналитическая основа](../spec/RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md)
- [RM-CUR-R2C — Controlled Auto Pricing](../spec/RM-CUR-R2C-SMART-FX-SHIELD-CONTROLLED-AUTO-PRICING-R1.md)
- [TASK-R3.2B — будущая инфраструктура уведомлений](../spec/RM-TASK-01B-R3.2B-SLA-POLICY-DURABLE-OUTBOX-FOUNDATION.md)
- [GitHub Issue #11 — Multi-Mode Auction & Deal Chat + Event Builder](https://github.com/alimbetov/RM/issues/11)

## Правило добавления

Идею записываем с уникальным ID, проверяемым пользовательским сценарием, ссылкой на домен/источник фактов, зависимостями, приоритетом и состоянием `IDEA`/`PARKED`/`SPEC_EXISTS`. Никакой идеи нельзя присваивать статус реализованной только по ТЗ.

**Приоритет проекта неизменен:** завершить MVP Business + QR Commerce, доказать real OIDC / Web UI / UAT и exact-SHA GREEN. Раздел `docs/ideas/` — структурная «ветка идей» внутри **существующей** `base_dev`, не новая Git-ветка и не альтернативный backlog разработки.
