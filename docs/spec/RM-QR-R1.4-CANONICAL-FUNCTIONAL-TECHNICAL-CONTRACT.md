# RM-QR-R1.4 — Canonical Functional & Technical Contract

**Версия:** DRAFT R1.4 / READY FOR ENGINEERING REVIEW, NOT IMPLEMENTED.
**Дата:** 2026-11-22. **Ветка:** base_dev. **Source inspection:** 65d1f8fc0983b646c9d6d1b354d4aa33f2ba1dd5 and reinspection of current base_dev before publication. **Production:** HOLD.
**Тип:** канонический согласовательный контракт для service-first разработки QR-коммерции; НЕ релизный отчёт.
**Scope:** STOREFRONT / CATALOG / FILES / GEO / ORGANIZATION / SALES / INVENTORY / IAM / AUDIT / INSIGHT. FINANCE / SERVICE / DELIVERIES — только ограниченная перспективная интеграция.
**Implementation prohibition:** никаких новых REST-контроллеров, публичных URL transports, дополнительных микросервисов, merge в main, новых branches и фиктивных доказательств выполнения тестов.

**Иерархия источников:**
1. RM-PLATFORM-STANDARD-R2 и проверенный на точном SHA код/DDL;
2. принятые действующие бизнес-инварианты SALES/INVENTORY;
3. этот R1.4 — единый источник реализации QR для новых feature slices;
4. прежние RM-QR-R1 / R1.3 и RM-COMMERCIAL-GEO-REGION-QR-FULFILLMENT-R1 — история решений и подробности; при расхождении с R1.4 обязателен QR-00 discrepancy record, нельзя молча имплементировать два противоречивых контракта.

## 1. Цели и продуктовая полнота

Единый QR в RM связывает физический/цифровой идентификатор с **допустимым контекстом действия**, но никогда не заменяет аутентификацию, правовую проверку или транзакционный бизнес-процесс. QR продукта: открыть карточку, фото, описание, локаль, характеристики, опубликованную цену, региональное наличие, количество, купить или предложить цену. QR заказа: посмотреть **свой** заказ. QR получения: показать одноразовый proof на складе и завершить передачу под контролем персонала. QR возврата: найти разрешённый RMA для осмотра и оприходования, а не сразу увеличить available.

**Верхние критерии качества:** неизменяемая история согласованных цен и выдач, отсутствие oversell, ноль двойных списаний/возвратов, ноль cross-tenant доступа и PII exposure, воспроизводимость аварийного восстановления, доступность печатного QR и доказуемые PostgreSQL инварианты. В R1 сохранить single seller/order, single warehouse/order и full-line reserve/fulfill; частичные выдачи и multiwarehouse — отдельно в R2.

**Не-цели R1:** QR как банковская оплата; универсальный QR wallet; offline acceptance; сквозные курьерские SLA; юридически значимый EDS; ввод SERVICE в stock ledger; новый слой каталога; автоматические refunds; ML-прайсинг; generic workflow engine; геомаршрутизация без подтверждённых координат и правил доставки.

## 2. Подтверждённый baseline и новая работа

| Код и owner | Что есть сейчас | Что именно нужно добавить |
|---|---|---|
| AutoOrderService.checkout | PUBLIC seller slug + email-first idempotent PLACED, price/server SKU snapshot, integer quantity 1..1000 and duplicate merge | QR alias→offer checkout coordinator; защита price acknowledgement по версии; не изменять текущую семантику заказа |
| AutoOrderService.verifyEmail | IAM email proof и post-verification buyer_user_id association | Buyer-only ownership for private QR order, proposals and pickup issue |
| StorefrontOfferService | Seller-authored offer; PUBLISHED/PAUSED/HIDDEN, version and price history | Public offer QR alias, read published card, revoke/rotate |
| CATALOG | PRODUCT/SERVICE и i18n текста | Только опубликованный PRODUCT для R1 commerce; характеристики/UOM отдельным контрактом |
| FILES | CRM_CUSTOMER attachment, scan/storage with owner access | Public CATALOG_ITEM media owner, safe variants and revocation |
| OrganizationLocationService | KATO/address/type/status, historical KATO edit guard | Nullable WGS84 coordinates + trusted update/audit |
| SalesInventoryCommandService.confirm | PLACED→CONFIRMED, full reserve at one ACTIVE WAREHOUSE | Immutable origin snapshot, same transaction and location owner lock |
| SalesInventoryCommandService.fulfill | CONFIRMED/PREPARING→COMPLETED, stock debit; no human receipt | New READY-only, token-redeeming handover; legacy fulfill separately labelled |
| SalesInventoryCommandService.cancel | READY_FOR_PICKUP forbidden | No-show/reschedule separate HOLD decision |
| InventoryReservationService | ACTIVE→FULFILLED/RELEASED; reserve on confirm, physical stock movement on fulfill | Reuse through OrderInventoryPort only, preserve uniqueness and locking |
| DomainPermission / TenantAuthorization | Tenant RBAC, actor/workspace locks, paired fulfill/confirm grants | Warehouse-specific staff scope and typed bargaining/return permissions after registry review |
| PostgreSQL DDL | sales_order has mandatory pending_email_identity_id, buyer_user_id nullable; store_offer server price; reserved balance numeric | AcceptedQuote checkout must supply legitimate IAM email identity and verified buyer; new fields/tables additive |

**Исходные коллизии, запрещённые к игнорированию:**
- Статус READY_FOR_PICKUP допустим в БД и INV.fulfill, но не в SALES.fulfill. Нельзя назвать QR выдачу реализованной посредством обновления enum.
- Email-first покупатель может создать PLACED ещё до подтверждения email; это не VerifiedBuyer и не JWT login. Нельзя показывать его приватные строки и выдавать pickup token на основании введённого email.
- crm.sales_order.pending_email_identity_id NOT NULL. Новый negotiated quote checkout обязан безопасно заполнить существующую IAM identity связь; один buyer_user_id не делает insert корректным.
- Existing checkout receipt unique (tenant_id,idempotency_key) используется seller-wide. Новый quote checkout и старый list checkout **не имеют права** независимо использовать тот же key с другой семантикой без одного общий transaction/key namespace и сравнения request fingerprints.
- Existing PRODUCT qty — integer; crm.inv_stock_balance.quantity NUMERIC(19,4). Это не разрешение принимать произвольное дробное quantity в checkout.
- Existing FileOwnerAccessPort не предоставляет публичную публикацию CATALOG_ITEM только потому, что FILES поддерживает attachment owner_type text.
- Публичный ref должен быть воспроизводим при reprint; hash-only без source alias не решает это требование.

## 3. Полный реестр назначений QR и приоритетов

| Purpose | Owner | Пользовательское действие | Evidence / effect | Stage |
|---|---|---|---|---|
| PRODUCT_OFFER | STOREFRONT | Открыть карточку, выбрать qty, купить/торговаться | Публичные данные, 0 stock effect | R1 |
| SELLER_STOREFRONT | STOREFRONT | Открыть ассортимент продавца | Published offers, pagination | R1.1 |
| ORDER_REFERENCE | SALES | Найти свой заказ и статус | Owner-only read, no debit | R1 |
| PICKUP_CREDENTIAL | SALES | Подтвердить передачу готового заказа | 1 receipt, 1 stock fulfillment | R1 |
| RETURN_REFERENCE | SALES | Найти запрос возврата | RMA lookup only | R1.2 |
| SERVICE_OFFER | STOREFRONT | Ознакомиться с услугой | Public catalog/service details, no stock | R2 |
| SERVICE_CHECKIN | SALES service | Отметить начало/завершение услуги | Appointment/proof, no SERVICE stock | R2 |
| PACKAGE_LABEL | SALES fulfillment | Идентифицировать коробку | Package identity, 0 automatic debit | R2 |
| DELIVERY_HANDOFF | SALES delivery | Передача курьеру/получателю | Distinct POD/delivery state | R2 |
| WAREHOUSE_LOCATION | ORGANIZATION/INVENTORY | Найти склад/ячейку | Location context only | R2 |
| STOCKTAKE_ITEM | INVENTORY | Выбрать SKU для пересчёта | No adjustment without explicit command | R2 |
| TRANSFER_BATCH | INVENTORY | Найти межскладское движение | No transfer without approved posting | R2 |

**Типы QR нельзя взаимозаменять:** ORDER_REFERENCE не является PICKUP_CREDENTIAL; RETURN_REFERENCE не оприходует товар; PACKAGE_LABEL и STOCKTAKE_ITEM не предоставляют товарно-финансовые права. Все future purposes должны быть явно зарегистрированы; неизвестные purpose → DENY.

## 4. Обязательные бизнес-правила BR-001…BR-050

| ID | Требование, обязательное для owning domain |
|---|---|
| BR-001 | QR товара ссылается на конкретную seller store_offer, не на голый SKU |
| BR-002 | Только PUBLISHED store + offer + CATALOG PRODUCT допускают публичную покупку |
| BR-003 | Публичная карточка доступна без входа; личные сделки/заказы — после buyer proof |
| BR-004 | Цена/валюта с сервера; браузерная цена не authoritative |
| BR-005 | Изменение цены/фотографий не меняет стабильный публичный QR |
| BR-006 | Отзыв/ротация QR не переписывает ранее созданные заказы |
| BR-007 | Публичные FILES показываются лишь после owner publication + CLEAN/AVAILABLE/approved variant |
| BR-008 | Private original и EXIF/location не публиковать |
| BR-009 | Продукт может иметь безопасный placeholder при отсутствии фото |
| BR-010 | Locale kk/ru/en/zh-Hans, fallback к source_locale обозначается |
| BR-011 | R1 quantity — integer 1..1000 per merged offer line |
| BR-012 | Unit of measure не подменяет правила integer quantity |
| BR-013 | Видимое наличие — indicative, не заказанный резерв |
| BR-014 | Поиск KATO exact match до подтверждения complete hierarchy |
| BR-015 | Разные продавцы и SKU не суммируются в публичное наличие |
| BR-016 | Наличие по региону ≠ обещание доставки |
| BR-017 | BUY_AT_LIST_PRICE использует price acknowledgement / version contract, не silent repricing |
| BR-018 | Email-first PLACED не доказывает завершённую идентификацию покупателя |
| BR-019 | Повторный checkout same tenant+key+fingerprint создаёт максимум один заказ |
| BR-020 | Цена клиента после торга не изменяет STOREFRONT public price |
| BR-021 | Proposal фиксирует buyer, seller offer, qty, unit price, currency, expiry, revision |
| BR-022 | Ответить на последнюю ревизию может только противоположная сторона |
| BR-023 | Согласованная цена не создаёт автоматически заказ |
| BR-024 | AcceptedQuote может быть consumed не более одного раза |
| BR-025 | Negotiated order содержит immutable quote source и accepted agreed price |
| BR-026 | AcceptedQuote checkout соблюдает pending_email_identity_id и buyer ownership |
| BR-027 | Никакой reservation при PLACED или торге |
| BR-028 | CONFIRMED резервирует full lines на одном tenant ACTIVE WAREHOUSE |
| BR-029 | warehouse origin KATO/address/GPS фиксируется только при первом CONFIRMED |
| BR-030 | Исторический origin никогда не перезаписывается после редактирования склада |
| BR-031 | Старым CONFIRMED без origin не присваивать выдуманные координаты |
| BR-032 | PREPARING и READY не изменяют on_hand и не списывают reserved |
| BR-033 | READY разрешён только при всех полных ACTIVE reservations на выбранном складе |
| BR-034 | ORDER_REFERENCE допускает лишь authenticated owner/staff lookup |
| BR-035 | PICKUP_CREDENTIAL выпускается только verified buyer/delegate при READY |
| BR-036 | Raw pickup secret нигде не сохраняется и не логируется |
| BR-037 | Expired/revoked/consumed proof нельзя выполнить |
| BR-038 | Сканирование/preview не потребляют credential и не списывают товар |
| BR-039 | Handover требует свежих IAM grants, warehouse scope и explicit staff confirmation |
| BR-040 | Один successful handover = один receipt, один COMPLETED и один set stock movements |
| BR-041 | Failed handover откатывает весь cross-domain transaction |
| BR-042 | Два concurrent scans не создают второе списание |
| BR-043 | Истечение QR не отменяет заказ и не освобождает резерв |
| BR-044 | No-show из READY остаётся отдельным неутверждённым workflow |
| BR-045 | После handover RMA создаётся отдельно, original COMPLETED не изменяется |
| BR-046 | Return qty ≤ ранее выданному minus уже принятому возврату |
| BR-047 | Возврат без inspection не увеличивает sellable on_hand |
| BR-048 | Повреждённое/карантинное не создаёт доступный для продажи stock |
| BR-049 | SERVICE не отправлять в PRODUCT inventory reserve/fulfill |
| BR-050 | QR payment/refund/courier delivery нельзя считать доказанными из COMPLETED склада |

## 5. Use-case cards с предусловиями, потоками и постусловиями

### UC-QR-01 Seller QR publication and reprint

**Actor:** verified seller staff, tenant and permission STOREFRONT_OFFER_MANAGE (publication uses STOREFRONT_OFFER_PUBLISH). **Given:** опубликованный store/offer и PRODUCT SKU. **Action:** issueOrGetQr(tenant,offer,expectedOfferVersion,requestKey). **Effects:** owner-scoped unique ACTIVE public_ref, issuer audit and generation. **Repeat:** reprint returns the identical literal ref, without rewriting order, SKU or price. **Revoke/reissue:** first revoke ACTIVE, then issue new generation with new ref; old ref never resurrected. **Failures:** foreign tenant, unpublished, suspended workspace, stale version and collisions → no public alias leak.

### UC-QR-02 Public card resolution

**Actor:** anonymous browser via future public adapter, R1 currently Java read port. **Given:** issued public_ref. **Steps:** resolve globally unique alias → verify current ACTIVE alias and current PUBLISHED storefront/offer/PRODUCT → localized description/specs → approved FILES gallery → optional stock band by exact KATO. **Result:** allowlisted PublicOfferCard. **No side effects:** no order/reserve/stock, no buyer identity creation merely from scan. **Error:** broken/hidden/archived QR returns generic unavailable; media scan failure degrades to safe placeholder, not PRIVATE bytes.

### UC-QR-03 List-price checkout

**Actor:** email-first buyer. **Given:** public card and integer quantity (merged duplicate offers validated), accepted price version and seller conditions. **Steps:** server resolves exact offer and product; if version/price differ show PRICE_RECONFIRM_REQUIRED, else reuse existing checkout semantic engine (email-first pending user, single tenant, price snapshots, status event/receipt). **Result:** one PLACED and stable idempotency receipt, no stock reservation; ORDER_REFERENCE issued safely via internal port or controlled post-commit issuer. **Notes:** actual current AutoOrderService does not accept price revision: adding that input is a target modification, not already implemented. Existing non-QR checkout remains backward-compatible.

### UC-QR-04 PriceProposal and counteroffer

**Actor:** verified global buyer and authorized seller staff. **Input:** offer, seller from offer, qty integer, offeredUnitPrice BigDecimal, currency, expiry, idempotency. **Steps:** buyer submits revision 1; seller accepts/rejects/counters; buyer accepts/rejects/counters; each revision immutable and CAS; expiry based on server clock. **Result:** AGREED with AcceptedQuote only when the other side accepts latest revision. **Forbidden:** seller accepts its own counter; buyer accepts its own original; one side silently changes qty/currency; creating a stock reservation or modifying public price.

### UC-QR-05 AcceptedQuote checkout

**Actor:** verified original buyer. **Given:** unexpired unique AcceptedQuote and exact final terms. **Steps:** obtain checkout namespace lock; revalidate seller/workspace/storefront/offer/product; assert buyer; preserve IAM verified current email identity and provide non-null pending_email_identity_id; lock quote in consistent DAG; insert PLACED, order line with agreed price, status event, shared seller checkout receipt; consume quote with orderId in same transaction. **Result:** exactly one order; no reservation yet. **Parallel:** two devices cannot each consume; same key/fingerprint returns previous receipt; cross-mode reuse of key must conflict or be separately namespaced in the single seller-wide receipt registry.

### UC-QR-06 Order reference and buyer history

**Actor:** verified buyer or authenticated seller with SALES_ORDER_READ. **Given:** stable public order ref. **Steps:** find record after identity admission; check buyer_user_id/global IAM current verified email or seller tenant-owned order. **Result:** status timeline, lines and accepted price snapshots with PII redacted by scope; separate pickup credential not included. **Legacy:** email-first order without yet verified buyer remains inaccessible to anonymous ref holder. No ability to infer existence of another tenant's order.

### UC-QR-07 Confirm warehouse and origin snapshot

**Actor:** authorized seller, with SALES_ORDER_CONFIRM and INVENTORY_RESERVE. **Given:** PLACED and one active tenant WAREHOUSE with sufficient inventory for every line. **Steps:** existing order lock, reserve each full line; read warehouse owner metadata under the same compatible lock; store warehouseId, katoCode, address and latitude/longitude pair as immutable origin; emit CONFIRMED and commit. **Result:** ACTIVE reservations, untouched on_hand, reserved incremented, fully consistent snapshot. **Race:** ORG GPS update vs confirm serializes, never mixed owner revisions. **Legacy:** unknown origin remains explicitly unknown, never backfill from current address.

### UC-QR-08 Prepare and issue pickup

**Actor:** warehouse staff with assigned warehouse object scope (not just tenant role). **Given:** CONFIRMED, all lines fully ACTIVE at order origin. **Steps:** startPreparing → PREPARING; verify full pick and markReady → READY_FOR_PICKUP; buyer after verified ownership issues one-time credential. **Result:** on_hand/reserved unchanged by READY or token issuance, one current credential generation. **Failures:** incomplete reserves, wrong staff/warehouse, expired buyer session or seller suspended → deny and preserve stock. Reissue revokes prior token and returns new raw secret once.

### UC-QR-09 Staff scan, preview and physical handover

**Actor:** verified staff, selectedTenant and selectedWarehouse; buyer shows PICKUP_CREDENTIAL. **Steps:** scan validates purpose and scope; restricted preview without mutation; staff physically verifies all order quantities and explicitly confirms. **Atomicity:** fresh paired grants; order row FOR UPDATE; credential row lock; server-time expiry; immutable origin match and full ACTIVE reserves; OrderInventoryPort.fulfill all lines; immutable handover receipt and COMPLETED status event; credential CONSUMED; one transaction. **Outcomes:** success exactly once, identical authorized retry reads receipt, conflict/expired/foreign denies with zero stock side effects. **Network:** online-only; if response lost, lookup authoritative receipt before any attempt. **No-show:** expiry of token does not cancel reservation.

### UC-QR-10 Return and inspection

**Actor:** verified buyer initiates RMA; permitted return staff authorize, receive and inspect. **Given:** originally successful fulfillment, line qty ≤ fulfilled − already accepted. **Steps:** RMA REQUESTED → AUTHORIZED → RECEIVED → INSPECTED; classify each line SELLABLE/DAMAGED/QUARANTINE/WRONG_ITEM/MISSING; typed inventory RETURN_RECEIPT only for accepted SELLABLE qty; reject or quarantine independently. **Result:** original order stays COMPLETED, one receipt per RMA-line application, no double physical return; no payment refund implied.

### UC-QR-11 Delivery, packages and services (future typed boundaries)

PACKAGE_LABEL identifies parcel or fulfillment group, not automatically reserve/debit; delivery proof distinct from pickup. SERVICE_OFFER can show description, photos and territory; SERVICE_CHECKIN and service completion require appointment/provider/time-slot state and no SERVICE stock. Product materials attached to service need separate PRODUCT lines. A future multiwarehouse/multiparcel feature must supersede R1 UNIQUE full-order handover with explicit fulfillment groups, not silently drop uniqueness.

### UC-QR-12 Warehouse location and stocktake (future typed boundaries)

WAREHOUSE_LOCATION and STOCKTAKE_ITEM allow authenticated staff to select existing location/stocktake/SKU; actual adjust/transfer must use current INVENTORY authorization, locked posting and ledger. No scan-triggered direct stock balance update. Bin-level coordinates or warehouse floor plans are separate internal geography from global WGS84.

## 6. Unified state machines: explicit admissible transitions

### 6.1 Published offer and public QR

| Aggregate | From | Event | Guard | To |
|---|---|---|---|---|
| Seller offer | DRAFT | publish | seller grant, published PRODUCT | PUBLISHED |
| Seller offer | PUBLISHED | pause/hide | current owner rights and version | PAUSED/HIDDEN |
| Seller offer | PAUSED/HIDDEN | publish | current seller + SKU, CAS | PUBLISHED |
| Offer QR | ABSENT | issue | current published offer | ACTIVE generation1 |
| Offer QR | ACTIVE | reprint | owner+ref stable | ACTIVE unchanged |
| Offer QR | ACTIVE | revoke | owner + expectedVersion | REVOKED |
| Offer QR | REVOKED | reissue | owner + published offer | new ACTIVE generation |
| Offer QR | ACTIVE | seller pause | derived current publication | Ref remains, card sellability false |

### 6.2 Negotiation

**Proposal states:** AWAITING_SELLER, AWAITING_BUYER, AGREED, REJECTED, WITHDRAWN, EXPIRED. **Quote states:** AVAILABLE, CONSUMED, EXPIRED, INVALIDATED.

| From | Event | Allowed actor | Guard | To |
|---|---|---|---|---|
| ABSENT | submit | verified buyer | published offer + valid qty/currency/price | AWAITING_SELLER revision1 |
| AWAITING_SELLER | counter | seller negotiator | exact expectedRevision, TTL, round limit | AWAITING_BUYER rev+1 |
| AWAITING_SELLER | accept | seller negotiator | last offer by buyer | AGREED + quote AVAILABLE |
| AWAITING_SELLER | reject | seller negotiator | exact revision | REJECTED |
| AWAITING_SELLER | withdraw | original buyer | not terminal | WITHDRAWN |
| AWAITING_BUYER | counter | original buyer | exact revision, within limits | AWAITING_SELLER rev+1 |
| AWAITING_BUYER | accept | original buyer | last counter by seller | AGREED + quote AVAILABLE |
| AWAITING_BUYER | reject | original buyer | exact revision | REJECTED |
| AWAITING_BUYER | withdraw | seller who sent counter | seller cancellation policy | WITHDRAWN |
| ANY awaiting | expire | server clock | now ≥ expiresAt | EXPIRED effective terminal |
| AVAILABLE quote | checkout | verified buyer | buyer+seller+offer+expiry+idempotency | CONSUMED and PLACED |
| AVAILABLE quote | expiry/invalidate | server policy | no consumed order | EXPIRED/INVALIDATED |
| CONSUMED | any new checkout | anyone | forbidden | never second PLACED |

One proposal references **one offer and fixed quantity** in R1; mixed list/negotiated cart, bulk bargaining and multi-seller orders are deferred to a separate order-price aggregation model.

### 6.3 Physical order / stock / pickup

| Order from | Event | Guard | Order to | Stock |
|---|---|---|---|---|
| ABSENT | checkout | buyer accepted server price | PLACED | untouched |
| PLACED | seller confirm | same tenant warehouse; full stock | CONFIRMED | reserve all lines |
| CONFIRMED | startPreparing | full ACTIVE reserves, warehouse scope | PREPARING | unchanged |
| PREPARING | markReady | full pick validation | READY_FOR_PICKUP | unchanged |
| READY_FOR_PICKUP | issue token | verified buyer | READY_FOR_PICKUP | unchanged |
| READY_FOR_PICKUP | handover | valid token, staff grants, same warehouse, all ACTIVE | COMPLETED | fulfill each line once |
| PLACED/CONFIRMED | cancel | existing cancel guard | CANCELLED | release if active |
| READY_FOR_PICKUP | noShowCancel | NOT IMPLEMENTED, separate approval | HOLD | unchanged |
| CONFIRMED/PREPARING | legacy fulfill | existing authorized stock process | COMPLETED legacy | existing stock debit |
| COMPLETED | return request | original handed over | COMPLETED + separate RMA | no immediate increment |

Credential ISSUED → CONSUMED/REVOKED/effective EXPIRED; these are terminal. Stale ISSUED DB state after expiredAt does not authorize consume. Original order remains COMPLETED through RMA lifecycle.

## 7. Typed Java service and DTO contracts — no controllers

| Owning package | Command/query name | Typed input contract | Result / important invariant |
|---|---|---|---|
| storefront | issueOfferQr / reprintOfferQr / revokeOfferQr | VerifiedActor, sellerTenant, offerId, expectedVersion, requestKey | Stable PublicQrRef, no duplicate generation |
| storefront | getPublicOfferCard | publicRef, locale, exactKato? | Sanitized PublicOfferCardDto, publication checks |
| catalog | publishedDetails | sellerTenant, itemId, locale | LocalizedProductDto, no unpublished text |
| files | approvedPublicProductMedia | tenant,itemId,offerId,page | PublicImageDto list, CLEAN sanitized only |
| inventory | regionalStockBand | sellerTenant,itemId,KATO | StockBand + asOf, live advisory only |
| sales | submitPriceProposal | VerifiedBuyer, publicOfferRef,qty,unitPrice,currency,expiry,key | ProposalDto, no order/reserve |
| sales | sellerRespondToProposal | VerifiedActor,tenant,proposalId,revision,action,optionalPrice,key | ProposalDto or AcceptedQuoteDto; CAS |
| sales | buyerRespondToProposal | VerifiedBuyer,proposalId,revision,action,optionalPrice,key | ProposalDto or AcceptedQuoteDto; ownership |
| sales | checkoutAcceptedQuote | VerifiedBuyer,quoteId,acceptedRevision,priceAck,key | CheckoutReceipt, once-only quote |
| sales | issueOrGetOrderReference | Trusted source orderId,tenant | PublicOrderReferenceDto, no access grant |
| sales | readOwnOrderByReference | VerifiedBuyer or VerifiedActor + ref | SanitizedOrderDto, scoped |
| sales | startPreparing | VerifiedActor,tenant,warehouse,orderId,expectedVersion,key | OrderTransitionReceipt, no stock mutation |
| sales | markReadyForPickup | VerifiedActor,tenant,warehouse,orderId,expectedVersion,key | READY status event, no stock mutation |
| sales | issuePickupCredential | VerifiedBuyer,ownedOrderRef,requestKey | Raw proof once + expiry, digest-only DB |
| sales | pickupPreview | VerifiedActor,tenant,warehouse,presentedProof | PreviewDto, read-only, no consume |
| sales | completePhysicalHandover | VerifiedActor,tenant,warehouse,presentedProof,explicitConfirm,orderVersion,key | HandoverReceiptDto, one atomic fulfillment |
| sales | openRma / authorizeRma / inspectRma | Verified buyer/staff,order+lines+qty+quality+revision+key | Typed RMA DTO; original order unchanged |
| inventory | acceptSellableReturn | Typed approved RMA receipt and actor permits | Immutable RETURN_RECEIPT, no duplicate posting |

**DTO exact semantics:**
- PublicOfferCardDto: stable public offerRef, seller display/slug, title, description and sourceLocale, approved images (opaqueSafeMediaRef, MIME, thumbnail/alt/sort), UNIT/UNKNOWN, BigDecimal publishedUnitPrice, ISO currency, catalogState, offerState, availabilityBand, KATO, asOf, priceRevision and booleans canOrder/canNegotiate. No internal stock, raw storage key, personal name, buyerId or warehouse exact GPS by default.
- ProposalDto: proposal reference, qty, listPriceAtSubmit, currentUnitPrice, currency, state, nextActor, expectedRevision, deadline, bounded event history. Buyer views only their proposals; seller views only seller tenant and approved staff scope.
- AcceptedQuoteDto: quoteId, offerId, buyer proof reference, exact qty+unitPrice+currency, expiresAt, revision. This is **not** public bearer coupon.
- OrderReferenceDto: opaque stable reference; public raw ref is not pickup token.
- OrderStatusDto: buyer-owned order line snapshot/status timestamps and permitted warehouse pickup info; never auto expose buyer PII from known ref.
- PickupCredentialDto: raw secret only on initial issuance, expiresAt, purpose, public-facing rendering info. Never return same token from a normal read.
- PickupPreviewDto: sanitized order reference, warehouse label, lineCount/quantities, status, canHandover, lastCheckedAt, not raw secret.
- HandoverReceiptDto: immutable receiptId, orderRef, warehouseRef, timestamp, staff record reference, each line fulfilled and immutable status event. No payment, invoice or courier POD claim.
- ReturnInspectionDto: original fulfilled qty, earlier accepted qty, inspected qty, sellable/damaged/quarantined qty and operator disposition, with provenance.

**Canonical amount policy:** existing SALES qty integer 1..1000 per merged offer line; stock quantity numeric. Money BigDecimal NUMERIC(19,4) and currency grouping; no implicit conversion or double math. Overflow/scale >4 is typed validation error. Zero-public-price offers may exist in current STOREFRONT; proposed negotiated offeredUnitPrice >0 does **not** silently change current public sale rule. Price lower-bound/rounding seller policy DECISION REQUIRED.

## 8. SQL schema contract: complete ownership and constraints

All fields are proposals, not applied migrations. Use **new additive Liquibase changesets**; do not rewrite historical changesets. Foreign keys for tenant-owned entities must include tenant ID, with declared UNIQUE/PK on referenced tuple. Global IAM buyer references are legitimately globally keyed.

### 8.1 STOREFRONT QR references

Table crm.store_offer_qr_ref: tenant_id UUID NOT NULL, id UUID NOT NULL, offer_id UUID NOT NULL, public_ref VARCHAR(64) NOT NULL, generation INT >=1, state ACTIVE/REVOKED, row_version BIGINT, issued_at TIMESTAMPTZ, revoked_at, issued_by. PK(tenant_id,id), FK(tenant_id,offer_id) → store_offer(tenant_id,id), global UNIQUE(public_ref), partial UNIQUE(tenant_id,offer_id) WHERE state='ACTIVE'. Reprint reads original ref. Reissue revokes previous generation then inserts new ACTIVE in same transaction. Do not store public_ref as an unrecoverable digest if exact reprint is a requirement.

### 8.2 SALES negotiation

Table crm.sales_price_proposal: composite PK(tenant_id,id), offer_id & catalog_item_id tenant-qualified FKs, verified buyer_user_id global IAM FK, quantity INTEGER CHECK 1..1000, currency CHAR(3), list_price_at_submit and current_unit_price NUMERIC(19,4), state enum-like CHECK, revision BIGINT, next_actor, round_count, expires_at TIMESTAMPTZ, created/updated_at, row_version. Do not duplicate plaintext email.

Table crm.sales_price_proposal_event: composite tenant key, proposal_id tenant FK, revision, event_type, actor_role, actor_id, unitPrice, qty, currency, occurred_at; UNIQUE(tenant_id,proposal_id,revision). **Append-only source event** and audit policy, not only mutable last price.

Table crm.sales_accepted_quote: composite PK; tenant+proposal+agreed revision UNIQUE; offer and buyer binding; qty, agreed_unit_price, currency, expires_at, status AVAILABLE/CONSUMED/EXPIRED/INVALIDATED, consumed_order_id nullable tenant-qualified order FK and unique non-NULL. Quote checkout locks row and preserves exact one order, even across retries and multiple devices.

Extend crm.sales_order_line: accepted_quote_id UUID NULL with composite FK, price_source LIST/NEGOTIATED nullable for legacy. Do not backfill historical negotiated price or replace immutable SKU snapshot. R1 negotiated checkout creates a **single-line** order, avoiding mixed basket until a separate multi-line quote design.

### 8.3 SALES order QR/pickup

Table crm.sales_order_qr_reference: tenant_id,id,order_id, public_ref global UNIQUE, generation,state, issued_at,revoked_at, row_version; tenant FK order; public ref stable and non-secret. No generic anonymous access to order lines.

Table crm.sales_pickup_credential: tenant_id,id,order_id, warehouse_id, credential_digest BYTEA, key_generation, issued_at,expires_at, consumed_at,revoked_at, state ISSUED/CONSUMED/REVOKED, row_version; tenant FKs; global or tenant-scoped UNIQUE digest; partial UNIQUE(tenant_id,order_id) WHERE state='ISSUED'. Effective expiry derives from server clock. During reissue first revoke old ISSUED, then insert new. Raw proof is NEVER stored; if issuance response is lost, verified buyer requests a new generation.

Table crm.sales_pickup_handover: tenant_id,id,order_id,warehouse_id,credential_id, staff_actor_id,request_key,request_sha256, happened_at, status_event_id, immutable line proof; composite tenant FKs, UNIQUE(tenant_id,order_id) for full-order pickup, UNIQUE(tenant_id,request_key). Every successful receipt has a matching completed sales status event and fully FULFILLED reservations.

**Important receipt namespace:** existing crm.sales_checkout_receipt has UNIQUE(tenant_id,idempotency_key) shared by list-price checkout. AcceptedQuote checkout must use the **same collision domain**, not a second table with the same key but ambiguous results. Introduce operation type into normalized request hash, and either explicit non-overlapping keyed prefix or a unified receipt admission facade. The server must **not** silently map the same key to two different purchase modes. Keep current checkout exact replay semantics and regression suite intact.

### 8.4 GEO origin and future destination

Extend public.org_location: nullable latitude NUMERIC(9,6) CHECK -90..90, longitude NUMERIC(9,6) CHECK -180..180; paired-null CHECK; coordinate_source allowed MANUAL/GEOCODED/IMPORTED, coordinates_updated_at and geo_verified_at. No fake centroid from KATO. Location changes remain authorized and row-versioned. Restrict move of an active warehouse across KATO with stock/history according to existing guard.

Extend crm.sales_order with nullable fulfillment_warehouse_id UUID tenant-qualified FK public.org_location(workspace_id,id); warehouse_kato_snapshot,warehouse_address_snapshot,warehouse_latitude_snapshot,warehouse_longitude_snapshot,warehouse_snapshot_captured_at. New CONFIRMED captures one consistent revision in the same transaction as reserve. Legacy orders without snapshot show UNKNOWN_LEGACY; DB CHECK tightened only after populated migration and verified historic classification.

Separate future buyer destination snapshot and delivery zones; origin is not destination. Public exact warehouse GPS may be withheld even if pickup location is known, according to a later approved visibility policy.

### 8.5 FILES and RMA

FILES existing file_object/file_storage/file_attachment stays authoritative. Add typed CATALOG_ITEM owner publication path; PUBLIC safe derivative requires CLEAN+AVAILABLE+approved image MIME/variant, private original denied, owner linked to current published offer. Do not reuse CRM_CUSTOMER attachment as public. No new binary storage in SALES.

Future SALES RMA: sales_return_request + sales_return_line + immutable return events, with tenant-qualified original order and fulfilled line/movement provenance. New INVENTORY RETURN_RECEIPT reference uniquely bound to tenant+RMA line+accepted portion; accepted quantity cumulative <= original fulfilled qty. Damaged/uninspected/quarantine not sellable, separate controlled quarantine model before implementing stock return.

### 8.6 Data migration hazards

1. Existing pending_email_identity_id NOT NULL — negotiated buyer checkout cannot omit; use authoritative verified current IAM email identity, preserve buyer_user_id association and privacy semantics. Do not insert fake identity or set NULL.
2. Existing completed orders lack handover receipt — label LEGACY_FULFILLED, not QR_HANDOVER_CONFIRMED.
3. Existing confirmed orders may lack origin GPS — no retrospective inference from today's warehouse coordinates.
4. Existing offer and product may be PUBLISHED/PAUSED/HIDDEN on exact current DDL; query current code rather than assuming old initial changeset enumerates all states.
5. Existing public attachment owner semantics and encrypted FILES requirements cannot be bypassed by simple file_attachment owner_type text.
6. Add indexes only with real query plans; validate partial unique, FK, external public_ref collisions, row lock duration and rollback on populated PostgreSQL.
7. New migrations cannot silently make old checkout or existing inventory movements depend on QR service availability.

## 9. Concurrency DAG and transactional linearization points

**QR-00 mandatory prerequisite:** generate the actual directed graph of existing locks at the current code SHA. The following are *validated source-derived constraints*, not license to guess a new order.

Existing list checkout: checkout request stripe → IAM workspace row → store_front → store_offer → catalog item → IAM email identity stripe/provision. Existing seller commands: IAM actor row → workspace row → storefront/offer/product. Existing sales inventory: IAM dual grants → sales_order row FOR UPDATE → warehouse/item eligibility (FOR SHARE in PG) → balance row FOR UPDATE → reservation state. Creating reverse acquisitions is prohibited without full lock graph and deterministic PG proof.

**PROPOSED quote checkout lock graph:** shared checkout stripe → seller workspace/storefront/offer/catalog → accepted quote row → buyer IAM email association; any seller counter or revoke path that locks proposal then offer must be refactored to be acyclic. Confirm exact order only after reviewing dependency from price update, verifyEmail and suspend. Do not assert one globally safe order solely from this document.

**QR alias issue:** lock seller storefront+offer in approved existing order, then unique active alias insert; concurrent retries encounter unique and re-read same ACTIVE. If proof/lease failure, issuer event rolls back with alias and no orphan public reference.

**Warehouse snapshot:** confirm transaction locks order and reserves full lines with location owner FOR SHARE; snapshot read must be from the SAME transaction/owner revision. GPS edit via OrganizationLocationService updates owner row and conflicts with the SHARE lock. No second connection or after-commit origin update.

**Pickup reissue linearization:** lock order → previous credential row(s) → revoke ISSUED → issue new digest generation → commit; at most one active generation by DB constraint. If the response gets lost after commit, raw token cannot be replayed; verified recovery issues fresh token. A rejected issue must not revoke an active credential.

**Pickup complete linearization:** admission paired IAM grants with actor/workspace row fence; order FOR UPDATE → credential lock → authoritative expiry/purpose/state/warehouse and READY/full reservations check → all InventoryPort fulfill → insert immutable handover → COMPLETED status event → token CONSUMED → commit. All stored events/balances/receipts in one PG transaction. If multiple lines fail at line N, all N−1 prior movement writes roll back. Exactly-once effect is a DB guarantee, NOT exactly-once network delivery.

**Expiry semantics:** validate with authoritative PostgreSQL/server current time under the credential lock immediately before stock mutation. This is the decision's time, not the initial preview; do not claim physical commit is guaranteed before expiresAt. If check is already expired, reject with no stock change.

**Incident nuance:** physical handover can occur despite network failure before a successful DB commit; users must not be told it succeeded unless receipt exists. Repair via authorized incident workflow with evidence and supervisor approval, not direct manual DB UPDATE or unsupported offline queue.

## 10. Security controls and trust boundaries

| Threat | Guard | Proof |
|---|---|---|
| QR copied to unrelated external site | Canonical approved RM HTTPS host + scanner warnings; no open redirects | Encoded URLs pass allowlist/domain check |
| Guess public QR aliases | Random 128+ bit, globally unique, rate limiting | No private read from guessed/foreign ID |
| Stolen order ref | Buyer identity proof + ownership or seller permission | No buyer/order PII leak |
| Stolen pickup screenshot | Short TTL, one-time consume, staff grants/warehouse match, high-value recipient check pending | Duplicate scan has zero new stock movement |
| Swap purpose from ORDER to PICKUP | Typed payload and digest purpose binding | Purpose mismatch DENY |
| Pickup DB leak | Raw secret never stored; digest keyed, rotate keys safely | Inspect DB/logs to verify absence |
| Cross-tenant offer/warehouse | Tenant-qualified SQL and FKs; independent staff grants | A/B/dual-member negative PG |
| Seller role revoked during accept | IAM command fence and CAS | Both commit orders tested |
| Buyer association guessed from email | Verify IAM email identity/OIDC principal, not typed email | Foreign buyer cannot access proposal/order |
| Private file in public card | CATALOG_ITEM owner+publication+CLEAN+safe derivative | CRM_CUSTOMER/foreign file denied |
| Stale region availability | Live stock on confirm under locks | Search may be stale, no oversell |
| Underflow/duplicate fulfillment | UNIQUE handover receipt and inventory ledger transaction | Exactly one stock movement per line |
| Token/email in traces and metrics | Redact; no high-cardinality private labels | Static/runtime log assertions |

Warehouse-scoped staff assignment is **not** covered by current tenant RBAC; real multiwarehouse operator use must remain blocked until object-scope policy exists and is tested. Do not add a generic ABAC engine solely for QR. Existing permissions SALES_ORDER_FULFILL and INVENTORY_FULFILL are necessary but not sufficient for this business policy.

**Key management:** preserve key-id/generation for HMAC verification during rotation, avoid hardcoded secrets, store only derivation identifiers and protected digest. If a key is revoked, all dependent active tokens fail or are reissued under approved recovery. Constant-time compare after indexed credential lookup, no raw token in query logs. Never misuse KMS tenant DEK for global IAM identity storage.

**UI QR mark:** white circular badge with check in center, safe quiet zone, no finder/alignment/timing obstruction, error correction H candidate, printed and display scanner qualification. A checkmark is graphical branding; it does not mean PAID, VERIFIED_SELLER, APPROVED_RETURN or READY.

## 11. Typed failures and rollback contract

| Code | Domain | Condition | Allowed outcome |
|---|---|---|---|
| QR_UNAVAILABLE | STOREFRONT | Unknown/revoked/hidden/not published | Generic public unavailable, no existence leak |
| MEDIA_NOT_PUBLIC | FILES | Attachment not approved CLEAN variant | No private bytes |
| KATO_UNVERIFIED | GEO | Unknown/incomplete hierarchy | No inferred descendant |
| AVAILABILITY_UNKNOWN | INVENTORY | Live read unavailable | Unknown band, no promise |
| PRICE_RECONFIRM_REQUIRED | SALES | Buyer saw different price revision | No PLACED until explicit acceptance |
| QTY_INVALID | SALES | 0, >1000, fractional or duplicate sum exceeds limit | No order/identity side effects |
| MONEY_INVALID | SALES | Invalid precision, currency, overflow | No quote/order |
| PROPOSAL_VERSION_CONFLICT | SALES | CAS stale revision | No new proposal event |
| PROPOSAL_EXPIRED | SALES | Now >= deadline | No AGREED |
| QUOTE_INVALID | SALES | Buyer/offer/seller/expiry mismatch | No order/consume |
| QUOTE_ALREADY_USED | SALES | Second consume | Same-key retry may read original receipt only |
| IDEMPOTENCY_CONFLICT | SALES | Same key, mismatched fingerprint/operation | Zero side effects |
| ORDER_NOT_ACCESSIBLE | SALES | Unverified or foreign buyer/order | No order detail |
| WAREHOUSE_INELIGIBLE | ORG/INV | Inactive/foreign/wrong type | Full rollback |
| INSUFFICIENT_STOCK | INV | One full-line quantity unavailable | Full confirm rollback |
| PICKUP_NOT_READY | SALES | No full ACTIVE reserves or wrong status | No proof/handover |
| PICKUP_TOKEN_INVALID | SALES | Expired/revoked/wrong purpose/digest | No private preview/debit |
| PICKUP_WRONG_WAREHOUSE | SALES | Staff warehouse differs from origin | No debit |
| PICKUP_ALREADY_HANDLED | SALES | Order already has successful receipt | Authorized exact replay sees same receipt only |
| IAM_DENIED | IAM | Suspended user/workspace, grant revoked | No business writes |
| RETURN_OVER_QTY | SALES | Return exceeds fulfilled−accepted | No RMA receipt |
| RETURN_NOT_SELLABLE | INV | Uninspected/damaged/quarantine | No sellable increment |

Network failure after possible commit is UNKNOWN_RESULT; resolve from idempotent receipt and authoritative DB. Never tell staff to scan again and debit stock blindly. Deadlock retry must restart **whole** transaction with bounded attempts, never only a failed INSERT.

## 12. Transaction examples, numerical oracle and failure injections

### Example A — full-order pickup

Before order: tenant A, warehouse W1, SKU X, on_hand=20, reserved=0. PLACED qty=5: 20/0. CONFIRMED: 20/5, available=15, origin W1. PREPARING: 20/5. READY: 20/5. Pickup credential ISSUED: 20/5. Successful handover: 15/0, available=15, reservation FULFILLED, one physical inventory movement −5, one COMPLETED event, one immutable handover receipt, credential CONSUMED. Two scanners repeat: still 15/0 and no new movement. Tenant B and W2 unchanged.

### Example B — multi-line atomicity

Order has eight PRODUCT lines and eight ACTIVE reservations at one WAREHOUSE. Fault injected into eighth inventory fulfill. Expected: all eight reservations remain ACTIVE, balances unchanged, no handover receipt, credential still usable if not independently revoked, order READY, no COMPLETED event, no committed fulfillment movement for any earlier seven lines. Exactly the same for crash before commit. This is stricter than checking only the last line.

### Example C — accepted quote checkout concurrency

Buyer U and seller A agree one offer O, quantity=10, currency KZT, price per unit 750. Two buyer devices submit checkout with same quote but distinct request keys. At most one PLACED with total=7500, one consumedQuote.orderId, one attached accepted_quote_id and immutable quantity/price snapshots. If public O price later changes, it does not mutate this already accepted agreement. If O hides *before* checkout, buyer cannot create a new order. If seller confirms with insufficient stock, PLACED remains and confirm rolls back all reservations; do not silently cancel or debit stock.

### Example D — geography and immutable history

Warehouse profile initially KATO K1, address A1, GPS (43.200000, 76.900000). Seller confirms order and origin snapshot equals full version V1. ORGANIZATION updates GPS under rowVersion to V2. Public region stock discovery now reflects CURRENT allowed profile as applicable; old order keeps V1, even after pickup and customer return. Legacy order with no origin remains UNKNOWN_LEGACY, never filled with V2.

### Example E — RMA quantity accounting

Originally fulfilled 5 units. First approved sellable return 2, second requested return 4 must reject because cumulative requested/accepted under approved policy would exceed remaining 3. Concurrent receipt for same 2 has one unique posting only. Damaged 1 moves to non-sellable classification only when a qualified quarantine model exists; cannot increase ordinary inv_stock_balance.sellable on_hand.

## 13. Release gates and exhaustive evidence contract

**Existing parent QA registry:** 37 original QR-P/N/O/G/H/C/R/S/PERF plus 36 QR-X = **73 unique requirement IDs**. These are **PLANNED**, not currently passing tests. This R1.4 adds contract IDs BR-001…050 but those are not an additional 50 executed test cases. One test may support several BRs but coverage requires explicit traceability.

| Gate | Business capability | Required parent test families | Extra exact evidence |
|---|---|---|---|
| QR-00 | Source/owner/permission/lock inventory | Trace of all 73 cases to stages | Lock DAG, DDL schema inventory, decision register |
| QR-01 | Published QR alias + read card | QR-P01..03; QR-X01..05 | One active alias, global ref unique, no stale private card |
| QR-02 | FILES public image + KATO availability | QR-P04/P05; QR-X06..10 | Real safe media owner, partial KATO negative |
| QR-03 | List-price QR checkout + ORDER_REFERENCE | QR-P06..08; QR-O01/O02; QR-X11/X12 | Existing AutoOrderService regression, email-first proof |
| QR-04 | Price bargaining + quote checkout | QR-N01..09; QR-X13..18 | Two-session CAS/quote contention and receipt identity |
| QR-05 | Warehouse GPS + PREPARING/READY | QR-G01/G02; QR-H01; QR-X19/X20 | One owner revision, full reserved states |
| QR-06 | Pickup credential and atomic handover | QR-H02..H09; QR-C01; QR-X21..29 | 8-line rollback, revoke commit orders, one-time proof |
| QR-07 | RMA inspection and return | QR-R01..04; QR-X30..33 | Original order immutable, no double stock credit |
| QR-08 | SERVICE, delivery, bin and package | QR-S01; QR-X34 + separate R2 tests | No false SERVICE stock |
| QR-PERF | Workload/performance | QR-PERF01; QR-X35/X36 | Reproducible PG explain, p95/p99, log privacy |

**Test evidence for each relevant ID** must name:
- concrete Java service method and module owner, exact source SHA and migration version;
- independent oracle from PostgreSQL base facts/status/ledger/receipt/quote, not just the response DTO;
- explicit positive, negative, cross-tenant and race case where relevant;
- two independent PostgreSQL connections with controlled barriers for interleavings (no sleep-based tests);
- separate H2 compatibility, populated SQL upgrade, FILES RustFS/ClamAV real infrastructure suite if feature touches media;
- Surefire method identifiers, counts, required workflow jobs and verify_rm_qa_01b.py manifest checks on the **same SHA**;
- no skipped mandatory cases, no synthetic unit-only claim of real OIDC/browser/phone acceptance.

The existing CI workflows (.github/workflows/rm-exact-sha-admission.yml, RM Module JUnit, SALES/INV populated, RustFS) must not be removed, weakened or declared PASS by this documentation commit. R3-v1 75 requirements metric is separate; adding 73 QR spec IDs does NOT improve implemented percentage.

### 13.1 Source-to-invariant-to-test minimal mapping

| Requirement | Method/path | Independent SQL observation | Required pattern |
|---|---|---|---|
| BR-001..006 | STOREFRONT QR alias issuer | UNIQUE public_ref, one ACTIVE per tenant+offer | parallel issue/revoke/read |
| BR-007..010 | FILES typed public image | owner_kind, scan_status, publication record | negative CRM_CUSTOMER/AV/foreign |
| BR-011..019 | AUTOORDER QR checkout | order, line amount, receipt, no inv reservation | price race/replay/qty boundary |
| BR-020..026 | SALES proposal and accepted quote | revision/event, quote/order 1:1 | double accept/checkout, expire |
| BR-027..031 | SALES confirm and ORG origin | inv reserves, order snapshot + V1 metadata | warehouse race, foreign location |
| BR-032..044 | SALES pickup and INVENTORY movement | status events, balance, receipt and token consume | two scanners, crash, IAM revoke |
| BR-045..048 | SALES RMA and INVENTORY return | unique receipt, fulfilled minus accepted quantities | damage/double return |
| BR-049..050 | SERVICE/FINANCE boundary | no SERVICE inv movement or paid status | unsupported action DENY |

## 14. Rollout and backward compatibility: staged SQL and behavior

1. **QR-00:** freeze exact SHA source inventory; inspect all actual writer methods, table names, permission registry, checkout and stock lock ordering; produce signed-off discrepancy register. No migrations yet.
2. **QR-01:** additive STOREFRONT public alias and read ports; preserve existing seller offer updates, no controllers.
3. **QR-02:** FILЕS typed CATALOG_ITEM policy, approved media derivatives; read exact KATO+live eligibility, no private inventory exposure.
4. **QR-03:** order QR reference and explicit price acceptance port; existing standard checkout still valid, no forced migration of old orders.
5. **QR-04:** proposal/quote tables, nullable price_source and accepted_quote_id; implement quote checkout with shared seller receipt namespace and verified IAM email identity.
6. **QR-05:** nullable ORG GPS and SALES origin snapshot, then PREPARING/READY typed service commands; existing confirmed orders without snapshot remain readable.
7. **QR-06:** credential digest/receipt, warehouse assignment, READY-only atomic fulfill; preserve legacy fulfill as **non-QR stock fulfillment** until separate compatibility migration and test.
8. **QR-07:** RMA/quality/quarantine approved model and typed stock receipt, never payment refund.
9. **QR-08:** future service/delivery/packages/stocktake independently qualified, no implicit change of R1 receipts.

**Rollback policy:** before real business data, additive schema can be rolled back only after safe environment/backups; once immutable order/pickup/RMA receipts exist, disable feature via safe flags and use forward schema evolution, never DROP receipts/history. Populated DB migration tests use synthetic realistic data and assert old orders, all versions and FK unchanged.

**Feature flags:** PUBLIC_QR_CARD, PRODUCT_MEDIA, REGIONAL_AVAILABILITY, QR_CHECKOUT, NEGOTIATION, WAREHOUSE_PICKUP, RMA_RETURN. Flags cannot bypass IAM, publication checks or unique receipt guarantees. Disabled stage must not compromise existing checkout/reserve/fulfill or leak QR secrets.

## 15. Non-functional: performance, resiliency, recovery, privacy

**Targets are proposals pending baseline**, not achieved values:
- public QR card indexed server resolve p95 ≤300ms, p99 ≤800ms with bounded media/region;
- staff preview p95 ≤250ms, warehouse handover commit p95 ≤800ms for R1 1–8 lines;
- negotiated quote checkout p95 ≤800ms in comparable data/load;
- duplicate stock debit and cross-tenant disclosure **0**, non-negotiable correctness.

Benchmark with fixed JVM/CPU/DB/storage, 100 tenants and realistic skew of SKU, warehouse and transaction sizes; mixed checkout, stocktake/transfer and scanner load; track p95/p99, lock waits, Hikari saturation, WAL, CPU/I/O, failed commands. Only add index/cache/queue based on EXPLAIN(ANALYZE,BUFFERS) and measurements; Redis/Kafka/ClickHouse/Rust are not default dependencies.

**Operations:** monitor READY without active reserves, completed legacy vs QR handover, issued proof older than TTL, two issued generations, quote consumed without order, return received but no inspection, and foreign-tenant attempts. Alerts and metrics must not contain raw token/email/orderId as unbounded labels. A complete runbook covers lost mobile, wrong warehouse scan, double scan, aborted fulfilment, lost database response, warehouse unavailable, FILES AV failure, QR key rotation and post-handover dispute. For physical handover performed while DB transaction fails, exception handling requires supervisor-approved reconciliation; never automatic untracked stock mutation.

**Privacy/retention:** public alias retention long-lived subject to revocation and approved URL policy; pickup digest short-lived and credential proof unrecoverable; immutable legal/stock receipts have separate retention/compliance periods. Detailed GDPR/KZ privacy, tax and evidence retention require explicit review; no invented retention dates.

**Print/scan acceptance:** central white circular badge/check, finder/timing/alignment patterns preserved, sufficient quiet zone, true black-white contrast, error correction H as candidate; real Android/iOS low-end device, camera autofocus, screen brightness, print sizes and damage matrix required only after scanner transport gate. Cosmetic checkmark never signals PAID or VERIFIED.

## 16. Decision register, policy defaults and hard blockers

| Decision | Engineering proposal, not user-approved | Blocking stage |
|---|---|---|
| DEC-01 | One stable QR per seller offer until revoke, new generation for reissue | QR-01 |
| DEC-02 | Public ref is NONsecret and reproducible; pickup secret hashed only | QR-01/06 |
| DEC-03 | Buyer may view without login, but negotiation requires verified buyer | QR-03/04 |
| DEC-04 | Quote checkout is single-line; list checkout remains up to 100 input lines | QR-04 |
| DEC-05 | Price proposal 3 rounds and 48h TTL as candidate | QR-04 policy approval |
| DEC-06 | Proposed price >0; any seller price floor is explicit separate setting | QR-04 |
| DEC-07 | Existing email identity mandatory for negotiated checkout | QR-04 |
| DEC-08 | Shared sales_checkout_receipt namespace & operation-aware fingerprint | QR-04 |
| DEC-09 | Single warehouse/full-order pickup R1 | QR-05/06 |
| DEC-10 | Warehouse staff assignments are required beyond tenant permissions | QR-06 security |
| DEC-11 | Short-lived pickup QR (candidate 3 minutes), no offline debit | QR-06 |
| DEC-12 | Lost token → reissue after buyer proof, no raw persisted proof | QR-06 |
| DEC-13 | No-show cancellation only after separate READY→CANCELLED release ADR | No-show feature HOLD |
| DEC-14 | Returns via RMA/inspection; quarantine model must be approved | QR-07 |
| DEC-15 | Service offer readable but no inventory fulfillment of SERVICE | QR-08 |
| DEC-16 | Region exact KATO; delivery zones/destination separate | QR-02/08 |
| DEC-17 | Public FILES derivative publication and cache revocation policy | QR-02 |
| DEC-18 | No mixing COMPLETED stock movement with financial payment/POD | All |
| DEC-19 | Four locales, integer qty, BigDecimal currency exact | QR-01 onward |
| DEC-20 | QR visual center check cosmetic, real scanner QA after transport | QR-HTTP |
| DEC-21 | Receipt/digest retention, legal evidence, sensitive logging | Production |
| DEC-22 | External OIDC/browser/QR scanner transport separated | Production |

**Not approved defaults are never coded as hidden policy.** QR-00 must close stage-relevant decisions and publish a source-anchored implementation slice specification before coding. Stage-specific tests can be implemented in synthetic UAT without asserting legal or production acceptance.

## 17. Definition of Done and non-negotiable prohibited shortcuts

A QR stage is DONE only if: implementation matches approved source-owner contract; additive DDL passes populated PostgreSQL upgrade; exact source method/invariant/test crosswalk exists; real PG positive+negative+cross-tenant+races pass with complete rollback; H2 and existing SALES/INV/IAM/FILES mandatory suites remain green; exact-SHA CI artifact and manifest genuinely validate code SHA; no open scope escalation or double stock movement; load evidence documented where required. OIDC HTTP adapter/browser scanner, external email transport, privacy/security and production release need separate explicit gates.

**Never do:** direct stock balance writes from SALES/QR; create phantom buyer identity from typed email; copy raw pickup token into immutable event or audit; use order public ref to issue goods; treat QR scan as PAID; let service SKU consume PRODUCT inventory; silently change price or qty; infer GPS from KATO centroid; extend readonly public FILES from CRM_CUSTOMER private attachment; create controller during Feature Freeze; claim drafted tests are executed; weaken exact-SHA workflow or merge main.

**Status:** QR-R1.4 canonical implementation specification; no feature code or tests added by this document. Production HOLD.
