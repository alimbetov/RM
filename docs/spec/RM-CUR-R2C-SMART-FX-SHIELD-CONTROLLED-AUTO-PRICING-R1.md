# RM-CUR-R2C — Smart FX Shield: Controlled Auto Pricing
## Техническое задание R1.0 — массовая автоматическая корректировка цен в KZT по заранее утверждённой валютной политике

**Дата:** 2026-11-24  
**Репозиторий:** alimbetov/RM, **ветка:** base_dev  
**Source baseline для анализа:** d12176ff4bacda7c2279f77ddea53c8e6788c27b  
**Статус:** DESIGN SPEC / POST-MVP PARKED / IMPLEMENTATION NOT AUTHORIZED / PRODUCTION HOLD  
**Приоритет:** коммерческая функциональность после пилотного MVP. ТЗ можно анализировать и согласовывать сейчас; не запускать его реализацию без отдельного scope-change решения после MVP G3.  
**Входящие продукты:** RM Business — главный владелец инструмента продавца; RM QR Commerce — потребитель только опубликованных KZT-цен.  

**Ссылки на нормативные документы:** 
- [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md) — архитектура, tenant, IAM, границы данных;
- [RM-MVP-CLOSURE-FREEZE-R1](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) — FX_AUTO и advanced FX по умолчанию за пределами MVP;
- [RM-FX-PRICING-R1](RM-FX-PRICING-R1-SMART-FX-SELLING-PRICE.md) — валютная модель и формулы;
- [CUR-R2A](RM-CUR-R2A-IMMUTABLE-SKU-FX-APPROVAL.md) — FX-anchor, immutable calculation, manual seller decision;
- [CUR-R2B](RM-CUR-R2B-SELLER-FX-PRICING-GUARDRAILS.md) — guardrails, TTL, офлайн/онлайн календарная квалификация;
- [CUR-R1C](RM-CUR-R1C-COMMERCE-ADMISSION.md) — публичные цены и KZT-only;
- [CURRENCY-R1](RM-CURRENCY-R1-TENANT-MARKET-CURRENCY-POLICY.md) — tenant currency purposes;
- [DEC-FUL-01](../architecture/RM-DEC-FUL-01-EXECUTION-CHANNEL-R1.md) — заказы/выдача не пересчитываются при изменении FX.

## 0. Executive decision, anti-scope-creep

**Бизнес-задача:** продавец заранее согласовывает ограниченную политику, связывающую цены с USD/EUR/CNY. Когда подтверждённый курс меняется, RM пересчитывает и при выполнении ВСЕХ ограничений публикует новую цену **только в KZT**, без повторного ручного подтверждения **каждой** позиции. Можно выбрать все допустимые позиции, только товары, только услуги или явный набор отдельных позиций. Исключения всегда доступны.

**Решение о границе разработки:** использовать существующие NBRK rate snapshots, SkuFxCandidateCalculator, SkuFxSellerApprovalService/CUR-R2B guardrails, TenantCurrencyPolicyService и *канонический owner коммерческой цены*. Не строить самостоятельный ERP price engine, альтернативную таблицу публичных цен, валютный checkout, собственную бухгалтерию, Kafka, Redis, ML/AI или новую микросервисную архитектуру.

**Предусловие запуска:** до MVP завершить ранее утверждённый G0–G4. Текущий документ НЕ меняет MVP-фриз, не разрешает REST/UI, не вводит новые валюты оплаты и не позволяет запускать scheduler. До отдельного решения всё остаётся OFF, без ретроактивной обработки исторического каталога.

**Важно:** FX Shield — защита *ценовой политики/торговой маржи*, не банковское или финансовое хеджирование; реальная себестоимость, COGS, оплата и возврат денег остаются вне этого контура.

## 1. Проверенная исходная реализация и фактические gaps

| Составная часть | Реально существует на source baseline | Граница |
|---|---|---|
| Каталог | CatalogItem.Kind = PRODUCT / SERVICE; CatalogItemService хранит SKU, публикацию, ревизии cat_price | Не все SERVICE являются продаваемыми публичными предложениями |
| Цена товара в витрине | StorefrontOfferService.updatePrice / approveSuggestedKzt; store_offer_price_revision append-only, rowVersion CAS | validateCatalogProduct разрешает только PRODUCT; автоматический trusted port отсутствует |
| Валютный якорь | crm.sku_fx_policy_version: tenant+offer+revision, USD/EUR/CNY, anchor, buffer, markup, rounding | Политика существует на offer, не автоматически на всех SKU или услугах |
| Расчёт | SkuFxCandidateCalculator.preview, BigDecimal, курс/nominal, округление вверх | Это candidate, не публичная цена |
| Seller decision | SkuFxSellerApprovalService.calculate/decide, crm.sku_fx_calculation, crm.sku_fx_seller_decision | decide привязан к VerifiedActor продавца и ручному approve/reject |
| Защитные лимиты | crm.sku_fx_guardrail_version: maxIncrease, maxDecrease, min/max KZT, freshness, TTL | Нет массовой наследуемой политики, cumulative window и cooldown |
| Курсы | NBRK importer, immutable rate snapshots/source hashes; MOCK отделён | Нет утверждённого unattended rate scheduler; synthetic/неаттестованные данные запрещены для AUTO |
| Календарь | Транспорт/стейджинг источников, manifest/seal, опциональные tenant gates | Независимое подтверждение всех KASE date/status notices не завершено; production HOLD |
| Публичный checkout | CUR-R1C gate при включении, KZT offer + immutable SALES snapshot | Не выполнять FX на checkout и не менять принятый заказ |
| Услуги | Catalog SERVICE; отдельный PackagingServiceCatalogService с собственными версиями цены | Не существует общего generic SERVICE pricing port, не обходить SALES owner |
| Batch / worker | Для FX_AUTO отсутствуют | Нужны tenant opt-in, durable work intent, execution lease/fencing, bounded retry |
| UI / API | Автоматическое ценообразование не имеет пользовательского кабинета | Post-MVP отдельная стадия |

**Исходная миграционная база:** Liquibase master уже содержит CUR 0129–0139 и TASK changesets вплоть до 0153. Нельзя переписывать применённые миграции; при реализации брать следующий свободный changeset из фактического HEAD, не фиксировать номер заранее.

**Противоречие старых ТЗ:** RM-FX-PRICING-R1 говорит о будущем PRICING_ANCHOR, а текущая R2A проверяет SALES_INTERNAL. Не менять смысл существующих разрешений молча. Решение об отдельном currency purpose PRICING_ANCHOR — обязательное архитектурное согласование до AUTO-activation; до согласования AUTO fail closed.

## 2. Пользовательские сценарии (business acceptance)

| ID | Актор и действие | Ожидаемый бизнес-результат |
|---|---|---|
| FXA-J01 | Owner выбирает «Все доступные для автоцен товары» | Предпросмотр количества подходящих PRODUCT offers и исключений; реальный opt-in только после утверждения |
| FXA-J02 | Owner задаёт политики для USD/EUR/CNY, буфер, наценку, округление, лимиты | Immutable revision + субъект утверждения + effective window; активируется только на допустимых позициях |
| FXA-J03 | Owner выбирает лишь одну группу или список SKU/offer | Не затронуты остальные tenant/группы; двусмысленная категория не выводится из названия |
| FXA-J04 | Продавец исключает отдельный offer или вручную меняет цену | AUTO перестаёт перезаписывать исключённый или вручную переопределённый offer до нового осознанного opt-in |
| FXA-J05 | Доверенный NBRK importer зафиксировал новую действующую ревизию курса | Для допущенных позиций создаются воспроизводимые candidate/eligibility решения |
| FXA-J06 | Candidate внутри всех диапазонов; нет конфликта с торгом/checkout | Ровно одна canonical KZT price revision без повторного seller click |
| FXA-J07 | Candidate выше/ниже лимитов или резко изменился курс | Публичная цена сохраняется; reason=BLOCKED и review queue для продавца |
| FXA-J08 | Часть из 10 000 позиций отклонена, часть прошла | Успешные позиции применены независимо, запрещённые не изменились; общий отчёт по batch |
| FXA-J09 | Сотрудник меняет настройки, владелец выключает AUTO или снимает разрешение | Не допускаются новые публикации после commit; in-flight операция разрешается только при правильном lock ordering |
| FXA-J10 | Неподтверждённый/просроченный курс, нет календарного attestation, падение upstream | Fail closed: сохранить старую KZT цену, не генерировать произвольный курс |
| FXA-J11 | Покупатель смотрит товар во время FX-публикации/оформляет заказ | Согласованное прочтение одной опубликованной версии, запрет stale acceptance, immutable order/quote |
| FXA-J12 | Компания имеет SERVICE и packaging service | Не менять услугу до квалификации её владельца цены; после подключения отдельного adapter — тот же safe policy/worker, собственная история |
| FXA-J13 | Клиент повторяет batch или worker падает после commit | Второго изменения по тому же rate/policy event нет, stable receipt и recovery |
| FXA-J14 | Продавец смотрит историю изменений цены | Видит old/new KZT, дату/версию курса, формулу, правило, согласовавшего policy owner, системного исполнителя, результат и причины исключений |
| FXA-J15 | Новая позиция добавлена после «применить ко всем» | По умолчанию НЕ включается автоматически; включение будущих позиций требует отдельного явно утверждённого includeFuture правила |

## 3. Область действия и приоритет правил

**Target types:**
- PRODUCT_OFFER — первый технически разрешимый тип: только PUBLISHED KZT store_offer, связанный с eligible PRODUCT. Один и тот же SKU в разных offers получает независимую опубликованную цену, нельзя изменять cat_price напрямую как обход.
- GENERIC_SERVICE — второй этап только после утверждения явного contract владельца коммерческой цены, service-publication и реализации service checkout; наличие CatalogItem.Kind.SERVICE само по себе недостаточно.
- PACKAGING_SERVICE — отдельно типизированный SALES owner: PackagingServiceCatalogService с versioned unit_price/currency. Не менять через StorefrontOfferService и не подменять unit_price в уже принятых service snapshots. Подключать только после отдельной проверки semantics/tax/version/release.
- UNKNOWN/UNSUPPORTED — BLOCKED_UNSUPPORTED_TARGET, никаких записей о ценах.

**Bulk selector:** tenant-wide eligible set, PRODUCT_ONLY, SERVICE_ONLY, explicit offer/service IDs, опубликованная категория ТОЛЬКО если существует каноническое tenant-owned membership и её ACL (на текущем baseline отдельной обязательной category→offer relation не доказано). Никакой выборки по нечёткому названию SKU, поисковым словам или email.

**Приоритет эффективного решения:** explicit EXCLUDE / MANUAL_OVERRIDE > offer-specific signed policy > approved group policy > tenant default policy. Продуктовый scope и merchant consent обязательны одновременно: tenant-wide правило не создаёт разрешения на чужую организацию или неподдерживаемый target. Более локальное правило не может расширить абсолютный hard safety ceiling без нового owner approval; при пересечении лимитов брать более строгую границу. Взаимоисключающие правила => BLOCKED_CONFLICT, а не случайный winner.

**Массовое согласие:** owner может утвердить один bulk-enrollment manifest (tenant, exact target IDs или правила селекции, effectiveAt, policy SHA, maxImpact, issuer), без тысяч индивидуальных кликов. New targets: includeFuture=false по умолчанию; если включено, UI обязан явно объяснить влияние на будущие позиции и разрешить узкое ограничение по типу/группе. Определение целевого набора для каждой batch фиксируется manifest/digest и не становится «всей БД после изменения фильтра».

## 4. Режимы и state machine

**Режимы:** MANUAL; FX_SUGGESTED (R2A/R2B existing); FX_AUTO_BOUNDED (новый, OFF default); DRY_RUN (test preview, NO PUBLIC MUTATION).

**Policy lifecycle:** DRAFT → APPROVED → ACTIVE → PAUSED/RETIRED. Параметры approved revisions immutable; изменение означает новую revision + отдельное activation событие с CAS. Не обновлять опубликованную ревизию «на месте».

**Target execution result (не PRICE state):** ELIGIBLE → CALCULATED → WOULD_UPDATE/NO_CHANGE/BLOCKED; при живом AUTO: CALCULATED → PUBLISHED или BLOCKED/RETRYABLE/DEAD_LETTER. Это отдельная запись результата FX job, не прямое изменение состояния SALES order, SKU или CRM TASK.

**Операционное правило:** при любой ошибке вычисления/авторизации/состояния источника не снимать ранее действующую опубликованную KZT-цену и не блокировать выдачу уже оформленных заказов; при юридическом currency admission HOLD новые публичные операции блокируются существующим CUR-R1C gate независимо от FX_AUTO.

## 5. Формула и математические инварианты

Для официального курса rateKzt за nominal единиц anchorCurrency:

convertedKzt = anchorAmount × rateKzt / nominal  
bufferedKzt = convertedKzt × (1 + bufferPct / 100)  
unroundedKzt = bufferedKzt × (1 + markupPct / 100)  
candidateKzt = CEILING(unroundedKzt / roundingStepKzt) × roundingStepKzt

Это ровно формула существующего SkuFxCandidateCalculator; повторно не реализовывать и не менять rounding без отдельного versioned methodology decision. Все деньги и проценты — BigDecimal, числовые DB limits, без double/float.

**Синтетический эталон:** 100 USD × 500 KZT/USD × 1.05 × 1.10 = 57 750 KZT; roundingStep=100 => **57 800 KZT**. Это НЕ текущий реальный курс. Для CNY/другой валюты с nominal=10 нельзя забывать деление на номинал.

**Обязательные guardrails, все одновременно:**
1. Разрешённый anchor currency и purpose; KZ public currency строго KZT.
2. Approved FX policy revision + signed tenant opt-in + explicit target enrollment, ACTIVE tenant/offer + current delegated authority.
3. Verified official source NBRK, immutable sourceRevision, SHA, effective date, корректный nominal; НЕТ fallback на MOCK/kurs24 при ошибке.
4. Freshness, TTL и calendar evidence (при включённом calendar gate не допускается отсутствие seal); production AUTO невозможен до независимой квалификации источника KASE/даты/исключений.
5. maxIncreasePct / maxDecreasePct от PRE-COMMIT текущей канонической цены с включённой округлённой candidate.
6. minPriceKzt / maxPriceKzt (после округления).
7. maxCumulativeChangePct или абсолютная сумма **за утверждённое окно**, рассчитанная от frozen baseline окна; запрет последовательного обхода лимита множеством малых шагов. Никакого reset rolling window одним reboot или новой job.
8. minChangePct/Amount для защиты от дрожания цен; cooldownSeconds от последней AUTO-публикации.
9. maxOffersPerRun, maxPublicPriceChangeValue / batch impact limits; новые численные пороги не задавать скрытыми default.
10. Запрет на active bargaining/AVAILABLE accepted quote, конфликт с manual edit, PAUSED/HIDDEN offer, устаревший version, revoke или неподтверждённое policy state.
11. No clamping: вне диапазона => BLOCKED (оставить текущую цену), не «обрезать» без согласия продавца.
12. Повтор того же официального rate revision / signed policy / target => NO_CHANGE или exact replay, без повторного накопительного удорожания.

**Окно накопленного изменения:** согласовать fixed business-day UTC or Asia/Almaty и момент открытия baseline по tenant; отдельно покрыть переход даты/временной зоны. Нельзя задавать «за день» с неуказанной точкой отсчёта. Значения hard limits — конфигурация продавца, подтверждённая owner, а не обещанные значения «+5%/-3%».

## 6. Security & consent contract

- **Tenant policy activation:** только утверждённый active organization OWNER / TENANT_RBAC_MANAGE (с учетом не-делегируемой политики). Утверждение должно привязываться к immutable policy version, exact target scope, start date, source preference и максимальному объёму финансового воздействия. Отдельное согласие, нельзя трактовать прежнее FX_SUGGESTED как opt-in в FX_AUTO.
- **Target-level edits:** продавец с STOREFRONT_OFFER_MANAGE может сузить/выключить допустимую автоматизацию своих offers; не вправе расширить tenant-wide ceiling или включить глобальный AUTO без owner.
- **Worker identity:** отдельная доверенная внутренняя identity/service capability, не подложный VerifiedActor продавца, не обычный user UUID из входного JSON и не глобальный tenant суперпользователь. В момент commit проверить текущую активацию/disable, tenant, разрешения и объект. Если business owner/approving authority отозван, применяется консервативный HOLD до повторного утверждения соответствующей организации (решение может быть ужесточено security review).
- **Price history:** сейчас crm.store_offer_price_revision.actor_user_id обязателен и описывает человека. Нельзя записывать туда UUID системного worker и выдавать его за продавца. При реализации расширить происхождение записи типом MANUAL vs FX_AUTO, ссылкой на policyAuthorizer и отдельной системной execution identity, сохранив имеющиеся manual API/историю и FK. Согласовать миграцию provenance, если actor_user_id больше не подходит по семантике; только потом разрешать автоматические price writes.
- **Service boundaries:** отдельный trusted internal price mutation port, использующий ТОТ ЖЕ transactional CAS, price_revision, currency checks, audit и lock order, что текущий StorefrontOfferService; Worker не должен напрямую UPDATE crm.store_offer, crm.cat_price или packaging tables в обход доменного owner.
- **PII/security:** в FX policy/jobs/logs только tenant, target IDs, rate IDs, amount/version/decision codes; ни персональных данных клиентов, ни bearer tokens, ни сырых HTML/API секретов, ни buyer address.
- **Emergency kill switch:** global executor OFF, per-tenant OFF, per-policy OFF, per-target exclude; после подтверждения PAUSE новых публикаций быть не должно. При неподтверждённой идентичности или source gate система fail closed.

## 7. Transaction boundaries, locking и конкурентные операции

**Importer** отдельно, вне commercial transaction: HTTPS fetch → проверка trusted origin/parse/date/nominal → immutable official rate/import run commit. Никаких сетевых запросов внутри пересчёта/изменения цены.

**Worker** делает один target за одну короткую транзакцию. Перед price write повторно:
- читает official rate as-of latest valid revision под правильным provider lock и удостоверяется, что source не superseded;
- проверяет tenant currency admission, approved policy activation, actor consent/revoke, target opt-in и guardrails;
- сериализует с storefront price update, negotiation, checkout и при необходимости SALES packaging version owner;
- читает актуальный offer row_version/current KZT, повторно вычисляет candidate относительно утверждённого anchor;
- проверяет все лимиты и только затем создаёт каноническую price revision + immutable FX execution receipt атомарно.

**Lock DAG:** проектируется по реальным вызовам R2A/R2B/Storefront/SALES/ORG и подтверждается двухтранзакционными PostgreSQL race tests. Не заявлять «проверенный порядок» только по комментариям. По возможности сохранить порядок existing IAM admission → currency policy → NBRK snapshot/provider lock → store_front → offer → authoritative price mutation, но разрешить любые конфликты порядка до кода. Взаимодействие correction importer с seller approval особенно важно.

**Checkout:**
- если checkout линейризовался ДО FX price commit, immutable order/quote остаётся на старой согласованной цене;
- если FX commit произошёл ДО checkout, покупатель должен видеть новую price revision и подтвердить её (stale acknowledgement отклоняется);
- уже согласованная active quote / price proposal не переписывается и блокирует AUTO target до разрешения состояния;
- QR publicRef неизменен, только текущая опубликованная KZT цена меняется с версией; не генерировать новый QR при каждом курсе;
- stock, reservation, fulfillment, return и уже оформленная SALES history FX не меняет.

**Manual override:** если seller вручную изменил цену между candidate и publish, AUTO не должен перезаписать её. Состояние override должно сохраняться/проецироваться из canonical price revision provenance и требовать явного повторного включения либо новой approval revision, а не маскироваться простым retry.

## 8. Durable batch execution, crash safety и масштаб

**No new microservice.** Spring Boot modular monolith + PostgreSQL worker foundation.

**Run identity:** tenantId + policyActivationRevision + officialRateSnapshotId (+ sourceRevision) + target set manifest digest + run purpose (DRY_RUN/AUTO). Unique semantic run key, повтор того же run возвращает тот же durable receipt.

**Target identity:** unique (tenant, runId, targetType, targetId) и dedupe исходного rate/policy для автоматической публикации. Сохранённый run не должен повторно публиковать target только из-за изменённого offer row_version после его собственного коммита; policy revision / rate revision и первоначальный effective target фиксируются. Новая rate correction — новый rate snapshot/source revision и новый отдельно проверяемый run.

**Claim / lease / fencing:** bounded claim с PostgreSQL FOR UPDATE SKIP LOCKED; commit lease до обработки; workerId + monotonic leaseGeneration + expiresAt, CAS на owner/generation/live lease; stale worker не может завершать чужой work item. Краткий retry с backoff после транзиентных ошибок, typed dead-letter после бюджета попыток; неверная цена/политика/consent не retriable автоматически. В один момент максимум один successful price commit по target/event, точное покрытие транзакционных окон.

**Run accounting:** counts total/eligible/updated/noChange/blocked/retry/deadLetter, signed manifest digest, validated sourceSha, snapshot policy/guardrail revisions. Batch может быть PARTIAL_SUCCESS; ошибки конкретного товара не откатывают успешные независимые товары. Никакого «ALL SUCCESS» при необработанных элементах.

**Rate correction / crash:** before commit — ни одной новых ценовых ревизий; after price commit but before ACK — восстановление идемпотентно возвращает persisted receipt, а не второй price write. После восстановления БД PITR engine должен подтвердить provenance и resume без переписывания immutable history.

**Нагрузочные параметры (не коммерческие promises):** сначала синтетические 0/1/100/10 000 targets, rate burst 2–5 revisions, две параллельные job-попытки и конкурирующие продавцы; реальные max batch/concurrency/p95/p99/DB connection budget устанавливать после измерений. Для одного target не держать долгую сетевую блокировку, для tenant store_front lock не выполнять гигантский batch в одной транзакции.

## 9. Предлагаемая additive SQL модель (не внедрена)

**Reuse unchanged:** crm.sku_fx_policy_version, crm.sku_fx_guardrail_version, crm.sku_fx_calculation, crm.sku_fx_seller_decision, crm.fx_official_rate_snapshot, crm.store_offer, crm.store_offer_price_revision, tenant currency tables, packaging version owner.

**New logical structures — names provisional until migration review:**

| Entity / owner | Purpose | Required constraints |
|---|---|---|
| fx_auto_policy_activation | per tenant signed AUTO enablement; versioned scope, active/paused state | tenant FK, non-overlapping active revisions, actor/approval proof, CAS |
| fx_auto_target_enrollment | explicit target opt-in/exclusion and override, not duplicate FX anchor | tenant-qualified target type+ID, uniqueness, effective scope, revision |
| fx_auto_limit_policy_version | immutable scope limits incl cumulative/cooldown/batch limits | tenant, immutable authorizer, revision/digest, numeric checks, activation history |
| fx_auto_run | source date/revision + policy set/target manifest + status | unique run semantic key, immutable provenance fields |
| fx_auto_work_item | bounded per-target work/lease/retry/terminal outcome | unique (tenant,run,target), fencing/claim constraints, indexed status/nextAttempt |
| fx_auto_execution_receipt | immutable per target; old/new price revision, decision, source/policy | tenant-composite FK to canonical price revision, cannot duplicate one target/source/policy publish |
| fx_auto_execution_event | append-only audit of claim, block, publish, retry, owner pause | actor kind, error code, time, hashes; no raw exception/PII |
| canonical_price_origin extension | distinguishes MANUAL / FX_AUTO with approved authority | canonical price owner migration, preserve manual history semantics |

**Schema safety:** use composite tenant FK everywhere; separate PG immutable triggers from mutable worker lease, no composite FK to an unqualified cross-tenant ID. Add indexes for eligible target scans, run claims (nextAttemptAt/status/tenant), target dedup and price-history provenance. No hot-path full-table scan. Do not migrate existing seller decisions to AUTO retroactively; append-only old immutable data remains unchanged.

**High-impact decision:** CATEGORY/GROUP selectors require an authoritative membership service. If none exists, R2C.0 supports only type + explicit target list and defers category selector; do not create «универсальный классификатор» solely for FX_AUTO.

## 10. Сервисные контракты (проектируемые, NOT EXISTS)

- FxAutoPolicyService.previewScope(actor,tenant,selector): counts eligible/ineligible, reasons, representative price/rate impact, no writes.
- FxAutoPolicyService.approve(actor,tenant,scope,limits,expectedRevision,requestKey): immutable policy+activation, owner authority, idempotent exact replay.
- FxAutoPolicyService.pause/resume/revoke(actor,tenant,activationId,expectedRevision): immediate future command fencing, history.
- FxAutoEnrollmentService.include/exclude(actor,tenant,target,...): typed target, no implicit new offers.
- FxAutoSimulationService.dryRun(actor,tenant,rateRevision,scope,limit/cursor): bounded deterministic preview with WOULD_UPDATE/BLOCKED/NO_CHANGE. Dry run must not insert any commercial price history.
- FxAutoRunService.prepareRun(trustedInternalPrincipal,tenant,rateRevision,policyActivation): durable manifest and target work items after committed official rate; no rate HTTP inside SQL.
- FxAutoWorker.claim/process/ack/fail(trustedInternalPrincipal,lease): per-target transactional price mutation via canonical owner; must revalidate source and current policy.
- CanonicalStorefrontAutoPricePort.applyValidatedPrice(trustedPrincipal,approvedPolicy,calculation,expectedVersion): existing store_offer.price revision semantics, manual price owner unchanged.
- ServicePriceAutoPort (future separate target): typed adapter to authoritative SERVICE/packaging price owner; unavailable target returns BLOCKED_UNSUPPORTED_TARGET.

Contract naming is illustrative. Implementation may consolidate into fewer classes provided no loss of security, testability and transaction ownership. No new HTTP controller until product MVP gate authorizes UI. System worker principal must be server-issued and unforgeable by caller-supplied UUID/string.

## 11. DRY_RUN → AUTO admission

**R2C.0 — Contract & source/owner review:** inventory price owners for PRODUCT, SERVICE and PACKAGING; select currency-purpose approach; establish policy signers, lock DAG, target membership, threat model and reversible feature flags. No new code required for approval of this stage.

**R2C.1 — Policy + DRY_RUN foundation:** append-only policy/enrollment, exact run selection, read-only evaluation; preview what would change with old/new price and reason. No auto publication. Test 0/1/100/10 000, tenant A/B, invalid source and boundary.

**R2C.2 — PRODUCT_AUTO shadow/pilot:** opt-in owner signature; guarded internal pricing port through STOREFRONT; durable work item/lease/fencing/recovery and immutable execution receipt; manual emergency stop; start with limited explicitly enrolled PRODUCT offers. Real scheduler still OFF by default.

**R2C.3 — SERVICE adapters (conditional):** after independently qualified target owner, eligibility, VAT/tax, historic quote/service snapshots, refund semantics, transfer of existing permissions. Packaging service as separate owner. No broad «all SERVICE» switch before this.

**R2C.4 — Operational enablement:** trusted NBRK calendar/date source-to-day attestation, live egress/issuer approval, manual controlled first batch, monitoring/alerts/retry and operator runbooks, fully qualified exact-SHA, followed by separate pilot and production authorization.

**Feature flags:** global scheduler OFF; tenant AUTO OFF; target AUTO OFF. DRY_RUN can be enabled earlier in controlled UAT. Rollback is **pause publication**, not retroactively undo accepted KZT orders. Fixing an incorrect price requires a separately authorized new canonical revision; never UPDATE historical price snapshots.

## 12. Тестовая матрица (42 named business checks, not completed tests)

| IDs | Area | Mandatory oracle |
|---|---|---|
| FXA-01…04 | Scope/approval | tenant owner only; signed approval; exact list and includeFuture=false; no cross-tenant or unsupported type |
| FXA-05…08 | Policy precedence | EXCLUDE>offer>group>tenant; inherited stricter limit; concurrent activation CAS; revoked policy blocks |
| FXA-09…12 | Math | USD/EUR/CNY nominal/precision; ceiling rounding; zero/negative/overflow; deterministic same snapshot |
| FXA-13…16 | Guards | ± delta incl rounding; min/max KZT; cumulative window non-bypass; cooldown + minChange |
| FXA-17…20 | Rate | NBRK exact revision/freshness; MOCK denied; corrected source consistency; missing/unattested KASE calendar fail closed |
| FXA-21…24 | Dry run | NO price mutation; 0/1/100/10 000 bounded; exact reason report; stable manifest replay |
| FXA-25…28 | Concurrency | checkout-before/after, quote-before/after, manual edit-before/after, IAM revoke-before/after |
| FXA-29…32 | Auto publishing | one KZT revision and system provenance; tenant/worker deny; multiple workers once; emergency pause |
| FXA-33…36 | Recovery | precommit rollback; postcommit lost ACK; lease expiration old-worker fencing; rate correction replay |
| FXA-37…39 | Service boundaries | PRODUCT-only initial; generic SERVICE blocked; approved PACKAGING adapter version and old snapshots intact |
| FXA-40…42 | Release gates | populated PostgreSQL migration; exact-SHA required jobs/Surefire no unexpected skips; full Business/QR end-to-end regressions |

**Особо обязательные негативные случаи:** SELLER FX mode cannot override public KZT policy; an existing order price never changes; no direct inventory/return/fulfillment effects; stale offer CAS fail; multiple accepted quotes not invalidated behind buyer's back; no fabricated auto actor; revoked owner/policy cannot silently authorize worker; skip one failing target without affecting all others; no FX change if target is hidden/unpublished or lacks approved anchor and guardrail.

**Evidence standard:** каждому FXA-ID — конкретный JUnit method(s), база H2/real PostgreSQL, независимый SQL oracle по ценовым ревизиям/аудиту/заказам/tenant isolation, Surefire XML + source SHA + GitHub Actions URL. Mandatory two-real-transaction races on PostgreSQL, not sleeps alone. Clean + populated migration upgrade, correct checksum, replay/restart/crash and main H2/PG/Security/RustFS/SALES/QR suites at one SHA. 42 business checks — **тест-план, НЕ 42 выполненных теста**.

## 13. Exit gates and decisions requiring approval

| Gate | Requirement | Result if missing |
|---|---|---|
| R2C-G0 | Post-MVP implementation authorization, service-owner map, scope/role, pricing anchor purpose | DESIGN HOLD |
| R2C-G1 | Safe additive schema, policy/limit validation, H2+PG and clean/populated upgrade | FOUNDATION HOLD |
| R2C-G2 | DRY_RUN exact deterministic price/guardrail results, no price mutations, 10k bounded | DRY_RUN HOLD |
| R2C-G3 | Canonical PRODUCT price auto adapter, manual-origin semantics, two-order transaction/race evidence | PRODUCT AUTO HOLD |
| R2C-G4 | Durable batch idempotency, lease/fencing, stop/restart/replay, no silent roll-forward | WORKER HOLD |
| R2C-G5 | Independent generic SERVICE and PACKAGING owner contract; tax/version/quote/return rules; specialized regressions | SERVICES AUTO HOLD (PRODUCT independent) |
| R2C-G6 | Qualified official rate/calendar, external live smoke, operational monitoring, legal/security owner sign-off | GENERAL ACTIVATION HOLD |
| R2C-G7 | Exact-SHA all required workflows plus limited real seller UAT and explicit approval | PILOT/PRODUCTION HOLD |

**Definition of Done (PRODUCT):** одна одобренная tenant/target policy, 100+ зафиксированных PRODUCT offers, одна подтверждённая новая ревизия NBRK, DRY_RUN объясняет все кандидаты, AUTO меняет только допустимые offers без seller click, каждый результат с rate/policy/price provenance, при replay/двух workers ни одной двойной price revision, order/quote immutability intact, zero cross-tenant writes, owner can pause instantly before next commit, stable exact-SHA PG evidence.

**Definition of Done (SERVICES):** отдельный source-of-truth price adapter и доказанный жизненный цикл каждой поддержанной услуги; нельзя просто утверждать выполнение из PRODUCT DoD.

**Business decision register до R2C.2:**
1. Продуктовая граница: все существующие vs новые включаются автоматически? default только existing explicitly enrolled.
2. Currency purpose: SALES_INTERNAL для временного UAT или новый PRICING_ANCHOR? Следовать RM-CURRENCY-R1 и юридической оценке, не смешивать с PUBLIC_PRICE.
3. Parent/child inheritance и authority на ослабление hard caps; default explicit owner reapproval.
4. Временное окно cumulative cap, базовая цена окна и holiday calendar/trading date selection.
5. Предельные значения, max impact, cooldown, TTL, retry budget и допускаемые подмножества.
6. Manual override, rollback/stop и уведомление об исключениях — нужна ли event delivery или достаточно доступного отчёта (по умолчанию отчёт).
7. Полномочия system principal и история как SYSTEM_AUTO, не спуфинг user.
8. Поддержка generic SERVICE и PACKAGING — отдельные решения, не обязательные для первой PRODUCT_AUTO вертикали.
9. Правовые ограничения на отображаемую KZT цену, защиту потребителя и условия уже акцептованной сделки — legal/compliance signoff before pilot.
10. Feature milestone: после MVP G3, без тихого расширения текущего функционального closure.

**Final status:** design only, сохраним под post-MVP feature backlog. Existing CUR-R2A/R2B остаются текущими сервисными возможностями. Не считать FX_AUTO реализованным, не разрешать real scheduler, менять обработку SALES/INVENTORY, подключать новые REST-контроллеры, раздувать MVP или merge main на основании одного этого ТЗ.
