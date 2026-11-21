# RM-DOMAIN-SQL-IMPLEMENTATION-R1 — AUDIT и INVENTORY

**Дата:** 2026-11-21. **Ветка:** feat/rm-kms-r1-implementation. **Статус:** реализация кодового и SQL-среза; production/API admission HOLD.

## Реализованные компоненты

1. AUDIT: AuditRetentionPolicy — чистый доменный контракт хранения 3–5 календарных лет с обязательным approval, точной границей истечения срока и legal hold.
2. INVENTORY: Liquibase 0022 создаёт crm.inv_stock_movement, tenant-scoped composite FK на баланс, ключ идемпотентности, математическое CHECK-ограничение изменения остатка, индекс tenant/location/time.
3. INVENTORY: Liquibase 0023 создаёт 64 транзакционные блокировочные записи по ключу запроса; порядок блокировок стабилен (request stripe → stock balance). Не используются Redis или JVM mutex.
4. INVENTORY: InventoryMovementService.adjust — транзакционное изменение остатка и запись движения, строгая BigDecimal арифметика, защита от списания зарезервированного остатка, повторной обработки и чужих tenant-данных.
5. INVENTORY: PostgreSQL-only changeset 0024 запрещает UPDATE/DELETE проведённого движения через trigger. Исправления осуществляются новой корректирующей операцией, а не изменением истории.
6. Тесты: AuditRetentionPolicyTest и InventoryMovementQualificationTest; конкуренция, tenant/FK, idempotency, stock floor и PostgreSQL immutability.

## Не выполнено

- Публичный REST и principal-derived role permission для INV; внутренний service нельзя вызывать от имени непроверенного tenant, переданного клиентом.
- Stock opening, transfer, reservation, reconciliation и полноценная SALES-интеграция.
- Ночные удаления INVENTORY **не включены**: складские, финансовые и юридически значимые события не обязаны разделять AUDIT 3–5 year TTL.
- Least-privilege DB grants, заполненная upgrade-DB, 2-pod нагрузка, retry/failover и release approval.

## Следующая очередь

INV-R1.1 — opening balance, reason-codes, corrective stock movement, tenant role admission, reservation и no-oversell E2E. RETENTION-R1.1 — полная классификация истории, dry-run и юридические ограничения. MKT-R1.0 — журнал кампаний и согласий, FIN-R1.0 — защищённая история бухгалтерских операций с независимым сроком хранения.

Не переписывать уже применённые Liquibase changesets 0001–0021. Все будущие изменения — новыми changesets и PostgreSQL E2E.
