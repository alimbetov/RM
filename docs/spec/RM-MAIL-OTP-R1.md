# RM-MAIL-OTP-R1 — Email Verification OTP, Mail Outbox & Localized Templates

**Статус:** R1.1 REVIEWED SPEC / DESIGN ONLY (R1 preserved, acceptance amended). Дата: 2026-11-21. Ветка: base_dev. Java implementation: NOT STARTED. Production/public HTTP admission: HOLD.
**Review R1.1:** уточнение общих лимитов, методов подтверждения, семантики доставки, локализации и эксплуатационных acceptance gates; принятые изменения изложены в §15 и имеют приоритет при расхождении с ранними разделами. Ничего из §15 не заявляется реализованным.
**Нормативная основа:** docs/architecture/RM-PLATFORM-STANDARD-R2.md; docs/spec/RM-IAM-PII-R2.5-GLOBAL-PROFILE-EMAIL-VERIFICATION.md.
**Принцип реализации:** без контроллеров и реального email-провайдера на текущем этапе; только сервисы, типизированный mail-port, шаблоны, mock/fake, H2/PostgreSQL qualification. Следующий public adapter — отдельный gate.

## 1. Цель
Добавить удобное подтверждение email шестизначным одноразовым кодом к имеющемуся IAM email verification; подготовить надёжную отправку через PostgreSQL transactional outbox и заменяемый адаптер KumoMTA HTTP API; создать локализованные версионированные шаблоны для OTP, будущих приглашений, напоминаний и транзакционных уведомлений.

Сценарии регистрации, CHECKOUT и CHANGE_EMAIL должны использовать один источник истины для email identity и verification status. Подтверждение email не выдаёт OIDC-сеанс, tenant role, права на заказ или global-admin.

Название «kumota» интерпретируется как KumoMTA; фактическая конфигурация поставщика допускается только после отдельной интеграционной проверки. Документация KumoMTA: https://docs.kumomta.com/reference/http/kumod/api_inject_v1_post/ .

## 2. Зафиксированные продуктовые решения
| Параметр | R1 |
|---|---|
| Код | Шесть ASCII цифр, строка 000000–999999, сохранять ведущие нули; CSPRNG |
| TTL | 10 минут с created_at, серверное UTC время |
| Допустимые проверки | 3 ошибки на challenge; дополнительно максимум 9 ошибочных вводов на identity+purpose в скользящий час (§15.1) |
| Повторное письмо | Cooldown 60 секунд; максимум 3 дополнительных выдачи в течение часа на identity/purpose |
| Защита от обхода попыток | Shared PostgreSQL rolling failure quota по identity+purpose + адресные/сетевые лимиты на будущей HTTP-границе; resend не сбрасывает счётчики (§15.1) |
| Успех | Атомарная одноразовая фиксация proof + разрешённого lifecycle-перехода + audit |
| Public HTTP (в будущем) | POST, код в JSON body; никогда GET-параметр/URL |
| Языки | en, ru, kk, zh-Hans; default en |
| Провайдер | MockMailTransport R1; KumoMtaTransport после admission |
| Текущие токены | Сохранить существующие 256-bit URL/bearer token paths и идентификаторы; OTP — дополнительный proof method |
| SMTP/KumoMTA / real OIDC / public controllers | HOLD до отдельного release gate |

Параметры R1 должны быть конфигурируемыми с безопасными верхними/нижними ограничениями и иметь воспроизводимые тесты. Подтверждение кода в пределах TTL определяется строго как now < expires_at, а не интервал с расширением по допуску часов.

## 3. Границы архитектуры
- IAM владеет email identity, challenge, статусов PENDING/CURRENT/HISTORICAL, verified_at и доказательством проверки.
- Почтовая доставка — общий технический компонент RM, но не шестнадцатый бизнес-домен и не второй IAM источник истины.
- R1 переиспользует public.iam_email_delivery_outbox для OTP/verification, расширяя её только необходимыми полями. Не создавать параллельный OTP outbox.
- Общие MailTemplateRenderer, MailTransportPort, MailDispatcher допускают будущие template types. При появлении реальных вторых producer-доменов (TASKS/ORG/MARKETING) отдельно проектируется один generic mail outbox с контролируемой миграцией, без дублирования данных и двух конкурирующих dispatchers.
- Не вводить Redis, Kafka, сторонний workflow-engine, отдельный mail microservice, UI template builder или новый authorization engine.
- Изменения схемы только additive Liquibase. Применённые changesets 0014–0051 не исправлять задним числом.

### Логическая схема
Caller (trusted IAM / checkout) → EmailChallengeService (TX) → challenge + IAM mail outbox (same TX)
→ MailOutboxDispatcher (claim/lease) → MailTemplateRenderer (en/ru/kk/zh-Hans)
→ MailTransportPort → Fake/Mock transport (R1) или KumoMTA HTTP API (future).

Code verification → EmailOtpVerificationService (transactional lock + attempts)
→ existing verified email state transition / proof event → result. Для CHANGE_EMAIL необходим verified actor и существующий change-email flow.

## 4. Challenge, секреты и lifecycle
4.1. Каждая challenge привязана к email_identity_id, purpose (REGISTRATION, CHECKOUT, CHANGE_EMAIL), verification_method (LINK_TOKEN или EMAIL_OTP), created_at, expires_at, consumed_at, статусу и попыткам.
4.2. Existing LINK_TOKEN proof: сохраняет текущую криптографическую схему; не переиспользовать шестизначную OTP-ветку для 256-bit tokens.
4.3. EMAIL_OTP proof: генерировать SecureRandom; проверять HMAC-SHA-256 с самостоятельным секретом и доменным префиксом, включающим challengeId, identityId, purpose и нормализованный шестизначный код. Не использовать SHA-256(code) из-за перебора пространства 10^6. Секрет ключа — не tenant DEK и не plaintext в БД. Версионировать key ID для возможного key rotation.
4.4. До отправки OTP требуется кратковременно восстановить его из зашифрованного delivery envelope под глобальным IAM ключом с AEAD AAD (challengeId, identityId, purpose, method, version). Не использовать обычный generic mail outbox payload, из которого другой tenant или сервис может получить код. Не хранить OTP в outbox plaintext, не записывать в логи, trace, audit, exception, метрики, HTTP response. После доставки/истечения/исчерпания retries удалять или криптографически уничтожать пригодный к расшифрованию delivery payload по retention policy.
4.5. После успешного proof: consumed_at + verified_at/current identity transition + append-only verification event в одной PostgreSQL транзакции. Нельзя подтверждать другой email, другую purpose, HISTORICAL identity, заблокированный аккаунт или выдавать чужие заказы.
4.6. После третьей неправильной попытки: challenge permanently invalid; новые попытки DENY. Resend после cooldown выпускает новую OTP challenge, инвалидирует прежнюю для данной identity/purpose/method и не переносит право владения email. Отложенные/outbox задачи старого challenge нельзя отправлять после аннулирования.
4.7. Некорректная попытка должна увеличивать attempt_count в зафиксированной транзакции. Не бросать unchecked exception после UPDATE так, чтобы транзакция откатила запись счётчика. Конкурирующие проверки сериализовать по challenge и связанному lifecycle; PostgreSQL two-connection tests обязательны. Стабильный порядок захвата блокировок не должен конфликтовать с существующим CHANGE_EMAIL и checkout.
4.8. Rate limiting: per identity/purpose, per IP и per device/session на будущей HTTP-границе, включая запросы новых кодов. Требовать generic responses без account enumeration; Redis для этого этапа не нужен. Защита не должна обнуляться при resend.
4.9. Невозможна implicit emailVerification от OIDC claim. Для CHANGE_EMAIL обязательны VerifiedActor и впоследствии recent-auth / step-up MFA; публичный анонимный challenge никогда сам по себе не меняет email уже авторизованного пользователя.

## 5. Сервисные контракты (R1, без контроллеров)
- requestEmailOtp(context, emailIdentity, purpose, locale) → opaque challenge reference / delivery queued result, без кода; actor/purpose проверяются владельцем IAM.
- verifyEmailOtp(context, challengeId, sixDigitCode) → VERIFIED | INVALID | EXPIRED | LOCKED | UNAVAILABLE и attemptsRemaining при допустимой безопасной модели. Не возвращать чужой userId.
- resendEmailOtp(context, currentChallengeId) → новый активный challenge после throttling, предыдущий revoked; neutral response.
- MailTemplateRenderer.render(templateKey, locale, typedValues) → immutable rendered subject, text body, HTML body.
- MailTransportPort.send(renderedMessage, deliveryId) → provider accepted/retryable/permanent; сам provider contract скрыт за adapter.
- MailOutboxDispatcher.processDueBatch(limit) → bounded claim/lease, failure-safe transitions.

Не дублировать подтверждение identity в отдельном OTP сервисе: после успешного сравнения кода использовать одну каноническую lifecycle-policy (выделенный внутренний port/метод), существующую для LINK_TOKEN.

## 6. Будущий HTTP-контракт — только SPEC, не реализовывать сейчас
- POST /public/v1/email-verifications/request {email, purpose=REGISTRATION, locale?} → 202 Accepted, нейтральный статус. Сервер проверяет, допустима ли публичная REGISTRATION; не возвращает существование аккаунта или OTP.
- POST /public/v1/email-verifications/{challengeId}/verify {code:"012345"} → 200 generic success or безопасная ошибка/429 без идентификационных утечек; не GET.
- POST /public/v1/email-verifications/{challengeId}/resend → 202 generic response либо 429 с Retry-After при лимите.
- В CHECKOUT initiate через доверенный checkout port, не через возможность публично выбрать identity/tenant. CHANGE_EMAIL только через authenticated VerifiedActor + current session + step-up перед public admission.
- У всех future response Cache-Control: no-store, Referrer-Policy: no-referrer; секреты не в URL, заголовках telemetry и ошибках. DoS/rate limit, CORS/CSRF, abuse detection и TLS проверяются отдельно.

## 7. Transactional outbox и доставка
- Текущий IAM outbox остаётся источником delivery intent для verification. Создание challenge + запись outbox атомарно.
- Dispatcher выбирает due rows с PostgreSQL SKIP LOCKED и краткоживущим lease/owner/fencing; реальные HTTP-запросы вне долгой транзакции; статус по результату под lease ownership. Повторно проверять challenge перед обращением к провайдеру и помнить, что письмо, уже принятое провайдером, нельзя отозвать (§15.3).
- Отслеживать PENDING / IN_FLIGHT / SENT / RETRY / FAILED / EXPIRED с попытками, next_attempt_at, provider accepted timestamp и sanitized error category. Существующая SQL CHECK constraint расширяется новым Liquibase changeset, без изменения старого.
- HTTP 429, 5xx, network timeout → bounded exponential backoff with jitter, максимум 5 transport attempts (отдельно от 3 OTP input attempts). Permanent 4xx/config/template validation → FAILED, без бесконечного retry.
- Запретить отправку истёкшего, поглощённого или superseded OTP; проверить expiry повторно непосредственно перед send.
- Доставка at least once: при падении после provider accept и до DB commit возможна дублирующая отправка. Передавать стабильный delivery/message ID; использовать provider idempotency только если она реально поддержана. Не заявлять exactly-once.
- Provider accepted означает «принято в очередь», а не доставлено адресату. Bounces, delivery callbacks и suppression list — поздний этап.
- В FakeTransport записывать только безопасный факт отправки/тип шаблона; в строго тестовом режиме тесту может быть доступно содержание через изолированный test fixture, не production logging.

## 8. Интеграция KumoMTA (после R1)
KumoMTA документирует POST /api/inject/v1 с полями envelope_sender, recipients, content; результат содержит success_count, fail_count и failed_recipients. R1 не должен зависеть от конкретной wire-схемы вне KumoMtaTransport. Для одинакового результата в mock/real provider шаблоны рендерит RM, а не KumoMTA. В KumoMTA передаётся уже собранное text/html письмо, с проверенным envelope sender, заголовками и безопасными trace ID без PII/OTP.

Параметры transport: HTTPS/закрытая сеть, authentication (по deployment), timeouts, connection pool, configured sender domain, SPF/DKIM/DMARC вне Java; credentials только из secret manager/env. Обязательно проверить действующую редакцию provider API перед интеграцией. На этапе R1 провайдер полностью замокан.

## 9. Локализованные шаблоны
Шаблоны R1 — versioned source files в src/main/resources/mail/<templateKey>/<locale>/{subject.txt,body.txt,body.html}; никакой БД/редактора шаблонов пока нет.

| templateKey | Класс | Обязательные placeholders | R1 |
|---|---|---|---|
| EMAIL_VERIFICATION_CODE | Security / transactional | otpCode, expiryMinutes, appName | Реализовать en/ru/kk/zh-Hans |
| EMAIL_CHANGE_CODE | Security / transactional | otpCode, expiryMinutes, appName | Реализовать en/ru/kk/zh-Hans |
| ORGANIZATION_INVITATION | Transactional | organizationName, inviterName, actionUrl, expiryDate | Подготовить контракт/шаблон, без бизнес-dispatch |
| TASK_REMINDER | Transactional | taskTitle, dueDate, actionUrl | Подготовить контракт/шаблон, без бизнес-dispatch |
| SYSTEM_NOTIFICATION | Transactional | eventTitle, message, actionUrl? | Подготовить контракт/шаблон, без бизнес-dispatch |

Locale: явное доверенное предпочтение получателя → проверенный параметр соответствующего события → контекст организации (только не для глобального IAM профиля) → en. Accept-Language учитывается лишь как предложение на будущей HTTP-границе. Если нужный перевод отсутствует, subject + text + HTML целиком переключаются на английский, без смеси языков. Шаблон должен содержать локализованные полноценные предложения и корректные форматы дат, чисел, time zone, plural forms. Для kk и ru требуются осмысленные формулировки, не механическая замена слов.

Placeholders: именованный allowlist по templateKey, типизация, required/optional, проверка пропущенных и лишних переменных; HTML escape по умолчанию, безопасное text rendering; URL только по allowlisted HTTPS host/scheme, никакие произвольные html/headers/template expressions из пользовательского ввода. Не выводить PII в subject/trace. Максимальные размеры текста/полей ограничены. Каждый template version имеет однозначное имя и снабжён unit tests.

Security messages (OTP) не являются marketing, подписка/consent для них не требуется. Для будущих маркетинговых писем обязателен отдельный MARKETING consent/unsubscribe/anti-spam контракт; это не часть MAIL-OTP-R1.

## 10. Пример базового английского шаблона
**Subject:** Your RM verification code
**Plain text:**
Your verification code is {otpCode}.
It expires in {expiryMinutes} minutes.
If you did not request this code, you can ignore this email.
Never share this code with anyone.
**HTML:** семантическая вёрстка с role/text, крупным кодом, понятной подписью и plain-text alternative. Без web trackers и внешних обязательных изображений.

## 11. Минимальные изменения persistence / backward compatibility
- Добавить в iam_email_verification_challenge method discriminator, OTP HMAC/key ID, attempts/lock state и технические поля revocation. Учесть legacy NOT NULL token_sha256 и уникальность: через additive migration, backfill LINK_TOKEN, затем DB CHECK для взаимно исключающих видов proof; старые записи не должны перестать читаться.
- Отдельно зашифрованный временный delivery secret envelope или новый versioned codec, совместимый со старым token_envelope, с AAD по challenge+purpose. Сначала доказать decrypt старого, затем подключить OTP. Не хранить короткий код как обычный SHA256 hash.
- Добавить в iam_email_delivery_outbox минимальные поля claim/lease, retry/error и template key/locale/version, если необходимы для R1. Не дублировать user email в plaintext.
- Исторические iam_email_identity, public.iam_email_verification_event, ORDER foreign keys и PENDING/CURRENT/HISTORICAL остаются стабильны. Изменять лишь добавочными SQL-migration и тестами populated-DB upgrade; CI clean DB не заменяет миграцию существующих данных.
- Retention и зачистка временных ciphertext/OTP payload после terminal state обязательны; append-only proof evidence не содержит код.

## 12. Qualification / тесты (без внешнего провайдера)
| ID | Сценарий | Критерий |
|---|---|---|
| OTP-01 | SecureRandom, 6 цифр и ведущие нули | Формат, равномерная генерация без предсказуемого seed |
| OTP-02 | Верный код до expiry | VERIFIED один раз, verified_at/audit в одной TX |
| OTP-03 | 3 неверных кода | attempts=3 committed, затем LOCKED |
| OTP-04 | Верный код после TTL / consumed / revoked | DENY, нет verified_at |
| OTP-05 | resend после 60 sec | новый код; старый отклоняется, лимит не сбрасывается |
| OTP-06 | другой purpose/email/identity/actor | DENY, никакого claim другого buyer |
| OTP-07 | 2–10 одновременных verify | максимум один successful proof |
| OTP-08 | CHANGE_EMAIL A→B + historical orders | старый CURRENT до proof; после proof новый CURRENT; FK сохранены |
| OTP-09 | suspended account / cross-tenant access | DENY и одинаковые безопасные ответы |
| OTP-10 | HMAC key отсутствует / bad envelope / tampered AAD | fail closed, ни одной plaintext записи |
| MAIL-01 | challenge + outbox transaction rollback | ни одной самостоятельной orphan delivery |
| MAIL-02 | mock provider OK / timeout / 429 / 5xx / 4xx | retryable/permanent split, bounded retries |
| MAIL-03 | 2 dispatchers, SKIP LOCKED, crash-after-accept | no double-claim; duplicate delivery documented |
| MAIL-04 | expired/superseded delivery | не отправляется после проверки актуальности |
| TPL-01 | 5 template keys × 4 locales | subject/text/html, fallback en, no unresolved placeholders |
| TPL-02 | HTML injection, unsafe links, Unicode | escaping, URL allowlist, корректные kk/ru/en/zh-Hans |
| TPL-03 | missing/extra variables, incorrect date formatting | fail closed, ни одного malformed mail |
| OPS-01 | PostgreSQL populated upgrade/rollback + exact-head H2/PG CI | сохраняются legacy challenges и письма, CI green |

Прогонять с управляемыми Clock и SecureRandom/фикстурами; проверять count/locks реальным PostgreSQL, не sleep-based race simulations.

## 13. Поэтапная реализация и gates
1. R1.A — Service-only OTP: challenge schema, HMAC, 3 attempts, TTL, resend, каноническая IAM lifecycle policy, PG concurrent tests. Никаких контроллеров.
2. R1.B — Mail core: versioned templates, renderer en/ru/kk/zh-Hans, FakeTransport, IAM outbox dispatcher with lease/retries and expiry filters. Никакого внешнего HTTP.
3. R1.C — Integration readiness: KumoMTA adapter за отключённым feature flag, mock HTTP contract tests, безопасная конфигурация, filled-PG upgrade, security review.
4. R1.D — Отдельный release candidate: реальный provider, SMTP/domain authentication, OIDC/BFF, публичные POST adapters, anti-abuse, browser/E2E and independent security sign-off. До этого production/public API HOLD.
5. R2 (future) — invitations/reminders/notifications use same MailTransportPort and templates; generic outbox only при появлении второго реального producer, campaigns только после consent rules.

### Definition of Done для внутреннего R1
- Нет GET для OTP и нет public controllers.
- Email verified только после точного single-use/within-TTL proof, не по факту доставки.
- 3 неуспешных попытки и resend throttling доказаны в двухсессионных PostgreSQL тестах.
- Existing 256-bit link verification, current/pending/historical and buyer-order history регрессионно целы.
- Шаблоны для четырёх локалей и FakeTransport квалифицированы, OTP/PII не появляются в логах/SQL plaintext/outbox metrics.
- Exact-head H2+PostgreSQL GREEN + отдельный populated-DB migration rehearsal.
- **Production admission остаётся HOLD**, пока нет реального OIDC/SMTP/HTTP и independent security approval.

## 14. Исключения R1
Не создавать дополнительные домены, новый frontend, email-template CMS, Redis, Kafka, live mailing campaigns, SMS OTP или универсальную коммуникационную оркестрацию. Не открывать реальные входящие API и не включать реальную отправку в составе этого ТЗ.

## 15. R1.1 — обязательные дополнения по итогам технического и security review

### 15.1. Модель угроз, три разных вида retry и защита от перебора

**Цель OTP — подтвердить контроль над почтовым адресом. EMAIL_OTP не является OIDC-login, вторым фактором (MFA) или самостоятельным механизмом восстановления привилегированного аккаунта.** NIST SP 800-63B запрещает email как out-of-band authenticator для аутентификации; подтверждение адреса посредством email-кода является отдельной допустимой функцией. Ни один OTP endpoint не создаёт сессию, не разрешает обход VerifiedActor, recent-auth или tenant RBAC. См. https://pages.nist.gov/800-63-4/sp800-63b.html и https://cheatsheetseries.owasp.org/cheatsheets/Email_Validation_and_Verification_Cheat_Sheet.html .

Различать **три независимые величины**:
1. **Ввод кода:** не более 3 ошибочных вводов на challenge. Третья ошибка атомарно делает challenge LOCKED; неверные попытки записываются в PostgreSQL даже когда проверка закончилась неуспехом.
2. **Повторная выдача:** первая выдача + максимум 3 resend в скользящий час на identity+purpose, не чаще раза в 60 секунд. Новая выдача не сбрасывает shared failure quota. В дополнение ко всем challenge установить **максимум 9 ошибочных вводов в скользящий час на identity+purpose** с блокировкой новых проверок до истечения окна; хранить/обновлять квоту в PostgreSQL с конкуренцией, без Redis.
3. **Повторная доставка провайдеру:** максимум 5 HTTP transport attempts на delivery, независимо от OTP tries и resend. Транспортные retries не создают новую OTP challenge и не продлевают expires_at.

Успех OTP после committed LOCKED, shared-quota exhaustion или expires_at невозможен. Применять криптографическое сравнение MAC в constant-time, fixed-size HMAC и bounded queries; код строго `^[0-9]{6}$` без trim, Unicode цифр, строкового toInteger и числового JSON поля. Любой код может начинаться с `0`. Не раскрывать индивидуальные состояния аккаунта по публичным ответам. На будущем HTTP-edge лимиты по IP, сети, устройству, адресу и корреляции запросов; CAPTCHA лишь опциональная мера, не замена серверным лимитам. Разрешить безопасный recovery UX после блокировки через cooldown и новый challenge, но *никогда* не обходить global failure quota.

**Ошибочная попытка и транзакционность:** при incorrect code increment производится в отдельной явно commit-able ветви, после чего возвращается ожидаемый result, а не unchecked exception, вызывающий rollback. Проверять три конкурентных неправильных кода, тройку wrong/wrong/correct и гонку correct/expire/revoke. Транзакционное время сравнивать в PostgreSQL или через единый Clock с контролируемым skew; правило `now < expires_at` неизменно. При locked challenge последующие проверки не увеличивают счётчик без ограничения.

### 15.2. Совместимость с существующей IAM R2.5 (особенно CHANGE_EMAIL/CHECKOUT)

- **Действующий LINK_TOKEN сохраняет TTL 15 минут, формат и decrypt/verify API; OTP имеет TTL 10 минут.** Никакой автоматической замены прежнего `EmailIdentityService.verifyRegistration/verifyEmail/confirmEmailChange` новыми несовместимыми методами.
- Выделить один внутренний `EmailVerificationLifecyclePolicy` / narrow port, применяющий подтверждение identity для обоих методов. Он проверяет account status, purpose, owner, pending reason, generation и immutable email identity; LINK_TOKEN и EMAIL_OTP различаются только проверкой секрета.
- Для смены адреса `CHANGE_EMAIL` proof требует *оба* доказательства: соответствующий действительный challenge и активный `VerifiedActor` владельца, а на будущей HTTP-границе recent-auth. Код из чужого email не переводит CURRENT/HISTORICAL identity и не даёт прав на чужой аккаунт.
- После первого успешно committed proof того же intent/generation sibling methods/challenges не должны повторно вызвать side effects, повторный audit или повторное buyer claim; выбрать и протестировать семантику инвалидации sibling challenges. При этом независимые одновременно идущие checkout orders одного покупателя не должны потерять историю из-за глобального отзыва всех checkout challenge без предметного правила.
- При `CHECKOUT` не выдавать userId в публичном response, не менять `pending_email_identity_id` и `buyer_user_id` в старом заказе по одному совпадению email. CHECKOUT receipt/order ownership остаётся у SALES, не у mail worker. Не допускать linking с HISTORICAL адресом.
- Перед реализацией зафиксировать диаграмму захвата блокировок существующих `EmailIdentityService`, `AutoOrderService` и будущего OTP: lock email stripe/identity/account/challenge в одинаковом порядке; при разных путях использовать двухсоединительные PostgreSQL сценарии, исключающие deadlock и stale state.
- Additive DB migration: `token_sha256` и `token_envelope` в legacy challenge имеют NOT NULL/UNIQUE. В OTP-строках нельзя создавать фиктивные или слабые токены, чтобы обойти constraint. Добавить method discriminator, nullable versioned columns и DB CHECK с двумя корректными взаимоисключающими формами, мигрировав legacy rows в LINK_TOKEN с backfill; подтвердить свежую PG и upgrade заполненной PG. Не редактировать применённые changesets.
- При компрометации OTP HMAC-key: fail-closed, аварийная остановка выдачи/проверки, контролируемая ротация ключа и ревокация только затронутых challenges. Секретная версия ключа должна быть известна verifier; ключи не переносятся в tenant KMS и не возвращаются из API.

### 15.3. Состояния доставки, пользовательский опыт и защита от устаревших писем

- Отправлять OTP только если challenge ACTIVE, identity/purpose не изменились и до expiry остаётся достаточно времени: **минимум 120 секунд на момент injection**. Если меньше — закончить delivery как EXPIRED, не продлевать код автоматически; в UX предложить запросить новый с соблюдением лимитов. Параметр min_remaining_ttl_seconds = 120, отдельно от OTP TTL=600.
- Не делать delivery раньше commit challenge+outbox. При задержке провайдера, превышающей TTL, письмо уже может прийти, но код остаётся INVALID. Пользователь должен видеть ясное сообщение «истёк/запросите новый» без раскрытия существования аккаунта.
- Resend revoke старый OTP и отменяет ещё не отправленные задания; in-flight доставка *может* попасть пользователю после revoke — попытка применить код всегда DENY. Не обещать возможность физического отзыва уже принятого письма. Проверка актуальности перед transport + fencing на финализации результатов обязательны, но не являются exactly-once delivery.
- Старые LINK_TOKEN и новые OTP могут по-разному сосуществовать. Выбранный вариант feature-toggle `rm.iam.email-otp.enabled` по умолчанию false; rollout отдельно для REGISTRATION, CHECKOUT, CHANGE_EMAIL после каждого квалификационного теста. Выключение flag блокирует создание новых OTP, но требует документированной политики завершения уже выданных challenge без security bypass.
- Предусмотреть идемпотентный request key на доверенном entrypoint, чтобы double-click или timeout клиента не выдавал два разных кода; не создавать отдельную таблицу, если хватает locked identity/purpose + периодного cooldown.
- Условная публичная регистрация не должна раскрывать существование адреса, даже через различие формата requestId/challengeId, задержку ответа и отправку. **Если endpoint возвращает только challengeId при реальном аккаунте**, это account-enumeration bug. Для R1.D обязателен нейтральный opaque request reference одинаковой формы для всех валидных запросов; он может отображать challenge только в приватном server-side mapping либо использовать безопасный opaque signed handle. Ключевой принцип: анонимный клиент не выбирает identityId, userId, tenantId или чужой CHANGE_EMAIL purpose.

### 15.4. KumoMTA adapter: точная семантика HTTP

Документация KumoMTA: https://docs.kumomta.com/reference/http/kumod/api_inject_v1_post/ и https://docs.kumomta.com/userguide/operation/httpinjection/ .
- В KumoMTA HTTP injection API `POST /api/inject/v1` требуется `envelope_sender`, `recipients`, `content`; обработка может вернуть частичный успех/ошибки внутри ответа. Проверять `success_count`, `fail_count`, `failed_recipients` и `errors`, а не только HTTP 2xx. Для OTP отправлять **ровно одному адресату**; transactional email batch/mass send в этой фазе не делать.
- При `content` строить корректное MIME/RFC 5322 письмо: безопасно кодированные UTF-8 Subject/From, `multipart/alternative` с text/plain и text/html, служебные заголовки, переносы строк. Не считать сырой HTML полным MIME email. Исключить CRLF/header injection через переменные шаблонов. DKIM подпись формируется после окончательной нормализации сообщения на почтовом контуре, не полагаться на подпись, сделанную до HTTP injection без проверки.
- `MAIL_SENT/ACCEPTED` значит «принято в очередь KumoMTA», **не** delivered/read. Bounces/complaints/status callbacks и suppression list — отдельный этап с отдельной admission и privacy review.
- Retry: 429 учитывать `Retry-After` (если корректен и bounded); 408/429/5xx/network timeout обычно retryable; явная ошибка конфигурации, 401/403, invalid sender/template и постоянная 4xx ошибка — permanent/operational alert. Частичный accept нельзя считать полным; повторять только тех recipients, которые достоверно не приняты, с учётом one-recipient OTP.
- Outbox retry может привести к повторной доставке при crash-after-accept. Стабильный messageId связывает попытки; если KumoMTA не гарантирует idempotency по данному идентификатору, не заявлять exactly-once. При дубликатах код не должен быть изменён; повторная отправка не продлевает TTL.
- Ограничить provider credentials отдельным scope, закрытым HTTPS или защищённым внутренним каналом и allowlisted хостом. Отправитель фиксируется конфигурацией, нельзя подменить From/Reply-To через шаблон или tenant-параметр. SPF/DKIM/DMARC и reverse DNS проверяются перед реальными письмами.
- Monitoring: queue_depth, oldest_due_age, dispatch_latency, accepted_count, failed_count, retries, expired_before_send, OTP verify failures, suppression and throttle; **не размечать метрики email/userId/otp**, не сохранять payload в tracing span.

### 15.5. Точный контракт шаблонов и корректность четырёх языков

- TemplateKey + `templateVersion` + `locale` должны фиксироваться в delivery intent при enqueue, чтобы новая версия приложения не подменила смысл и язык уже отправляемого письма. Не хранить секрет OTP как готовый plaintext rendered body. Пока delivery живой, нужный templateVersion доступен на всех worker nodes; при отсутствии — fail closed/permanent error с alert, но не silent fallback к другой версии.
- Локали только `en`, `ru`, `kk`, `zh-Hans`; входящий `ru-KZ` или `kk-KZ` допускается после явного locale matching, неизвестное значение → `en`. Subject/text/html всегда выбираются *в одной* resolved locale.
- Поддерживать Unicode, UTF-8, корректные множественные формы и порядок слов. `expiryMinutes` / `dueDate` / `organizationName` — типизированные значения; date format и time zone передаются через строго заданные Java Time/Locale методы. Для казахского языка проверять естественную грамматику, включая падежные окончания/подстановки. Для китайского — корректные пробелы и порядок фраз. Нельзя автоматически строить грамматику простой конкатенацией placeholders.
- Разделить SECURITY/TRANSACTIONAL/INVITATION и будущий MARKETING. OTP всегда security и не требует marketing consent; рассылки по сегментам только через отдельное согласие/отписку/ограничения. Не использовать generic `SYSTEM_NOTIFICATION` как обход opt-out.
- URL placeholders — только безопасный HTTPS allowlist, абсолютные URI без credentials/userinfo и без произвольных redirect-url; приглашения должны иметь собственный срок действия и одноразовый proof уже на стороне владельца invite workflow.
- Render validation на старте: все template resources присутствуют; обязательные placeholders и subject/text/html валидны; максимальные размеры и HTML escape; placeholders с секретами не разрешены в Subject/From/Header. Для OTP шаблона запретить ссылки на сторонние ресурсы, tracking pixel и вложения.
- Проверить все языковые наборы редакторским review и snapshot tests. Для важных transactional шаблонов английский fallback лучше, чем смесь локалей; fallback должен быть отмечен как безопасная техническая метрика без персональных данных.

### 15.6. Контракты и аудит — минимально необходимое расширение

- Сервисный result: `VERIFY_OK | INVALID_OR_EXPIRED | LOCKED_OR_THROTTLED | RETRY_AFTER | TEMPORARILY_UNAVAILABLE` внутри trusted application boundary. Публичная версия объединяет потенциально перечисляющие пользователя ошибки в нейтральный ответ; `attemptsRemaining` только в доверенном контексте, не в анонимной ошибке.
- UI/UX (только ТЗ): поле шести цифр, возможность вставить целиком, доступность с клавиатуры, masking email (`r***@example.com`), countdown по серверному expiresAt, resend после cooldown, не сбрасывать введённый код без причины, без автоматических повторов при ошибке. Для неизвестного адреса не показывать правдоподобный ложный успех «адрес подтверждён».
- Audit append-only: purpose/method, challengeId, eventType `ISSUED/CONSUMED/EXPIRED/LOCKED/REVOKED`, timestamp, безопасный correlationId, no token/code/email. При неуспешных попытках допускается агрегированный счётчик вместо одного события на каждую неудачу (избежать write amplification), но security monitoring обязан выявлять brute force.
- Retention: OTP secret envelope и получатель доступны только до завершения доставки/TTL; после terminal state безопасная очистка по policy. Сохраняется только минимально необходимое evidence для юридического/audit срока. Секретные данные в database backup контролируются отдельным retention/crypto key lifecycle; очистка основной БД не очищает бэкапы.
- Public entrypoint и реальная отправка по-прежнему HOLD до integration/security review; текущая поставка — service-only.

### 15.7. Дополнительные приёмочные тесты R1.1

| ID | Проверка | Обязательный результат |
|---|---|---|
| OTP-11 | 4 issuance / 9 shared failed checks в час | Условия lockout, новые challenge не сбрасывают quota |
| OTP-12 | Wrong/wrong/correct + 3 simultaneous wrong + rollback injection | Ошибки фиксируются; запрет false-success и lost updates |
| OTP-13 | 6-значный `000001`, JSON code numeric, Unicode digits, неправильно проброшенный MAC keyId | Ведущие нули сохранены; формат/секреты отклоняются |
| OTP-14 | LINK_TOKEN 15m vs OTP 10m; proof methods вместе | Legacy совместимость и один смысл verified state |
| OTP-15 | R2.5 CHANGE_EMAIL + CHECKOUT и две конкурентные checkout с одинаковым email | Нет historical takeover, broken order FK, stale JWT bypass |
| OTP-16 | Анонимный request при существующем/несуществующем/disabled email | Не раскрывает факт существования учётной записи |
| OTP-17 | Resend с уже IN_FLIGHT/ACCEPTED mail | Старый код invalid, дубли возможны, lifetime не продлевается |
| MAIL-05 | TTL remaining < 120s + очередь 3/20 минут | Истёкшие/почти истёкшие OTP не передаются провайдеру |
| MAIL-06 | HTTP 200 + partial fail, 429 Retry-After, 401/403, crash-after-accept | Корректная классификация и bounded retry |
| MAIL-07 | 2 workers + crash before/after final commit + expired lease | Fencing результата, at-least-once semantics |
| MAIL-08 | Bad UTF-8 header/CRLF, HTML-only content, неправильная MIME сборка | Fail closed, валидный RFC5322 multipart |
| TPL-04 | pin template version; rolling deployment двух версий | Одинаковое письмо/язык, не silent downgrade |
| TPL-05 | Locale kk-KZ, ru-KZ, unknown, Chinese, Russian plural, Kazakh endings | Осмысленные localized тексты и единый fallback |
| OPS-02 | Legacy 0014–0051 filled PG upgrade + recovery rollback | Нет потери старых токенов, historical orders, proof evidence |
| OPS-03 | Missing encryption/HMAC key, log/trace leakage scan | Fail closed и ноль plaintext OTP в runtime logs |
| OPS-04 | Alert/metrics без PII и queue delay under load | Метрики понятны, no token/user/email label |
| SEC-01 | Security review на отказ от email как MFA | Email OTP доказывает владение адресом, не вход или 2FA |

### 15.8. Решение по реализации: простота без преждевременного platform engineering

В R1.A доработать текущий `EmailIdentityService` / узкий OTP verifier, а не создавать новый независимый IAM lifecycle; в R1.B — один reusable mail port с renderer, bounded worker и fake adapter. Не строить универсальную messaging platform ради одной реальной email-вертикали. Более общий outbox, bulk mail, delivery callbacks, маркетинг и вложения — только когда будут отдельные подтверждённые бизнес-потребности.

**Gate:** GREEN exact-head H2 + PostgreSQL и review source/tests не означают production admission. Отдельно требуются populated PG upgrade/restore, настоящие sender/provider/JWKS, public POST edge + abuse controls, key management/recovery и independent security review.
