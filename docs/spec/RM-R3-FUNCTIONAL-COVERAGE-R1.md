# RM-R3-FUNCTIONAL-COVERAGE-R1 — evidence-based functional matrix

**Branch:** `base_dev`. **Status:** curated first-wave R3 catalogue and CI qualification framework; **production admission HOLD**.

## Scope and truth boundary

This register is a **versioned, curated first-wave subset of RM-STRATEGY-R3**:
75 explicitly enumerated capabilities across the 15 `DomainModule` contexts, with CRM Interaction evaluated separately, yielding **16 assessment rows**. The denominator is **75 catalogued requirements, not all imaginable CRM/ERP functions**, so its percentage must never be marketed as total product completeness. Capabilities omitted from the register must be added by reviewed catalogue change, not silently counted as covered.

The authoritative requirements live in `docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json`:
- `id, title, delivery` identify a stable user-relevant capability.
- `delivery=IMPLEMENTED` requires an existing `src/main/java` source path and a traceable feature. The evaluator validates that the source file exists, but its presence does **not** establish correct behavior.
- `delivery=PLANNED` claims no implementation and counts as not implemented even if an enum or table for the future domain already exists.
- `proof` identifies exactly one tested `artifact + Surefire suite + test method`. There can be no duplicate proof shared across requirements; a test file alone is insufficient.
- Missing, skipped or failed real-PostgreSQL test results never count as executed proof.

## Exact-SHA evidence contract

`.github/workflows/rm-exact-sha-admission.yml` checks out `github.sha` **before** generating the preliminary manifest, then waits for all five mandatory same-SHA workflows and their expected artifacts. The verifier examines Surefire XML (including mandatory named PostgreSQL races), computes SHA-256 for every evidence ZIP and a deterministic Liquibase path/content fingerprint, and records skip reasons.

`.github/scripts/verify_rm_qa_01b.py` additionally calculates the R3 coverage matrix using **only passing PostgreSQL Surefire test cases from the exact SHA**. The outputs are:

- `rm-exact-sha-manifest.json` — mandatory workflows, checked artifacts, suites, tests, migration fingerprint and `qa01bGate`.
- `rm-r3-functional-coverage.json` — per-domain and per-requirement statuses, SHA, counts and percentages.
- `rm-r3-functional-coverage.md` — human-readable table of all 16 domains and 75 capabilities.

All three are published together as a single GitHub Actions artifact `rm-exact-sha-qualification-manifest`. Its contents are generated for **the specific workflow SHA**; previous SHA artifacts cannot be inherited.

## Metrics

For each domain and overall:
- `implementedPct = implemented / total * 100`. Based on an explicit code-reviewed delivery declaration and verified source path; this is not statement/branch coverage.
- `testEvidencePct = requirements whose exact mapped PostgreSQL test actually ran successfully / total * 100`. The evidence is **internal service qualification**, not a browser or real issuer.
- `verifiedPct = testExecuted / total * 100` **only when the entire RM-QA-01B evidence gate is PASS**; otherwise `verifiedInternal=0` and executed rows are marked `EXECUTED_NOT_ADMITTED`.
- `notImplemented = total - implemented`. Missing capabilities remain visible in the denominator.

A successful `RM-QA-01B` is a **necessary evidence gate, not production admission**. Real OIDC, user workflow, independent security review, recovery and performance still require their own approvals. All manifests carry `productionAdmission=HOLD`.

## Updating the catalogue

1. Start from an approved R3 user capability, not a Java method count. Identify the canonical domain data owner and the correct delivery state.
2. For implemented work, link an existing source file and **one precise PostgreSQL test method** that actually checks the requirement. Add negative/tenant/retry/race tests as appropriate; one mapped test is a minimum, not complete proof.
3. For planned work, leave both source and proof null. Never mark schemas and enums alone as finished services.
4. Review additions/deletions to the denominator explicitly and keep requirement IDs stable; compare historical percentage only at matching catalogue revisions.
5. Require the same-SHA complete CI after changes. A coverage change that drops a domain, reuses a test, invents a source or maps H2-only proof is an error.

## Current exclusions

JaCoCo line/branch coverage is not supplied by this matrix. Browser E2E, a production issuer/JWKS and customer-backed migration validation are not qualified by this test pipeline. The R3 registry is an **initial measurable product baseline**; a complete domain acceptance matrix remains a separate expansion task.
