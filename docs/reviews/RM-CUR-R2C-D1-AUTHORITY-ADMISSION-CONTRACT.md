# RM-CUR-R2C.1D — Authority admission contract for D.2
Date: 2026-11-24. Status: DESIGN / TESTS PENDING / NO PRODUCTION ADMISSION.
Baseline: 24d166b1102772f97372a18eaba7475d73581744.
D.1C tests: eae127123ede18a98a15d86160677d7a9c5413f8.

## Required transactional contract
- PRODUCT only. VerifiedActor is minted by IAM, never by caller-supplied actor ID.
- ACTIVE account, organization workspace, nondelegable OWNER membership and current STOREFRONT_OFFER_MANAGE grant are all required.
- Canonical target bindings must be loaded server-side by tenant + offer + anchor + guardrail; derive complete distinct currency set from those bindings. No caller-provided subset may authorize persistence.
- Require PRICING_ANCHOR for every currency and PUBLIC_PRICE KZT; no inheritance from PURCHASE or SALES_INTERNAL.
- Admission and persistence share one transaction, READ COMMITTED; stronger isolation is not admitted until fresh authorization visibility is independently proven. No detached permit, no REQUIRES_NEW persistence, no external I/O under locks.
- Recheck VerifiedActor expiry after locks and immediately before evidence write. Failure rolls back all writes.
- Actor SHARE -> workspace SHARE -> tenant currency policy UPDATE -> policy/target locks in canonical order. Do not upgrade IAM locks to exclusive in a consent command.
- D.2 creates immutable revision/enrollments only; D.3 verifies canonical scope SHA-256 and explicit owner consent. D.4 revocation is terminal with CAS epoch. Prior approval is never inherited on ownership transfer or recovery.
- No ACTIVE state, worker, publication, budget consumption, REST/UI or commercial mutation.

## Qualification
- D.1A/B 11 methods: signed RSA JWT/local JWKS, negative tokens, owner/domain grant, tenant isolation, grant revoke, anchor revoke, account/membership/workspace status, mandatory transaction, ownership transfer, admin denial, expiration while blocked.
- D.1C adds two methods, four commit-order combinations via real transfer and membership suspension services. Exact backend lock observation and bounded lock timeout; unexpected SQL errors fail.
- Full target R2C suite expects 84 methods; prior 82-method exact SHA was queued at observation, not PASS.
- Known open lock inversion: TenantAccessStatusService.restoreMember obtains target account UPDATE after workspace UPDATE, while admission locks account before workspace. Must qualify/fix in IAM lifecycle scope; do not claim global deadlock freedom.
- Membership suspension uses a synthetic two-owner fixture to exercise the legal service path; no direct SQL lifecycle mutation in race itself.
- D.3/D.4 must independently prove stored consent noninheritance, including restoration, transfer back and revoked grants; D.1 tests only current admission.
- Closure requires new exact SHA green, populated upgrades, full regression, independent lock review. No release authorization; original design scope restrictions remain.
