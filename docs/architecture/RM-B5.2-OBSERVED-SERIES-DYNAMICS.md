# B5.2 — observed series dynamics and activity

B5.1 initial development package closed at a1260071db5e96402f7c076cec71e45c19df5237: 24/24 workflows, 38 successful checks, one manual-only module-postgresql skip; targeted PostgreSQL 45796875016 (22/22), exact-SHA 45796876098. Production and historical admission remain HOLD.

B5.2 adds a series result alongside the existing pair observation. Reuses DerivedMetricEngine TREND (OLS slope per ordinal), SMA and MetricActivityGate; no new formulas for these metrics. Minimum 3, maximum 12 adjacent closed periods in one authorized snapshot. Equal elapsed duration required for the v1 descriptive trend; unequal months, decades or DST lengths abstain. All READY points required. Internal L0 scope identifier is not an actual generation or proof. Mixed L0/L3 permitted in common snapshot; multiple L3 generations/proofs rejected.

Policy v1 classifies strictly monotonic material steps as UP/DOWN only with matching slope sign. FLAT requires all adjacent steps and endpoint span immaterial. MIXED is not a sustained trend. This describes observed values, not latent demand, statistical significance, structural break, sustainable recovery or forecasts. No numeric probability or business decision. Every outcome BLOCKED. PARTIAL/MISSING currently returned by runtime B4 cannot qualify a trend; no synthetic READY promotion. Existing pair observation contract retained, its sustainedTrend field remains false; the separate trend result describes the series.

Seven independent hand-oracle methods for BS-001/002/003/006/097/102 and calendar duration, plus real PG service negative path inside existing journey. No full family admission; all 112 remain inventoried, not qualified. No schema, dependency, external API or new infrastructure.
