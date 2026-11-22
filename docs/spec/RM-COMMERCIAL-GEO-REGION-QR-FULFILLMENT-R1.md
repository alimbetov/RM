# RM-COMMERCIAL-GEO-REGION-QR-FULFILLMENT-R1

**Статус:** DRAFT / SPEC ONLY, NOT IMPLEMENTED. **Дата:** 2026-11-22. **Branch:** base_dev. **Source baseline:** c8fc514a871f23d29e44ec8be25ad112b928619c. **Production:** HOLD.

**Связанные документы:** RM-PLATFORM-STANDARD-R2, RM-COMMERCE-INVENTORY-COMPLETION-R1, RM-INVENTORY-FUNCTIONAL-COMPLETION-R1, RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE, RM-STRATEGY-R4.

## 1. Цель

Создать комплексный сценарий: поиск опубликованного товара по региону → PLACED с QR поиска заказа → CONFIRMED с резервом на конкретном складе и immutable GPS-origin snapshot → PREPARING/READY_FOR_PICKUP → проверка одноразового QR получения → фактическая выдача с единственным складским списанием → при необходимости отдельный RMA/возврат с осмотром и учётом качества.

Один модульный монолит Java 21 / Spring Boot / PostgreSQL, существующий Feature Freeze: никаких новых HTTP controllers, Redis, Kafka, других микросервисов, без merge в main. Сначала сервисные и PostgreSQL контракты, затем реальный OIDC/browser E2E.

**Подтверждено изучением кода:** public.org_location содержит KATO/address/status, но не GPS. AutoOrderService.checkout создаёт PLACED без выбранного склада. SalesInventoryCommandService.confirm резервирует целые строки на одном locationId. SalesInventoryCommandService.fulfill допускает CONFIRMED/PREPARING и сразу устанавливает COMPLETED; InventoryReservationService дополнительно поддерживает fulfillment из READY_FOR_PICKUP. Статус READY_FOR_PICKUP объявлен в DB CHECK, но полного QR pickup workflow и return workflow не обнаружено. SERVICE checkout/fulfillment пока запрещён; FINANCE payment/refund не реализован. Нельзя трактовать заявленные статусы как завершённые процессы.

## 2. Domain ownership

| Домен | Authority | Граница |
|---|---|---|
| GEO | KATO hierarchy/validation | Не владеет stock или заказом |
| ORGANIZATION | public.org_location: склад, адрес, тип, GPS, lifecycle | Не списывает товар |
| CATALOG | PRODUCT/SERVICE SKU | Не хранит складской остаток |
| STOREFRONT | published seller offer/price, региональный public read | Не подтверждает резерв и не создаёт заказ |
| INVENTORY | on_hand/reserved, immutable movement, reservation, stocktake, transfer | Не владеет GPS/стоимостью/выдачей |
| SALES | order, immutable price/SKU/warehouse-origin snapshot, QR credential, handover proof, RMA lifecycle | Не делает direct UPDATE stock balance |
| IAM | VerifiedActor, active tenant/grants/revocation | QR не заменяет authentication/permissions |
| AUDIT | История доступа/действий | Не отдельный складской ledger |
| FINANCE | Future payment/refund/credit note | Stock return не означает refund |

TenantId = iam_workspace.id, склад = org_location.id. Один SKU на разных tenants — разные товары. One seller/order, one warehouse/order, full-line reservation остаются действующим R1 контрактом. Не переносить обязательства RM-STRATEGY-R4 W0 ради сложных функций.

## 3. RM-GEO-LOC-R1: широта и долгота склада

Расширить public.org_location **additive** миграцией:
- latitude NUMERIC(9,6) NULL: WGS84 -90..90.
- longitude NUMERIC(9,6) NULL: WGS84 -180..180.
- coordinate_source VARCHAR(20) NULL: MANUAL / GEOCODED / IMPORTED.
- coordinates_updated_at TIMESTAMPTZ NULL, geo_verified_at TIMESTAMPTZ NULL.

Пара GPS строго обе NULL либо обе не NULL. При заполненной паре обязательно source и updatedAt. Java + SQL отвергают неверные диапазоны, неполные/нечисловые значения и превышение precision; WGS84 координаты — не центроид KATO. Старые локации остаются без GPS и не блокируют текущую торговлю. Изменение через authenticated ORGANIZATION location operation, tenant-owned rowVersion/CAS, audit, совместимую owner row lock с INV-R1.4. Исторические snapshots заказов не обновляются при редактировании ORG.

## 4. RM-SALES-GEO-R1: складские координаты в заказе

При PLACED склад неизвестен; **не копировать складские GPS при оформлении заказа**. При первом успешном CONFIRMED добавить в crm.sales_order nullable поля:
- fulfillment_warehouse_id UUID с tenant-qualified composite FK к public.org_location(workspace_id,id).
- warehouse_kato_snapshot VARCHAR(16).
- warehouse_address_snapshot VARCHAR(500).
- warehouse_latitude_snapshot NUMERIC(9,6).
- warehouse_longitude_snapshot NUMERIC(9,6).
- warehouse_snapshot_captured_at TIMESTAMPTZ.

В одной SQL-транзакции: trusted SALES_ORDER_CONFIRM + INVENTORY_RESERVE admission; lock order; full-line reservations through OrderInventoryPort with ACTIVE WAREHOUSE owner guard; read current org_location by (workspace_id,id) under existing compatible owner lock; capture KATO/address/GPS from **one consistent revision**; store snapshot + CONFIRMED event; commit. Ошибка в одной строке — rollback всех изменений. На retry с тем же warehouseId snapshot не обновлять; другой warehouseId rejected. Cancel/fulfill/warehouse edit/reconciliation не меняют origin. Legacy CONFIRMED/COMPLETED без snapshot — UNKNOWN_LEGACY, никакого fake backfill и жесткого status CHECK до проверки populated данных. Warehouse origin ≠ buyer delivery destination ≠ фактическая доставка.

## 5. RM-REGION-SEARCH-R1: поиск по региону

Публичная read граница STOREFRONT: PublishedRegionalOfferSearch.search(regionKatoCode, text?, sku?, currency?, availableOnly, page, size) возвращает только опубликованные offer/price/PRODUCT и их advisory availability по выбранной территории.

Join с tenant qualifiers: STOREFRONT published offer → published CATALOG PRODUCT → INVENTORY stock balance → ACTIVE WAREHOUSE ORGANIZATION → GEO valid KATO. Available = on_hand - reserved, группировка per sellerTenant/offer/SKU; не смешивать два tenants с одинаковым sku text; не раскрывать внутренние balances, закрытые адреса, GPS, warehouse IDs без public-policy. В R1 только **exact KATO**: текущий KATO snapshot частичный, префиксное угадывание child territory запрещено. Поиск по административному предку — только после квалифицированной иерархии.

Наличие товара на складе региона не означает возможность доставки по всему региону. Для сервиса SERVICE нужен независимый service-coverage контур, а не stock lookup. Search может устареть к моменту покупки; confirm всегда повторно проверяет live stock и warehouse under locks. Bounded pagination, stable sort, без N+1 и полного scan; индексы после EXPLAIN на populated PG. Поиск не создаёт резерв.

## 6. QR: три разных сущности, а не один универсальный код

### 6.1 ORDER_REFERENCE — долговечная ссылка поиска

Генерировать из CSPRNG при первом PLACED, idempotent checkout retry возвращает прежний reference. Привязка tenantId/orderId/purpose=ORDER_LOOKUP. QR/ссылка не содержит raw contact/phone/email/address/JWT, buyerId, внутренние цены, warehouse secret. Для просмотра чужому пользователю одного QR недостаточно: buyer identity proof либо authenticated seller staff tenant permission. До полной buyer proof не выдавать постороннему приватный QR. При CANCELLED QR разрешает только просмотр разрешённой истории, но не pickup.

### 6.2 PICKUP_CREDENTIAL — короткоживущий proof получения

Выпускается только после READY_FOR_PICKUP и полной проверки ACTIVE reservation на выбранном складе. Не менее 128 бит энтропии CSPRNG (рекомендация 192–256), purpose-bound, tenant/order/warehouse bound, TTL конфигурируемый (целевой UX 2–5 минут для показанного кода), hashed/HMAC value в базе, server key generation/version, single active token per order. Не хранить plaintext token в БД, логах, событиях, аналитике, URL access logs. Rotation/reissue/revocation при expiry, cancel, смене риска или явном отзыве. Screenshot можно передать: потому нужен staff auth, окончательное подтверждение и, для высокой стоимости, отдельная approved recipient challenge/ID policy.

Постоянный ORDER_REFERENCE **не способен** выполнить pickup redemption. Сам факт сканирования краткоживущего кода тоже не списывает товар. Покупателю доступны безопасные процессы lost phone/print code/representative pickup; делегированное получение — отдельное авторизованное разрешение, не пересылка QR как полное право.

### 6.3 RETURN_REFERENCE — идентификатор RMA, не возврат остатков

Формируется только по утверждённой заявке на возврат (RMA) с исходным order/lines/qty. Scan лишь находит return case. Отдельное inspection/acceptance решает, что можно принять на остаток, что карантинировать и что отклонить. Платёжный refund — отдельный FINANCE процесс.

## 7. Состояния физического заказа и выдача

**R1 full-order pickup:** PLACED → CONFIRMED (full reserve, snapshot) → PREPARING → READY_FOR_PICKUP (stock still reserved, no on_hand debit) → COMPLETED (handover confirmed + atomic inventory fulfill).

Нужны typed service operations:
- startPreparation: status/tenant/RBAC checks; не списывает.
- markReadyForPickup: только когда все строки полностью зарезервированы и склад выбран; создаёт событие готовности; не уменьшает on_hand.
- issuePickupCredential: buyer authentication/proof, READY status, active reserves, expiry/reissue.
- scanPreview: trusted staff, выбранный tenant+warehouse; ограниченный DTO (номер/линии/количество/status), customer PII редактирован; нет stock mutation, token consumption или скрытого auto-fulfillment.
- confirmPhysicalHandover: staff нажимает отдельное подтверждение после проверки фактической передачи. В **одной PostgreSQL транзакции** проверить IAM SALES_ORDER_FULFILL + INVENTORY_FULFILL и object scope; order row FOR UPDATE; credential lock/expiry/purpose/state, warehouseId match; READY_FOR_PICKUP, full ACTIVE reservations. Вызвать существующий OrderInventoryPort.fulfill для каждой строки, записать immutable handover receipt, SALES status event COMPLETED и consume token. Любая ошибка — rollback и отсутствие частичных movement/receipt/consume. One full-order handover per order. Idempotency key+fingerprint; exact replay возвращает старый receipt без двойного списания.
- После commit: уведомление/печать из durable delivery process; failure уведомления не откатывает фактическую выдачу.

**Несовместимость текущего кода обязательна к исправлению:** SalesInventoryCommandService.fulfill принимает лишь CONFIRMED/PREPARING, а InventoryReservationService.fulfill также READY_FOR_PICKUP; новый pickup facade согласует переходы и доказывает оба уровня в PG. Не называть legacy fulfill «подтверждением выдачи»; он сейчас означает stock fulfillment. Existing direct fulfill либо сохраняется как явный legacy operation до миграции, либо отключается только после совместимой квалификации.

Неявный warehouse-scoped staff ACL пока не гарантирован: staff должен иметь tenant-scoped permissions и, до включения нескольких складов/операторов в production, отдельную утверждённую warehouse object policy. Нельзя считать ORG LOCATION_READ достаточным разрешением списывать stock.

**Отказные сценарии:** неверный seller tenant, неверный склад, заказ не READY, отменённый/закрытый заказ, токен другого purpose, forged/expired/revoked/used QR, два кладовщика, роль отозвана между scan/confirm, получение до передачи, сбой БД, нет сети. В R1 online-only; offline queue pickup запрещена, иначе нет безопасного exactly-once consume.

Невостребованный заказ: TTL pickup credential не освобождает резерв и не отменяет заказ. Срок хранения в READY и решение о cancel/release — отдельная утверждённая бизнес-политика.

## 8. Возврат: только отдельный RMA lifecycle

Original order остаётся COMPLETED. Новые SALES return_request, return_line, return_event с tenant FK, state, line qty, source fulfilled reference, return warehouse и actor proof. Возможные статусы: REQUESTED → AUTHORIZED → RECEIVED → INSPECTED → RESTOCKED / QUARANTINED / REJECTED. Согласовать сложный частичный возврат отдельно от full-order pickup; для каждой line accepted total <= fulfilled qty - earlier accepted returns.

Физическое поступление sellable товара — отдельная типизированная INVENTORY RETURN_RECEIPT, связанная с immutable original fulfillment movement + RMA line и idempotent once-only. Повреждённое, ошибочное или ещё не проверенное не увеличивает available; quarantine требует отдельной non-sellable policy, а не обычного RECEIPT в ACTIVE sellable stock. Нет automatic FINANCE refund, credit note, VAT/tax или изменения исходной продажи. SERVICE не создаёт физический RETURN_RECEIPT.

## 9. Предлагаемая SQL-модель без второго складского движка

Все новые схемы через additive Liquibase и populated PG qualification.

- crm.sales_order_qr_reference: tenant_id, reference_id, order_id, hashed_opaque_reference, purpose, state, created_at, revoked_at; unique active scoped reference; не plaintext buyer secrets.
- crm.sales_pickup_credential: tenant_id, id, order_id, warehouse_id, digest, key_generation, issued_at, expires_at, consumed_at, revoked_at, state, row_version; не больше одного активного credential/order.
- crm.sales_pickup_handover: tenant_id, id, order_id, warehouse_id, credential_id, staff_actor_id, request_key, request_fingerprint, happened_at, source_event_id; UNIQUE tenant+order для full-order pickup, immutable receipt.
- Return request/line/event только в RETURN-R1.2 после утверждения модели inspection.
- При регистрации R1 warehouse-origin snapshot используются nullable поля order из §4. Существующие rows остаются без backfill.

Не писать QR-credential в inventory или audit_event; неизменяемые inventory movements остаются единственным фактом складского расхода. Event outbox аналитики может использовать безопасные source IDs, не сам токен.

## 10. Безопасность, UX и аналитика

QR нужен как средство идентификации/подтверждения, **не как аутентификация сотрудника и не как bearer permission на одностороннее списание**. Staff scan всегда authenticated, проверяет active tenant, role grants, warehouse match, object policy. Ошибки foreign tenant/missing ref должны быть без перечисления заказов. Rate limit проверки, audit attempts без raw tokens, key rotation, HTTPS/owned domain allowlist, no open redirects. Public ссылки не раскрывают складские координаты и buyer PII.

UX покупателя: QR заказа и история доступны после proof; QR выдачи появляется только после уведомления READY; таймер действия, обновление, сообщение об уже полученном, lost phone/делегирование. UX кладовщика: открыть scanner → распознать QR → безопасный preview → сверить содержимое → отдельная кнопка «Выдать» → receipt. Для возврата: открыть RMA → проверить оригинальный order и допустимое количество → инспекция → принять по качеству → однозначное подтверждение.

Аналитические метрики после появления источников: placed→confirmed, confirmed→ready, ready→handover, число выдач/неявок/отмен, истёкших и подозрительных QR, return rate по причинам, производительность склада. Источник исторической географии **склада исполнения** — immutable warehouse snapshot; не выдавать за регион спроса покупателя. COMPLETED не равен PAID и не означает доставку курьером. Prometheus labels не должны включать QR secrets, orderId, персональные данные или безлимитный tenantId.

## 11. Приёмочные PostgreSQL сценарии

| ID | Проверка | Обязательный исход |
|---|---|---|
| GEO-01 | Координаты + некорректные lat/lon | Paired validation, tenant scope, CAS, audit |
| GEO-02 | update warehouse GPS × confirm | Полный snapshot одной ревизии, no mixed fields |
| GEO-03 | Legacy orders/warehouse without GPS | History remains UNKNOWN, current checkout works |
| REG-01 | KATO/две локации/две организации | Только опубликованные и допустимые остатки своего seller tenant |
| REG-02 | Search × competing reserve/transfer | Search advisory, confirm prevents oversell |
| QR-01 | Checkout idempotency | Один order, один стабильный reference |
| QR-02 | Foreign tenant / random QR lookup | No leak, no side effects |
| QR-03 | ORDER_REFERENCE used for handover | Denied |
| QR-04 | Credential before READY | Denied |
| QR-05 | READY without complete reservations | Denied |
| QR-06 | Expired, reused, forged, revoked, wrong purpose | Denied and audited; no fulfill |
| QR-07 | Two staff simultaneous handover scans | One commit/receipt, one movement per line |
| QR-08 | Role revoked × handover in either order | Proper serialization; no unauthorized writes |
| QR-09 | Preview only | No consume, no stock movement |
| QR-10 | Crash after movement before consume | Entire transaction rollback, restart safe |
| QR-11 | Wrong warehouse vs origin snapshot | No silent reallocation |
| QR-12 | Exact command retry/conflicting payload | Stable receipt or typed conflict |
| QR-13 | READY stock reserve + handover | on_hand/reserved debited exactly once |
| QR-14 | Warehouse coordinate update after handover | Old snapshot and handover remain immutable |
| QR-15 | No GPS, screen lost, offline | No fabricated GPS or offline issue; safe verified alternative |
| QR-16 | Service QR | No SERVICE SKU stock mutation; hold till service E2E |
| RET-01 | Return above fulfilled qty | Denied, no stock changes |
| RET-02 | Concurrent duplicate RMA receipt | One accepted stock contribution |
| RET-03 | DAMAGED / QUARANTINED | No sellable available increment |
| RET-04 | Physical return without FINANCE | No fictitious refund |
| PERF-01 | Populated migration + scan/QR/fulfillment load | No stale/revoked read, measured p95/p99, exact-SHA GREEN |

Все нужные tests — real PostgreSQL с двумя независимыми connections для гонок, independent SQL read после commit, H2 smoke только как дополнение. Прошлые exact-SHA evidence не переносятся автоматически на новый SHA. Никаких пропусков обязательных QR-кейсов. Production OIDC/HTTP/UI/security/load остаются самостоятельными gates.

## 12. Этапы и решения

| Этап | Граница поставки |
|---|---|
| GEO-LOC-R1 | GPS склада и owner mutation |
| REGION-SEARCH-R1 | Published PRODUCT by KATO and live available |
| SALES-GEO-R1 | Immutable warehouse origin at CONFIRMED |
| QR-R1.0 | Long-lived ORDER_REFERENCE and staff lookup, no stock debit |
| QR-R1.1 | PREPARING/READY, one-time pickup credential, authenticated handover and atomic stock fulfillment |
| SERVICE-QR-R1 | Separate service-check-in, after service fulfillment agreement |
| QR-R1.2 | RMA, inspection, RETURN_RECEIPT; refund belongs FINANCE |
| PERF-QR-R1 | Scan/replay/concurrency/security/load qualification |

**Решения, требующие продуктового утверждения:** в R1 только full-order pickup на одном складе; partial pickup и split R2; делегирование другому получателю отдельной авторизованной операцией; высокая стоимость/опасные товары требуют дополнительной выдачной проверки; no-show не отменяет заказ по expiry QR; точные координаты склада не раскрывать публично без pickup policy; возврат денег не запускать автоматически от сканирования.

**Definition of Done:** service-only implementation, additive SQL, populated migration, no cross-tenant disclosure, no double debit or return, proof of QR credential security, exact-SHA PostgreSQL tests, safe crash/retry, позже реальные authenticated browser/scanner E2E и operator playbook. Пока это только проектирование.
