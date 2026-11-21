# RM — технический аудит доменного слоя после merge в main

**Дата:** 2026-11-21. **Аудируемый commit:** `f2e32bc053c6c0f3e9c5d1eb14af97978ab1eca8`.
**Область:** 15 bounded contexts, Java 21/Spring, JDBC/JPA, PostgreSQL/Liquibase, IAM/tenant, KMS, integration CI. **Метод:** source/static review + инспекция CI runs; новых E2E/нагрузочных запусков в ходе самого аудита не выполнялось.
**Вердикт:** архитектурный foundation usable for continued development; **production admission HOLD**. GREEN старого feature-commit не эквивалентен full domain E2E.

## Подтверждённые достоинства

- 15 уникальных `DomainModule` и `DomainStandards` со scope/privacy/retention/partition strategy; но эти правила — декларации, а не automatic enforcement.
- Tenant-specific composite PK/FK в CRM/Sales/Inventory/Files и запрет negative stock `CHECK(on_hand>=reserved>=0)`.
- INVENTORY: transaction scoped `FOR UPDATE`, idempotency locks, immutable movement ledger и reservation event; cancel/release/fulfill E2E в H2/PG.
- IAM: global encrypted email with HMAC lookup; buyer order bound after challenge proof; onboarding status PENDING; token never included in checkout response.
- CRM/KMS: encryption envelopes for person/contact PII; KMS key rotation fail-closed.
- AUDIT: PostgreSQL RANGE→HASH prototype; approved 3–5 year tenant-specific retention with legal hold and default-off scheduler.
- Latest pre-merge feature CI `44887481062` and `44887486278` GREEN at `e77b7b1...`; main merge at `f2e32bc...`.

## P0 — до открытия публичных endpoints и production

**P0-01: CRM authorization divergence and inactive-actor access.**
`CrmCustomerService.requireOrganizationMember` checks workspace kind and membership status, but **does not check actor's `iam_user_account.status` or workspace `ACTIVE` status** and permits write by MEMBER (only VIEWER blocked). In contrast, `TenantAuthorization.requireOrganizationWriter` requires actor ACTIVE, tenant ACTIVE, OWNER/ADMIN/MANAGER. If a suspended or deleted user still has ACTIVE membership, CRM operations can be called with that actorId. **Fix:** one canonical IAM permission port for CRM read/write with explicit roles, active account/workspace check and principal-derived IDs; negative E2E for suspended user, workspace and MEMBER.

**P0-02: no trusted public principal/session admission.**
`AutoOrderService.buyerOrders(authenticatedUserId)`, `CrmCustomerService` and `InventoryReservationService` rely on an internal caller supplying trusted IDs. There is no complete authenticated OIDC principal/BFF adapter in the inspected source. `InventoryReservationService` has no authorization inside its `reserve/release/fulfill` methods and takes tenantId as caller-provided argument. **Fix:** enforce a mandatory authenticated actor/tenant context at application boundary, separating public buyer claims from seller-authorized stock changes; do not expose current internal signatures as HTTP request fields.

**P0-03: legacy IAM plaintext and unfinished email admission.**
`UserAccountEntity.emailNormalized` / `iam_user_account.email_normalized` persists alongside encrypted `iam_email_identity`. `EmailIdentityService` falls back to plaintext lookup for legacy account linking, but comprehensive migration/backfill, verified email sender, throttle/enumeration/replay hardening and OIDC binding remain absent. **Fix:** versioned canonicalization and HMAC migration, key consistency, verification dispatch with lease/fencing, rate limits, account claim tests.

**P0-04: CI does not cover pushes to main.**
`.github/workflows/kms-r1-qualification.yml` enables push CI only for `feat/rm-kms-r1-implementation`; pull requests and manual dispatch trigger the workflow, but the merge commit `f2e32bc...` is not recorded as main push run in the inspected recent runs. **Fix:** add `main` to push triggers; branch-protection required checks H2 + PostgreSQL plus migration upgrade tests and codeowners/security review. Run explicitly against latest main and inspect result.

**P0-05: production AUDIT retention and partition migration not qualified.**
`0018` replaces `crm.audit_event` with a partitioned table by rename/copy/drop; potential DDL locking and upgrade-time risk. `AuditRetentionService` runs DELETE with explicit tenant policies/legal holds, but DB grants, privileged hold administration, dry-run, backup/PITR, preflight inbound FK, migration data counts/CRC and recovery tests remain not qualified. Flag is false by default. **Fix:** keep retention off and do populated PostgreSQL rehearsal, DB privileges and load/backup admission before enabling.

## P1 — domain correctness and boundaries

**P1-01: SALES / INVENTORY lifecycle is not a single invariant.**
`InventoryReservationService.reserve` checks `sales_order.status` (CONFIRMED or PREPARING) with row lock. Fulfillment depends on mutable order status; `AutoOrderService` creates PLACED only, but no canonical seller order transition service / cancellation orchestration with atomic reservation release is in current source. Direct order status updates in test illustrate missing layer. **Fix:** explicit SALES state machine and atomic cancellation/release orchestration + outbox; timeout and crash recovery for orphan ACTIVE reservations, tests for CANCELLED→no ACTIVE reservation.

**P1-02: Inventory valuation settings are not a costing engine.**
`inv_tenant_setting` and `inv_cost_policy_version` are real persisted configuration, with immutability triggers, optimistic version and rights checks; `InventoryTenantSettingsService.approveCostPolicy` refuses existing nonzero/unvalued stock. But FIFO cost layers, moving average, stock receipts with unit costs, COGS posting, period close, FX and valuation reconciliation are not implemented. **Fix:** hold financial posting until INV-R1.2 ledger E2E and FINANCE binding.

**P1-03: business logic mixed with SQL and cross-domain repository knowledge.**
`AutoOrderService` directly writes IAM verification, seller order and storefront offers through SQL; `InventoryReservationService` directly joins SALES, STOREFRONT, CATALOG, INVENTORY. `OrganizationEntity` resides in IAM while ORG owns it; physical `crm.store_*`, `crm.sales_*` use shared schema. This is a modular-monolith orchestration boundary, but should be exposed through typed ports and outbox, not permanently coupled SQL internals. **Fix:** application orchestration + separate IAM/SALES/INV ports; enforce architecture tests forbidding cross-domain direct repositories on the business layer.

**P1-04: configuration and invariants not enforced end-to-end.**
`DomainStandards` is declarative; there is no per-domain mandatory tenant filter, no uniform actor permission object. `auto_reserve_on_confirm` is stored but not wired to a seller confirmation event; approved `inv_cost_policy_version` does not yet govern any valuation posting. `crm.cat_price` is stored but checkout still reads `crm.store_offer`. **Fix:** explicit feature-admission status/contract for each setting, prevent false-ready UI, and add service-level E2E for every enabled mode.

**P1-05: expiry/cancellation and inventory reconciliation.**
There is no reservation expiry field/lease or recovery worker; all reservations remain ACTIVE until manual release/fulfill. Ledger movements are immutable but no periodic reconciler proves `on_hand` consistency with receipt + adjustment + fulfillment events. **Fix:** expiry policy, reaper/reconciler with fencing, negative tests for crashed worker and double fulfillment.

**P1-06: organization and IAM permissions drift.**
`TenantAuthorization` has one broad `requireOrganizationWriter` role set; CRM has another; TASKS/FILES/CATALOG/FINANCE/MARKETING have no implemented command-side permission gateways. **Fix:** permission matrix by domain and operation (OWNER/ADMIN/MANAGER/STAFF/VIEWER), not merely by package.

**P1-07: domain record vs persistence status.**
FILES has no encrypted blob adapter, CATALOG no complete publish/price service, FINANCE only invoice table/Money, MARKETING and TASKS mainly state records, AUDIT no universal immutable event producer, SUBSCRIPTION no billing/entitlement enforcement. These are **schema baselines**, not complete business domains. No user-facing percentage of production completeness should be inferred from table counts.

## P2 — maintainability and operability

- One large `db.changelog-master.sql` contains 30 sequential changesets; do not rewrite historical migrations, but move *future* migrations into per-domain files included from a new master, with upgrade/fresh DB parity gates.
- 256 checkout and 64 inventory hash lock stripes ensure early-stage idempotency correctness but can serialize unrelated transactions; benchmark and redesign by observed throughput/latency.
- Audit annual partition bounds 2025–2027 and DEFAULT need pre-created future partitions and measured pruning (EXPLAIN with JDBC parameters), legal hold/retention-aware drop plan. No partition per SKU/locale/KATO.
- Tests use PostgreSQL 17 service and H2 dev but not two independent processes, read replicas, failure injection, populated DB migrations, tenant-skew or privilege-separated DB roles.

## Maturity matrix at audited main

| Bounded context | Actual state |
|---|---|
| IAM | Partial business logic: user/workspace/account/email crypto, admission gaps |
| ORG | Partial: locations, active writer gate; entity ownership debt |
| GEO | Partial: KATO lookup & validation |
| CRM | Partial: encrypted customer/contact/consent; authorization defect |
| KMS | Partial: DEK wrap, fenced job; distributed rotation disabled |
| SALES | Partial: orders, price snapshots, receipts; state orchestration incomplete |
| STOREFRONT | Partial: offer/store schemas & internal checkout; no public admission |
| INVENTORY | Partial: movement, reservation, settings; no cost ledger/expiry |
| FILES | Persistence only, no secure file IO |
| CATALOG | Persistence only, no price/publish workflow |
| FINANCE | Contract/DB invoice only, no ledger/payments |
| MARKETING | Campaign state/DB only |
| TASKS | Task state/DB only |
| AUDIT | Retention/partition implementation partial; no complete event sink |
| SUBSCRIPTION | Plans/subscription persistence only |

## Recommended remediation order and acceptance

1. **SEC-R1.0:** unify IAM/CRM/ORG authorization and principal-derived actor; enforce negative tenant/user-status tests; keep public API off.
2. **CI-R1.0:** require latest main H2+PostgreSQL, fix workflow push/main and protected branch checks; populated-DB Liquibase migration proof.
3. **SALES-INV-R1.2:** immutable seller order transition + cancellation/reservation release in one transaction, expiration/reconciliation, concurrency 2-process test.
4. **IAM-R1.2B:** legacy identity migration, delivery outbox worker, OIDC claim anti-abuse.
5. **INV-VAL-R1.2:** qualified inbound receipt + FIFO / moving-average immutable valuation + cost reconciliation to FINANCE.
6. **FILES/CATALOG/FINANCE/MKT/TASKS/AUDIT/SUB:** independently qualify command/read API, IAM permission, PII/data ownership, failure handling, PostgreSQL E2E.
7. **PERF/RETENTION-R2:** 2-pod and 1m/5m/20m audit history, p95/p99, PITR, security DB roles, partition lifecycle.

**Audit result is NOT a claim that defects were fixed.** This is a source-level technical review of the exact merge commit; no independent manual production infrastructure or recovery test was executed. All production admission gates remain closed.
