# UR-R3.5 — unified exact-SHA maturity report

**Branch:** `base_dev`. **Status:** implemented reporting contract; full admission is established only by a successful current-SHA GitHub Actions execution. **Production HOLD.**

## Input contracts

This stage **does not inspect arbitrary source files or guess maturity from test names**. It consumes evidence already produced by the mandatory RM-QA-01B verifier:

- `rm-exact-sha-manifest.json`: exact commit, five workflows, verified artifact digests, Liquibase SHA-256 fingerprint, Surefire totals and QA gate.
- `rm-r3-functional-coverage.json`: 75 first-wave capabilities across 16 canonical assessment domains; delivery, canonical proof and internal qualification.
- `rm-user-role-scenario-results.json`: 19 individually executed mandatory JUnit service cases; eight UR-R3.3 concurrency, rollback and replay cases included.
- `rm-user-role-crosswalk-validation.json` and `docs/spec/RM-R3-USER-ROLE-CROSSWALK-v1.json`: cross-domain proof mapping, explicit source checksum and requirement IDs.

All five sources must match their declared commit/catalogue/crosswalk hashes; the global evidence gate must be `PASS`. Any missing file, wrong SHA, skipped mandatory test, incomplete domain or wrong manifest fingerprint causes a **failed report-generation step**, never a fabricated PASS.

## Artifacts

The GitHub exact-SHA workflow now publishes two additional outputs inside its existing `rm-exact-sha-qualification-manifest` artifact:

- `rm-r3-maturity-report.json` — machine-readable `RM-UR-R3.5-MATURITY/v1`, source hashes, global totals, 16 domain rows, per-domain missing requirement IDs, links to specific role and race case IDs, release boundary.
- `rm-r3-maturity-report.md` — compact 16-row table and explicit qualification limitations, also printed to GitHub Actions Step Summary.

Generation is implemented by `.github/scripts/rm_r35_maturity_report.py`. Synthetic fail-closed tests are in `.github/scripts/test_rm_r35_maturity_report.py`.

## Maturity dimensions — do not combine into a weighted score

| Axis | Denominator | What a PASS means |
|---|---|---|
| Functional implementation | 75 R3-v1 requirements | Code-reviewed delivery declaration, *not* E2E |
| Functional evidence | 75 R3-v1 requirements | One unique canonical per-requirement test passed on exact-SHA mandatory evidence |
| Service role scenarios | 19 mandatory UR-R3.1–R3.3 JUnit cases | Real PostgreSQL executions with checked side effects for mapped cases |
| Concurrency/rollback/replay | Eight UR-R3.3 cases | Both relevant transaction orders and selected rollback/retry invariants |
| Infrastructure/CI | Five mandatory workflows and evidence artifacts | Same-SHA and immutable evidence digests, including actual RustFS/ClamAV |
| Release | Independent review, real login/E2E, operating evidence | **NOT_ADMITTED / PRODUCTION HOLD**, regardless of internal PASS |

**Domain-specific `roleCaseCoverage.status=NOT_ASSESSED` or `concurrencyCoverage.status=NOT_ASSESSED` is never presented as a pass or a zero-failure qualification.** Multiple domain references to one JUnit case do not increase the global total of unique cases.

The 75 requirements / 16 assessment domains remain unchanged. This report is **not a measure of all possible CRM/ERP features**, Java line coverage or complete role × tenant × operation Cartesian coverage.

## Acceptance criteria

1. All five mandatory exact-SHA workflows are successful and their artifact digests are present.
2. The crosswalk has exactly 75 unique requirements and each matches its declared domain.
3. The catalogue validates 46 implemented, 46 individually verified and 29 planned requirements.
4. All 19 registered service cases are executed and verified, including eight UR-R3.3 concurrency/retry cases.
5. Git SHA, catalogue SHA, role catalogue SHA, crosswalk SHA and Liquibase fingerprint are consistent.
6. JSON and Markdown contain all 16 domain rows; each distinguishes missing implementation from absent role tests.
7. QA failure or mismatched evidence causes fail-closed CI; a report generated on a prior SHA cannot be reused.
8. No change to production admission, new REST endpoints, external OIDC or domain business state.
