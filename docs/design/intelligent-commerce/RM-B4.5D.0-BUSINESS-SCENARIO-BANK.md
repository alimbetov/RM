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


## 8. Interpretation qualification extension — R1.1 (planned)

The bank must distinguish observations, explanations, predictions and decisions. A correct calculation is not sufficient evidence of a correct interpretation. Synthetic ground truth is held by the oracle, not exposed to the tested interpreter. No causal claim may be inferred solely from correlation. The test contract distinguishes a uniquely identifiable cause under declared assumptions, a set of plausible hypotheses, contradictory evidence, and unidentifiable causes. In the last case, abstention and a request for discriminating evidence are correct outcomes.

### 8.1 Additional 48 interpretation families (112 total families with section 3)

| ID | Observable pattern / contrast | Required distinction or safe response |
|---|---|---|
| BS-065 | Sales fall: demand decline vs stockout | Check availability and lost-demand evidence |
| BS-066 | Sales fall: seasonal trough vs structural decline | Compare like periods and trend |
| BS-067 | Sales fall: channel outage vs customer churn | Check channel health and cohorts |
| BS-068 | Sales fall: late ingestion vs real decline | Check freshness and completeness |
| BS-069 | Sales rise: unit growth vs price inflation | Separate price and volume |
| BS-070 | Sales rise: promotion vs organic demand | Separate temporary and durable effects |
| BS-071 | Revenue rises: product mix vs volume | Price-volume-mix decomposition |
| BS-072 | Revenue rises while gross profit falls | Check unit cost, mix, discount |
| BS-073 | Margin falls: FX vs supplier price vs freight | Identify supported cost drivers |
| BS-074 | Margin rises but cash position worsens | Distinguish accrual, receivables and cash |
| BS-075 | Margin percent rises while gross profit falls | Distinguish absolute and relative metrics |
| BS-076 | FX moves with domestic fixed-price supplier | No automatic FX pass-through |
| BS-077 | FX moves after inventory purchase | Preserve historical valuation |
| BS-078 | FX exposure offset by matched currency receipts | Net exposure, if supported |
| BS-079 | Low stock: high demand vs supply interruption | Separate depletion and replenishment |
| BS-080 | High stock: demand fall vs early delivery | Distinguish temporary surplus |
| BS-081 | High turnover: healthy sales vs stock scarcity | Check service level and availability |
| BS-082 | Procurement increase vs cash constraint | Feasible bounded action or abstain |
| BS-083 | Lower unit price vs excessive MOQ | Total cost and holding risk |
| BS-084 | Supplier delay vs missing receiving event | Data quality before escalation |
| BS-085 | Regional decline vs warehouse outage | Compare unaffected regions |
| BS-086 | Regional growth vs transfer displacement | Avoid counting transfers as final sales |
| BS-087 | Category growth vs reclassification | Historical hierarchy vs current grouping |
| BS-088 | Aggregate growth hides SKU decline | Drill down; prevent Simpson reversal |
| BS-089 | Aggregate margin falls despite segment improvement | Detect mix effects / Simpson reversal |
| BS-090 | Customer churn vs identity merge | Check identity lifecycle |
| BS-091 | Large order vs broad demand increase | Concentration and outlier handling |
| BS-092 | Increased returns vs late return recording | Event time vs recorded time |
| BS-093 | Zero sales vs missing sales feed | No data is not zero |
| BS-094 | Zero observed demand with out-of-stock SKU | Censoring and uncertainty |
| BS-095 | Incomplete period vs full prior period | Like-for-like coverage |
| BS-096 | Currency conversion vs native currency totals | Require approved FX and valuation date |
| BS-097 | Short history vs apparent trend | Insufficient evidence |
| BS-098 | Conflicting sales and inventory indicators | Report conflict, not fabricated certainty |
| BS-099 | Identical observations with two latent causes | Return plausible set / abstain |
| BS-100 | Noisy observations around alert threshold | Stability and hysteresis where specified |
| BS-101 | Crisis shock vs ordinary seasonal variation | Distinguish only when supported |
| BS-102 | Recovery from low base vs sustainable recovery | Base effects and persistence |
| BS-103 | Demand rebound with constrained supply | Separate forecast and feasible fulfillment |
| BS-104 | Inflation and recession jointly | Nominal vs real volume and margins |
| BS-105 | Liquidity shock with profitable orders | Do not infer solvency from margin |
| BS-106 | Multi-region shock vs local anomaly | Scope evidence and avoid tenant leakage |
| BS-107 | Alternative intervention changes outcome | Counterfactual only under explicit model |
| BS-108 | Recommendation becomes obsolete after late event | Invalidate and re-evaluate |
| BS-109 | Unseen combination of known shocks | Generalization or safe abstention |
| BS-110 | Novel regime outside training support | Out-of-distribution warning |
| BS-111 | Sensitive recommendation based on incomplete inputs | No automatic action; request missing inputs |
| BS-112 | No material change / false alarm control | No unsupported recommendation |

### 8.2 Scenario variants and contrast sets

For each applicable family generate a baseline/no-op, positive case, matched symptom with alternative cause, inverse perturbation, boundary case, missing evidence, contradictory evidence, delayed evidence and recovery case. Not every variant is applicable; explicit applicability and exclusion rationale are required. Contrast pairs share nuisance parameters, tenant scope and random seed while changing a declared causal factor; observationally equivalent pairs intentionally hide the discriminating variable. Include matched cases where cause is constant but symptom magnitude changes. Vary sector, size, turnover, season, currency, supplier concentration, warehouse and SKU distribution without leaking synthetic labels into features. Parameter sampling: boundary values, pairwise interactions, constrained random sampling, and selected three-or-more-factor crisis compositions. Do not claim exhaustive Cartesian coverage. Maintain hidden held-out seeds and entire scenario families for future model generalization tests; tuning against the final holdout is prohibited.

### 8.3 Typed interpretation evidence contract (future capability-gated)

Proposed records: ObservationWindow, EvidenceRef, Hypothesis, InterpretationResult, DecisionCandidate, InterpretationOracle. Each result carries tenant, as-of, scope, source versions, metric values and units, coverage/freshness, observation vs inference distinction, candidate causes, supporting and opposing evidence references, missing discriminating inputs, uncertainty, severity, and reason code. Status: IDENTIFIED (only under declared identifiability), PLAUSIBLE, AMBIGUOUS, INSUFFICIENT_DATA, CONFLICTING_EVIDENCE, OUT_OF_DISTRIBUTION, NO_MATERIAL_CHANGE. Hypothesis confidence must be calibrated before numeric probabilities are advertised; otherwise use qualitative uncertainty. Evidence must be traceable to authorized source snapshots; no future observation, fabricated fact or cross-tenant signal. Recommendations separate from interpretation, with prerequisites, constraints, alternatives, expected tradeoffs, validity horizon, invalidation trigger and approval requirement; no automatic purchase or price change authorized. Missing capability is NOT_IMPLEMENTED, not a passing inference.

### 8.4 Independent interpretation oracle and scoring

Oracle stores latent truth, observable facts, identifiability class, accepted explanation sets, required evidence, prohibited assertions, acceptable abstentions, decision feasibility constraints and alternative-action bounds. For identifiable golden cases compare exact expected reason codes and evidence. For ambiguous cases score acceptable hypothesis sets and abstention; do not reward guessing hidden cause. A wrong confident explanation is a critical failure for golden safety cases. Hard gates for implemented capabilities: zero fabricated evidence, future leakage, cross-tenant data, unsupported causal certainty on unidentifiable golden pairs, unsafe action violating explicit constraints, and silent conversion of unknown to zero. Measure cause precision/recall, macro-F1, set coverage, false alarm rate, abstention coverage vs error, calibration (Brier/reliability where probabilities exist), evidence attribution accuracy, and decision regret only where an independent objective/feasible optimum is defined. Model-specific thresholds require later approval using baseline and cost of errors; no arbitrary aggregate score can mask a critical failure. Compare against simple rule and naive forecast baselines. For stochastic models report repeated-seed variation and uncertainty intervals, not single-run accuracy. Report performance separately by crisis severity, tenant size, family and data quality.

### 8.5 Illustrative golden contrast

A: sales units 100 to 60; availability 100%; traffic and conversion fall, no missing feed. B: sales units 100 to 60; availability constrained, independent attempted orders remain 100. C: sales units 100 to 60 with missing stock/traffic/attempt data. A supports demand decline under fixture assumptions; B supports stockout-censored sales, not demand decline; C must abstain from choosing between causes. Purchase recommendation requires lead time, inventory position (on hand + inbound - committed under approved semantics), cash constraints and service target. It is not entailed by the sales change. No actual procurement feature is implemented by this document.

### 8.6 Roadmap extension

B4.5D.0B includes this versioned 112-family taxonomy and contrast generator. B4.5D.0C includes independent interpretation oracle schema and golden anchors; B4.5D.0D includes contrast, metamorphic and missing-evidence tests; B4.5D.0E records capability, identifiability, rejected assertions and evidence trace. Current B4.5D executes only implemented math/data/transaction capabilities (including freshness, historical classification and currency separation); future Intelligence/Radar/Forecast/Procurement releases own behavioral qualification of their respective interpretation contracts. No new runtime module, ML library, production route or autonomous action is approved. B4.5C.7B/B4.4B.3 prerequisites and B4.6/production HOLD unchanged.

## Обязательное дополнение MIC-R1 (2026-11-26)
[RM Mathematical Integrity Contract R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md). Дополнительные обязательные cross-cutting критерии MIC-Q01–Q10; они не заменяют существующие семейства и не считаются выполненными.
