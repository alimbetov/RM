# RM-QR-00 / QR-01 — исходная трассировка и первая реализация

**Этап:** QR-00 preflight + QR-01 PRODUCT_OFFER reference/read-only card.
**Нормативная основа:** RM-QR-R1.4, источник 29e960f6d8251918d45cca99e8de2598ed8afebf.
**Ветка:** base_dev. **Статус:** CODE CANDIDATE, QUALIFICATION PENDING, PRODUCTION HOLD.
**Freeze:** Новых REST-контроллеров, HTTP/public transport, QR image renderers, отдельного QR-домена или микросервиса нет.

## 1. Фактический объём первой поставки

- STOREFRONT владеет постоянным PRODUCT_OFFER public reference. Ref — random NON-SECRET, повторная печать возвращает прежний идентификатор.
- Seller commands PublishedOfferQrService.issueOrGet/reprint/revoke/reissue используют VerifiedActor и STOREFRONT_OFFER_MANAGE через TenantAuthorization.requireForCommand.
- Запись требует существующий owner lock order: IAM actor/workspace -> store_front FOR UPDATE -> store_offer FOR UPDATE -> cat_item FOR UPDATE для issue/reissue.
- Новая таблица crm.store_offer_qr_ref хранит только связь offer/ref, а не CATALOG, цену, stock или заказ.
- UNIQUE(tenant_id,active_offer_id) с NULL при REVOKED семантически обеспечивает один активный QR на offer и совместим с H2, вместо PG-only partial unique. Есть global UNIQUE(public_ref), generation unique и tenant composite FK.
- PublishedOfferCardQuery.findPublished — анонимное чтение через Java, не HTTP. Каждый read проверяет ACTIVE workspace + опубликованные store, offer, PRODUCT. Фото и регион ещё недоступны: состояние доступности UNKNOWN.
- Текущая цена читается исключительно из crm.store_offer; issue/reprint/read ничего не изменяет в SALES/INVENTORY и не создаёт нового source of truth.
- Аудит выпуска/отзыва: tenant+actor+offer и action-code, без секретов.

## 2. Source-to-command трассировка

| Процесс | Владелец | Источник истины | Изменение |
|---|---|---|---|
| Публикация | StorefrontOfferService, CatalogItemService | store/offer/item PUBLISHED | Существующий код, без изменений |
| QR issue/reprint/revoke | PublishedOfferQrService | store_offer_qr_ref | Новая service-only функция |
| Публичная карточка | PublishedOfferCardQuery | store + offer + cat_item/i18n | Только безопасное чтение |
| Цена | StorefrontOfferService.updatePrice | store_offer + immutable price revisions | QR показывает current |
| Автозаказ | AutoOrderService.checkout | SALES order and receipt | Не изменять на QR-01 |
| Фото | FILES CATALOG_ITEM | file_attachment + publication | QR-02 / HOLD |
| Геодоступность | ORG/GEO/INVENTORY | KATO + live stock | QR-02 / HOLD |
| Выдача | SALES/INVENTORY | order, reserve, movement | QR-05/06 / HOLD |

## 3. Проверки кандидата

| Тест | Инвариант |
|---|---|
| qrP01StableReprintAndCurrentPublishedPriceAfterRevision | Same alias, new current price, one issue audit |
| qrP03PausedHiddenAndRevokedReferencesCannotExposeCard | Paused/hidden/revoked ref non-public, new generation |
| qrP02SameSkuAcrossSellersAndForeignTenantMutationsRemainIsolated | Offer identity tenant-safe |
| qrP03UnpublishedProductAndUnknownLocaleDenied | Catalog and locale fail-closed |
| qrP01ConcurrentIssueOneActiveReferenceInRealPostgres | Real PG contention yields one ACTIVE alias |

H2 не доказывает поведение FOR UPDATE в PostgreSQL. Exact-SHA CI и independent SQL oracle обязательны. Пока результаты не подтверждены, не объявлять QR-01 квалифицированным.

## 4. Открытые вопросы QR-00 и область дальнейшей разработки

1. В QR-01 issueOrGet естественно идемпотентен по (tenant,offer). R1.4 дополнительно предлагает requestKey+fingerprint для произвольных request replay. Этот интерфейс пока не реализован и требует отдельного утверждения или расширения; до этого полный QR-01 gate HOLD.
2. Идентификатор QR — не HTTPS URL и не изображение; домен, публичный транспорт и круглый значок с галочкой требуют отдельного безопасного adapter/scanner gate.
3. Пока нет approved FILES CATALOG_ITEM owner и safe image publication: public card без фото, никаких закрытых вложений.
4. Пока нет квалифицированной GEO иерархии/region stock: доступность UNKNOWN, не обещать наличие или доставку.
5. Buyer identity не создаётся при scan. Текущий checkout сохраняет email-first flow; negotiated checkout и price revision acknowledgement — QR-03/04.
6. Популяционная PostgreSQL migration, EXPLAIN/benchmark, OIDC/browser/телефон и нагрузка — отдельные доказательные рубежи.

**DoD:** exact-SHA compile + H2 migration + реальный PG concurrency/security + unchanged SALES/INVENTORY/FILES regression + approved issue idempotency. Production и новый HTTP transport запрещены без независимого решения.
