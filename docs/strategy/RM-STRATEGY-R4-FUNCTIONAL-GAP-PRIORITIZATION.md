# RM-STRATEGY-R4 — Functional Gap Prioritization

**Branch:** `base_dev` · **Status:** priority decision proposal, no feature implementation · **Production HOLD**.

## Purpose and measurement boundary

R3-v1 has **75 explicitly catalogued requirements**, **46 implemented and internally verified**, **29 PLANNED** across **16 assessment contours**. These numbers are a **curated first-wave baseline**, not total CRM/ERP market coverage. R4 retains the 75 IDs and statuses unchanged.

The objective is to reach one sellable, end-to-end **CRM-first commercial workflow** before adding isolated new subsystems. The existing modular monolith remains the architecture. All scores are **ordinal expert hypotheses** (1–5) and must not be represented as measured ROI, delivery velocity or person-days.

## Decision rules

1. Dependency order is mandatory; `rank` is the proposed implementation sequence. A numerical score never overrides an unmet dependency or a security P0 gate.
2. Give precedence to real user sign-in, first-tenant onboarding, seller UI and actual buyer/seller browser E2E. This addresses the principal gap between qualified internal services and market-usable functionality.
3. Maintain separation of IAM, CRM, CATALOG, STOREFRONT, SALES, INVENTORY, FILES and FINANCE ownership. Do not use dual-write shortcuts or fake production connectors.
4. A wave opens only after the previous wave's commercial acceptance evidence is reviewed; narrowly independent security/compliance work may be prepared in parallel but not declared product-ready early.
5. Each new capability requires proper H2 smoke, PostgreSQL business and tenant/race tests, exact-SHA artifact; external OIDC/provider/browser acceptance is separate, and production admission requires explicit independent review.

## Five implementation waves

| Wave | Count | Strategic outcome |
|---|---:|---|
| W0 — First real customer commercial vertical | 5 | External login, tenant onboarding, usable seller surface, browser buyer/seller order and CRM journey qualified |
| W1 — Commercial reliability, migration and evidence | 5 | Object ACL, cross-page reconciliation, catalog import, sanitized audit, encrypted interactions qualified |
| W2 — Financial settlement and typed tasks | 8 | Invoice, payment, reconciliation, order finance, minimal tenant tasks and CRM links qualified |
| W3 — Operations and advanced workflows | 5 | Tasks UX, GEO update, auditor export and separately gated key recovery qualified |
| W4 — Growth and recurring revenue | 6 | Consent campaigns and tenant subscription metering/billing qualified |

## Complete ordered backlog

| Rank | R3 ID | Domain | Wave | Score* | Effort (1–5) | Dependencies (planned) |
|---:|---|---|---|---:|---:|---|
| 1 | IAM-06 | IAM_RBAC | W0 | 52 | 4 | — |
| 2 | ORG-05 | ORGANIZATION | W0 | 51 | 3 | IAM-06 |
| 3 | STO-05 | STOREFRONT | W0 | 46 | 4 | IAM-06, ORG-05 |
| 4 | SAL-06 | SALES_AUTOORDER | W0 | 50 | 5 | IAM-06, ORG-05, STO-05 |
| 5 | CRM-06 | CRM_CORE | W0 | 42 | 4 | IAM-06, ORG-05 |
| 6 | FIL-06 | FILES | W1 | 45 | 3 | IAM-06 |
| 7 | INV-07 | INVENTORY | W1 | 41 | 5 | — |
| 8 | CAT-04 | CATALOG | W1 | 37 | 4 | ORG-05 |
| 9 | AUD-03 | AUDIT | W1 | 45 | 3 | IAM-06 |
| 10 | INT-03 | CRM_INTERACTION | W1 | 41 | 3 | CRM-06 |
| 11 | FIN-01 | FINANCE | W2 | 45 | 4 | SAL-06 |
| 12 | FIN-02 | FINANCE | W2 | 46 | 5 | FIN-01 |
| 13 | FIN-03 | FINANCE | W2 | 46 | 5 | FIN-02 |
| 14 | SAL-07 | SALES_AUTOORDER | W2 | 52 | 4 | FIN-01, FIN-02, FIN-03 |
| 15 | FIN-04 | FINANCE | W2 | 42 | 3 | SAL-07, AUD-03 |
| 16 | TSK-01 | TASKS | W2 | 35 | 3 | ORG-05 |
| 17 | TSK-02 | TASKS | W2 | 38 | 3 | TSK-01, INT-03 |
| 18 | INT-04 | CRM_INTERACTION | W2 | 35 | 3 | TSK-02 |
| 19 | TSK-03 | TASKS | W3 | 26 | 3 | TSK-01 |
| 20 | TSK-04 | TASKS | W3 | 30 | 3 | TSK-01, TSK-02, TSK-03, IAM-06 |
| 21 | GEO-03 | GEO | W3 | 24 | 4 | — |
| 22 | AUD-04 | AUDIT | W3 | 27 | 4 | AUD-03, IAM-06 |
| 23 | KMS-04 | KMS | W3 | 33 | 5 | — |
| 24 | MKT-01 | MARKETING | W4 | 32 | 3 | CRM-06, INT-03 |
| 25 | MKT-02 | MARKETING | W4 | 30 | 4 | MKT-01 |
| 26 | MKT-03 | MARKETING | W4 | 29 | 3 | MKT-02 |
| 27 | SUB-01 | SUBSCRIPTION | W4 | 34 | 3 | ORG-05 |
| 28 | SUB-02 | SUBSCRIPTION | W4 | 32 | 4 | SUB-01 |
| 29 | SUB-03 | SUBSCRIPTION | W4 | 33 | 5 | SUB-01, SUB-02, FIN-02 |

\* Score = `5 × businessValue + 4 × commercialCriticality + 3 × riskReduction − 2 × effortPoints`. It is secondary to the dependency graph and chosen wave; these weights are provisional and require product validation.

## First execution tranche — W0

**Recommended kickoff: IAM-06 → ORG-05 → STO-05 → SAL-06**, with CRM-06 developed after the identity and tenant foundations are admitted. The first deliverable is a demonstrable loop: verified user → tenant registration → catalog / offer management → anonymous or authenticated buyer discovery → order acceptance → seller confirmation → inventory reserve → CRM trace.

Release gates for W0: trusted issuer/JWKS and email verification; explicit tenant context and account/workspace admission; no cross-tenant leaks; seller UI persistence and revocation; browser E2E for both buyer and seller, repeat and failure/recovery; exact-SHA regression; separate security and production decisions.

## Deferrals that are deliberate, not omissions

- FINANCE (FIN-01..04 and SAL-07) follows real orders; fulfillment is not payment and financial booking must remain independently owned.
- TASKS starts after the minimal real customer workflow; interaction-to-task links cannot create parallel task ownership.
- MARKETING requires consent-bound CRM/interaction contracts before any campaign dispatch.
- SUBSCRIPTION requires tenant plan and idempotent metering; billing must rely on qualified payment contracts rather than inventing another ledger.
- KMS-04 is safety-critical but is **not permission to enable rotation**: complete distributed fencing and recovery proof separately, retaining fail-closed configuration until a dedicated gate passes.
- INV-07 remains important for warehouse-scale integrity: existing per-position and MVCC tests do **not** certify a consistent tenant-wide snapshot across pages.

## DoD for each requirement

The next delivery stage must record a contract/owner, DB migration impact, tenant permissions and negative tests, cross-domain failure semantics, method-level JUnit evidence, exactly matched R3 requirement ID, QA-01B PASS on the new SHA, and UAT browser/provider evidence where relevant. Only then can its `delivery` status be reviewed; R4 itself does not alter R3 functional coverage.

## Machine-readable source and gate

- Source: `docs/strategy/RM-STRATEGY-R4-GAP-PRIORITY-v1.json` (29 entries, ordered and dependency-linked).
- Validator: `.github/scripts/verify_rm_r4_priority.py`; negative fixtures: `.github/scripts/test_verify_rm_r4_priority.py`.
- This is **prioritization, not execution or production admission**. All 29 rows remain `NOT_STARTED` / `PLANNED`; **PRODUCTION HOLD**.
