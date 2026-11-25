-- FINAL-SEC-GATE synthetic runtime least-privilege qualification.
-- Runs only in ephemeral PostgreSQL CI, after migrations. All role and grant changes roll back.
-- This is a proof of deny/allow privilege semantics, NOT deployment of an actual runtime identity.
BEGIN;
CREATE ROLE rm_runtime_gate NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT USAGE ON SCHEMA public, crm TO rm_runtime_gate;
GRANT SELECT ON public.iam_user_account, public.iam_workspace,
    public.iam_org_membership TO rm_runtime_gate;
GRANT SELECT, UPDATE ON crm.inv_stock_balance TO rm_runtime_gate;
-- Narrow additional grants for the INT source-admission and owner status-event boundary.
GRANT SELECT ON crm.sales_order, crm.sales_order_line, crm.inv_stock_movement TO rm_runtime_gate;
GRANT SELECT, INSERT ON crm.sales_order_status_event, crm.int_canonical_fact TO rm_runtime_gate;
GRANT UPDATE (booked_snapshot_sealed) ON crm.sales_order TO rm_runtime_gate;

-- Prepare a real tenant-owned parent and merchandise snapshot as the fixture owner.
-- The entire probe, including copied rows and grants, rolls back at the end.
DO $
DECLARE source_order crm.sales_order%ROWTYPE;
DECLARE probe_order UUID := gen_random_uuid();
DECLARE probe_event UUID := gen_random_uuid();
BEGIN
    SELECT o.* INTO STRICT source_order FROM crm.sales_order o
    WHERE EXISTS (SELECT 1 FROM crm.sales_order_line l
                  WHERE l.tenant_id=o.tenant_id AND l.order_id=o.id)
    ORDER BY o.tenant_id,o.id LIMIT 1;
    INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,
        total_amount,currency,created_at,fulfillment_mode)
    VALUES(source_order.tenant_id,probe_order,source_order.pending_email_identity_id,
        source_order.buyer_user_id,'PLACED',source_order.total_amount,source_order.currency,
        CURRENT_TIMESTAMP,source_order.fulfillment_mode);
    INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,
        unit_price_snapshot,currency,catalog_item_id_snapshot)
    SELECT tenant_id,gen_random_uuid(),probe_order,offer_id,title_snapshot,quantity,
        unit_price_snapshot,currency,catalog_item_id_snapshot FROM crm.sales_order_line
    WHERE tenant_id=source_order.tenant_id AND order_id=source_order.id;
    PERFORM set_config('rm.gate_tenant',source_order.tenant_id::text,true);
    PERFORM set_config('rm.gate_order',probe_order::text,true);
    PERFORM set_config('rm.gate_event',probe_event::text,true);
END
$;

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
DO $
DECLARE t UUID := current_setting('rm.gate_tenant')::uuid;
DECLARE o UUID := current_setting('rm.gate_order')::uuid;
DECLARE e UUID := current_setting('rm.gate_event')::uuid;
BEGIN
    IF has_table_privilege(current_user,'crm.int_canonical_fact','UPDATE')
       OR has_table_privilege(current_user,'crm.int_canonical_fact','DELETE')
       OR has_table_privilege(current_user,'crm.int_canonical_fact','TRUNCATE')
       OR has_table_privilege(current_user,'crm.sales_order_status_event','UPDATE')
       OR has_table_privilege(current_user,'crm.sales_order_status_event','DELETE')
       OR has_table_privilege(current_user,'crm.sales_order_status_event','TRUNCATE')
       OR has_column_privilege(current_user,'crm.sales_order','total_amount','UPDATE')
       OR has_column_privilege(current_user,'crm.sales_order','currency','UPDATE')
    THEN RAISE EXCEPTION 'INT_RUNTIME_EXCESSIVE_PRIVILEGES'; END IF;
    INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at)
    VALUES(t,e,o,'PLACED',CURRENT_TIMESTAMP);
    IF NOT EXISTS (SELECT 1 FROM crm.sales_order
                   WHERE tenant_id=t AND id=o AND booked_snapshot_sealed)
    THEN RAISE EXCEPTION 'INT_RUNTIME_SEAL_FAILED'; END IF;
    -- Synthetic digest: this is a SQL privilege probe, not an appender digest oracle.
    INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,
        occurred_at,schema_version,digest)
    VALUES(t,'SALES','ORDER_PLACED',e,o,CURRENT_TIMESTAMP,2,repeat('a',64));
    IF NOT EXISTS (SELECT 1 FROM crm.int_canonical_fact WHERE tenant_id=t AND source_id=e)
    THEN RAISE EXCEPTION 'INT_RUNTIME_APPEND_FAILED'; END IF;
    BEGIN
        UPDATE crm.sales_order SET booked_snapshot_sealed=FALSE WHERE tenant_id=t AND id=o;
        RAISE EXCEPTION 'INT_RUNTIME_UNSEAL_ACCEPTED';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM <> 'SALES_BOOKED_SNAPSHOT_IMMUTABLE' THEN RAISE; END IF;
    END;
    BEGIN
        ALTER TABLE crm.int_canonical_fact DISABLE TRIGGER trg_int_fact_immutable;
        RAISE EXCEPTION 'INT_RUNTIME_TRIGGER_DISABLE_ACCEPTED';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
END
$;
RESET ROLE;
ROLLBACK;
