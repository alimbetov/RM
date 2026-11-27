# RM-IC-R2-COR-01 — Single Analytical Engine & DQR Responsibility Correction

2026-11-27 | OWNER AGREED TO RECOVERY AND REVIEW | Q4.2/Q5 HOLD | B4 FREEZE | API FREEZE | PRODUCTION HOLD
Source baseline main 956e1f9c44a58d2951f7c53b853f4dffadf2b64f (B5.4). Selected recovery tree 78a7ec1603935ade7746ef0e82e0a474f1a51ca3 from base_dev 632f803914189be15c18eb2e80852ba44ece1e0a (B5.5R, 24/24 workflows, Exact-SHA 45866826507, targeted 45866826596). Preserve four B5.5 commits after main. Prior DQR tip 712a55d8898a728d1f83e0ca54ec94d700acf4bd is immutable Git reference, not active roadmap. Compare: https://github.com/alimbetov/RM/compare/632f803914189be15c18eb2e80852ba44ece1e0a...712a55d8898a728d1f83e0ca54ec94d700acf4bd (22 commits, 31 files). Restore by new child commit of prior tip with exact B5.5 tree, no reset/force/merge main. Source recovery only, not production admission or DB rollback. No proof of existing deployment DB changeset application; do not run recovered version against databases containing 0222/0223 without separate compatibility decision. Preserve old SQL and evidence in Git; no destructive database operation authorized.

## Preliminary full changed-file inventory by responsibility (review, not deletion verdict)
- Existing B5.5 BusinessConclusionService, ConclusionConcurrencyEvidence, B5.5 docs/tests remain in recovered tree.
- Coverage/oracle candidates: HistoricalCoverageService, BookedSourceReconciliationService, BookedHistoryComposition, BookedMonthlyAuthorityAssessment, IndependentCanonicalDigestVerifier changes; BookedDigestPgEvidence and matching qualification tests. Assess reuse, independent bounded source oracle only; no normal report read path.
- Authority candidates: BookedOwnerAuthorityContract, BookedSourceAuthorityInputs, BookedOwnerDeclarationService, NativeBookedCaptureService, NativeBookedClosure; matching tests and BookedDeclarationLifecycleEvidence, BookedMonthlyJourneyEvidence, NativeBookedCaptureJourneyEvidence, BookedConcurrencyEvidence. Review scope, evidence freshness and minimum necessary source completeness; no automatic readmission.
- Owner-side mutation/guard candidates: AutoOrderService, db.changelog-master.sql changesets 0222/0223, native capture/receipt fences, prospective timestamp. Need source and actual deployment migration inventory before any reintroduction; immutable migration history for applied DBs, no edit/drop without approved forward remediation.
- Integration tests and documentation: BusMvpServiceJourneyQualificationTest, CanonicalDeliveryJourneyEvidence, QrNegotiatedCheckoutQualificationTest, targeted workflow/registry, RM-FINAL-SEC-GATE-DB-ROLE-QUALIFICATION.sql, RM-IE-00B-R2-SOURCE-INVENTORY.json, RM-IC-R2-S1-HISTORICAL-COVERAGE.md. Preserve prior evidence in old commit, select tests by admitted behavior; do not silently drop coverage for retained behavior.
- No conclusion of a second deployed engine: DQR was shadow-only. The drift is separate repeated metric computations and increasingly complex source locking/resource control before aggregate integration.

## Sequential review gates (not new DAG nodes)
| Gate | Deliverable | State at registration |
|---|---|---|
| COR-01.0 | Reversible source recovery and governance links; exact-SHA on restored tree | recovery candidate; CI pending |
| COR-01.1 | Actual file/method/SQL/migration/read-call graph inventory vs B5.5 | initial file inventory above; detailed dependency review OPEN |
| COR-01.2 | Map each DQR capability to existing B1–B5 and classify reuse/retain control/remove/defer | OPEN |
| COR-01.3 | Verify minimum transaction/lock and DB compatibility requirements; no new bulkhead by default | OPEN |
| COR-01.4 | Minimal aggregate/evidence integration proposal, BOOKED semantics, no second engine; acceptance tests | OPEN |
| COR-01.5 | Owner approves concrete diff and deployment compatibility | HOLD |
| COR-01.6 | Implement only admitted changes | HOLD |
| COR-01.7 | Independent mathematical/security/PG/resource evidence on exact SHA | HOLD |
| COR-01.8 | Architectural acceptance, resume existing S1–S6 | HOLD |

First technical question after recovery: how can existing AuthorizedAnalyticalReadService / L3DerivedSeriesService and generation coverage contract supply BOOKED_ORDERS with source completeness evidence without a new calculation path? Compare exact scopes and zero semantics first, no production code before design review. Existing D08, MIC, DAG, separate Radar and production gates remain.


## COR-01.1–01.2 source review — 2026-11-27
[Actual aggregate and DQR responsibility audit](RM-IC-R2-COR-01.1-01.2-AUDIT.md) records full changed-file inventory and inspected methods. Restored base_dev a2fdfb0 matches B5.5 tree; targeted PASS, exact-SHA pending at entry. Critical pre-existing B4 issue: current proof status reruns independent whole-tenant parity for each eligible anchor/metric. No disabling forged-proof protection; B4 change control required. Historical completeness distinct. COR-01.3 next: proof identity/lock/query-cost and migration compatibility review; COR-01.4 minimal integration proposal before owner approval. No runtime changes.
