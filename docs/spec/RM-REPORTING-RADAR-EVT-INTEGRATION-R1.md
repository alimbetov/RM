# RM Reporting × Radar × EVT R1 — lifecycle integration
Date: 2026-11-28. Reviewed source: 7b2568211ddede723a7b12c6248451df060e4d55. Branch: base_dev.
Status: OWNER-REQUESTED DESIGN ALIGNMENT / DOC-ONLY / RUNTIME ADMISSION HOLD / PRODUCTION HOLD.

## 1. Scope and ordering
Preserve P4.1A.5-Q → P4.2 after actual GREEN. P4.1B is a planning alias for this integration package, not a replacement milestone or gate. Reporting publication may proceed independently within approved scope; Radar provider and EVT reporting triggers require explicit scoped admission, R0.1D and applicable EVT/IE gates. No new external sends, Redis, branch, permission, applied migration or CI policy change.
Normative: [SFC](RM-SFC-R1.md), [transport](RM-EVENT-TRANSPORT-POSTGRES-OUTBOX-REDIS-READINESS-R1.md), [Radar](RM-RADAR-VISION-R1-INTELLIGENT-BUSINESS-CONTROL-CENTER.md), [EVT scope](RM-MVP-INTELLIGENCE-EVT-SCOPE-R1.md), [DAG](RM-INTELLIGENT-COMMERCE-EXECUTION-DAG-R1.md). Stricter gates prevail.

## 2. Owner and transaction contract
Reporting owns instance, dataset, file intent and publication state. Files owns encrypted storage and attachment. Catalog reads authoritative committed metadata with fresh authorization; it is not dependent on event projection. Radar is a read-only signal consumer; EVT owns recipient policy, intent and delivery. Scheduler owns occurrence/deadline. Intelligence owns qualified dataset/metrics. No parsing XLSX/PDF to derive metrics.
Existing ReportFilePublicationGate is an XLSX pre-publication guard, not publisher. Preserve verify outside caller transaction (bounded authenticated storage read), then admit in READ COMMITTED transaction, lock intended consumer, lockVerified, attach + state transition + canonical event/required durable intent atomically. Preserve existing lock ordering, 30-second evidence, actor/tenant/owner/digest/pointer validation; no remote I/O under business locks. Review actual DDL and consumer identity before coding; do not invent permission or lifecycle state. File object AVAILABLE is not report PUBLISHED. PostgreSQL/RustFS are not one distributed transaction: reconcile orphan intent/object, never delete referenced object, retry with stable identity and fencing. PDF is separate format admission, not reuse XLSX permit.
Use existing OwnerEventEnvelope and TransactionalEventPort semantics, not new event bus or IAM mail outbox. Per-consumer atomic receipt/effect, at-least-once, bounded lease/retry/replay; no global ordering or exactly-once claim. Source event identity tenant+owner+eventId; publication occurrence stable per admitted instance/version/format transition, exact key to be verified against schema. Distinguish logical report from each format artifact. One user-visible ready signal per admitted occurrence/policy; optional format availability update without duplicate ready notification.

## 3. Proposed typed event admission
| Candidate | Authoritative trigger | Scope |
|---|---|---|
| REPORT_PUBLISHED | committed authorized publication | first publication slice |
| REPORT_GENERATION_FAILED | durable terminal failure, not transient retry | later lifecycle admission |
| REPORT_QUALITY_REJECTED | durable owner quality decision | later lifecycle admission |
| REPORT_PUBLICATION_FAILED | durable terminal publication failure | later lifecycle admission |
| REPORT_OVERDUE | qualified Scheduler occurrence and deadline | scheduled slice |
| REPORT_SUPERSEDED / REPORT_REVOKED | committed owner transition | version/catalog slice |
| REPORT_DELIVERY_FAILED | EVT terminal delivery evidence | EVT slice |
Names are proposed; reconcile existing registry and schema first. Envelope has tenantId, sourceOwner, eventId, eventType, schemaVersion, subjectRef, occurredAt, recordedAt if distinct, actual sourceVersion if supplied. Minimal typed payload: instance ref, format, report code, approved period/asOf or evidence ref, safe status/reason code. No raw data, filenames containing PII, storage keys, JWT, recipient addresses, permanent URLs or evidence capability. Fresh authorization on signal read, notification delivery and catalog open; revoked recipients cannot infer report existence. No broadcast by tenant membership alone. Do not report scheduled/generated/delivered as published. A stale event cannot restore a revoked report; reconcile authoritative state. In-app only after admission; external MOCK only under existing EVT scope.

## 4. Scenario traceability
Local design IDs (not new accepted master-registry counts): RP-J01 manual report ready/open; RP-J02 terminal failure/quality rejection; RP-J03 duplicate/multi-pod/recovery; RP-J04 tenant and role revocation; RP-J05 scheduled occurrence/overdue; RP-J06 supersession/revoke and multiple formats. Map each to existing scenario ID+variant, actor, grants, owner, class/method, DDL, transaction, identity, receipt, independent oracle and actual SHA evidence before implementation. No fake zero or ready from incomplete dataset. Missing data remains unknown/blocked. Existing period/currency semantics unchanged.

## 5. Qualification and delivery
P4.1A.5-Q unchanged. After GREEN: P4.2 source/DDL/permission/EVT inventory and contract reconciliation → atomic XLSX publication and owner event → PG rollback/idempotency/race/crash qualification → catalog access → PDF admission → scheduled occurrence/delivery. Radar/EVT activation separately gated; reporting producer need not wait for unavailable consumers, but no false consumer PASS. Preserve existing Exact-SHA and performance gates (Radar 200ms / 10% where applicable); no new release gate.
RP-Q01 commit and exact one logical event; Q02 rollback no event; Q03 two tenants and foreign pointers; Q04 retry idempotency; Q05 2–4 pod races; Q06 storage-before-commit crash and orphan cleanup; Q07 commit-before-relay crash; Q08 duplicate/replay plus live; Q09 revocation both commit orders; Q10 bad ciphertext/hash/scan; Q11 expired evidence/pointer drift; Q12 unavailable consumer and independent receipts; Q13 stale/reordered event and supersession; Q14 multi-format notification identity; Q15 deadline/timezone/occurrence boundary and quality unknown vs zero; Q16 mixed PG load and existing Exact-SHA. Tests proposed, NOT_RUN. No new runtime admission or PASS by this document.


## 6. Functional coverage crosswalk
[Reporting FC-R1.0](RM-REPORTING-FC-R1.0.md) links RP-J01..06 to SFC obligations, not aliases or admission. RP-Q01..16 remain NOT_RUN. P4.1A.5-Q → P4.2 unchanged; no new gate/runtime.
