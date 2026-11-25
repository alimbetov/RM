# RM-FILES-R1.1 — единая файловая медиатека CRM, связи с сущностями и контроль доступа

> **Обновление архитектурного решения, 2026-11-21:** для FILES выбрано хранение исходных бинарных данных **без прикладного шифрования**. Активные backend — LOCAL (по умолчанию) и RustFS/S3 (по флагу), без автоматической миграции. Любые требования ниже об обязательном binary AES-GCM считаются отменёнными этим решением; шифрование PII-полей CRM не меняется. **Production для конфиденциальных документов остаётся HOLD** до независимого privacy/security и эксплуатационного допуска. Описание актуального механизма: [RM-FILES-RUSTFS-FLAG-ROLLOUT-R1](../operations/RM-FILES-RUSTFS-FLAG-ROLLOUT-R1.md).

**Статус:** REVIEWED DESIGN / ТЗ к реализации; код FILES не изменён.  
**Дата:** 2026-11-21. **Рабочая ветка:** base_dev. **Production/public HTTP:** HOLD.  
**Нормативный стандарт:** [RM-PLATFORM-STANDARD-R2](../architecture/RM-PLATFORM-STANDARD-R2.md); правила приватности [RM-DOMAIN-FIELDS-PRIVACY-DELETE-R1](RM-DOMAIN-FIELDS-PRIVACY-DELETE-R1.md).  
**Эволюция:** заменяет устаревший текст RM-FILES-R1 по тому же пути; сохраняет принятые концепции file_id, tenant_id, LOCAL→RustFS и три существующие таблицы. При конфликте со стандартом R2 приоритет у R2. Утверждение ТЗ не означает готовность сервиса.

## 0. Что действительно есть в base_dev и что отсутствует

Уже созданные Liquibase-таблицы (changeset 0017; отдельные индексы в 0019):
- crm.file_object: PRIMARY KEY(tenant_id,id), original_name_envelope, mime_type, size_bytes, sha256, state, created_at, row_version; CHECK state IN (STAGED,AVAILABLE,REJECTED,DELETING,DELETED).
- crm.file_storage: PRIMARY KEY(tenant_id,file_id), FK на file_object по (tenant_id,file_id), provider LOCAL/RUSTFS, storage_key, object_version, state STAGED/ACTIVE/MIGRATING/DELETING.
- crm.file_attachment: PRIMARY KEY(tenant_id,id), file_id, owner_type, owner_id, purpose, created_at, FK по (tenant_id,file_id). Индекс (tenant_id,owner_type,owner_id).
- kz.rm.files.FileObject — простой record метаданных; нет полнофункционального upload/download/scan/attachments service; реальная работа с файлами, авторизация FILES, storage adapter, шифрование binary и production recovery не квалифицированы.
- DomainModule.FILES имеет префикс file и пакет kz.rm.files. Существующий DomainPermission на момент ТЗ **не содержит** FILES permissions: их нужно добавить по мере реализации.

**Принцип:** мигрировать существующие таблицы аддитивными новыми Liquibase changesets. Не создавать вторую конкурирующую таблицу files; не переписывать changesets 0017/0019 или уже сохранённые tenant/file UUID.

## 1. Продуктовая цель и границы

Единая медиатека в CRM: пользователь может загрузить фотографию, PDF, иной разрешённый документ, увидеть его метаданные, прикрепить к клиенту, контакту, товару или заказу, просмотреть по правам, скачать, отвязать и удалить без битых ссылок. Файлы могут быть связаны с несколькими объектами **одного tenant** после разрешённой проверки владельца. Планируются галерея, превью, сортировка и фильтры, но UI не реализуется на service-only этапе.

**Истина в PostgreSQL:** логическая file identity, owner, attachment, статус, разрешения, публикация, версии. **Байты вне PostgreSQL:** локальное постоянное хранилище при разработке; RustFS как S3-compatible storage при квалификации. Для больших файлов не использовать BYTEA в бизнес-таблицах. Физический storage key не является идентификатором файла или правом доступа.

**Три идентификатора, а не два:** file_id — неизменяемый UUID бинарного объекта; (owner_type,owner_id) — тип и UUID связанной предметной сущности; attachment_id — UUID конкретной связи. tenant_id обязателен и во всех запросах, и во внешних ключах. organizationId, KATO и location не заменяют tenantId.

Не вводить отдельный Rust/Axum file microservice сейчас: RM — Java 21 / Spring Boot modular monolith. RustFS — замена физического хранилища за Java StoragePort, а не обязательный новый HTTP-сервис. Вынос в Rust допускается только по доказанной нагрузкой необходимости и отдельному ADR.

## 2. Архитектурный поток

Trusted internal caller (VerifiedActor / trusted system job)
→ TenantAuthorization + FILES permission
→ FileApplicationService
→ OwnerAccessPort (CRM/CATALOG/SALES/TASKS и т. д., проверка owner id и object policy)
→ FileRepository (PostgreSQL: file_object, file_attachment, file_storage)
→ FileStoragePort (LOCAL затем RustFs S3)
→ FileScanPort (ClamAV или строгий deny, при отсутствии действующего scanner)
→ FileProcessing/PreviewPort (позже; изображения вначале ограниченные безопасные форматы).

Бизнес-домен **решает право доступа к своему объекту**, FILES проверяет право работы с файлом и гарантирует одинаковый tenant и допустимую классификацию. Ни один сервис CRM/CATALOG/SALES не сохраняет физический путь, бакет или presigned URL; только fileId/attachmentId.

## 3. Реестр и модель данных

### 3.1. crm.file_object — логический файл

Уже есть: tenant_id, id, original_name_envelope (BYTEA), mime_type, size_bytes, sha256, state, created_at, row_version. Остальные поля — требования к новой аддитивной миграции:

| Поле | Тип / правило | Причина |
|---|---|---|
| tenant_id + id | Существующий composite PK | Tenant-bound immutable file identity |
| uploaded_by | UUID nullable только для доверенного системного импорта; для пользователя обязательно | Происхождение файла |
| kind | IMAGE, DOCUMENT, OTHER; VIDEO только после отдельного gate | Ограниченная политика типов |
| declared_mime, detected_mime | VARCHAR; текущий mime_type сохранить и определить как фактический mime после инспекции | Не доверять client Content-Type |
| original_name_envelope | Существующее encrypted поле; UTF-8 имя раскрывать только по разрешённому чтению | Имя может содержать PII |
| size_bytes, sha256 | Существующие поля; sha256 server-computed, не ETag | Целостность |
| state | Существующий STAGED/AVAILABLE/REJECTED/DELETING/DELETED | Логический lifecycle |
| scan_status | NOT_SCANNED/SCANNING/CLEAN/INFECTED/ERROR; отдельное поле от state | Нельзя открыть файл раньше сканирования |
| content_class | BUSINESS_PUBLIC_CANDIDATE / INTERNAL / CONFIDENTIAL | Политика доступа не сводится к PUBLIC |
| created_at, completed_at, deleted_at | TIMESTAMPTZ, частично additive | История и очистка |
| row_version | Существующий BIGINT | Optimistic concurrency |
| retention_until, legal_hold | Только при подтверждённой доменной политике | Безопасное удаление |

**Нельзя** считать поле visibility=PUBLIC достаточным условием публичного доступа. В R1.1 публичная доступность вычисляется из доверенной публикации конкретной attachment и owner (см. §5). Не делать PUBLIC/PRIVATE новым единственным authorization engine.

### 3.2. crm.file_storage — физическое хранилище

Оставить существующие (tenant_id,file_id) FK, provider LOCAL/RUSTFS, storage_key, object_version, state. Дополнительные атрибуты при потребности: storage_bucket, encrypted_format_version, key_generation, expected_checksum, durable_at. Никаких секретов, URL, логинов и ACL в таблице.

**Immutable object key:** tenantId/fileId/blobVersion/randomSuffix; final key назначается сервером, не клиентом. В R1 **один активный бинарный content на fileId**. Если нужна новая версия документа — новый fileId с новым attachment/version history, без перезаписи существующего; физический объект не изменяется после AVAILABLE.

Миграция LOCAL→RustFS допускает временно две физические копии и один логически активный storage pointer. Для этого при необходимости добавить versioned location record/transition record, но **не ломать существующий ключ (tenant_id,file_id)**; физическая модель миграции утверждается после простого R1.0 local adapter.

### 3.3. crm.file_attachment — связи с бизнес-объектом

Уже есть: tenant_id, id (attachmentId), file_id, owner_type, owner_id, purpose, created_at.

Правила:
- owner_type — закрытый список из зарегистрированных FileOwnerKind, не произвольная строка из HTTP. owner_id — UUID существующего объекта того же tenant; validated у owning domain. Добавить уникальность (tenant_id,file_id,owner_type,owner_id,purpose) с учётом active/detached semantics.
- Только AVAILABLE + CLEAN файлы могут быть активными вложениями. При активной привязке необходимо хранить created_by, detached_at/detached_by (аддитивно), order/sort_index по требованию галереи.
- Один fileId может иметь N attachments **внутри одного tenant**, но повторное использование запрещено, если новая связь нарушает privacy/classification (например, приватный CRM-документ нельзя привязать в публичную карточку товара).
- Полиморфный (owner_type,owner_id) невозможно защитить одним обычным FK на произвольные доменные таблицы. Поэтому вызывающий домен предоставляет typed FileOwnerAccessPort: existsSameTenant, canAttach, canRead, canDetach, currentPublicationState. Ошибка owner validation делает операцию fail closed.
- Отсоединение attachment не равно удалению файла. Физическое удаление запрещено пока есть активные ссылки, legal hold или retention. Повторное attach/detach идемпотентно с устойчивым request key.
- В SQL везде использовать tenant_id для file_object, file_storage, file_attachment и owner reference; не выполнять прямой lookup по fileId без серверного tenant контекста.

### 3.4. Превью и медиатека

R1.1: галерея перечисляет только разрешённые file attachments и выводит безопасное краткое описание, mime, size, createdAt, previewAvailable. Ограниченная пагинация (максимум 100, стабильный порядок) и фильтры kind, owner, date, purpose. Не индексировать оригинальное filename plaintext; поиск по имени — отдельная защищённая аналитика/индексация после оценки PII.

Опциональный будущий file_variant/preview: thumbnail (примерно 320px), medium (примерно 1280px), оригинал. Производные имеют собственные opaque keys, scan status, sourceFileId/variantKind; нельзя расширять права превью относительно оригинала. Удаление/отзыв исходника распространяется на производные. Не внедрять видео-транскодирование или OCR в R1.

## 4. Поддерживаемые владельцы (entity_type) и этапы

| Owner kind | Типичный файл | Требуемая проверка | Первый этап |
|---|---|---|---|
| CRM_CUSTOMER | Контракт, скан документа, фото клиента | CRM_READ/CRM_PII_READ по классификации + object policy | R1.1 |
| CRM_CONTACT | Вложение контакта | CRM access, consent не даёт permission автоматически | R1.1 |
| CATALOG_ITEM | Фотографии товара, PDF инструкция | CATALOG read/write и состояние публикации | R1.1 |
| SALES_ORDER | Счёт, акт, договор, подтверждение исполнения | SALES order access, immutable history | R1.2 |
| SALES_ESTIMATE | Версия предложения | Доступ только к соответствующей revision | R1.2 |
| FINANCE_INVOICE | Закрывающие документы | Отдельная FINANCE policy | Позже |
| TASK | Приложенный файл задачи | Право на task/assignee scope | Позже |
| ORG_LOCATION | Фото филиала | Organization object permission | Позже |
| MARKETING_CAMPAIGN | Баннеры кампаний | Marketing policy + consent где нужно | Позже |

Первый service-only vertical **CRM_CUSTOMER + CATALOG_ITEM** после готовности их точных owner access ports. Не создавать все adapters заранее. Нет глобального enum для всех возможных сущностей из пользовательского ввода.

## 5. Авторизация, tenant isolation и PUBLIC/PRIVATE

### 5.1. Строгое правило

Три обязательных условия: (1) verified actor + ACTIVE account/workspace/membership (за исключением отдельно опубликованных анонимных asset routes); (2) назначенные tenant FILES permissions; (3) право на конкретную связанную бизнес-сущность, цель обращения, lifecycle и классификацию. Идентификаторы actorId/tenantId/ownerId из HTTP-полей не являются доказательством прав.

Для R1 потребуются явные DomainPermission, например FILES_UPLOAD, FILES_READ, FILES_ATTACH, FILES_DETACH, FILES_DELETE, FILES_PUBLISH. Эти permissions — каталог возможностей, роли назначаются tenant-scoped, без отдельных hardcoded USER/MANAGER ролей на домен. Нельзя выдавать OWNER и PLATFORM_ADMIN автоматическую возможность чтения приватного CRM-документа без object/PII policy.

Прямое скачивание приватного файла по одному fileId не допускается без проверки действующего attachment/разрешённого owner. Предпочтительный внутренний контракт downloadByAttachment(actor,tenantId,attachmentId) проверяет единственную конкретную связь. Для не привязанного файла доступ ограничен загрузившим субъектом или специальной владельческой FILES политикой. Не допускать утечки через «хотя бы одно разрешённое attachment», когда остальные attachments имеют более строгую классификацию; смешанная классификация того же бинарного файла должна быть запрещена либо скопирована в отдельный fileId.

### 5.2. Публичная медиатека

Публичным становится **не файл сам по себе**, а отдельное разрешение публикации для конкретной связки (например опубликованный CATALOG_ITEM/STORE storefront asset), подтверждённое бизнес-доменом. Условия: source AVAILABLE, scan CLEAN, approved safe image MIME, content class PUBLIC_CANDIDATE, owner published/active, явное FILES_PUBLISH у инициатора, отсутствие признаков приватного/PII документа.

R1.1: публичный просмотр только через контролируемый FILES public read handler после отдельного HTTP admission, с проверкой текущего состояния owner/attachment; storage bucket остаётся private. Для ускорения CDN допускается позднее публиковать **новую безопасную производную копию** (без EXIF/PII) с отдельным ключом и TTL/cache purge; не повышать PRIVATE original in-place до PUBLIC.

Public route не обязана проверять tenant membership посетителя, но обязана проверить опубликованную разрешённую карточку и принадлежащий ей tenant в БД. Случайный fileId или известный storage_key не дают публичного доступа. Не выдавать внутренний storage path и приватные original filename/checksum.

### 5.3. Presigned URLs

Presigned PUT/GET — опциональная оптимизация R1.2 после RustFS qualification, **не фундамент авторизации**:
- Выдавать только после server-side access check; короткий TTL (предлагаемые границы: 5 минут PUT, 60 секунд GET), scope одного staging key/method, ограниченный expected content-length/checksum где реально поддерживается.
- Presigned URL — bearer capability: может быть повторно использован, утечь или продолжить действовать после изменения RBAC, пока подпись/credentials действительны. Для PII/confidential downloads в R1 запрещено выдавать presigned GET: скачивать через авторизованный поток RM с проверкой доступа на старте.
- Для presigned PUT выдавать уникальный *staging/quarantine key*, не финальный immutable key; после upload server HEAD/stream verify, checksum/size/MIME, AV, затем server-side promote/copy в **новый финальный ключ**. Повторное PUT в staging не должно менять уже verified AVAILABLE bytes. Нельзя полагаться на ETag как на SHA-256 (особенно multipart).
- URL нельзя сохранять как постоянную ссылку, логировать целиком, класть в события/audit или индексировать. Отзыв ссылки и немедленная отмена передачи не гарантированы одной лишь записью в БД.
- R1.0 не использует presigned URLs вообще: локальный streaming port и service-only qualification.

## 6. Загрузка, проверки и lifecycle

### 6.1. Логические состояния

Сохраняем существующий DB state без добавления конкурирующего набора:
- STAGED: upload intent, запись байтов в private temporary area, scan_status NOT_SCANNED/SCANNING; ничего нельзя читать посторонним.
- AVAILABLE: размер, фактический MIME, checksum, integrity, AV CLEAN, owner policy, durable storage проверены; бинарные данные immutable.
- REJECTED: malware, type mismatch, corrupt bytes, limit breach или неприемлемая классификация. Доступа к content нет.
- DELETING: логический доступ закрыт, продолжается retention-aware физическая очистка.
- DELETED: физическое удаление подтверждено для активных и versioned copies/derivatives, в рамках storage policy. Если versioning/object lock сохраняет копии, нельзя ложно заявлять irreversible deletion.

Нельзя переводить STAGED напрямую в AVAILABLE по факту клиентского «успешно загрузил» без server-side доказательств.

### 6.2. Основной happy path

1. Инициатор с VerifiedActor инициирует upload для конкретной ownerRef (или временно unattached private draft). FILES + owner service проверяют actor, tenant, permission, file type/size quotas, business owner existence. Создаётся fileId + STAGED metadata; caller metadata — недоверенные.
2. Bytes поступают потоково в opaque staging key с size caps/backpressure, без загрузки всего файла в heap. При локальном storage использовать отдельный root вне webroot, запрет симлинков/path traversal, безопасный create-new, fsync/atomic rename по необходимости.
3. Сервер вычисляет SHA-256 по реально записанным bytes, определяет content-type по сигнатуре + безопасному декодированию; сверяет лимит, заявленный MIME и length. Для изображений декодировать/re-encode с ограничением пикселей, убрать EXIF/GPS и потенциально опасные embedded metadata в разрешённом preview/public variant.
4. FileScanPort проверяет bytes, возвращает CLEAN/INFECTED/ERROR. При недоступном scanner для чужих upload **fail closed**: запись остаётся STAGED или REJECTED по политике, но не AVAILABLE. Не принимать AV ERROR как CLEAN.
5. Storage durability и соответствие bytes подтверждены. Транзакционно выполнить transition AVAILABLE + активное attachment (после повторной проверки owner), audit/evidence. При ошибке PostgreSQL byte blob остаётся только staged-orphan и подлежит reconciler.
6. Read/list/download проверяет доступ, current state, scan_status и owner; поток не раскрывает path, имеет Content-Disposition и безопасные заголовки. Данные не логировать.
7. Detach/revoke: немедленно закрыть новые выдачи по конкретной связи; последующее физическое удаление после проверки других attachments и retention.

**Idempotency:** init по (tenantId,ownerRef,requestKey) повторно возвращает тот же upload intent при том же payload, иначе CONFLICT; finalize повторно не создаёт attachments; orphan reconcile без дублирующих side effects. Разделять idempotency жизненного цикла и уникальность active attachment.

### 6.3. Лимиты (предлагаемые R1 defaults, подлежат performance qualification)

- IMAGE: JPEG/PNG/WebP, максимум 20 MiB, max pixels и width/height ограничены конфигурацией; SVG, HTML и исполняемые форматы не принимать.
- DOCUMENT: PDF (первое разрешение), максимум 25 MiB; DOCX после отдельного parser/CDR qualification. ZIP/скрипты/исполняемые файлы не принимать в R1.
- VIDEO: не принимать до R2 (multipart, Range, трансформация, сканирование и ресурсные лимиты — отдельная вертикаль).
- Политики лимитов, количества файлов на owner, tenant quota, max concurrent uploads и upload intent TTL задаются конфигурацией, не изменяются клиентом. Учитывать STAGED и multipart uploads в quota; параллельная выдача intent не должна обходить лимиты.

Практика OWASP: allowlist extensions, mime sniff, content inspection, безопасные имена и scan gate. Протокол ClamAV INSTREAM ограничен StreamMaxLength: если размер не помещается — не обходить scan, а использовать квалифицированный локальный файл/отдельный scanner с корректным ограничением. Для R1 допускается FakeScanner только в тестовом профиле, никогда как production PASS.

## 7. Хранение и шифрование

**Нужен один StoragePort на Java**, например putStaged(stream, maxLength), head(key), openStream(key, range?), finalizeStaged(stagedKey, immutableFinalKey), delete(key, version). Никаких load():byte[] для больших файлов. Внешние реализации — LocalFileStoragePort (разработка/один стабильный узел) и RustFsStoragePort (S3 SDK). Не принимать на клиентском API raw storage_key, bucket или provider.

**PII/конфиденциальные файлы:** шифрование содержимого в покое — release gate. Уже имеющийся TenantCryptoPort работает со строками и **не доказывает** потоковое шифрование больших binary; R1 должен спроектировать отдельный ограниченный FileContentCryptoPort с versioned envelope + authenticated chunk encryption, неизменяемым nonce/chunk index, AAD (tenantId,fileId,blobVersion,chunk index) и key generation, без экспорта DEK в API. Не писать на диск/в RustFS plaintext конфиденциальных файлов до квалификации. Не использовать глобальные IAM email/profile ключи для tenant файлов.

PUBLIC производные также требуют контролируемого encryption-at-rest (S3 SSE или иной утверждённый вариант); если файл предназначен для анонимной раздачи, нельзя напрямую выдавать зашифрованный tenant-private original. Классификация, обработка EXIF и публикация — отдельные проверки.

**Важный риск RustFS:** официальная S3 compatibility matrix подтверждает избранные операции presigned PUT/GET и multipart, но не гарантирует переносимость его SSE-encrypted blobs между разными S3 реализациями. Для PII migrator обязан квалифицировать decrypt/restore исходным ключевым хозяйством, независимо от «совместимости API».

LOCAL — только dev/UAT и single-node с постоянным файловым томом; в multi-pod pod-local ephemeral storage запрещён. Для production требуется надёжное object storage или общий отказоустойчивый том с backup/recovery.

## 8. Выдача файлов и безопасность HTTP (только будущая спецификация, не контроллеры R1)

Обычный authenticated read: разрешённый attachmentId + авторизованный контекст → metadata view или stream. Нельзя скачать по одному storage key; public read — отдельный endpoint и отдельная policy. Ошибки чужого tenant / отсутствующего ID / скрытого PII не должны раскрывать существование объекта (обычно унифицированный 404 на внешней границе).

Заголовки: Content-Type из фактической безопасной классификации; X-Content-Type-Options: nosniff; безопасный Content-Disposition (для confidential/docs — attachment; inline только проверенные raster images), Cache-Control no-store для приватного, CSP и origin isolation для публичных файлов. Original filename sanitize + RFC-safe encoding; никогда не использовать имя в path, response location или логах. Ограничивать Range, throughput, concurrent readers и timeouts; не собирать весь файл в память. Download authorization действует при начале stream; продолжение уже начатого стрима после revoke не равно мгновенному отзыву полученных байтов.

Public media GET может быть анонимным **после** отдельного публичного API gate, с rate limit, published owner query, безопасным asset type и контролируемым TTL. Никаких public buckets/S3 ACL по умолчанию.

## 9. Жизненный цикл ссылок и удаление

- Открепление не удаляет file_object, если активна хотя бы одна связь.
- Запрещено удаление CONFIDENTIAL или юридически значимых приложений, если owner находится под retention/legal hold или бизнес-документ immutable (issued invoice, signed order). Для таких случаев — revoke visibility или versioned correction, а не уничтожение исторических доказательств.
- При разрешённом DELETE сначала закрывается доступ в PostgreSQL (DELETING + durable cleanup intent), затем background job удаляет staging/final/preview versions и подтверждает DELETED. Ретраи безопасны.
- Lost blob, orphan blob, missed deletion, broken attachment выявляются reconciler (частота config), с sanitized metrics и repair/retry. Не претендовать на одну ACID транзакцию между PG и файловым хранилищем.
- Уничтожение tenant DEK ради одного файла запрещено, если ключ используется другими объектами. Сроки хранения, backup snapshot, object lock и версия RustFS должны быть согласованы с privacy policy.

## 10. Проверяемые сервисные контракты (без HTTP)

Предлагаемые Java application operations (имена ориентировочные, код до утверждения не создан):

| Контракт | Вход | Выход/правило |
|---|---|---|
| FileUploadService.begin | VerifiedActor, ownerRef?, metadata, requestKey | uploadIntent(fileId, constraints), STAGED |
| FileUploadService.accept | trusted uploadIntent, InputStream | Проверенный staging blob, upload receipt |
| FileUploadService.complete | uploadIntent, expectedVersion | Scan, checksum, promote, AVAILABLE; один раз |
| FileAttachmentService.attach | VerifiedActor, fileId, typedOwnerRef, purpose, requestKey | attachmentId при разрешённом owner |
| FileAttachmentService.detach | VerifiedActor, attachmentId | Идемпотентный revoke link |
| FileMediaQueryService.list | VerifiedActor, ownerRef/kind/page filters | Только permitted media, ≤100 items/page |
| FileDownloadService.open | VerifiedActor, attachmentId | Streaming response after object authorization |
| FileDeletionService.request | VerifiedActor, fileId, expectedVersion | DELETING or DENIED by retention |
| FileReconciler.scan | trusted system job + bounded batch | Исправление orphan/stale state |

Trusted jobs проверяют tenant scope/service identity и не имеют обычных пользовательских полномочий без утверждённого internal service port. Для mutating business commands использовать текущую requireForCommand и порядок блокировок IAM R2. Надёжный dual-scope authorization должен доказать отсутствие TOCTOU между owner revocation и записью attachment.

## 11. SQL migration / индексы / транзакции

**Обязательная новая Liquibase migration** (не редактировать существующий changeset 0017):
- file_object: поля type/scan_status/uploader/completed_at/deleted_at/content_class, нужные CHECK и обратимые default/backfill; не переопределять значение старого mime_type без теста compatibility.
- file_attachment: created_by, detached_at, optional sort_order, version и уникальность активной связи (PostgreSQL partial unique); корректные индексы (tenant_id,owner_type,owner_id,created_at DESC,id) и (tenant_id,file_id,detached_at).
- file_storage: корректный UNIQUE(provider,storage_key[, bucket]) в том tenant/project scope, где storage_key реально глобально уникален; migrate осторожно после проверки исторических дубликатов. Не выполнять бездумно глобальный UNIQUE, если разные buckets могут содержать одинаковые ключи.
- Upload idempotency/reconcile intent: использовать имеющийся file_object + небольшие технические поля/таблицу **только если** нельзя доказать повторное выполнение без неё; не создавать универсальную очередь поверх всех доменов заранее.
- Ограничить DB role, чтобы FILES service не мог напрямую мутировать CRM/Sales; owner validation — через typed application port. Привязки никогда не создаются без проверки фактического владельца и tenant. Обычный FK на owner_type/owner_id не доказательство целостности.
- PostgreSQL populated upgrade, downgrade до destructive step, replay old rows, FK and checksum verification; применять без потери существующих fileId, attachments и encrypted filename.

**Status truth:** Physical bytes exist ≠ FILES AVAILABLE; PostgreSQL AVAILABLE без проверенных байтов/scan — дефект и release blocker.

## 12. Матрица прав и отказов

| Операция | FILES permission | Object policy | Результат |
|---|---|---|---|
| Begin upload | FILES_UPLOAD | Может добавлять медиа к owner | STAGED |
| Attach | FILES_ATTACH | Может редактировать target owner | Active link |
| List/Download | FILES_READ | Может читать target и content_class | Разрешённый stream |
| Detach | FILES_DETACH | Может изменять/удалять эту связь | Revoked link |
| Delete | FILES_DELETE | Нет active links или retention hold | DELETING |
| Publish | FILES_PUBLISH | Owner опубликован + asset safe | Публичная медиассылка |
| Anonymous public GET | Не tenant-staff RBAC | Строгая publication policy owner | Только approved public asset |

Отзыв membership, FILES grant, статуса owner, публикации продукта, согласия (если оно относится к раскрытию вложения) должен блокировать новые операции после commit. Для проверки в PostgreSQL двухсоединительные interleavings и архитектурные правила против обхода портов.

## 13. Тестовые сценарии (minimum release matrix)

| ID | Проверка | Критерий |
|---|---|---|
| FILE-01 | tenant A vs tenant B: guessed file/attachment/owner UUID | Никакого read/list/attach/delete/copy |
| FILE-02 | suspended actor/member/workspace, wrong role | DENY до обращения к storage |
| FILE-03 | CUSTOMER accessible only to assigned employee; CRM_PII_READ missing | confidential PDF unreadable |
| FILE-04 | Невалидный ownerType, ownerId absent/cross tenant/deleted | Attachment not committed |
| FILE-05 | Один file в двух разрешённых private owners | Оба attachment независимы, без утечки |
| FILE-06 | Private file → public catalog attachment | DENY, без public URL |
| FILE-07 | Unpublished→published→unpublished product | Public read только в опубликованном состоянии |
| FILE-08 | STAGED/REJECTED/DELETING/DELETED download | DENY, включая существующую ссылку |
| FILE-09 | MIME spoof, polyglot, SVG/HTML, path traversal, filename CRLF | Block, без script execution |
| FILE-10 | File size, giant pixels, truncated upload, checksum mismatch | No AVAILABLE |
| FILE-11 | Infected scan, scanner ERROR/timeouts, ClamAV StreamMaxLength | Fail closed, no download |
| FILE-12 | Server restart after local save / SQL commit failure | Recoverable staging orphan, no false AVAILABLE |
| FILE-13 | Concurrent begin/complete/attach with same requestKey | One logical file/link, deterministic retry |
| FILE-14 | Concurrent detach vs download / role revocation | No new unauthorized read after revoke commit |
| FILE-15 | Multiple attachments then detach one | Physical file preserved for other active links |
| FILE-16 | Delete under retention/legal hold or immutable owner | Denied with sanitized audit |
| FILE-17 | Delete retries + object version + backup retention | No false DELETED while bytes retained |
| FILE-18 | Filename with PII/UTF-8 | Stored encrypted, masked/escaped output, no log leak |
| FILE-19 | Swapped tenant/keys/ciphertext/chunks | Authenticated decrypt fails |
| FILE-20 | Presigned upload overwrite/reuse, stale/quarantine key | Final AVAILABLE remains immutable |
| FILE-21 | Presigned GET leak after revoke | Risk documented; CONFIDENTIAL never gets direct GET |
| FILE-22 | LOCAL→RustFS migrate with copy/verify/switch failures | Stable fileId/attachments, original retained until safe |
| FILE-23 | Two nodes + ephemeral local storage | Deployment fail/not qualified; RustFS shared read tested |
| FILE-24 | List pagination and owner permission filtering | No cross-owner leaks/unstable ordering |
| FILE-25 | Captured HTTP error/trace, object key, raw EXIF | No PII/secret leak or untrusted metadata publication |
| FILE-26 | Populated PostgreSQL Liquibase upgrade + rollback rehearsal | FK, counts, metadata, original links preserved |
| FILE-27 | Backup/restore database+blobs+keys | Reopen and checksum round-trip |
| FILE-28 | Reader active during provider migration | Read from valid old/new pointer, no missing blob |
| FILE-29 | Invalid image re-encode/preview generator crash | Source private; public variant not published |
| FILE-30 | Large stream and concurrent requests | Bounded heap, file handles, backpressure and cancellation |

Tests: H2 compatibility + real PostgreSQL Testcontainers/CI, real filesystem restart tests, deterministic races (no sleep-dependent success), mock storage/scan fault injection, later RustFS integration with actual object versions and range/multipart checks. Exact commit SHA, measured throughput, p95/p99, memory pressure, queue and cleanup lag captured before production. CI GREEN по исходному DOMAIN layer не является FILES E2E qualification.

## 14. Поэтапная разработка (без overengineering)

**FILES-MEDIA-R1.0 — Metadata & Secure Local Core, service-only**
- Additive SQL/Liquibase, owner type registry, FileOwnerAccessPort, RBAC, local streamed storage, STAGED/AVAILABLE/REJECTED lifecycle, filename encryption, size/MIME limits, mock AV only in tests, fault injection and tenant negative tests.
- Для настоящих конфиденциальных документов до допуска обязателен authenticated file encryption и AV CLEAN; при отсутствии в R1.0 — только синтетические тестовые данные, no production PII.
- DONE: H2 + PostgreSQL + local restart + transaction/tenant security tests. Ни одного public controller.

**FILES-MEDIA-R1.1 — CRM documents + CATALOG images + Media Query**
- Typed owner adapters для CRM_CUSTOMER и CATALOG_ITEM; attach/detach/list; scan with ClamAV, безопасные images and PDF; классификация приватное/публичный кандидат; полный permission matrix.
- Приватные CRM attachments только после at-rest encryption + key recovery qualification.
- DONE: service E2E с реальными customer/item, не enum-only.

**FILES-MEDIA-R1.2 — RustFS Storage & Recovery**
- S3-compatible provider port, quarantine presigned PUT (опционально), checksum+scan server finalize, LOCAL→RustFS migration, versioned delete, restore, no ephemeral storage, two-instance tests.
- DONE: failover + migration fault injection; database/storage/key backup recovery.

**FILES-MEDIA-R1.3 — Public Media & Download Gate**
- Только после реального OIDC/public HTTP architecture review: authorized download endpoints, approved CATALOG/STOREFRONT public media handler, safe image variants, cache invalidation, monitoring, abuse limits. Private data не получают public presigned GET.
- DONE: browser/API E2E, independent security review, p95/p99 budgets and load qualification.

**R2 (не включать преждевременно):** видео, multipart/resumable, Range streaming, thumbnails at scale, CDN, OCR/извлечение текста, dedup, cross-tenant share, versioned document editor, complex lifecycle engines. Добавлять по измеренным продуктовым сценариям.

## 15. Definition of Done и предметные решения

FILES qualified только когда есть проверенный пользовательский service-only процесс **owner → upload → scan → attach → list → authorized download → detach/cleanup → restore** и отрицательные сценарии cross-tenant/PII. Наличие file tables или Java record не является подтверждением.

**Открытые предметные решения, запрещающие расширять доступ по умолчанию:**
1. CRM — весь портфель или только закреплённые клиенты? До утверждения: deny для чужого объекта даже если член tenant имеет FILES_READ.
2. CATALOG — какие материалы действительно можно публиковать, нужна ли ручная модерация изображений? До решения public handler disabled.
3. Лимиты по tenant/tарифу и retention для документов разных типов — задать до реального production.
4. Для публичной фотографии: publish только копию после удаления EXIF/личных метаданных, не исходный private file.
5. Выбор storage: LOCAL dev/UAT; RustFS после security и recovery qualification; отдельный Rust HTTP microservice не нужен без доказанной нагрузки.

**Production and public HTTP admission: HOLD** до квалификации storage/KMS/AV, populated PostgreSQL, role/object access, OIDC/BFF, provider restoration и независимого security review.

## 16. Внешние нормативные и технические ориентиры

- OWASP File Upload Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html — проверка типов/размеров, access control, scan и download risks.
- AWS S3 Presigned URL Guide: https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html — bearer capability, срок жизни, многократный PUT.
- RustFS S3 Compatibility Matrix: https://docs.rustfs.com/en/reference/s3-compatibility — presigned/multipart scope; encryption-portability caveat.
- ClamAV clamd protocol: https://docs.clamav.net/manual/Usage/ClamdProtocol.html — INSTREAM и StreamMaxLength.

Эти источники — ориентиры для реализации и проверки конкретных версий инфраструктуры; не означают, что RustFS, ClamAV и presigned APIs уже развернуты в RM.
