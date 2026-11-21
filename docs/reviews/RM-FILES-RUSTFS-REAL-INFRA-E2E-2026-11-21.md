# RM FILES — RustFS Real Infrastructure E2E qualification (2026-11-21)

**Branch:** `base_dev`. **Scope:** real RustFS + real PostgreSQL, synthetic-only service-layer E2E. **Production:** HOLD.

## Evidence

First successful live-infrastructure run:
- GitHub Actions: https://github.com/alimbetov/RM/actions/runs/44933205749
- Code SHA: `ef7f01a15c6749558bd1eca3c761cb97d4c36bfd`
- Actual RustFS Docker image observed in CI: `sha256:ee717072290bec9b11abfc0365f8310b00df1cd9b3639943edae47bdef05044a` (`rustfs/rustfs:latest` at run time).
- RustFS official S3 API healthcheck: successful before and after daemon restart.
- `RustFsLiveInfrastructureE2ETest`: 3 tests, 0 failures, 0 errors, 0 skips, `BUILD SUCCESS`.
- `RustFsRestartProbeTest`: seed invocation 1 executed / 1 intentionally skipped, verification invocation 1 executed / 1 intentionally skipped, both `BUILD SUCCESS`. These are two Maven JVM executions separated by a real Docker container restart.
- GitHub workflow: `.github/workflows/rustfs-real-infra-e2e.yml` (separate from regular H2 + PG qualification).

## What was actually tested

1. Real RustFS S3 stage PUT + HEAD, final COPY + HEAD, content hash comparison, new `S3Client` for read-back, size and SHA-256 verification. PostgreSQL metadata stores `provider=RUSTFS` and `state=AVAILABLE`.
2. Simulated RM configuration switch back to LOCAL using a newly constructed `SwitchableFileStorage` in the live test (without changing stored RUSTFS key). A new upload uses LOCAL; the earlier RustFS object is still accessible. This is **adapter restart semantics**, not an independently launched second Spring Boot process with the flag changed.
3. Simulated crash window after real S3 COPY but before PostgreSQL finalization: SQL has no ACTIVE pointer; token expires; `FileRecoveryService.reconcileOne` removes the real orphan; expired worker is denied; a retried upload completes to exactly one authoritative RUSTFS pointer.
4. Real missing-object handling: delete the S3 object, run reconciliation, expect quarantine. Cross-tenant direct storage read attempt is denied.
5. Persistent-volume restart: seed creates a real final RustFS object; container is restarted via `docker restart` without deleting the named volume; a **second Maven JVM** instantiates a new S3 client, reads prior object, verifies SHA-256 and deletes the test object.

## Architecture maintained

- One RM FILES module; no new file microservice.
- `RM_FILES_RUSTFS_ENABLED=false` for normal operation; `true` selects new RustFS uploads only. Existing LOCAL and RustFS blobs are addressed by persisted backend key.
- No application-level binary encryption by recorded product decision; existing CRM field encryption remains unchanged.
- No auto-migration between LOCAL and RustFS.
- No public file endpoints, `SecurityConfig`, or `main` merge.

## Explicit limitations (not qualified)

- The Docker image uses mutable `rustfs/rustfs:latest` in the test workflow; a specific deployment release must **pin a digest**, run the same E2E against that digest, and record it.
- No independently qualified real `clamd` malware/timeout/EICAR acceptance; test-only CLEAN scanner remains confined to synthetic fixtures.
- No process-kill during actual in-flight TCP request, no node/disk loss, no volume restore, no S3 multipart/large-object load, no p95/p99 measurements, no proven multi-node behavior.
- The simulated LOCAL switch is not a separate Spring Boot process restart with `RM_FILES_RUSTFS_ENABLED=false`; its implementation behavior is covered but this stricter operational exercise remains available for a future gate.
- Infrastructure encryption is not asserted. Chosen no-file-encryption policy means plaintext disk/spool/S3 objects. Confidential real customer documents remain HOLD until security/privacy/legal admission and actual storage controls are approved.
- Independent security review and production readiness are separate decisions.

## Qualification decision

**Real RustFS single-node S3 integration and restart E2E: GREEN for synthetic service-layer scope.**
**Production, confidential attachments and independent security sign-off: HOLD.**
