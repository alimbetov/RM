# FINAL-SEC-GATE / MAIL-OTP-R1.1 — review and admission record

**Date:** 2026-11-21. **Repository:** alimbetov/RM. **Branch:** `base_dev`.
**Reviewed HEAD:** `cc972f3e7e1cf7588e7427f03f446f09d1004b66`.
**Decision:** **HOLD** for merge and production. **MAIL-OTP-R1.A implementation is not yet admitted** under the requested ordering (security gate first).

## Evidence verified on the reviewed SHA

- CI workflow [44915317365](https://github.com/alimbetov/RM/actions/runs/44915317365): `h2-smoke`, `postgres-integration`, `postgres-pii-populated` all SUCCESS.
- [FRZ-1 review](../reviews/RM-FRZ1-SECURITY-REVIEW-2026-11-21.md) documents typed `VerifiedActor` entrypoints, transactional `TenantAuthorization.requireForCommand`, and package-private inventory mutation boundaries. The gate is **not** satisfied merely by this historical review or existing CI.
- [PR #9](https://github.com/alimbetov/RM/pull/9) has no submitted independent review at this check.
- `EmailIdentityService` presently implements 15-minute LINK_TOKEN challenges, `REGISTRATION`/`CHECKOUT`/`CHANGE_EMAIL` flow, and existing `iam_email_delivery_outbox`. `RM-MAIL-OTP-R1.md` is design only; no OTP claim is made.

## Mandatory FINAL-SEC-GATE matrix (not yet evidenced on exact HEAD)

| ID | Required test/evidence | Gate |
|---|---|---|
| SEC-F1 | Verified actor binding; deny forged actor, disabled/unknown account, invalid issuer/audience/signature/expiry; no UUID bypass into SALES or INVENTORY | OPEN until exact-SHA negative report |
| SEC-F2 | PostgreSQL two-connection ordering: admitted command before role revoke, revoke before admission, membership suspend and global account suspend in both interleavings; fail-closed under rollback/retry and stale JPA state | OPEN until comprehensive concurrency report |
| SEC-F3 | Reject raw inventory mutations from public application boundary, mismatched actor/tenant permits, reused permits after commit, and cross-tenant movement/order mutations; verify architecture regression suite | OPEN until exact-SHA evidence |
| SEC-DB | Inventory all privileged database writes; qualified least-privilege runtime role; populated PG upgrade/restore with rollback evidence | OPEN for production |
| SEC-REVIEW | Independent reviewer submits approval for the precise security diff and its residual risks | OPEN |
| SEC-CI | Latest SHA, H2 + PG + populated PG CI green, tests cited by job IDs | existing green, must repeat after changes |

**Distinct decisions:** internal merge to `main` requires exact-head security qualification and explicit review/release decision; public production admission additionally requires real OIDC/BFF/HTTP and mail provider controls, key operations, DB privileges, filled-db recovery and independent sign-off. Never infer production admission from synthetic CI.

## MAIL-OTP-R1.1 source review: implementation contract

1. Implement strictly in `base_dev`, no new branch, controllers, SMTP/KumoMTA live calls, Redis or new IAM authority. Respect §15 precedence in [spec](../spec/RM-MAIL-OTP-R1.md).
2. R1.A: additive Liquibase after `0051`; keep all legacy LINK_TOKEN rows and 15-minute TTL unchanged. Method-discriminator with DB constraints, per-challenge OTP HMAC keyId and AES-GCM delivery envelope. Six ASCII digits retained as string, 10-minute TTL, constant-time HMAC compare.
3. Invalid attempts must COMMIT rather than rollback with unchecked exceptions; three attempts/challenge and nine failures/identity+purpose/rolling hour serialized in PG. First issue plus three resends/rolling hour; 60s cooldown; resend never resets shared quota. Parallel wrong/correct/revoke scenarios required.
4. Consolidate email proof lifecycle for LINK_TOKEN and OTP, preserving `VerifiedActor` protection on CHANGE_EMAIL, immutable HISTORICAL identity, order FKs, and checkout ownership rules. Prove lock order with two-session PG tests before enabling.
5. R1.B: pin template key/version/locale at enqueue; en/ru/kk/zh-Hans; strict typed placeholders/HTML escaping/no injected URLs/headers; one reusable mail port, fake transport and bounded PG SKIP LOCKED lease/fencing dispatcher. Do not send an OTP with <120s TTL; recheck superseded/consumed state before injection; do not claim exactly-once.
6. R1.C: provider mock contract & populated-DB migration rehearsal; keep real KumoMTA, live delivery and public HTTP behind a separate gate.
7. R1.D (separate release): live issuer/JWKS/BFF, POST-only public adapter, anti-enumeration and network/device/IP throttles, mail domain authentication and independent review.

## Security review concerns to address explicitly

- Existing LINK_TOKEN verification paths lock a challenge before the user account; CHANGE_EMAIL locks account before challenge. A shared OTP lifecycle must define one canonical order and verify deadlock-freedom; do not merely move code into a new service.
- Existing `EmailIdentityCrypto.tokenHash` is SHA-256 for 256-bit bearer tokens. Never reuse it for six-digit OTP. OTP must use a separate keyed HMAC secret, bound to challenge/identity/purpose/key version.
- Existing IAM outbox cannot hold plaintext OTP or unversioned rendered messages. Encrypt the OTP payload with dedicated AAD and erase after terminal delivery/expiry.
- A provider `accepted` receipt is not delivered; stale IN_FLIGHT delivery cannot make a revoked code valid.
- An OTP proves email control only; it is **not** login/MFA, and must never mint a session or tenant authorization.

## Release record

**Security gate:** HOLD, pending evidence and independent review.  
**MAIL-OTP implementation:** NOT STARTED by this review; enabling before FINAL-SEC-GATE would violate approved sequencing.  
**Merge `base_dev` → `main`:** HOLD, requires explicit separate approval.  
**Production/public admission:** HOLD.
