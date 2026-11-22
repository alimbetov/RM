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


--changeset rm:0105-crm-r22b-lifecycle-dispute
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
