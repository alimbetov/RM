# RM FILES-MEDIA-R1.0B — synthetic streaming and fail-closed lifecycle qualification

**Branch:** `base_dev`. **Status:** implementation with CI qualification required on exact HEAD; not production-ready.
**Parent:** [FILES R1.0 core decisions](../spec/RM-FILES-R1.0-SERVICE-CORE-IMPLEMENTATION-DECISIONS.md).
No controllers, public URLs, real customer files, RustFS, or SecurityConfig.

## Implemented

- `FileStoragePort` and private persistent single-node `LocalFileStorage`: opaque server-generated tenant/file/version staging keys, create-new, no file name/path from user, symlink/path traversal guard, bounded streaming, SHA-256 over actual bytes, exact declared-size enforcement, private storage, atomic promotion.
- `FileContentInspection`: allowlisted JPEG, PNG, WebP, PDF signature gate. This is **not** a complete file parser, polyglot detection, AV or CDR; test fixtures are synthetic only. Declared MIME must match the inspected signature. Do not ingest real confidential files until separate qualification.
- `FileScanPort`: default `UnavailableFileScanner` returns ERROR, never CLEAN. Only a dedicated test-context `@Primary SyntheticScanner` can approve synthetic fixtures.
- `FileUploadCompletionService.acceptAndComplete(VerifiedActor,tenant,fileId,stream)`: checks FILES_UPLOAD and uploaded_by before touching stream. Streaming and scan take place **outside** the DB transaction. A short, transactional commit rechecks actor/tenant RBAC, tenant/file, size, state, and storage uniqueness; AVAILABLE only after accepted checksum and scanner CLEAN, atomic physical promotion and persisted final pointer.
- `INFECTED` scan: terminal `REJECTED/INFECTED`. `ERROR` scan: retryable `STAGED/NOT_SCANNED`; no promoted object. Interrupted, truncated, oversized, spoofed signature and revoked-actor operations: fail closed, no falsely available file.
- Cleanup of uncommitted final/staged blobs on failure is best effort; **a durable orphan reconciler is R1.0C**, so crash windows and accumulated orphan blobs are explicitly not yet qualified.

## Tests

`FilesR10bQualificationTest`: synthetic PDF complete and checksum, immutable successful replay, scanner INFECTED and ERROR, signature mismatch, truncated and oversized streams, throwing source stream and retry, cross tenant and revoked FILES_UPLOAD deny before source stream read, default unavailable scanner, reopening final blob via reinitialized local adapter, and suspension after scan but before SQL commit. Test-only scanner must not be enabled by a runtime/test environment profile alone.

`FilesR10aQualificationTest` remains required. All relevant tests run on H2 dev and PostgreSQL integration CI. PostgreSQL PII populated workflow verifies compatibility. The GitHub Action exact-head SHA is the acceptance evidence.

## Risks deferred, not waived

1. **Binary confidentiality:** plaintext LOCAL staging and final content are synthetic-only. No real uploaded customer PII/contracts without authenticated streaming envelope encryption, tenant-key recovery and actual AV qualification (R1.1).
2. **Parser completeness:** simple signature check does not guarantee image safety, enforce safe image dimensions, or identify malicious PDF/polyglots. Strict content validation/CDR and safe public variant generation remain gated.
3. **Recovery:** fsync and power-loss durability, orphan sweep, cleanup after abrupt process death, concurrent finalize/replay and staged intent expiry require R1.0C.
4. **Object authorization:** only own-upload draft and FILES_UPLOAD admitted. CRM/CATALOG owner checks, attach/detach and authorized binary read remain gated R1.1.
5. **LOCAL deployment:** stable single-node UAT volume only, not multi-node/ephemeral production. RustFS and backup/restore qualification remain R1.2.
6. **Scanner ERROR:** no AVAILABLE even if physical bytes were written. The default scanner is unavailable; without a qualified real scanner, no real release admission.

**Production admission:** HOLD. Do not infer production suitability from test scanners or synthetic success.
