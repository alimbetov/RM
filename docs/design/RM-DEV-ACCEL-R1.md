# RM-DEV-ACCEL-R1 — Accelerated Development & Batch Qualification Standard

Accepted 2026-11-28. Work only on base_dev for this RM development stream. No new branches. This standard changes delivery grouping, not the existing CI or release admission policy.

- Combine related changes into bounded functional packages; prepare and statically review the complete diff before one push. Avoid intermediate remote commits for small internal steps. Reuse existing source, schema, authorization and math contracts.
- Before publication verify test names against strict JUnit registry, workflow path dependencies, migration identity/checksum compatibility, SQL delimiters, existing source-inventory hashes, tenant scopes, transaction boundaries and regressions. Execute local targeted tests where available; otherwise explicitly report NOT_EXECUTED, never fabricate results.
- Existing development admission and Exact-SHA contracts remain intact; do not skip mandatory workflows, reuse unqualified evidence or treat a targeted PASS as release qualification. Full Exact-SHA at functional package boundary. Fix actual defects in place, no gate weakening. Prepare next package while prior CI runs, but respect explicit prior-GREEN dependencies before updating base_dev.
- One consolidated technical audit per package. Do not expand scope without necessity. Security, tenant isolation, mathematical correctness, transaction integrity, compatibility and operational performance remain non-negotiable.
- RM-ARS R1.2B P1=B.4+B.5 (monthly calendar/zero); P2=B.6+B.7+B.8 (manifest/amount/admission); P3=B.9 (full qualification). Unresolved factual authority is an admission blocker, not a reason to fabricate READY. Measure elapsed time, push/CI cycles and runner usage across packages; 1.5–2x acceleration is a hypothesis, not a measured result.
- Production Admission HOLD until independent required evidence and current Exact-SHA qualify the release.
