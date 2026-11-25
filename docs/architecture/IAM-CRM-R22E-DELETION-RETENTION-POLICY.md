# IAM–CRM-R2.2E — Deletion Security, OIDC Revocation and Data Retention Policy (draft)

**Status:** Engineering policy draft, NOT legal approval, NOT production admission. **Branch:** `base_dev`. **Controls:** service-only; controllers/UI STOP; no merge into `main`.

## Scope and data ownership

IAM is the sole owner of global user identity and cryptographic email/phone/profile material. CRM owns tenant customer records and buyer-link audits. SALES owns orders and financial/stock evidence. Erasing an IAM account **must not** cascade delete CRM customer, order, immutable audit or accounting records. Surviving UUID references are **pseudonymous**, not guaranteed anonymous: correlation with tenant-held operational records can re-identify people.

## Lifecycle decisions

1. Only an ACTIVE, independently authenticated PLATFORM_ADMIN may initiate the current internal deletion command. Separate requester consent, identity proof, legal hold checks, appeal mechanism and authorized approval workflow remain **STOP / unresolved**.
2. An active organization OWNER must transfer ownership, and a PLATFORM_ADMIN must relinquish global privileges, before deletion.
3. State becomes `DELETED` in a single PostgreSQL transaction. Live IAM subject, email, phone and profile secrets are removed; email identity IDs remain only for FK continuity, with active lookups cleared and encrypted email overwritten. Pending email deliveries and unconsumed verification proofs are invalidated.
4. Record immutable deletion evidence containing actor, target, event time and idempotency key. Do not copy deleted contact details into audit or logs.
5. For identities with an OIDC subject, write a durable **encrypted revocation intent** in the same database transaction. This establishes only `PENDING`. A separate trusted provider-specific consumer must execute global session logout/token invalidation, independently prove the provider response and mark `ACKNOWLEDGED`. Until then, **external OIDC revocation is NOT confirmed** and production admission remains HOLD.
6. Locks on the IAM account row serialize account deletion against the buyer-link confirmation, email-change confirmation and verification paths; transaction failures roll back the local de-identification and outbox atomically.
7. No automated restoration from DELETED. A new identity must pass new verification and cannot silently take ownership of historical SALES rows or old CRM links.

## Retention matrix — deadlines require legal and business approval

| Data class | On deletion | Retention deadline | Owner and evidence |
|---|---|---|---|
| Live IAM external subject, plaintext legacy columns | Clear from active user row | Immediate at successful transaction | IAM / tombstone tests |
| IAM encrypted email, phone, profile, contact indexes | Delete/overwrite live values; retain only FK-safe non-identifying rows | Immediate at successful transaction | IAM / PostgreSQL migration and service tests |
| Pending verification challenges and delivery jobs | Invalidate/mark FAILED; prevent future emission | Immediate functional invalidation; eventual purge TBD | IAM / outbox consumer qualification pending |
| Encrypted OIDC revocation subject | Keep while PENDING for trusted external session revocation | Deadline and failure-escalation SLA **TBD**; purge after ACK and approved audit period | IAM Security / provider integration **HOLD** |
| Immutable IAM deletion/security audit | Retain minimal event ID, actor/target IDs, reason-free request key/time | **TBD by legal** | Security, governance |
| CRM customer link/adjudication history | Retain tenant-qualified UUID and append-only actions; block order projection | **TBD by legal and contract** | CRM / consent and tenant controls |
| SALES contracts, payments and order facts | Retain for financial/legal obligations without deleting FK owner IDs | **TBD by statutory rules per jurisdiction** | SALES, Legal |
| WAL, PITR backups, replicas, exports, object storage, email providers and analytics | Cannot assume live deletion sanitizes historical copies; enumerate and restrict restore/access | Backup expiration/purge schedule **TBD** | SRE, Security, Legal |
| Application logs/traces | Avoid subject, email, tokens and ciphertext; audit existing data stores | Redaction rollout and expiry **TBD** | Observability/SRE |

**No duration or statutory entitlement is asserted here.** Approve separately for Kazakhstan jurisdiction, contractual obligations, international transfers and any legal holds before operationalizing deletion.

## Mandatory R2.2E gates

- Dedicated PostgreSQL exact-SHA suite: `delete × buyer confirm`, `delete × email change confirm`, and verification challenge races in both commit orders; deny stale JWT/principal after deletion; preserve CRM/SALES FK and immutable audit.
- Fault-injection after local scrub but before deletion/outbox commit: transaction rollback leaves user active and contact material intact; retry creates precisely one tombstone and one PENDING provider revocation intent.
- Populated PostgreSQL upgrade from the prior qualified schema: old composite link/event identifiers, versions, actions and SALES order references must survive unchanged.
- External OIDC: negative/retry/ACK/duplicate/failure/recovery tests against a real provider or contract-certified environment are required for provider admission; absence of these tests means HOLD.
- Backup restore tests must not re-enable a DELETED account or republish old verification challenges without reconciliation; requires a separate disaster-recovery drill.
- Legal review and recorded retention authorization are required before production.

## Operational policy

**Production HOLD** unless every relevant gate passes. Local PostgreSQL success is an engineering qualification, not evidence of statutory compliance, complete backup destruction or successful logout from external providers.
