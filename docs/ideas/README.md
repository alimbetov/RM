# Банк идей RM / RM Idea Bank

**Дата открытия:** 2026-11-24 · **Git-ветка:** `base_dev` · **Каталог:** `docs/ideas/`  
**Статус:** PRODUCT DISCOVERY / POST-MVP PARKED. Документы этого раздела НЕ разрешают новую разработку или выпуск в production.

## Центральный реестр

**[RM-IDEA-BANK-R1 — Единый банк продуктовых идей](RM-IDEA-BANK-R1.md)**

В реестре собрано **16 направлений** из обсуждений пользователя и существующей стратегии RM:

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
