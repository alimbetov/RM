# RM INVENTORY-VALUATION-R1 — поступления и методы оценки себестоимости

**Статус:** PROPOSED / к реализации после INV-R1.1 reservation E2E. **Дата:** 2026-11-21.
**Принцип:** количество, резервы и стоимость — независимые, согласованно проводимые регистры. Никаких silent revaluations completed orders.

## 1. Формулы и разрешённые методы

- `FIFO`: при проведённом выбытии стоимость назначается по самым ранним ещё не исчерпанным слоям поступления.
- `MOVING_WEIGHTED_AVERAGE`: после приходной операции `new_avg=(old_qty*old_avg+received_qty*new_unit_cost)/(old_qty+received_qty)` с сохранением суммы стоимости до округления. Исходные затраты и rounding adjustments отдельно записываются в immutable valuation journal.
- `SPECIFIC_IDENTIFICATION`: определённая единица/партия/серийный номер, не взаимозаменяемая с прочими.
- `PERIODIC_WEIGHTED_AVERAGE`: будущая версия, считает общую среднюю по закрываемому периоду и требует period-close.
- `LIFO`: только `SIMULATION`/scenario forecast; запрет `POSTED` в IFRS-oriented financial mode (IAS 2 permits FIFO / weighted average for interchangeable inventory). Нельзя использовать LIFO для официального posting под IFRS.
- `FEFO`: отдельная физическая стратегия выбора партии по `expiry_at`; не считать отдельным cost valuation formula.

## 2. Предметные агрегаты и SQL-контракты (ещё не созданные таблицы)

- `inv_receipt`: tenant, location, supplier document, received_at, accounting_at, document_currency, status DRAFT/POSTED/REVERSED, `idempotency_key`, `row_version`.
- `inv_receipt_line`: receipt, item/SKU, quantity, unit_cost, eligible landed costs, FX rate/source/date, tax eligibility, purchase line snapshot.
- `inv_cost_layer`: tenant, item, location, receipt-line provenance, effective_at, original_quantity, remaining_quantity, original_value, remaining_value, optional batch/expiry and serial identity; composite tenant FKs.
- `inv_cost_policy_version`: legal-entity scope, equivalent-inventory category/SKU scope, `FIFO|MOVING_WEIGHTED_AVERAGE|SPECIFIC_IDENTIFICATION`, effective interval, approval, immutable policy revision SHA. Warehouse transfers preserve historical economic cost.
- `inv_valuation_event`: immutable cost allocation/revaluation/rounding/correction, currency, `cost_policy_version`, `sale_order_line_id`, `stock_movement_id`, `received_layer_id`, exact before/after values. Posted sales cost must reference this immutable evidence.
- `inv_stock_movement` already exists; `inv_stock_reservation` exists in feature branch and only affects quantity reserved; reservation must NOT post COGS before confirmed fulfillment.
- `fin_cost_posting`: created later by FINANCE from immutable valuation receipts, not by INVENTORY directly mutating invoices.

## 3. Непересекающиеся жизненные циклы

**Receipt POSTED:** validate supplier/source, VAT/landed-cost allocation and FX; atomic `on_hand += qty`, `inv_stock_movement(RECEIPT)`, cost layers/average update, outbox. Idempotent across pods. No direct mutable balance update by controllers.

**Reservation ACTIVE:** `reserved += qty` but financial book value unchanged.

**Fulfillment:** transactionally decrement `on_hand` and `reserved` once and allocate frozen cost according to active valuation policy and available cost layers; immutable COGS event; compensation/reversal is another explicit event.

**Release:** release reserve, do not change valuation.

**Return / transfer / adjustment:** explicit reason, original movement/sale reference, supplier batch/serial or valuation receipt; do not silently change posted accounting values. Negative inventory prohibited. Backdated receipt triggers period revaluation workflow rather than retroactively changing finalized snapshots.

## 4. Security and accounting admission

- Tenant identity from verified principal; supplier PII encrypted according to privacy classification.
- Scope currency is accounting/legal entity; item/stock location in same tenant; do not average KZT and USD without a recorded and verified functional-currency conversion.
- Use `BigDecimal`; quantities precision/scale, cost high precision and explicit rounding at posting, deterministic tie-break (received_at, receipt_line_id).
- Under IFRS, same cost formula for inventory with similar nature/use; changing the policy needs approval and effective date, not silent switching per order.
- Cost ledger is immutable, retains its own approved legal/financial retention, not AUDIT 3–5y auto-purge.
- Opening stock needs quantity AND valuation baseline; provide migration reconciliation of existing `inv_stock_balance` without a cost layer.

## 5. Acceptance scenarios

1. Two receipts (10×100, 10×140) and shipment of 12 => FIFO COGS=1280, closing=1120; moving average COGS=1440, closing=960.
2. Concurrent receipt/fulfill/reservation for one SKU no negative stock, no lost update, COGS exactly once.
3. Idempotent retry retains quantity and cost; mismatched payload rejects.
4. Two tenants / multiple locations never share valuation evidence or costs improperly.
5. Equal timestamps use deterministic lot ordering and repeatable test result.
6. Foreign-currency source must have approved FX rate and functional currency before POSTED.
7. Once a period/receipt posted, edit/delete and valuation method rewrite prohibited.
8. FEFO allocation picks nearest expiry without silently imposing LIFO valuation.
9. LIFO simulator never creates POSTED valuation events in IFRS mode.
10. PostgreSQL E2E + upgrade with historical stock + failure injection + 2-process load.

**Priority:** complete INV-R1.1 reserve/release/fulfill GREEN, then INV-R1.2 receipts + FIFO + moving-average with immutable valuation and FINANCE integration.
