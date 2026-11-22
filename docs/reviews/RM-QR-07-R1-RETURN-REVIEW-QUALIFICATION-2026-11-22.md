# RM QR-07/R1 — Return Review & Approval, Service-Layer Qualification

**Date:** 2026-11-22
**Branch:** `base_dev`; no new controllers, no `main` merge.
**Qualified exact source SHA:** `ebdd8971195c2ba1bc3205f8ffff83cb6f8e44eb`.
**Dedicated exact-SHA CI:** https://github.com/alimbetov/RM/actions/runs/45042596024
**Admission:** QR-07/R0+R1 dedicated PostgreSQL gate **PASS**; separate full-project same-SHA regression unverified, QR-07/R2 and production remain HOLD.

## Implemented R1 contract

- `SalesReturnReviewService` exposes three commands: `startReview`, `approve`, `reject`; verified actor required, tenant authorization `SALES_RETURN_REVIEW` checked by `TenantAuthorization.requireForCommand` within transaction before any return mutation.
- Service accepts tenant/return UUID, expected revision, request key, explicit methodology version; locks the persisted return row `FOR UPDATE`, checks originating order remains `COMPLETED`, rejects reviewer matching buyer, enforces the `SalesReturnState` transition matrix and optimistic row version.
- Approval is limited to **UAT provisional `UAT_1`** with a 30-day eligibility window after immutable handover timestamp; neither a jurisdictional consumer-rights ruling nor production commercial policy. Unknown policy versions cannot approve; every decision must match the policy version pinned to the request.
- Requests are fingerprinted SHA-256 over actor, return, target state, expected version and policy. Duplicate key/payload returns the original committed decision receipt; reused key with different transition or payload fails. R1 decisions record from/to state, expected version, policy version, actor, hash, key and occurred_at on a PostgreSQL append-only event.
- New Liquibase changeset `0097` adds nullable R1 audit columns to `crm.sales_return_event`; R0 `REQUESTED` events remain valid. Composite tenant FK from R0 preserved; unique tenant/key and consistency constraints introduced. Transaction records decision event and return status/version atomically.
- Original `COMPLETED` order, `sales_pickup_handover`, fulfillment movement, stock balances, and payment are not changed by R1; there is **no refund, physical reception, inspection or inventory restock**.

## New PostgreSQL scenarios

1. Review → approve → exact-key replay of both previous operations; immutable receipt and version stability.
2. Reject a review and release the originally allocated return quantity to a fresh buyer claim without changing original sale.
3. Missing role permission, foreign reviewer, foreign tenant and buyer self-review fail closed before writes.
4. Wrong policy version, stale revision, approval from `REQUESTED` and malformed key denied.
5. Race between two independent threads `approve × reject`, exactly one transitions, another fails with revision conflict and one decision audit entry.
6. Revoking `SALES_RETURN_REVIEW` after first review prevents subsequent approval, with no extra event.
7. Reusing same idempotency key for a different action and direct PostgreSQL event update/delete are rejected.

These are seven R1 test methods added to the existing three R0 methods (ten QR07-focused methods in total). A test method can contain multiple assertions; do not call this a 20+ scenario full R1 gate. Prior R0 exact-SHA PostgreSQL CI https://github.com/alimbetov/RM/actions/runs/45041857312 passed 3 tests at its own SHA and **does not** qualify these subsequent R1 edits.

## Remaining admission and next increments

- Dedicated PostgreSQL 17 R0/R1 suite must achieve BUILD SUCCESS with exact SHA and zero failures/errors/skips. Same SHA broader H2/PostgreSQL workflow must be checked separately.
- Extend business-policy matrix and independent reviewer audit, concurrent role revocation and row fencing, SQL migration tests on populated data, source/reason-specific evidence requirements, and a buyer-facing return status read model, without new controllers.
- QR-07/R2 — warehouse-scoped receive, inspection, disposition and precisely-once original-fulfillment-linked stock posting with crash/retry, concurrent physical scans, return partials, and tenant isolation.
- QR-08 packaging/services only after their own approval and test qualification.
- QR-06B/B3.5 sustainable-capacity admission, external OIDC, HTTPS and physical scanner gates remain separate and unresolved; production HOLD.

**Qualified decision:** Dedicated QR-07 R0/R1 PostgreSQL service tests PASS. No claim of full-project regression or production admission.

## Exact-SHA R1 result and fixture correction

The first candidate job at SHA `e403b1f0cfd60bd0ae3a65824529b0a8af542d02` failed **one R0 test**, while nine other QR07 tests passed. Root cause: the existing partial-return test selected `o.lines().getFirst()` despite the lines being sorted by a randomized SKU ID; the first line can have **3** units rather than **5**, making a requested `2+3` return correctly fail. No production return-state or inventory fix was needed. The test fixture now explicitly selects `sales_order_line.quantity=5`.

The corrected code SHA `ebdd8971195c2ba1bc3205f8ffff83cb6f8e44eb` is qualified by https://github.com/alimbetov/RM/actions/runs/45042596024 :

- `QrWarehouseHandoverQualificationTest#qr07R*` on PostgreSQL 17;
- **10 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**;
- R0 3 tests, R1 7 tests;
- Liquibase 0095/0096/0097 run through Spring Boot test startup;
- immutable decision event and live tenant RBAC negative checks exercised.

**Scope caveat:** Ten test methods establish only tested service behaviors; this is not the complete 20+ case business qualification or the independent full regression. R2 stock movements, external OIDC and production release still HOLD. R1 30-day eligibility and `UAT_1` methodology are provisional, not commercial policy.
