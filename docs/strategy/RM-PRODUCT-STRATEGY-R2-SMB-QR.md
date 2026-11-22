# RM-PRODUCT-STRATEGY-R2 — RM Business + RM QR Commerce

**Дата:** 2026-11-23. **Ветка:** `base_dev`. **Статус:** ACTIVE PRODUCT SCOPE DECISION / DOCUMENTATION ONLY. **Production:** HOLD.

## 1. Продуктовое решение

Активных рыночных направлений **два**, использующих один Java/Spring Boot modular monolith, общую базу бизнес-объектов и согласованные права:

| Целевой продукт | Для кого | Пользовательская ценность | Базовая вертикаль |
|---|---|---|---|
| **RM Business** | Малый и средний бизнес: розница, оптовая торговля, небольшие дистрибьюторы | Клиенты, товары, остатки, заказы и сотрудники в едином проверяемом процессе | IAM / ORGANIZATION / CRM / CATALOG / STOREFRONT / SALES / INVENTORY / FILES / события аудита |
| **RM QR Commerce** | Те же продавцы, добавляющие внешний канал продаж | QR-карточка → покупка/торг → заказ → резерв → выдача → возврат | Existing STOREFRONT / SALES / INVENTORY / IAM / QR-протоколы |

**RM Network** удалён из активного целевого рынка, MVP и отдельной продуктовой вертикали. Холдинги, intercompany ownership/finance, сложная иерархия и корпоративная консолидация не развиваются сейчас. **Сохраняем** существующие `tenant`, membership, multi-location/warehouse, inventory transfer, role/revocation и audit-механизмы: они нужны и SMB. Не делать Breaking Schema Rewrite ради изменения рыночного сегмента.

Эта стратегия **заменяет целевой сегментный выбор** документа `RM-PRODUCT-TARGETS-AND-KILLER-FEATURES-R1.md`, но не меняет норматив `RM-PLATFORM-STANDARD-R2`, исторические отчёты или предметные технические контракты.

## 2. Текущая последовательность

1. **Service functional closure:** исправить общую регрессию; закрыть IAM/ORG, CRM, CATALOG/STOREFRONT, SALES/INVENTORY, QR и cross-domain business gates по `docs/spec/RM-CORE-FUNCTIONAL-CLOSURE-R1.md`.
2. **TASKS — после текущего P0/P1:** отдельное одобрение; никаких временных задач или искусственной зависимости CRM от отсутствующего TaskService.
3. **Product/pilot readiness — отдельная стадия:** только после service qualification — реальный OIDC, email/provider, транспорт/UX, рабочий опыт покупателя и продавца, безопасность, восстановление, экономика пилотов и соответствующий release admission.
4. **Killer Features KF-01..05:** сохраняются как `PROPOSED/PARKED` и нуждаются в отдельном `IMPLEMENTATION APPROVED`; KF-05 Business Network отложен без срока.

## 3. MVP-границы

**RM Business service acceptance:** доверенный actor/tenant; владелец и сотрудники с явными grants; customer/contact/contract и безопасная история; PRODUCT SKU + цена; seller storefront/offer; заказ; подтверждение/резерв на разрешённом складе; подготовка/выдача; версионированные снимки и корректная складская история. Простые read queries необходимы; full TASKS, финансирование, invoice/payment ledger, автоматическая рассылка, ML и подписки не входят в текущий gate.

**RM QR Commerce service acceptance:** стабильный `PRODUCT_OFFER` QR, безопасный публичный read, buy-at-list или согласованная цена/checkout, один SALES заказ, резерв после подтверждения, одноразовый `PICKUP_CREDENTIAL`, exactly-once handover, RMA/inspection/approved inventory posting. QR-08 Packaging R0 — **отдельная дополнительная sub-qualification**, а не prerequisite для базового QR-товара. Упаковочные материалы R1 и услуги без завершённого fulfillment не включать в core admission.

**Отдельно** остаются настоящая оплата, финансовые проводки, полноценная доставка, возврат денежных средств, реальное сканирующее оборудование, браузер/HTTP и внешняя IAM/provider приёмка. `SALES.COMPLETED` не означает `PAID`, `DELIVERED` или `REFUNDED`.

## 4. Product gates

- **BUSINESS SERVICE FUNCTIONAL PASS:** только реально выполненные Java service journey tests + PostgreSQL, включая tenant, permissions, price snapshot, stock correctness, denied-command zero effects, recovery.
- **QR SERVICE FUNCTIONAL PASS:** основная QR-вертикаль прошла list-price/negotiation, pickup/return и cross-domain race tests; QR08 packaging оценивается отдельно.
- **INTEGRATED CORE PASS:** оба набора подтверждены на одном точном release SHA с H2 compatibility, full PostgreSQL regression, миграцией заполненной БД и обязательным manifest/Surefire XML.
- **PILOT/PRODUCTION:** отдельное решение только после real provider, UX, operations, security и коммерческих доказательств. Нельзя приравнивать service PASS к продаже готового продукта.

## 5. Источники

1. `docs/architecture/RM-PLATFORM-STANDARD-R2.md` — единственный норматив архитектуры.
2. `docs/spec/RM-CORE-FUNCTIONAL-CLOSURE-R1.md` — active service-only execution specification.
3. `docs/spec/RM-SVC-MATURITY-COMPLETION-R2.md`, `docs/spec/RM-TENANT-SERVICES-COMPLETION-R1.md` — подробные ранее принятые направления, переупорядочиваемые по актуальному решению.
4. `docs/spec/RM-QR-R1.4-CANONICAL-FUNCTIONAL-TECHNICAL-CONTRACT.md` — QR state machines, business invariants и DATA/ACL.
5. `docs/spec/RM-COMPETE-R1-POST-STABILIZATION-KILLER-FEATURES.md` — исключительно parked future proposals.
