# RM FILES-MEDIA-R1.0 — executable service-core decisions

**Status:** implementation contract / DESIGN ONLY, not qualified or production-ready. **Branch:** `base_dev`. **Parent:** [RM-FILES-R1.md](RM-FILES-R1.md) (currently R1.1 target specification); [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md). No controllers, SecurityConfig, new microservices, merge or production access until independent admission.

## 1. Architecture decision

Use one modular Java 21/Spring Boot FILES domain. PostgreSQL is the authority on tenant identity, file lifecycle, attachments, owner references, idempotency and permissions. Binary bytes live behind a streaming `FileStoragePort`, initially a private persistent LOCAL directory; RustFS S3 later via the same port, without a separate HTTP/Rust service. Never use public bucket ACL or user-defined file paths.

* `fileId` identifies one immutable content object, `attachmentId` identifies a specific link to a business object, and `(ownerType,ownerId)` identifies a typed domain-owned resource. Every persistent query includes tenant_id.
* One physical content for each available fileId; revisions use new file IDs, never overwrite existing AVAILABLE content.
* The object owner, not FILES alone, decides whether a given employee may attach/read/detach this resource. A FILES grant must not bypass CRM_PII_READ, customer assignment or classification.
* Code must use public `AuthenticatedFilesCommands`/`AuthenticatedFilesQueries` requiring `VerifiedActor`; package-private raw UUID actor handlers, matching SALES and CRM conventions. Do not claim VerifiedActor is real HTTP/OIDC security prior to its gate.

## 2. Minimal interfaces: one vertical before generalizing

* `FileStoragePort.putStaged(InputStream,maxBytes)` (bounded, streamed, server-owned temporary key; compute SHA-256 from actual bytes); `inspect` (existence, size, hash), `promote` (unique final key, never overwrite), `open` (stream), `delete` (idempotent).
* `FileScanPort.scan(stagingRef)`: `CLEAN | INFECTED | ERROR`; never treat ERROR/timeouts or unavailable scanner as CLEAN. Fake scanner only in tests, never a production implementation.
* `FileOwnerAccessPort` is typed by owner kind. Implement first **CRM_CUSTOMER** only when the CRM domain can enforce the object's actual view/assignment/PII rules. `CATALOG_ITEM` is second. No universal owner SQL table or caller-supplied arbitrary ownerType.
* Internal business operations: `begin(actor,tenant,ownerRef,metadata,requestKey)`, `accept(intent,stream)`, `complete(intent)`, `attach(actor,tenant,fileId,ownerRef,purpose,requestKey)`, `list(actor,tenant,ownerRef,filter,page,size)`, `openByAttachment(actor,tenant,attachmentId)`, `detach(actor,tenant,attachmentId)`, and `requestDelete(actor,tenant,fileId)`. R1.0 first qualify begin/accept/complete and protected own-draft read; attach owner vertical follows in R1.1. **No direct public fileId download.**
* Avoid a flexible plugin registry, workflow engine, Redis, presigned URLs, image processors and a second persistence model.

## 3. RBAC and object checks

Introduce finite `FILES_UPLOAD`, `FILES_READ`, `FILES_ATTACH`, `FILES_DETACH`, `FILES_DELETE` permissions when corresponding operations are implemented; `FILES_PUBLISH` only in future public gate. Update `TenantAuthorization.permitted` exhaustive switch **explicitly deny new FILES permissions in unenforced legacy RBAC mode**, rather than granting to OWNER/MANAGER by default. Assign functional grants through active tenant roles in enforced RBAC mode. Permission is necessary but insufficient; owner authorization and file classification are independently mandatory.

The admitted order for mutations is verified actor -> tenant-bound IAM transaction locks via `requireForCommand` -> typed owner access (tenant, state, permission) -> file/attachment row locks -> mutation. Define and test lock order including concurrent owner status/permission revocations. Do not hold DB transactions during antivirus scans or full file streaming; revalidate owner/permissions before final metadata commit.

## 4. Persistence: additive migrations only

Reuse existing `crm.file_object`, `crm.file_storage`, `crm.file_attachment` and file indexes from Liquibase 0017/0019; leave old changesets untouched. Add only minimum operational fields: `file_object.uploaded_by`, `scan_status`, `content_class`, `completed_at`; `file_attachment.detached_at` and optional `created_by`; a small durable request key mapping only if the existing tables cannot enforce unambiguous replay. Add unique active attachment constraint with careful populated-database precheck, plus useful `(tenant_id,owner_type,owner_id,created_at,id)` index. SQL constraints must enforce legal state transitions or service must lock/version-check and test forbidden transitions. Original filename stays encrypted.

For storage key: opaque server-assigned namespace `tenant/fileId/random`; keep keys private. The current `file_storage` single-row `(tenant_id,file_id)` model is enough in LOCAL R1.0; versioned location and migration history are postponed until RustFS.

## 5. Transaction boundaries and recovery

External filesystem/AV cannot join PostgreSQL ACID. Explicitly represent:
- `STAGED`: intent committed, bytes may be absent/incomplete and are *not available for read*.
- `AVAILABLE`: server verified actual persisted bytes, hash, size, MIME, AV CLEAN, owner policy, and committed state; reads must still pass current authorization.
- `REJECTED`: inspection, scan or policy failed; never downloadable.
- `DELETING` then `DELETED`: access denied immediately; physical cleanup proceeds idempotently with retention/legal hold checks.

Proposed minimal sequence: DB commit intent STAGED -> stream to private staged key with bounded I/O -> verify, scan -> durable final private key created immutably -> short PG transaction recheck tenant/owner, compare row version, set ACTIVE storage + AVAILABLE state -> return. If PG commit fails, the unreferenced blob remains private and the bounded reconciler can purge it later. Failure at any step must not persist a false AVAILABLE. When local restart occurs, durable directory and persisted staged refs are rechecked; never use ephemeral pod-local storage in production.

Uploads are replay-safe per tenant and request key: same payload returns same intent/result; different payload with same key must fail. Finalize is idempotent. Concurrent workers lock a file row; prevent duplicate attachments/unsafe multi-owner classification. An upload that was completed but crashed before acknowledgment must be recovered through database state, not recreated with another file ID.

## 6. Security defaults

R1.0 qualifies **synthetic binary content only**. No live client PII, contracts, or images before tested authenticated binary encryption, real scanner, key recovery, and real owner policy. Only JPEG/PNG/WebP and PDF *after* signature inspection, strict byte limits (proposed 20 MiB image, 25 MiB PDF), safe path handling and scanner success. No SVG, HTML, arbitrary archives or video. Binary crypto is a separate `FileContentCryptoPort`, not string `TenantCryptoPort` misapplied to byte arrays. At-rest plaintext even in quarantine of real PII is forbidden until encryption is available. Keep test fixtures fully synthetic.

The LOCAL adapter root is private, absolute, configured outside webroot, with no traversal/symlink following, create-new and bounded streams, sanitized errors; filename is never the storage key. Explicitly deny reads for STAGED/REJECTED/DELETING/DELETED or stale owner permission. Avoid leaking keys, filenames, PII, or hashes to logs/audit.

## 7. Evidence matrix for R1.0 acceptance

- FILE-01 wrong tenant, guessed fileId and attachmentId, revoked/suspended actor, no external I/O before denial.
- FILE-02 upload initiation: exactly one intent for same key/payload; different payload conflict; two workers same key, one intent.
- FILE-03 streams: zero/oversize, interrupted or truncated upload, server-side SHA mismatch, malicious signature/MIME, symlink and traversal denial.
- FILE-04 lifecycle: no AVAILABLE without persisted matching bytes + CLEAN; scanner ERROR, timeout and INFECTED fail closed.
- FILE-05 rollback/failure at metadata insert, staging write, scan, promote, final PG commit: no false AVAILABLE; orphan cleanup and idempotent retry.
- FILE-06 restart: durable staging and AVAILABLE rechecked; recovered stream checksum matches record.
- FILE-07 authorization/owner: owner absent, foreign tenant, archived, denied CRM_PII_READ, concurrent owner status/revocation vs attach or protected read. Only when typed CRM owner adapter implemented.
- FILE-08 attachment: double attach/detach, multi-owner classification denied, file retained while another permitted active attachment exists.
- FILE-09 read/list: stable order, page <=100, no unintended PII in metadata, no UUID existence leakage to another tenant.
- FILE-10 PostgreSQL populated migration, indexes, row versions, failed migration rollback, H2 smoke and exact SHA evidence.

Run a focused H2 + real PostgreSQL suite first, then local filesystem restart/partial-failure E2E. Real ClamAV, binary crypto, customer-owner access, RustFS migration, two-node failover, public image publication and operational load are additional gates; never label all of FILES QUALIFIED solely because R1.0 tests pass.

## 8. Delivery sequence and ownership

1. **R1.0A**: FILES permissions with deny-by-default legacy branch, additive schema + metadata service/DTO and negative tenant tests.
2. **R1.0B**: local streaming storage + checksum + deterministic fake scanner only in tests, STAGED/AVAILABLE and error/recovery tests.
3. **R1.0C**: private protected stream, rollback/restart/reconciler, version conflicts and concurrent idempotency. GREEN exact-head CI.
4. **R1.1**: owner adapter CRM_CUSTOMER + CATALOG_ITEM, binary encryption + real AV, attachment policy; independent security review.
5. **R1.2**: RustFS storage, migration/recovery, two-node and restore qualification.
6. **R1.3**: HTTP controllers and SecurityConfig only after global RM service-first freeze gate, real verified identity and independent reviews; public media is a separate explicit admission.

**Non-goals now:** no Java controller, no SecurityConfig, no `main` merge, no production PII upload, no Rust microservice, no CDN/video/OCR/multipart.

**Definition of success:** exact-head CI demonstrates working synthetic `begin → stream → validate/scan → finalize → authorized read → retry/reconcile` with denial/rollback and no false availability, before R1.1.
