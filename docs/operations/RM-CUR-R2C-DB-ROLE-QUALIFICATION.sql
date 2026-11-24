-- R2C.1B.2A: ephemeral PostgreSQL qualification, NOT production provisioning.
-- Current R2C runtime is deliberately read-only until OWNER admission exists.
BEGIN;
CREATE ROLE rm_fx_runtime_gate NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA crm TO rm_fx_runtime_gate;
GRANT SELECT ON crm.fx_auto_policy_revision, crm.fx_auto_limit_policy_version,
 crm.fx_auto_target_enrollment, crm.fx_auto_approval_evidence,
 crm.fx_auto_policy_activation, crm.fx_auto_simulation, crm.fx_auto_simulation_item,
 crm.fx_auto_simulation_seal TO rm_fx_runtime_gate;
SET LOCAL ROLE rm_fx_runtime_gate;
SELECT count(*) FROM crm.fx_auto_policy_revision;
SELECT count(*) FROM crm.fx_auto_simulation_seal;
DO $$
DECLARE t text;
BEGIN
 IF has_schema_privilege(current_user,'crm','CREATE')
 OR has_schema_privilege(current_user,'public','CREATE')
 OR has_table_privilege(current_user,'public.databasechangelog','UPDATE')
 OR has_table_privilege(current_user,'crm.store_offer','UPDATE')
 THEN RAISE EXCEPTION 'FX_RUNTIME_EXCESSIVE_PRIVILEGE'; END IF;
 FOREACH t IN ARRAY ARRAY['policy_revision','limit_policy_version','target_enrollment',
 'approval_evidence','policy_activation','simulation','simulation_item','simulation_seal',
 'scope_guard','simulation_guard'] LOOP
  IF has_table_privilege(current_user,'crm.fx_auto_'||t,'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER')
  THEN RAISE EXCEPTION 'FX_RUNTIME_EXCESSIVE_TABLE_PRIVILEGE: %',t; END IF;
 END LOOP;
 BEGIN
  UPDATE crm.fx_auto_scope_guard SET sealed=false;
  RAISE EXCEPTION 'FX_RUNTIME_GUARD_WRITE_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  ALTER TABLE crm.fx_auto_scope_guard DISABLE TRIGGER USER;
  RAISE EXCEPTION 'FX_RUNTIME_DDL_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  TRUNCATE crm.fx_auto_simulation_item;
  RAISE EXCEPTION 'FX_RUNTIME_TRUNCATE_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $$;
RESET ROLE;
ROLLBACK;
