# RM-CUR-R2C — Smart FX Shield: Controlled Auto Pricing
## Техническое задание R1.1 — проверяемые контракты ограниченной автоматической переоценки

**Дата редакции:** 2026-11-24. **Репозиторий:** alimbetov/RM. **Рабочая ветка:** base_dev.  
**Проверенный source baseline:** 004c8f63a6fb8a2f5690385ba9da5fc5927f73e1; исходная редакция R1.0 введена этим коммитом.  
**Статус:** DESIGN SPEC / POST-MVP PARKED / IMPLEMENTATION NOT AUTHORIZED / PRODUCTION HOLD.  
**Назначение:** единый контракт для разработки, review и независимой приёмки. Наличие документа не доказывает реализацию, прохождение тестов или разрешение реальной автопубликации.

R1.1 заменяет R1.0 в этом же файле, чтобы сохранить действующие ссылки. Она уточняет общие контракты товаров и услуг, полномочия владельца, математические ограничения, риск устаревшей цены, совместное расходование бюджетов и последовательность допуска. Пороговые значения продавца и операционные SLO должны быть утверждены перед активацией; отсутствие обязательного значения означает HOLD, а не скрытый default.

**Нормативные связи:**
- [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md): IAM, tenant, неделегируемая membership OWNER, доменные права, владение данными.
- [RM-MVP-CLOSURE-FREEZE-R1](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md): FX_AUTO вне текущего MVP; G3 — пилот, G4 — release admission.
- [RM-FX-PRICING-R1](RM-FX-PRICING-R1-SMART-FX-SELLING-PRICE.md): валютная привязка и отдельное назначение PRICING_ANCHOR.
- [CUR-R2A](RM-CUR-R2A-IMMUTABLE-SKU-FX-APPROVAL.md), [CUR-R2B](RM-CUR-R2B-SELLER-FX-PRICING-GUARDRAILS.md): существующие расчёты, ручное решение, guardrails.
- [CURRENCY-R1](RM-CURRENCY-R1-TENANT-MARKET-CURRENCY-POLICY.md), [CUR-R1C](RM-CUR-R1C-COMMERCE-ADMISSION.md): KZT-only public commerce.
- [DEC-FUL-01](../architecture/RM-DEC-FUL-01-EXECUTION-CHANNEL-R1.md): неизменяемость согласованных заказов и границы исполнения.

При конфликте с действующим стандартом безопасности или MVP freeze действует более строгая граница, конфликт заносится в decision record. R1.1 не переписывает молча нормативные документы и существующие разрешения.

## 0. Продуктовое решение и граница работ

Продавец заранее разрешает системе менять будущую публичную KZT-цену выбранных предложений на основе утверждённого валютного якоря USD/EUR/CNY. Каждая публикация должна оставаться внутри утверждённой политики и текущих доменных ограничений. Повторное подтверждение каждой допустимой цены не требуется.

**Что защищает механизм:** исполнение согласованной политики переоценки и ограничение автоматического воздействия. Он не гарантирует прибыль, себестоимость, отсутствие убытка или банковское хеджирование. Устаревший anchor или сохранённая при шоке старая цена могут привести к экономическому убытку.

**Архитектура:** Spring Boot modular monolith + PostgreSQL; один существующий калькулятор, общие policy/risk/worker contracts, отдельный adapter каждого канонического владельца цены. Нет второй таблицы актуальных публичных цен, самостоятельного ERP/pricing microservice, нового order aggregate, валютного checkout, Kafka, Redis, ML, бухгалтерии или расчёта COGS.

**Разрешённая сейчас работа:** анализ и документация. Реализация требует отдельного письменного scope-change после MVP G3. Для реального FX-пилота дополнительно требуется MVP G4 либо явное отдельное решение ответственных о контролируемом пилотном окружении; G3 сам по себе production не разрешает. Все зависимости R2C-G0…G7 ниже обязательны для соответствующего scope.

Реальные scheduler, tenant AUTO и target AUTO по умолчанию OFF. Существующие MANUAL/FX_SUGGESTED не преобразуются в AUTO. Новые REST/UI и внешняя доставка уведомлений не разрешаются фактом редактирования этого документа. Main не изменять.

## 1. Проверенная исходная реализация

| Компонент | Факт на source baseline | Ограничение для R2C |
|---|---|---|
| CatalogItem | PRODUCT / SERVICE, собственная история cat_price | Catalog SERVICE не равен продаваемому публичному offer |
| StorefrontOfferService | PRODUCT-only, CAS rowVersion, append-only store_offer_price_revision | Нет qualified trusted AUTO port; actor_user_id означает человека |
| SkuFxCandidateCalculator | Чистый preview на BigDecimal, rate/nominal, CEILING | Не публикует цену; сам не даёт разрешения AUTO |
| SkuFxSellerApprovalService | Политики, guardrails, calculation и decision привязаны к offerId | Нельзя передавать serviceCode вместо offerId или фабриковать offer |
| TenantCurrencyPolicyService | Текущая R2A использует SALES_INTERNAL | Для нового AUTO требуется явный PRICING_ANCHOR; legacy не переименовывать |
| NBRK snapshots | Immutable revisions, provenance и source hashes | Независимая квалификация календаря/источника и unattended operation не доказана документом |
| PackagingServiceCatalogService | SALES owner; ключ tenant + serviceCode; versioned unit_price, tax_rate; цена может быть нулевой | Собственные CAS/lifecycle/permission; процент изменения от нуля не определён |
| SALES / QR checkout | Канонические предложения, accepted quote/order snapshots | FX не меняет акцептованные суммы, склад, выдачу или возвраты |
| FX_AUTO policy/worker | Предлагаются этим ТЗ | Нельзя считать существующими по списку будущих классов |

Проверенные источники: [calculator](../../src/main/java/kz/rm/fx/SkuFxCandidateCalculator.java), [seller approval](../../src/main/java/kz/rm/fx/SkuFxSellerApprovalService.java), [storefront owner](../../src/main/java/kz/rm/storefront/StorefrontOfferService.java), [packaging owner](../../src/main/java/kz/rm/sales/PackagingServiceCatalogService.java).

При реализации повторно инвентаризировать фактический HEAD, Liquibase master и вызываемые owner paths. Применённые changesets не менять; брать следующий свободный номер на момент реализации. Этот source review не является результатом запуска тестов.

## 2. Архитектурные решения R1.1

| ID | Решение | Последствие |
|---|---|---|
| DEC-01 | PRODUCT first; SERVICE adapters conditional | Реализация услуг не блокирует товарную вертикаль; общий target contract определяется до foundation |
| DEC-02 | Общая математика, отдельные price owners | Adapter не реализует копию формулы и не делает прямой SQL в чужие таблицы |
| DEC-03 | AUTO требует PRICING_ANCHOR | Нет fallback на SALES_INTERNAL/PURCHASE/PUBLIC_PRICE; нужна additive migration и compatibility evidence |
| DEC-04 | Approval: ACTIVE OWNER плюс необходимые доменные права | OWNER не становится wildcard; один TENANT_RBAC_MANAGE не даёт разрешения менять товарные цены |
| DEC-05 | Consent — подтверждённое действие над immutable payload | SHA — контроль целостности, не криптографическая подпись пользователя и не правовое доказательство сам по себе |
| DEC-06 | Обязательны rolling risk limits; fixed business-day limits могут только дополнительно ужесточать | Полночь, restart, новый run и новая activation не обнуляют использованный rolling budget |
| DEC-07 | Первая вертикаль: PRICE_ONLY_KEEP_LAST | При FX-блокировке старая цена сохраняется; новые продажи продолжаются по действующим правилам, риск явно принят владельцем |
| DEC-08 | HOLD_NEW_SALES — отдельная отключённая capability | Её нельзя включить без consent, SALES admission contract, независимых тестов и отдельного scope approval |
| DEC-09 | Критические FX-инциденты требуют durable alert и реакции | Отчёт сам по себе недостаточен для unattended AUTO; общий notification engine не строить |
| DEC-10 | R2C.2 — техническая квалификация, R2C.4 — разрешённая эксплуатация | Ручной вызов live AUTO не обходит source/operations/release gates |

Технические решения выше являются требованиями будущей реализации, а не свидетельством ранее полученного security/legal approval. Если review отклоняет решение, создаётся новая редакция с последствиями и тестами; runtime не выбирает альтернативу самостоятельно.

## 3. Общий контракт цели, scope и наследование

### 3.1. Типы и владельцы

| targetType | Канонический ключ | Owner и начальный допуск |
|---|---|---|
| PRODUCT_OFFER | tenantId + offer UUID | STOREFRONT; PUBLISHED KZT offer + eligible PRODUCT |
| GENERIC_SERVICE | tenantId + owner-qualified stable service key | Future owner; BLOCKED_UNSUPPORTED_TARGET до отдельной квалификации |
| PACKAGING_SERVICE | tenantId + serviceCode | SALES PackagingServiceCatalogService; отдельный adapter и выпуск |
| UNKNOWN | Любой неразрешённый тип/owner | BLOCKED_UNSUPPORTED_TARGET; никакой ценовой записи |

Общий TargetRef содержит tenantId, targetType, ownerType, canonicalKey. Он не требует, чтобы каждый владелец использовал UUID. Ключ без tenant и type недостаточен. Нельзя переиспользовать ID удалённой цели для другой сущности.

Общий immutable PricingContext содержит TargetRef; canonicalPriceRevision и CAS version; current KZT amount; unitCode и priceBasis; anchorRef/revision, currency/amount; formulaVersion; buffer/markup/rounding; действующие policy/guardrail/enrollment revisions; source snapshot/revision; consent reference; оценочное время. Tax semantics задаются owner: TAX_INCLUDED, TAX_EXCLUDED либо явно описанная NONE. NONE не означает налоговое освобождение.

Для PRODUCT используются существующие anchor/guardrail записи R2A/R2B. Service adapter позднее предоставляет owner-qualified immutable anchor binding; его таблица не становится альтернативной публичной ценой. Предложение сохранить все offer-bound таблицы «unchanged» относится к PRODUCT и истории legacy; оно не обязывает помещать туда услуги.

Ссылки receipt на price revision проверяются конкретным owner и tenant-qualified FK/typed link. Полиморфная пара type/id без доказуемой ссылочной целостности недостаточна.

### 3.2. Поддержанная ценовая семантика

Первая формула применяется к положительной фиксированной цене одной явно определённой единицы, целиком индексируемой одним валютным якорем. Смена единицы, priceBasis, налоговой трактовки, anchor currency или формулы требует нового owner-approved pricing context.

Нулевая текущая цена, бесплатная услуга, смешанная валютная/локальная составляющая, тарифные ступени, subscription period, динамическая цена, скидка с отдельным owner или пакетная цена товара+услуги получают BLOCKED_UNSUPPORTED_PRICE_BASIS до отдельного контракта. Нельзя делить на ноль, заменять ноль epsilon или индексировать всю смешанную услугу по догадке.

Если у нескольких позиций есть единая пакетная цена, условие общей скидки либо обязательная согласованная комбинация, они не являются независимыми target transactions. В R1.1 такие связанные цели исключены. Их будущая поддержка требует контракта владельца итоговой цены; не делать частичный update пакета.

### 3.3. Селекция и согласие

Поддерживаются явный список TargetRef и PRODUCT_ONLY eligible selection внутри tenant. GROUP/CATEGORY включаются только при наличии канонического tenant-owned membership и ACL; поиск по названию не является membership. До такого контракта group selector недоступен.

Approval фиксирует allowedTargetTypes, owner types, selector version/digest, maxTargets, eligibility version, includeFuture и pricing bindings. includeFuture=false по умолчанию. Для существующего списка фиксируются точные target IDs и их anchor/context revisions.

includeFuture=true разрешает лишь новые цели внутри уже утверждённых типов и ограничений. Наличие этой опции не даёт worker права придумывать anchor или копировать чужое согласие: новая цель должна иметь owner-approved binding либо пройти явно утверждённое детерминированное template rule. Без квалифицированного template включение будущих целей остаётся OFF.

Установка SERVICE adapter не расширяет старое PRODUCT consent. Смена типов, selector semantics или денежных параметров требует новой approval revision. Для каждой batch сохраняется конечный immutable manifest с версиями membership/selection; изменение фильтра не меняет уже созданный run.

### 3.4. Эффективные правила

EXCLUDE / MANUAL_OVERRIDE > target-specific approved policy > approved group policy > tenant default. Неоднозначное членство нескольких групп без согласованного правила композиции => BLOCKED_POLICY_CONFLICT. При одинаковом уровне не выбирать случайного победителя.

Для hard safety limits применяется пересечение ограничений всех действующих уровней: меньший максимум, больший минимум; пустой интервал => BLOCKED_POLICY_CONFLICT. Локальная политика не расширяет tenant ceiling. В состав effectivePolicyDigest входят все участвующие revisions, bindings и methodology, а не только ID ближайшего правила.

Общий tenant budget действует поверх локальных политик. Разбиение цели между группами/батчами, смена priority или enable/disable не создают новый бюджет.

## 4. Consent, IAM и жизненный цикл

Режимы цели: MANUAL, существующий FX_SUGGESTED и новый FX_AUTO_BOUNDED. DRY_RUN — операция оценки, которая не переводит цель в AUTO и не даёт разрешения публикации.

### 4.1. Кто может разрешить AUTO

Approval/activation/расширение/resume: VerifiedActor с ACTIVE global account, ACTIVE tenant/workspace, ACTIVE OWNER membership в этом tenant и текущими необходимыми доменными grants. Для PRODUCT это как минимум STOREFRONT_OFFER_MANAGE; для будущего PACKAGING — SALES_PACKAGING_MANAGE. Если owner contract требует дополнительные permissions, проверяются и они. Publish lifecycle permission не подменяется: AUTO не переводит DRAFT/PAUSED/HIDDEN в PUBLISHED.

Право TENANT_RBAC_MANAGE используется только в его неделегируемом IAM-смысле; оно не является альтернативой OWNER membership + domain admission. PLATFORM_ADMIN не получает merchant consent автоматически.

Сотрудник с разрешением управления конкретной целью может EXCLUDE/PAUSE её автоматизацию, но не расширить tenant scope, ослабить лимиты, включить AUTO или снять чужой security/source HOLD. Для групповой политики нужны подтверждённые права на каждую включаемую область.

### 4.2. Доказательство согласия

ApprovalEvidence содержит tenant, actor user reference, время доверенного сервера, requestKey, verified authentication evidence reference без токенов, проверенную membership/authority revision, payloadSchemaVersion и payloadDigest. Payload включает scope, target types, binding/template revisions, currency purpose, источник, формулу, limits, risk profile, deadlines, validFrom/validUntil и обязательное описание риска сохранения старой цены.

Canonical encoding для digest версионируется: UTF-8, фиксированный порядок полей, сортировка множеств по typed key, различение отсутствия и null, нормализованная десятичная строка без exponent/неоднозначных trailing zeros, UTC instants. Golden fixtures фиксируют точные bytes и SHA-256. Нельзя подписать hash одного preview и активировать другой payload.

В R1.1 «подтверждение» означает аутентифицированное действие с защищённой append-only записью. Это не утверждение об ЭЦП пользователя. SHA не заменяет IAM, контроль записи аудита и security review. Если для выпуска требуется отдельная электронная подпись, её профиль и проверка оформляются явно до допуска.

Повтор requestKey с тем же payload возвращает тот же результат после текущей проверки доступа; с другим payload => IDEMPOTENCY_CONFLICT. Секреты, bearer/JWT и клиентские PII в evidence не сохраняются.

### 4.3. Состояния и отзыв

Policy revision immutable: DRAFT → APPROVED → ACTIVE → PAUSED / RETIRED. REVOKED и EXPIRED — терминальные причины недействительности consent; вернуть их в ACTIVE нельзя, нужна новая approval. PAUSED → ACTIVE возможен только при ещё действующем consent, актуальных полномочиях и повторном admission; используется новый executionEpoch, но прежний бюджет и semantic event identity.

Любое изменение финансовых параметров, источника, formula/anchor/priceBasis, allowed types или расширение selector требует новой approved revision. Сужение scope разрешено отдельным auditable exclude/pause, без редактирования старого payload.

Перед новым price write проверяются текущие полномочия авторов всех approvals, участвующих в effective policy/authority chain. Блокировка такого автора, отзыв обязательного grant, потеря OWNER, transfer/recovery ownership или истечение consent => AUTHORITY_HOLD. Новый OWNER не наследует старое AUTO-согласие; требуется собственное подтверждение. Восстановление старого аккаунта не возобновляет AUTO самостоятельно.

System worker имеет отдельную server-issued capability, ограниченную tenant/операцией/активацией. Не создавать фиктивный VerifiedActor продавца и не доверять UUID из JSON. В receipt отдельно: policyAuthorizer и systemExecutionIdentity.

### 4.4. Stop semantics

Global executor, tenant, policy и target имеют проверяемые durable stop/epoch fences. Флаг только в памяти или cache не обеспечивает гарантию.

PAUSE/revoke подтверждается после сериализации с in-flight публикациями: уже линейризовавшаяся price transaction может завершиться до PAUSE commit; после успешного PAUSE commit новых ценовых commits нет. Timeout означает STOP_REQUESTED, а не ложное «остановлено». Тестируются обе очередности.

Повторный запуск, смена worker, флага или lease не снимают HOLD и не сбрасывают счётчики. Kill switch останавливает новые AUTO price writes; существующие заказы исполняются по прежним правилам.

## 5. Математика и обязательные price guardrails

### 5.1. Единственная формула

Для официального курса rateKzt за nominal единиц anchorCurrency:

    normalizedRate = rateKzt / nominal
    convertedKzt = anchorAmount × rateKzt / nominal
    bufferedKzt = convertedKzt × (1 + bufferPct / 100)
    unroundedKzt = bufferedKzt × (1 + markupPct / 100)
    candidateKzt = CEILING(unroundedKzt / roundingStepKzt) × roundingStepKzt

Используется SkuFxCandidateCalculator и versioned methodology SKU_FX_R2A_V1 с его существующим MathContext.DECIMAL128 для промежуточных операций: сначала anchorAmount.multiply(rateKzt, MC), затем divide(nominal, MC). normalizedRate служит для анализа источника; заранее округлять его и подавать вместо существующей последовательности запрещено. Новые adapters не копируют расчёт. Смена порядка операций, precision или rounding — отдельная methodology revision и reapproval, с regression старых эталонов.

anchorAmount, rate, nominal, roundingStep положительны; buffer/markup и scale/precision валидируются существующим calculator и более строгими owner/storage constraints. Ограничение NUMERIC проверяется по целой и дробной частям до записи, а не только по Java precision. Никаких double/float и автоматического исправления неверного nominal.

Синтетический oracle: 100 USD × 500 KZT/USD × 1.05 × 1.10 = 57 750; шаг 100 => 57 800 KZT. Это не текущий курс. Эквивалентные пары rate/nominal должны давать тот же результат независимо от масштаба исходной котировки.

### 5.2. Проверки непосредственно перед публикацией

P — текущая каноническая KZT-цена под owner lock; C — окончательно округлённая candidate; Δ=C−P. Для P>0 увеличение равно max(Δ,0)×100/P; снижение равно max(−Δ,0)×100/P.

Все обязательные условия действуют одновременно:
1. PRICING_ANCHOR разрешён для anchorCurrency, PUBLIC_PRICE — KZT; валютная policy и legal admission действительны.
2. Valid consent, ACTIVE owner authority, target enrollment, текущие revisions и supported priceBasis.
3. Qualified NBRK source, correct effective date/nominal, latest admissible revision, freshness и обязательное calendar evidence.
4. C проходит maxIncreasePct/maxDecreasePct относительно P; minPriceKzt ≤ C ≤ maxPriceKzt.
5. C проходит все target rolling/fixed limits и общий бюджет из §6.
6. Cooldown истёк; абсолютное изменение и относительное изменение не меньше обоих утверждённых minChangeAmount/minChangePct. Если порог равен 0, это явно записывается в policy.
7. Нет manual override, активного торга/AVAILABLE accepted quote, stop/HOLD, source supersession или несовместимого изменения объекта.
8. Версия актуальна; цена и receipt будут записаны атомарно; event ещё не был успешно опубликован.

Равенство maximum допускается, превышение — BLOCKED. Для минимума изменение на самой границе допускается. Сравнения выполняются без предварительного округления вычисленного процента; по возможности точным cross-multiplication. Округление candidate всегда предшествует проверкам.

C=P => NO_CHANGE, без новой price revision и без расходования бюджета. Меньше порога minChange => NO_CHANGE_SMALL_DELTA. Вне диапазона => BLOCKED; clamping, поэтапное приближение к запрещённому candidate и перерасчёт следующего шага от прошлой AUTO-цены запрещены. Формула всегда идёт от утверждённого anchor.

Anchor имеет reviewDueAt и provenance. Просроченный anchor блокирует AUTO. Продление срока без проверки не выполняется worker; owner подтверждает новую binding revision. minPriceKzt не считается автоматически вычисленной себестоимостью.

## 6. Накопленные ограничения и совместный бюджет

### 6.1. Точное окно и baseline

Для каждого target утверждаются rolling duration W, maxRollingIncreasePct, maxRollingDecreasePct, maxRollingAbsoluteVariationKzt и maxAutoPublications. W — длительность, а не строка «за день». Время t берётся у БД при финальном admission; окно событий определяется как (t−W, t].

Baseline B(t) — последняя каноническая цена, действовавшая на t−W, включая событие ровно на границе; при одинаковом timestamp порядок задаёт каноническая owner revision. Она реконструируется по immutable owner history, а не назначается текущим worker. Проверяются netIncrease=max(C−B,0)×100/B и netDecrease=max(B−C,0)×100/B. B должен быть положительным и соответствовать тому же unit/priceBasis/context.

Если новая цель существует меньше W, используется её первая доказанная каноническая цена с явной отметкой shortenedHistory. Для старой цели отсутствие непрерывной истории не маскируется этим правилом: BASELINE_UNAVAILABLE => HOLD. Legacy timestamps проходят проверку пригодности, не переименовываются в DB commit time и не исправляются задним числом. Первый enrollment сохраняет ссылку на историю, а не сбрасывает её. Несопоставимая смена unit/priceBasis блокирует AUTO до отдельного утверждённого transition contract; новая context revision не является разрешением обнулить ограничения.

AbsoluteVariation = сумма |newPrice−oldPrice| всех канонических price changes цели внутри окна плюс |Δ| предлагаемой публикации. Учитываются и MANUAL изменения; они не запрещаются FX guardrail, но могут исчерпать допустимость последующего AUTO. История до текущего enrollment учитывается, если попадает в окно и имеет совместимый context.

maxAutoPublications считает только успешные AUTO price revisions в том же окне плюс предлагаемую. NO_CHANGE, BLOCKED, retry и DRY_RUN не расходуют лимит. Колебание вверх-вниз не компенсирует использованную absolute variation; новая корректирующая AUTO revision расходует бюджет.

Дополнительно можно установить fixed business-day limits с явной IANA zone, временем начала дня и immutable boundary instant. Frozen baseline фиксированного окна — каноническая цена на его начало, а не цена при первой job. Fixed лимиты никогда не заменяют rolling limits. При смене zone/window старое окно продолжает ограничивать публикации до истечения своего срока; переход фиксируется owner-approved policy.

### 6.2. Бюджет tenant и run

Обязательные измеримые лимиты:

| Лимит | Единица и смысл | Область |
|---|---|---|
| maxTargetsPerRun | Количество материализованных целей | Один manifest; превышение => reject preparation, без тихого усечения |
| maxPublishedTargetsPerRun | Количество успешных ценовых публикаций | Run |
| maxSumAbsoluteUnitPriceDeltaKztPerRun | Сумма абсолютных Δ цен за утверждённую единицу каждой цели | Run |
| maxSumAbsoluteUnitPriceDeltaKztRolling | Та же сумма по успешным AUTO commits | Tenant, все policy/groups/runs/types, утверждённое rolling Wb |
| maxAutoPublicationsTenantRolling | Количество успешных AUTO commits | Tenant, то же окно |

Сумма изменений unit prices — технический ограничитель масштаба публикаций, **не денежный убыток, GMV или рыночная стоимость запасов**. Сложение цен разных единиц не превращает её в оценку экономической экспозиции. Старое неоднозначное имя maxPublicPriceChangeValue в новом контракте не используется.

Если нужен quantity-weighted exposure budget, сначала определить источник quantity, snapshot/date, единицы, tax basis и методику. R1.1 такой расчёт не обещает и не читает INVENTORY для оценки риска.

Нельзя компенсировать +5 000 и −5 000: расход равен 10 000. Новый run, изменённый manifest, смена policy, повторное включение цели и новая source correction не обнуляют tenant consumption. MANUAL не списывает AUTO tenant budget, но не возвращает ранее израсходованный.

### 6.3. Атомарность расходования

Под owner/budget locks worker вычисляет фактический Δ от текущей цены, проверяет свободный run и tenant budget и в одной короткой транзакции фиксирует цену, receipt и consumption. Durable ledger ссылается на канонические price revisions; он не хранит альтернативную текущую цену.

Чтение остатка бюджета без сериализации недостаточно. Конкурирующие разные цели/разные runs обязаны конфликтовать на одном стабильном budget guard (tenant, budgetFamily), который не заменяется при смене policy/window revision. Проверяются все ещё действующие window obligations. Lock order нескольких budget guards детерминирован. Отдельная долгоживущая предварительная reservation для первой версии не нужна: проверка и расход происходят в transaction публикации.

Если два workers хотят потратить по 60 при доступном остатке 100, допускается только один commit, второй получает BLOCKED_BUDGET_EXHAUSTED. Потеря ACK не списывает ещё 60. Rollback не оставляет расход. Новая компенсирующая ценовая revision не удаляет прежнюю запись расхода.

Изменение лимита сериализуется с расходованием. Снижение ниже использованного не откатывает цены, но блокирует новый AUTO; повышение требует owner approval и сохраняет consumption. Смена duration не разрешает забыть неистёкшие обязательства старого окна. Очистка истории ограничена максимальным поддержанным lookback; расширение сверх доступной истории => HOLD.

### 6.4. Числовые приёмочные примеры

Все значения ниже синтетические, не коммерческие defaults.

| Сценарий | Ожидаемый результат |
|---|---|
| B=100; 100→110, затем 110→121 внутри W; rolling increase cap=15% | Второе изменение BLOCKED, хотя отдельный шаг равен 10% |
| 100→110→100; net deviation=0; variation cap=15 | Возврат 110→100 BLOCKED: общая variation была бы 20 |
| Окно с W=24h; +10% до полуночи, ещё +10% после; cap=15% | Граница календарного дня не обходит rolling cap |
| Run A потратил 70 из tenant cap=100; новый run B хочет 40 | BLOCKED независимо от нового manifest |
| Worker опубликовал Δ=25 и потерял ACK | Recovery возвращает receipt; цена/consumption остаются в одном экземпляре |

## 7. Курс, шоки и риск сохранённой цены

### 7.1. Admission источника

Для live AUTO допустим только qualified NBRK importer: trusted HTTPS origin, parsing, currency/nominal/precision, raw/semantic SHA, effective date, source revision и независимое календарное evidence. MOCK/синтетика допустимы лишь в изолированных тестах и никогда не образуют live eligibility. Нет fallback на kurs24, cache произвольной давности или ручной «похожий» курс.

Freshness различает fetchedAt, sourcePublishedAt при наличии, validDate и календарное evidence. Новая дата загрузки не делает старый курс свежим. Будущая effective date не используется раньше времени; праздник/выходной не предполагается без утверждённого календаря. Correction и новый trading day — разные события.

Run фиксирует source snapshot/revision. Worker не заменяет её на latest внутри того же run. Если зафиксированная ревизия стала superseded, неопубликованный work item завершается SUPERSEDED, а новый source event создаёт новый run. Уже committed цены остаются в истории; correction рассматривается как новая переоценка со всеми лимитами.

Для первого AUTO в валюте нужен отдельно принятый risk reference rate. Любой полученный import не становится risk baseline автоматически. При сопоставлении всегда сравнивается normalizedRate=rate/nominal; смена 1→10 единиц котировки при неизменном normalizedRate не является валютным скачком.

### 7.2. Circuit breaker

До оценки цен применяется source/tenant risk gate: изменение normalizedRate от последнего admitted reference и от reference внутри утверждённого rolling risk horizon; направление, revision ordering, частота corrections и превышение утверждённых maxRateJump/maxRateVariation. Пороговые значения и horizon входят в risk policy.

Это независимая проверка: округление цены, буфер либо minChange не должны скрывать FX-шок. Сравнение положительных rate/nominal с порогом выполняется без раннего округления нормализованного курса, например точным cross-multiplication. Даже экономически реальный скачок может требовать review. Проверка по независимому источнику может дополнительно наложить VETO, но не подменить NBRK в формуле и не разрешить публикацию при его недопуске.

Два уровня HOLD:
- SOURCE_INTEGRITY_HOLD по provider/currency: некорректные данные, календарь, невозможный nominal, нарушенная revision chain. Снять может только допущенный source operator после квалификации; merchant owner не обходит его.
- TENANT_FX_SHOCK_HOLD: превышен утверждённый риск tenant/currency. Снять может текущий OWNER с нужными grants после review и нового DRY_RUN; source gate должен уже допускать источник.

Состояния NORMAL → HOLD → REVIEWED → NORMAL; release фиксирует reviewed revision, evidence, actor и новый risk reference. Минимальное время стабилизации/число допустимых observations задаются policy; одних таймера или хорошего следующего tick недостаточно. Approval референса не сбрасывает price/budget history и не ослабляет лимиты автоматически.

### 7.3. Две независимые реакции

При HOLD всегда запрещены новые AUTO price writes, создаётся durable incident и сохраняется текущая каноническая цена.

| Профиль | Новые сделки | Что требуется |
|---|---|---|
| PRICE_ONLY_KEEP_LAST | Действующие SALES/CUR правила продолжают работать; старая цена может оставаться покупаемой | Обязательное owner acknowledgement риска, alert, response deadline; начальный поддержанный профиль |
| HOLD_NEW_SALES | Ограничить только новые несогласованные сделки по затронутым целям | Отдельный scope-change, consent, канонический SALES admission port, UX и тесты; в первой вертикали unavailable |

HOLD_NEW_SALES не должен реализовываться прямым изменением order или массовым hide offer из FX worker. Возможное будущее ограничение не переписывает existing accepted quote/order, не срывает разрешённую выдачу/возврат и не снимает существующие legal/security restrictions. Матрица новых list-price checkout, новых negotiations, уже принятых quotes и созданных orders обязательна до включения capability.

Сценарий «курс +30%, разрешённая цена +10%»: AUTO блокируется; PRICE_ONLY_KEEP_LAST сохраняет старую цену и явно допускает риск новых продаж. Нельзя отчитываться «убыток предотвращён». Если бизнес не принимает этот риск, данный tenant нельзя допускать к live AUTO до квалификации другого профиля.

### 7.4. Alerts, ответственность и зависимость от delivery

SHOCK, SOURCE_INTEGRITY_HOLD, отсутствие свежего курса сверх deadline, массовая блокировка/исчерпание budget, необработанный backlog и остановка executor создают durable incident. Dedupe key задаёт tenant/source/incident kind/episode; repeated attempts не создают шторм сообщений.

До live AUTO определяются alert channel, ответственный и заместитель, delivery/ack/response deadlines, escalation и политика при недоступности канала. Успешный HTTP/send не означает merchant ACK. Тестируются retry, lost ACK, outage и восстановление; incident не исчезает после restart.

Использовать существующий квалифицированный delivery port, если он есть. R3.2B/R3.2C не считаются готовыми или разрешёнными этим ТЗ. Если канала нет, допустима только отдельно утверждённая attended сессия с оператором и измеренным временем реакции; unattended AUTO остаётся HOLD. Это не разрешение строить общий notification subsystem в MVP.

При просроченной реакции — эскалация и сохранение AUTO HOLD. Для PRICE_ONLY_KEEP_LAST система не обещает автоматически остановить продажи. Восстановление AUTO требует review, актуальных gates и явного действия; notifier сам не возобновляет цены.

## 8. Транзакции, конкурентность и каноническая публикация

Importer: network fetch/validation вне commercial transaction → immutable official import commit → durable source event. Никаких HTTP вызовов под блокировками цены.

Один worker обрабатывает одну цель за короткую transaction:
1. Проверяет durable execution fences, актуальную identity/consent/tenant authority.
2. Под согласованными locks фиксирует current source admission и нужные policy/context/budget versions.
3. Канонический price owner блокирует текущую цель; проверяет lifecycle, priceBasis, current version, negotiations/quotes и manual provenance.
4. Вызывает общий calculator; повторно проверяет source, elapsed-time limits и все guardrails по актуальной цене/бюджету.
5. Через trusted owner port создаёт canonical price revision; атомарно записывает receipt, budget consumption и terminal work outcome с актуальным fence.

Этот список — логические обязательства, не готовый lock DAG. До реализации R2C.0 составляет фактический DAG вызовов IAM, currency, source importer/correction, policy, budget, STOREFRONT, SALES и work item. Нельзя назначить новый порядок, конфликтующий с существующим seller approval. Обязательны оба порядка двух реальных PG-transactions, timeout/deadlock classification и отсутствие lock inversion. Перечень конкретных таблиц/lock modes и порядок нескольких keys — артефакт G0.

**Checkout/quote:** если checkout линейризовался раньше FX commit, order сохраняет старую согласованную цену. Если позже — stale acknowledgement отклоняется, нужна новая версия и подтверждение покупателя. Active negotiation и AVAILABLE accepted quote блокируют AUTO всей соответствующей цели по существующему контракту; FX не продлевает и не отменяет их. Длительная такая блокировка попадает в backlog alert, а не обходится worker.

**Manual edit:** любое ручное изменение канонической цены устанавливает устойчивый MANUAL_OVERRIDE/enrollment fence в той же transaction либо через проверяемую каноническую revision provenance. Асинхронная проекция без precommit проверки недостаточна. Retry не перезаписывает ручную цену; возобновление — явный owner opt-in. Изменение только title не считается изменением цены, но CAS требует переоценки контекста.

**Pause/revoke:** общий guard lock/epoch сериализован с price write. Успешный ответ PAUSE выдаётся только после его commit. Ограничение apply-порта не зависит от того, вызвал его scheduler, оператор или тестовый клиент.

**Lease:** workerId/generation проверяются в той же transaction, что owner write, и при финальном work CAS. Row lock предотвращает takeover между финальной проверкой и commit. Lease validity оценивается по DB clock на точке fenced CAS; transaction с уже прошедшим CAS не считается незаконной только из-за истечения wall-clock lease до ответа. Новая generation никогда не допускает commit старой. Короткая transaction и DB timeout ограничивают задержку stop.

**Side effects:** цена не меняет QR publicRef, stock, reservations, fulfillment, return, принятые SALES snapshots. Business BLOCKED может записать результат/incident без price/budget writes. Неавторизованный вызов не создаёт commercial run/receipt/consumption; отдельный безопасный security audit допустим по общему стандарту.

**Clock:** актуальный DB clock после ожидания locks используется для lease, expiry, window и cooldown; cached transaction-start time и JVM clocks не определяют разрешение. Event time — время соответствующей DB linearization, не выдуманный точный commit timestamp. Обнаруженное существенное расхождение/скачок времени => TIME_INTEGRITY_HOLD; пороги и источник мониторинга задаются операционным профилем. Boundary tests управляют временем без sleeps как единственного oracle.

## 9. DRY_RUN, durable execution и восстановление

### 9.1. Snapshot и воспроизводимость

Один run относится к одному tenant и одному anchor currency/source snapshot. Массовая политика USD/EUR/CNY создаёт отдельные currency runs с общим tenant budget; результаты можно объединить отчётом. Нет фиктивной одной source revision для разных валют.

PrepareRun материализует ограниченный immutable manifest из согласованного DB snapshot: typed targets, effective contexts/policies, source, selection/membership revisions, evaluationAsOf, budget/reference snapshots и methodology. Подход к snapshot (например, bounded REPEATABLE_READ) квалифицируется; keyset pagination сама по себе не означает общий snapshot между транзакциями.

При запросе больше maxTargetsPerRun возвращается LIMIT_EXCEEDED с возможностью явного разбиения, а не скрытая усечённая выборка. В первой квалификации верхняя проверяемая ёмкость — 10 000; runtime limit может быть меньше по измерениям. Выборка и материализация не держат price owner locks на весь batch.

DRY_RUN допускает только запись simulation metadata/results в свой контур, если нужен durable preview; не пишет commercial prices, seller decisions, risk consumption, activation или SALES. Чистый previewScope может оставаться полностью read-only. Эти два контракта не смешивать.

Для воспроизводимого результата фиксируются и текущая цена/история, и время, и бюджет, а не только курс и policy. Одинаковый simulation key возвращает сохранённый результат; «посмотреть свежие данные» создаёт новый simulation snapshot.

Общий бюджет моделируется виртуально в стабильном порядке targetType/ownerType/canonicalKey. DRY_RUN показывает WOULD_UPDATE / NO_CHANGE / BLOCKED / WOULD_DEFER и причины; не утверждает, что каждый target независимо доступен на полный tenant budget.

AUTO использует ту же evaluator semantics, но повторно читает актуальные данные под locks. Поэтому preview не обещает те же цены/набор успехов при concurrent changes. Receipt сохраняет отличие observedContext от simulation и причину; старый preview не является правом обойти CAS.

### 9.2. Идемпотентность

RunKey = tenant + approvedActivationRevision + sourceSnapshotId/sourceRevision + manifestDigest + purpose + evaluationRequestId. evaluationRequestId обязателен только для явно запрошенной новой оценки; exact request replay не генерирует новый ID. Периодический автоматический source event имеет стабильный request identity.

TargetEventKey = tenant + typed TargetRef + sourceSnapshotId/sourceRevision + effectivePricingRevision. effectivePricingRevision фиксирует согласованные anchor, methodology, financial policy и authority context. RunId, manifestDigest, workerId, leaseGeneration и offer rowVersion в semantic publish key не входят.

Unique successful TargetEventKey защищает от повторной публикации через другие runs. Pause/resume, переупаковка manifest и собственное увеличение rowVersion не создают нового price event. Изменённая действительно утверждённая политика/anchor либо source correction создаёт новую экономическую оценку, но не новый budget history.

Существующий successful receipt после текущей проверки доступа возвращается без нового price write, даже если курс уже superseded. Для ещё не выполненного event superseded source блокирует новую запись. Exact replay не является повторным исполнением.

### 9.3. Work states и retry

Work states: READY → CLAIMED → PUBLISHED / NO_CHANGE / BLOCKED / SUPERSEDED / DEFERRED / RETRYABLE / DEAD_LETTER. DEFERRED и RETRYABLE возвращаются к claim только по nextAttemptAt; terminal outcome immutable. Append-only attempt events отделены от изменяемого work state.

| Причина | Результат | Повтор |
|---|---|---|
| C=P или ниже minChange | NO_CHANGE | Новый source/policy event либо явная новая оценка |
| Cooldown ещё действует | DEFERRED | Не раньше persisted nextEligibleAt; повторная полная проверка |
| Deadlock/serialization/transient DB unavailable | RETRYABLE | Bounded backoff+jitter; после бюджета DEAD_LETTER |
| Source replaced более новой revision | SUPERSEDED | Новое source event; старый run не перепривязывать |
| Price/budget/risk/authority violation, unsupported target | BLOCKED | Не крутить автоматический retry; нужен изменённый допустимый контекст либо явный review/re-evaluation |
| Неизвестная ошибка | DEAD_LETTER после ограниченной классифицированной попытки | Оператор разбирает; не подменять успехом |

Новая разрешённая re-evaluation может создать новый run после завершившегося BLOCKED, но unique successful TargetEventKey и общий budget сохраняются. «Вызвать ещё раз, пока пройдёт после смены суток» не является автоматической стратегией обхода ограничения.

Claim: bounded FOR UPDATE SKIP LOCKED, отдельный короткий commit lease; workerId, monotonic leaseGeneration, expiresAt, attempts, nextAttemptAt. Длинный сетевой процесс в lease transaction запрещён. Concurrent workers могут дать разный порядок успешных независимых целей при ограниченном budget; контракт гарантирует соблюдение лимитов и отчёт, а не произвольный обещанный порядок.

Нужны per-tenant concurrency quota и fairness: большой tenant не исчерпывает connection pool для остальных. Burst 2–5 source revisions coalesces неопубликованные obsolete work в SUPERSEDED; уже совершённые публикации не удаляются.

### 9.4. Crash, PITR и отчёт

До commit: rollback цены, receipt, consumption и terminal result вместе. После commit до ACK: recovery находит receipt, возвращает прежний outcome и не создаёт вторую цену. Падение во время manifest preparation не оставляет run со статусом READY без полного manifest; incomplete preparation либо откатывается, либо имеет отдельное непроцессируемое состояние.

PITR/restore по умолчанию поднимает AUTO OFF. До resume проверяются restored cutoff, canonical price/receipt/budget consistency, актуальный источник и актуальные merchant authority/stop decisions. Возможная потеря последних revoke/approval при откате не разрешает автоматический resume: нужна reconciliation и подтверждение уполномоченного владельца. Старые system leases после restore недействительны.

Run accounting: total, published, noChange, blocked, superseded, deferred, retryable, deadLetter, ready/claimed. Сумма непересекающихся текущих work states равна total. Пока есть nonterminal items, run остаётся RUNNING и отчёт показывает partial progress. Терминальные COMPLETED/PARTIAL_SUCCESS/FAILED допустимы только после завершения всех items; PARTIAL_SUCCESS честно показывает successful и rejected outcomes, DEAD_LETTER не выдаётся за business BLOCKED. Каждый report содержит source/policy/manifest digests, source SHA и execution timestamps.

## 10. Модель хранения, миграция и происхождение цены

Физические имена ниже — проектные; окончательные constraints и indexes фиксируются перед миграцией. Существующая каноническая цена остаётся у доменного владельца.

| Структура | Назначение | Обязательная гарантия |
|---|---|---|
| fx_auto_policy_revision / approval_evidence | Immutable payload и подтверждение | tenant, authorizer, digest/schema, valid window, unique requestKey+payload |
| fx_auto_policy_activation | Текущее разрешение и executionEpoch | CAS, non-overlapping activation для области; durable pause/revoke |
| fx_auto_target_enrollment | Typed scope, exclude/manual override | tenant-qualified target; immutable approval link; никаких подставных offers |
| fx_auto_limit_policy_version | Ограничения/rolling/fixed/risk profile | Immutable versions, explicit values/units, strict intersection |
| owner-specific anchor binding | Ссылка на единственный authoritative FX input | PRODUCT reuse R2A; SERVICE future owner; не дублировать public price |
| fx_auto_run / manifest / work_item | Полная выборка, bounded execution, lease | semantic run uniqueness, typed target uniqueness, fencing, status indexes |
| fx_auto_execution_receipt + typed price links | Неизменяемый результат/old-new provenance | Unique successful TargetEventKey, tenant-qualified owner revision FK |
| fx_auto_budget_guard / consumption | Сериализация и ledger фактических AUTO изменений | Same transaction with price/receipt; no double spend; window query indexes |
| fx_auto_risk_state / incident | Source/tenant HOLD, review и alert lifecycle | Durable generations, explicit authorised release, incident dedupe |
| fx_auto_execution_event | Append-only attempt/stop/review audit | Без tokens/raw exceptions/PII; stable reason codes |
| canonical price provenance extension | MANUAL / FX_AUTO + authorizer/system identities | Существующая ручная история не переинтерпретируется |

Для polymorphic targets используются typed owner links с реальными composite tenant FK либо эквивалентная квалифицированная схема constraints. Проверка «ровно один соответствующий owner link» должна быть доказана в PostgreSQL; если требуются deferred triggers, они описываются явно. Нельзя объявить cross-table ссылочную целостность обычным CHECK, который её не обеспечивает.

Immutable history защищается отдельно от mutable work/lease state. Indexes: nextAttemptAt/status/tenant, successful event dedupe, target/recordedAt canonical history, consumption tenant/time и source/policy references. Нет full-table scan на каждом target.

Migration: clean DB и заполненная PostgreSQL; старые price revisions сохраняют суммы, currency, actor и timestamps. Legacy human actor не становится SYSTEM_AUTO. Если actor_user_id обязателен, расширяется schema provenance и сохраняется FK-семантика; записывать worker UUID в human column нельзя. Никакой ретроактивной записи AUTO approvals.

Retention: до live допуска определяются сроки price/consent/risk/receipt/audit/incident history с security/legal owners. Минимальная техническая сохранность — доступность всех records для максимального активного window, replay/idempotency и accepted-order provenance. Отсутствие необходимого baseline после archive => HOLD; очистка не возобновляет лимиты. Legal retention PASS из этого ТЗ не следует.

Rollback deployment: остановить новые AUTO публикации, сохранить совместимость чтения истории и существующий MANUAL flow. Исправление неверной цены — отдельная разрешённая canonical revision; не UPDATE/DELETE старой истории и не менять суммы принятых заказов.

## 11. Сервисные контракты и reason codes

Названия illustrative; не требуется отдельный класс для каждой строки.

| Контракт | Вход/результат | Граница |
|---|---|---|
| previewScope | VerifiedActor, tenant, selector → eligible/ineligible counts, typed reasons | Не создаёт consent, не раскрывает чужой tenant |
| approve / activate | Immutable payload, expectedRevision, requestKey → evidence/activation | OWNER + grants; exact replay; activation после gates |
| pause / resume / revoke | Scope, expectedEpoch, actor → committed fence/status | Resume не сбрасывает budgets; revoke terminal |
| include / exclude | TargetRef, context binding, expectedRevision | Include только в согласованном scope; exclude не расширяет права |
| dryRun | Snapshot request, source, scope → persisted reproducible simulation | Нет commercial price/consumption writes |
| prepareRun | Trusted principal, tenant, activation, source event | Полный bounded manifest; один anchor currency |
| claim / process / ack / fail | Trusted capability, workId, generation → outcome | Fenced per-target transaction |
| CanonicalStorefrontAutoPricePort.applyValidatedPrice | Trusted principal + approved context + expectedVersion | Тот же owner CAS, currency/security/history; запрещён прямой caller-supplied price bypass |
| ServicePriceAutoPort | Typed future owner context | Нет реализации до owner admission; общий calculator/evaluator |
| reviewRisk / releaseRisk | Scoped operator или OWNER, incident, reviewed revision | Source integrity и merchant risk имеют разные полномочия |
| incidentDeliveryPort | Durable incident reference → delivered/acknowledged status | Не выдаёт право на price write/resume |

applyValidatedPrice не принимает «validated=true» как доказательство. Owner/shared admission перепроверяет trusted capability, binding, effective limits и fences в той же transaction; сформированный внешним caller calculation не является разрешением.

AUTO authoring/admission использует явный PRICING_ANCHOR path к общему authoritative anchor storage. Нельзя слепо вызывать legacy configure с обязательным SALES_INTERNAL и тем самым требовать несвязанное торговое разрешение либо подменять его смысл. Legacy FX_SUGGESTED сохраняет свой текущий контракт до отдельной миграции; существующий anchor можно явно enroll после проверки PRICING_ANCHOR, binding и нового consent. Общая валидация/хранение переиспользуются, но choice purpose задаёт доверенный operation contract, а не произвольная строка клиента.

Минимальный versioned reason vocabulary: WOULD_UPDATE, WOULD_DEFER, NO_CHANGE, NO_CHANGE_SMALL_DELTA, BLOCKED_UNSUPPORTED_TARGET, BLOCKED_UNSUPPORTED_PRICE_BASIS, BLOCKED_POLICY_CONFLICT, BLOCKED_PRICE_LIMIT, BLOCKED_ROLLING_LIMIT, BLOCKED_BUDGET_EXHAUSTED, BLOCKED_ACTIVE_NEGOTIATION, MANUAL_OVERRIDE, STALE_TARGET_CONTEXT, SOURCE_NOT_ADMITTED, SOURCE_SUPERSEDED, SOURCE_INTEGRITY_HOLD, TENANT_FX_SHOCK_HOLD, ANCHOR_REVIEW_OVERDUE, BASELINE_UNAVAILABLE, AUTHORITY_HOLD, CONSENT_EXPIRED, PAUSED, TIME_INTEGRITY_HOLD, RETRYABLE_TRANSIENT, DEAD_LETTER, IDEMPOTENCY_CONFLICT.

Reason содержит безопасные ожидаемые/фактические значения и ссылки на версии в пределах прав пользователя; не raw stacktrace. Multiple reasons допустимы с детерминированным primary reason. Ошибка чужого tenant не раскрывает существование цели, её цену или бюджет.

## 12. Этапы, gates и критерии завершения

### 12.1. Порядок работ

| Этап | Результат | Что ещё запрещено |
|---|---|---|
| R2C.0 Contract Review | Owner map, target/anchor semantics, authority, currency-purpose migration plan, DAG, budgets, risk/consent, traceability | Реализация без post-MVP scope authorization |
| R2C.1 Policy + DRY_RUN | Additive schema, evidence/enrollment, общая evaluation semantics, deterministic simulations | Автопубликация |
| R2C.2 PRODUCT_AUTO qualification | PRODUCT adapter, durable worker, crash/concurrency evidence в изолированной среде | Live pilot до G6 и G7-PILOT; scheduler OFF |
| R2C.3 SERVICE adapters — conditional | Отдельные owner-specific contracts/implementations/evidence | Blanket ALL_SERVICE и перенос PRODUCT PASS на услуги |
| R2C.4 Operational admission | Qualified source, delivery/attended mode, runbooks/SLO, exact-SHA, затем отдельные pilot/release решения | Автоматический переход в production по одному CI PASS |

Для PRODUCT R2C.4 может выполняться без R2C.3. Для каждого SERVICE adapter gates проходят отдельно. Номер этапа не отменяет обязательные safety prerequisites.

### 12.2. Проверяемые gates

| Gate | Обязательное доказательство | При отсутствии |
|---|---|---|
| R2C-G0 | Written implementation scope; review решений DEC-01…10; owner/authority map; price/source/lock DAG; перечень обязательных тестов | DESIGN HOLD |
| R2C-G1 | Schema/typed links/provenance/budget review; clean+populated upgrades; rollback compatibility | FOUNDATION HOLD |
| R2C-G2 | DRY_RUN reproducible input/output, virtual budgets, zero commercial mutations, bounded 10k fixture | DRY_RUN HOLD |
| R2C-G3 | PRODUCT canonical mutation; current permissions/currency/context; quote/manual/CAS races | PRODUCT AUTO HOLD |
| R2C-G4 | Durable selection, unique publication, lease/fences, shared budgets, stop/crash/retry/PITR evidence | WORKER HOLD |
| R2C-G5 | Каждый SERVICE owner: key/binding/unit/tax/zero-price/quote/return/permissions, specialized PG regression | SERVICES AUTO HOLD; PRODUCT независим |
| R2C-G6 | Official source/calendar live qualification; ops/alert channel or approved attended mode; privacy/security/commercial review; deployment/restore drills | ANY LIVE AUTO HOLD |
| R2C-G7-PILOT | G0–G4+G6 для PRODUCT; G5 для включаемых услуг; exact-SHA required workflows; synthetic UAT; письменное разрешение ограниченного live pilot и утверждённые limits | LIVE PILOT HOLD |
| R2C-G7-RELEASE | Результаты разрешённого live pilot, измеренные SLO/load, устранённые дефекты, security/legal/operations sign-off и explicit release decision | PRODUCTION HOLD |

G7-PILOT не требует заранее результатов ещё не разрешённого live pilot; эти результаты нужны G7-RELEASE. Нельзя обходить G6/G7 ручным вызовом process/apply или тестовым endpoint в реальной среде.

### 12.3. DoD

**PRODUCT technical DoD:** минимум 100 явно enrolled synthetic PRODUCT offers и load fixture 10 000; две организации; одна и burst ревизий курса; одинаковая evaluator semantics в simulation/AUTO; exactly-one canonical price revision по successful event; current owner authority; immutable orders/quotes; shared budget не превышен; stop и crash recovery доказаны на PostgreSQL. Это не live readiness.

**SERVICE DoD:** сохранены отдельные owner identity, unit/tax/price semantics и история; нет фиктивных offers/второго calculator; нет изменения старых service snapshots; отрицательные unsupported cases и combined tenant budget проверены. Для GENERIC и PACKAGING отдельные evidence rows.

**Operational DoD:** утверждённый capacity profile (batch/concurrency/DB pool/queue age, p95/p99, stop latency, alert delivery/ack/response, backup RPO/RTO), подтверждённые measurement artifacts и runbooks. Числа не назначаются произвольно в ТЗ. Каждое обязательное поле заполнено до live activation; отсутствие измерений => HOLD.

## 13. Матрица приёмки — 80 сценариев, не выполненные тесты

Сохранены ID FXA-01…42 из R1.0; FXA-43…80 уточняют выявленные риски. Один сценарий может требовать несколько JUnit methods и оба порядка транзакций. Количество IDs не является метрикой готовности или гарантией отсутствия дефектов.

| ID | Проверяемое пользовательское/системное поведение | Обязательный oracle |
|---|---|---|
| FXA-01 | OWNER без бизнес-grant; сотрудник с grant без OWNER; OWNER с обоими | Первые два не включают AUTO; третий действует только в своём scope |
| FXA-02 | Подтверждение конкретного payload; подложный actor UUID | Одно immutable evidence; caller identity из доверенной границы, forged input denied |
| FXA-03 | Explicit selection и includeFuture=false | Только exact targets; новая позиция не получает разрешения |
| FXA-04 | Tenant A/B, чужие IDs и unsupported type | Ноль чужих price/receipt/budget writes и утечек существования |
| FXA-05 | EXCLUDE/override/target/group/tenant precedence | Детерминированное effective решение; excluded target не меняется |
| FXA-06 | Child ослабляет parent cap; пересечение пусто | Строгая граница либо BLOCKED_POLICY_CONFLICT |
| FXA-07 | Две concurrent policy activation | Один CAS winner; проигравший не даёт authority worker |
| FXA-08 | Policy revoke до/после публикации | Ни одного нового price commit после revoke commit |
| FXA-09 | USD/EUR/CNY, разные nominal, purpose | Независимые числовые эталоны; candidate/public price строго KZT |
| FXA-10 | CEILING около границы шага | Округлённый результат и receipt совпадают с oracle |
| FXA-11 | Zero/negative/overflow/precision invalid | Deny до price/budget writes; не исправлять вход молча |
| FXA-12 | Один snapshot/context повторён | Тот же candidate и digest; формула не зависит от предыдущей AUTO-цены |
| FXA-13 | ± per-step limit после rounding | Включительная точная граница; превышение BLOCKED |
| FXA-14 | min/max KZT после rounding | Ни одной публикации вне диапазона, no clamping |
| FXA-15 | Серия малых шагов внутри rolling W | Ограничение от исторического B, а не от последнего шага |
| FXA-16 | Cooldown и оба minChange порога | Persisted DEFERRED либо NO_CHANGE; нет лишней revision |
| FXA-17 | NBRK validDate/fetch time/freshness | Повторное получение старого курса не продлевает допустимость |
| FXA-18 | MOCK/kurs24/unqualified source вместо NBRK | Live AUTO denied; отсутствует fallback |
| FXA-19 | Correction заменила source перед price write | Старый work SUPERSEDED; новый не подменяет старый manifest |
| FXA-20 | Отсутствующее/неверное calendar attestation | SOURCE_NOT_ADMITTED; праздник не угадывается |
| FXA-21 | DRY_RUN по реально существующим целям | Price/seller decision/consumption/SALES counts и hashes неизменны |
| FXA-22 | 0/1/100/10 000 и limit+1 целей | Полный manifest; limit+1 rejected без скрытого усечения |
| FXA-23 | Несколько причин запрета и разный доступ | Детерминированные safe reasons; чужие данные не раскрыты |
| FXA-24 | Replay simulation/manifest при изменившейся БД | Сохранённый результат прежний; refresh создаёт новый snapshot |
| FXA-25 | Checkout × FX, обе очередности | Старый immutable order либо stale-price rejection; ноль частичных orders |
| FXA-26 | Negotiation/accepted quote × FX | Согласованная цена не изменяется; active quote блокирует AUTO |
| FXA-27 | Manual price edit × FX, обе очередности | Устойчивый override; retry не перезаписывает ручную цену |
| FXA-28 | Signed access token/local JWKS → actor → tenant → policy; revoke | Valid issuer/audience/purpose; expired/ID token/foreign tenant denied; свежие права перед write |
| FXA-29 | Успешная AUTO-публикация | Одна canonical revision; отдельные authorizer и system identity; receipt/budget атомарны |
| FXA-30 | Подложный worker, чужая tenant capability, ordinary user | Trusted port denied без побочных коммерческих записей |
| FXA-31 | Два workers и два runs для одного event | Один successful TargetEventKey и одна price revision |
| FXA-32 | Global/tenant/policy/target stop × publish | После подтверждённого stop commit новых публикаций нет |
| FXA-33 | Fault injection до commit на каждом write boundary | Цена/receipt/consumption/work terminal state откатываются вместе |
| FXA-34 | Commit выполнен, ACK потерян | Recovery возвращает тот же receipt; второго расхода/цены нет |
| FXA-35 | Lease expired и новый generation захватил item | Старый worker не пишет цену и не завершает чужой item |
| FXA-36 | Correction после успешной публикации; повтор delivery | Новое отдельно проверенное событие; старый receipt и history неизменны |
| FXA-37 | Начальный PRODUCT_ONLY и изменившаяся eligibility каталога | Услуги/hidden/paused/draft/archived PRODUCT не переоцениваются; QR publicRef стабилен |
| FXA-38 | Generic SERVICE до qualification | BLOCKED_UNSUPPORTED_TARGET; нет фиктивного store_offer |
| FXA-39 | Qualified PACKAGING adapter | Своя version/CAS/tax semantics; прежние service snapshots неизменны |
| FXA-40 | Clean и populated Liquibase upgrade | Checksums/старые суммы/FK сохранены; invalid cross-tenant links rejected |
| FXA-41 | Exact-SHA evidence gate | Все обязательные jobs/XML доступны, нет неожиданных skips или ложного PASS |
| FXA-42 | Business/QR/SALES/INVENTORY regression | Orders/stock/handover/return не получают побочных FX-изменений |
| FXA-43 | UUID offer и строковый serviceCode; forged owner links | Typed identity корректна; tenant FK/ровно один соответствующий owner link защищены |
| FXA-44 | Бесплатная/смешанная услуга, смена unit/tax basis | Unsupported контекст заблокирован; нет divide-by-zero/индексации по догадке |
| FXA-45 | Установка adapter после «все позиции» consent | SERVICE не включается без новой approval; includeFuture не расширяет type |
| FXA-46 | Payload hash mismatch, validFrom/validUntil, requestKey conflict | Ни одной активации другого payload; expiry и conflict воспроизводимы |
| FXA-47 | OWNER transfer/recovery/role revoke × worker | Старое согласие HOLD; новый OWNER не получает чужое consent автоматически |
| FXA-48 | SALES_INTERNAL без PRICING_ANCHOR и обратный случай | Первый AUTO denied; второй AUTO authoring не требует SALES_INTERNAL; legacy flow не переинтерпретирован |
| FXA-49 | Два targets хотят по 60 при общем остатке 100 | Только один расход/price commit; второй budget-blocked |
| FXA-50 | Разбиение 70+40 между runs/groups при cap=100 | Tenant budget не обходится сменой manifest или группы |
| FXA-51 | Пара изменений +5 000/−5 000 | Расход 10 000, а не 0; компенсирующая revision не возвращает budget |
| FXA-52 | Midnight/timezone/fixed-day boundary внутри W | Rolling baseline/cap действуют через границу суток |
| FXA-53 | Новая цель против старой с отсутствующей историей | Разрешён только доказанный shortenedHistory; missing history => HOLD |
| FXA-54 | Restart/reapproval/window change/pause/resume | Неистёкший consumption и ограничения не обнулены |
| FXA-55 | Рост/падение и manual edit с повторным enrollment | Net и absolute variation проверены; ручное изменение не стирает risk history |
| FXA-56 | Изменение nominal при том же normalizedRate | Нет ложного FX-шока; одинаковый допустимый candidate |
| FXA-57 | FX +30%, price cap +10%, KEEP_LAST | BLOCKED + durable incident; старая цена сохранена; новые продажи не объявлены остановленными |
| FXA-58 | FX-шок скрыт rounding/minChange либо малым Δ | Risk gate всё равно накладывает HOLD до price evaluator |
| FXA-59 | Первый rate reference, integrity HOLD и попытка merchant bypass | Только qualified bootstrap/operator release; tenant owner не снимает provider HOLD |
| FXA-60 | Timer/new tick/restart после shock | Нет автоматического resume; нужен review и допустимый source |
| FXA-61 | Alert lost delivery/ACK/outage/escalation | Durable incident не теряется; send не означает merchant ACK; нет duplicate storm |
| FXA-62 | Upstream outage/старый курс/длительная блокировка quote | Freshness/backlog deadlines и alerts срабатывают; произвольного курса нет |
| FXA-63 | Cooldown work и burst 2–5 revisions | Obsolete work superseded; latest заново проверен, нет catch-up публикации всех устаревших цен |
| FXA-64 | Concurrent membership/source/context changes при manifest capture | Согласованный snapshot либо явный отказ; не смешанные страницы |
| FXA-65 | Policy/anchor/context изменились между preview и commit | Старый результат не даёт права записи; требуется актуальный approved context |
| FXA-66 | DRY_RUN делит ограниченный budget между targets | Virtual consumption детерминирован; AUTO revalidation объясняет допустимое отличие |
| FXA-67 | Caller передал validated=true/поддельный calculation | Canonical port перепроверяет authority и limits; ноль чужих writes |
| FXA-68 | Товар+услуга с единой пакетной ценой | Нет частичной независимой переоценки пакета |
| FXA-69 | PRODUCT и qualified SERVICE конкурируют за tenant budget | Единый budget не превышен; две цены остаются у своих owners |
| FXA-70 | Повторный enrollment/run и собственный rowVersion | Нет duplicate successful event и повторного начисления процента |
| FXA-71 | PARTIAL_SUCCESS, deferred, dead letter, retry exhaustion | Непересекающиеся counts в сумме total; незавершённое не помечено COMPLETED |
| FXA-72 | 10k targets, несколько tenants, bursts, competing sellers | Измерены p95/p99/queue/DB budget; fairness и bounded transactions подтверждены |
| FXA-73 | Lease CAS/time boundary, delayed commit и concurrent stop | Выполняется точная linearization semantics §8, не эвристика по JVM clock |
| FXA-74 | PITR потерял поздний revoke/ACK/source state | AUTO OFF; reconciliation/current authority approval до resume; old leases invalid |
| FXA-75 | Миграция старой ручной истории и rollback deployment | Human provenance не SYSTEM; нет retroactive AUTO consent, manual flow совместим |
| FXA-76 | Archive/retention убрал необходимый baseline | HOLD без выдуманной цены; idempotency и history references сохранены |
| FXA-77 | Генерируемые допустимые числа и независимые math invariants | Детерминизм, monotonicity фиксированного context, rounding bounds, nominal equivalence; без вызова production calculator как oracle |
| FXA-78 | Точные границы amount/%/window и минимальные decimal steps | Inclusive caps и оба minChange порога; нет early percentage rounding |
| FXA-79 | JVM drift/DB time anomaly/expiry boundaries | DB authoritative; подозрительное время HOLD, stop/lease не обходятся |
| FXA-80 | Недостающий threshold/SLO/gate; ручной вызов live process; неподдержанный HOLD_NEW_SALES | Activation denied; manual invocation не обходит live gates и не включает unavailable capability |

**Evidence contract:** для каждого applicable ID сохраняются requirement IDs, test class.method, independent oracle, source SHA, DB/migration version, fixture/seed, GitHub Actions run/artifact, Surefire failures/errors/skips и reviewer conclusion. Independent oracle проверяет канонические price revisions, receipt, budget ledger, order/quote hashes и tenant isolation через отдельные запросы; не повторяет implementation evaluator.

PG races используют две реальные transactions и, где важен process/lease/clock boundary, два worker processes. Оба порядка управляются barriers/latches/SQL locks; sleep сам по себе не доказательство. Crash tests делают fault injection в обозначенных окнах, lost response отдельно от rollback.

Все required H2/PostgreSQL/Security/RustFS/SALES/QR jobs проходят на одном SHA; набор берётся из фактического workflow, без удаления существующих обязательных suites. Наличие JUnit метода или зелёного неполного workflow не означает PASS.

Service-specific positive cases FXA-39/69 до включения соответствующего adapter имеют статус DEFERRED_NOT_IN_RELEASE с owner/reason, а не PASS. Deny unsupported services FXA-04/37/38/45/80 обязателен уже для PRODUCT. G5 нельзя закрыть исключением positive cases.

## 14. Трассируемость требований

| Requirement | Обязательный инвариант | Разделы | Минимальные FXA evidence |
|---|---|---|---|
| R2C-REQ-01 | Единственный canonical price owner | 1, 3, 8, 10, 11 | 29, 37–39, 43, 67 |
| R2C-REQ-02 | Tenant-qualified scope, no implicit future/type expansion | 3 | 03, 04, 45 |
| R2C-REQ-03 | Current identity/OWNER/domain permissions | 4 | 01, 02, 08, 28, 30, 47 |
| R2C-REQ-04 | Immutable consent с точным payload и сроком | 4 | 02, 07, 46, 65 |
| R2C-REQ-05 | Strict inheritance и отсутствие случайного winner | 3 | 05, 06 |
| R2C-REQ-06 | PRICING_ANCHOR и KZT-only public price | 2, 5 | 09, 18, 48 |
| R2C-REQ-07 | Qualified immutable source/calendar/correction | 7 | 17–20, 36, 56, 59, 62, 64 |
| R2C-REQ-08 | Общая versioned decimal mathematics | 5 | 09–12, 77, 78 |
| R2C-REQ-09 | Post-rounding delta/band/rolling limits | 5, 6 | 13–16, 52–55, 78 |
| R2C-REQ-10 | Атомарный общий budget без компенсации знаков | 6 | 49–51, 54, 69 |
| R2C-REQ-11 | DRY_RUN воспроизводим и не меняет коммерческие данные | 9 | 21–24, 66 |
| R2C-REQ-12 | Полный bounded manifest, coherent snapshot | 9 | 22, 24, 64 |
| R2C-REQ-13 | Один successful semantic price event | 9, 10 | 31, 34, 36, 70 |
| R2C-REQ-14 | Durable stop/lease fencing | 4, 8, 9 | 08, 32, 35, 73 |
| R2C-REQ-15 | Crash/retry/accounting без ложного успеха | 8, 9 | 33, 34, 63, 71 |
| R2C-REQ-16 | Checkout/quote/order/inventory immutability | 8 | 25–27, 42 |
| R2C-REQ-17 | Manual override и честная provenance | 4, 8, 10 | 27, 29, 55, 75 |
| R2C-REQ-18 | Shock HOLD и явная семантика старой цены | 7 | 57–60, 80 |
| R2C-REQ-19 | Durable incidents и измеримая реакция | 7, 12 | 61, 62, 71, 80 |
| R2C-REQ-20 | Независимый admission услуг/пакетов | 3, 12 | 38, 39, 43–45, 68, 69 |
| R2C-REQ-21 | Безопасная additive migration и retention | 10 | 40, 75, 76 |
| R2C-REQ-22 | Restore не возвращает потерянное разрешение | 9 | 74 |
| R2C-REQ-23 | Bounded capacity и доверенное время | 8, 12 | 72, 73, 79, 80 |
| R2C-REQ-24 | Exact-SHA и отдельные pilot/release authorizations | 0, 12, 13 | 41, 42, 80 |

### 14.1. Сохранённые пользовательские сценарии R1.0

| Journey ID | Действие и наблюдаемый результат | FXA evidence |
|---|---|---|
| FXA-J01 | Owner выбирает все допустимые товары, видит точный scope и исключения до consent | 01, 03, 22, 23 |
| FXA-J02 | Owner утверждает валюты/формулу/лимиты и получает immutable revision | 02, 07, 09, 46, 48 |
| FXA-J03 | Выбран явный набор либо квалифицированная группа; остальные цели не затронуты | 03–06, 64 |
| FXA-J04 | Seller исключает offer или меняет цену вручную; AUTO её не перезаписывает | 05, 27, 55, 70 |
| FXA-J05 | Qualified новая FX revision создаёт воспроизводимые расчёты только своей валюты | 12, 17–20, 24, 64 |
| FXA-J06 | Допустимый candidate публикуется без повторного клика, один раз | 13, 14, 29, 31, 34 |
| FXA-J07 | Лимит или шок запрещает публикацию; цена сохранена, причина и incident доступны | 15, 23, 57–61 |
| FXA-J08 | Из 10 000 независимых целей проходит только допустимая часть; отчёт точен | 22, 49–51, 71, 72 |
| FXA-J09 | Owner выключает AUTO/теряет полномочия; новые commits прекращаются | 08, 28, 32, 47, 73 |
| FXA-J10 | Источник недоступен/просрочен/неаттестован; нет выдуманного курса | 17–20, 59, 62 |
| FXA-J11 | Покупатель оформляет заказ во время изменения; согласованная версия защищена | 25, 26, 42 |
| FXA-J12 | PRODUCT работает отдельно; услуги включаются только своим qualified adapter | 37–39, 43–45, 68, 69 |
| FXA-J13 | Повтор batch/crash после commit возвращает прежний receipt | 24, 31, 33–36, 70, 74 |
| FXA-J14 | Seller видит old/new, source, policy, authorizer, executor и причины | 23, 29, 71, 75, 76 |
| FXA-J15 | Новая позиция/новый тип не включается автоматически вне подтверждённого includeFuture | 03, 45, 80 |

## 15. Обязательные результаты Contract Review и параметры активации

### 15.1. Что нужно закрыть до разработки соответствующего slice

| Артефакт review | Ответственный | Проверяемый результат |
|---|---|---|
| Price-owner map и typed context | Tech Lead + владельцы STOREFRONT/SALES | PRODUCT contract; negative service boundaries; место будущих service anchors без fictitious offers |
| IAM/consent map | Security + IAM + Product Owner | OWNER+grants, current authorizer checks, canonical payload, approval/revoke/transfer race plan |
| PRICING_ANCHOR compatibility | Currency owner + Tech Lead | Additive migration и строгий admission нового AUTO, сохранённая legacy semantics |
| Lock DAG и budget transaction | Tech Lead + DB reviewer | Все реальные lock paths, linearization points и двухтранзакционные fixture plans; отсутствие цикла |
| Risk profile и старые цены | Product Owner + merchant representative | Явно принят KEEP_LAST риск либо отказ от live AUTO до другого qualified профиля |
| Source/risk reference admission | FX source owner | Bootstrap, календарь, correction ordering, independent evidence и release authority |
| Alert/operations dependencies | Operations + Product Owner | Реально доступный qualified channel либо отдельно разрешённый attended режим; не фиктивная готовность R3.2B |
| Evidence/release matrix | QA + Tech Lead | FXA→methods/oracles/jobs; applicable/deferred scope; точный candidate SHA |

Запрос «доработать ТЗ» не заполняет эти review records от имени ответственных и не снимает implementation/production HOLD. При review используются конкретные артефакты и решения; «согласовано устно» без traceable record не закрывает gate.

### 15.2. Полный activation checklist параметров

Перед активацией обязательны: allowed target/owner types; exact selector/template version; includeFuture; max target scope; authoritative anchor и reviewDueAt; unit/priceBasis/tax semantics; formula/buffer/markup/rounding; positive min/max price; step increase/decrease; оба minChange; cooldown; rolling W/net up/net down/absolute variation/count; tenant Wb/gross unit-price variation/count и per-run caps; источник и rate freshness/calendar policy; normalized-rate shock thresholds/horizon/reference; consent validFrom/validUntil; risk profile acknowledgement; worker concurrency/lease/retry/timeouts; alert/response/escalation; operational capacity и restore parameters.

Для first PRODUCT release includeFuture остаётся false, пока отдельно не qualified template. fixed-day limits необязательны; при включении нужны zone/boundary/baseline и тесты. Unavailable capability нельзя активировать ненулевым config. Null обязательного значения, нарушение объявленного диапазона (minChange явно допускает 0), пустое пересечение, неизвестная unit или отсутствующий owner evidence => validation error/HOLD. Конкретные проценты и сроки задаёт уполномоченный владелец в пределах квалифицированного deployment envelope.

### 15.3. Пять вопросов для финального review

1. Если FX вырос на 30%, а разрешённая цена только на 10%, кто и за какое время реагирует на продажи по старой цене; принято ли это явно?
2. Может ли общий context описать PRODUCT UUID и PACKAGING serviceCode, не создавая фиктивный offer, второй calculator и общий незащищённый FK?
3. Какая transaction не даст двум workers потратить по 60 из остатка 100; что происходит после lost ACK, нового run и смены window?
4. Какая актуальная membership/permission/evidence разрешает worker действовать после OWNER transfer; какой commit окончательно останавливает публикации?
5. Какие gates препятствуют ручному live apply до qualified source/operations/pilot authorization и как это проверено независимо от scheduler flag?

## 16. Редакционная приёмка R1.1

Из R1.0 сохранены KZT-only, canonical owner, формула, независимый PRODUCT-first, service qualification, bounded transactions, consent, immutable history, no clamping, manual override, idempotency, fencing, source correction и exact-SHA стандарт.

Уточнены: owner не wildcard; PRICING_ANCHOR обязателен для AUTO; типы и unit/tax context; нулевая/смешанная услуга; finite scope и новые adapters; consent payload/expiry; rolling baseline/variation и общий бюджет; shock vs sale admission; alert dependency; coherent DRY_RUN; semantic dedupe across runs; lease linearization; PITR OFF; отдельные pilot/release gates; проверяемые requirements и oracles.

**Текущее доказанное состояние:** подготовлена редакция требований, а не исполнение 80 сценариев. Реализация, численные эксплуатационные обещания, независимые business/security/legal approvals и live активация этим документом не подтверждены.



