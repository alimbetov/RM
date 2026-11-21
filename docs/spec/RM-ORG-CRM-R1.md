# RM-ORG-CRM-R1 — Organization CRM functional contracts

**Status:** PROPOSED MODULE SCHEMA / implementation backlog; no CRM/Billing tables or REST endpoints deployed in IAM-R1 slice.  
**Base:** approved seven account modes and `Workspace.id` as tenantId.  
**Architecture:** multi-pod RM; H2 dev, PostgreSQL integration; KMS encryption for sensitive values.

## Cross-cutting constraints

- Every business row carries `workspace_id`. Composite `(workspace_id, id)` identity/unique constraints on all referenced objects, with same-workspace composite foreign keys for links. Never authorize based on request-supplied workspace alone; require valid active `OrganizationMembership` from authenticated principal.
- Organization owner does not acquire global admin; personal and business CRM records never merge implicitly.
- Scope-aware permissions: `CUSTOMER_READ/WRITE`, `ORDER_READ/WRITE`, `ESTIMATE_APPROVE`, `INVOICE_ISSUE`, `PAYMENT_RECORD`, `CAMPAIGN_SEND`, `AUDIT_READ`; org-specific assignments; platform auditor has READ metadata only, cannot edit or decrypt PII by default.
- `status`, `created_at`, `updated_at`, `created_by`, `row_version` for mutable rows. Per-tenant indexes start with `workspace_id`. Domain events use transactional outbox and deduplication, not uncoordinated `@Scheduled` across pods.
- Money: `DECIMAL(19,4)` (or methodology-specific scale), `currency CHAR(3)`; never double. Store snapshots of item price/quantity/tax at time of acceptance; no retrospective mutation of issued invoices.
- Encryption: CRM contact phone/email/notes stored as `EncryptedValueCodec` envelopes when needed, with `CryptoContext(workspaceId, entityType, stableEntityId, field)`. Search keys/blind index are an explicit later privacy design, not raw ciphertext LIKE.

## 1. Clients and contacts

- `crm_customer`: id, workspaceId, customerType PERSON/COMPANY, displayName, taxIdRef nullable, stage, responsibleMembershipId, createdAt, updatedAt, rowVersion.
- `crm_contact`: id, workspaceId, customerId, fullName, role, phoneEncrypted, emailEncrypted, isPrimary, status.
- `crm_consent`: id, workspaceId, contactId, channel EMAIL, purpose, consentGranted, source, policyVersion, capturedAt, revokedAt, proofRef; retain immutable consent history; never infer from CRM contact existence. Email sending requires active positive `consent_email` immediately before dispatch.
- `crm_customer_event`: append-only timeline links to customer/contact/order/task and change/communication events; immutable actor/correlation and timestamps.

## 2. Orders

- `crm_order`: id, workspaceId, customerId, reference (unique per tenant), ownerMembershipId, pipelineId, stageId, status, dueAt, acceptedEstimateRevisionId, subtotal, currency, createdAt, rowVersion.
- `crm_order_line`: id, workspaceId, orderId, position, catalogItemId nullable, catalogVariantId nullable, skuSnapshot, nameSnapshot, descriptionSnapshot, quantity, unitSnapshot, unitPrice, discount, currency, total, costSnapshot nullable, rowVersion. Catalog FK must be same-workspace. Historical snapshots are immutable after confirmation.
- `crm_order_status_event`: fromStatus,toStatus,changedBy,reason,occurredAt; validated state machine.
- `crm_order_pipeline`, `crm_order_stage`: tenant-specific configurable pipeline/stage with ordering and terminal labels.
- Create from customer card: verify same tenant and required membership; persist order and initial event atomically.

## 2A. Organization-specific product and service catalog — mandatory before orders

Every organization has its **own catalog scoped to `workspace_id`**. Two organizations may use the same SKU without collisions. Personal workspaces may optionally use the same catalog domain later, without sharing organization records.

### Core entities

| Table / entity | Important fields | Rules |
|---|---|---|
| `crm_catalog_category` | id, workspaceId, parentId nullable, code, name, sortOrder, status | Unique `(workspace_id, code)`, parent must belong to same workspace, no cycles |
| `crm_catalog_item` | id, workspaceId, categoryId nullable, type PRODUCT/SERVICE, name, description, status ACTIVE/ARCHIVED, defaultUnitId, trackInventory, createdAt, rowVersion | Tenant-scoped, service never requires stock; archive rather than hard-delete referenced item |
| `crm_catalog_variant` | id, workspaceId, itemId, sku, barcode nullable, attributesJson/version, status, rowVersion | SKU unique **within workspace**; multiple variants for one product; services may use one default variant |
| `crm_catalog_unit` | id, workspaceId nullable, code, name, precisionScale | Global standardized units read-only plus optional tenant-specific units; avoid ambiguous quantity rounding |
| `crm_catalog_price` | id, workspaceId, variantId, priceListId, currency, amount, validFrom, validTo nullable, revision | Effective-dated prices; reject overlapping active windows for same variant/list/currency, validate in PostgreSQL transaction |
| `crm_catalog_price_list` | id, workspaceId, name, currency, isDefault, status | Default list is unique per workspace/currency |
| `crm_catalog_cost` | id, workspaceId, variantId, currency, unitCost, effectiveFrom, source, revision | Cost for margin; access-controlled, separate from selling price; historical order cost snapshot |
| `crm_catalog_media` | id, workspaceId, itemId, storageKey, mediaType, sortOrder, scanStatus | External object storage, no credentials, checked MIME and malware status |

### Optional inventory extension, separate from catalog CRUD

- `crm_stock_location`: warehouse/stockroom belonging to one workspace.
- `crm_stock_balance`: (workspaceId, variantId, locationId), onHand, reserved, rowVersion; `available=onHand-reserved`.
- `crm_stock_movement`: append-only source document, delta, reason, timestamp, idempotencyKey. Stock updates and movement committed atomically; use row lock/optimistic version to prevent overselling across pods.
- `crm_stock_reservation`: orderLineId, variantId, qty, status, expiresAt, fencing version. **Do not** infer reservation from order status alone.
- `crm_supplier` / `crm_supplier_item` may follow later (purchasing is out of CRM-R1 scope).

### Required business behavior

- Categories, items, variants and prices support CRUD with audit, soft archive and pagination. Filtering by category/name/SKU, no cross-organization access even if UUID is known.
- The buyer-facing order or estimate line may be **manual/free-text**, or linked to a catalog item. On add/accept, persist a full **price/name/unit/cost snapshot** and currency so later changes to catalog cannot change past amounts.
- A catalog reference always uses `(workspace_id, catalog_item_id)` and `(workspace_id, catalog_variant_id)` foreign keys/verification. All SKU uniqueness and price effective-window checks are tenant-scoped.
- Inventory is optional for SERVICE and for PRODUCT when `trackInventory=false`. Creating estimates never implicitly decreases stock. Order confirmation/reservation is a separate lifecycle action.
- Prices in different currencies are never added without explicit FX conversion and rate version. Decimal arithmetic only (`BigDecimal`); quantity precision follows the unit.
- Catalog permissions: `CATALOG_READ`, `CATALOG_WRITE`, `PRICE_MANAGE`, `COST_VIEW`, `STOCK_MANAGE`, separately assignable via organization membership.
- Future importing product spreadsheets should be idempotent per (workspace, importBatch, sourceRow), validate SKU conflicts and report partial failures, never apply a cross-tenant global SKU map implicitly.

### Qualification cases

`CAT-01` same SKU in two organizations is allowed; `CAT-02` duplicate SKU in same organization rejected; `CAT-03` cross-tenant category/variant/price association rejected; `CAT-04` service needs no stock; `CAT-05` price update does not mutate confirmed orders/accepted estimates; `CAT-06` cannot delete variant referenced by history; `CAT-07` nonoverlapping effective price revision; `CAT-08` concurrent stock reservations never oversell; `CAT-09` mass XLSX/CSV import idempotency; `CAT-10` sensitive cost visibility by role; `CAT-11` two pods cannot create same tenant SKU simultaneously; `CAT-12` category ancestor cycle rejected.

## 3. Estimates

- `crm_estimate`: id, workspaceId, orderId, status DRAFT/SENT/ACCEPTED/REJECTED/SUPERSEDED, currentRevision.
- `crm_estimate_revision`: id, estimateId, revisionNumber, currency, amountTotal, createdAt, createdBy, immutable after publication; unique(estimateId,revisionNumber).
- `crm_estimate_line`: id, workspaceId, revisionId, catalogItemId/variantId nullable (same-workspace), skuSnapshot, descriptionSnapshot, unitSnapshot, qty, priceSnapshot, discount, tax and total. An accepted revision copies frozen snapshots, not mutable catalog state.
- Acceptance `crm_estimate_acceptance`: unique accepted revision, acceptedAt, acceptedBy, idempotencyKey; snapshot accepted lines into order as one PostgreSQL transaction with order version check; never duplicate on retries.
- Editing an accepted estimate requires creating a new version and explicit amendment; never overwrite historical revision.

## 4. Management finance

- `crm_invoice`: id, workspaceId, orderId, estimateRevisionId nullable, invoiceNumber scoped to tenant, issuedAt, dueAt, status, amount, currency, rowVersion.
- `crm_invoice_line`: immutable priced snapshots (independent from mutable order lines).
- `crm_payment`: id, workspaceId, paymentReference, paidAt, amount, currency, source, status, idempotencyKey; capture payment provider reference without storing secrets.
- `crm_payment_allocation`: invoiceId/paymentId/allocatedAmount; enable one payment to settle multiple invoices, enforce no over-allocation transactionally.
- Invoice analytics: **invoiced** = sum finalized nonvoid invoices, **paid** = allocated settled payments (net of reversed/refunded allocations), **outstanding** = invoiced - paid. Currency-specific aggregation only; payments are not directly profit.
- Margin if costs exist: **recognized revenue − captured cost**, both in the same currency and defined recognition period. Define `amount_actual` unambiguously: it cannot simultaneously mean order face value, customer payment, and recognized revenue. No cost ⇒ margin unknown, not zero.

## 5. Email communications

- `crm_segment`: tenant-specific rule-set AST + version, dynamic or saved snapshot; no raw executable SQL expression from user.
- `crm_email_template`: id, workspaceId, revision, subject, bodyHtml/bodyText, approvedVariables, status; sanitize HTML and PII.
- `crm_campaign`: template revisionId, segment revision/snapshot, scheduledAt, status, throttling, createdBy, deduplication key.
- `crm_campaign_recipient`: contactId, consentProof/revision, sendState, idempotencyKey; eligibility rechecked immediately before sending.
- `crm_email_delivery_event`: immutable delivery/open/click/bounce/unsubscribe with provider IDs and timestamps; opens may be inaccurate due to privacy proxy/pixel blocking.
- `crm_unsubscribe`: contact/address hash, purpose/scope, timestamp, source; suppression applies before dispatch irrespective of campaign segment.
- Scheduler/outbox needs PostgreSQL claim/fencing semantics; lease alone insufficient for exactly-once provider delivery. Provider sends are at-least-once with idempotent deduplication where supported. Consent revocation wins over queued sends.

## 6. Tasks and history

- `crm_task`: id, workspaceId, relatedCustomerId/relatedOrderId, ownerMembershipId, priority, status, dueAt, createdAt, completedAt, rowVersion.
- `crm_communication`: id, workspaceId, customerId/contactId/orderId, channel, direction, performedAt, bodyEncrypted/ref, outcome, actor; access-controlled.
- `crm_audit_event`: append-only, before/after hashes/allowlisted metadata, actor, action, tenant, entity, correlationId, occurredAt. No raw DEK/plaintext, no retroactive rewriting. Outbox-to-audit after commit as appropriate, guaranteed delivery.

## 7. Dashboards

Materialize/query per-workspace with scoped access and explicit reporting `asOf`:
- orders by state and count/amount;
- invoiced, settled allocated paid, outstanding by currency;
- lead-to-order conversion = unique leads converted / qualifying leads within a stated cohort and time window;
- campaign delivery = accepted deliveries / attempted sends; open = tracked unique opens / delivered; unsubscribe = unsubscribes / delivered. Metrics caveat: open pixel is an estimate.
- `crm_dashboard_daily` optional projection keyed (workspaceId, date, currency), rebuildable from immutable events and verified against transactional source; only introduce after profiling. No cross-currency sum without explicit FX conversion.

## Entity order and release sequence

| Phase | Scope | Gate |
|---|---|---|
| IAM-R1 | User, Workspace, Organization, Membership, Global Roles (5 tables) | Schema + JPA + tenant isolation tests |
| CRM-R1A | Customers, Contacts, Consent, Timeline | CRUD + consent negatives + KMS persisted E2E |
| CRM-R1A.5 | Organization catalog: categories, products/services, SKU, price lists, costs, units | Tenant-scoped SKU, price history and catalog snapshot tests |
| CRM-R1B | Orders, lines, status/pipeline, responsibility with optional catalog FK | Frozen price/name/cost snapshots and same-tenant FK tests |
| CRM-R1C | Estimate revisions and acceptance | Immutable acceptance, idempotent copy-to-order tests |
| CRM-R1D | Invoices, payments, allocations, receivables | Independent reconciliation and currency invariants |
| CRM-R1E | Segments, templates, campaigns, delivery/suppression | Consent at send time, distributed scheduler + provider retries |
| CRM-R1F | Tasks, communication, audit | Append-only audit and PII restriction tests |
| CRM-R1G | Dashboard APIs/projections | Independent total reconciliation, reporting-period and FX tests |

**Product scope update:** organization-specific product and service catalogs are mandatory in the business model and precede order/estimate implementation. IAM-R1 does not create catalog tables yet.

**Production gate:** 7 user modes approved, but authorization middleware and feature entitlements are still separate implementation phases. The IAM-R1 changeset does not yet provide HTTP endpoints, billing, onboarding workflow or CRM tables.


## 8. Территориальная привязка КАТО до районного уровня

**Утверждённое ограничение CRM:** в форме организации, филиала, склада или торговой точки выбирать территорию **до района / городской администрации включительно**, без населённых пунктов и сельских округов. Для текущей структуры `kato-data.json` это активные записи с `Level <= 3`: `2` — область; `3` — район или городская администрация. `AreaType` сохраняется как дополнительная классификация, но не подменяет `Level`.

- Уровни 4–6 сохраняются в глобальном справочнике на случай истории и будущего расширения, но не допускаются для создания новых `org_location`.
- `kato_code` указывает на выбранную территорию; `address_line` содержит улицу, дом и прочие детали. Не считать выбор района доказательством точного расположения.
- Иерархия `Parent` разрешается по `source_id`, не вычисляется по цифрам кода `Code`.
- Удалённые записи (`IsMarkedToDelete`) недоступны для новой привязки, но остаются для исторических ссылок.
- Фильтры каталога, запасов, заказов и дашборда смогут использовать `workspace_id + kato_code` выбранной локации; динамические фильтры по иерархии требуют полноценного источника КАТО.
- Фактический файл содержит **лишь 100 записей**, в том числе 2 области и 3 записи третьего уровня. Для рабочего общереспубликанского выбора потребуется загрузка полного актуального КАТО с датой версии и проверкой целостности.

**Acceptance:** филиал/склад/точка продаж принимает активный район или область; отклоняет `Level > 3`, удалённый и неизвестный код; сохраняет `address_line` независимо от кода.
