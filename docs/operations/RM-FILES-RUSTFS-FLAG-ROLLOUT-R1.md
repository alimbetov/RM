# RM FILES — RustFS S3 storage behind deployment flag

**Branch:** `base_dev`. **Status:** service-only UAT implementation; production and real confidential documents remain HOLD.
**Decision:** no application-layer file encryption. CRM database PII field encryption is unchanged. RustFS is an object storage backend, **not** a second microservice.

## Deployment flag

| Setting | Default | Meaning |
|---|---|---|
| `RM_FILES_RUSTFS_ENABLED` | `false` | `false`: new uploads use LOCAL; `true`: new uploads use RustFS |
| `RM_FILES_RUSTFS_ENDPOINT` | unset | S3 endpoint origin, e.g. `http://localhost:9000` for local UAT |
| `RM_FILES_RUSTFS_REGION` | `us-east-1` | SigV4 region |
| `RM_FILES_RUSTFS_BUCKET` | `rm-files` | Pre-created private bucket |
| `RM_FILES_RUSTFS_ACCESS_KEY` | unset | Restricted S3 service identity |
| `RM_FILES_RUSTFS_SECRET_KEY` | unset | Secret provided through deployment secret manager |

The endpoint is resolved via Spring Boot's relaxed environment binding of `RM_FILES_RUSTFS_ENDPOINT` to `rm.files.rustfs.endpoint`. The S3 client uses **path-style addressing** and AWS Java SDK v2 with SigV4. Bucket provisioning is deliberately out of scope. Before enabling, create a private bucket and a limited service account with ListBucket on that bucket and GetObject/PutObject/DeleteObject on its keys. Do not use root credentials in production.

Minimal local-UAT example (replace credentials with actual operator-supplied values, do not commit them):
```bash
export RM_FILES_RUSTFS_ENDPOINT=http://127.0.0.1:9000
export RM_FILES_RUSTFS_REGION=us-east-1
export RM_FILES_RUSTFS_BUCKET=rm-files
export RM_FILES_RUSTFS_ACCESS_KEY="$RUSTFS_APP_ACCESS_KEY"
export RM_FILES_RUSTFS_SECRET_KEY="$RUSTFS_APP_SECRET_KEY"
export RM_FILES_RUSTFS_ENABLED=true
```

Flag is **read on application startup**, not hot-reload. Changing it requires a managed restart. The application fails to start when RustFS is enabled but no adapter endpoint is configured, rather than silently storing to LOCAL.

## Preserving existing blobs

- Old LOCAL key: `<tenantUUID>/<fileUUID>/final-<uuid>`, provider `LOCAL`.
- RustFS key in metadata: `rustfs/<tenantUUID>/<fileUUID>/final-<uuid>`, provider `RUSTFS`. The first `rustfs/` is an internal router discriminator, stripped before the S3 API call.
- The flag selects storage **for new STAGED objects only**. Every later scan, promote, integrity check, read, delete and recovery uses the actual stored key, not the current flag.
- Turning the flag off does **not** migrate or delete RustFS objects. Keep RustFS endpoint and access credentials configured after turning off the flag when existing RustFS files must remain accessible. Without access, reads fail closed. Do not interpret network outages as proof of corruption or delete healthy file metadata.
- There is no automatic backfill or LOCAL→RustFS copy. Storage migration is an independently qualified project.

## Data flow and safety

`VerifiedActor/FILES_UPLOAD` → claim → bounded staging of up to 25 MiB → signature and SHA-256 → S3 private stage PUT → ClamAV INSTREAM scan → S3 private final COPY → HEAD + byte-content hash verification → short PostgreSQL finalize with `provider=RUSTFS` → cleanup staged object.

AWS sync S3 PUT uses a small bounded private local spool file for repeatable request body and reliable length validation; it is removed after upload. It is **not an unlimited RAM buffer**, but the spool contains plaintext because RM has chosen no application-level file encryption. Restrict temp directory permissions, disk access and backups accordingly. Physical disk/S3-at-rest encryption is an infrastructure decision; do not claim it is enabled without evidence.

- File names, storage keys, object URLs and S3 credentials must not be disclosed in business API responses or logs.
- S3 bucket is private. No public ACLs, direct browser GET, unreviewed presigned URLs, or controllers in this phase.
- ClamAV is still fail-closed by default. Mock/always-CLEAN scanners are **test-only**. Until a real clamd integration and policy admission are qualified, no production customer documents.
- S3 transient failures must return IO failure, **not** rewrite an AVAILABLE DB row as REJECTED. Missing keys and corrupted content can be quarantined only after authoritative integrity evidence.

## Admission tests

1. `RM_FILES_RUSTFS_ENABLED=false`: existing H2/PostgreSQL LOCAL suite GREEN; no S3 connection needed.
2. Switch true with a controlled RustFS service, private bucket and limited credentials; perform synthetic `begin → stage → AV CLEAN → promote → DB AVAILABLE`, verify provider RUSTFS and exact SHA.
3. With the flag true, read an existing LOCAL file and a newly uploaded RustFS file through the same authorized service; confirm no cross-tenant access.
4. Switch false with RustFS credentials retained; existing RustFS objects remain readable; new objects go to LOCAL. No implicit migration.
5. Kill a worker after RustFS COPY and before DB commit; stale claim must be fenced, orphan object eventually removed, retry uses same `fileId`.
6. Simulate RustFS 503, auth denial and timeout; API fails closed and does not quarantine legitimate content on temporary network outage.
7. Confirm bad MIME/length, scanner ERROR/INFECTED and unauthorized tenant never make a file AVAILABLE.
8. Validate actual RustFS release compatibility and real ClamAV, then backup/restore and operational load before any production admission.

CI SDK mock tests prove request construction, routing and failure semantics, **not** compatibility with a live RustFS daemon; that requires the explicit infrastructure E2E above. No controllers or merge into `main`.
