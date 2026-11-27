# RM-ARS-R1 — Vertical, Horizontal, Mathematical & Transactional Design Qualification

2026-11-28, Asia/Almaty | DESIGN CORRECTED / RUNTIME GATES OPEN | PRODUCTION HOLD

Implementation source: `base_dev` at `2113d898fa6388926286233ea09275f27b16841f`.
Design input: `design/intelligent-commerce-r2` at `f42898a8cccc45d2978c130106fe8c36ec865a65`.
Authority: [RM-ARS-R1](../RM-ARS-R1-TENANT-AWARE-ANALYTICAL-REPORTING-STORAGE.md), owner-agreed [D06E.5.3](D06E.5.3-PARTITIONED-REPORT-STAGING-ARTIFACT-LIFECYCLE-R2.md) and [D06E.5.2](D06E.5.2-TENANT-REPORT-OWNERSHIP-METADATA-TEMPLATES-R2.md).

The corrections are incorporated in these contracts, not deferred as recommendations. This review preserves one analytical engine, daily staging, private existing File Service, existing S1–S6, report IDs and acceptance families. Optional long-retention archive remains optional/unadmitted. No Java, migration, runtime test, dependency, workflow, base_dev or main modification. The Python reference artifact below is executable design documentation, not an application test or release gate.

## 1. Source evidence and verdict

| Evidence at implementation SHA | Observed behavior | Design consequence |
|---|---|---|
| [AuthorizedAnalyticalReadService](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/intelligence/AuthorizedAnalyticalReadService.java#L64), readMetricWindows | ≤12 anchors, ≤3 distinct metrics, one scope, outer RC admission and inner REQUIRES_NEW read-only RR; rejects caller transaction | No 24M guarantee from concatenation; admit bounded bulk plan independently, budget both connections |
| Same source, result()/ownerValue() | PARTIAL/MISSING even for L3 parity; exact DISTINCT; no rows does not produce qualified zero | Preserve quality; storage READY is not historical completeness |
| [L3DerivedSeriesService](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/intelligence/L3DerivedSeriesService.java#L80), admit()/readPeriods() | Owner permissions and sorted warehouse assignment checks; set-based period read exists | Reuse mathematical engine behind an admitted authorization envelope, not call package-private raw readers as a shortcut |
| [TenantAuthorization](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/iam/TenantAuthorization.java#L80) / [WarehouseStaffScopeService](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/iam/WarehouseStaffScopeService.java#L58) | Actor -> workspace command fences; location -> assignment, transaction-required checks | Recheck after waits; current report audience/whole actual scope must join existing IAM order |
| [DomainPermission](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/iam/DomainPermission.java) | FILES permissions exist, proposed REPORT_* absent | Report permission names remain design contracts; do not fabricate authorization |
| [FileOwnerAccessPort](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/files/FileOwnerAccessPort.java) / [FileAttachmentService](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/files/FileAttachmentService.java) | CRM_CUSTOMER only, IAM -> owner -> file locks; binary read rejects cryptoVersion != 0; synthetic-only production caveat | Explicit report owner dispatch, scanner/crypto integration and revocation qualification required; never impersonate CRM owner |
| [AnalyticalLifecycleScheduler](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/intelligence/AnalyticalLifecycleScheduler.java) / [AnalyticalJobRepository](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/src/main/java/kz/rm/intelligence/AnalyticalJobRepository.java#L118) | One bounded local worker; NIGHT/INTRADAY claim types, DB leases and tokens | Coordination pattern is reusable, existing job authority is not already a general report queue; no competing scheduler |
| [Q2-FINAL](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/docs/reviews/RM-INT-SCHED-R1.3-Q2-FINAL.md) | P1–P8 evidence checklist, not evidence report | ARS design work cannot close Q2-FINAL or assert current Exact-SHA GREEN |
| [Targeted PostgreSQL workflow](https://github.com/alimbetov/RM/blob/2113d898fa6388926286233ea09275f27b16841f/.github/workflows/rm-intelligence-targeted-pg.yml) | postgres:17 fixture | Review PG17 semantics; verify actual deployed version at runtime admission |

Search of implementation `src/main` found no `int_report_`, `MicroReportPayload` or `REPORT_READ`/`REPORT_GENERATE`. These remain designed contracts, not available production APIs. Exact source blob inventory is recorded in the existing [qualification trace](D06E-QUALIFICATION-TRACE-R2.json), under `ars_design_qualification`; its separate historical top-level baseline is preserved.

| Dimension | Design review result | Remaining evidence |
|---|---|---|
| Vertical | Request -> authorized source -> immutable dataset -> rendered files -> publication -> cleanup boundaries specified and corrected | S1/S2 source/bulk, S4 renderer, S5 owner/file integration |
| Horizontal | Tenant, IAM, files, queue, DDL, pool, recovery and retention interactions reconciled | Real roles, revoke races, mixed workload and FK/trigger lock graph |
| Mathematical | 12 independent reference vectors executed successfully | Exact RM/SQL/template parity on qualified inputs, not inferred from these examples |
| Transactional | State/lock protocol corrected; 720 atomic model schedules checked | Real PG multi-session and ≥2 application pods with crash/recovery, no model-to-runtime PASS inference |

## 2. Findings corrected in the contracts

IDs below are review findings/subcases, not new acceptance families or development stages. P0 means mandatory before the affected implementation is admitted; it does not assert a deployed runtime defect. Existing integration gaps are retained explicitly rather than relabelled implemented.

| ID | Priority | Gap or ambiguity | Incorporated correction | Existing gate |
|---|---|---|---|---|
| ARS-DQ-01 | P0 | Long-window extraction could be mistaken for concatenation of bounded reads | One admitted full-plan snapshot, complete version manifest, no scalar-revision equivalence shortcut | CC-02, UAP-04/10, S1/S2 |
| ARS-DQ-02 | P0 | Dataset READY could imply qualified analytics or zero | Separate storage integrity, analytical quality and publication; no renderer quality upgrade | CC-02/10, UAP-04/08, S1/S4 |
| ARS-DQ-03 | P0 | Worker takeover could append a new snapshot to partial old rows | dataset_id per extraction; source loss starts new dataset, READY consumer retries preserve bytes | CC-11, JOB-01/02, S2/S5 |
| ARS-DQ-04 | P0 | Count/digest validation could race append or validate only its own truncated rows | Freeze VALIDATING, independent expected manifest, bounded reads/pin, atomic guarded READY | CC-02/11, UAP-10, S4 |
| ARS-DQ-05 | P0 | Publish and terminal abandonment had no explicit common CAS | Same consumer row; PUBLISHED_PENDING_ACK and receipt in T8, separate idempotent T9 ACK; abandonment cannot erase success | CC-10/11/12, JOB-01/02, S5 |
| ARS-DQ-06 | P0 | Pre-lock lease checks, new consumers or empty consumer sets could permit unsafe deletion | Post-lock DB time, token-specific renewal, fenced registration/holds, explicit zero-consumer disposition, conjunction across tenants | CC-11/12, JOB-02, S4/S5 |
| ARS-DQ-07 | P0 | Report permission/file owner proposals are absent from code | Explicit IAM/owner adapter contracts, whole-artifact scope, current audience revocation, no CRM or direct-child bypass | CC-10, SEC-02, S5 |
| ARS-DQ-08 | P1 | Per-day fence could be read as per-day DDL isolation | Parent ACCESS EXCLUSIVE contention, bounded maintenance, timeout rollback, actual relation/FK graph required | CC-11/12, PERF-02, S4/S6 |
| ARS-DQ-09 | P1 | Ten report jobs could be interpreted as ten DB connections | Admission budgets nested connections plus operational headroom and staging/renderer memory; target is not runtime setting | PERF-02, S2/S6 |
| ARS-DQ-10 | P1 | Retention scalar/lease expiry could hide pending ACK/hold/orphan or override old gates | Formal eligibility incl. unconfigured grace; conservative recovery; historical NEXT labels clarified without changing admission | CC-12, JOB-02, S5 |
| ARS-DQ-11 | P0 | Mathematical invariants lacked concrete adversarial examples at report boundary | Distinct union, ratio/weight, population variance, cumulative correction, null/zero, currency, rounding, calendar vectors | CC-02/10, UAP-04/08, TPL-02/05 |
| ARS-DQ-12 | P0 | Verified file refs could become stale; orphan GC could race publication | Current file-row checks at publication; immutable receipt, original timestamp, reference-fenced orphan cleanup, retry identity | CC-10/11, SEC-02, JOB-01/02, S5 |

## 3. Vertical and horizontal invariants

1. Every request, attempt, consumer, pin, file binding and lookup carries tenant. Composite FKs enforce tenant equality. Cross-tenant identical IDs, keyset row numbers, report names and idempotency keys are negative fixtures. Cache entries include tenant, actual scope/evidence and policy semantics; current authorization precedes every cache/file response.
2. Requested scope, actual frozen scope and current permitted scope are distinct. Whole artifact access requires all actual data authorized, including hidden workbook data and metadata. Narrower scope requires a separate payload/file. Audience changes do not mutate payload digest. List/count/search is an authorization operation too.
3. A report request has immutable method/template/renderer/format/scope/period plan and canonical idempotency digest. Different digest on the same tenant/key is conflict. Explicit regeneration is a new request/instance with supersedes relation. A retry of an already published instance returns the same authorized outcome; it does not move publication time or retention.
4. A dataset spans one allocated UTC staging_date; business periods, actual_as_of and tenant calendar are separate. Rows sort by deterministic ordinal, not database insertion order. Pages bind tenant/date/dataset and `row_key > last`, bounded limit; no OFFSET over a mutating dataset. Source extraction order must itself be deterministic before ordinal assignment.
5. Expected manifest comes from the complete admitted extraction. Observed manifest comes from frozen persisted rows. Canonical encoding has versioned field order, length-delimited UTF-8 or an equally specified unambiguous format, explicit null markers, canonical decimal/UTC timestamp precision, scope and row count. Payload digest excludes its own digest and file digest; binary file digest is separate. Merely hashing a truncated staged set cannot establish completeness. ARS §9.2 now specifies encoding v1 and golden bytes M12; implementation must match that contract.
6. Short staging transactions can follow closed bounded source extraction; loss of the only complete in-memory payload before sealing triggers new extraction identity. Streaming with an open source snapshot needs separate measured budgets and full-snapshot-loss recovery. No mixed source read on page retry. A revision number is evidence metadata, not a historical database snapshot.
7. A READY dataset may contain PARTIAL/MISSING cells according to the admitted descriptive contract. Any block requiring analytical DataQuality.READY/qualified zero remains blocked until owner coverage evidence passes. Dataset completeness, business completeness, method eligibility and report state have separate types/fields. Existing DataQuality uses READY/PARTIAL/MISSING/STALE/NOT_MEASURABLE; do not introduce a fictitious COMPLETE enum or confuse DataQuality.READY with dataset lifecycle READY. Optional unavailable block is explicit; mandatory block failure cannot silently publish a complete-looking file.
8. Files stay private and unavailable through user report access before publication. T8 verifies current File Service state under the admitted file protocol. Upload/scan/attach is not publication. Unpublished orphan GC and publication must serialize on file/reference lifecycle state; GC marks a file for deletion only when no committed publication/reference can be added, then deletes via existing retryable File Service. Network deletion is outside DB transaction. Unknown completion blocks recovery/cleanup, not guesses success. Automatic deletion never removes a committed referenced artifact.
9. Reuse existing coordinated lifecycle; analytical job type/identity admission does not confer report authority. Do not run rendering or partition DDL inline in the bounded analytical compute transaction. Report queue integration and resource sharing must be admitted through the existing mechanism before adding job types. No new independent scheduler.
10. DDL/retention role uses trusted parent/date routing; application cannot invoke arbitrary DDL or bypass tenant-scoped reads through child access. Where SECURITY DEFINER is chosen, require fixed safe search_path, schema-qualified objects, explicit EXECUTE grants and no PUBLIC execute. Actual routine/role inventory remains runtime qualification; RLS is not assumed present from a design sentence.

Resource contract: at the current nested analytical read boundary each simultaneous extraction can hold an outer IAM connection and an inner snapshot connection. Reserve both before admitting work or otherwise prove starvation-free scheduling. For a configured pool budget P, require `2 * concurrent_extractions + reserved_other_peak <= P` for this topology, with measured reserve for commands/revocation/maintenance/recovery; streaming sinks may add another connection and must be counted. This is a necessary capacity bound, not a latency proof or proposed pool increase. Cluster caps must also account for per-pod pools. Existing report concurrency 10 is a proposed logical ceiling, not permission to start ten extractors on an unqualified pool. Measure queued wait, RR age, IAM lock duration, renderer heap/temp disk and bounded row budgets. No source transaction during upload or chart conversion.

## 4. Mathematical reference vectors

These synthetic fixtures specify expected semantics independent of RM Java/SQL. They do not admit new metrics or replace method-specific tolerances and golden bytes. Currency/basis/scope matching is required before arithmetic; exact decimals for amounts/counts/ratios, approved numerical tolerances for non-exact operators.

| Vector | Input and preconditions | Expected result / rejected shortcut |
|---|---|---|
| M01 | Category A orders {o1,o2}, B {o1,o3} | Union 3; sum of category distinct counts 4 is wrong |
| M02 | Matched amount/count groups (100,1), (900,3) | Total average 1000/4=250; average of averages 200 is wrong |
| M03 | Value/weight (10,1), (20,3) | Weighted mean 17.5, not unweighted 15 |
| M04 | Complete values 2,4,4,4,5,5,7,9; population convention | Mean 5, variance 4, stddev 2, CV 0.4; sample variance 32/7 is a different method |
| M05 | Same-day cumulative observations 4 -> 7 -> 6, compatible scope/cutoff provenance | Latest value 6, not sum 17; correction -1 does not prove a negative new-order count |
| M06 | UTC publication A 2026-11-27 08:00, B 2026-11-28 10:00 | Earliest time 2026-11-29 10:00 UTC if all guards pass; one microsecond earlier fails; live pin/hold overrides time eligibility |
| M07 | No observation; numeric zero without coverage; numeric zero with qualified coverage | (NULL,MISSING), (0,PARTIAL), (0,READY) remain distinct; current read port does not manufacture third case |
| M08 | Exact 0.004+0.004; illustrative HALF_UP 2dp presentation | Raw 0.008, displayed total 0.01; displayed rows sum 0.00, so do not recompute authoritative total from formatted cells |
| M09 | New York 2026-03-08 and 2026-11-01; 2026-11-27 21:00 UTC in Almaty | Civil days 23/25 hours; Almaty date Nov 28, staging date Nov 27; elapsed 24h retention distinct from local-day duration |
| M10 | Same source revision 7, generation G1 vs G2 | Scalar equality is insufficient to establish equal evidence manifests |
| M11 | Zero denominator or incompatible currency/population | Explicit undefined denominator or scope mismatch; no infinity/zero or mixed-unit result |
| M12 | Canonical dataset v1 header and one exact typed amount row, specified in ARS §9.2 and the executable reference | 484 bytes; SHA-256 `f54eae406408e614c77b222932179052d01819dbaa7757d2e0f2470601293995`; null differs from zero, framing prevents concatenation ambiguity |

Additional runtime fixtures: period half-open exact boundaries; order across SKU/region ancestors; missing historical period vs closed zero; late event changes source and grouping; weighted basis/zero/negative weights per admitted method; no currency conversion without qualified policy; formula injection; chart caches and visible tables match the same payload; ratio denominator excludes neither hidden population nor missing observations silently. Table filters never silently change published totals.

Reproduce design examples:

```sh
python3 docs/design/intelligent-commerce/qualification/rm_ars_r1_reference_oracles.py
```

Executed locally: 12/12 reference vectors passed. All 720 permutations of publish, retry, abandon, ACK, time advance and drop passed the finite model invariants; 152 end with safe drop, 480 with publication, 240 with abandonment. Counts overlap by disposition: drop is not a third terminal category. Preconditions: one initially active authorized consumer, atomic guarded transitions, no pins/holds, 24h abandonment grace only for that fixture. This does not model real locks, stale tokens, concurrent FK actions, upload/scan or actual pods. The trace records results and script SHA-256 for reproduction.

## 5. Transactional qualification

Refined states (logical fields in existing proposed metadata, no mandatory new service/table per state):

| Entity | Allowed path | Forbidden path |
|---|---|---|
| Dataset build | BUILDING -> VALIDATING -> READY; unpublished failed build -> controlled ABANDONED | VALIDATING/READY -> append; restart source snapshot inside same partial dataset |
| Consumer | REGISTERED -> ACTIVE only with READY handle/pin; ACTIVE -> PUBLISHED_PENDING_ACK -> PUBLISHED_ACK; REGISTERED/ACTIVE -> controlled ABANDONED | Pending/successful publication -> ABANDONED; abandoned consumer -> publish; upload alone -> published |
| Worker | Takeover under durable row lock increments its token; publication recovery preserves receipt/time | Expired old token renews, writes, reads, publishes or terminalizes another execution |
| Partition | Future precreated (no allocations); current OPEN -> SEALED -> RETIRED | New allocations on SEALED, recreation of RETIRED, drop without complete eligibility |

All transitions preserve tenant, immutable identity/scope/source and audit. READY is not deleted as a row-by-row expiry operation: only its disposable rows disappear at safe partition drop; durable identity/passport remains. Pin/lease release is token-specific and cannot remove a successor's pin.

Application lock partial order:

| Path | Required ordered locks | Never acquire afterward |
|---|---|---|
| Publish/visibility | IAM actor -> workspace -> sorted owner scope -> report instance/visibility -> consumer -> sorted files | Lifecycle fence or dataset attempt row |
| Staging/ACK/abandon/hold | Date shared fence -> dataset attempt -> sorted consumer/pin | IAM, report instance or file row locks |
| Maintenance | Existing maintenance coordination -> date exclusive fence -> attempt/consumer/pin -> verified DDL | IAM, report instance/file row locks or network |

Acquire date fence in its own statement before accessing staging relations, and process one date per lifecycle transaction; any admitted multi-date path must sort all dates first. Metadata keys immutable; FK KEY SHARE acquisition must remain compatible with lifecycle row locks. T8 does not add dataset/consumer FKs or acquire attempt rows after locking consumer. T9 reads an immutable committed receipt without FOR UPDATE on report/file rows. Reconciliation can read durable report evidence without introducing an opposite row-lock order. SQL planning/relation/FK/trigger locks are not covered by this application graph: inspect them and inject waits in PG17 qualification. Do not call a public owner adapter that reacquires earlier IAM locks from inside a file-locked callback.

Publication linearization is guarded T8 commit, not upload completion. Instance lock plus unpublished/identical-receipt guard also serializes competing consumers for the same report; a losing unpublished consumer is disposed through T9, never publishes a second format set. Final evidence passport is assigned once on publication, while the original request/actual scope remains immutable from admission. Revocation after an admitted publication can still deny subsequent access; revocation that commits first must prevent new publish/access. Long-lived signed URLs cannot promise instant revocation: choose authenticated streaming for strict per-access policy or admit an explicit bounded URL lifetime. No historical artifact can remove bytes already downloaded.

| Required real schedule | Expected outcome | Reused case |
|---|---|---|
| Publish locks consumer; abandon waits, then sees receipt | Cannot abandon; ACK recovery uses original publication time | JOB-01/02, CC-11 |
| Abandon locks consumer first; publisher resumes with old preflight | Publication CAS fails; no public report/file binding | JOB-01/02, CC-10 |
| Crash after publish commit before ACK | PENDING_ACK retains staging; idempotent recovery ACK, no duplicate file or changed timestamp | JOB-02, CC-12 |
| Append waits past lease expiry; successor claims | Old worker fails post-lock clock/token guard; no append/READY/renew | JOB-02, CC-11 |
| Freeze validation vs duplicate/late batch | Frozen content immutable; mismatched duplicate rejects; no incomplete READY | UAP-10, CC-02/11 |
| Pin/consumer/hold registration vs drop | One serialized winner; no pin on retired day, no drop with active dependency | JOB-02, CC-12 |
| Consumer has no publication and no admitted abandonment grace | No deletion from empty MAX/NULL/expired lease | JOB-02, CC-12 |
| Active day D+1 read overlaps day D DROP/create | Parent lock contention bounded; timeout rolls back retirement and DDL | PERF-02, CC-11/12 |
| Report scope revoked during render; foreign tenant has same IDs | Reauthorization denies publish/download/list/count; no data/metadata leak | SEC-02, CC-10 |
| File scan/availability/owner changes after preflight; orphan GC races publish | Current file checks/reference fence prevent unsafe publication or deleting committed artifact | SEC-02, JOB-02 |
| Source changes between 12-month batches or source snapshot lost on crash | Reject mixed report or restart full new extraction; no resumed mixed rows | UAP-04/10, CC-02 |
| Two real application pods, kill one after claim/render/upload/READY/DDL boundary | Token takeover/recovery, no duplicates/unsafe drop/tenant leak; record actual pod/process identities | JOB-01/02, CC-11/12 |

A minimal counterexample to unguarded completion is: publisher checks ACTIVE; recovery marks ABANDONED; grace elapses and cleanup drops; publisher commits using only its old preflight. The same-row T8 CAS rejects the last step. The model assumes that CAS atomicity; only real SQL can prove the implementation follows it.

## 6. Closure and implementation handoff

Design status: corrections applied in place; reference examples PASS. Runtime/SQL/security/performance evidence remains NOT EXECUTED in this review. Source code search/static reading is evidence of current capabilities, not a substitute for executing them. No current-CI success claim.

Remaining gates already belong to S1–S6: qualified source coverage and 24M bulk; canonical byte parity and bounded limits; actual report IAM/file-owner integration; migrations/least privilege and full SQL lock graph; PostgreSQL fault/revoke/multi-pod tests; renderer parity and resource/mixed workload qualification. Q2-FINAL P1–P8 admission remains independent. Existing P5 comparison targets may be reused only for the agreed comparable workload, not declared measured. Deployed PostgreSQL version, abandonment grace, per-tenant quotas, SLA and actual capacities must be explicit admitted configuration; unset mandatory policy fails admission.

Historical B5.5 NEXT text in older documents does not restart completed baseline work: preserve B5.5 and COR source recovery, continue the existing scheduler/ARS and S1–S6 dependencies. No new stage, engine or acceptance-family count. B4/product boundaries unchanged; D08 OPEN, Radar/production HOLD.

Primary PostgreSQL references checked for CI major 17: [partition maintenance and keys](https://www.postgresql.org/docs/17/ddl-partitioning.html), [explicit and advisory locks](https://www.postgresql.org/docs/17/explicit-locking.html), [MVCC isolation](https://www.postgresql.org/docs/17/transaction-iso.html). Parent locks, transaction-scoped lock duration and RR snapshot semantics inform the corrections; the proposed RM protocol itself is an engineering design requiring runtime proof.
