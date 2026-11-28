# RM-UI-DS-R1 — UI Design System & Frontend Architecture

Status: DRAFT / design only | 2026-11-28
Branch: design/ui-design-system-r1
Baseline: base_dev @ aef9fcae625cf6b3f20650cc1c9ac2c379b9b832
Related design stream: design/intelligent-commerce-r2 (read/reconcile, not merge blindly).

## 1. Mission and boundaries

Build one reusable UI language for RM business operations, Intelligence, Radar, FX Shield and reports in parallel with backend qualification. Reduce the cost of implementing new screens without introducing a second business or analytical engine. This branch contains design specifications, contracts and, after explicit approval, isolated UI prototypes with synthetic data. No production backend, migration, role grant, CI gate or API changes on this branch. No changes to base_dev or main in this package. Existing backend behavior is the source of truth; proposed API changes are tracked as gaps and resolved separately in base_dev. No production admission implied.

## 2. Design principles

- Desktop-first for dense business operations; responsive tablet/mobile adaptation with a reduced navigation surface, not a separate semantic model.
- Consistent shell: workspace switcher, role-aware navigation, global search (subject to availability and authorization), notification entry, contextual page header, content, optional detail panel.
- Accessible controls: keyboard navigation, visible focus, labels, semantic HTML, no color-only signals, target WCAG 2.2 AA (verification pending).
- Internationalization from day one: proposed initial ru/kk/en; locale-independent storage and formatting at presentation boundary. Locale, accounting currency, time zone and display preferences are separate concepts; never assume currency from UI language. KZT is a regional default only where tenant policy specifies it.
- Preserve exact financial values and units; no client-side aggregation of authoritative metrics, currency conversion, risk evaluation or permissions decisions. Display backend-provided results and provenance.
- Tenant isolation: tenant-bound API/cache/query keys, cancel stale requests on switch, clear tenant-scoped stores and persisted drafts safely, never reuse sensitive data across workspaces, revalidate authorization; hidden controls are not security enforcement. Never log PII, tokens or financial payloads.
- UI states are contractual: loading, empty, true zero, partial/missing, not qualified, stale, forbidden, conflict, validation, error, retry and success. Map actual backend states; proposed names are not API enums. Unknown values fail safe, not zero.
- Prefer native reusable primitives to a large custom UI framework. Technology candidate React + TypeScript; library choices, versioning, licensing and frontend deployment remain open until inventory and approval. No runtime dependencies introduced in R1.0.

## 3. System layers

| Layer | Responsibility |
|---|---|
| Foundations | Semantic colors, typography, spacing, density, radius, elevation, breakpoints, focus, motion and dark/light policy |
| Primitives | Button, input, textarea, select, checkbox, radio, switch, date/period picker, label, icon, spinner, badge, tooltip, dialog, toast |
| Composites | Form field, money/quantity input, data table, filter bar, pagination, search, tabs, drawer, confirmation, empty/error/skeleton states |
| Business components | Status badge, tenant switcher, order lines, stock/reservation indicator, FX context, metric card, delta, quality badge, Radar item, timeline, report export |
| Page templates | List/detail, create/edit, master-detail, dashboard, analytics explorer, report preview, settings |
| Feature pages | Order-to-fulfillment, inventory, customer, FX Shield, Radar, Intelligence, Reports, IAM/settings; inventory before scope approval |

Tokens: primitive → semantic → component. Example semantic names: surface.default, surface.raised, text.primary, text.muted, border.default, action.primary, status.success, status.warning, status.danger, focus.ring. No final palette or logo approved yet. Density compact/comfortable to be evaluated on real tables.

## 4. Page and component contracts

Each component specification records purpose, inputs, events, keyboard behavior, responsive behavior, all states, localization/formatting, security, examples, acceptance checks and ownership. Each page specification additionally records actor/permission, tenant scope, route, existing API evidence (path/method/DTO/error/pagination), state transitions, confirmation/rollback expectations and fixtures. Never invent an implemented endpoint. Track each API mapping as VERIFIED / GAP / PROPOSED, with backend SHA and source link.

Server-side sorting/filtering/pagination for large datasets; no hidden fetch-all. Stable row identities, explicit loading and retry semantics, safe mutation idempotency and conflict handling per actual backend contract. Destructive actions require context-aware confirmation. Avoid optimistic changes to authoritative stock, finance, security or order lifecycle unless a verified contract supports it.

## 5. Analytics presentation

One Metric Presentation Contract shared with intelligent-commerce design: metric ID, label, value, currency/unit, scale, precision, period/as-of, comparison basis, absolute/relative delta, quality state, data freshness, provenance/revision when supplied, preferred visualization, accessible equivalent table. Show genuine zero only with qualified evidence; partial/missing/unqualified are distinct from zero. Growth from zero baseline is not a fabricated percentage. Negative values and unavailable comparisons have explicit rendering. Use the same server metric snapshot for KPI, chart, table and report where supported; never claim equivalence when snapshots differ. Export uses authorized server contract; browser formatting must not recalculate authoritative data.

## 6. Roadmap

| Package | Deliverable | Gate |
|---|---|---|
| R1.0 | Charter, existing UI/API inventory, persona and workflow boundaries | Review scope and source evidence |
| R1.1 | Design tokens, typography, colors, density and accessibility rules | Approve foundations with contrast and localization evidence |
| R1.2 | Application shell, information architecture, role/tenant navigation | Verify navigation and tenant switch behavior |
| R1.3 | Primitive component specifications and state matrix | Approve keyboard, error, loading and disabled behavior |
| R1.4 | Business components, tables, filters, forms, money and status | Approve representative operational flows |
| R1.5 | Intelligence/Radar/Reports presentation contracts, chart and quality states | Reconcile with RM-ARS and existing metric contracts |
| R1.6 | Page templates and representative mock-data prototypes | Review desktop/mobile, locale and accessibility |
| R1.7 | API mapping, security and frontend architecture admission | Verified endpoint inventory, no unresolved critical gaps, approved stack |
| R1.8 | Design freeze and implementation handoff | Approved component/page catalog, tests, integration plan |

R1.0–R1.6 may advance without waiting for full analytical closure. Real integration requires the relevant stable backend contracts; no mock results may be represented as real. Future frontend implementation branch and merge policy require separate approval.

## 7. Qualification

- Component state coverage, keyboard and focus checks, contrast, responsive layout, long translations and empty/error fixtures.
- Two tenants and differing roles: cache isolation, in-flight switch races, session expiry, revoked permissions, no sensitive data retention.
- Business journey tests: create order → confirmation → fulfillment; inventory/reservation; authorized analytics → report; Radar event → authorized detail. Actual routes/permissions verified before integration.
- Financial and statistical fixtures: KZT/USD as policy permits, negative/large amounts, zero denominator, real zero vs missing, stale vs fresh, late correction and snapshot mismatch.
- Performance budgets to be established from representative devices and data; test large table, repeated navigation and dashboard request fan-out. Do not set arbitrary release thresholds without measurement.
- Future implementation gates: type checks, lint, component tests, accessibility and browser E2E. No existing backend CI gates modified.

## 8. First technical priority

RM-UI-DS-R1.0A — Existing UI/API Inventory & Information Architecture.

Inspect base_dev for frontend assets, API controllers, security policies, DTOs, error contracts, existing locale/metric contracts and report/Radar capabilities. Reconcile with design/intelligent-commerce-r2. Produce screen catalog and role-to-route/API matrix with actual source references, explicit gaps and a minimal MVP slice (order/warehouse/analytics/Radar). No backend changes, no component library installation, no visual theme freeze before review.

## 9. Open decisions

Frontend library and versions, browser support, mobile scope, initial locales, branding palette, charting implementation, BFF/session integration, deployment topology and final page scope. These are proposed design topics, not accepted architecture. Root baseline has no dedicated top-level frontend directory; nested assets not yet audited.
