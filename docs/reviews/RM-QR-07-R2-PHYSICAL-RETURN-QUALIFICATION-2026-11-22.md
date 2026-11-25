# RM QR-07/R2 — Return Reception, Inspection & Inventory Posting

**Дата:** 2026-11-22  
**Ветка:** `base_dev`. **Квалифицированный кодовый SHA:** `c386f6cea27745ff0596306003cd2ff1dd635fee`.  
**PostgreSQL 17 exact-SHA CI:** https://github.com/alimbetov/RM/actions/runs/45043882942  
**H2 module CI same SHA:** https://github.com/alimbetov/RM/actions/runs/45043882836

**Решение:** R2 service-level PostgreSQL scope **PASS**; full-project PostgreSQL and production admission **HOLD**.

## Реализованная архитектура

1. `SalesReturnPhysicalService.receive()`: проверяет `SALES_RETURN_RECEIVE`, активное warehouse-scoped IAM назначение, ожидаемую revision, исходный `APPROVED` возврат. В одной транзакции проводит `APPROVED → AWAITING_RECEIPT → RECEIVED`, вставляет append-only `sales_return_reception` и два события. Без складских проводок.
2. `inspect()`: требует `SALES_RETURN_INSPECT`, точный склад физической приёмки, ревизию `RECEIVED`; фиксирует immutable `sales_return_inspection` и `INSPECTED`. Поддерживает `RESTOCK`, `WRITE_OFF`, `QUARANTINE`. Ни одно решение не увеличивает доступный остаток до `post()`.
3. `post()`: требует `SALES_RETURN_POST` и warehouse scope, исходный immutable fulfillment/return-line provenance, существующую приёмку и инспекцию. При `RESTOCK` вызывает внутренний `ReturnInventoryPostingPort` с отдельным `INVENTORY_RECEIVE` gate, который пишет ровно один `InventoryMovementService.Reason.RECEIPT` с детерминированным ключом `R07_{returnId}`. Ссылка на созданное движение и данные provenance сохраняются в `sales_return_posting` в той же Spring/PostgreSQL транзакции. Состояние переходит в `RESTOCKED`.
4. `WRITE_OFF` в R2 означает **утилизацию уже физически возвращённого, но ещё не зачисленного в sellable stock товара**: фиксируются `DISPOSED` и immutable posting без фиктивного положительного/отрицательного движения по sellable on_hand. Это НЕ заменяет отдельный процесс списания ранее принятого на баланс товара.
5. `QUARANTINE` фиксируется в инспекции, но `post()` **запрещён**, пока не реализованы отдельные карантинные локации, статус и перевод в/из карантина. Возвращённый товар не становится продаваемым.
6. Liquibase changesets `0098`/`0099`: tenant-qualified FK, уникальность возврата и ключей, неизменяемые reception/inspection/posting и FK на складское движение. Не изменяют исходные SALES `COMPLETED`, pickup receipt или исходное списание.
7. Нет новых контроллеров. `main` не изменён.

## Доказанные сценарии

**PostgreSQL 17:** GitHub job `qr07-r0-postgresql`, workflow run [45043882942](https://github.com/alimbetov/RM/actions/runs/45043882942), **17 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. 3 R0 + 7 R1 + 7 R2 методов.

R2:
- RESTOCK — приёмка и инспекция не меняют остаток; ровно одна подтверждённая обратная проводка с FK на возврат; retry возвращает исходный receipt.
- WRITE_OFF — факт disposal, `movement_id` пуст, никаких складских дельт; QUARANTINE запрещает posting.
- IAM/tenant/scope negative — чужой tenant/сотрудник и отозванное складское назначение не принимают товар; premature posting отклоняется.
- Race двух потоков — только один из двух конкурирующих физических scans создаёт reception.
- Реальный отдельный процесс: hard JVM halt до COMMIT откатывает транзакцию и позволяет точный retry без двойного начисления.
- Реальный отдельный процесс: hard JVM halt после COMMIT, до ответа; повтор не начисляет склад второй раз.
- Два независимых JVM конкурируют за проведение одного возврата; остаются одна posting запись и одно inventory movement.
- Сохранность исходного pickup подтверждена проверками ledger.

**H2 (тот же SHA):** [workflow 45043882836](https://github.com/alimbetov/RM/actions/runs/45043882836): **96 tests, 0 failures, 0 errors, 12 skipped, BUILD SUCCESS**. PostgreSQL-only сценарии не превращаются от этого в доказанные H2-тестами.

Первый R2 кандидат не собрался из-за неверной перегрузки `JdbcTemplate` в тесте, следующий прогон упал на неоднозначной типизации nullable UUID, затем PostgreSQL выполнил 15/17 тестов, но два process-crash теста завершились `DirectoryNotEmpty` из-за ошибочной временной фикстуры. Эти ошибки тестовой инфраструктуры исправлены; финальный кодовый SHA указан выше.

## Незакрытые требования

- **Full-project PostgreSQL regression**: module PostgreSQL job в параллельном общем workflow был SKIPPED. Зелёный 17-test QR07 gate **не является** полной регрессией RM.
- Реальные сканеры, подписанные/одноразовые физические QR-доказательства, сетевые обрывы, внешний OIDC и HTTPS: не подтверждены. `evidence_ref` сейчас лишь UAT-идентификатор, не криптографическое доказательство реальной физической приёмки.
- QUARANTINE: отдельный ledger карантинных мест хранения/непродаваемый остаток, disposition after quarantine и negative stock conservation — не реализовано.
- Финансовые возвраты, частичные компенсации, возврат платежей и обмен: отдельный API/domain, не реализованы.
- Большая смешанная нагрузка приемок + выдач + перемещений, сценарии двух tenant с конкурирующим staff revocation и populated data миграция: будущая квалификация.
- QR-06B/B3.5 устойчивый throughput ceiling, реальные network fault injection и production release остаются **HOLD**.

**Финальное решение:** QR-07/R2 сервисный scope PASS на указанном exact SHA; QR-07 как полный production модуль ещё HOLD. Следующим этапом — R2 hardening: реальный карантин, доказательство происхождения сканирования, специализированная конкуренция IAM × складская проводка, общий PostgreSQL gate. QR-08 можно продолжать проектировать, но нельзя автоматически считать квалифицированным.
