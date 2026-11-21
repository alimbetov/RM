# RM SALES–INVENTORY R1.2 — implemented internal commands

**Status:** merged into `main` via PR #2; H2/PostgreSQL CI PASS on reviewed HEAD. No public API or production admission.

## Commands
- `AuthenticatedSalesCommands` accepts `VerifiedActor` and delegates to transactional `SalesInventoryCommandService`.
- `confirm`: PLACED → CONFIRMED; all lines reserved atomically. `cancel`: PLACED/CONFIRMED/PREPARING → CANCELLED and active reserves released. `fulfill`: CONFIRMED/PREPARING → COMPLETED with one debit per reservation.
- Idempotent terminal-state retries verify reservation state. Completed is not shipping proof, payment settlement, invoice or COGS.
- `AuthenticatedInventoryCommands` and `InventoryAuthorizedCommandService` manage separately permitted receive/adjust operations.

## Security boundary
- Requires BOTH SALES and INVENTORY permissions on each order-linked operation.
- `TenantAuthorization.requireForCommand` holds a PostgreSQL tenant `FOR SHARE` lock until commit; tenant RBAC mutation uses `FOR UPDATE`.
- The `OrderInventoryPort` validates same actor/tenant, exact permission pair and transaction-bound decisions. Raw reservation and movement mutations are package-private.
- Stock row locks, consistent multi-SKU ordering, unique reservation/movement evidence and transaction rollback protect against overselling/double debit.

## Open gates
- Real OIDC HTTP/BFF principal propagation;
- account and membership suspension on the same lock protocol;
- DB least privilege, populated-database migration rehearsal, external E2E and load qualification;
- partial/multi-warehouse fulfillment, valuation and full shipping state machine.

Previous claims that transactional revocation and inventory port protection were **not** implemented have been superseded by FRZ-1. See [canonical baseline](../architecture/RM-DOMAIN-LAYER-BASELINE.md) and [security review](../reviews/RM-FRZ1-SECURITY-REVIEW-2026-11-21.md).
