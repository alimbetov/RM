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
