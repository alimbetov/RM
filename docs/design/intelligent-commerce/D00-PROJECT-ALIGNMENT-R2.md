# D00 — Сверка Intelligent Commerce R2 с текущим RM
Редакция: R2.0-draft · 2026-11-25. Статус: SOURCE REVIEW / DESIGN ONLY. Владелец проекта: Ruslan Alimbetov; техническое утверждение новых решений OPEN.

## 1. Снимок и границы доказательств
- Проверенный base_dev: d9242e539a07ef1843c0e72aba9c651204bf42ae.
- Начальный HEAD design/intelligent-commerce-r2: f5c86672e36dabb1445265c9276195d844e1a1f9; общий предок: 271c5a9a57dd969a806bfd36f95ee9bde29559fc.
- main: 541b9a808e02d0178863162c47fb33c3ccd90f34.
- [PR #9](https://github.com/alimbetov/RM/pull/9) при чтении 2026-11-25 открыт, merged=false, head совпадает с проверенным base_dev.
- Снимок checks около 2026-11-25T06:46Z: 45 записей, success=42, failure=1, skipped=2. Ошибка [exact-sha-manifest](https://github.com/alimbetov/RM/actions/runs/45559438747/job/139019316618). Этот подсчёт не определяет обязательность checks, не доказывает Q0 и не объясняет причину ошибки.
- Сверены исходники сервисов, объявления Liquibase, существующие тестовые методы и нормативные документы. PostgreSQL, миграции, Java-тесты и нагрузка в этой итерации не запускались. Фактический каталог мигрированной БД и полнота каждого producer остаются IE-00B.
- Указание метода теста означает существующее доказательство в исходном коде, а не PASS на данном SHA. Перечень прочитанных файлов и blob SHA: [traceability](TRACEABILITY-R2.json).

После общего предка base_dev имеет два дополнительных коммита, design — один. Изменения base_dev: RET04 checkout provenance verifier и его тесты, global artifact verifier, QR07 workflow, CORE evidence JSON и QrWarehouseHandoverQualificationTest. В этом delta нет изменений src/main или производственной схемы. Дизайн сверяется с новым SHA без переноса чужих коммитов и без merge.

## 2. Иерархия документов
[Charter](README.md), [Master R1.1](../../spec/RM-INTELLIGENT-COMMERCE-MASTER-R1.md), [DAG R1.1](../../spec/RM-INTELLIGENT-COMMERCE-EXECUTION-DAG-R1.md), Platform Standard и доменные контракты остаются нормативными. R2 конкретизирует проект и содержит предложения; не заменяет утверждённые контракты. Новые числовые параметры, схема хранения, grants и методики не становятся утверждёнными от публикации текста.

Сохраняются 22 SRC-группы, 8 основных кандидатов метрик, 32 IE-Q + 48 BV + 8 MC = 88 семейств плановой приёмки, historical DAG R1.1 32 nodes / 48 edges (current R1.2: 33/54). Это не число выполненных тестов. RAD/CAL, F2R, BSR и owner regressions не прибавляются к 88.

## 3. Проверенные связи с кодом
Все ссылки ниже закреплены за проверенным base_dev SHA.

| ID | Наблюдение | Следствие для R2 |
|---|---|---|
| ALIGN-01 | [pom.xml](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/pom.xml): Java 21, Spring Boot 4.1.3, JDBC/JPA, Liquibase, PostgreSQL/H2 | Использовать текущий модульный монолит; не переносить стек другого проекта или FIN runtime |
| ALIGN-02 | [DomainModule](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/architecture/DomainModule.java) содержит 15 контекстов без INTELLIGENCE/EVT; [DomainPermission](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/iam/DomainPermission.java) не содержит их management grants | Нужен ADR ownership/package/prefix и явные права; новые имена пока conceptual |
| ALIGN-03 | [AutoOrderService](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/autoorder/AutoOrderService.java#L243) записывает order/lines/PLACED; accepted quote имеет отдельный producer около L450 | Квалифицировать оба пути, учитывать факт PLACED, а не текущий статус заказа |
| ALIGN-04 | [SalesInventoryCommandService](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/sales/SalesInventoryCommandService.java) и SalesPickupHandoverService создают COMPLETED по различным допустимым путям | Один смысл M-S04; разные owner fixtures, отсутствие повторного COMPLETED |
| ALIGN-05 | [CrmBuyerLinkService.orderHistory](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/crm/CrmBuyerLinkService.java#L143) проверяет CRM_READ + SALES_ORDER_READ, CONFIRMED link, ACTIVE customer и buyer с блокировкой аккаунта | Customer 360 не может раскрывать предварительно рассчитанные суммы после отзыва связи |
| ALIGN-06 | [InventoryHistoryQueryService](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/inventory/InventoryHistoryQueryService.java#L72) фильтрует recorded_at; StockBalanceRepository считает available=on_hand-reserved | Отдельно объявлять временную основу; текущая доступность читается из владельца |
| ALIGN-07 | [InventoryPostingVersion](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/inventory/InventoryPostingVersion.java) использует версию позиции; reserve/release создают допустимые разрывы | posting_version не глобальный watermark, пропуск номера не означает потерянное движение |
| ALIGN-08 | [SalesReturnPhysicalService.post](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/sales/SalesReturnPhysicalService.java#L160): RESTOCK → обычное движение, QUARANTINE → отдельный ledger, WRITE_OFF → без повторного движения обычного остатка | Возврат после выдачи нельзя списать из sellable stock второй раз; считать реально записанное движение |
| ALIGN-09 | [SalesReturnQuarantineResolutionService](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/sales/SalesReturnQuarantineResolutionService.java) различает RESTOCK и DISPOSE | Последующее освобождение карантина даёт один приход; исходный quarantine receipt остаётся историей |
| ALIGN-10 | [OrderPackagingService](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/sales/OrderPackagingService.java) пишет отдельные service line/event/receipt | M-S02 не включает упаковочные услуги автоматически |
| ALIGN-11 | [ContractExpiryPolicy](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/crm/ContractExpiryPolicy.java) использует inclusive endsOn и warningDays 0..365; TaskTimingQueryService использует TaskTimingEngine | Не вводить вторую классификацию просрочки и persisted EXPIRED |
| ALIGN-12 | [OidcJwtConfiguration](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/iam/OidcJwtConfiguration.java) проверяет issuer/audience/срок и назначение Bearer; VerifiedActor не выдаёт RBAC автоматически | Новые публичные сервисные границы принимают VerifiedActor и выполняют актуальную авторизацию |
| ALIGN-13 | [SalesAuthenticatedCommandController](https://github.com/alimbetov/RM/blob/d9242e539a07ef1843c0e72aba9c651204bf42ae/src/main/java/kz/rm/sales/SalesAuthenticatedCommandController.java) уже существует | Нельзя утверждать, что в репозитории вообще нет контроллеров. R2 сейчас проектирует сервисы; новые REST/UI не добавляются |
| ALIGN-14 | Liquibase sales_order_status_event имеет id и occurred_at, без доказанной aggregate version; первичный DDL не задаёт уникальность order+event_type | Нельзя выводить exactly-once из наличия таблицы. Проверять cardinality, atomicity и legacy отдельно |

## 4. Полнота повторного просмотра
SRC-01…04,06…10,12,14,16,17: просмотрены соответствующие ключевые реализации и/или запросы, не полный аудит всех путей записи.
SRC-05,11,13,15,18…22: сохранены из IE-00A, проверено наличие объявлений/путей в снимке; полная повторная квалификация владельца не выполнена. Наличие DDL не означает готовность платежей, оценки себестоимости или отправки сообщений.
Полный исходный словарь 22 групп остаётся в [IE-00A](../../reviews/RM-INTELLIGENCE-IE-00A-SOURCE-CONTRACT-INVENTORY.md); машинный реестр сохраняет его ID и текст, не подменяет базовую схему.

## 5. Пробелы и маршрутизация
| Пробел | Что требуется | Gate |
|---|---|---|
| Реальная схема и историческое покрытие не измерены | Clean/populated migration, schema manifest, источники и cardinality | A1 |
| Не доказана неизменяемость всех сумм/полнота событий | Producer matrix на каждый checkout/status path; rollback/duplicate/legacy fixtures | A1/B2 |
| Нет утверждённого tenant calendar | Версионированная IANA zone и правила смены календаря | A2/B1 |
| Не определена универсальная UOM | Первая версия quantities по SKU; расширение только после owner contract | A2/W3 |
| Привязка booked demand к складу может отсутствовать до confirm | Tenant ABC либо доказанный historical warehouse attribution | A2/W3 |
| Нет согласованного general EVT owner/grants | ADR и deny-by-default management/worker identity | A2/E1 |
| Нет доказанного commit-safe incremental feed | Durable outbox/receipts, snapshot backfill и ловля late commits | B2/B3 |
| Новые performance budgets не утверждены | Зафиксировать нагрузку, лимиты и критерии до измерений | A2/X1 |

Q0/Q1 не закрыты этим документом. Условие verified core merge перед IE-00B сохраняется. Исторические FAIL/PASS из R1.1 не выдаются за актуальные результаты. Исключение C3 остаётся узким: manual-only для core merge, C2 обязателен; возврат push и прежние 200 ms/10% обязательны перед возобновлением Radar/production.

## 6. Повторная сверка перед сохранением
Во время проектирования base_dev продвинулся до `d1019e6acdb04a5c9a03da79d0f9232649723ead` ещё на два коммита. Проверен compare от `d9242e539a07ef1843c0e72aba9c651204bf42ae`: изменены RET04 E6C verifier/self-tests/global verifier, CORE evidence JSON, VerifiedBuyerEmailFixture, QrWarehouseHandoverQualificationTest; добавлен [CORE-RET-04 E7A review](https://github.com/alimbetov/RM/blob/d1019e6acdb04a5c9a03da79d0f9232649723ead/docs/reviews/RM-BSR-CORE-RET-04-E7A-NORMATIVE-REVIEW.md). Изменений src/main и производственного DDL в этом delta нет.
Поэтому семантический анализ сохраняет закреплённый source SHA; test reuse ссылки указывают именно на него. Новые E7A доказательства не объявляются выполненными этим R2. Review отдельно оставляет OPEN asymmetric A/B/AB grants, inactive workspace и variant manifest/independent sign-off. Эти варианты обязательны в R2-S14/S16/S37. CI snapshot §1 относится только к старому SHA и не описывает статус нового HEAD. Перед runtime admission снова проверить актуальный HEAD/CI.

## Final technical reconciliation — 2026-11-26
Current implementation authority exclusively base_dev @ a6bf99107aa64f4eb255b4997ef9417748b05607. Earlier links and test observations are historical, not current gaps. DomainModule registers 17 contexts including INTELLIGENCE/int/kz.rm.intelligence and EVT/evt/kz.rm.evt. DomainPermission lacks specialized INT/EVT management grants. A2 all 15 original decisions owner-approved, A3-DEV approved, A3-QUAL pending. Operative DAG R1.2 33 nodes/54 edges, S0–S6 roadmap, no restart B1–B4.
