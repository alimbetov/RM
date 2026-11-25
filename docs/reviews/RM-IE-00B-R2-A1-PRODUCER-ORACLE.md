# IE-00B A1 — producer oracle increment
Baseline 0e9acd89e820563f025d98676824f922031262c4. Design 852b993271617d30721d24b3d3b759d17038ca5f.

Independent SQL assertions added to existing BUS and QR service fixtures, with unchanged test method count. The oracle checks all eight source metric quantities across an isolated tenant lifetime, PLACED cardinality, duplicate transition kinds and immutable line/header snapshot parity. No projections, no runtime read API, no new grants or DDL.

| Fixture | PLACED / BOOKED KZT / CONFIRMED / COMPLETED / CANCELLED / IN / OUT / AVAILABLE |
|---|---|
| BUS generic fulfill + cancel | 2 / 375 / 2 / 1 / 1 / 10 / 3 / 7 |
| QR list-price before handover | 1 / 300 / 0 / 0 / 0 / 10 / 0 / 10 |
| QR list-price after restock + write-off | 1 / 300 / 1 / 1 / 0 / 12 / 3 / 9 |
| QR accepted quote before handover | 1 / 160 / 0 / 0 / 0 / 10 / 0 / 10 |
| QR accepted quote after quarantine | 1 / 160 / 1 / 1 / 0 / 10 / 2 / 8 |

Expected results, not execution evidence. Existing strict PostgreSQL workflow selects all three methods. A1 remains HOLD until exact-SHA results, actual clean/populated schema, broader producer/rollback and current IAM scope evidence. No R2-S01 complete claim: the fixture differs from its normative 5000 KZT example. A2/A3 remain open; no production admission.
