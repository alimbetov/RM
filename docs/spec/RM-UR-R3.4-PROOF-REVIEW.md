# UR-R3.4 — Evidence review of five previously unlinked R3-v1 capabilities

**Branch:** `base_dev`. **Scope:** five proof gaps, without changing 75 requirement IDs, the 16 domain rows, delivery flags or the 19 role/concurrency JUnit cases.

**Admission:** This file documents a source-and-assertion review. The canonical `proof` is **declared** in the catalogue; `VERIFIED_INTERNAL` is only computed by RM-QA-01B from the exact commit's successful, unskipped Surefire XML and matching artifact provenance. A previous successful SHA does not qualify a later revision. **Production HOLD**.

| Requirement | Unique canonical test | Required artifact | Substantive assertions | Qualification boundary |
|---|---|---|---|---|
| `INV-02` | `InventoryReservationQualificationTest.reservationReleaseAndIdempotentReplay` | `kms-postgres-surefire` | Reserve 3, on-hand unchanged, reserved +3; same-key replay preserves reservation ID; release and repeated release; reserved back to 0; rejected fulfill after release; exactly two immutable reservation events | Core reserve/release; concurrency and multi-tenant are separately exercised in `twoConcurrentOrdersCannotOversellSameSku`, `tenantIsolationAndReservationEventImmutability` and UR-R3.2/R3.3 |
| `FIL-05` | `RustFsRealClamAvE2ETest.realClamdRespondsCleanInfectedAndFailClosedForUnavailableEndpoint` | `rustfs-real-infra-e2e-surefire` | Actual S3-compatible RustFS object staging and retrieval through ClamAV adapter, clean verdict; EICAR infected verdict; unreachable clamd => ERROR, no false clean; real services are started in the required RustFS workflow | This proves integrated real RustFS + ClamAV scanning. RustFS restart, restored volume and PG metadata remain independently evidenced by other required stages; not a production storage SLA |
| `ORG-04` | `OrganizationLocationQueryQualificationTest.paginationAndFiltersUseTenantBoundary` | `kms-postgres-surefire` | Two tenants with same-name warehouses; type and name filters; paged list and counts; cross-tenant ID absent; foreign get rejected; max page validation | Owner/source-of-truth location queries, not all authorization-role combinations (those have UR-R3.2) |
| `KMS-01` | `KmsPersistedRoundTripTest.persistedEnvelopeRestoresUsingFreshCryptoInstance` | `kms-postgres-surefire` | Tenant key provision, encrypt, persisted byte array, reload, fresh crypto service reads wrapped tenant DEK from DB, decrypts exact original, rejects wrong crypto context | Synthetic PostgreSQL persisted envelope; not external HSM/KMS resilience |
| `KMS-03` | `KmsDeploymentGuardTest.urR34DeploymentConfigurationAcceptsOnlySafeImmutableKeyModes` | `kms-postgres-surefire` | Both supported modes accept immutable-key operation; every rotation/migration-enabled combination fails closed; unknown, malformed and mismatched modes rejected | Java deployment guard config; test is *run in* PG suite but has no DB interaction and proves neither live distributed fencing nor external HSM. Rotation/migration remain disabled |

## Rules for acceptance

1. The original 75 requirements across 16 assessment domains remain the complete unchanged denominator of R3-v1. Implementation is still 46/75. A proof binding never changes the number of implemented features.
2. Each of these five proof bindings is unique among canonical R3 requirements; `rm_r3_functional_coverage.assess` rejects duplicate artifact/suite/method proof keys.
3. The crosswalk validator now requires these **exact five reviewed proof keys**; names and test contents must be reviewed before intentional future changes. `qualifed=false` in the crosswalk is static; executed evidence is derived from GitHub Actions, not written into source.
4. RM-QA-01B `REQUIRED_CASES` includes all four PostgreSQL keys and the separate real-infrastructure RustFS key. A skipped or absent method blocks the exact-SHA gate.
5. `41/75` is the previous baseline of *declared* canonical proofs. The new candidate is `46/75` declared bindings. Only a completed successful exact-SHA gate may report `46/75` **verified internal**.
6. Real user HTTP/OIDC, production KMS/HSM, distributed key rotation, cross-page tenant-wide inventory snapshot and production admission are not established here. **PRODUCTION HOLD**.

## Future audit triggers

A change to `InventoryReservationService`, `RustFsFileStorage`, `ClamAvFileScanner`, `OrganizationLocationQueryService`, `TenantCryptoService`, `KeyWrappingService` or `KmsDeploymentGuard` requires rerunning the same mandatory proof at the updated SHA. The exact-SHA gate does that for every qualifying `base_dev` push.
