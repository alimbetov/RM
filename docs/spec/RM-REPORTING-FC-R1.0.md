# RM-REPORTING-FC-R1.0 — Full Report Inventory & Source Binding Audit
Date 2026-11-28. Source `3d8c05b207b8aa4c91f644420ff69e86a5475eac`, design `2bb85f2e30eb7d07b4f86d18a10e3ab7fdeb4b0c`. INITIAL INVENTORY / SEMANTIC REVIEW OPEN / SERVICE-ONLY / PRODUCTION HOLD.

## Goal and normative scope
Every approved report-requiring scenario must have a qualified report or owner read/export service. New controllers/BFF/frontend integration STOP until service contract freeze and separate transport admission. Existing [SFC](RM-SFC-R1.md), [Freeze](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md), MIC R1+R2, ARS, IE and [P4 integration](RM-REPORTING-RADAR-EVT-INTEGRATION-R1.md) prevail. P4.1A.5-Q → P4.2 after actual GREEN, independent of broader documentation. SFC-C02/C06 OPEN; no implicit MVP expansion, FX_AUTO activation, new external sends, Radar F2H, P6 or gate changes.

## Inventory
[Registry](RM-REPORTING-FC-REGISTER-v1.json): 12 design templates, nine bounded data bindings, three gated diagnostic files, 12 operational candidates. Initial screening of 383 SFC index records, 85 obligations, 50 QR rules, 9 commerce sections, 24 FX requirements/80 variants, 8 Scheduler packages, 26 analytics/FX/EVT sections and 6 RP-J. Overlapping denominators, not a unique scenario count. All candidate links require semantic and scope adjudication.
| Code | Title | Source status | Current bounds |
|---|---|---|---|
| RM-R01 | Executive Business Review | PARTIAL_BOOKED | 24 closed months; 12 display |
| RM-R02 | Sales Performance & Dynamics | PARTIAL_DURABLE_R02 | 24 closed months; tenant accounting policy |
| RM-R03 | Order Lifecycle Analytics | BOUNDED_OWNER | closed month vs prior month |
| RM-R04 | Product & Assortment Intelligence | BOUNDED_OWNER | M/current descriptive D; 500 SKU; 100 warehouses |
| RM-R05 | Regional Intelligence | BOUNDED_OWNER | M/current descriptive D; 4 regional rows; 100 warehouses |
| RM-R06 | Inventory Position | BOUNDED_LIVE | one warehouse, 1–4 SKU; live |
| RM-R07 | Inventory Flow Analytics | PARTIAL_WINDOW | 12 closed months; SKU/warehouses |
| RM-R08 | ABC/XYZ Portfolio Analysis | METHOD_GATED | W3 ABC/XYZ |
| RM-R09 | Customer Activity Intelligence | OWNER_GATED | C1/C2 identity and PII |
| RM-R10 | Anomaly Monitoring | PARTIAL_WINDOW | 6 closed weeks |
| RM-R11 | Factor Evidence Analysis | PARTIAL_WINDOW | 2 adjacent closed weeks, same SKU/scope/revision |
| RM-R12 | Pricing & Currency Intelligence | OWNER_GATED | CUR-R2C owner source |
Historical [PG evidence](https://github.com/alimbetov/RM/actions/runs/46136811714/job/140953836515) d8567749: 106/106; 12 XLSX, 14 charts, 20 tables, 3 source gated; verified SHA256SUMS and artifact 15259693354. Global exact-SHA FAIL on that revision, not current evidence. R08/R09/R12 lack actual metric data. R02 only internal durable dataset/upload/guard, not publisher. PDF, catalog and complete automatic file generation remain open.

## Scenario and metric contract
CAT-04 explicitly calls for migration reconciliation report, AUD-04 for bounded compliant export; scope still review. CRM-SIM-079 export tenant-isolation is a negative invariant. Other operational candidates are not approval of additional XLSX templates. Owner read/history/export may suffice; no standalone artifact inferred is not N/A. Preserve 112 B5 variants, FX/QR and scheduler qualification variants; no one report per test. Procurement/cash/margin/predictive claims require separate authoritative inputs and scope. Each accepted variant records owner, actor/recipient, permission, warehouse/PII scope, metric/grain/version, currency/unit, calendar/period/asOf, source revision, quality, format/trigger, Java/SQL, transaction and evidence. Explicit N/A justification. Missing approved implementation OPEN_GAP; unknown scope DECISION_REQUIRED.

## Integrity and delivery
Shared immutable evidence for XLSX/PDF. Exact distinct at target grain; booked/fulfilled/paid separate; balances not stock flows; no mixed-currency sums, versioned conversion and tenant accounting policy. MISSING/PARTIAL not zero; READY serialization not quality or publication. R04 500 SKU, R05 4 region, R06 4 SKU and selected 2 MiB output bounds: qualify adaptive capacity or explicitly bounded detail and complete totals; never silently truncate. Fresh tenant/grants/warehouse/PII on read, generation, catalog/download/revoke. Verify storage outside transaction; admit/consumer lock/lockVerified in READ COMMITTED; attach+state+required event/intent atomic. PDF separate fidelity/security gate. One existing queue authority, NIGHT/INTRADAY analytical refresh not automatic physical file per slot; qualified EVENT and ON_DEMAND/schedule, bounded capacity, fairness, crash/retry/fence.

## Order of completion
| Slice | Exit |
|---|---|
| FC-R1.0A | fixed SHA inventory and candidate crosswalk; no new PASS |
| FC-R1.0B | semantic scope/artifact/variant review across remaining SFC/design docs, C02/C06 trace, accepted report list |
| Existing P4.1A.5-Q → P4.2 | actual GREEN then R02 atomic authorized publication |
| FC-R1.1 | owner/metric/math contracts, capacity, historical completeness and independent oracles |
| FC-R1.2 | admitted R01–R07/R10/R11 gaps; W3 R08, C1/C2 R09, FX R12 after owner admission, shared lifecycle |
| FC-R1.3 | catalog/PDF per P4, retention/revoke/supersede |
| FC-R1.4 | admitted report jobs/NIGHT/INTRADAY/EVENT/ON_DEMAND, cluster limits/recovery |
| FC-R1.5 | all accepted forms × required formats × scope/actor × lifecycle variants; exact SHA/service freeze |
Reuse RP-Q01..16 and TPL/NIGHT/JOB/INTRA. Independent math, two tenants, revoke, no unauthorized effects, rollback/replay/crash, late facts, charts/PDF fidelity and mixed load. No second gates. 100% accepted reporting scenarios with qualified owner read or artifact, 0 critical gaps/leaks and current exact-SHA GREEN; denominator unresolved. This is initial screening, not exhaustive semantic review or new runtime admission. Other docs/methods and full tests remain open.
