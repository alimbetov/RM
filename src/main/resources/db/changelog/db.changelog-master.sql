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
