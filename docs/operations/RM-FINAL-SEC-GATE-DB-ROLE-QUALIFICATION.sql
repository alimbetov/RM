-- FINAL-SEC-GATE synthetic runtime least-privilege qualification.
-- Runs only in ephemeral PostgreSQL CI, after migrations. All role and grant changes roll back.
-- This is a proof of deny/allow privilege semantics, NOT deployment of an actual runtime identity.
BEGIN;
CREATE ROLE rm_runtime_gate NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT USAGE ON SCHEMA public, crm TO rm_runtime_gate;
GRANT SELECT ON public.iam_user_account, public.iam_workspace,
    public.iam_org_membership TO rm_runtime_gate;
GRANT SELECT, UPDATE ON crm.inv_stock_balance TO rm_runtime_gate;
SET LOCAL ROLE rm_runtime_gate;
SELECT count(*) FROM public.iam_user_account;
SELECT count(*) FROM public.iam_workspace;
SELECT count(*) FROM public.iam_org_membership;
SELECT count(*) FROM crm.inv_stock_balance;
DO $$
BEGIN
    IF has_schema_privilege(current_user, 'public', 'CREATE')
       OR has_schema_privilege(current_user, 'crm', 'CREATE')
       OR has_schema_privilege(current_user, 'security', 'USAGE')
       OR has_schema_privilege(current_user, 'iam_pii', 'USAGE')
       OR has_table_privilege(current_user, 'public.iam_user_account', 'UPDATE')
       OR has_table_privilege(current_user, 'crm.inv_stock_balance', 'DELETE')
       OR has_table_privilege(current_user, 'public.databasechangelog', 'UPDATE')
    THEN
        RAISE EXCEPTION 'Synthetic runtime role has excessive privileges';
    END IF;
END
$$;
RESET ROLE;
ROLLBACK;
