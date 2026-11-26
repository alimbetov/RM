# D07 — Сценарии, конкурентность и доказательная приёмка
R2.0-draft · PLAN ONLY / NOT EXECUTED · Source SHA d9242e539a07ef1843c0e72aba9c651204bf42ae.
Owners: QA + соответствующий owner service + IAM. Новые тестовые методы не написаны и не запущены. [Traceability JSON](TRACEABILITY-R2.json) — полный список 88 унаследованных семейств, 8 метрик, 22 источников и 40 детализированных сценарных записей. Числа не складываются: сценарий может покрывать несколько семейств.

## 1. Контракт доказательства
Каждая конкретная runtime-проверка должна хранить requirement/scenario/family IDs, source candidate SHA, exact test class+method, engine/version, fixture provenance, controlled interleaving, expected invariants, observed SQL snapshots/digests, XML, artifact SHA, CI URL и итог.
Существующий метод owner regression не доказывает новый Customer/EVT consumer. Обязательные concurrent variants включают оба commit orders; clocks управляемые, не sleep-only guesses; PG waiting/locks или барьеры фиксируют interleaving.
Результаты: SPECIFIED/IMPLEMENTED/SERVICE TESTED/PG QUALIFIED/USER ACCEPTED/RELEASE ADMITTED; PLAN/DEFERRED не PASS. Evidence SHA обязан совпадать с candidate, tests>0, обязательные skips недопустимы без действующего узкого исключения.

## 2. Детализированная сценарная матрица
Все rows — planned; G2/G3/G4/Radar выполняются только после своих gates.

| ID | Given / When | Then / oracle / evidence | Baseline families |
|---|---|---|---|
| R2-S01 | Один SKU: receipt12, заказы A3/B2 по1000, confirm обоих, fulfill A, cancel B, RESTOCK1 | 8 результатов 2/5000/2/1/1/13/3/10; stock checkpoint table и average2500; hand oracle + direct SQL | IE-Q04, IE-Q06, IE-Q14, IE-Q15, MC-01, MC-02 |
| R2-S02 | Два currency и большие decimal sums, fractional movement quantity | Раздельные суммы и denominator; точный NUMERIC/BigDecimal, overflow явный; source currency precision сохранена | IE-Q03, IE-Q06 |
| R2-S03 | D/W/DEC/M/Q/H/Y, leap day, ISO year boundary, DST gap/overlap | Точные half-open Instant intervals; boundary один раз; неделя через месяц не присваивается целиком месяцу | IE-Q02, IE-Q07 |
| R2-S04 | PLACED в предыдущем месяце, CONFIRMED сейчас, затем CANCELLED | Transition cohorts независимы, нет фиктивной конверсии или ретроактивного вычитания | IE-Q04, IE-Q06, MC-01 |
| R2-S05 | Повтор доставки, два разных PLACED, missing PLACED, null snapshot | Повтор ID no-op; duplicate canonical transition/unknown lineage даёт non-READY; не прятать defect через DISTINCT | IE-Q05, IE-Q06, IE-Q08 |
| R2-S06 | Дневные average10 и100 при разном count; пустая cohort | Ratio of sums по hand example; denominator0 не 0; incompatible filter/versions denied | IE-Q04, IE-Q12 |
| R2-S07 | Reserve/release, fulfill, текущая цена изменена после checkout | Резерв не движение; историческая сумма прежняя; live available соответствует owner | IE-Q06, IE-Q14, MC-01, MC-02 |
| R2-S08 | Transfer двух warehouse, встречные concurrent transfers | Два legs, tenant net0, одна unit/SKU, scope не раскрывает чужой склад; header не прибавляется | IE-Q14, IE-Q15, MC-02 |
| R2-S09 | Частичный возврат с RESTOCK/WRITE_OFF/QUARANTINE в разных fixtures | Только linked restock увеличивает sellable; writeoff после handover не второй debit; нет выдуманного refund | IE-Q15, MC-02 |
| R2-S10 | QUARANTINE затем RESTOCK или DISPOSE, replay/concurrent resolve | Quarantine уменьшается один раз; stock+1 только RESTOCK; исходный receipt не считается движением повторно | IE-Q15, IE-Q08, MC-02 |
| R2-S11 | Reserve/release между движениями дают gaps posting_version | Не ложный MISSING по разрыву; ordering только внутри position; max не общий commit cursor | IE-Q05, IE-Q14, IE-Q16 |
| R2-S12 | Legacy начальный остаток без полного ledger, nullable version/UOM | Не реконструировать opening=0; UNKNOWN coverage; cost turnover/COGS заблокированы | IE-Q05, IE-Q16 |
| R2-S13 | Customer summary/drilldown × revoke/dispute link, оба порядка commit | До revoke admitted read может завершиться; revoke первым прекращает attribution без ожидания rebuild | IE-Q11, IE-Q13, MC-04 |
| R2-S14 | Read × buyer/account/member/tenant suspension/delete, оба порядка | Нет результатов или counts после первого committed revoke; текущие транзакционные fences, no cached JPA authorization | IE-Q11, IE-Q13, MC-04 |
| R2-S15 | CRM user видит customer, но не SALES или чужую TASK | Закрытая секция и её count не утекли; TASK creator/assignee остаётся обязательным | IE-Q13, IE-Q19 |
| R2-S16 | Warehouse view × assignment revoke; tenant-manager policy; одинаковые IDs двух tenants где DDL допускает | Current scope проверен; grants не выдуманы; без чужих counts/cursors | IE-Q13, IE-Q19, MC-04 |
| R2-S17 | Signed access JWT: wrong signature/issuer/audience, ID/refresh, expiry, spoofed user claims | Только настоящий IAM resolver; reject до source/intent; expiration также перед output | IE-Q13, MC-04 |
| R2-S18 | Timeline/evidence paging: ties, sparse filters, tampered cursor, scope change | Filter before limit; keyset stable для static set; live semantics disclosed; no unbounded read/export | IE-Q19 |
| R2-S19 | ABC [80,15,5],[70,20,10],ties,zero,negative,missing SKU,mixed currency | Результаты утверждённой boundary policy; exclusions/coverage; warehouse inference denied | IE-Q17, MC-02 |
| R2-S20 | XYZ 12×10; six0/six20; missing; allzero; stockout; threshold equality | CV0/100% по population formula; UNKNOWN where insufficient; threshold comparison без early rounding | IE-Q18, MC-02 |
| R2-S21 | Typed rule/template preview, injection/unknown placeholders, disabled rule | Ни intent, ни schedule/delivery; invalid expression rejected; безопасный output | IE-Q01, IE-Q21, IE-Q23 |
| R2-S22 | Event replay после revision/template update, pause/resume | Один immutable occurrence binding; новая revision не рассылает старое событие повторно | IE-Q22, IE-Q24, IE-Q27 |
| R2-S23 | Role audience изменена, recipient потерял permission/account/warehouse scope | Сохранённая audience не даёт бессрочного права; final eligibility/current open access; no late new-member send | IE-Q22, IE-Q27, MC-04 |
| R2-S24 | Owner checkout/status transaction rollback на последней записи | Нет committed event/outbox/intent; оба checkout paths; source-side rollback proof | IE-Q20, MC-03 |
| R2-S25 | INT succeeds, EVT fails/retries; обратный порядок, повтор event | Independent receipts; аналитика не ждёт notification; один visible in-app | IE-Q09, IE-Q24, MC-03 |
| R2-S26 | Два PG claimers, lease expiry, late ack/heartbeat прежнего worker | Generation fencing: stale actor не меняет новый attempt; один committed effect | IE-Q25 |
| R2-S27 | Process kill до/после in-app commit и потерянный ответ | 0 либо1 notification/receipt atomically; replay восстанавливает единственный результат | IE-Q26, IE-Q24 |
| R2-S28 | Pause/tenant stop/revoke/expiry × final delivery оба commit orders | Доказанный linearization; denied first → нет нового effect; safe terminal disposition | IE-Q27, MC-04 |
| R2-S29 | Transient/permanent errors, capped retry, DEAD operator retry | Bounded backoff; та же logical identity; нет второй строки notification; mock не provider ACK | IE-Q25, IE-Q24 |
| R2-S30 | Task reschedule/complete; contract renew/close; DST/missed-window/restart | Старая occurrence отменена; inclusive end preserved; no fabricated source event ID | IE-Q20, IE-Q27 |
| R2-S31 | Backfill × late commit с меньшим timestamp/UUID, duplicate overlap | Ни lost fact, ни double delta; receipts+result atomic, explicit progress, no max-sequence shortcut | IE-Q08, IE-Q09, MC-05 |
| R2-S32 | Shadow rebuild при live updates; mismatch; crash switch; old cursor | Mismatch blocks switch; generation consistency; rollback freshness disclosed; parity with direct oracle | IE-Q07, IE-Q10, MC-05 |
| R2-S33 | SQL timeout/error/cancel, partial source, stale projection, complete empty | Denied/error/unknown/zero различаются; no success-shaped zero; no partial mutation | IE-Q05, IE-Q09 |
| R2-S34 | Clean/populated Liquibase с legacy null/provenance и текущими applied checksums | Schema manifest, constraints/indexes, compatibility; existing changesets неизменны; new schema only after admission | IE-Q29 |
| R2-S35 | PG17 1k/10k/100k, skew/history, mixed checkout/reserve/fulfill/TASK плюс INT/EVT | Actual overlap, plans/locks/p95/p99/throughput, predeclared budgets, bounded backlog и command regression | IE-Q30, MC-05 |
| R2-S36 | Снимки owner ledgers до/после reads/preview/replay, secrets in labels | Нет unexpected business DML, payload/log/DTO allowlist; source correctness retained | IE-Q21, IE-Q28, MC-03 |
| R2-S37 | Обязательные owner regression и signed-token negative suites на финальном candidate SHA | Обязательные XML/artifacts и manifest; не считать метод по имени executed; отсутствие необъяснённых skips | IE-Q32, MC-01, MC-02, MC-04, MC-05 |
| R2-S38 | Будущий G2: BUS-J1/QR-J1/I1 с реальным OIDC и role-scoped UI | Отдельные браузерные сценарии с owner drilldown, без нового UI в текущей итерации | IE-Q31, MC-08 |
| R2-S39 | Будущие G3/G4: pilot UAT, restore/rollback, независимый release review | Подписанная пользовательская приёмка и release decision; документ не PASS | IE-Q32, MC-08 |
| R2-S40 | Будущий Radar: sources stale/missing, top-K/ties, current permissions, proposed action | 48 BV families остаются раздельно нормативными; no auto actions, no false resolution; explicit resumption | MC-06, MC-07 |

Каждая строка раскрывается в конкретные cases до implementation admission соответствующего slice; например R2-S14 — разные account/buyer/member/tenant writers и два commit orders, не один universal assertion. R2-S40 не заменяет 48 отдельных BV obligations. Customer/warehouse scope и unknown reasons проверяются не только по DTO, но также count/cursor/log/notification side channels.

## 3. Проверенные в исходниках точки повторного использования
| Файл / существующий метод | Что можно переиспользовать | Что ещё нужно для R2 |
|---|---|---|
| CrmBuyerLinkQualificationTest#buyerRevocationStopsHistoryAndAllowsNewExplicitConsent | Owner link revoke fixture и история | Summary/projection/drilldown revocation и оба порядка |
| CrmBuyerLinkQualificationTest#suspendedBuyerHidesSellerHistoryAndPreservesLinkEvidence | Buyer suspension semantics | Новый consumer не раскрывает cached totals |
| InventoryReservationQualificationTest#fulfillDebitsExactlyOnceAndPostsImmutableMovement | Reservation/fulfill ledger assertions | Parity M-I01/02/03 и downstream receipts |
| InventoryTransferStocktakeQualificationTest#transferWritesExactlyTwoLegsAndReplaysWithoutDuplicateBalances | Paired transfer invariant | Warehouse view/scope/period aggregation |
| InventoryTransferStocktakeQualificationTest#postgresInv16DurableAdvanceAndConcurrentReceivingHaveNoFalseDrift | Concurrent position/reconciliation fixture | Analytical backfill/live source completeness |
| QrWarehouseHandoverQualificationTest#qr07R2WriteOffAndQuarantineNeverIncreaseSellableStock | Return disposition fixture | Warehouse history не double-debit/double-count |
| QrWarehouseHandoverQualificationTest#qr07R25QuarantineReleaseRestocksExactlyOnceAndLeavesOriginalReceipt | Resolution RESTOCK + immutable receipt | Derived +1 exactly once после replay |
| TenantRbacRevocationQualificationTest#commandCannotProceedUntilEarlierRevocationCommits | IAM lock ordering / deny after revoke | Новые read/final delivery lock paths |
| OidcJwtCryptoQualificationTest#signatureIssuerAudienceAndExpiryAreAllRequired | Crypto fixture | Access purpose/claims/tenant + new service entrypoints |
| QrWarehouseHandoverQualificationTest#qr07R25StrictRet04E06IdToken | Current access-purpose negative case | Не переиспользовать фиктивный VerifiedActor |
| TaskTimingCompletionQualificationTest#e04ReschedulePreservesOriginalLateDespiteEffectiveGreen | Original vs effective timing | EVT invalidation, Radar adapter contract |

Файлы/методы проверены на source SHA; ссылочные anchors и пути в TRACEABILITY-R2.json. Это reuse candidates, не повторная сертификация. Для valid fixtures предпочтительны end-to-end owner service transitions: старые low-level тесты с SQL setup не объявлять доказательством business producer.

## 4. Топология прогонов
- Pure Java: period arithmetic, BigDecimal, closed predicates, ABC/XYZ examples.
- H2: совместимость SQL/миграций, базовые сервисные сценарии; не доказательство PG locking.
- PostgreSQL 17: clean/populated schema, транзакции, leases, fences, both commit orders, process kill/restart, shadow switch.
- Mixed workload: source writers + direct/L1/L2 reads + consumers + EVT; не отдельный тихий benchmark аналитики.
- G2: реальный OIDC/REST/UI по отдельному gate; G3: owner-signed UAT/restore; G4: release.

1k/10k/100k source fixture sizes унаследованы; dense/sparse, hot SKU, long history, tenant skew и backlog фиксируются заранее. Сохраняются existing Radar p95<=200ms и TASK degradation<=10%. Новые INT/EVT budgets OPEN: raw baseline/mixed samples, p50/p95/p99, throughput, actual temporal overlap, CPU/IO/GC/locks, SQL plans. Не выбирать удобный threshold после замеров.

## 5. Что проверено в этой документационной итерации
Проверяется только целостность пакета: уникальность IDs, сохранение 22/8/88, links и actual source/test symbols, 32/48 DAG и отсутствие циклов, корректность reference arithmetic, только docs diff, source SHA и optimistic branch update.
Это static document validation. Runtime assertions в матрице не выполнены. Полная проверка source producers/DDL/миграций остаётся IE-00B, доступным только после Q1.

## 6. Уточнение по свежему CORE-RET-04 review
После source inspection появился [E7A normative review](https://github.com/alimbetov/RM/blob/d1019e6acdb04a5c9a03da79d0f9232649723ead/docs/reviews/RM-BSR-CORE-RET-04-E7A-NORMATIVE-REVIEW.md). Для R2-S14/S16/S37 явно включить actor A-only/B-only/A+B с разными правами в двух tenant, независимо изолированное inactive workspace, fixture prerequisites/isolation/interleaving/source metadata и independent reviewer verdict. Сохранить SUPPORTING_PARTIAL, пока текущий CORE gate не доказан; существующая fixture не означает end-to-end onboarding или external IdP acceptance.

## 7. Детализация будущей квалификации Radar

R2-S40 сохраняется как зонтичный сценарий. [Карта адаптации Radar](RADAR-ADAPTATION-TRACE-R2.json) связывает 48 BV семейств с этапами и конкретными предложенными проверками. Набор применимых проверок выбирается на допущенный provider. Отдельные условия действуют для модели, EVT, Advisor, UI и пилота. Сценарии имеют статус DEFERRED_ANALYTICS_FIRST.

## Обязательное дополнение MIC-R1 (2026-11-26)
[RM Mathematical Integrity Contract R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md). MIC-Q01–Q10 обязательны по применимости; дополнительно один SKU на трёх складах и двух областях = 1 SKU, 3 позиции; два offers одного SKU = 1 SKU; одинаковый код разных tenants не объединяется; неизвестный SKU даёт неполное coverage. Не считать прежние тесты доказательством новых требований.
