# RM report mockups — MOCK-0.1
Design-only companion files for D06C, D06E.2, D06E.5/5.1/5.2/5.3. Owner-approved Template First direction; **synthetic values only; not production reports, no runtime admission**. All 12 files use a single illustrative tenant, explicit mock quality, Dashboard and Metadata. English technical codes and geography, Russian hints. Native Excel charts and filterable tables; cached chart points match the corresponding values at fixture creation. No business calculations in renderer; example derived values are precomputed. Do not treat mock KPI/owner fields, thresholds or ABC/XYZ classifications as admitted metrics or evidence.
| Report | Workbook | Source readiness | Sheets | Charts |
|---|---|---|---|---|
| RM-R01 | [Executive Business Review](RM-R01.xlsx) | AGGREGATE_BACKED | Dashboard, Dynamics, History, Booked, Summary, Metadata | 2 |
| RM-R02 | [Sales Performance & Dynamics](RM-R02.xlsx) | AGGREGATE_BACKED | Dashboard, Dynamics, History, Booked, Metadata | 2 |
| RM-R03 | [Order Lifecycle Analytics](RM-R03.xlsx) | AGGREGATE_BACKED | Dashboard, Details, Metadata | 1 |
| RM-R04 | [Product & Assortment Intelligence](RM-R04.xlsx) | AGGREGATE_BACKED | Dashboard, Groups, Details, Metadata | 1 |
| RM-R05 | [Regional Intelligence](RM-R05.xlsx) | AGGREGATE_BACKED | Dashboard, Details, Metadata | 1 |
| RM-R06 | [Inventory Position](RM-R06.xlsx) | LIVE_OWNER_BACKED | Dashboard, Details, Metadata | 1 |
| RM-R07 | [Inventory Flow Analytics](RM-R07.xlsx) | AGGREGATE_BACKED | Dashboard, Dynamics, Metadata | 1 |
| RM-R08 | [ABC/XYZ Portfolio Analysis](RM-R08.xlsx) | METHOD_GATED | Dashboard, Details, Matrix, Metadata | 1 |
| RM-R09 | [Customer Activity Intelligence](RM-R09.xlsx) | OWNER_GATED | Dashboard, Dynamics, Metadata | 1 |
| RM-R10 | [Anomaly Monitoring](RM-R10.xlsx) | AGGREGATE_BACKED | Dashboard, Dynamics, Metadata | 1 |
| RM-R11 | [Factor Evidence Analysis](RM-R11.xlsx) | AGGREGATE_BACKED | Dashboard, Details, Metadata | 1 |
| RM-R12 | [Pricing & Currency Intelligence](RM-R12.xlsx) | OWNER_GATED | Dashboard, Dynamics, Details, Metadata | 1 |

## Shared conventions
- Dashboard contains report identity, synthetic status, actual illustrative scope, asOf, unit/metric, hint and limitations; Metadata records range, comparison where shown, source status, visibility and restrictions. All audience data fictional, no live ACL evidence.
- Native chart titles use metric code where registered, with explicit mock labels for gated owner metrics. Data source tables provide precise values; chart caches contain corresponding demo values. Excel filters only filter existing exported rows, not authoritative totals. No arbitrary Excel formulas, macros, external workbook links or business calculation logic added.
- RM-R01/R02 contain 12 aligned current/prior months and separate 24-month History; booked and fulfilled amounts distinct. RM-R03 status counts are not a funnel. RM-R04 has 500 SKU / 7 bands, detail totals reconcile to 100M KZT; other charts illustrate bounded subset of planned blocks. RM-R05 region only (city/branch gated); RM-R06 live-only owner snapshot; R07 flows not balances; R10 illustrative preceding baseline/thresholds, not statistical probability; R11 equal 7-day comparison and no causality.
- R08/R09/R12 are explicitly gated design layouts. Mock ABC/XYZ, customer count and FX values are not admitted runtime metrics. No template ID or metric inventory expansion.
- This is a visual mockup version, not final trusted production workbook binary or full typed binding manifest. Before admission define named range/slot capacity and exact payload bindings, clear all fixture data/caches, validate formulas and charts in supported Excel clients/POI, verify print/PDF fidelity, independent oracle and authorization/file owner. Do not use these fixture binaries directly for tenant publication.
- [Machine-readable catalog](manifest.json) pins exact file digests, sheets, native chart/table counts and applicable design dependencies. Reuse existing TPL/UAP cases, no new test families; local file checks are not runtime qualification.
Implementation order remains RM-R02 → RM-R10 → RM-R11; B5.5 NEXT, B4 FREEZE, D08 OPEN, production and Radar HOLD.

MPC-1 in [D06E.1](../D06E.1-SYNTHETIC-HISTORY-AND-METRIC-PRESENTATION-R2.md) governs future template binding. Current manifest and binaries remain MOCK-0.1; no MOCK-0.2 upload claimed. [Roadmap](../RM-IC-R2-PROJECT-ALIGNMENT-ROADMAP.md).
