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


--changeset rm:0012-kato-romanization-case-normalization
-- Preserve all-uppercase source names as all-uppercase Latin; no translations.
UPDATE ref_kato_territory SET name_en='AKMOLINSKAYA OBLAST' WHERE code='110000000';
UPDATE ref_kato_territory SET name_en='G.STEPNOGORSK' WHERE code='111810000';
UPDATE ref_kato_territory SET name_en='S.SHORTANDY' WHERE code='113445400';
UPDATE ref_kato_territory SET name_en='S.BRUSILOVKA' WHERE code='117053200';
UPDATE ref_kato_territory SET name_en='S.DRUZHBA' WHERE code='396857105';
UPDATE ref_kato_territory SET name_en='S.TRAKTOVOE' WHERE code='396857400';
UPDATE ref_kato_territory SET name_en='PESHKOVSKIY S.O.' WHERE code='396859000';
UPDATE ref_kato_territory SET name_en='S.PESHKOVKA' WHERE code='396859100';
UPDATE ref_kato_territory SET name_en='S.BOLSHOE' WHERE code='396859200';
UPDATE ref_kato_territory SET name_en='S.KALINOVKA' WHERE code='396859400';
UPDATE ref_kato_territory SET name_en='S.KRAVTSOVO' WHERE code='396859500';
UPDATE ref_kato_territory SET name_en='S.POLTAVKA' WHERE code='396859700';
UPDATE ref_kato_territory SET name_en='S.BAYANBAY' WHERE code='117053300';
UPDATE ref_kato_territory SET name_en='KYZYLORDINSKAYA OBLAST' WHERE code='430000000';
UPDATE ref_kato_territory SET name_en='KYZYLORDA G.A.' WHERE code='431000000';
UPDATE ref_kato_territory SET name_en='G.KYZYLORDA' WHERE code='431010000';
UPDATE ref_kato_territory SET name_en='AKZHARMINSKIY S.O.' WHERE code='431033000';
UPDATE ref_kato_territory SET name_en='S.BAYMҰRAT BATYR' WHERE code='431033100';
UPDATE ref_kato_territory SET name_en='S.TALDYARAL' WHERE code='431033400';
UPDATE ref_kato_territory SET name_en='RZD.17' WHERE code='117053108';
UPDATE ref_kato_territory SET name_en='AKSUATSKIY S.O.' WHERE code='431035000';
UPDATE ref_kato_territory SET name_en='S.MAKHAMBETOVA' WHERE code='431035100';
UPDATE ref_kato_territory SET name_en='BELKOLSKAYA P.A.' WHERE code='431037000';
UPDATE ref_kato_territory SET name_en='P.BELKOL' WHERE code='431037100';
UPDATE ref_kato_territory SET name_en='S.BIRKAZAN' WHERE code='431037200';
UPDATE ref_kato_territory SET name_en='KOSSHYNYRAUSKIY S.O.' WHERE code='431039000';
UPDATE ref_kato_territory SET name_en='S.ABAY' WHERE code='431039100';
UPDATE ref_kato_territory SET name_en='S.DOSAN' WHERE code='431039200';
UPDATE ref_kato_territory SET name_en='KARAULTOBINSKIY S.O.' WHERE code='431041000';
UPDATE ref_kato_territory SET name_en='S.KARAULTOBE' WHERE code='431041100';
UPDATE ref_kato_territory SET name_en='RZD.19' WHERE code='117053109';
UPDATE ref_kato_territory SET name_en='KYZYLZHARMINSKIY S.O.' WHERE code='431043000';
UPDATE ref_kato_territory SET name_en='S.KYZYLZHARMA' WHERE code='431043100';
UPDATE ref_kato_territory SET name_en='KYZYLOZEKSKIY S.O.' WHERE code='431045000';
UPDATE ref_kato_territory SET name_en='S.KARAOZEK' WHERE code='431045100';
UPDATE ref_kato_territory SET name_en='S.AYNAKOL' WHERE code='431045200';
UPDATE ref_kato_territory SET name_en='ST.KARAOZEK' WHERE code='431045300';
UPDATE ref_kato_territory SET name_en='TALSUATSKIY S.O.' WHERE code='431046000';
UPDATE ref_kato_territory SET name_en='S.TALSUAT' WHERE code='431046100';
UPDATE ref_kato_territory SET name_en='S.O.ATAMEKEN' WHERE code='117055000';
UPDATE ref_kato_territory SET name_en='TASBUGETSKAYA P.A.' WHERE code='431047000';
UPDATE ref_kato_territory SET name_en='P.TASBUGET' WHERE code='431047100';
UPDATE ref_kato_territory SET name_en='S.IRKOL' WHERE code='431047200';
UPDATE ref_kato_territory SET name_en='BAYKONYR G.A.' WHERE code='431900000';
UPDATE ref_kato_territory SET name_en='G.BAYKONYR' WHERE code='431910000';
UPDATE ref_kato_territory SET name_en='ARALSKIY RAYON' WHERE code='433200000';
UPDATE ref_kato_territory SET name_en='ARALSKAYA G.A.' WHERE code='433220000';
UPDATE ref_kato_territory SET name_en='G.ARALSK' WHERE code='433220100';
UPDATE ref_kato_territory SET name_en='AKIREKSKIY S.O.' WHERE code='433231000';
UPDATE ref_kato_territory SET name_en='S.ATAMEKEN' WHERE code='117055100';
UPDATE ref_kato_territory SET name_en='S.AKBAY' WHERE code='433231100';
UPDATE ref_kato_territory SET name_en='ATANSHINSKIY S.O.' WHERE code='433232000';
UPDATE ref_kato_territory SET name_en='S.ZHINISHKEKUM' WHERE code='433232100';
UPDATE ref_kato_territory SET name_en='AMANOTKELSKIY S.O.' WHERE code='433233000';
UPDATE ref_kato_territory SET name_en='S.AMANOTKEL' WHERE code='433233100';
UPDATE ref_kato_territory SET name_en='S.AKKULAK' WHERE code='433233300';
UPDATE ref_kato_territory SET name_en='S.AKSHATAU' WHERE code='433233400';
UPDATE ref_kato_territory SET name_en='S.KHAN' WHERE code='433233700';
UPDATE ref_kato_territory SET name_en='ARALKUMSKIY S.O.' WHERE code='433234000';
UPDATE ref_kato_territory SET name_en='S.ZHANAZHOL' WHERE code='117055200';
UPDATE ref_kato_territory SET name_en='ST.ARALKUM' WHERE code='433234100';
UPDATE ref_kato_territory SET name_en='S.MOYNAK' WHERE code='433234400';
UPDATE ref_kato_territory SET name_en='ST.SHOMISH' WHERE code='433234700';
UPDATE ref_kato_territory SET name_en='BOGENSKIY S.O.' WHERE code='433235000';
UPDATE ref_kato_territory SET name_en='S.BOGEN' WHERE code='433235100';
UPDATE ref_kato_territory SET name_en='S.KARASHALAN' WHERE code='433235300';
UPDATE ref_kato_territory SET name_en='ZHANAKURYLYSSKIY S.O.' WHERE code='433236000';
UPDATE ref_kato_territory SET name_en='S.ZHANAKURYLYS' WHERE code='433236100';
UPDATE ref_kato_territory SET name_en='ZHAKSYKYLYSHSKIY S.O.' WHERE code='433237000';
UPDATE ref_kato_territory SET name_en='S.ZHARKAYYN' WHERE code='117055300';
UPDATE ref_kato_territory SET name_en='S.ZHAKSYKYLYSH' WHERE code='433237100';
UPDATE ref_kato_territory SET name_en='BEKBAUYLSKIY S.O.' WHERE code='433238000';
UPDATE ref_kato_territory SET name_en='ST.BEKBAUYL' WHERE code='433238100';
UPDATE ref_kato_territory SET name_en='S.KUMBAZAR' WHERE code='433238500';
UPDATE ref_kato_territory SET name_en='RZD.93 UKULISAY' WHERE code='433238700';
UPDATE ref_kato_territory SET name_en='KAMYSTYBASSKIY S.O.' WHERE code='433239000';
UPDATE ref_kato_territory SET name_en='ST.KAMYSTYBAS' WHERE code='433239100';
UPDATE ref_kato_territory SET name_en='RZD.92' WHERE code='433239880';
UPDATE ref_kato_territory SET name_en='ZHINISHKEKUMSKIY S.O.' WHERE code='433241000';
UPDATE ref_kato_territory SET name_en='S.ZHASYL' WHERE code='117055400';
UPDATE ref_kato_territory SET name_en='S.TOKABAY' WHERE code='433241100';
UPDATE ref_kato_territory SET name_en='S.O. ZHETES BI' WHERE code='433242000';
UPDATE ref_kato_territory SET name_en='S.RAIM' WHERE code='433242100';
UPDATE ref_kato_territory SET name_en='S.ESKIURA' WHERE code='433242500';
UPDATE ref_kato_territory SET name_en='KARAKUMSKIY S.O.' WHERE code='433243000';
UPDATE ref_kato_territory SET name_en='S.ABAY' WHERE code='433243100';
UPDATE ref_kato_territory SET name_en='S.ERIMBETZHAGA' WHERE code='433243300';
--rollback SELECT 1;


--changeset rm:0013-crm-r1a-private-customer-contact-consent
-- Multi-tenant CRM metadata is kept apart from encrypted person/contact fields.
CREATE SCHEMA IF NOT EXISTS crm;
CREATE SCHEMA IF NOT EXISTS crm_pii;
CREATE TABLE crm.customer (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    customer_type VARCHAR(20) NOT NULL,
    organization_label VARCHAR(200),
    region_zone_id VARCHAR(2),
    kato_code VARCHAR(16),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_crm_customer PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_crm_customer_tenant FOREIGN KEY (tenant_id) REFERENCES public.iam_workspace(id),
    CONSTRAINT fk_crm_customer_kato FOREIGN KEY (kato_code) REFERENCES public.ref_kato_territory(code),
    CONSTRAINT ck_crm_customer_type CHECK (customer_type IN ('PERSON','COMPANY')),
    CONSTRAINT ck_crm_customer_label CHECK (
      (customer_type='PERSON' AND organization_label IS NULL)
      OR (customer_type='COMPANY' AND organization_label IS NOT NULL)),
    CONSTRAINT ck_crm_customer_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_crm_customer_region CHECK (
      (kato_code IS NULL AND region_zone_id IS NULL)
      OR (kato_code IS NOT NULL AND region_zone_id IS NOT NULL))
);
CREATE INDEX idx_crm_customer_tenant_region ON crm.customer(tenant_id,region_zone_id);
CREATE TABLE crm_pii.customer_secret (
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    name_envelope BYTEA NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_crm_customer_secret PRIMARY KEY (tenant_id,customer_id),
    CONSTRAINT fk_crm_customer_secret FOREIGN KEY (tenant_id,customer_id)
      REFERENCES crm.customer(tenant_id,id)
);
CREATE TABLE crm.contact (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    customer_id UUID NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_crm_contact PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_crm_contact_customer FOREIGN KEY (tenant_id,customer_id)
      REFERENCES crm.customer(tenant_id,id),
    CONSTRAINT ck_crm_contact_status CHECK (status IN ('ACTIVE','ARCHIVED'))
);
CREATE INDEX idx_crm_contact_customer ON crm.contact(tenant_id,customer_id);
CREATE TABLE crm_pii.contact_secret (
    tenant_id UUID NOT NULL,
    contact_id UUID NOT NULL,
    name_envelope BYTEA NOT NULL,
    email_envelope BYTEA,
    phone_envelope BYTEA,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_crm_contact_secret PRIMARY KEY(tenant_id,contact_id),
    CONSTRAINT fk_crm_contact_secret FOREIGN KEY (tenant_id,contact_id)
      REFERENCES crm.contact(tenant_id,id)
);
CREATE TABLE crm.email_consent_event (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    contact_id UUID NOT NULL,
    granted BOOLEAN NOT NULL,
    purpose VARCHAR(80) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    source VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_crm_email_consent PRIMARY KEY (tenant_id,id),
    CONSTRAINT fk_crm_email_consent_contact FOREIGN KEY (tenant_id,contact_id)
      REFERENCES crm.contact(tenant_id,id)
);
CREATE INDEX idx_crm_consent_latest ON crm.email_consent_event(tenant_id,contact_id,purpose,occurred_at);
--rollback DROP TABLE crm.email_consent_event;
--rollback DROP TABLE crm_pii.contact_secret;
--rollback DROP TABLE crm.contact;
--rollback DROP TABLE crm_pii.customer_secret;
--rollback DROP TABLE crm.customer;
--rollback DROP SCHEMA crm_pii;
--rollback DROP SCHEMA crm;


--changeset rm:0014-autoorder-r1-foundation
-- Email identity is global IAM-owned; seller orders remain tenant-owned.
CREATE TABLE iam_email_identity (
  id UUID NOT NULL PRIMARY KEY,
  user_id UUID NOT NULL,
  lookup_hmac BYTEA NOT NULL,
  email_envelope BYTEA NOT NULL,
  verified_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_iam_email_identity_user FOREIGN KEY (user_id) REFERENCES iam_user_account(id),
  CONSTRAINT uk_iam_email_lookup UNIQUE (lookup_hmac),
  CONSTRAINT uk_iam_email_identity_user UNIQUE (user_id)
);
CREATE TABLE iam_email_verification_challenge (
  id UUID NOT NULL PRIMARY KEY,
  email_identity_id UUID NOT NULL,
  token_sha256 BYTEA NOT NULL,
  token_envelope BYTEA NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  consumed_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_iam_email_challenge_identity FOREIGN KEY (email_identity_id) REFERENCES iam_email_identity(id),
  CONSTRAINT uk_iam_email_token UNIQUE (token_sha256)
);
CREATE INDEX idx_iam_email_challenge_active ON iam_email_verification_challenge(email_identity_id, expires_at);
CREATE TABLE crm.store_front (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  slug VARCHAR(100) NOT NULL,
  state VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_store_front PRIMARY KEY (tenant_id,id),
  CONSTRAINT uk_store_slug UNIQUE (slug),
  CONSTRAINT uk_store_tenant UNIQUE (tenant_id),
  CONSTRAINT fk_store_tenant FOREIGN KEY (tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT ck_store_state CHECK (state IN ('DRAFT','PUBLISHED','PAUSED'))
);
-- Minimal server-authored offer: prevents client-controlled prices. Superseded by CATALOG integration.
CREATE TABLE crm.store_offer (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  title VARCHAR(240) NOT NULL,
  unit_price NUMERIC(19,4) NOT NULL,
  currency CHAR(3) NOT NULL,
  state VARCHAR(20) NOT NULL,
  CONSTRAINT pk_store_offer PRIMARY KEY (tenant_id,id),
  CONSTRAINT fk_store_offer_tenant FOREIGN KEY (tenant_id) REFERENCES crm.store_front(tenant_id),
  CONSTRAINT ck_store_offer_price CHECK (unit_price >= 0),
  CONSTRAINT ck_store_offer_state CHECK (state IN ('PUBLISHED','HIDDEN'))
);
CREATE TABLE crm.sales_order (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  pending_email_identity_id UUID NOT NULL,
  buyer_user_id UUID,
  status VARCHAR(24) NOT NULL,
  total_amount NUMERIC(19,4) NOT NULL,
  currency CHAR(3) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_sales_order PRIMARY KEY (tenant_id,id),
  CONSTRAINT fk_sales_order_tenant FOREIGN KEY (tenant_id) REFERENCES crm.store_front(tenant_id),
  CONSTRAINT fk_sales_order_email_identity FOREIGN KEY (pending_email_identity_id) REFERENCES public.iam_email_identity(id),
  CONSTRAINT fk_sales_order_buyer FOREIGN KEY (buyer_user_id) REFERENCES public.iam_user_account(id),
  CONSTRAINT ck_sales_order_amount CHECK (total_amount >= 0),
  CONSTRAINT ck_sales_order_status CHECK (status IN ('PLACED','CONFIRMED','PREPARING','READY_FOR_PICKUP','OUT_FOR_DELIVERY','COMPLETED','CANCELLED','REJECTED'))
);
CREATE INDEX idx_sales_order_buyer ON crm.sales_order(buyer_user_id, created_at);
CREATE TABLE crm.sales_order_line (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  order_id UUID NOT NULL,
  offer_id UUID NOT NULL,
  title_snapshot VARCHAR(240) NOT NULL,
  quantity INTEGER NOT NULL,
  unit_price_snapshot NUMERIC(19,4) NOT NULL,
  currency CHAR(3) NOT NULL,
  CONSTRAINT pk_sales_order_line PRIMARY KEY (tenant_id,id),
  CONSTRAINT fk_sales_line_order FOREIGN KEY (tenant_id,order_id) REFERENCES crm.sales_order(tenant_id,id),
  CONSTRAINT fk_sales_line_offer FOREIGN KEY (tenant_id,offer_id) REFERENCES crm.store_offer(tenant_id,id),
  CONSTRAINT ck_sales_line_qty CHECK (quantity > 0)
);
CREATE TABLE crm.sales_checkout_receipt (
  tenant_id UUID NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_sha256 BYTEA NOT NULL,
  order_id UUID NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_sales_checkout_receipt PRIMARY KEY (tenant_id,idempotency_key),
  CONSTRAINT fk_sales_receipt_order FOREIGN KEY (tenant_id,order_id) REFERENCES crm.sales_order(tenant_id,id)
);
CREATE TABLE crm.sales_order_status_event (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  order_id UUID NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_sales_order_status_event PRIMARY KEY (tenant_id,id),
  CONSTRAINT fk_sales_order_status_event FOREIGN KEY (tenant_id,order_id) REFERENCES crm.sales_order(tenant_id,id)
);
--rollback DROP TABLE crm.sales_order_status_event;
--rollback DROP TABLE crm.sales_checkout_receipt;
--rollback DROP TABLE crm.sales_order_line;
--rollback DROP TABLE crm.sales_order;
--rollback DROP TABLE crm.store_offer;
--rollback DROP TABLE crm.store_front;
--rollback DROP TABLE iam_email_verification_challenge;
--rollback DROP TABLE iam_email_identity;


--changeset rm:0015-iam-email-verification-durable-delivery
-- Delivery jobs read encrypted email + token using IAM service credentials only.
-- Never expose challenge data in checkout responses or logs.
CREATE TABLE iam_email_delivery_outbox (
  id UUID NOT NULL PRIMARY KEY,
  challenge_id UUID NOT NULL,
  email_identity_id UUID NOT NULL,
  state VARCHAR(20) NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  sent_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT fk_iam_delivery_challenge FOREIGN KEY (challenge_id) REFERENCES iam_email_verification_challenge(id),
  CONSTRAINT fk_iam_delivery_identity FOREIGN KEY (email_identity_id) REFERENCES iam_email_identity(id),
  CONSTRAINT uk_iam_delivery_challenge UNIQUE (challenge_id),
  CONSTRAINT ck_iam_delivery_state CHECK (state IN ('PENDING','SENT','FAILED')),
  CONSTRAINT ck_iam_delivery_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX idx_iam_delivery_due ON iam_email_delivery_outbox(state,next_attempt_at);
--rollback DROP TABLE iam_email_delivery_outbox;


--changeset rm:0016-autoorder-transaction-lock-stripes
-- Fixed cardinality lock stripes avoid uncontrolled per-email/per-tenant DDL.
-- Application lock order: checkout stripe first, IAM identity stripe second.
CREATE TABLE crm.sales_checkout_lock (
  bucket INTEGER NOT NULL PRIMARY KEY
);
INSERT INTO crm.sales_checkout_lock(bucket) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15),(16),(17),(18),(19),(20),(21),(22),(23),(24),(25),(26),(27),(28),(29),(30),(31),(32),(33),(34),(35),(36),(37),(38),(39),(40),(41),(42),(43),(44),(45),(46),(47),(48),(49),(50),(51),(52),(53),(54),(55),(56),(57),(58),(59),(60),(61),(62),(63),(64),(65),(66),(67),(68),(69),(70),(71),(72),(73),(74),(75),(76),(77),(78),(79),(80),(81),(82),(83),(84),(85),(86),(87),(88),(89),(90),(91),(92),(93),(94),(95),(96),(97),(98),(99),(100),(101),(102),(103),(104),(105),(106),(107),(108),(109),(110),(111),(112),(113),(114),(115),(116),(117),(118),(119),(120),(121),(122),(123),(124),(125),(126),(127),(128),(129),(130),(131),(132),(133),(134),(135),(136),(137),(138),(139),(140),(141),(142),(143),(144),(145),(146),(147),(148),(149),(150),(151),(152),(153),(154),(155),(156),(157),(158),(159),(160),(161),(162),(163),(164),(165),(166),(167),(168),(169),(170),(171),(172),(173),(174),(175),(176),(177),(178),(179),(180),(181),(182),(183),(184),(185),(186),(187),(188),(189),(190),(191),(192),(193),(194),(195),(196),(197),(198),(199),(200),(201),(202),(203),(204),(205),(206),(207),(208),(209),(210),(211),(212),(213),(214),(215),(216),(217),(218),(219),(220),(221),(222),(223),(224),(225),(226),(227),(228),(229),(230),(231),(232),(233),(234),(235),(236),(237),(238),(239),(240),(241),(242),(243),(244),(245),(246),(247),(248),(249),(250),(251),(252),(253),(254),(255);
CREATE TABLE iam_email_identity_lock (
  bucket INTEGER NOT NULL PRIMARY KEY
);
INSERT INTO iam_email_identity_lock(bucket) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15),(16),(17),(18),(19),(20),(21),(22),(23),(24),(25),(26),(27),(28),(29),(30),(31),(32),(33),(34),(35),(36),(37),(38),(39),(40),(41),(42),(43),(44),(45),(46),(47),(48),(49),(50),(51),(52),(53),(54),(55),(56),(57),(58),(59),(60),(61),(62),(63),(64),(65),(66),(67),(68),(69),(70),(71),(72),(73),(74),(75),(76),(77),(78),(79),(80),(81),(82),(83),(84),(85),(86),(87),(88),(89),(90),(91),(92),(93),(94),(95),(96),(97),(98),(99),(100),(101),(102),(103),(104),(105),(106),(107),(108),(109),(110),(111),(112),(113),(114),(115),(116),(117),(118),(119),(120),(121),(122),(123),(124),(125),(126),(127),(128),(129),(130),(131),(132),(133),(134),(135),(136),(137),(138),(139),(140),(141),(142),(143),(144),(145),(146),(147),(148),(149),(150),(151),(152),(153),(154),(155),(156),(157),(158),(159),(160),(161),(162),(163),(164),(165),(166),(167),(168),(169),(170),(171),(172),(173),(174),(175),(176),(177),(178),(179),(180),(181),(182),(183),(184),(185),(186),(187),(188),(189),(190),(191),(192),(193),(194),(195),(196),(197),(198),(199),(200),(201),(202),(203),(204),(205),(206),(207),(208),(209),(210),(211),(212),(213),(214),(215),(216),(217),(218),(219),(220),(221),(222),(223),(224),(225),(226),(227),(228),(229),(230),(231),(232),(233),(234),(235),(236),(237),(238),(239),(240),(241),(242),(243),(244),(245),(246),(247),(248),(249),(250),(251),(252),(253),(254),(255);
--rollback DROP TABLE iam_email_identity_lock;
--rollback DROP TABLE crm.sales_checkout_lock;


--changeset rm:0017-domain-persistence-baselines
-- Minimal tenant-scoped DB foundation for eight domain contexts.
-- No payments, SMTP, file IO, stock mutations, or public APIs in this changeset.
CREATE TABLE crm.file_object (
  tenant_id UUID NOT NULL, id UUID NOT NULL,
  original_name_envelope BYTEA, mime_type VARCHAR(120) NOT NULL,
  size_bytes BIGINT NOT NULL, sha256 VARCHAR(64),
  state VARCHAR(20) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_file_object PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_file_object_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT ck_file_object_size CHECK(size_bytes >= 0),
  CONSTRAINT ck_file_object_state CHECK(state IN ('STAGED','AVAILABLE','REJECTED','DELETING','DELETED'))
);
CREATE TABLE crm.file_storage (
  tenant_id UUID NOT NULL, file_id UUID NOT NULL,
  provider VARCHAR(20) NOT NULL, storage_key VARCHAR(500) NOT NULL,
  object_version VARCHAR(160), state VARCHAR(20) NOT NULL,
  CONSTRAINT pk_file_storage PRIMARY KEY(tenant_id,file_id),
  CONSTRAINT fk_file_storage_file FOREIGN KEY(tenant_id,file_id) REFERENCES crm.file_object(tenant_id,id),
  CONSTRAINT ck_file_storage_provider CHECK(provider IN ('LOCAL','RUSTFS')),
  CONSTRAINT ck_file_storage_state CHECK(state IN ('STAGED','ACTIVE','MIGRATING','DELETING'))
);
CREATE TABLE crm.file_attachment (
  tenant_id UUID NOT NULL,id UUID NOT NULL,file_id UUID NOT NULL,
  owner_type VARCHAR(32) NOT NULL,owner_id UUID NOT NULL,
  purpose VARCHAR(48) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_file_attachment PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_file_attachment_file FOREIGN KEY(tenant_id,file_id) REFERENCES crm.file_object(tenant_id,id)
);
CREATE INDEX idx_file_attachment_owner ON crm.file_attachment(tenant_id,owner_type,owner_id);

CREATE TABLE crm.cat_item (
  tenant_id UUID NOT NULL,id UUID NOT NULL,
  sku VARCHAR(80) NOT NULL,kind VARCHAR(20) NOT NULL,
  source_locale VARCHAR(12) NOT NULL,state VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_cat_item PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_cat_item_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT uk_cat_item_sku UNIQUE(tenant_id,sku),
  CONSTRAINT ck_cat_item_kind CHECK(kind IN ('PRODUCT','SERVICE')),
  CONSTRAINT ck_cat_item_state CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED')),
  CONSTRAINT ck_cat_item_locale CHECK(source_locale IN ('kk','ru','en','zh-Hans'))
);
CREATE TABLE crm.cat_item_i18n (
  tenant_id UUID NOT NULL,item_id UUID NOT NULL,locale VARCHAR(12) NOT NULL,
  name VARCHAR(240) NOT NULL,description TEXT,
  CONSTRAINT pk_cat_item_i18n PRIMARY KEY(tenant_id,item_id,locale),
  CONSTRAINT fk_cat_item_i18n_item FOREIGN KEY(tenant_id,item_id) REFERENCES crm.cat_item(tenant_id,id),
  CONSTRAINT ck_cat_item_i18n_locale CHECK(locale IN ('kk','ru','en','zh-Hans'))
);
CREATE TABLE crm.cat_price (
  tenant_id UUID NOT NULL,id UUID NOT NULL,item_id UUID NOT NULL,
  unit_price NUMERIC(19,4) NOT NULL,currency CHAR(3) NOT NULL,
  valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_cat_price PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_cat_price_item FOREIGN KEY(tenant_id,item_id) REFERENCES crm.cat_item(tenant_id,id),
  CONSTRAINT ck_cat_price_amount CHECK(unit_price >= 0)
);
CREATE INDEX idx_cat_price_lookup ON crm.cat_price(tenant_id,item_id,valid_from);

-- Strong tenant-local warehouse references; no cross-tenant location FK.
ALTER TABLE public.org_location ADD CONSTRAINT uk_org_location_workspace_id UNIQUE(workspace_id,id);
CREATE TABLE crm.inv_stock_balance (
  tenant_id UUID NOT NULL,location_id UUID NOT NULL,item_id UUID NOT NULL,
  on_hand NUMERIC(19,4) NOT NULL DEFAULT 0,
  reserved NUMERIC(19,4) NOT NULL DEFAULT 0,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_inv_stock_balance PRIMARY KEY(tenant_id,location_id,item_id),
  CONSTRAINT fk_inv_stock_location FOREIGN KEY(tenant_id,location_id) REFERENCES public.org_location(workspace_id,id),
  CONSTRAINT fk_inv_stock_item FOREIGN KEY(tenant_id,item_id) REFERENCES crm.cat_item(tenant_id,id),
  CONSTRAINT ck_inv_stock_balance CHECK(on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)
);

CREATE TABLE crm.fin_invoice (
  tenant_id UUID NOT NULL,id UUID NOT NULL,
  order_id UUID NOT NULL,document_no VARCHAR(80) NOT NULL,
  amount NUMERIC(19,4) NOT NULL,currency CHAR(3) NOT NULL,
  state VARCHAR(20) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_fin_invoice PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_fin_invoice_order FOREIGN KEY(tenant_id,order_id) REFERENCES crm.sales_order(tenant_id,id),
  CONSTRAINT uk_fin_invoice_no UNIQUE(tenant_id,document_no),
  CONSTRAINT ck_fin_invoice_amount CHECK(amount >= 0),
  CONSTRAINT ck_fin_invoice_state CHECK(state IN ('DRAFT','ISSUED','VOID'))
);

CREATE TABLE crm.mkt_campaign (
  tenant_id UUID NOT NULL,id UUID NOT NULL,
  state VARCHAR(20) NOT NULL,default_locale VARCHAR(12) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_mkt_campaign PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_mkt_campaign_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT ck_mkt_campaign_state CHECK(state IN ('DRAFT','APPROVED','SCHEDULED','SENDING','COMPLETED','CANCELLED')),
  CONSTRAINT ck_mkt_campaign_locale CHECK(default_locale IN ('kk','ru','en','zh-Hans'))
);

CREATE TABLE crm.task_item (
  tenant_id UUID NOT NULL,id UUID NOT NULL,
  assignee_user_id UUID,status VARCHAR(20) NOT NULL,
  due_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_task_item PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_task_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT fk_task_assignee FOREIGN KEY(assignee_user_id) REFERENCES public.iam_user_account(id),
  CONSTRAINT ck_task_status CHECK(status IN ('OPEN','IN_PROGRESS','BLOCKED','COMPLETED','ARCHIVED'))
);
CREATE INDEX idx_task_assignee ON crm.task_item(tenant_id,assignee_user_id,status);

CREATE TABLE crm.audit_event (
  tenant_id UUID NOT NULL,id UUID NOT NULL,
  actor_user_id UUID NOT NULL,entity_id UUID NOT NULL,
  action_code VARCHAR(80) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_audit_event PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_audit_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT fk_audit_actor FOREIGN KEY(actor_user_id) REFERENCES public.iam_user_account(id)
);
CREATE INDEX idx_audit_history ON crm.audit_event(tenant_id,occurred_at);

CREATE TABLE crm.sub_plan (
  id UUID NOT NULL PRIMARY KEY,code VARCHAR(80) NOT NULL UNIQUE,
  state VARCHAR(20) NOT NULL,
  CONSTRAINT ck_sub_plan_state CHECK(state IN ('DRAFT','ACTIVE','RETIRED'))
);
CREATE TABLE crm.sub_subscription (
  tenant_id UUID NOT NULL,id UUID NOT NULL,plan_id UUID NOT NULL,
  state VARCHAR(20) NOT NULL,started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  ends_at TIMESTAMP WITH TIME ZONE,row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_sub_subscription PRIMARY KEY(tenant_id,id),
  CONSTRAINT fk_sub_subscription_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT fk_sub_subscription_plan FOREIGN KEY(plan_id) REFERENCES crm.sub_plan(id),
  CONSTRAINT ck_sub_subscription_state CHECK(state IN ('TRIAL','ACTIVE','PAST_DUE','CANCELLED','EXPIRED')),
  CONSTRAINT ck_sub_subscription_period CHECK(ends_at IS NULL OR ends_at > started_at)
);
CREATE INDEX idx_sub_subscription_tenant ON crm.sub_subscription(tenant_id,state);

--rollback DROP TABLE crm.sub_subscription;
--rollback DROP TABLE crm.sub_plan;
--rollback DROP TABLE crm.audit_event;
--rollback DROP TABLE crm.task_item;
--rollback DROP TABLE crm.mkt_campaign;
--rollback DROP TABLE crm.fin_invoice;
--rollback DROP TABLE crm.inv_stock_balance;
--rollback ALTER TABLE public.org_location DROP CONSTRAINT uk_org_location_workspace_id;
--rollback DROP TABLE crm.cat_price;
--rollback DROP TABLE crm.cat_item_i18n;
--rollback DROP TABLE crm.cat_item;
--rollback DROP TABLE crm.file_attachment;
--rollback DROP TABLE crm.file_storage;
--rollback DROP TABLE crm.file_object;


--changeset rm:0018-audit-range-hash-postgresql dbms:postgresql
-- PostgreSQL-only online-admission HOLD: transactional, data-preserving shadow table swap.
-- Partition key is (occurred_at, tenant_id), and BOTH must be in the parent PK.
-- No external FK may point to crm.audit_event before running this migration.
ALTER TABLE crm.audit_event RENAME TO audit_event_prepart_0018;
CREATE TABLE crm.audit_event (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  entity_id UUID NOT NULL,
  action_code VARCHAR(80) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_audit_event_partitioned PRIMARY KEY(tenant_id,occurred_at,id),
  CONSTRAINT fk_audit_event_partitioned_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT fk_audit_event_partitioned_actor FOREIGN KEY(actor_user_id) REFERENCES public.iam_user_account(id)
) PARTITION BY RANGE (occurred_at);

-- Annual partitions are a conservative initial sizing; monthly partitions require workload proof.
CREATE TABLE crm.audit_event_2025 PARTITION OF crm.audit_event
  FOR VALUES FROM ('2025-01-01 00:00:00+00') TO ('2026-01-01 00:00:00+00')
  PARTITION BY HASH (tenant_id);
CREATE TABLE crm.audit_event_2026 PARTITION OF crm.audit_event
  FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2027-01-01 00:00:00+00')
  PARTITION BY HASH (tenant_id);
CREATE TABLE crm.audit_event_2027 PARTITION OF crm.audit_event
  FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2028-01-01 00:00:00+00')
  PARTITION BY HASH (tenant_id);
CREATE TABLE crm.audit_event_other PARTITION OF crm.audit_event DEFAULT
  PARTITION BY HASH (tenant_id);

CREATE TABLE crm.audit_event_2025_h0 PARTITION OF crm.audit_event_2025 FOR VALUES WITH (MODULUS 4,REMAINDER 0);
CREATE TABLE crm.audit_event_2025_h1 PARTITION OF crm.audit_event_2025 FOR VALUES WITH (MODULUS 4,REMAINDER 1);
CREATE TABLE crm.audit_event_2025_h2 PARTITION OF crm.audit_event_2025 FOR VALUES WITH (MODULUS 4,REMAINDER 2);
CREATE TABLE crm.audit_event_2025_h3 PARTITION OF crm.audit_event_2025 FOR VALUES WITH (MODULUS 4,REMAINDER 3);
CREATE TABLE crm.audit_event_2026_h0 PARTITION OF crm.audit_event_2026 FOR VALUES WITH (MODULUS 4,REMAINDER 0);
CREATE TABLE crm.audit_event_2026_h1 PARTITION OF crm.audit_event_2026 FOR VALUES WITH (MODULUS 4,REMAINDER 1);
CREATE TABLE crm.audit_event_2026_h2 PARTITION OF crm.audit_event_2026 FOR VALUES WITH (MODULUS 4,REMAINDER 2);
CREATE TABLE crm.audit_event_2026_h3 PARTITION OF crm.audit_event_2026 FOR VALUES WITH (MODULUS 4,REMAINDER 3);
CREATE TABLE crm.audit_event_2027_h0 PARTITION OF crm.audit_event_2027 FOR VALUES WITH (MODULUS 4,REMAINDER 0);
CREATE TABLE crm.audit_event_2027_h1 PARTITION OF crm.audit_event_2027 FOR VALUES WITH (MODULUS 4,REMAINDER 1);
CREATE TABLE crm.audit_event_2027_h2 PARTITION OF crm.audit_event_2027 FOR VALUES WITH (MODULUS 4,REMAINDER 2);
CREATE TABLE crm.audit_event_2027_h3 PARTITION OF crm.audit_event_2027 FOR VALUES WITH (MODULUS 4,REMAINDER 3);
CREATE TABLE crm.audit_event_other_h0 PARTITION OF crm.audit_event_other FOR VALUES WITH (MODULUS 4,REMAINDER 0);
CREATE TABLE crm.audit_event_other_h1 PARTITION OF crm.audit_event_other FOR VALUES WITH (MODULUS 4,REMAINDER 1);
CREATE TABLE crm.audit_event_other_h2 PARTITION OF crm.audit_event_other FOR VALUES WITH (MODULUS 4,REMAINDER 2);
CREATE TABLE crm.audit_event_other_h3 PARTITION OF crm.audit_event_other FOR VALUES WITH (MODULUS 4,REMAINDER 3);

CREATE INDEX idx_audit_partitioned_tenant_time ON crm.audit_event(tenant_id,occurred_at DESC);
-- The old table remains exclusively a transactional migration source, never a dual-write destination.
INSERT INTO crm.audit_event(tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
SELECT tenant_id,id,actor_user_id,entity_id,action_code,occurred_at
FROM crm.audit_event_prepart_0018;
-- Verify preserved source/target row counts before retiring the original table.
-- Preconditions on inbound foreign keys and migration volume must be reviewed before production admission.
DROP TABLE crm.audit_event_prepart_0018;
-- Rollback is intentionally not automated: forward migration requires PITR/verified backup policy.

--changeset rm:0019-partition-aware-query-indexes
-- These are deliberately ordinary composite indexes on unpartitioned mutable aggregates.
CREATE INDEX idx_sales_order_tenant_created ON crm.sales_order(tenant_id,created_at DESC);
CREATE INDEX idx_sales_order_tenant_buyer_created ON crm.sales_order(tenant_id,buyer_user_id,created_at DESC);
CREATE INDEX idx_sales_status_tenant_order_time ON crm.sales_order_status_event(tenant_id,order_id,occurred_at DESC);
CREATE INDEX idx_crm_customer_tenant_created ON crm.customer(tenant_id,created_at DESC);
CREATE INDEX idx_crm_contact_tenant_created ON crm.contact(tenant_id,created_at DESC);
CREATE INDEX idx_files_tenant_state_created ON crm.file_object(tenant_id,state,created_at DESC);
CREATE INDEX idx_fin_invoice_tenant_created ON crm.fin_invoice(tenant_id,created_at DESC);
--rollback DROP INDEX idx_fin_invoice_tenant_created;
--rollback DROP INDEX idx_files_tenant_state_created;
--rollback DROP INDEX idx_crm_contact_tenant_created;
--rollback DROP INDEX idx_crm_customer_tenant_created;
--rollback DROP INDEX idx_sales_status_tenant_order_time;
--rollback DROP INDEX idx_sales_order_tenant_buyer_created;
--rollback DROP INDEX idx_sales_order_tenant_created;


--changeset rm:0020-audit-retention-governance
-- Retention is opt-in per tenant after an approved policy. Absence of approval => NO PURGE.
-- These tables are intentionally unpartitioned control-plane metadata.
CREATE TABLE crm.audit_retention_policy (
  tenant_id UUID NOT NULL PRIMARY KEY,
  retention_years SMALLINT NOT NULL DEFAULT 5,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  approved_at TIMESTAMP WITH TIME ZONE,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_audit_retention_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT ck_audit_retention_years CHECK(retention_years BETWEEN 3 AND 5),
  CONSTRAINT ck_audit_retention_approval CHECK(NOT enabled OR approved_at IS NOT NULL)
);
-- Legal holds are by stable tenant/event UUID; separate table avoids changing
-- the PK/partition key of the existing audit journal.
CREATE TABLE crm.audit_retention_hold (
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  reason_code VARCHAR(80) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  released_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_audit_retention_hold PRIMARY KEY(tenant_id,event_id),
  CONSTRAINT fk_audit_hold_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
  CONSTRAINT ck_audit_hold_dates CHECK(released_at IS NULL OR released_at >= created_at)
);
CREATE TABLE crm.audit_retention_run (
  id UUID NOT NULL PRIMARY KEY,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  finished_at TIMESTAMP WITH TIME ZONE,
  deleted_rows BIGINT NOT NULL DEFAULT 0,
  batch_count INTEGER NOT NULL DEFAULT 0,
  outcome VARCHAR(20) NOT NULL,
  CONSTRAINT ck_audit_run_rows CHECK(deleted_rows >= 0 AND batch_count >= 0),
  CONSTRAINT ck_audit_run_outcome CHECK(outcome IN ('RUNNING','DONE','FAILED','SKIPPED'))
);
--rollback DROP TABLE crm.audit_retention_run;
--rollback DROP TABLE crm.audit_retention_hold;
--rollback DROP TABLE crm.audit_retention_policy;


--changeset rm:0021-audit-retention-governance-lock dbms:postgresql splitStatements:false
-- Governance writes serialize against the nightly cleanup transaction.
-- The row-batch worker holds the matching advisory transaction lock.
CREATE FUNCTION crm.audit_retention_governance_lock() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(701121,1);
  RETURN NULL;
END;
$$;
CREATE TRIGGER trg_audit_retention_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON crm.audit_retention_policy
FOR EACH STATEMENT EXECUTE FUNCTION crm.audit_retention_governance_lock();
CREATE TRIGGER trg_audit_retention_hold_lock
BEFORE INSERT OR UPDATE OR DELETE ON crm.audit_retention_hold
FOR EACH STATEMENT EXECUTE FUNCTION crm.audit_retention_governance_lock();
--rollback DROP TRIGGER trg_audit_retention_hold_lock ON crm.audit_retention_hold;
--rollback DROP TRIGGER trg_audit_retention_policy_lock ON crm.audit_retention_policy;
--rollback DROP FUNCTION crm.audit_retention_governance_lock();


--changeset rm:0022-inventory-immutable-stock-movement
-- Append-only stock movements; no automatic purge under the AUDIT retention policy.
-- The balance row and movement are committed atomically by INVENTORY application service.
CREATE TABLE crm.inv_stock_movement (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  location_id UUID NOT NULL,
  item_id UUID NOT NULL,
  request_key VARCHAR(100) NOT NULL,
  delta_quantity NUMERIC(19,4) NOT NULL,
  balance_before NUMERIC(19,4) NOT NULL,
  balance_after NUMERIC(19,4) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_inv_stock_movement PRIMARY KEY(tenant_id,id),
  CONSTRAINT uk_inv_stock_movement_request UNIQUE(tenant_id,request_key),
  CONSTRAINT fk_inv_stock_movement_balance FOREIGN KEY(tenant_id,location_id,item_id)
    REFERENCES crm.inv_stock_balance(tenant_id,location_id,item_id),
  CONSTRAINT ck_inv_stock_movement_nonzero CHECK(delta_quantity <> 0),
  CONSTRAINT ck_inv_stock_movement_nonnegative CHECK(balance_before >= 0 AND balance_after >= 0),
  CONSTRAINT ck_inv_stock_movement_math CHECK(balance_after = balance_before + delta_quantity)
);
CREATE INDEX idx_inv_movement_tenant_location_time
 ON crm.inv_stock_movement(tenant_id,location_id,occurred_at DESC);
--rollback DROP TABLE crm.inv_stock_movement;


--changeset rm:0023-inventory-request-key-lock
-- Lock order in InventoryMovementService: tenant/request stripe before balance row.
CREATE TABLE crm.inv_movement_request_lock (
 bucket INTEGER NOT NULL PRIMARY KEY
);
INSERT INTO crm.inv_movement_request_lock(bucket) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15),(16),(17),(18),(19),(20),(21),(22),(23),(24),(25),(26),(27),(28),(29),(30),(31),(32),(33),(34),(35),(36),(37),(38),(39),(40),(41),(42),(43),(44),(45),(46),(47),(48),(49),(50),(51),(52),(53),(54),(55),(56),(57),(58),(59),(60),(61),(62),(63);
--rollback DROP TABLE crm.inv_movement_request_lock;


--changeset rm:0024-inventory-ledger-immutability dbms:postgresql splitStatements:false
-- Once recorded, inventory movements are append-only. Corrections are new movements.
CREATE FUNCTION crm.inv_reject_movement_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Inventory movements are immutable; post a corrective movement';
END;
$$;
CREATE TRIGGER trg_inv_movement_immutable
BEFORE UPDATE OR DELETE ON crm.inv_stock_movement
FOR EACH ROW EXECUTE FUNCTION crm.inv_reject_movement_mutation();
--rollback DROP TRIGGER trg_inv_movement_immutable ON crm.inv_stock_movement;
--rollback DROP FUNCTION crm.inv_reject_movement_mutation();


--changeset rm:0025-inventory-reservation-contract
-- Mutable reservations are intentionally NOT partitioned: unique per sales line.
-- A reservation belongs to one seller tenant, one order line, and one stock location/SKU.
ALTER TABLE crm.sales_order_line
 ADD CONSTRAINT uk_sales_order_line_tenant_order_id UNIQUE(tenant_id,order_id,id);
CREATE TABLE crm.inv_stock_reservation (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  order_id UUID NOT NULL,
  order_line_id UUID NOT NULL,
  location_id UUID NOT NULL,
  item_id UUID NOT NULL,
  quantity NUMERIC(19,4) NOT NULL,
  state VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_inv_stock_reservation PRIMARY KEY(tenant_id,id),
  CONSTRAINT uk_inv_reservation_order_line UNIQUE(tenant_id,order_line_id),
  CONSTRAINT fk_inv_reservation_order_line FOREIGN KEY(tenant_id,order_id,order_line_id)
    REFERENCES crm.sales_order_line(tenant_id,order_id,id),
  CONSTRAINT fk_inv_reservation_balance FOREIGN KEY(tenant_id,location_id,item_id)
    REFERENCES crm.inv_stock_balance(tenant_id,location_id,item_id),
  CONSTRAINT ck_inv_reservation_quantity CHECK(quantity > 0),
  CONSTRAINT ck_inv_reservation_state CHECK(state IN ('ACTIVE','RELEASED','FULFILLED'))
);
CREATE INDEX idx_inv_reservation_tenant_state
 ON crm.inv_stock_reservation(tenant_id,state,created_at);
CREATE INDEX idx_inv_reservation_tenant_order
 ON crm.inv_stock_reservation(tenant_id,order_id);
-- Enrich the existing append-only movement ledger; no changes to old changesets.
ALTER TABLE crm.inv_stock_movement
 ADD COLUMN movement_kind VARCHAR(24) NOT NULL DEFAULT 'ADJUSTMENT';
ALTER TABLE crm.inv_stock_movement
 ADD COLUMN reason_code VARCHAR(64) NOT NULL DEFAULT 'MANUAL_ADJUSTMENT';
ALTER TABLE crm.inv_stock_movement
 ADD COLUMN reservation_id UUID;
ALTER TABLE crm.inv_stock_movement
 ADD CONSTRAINT ck_inv_movement_kind CHECK(movement_kind IN ('ADJUSTMENT','FULFILLMENT'));
ALTER TABLE crm.inv_stock_movement
 ADD CONSTRAINT ck_inv_movement_reason CHECK(length(trim(reason_code)) >= 3);
ALTER TABLE crm.inv_stock_movement
 ADD CONSTRAINT ck_inv_movement_fulfillment_ref CHECK(
   (movement_kind='FULFILLMENT' AND reservation_id IS NOT NULL AND delta_quantity < 0)
   OR (movement_kind='ADJUSTMENT' AND reservation_id IS NULL)
 );
ALTER TABLE crm.inv_stock_movement
 ADD CONSTRAINT fk_inv_movement_reservation FOREIGN KEY(tenant_id,reservation_id)
 REFERENCES crm.inv_stock_reservation(tenant_id,id);
CREATE UNIQUE INDEX uk_inv_movement_fulfilled_reservation
 ON crm.inv_stock_movement(tenant_id,reservation_id);
--rollback DROP INDEX uk_inv_movement_fulfilled_reservation;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT fk_inv_movement_reservation;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT ck_inv_movement_fulfillment_ref;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT ck_inv_movement_reason;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT ck_inv_movement_kind;
--rollback ALTER TABLE crm.inv_stock_movement DROP COLUMN reservation_id;
--rollback ALTER TABLE crm.inv_stock_movement DROP COLUMN reason_code;
--rollback ALTER TABLE crm.inv_stock_movement DROP COLUMN movement_kind;
--rollback DROP TABLE crm.inv_stock_reservation;
--rollback ALTER TABLE crm.sales_order_line DROP CONSTRAINT uk_sales_order_line_tenant_order_id;


--changeset rm:0026-store-offer-catalog-identity
-- Provisional storefront offers must resolve to an actual tenant-owned catalog SKU.
-- Existing offers remain unmapped and cannot enter inventory reservation until backfilled.
ALTER TABLE crm.store_offer ADD COLUMN catalog_item_id UUID;
ALTER TABLE crm.store_offer ADD CONSTRAINT fk_store_offer_catalog_item
 FOREIGN KEY (tenant_id,catalog_item_id) REFERENCES crm.cat_item(tenant_id,id);
CREATE INDEX idx_store_offer_catalog ON crm.store_offer(tenant_id,catalog_item_id);
--rollback DROP INDEX idx_store_offer_catalog;
--rollback ALTER TABLE crm.store_offer DROP CONSTRAINT fk_store_offer_catalog_item;
--rollback ALTER TABLE crm.store_offer DROP COLUMN catalog_item_id;

--changeset rm:0027-inventory-reservation-evidence
-- Reservation lifecycle evidence is independent of balance-change movements.
CREATE TABLE crm.inv_reservation_event (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 reservation_id UUID NOT NULL,
 event_type VARCHAR(16) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_inv_reservation_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_inv_reservation_event_state UNIQUE(tenant_id,reservation_id,event_type),
 CONSTRAINT fk_inv_reservation_event_reservation FOREIGN KEY(tenant_id,reservation_id)
  REFERENCES crm.inv_stock_reservation(tenant_id,id),
 CONSTRAINT ck_inv_reservation_event_type CHECK(event_type IN ('RESERVED','RELEASED','FULFILLED'))
);
CREATE INDEX idx_inv_reservation_event_history
 ON crm.inv_reservation_event(tenant_id,reservation_id,occurred_at);
--rollback DROP TABLE crm.inv_reservation_event;

--changeset rm:0028-inventory-reservation-evidence-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION crm.inv_reject_reservation_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Inventory reservation history is immutable';
END;
$$;
CREATE TRIGGER trg_inv_reservation_event_immutable
BEFORE UPDATE OR DELETE ON crm.inv_reservation_event
FOR EACH ROW EXECUTE FUNCTION crm.inv_reject_reservation_event_mutation();
--rollback DROP TRIGGER trg_inv_reservation_event_immutable ON crm.inv_reservation_event;
--rollback DROP FUNCTION crm.inv_reject_reservation_event_mutation();


--changeset rm:0029-inventory-tenant-valuation-settings
-- One tenant configuration; accounting valuation must reference an approved immutable revision.
-- Unapproved/new tenants have NO active cost method (fail closed for cost posting).
CREATE TABLE crm.inv_tenant_setting (
 tenant_id UUID NOT NULL PRIMARY KEY,
 picking_strategy VARCHAR(20) NOT NULL DEFAULT 'FIFO',
 auto_reserve_on_confirm BOOLEAN NOT NULL DEFAULT FALSE,
 allow_negative_stock BOOLEAN NOT NULL DEFAULT FALSE,
 active_policy_version_id UUID,
 row_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_inv_setting_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT ck_inv_setting_pick CHECK(picking_strategy IN ('FIFO','FEFO','MANUAL')),
 CONSTRAINT ck_inv_setting_no_negative CHECK(allow_negative_stock=FALSE),
 CONSTRAINT ck_inv_setting_row_version CHECK(row_version>=0)
);
CREATE TABLE crm.inv_cost_policy_version (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 revision_no INTEGER NOT NULL,
 valuation_method VARCHAR(40) NOT NULL,
 functional_currency CHAR(3) NOT NULL,
 effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
 approved_by UUID NOT NULL,
 approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_inv_cost_policy_version PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_inv_cost_policy_setting FOREIGN KEY(tenant_id)
   REFERENCES crm.inv_tenant_setting(tenant_id),
 CONSTRAINT fk_inv_cost_policy_approver FOREIGN KEY(approved_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT uk_inv_cost_policy_revision UNIQUE(tenant_id,revision_no),
 CONSTRAINT ck_inv_cost_policy_revision CHECK(revision_no>0),
 CONSTRAINT ck_inv_cost_policy_method CHECK(valuation_method IN
   ('FIFO','MOVING_WEIGHTED_AVERAGE','SPECIFIC_IDENTIFICATION')),
 CONSTRAINT ck_inv_cost_policy_currency CHECK(
   length(trim(functional_currency))=3 AND upper(functional_currency)=functional_currency)
);
ALTER TABLE crm.inv_tenant_setting
 ADD CONSTRAINT fk_inv_setting_active_policy
 FOREIGN KEY(tenant_id,active_policy_version_id)
 REFERENCES crm.inv_cost_policy_version(tenant_id,id);
--rollback ALTER TABLE crm.inv_tenant_setting DROP CONSTRAINT fk_inv_setting_active_policy;
--rollback DROP TABLE crm.inv_cost_policy_version;
--rollback DROP TABLE crm.inv_tenant_setting;

--changeset rm:0030-inventory-cost-policy-immutable dbms:postgresql splitStatements:false
-- Approved methods cannot be rewritten or deleted; new approval = new revision.
CREATE FUNCTION crm.inv_reject_cost_policy_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Approved inventory cost policy is immutable';
END;
$$;
CREATE TRIGGER trg_inv_cost_policy_immutable
BEFORE UPDATE OR DELETE ON crm.inv_cost_policy_version
FOR EACH ROW EXECUTE FUNCTION crm.inv_reject_cost_policy_mutation();
--rollback DROP TRIGGER trg_inv_cost_policy_immutable ON crm.inv_cost_policy_version;
--rollback DROP FUNCTION crm.inv_reject_cost_policy_mutation();


--changeset rm:0031-tenant-scoped-rbac
-- R1.1: per-tenant functional roles, no hardcoded warehouse/accountant role types.
-- RBAC is opt-in per existing tenant until its role grants and assignments are migrated.
CREATE TABLE public.iam_tenant_rbac_config (
 tenant_id UUID NOT NULL PRIMARY KEY,
 enforced BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_rbac_config_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT ck_rbac_config_version CHECK(row_version>=0)
);
CREATE TABLE public.iam_tenant_role (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 role_code VARCHAR(64) NOT NULL,
 display_name VARCHAR(120) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_tenant_role PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_tenant_role_code UNIQUE(tenant_id,role_code),
 CONSTRAINT fk_tenant_role_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT ck_tenant_role_code CHECK(length(role_code)>=3)
);
CREATE TABLE public.iam_tenant_role_permission (
 tenant_id UUID NOT NULL,
 role_id UUID NOT NULL,
 permission_code VARCHAR(90) NOT NULL,
 CONSTRAINT pk_tenant_role_permission PRIMARY KEY(tenant_id,role_id,permission_code),
 CONSTRAINT fk_role_permission_role FOREIGN KEY(tenant_id,role_id)
   REFERENCES public.iam_tenant_role(tenant_id,id)
);
CREATE TABLE public.iam_tenant_user_role (
 tenant_id UUID NOT NULL,
 user_id UUID NOT NULL,
 role_id UUID NOT NULL,
 assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_tenant_user_role PRIMARY KEY(tenant_id,user_id,role_id),
 CONSTRAINT fk_user_role_role FOREIGN KEY(tenant_id,role_id)
   REFERENCES public.iam_tenant_role(tenant_id,id),
 CONSTRAINT fk_user_role_account FOREIGN KEY(user_id) REFERENCES public.iam_user_account(id)
);
CREATE INDEX idx_tenant_user_role_actor ON public.iam_tenant_user_role(tenant_id,user_id);
CREATE TABLE public.iam_tenant_rbac_event (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 action_code VARCHAR(40) NOT NULL,
 target_user_id UUID,
 role_id UUID,
 permission_code VARCHAR(90),
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_tenant_rbac_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_rbac_event_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT fk_rbac_event_actor FOREIGN KEY(actor_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_rbac_event_action CHECK(action_code IN
   ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED','RBAC_ENABLED'))
);
CREATE INDEX idx_tenant_rbac_event_time ON public.iam_tenant_rbac_event(tenant_id,occurred_at DESC);
--rollback DROP TABLE public.iam_tenant_rbac_event;
--rollback DROP TABLE public.iam_tenant_user_role;
--rollback DROP TABLE public.iam_tenant_role_permission;
--rollback DROP TABLE public.iam_tenant_role;
--rollback DROP TABLE public.iam_tenant_rbac_config;

--changeset rm:0032-tenant-rbac-event-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION public.iam_reject_rbac_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Tenant RBAC history is immutable';
END;
$$;
CREATE TRIGGER trg_iam_rbac_event_immutable
BEFORE UPDATE OR DELETE ON public.iam_tenant_rbac_event
FOR EACH ROW EXECUTE FUNCTION public.iam_reject_rbac_event_mutation();
--rollback DROP TRIGGER trg_iam_rbac_event_immutable ON public.iam_tenant_rbac_event;
--rollback DROP FUNCTION public.iam_reject_rbac_event_mutation();


--changeset rm:0033-tenant-rbac-role-activation-evidence
-- Functional role suspension/reactivation must be auditable; never rewrite 0031.
ALTER TABLE public.iam_tenant_rbac_event DROP CONSTRAINT ck_rbac_event_action;
ALTER TABLE public.iam_tenant_rbac_event ADD CONSTRAINT ck_rbac_event_action CHECK(action_code IN
 ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED',
  'RBAC_ENABLED','ROLE_DEACTIVATED','ROLE_ACTIVATED'));
--rollback ALTER TABLE public.iam_tenant_rbac_event DROP CONSTRAINT ck_rbac_event_action;
--rollback ALTER TABLE public.iam_tenant_rbac_event ADD CONSTRAINT ck_rbac_event_action CHECK(action_code IN
--rollback  ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED','RBAC_ENABLED'));


--changeset rm:0034-tenant-access-status-evidence
-- Lifecycle events extend the existing immutable tenant evidence, not historical changeset 0033.
ALTER TABLE public.iam_tenant_rbac_event DROP CONSTRAINT ck_rbac_event_action;
ALTER TABLE public.iam_tenant_rbac_event ADD CONSTRAINT ck_rbac_event_action CHECK(action_code IN
 ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED',
  'RBAC_ENABLED','ROLE_DEACTIVATED','ROLE_ACTIVATED',
  'MEMBER_SUSPENDED','MEMBER_RESTORED','TENANT_SUSPENDED','TENANT_RESTORED'));
--rollback ALTER TABLE public.iam_tenant_rbac_event DROP CONSTRAINT ck_rbac_event_action;
--rollback ALTER TABLE public.iam_tenant_rbac_event ADD CONSTRAINT ck_rbac_event_action CHECK(action_code IN
--rollback  ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED',
--rollback   'RBAC_ENABLED','ROLE_DEACTIVATED','ROLE_ACTIVATED'));

--changeset rm:0035-account-status-audit
-- Cross-tenant/global account changes need an independent immutable audit stream.
CREATE TABLE public.iam_account_status_event (
 id UUID NOT NULL PRIMARY KEY,
 actor_user_id UUID NOT NULL,
 target_user_id UUID NOT NULL,
 from_status VARCHAR(20) NOT NULL,
 to_status VARCHAR(20) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_account_status_actor FOREIGN KEY(actor_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_account_status_target FOREIGN KEY(target_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_account_status_transition CHECK(
   (from_status='ACTIVE' AND to_status='SUSPENDED')
   OR (from_status='SUSPENDED' AND to_status='ACTIVE'))
);
CREATE INDEX idx_account_status_event_target ON public.iam_account_status_event(target_user_id,occurred_at DESC);
--rollback DROP TABLE public.iam_account_status_event;

--changeset rm:0036-account-status-audit-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION public.iam_reject_account_status_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Account status history is immutable';
END;
$$;
CREATE TRIGGER trg_iam_account_status_event_immutable
BEFORE UPDATE OR DELETE ON public.iam_account_status_event
FOR EACH ROW EXECUTE FUNCTION public.iam_reject_account_status_event_mutation();
--rollback DROP TRIGGER trg_iam_account_status_event_immutable ON public.iam_account_status_event;
--rollback DROP FUNCTION public.iam_reject_account_status_event_mutation();

--changeset rm:0037-iam-user-onboarding-evidence
-- Extend immutable tenant event actions; applied changesets are not modified.
ALTER TABLE public.iam_tenant_rbac_event DROP CONSTRAINT ck_rbac_event_action;
ALTER TABLE public.iam_tenant_rbac_event ADD CONSTRAINT ck_rbac_event_action CHECK(action_code IN
 ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED',
  'RBAC_ENABLED','ROLE_DEACTIVATED','ROLE_ACTIVATED',
  'MEMBER_SUSPENDED','MEMBER_RESTORED','TENANT_SUSPENDED','TENANT_RESTORED',
  'TENANT_PROVISIONED','MEMBER_INVITED','MEMBER_JOINED'));
--rollback ALTER TABLE public.iam_tenant_rbac_event DROP CONSTRAINT ck_rbac_event_action;
--rollback ALTER TABLE public.iam_tenant_rbac_event ADD CONSTRAINT ck_rbac_event_action CHECK(action_code IN
--rollback  ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED',
--rollback   'RBAC_ENABLED','ROLE_DEACTIVATED','ROLE_ACTIVATED',
--rollback   'MEMBER_SUSPENDED','MEMBER_RESTORED','TENANT_SUSPENDED','TENANT_RESTORED'));


--changeset rm:0038-platform-admin-guard-and-evidence
-- Single row serializes global admin grants, revocations and one-time bootstrap.
CREATE TABLE public.iam_global_admin_guard (
 id SMALLINT PRIMARY KEY,
 bootstrapped BOOLEAN NOT NULL DEFAULT FALSE,
 bootstrapped_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT ck_global_admin_guard_single CHECK(id=1),
 CONSTRAINT ck_global_admin_guard_state CHECK((bootstrapped=FALSE AND bootstrapped_at IS NULL)
          OR (bootstrapped=TRUE AND bootstrapped_at IS NOT NULL))
);
INSERT INTO public.iam_global_admin_guard(id,bootstrapped) VALUES (1,FALSE);
CREATE TABLE public.iam_global_role_event (
 id UUID NOT NULL PRIMARY KEY,
 actor_user_id UUID NOT NULL,
 target_user_id UUID NOT NULL,
 action_code VARCHAR(40) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_global_role_event_actor FOREIGN KEY(actor_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_global_role_event_target FOREIGN KEY(target_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_global_role_event_action CHECK(action_code IN ('ADMIN_BOOTSTRAPPED','ADMIN_GRANTED','ADMIN_REVOKED'))
);
CREATE INDEX idx_global_role_event_target ON public.iam_global_role_event(target_user_id,occurred_at DESC);
--rollback DROP TABLE public.iam_global_role_event;
--rollback DROP TABLE public.iam_global_admin_guard;

--changeset rm:0039-global-role-evidence-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION public.iam_reject_global_role_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Global role evidence is immutable';
END;
$$;
CREATE TRIGGER trg_iam_global_role_event_immutable
BEFORE UPDATE OR DELETE ON public.iam_global_role_event
FOR EACH ROW EXECUTE FUNCTION public.iam_reject_global_role_event_mutation();
--rollback DROP TRIGGER trg_iam_global_role_event_immutable ON public.iam_global_role_event;
--rollback DROP FUNCTION public.iam_reject_global_role_event_mutation();

--changeset rm:0040-owner-transfer-request
-- One active or terminal transfer record per tenant; accepted/cancelled transitions
-- are evidenced by tenant RBAC events before a subsequent request can replace it.
CREATE TABLE public.iam_owner_transfer_request (
 tenant_id UUID NOT NULL PRIMARY KEY,
 request_id UUID NOT NULL,
 from_user_id UUID NOT NULL,
 to_user_id UUID NOT NULL,
 state VARCHAR(20) NOT NULL,
 requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 decided_at TIMESTAMP WITH TIME ZONE,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_owner_transfer_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT fk_owner_transfer_from FOREIGN KEY(from_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_owner_transfer_to FOREIGN KEY(to_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_owner_transfer_people CHECK(from_user_id<>to_user_id),
 CONSTRAINT ck_owner_transfer_state CHECK(state IN ('PENDING','ACCEPTED','CANCELLED')),
 CONSTRAINT ck_owner_transfer_expiry CHECK(expires_at>requested_at),
 CONSTRAINT ck_owner_transfer_decided CHECK(
    (state='PENDING' AND decided_at IS NULL) OR
    (state<>'PENDING' AND decided_at IS NOT NULL))
);
--rollback DROP TABLE public.iam_owner_transfer_request;

--changeset rm:0041-owner-lifecycle-evidence
-- Amend the event constraint by adding actions; preserve historical migrations.
ALTER TABLE public.iam_tenant_rbac_event DROP CONSTRAINT ck_rbac_event_action;
ALTER TABLE public.iam_tenant_rbac_event ADD CONSTRAINT ck_rbac_event_action CHECK(action_code IN
 ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED',
  'RBAC_ENABLED','ROLE_DEACTIVATED','ROLE_ACTIVATED',
  'MEMBER_SUSPENDED','MEMBER_RESTORED','TENANT_SUSPENDED','TENANT_RESTORED',
  'TENANT_PROVISIONED','MEMBER_INVITED','MEMBER_JOINED',
  'OWNER_TRANSFER_REQUESTED','OWNER_TRANSFER_ACCEPTED','OWNER_TRANSFER_CANCELLED',
  'OWNER_RECOVERED','OWNER_DEMOTED_BY_RECOVERY'));
--rollback ALTER TABLE public.iam_tenant_rbac_event DROP CONSTRAINT ck_rbac_event_action;
--rollback ALTER TABLE public.iam_tenant_rbac_event ADD CONSTRAINT ck_rbac_event_action CHECK(action_code IN
--rollback  ('ROLE_CREATED','GRANT_ADDED','GRANT_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED',
--rollback   'RBAC_ENABLED','ROLE_DEACTIVATED','ROLE_ACTIVATED',
--rollback   'MEMBER_SUSPENDED','MEMBER_RESTORED','TENANT_SUSPENDED','TENANT_RESTORED',
--rollback   'TENANT_PROVISIONED','MEMBER_INVITED','MEMBER_JOINED'));


--changeset rm:0042-legacy-global-admin-bootstrap-closure
-- When upgrading a populated DB with pre-existing administrators, prevent
-- a newly enabled offline bootstrap from granting an additional first admin.
UPDATE public.iam_global_admin_guard
SET bootstrapped=TRUE,bootstrapped_at=CURRENT_TIMESTAMP
WHERE id=1 AND bootstrapped=FALSE
  AND EXISTS (SELECT 1 FROM public.iam_global_role_assignment WHERE role='PLATFORM_ADMIN');
--rollback SELECT 1;


--changeset rm:0043-owner-recovery-reason
-- Only emergency owner recovery requires a documented external incident/ticket.
ALTER TABLE public.iam_tenant_rbac_event ADD COLUMN reason_code VARCHAR(80);
--rollback ALTER TABLE public.iam_tenant_rbac_event DROP COLUMN reason_code;


--changeset rm:0044-iam-registration-purpose-and-evidence
-- Initial signup is never verified by default. Existing checkout challenges
-- retain CHECKOUT purpose. No email state/identity remapping in R2.5A.
ALTER TABLE public.iam_email_verification_challenge
  ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT 'CHECKOUT';
ALTER TABLE public.iam_email_verification_challenge
  ADD CONSTRAINT ck_iam_email_challenge_purpose
  CHECK (purpose IN ('CHECKOUT','REGISTRATION'));
CREATE TABLE public.iam_email_verification_event (
  id UUID PRIMARY KEY,
  challenge_id UUID NOT NULL UNIQUE,
  email_identity_id UUID NOT NULL,
  user_id UUID NOT NULL,
  purpose VARCHAR(24) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_iam_verification_event_challenge FOREIGN KEY (challenge_id)
    REFERENCES public.iam_email_verification_challenge(id),
  CONSTRAINT fk_iam_verification_event_identity FOREIGN KEY (email_identity_id)
    REFERENCES public.iam_email_identity(id),
  CONSTRAINT fk_iam_verification_event_user FOREIGN KEY (user_id)
    REFERENCES public.iam_user_account(id),
  CONSTRAINT ck_iam_verification_event_purpose CHECK (purpose IN ('CHECKOUT','REGISTRATION'))
);
CREATE INDEX idx_iam_verification_event_identity
  ON public.iam_email_verification_event(email_identity_id,occurred_at DESC);
--rollback DROP TABLE public.iam_email_verification_event;
--rollback ALTER TABLE public.iam_email_verification_challenge DROP CONSTRAINT ck_iam_email_challenge_purpose;
--rollback ALTER TABLE public.iam_email_verification_challenge DROP COLUMN purpose;

--changeset rm:0045-iam-verification-evidence-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION public.iam_reject_email_verification_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Email verification evidence is immutable';
END;
$$;
CREATE TRIGGER trg_iam_email_verification_event_immutable
 BEFORE UPDATE OR DELETE ON public.iam_email_verification_event
 FOR EACH ROW EXECUTE FUNCTION public.iam_reject_email_verification_event_mutation();
--rollback DROP TRIGGER trg_iam_email_verification_event_immutable ON public.iam_email_verification_event;
--rollback DROP FUNCTION public.iam_reject_email_verification_event_mutation();


--changeset rm:0046-iam-email-lifecycle-and-active-lookup
-- Keep historical email envelopes and order foreign keys immutable. NULLs in
-- unique columns allow arbitrary histories but only one live email owner.
ALTER TABLE public.iam_email_identity
 ADD COLUMN lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE public.iam_email_identity ADD COLUMN pending_reason VARCHAR(20);
ALTER TABLE public.iam_email_identity ADD COLUMN active_lookup_hmac BYTEA;
ALTER TABLE public.iam_email_identity ADD COLUMN current_owner_id UUID;
ALTER TABLE public.iam_email_identity ADD COLUMN pending_owner_id UUID;
UPDATE public.iam_email_identity
SET lifecycle_state=CASE WHEN verified_at IS NOT NULL THEN 'CURRENT' ELSE 'PENDING' END,
    pending_reason=CASE WHEN verified_at IS NULL THEN 'REGISTRATION' ELSE NULL END,
    active_lookup_hmac=lookup_hmac,
    current_owner_id=CASE WHEN verified_at IS NOT NULL THEN user_id ELSE NULL END,
    pending_owner_id=CASE WHEN verified_at IS NULL THEN user_id ELSE NULL END;
ALTER TABLE public.iam_email_identity DROP CONSTRAINT uk_iam_email_lookup;
ALTER TABLE public.iam_email_identity DROP CONSTRAINT uk_iam_email_identity_user;
ALTER TABLE public.iam_email_identity
 ADD CONSTRAINT uk_iam_email_active_lookup UNIQUE(active_lookup_hmac);
ALTER TABLE public.iam_email_identity
 ADD CONSTRAINT uk_iam_email_current_owner UNIQUE(current_owner_id);
ALTER TABLE public.iam_email_identity
 ADD CONSTRAINT uk_iam_email_pending_owner UNIQUE(pending_owner_id);
ALTER TABLE public.iam_email_identity
 ADD CONSTRAINT ck_iam_email_lifecycle CHECK(
  (lifecycle_state='CURRENT' AND current_owner_id=user_id AND pending_owner_id IS NULL
    AND pending_reason IS NULL AND active_lookup_hmac IS NOT NULL AND verified_at IS NOT NULL)
  OR (lifecycle_state='PENDING' AND pending_owner_id=user_id AND current_owner_id IS NULL
    AND pending_reason IN ('REGISTRATION','CHANGE_EMAIL')
    AND active_lookup_hmac IS NOT NULL AND verified_at IS NULL)
  OR (lifecycle_state='HISTORICAL' AND current_owner_id IS NULL
    AND pending_owner_id IS NULL AND active_lookup_hmac IS NULL
    AND pending_reason IS NULL)
 );
ALTER TABLE public.iam_email_verification_challenge DROP CONSTRAINT ck_iam_email_challenge_purpose;
ALTER TABLE public.iam_email_verification_challenge
 ADD CONSTRAINT ck_iam_email_challenge_purpose CHECK(purpose IN ('CHECKOUT','REGISTRATION','CHANGE_EMAIL'));
ALTER TABLE public.iam_email_verification_event DROP CONSTRAINT ck_iam_verification_event_purpose;
ALTER TABLE public.iam_email_verification_event
 ADD CONSTRAINT ck_iam_verification_event_purpose CHECK(purpose IN ('CHECKOUT','REGISTRATION','CHANGE_EMAIL'));
--rollback ALTER TABLE public.iam_email_verification_event DROP CONSTRAINT ck_iam_verification_event_purpose;
--rollback ALTER TABLE public.iam_email_verification_event ADD CONSTRAINT ck_iam_verification_event_purpose CHECK(purpose IN ('CHECKOUT','REGISTRATION'));
--rollback ALTER TABLE public.iam_email_verification_challenge DROP CONSTRAINT ck_iam_email_challenge_purpose;
--rollback ALTER TABLE public.iam_email_verification_challenge ADD CONSTRAINT ck_iam_email_challenge_purpose CHECK(purpose IN ('CHECKOUT','REGISTRATION'));
--rollback ALTER TABLE public.iam_email_identity DROP CONSTRAINT ck_iam_email_lifecycle;
--rollback ALTER TABLE public.iam_email_identity DROP CONSTRAINT uk_iam_email_pending_owner;
--rollback ALTER TABLE public.iam_email_identity DROP CONSTRAINT uk_iam_email_current_owner;
--rollback ALTER TABLE public.iam_email_identity DROP CONSTRAINT uk_iam_email_active_lookup;
--rollback ALTER TABLE public.iam_email_identity ADD CONSTRAINT uk_iam_email_identity_user UNIQUE(user_id);
--rollback ALTER TABLE public.iam_email_identity ADD CONSTRAINT uk_iam_email_lookup UNIQUE(lookup_hmac);
--rollback ALTER TABLE public.iam_email_identity DROP COLUMN pending_owner_id;
--rollback ALTER TABLE public.iam_email_identity DROP COLUMN current_owner_id;
--rollback ALTER TABLE public.iam_email_identity DROP COLUMN active_lookup_hmac;
--rollback ALTER TABLE public.iam_email_identity DROP COLUMN pending_reason;
--rollback ALTER TABLE public.iam_email_identity DROP COLUMN lifecycle_state;

--changeset rm:0047-iam-email-lifecycle-audit
CREATE TABLE public.iam_email_lifecycle_event (
 id UUID NOT NULL PRIMARY KEY,
 email_identity_id UUID NOT NULL,
 user_id UUID NOT NULL,
 action_code VARCHAR(28) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_iam_email_lifecycle_identity FOREIGN KEY(email_identity_id)
 REFERENCES public.iam_email_identity(id),
 CONSTRAINT fk_iam_email_lifecycle_user FOREIGN KEY(user_id)
 REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_iam_email_lifecycle_action CHECK(action_code IN
   ('CHANGE_REQUESTED','CHANGE_CANCELLED','CHANGE_CONFIRMED'))
);
CREATE INDEX idx_iam_email_lifecycle_user
 ON public.iam_email_lifecycle_event(user_id,occurred_at DESC);
--rollback DROP TABLE public.iam_email_lifecycle_event;

--changeset rm:0048-iam-email-lifecycle-audit-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_iam_email_lifecycle_event_immutable
 BEFORE UPDATE OR DELETE ON public.iam_email_lifecycle_event
 FOR EACH ROW EXECUTE FUNCTION public.iam_reject_email_verification_event_mutation();
--rollback DROP TRIGGER trg_iam_email_lifecycle_event_immutable ON public.iam_email_lifecycle_event;


--changeset rm:0049-iam-global-profile-envelopes
-- Global IAM identity profile is NOT encrypted with a tenant DEK. Application
-- enforces per-user, per-field AAD. Plaintext legacy columns remain only for
-- explicitly gated, offline backfill until destructive cutover qualification.
CREATE SCHEMA IF NOT EXISTS iam_pii;
CREATE TABLE iam_pii.user_profile_secret (
 user_id UUID PRIMARY KEY,
 display_name_envelope BYTEA NOT NULL,
 phone_envelope BYTEA,
 phone_lookup_hmac BYTEA,
 row_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_iam_profile_user FOREIGN KEY(user_id)
 REFERENCES public.iam_user_account(id),
 CONSTRAINT uk_iam_profile_phone_lookup UNIQUE(phone_lookup_hmac),
 CONSTRAINT ck_iam_profile_version CHECK(row_version>=0)
);
ALTER TABLE public.iam_user_account ALTER COLUMN display_name DROP NOT NULL;
--rollback ALTER TABLE public.iam_user_account ALTER COLUMN display_name SET NOT NULL;
--rollback DROP TABLE iam_pii.user_profile_secret;
--rollback DROP SCHEMA iam_pii;


--changeset rm:0050-iam-global-profile-audit
CREATE TABLE iam_pii.user_profile_event (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL,
 action_code VARCHAR(30) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_iam_profile_event_user FOREIGN KEY(user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_iam_profile_event_action CHECK(action_code IN
  ('PROFILE_CREATED','PROFILE_UPDATED','PROFILE_MIGRATED'))
);
CREATE INDEX idx_iam_profile_event_user ON iam_pii.user_profile_event(user_id,occurred_at DESC);
--rollback DROP TABLE iam_pii.user_profile_event;

--changeset rm:0051-iam-global-profile-audit-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_iam_profile_event_immutable
 BEFORE UPDATE OR DELETE ON iam_pii.user_profile_event
 FOR EACH ROW EXECUTE FUNCTION public.iam_reject_email_verification_event_mutation();
--rollback DROP TRIGGER trg_iam_profile_event_immutable ON iam_pii.user_profile_event;

--changeset rm:0052-crm-consent-idempotency
-- Legacy events remain unchanged; only keyed new submissions participate in idempotency.
ALTER TABLE crm.email_consent_event ADD COLUMN request_key VARCHAR(100);
CREATE UNIQUE INDEX uk_crm_consent_request_key
 ON crm.email_consent_event(tenant_id,request_key);
CREATE TABLE crm.consent_request_lock (
    bucket INTEGER NOT NULL PRIMARY KEY,
    CONSTRAINT ck_crm_consent_lock_bucket CHECK (bucket >= 0 AND bucket < 64)
);
INSERT INTO crm.consent_request_lock(bucket)
 SELECT ones.n + multiples.tens FROM (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
 ) ones CROSS JOIN (
    SELECT 0 AS tens UNION ALL SELECT 4 UNION ALL SELECT 8 UNION ALL SELECT 12
    UNION ALL SELECT 16 UNION ALL SELECT 20 UNION ALL SELECT 24 UNION ALL SELECT 28
    UNION ALL SELECT 32 UNION ALL SELECT 36 UNION ALL SELECT 40 UNION ALL SELECT 44
    UNION ALL SELECT 48 UNION ALL SELECT 52 UNION ALL SELECT 56 UNION ALL SELECT 60
 ) multiples;
--rollback DROP TABLE crm.consent_request_lock;
--rollback DROP INDEX uk_crm_consent_request_key;
--rollback ALTER TABLE crm.email_consent_event DROP COLUMN request_key;

--changeset rm:0053-crm-email-consent-immutable dbms:postgresql splitStatements:false
-- Consent grants and revocations are durable evidence: only append a new event.
CREATE FUNCTION crm.reject_email_consent_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'CRM consent history is immutable';
END;
$$;
CREATE TRIGGER trg_crm_consent_immutable
BEFORE UPDATE OR DELETE ON crm.email_consent_event
FOR EACH ROW EXECUTE FUNCTION crm.reject_email_consent_mutation();
--rollback DROP TRIGGER trg_crm_consent_immutable ON crm.email_consent_event;
--rollback DROP FUNCTION crm.reject_email_consent_mutation();

--changeset rm:0054-files-r10a-staged-metadata
-- Existing file IDs and historical rows are retained; no legacy AVAILABLE row
-- is assumed CLEAN or granted access by this additive migration.
ALTER TABLE crm.file_object ADD COLUMN uploaded_by UUID;
ALTER TABLE crm.file_object ADD COLUMN request_key VARCHAR(100);
ALTER TABLE crm.file_object ADD COLUMN scan_status VARCHAR(20) NOT NULL DEFAULT 'NOT_SCANNED';
ALTER TABLE crm.file_object ADD COLUMN content_class VARCHAR(32) NOT NULL DEFAULT 'INTERNAL';
ALTER TABLE crm.file_object ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm.file_object
 ADD CONSTRAINT ck_files_scan_status CHECK(scan_status IN ('NOT_SCANNED','SCANNING','CLEAN','INFECTED','ERROR'));
ALTER TABLE crm.file_object
 ADD CONSTRAINT ck_files_content_class CHECK(content_class IN ('INTERNAL','CONFIDENTIAL','BUSINESS_PUBLIC_CANDIDATE'));
ALTER TABLE crm.file_object
 ADD CONSTRAINT fk_file_uploaded_by FOREIGN KEY(uploaded_by) REFERENCES public.iam_user_account(id);
CREATE UNIQUE INDEX uk_files_tenant_request ON crm.file_object(tenant_id,request_key);
CREATE INDEX idx_file_tenant_uploader_state
 ON crm.file_object(tenant_id,uploaded_by,state,created_at DESC,id DESC);
--rollback DROP INDEX idx_file_tenant_uploader_state;
--rollback DROP INDEX uk_files_tenant_request;
--rollback ALTER TABLE crm.file_object DROP CONSTRAINT fk_file_uploaded_by;
--rollback ALTER TABLE crm.file_object DROP CONSTRAINT ck_files_content_class;
--rollback ALTER TABLE crm.file_object DROP CONSTRAINT ck_files_scan_status;
--rollback ALTER TABLE crm.file_object DROP COLUMN completed_at;
--rollback ALTER TABLE crm.file_object DROP COLUMN content_class;
--rollback ALTER TABLE crm.file_object DROP COLUMN scan_status;
--rollback ALTER TABLE crm.file_object DROP COLUMN request_key;
--rollback ALTER TABLE crm.file_object DROP COLUMN uploaded_by;

--changeset rm:0055-files-r10c-fenced-upload-claim
-- A single upload worker may hold the short-lived, token-fenced processing claim.
-- No client identity or filename is used as a claim credential.
ALTER TABLE crm.file_object ADD COLUMN processing_token UUID;
ALTER TABLE crm.file_object ADD COLUMN processing_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm.file_object ADD CONSTRAINT ck_files_processing_claim CHECK (
  (processing_token IS NULL AND processing_started_at IS NULL)
  OR (processing_token IS NOT NULL AND processing_started_at IS NOT NULL
      AND state='STAGED' AND scan_status='SCANNING')
);
CREATE INDEX idx_files_pending_processing ON crm.file_object
  (state,scan_status,processing_started_at);
--rollback DROP INDEX idx_files_pending_processing;
--rollback ALTER TABLE crm.file_object DROP CONSTRAINT ck_files_processing_claim;
--rollback ALTER TABLE crm.file_object DROP COLUMN processing_started_at;
--rollback ALTER TABLE crm.file_object DROP COLUMN processing_token;

--changeset rm:0056-files-r11-crm-attachment-links
-- Additive: existing attachment IDs preserved; legacy links keep detached_at=NULL.
ALTER TABLE crm.file_attachment ADD COLUMN created_by UUID;
ALTER TABLE crm.file_attachment ADD COLUMN detached_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm.file_attachment ADD COLUMN detached_by UUID;
ALTER TABLE crm.file_attachment ADD CONSTRAINT fk_file_attachment_created_by
  FOREIGN KEY(created_by) REFERENCES public.iam_user_account(id);
ALTER TABLE crm.file_attachment ADD CONSTRAINT fk_file_attachment_detached_by
  FOREIGN KEY(detached_by) REFERENCES public.iam_user_account(id);
ALTER TABLE crm.file_attachment ADD CONSTRAINT ck_file_attachment_detachment
  CHECK ((detached_at IS NULL AND detached_by IS NULL)
         OR (detached_at IS NOT NULL AND detached_by IS NOT NULL));
CREATE INDEX idx_file_attachment_active_owner
  ON crm.file_attachment(tenant_id,owner_type,owner_id,detached_at,created_at,id);
--rollback DROP INDEX idx_file_attachment_active_owner;
--rollback ALTER TABLE crm.file_attachment DROP CONSTRAINT ck_file_attachment_detachment;
--rollback ALTER TABLE crm.file_attachment DROP CONSTRAINT fk_file_attachment_detached_by;
--rollback ALTER TABLE crm.file_attachment DROP CONSTRAINT fk_file_attachment_created_by;
--rollback ALTER TABLE crm.file_attachment DROP COLUMN detached_by;
--rollback ALTER TABLE crm.file_attachment DROP COLUMN detached_at;
--rollback ALTER TABLE crm.file_attachment DROP COLUMN created_by;

--changeset rm:0057-files-r11-unique-active-attachments dbms:postgresql
-- A detached link cannot become active again; retries return its existing active ID.
CREATE UNIQUE INDEX uk_file_active_owner_purpose
 ON crm.file_attachment(tenant_id,file_id,owner_type,owner_id,purpose)
 WHERE detached_at IS NULL;
--rollback DROP INDEX uk_file_active_owner_purpose;

--changeset rm:0058-files-r11-encrypted-storage-format
-- Legacy plaintext records remain version zero and cannot enter secure read.
ALTER TABLE crm.file_storage ADD COLUMN encrypted_format_version SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE crm.file_storage ADD CONSTRAINT ck_file_storage_crypto_version
  CHECK (encrypted_format_version IN (0,1));
--rollback ALTER TABLE crm.file_storage DROP CONSTRAINT ck_file_storage_crypto_version;
--rollback ALTER TABLE crm.file_storage DROP COLUMN encrypted_format_version;


--changeset rm:0059-crm-categories-contracts
-- Tenant-first keys and same-tenant composite FKs are authoritative.
-- A customer has zero or one primary category in R1.2.
CREATE TABLE crm.customer_category (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 code VARCHAR(40) NOT NULL,
 name VARCHAR(120) NOT NULL,
 status VARCHAR(20) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_crm_customer_category PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_crm_category_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT uk_crm_category_code UNIQUE(tenant_id,code),
 CONSTRAINT ck_crm_category_status CHECK(status IN ('ACTIVE','ARCHIVED')),
 CONSTRAINT ck_crm_category_name CHECK(length(trim(name)) > 0)
);
CREATE INDEX idx_crm_category_tenant_status
 ON crm.customer_category(tenant_id,status,code,id);
ALTER TABLE crm.customer ADD COLUMN primary_category_id UUID;
ALTER TABLE crm.customer ADD CONSTRAINT fk_crm_customer_primary_category
 FOREIGN KEY(tenant_id,primary_category_id)
 REFERENCES crm.customer_category(tenant_id,id);
CREATE INDEX idx_crm_customer_tenant_category
 ON crm.customer(tenant_id,primary_category_id,id);

CREATE TABLE crm.customer_contract (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 customer_id UUID NOT NULL,
 contract_code VARCHAR(80) NOT NULL,
 starts_on DATE NOT NULL,
 ends_on DATE,
 status VARCHAR(20) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_crm_customer_contract PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_crm_contract_customer FOREIGN KEY(tenant_id,customer_id)
   REFERENCES crm.customer(tenant_id,id),
 CONSTRAINT uk_crm_contract_code UNIQUE(tenant_id,contract_code),
 CONSTRAINT ck_crm_contract_dates CHECK(ends_on IS NULL OR starts_on <= ends_on),
 CONSTRAINT ck_crm_contract_status CHECK(status IN ('DRAFT','ACTIVE','CLOSED','CANCELLED'))
);
CREATE INDEX idx_crm_contract_tenant_customer
 ON crm.customer_contract(tenant_id,customer_id,created_at DESC,id);
CREATE INDEX idx_crm_contract_tenant_expiry
 ON crm.customer_contract(tenant_id,status,ends_on,id);
--rollback DROP TABLE crm.customer_contract;
--rollback DROP INDEX idx_crm_customer_tenant_category;
--rollback ALTER TABLE crm.customer DROP CONSTRAINT fk_crm_customer_primary_category;
--rollback ALTER TABLE crm.customer DROP COLUMN primary_category_id;
--rollback DROP TABLE crm.customer_category;


--changeset rm:0060-crm-r13-audit-revisions
-- Immutable minimal business audit and original contract terms. No PII or free text.
CREATE TABLE crm.customer_change_event (
 tenant_id UUID NOT NULL, id UUID NOT NULL, customer_id UUID NOT NULL,
 actor_id UUID NOT NULL, action_code VARCHAR(32) NOT NULL,
 previous_version BIGINT, new_version BIGINT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_crm_customer_change_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_crm_customer_change_customer FOREIGN KEY(tenant_id,customer_id)
   REFERENCES crm.customer(tenant_id,id),
 CONSTRAINT fk_crm_customer_change_actor FOREIGN KEY(actor_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_crm_customer_change_action CHECK(action_code IN ('METADATA_UPDATED','NAME_UPDATED','CATEGORY_CHANGED','ARCHIVED'))
);
CREATE INDEX idx_crm_customer_changes ON crm.customer_change_event(tenant_id,customer_id,occurred_at,id);
CREATE TABLE crm.contract_revision (
 tenant_id UUID NOT NULL, id UUID NOT NULL, contract_id UUID NOT NULL,
 revision_no BIGINT NOT NULL, actor_id UUID NOT NULL,
 action_code VARCHAR(32) NOT NULL, starts_on DATE NOT NULL, ends_on DATE,
 status VARCHAR(20) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_crm_contract_revision PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_crm_contract_revision UNIQUE(tenant_id,contract_id,revision_no),
 CONSTRAINT fk_crm_revision_contract FOREIGN KEY(tenant_id,contract_id)
   REFERENCES crm.customer_contract(tenant_id,id),
 CONSTRAINT fk_crm_revision_actor FOREIGN KEY(actor_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_crm_revision_period CHECK(ends_on IS NULL OR ends_on>=starts_on),
 CONSTRAINT ck_crm_revision_status CHECK(status IN ('DRAFT','ACTIVE','CLOSED','CANCELLED')),
 CONSTRAINT ck_crm_revision_action CHECK(action_code IN ('CREATED','BASELINE_CAPTURED','DATES_CHANGED','STATUS_CHANGED','RENEWED'))
);
CREATE INDEX idx_crm_contract_revision_tenant ON crm.contract_revision(tenant_id,contract_id,revision_no);
--rollback DROP TABLE crm.contract_revision;
--rollback DROP TABLE crm.customer_change_event;

--changeset rm:0061-crm-r13-immutable-events dbms:postgresql splitStatements:false
CREATE FUNCTION crm.reject_r13_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'CRM R1.3 history is immutable';
END;
$$;
CREATE TRIGGER trg_customer_change_immutable BEFORE UPDATE OR DELETE ON crm.customer_change_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
CREATE TRIGGER trg_contract_revision_immutable BEFORE UPDATE OR DELETE ON crm.contract_revision
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_contract_revision_immutable ON crm.contract_revision;
--rollback DROP TRIGGER trg_customer_change_immutable ON crm.customer_change_event;
--rollback DROP FUNCTION crm.reject_r13_history_mutation();


--changeset rm:0062-crm-r14-contact-history
-- Tenant-bound append-only contact lifecycle metadata; never store plaintext PII.
CREATE TABLE crm.contact_change_event (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 contact_id UUID NOT NULL,
 customer_id UUID NOT NULL,
 actor_id UUID NOT NULL,
 action_code VARCHAR(32) NOT NULL,
 previous_version BIGINT,
 new_version BIGINT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_crm_contact_change PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_crm_contact_change_contact FOREIGN KEY(tenant_id,contact_id)
   REFERENCES crm.contact(tenant_id,id),
 CONSTRAINT fk_crm_contact_change_customer FOREIGN KEY(tenant_id,customer_id)
   REFERENCES crm.customer(tenant_id,id),
 CONSTRAINT fk_crm_contact_change_actor FOREIGN KEY(actor_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_crm_contact_change_action CHECK(action_code IN ('UPDATED','ARCHIVED','RESTORED'))
);
CREATE INDEX idx_crm_contact_change_history
 ON crm.contact_change_event(tenant_id,contact_id,occurred_at DESC,id);
--rollback DROP TABLE crm.contact_change_event;

--changeset rm:0063-crm-r14-contact-history-immutable dbms:postgresql
CREATE TRIGGER trg_contact_change_immutable BEFORE UPDATE OR DELETE ON crm.contact_change_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_contact_change_immutable ON crm.contact_change_event;

--changeset rm:0064-crm-r14-customer-restore-audit
ALTER TABLE crm.customer_change_event DROP CONSTRAINT ck_crm_customer_change_action;
ALTER TABLE crm.customer_change_event ADD CONSTRAINT ck_crm_customer_change_action
 CHECK(action_code IN ('METADATA_UPDATED','NAME_UPDATED','CATEGORY_CHANGED','ARCHIVED','RESTORED'));
--rollback ALTER TABLE crm.customer_change_event DROP CONSTRAINT ck_crm_customer_change_action;
--rollback ALTER TABLE crm.customer_change_event ADD CONSTRAINT ck_crm_customer_change_action CHECK(action_code IN ('METADATA_UPDATED','NAME_UPDATED','CATEGORY_CHANGED','ARCHIVED'));

--changeset rm:0065-crm-r14-contact-create-history
ALTER TABLE crm.contact_change_event DROP CONSTRAINT ck_crm_contact_change_action;
ALTER TABLE crm.contact_change_event ADD CONSTRAINT ck_crm_contact_change_action
 CHECK(action_code IN ('CREATED','UPDATED','ARCHIVED','RESTORED'));
--rollback ALTER TABLE crm.contact_change_event DROP CONSTRAINT ck_crm_contact_change_action;
--rollback ALTER TABLE crm.contact_change_event ADD CONSTRAINT ck_crm_contact_change_action CHECK(action_code IN ('UPDATED','ARCHIVED','RESTORED'));


--changeset rm:0066-crm-interactions
-- Tenant-owned, no free text/PII: all links are tenant-composite.
-- A contact cannot be attached to an interaction with another customer.
ALTER TABLE crm.contact ADD CONSTRAINT uk_crm_contact_owner_triplet
 UNIQUE(tenant_id,id,customer_id);
CREATE TABLE crm.customer_interaction (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 customer_id UUID NOT NULL,
 contact_id UUID,
 kind VARCHAR(20) NOT NULL,
 status VARCHAR(20) NOT NULL,
 responsible_user_id UUID,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 next_action_at TIMESTAMP WITH TIME ZONE,
 request_key VARCHAR(100) NOT NULL,
 request_sha256 BYTEA NOT NULL,
 created_by UUID NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_crm_customer_interaction PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_crm_interaction_request UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_crm_interaction_customer FOREIGN KEY(tenant_id,customer_id)
   REFERENCES crm.customer(tenant_id,id),
 CONSTRAINT fk_crm_interaction_contact_owner FOREIGN KEY(tenant_id,contact_id,customer_id)
   REFERENCES crm.contact(tenant_id,id,customer_id),
 CONSTRAINT fk_crm_interaction_responsible FOREIGN KEY(responsible_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_crm_interaction_creator FOREIGN KEY(created_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_crm_interaction_kind CHECK(kind IN ('CALL','MEETING','EMAIL','NOTE')),
 CONSTRAINT ck_crm_interaction_status CHECK(status IN ('OPEN','COMPLETED','CANCELLED')),
 CONSTRAINT ck_crm_interaction_version CHECK(row_version>=0)
);
CREATE INDEX idx_crm_interaction_customer
 ON crm.customer_interaction(tenant_id,customer_id,occurred_at DESC,id);
CREATE INDEX idx_crm_interaction_assignee
 ON crm.customer_interaction(tenant_id,responsible_user_id,status,occurred_at DESC,id);
CREATE TABLE crm.customer_interaction_event (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 interaction_id UUID NOT NULL,
 actor_id UUID NOT NULL,
 action_code VARCHAR(32) NOT NULL,
 previous_version BIGINT,
 new_version BIGINT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_crm_interaction_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_crm_interaction_event_owner FOREIGN KEY(tenant_id,interaction_id)
   REFERENCES crm.customer_interaction(tenant_id,id),
 CONSTRAINT fk_crm_interaction_event_actor FOREIGN KEY(actor_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT uk_crm_interaction_event_revision UNIQUE(tenant_id,interaction_id,new_version),
 CONSTRAINT ck_crm_interaction_event_action CHECK(action_code IN
   ('CREATED','RESPONSIBLE_ASSIGNED','NEXT_ACTION_SET','COMPLETED','CANCELLED'))
);
CREATE INDEX idx_crm_interaction_event_history
 ON crm.customer_interaction_event(tenant_id,interaction_id,occurred_at DESC,id);
CREATE TABLE crm.interaction_request_lock (bucket INTEGER PRIMARY KEY);
INSERT INTO crm.interaction_request_lock(bucket) VALUES
 (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15),
 (16),(17),(18),(19),(20),(21),(22),(23),(24),(25),(26),(27),(28),(29),(30),(31);
--rollback DROP TABLE crm.interaction_request_lock;
--rollback DROP TABLE crm.customer_interaction_event;
--rollback DROP TABLE crm.customer_interaction;
--rollback ALTER TABLE crm.contact DROP CONSTRAINT uk_crm_contact_owner_triplet;

--changeset rm:0067-crm-interaction-history-immutable dbms:postgresql
CREATE TRIGGER trg_crm_interaction_history_immutable
 BEFORE UPDATE OR DELETE ON crm.customer_interaction_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_crm_interaction_history_immutable ON crm.customer_interaction_event;

--changeset rm:0068-catalog-r1
-- Existing SKU, translations and prices are the only catalog source of truth.
-- Model code is optional for imported legacy/draft items, distinct from SKU.
ALTER TABLE crm.cat_item ADD COLUMN model_code VARCHAR(80);
CREATE INDEX idx_cat_item_tenant_model ON crm.cat_item(tenant_id,kind,model_code,id);
CREATE TABLE crm.cat_item_event (
 tenant_id UUID NOT NULL, id UUID NOT NULL, item_id UUID NOT NULL,
 actor_id UUID NOT NULL, action_code VARCHAR(32) NOT NULL,
 previous_version BIGINT, new_version BIGINT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_cat_item_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_cat_item_event_item FOREIGN KEY(tenant_id,item_id)
   REFERENCES crm.cat_item(tenant_id,id),
 CONSTRAINT fk_cat_item_event_actor FOREIGN KEY(actor_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT uk_cat_item_event_revision UNIQUE(tenant_id,item_id,new_version),
 CONSTRAINT ck_cat_item_event_action CHECK(action_code IN
  ('CREATED','MODEL_UPDATED','TRANSLATION_UPDATED','PRICE_REVISED','PUBLISHED','ARCHIVED'))
);
CREATE INDEX idx_cat_item_event_history ON crm.cat_item_event(tenant_id,item_id,occurred_at DESC,id);
--rollback DROP TABLE crm.cat_item_event;
--rollback DROP INDEX idx_cat_item_tenant_model;
--rollback ALTER TABLE crm.cat_item DROP COLUMN model_code;

--changeset rm:0069-catalog-r1-immutable-evidence dbms:postgresql
CREATE TRIGGER trg_catalog_item_event_immutable
 BEFORE UPDATE OR DELETE ON crm.cat_item_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
CREATE TRIGGER trg_catalog_price_immutable
 BEFORE UPDATE OR DELETE ON crm.cat_price
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_catalog_price_immutable ON crm.cat_price;
--rollback DROP TRIGGER trg_catalog_item_event_immutable ON crm.cat_item_event;

--changeset rm:0070-inventory-provenance
-- Legacy evidence cannot truthfully acquire synthetic actor attribution.
-- All new verified staff actions write actor; prior historical rows remain NULL.
ALTER TABLE crm.inv_stock_movement ADD COLUMN actor_user_id UUID;
ALTER TABLE crm.inv_stock_movement ADD COLUMN source_type VARCHAR(24);
ALTER TABLE crm.inv_stock_movement ADD COLUMN source_id UUID;
ALTER TABLE crm.inv_stock_movement ADD COLUMN business_reason_code VARCHAR(32);
ALTER TABLE crm.inv_stock_movement ADD CONSTRAINT fk_inv_movement_actor
 FOREIGN KEY(actor_user_id) REFERENCES public.iam_user_account(id);
ALTER TABLE crm.inv_stock_movement ADD CONSTRAINT ck_inv_movement_source
 CHECK(source_type IS NULL OR source_type IN ('STAFF','SALES_ORDER','TRANSFER','STOCKTAKE'));
ALTER TABLE crm.inv_stock_movement ADD CONSTRAINT ck_inv_movement_reason_detail
 CHECK(business_reason_code IS NULL OR business_reason_code IN
 ('DAMAGE','LOSS','EXPIRED','OTHER'));
ALTER TABLE crm.inv_reservation_event ADD COLUMN actor_user_id UUID;
ALTER TABLE crm.inv_reservation_event ADD CONSTRAINT fk_inv_reservation_event_actor
 FOREIGN KEY(actor_user_id) REFERENCES public.iam_user_account(id);
CREATE INDEX idx_inv_movement_tenant_item_history
 ON crm.inv_stock_movement(tenant_id,item_id,recorded_at DESC,id DESC);
--rollback DROP INDEX idx_inv_movement_tenant_item_history;
--rollback ALTER TABLE crm.inv_reservation_event DROP CONSTRAINT fk_inv_reservation_event_actor;
--rollback ALTER TABLE crm.inv_reservation_event DROP COLUMN actor_user_id;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT ck_inv_movement_reason_detail;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT ck_inv_movement_source;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT fk_inv_movement_actor;
--rollback ALTER TABLE crm.inv_stock_movement DROP COLUMN business_reason_code;
--rollback ALTER TABLE crm.inv_stock_movement DROP COLUMN source_id;
--rollback ALTER TABLE crm.inv_stock_movement DROP COLUMN source_type;
--rollback ALTER TABLE crm.inv_stock_movement DROP COLUMN actor_user_id;


--changeset rm:0071-inventory-transfer
CREATE TABLE crm.inv_stock_transfer (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 source_location_id UUID NOT NULL,
 target_location_id UUID NOT NULL,
 item_id UUID NOT NULL,
 quantity NUMERIC(19,4) NOT NULL,
 posted_by UUID NOT NULL,
 posted_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_inv_stock_transfer PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_inv_stock_transfer_request UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_inv_transfer_source FOREIGN KEY(tenant_id,source_location_id)
   REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_inv_transfer_target FOREIGN KEY(tenant_id,target_location_id)
   REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_inv_transfer_item FOREIGN KEY(tenant_id,item_id)
   REFERENCES crm.cat_item(tenant_id,id),
 CONSTRAINT fk_inv_transfer_actor FOREIGN KEY(posted_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_inv_transfer_two_locations CHECK(source_location_id<>target_location_id),
 CONSTRAINT ck_inv_transfer_qty CHECK(quantity>0)
);
CREATE INDEX idx_inv_transfer_source_time
 ON crm.inv_stock_transfer(tenant_id,source_location_id,posted_at DESC,id);
--rollback DROP TABLE crm.inv_stock_transfer;

--changeset rm:0072-inventory-transfer-immutable dbms:postgresql
CREATE TRIGGER trg_inv_transfer_immutable
 BEFORE UPDATE OR DELETE ON crm.inv_stock_transfer
 FOR EACH ROW EXECUTE FUNCTION crm.inv_reject_movement_mutation();
--rollback DROP TRIGGER trg_inv_transfer_immutable ON crm.inv_stock_transfer;


--changeset rm:0073-inventory-stocktake
-- A counted snapshot, not a second balance ledger. 100 SKU per bounded count.
CREATE TABLE crm.inv_stocktake (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 location_id UUID NOT NULL,
 start_request_key VARCHAR(100) NOT NULL,
 item_set_sha256 VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL,
 started_by UUID NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,
 posted_by UUID,
 posted_at TIMESTAMP WITH TIME ZONE,
 post_request_key VARCHAR(100),
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_inv_stocktake PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_inv_stocktake_start UNIQUE(tenant_id,start_request_key),
 CONSTRAINT uk_inv_stocktake_post UNIQUE(tenant_id,post_request_key),
 CONSTRAINT fk_inv_stocktake_location FOREIGN KEY(tenant_id,location_id)
    REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_inv_stocktake_starter FOREIGN KEY(started_by)
    REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_inv_stocktake_poster FOREIGN KEY(posted_by)
    REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_inv_stocktake_state CHECK(status IN ('OPEN','POSTED','CANCELLED')),
 CONSTRAINT ck_inv_stocktake_version CHECK(row_version>=0),
 CONSTRAINT ck_inv_stocktake_post CHECK(
    (status='POSTED' AND posted_by IS NOT NULL AND posted_at IS NOT NULL
                      AND post_request_key IS NOT NULL)
    OR (status<>'POSTED' AND posted_by IS NULL AND posted_at IS NULL
                           AND post_request_key IS NULL))
);
CREATE TABLE crm.inv_stocktake_line (
 tenant_id UUID NOT NULL,
 stocktake_id UUID NOT NULL,
 item_id UUID NOT NULL,
 book_on_hand NUMERIC(19,4) NOT NULL,
 book_reserved NUMERIC(19,4) NOT NULL,
 expected_stock_version BIGINT NOT NULL,
 counted_on_hand NUMERIC(19,4),
 counted_by UUID,
 counted_at TIMESTAMP WITH TIME ZONE,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_inv_stocktake_line PRIMARY KEY(tenant_id,stocktake_id,item_id),
 CONSTRAINT fk_inv_stocktake_line_header FOREIGN KEY(tenant_id,stocktake_id)
    REFERENCES crm.inv_stocktake(tenant_id,id),
 CONSTRAINT fk_inv_stocktake_line_item FOREIGN KEY(tenant_id,item_id)
    REFERENCES crm.cat_item(tenant_id,id),
 CONSTRAINT fk_inv_stocktake_line_actor FOREIGN KEY(counted_by)
    REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_inv_stocktake_line_book CHECK(book_on_hand>=book_reserved AND book_reserved>=0),
 CONSTRAINT ck_inv_stocktake_line_count CHECK(counted_on_hand IS NULL OR counted_on_hand>=0),
 CONSTRAINT ck_inv_stocktake_line_version CHECK(expected_stock_version>=0 AND row_version>=0)
);
CREATE INDEX idx_inv_stocktake_location
 ON crm.inv_stocktake(tenant_id,location_id,status,started_at DESC);
--rollback DROP TABLE crm.inv_stocktake_line;
--rollback DROP TABLE crm.inv_stocktake;

--changeset rm:0074-inventory-stocktake-terminal-immutability dbms:postgresql splitStatements:false
CREATE FUNCTION crm.inv_reject_terminal_stocktake_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE current_status VARCHAR(16);
BEGIN
 IF TG_TABLE_NAME='inv_stocktake' THEN
   IF TG_OP='DELETE' OR OLD.status IN ('POSTED','CANCELLED') THEN
      RAISE EXCEPTION 'Terminal stocktake cannot be changed or removed';
   END IF;
   RETURN NEW;
 END IF;
 IF TG_OP='INSERT' THEN
    SELECT status INTO current_status FROM crm.inv_stocktake
     WHERE tenant_id=NEW.tenant_id AND id=NEW.stocktake_id;
 ELSE
    SELECT status INTO current_status FROM crm.inv_stocktake
     WHERE tenant_id=OLD.tenant_id AND id=OLD.stocktake_id;
 END IF;
 IF current_status<>'OPEN' OR current_status IS NULL THEN
    RAISE EXCEPTION 'Stocktake lines immutable after terminal state';
 END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER trg_inv_stocktake_immutable
 BEFORE UPDATE OR DELETE ON crm.inv_stocktake
 FOR EACH ROW EXECUTE FUNCTION crm.inv_reject_terminal_stocktake_mutation();
CREATE TRIGGER trg_inv_stocktake_line_immutable
 BEFORE INSERT OR UPDATE OR DELETE ON crm.inv_stocktake_line
 FOR EACH ROW EXECUTE FUNCTION crm.inv_reject_terminal_stocktake_mutation();
--rollback DROP TRIGGER trg_inv_stocktake_line_immutable ON crm.inv_stocktake_line;
--rollback DROP TRIGGER trg_inv_stocktake_immutable ON crm.inv_stocktake;
--rollback DROP FUNCTION crm.inv_reject_terminal_stocktake_mutation();


--changeset rm:0075-stocktake-cancellation-evidence
-- Cancellation is terminal and attributable, not a silent status flip.
ALTER TABLE crm.inv_stocktake ADD COLUMN cancelled_by UUID;
ALTER TABLE crm.inv_stocktake ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm.inv_stocktake ADD CONSTRAINT fk_inv_stocktake_canceller
 FOREIGN KEY(cancelled_by) REFERENCES public.iam_user_account(id);
ALTER TABLE crm.inv_stocktake ADD CONSTRAINT ck_inv_stocktake_cancellation
 CHECK((status='CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)
    OR (status<>'CANCELLED' AND cancelled_by IS NULL AND cancelled_at IS NULL));
--rollback ALTER TABLE crm.inv_stocktake DROP CONSTRAINT ck_inv_stocktake_cancellation;
--rollback ALTER TABLE crm.inv_stocktake DROP CONSTRAINT fk_inv_stocktake_canceller;
--rollback ALTER TABLE crm.inv_stocktake DROP COLUMN cancelled_at;
--rollback ALTER TABLE crm.inv_stocktake DROP COLUMN cancelled_by;

--changeset rm:0076-sales-line-catalog-snapshot
-- Historical NULL remains intentionally unknown; never reconstruct SKU from a mutable offer.
ALTER TABLE crm.sales_order_line ADD COLUMN catalog_item_id_snapshot UUID;
ALTER TABLE crm.sales_order_line ADD CONSTRAINT fk_sales_line_catalog_snapshot
 FOREIGN KEY (tenant_id,catalog_item_id_snapshot)
 REFERENCES crm.cat_item(tenant_id,id);
CREATE INDEX idx_sales_line_catalog_snapshot
 ON crm.sales_order_line(tenant_id,catalog_item_id_snapshot);
--rollback DROP INDEX idx_sales_line_catalog_snapshot;
--rollback ALTER TABLE crm.sales_order_line DROP CONSTRAINT fk_sales_line_catalog_snapshot;
--rollback ALTER TABLE crm.sales_order_line DROP COLUMN catalog_item_id_snapshot;

--changeset rm:0077-sales-line-immutable-acceptance dbms:postgresql splitStatements:false
-- The entire accepted line (price, identity, quantity and presentation) is immutable.
CREATE TRIGGER trg_sales_line_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_order_line
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_sales_line_immutable ON crm.sales_order_line;

 
--changeset rm:0078-sales-offer-cas-lifecycle
-- Backward compatible: existing PUBLISHED/HIDDEN offers retain their state.
ALTER TABLE crm.store_offer ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE crm.store_offer DROP CONSTRAINT ck_store_offer_state;
ALTER TABLE crm.store_offer ADD CONSTRAINT ck_store_offer_state
 CHECK (state IN ('DRAFT','PUBLISHED','PAUSED','HIDDEN'));
ALTER TABLE crm.store_offer ADD CONSTRAINT ck_store_offer_version
 CHECK (row_version>=0);
--rollback ALTER TABLE crm.store_offer DROP CONSTRAINT ck_store_offer_version;
--rollback ALTER TABLE crm.store_offer DROP COLUMN row_version;
--rollback ALTER TABLE crm.store_offer DROP CONSTRAINT ck_store_offer_state;
--rollback ALTER TABLE crm.store_offer ADD CONSTRAINT ck_store_offer_state CHECK (state IN ('PUBLISHED','HIDDEN'));
-- Downgrade intentionally fails closed while DRAFT/PAUSED rows exist.


--changeset rm:0079-sales-offer-sku-stability dbms:postgresql splitStatements:false
-- A published offer identity may not be reassigned to another SKU or tenant.
-- Historical NULL offers may acquire their first verified SKU binding, but not silently remap.
CREATE FUNCTION crm.sales_reject_offer_identity_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.id IS DISTINCT FROM OLD.id THEN
    RAISE EXCEPTION 'Offer identity is immutable';
 END IF;
 IF OLD.catalog_item_id IS NOT NULL
    AND NEW.catalog_item_id IS DISTINCT FROM OLD.catalog_item_id THEN
    RAISE EXCEPTION 'Offer catalog mapping is immutable';
 END IF;
 IF OLD.catalog_item_id IS NULL AND OLD.state='PUBLISHED'
    AND NEW.catalog_item_id IS DISTINCT FROM OLD.catalog_item_id THEN
    RAISE EXCEPTION 'Published offer cannot be backfilled without new offer identity';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_sales_offer_identity_immutable
 BEFORE UPDATE ON crm.store_offer FOR EACH ROW
 EXECUTE FUNCTION crm.sales_reject_offer_identity_mutation();
--rollback DROP TRIGGER trg_sales_offer_identity_immutable ON crm.store_offer;
--rollback DROP FUNCTION crm.sales_reject_offer_identity_mutation();


--changeset rm:0080-inventory-reconciliation-run
-- Diagnostic evidence only; never mutate stock, reservations or immutable ledger.
CREATE TABLE crm.inv_reconciliation_run (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 status VARCHAR(16) NOT NULL,
 started_by UUID NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 completed_at TIMESTAMP WITH TIME ZONE,
 cursor_location_id UUID,
 cursor_item_id UUID,
 inspected_positions BIGINT NOT NULL DEFAULT 0,
 finding_count BIGINT NOT NULL DEFAULT 0,
 failure_code VARCHAR(64),
 CONSTRAINT pk_inv_reconciliation_run PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_inv_reconciliation_tenant FOREIGN KEY(tenant_id)
   REFERENCES public.iam_workspace(id),
 CONSTRAINT fk_inv_reconciliation_actor FOREIGN KEY(started_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_inv_reconciliation_status CHECK(status IN ('RUNNING','COMPLETED','FAILED')),
 CONSTRAINT ck_inv_reconciliation_cursor CHECK(
   (cursor_location_id IS NULL AND cursor_item_id IS NULL)
   OR (cursor_location_id IS NOT NULL AND cursor_item_id IS NOT NULL)),
 CONSTRAINT ck_inv_reconciliation_counters CHECK(inspected_positions>=0 AND finding_count>=0),
 CONSTRAINT ck_inv_reconciliation_terminal CHECK(
   (status='RUNNING' AND completed_at IS NULL AND failure_code IS NULL)
   OR (status='COMPLETED' AND completed_at IS NOT NULL AND failure_code IS NULL)
   OR (status='FAILED' AND completed_at IS NOT NULL AND failure_code IS NOT NULL))
);
CREATE TABLE crm.inv_reconciliation_finding (
 tenant_id UUID NOT NULL,
 run_id UUID NOT NULL,
 location_id UUID NOT NULL,
 item_id UUID NOT NULL,
 finding_code VARCHAR(48) NOT NULL,
 on_hand NUMERIC(19,4),
 reserved NUMERIC(19,4),
 active_reservations NUMERIC(19,4) NOT NULL,
 ledger_balance NUMERIC(19,4),
 inspected_movements BIGINT NOT NULL,
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_inv_reconciliation_finding PRIMARY KEY(tenant_id,run_id,location_id,item_id),
 CONSTRAINT fk_inv_reconciliation_finding_run FOREIGN KEY(tenant_id,run_id)
   REFERENCES crm.inv_reconciliation_run(tenant_id,id),
 CONSTRAINT ck_inv_reconciliation_finding_count CHECK(inspected_movements>=0)
);
CREATE INDEX idx_inv_reconciliation_recent
 ON crm.inv_reconciliation_run(tenant_id,started_at DESC,id);
--rollback DROP TABLE crm.inv_reconciliation_finding;
--rollback DROP TABLE crm.inv_reconciliation_run;


--changeset rm:0081-inventory-position-posting-order
-- New movements use the already-serialized stock balance row_version as a
-- per-position monotonic ordering token. NULL denotes unverifiable legacy
-- history; never infer it from timestamps or UUIDs.
ALTER TABLE crm.inv_stock_movement ADD COLUMN posting_version BIGINT;
ALTER TABLE crm.inv_stock_movement ADD CONSTRAINT ck_inv_movement_posting_version
  CHECK (posting_version IS NULL OR posting_version > 0);
ALTER TABLE crm.inv_stock_movement ADD CONSTRAINT uk_inv_movement_posting_version
  UNIQUE(tenant_id,location_id,item_id,posting_version);
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT uk_inv_movement_posting_version;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT ck_inv_movement_posting_version;
--rollback ALTER TABLE crm.inv_stock_movement DROP COLUMN posting_version;


--changeset rm:0082-store-offer-immutable-price-revisions
-- The source of truth remains crm.store_offer. Price revisions are append-only
-- committed evidence, NOT an alternative current-price projection.
CREATE TABLE crm.store_offer_price_revision (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 offer_id UUID NOT NULL,
 offer_row_version BIGINT NOT NULL,
 old_price NUMERIC(19,4),
 new_price NUMERIC(19,4) NOT NULL,
 old_currency CHAR(3),
 new_currency CHAR(3) NOT NULL,
 actor_user_id UUID NOT NULL,
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_store_offer_price_revision PRIMARY KEY (tenant_id,id),
 CONSTRAINT fk_offer_price_revision_offer FOREIGN KEY(tenant_id,offer_id)
   REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_offer_price_revision_actor FOREIGN KEY(actor_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT uk_offer_price_revision_version UNIQUE(tenant_id,offer_id,offer_row_version),
 CONSTRAINT ck_offer_price_revision_positive CHECK(new_price>0 AND
   (old_price IS NULL OR old_price>0)),
 CONSTRAINT ck_offer_price_revision_baseline CHECK(
   (old_price IS NULL AND old_currency IS NULL)
   OR (old_price IS NOT NULL AND old_currency IS NOT NULL))
);
CREATE INDEX idx_offer_price_revision_history
 ON crm.store_offer_price_revision(tenant_id,offer_id,offer_row_version DESC);
--rollback DROP TABLE crm.store_offer_price_revision;

--changeset rm:0083-store-offer-price-revision-immutability dbms:postgresql splitStatements:false
CREATE FUNCTION crm.reject_price_revision_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Offer price revisions are immutable';
END;
$$;
CREATE TRIGGER trg_offer_price_revision_immutable
 BEFORE UPDATE OR DELETE ON crm.store_offer_price_revision
 FOR EACH ROW EXECUTE FUNCTION crm.reject_price_revision_mutation();
--rollback DROP TRIGGER trg_offer_price_revision_immutable ON crm.store_offer_price_revision;
--rollback DROP FUNCTION crm.reject_price_revision_mutation();


--changeset rm:0084-product-offer-qr-stable-reference
-- QR-01: references are random NON-SECRET public identifiers. Reprint returns
-- the same literal public_ref. No QR token is a buyer or staff permission.
-- active_offer_id is populated only for ACTIVE rows: ordinary UNIQUE works
-- on both PostgreSQL and H2 without a PostgreSQL-only partial unique index.
CREATE TABLE crm.store_offer_qr_ref (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 offer_id UUID NOT NULL,
 active_offer_id UUID,
 public_ref VARCHAR(64) NOT NULL,
 generation INTEGER NOT NULL,
 state VARCHAR(16) NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revoked_at TIMESTAMP WITH TIME ZONE,
 issued_by UUID NOT NULL,
 CONSTRAINT pk_store_offer_qr_ref PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_offer_qr_offer FOREIGN KEY(tenant_id,offer_id)
   REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_offer_qr_actor FOREIGN KEY(issued_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT uq_offer_qr_public_ref UNIQUE(public_ref),
 CONSTRAINT uq_offer_qr_active_offer UNIQUE(tenant_id,active_offer_id),
 CONSTRAINT uq_offer_qr_generation UNIQUE(tenant_id,offer_id,generation),
 CONSTRAINT ck_offer_qr_generation CHECK(generation > 0),
 CONSTRAINT ck_offer_qr_version CHECK(row_version >= 0),
 CONSTRAINT ck_offer_qr_lifecycle CHECK(
   (state='ACTIVE' AND active_offer_id=offer_id AND revoked_at IS NULL)
   OR
   (state='REVOKED' AND active_offer_id IS NULL AND revoked_at IS NOT NULL))
);
CREATE INDEX idx_offer_qr_offer_history
 ON crm.store_offer_qr_ref(tenant_id,offer_id,generation DESC);
--rollback DROP TABLE crm.store_offer_qr_ref;


--changeset rm:0085-qr-command-receipts
-- QR-01B: immutable command effect, seller-wide request key namespace.
-- All QR mutations serialize on crm.store_front(tenant_id) FOR UPDATE.
-- Receipt replays preserve original outcome even when alias later changes state.
CREATE TABLE crm.store_offer_qr_receipt (
 tenant_id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 offer_id UUID NOT NULL,
 actor_id UUID NOT NULL,
 action_code VARCHAR(16) NOT NULL,
 request_sha256 CHAR(64) NOT NULL,
 qr_ref_id UUID NOT NULL,
 result_state VARCHAR(16) NOT NULL,
 result_row_version BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_store_offer_qr_receipt PRIMARY KEY (tenant_id,request_key),
 CONSTRAINT fk_offer_qr_receipt_offer FOREIGN KEY (tenant_id,offer_id)
    REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_offer_qr_receipt_ref FOREIGN KEY (tenant_id,qr_ref_id)
    REFERENCES crm.store_offer_qr_ref(tenant_id,id),
 CONSTRAINT fk_offer_qr_receipt_actor FOREIGN KEY (actor_id)
    REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_offer_qr_receipt_action CHECK (
    action_code IN ('ISSUE','REISSUE','REVOKE')),
 CONSTRAINT ck_offer_qr_receipt_result CHECK (
    result_state IN ('ACTIVE','REVOKED') AND result_row_version >= 0),
 CONSTRAINT ck_offer_qr_receipt_hash CHECK (LENGTH(request_sha256)=64)
);
CREATE INDEX idx_offer_qr_receipt_offer
 ON crm.store_offer_qr_receipt(tenant_id,offer_id,created_at);
--rollback DROP TABLE crm.store_offer_qr_receipt;

--changeset rm:0086-qr-command-receipt-immutability dbms:postgresql splitStatements:false
CREATE FUNCTION crm.reject_offer_qr_receipt_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'QR command receipts are immutable';
END;
$$;
CREATE TRIGGER trg_offer_qr_receipt_immutable
 BEFORE UPDATE OR DELETE ON crm.store_offer_qr_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.reject_offer_qr_receipt_mutation();
--rollback DROP TRIGGER trg_offer_qr_receipt_immutable ON crm.store_offer_qr_receipt;
--rollback DROP FUNCTION crm.reject_offer_qr_receipt_mutation();


--changeset rm:0087-files-catalog-public-media
-- QR-02A: only sanitizer-generated, CLEAN and seller-approved derivative is public.
-- Uploaded ORIGINAL bytes retain INTERNAL classification and are never public.
-- The approving code writes one immutable lineage row; revocation disables reads.
CREATE TABLE crm.file_catalog_public_variant (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 item_id UUID NOT NULL,
 original_file_id UUID NOT NULL,
 derivative_file_id UUID NOT NULL,
 attachment_id UUID NOT NULL,
 public_ref VARCHAR(64) NOT NULL,
 sort_order INTEGER NOT NULL,
 active_slot INTEGER,
 request_key VARCHAR(100) NOT NULL,
 state VARCHAR(16) NOT NULL,
 sanitizer_version VARCHAR(20) NOT NULL,
 width_pixels INTEGER NOT NULL,
 height_pixels INTEGER NOT NULL,
 approved_by UUID NOT NULL,
 approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revoked_by UUID,
 revoked_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT pk_catalog_public_variant PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_catalog_public_variant_item FOREIGN KEY(tenant_id,item_id)
   REFERENCES crm.cat_item(tenant_id,id),
 CONSTRAINT fk_catalog_public_variant_original FOREIGN KEY(tenant_id,original_file_id)
   REFERENCES crm.file_object(tenant_id,id),
 CONSTRAINT fk_catalog_public_variant_derivative FOREIGN KEY(tenant_id,derivative_file_id)
   REFERENCES crm.file_object(tenant_id,id),
 CONSTRAINT fk_catalog_public_variant_attachment FOREIGN KEY(tenant_id,attachment_id)
   REFERENCES crm.file_attachment(tenant_id,id),
 CONSTRAINT fk_catalog_public_variant_approver FOREIGN KEY(approved_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_catalog_public_variant_revoker FOREIGN KEY(revoked_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT uq_catalog_public_media_ref UNIQUE(public_ref),
 CONSTRAINT uq_catalog_public_request UNIQUE(tenant_id,request_key),
 CONSTRAINT uq_catalog_public_active_slot UNIQUE(tenant_id,item_id,active_slot),
 CONSTRAINT ck_catalog_public_order CHECK(sort_order BETWEEN 0 AND 19),
 CONSTRAINT ck_catalog_public_dimensions CHECK(
   width_pixels BETWEEN 1 AND 4096 AND height_pixels BETWEEN 1 AND 4096
   AND width_pixels * height_pixels <= 4000000),
 CONSTRAINT ck_catalog_public_sanitizer CHECK(sanitizer_version='PNG_R1'),
 CONSTRAINT ck_catalog_public_not_same_file CHECK(original_file_id<>derivative_file_id),
 CONSTRAINT ck_catalog_public_state CHECK(
    (state='ACTIVE' AND active_slot=sort_order
      AND revoked_by IS NULL AND revoked_at IS NULL)
    OR
    (state='REVOKED' AND active_slot IS NULL
      AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL))
);
CREATE INDEX idx_catalog_public_variant_item
 ON crm.file_catalog_public_variant(tenant_id,item_id,state,sort_order);
--rollback DROP TABLE crm.file_catalog_public_variant;


--changeset rm:0088-sales-order-qr-reference
-- QR-03B: a durable nonsecret order locator, created atomically with PLACED.
-- A stable public ref NEVER authorizes order detail, pickup, refund or payment.
-- ACTIVE uniqueness is portable to PostgreSQL and H2 using NULL active_order_id.
CREATE TABLE crm.sales_order_qr_reference (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 order_id UUID NOT NULL,
 active_order_id UUID,
 public_ref VARCHAR(64) NOT NULL,
 generation INTEGER NOT NULL,
 state VARCHAR(16) NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
 issued_by UUID,
 revoked_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT pk_sales_order_qr_reference PRIMARY KEY (tenant_id,id),
 CONSTRAINT fk_sales_order_qr_order FOREIGN KEY (tenant_id,order_id)
   REFERENCES crm.sales_order(tenant_id,id),
 CONSTRAINT fk_sales_order_qr_issuer FOREIGN KEY (issued_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT uq_sales_order_qr_public_ref UNIQUE (public_ref),
 CONSTRAINT uq_sales_order_qr_one_active UNIQUE (tenant_id,active_order_id),
 CONSTRAINT uq_sales_order_qr_generation UNIQUE (tenant_id,order_id,generation),
 CONSTRAINT ck_sales_order_qr_generation CHECK (generation > 0 AND row_version >= 0),
 CONSTRAINT ck_sales_order_qr_state CHECK (
   (state='ACTIVE' AND active_order_id=order_id AND revoked_at IS NULL)
   OR
   (state='REVOKED' AND active_order_id IS NULL AND revoked_at IS NOT NULL))
);
CREATE INDEX idx_sales_order_qr_history
 ON crm.sales_order_qr_reference(tenant_id,order_id,generation DESC);
--rollback DROP TABLE crm.sales_order_qr_reference;


--changeset rm:0089-sales-qr-price-proposals
-- QR-04A: private buyer/seller negotiation, no public price mutation.
-- UAT policy: maximum 3 COUNTER events; expiry is 48 hours from submit.
CREATE TABLE crm.sales_price_proposal (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 offer_id UUID NOT NULL,
 catalog_item_id UUID NOT NULL,
 buyer_user_id UUID NOT NULL,
 quantity INTEGER NOT NULL,
 list_price_at_submit NUMERIC(19,4) NOT NULL,
 current_unit_price NUMERIC(19,4) NOT NULL,
 currency CHAR(3) NOT NULL,
 state VARCHAR(24) NOT NULL,
 revision BIGINT NOT NULL,
 next_actor VARCHAR(12) NOT NULL,
 round_count INTEGER NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_sales_price_proposal PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_price_proposal_offer FOREIGN KEY(tenant_id,offer_id)
  REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_price_proposal_item FOREIGN KEY(tenant_id,catalog_item_id)
  REFERENCES crm.cat_item(tenant_id,id),
 CONSTRAINT fk_price_proposal_buyer FOREIGN KEY(buyer_user_id)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_price_proposal_qty CHECK(quantity BETWEEN 1 AND 1000),
 CONSTRAINT ck_price_proposal_amount CHECK(list_price_at_submit>=0 AND current_unit_price>0),
 CONSTRAINT ck_price_proposal_rev CHECK(revision>=1 AND row_version>=0 AND round_count BETWEEN 0 AND 3),
 CONSTRAINT ck_price_proposal_state CHECK(
  (state='AWAITING_SELLER' AND next_actor='SELLER') OR
  (state='AWAITING_BUYER' AND next_actor='BUYER') OR
  (state IN ('AGREED','REJECTED','WITHDRAWN','EXPIRED') AND next_actor='NONE')),
 CONSTRAINT ck_price_proposal_expiry CHECK(expires_at>created_at)
);
CREATE INDEX idx_price_proposal_buyer
 ON crm.sales_price_proposal(buyer_user_id,created_at,id);
CREATE INDEX idx_price_proposal_offer
 ON crm.sales_price_proposal(tenant_id,offer_id,state);

CREATE TABLE crm.sales_price_proposal_event (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 proposal_id UUID NOT NULL,
 revision BIGINT NOT NULL,
 event_type VARCHAR(16) NOT NULL,
 actor_role VARCHAR(12) NOT NULL,
 actor_user_id UUID,
 unit_price NUMERIC(19,4) NOT NULL,
 quantity INTEGER NOT NULL,
 currency CHAR(3) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_price_proposal_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_price_proposal_event FOREIGN KEY(tenant_id,proposal_id)
  REFERENCES crm.sales_price_proposal(tenant_id,id),
 CONSTRAINT fk_price_event_actor FOREIGN KEY(actor_user_id)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT uq_price_proposal_revision UNIQUE(tenant_id,proposal_id,revision),
 CONSTRAINT ck_price_proposal_event_kind CHECK(event_type IN
  ('SUBMITTED','COUNTERED','ACCEPTED','REJECTED','WITHDRAWN','EXPIRED')),
 CONSTRAINT ck_price_proposal_event_role CHECK(
  (actor_role IN ('BUYER','SELLER') AND actor_user_id IS NOT NULL) OR
  (actor_role='SYSTEM' AND actor_user_id IS NULL)),
 CONSTRAINT ck_price_proposal_event_amount CHECK(unit_price>0 AND quantity BETWEEN 1 AND 1000)
);

CREATE TABLE crm.sales_accepted_quote (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 proposal_id UUID NOT NULL,
 agreed_revision BIGINT NOT NULL,
 offer_id UUID NOT NULL,
 buyer_user_id UUID NOT NULL,
 quantity INTEGER NOT NULL,
 agreed_unit_price NUMERIC(19,4) NOT NULL,
 currency CHAR(3) NOT NULL,
 state VARCHAR(16) NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 consumed_order_id UUID,
 consumed_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT pk_sales_accepted_quote PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_accepted_quote_proposal FOREIGN KEY(tenant_id,proposal_id)
  REFERENCES crm.sales_price_proposal(tenant_id,id),
 CONSTRAINT fk_accepted_quote_offer FOREIGN KEY(tenant_id,offer_id)
  REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_accepted_quote_buyer FOREIGN KEY(buyer_user_id)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_accepted_quote_order FOREIGN KEY(tenant_id,consumed_order_id)
  REFERENCES crm.sales_order(tenant_id,id),
 CONSTRAINT uq_accepted_quote_proposal UNIQUE(tenant_id,proposal_id),
 CONSTRAINT uq_accepted_quote_order UNIQUE(tenant_id,consumed_order_id),
 CONSTRAINT ck_accepted_quote_qty CHECK(quantity BETWEEN 1 AND 1000 AND agreed_unit_price>0),
 CONSTRAINT ck_accepted_quote_state CHECK(
  (state='CONSUMED' AND consumed_order_id IS NOT NULL AND consumed_at IS NOT NULL)
  OR
  (state IN ('AVAILABLE','EXPIRED','INVALIDATED')
   AND consumed_order_id IS NULL AND consumed_at IS NULL))
);
CREATE INDEX idx_accepted_quote_buyer
 ON crm.sales_accepted_quote(buyer_user_id,state,expires_at);

CREATE TABLE crm.sales_proposal_command_receipt (
 tenant_id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 actor_user_id UUID NOT NULL,
 action_code VARCHAR(16) NOT NULL,
 request_sha256 CHAR(64) NOT NULL,
 proposal_id UUID NOT NULL,
 result_revision BIGINT NOT NULL,
 result_state VARCHAR(24) NOT NULL,
 result_next_actor VARCHAR(12) NOT NULL,
 result_round_count INTEGER NOT NULL,
 result_unit_price NUMERIC(19,4) NOT NULL,
 result_quantity INTEGER NOT NULL,
 result_currency CHAR(3) NOT NULL,
 result_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 result_quote_id UUID,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sales_proposal_receipt PRIMARY KEY(tenant_id,request_key),
 CONSTRAINT fk_sales_proposal_receipt_proposal FOREIGN KEY(tenant_id,proposal_id)
  REFERENCES crm.sales_price_proposal(tenant_id,id),
 CONSTRAINT fk_sales_proposal_receipt_actor FOREIGN KEY(actor_user_id)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_sales_proposal_receipt_quote FOREIGN KEY(tenant_id,result_quote_id)
  REFERENCES crm.sales_accepted_quote(tenant_id,id),
 CONSTRAINT ck_sales_proposal_receipt_hash CHECK(LENGTH(request_sha256)=64),
 CONSTRAINT ck_sales_proposal_receipt_result CHECK(
  result_revision>=1 AND result_round_count BETWEEN 0 AND 3
  AND result_unit_price>0 AND result_quantity BETWEEN 1 AND 1000)
);
CREATE INDEX idx_proposal_receipt_proposal
 ON crm.sales_proposal_command_receipt(tenant_id,proposal_id);

--rollback DROP TABLE crm.sales_proposal_command_receipt;
--rollback DROP TABLE crm.sales_accepted_quote;
--rollback DROP TABLE crm.sales_price_proposal_event;
--rollback DROP TABLE crm.sales_price_proposal;

--changeset rm:0090-sales-quote-order-snapshot
-- Legacy lines keep NULL price_source; never infer negotiated history.
ALTER TABLE crm.sales_order_line
 ADD COLUMN price_source VARCHAR(16);
ALTER TABLE crm.sales_order_line
 ADD COLUMN accepted_quote_id UUID;
ALTER TABLE crm.sales_order_line
 ADD CONSTRAINT fk_sales_line_quote FOREIGN KEY(tenant_id,accepted_quote_id)
 REFERENCES crm.sales_accepted_quote(tenant_id,id);
ALTER TABLE crm.sales_order_line
 ADD CONSTRAINT ck_sales_line_price_source CHECK(
  (price_source IS NULL AND accepted_quote_id IS NULL) OR
  (price_source='LIST' AND accepted_quote_id IS NULL) OR
  (price_source='NEGOTIATED' AND accepted_quote_id IS NOT NULL));
CREATE UNIQUE INDEX uq_sales_line_accepted_quote
 ON crm.sales_order_line(tenant_id,accepted_quote_id);
--rollback DROP INDEX crm.uq_sales_line_accepted_quote;
--rollback ALTER TABLE crm.sales_order_line DROP CONSTRAINT ck_sales_line_price_source;
--rollback ALTER TABLE crm.sales_order_line DROP CONSTRAINT fk_sales_line_quote;
--rollback ALTER TABLE crm.sales_order_line DROP COLUMN accepted_quote_id;
--rollback ALTER TABLE crm.sales_order_line DROP COLUMN price_source;

--changeset rm:0091-sales-proposal-event-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION crm.reject_price_proposal_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Price proposal events and command receipts are immutable';
END;
$$;
CREATE TRIGGER trg_price_proposal_event_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_price_proposal_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_price_proposal_event_mutation();
CREATE TRIGGER trg_price_proposal_receipt_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_proposal_command_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.reject_price_proposal_event_mutation();
--rollback DROP TRIGGER trg_price_proposal_receipt_immutable ON crm.sales_proposal_command_receipt;
--rollback DROP TRIGGER trg_price_proposal_event_immutable ON crm.sales_price_proposal_event;
--rollback DROP FUNCTION crm.reject_price_proposal_event_mutation();


--changeset rm:0092-qr-warehouse-scope-and-preparation
-- Active staff+warehouse assignment is an explicit additional IAM scope.
CREATE TABLE public.iam_warehouse_staff_scope (
 tenant_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 state VARCHAR(12) NOT NULL,
 granted_by UUID NOT NULL,
 granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
 changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_iam_warehouse_staff_scope PRIMARY KEY(tenant_id,warehouse_id,actor_user_id),
 CONSTRAINT fk_warehouse_scope_location FOREIGN KEY(tenant_id,warehouse_id)
   REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_warehouse_scope_actor FOREIGN KEY(actor_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_warehouse_scope_grantor FOREIGN KEY(granted_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_warehouse_scope_state CHECK(state IN('ACTIVE','REVOKED')),
 CONSTRAINT ck_warehouse_scope_version CHECK(row_version>=0)
);
CREATE INDEX idx_warehouse_scope_actor ON public.iam_warehouse_staff_scope(tenant_id,actor_user_id,state);

-- The complete order line is picked once; corrections need a separate supervised workflow.
CREATE TABLE crm.sales_order_pick_confirmation (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 order_id UUID NOT NULL,
 order_line_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 item_id UUID NOT NULL,
 quantity INTEGER NOT NULL,
 picked_by UUID NOT NULL,
 picked_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sales_pick_confirmation PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_sales_pick_line UNIQUE(tenant_id,order_line_id),
 CONSTRAINT fk_sales_pick_line FOREIGN KEY(tenant_id,order_id,order_line_id)
   REFERENCES crm.sales_order_line(tenant_id,order_id,id),
 CONSTRAINT fk_sales_pick_wh FOREIGN KEY(tenant_id,warehouse_id)
   REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_sales_pick_actor FOREIGN KEY(picked_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_sales_pick_item FOREIGN KEY(tenant_id,item_id)
   REFERENCES crm.cat_item(tenant_id,id),
 CONSTRAINT ck_sales_pick_qty CHECK(quantity BETWEEN 1 AND 1000)
);
CREATE INDEX idx_sales_pick_order ON crm.sales_order_pick_confirmation(tenant_id,order_id);

CREATE TABLE crm.sales_preparation_receipt (
 tenant_id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 actor_user_id UUID NOT NULL,
 action_code VARCHAR(16) NOT NULL,
 order_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 line_id UUID,
 request_sha256 VARCHAR(64) NOT NULL,
 result_status VARCHAR(24) NOT NULL,
 result_row_version BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sales_prep_receipt PRIMARY KEY(tenant_id,request_key),
 CONSTRAINT fk_sales_prep_order FOREIGN KEY(tenant_id,order_id)
   REFERENCES crm.sales_order(tenant_id,id),
 CONSTRAINT fk_sales_prep_location FOREIGN KEY(tenant_id,warehouse_id)
   REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_sales_prep_actor FOREIGN KEY(actor_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_sales_prep_action CHECK(action_code IN('START','PICK','READY')),
 CONSTRAINT ck_sales_prep_result CHECK(result_row_version>=0 AND LENGTH(request_sha256)=64)
);
--rollback DROP TABLE crm.sales_preparation_receipt;
--rollback DROP TABLE crm.sales_order_pick_confirmation;
--rollback DROP TABLE public.iam_warehouse_staff_scope;

--changeset rm:0093-qr-one-time-pickup-and-handover
CREATE TABLE crm.sales_pickup_credential (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 order_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 active_order_id UUID,
 credential_digest BYTEA NOT NULL,
 issued_by UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 generation INTEGER NOT NULL,
 state VARCHAR(12) NOT NULL,
 issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 consumed_at TIMESTAMP WITH TIME ZONE,
 revoked_at TIMESTAMP WITH TIME ZONE,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_pickup_credential PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_pickup_order FOREIGN KEY(tenant_id,order_id)
   REFERENCES crm.sales_order(tenant_id,id),
 CONSTRAINT fk_pickup_warehouse FOREIGN KEY(tenant_id,warehouse_id)
   REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_pickup_issuer FOREIGN KEY(issued_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT uq_pickup_request UNIQUE(tenant_id,request_key),
 CONSTRAINT uq_pickup_digest UNIQUE(credential_digest),
 CONSTRAINT uq_pickup_active_order UNIQUE(tenant_id,active_order_id),
 CONSTRAINT uq_pickup_generation UNIQUE(tenant_id,order_id,generation),
 CONSTRAINT ck_pickup_generation CHECK(generation>0 AND row_version>=0),
 CONSTRAINT ck_pickup_expiry CHECK(expires_at>issued_at),
 CONSTRAINT ck_pickup_state CHECK(
   (state='ISSUED' AND active_order_id=order_id AND revoked_at IS NULL AND consumed_at IS NULL)
   OR(state='REVOKED' AND active_order_id IS NULL AND revoked_at IS NOT NULL AND consumed_at IS NULL)
   OR(state='CONSUMED' AND active_order_id IS NULL AND consumed_at IS NOT NULL AND revoked_at IS NULL))
);
CREATE INDEX idx_pickup_credential_history
 ON crm.sales_pickup_credential(tenant_id,order_id,generation DESC);

CREATE TABLE crm.sales_pickup_handover (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 order_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 credential_id UUID NOT NULL,
 staff_actor_id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 request_sha256 VARCHAR(64) NOT NULL,
 status_event_id UUID NOT NULL,
 happened_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_pickup_handover PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_pickup_handover_order UNIQUE(tenant_id,order_id),
 CONSTRAINT uq_pickup_handover_credential UNIQUE(tenant_id,credential_id),
 CONSTRAINT uq_pickup_handover_key UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_handover_order FOREIGN KEY(tenant_id,order_id)
   REFERENCES crm.sales_order(tenant_id,id),
 CONSTRAINT fk_handover_wh FOREIGN KEY(tenant_id,warehouse_id)
   REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_handover_credential FOREIGN KEY(tenant_id,credential_id)
   REFERENCES crm.sales_pickup_credential(tenant_id,id),
 CONSTRAINT fk_handover_staff FOREIGN KEY(staff_actor_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_handover_event FOREIGN KEY(tenant_id,status_event_id)
   REFERENCES crm.sales_order_status_event(tenant_id,id),
 CONSTRAINT ck_handover_hash CHECK(LENGTH(request_sha256)=64)
);
CREATE TABLE crm.sales_pickup_handover_line (
 tenant_id UUID NOT NULL,
 handover_id UUID NOT NULL,
 reservation_id UUID NOT NULL,
 movement_id UUID NOT NULL,
 quantity NUMERIC(19,4) NOT NULL,
 CONSTRAINT pk_handover_line PRIMARY KEY(tenant_id,handover_id,reservation_id),
 CONSTRAINT uq_handover_movement UNIQUE(tenant_id,movement_id),
 CONSTRAINT fk_handover_line_receipt FOREIGN KEY(tenant_id,handover_id)
   REFERENCES crm.sales_pickup_handover(tenant_id,id),
 CONSTRAINT fk_handover_line_reservation FOREIGN KEY(tenant_id,reservation_id)
   REFERENCES crm.inv_stock_reservation(tenant_id,id),
 CONSTRAINT fk_handover_line_movement FOREIGN KEY(tenant_id,movement_id)
   REFERENCES crm.inv_stock_movement(tenant_id,id),
 CONSTRAINT ck_handover_line_qty CHECK(quantity>0)
);
--rollback DROP TABLE crm.sales_pickup_handover_line;
--rollback DROP TABLE crm.sales_pickup_handover;
--rollback DROP TABLE crm.sales_pickup_credential;

--changeset rm:0094-qr-warehouse-receipt-immutability dbms:postgresql splitStatements:false
CREATE FUNCTION crm.reject_pickup_receipt_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Pickup and preparation proof is append-only';
END;
$$;
CREATE TRIGGER trg_pick_confirmation_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_order_pick_confirmation
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
CREATE TRIGGER trg_prep_receipt_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_preparation_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
CREATE TRIGGER trg_handover_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_pickup_handover
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
CREATE TRIGGER trg_handover_line_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_pickup_handover_line
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
--rollback DROP TRIGGER trg_handover_line_immutable ON crm.sales_pickup_handover_line;
--rollback DROP TRIGGER trg_handover_immutable ON crm.sales_pickup_handover;
--rollback DROP TRIGGER trg_prep_receipt_immutable ON crm.sales_preparation_receipt;
--rollback DROP TRIGGER trg_pick_confirmation_immutable ON crm.sales_order_pick_confirmation;
--rollback DROP FUNCTION crm.reject_pickup_receipt_mutation();


--changeset rm:0095-qr07-return-domain-r0
-- QR-07 R0: a return case is separate from, and never rewrites, completed SALES.
-- Source order/line and immutable pickup receipt are referenced in one tenant.
ALTER TABLE crm.sales_pickup_handover
 ADD CONSTRAINT uq_qr07_handover_tenant_order_id UNIQUE (tenant_id,order_id,id);

CREATE TABLE crm.sales_return_request (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 order_id UUID NOT NULL,
 source_handover_id UUID NOT NULL,
 buyer_user_id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 request_fingerprint VARCHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL,
 reason_code VARCHAR(40) NOT NULL,
 policy_version VARCHAR(40) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_sales_return_request PRIMARY KEY (tenant_id,id),
 CONSTRAINT uq_sales_return_request_key UNIQUE (tenant_id,request_key),
 CONSTRAINT uq_sales_return_request_order UNIQUE (tenant_id,order_id,id),
 CONSTRAINT fk_sales_return_request_source FOREIGN KEY (tenant_id,order_id,source_handover_id)
   REFERENCES crm.sales_pickup_handover(tenant_id,order_id,id),
 CONSTRAINT fk_sales_return_request_buyer FOREIGN KEY (buyer_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_sales_return_request_fingerprint CHECK (LENGTH(request_fingerprint)=64),
 CONSTRAINT ck_sales_return_request_state CHECK(state IN
   ('REQUESTED','UNDER_REVIEW','APPROVED','REJECTED','AWAITING_RECEIPT',
    'RECEIVED','INSPECTED','RESTOCKED','DISPOSED','CANCELLED_BY_BUYER')),
 CONSTRAINT ck_sales_return_request_version CHECK(row_version>=0)
);
CREATE INDEX idx_sales_return_order ON crm.sales_return_request(tenant_id,order_id,created_at);

CREATE TABLE crm.sales_return_line (
 tenant_id UUID NOT NULL,
 return_id UUID NOT NULL,
 order_id UUID NOT NULL,
 order_line_id UUID NOT NULL,
 quantity INTEGER NOT NULL,
 CONSTRAINT pk_sales_return_line PRIMARY KEY(tenant_id,return_id,order_line_id),
 CONSTRAINT fk_sales_return_line_request FOREIGN KEY(tenant_id,order_id,return_id)
   REFERENCES crm.sales_return_request(tenant_id,order_id,id),
 CONSTRAINT fk_sales_return_line_original FOREIGN KEY(tenant_id,order_id,order_line_id)
   REFERENCES crm.sales_order_line(tenant_id,order_id,id),
 CONSTRAINT ck_sales_return_line_quantity CHECK(quantity>0)
);
CREATE INDEX idx_sales_return_line_source ON crm.sales_return_line(tenant_id,order_id,order_line_id);

CREATE TABLE crm.sales_return_event (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 return_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sales_return_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_sales_return_event_request FOREIGN KEY(tenant_id,return_id)
   REFERENCES crm.sales_return_request(tenant_id,id),
 CONSTRAINT fk_sales_return_event_actor FOREIGN KEY(actor_user_id)
   REFERENCES public.iam_user_account(id)
);
--rollback DROP TABLE crm.sales_return_event;
--rollback DROP TABLE crm.sales_return_line;
--rollback DROP TABLE crm.sales_return_request;
--rollback ALTER TABLE crm.sales_pickup_handover DROP CONSTRAINT uq_qr07_handover_tenant_order_id;

--changeset rm:0096-qr07-return-event-immutability dbms:postgresql splitStatements:false
CREATE TRIGGER trg_qr07_return_event_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_return_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
--rollback DROP TRIGGER trg_qr07_return_event_immutable ON crm.sales_return_event;


--changeset rm:0097-qr07-review-decisions
-- Add revision-checked, replay-resistant and audit-ready R1 transitions.
-- Legacy REQUESTED events remain valid; null is reserved for R0 events.
ALTER TABLE crm.sales_return_event ADD COLUMN request_key VARCHAR(100);
ALTER TABLE crm.sales_return_event ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE crm.sales_return_event ADD COLUMN from_state VARCHAR(32);
ALTER TABLE crm.sales_return_event ADD COLUMN to_state VARCHAR(32);
ALTER TABLE crm.sales_return_event ADD COLUMN expected_row_version BIGINT;
ALTER TABLE crm.sales_return_event ADD COLUMN policy_version VARCHAR(40);
ALTER TABLE crm.sales_return_event
 ADD CONSTRAINT uq_qr07_decision_request_key UNIQUE (tenant_id,request_key);
ALTER TABLE crm.sales_return_event
 ADD CONSTRAINT ck_qr07_event_request_fingerprint
   CHECK(request_fingerprint IS NULL OR LENGTH(request_fingerprint)=64);
ALTER TABLE crm.sales_return_event
 ADD CONSTRAINT ck_qr07_event_revision
   CHECK(expected_row_version IS NULL OR expected_row_version>=0);
ALTER TABLE crm.sales_return_event
 ADD CONSTRAINT ck_qr07_event_decision_fields
   CHECK (
    (request_key IS NULL AND request_fingerprint IS NULL AND
     from_state IS NULL AND to_state IS NULL AND expected_row_version IS NULL AND
     policy_version IS NULL)
    OR
    (request_key IS NOT NULL AND request_fingerprint IS NOT NULL AND
     from_state IS NOT NULL AND to_state IS NOT NULL AND
     expected_row_version IS NOT NULL AND policy_version IS NOT NULL)
   );
CREATE INDEX idx_qr07_event_return_history
 ON crm.sales_return_event(tenant_id,return_id,occurred_at,id);
--rollback DROP INDEX idx_qr07_event_return_history;
--rollback ALTER TABLE crm.sales_return_event DROP CONSTRAINT ck_qr07_event_decision_fields;
--rollback ALTER TABLE crm.sales_return_event DROP CONSTRAINT ck_qr07_event_revision;
--rollback ALTER TABLE crm.sales_return_event DROP CONSTRAINT ck_qr07_event_request_fingerprint;
--rollback ALTER TABLE crm.sales_return_event DROP CONSTRAINT uq_qr07_decision_request_key;
--rollback ALTER TABLE crm.sales_return_event DROP COLUMN policy_version;
--rollback ALTER TABLE crm.sales_return_event DROP COLUMN expected_row_version;
--rollback ALTER TABLE crm.sales_return_event DROP COLUMN to_state;
--rollback ALTER TABLE crm.sales_return_event DROP COLUMN from_state;
--rollback ALTER TABLE crm.sales_return_event DROP COLUMN request_fingerprint;
--rollback ALTER TABLE crm.sales_return_event DROP COLUMN request_key;


--changeset rm:0098-qr07-r2-physical-receipt-inspection-post
-- R2 physical evidence and disposition never mutate original pickup proof.
CREATE TABLE crm.sales_return_reception (
 tenant_id UUID NOT NULL, return_id UUID NOT NULL, receipt_id UUID NOT NULL,
 warehouse_id UUID NOT NULL, actor_user_id UUID NOT NULL,
 evidence_ref VARCHAR(120) NOT NULL, request_key VARCHAR(100) NOT NULL,
 happened_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_qr07_reception PRIMARY KEY(tenant_id,return_id),
 CONSTRAINT uq_qr07_reception_id UNIQUE(tenant_id,receipt_id),
 CONSTRAINT uq_qr07_reception_key UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_qr07_reception_return FOREIGN KEY(tenant_id,return_id)
   REFERENCES crm.sales_return_request(tenant_id,id),
 CONSTRAINT fk_qr07_reception_warehouse FOREIGN KEY(tenant_id,warehouse_id)
   REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_qr07_reception_actor FOREIGN KEY(actor_user_id)
   REFERENCES public.iam_user_account(id)
);
CREATE TABLE crm.sales_return_inspection (
 tenant_id UUID NOT NULL, return_id UUID NOT NULL, inspection_id UUID NOT NULL,
 actor_user_id UUID NOT NULL, disposition VARCHAR(20) NOT NULL,
 evidence_ref VARCHAR(120) NOT NULL, request_key VARCHAR(100) NOT NULL,
 happened_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_qr07_inspection PRIMARY KEY(tenant_id,return_id),
 CONSTRAINT uq_qr07_inspection_key UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_qr07_inspection_return FOREIGN KEY(tenant_id,return_id)
   REFERENCES crm.sales_return_request(tenant_id,id),
 CONSTRAINT ck_qr07_disposition CHECK(disposition IN('RESTOCK','WRITE_OFF','QUARANTINE'))
);
CREATE TABLE crm.sales_return_posting (
 tenant_id UUID NOT NULL, return_id UUID NOT NULL, posting_id UUID NOT NULL,
 actor_user_id UUID NOT NULL, warehouse_id UUID NOT NULL,
 item_id UUID NOT NULL, quantity NUMERIC(19,4) NOT NULL,
 disposition VARCHAR(20) NOT NULL, movement_id UUID,
 request_key VARCHAR(100) NOT NULL, happened_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_qr07_posting PRIMARY KEY(tenant_id,return_id),
 CONSTRAINT uq_qr07_posting_key UNIQUE(tenant_id,request_key),
 CONSTRAINT uq_qr07_posting_movement UNIQUE(tenant_id,movement_id),
 CONSTRAINT fk_qr07_posting_return FOREIGN KEY(tenant_id,return_id)
   REFERENCES crm.sales_return_request(tenant_id,id),
 CONSTRAINT fk_qr07_posting_stock FOREIGN KEY(tenant_id,movement_id)
   REFERENCES crm.inv_stock_movement(tenant_id,id),
 CONSTRAINT ck_qr07_posting_qty CHECK(quantity>0),
 CONSTRAINT ck_qr07_posting_disposition CHECK(
   (disposition='RESTOCK' AND movement_id IS NOT NULL)
   OR (disposition='WRITE_OFF' AND movement_id IS NULL))
);
--rollback DROP TABLE crm.sales_return_posting;
--rollback DROP TABLE crm.sales_return_inspection;
--rollback DROP TABLE crm.sales_return_reception;

--changeset rm:0099-qr07-r2-physical-evidence-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_qr07_reception_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_return_reception
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
CREATE TRIGGER trg_qr07_inspection_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_return_inspection
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
CREATE TRIGGER trg_qr07_posting_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_return_posting
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
--rollback DROP TRIGGER trg_qr07_posting_immutable ON crm.sales_return_posting;
--rollback DROP TRIGGER trg_qr07_inspection_immutable ON crm.sales_return_inspection;
--rollback DROP TRIGGER trg_qr07_reception_immutable ON crm.sales_return_reception;


--changeset rm:0100-qr07-r25-quarantine-ledger
-- Quarantine is segregated from sellable on_hand / reserved.
-- Every quarantine addition must be backed by one inspected return.
CREATE TABLE crm.inv_return_quarantine_balance (
 tenant_id UUID NOT NULL, warehouse_id UUID NOT NULL, item_id UUID NOT NULL,
 quantity NUMERIC(19,4) NOT NULL DEFAULT 0, row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_qr07_quarantine_balance PRIMARY KEY(tenant_id,warehouse_id,item_id),
 CONSTRAINT fk_qr07_quarantine_warehouse FOREIGN KEY(tenant_id,warehouse_id)
  REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT fk_qr07_quarantine_item FOREIGN KEY(tenant_id,item_id)
  REFERENCES crm.cat_item(tenant_id,id),
 CONSTRAINT ck_qr07_quarantine_qty CHECK(quantity>=0)
);
CREATE TABLE crm.inv_return_quarantine_movement (
 tenant_id UUID NOT NULL,id UUID NOT NULL, return_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,item_id UUID NOT NULL,
 delta_quantity NUMERIC(19,4) NOT NULL, before_quantity NUMERIC(19,4) NOT NULL,
 after_quantity NUMERIC(19,4) NOT NULL, actor_user_id UUID NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_qr07_quarantine_movement PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_qr07_quarantine_return UNIQUE(tenant_id,return_id),
 CONSTRAINT fk_qr07_quarantine_return FOREIGN KEY(tenant_id,return_id)
  REFERENCES crm.sales_return_request(tenant_id,id),
 CONSTRAINT fk_qr07_quarantine_position FOREIGN KEY(tenant_id,warehouse_id,item_id)
  REFERENCES crm.inv_return_quarantine_balance(tenant_id,warehouse_id,item_id),
 CONSTRAINT ck_qr07_quarantine_movement_positive CHECK(delta_quantity>0),
 CONSTRAINT ck_qr07_quarantine_movement_math CHECK(after_quantity=before_quantity+delta_quantity)
);
ALTER TABLE crm.sales_return_posting ADD COLUMN quarantine_movement_id UUID;
ALTER TABLE crm.sales_return_posting DROP CONSTRAINT ck_qr07_posting_disposition;
ALTER TABLE crm.sales_return_posting
 ADD CONSTRAINT fk_qr07_posting_quarantine FOREIGN KEY(tenant_id,quarantine_movement_id)
 REFERENCES crm.inv_return_quarantine_movement(tenant_id,id);
ALTER TABLE crm.sales_return_posting
 ADD CONSTRAINT uq_qr07_posting_quarantine UNIQUE(tenant_id,quarantine_movement_id);
ALTER TABLE crm.sales_return_posting
 ADD CONSTRAINT ck_qr07_posting_disposition CHECK(
  (disposition='RESTOCK' AND movement_id IS NOT NULL AND quarantine_movement_id IS NULL)
  OR (disposition='WRITE_OFF' AND movement_id IS NULL AND quarantine_movement_id IS NULL)
  OR (disposition='QUARANTINE' AND movement_id IS NULL AND quarantine_movement_id IS NOT NULL)
 );
ALTER TABLE crm.sales_return_request DROP CONSTRAINT ck_sales_return_request_state;
ALTER TABLE crm.sales_return_request ADD CONSTRAINT ck_sales_return_request_state
 CHECK(state IN ('REQUESTED','UNDER_REVIEW','APPROVED','REJECTED','AWAITING_RECEIPT',
 'RECEIVED','INSPECTED','RESTOCKED','DISPOSED','QUARANTINED','CANCELLED_BY_BUYER'));
--rollback ALTER TABLE crm.sales_return_request DROP CONSTRAINT ck_sales_return_request_state;
--rollback ALTER TABLE crm.sales_return_request ADD CONSTRAINT ck_sales_return_request_state CHECK(state IN ('REQUESTED','UNDER_REVIEW','APPROVED','REJECTED','AWAITING_RECEIPT','RECEIVED','INSPECTED','RESTOCKED','DISPOSED','CANCELLED_BY_BUYER'));
--rollback ALTER TABLE crm.sales_return_posting DROP CONSTRAINT ck_qr07_posting_disposition;
--rollback ALTER TABLE crm.sales_return_posting ADD CONSTRAINT ck_qr07_posting_disposition CHECK((disposition='RESTOCK' AND movement_id IS NOT NULL) OR (disposition='WRITE_OFF' AND movement_id IS NULL));
--rollback ALTER TABLE crm.sales_return_posting DROP CONSTRAINT uq_qr07_posting_quarantine;
--rollback ALTER TABLE crm.sales_return_posting DROP CONSTRAINT fk_qr07_posting_quarantine;
--rollback ALTER TABLE crm.sales_return_posting DROP COLUMN quarantine_movement_id;
--rollback DROP TABLE crm.inv_return_quarantine_movement;
--rollback DROP TABLE crm.inv_return_quarantine_balance;

--changeset rm:0101-qr07-r25-quarantine-movement-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_qr07_quarantine_movement_immutable
 BEFORE UPDATE OR DELETE ON crm.inv_return_quarantine_movement
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
--rollback DROP TRIGGER trg_qr07_quarantine_movement_immutable ON crm.inv_return_quarantine_movement;


--changeset rm:0102-qr07-r25-one-time-physical-return-proof
-- Buyer-issued one-time physical return credential: store digest, never raw token.
CREATE TABLE crm.sales_return_reception_proof (
 tenant_id UUID NOT NULL, return_id UUID NOT NULL,
 warehouse_id UUID NOT NULL, buyer_user_id UUID NOT NULL,
 digest_sha256 VARCHAR(64) NOT NULL, generation INTEGER NOT NULL,
 request_key VARCHAR(100) NOT NULL, state VARCHAR(16) NOT NULL,
 issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 consumed_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT pk_qr07_reception_proof PRIMARY KEY (tenant_id,return_id),
 CONSTRAINT uq_qr07_reception_proof_key UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_qr07_reception_proof_return FOREIGN KEY(tenant_id,return_id)
 REFERENCES crm.sales_return_request(tenant_id,id),
 CONSTRAINT fk_qr07_reception_proof_warehouse FOREIGN KEY(tenant_id,warehouse_id)
 REFERENCES public.org_location(workspace_id,id),
 CONSTRAINT ck_qr07_proof_state CHECK(state IN('ISSUED','CONSUMED')),
 CONSTRAINT ck_qr07_proof_generation CHECK(generation>0),
 CONSTRAINT ck_qr07_proof_digest CHECK(LENGTH(digest_sha256)=64)
);
--rollback DROP TABLE crm.sales_return_reception_proof;


--changeset rm:0103-crm-r22-buyer-link
-- A seller-owned CRM customer can be linked to a global IAM buyer only after
-- explicit verified buyer approval. No copied PII and no copied SALES rows.
CREATE TABLE crm.customer_buyer_link (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 customer_id UUID NOT NULL,
 buyer_user_id UUID NOT NULL,
 proposed_by UUID NOT NULL,
 confirmed_at TIMESTAMP WITH TIME ZONE,
 status VARCHAR(16) NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_crm_customer_buyer_link PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_crm_buyer_link_customer UNIQUE(tenant_id,customer_id),
 CONSTRAINT uk_crm_buyer_link_buyer UNIQUE(tenant_id,buyer_user_id),
 CONSTRAINT uk_crm_buyer_link_request UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_crm_buyer_link_customer FOREIGN KEY(tenant_id,customer_id)
   REFERENCES crm.customer(tenant_id,id),
 CONSTRAINT fk_crm_buyer_link_buyer FOREIGN KEY(buyer_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_crm_buyer_link_proposer FOREIGN KEY(proposed_by)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_crm_buyer_link_state CHECK(status IN ('PENDING','CONFIRMED')),
 CONSTRAINT ck_crm_buyer_link_confirmed CHECK(
   (status='PENDING' AND confirmed_at IS NULL)
   OR (status='CONFIRMED' AND confirmed_at IS NOT NULL))
);
CREATE TABLE crm.customer_buyer_link_event (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 link_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 action_code VARCHAR(24) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_crm_buyer_link_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT fk_crm_buyer_link_event FOREIGN KEY(tenant_id,link_id)
   REFERENCES crm.customer_buyer_link(tenant_id,id),
 CONSTRAINT fk_crm_buyer_link_event_actor FOREIGN KEY(actor_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_crm_buyer_link_event_action CHECK(action_code IN ('PROPOSED','CONFIRMED')),
 CONSTRAINT uk_crm_buyer_link_event_action UNIQUE(tenant_id,link_id,action_code)
);
--rollback DROP TABLE crm.customer_buyer_link_event;
--rollback DROP TABLE crm.customer_buyer_link;


--changeset rm:0103-qr07-r25-quarantine-resolution
-- Release/disposal is a separate append-only exit from non-sellable quarantine.
CREATE TABLE crm.sales_return_quarantine_resolution (
 tenant_id UUID NOT NULL,return_id UUID NOT NULL,id UUID NOT NULL,
 warehouse_id UUID NOT NULL,item_id UUID NOT NULL,
 quantity NUMERIC(19,4) NOT NULL,before_quantity NUMERIC(19,4) NOT NULL,
 after_quantity NUMERIC(19,4) NOT NULL,
 action_code VARCHAR(20) NOT NULL,actor_user_id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,evidence_ref VARCHAR(120) NOT NULL,
 stock_movement_id UUID,happened_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_qr07_quarantine_resolution PRIMARY KEY(tenant_id,return_id),
 CONSTRAINT uq_qr07_resolution_request_key UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_qr07_resolution_origin FOREIGN KEY(tenant_id,return_id)
   REFERENCES crm.inv_return_quarantine_movement(tenant_id,return_id),
 CONSTRAINT fk_qr07_resolution_stock FOREIGN KEY(tenant_id,stock_movement_id)
   REFERENCES crm.inv_stock_movement(tenant_id,id),
 CONSTRAINT fk_qr07_resolution_position FOREIGN KEY(tenant_id,warehouse_id,item_id)
   REFERENCES crm.inv_return_quarantine_balance(tenant_id,warehouse_id,item_id),
 CONSTRAINT ck_qr07_resolution_positive CHECK(quantity>0),
 CONSTRAINT ck_qr07_resolution_balance CHECK(
   before_quantity>=quantity AND after_quantity=before_quantity-quantity),
 CONSTRAINT ck_qr07_resolution_kind CHECK(
  (action_code='RESTOCK' AND stock_movement_id IS NOT NULL)
  OR (action_code='DISPOSE' AND stock_movement_id IS NULL))
);
--rollback DROP TABLE crm.sales_return_quarantine_resolution;

--changeset rm:0104-qr07-r25-quarantine-resolution-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_qr07_quarantine_resolution_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_return_quarantine_resolution
 FOR EACH ROW EXECUTE FUNCTION crm.reject_pickup_receipt_mutation();
--rollback DROP TRIGGER trg_qr07_quarantine_resolution_immutable ON crm.sales_return_quarantine_resolution;


--changeset rm:0104-crm-r22-buyer-link-event-immutable dbms:postgresql splitStatements:false
-- Buyer confirmation evidence cannot be rewritten or deleted after commit.
CREATE TRIGGER trg_crm_r22_buyer_event_immutable
 BEFORE UPDATE OR DELETE ON crm.customer_buyer_link_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_crm_r22_buyer_event_immutable ON crm.customer_buyer_link_event;


--changeset rm:0105-crm-r22b-lifecycle-dispute dbms:postgresql
-- Keep terminal link rows forever; only currently active mappings occupy
-- one-to-one uniqueness slots. Revoked links can be re-proposed with NEW consent.
ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT uk_crm_buyer_link_customer;
ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT uk_crm_buyer_link_buyer;
CREATE UNIQUE INDEX uk_crm_buyer_active_customer ON crm.customer_buyer_link(tenant_id,customer_id)
 WHERE status IN ('PENDING','CONFIRMED','DISPUTED');
CREATE UNIQUE INDEX uk_crm_buyer_active_buyer ON crm.customer_buyer_link(tenant_id,buyer_user_id)
 WHERE status IN ('PENDING','CONFIRMED','DISPUTED');
ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT ck_crm_buyer_link_state;
ALTER TABLE crm.customer_buyer_link ADD CONSTRAINT ck_crm_buyer_link_state
 CHECK(status IN ('PENDING','CONFIRMED','DISPUTED','REVOKED'));
ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT ck_crm_buyer_link_confirmed;
ALTER TABLE crm.customer_buyer_link ADD CONSTRAINT ck_crm_buyer_link_confirmed CHECK(
 (status='PENDING' AND confirmed_at IS NULL)
 OR (status IN ('CONFIRMED','DISPUTED') AND confirmed_at IS NOT NULL)
 OR status='REVOKED');
ALTER TABLE crm.customer_buyer_link_event DROP CONSTRAINT ck_crm_buyer_link_event_action;
ALTER TABLE crm.customer_buyer_link_event ADD CONSTRAINT ck_crm_buyer_link_event_action
 CHECK(action_code IN ('PROPOSED','CONFIRMED','DISPUTED','REVOKED','DISPUTE_CLOSED'));
--rollback ALTER TABLE crm.customer_buyer_link_event DROP CONSTRAINT ck_crm_buyer_link_event_action;
--rollback ALTER TABLE crm.customer_buyer_link_event ADD CONSTRAINT ck_crm_buyer_link_event_action CHECK(action_code IN ('PROPOSED','CONFIRMED'));
--rollback ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT ck_crm_buyer_link_confirmed;
--rollback ALTER TABLE crm.customer_buyer_link ADD CONSTRAINT ck_crm_buyer_link_confirmed CHECK((status='PENDING' AND confirmed_at IS NULL) OR (status='CONFIRMED' AND confirmed_at IS NOT NULL));
--rollback ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT ck_crm_buyer_link_state;
--rollback ALTER TABLE crm.customer_buyer_link ADD CONSTRAINT ck_crm_buyer_link_state CHECK(status IN ('PENDING','CONFIRMED'));
--rollback DROP INDEX crm.uk_crm_buyer_active_buyer;
--rollback DROP INDEX crm.uk_crm_buyer_active_customer;
--rollback ALTER TABLE crm.customer_buyer_link ADD CONSTRAINT uk_crm_buyer_link_buyer UNIQUE(tenant_id,buyer_user_id);
--rollback ALTER TABLE crm.customer_buyer_link ADD CONSTRAINT uk_crm_buyer_link_customer UNIQUE(tenant_id,customer_id);


--changeset rm:0105h2-crm-r22b-lifecycle-dispute-compat dbms:h2
-- H2 has no partial unique indexes. Nullable generated columns replicate
-- PostgreSQL's active-only uniqueness without restricting revoked history.
-- PostgreSQL 0105 SQL body is unchanged for checksum compatibility.
ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT uk_crm_buyer_link_customer;
ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT uk_crm_buyer_link_buyer;
ALTER TABLE crm.customer_buyer_link
 ADD COLUMN active_customer_id UUID GENERATED ALWAYS AS
  (CASE WHEN status IN ('PENDING','CONFIRMED','DISPUTED') THEN customer_id ELSE NULL END);
ALTER TABLE crm.customer_buyer_link
 ADD COLUMN active_buyer_user_id UUID GENERATED ALWAYS AS
  (CASE WHEN status IN ('PENDING','CONFIRMED','DISPUTED') THEN buyer_user_id ELSE NULL END);
CREATE UNIQUE INDEX uk_crm_buyer_active_customer ON crm.customer_buyer_link(tenant_id,active_customer_id);
CREATE UNIQUE INDEX uk_crm_buyer_active_buyer ON crm.customer_buyer_link(tenant_id,active_buyer_user_id);
ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT ck_crm_buyer_link_state;
ALTER TABLE crm.customer_buyer_link ADD CONSTRAINT ck_crm_buyer_link_state
 CHECK(status IN ('PENDING','CONFIRMED','DISPUTED','REVOKED'));
ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT ck_crm_buyer_link_confirmed;
ALTER TABLE crm.customer_buyer_link ADD CONSTRAINT ck_crm_buyer_link_confirmed CHECK(
 (status='PENDING' AND confirmed_at IS NULL)
 OR (status IN ('CONFIRMED','DISPUTED') AND confirmed_at IS NOT NULL)
 OR status='REVOKED');
ALTER TABLE crm.customer_buyer_link_event DROP CONSTRAINT ck_crm_buyer_link_event_action;
ALTER TABLE crm.customer_buyer_link_event ADD CONSTRAINT ck_crm_buyer_link_event_action
 CHECK(action_code IN ('PROPOSED','CONFIRMED','DISPUTED','REVOKED','DISPUTE_CLOSED'));
--rollback ALTER TABLE crm.customer_buyer_link_event DROP CONSTRAINT ck_crm_buyer_link_event_action;
--rollback ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT ck_crm_buyer_link_confirmed;
--rollback ALTER TABLE crm.customer_buyer_link DROP CONSTRAINT ck_crm_buyer_link_state;
--rollback DROP INDEX crm.uk_crm_buyer_active_buyer;
--rollback DROP INDEX crm.uk_crm_buyer_active_customer;
--rollback ALTER TABLE crm.customer_buyer_link DROP COLUMN active_buyer_user_id;
--rollback ALTER TABLE crm.customer_buyer_link DROP COLUMN active_customer_id;


--changeset rm:0106-crm-r22c-dispute-adjudication
-- Independent tenant reviewer must record a structured, non-PII reason code.
CREATE TABLE crm.customer_buyer_dispute_decision (
 tenant_id UUID NOT NULL,
 link_id UUID NOT NULL,
 reviewer_user_id UUID NOT NULL,
 reason_code VARCHAR(32) NOT NULL,
 decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_crm_buyer_dispute_decision PRIMARY KEY(tenant_id,link_id),
 CONSTRAINT fk_crm_buyer_dispute_decision_link FOREIGN KEY(tenant_id,link_id)
   REFERENCES crm.customer_buyer_link(tenant_id,id),
 CONSTRAINT fk_crm_buyer_dispute_decision_reviewer FOREIGN KEY(reviewer_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_crm_buyer_dispute_reason CHECK(reason_code IN
   ('IDENTITY_MISMATCH','BUYER_DENIED','INSUFFICIENT_EVIDENCE'))
);
--rollback DROP TABLE crm.customer_buyer_dispute_decision;

--changeset rm:0107-crm-r22c-adjudication-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_crm_r22c_adjudication_immutable
 BEFORE UPDATE OR DELETE ON crm.customer_buyer_dispute_decision
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_crm_r22c_adjudication_immutable ON crm.customer_buyer_dispute_decision;


--changeset rm:0108-iam-global-account-deletion-audit
-- Keep durable tombstone and non-PII provenance, not user profile or email plaintext.
CREATE TABLE public.iam_account_deletion_event (
 id UUID NOT NULL PRIMARY KEY,
 actor_user_id UUID NOT NULL,
 target_user_id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_iam_deletion_actor FOREIGN KEY(actor_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_iam_deletion_target FOREIGN KEY(target_user_id) REFERENCES public.iam_user_account(id),
 CONSTRAINT uk_iam_deletion_target UNIQUE(target_user_id),
 CONSTRAINT uk_iam_deletion_request UNIQUE(actor_user_id,request_key)
);
--rollback DROP TABLE public.iam_account_deletion_event;

--changeset rm:0109-iam-global-account-deletion-audit-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_iam_account_deletion_event_immutable
 BEFORE UPDATE OR DELETE ON public.iam_account_deletion_event
 FOR EACH ROW EXECUTE FUNCTION public.iam_reject_account_status_event_mutation();
--rollback DROP TRIGGER trg_iam_account_deletion_event_immutable ON public.iam_account_deletion_event;


--changeset rm:0110-iam-deletion-oidc-revocation-outbox
-- Transactional local intent only. External provider revocation must be
-- acknowledged by a separately qualified consumer; never claim success here.
CREATE TABLE public.iam_deletion_oidc_revocation_outbox (
 deletion_event_id UUID NOT NULL PRIMARY KEY,
 target_user_id UUID NOT NULL,
 provider_subject_envelope BYTEA NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 acknowledged_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT fk_iam_revocation_deletion_event FOREIGN KEY(deletion_event_id)
   REFERENCES public.iam_account_deletion_event(id),
 CONSTRAINT fk_iam_revocation_target FOREIGN KEY(target_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_iam_revocation_state CHECK(
  (state='PENDING' AND acknowledged_at IS NULL)
  OR (state='ACKNOWLEDGED' AND acknowledged_at IS NOT NULL))
);
--rollback DROP TABLE public.iam_deletion_oidc_revocation_outbox;


--changeset rm:0115-qr08-r0a-packaging-catalog
-- SALES packaging is a versioned service; it does NOT create an inventory SKU.
CREATE TABLE crm.sales_packaging_catalog_head (
 tenant_id UUID NOT NULL, service_code VARCHAR(40) NOT NULL,
 current_version INTEGER NOT NULL, active BOOLEAN NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT pk_packaging_catalog_head PRIMARY KEY(tenant_id,service_code),
 CONSTRAINT fk_packaging_head_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT ck_packaging_head_version CHECK(current_version>0)
);
CREATE TABLE crm.sales_packaging_catalog_version (
 tenant_id UUID NOT NULL, service_code VARCHAR(40) NOT NULL,
 version INTEGER NOT NULL, display_name VARCHAR(160) NOT NULL,
 unit_price NUMERIC(19,4) NOT NULL, currency CHAR(3) NOT NULL,
 tax_rate NUMERIC(7,4) NOT NULL, policy_version VARCHAR(40) NOT NULL,
 published_by UUID NOT NULL, published_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_packaging_catalog_version PRIMARY KEY(tenant_id,service_code,version),
 CONSTRAINT fk_packaging_catalog_head FOREIGN KEY(tenant_id,service_code)
  REFERENCES crm.sales_packaging_catalog_head(tenant_id,service_code),
 CONSTRAINT fk_packaging_catalog_actor FOREIGN KEY(published_by)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_packaging_price CHECK(unit_price>=0),
 CONSTRAINT ck_packaging_tax CHECK(tax_rate>=0 AND tax_rate<=1),
 CONSTRAINT ck_packaging_version CHECK(version>0)
);
--rollback DROP TABLE crm.sales_packaging_catalog_version;
--rollback DROP TABLE crm.sales_packaging_catalog_head;

--changeset rm:0116-qr08-r0a-packaging-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_qr08_packaging_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_packaging_catalog_version
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_qr08_packaging_immutable ON crm.sales_packaging_catalog_version;


--changeset rm:0117-qr08-r0b-order-packaging-service
-- One SALES order and immutable commercial snapshot; no inventory effects in R0.
CREATE TABLE crm.sales_order_service_line (
 tenant_id UUID NOT NULL, id UUID NOT NULL, order_id UUID NOT NULL,
 service_code VARCHAR(40) NOT NULL, catalog_version INTEGER NOT NULL,
 name_snapshot VARCHAR(160) NOT NULL, price_snapshot NUMERIC(19,4) NOT NULL,
 currency_snapshot CHAR(3) NOT NULL, tax_rate_snapshot NUMERIC(7,4) NOT NULL,
 policy_snapshot VARCHAR(40) NOT NULL, warehouse_id UUID,
 status VARCHAR(16) NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 offered_by UUID NOT NULL, buyer_user_id UUID NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, decided_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT pk_sales_order_service_line PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_sales_order_packaging_one_service UNIQUE(tenant_id,order_id,service_code),
 CONSTRAINT fk_sales_order_packaging_order FOREIGN KEY(tenant_id,order_id)
  REFERENCES crm.sales_order(tenant_id,id),
 CONSTRAINT fk_sales_order_packaging_catalog FOREIGN KEY(tenant_id,service_code,catalog_version)
  REFERENCES crm.sales_packaging_catalog_version(tenant_id,service_code,version),
 CONSTRAINT fk_sales_order_packaging_staff FOREIGN KEY(offered_by)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_sales_order_packaging_buyer FOREIGN KEY(buyer_user_id)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_sales_order_packaging_price CHECK(price_snapshot>=0),
 CONSTRAINT ck_sales_order_packaging_tax CHECK(tax_rate_snapshot>=0 AND tax_rate_snapshot<=1),
 CONSTRAINT ck_sales_order_packaging_status CHECK(status IN
  ('OFFERED','ACCEPTED','REJECTED','CANCELLED')),
 CONSTRAINT ck_sales_order_packaging_decided CHECK(
  (status='OFFERED' AND decided_at IS NULL)
  OR (status<>'OFFERED' AND decided_at IS NOT NULL))
);
CREATE TABLE crm.sales_order_service_event (
 tenant_id UUID NOT NULL, id UUID NOT NULL, service_line_id UUID NOT NULL,
 actor_user_id UUID NOT NULL, event_type VARCHAR(16) NOT NULL,
 to_state VARCHAR(16) NOT NULL, revision BIGINT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sales_order_service_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_sales_order_service_event_rev UNIQUE(tenant_id,service_line_id,revision),
 CONSTRAINT fk_sales_order_service_event_line FOREIGN KEY(tenant_id,service_line_id)
  REFERENCES crm.sales_order_service_line(tenant_id,id),
 CONSTRAINT fk_sales_order_service_event_actor FOREIGN KEY(actor_user_id)
  REFERENCES public.iam_user_account(id)
);
CREATE TABLE crm.sales_order_service_receipt (
 tenant_id UUID NOT NULL, request_key VARCHAR(100) NOT NULL,
 actor_user_id UUID NOT NULL, action_code VARCHAR(16) NOT NULL,
 request_sha256 VARCHAR(64) NOT NULL, service_line_id UUID NOT NULL,
 result_state VARCHAR(16) NOT NULL, result_revision BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sales_order_service_receipt PRIMARY KEY(tenant_id,request_key),
 CONSTRAINT fk_sales_order_service_receipt_line FOREIGN KEY(tenant_id,service_line_id)
  REFERENCES crm.sales_order_service_line(tenant_id,id)
);
--rollback DROP TABLE crm.sales_order_service_receipt;
--rollback DROP TABLE crm.sales_order_service_event;
--rollback DROP TABLE crm.sales_order_service_line;

--changeset rm:0118-qr08-r0b-immutable-evidence dbms:postgresql splitStatements:false
CREATE TRIGGER trg_qr08_order_service_event_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_order_service_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
CREATE TRIGGER trg_qr08_order_service_receipt_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_order_service_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_qr08_order_service_receipt_immutable ON crm.sales_order_service_receipt;
--rollback DROP TRIGGER trg_qr08_order_service_event_immutable ON crm.sales_order_service_event;

--changeset rm:0119-qr08-r0b-snapshot-fence dbms:postgresql splitStatements:false
CREATE OR REPLACE FUNCTION crm.reject_packaging_snapshot_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (OLD.tenant_id,OLD.id,OLD.order_id,OLD.service_code,OLD.catalog_version,
     OLD.name_snapshot,OLD.price_snapshot,OLD.currency_snapshot,OLD.tax_rate_snapshot,
     OLD.policy_snapshot,OLD.warehouse_id,OLD.offered_by,OLD.buyer_user_id,OLD.created_at)
    IS DISTINCT FROM
    (NEW.tenant_id,NEW.id,NEW.order_id,NEW.service_code,NEW.catalog_version,
     NEW.name_snapshot,NEW.price_snapshot,NEW.currency_snapshot,NEW.tax_rate_snapshot,
     NEW.policy_snapshot,NEW.warehouse_id,NEW.offered_by,NEW.buyer_user_id,NEW.created_at)
 THEN RAISE EXCEPTION 'PACKAGING_SNAPSHOT_IMMUTABLE'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_qr08_order_service_snapshot_fence
 BEFORE UPDATE ON crm.sales_order_service_line
 FOR EACH ROW EXECUTE FUNCTION crm.reject_packaging_snapshot_mutation();
--rollback DROP TRIGGER trg_qr08_order_service_snapshot_fence ON crm.sales_order_service_line;
--rollback DROP FUNCTION crm.reject_packaging_snapshot_mutation();


--changeset rm:0120-iam-oidc-revocation-lease-fencing dbms:postgresql
-- At-least-once delivery: attempt sequence is a fencing token for crash recovery.
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
 ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE,
 ADD COLUMN lease_owner UUID,
 ADD COLUMN leased_until TIMESTAMP WITH TIME ZONE,
 ADD COLUMN last_failure_code VARCHAR(32);
UPDATE public.iam_deletion_oidc_revocation_outbox
 SET next_attempt_at=created_at WHERE next_attempt_at IS NULL;
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ALTER COLUMN next_attempt_at SET NOT NULL;
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD CONSTRAINT ck_iam_oidc_attempt CHECK(attempt_count>=0),
 ADD CONSTRAINT ck_iam_oidc_lease CHECK (
   (lease_owner IS NULL AND leased_until IS NULL)
    OR (lease_owner IS NOT NULL AND leased_until IS NOT NULL));
CREATE INDEX idx_iam_oidc_revocation_due
 ON public.iam_deletion_oidc_revocation_outbox(state,next_attempt_at,leased_until);
--rollback DROP INDEX public.idx_iam_oidc_revocation_due;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP CONSTRAINT ck_iam_oidc_lease;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP CONSTRAINT ck_iam_oidc_attempt;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN last_failure_code;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN leased_until;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN lease_owner;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN next_attempt_at;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN attempt_count;


--changeset rm:0120h2-iam-oidc-revocation-lease-compat dbms:h2
-- Dialect-only equivalent of PostgreSQL 0120, preserving the original SQL body
-- and checksum in existing PostgreSQL DATABASECHANGELOG records.
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD COLUMN lease_owner UUID;
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD COLUMN leased_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD COLUMN last_failure_code VARCHAR(32);
UPDATE public.iam_deletion_oidc_revocation_outbox
 SET next_attempt_at=created_at WHERE next_attempt_at IS NULL;
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ALTER COLUMN next_attempt_at SET NOT NULL;
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD CONSTRAINT ck_iam_oidc_attempt CHECK(attempt_count>=0);
ALTER TABLE public.iam_deletion_oidc_revocation_outbox
 ADD CONSTRAINT ck_iam_oidc_lease CHECK (
   (lease_owner IS NULL AND leased_until IS NULL)
    OR (lease_owner IS NOT NULL AND leased_until IS NOT NULL));
CREATE INDEX idx_iam_oidc_revocation_due
 ON public.iam_deletion_oidc_revocation_outbox(state,next_attempt_at,leased_until);
--rollback DROP INDEX public.idx_iam_oidc_revocation_due;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP CONSTRAINT ck_iam_oidc_lease;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP CONSTRAINT ck_iam_oidc_attempt;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN last_failure_code;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN leased_until;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN lease_owner;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN next_attempt_at;
--rollback ALTER TABLE public.iam_deletion_oidc_revocation_outbox DROP COLUMN attempt_count;


--changeset rm:0121-fc40-fulfillment-channel-policy
-- DEC-FUL-01: one existing SALES order aggregate, durable fulfillment admission.
-- Historical orders retain STANDARD. QR checkout/accepted quote opt in explicitly.
ALTER TABLE crm.sales_order
 ADD COLUMN fulfillment_mode VARCHAR(24) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE crm.sales_order
 ADD CONSTRAINT ck_sales_order_fulfillment_mode
 CHECK (fulfillment_mode IN ('STANDARD','PICKUP_PROOF_REQUIRED'));
--rollback ALTER TABLE crm.sales_order DROP CONSTRAINT ck_sales_order_fulfillment_mode;
--rollback ALTER TABLE crm.sales_order DROP COLUMN fulfillment_mode;


--changeset rm:0122-fc40-fulfillment-mode-immutable dbms:postgresql splitStatements:false
-- The execution channel is immutable after order creation; a generic service
-- cannot downgrade an already proof-required order via direct SQL.
CREATE OR REPLACE FUNCTION crm.reject_sales_fulfillment_mode_change()
 RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.fulfillment_mode IS DISTINCT FROM OLD.fulfillment_mode THEN
  RAISE EXCEPTION 'SALES_FULFILLMENT_MODE_IMMUTABLE';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_sales_fulfillment_mode_immutable
 BEFORE UPDATE ON crm.sales_order
 FOR EACH ROW EXECUTE FUNCTION crm.reject_sales_fulfillment_mode_change();
--rollback DROP TRIGGER trg_sales_fulfillment_mode_immutable ON crm.sales_order;
--rollback DROP FUNCTION crm.reject_sales_fulfillment_mode_change();


--changeset rm:0123-cur-r1a-tenant-currency-foundation
-- No automatic conversion of historical offers, orders, valuations or quotes.
-- KZ public-facing price admission is a separate application rule; foreign
-- purchase purposes must not authorize foreign public prices.
CREATE TABLE public.tenant_currency_policy (
 tenant_id UUID NOT NULL PRIMARY KEY,
 accounting_currency CHAR(3) NOT NULL DEFAULT 'KZT',
 market_country CHAR(2) NOT NULL DEFAULT 'KZ',
 public_price_currency CHAR(3) NOT NULL DEFAULT 'KZT',
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 policy_version BIGINT NOT NULL DEFAULT 1,
 row_version BIGINT NOT NULL DEFAULT 0,
 approved_by UUID,
 approved_at TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_cur_policy_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT fk_cur_policy_actor FOREIGN KEY(approved_by) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_cur_policy_market CHECK(market_country='KZ' AND public_price_currency='KZT'),
 CONSTRAINT ck_cur_policy_accounting CHECK(accounting_currency='KZT'),
 CONSTRAINT ck_cur_policy_status CHECK(status IN ('ACTIVE','REVIEW_REQUIRED')),
 CONSTRAINT ck_cur_policy_version CHECK(policy_version>=1 AND row_version>=0),
 CONSTRAINT ck_cur_policy_approval CHECK((approved_by IS NULL AND approved_at IS NULL)
      OR (approved_by IS NOT NULL AND approved_at IS NOT NULL))
);
CREATE TABLE public.tenant_allowed_currency (
 tenant_id UUID NOT NULL,
 currency_code CHAR(3) NOT NULL,
 purpose VARCHAR(24) NOT NULL,
 state VARCHAR(12) NOT NULL DEFAULT 'ENABLED',
 row_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_cur_allowed PRIMARY KEY(tenant_id,currency_code,purpose),
 CONSTRAINT fk_cur_allowed_policy FOREIGN KEY(tenant_id)
    REFERENCES public.tenant_currency_policy(tenant_id),
 CONSTRAINT ck_cur_allowed_iso CHECK(length(trim(currency_code))=3
    AND upper(currency_code)=currency_code),
 CONSTRAINT ck_cur_allowed_purpose CHECK(purpose IN
    ('PURCHASE','ACCOUNTING','INVENTORY_VALUATION','SALES_INTERNAL','PUBLIC_PRICE')),
 CONSTRAINT ck_cur_allowed_state CHECK(state IN ('ENABLED','DISABLED')),
 CONSTRAINT ck_cur_allowed_row_version CHECK(row_version>=0),
 CONSTRAINT ck_cur_allowed_public CHECK(purpose<>'PUBLIC_PRICE' OR currency_code='KZT'),
 CONSTRAINT ck_cur_allowed_accounting CHECK(purpose<>'ACCOUNTING' OR currency_code='KZT')
);
CREATE INDEX idx_cur_allowed_purpose
 ON public.tenant_allowed_currency(tenant_id,purpose,state);
CREATE TABLE public.tenant_currency_policy_event (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 revision_no BIGINT NOT NULL,
 actor_user_id UUID NOT NULL,
 action_code VARCHAR(32) NOT NULL,
 currency_code CHAR(3) NOT NULL,
 purpose VARCHAR(24) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_cur_policy_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_cur_policy_event_rev UNIQUE(tenant_id,revision_no),
 CONSTRAINT fk_cur_policy_event_tenant FOREIGN KEY(tenant_id)
   REFERENCES public.tenant_currency_policy(tenant_id),
 CONSTRAINT fk_cur_policy_event_actor FOREIGN KEY(actor_user_id)
   REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_cur_event_revision CHECK(revision_no>=1),
 CONSTRAINT ck_cur_event_action CHECK(action_code IN
   ('INITIALIZED','CURRENCY_ENABLED','CURRENCY_DISABLED'))
);
--rollback DROP TABLE public.tenant_currency_policy_event;
--rollback DROP INDEX public.idx_cur_allowed_purpose;
--rollback DROP TABLE public.tenant_allowed_currency;
--rollback DROP TABLE public.tenant_currency_policy;

--changeset rm:0124-cur-r1a-currency-event-immutable dbms:postgresql splitStatements:false
-- Currency approval history is append-only; not a new financial ledger.
CREATE OR REPLACE FUNCTION public.reject_currency_policy_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'TENANT_CURRENCY_POLICY_EVENT_IMMUTABLE';
END;
$$;
CREATE TRIGGER trg_cur_policy_event_immutable
 BEFORE UPDATE OR DELETE ON public.tenant_currency_policy_event
 FOR EACH ROW EXECUTE FUNCTION public.reject_currency_policy_event_mutation();
--rollback DROP TRIGGER trg_cur_policy_event_immutable ON public.tenant_currency_policy_event;
--rollback DROP FUNCTION public.reject_currency_policy_event_mutation();


--changeset rm:0125-fx-r1a-rate-snapshot-and-refresh-history
-- Synthetic-only initial slice. No external network, no SKU prices or ledger changes.
-- Real NBRK corrections later require separate qualified revision policy.
CREATE TABLE crm.fx_rate_provider_lock (
 provider_code VARCHAR(24) NOT NULL PRIMARY KEY,
 CONSTRAINT ck_fx_r1a_provider CHECK(provider_code='MOCK')
);
INSERT INTO crm.fx_rate_provider_lock(provider_code) VALUES ('MOCK');

CREATE TABLE crm.fx_official_rate_snapshot (
 id UUID NOT NULL PRIMARY KEY,
 provider_code VARCHAR(24) NOT NULL,
 base_currency CHAR(3) NOT NULL,
 quote_currency CHAR(3) NOT NULL,
 nominal NUMERIC(19,6) NOT NULL,
 rate NUMERIC(24,10) NOT NULL,
 valid_date DATE NOT NULL,
 source_revision INTEGER NOT NULL DEFAULT 1,
 source_payload_sha256 CHAR(64) NOT NULL,
 synthetic BOOLEAN NOT NULL,
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_fx_r1a_snapshot_provider FOREIGN KEY(provider_code)
   REFERENCES crm.fx_rate_provider_lock(provider_code),
 CONSTRAINT uq_fx_r1a_rate_identity UNIQUE(provider_code,base_currency,quote_currency,valid_date,source_revision),
 CONSTRAINT ck_fx_r1a_base CHECK(base_currency IN ('USD','CNY','EUR')),
 CONSTRAINT ck_fx_r1a_quote CHECK(quote_currency='KZT'),
 CONSTRAINT ck_fx_r1a_positive CHECK(nominal>0 AND rate>0),
 CONSTRAINT ck_fx_r1a_revision CHECK(source_revision=1),
 CONSTRAINT ck_fx_r1a_mock CHECK(provider_code='MOCK' AND synthetic=TRUE),
 CONSTRAINT ck_fx_r1a_sha CHECK(length(source_payload_sha256)=64)
);
CREATE INDEX idx_fx_r1a_valid_date ON crm.fx_official_rate_snapshot(base_currency,valid_date);

CREATE TABLE crm.fx_rate_import_run (
 id UUID NOT NULL PRIMARY KEY,
 provider_code VARCHAR(24) NOT NULL,
 requested_date DATE NOT NULL,
 imported_count INTEGER NOT NULL,
 replayed_count INTEGER NOT NULL,
 source_count INTEGER NOT NULL,
 synthetic BOOLEAN NOT NULL,
 completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_fx_r1a_run_provider FOREIGN KEY(provider_code)
   REFERENCES crm.fx_rate_provider_lock(provider_code),
 CONSTRAINT ck_fx_r1a_run_counts CHECK(imported_count>=0 AND replayed_count>=0
   AND source_count>0 AND imported_count+replayed_count=source_count),
 CONSTRAINT ck_fx_r1a_run_mock CHECK(provider_code='MOCK' AND synthetic=TRUE)
);
CREATE INDEX idx_fx_r1a_run_history ON crm.fx_rate_import_run(provider_code,completed_at);
--rollback DROP TABLE crm.fx_rate_import_run;
--rollback DROP INDEX crm.idx_fx_r1a_valid_date;
--rollback DROP TABLE crm.fx_official_rate_snapshot;
--rollback DROP TABLE crm.fx_rate_provider_lock;

--changeset rm:0126-fx-r1a-immutable-snapshots dbms:postgresql splitStatements:false
CREATE OR REPLACE FUNCTION crm.fx_r1a_reject_evidence_mutation()
 RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'FX_R1A_IMMUTABLE_EVIDENCE';
END;
$$;
CREATE TRIGGER trg_fx_r1a_snapshot_immutable
 BEFORE UPDATE OR DELETE ON crm.fx_official_rate_snapshot
 FOR EACH ROW EXECUTE FUNCTION crm.fx_r1a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_r1a_import_run_immutable
 BEFORE UPDATE OR DELETE ON crm.fx_rate_import_run
 FOR EACH ROW EXECUTE FUNCTION crm.fx_r1a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_fx_r1a_import_run_immutable ON crm.fx_rate_import_run;
--rollback DROP TRIGGER trg_fx_r1a_snapshot_immutable ON crm.fx_official_rate_snapshot;
--rollback DROP FUNCTION crm.fx_r1a_reject_evidence_mutation();


--changeset rm:0127-fx-r1a3-trusted-nbrk-revisions
-- Extend, not replace, R1A mock evidence. Never rewrite existing rows.
ALTER TABLE crm.fx_rate_provider_lock DROP CONSTRAINT ck_fx_r1a_provider;
ALTER TABLE crm.fx_rate_provider_lock ADD CONSTRAINT ck_fx_r1a_provider
 CHECK (provider_code IN ('MOCK','NBRK'));
INSERT INTO crm.fx_rate_provider_lock(provider_code) VALUES ('NBRK');

ALTER TABLE crm.fx_official_rate_snapshot DROP CONSTRAINT ck_fx_r1a_base;
ALTER TABLE crm.fx_official_rate_snapshot DROP CONSTRAINT ck_fx_r1a_revision;
ALTER TABLE crm.fx_official_rate_snapshot ADD CONSTRAINT ck_fx_r1a_revision
 CHECK (source_revision >= 1);
ALTER TABLE crm.fx_official_rate_snapshot DROP CONSTRAINT ck_fx_r1a_mock;
ALTER TABLE crm.fx_official_rate_snapshot ADD CONSTRAINT ck_fx_r1a_source_integrity
 CHECK ((provider_code='MOCK' AND synthetic=TRUE AND source_revision=1)
     OR (provider_code='NBRK' AND synthetic=FALSE));
ALTER TABLE crm.fx_rate_import_run DROP CONSTRAINT ck_fx_r1a_run_mock;
ALTER TABLE crm.fx_rate_import_run ADD CONSTRAINT ck_fx_r1a_run_source_integrity
 CHECK ((provider_code='MOCK' AND synthetic=TRUE)
     OR (provider_code='NBRK' AND synthetic=FALSE));
ALTER TABLE crm.fx_rate_import_run ADD COLUMN raw_payload_sha256 CHAR(64);
ALTER TABLE crm.fx_rate_import_run ADD COLUMN source_endpoint VARCHAR(120);
ALTER TABLE crm.fx_rate_import_run ADD CONSTRAINT ck_fx_r1a3_raw_sha
 CHECK (raw_payload_sha256 IS NULL OR length(raw_payload_sha256)=64);
CREATE INDEX idx_fx_r1a3_source_revision
 ON crm.fx_official_rate_snapshot(provider_code,base_currency,valid_date,source_revision);
--rollback DROP INDEX crm.idx_fx_r1a3_source_revision;
--rollback ALTER TABLE crm.fx_rate_import_run DROP CONSTRAINT ck_fx_r1a3_raw_sha;
--rollback ALTER TABLE crm.fx_rate_import_run DROP COLUMN source_endpoint;
--rollback ALTER TABLE crm.fx_rate_import_run DROP COLUMN raw_payload_sha256;
--rollback ALTER TABLE crm.fx_rate_import_run DROP CONSTRAINT ck_fx_r1a_run_source_integrity;
--rollback ALTER TABLE crm.fx_rate_import_run ADD CONSTRAINT ck_fx_r1a_run_mock CHECK(provider_code='MOCK' AND synthetic=TRUE);
--rollback ALTER TABLE crm.fx_official_rate_snapshot DROP CONSTRAINT ck_fx_r1a_source_integrity;
--rollback ALTER TABLE crm.fx_official_rate_snapshot ADD CONSTRAINT ck_fx_r1a_mock CHECK(provider_code='MOCK' AND synthetic=TRUE);
--rollback ALTER TABLE crm.fx_official_rate_snapshot DROP CONSTRAINT ck_fx_r1a_revision;
--rollback ALTER TABLE crm.fx_official_rate_snapshot ADD CONSTRAINT ck_fx_r1a_revision CHECK(source_revision=1);
--rollback ALTER TABLE crm.fx_official_rate_snapshot ADD CONSTRAINT ck_fx_r1a_base CHECK(base_currency IN ('USD','CNY','EUR'));
--rollback DELETE FROM crm.fx_rate_provider_lock WHERE provider_code='NBRK';
--rollback ALTER TABLE crm.fx_rate_provider_lock DROP CONSTRAINT ck_fx_r1a_provider;
--rollback ALTER TABLE crm.fx_rate_provider_lock ADD CONSTRAINT ck_fx_r1a_provider CHECK(provider_code='MOCK');


--changeset rm:0128-fx-r1a3-revision-source-run-link
-- Each NBRK rate revision points to the immutable import run which produced it.
ALTER TABLE crm.fx_official_rate_snapshot ADD COLUMN source_import_run_id UUID;
ALTER TABLE crm.fx_official_rate_snapshot ADD CONSTRAINT fk_fx_r1a3_revision_import_run
 FOREIGN KEY (source_import_run_id) REFERENCES crm.fx_rate_import_run(id);
ALTER TABLE crm.fx_official_rate_snapshot ADD CONSTRAINT ck_fx_r1a3_revision_provenance
 CHECK (provider_code='MOCK' OR source_import_run_id IS NOT NULL);
CREATE INDEX idx_fx_r1a3_revision_import_run
 ON crm.fx_official_rate_snapshot(source_import_run_id);
--rollback DROP INDEX crm.idx_fx_r1a3_revision_import_run;
--rollback ALTER TABLE crm.fx_official_rate_snapshot DROP CONSTRAINT ck_fx_r1a3_revision_provenance;
--rollback ALTER TABLE crm.fx_official_rate_snapshot DROP CONSTRAINT fk_fx_r1a3_revision_import_run;
--rollback ALTER TABLE crm.fx_official_rate_snapshot DROP COLUMN source_import_run_id;


--changeset rm:0129-cur-r2a-immutable-sku-fx-approval-evidence
-- Versioned seller-approved anchor; NO second public price owner.
CREATE TABLE crm.sku_fx_policy_version (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 offer_id UUID NOT NULL,
 revision BIGINT NOT NULL,
 anchor_currency CHAR(3) NOT NULL,
 anchor_amount NUMERIC(19,4) NOT NULL,
 buffer_pct NUMERIC(8,4) NOT NULL,
 markup_pct NUMERIC(8,4) NOT NULL,
 rounding_step_kzt NUMERIC(19,4) NOT NULL,
 approved_by UUID NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sku_fx_policy_version PRIMARY KEY(tenant_id,id),
 CONSTRAINT uk_sku_fx_policy_version_rev UNIQUE(tenant_id,offer_id,revision),
 CONSTRAINT fk_sku_fx_policy_offer FOREIGN KEY(tenant_id,offer_id)
    REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_sku_fx_policy_actor FOREIGN KEY(approved_by)
    REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_sku_fx_policy_revision CHECK(revision>0),
 CONSTRAINT ck_sku_fx_policy_currency CHECK(anchor_currency IN ('USD','EUR','CNY')),
 CONSTRAINT ck_sku_fx_policy_amount CHECK(anchor_amount>0 AND rounding_step_kzt>0),
 CONSTRAINT ck_sku_fx_policy_pct CHECK(buffer_pct>=0 AND buffer_pct<=1000
    AND markup_pct>=0 AND markup_pct<=1000)
);
CREATE TABLE crm.sku_fx_calculation (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 offer_id UUID NOT NULL,
 policy_id UUID NOT NULL,
 policy_revision BIGINT NOT NULL,
 rate_snapshot_id UUID NOT NULL,
 offer_row_version BIGINT NOT NULL,
 current_price_kzt NUMERIC(19,4) NOT NULL,
 candidate_price_kzt NUMERIC(19,4) NOT NULL,
 anchor_currency CHAR(3) NOT NULL,
 anchor_amount NUMERIC(19,4) NOT NULL,
 nominal NUMERIC(19,6) NOT NULL,
 rate_kzt NUMERIC(24,10) NOT NULL,
 valid_date DATE NOT NULL,
 source_revision INTEGER NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 buffer_pct NUMERIC(8,4) NOT NULL,
 markup_pct NUMERIC(8,4) NOT NULL,
 rounding_step_kzt NUMERIC(19,4) NOT NULL,
 formula_version VARCHAR(24) NOT NULL,
 request_sha256 CHAR(64) NOT NULL,
 calculated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sku_fx_calculation PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_sku_fx_calculation_request UNIQUE(tenant_id,request_sha256),
 CONSTRAINT fk_sku_fx_calculation_offer FOREIGN KEY(tenant_id,offer_id)
    REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_sku_fx_calculation_policy FOREIGN KEY(tenant_id,policy_id)
    REFERENCES crm.sku_fx_policy_version(tenant_id,id),
 CONSTRAINT fk_sku_fx_calculation_rate FOREIGN KEY(rate_snapshot_id)
    REFERENCES crm.fx_official_rate_snapshot(id),
 CONSTRAINT ck_sku_fx_calculation_positive CHECK(current_price_kzt>0
    AND candidate_price_kzt>0 AND anchor_amount>0 AND nominal>0 AND rate_kzt>0),
 CONSTRAINT ck_sku_fx_calculation_version CHECK(offer_row_version>=0
    AND policy_revision>0 AND source_revision>0),
 CONSTRAINT ck_sku_fx_calculation_formula CHECK(formula_version='SKU_FX_R2A_V1'),
 CONSTRAINT ck_sku_fx_calculation_source_sha CHECK(length(source_sha256)=64)
);
CREATE TABLE crm.sku_fx_seller_decision (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 calculation_id UUID NOT NULL,
 offer_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 action VARCHAR(16) NOT NULL,
 price_revision_id UUID,
 decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sku_fx_seller_decision PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_sku_fx_decision_calculation UNIQUE(tenant_id,calculation_id),
 CONSTRAINT uq_sku_fx_decision_request UNIQUE(tenant_id,request_key),
 CONSTRAINT fk_sku_fx_decision_calc FOREIGN KEY(tenant_id,calculation_id)
    REFERENCES crm.sku_fx_calculation(tenant_id,id),
 CONSTRAINT fk_sku_fx_decision_offer FOREIGN KEY(tenant_id,offer_id)
    REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_sku_fx_decision_actor FOREIGN KEY(actor_user_id)
    REFERENCES public.iam_user_account(id),
 CONSTRAINT fk_sku_fx_decision_price FOREIGN KEY(tenant_id,price_revision_id)
    REFERENCES crm.store_offer_price_revision(tenant_id,id),
 CONSTRAINT ck_sku_fx_decision_action CHECK(action IN ('APPROVED','REJECTED')),
 CONSTRAINT ck_sku_fx_decision_price CHECK(
    (action='APPROVED' AND price_revision_id IS NOT NULL)
    OR (action='REJECTED' AND price_revision_id IS NULL))
);
CREATE INDEX idx_sku_fx_policy_offer ON crm.sku_fx_policy_version(tenant_id,offer_id,revision DESC);
CREATE INDEX idx_sku_fx_calculation_offer ON crm.sku_fx_calculation(tenant_id,offer_id,calculated_at DESC);
--rollback DROP INDEX crm.idx_sku_fx_calculation_offer;
--rollback DROP INDEX crm.idx_sku_fx_policy_offer;
--rollback DROP TABLE crm.sku_fx_seller_decision;
--rollback DROP TABLE crm.sku_fx_calculation;
--rollback DROP TABLE crm.sku_fx_policy_version;

--changeset rm:0130-cur-r2a-evidence-immutability dbms:postgresql splitStatements:false
-- All approval evidence append-only; existing commercial price history already immutable.
CREATE OR REPLACE FUNCTION crm.cur_r2a_reject_evidence_mutation()
 RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'CUR_R2A_IMMUTABLE_EVIDENCE';
END;
$$;
CREATE TRIGGER trg_cur_r2a_policy_immutable
 BEFORE UPDATE OR DELETE ON crm.sku_fx_policy_version
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_cur_r2a_calc_immutable
 BEFORE UPDATE OR DELETE ON crm.sku_fx_calculation
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_cur_r2a_decision_immutable
 BEFORE UPDATE OR DELETE ON crm.sku_fx_seller_decision
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_cur_r2a_decision_immutable ON crm.sku_fx_seller_decision;
--rollback DROP TRIGGER trg_cur_r2a_calc_immutable ON crm.sku_fx_calculation;
--rollback DROP TRIGGER trg_cur_r2a_policy_immutable ON crm.sku_fx_policy_version;
--rollback DROP FUNCTION crm.cur_r2a_reject_evidence_mutation();


--changeset rm:0131-cur-r2a-cross-reference-context-constraints
-- Defend provenance against cross-policy-revision and wrong-offer references.
ALTER TABLE crm.sku_fx_policy_version
 ADD CONSTRAINT uq_sku_fx_policy_revision_ref UNIQUE(tenant_id,id,revision);
ALTER TABLE crm.sku_fx_calculation
 ADD CONSTRAINT fk_sku_fx_calc_policy_revision
 FOREIGN KEY (tenant_id,policy_id,policy_revision)
 REFERENCES crm.sku_fx_policy_version(tenant_id,id,revision);
ALTER TABLE crm.sku_fx_calculation
 ADD CONSTRAINT uq_sku_fx_calc_offer_ref UNIQUE(tenant_id,id,offer_id);
ALTER TABLE crm.sku_fx_seller_decision
 ADD CONSTRAINT fk_sku_fx_decision_calc_offer
 FOREIGN KEY (tenant_id,calculation_id,offer_id)
 REFERENCES crm.sku_fx_calculation(tenant_id,id,offer_id);
ALTER TABLE crm.store_offer_price_revision
 ADD CONSTRAINT uq_sku_fx_price_offer_ref UNIQUE(tenant_id,id,offer_id);
ALTER TABLE crm.sku_fx_seller_decision
 ADD CONSTRAINT fk_sku_fx_decision_price_offer
 FOREIGN KEY (tenant_id,price_revision_id,offer_id)
 REFERENCES crm.store_offer_price_revision(tenant_id,id,offer_id);
--rollback ALTER TABLE crm.sku_fx_seller_decision DROP CONSTRAINT fk_sku_fx_decision_price_offer;
--rollback ALTER TABLE crm.store_offer_price_revision DROP CONSTRAINT uq_sku_fx_price_offer_ref;
--rollback ALTER TABLE crm.sku_fx_seller_decision DROP CONSTRAINT fk_sku_fx_decision_calc_offer;
--rollback ALTER TABLE crm.sku_fx_calculation DROP CONSTRAINT uq_sku_fx_calc_offer_ref;
--rollback ALTER TABLE crm.sku_fx_calculation DROP CONSTRAINT fk_sku_fx_calc_policy_revision;
--rollback ALTER TABLE crm.sku_fx_policy_version DROP CONSTRAINT uq_sku_fx_policy_revision_ref;


--changeset rm:0132-cur-r2b-immutable-seller-fx-guardrails
-- Opt-in tenant/offer seller guardrail revisions; existing R2A evidence untouched.
CREATE TABLE crm.sku_fx_guardrail_version (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 offer_id UUID NOT NULL,
 revision BIGINT NOT NULL,
 max_increase_pct NUMERIC(8,4) NOT NULL,
 max_decrease_pct NUMERIC(8,4) NOT NULL,
 min_price_kzt NUMERIC(19,4) NOT NULL,
 max_price_kzt NUMERIC(19,4) NOT NULL,
 max_rate_calendar_days INTEGER NOT NULL,
 calculation_ttl_seconds INTEGER NOT NULL,
 approved_by UUID NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_sku_fx_guardrail PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_sku_fx_guardrail_offer_revision UNIQUE(tenant_id,offer_id,revision),
 CONSTRAINT uq_sku_fx_guardrail_ref UNIQUE(tenant_id,id,revision),
 CONSTRAINT fk_sku_fx_guardrail_offer FOREIGN KEY(tenant_id,offer_id)
  REFERENCES crm.store_offer(tenant_id,id),
 CONSTRAINT fk_sku_fx_guardrail_actor FOREIGN KEY(approved_by)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_sku_fx_guardrail_values CHECK (
  revision>0 AND max_increase_pct>=0 AND max_increase_pct<=1000
  AND max_decrease_pct>=0 AND max_decrease_pct<=100
  AND min_price_kzt>0 AND max_price_kzt>=min_price_kzt
  AND max_rate_calendar_days BETWEEN 0 AND 365
  AND calculation_ttl_seconds BETWEEN 1 AND 604800)
);
ALTER TABLE crm.sku_fx_calculation ADD COLUMN guardrail_id UUID;
ALTER TABLE crm.sku_fx_calculation ADD COLUMN guardrail_revision BIGINT;
ALTER TABLE crm.sku_fx_calculation ADD CONSTRAINT ck_sku_fx_calc_guardrail_ref
 CHECK ((guardrail_id IS NULL AND guardrail_revision IS NULL)
     OR (guardrail_id IS NOT NULL AND guardrail_revision>0));
ALTER TABLE crm.sku_fx_calculation ADD CONSTRAINT fk_sku_fx_calc_guardrail
 FOREIGN KEY (tenant_id,guardrail_id,guardrail_revision)
 REFERENCES crm.sku_fx_guardrail_version(tenant_id,id,revision);
--rollback ALTER TABLE crm.sku_fx_calculation DROP CONSTRAINT fk_sku_fx_calc_guardrail;
--rollback ALTER TABLE crm.sku_fx_calculation DROP CONSTRAINT ck_sku_fx_calc_guardrail_ref;
--rollback ALTER TABLE crm.sku_fx_calculation DROP COLUMN guardrail_revision;
--rollback ALTER TABLE crm.sku_fx_calculation DROP COLUMN guardrail_id;
--rollback DROP TABLE crm.sku_fx_guardrail_version;

--changeset rm:0133-cur-r2b-guardrail-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_cur_r2b_guardrail_immutable
 BEFORE UPDATE OR DELETE ON crm.sku_fx_guardrail_version
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_cur_r2b_guardrail_immutable ON crm.sku_fx_guardrail_version;


--changeset rm:0134-cur-r2b4-calendar-evidence-admission
-- Staged instrument-specific KASE evidence. Never infer missing trading days.
-- Opt-in only: the tenant gate defaults OFF, existing CUR-R2A tenants are unchanged.
CREATE TABLE crm.fx_calendar_source_evidence (
 id UUID NOT NULL PRIMARY KEY,
 source_url VARCHAR(500) NOT NULL,
 raw_sha256 CHAR(64) NOT NULL,
 source_byte_count INTEGER NOT NULL,
 verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
 verification_method VARCHAR(32) NOT NULL,
 CONSTRAINT uq_fx_calendar_source_sha UNIQUE(source_url,raw_sha256),
 CONSTRAINT ck_fx_calendar_source_sha CHECK(length(raw_sha256)=64),
 CONSTRAINT ck_fx_calendar_source_size CHECK(source_byte_count BETWEEN 1 AND 1048576),
 CONSTRAINT ck_fx_calendar_source_method CHECK(verification_method='SOURCE_SHA_VERIFIED')
);
CREATE TABLE crm.fx_calendar_day_evidence (
 source_id UUID NOT NULL,
 instrument VARCHAR(16) NOT NULL,
 trading_date DATE NOT NULL,
 trading_status VARCHAR(8) NOT NULL,
 CONSTRAINT pk_fx_calendar_day_evidence PRIMARY KEY(source_id,instrument,trading_date),
 CONSTRAINT fk_fx_calendar_day_source FOREIGN KEY(source_id)
  REFERENCES crm.fx_calendar_source_evidence(id),
 CONSTRAINT ck_fx_calendar_instrument CHECK(instrument IN ('USD/KZT','EUR/KZT','CNY/KZT')),
 CONSTRAINT ck_fx_calendar_day_status CHECK(trading_status IN ('TRADING','CLOSED'))
);
CREATE INDEX idx_fx_calendar_day_lookup
 ON crm.fx_calendar_day_evidence(instrument,trading_date);
CREATE TABLE crm.fx_calendar_tenant_gate (
 tenant_id UUID NOT NULL PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 max_trading_days INTEGER NOT NULL,
 source_id UUID NOT NULL,
 approved_by UUID NOT NULL,
 approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_fx_calendar_gate_tenant FOREIGN KEY(tenant_id)
  REFERENCES public.iam_workspace(id),
 CONSTRAINT fk_fx_calendar_gate_source FOREIGN KEY(source_id)
  REFERENCES crm.fx_calendar_source_evidence(id),
 CONSTRAINT fk_fx_calendar_gate_actor FOREIGN KEY(approved_by)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_fx_calendar_gate_age CHECK(max_trading_days BETWEEN 0 AND 10)
);
--rollback DROP TABLE crm.fx_calendar_tenant_gate;
--rollback DROP INDEX crm.idx_fx_calendar_day_lookup;
--rollback DROP TABLE crm.fx_calendar_day_evidence;
--rollback DROP TABLE crm.fx_calendar_source_evidence;

--changeset rm:0135-cur-r2b4-calendar-evidence-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_cur_r2b4_calendar_source_immutable
 BEFORE UPDATE OR DELETE ON crm.fx_calendar_source_evidence
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_cur_r2b4_calendar_day_immutable
 BEFORE UPDATE OR DELETE ON crm.fx_calendar_day_evidence
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_cur_r2b4_calendar_day_immutable ON crm.fx_calendar_day_evidence;
--rollback DROP TRIGGER trg_cur_r2b4_calendar_source_immutable ON crm.fx_calendar_source_evidence;


--changeset rm:0136-cur-r2b4-calendar-source-serialization
-- Serialize source import/replay without changing immutable source/day records.
CREATE TABLE crm.fx_calendar_source_lock (
 provider_code VARCHAR(12) NOT NULL PRIMARY KEY,
 CONSTRAINT ck_fx_calendar_lock_provider CHECK(provider_code='KASE')
);
INSERT INTO crm.fx_calendar_source_lock(provider_code) VALUES ('KASE');
--rollback DROP TABLE crm.fx_calendar_source_lock;

--changeset rm:0137-cur-r2b4-seller-calendar-source-receipt
-- A newly approved seller decision captures the exact calendar source ID used.
-- Historical decisions remain valid with NULL; no backfill or evidence rewrite.
ALTER TABLE crm.sku_fx_seller_decision ADD COLUMN calendar_source_id UUID;
ALTER TABLE crm.sku_fx_seller_decision ADD CONSTRAINT fk_fx_decision_calendar_source
 FOREIGN KEY(calendar_source_id) REFERENCES crm.fx_calendar_source_evidence(id);
--rollback ALTER TABLE crm.sku_fx_seller_decision DROP CONSTRAINT fk_fx_decision_calendar_source;
--rollback ALTER TABLE crm.sku_fx_seller_decision DROP COLUMN calendar_source_id;


--changeset rm:0138-cur-r2b4-calendar-source-seal
-- One immutable manifest commits exact day set; source-day additions after seal forbidden.
CREATE TABLE crm.fx_calendar_source_seal (
 source_id UUID NOT NULL PRIMARY KEY,
 day_count INTEGER NOT NULL,
 days_sha256 CHAR(64) NOT NULL,
 sealed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_fx_calendar_seal_source FOREIGN KEY(source_id)
  REFERENCES crm.fx_calendar_source_evidence(id),
 CONSTRAINT ck_fx_calendar_seal_count CHECK(day_count BETWEEN 1 AND 366),
 CONSTRAINT ck_fx_calendar_seal_sha CHECK(length(days_sha256)=64)
);
--rollback DROP TABLE crm.fx_calendar_source_seal;

--changeset rm:0139-cur-r2b4-sealed-day-protection dbms:postgresql splitStatements:false
CREATE OR REPLACE FUNCTION crm.fx_calendar_reject_late_day_insert()
 RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM crm.fx_calendar_source_seal WHERE source_id=NEW.source_id) THEN
  RAISE EXCEPTION 'CUR_R2B_CALENDAR_SOURCE_SEALED';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_cur_r2b4_sealed_day_insert
 BEFORE INSERT ON crm.fx_calendar_day_evidence
 FOR EACH ROW EXECUTE FUNCTION crm.fx_calendar_reject_late_day_insert();
CREATE TRIGGER trg_cur_r2b4_calendar_seal_immutable
 BEFORE UPDATE OR DELETE ON crm.fx_calendar_source_seal
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_cur_r2b4_calendar_seal_immutable ON crm.fx_calendar_source_seal;
--rollback DROP TRIGGER trg_cur_r2b4_sealed_day_insert ON crm.fx_calendar_day_evidence;
--rollback DROP FUNCTION crm.fx_calendar_reject_late_day_insert();


--changeset rm:0140-task-r2-service-foundation
-- Extends the original tenant-owned task stub, no duplicate task aggregate.
ALTER TABLE crm.task_item ADD COLUMN title VARCHAR(180);
ALTER TABLE crm.task_item ADD COLUMN created_by UUID;
ALTER TABLE crm.task_item ADD COLUMN created_request_key VARCHAR(100);
ALTER TABLE crm.task_item ADD COLUMN request_sha256 CHAR(64);
ALTER TABLE crm.task_item ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm.task_item ADD CONSTRAINT fk_task_creator FOREIGN KEY(created_by)
 REFERENCES public.iam_user_account(id);
CREATE UNIQUE INDEX uq_task_create_request ON crm.task_item(tenant_id,created_request_key);
CREATE TABLE crm.task_event (
 tenant_id UUID NOT NULL, id UUID NOT NULL, task_id UUID NOT NULL,
 actor_user_id UUID NOT NULL, action_code VARCHAR(24) NOT NULL,
 from_state VARCHAR(20), to_state VARCHAR(20) NOT NULL,
 previous_version BIGINT, new_version BIGINT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_task_event PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_task_event_version UNIQUE(tenant_id,task_id,new_version),
 CONSTRAINT fk_task_event_task FOREIGN KEY(tenant_id,task_id)
  REFERENCES crm.task_item(tenant_id,id),
 CONSTRAINT fk_task_event_actor FOREIGN KEY(actor_user_id)
  REFERENCES public.iam_user_account(id)
);
CREATE INDEX idx_task_event_order ON crm.task_event(tenant_id,task_id,new_version);
CREATE TABLE crm.task_request_lock (bucket INTEGER NOT NULL PRIMARY KEY);
INSERT INTO crm.task_request_lock(bucket)
 SELECT bucket FROM crm.interaction_request_lock;
--rollback DROP TABLE crm.task_request_lock;
--rollback DROP TABLE crm.task_event;
--rollback DROP INDEX crm.uq_task_create_request;
--rollback ALTER TABLE crm.task_item DROP CONSTRAINT fk_task_creator;
--rollback ALTER TABLE crm.task_item DROP COLUMN updated_at;
--rollback ALTER TABLE crm.task_item DROP COLUMN request_sha256;
--rollback ALTER TABLE crm.task_item DROP COLUMN created_request_key;
--rollback ALTER TABLE crm.task_item DROP COLUMN created_by;
--rollback ALTER TABLE crm.task_item DROP COLUMN title;

--changeset rm:0141-task-r2-event-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_task_event_immutable
 BEFORE UPDATE OR DELETE ON crm.task_event
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_task_event_immutable ON crm.task_event;


--changeset rm:0142-task-r2-assignment-evidence
-- Preserve immutable before/after assignee identity without changing existing event rows.
ALTER TABLE crm.task_event ADD COLUMN previous_assignee_user_id UUID;
ALTER TABLE crm.task_event ADD COLUMN next_assignee_user_id UUID;
ALTER TABLE crm.task_event ADD CONSTRAINT fk_task_event_prev_assignee
 FOREIGN KEY(previous_assignee_user_id) REFERENCES public.iam_user_account(id);
ALTER TABLE crm.task_event ADD CONSTRAINT fk_task_event_next_assignee
 FOREIGN KEY(next_assignee_user_id) REFERENCES public.iam_user_account(id);
--rollback ALTER TABLE crm.task_event DROP CONSTRAINT fk_task_event_next_assignee;
--rollback ALTER TABLE crm.task_event DROP CONSTRAINT fk_task_event_prev_assignee;
--rollback ALTER TABLE crm.task_event DROP COLUMN next_assignee_user_id;
--rollback ALTER TABLE crm.task_event DROP COLUMN previous_assignee_user_id;


--changeset rm:0143-task-r2-execution-idempotency
-- An immutable actor-scoped command receipt belongs to the task event itself.
ALTER TABLE crm.task_event ADD COLUMN request_key VARCHAR(100);
ALTER TABLE crm.task_event ADD COLUMN request_sha256 CHAR(64);
CREATE UNIQUE INDEX uq_task_event_command_request
 ON crm.task_event(tenant_id,actor_user_id,request_key);
--rollback DROP INDEX crm.uq_task_event_command_request;
--rollback ALTER TABLE crm.task_event DROP COLUMN request_sha256;
--rollback ALTER TABLE crm.task_event DROP COLUMN request_key;


--changeset rm:0144-task-r2-crm-followup
-- One task root. CRM interaction linkage is tenant-qualified and immutable.
CREATE TABLE crm.task_crm_followup (
 tenant_id UUID NOT NULL,
 task_id UUID NOT NULL,
 interaction_id UUID NOT NULL,
 created_by UUID NOT NULL,
 linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_task_crm_followup PRIMARY KEY(tenant_id,task_id),
 CONSTRAINT fk_task_followup_task FOREIGN KEY(tenant_id,task_id)
  REFERENCES crm.task_item(tenant_id,id),
 CONSTRAINT fk_task_followup_interaction FOREIGN KEY(tenant_id,interaction_id)
  REFERENCES crm.customer_interaction(tenant_id,id),
 CONSTRAINT fk_task_followup_actor FOREIGN KEY(created_by)
  REFERENCES public.iam_user_account(id)
);
CREATE INDEX idx_task_crm_followup_interaction
 ON crm.task_crm_followup(tenant_id,interaction_id,task_id);
--rollback DROP INDEX crm.idx_task_crm_followup_interaction;
--rollback DROP TABLE crm.task_crm_followup;

--changeset rm:0145-task-r2-crm-followup-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_task_crm_followup_immutable
 BEFORE UPDATE OR DELETE ON crm.task_crm_followup
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_task_crm_followup_immutable ON crm.task_crm_followup;


--changeset rm:0146-task-r2-due-date-evidence
-- Additive immutable due-date change evidence. Original event revisions remain untouched.
ALTER TABLE crm.task_event ADD COLUMN previous_due_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm.task_event ADD COLUMN next_due_at TIMESTAMP WITH TIME ZONE;
--rollback ALTER TABLE crm.task_event DROP COLUMN next_due_at;
--rollback ALTER TABLE crm.task_event DROP COLUMN previous_due_at;


--changeset rm:0147-task-r3-cancellation-queue-lifecycle
-- Extend the single canonical task aggregate; never delete or overwrite task evidence.
ALTER TABLE crm.task_item DROP CONSTRAINT ck_task_status;
ALTER TABLE crm.task_item ADD CONSTRAINT ck_task_status CHECK
 (status IN ('OPEN','IN_PROGRESS','BLOCKED','COMPLETED','ARCHIVED','QUEUED','CANCELLED'));
ALTER TABLE crm.task_event ADD COLUMN reason_code VARCHAR(32);
ALTER TABLE crm.task_event ADD CONSTRAINT ck_task_event_reason CHECK
 (reason_code IS NULL OR reason_code IN
  ('MANAGER_CANCELLED','SOURCE_CANCELLED','RETURNED_TO_QUEUE'));
CREATE INDEX idx_task_queue ON crm.task_item(tenant_id,status,due_at,id);
--rollback DROP INDEX crm.idx_task_queue;
--rollback ALTER TABLE crm.task_event DROP CONSTRAINT ck_task_event_reason;
--rollback ALTER TABLE crm.task_event DROP COLUMN reason_code;
--rollback ALTER TABLE crm.task_item DROP CONSTRAINT ck_task_status;
-- NOTE: downgrade of the status constraint requires zero QUEUED/CANCELLED rows.
--rollback ALTER TABLE crm.task_item ADD CONSTRAINT ck_task_status CHECK (status IN ('OPEN','IN_PROGRESS','BLOCKED','COMPLETED','ARCHIVED'));


--changeset rm:0148-task-r31-queue-keyset-index
-- Supports tenant/status/id keyset scans without sorting by due_at.
CREATE INDEX idx_task_queue_keyset
 ON crm.task_item(tenant_id,status,id);
--rollback DROP INDEX crm.idx_task_queue_keyset;


--changeset rm:0149-task-r32-reconciliation-snapshot
-- Persistent immutable enumeration; task state remains canonical in task_item.
CREATE TABLE crm.task_reconciliation_snapshot (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 interaction_id UUID NOT NULL,
 created_by UUID NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
 item_count INTEGER NOT NULL,
 CONSTRAINT pk_task_reconciliation_snapshot PRIMARY KEY(tenant_id,id),
 CONSTRAINT uq_task_reconciliation_snapshot_request UNIQUE(tenant_id,created_by,request_key),
 CONSTRAINT fk_task_reconciliation_snapshot_source FOREIGN KEY(tenant_id,interaction_id)
  REFERENCES crm.customer_interaction(tenant_id,id),
 CONSTRAINT fk_task_reconciliation_snapshot_actor FOREIGN KEY(created_by)
  REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_task_reconciliation_snapshot_count CHECK(item_count BETWEEN 0 AND 10000)
);
CREATE TABLE crm.task_reconciliation_snapshot_item (
 tenant_id UUID NOT NULL,
 snapshot_id UUID NOT NULL,
 task_id UUID NOT NULL,
 captured_state VARCHAR(20) NOT NULL,
 captured_version BIGINT NOT NULL,
 CONSTRAINT pk_task_reconciliation_snapshot_item PRIMARY KEY(tenant_id,snapshot_id,task_id),
 CONSTRAINT fk_task_snapshot_item_snapshot FOREIGN KEY(tenant_id,snapshot_id)
  REFERENCES crm.task_reconciliation_snapshot(tenant_id,id),
 CONSTRAINT fk_task_snapshot_item_task FOREIGN KEY(tenant_id,task_id)
  REFERENCES crm.task_item(tenant_id,id),
 CONSTRAINT ck_task_snapshot_version CHECK(captured_version>=0)
);
--rollback DROP TABLE crm.task_reconciliation_snapshot_item;
--rollback DROP TABLE crm.task_reconciliation_snapshot;

--changeset rm:0150-task-r32-reconciliation-snapshot-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_task_reconciliation_snapshot_immutable
 BEFORE UPDATE OR DELETE ON crm.task_reconciliation_snapshot
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_task_reconciliation_snapshot_item_immutable
 BEFORE UPDATE OR DELETE ON crm.task_reconciliation_snapshot_item
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_task_reconciliation_snapshot_item_immutable ON crm.task_reconciliation_snapshot_item;
--rollback DROP TRIGGER trg_task_reconciliation_snapshot_immutable ON crm.task_reconciliation_snapshot;


--changeset rm:0151-task-r32-snapshot-seal
-- A committed immutable seal prevents extending a captured task set.
CREATE TABLE crm.task_reconciliation_snapshot_seal (
 tenant_id UUID NOT NULL,
 snapshot_id UUID NOT NULL,
 sealed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 items_sha256 CHAR(64) NOT NULL,
 CONSTRAINT pk_task_reconciliation_snapshot_seal PRIMARY KEY(tenant_id,snapshot_id),
 CONSTRAINT fk_task_reconciliation_snapshot_seal FOREIGN KEY(tenant_id,snapshot_id)
  REFERENCES crm.task_reconciliation_snapshot(tenant_id,id),
 CONSTRAINT ck_task_reconciliation_snapshot_seal_sha CHECK(length(items_sha256)=64)
);
--rollback DROP TABLE crm.task_reconciliation_snapshot_seal;

--changeset rm:0152-task-r32-snapshot-seal-protection dbms:postgresql splitStatements:false
CREATE OR REPLACE FUNCTION crm.task_snapshot_reject_insert_after_seal()
 RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS (
  SELECT 1 FROM crm.task_reconciliation_snapshot_seal
   WHERE tenant_id=NEW.tenant_id AND snapshot_id=NEW.snapshot_id
 ) THEN
  RAISE EXCEPTION 'TASK_SNAPSHOT_ALREADY_SEALED';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_snapshot_no_late_insert
 BEFORE INSERT ON crm.task_reconciliation_snapshot_item
 FOR EACH ROW EXECUTE FUNCTION crm.task_snapshot_reject_insert_after_seal();
CREATE TRIGGER trg_task_snapshot_seal_immutable
 BEFORE UPDATE OR DELETE ON crm.task_reconciliation_snapshot_seal
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_task_snapshot_seal_immutable ON crm.task_reconciliation_snapshot_seal;
--rollback DROP TRIGGER trg_task_snapshot_no_late_insert ON crm.task_reconciliation_snapshot_item;
--rollback DROP FUNCTION crm.task_snapshot_reject_insert_after_seal();


--changeset rm:0153-task-r32-snapshot-seal-transaction-fence dbms:postgresql splitStatements:false
-- Harden R3.2A immutability against a concurrent item INSERT that starts
-- before the seal transaction commits: both operations must lock the same
-- canonical snapshot header, then re-evaluate seal visibility after waiting.
CREATE OR REPLACE FUNCTION crm.task_snapshot_reject_insert_after_seal()
 RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM crm.task_reconciliation_snapshot
  WHERE tenant_id=NEW.tenant_id AND id=NEW.snapshot_id FOR UPDATE;
 IF NOT FOUND THEN
  RAISE EXCEPTION 'TASK_SNAPSHOT_MISSING';
 END IF;
 IF EXISTS (
  SELECT 1 FROM crm.task_reconciliation_snapshot_seal
   WHERE tenant_id=NEW.tenant_id AND snapshot_id=NEW.snapshot_id
 ) THEN
  RAISE EXCEPTION 'TASK_SNAPSHOT_ALREADY_SEALED';
 END IF;
 RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION crm.task_snapshot_lock_before_seal()
 RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_count integer; actual_count bigint;
BEGIN
 SELECT s.item_count INTO expected_count
 FROM crm.task_reconciliation_snapshot s
 WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.snapshot_id FOR UPDATE;
 IF NOT FOUND THEN
  RAISE EXCEPTION 'TASK_SNAPSHOT_MISSING';
 END IF;
 SELECT count(*) INTO actual_count
 FROM crm.task_reconciliation_snapshot_item
 WHERE tenant_id=NEW.tenant_id AND snapshot_id=NEW.snapshot_id;
 IF actual_count<>expected_count THEN
  RAISE EXCEPTION 'TASK_SNAPSHOT_COUNT_MISMATCH';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_snapshot_seal_transaction_fence
 BEFORE INSERT ON crm.task_reconciliation_snapshot_seal
 FOR EACH ROW EXECUTE FUNCTION crm.task_snapshot_lock_before_seal();
--rollback DROP TRIGGER trg_task_snapshot_seal_transaction_fence ON crm.task_reconciliation_snapshot_seal;
--rollback DROP FUNCTION crm.task_snapshot_lock_before_seal();
--rollback CREATE OR REPLACE FUNCTION crm.task_snapshot_reject_insert_after_seal() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS (SELECT 1 FROM crm.task_reconciliation_snapshot_seal WHERE tenant_id=NEW.tenant_id AND snapshot_id=NEW.snapshot_id) THEN RAISE EXCEPTION 'TASK_SNAPSHOT_ALREADY_SEALED'; END IF; RETURN NEW; END; $$;


--changeset rm:0154-fx-auto-policy-foundation
-- R2C.1B PRODUCT-only inert schema. No publication or runtime activation path.
ALTER TABLE crm.sku_fx_policy_version ADD CONSTRAINT uq_fx_auto_anchor_offer UNIQUE(tenant_id,id,offer_id);
ALTER TABLE crm.sku_fx_guardrail_version ADD CONSTRAINT uq_fx_auto_guard_offer UNIQUE(tenant_id,id,offer_id);
CREATE TABLE crm.fx_auto_limit_policy_version (
 tenant_id UUID NOT NULL REFERENCES public.iam_workspace(id),
 id UUID NOT NULL,
 rolling_seconds BIGINT NOT NULL CHECK(rolling_seconds>0),
 max_rolling_increase_pct NUMERIC(8,4) NOT NULL CHECK(max_rolling_increase_pct>=0),
 max_rolling_decrease_pct NUMERIC(8,4) NOT NULL CHECK(max_rolling_decrease_pct BETWEEN 0 AND 100),
 max_rolling_variation_kzt NUMERIC(19,4) NOT NULL CHECK(max_rolling_variation_kzt>=0),
 max_auto_publications BIGINT NOT NULL CHECK(max_auto_publications>=0),
 tenant_window_seconds BIGINT NOT NULL CHECK(tenant_window_seconds>0),
 tenant_delta_cap_kzt NUMERIC(19,4) NOT NULL CHECK(tenant_delta_cap_kzt>=0),
 tenant_count_cap BIGINT NOT NULL CHECK(tenant_count_cap>=0),
 run_delta_cap_kzt NUMERIC(19,4) NOT NULL CHECK(run_delta_cap_kzt>=0),
 run_count_cap INTEGER NOT NULL CHECK(run_count_cap BETWEEN 0 AND 10000),
 max_targets INTEGER NOT NULL CHECK(max_targets BETWEEN 1 AND 10000),
 PRIMARY KEY(tenant_id,id),
 CHECK(run_count_cap<=max_targets)
);
CREATE TABLE crm.fx_auto_policy_revision (
 tenant_id UUID NOT NULL REFERENCES public.iam_workspace(id),
 id UUID NOT NULL,
 policy_id UUID NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>0),
 limit_id UUID NOT NULL,
 payload VARCHAR(1000000) NOT NULL CHECK(length(payload)>0),
 payload_sha256 CHAR(64) NOT NULL CHECK(length(trim(payload_sha256))=64),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,policy_id,revision),
 UNIQUE(tenant_id,id,policy_id),
 UNIQUE(tenant_id,id,payload_sha256),
 FOREIGN KEY(tenant_id,limit_id) REFERENCES crm.fx_auto_limit_policy_version(tenant_id,id)
);
-- Explicit finite target list. Scope is sealed when approval is inserted.
CREATE TABLE crm.fx_auto_target_enrollment (
 tenant_id UUID NOT NULL,
 policy_revision_id UUID NOT NULL,
 offer_id UUID NOT NULL,
 anchor_id UUID NOT NULL,
 guardrail_id UUID NOT NULL,
 anchor_review_due_at TIMESTAMP WITH TIME ZONE NOT NULL,
 unit_code VARCHAR(64) NOT NULL CHECK(length(trim(unit_code))>0),
 tax_basis VARCHAR(20) NOT NULL CHECK(tax_basis IN ('TAX_INCLUDED','TAX_EXCLUDED','NONE')),
 PRIMARY KEY(tenant_id,policy_revision_id,offer_id),
 FOREIGN KEY(tenant_id,policy_revision_id) REFERENCES crm.fx_auto_policy_revision(tenant_id,id),
 FOREIGN KEY(tenant_id,offer_id) REFERENCES crm.store_offer(tenant_id,id),
 FOREIGN KEY(tenant_id,anchor_id,offer_id) REFERENCES crm.sku_fx_policy_version(tenant_id,id,offer_id),
 FOREIGN KEY(tenant_id,guardrail_id,offer_id) REFERENCES crm.sku_fx_guardrail_version(tenant_id,id,offer_id)
);
CREATE TABLE crm.fx_auto_approval_evidence (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 policy_revision_id UUID NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 owner_user_id UUID NOT NULL REFERENCES public.iam_user_account(id),
 membership_id UUID NOT NULL REFERENCES public.iam_org_membership(id),
 request_key VARCHAR(100) NOT NULL CHECK(length(trim(request_key))>0),
 approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 target_count INTEGER NOT NULL CHECK(target_count BETWEEN 1 AND 10000),
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,policy_revision_id),
 UNIQUE(tenant_id,id,policy_revision_id),
 UNIQUE(tenant_id,owner_user_id,request_key),
 FOREIGN KEY(tenant_id,policy_revision_id,payload_sha256)
 REFERENCES crm.fx_auto_policy_revision(tenant_id,id,payload_sha256),
 CHECK(expires_at>approved_at)
);
CREATE TABLE crm.fx_auto_policy_activation (
 tenant_id UUID NOT NULL,
 policy_id UUID NOT NULL,
 policy_revision_id UUID NOT NULL,
 approval_id UUID NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PAUSED' CHECK(state IN ('PAUSED','REVOKED')),
 execution_epoch BIGINT NOT NULL DEFAULT 0 CHECK(execution_epoch>=0),
 PRIMARY KEY(tenant_id,policy_id),
 FOREIGN KEY(tenant_id,policy_revision_id,policy_id)
 REFERENCES crm.fx_auto_policy_revision(tenant_id,id,policy_id),
 FOREIGN KEY(tenant_id,approval_id,policy_revision_id)
 REFERENCES crm.fx_auto_approval_evidence(tenant_id,id,policy_revision_id)
);
CREATE TABLE crm.fx_auto_simulation (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 policy_revision_id UUID NOT NULL,
 approval_id UUID NOT NULL,
 source_snapshot_id UUID NOT NULL REFERENCES crm.fx_official_rate_snapshot(id),
 request_key VARCHAR(100) NOT NULL CHECK(length(trim(request_key))>0),
 evaluation_as_of TIMESTAMP WITH TIME ZONE NOT NULL,
 input_payload VARCHAR(1000000) NOT NULL CHECK(length(input_payload)>0),
 input_sha256 CHAR(64) NOT NULL CHECK(length(trim(input_sha256))=64),
 item_count INTEGER NOT NULL CHECK(item_count BETWEEN 0 AND 10000),
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,request_key),
 UNIQUE(tenant_id,id,policy_revision_id),
 FOREIGN KEY(tenant_id,approval_id,policy_revision_id)
 REFERENCES crm.fx_auto_approval_evidence(tenant_id,id,policy_revision_id)
);
CREATE TABLE crm.fx_auto_simulation_item (
 tenant_id UUID NOT NULL,
 simulation_id UUID NOT NULL,
 policy_revision_id UUID NOT NULL,
 offer_id UUID NOT NULL,
 price_revision_id UUID NOT NULL,
 input_payload VARCHAR(1000000) NOT NULL CHECK(length(input_payload)>0),
 outcome VARCHAR(48) NOT NULL CHECK(outcome IN ('WOULD_UPDATE','NO_CHANGE','NO_CHANGE_SMALL_DELTA','WOULD_DEFER','BLOCKED_PRICE_LIMIT','BLOCKED_ROLLING_LIMIT','BASELINE_UNAVAILABLE','BLOCKED_BUDGET_EXHAUSTED','BLOCKED_POLICY_CONFLICT','AUTHORITY_HOLD','CONSENT_EXPIRED','SOURCE_NOT_ADMITTED')),
 virtual_delta_kzt NUMERIC(19,4) NOT NULL CHECK(virtual_delta_kzt>=0),
 PRIMARY KEY(tenant_id,simulation_id,offer_id),
 FOREIGN KEY(tenant_id,simulation_id,policy_revision_id)
 REFERENCES crm.fx_auto_simulation(tenant_id,id,policy_revision_id),
 FOREIGN KEY(tenant_id,policy_revision_id,offer_id)
 REFERENCES crm.fx_auto_target_enrollment(tenant_id,policy_revision_id,offer_id),
 FOREIGN KEY(tenant_id,price_revision_id,offer_id)
 REFERENCES crm.store_offer_price_revision(tenant_id,id,offer_id),
 CHECK((outcome='WOULD_UPDATE' AND virtual_delta_kzt>0) OR (outcome<>'WOULD_UPDATE' AND virtual_delta_kzt=0))
);
CREATE TABLE crm.fx_auto_simulation_seal (
 tenant_id UUID NOT NULL,
 simulation_id UUID NOT NULL,
 result_sha256 CHAR(64) NOT NULL CHECK(length(trim(result_sha256))=64),
 PRIMARY KEY(tenant_id,simulation_id),
 FOREIGN KEY(tenant_id,simulation_id) REFERENCES crm.fx_auto_simulation(tenant_id,id)
);
--rollback DROP TABLE crm.fx_auto_simulation_seal;
--rollback DROP TABLE crm.fx_auto_simulation_item;
--rollback DROP TABLE crm.fx_auto_simulation;
--rollback DROP TABLE crm.fx_auto_policy_activation;
--rollback DROP TABLE crm.fx_auto_approval_evidence;
--rollback DROP TABLE crm.fx_auto_target_enrollment;
--rollback DROP TABLE crm.fx_auto_policy_revision;
--rollback DROP TABLE crm.fx_auto_limit_policy_version;
--rollback ALTER TABLE crm.sku_fx_guardrail_version DROP CONSTRAINT uq_fx_auto_guard_offer;
--rollback ALTER TABLE crm.sku_fx_policy_version DROP CONSTRAINT uq_fx_auto_anchor_offer;

--changeset rm:0155-fx-auto-evidence-immutability dbms:postgresql splitStatements:false
CREATE TRIGGER trg_fx_auto_limit_policy_version_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_limit_policy_version
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_policy_revision_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_policy_revision
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_target_enrollment_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_target_enrollment
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_approval_evidence_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_approval_evidence
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_simulation_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_simulation
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_simulation_item_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_simulation_item
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_simulation_seal_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_simulation_seal
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_fx_auto_simulation_seal_immutable ON crm.fx_auto_simulation_seal;
--rollback DROP TRIGGER trg_fx_auto_simulation_item_immutable ON crm.fx_auto_simulation_item;
--rollback DROP TRIGGER trg_fx_auto_simulation_immutable ON crm.fx_auto_simulation;
--rollback DROP TRIGGER trg_fx_auto_approval_evidence_immutable ON crm.fx_auto_approval_evidence;
--rollback DROP TRIGGER trg_fx_auto_target_enrollment_immutable ON crm.fx_auto_target_enrollment;
--rollback DROP TRIGGER trg_fx_auto_policy_revision_immutable ON crm.fx_auto_policy_revision;
--rollback DROP TRIGGER trg_fx_auto_limit_policy_version_immutable ON crm.fx_auto_limit_policy_version;

--changeset rm:0156-fx-auto-seals-and-epoch dbms:postgresql splitStatements:false
-- Use a header row UPDATE (rather than read-only lock) to force stale snapshot
-- writers to serialize/fail even at REPEATABLE READ. Separate mutable guard rows.
CREATE TABLE crm.fx_auto_scope_guard (
 tenant_id UUID NOT NULL, policy_revision_id UUID NOT NULL,
 sealed BOOLEAN NOT NULL DEFAULT FALSE, generation BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,policy_revision_id),
 FOREIGN KEY(tenant_id,policy_revision_id) REFERENCES crm.fx_auto_policy_revision(tenant_id,id)
);
CREATE TABLE crm.fx_auto_simulation_guard (
 tenant_id UUID NOT NULL, simulation_id UUID NOT NULL,
 sealed BOOLEAN NOT NULL DEFAULT FALSE, generation BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,simulation_id),
 FOREIGN KEY(tenant_id,simulation_id) REFERENCES crm.fx_auto_simulation(tenant_id,id)
);
CREATE FUNCTION crm.fx_auto_init_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='fx_auto_policy_revision' THEN
  INSERT INTO crm.fx_auto_scope_guard(tenant_id,policy_revision_id) VALUES(NEW.tenant_id,NEW.id);
 ELSE
  INSERT INTO crm.fx_auto_simulation_guard(tenant_id,simulation_id) VALUES(NEW.tenant_id,NEW.id);
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_fx_auto_scope_init AFTER INSERT ON crm.fx_auto_policy_revision
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_init_guard();
CREATE TRIGGER trg_fx_auto_sim_init AFTER INSERT ON crm.fx_auto_simulation
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_init_guard();
CREATE FUNCTION crm.fx_auto_scope_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_count bigint; cap integer;
BEGIN
 UPDATE crm.fx_auto_scope_guard SET generation=generation+1,
  sealed=(TG_TABLE_NAME='fx_auto_approval_evidence')
 WHERE tenant_id=NEW.tenant_id AND policy_revision_id=NEW.policy_revision_id AND NOT sealed;
 IF NOT FOUND THEN RAISE EXCEPTION 'FX_AUTO_SCOPE_SEALED_OR_MISSING'; END IF;
 IF TG_TABLE_NAME='fx_auto_approval_evidence' THEN
  SELECT count(*) INTO actual_count FROM crm.fx_auto_target_enrollment
   WHERE tenant_id=NEW.tenant_id AND policy_revision_id=NEW.policy_revision_id;
  SELECT l.max_targets INTO cap FROM crm.fx_auto_policy_revision p JOIN crm.fx_auto_limit_policy_version l
   ON l.tenant_id=p.tenant_id AND l.id=p.limit_id
   WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.policy_revision_id;
  IF actual_count<>NEW.target_count OR actual_count>cap THEN RAISE EXCEPTION 'FX_AUTO_SCOPE_COUNT_MISMATCH'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.iam_workspace w JOIN public.iam_org_membership m
   ON m.organization_id=w.organization_id JOIN public.iam_user_account u ON u.id=m.user_id
   WHERE w.id=NEW.tenant_id AND m.id=NEW.membership_id AND m.user_id=NEW.owner_user_id
    AND w.status='ACTIVE' AND m.status='ACTIVE' AND m.role='OWNER' AND u.status='ACTIVE')
  THEN RAISE EXCEPTION 'FX_AUTO_OWNER_EVIDENCE_INVALID'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_fx_auto_scope_insert BEFORE INSERT ON crm.fx_auto_target_enrollment
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_scope_fence();
CREATE TRIGGER trg_fx_auto_scope_seal BEFORE INSERT ON crm.fx_auto_approval_evidence
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_scope_fence();
CREATE FUNCTION crm.fx_auto_sim_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_count bigint; expected_count integer;
BEGIN
 UPDATE crm.fx_auto_simulation_guard SET generation=generation+1,
  sealed=(TG_TABLE_NAME='fx_auto_simulation_seal')
 WHERE tenant_id=NEW.tenant_id AND simulation_id=NEW.simulation_id AND NOT sealed;
 IF NOT FOUND THEN RAISE EXCEPTION 'FX_AUTO_SIM_SEALED_OR_MISSING'; END IF;
 IF TG_TABLE_NAME='fx_auto_simulation_seal' THEN
  SELECT count(*) INTO actual_count FROM crm.fx_auto_simulation_item
   WHERE tenant_id=NEW.tenant_id AND simulation_id=NEW.simulation_id;
  SELECT item_count INTO expected_count FROM crm.fx_auto_simulation
   WHERE tenant_id=NEW.tenant_id AND id=NEW.simulation_id;
  IF actual_count<>expected_count THEN RAISE EXCEPTION 'FX_AUTO_SIM_COUNT_MISMATCH'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_fx_auto_sim_insert BEFORE INSERT ON crm.fx_auto_simulation_item
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_sim_fence();
CREATE TRIGGER trg_fx_auto_sim_seal BEFORE INSERT ON crm.fx_auto_simulation_seal
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_sim_fence();
CREATE FUNCTION crm.fx_auto_activation_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'FX_AUTO_ACTIVATION_DELETE_FORBIDDEN'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'PAUSED' OR NEW.execution_epoch<>0 THEN RAISE EXCEPTION 'FX_AUTO_INITIAL_STATE_INVALID'; END IF;
 ELSE
  IF OLD.state='REVOKED' OR NEW.tenant_id<>OLD.tenant_id OR NEW.policy_id<>OLD.policy_id
    OR NEW.policy_revision_id<>OLD.policy_revision_id OR NEW.approval_id<>OLD.approval_id
    OR NEW.execution_epoch<>OLD.execution_epoch+1 OR NEW.state<>'REVOKED'
  THEN RAISE EXCEPTION 'FX_AUTO_EPOCH_OR_TRANSITION_INVALID'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_fx_auto_activation_fence BEFORE INSERT OR UPDATE OR DELETE ON crm.fx_auto_policy_activation
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_activation_fence();
--rollback DROP TRIGGER trg_fx_auto_activation_fence ON crm.fx_auto_policy_activation;
--rollback DROP FUNCTION crm.fx_auto_activation_fence();
--rollback DROP TRIGGER trg_fx_auto_sim_seal ON crm.fx_auto_simulation_seal;
--rollback DROP TRIGGER trg_fx_auto_sim_insert ON crm.fx_auto_simulation_item;
--rollback DROP FUNCTION crm.fx_auto_sim_fence();
--rollback DROP TRIGGER trg_fx_auto_scope_seal ON crm.fx_auto_approval_evidence;
--rollback DROP TRIGGER trg_fx_auto_scope_insert ON crm.fx_auto_target_enrollment;
--rollback DROP FUNCTION crm.fx_auto_scope_fence();
--rollback DROP TRIGGER trg_fx_auto_sim_init ON crm.fx_auto_simulation;
--rollback DROP TRIGGER trg_fx_auto_scope_init ON crm.fx_auto_policy_revision;
--rollback DROP FUNCTION crm.fx_auto_init_guard();
--rollback DROP TABLE crm.fx_auto_simulation_guard;
--rollback DROP TABLE crm.fx_auto_scope_guard;


--changeset rm:0157-fx-auto-guard-tamper-protection dbms:postgresql splitStatements:false
-- Protect against ordinary direct DML, not a superuser or DDL-capable adversary.
CREATE FUNCTION crm.fx_auto_guard_internal_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF pg_trigger_depth()<>2 OR TG_OP='DELETE' THEN
  RAISE EXCEPTION 'FX_AUTO_GUARD_DIRECT_MUTATION';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.sealed OR NEW.generation<>0 THEN RAISE EXCEPTION 'FX_AUTO_GUARD_INITIAL_STATE'; END IF;
 ELSE
  IF OLD.sealed OR NEW.generation<>OLD.generation+1
     OR (to_jsonb(NEW)-'sealed'-'generation')<>(to_jsonb(OLD)-'sealed'-'generation')
  THEN RAISE EXCEPTION 'FX_AUTO_GUARD_TRANSITION_INVALID'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_fx_auto_scope_guard_internal BEFORE INSERT OR UPDATE OR DELETE ON crm.fx_auto_scope_guard
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_guard_internal_only();
CREATE TRIGGER trg_fx_auto_sim_guard_internal BEFORE INSERT OR UPDATE OR DELETE ON crm.fx_auto_simulation_guard
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_guard_internal_only();
CREATE TRIGGER trg_fx_auto_limit_policy_version_no_truncate BEFORE TRUNCATE ON crm.fx_auto_limit_policy_version
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_policy_revision_no_truncate BEFORE TRUNCATE ON crm.fx_auto_policy_revision
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_target_enrollment_no_truncate BEFORE TRUNCATE ON crm.fx_auto_target_enrollment
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_approval_evidence_no_truncate BEFORE TRUNCATE ON crm.fx_auto_approval_evidence
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_simulation_no_truncate BEFORE TRUNCATE ON crm.fx_auto_simulation
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_simulation_item_no_truncate BEFORE TRUNCATE ON crm.fx_auto_simulation_item
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_simulation_seal_no_truncate BEFORE TRUNCATE ON crm.fx_auto_simulation_seal
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_scope_guard_no_truncate BEFORE TRUNCATE ON crm.fx_auto_scope_guard
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_simulation_guard_no_truncate BEFORE TRUNCATE ON crm.fx_auto_simulation_guard
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_policy_activation_no_truncate BEFORE TRUNCATE ON crm.fx_auto_policy_activation
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_fx_auto_policy_activation_no_truncate ON crm.fx_auto_policy_activation;
--rollback DROP TRIGGER trg_fx_auto_simulation_guard_no_truncate ON crm.fx_auto_simulation_guard;
--rollback DROP TRIGGER trg_fx_auto_scope_guard_no_truncate ON crm.fx_auto_scope_guard;
--rollback DROP TRIGGER trg_fx_auto_simulation_seal_no_truncate ON crm.fx_auto_simulation_seal;
--rollback DROP TRIGGER trg_fx_auto_simulation_item_no_truncate ON crm.fx_auto_simulation_item;
--rollback DROP TRIGGER trg_fx_auto_simulation_no_truncate ON crm.fx_auto_simulation;
--rollback DROP TRIGGER trg_fx_auto_approval_evidence_no_truncate ON crm.fx_auto_approval_evidence;
--rollback DROP TRIGGER trg_fx_auto_target_enrollment_no_truncate ON crm.fx_auto_target_enrollment;
--rollback DROP TRIGGER trg_fx_auto_policy_revision_no_truncate ON crm.fx_auto_policy_revision;
--rollback DROP TRIGGER trg_fx_auto_limit_policy_version_no_truncate ON crm.fx_auto_limit_policy_version;
--rollback DROP TRIGGER trg_fx_auto_sim_guard_internal ON crm.fx_auto_simulation_guard;
--rollback DROP TRIGGER trg_fx_auto_scope_guard_internal ON crm.fx_auto_scope_guard;
--rollback DROP FUNCTION crm.fx_auto_guard_internal_only();

--changeset rm:0158-fx-auto-canonical-digest-columns
-- Legacy evidence remains readable but is not upgraded by fabricating consent.
ALTER TABLE crm.fx_auto_approval_evidence ADD COLUMN scope_sha256 CHAR(64);
--rollback ALTER TABLE crm.fx_auto_approval_evidence DROP COLUMN scope_sha256;

--changeset rm:0159-fx-auto-canonical-digest-validation dbms:postgresql splitStatements:false
-- PG JSONB text serialization, UTF-8, SHA-256. Versioned database-local format,
-- not RFC 8785/JCS; JSON object payloads only. No implicit timezone dependence.
CREATE FUNCTION crm.fx_auto_sha_v1(value jsonb) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
 SELECT encode(sha256(convert_to(value::text,'UTF8')),'hex')
$$;
CREATE FUNCTION crm.fx_auto_scope_sha_v1(t uuid,p uuid) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT crm.fx_auto_sha_v1(jsonb_build_object(
  'schema','FX_AUTO_SCOPE_PG_V1','tenant',p0.tenant_id,'revision',p0.id,
  'policy',p0.policy_id,'revisionNo',p0.revision,'payloadSha',trim(p0.payload_sha256),
  'limits',to_jsonb(l),
  'targets',COALESCE((SELECT jsonb_agg(jsonb_build_object(
   'offer',e.offer_id,'anchor',e.anchor_id,'guardrail',e.guardrail_id,
   'reviewDueEpoch',extract(epoch FROM e.anchor_review_due_at),
   'unit',e.unit_code,'tax',e.tax_basis) ORDER BY e.offer_id)
   FROM crm.fx_auto_target_enrollment e WHERE e.tenant_id=t AND e.policy_revision_id=p),'[]'::jsonb)))
 FROM crm.fx_auto_policy_revision p0 JOIN crm.fx_auto_limit_policy_version l
 ON l.tenant_id=p0.tenant_id AND l.id=p0.limit_id WHERE p0.tenant_id=t AND p0.id=p
$$;
CREATE FUNCTION crm.fx_auto_sim_sha_v1(t uuid,s uuid) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT crm.fx_auto_sha_v1(jsonb_build_object(
  'schema','FX_AUTO_SIM_PG_V1','tenant',h.tenant_id,'simulation',h.id,
  'policy',h.policy_revision_id,'approval',h.approval_id,'source',h.source_snapshot_id,
  'request',h.request_key,'asOfEpoch',extract(epoch FROM h.evaluation_as_of),
  'inputSha',trim(h.input_sha256),'count',h.item_count,
  'items',COALESCE((SELECT jsonb_agg(jsonb_build_object(
   'offer',i.offer_id,'priceRevision',i.price_revision_id,'input',i.input_payload::jsonb,
   'outcome',i.outcome,'delta',i.virtual_delta_kzt) ORDER BY i.offer_id)
   FROM crm.fx_auto_simulation_item i WHERE i.tenant_id=t AND i.simulation_id=s),'[]'::jsonb)))
 FROM crm.fx_auto_simulation h WHERE h.tenant_id=t AND h.id=s
$$;
CREATE FUNCTION crm.fx_auto_payload_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p jsonb; h text;
BEGIN
 IF TG_TABLE_NAME='fx_auto_policy_revision' THEN
  p=NEW.payload::jsonb; h=trim(NEW.payload_sha256);
 ELSIF TG_TABLE_NAME='fx_auto_simulation' THEN
  p=NEW.input_payload::jsonb; h=trim(NEW.input_sha256);
 ELSE
  p=NEW.input_payload::jsonb;
 END IF;
 IF jsonb_typeof(p)<>'object' THEN RAISE EXCEPTION 'FX_AUTO_PAYLOAD_OBJECT_REQUIRED'; END IF;
 IF TG_TABLE_NAME<>'fx_auto_simulation_item' AND h IS DISTINCT FROM crm.fx_auto_sha_v1(p)
 THEN RAISE EXCEPTION 'FX_AUTO_PAYLOAD_DIGEST_MISMATCH'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_fx_auto_policy_payload BEFORE INSERT ON crm.fx_auto_policy_revision
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_payload_check();
CREATE TRIGGER trg_fx_auto_sim_payload BEFORE INSERT ON crm.fx_auto_simulation
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_payload_check();
CREATE TRIGGER trg_fx_auto_item_payload BEFORE INSERT ON crm.fx_auto_simulation_item
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_payload_check();
-- AFTER INSERT runs after the existing BEFORE fence has serialized writers.
CREATE FUNCTION crm.fx_auto_seal_digest_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='fx_auto_approval_evidence' THEN
  IF trim(NEW.scope_sha256) IS DISTINCT FROM crm.fx_auto_scope_sha_v1(NEW.tenant_id,NEW.policy_revision_id)
  THEN RAISE EXCEPTION 'FX_AUTO_SCOPE_DIGEST_MISMATCH'; END IF;
 ELSE
  IF trim(NEW.result_sha256) IS DISTINCT FROM crm.fx_auto_sim_sha_v1(NEW.tenant_id,NEW.simulation_id)
  THEN RAISE EXCEPTION 'FX_AUTO_SIM_DIGEST_MISMATCH'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_fx_auto_scope_digest AFTER INSERT ON crm.fx_auto_approval_evidence
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_seal_digest_check();
CREATE TRIGGER trg_fx_auto_sim_digest AFTER INSERT ON crm.fx_auto_simulation_seal
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_seal_digest_check();
CREATE FUNCTION crm.fx_auto_verified_approval_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM crm.fx_auto_approval_evidence a
  WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.approval_id
   AND a.scope_sha256 IS NOT NULL
   AND trim(a.scope_sha256)=crm.fx_auto_scope_sha_v1(a.tenant_id,a.policy_revision_id))
 THEN RAISE EXCEPTION 'FX_AUTO_UNVERIFIED_LEGACY_APPROVAL'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_fx_auto_sim_verified_approval BEFORE INSERT ON crm.fx_auto_simulation
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_verified_approval_only();
CREATE TRIGGER trg_fx_auto_activation_verified_approval BEFORE INSERT ON crm.fx_auto_policy_activation
 FOR EACH ROW EXECUTE FUNCTION crm.fx_auto_verified_approval_only();
--rollback DROP TRIGGER trg_fx_auto_activation_verified_approval ON crm.fx_auto_policy_activation;
--rollback DROP TRIGGER trg_fx_auto_sim_verified_approval ON crm.fx_auto_simulation;
--rollback DROP FUNCTION crm.fx_auto_verified_approval_only();
--rollback DROP TRIGGER trg_fx_auto_sim_digest ON crm.fx_auto_simulation_seal;
--rollback DROP TRIGGER trg_fx_auto_scope_digest ON crm.fx_auto_approval_evidence;
--rollback DROP FUNCTION crm.fx_auto_seal_digest_check();
--rollback DROP TRIGGER trg_fx_auto_item_payload ON crm.fx_auto_simulation_item;
--rollback DROP TRIGGER trg_fx_auto_sim_payload ON crm.fx_auto_simulation;
--rollback DROP TRIGGER trg_fx_auto_policy_payload ON crm.fx_auto_policy_revision;
--rollback DROP FUNCTION crm.fx_auto_payload_check();
--rollback DROP FUNCTION crm.fx_auto_sim_sha_v1(uuid,uuid);
--rollback DROP FUNCTION crm.fx_auto_scope_sha_v1(uuid,uuid);
--rollback DROP FUNCTION crm.fx_auto_sha_v1(jsonb);


--changeset rm:0160-cur-r2c-pricing-anchor-purpose
-- Independent opt-in; deliberately no backfill from PURCHASE or SALES_INTERNAL.
ALTER TABLE public.tenant_allowed_currency DROP CONSTRAINT ck_cur_allowed_purpose;
ALTER TABLE public.tenant_allowed_currency ADD CONSTRAINT ck_cur_allowed_purpose
 CHECK(purpose IN ('PURCHASE','ACCOUNTING','INVENTORY_VALUATION','SALES_INTERNAL','PUBLIC_PRICE','PRICING_ANCHOR'));
ALTER TABLE public.tenant_allowed_currency ADD CONSTRAINT ck_cur_allowed_pricing_anchor
 CHECK(purpose<>'PRICING_ANCHOR' OR currency_code IN ('USD','EUR','CNY'));
-- Rollback intentionally fails if any anchor configuration exists: no silent loss of consent.
--rollback ALTER TABLE public.tenant_allowed_currency ADD CONSTRAINT ck_cur_allowed_pre_anchor CHECK(purpose IN ('PURCHASE','ACCOUNTING','INVENTORY_VALUATION','SALES_INTERNAL','PUBLIC_PRICE'));
--rollback ALTER TABLE public.tenant_allowed_currency DROP CONSTRAINT ck_cur_allowed_pricing_anchor;
--rollback ALTER TABLE public.tenant_allowed_currency DROP CONSTRAINT ck_cur_allowed_purpose;
--rollback ALTER TABLE public.tenant_allowed_currency RENAME CONSTRAINT ck_cur_allowed_pre_anchor TO ck_cur_allowed_purpose;


--changeset rm:0161-fx-auto-revocation-evidence
CREATE TABLE crm.fx_auto_revocation_evidence (
 tenant_id UUID NOT NULL,
 policy_id UUID NOT NULL,
 approval_id UUID NOT NULL,
 actor_user_id UUID NOT NULL REFERENCES public.iam_user_account(id),
 previous_epoch BIGINT NOT NULL CHECK(previous_epoch>=0),
 revoked_epoch BIGINT NOT NULL CHECK(revoked_epoch=previous_epoch+1),
 revoked_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,policy_id),
 FOREIGN KEY(tenant_id,policy_id) REFERENCES crm.fx_auto_policy_activation(tenant_id,policy_id),
 FOREIGN KEY(tenant_id,approval_id) REFERENCES crm.fx_auto_approval_evidence(tenant_id,id)
);
--rollback DROP TABLE crm.fx_auto_revocation_evidence;

--changeset rm:0162-fx-auto-revocation-evidence-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_fx_auto_revocation_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_revocation_evidence
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_revocation_no_truncate BEFORE TRUNCATE ON crm.fx_auto_revocation_evidence
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_fx_auto_revocation_no_truncate ON crm.fx_auto_revocation_evidence;
--rollback DROP TRIGGER trg_fx_auto_revocation_immutable ON crm.fx_auto_revocation_evidence;


--changeset rm:0163-storefront-commercial-context
-- Explicit owner-reviewed metadata only. No inferred legacy unit/tax backfill.
CREATE TABLE crm.store_offer_commercial_context (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 offer_id UUID NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>0),
 unit_code VARCHAR(64) NOT NULL CHECK(length(trim(unit_code))>0),
 tax_basis VARCHAR(20) NOT NULL CHECK(tax_basis IN ('TAX_INCLUDED','TAX_EXCLUDED','NONE')),
 price_basis VARCHAR(32) NOT NULL CHECK(price_basis='FIXED_SINGLE_UNIT'),
 approved_by UUID NOT NULL REFERENCES public.iam_user_account(id),
 approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,offer_id,revision),
 UNIQUE(tenant_id,id,offer_id),
 FOREIGN KEY(tenant_id,offer_id) REFERENCES crm.store_offer(tenant_id,id)
);
--rollback DROP TABLE crm.store_offer_commercial_context;

--changeset rm:0164-storefront-commercial-context-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_store_offer_commercial_context_immutable BEFORE UPDATE OR DELETE ON crm.store_offer_commercial_context
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_store_offer_commercial_context_no_truncate BEFORE TRUNCATE ON crm.store_offer_commercial_context
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_store_offer_commercial_context_no_truncate ON crm.store_offer_commercial_context;
--rollback DROP TRIGGER trg_store_offer_commercial_context_immutable ON crm.store_offer_commercial_context;

--changeset rm:0165-fx-auto-stable-tenant-budget-guard
-- Stable across currencies, policies, revisions and run identities.
-- No consumption or AUTO execution authority is introduced by this migration.
CREATE TABLE crm.fx_auto_tenant_budget_guard (
 tenant_id UUID PRIMARY KEY REFERENCES public.iam_workspace(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
--rollback DROP TABLE crm.fx_auto_tenant_budget_guard;


--changeset rm:0166-fx-auto-budget-ledger-foundation
-- Successful consumption is tied to a canonical price revision. No live writer
-- is enabled here; execution receipt and system provenance are the next gate.
CREATE TABLE crm.fx_auto_budget_consumption (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 offer_id UUID NOT NULL,
 price_revision_id UUID NOT NULL,
 policy_revision_id UUID NOT NULL,
 approval_id UUID NOT NULL,
 delta_kzt NUMERIC(19,4) NOT NULL CHECK(delta_kzt>0),
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,price_revision_id),
 FOREIGN KEY(tenant_id) REFERENCES crm.fx_auto_tenant_budget_guard(tenant_id),
 FOREIGN KEY(tenant_id,price_revision_id,offer_id) REFERENCES crm.store_offer_price_revision(tenant_id,id,offer_id),
 FOREIGN KEY(tenant_id,policy_revision_id,offer_id) REFERENCES crm.fx_auto_target_enrollment(tenant_id,policy_revision_id,offer_id),
 FOREIGN KEY(tenant_id,approval_id,policy_revision_id) REFERENCES crm.fx_auto_approval_evidence(tenant_id,id,policy_revision_id)
);
CREATE INDEX ix_fx_auto_budget_window ON crm.fx_auto_budget_consumption(tenant_id,recorded_at);
--rollback DROP TABLE crm.fx_auto_budget_consumption;

--changeset rm:0167-fx-auto-budget-ledger-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_fx_auto_budget_consumption_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_budget_consumption
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_budget_consumption_no_truncate BEFORE TRUNCATE ON crm.fx_auto_budget_consumption
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_budget_guard_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_tenant_budget_guard
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_budget_guard_no_truncate BEFORE TRUNCATE ON crm.fx_auto_tenant_budget_guard
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_fx_auto_budget_guard_no_truncate ON crm.fx_auto_tenant_budget_guard;
--rollback DROP TRIGGER trg_fx_auto_budget_guard_immutable ON crm.fx_auto_tenant_budget_guard;
--rollback DROP TRIGGER trg_fx_auto_budget_consumption_no_truncate ON crm.fx_auto_budget_consumption;
--rollback DROP TRIGGER trg_fx_auto_budget_consumption_immutable ON crm.fx_auto_budget_consumption;


--changeset rm:0168-fx-auto-execution-receipt-foundation
-- Receipt and consumption must identify the very same canonical price revision.
-- No ACTIVE state or executable AUTO port is enabled by this migration.
ALTER TABLE crm.fx_auto_budget_consumption ADD CONSTRAINT uq_fx_auto_consumption_binding
 UNIQUE(tenant_id,id,offer_id,price_revision_id,policy_revision_id,approval_id);
CREATE TABLE crm.fx_auto_execution_receipt (
 tenant_id UUID NOT NULL,
 id UUID NOT NULL,
 semantic_event_key CHAR(64) NOT NULL,
 consumption_id UUID NOT NULL,
 offer_id UUID NOT NULL,
 price_revision_id UUID NOT NULL,
 policy_revision_id UUID NOT NULL,
 approval_id UUID NOT NULL,
 commercial_context_id UUID NOT NULL,
 source_snapshot_id UUID NOT NULL REFERENCES crm.fx_official_rate_snapshot(id),
 execution_epoch BIGINT NOT NULL CHECK(execution_epoch>=0),
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,semantic_event_key),
 UNIQUE(tenant_id,consumption_id),
 UNIQUE(tenant_id,price_revision_id),
 FOREIGN KEY(tenant_id,consumption_id,offer_id,price_revision_id,policy_revision_id,approval_id)
 REFERENCES crm.fx_auto_budget_consumption(tenant_id,id,offer_id,price_revision_id,policy_revision_id,approval_id),
 FOREIGN KEY(tenant_id,commercial_context_id,offer_id)
 REFERENCES crm.store_offer_commercial_context(tenant_id,id,offer_id)
);
--rollback DROP TABLE crm.fx_auto_execution_receipt;
--rollback ALTER TABLE crm.fx_auto_budget_consumption DROP CONSTRAINT uq_fx_auto_consumption_binding;

--changeset rm:0169-fx-auto-execution-receipt-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_fx_auto_execution_receipt_immutable BEFORE UPDATE OR DELETE ON crm.fx_auto_execution_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
CREATE TRIGGER trg_fx_auto_execution_receipt_no_truncate BEFORE TRUNCATE ON crm.fx_auto_execution_receipt
 FOR EACH STATEMENT EXECUTE FUNCTION crm.cur_r2a_reject_evidence_mutation();
--rollback DROP TRIGGER trg_fx_auto_execution_receipt_no_truncate ON crm.fx_auto_execution_receipt;
--rollback DROP TRIGGER trg_fx_auto_execution_receipt_immutable ON crm.fx_auto_execution_receipt;


--changeset rm:0170-storefront-price-provenance
-- Existing price evidence retains its original human actor. No synthetic user.
ALTER TABLE crm.store_offer_price_revision ADD COLUMN provenance VARCHAR(16) DEFAULT 'USER' NOT NULL;
ALTER TABLE crm.store_offer_price_revision ALTER COLUMN actor_user_id DROP NOT NULL;
ALTER TABLE crm.store_offer_price_revision ADD CONSTRAINT ck_store_offer_price_provenance
 CHECK((provenance='USER' AND actor_user_id IS NOT NULL) OR
       (provenance='SYSTEM_AUTO' AND actor_user_id IS NULL AND old_price IS NOT NULL
        AND old_currency='KZT' AND new_currency='KZT' AND old_price<>new_price));
-- rollback deliberately fails if SYSTEM_AUTO evidence exists.
--rollback ALTER TABLE crm.store_offer_price_revision ALTER COLUMN actor_user_id SET NOT NULL;
--rollback ALTER TABLE crm.store_offer_price_revision DROP CONSTRAINT ck_store_offer_price_provenance;
--rollback ALTER TABLE crm.store_offer_price_revision DROP COLUMN provenance;

--changeset rm:0171-storefront-system-auto-closed dbms:postgresql splitStatements:false
-- B.1 foundation only: all SYSTEM_AUTO inserts are closed until the trusted
-- execution port and the deferred receipt/consumption invariant are qualified.
CREATE FUNCTION crm.reject_unadmitted_auto_price() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.provenance='SYSTEM_AUTO' THEN RAISE EXCEPTION 'FX_AUTO_EXECUTION_DISABLED'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_store_offer_auto_closed BEFORE INSERT ON crm.store_offer_price_revision
 FOR EACH ROW EXECUTE FUNCTION crm.reject_unadmitted_auto_price();
CREATE TRIGGER trg_store_offer_price_no_truncate BEFORE TRUNCATE ON crm.store_offer_price_revision
 FOR EACH STATEMENT EXECUTE FUNCTION crm.reject_price_revision_mutation();
--rollback DROP TRIGGER trg_store_offer_price_no_truncate ON crm.store_offer_price_revision;
--rollback DROP TRIGGER trg_store_offer_auto_closed ON crm.store_offer_price_revision;
--rollback DROP FUNCTION crm.reject_unadmitted_auto_price();

--changeset rm:0172-task-radar-active-due-index dbms:postgresql
-- One ordered active/assigned/non-null due range across statuses.
-- Do not include row_version or other mutable payload columns.
-- Build transactionally during controlled migration; measure populated build/lock time.
CREATE INDEX idx_task_radar_active_due ON crm.task_item(tenant_id,due_at,id)
 WHERE status IN ('OPEN','IN_PROGRESS','BLOCKED')
   AND assignee_user_id IS NOT NULL AND due_at IS NOT NULL;
--rollback DROP INDEX crm.idx_task_radar_active_due;

--changeset rm:0173-int-calendar-policy
CREATE TABLE crm.int_calendar_policy (
 tenant_id UUID NOT NULL,
 version BIGINT NOT NULL,
 zone_id VARCHAR(64) NOT NULL,
 effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
 created_by UUID NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_int_calendar_policy PRIMARY KEY(tenant_id,version),
 CONSTRAINT fk_int_calendar_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT fk_int_calendar_actor FOREIGN KEY(created_by) REFERENCES public.iam_user_account(id),
 CONSTRAINT ck_int_calendar_version CHECK(version>0),
 CONSTRAINT ck_int_calendar_zone CHECK(length(trim(zone_id))>0),
 CONSTRAINT ck_int_calendar_effective CHECK(effective_from=created_at)
);
--rollback DROP TABLE crm.int_calendar_policy;

--changeset rm:0174-int-calendar-policy-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION crm.int_reject_calendar_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'CALENDAR_POLICY_IMMUTABLE';
END; $$;
CREATE TRIGGER trg_int_calendar_immutable BEFORE UPDATE OR DELETE ON crm.int_calendar_policy
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_calendar_mutation();
CREATE TRIGGER trg_int_calendar_no_truncate BEFORE TRUNCATE ON crm.int_calendar_policy
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_calendar_mutation();
--rollback DROP TRIGGER trg_int_calendar_no_truncate ON crm.int_calendar_policy;
--rollback DROP TRIGGER trg_int_calendar_immutable ON crm.int_calendar_policy;
--rollback DROP FUNCTION crm.int_reject_calendar_mutation();

--changeset rm:0175-int-canonical-fact
CREATE TABLE crm.int_canonical_fact (
 tenant_id UUID NOT NULL,
 owner_module VARCHAR(16) NOT NULL,
 source_kind VARCHAR(32) NOT NULL,
 source_id UUID NOT NULL,
 subject_id UUID NOT NULL,
 source_version BIGINT,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 recorded_at TIMESTAMP WITH TIME ZONE,
 schema_version INTEGER NOT NULL,
 digest VARCHAR(64) NOT NULL,
 CONSTRAINT pk_int_canonical_fact PRIMARY KEY(tenant_id,owner_module,source_kind,source_id),
 CONSTRAINT fk_int_fact_tenant FOREIGN KEY(tenant_id) REFERENCES public.iam_workspace(id),
 CONSTRAINT ck_int_fact_schema CHECK(schema_version=1),
 CONSTRAINT ck_int_fact_digest CHECK(length(digest)=64),
 CONSTRAINT ck_int_fact_kind CHECK((owner_module='SALES' AND source_kind IN ('ORDER_PLACED','ORDER_CONFIRMED','ORDER_COMPLETED','ORDER_CANCELLED') AND recorded_at IS NULL)
 OR (owner_module='INVENTORY' AND source_kind='STOCK_MOVEMENT' AND recorded_at IS NOT NULL)),
 CONSTRAINT ck_int_fact_version CHECK(source_version IS NULL OR source_version>=0)
);
--rollback DROP TABLE crm.int_canonical_fact;

--changeset rm:0176-int-canonical-fact-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION crm.int_reject_fact_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'CANONICAL_FACT_IMMUTABLE';
END; $$;
CREATE TRIGGER trg_int_fact_immutable BEFORE UPDATE OR DELETE ON crm.int_canonical_fact
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_fact_no_truncate BEFORE TRUNCATE ON crm.int_canonical_fact
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TRIGGER trg_int_fact_no_truncate ON crm.int_canonical_fact;
--rollback DROP TRIGGER trg_int_fact_immutable ON crm.int_canonical_fact;
--rollback DROP FUNCTION crm.int_reject_fact_mutation();
 
--changeset rm:0177-int-canonical-fact-v2
ALTER TABLE crm.int_canonical_fact DROP CONSTRAINT ck_int_fact_schema;
ALTER TABLE crm.int_canonical_fact ADD CONSTRAINT ck_int_fact_schema CHECK(schema_version IN (1,2));
--rollback ALTER TABLE crm.int_canonical_fact DROP CONSTRAINT ck_int_fact_schema;
--rollback ALTER TABLE crm.int_canonical_fact ADD CONSTRAINT ck_int_fact_schema CHECK(schema_version=1);

--changeset rm:0178-int-canonical-fact-hex dbms:postgresql
ALTER TABLE crm.int_canonical_fact ADD CONSTRAINT ck_int_fact_hex CHECK(digest ~ '^[0-9a-f]{64}$');
--rollback ALTER TABLE crm.int_canonical_fact DROP CONSTRAINT ck_int_fact_hex;

--changeset rm:0179-sales-canonical-status-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_sales_status_event_immutable
 BEFORE UPDATE OR DELETE ON crm.sales_order_status_event
 FOR EACH ROW EXECUTE FUNCTION crm.reject_r13_history_mutation();
CREATE TRIGGER trg_sales_status_event_no_truncate
 BEFORE TRUNCATE ON crm.sales_order_status_event
 FOR EACH STATEMENT EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_sales_status_event_no_truncate ON crm.sales_order_status_event;
--rollback DROP TRIGGER trg_sales_status_event_immutable ON crm.sales_order_status_event;

--changeset rm:0180-sales-canonical-booked-header-immutable dbms:postgresql splitStatements:false
CREATE FUNCTION crm.reject_sales_booked_header_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.total_amount IS DISTINCT FROM OLD.total_amount OR NEW.currency IS DISTINCT FROM OLD.currency
 THEN RAISE EXCEPTION 'SALES_BOOKED_HEADER_IMMUTABLE'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_sales_booked_header_immutable BEFORE UPDATE ON crm.sales_order
 FOR EACH ROW EXECUTE FUNCTION crm.reject_sales_booked_header_mutation();
--rollback DROP TRIGGER trg_sales_booked_header_immutable ON crm.sales_order;
--rollback DROP FUNCTION crm.reject_sales_booked_header_mutation();


--changeset rm:0181-sales-booked-snapshot-seal dbms:postgresql splitStatements:false
-- Both checkout producers persist merchandise lines before their first status event.
-- A committed historical status event seals the existing snapshot, not its historical completeness.
ALTER TABLE crm.sales_order ADD COLUMN booked_snapshot_sealed BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE crm.sales_order o SET booked_snapshot_sealed=TRUE
 WHERE EXISTS (SELECT 1 FROM crm.sales_order_status_event e
               WHERE e.tenant_id=o.tenant_id AND e.order_id=o.id);
CREATE FUNCTION crm.reject_sales_snapshot_unseal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.booked_snapshot_sealed AND NOT NEW.booked_snapshot_sealed
 THEN RAISE EXCEPTION 'SALES_BOOKED_SNAPSHOT_IMMUTABLE'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_sales_snapshot_no_unseal BEFORE UPDATE ON crm.sales_order
 FOR EACH ROW EXECUTE FUNCTION crm.reject_sales_snapshot_unseal();
CREATE FUNCTION crm.seal_sales_booked_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE crm.sales_order SET booked_snapshot_sealed=TRUE
 WHERE tenant_id=NEW.tenant_id AND id=NEW.order_id AND NOT booked_snapshot_sealed;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_sales_snapshot_seal BEFORE INSERT ON crm.sales_order_status_event
 FOR EACH ROW EXECUTE FUNCTION crm.seal_sales_booked_snapshot();
CREATE FUNCTION crm.reject_late_sales_order_line() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sealed BOOLEAN;
BEGIN
 SELECT booked_snapshot_sealed INTO sealed FROM crm.sales_order
 WHERE tenant_id=NEW.tenant_id AND id=NEW.order_id FOR NO KEY UPDATE;
 IF sealed IS TRUE THEN RAISE EXCEPTION 'SALES_BOOKED_SNAPSHOT_SEALED'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_sales_line_no_late_insert BEFORE INSERT ON crm.sales_order_line
 FOR EACH ROW EXECUTE FUNCTION crm.reject_late_sales_order_line();
--rollback DROP TRIGGER trg_sales_line_no_late_insert ON crm.sales_order_line;
--rollback DROP FUNCTION crm.reject_late_sales_order_line();
--rollback DROP TRIGGER trg_sales_snapshot_seal ON crm.sales_order_status_event;
--rollback DROP FUNCTION crm.seal_sales_booked_snapshot();
--rollback DROP TRIGGER trg_sales_snapshot_no_unseal ON crm.sales_order;
--rollback DROP FUNCTION crm.reject_sales_snapshot_unseal();
--rollback ALTER TABLE crm.sales_order DROP COLUMN booked_snapshot_sealed;

--changeset rm:0182-canonical-source-no-truncate dbms:postgresql splitStatements:false
CREATE TRIGGER trg_sales_line_no_truncate BEFORE TRUNCATE ON crm.sales_order_line
 FOR EACH STATEMENT EXECUTE FUNCTION crm.reject_r13_history_mutation();
CREATE TRIGGER trg_inv_movement_no_truncate BEFORE TRUNCATE ON crm.inv_stock_movement
 FOR EACH STATEMENT EXECUTE FUNCTION crm.reject_r13_history_mutation();
--rollback DROP TRIGGER trg_inv_movement_no_truncate ON crm.inv_stock_movement;
--rollback DROP TRIGGER trg_sales_line_no_truncate ON crm.sales_order_line;


--changeset rm:0183-int-durable-discovery
-- Internal consumer identity is a target/epoch, not a global cursor.
CREATE TABLE crm.int_fact_consumer (
 tenant_id UUID NOT NULL REFERENCES public.iam_workspace(id),
 consumer_code VARCHAR(96) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,consumer_code),
 CHECK(length(trim(consumer_code))>0)
);
CREATE TABLE crm.int_fact_consumer_receipt (
 tenant_id UUID NOT NULL,
 consumer_code VARCHAR(96) NOT NULL,
 owner_module VARCHAR(16) NOT NULL,
 source_kind VARCHAR(32) NOT NULL,
 source_id UUID NOT NULL,
 digest VARCHAR(64) NOT NULL CHECK(length(digest)=64),
 schema_version INTEGER NOT NULL CHECK(schema_version IN (1,2)),
 disposition VARCHAR(24) NOT NULL,
 error_code VARCHAR(64),
 processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,consumer_code,owner_module,source_kind,source_id),
 FOREIGN KEY(tenant_id,consumer_code) REFERENCES crm.int_fact_consumer(tenant_id,consumer_code),
 FOREIGN KEY(tenant_id,owner_module,source_kind,source_id)
   REFERENCES crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id),
 CHECK((disposition IN ('APPLIED','OUTSIDE_COVERAGE') AND error_code IS NULL)
    OR (disposition='REJECTED' AND error_code IS NOT NULL))
);
--rollback DROP TABLE crm.int_fact_consumer_receipt;
--rollback DROP TABLE crm.int_fact_consumer;

--changeset rm:0184-int-bounded-backfill
CREATE TABLE crm.int_fact_backfill_run (
 tenant_id UUID NOT NULL REFERENCES public.iam_workspace(id),
 run_id UUID NOT NULL,
 owner_module VARCHAR(16) NOT NULL,
 source_kind VARCHAR(32) NOT NULL,
 range_start TIMESTAMP WITH TIME ZONE NOT NULL,
 range_end TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,run_id),
 UNIQUE(tenant_id,run_id,owner_module,source_kind),
 CHECK(range_start<range_end),
 CHECK((owner_module='SALES' AND source_kind IN ('ORDER_PLACED','ORDER_CONFIRMED','ORDER_COMPLETED','ORDER_CANCELLED'))
    OR (owner_module='INVENTORY' AND source_kind='STOCK_MOVEMENT'))
);
CREATE TABLE crm.int_fact_backfill_receipt (
 tenant_id UUID NOT NULL,
 run_id UUID NOT NULL,
 owner_module VARCHAR(16) NOT NULL,
 source_kind VARCHAR(32) NOT NULL,
 source_id UUID NOT NULL,
 disposition VARCHAR(16) NOT NULL,
 digest VARCHAR(64),
 schema_version INTEGER,
 error_code VARCHAR(64),
 processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,run_id,owner_module,source_kind,source_id),
 FOREIGN KEY(tenant_id,run_id,owner_module,source_kind)
   REFERENCES crm.int_fact_backfill_run(tenant_id,run_id,owner_module,source_kind),
 CHECK((disposition IN ('INSERTED','VERIFIED') AND digest IS NOT NULL AND length(digest)=64
        AND schema_version IS NOT NULL AND schema_version IN (1,2) AND error_code IS NULL)
    OR (disposition IN ('CONFLICT','INVALID') AND digest IS NULL AND schema_version IS NULL AND error_code IS NOT NULL))
);
--rollback DROP TABLE crm.int_fact_backfill_receipt;
--rollback DROP TABLE crm.int_fact_backfill_run;

--changeset rm:0185-int-backfill-range-indexes
CREATE INDEX idx_int_backfill_sales_range ON crm.sales_order_status_event(tenant_id,event_type,occurred_at,id);
CREATE INDEX idx_int_backfill_inventory_range ON crm.inv_stock_movement(tenant_id,occurred_at,id);
CREATE INDEX idx_int_fact_range ON crm.int_canonical_fact(tenant_id,owner_module,source_kind,occurred_at,source_id);
--rollback DROP INDEX crm.idx_int_fact_range;
--rollback DROP INDEX crm.idx_int_backfill_inventory_range;
--rollback DROP INDEX crm.idx_int_backfill_sales_range;

--changeset rm:0186-int-discovery-evidence-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_int_consumer_immutable BEFORE UPDATE OR DELETE ON crm.int_fact_consumer
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_consumer_no_truncate BEFORE TRUNCATE ON crm.int_fact_consumer
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_consumer_receipt_immutable BEFORE UPDATE OR DELETE ON crm.int_fact_consumer_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_consumer_receipt_no_truncate BEFORE TRUNCATE ON crm.int_fact_consumer_receipt
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_backfill_run_immutable BEFORE UPDATE OR DELETE ON crm.int_fact_backfill_run
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_backfill_run_no_truncate BEFORE TRUNCATE ON crm.int_fact_backfill_run
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_backfill_receipt_immutable BEFORE UPDATE OR DELETE ON crm.int_fact_backfill_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_backfill_receipt_no_truncate BEFORE TRUNCATE ON crm.int_fact_backfill_receipt
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TRIGGER trg_int_backfill_receipt_no_truncate ON crm.int_fact_backfill_receipt;
--rollback DROP TRIGGER trg_int_backfill_receipt_immutable ON crm.int_fact_backfill_receipt;
--rollback DROP TRIGGER trg_int_backfill_run_no_truncate ON crm.int_fact_backfill_run;
--rollback DROP TRIGGER trg_int_backfill_run_immutable ON crm.int_fact_backfill_run;
--rollback DROP TRIGGER trg_int_consumer_receipt_no_truncate ON crm.int_fact_consumer_receipt;
--rollback DROP TRIGGER trg_int_consumer_receipt_immutable ON crm.int_fact_consumer_receipt;
--rollback DROP TRIGGER trg_int_consumer_no_truncate ON crm.int_fact_consumer;
--rollback DROP TRIGGER trg_int_consumer_immutable ON crm.int_fact_consumer;


--changeset rm:0187-int-backfill-time-basis
-- Existing runs retain occurred_at semantics. New inventory runs use recorded_at.
ALTER TABLE crm.int_fact_backfill_run ADD COLUMN time_basis VARCHAR(16) DEFAULT 'OCCURRED' NOT NULL;
ALTER TABLE crm.int_fact_backfill_run ADD CONSTRAINT ck_int_backfill_basis
 CHECK(time_basis='OCCURRED' OR (owner_module='INVENTORY' AND time_basis='RECORDED'));
CREATE INDEX idx_int_backfill_inventory_recorded ON crm.inv_stock_movement(tenant_id,recorded_at,id);
CREATE INDEX idx_int_fact_recorded ON crm.int_canonical_fact(tenant_id,owner_module,source_kind,recorded_at,source_id);
--rollback DROP INDEX crm.idx_int_fact_recorded;
--rollback DROP INDEX crm.idx_int_backfill_inventory_recorded;
--rollback ALTER TABLE crm.int_fact_backfill_run DROP CONSTRAINT ck_int_backfill_basis;
--rollback ALTER TABLE crm.int_fact_backfill_run DROP COLUMN time_basis;

--changeset rm:0188-int-l1-generation
CREATE TABLE crm.int_projection_generation (
 tenant_id UUID NOT NULL,
 generation_id UUID NOT NULL,
 projection_code VARCHAR(24) NOT NULL CHECK(projection_code='L1'),
 state VARCHAR(16) NOT NULL DEFAULT 'BUILDING' CHECK(state='BUILDING'),
 consumer_code VARCHAR(96) NOT NULL,
 method_version INTEGER NOT NULL CHECK(method_version=1),
 calendar_version BIGINT NOT NULL,
 from_day DATE NOT NULL,
 until_day DATE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,generation_id),
 UNIQUE(tenant_id,consumer_code),
 FOREIGN KEY(tenant_id,consumer_code) REFERENCES crm.int_fact_consumer(tenant_id,consumer_code),
 FOREIGN KEY(tenant_id,calendar_version) REFERENCES crm.int_calendar_policy(tenant_id,version),
 CHECK(from_day<until_day)
);
-- P2 receipt is the single effect receipt; generation -> consumer is one-to-one.
-- No active reader route is enabled in this slice.
CREATE TABLE crm.int_daily_bucket (
 tenant_id UUID NOT NULL,
 generation_id UUID NOT NULL,
 metric_code VARCHAR(32) NOT NULL,
 local_day DATE NOT NULL,
 currency_key VARCHAR(3) NOT NULL,
 warehouse_key UUID NOT NULL,
 sku_key UUID NOT NULL,
 amount NUMERIC NOT NULL CHECK(amount>=0),
 observations BIGINT NOT NULL CHECK(observations>=0),
 PRIMARY KEY(tenant_id,generation_id,metric_code,local_day,currency_key,warehouse_key,sku_key),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_projection_generation(tenant_id,generation_id),
 CHECK((metric_code IN ('PLACED_ORDERS','CONFIRMED_ORDERS','FULFILLED_ORDERS','CANCELLED_ORDERS') AND currency_key='~')
    OR (metric_code='BOOKED_ORDER_AMOUNT' AND currency_key<>'~')
    OR (metric_code IN ('STOCK_IN_QTY','STOCK_OUT_QTY') AND currency_key='~')),
 CHECK(metric_code IN ('STOCK_IN_QTY','STOCK_OUT_QTY') OR
   (warehouse_key=CAST('00000000-0000-0000-0000-000000000000' AS UUID)
    AND sku_key=CAST('00000000-0000-0000-0000-000000000000' AS UUID)))
);
CREATE TABLE crm.int_l1_sales_contribution (
 tenant_id UUID NOT NULL,
 generation_id UUID NOT NULL,
 event_kind VARCHAR(32) NOT NULL,
 order_id UUID NOT NULL,
 local_day DATE NOT NULL,
 currency VARCHAR(3) NOT NULL,
 booked_amount NUMERIC NOT NULL CHECK(booked_amount>=0),
 PRIMARY KEY(tenant_id,generation_id,event_kind,order_id),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_projection_generation(tenant_id,generation_id),
 FOREIGN KEY(tenant_id,order_id) REFERENCES crm.sales_order(tenant_id,id),
 CHECK(event_kind IN ('ORDER_PLACED','ORDER_CONFIRMED','ORDER_COMPLETED','ORDER_CANCELLED'))
);
CREATE TABLE crm.int_projection_coverage (
 tenant_id UUID NOT NULL,
 generation_id UUID NOT NULL,
 proof_id UUID NOT NULL,
 observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 missing_facts BIGINT NOT NULL,
 orphan_facts BIGINT NOT NULL,
 pending_facts BIGINT NOT NULL,
 rejected_facts BIGINT NOT NULL,
 receipt_mismatches BIGINT NOT NULL,
 bucket_mismatches BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,generation_id,proof_id),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_projection_generation(tenant_id,generation_id)
);
--rollback DROP TABLE crm.int_projection_coverage;
--rollback DROP TABLE crm.int_l1_sales_contribution;
--rollback DROP TABLE crm.int_daily_bucket;
--rollback DROP TABLE crm.int_projection_generation;

--changeset rm:0189-int-l1-immutable-evidence dbms:postgresql splitStatements:false
CREATE TRIGGER trg_int_l1_generation_immutable BEFORE UPDATE OR DELETE ON crm.int_projection_generation
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_l1_generation_no_truncate BEFORE TRUNCATE ON crm.int_projection_generation
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_l1_coverage_immutable BEFORE UPDATE OR DELETE ON crm.int_projection_coverage
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_l1_coverage_no_truncate BEFORE TRUNCATE ON crm.int_projection_coverage
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TRIGGER trg_int_l1_coverage_no_truncate ON crm.int_projection_coverage;
--rollback DROP TRIGGER trg_int_l1_coverage_immutable ON crm.int_projection_coverage;
--rollback DROP TRIGGER trg_int_l1_generation_no_truncate ON crm.int_projection_generation;
--rollback DROP TRIGGER trg_int_l1_generation_immutable ON crm.int_projection_generation;


--changeset rm:0190-catalog-group-classification
ALTER TABLE crm.cat_item ADD CONSTRAINT uq_cat_item_kind_identity UNIQUE(tenant_id,id,kind);
CREATE TABLE crm.cat_group_node (
 tenant_id UUID NOT NULL REFERENCES public.iam_workspace(id),
 id UUID NOT NULL,
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('PRODUCT','SERVICE')),
 group_code VARCHAR(80) NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>0),
 parent_id UUID,
 label VARCHAR(240) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,id,kind),
 UNIQUE(tenant_id,kind,group_code,revision),
 FOREIGN KEY(tenant_id,parent_id,kind) REFERENCES crm.cat_group_node(tenant_id,id,kind),
 CHECK(parent_id IS NULL OR parent_id<>id)
);
CREATE TABLE crm.cat_group_assignment (
 tenant_id UUID NOT NULL,
 item_id UUID NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>0),
 kind VARCHAR(20) NOT NULL,
 group_id UUID NOT NULL,
 assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,item_id,revision),
 UNIQUE(tenant_id,item_id,revision,group_id),
 FOREIGN KEY(tenant_id,item_id,kind) REFERENCES crm.cat_item(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,group_id,kind) REFERENCES crm.cat_group_node(tenant_id,id,kind)
);
--rollback DROP TABLE crm.cat_group_assignment;
--rollback DROP TABLE crm.cat_group_node;
--rollback ALTER TABLE crm.cat_item DROP CONSTRAINT uq_cat_item_kind_identity;

--changeset rm:0191-int-dimension-snapshots
-- Historical absence is not reconstructed from today's master data.
CREATE TABLE crm.int_sales_line_dimension (
 tenant_id UUID NOT NULL,
 line_id UUID NOT NULL,
 item_id UUID,
 item_kind VARCHAR(20),
 group_id UUID,
 assignment_revision BIGINT,
 captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,line_id),
 FOREIGN KEY(tenant_id,line_id) REFERENCES crm.sales_order_line(tenant_id,id),
 FOREIGN KEY(tenant_id,item_id,item_kind) REFERENCES crm.cat_item(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,item_id,assignment_revision,group_id) REFERENCES crm.cat_group_assignment(tenant_id,item_id,revision,group_id),
 CHECK((item_id IS NULL AND item_kind IS NULL) OR (item_id IS NOT NULL AND item_kind IS NOT NULL)),
 CHECK((group_id IS NULL AND assignment_revision IS NULL) OR
   (group_id IS NOT NULL AND assignment_revision IS NOT NULL AND item_id IS NOT NULL))
);
CREATE TABLE crm.int_movement_dimension (
 tenant_id UUID NOT NULL,
 movement_id UUID NOT NULL,
 item_id UUID NOT NULL,
 item_kind VARCHAR(20) NOT NULL,
 group_id UUID,
 assignment_revision BIGINT,
 location_id UUID NOT NULL,
 kato_code VARCHAR(16) NOT NULL,
 region_code VARCHAR(16),
 geo_quality VARCHAR(16) NOT NULL CHECK(geo_quality IN ('RESOLVED','UNKNOWN')),
 captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,movement_id),
 FOREIGN KEY(tenant_id,movement_id) REFERENCES crm.inv_stock_movement(tenant_id,id),
 FOREIGN KEY(tenant_id,item_id,item_kind) REFERENCES crm.cat_item(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,item_id,assignment_revision,group_id) REFERENCES crm.cat_group_assignment(tenant_id,item_id,revision,group_id),
 FOREIGN KEY(tenant_id,location_id) REFERENCES public.org_location(workspace_id,id),
 CHECK((group_id IS NULL AND assignment_revision IS NULL) OR
       (group_id IS NOT NULL AND assignment_revision IS NOT NULL)),
 CHECK((geo_quality='RESOLVED' AND region_code IS NOT NULL) OR (geo_quality='UNKNOWN' AND region_code IS NULL))
);
CREATE INDEX idx_int_movement_dimension_geo ON crm.int_movement_dimension(tenant_id,region_code,group_id);
CREATE INDEX idx_int_sales_dimension_group ON crm.int_sales_line_dimension(tenant_id,group_id,line_id);
--rollback DROP TABLE crm.int_movement_dimension;
--rollback DROP TABLE crm.int_sales_line_dimension;

--changeset rm:0192-int-dimension-immutable dbms:postgresql splitStatements:false
CREATE TRIGGER trg_cat_group_node_immutable BEFORE UPDATE OR DELETE ON crm.cat_group_node
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_cat_group_node_no_truncate BEFORE TRUNCATE ON crm.cat_group_node
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_cat_group_assignment_immutable BEFORE UPDATE OR DELETE ON crm.cat_group_assignment
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_cat_group_assignment_no_truncate BEFORE TRUNCATE ON crm.cat_group_assignment
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_sales_dim_immutable BEFORE UPDATE OR DELETE ON crm.int_sales_line_dimension
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_sales_dim_no_truncate BEFORE TRUNCATE ON crm.int_sales_line_dimension
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_movement_dim_immutable BEFORE UPDATE OR DELETE ON crm.int_movement_dimension
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_movement_dim_no_truncate BEFORE TRUNCATE ON crm.int_movement_dimension
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TRIGGER trg_int_movement_dim_no_truncate ON crm.int_movement_dimension;
--rollback DROP TRIGGER trg_int_movement_dim_immutable ON crm.int_movement_dimension;
--rollback DROP TRIGGER trg_int_sales_dim_no_truncate ON crm.int_sales_line_dimension;
--rollback DROP TRIGGER trg_int_sales_dim_immutable ON crm.int_sales_line_dimension;
--rollback DROP TRIGGER trg_cat_group_assignment_no_truncate ON crm.cat_group_assignment;
--rollback DROP TRIGGER trg_cat_group_assignment_immutable ON crm.cat_group_assignment;
--rollback DROP TRIGGER trg_cat_group_node_no_truncate ON crm.cat_group_node;
--rollback DROP TRIGGER trg_cat_group_node_immutable ON crm.cat_group_node;


--changeset rm:0193-int-dimension-source-binding
-- Bind copied identities to their exact source, not merely to valid tenant entities.
-- Existing mismatched non-null snapshots fail migration; no historical repair.
ALTER TABLE crm.sales_order_line ADD CONSTRAINT uq_sales_line_source_item
 UNIQUE(tenant_id,id,catalog_item_id_snapshot);
ALTER TABLE crm.inv_stock_movement ADD CONSTRAINT uq_inv_movement_source_dims
 UNIQUE(tenant_id,id,item_id,location_id);
ALTER TABLE crm.int_sales_line_dimension ADD CONSTRAINT fk_int_sales_dim_source_item
 FOREIGN KEY(tenant_id,line_id,item_id)
 REFERENCES crm.sales_order_line(tenant_id,id,catalog_item_id_snapshot);
ALTER TABLE crm.int_movement_dimension ADD CONSTRAINT fk_int_movement_dim_source
 FOREIGN KEY(tenant_id,movement_id,item_id,location_id)
 REFERENCES crm.inv_stock_movement(tenant_id,id,item_id,location_id);
--rollback ALTER TABLE crm.int_movement_dimension DROP CONSTRAINT fk_int_movement_dim_source;
--rollback ALTER TABLE crm.int_sales_line_dimension DROP CONSTRAINT fk_int_sales_dim_source_item;
--rollback ALTER TABLE crm.inv_stock_movement DROP CONSTRAINT uq_inv_movement_source_dims;
--rollback ALTER TABLE crm.sales_order_line DROP CONSTRAINT uq_sales_line_source_item;

--changeset rm:0194-int-dimension-source-null-guard dbms:postgresql splitStatements:false
-- MATCH SIMPLE skips NULL; verify equality including NULL.
-- Historical absence of the entire dimension row remains valid.
DO $$
BEGIN
 IF EXISTS(SELECT 1 FROM crm.int_sales_line_dimension d
   JOIN crm.sales_order_line l ON l.tenant_id=d.tenant_id AND l.id=d.line_id
   WHERE d.item_id IS DISTINCT FROM l.catalog_item_id_snapshot) THEN
   RAISE EXCEPTION 'INT_SALES_DIM_SOURCE_ITEM_MISMATCH';
 END IF;
END; $$;
CREATE FUNCTION crm.int_check_sales_dimension_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM crm.sales_order_line l
   WHERE l.tenant_id=NEW.tenant_id AND l.id=NEW.line_id
   AND l.catalog_item_id_snapshot IS NOT DISTINCT FROM NEW.item_id) THEN
   RAISE EXCEPTION 'INT_SALES_DIM_SOURCE_ITEM_MISMATCH' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_sales_dim_source BEFORE INSERT ON crm.int_sales_line_dimension
 FOR EACH ROW EXECUTE FUNCTION crm.int_check_sales_dimension_source();
--rollback DROP TRIGGER trg_int_sales_dim_source ON crm.int_sales_line_dimension;
--rollback DROP FUNCTION crm.int_check_sales_dimension_source();

--changeset rm:0195-int-movement-dimension-geo-admission dbms:postgresql splitStatements:false
-- Validate geography on insertion, without reinterpreting older snapshots.
CREATE FUNCTION crm.int_check_movement_dimension_geo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_kato VARCHAR(16); node RECORD; next_source BIGINT;
 expected_region VARCHAR(16); visited BIGINT[] := ARRAY[]::BIGINT[];
BEGIN
 SELECT l.kato_code INTO actual_kato FROM public.org_location l
 WHERE l.workspace_id=NEW.tenant_id AND l.id=NEW.location_id FOR SHARE;
 IF NOT FOUND OR actual_kato IS DISTINCT FROM NEW.kato_code THEN
   RAISE EXCEPTION 'INT_MOVEMENT_DIM_KATO_MISMATCH' USING ERRCODE='23514';
 END IF;
 SELECT source_id,parent_source_id,code,level_no,is_deleted INTO node
 FROM public.ref_kato_territory WHERE code=actual_kato;
 FOR depth IN 1..32 LOOP
   EXIT WHEN node.source_id IS NULL;
   EXIT WHEN node.is_deleted OR node.source_id=ANY(visited);
   visited:=array_append(visited,node.source_id);
   IF node.level_no=2 THEN expected_region:=node.code; EXIT; END IF;
   EXIT WHEN node.level_no<2 OR node.parent_source_id IS NULL;
   next_source:=node.parent_source_id;
   SELECT source_id,parent_source_id,code,level_no,is_deleted INTO node
   FROM public.ref_kato_territory WHERE source_id=next_source;
 END LOOP;
 IF expected_region IS DISTINCT FROM NEW.region_code OR
    NEW.geo_quality IS DISTINCT FROM
       (CASE WHEN expected_region IS NULL THEN 'UNKNOWN' ELSE 'RESOLVED' END) THEN
   RAISE EXCEPTION 'INT_MOVEMENT_DIM_REGION_MISMATCH' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_movement_dim_geo BEFORE INSERT ON crm.int_movement_dimension
 FOR EACH ROW EXECUTE FUNCTION crm.int_check_movement_dimension_geo();
--rollback DROP TRIGGER trg_int_movement_dim_geo ON crm.int_movement_dimension;
--rollback DROP FUNCTION crm.int_check_movement_dimension_geo();


--changeset rm:0196-int-generation-runtime-lifecycle
-- Existing immutable generation configuration and evidence are not rewritten.
CREATE TABLE crm.int_projection_lifecycle (
 tenant_id UUID NOT NULL,
 generation_id UUID NOT NULL,
 lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'BUILDING',
 revision BIGINT NOT NULL DEFAULT 0,
 changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,generation_id),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_projection_generation(tenant_id,generation_id),
 CHECK((lifecycle_state='BUILDING' AND revision=0) OR
       (lifecycle_state='ABORTED' AND revision=1))
);
INSERT INTO crm.int_projection_lifecycle(tenant_id,generation_id,changed_at)
 SELECT tenant_id,generation_id,created_at FROM crm.int_projection_generation;
--rollback DROP TABLE crm.int_projection_lifecycle;

--changeset rm:0197-int-generation-runtime-guard dbms:postgresql splitStatements:false
CREATE FUNCTION crm.int_init_generation_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO crm.int_projection_lifecycle(tenant_id,generation_id,changed_at)
 VALUES (NEW.tenant_id,NEW.generation_id,NEW.created_at);
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_generation_lifecycle_init AFTER INSERT ON crm.int_projection_generation
 FOR EACH ROW EXECUTE FUNCTION crm.int_init_generation_lifecycle();
CREATE FUNCTION crm.int_guard_generation_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF NEW.lifecycle_state='BUILDING' AND NEW.revision=0 THEN RETURN NEW; END IF;
 ELSIF TG_OP='UPDATE' THEN
   IF OLD.tenant_id=NEW.tenant_id AND OLD.generation_id=NEW.generation_id
      AND OLD.lifecycle_state='BUILDING' AND OLD.revision=0
      AND NEW.lifecycle_state='ABORTED' AND NEW.revision=1
      AND NEW.changed_at>=OLD.changed_at THEN RETURN NEW; END IF;
 END IF;
 RAISE EXCEPTION 'INT_GENERATION_LIFECYCLE_TRANSITION' USING ERRCODE='23514';
END; $$;
CREATE TRIGGER trg_int_generation_lifecycle_guard BEFORE INSERT OR UPDATE OR DELETE
 ON crm.int_projection_lifecycle FOR EACH ROW EXECUTE FUNCTION crm.int_guard_generation_lifecycle();
CREATE TRIGGER trg_int_generation_lifecycle_no_truncate BEFORE TRUNCATE ON crm.int_projection_lifecycle
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TRIGGER trg_int_generation_lifecycle_no_truncate ON crm.int_projection_lifecycle;
--rollback DROP TRIGGER trg_int_generation_lifecycle_guard ON crm.int_projection_lifecycle;
--rollback DROP FUNCTION crm.int_guard_generation_lifecycle();
--rollback DROP TRIGGER trg_int_generation_lifecycle_init ON crm.int_projection_generation;
--rollback DROP FUNCTION crm.int_init_generation_lifecycle();

--changeset rm:0198-kato-admin-release-20261117
-- Source: user-provided KATO_17.11.2026.xlsx; SHA-256: 7dd2a5cde003b664cdd581a5805bacd33405181d702731265476d2c46cb2d660
-- Scope: 20 roots (17 oblasts + 3 cities), 195 district records, 91 city records (3 overlap roots),
-- 39 level-3 city administrations and 33 required intermediate nodes = 375 selected codes.
-- Parent relationships derived from the longest existing selected KATO prefix; IDs are internal, not official.
-- Existing source IDs, names and archival flags are preserved. New name_en is generated transliteration.
-- area_type on new rows stores the source k column; it is not an English type label.
-- Scope SHA-256 (UTF-8 compact JSON rows [code,parent,level,k,kk,ru], sorted by level/code): 53a3a6d643d1e91267dd58524d56fa160223ff164ad602203d5fb03ab09f6213
-- 365 new records; repair only the dangling Stepnogorsk city parent. No settlements imported.
INSERT INTO public.ref_kato_territory (code,source_id,parent_source_id,level_no,area_type,name_kk,name_ru,name_en,is_deleted) VALUES
 ('100000000',1100000000,NULL,2,0,'Абай облысы','область Абай','oblast Abay',FALSE),
 ('150000000',1150000000,NULL,2,0,'Ақтөбе облысы','Актюбинская область','Aktyubinskaya oblast',FALSE),
 ('190000000',1190000000,NULL,2,0,'Алматы облысы','Алматинская область','Almatinskaya oblast',FALSE),
 ('230000000',1230000000,NULL,2,0,'Атырау облысы','Атырауская область','Atyrauskaya oblast',FALSE),
 ('270000000',1270000000,NULL,2,0,'Батыс Қазақстан облысы','Западно-Казахстанская область','Zapadno-Kazakhstanskaya oblast',FALSE),
 ('310000000',1310000000,NULL,2,0,'Жамбыл облысы','Жамбылская область','Zhambylskaya oblast',FALSE),
 ('330000000',1330000000,NULL,2,0,'Жетісу облысы','область Жетісу','oblast Zhetisu',FALSE),
 ('350000000',1350000000,NULL,2,0,'Қарағанды облысы','Карагандинская область','Karagandinskaya oblast',FALSE),
 ('390000000',1390000000,NULL,2,0,'Қостанай облысы','Костанайская область','Kostanayskaya oblast',FALSE),
 ('470000000',1470000000,NULL,2,0,'Маңғыстау облысы','Мангистауская область','Mangistauskaya oblast',FALSE),
 ('550000000',1550000000,NULL,2,0,'Павлодар облысы','Павлодарская область','Pavlodarskaya oblast',FALSE),
 ('590000000',1590000000,NULL,2,0,'Солтүстік Қазақстан облысы','Северо-Казахстанская область','Severo-Kazakhstanskaya oblast',FALSE),
 ('610000000',1610000000,NULL,2,0,'Түркістан облысы','Туркестанская область','Turkestanskaya oblast',FALSE),
 ('620000000',1620000000,NULL,2,0,'Ұлытау облысы','область Ұлытау','oblast Ulytau',FALSE),
 ('630000000',1630000000,NULL,2,0,'Шығыс Қазақстан облысы','Восточно-Казахстанская область','Vostochno-Kazakhstanskaya oblast',FALSE),
 ('710000000',1710000000,NULL,2,1,'Астана қ.','г.Астана','g.Astana',FALSE),
 ('750000000',1750000000,NULL,2,1,'Алматы қ.','г.Алматы','g.Almaty',FALSE),
 ('790000000',1790000000,NULL,2,1,'Шымкент қ.','г.Шымкент','g.Shymkent',FALSE),
 ('101000000',1101000000,1100000000,3,1,'Семей Қ.Ә.','Семей Г.А.','Semey G.A.',FALSE),
 ('101800000',1101800000,1100000000,3,1,'Курчатов Қ.Ә.','Курчатов Г.А.','Kurchatov G.A.',FALSE),
 ('103200000',1103200000,1100000000,3,2,'Абай ауданы','Абайский район','Abayskiy rayon',FALSE),
 ('103400000',1103400000,1100000000,3,2,'Ақсуат ауданы','район Ақсуат','rayon Aqsuat',FALSE),
 ('103600000',1103600000,1100000000,3,4,'Аягөз ауданы','Аягозский район','Ayagozskiy rayon',FALSE),
 ('103800000',1103800000,1100000000,3,2,'Бесқарағай ауданы','Бескарагайский район','Beskaragayskiy rayon',FALSE),
 ('104000000',1104000000,1100000000,3,2,'Бородулиха ауданы','Бородулихинский район','Borodulikhinskiy rayon',FALSE),
 ('104100000',1104100000,1100000000,3,2,'Жаңасемей ауданы','район Жаңасемей','rayon Zhangasemey',FALSE),
 ('104200000',1104200000,1100000000,3,4,'Жарма ауданы','Жарминский район','Zharminskiy rayon',FALSE),
 ('104400000',1104400000,1100000000,3,2,'Көкпекті ауданы','Кокпектинский район','Kokpektinskiy rayon',FALSE),
 ('104500000',1104500000,1100000000,3,2,'Мақаншы ауданы','район Мақаншы','rayon Maqanshy',FALSE),
 ('104600000',1104600000,1100000000,3,2,'Үржар ауданы','Урджарский район','Urdzharskiy rayon',FALSE),
 ('111000000',1111000000,1,3,3,'Көкшетау Қ.Ә.','Кокшетау Г.А.','Kokshetau G.A.',FALSE),
 ('111600000',1111600000,1,3,3,'Қосшы Қ.Ә.','Косшы Г.А.','Kosshy G.A.',FALSE),
 ('111800000',1111800000,1,3,3,'Степногор Қ.Ә.','Степногорск Г.А.','Stepnogorsk G.A.',FALSE),
 ('113200000',1113200000,1,3,4,'Ақкөл ауданы','Аккольский район','Akkolskiy rayon',FALSE),
 ('113400000',1113400000,1,3,2,'Аршалы ауданы','Аршалынский район','Arshalynskiy rayon',FALSE),
 ('113600000',1113600000,1,3,2,'Астрахан ауданы','Астраханский район','Astrakhanskiy rayon',FALSE),
 ('113800000',1113800000,1,3,4,'Атбасар ауданы','Атбасарский район','Atbasarskiy rayon',FALSE),
 ('114000000',1114000000,1,3,4,'Бұланды ауданы','Буландынский район','Bulandynskiy rayon',FALSE),
 ('114400000',1114400000,1,3,2,'Егіндікөл ауданы','Егиндыкольский район','Egindykolskiy rayon',FALSE),
 ('114500000',1114500000,1,3,4,'Біржан сал ауданы','район Биржан сал','rayon Birzhan sal',FALSE),
 ('114600000',1114600000,1,3,4,'Ерейментау ауданы','Ерейментауский район','Ereymentauskiy rayon',FALSE),
 ('114800000',1114800000,1,3,4,'Есіл ауданы','Есильский район','Esilskiy rayon',FALSE),
 ('115200000',1115200000,1,3,2,'Жақсы ауданы','Жаксынский район','Zhaksynskiy rayon',FALSE),
 ('115400000',1115400000,1,3,4,'Жарқайың ауданы','Жаркаинский район','Zharkainskiy rayon',FALSE),
 ('115600000',1115600000,1,3,2,'Зеренді ауданы','Зерендинский район','Zerendinskiy rayon',FALSE),
 ('116000000',1116000000,1,3,2,'Қорғалжын ауданы','Коргалжынский район','Korgalzhynskiy rayon',FALSE),
 ('116400000',1116400000,1,3,2,'Сандықтау ауданы','Сандыктауский район','Sandyktauskiy rayon',FALSE),
 ('116600000',1116600000,1,3,2,'Целиноград ауданы','Целиноградский район','Tselinogradskiy rayon',FALSE),
 ('116800000',1116800000,1,3,2,'Шортанды ауданы','Шортандинский район','Shortandinskiy rayon',FALSE),
 ('117000000',1117000000,1,3,4,'Бурабай ауданы','Бурабайский район','Burabayskiy rayon',FALSE),
 ('151000000',1151000000,1150000000,3,1,'Ақтөбе Қ.Ә.','Актобе Г.А.','Aktobe G.A.',FALSE),
 ('153200000',1153200000,1150000000,3,4,'Алға ауданы','Алгинский район','Alginskiy rayon',FALSE),
 ('153400000',1153400000,1150000000,3,2,'Әйтеке би ауданы','Айтекебийский район','Aytekebiyskiy rayon',FALSE),
 ('153600000',1153600000,1150000000,3,2,'Байғанин ауданы','Байганинский район','Bayganinskiy rayon',FALSE),
 ('154000000',1154000000,1150000000,3,2,'Қарғалы ауданы','Каргалинский район','Kargalinskiy rayon',FALSE),
 ('154200000',1154200000,1150000000,3,2,'Қобда ауданы','Хобдинский район','Khobdinskiy rayon',FALSE),
 ('154600000',1154600000,1150000000,3,2,'Мәртөк ауданы','Мартукский район','Martukskiy rayon',FALSE),
 ('154800000',1154800000,1150000000,3,4,'Мұғалжар ауданы','Мугалжарский район','Mugalzharskiy rayon',FALSE),
 ('155200000',1155200000,1150000000,3,2,'Ойыл ауданы','Уилский район','Uilskiy rayon',FALSE),
 ('155600000',1155600000,1150000000,3,4,'Темір ауданы','Темирский район','Temirskiy rayon',FALSE),
 ('156000000',1156000000,1150000000,3,4,'Хромтау ауданы','Хромтауский район','Khromtauskiy rayon',FALSE),
 ('156400000',1156400000,1150000000,3,4,'Шалқар ауданы','Шалкарский район','Shalkarskiy rayon',FALSE),
 ('156800000',1156800000,1150000000,3,2,'Ырғыз ауданы','Иргизский район','Irgizskiy rayon',FALSE),
 ('191000000',1191000000,1190000000,3,3,'Қонаев Қ.Ә.','Қонаев Г.А.','Qonaev G.A.',FALSE),
 ('191800000',1191800000,1190000000,3,1,'Алатау Қ.Ә.','Алатау Г.А.','Alatau G.A.',FALSE),
 ('193600000',1193600000,1190000000,3,2,'Балқаш ауданы','Балхашский район','Balkhashskiy rayon',FALSE),
 ('194000000',1194000000,1190000000,3,4,'Еңбекшіқазақ ауданы','Енбекшиказахский район','Enbekshikazakhskiy rayon',FALSE),
 ('194200000',1194200000,1190000000,3,2,'Жамбыл ауданы','Жамбылский район','Zhambylskiy rayon',FALSE),
 ('194400000',1194400000,1190000000,3,2,'Кеген ауданы','Кегенский район','Kegenskiy rayon',FALSE),
 ('195200000',1195200000,1190000000,3,4,'Қарасай ауданы','Карасайский район','Karasayskiy rayon',FALSE),
 ('195800000',1195800000,1190000000,3,2,'Райымбек ауданы','Райымбекский район','Rayymbekskiy rayon',FALSE),
 ('196200000',1196200000,1190000000,3,4,'Талғар ауданы','Талгарский район','Talgarskiy rayon',FALSE),
 ('196600000',1196600000,1190000000,3,2,'Ұйғыр ауданы','Уйгурский район','Uygurskiy rayon',FALSE),
 ('196800000',1196800000,1190000000,3,2,'Іле ауданы','Илийский район','Iliyskiy rayon',FALSE),
 ('231000000',1231000000,1230000000,3,3,'Атырау Қ.Ә.','Атырау Г.А.','Atyrau G.A.',FALSE),
 ('233600000',1233600000,1230000000,3,4,'Жылыой ауданы','Жылыойский район','Zhylyoyskiy rayon',FALSE),
 ('234000000',1234000000,1230000000,3,2,'Индер ауданы','Индерский район','Inderskiy rayon',FALSE),
 ('234200000',1234200000,1230000000,3,2,'Исатай ауданы','Исатайский район','Isatayskiy rayon',FALSE),
 ('234600000',1234600000,1230000000,3,2,'Құрманғазы ауданы','Курмангазинский район','Kurmangazinskiy rayon',FALSE),
 ('234800000',1234800000,1230000000,3,2,'Қызылқоға ауданы','Кзылкогинский район','Kzylkoginskiy rayon',FALSE),
 ('235200000',1235200000,1230000000,3,2,'Мақат ауданы','Макатский район','Makatskiy rayon',FALSE),
 ('235600000',1235600000,1230000000,3,2,'Махамбет ауданы','Махамбетский район','Makhambetskiy rayon',FALSE),
 ('271000000',1271000000,1270000000,3,3,'Орал Қ.Ә.','Уральск Г.А.','Uralsk G.A.',FALSE),
 ('273200000',1273200000,1270000000,3,2,'Ақжайық ауданы','Акжаикский район','Akzhaikskiy rayon',FALSE),
 ('273600000',1273600000,1270000000,3,4,'Бөрлі ауданы','Бурлинский район','Burlinskiy rayon',FALSE),
 ('274000000',1274000000,1270000000,3,2,'Жаңақала ауданы','Жангалинский район','Zhangalinskiy rayon',FALSE),
 ('274200000',1274200000,1270000000,3,2,'Жәнібек ауданы','Жанибекский район','Zhanibekskiy rayon',FALSE),
 ('274400000',1274400000,1270000000,3,2,'Бәйтерек ауданы','район Бәйтерек','rayon Bayterek',FALSE),
 ('274800000',1274800000,1270000000,3,2,'Казталовка ауданы','Казталовский район','Kaztalovskiy rayon',FALSE),
 ('275000000',1275000000,1270000000,3,2,'Қаратөбе ауданы','Каратобинский район','Karatobinskiy rayon',FALSE),
 ('275400000',1275400000,1270000000,3,2,'Бөкейордасы ауданы','Бокейординский район','Bokeyordinskiy rayon',FALSE),
 ('275800000',1275800000,1270000000,3,2,'Сырым ауданы','Сырымский район','Syrymskiy rayon',FALSE),
 ('276000000',1276000000,1270000000,3,2,'Тасқала ауданы','Таскалинский район','Taskalinskiy rayon',FALSE),
 ('276200000',1276200000,1270000000,3,2,'Теректі ауданы','Теректинский район','Terektinskiy rayon',FALSE),
 ('276600000',1276600000,1270000000,3,2,'Шыңғырлау ауданы','Чингирлауский район','Chingirlauskiy rayon',FALSE),
 ('311000000',1311000000,1310000000,3,1,'Тараз Қ.Ә.','Тараз Г.А.','Taraz G.A.',FALSE),
 ('313600000',1313600000,1310000000,3,2,'Байзақ ауданы','Байзакский район','Bayzakskiy rayon',FALSE),
 ('314000000',1314000000,1310000000,3,2,'Жамбыл ауданы','Жамбылский район','Zhambylskiy rayon',FALSE),
 ('314200000',1314200000,1310000000,3,2,'Жуалы ауданы','Жуалынский район','Zhualynskiy rayon',FALSE),
 ('314800000',1314800000,1310000000,3,2,'Қордай ауданы','Кордайский район','Kordayskiy rayon',FALSE),
 ('315000000',1315000000,1310000000,3,2,'Тұрар Рысқұлов ауданы','район Турара Рыскулова','rayon Turara Ryskulova',FALSE),
 ('315400000',1315400000,1310000000,3,2,'Мерке ауданы','Меркенский район','Merkenskiy rayon',FALSE),
 ('315600000',1315600000,1310000000,3,2,'Мойынқұм ауданы','Мойынкумский район','Moyynkumskiy rayon',FALSE),
 ('316000000',1316000000,1310000000,3,4,'Сарысу ауданы','Сарысуский район','Sarysuskiy rayon',FALSE),
 ('316200000',1316200000,1310000000,3,4,'Талас ауданы','Таласский район','Talasskiy rayon',FALSE),
 ('316600000',1316600000,1310000000,3,4,'Шу ауданы','Шуский район','Shuskiy rayon',FALSE),
 ('331000000',1331000000,1330000000,3,3,'Талдықорған Қ.Ә.','Талдыкорган Г.А.','Taldykorgan G.A.',FALSE),
 ('331800000',1331800000,1330000000,3,3,'Текелі Қ.Ә.','Текели Г.А.','Tekeli G.A.',FALSE),
 ('333200000',1333200000,1330000000,3,2,'Ақсу ауданы','Аксуский район','Aksuskiy rayon',FALSE),
 ('333400000',1333400000,1330000000,3,4,'Алакөл ауданы','Алакольский район','Alakolskiy rayon',FALSE),
 ('333600000',1333600000,1330000000,3,2,'Ескелді ауданы','Ескельдинский район','Eskeldinskiy rayon',FALSE),
 ('334000000',1334000000,1330000000,3,2,'Кербұлақ ауданы','Кербулакский район','Kerbulakskiy rayon',FALSE),
 ('334200000',1334200000,1330000000,3,2,'Көксу ауданы','Коксуский район','Koksuskiy rayon',FALSE),
 ('334400000',1334400000,1330000000,3,4,'Қаратал ауданы','Каратальский район','Karatalskiy rayon',FALSE),
 ('334600000',1334600000,1330000000,3,4,'Панфилов ауданы','Панфиловский район','Panfilovskiy rayon',FALSE),
 ('334800000',1334800000,1330000000,3,4,'Сарқан ауданы','Сарканский район','Sarkanskiy rayon',FALSE),
 ('351000000',1351000000,1350000000,3,3,'Қарағанды Қ.Ә.','Караганда Г.А.','Karaganda G.A.',FALSE),
 ('351600000',1351600000,1350000000,3,3,'Балқаш Қ.Ә.','Балхаш Г.А.','Balkhash G.A.',FALSE),
 ('352100000',1352100000,1350000000,3,1,'Приозерск Қ.Ә.','Приозерск Г.А.','Priozersk G.A.',FALSE),
 ('352200000',1352200000,1350000000,3,3,'Саран Қ.Ә.','Сарань Г.А.','Saran G.A.',FALSE),
 ('352400000',1352400000,1350000000,3,3,'Теміртау Қ.Ә.','Темиртау Г.А.','Temirtau G.A.',FALSE),
 ('352800000',1352800000,1350000000,3,3,'Шахтинск Қ.Ә.','Шахтинск Г.А.','Shakhtinsk G.A.',FALSE),
 ('353200000',1353200000,1350000000,3,4,'Абай ауданы','Абайский район','Abayskiy rayon',FALSE),
 ('353600000',1353600000,1350000000,3,2,'Ақтоғай ауданы','Актогайский район','Aktogayskiy rayon',FALSE),
 ('354000000',1354000000,1350000000,3,2,'Бұқар жырау ауданы','Бухар-Жырауский район','Bukhar-Zhyrauskiy rayon',FALSE),
 ('354800000',1354800000,1350000000,3,4,'Қарқаралы ауданы','Каркаралинский район','Karkaralinskiy rayon',FALSE),
 ('355200000',1355200000,1350000000,3,2,'Нұра ауданы','Нуринский район','Nurinskiy rayon',FALSE),
 ('355600000',1355600000,1350000000,3,2,'Осакаров ауданы','Осакаровский район','Osakarovskiy rayon',FALSE),
 ('356400000',1356400000,1350000000,3,2,'Шет ауданы','Шетский район','Shetskiy rayon',FALSE),
 ('391000000',1391000000,1390000000,3,1,'Қостанай Қ.Ә.','Костанай Г.А.','Kostanay G.A.',FALSE),
 ('391600000',1391600000,1390000000,3,3,'Арқалық Қ.Ә.','Аркалык Г.А.','Arkalyk G.A.',FALSE),
 ('392000000',1392000000,1390000000,3,3,'Лисаков Қ.Ә.','Лисаковск Г.А.','Lisakovsk G.A.',FALSE),
 ('392400000',1392400000,1390000000,3,3,'Рудный Қ.Ә.','Рудный Г.А.','Rudnyy G.A.',FALSE),
 ('393200000',1393200000,1390000000,3,2,'Алтынсарин ауданы','Алтынсаринский район','Altynsarinskiy rayon',FALSE),
 ('393400000',1393400000,1390000000,3,2,'Амангелді ауданы','Амангельдинский район','Amangeldinskiy rayon',FALSE),
 ('393600000',1393600000,1390000000,3,2,'Әулиекөл ауданы','Аулиекольский район','Auliekolskiy rayon',FALSE),
 ('394000000',1394000000,1390000000,3,2,'Денисов ауданы','Денисовский район','Denisovskiy rayon',FALSE),
 ('394200000',1394200000,1390000000,3,2,'Жангелдин ауданы','Жангельдинский район','Zhangeldinskiy rayon',FALSE),
 ('394400000',1394400000,1390000000,3,4,'Жітіқара ауданы','Житикаринский район','Zhitikarinskiy rayon',FALSE),
 ('394800000',1394800000,1390000000,3,2,'Қамысты ауданы','Камыстинский район','Kamystinskiy rayon',FALSE),
 ('395000000',1395000000,1390000000,3,2,'Қарабалық ауданы','Карабалыкский район','Karabalykskiy rayon',FALSE),
 ('395200000',1395200000,1390000000,3,2,'Қарасу ауданы','Карасуский район','Karasuskiy rayon',FALSE),
 ('395400000',1395400000,1390000000,3,4,'Қостанай ауданы','Костанайский район','Kostanayskiy rayon',FALSE),
 ('395600000',1395600000,1390000000,3,2,'Меңдіқара ауданы','Мендыкаринский район','Mendykarinskiy rayon',FALSE),
 ('395800000',1395800000,1390000000,3,2,'Наурызым ауданы','Наурзумский район','Naurzumskiy rayon',FALSE),
 ('396200000',1396200000,1390000000,3,2,'Сарыкөл ауданы','Сарыкольский район','Sarykolskiy rayon',FALSE),
 ('396400000',1396400000,1390000000,3,2,'Бейімбет Майлин ауданы','район Беимбета Майлина','rayon Beimbeta Maylina',FALSE),
 ('396600000',1396600000,1390000000,3,2,'Ұзынкөл ауданы','Узункольский район','Uzunkolskiy rayon',FALSE),
 ('396800000',1396800000,1390000000,3,2,'Федоров ауданы','Федоровский район','Fedorovskiy rayon',FALSE),
 ('433600000',1433600000,10012,3,2,'Жалағаш ауданы','Жалагашский район','Zhalagashskiy rayon',FALSE),
 ('434000000',1434000000,10012,3,2,'Жаңақорған ауданы','Жанакорганский район','Zhanakorganskiy rayon',FALSE),
 ('434400000',1434400000,10012,3,4,'Қазалы ауданы','Казалинский район','Kazalinskiy rayon',FALSE),
 ('434600000',1434600000,10012,3,2,'Қармақшы ауданы','Кармакшинский район','Karmakshinskiy rayon',FALSE),
 ('434800000',1434800000,10012,3,2,'Сырдария ауданы','Сырдарьинский район','Syrdarinskiy rayon',FALSE),
 ('435200000',1435200000,10012,3,2,'Шиелі ауданы','Шиелийский район','Shieliyskiy rayon',FALSE),
 ('471000000',1471000000,1470000000,3,3,'Ақтау Г.А.','Актау Г.А.','Aktau G.A.',FALSE),
 ('471800000',1471800000,1470000000,3,3,'Жаңаөзен Қ.Ә.','Жанаозен Г.А.','Zhanaozen G.A.',FALSE),
 ('473600000',1473600000,1470000000,3,2,'Бейнеу ауданы','Бейнеуский район','Beyneuskiy rayon',FALSE),
 ('474200000',1474200000,1470000000,3,2,'Қарақия ауданы','Каракиянский район','Karakiyanskiy rayon',FALSE),
 ('474600000',1474600000,1470000000,3,2,'Маңғыстау ауданы','Мангистауский район','Mangistauskiy rayon',FALSE),
 ('475000000',1475000000,1470000000,3,2,'Мұнайлы ауданы','Мунайлинский район','Munaylinskiy rayon',FALSE),
 ('475200000',1475200000,1470000000,3,4,'Түпқараған ауданы','Тупкараганский район','Tupkaraganskiy rayon',FALSE),
 ('551000000',1551000000,1550000000,3,3,'Павлодар Қ.Ә.','Павлодар Г.А.','Pavlodar G.A.',FALSE),
 ('551600000',1551600000,1550000000,3,3,'Ақсу Қ.Ә.','Аксу Г.А.','Aksu G.A.',FALSE),
 ('552200000',1552200000,1550000000,3,3,'Екібастұз Қ.Ә.','Экибастуз Г.А.','Ekibastuz G.A.',FALSE),
 ('553200000',1553200000,1550000000,3,2,'Ақтоғай ауданы','Актогайский район','Aktogayskiy rayon',FALSE),
 ('553600000',1553600000,1550000000,3,2,'Баянауыл ауданы','Баянаульский район','Bayanaulskiy rayon',FALSE),
 ('554200000',1554200000,1550000000,3,2,'Железин ауданы','Железинский район','Zhelezinskiy rayon',FALSE),
 ('554600000',1554600000,1550000000,3,2,'Ертіс ауданы','Иртышский район','Irtyshskiy rayon',FALSE),
 ('554800000',1554800000,1550000000,3,2,'Тереңкөл ауданы','район Тереңкөл','rayon Terengkol',FALSE),
 ('555200000',1555200000,1550000000,3,2,'Аққулы ауданы','район Аққулы','rayon Aqquly',FALSE),
 ('555600000',1555600000,1550000000,3,2,'Май ауданы','Майский район','Mayskiy rayon',FALSE),
 ('556000000',1556000000,1550000000,3,2,'Павлодар ауданы','Павлодарский район','Pavlodarskiy rayon',FALSE),
 ('556400000',1556400000,1550000000,3,2,'Успен ауданы','Успенский район','Uspenskiy rayon',FALSE),
 ('556800000',1556800000,1550000000,3,2,'Шарбақты ауданы','Щербактинский район','Shcherbaktinskiy rayon',FALSE),
 ('591000000',1591000000,1590000000,3,1,'Петропавл Қ.Ә.','Петропавловск Г.А.','Petropavlovsk G.A.',FALSE),
 ('593200000',1593200000,1590000000,3,2,'Айыртау ауданы','Айыртауский район','Ayyrtauskiy rayon',FALSE),
 ('593400000',1593400000,1590000000,3,2,'Ақжар ауданы','Акжарский район','Akzharskiy rayon',FALSE),
 ('593600000',1593600000,1590000000,3,4,'Мағжан Жұмабаев ауданы','Район Магжана Жумабаева','Rayon Magzhana Zhumabaeva',FALSE),
 ('594200000',1594200000,1590000000,3,2,'Есіл ауданы','Есильский район','Esilskiy rayon',FALSE),
 ('594600000',1594600000,1590000000,3,2,'Жамбыл ауданы','Жамбылский район','Zhambylskiy rayon',FALSE),
 ('595000000',1595000000,1590000000,3,2,'Қызылжар ауданы','Кызылжарский район','Kyzylzharskiy rayon',FALSE),
 ('595200000',1595200000,1590000000,3,4,'Мамлют ауданы','Мамлютский район','Mamlyutskiy rayon',FALSE),
 ('595600000',1595600000,1590000000,3,4,'Шал ақын ауданы','Район Шал акына','Rayon Shal akyna',FALSE),
 ('595800000',1595800000,1590000000,3,2,'Аққайың ауданы','Аккайынский район','Akkayynskiy rayon',FALSE),
 ('596000000',1596000000,1590000000,3,4,'Тайынша ауданы','Тайыншинский район','Tayynshinskiy rayon',FALSE),
 ('596200000',1596200000,1590000000,3,2,'Тимирязев ауданы','Тимирязевский район','Timiryazevskiy rayon',FALSE),
 ('596400000',1596400000,1590000000,3,2,'Уәлиханов ауданы','Уалихановский район','Ualikhanovskiy rayon',FALSE),
 ('596600000',1596600000,1590000000,3,2,'Ғабит Мүсірепов атын.ауданы','Район им.Габита Мусрепова','Rayon im.Gabita Musrepova',FALSE),
 ('611000000',1611000000,1610000000,3,1,'Түркістан Қ.Ә.','Туркестан Г.А.','Turkestan G.A.',FALSE),
 ('611600000',1611600000,1610000000,3,3,'Арыс Қ.Ә.','Арысь Г.А.','Arys G.A.',FALSE),
 ('612000000',1612000000,1610000000,3,3,'Кентау Қ.Ә.','Кентау Г.А.','Kentau G.A.',FALSE),
 ('613600000',1613600000,1610000000,3,2,'Бәйдібек ауданы','район Байдибека','rayon Baydibeka',FALSE),
 ('613800000',1613800000,1610000000,3,4,'Жетісай ауданы','Жетысайский район','Zhetysayskiy rayon',FALSE),
 ('613900000',1613900000,1610000000,3,2,'Келес ауданы','Келесский район','Kelesskiy rayon',FALSE),
 ('614000000',1614000000,1610000000,3,2,'Қазығұрт ауданы','Казыгуртский район','Kazygurtskiy rayon',FALSE),
 ('614400000',1614400000,1610000000,3,2,'Мақтаарал ауданы','Мактааральский район','Maktaaralskiy rayon',FALSE),
 ('614600000',1614600000,1610000000,3,2,'Ордабасы ауданы','Ордабасынский район','Ordabasynskiy rayon',FALSE),
 ('614800000',1614800000,1610000000,3,2,'Отырар ауданы','Отрарский район','Otrarskiy rayon',FALSE),
 ('615200000',1615200000,1610000000,3,2,'Сайрам ауданы','Сайрамский район','Sayramskiy rayon',FALSE),
 ('615400000',1615400000,1610000000,3,4,'Сарыағаш ауданы','Сарыагашский район','Saryagashskiy rayon',FALSE),
 ('615500000',1615500000,1610000000,3,2,'Сауран ауданы','район Сауран','rayon Sauran',FALSE),
 ('615600000',1615600000,1610000000,3,2,'Созақ ауданы','Сузакский район','Suzakskiy rayon',FALSE),
 ('615800000',1615800000,1610000000,3,4,'Төле би ауданы','Толебийский район','Tolebiyskiy rayon',FALSE),
 ('616000000',1616000000,1610000000,3,2,'Түлкібас ауданы','Тюлькубасский район','Tyulkubasskiy rayon',FALSE),
 ('616400000',1616400000,1610000000,3,4,'Шардара ауданы','Шардаринский район','Shardarinskiy rayon',FALSE),
 ('621000000',1621000000,1620000000,3,3,'Жезқазған Қ.Ә.','Жезказган Г.А.','Zhezkazgan G.A.',FALSE),
 ('621800000',1621800000,1620000000,3,3,'Қаражал Қ.Ә.','Каражал Г.А.','Karazhal G.A.',FALSE),
 ('622000000',1622000000,1620000000,3,3,'Сәтбаев Қ.Ә.','Сатпаев Г.А.','Satpaev G.A.',FALSE),
 ('623600000',1623600000,1620000000,3,2,'Жаңаарқа ауданы','Жанааркинский район','Zhanaarkinskiy rayon',FALSE),
 ('623800000',1623800000,1620000000,3,2,'Ұлытау ауданы','Улытауский район','Ulytauskiy rayon',FALSE),
 ('631000000',1631000000,1630000000,3,3,'Өскемен Қ.Ә.','Усть-Каменогорск Г.А.','Ust-Kamenogorsk G.A.',FALSE),
 ('632400000',1632400000,1630000000,3,3,'Риддер Қ.Ә.','Риддер Г.А.','Ridder G.A.',FALSE),
 ('634000000',1634000000,1630000000,3,2,'Глубокое ауданы','Глубоковский район','Glubokovskiy rayon',FALSE),
 ('634600000',1634600000,1630000000,3,4,'Зайсан ауданы','Зайсанский район','Zaysanskiy rayon',FALSE),
 ('634800000',1634800000,1630000000,3,4,'Алтай ауданы','район Алтай','rayon Altay',FALSE),
 ('635200000',1635200000,1630000000,3,2,'Күршім ауданы','Курчумский район','Kurchumskiy rayon',FALSE),
 ('635400000',1635400000,1630000000,3,2,'Катонқарағай ауданы','Катон-Карагайский район','Katon-Karagayskiy rayon',FALSE),
 ('635500000',1635500000,1630000000,3,2,'Марқакөл ауданы','район Марқакөл','rayon Marqakol',FALSE),
 ('635600000',1635600000,1630000000,3,2,'Самар ауданы','район Самар','rayon Samar',FALSE),
 ('635800000',1635800000,1630000000,3,2,'Тарбағатай ауданы','Тарбагатайский район','Tarbagatayskiy rayon',FALSE),
 ('636200000',1636200000,1630000000,3,2,'Ұлан ауданы','Уланский район','Ulanskiy rayon',FALSE),
 ('636300000',1636300000,1630000000,3,2,'Үлкен Нарын ауданы','район Үлкен Нарын','rayon Ulken Naryn',FALSE),
 ('636800000',1636800000,1630000000,3,4,'Шемонаиха ауданы','Шемонаихинский район','Shemonaikhinskiy rayon',FALSE),
 ('711110000',1711110000,1710000000,3,1,'Алматы ауданы','район Алматы','rayon Almaty',FALSE),
 ('711210000',1711210000,1710000000,3,1,'Есіл ауданы','район Есиль','rayon Esil',FALSE),
 ('711310000',1711310000,1710000000,3,1,'Сарыарқа ауданы','район Сарыарка','rayon Saryarka',FALSE),
 ('711410000',1711410000,1710000000,3,1,'Байқоңыр ауданы','район Байқоңыр','rayon Bayqongyr',FALSE),
 ('711510000',1711510000,1710000000,3,1,'Нұра ауданы','район Нұра','rayon Nura',FALSE),
 ('711610000',1711610000,1710000000,3,1,'Сарайшық ауданы','район Сарайшық','rayon Sarayshyq',FALSE),
 ('751110000',1751110000,1750000000,3,1,'Алмалы ауданы','Алмалинский район','Almalinskiy rayon',FALSE),
 ('751210000',1751210000,1750000000,3,1,'Алатау ауданы','Алатауский район','Alatauskiy rayon',FALSE),
 ('751310000',1751310000,1750000000,3,1,'Әуезов ауданы','Ауэзовский район','Auezovskiy rayon',FALSE),
 ('751410000',1751410000,1750000000,3,1,'Бостандық ауданы','Бостандыкский район','Bostandykskiy rayon',FALSE),
 ('751510000',1751510000,1750000000,3,1,'Жетісу ауданы','Жетысуский район','Zhetysuskiy rayon',FALSE),
 ('751710000',1751710000,1750000000,3,1,'Медеу ауданы','Медеуский район','Medeuskiy rayon',FALSE),
 ('751810000',1751810000,1750000000,3,1,'Наурызбай ауданы','Наурызбайский район','Nauryzbayskiy rayon',FALSE),
 ('751910000',1751910000,1750000000,3,1,'Түрксіб ауданы','Турксибский район','Turksibskiy rayon',FALSE),
 ('791110000',1791110000,1790000000,3,1,'Абай ауданы','Абайский район','Abayskiy rayon',FALSE),
 ('791310000',1791310000,1790000000,3,1,'Әл Фараби ауданы','Аль-Фарабийский район','Al-Farabiyskiy rayon',FALSE),
 ('791510000',1791510000,1790000000,3,1,'Еңбекші ауданы','Енбекшинский район','Enbekshinskiy rayon',FALSE),
 ('791710000',1791710000,1790000000,3,1,'Қаратау ауданы','Каратауский район','Karatauskiy rayon',FALSE),
 ('791910000',1791910000,1790000000,3,1,'Тұран ауданы','район Тұран','rayon Turan',FALSE),
 ('101010000',1101010000,1101000000,4,1,'Семей қ.','г.Семей','g.Semey',FALSE),
 ('101810000',1101810000,1101800000,4,1,'Курчатов қ.','г.Курчатов','g.Kurchatov',FALSE),
 ('103620000',1103620000,1103600000,4,3,'Аягөз қ.ә.','Аягоз г.а.','Ayagoz g.a.',FALSE),
 ('104221000',1104221000,1104200000,4,3,'Шар қ.ә.','Шарская г.а.','Sharskaya g.a.',FALSE),
 ('111010000',1111010000,1111000000,4,1,'Көкшетау қ.','г.Кокшетау','g.Kokshetau',FALSE),
 ('111610000',1111610000,1111600000,4,1,'Қосшы қ.','г.Косшы','g.Kosshy',FALSE),
 ('113220000',1113220000,1113200000,4,3,'Ақкөл қ.ә.','Аккольская г.а.','Akkolskaya g.a.',FALSE),
 ('113820100',1113820100,1113800000,4,1,'Атбасар қ.','г.Атбасар','g.Atbasar',FALSE),
 ('114020100',1114020100,1114000000,4,1,'Макинск қ.','г.Макинск','g.Makinsk',FALSE),
 ('114520000',1114520000,1114500000,4,3,'Степняк қ.ә.','Степнякская г.а.','Stepnyakskaya g.a.',FALSE),
 ('114620100',1114620100,1114600000,4,1,'Ерейментау қ.','г.Ерейментау','g.Ereymentau',FALSE),
 ('114820100',1114820100,1114800000,4,1,'Есіл қ.','г.Есиль','g.Esil',FALSE),
 ('115420100',1115420100,1115400000,4,1,'Державин қ.','г.Державинск','g.Derzhavinsk',FALSE),
 ('117020000',1117020000,1117000000,4,1,'Щучинск қ.ә.','Щучинская г.а.','Shchuchinskaya g.a.',FALSE),
 ('151010000',1151010000,1151000000,4,1,'Ақтөбе қ.','г.Актобе','g.Aktobe',FALSE),
 ('151011100',1151011100,1151000000,4,1,'Астана ауданы','район Астана','rayon Astana',FALSE),
 ('151013100',1151013100,1151000000,4,1,'Алматы ауданы','район Алматы','rayon Almaty',FALSE),
 ('153220000',1153220000,1153200000,4,1,'Алға қ.ә.','Алгинская г.а.','Alginskaya g.a.',FALSE),
 ('154820000',1154820000,1154800000,4,1,'Қандыағаш қ.ә.','Кандыагашская г.а.','Kandyagashskaya g.a.',FALSE),
 ('154823000',1154823000,1154800000,4,3,'Ембі қ.ә.','Эмбинская г.а.','Embinskaya g.a.',FALSE),
 ('154825000',1154825000,1154800000,4,1,'Жем қ.ә.','Жемская г.а.','Zhemskaya g.a.',FALSE),
 ('155621000',1155621000,1155600000,4,3,'Темір қ.ә.','Темирская г.а.','Temirskaya g.a.',FALSE),
 ('156020100',1156020100,1156000000,4,1,'Хромтау қ.','г.Хромтау','g.Khromtau',FALSE),
 ('156420000',1156420000,1156400000,4,1,'Шалқар қ.ә.','Шалкарская г.а.','Shalkarskaya g.a.',FALSE),
 ('191010000',1191010000,1191000000,4,1,'Қонаев қ.','г.Қонаев','g.Qonaev',FALSE),
 ('191810000',1191810000,1191800000,4,1,'Алатау қ.','г.Алатау','g.Alatau',FALSE),
 ('194020000',1194020000,1194000000,4,1,'Есік қ.ә.','Есикская г.а.','Esikskaya g.a.',FALSE),
 ('195220000',1195220000,1195200000,4,3,'Қаскелең қ.ә.','Каскеленская г.а.','Kaskelenskaya g.a.',FALSE),
 ('196220000',1196220000,1196200000,4,1,'Талғар қ.ә.','Талгарская г.а.','Talgarskaya g.a.',FALSE),
 ('231010000',1231010000,1231000000,4,1,'Атырау қ.','г.Атырау','g.Atyrau',FALSE),
 ('233620000',1233620000,1233600000,4,1,'Құлсары қ.ә.','Кульсаринская г.а.','Kulsarinskaya g.a.',FALSE),
 ('271010000',1271010000,1271000000,4,1,'Орал қ.','г.Уральск','g.Uralsk',FALSE),
 ('273620000',1273620000,1273600000,4,1,'Ақсай қ.ә.','Аксайская г.а.','Aksayskaya g.a.',FALSE),
 ('311010000',1311010000,1311000000,4,1,'Тараз қ.','г.Тараз','g.Taraz',FALSE),
 ('311011100',1311011100,1311000000,4,1,'Әулиеата ауданы','район Әулиеата','rayon Aulieata',FALSE),
 ('311013100',1311013100,1311000000,4,1,'Жібек жолы ауданы','район Жібек жолы','rayon Zhibek zholy',FALSE),
 ('316020100',1316020100,1316000000,4,1,'Жаңатас қ.','г.Жанатас','g.Zhanatas',FALSE),
 ('316220100',1316220100,1316200000,4,1,'Қаратау қ.','г.Каратау','g.Karatau',FALSE),
 ('316621000',1316621000,1316600000,4,1,'Шу қ.ә.','Шуская г.а.','Shuskaya g.a.',FALSE),
 ('331010000',1331010000,1331000000,4,1,'Талдықорған қ.','г.Талдыкорган','g.Taldykorgan',FALSE),
 ('331810000',1331810000,1331800000,4,1,'Текелі қ.','г.Текели','g.Tekeli',FALSE),
 ('333420000',1333420000,1333400000,4,3,'Үшарал қ.ә.','Ушаральская г.а.','Usharalskaya g.a.',FALSE),
 ('334420000',1334420000,1334400000,4,3,'Үштөбе қ.ә.','Уштобинская г.а.','Ushtobinskaya g.a.',FALSE),
 ('334620000',1334620000,1334600000,4,1,'Жаркент қ.ә.','Жаркентская г.а.','Zharkentskaya g.a.',FALSE),
 ('334820000',1334820000,1334800000,4,3,'Сарқан қ.ә.','Сарканская г.а.','Sarkanskaya g.a.',FALSE),
 ('351010000',1351010000,1351000000,4,1,'Қарағанды қ.','г.Караганда','g.Karaganda',FALSE),
 ('351011000',1351011000,1351000000,4,3,'Әлихан Бөкейхан а.ә.','Әлихан Бөкейхан р.а.','Alikhan Bokeykhan r.a.',FALSE),
 ('351013000',1351013000,1351000000,4,1,'Қазыбек би атын. а.ә.','р.а. им.Казыбек би','r.a. im.Kazybek bi',FALSE),
 ('351610000',1351610000,1351600000,4,1,'Балқаш қ.','г.Балхаш','g.Balkhash',FALSE),
 ('352110000',1352110000,1352100000,4,1,'Приозерск қ.','г.Приозерск','g.Priozersk',FALSE),
 ('352210000',1352210000,1352200000,4,1,'Саран қ.','г.Сарань','g.Saran',FALSE),
 ('352410000',1352410000,1352400000,4,1,'Теміртау қ.','г.Темиртау','g.Temirtau',FALSE),
 ('352810000',1352810000,1352800000,4,1,'Шахтинск қ.','г.Шахтинск','g.Shakhtinsk',FALSE),
 ('353220000',1353220000,1353200000,4,1,'Абай қ.ә.','Абайская г.а.','Abayskaya g.a.',FALSE),
 ('354820100',1354820100,1354800000,4,1,'Қарқаралы қ.','г.Каркаралинск','g.Karkaralinsk',FALSE),
 ('391010000',1391010000,1391000000,4,1,'Қостанай қ.','г.Костанай','g.Kostanay',FALSE),
 ('391610000',1391610000,1391600000,4,1,'Арқалық қ.','г.Аркалык','g.Arkalyk',FALSE),
 ('392010000',1392010000,1392000000,4,1,'Лисаков қ.','г.Лисаковск','g.Lisakovsk',FALSE),
 ('392410000',1392410000,1392400000,4,1,'Рудный қ.','г.Рудный','g.Rudnyy',FALSE),
 ('394420100',1394420100,1394400000,4,1,'Жітіқара қ.','г.Житикара','g.Zhitikara',FALSE),
 ('395420000',1395420000,1395400000,4,1,'Тобыл қ.ә.','Тобылская г.а.','Tobylskaya g.a.',FALSE),
 ('434423000',1434423000,1434400000,4,1,'Қазалы қ.ә.','Казалинская г.а.','Kazalinskaya g.a.',FALSE),
 ('471010000',1471010000,1471000000,4,1,'Ақтау қ.','г.Актау','g.Aktau',FALSE),
 ('471810000',1471810000,1471800000,4,1,'Жаңаөзен қ.','г.Жанаозен','g.Zhanaozen',FALSE),
 ('475220100',1475220100,1475200000,4,1,'Форт-Шевченко ќ.','г.Форт-Шевченко','g.Fort-Shevchenko',FALSE),
 ('551010000',1551010000,1551000000,4,1,'Павлодар қ.','г.Павлодар','g.Pavlodar',FALSE),
 ('551610000',1551610000,1551600000,4,1,'Ақсу қ.','г.Аксу','g.Aksu',FALSE),
 ('552210000',1552210000,1552200000,4,1,'Екібастұз қ.','г.Экибастуз','g.Ekibastuz',FALSE),
 ('591010000',1591010000,1591000000,4,1,'Петропавл қ.','г.Петропавловск','g.Petropavlovsk',FALSE),
 ('593620000',1593620000,1593600000,4,3,'Булаев қ.ә.','Булаевская г.а.','Bulaevskaya g.a.',FALSE),
 ('595220000',1595220000,1595200000,4,1,'Мамлют қ.ә.','Мамлютская г.а.','Mamlyutskaya g.a.',FALSE),
 ('595620000',1595620000,1595600000,4,1,'Сергеев қ.ә.','Сергеевская г.а.','Sergeevskaya g.a.',FALSE),
 ('596020000',1596020000,1596000000,4,1,'Тайынша қ.ә.','Тайыншинская г.а.','Tayynshinskaya g.a.',FALSE),
 ('611010000',1611010000,1611000000,4,1,'Түркістан қ.','г.Туркестан','g.Turkestan',FALSE),
 ('611610000',1611610000,1611600000,4,1,'Арыс қ.','г.Арысь','g.Arys',FALSE),
 ('612010000',1612010000,1612000000,4,1,'Кентау қ.','г.Кентау','g.Kentau',FALSE),
 ('613820100',1613820100,1613800000,4,1,'Жетісай қ.','г.Жетысай','g.Zhetysay',FALSE),
 ('615420100',1615420100,1615400000,4,1,'Сарыағаш қ.','г.Сарыагаш','g.Saryagash',FALSE),
 ('615820100',1615820100,1615800000,4,1,'Леңгір қ.','г.Ленгер','g.Lenger',FALSE),
 ('616420100',1616420100,1616400000,4,1,'Шардара қ.','г.Шардара','g.Shardara',FALSE),
 ('621010000',1621010000,1621000000,4,1,'Жезқазған қ.','г.Жезказган','g.Zhezkazgan',FALSE),
 ('621810000',1621810000,1621800000,4,1,'Қаражал қ.','г.Каражал','g.Karazhal',FALSE),
 ('622010000',1622010000,1622000000,4,1,'Сәтбаев қ.','г.Сатпаев','g.Satpaev',FALSE),
 ('631010000',1631010000,1631000000,4,1,'Өскемен қ.','г.Усть-Каменогорск','g.Ust-Kamenogorsk',FALSE),
 ('632410000',1632410000,1632400000,4,1,'Риддер қ.','г.Риддер','g.Ridder',FALSE),
 ('634620000',1634620000,1634600000,4,1,'Зайсан қ.ә.','Зайсанская г.а.','Zaysanskaya g.a.',FALSE),
 ('634820000',1634820000,1634800000,4,3,'Алтай қ.ә.','г.а.Алтай','g.a.Altay',FALSE),
 ('634821100',1634821100,1634800000,4,1,'Серебрянск қ.','г.Серебрянск','g.Serebryansk',FALSE),
 ('636820000',1636820000,1636800000,4,1,'Шемонаиха қ.ә.','Шемонаихинская г.а.','Shemonaikhinskaya g.a.',FALSE),
 ('103620100',1103620100,1103620000,5,1,'Аягөз қ.','г.Аягоз','g.Ayagoz',FALSE),
 ('104221100',1104221100,1104221000,5,1,'Шар қ.','г.Шар','g.Shar',FALSE),
 ('113220100',1113220100,1113220000,5,1,'Ақкөл қ.','г.Акколь','g.Akkol',FALSE),
 ('114520100',1114520100,1114520000,5,1,'Степняк қ.','г.Степняк','g.Stepnyak',FALSE),
 ('117020100',1117020100,1117020000,5,1,'Щучинск қ.','г.Щучинск','g.Shchuchinsk',FALSE),
 ('153220100',1153220100,1153220000,5,1,'Алға қ.','г.Алга','g.Alga',FALSE),
 ('154820100',1154820100,1154820000,5,1,'Қандыағаш қ.','г.Кандыагаш','g.Kandyagash',FALSE),
 ('154823100',1154823100,1154823000,5,1,'Ембі қ.','г.Эмба','g.Emba',FALSE),
 ('154825100',1154825100,1154825000,5,1,'Жем қ.','г.Жем','g.Zhem',FALSE),
 ('155621100',1155621100,1155621000,5,1,'Темір қ.','г.Темир','g.Temir',FALSE),
 ('156420100',1156420100,1156420000,5,1,'Шалқар қ.','г.Шалкар','g.Shalkar',FALSE),
 ('194020100',1194020100,1194020000,5,1,'Есік қ.','г.Есик','g.Esik',FALSE),
 ('195220100',1195220100,1195220000,5,1,'Қаскелең қ.','г.Каскелен','g.Kaskelen',FALSE),
 ('196220100',1196220100,1196220000,5,1,'Талғар қ.','г.Талгар','g.Talgar',FALSE),
 ('233620100',1233620100,1233620000,5,1,'Құлсары қ.','г.Кульсары','g.Kulsary',FALSE),
 ('273620100',1273620100,1273620000,5,1,'Ақсай қ.','г.Аксай','g.Aksay',FALSE),
 ('316621100',1316621100,1316621000,5,1,'Шу қ.','г.Шу','g.Shu',FALSE),
 ('333420100',1333420100,1333420000,5,1,'Үшарал қ.','г.Ушарал','g.Usharal',FALSE),
 ('334420100',1334420100,1334420000,5,1,'Үштөбе қ.','г.Уштобе','g.Ushtobe',FALSE),
 ('334620100',1334620100,1334620000,5,1,'Жаркент қ.','г.Жаркент','g.Zharkent',FALSE),
 ('334820100',1334820100,1334820000,5,1,'Сарқан қ.','г.Саркан','g.Sarkan',FALSE),
 ('351011100',1351011100,1351011000,5,1,'Әлихан Бөкейхан ауданы','район Әлихан Бөкейхан','rayon Alikhan Bokeykhan',FALSE),
 ('351013100',1351013100,1351013000,5,1,'Қазыбек би атын.ауданы','район им.Казыбек би','rayon im.Kazybek bi',FALSE),
 ('353220100',1353220100,1353220000,5,1,'Абай қ.','г.Абай','g.Abay',FALSE),
 ('395420100',1395420100,1395420000,5,1,'Тобыл қ.','г.Тобыл','g.Tobyl',FALSE),
 ('434423100',1434423100,1434423000,5,1,'Қазалы қ.','г.Казалинск','g.Kazalinsk',FALSE),
 ('593620100',1593620100,1593620000,5,1,'Булаев қ.','г.Булаево','g.Bulaevo',FALSE),
 ('595220100',1595220100,1595220000,5,1,'Мамлют қ.','г.Мамлютка','g.Mamlyutka',FALSE),
 ('595620100',1595620100,1595620000,5,1,'Сергеев қ.','г.Сергеевка','g.Sergeevka',FALSE),
 ('596020100',1596020100,1596020000,5,1,'Тайынша қ.','г.Тайынша','g.Tayynsha',FALSE),
 ('634620100',1634620100,1634620000,5,1,'Зайсан қ.','г.Зайсан','g.Zaysan',FALSE),
 ('634820100',1634820100,1634820000,5,1,'Алтай қ.','г.Алтай','g.Altay',FALSE),
 ('636820100',1636820100,1636820000,5,1,'Шемонайха қ.','г.Шемонаиха','g.Shemonaikha',FALSE);

-- Existing city row had parent_source_id=9, absent from the seed. Keep its own source_id=10.
UPDATE public.ref_kato_territory SET parent_source_id=1111800000 WHERE code='111810000' AND source_id=10 AND parent_source_id=9;

-- Rollback only before the new codes have been used by business operations; existing FK may reject deletion.
--rollback UPDATE public.ref_kato_territory SET parent_source_id=9 WHERE code='111810000' AND source_id=10 AND parent_source_id=1111800000;
--rollback DELETE FROM public.ref_kato_territory WHERE source_id IN (1103620100,1104221100,1113220100,1114520100,1117020100,1153220100,1154820100,1154823100,1154825100,1155621100,1156420100,1194020100,1195220100,1196220100,1233620100,1273620100,1316621100,1333420100,1334420100,1334620100,1334820100,1351011100,1351013100,1353220100,1395420100,1434423100,1593620100,1595220100,1595620100,1596020100,1634620100,1634820100,1636820100);
--rollback DELETE FROM public.ref_kato_territory WHERE source_id IN (1101010000,1101810000,1103620000,1104221000,1111010000,1111610000,1113220000,1113820100,1114020100,1114520000,1114620100,1114820100,1115420100,1117020000,1151010000,1151011100,1151013100,1153220000,1154820000,1154823000,1154825000,1155621000,1156020100,1156420000,1191010000,1191810000,1194020000,1195220000,1196220000,1231010000,1233620000,1271010000,1273620000,1311010000,1311011100,1311013100,1316020100,1316220100,1316621000,1331010000,1331810000,1333420000,1334420000,1334620000,1334820000,1351010000,1351011000,1351013000,1351610000,1352110000,1352210000,1352410000,1352810000,1353220000,1354820100,1391010000,1391610000,1392010000,1392410000,1394420100,1395420000,1434423000,1471010000,1471810000,1475220100,1551010000,1551610000,1552210000,1591010000,1593620000,1595220000,1595620000,1596020000,1611010000,1611610000,1612010000,1613820100,1615420100,1615820100,1616420100,1621010000,1621810000,1622010000,1631010000,1632410000,1634620000,1634820000,1634821100,1636820000);
--rollback DELETE FROM public.ref_kato_territory WHERE source_id IN (1101000000,1101800000,1103200000,1103400000,1103600000,1103800000,1104000000,1104100000,1104200000,1104400000,1104500000,1104600000,1111000000,1111600000,1111800000,1113200000,1113400000,1113600000,1113800000,1114000000,1114400000,1114500000,1114600000,1114800000,1115200000,1115400000,1115600000,1116000000,1116400000,1116600000,1116800000,1117000000,1151000000,1153200000,1153400000,1153600000,1154000000,1154200000,1154600000,1154800000,1155200000,1155600000,1156000000,1156400000,1156800000,1191000000,1191800000,1193600000,1194000000,1194200000,1194400000,1195200000,1195800000,1196200000,1196600000,1196800000,1231000000,1233600000,1234000000,1234200000,1234600000,1234800000,1235200000,1235600000,1271000000,1273200000,1273600000,1274000000,1274200000,1274400000,1274800000,1275000000,1275400000,1275800000,1276000000,1276200000,1276600000,1311000000,1313600000,1314000000,1314200000,1314800000,1315000000,1315400000,1315600000,1316000000,1316200000,1316600000,1331000000,1331800000,1333200000,1333400000,1333600000,1334000000,1334200000,1334400000,1334600000,1334800000,1351000000,1351600000,1352100000,1352200000,1352400000,1352800000,1353200000,1353600000,1354000000,1354800000,1355200000,1355600000,1356400000,1391000000,1391600000,1392000000,1392400000,1393200000,1393400000,1393600000,1394000000,1394200000,1394400000,1394800000,1395000000,1395200000,1395400000,1395600000,1395800000,1396200000,1396400000,1396600000,1396800000,1433600000,1434000000,1434400000,1434600000,1434800000,1435200000,1471000000,1471800000,1473600000,1474200000,1474600000,1475000000,1475200000,1551000000,1551600000,1552200000,1553200000,1553600000,1554200000,1554600000,1554800000,1555200000,1555600000,1556000000,1556400000,1556800000,1591000000,1593200000,1593400000,1593600000,1594200000,1594600000,1595000000,1595200000,1595600000,1595800000,1596000000,1596200000,1596400000,1596600000,1611000000,1611600000,1612000000,1613600000,1613800000,1613900000,1614000000,1614400000,1614600000,1614800000,1615200000,1615400000,1615500000,1615600000,1615800000,1616000000,1616400000,1621000000,1621800000,1622000000,1623600000,1623800000,1631000000,1632400000,1634000000,1634600000,1634800000,1635200000,1635400000,1635500000,1635600000,1635800000,1636200000,1636300000,1636800000,1711110000,1711210000,1711310000,1711410000,1711510000,1711610000,1751110000,1751210000,1751310000,1751410000,1751510000,1751710000,1751810000,1751910000,1791110000,1791310000,1791510000,1791710000,1791910000);
--rollback DELETE FROM public.ref_kato_territory WHERE source_id IN (1100000000,1150000000,1190000000,1230000000,1270000000,1310000000,1330000000,1350000000,1390000000,1470000000,1550000000,1590000000,1610000000,1620000000,1630000000,1710000000,1750000000,1790000000);


--changeset rm:0199-int-coverage-revision-schema
-- Revision zero is the migration baseline; earlier proofs have no fence and cannot route.
CREATE TABLE crm.int_source_revision (
 tenant_id UUID PRIMARY KEY REFERENCES public.iam_workspace(id),
 revision BIGINT NOT NULL CHECK(revision>=0)
);
CREATE TABLE crm.int_projection_revision (
 tenant_id UUID NOT NULL,generation_id UUID NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>=0),
 PRIMARY KEY(tenant_id,generation_id),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_projection_generation(tenant_id,generation_id)
);
CREATE TABLE crm.int_coverage_fence (
 tenant_id UUID NOT NULL,generation_id UUID NOT NULL,proof_id UUID NOT NULL,
 source_revision BIGINT NOT NULL CHECK(source_revision>=0),
 projection_revision BIGINT NOT NULL CHECK(projection_revision>=0),
 lifecycle_revision BIGINT NOT NULL CHECK(lifecycle_revision>=0),
 PRIMARY KEY(tenant_id,generation_id,proof_id),
 FOREIGN KEY(tenant_id,generation_id,proof_id)
 REFERENCES crm.int_projection_coverage(tenant_id,generation_id,proof_id)
);
ALTER TABLE crm.int_projection_lifecycle ADD CONSTRAINT uq_int_lifecycle_state_identity
 UNIQUE(tenant_id,generation_id,lifecycle_state);
CREATE TABLE crm.int_active_generation (
 tenant_id UUID NOT NULL,projection_code VARCHAR(24) NOT NULL CHECK(projection_code='L1'),
 generation_id UUID NOT NULL,proof_id UUID NOT NULL,
 lifecycle_state VARCHAR(16) NOT NULL CHECK(lifecycle_state='ACTIVE'),
 PRIMARY KEY(tenant_id,projection_code),
 FOREIGN KEY(tenant_id,generation_id,lifecycle_state)
 REFERENCES crm.int_projection_lifecycle(tenant_id,generation_id,lifecycle_state),
 FOREIGN KEY(tenant_id,generation_id,proof_id)
 REFERENCES crm.int_coverage_fence(tenant_id,generation_id,proof_id)
);
--rollback DROP TABLE crm.int_active_generation;
--rollback ALTER TABLE crm.int_projection_lifecycle DROP CONSTRAINT uq_int_lifecycle_state_identity;
--rollback DROP TABLE crm.int_coverage_fence;
--rollback DROP TABLE crm.int_projection_revision;
--rollback DROP TABLE crm.int_source_revision;

--changeset rm:0200-int-coverage-revision-triggers dbms:postgresql splitStatements:false
-- Transactional counters, never a sequence/time watermark. Conservative tenant-wide
-- source invalidation includes direct owner inserts before canonical delivery.
CREATE FUNCTION crm.int_bump_source_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO crm.int_source_revision(tenant_id,revision) VALUES(NEW.tenant_id,1)
 ON CONFLICT(tenant_id) DO UPDATE SET revision=int_source_revision.revision+1;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_source_rev_0 AFTER INSERT ON crm.sales_order_status_event
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_source_revision();
CREATE TRIGGER trg_int_source_rev_1 AFTER INSERT ON crm.sales_order_line
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_source_revision();
CREATE TRIGGER trg_int_source_rev_2 AFTER INSERT ON crm.inv_stock_movement
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_source_revision();
CREATE TRIGGER trg_int_source_rev_3 AFTER INSERT ON crm.int_canonical_fact
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_source_revision();
CREATE TRIGGER trg_int_source_rev_4 AFTER INSERT ON crm.int_sales_line_dimension
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_source_revision();
CREATE TRIGGER trg_int_source_rev_5 AFTER INSERT ON crm.int_movement_dimension
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_source_revision();
CREATE FUNCTION crm.int_bump_projection_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t UUID; g UUID;
BEGIN
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; g:=OLD.generation_id;
 ELSE
   t:=NEW.tenant_id; g:=NEW.generation_id;
   IF TG_OP='UPDATE' THEN
     IF OLD.tenant_id<>t OR OLD.generation_id<>g THEN
       RAISE EXCEPTION 'INT_PROJECTION_REVISION_IDENTITY' USING ERRCODE='23514';
     END IF;
   END IF;
 END IF;
 INSERT INTO crm.int_projection_revision(tenant_id,generation_id,revision) VALUES(t,g,1)
 ON CONFLICT(tenant_id,generation_id) DO UPDATE SET revision=int_projection_revision.revision+1;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_proj_rev_0 AFTER INSERT OR UPDATE OR DELETE ON crm.int_daily_bucket
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_projection_revision();
CREATE TRIGGER trg_int_proj_truncate_0 BEFORE TRUNCATE ON crm.int_daily_bucket
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_proj_rev_1 AFTER INSERT OR UPDATE OR DELETE ON crm.int_l1_sales_contribution
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_projection_revision();
CREATE TRIGGER trg_int_proj_truncate_1 BEFORE TRUNCATE ON crm.int_l1_sales_contribution
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE FUNCTION crm.int_bump_projection_receipt_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE g UUID;
BEGIN
 SELECT generation_id INTO g FROM crm.int_projection_generation
 WHERE tenant_id=NEW.tenant_id AND consumer_code=NEW.consumer_code;
 IF FOUND THEN
   INSERT INTO crm.int_projection_revision(tenant_id,generation_id,revision) VALUES(NEW.tenant_id,g,1)
   ON CONFLICT(tenant_id,generation_id) DO UPDATE SET revision=int_projection_revision.revision+1;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_proj_receipt_rev AFTER INSERT ON crm.int_fact_consumer_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.int_bump_projection_receipt_revision();
CREATE TRIGGER trg_int_coverage_fence_immutable BEFORE UPDATE OR DELETE ON crm.int_coverage_fence
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_coverage_fence_no_truncate BEFORE TRUNCATE ON crm.int_coverage_fence
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TRIGGER trg_int_coverage_fence_no_truncate ON crm.int_coverage_fence;
--rollback DROP TRIGGER trg_int_coverage_fence_immutable ON crm.int_coverage_fence;
--rollback DROP TRIGGER trg_int_proj_receipt_rev ON crm.int_fact_consumer_receipt;
--rollback DROP FUNCTION crm.int_bump_projection_receipt_revision();
--rollback DROP TRIGGER trg_int_proj_truncate_0 ON crm.int_daily_bucket;
--rollback DROP TRIGGER trg_int_proj_rev_0 ON crm.int_daily_bucket;
--rollback DROP TRIGGER trg_int_proj_truncate_1 ON crm.int_l1_sales_contribution;
--rollback DROP TRIGGER trg_int_proj_rev_1 ON crm.int_l1_sales_contribution;
--rollback DROP FUNCTION crm.int_bump_projection_revision();
--rollback DROP TRIGGER trg_int_source_rev_0 ON crm.sales_order_status_event;
--rollback DROP TRIGGER trg_int_source_rev_1 ON crm.sales_order_line;
--rollback DROP TRIGGER trg_int_source_rev_2 ON crm.inv_stock_movement;
--rollback DROP TRIGGER trg_int_source_rev_3 ON crm.int_canonical_fact;
--rollback DROP TRIGGER trg_int_source_rev_4 ON crm.int_sales_line_dimension;
--rollback DROP TRIGGER trg_int_source_rev_5 ON crm.int_movement_dimension;
--rollback DROP FUNCTION crm.int_bump_source_revision();


--changeset rm:0201-kato-semantic-administrative-types
-- Explicit release mapping; level_no remains legacy structural depth, never a business type.
ALTER TABLE public.ref_kato_territory ADD COLUMN territory_type VARCHAR(32) NOT NULL DEFAULT 'LEGACY_OTHER';
ALTER TABLE public.ref_kato_territory ADD COLUMN semantic_release VARCHAR(10);
UPDATE public.ref_kato_territory SET territory_type='OBLAST',semantic_release='2026-11-17'
 WHERE code IN ('100000000','110000000','150000000','190000000','230000000','270000000','310000000','330000000','350000000','390000000','430000000','470000000','550000000','590000000','610000000','620000000','630000000');
UPDATE public.ref_kato_territory SET territory_type='CITY',semantic_release='2026-11-17'
 WHERE code IN ('101010000','101810000','103620100','104221100','111010000','111610000','111810000','113220100','113820100','114020100','114520100','114620100','114820100','115420100','117020100','151010000','153220100','154820100','154823100','154825100','155621100','156020100','156420100','191010000','191810000','194020100','195220100','196220100','231010000','233620100','271010000','273620100','311010000','316020100','316220100','316621100','331010000','331810000','333420100','334420100','334620100','334820100','351010000','351610000','352110000','352210000','352410000','352810000','353220100','354820100','391010000','391610000','392010000','392410000','394420100','395420100','431010000','431910000','433220100','434423100','471010000','471810000','475220100','551010000','551610000','552210000','591010000','593620100','595220100','595620100','596020100','611010000','611610000','612010000','613820100','615420100','615820100','616420100','621010000','621810000','622010000','631010000','632410000','634620100','634820100','634821100','636820100');
UPDATE public.ref_kato_territory SET territory_type='CITY_ADMINISTRATION',semantic_release='2026-11-17'
 WHERE code IN ('101000000','101800000','103620000','104221000','111000000','111600000','111800000','113220000','114520000','117020000','151000000','153220000','154820000','154823000','154825000','155621000','156420000','191000000','191800000','194020000','195220000','196220000','231000000','233620000','271000000','273620000','311000000','316621000','331000000','331800000','333420000','334420000','334620000','334820000','351000000','351600000','352100000','352200000','352400000','352800000','353220000','391000000','391600000','392000000','392400000','395420000','431000000','431900000','433220000','434423000','471000000','471800000','551000000','551600000','552200000','591000000','593620000','595220000','595620000','596020000','611000000','611600000','612000000','621000000','621800000','622000000','631000000','632400000','634620000','634820000','636820000');
UPDATE public.ref_kato_territory SET territory_type='DISTRICT',semantic_release='2026-11-17'
 WHERE code IN ('103200000','103400000','103600000','103800000','104000000','104100000','104200000','104400000','104500000','104600000','113200000','113400000','113600000','113800000','114000000','114400000','114500000','114600000','114800000','115200000','115400000','115600000','116000000','116400000','116600000','116800000','117000000','153200000','153400000','153600000','154000000','154200000','154600000','154800000','155200000','155600000','156000000','156400000','156800000','193600000','194000000','194200000','194400000','195200000','195800000','196200000','196600000','196800000','233600000','234000000','234200000','234600000','234800000','235200000','235600000','273200000','273600000','274000000','274200000','274400000','274800000','275000000','275400000','275800000','276000000','276200000','276600000','313600000','314000000','314200000','314800000','315000000','315400000','315600000','316000000','316200000','316600000','333200000','333400000','333600000','334000000','334200000','334400000','334600000','334800000','353200000','353600000','354000000','354800000','355200000','355600000','356400000','393200000','393400000','393600000','394000000','394200000','394400000','394800000','395000000','395200000','395400000','395600000','395800000','396200000','396400000','396600000','396800000','433200000','433600000','434000000','434400000','434600000','434800000','435200000','473600000','474200000','474600000','475000000','475200000','553200000','553600000','554200000','554600000','554800000','555200000','555600000','556000000','556400000','556800000','593200000','593400000','593600000','594200000','594600000','595000000','595200000','595600000','595800000','596000000','596200000','596400000','596600000','613600000','613800000','613900000','614000000','614400000','614600000','614800000','615200000','615400000','615500000','615600000','615800000','616000000','616400000','623600000','623800000','634000000','634600000','634800000','635200000','635400000','635500000','635600000','635800000','636200000','636300000','636800000');
UPDATE public.ref_kato_territory SET territory_type='REPUBLICAN_CITY',semantic_release='2026-11-17'
 WHERE code IN ('710000000','750000000','790000000');
UPDATE public.ref_kato_territory SET territory_type='CITY_DISTRICT',semantic_release='2026-11-17'
 WHERE code IN ('151011100','151013100','311011100','311013100','351011100','351013100','711110000','711210000','711310000','711410000','711510000','711610000','751110000','751210000','751310000','751410000','751510000','751710000','751810000','751910000','791110000','791310000','791510000','791710000','791910000');
UPDATE public.ref_kato_territory SET territory_type='DISTRICT_ADMINISTRATION',semantic_release='2026-11-17'
 WHERE code IN ('351011000','351013000');
ALTER TABLE public.ref_kato_territory ADD CONSTRAINT ck_kato_code_format
 CHECK(REGEXP_LIKE(code,'^[0-9]{9}$'));
ALTER TABLE public.ref_kato_territory ADD CONSTRAINT ck_kato_semantic_type
 CHECK(territory_type IN ('OBLAST','REPUBLICAN_CITY','DISTRICT','CITY','CITY_DISTRICT','CITY_ADMINISTRATION','DISTRICT_ADMINISTRATION','LEGACY_OTHER'));
ALTER TABLE public.ref_kato_territory ADD CONSTRAINT ck_kato_semantic_release
 CHECK((territory_type='LEGACY_OTHER' AND semantic_release IS NULL) OR
       (territory_type<>'LEGACY_OTHER' AND semantic_release IS NOT NULL));
ALTER TABLE public.ref_kato_territory ADD CONSTRAINT ck_kato_semantic_root
 CHECK(territory_type='LEGACY_OTHER' OR
       (territory_type IN ('OBLAST','REPUBLICAN_CITY') AND parent_source_id IS NULL) OR
       (territory_type NOT IN ('OBLAST','REPUBLICAN_CITY') AND parent_source_id IS NOT NULL));
--rollback ALTER TABLE public.ref_kato_territory DROP CONSTRAINT ck_kato_semantic_root;
--rollback ALTER TABLE public.ref_kato_territory DROP CONSTRAINT ck_kato_semantic_release;
--rollback ALTER TABLE public.ref_kato_territory DROP CONSTRAINT ck_kato_semantic_type;
--rollback ALTER TABLE public.ref_kato_territory DROP CONSTRAINT ck_kato_code_format;
--rollback ALTER TABLE public.ref_kato_territory DROP COLUMN semantic_release;
--rollback ALTER TABLE public.ref_kato_territory DROP COLUMN territory_type;

--changeset rm:0202-kato-semantic-region-admission dbms:postgresql splitStatements:false
-- Use one explicit root type and the stored parent chain; no inferred parent from a prefix.
CREATE FUNCTION crm.int_resolve_semantic_region(p_code VARCHAR) RETURNS VARCHAR LANGUAGE plpgsql STABLE AS $$
DECLARE node RECORD; next_source BIGINT; previous_level INTEGER:=2147483647;
 visited BIGINT[]:=ARRAY[]::BIGINT[];
BEGIN
 IF p_code IS NULL OR p_code !~ '^[0-9]{9}$' THEN RETURN NULL; END IF;
 SELECT source_id,parent_source_id,code,level_no,is_deleted,territory_type INTO node
 FROM public.ref_kato_territory WHERE code=p_code;
 FOR depth IN 1..32 LOOP
   EXIT WHEN node.source_id IS NULL OR node.is_deleted OR node.source_id=ANY(visited)
      OR node.level_no>=previous_level OR left(node.code,2)<>left(p_code,2);
   visited:=array_append(visited,node.source_id);
   IF node.territory_type IN ('OBLAST','REPUBLICAN_CITY') THEN
     IF node.parent_source_id IS NULL THEN RETURN node.code; END IF;
     EXIT;
   END IF;
   EXIT WHEN node.parent_source_id IS NULL;
   previous_level:=node.level_no;
   next_source:=node.parent_source_id;
   SELECT source_id,parent_source_id,code,level_no,is_deleted,territory_type INTO node
   FROM public.ref_kato_territory WHERE source_id=next_source;
 END LOOP;
 RETURN NULL;
END; $$;
CREATE OR REPLACE FUNCTION crm.int_check_movement_dimension_geo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_kato VARCHAR(16); expected_region VARCHAR(16);
BEGIN
 SELECT l.kato_code INTO actual_kato FROM public.org_location l
 WHERE l.workspace_id=NEW.tenant_id AND l.id=NEW.location_id FOR SHARE;
 IF NOT FOUND OR actual_kato IS DISTINCT FROM NEW.kato_code THEN
   RAISE EXCEPTION 'INT_MOVEMENT_DIM_KATO_MISMATCH' USING ERRCODE='23514';
 END IF;
 expected_region:=crm.int_resolve_semantic_region(actual_kato);
 IF expected_region IS DISTINCT FROM NEW.region_code OR
    NEW.geo_quality IS DISTINCT FROM
       (CASE WHEN expected_region IS NULL THEN 'UNKNOWN' ELSE 'RESOLVED' END) THEN
   RAISE EXCEPTION 'INT_MOVEMENT_DIM_REGION_MISMATCH' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
--rollback CREATE OR REPLACE FUNCTION crm.int_check_movement_dimension_geo() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE actual_kato VARCHAR(16); node RECORD; next_source BIGINT;  expected_region VARCHAR(16); visited BIGINT[] := ARRAY[]::BIGINT[]; BEGIN  SELECT l.kato_code INTO actual_kato FROM public.org_location l  WHERE l.workspace_id=NEW.tenant_id AND l.id=NEW.location_id FOR SHARE;  IF NOT FOUND OR actual_kato IS DISTINCT FROM NEW.kato_code THEN    RAISE EXCEPTION 'INT_MOVEMENT_DIM_KATO_MISMATCH' USING ERRCODE='23514';  END IF;  SELECT source_id,parent_source_id,code,level_no,is_deleted INTO node  FROM public.ref_kato_territory WHERE code=actual_kato;  FOR depth IN 1..32 LOOP    EXIT WHEN node.source_id IS NULL;    EXIT WHEN node.is_deleted OR node.source_id=ANY(visited);    visited:=array_append(visited,node.source_id);    IF node.level_no=2 THEN expected_region:=node.code; EXIT; END IF;    EXIT WHEN node.level_no<2 OR node.parent_source_id IS NULL;    next_source:=node.parent_source_id;    SELECT source_id,parent_source_id,code,level_no,is_deleted INTO node    FROM public.ref_kato_territory WHERE source_id=next_source;  END LOOP;  IF expected_region IS DISTINCT FROM NEW.region_code OR     NEW.geo_quality IS DISTINCT FROM        (CASE WHEN expected_region IS NULL THEN 'UNKNOWN' ELSE 'RESOLVED' END) THEN    RAISE EXCEPTION 'INT_MOVEMENT_DIM_REGION_MISMATCH' USING ERRCODE='23514';  END IF;  RETURN NEW; END; $$;
--rollback DROP FUNCTION crm.int_resolve_semantic_region(VARCHAR);


--changeset rm:0203-int-source-revision-event-journal
-- Independent source commits must not serialize on one tenant revision row.
-- Identity is a unique journal key, NOT a freshness watermark.
CREATE TABLE crm.int_source_revision_event (
 tenant_id UUID NOT NULL REFERENCES public.iam_workspace(id),
 event_id BIGINT GENERATED ALWAYS AS IDENTITY,
 PRIMARY KEY(tenant_id,event_id)
);
--rollback DROP TABLE crm.int_source_revision_event;

--changeset rm:0204-int-source-revision-append-only dbms:postgresql splitStatements:false
-- Keep the old per-tenant revision as a frozen migration baseline. Every actual
-- source insert writes an independent event in its owner transaction. Snapshot
-- cardinality, not MAX(event_id), observes late commits and excludes rollbacks.
CREATE OR REPLACE FUNCTION crm.int_bump_source_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO crm.int_source_revision_event(tenant_id) VALUES(NEW.tenant_id);
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_source_rev_baseline_immutable BEFORE INSERT OR UPDATE OR DELETE ON crm.int_source_revision
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_source_rev_baseline_no_truncate BEFORE TRUNCATE ON crm.int_source_revision
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_source_rev_event_immutable BEFORE UPDATE OR DELETE ON crm.int_source_revision_event
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_source_rev_event_no_truncate BEFORE TRUNCATE ON crm.int_source_revision_event
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
-- Rollback is deliberately blocked after journal writes; dropping committed
-- revision events would make earlier proofs appear current again.
--rollback DO $$ BEGIN IF EXISTS(SELECT 1 FROM crm.int_source_revision_event) THEN RAISE EXCEPTION 'INT_SOURCE_REVISION_ROLLBACK_UNSAFE'; END IF; END; $$;
--rollback DROP TRIGGER trg_int_source_rev_event_no_truncate ON crm.int_source_revision_event;
--rollback DROP TRIGGER trg_int_source_rev_event_immutable ON crm.int_source_revision_event;
--rollback DROP TRIGGER trg_int_source_rev_baseline_no_truncate ON crm.int_source_revision;
--rollback DROP TRIGGER trg_int_source_rev_baseline_immutable ON crm.int_source_revision;
--rollback CREATE OR REPLACE FUNCTION crm.int_bump_source_revision() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO crm.int_source_revision(tenant_id,revision) VALUES(NEW.tenant_id,1) ON CONFLICT(tenant_id) DO UPDATE SET revision=int_source_revision.revision+1; RETURN NEW; END; $$;


--changeset rm:0204b-int-source-revision-trigger-privilege dbms:postgresql splitStatements:false
-- A restricted business writer can cause an actual journal event only through
-- the already installed owner-source trigger. Do not grant it journal INSERT.
CREATE OR REPLACE FUNCTION crm.int_bump_source_revision() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_OP<>'INSERT' OR TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME NOT IN
 ('sales_order_status_event','sales_order_line','inv_stock_movement','int_canonical_fact',
  'int_sales_line_dimension','int_movement_dimension') THEN
   RAISE EXCEPTION 'INT_SOURCE_REVISION_TRIGGER_SCOPE' USING ERRCODE='23514';
 END IF;
 INSERT INTO crm.int_source_revision_event(tenant_id) VALUES(NEW.tenant_id);
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_bump_source_revision() FROM PUBLIC;
--rollback GRANT EXECUTE ON FUNCTION crm.int_bump_source_revision() TO PUBLIC;
--rollback CREATE OR REPLACE FUNCTION crm.int_bump_source_revision() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$ BEGIN INSERT INTO crm.int_source_revision_event(tenant_id) VALUES(NEW.tenant_id); RETURN NEW; END; $$;
--rollback ALTER FUNCTION crm.int_bump_source_revision() RESET search_path;


--changeset rm:0205-int-projection-revision-trigger-privilege dbms:postgresql splitStatements:false
-- Follow 0204b: internal revision maintenance without direct worker grants.
CREATE OR REPLACE FUNCTION crm.int_bump_projection_receipt_revision() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE g UUID;
BEGIN
 IF TG_OP<>'INSERT' OR TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'int_fact_consumer_receipt' THEN
   RAISE EXCEPTION 'INT_PROJECTION_REVISION_TRIGGER_SCOPE' USING ERRCODE='23514';
 END IF;
 SELECT generation_id INTO g FROM crm.int_projection_generation
 WHERE tenant_id=NEW.tenant_id AND consumer_code=NEW.consumer_code;
 IF FOUND THEN
   INSERT INTO crm.int_projection_revision(tenant_id,generation_id,revision) VALUES(NEW.tenant_id,g,1)
   ON CONFLICT(tenant_id,generation_id) DO UPDATE SET revision=int_projection_revision.revision+1;
 END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_bump_projection_receipt_revision() FROM PUBLIC;
CREATE OR REPLACE FUNCTION crm.int_bump_projection_revision() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE t UUID; g UUID;
BEGIN
 IF TG_OP NOT IN ('INSERT','UPDATE','DELETE') OR TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME NOT IN ('int_daily_bucket','int_l1_sales_contribution') THEN
   RAISE EXCEPTION 'INT_PROJECTION_REVISION_TRIGGER_SCOPE' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; g:=OLD.generation_id;
 ELSE
   t:=NEW.tenant_id; g:=NEW.generation_id;
   IF TG_OP='UPDATE' THEN
     IF OLD.tenant_id<>t OR OLD.generation_id<>g THEN
       RAISE EXCEPTION 'INT_PROJECTION_REVISION_IDENTITY' USING ERRCODE='23514';
     END IF;
   END IF;
 END IF;
 INSERT INTO crm.int_projection_revision(tenant_id,generation_id,revision) VALUES(t,g,1)
 ON CONFLICT(tenant_id,generation_id) DO UPDATE SET revision=int_projection_revision.revision+1;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_bump_projection_revision() FROM PUBLIC;
--rollback ALTER FUNCTION crm.int_bump_projection_receipt_revision() SECURITY INVOKER;
--rollback ALTER FUNCTION crm.int_bump_projection_receipt_revision() RESET search_path;
--rollback GRANT EXECUTE ON FUNCTION crm.int_bump_projection_receipt_revision() TO PUBLIC;
--rollback ALTER FUNCTION crm.int_bump_projection_revision() SECURITY INVOKER;
--rollback ALTER FUNCTION crm.int_bump_projection_revision() RESET search_path;
--rollback GRANT EXECUTE ON FUNCTION crm.int_bump_projection_revision() TO PUBLIC;


--changeset rm:0206-int-l2-l3-storage
-- Opt-in dimensional extension of an immutable generation scope. No active route.
CREATE TABLE crm.int_dim_revision (
 tenant_id UUID NOT NULL, generation_id UUID NOT NULL,
 dirty_revision BIGINT NOT NULL DEFAULT 0 CHECK(dirty_revision>=0),
 PRIMARY KEY(tenant_id,generation_id),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_projection_generation(tenant_id,generation_id)
);
CREATE TABLE crm.int_l2_contribution (
 tenant_id UUID NOT NULL, generation_id UUID NOT NULL,
 stream_code VARCHAR(16) NOT NULL CHECK(stream_code IN ('BOOKED','FULFILLED','STOCK')),
 source_id UUID NOT NULL, line_id UUID, movement_id UUID, order_id UUID,
 local_day DATE NOT NULL, region_code VARCHAR(16), group_id UUID,
 item_kind VARCHAR(20), warehouse_id UUID, sku_id UUID, currency VARCHAR(3),
 amount NUMERIC NOT NULL DEFAULT 0 CHECK(amount>=0),
 qty_in NUMERIC NOT NULL DEFAULT 0 CHECK(qty_in>=0),
 qty_out NUMERIC NOT NULL DEFAULT 0 CHECK(qty_out>=0),
 PRIMARY KEY(tenant_id,generation_id,stream_code,source_id),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_dim_revision(tenant_id,generation_id),
 FOREIGN KEY(tenant_id,line_id) REFERENCES crm.sales_order_line(tenant_id,id),
 FOREIGN KEY(tenant_id,movement_id) REFERENCES crm.inv_stock_movement(tenant_id,id),
 FOREIGN KEY(tenant_id,order_id,line_id) REFERENCES crm.sales_order_line(tenant_id,order_id,id),
 FOREIGN KEY(tenant_id,group_id,item_kind) REFERENCES crm.cat_group_node(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES public.org_location(workspace_id,id),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES crm.cat_item(tenant_id,id),
 CHECK(group_id IS NULL OR item_kind IS NOT NULL),
 CHECK(item_kind IS NULL OR item_kind IN ('PRODUCT','SERVICE')),
 CHECK((stream_code='BOOKED' AND line_id IS NOT NULL AND source_id=line_id AND order_id IS NOT NULL
       AND movement_id IS NULL AND warehouse_id IS NULL AND region_code IS NULL AND currency IS NOT NULL
       AND qty_in=0 AND qty_out=0)
    OR (stream_code='FULFILLED' AND movement_id IS NOT NULL AND source_id=movement_id AND line_id IS NOT NULL
       AND order_id IS NOT NULL AND warehouse_id IS NOT NULL AND sku_id IS NOT NULL AND currency IS NOT NULL
       AND qty_in=0 AND qty_out=0)
    OR (stream_code='STOCK' AND movement_id IS NOT NULL AND source_id=movement_id AND line_id IS NULL
       AND order_id IS NULL AND warehouse_id IS NOT NULL AND sku_id IS NOT NULL AND currency IS NULL
       AND amount=0 AND ((qty_in>0 AND qty_out=0) OR (qty_out>0 AND qty_in=0))))
);
CREATE INDEX idx_int_l2_scope_day ON crm.int_l2_contribution(tenant_id,generation_id,local_day,stream_code);
-- L3 cells retain leaf identity and warehouse. DISTINCT counts are not additive.
-- A period is visible only when its certified refresh revision matches dirty_revision.
CREATE TABLE crm.int_l3_period (
 tenant_id UUID NOT NULL, generation_id UUID NOT NULL,
 period_code VARCHAR(3) NOT NULL CHECK(period_code IN ('D','W','DEC','M','Q','H','Y')),
 from_day DATE NOT NULL, until_day DATE NOT NULL CHECK(from_day<until_day),
 refreshed_revision BIGINT NOT NULL CHECK(refreshed_revision>=0),
 PRIMARY KEY(tenant_id,generation_id,period_code,from_day),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_dim_revision(tenant_id,generation_id)
);
CREATE TABLE crm.int_l3_cell (
 tenant_id UUID NOT NULL, generation_id UUID NOT NULL, period_code VARCHAR(3) NOT NULL,
 from_day DATE NOT NULL, cell_id UUID NOT NULL,
 stream_code VARCHAR(16) NOT NULL CHECK(stream_code IN ('BOOKED','FULFILLED','STOCK')),
 region_code VARCHAR(16), group_id UUID, item_kind VARCHAR(20), warehouse_id UUID, sku_id UUID, currency VARCHAR(3),
 amount NUMERIC NOT NULL CHECK(amount>=0), qty_in NUMERIC NOT NULL CHECK(qty_in>=0),
 qty_out NUMERIC NOT NULL CHECK(qty_out>=0), distinct_orders BIGINT NOT NULL CHECK(distinct_orders>=0),
 PRIMARY KEY(tenant_id,generation_id,period_code,from_day,cell_id),
 FOREIGN KEY(tenant_id,generation_id,period_code,from_day)
 REFERENCES crm.int_l3_period(tenant_id,generation_id,period_code,from_day),
 FOREIGN KEY(tenant_id,group_id,item_kind) REFERENCES crm.cat_group_node(tenant_id,id,kind),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES public.org_location(workspace_id,id),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES crm.cat_item(tenant_id,id),
 CHECK(group_id IS NULL OR item_kind IS NOT NULL),
 CHECK((stream_code='BOOKED' AND warehouse_id IS NULL AND region_code IS NULL AND currency IS NOT NULL AND qty_in=0 AND qty_out=0)
    OR (stream_code='FULFILLED' AND warehouse_id IS NOT NULL AND sku_id IS NOT NULL AND currency IS NOT NULL AND qty_in=0 AND qty_out=0)
    OR (stream_code='STOCK' AND warehouse_id IS NOT NULL AND sku_id IS NOT NULL AND currency IS NULL AND amount=0 AND distinct_orders=0))
);
--rollback DROP TABLE crm.int_l3_cell;
--rollback DROP TABLE crm.int_l3_period;
--rollback DROP TABLE crm.int_l2_contribution;
--rollback DROP TABLE crm.int_dim_revision;

--changeset rm:0207-int-l2-l3-transactional-revision dbms:postgresql splitStatements:false
CREATE UNIQUE INDEX uq_int_l3_cell_grain ON crm.int_l3_cell
 (tenant_id,generation_id,period_code,from_day,stream_code,region_code,group_id,item_kind,warehouse_id,sku_id,currency)
 NULLS NOT DISTINCT;
-- All writers take lifecycle SHARE before the dimension revision row.
-- A future refresh holds that same row FOR UPDATE until cells and period stamp commit.
CREATE FUNCTION crm.int_dim_lock_building(p_t UUID,p_g UUID) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 PERFORM 1 FROM crm.int_projection_lifecycle WHERE tenant_id=p_t AND generation_id=p_g
   AND lifecycle_state='BUILDING' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_DIM_NOT_BUILDING' USING ERRCODE='23514'; END IF;
END; $$;
REVOKE ALL ON FUNCTION crm.int_dim_lock_building(UUID,UUID) FROM PUBLIC;
CREATE FUNCTION crm.int_l2_dirty() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE t UUID; g UUID;
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'int_l2_contribution' OR TG_OP NOT IN ('INSERT','UPDATE','DELETE')
 THEN RAISE EXCEPTION 'INT_DIM_TRIGGER_SCOPE'; END IF;
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; g:=OLD.generation_id;
 ELSE t:=NEW.tenant_id; g:=NEW.generation_id; END IF;
 IF TG_OP='UPDATE' AND (OLD.tenant_id,OLD.generation_id,OLD.stream_code,OLD.source_id)
    IS DISTINCT FROM (NEW.tenant_id,NEW.generation_id,NEW.stream_code,NEW.source_id)
 THEN RAISE EXCEPTION 'INT_DIM_IDENTITY' USING ERRCODE='23514'; END IF;
 PERFORM crm.int_dim_lock_building(t,g);
 IF TG_OP<>'DELETE' AND NOT EXISTS(SELECT 1 FROM crm.int_projection_generation s
    WHERE s.tenant_id=t AND s.generation_id=g AND NEW.local_day>=s.from_day AND NEW.local_day<s.until_day)
 THEN RAISE EXCEPTION 'INT_DIM_DAY_SCOPE' USING ERRCODE='23514'; END IF;
 UPDATE crm.int_dim_revision SET dirty_revision=dirty_revision+1 WHERE tenant_id=t AND generation_id=g;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_DIM_REVISION_MISSING'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_l2_dirty() FROM PUBLIC;
-- AFTER avoids false increments on INSERT ... ON CONFLICT DO NOTHING.
CREATE TRIGGER trg_int_l2_dirty AFTER INSERT OR UPDATE OR DELETE ON crm.int_l2_contribution
 FOR EACH ROW EXECUTE FUNCTION crm.int_l2_dirty();
CREATE TRIGGER trg_int_l2_no_truncate BEFORE TRUNCATE ON crm.int_l2_contribution
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE FUNCTION crm.int_l3_write_guard() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE t UUID; g UUID; r BIGINT;
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME NOT IN ('int_l3_period','int_l3_cell')
    OR TG_OP NOT IN ('INSERT','UPDATE','DELETE') THEN RAISE EXCEPTION 'INT_DIM_TRIGGER_SCOPE'; END IF;
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; g:=OLD.generation_id;
 ELSE t:=NEW.tenant_id; g:=NEW.generation_id; END IF;
 IF TG_OP='UPDATE' AND (OLD.tenant_id,OLD.generation_id,OLD.period_code,OLD.from_day)
    IS DISTINCT FROM (NEW.tenant_id,NEW.generation_id,NEW.period_code,NEW.from_day)
 THEN RAISE EXCEPTION 'INT_DIM_IDENTITY' USING ERRCODE='23514'; END IF;
 PERFORM crm.int_dim_lock_building(t,g);
 SELECT dirty_revision INTO STRICT r FROM crm.int_dim_revision WHERE tenant_id=t AND generation_id=g FOR UPDATE;
 IF TG_TABLE_NAME='int_l3_period' AND TG_OP<>'DELETE' THEN
   IF NEW.refreshed_revision<>r THEN RAISE EXCEPTION 'INT_DIM_STALE_REFRESH' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_l3_write_guard() FROM PUBLIC;
CREATE TRIGGER trg_int_l3_period_guard BEFORE INSERT OR UPDATE OR DELETE ON crm.int_l3_period
 FOR EACH ROW EXECUTE FUNCTION crm.int_l3_write_guard();
CREATE TRIGGER trg_int_l3_cell_guard BEFORE INSERT OR UPDATE OR DELETE ON crm.int_l3_cell
 FOR EACH ROW EXECUTE FUNCTION crm.int_l3_write_guard();
CREATE TRIGGER trg_int_l3_period_no_truncate BEFORE TRUNCATE ON crm.int_l3_period
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_l3_cell_no_truncate BEFORE TRUNCATE ON crm.int_l3_cell
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TRIGGER trg_int_l3_cell_no_truncate ON crm.int_l3_cell;
--rollback DROP TRIGGER trg_int_l3_period_no_truncate ON crm.int_l3_period;
--rollback DROP TRIGGER trg_int_l3_cell_guard ON crm.int_l3_cell;
--rollback DROP TRIGGER trg_int_l3_period_guard ON crm.int_l3_period;
--rollback DROP FUNCTION crm.int_l3_write_guard();
--rollback DROP TRIGGER trg_int_l2_no_truncate ON crm.int_l2_contribution;
--rollback DROP TRIGGER trg_int_l2_dirty ON crm.int_l2_contribution;
--rollback DROP FUNCTION crm.int_l2_dirty();
--rollback DROP FUNCTION crm.int_dim_lock_building(UUID,UUID);
--rollback DROP INDEX crm.uq_int_l3_cell_grain;


--changeset rm:0208-int-l2-consumer-binding
-- Separate consumer identity, same immutable calendar/range and terminal lifecycle.
CREATE TABLE crm.int_l2_consumer (
 tenant_id UUID NOT NULL, generation_id UUID NOT NULL, consumer_code VARCHAR(96) NOT NULL,
 PRIMARY KEY(tenant_id,generation_id), UNIQUE(tenant_id,consumer_code),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_dim_revision(tenant_id,generation_id),
 FOREIGN KEY(tenant_id,consumer_code) REFERENCES crm.int_fact_consumer(tenant_id,consumer_code)
);
CREATE INDEX idx_int_l2_order_reconcile ON crm.int_l2_contribution(tenant_id,generation_id,stream_code,order_id);
ALTER TABLE crm.int_l2_contribution ADD CONSTRAINT fk_int_l2_movement_exact
 FOREIGN KEY(tenant_id,movement_id,sku_id,warehouse_id)
 REFERENCES crm.inv_stock_movement(tenant_id,id,item_id,location_id);
ALTER TABLE crm.int_l3_period ALTER COLUMN refreshed_revision DROP NOT NULL;
--rollback ALTER TABLE crm.int_l3_period ALTER COLUMN refreshed_revision SET NOT NULL;
--rollback ALTER TABLE crm.int_l2_contribution DROP CONSTRAINT fk_int_l2_movement_exact;
--rollback DROP INDEX crm.idx_int_l2_order_reconcile;
--rollback DROP TABLE crm.int_l2_consumer;

--changeset rm:0209-int-l2-provenance-and-rollup-fence dbms:postgresql splitStatements:false
CREATE TRIGGER trg_int_l2_consumer_immutable BEFORE UPDATE OR DELETE ON crm.int_l2_consumer
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_l2_consumer_no_truncate BEFORE TRUNCATE ON crm.int_l2_consumer
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE FUNCTION crm.int_l2_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.consumer_code <> 'l2:'||NEW.generation_id::text THEN
   RAISE EXCEPTION 'INT_L2_CONSUMER_IDENTITY' USING ERRCODE='23514';
 END IF;
 PERFORM crm.int_dim_lock_building(NEW.tenant_id,NEW.generation_id);
 RETURN NEW;
END; $$;
-- Guard executes in a narrowly scoped privileged context, not through a public helper grant.
ALTER FUNCTION crm.int_l2_binding_guard() SECURITY DEFINER;
ALTER FUNCTION crm.int_l2_binding_guard() SET search_path=pg_catalog,pg_temp;
REVOKE ALL ON FUNCTION crm.int_l2_binding_guard() FROM PUBLIC;
CREATE TRIGGER trg_int_l2_binding_guard BEFORE INSERT ON crm.int_l2_consumer
 FOR EACH ROW EXECUTE FUNCTION crm.int_l2_binding_guard();

-- Source-derived contributions, separate from the authorized L0 aggregation oracle.
-- Keep exact leaf revision, UNKNOWN dimensions, and original booked price.
CREATE VIEW crm.int_l2_expected_source AS
WITH eligible AS (
 SELECT tenant_id,order_id,event_type,min(occurred_at) AS event_at
 FROM crm.sales_order_status_event WHERE event_type IN ('PLACED','COMPLETED')
 GROUP BY tenant_id,order_id,event_type HAVING count(*)=1
)
SELECT l.tenant_id,'BOOKED'::varchar AS stream_code,l.id AS source_id,l.id AS line_id,
 NULL::uuid AS movement_id,l.order_id,e.event_at,
 NULL::varchar AS region_code,d.group_id,d.item_kind,NULL::uuid AS warehouse_id,
 l.catalog_item_id_snapshot AS sku_id,trim(l.currency)::varchar AS currency,
 (l.quantity*l.unit_price_snapshot)::numeric AS amount,0::numeric AS qty_in,0::numeric AS qty_out
FROM eligible e JOIN crm.sales_order_line l ON l.tenant_id=e.tenant_id AND l.order_id=e.order_id
LEFT JOIN crm.int_sales_line_dimension d ON d.tenant_id=l.tenant_id AND d.line_id=l.id
WHERE e.event_type='PLACED'
UNION ALL
SELECT m.tenant_id,'FULFILLED',m.id,l.id,m.id,l.order_id,e.event_at,
 md.region_code,d.group_id,d.item_kind,m.location_id,m.item_id,trim(l.currency),
 (-m.delta_quantity*l.unit_price_snapshot)::numeric,0::numeric,0::numeric
FROM eligible e JOIN crm.sales_order_line l ON l.tenant_id=e.tenant_id AND l.order_id=e.order_id
JOIN crm.inv_stock_reservation r ON r.tenant_id=l.tenant_id AND r.order_line_id=l.id AND r.order_id=l.order_id
JOIN crm.inv_stock_movement m ON m.tenant_id=r.tenant_id AND m.reservation_id=r.id
 AND m.location_id=r.location_id AND m.item_id=r.item_id AND m.movement_kind='FULFILLMENT'
LEFT JOIN crm.int_movement_dimension md ON md.tenant_id=m.tenant_id AND md.movement_id=m.id
LEFT JOIN crm.int_sales_line_dimension d ON d.tenant_id=l.tenant_id AND d.line_id=l.id
WHERE e.event_type='COMPLETED'
UNION ALL
SELECT m.tenant_id,'STOCK',m.id,NULL::uuid,m.id,NULL::uuid,m.recorded_at,
 d.region_code,d.group_id,d.item_kind,m.location_id,m.item_id,NULL::varchar,
 0::numeric,greatest(m.delta_quantity,0),greatest(-m.delta_quantity,0)
FROM crm.inv_stock_movement m
LEFT JOIN crm.int_movement_dimension d ON d.tenant_id=m.tenant_id AND d.movement_id=m.id;

CREATE FUNCTION crm.int_l2_provenance_guard() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'int_l2_contribution' OR TG_OP NOT IN ('INSERT','UPDATE')
 THEN RAISE EXCEPTION 'INT_L2_TRIGGER_SCOPE'; END IF;
 IF NOT EXISTS(
   SELECT 1 FROM crm.int_l2_expected_source e
   JOIN crm.int_projection_generation g ON g.tenant_id=e.tenant_id AND g.generation_id=NEW.generation_id
   JOIN crm.int_calendar_policy p ON p.tenant_id=g.tenant_id AND p.version=g.calendar_version
   WHERE e.tenant_id=NEW.tenant_id AND e.stream_code=NEW.stream_code AND e.source_id=NEW.source_id
     AND (e.line_id,e.movement_id,e.order_id,(e.event_at AT TIME ZONE p.zone_id)::date,
          e.region_code,e.group_id,e.item_kind,e.warehouse_id,e.sku_id,e.currency,e.amount,e.qty_in,e.qty_out)
     IS NOT DISTINCT FROM
         (NEW.line_id,NEW.movement_id,NEW.order_id,NEW.local_day,
          NEW.region_code,NEW.group_id,NEW.item_kind,NEW.warehouse_id,NEW.sku_id,NEW.currency,NEW.amount,NEW.qty_in,NEW.qty_out)
 ) THEN RAISE EXCEPTION 'INT_L2_SOURCE_MISMATCH' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_l2_provenance_guard() FROM PUBLIC;
CREATE TRIGGER trg_int_l2_provenance BEFORE INSERT OR UPDATE ON crm.int_l2_contribution
 FOR EACH ROW EXECUTE FUNCTION crm.int_l2_provenance_guard();

-- Any cell mutation invalidates the period stamp. A future refresh must stamp last.
CREATE FUNCTION crm.int_l3_invalidate_stamp() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE t uuid; g uuid; c varchar; f date;
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'int_l3_cell' OR TG_OP NOT IN ('INSERT','UPDATE','DELETE')
 THEN RAISE EXCEPTION 'INT_L3_TRIGGER_SCOPE'; END IF;
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; g:=OLD.generation_id; c:=OLD.period_code; f:=OLD.from_day;
 ELSE t:=NEW.tenant_id; g:=NEW.generation_id; c:=NEW.period_code; f:=NEW.from_day; END IF;
 UPDATE crm.int_l3_period SET refreshed_revision=NULL
 WHERE tenant_id=t AND generation_id=g AND period_code=c AND from_day=f AND refreshed_revision IS NOT NULL;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_l3_invalidate_stamp() FROM PUBLIC;
CREATE TRIGGER trg_int_l3_invalidate_stamp AFTER INSERT OR UPDATE OR DELETE ON crm.int_l3_cell
 FOR EACH ROW EXECUTE FUNCTION crm.int_l3_invalidate_stamp();
--rollback DROP TRIGGER trg_int_l3_invalidate_stamp ON crm.int_l3_cell;
--rollback DROP FUNCTION crm.int_l3_invalidate_stamp();
--rollback DROP TRIGGER trg_int_l2_provenance ON crm.int_l2_contribution;
--rollback DROP FUNCTION crm.int_l2_provenance_guard();
--rollback DROP VIEW crm.int_l2_expected_source;
--rollback DROP TRIGGER trg_int_l2_binding_guard ON crm.int_l2_consumer;
--rollback DROP FUNCTION crm.int_l2_binding_guard();
--rollback DROP TRIGGER trg_int_l2_consumer_no_truncate ON crm.int_l2_consumer;
--rollback DROP TRIGGER trg_int_l2_consumer_immutable ON crm.int_l2_consumer;


--changeset rm:0210-int-l3-exact-order-membership
CREATE TABLE crm.int_l3_cell_order (
 tenant_id UUID NOT NULL, generation_id UUID NOT NULL, period_code VARCHAR(3) NOT NULL,
 from_day DATE NOT NULL, cell_id UUID NOT NULL, order_id UUID NOT NULL,
 PRIMARY KEY(tenant_id,generation_id,period_code,from_day,cell_id,order_id),
 FOREIGN KEY(tenant_id,generation_id,period_code,from_day,cell_id)
 REFERENCES crm.int_l3_cell(tenant_id,generation_id,period_code,from_day,cell_id),
 FOREIGN KEY(tenant_id,order_id) REFERENCES crm.sales_order(tenant_id,id)
);
CREATE INDEX idx_int_l3_order_union ON crm.int_l3_cell_order(tenant_id,generation_id,period_code,from_day,order_id);
--rollback DROP TABLE crm.int_l3_cell_order;

--changeset rm:0211-int-l3-membership-guard dbms:postgresql splitStatements:false
CREATE FUNCTION crm.int_l3_membership_guard() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE t uuid; g uuid; c varchar; f date;
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'int_l3_cell_order' OR TG_OP NOT IN ('INSERT','DELETE')
 THEN RAISE EXCEPTION 'INT_L3_MEMBERSHIP_SCOPE' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; g:=OLD.generation_id; c:=OLD.period_code; f:=OLD.from_day;
 ELSE t:=NEW.tenant_id; g:=NEW.generation_id; c:=NEW.period_code; f:=NEW.from_day; END IF;
 PERFORM crm.int_dim_lock_building(t,g);
 PERFORM 1 FROM crm.int_dim_revision WHERE tenant_id=t AND generation_id=g FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_DIM_REVISION_MISSING'; END IF;
 IF TG_OP='INSERT' AND NOT EXISTS (
   SELECT 1 FROM crm.int_l3_cell x
   JOIN crm.int_l3_period p USING(tenant_id,generation_id,period_code,from_day)
   JOIN crm.int_l2_contribution l ON l.tenant_id=x.tenant_id AND l.generation_id=x.generation_id
     AND l.local_day>=p.from_day AND l.local_day<p.until_day
     AND (l.stream_code,l.region_code,l.group_id,l.item_kind,l.warehouse_id,l.sku_id,l.currency)
       IS NOT DISTINCT FROM (x.stream_code,x.region_code,x.group_id,x.item_kind,x.warehouse_id,x.sku_id,x.currency)
   WHERE x.tenant_id=t AND x.generation_id=g AND x.period_code=c AND x.from_day=f
     AND x.cell_id=NEW.cell_id AND l.order_id=NEW.order_id AND x.stream_code IN ('BOOKED','FULFILLED')
 ) THEN RAISE EXCEPTION 'INT_L3_MEMBERSHIP_SOURCE' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_l3_membership_guard() FROM PUBLIC;
CREATE TRIGGER trg_int_l3_membership_guard BEFORE INSERT OR DELETE ON crm.int_l3_cell_order
 FOR EACH ROW EXECUTE FUNCTION crm.int_l3_membership_guard();
CREATE TRIGGER trg_int_l3_membership_immutable BEFORE UPDATE ON crm.int_l3_cell_order
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_l3_membership_no_truncate BEFORE TRUNCATE ON crm.int_l3_cell_order
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE FUNCTION crm.int_l3_membership_invalidate() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE t uuid; g uuid; c varchar; f date;
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'int_l3_cell_order' OR TG_OP NOT IN ('INSERT','DELETE')
 THEN RAISE EXCEPTION 'INT_L3_MEMBERSHIP_SCOPE'; END IF;
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; g:=OLD.generation_id; c:=OLD.period_code; f:=OLD.from_day;
 ELSE t:=NEW.tenant_id; g:=NEW.generation_id; c:=NEW.period_code; f:=NEW.from_day; END IF;
 UPDATE crm.int_l3_period SET refreshed_revision=NULL
 WHERE tenant_id=t AND generation_id=g AND period_code=c AND from_day=f AND refreshed_revision IS NOT NULL;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_l3_membership_invalidate() FROM PUBLIC;
CREATE TRIGGER trg_int_l3_membership_invalidate AFTER INSERT OR DELETE ON crm.int_l3_cell_order
 FOR EACH ROW EXECUTE FUNCTION crm.int_l3_membership_invalidate();
--rollback DROP TRIGGER trg_int_l3_membership_invalidate ON crm.int_l3_cell_order;
--rollback DROP FUNCTION crm.int_l3_membership_invalidate();
--rollback DROP TRIGGER trg_int_l3_membership_no_truncate ON crm.int_l3_cell_order;
--rollback DROP TRIGGER trg_int_l3_membership_immutable ON crm.int_l3_cell_order;
--rollback DROP TRIGGER trg_int_l3_membership_guard ON crm.int_l3_cell_order;
--rollback DROP FUNCTION crm.int_l3_membership_guard();


--changeset rm:0212-int-dim-proof-revisions
ALTER TABLE crm.int_dim_revision ADD COLUMN receipt_revision BIGINT NOT NULL DEFAULT 0 CHECK(receipt_revision>=0);
ALTER TABLE crm.int_dim_revision ADD COLUMN materialization_revision BIGINT NOT NULL DEFAULT 0 CHECK(materialization_revision>=0);
CREATE TABLE crm.int_dim_coverage_proof (
 tenant_id UUID NOT NULL,generation_id UUID NOT NULL,proof_id UUID NOT NULL,
 observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 source_revision BIGINT NOT NULL CHECK(source_revision>=0),
 dirty_revision BIGINT NOT NULL CHECK(dirty_revision>=0),
 receipt_revision BIGINT NOT NULL CHECK(receipt_revision>=0),
 materialization_revision BIGINT NOT NULL CHECK(materialization_revision>=0),
 lifecycle_revision BIGINT NOT NULL CHECK(lifecycle_revision>=0),
 delivery_mismatches BIGINT NOT NULL CHECK(delivery_mismatches>=0),
 contribution_mismatches BIGINT NOT NULL CHECK(contribution_mismatches>=0),
 period_mismatches BIGINT NOT NULL CHECK(period_mismatches>=0),
 cell_mismatches BIGINT NOT NULL CHECK(cell_mismatches>=0),
 membership_mismatches BIGINT NOT NULL CHECK(membership_mismatches>=0),
 PRIMARY KEY(tenant_id,generation_id,proof_id),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_l2_consumer(tenant_id,generation_id)
);
--rollback DROP TABLE crm.int_dim_coverage_proof;
--rollback ALTER TABLE crm.int_dim_revision DROP COLUMN materialization_revision;
--rollback ALTER TABLE crm.int_dim_revision DROP COLUMN receipt_revision;

--changeset rm:0213-int-dim-proof-fences dbms:postgresql splitStatements:false
CREATE FUNCTION crm.int_dim_receipt_revision() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE g uuid;
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'int_fact_consumer_receipt' OR TG_OP<>'INSERT'
 THEN RAISE EXCEPTION 'INT_DIM_RECEIPT_SCOPE'; END IF;
 SELECT generation_id INTO g FROM crm.int_l2_consumer
 WHERE tenant_id=NEW.tenant_id AND consumer_code=NEW.consumer_code;
 IF FOUND THEN
   PERFORM crm.int_dim_lock_building(NEW.tenant_id,g);
   UPDATE crm.int_dim_revision SET receipt_revision=receipt_revision+1
   WHERE tenant_id=NEW.tenant_id AND generation_id=g;
 END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_dim_receipt_revision() FROM PUBLIC;
CREATE TRIGGER trg_int_dim_receipt_revision AFTER INSERT ON crm.int_fact_consumer_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.int_dim_receipt_revision();
CREATE FUNCTION crm.int_dim_materialization_revision() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE t uuid; g uuid;
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME NOT IN ('int_l3_period','int_l3_cell','int_l3_cell_order')
 OR TG_OP NOT IN ('INSERT','UPDATE','DELETE') THEN RAISE EXCEPTION 'INT_DIM_MATERIALIZATION_SCOPE'; END IF;
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; g:=OLD.generation_id;
 ELSE t:=NEW.tenant_id; g:=NEW.generation_id; END IF;
 PERFORM crm.int_dim_lock_building(t,g);
 UPDATE crm.int_dim_revision SET materialization_revision=materialization_revision+1
 WHERE tenant_id=t AND generation_id=g;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_DIM_REVISION_MISSING'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_dim_materialization_revision() FROM PUBLIC;
CREATE TRIGGER trg_int_dim_period_revision AFTER INSERT OR UPDATE OR DELETE ON crm.int_l3_period
 FOR EACH ROW EXECUTE FUNCTION crm.int_dim_materialization_revision();
CREATE TRIGGER trg_int_dim_cell_revision AFTER INSERT OR UPDATE OR DELETE ON crm.int_l3_cell
 FOR EACH ROW EXECUTE FUNCTION crm.int_dim_materialization_revision();
CREATE TRIGGER trg_int_dim_member_revision AFTER INSERT OR DELETE ON crm.int_l3_cell_order
 FOR EACH ROW EXECUTE FUNCTION crm.int_dim_materialization_revision();
CREATE TRIGGER trg_int_dim_proof_immutable BEFORE UPDATE OR DELETE ON crm.int_dim_coverage_proof
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_dim_proof_no_truncate BEFORE TRUNCATE ON crm.int_dim_coverage_proof
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();

-- Reservation identity is analytically significant. Track actual changes without
-- serializing unrelated source writes or invalidating for state-only transitions.
CREATE FUNCTION crm.int_dim_reservation_revision() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'inv_stock_reservation' OR TG_OP NOT IN ('INSERT','UPDATE','DELETE')
 THEN RAISE EXCEPTION 'INT_DIM_RESERVATION_SCOPE'; END IF;
 IF TG_OP='UPDATE' THEN
   IF (OLD.tenant_id,OLD.id,OLD.order_id,OLD.order_line_id,OLD.location_id,OLD.item_id)
      IS NOT DISTINCT FROM (NEW.tenant_id,NEW.id,NEW.order_id,NEW.order_line_id,NEW.location_id,NEW.item_id)
   THEN RETURN NEW; END IF;
 END IF;
 IF TG_OP<>'INSERT' THEN INSERT INTO crm.int_source_revision_event(tenant_id) VALUES(OLD.tenant_id); END IF;
 IF TG_OP='INSERT' THEN INSERT INTO crm.int_source_revision_event(tenant_id) VALUES(NEW.tenant_id);
 ELSIF TG_OP='UPDATE' AND OLD.tenant_id<>NEW.tenant_id THEN
   INSERT INTO crm.int_source_revision_event(tenant_id) VALUES(NEW.tenant_id);
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_dim_reservation_revision() FROM PUBLIC;
CREATE TRIGGER trg_int_dim_reservation_revision AFTER INSERT OR UPDATE OR DELETE ON crm.inv_stock_reservation
 FOR EACH ROW EXECUTE FUNCTION crm.int_dim_reservation_revision();
--rollback DO $$ BEGIN IF EXISTS(SELECT 1 FROM crm.int_dim_coverage_proof) THEN RAISE EXCEPTION 'INT_DIM_PROOF_ROLLBACK_UNSAFE'; END IF; END; $$;
--rollback DROP TRIGGER trg_int_dim_reservation_revision ON crm.inv_stock_reservation;
--rollback DROP FUNCTION crm.int_dim_reservation_revision();
--rollback DROP TRIGGER trg_int_dim_proof_no_truncate ON crm.int_dim_coverage_proof;
--rollback DROP TRIGGER trg_int_dim_proof_immutable ON crm.int_dim_coverage_proof;
--rollback DROP TRIGGER trg_int_dim_member_revision ON crm.int_l3_cell_order;
--rollback DROP TRIGGER trg_int_dim_cell_revision ON crm.int_l3_cell;
--rollback DROP TRIGGER trg_int_dim_period_revision ON crm.int_l3_period;
--rollback DROP FUNCTION crm.int_dim_materialization_revision();
--rollback DROP TRIGGER trg_int_dim_receipt_revision ON crm.int_fact_consumer_receipt;
--rollback DROP FUNCTION crm.int_dim_receipt_revision();


--changeset rm:0214-int-commercial-order-units dbms:postgresql splitStatements:false
ALTER TABLE crm.int_l2_contribution ADD COLUMN product_order_units NUMERIC CHECK(product_order_units>=0 AND (product_order_units IS NULL OR (stream_code IN ('BOOKED','FULFILLED') AND item_kind='PRODUCT')));
ALTER TABLE crm.int_l3_cell ADD COLUMN product_order_units NUMERIC CHECK(product_order_units>=0 AND (product_order_units IS NULL OR (stream_code IN ('BOOKED','FULFILLED') AND item_kind='PRODUCT')));
CREATE OR REPLACE VIEW crm.int_l2_expected_source AS
WITH eligible AS (
 SELECT tenant_id,order_id,event_type,min(occurred_at) AS event_at
 FROM crm.sales_order_status_event WHERE event_type IN ('PLACED','COMPLETED')
 GROUP BY tenant_id,order_id,event_type HAVING count(*)=1
)
SELECT l.tenant_id,'BOOKED'::varchar AS stream_code,l.id AS source_id,l.id AS line_id,
 NULL::uuid AS movement_id,l.order_id,e.event_at,
 NULL::varchar AS region_code,d.group_id,d.item_kind,NULL::uuid AS warehouse_id,
 l.catalog_item_id_snapshot AS sku_id,trim(l.currency)::varchar AS currency,
 (l.quantity*l.unit_price_snapshot)::numeric AS amount,0::numeric AS qty_in,0::numeric AS qty_out,
 CASE WHEN d.item_kind='PRODUCT' THEN l.quantity::numeric END AS product_order_units
FROM eligible e JOIN crm.sales_order_line l ON l.tenant_id=e.tenant_id AND l.order_id=e.order_id
LEFT JOIN crm.int_sales_line_dimension d ON d.tenant_id=l.tenant_id AND d.line_id=l.id
WHERE e.event_type='PLACED'
UNION ALL
SELECT m.tenant_id,'FULFILLED',m.id,l.id,m.id,l.order_id,e.event_at,
 md.region_code,d.group_id,d.item_kind,m.location_id,m.item_id,trim(l.currency),
 (-m.delta_quantity*l.unit_price_snapshot)::numeric,0::numeric,0::numeric,
 CASE WHEN d.item_kind='PRODUCT' THEN -m.delta_quantity END
FROM eligible e JOIN crm.sales_order_line l ON l.tenant_id=e.tenant_id AND l.order_id=e.order_id
JOIN crm.inv_stock_reservation r ON r.tenant_id=l.tenant_id AND r.order_line_id=l.id AND r.order_id=l.order_id
JOIN crm.inv_stock_movement m ON m.tenant_id=r.tenant_id AND m.reservation_id=r.id
 AND m.location_id=r.location_id AND m.item_id=r.item_id AND m.movement_kind='FULFILLMENT'
LEFT JOIN crm.int_movement_dimension md ON md.tenant_id=m.tenant_id AND md.movement_id=m.id
LEFT JOIN crm.int_sales_line_dimension d ON d.tenant_id=l.tenant_id AND d.line_id=l.id
WHERE e.event_type='COMPLETED'
UNION ALL
SELECT m.tenant_id,'STOCK',m.id,NULL::uuid,m.id,NULL::uuid,m.recorded_at,
 d.region_code,d.group_id,d.item_kind,m.location_id,m.item_id,NULL::varchar,
 0::numeric,greatest(m.delta_quantity,0),greatest(-m.delta_quantity,0),NULL::numeric
FROM crm.inv_stock_movement m
LEFT JOIN crm.int_movement_dimension d ON d.tenant_id=m.tenant_id AND d.movement_id=m.id;

CREATE OR REPLACE FUNCTION crm.int_l2_provenance_guard() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_TABLE_SCHEMA<>'crm' OR TG_TABLE_NAME<>'int_l2_contribution' OR TG_OP NOT IN ('INSERT','UPDATE')
 THEN RAISE EXCEPTION 'INT_L2_TRIGGER_SCOPE'; END IF;
 IF NOT EXISTS(
   SELECT 1 FROM crm.int_l2_expected_source e
   JOIN crm.int_projection_generation g ON g.tenant_id=e.tenant_id AND g.generation_id=NEW.generation_id
   JOIN crm.int_calendar_policy p ON p.tenant_id=g.tenant_id AND p.version=g.calendar_version
   WHERE e.tenant_id=NEW.tenant_id AND e.stream_code=NEW.stream_code AND e.source_id=NEW.source_id
     AND (e.line_id,e.movement_id,e.order_id,(e.event_at AT TIME ZONE p.zone_id)::date,
          e.region_code,e.group_id,e.item_kind,e.warehouse_id,e.sku_id,e.currency,e.amount,e.qty_in,e.qty_out,e.product_order_units)
     IS NOT DISTINCT FROM
         (NEW.line_id,NEW.movement_id,NEW.order_id,NEW.local_day,
          NEW.region_code,NEW.group_id,NEW.item_kind,NEW.warehouse_id,NEW.sku_id,NEW.currency,NEW.amount,NEW.qty_in,NEW.qty_out,NEW.product_order_units)
 ) THEN RAISE EXCEPTION 'INT_L2_SOURCE_MISMATCH' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_l2_provenance_guard() FROM PUBLIC;
-- Existing generations must pass the expanded independent proof; legacy rows are not silently backfilled.
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_COMMERCIAL_UNITS_REQUIRES_FORWARD_REBUILD'; END; $$;


--changeset rm:0215-int-fact-availability-observation dbms:postgresql splitStatements:false
-- First committed visibility is NOT the insert timestamp. This journal records
-- observations made by a later reader, not an inferred historical commit time.
CREATE TABLE crm.int_fact_availability_observation (
 tenant_id UUID NOT NULL,owner_module VARCHAR(16) NOT NULL,source_kind VARCHAR(32) NOT NULL,
 source_id UUID NOT NULL,observation_id UUID NOT NULL DEFAULT gen_random_uuid(),
 observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,observation_id),
 FOREIGN KEY(tenant_id,owner_module,source_kind,source_id)
 REFERENCES crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id)
);
CREATE INDEX idx_int_fact_availability_lookup ON crm.int_fact_availability_observation
 (tenant_id,owner_module,source_kind,source_id,observed_at);
CREATE FUNCTION crm.int_availability_observation_stamp() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 NEW.observed_at:=clock_timestamp();
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_availability_stamp BEFORE INSERT ON crm.int_fact_availability_observation
 FOR EACH ROW EXECUTE FUNCTION crm.int_availability_observation_stamp();
CREATE TRIGGER trg_int_availability_immutable BEFORE UPDATE OR DELETE ON crm.int_fact_availability_observation
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_availability_no_truncate BEFORE TRUNCATE ON crm.int_fact_availability_observation
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TABLE crm.int_fact_availability_observation;
--rollback DROP FUNCTION crm.int_availability_observation_stamp();

--changeset rm:0216-int-feature-candidate-store dbms:postgresql splitStatements:false
CREATE TABLE crm.int_feature_candidate (
 tenant_id UUID NOT NULL,generation_id UUID NOT NULL,feature_id UUID NOT NULL,
 contract_version INTEGER NOT NULL CHECK(contract_version>0),
 numeric_policy_version INTEGER NOT NULL CHECK(numeric_policy_version>0),
 method_version INTEGER NOT NULL CHECK(method_version>0),
 observed_at TIMESTAMPTZ NOT NULL,
 persisted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
 availability VARCHAR(48) NOT NULL CHECK(availability='CURRENT_SNAPSHOT_ONLY'),
 PRIMARY KEY(tenant_id,feature_id),
 FOREIGN KEY(tenant_id,generation_id) REFERENCES crm.int_projection_generation(tenant_id,generation_id),
 CHECK(observed_at<=persisted_at)
);
CREATE INDEX idx_int_feature_candidate_generation ON crm.int_feature_candidate(tenant_id,generation_id,observed_at);
CREATE TRIGGER trg_int_feature_candidate_immutable BEFORE UPDATE OR DELETE ON crm.int_feature_candidate
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_feature_candidate_no_truncate BEFORE TRUNCATE ON crm.int_feature_candidate
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
--rollback DROP TABLE crm.int_feature_candidate;


--changeset rm:0217-int-feature-evidence-hardening dbms:postgresql splitStatements:false
-- Existing candidate rows remain legacy and cannot enter the new reader.
ALTER TABLE crm.int_feature_candidate ADD COLUMN proof_id UUID,
 ADD COLUMN source_revision BIGINT, ADD COLUMN dirty_revision BIGINT,
 ADD COLUMN receipt_revision BIGINT, ADD COLUMN materialization_revision BIGINT,
 ADD COLUMN lifecycle_revision BIGINT, ADD COLUMN activity_policy_version INTEGER,
 ADD COLUMN activity VARCHAR(32), ADD COLUMN payload_sha256 VARCHAR(64),
 ADD COLUMN request_key UUID, ADD COLUMN actor_id UUID;
ALTER TABLE crm.int_feature_candidate ADD CONSTRAINT fk_int_feature_proof
 FOREIGN KEY(tenant_id,generation_id,proof_id) REFERENCES crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id);
ALTER TABLE crm.int_feature_candidate ADD CONSTRAINT uq_int_feature_request UNIQUE(tenant_id,request_key);
ALTER TABLE crm.int_feature_candidate ADD CONSTRAINT ck_int_feature_evidence CHECK (
 request_key IS NULL OR (
 proof_id IS NOT NULL AND actor_id IS NOT NULL AND source_revision IS NOT NULL AND source_revision>=0
 AND dirty_revision IS NOT NULL AND dirty_revision>=0 AND receipt_revision IS NOT NULL AND receipt_revision>=0
 AND materialization_revision IS NOT NULL AND materialization_revision>=0 AND lifecycle_revision IS NOT NULL AND lifecycle_revision>=0
 AND activity_policy_version IS NOT NULL AND activity_policy_version>0
 AND activity IS NOT NULL AND activity IN ('INSUFFICIENT_HISTORY','PERIOD_GAP','PARTIAL_COVERAGE','OPEN_PERIOD','STALE','INVALID','NOT_APPLICABLE','DISABLED')
 AND payload_sha256 IS NOT NULL AND payload_sha256 ~ '^[0-9a-f]{64}$'));
CREATE FUNCTION crm.int_feature_evidence_stamp() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 NEW.persisted_at:=clock_timestamp();
 IF NEW.request_key IS NULL THEN RAISE EXCEPTION 'INT_FEATURE_EVIDENCE_REQUIRED'; END IF;
 IF NOT EXISTS(SELECT 1 FROM crm.int_dim_coverage_proof p
 WHERE p.tenant_id=NEW.tenant_id AND p.generation_id=NEW.generation_id AND p.proof_id=NEW.proof_id
 AND (p.source_revision,p.dirty_revision,p.receipt_revision,p.materialization_revision,p.lifecycle_revision)=
 (NEW.source_revision,NEW.dirty_revision,NEW.receipt_revision,NEW.materialization_revision,NEW.lifecycle_revision)
 AND p.delivery_mismatches=0 AND p.contribution_mismatches=0 AND p.period_mismatches=0 AND p.cell_mismatches=0 AND p.membership_mismatches=0)
 THEN RAISE EXCEPTION 'INT_FEATURE_PROOF_MISMATCH'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_int_feature_evidence_stamp BEFORE INSERT ON crm.int_feature_candidate
 FOR EACH ROW EXECUTE FUNCTION crm.int_feature_evidence_stamp();
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_FEATURE_EVIDENCE_FORWARD_ONLY'; END; $$;

--changeset rm:0218-int-availability-idempotent-observer dbms:postgresql
-- Preserve old observations, do not infer historic commit timestamps.
CREATE INDEX idx_int_availability_source_lookup ON crm.int_fact_availability_observation(tenant_id,owner_module,source_kind,source_id);
--rollback DROP INDEX crm.idx_int_availability_source_lookup;


--changeset rm:0219-int-observation-unique-and-feature-hash dbms:postgresql splitStatements:false
-- Fail rather than delete historical evidence if an earlier writer created duplicates.
ALTER TABLE crm.int_fact_availability_observation ADD CONSTRAINT uq_int_availability_source
 UNIQUE(tenant_id,owner_module,source_kind,source_id);
ALTER TABLE crm.int_feature_candidate ADD CONSTRAINT ck_int_feature_payload_hash
 CHECK(request_key IS NULL OR payload_sha256=encode(sha256(convert_to(payload::text,'UTF8')),'hex'));
REVOKE ALL ON crm.int_fact_availability_observation,crm.int_feature_candidate FROM PUBLIC;
REVOKE ALL ON FUNCTION crm.int_availability_observation_stamp(),crm.int_feature_evidence_stamp() FROM PUBLIC;
--rollback ALTER TABLE crm.int_feature_candidate DROP CONSTRAINT ck_int_feature_payload_hash;
--rollback ALTER TABLE crm.int_fact_availability_observation DROP CONSTRAINT uq_int_availability_source;
-- Privilege hardening is intentionally not reversed.


--changeset rm:0220-int-feature-payload-binding dbms:postgresql splitStatements:false
ALTER TABLE crm.int_feature_candidate ADD CONSTRAINT ck_int_feature_payload_binding CHECK (
 request_key IS NULL OR (
 (payload #> '{request,generation}') IS NOT DISTINCT FROM to_jsonb(generation_id)
 AND (payload #> '{request,proof}') IS NOT DISTINCT FROM to_jsonb(proof_id)
 AND (payload #> '{request,contractVersion}') IS NOT DISTINCT FROM to_jsonb(contract_version)
 AND (payload #> '{request,methodVersion}') IS NOT DISTINCT FROM to_jsonb(method_version)
 AND (payload #> '{request,numericPolicyVersion}') IS NOT DISTINCT FROM to_jsonb(numeric_policy_version)
 AND (payload #> '{request,activityPolicyVersion}') IS NOT DISTINCT FROM to_jsonb(activity_policy_version)
 AND (payload->'sourceRevision') IS NOT DISTINCT FROM to_jsonb(source_revision)
 AND (payload->'dirtyRevision') IS NOT DISTINCT FROM to_jsonb(dirty_revision)
 AND (payload->'receiptRevision') IS NOT DISTINCT FROM to_jsonb(receipt_revision)
 AND (payload->'materializationRevision') IS NOT DISTINCT FROM to_jsonb(materialization_revision)
 AND (payload->'lifecycleRevision') IS NOT DISTINCT FROM to_jsonb(lifecycle_revision)
 AND (payload->'activity') IS NOT DISTINCT FROM to_jsonb(activity)
 AND (payload->'decisionEligibility') IS NOT DISTINCT FROM to_jsonb('BLOCKED'::text)
 AND jsonb_typeof(payload->'inputs') IS NOT DISTINCT FROM 'array'
 AND (payload->>'observedAt')::timestamptz IS NOT DISTINCT FROM observed_at
 ));
--rollback ALTER TABLE crm.int_feature_candidate DROP CONSTRAINT ck_int_feature_payload_binding;


--changeset rm:0221-int-controlled-l3-activation dbms:postgresql splitStatements:false
-- Frozen L3 generations; L1 routing remains disabled. No public API admission.
ALTER TABLE crm.int_projection_lifecycle DROP CONSTRAINT int_projection_lifecycle_check;
ALTER TABLE crm.int_projection_lifecycle ADD CONSTRAINT ck_int_lifecycle_transition CHECK(
 (lifecycle_state='BUILDING' AND revision=0) OR
 (lifecycle_state IN ('ABORTED','ACTIVE') AND revision=1) OR
 (lifecycle_state='RETIRED' AND revision=2));
CREATE UNIQUE INDEX uq_int_single_active_generation ON crm.int_projection_lifecycle(tenant_id)
 WHERE lifecycle_state='ACTIVE';
ALTER TABLE crm.int_active_generation DROP CONSTRAINT int_active_generation_projection_code_check;
ALTER TABLE crm.int_active_generation ALTER COLUMN proof_id DROP NOT NULL;
ALTER TABLE crm.int_active_generation ADD COLUMN dim_proof_id UUID, ADD COLUMN admission_proof_id UUID;
ALTER TABLE crm.int_active_generation ADD CONSTRAINT ck_int_pointer_kind CHECK(
 (projection_code='L1' AND proof_id IS NOT NULL AND dim_proof_id IS NULL AND admission_proof_id IS NULL) OR
 (projection_code='L3' AND proof_id IS NULL AND dim_proof_id IS NOT NULL AND admission_proof_id IS NOT NULL));
ALTER TABLE crm.int_active_generation ADD CONSTRAINT fk_int_pointer_dim_proof
 FOREIGN KEY(tenant_id,generation_id,dim_proof_id) REFERENCES crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id);
ALTER TABLE crm.int_active_generation ADD CONSTRAINT fk_int_pointer_admission_proof
 FOREIGN KEY(tenant_id,generation_id,admission_proof_id) REFERENCES crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id);
CREATE OR REPLACE FUNCTION crm.int_guard_generation_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF NEW.lifecycle_state='BUILDING' AND NEW.revision=0 THEN RETURN NEW; END IF;
 ELSIF TG_OP='UPDATE' THEN
   IF OLD.tenant_id=NEW.tenant_id AND OLD.generation_id=NEW.generation_id
      AND NEW.changed_at>=OLD.changed_at AND NEW.revision=OLD.revision+1
      AND ((OLD.lifecycle_state='BUILDING' AND NEW.lifecycle_state IN ('ABORTED','ACTIVE'))
           OR (OLD.lifecycle_state='ACTIVE' AND NEW.lifecycle_state='RETIRED'))
   THEN RETURN NEW; END IF;
 END IF;
 RAISE EXCEPTION 'INT_GENERATION_LIFECYCLE_TRANSITION' USING ERRCODE='23514';
END; $$;
-- At commit every ACTIVE generation must have the L3 pointer. L1 remains disabled.
CREATE FUNCTION crm.int_assert_active_pointer() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE t uuid;
BEGIN
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id; ELSE t:=NEW.tenant_id; END IF;
 IF EXISTS(SELECT 1 FROM crm.int_projection_lifecycle l
   WHERE l.tenant_id=t AND l.lifecycle_state='ACTIVE' AND NOT EXISTS(
     SELECT 1 FROM crm.int_active_generation a WHERE a.tenant_id=l.tenant_id
       AND a.generation_id=l.generation_id AND a.projection_code='L3'))
 OR EXISTS(SELECT 1 FROM crm.int_active_generation a WHERE a.tenant_id=t AND a.projection_code='L1')
 THEN RAISE EXCEPTION 'INT_ACTIVE_POINTER_REQUIRED' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER trg_int_activation_lifecycle AFTER INSERT OR UPDATE OR DELETE ON crm.int_projection_lifecycle
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crm.int_assert_active_pointer();
CREATE CONSTRAINT TRIGGER trg_int_activation_pointer AFTER INSERT OR UPDATE OR DELETE ON crm.int_active_generation
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crm.int_assert_active_pointer();
REVOKE ALL ON FUNCTION crm.int_assert_active_pointer() FROM PUBLIC;
REVOKE ALL ON crm.int_active_generation,crm.int_projection_lifecycle FROM PUBLIC;
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_ACTIVATION_FORWARD_ONLY'; END; $$;


--changeset rm:0224-int-analytics-job-run dbms:postgresql splitStatements:false
-- R1.1A storage only; no scheduler execution or runtime grants.
CREATE TABLE crm.int_analytics_job_run (
  tenant_id uuid NOT NULL,
  job_id uuid NOT NULL,
  job_type varchar(16) NOT NULL CHECK (job_type IN ('NIGHT','INTRADAY')),
  scheduled_slot timestamptz NOT NULL,
  scheduled_at timestamptz NOT NULL,
  business_day date NOT NULL,
  calendar_version bigint NOT NULL,
  zone_id varchar(64) NOT NULL,
  from_day date,
  until_day date,
  generation_id uuid, -- allocated before generation creation; verify binding at execution
  expected_previous uuid,
  active_proof_id uuid,
  source_revision bigint CHECK (source_revision >= 0),
  actual_as_of timestamptz,
  status varchar(16) NOT NULL CHECK (status IN
    ('PENDING','RUNNING','COMPLETED','FAILED','BLOCKED','SKIPPED')),
  next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_owner uuid,
  lease_until timestamptz,
  cluster_slot smallint CHECK (cluster_slot BETWEEN 1 AND 4),
  attempt_token bigint NOT NULL DEFAULT 0 CHECK (attempt_token >= 0),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  consecutive_failures integer NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
  last_error_code varchar(96),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id,job_id),
  UNIQUE (tenant_id,job_type,scheduled_slot),
  FOREIGN KEY (tenant_id,calendar_version)
    REFERENCES crm.int_calendar_policy(tenant_id,version),
  FOREIGN KEY (tenant_id,generation_id,active_proof_id)
    REFERENCES crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id),
  CHECK ((job_type='NIGHT' AND generation_id IS NOT NULL
       AND from_day IS NOT NULL AND until_day IS NOT NULL AND from_day < until_day)
      OR (job_type='INTRADAY' AND generation_id IS NULL AND from_day IS NULL
       AND until_day IS NULL AND expected_previous IS NULL AND active_proof_id IS NULL)),
  CHECK ((status='RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
       AND cluster_slot IS NOT NULL)
      OR (status<>'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL
       AND cluster_slot IS NULL)),
  CHECK (active_proof_id IS NULL OR status='COMPLETED'),
  CHECK (status<>'COMPLETED' OR (actual_as_of IS NOT NULL
       AND source_revision IS NOT NULL
       AND (job_type<>'NIGHT' OR active_proof_id IS NOT NULL)))
);
CREATE INDEX idx_int_analytics_job_due
 ON crm.int_analytics_job_run(next_attempt_at,scheduled_slot,tenant_id)
 WHERE status='PENDING';
-- Hard DB ceiling = four logical slots; initial admission uses slots 1..2 only.
-- Expired RUNNING retains its slot until reclaimed or fenced terminal transition.
CREATE UNIQUE INDEX uq_int_analytics_job_running_slot
 ON crm.int_analytics_job_run(cluster_slot) WHERE status='RUNNING';
-- At most one logical RUNNING job per tenant, across NIGHT and INTRADAY.
CREATE UNIQUE INDEX uq_int_analytics_job_tenant_running
 ON crm.int_analytics_job_run(tenant_id) WHERE status='RUNNING';
CREATE INDEX idx_int_analytics_job_expired
 ON crm.int_analytics_job_run(lease_until,cluster_slot) WHERE status='RUNNING';
-- Prevent two nightly runs for the same logical business day even across calendar revisions.
CREATE UNIQUE INDEX uq_int_analytics_job_night_day
 ON crm.int_analytics_job_run(tenant_id,business_day)
 WHERE job_type='NIGHT' AND status<>'SKIPPED';
-- Never run two unfinished NIGHT generations for one tenant.
CREATE UNIQUE INDEX uq_int_analytics_job_night_open
 ON crm.int_analytics_job_run(tenant_id)
 WHERE job_type='NIGHT' AND status IN ('PENDING','RUNNING','BLOCKED','FAILED');
REVOKE ALL ON crm.int_analytics_job_run FROM PUBLIC;

CREATE FUNCTION crm.int_analytics_job_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF NEW.status<>'PENDING' OR NEW.attempt_token<>0 OR NEW.attempts<>0
      OR NEW.consecutive_failures<>0 THEN
     RAISE EXCEPTION 'INT_JOB_INITIAL_STATE' USING ERRCODE='23514';
   END IF;
   RETURN NEW;
 END IF;
 IF (OLD.tenant_id,OLD.job_id,OLD.job_type,OLD.scheduled_slot,OLD.scheduled_at,
     OLD.business_day,OLD.calendar_version,OLD.zone_id,OLD.from_day,OLD.until_day,
     OLD.generation_id,OLD.expected_previous,OLD.created_at)
    IS DISTINCT FROM
    (NEW.tenant_id,NEW.job_id,NEW.job_type,NEW.scheduled_slot,NEW.scheduled_at,
     NEW.business_day,NEW.calendar_version,NEW.zone_id,NEW.from_day,NEW.until_day,
     NEW.generation_id,NEW.expected_previous,NEW.created_at) THEN
   RAISE EXCEPTION 'INT_JOB_SCOPE_IMMUTABLE' USING ERRCODE='23514';
 END IF;
 IF OLD.status IN ('COMPLETED','SKIPPED') THEN
   RAISE EXCEPTION 'INT_JOB_TERMINAL_IMMUTABLE' USING ERRCODE='23514';
 END IF;
 IF NEW.status='RUNNING' AND (OLD.status<>'RUNNING'
      OR NEW.attempt_token<>OLD.attempt_token) THEN
   IF OLD.status NOT IN ('PENDING','RUNNING')
      OR (OLD.status='RUNNING' AND (OLD.lease_until>clock_timestamp()
          OR NEW.cluster_slot<>OLD.cluster_slot))
      OR NEW.attempt_token<>OLD.attempt_token+1
      OR NEW.attempts<>OLD.attempts+1
      OR NEW.lease_until<=clock_timestamp() THEN
     RAISE EXCEPTION 'INT_JOB_CLAIM_FENCE' USING ERRCODE='23514';
   END IF;
 ELSIF NEW.attempt_token<>OLD.attempt_token OR NEW.attempts<>OLD.attempts
      OR (NEW.status='RUNNING' AND
          (NEW.lease_owner IS DISTINCT FROM OLD.lease_owner
           OR NEW.cluster_slot IS DISTINCT FROM OLD.cluster_slot
           OR OLD.lease_until<=clock_timestamp()
           OR NEW.lease_until<=clock_timestamp())) THEN
   RAISE EXCEPTION 'INT_JOB_ATTEMPT_FENCE' USING ERRCODE='23514';
 END IF;
 IF NEW.status='COMPLETED' AND (OLD.status<>'RUNNING'
      OR OLD.lease_until<=clock_timestamp()) THEN
   RAISE EXCEPTION 'INT_JOB_COMPLETION_FENCE' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION crm.int_analytics_job_guard() FROM PUBLIC;
CREATE TRIGGER trg_int_analytics_job_guard BEFORE INSERT OR UPDATE ON crm.int_analytics_job_run
 FOR EACH ROW EXECUTE FUNCTION crm.int_analytics_job_guard();
--rollback DO $$ BEGIN IF EXISTS(SELECT 1 FROM crm.int_analytics_job_run) THEN RAISE EXCEPTION 'INT_JOB_ROLLBACK_UNSAFE'; END IF; END; $$;
--rollback DROP TABLE crm.int_analytics_job_run;
--rollback DROP FUNCTION crm.int_analytics_job_guard();


--changeset rm:0225-iam-principal-kind
-- Existing human accounts retain their user semantics. Deployment inventory
-- must approve classification of any existing workload account separately.
ALTER TABLE public.iam_user_account ADD COLUMN principal_kind VARCHAR(16) DEFAULT 'HUMAN' NOT NULL;
ALTER TABLE public.iam_user_account ADD CONSTRAINT ck_iam_principal_kind CHECK(principal_kind IN ('HUMAN','SERVICE'));
--rollback ALTER TABLE public.iam_user_account DROP CONSTRAINT ck_iam_principal_kind;
--rollback ALTER TABLE public.iam_user_account DROP COLUMN principal_kind;

--changeset rm:0226-int-intraday-result dbms:postgresql splitStatements:false
CREATE FUNCTION public.iam_principal_kind_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF OLD.principal_kind='SERVICE' AND (NEW.principal_kind<>'SERVICE' OR NEW.external_subject IS DISTINCT FROM OLD.external_subject)
 THEN RAISE EXCEPTION 'IAM_SERVICE_CLASSIFICATION_IMMUTABLE' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.iam_principal_kind_guard() FROM PUBLIC;
CREATE TRIGGER trg_iam_principal_kind BEFORE UPDATE OF principal_kind,external_subject ON public.iam_user_account
 FOR EACH ROW EXECUTE FUNCTION public.iam_principal_kind_guard();
-- No runtime GRANT or service account provisioning in this changeset.
CREATE TABLE crm.int_intraday_result (
 tenant_id uuid NOT NULL,job_id uuid NOT NULL,
 schema_version smallint NOT NULL DEFAULT 1 CHECK(schema_version=1),
 consumer_code varchar(48) NOT NULL DEFAULT 'INTRADAY_SNAPSHOT_STORE_V1' CHECK(consumer_code='INTRADAY_SNAPSHOT_STORE_V1'),
 service_account_id uuid NOT NULL,scope_version bigint NOT NULL CHECK(scope_version>0),
 scope_digest char(64) NOT NULL CHECK(scope_digest ~ '^[0-9a-f]{64}$'),
 metric_code varchar(32) NOT NULL DEFAULT 'BOOKED_ORDERS' CHECK(metric_code='BOOKED_ORDERS'),
 method_version integer NOT NULL DEFAULT 1 CHECK(method_version=1),
 day_start timestamptz NOT NULL,day_end timestamptz NOT NULL CHECK(day_start<day_end),
 actual_as_of timestamptz,read_observed_at timestamptz,source_revision bigint CHECK(source_revision>=0),
 metric_value bigint CHECK(metric_value>0),quality varchar(16) CHECK(quality IN ('PARTIAL','MISSING')),
 quality_reason varchar(64),result_digest char(64) CHECK(result_digest ~ '^[0-9a-f]{64}$'),acknowledged_at timestamptz,
 PRIMARY KEY(tenant_id,job_id),FOREIGN KEY(tenant_id,job_id) REFERENCES crm.int_analytics_job_run(tenant_id,job_id),
 CHECK((acknowledged_at IS NULL AND actual_as_of IS NULL AND read_observed_at IS NULL AND source_revision IS NULL
   AND metric_value IS NULL AND quality IS NULL AND quality_reason IS NULL AND result_digest IS NULL)
 OR (acknowledged_at IS NOT NULL AND actual_as_of IS NOT NULL AND read_observed_at IS NOT NULL
   AND source_revision IS NOT NULL AND quality IS NOT NULL AND quality_reason IS NOT NULL AND result_digest IS NOT NULL
   AND day_start<=actual_as_of AND actual_as_of<day_end AND actual_as_of<=read_observed_at
   AND read_observed_at<=acknowledged_at
   AND ((quality='PARTIAL' AND metric_value IS NOT NULL AND quality_reason='PERIOD_NOT_CLOSED')
     OR (quality='MISSING' AND metric_value IS NULL AND quality_reason='NO_QUALIFIED_OBSERVATION'))))
);
REVOKE ALL ON crm.int_intraday_result FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM crm.int_analytics_job_run WHERE job_type='INTRADAY' AND status='COMPLETED')
 THEN RAISE EXCEPTION 'INT_INTRADAY_LEGACY_COMPLETED_REQUIRES_RECONCILIATION'; END IF;
END $$;

CREATE FUNCTION crm.int_intraday_result_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE j crm.int_analytics_job_run%ROWTYPE;
BEGIN
 SELECT * INTO j FROM crm.int_analytics_job_run WHERE tenant_id=NEW.tenant_id AND job_id=NEW.job_id;
 IF NOT FOUND OR j.job_type<>'INTRADAY' OR j.scheduled_slot<NEW.day_start OR j.scheduled_slot>=NEW.day_end
 THEN RAISE EXCEPTION 'INT_INTRADAY_PARENT' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
   IF j.status<>'PENDING' OR NEW.acknowledged_at IS NOT NULL
   THEN RAISE EXCEPTION 'INT_INTRADAY_INITIAL_STATE' USING ERRCODE='23514'; END IF;
 ELSE
   IF OLD.acknowledged_at IS NOT NULL OR NEW.acknowledged_at IS NULL
    OR (OLD.tenant_id,OLD.job_id,OLD.schema_version,OLD.consumer_code,OLD.service_account_id,OLD.scope_version,
        OLD.scope_digest,OLD.metric_code,OLD.method_version,OLD.day_start,OLD.day_end)
       IS DISTINCT FROM
       (NEW.tenant_id,NEW.job_id,NEW.schema_version,NEW.consumer_code,NEW.service_account_id,NEW.scope_version,
        NEW.scope_digest,NEW.metric_code,NEW.method_version,NEW.day_start,NEW.day_end)
    OR j.status<>'RUNNING' OR j.lease_until<=clock_timestamp() OR NEW.actual_as_of<j.scheduled_slot
   THEN RAISE EXCEPTION 'INT_INTRADAY_IMMUTABLE_OR_FENCE' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_intraday_guard BEFORE INSERT OR UPDATE ON crm.int_intraday_result
 FOR EACH ROW EXECUTE FUNCTION crm.int_intraday_result_guard();
CREATE TRIGGER trg_int_intraday_no_delete BEFORE DELETE ON crm.int_intraday_result
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_intraday_no_truncate BEFORE TRUNCATE ON crm.int_intraday_result
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();

CREATE FUNCTION crm.int_intraday_assert_pair() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE t uuid; j_id uuid; j crm.int_analytics_job_run%ROWTYPE; r crm.int_intraday_result%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='int_analytics_job_run' THEN
   IF TG_OP='DELETE' THEN
     IF OLD.job_type<>'INTRADAY' THEN RETURN NULL; END IF;
   ELSE
     IF NEW.job_type<>'INTRADAY' THEN RETURN NULL; END IF;
   END IF;
 END IF;
 IF TG_OP='DELETE' THEN t:=OLD.tenant_id;j_id:=OLD.job_id;
 ELSE t:=NEW.tenant_id;j_id:=NEW.job_id; END IF;
 SELECT * INTO j FROM crm.int_analytics_job_run WHERE tenant_id=t AND job_id=j_id;
 SELECT * INTO r FROM crm.int_intraday_result WHERE tenant_id=t AND job_id=j_id;
 IF (r.job_id IS NOT NULL AND (j.job_id IS NULL OR j.job_type<>'INTRADAY'))
 OR (j.job_type='INTRADAY' AND
      ((j.status='COMPLETED') IS DISTINCT FROM (r.acknowledged_at IS NOT NULL)
       OR (j.status='COMPLETED' AND (j.actual_as_of,j.source_revision) IS DISTINCT FROM (r.actual_as_of,r.source_revision))))
 THEN RAISE EXCEPTION 'INT_INTRADAY_ATOMIC_PAIR' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_int_intraday_job_pair AFTER INSERT OR UPDATE OR DELETE ON crm.int_analytics_job_run
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crm.int_intraday_assert_pair();
CREATE CONSTRAINT TRIGGER trg_int_intraday_result_pair AFTER INSERT OR UPDATE OR DELETE ON crm.int_intraday_result
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crm.int_intraday_assert_pair();

CREATE FUNCTION crm.int_intraday_register(t uuid,j uuid,actor uuid,v bigint,d text,b timestamptz,e timestamptz)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 -- Registration is not authorization to read. Nevertheless reject any
 -- foreign or overprivileged service account before creating a request.
 IF NOT EXISTS(SELECT 1 FROM public.iam_user_account u
    JOIN public.iam_workspace x ON x.id=t
    JOIN public.org_organization org ON org.id=x.organization_id
    JOIN public.iam_org_membership m ON m.organization_id=x.organization_id AND m.user_id=u.id
    JOIN public.iam_tenant_rbac_config cfg ON cfg.tenant_id=x.id
    WHERE u.id=actor AND u.status='ACTIVE' AND u.principal_kind='SERVICE'
      AND x.kind='ORGANIZATION' AND x.status='ACTIVE' AND org.status='ACTIVE'
      AND m.status='ACTIVE' AND m.role='MEMBER' AND cfg.enforced
      AND EXISTS(SELECT 1 FROM public.iam_tenant_user_role a
        JOIN public.iam_tenant_role rr ON rr.tenant_id=a.tenant_id AND rr.id=a.role_id
        JOIN public.iam_tenant_role_permission p ON p.tenant_id=rr.tenant_id AND p.role_id=rr.id
        WHERE a.tenant_id=t AND a.user_id=actor AND rr.active AND p.permission_code='SALES_ORDER_READ')
      AND NOT EXISTS(SELECT 1 FROM public.iam_tenant_user_role a
        JOIN public.iam_tenant_role rr ON rr.tenant_id=a.tenant_id AND rr.id=a.role_id
        JOIN public.iam_tenant_role_permission p ON p.tenant_id=rr.tenant_id AND p.role_id=rr.id
        WHERE a.tenant_id=t AND a.user_id=actor AND rr.active AND p.permission_code<>'SALES_ORDER_READ'))
 THEN RAISE EXCEPTION 'INT_INTRADAY_SERVICE_ACCOUNT' USING ERRCODE='23514'; END IF;
 INSERT INTO crm.int_intraday_result(tenant_id,job_id,service_account_id,scope_version,scope_digest,day_start,day_end)
 VALUES(t,j,actor,v,d,b,e);
 RETURN true;
END $$;

CREATE FUNCTION crm.int_intraday_ack(t uuid,j uuid,w uuid,tok bigint,sl integer,actor uuid,d text,
 a timestamptz,o timestamptz,rev bigint,n bigint,q text,reason text,digest text,ack timestamptz,deadline timestamptz)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE job crm.int_analytics_job_run%ROWTYPE;
BEGIN
 SELECT * INTO job FROM crm.int_analytics_job_run WHERE tenant_id=t AND job_id=j FOR UPDATE;
 IF NOT FOUND OR job.job_type<>'INTRADAY' OR job.status<>'RUNNING' OR job.lease_owner IS DISTINCT FROM w
 OR job.attempt_token<>tok OR job.cluster_slot<>sl OR job.lease_until<=clock_timestamp()
 OR clock_timestamp()>=deadline OR ack>clock_timestamp()
 OR NOT EXISTS(SELECT 1 FROM public.iam_workspace x JOIN public.org_organization org ON org.id=x.organization_id
    WHERE x.id=t AND x.kind='ORGANIZATION' AND x.status='ACTIVE' AND org.status='ACTIVE')
 OR job.calendar_version IS DISTINCT FROM (SELECT p.version FROM crm.int_calendar_policy p
    WHERE p.tenant_id=t AND p.effective_from<=clock_timestamp() ORDER BY p.version DESC LIMIT 1)
 OR NOT EXISTS(SELECT 1 FROM public.iam_user_account u
    JOIN public.iam_workspace x ON x.id=t
    JOIN public.iam_org_membership m ON m.organization_id=x.organization_id AND m.user_id=u.id
    JOIN public.iam_tenant_rbac_config cfg ON cfg.tenant_id=x.id
    WHERE u.id=actor AND u.status='ACTIVE' AND u.principal_kind='SERVICE'
      AND m.status='ACTIVE' AND m.role='MEMBER' AND cfg.enforced
      AND EXISTS(SELECT 1 FROM public.iam_tenant_user_role a
        JOIN public.iam_tenant_role rr ON rr.tenant_id=a.tenant_id AND rr.id=a.role_id
        JOIN public.iam_tenant_role_permission p ON p.tenant_id=rr.tenant_id AND p.role_id=rr.id
        WHERE a.tenant_id=t AND a.user_id=actor AND rr.active AND p.permission_code='SALES_ORDER_READ')
      AND NOT EXISTS(SELECT 1 FROM public.iam_tenant_user_role a
        JOIN public.iam_tenant_role rr ON rr.tenant_id=a.tenant_id AND rr.id=a.role_id
        JOIN public.iam_tenant_role_permission p ON p.tenant_id=rr.tenant_id AND p.role_id=rr.id
        WHERE a.tenant_id=t AND a.user_id=actor AND rr.active AND p.permission_code<>'SALES_ORDER_READ'))
 THEN RAISE EXCEPTION 'INT_INTRADAY_ACK_FENCE' USING ERRCODE='23514'; END IF;
 UPDATE crm.int_intraday_result SET actual_as_of=a,read_observed_at=o,source_revision=rev,
  metric_value=n,quality=q,quality_reason=reason,result_digest=digest,acknowledged_at=ack
 WHERE tenant_id=t AND job_id=j AND service_account_id=actor AND scope_digest=d
  AND acknowledged_at IS NULL AND clock_timestamp()<day_end AND deadline<=day_end;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_INTRADAY_RESULT_FENCE' USING ERRCODE='23514'; END IF;
 UPDATE crm.int_analytics_job_run SET status='COMPLETED',actual_as_of=a,source_revision=rev,
  consecutive_failures=0,last_error_code=NULL,lease_owner=NULL,lease_until=NULL,cluster_slot=NULL,updated_at=clock_timestamp()
 WHERE tenant_id=t AND job_id=j AND status='RUNNING' AND lease_owner=w AND attempt_token=tok
  AND cluster_slot=sl AND lease_until>clock_timestamp() AND clock_timestamp()<deadline;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_INTRADAY_COMPLETION_FENCE' USING ERRCODE='23514'; END IF;
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION crm.int_intraday_result_guard(),crm.int_intraday_assert_pair(),
 crm.int_intraday_register(uuid,uuid,uuid,bigint,text,timestamptz,timestamptz),
 crm.int_intraday_ack(uuid,uuid,uuid,bigint,integer,uuid,text,timestamptz,timestamptz,bigint,bigint,text,text,text,timestamptz,timestamptz)
 FROM PUBLIC;
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_INTRADAY_FORWARD_ONLY'; END; $$;


--changeset rm:0227-int-report-storage-foundation dbms:postgresql splitStatements:false
-- RM-ARS-R1 P3/P4: dormant storage foundation. No runtime grants, scheduler,
-- publication, automatic cleanup or report authorization admission.
CREATE SCHEMA reporting;
REVOKE ALL ON SCHEMA reporting FROM PUBLIC;

CREATE TABLE reporting.int_report_instance (
 tenant_id uuid NOT NULL REFERENCES public.iam_workspace(id),
 instance_id uuid NOT NULL,
 idempotency_key varchar(128) NOT NULL CHECK(length(trim(idempotency_key))>0),
 request_digest varchar(64) NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 scope_digest varchar(64) NOT NULL CHECK(scope_digest ~ '^[0-9a-f]{64}$'),
 report_code varchar(32) NOT NULL,
 template_version varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,instance_id),
 UNIQUE(tenant_id,idempotency_key)
);
CREATE INDEX idx_int_report_instance_created
 ON reporting.int_report_instance(tenant_id,created_at DESC,instance_id);

-- Durable catalog/tombstone identity; future dates are precreated, not allocatable.
CREATE TABLE reporting.int_report_partition_day (
 staging_date date PRIMARY KEY,
 child_oid oid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 retired_at timestamptz,
 CHECK(retired_at IS NULL OR retired_at>=created_at)
);

CREATE TABLE reporting.int_report_dataset_attempt (
 tenant_id uuid NOT NULL REFERENCES public.iam_workspace(id),
 dataset_id uuid NOT NULL,
 staging_date date NOT NULL REFERENCES reporting.int_report_partition_day(staging_date),
 allocated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 scope_digest varchar(64) NOT NULL CHECK(scope_digest ~ '^[0-9a-f]{64}$'),
 PRIMARY KEY(tenant_id,dataset_id),
 UNIQUE(tenant_id,dataset_id,staging_date),
 CHECK(staging_date=(allocated_at AT TIME ZONE 'UTC')::date)
);

CREATE TABLE reporting.int_report_dataset (
 staging_date date NOT NULL,
 tenant_id uuid NOT NULL,
 dataset_id uuid NOT NULL,
 row_key bigint NOT NULL CHECK(row_key>0),
 metric_code varchar(64) NOT NULL,
 method_version bigint NOT NULL CHECK(method_version>0),
 from_inclusive timestamptz NOT NULL,
 until_exclusive timestamptz NOT NULL CHECK(until_exclusive>from_inclusive),
 dimensions jsonb NOT NULL CHECK(jsonb_typeof(dimensions)='array'),
 unit_code varchar(64) NOT NULL,
 currency varchar(3) CHECK(currency ~ '^[A-Z]{3}$'),
 metric_value numeric CHECK(metric_value NOT IN ('NaN'::numeric,'Infinity'::numeric,'-Infinity'::numeric)),
 quality_code varchar(16) NOT NULL CHECK(quality_code IN ('READY','PARTIAL','MISSING','STALE','NOT_MEASURABLE')),
 quality_reason varchar(128),
 PRIMARY KEY(staging_date,tenant_id,dataset_id,row_key),
 FOREIGN KEY(tenant_id,dataset_id,staging_date)
 REFERENCES reporting.int_report_dataset_attempt(tenant_id,dataset_id,staging_date)
) PARTITION BY RANGE(staging_date);

-- No DEFAULT partition. No redundant tenant/date/dataset index: the PK covers
-- equality predicates followed by ordered row_key pagination.
REVOKE ALL ON ALL TABLES IN SCHEMA reporting FROM PUBLIC;

-- Foundation identities are immutable. State/consumer/publication extensions
-- must be introduced by subsequent reviewed changesets, not by runtime grants.
CREATE TRIGGER trg_int_report_instance_immutable BEFORE UPDATE OR DELETE ON reporting.int_report_instance
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_report_attempt_immutable BEFORE UPDATE OR DELETE ON reporting.int_report_dataset_attempt
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_report_row_immutable BEFORE UPDATE OR DELETE ON reporting.int_report_dataset
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_report_dataset_no_truncate BEFORE TRUNCATE ON reporting.int_report_dataset
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();

-- Narrow maintenance primitive, owner-only until separate privilege qualification.
-- Two-int advisory namespace 1380794706 reserved here for report lifecycle.
-- Serialize creation on a fixed maintenance key, then date key. Never recreate
-- a catalogued missing/retired child. Reject unexpected existing relation.
CREATE FUNCTION reporting.int_report_ensure_day(d date) RETURNS boolean
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE today date; child text; actual oid; registered reporting.int_report_partition_day%ROWTYPE;
BEGIN
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock(1380794706,-2147483648);
 today:=(clock_timestamp() AT TIME ZONE 'UTC')::date;
 IF d<today OR d>today+2 THEN RAISE EXCEPTION 'INT_REPORT_DATE_HORIZON'; END IF;
 PERFORM pg_advisory_xact_lock(1380794706,d-date '1970-01-01');
 today:=(clock_timestamp() AT TIME ZONE 'UTC')::date;
 IF d<today OR d>today+2 THEN RAISE EXCEPTION 'INT_REPORT_DATE_HORIZON'; END IF;
 child:='int_report_dataset_'||to_char(d,'YYYYMMDD');
 actual:=to_regclass(format('reporting.%I',child));
 SELECT * INTO registered FROM reporting.int_report_partition_day WHERE staging_date=d;
 IF FOUND THEN
   IF registered.retired_at IS NOT NULL OR actual IS DISTINCT FROM registered.child_oid
      OR NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhrelid=actual
          AND inhparent='reporting.int_report_dataset'::regclass)
   THEN RAISE EXCEPTION 'INT_REPORT_PARTITION_DRIFT'; END IF;
   -- Repeat creation is deliberately not a catalog/grant certification.
   RETURN false;
 END IF;
 IF actual IS NOT NULL THEN RAISE EXCEPTION 'INT_REPORT_UNREGISTERED_PARTITION'; END IF;
 EXECUTE format('CREATE TABLE reporting.%I PARTITION OF reporting.int_report_dataset FOR VALUES FROM (%L) TO (%L)',child,d,d+1);
 EXECUTE format('REVOKE ALL ON reporting.%I FROM PUBLIC',child);
 actual:=to_regclass(format('reporting.%I',child));
 INSERT INTO reporting.int_report_partition_day(staging_date,child_oid) VALUES(d,actual);
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION reporting.int_report_ensure_day(date) FROM PUBLIC;
-- No automatic invocation, no DROP routine. Caller must set bounded lock and
-- statement timeouts; full bounds/index/grant reconciliation and pin/consumer
-- lifecycle are prerequisites for admission.
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_STORAGE_FORWARD_ONLY'; END; $$;


--changeset rm:0228-int-report-dataset-build-consumer dbms:postgresql splitStatements:false
-- Dormant owner-only build primitives. No runtime IAM grants, publication,
-- terminal abandonment, pin API or DROP admission. 10k rows/attempt and 10min
-- build lease are bounded experiment limits, not production SLA/configuration.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM reporting.int_report_dataset_attempt)
 THEN RAISE EXCEPTION 'INT_REPORT_LEGACY_ATTEMPT_REQUIRES_RECONCILIATION'; END IF;
END $$;
ALTER TABLE reporting.int_report_dataset_attempt
 ADD COLUMN source_manifest_digest varchar(64) NOT NULL CHECK(source_manifest_digest ~ '^[0-9a-f]{64}$'),
 ADD COLUMN expected_rows bigint NOT NULL CHECK(expected_rows BETWEEN 0 AND 10000),
 ADD COLUMN expected_digest varchar(64) NOT NULL CHECK(expected_digest ~ '^[0-9a-f]{64}$'),
 ADD COLUMN build_state varchar(16) NOT NULL CHECK(build_state IN ('BUILDING','VALIDATING','READY')),
 ADD COLUMN build_owner uuid NOT NULL,
 ADD COLUMN build_token bigint NOT NULL CHECK(build_token=1),
 ADD COLUMN build_until timestamptz NOT NULL,
 ADD COLUMN observed_rows bigint,
 ADD COLUMN observed_digest varchar(64),
 ADD COLUMN ready_at timestamptz,
 ADD CONSTRAINT ck_int_report_build_evidence CHECK(
 (build_state IN ('BUILDING','VALIDATING') AND observed_rows IS NULL AND observed_digest IS NULL AND ready_at IS NULL)
 OR (build_state='READY' AND observed_rows IS NOT NULL AND observed_digest IS NOT NULL
     AND observed_rows=expected_rows AND observed_digest=expected_digest AND ready_at IS NOT NULL));
DROP TRIGGER trg_int_report_attempt_immutable ON reporting.int_report_dataset_attempt;
CREATE TRIGGER trg_int_report_attempt_identity BEFORE UPDATE OF tenant_id,dataset_id,staging_date,allocated_at,
 scope_digest,source_manifest_digest,expected_rows,expected_digest,build_owner,build_token,build_until
 OR DELETE ON reporting.int_report_dataset_attempt FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();

CREATE FUNCTION reporting.int_report_build_transition() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF NEW.build_state<>'BUILDING' OR NEW.build_until<=clock_timestamp()
   THEN RAISE EXCEPTION 'INT_REPORT_BUILD_INITIAL'; END IF;
 ELSE
   IF NOT ((OLD.build_state='BUILDING' AND NEW.build_state='VALIDATING')
       OR (OLD.build_state='VALIDATING' AND NEW.build_state='READY'))
      OR OLD.build_until<=clock_timestamp()
   THEN RAISE EXCEPTION 'INT_REPORT_BUILD_TRANSITION'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_report_build_transition BEFORE INSERT OR UPDATE ON reporting.int_report_dataset_attempt
 FOR EACH ROW EXECUTE FUNCTION reporting.int_report_build_transition();

-- All admitted callers must take the shared day fence in a separate SQL
-- statement BEFORE touching staging relations; no runtime caller admitted yet.
CREATE FUNCTION reporting.int_report_assert_day(d date) RETURNS void
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE child oid;
BEGIN
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 SELECT child_oid INTO child FROM reporting.int_report_partition_day
 WHERE staging_date=d AND retired_at IS NULL;
 IF NOT FOUND OR child IS DISTINCT FROM to_regclass(format('reporting.%I','int_report_dataset_'||to_char(d,'YYYYMMDD')))::oid
    OR NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhparent='reporting.int_report_dataset'::regclass AND inhrelid=child)
 THEN RAISE EXCEPTION 'INT_REPORT_PARTITION_UNAVAILABLE'; END IF;
END $$;

CREATE FUNCTION reporting.int_report_allocate(t uuid,a uuid,d date,w uuid,s text,src text,n bigint,dig text)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE now_at timestamptz;
BEGIN
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 now_at:=clock_timestamp();
 IF d<>(now_at AT TIME ZONE 'UTC')::date THEN RAISE EXCEPTION 'INT_REPORT_ALLOCATION_DATE'; END IF;
 INSERT INTO reporting.int_report_dataset_attempt(tenant_id,dataset_id,staging_date,allocated_at,scope_digest,
 source_manifest_digest,expected_rows,expected_digest,build_state,build_owner,build_token,build_until)
 VALUES(t,a,d,now_at,s,src,n,dig,'BUILDING',w,1,now_at+interval '10 minutes');
 RETURN true;
END $$;

CREATE FUNCTION reporting.int_report_lock_build(t uuid,a uuid,d date,w uuid,tok bigint,required_state text)
 RETURNS reporting.int_report_dataset_attempt LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE;
BEGIN
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 SELECT * INTO b FROM reporting.int_report_dataset_attempt WHERE tenant_id=t AND dataset_id=a AND staging_date=d FOR NO KEY UPDATE;
 IF NOT FOUND OR b.build_state IS DISTINCT FROM required_state OR b.build_owner IS DISTINCT FROM w
 OR b.build_token IS DISTINCT FROM tok OR b.build_until<=clock_timestamp()
 THEN RAISE EXCEPTION 'INT_REPORT_BUILD_FENCE'; END IF;
 RETURN b;
END $$;

CREATE FUNCTION reporting.int_report_row_insert_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE;
BEGIN
 -- Direct owner INSERT is not a supported API. This last-line guard prevents
 -- mutation after freeze; owner-only append routine supplies the worker fence.
 SELECT * INTO b FROM reporting.int_report_dataset_attempt
 WHERE tenant_id=NEW.tenant_id AND dataset_id=NEW.dataset_id AND staging_date=NEW.staging_date FOR NO KEY UPDATE;
 IF NOT FOUND OR b.build_state<>'BUILDING' OR b.build_until<=clock_timestamp()
 OR NEW.row_key>b.expected_rows THEN RAISE EXCEPTION 'INT_REPORT_ROW_BUILD_STATE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_report_row_insert BEFORE INSERT ON reporting.int_report_dataset
 FOR EACH ROW EXECUTE FUNCTION reporting.int_report_row_insert_guard();

CREATE FUNCTION reporting.int_report_append(t uuid,a uuid,d date,w uuid,tok bigint,
 k bigint,metric text,method bigint,lo timestamptz,hi timestamptz,dims jsonb,u text,c text,v numeric,q text,r text)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE; oldrow reporting.int_report_dataset%ROWTYPE;
BEGIN
 b:=reporting.int_report_lock_build(t,a,d,w,tok,'BUILDING');
 IF k IS NULL OR k<1 OR k>b.expected_rows THEN RAISE EXCEPTION 'INT_REPORT_ROW_RANGE'; END IF;
 SELECT * INTO oldrow FROM reporting.int_report_dataset
 WHERE staging_date=d AND tenant_id=t AND dataset_id=a AND row_key=k;
 IF FOUND THEN
   IF (oldrow.metric_code,oldrow.method_version,oldrow.from_inclusive,oldrow.until_exclusive,oldrow.dimensions,
       oldrow.unit_code,oldrow.currency,oldrow.metric_value,oldrow.quality_code,oldrow.quality_reason)
      IS DISTINCT FROM (metric,method,lo,hi,dims,u,c,v,q,r)
   THEN RAISE EXCEPTION 'INT_REPORT_ROW_REPLAY_CONFLICT'; END IF;
   RETURN false;
 END IF;
 INSERT INTO reporting.int_report_dataset(staging_date,tenant_id,dataset_id,row_key,metric_code,method_version,
 from_inclusive,until_exclusive,dimensions,unit_code,currency,metric_value,quality_code,quality_reason)
 VALUES(d,t,a,k,metric,method,lo,hi,dims,u,c,v,q,r);
 RETURN true;
END $$;

CREATE FUNCTION reporting.int_report_freeze(t uuid,a uuid,d date,w uuid,tok bigint)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE; n bigint;
BEGIN
 b:=reporting.int_report_lock_build(t,a,d,w,tok,'BUILDING');
 SELECT count(*) INTO n FROM reporting.int_report_dataset WHERE staging_date=d AND tenant_id=t AND dataset_id=a;
 -- Unique ordinal and 1..expected_rows bound make equal count sufficient
 -- for ordinal completeness, not for analytical/source completeness.
 IF n<>b.expected_rows THEN RAISE EXCEPTION 'INT_REPORT_ROW_COUNT'; END IF;
 UPDATE reporting.int_report_dataset_attempt SET build_state='VALIDATING' WHERE tenant_id=t AND dataset_id=a;
 RETURN true;
END $$;

-- Trusted owner-side validator only: observed digest must be independently
-- recomputed by Java from frozen typed rows; SQL does not calculate the digest.
CREATE FUNCTION reporting.int_report_seal(t uuid,a uuid,d date,w uuid,tok bigint,n bigint,dig text)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE;
BEGIN
 b:=reporting.int_report_lock_build(t,a,d,w,tok,'VALIDATING');
 IF n IS DISTINCT FROM b.expected_rows OR dig IS DISTINCT FROM b.expected_digest
 THEN RAISE EXCEPTION 'INT_REPORT_VALIDATION_MISMATCH'; END IF;
 UPDATE reporting.int_report_dataset_attempt SET build_state='READY',observed_rows=n,observed_digest=dig,
 ready_at=clock_timestamp() WHERE tenant_id=t AND dataset_id=a;
 RETURN true;
END $$;

CREATE TABLE reporting.int_report_consumer (
 tenant_id uuid NOT NULL,instance_id uuid NOT NULL,consumer_id uuid NOT NULL,
 dataset_id uuid NOT NULL,staging_date date NOT NULL,
 state varchar(24) NOT NULL DEFAULT 'REGISTERED' CHECK(state IN ('REGISTERED','ACTIVE','PUBLISHED_PENDING_ACK','PUBLISHED_ACK','ABANDONED')),
 token bigint NOT NULL DEFAULT 0 CHECK(token>=0),
 lease_owner uuid,lease_until timestamptz,
 publication_digest varchar(64) CHECK(publication_digest ~ '^[0-9a-f]{64}$'),
 published_at timestamptz,terminal_at timestamptz,
 PRIMARY KEY(tenant_id,instance_id,consumer_id),
 FOREIGN KEY(tenant_id,instance_id) REFERENCES reporting.int_report_instance(tenant_id,instance_id),
 FOREIGN KEY(tenant_id,dataset_id,staging_date) REFERENCES reporting.int_report_dataset_attempt(tenant_id,dataset_id,staging_date),
 CHECK((state='REGISTERED' AND token=0 AND lease_owner IS NULL AND lease_until IS NULL)
 OR (state='ACTIVE' AND token>0 AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
 OR (state IN ('PUBLISHED_PENDING_ACK','PUBLISHED_ACK','ABANDONED') AND lease_owner IS NULL AND lease_until IS NULL)),
 CHECK((state IN ('PUBLISHED_PENDING_ACK','PUBLISHED_ACK') AND publication_digest IS NOT NULL AND published_at IS NOT NULL)
 OR (state IN ('REGISTERED','ACTIVE','ABANDONED') AND publication_digest IS NULL AND published_at IS NULL)),
 CHECK((state IN ('PUBLISHED_ACK','ABANDONED') AND terminal_at IS NOT NULL)
 OR (state IN ('REGISTERED','ACTIVE','PUBLISHED_PENDING_ACK') AND terminal_at IS NULL))
);
CREATE INDEX idx_int_report_consumer_dataset ON reporting.int_report_consumer(staging_date,tenant_id,dataset_id);
CREATE TRIGGER trg_int_report_consumer_identity BEFORE UPDATE OF tenant_id,instance_id,consumer_id,dataset_id,staging_date
 OR DELETE ON reporting.int_report_consumer FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();

CREATE FUNCTION reporting.int_report_register_consumer(t uuid,i uuid,c uuid,a uuid,d date)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE;
BEGIN
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 SELECT * INTO b FROM reporting.int_report_dataset_attempt WHERE tenant_id=t AND dataset_id=a AND staging_date=d FOR NO KEY UPDATE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM reporting.int_report_instance
 WHERE tenant_id=t AND instance_id=i AND scope_digest=b.scope_digest)
 THEN RAISE EXCEPTION 'INT_REPORT_CONSUMER_SCOPE'; END IF;
 INSERT INTO reporting.int_report_consumer(tenant_id,instance_id,consumer_id,dataset_id,staging_date)
 VALUES(t,i,c,a,d);
 RETURN true;
END $$;

-- Claim is not a render pin or publication permit. Those APIs remain gated.
CREATE FUNCTION reporting.int_report_claim_consumer(t uuid,i uuid,c uuid,a uuid,d date,w uuid)
 RETURNS bigint LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE; x reporting.int_report_consumer%ROWTYPE; now_at timestamptz;
BEGIN
 IF d IS NULL OR w IS NULL THEN RAISE EXCEPTION 'INT_REPORT_CLAIM_INPUT'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 SELECT * INTO b FROM reporting.int_report_dataset_attempt WHERE tenant_id=t AND dataset_id=a AND staging_date=d FOR NO KEY UPDATE;
 IF NOT FOUND OR b.build_state<>'READY' THEN RAISE EXCEPTION 'INT_REPORT_DATASET_NOT_READY'; END IF;
 SELECT * INTO x FROM reporting.int_report_consumer WHERE tenant_id=t AND instance_id=i AND consumer_id=c
 AND dataset_id=a AND staging_date=d FOR NO KEY UPDATE;
 now_at:=clock_timestamp();
 IF NOT FOUND OR NOT (x.state='REGISTERED' OR (x.state='ACTIVE' AND x.lease_until<=now_at))
 THEN RAISE EXCEPTION 'INT_REPORT_CONSUMER_FENCE'; END IF;
 UPDATE reporting.int_report_consumer SET state='ACTIVE',token=token+1,lease_owner=w,lease_until=now_at+interval '5 minutes'
 WHERE tenant_id=t AND instance_id=i AND consumer_id=c RETURNING token INTO x.token;
 RETURN x.token;
END $$;

-- Diagnostic only. Never use as a deletion permit: publication ACK, pins,
-- abandonment policy, holds, orphan reconciliation and catalog certification
-- have not been implemented. Therefore no partition is eligible in this slice.
CREATE FUNCTION reporting.int_report_retention_blockers(d date) RETURNS text[]
 LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT array_remove(ARRAY[
 'RETENTION_NOT_ADMITTED',
 CASE WHEN d IS NULL OR d>=(statement_timestamp() AT TIME ZONE 'UTC')::date THEN 'NOT_SEALED' END,
 CASE WHEN EXISTS(SELECT 1 FROM reporting.int_report_dataset_attempt a WHERE a.staging_date=d AND a.build_state<>'READY') THEN 'BUILD_INCOMPLETE' END,
 CASE WHEN EXISTS(SELECT 1 FROM reporting.int_report_consumer c WHERE c.staging_date=d AND c.state NOT IN ('PUBLISHED_ACK','ABANDONED')) THEN 'CONSUMER_PENDING' END,
 CASE WHEN EXISTS(SELECT 1 FROM reporting.int_report_dataset_attempt a WHERE a.staging_date=d
 AND NOT EXISTS(SELECT 1 FROM reporting.int_report_consumer c WHERE c.tenant_id=a.tenant_id AND c.dataset_id=a.dataset_id AND c.staging_date=d)) THEN 'NO_CONSUMER_DISPOSITION' END
 ]::text[],NULL)
$$;
REVOKE ALL ON ALL TABLES IN SCHEMA reporting FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA reporting FROM PUBLIC;
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_BUILD_FORWARD_ONLY'; END; $$;


--changeset rm:0229-int-report-resource-batch-catalog dbms:postgresql splitStatements:false
-- Dormant owner-only experiment. No runtime grants or DROP. Storage budget
-- is conservative, not physical heap/TOAST size or canonical digest length.
-- Existing 0227/0228 checksums remain unchanged.
CREATE FUNCTION reporting.int_report_row_budget(metric text,u text,r text,dims jsonb,v numeric)
 RETURNS integer LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE x jsonb; n integer; text_value text;
BEGIN
 IF metric IS NULL OR u IS NULL OR dims IS NULL OR jsonb_typeof(dims)<>'array'
 OR length(metric)>64 OR length(u)>64 OR length(r)>128
 OR metric !~ '^[A-Za-z0-9_.:-]+$' OR u !~ '^[A-Za-z0-9_.:-]+$'
 OR (r IS NOT NULL AND r !~ '^[A-Za-z0-9_.:-]+$')
 THEN RAISE EXCEPTION 'INT_REPORT_ROW_BUDGET'; END IF;
 IF jsonb_array_length(dims)>32 THEN RAISE EXCEPTION 'INT_REPORT_DIMENSION_BUDGET'; END IF;
 FOR x IN SELECT value FROM jsonb_array_elements(dims) LOOP
   IF jsonb_typeof(x)<>'array' THEN RAISE EXCEPTION 'INT_REPORT_DIMENSION_SHAPE'; END IF;
   IF jsonb_array_length(x)<>3 OR jsonb_typeof(x->0) IS DISTINCT FROM 'string'
    OR jsonb_typeof(x->1) IS DISTINCT FROM 'string' OR jsonb_typeof(x->2) IS DISTINCT FROM 'string'
    OR length(x->>0)>128 OR length(x->>1)>128
    OR (x->>0) !~ '^[A-Za-z0-9_.:-]+$' OR (x->>1) !~ '^[A-Za-z0-9_.:-]+$'
    OR (x->>2) !~ '^[1-9][0-9]{0,18}$'
   THEN RAISE EXCEPTION 'INT_REPORT_DIMENSION_SHAPE'; END IF;
   IF (x->>2)::numeric>9223372036854775807::numeric THEN RAISE EXCEPTION 'INT_REPORT_DIMENSION_VERSION'; END IF;
 END LOOP;
 IF v IS NOT NULL THEN
   IF v IN ('NaN'::numeric,'Infinity'::numeric,'-Infinity'::numeric)
   THEN RAISE EXCEPTION 'INT_REPORT_DECIMAL_BUDGET'; END IF;
   -- Conservative bound on PostgreSQL numeric output BEFORE text conversion.
   -- Reject extreme stored scale even if trailing zeros would normalize away.
   IF scale(v)>16384 OR abs(v)>=1e16384::numeric THEN RAISE EXCEPTION 'INT_REPORT_DECIMAL_BUDGET'; END IF;
   text_value:=v::text;
   IF length(text_value)>16384 THEN RAISE EXCEPTION 'INT_REPORT_DECIMAL_BUDGET'; END IF;
 END IF;
 -- 512 bytes covers row key, method, timestamps, quality, currency,
 -- JSON punctuation and frame. JSONB display spaces and original numeric
 -- scale overestimate canonical v1 for admitted ASCII fields.
 n:=512+octet_length(metric)+octet_length(u)+coalesce(octet_length(r),0)
     +octet_length(dims::text)+coalesce(octet_length(text_value),0);
 IF n>16384 THEN RAISE EXCEPTION 'INT_REPORT_ROW_BYTE_BUDGET'; END IF;
 RETURN n;
END $$;

-- Store an independently computed conservative per-row budget; do not trust
-- caller-supplied byte counts. Backfill is deliberately not implicit.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM reporting.int_report_dataset_attempt)
 THEN RAISE EXCEPTION 'INT_REPORT_RESOURCE_UPGRADE_REQUIRES_EMPTY_ATTEMPTS'; END IF;
END $$;
ALTER TABLE reporting.int_report_dataset ADD COLUMN storage_budget_bytes integer
 GENERATED ALWAYS AS (reporting.int_report_row_budget(metric_code,unit_code,quality_reason,dimensions,metric_value)) STORED;
ALTER TABLE reporting.int_report_dataset ADD CONSTRAINT ck_int_report_storage_budget
 CHECK(storage_budget_bytes BETWEEN 1 AND 16384);

-- Catalog guard: verify exact daily bound, no DEFAULT, valid attached PK
-- and no explicit non-owner child ACL. Parent owner is not a runtime role.
-- DateStyle normalized so catalog bound rendering is deterministic.
CREATE OR REPLACE FUNCTION reporting.int_report_assert_day(d date) RETURNS void
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp SET DateStyle='ISO, YMD' AS $$
DECLARE child oid; parent_oid oid:='reporting.int_report_dataset'::regclass; bound text;
BEGIN
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 SELECT child_oid INTO child FROM reporting.int_report_partition_day WHERE staging_date=d AND retired_at IS NULL;
 IF NOT FOUND OR child IS DISTINCT FROM to_regclass(format('reporting.%I','int_report_dataset_'||to_char(d,'YYYYMMDD')))::oid
    OR NOT EXISTS(SELECT 1 FROM pg_inherits WHERE inhparent=parent_oid AND inhrelid=child)
 THEN RAISE EXCEPTION 'INT_REPORT_PARTITION_UNAVAILABLE'; END IF;
 SELECT pg_get_expr(relpartbound,oid) INTO bound FROM pg_class WHERE oid=child AND relkind='r' AND relispartition;
 IF bound IS DISTINCT FROM format('FOR VALUES FROM (%L) TO (%L)',d::text,(d+1)::text)
 OR EXISTS(SELECT 1 FROM pg_partitioned_table WHERE partrelid=parent_oid AND partdefid<>0)
 THEN RAISE EXCEPTION 'INT_REPORT_PARTITION_BOUNDS'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_index pi JOIN pg_inherits inh ON inh.inhparent=pi.indexrelid
 JOIN pg_index ci ON ci.indexrelid=inh.inhrelid
 WHERE pi.indrelid=parent_oid AND pi.indisprimary AND pi.indisvalid AND pi.indisready
 AND ci.indrelid=child AND ci.indisprimary AND ci.indisvalid AND ci.indisready)
 THEN RAISE EXCEPTION 'INT_REPORT_PARTITION_INDEX'; END IF;
 IF EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) acl
 WHERE c.oid IN (child,parent_oid) AND acl.grantee<>c.relowner)
 THEN RAISE EXCEPTION 'INT_REPORT_PARTITION_ACL'; END IF;
 IF EXISTS(SELECT 1 FROM pg_attribute att CROSS JOIN LATERAL aclexplode(att.attacl) acl
 JOIN pg_class c ON c.oid=att.attrelid
 WHERE att.attrelid IN (child,parent_oid) AND acl.grantee<>c.relowner)
 THEN RAISE EXCEPTION 'INT_REPORT_PARTITION_COLUMN_ACL'; END IF;
END $$;

-- Keep one maintenance authority. Existing routine is now private to the
-- owner; wrapper validates the result under its exclusive date fence.
ALTER FUNCTION reporting.int_report_ensure_day(date) RENAME TO int_report_ensure_day_internal;
CREATE FUNCTION reporting.int_report_ensure_day(d date) RETURNS boolean
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET DateStyle='ISO, YMD' AS $$
DECLARE created boolean;
BEGIN
 created:=reporting.int_report_ensure_day_internal(d);
 PERFORM reporting.int_report_assert_day(d);
 RETURN created;
END $$;

-- Canonical SQL ingress format: strict array of objects (no tenant/date
-- fields), exact numeric strings, UTC timestamp text with <=6 fractions.
-- Bounds are checked before expanding the recordset. No ON CONFLICT DO NOTHING.
CREATE FUNCTION reporting.int_report_append_batch(t uuid,a uuid,d date,w uuid,tok bigint, payload jsonb)
 RETURNS integer LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE; n integer; inserted integer;
 existing_bytes bigint; new_bytes bigint;
BEGIN
 IF payload IS NULL OR jsonb_typeof(payload)<>'array' THEN RAISE EXCEPTION 'INT_REPORT_BATCH_SHAPE'; END IF;
 IF jsonb_array_length(payload) NOT BETWEEN 1 AND 256 OR octet_length(payload::text)>4194304
 THEN RAISE EXCEPTION 'INT_REPORT_BATCH_BUDGET'; END IF;
 b:=reporting.int_report_lock_build(t,a,d,w,tok,'BUILDING');
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(payload) e(x)
 WHERE CASE WHEN jsonb_typeof(x)<>'object' THEN true ELSE
   NOT (x ?& ARRAY['row_key','metric_code','method_version','from_inclusive','until_exclusive',
       'dimensions','unit_code','currency','metric_value','quality_code','quality_reason'])
   OR (x - ARRAY['row_key','metric_code','method_version','from_inclusive','until_exclusive',
       'dimensions','unit_code','currency','metric_value','quality_code','quality_reason'])<>'{}'::jsonb
   OR jsonb_typeof(x->'row_key') IS DISTINCT FROM 'number'
   OR jsonb_typeof(x->'method_version') IS DISTINCT FROM 'number'
   OR jsonb_typeof(x->'metric_code') IS DISTINCT FROM 'string'
   OR jsonb_typeof(x->'unit_code') IS DISTINCT FROM 'string'
   OR jsonb_typeof(x->'quality_code') IS DISTINCT FROM 'string'
   OR jsonb_typeof(x->'currency') NOT IN ('null','string')
   OR jsonb_typeof(x->'quality_reason') NOT IN ('null','string')
   OR jsonb_typeof(x->'metric_value') NOT IN ('null','string')
   OR (x->>'metric_value' IS NOT NULL AND (length(x->>'metric_value')>16384
       OR (x->>'metric_value') !~ '^-?[0-9]+(\.[0-9]+)?$'))
   OR jsonb_typeof(x->'from_inclusive') IS DISTINCT FROM 'string'
   OR jsonb_typeof(x->'until_exclusive') IS DISTINCT FROM 'string'
   OR (x->>'from_inclusive') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$'
   OR (x->>'until_exclusive') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$'
 END) THEN RAISE EXCEPTION 'INT_REPORT_BATCH_SHAPE'; END IF;
 SELECT count(DISTINCT (x->>'row_key')::bigint) INTO n FROM jsonb_array_elements(payload) e(x);
 IF n<>jsonb_array_length(payload) THEN RAISE EXCEPTION 'INT_REPORT_BATCH_DUPLICATE_KEY'; END IF;
 IF EXISTS(SELECT 1 FROM jsonb_to_recordset(payload) AS x(row_key bigint)
 WHERE row_key IS NULL OR row_key<1 OR row_key>b.expected_rows)
 THEN RAISE EXCEPTION 'INT_REPORT_ROW_RANGE'; END IF;
 -- Compare typed values, including NULL; numeric equality ignores scale.
 IF EXISTS(SELECT 1 FROM jsonb_to_recordset(payload) AS x(row_key bigint,metric_code text,method_version bigint,
 from_inclusive timestamptz,until_exclusive timestamptz,dimensions jsonb,unit_code text,currency text,
 metric_value numeric,quality_code text,quality_reason text)
 JOIN reporting.int_report_dataset y ON y.staging_date=d AND y.tenant_id=t AND y.dataset_id=a AND y.row_key=x.row_key
 WHERE (y.metric_code,y.method_version,y.from_inclusive,y.until_exclusive,y.dimensions,y.unit_code,y.currency,
 y.metric_value,y.quality_code,y.quality_reason) IS DISTINCT FROM
 (x.metric_code,x.method_version,x.from_inclusive,x.until_exclusive,x.dimensions,x.unit_code,x.currency,
 x.metric_value,x.quality_code,x.quality_reason))
 THEN RAISE EXCEPTION 'INT_REPORT_ROW_REPLAY_CONFLICT'; END IF;
 SELECT coalesce(sum(storage_budget_bytes),0) INTO existing_bytes FROM reporting.int_report_dataset
 WHERE staging_date=d AND tenant_id=t AND dataset_id=a;
 SELECT coalesce(sum(reporting.int_report_row_budget(x.metric_code,x.unit_code,x.quality_reason,x.dimensions,x.metric_value)),0)
 INTO new_bytes FROM jsonb_to_recordset(payload) AS x(row_key bigint,metric_code text,unit_code text,quality_reason text,dimensions jsonb,metric_value numeric)
 WHERE NOT EXISTS(SELECT 1 FROM reporting.int_report_dataset y WHERE y.staging_date=d AND y.tenant_id=t AND y.dataset_id=a AND y.row_key=x.row_key);
 IF existing_bytes+new_bytes+1024>33554432 THEN RAISE EXCEPTION 'INT_REPORT_DATASET_BYTE_BUDGET'; END IF;
 INSERT INTO reporting.int_report_dataset(staging_date,tenant_id,dataset_id,row_key,metric_code,method_version,
 from_inclusive,until_exclusive,dimensions,unit_code,currency,metric_value,quality_code,quality_reason)
 SELECT d,t,a,x.row_key,x.metric_code,x.method_version,x.from_inclusive,x.until_exclusive,x.dimensions,x.unit_code,x.currency,
 x.metric_value,x.quality_code,x.quality_reason
 FROM jsonb_to_recordset(payload) AS x(row_key bigint,metric_code text,method_version bigint,from_inclusive timestamptz,
 until_exclusive timestamptz,dimensions jsonb,unit_code text,currency text,metric_value numeric,quality_code text,quality_reason text)
 WHERE NOT EXISTS(SELECT 1 FROM reporting.int_report_dataset y WHERE y.staging_date=d AND y.tenant_id=t AND y.dataset_id=a AND y.row_key=x.row_key)
 ORDER BY x.row_key;
 GET DIAGNOSTICS inserted=ROW_COUNT;
 IF b.build_until<=clock_timestamp() THEN RAISE EXCEPTION 'INT_REPORT_BUILD_FENCE'; END IF;
 RETURN inserted;
END $$;

-- Legacy owner-side append delegates to the same bounded ingress. Timestamp
-- formatting fixed to UTC microseconds; v::text retains exact numeric value.
CREATE OR REPLACE FUNCTION reporting.int_report_append(t uuid,a uuid,d date,w uuid,tok bigint,
 k bigint,metric text,method bigint,lo timestamptz,hi timestamptz,dims jsonb,u text,c text,v numeric,q text,r text)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 PERFORM reporting.int_report_row_budget(metric,u,r,dims,v);
 RETURN reporting.int_report_append_batch(t,a,d,w,tok,jsonb_build_array(jsonb_build_object(
 'row_key',k,'metric_code',metric,'method_version',method,
 'from_inclusive',to_char(lo AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'until_exclusive',to_char(hi AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'dimensions',dims,'unit_code',u,'currency',c,'metric_value',v::text,'quality_code',q,'quality_reason',r)))=1;
END $$;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA reporting FROM PUBLIC;
-- Owner DML remains outside admitted API; cumulative budget enforced by
-- serialized ingress, not a claim of enforcement against the database owner.
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_BATCH_FORWARD_ONLY'; END; $$;


--changeset rm:0230-int-report-request-recovery dbms:postgresql splitStatements:false
-- Dormant owner-side request identity and attempt binding. No runtime grants,
-- publication permit, cleanup or reactivation of an expired build token.
CREATE TABLE reporting.int_report_request_attempt (
 tenant_id uuid NOT NULL, instance_id uuid NOT NULL, dataset_id uuid NOT NULL,
 staging_date date NOT NULL, selected_at timestamptz,
 PRIMARY KEY(tenant_id,instance_id,dataset_id),
 UNIQUE(tenant_id,dataset_id),
 FOREIGN KEY(tenant_id,instance_id) REFERENCES reporting.int_report_instance(tenant_id,instance_id),
 FOREIGN KEY(tenant_id,dataset_id,staging_date)
 REFERENCES reporting.int_report_dataset_attempt(tenant_id,dataset_id,staging_date)
);
CREATE UNIQUE INDEX uq_int_report_request_selected
 ON reporting.int_report_request_attempt(tenant_id,instance_id) WHERE selected_at IS NOT NULL;
CREATE TRIGGER trg_int_report_request_attempt_identity
 BEFORE UPDATE OF tenant_id,instance_id,dataset_id,staging_date OR DELETE
 ON reporting.int_report_request_attempt FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_report_request_attempt_no_truncate BEFORE TRUNCATE
 ON reporting.int_report_request_attempt FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();

CREATE FUNCTION reporting.int_report_request_selection_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF NEW.selected_at IS NOT NULL THEN RAISE EXCEPTION 'INT_REPORT_SELECTION_INITIAL'; END IF;
 ELSE
   IF OLD.selected_at IS NOT NULL OR NEW.selected_at IS NULL OR NOT EXISTS(
     SELECT 1 FROM reporting.int_report_dataset_attempt a
     WHERE a.tenant_id=NEW.tenant_id AND a.dataset_id=NEW.dataset_id AND a.staging_date=NEW.staging_date
       AND a.build_state='READY')
   THEN RAISE EXCEPTION 'INT_REPORT_SELECTION_TRANSITION'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM reporting.int_report_instance i
   JOIN reporting.int_report_dataset_attempt a ON a.tenant_id=i.tenant_id AND a.scope_digest=i.scope_digest
   WHERE i.tenant_id=NEW.tenant_id AND i.instance_id=NEW.instance_id
     AND a.dataset_id=NEW.dataset_id AND a.staging_date=NEW.staging_date)
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_report_request_selection BEFORE INSERT OR UPDATE ON reporting.int_report_request_attempt
 FOR EACH ROW EXECUTE FUNCTION reporting.int_report_request_selection_guard();

-- READ COMMITTED: conflict resolution and lookup are distinct statements.
CREATE FUNCTION reporting.int_report_register_request(t uuid,i uuid,k text,req text,s text,code text,ver text)
 RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE x reporting.int_report_instance%ROWTYPE;
BEGIN
 INSERT INTO reporting.int_report_instance(tenant_id,instance_id,idempotency_key,request_digest,scope_digest,report_code,template_version)
 VALUES(t,i,k,req,s,code,ver) ON CONFLICT(tenant_id,idempotency_key) DO NOTHING;
 SELECT * INTO x FROM reporting.int_report_instance WHERE tenant_id=t AND idempotency_key=k;
 IF NOT FOUND OR (x.request_digest,x.scope_digest,x.report_code,x.template_version)
 IS DISTINCT FROM (req,s,code,ver) THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_CONFLICT'; END IF;
 RETURN x.instance_id;
END $$;

-- Shared date fence first; lock only the logical request, never a previous
-- attempt under this lock. New attempt and durable binding commit together.
CREATE FUNCTION reporting.int_report_allocate_request(t uuid,i uuid,a uuid,d date,w uuid,s text,src text,n bigint,dig text)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 PERFORM 1 FROM reporting.int_report_instance WHERE tenant_id=t AND instance_id=i AND scope_digest=s FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 IF EXISTS(SELECT 1 FROM reporting.int_report_request_attempt l
   JOIN reporting.int_report_dataset_attempt b USING(tenant_id,dataset_id,staging_date)
   WHERE l.tenant_id=t AND l.instance_id=i
   AND (l.selected_at IS NOT NULL OR b.build_state='READY' OR b.build_until>clock_timestamp()))
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_ALREADY_HAS_RESULT_OR_ACTIVE_ATTEMPT'; END IF;
 PERFORM reporting.int_report_allocate(t,a,d,w,s,src,n,dig);
 INSERT INTO reporting.int_report_request_attempt(tenant_id,instance_id,dataset_id,staging_date) VALUES(t,i,a,d);
 RETURN true;
END $$;

-- Select exactly one READY attempt. No publication or access admission.
-- Day -> attempt -> instance; never acquire another day fence here.
CREATE FUNCTION reporting.int_report_select_request(t uuid,i uuid,a uuid,d date)
 RETURNS uuid LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE chosen uuid;
BEGIN
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 PERFORM 1 FROM reporting.int_report_dataset_attempt
 WHERE tenant_id=t AND dataset_id=a AND staging_date=d AND build_state='READY' FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_DATASET_NOT_READY'; END IF;
 PERFORM 1 FROM reporting.int_report_instance WHERE tenant_id=t AND instance_id=i FOR NO KEY UPDATE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM reporting.int_report_request_attempt
 WHERE tenant_id=t AND instance_id=i AND dataset_id=a AND staging_date=d)
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 SELECT dataset_id INTO chosen FROM reporting.int_report_request_attempt
 WHERE tenant_id=t AND instance_id=i AND selected_at IS NOT NULL;
 IF FOUND THEN RETURN chosen; END IF;
 UPDATE reporting.int_report_request_attempt SET selected_at=clock_timestamp()
 WHERE tenant_id=t AND instance_id=i AND dataset_id=a AND staging_date=d AND selected_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_SELECTION_LOST'; END IF;
 RETURN a;
END $$;
REVOKE ALL ON reporting.int_report_request_attempt FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA reporting FROM PUBLIC;
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_REQUEST_RECOVERY_FORWARD_ONLY'; END; $$;


--changeset rm:0231-int-report-source-evidence dbms:postgresql splitStatements:false
-- Preserve exact v1 framed evidence, not a coverage certificate. Owner-only.
CREATE TABLE reporting.int_report_source_evidence (
 tenant_id uuid NOT NULL,dataset_id uuid NOT NULL,staging_date date NOT NULL,
 scope_bytes bytea NOT NULL CHECK(octet_length(scope_bytes) BETWEEN 1 AND 65536),
 source_bytes bytea NOT NULL CHECK(octet_length(source_bytes) BETWEEN 1 AND 1048576),
 canonical_bytes bigint NOT NULL CHECK(canonical_bytes BETWEEN 1 AND 33554432),
 PRIMARY KEY(tenant_id,dataset_id),
 FOREIGN KEY(tenant_id,dataset_id,staging_date)
 REFERENCES reporting.int_report_dataset_attempt(tenant_id,dataset_id,staging_date)
);
CREATE FUNCTION reporting.int_report_source_evidence_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM reporting.int_report_dataset_attempt a
   JOIN reporting.int_report_request_attempt l USING(tenant_id,dataset_id,staging_date)
   WHERE a.tenant_id=NEW.tenant_id AND a.dataset_id=NEW.dataset_id AND a.staging_date=NEW.staging_date
     AND a.build_state='BUILDING' AND a.build_until>clock_timestamp()
     AND a.scope_digest=encode(sha256(NEW.scope_bytes),'hex')
     AND a.source_manifest_digest=encode(sha256(NEW.source_bytes),'hex'))
 THEN RAISE EXCEPTION 'INT_REPORT_SOURCE_EVIDENCE_MISMATCH'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_report_source_evidence_guard BEFORE INSERT ON reporting.int_report_source_evidence
 FOR EACH ROW EXECUTE FUNCTION reporting.int_report_source_evidence_guard();
CREATE TRIGGER trg_int_report_source_evidence_immutable BEFORE UPDATE OR DELETE ON reporting.int_report_source_evidence
 FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_report_source_evidence_no_truncate BEFORE TRUNCATE ON reporting.int_report_source_evidence
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
REVOKE ALL ON reporting.int_report_source_evidence FROM PUBLIC;
REVOKE ALL ON FUNCTION reporting.int_report_source_evidence_guard() FROM PUBLIC;
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_SOURCE_EVIDENCE_FORWARD_ONLY'; END; $$;


--changeset rm:0232-int-report-seal-allocation-fence dbms:postgresql splitStatements:false
-- Serialize READY transition of a bound attempt with replacement allocation.
-- Existing order: shared day -> attempt -> instance. Allocation takes only
-- day -> instance -> NEW attempt; it never locks an older attempt.
CREATE OR REPLACE FUNCTION reporting.int_report_seal(t uuid,a uuid,d date,w uuid,tok bigint,n bigint,dig text)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE; i uuid;
BEGIN
 b:=reporting.int_report_lock_build(t,a,d,w,tok,'VALIDATING');
 SELECT instance_id INTO i FROM reporting.int_report_request_attempt
 WHERE tenant_id=t AND dataset_id=a AND staging_date=d;
 IF FOUND THEN
   IF current_setting('transaction_isolation')<>'read committed'
   THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_ISOLATION'; END IF;
   PERFORM 1 FROM reporting.int_report_instance WHERE tenant_id=t AND instance_id=i FOR NO KEY UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
   -- The instance lock may have delayed us past the original build lease.
   IF b.build_until<=clock_timestamp() THEN RAISE EXCEPTION 'INT_REPORT_BUILD_FENCE'; END IF;
   IF EXISTS(SELECT 1 FROM reporting.int_report_request_attempt l
     JOIN reporting.int_report_dataset_attempt x USING(tenant_id,dataset_id,staging_date)
     WHERE l.tenant_id=t AND l.instance_id=i AND l.dataset_id<>a
       AND (x.build_state='READY' OR l.selected_at IS NOT NULL OR x.build_until>clock_timestamp()))
   THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SUPERSEDED'; END IF;
 END IF;
 IF n IS DISTINCT FROM b.expected_rows OR dig IS DISTINCT FROM b.expected_digest
 THEN RAISE EXCEPTION 'INT_REPORT_VALIDATION_MISMATCH'; END IF;
 UPDATE reporting.int_report_dataset_attempt SET build_state='READY',observed_rows=n,observed_digest=dig,
 ready_at=clock_timestamp() WHERE tenant_id=t AND dataset_id=a;
 RETURN true;
END $$;

CREATE OR REPLACE FUNCTION reporting.int_report_allocate_request(t uuid,i uuid,a uuid,d date,w uuid,s text,src text,n bigint,dig text)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF current_setting('transaction_isolation')<>'read committed'
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_ISOLATION'; END IF;
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 PERFORM 1 FROM reporting.int_report_instance WHERE tenant_id=t AND instance_id=i AND scope_digest=s FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 IF EXISTS(SELECT 1 FROM reporting.int_report_request_attempt l
   JOIN reporting.int_report_dataset_attempt b USING(tenant_id,dataset_id,staging_date)
   WHERE l.tenant_id=t AND l.instance_id=i
   AND (l.selected_at IS NOT NULL OR b.build_state='READY' OR b.build_until>clock_timestamp()))
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_ALREADY_HAS_RESULT_OR_ACTIVE_ATTEMPT'; END IF;
 PERFORM reporting.int_report_allocate(t,a,d,w,s,src,n,dig);
 INSERT INTO reporting.int_report_request_attempt(tenant_id,instance_id,dataset_id,staging_date) VALUES(t,i,a,d);
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION reporting.int_report_seal(uuid,uuid,date,uuid,bigint,bigint,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION reporting.int_report_allocate_request(uuid,uuid,uuid,date,uuid,text,text,bigint,text) FROM PUBLIC;
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_REQUEST_FENCE_FORWARD_ONLY'; END; $$;


--changeset rm:0233-int-report-terminal-attempt-recovery dbms:postgresql splitStatements:false
-- Owner-side terminal disposition only. No deletion admission or consumer abandonment.
-- Three total attempts is a bounded pilot policy, not a runtime SLA.
ALTER TABLE reporting.int_report_request_attempt ADD COLUMN abandoned_at timestamptz,
 ADD COLUMN abandon_reason varchar(32),
 ADD CONSTRAINT ck_int_report_request_disposition CHECK(
 (abandoned_at IS NULL AND abandon_reason IS NULL) OR
 (abandoned_at IS NOT NULL AND abandon_reason IS NOT NULL
 AND abandon_reason='LEASE_EXPIRED' AND selected_at IS NULL));

CREATE OR REPLACE FUNCTION reporting.int_report_request_selection_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE;
BEGIN
 SELECT * INTO b FROM reporting.int_report_dataset_attempt a
 WHERE a.tenant_id=NEW.tenant_id AND a.dataset_id=NEW.dataset_id AND a.staging_date=NEW.staging_date;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM reporting.int_report_instance i
 WHERE i.tenant_id=NEW.tenant_id AND i.instance_id=NEW.instance_id AND i.scope_digest=b.scope_digest)
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.selected_at IS NOT NULL OR NEW.abandoned_at IS NOT NULL OR NEW.abandon_reason IS NOT NULL
   THEN RAISE EXCEPTION 'INT_REPORT_SELECTION_INITIAL'; END IF;
 ELSE
   IF OLD.selected_at IS NOT NULL OR OLD.abandoned_at IS NOT NULL
   THEN RAISE EXCEPTION 'INT_REPORT_DISPOSITION_IMMUTABLE'; END IF;
   IF NEW.selected_at IS NOT NULL AND NEW.abandoned_at IS NULL AND NEW.abandon_reason IS NULL THEN
     IF b.build_state<>'READY' THEN RAISE EXCEPTION 'INT_REPORT_SELECTION_TRANSITION'; END IF;
   ELSIF NEW.selected_at IS NULL AND NEW.abandoned_at IS NOT NULL AND NEW.abandon_reason='LEASE_EXPIRED' THEN
     IF b.build_state='READY' OR b.build_until>clock_timestamp()
       OR NEW.abandoned_at<b.build_until
       OR NEW.abandoned_at>clock_timestamp()
       OR EXISTS(SELECT 1 FROM reporting.int_report_consumer c
          WHERE c.tenant_id=NEW.tenant_id AND c.dataset_id=NEW.dataset_id AND c.staging_date=NEW.staging_date)
     THEN RAISE EXCEPTION 'INT_REPORT_ABANDONMENT_DENIED'; END IF;
   ELSE RAISE EXCEPTION 'INT_REPORT_SELECTION_TRANSITION';
   END IF;
 END IF;
 RETURN NEW;
END $$;

-- Day -> old attempt -> instance. Never hold two dates or lock an old
-- attempt from allocation. Check committed state and lease after locks.
CREATE FUNCTION reporting.int_report_abandon_request_attempt(t uuid,i uuid,a uuid,d date)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE; l reporting.int_report_request_attempt%ROWTYPE;
BEGIN
 IF current_setting('transaction_isolation')<>'read committed'
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_ISOLATION'; END IF;
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 SELECT * INTO b FROM reporting.int_report_dataset_attempt
 WHERE tenant_id=t AND dataset_id=a AND staging_date=d FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 PERFORM 1 FROM reporting.int_report_instance WHERE tenant_id=t AND instance_id=i FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 SELECT * INTO l FROM reporting.int_report_request_attempt
 WHERE tenant_id=t AND instance_id=i AND dataset_id=a AND staging_date=d;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 IF l.abandoned_at IS NOT NULL OR l.selected_at IS NOT NULL OR b.build_state='READY'
 OR b.build_until>clock_timestamp() THEN RETURN false; END IF;
 IF EXISTS(SELECT 1 FROM reporting.int_report_consumer c WHERE c.tenant_id=t AND c.dataset_id=a AND c.staging_date=d)
 THEN RAISE EXCEPTION 'INT_REPORT_ABANDONMENT_DENIED'; END IF;
 UPDATE reporting.int_report_request_attempt SET abandoned_at=clock_timestamp(),abandon_reason='LEASE_EXPIRED'
 WHERE tenant_id=t AND instance_id=i AND dataset_id=a AND abandoned_at IS NULL AND selected_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_DISPOSITION_LOST'; END IF;
 RETURN true;
END $$;

CREATE OR REPLACE FUNCTION reporting.int_report_allocate_request(t uuid,i uuid,a uuid,d date,w uuid,s text,src text,n bigint,dig text)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF current_setting('transaction_isolation')<>'read committed'
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_ISOLATION'; END IF;
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 PERFORM 1 FROM reporting.int_report_instance WHERE tenant_id=t AND instance_id=i AND scope_digest=s FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 IF EXISTS(SELECT 1 FROM reporting.int_report_request_attempt l
   JOIN reporting.int_report_dataset_attempt b USING(tenant_id,dataset_id,staging_date)
   WHERE l.tenant_id=t AND l.instance_id=i
   AND (l.selected_at IS NOT NULL OR b.build_state='READY' OR b.build_until>clock_timestamp()))
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_ALREADY_HAS_RESULT_OR_ACTIVE_ATTEMPT'; END IF;
 IF EXISTS(SELECT 1 FROM reporting.int_report_request_attempt l
   WHERE l.tenant_id=t AND l.instance_id=i AND l.abandoned_at IS NULL)
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_DISPOSITION_REQUIRED'; END IF;
 IF (SELECT count(*) FROM reporting.int_report_request_attempt l WHERE l.tenant_id=t AND l.instance_id=i)>=3
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_RETRY_EXHAUSTED'; END IF;
 PERFORM reporting.int_report_allocate(t,a,d,w,s,src,n,dig);
 INSERT INTO reporting.int_report_request_attempt(tenant_id,instance_id,dataset_id,staging_date) VALUES(t,i,a,d);
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION reporting.int_report_abandon_request_attempt(uuid,uuid,uuid,date) FROM PUBLIC;
REVOKE ALL ON FUNCTION reporting.int_report_request_selection_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION reporting.int_report_allocate_request(uuid,uuid,uuid,date,uuid,text,text,bigint,text) FROM PUBLIC;
-- Retention remains NOT_ADMITTED: an abandoned binding is not a partition drop permit.
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_ATTEMPT_DISPOSITION_FORWARD_ONLY'; END; $$;


--changeset rm:0234-int-report-terminal-evidence-hardening dbms:postgresql splitStatements:false
-- Owner-side only. Explicit cancellation of an unselected READY result
-- is NOT lease expiry, deletion admission, or revocation of published files.
ALTER TABLE reporting.int_report_request_attempt DROP CONSTRAINT ck_int_report_request_disposition;
ALTER TABLE reporting.int_report_request_attempt ADD CONSTRAINT ck_int_report_request_disposition CHECK(
 (abandoned_at IS NULL AND abandon_reason IS NULL) OR
 (abandoned_at IS NOT NULL AND abandon_reason IS NOT NULL
 AND abandon_reason IN ('LEASE_EXPIRED','UNSELECTED_READY_CANCELLED') AND selected_at IS NULL));

CREATE OR REPLACE FUNCTION reporting.int_report_request_selection_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE;
BEGIN
 SELECT * INTO b FROM reporting.int_report_dataset_attempt a
 WHERE a.tenant_id=NEW.tenant_id AND a.dataset_id=NEW.dataset_id AND a.staging_date=NEW.staging_date;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM reporting.int_report_instance i
 WHERE i.tenant_id=NEW.tenant_id AND i.instance_id=NEW.instance_id AND i.scope_digest=b.scope_digest)
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.selected_at IS NOT NULL OR NEW.abandoned_at IS NOT NULL OR NEW.abandon_reason IS NOT NULL
   THEN RAISE EXCEPTION 'INT_REPORT_SELECTION_INITIAL'; END IF;
 ELSE
   IF OLD.selected_at IS NOT NULL OR OLD.abandoned_at IS NOT NULL
   THEN RAISE EXCEPTION 'INT_REPORT_DISPOSITION_IMMUTABLE'; END IF;
   IF NEW.selected_at IS NOT NULL AND NEW.abandoned_at IS NULL AND NEW.abandon_reason IS NULL THEN
     IF b.build_state<>'READY' THEN RAISE EXCEPTION 'INT_REPORT_SELECTION_TRANSITION'; END IF;
   ELSIF NEW.selected_at IS NULL AND NEW.abandoned_at IS NOT NULL THEN
     IF NEW.abandoned_at>clock_timestamp() OR EXISTS(SELECT 1 FROM reporting.int_report_consumer c
         WHERE c.tenant_id=NEW.tenant_id AND c.dataset_id=NEW.dataset_id AND c.staging_date=NEW.staging_date)
     THEN RAISE EXCEPTION 'INT_REPORT_ABANDONMENT_DENIED'; END IF;
     IF NEW.abandon_reason='LEASE_EXPIRED' THEN
       IF b.build_state='READY' OR b.build_until>clock_timestamp() OR NEW.abandoned_at<b.build_until
       THEN RAISE EXCEPTION 'INT_REPORT_ABANDONMENT_DENIED'; END IF;
     ELSIF NEW.abandon_reason='UNSELECTED_READY_CANCELLED' THEN
       IF b.build_state<>'READY' OR NEW.abandoned_at<b.ready_at
       THEN RAISE EXCEPTION 'INT_REPORT_ABANDONMENT_DENIED'; END IF;
     ELSE RAISE EXCEPTION 'INT_REPORT_SELECTION_TRANSITION';
     END IF;
   ELSE RAISE EXCEPTION 'INT_REPORT_SELECTION_TRANSITION';
   END IF;
 END IF;
 RETURN NEW;
END $$;

-- Bounded owner maintenance discovery; intentionally independent of actor
-- request digest and current business policy. No implicit cancellation.
CREATE FUNCTION reporting.int_report_disposition_candidates(t uuid,d date,n integer)
 RETURNS TABLE(instance_id uuid,dataset_id uuid,reason text)
 LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF t IS NULL OR d IS NULL OR n IS NULL OR n NOT BETWEEN 1 AND 256
 THEN RAISE EXCEPTION 'INT_REPORT_DISPOSITION_INPUT'; END IF;
 RETURN QUERY SELECT l.instance_id,l.dataset_id,
   CASE WHEN a.build_state='READY' THEN 'UNSELECTED_READY_CANCELLED' ELSE 'LEASE_EXPIRED' END
 FROM reporting.int_report_request_attempt l JOIN reporting.int_report_dataset_attempt a USING(tenant_id,dataset_id,staging_date)
 WHERE l.tenant_id=t AND l.staging_date=d AND l.selected_at IS NULL AND l.abandoned_at IS NULL
   AND (a.build_state='READY' OR a.build_until<=statement_timestamp())
   AND NOT EXISTS(SELECT 1 FROM reporting.int_report_consumer c
       WHERE c.tenant_id=l.tenant_id AND c.dataset_id=l.dataset_id AND c.staging_date=l.staging_date)
 ORDER BY l.instance_id,l.dataset_id LIMIT n;
END $$;

CREATE FUNCTION reporting.int_report_cancel_unselected_ready(t uuid,i uuid,a uuid,d date)
 RETURNS boolean LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE b reporting.int_report_dataset_attempt%ROWTYPE; l reporting.int_report_request_attempt%ROWTYPE;
BEGIN
 IF current_setting('transaction_isolation')<>'read committed'
 THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_ISOLATION'; END IF;
 IF d IS NULL THEN RAISE EXCEPTION 'INT_REPORT_DATE_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock_shared(1380794706,d-date '1970-01-01');
 PERFORM reporting.int_report_assert_day(d);
 SELECT * INTO b FROM reporting.int_report_dataset_attempt
 WHERE tenant_id=t AND dataset_id=a AND staging_date=d FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 PERFORM 1 FROM reporting.int_report_instance WHERE tenant_id=t AND instance_id=i FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 SELECT * INTO l FROM reporting.int_report_request_attempt
 WHERE tenant_id=t AND instance_id=i AND dataset_id=a AND staging_date=d;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_REQUEST_SCOPE'; END IF;
 IF l.selected_at IS NOT NULL OR l.abandoned_at IS NOT NULL OR b.build_state<>'READY' THEN RETURN false; END IF;
 IF EXISTS(SELECT 1 FROM reporting.int_report_consumer c WHERE c.tenant_id=t AND c.dataset_id=a AND c.staging_date=d)
 THEN RAISE EXCEPTION 'INT_REPORT_ABANDONMENT_DENIED'; END IF;
 UPDATE reporting.int_report_request_attempt SET abandoned_at=clock_timestamp(),abandon_reason='UNSELECTED_READY_CANCELLED'
 WHERE tenant_id=t AND instance_id=i AND dataset_id=a AND staging_date=d AND selected_at IS NULL AND abandoned_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_DISPOSITION_LOST'; END IF;
 RETURN true;
END $$;

-- Bound attempts must be selected for their own instance before any consumer
-- is registered. Serialize with cancellation using the same attempt lock.
CREATE FUNCTION reporting.int_report_consumer_binding_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE l reporting.int_report_request_attempt%ROWTYPE;
BEGIN
 PERFORM 1 FROM reporting.int_report_dataset_attempt
 WHERE tenant_id=NEW.tenant_id AND dataset_id=NEW.dataset_id AND staging_date=NEW.staging_date FOR NO KEY UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'INT_REPORT_CONSUMER_SCOPE'; END IF;
 SELECT * INTO l FROM reporting.int_report_request_attempt
 WHERE tenant_id=NEW.tenant_id AND dataset_id=NEW.dataset_id AND staging_date=NEW.staging_date;
 IF FOUND THEN
   IF l.instance_id<>NEW.instance_id OR l.selected_at IS NULL OR l.abandoned_at IS NOT NULL
   THEN RAISE EXCEPTION 'INT_REPORT_CONSUMER_SELECTION_REQUIRED'; END IF;
 ELSIF EXISTS(SELECT 1 FROM reporting.int_report_instance i
   WHERE i.tenant_id=NEW.tenant_id AND i.instance_id=NEW.instance_id AND i.report_code='RM-R02')
 THEN RAISE EXCEPTION 'INT_REPORT_CONSUMER_SELECTION_REQUIRED'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_report_consumer_binding BEFORE INSERT ON reporting.int_report_consumer
 FOR EACH ROW EXECUTE FUNCTION reporting.int_report_consumer_binding_guard();

-- Mandatory evidence for the real accounting pilot (not low-level storage
-- fixtures). Even a direct owner READY UPDATE must have matching evidence.
CREATE FUNCTION reporting.int_report_accounting_evidence_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF NEW.build_state='READY' AND EXISTS(
   SELECT 1 FROM reporting.int_report_request_attempt l
   JOIN reporting.int_report_instance i USING(tenant_id,instance_id)
   WHERE l.tenant_id=NEW.tenant_id AND l.dataset_id=NEW.dataset_id AND l.staging_date=NEW.staging_date
     AND i.report_code='RM-R02')
 AND NOT EXISTS(SELECT 1 FROM reporting.int_report_source_evidence e
   WHERE e.tenant_id=NEW.tenant_id AND e.dataset_id=NEW.dataset_id AND e.staging_date=NEW.staging_date
     AND NEW.scope_digest=encode(sha256(e.scope_bytes),'hex')
     AND NEW.source_manifest_digest=encode(sha256(e.source_bytes),'hex')
     AND substring(e.source_bytes FROM 1 FOR 18)=convert_to('15:RM-ARS-SOURCE-3','UTF8'))
 THEN RAISE EXCEPTION 'INT_REPORT_ACCOUNTING_EVIDENCE_REQUIRED'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_report_accounting_evidence BEFORE UPDATE OF build_state ON reporting.int_report_dataset_attempt
 FOR EACH ROW EXECUTE FUNCTION reporting.int_report_accounting_evidence_guard();

REVOKE ALL ON FUNCTION reporting.int_report_disposition_candidates(uuid,date,integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION reporting.int_report_cancel_unselected_ready(uuid,uuid,uuid,date) FROM PUBLIC;
REVOKE ALL ON FUNCTION reporting.int_report_consumer_binding_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION reporting.int_report_accounting_evidence_guard() FROM PUBLIC;
-- No scheduler, runtime grant, automatic READY cancellation or partition deletion.
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_TERMINAL_EVIDENCE_FORWARD_ONLY'; END; $$;

--changeset rm:0223-s1-native-booked-capture dbms:postgresql splitStatements:false
-- Prospective, opt-in system-of-record boundary for committed native RM orders only.
-- A single guard row per workspace, not an order/event/coverage ledger.
CREATE TABLE crm.int_native_booked_capture (
 tenant_id UUID PRIMARY KEY REFERENCES public.iam_workspace(id),
 started_at TIMESTAMPTZ,
 activated_by UUID REFERENCES public.iam_user_account(id),
 policy_version INTEGER NOT NULL DEFAULT 1 CHECK(policy_version=1),
 CHECK((started_at IS NULL)=(activated_by IS NULL))
);
-- Prevent a workspace INSERT in the gap between seeding and installing its trigger.
LOCK TABLE public.iam_workspace IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO crm.int_native_booked_capture(tenant_id) SELECT id FROM public.iam_workspace;
CREATE FUNCTION crm.int_native_capture_init() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 INSERT INTO crm.int_native_booked_capture(tenant_id) VALUES(NEW.id);
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_native_capture_init AFTER INSERT ON public.iam_workspace
 FOR EACH ROW EXECUTE FUNCTION crm.int_native_capture_init();
CREATE FUNCTION crm.int_native_capture_transition() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'S1_NATIVE_CAPTURE_IMMUTABLE'; END IF;
 IF OLD.started_at IS NOT NULL OR NEW.tenant_id<>OLD.tenant_id OR
    NEW.policy_version<>OLD.policy_version OR NEW.activated_by IS NULL
 THEN RAISE EXCEPTION 'S1_NATIVE_CAPTURE_IMMUTABLE'; END IF;
 -- UPDATE holds the exclusive row fence. All source inserts hold FOR SHARE.
 NEW.started_at:=clock_timestamp();
 IF EXISTS(SELECT 1 FROM crm.sales_order o WHERE o.tenant_id=NEW.tenant_id AND o.created_at>=NEW.started_at)
 OR EXISTS(SELECT 1 FROM crm.sales_order_status_event e WHERE e.tenant_id=NEW.tenant_id
    AND e.event_type='PLACED' AND e.occurred_at>=NEW.started_at)
 THEN RAISE EXCEPTION 'S1_NATIVE_EXISTING_FUTURE_SOURCE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_native_capture_transition BEFORE UPDATE OR DELETE ON crm.int_native_booked_capture
 FOR EACH ROW EXECUTE FUNCTION crm.int_native_capture_transition();
CREATE TRIGGER trg_int_native_capture_no_truncate BEFORE TRUNCATE ON crm.int_native_booked_capture
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE FUNCTION crm.int_native_capture_fence() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE boundary timestamptz; header_time timestamptz;
BEGIN
 IF TG_TABLE_NAME='sales_order_status_event' THEN
   IF NEW.event_type<>'PLACED' THEN RETURN NEW; END IF;
 END IF;
 -- Row exists from workspace creation; FOR SHARE detects concurrent activation
 -- even under RR (serialization failure rather than an obsolete inactive policy).
 SELECT started_at INTO STRICT boundary FROM crm.int_native_booked_capture WHERE tenant_id=NEW.tenant_id FOR SHARE;
 IF boundary IS NULL THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='sales_order' THEN
   IF NEW.created_at<boundary OR NEW.created_at>clock_timestamp()+interval '5 minutes' OR NEW.status<>'PLACED'
   THEN RAISE EXCEPTION 'S1_NATIVE_ORDER_BOUNDARY'; END IF;
 ELSE
   SELECT created_at INTO STRICT header_time FROM crm.sales_order WHERE tenant_id=NEW.tenant_id AND id=NEW.order_id FOR UPDATE;
   IF header_time<boundary OR NEW.occurred_at<>header_time
      OR EXISTS(SELECT 1 FROM crm.sales_order_status_event WHERE tenant_id=NEW.tenant_id AND order_id=NEW.order_id AND event_type='PLACED')
   THEN RAISE EXCEPTION 'S1_NATIVE_PLACED_IDENTITY'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_native_order_fence BEFORE INSERT ON crm.sales_order
 FOR EACH ROW EXECUTE FUNCTION crm.int_native_capture_fence();
CREATE TRIGGER trg_int_native_event_fence BEFORE INSERT ON crm.sales_order_status_event
 FOR EACH ROW EXECUTE FUNCTION crm.int_native_capture_fence();
-- Protect both the old and new tenant against moving an old row into an active
-- capture range. Lock guard rows in canonical tenant order.
CREATE FUNCTION crm.int_native_header_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE target uuid; boundary timestamptz;
BEGIN
 target:=OLD.tenant_id;
 IF TG_OP='UPDATE' THEN
   IF NEW.tenant_id=OLD.tenant_id AND NEW.id=OLD.id AND NEW.created_at=OLD.created_at THEN RETURN NEW; END IF;
   target:=NEW.tenant_id;
 END IF;
 FOR boundary IN SELECT started_at FROM crm.int_native_booked_capture
    WHERE tenant_id IN (OLD.tenant_id,target) ORDER BY tenant_id FOR SHARE LOOP
   IF boundary IS NOT NULL THEN RAISE EXCEPTION 'S1_NATIVE_HEADER_IDENTITY'; END IF;
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_native_header_guard BEFORE UPDATE OF tenant_id,id,created_at OR DELETE ON crm.sales_order
 FOR EACH ROW EXECUTE FUNCTION crm.int_native_header_guard();
CREATE TRIGGER trg_int_native_header_no_truncate BEFORE TRUNCATE ON crm.sales_order
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();

-- Checkout receipts are durable evidence in the activated tenant. Existing
-- inactive tenant row mutations are not newly forbidden; global TRUNCATE is unsafe.
CREATE FUNCTION crm.int_native_checkout_receipt_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE target uuid; boundary timestamptz;
BEGIN
 target:=OLD.tenant_id;
 IF TG_OP='UPDATE' THEN target:=NEW.tenant_id; END IF;
 FOR boundary IN SELECT started_at FROM crm.int_native_booked_capture
    WHERE tenant_id IN (OLD.tenant_id,target) ORDER BY tenant_id FOR SHARE LOOP
   IF boundary IS NOT NULL THEN RAISE EXCEPTION 'S1_NATIVE_CHECKOUT_RECEIPT_IMMUTABLE'; END IF;
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_native_checkout_receipt_guard BEFORE UPDATE OR DELETE ON crm.sales_checkout_receipt
 FOR EACH ROW EXECUTE FUNCTION crm.int_native_checkout_receipt_guard();
CREATE TRIGGER trg_int_native_checkout_receipt_no_truncate BEFORE TRUNCATE ON crm.sales_checkout_receipt
 FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE INDEX idx_int_native_checkout_receipt_order ON crm.sales_checkout_receipt(tenant_id,order_id);
CREATE FUNCTION crm.int_native_order_complete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE boundary timestamptz;
BEGIN
 SELECT started_at INTO STRICT boundary FROM crm.int_native_booked_capture WHERE tenant_id=NEW.tenant_id FOR SHARE;
 IF boundary IS NULL THEN RETURN NULL; END IF;
 IF NOT EXISTS(SELECT 1 FROM crm.sales_order o WHERE o.tenant_id=NEW.tenant_id AND o.id=NEW.id AND o.booked_snapshot_sealed)
 OR NOT EXISTS(SELECT 1 FROM crm.sales_order_line l WHERE l.tenant_id=NEW.tenant_id AND l.order_id=NEW.id)
 OR (SELECT count(*) FROM crm.sales_order_status_event e WHERE e.tenant_id=NEW.tenant_id AND e.order_id=NEW.id AND e.event_type='PLACED')<>1
 OR NOT EXISTS(SELECT 1 FROM crm.sales_order_status_event e JOIN crm.int_canonical_fact f
   ON f.tenant_id=e.tenant_id AND f.source_id=e.id AND f.subject_id=e.order_id AND f.owner_module='SALES'
      AND f.source_kind='ORDER_PLACED' AND f.occurred_at=e.occurred_at AND f.recorded_at IS NULL
      AND f.source_version IS NULL AND f.schema_version=2
   WHERE e.tenant_id=NEW.tenant_id AND e.order_id=NEW.id AND e.event_type='PLACED' AND e.occurred_at=NEW.created_at)
 OR NOT EXISTS(SELECT 1 FROM crm.sales_checkout_receipt r WHERE r.tenant_id=NEW.tenant_id AND r.order_id=NEW.id)
 THEN RAISE EXCEPTION 'S1_NATIVE_ORDER_TRANSACTION_INCOMPLETE'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_int_native_order_complete AFTER INSERT ON crm.sales_order
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION crm.int_native_order_complete();
REVOKE ALL ON crm.int_native_booked_capture FROM PUBLIC;
REVOKE ALL ON FUNCTION crm.int_native_capture_init(),crm.int_native_capture_transition(),crm.int_native_capture_fence(),
 crm.int_native_header_guard(),crm.int_native_order_complete(),crm.int_native_checkout_receipt_guard() FROM PUBLIC;
--rollback DO $$ BEGIN IF EXISTS(SELECT 1 FROM crm.int_native_booked_capture WHERE started_at IS NOT NULL) THEN RAISE EXCEPTION 'S1_NATIVE_CAPTURE_ROLLBACK_UNSAFE'; END IF; END; $$;
--rollback DROP TRIGGER trg_int_native_order_complete ON crm.sales_order;
--rollback DROP FUNCTION crm.int_native_order_complete();
--rollback DROP INDEX crm.idx_int_native_checkout_receipt_order;
--rollback DROP TRIGGER trg_int_native_checkout_receipt_no_truncate ON crm.sales_checkout_receipt;
--rollback DROP TRIGGER trg_int_native_checkout_receipt_guard ON crm.sales_checkout_receipt;
--rollback DROP FUNCTION crm.int_native_checkout_receipt_guard();
--rollback DROP TRIGGER trg_int_native_header_no_truncate ON crm.sales_order;
--rollback DROP TRIGGER trg_int_native_header_guard ON crm.sales_order;
--rollback DROP FUNCTION crm.int_native_header_guard();
--rollback DROP TRIGGER trg_int_native_event_fence ON crm.sales_order_status_event;
--rollback DROP TRIGGER trg_int_native_order_fence ON crm.sales_order;
--rollback DROP FUNCTION crm.int_native_capture_fence();
--rollback DROP TRIGGER trg_int_native_capture_init ON public.iam_workspace;
--rollback DROP FUNCTION crm.int_native_capture_init();
--rollback DROP TABLE crm.int_native_booked_capture;
--rollback DROP FUNCTION crm.int_native_capture_transition();

--changeset rm:0235-int-report-private-file-intent dbms:postgresql splitStatements:false
-- Owner-only immutable linkage before publication; no runtime grants or attachment.
CREATE TABLE reporting.int_report_file_intent (
 tenant_id uuid NOT NULL, instance_id uuid NOT NULL, dataset_id uuid NOT NULL,
 format_code varchar(8) NOT NULL CHECK(format_code IN ('XLSX','PDF')),
 file_id uuid NOT NULL, requester_id uuid NOT NULL REFERENCES public.iam_user_account(id),
 scope_digest varchar(64) NOT NULL CHECK(scope_digest ~ '^[0-9a-f]{64}$'),
 source_digest varchar(64) NOT NULL CHECK(source_digest ~ '^[0-9a-f]{64}$'),
 dataset_digest varchar(64) NOT NULL CHECK(dataset_digest ~ '^[0-9a-f]{64}$'),
 renderer_version varchar(64) NOT NULL CHECK(length(trim(renderer_version))>0),
 content_digest varchar(64) NOT NULL CHECK(content_digest ~ '^[0-9a-f]{64}$'),
 size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND CASE WHEN format_code='XLSX' THEN 2097152 ELSE 26214400 END),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,instance_id,format_code),
 UNIQUE(tenant_id,file_id),
 FOREIGN KEY(tenant_id,instance_id,dataset_id)
    REFERENCES reporting.int_report_request_attempt(tenant_id,instance_id,dataset_id),
 FOREIGN KEY(tenant_id,file_id) REFERENCES crm.file_object(tenant_id,id)
);
CREATE TRIGGER trg_int_report_file_intent_immutable BEFORE UPDATE OR DELETE
 ON reporting.int_report_file_intent FOR EACH ROW EXECUTE FUNCTION crm.int_reject_fact_mutation();
CREATE TRIGGER trg_int_report_file_intent_no_truncate BEFORE TRUNCATE
 ON reporting.int_report_file_intent FOR EACH STATEMENT EXECUTE FUNCTION crm.int_reject_fact_mutation();
REVOKE ALL ON reporting.int_report_file_intent FROM PUBLIC;
--rollback DO $$ BEGIN RAISE EXCEPTION 'INT_REPORT_FILE_INTENT_FORWARD_ONLY'; END; $$;

--changeset rm:0236-int-report-v1-terminal-fence dbms:postgresql splitStatements:false
-- A terminal report cannot acquire a new upload token. The immutable file
-- pointer is retained until a separately admitted REPORT retention lifecycle.
-- This is the post-lock safety condition for out-of-transaction orphan GC.
CREATE FUNCTION crm.int_report_file_terminal_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM reporting.int_report_file_intent i WHERE i.tenant_id=OLD.tenant_id AND i.file_id=OLD.id) THEN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'REPORT_FILE_RETENTION_NOT_ADMITTED'; END IF;
   IF (NEW.tenant_id,NEW.id,NEW.uploaded_by,NEW.request_key,NEW.content_class,NEW.mime_type,NEW.size_bytes,NEW.sha256)
      IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.uploaded_by,OLD.request_key,OLD.content_class,OLD.mime_type,OLD.size_bytes,OLD.sha256)
      OR (OLD.state IN ('AVAILABLE','REJECTED','DELETING','DELETED') AND
         (NEW.state='STAGED' OR NEW.processing_token IS NOT NULL))
   THEN RAISE EXCEPTION 'REPORT_FILE_TERMINAL_OR_IDENTITY'; END IF;
   IF OLD.state='REJECTED' AND NEW.state<>'REJECTED' THEN RAISE EXCEPTION 'REPORT_FILE_TERMINAL_OR_IDENTITY'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_report_file_terminal_guard BEFORE UPDATE OR DELETE ON crm.file_object
 FOR EACH ROW EXECUTE FUNCTION crm.int_report_file_terminal_guard();
CREATE FUNCTION crm.int_report_v1_pointer_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF OLD.encrypted_format_version=1 AND EXISTS(
    SELECT 1 FROM reporting.int_report_file_intent i WHERE i.tenant_id=OLD.tenant_id AND i.file_id=OLD.file_id)
 THEN RAISE EXCEPTION 'REPORT_FILE_POINTER_IMMUTABLE'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_int_report_v1_pointer_guard BEFORE UPDATE OR DELETE ON crm.file_storage
 FOR EACH ROW EXECUTE FUNCTION crm.int_report_v1_pointer_guard();
REVOKE ALL ON FUNCTION crm.int_report_file_terminal_guard(),crm.int_report_v1_pointer_guard() FROM PUBLIC;
--rollback DO $$ BEGIN RAISE EXCEPTION 'REPORT_FILE_TERMINAL_FENCE_FORWARD_ONLY'; END; $$;
