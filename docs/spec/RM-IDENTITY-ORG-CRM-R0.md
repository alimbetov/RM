# RM-IDENTITY-ORG-CRM-R0 — Пользователи, тарифы, организации и CRM

**Статус:** PROPOSED для согласования. Только проектирование сущностей; Liquibase/JPA не изменены.  
**Ветка:** `feat/rm-kms-r1-implementation` (без merge в main).  
**Архитектура:** Spring Boot modular monolith, PostgreSQL production, H2 dev, multi-pod, KMS tenant-scoped.

## 1. Семь пользовательских режимов

Пользователь перечислил 6 режимов: platform admin, individual free, individual paid, organization free, organization paid, platform auditor. **Седьмой режим предложен предварительно: сотрудник организации**.

| Mode | Security Role | Workspace | Billing |
|---|---|---|---|
| Platform administrator | PLATFORM_ADMIN | Platform | N/A |
| Personal free | PERSONAL_OWNER | PERSONAL | FREE |
| Personal paid | PERSONAL_OWNER | PERSONAL | PAID |
| Organization free | ORG_OWNER | ORGANIZATION | FREE |
| Organization paid | ORG_OWNER | ORGANIZATION | PAID |
| Platform auditor | PLATFORM_AUDITOR | Platform scope (audit) | N/A |
| Organization employee | ORG_MEMBER | ORGANIZATION | Inherits organization subscription |

**NOT** seven immutable enums in UserAccount: role (authorization), workspace type (scope), subscription (feature/limits) are orthogonal. Organization member can have team-granted roles.

## 2. Identity domain (`iam_*`)

- **UserAccountEntity**: `id UUID PK`, `externalSubject VARCHAR UNIQUE` (OIDC subject if IAM provider), `emailNormalized` unique nullable, `phoneE164` unique nullable, `displayName`, `status` (PENDING/ACTIVE/SUSPENDED/DELETED), `createdAt`, `updatedAt`, `rowVersion`. Require at least one verified authentication identifier under the chosen login policy; passwords do not belong in CRM tables.
- **UserProfileEntity**: `userId PK/FK`, locale, timeZone, avatarRef, encrypted PII via KMS where required.
- **GlobalRoleAssignmentEntity**: `userId`, `role` (PLATFORM_ADMIN/PLATFORM_AUDITOR), grantedBy, grantedAt, revokedAt; platform-wide authority, never granted by organization admins.
- **OrganizationMembershipEntity**: `id PK`, `userId FK`, `organizationId FK`, `role` (OWNER/ADMIN/MANAGER/MEMBER/VIEWER), `status` (INVITED/ACTIVE/SUSPENDED/LEFT), joinedAt, invitedBy, `rowVersion`; unique active membership `(organizationId,userId)`. Invitations may target nonregistered email/phone as separate invitation record.
- **WorkspaceEntity**: `id UUID PK`, `kind` (PERSONAL/ORGANIZATION), `organizationId FK nullable`, `ownerUserId FK nullable`, `status`, createdAt. Exactly one owner source per kind (CHECK). One personal workspace per user; one organization workspace per organization. **Workspace ID is the stable tenantId for KMS and business rows.**
- Optional **AccessGrantEntity** later for record-level sharing, always `workspaceId` scoped. Do not implement broad platform auditor decrypt access implicitly.

## 3. Organization domain (`org_*`)

- **OrganizationEntity**: `id UUID PK`, `legalName`, `displayName`, `organizationType` (LEGAL_ENTITY/SOLE_PROPRIETOR/OTHER), `countryCode`, `registrationNumber` (e.g. BIN/IIN where legally appropriate; nullable, country-specific validation), `status` (DRAFT/ACTIVE/SUSPENDED/CLOSED), createdAt, updatedAt, rowVersion. Do not require a BIN before a free trial workspace exists; verified legal identity separately.
- **OrganizationLegalProfileEntity**: `organizationId FK`, address/verification metadata; sensitive identifiers encrypted and access-controlled.
- **OrganizationUnitEntity**: `id`, `organizationId`, `parentUnitId`, `name`, `status`. Tree/no cycles checked by service; optional until teams are needed.
- **OrganizationInvitationEntity**: id, organizationId, target email/phone hash or encrypted target, invitedRole, tokenHash, expiresAt, status, invitedBy; avoid storing raw token.

## 4. Subscription and entitlements (`billing_*`)

- **PlanEntity**: `id`, `code` (FREE/PERSONAL_PRO/ORG_PRO etc), version, status.
- **PlanEntitlementEntity**: `planId`, `featureKey`, `limitValue` nullable, units/semantics; immutable revision used for reproducible billing.
- **SubscriptionEntity**: `id`, `workspaceId`, planId/planVersion, status (TRIAL/ACTIVE/PAST_DUE/CANCELLED/EXPIRED), startsAt, endsAt, currentPeriodEnd, rowVersion. Only one effective subscription per workspace at a time; atomic transition. User roles do not change on upgrade.
- **UsageCounterEntity**: `workspaceId`, featureKey, period, used, version; enforce quotas safely across pods with atomic PostgreSQL updates (not Caffeine).

## 5. CRM bounded context (`crm_*`), every row workspace-scoped

- **CrmCustomerEntity**: `id UUID PK`, `workspaceId FK`, `customerType` PERSON/COMPANY, displayName, lifecycleStage, ownerMembershipId nullable, createdAt, updatedAt, rowVersion.
- **CrmContactEntity**: id, workspaceId, customerId, fullName, position, encrypted phone/email envelopes, isPrimary, rowVersion. Use `CryptoContext(workspaceId, entityType, contactId, fieldName)`.
- **CrmLeadEntity**: id, workspaceId, customerId nullable, source, stage, assignedToMembershipId nullable, estimatedValue/currency, createdAt.
- **CrmDealEntity**: id, workspaceId, customerId, pipelineId, stageId, amount/currency, assignedToMembershipId, expectedCloseAt, rowVersion.
- **CrmPipelineEntity** + **CrmStageEntity**: workspaceId, name/order, archived state; explicit stage references and guarded transitions.
- **CrmActivityEntity**: id, workspaceId, relatedType/relatedId, activityType, dueAt, status, assignedToMembershipId, creatorUserId.
- **CrmNoteEntity**: id, workspaceId, related record, encrypted body if sensitive, createdBy, createdAt.
- **CrmAttachmentEntity**: id, workspaceId, objectId, storageKey, mediaType, checksum, size, uploadedBy, malwareScanStatus. No plaintext secrets or raw URLs in DB. File encryption policy separate.
- **CrmAuditEventEntity**: id, workspaceId, actor, action, resourceType/id, occurredAt, correlationId, metadata sanitized, no plaintext/DEK. Durable append-only retention policy.

MVP order: UserAccount → Workspace → Organization + Membership → Subscription → Customer + Contact → Lead + Deal → Activity + Audit. Units, granular ACL, attachments and complex billing after MVP.

## 6. Relationship and access invariants

- One user may have one personal workspace **and** membership in multiple organizations. Organization subscription belongs to its workspace and never follows individual membership.
- Every CRM FK/reference and service query includes `workspaceId`; enforce composite `(workspace_id,id)` FK/unique patterns to prevent cross-tenant joining. Do not trust workspaceId supplied by frontend without membership authorization.
- PLATFORM_ADMIN does not automatically grant permission to decrypt sensitive tenant PII; audited break-glass is a separate privileged control.
- PLATFORM_AUDITOR has scoped READ of metadata/audit records only unless a separate explicit legal/privacy basis and grant exists. No CREATE/UPDATE/DELETE, no self-elevation, no raw KEK/DEK, no sensitive decrypt by default.
- ORG_OWNER may administer own organization only; ORG_MEMBER is tenant-scoped and permissions granted via membership, not global.
- Multi-pod role/subscription changes require durable authorization-source consistency; do not use Caffeine alone to revoke access. Quotas enforced in PostgreSQL.
- Workspace lifecycle: create workspace and provision tenant KMS with idempotent commands; until crypto-ready, reject encrypted-field writes. Do not assume global ACID across microservices.
- Record deletion, exports, sharing and organization closure require a separate retention/authorization policy and audit.

## 7. Required qualification scenarios

IAM-01 global admin cannot be impersonated by org owner; IAM-02 auditor read-only even via direct REST; IAM-03 no default auditor PII decrypt; IAM-04 membership A cannot access org B; IAM-05 user may join multiple organizations and switch context; IAM-06 suspended membership denied with stale sessions; IAM-07 one personal workspace per user; ORG-01 owner role unique policy, ORG-02 invitations expire safely; BILL-01 free→paid retains user and data, BILL-02 subscription expiry enforces feature limits without deleting data, BILL-03 2 pods cannot overrun quota; CRM-01 cross-tenant ID guessed rejected, CRM-02 composite FK prevents linking contact to customer of another workspace, CRM-03 encrypted phone persists/reloads across process restart, CRM-04 auditor cannot mutate or decrypt, CRM-05 concurrent optimistic updates detect conflicts; KMS-CRM-01 workspaceId must equal CryptoContext.tenantId.

**Acceptance:** H2 dev smoke and isolated PostgreSQL integration, REST authorization negatives, actual multi-pod concurrency tests and backup/recovery for encrypted fields. This document does not imply implementation is complete.

## 8. Pending product decision

**Seven roles ambiguity:** only six modes explicitly named. Proposed seventh = `ORG_MEMBER` (company employee); confirm or replace before freezing the R1 role catalog.
