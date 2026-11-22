# RM Product Targets & Killer Features — Consolidated Strategy R1

**Date:** 2026-11-22
**Branch:** `base_dev`
**Status:** PRODUCT STRATEGY PROPOSAL / DESIGN ONLY
**Delivery:** service-only; controllers, BFF, UI and production admission remain STOP/HOLD per current development policy. No new killer-feature implementation authorization, schema, API, or branch is granted by this document.

## 1. Purpose, authority, and positioning

RM is one CRM-first modular-monolith commerce operations platform, not three independent applications. Commercial directions share IAM, ORGANIZATION, CRM, CATALOG, STOREFRONT, SALES/AutoOrder, INVENTORY, FILES, AUDIT and eventual FINANCE source-of-truth domains.

The product promise to test: **from customer inquiry or product discovery to a verified order, reservation, physical handover and auditable history with less manual re-entry and fewer discrepancies**. Payment, legal transfer, shipping and refunds have independent proof; SALES `COMPLETED` is not `PAID` or `DELIVERED`.

Authority precedence: `docs/architecture/RM-PLATFORM-STANDARD-R2.md` remains normative. The service-first backlog and the R3/R4 registries own delivery facts. `docs/spec/RM-COMPETE-R1-POST-STABILIZATION-KILLER-FEATURES.md` remains **PROPOSED/PARKED**, and `docs/spec/RM-QR-R1.4-CANONICAL-FUNCTIONAL-TECHNICAL-CONTRACT.md` remains the QR business specification. This document consolidates market targets; it does not supersede technical or security contracts.

## 2. Three segments on the same platform

| ID | Product direction | Initial ideal customer and buyer | Core user job | Initial product boundary | Success evidence |
|---|---|---|---|---|---|
| T1 | RM Business — SMB | B2B wholesaler/distributor or retailer with 1 legal entity, 1–5 outlets and a small sales/warehouse team; owner/operations lead | Capture customer, quote, sell, reserve, hand over, follow up, view stock | CRM + catalog + price/offer + sales + inventory + employee roles + minimal audit; no custom accounting ERP | 2–3 pilots; successful end-to-end transaction, time/actions/error baseline; paid willingness-to-use |
| T2 | RM Network — multi-branch | Trading organization with several warehouses/branches, optionally several legal entities later; operations director | Control location stock, scoped staff rights, transfers and consolidated operational visibility | **Phase N1:** one legal entity, multiple stores and warehouses; **Phase N2:** multiple legal entities only after separate legal, financial, data-ownership and access decisions | Cross-location isolation, reconciliation, scoped access tests, successful network-wide operator UAT |
| T3 | RM QR Commerce | Existing RM seller who needs contactless catalog discovery, bargaining and verified pickup; seller and buyer | Scan stable published offer QR, see card/availability, order or negotiate, seller confirms, buyer picks up securely | PRODUCT_OFFER, publication, bounded public read, checkout/negotiation, secure pickup, return; distinct QR purposes and independent payment/delivery | Funnel conversion scan→order, completed pickup without double debit, repeat-scan robustness, buyer/seller feedback |

Segment sizing and commercial willingness to pay are **hypotheses**, not research findings. Do not use assumed number of stores or benchmark percentages as market evidence.

## 3. One common business journey

`verified seller + tenant → customer / published offer → quote or QR discovery → order (PLACED) → authorized confirmation → atomic inventory reserve → preparing/ready → secure handover → immutable fulfillment evidence → returns / CRM follow-up`.

A public QR card does not require seller CRM authorization for the buyer; private order history requires buyer ownership proof. Agreement on price alone does not reserve inventory or create a paid order. Physical handover cannot silently imply delivery or invoice settlement.

## 4. Killer features to segment mapping (all KF-01..05 remain parked)

| Killer feature | T1 SMB | T2 Network | T3 QR Commerce | Stage / prerequisites |
|---|---|---|---|---|
| KF-01 InstantDeal | PRIMARY — individualized B2B offer-to-order | Useful for key accounts | Reuse quote acceptance logic, not another checkout engine | Wave 1; CRM/price/Sales/Storefront/auth |
| KF-02 Promise Engine | PRIMARY — truthful availability | PRIMARY — location-aware ATP, policy-scoped | Public coarse-grained availability, not stock guarantee | Wave 1/2; inventory and location truth |
| KF-03 Relationship Radar | PRIMARY — next customer action | Regional/team visibility after scope permissions | Seller follow-up after buyer consent and linkage | Wave 2; CRM Interaction/Tasks |
| KF-04 Zero-Entry Order | High B2B value for XLSX/CSV orders | High for regional bulk ordering | Not a first QR gate | Wave 3; FILES, SKU matching, sales draft |
| KF-05 Business Network | Future partner ordering | Strategic intercompany ecosystem; **not equal to branch hierarchy** | Future buyer/seller B2B links | Wave 4; bilateral opt-in, partner agreement, allowlisted projection |

**QR differentiated capabilities** are a distinct existing product vertical, not a fictional KF-06 implemented today: stable QR publication and reprint, public product card, offer negotiation and accepted quote checkout, one-time pickup credential, verified warehouse handover, inspected returns and packaging/service foundations. Each capability's IMPLEMENTED/QUALIFIED state must be checked at its exact source SHA; a spec does not prove code.

## 5. Target adoption and sequencing

- **Stage S0 — secure functional foundation (current priority):** close service E2E for IAM tenant onboarding contracts, CRM customer↔buyer authoritative identity link, catalog→storefront offer, sales order/reserve/handover, warehouse permissions, PostgreSQL tenant/race/migration gates. Maintain HTTP/UI freeze.
- **Stage S1 — T1 pilot candidate:** one seller company with staff, basic CRM, catalog/price, stock, order, pickup, trace; only after separate UI/real OIDC/email/infrastructure gate can a real pilot be approved. Establish actual baseline for 5–10 jobs.
- **Stage S2 — T3 differentiated QR vertical:** integrate stable QR + verified buyer journey + negotiation + receipt/return onto shared S1 order and stock ownership; measure scan→order→pickup and exceptions. Do not duplicate checkout/reservation.
- **Stage S3 — T2 one-legal-entity network:** multiple branches/warehouses, location-scoped authorization, transfers, bounded cross-location reports, reconciliation, delegated staff; only expand to multi-legal-entity after separate decision.
- **Stage S4 — staged KF launch:** independently authorize KF-01/02 on top of proven S1/S2 prerequisites, then KF-03/04, KF-05 last. Their original parked status remains unchanged until gate decisions.

Sequencing describes dependencies and focus, **not** a promise that QR implementation is absent or that stages must be implemented strictly serially. Reconcile each stage against the latest exact-SHA QR qualification records before any release decision.

## 6. Differentiation and validation targets

| Metric | Hypothesis / measurement plan | Evidence threshold to decide |
|---|---|---|
| Core journey completion | New operator creates customer/offer/order, confirms stock and hands over | Successful guided and unguided UAT, including failure paths |
| Manual effort | Baseline against same jobs using current customer tooling | Test ≥30% fewer manual actions in offer/order/import scenarios as an **experimental target**, never a pre-verified claim |
| Availability integrity | Compare availability preview vs confirmed reserve | Zero oversell and no false guaranteed reservation in tested concurrent scenarios |
| QR funnel | Track card view→quote/order→confirmed→pickup, abandonment and reasons | Pilot conversion and repeated use; no invented conversion target |
| Network operation | Location permission matrix, transfer reconciliation and stock ledger conservation | Exact-SHA PG race/tenant/rollback evidence plus operational UAT |
| Commercial | 2–3 candidate pilots in SMB/B2B trading, interviews and pricing experiments | Document adoption, support cost and willingness to pay; pivot if no recurring value |

Avoid unverified competitive superiority statements. Compare identical jobs against chosen Bitrix24/Odoo/Zoho/1C/MoySklad configurations with recorded versions and modules.

## 7. Network boundaries requiring decisions

- Tenant denotes security/data isolation; a tenant must not be casually equated with legal entity, store or holding. **Proposed mapping requires an explicit architecture ADR**, not a silent tenant schema rewrite.
- Model organization/legal entity, branch, warehouse/location and staff resource scope separately; no universal cross-tenant superuser, wildcard grant or cross-tenant FK.
- Same-owner physical warehouse transfer and change-of-legal-owner intercompany movement are distinct business events. The latter requires separate legal/finance/settlement qualification.
- Consolidated reporting only via authorized projections, with purpose/tenant/legal-entity access; no direct bypass of domain permissions.
- Business Network KF-05 is partner opt-in exchange between independently isolated organizations; it is not an IAM shortcut for a holding company.

## 8. Gates and non-goals

Gates: domain completion, exact-SHA H2+PostgreSQL CI, populated migrations, actor/tenant/object ACL and revocation races, idempotent retry/rollback, real infrastructure/provider recovery where relevant, UX journey proof, commercial pilot evidence, separate production admission.

No new microservices, standalone ERP accounting, tax engine, universal workflow DSL, global tenant data visibility, or new ML/Redis/OCR dependency. No controller, public API, feature flags, SQL tables or Java classes based solely on this strategy. Current QR and CRM service work remains governed by its own existing task approvals.

## 9. Immediate management decisions

1. **Recommend T1 as commercial beachhead**: B2B trade/distribution with first one-entity seller journey.
2. **Recommend T3 as strongest differentiation** leveraging the same catalog→order→warehouse chain, with independent proof for public QR and physical handover.
3. **Recommend T2 as expansion**: first multi-location within one legal entity; intercompany later.
4. Keep KF-01..05 PARKED until their own `IMPLEMENTATION APPROVED` decision; no implicit authorization by this consolidation.
5. Keep `base_dev` sole development branch, `main` unchanged, and production HOLD.

## 10. Source index

- `docs/spec/RM-COMPETE-R1-POST-STABILIZATION-KILLER-FEATURES.md`
- `docs/strategy/RM-STRATEGY-R4-FUNCTIONAL-GAP-PRIORITIZATION.md`
- `docs/strategy/RM-PROJECT-KNOWLEDGE-AND-CRM-SERVICE-ROADMAP-R1.md`
- `docs/spec/RM-QR-R1.4-CANONICAL-FUNCTIONAL-TECHNICAL-CONTRACT.md`
- `docs/spec/RM-COMMERCE-INVENTORY-COMPLETION-R1.md`
- `docs/reviews/RM-SVC-FUNCTIONAL-DEPTH-AUDIT-2026-11-22.md`
- `docs/architecture/RM-PLATFORM-STANDARD-R2.md`
