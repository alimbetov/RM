# RM FILES — RustFS flag-driven storage review

**Scope:** `base_dev`, service-only. **Status:** SDK-contract/H2/PostgreSQL qualification GREEN; live RustFS E2E and production admission HOLD. No controller or SecurityConfig updates, no merge into main.

## Implemented

1. Optional RustFS `FileStoragePort` adapter using official AWS SDK for Java v2, S3-compatible path-style SigV4 endpoint, explicit region, service identity and private pre-existing bucket.
2. `SwitchableFileStorage` routes *new* file intents by `RM_FILES_RUSTFS_ENABLED` (false LOCAL, true RustFS). Existing bytes route using persisted storage key prefix: unprefixed LOCAL vs `rustfs/` prefix. A flag flip never moves or deletes old objects.
3. RustFS stage uses a private, bounded (25 MiB max) temporary spool with server-side SHA-256 and signature check. The temporary plaintext spool is removed after the attempt. PUT stage, HEAD size, COPY to final, HEAD size, DELETE stage; final file content is verified by rereading byte stream and matching SHA-256 before AVAILABLE.
4. Real ClamAV `INSTREAM` adapter exists but is explicitly opt-in and has no live daemon qualification; default scanner is fail-closed ERROR. A synthetic test scanner is only injected by test code.
5. PostgreSQL `crm.file_storage.provider` commits LOCAL or RUSTFS as actually used. Existing LOCAЛ rows remain unchanged. No secondary file tables; no automatic migration job.
6. `FileRecoveryService` uses private backend-aware enumeration for orphan cleanup. Temporary RustFS network errors propagate as IO failures instead of being treated as proved object corruption.
7. Application-level AES-GCM binary encryption and the opt-in encrypted LOCAL backend were removed following the chosen no-file-encryption policy. Existing customer field encryption remains unchanged. `encrypted_format_version` in SQL is retained to differentiate historical formats, and plaintext 0 is the only active FILES storage mode.
8. CRM attachment metadata and binary read remain gated by VerifiedActor, tenant/RBAC, CRM owner/type and PII permissions, file clean state, and stored checksum. No public GET or direct presigned access.

## Evidence at code head

- H2 and PostgreSQL tests compile and pass on `base_dev`.
- `RustFsStorageQualificationTest`: 3 mocked S3 SDK tests (staged PUT/COPY/HEAD/DELETE/LIST, signature denial, transient S3 outage vs false corruption, flag/local routing and missing-configuration denial).
- `FilesR10bQualificationTest`: 18 functional tests including plain-file CRM owner read, permission revocation, corruption/recovery, concurrence and rollback.
- `FilesR10aQualificationTest`: 5 tests. Populated PostgreSQL PII regression preserved.
- CI evidence must be on the exact final HEAD; GitHub Actions links are attached separately to PR review discussion.

## Boundaries / security and ops HOLD

- **No live RustFS container/daemon E2E yet.** SDK mock success does not prove real RustFS API behavior, bucket access policy, S3 compatibility, versioning, or copy semantics with the installed image.
- **No production without ClamAV and storage operational review.** Scanner mock CLEAN never qualifies production files. File-content signature matching alone is not a safe parser or CDR.
- **No application-level encryption by explicit design.** Files and temporary spool contain original bytes; restrict filesystem, storage service accounts, bucket policies, networks, backups and disks. Personal data handling requires separate applicable legal/privacy security admission; not waived by RBAC.
- RustFS endpoint and credentials must remain configured when any historical RUSTFS objects remain and reads are needed, even if new uploads are switched back to LOCAL.
- LOCAL and RUSTFS have no automated migration, multi-node failover or load qualification; restart and backup/restore need operational trials.
- Storage switch is applied at **process restart**, not runtime hot toggling. No database data rewrite occurs.
- No production release, no merge into `main`, no HTTP controllers or `SecurityConfig`.

See [flag rollout instructions](../operations/RM-FILES-RUSTFS-FLAG-ROLLOUT-R1.md).
