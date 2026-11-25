# RM B2.1R.1 pre-closure review and B2.2A entry plan
Baseline: af5d5c52ca77635918199f310c99139042ceef07. Observed A1 workflow success; strict PostgreSQL job's test, no-skip, preflight and artifact upload steps successful; final exact-SHA admission not yet observed. New commit invalidates prior exact-SHA admission evidence for the candidate.

## SQ-09
Retain full BUS journey and independent SALES/INVENTORY digest oracle. Add four independently named PostgreSQL JUnit cases, each with fresh real-service fixture: source admission/owner rollback, concurrent replay/rollback, sealing lock orders, tenant source collision. H2 explicitly skips the four PG-only cases instead of reporting false PostgreSQL successes. Strict PG workflow and central verifier require all four names with no skips; H2 skip audit remains subject to existing policy. Case groups still contain several assertions/sub-scenarios, not one XML entry per internal permutation. This is evidence decomposition, not new functionality.

## B2.2A readiness (implementation HOLD)
Reviewed actual production SALES status event INSERT sites at baseline:
| Owner producer | Physical location | Canonical kind | Required change after admission |
|---|---|---|---|
| AutoOrderService.checkoutInternal (ordinary and verified published QR) | line 257 | ORDER_PLACED | allocate event UUID once, INSERT owner source, append same key before receipt/commit |
| AutoOrderService.checkoutAcceptedQuote | line 466 | ORDER_PLACED | same event UUID; append within quote-consume/checkout transaction |
| SalesInventoryCommandService.appendEvent | line 231 | ORDER_CONFIRMED / ORDER_COMPLETED / ORDER_CANCELLED | explicit typed mapping, one UUID, append after source INSERT within confirm/cancel/fulfill |
| SalesPickupHandoverService.complete | line 254 | ORDER_COMPLETED | reuse existing event UUID, append inside handover/credential/stock transaction |
| SalesOrderPreparationService.execute | line 166 | PREPARING / READY_FOR_PICKUP | excluded by existing canonical kind contract; no append |
All line numbers anchored to baseline; source writes and appender must share owner transaction and data source. Do not use after-commit, REQUIRES_NEW, request key as source identity or catch-and-ignore errors. Preserve exact checkout/command replay behavior; no new canonical fact on no-op replay. Backfill/reconciliation of prior sources belongs to B2.4, not retry side effects.

B2.2A proof: independent SQL joins by tenant/owner/kind/source ID; exact new source cardinality and matching fact envelope; actual checkout (ordinary/list/negotiated), confirm/cancel/generic fulfill/QR handover; denied/replayed commands zero additional facts; injected appender failure rolls back entire changed owner ledger (including receipts/quote/reservation/stock/credential/handover as applicable); generic vs QR race and source event uniqueness. B2.2B covers INVENTORY physical writers separately; B2.3 combined business qualification.

Admission prerequisites: current exact SHA, both A1 jobs, strict named PG cases, Domain Security, global verifier PASS and review of scope limitations. SQ-08 scanner remains conservative (not a complete dynamic SQL/external writer proof). No production producers changed; no new migrations or business contract. No independent test execution on this new SHA. A3-QUAL / Production HOLD.
