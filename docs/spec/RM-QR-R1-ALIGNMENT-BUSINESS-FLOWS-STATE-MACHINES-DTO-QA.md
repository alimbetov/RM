# RM-QR-R1 — согласование QR-бизнес-процессов, state machines, DTO и тестов

**Статус:** DRAFT R1.1 / расширенное техническое задание, не реализация. **Дата:** 2026-11-22. **Ветка:** base_dev. **Source-review SHA:** 98b2607e231e6c84a04eea0a104f49ab60f36320 (source audit for R1.1). **Production:** HOLD.
**Неподвижные рамки:** Java 21 / Spring Boot modular monolith / PostgreSQL; только service-first, никаких новых REST-контроллеров, публичных API, отдельных QR-микросервисов, новых веток и merge в main на этом этапе. Реализация в последующих этапах должна пройти собственный exact-SHA PostgreSQL gate.

**Нормативные источники:** docs/architecture/RM-PLATFORM-STANDARD-R2.md, docs/spec/RM-STOREFRONT-AUTOORDER-R1.md, docs/spec/RM-COMMERCE-INVENTORY-COMPLETION-R1.md, docs/spec/RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md, docs/spec/RM-FILES-R1.md, docs/spec/RM-COMMERCIAL-GEO-REGION-QR-FULFILLMENT-R1.md, docs/spec/RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md, docs/strategy/RM-STRATEGY-R4-FUNCTIONAL-GAP-PRIORITIZATION.md. При конфликте действующий стандарт R2 и исходный код имеют приоритет над описанием предполагаемого поведения.

## 1. Цель и границы

Построить единый набор QR-сценариев для покупателей, продавцов, склада, курьеров и исполнителей услуг: от сканирования публичной карточки до заказа, торга, подтверждения, выдачи, доставки и возврата. QR служит **публичным указателем или ограниченным подтверждением действия**, а не источником истины, tenant-авторизацией или самостоятельным автоматом изменения остатков.

Предложенный R1 продуктовый вертикальный срез: PRODUCT_OFFER_QR → опубликованная карточка и количество → BUY_LIST или NEGOTIATE → PLACED + ORDER_REFERENCE → CONFIRMED + full reserve + warehouse origin snapshot → PREPARING → READY_FOR_PICKUP → одноразовый PICKUP_CREDENTIAL → уполномоченный кладовщик preview → явное подтверждение передачи → единственный INVENTORY fulfill + SALES COMPLETED. Возвраты и услуги описываются, но реализуются отдельными gate.

**Торговые инварианты:**
- Один seller tenant на заказ. Два продавца с одинаковым текстом SKU — разные коммерческие предложения и независимые складские данные.
- PLACED не резервирует. CONFIRMED резервирует все PRODUCT lines на одном выбранном ACTIVE WAREHOUSE. On-hand уменьшается только при исполнении через INVENTORY. Никакое сканирование, открытие карточки или начало торга не изменяет баланс.
- В R1 одна строка заказа = один полный резерв, один склад на заказ; частичная выдача и multi-warehouse split требуют отдельной версии контрактов.
- Публичная цена принадлежит STOREFRONT; цена, принятая покупателем, становится immutable SALES line snapshot. Персональное ценовое предложение никогда не изменяет общую цену каталога/витрины.
- GPS склады принадлежат ORGANIZATION. Выбранный warehouseId, KATO, адрес и доступные WGS84 координаты копируются в immutable SALES snapshot **при первом успешном CONFIRMED**, не при PLACED. Warehouse origin — не buyer delivery destination.
- Самому SERVICE не полагаются inv_stock_balance или InventoryReservationService. SERVICE QR ordering/check-in только после отдельного service fulfillment.
- Никакой QR не создаёт прав на чтение приватных заказов, получение товара, чтение FILES или изменение склада без отдельной проверенной политики.

## 2. Source-derived фактическая реализация и GAP

| Домен | Действующий контракт в коде | Новый контракт QR |
|---|---|---|
| STOREFRONT | store_front/store_offer, DRAFT/PUBLISHED/PAUSED/HIDDEN, price and optimistic CAS | Public stable offer QR, alias rotation, публичный read card |
| CATALOG | PRODUCT/SERVICE, item translations and price history | Публичные description/specifications с source-locale |
| FILES | file_object/storage/attachment, антивирус и CRM_CUSTOMER ACL | CATALOG_ITEM owner access, безопасная галерея/thumbnail/publication |
| SALES/AUTOORDER | checkout seller offer + integer qty 1..1000, email-first, PLACED, server-owned snapshot, idempotency receipt | AcceptedQuote checkout, order lookup QR, preparing/ready/pickup receipt, RMA |
| INVENTORY | balances, ACTIVE/RELEASED/FULFILLED reservation, OrderInventoryPort | Только reusable reserve/release/fulfill, отдельный RETURN_RECEIPT позже |
| ORGANIZATION/GEO | public.org_location KATO/address/status | Взаимодействие с GPS и regional search по связанному ТЗ |
| IAM | tenant-scoped grants + VerifiedActor / buyer proof | Role-gated negotiation, warehouse-scoped operator eligibility, revoke races |
| AUDIT | Частичная security history | События выдачи/возврата с безопасным provenance |
| FINANCE | Не завершён | Нет автоматических refund/payment state |
| INSIGHT | ТЗ аналитики | Считать только реально созданные события, не сканы как продажи |

Критический найденный конфликт: SalesInventoryCommandService.fulfill допускает CONFIRMED/PREPARING и сразу COMPLETED; InventoryReservationService.fulfill уже допускает READY_FOR_PICKUP, но полноценной SALES pickup-команды нет. DB enum READY_FOR_PICKUP не равен реализованному переходу. Новый код должен **дополнить** эти контракты, не заявлять legacy fulfill физической передачей без handover receipt.

## 3. Закрытый QR purpose registry

| Purpose | Сканирует | Результат | Admission | Приоритет |
|---|---|---|---|---|
| PRODUCT_OFFER | Любой покупатель | Товар: фото, описание, цена, регион, покупка или торг | Публичная публикационная политика, без buyer login для просмотра | P0 |
| STOREFRONT | Любой покупатель | Ассортимент конкретного продавца | Публичная публикационная политика | P1 |
| ORDER_REFERENCE | Покупатель или продавец | Собственный заказ/история и статус | Verified buyer ownership или seller SALES_ORDER_READ | P0 |
| PICKUP_CREDENTIAL | Покупатель показывает, кладовщик сканирует | Preview, затем отдельная подтверждённая выдача | Buyer proof при выпуске + staff SALES_ORDER_FULFILL и INVENTORY_FULFILL | P0 |
| RETURN_REFERENCE | Покупатель / сотрудник приёмки | Конкретная заявка возврата | Ownership + return permission | P1 |
| SERVICE_OFFER | Клиент | Фото и условия услуги | Публичная публикация; service checkout HOLD | P2 |
| SERVICE_CHECKIN | Клиент/исполнитель | Визит / старт / завершение услуги | Утверждённое расписание, actor / object policy | P2 |
| PACKAGE_LABEL | Комплектовщик/курьер | Коробка и содержимое заказа | Staff object scope; не stock debit | P2 |
| DELIVERY_HANDOFF | Курьер/получатель | Отдельная передача и proof of delivery | Verified courier/recipient, не pickup proof | P2 |
| WAREHOUSE_LOCATION | Кладовщик | Выбор склада/ячейки | Location-specific eligibility, не grant | P2 |
| STOCKTAKE_ITEM | Инвентаризатор | Пересчёт конкретного SKU | INVENTORY_ADJUST + lock/approval | P2 |
| TRANSFER_BATCH | Сотрудник склада | Поиск межскладского transfer | INVENTORY_ADJUST + tenant/location | P2 |

Purpose нельзя подавать клиентом как произвольный класс операции. QR-публичный resolver не должен выполнять stock mutation, seller command или выдавать приватный order DTO.

## 4. Бизнес-процессы, окончательный перечень

**BP-01 Публикация и печать QR товара.** Продавец публикует PRODUCT/offer; STOREFRONT идемпотентно регистрирует непривязанный к текущей цене stable opaque alias; печать со светлой quiet-zone и круглым центральным бейджем с галочкой. QR не подтверждает stock или оплату. Price/media/title/stock change не меняют alias. Pause/hide/archive отключает checkout и закрывает приватные media. Revoke/rotate требует отдельного seller permission и аудита.

**BP-02 QR-карточка.** Публичный пользователь сканирует код без регистрации; видит опубликованные изображения, описание, характеристики, текущую цену/currency, серверную единицу измерения, индикативную доступность по выбранному KATO и seller. No side effects. FILES допускает только CATALOG_ITEM PUBLIC approved CLEAN variants; существующий CRM_CUSTOMER adapter не может быть использован как обход. КАТО exact match MVP, проверенная GEO hierarchy позже; никаких утверждений об обещанной доставке.

**BP-03 Покупка по обычной цене.** Количество пока INTEGER 1..1000 на одну offer line; агрегирование повторов проверяется сервером. При checkout повторно проверить offer/product published, seller status, amount/currency и buyer-approved terms, сформировать immutable order price snapshot, PLACED. Изменение цены между preview и checkout требует явного согласия, не silent repricing. Никакого резерва на PLACED.

**BP-04 Предложить цену.** Verified buyer (или ограниченный pending intent с email proof до seller acceptance) создаёт PriceProposal с offer, количеством, предлагаемой ценой за единицу, валютой, expiry и seller tenant. Создание proposal не меняет STOREFRONT price или склад, не создаёт order. Privacy, anti-spam и rate limits обязательны.

**BP-05 Торг и согласование.** Продавец с явным tenant SALES_PRICE_NEGOTIATE принимает, отклоняет или отправляет встречную цену. Buyer подтверждает ту же immutable revision. Срок и число раундов ограничены. Winner определяется CAS rowVersion; предложение по qty/currency нельзя тихо изменить. Final AGREED acceptedQuote — персональный договорный snapshot, не order и не stock guarantee.

**BP-06 Checkout согласованной цены.** Только verified buyer, связанный с quote, подтверждает final qty/price/currency. В одной PG-транзакции seller/offer eligibility + accepted quote lock + once-only consumption + PLACED order/lines with priceSource=NEGOTIATED + immutable acceptedQuoteId + idempotency receipt. Retry same terms → same order; changed payload → conflict; seller public price не меняется. Confirm позже может отклонить при отсутствии stock.

**BP-07 Заказ и ORDER_REFERENCE.** После PLACED формируется стабильный поиск заказа. Покупатель после identity proof видит свой заказ и статус, seller staff — только в рамках SALES permission. QR reference не является разрешением на выдачу. CANCELLED остаётся видимым в разрешённой истории.

**BP-08 Подтверждение и склад.** Seller делает CONFIRMED, в одной транзакции резервирует full PRODUCT order на одном складе и сохраняет immutable warehouse origin KATO/address/GPS. Неправильный tenant/склад, отсутствие stock, concurrent disable warehouse → rollback. Никаких координат при PLACED; изменение склада после confirm не переписывает history.

**BP-09 Подготовка и READY.** Authorized warehouse staff переводит CONFIRMED → PREPARING → READY_FOR_PICKUP при полном ACTIVE резерве, с проверенным warehouse object scope. READY не списывает on_hand. Доступен выпуск краткоживущего pickup proof, но его истечение не отменяет резерв.

**BP-10 Сканирование и выдача.** Покупатель показывает one-time PICKUP_CREDENTIAL. Staff со своим проверенным IAM actor выбирает tenant и warehouse. Scan → restricted preview; кнопка «Подтвердить фактическую выдачу» → одна PG-транзакция: fresh grants, order lock, credential row lock, purpose/expiry/warehouse/READY, active reservations → OrderInventoryPort.fulfill all lines → immutable handover receipt + COMPLETED event + consume credential. Ошибка → полный rollback; competing scanners → одна проводка и один receipt. Нельзя выдавать со склада другого tenant или без подтверждения передачи.

**BP-11 Плохая связь/утерянный QR/делегирование.** R1 online only, offline stock fulfillment запрещён. При утере пользователь проходит buyer proof, получает новый credential, старый отзывается. Screenshot может быть переслан: краткий TTL, одноразовость, staff admission и, при необходимости, recipient challenge. Делегирование — отдельный верифицированный получатель, не пересылка ORDER_REFERENCE.

**BP-12 Неявка и отмена.** Expired pickup token != CANCELLED order. No-show policy задаёт срок хранения/напоминания и возможное уполномоченное освобождение резерва. Текущий cancel не разрешает READY_FOR_PICKUP: если разрешить такую отмену, разрабатывать отдельный согласованный SALES+INVENTORY release с тестами, а не менять стейт-машину без ревью.

**BP-13 Доставка и упаковки (R2).** PACKAGE_LABEL идентифицирует ящик, не выполняет movement. OUT_FOR_DELIVERY и proof-of-delivery — отдельный courier workflow; pickup QR не является delivery proof; прочитанный qr пакета не делает заказ COMPLETED. Partial package/partial pickup требует новую allocation/fulfillment line модель после R1.

**BP-14 Возврат товара (R1.2).** По завершённому order buyer создаёт RMA на конкретные исполненные строки/qty. Return QR лишь открывает заявку. Seller approval → warehouse physical receive → inspection → sellable restock or quarantine/reject с отдельным типизированным INVENTORY RETURN_RECEIPT. Accepted return qty не превышает fulfilled minus already accepted. Original COMPLETED остаётся неизменным; refund — отдельный FINANCE lifecycle.

**BP-15 QR услуги (R2).** SERVICE_OFFER открывает публичную карточку, но SERVICE checkout пока HOLD до service fulfillment: scheduling, provider, region, slot/capacity, service-checkin, completion, cancellation. Сам SERVICE не уменьшает stock. Если нужны материалы, они являются отдельными PRODUCT/INVENTORY потребностями, а не stock qty услуги.

**BP-16 Ячейки, инвентаризация, перенос (R2).** WAREHOUSE_LOCATION, STOCKTAKE_ITEM, TRANSFER_BATCH идентифицируют только объект; завершение count/post/adjust/transfer вызывает существующие авторизованные INVENTORY commands, с location policy, event provenance и tenant qualification.

## 5. State machines — не смешивать независимые статусы

| Aggregate | State/transition | Ответственный |
|---|---|---|
| Catalog item | Существующие DRAFT/PUBLISHED/ARCHIVED | CATALOG |
| Seller offer | DRAFT → PUBLISHED ↔ PAUSED, переход HIDDEN по действующему контракту | STOREFRONT |
| QR alias | ACTIVE → REVOKED, отдельная reissue generation | STOREFRONT или SALES по purpose |
| PriceProposal | SUBMITTED → SELLER_ACCEPTED / COUNTERED / REJECTED / WITHDRAWN / EXPIRED; COUNTERED → BUYER_ACCEPTED / COUNTERED / DECLINED / EXPIRED; достижение AGREED только по единой revision обеих сторон | SALES |
| AcceptedQuote | AGREED → CHECKED_OUT / EXPIRED / INVALIDATED | SALES, one-time checkout |
| Physical Order | PLACED → CONFIRMED (reserve) → PREPARING → READY_FOR_PICKUP → COMPLETED (handover + debit); другие существующие statuses остаются отдельными разрешёнными путями | SALES |
| Stock reservation | ACTIVE → RELEASED или FULFILLED | INVENTORY, единственный источник stock delta |
| PickupCredential | ISSUED → CONSUMED / REVOKED / EXPIRED | SALES |
| PickupHandover | Только immutable успешный receipt; отказ не создаёт receipt | SALES |
| RMA | REQUESTED → AUTHORIZED → RECEIVED → INSPECTED → RESTOCKED / QUARANTINED / REJECTED | SALES для заявки, INVENTORY для stock receipt |
| Service appointment | DRAFT → REQUESTED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED / CANCELLED, только после отдельного service ADR | SALES service fulfillment R2 |

**Точное ограничение текущей реализации:** из физических order переходов сейчас реализованы confirm, cancel, fulfill; ввод PREPARING/READY и новой выдачи требует изменение SALES команд, неизменяемых status events и согласования guard с INVENTORY. Нельзя объявить state machine готовой из-за enum в DDL. Доставка и возвраты не должны автоматически переиспользовать COMPLETED как payment/refund или service completion.

## 6. Service-only контракты (предлагаемые)

Запрещены новые controller/request mappings. Все контрактные классы — Java records и application services/ports в существующих пакетах. Названия уточнить относительно latest base_dev перед реализацией.

| Domain package | Service/port | Команды или запросы | Авторизация |
|---|---|---|---|
| kz.rm.storefront | PublishedOfferQrService | issue, resolvePublicCard, revoke, reissue | Seller management grant для мутаций; publication policy для public lookup |
| kz.rm.storefront | RegionalOfferQuery | findPublished(regionKato, text, page) | Public allowlist; никаких скрытых warehouse balances |
| kz.rm.catalog | PublishedProductDetailsPort | publishedTextAndSpecifications | Собственная catalog publication |
| kz.rm.files | CatalogItemMediaPort | listApprovedPublicImages | FILES + CATALOG_ITEM owner policy |
| kz.rm.sales | PriceProposalCommandService | submit, counter, accept, reject, withdraw | Verified buyer / seller SALES_PRICE_NEGOTIATE |
| kz.rm.sales | AcceptedQuoteCheckoutService | checkoutQuote | Verified buyer, seller/storefront/offer locks |
| kz.rm.sales | OrderQrLookupService | issueReference, readOwnOrder, revoke | Buyer ownership или tenant SALES_ORDER_READ |
| kz.rm.sales | PickupPreparationService | startPreparing, markReady, issuePickupToken | Seller grants, warehouse object scope |
| kz.rm.sales | PickupHandoverService | scanPreview, confirmHandover | Fresh SALES_ORDER_FULFILL + INVENTORY_FULFILL; selected warehouse |
| kz.rm.sales | ReturnRequestService | request, authorize, receive, inspect | Verified buyer/seller; approved return rights |
| kz.rm.inventory | OrderInventoryPort | reserve/release/fulfill | Уже существующий dual-permit boundary, не копировать |
| kz.rm.inventory | InventoryReturnPort (R1.2) | acceptSellableReturn | Tenant/warehouse/source movement, typed return right |
| kz.rm.organization | LocationProfilePort | currentWarehouseSnapshot | Tenant qualification, stable locked revision |

**Locking:** использовать текущий checkout lock stripe → IAM workspace → storefront → offer → catalog и order row → reservation stripes → stock → reservation, не внедрять обратный lock order. Для confirmed pick-up credential lock разрешён только в последовательности, не создающей deadlocks с order+reservation. Требуется отдельный concurrency-review, deterministic two-connection PG tests и явная lock-order таблица перед кодом.

## 7. Типизированные DTO / команды без транспортного слоя

| DTO record | Поля, владелец | Примечание |
|---|---|---|
| PublicOfferCardDto | publicOfferRef, sellerDisplayName, title, description, locale, gallery, unitPrice, currency, unit, publishState, availabilityBand, asOf, region, canOrder, canNegotiate | Публичный allowlist, no private stock |
| PublicImageDto | opaqueImageRef, variant, altText, sortPosition | No storageKey, EXIF, private original |
| CreatePriceProposalCommand | opaqueOfferRef, quantity, proposedUnitPrice, currency, expiresAt, expectedOfferVersion, idempotencyKey | Buyer identity injected from verified service boundary |
| PriceProposalDto | proposalId, offeredUnitPrice, listUnitPriceAtSubmit, quantity, currency, status, revision, expiresAt, counterAmount | Доступ только buyer/allowed seller |
| PriceProposalTransitionCommand | proposalId, expectedRevision, action, optionalCounterUnitPrice, requestKey | Revision CAS |
| AcceptedQuoteDto | acceptedQuoteId, quantity, unitPrice, currency, expiresAt, version, buyerRef | Не универсальный bearer coupon |
| QuoteCheckoutCommand | acceptedQuoteId, buyerConfirmationRevision, idempotencyKey | Same quote + buyer can create at most one order |
| OrderQrDto | publicOrderRef, sanitizedStatus, createdAt | Не содержит pickup credential |
| WarehouseOriginSnapshotDto | warehouseId, KATO, address, latitude, longitude, capturedAt | Seller private, immutable after confirm |
| PickupCredentialDto | renderedOneTimeQrPayload, expiresAt, displayInstructions | Только authenticated buyer; raw token не сохраняется |
| PickupPreviewDto | orderRef, lineSummary, warehouseLabel, readiness, canHandover | Клиентский preview ничего не проводит |
| ConfirmHandoverCommand | credentialRef, orderRef, selectedWarehouseId, idempotencyKey, expectedOrderVersion, explicitConfirmation | Verified staff actor, never from QR |
| HandoverReceiptDto | receiptId, orderRef, warehouse, handledAt, lineCount, finalStatus | Immutable; no payment claim |
| ReturnRequestDto | returnRequestId, originalOrderRef, permittedLineQuantities, currentStatus | No implicit refund |
| ReturnInspectionCommand | returnRequestId, expectedRevision, lineId, acceptedQty, qualityClass, locationId, idempotencyKey | Quarantine != sellable stock |

Ключ tenantId/actorId поступает из проверенного server context, а не автоматически считается доверенным, если оказался в DTO. Quantity пока integer 1..1000 в существующем checkout; дробные единицы измерения требуют отдельной согласованной миграции CATALOG/SALES/INVENTORY.

## 8. Модель хранения, tenant isolation и секреты

- Не создавать универсальную crm.qr_action таблицу или отдельную CRM-копию заказа/stock.
- STOREFRONT: crm.store_offer_qr_ref (tenant_id, offer_id, public_ref, generation, state, issued_at, revoked_at), unique active offer alias; доступность зависит от действующей публикации.
- SALES: crm.sales_price_proposal, crm.sales_price_proposal_event, crm.sales_accepted_quote; composite FK и optimistic CAS. PriceProposal не меняет store_offer.unit_price; quote once-only consumed при checkout.
- SALES: crm.sales_order_qr_reference и crm.sales_pickup_credential, crm.sales_pickup_handover, позже sales_return_request/line/event. QR lookup и pickup credential — **разные purpose и таблицы**. Plaintext pickup token не хранить; strong CSPRNG 192–256 bits рекомендовано, hash/HMAC, purpose, tenant/order/warehouse, expiry, one-time server-side consume и revoke.
- SALES: immutable warehouse origin поля по связанному GEO spec. Старые CONFIRMED/COMPLETED без снимков остаются UNKNOWN_LEGACY.
- ORGANIZATION: nullable latitude/longitude WGS84, paired CHECK, tenant & rowVersion. No KATO centroid masquerading as GPS.
- FILES: только существующие file_object/storage/attachment и typed CATALOG_ITEM owner access; без локальных фото в SALES.
- Резерв и movement остаются только INVENTORY truth. Для возврата новый typed RETURN_RECEIPT с original movement provenance и ограничением cumulative accepted qty.
- Каждая таблица tenant-first PK/unique/composite foreign key, каждый read/filter/pagination tenant-scoped или публично опубликован без закрытых данных. Actor grants one tenant cannot authorize second; warehouse object assignment пока не считать реализованным.
- Buyer proof обязателен для персонального торга/заказа. Публичная карточка без логина, но не публичный доступ к чужим заказам. Lost token: reissue after buyer proof, old revoke; QR screenshot может быть передан, поэтому финальный handover требует штатной проверки.
- TTL QR получения не равен TTL резерва и не создаёт автоматического no-show или cancel.
- QR с круглой галочкой в центре — декоративная верстка, не значок VERIFIED seller/paid/available. Проверить реальные смартфоны, quiet zone и устойчивость печати.
- Populated Liquibase additive only; legacy changesets and main branch untouched.

## 9. PostgreSQL negative / race qualification matrix

**Тесты ещё не написаны и не прошли**: ниже минимальный перечень будущих обязательных JUnit/PostgreSQL доказательств, не заменяемый unit/H2 проверками.

| ID | Бизнес-процесс | Проверка | Pass condition |
|---|---|---|---|
| QR-P01 | BP-01 | Public QR issue/reprint | One stable alias for same seller offer |
| QR-P02 | BP-01 | Seller A/B same SKU; foreign QR | Exact seller offer, no cross-tenant leak |
| QR-P03 | BP-01/02 | Offer pause, archive, revoke, photo update | No private media / checkout from withdrawn offer, previous orders immutable |
| QR-P04 | BP-02 | FILES publication owner policy | Only safe CLEAN AVAILABLE published CATALOG_ITEM preview |
| QR-P05 | BP-02 | KATO/warehouses and reserved | Public availability is scoped and indicative, not leaking internal balance |
| QR-P06 | BP-03 | Qty 0,1,1000,1001/fraction and duplicate line merge | Invalid requests rejected; valid totals correct |
| QR-P07 | BP-03 | Changed public price between preview and checkout | No silent repricing, explicit new acknowledgment |
| QR-P08 | BP-03 | Double checkout same idempotency key | Exactly one PLACED and status event |
| QR-N01 | BP-04 | Price proposal with verified buyer | Proposal only, no order/reserve or global price change |
| QR-N02 | BP-04 | Foreign proposal/buyer, fake seller role | Denied, no PII/tenant enumeration |
| QR-N03 | BP-05 | Concurrent accept vs counter, expected revision | One CAS winner; immutable agreed terms |
| QR-N04 | BP-05 | Seller revoke × acceptance, both commit orders | No unauthorized committed mutation |
| QR-N05 | BP-05/06 | Proposal expired/withdrawn/offer hidden | No order and no quote consumption |
| QR-N06 | BP-06 | Two simultaneous accepted-quote checkouts | Exactly one order, same replay receipt |
| QR-N07 | BP-06 | Changing agreed qty/currency/unit price | Conflict, no silent client override |
| QR-N08 | BP-06 | Different buyer, seller suspended | Denied with zero partial side effects |
| QR-N09 | BP-06/08 | Quote checkout then insufficient stock on confirm | PLACED valid, confirm full rollback |
| QR-O01 | BP-07 | Lookup reference without buyer proof | No private order data |
| QR-O02 | BP-07 | Checkout retry reference / cancelled order | Stable history reference, no pickup ability |
| QR-G01 | BP-08 | Confirm while warehouse GPS changes | Complete snapshot from one ORG revision |
| QR-G02 | BP-08 | Warehouse foreign tenant/inactive/no GPS/legacy | Safe rejection or explicit unknown fields, no fake GPS |
| QR-H01 | BP-09 | READY without full active reserves | Denied, no stock debit or credential issuance |
| QR-H02 | BP-10 | QR lookup only / preview only | No token consumption, no movement |
| QR-H03 | BP-10 | ORDER_REFERENCE as pickup proof | Denied: purpose mismatch |
| QR-H04 | BP-10 | Two scanners concurrent handover | One receipt, one debit per line, no oversell |
| QR-H05 | BP-10 | Crash after movement before consume | Entire transaction rollback, safe retry |
| QR-H06 | BP-10 | Wrong warehouse, expired/used/revoked token | Denied with no side effects |
| QR-H07 | BP-10 | Staff role revoke × fulfill, two orders | Authorization fence, no unauthorized handover |
| QR-H08 | BP-10 | READY vs existing SALES fulfill status guard | Both SALES and INVENTORY transitions consistent |
| QR-H09 | BP-11 | Screenshot replay/lost QR/reissue | Old proof revoked, new one works exactly once |
| QR-C01 | BP-12 | QR expires while order READY | Reservation remains; no silent cancellation |
| QR-R01 | BP-14 | Return exceeds fulfilled minus accepted | Denied, no stock increment |
| QR-R02 | BP-14 | Two staff accept same return | One physical receipt, no double restock |
| QR-R03 | BP-14 | Damaged/quarantine inspection | No sellable-stock increment |
| QR-R04 | BP-14 | Return without FINANCE | No fictitious payment refund |
| QR-S01 | BP-15 | SERVICE QR does not invoke stock mutation | Service stockless, checkout HOLD until accepted service model |
| QR-PERF01 | Все | Populated migration and mixed checkout/scan/retry/load | No deadlocks/connection starvation, recorded p95/p99 and exact-SHA artifacts |

Каждая строка qualification: independent SQL oracle (не тестировать свой метод самим собой); отдельные PostgreSQL connections с барьерами для гонок; после отказа сравнить БД и side effects; H2 для smoke, PostgreSQL обязательно для регрессий. Для соответствующего реализованного gate зарегистрировать конкретные method-level tests в текущем exact-SHA workflow и verifier, без фиктивного увеличения числа R3 capabilities. Security, populated upgrade и workflow tests не пропускать.

## 10. Реализация по этапам — завершение одного процесса до следующего

| Этап | Домен, предмет | Definition of Done |
|---|---|---|
| QR-00 | Source trace + утверждение lock-order и R1 бизнес-правил | Таблица существующие commands/owner/event/permissions и неизменяемые инварианты |
| QR-01 | PRODUCT QR + published read-only offer card, без фото при отсутствии owner policy | QR-P01..03, tenant/public read proof |
| QR-02 | Public FILES image owner, regional availability, safe gallery | QR-P04..05 + populated PG + FILES regression |
| QR-03 | List-price checkout through QR and safe ORDER_REFERENCE | QR-P06..08 + QR-O01..02 |
| QR-04 | PriceProposal/CAS + AcceptedQuote + once-only checkout | QR-N01..09, original checkout unchanged |
| QR-05 | Warehouse origin snapshot + PREPARING/READY service contracts | QR-G01..02 and QR-H01 |
| QR-06 | Short-lived PICKUP_CREDENTIAL, preview, atomic handover receipt | QR-H02..09, no double movement, status guard reconciled |
| QR-07 | Return RMA + inspection + typed inventory receipt | QR-R01..04 |
| QR-08 | SERVICE QR booking/fulfillment and courier/package/stocktake QR | Separate approved service and logistic specifications |
| QR-PERF | Mixed-load qualification | p95/p99 baseline/regression, no extra reservation lock order |
| QR-HTTP | Only after independent identity/transport gate | Browser + real phone scanner, no controller before gate |

Рекомендованный MVP: QR-00 → QR-01 → QR-02 → QR-03, затем QR-04 (торг) и QR-05/06 (склад). QR-07/08 нельзя ставить признаком завершённости первого R1.

## 11. Решения и неразрешённые продуктовые политики

**Зафиксировать как R1 default:**
- R1 pickup — полный заказ на одном складе, partial pickup/split R2.
- PRODUCT_OFFER_QR стабилен при изменении цены/остатка; фото и цена отражают текущую публикацию, order history — immutable.
- Предложенная цена — за единицу при фиксированном количестве. AcceptedQuote приватен, имеет TTL, не резервирует stock.
- ORDER_REFERENCE никогда не является pickup credential; выдача требует отдельного one-time proof и authenticated staff.
- Offline scan не делает списание; lost phone и delegated pickup требуют отдельного safe recovery.
- Возврат физического товара не меняет original COMPLETED и не инициирует refund.
- Вклад аналитики строится из source events: QR scans не являются продажами или оплаченным оборотом.
- Центральный круг с галочкой в QR — декоративный знак, не security/assertion badge.

**Отдельные решения до кода:**
- Точная location-specific staff access policy. Текущий tenant grant не означает автоматически доступ к каждому складу.
- Встречное предложение: число раундов, предел суммы, TTL, возможность seller automatic rejection, критерии идентификации покупателя.
- Перевод READY_FOR_PICKUP в CANCELLED и no-show retention; текущий cancel guard требует отдельного изменения.
- Услуга: service resource/time/location/slot и материалы; пока не активировать SERVICE checkout.
- Фактическая доставка/курьерская передача и юридический proof; не смешивать с выдачей на складе.
- Дробные UOM (метры/кг) требуют согласованного BigDecimal quantity контракта по всем доменам, а не просто другого input type.

**Признак готовности:** только реализованный service-only code + additive migrations + unit/H2 + PostgreSQL negative/race tests с exact-SHA PASS; реальный OIDC/browser/QR camera и performance admission отдельно. Данный документ не даёт production допуска и не подтверждает реализацию.

---

## 12. R1.1 — нормативная детализация и решения по противоречиям

Следующие правила уточняют разделы 1–11 и обязательны при реализации. Если продуктовая политика помечена DECISION REQUIRED, разработчик не вправе заменять её собственным решением. Статус спецификации не означает реализацию или прохождение тестов.

| ID | Правило | Инвариант |
|---|---|---|
| QR-ADR-01 | QR не образует новый DomainModule | Owning domain принимает любое бизнес-решение |
| QR-ADR-02 | PRODUCT_OFFER_QR связан с seller tenant и store_offer, не только с SKU | Один SKU может иметь разные цены у продавцов |
| QR-ADR-03 | Постоянный public_ref товара и заказа — несекретный случайный идентификатор, сохраняемый для повторной печати | Hash-only публичного alias не позволяет восстановить тот же QR |
| QR-ADR-04 | Только одноразовый PICKUP_CREDENTIAL хранится как защищённый digest | Нет raw token в DB, логах, аналитике |
| QR-ADR-05 | Публичная карточка допускает только опубликованные CATALOG/FILES данные | Нет приватных файлов или закрытых складских остатков |
| QR-ADR-06 | Принятая покупателем цена и публичная цена STOREFRONT независимы | Торг не меняет store_offer.unit_price |
| QR-ADR-07 | Переговоры и согласование не резервируют товар | Только CONFIRMED выполняет reserve |
| QR-ADR-08 | Quantity PRODUCT R1 — целое 1..1000 | Дробные единицы требуют отдельного CATALOG/SALES/INVENTORY контракта |
| QR-ADR-09 | Warehouse origin snapshot сохраняется при первом успешном CONFIRMED | PLACED ещё не знает склад |
| QR-ADR-10 | Новый pickup исполнен только после READY_FOR_PICKUP и физического handover | Старый fulfill из CONFIRMED не является подтверждением передачи |
| QR-ADR-11 | R1 один продавец, один склад на заказ, все строки целиком | Нельзя незаметно вводить split/partial pickup |
| QR-ADR-12 | Один full-order handover создаёт один receipt и одно множество stock movements | Повторный scan не списывает второй раз |
| QR-ADR-13 | Возврат оформляется через отдельный RMA | COMPLETED заказ не возвращается в PLACED |
| QR-ADR-14 | SERVICE не использует stock balance для самой услуги | Только отдельный service appointment после своего gate |
| QR-ADR-15 | Текущий складской KATO не является адресом покупателя и зоной доставки | Историческая геоаналитика не подменяет понятия |
| QR-ADR-16 | Только Java application services/ports/records и PG qualification | Ни одного нового REST controller или public API |
| QR-ADR-17 | Любое изменение порядка блокировок требует отдельного lock graph и двухконнектных тестов | Сохранить SALES, INVENTORY и IAM concurrency guarantees |
| QR-ADR-18 | Центральная круглая галочка QR — декоративный элемент | Не означает VERIFIED, PAID или гарантированное наличие |

Пять независимых типов статуса нельзя смешивать: публикация SKU, публикация offer, региональное наличие, коммерческие переговоры и жизненный цикл заказа. Состояние credential, RMA и appointment добавляются отдельно по мере реализации.

## 13. Подробные бизнес-контракты и поведение на границах

### 13.1 BP-01/02 — публикация, QR-карточка, фото, регион

**Участники:** уполномоченный seller, анонимный посетитель. **Источник истины:** STOREFRONT current published offer + CATALOG published PRODUCT + FILES approved CATALOG_ITEM attachments + INVENTORY live stock + ORG/GEO регион. Публикация товара и создание стабильного QR — разные действия.

**Preconditions issue/reprint:** store_front PUBLISHED, seller workspace ACTIVE, offer PUBLISHED, SKU PRODUCT PUBLISHED, seller permission; один активный QR alias для offer. Reprint возвращает тот же публичный ref и URL при новой цене/фото/остатке. Explicit revoke закрывает ref; reissue создаёт новую generation и не восстанавливает старую.

**Public read:** проверить текущее состояние store/offer/item при каждом разрешении ссылки; локаль kk/ru/en/zh-Hans, fallback source_locale; title, description, characteristics (если утверждены), approved images, public price/currency, unit, stock availability band и selected KATO. Никаких tenant staff IDs, raw balance, owner storage keys, private images, buyer PII. Отсутствие файла не блокирует обычную продажу, если обязательность фото не введена отдельной политикой. Недоступный scanner/AV ERROR никогда не превращает закрытый файл в PUBLIC.

**Region:** в R1 только точное соответствие допустимого KATO; нельзя выводить иерархию регионов из совпадения текстового префикса кода, поскольку справочник частичный. Наличие товара в регионе не обещает доставку по этому региону. Показанный available — advisory; checkout и confirm повторно проверяют свои факты.

**Error policy:** revoked, hidden, archived и foreign QR возвращают единый public unavailable без раскрытия внутреннего существования; seller management read получает typed reason после IAM admission.

### 13.2 BP-03 — заказать по опубликованной цене

**Input:** offer reference, integer quantity 1..1000, region, seller-owned terms, email-first buyer proof и idempotency key. Цена, SKU и валюты от клиента не являются доверенными.

**Preconditions:** active seller, published storefront/offer/PRODUCT, допустимое количество, согласованные условия, verified или pending buyer согласно нынешнему AUTOORDER flow. Если цена изменилась с момента preview — вернуть PRICE_RECONFIRM_REQUIRED, не создавать order по молча обновлённой цене.

**Transaction result:** immutable PLACED с server-owned SKU/title/quantity/unitPrice/currency snapshots, одна запись sales_checkout_receipt и status event. Нет inv_stock_reservation. Повтор с теми же параметрами возвращает тот же order; тот же key с другим payload даёт IDEMPOTENCY_CONFLICT.

### 13.3 BP-04/05 — предложить цену, ответить, согласовать

**Buyer submit:** verified global buyer identity; seller tenant определяется из published offer, quantity INTEGER и proposedUnitPrice BigDecimal в той же валюте, что offer; expiry/round limits. Создаётся proposal revision 1 со стороной, которая должна ответить, SELLER. Отправка не меняет stock, offer price или заказ. До подтверждения buyer identity разрешён только отдельный ограниченный pending intent, не seller-accepted quote.

**Seller answer:** после отдельного tenant-scoped SALES_PRICE_NEGOTIATE permission seller может ACCEPT текущей buyer revision, REJECT либо COUNTER своей ценой. COUNTER увеличивает revision и меняет ожидающую сторону на BUYER; не изменяет quantity/currency без создания нового proposal.

**Buyer answer:** buyer может ACCEPT последнее встречное предложение, REJECT либо COUNTER с новым revision. При ACCEPT любой стороной последнего предложения другой стороны создаётся ровно один AGREED / AcceptedQuote, связанный с agreedRevision. Нет двойных статусов вида sellerAccepted + buyerAccepted для одного раунда.

**Competition:** два ответа на один expectedRevision → ровно один CAS winner; expired proposal, suspended seller, withdrawn buyer или non-owner participant → fail closed. Никаких скрытых скидок другим покупателям.

### 13.4 BP-06 — checkout по согласованной цене

Buyer после подтверждения финальной суммы передаёт acceptedQuoteId, expectedRevision и idempotencyKey. Сервер проверяет verifiedBuyer == quote.buyer, seller tenant, опубликованность offer/product, текущий статус seller, expiry quote, exact agreed quantity/price/currency. **Не передавать произвольную клиентскую negotiatedPrice в старый AutoOrderService.checkout.**

В одной PostgreSQL-транзакции и совместимом с нынешними checkout locks порядке создать ровно один PLACED с priceSource=NEGOTIATED, acceptedQuoteId, immutable agreedUnitPrice, статусное событие и receipt; пометить quote CONSUMED с orderId. Оба изменения либо commit, либо rollback. Два concurrent checkouts одного quote создают один заказ. Изменение публичной цены после AGREED не меняет agreedUnitPrice, но скрытие/архивирование offer блокирует новый checkout согласно approved quote eligibility policy.

Согласованная цена не равна оплаченной выручке и не резервирует stock. Поздний отказ seller confirm по shortage допустим, но должен быть объяснён клиенту.

### 13.5 BP-07/08 — QR заказа, confirm и исторический склад

ORDER_REFERENCE создаётся после PLACED и позволяет только найти заказ после buyer ownership proof либо seller SALES_ORDER_READ. Постоянный ref не выступает credential выдачи; email challenge не включается в ref. Исторические отменённые заказы могут отображаться владельцу.

При первом CONFIRMED выбранный seller warehouse проверяется как ACTIVE WAREHOUSE того же tenant. EXISTING OrderInventoryPort.reserve резервирует все линии, после чего в той же транзакции фиксируется immutable warehouse origin: warehouseId, KATO, address, nullable WGS84 latitude/longitude и timestamp. Ошибка любой строки, чужой warehouse или закрытие склада откатывает полный reserve и status/snapshot. При idempotent retry тот же warehouse не обновляет исторические координаты.

### 13.6 BP-09/10 — подготовка и одноразовая выдача

Seller/warehouse actor переводит CONFIRMED→PREPARING→READY_FOR_PICKUP после проверки всех ACTIVE reservations и совпадения одного warehouse. READY не списывает on_hand и не снимает резерв.

После buyer identity proof выпускается краткоживущий PICKUP_CREDENTIAL, cryptographic random, purpose-bound, one active generation на order, только digest в базе, raw secret одноразово возвращается доверенному клиенту. Потеря телефона → revoke+reissue; expiry не снимает резерв.

Warehouse staff выбирает tenant и warehouse, сканирует proof, получает строго ограниченный preview (номер, позиции, qty, readiness). **Preview не погашает proof и не списывает stock.** После физической передачи staff отдельно подтверждает выдачу.

В одной транзакции: fresh IAM grants SALES_ORDER_FULFILL + INVENTORY_FULFILL, order row lock, credential lock, expiry/purpose/state/warehouse checks, READY, full ACTIVE reserves → all OrderInventoryPort.fulfill operations → immutable handover receipt → COMPLETED status event → consume proof → commit. Любой сбой откатывает всё. Повторный same-key request возвращает ранее успешный receipt, без второго stock movement. Offline списание R1 запрещено.

### 13.7 BP-11/12 — исключения, неявка, отзыв

Lost proof и delegated pickup не равны пересылке бессрочного QR заказа; нужна отдельная повторная проверка владения/получателя. Screenshot краткоживущего proof может быть скопирован — поэтому staff admission, single-use consume и физическая проверка обязательны. Для high-value товаров дополнительное подтверждение получателя — DECISION REQUIRED.

TTL QR получения не является TTL inventory reserve и не порождает автоматический CANCELLED. В текущем коде cancel запрещён из READY_FOR_PICKUP; для no-show release нужен отдельный согласованный переход с двойным permission, без автоматической подмены.

### 13.8 BP-13/14/15/16 — логистика, возвраты, услуги, инвентаризация

PACKAGE_LABEL, DELIVERY_HANDOFF, STOCKTAKE_ITEM и TRANSFER_BATCH в R2 служат поиску контекста, но не проводят stock без явной авторизованной команды. Доставка и самовывоз имеют независимые proofs; OUT_FOR_DELIVERY не равен COMPLETED.

RETURN_REFERENCE создаётся только по RMA после physical fulfill; scan открывает заявку. Inspection классифицирует SELLABLE, DAMAGED, QUARANTINED, WRONG_ITEM, MISSING; only accepted sellable qty приходит через новый typed INVENTORY RETURN_RECEIPT с original movement provenance. Original COMPLETED остаётся исторически неизменным; деньги возвращает отдельный будущий FINANCE process.

SERVICE QR карточка не даёт права вызвать PRODUCT inventory reservation. Service appointment, доступность исполнителя/слота, check-in, выполнение и отмена — отдельная R2-вертикаль. Материалы услуги остаются PRODUCT SKU со своим stock lifecycle.

## 14. Строгие state machines и переходы

### 14.1 Публичный QR и предложение цены

| Aggregate/from | Command, actor and guard | To / guaranteed effect |
|---|---|---|
| OfferQr ABSENT | issue seller, active published offer | ACTIVE generation 1 |
| OfferQr ACTIVE | reprint seller | ACTIVE, тот же исходный public_ref |
| OfferQr ACTIVE | revoke seller, expectedVersion CAS | REVOKED, ref отключён |
| OfferQr REVOKED | reissue seller, offer still PUBLISHED | Новая ACTIVE generation; old ref не восстановлен |
| OfferQr ACTIVE | offer paused/hidden | QR ref остаётся, public sellable content блокируется |
| Proposal ABSENT | submit verified buyer, valid offer/qty/price | AWAITING_SELLER revision=1 |
| Proposal AWAITING_SELLER | sellerCounter with exact revision | AWAITING_BUYER revision+1 |
| Proposal AWAITING_SELLER | sellerAccept exact buyer revision | AGREED + one AcceptedQuote |
| Proposal AWAITING_SELLER | sellerReject / buyerWithdraw | REJECTED / WITHDRAWN |
| Proposal AWAITING_BUYER | buyerCounter exact revision | AWAITING_SELLER revision+1 |
| Proposal AWAITING_BUYER | buyerAccept exact seller revision | AGREED + one AcceptedQuote |
| Proposal AWAITING_BUYER | buyerReject / approved sellerWithdraw | REJECTED / WITHDRAWN |
| Proposal waiting | server now >= expiresAt | EXPIRED; no quote |
| AcceptedQuote AVAILABLE | approved buyer checkout, not expired, eligible seller | CONSUMED + one PLACED |
| AcceptedQuote AVAILABLE | expires / seller invalidates | EXPIRED/INVALIDATED, no new order |
| Any terminal proposal/quote | mutate or resubmit same revision | REJECTED; new proposal required |

Обе стороны не могут принять собственную последнюю ревизию как согласование другой стороны. Proposal AGREED и Quote CONSUMED — не order confirmed/reserved/paid.

### 14.2 Физический order и RMA

| Order/from | Command and admission | To and stock effect |
|---|---|---|
| ABSENT | checkout standard/quote | PLACED, stock untouched |
| PLACED | confirm seller with dual grants and one warehouse | CONFIRMED, ACTIVE reservation for all lines, immutable origin |
| CONFIRMED | startPreparing staff | PREPARING, no stock debit |
| PREPARING | markReady staff with full reservations | READY_FOR_PICKUP, no stock debit |
| READY_FOR_PICKUP | issue/reissue buyer pickup credential | Order unchanged, token generation |
| READY_FOR_PICKUP | confirmPhysicalHandover staff dual grants + valid token | COMPLETED, fulfill all lines, unique receipt, token CONSUMED |
| PLACED/CONFIRMED | existing allowed cancel | CANCELLED, release active reservations as applicable |
| READY_FOR_PICKUP | noShowCancel | HOLD until separate policy; current cancel forbids it |
| CONFIRMED/PREPARING | existing legacy fulfill | Existing COMPLETED semantics; **not** proof buyer handover |
| COMPLETED | request return | Original stays COMPLETED, separate RMA |
| RMA REQUESTED | approve/deny seller | AUTHORIZED/REJECTED |
| RMA AUTHORIZED | physical receipt staff | RECEIVED, not sellable stock yet |
| RMA RECEIVED | inspection staff | INSPECTED with typed line-level quality |
| RMA INSPECTED | approved sellable restock | RESTOCKED with new typed stock receipt |
| RMA INSPECTED | damaged/unverified | QUARANTINED/REJECTED, no sellable increment |

Из существующего DDL READY_FOR_PICKUP допускается как значение, но current SalesInventoryCommandService.fulfill его не принимает, тогда как InventoryReservationService.fulfill принимает. Это **обязательный gap**, не косметическое переименование статуса. Для нового QR handover нужен отдельный orchestration path, сохраняющий старые regressions и lock order.

## 15. Конкретные service-only команды и DTO

| Домен / proposed class | Contract method | Command fields | Result / policy |
|---|---|---|---|
| STOREFRONT PublishedOfferQrService | issueOrGet / reprint / revoke | VerifiedActor, tenant, offerId, expectedVersion, requestKey | PublicQrRefDto; seller management right |
| STOREFRONT PublishedOfferCardQuery | findPublished | opaqueRef, locale, regionKato? | PublicOfferCardDto, anonymous publication allowlist |
| CATALOG PublishedCatalogDetailsPort | publishedDetails | sellerTenant, itemId, locale | Safe title/description/specifications |
| FILES CatalogItemPublicMediaPort | listApproved | sellerTenant, publishedItemId, page | PublicImageDto with approved CLEAN variants |
| INVENTORY RegionalAvailabilityPort | publicBand | sellerTenant, itemId, KATO | AvailabilityDto(asOf,band); no reserve |
| SALES BuyerPriceProposalService | submit / respond | verifiedBuyer, offerRef, qty, unitPrice, currency, revision, requestKey | ProposalDto, buyer-owned |
| SALES SellerPriceProposalService | accept / reject / counter | verifiedSeller, tenant, proposalId, expectedRevision, proposedUnitPrice? | ProposalDto / AcceptedQuoteDto |
| SALES AcceptedQuoteCheckoutService | checkoutQuote | verifiedBuyer, quoteId, buyerConfirmedRevision, idempotencyKey | OrderReceiptDto, one-time quote |
| SALES OrderQrLookupService | issueOrGet / readOwn | verified buyer or staff, order ref | OrderQrDto / SanitizedOrderDto |
| SALES PickupPreparationService | start / markReady | verifiedStaff, tenant, orderId, expectedOrderVersion, requestKey | OrderStateDto and event |
| SALES PickupCredentialService | issue / revoke | verifiedBuyer, orderRef, requestKey | PickupCredentialDto; raw token only at issue |
| SALES PickupHandoverService | preview / confirm | verifiedStaff, selectedTenant, selectedWarehouse, proof, explicitConfirm, requestKey | PickupPreviewDto / immutable HandoverReceiptDto |
| SALES ReturnRequestService | request / authorize / inspect | verified actor, originalOrder/line quantities, expectedRevision, quality | ReturnRequestDto; RMA source |
| INVENTORY InventoryReturnPort | acceptSellable | tenant, RMA line, original movement, qty, warehouse, approved actor | Typed RETURN_RECEIPT |
| ORGANIZATION LocationSnapshotPort | currentLockedWarehouse | tenant, locationId | Consistent KATO/address/GPS origin |

**DTO allowlists:** PublicOfferCardDto содержит title/description/locale/gallery, unitPrice BigDecimal, currency, unitOfMeasure, availabilityBand + asOf, region, published flags. Нельзя выводить internal warehouse balance, private file keys, IAM actor, private buyer negotiations. OrderStatusDto содержит sanitized state и immutable lines; PickupPreviewDto — ожидаемые quantity и warehouse label, без private PII; HandoverReceiptDto — receiptId/orderRef/warehouse/time/lineCount, без утверждения PAID или DELIVERED.

**Validation:** amount/quote totals только BigDecimal и единая currency, quantity в текущем checkout INTEGER 1..1000 включая merge повторных offer lines; разные currencies в одном заказе не смешивать. Locales kk/ru/en/zh-Hans, источник fallback явный. ExpectedVersion/revision CAS обязательны. RequestKey с canonical request fingerprint: тот же key + те же параметры → тот же результат, иначе IDEMPOTENCY_CONFLICT. Actor/tenant берутся из trusted server context, а не из данных QR/URL.

## 16. Schema-level design: owner, PK/FK, constraints, индексы

Все таблицы ниже **предлагаемые**; их ещё нет в production-qualified коде. Создавать только additive Liquibase changesets с проверкой занятых имён и populated upgrade. Existing sales_order, inv_stock_balance и их ownership не заменять.

| Table | Minimum columns | Mandatory relational rules |
|---|---|---|
| crm.store_offer_qr_ref | tenant_id,id,offer_id,**public_ref**,generation,state,issued_at,revoked_at,row_version | PK(tenant,id), FK(tenant,offer_id), global UNIQUE(public_ref), UNIQUE active tenant/offer |
| crm.sales_price_proposal | tenant_id,id,offer_id,buyer_user_id,quantity,current_unit_price,currency,state,revision,next_actor_role,expires_at,row_version | Composite tenant FK offer; verified buyer; quantity CHECK 1..1000; amount NUMERIC(19,4), >0 |
| crm.sales_price_proposal_event | tenant_id,id,proposal_id,revision,actor_role,action,unit_price,quantity,currency,occurred_at | Append-only; UNIQUE(tenant,proposal_id,revision); composite FK proposal |
| crm.sales_accepted_quote | tenant_id,id,proposal_id,agreed_revision,buyer_user_id,quantity,agreed_unit_price,currency,expires_at,state,consumed_order_id | UNIQUE(tenant,proposal,revision); one consumed order, owner/expiry checks |
| crm.sales_order_line extension | accepted_quote_id NULL, price_source NULL | Tenant-qualified FK; legacy NULL means UNKNOWN_LEGACY, do not rewrite historical amount |
| crm.sales_order_qr_reference | tenant_id,id,order_id,public_ref,issued_at,revoked_at,state | PK tenant+id, FK tenant+order, global UNIQUE(public_ref), stable non-secret reference |
| crm.sales_pickup_credential | tenant_id,id,order_id,warehouse_id,digest,issued_at,expires_at,consumed_at,revoked_at,state,generation,row_version | Tenant FKs, one active ISSUED/order, secret never plaintext |
| crm.sales_pickup_handover | tenant_id,id,order_id,warehouse_id,credential_id,staff_user_id,request_key,request_hash,happened_at,status_event_id | UNIQUE(tenant,order_id) R1; UNIQUE(tenant,request_key); immutable |
| crm.sales_return_request/line/event | tenant_id, original order, line qty, event/quality, version, return warehouse | Composite tenant FKs, original completed, cumulative received qty <= original fulfilled |
| public.org_location extension | latitude,longitude NUMERIC(9,6),coordinate_source,coordinates_updated_at,geo_verified_at | WGS84 range, paired-null CHECK, row_version and authorized update |
| crm.sales_order extension | fulfillment_warehouse_id, warehouse_kato/address/lat/lon_snapshot, captured_at | Tenant composite FK ORG; immutable after first CONFIRMED; nullable for legacy |

**Исправление прежнего контракта:** у постоянной публичной QR-ссылки и справочного ORDER_REFERENCE разрешено хранить исходный random public_ref, поскольку он **не является секретом доступа**. Прежнее предложение хранить только hash public alias конфликтовало с reprint. У pickup proof обратное правило: хранится только keyed digest, raw token не сохраняется. Reissue старого pickup proof создаёт новый secret, а не «восстанавливает» старый.

**GPS:** широта -90..90 и долгота -180..180; обе NULL или обе заполнены, coordinates_updated_at + source обязательны при наличии GPS. Нельзя брать центроид KATO за точный GPS. Старые локации и CONFIRMED без GPS остаются NULL/UNKNOWN_LEGACY, без фиктивного backfill.

**FILES:** публичное фото имеет owner CATALOG_ITEM + active published offer + AVAILABLE/CLEAN + approved MIME/variant; текущий FileAttachmentService проверяет CRM_CUSTOMER, не разрешает автоматически CATALOG_ITEM. No duplicate photo DB.

## 17. Security and IAM — матрица полномочий

| Операция | Authn/authz | Object policy |
|---|---|---|
| Public offer QR/card | Anonymous only by explicit publication allowlist | Published storefront/offer/product/files |
| Buyer PriceProposal | Verified buyer identity/email proof | Только собственные proposals |
| Seller counter/accept | Новая permission SALES_PRICE_NEGOTIATE (только после registry/role tests) | Proposal принадлежит выбранному seller tenant |
| Seller confirm | Existing SALES_ORDER_CONFIRM + INVENTORY_RESERVE | Active tenant WAREHOUSE, order ownership |
| Warehouse prepare | Explicit seller preparation grant, decision pending | Сотруднику разрешён выбранный warehouse |
| Warehouse handover | Existing SALES_ORDER_FULFILL + INVENTORY_FULFILL | Same tenant, same warehouse, READY, valid proof |
| Buyer order history | Verified buyer principal | buyer_user_id verified, QR alone insufficient |
| Seller order history | SALES_ORDER_READ | Tenant order, PII hidden without separate grant |
| Return inspection | New SALES_RETURN_* and typed inventory return permission | Original fulfillment and cumulative qty |
| SYSTEM jobs | Explicit scoped service identity | No fake global PLATFORM_ADMIN |

**DECISION REQUIRED:** отдельная warehouse-scoped staff object policy; текущий tenant grant не означает права каждого кладовщика на каждый склад. Для полноценной production выдачи разных складов отсутствие этой политики — HOLD. Новые права не добавляются просто ради названия роли, а должны иметь owner, registry, role grant и negative PostgreSQL tests.

**Threat controls:** QR token purpose separation, cryptographic random, short TTL for pickup, keyed digest, constant-time comparison where applicable, server clock expiry, one-time consume, rate limits, safe origin URL without arbitrary redirects, no raw tokens or PII in logs/analytics/audit, anti-enumeration for foreign orders. Скриншот proof может быть переслан; для дорогих товаров нужен явный recipient challenge (business decision). QR круг с галочкой — косметический, не подтверждение прав.

## 18. Транзакции и порядок блокировок — mandatory technical gate

**Existing checkout:** request stripe → IAM workspace → storefront → offer → catalog. Quote checkout должен соблюдать совместимый порядок. Нельзя брать quote lock, затем пытаться взять offer lock, если существует обратный путь и нет доказанного lock graph.

**Existing inventory:** seller order row FOR UPDATE → reservation request stripes and StockEligibility owner locks → stock balance → reservation. StockEligibility использует PostgreSQL FOR SHARE для ACTIVE warehouse и опубликованного SKU; изменение ORG location конфликтует с owner-row guard. Сохранить эту семантику.

**Atomic handover:**
1. Verified staff/tenant/warehouse и две действующие permissions до исполнения.
2. Найти order по purpose-bound proof, захватить order row lock.
3. Под ним lock credential, проверить digest, expiry, consumed/revoked и warehouseId.
4. READY + full ACTIVE reservations, снимок origin, все строки соответствуют складу, explicit staff confirmation.
5. Упорядоченно выполнить все OrderInventoryPort.fulfill; status COMPLETED, immutable receipt и CONSUMED proof записать в одной PostgreSQL транзакции.
6. Commit. Уведомление/печать после commit; отказ уведомления не повторяет складское списание.
7. Idempotent exact replay — тот же receipt; иной payload под прежним requestKey — конфликт.

**Обязательные гонки:** предложение цены accept×counter, quote checkout×checkout, offer pause×checkout, staff revoke×handover, GPS update×confirm, warehouse close×reserve, reissue×reissue, 2 scanners×handover, expiry between preview and confirm, RMA receipt×receipt. Все проверять двумя независимыми PG connections и фиксацией обоих порядков commit.

**Fail invariant:** при отказе остаются неизменными business order/status events, balances, reservation records/events, movements, consumed quote, handover receipt и credential state (кроме разрешённого отдельного security-attempt audit). Никакого частично списанного заказа, невалидного quote consumed и «COMPLETED без записи в ledger».

