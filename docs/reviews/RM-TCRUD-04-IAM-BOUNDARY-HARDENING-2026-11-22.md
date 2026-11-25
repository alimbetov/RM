# TCRUD-04 — IAM Public Entrypoint Boundary Hardening

**Branch:** `base_dev` only. **Scope:** tenant-scoped RBAC Java service trust boundary; no HTTP controllers, schema changes, wildcard permissions or merge into `main`.

## Confirmed issue

`TenantRoleService` historically declared ten public Java methods with an initial caller-supplied `UUID actorId`: `createRole`, `grant`, `revoke`, `assign`, `unassign`, `enable`, `setRoleActive`, `listGrants`, `listAssignments`, `listRoles`. The methods verify current organization ownership and tenant isolation internally, but Java consumers in other packages could supply the UUID of a different OWNER and thereby trigger an operation **as that owner**. An ownership SQL check is not caller identity authentication.

## Design and implementation

1. These ten methods remain usable **inside `kz.rm.iam`** by existing IAM domain logic and qualification tests but are now package-private. The class remains a Spring bean for its verified adapter, not an externally callable Java application port.
2. `VerifiedTenantRoleCommands` is the public entrypoint. It accepts `VerifiedActor`, calls `owner.userId()` (which checks principal expiry), and delegates into the unchanged internal RBAC algorithms. Extended facade operations: `enable`, `listGrants`, `listAssignments`, in addition to role create/grant/revoke/assign/unassign/activation/list roles.
3. Seven cross-domain qualification files in CRM, FILES, INVENTORY, ORGANIZATION and SALES now provision permissions only via the verified facade. Test OIDC resolver is synthetic and confined to test code.
4. `Tcrud04VerifiedEntrypointQualificationTest.legacyTenantRoleUuidEntryPointsAreNotPublic` rejects all ten public UUID methods; `verifiedIamFacadeCoversRoleLifecycleAndReadOperations` asserts the facade is complete and uses `VerifiedActor`.
5. `TenantRbacQualificationTest.tcrud04VerifiedOwnerCanAdministerButForeignActorCannotMutateRbac` validates NULL/foreign/non-owner rejection, allowed owner grant and assignment, activation, read-back and permission revocation.
6. Existing PostgreSQL two-connection race tests remain unmodified; the internal transactions, actor/workspace lock ordering, optimistic versioning and immutable audit are not rewritten.

## Source/permission/tenant/QA matrix

| Entrypoint | Auth boundary | Permission and tenant policy | Test evidence | Gate |
|---|---|---|---|---|
| `VerifiedTenantRoleCommands.createRole/grant/revoke` | VerifiedActor; UUID methods package-private | current ACTIVE OWNER of requested organization, lock IAM account then tenant, assignable permission restriction | `TenantRbacQualificationTest` plus tcrud04 test | exact SHA CI |
| `assign/unassign` | VerifiedActor | no self-assignment, target active member of tenant, role composite tenant keys | `TenantRbacQualificationTest` | exact SHA CI |
| `enable` | VerifiedActor | tenant OWNER; at least one active functional permission assignment | `TenantRbacQualificationTest` | exact SHA CI |
| `activateRole/listRoles/listGrants/listAssignments` | VerifiedActor | tenant OWNER / TENANT_RBAC_MANAGE; tenant scoped SQL | `TenantRbacQualificationTest`, boundary reflection tests | exact SHA CI |
| permission revoke vs in-flight command | IAM-only lock test | database row locks: no re-admission after revoke, legal commit order | `TenantRbacRevocationQualificationTest` 11 PG race cases | exact SHA CI |

## Scope limits and residual risks

The Java language/package guard applies to direct calls from other packages. Code already executing inside IAM remains trusted internal code. Spring reflection or a future untrusted adapter can still subvert the intended boundary if it uses unrestricted bean lookup/reflection; require architecture checks and explicit design review for new adapters.

Other public IAM services (`EmailIdentityService`, `PlatformAdminGovernanceService`, registration and bootstrap paths) remain separate admission surfaces and must not be described as globally QUALIFIED through this RBAC-only change. `AutoOrderService.checkout` is deliberately anonymous and requires transport admission/anti-abuse before public HTTP exposure. TCRUD-03 full negative method/JOIN mapping remains PARTIAL.

**Acceptance:** H2, PostgreSQL integration, IAM populated migration, CRM populated migration, and RustFS/ClamAV live E2E all `success` at same HEAD. PostgreSQL revocation cases must execute rather than skip. Do not infer production readiness from CI.

**Production:** HOLD. **Feature Freeze:** unchanged.
