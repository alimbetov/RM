# RM IAM–SELLER-R0 — Authenticated Tenant Service Admission (pre-REST)

**Дата:** 2026-11-24. **Ветка:** `base_dev`. **Статус:** `IMPLEMENTED / EXACT-SHA CI QUALIFICATION PENDING` до положительного Surefire manifest на HEAD.  
**Исходный подтверждённый baseline:** `7b1251b4890df2e16212a773b4900a7d6d5145ec`, 11/11 CI SUCCESS, включая exact-SHA admission.  
**Связанный аудит:** `docs/reviews/RM-SELLER-EDIT-R0-DIAGNOSTIC-2026-11-24.md`.  
**Не делать:** REST-контроллеры, XLSX, новых бизнес-акторов, дублирование tenant_id в авторитативных claims, прямой repository/JDBC import.

## 1. Реальная проблема и исправление

В исходном `OidcJwtConfiguration.configureValidation` проверялись доверенная криптографическая подпись `NimbusJwtDecoder`, `issuer`, `audience` и стандартные временные ограничения. **Не проверялся тип бизнес-токена**. Подписанный JWT типа `ID token` с `aud=rm-api` в такой конфигурации мог удовлетворить всем проверкам и стать `VerifiedActor`.

Минимальная правка: требуется Keycloak-style payload `typ=Bearer` **плюс** `aud` RM API, `issuer` и стандартная JWT-валидация. Если присутствует `token_use`, он должен равняться `access`. Значения `ID`, `id`, `refresh` и отсутствие `typ` отклоняются. Это локально утверждённый **профиль токена RM**, его необходимо проверить с реальной настройкой Keycloak при external OIDC acceptance. **Вход в RM по одной только подписи или по ID token запрещён.**

Проверка `typ=Bearer` реализована как подписанный payload-claim, а не HTTP-схема заголовка и не тип `typ=JWT` в JOSE-header: последние сами по себе не доказывают назначение access token. Реальный доступ дополнительно зависит от доверенного issuer/JWKS и audience.

Внешний OIDC-конфиг при `rm.security.oidc.enabled=true` требует HTTPS JWKS. В целевом тесте применяется *только тестовый* loopback HTTP JWKS и настоящий RS256, без публикации HTTP-маршрутов RM.

## 2. Цепочка полномочий (вся внутри service layer)

```text
Signed JWT (real RS256, real JWKS GET)
  └─ NimbusJwtDecoder: signature + iss + aud + exp/nbf + typ=Bearer
      └─ OidcActorResolver: jwt.sub → iam_user_account.external_subject
          └─ VerifiedActor(userId, expiresAt)
              └─ MyAccessService.myTenants / selectTenant:
                  active account + active workspace + ACTIVE membership
                  └─ CatalogItemService.createDraft:
                      TenantAuthorization.requireForCommand
                      → active user, tenant, membership and enforced employee RBAC
                      → tenant-qualified crm.cat_item + immutable crm.cat_item_event
```

**Авторитативные значения:** `jwt.sub` разрешается на сервере в `RM userId`. Tenant выбирается явно из активных memberships и повторно проверяется внутри *каждой пишущей транзакции*. Поля `tenant_id`, `user_id` в JWT не являются доверенным источником выбранного tenant или actor и не могут повысить права. Пользователь, имеющий membership в A и B, может получить разные grants для A и B.

**Тестовый метод:** `src/test/java/kz/rm/iam/IamSellerAuthenticatedTenantAdmissionQualificationTest.java`. Тест выполняется на настоящей PostgreSQL, генерирует RSA-2048 JCA KeyPair, отдаёт JWK через loopback HTTP и подписывает токены RS256. Декодер **не mock**. CRM/CATALOG операции выполняются существующими Spring-сервисами. SQL для setup/oracles не является production-путём изменения данных.

## 3. Матрица шести обязательных PG-доказательств

| Сценарий | JUnit method | Инвариант |
|---|---|---|
| JWT-01 | `iamSellerR0RealBearerSubCannotBeOverriddenByActorOrTenantClaims` | Подписанный `user_id` от owner и `tenant_id` от чужой компании не подменяет `sub`; A write, B read-only, C non-member denied; независимые SQL counts/audits |
| JWT-02 | `iamSellerR0SignedIdRefreshWrongPurposeAndBadJwtNeverMintActor` | Reject missing/ID `typ`, `token_use=id/refresh`, wrong `aud`, wrong `iss`, expired JWT, wrong signature, unknown `sub`; no catalog write/event |
| JWT-03 | `iamSellerR0RoleRevokedAfterSignedTokenAndTenantSelectionDeniesWrite` | Старый, всё ещё криптографически валидный access token не возвращает отозванный `CATALOG_WRITE`; предыдущая корректная запись сохраняется |
| JWT-04 | `iamSellerR0MembershipSuspendedAfterBearerBlocksSelectAndMutation` | Утрата active tenant membership запрещает повторный выбор tenant и команду без side effects |
| JWT-05 | `iamSellerR0SuspendedAccountRejectsExistingAndFreshBearer` | При блокировке RM account новый resolver не выдаёт `VerifiedActor`; ранее выданный actor не позволяет бизнес-запись |
| JWT-06 | `iamSellerR0TenantSuspendedAfterAccessTokenDeniesCurrentSelectionAndWrite` | Неактивный workspace запрещает выбор и изменение товара независимо от срока действия access token |

**Квалификация на SHA:** `RM IAM Seller R0 Authenticated Tenant Service Admission PostgreSQL` через `.github/workflows/rm-iam-seller-r0-postgresql.yml`, требует 6/6 suite methods без `failure/error/skipped`, публикует `rm-iam-seller-r0-pg-<SHA>`. Также 6 методов внесены в `.github/scripts/verify_rm_qa_01b.py`, чтобы общий exact-SHA gate не пропускал отсутствующее доказательство.

**Нельзя автоматически ставить `PASS`:** документ фиксирует реализацию и критерии. Итоговый статус меняется только после выполнения PostgreSQL CI и независимой проверки Surefire XML на одном HEAD. При любой ошибке `HOLD`, анализ root cause и минимальный patch.

## 4. Явно не подтверждено этой квалификацией

- Внешний Keycloak realm, реальный токен с текущими mapper-политиками, HTTPS/TLS JWKS, ротация `kid`, внешняя OIDC сессия и её отзыв.
- Полноценный HTTP request filter → authentication → `VerifiedActor` → выбранный tenant → сервисный вызов. `RmOidcHttpSecurity` существует, но контроллеры продавца всё ещё STOP.
- Доверенные client allowlists по `azp`/client ID (конкретные разрешённые клиентские приложения еще не утверждены), client-scope и проверка `nonce` применительно к отдельным сценариям. Access-token `aud` и `typ` — минимальный обязательный профиль, не полная настройка внешнего IdP.
- Production resource server с глобальной revocation/sid-политикой и всеми эксплуатационными SLA. Сервисная блокировка пользователю/tenant/roles проверяется по БД, но криптографическая JWT-валидация не равнозначна online introspection или немедленному отзыву всех токенов.
- PostgreSQL RLS: tenant-изоляция доказана service-layer contract, а не системными RLS политиками на всех таблицах.

**Вывод:** `IAM–SELLER-R0` — необходимый pre-REST технический gate. Он не открывает `RM-MVP-ONBOARD-01/R1`, не создаёт новый user flow и не меняет правило `production=HOLD`. После точного успешного SHA следующий отдельный рубеж — подключённый внешний OIDC acceptance с реальными bearer и HTTPS, затем API boundary, когда контроллеры будут разрешены.
