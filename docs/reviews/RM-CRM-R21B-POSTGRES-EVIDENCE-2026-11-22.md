# CRM-R2.1B — exact-SHA PostgreSQL concurrency qualification evidence

**Date:** 2026-11-22. **Repository/branch:** alimbetov/RM / base_dev.
**Qualification code SHA:** `8f5461768183a70e345c89f259edf3a0f2079dc7`.
**Workflow:** [CRM-R2.1B Isolated Exact-SHA PostgreSQL Qualification, run 45047687969](https://github.com/alimbetov/RM/actions/runs/45047687969).
**Job:** `137285762834` (`crm-r21b-postgres`), completed **SUCCESS**.
**Evidence artifact:** `crm-r21b-postgresql-evidence-45047687969` (artifact ID `14764286704`, 57189 bytes; includes SHA provenance, Surefire XML, per-pass manifest).
**Test system:** PostgreSQL 17, Java 21, Spring Boot; service-layer only.

## Qualified executions

| Pass | JUnit scope | Tests | Failures | Errors | Skipped | Result |
|---|---|---:|---:|---:|---:|---|
| Full | CrmR1aQualificationTest + CrmInteractionQualificationTest | 56 | 0 | 0 | 0 | PASS |
| Race-1 | Six crmR21bPostgres* test methods | 6 | 0 | 0 | 0 | PASS |
| Race-2 | Six crmR21bPostgres* test methods | 6 | 0 | 0 | 0 | PASS |
| Race-3 | Six crmR21bPostgres* test methods | 6 | 0 | 0 | 0 | PASS |

**Total:** 74 test executions (56 full suite + 18 additional concurrency executions); zero failures, errors or skips in these passes. JUnit XML presence, method names, counts, outcomes and exact source SHA checked by the workflow's fail-closed validation step. GitHub Actions completed SUCCESS.

## Business invariants

- `complete × cancel`: concurrent single-winner and explicitly ordered complete-first/cancel-first; rowVersion and event cardinality verified.
- `archive × record`: concurrent interleaving plus explicitly ordered archive-first/record-first; history remains intact and no new interaction created after archive commit.
- `assign × suspend`: both explicit commit orders, using the real owner-only `TenantAccessStatusService.suspendMember` instead of direct membership SQL. Pre-admitted assignment completes before suspension; committed suspension fences a waiting assignment. Denied commands do not change interaction revision or audit events.
- Repeat-key, negative RBAC, and archival invariants from the existing CRM suite are also covered in the full 56-case pass.

## CI isolation decision

`.github/workflows/crm-r21b-exact-sha.yml` is an independent push-on-workflow-or-test-change and workflow_dispatch action. It checks out an immutable full 40-character SHA, verifies provenance on `base_dev`, **does not specify branch-scoped cancel-in-progress**, runs the full CRM suite on real PostgreSQL and repeats six race tests three times. It stores an immutable ZIP artifact tied to the workflow run.

The broad `kms-r1-qualification.yml` still uses `cancel-in-progress: true` for its normal branch-oriented workflow; this isolated CRM qualification is not a replacement for the platform-wide RM-QA-01B gate.

## Admission

**CRM-R2.1B: QUALIFIED / GREEN on exact source SHA above, for the scoped service-and-concurrency matrix.**

**NOT implied:** all 99 CRM business simulations completed, CRM ↔ Buyer identity integration qualified, generic RM exact-SHA platform workflow GREEN, production authorization, external OIDC or UI acceptance. Production stays HOLD. CRM-R2.2 can begin as a separate service-only implementation slice without retroactively changing these facts.
