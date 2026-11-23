# RM-TASK-01B/R3.2A — Exact-SHA, Snapshot Safety & Project Consistency Audit

**Date:** 2026-11-23. **Branch:** `base_dev`. **Production:** HOLD. **Main:** unchanged. Controllers/UI, background worker, external notification channel: STOP.

## Inspected contracts

- RM Business TASKS is one canonical `crm.task_item`; CRM interaction remains `crm.customer_interaction`. Snapshot tables `0149–0152` capture only IDs/state/version and link by composite tenant-qualified FKs; they are *evidence*, never duplicate business task aggregates.
- Manager `capture/page` requires both `TASK_MANAGE` and `CRM_READ`; snapshot owner is verified on page access. Tenant-wide state and actor permissions are rechecked for subsequent reconciliation. CRM cancellation does not automatically cancel task.
- Sealed header and items are immutable in PostgreSQL; a late item INSERT after seal is rejected. SHA-256 digest persists, but an independent post-commit digest recomputation/backup-restore integrity audit is not yet qualified.
- R3.1 keyset queue traversal is not a stable multi-request snapshot, whereas R3.2 captured **membership** is fixed. Captured status/version is historical and never a valid substitute for live optimistic concurrency checks.

## Findings / fixes

1. **P1 / SNAPSHOT-READ-BOUND (fixed):** `capture` previously fetched all pending rows then tested `items.size()>10000`. Unlimited fetch can exhaust memory for a large tenant. Change to `ORDER BY t.id LIMIT 10001`; the 10,001st row causes fail-closed capacity rejection **before INSERT**, no partial manifest.
2. **P1 / CONCURRENT-IDEMPOTENCY (proof added):** T47 PostgreSQL tests two simultaneous `capture` calls for same tenant/actor/CRM source/requestKey. The CRM row lock serializes capture and unique request receipt permits exactly one sealed snapshot and both callers get the same ID.
3. **P1 / CAPACITY-BOUNDARY (proof added):** T48 PostgreSQL seeds 10,001 linked tasks with tenant-scoped FKs, transitions CRM source to CANCELLED and asserts capacity error with no header/seal.
4. **QA / EVIDENCE COUNTS (fixed):** Mandatory TASKS suite and global exact-SHA verifier now require 48 discovered scenarios, all 48 executed on PostgreSQL, and 15 PostgreSQL-only H2 skips. Python verifier fixtures amended accordingly.

## QA and gaps — admission claims are SHA-specific

- Prior `9e74fe7c022435a99c7e3511b162d8ad8db5e152` had TASKS 46/46 PASS, Module JUnit/CRM/SALES/INVENTORY PASS and RustFS PASS; Domain Security and aggregate exact-SHA were still **in_progress** on inspection. Do not claim it was exact-SHA-green.
- New commits require their own same-SHA PASS, including mandatory H2, PostgreSQL, domain security, populated domain upgrades and SHA evidence aggregator. Do not infer PASS from source code or previous green tests.
- **Still open:** true **populated PostgreSQL 0148–0152 upgrade qualification** with existing pre-upgrade task/CRM/event rows and immutable triggers. A fresh Liquibase bootstrap is not a populated upgrade.
- **Still open:** crash **after commit before returning a response** beyond deterministic re-fetch, post-commit restore from backup and integrity digest verification, large-tenant `EXPLAIN (ANALYZE, BUFFERS)` including 10k snapshot, lock contention p95/p99 and 10k boundary success.
- **Still open:** concurrent actual CRM status change during snapshot capture tested with explicit database interleaving; same-key concurrent capture tested, but true source-cancellation race and role/membership revocation under this service deserve independent proof.
- **Privacy:** Historical free-text task titles and task snapshot retention are undecided; no customer PII may enter snapshot/outbox. KMS-encrypted notes remain STOP.

## R3.2B architecture gate (not implemented)

Do not start SLA timer or notification delivery until approved tenant policy versions, working calendars/holidays, retention, responsible identity, consent, and owner escalation contract exist. For outbox, require atomic event+intent commit, event-id idempotency, `FOR UPDATE SKIP LOCKED`, lease/fencing, bounded retry/dead-letter, after-send/before-ACK crash, dual workers, provider ACK semantics and safe replays. At-least-once transport is **not exactly-once notification delivery**. Reuse existing RM outbox conventions, no Redis/broker by default.

## Development constraints

Continue only on `base_dev`; no additional task aggregate, REST controller/UI, migration rewrite or `main` merge. Report no production admission until independent domain and security gates qualify exact commit SHA.
