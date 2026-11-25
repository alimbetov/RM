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
DO $$
DECLARE
    source_order crm.sales_order%ROWTYPE;
    probe_order UUID := gen_random_uuid();
    probe_event UUID := gen_random_uuid();
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
    PERFORM set_config('rm.gate_revision_before',(SELECT count(*) FROM crm.int_source_revision_event
        WHERE tenant_id=source_order.tenant_id)::text,true);
END
$$;

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
DO $$
DECLARE
    t UUID := current_setting('rm.gate_tenant')::uuid;
    o UUID := current_setting('rm.gate_order')::uuid;
    e UUID := current_setting('rm.gate_event')::uuid;
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
    IF has_table_privilege(current_user,'crm.int_source_revision_event','INSERT')
       OR has_any_column_privilege(current_user,'crm.int_source_revision_event','UPDATE')
       OR has_table_privilege(current_user,'crm.int_source_revision_event','DELETE')
       OR has_table_privilege(current_user,'crm.int_source_revision_event','TRUNCATE')
       OR has_function_privilege(current_user,'crm.int_bump_source_revision()','EXECUTE')
    THEN RAISE EXCEPTION 'INT_RUNTIME_REVISION_PRIVILEGES'; END IF;
    BEGIN
        INSERT INTO crm.int_source_revision_event(tenant_id) VALUES(t);
        RAISE EXCEPTION 'INT_RUNTIME_DIRECT_REVISION_ACCEPTED';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
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
$$;
RESET ROLE;
DO $$
BEGIN
    IF (SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=current_setting('rm.gate_tenant')::uuid)
       <> current_setting('rm.gate_revision_before')::bigint+2
    THEN RAISE EXCEPTION 'INT_RUNTIME_REVISION_EVENT_COUNT'; END IF;
END
$$;

-- P2: separate synthetic delivery identity. Locking a row requires a column
-- UPDATE grant in PostgreSQL; immutable triggers still forbid actual changes.
CREATE ROLE rm_int_worker_gate NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT USAGE ON SCHEMA crm TO rm_int_worker_gate;
GRANT SELECT ON crm.sales_order,crm.sales_order_line,crm.sales_order_status_event,crm.inv_stock_movement TO rm_int_worker_gate;
GRANT SELECT,INSERT ON crm.int_canonical_fact,crm.int_fact_consumer,crm.int_fact_consumer_receipt,
    crm.int_fact_backfill_run,crm.int_fact_backfill_receipt TO rm_int_worker_gate;
GRANT UPDATE(consumer_code) ON crm.int_fact_consumer TO rm_int_worker_gate;
GRANT UPDATE(run_id) ON crm.int_fact_backfill_run TO rm_int_worker_gate;
SET LOCAL ROLE rm_int_worker_gate;
DO $$
DECLARE
    t UUID := current_setting('rm.gate_tenant')::uuid;
    e UUID := current_setting('rm.gate_event')::uuid;
    r UUID := gen_random_uuid();
    tab TEXT;
BEGIN
    FOREACH tab IN ARRAY ARRAY['crm.sales_order_status_event','crm.sales_order_line','crm.inv_stock_movement']
    LOOP
        IF has_table_privilege(current_user,tab,'INSERT') OR has_any_column_privilege(current_user,tab,'UPDATE')
           OR has_table_privilege(current_user,tab,'DELETE') OR has_table_privilege(current_user,tab,'TRUNCATE')
        THEN RAISE EXCEPTION 'P2_WORKER_OWNER_WRITE_GRANTED'; END IF;
    END LOOP;
    FOREACH tab IN ARRAY ARRAY['crm.int_canonical_fact','crm.int_fact_consumer_receipt','crm.int_fact_backfill_receipt']
    LOOP
        IF has_any_column_privilege(current_user,tab,'UPDATE') OR has_table_privilege(current_user,tab,'DELETE')
           OR has_table_privilege(current_user,tab,'TRUNCATE')
        THEN RAISE EXCEPTION 'P2_WORKER_EVIDENCE_MUTATION_GRANTED'; END IF;
    END LOOP;
    INSERT INTO crm.int_fact_consumer(tenant_id,consumer_code) VALUES(t,'p2-role-probe');
    PERFORM consumer_code FROM crm.int_fact_consumer WHERE tenant_id=t AND consumer_code='p2-role-probe' FOR UPDATE;
    INSERT INTO crm.int_fact_consumer_receipt(tenant_id,consumer_code,owner_module,source_kind,source_id,digest,schema_version,disposition)
    VALUES(t,'p2-role-probe','SALES','ORDER_PLACED',e,repeat('a',64),2,'APPLIED');
    INSERT INTO crm.int_fact_backfill_run(tenant_id,run_id,owner_module,source_kind,range_start,range_end)
    VALUES(t,r,'SALES','ORDER_PLACED',CURRENT_TIMESTAMP-INTERVAL '1 day',CURRENT_TIMESTAMP+INTERVAL '1 day');
    PERFORM run_id FROM crm.int_fact_backfill_run WHERE tenant_id=t AND run_id=r FOR UPDATE;
    INSERT INTO crm.int_fact_backfill_receipt(tenant_id,run_id,owner_module,source_kind,source_id,disposition,digest,schema_version)
    VALUES(t,r,'SALES','ORDER_PLACED',e,'VERIFIED',repeat('a',64),2);
    BEGIN
        UPDATE crm.int_fact_consumer SET consumer_code=consumer_code WHERE tenant_id=t AND consumer_code='p2-role-probe';
        RAISE EXCEPTION 'P2_CONSUMER_MUTATION_ACCEPTED';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM <> 'CANONICAL_FACT_IMMUTABLE' THEN RAISE; END IF;
    END;
    BEGIN
        UPDATE crm.int_fact_backfill_run SET run_id=run_id WHERE tenant_id=t AND run_id=r;
        RAISE EXCEPTION 'P2_BACKFILL_MUTATION_ACCEPTED';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM <> 'CANONICAL_FACT_IMMUTABLE' THEN RAISE; END IF;
    END;
END
$$;
RESET ROLE;
ROLLBACK;
