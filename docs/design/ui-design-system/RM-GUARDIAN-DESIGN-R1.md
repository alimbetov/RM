# RM Precision — Intelligent Business Guardian

Decision: approved design direction by project owner, 2026-11-28.
Package: RM-UI-DS-R1.0K.9B | Branch: design/ui-design-system-r1.
Scope: normative visual and UX direction for future RM interfaces; not final asset approval, implementation completion, or Production Admission.

## 1. Product promise and design philosophy

Intelligent control. Protected business. Simple management.

The user should understand what is happening in their business, know which risks have actually been identified, and know what action to take next. Complexity inside, simplicity outside. Intelligence means explainable, evidence-based observations, not visual AI decoration. Protection means clear, truthful control of identified risks and data access, not a promise of absolute security. Simplicity means useful defaults, predictable actions, and progressive disclosure, not removal of necessary information or controls.

RM Precision is the design identity; Intelligent Business Guardian is its UX concept. RM remains the product name. No product rename or external branding launch is implied.

## 2. Visual language

| Semantic direction | Reference color | Purpose |
|---|---|---|
| Deep Navy | #142238 | Calm, confident navigation and brand surfaces |
| Precision Blue | #2855B8 | Primary actions and intellectual/analytical emphasis |
| Signal Teal | #008579 | Restrained secondary analytic series and brand signal |
| Cloud | #F4F6FA | Light workspace canvas |
| Pure Surface | #FFFFFF | Cards and working surfaces |
| Graphite | #17243A | Primary text |
| Secure Green | #176443 | Evidence-supported success/confirmation |
| Alert Amber | #854D0E | Attention and warning |
| Danger | #A52222 | Errors or critical states when warranted |

The reference palette sets direction, not approval of every foreground/background pair. Qualify contrast for both themes, controls, focus and states; adjust shades through semantic tokens when needed. Teal never replaces semantic status colors. No color-only meaning.

Light mode: navy navigation and a clear light working canvas. Dark mode: layered deep navy/slate surfaces rather than pure black; exact semantic dark tokens pending visual and contrast qualification. Both themes use one component tree, one SVG sprite and one state contract. Compact and comfortable density are presentation options, not separate components; preserve touch targets and accessibility.

Use existing system font first; clear typographic hierarchy, tabular numerals for financial values, 4px spacing base, existing 6/10/16px radius direction, restrained elevation, no excessive shadows or gradients, no glassmorphism in working surfaces, no decorative 3D. Do not introduce external font downloads or new UI frameworks as a prerequisite. Use the existing 56-symbol outline SVG family; connected nodes, contours and signals are the brand motif, restricted to brand and module identity. No automatic new logo or replacement icon IDs without review.

### 2.1 RM OYU — Soft Jiek cultural signature (R1.0K.10A)

Approved direction: barely perceptible presence, never an emphasis. Use independently drawn smooth, rounded cubic Bézier curves inspired by traditional Kazakh border ornament (Жиек ою), not the referenced commercial vector. One quiet three-motif decorative strip below the sidebar controls and note, with the existing muted navigation color at 14% opacity; width 156 CSS px maximum. No sharp corners, polygonal line work, metallic or bright accent, ornament animation, semantic meaning or user setting. This is a stylization, not a claim of ethnographic fidelity. Do not add ornaments to KPI cards, charts, tables, forms, alerts, navigation items or behind text. Keep working areas entirely clean. Hide the strip on viewports below 1024 CSS px wide or 720 CSS px high, in forced colors and on print. Decorative SVG is aria-hidden, non-focusable and non-interactive. Existing icon sprite, themes and motion rules unchanged. Future report covers or authentication use requires separate visual review, not automatic reuse. See [targeted qualification](ui-kit/QUALIFICATION-OYU.md).

## 3. Business Control Center

The landing view should answer four questions: what is happening, where attention is required, why a metric changed (only when supported), and what next action is available. Prioritize current state, qualified key metrics, actionable observations and Radar events; do not default to a wall of charts. Show a small role-relevant selection rather than forcing every module into the overview. The exact metric set, permissions, links and source contracts must be verified against base_dev before real integration.

Use one clearly identified primary action per action group. Secondary actions remain available without competing for attention. Alerts have severity, context, affected scope, timestamp/freshness and an authorized path to detail when supported by the real contract. Do not invent counts, scores, recommendations or deadlines. Mock fixtures must be marked as synthetic.

## 4. Progressive disclosure

1. Business state: concise, relevant indicators and material events.
2. Observation: change, comparison and supported explanation or explicitly labeled hypothesis.
3. Analysis: trend, dimensions, filters, comparisons and evidence quality.
4. Source: authorized primary records and provenance, when available.

The same component and data contracts apply at every level. A drill-down must preserve tenant, scope, period, currency and snapshot/revision context where the backend supports it. Do not expose restricted evidence or recompute authoritative metrics in the browser. Back navigation should preserve context when feasible without leaking data between tenants. No required action is hidden solely for visual simplicity.

## 5. Protection and trust

RM Intelligence: understand and explain. RM Radar: identify and prioritize detected events. Smart FX Shield: display evaluated currency risk and policy context. RM Security: display verified security/access state only. RM Reports: organize and present qualified results. These are visual/UX roles, not new backend capabilities or public guarantees.

Show green protection/success only for an actual, applicable, current verified check. No detected critical events is not equivalent to all risks covered or absolute protection. Distinguish checked/no detected event, warning, critical, pending, stale, incomplete, not qualified, unavailable and forbidden as applicable to the real backend contract. Unknown, absent or stale evidence never maps to healthy, safe or zero. Identify the check's scope and last verified time where supplied; if unavailable, state that the status cannot be confirmed. Never imply protection or authorization from an icon, hidden control, color or frontend mock. Tenant and role enforcement remain server-side. No PII or secrets in logs or inappropriate UI surfaces.

## 6. Intelligence presentation

Separate observed facts, derived observations and possible explanations. Indicate comparison period, denominator, units, currency, data quality and provenance/revision where supplied. No unsupported causal statement or automatic financial recommendation. Show true zero only with qualified evidence; missing/unqualified values are visibly different. Keep chart, KPI, table and report consistent with authoritative snapshots; no animated counting, smoothed fictitious trends or fake risk score. Accessible text/table equivalents are mandatory for charts; color alone cannot encode series or state.

## 7. Interaction and accessibility

Reuse current RM Motion System R1 (100ms feedback, 160ms menu/dialog, 200ms drawer, bounded loading rotation) and reduced-motion behavior. No artificial wait, layout movement, perpetual decoration or animated authoritative figures. Keep native keyboard/focus behavior. Responsive by default: one navigation and component tree, touch targets >=48px in narrow or coarse-pointer contexts, no document-wide horizontal scroll at 320 CSS px; genuine two-dimensional tables use a local accessible scroll region. Target WCAG 2.2 AA, not a claim of conformance. Respect RU/KK/EN expansion, locale independent of currency/time zone, and exact money values.

## 8. Implementation plan — one coherent increment

| Package | Deliverable |
|---|---|
| B.1 | Visual identity and semantic tokens |
| B.2 | Light/dark theme and density |
| B.3 | Business Control Center prototype using existing components |
| B.4 | Intelligence presentation consistency |
| B.5 | Risk/protection visual states and truthful evidence |
| B.6 | Progressive disclosure UX |
| B.7 | Responsive components and motion consistency |
| B.8 | Visual consistency and Design Freeze qualification |

Implement in the existing UI Kit; no parallel design system, unnecessary component variants, production business routes, new API contracts or backend changes. Use existing components first. The B.1–B.8 labels are work areas, not eight mandatory sequential architecture gates. Consolidate into a practical implementation package, fix identified defects in place, and qualify once on the actual resulting source.

## 9. Acceptance and status

Direction approved. Implementation and final visual acceptance remain pending. Check the actual desktop/tablet/mobile prototype in both themes and densities; verify contrast, focus/keyboard, reduced motion, text expansion, 320px reflow, status truthfulness, exact values, missing/zero, stale/unknown, mock workspace switching and current component regression. Record tested source SHA and real limitations. Real HTTP assets, Safari/Firefox, physical devices and full accessibility remain open until evidence exists. Verify API/security contracts separately before production. No Design Freeze or Production Admission by this document alone.
