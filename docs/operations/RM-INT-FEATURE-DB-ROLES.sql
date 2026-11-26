-- Deployment template, execute as schema owner only after 0220.
-- Supply actual existing LOGIN roles via psql -v observer_login=... -v feature_login=... -v runtime_login=...
-- The application currently uses one datasource; do not apply these grants to that
-- shared login until isolated connection configuration is qualified.
BEGIN;
CREATE ROLE rm_int_observer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE rm_int_feature_store NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA crm TO rm_int_observer,rm_int_feature_store;
GRANT SELECT ON crm.int_canonical_fact TO rm_int_observer;
GRANT SELECT,INSERT ON crm.int_fact_availability_observation TO rm_int_observer;
GRANT SELECT,INSERT ON crm.int_feature_candidate TO rm_int_feature_store;
GRANT SELECT ON crm.int_dim_coverage_proof TO rm_int_feature_store;
REVOKE ALL ON crm.int_fact_availability_observation,crm.int_feature_candidate FROM :"runtime_login";
GRANT rm_int_observer TO :"observer_login";
GRANT rm_int_feature_store TO :"feature_login";
-- Effective privileges must also be checked for inherited roles, owners and superusers.
SELECT has_table_privilege(:'runtime_login','crm.int_fact_availability_observation','INSERT') AS runtime_observation_insert,
       has_table_privilege(:'runtime_login','crm.int_feature_candidate','INSERT') AS runtime_feature_insert;
-- Intentionally rollback template: provisioning requires separately verified
-- distinct login identities and application routing. No credentials here.
ROLLBACK;
