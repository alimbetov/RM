# RM FILES-RUSTFS-R1.3 — Operational Reliability & ClamAV Qualification

**Branch:** `base_dev`. **Environment:** real RustFS S3, real PostgreSQL 17, real ClamAV clamd, synthetic-only fixtures.
**Architectural boundary:** one RM FILES module, no second service, no HTTP controller or `SecurityConfig` changes, no merge into `main`.
**Decision:** no application-layer file encryption. Confidential production attachments remain HOLD.

## Immutable infrastructure versions used by qualification

- RustFS registry reference: `rustfs/rustfs@sha256:b60e90042aa6b8dbecd58e224b3e36114dbf8ad754465264c2476ad58a86e45c`; observed Linux image ID `sha256:ee717072290bec9b11abfc0365f8310b00df1cd9b3639943edae47bdef05044a`.
- ClamAV registry reference: `clamav/clamav@sha256:12177441d1b40cef3c47b44c4090d59d4babc4c67bedd5af0fa315e603aa6dd8`; observed image ID `sha256:12177441d1b40cef3c47b44c4090d59d4babc4c67bedd5af0fa315e603aa6dd8`.
- CI `.github/workflows/rustfs-real-infra-e2e.yml` validates expected image IDs after immutable-digest pull before tests. A deployment image and scanner signature database must be separately maintained and reviewed.

## Real ClamAV admission tests

- A real `clamd` process is started in a container with signature database; `zPING` receives `PONG` before qualification starts.
- `ClamAvFileScanner` uses real `zINSTREAM` over TCP, reading a private RustFS staged object. Clean synthetic PDF -> `CLEAN`.
- An **exact, standalone 68-byte EICAR antivirus test object** is written to private RustFS staging via S3 PUT, then streamed to real clamd -> `INFECTED`. EICAR is a harmless, standardized test signature, not real malware. A PDF with EICAR text embedded is **not** a reliable AV test; that earlier fixture was rejected during qualification.
- Unavailable scanner endpoint -> `ERROR`, never `CLEAN`.
- The existing FILES service tests separately enforce `CLEAN` -> short transaction `AVAILABLE`, `INFECTED` -> `REJECTED/INFECTED`, `ERROR` -> retryable `STAGED`; default scanner remains fail-closed. **The exact EICAR file is not admitted by the PDF/JPEG/PNG/WebP signature allowlist**, so this live EICAR test qualifies the scanner adapter independently rather than claiming an end-to-end infected PDF lifecycle.

## Operational fault and restore matrix

| Scenario | Acceptance evidence |
|---|---|
| Real RustFS + PostgreSQL path | Real upload, S3 stage/final, SHA-256, `provider=RUSTFS` and `AVAILABLE`, cross-tenant deny |
| Actual daemon restart | Same volume after `docker restart`; new Maven process verifies original object SHA |
| Real network outage | Stop RustFS container; `matches` throws `IOException`, never returns false corruption |
| Quiesced PostgreSQL snapshot | `pg_dump -Fc` real database; restore into separate `rm_restore` database |
| Offline RustFS backup | Stop daemon, archive volume, restore archive into a new Docker volume |
| Restored data and metadata coherence | New RustFS daemon + new PostgreSQL database; `RustFsRestoredDatabaseE2ETest` finds real `ACTIVE/AVAILABLE` pointer and verifies bytes against stored SHA |
| Crash after S3 COPY before DB commit | Fenced old token, orphan cleanup, retry produces one authoritative file |
| Reverse LOCAL switch | New LOCAL writes while old RustFS keys remain readable; no silent migration |

The whole suite remains opt-in and deterministic through `RM_RUSTFS_LIVE_E2E`/`RM_CLAMAV_LIVE_E2E`, with the standard H2/PostgreSQL pipeline unchanged. No mocks replace the S3 or AV daemon during live E2E. The existing state-transition tests remain synthetic by design.

## Residual production HOLD

1. **AV operations:** pinned image is not a proof of signature freshness. Qualify signature-update freshness alarms, maintenance process, scanner failure modes under load and maximum scan duration/size policy before production.
2. **Storage reliability:** this is a **single-node, quiesced backup** and restore; not hot backup, cluster/failover, node/disk-loss test, disaster-recovery RPO/RTO, multipart/large-object (>25 MiB), object-versioning or sustained performance qualification.
3. **Confidential files:** storage bytes and bounded temporary S3 upload spool are plaintext per the approved policy. Enforce bucket privacy, infrastructure access control, TLS for non-local endpoints, disk protections, key/credential rotation and privacy/legal admission. RBAC alone is not a production confidentiality control for host compromise.
4. **Download authorization:** authenticated CRM owner checks exist; the public controller/transport gate is not yet implemented. No presigned/public links or controllers introduced here.
5. **Flag change:** the LOCAL reversal is validated through a re-instantiated selector and real S3 backend, not a full externally deployed second Spring Boot process restart with a changed flag.
6. **Independent review:** no independent security approval and no confidential production-data admission. Keep `main` and production HOLD.

## Acceptance

R1.3 may be marked **GREEN for real single-node synthetic reliability and ClamAV protocol qualification** only if *both* workflow suites pass on the same exact `base_dev` SHA:
- `RustFS Real Infrastructure E2E`: actual RustFS/ClamAV, restart, outage, offline volume and database restore, restored SHA.
- `RM Domain Security and PostgreSQL Qualification`: H2 smoke, PostgreSQL integration and populated PII regression.

Attach the final exact-head workflow run links to the PR review. Do not upgrade this scoped acceptance to production-ready.
