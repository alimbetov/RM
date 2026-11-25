# CRM-R2.2C — Identity Dispute Hardening, exact-SHA PostgreSQL Evidence

**Date:** 2026-11-22. **Repository/branch:** `alimbetov/RM` / `base_dev`.

## Executed tests and provenance

### Service + concurrency exact-SHA qualification

- Source SHA: `d5c252e1993f9f662b8fd0569579457aac057071`
- GitHub Actions: https://github.com/alimbetov/RM/actions/runs/45050614367 — **SUCCESS**
- Job: `137294680159`
- Artifact: `crm-r22-buyer-postgresql-45050614367`, ID `14765282962`
- Surefire: **72 tests, 0 failures, 0 errors, 0 skipped**:
  - CrmR1aQualificationTest: 37
  - CrmBuyerLinkQualificationTest: 16
  - CrmInteractionQualificationTest: 19

### Populated upgrade with real old-schema data

- Target SHA: `9005ffb656e9e4d0a10431fff426d05f08a51fe2`
- GitHub Actions: https://github.com/alimbetov/RM/actions/runs/45050632811 — **SUCCESS**
- Job: `137294739163`
- Artifact: `crm-r22c-populated-upgrade-45050632811`, ID `14765233068`
- Baseline R2.2B source: `a7d4ac44482f22cba6ba2f52451ef6d4ff773e75`.
- Existing PostgreSQL populated by baseline CRM buyer qualification: 12 tests PASS.
- Before upgrade: **16 persisted tenant-scoped links and 27 immutable link events**.
- Candidate code applied Liquibase changesets 0106/0107 to the populated database and re-ran 16 buyer tests: **16 PASS, zero failures/errors/skips**.
- Snapshot checks verified all pre-existing link composite IDs, states, row versions and event IDs/actions remained present and unchanged. New adjudication table and active-uniqueness index verified. Both before/after lists uploaded.

## Implemented contracts

- Buyer-account **ACTIVE** state is checked under transaction-held PostgreSQL row locks before confirmation, buyer revocation and buyer dispute; proposal checks buyer state under a shared account lock.
- Seller order-history projection requires current ACTIVE global buyer status, CONFIRMED link, active CRM customer, seller CRM_READ and SALES_ORDER_READ; suspension or DELETED status fails closed.
- `GlobalAccountStatusService.suspendAccount` is exercised in two deterministic transaction-commit orders against buyer confirmation. Account suspension prevents a new confirmation when it commits first; previously admitted confirmation can commit before suspension. Subsequent seller history is always denied.
- Independent reviewer: an authorized CRM writer distinct from the original link proposer and buyer must adjudicate a DISPUTED mapping. Only fail-closed transition to REVOKED is permitted. Reason is a bounded non-PII enumeration (IDENTITY_MISMATCH, BUYER_DENIED, INSUFFICIENT_EVIDENCE).
- `crm.customer_buyer_dispute_decision` stores reviewer ID, reason and timestamp with a tenant-composite link FK. PostgreSQL rejects UPDATE/DELETE of decisions. Existing link-event immutability remains enforced.
- Synthetic IAM account state DELETED test verifies the old link and audit remain and cross-domain history is hidden. A physical account DELETE is rejected by persisted references. **This is not an implementation or E2E of the trusted global account deletion process.**

## Defect found and corrected

Initial test runs failed because `disputeByBuyer` accepted a previously verified principal after committed IAM suspension. Corrected by locking and checking current global buyer account status in the service. Both final PostgreSQL qualification gates passed afterward.

## Admission boundary

**CRM-R2.2C: scoped service-and-migration qualification GREEN at the two exact SHAs above.**

**Remains HOLD:** actual trusted IAM deletion/deidentification workflow and operational retention policy; multi-reviewer governance beyond distinct proposer and reviewer; external OIDC end-to-end; production populated migration with production-like volumes; platform-wide RM-QA-01B admission and performance qualification.

Controllers/UI STOP. No main merge. Production HOLD.
