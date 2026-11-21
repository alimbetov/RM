# RM-KMS — технический аудит реализации, multi-pod, CI (2026-11-21)

**Scope:** branch `feat/rm-kms-r1-implementation`, draft PR #1, commit `ddcfac99e5afb9e33a9737559dca8e312da2b705`.  
**Evidence:** GitHub Actions run [#44870170151](https://github.com/alimbetov/RM/actions/runs/44870170151); actual GitHub Java, SQL, JUnit, workflow files.  
**Verdict:** **R1.6 smoke PASS / multi-pod safety NOT QUALIFIED / production HOLD**. No actual two-pod, scheduler, fencing or recovery scenario has been run in this review.

## 1. Verified results

- **H2 / dev:** 18/18 JUnit PASS; failures 0, errors 0, skipped 0; Maven BUILD SUCCESS; 5 Liquibase changesets.
- **PostgreSQL / test:** 18/18 JUnit PASS; failures 0, errors 0, skipped 0; Maven BUILD SUCCESS; 5 Liquibase changesets.
- `KmsDeploymentGuardTest` covers config flags and allowed strings; it **does not** run two independent JVMs or validate exclusive scheduling.
- `@JdbcTypeCode(SqlTypes.CHAR)` corrects `active_slot` / `ready_slot` mapping for H2 and PostgreSQL.
- AES-GCM round trip, AAD context swap, malformed nonce/tag, wrapping wrong Root KEK are present in tests; not equal to full cryptographic qualification matrix.

## 2. Findings

| ID | Severity | Verified observation | Remediation / pass criterion |
|---|---|---|---|
| RM-AUD-01 | **P0** (before revoke) | `TenantCryptoService.decrypt` on Caffeine HIT skips `keys.forDecrypt`; future REVOKED key can remain usable in another pod | Persistent admission/epoch + fail-closed state recheck or valid bounded lease, distributed tests including pod pause/partition |
| RM-AUD-02 | **P0** (before rotation) | Scheduler lease/fencing implementation absent from current KMS code, `rotate()` throws `UnsupportedOperationException`; config guard merely accepts `multi-pod` | Verify actual RM scheduler implementation (not evident in reviewed KMS files), durable per-tenant lease/fencing and writer epoch barrier under PostgreSQL, two JVM/process tests |
| RM-AUD-03 | **P0** | No persisted encrypted CRM entity/codec, no decrypt-after-restart/backup exercise, no archive restore | Binary envelope storage + reload E2E, backup/restore with historical KEK |
| RM-AUD-04 | **P0** | Legacy per-version table coexists with new 3-slot table, but old key migration absent | Safe legacy transfer including >3 generations; no data loss and restore tests |
| RM-AUD-05 | **P1** | `TenantKeyService.provision` `existsById` then INSERT; concurrent two-pod registration can fail one request on unique key; idempotent retry not implemented | Two concurrent provision requests; one persisted key, both callers eventually same tenant via transaction-safe retry |
| RM-AUD-06 | **P1** | SQL `ck_kms_key_status` and `ck_kms_active` allow **two DECRYPT_ONLY** slots, contradicts spec `<=1` | New Liquibase changeset with CHECK cardinality and invalid-row rejection on H2/PG |
| RM-AUD-07 | **P1** | `next_generation > 1` only; does not guarantee `next_generation > all occupied generations` | New changeset check monotonic next_generation and negative SQL test |
| RM-AUD-08 | **P1** | `KmsDeploymentGuard` does not detect multiple pods nor confirm shared PostgreSQL + same Root KEK, it only compares config string and feature flags | Add real multi-instance integration tests, readiness/config key ID fingerprint check without logging key |
| RM-AUD-09 | **P1** | `TenantCryptoService.encrypt` reads ACTIVE row for each call, even if DEK cached; potential DB hotspot under many pods | Benchmark with 2/5/10 pods before caching metadata; forbid stale-generation performance shortcut |
| RM-AUD-10 | **P1** | Root KEK ID hardcoded `root-v1` in `TenantKeyService` and decrypt MISS, no versioned KEK resolver | Versioned KEK provider; test multiple wrapping key IDs and unavailable historic KEK fail-closed |
| RM-AUD-11 | **P1** | CI runs same 18 tests in two profiles, no separate multi-pod/scheduler, concurrent writes, property test, KAT or measured coverage | Add PostgreSQL multi-process qualification and JaCoCo; preserve fast H2 job |
| RM-AUD-12 | **P2** | `TenantCryptoService` comment says Single-JVM though deployment guard allows multi-pod immutable-key phase | Correct documentation/comments in next functional commit |
| RM-AUD-13 | **P1** | `EncryptedValue` is only a Java record, no explicit bounded DB codec or persistence contract | Define immutable versioned binary codec plus persisted round-trip and malformed format tests |
| RM-AUD-14 | **P1** | In-memory Caffeine cache is never cluster-invalidated. `invalidateTenant` operates only on current JVM | No rotation/revoke until distributed publication + DB fence, verify crash-after-commit and packet partition |

## 3. Architectural gates for scheduler and KMS

**Distinct invariants:**
- **Job coordination**: single winner for a tenant operation, durable lease owner and fencing token; a stale worker cannot commit after token change.
- **Business write fencing**: each encrypted-data write transaction must check authoritative generation/epoch before commit. A job scheduler lock alone does not protect API writers.
- **Cache consistency**: READ of any now-revoked generation must not rely solely on local expired/possibly stale Caffeine; fail closed on uncertain state.
- **Final retirement gate**: no old references in all encrypted datasets + writers drained + archive restore verified.

**Mandatory future PostgreSQL qualification cases:** 2 and 5 simultaneous pods; provision same tenant 100 parallel calls; duplicate job scheduling; paused worker after lease expiry; network partition / DB reconnect; crash after job commit before cache invalidation; concurrent CRUD at rotation boundary; stale cache/decrypt after revoke; old reference in outbox; backup from historical generation.

## 4. Sequenced remediation

1. **R1.6C**: fix two-slot DECRYPT_ONLY cardinality SQL check, monotonic next generation; concurrent provision PostgreSQL test. Preserve applied changesets 0001..0005.
2. **R1.6D**: binary EncryptedValue codec, persisted encrypt→commit→reload→decrypt across application restart and 2 pods.
3. **R1.7**: real RM scheduler integration assessment; persistent lease/fencing with PostgreSQL, no split brain; prove worker cannot update after expired lease.
4. **R1.8**: writer-generation transactional barrier and cache revocation/epoch; only then implement promotion/rotation under feature gate.
5. **R2.0**: legacy key migration, Archive KEK rewrap/restore and all full-scan gates.

## 5. Decision

**Accept** H2/PostgreSQL schema + crypto baseline as a qualified first slice; **do not merge as production KMS**, do not enable rotation/migration and do not infer multi-pod safety from passing config tests. Run separate multi-instance integration tests and independent recovery qualification before changing gates.

**Quality assessment (subjective):** architecture 8/10 for MVP design; implemented KMS completeness 5.5/10; test maturity 6.5/10. These are judgments, not JaCoCo metrics or certification.
