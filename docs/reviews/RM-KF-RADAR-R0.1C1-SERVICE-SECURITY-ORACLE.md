# RM-KF-RADAR-R0.1C1 — deterministic service/security/oracle qualification
Qualified B baseline 4942737dfed3dddbbc79c91110c2ab7c4fce4d34: R0.1A 24/24, R0.1B 12/12, TASK PG 53/53 and H2 35+18 expected skips, [A](https://github.com/alimbetov/RM/actions/runs/45433746792), [B](https://github.com/alimbetov/RM/actions/runs/45433746867), [TASK](https://github.com/alimbetov/RM/actions/runs/45433746820).
Integration parent 0805c34ce92cd2929d1df4fd2e5b7c56e0e2b0bc; intervening CORE-RET-04 changes touch sales tests and their CI only. No inherited PASS on new SHA. Independent development approved; W6/global release gate strict; production HOLD.

Eight named Spring service tests per engine; execution pending. No production Java or schema changes. Real signed JWT/JWKS and service proxy; test-only fixed counting Clock. SQL legacy/inactive-status fixtures are not proof of trusted writer fencing.

| Case | Evidence | Trace |
|---|---|---|
| c01 | exact cutoff/equality, one sample, 1us, 24h/72h | RAD-07/08/09/11 |
| c02 | nulls, overlap, unrelated reader, composite tenant | RAD-06/12/13/14 |
| c03 | null principal/tenant, cursor, manage-only and owner denied | RAD-15/16/17/30 |
| c04 | committed inactive account/membership/workspace, personal workspace | RAD-18 partial |
| c05 | expired/untrusted bearer rejected; valid accepted | RAD-15 partial, not post-mint expiry |
| c06 | reassignment removes old assignee, preserves creator | RAD-22 |
| c07 | rolled-back reschedule leaves exact rows/history | RAD-25 partial |
| c08 | independent SQL selection and Java duration oracle; keyset and before/after task/event snapshots | RAD-28/35 partial |

C2: actual PostgreSQL writer fences and command races, both orders, independent connections and blocking evidence, DB errors/retry, status rollback and live pagination, statement/no-DML instrumentation. C3: populated 1k/10k/100k, plans/buffers/p95 and command degradation. No full RAD coverage claim. Existing A/B/TASK CI unchanged; C1 exact SHA, eight names/no skips each engine and DB identity; A/B/C1 + TASK required on candidate. Full final gate D after E/F.
