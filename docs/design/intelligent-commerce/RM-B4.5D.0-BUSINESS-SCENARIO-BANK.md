# RM-B4.5D.0 — Synthetic Dataset & Business Scenario Bank

Status: concept approved; technical plan, not implemented or qualified. Baseline: 2bd8c55306247323b6f339157309da6b13baaa40. Scope: test infrastructure only; no production activation, no new business modules. B4.6 and production HOLD.

## 1. Architecture and ownership

A versioned, deterministic scenario bank produces an economic event timeline, independent expected results and a reproducibility manifest. A Java test-only adapter executes supported owner-domain commands against migrated PostgreSQL; privileged fixtures are explicitly marked and used only for setup, negative probes or unsupported historical inputs. Never seed L2/L3 with expected aggregates. No customer PII, external network dependency, live FX feed or production database access. Future modules may consume the same scenario bank via separate adapters. Synthetic scenarios do not prove real-world predictive accuracy.

Suggested test packages: qualification.dataset (profile, seed, manifest, timeline), qualification.scenario (definitions and parameter constraints), qualification.oracle (independent expected values), qualification.adapter (owner commands), qualification.evidence (run manifests). Actual placement follows repository test conventions after inventory. Use fixed Clock, deterministic IDs, ordered collections, canonical decimal encoding, explicit currency/units and zone/calendar version. Separate scenario version, generator version, parameter hash, input hash and expected-output hash. No use of production calculation code in independent oracle.

## 2. Scenario contract

Each versioned scenario has ID, title, domain, severity, assumptions, prerequisites/capabilities, tenant scope, time range, baseline, parameter distributions, ordered shocks (effective time and recorded time), causal dependencies, allowed actions, independent oracle, invariants, tolerance, negative cases, expected recovery, seed, profile and evidence requirements. Store synthetic observations separately from latent demand and future truth. Future truth must never be visible to a forecast at its as-of boundary. Scenario combinations use explicit causal order and compatibility rules, not arbitrary Cartesian products. Report unsupported capability as NOT_APPLICABLE/NOT_IMPLEMENTED, never PASS.

## 3. Initial bank: 64 scenario families

| ID | Family | Primary evidence / eventual behavior |
|---|---|---|
| BS-001 | Stable demand | No spurious trend |
| BS-002 | Sustained growth | Directional demand increase |
| BS-003 | Sustained decline | Directional demand decrease |
| BS-004 | Seasonal peaks | Calendar-aware pattern |
| BS-005 | Promotion spike | Separate transient uplift |
| BS-006 | Structural break | Detect regime change |
| BS-007 | Sparse/intermittent demand | Avoid unjustified precision |
| BS-008 | New SKU cold start | Explicit uncertainty |
| BS-009 | Product substitution | Category vs SKU demand |
| BS-010 | Cannibalization | No double-counted demand |
| BS-011 | Regional divergence | Region-level aggregation |
| BS-012 | Customer concentration | Concentration risk |
| BS-013 | FX depreciation | Future replacement cost increases |
| BS-014 | FX appreciation | Future replacement cost decreases |
| BS-015 | FX volatility | Sensitivity and uncertainty |
| BS-016 | Supplier price increase | Margin pressure |
| BS-017 | Supplier price decrease | Cost opportunity |
| BS-018 | Sales discount | Revenue vs margin distinction |
| BS-019 | Freight cost shock | Landed cost sensitivity |
| BS-020 | Multi-currency sales | No implicit cross-currency sum |
| BS-021 | FX observation missing | Fail closed / explicit unavailable |
| BS-022 | Inflation regime | Nominal vs real distinction where supported |
| BS-023 | Stockout / censored demand | Sales not equivalent to demand |
| BS-024 | Excess inventory | Overstock signal |
| BS-025 | Lead-time increase | Reorder sensitivity |
| BS-026 | Supplier delay | Arrival risk |
| BS-027 | Supplier failure | Alternate sourcing constraints |
| BS-028 | MOQ / lot constraints | Feasible procurement |
| BS-029 | Warehouse transfer | No double count of stock |
| BS-030 | Partial fulfillment | Exact fulfillment grain |
| BS-031 | Cancellation and reserve release | No phantom fulfillment |
| BS-032 | Return / damage | Explicit supported movement semantics |
| BS-033 | Category reassignment | Immutable historical group |
| BS-034 | Warehouse region change | Immutable historical location |
| BS-035 | Empty calendar periods | Complete period manifest |
| BS-036 | Duplicate / replay | Idempotence |
| BS-037 | Late event | Proof invalidation/reconciliation |
| BS-038 | Out-of-order fulfillment | Source parity |
| BS-039 | Dimension-only repair | No invented historical data |
| BS-040 | Concurrent tenant workloads | Isolation |
| BS-041 | Recession / broad demand fall | Stress demand and liquidity |
| BS-042 | Stagflation | Demand down, replacement costs up |
| BS-043 | Currency crisis | FX shock + volatility + supply uncertainty |
| BS-044 | Liquidity crunch | Constrained procurement |
| BS-045 | Credit tightening | Financing constraints |
| BS-046 | Payment delay | Cash-flow pressure |
| BS-047 | Supplier bankruptcy | Supply interruption |
| BS-048 | Logistics disruption | Lead-time / availability shock |
| BS-049 | Regional warehouse outage | Capacity and transfer constraints |
| BS-050 | Demand panic buying | Spike and rationed supply |
| BS-051 | Sudden demand collapse | Excess stock and markdown risk |
| BS-052 | Import restriction | Substitution / availability |
| BS-053 | Energy cost shock | Operating cost sensitivity |
| BS-054 | Combined FX + demand + lead time | Conflicting procurement signals |
| BS-055 | Crisis recovery | Controlled normalization |
| BS-056 | False recovery / double dip | Avoid premature optimism |
| BS-057 | Data ingestion interruption | Stale proof / no false completeness |
| BS-058 | Worker crash mid-transaction | Atomic rollback and replay |
| BS-059 | Concurrent refresh and late facts | Revision fence consistency |
| BS-060 | Forged proof / digest | Fail-closed admission |
| BS-061 | Extreme tenant skew | Hot tenant isolation and latency |
| BS-062 | Extreme SKU/category skew | Exact DISTINCT and rollup |
| BS-063 | Long historical horizon | Calendar and index scalability |
| BS-064 | Correlated multi-region crisis | Cross-region comparison without tenant leakage |

Crisis families use baseline, shock, persistence, response and recovery phases; parameter severity mild/moderate/severe/extreme, with explicit synthetic assumptions, no claimed probability of real crises. Example combined FX shock +15%, supplier price +10%, demand +30%, lead time +7 days, inventory -20% is illustrative only. Include inverse shocks, zero shock, boundary values, contradictory signals, missing observations and regime reversal. Historical booked cost cannot be rewritten by future FX. Do not treat revenue as profit or fulfilled sales as unconstrained demand.

## 4. Qualification matrix and profile targets

GOLDEN 100–1,000 canonical facts; FUNCTIONAL 10,000; STRESS-S 100,000; STRESS-L 1,000,000; SCALE 10,000,000. These are proposed targets, not measured capacity or CI budgets. Functional target up to 10 tenants, 17 oblasts and 3 republican cities, 100 warehouses, 200 groups, 5 hierarchy levels, 5,000 SKUs, 4 currencies and 3 years. Coverage of regional units may be distributed over tenants; never invent current geography. Use existing versioned geo catalog; ordinary city aggregation stays deferred. Test D/W/DEC/M/Q/H/Y, leap dates and zone boundaries, empty periods, unknown vs not applicable, historical hierarchy, exact distinct membership unions, warehouse authorization and multi-tenant identical local identities where schema permits.

Q1 source integrity; Q2 exact math (golden, independent oracle, metamorphic invariants); Q3 response to factors; Q4 predictive backtest; Q5 decision feasibility; Q6 combined stress. Q1/Q2 and applicable transactional Q6 are current B4.5D gates. Q3–Q5 economic behavior, forecasts and recommendation optimization are future qualification only where the corresponding business contracts exist. No new FX, cost, forecast, procurement or financing runtime module is authorized by this plan. Current currency tests check separation and no fabricated conversion; future FX and replacement cost require approved source/valuation contracts. Forecasts require chronological train/validation/test split, rolling-origin backtests, baseline comparison, MAE/WAPE (zero denominator policy), bias, uncertainty calibration and no leakage. Thresholds must be approved per model before admission; not invented here.

## 5. Implementation roadmap

- B4.5D.0A: deterministic generator, manifest, test adapters, capability registry, golden fixtures.
- B4.5D.0B: versioned scenario bank and composition rules, crisis severity and recovery, parameter validation.
- B4.5D.0C: independent oracle, hand-computed anchors and exact membership sets.
- B4.5D.0D: seeded property/metamorphic scenarios, adversarial and concurrency schedules.
- B4.5D.0E: evidence format and CI integration, replay command, profile budgets.
- B4.5D.1: seven-period mathematical qualification; B4.5D.2 concurrency; B4.5D.3 performance. Keep B4.5C.7B and B4.4B.3 prerequisites; no bypass of Exact-SHA admission.

## 6. Acceptance

Each implemented scenario has positive and relevant negative controls, independent expected output, reproducible seed, owner-command/fixture provenance, explicit unsupported capabilities and evidence linked to exact commit. Replaying a seed recreates identical normalized dataset and expected hashes. CI artifacts record profile, scenario versions, seed, SHA, DB version/migration fingerprint, actual row counts by tenant/stream, expected vs actual mismatch details, passed/failed/skipped reason, concurrency schedule, runtime and resource metrics as applicable. No pass for skipped required cases or stale SHA. Golden/functional mandatory CI only after runtime budgeting; heavy profiles in specialized qualification workflows, not silently substituted by smaller ones. Performance gate thresholds set after baseline measurement. All current mandatory cases must pass before B4.6 can be considered separately. No production activation or new runtime feature implied.

## 7. Reuse

RM Intelligence, Radar, Customer 360, Warehouse 360, ABC/XYZ, Smart Demand and Intelligent Commerce R2 may later register capabilities and independent scenario adapters. Scenario bank is a test asset, not a customer-facing economic simulator or a claim of predictive accuracy. Future customer-facing simulation needs separate specification and validation.
