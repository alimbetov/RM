# RM-SERVICE-12-PASS-REVIEW-PROTOCOL-R1 — циклическое глубокое ревью одного сервиса

**Статус:** единый шаблон проектного аудита. **Ветка:** `base_dev`. **Режим:** один домен за раз; после 12 проходов — одно предметное ТЗ, затем реализация малыми квалифицированными инкрементами, повторная проверка и только после этого следующий домен.

## Принцип

Не считать сервис зрелым по количеству \`@Service\`, методам CRUD, отсутствию \`TODO\` или `CI=SUCCESS`. Для каждого пользовательского сценария нужны наблюдаемый эффект, его потребитель, права, состояние, обратная связь, устойчивость к отказам и тест. Зафиксировать *source SHA*; не домысливать текущий код по старым ТЗ. Ни один проход не требует создавать новый класс или изменять архитектуру без gap-обоснования.

## Двенадцать проходов

| Pass | Контрольный вопрос | Обязательное доказательство |
|---:|---|---|
| 01 | Что пользователь реально делает и где заканчивается операция? | Минимальный user journey от входа до чтения результата |
| 02 | Какие методы/сервисы реально исполняются? Есть заглушки/пустые, ложные настройки, мёртвые ветви? | Метод → SQL/вызовы → результат; нулевая ценность обнаружена/опровергнута |
| 03 | Соответствуют ли aggregate/schema/JPA/JDBC и tenant FK предмету? | Таблицы, индексы, инварианты, stale schema, populated migration |
| 04 | Все ли необходимые команды существуют и выполняют работу? | Typed commands, validation, legal transitions, write audit |
| 05 | Можно ли безопасно читать результат, искать и просматривать историю? | Query/get/list/count/filter/pagination, PII, stable sort |
| 06 | Правильны ли транзакции, idempotency и бизнес-состояния? | Failure injection, race/rollback, immutable evidence |
| 07 | Имеют ли настройки/флаги реального потребителя? | Set value → observe changed behavior E2E или убрать как действующую capability |
| 08 | Согласованы ли соседние домены и владельцы данных? | Cross-domain matrix provider/consumer, FK, immutable snapshot |
| 09 | Правильно ли соблюдаются principal, tenant, permissions и per-object policy? | VerifiedActor, negative user A/B/AB, revoked membership/workspace |
| 10 | Выдерживает ли логика нагрузку, повтор и частичный отказ? | PostgreSQL contention, retry correctness, recovery, no invented p95 |
| 11 | Какие учётные/финансовые/юридические инварианты нужны и какие ещё НЕ реализованы? | Truthful feature boundary, evidence, no fake accounting/PII |
| 12 | Что делает задачу завершённой? | Ordered backlog, minimal method contracts, test IDs, release gate, owner decisions |

## Артефакты каждого цикла

1. Обновить раздел исходного `RM-SVC-FUNCTIONAL-DEPTH-AUDIT-2026-11-22.md` ссылкой на специфический аудит, не приписывая другим доменам квалификацию.
2. Отдельное \`docs/spec/RM-<DOMAIN>-FUNCTIONAL-COMPLETION-R1.md\`: 12 проходов, исходный код, gap severity, минимальные контракты, междоменные связи, DDL plan, тестовая матрица, статус решений.
3. Реализация только после согласования спорных предметных правил, **в той же \`base_dev\`**, без новых контроллеров/микросервисов/main merge. Для каждого small increment: source SHA, CI, negative PostgreSQL tests и фактический DoD.
4. Второй source-level контроль на реализованном SHA обновляет статусы 12 проходов. Если \`PARTIAL\`, следующий домен не помечать завершённым; можно выделить зависимый backlog.
5. Порядок доменов диктуют реальные user journeys и зависимости, а не алфавит/«закрытие всех CRUD колонок».

## Разрешённые состояния

\`DECLARED\` (тип/DDL) → \`IMPLEMENTED\` (эффект в коде) → \`TESTED\` (positive/negative) → \`QUALIFIED\` (PostgreSQL/full owner/tenant/migration matrix) → \`ADMITTED\` (внешняя эксплуатационная безопасность). \`HOLD\` — для заранее отключённых функций. Нельзя задним числом объявить \`QUALIFIED\` из старого CI другого SHA.

## Текущий цикл

**01 INVENTORY:** \`RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md\` (12 проходов, узкие Q0–Q9 и отдельный INV-VAL). После завершения этого сервиса — рассмотреть SALES, потому что он потребляет reserve/fulfill и offer mapping.
