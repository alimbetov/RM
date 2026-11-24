# RM-KF-RADAR-R0.1E — completed task timing and original deadline
Qualified baseline d624b2744158a562fafcd0e4a779b7797a517542: 85/85, TASK PG 53/53 H2 35+18. [C3](https://github.com/alimbetov/RM/actions/runs/45448139834). Measured max warm read p95 23.625ms at 100k, mixed reader 24.552ms (15 samples), command p95 baseline 2.433ms, mixed 2.653ms (~9.04% degradation). CI-only evidence, not production SLA. New SHA requires requalification; production HOLD.

Runtime TaskTimingEngine full relation adds scoped event window/aggregation, original deadline reconstruction, exact completion facts and typed safe snapshot. Shared active classification, direction/magnitude/severity reused by optimized overdue projection; history-free path retained. Single statement getMine by composite task/tenant with existing TASK_READ transaction fence and once-truncated Clock; no history backfill, schema change, task writes or raw title/event exposure. Exact SQL classification and magnitude; Java maps and rounds savingsPercent only. Known no original due distinct from unknown; missing/broken original does not override independently valid completion evidence. Structural terminal evidence does not require complete earlier history. Original chain requires contiguous versions, valid action/state transitions, due-change continuity and final due match. Event time is not archive time or commit watermark. Fail closed outside finite time range. Full relation has internal point/non-point parameter shape; calendar filter/page admission not yet implemented.

Ten named service tests per database, pending:
- e01ExactHalfAndAdjacentMicroseconds
- e02EqualityLateAndNonpositivePlan
- e03ArchiveKeepsCompletionNotArchiveTimestamp
- e04ReschedulePreservesOriginalLateDespiteEffectiveGreen
- e05AfterCutoffAndBeforeCreationFailClosed
- e06MissingDuplicateConflictingAndWrongVersionEvidence
- e07ValidCompletionWithoutDeadlineAndIncompleteOriginal
- e08KnownOriginalNullAndBrokenDueChain
- e09ActiveProjectionMatchesOverdueAndScope
- e10RolledBackCompletionDoesNotCreateGreen
Covers threshold (including display rounding), equality/late/nonpositive, archive, reschedules, cutoff, missing/duplicate/wrong evidence, no due, original null/broken chain, composite tenant and active projection parity, rollback. Test-only mutable clock and signed JWT. Full R0.1E qualification still needs broader SQL instrumentation, independent oracle, concurrent terminal visibility and complete CAL trace; no claim of full CAL closure. C3 remains unchanged and reruns; full-relation calendar load needs separate evidence F/D. Target 105/105 (85+20) and TASK regression on candidate SHA. Next F after E gate: calendar range/perspective/date basis/state/timeliness/title code/cursor. R0.2 separately admitted; G2/REST/UI deferred.
