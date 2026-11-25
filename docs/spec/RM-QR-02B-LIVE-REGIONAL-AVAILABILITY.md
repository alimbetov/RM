# RM-QR-02B — Live Regional Availability & Public Offer Search

**Parent:** RM-QR-R1.4 and RM-COMMERCIAL-GEO-REGION-QR-FULFILLMENT-R1.
**Branch:** base_dev. **Scope:** read-only service-first integration; no controllers, no new stock ledger.
**Status:** implementation candidate pending PostgreSQL and exact-SHA qualification.

## Authoritative contracts

- GEO: KatoLocationPolicy.requireOrganizationLocationEligible confirms a known, not-deleted exact KATO code within existing eligible levels. No code-prefix guessing, geographic radius, routing or inferred parent/child hierarchy.
- ORGANIZATION: public.org_location with warehouse type, ACTIVE status, workspace_id and exact kato_code. No inactive branch/POS, foreign tenant or guessed GPS.
- INVENTORY: crm.inv_stock_balance is the only live balance. The snapshot is advisory; exact stock quantities are NOT public.
- STOREFRONT/CATALOG: every query revalidates the currently active seller workspace, current PUBLISHED storefront and offer, PUBLISHED PRODUCT and current ACTIVE QR alias. No display of hidden, archived, revoked or inactive seller objects.
- A requested order quantity 1..1000 in the existing PRODUCT integer checkout contract is satisfied only when ONE eligible ACTIVE warehouse in the specified exact KATO has (on_hand-reserved)>=requestedQty. Summing two warehouses with 1 each into a promised 2 would violate R1 one-warehouse/full-order confirmation. Maximum per-warehouse available is used only for an indicative band.
- Region bands: INDICATIVE_AVAILABLE, INSUFFICIENT_STOCK, NOT_LISTED_IN_REGION. These do NOT reserve goods, guarantee checkout or promise delivery.
- Public search lists active seller offers with a single qualifying warehouse at exact KATO. It returns opaque offer ref, localized title (with documented source fallback), current offer price/currency and requested KATO; no tenant UUID, warehouse identity, inventory quantities or private data.
- Keyset pagination on globally unique stable public_ref; deterministic ascending order and max page size 50, no unbounded offset.
- If a query cannot validate the KATO source, fail with KATO_UNVERIFIED rather than silently constructing region ancestry.
- Public PublishedOfferCardQuery overload adds requested exact KATO and quantity, and translates the inventory band. Two-parameter historical signature remains compatible and uses UNKNOWN when no region is selected.

## Positive and negative PostgreSQL evidence

1. Two warehouses each with available=1 do NOT show qty=2 available; adding stock to ONE warehouse does.
2. Inactive, out-of-region, foreign-tenant warehouse positions do not contribute; seller hide/pause/revoke blocks public result.
3. Same SKU text of two sellers remains two separate offers; a shortage in tenant A does not suppress tenant B.
4. Reservation changes affect the live reading; offer search never changes on_hand, reserved, reservation, movement or order.
5. Invalid KATO or quantity fail closed; synthetic tests do not establish accuracy of full Kazakhstan KATO hierarchy.

**Remaining:** populated large-data EXPLAIN/p95/p99, complete nationwide KATO ancestry, geolocation radius/ETA, shipment zone policies and real checkout/QR transport. None is claimed ready here.
