# RM-UI-DS-R1 — UI Design System & Frontend Architecture

Status: DRAFT / design only | 2026-11-28
Branch: design/ui-design-system-r1
Baseline: base_dev @ aef9fcae625cf6b3f20650cc1c9ac2c379b9b832
Related design stream: design/intelligent-commerce-r2 (read/reconcile, not merge blindly).

## 1. Mission and boundaries

Build one reusable UI language for RM business operations, Intelligence, Radar, FX Shield and reports in parallel with backend qualification. Reduce the cost of implementing new screens without introducing a second business or analytical engine. This branch contains design specifications, contracts and, after explicit approval, isolated UI prototypes with synthetic data. No production backend, migration, role grant, CI gate or API changes on this branch. No changes to base_dev or main in this package. Existing backend behavior is the source of truth; proposed API changes are tracked as gaps and resolved separately in base_dev. No production admission implied.

## 2. Design principles

- One responsive UI kit for desktop, tablet and mobile. Dense desktop operations remain supported; CSS layouts use a narrow-first baseline, expand progressively, and never introduce a separate business or semantic model.
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

Tokens: primitive → semantic → component. Example semantic names: surface.default, surface.raised, text.primary, text.muted, border.default, action.primary, status.success, status.warning, status.danger, focus.ring. Visual direction and reference palette are approved in §8.8; final semantic token pairs and logo assets remain pending qualification. Density compact/comfortable to be evaluated on real tables.

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

## 8. First technical priority — UI Kit First (2026-11-28)

RM-UI-DS-R1.0K — Minimal Standard UI Kit & Visual Baseline.

This decision supersedes the previous inventory-first sequencing in this section. The wider roadmap remains a backlog, not a prerequisite for creating the kit. The project owner requests a minimal base and standardization before broader frontend architecture. Full API inventory, navigation maps, business page catalog and production integration are deferred. The isolated static prototype is in scope; no framework installation, build pipeline or production API access.

Initial reference assets:
- [Design tokens](ui-kit/tokens.css) — one CSS custom-property source for colors, typography, spacing, radii, control sizes and semantic states.
- [Visual catalog](ui-kit/index.html) — HTML reference with local assets. Starting R1.0K.1 serve the directory over same-origin local HTTP for external SVG use; synthetic data only, no external dependencies or real mutations.

Minimal component standard: use native HTML semantics first; one component name and a small set of variants; default/hover/focus/disabled plus loading and error when applicable. One primary action per action group. Financial values align right, carry explicit currency, and are never recalculated in the visual kit. Status has text, not color alone. A qualified zero is not a missing/unqualified value.

| Increment | Scope | Evidence |
|---|---|---|
| K1 | Tokens, button variants, fields, choice controls | Visual reference and keyboard/contrast review |
| K2 | Table, card, badge, alerts, empty/loading, dialog | Representative static examples and state review |
| K3 | Tabs, pagination, date picker, switch, tooltip, shell basics | Add only when a representative workflow requires them |
| K4 | Extract shared framework components after stack approval | Reuse token names and behavior; no parallel competing kit |

K1/K2 initial reference created; not yet qualified in a browser. Candidate values: light theme, system font, 12/14/16/20/24 px type scale, 4 px spacing base, controls 32/40/48 px. Palette and dimensions are candidates until visual review. No dark theme, advanced charts, bespoke framework or complete page design required for initial kit.

Acceptance: locally open catalog; inspect on desktop and narrow viewport; tab through interactive elements; verify focus, labels, disabled state, dialog close/return focus, readable contrast, status text and synthetic metric states. Record issues before treating the kit as accepted. Add components only on demonstrated reuse need. No full backend qualification is necessary for this static package; production security and API gates remain unchanged.

## 8.1 Responsive UI baseline — mandatory (2026-11-28)

No separate mobile component library. Use the same component contracts, semantic tokens, markup and data. Responsive CSS is part of each component's definition of done. Desktop/tablet/mobile navigation patterns are future shell requirements, not implemented pages.

| Viewport (CSS px) | Layout reference |
|---|---|
| 320–479 | XS: one column; primary actions can fill available width |
| 480–767 | SM: one or more columns when the component container permits; action rows wrap |
| 768–1023 | MD: intrinsic grid, typically two columns when content fits |
| 1024–1439 | LG: intrinsic grid, typically three columns; compact or comfortable density |
| 1440+ | XL: flexible desktop layout; reference catalog remains max 1120px |

Breakpoints are candidate layout thresholds, not device detection. Test intermediate widths and embedded narrow containers; future business grid column counts may vary by content. Avoid fixed component widths, forced page-level horizontal scrolling, and clipping. Do not mask overflow with body overflow-x:hidden.

- Buttons and editable controls: 48px minimum height on narrow viewports or coarse-pointer devices, including icon targets and compact variants; 16px input text baseline for touch contexts; wrap labels. Desktop density retains 32/40/48px variants.
- Forms: intrinsic grid with minimum preferred track 17rem, bounded by available container width; labels, validation and values remain visible. Logical reading/tab order stays consistent.
- Tables: preserve all columns and exact values; use a labeled, keyboard-focusable local horizontal scroll region on narrow screens. No hidden columns or number truncation by default. Future card views are optional, not a second data model.
- Cards and messages: flexible grids; wrap long localized labels; retain currency, precision and data quality state.
- Dialogs: viewport-constrained width and height, internal scrolling and native focus behavior; no off-screen actions. Future drawers and navigation must meet equivalent rules.
- Accessibility: respect text resizing and reduced motion, visible focus, explicit labels/status text; verify 200% text zoom and reflow at 320 CSS px; local table scrolling is allowed for genuinely two-dimensional data. No zoom restrictions.
- Native CSS only in this increment. No frontend framework, responsive JS runtime or added dependencies.

Reference catalog uses shared responsive layout tokens and CSS media queries. Test at widths 320, 375, 480, 600, 767, 768, 820, 1023, 1024, 1280, 1440 and 1920, plus short landscape, coarse pointer, keyboard and 200% zoom. Acceptance requires no document-level horizontal overflow, all table columns reachable, long ru/kk/en fixtures, readable metric values, reachable dialog actions and visible focus. Record browser/device and measured viewport evidence before claiming qualification. Implementation is a candidate; real-browser/device qualification remains pending.

## 8.2 RM Icon System R1 and responsive qualification (2026-11-28)

RM-UI-DS-R1.0K.1 is a candidate component standard, not Production Admission. See [qualification record](ui-kit/QUALIFICATION.md). Reference: [icons.svg](ui-kit/icons.svg), [components.css](ui-kit/components.css), [catalog.js](ui-kit/catalog.js). Own SVG geometry, 24x24 viewBox, 1.75 stroke, round caps/joins, no fill by default, currentColor; semantic color inherited from the control. Size tokens 16/20/24/32px. Icon graphics never replace the accessible button name or the textual data-quality status. Icon buttons use at least 48x48px on narrow or coarse-pointer contexts. Use one stable rm-{name} identifier; never import third-party icon packages or duplicate an existing glyph. No scripts, external references or embedded assets in the sprite. Add domain icons only on demonstrated need. Do not infer backend roles or status semantics from icon names.

Component CSS is separated from the page, grids are intrinsic to available container width, disabled inputs retain their disabled background, and reference controls exercise local demo actions only. Direct file:// loading is no longer the supported preview path for external SVG symbols; use a local HTTP server. Local Chromium in-memory fixture: 15/15 cases passed; actual external asset loading, other browsers and physical devices remain unqualified. All five runtime asset blob IDs were matched to the tested local source files. Test-only Playwright is not a frontend dependency and CI is unchanged.

## 8.3 Application Shell and navigation — R1.0K.2 (2026-11-28)

Reference [shell.html](ui-kit/shell.html), [shell.css](ui-kit/shell.css), [shell.js](ui-kit/shell.js), [qualification](ui-kit/QUALIFICATION-K2.md). Single navigation DOM with desktop sidebar at 1024 CSS px, wrapping tablet navigation from 768px, inline mobile disclosure below 768px. No modal drawer, duplicate mobile menu, framework, production route or permission logic. Header, demo workspace selector, breadcrumb, keyboard-operated tabs, native dropdown, filter form and pagination share existing design tokens and primitives. Selection updates the example heading, breadcrumb and active navigation item. All records are synthetic and shared across demo workspaces; workspace change resets filters and tabs, not a security boundary. Production tenant/auth admission is unchanged. Extended sprite has 56 unique symbols, retaining all original 43 IDs. Browser qualification remains partial; see explicit evidence and remaining checks. 

## 8.4 Business Studio prototypes — R1.0K.3–K.7 (2026-11-28)

[Business Studio](ui-kit/studio.html) groups five prototype increments: K.3 advanced forms, K.4 advanced table, K.5 charts, K.6 page templates and K.7 mock frontend integration. Reuses the K.1/K.2 tokens, base CSS and SVG sprite; no added runtime dependencies or backend/CI changes. See [qualification](ui-kit/QUALIFICATION-STUDIO.md).

- Form: native date and number fields, decimal strings for demo KZT (nonnegative, two fractional digits), range validation, period, checkbox multiselect, switch and local file metadata. No persistence, upload or domain-specific currency policy.
- Table: async mock list, search, identifier sort, current-page selection, pagination, empty/error/retry, confirmation and detail drawer.
- Chart: synthetic nonnegative series, SVG bar/line, gaps for missing values, distinct zero, accessible data table. Fixed prototype scale only; no actual analytics or metric calculation.
- Templates: dashboard, list, form and detail views; separate reference from the K.2 shell. Not production routes or a framework component library.
- Integration: mock list adapter with query/page/sort/workspace inputs and generation guard against stale responses; clear form, selected rows and detail on workspace switch. Demonstration only, not authorization or evidence of backend tenant isolation. Real DTO/API mapping and access control remain pending.

Status: prototype / partial Chromium qualification; 8/8 viewport scenarios in in-memory fixture, no claim of full production or cross-browser qualification. Keep the existing K.1/K.2 qualifications separate; no regression rerun on this increment.

## 8.5 Unified UI Kit — R1.0K.8 (2026-11-28)

[Canonical UI Kit](ui-kit/index.html) combines Application Shell, Business Studio and basic component catalog without new component types or icon IDs. One header, workspace selector, navigation, breadcrumb, registry adapter, filter and pagination. Dashboard/List/Form/Detail/Catalog are hash-linked views, not production routes. Old shell.html and studio.html redirect; superseded implementations and test scripts are retained in Git history at ff3777026d56cc161bfcde930b2320e7ed11cd04. New [qualification](ui-kit/QUALIFICATION-K8.md) and [test](ui-kit/qualify_unified.py) supersede historical runnable qualification for this layout. Prior evidence remains scoped to its tested source. Shared CSS tokens, basic styles and 56-symbol SVG sprite unchanged.

Separate design review was an AI self-review, not an external designer certification. Consolidated behavior, fixed focus and error/empty separation, improved visual hierarchy. 16/16 Chromium in-memory scenarios PASS with exact blob equality, partial qualification only. HTTP external assets, actual browser/device coverage, accessibility, full API/tenant admission and production integration remain open. Freeze component breadth pending visual acceptance. No backend/CI changes.

## 8.6 Modern Enterprise Visual Refinement — R1.0K.9A (2026-11-28)

Visual candidate, not Design Freeze. Retain all existing views, mock contracts, 56 SVG symbols and zero runtime dependencies. Update existing tokens to a neutral slate canvas #F4F6FA, cobalt primary #2855B8, primary hover #1E4395, text #17243A, muted #526179, border #DCE3ED and semantic status surfaces. Radii 6/10/16px, subtle elevation, responsive heading and KPI hierarchy, desktop page gutter 32px, desktop sidebar 15rem. Shared existing system font and focus/touch rules retained. Header mark is a candidate, not an approved product logo. Dashboard uses independent KPI cards and chart panel, visible tick grid and rounded bars; missing vs zero remains unchanged. Registry uses text-bearing semantic badges and less prominent row actions. No business metric, API, role or tenant policy added.

Regression: 16/16 Chromium in-memory scenarios passed, existing test unchanged. See [visual qualification](ui-kit/QUALIFICATION-K9A.md). Actual HTTP assets, Safari, Firefox, physical devices, actual browser zoom, full accessibility/localization and owner visual review remain pending. No backend/CI changes.

## 8.7 Functional motion — R1.0K.9A-M (2026-11-28)

Keep motion subordinate to business work. Existing CSS and tokens define 100ms control color transitions, 160ms dialog/menu entry, 200ms drawer entry, and 650ms loading rotation limited to three cycles. Motion is opt-in via prefers-reduced-motion:no-preference; reduced motion has no transitions or animations and retains static busy indication and status text. No motion on KPI values, charts or table rows, no perpetual decoration, no delayed closing or artificial loading, no JS animation runtime. Keep focus, visibility and request states independent of animation. Do not animate layout dimensions or use transition:all. Exit actions and Escape remain immediate. See [motion qualification](ui-kit/QUALIFICATION-MOTION.md). Candidate values pending visual approval. No changes to business logic, HTML, SVG icons, backend or CI.

## 8.8 Approved design direction — RM Precision / Intelligent Business Guardian (2026-11-28)

The project owner approved the direction: intelligence, protection and ease of use without overcomplication. The normative [RM Guardian visual and UX standard](RM-GUARDIAN-DESIGN-R1.md) is the source of truth for R1.0K.9B and future RM frontend design. RM Precision is the visual identity; Intelligent Business Guardian is the UX concept. Complexity inside, simplicity outside. Reuse the existing unified UI Kit, SVG and Motion System; no parallel component library. Business Control Center, progressive disclosure, evidence-based protection/analytics states, navy/light and deep-slate dark themes are the target. This approval fixes the direction, not final logo assets, exact dark tokens, production integration or Design Freeze. Prior prototype qualification records remain historical. No runtime, backend or CI changes in this decision.

## 8.9 RM Guardian unified prototype — R1.0K.9B (2026-11-28)

Implemented approved direction on the existing static UI Kit: Deep Navy navigation, semantic light/dark theme, comfortable/compact presentation density (desktop fine-pointer only; coarse and narrow controls retain 48px minimum), candidate brand mark using the existing FX Shield symbol, overview protection availability and progressive disclosure of synthetic Intelligence observations, values and source limitations. Preferences are page-local, not persisted or tenant policy. Workspace switches close the two new disclosures while retaining presentation preferences. No production protection check, recommendation engine or business source is introduced. Existing 56-symbol sprite and Motion System retained; corrected disabled variant precedence and dark dialog surface.

Qualification: 16/16 existing unified scenarios + 16/16 Guardian scenarios + 2/2 motion scenarios, all completed exit 0. Sampled contrast checks in both themes: 17 pairs; actual external HTTP resources, Safari/Firefox, devices, full accessibility and RU/KK/EN localization remain open. See [qualification](ui-kit/QUALIFICATION-GUARDIAN.md). Design Freeze is pending owner visual acceptance and remaining required checks. No changes to base_dev, main, backend or CI.

## 9. Open decisions

Frontend library and versions, browser support, mobile scope, initial locales, final qualified theme token pairs and logo assets, charting implementation, BFF/session integration, deployment topology and final page scope. These are proposed design topics, not accepted architecture. Root baseline has no dedicated top-level frontend directory; nested assets not yet audited.
