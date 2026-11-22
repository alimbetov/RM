# RM — ORGANIZATION: проход 07/12 — IAM, Tenant RBAC & Security Boundary

**Документ:** RM-ORG-FUNC-R1-P07. **Reviewed source HEAD:** `1f24bba79b228dff7a8b4df75f0f80fd700766e0`, `base_dev` (22.11.2026). **Статус:** SOURCE REVIEWED / SECURITY CONTRACT SPECIFIED; ORGANIZATION lifecycle, новые PG квалификационные тесты 07 и production admission НЕ реализованы. Работы в документе — анализ и согласование ТЗ, без Java/DDL/HTTP изменений.

**Ссылки:** [основное ТЗ ORGANIZATION](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md); [проход 06 SALES integration](RM-ORG-FUNCTIONAL-DEPTH-PASS06-2026-11-22.md); [промежуточное заключение 07/12](RM-ORG-INTERIM-CLOSURE-07-2026-11-22.md).

## 1. Что проверено в действующем коде

| Owner / class | Наблюдаемое поведение | Граница применения |
|---|---|---|
| `OidcJwtConfiguration` | Условно включаемый `JwtDecoder`; HTTPS JWKS, issuer, audience, signature, expiry validator | Только если `rm.security.oidc.enabled=true`; нет контроллера/HTTP admission, нет живой интеграции с IdP на этом проходе |
| `OidcActorResolver` | Decode JWT, map subject → `iam_user_account.external_subject`, требует ACTIVE user, возвращает short-lived `VerifiedActor` | Не доверяет userId из payload или HTTP body |
| `VerifiedActor` | package-private constructor, `userId()` повторно отвергает expired object | Не является сам по себе tenant grant; статус account проверяется в IAM service при команде |
| `TenantAuthorization.requireForCommand` | `Propagation.MANDATORY`; actor row `FOR SHARE` (PG), workspace row `FOR SHARE`; JDBC fresh ACTIVE organization membership; ORM decision and fresh membership concordance; permission; transaction-bound `Decision` | Функциональный допуск ТОЛЬКО в транзакции; не PostgreSQL RLS |
| `AuthenticatedOrganizationLocations` | Принимает `VerifiedActor`, экспонирует `create/get/search`, не экспонирует UUID actor | У текущего ORG нет `update/deactivate/reactivate/close` и нельзя приписывать им уже существующее security hardening |
| `OrganizationLocationService.create` | `@Transactional` → `requireForCommand(... ORGANIZATION_LOCATION_WRITE)` → GEO KATO → JPA write | Право проверяется до KATO; создание реально, но create audit/валидация из P04 ещё OPEN |
| `OrganizationLocationQueryService.get/search` | `@Transactional` и `requireForCommand(... ORGANIZATION_LOCATION_READ)`; scoped repository по workspace | READ не означает WRITE или INVENTORY_STOCK_READ |
| `VerifiedTenantRoleCommands` / `TenantRoleService` | OWNER-only create/grant/revoke/assign/unassign/activate/list roles + immutable event; actor account and workspace `FOR UPDATE` | `TENANT_RBAC_MANAGE` нельзя делегировать через functional grant, self-assignment запрещён |
| `PlatformTenantBootstrapService` | Только verified PLATFORM_ADMIN создаёт org/workspace/OWNER и `iam_tenant_rbac_config.enforced=TRUE` без functional grants | Глобальный admin не получает tenant business permissions |
| `TenantMemberEnrollmentService` | OWNER invite existing ACTIVE OIDC-linked user; employee accepts with VerifiedActor; только затем OWNER assigns function role | От приглашения до принятия: membership INVITED и бизнес-доступ отсутствует |
| `TenantAccessStatusService` / `GlobalAccountStatusService` | Suspend/restore member, tenant, global account, separate evidence; row locks serialize existing operations | Suspended tenant/actor/member cannot create/modify stock or location; восстановление только уполномоченным путём |

**DomainPermission enum:** `ORGANIZATION_LOCATION_READ`, `ORGANIZATION_LOCATION_WRITE`; `INVENTORY_RECEIVE/ADJUST/RESERVE/RELEASE/FULFILL/STOCK_READ`; `SALES_ORDER_CONFIRM/CANCEL/FULFILL/READ`. Никаких hardcoded функциональных ролей «кладовщик/менеджер/директор» по каждому сервису; tenant role codes могут быть пользовательскими.

## 2. VerifiedActor и доверенная граница

**DEC-ORG-07-A:** все будущие lifecycle-команды `update/deactivate/reactivate/close` доступны только через тот же `AuthenticatedOrganizationLocations(VerifiedActor,tenant,...)`. Service `@Transactional`, до чтения чужого location ID или KATO необходимо `TenantAuthorization.requireForCommand(actor.userId(),selectedTenant,ORGANIZATION_LOCATION_WRITE)`. Query `get/search` отдельно `ORGANIZATION_LOCATION_READ`.

`VerifiedActor` появляется из проверенного JWT; `OidcJwtConfiguration` требует HTTPS JWKS + issuer + audience и `JwtValidators.createDefaultWithIssuer`. `OidcJwtCryptoQualificationTest.signatureIssuerAudienceAndExpiryAreAllRequired` содержит локально подписанные synthetic JWT (не реальный enterprise SSO). `OIDC` включается только конфигурацией; не считать authentication «продуктово готовой» без trusted ingress, issuer/JWKS rotation и отдельного security E2E.

**Не принимать** произвольный `UUID actorId` от клиента, не делать internal `OrganizationLocationService` публичным HTTP фасадом, не монтировать автоматически platform-admin privileges в tenant. `VerifiedActor` сам по себе не разрешает ничего вне проверяемого выбранного tenant.

**Статус пользователя повторно проверяется под lock:** уже выданный `VerifiedActor` после committed account suspension не должен пройти `requireForCommand`. Истечение срока действия actor при `actor.userId()` тоже должно быть отказом до мутации. Нет запроса «проверить bearer заново» внутри каждой JPA-команды, т.к. immutable verified principal уже введён на trusted edge.

## 3. Tenant A/B/AB — инвариант доступа

| Actor | Tenant A | Tenant B | Проверяемый исход |
|---|---|---|---|
| U1 MEMBER | ORG_READ+WRITE | ORG_READ | Create/update/deactivate только в A, get/search оба, без B writes |
| U2 MEMBER | INVENTORY_RECEIVE | SALES_ORDER_CONFIRM (без INVENTORY_RESERVE) | Receive only A; confirm in B denied even with SALES permission |
| U3 member A only | ORG_WRITE | Нет членства | Все B get/search/create/update denial, foreign UUID не раскрывается |
| U4 OWNER in A | TENANT_RBAC_MANAGE | Нет членства | Может управлять tenant A roles, не админ B |
| Platform administrator | Global PLATFORM_ADMIN | Нет tenant membership | Может provision/recovery в своих специальных сервисах, не ORG CRUD/stock |
| Suspended U1 | Роли сохранились физически | Роли сохранились физически | Все команды denied до lawful restore и fresh admission |

Список tenant из `MyAccessService.myTenants` — UI discovery, **не** источник разрешения на команду. `selectedTenant` может быть передан клиентом, но всегда перепроверяется с active member and role grant. Глобальный один `userId` может законно состоять в нескольких организациях с разными наборами разрешений.

**В БД:** `org_location` загружается по `(workspace_id,id)`. `crm.inv_stock_balance` FK на `(tenant_id,location_id)→(workspace_id,id)`. `crm.inv_stock_reservation` связывает tenant/order_line/location/item через composite FKs. Наличие FK не заменяет IAM permission и owner admission; raw SQL обход возможен для доверенных DB principals, потому что `TenantAuthorization` **не** RLS-policy.

**Existing verification (source presence, not newly executed):**
- `OrganizationLocationQueryQualificationTest.tcrud03SameGlobalMemberDifferentLocationGrantsAndForeignIdDenied`: A READ/WRITE, B READ, foreign location denied, no foreign ID in pages.
- `IamUserOnboardingProcessQualificationTest.oneLoginListsOnlyOwnTenantsAndDifferentFunctionalRoles`: one global identity, different tenants/roles, B without grant denied.
- `TenantRbacQualificationTest.functionalRoleIsIsolatedAndActivatedOnlyAfterApproval`, `actorCannotGrantToSelfOrCrossTenantOrDelegateAdmin`.
- `SalesInventoryRbacQualificationTest`: paired SALES+INVENTORY grants and tenant constraints.

## 4. Обязательные tenant-scoped операции администратора и путь новых организаций

Новый tenant provisioning: **(1)** active verified platform admin provisions tenant+OWNER, `RBAC enforced=TRUE` with zero functional grants → **(2)** OWNER creates named functional role (e.g. WAREHOUSE_OPERATOR) → **(3)** OWNER grants `ORG_READ/WRITE`, `INVENTORY_*` permissions as needed → **(4)** OWNER invites ACTIVE OIDC-linked employee → **(5)** employee accepts from own VerifiedActor → **(6)** OWNER assigns employee role → **(7)** employee creates location and receives stock with its own VerifiedActor and selected tenant.

**P07-F01 / P1 — functional bootstrap gap:** `TenantRoleService.assign` forbids `userId.equals(actorId)`; if the only OWNER runs the company alone, it can manage roles, but cannot assign a functional role to itself and thus cannot use `ORGANIZATION_LOCATION_WRITE` in a new enforced tenant. This is an intentional fail-closed policy and **not** an authorization bypass; it is a genuine single-owner usability gap for RM small-business persona.

**Recommended narrow design decision to be explicitly approved before IAM implementation:** Allow only authenticated ACTIVE OWNER under existing actor+workspace lock to *explicitly opt in* to a pre-existing ACTIVE functional role for their own user, with immutable audit `OWNER_FUNCTIONAL_ROLE_ASSIGNED`, no implicit wildcard grants, no `TENANT_RBAC_MANAGE` delegation, and verified transaction. Existing `assign(other user)` remains no-self to prevent hidden privilege escalation by managers. This needs an additive `iam_tenant_rbac_event.action_code` CHECK extension and PG tests; do NOT secretly change current ban. Alternative: if owner self-operation is out of R1 scope, clearly require employee onboarding as the supported first-workflow; this must be a conscious product limitation.

**Further important boundary:** `TenantAuthorization.require` has a legacy `enforced=FALSE` membership-role fallback: ORG_READ is available to active members in that mode, and ORG_WRITE to OWNER/ADMIN/MANAGER. **Enforced=TRUE** exact role/permission lookup is used for new provisioned tenants. `TenantRoleService.enable` can switch legacy off → on after at least one real grant. **P07-F02 / P1:** identify and migrate surviving `enforced=FALSE` tenants through populated data + role assignment before declaring universal tenant-scoped functional RBAC; never silently switch flags or remove fallback in a historical database. Test active legacy VIEWER read and permitted older manager, then cutover.

**Deprecated internal helper:** `TenantAuthorization.requireOrganizationWriter(actorId,tenantId)` is public and calls non-fenced `require`. Current `OrganizationLocationService.create` does **not** use it and correctly calls `requireForCommand`. **P07-F03 / P1 future misuse risk:** explicitly prohibit introducing new write commands through that helper; after callers inventory find zero usages, narrow/deprecate it safely rather than assuming its mere existence is an exploited bypass.

## 5. Transaction fences — precise security and concurrency

**Business command on PostgreSQL**:
1. `VerifiedActor.userId()` → actor `iam_user_account FOR SHARE`;
2. workspace `iam_workspace FOR SHARE`;
3. fresh JDBC `iam_org_membership` ACTIVE, `workspace.kind=ORGANIZATION,status=ACTIVE`;
4. tenant functional permission check (RBAC exact grant when enforced, legacy fallback only if not);
5. `TenantAuthorization.Decision` registered with active transaction sync;
6. domain lock: ORG location row `FOR UPDATE` for edit/deactivate, INVENTORY location + PRODUCT row for receive, SALES order then location for confirm;
7. DB commit releases actor/workspace shared locks.

**RBAC mutation** by OWNER: actor `FOR UPDATE` → workspace `FOR UPDATE` → role/assignment update and `iam_tenant_rbac_event`, same TX. `GlobalAccountStatusService`/`TenantAccessStatusService` lock actor or workspace before status mutation. Therefore:
- If a command is admitted and holds workspace SHARE, concurrent role revoke cannot commit *before* that command finishes.
- If revoke commits first, later command sees missing grant and denies.
- No guarantee of «uncommitted revoke instantly interrupts already admitted operation»: this is **transaction order**, not real-time cancellation.
- Mixed IAM decisions in `OrderInventoryPort` are valid only when actor/tenant/current transaction matches both `SALES_*` and `INVENTORY_*`. One grant alone cannot reserve/release/fulfill.

**Existing PostgreSQL tests:** `TenantRbacRevocationQualificationTest.activeCommandCompletesBeforeConcurrentRevocationCommits` and `commandCannotProceedUntilEarlierRevocationCommits`, plus member/tenant/account suspension races; `OrganizationLocationQueryQualificationTest.postgresLocationCreationFencesConcurrentPermissionRevocation`, `SalesInventoryRbacQualificationTest.postgresSalesConfirmationFencesConcurrentPermissionRevocation`. These are **source-verified test cases**, not newly run in this pass. They do not prove future `update/deactivate/close × revoke`, or all three domain operations at once.

**Read queries:** `OrganizationLocationQueryService.get/search` use writable Spring transactions with `requireForCommand(... READ)` for fresh IAM fencing. Under `READ COMMITTED`, paginated SELECT and COUNT may still observe different business snapshots — deferred pass 08. Do not downgrade these to `@Transactional(readOnly=true)` without proving IAM writes/locks remain valid and revoke races pass.

## 6. Security acceptance ORG-P07-Q01…Q24 (PENDING/NOT RUN)

| ID | PostgreSQL qualification scenario | Expected result |
|---|---|---|
| P07-Q01 | Signed JWT expected issuer/audience/exp, valid subject ACTIVE | Exactly mapped VerifiedActor; no supplied actorId |
| P07-Q02 | Forged/expired/missing/foreign-audience/issuer JWT | Reject before any ORG data read |
| P07-Q03 | Already minted actor after account SUSPENDED | Denied by fresh IAM command admission |
| P07-Q04 | Create tenant bootstrap → OWNER with enforced RBAC and no grant | Owner ORG_READ/WRITE denied; RBAC management allowed |
| P07-Q05 | OWNER create role, grant ORG_READ/WRITE, employee invite→accept→assign | Employee may create/get in chosen tenant only |
| P07-Q06 | Invited but not accepted staff and accepted but unassigned staff | Deny ORG commands |
| P07-Q07 | Only OWNER without employee attempts ORG_WRITE | Documented denial on current code; new explicit self-assignment flow qualification if approved |
| P07-Q08 | OWNER attempts self grant by current assign or admin functional grant | Denied according to existing law; no implicit privileged role |
| P07-Q09 | A/B/AB one user A READ+WRITE, B READ | A writes, B reads only; B write denied |
| P07-Q10 | A role read only, B role inventory receive only; mix grants | No permission bleed between tenants |
| P07-Q11 | get foreign location UUID under authorized A ID | Scoped NOT_FOUND, no foreign data |
| P07-Q12 | search/pagination 2 tenants 50 rows each, matching names | Only selected tenant items/count, no foreign IDs |
| P07-Q13 | PLATFORM_ADMIN without active tenant member/grant invokes ORG/SALES/INV | Deny, no global override |
| P07-Q14 | Member suspended / tenant suspended / account suspended | Deny, including previously minted VerifiedActor |
| P07-Q15 | Restore member or tenant via authorized owner flow | Admit only after lawful restore and actual role grants |
| P07-Q16 | Revoke ORG_WRITE after pending create/edit/deactivate admitted | Revoke waits till command commit; next write denied |
| P07-Q17 | Revoke first, concurrent edit/deactivate waits | Denied with 0 domain writes/audit |
| P07-Q18 | Revoke INVENTORY_RECEIVE while receive/transfer active | Commit order respected, no unauthorized later movement |
| P07-Q19 | Revoke SALES_ORDER_CONFIRM or INVENTORY_RESERVE during confirm | Paired decisions fenced, no partial CONFIRMED/reserve |
| P07-Q20 | Revoke INVENTORY_FULFILL or SALES_ORDER_FULFILL during fulfill | Deny or completed before revoke; no mixed terminal side effects |
| P07-Q21 | A+B roles, foreign SKU/order/location for ORG/INVENTORY/SALES commands | All tenant FK/permissions bound; no leak/partial commit |
| P07-Q22 | Legacy tenant enforced=FALSE, then explicit role grants and enable | Record baseline legacy effects; after enable exact grants only; no mass privilege auto-grant |
| P07-Q23 | Previously obtained TenantAuthorization.Decision reused in new transaction | `validForCurrentTransaction()==FALSE`; `OrderInventoryPort` denies |
| P07-Q24 | PostgreSQL role event UPDATE/DELETE, grant/revoke/role assignment same TX | Immutable audit, no orphan/duplicate evidence, rollback on failure |

For all new tests: use ≥2 actual PostgreSQL connections, controlled barriers, strict no-skip, exact HEAD, before/after `org_location`, `iam_tenant_*`, `crm.inv_stock_balance`, `crm.inv_stock_reservation`, `crm.sales_order`, audit/event counts, revocation event commit timestamps; do not claim p95/p99 or GREEN. H2 alone cannot prove PG row-lock semantics. Include cases with owner alone, no member grant, A/B memberships and two functional permissions.

## 7. Non-overengineering implementation contracts

**P0:** implement `OrganizationLocationService.update/deactivate/reactivate/close` with exactly existing actor gate + `requireForCommand(ORG_WRITE)` before scoped location lock, expectedVersion, typed INVENTORY blockers, atomic ORG audit. Do not create a second IAM system.

**P1:** mark new provisioning journey and self-owner operational path as a product decision; if approved, add *single* audited owner-only functional self-assignment in IAM, not wildcard/root grant. Until approved, use existing employee enrollment path, no grant bypass.

**P1:** qualify legacy unenforced tenants via populated DB, not a blanket assertion that every tenant is RBAC-enforced. Keep precise separation of global admin recovery and tenant business permissions.

**P1:** add real PG races `ORG update/deactivate/close × role revoke × account/member/tenant suspend` and `SALES confirm/fulfill × permissions revoke` after lifecycle implemented; current test suite only partly covers this.

**P2:** after proving all trusted use sites, limit/deprecate `TenantAuthorization.requireOrganizationWriter` and other raw actor ID legacy helpers; avoid introducing HTTP during freeze.

**Rejected design:** per-location hardcoded ADMIN/MANAGER/WAREHOUSE roles, duplicate RBAC tables, trust in JWT tenant claims without DB membership, global platform-admin tenant bypass, infinite-lived actor tokens, direct public CRUD with actor UUID, new controller/UI, distributed lock/Redis.

## 8. Verdict and adjusted exit

**Pass 07/12 = SPECIFIED at source-level**. ORGANIZATION maturity **1/5**, because actual lifecycle methods and E2E proofs absent. See [interim closure](RM-ORG-INTERIM-CLOSURE-07-2026-11-22.md): seven analyzed/spec passes, none automatically runtime-implemented, 08–12 deferred by updated user schedule. Subsequent service/domain selection may proceed *for auditing* after interim sign-off, not as a claim of production readiness.
