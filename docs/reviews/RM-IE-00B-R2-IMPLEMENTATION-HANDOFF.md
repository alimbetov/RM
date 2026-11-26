# RM Intelligent Commerce R2 — IE-00B source admission workstream

Working branch: base_dev only. Design source: 852b993271617d30721d24b3d3b759d17038ca5f. Baseline: 31c8e43ef0a5545dc0f6b9976ff5cc1d1e91a363. PR #9 merged; source tree baseline established. User authorized implementation in base_dev. This does not waive runtime admission.

## Source inspection
Existing Liquibase formatted SQL is src/main/resources/db/changelog/db.changelog-master.sql; last observed changeset 0172-task-radar-active-due-index. Use append-only changesets, no rewrite. Existing owner tables: crm.sales_order, crm.sales_order_status_event, crm.inv_stock_balance, crm.inv_stock_movement, crm.customer_buyer_link, crm.task_item. Initial DDL confirms tenant composite keys; runtime schema and producer parity remain unverified.

The accompanying inventory compares Git blobs from the design trace with the current baseline and preserves the 22 source groups, eight metrics, 88 acceptance family IDs and 40 scenario IDs. Source equality is not PostgreSQL proof.

## Next executable stages
- A1: clean/populated PostgreSQL schema inspection, verified list-price and accepted-quote producer fixtures; owner status/movement/return/CRM/IAM evidence.
- A2: approve slice-specific ownership, calendar, grants, warehouse scope and eligibility. No guessed thresholds or implicit tenant-wide access.
- A3: record independent admission evidence before runtime Java or DDL.
- B1: pure period/metric contracts and authorized owner-side native SQL.
- B2/E1: event contracts and rules; B3/B4 projections; C1/W1 after B1; C2/W2/W3 and E2/E3 according to DAG.
- Radar remains deferred pending separate resumption. Production HOLD.

No runtime Java, changesets, CI policy or design branch changed in this source-inventory commit. Existing normative DAG remains operative.
