# RM IAM-PII-R2.5 — защищённый глобальный профиль и обязательное подтверждение email

**Статус:** R2.5A/B internal services + тесты реализованы в `base_dev`; R2.5C — encrypted profile + opt-in offline migration реализованы, но физический populated-PG cutover, контроль альтернативных writers и production qualification — HOLD; всё ещё PR #9, не `main` · **Дата:** 2026-11-21 · **Ветка:** `base_dev` · **Основа:** [RM-PLATFORM-STANDARD-R2, §25](../architecture/RM-PLATFORM-STANDARD-R2.md).
**Объём:** внутренние Java/Spring Boot сервисы, данные, безопасные миграции Liquibase, процессные H2/PostgreSQL-тесты. **Нет** REST-контроллеров, UI, SMTP-включения, production approval или переработки всех доменов.

## 1. Цель и подтверждённая исходная база

RM использует **один глобальный аккаунт** (`iam_user_account`) для покупателя, сотрудника, OWNER и PLATFORM_ADMIN. Его email и профиль не принадлежат определённой организации: **никогда не шифровать глобальный user profile tenant DEK**.

Проверено в `base_dev`:
- `EmailIdentityCrypto`: глобальные IAM AES-256-GCM для email, отдельный HMAC-SHA-256 для equality lookup, раздельные секреты; **не** tenant-ключи KMS. `iam_email_identity.email_envelope`, `lookup_hmac`, `verified_at` уже существуют.
- `EmailIdentityService.findOrProvision/queueVerification/verifyEmail`: PENDING email, токен SHA-256 и encrypted delivery outbox, TTL **15 минут**, разовое потребление; проверка почты не является аутентифицированной сессией. Реальный отправитель/outbound admission ещё не включён.
- `AutoOrderService`: гостевой checkout, связь order с `pending_email_identity_id` и последующее подтверждение. **Эти связи нельзя ломать при смене email.**
- `UserAccountEntity`: `display_name`, `email_normalized`, `phone_e164`, `external_subject` — сейчас обычные строковые колонки; поля профиля ещё не зашифрованы на уровне приложения. `OidcActorResolver` ищет по `external_subject`.
- `TenantCryptoService/TenantKeyService` обслуживают CRM tenant DEK, **не** глобальный профиль. Ротация tenant DEK пока выключена.

**Пробел:** нынешняя проверка email обслуживает email identity/checkout, но **нет отдельного безопасного процесса смены адреса текущего аккаунта**, и нет завершённой миграции открытых IAM profile fields. Не создавать второй параллельный сервис проверки почты с несовместимыми токенами.

## 2. Обязательные правила продукта

1. **При любой первой регистрации:** `emailVerified = false`. В БД это означает `verified_at = NULL` у новой текущей/кандидатной email identity; `emailVerified` — **вычисляемое состояние**, а не вторая независимая колонка. Если email не указан, статус тоже false. Даже `email_verified=true` из OIDC-профиля не становится автоматически подтверждением RM: это отдельное решение доверия issuer, вне R2.5.
2. **Первое подтверждение:** только после доказательства владения **конкретным** email путём одноразового, адресно и purpose-привязанного challenge. Тогда `verified_at` записывается сервером и `emailVerified=true`. Нажатие «отправить письмо» не подтверждает адрес.
3. **При изменении email:** всегда подтвердить **новый** адрес. Его статус `pendingEmailVerified=false` до доказательства; старый подтверждённый адрес остаётся текущим и `currentEmailVerified=true` до успешного атомарного переключения. Не делать `emailVerified=true` у нового адреса потому, что старый был подтверждён. Если старого подтверждённого адреса нет, вход остаётся неподтверждённым до проверки нового.
4. **Одинаковый нормализованный адрес:** повторное указание фактически текущего email — безопасный no-op, **не смена**; подтверждение не сбрасывается. Другое значение, включая адрес другого почтового ящика, — обязательный challenge.
5. **Подтверждение почты ≠ логин, OIDC identity или tenant permission.** Оно не назначает ROLE, не даёт доступ к CRM/PII, не активирует SUSPENDED/DELETED аккаунт и не создаёт session. Пользователь может иметь доверенный OIDC-login и `emailVerified=false` одновременно.
6. **Email-sensitive действия** (восстановление через email, активация email-приглашения, покупательский claim заказа, уведомления с чувствительными данными) используют **только текущий подтверждённый адрес** и отдельные права. Не использовать PENDING/historical email как основание доступа.
7. **Пользователь сам** инициирует смену из проверенного `VerifiedActor` и, когда будет интерактивный transport, с повторным подтверждением личности (recent auth/MFA). PLATFORM_ADMIN не может отметить email verified или молча поменять чужой адрес без доказательства владения им.

## 3. Шифрование и хранение персональных полей

| Атрибут | Конечное хранение R2.5 | Поиск и доступ |
|---|---|---|
| `user_id`, `status`, `row_version`, timestamps | Технические типизированные поля без прикладного шифрования | Ограниченные IAM service methods |
| `external_subject` (+ issuer) | Пока точный идентификатор OIDC для lookup, защищён DB IAM-only permissions; это **документированное исключение**, а не «всё зашифровано» | Запрет выводить в API/audit; перенос к blind index при отдельной квалификации issuer+subject |
| `display_name` | Отдельный `iam_pii.user_profile_secret.display_name_envelope` (AES-256-GCM) | Только ограниченный IAM profile read / собственный verified actor |
| `phone_e164` | `phone_envelope` + нормализованный keyed HMAC blind index (если номер задан) | Equality lookup, уникальность в PostgreSQL, не раскрывать другим tenant |
| Email | **Переиспользовать** `iam_email_identity.email_envelope` + `lookup_hmac` | `verified_at`, статус адреса, только IAM service; никаких plaintext `email_normalized` после миграции |
| `password`, OTP, проверочный токен | Не хранить пароли в RM, если аутентификацией владеет OIDC. Токен проверки — криптографически случайный; хранить hash для проверки и временный encrypted delivery payload только в сервисной области | Never log / never return токен; удалить/просрочить по retention |

**Crypto:** использовать уже имеющийся **глобальный IAM** crypto/key-management контур и domain separation: версия формата, уникальный nonce, AEAD AAD с `userId`, именем поля и revision/generation; разные контексты email/profile/phone. **Не** использовать ключ одного tenant и **не** подменять HMAC неключевым SHA-256 для поиска по email/телефону. Если нынешний `EmailIdentityCrypto` нельзя безопасно расширить per-field AAD без нарушения старых envelopes, добавить новую версию codec с backward decrypt и миграцией; не ломать старый `rm:iam:email:v1`. Ключи из secret manager/config, не в Git/таблицах бизнес-данных; отдельная стратегия key IDs / восстановление и ротация перед production.

**Текущий `iam_user_account` не удалять вслепую.** Создать additive `iam_pii` и мигрировать заполненные строки: скопировать/зашифровать, проверить парность и уникальность, переключить сервисные чтения/записи, затем зачистить plaintext и исключить legacy-пути. Снимать `NOT NULL/UNIQUE` с прежних колонок только вместе с тестами совместимости. Не объявлять «полная защита», пока открытые копии остаются в таблицах, исторических dumps, логах или бэкапах без контроля доступа.

## 4. Семантика current/pending/historical email и совместимость с заказами

**Критический инвариант:** `crm.sales_order.pending_email_identity_id` уже ссылается на конкретную `iam_email_identity.id`. **Нельзя обновить `email_envelope/lookup_hmac` прямо у прежней identity** при смене адреса: это переименует исторический почтовый адрес во всех связанных заказах.

Минимальная целевая модель (в существующих IAM-таблицах, без нового домена):
- `iam_email_identity`: immutable `id` и привязка исходного email; добавить понятную роль адреса: **PENDING** (ожидает подтверждения), **CURRENT** (текущий), **HISTORICAL** (больше не используется для login/verify/recovery). Исходные `email_envelope`, `lookup_hmac` после создания не меняются; `verified_at` устанавливается только после доказательства владения.
- У одного user максимум **один CURRENT** и максимум **один PENDING** для изменения. Для существующего непроверенного пользователя email может быть PENDING, а CURRENT отсутствовать. Миграция снимает старую `UNIQUE(user_id)` **только после** добавления PostgreSQL partial unique и обновления сервисных запросов.
- Глобальная уникальность **активного** email lookup для PENDING/CURRENT, но **HISTORICAL** не блокирует повторное использование адреса другим проверенным человеком навсегда. History остаётся адресно привязанной для заказов/аудита. Не переносить старые buyer orders между людьми по совпадению email.
- `EmailIdentityService.findOrProvision` должен выбирать только активную email identity; checkout по историческому адресу не должен возвращать прежнего владельца. `AutoOrderService.verifyEmail/buyerOrders` не должны приписывать чужие исторические заказы или выдавать право по старой verified identity. Изменение email **не меняет** `buyer_user_id` и историю заказа.
- Письмо на новый адрес формируется из PENDING identity. `iam_email_verification_challenge` получает purpose (`REGISTRATION`, `CHANGE_EMAIL`, `CHECKOUT`) и связь с конкретной identity/user; запрет cross-purpose replay. `iam_email_delivery_outbox` используется повторно, нового email-sender framework нет.
- При конкурирующих попытках подтвердить один адрес между двумя аккаунтами результат определяется одной транзакцией и SQL unique constraint; проигравшему — безопасный generic conflict, без раскрытия существования чужого аккаунта.

**Важно:** это целевой пересмотр ранее внедрённого `UNIQUE(user_id)`/lookup в `iam_email_identity`. Он не считается существующим кодом; необходима отдельная reversible Liquibase-миграция и PostgreSQL acceptance. Не менять существующие changesets 0014–0016 задним числом.

## 5. Сервисные сценарии (без HTTP)

### PII-UC-01 — Первая регистрация пользователя

**Вход:** доверенный onboarding service / checkout email + нормализатор; новый userId выдаёт сервер. **Шаги:** выбрать email → normalize строго одним `EmailIdentityCrypto.normalize` → encrypted envelope + HMAC index → `verified_at=NULL`/PENDING → создать одноразовый challenge + outbox → вернуть безопасный статус без токена.

**Результат:** `emailVerified=false`; в БД нет plaintext нового email. Пользователь не получает mail-sensitive права. `EmailIdentityService.verifyEmail` после проверки может активировать ранее PENDING buyer account только по действующей отдельной state policy; это не OIDC login. Не смешивать signup email и автоматически созданный checkout identity без account-link verification.

### PII-UC-02 — Подтверждение адреса после регистрации

**Вход:** challengeId + 256-bit случайный bearer token, пришедший именно на адрес назначения. **Проверки:** purpose, адрес/identity, TTL (текущее значение 15 минут), single-use, ограничение попыток, revoked/stale, ACTIVE/PENDING account. **Результат:** единственная транзакция устанавливает `verified_at` и CURRENT identity, помечает token consumed, создаёт безопасный audit; `emailVerified=true` только после commit. Повтор/ошибка/outbox failure не даёт подтверждения.

### PII-UC-03 — Смена email действующим пользователем

**Вход:** `VerifiedActor`, `newEmail`; step-up check предусмотрен для будущего adapter. **Шаги:** нормализация → если адрес отличается от текущего, создать/заменить только PENDING identity нового адреса и отправить challenge → старый CURRENT не трогать. После доказательства владения **именно новым** адресом — в транзакции новый identity становится CURRENT, прежний CURRENT становится HISTORICAL, token consumed, audit `EMAIL_CHANGED`. Уведомить старый адрес об изменении, но письмо само по себе не даёт права отменить действие без аутентификации.

**После:** `emailVerified=true` у нового CURRENT; прежнее подтверждение **не переносилось** на новый email. Пока письмо не подтверждено: `currentEmailVerified` остаётся истинным для старого, `pendingEmailVerified=false` для нового. Истёкшая/отменённая/переопределённая PENDING запись не изменяет текущую почту. Повтор подтверждения не делает второй switch.

### PII-UC-04 — Повторное письмо, отмена и конфликт

Повтор письма подлежит cooldown и rate limiting; последний действующий challenge в нужном purpose/identity должен иметь однозначное правило, **отозванные токены не работают**. Пользователь может отменить pending change. Если другой аккаунт занял предложенный email в промежутке — атомарный отказ и сохранение старого CURRENT. Ответ одинаково безопасен независимо от существования чужого email; нельзя enumeratе accounts по ошибкам.

### PII-UC-05 — Чтение и исправление глобального профиля

`MyProfileService.get/update` по `VerifiedActor`, не по присланному `actorId`; encrypted `displayName/phone`, email только через IAM email identity. Для просмотра собственных данных разрешить owner-self; для просмотра данных другого участника — отдельное IAM permission/процесс, не любому OWNER tenant и не автоматом PLATFORM_ADMIN. Изменение телефонного номера не доказывает владение SIM: `phoneVerified` не присваивается автоматически. Оптимистический `rowVersion` и sanitized audit без plaintext.

## 6. Предлагаемые небольшие контракты

Никаких DTO-классов заранее. Для следующей реализации нужны только эти бизнес-операции:
- `GlobalUserProfileService.getOwnProfile(VerifiedActor)` → `userId,displayName,emailMasked,emailVerified,phoneMasked,rowVersion`; подробные PII при разрешённом self-access.
- `GlobalUserProfileService.updateDisplayName(VerifiedActor, name, expectedVersion)` и `updatePhone(VerifiedActor, phone, expectedVersion)` → безопасный результат.
- `EmailIdentityService.requestInitialVerification(VerifiedActor|trustedOnboardingContext)` и `verifyRegistration(challengeId,token)` с отдельным purpose.
- `EmailIdentityService.requestEmailChange(VerifiedActor,newEmail)`, `confirmEmailChange(VerifiedActor,challengeId,token)`, `cancelEmailChange(VerifiedActor)`. Проверка токена не превращается в session/login.
- `EmailVerificationStatus(currentEmailVerified,pendingEmailVerified)` вычисляется из статуса адресов/verifiedAt; не хранить вторую противоречивую булеву колонку.

Имена предложены для контрактов, не означают уже существующие методы.

## 7. Миграция заполненной PostgreSQL: обязательный порядок

1. Снимок/backup и число plaintext записей; инвентаризация фактического состояния `iam_user_account`, `iam_email_identity`, FK `pending_email_identity_id`, legacy `email_normalized`, `phone_e164` и дублей после normalize. Не писать PII в отчёт миграции.
2. Аддитивные таблица `iam_pii.user_profile_secret`, состояния identity, индексы и purpose challenge, версионированные envelopes. Ключи доступны и проверены в standalone service; legacy migrations **не править**.
3. Backfill профилей под ограниченной service identity и контрольный encrypted round-trip. `emailVerified` для legacy без проверяемого `verified_at`/доверенного evidence трактовать **false**; не превращать известный адрес в подтверждённый автоматически.
4. В тестовой копии заполненной PG квалифицировать переключение сервисов, адресный поиск, совпадения email, переиспользование старого адреса и сохранность исторических заказов.
5. Только после переключения всех чтений: удалить/обнулить plaintext `email_normalized`, `phone_e164`, `display_name` или удалить устаревшие колонки по отдельному безопасному cutover, проверить отсутствие plaintext/обходов. Безопасное хранение старых backup/dump — самостоятельная retention-политика.
6. Проверить rollback **до** destructive cutover; после удаления plaintext нельзя рассчитывать на rollback без подготовленной совместимой версии. Регламент recovery при потере глобального IAM-ключа обязателен.

## 8. Минимальная тестовая матрица, критерии DONE

| ID | H2/PG сервисный тест | Обязательное подтверждение |
|---|---|---|
| PII-T01 | Новый email при первой регистрации | `emailVerified=false`, `verified_at IS NULL`; нет plaintext |
| PII-T02 | Проверка правильным токеном / bad / expiry / replay | Только первый корректный proof меняет CURRENT; misuse DENY |
| PII-T03 | Смена A→B при подтверждённом A | До proof B неактивен, A подтверждён; после proof B CURRENT, A HISTORICAL |
| PII-T04 | OIDC login + `email_verified` claim без RM challenge | Не проставляет RM verification; не выдаёт mail-dependent permissions |
| PII-T05 | Параллельное подтверждение B двумя аккаунтами | Ровно один победитель; без чужого userId, identity takeover и PII в ответе |
| PII-T06 | Старое письмо/старый token при смене адреса | Не подтверждает новый email; ошибка без side effects |
| PII-T07 | Смена email при существующих guest orders | Исторические identity/order IDs сохраняются; новые claims не присваивают чужие заказы |
| PII-T08 | Encrypt/decrypt профиля, swapped ciphertext, wrong AAD, wrong key | AES-GCM integrity failure, safe error, never plaintext in logs |
| PII-T09 | Два tenant одного user + глобальный профиль | Одна global identity, нет tenant DEK, нет tenant RBAC bypass |
| PII-T10 | Old user, unverified legacy, populated PG upgrade/rollback | Ни один адрес не получает false-positive verified; прежние покупки/доступ сохраняются |
| PII-T11 | Rate limits, max retries, outbox duplicate/crash | Нет повторной верификации, токены/PII не возвращаются и не логируются |
| PII-T12 | Invalid/suspended account or revoked VerifiedActor | DENY; email verification не восстанавливает suspended account |
| PII-T13 | HMAC normalization / uniqueness / concurrent reuse | Уникальный CURRENT, учёт HISTORICAL, индекс без plaintext |
| PII-T14 | Two-phase migration and fail-closed on missing IAM crypto keys | Нет «тихой» записи plaintext, lost-key не вызывает bypass |

**DONE внутреннего R2.5:** все тесты GREEN на PostgreSQL (H2 compatibility отдельно), exact-SHA CI, безопасная обратимая схема до cutover, аудиторский отчёт без PII; нет старых plaintext write/read paths. **PRODUCTION HOLD** до проверки ключевого хозяйства, least-privilege DB, populated migration/restore, настоящих email provider и OIDC/BFF, independent security sign-off и retention.

## 9. Ограничения и порядок реализации

**Рекомендуемые маленькие вертикали:** R2.5A — источники истины/email state и тесты без миграции всех полей; R2.5B — current/pending/historical email + подтверждение смены и checkout reconciliation; R2.5C — encrypted display name/phone + populated PG backfill и удаление legacy plaintext. Блокирующий релизный gate общий для всей R2.5.

Новых REST-контроллеров, SMS-провайдеров, Redis, универсального privacy-engine, второго аккаунта на tenant или произвольного администратора с raw userId API **не создавать**. Любое изменение email проходит по одному механизму и требует нового независимого proof.

**Зависимости:** [§23 — enrollment и роли](../architecture/RM-PLATFORM-STANDARD-R2.md#23-iam--внутренний-процесс-входа-и-раздачи-ролей-service-only-r23), [§24 — governance и recovery](../architecture/RM-PLATFORM-STANDARD-R2.md#24-iam--первичный-администратор-двухсторонняя-передача-owner-и-emergency-recovery), существующие `EmailIdentityService`, `EmailIdentityCrypto`, `AutoOrderService`, Liquibase 0014–0016.


## 10. Технический результат R2.5A (кандидат в base_dev)

- Регистрация: `EmailIdentityService.registerPendingEmail(rawEmail)` создаёт `PENDING` user/email identity только при отсутствии ранее зарегистрированного адреса; возвращает нейтральный результат, который не раскрывает наличие ACTIVE учётной записи. Хранение email уже AES-256-GCM + глобальный IAM HMAC.
- Для ACTIVE OIDC-linked аккаунта: `requestInitialVerification(VerifiedActor,email)` привязывает **только первый** адрес; если другой уже привязан — отказ до реализации безопасной смены R2.5B. `myVerificationStatus(VerifiedActor)` вычисляет `emailVerified` без нового булевого поля.
- Первый proof: `verifyRegistration(challengeId,token)` требует purpose `REGISTRATION`, TTL 15 минут, single-use, ACTIVE/PENDING account; создаёт событие `iam_email_verification_event` и не выдаёт OIDC-сессию. `verifyEmail` для checkout теперь допускает только purpose `CHECKOUT`, а legacy challenge получают CHECKOUT по умолчанию.
- Liquibase `0044–0045`: additive purpose, append-only proof evidence и PG trigger. Новые сервисные тесты — `IamInitialEmailRegistrationQualificationTest` (9 случаев, включая двухпоточные). Cooldown на повтор регистрации — 60 секунд.
- **НЕ сделано в R2.5A:** смена email CURRENT/PENDING/HISTORICAL; шифрование `display_name` и `phone_e164`, миграция plaintext в заполненной БД, real email sender, внешняя HTTP/OIDC-интеграция, независимая security-квалификация. **Не считать всю IAM-PII-R2.5 завершённой.**

## 11. Подтверждённая реализация R2.5B (кандидат в base_dev)

- `requestEmailChange/confirmEmailChange/cancelEmailChange` требуют `VerifiedActor` владельца; новая почта проходит отдельный `CHANGE_EMAIL` proof, а старый `CURRENT` остаётся verified до атомарного переключения.
- Liquibase 0046–0048: раздельные `PENDING`/`CURRENT`/`HISTORICAL`, уникальный active HMAC индекс при сохранённом историческом lookup, no duplicate active/current/pending owner, immutable transition evidence.
- `AutoOrderService` не связывает заказы через HISTORICAL identity; исходный `email_identity_id` и `buyer_user_id` сохраняются. Старый email может быть заново подтверждён другим человеком **без автоматической передачи старых заказов**.
- Сервисные тесты `IamEmailChangeQualificationTest` и `IamEmailChangeOrderHistoryQualificationTest` — обязательные, включая конкурентные подтверждения и связь с реальным Sales order.

## 12. Подтверждённая реализация R2.5C и границы backfill

- `GlobalUserProfileCrypto`: AES-256-GCM + AAD по `userId/field`; отдельные глобальные IAM 32-byte ключи для профиля и HMAC телефона. Не использовать tenant DEK. Невалидный ключ, неверный AAD или повреждённый ciphertext → отказ.
- `GlobalUserProfileService`: self-only чтение и изменение имени/телефона; версионированный encrypted profile и аудит без plaintext.
- `UserAccountEntity` исключает legacy поля `display_name`, `email_normalized`, `phone_e164` из JPA-мэппинга. Код checkout не пишет открытую строку имени. Миграции 0049–0051 создают `iam_pii.user_profile_secret`, аудит и допускают NULL для `display_name` до окончания cutover.
- `LegacyIamPiiMigrationService` работает **только при явно активированном offline maintenance flag**, шифрует и очищает старые plaintext-поля транзакционно после проверки email collision; legacy email не становится verified по факту миграции. При ошибке полный rollback, старые данные сохраняются. Нельзя запускать одновременно с online checkout/legacy writers.
- **Отложено до отдельной квалификации:** прогон в обезличенной копии наполненной PostgreSQL, выявление дублей и запрет альтернативных прямых SQL-writers, DB-level запрет plaintext/удаление старых колонок, восстановление из backup и ротация global IAM ключей. Пока это не завершено, R2.5C — internal implementation / technical tests, **не** production confidentiality admission.

## 13. R2.5D — populated PostgreSQL migration & security qualification

**Нормативный операционный контракт:** [RM-IAM-PII-R2.5D-POSTGRES-QUALIFICATION](../operations/RM-IAM-PII-R2.5D-POSTGRES-QUALIFICATION.md). [Ручной PostgreSQL seal](../operations/RM-IAM-PII-R2.5D-POSTGRES-SEAL.sql). Контроллеры не создаются. Нельзя автоматически мигрировать реальные пользовательские данные при старте Spring Boot.

### 13.1 Минимальные сервисы квалификации

- `IamPiiMigrationQualificationService.inventory()` — агрегатные количества global accounts, остаточных plaintext, encrypted profiles, active/pending/historical email identities. Возвращаемая структура **не содержит ФИО, телефон, email и userId**.
- `assertBackfillAdmissible()` — read-only проверка пригодности заполненных данных: повторяемость нормализации email, конфликт владельцев email, различие старого имени/телефона и encrypted value, AEAD-integrity и HMAC, глобальные IAM секреты. При любой ошибке **fail closed** и запрет scrub.
- `assertReadyForPlaintextSeal()` — криптографическая самопроверка ключей даже при пустой БД, полная верификация envelope/HMAC и ровно 0 ненулевых `display_name/phone_e164/email_normalized`. Успешный результат **только** допускает ручную, отдельно утверждаемую SQL seal-транзакцию, не запускает её.
- `LegacyIamPiiMigrationService` обязан сверять уже сохранённый encrypted профиль с legacy перед очищением, а для email-only аккаунта без имени переносить почту без выдумывания ФИО. Попытка перенести другой legacy email поверх текущего или чужого active HMAC → DENY.

### 13.2 Изолированное наполненное испытание

Третий обязательный CI job `postgres-pii-populated` поднимает чистую PostgreSQL 17 и запускает класс `IamPiiPopulatedDatabaseQualificationTest` с 200 **синтетическими** аккаунтами (150 имя+телефон+email, 30 email-only, 20 имя-only). В нескольких транзакционных партиях выполняется backfill; после него crypto/HMAC scan и zero residual. Дополнительно проверяются case-insensitive email collision, расхождение encrypted/legacy, потеря ключа, ciphertext tampering, транзакционный DDL CHECK, который запрещает новые открытые значения.

### 13.3 Ручное production-принятие: HOLD

Для реальной заполненной БД обязательны независимое разрешение владельца данных, подтверждённая защита и восстановление backup, изоляция всех SQL-writers, preflight без раскрытия PII, полная миграция, zero residual, отдельная manual seal-транзакция и post-seal restore/test на совместимых IAM keys. `external_subject` остаётся осознанным нешифрованным lookup, а старые nullable legacy колонки **не удаляются физически** данным этапом; их нельзя удалить до refactoring всех SQL-путей. Исторические backups/логи не очищаются автоматически. Production admission без этих свидетельств — HOLD.

**Не подменять реальный populated backup синтетическими данными GitHub Actions.** Независимый security-review, SMTP/provider abuse controls, реальный OIDC/BFF и DB least privilege также остаются обязательными.
