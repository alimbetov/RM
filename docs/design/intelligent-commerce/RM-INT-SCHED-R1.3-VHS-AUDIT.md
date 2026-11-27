# RM-INT-SCHED-R1.3 — Vertical, Horizontal & Statistical Audit
2026-11-27 | DESIGN REVIEW / PROPOSED REFINEMENT | OWNER APPROVAL PENDING | RUNTIME OFF | PRODUCTION HOLD

Baseline base_dev = dev/intelligence eeb01b320a5810a654fd84a3cbd99c684f0dd2ca; [Full Exact-SHA](https://github.com/alimbetov/RM/actions/runs/46033329208) 25/25 PASS; targeted 80/80. Design parent 697067cb068a62e5145fc96ca6dfa03da0cce5dc. Scope: R1.3 readiness and upstream/downstream interfaces, not full RM codebase audit or runtime qualification. Read with [R1.3 contracts](RM-INT-SCHED-R1.3-INTRADAY-CONTRACTS.md), [RM-INT-SCHED-R1](RM-INT-SCHED-R1.md), D06C, D06E.5.3 and S1–S6 roadmap. No new roadmap stage. Refine A–D before approval; do not admit runtime or edit B4 without approval.

## 1. Vertical trace and observed facts

| Layer | Actual source / responsibility | Conclusion |
|---|---|---|
| Owner events | SALES order status event, sales_order_line snapshots; INVENTORY movement recorded_at | Different event-time bases; preserve owner semantics |
| Canonical capture / L0 | int_canonical_fact; view crm.int_l2_expected_source | L0 view uses owner rows, not receipt aggregation; eligible PLACED/COMPLETED grouped by tenant/order/type HAVING count(*)=1; ambiguous order events excluded |
| L1 | L1ProjectionService, MetricRegistry | Closed-period materialization; distinct order identity; not intraday source |
| L2/L3 | L2ProjectionService, L3RollupService, MultidimensionalCoverageService | Independent oracle, materialized closed generations; no mutation by intraday |
| Authorized read | AuthorizedAnalyticalReadService.ownerValue/readSnapshot | event_at >= start AND < min(end, transaction_timestamp()), count distinct; RR snapshot; PARTIAL/MISSING |
| Scheduler | AnalyticalLifecycleScheduler, AnalyticalJobRepository, AnalyticalJobService | NIGHT qualified; no INTRADAY claim path yet |
| Statistical inference | DerivedMetricEngine, MetricActivityGate, BusinessInterpretationEngine, BusinessTrendAssessment, BusinessAnomalyAssessment | closed READY compatible windows only; existing point comparison requires same observedAt/revision; do not feed asynchronous partial snapshots into these engines |
| Candidate provenance | FactAvailabilityObserver, FeatureCandidateStore, PredictiveFeatureContract | current-snapshot only; observer time is not first actual commit time; feature candidate requires generation/proof and closed periods; unsuitable as intraday store |
| Report/Radar | D06C, D06E.5.3, S4/S5 design | separate owner/audience/admission; no delivery inferred from job completion |

Runtime statistical derivations are not historical certification: DerivedMetricEngine uses DECIMAL128, population variance, EMA alpha=2/(n+1), OLS slope on period ordinal, FORECAST_NEXT=mean. These are existing descriptive/baseline formulas, not validated forecast accuracy or causal inference. BusinessTrendAssessment rejects unequal-duration totals and BusinessAnomalyAssessment uses descriptive materiality/CV/sigma gates, not calibrated p-values. PredictiveFeatureContract always denies historical training and production inference. Retain all these gates.

## 2. Statistical model v1: census of eligible visible orders, not sample inference

Let tenant t, calendar version c, local day d, scope s, nominal slot k, actual event cutoff a and MVCC snapshot S. dayStart b and dayEnd e from PeriodResolver(D,d,c); b <= a < e. a is DB transaction_timestamp of the read-only RR transaction (event cutoff), not exact commit instant or snapshot visibility timestamp. Record snapshot observed-at DB time at/after first snapshot-establishing query (or explicitly call it read-observed timestamp), source revision in same RR, and durable acknowledgement time separately. Do not claim observed timestamp reconstructs MVCC or knowledge available at nominal slot. Time cutoffs and event times UTC, local labels from stored zone. Reject backwards clock/inconsistent bounds; no clamp/fabricated cutoff.
E(t,d,a,S,s) = set of distinct order_id in eligible BOOKED rows visible in S with tenant t, event_at in [b,a), and exact admitted scope s.
C(t,d,a,S,s) = |E| if nonempty, else null (MISSING) under current contract. PARTIAL for nonnull. Mathematical set cardinality of empty is zero; business result null because absence of eligible rows does not prove zero activity or completeness. Do not COALESCE to zero. Positive count is observed eligible population, not estimate of all true orders; no sampling SE/CI. If source ambiguity is detected, do not label result complete; explicit diagnostic reason and suppress comparative use. Existing view excludes duplicates, line-less orders and nonvisible facts; do not silently change ownerValue SQL. Qualify ordinary owner invariants and independent L0 oracle; source mismatch must not become a confident zero. Any additional per-slot integrity check is a bounded owner diagnostic, not new metric engine; approve if needed after cost review. Source mismatch BLOCKED when proven, otherwise PARTIAL/MISSING.
BOOKED_ORDERS measures unique booked orders, not completed orders, demand, revenue, conversions or customers. Cancellation does not undo PLACED in this contract. Multiple lines/SKUs still one order at tenant grain. Grain and distinct identity must be retained. No implicit geographic attribution to warehouse/region of booked orders.

Never sum cumulative C(a1)+C(a2). New snapshot replaces prior operational display, old immutable outcome retained. Difference Delta=C2-C1 only an observed net revision between comparable scope/day observations, not count of events in [a1,a2) unless immutable monotonic source set, unchanged prior visibility and exact identity conditions proven. Late arrivals in [b,a1) can appear at a2. Data corrections/eligibility changes can decrease count; do not force monotonicity. A true interval metric requires direct owner query of interval with admitted semantics, not subtracting distinct totals across filters. Percentage delta only when baseline >0 and same scope, not approved for R1.3 inference.

Cross-grain: additive currency sums only same currency and basis, stock flows only same SKU/unit or approved conversion; ORDER/LINE/SKU exact distinct union by entity ID, no sums over overlapping groups/warehouses/time. CURRENT_AVAILABLE semi-additive snapshot, not flow sum; ratios use ratio-of-sums with qualified denominator, not average of ratios. These are guardrails for expansion, not admission of extra metrics.

## 3. Point-in-time comparison and statistical policy

| Use | R1.3 status | Prerequisite for future |
|---|---|---|
| Display actual count and cutoff | allowed after runtime admission | PARTIAL/MISSING label, authorized current scope |
| Actual past snapshot retained | internal immutable evidence only | exact payload + current ACL; not reconstructible from revision |
| Compare different daily elapsed times | blocked | equal cutoff/exposure and compatible coverage |
| Compare today 09:17 with yesterday 09:00 or full day | blocked | matched elapsed time or explicitly admitted normalization |
| Day-of-week/holiday baseline | blocked | trusted as-known-at-cutoff series, comparable calendar/scope, sufficient valid observations |
| Trend/anomaly/forecast/decision | blocked | separately qualified historical data and statistical policy |

Future descriptive comparisons may use matched cutoff and day class (weekday/holiday/business regime), same tenant/metric/method/scope/units/calendar semantics, no lookahead and no MISSING imputation. DST: actual duration differs from wall-clock hour labels; if duration differs abstain unless explicit exposure model qualified. A missed slot remains missing, never synthesize prior cutoff from latest rows. Preserve each actual observation's cutoff and source visibility. Existing FactAvailabilityObserver is an upper bound on first observed availability, not first actual visibility; no historic snapshots retroactively minted.
Future optional statistical model (not R1.3 implementation or approval): empirical median of comparable past days; MAD=median(|x-median|); robust scale 1.4826*MAD when assumptions justified; empirical quantile bands with sufficient history and calibration. Use no z-score if MAD/variance zero or low count/insufficient history; count dispersion may be non-Poisson/seasonal, don't apply normal significance or independence assumptions to overlapping cumulative snapshots. Minimum n/thresholds selected by time-ordered out-of-sample calibration, not arbitrary production policy. Evaluate false alarms, precision/recall, coverage, drift by tenant and day class; no random train/test leakage; do not train on revisions unknown at decision time. No ML, MAD implementation, new operator or forecast in R1.3. Existing B5 gates remain.

## 4. Horizontal findings and required refinements before implementation approval

| ID | Severity | Finding / proposed acceptance |
|---|---|---|
| VHS-01 | P0 | Service-token segregation: current ordinary JWT decoder checks expected audience inclusion, not client exclusion; dual-audience token can otherwise pass user decoder. Require service-only audience, exact client/subject, deny service principal on user route even if dual-aud token issued; verify actual issuer mapping and HTTP negative cases. No human login/OWNER privileges or mutation authority. Separate account/credential lifecycle explicit. |
| VHS-02 | P0 | Snapshot semantics: distinguish event cutoff, read-observed time, source revision and ack. RR visibility is not timestamp replay. Preserve existing user API; no reconstruction of historical cutoff. |
| VHS-03 | P0 | Durable pair: registry trigger 0224 ensures live lease on COMPLETED but not result existence/ack. Deferred pair constraint in both directions (COMPLETED iff ack for admitted INTRADAY; exact metadata, parent type, immutable request/result). Reject forged metadata/digest and direct DML bypass under runtime role; digest alone not authenticity. No edit of applied migration; migration preflight for legacy rows. |
| VHS-04 | P0 | Logical slot unique(tenant,type,instant) does not enforce 7/day across changed calendar versions. Pin calendar and scope to first admitted local business day, block additional same-day scheduling on drift until next eligible day or owner reconciliation; no overwrite or extra quota. Calendar change may move civil-day labels; require explicit reconciliation on overlaps rather than assume global date uniqueness. Test DST gap collisions and day transitions. |
| VHS-05 | P0 | Shared claim currently NIGHT only and range nonnull. Explicit type-specific fencing, null-safe immutable scope, shared hard caps. No weakening NIGHT path. Coalesce only PENDING or expired fenced takeover, never live lease. |
| VHS-06 | P1 | FAILED/BLOCKED NIGHT can indefinitely defer intraday; R1.3 keeps current safe defer rule, explicit reason/age/backlog escalation and controlled operator resolution; no silent unblock or skip NIGHT. |
| VHS-07 | P0 | Eligible-view exclusion (duplicate status/line-less) means count != completeness. Qualify independent real-owner oracle, ambiguous source negative, MISSING null; don't upgrade PARTIAL. |
| VHS-08 | P0 | Existing B5 closed-ready comparison cannot accept cross-snapshot PARTIAL; no numeric/statistical or business decision admission; future same-cutoff methodology separately qualified. |
| VHS-09 | P1 | Pool/locks: outer RC + inner RR requires two connections; current 120/180s read timeout and 300s lease are proposals not measured guarantees. Bound lock/token/query timing; compare checkout/read/revoke p95/p99, DB wait and scheduler backlog; no source network/render inside DB locks. |
| VHS-10 | P1 | Payload retention grows 7/day per stable tenant calendar; no auto delete on pilot, explicit bounded tenant count/days/storage stop and retention owner policy before rollout; no report partition/staging duplication. |
| VHS-11 | P0 | Positive DB role least privilege still open; untrusted role denial not sufficient; approve exact role grants and demonstrate authorized allow plus forbidden cross-tenant/forged ack/delete/truncate paths before enablement. |

No new product feature beyond R1.3; fixes belong to same package subject to A–D approval. No change to existing DQR, report staging, retention or ML policy. Real pod kill, industrial workloads and operational security remain R1.4.

## 5. Hand-calculated acceptance fixtures

Illustrative oracle (not observed tenant data), one local day:
- Snapshot 03:07 visible orders A(two lines), B(one line): distinct count 2, PARTIAL.
- Snapshot 06:11 adds C, D; late order E occurred 02:50 but committed after first read: count 5, PARTIAL. Net delta 3 is not three interval orders; direct interval [03:07,06:11) contains C,D only (2). Never sum 2+5=7.
- A cancelled later: BOOKED count unchanged absent source correction. Fulfilled metric independent and not admitted in initial pilot.
- Two groups A,B and A,C: each group count 2, combined distinct 3, not 4.
- No eligible rows: MISSING/null. Duplicate PLACED excluded by current view; explicit ambiguity fixture ensures no completeness claim or false zero. Unknown/missing calendar, wrong tenant, denied actor => no payload.
- Event exactly dayStart included, exactly actual cutoff excluded, event at next day excluded. Delayed run labelled actual cutoff, no fake nominal history; next-slot/midnight drift prevents publication. DST duration mismatch not comparable.
- Two legitimate snapshots same scope different source revisions and asOf remain separate immutable outcomes; cannot pass into BusinessInterpretationEngine as same snapshot.
- Independent oracle SELECT COUNT(DISTINCT order_id) from owner status+line rows with tenant, unique event and half-open cutoffs; compare expected hand set and actual L0 value. Fixture for late visibility must hold source transaction uncommitted while snapshot established and then commit, use deterministic DB barrier, not fake timestamps alone. Qualification of closed-day historical coverage not implied.
- Fault injection on result/ack completion, rollback, retry, exact digest, stale token, mismatched parent/tenant/calendar/scope, dual-audience JWT, role revocation, mixed NIGHT/INTRADAY races and no ACTIVE/L1–L3 writes. Reuse existing 80 cases + necessary new SCH-07 cases. No test count target until concrete registry.

## 6. Acceptance decision

Architecture reuse: favorable. R1.3 calculation: existing L0 sufficient for descriptive initial BOOKED_ORDERS; no new mathematical implementation required. Authority, temporal semantics, durable outcome and operational guarantees: not implemented/admitted. Recommend approve A–D only together with the P0 refinements above. R1.3 must remain default OFF; 25/25 covers baseline R1.2, not R1.3. No live IAM/DB grants, migration compatibility or actual statistical predictive validation performed. Design review and engineering proposals only; implementation package in base_dev after explicit owner decision, no scope guard bypass. 

## 7. Second-pass audit and model reconciliation — 2026-11-27

Actual source rechecked: OidcJwtConfiguration/OidcActorResolver/RmOidcHttpSecurity, UserAccountEntity, TenantRoleService/TenantMemberEnrollmentService actor->workspace locks, AnalyticalJobRepository claim and 0224 trigger, latest int_l2_expected_source, append-only int_source_revision_event, DerivedMetricEngine and PredictiveFeatureContract. No runtime changes. See [contract v1.2 §8](RM-INT-SCHED-R1.3-INTRADAY-CONTRACTS.md#8-re-audit-v12--normative-proposed-clarifications-supersede-conflicting-earlier-wording). Superseding proposals:
| Finding | Reconciliation / acceptance |
|---|---|
| VHS-01 | Durable principal_kind proposal + explicit decoder wiring + both user-route/resolver exclusion, including ordinary audience token for SERVICE subject; IAM change requires approval. |
| VHS-02 | Three separate DB timestamp meanings and revision cardinality; final write linearization, not exact commit wall clock. |
| VHS-03 | One typed result row for fixed BOOKED_ORDERS, no duplicate JSONB; exact count conversion; symmetric deferred pair constraint and role-protected guarded writes; digest is not authenticity. |
| VHS-04 | Pin UTC day interval, same-day and overlapping incompatible calendar deny; seven slots per stable civil day, not rolling 24h. |
| VHS-05/06 | Explicit lock DAG job-first reconciliation, planning must not wait on existing job rows under workspace lock; no silent NIGHT unblock. |
| VHS-07 | Independent eligible-order oracle groups ALL visible PLACED before cutoff filter, handles duplicate outside cutoff. Count is observed census, not proof of completeness. |
| VHS-08 | Future evaluation unit matched cutoff/day class; rolling origin, missingness/abstention, error and alert calibration. No predictive implementation. |
| VHS-09/10 | Pool+query cost including full tenant source revision COUNT; pilot storage stop budget before enablement, no auto delete. |
| VHS-11 | Explicit application vs DB threat boundary: shared login cannot enforce hostile-session tenant authorization; actual restricted role/guarded routines require qualification, no blanket grant. |

Synthetic model verification executed in isolated JavaScript (not RM code, not PG): six assertions passed (first set A/B, second A/B/C/D/E, interval C/D, delta 3, half-open cutoff/tenant, empty -> null), exhaustive union cardinality identity for 256 ordered pairs of subsets of a four-element universe passed. These are math checks only. For illustrative series [92,105,98,110,95,102,100,108], median=101, MAD=5 (correct previous conversation display MAD=4), illustrative median +/- 3*1.4826*MAD = [78.761,123.239], 72 relative deviation -28.712871%; no calibrated rule. Interval semantics [a1,a2), not (a1,a2], consistent with half-open cutoff.
Additional acceptance: future event exactly cutoff; duplicate PLACED outside cutoff; count conversion overflow/fractional/zero rejection; source revision grows on unrelated event with count unchanged; same slot failed ack/commit response recovery; actual DB role direct write denial, user decoder ambiguity negative, service classification lifecycle, role revoke lock, plan/claim deadlock race and calendar windows overlap. Existing 80 cases preserved, new count only after actual registry. No actual statistics on historical tenants, no migration, no source tests, no runtime/production admission. No new roadmap phase.
