--liquibase formatted sql

--changeset rm:0001-bootstrap
SELECT 1;

--rollback SELECT 1;

--changeset rm:0002-kms-schema
CREATE SCHEMA IF NOT EXISTS security;

--rollback DROP SCHEMA IF EXISTS security;

--changeset rm:0003-tenant-key
CREATE TABLE security.tenant_key (
    tenant_id UUID NOT NULL,
    version INTEGER NOT NULL,
    wrapped_key BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    rotate_after TIMESTAMP WITH TIME ZONE NOT NULL,
    wrapping_key_id VARCHAR(128) NOT NULL,
    active_slot SMALLINT,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_tenant_key PRIMARY KEY (tenant_id, version),
    CONSTRAINT uk_tenant_active_slot UNIQUE (tenant_id, active_slot),
    CONSTRAINT ck_tenant_key_version CHECK (version >= 1),
    CONSTRAINT ck_tenant_key_status CHECK (
        status IN ('ACTIVE', 'DECRYPT_ONLY', 'REVOKED')
    ),
    CONSTRAINT ck_tenant_key_active_slot CHECK (
        (status = 'ACTIVE' AND active_slot = 1)
        OR (status <> 'ACTIVE' AND active_slot IS NULL)
    )
);

--rollback DROP TABLE security.tenant_key;


--changeset rm:0004-three-slot-kms
-- Preserve security.tenant_key as the legacy migration source. No destructive transfer is attempted.
CREATE TABLE security.kms_tenant_key (
    tenant_id UUID NOT NULL PRIMARY KEY,
    key_a BYTEA, generation_a BIGINT, status_a VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
    remaining_a BIGINT NOT NULL DEFAULT 0, migrated_a BIGINT NOT NULL DEFAULT 0,
    last_scan_a TIMESTAMP WITH TIME ZONE, wrapping_key_id_a VARCHAR(128),
    key_b BYTEA, generation_b BIGINT, status_b VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
    remaining_b BIGINT NOT NULL DEFAULT 0, migrated_b BIGINT NOT NULL DEFAULT 0,
    last_scan_b TIMESTAMP WITH TIME ZONE, wrapping_key_id_b VARCHAR(128),
    key_c BYTEA, generation_c BIGINT, status_c VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
    remaining_c BIGINT NOT NULL DEFAULT 0, migrated_c BIGINT NOT NULL DEFAULT 0,
    last_scan_c TIMESTAMP WITH TIME ZONE, wrapping_key_id_c VARCHAR(128),
    active_slot CHAR(1) NOT NULL, ready_slot CHAR(1),
    next_generation BIGINT NOT NULL, rotate_after TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_kms_key_status CHECK (
        status_a IN ('EMPTY','ACTIVE','DECRYPT_ONLY','READY','REVOKED') AND
        status_b IN ('EMPTY','ACTIVE','DECRYPT_ONLY','READY','REVOKED') AND
        status_c IN ('EMPTY','ACTIVE','DECRYPT_ONLY','READY','REVOKED')),
    CONSTRAINT ck_kms_a_present CHECK (
        (status_a='EMPTY' AND key_a IS NULL AND generation_a IS NULL AND wrapping_key_id_a IS NULL)
        OR (status_a<>'EMPTY' AND key_a IS NOT NULL AND generation_a > 0 AND wrapping_key_id_a IS NOT NULL)),
    CONSTRAINT ck_kms_b_present CHECK (
        (status_b='EMPTY' AND key_b IS NULL AND generation_b IS NULL AND wrapping_key_id_b IS NULL)
        OR (status_b<>'EMPTY' AND key_b IS NOT NULL AND generation_b > 0 AND wrapping_key_id_b IS NOT NULL)),
    CONSTRAINT ck_kms_c_present CHECK (
        (status_c='EMPTY' AND key_c IS NULL AND generation_c IS NULL AND wrapping_key_id_c IS NULL)
        OR (status_c<>'EMPTY' AND key_c IS NOT NULL AND generation_c > 0 AND wrapping_key_id_c IS NOT NULL)),
    CONSTRAINT ck_kms_active CHECK (
        (active_slot='A' AND status_a='ACTIVE' AND status_b<>'ACTIVE' AND status_c<>'ACTIVE')
        OR (active_slot='B' AND status_b='ACTIVE' AND status_a<>'ACTIVE' AND status_c<>'ACTIVE')
        OR (active_slot='C' AND status_c='ACTIVE' AND status_a<>'ACTIVE' AND status_b<>'ACTIVE')),
    CONSTRAINT ck_kms_ready CHECK (
        (ready_slot IS NULL AND status_a<>'READY' AND status_b<>'READY' AND status_c<>'READY')
        OR (ready_slot='A' AND status_a='READY' AND status_b<>'READY' AND status_c<>'READY')
        OR (ready_slot='B' AND status_b='READY' AND status_a<>'READY' AND status_c<>'READY')
        OR (ready_slot='C' AND status_c='READY' AND status_a<>'READY' AND status_b<>'READY')),
    CONSTRAINT ck_kms_positive CHECK (
        remaining_a >= 0 AND migrated_a >= 0 AND remaining_b >= 0 AND migrated_b >= 0
        AND remaining_c >= 0 AND migrated_c >= 0 AND next_generation > 1),
    CONSTRAINT ck_kms_generations_distinct CHECK (
        (generation_a IS NULL OR generation_b IS NULL OR generation_a <> generation_b)
        AND (generation_a IS NULL OR generation_c IS NULL OR generation_a <> generation_c)
        AND (generation_b IS NULL OR generation_c IS NULL OR generation_b <> generation_c))
);
--rollback DROP TABLE security.kms_tenant_key;

--changeset rm:0005-kms-archive
CREATE TABLE security.kms_tenant_key_archive (
    tenant_id UUID NOT NULL,
    key_generation BIGINT NOT NULL,
    wrapped_key BYTEA NOT NULL,
    wrapping_key_id VARCHAR(128) NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_kms_tenant_key_archive PRIMARY KEY (tenant_id, key_generation),
    CONSTRAINT ck_kms_archive_generation CHECK (key_generation > 0)
);
--rollback DROP TABLE security.kms_tenant_key_archive;


--changeset rm:0006-kms-generation-invariants
-- Add constraints without modifying previously executed changesets.
ALTER TABLE security.kms_tenant_key ADD CONSTRAINT ck_kms_single_decrypt_only CHECK (
    (CASE WHEN status_a='DECRYPT_ONLY' THEN 1 ELSE 0 END) +
    (CASE WHEN status_b='DECRYPT_ONLY' THEN 1 ELSE 0 END) +
    (CASE WHEN status_c='DECRYPT_ONLY' THEN 1 ELSE 0 END) <= 1
);
ALTER TABLE security.kms_tenant_key ADD CONSTRAINT ck_kms_next_generation CHECK (
    (generation_a IS NULL OR next_generation > generation_a) AND
    (generation_b IS NULL OR next_generation > generation_b) AND
    (generation_c IS NULL OR next_generation > generation_c)
);
--rollback ALTER TABLE security.kms_tenant_key DROP CONSTRAINT ck_kms_next_generation;
--rollback ALTER TABLE security.kms_tenant_key DROP CONSTRAINT ck_kms_single_decrypt_only;


--changeset rm:0007-kms-job-fencing
-- One key-maintenance lease per tenant; existing tenant row is the lock anchor.
-- Independent customer writers still require a separate generation/epoch barrier.
ALTER TABLE security.kms_tenant_key
    ADD COLUMN job_lease_owner UUID NULL;
ALTER TABLE security.kms_tenant_key
    ADD COLUMN job_lease_until TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE security.kms_tenant_key
    ADD COLUMN job_fence_token BIGINT NOT NULL DEFAULT 0;
ALTER TABLE security.kms_tenant_key
    ADD CONSTRAINT ck_kms_job_lease_pair CHECK (
        (job_lease_owner IS NULL AND job_lease_until IS NULL) OR
        (job_lease_owner IS NOT NULL AND job_lease_until IS NOT NULL)
    );
ALTER TABLE security.kms_tenant_key
    ADD CONSTRAINT ck_kms_fence_positive CHECK (job_fence_token >= 0);
--rollback ALTER TABLE security.kms_tenant_key DROP CONSTRAINT ck_kms_fence_positive;
--rollback ALTER TABLE security.kms_tenant_key DROP CONSTRAINT ck_kms_job_lease_pair;
--rollback ALTER TABLE security.kms_tenant_key DROP COLUMN job_fence_token;
--rollback ALTER TABLE security.kms_tenant_key DROP COLUMN job_lease_until;
--rollback ALTER TABLE security.kms_tenant_key DROP COLUMN job_lease_owner;


--changeset rm:0008-iam-organization-foundation
-- Identity is global; subscriptions belong to workspaces, never to security roles.
CREATE TABLE iam_user_account (
  id UUID NOT NULL PRIMARY KEY,
  external_subject VARCHAR(255),
  email_normalized VARCHAR(320),
  phone_e164 VARCHAR(24),
  display_name VARCHAR(200) NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_iam_user_external UNIQUE (external_subject),
  CONSTRAINT uk_iam_user_email UNIQUE (email_normalized),
  CONSTRAINT uk_iam_user_phone UNIQUE (phone_e164),
  CONSTRAINT ck_iam_user_status CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','DELETED'))
);
CREATE TABLE org_organization (
  id UUID NOT NULL PRIMARY KEY,
  legal_name VARCHAR(300) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  organization_type VARCHAR(30) NOT NULL,
  country_code VARCHAR(2) NOT NULL,
  registration_number VARCHAR(40),
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_org_type CHECK (organization_type IN ('LEGAL_ENTITY','SOLE_PROPRIETOR','OTHER')),
  CONSTRAINT ck_org_status CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','CLOSED'))
);
CREATE TABLE iam_workspace (
  id UUID NOT NULL PRIMARY KEY,
  kind VARCHAR(20) NOT NULL,
  organization_id UUID UNIQUE,
  owner_user_id UUID UNIQUE,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_workspace_org FOREIGN KEY (organization_id) REFERENCES org_organization(id),
  CONSTRAINT fk_workspace_person FOREIGN KEY (owner_user_id) REFERENCES iam_user_account(id),
  CONSTRAINT ck_workspace_owner CHECK (
    (kind='PERSONAL' AND owner_user_id IS NOT NULL AND organization_id IS NULL)
    OR (kind='ORGANIZATION' AND organization_id IS NOT NULL AND owner_user_id IS NULL)),
  CONSTRAINT ck_workspace_status CHECK (status IN ('PROVISIONING','ACTIVE','SUSPENDED','CLOSED'))
);
CREATE TABLE iam_org_membership (
  id UUID NOT NULL PRIMARY KEY,
  organization_id UUID NOT NULL,
  user_id UUID NOT NULL,
  role VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_iam_org_member UNIQUE (organization_id, user_id),
  CONSTRAINT fk_membership_org FOREIGN KEY (organization_id) REFERENCES org_organization(id),
  CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES iam_user_account(id),
  CONSTRAINT ck_member_role CHECK (role IN ('OWNER','ADMIN','MANAGER','MEMBER','VIEWER')),
  CONSTRAINT ck_member_status CHECK (status IN ('INVITED','ACTIVE','SUSPENDED','LEFT'))
);
CREATE TABLE iam_global_role_assignment (
  id UUID NOT NULL PRIMARY KEY,
  user_id UUID NOT NULL,
  role VARCHAR(30) NOT NULL,
  granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT uk_global_role UNIQUE (user_id, role),
  CONSTRAINT fk_global_role_user FOREIGN KEY (user_id) REFERENCES iam_user_account(id),
  CONSTRAINT ck_global_role CHECK (role IN ('PLATFORM_ADMIN','PLATFORM_AUDITOR'))
);
CREATE INDEX idx_org_membership_user ON iam_org_membership (user_id, status);
CREATE INDEX idx_global_role_user ON iam_global_role_assignment (user_id, revoked_at);
--rollback DROP TABLE iam_global_role_assignment;
--rollback DROP TABLE iam_org_membership;
--rollback DROP TABLE iam_workspace;
--rollback DROP TABLE org_organization;
--rollback DROP TABLE iam_user_account;


--changeset rm:0009-kato-territories-and-org-locations
-- Global KATO dictionary; organizational branches/warehouses/POS are separate tenant-owned rows.
CREATE TABLE ref_kato_territory (
  code VARCHAR(16) NOT NULL PRIMARY KEY,
  source_id BIGINT NOT NULL UNIQUE,
  parent_source_id BIGINT,
  level_no INTEGER NOT NULL,
  area_type INTEGER NOT NULL,
  name_kk VARCHAR(300) NOT NULL,
  name_ru VARCHAR(300) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_kato_level CHECK (level_no > 0)
);
CREATE INDEX idx_kato_parent_source ON ref_kato_territory(parent_source_id);
CREATE TABLE org_location (
  id UUID NOT NULL PRIMARY KEY,
  workspace_id UUID NOT NULL,
  location_type VARCHAR(20) NOT NULL,
  name VARCHAR(200) NOT NULL,
  kato_code VARCHAR(16) NOT NULL,
  address_line VARCHAR(500),
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_org_location_workspace FOREIGN KEY (workspace_id) REFERENCES iam_workspace(id),
  CONSTRAINT fk_org_location_kato FOREIGN KEY (kato_code) REFERENCES ref_kato_territory(code),
  CONSTRAINT uq_org_location_workspace_id UNIQUE (workspace_id,id),
  CONSTRAINT ck_org_location_type CHECK (location_type IN ('BRANCH','WAREHOUSE','POINT_OF_SALE')),
  CONSTRAINT ck_org_location_status CHECK (status IN ('ACTIVE','INACTIVE','CLOSED'))
);
CREATE INDEX idx_org_location_scope ON org_location (workspace_id, location_type, status);
CREATE INDEX idx_org_location_kato ON org_location (kato_code);
--rollback DROP TABLE org_location;
--rollback DROP TABLE ref_kato_territory;


--changeset rm:0010-kato-source-100-seed
-- Deterministic snapshot from src/main/resources/kato-data.json; exactly 100 rows.
-- Partial reference dataset: missing parent_source_id remains unresolved, never fabricated.
INSERT INTO ref_kato_territory (code,source_id,parent_source_id,level_no,area_type,name_kk,name_ru,is_deleted)
VALUES
('110000000', 1, NULL, 2, 0, 'АҚМОЛА ОБЛЫСЫ', 'АКМОЛИНСКАЯ ОБЛАСТЬ', FALSE),
('111810000', 10, 9, 4, 1, 'СТЕПНОГОР Қ.', 'Г.СТЕПНОГОРСК', FALSE),
('113445400', 100, 97, 5, 2, 'ШОРТАНДЫ А.', 'С.ШОРТАНДЫ', FALSE),
('117053200', 1000, 997, 5, 2, 'БРУСИЛОВКА А.', 'С.БРУСИЛОВКА', FALSE),
('396857105', 10000, 9999, 6, 2, 'ДРУЖБА А.', 'С.ДРУЖБА', FALSE),
('396857400', 10001, 9998, 5, 2, 'ТРАКТОВЫЙ А.', 'С.ТРАКТОВОЕ', FALSE),
('396859000', 10002, 9940, 4, 2, 'ПЕШКОВ А.О.', 'ПЕШКОВСКИЙ С.О.', FALSE),
('396859100', 10003, 10002, 5, 2, 'ПЕШКОВ А.', 'С.ПЕШКОВКА', FALSE),
('396859200', 10004, 10002, 5, 2, 'БОЛЬШОЕ А.', 'С.БОЛЬШОЕ', FALSE),
('396859400', 10005, 10002, 5, 2, 'КАЛИНОВ А.', 'С.КАЛИНОВКА', FALSE),
('396859500', 10006, 10002, 5, 2, 'КРАВЦОВО А.', 'С.КРАВЦОВО', FALSE),
('396859700', 10007, 10002, 5, 2, 'ПОЛТАВА А.', 'С.ПОЛТАВКА', FALSE),
('396865000', 10008, 9940, 4, 2, 'Шаңдақ а.о.', 'Чандакский с.о.', TRUE),
('396865100', 10009, 10008, 5, 2, 'Мирный а.', 'с.Мирное', TRUE),
('117053300', 1001, 997, 5, 2, 'БАЯНБАЙ А.', 'С.БАЯНБАЙ', FALSE),
('396865300', 10010, 10008, 5, 2, 'Дорожный а.', 'с.Дорожное', TRUE),
('396865400', 10011, 10008, 5, 2, 'Крамской а.', 'с.Крамское', TRUE),
('430000000', 10012, NULL, 2, 0, 'ҚЫЗЫЛОРДА ОБЛЫСЫ', 'КЫЗЫЛОРДИНСКАЯ ОБЛАСТЬ', FALSE),
('431000000', 10013, 10012, 3, 3, 'ҚЫЗЫЛОРДА Қ.Ә.', 'КЫЗЫЛОРДА Г.А.', FALSE),
('431010000', 10014, 10013, 4, 1, 'ҚЫЗЫЛОРДА Қ.', 'Г.КЫЗЫЛОРДА', FALSE),
('431033000', 10015, 10013, 4, 2, 'АҚЖАРМА А.О.', 'АКЖАРМИНСКИЙ С.О.', FALSE),
('431033100', 10016, 10015, 5, 2, 'БАЙМҰРАТ БАТЫР А.', 'С.БАЙМҰРАТ БАТЫР', FALSE),
('431033200', 10017, 10015, 5, 2, 'Қалғандария а.', 'с.Калгандарья', TRUE),
('431033300', 10018, 10015, 5, 2, 'Қубас а.', 'с.Кубас', TRUE),
('431033400', 10019, 10015, 5, 2, 'ТАЛДЫАРАЛ А.', 'С.ТАЛДЫАРАЛ', FALSE),
('117053108', 1002, 998, 6, 2, 'РЗД.17', 'РЗД.17', FALSE),
('431035000', 10020, 10013, 4, 2, 'АҚСУАТ А.О.', 'АКСУАТСКИЙ С.О.', FALSE),
('431035100', 10021, 10020, 5, 2, 'МАҚАМБЕТОВ А.', 'С.МАХАМБЕТОВА', FALSE),
('431037000', 10022, 10013, 4, 2, 'БЕЛКӨЛ К.Ә.', 'БЕЛКОЛЬСКАЯ П.А.', FALSE),
('431037100', 10023, 10022, 5, 2, 'БЕЛКӨЛ К.', 'П.БЕЛКОЛЬ', FALSE),
('431037200', 10024, 10022, 5, 2, 'БІРҚАЗАН А.', 'С.БИРКАЗАН', FALSE),
('431039000', 10025, 10013, 4, 2, 'ҚОСШЫҢЫРАУ А.О.', 'КОСШЫНЫРАУСКИЙ С.О.', FALSE),
('431039100', 10026, 10025, 5, 2, 'АБАЙ А.', 'С.АБАЙ', FALSE),
('431039200', 10027, 10025, 5, 2, 'ДОСАН А.', 'С.ДОСАН', FALSE),
('431041000', 10028, 10013, 4, 2, 'ҚАРАУЫЛТӨБЕ А.О.', 'КАРАУЛТОБИНСКИЙ С.О.', FALSE),
('431041100', 10029, 10028, 5, 2, 'ҚАРАУЫЛТӨБЕ А.', 'С.КАРАУЛТОБЕ', FALSE),
('117053109', 1003, 998, 6, 2, 'РЗД.19', 'РЗД.19', FALSE),
('431043000', 10030, 10013, 4, 2, 'ҚЫЗЫЛЖАРМА А.О', 'КЫЗЫЛЖАРМИНСКИЙ С.О.', FALSE),
('431043100', 10031, 10030, 5, 2, 'ҚЫЗЫЛЖАРМА А.', 'С.КЫЗЫЛЖАРМА', FALSE),
('431043400', 10032, 10030, 5, 2, 'Байқадам а.', 'с.Байкадам', TRUE),
('431043600', 10033, 10030, 5, 2, 'рзд.11', 'рзд.11', TRUE),
('431045000', 10034, 10013, 4, 2, 'ҚЫЗЫЛӨЗЕК А.О.', 'КЫЗЫЛОЗЕКСКИЙ С.О.', FALSE),
('431045100', 10035, 10034, 5, 2, 'ҚАРАӨЗЕК А.', 'С.КАРАОЗЕК', FALSE),
('431045200', 10036, 10034, 5, 2, 'АЙНАКӨЛ А.', 'С.АЙНАКОЛЬ', FALSE),
('431045300', 10037, 10034, 5, 2, 'ҚАРАӨЗЕК СТ.', 'СТ.КАРАОЗЕК', FALSE),
('431046000', 10038, 10013, 4, 2, 'ТАЛСУАТ А.О.', 'ТАЛСУАТСКИЙ С.О.', FALSE),
('431046100', 10039, 10038, 5, 2, 'ТАЛСУАТ А.', 'С.ТАЛСУАТ', FALSE),
('117055000', 1004, 958, 4, 2, 'АТАМЕКЕН А.О.', 'С.О.АТАМЕКЕН', FALSE),
('431046300', 10040, 10038, 5, 2, 'Геологов а.', 'с.Геологов', TRUE),
('431047000', 10041, 10013, 4, 2, 'ТАСБӨГЕТ К.Ә.', 'ТАСБУГЕТСКАЯ П.А.', FALSE),
('431047100', 10042, 10041, 5, 2, 'ТАСБӨГЕТ К.', 'П.ТАСБУГЕТ', FALSE),
('431047200', 10043, 10041, 5, 2, 'ИІРКӨЛ А.', 'С.ИРКОЛЬ', FALSE),
('431900000', 10044, 10012, 3, 1, 'БАЙҚОҢЫР Қ.Ә.', 'БАЙКОНЫР Г.А.', FALSE),
('431910000', 10045, 10044, 4, 1, 'БАЙҚОҢЫР Қ.', 'Г.БАЙКОНЫР', FALSE),
('433200000', 10046, 10012, 3, 4, 'АРАЛ АУДАНЫ', 'АРАЛЬСКИЙ РАЙОН', FALSE),
('433220000', 10047, 10046, 4, 1, 'АРАЛ Қ.Ә.', 'АРАЛЬСКАЯ Г.А.', FALSE),
('433220100', 10048, 10047, 5, 1, 'АРАЛ Қ.', 'Г.АРАЛЬСК', FALSE),
('433231000', 10049, 10046, 4, 2, 'АҚИРЕК А.О.', 'АКИРЕКСКИЙ С.О.', FALSE),
('117055100', 1005, 1004, 5, 2, 'АТАМЕКЕН А.', 'С.АТАМЕКЕН', FALSE),
('433231100', 10050, 10049, 5, 2, 'АҚБАЙ А.', 'С.АКБАЙ', FALSE),
('433232000', 10051, 10046, 4, 2, 'АТАНШИ А.О.', 'АТАНШИНСКИЙ С.О.', FALSE),
('433232100', 10052, 10051, 5, 2, 'ЖІҢІШКЕҚҰМ А.', 'С.ЖИНИШКЕКУМ', FALSE),
('433232200', 10053, 10051, 5, 2, 'Атанши а.', 'с.Атанши', TRUE),
('433233000', 10054, 10046, 4, 2, 'АМАНӨТКЕЛ А.О.', 'АМАНОТКЕЛЬСКИЙ С.О.', FALSE),
('433233100', 10055, 10054, 5, 2, 'АМАНӨТКЕЛ А.', 'С.АМАНОТКЕЛЬ', FALSE),
('433233300', 10056, 10054, 5, 2, 'АҚҚҰЛАҚ А.', 'С.АККУЛАК', FALSE),
('433233400', 10057, 10054, 5, 2, 'АҚШАТАУ А.', 'С.АКШАТАУ', FALSE),
('433233700', 10058, 10054, 5, 2, 'ХАН А.', 'С.ХАН', FALSE),
('433234000', 10059, 10046, 4, 2, 'АРАЛҚҰМ А.О.', 'АРАЛКУМСКИЙ С.О.', FALSE),
('117055200', 1006, 1004, 5, 2, 'ЖАҢАЖОЛ А.', 'С.ЖАНАЖОЛ', FALSE),
('433234100', 10060, 10059, 5, 2, 'АРАЛҚҰМ СТ.', 'СТ.АРАЛКУМ', FALSE),
('433234400', 10061, 10059, 5, 2, 'МОЙНАҚ А.', 'С.МОЙНАК', FALSE),
('433234700', 10062, 10059, 5, 2, 'ШӨМІШ СТ.', 'СТ.ШОМИШ', FALSE),
('433235000', 10063, 10046, 4, 2, 'БӨГЕН А.О', 'БОГЕНСКИЙ С.О.', FALSE),
('433235100', 10064, 10063, 5, 2, 'БӨГЕН А.', 'С.БОГЕН', FALSE),
('433235200', 10065, 10063, 5, 2, 'Көне Бөген  а.', 'с.Коне Боген', TRUE),
('433235300', 10066, 10063, 5, 2, 'ҚАРАШАЛАҢ А.', 'С.КАРАШАЛАН', FALSE),
('433236000', 10067, 10046, 4, 2, 'ЖАҢАҚҰРЫЛЫС А.О.', 'ЖАНАКУРЫЛЫССКИЙ С.О.', FALSE),
('433236100', 10068, 10067, 5, 2, 'ЖАҢАҚҰРЫЛЫС А.', 'С.ЖАНАКУРЫЛЫС', FALSE),
('433237000', 10069, 10046, 4, 2, 'ЖАҚСЫҚЫЛЫШ А.О.', 'ЖАКСЫКЫЛЫШСКИЙ С.О.', FALSE),
('117055300', 1007, 1004, 5, 2, 'ЖАРҚАЙЫҢ А.', 'С.ЖАРКАЙЫН', FALSE),
('433237100', 10070, 10069, 5, 2, 'ЖАҚСЫҚЫЛЫШ А.', 'С.ЖАКСЫКЫЛЫШ', FALSE),
('433238000', 10071, 10046, 4, 2, 'БЕКБАУЫЛ А.О.', 'БЕКБАУЫЛСКИЙ С.О.', FALSE),
('433238100', 10072, 10071, 5, 2, 'БЕКБАУЫЛ СТ.', 'СТ.БЕКБАУЫЛ', FALSE),
('433238500', 10073, 10071, 5, 2, 'ҚҰМБАЗАР А.', 'С.КУМБАЗАР', FALSE),
('433238700', 10074, 10071, 5, 2, 'РЗД.93 ҮКІЛІСАЙ', 'РЗД.93 УКУЛИСАЙ', FALSE),
('433239000', 10075, 10046, 4, 2, 'ҚАМЫСТЫБАС А.О.', 'КАМЫСТЫБАССКИЙ С.О.', FALSE),
('433239100', 10076, 10075, 5, 2, 'КАМЫСТЫБАС СТ.', 'СТ.КАМЫСТЫБАС', FALSE),
('433239800', 10077, 10075, 5, 2, 'рзд.91', 'рзд.91', TRUE),
('433239880', 10078, 10075, 5, 2, 'РЗД.92', 'РЗД.92', FALSE),
('433241000', 10079, 10046, 4, 2, 'ЖІҢІШКЕҚҰМ А.О.', 'ЖИНИШКЕКУМСКИЙ С.О.', FALSE),
('117055400', 1008, 1004, 5, 2, 'ЖАСЫЛ А.', 'С.ЖАСЫЛ', FALSE),
('433241100', 10080, 10079, 5, 2, 'ТОҚАБАЙ А.', 'С.ТОКАБАЙ', FALSE),
('433242000', 10081, 10046, 4, 2, 'ЖЕТЕС БИ А.О.', 'С.О. ЖЕТЕС БИ', FALSE),
('433242100', 10082, 10081, 5, 2, 'РАИЫМ А.', 'С.РАИМ', FALSE),
('433242300', 10083, 10081, 5, 2, 'Водокачка а.', 'с.Водокачка', TRUE),
('433242500', 10084, 10081, 5, 2, 'ЕСКІҰРА А.', 'С.ЕСКИУРА', FALSE),
('433243000', 10085, 10046, 4, 2, 'ҚАРАҚҰМ А.О.', 'КАРАКУМСКИЙ С.О.', FALSE),
('433243100', 10086, 10085, 5, 2, 'АБАЙ А.', 'С.АБАЙ', FALSE),
('433243300', 10087, 10085, 5, 2, 'ЕРІМБЕТЖАҒА А.', 'С.ЕРИМБЕТЖАГА', FALSE);
--rollback DELETE FROM ref_kato_territory WHERE source_id IN (1,10,100,1000,10000,10001,10002,10003,10004,10005,10006,10007,10008,10009,1001,10010,10011,10012,10013,10014,10015,10016,10017,10018,10019,1002,10020,10021,10022,10023,10024,10025,10026,10027,10028,10029,1003,10030,10031,10032,10033,10034,10035,10036,10037,10038,10039,1004,10040,10041,10042,10043,10044,10045,10046,10047,10048,10049,1005,10050,10051,10052,10053,10054,10055,10056,10057,10058,10059,1006,10060,10061,10062,10063,10064,10065,10066,10067,10068,10069,1007,10070,10071,10072,10073,10074,10075,10076,10077,10078,10079,1008,10080,10081,10082,10083,10084,10085,10086,10087);


--changeset rm:0011-kato-romanized-name
-- NameEng is a stable Latin transliteration of NameRus, NOT an English translation.
-- Algorithm: RM-RU-LATN-1; original Russian and Kazakh names remain authoritative.
ALTER TABLE ref_kato_territory ADD COLUMN name_en VARCHAR(400);
UPDATE ref_kato_territory SET name_en='AKMOLINSKAYa OBLAST' WHERE code='110000000';
UPDATE ref_kato_territory SET name_en='G.STEPNOGORSK' WHERE code='111810000';
UPDATE ref_kato_territory SET name_en='S.ShORTANDY' WHERE code='113445400';
UPDATE ref_kato_territory SET name_en='S.BRUSILOVKA' WHERE code='117053200';
UPDATE ref_kato_territory SET name_en='S.DRUZhBA' WHERE code='396857105';
UPDATE ref_kato_territory SET name_en='S.TRAKTOVOE' WHERE code='396857400';
UPDATE ref_kato_territory SET name_en='PEShKOVSKIY S.O.' WHERE code='396859000';
UPDATE ref_kato_territory SET name_en='S.PEShKOVKA' WHERE code='396859100';
UPDATE ref_kato_territory SET name_en='S.BOLShOE' WHERE code='396859200';
UPDATE ref_kato_territory SET name_en='S.KALINOVKA' WHERE code='396859400';
UPDATE ref_kato_territory SET name_en='S.KRAVTsOVO' WHERE code='396859500';
UPDATE ref_kato_territory SET name_en='S.POLTAVKA' WHERE code='396859700';
UPDATE ref_kato_territory SET name_en='Chandakskiy s.o.' WHERE code='396865000';
UPDATE ref_kato_territory SET name_en='s.Mirnoe' WHERE code='396865100';
UPDATE ref_kato_territory SET name_en='S.BAYaNBAY' WHERE code='117053300';
UPDATE ref_kato_territory SET name_en='s.Dorozhnoe' WHERE code='396865300';
UPDATE ref_kato_territory SET name_en='s.Kramskoe' WHERE code='396865400';
UPDATE ref_kato_territory SET name_en='KYZYLORDINSKAYa OBLAST' WHERE code='430000000';
UPDATE ref_kato_territory SET name_en='KYZYLORDA G.A.' WHERE code='431000000';
UPDATE ref_kato_territory SET name_en='G.KYZYLORDA' WHERE code='431010000';
UPDATE ref_kato_territory SET name_en='AKZhARMINSKIY S.O.' WHERE code='431033000';
UPDATE ref_kato_territory SET name_en='S.BAYMҰRAT BATYR' WHERE code='431033100';
UPDATE ref_kato_territory SET name_en='s.Kalgandarya' WHERE code='431033200';
UPDATE ref_kato_territory SET name_en='s.Kubas' WHERE code='431033300';
UPDATE ref_kato_territory SET name_en='S.TALDYARAL' WHERE code='431033400';
UPDATE ref_kato_territory SET name_en='RZD.17' WHERE code='117053108';
UPDATE ref_kato_territory SET name_en='AKSUATSKIY S.O.' WHERE code='431035000';
UPDATE ref_kato_territory SET name_en='S.MAKhAMBETOVA' WHERE code='431035100';
UPDATE ref_kato_territory SET name_en='BELKOLSKAYa P.A.' WHERE code='431037000';
UPDATE ref_kato_territory SET name_en='P.BELKOL' WHERE code='431037100';
UPDATE ref_kato_territory SET name_en='S.BIRKAZAN' WHERE code='431037200';
UPDATE ref_kato_territory SET name_en='KOSShYNYRAUSKIY S.O.' WHERE code='431039000';
UPDATE ref_kato_territory SET name_en='S.ABAY' WHERE code='431039100';
UPDATE ref_kato_territory SET name_en='S.DOSAN' WHERE code='431039200';
UPDATE ref_kato_territory SET name_en='KARAULTOBINSKIY S.O.' WHERE code='431041000';
UPDATE ref_kato_territory SET name_en='S.KARAULTOBE' WHERE code='431041100';
UPDATE ref_kato_territory SET name_en='RZD.19' WHERE code='117053109';
UPDATE ref_kato_territory SET name_en='KYZYLZhARMINSKIY S.O.' WHERE code='431043000';
UPDATE ref_kato_territory SET name_en='S.KYZYLZhARMA' WHERE code='431043100';
UPDATE ref_kato_territory SET name_en='s.Baykadam' WHERE code='431043400';
UPDATE ref_kato_territory SET name_en='rzd.11' WHERE code='431043600';
UPDATE ref_kato_territory SET name_en='KYZYLOZEKSKIY S.O.' WHERE code='431045000';
UPDATE ref_kato_territory SET name_en='S.KARAOZEK' WHERE code='431045100';
UPDATE ref_kato_territory SET name_en='S.AYNAKOL' WHERE code='431045200';
UPDATE ref_kato_territory SET name_en='ST.KARAOZEK' WHERE code='431045300';
UPDATE ref_kato_territory SET name_en='TALSUATSKIY S.O.' WHERE code='431046000';
UPDATE ref_kato_territory SET name_en='S.TALSUAT' WHERE code='431046100';
UPDATE ref_kato_territory SET name_en='S.O.ATAMEKEN' WHERE code='117055000';
UPDATE ref_kato_territory SET name_en='s.Geologov' WHERE code='431046300';
UPDATE ref_kato_territory SET name_en='TASBUGETSKAYa P.A.' WHERE code='431047000';
UPDATE ref_kato_territory SET name_en='P.TASBUGET' WHERE code='431047100';
UPDATE ref_kato_territory SET name_en='S.IRKOL' WHERE code='431047200';
UPDATE ref_kato_territory SET name_en='BAYKONYR G.A.' WHERE code='431900000';
UPDATE ref_kato_territory SET name_en='G.BAYKONYR' WHERE code='431910000';
UPDATE ref_kato_territory SET name_en='ARALSKIY RAYON' WHERE code='433200000';
UPDATE ref_kato_territory SET name_en='ARALSKAYa G.A.' WHERE code='433220000';
UPDATE ref_kato_territory SET name_en='G.ARALSK' WHERE code='433220100';
UPDATE ref_kato_territory SET name_en='AKIREKSKIY S.O.' WHERE code='433231000';
UPDATE ref_kato_territory SET name_en='S.ATAMEKEN' WHERE code='117055100';
UPDATE ref_kato_territory SET name_en='S.AKBAY' WHERE code='433231100';
UPDATE ref_kato_territory SET name_en='ATANShINSKIY S.O.' WHERE code='433232000';
UPDATE ref_kato_territory SET name_en='S.ZhINIShKEKUM' WHERE code='433232100';
UPDATE ref_kato_territory SET name_en='s.Atanshi' WHERE code='433232200';
UPDATE ref_kato_territory SET name_en='AMANOTKELSKIY S.O.' WHERE code='433233000';
UPDATE ref_kato_territory SET name_en='S.AMANOTKEL' WHERE code='433233100';
UPDATE ref_kato_territory SET name_en='S.AKKULAK' WHERE code='433233300';
UPDATE ref_kato_territory SET name_en='S.AKShATAU' WHERE code='433233400';
UPDATE ref_kato_territory SET name_en='S.KhAN' WHERE code='433233700';
UPDATE ref_kato_territory SET name_en='ARALKUMSKIY S.O.' WHERE code='433234000';
UPDATE ref_kato_territory SET name_en='S.ZhANAZhOL' WHERE code='117055200';
UPDATE ref_kato_territory SET name_en='ST.ARALKUM' WHERE code='433234100';
UPDATE ref_kato_territory SET name_en='S.MOYNAK' WHERE code='433234400';
UPDATE ref_kato_territory SET name_en='ST.ShOMISh' WHERE code='433234700';
UPDATE ref_kato_territory SET name_en='BOGENSKIY S.O.' WHERE code='433235000';
UPDATE ref_kato_territory SET name_en='S.BOGEN' WHERE code='433235100';
UPDATE ref_kato_territory SET name_en='s.Kone Bogen' WHERE code='433235200';
UPDATE ref_kato_territory SET name_en='S.KARAShALAN' WHERE code='433235300';
UPDATE ref_kato_territory SET name_en='ZhANAKURYLYSSKIY S.O.' WHERE code='433236000';
UPDATE ref_kato_territory SET name_en='S.ZhANAKURYLYS' WHERE code='433236100';
UPDATE ref_kato_territory SET name_en='ZhAKSYKYLYShSKIY S.O.' WHERE code='433237000';
UPDATE ref_kato_territory SET name_en='S.ZhARKAYYN' WHERE code='117055300';
UPDATE ref_kato_territory SET name_en='S.ZhAKSYKYLYSh' WHERE code='433237100';
UPDATE ref_kato_territory SET name_en='BEKBAUYLSKIY S.O.' WHERE code='433238000';
UPDATE ref_kato_territory SET name_en='ST.BEKBAUYL' WHERE code='433238100';
UPDATE ref_kato_territory SET name_en='S.KUMBAZAR' WHERE code='433238500';
UPDATE ref_kato_territory SET name_en='RZD.93 UKULISAY' WHERE code='433238700';
UPDATE ref_kato_territory SET name_en='KAMYSTYBASSKIY S.O.' WHERE code='433239000';
UPDATE ref_kato_territory SET name_en='ST.KAMYSTYBAS' WHERE code='433239100';
UPDATE ref_kato_territory SET name_en='rzd.91' WHERE code='433239800';
UPDATE ref_kato_territory SET name_en='RZD.92' WHERE code='433239880';
UPDATE ref_kato_territory SET name_en='ZhINIShKEKUMSKIY S.O.' WHERE code='433241000';
UPDATE ref_kato_territory SET name_en='S.ZhASYL' WHERE code='117055400';
UPDATE ref_kato_territory SET name_en='S.TOKABAY' WHERE code='433241100';
UPDATE ref_kato_territory SET name_en='S.O. ZhETES BI' WHERE code='433242000';
UPDATE ref_kato_territory SET name_en='S.RAIM' WHERE code='433242100';
UPDATE ref_kato_territory SET name_en='s.Vodokachka' WHERE code='433242300';
UPDATE ref_kato_territory SET name_en='S.ESKIURA' WHERE code='433242500';
UPDATE ref_kato_territory SET name_en='KARAKUMSKIY S.O.' WHERE code='433243000';
UPDATE ref_kato_territory SET name_en='S.ABAY' WHERE code='433243100';
UPDATE ref_kato_territory SET name_en='S.ERIMBETZhAGA' WHERE code='433243300';
ALTER TABLE ref_kato_territory ALTER COLUMN name_en SET NOT NULL;
--rollback ALTER TABLE ref_kato_territory DROP COLUMN name_en;
