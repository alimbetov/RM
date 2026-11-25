# CORE-RET-04 — E.7A normative review and independent physical lineage

Reviewed exact SHA d14234a5282f1ec625606e8b2b414256894fdeee. Source: RM-BSR-R1-SERVICE-SCENARIO-QUALIFICATION.md §4, §6/6.8, §14.3 and §15; canonical CORE-RET-04 is concurrent quarantine resolution in both orders → one resolution/conserved qty. Do not substitute initial disposition for resolution.

Baseline: [target](https://github.com/alimbetov/RM/actions/runs/45562844017/job/139030445679) 81/81 Java, 56/56 dedicated cases, D24/A24/B8/E6C(v3)56, 21 E6C self-tests; [full](https://github.com/alimbetov/RM/actions/runs/45562844017/job/139030445371) 1007 tests, 0 errors/failures, 24 skipped; [global](https://github.com/alimbetov/RM/actions/runs/45562844121/job/139030446255) ALL_EVIDENCE_READY, 124 Python self-tests, aggregate 3552 test executions/427 skipped/0 errors and failures, semantic HOLD. Not unique test counts.

| Normative requirement | Current proof / gap |
|---|---|
| Both real ordered RESTOCK/DISPOSE races | D RestockFirst/DisposeFirst, PG blocking evidence |
| Duplicate same/different key, stale version, changed payload | D B01–B08, SQL before/after |
| Exception rollback and retry | D B09–B12, SQLSTATE P0001 |
| JVM crash before/after commit and independent JVMs | D C01–C06, exit 86/87 and replay |
| Shared SKU conservation | D C07–C10, two orders/returns, quantity 2+3 |
| Signed access token and negative identity/tenant/warehouse/grant/account/membership | E3A 24; fixtures use real resolver/JWKS |
| Revocation × resolution both orders | E3B 8, permission/scope fence and durable IAM observations |
| Actual registered buyer, publication and verified checkout | E6C v3, 5 registration phases and linked receipts |
| SQL-BSR-07 original handover, posting and allocated capacity | Java assertions/digests exist; E7A candidate independently joins and checks quantities/IDs at each snapshot |
| SQL-BSR-09 no financial side effect | Existing invoice hash stable during resolution; E7A requires zero invoice rows at every return checkpoint. Earlier checkout-to-handover checkpoint not independently exported here |
| A/B/AB actor with different grants | A/B negative controls exist; explicit dual-tenant actor with asymmetric grants for this operation remains OPEN |
| Inactive workspace | E3A covers account, membership and warehouse, not an independently isolated inactive seller workspace variant; OPEN |
| G0/variant manifest metadata and independent reviewer | Existing exact-SHA workflow, method evidence and hashes; full §4.9 crosswalk (fixture prerequisites, isolation, schedules, source, reviewer, etc.) and independent sign-off remain OPEN |
| External email / live IdP / HTTP / physical UAT | Not proved; G2/G3 deferred per scope. Synthetic account, membership and warehouse fixtures are prerequisites, not onboarding qualification. |

E7A adds E6C/v4 physical lineage, independently checks original order COMPLETED, reservation FULFILLED, return line allocation against handover quantity, original posting and immutable intake link, no initial sellable credit, terminal event version/actor, resolution-to-stock movement link and no invoice at checkpoints. Join through tenant, original order, line, handover, posting and intake. Original IDs/quantities remain stable across snapshots. Existing D/E3A/E3B hashes and E6C registration proofs remain mandatory. No production changes, no modified scenario counts or admission thresholds. Candidate must pass new exact-SHA CI; no claim of PASS or EXACT on candidate. No independent reviewer sign-off represented by this document.

Next: finish explicit AB grants/workspace denial and remaining manifest metadata, inspect exact-SHA evidence and obtain reviewer verdict before mapping EXACT. Do not remove current partial-only W6 guard without replacing it with a stronger actual artifact-bound EXACT admission. Global gate retains all other CORE IDs and TASK addenda. Production HOLD.
