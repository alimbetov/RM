# RM SEC-R1.1 — tenant-scoped functional RBAC

**Branch:** feat/sec-r1-unified-domain-authorization, PR #2. **Status:** implemented internal service and Liquibase qualification candidate; public API/OIDC/production HOLD. Do not merge until CI succeeds.

## Goal

An organization may define multiple independent functional roles (director, sales manager, warehouse operator, accountant). A role is defined in and confined to **one `tenant_id = iam_workspace.id`**, not a global IAM role, KATO region or another organization's role. A user may hold multiple roles; their effective permissions are the union of the grants of active assigned tenant roles. An explicit lack of grant means DENY. There is no global `SUPERUSER` wildcard or hardcoded per-domain functional role.

## Database — additive Liquibase

New `rm:0031-tenant-scoped-rbac`:
- `iam_tenant_role`: `(tenant_id,id)` PK; `(tenant_id,role_code)` unique; display name, active, timestamp. Role codes are caller-defined, normalized uppercase, e.g. `WAREHOUSE_OPERATOR`, `SALES_MANAGER`, `ACCOUNTANT`.
- `iam_tenant_role_permission`: `(tenant_id,role_id,permission_code)` PK, role composite FK; codes validated against Java `DomainPermission`.
- `iam_tenant_user_role`: `(tenant_id,user_id,role_id)` PK; composite FK to tenant role and FK to IAM global user. Membership to same organization is also validated by service.
- `iam_tenant_rbac_config`: `tenant_id` PK, `enforced`, row_version. No row or `false` = existing SEC-R1.0 compatibility mode. Activation is explicit and requires a second active OWNER holding a functional grant, because a user may not grant themselves roles.
- `iam_tenant_rbac_event`: immutable append-only role administration evidence (actor, action, target, role, permission, timestamp).

New PostgreSQL-only `rm:0032-tenant-rbac-event-immutable`: UPDATE/DELETE trigger on RBAC evidence.

No existing Liquibase changeset is rewritten.

## Security decisions and services

`TenantAuthorization.require(actorId,tenantId,DomainPermission)` always validates active IAM user, active organization workspace and active organization membership before checking RBAC. When RBAC is enforced, permission must be present through an active tenant-owned assigned functional role. Before enforce, only original legacy SEC-R1.0 operations are allowed; newly registered SALES/INVENTORY write actions are DENIED without an explicit RBAC grant.

`TENANT_RBAC_MANAGE` remains a non-delegable IAM **organization OWNER-only** administration capability; the grant operation refuses it. Manager, viewer, employee cannot create, grant or assign roles.

`TenantRoleService` has transactions and a workspace row lock for all mutations:
- `createRole(actor,tenant,code,displayName)`
- `grant/revoke(actor,tenant,role,DomainPermission)`
- `assign/unassign(actor,tenant,targetUser,role)`
- `enable(actor,tenant)`
- `listRoles(actor,tenant)`

Rules: no role assignment/revocation for oneself; target must have active IAM user and active membership in this tenant; roles and permissions must refer to the *same* tenant; duplicate grants/assignments are no-op; audit events only for committed changes. API actorId must be derived from verified principal; **no HTTP adapter has been opened**. Data changes by privileged DBA bypass business authorization, so separate DB grants/RLS and operational procedures are required for production.

### Provisioning and rollout

1. Keep mode off for existing tenants; enumerate existing OWNER/ADMIN/MANAGER/MEMBER/VIEWER.
2. OWNER creates functional tenant roles with explicit grants; another active OWNER is assigned at least one such role to avoid lockout.
3. Assign only to verified active employees. Compare effective privileges and run dry-run against existing permissions.
4. OWNER enables RBAC; from then onward, non-admin operations require assigned functional grants. Existing base roles do not implicitly elevate permissions. `TENANT_RBAC_MANAGE` stays OWNER-only as recovery root.
5. Test revocation, user suspension, tenant suspension, cross-tenant UUID substitution and two-pod racing administration.
6. Critical operation-level IAM approval/sensitive FINANCE rights and OIDC require SEC-R1.2; staging activation only until qualified.

## Permission catalogue

Current permissions: `CRM_READ`, `CRM_PII_READ`, `CRM_WRITE`, `ORGANIZATION_LOCATION_READ/WRITE`, `SALES_ORDER_READ/CONFIRM/CANCEL`, `INVENTORY_STOCK_READ`, `INVENTORY_RECEIVE/ADJUST/RESERVE/FULFILL`, `INVENTORY_SETTINGS_MANAGE`. A permission in the catalogue is **not proof that a command-side service exists**. FINANCE/KMS/CONSENT require distinct explicit permissions and possible dual approval before being made operational; no wildcard grants.

## Acceptance evidence

- H2 + PostgreSQL tests on fresh DB.
- RBAC mode disabled: original permissions preserved; new mutating actions deny.
- Enabled: assigned permission allows, absent permission denies, union across roles.
- OWNER admin ability is nondelegable; self-assign, cross-tenant role ID and outsider reject.
- A grant removed or member suspended becomes ineffective on next check.
- PostgreSQL forbids changing/removing role administration events.
- Additional pending gates: real OIDC principal, signed session/claims, DB least-privilege, multi-process revocation races, migration on populated DB, authorization cache/invalidation load, approvals for financial write operations.

**Release:** internal API built; public endpoints and production admission remain HOLD.
