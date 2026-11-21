# RM-FILES-R1 — File metadata domain, server disk now, RustFS later

**Status:** PROPOSED — DOMAIN DESIGN; no file tables, controllers or storage implementation deployed yet.
**Branch:** `feat/rm-kms-r1-implementation`
**Priority:** Foundation alongside CRM-R1A and CATALOG-R1A.5, not after SALES.
**Deployment:** one Spring Boot modular monolith / PostgreSQL, initial local filesystem; RustFS (S3-compatible object storage) behind one Java storage port later.

## 1. Non-negotiable boundary

Files are a **separate FILES bounded context**, owned by an immutable `file_id UUID` and `tenant_id UUID`, independently of physical storage. CRM, CATALOG, SALES, FINANCE and TASKS link to `file_id` via tenant-scoped attachment records, never store server paths, RustFS bucket/key or direct public URL in their own tables.

Keep `region_zone_id` off file metadata by default: a customer's document, a product photo or an invoice can serve multiple regions. A linked sales/warehouse entity determines geographic context. `tenant_id` remains the stable authorization/KMS boundary.

Use **logical metadata in PostgreSQL and binary content outside PostgreSQL**. Avoid `BYTEA` for large files. Some small encrypted metadata and checksums may reside in PostgreSQL.

## 2. Minimum tables

| Table | Key/fields | Responsibility |
|---|---|---|
| `files.file_object` | `(tenant_id,id)` PK; `original_name`, `declared_content_type`, `detected_content_type`, `size_bytes`, `sha256`, `kind`, `state`, `created_by`, `created_at`, `completed_at`, `deleted_at`, `row_version` | Logical immutable file identity, verification, lifecycle |
| `files.file_storage` | `(tenant_id,file_id)` FK; `provider` LOCAL/RUSTFS; `storage_key`, `bucket` nullable, `object_version` nullable, `storage_state`, `migrated_at` | Current physical location, privately owned by FILES |
| `files.file_attachment` | `(tenant_id,id)` PK; `file_id`, `owner_type`, `owner_id`, `purpose`, `created_by`, `created_at`, `deleted_at` | Logical association to customer/contact/product/order/estimate/invoice/task |

Use composite FK `(tenant_id,file_id)` from storage and attachment to file. Enforce authorized owner lookup in the corresponding domain; a polymorphic `owner_type/owner_id` alone **cannot** be made referentially safe by one FK and needs explicit tenant/owner validation. If attachment relation count becomes large, use separate typed link tables (`crm_customer_file`, `catalog_item_file`, etc.) with composite FK on both sides. Do not introduce all typed tables now.

**Optional later:** `files.file_revision` for replaceable documents, `files.file_processing_event` for AV/thumbnail/OCR, `files.file_access_audit` for sensitive file reads, `files.file_migration_job` for large migration with leasing, checksums and retries. Avoid premature versions if immutable file IDs plus new attachment suffice.

### Domain entities

`StoredFile` aggregate root — immutable content, state transitions; `FileStorageLocation` internal; `FileAttachment` links owning business entity; `UploadSession` application workflow (persist only if resumable uploads become necessary).

## 3. Public application-facing contracts

```java
interface FileObjectStore {
    PutResult put(String opaqueStorageKey, InputStream data, long expectedLength, String contentType);
    InputStream open(String opaqueStorageKey);
    void delete(String opaqueStorageKey);
    boolean exists(String opaqueStorageKey);
}
```

Implementation options: `LocalFileObjectStore` (initial) and `RustFsObjectStore` (future). This is a design contract, not committed Java code; future implementation may use streaming APIs/resources to control close and range reads.

The FILES application service uses `tenantId` and authenticated actor context, checks permission against the linked business object, allocates a random `fileId`, derives an **opaque server-managed object key**, validates file size/type/contents, writes and verifies bytes, commits metadata, and returns `fileId`. Controllers never accept a filesystem path, storage key, arbitrary principal or bucket from the client.

### Operations

- `initUpload(tenant, ownerRef, metadata)` → `fileId` and upload constraints (requires owning domain authorization).
- `upload` (streaming, size-limited) → `STAGED` → `AVAILABLE` once integrity/type checks pass and owner attachment succeeds.
- `download(tenant, fileId)` → authenticated, authorized streamed bytes; `Content-Disposition` sanitized; never use name as disk path.
- `attach/detach` uses tenant-scoped owner validation; shared logical file may have multiple attachments **only within the tenant** if explicitly authorized.
- `delete`: mark logical file `DELETING`; physical cleanup deferred until unreferenced/retention expired; `DELETED` after confirmed deletion. Repeated deletion idempotent.

## 4. State model and failure handling

`STAGED → AVAILABLE → DELETING → DELETED`, plus `REJECTED`/ `FAILED` for scan/integrity failures. Only `AVAILABLE` is downloadable.

Local filesystem and PostgreSQL **do not share an ACID transaction**. Use staged write to temporary file, streaming SHA-256, `fsync` where durability required, atomic rename on the same filesystem if supported; then finalize database metadata. On failure, staged orphan cleanup job reconciles DB and storage. Do not claim atomicity across filesystem/DB.

Use secure path prefix under configured root, a derived opaque key (e.g. `tenant UUID / generated file UUID`), protect traversal and symlinks, and never trust `original_name` as a path. Reject unsupported content and enforce allowlisted extensions, MIME sniffing, file size, quotas. Add malware scanning gate (e.g. ClamAV) before exposing attachments where external uploads are accepted; pending files remain non-downloadable until passed.

Secrets: personal documents/photos can contain PII. `crm_pii` split of table fields **does not protect file bytes**. Require encryption at rest (KMS tenant-scoped envelope or another qualified encryption strategy), HTTPS transport, narrow filesystem permissions, private buckets and access audit for sensitive files. Design encryption streaming/chunking and key-generation metadata before first production PII file. Never use KATO code or Java hashCode as a file encryption key.

Initial local storage is safe only on a single-instance setup or **genuinely shared persistent storage** mounted consistently on all pods. A local directory in each ephemeral pod produces missing files after rescheduling. For multi-pod production use durable shared mount or adopt RustFS earlier. Backups require PostgreSQL metadata and blob bytes captured to a consistent recoverable point.

## 5. RustFS migration without changing business links

- Keep `file_id`, `tenant_id`, attachments, filenames and checksums immutable.
- Create separate `RustFsObjectStore` adapter; configure S3 endpoint, bucket and credentials outside database/source control.
- Read via `file_storage.provider`, not global static configuration. New writes may switch to RUSTFS, old files stay on LOCAL while copying.
- Migration per file: read LOCAL → copy to RustFS → verify size + SHA-256 by **reading the destination** → atomically switch `file_storage` pointer under optimistic lock/fencing → retain source for rollback window → async garbage collection.
- Retry-safe/idempotent migration with checksum and object version; concurrent downloads must still read the valid current location; do not delete LOCAL until verified and after active readers are drained/covered.
- For sensitive encrypted objects compare checksums of the stored ciphertext *and*, if required, authenticated decryption in authorized verification jobs. Original plaintext checksums can leak equality; protect scope and access.
- API and CRM foreign keys remain unchanged across transition.

## 6. Domain-specific attachments

| Owner | Examples | Stage |
|---|---|---|
| CRM Customer / Contact | agreements, customer documents, correspondence | CRM-R1A |
| CATALOG Item / Variant | product photos, technical specifications, brochures | CATALOG-R1A.5 |
| SALES Order / EstimateRevision | signed orders, immutable PDF estimate snapshots, delivery proofs | SALES-R1B/C |
| FINANCE Invoice / Payment | invoice PDF, receipts, bank confirmations | FINANCE-R1D |
| TASKS Activity | communications attachments | TASKS-R1F |
| MARKETING | campaign assets with legal consent checks | MARKETING-R1E |

Private file download requires both FILES permission and **owning domain object permission**; auditors have no implicit PII file read.

## 7. Minimum acceptance tests

1. File from tenant A cannot be listed, linked, downloaded or deleted by B even with guessed file UUID.
2. Path traversal, fake MIME, disallowed extension, over-limit size, corrupt upload and missing chunks fail closed.
3. After restart the binary and metadata remain readable; backup/restore validates size and checksum.
4. Physical write failure leaves no `AVAILABLE` record; SQL commit failure leaves only a recoverable staged orphan.
5. An attachment cannot cross to a customer/order of another tenant.
6. Download is blocked for `STAGED`, `REJECTED` and `DELETED`, and for an unauthorized actor.
7. Filename/header injection is escaped; sensitive content never appears in logs, URLs or audit payloads.
8. Migration LOCAL → RustFS preserves fileId, content and business links, with injected copy/verify/switch failures and retry.
9. Multi-pod path verifies shared storage; an ephemeral local pod filesystem must **not** be accepted as production-grade persistence.
10. Repeated idempotent operations do not create duplicate logical attachments.

## 8. Development sequence

**FILES-R1.0 (alongside IAM/GEO and before CRM attachments):** Liquibase 3-table schema, domain model and local filesystem port, streaming uploads/downloads, authorization, tenant constraints, staged lifecycle and recovery tests. Gate: PostgreSQL + H2 + filesystem restart and boundary negatives.

**FILES-R1.1 (with CRM-R1A / CATALOG-R1A.5):** customer documents and product photo attachments, access checks and at-rest encryption plan/implementation for any sensitive files.

**FILES-R1.2 (later):** RustFS adapter, dual-read single-write migration, integrity verification, retries, switching and cleanup. No change to CRM keys or APIs.

**No large universal file-management platform, public URLs, CDN, deduplication across tenants or premature OCR in R1.**
