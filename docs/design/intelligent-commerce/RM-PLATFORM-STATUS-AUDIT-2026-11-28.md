# RM Platform — technical status audit and development map
2026-11-28. Source baseline: base_dev 861749722fb61fd86b4823453ff80dfc072dd438. P4.1A candidate 923db1055a85649f1e3027946aefebcb4ebab7f3, four commits ahead, zero behind, 14 files (+706/-26); not integrated. Design-only audit; no new DAG stages, no release admission.

## Evidence and inventory

GitHub source tree recursive inventory not truncated: 310 main Java files, 142 test Java files (files, not test cases), 17 registered bounded contexts in DomainModule (including INTELLIGENCE and EVT). FX, AutoOrder and Radar are cross-domain capabilities, not additional registered domains. No package.json/pubspec.yaml observed in the source tree; not proof that no frontend exists elsewhere.
CI on baseline: 19/23 successful, four running, no observed failures; all 23 attempts 1. SALES 46129875313 and INVENTORY 46129875240 passed; RustFS V0 real infra E2E 46129875375 passed. Pending QR-07 46129875269, Domain Security 46129875292, Exact-SHA 46129875295 and Development Admission 46129875236. Do not convert running into PASS or reuse previous SHA proof. The candidate has no CI/runtime evidence. Independent Python cryptography vector recomputation matched the V1 fixture (185 bytes); Java integration/tests not executed in this audit.

## Platform map (evidence boundary, not a numerical release percentage)

| Lane | Current implementation/evidence | Remaining admission |
|---|---|---|
| IAM / ORG / KMS | Tenant permissions, role fences, organization/location and encrypted PII infrastructure; historical qualification, current domain security running | Real external OIDC/browser, key restore and operational proof; rotation remains disabled |
| GEO | KATO directory, hierarchy and location policy | Source freshness/history attribution and regional extension independently qualified |
| CRM / TASKS | CRM customer/contact/interaction and TASK lifecycle, CRM follow-up and reconciliation; current TASK R2/R3.2A/T00 runs pass | Full authorized user/API journey; notifications/SLA automation not silently reopened |
| CATALOG / STOREFRONT / SALES | Product, offer lifecycle, immutable checkout snapshot, orders; current SALES populated-upgrade pass and MVP service journey pass | User-facing journey, exact current release gate and physical QR/return evidence |
| INVENTORY / QR | Movement, reservations, transfers, stocktake, reconciliation; current INVENTORY upgrade pass | Full QR-07 still running; real phone/browser, matched load and release admission |
| Smart FX Shield | NBRK/KASE ingestion/calendar, pricing candidates, policy/consent/dry-run/ledger and preparation; current CUR-R2C workflow pass | Controlled actual execution and tenant source/price permission; no inferred general auto-pricing release |
| FILES | V0 private LOCAL/RustFS and AV existing baseline; real infra run pass | V1 candidate below; trusted report intent, scan, metadata commit, real V1 provider/restore, authorization |
| INTELLIGENCE L0-L3/B5 | Canonical facts, generation and coverage, numeric/interpretation services; prior B5.5 baseline preserved; current A1 PASS | Historical completeness distinct from parity, actual scope, data quality and independent multi-pod/production proof |
| NIGHT / INTRADAY | AnalyticalJobRepository/Service, scheduler, IntradayWorker and result store present | Q2-FINAL checklist P1-P8 evidence pending per existing review; paired OFF/ON performance, real pods and full closure |
| Reporting | reporting.int_report_instance/dataset_attempt/partition_day/dataset/consumer/source_evidence schema, sealed dataset and 12 XLSX template generation previously demonstrated on P3; renderer is XSSFWorkbook/byte[] | P4 secure publication and report scope/ACL, PDF, scheduler/report retention and user catalog; gate-only workbook is not a qualified business metric |
| Radar | R0.1 timing, overdue, completed and calendar queries; observed Radar workflows pass | Actual competition/performance admission, later Radar scope remains gated |
| EVT / notification | EVT registered; no kz.rm.evt main Java files in inspected baseline. Existing IAM outbox and TASK follow-up are not full Event Builder | Explicit admitted MVP in-app typed rules/durable delivery and mock external only; no claims of broad real delivery |
| AUDIT | Audit action/retention service and scheduler | Complete sanitized cross-domain evidence, observability/retention and production policy |
| FINANCE / MARKETING / SUBSCRIPTION | Registered, initial schema/value contracts; one main Java file in each package | Separate functional implementation/admission, not a reason to expand frozen MVP |
| UI / product | Backend-first service evidence; no frontend package manifest observed in base_dev | G2 real OIDC/API/minimum user journeys; G3 controlled pilot; G4 release. D08 remains OPEN |

## P4 status

P4.0: CI mitigation committed, SALES and INVENTORY now pass first attempt, exact admission pending. P4.1A.1: V1 AES-GCM/HKDF candidate and independent reference vector. P4.1A.2: candidate code integrates explicit stageEncrypted/promoteEncrypted/openEncrypted into existing FileStoragePort, LOCAL/RustFS/provider selector; distinct stage-v1/final-v1 attempt keys; existing generic claim/finalize denies confidential, replay denies V1; recovery checks format and exact metadata. Legacy version-zero verification remains. New tests are written, not executed. No new tables, migrations or dependencies. P4.1A.3 OPEN: trusted report intent with immutable dataset/passport/size/digest, actual OOXML/AV admission, fresh IAM and fenced V1 metadata commit. P4.1A.4 OPEN: independent recovery/multi-pod/commit ambiguity and workload. P4.1A.5 OPEN: integrate d69daea7 guard with real file evidence (not included in candidate). P4.2 atomic publication, P4.3 delivery, P4.4 catalog, P4.5 audit all OPEN.

## Selected technical findings and closure

| ID | Priority | Finding / gate |
|---|---|---|
| PA-01 | P0 | P4.0 exact admission still running. Do not move base_dev while active. No runtime promotion of detached candidate. |
| PA-02 | P0 | Existing FILES generic upload only admits images/PDF, legacy scanner receives private staging key. V1 staging is deliberately disconnected from upload completion; must bind authenticated plaintext to real ClamAV result and trusted renderer before AVAILABLE. |
| PA-03 | P0 | RustFS conditional create uses SDK 2.25.27 request override header If-None-Match; real provider semantics, immutable bytes, retry/ambiguous write, TLS/private credentials and backup/restore need qualification. LOCAL no-replace hard link requires actual filesystem durability/permissions tests, no production assumption. |
| PA-04 | P0 | PostgreSQL and storage are not one transaction. Existing generic completion deletes finalKey on unsuccessful return; V1 path must handle ambiguous commit via fenced durable pointer/reconciliation, never delete a committed artifact. Recovery/GC must not race publication or active claims. Verify current pointer and state on quarantine. |
| PA-05 | P0 | Existing consumer schema permits ACTIVE/PUBLISHED_PENDING_ACK/PUBLISHED_ACK/ABANDONED and publication digest; report owner attachment/atomic receipt not completed. No direct storage URL, no cross-tenant/within-tenant scope bypass; current permissions at publish/read. |
| PA-06 | P1 | One V1 operation per JVM semaphore is conservative, not tenant/cluster budget. Renderer uses XSSFWorkbook + byte[] before storage; measure all live buffers, heap, concurrent workloads, timeouts and backpressure. |
| PA-07 | P1 | Q2-FINAL checklist P1–P8 remains pending; no actual two-pod and matched performance proof inferred from thread tests. Separate 24h report staging retention (consumer ACK) from analytical scheduler. |
| PA-08 | P1 | Reporting retention blockers explicitly return RETENTION_NOT_ADMITTED; do not enable partition deletion until file publication ACK, holds, pins and orphan fences are proven. |
| PA-09 | P1 | Old maturity reports and initial domain implementation plan reflect earlier snapshots. Keep formal UR-R3.5 (75 requirements/16 assessment domains, 19 role cases) distinct from 17 registered domains; consume real exact-SHA evidence rather than assigning unsupported maturity scores. |
| PA-10 | P0 for release | G1–G4: backend closure is not user-facing pilot; real OIDC/browser, phone QR, operations/privacy/load and independent admission required. |

This is a selected cross-domain source/DDL/CI/design reconciliation, not an exhaustive line-by-line security audit or a fresh full suite. Full audit remains after P4 completion. Product/B4 freeze, D08 OPEN, production HOLD. Next: P4.1A.3 trusted report upload and real AV, after P4.0 GREEN and candidate compile/tests; preserve no new branch or force push.
