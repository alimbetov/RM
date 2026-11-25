# RM-TASK-01A.2 — Second service review, IAM race, retry, privacy

**Date:** 2026-11-23. **Branch:** `base_dev`. **Status:** qualification pending final exact SHA. **Production:** HOLD. No controllers/UI or `main` merge.

## Baseline review finding

The previous `533f13094785c0187e3f11c4cf908dbe182eeb1a` had successful TASKS (nine cases), CRM, SALES, INVENTORY, Module JUnit, Security and RustFS workflows but **FAILED** the overall exact-SHA workflow: the synthetic Python QA verifier's TASK artifact fixture accidentally replaced nine mandatory cases with one dummy case. GitHub Actions fail-closed behavior was correct. This is a mandatory process defect, not an acceptable green release.

## Implementation: immutable execution receipts and safe titles

- New tasks accept only a **closed vocabulary** of generic non-confidential operational titles: `Prepare customer call`, `Review order`, `Check inventory`, `Review document`, `General operations`. Unknown strings (including names, emails, phone numbers and all free text) are rejected before persistence. This is a conservative temporary policy, **not an encryption implementation**. Historical plaintext task titles remain untouched and must be inventoried/remediated before production access.
- New additive Liquibase migration `0143` adds `request_key` and `request_sha256` to append-only `crm.task_event`, with UNIQUE `(tenant_id,actor_user_id,request_key)`. Start/block/resume/complete, assignment and archive support additional overloads with a request key and persist at most one immutable event per exact command. A persisted request stripe serializes keyed commands before individual task mutation, including cross-task reuse. Same key/payload returns a committed current version; different payload or task conflicts. If later transitions occurred, `TASK_RECEIPT_SUPERSEDED` prevents fabricating a stale response.
- IAM admission occurs **before** receipt lookup, so a revoked actor cannot exploit replay as an authorization bypass. The preexisting no-key methods remain for internal compatibility and do not claim exactly-once retry guarantees.
- `create` retains its existing actor-scoped retry boundary using tenant, request key and payload SHA. `assign` and `archive` do **not yet** provide keyed recovery after loss of response. Do not claim universal task-command replay safety.

## PostgreSQL qualification

- Functional role revoke **first** denies task execution with no new task event.
- Existing admitted execution commits before competing OWNER `TASK_EXECUTE` revoke, serialized by workspace locks. A subsequent attempt is denied.
- Membership suspension **first** denies task assignment; compliant membership writer takes tenant workspace `FOR UPDATE`, serializing after already admitted task creation. Revoked member cannot execute afterwards.
- A lost response to a keyed `start` is replayed without a duplicate event; conflicting payload returns a conflict; after a subsequent transition a previous receipt returns `TASK_RECEIPT_SUPERSEDED`.
- Literal email/phone in task title is rejected; new writes have only controlled generic labels.
- Specialized suite requires 17 cases on PostgreSQL with zero skips, and 17 discovery/5 PostgreSQL-only skips on H2 (12 executed).
- The mandatory global exact-SHA verifier now expects the dedicated TASKS H2 and PostgreSQL artifacts and checks exact counts plus the key racing/retry cases. Synthetic verifier fixtures must preserve the full test matrix.

## Outstanding gates

1. Verify all mandatory workflows and exact-SHA admission on the **same final commit**; no reused green artifacts.
2. Validate every keyed command on PostgreSQL (including `assign` and `archive`) and ensure no-key compatibility callers do not misinterpret their retry guarantees. Test crash/rollback before receipt commit and re-admission after role revocation in an additional resilience slice.
3. Qualify membership status changes through every supported IAM writer and both fully observed lock interleavings; direct SQL in synthetic tests is **not** a substitute for a public production admin workflow.
4. Inventory/migrate historical free-text titles and decide on encrypted separate confidential notes with KMS/AAD/key rotation. Arbitrary notes stay STOP.
5. External OIDC admission, real devices, broader production security and load gates remain HOLD.

## Delivery constraints

Continue R2 service-only work in `base_dev`, preserve tenant isolation, IAM lock order and immutable event evidence. No `main` changes, no controllers/UI, no production admission.
