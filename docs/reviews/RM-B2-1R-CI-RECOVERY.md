# B2.1R CI regression recovery
Failed SHA 82452bbb0f869d31af6c5f3b073079453e482483. Reviewed GitHub jobs 139163994815, 139163996949, 139163995607.
- Intelligence A1: contract PASS; source inventory rejected the master changelog blob drift after 0177/0178. Exact tree comparison found only that inventory mismatch. Updated the pinned blob and preserved prior/design hashes; admission remains HOLD.
- Domain Security PostgreSQL: Maven Central HTTP 429 resolving maven-dependency-plugin 3.9.0 before tests. Added bounded rate-limit-only retry (30s/60s) to classpath resolution, fail closed on other errors/exhaustion, require nonempty classpath. Full clean verify and DB role qualification unchanged.
- Exact-SHA admission: correctly failed on mandatory Domain Security workflow failure. No changes to aggregator, required checks or test selection.
New SHA requires independent CI evidence; no claim of PostgreSQL qualification or B2.1R completion.
