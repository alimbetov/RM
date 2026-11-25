# RM-R4-W0.1 — Trusted Identity and First-Tenant Onboarding

**Branch:** `base_dev`. **Scope:** internal Java services and evidence gates only. **Production HOLD**. The source R3-v1 requirement catalogue remains at 75, with `IAM-06` and `ORG-05` still `PLANNED` pending real external OIDC/email delivery and end-user entrypoint acceptance.

## Existing source-of-truth

- Global IAM account: `iam_user_account` with unique `external_subject`; `OidcActorResolver` requires a cryptographically validated JWT and maps subject to active global account.
- `OidcJwtConfiguration` enforces issuer/audience and HTTPS JWKS for configured deployments. `RmOidcHttpSecurity` denies all business requests when OIDC disabled.
- Global email ownership and proof: `EmailIdentityService` with encrypted email, expiry, one-use proof, verification outbox; email proof alone does **not** create a bearer principal.
- Organization is `org_organization`; organization tenant is `iam_workspace.id`; OWNER is `iam_org_membership`. `MyAccessService` selects only active memberships/workspaces.
- Existing operator-only `PlatformTenantBootstrapService` remains independent from new self-service; there is no automatic global admin grant.

## New internal workflow

`SelfServiceTenantOnboardingService.createFirstTenant(actor, provisioningId, legal, display, country)`:

1. Reject null/malformed request, inactive or unlinked actor, unverified email; acquire the active user's SQL row lock before any org writes.
2. Resolve current active tenant memberships; never trust user/owner UUID in request.
3. First request atomically creates one organization, ACTIVE workspace, first OWNER membership, RBAC ENFORCED with no implicit business grants, and a `TENANT_PROVISIONED` audit event.
4. Exact replay of same tenant UUID and matching payload returns the same organization and does not add events. Foreign/colliding/reused provisioning IDs or a second first tenant are denied.
5. `selectTenant(actor, tenantId)` is a pure active membership read; it neither mints grants nor accepts arbitrary tenant IDs.
6. All failures roll back. The PostgreSQL test checks no unauthorized workspace, membership, audit or role pollution, and concurrent first-tenant attempts.

## Identity testing tiers

- **R4-W0.1-PG synthetic:** `R4FirstTenantOnboardingQualificationTest` executes in the mandatory PostgreSQL suite; email verification uses the real encrypted proof service, not a fake boolean.
- **R4-W0.1-JWKS integration:** `R4LocalJwksIntegrationQualificationTest` hosts an ephemeral real JWKS HTTP endpoint locally, validates RSA signature via Nimbus JWKS decoder, issuer, audience, expiry and unknown subject. Test HTTP is loopback-only. Production JWKS URL still **requires HTTPS**.
- **R4-W0.1-EXTERNAL HOLD:** a provider-controlled HTTPS OIDC discovery/JWKS, actual authorization-code/PKCE login with token exchange, issuer signing-key rotation, browser callbacks, real email transport and account recovery are not covered by the local JWKS test. These require an explicit separate E2E profile, operator test secrets and review; no service-only PASS may qualify the full `IAM-06` requirement.

## PostgreSQL invariants

- Only active linked + verified owner may provision; an ordinary verified user may not mint `PLATFORM_ADMIN`.
- The first owner's tenant RBAC is ON; no permission `CRM_READ` or `INVENTORY_RECEIVE` is implicitly granted.
- Selecting a foreign tenant or selecting after suspension fails closed.
- Concurrent first-tenant attempts for same global user serialize on that user; exactly one is admitted, with exactly one audit event; no orphan organization.
- Exact retry is idempotent; parameter changes are rejected.

## Admission

Four named tests are required by RM-QA-01B on `kms-postgres-surefire`, and the new `R4-W0.1` profile can be run in GitHub H2/PostgreSQL Module JUnit. The exact-SHA gate and manifest remain separate from real external IdP qualification. The original 75-feature strategy and the 29-item R4 priority graph are **unchanged**.

Next after PostgreSQL acceptance: connect a **real HTTPS test IdP with real account registration/email transport** and an authenticated onboarding UI/API in a separately scoped release; only then review `IAM-06` / `ORG-05` delivery status and start STO-05 seller UI.
