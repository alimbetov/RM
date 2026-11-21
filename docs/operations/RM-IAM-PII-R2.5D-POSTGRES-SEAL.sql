-- RM IAM-PII-R2.5D: manual, PostgreSQL-only, post-migration plaintext seal.
-- DO NOT automatically execute at startup or through Liquibase.
-- Prerequisite: stop every application/legacy writer; take verified encrypted
-- backup; run IamPiiMigrationQualificationService.assertReadyForPlaintextSeal()
-- with the intended production key set against this exact restored database;
-- obtain independent approval and preserve the operator evidence.
-- This script is a one-way POLICY gate (not physical column removal).
-- Any attempted new plaintext INSERT/UPDATE is rejected by PostgreSQL.
--
-- Execute as a constrained migration operator, in a maintenance window.
-- Never use a database containing live PII as a CI fixture.

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE public.iam_user_account IN ACCESS EXCLUSIVE MODE;
LOCK TABLE iam_pii.user_profile_secret IN SHARE MODE;
LOCK TABLE public.iam_email_identity IN SHARE MODE;

DO $$
DECLARE remaining BIGINT;
BEGIN
  SELECT count(*) INTO remaining FROM public.iam_user_account
  WHERE display_name IS NOT NULL OR phone_e164 IS NOT NULL
     OR email_normalized IS NOT NULL;
  IF remaining <> 0 THEN
    RAISE EXCEPTION 'IAM PII plaintext seal refused: residual legacy rows';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema='iam_pii' AND table_name='user_profile_secret'
  ) THEN
    RAISE EXCEPTION 'IAM PII plaintext seal refused: encrypted profile table missing';
  END IF;
  -- Do not expose email/name/phone values in SQL diagnostics.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname='ck_iam_account_legacy_pii_null'
      AND conrelid='public.iam_user_account'::regclass
  ) THEN
    EXECUTE 'ALTER TABLE public.iam_user_account
      ADD CONSTRAINT ck_iam_account_legacy_pii_null
      CHECK (display_name IS NULL AND phone_e164 IS NULL
             AND email_normalized IS NULL) NOT VALID';
  END IF;
END $$;

ALTER TABLE public.iam_user_account
  VALIDATE CONSTRAINT ck_iam_account_legacy_pii_null;

REVOKE ALL ON SCHEMA iam_pii FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA iam_pii FROM PUBLIC;

COMMIT;

-- Operator verification: must return 0. Do not query or print actual PII.
SELECT count(*) AS residual_plaintext_rows
FROM public.iam_user_account
WHERE display_name IS NOT NULL OR phone_e164 IS NOT NULL
   OR email_normalized IS NOT NULL;

-- POST-SEAL: disable RM_IAM_PII_MIGRATION_ENABLED; retain key backups.
-- Physical DROP COLUMN is a separate irreversible, audited migration AFTER
-- every remaining legacy SQL read path is eliminated and requalified.
