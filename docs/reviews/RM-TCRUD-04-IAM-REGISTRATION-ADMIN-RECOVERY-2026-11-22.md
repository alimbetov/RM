# RM — TCRUD-04 IAM Registration, Email Proof, Global Admin, Bootstrap/Recovery Boundary Audit

**Branch:** `base_dev`, Feature Freeze; no REST controllers, no new branch, no merge to main. **Scope:** current Java application boundary only; no assertion of completed HTTP transport, rate limits, external OIDC integration or production admission.

## Trust boundaries and decisions

| Workflow / Java entrypoint | Caller-provided proof | Internal checks / tenant predicate | Exposure decision | Qualification evidence |
|---|---|---|---|---|
| `EmailIdentityService.registerPendingEmail(email)` | Email alone; **not** authentication | Normalization, globally serialized HMAC lookup, pending-only registration, challenge cooldown | **package-private** until explicit transport admission, abuse protection and rate limits | `IamInitialEmailRegistrationQualificationTest` (duplicate, concurrency, purpose, expired tokens) |
| `EmailIdentityService.verifyRegistration(challengeId,token)` | Single-use registration proof | Purpose REGISTRATION only, SHA-256 token, expiry, consumption, active-account policy | **package-private**; no OIDC session | `IamInitialEmailRegistrationQualificationTest` |
| `requestInitialVerification/myVerificationStatus/requestEmailChange/cancelEmailChange/confirmEmailChange` | `VerifiedActor`; change requires additional new-address proof | ACTIVE OIDC-linked account, global email HMAC, same userId/purpose, consumed challenge, historical ciphertext | Public IAM application methods; not HTTP endpoints | `IamInitialEmailRegistrationQualificationTest`, `IamEmailChangeQualificationTest`, `IamEmailChangeOrderHistoryQualificationTest` |
| `findOrProvision(email)` and `queueVerification(identityId)` | **Trusted AUTOORDER server workflow**, not end-user proof | HMAC uniqueness, checkout idempotent seller-order transaction, CHECKOUT-only challenge | Existing cross-domain public Java calls retained to preserve transactional checkout; **DO NOT publish as HTTP operations** | `AutoOrderR1QualificationTest`, email purpose-isolation tests |
| `verifyEmail(challengeId,token)` | One-time delivered CHECKOUT proof, **not** OIDC identity | Purpose CHECKOUT, expiry, single-use token, status | Public Java used only as trusted AUTOORDER proof handoff; never grants a session | `AutoOrderR1QualificationTest` |
| `PlatformAdminGovernanceService.bootstrapFirstAdmin` | `VerifiedActor` plus one-time operator secret | Bootstrap explicitly enabled, configured SHA-256 secret, global guard, only first admin | Offline-only command, no controllers, no default secret | `IamAdminOwnerGovernanceQualificationTest` |
| `grantAdmin/revokeAdmin` | `VerifiedActor` | ACTIVE OIDC-linked PLATFORM_ADMIN, account/guard fencing, no self-change, cannot revoke last admin, audited | Verified Java admin operation | `IamAdminOwnerGovernanceQualificationTest` |
| `PlatformTenantBootstrapService.provision` | `VerifiedActor` | **Updated**: uses `PlatformAdminGovernanceService.activeAdministrator` for same account+guard lock as grant/revoke/recovery; validates ACTIVE OIDC-linked owner, idempotent tenant UUID+payload, fail-closed tenant RBAC | Trusted platform administrative provisioning only | `IamUserOnboardingProcessQualificationTest`, `IamAdminOwnerGovernanceQualificationTest.tcrud04RevokedPlatformAdministratorCannotProvisionTenantOrRecoverOwner` |
| `PlatformTenantRecoveryService.recoverOwner` | `VerifiedActor` and incident reference | Same global guard, zero eligible active owners, ACTIVE linked tenant member successor, no self-recovery, immutable audit | Internal emergency recovery only | `IamAdminOwnerGovernanceQualificationTest` (negative, concurrent recovery, incident audit) |
| `TenantMemberEnrollmentService`, `TenantOwnershipTransferService` | `VerifiedActor`; two-person acceptance where required | ACTIVE OWNER and member, tenant row lock, no self-promotion | Verified management operations | `IamUserOnboardingProcessQualificationTest`, `IamAdminOwnerGovernanceQualificationTest` |
| `GlobalAccountStatusService` | `VerifiedActor` admin | Active platform administrator, target account, no self suspension, owner safeguards | Verified admin | `IamAccessStatusQualificationTest` |

## Changes made

1. `PlatformTenantBootstrapService` now reuses the same central `activeAdministrator` admission and global row-lock fence as privileged role mutation and recovery, rather than maintaining a different direct role check. This closes an inconsistency in provisioning-vs-revocation serialization. It does **not** change tenant creation SQL, permissions or audit.
2. `registerPendingEmail` and `verifyRegistration` were previously public Java methods accepting only email or a challenge/token. Both remain available to `kz.rm.iam` integration tests/internal orchestration but are no longer public Java entrypoints. They are deliberately **not enabled** as unthrottled, user-facing registration routes.
3. New reflection tests in `Tcrud04VerifiedEntrypointQualificationTest` enforce the non-public registration pair, freeze the allowed public UUID-first checkout helpers (`queueVerification`, `verifyEmail`) and ensure global admin/owner/recovery/public membership methods begin with `VerifiedActor`.
4. `IamAdminOwnerGovernanceQualificationTest` now rejects revoked platform admin provisioning/recovery, verifies no side-effect tenant creation or OWNER_RECOVERED audit, and retains independent successful administrative onboarding tests.

## Residual risks / qualification boundary

- **P0 until transport cutover:** no public registration, checkout confirmation, operator bootstrap, recovery or role management endpoint may call raw Java services without an explicit authenticated/proof/anti-abuse adapter. Java `@Service` is not a public HTTP endpoint.
- AUTOORDER `findOrProvision/queueVerification/verifyEmail` remain cross-package public Java internal compatibility methods. Their safety relies on NOT exposing them as untrusted API ports; future route work must wrap them in purpose-limited admission and rate limiting.
- Migration jobs `LegacyIamPiiMigrationService.migrateBatch/migrateUser` remain offline/maintenance-only, gated by config; do not expose as public transport. No operational adapter authorization is implied by this audit.
- Static reflection checks are regression gates but do not prove absence of dynamic reflection, custom bean lookup, classpath-bypassing code or external transport misuse.
- TCRUD-03 per-method cross-domain negatives remain PARTIAL; global production admission **HOLD**.

## Exact SHA acceptance

Require all four workflows PASS on the same `base_dev` HEAD: domain H2/PostgreSQL and populated IAM, populated CRM upgrade, RustFS/ClamAV E2E. PostgreSQL admin/revocation tests must execute without skip. A successful earlier SHA cannot qualify a later commit. Results and tested SHA are to be recorded separately after completion.
