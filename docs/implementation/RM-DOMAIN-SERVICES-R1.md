# RM DOMAIN-SERVICES-R1 — прикладные DTO, MapStruct, repositories и фильтры

**Ветка:** feat/sec-r1-unified-domain-authorization. **Status:** internal read-side implementation candidate; PostgreSQL/H2 CI required, public OIDC HTTP endpoints OFF.

## 1. Контракт без усложнения

- Вертикали R1: ORGANIZATION location read, INVENTORY stock read и SALES order list. Не вводить универсальный generic repository/service и не плодить абстрактные DTO базовых классов.
- Оба сервиса принимают `authenticatedActorId` и `tenantId` **только от доверенного adapter**. Не создавать controller, где actor передаётся из query/path/body. `TenantAuthorization.require` вызывает active IAM user/workspace/membership + concrete domain permission **до** чтения данных.
- Page/size: page ≥ 0, 1 ≤ size ≤ 100, zero-based. Детерминированная сортировка по созданию+id (ORG), location+SKU (inventory). В ответе Spring `Page<T>`: content, total, page metadata. Избегать неограниченных list endpoints.
- В DTO не возвращать личные данные, secret envelope, IAM subject и KMS key; stock available = on_hand − reserved.

## 2. ORGANIZATION

- `LocationDto`: id/workspace/type/name/KATO/address/status/timestamps/rowVersion.
- `LocationSearch`: nullable type/status/name (trim, max 100 chars).
- `LocationMapper`: MapStruct Spring component, explicit entity → immutable record.
- `OrganizationLocationRepository.search`: Spring Data JPA `Page`, обязательный workspaceId в query; фильтр по типу, статусу, case-insensitive substring name; skip expensive joins.
- `OrganizationLocationQueryService.search/get`: IAM `ORGANIZATION_LOCATION_READ` before repository, tenant isolation and hard page-size cap.

## 3. INVENTORY

- `StockBalanceDto`: tenant/location/SKU, onHand/reserved/available, rowVersion.
- `StockSearch`: optional locationId, itemId and boolean availableOnly.
- `StockBalanceRepository`: простой JDBC read-only repository, строгий tenant_id predicate, whitelisted SQL snippets and bound values, separate COUNT and stable page query. **Это снимок двух SQL запросов, не транзакционно консистентный экспорт на конкурентно меняющемся складе**: для финансовых reconciliation требуется snapshot strategy.
- `StockBalanceQueryService`: IAM `INVENTORY_STOCK_READ`, zero-based page and cap 100. Коды SQL stock updates не менялись.

## 4. SALES

- `SalesOrderDto`: только order id, статус, сумма, валюта, дата создания и версия; без buyer UUID/email/телефона.
- `SalesOrderSearch`: optional status из фиксированного набора и временной интервал `[fromInclusive,toExclusive)`; проверяется корректность границ.
- `SalesOrderRepository`: JDBC с обязательным tenant predicate и bind-параметрами, стабильная сортировка created_at DESC, id DESC.
- `SalesOrderQueryService`: `SALES_ORDER_READ` и ограничение page/size; seller-side read, не публичный buyer view.

## 5. Безопасность

- CRM/ORG/INVENTORY получают права из общей модели SEC-R1.0.
- Не существует публичной API реализации, поскольку нет доверенного OIDC principal mapping.
- OWNER/ADMIN/MANAGER/MEMBER/VIEWER — допуск к чтению обычных складских остатков в рамках активной организации (будущие scope-by-warehouse ограничения — SEC-R1.1).
- Для каждой новой команды mutating stock обязательно отдельное разрешение; `INVENTORY_STOCK_READ` не разрешает корректировку, поступление или отгрузку.

## 6. Приёмка и неохваченные сценарии

- H2 и PostgreSQL CI: создание нескольких locations, поиск с optional фильтрами, 0/1 page, size>100 отрицательная проверка; tenant-A not visible in tenant-B.
- PostgreSQL stock: reserved floor, availableOnly excludes zero-available stock, чужой tenant отклоняется.
- Нет отдельной schema migration (используются существующие таблицы).
- Не внедрены MapStruct для existing JDBC-only CRM secrets, потому что entity mapping не должен скрыто дешифровывать PII.
- Дальнейшие вертикали: CATALOG publish/price revision; dedicated role permissions for stock adjustments. Объём ограничен намеренно, чтобы проверить архитектуру на реальном PG прежде чем повторить для других 13 доменов.

PG qualification note: a nullable `:name` inside JPQL `LOWER(CONCAT(...))` caused PostgreSQL `LOWER(bytea)` while H2 passed. The source now binds empty string for absent name, preserving a typed string parameter. This incident is a reason to require PostgreSQL gates.
