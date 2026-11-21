# RM-LIQUIBASE-PARTITIONING-R1 — ревизия SQL и PostgreSQL partitions

**Дата:** 2026-11-21. **Ветка:** `feat/rm-kms-r1-implementation`.
**Статус:** implementation candidate / PostgreSQL CI qualification; **production migration gate CLOSED**.
**Нормы:** [RM-PARTITIONING-R1](../spec/RM-PARTITIONING-R1.md), [RM-IMPLEMENTATION-BASELINE-R1](../spec/RM-IMPLEMENTATION-BASELINE-R1.md).

## 1. Стратегия по текущим таблицам

| Домен | R1 layout | Обоснование |
|---|---|---|
| IAM, KMS, ORG, GEO | UNPARTITIONED | глобальная уникальность и тесные FK |
| CATALOG, SUBSCRIPTION | UNPARTITIONED | стабильные SKU, планы и уникальность |
| FILES, CRM/PII | UNPARTITIONED; HASH tenant candidate | tenant FK, KMS/AAD; до HASH нет нагрузочного доказательства |
| SALES: orders/lines/receipts | UNPARTITIONED + composite indexes | order references and tenant-wide idempotency |
| INVENTORY balance | UNPARTITIONED | atomic stock uniqueness and reservation constraints |
| FINANCE invoice/payment | UNPARTITIONED | immutable document number and transaction constraints |
| MARKETING campaign, TASKS | UNPARTITIONED | мастер-данные |
| AUDIT event | PostgreSQL yearly RANGE(occurred_at) → 4 HASH(tenant_id), H2 unpartitioned | append-only/retention |
| Будущие ledger, stock movement, delivery/status events | PARTITION CANDIDATE | отдельная будущая миграция после реальных измерений |

КАТО, locale, SKU, tenant identity и KMS keys не включаются в partition key. Не создавать per-tenant child tables.

## 2. Liquibase migration safety

- **0018 PostgreSQL-only:** сохраняющая строки миграция crm.audit_event через временное переименование, новый RANGE parent, children 2025/2026/2027 + DEFAULT и четыре hash buckets в каждом периоде. Primary key меняется на (tenant_id,occurred_at,id), то есть уникальность id без времени на partitioned parent **не гарантирована**.
- **0019 H2/PostgreSQL:** составные tenant+time индексы на CRM, SALES, FILES, FINANCE; не меняет business primary keys.
- Changesets 0001–0017 не переписывались. Existing UUID tenant, geo region and KMS envelopes unchanged.
- На существующей базе требуется **preflight**: никакие inbound FK не должны ссылаться на старый audit PK, backup/PITR, row counts/hash before/after, доступное окно блокировки DDL. Автоматический rollback через reverse SQL отсутствует.
- DEFAULT partition забирает годы вне 2025–2027; добавлять новые годы только после проверки строк DEFAULT, иначе PostgreSQL ATTACH/CREATE PARTITION потребует переноса данных и блокировок.
- Число partitions (16 leaf) и годовые диапазоны — квалификационная отправная точка, **не финальный production sizing**. Проверять monthly/quarterly alternatives на нагрузке.
- Для полной upgrade приемки нужно поднять заполненную предыдущую DB, выполнить миграцию, подтвердить бизнес counts/hashes и расшифровку CRM secrets. Fresh H2/PG CI недостаточно.

## 3. Acceptance

PostgreSQL test: pg_partition_tree count=16, historical 2024 routes DEFAULT, 2026/2027 routes year+hash, duplicate composite key fails, foreign tenant fails. H2 skips PostgreSQL-only DDL and remains unpartitioned. Existing KMS/CRM/SALES integration tests must remain GREEN.

**Production HOLD:** no throughput comparison / 2-pod qualification / real upgrade rehearsal / backup restore validation. Further partitioning of orders, PII, invoices and inventories would risk weakening uniqueness and referential integrity.

## 4. Следующий рубеж

PART-R1.2 — EXPLAIN(ANALYZE,BUFFERS) and prepared JDBC pruning, workloads of 1m/5m/20m operations, p95/p99, hot tenant skew, maintenance, extension into new event periods, full backup/PITR and safe upgrade rehearsal.
