# B4.8 — Authorized Analytical Access & L0 Fallback

Internal authenticated Java application port. No HTTP endpoints, production-role provisioning, historical admission or ML decisions.

- One typed request for twelve commercial and stock metrics, immutable calendar, historical region/group/SKU, explicit warehouses and currency.
- Fresh READ COMMITTED IAM and warehouse locks remain held during a nested read-only REPEATABLE READ snapshot.
- Resolve pointer, calendar, source revision, independent coverage and materialized values in that same snapshot.
- Route closed covered periods to L3 only with CURRENT proof. Open, absent, stale or out-of-generation periods use owner-source SQL through int_l2_expected_source (not L2 contributions).
- Filter warehouses before aggregation; exact distinct order/line/SKU identities. Stock requires SKU. Monetary values require currency. Booked geography is unavailable.
- Missing observations are not zero. Both routes retain PARTIAL for non-null values pending historical completeness. Future periods return MISSING.
- L0 shares the owner-source view used by the L2 producer; independent mathematical parity is still checked by MultidimensionalCoverageService. Whole-tenant proof re-evaluation may be expensive; no latency claim.
- Qualification: L0/L3 parity on the existing populated journey, denied foreign actor and revoked warehouse scope. Further production authorization and workload qualification remain HOLD.
