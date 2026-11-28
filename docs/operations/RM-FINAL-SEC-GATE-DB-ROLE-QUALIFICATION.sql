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
      AND EXISTS (SELECT 1 FROM crm.int_native_booked_capture c
                  WHERE c.tenant_id=o.tenant_id AND c.started_at IS NULL)
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

-- Two tenants share the consumer code but not their generation.
DO $$
DECLARE t UUID := current_setting('rm.gate_tenant')::uuid;
        other_t UUID; actor UUID; v BIGINT; gen UUID; tenant RECORD;
BEGIN
 SELECT id INTO STRICT other_t FROM public.iam_workspace WHERE id<>t ORDER BY id LIMIT 1;
 SELECT id INTO STRICT actor FROM public.iam_user_account ORDER BY id LIMIT 1;
 FOR tenant IN SELECT unnest(ARRAY[t,other_t]) AS id LOOP
   SELECT coalesce(max(version),0)+1 INTO v FROM crm.int_calendar_policy WHERE tenant_id=tenant.id;
   INSERT INTO crm.int_calendar_policy(tenant_id,version,zone_id,effective_from,created_by,created_at)
   VALUES(tenant.id,v,'UTC',CURRENT_TIMESTAMP,actor,CURRENT_TIMESTAMP);
   INSERT INTO crm.int_fact_consumer(tenant_id,consumer_code) VALUES(tenant.id,'projection-role-probe');
   gen:=gen_random_uuid();
   INSERT INTO crm.int_projection_generation(tenant_id,generation_id,projection_code,consumer_code,
       method_version,calendar_version,from_day,until_day)
   VALUES(tenant.id,gen,'L1','projection-role-probe',1,v,CURRENT_DATE,CURRENT_DATE+1);
   IF tenant.id=t THEN PERFORM set_config('rm.gate_projection',gen::text,true);
   ELSE PERFORM set_config('rm.gate_other_tenant',other_t::text,true);
        PERFORM set_config('rm.gate_other_projection',gen::text,true);
   END IF;
 END LOOP;
END
$$;

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

    IF has_table_privilege(current_user,'crm.int_projection_generation','SELECT')
       OR has_table_privilege(current_user,'crm.int_projection_generation','INSERT')
       OR has_any_column_privilege(current_user,'crm.int_projection_generation','UPDATE')
       OR has_table_privilege(current_user,'crm.int_projection_generation','DELETE')
       OR has_table_privilege(current_user,'crm.int_projection_revision','INSERT')
       OR has_any_column_privilege(current_user,'crm.int_projection_revision','UPDATE')
       OR has_table_privilege(current_user,'crm.int_projection_revision','DELETE')
       OR has_table_privilege(current_user,'crm.int_projection_revision','TRUNCATE')
       OR has_function_privilege(current_user,'crm.int_bump_projection_receipt_revision()','EXECUTE')
       OR has_function_privilege(current_user,'crm.int_bump_projection_revision()','EXECUTE')
    THEN RAISE EXCEPTION 'INT_PROJECTION_WORKER_EXCESSIVE_PRIVILEGES'; END IF;
    BEGIN
      INSERT INTO crm.int_projection_revision(tenant_id,generation_id,revision)
      VALUES(t,current_setting('rm.gate_projection')::uuid,99);
      RAISE EXCEPTION 'INT_PROJECTION_DIRECT_REVISION_ACCEPTED';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
      INSERT INTO crm.int_fact_consumer_receipt(tenant_id,consumer_code,owner_module,source_kind,source_id,digest,schema_version,disposition) VALUES(t,'projection-role-probe','SALES','ORDER_PLACED',e,repeat('a',64),2,'APPLIED');
      RAISE EXCEPTION 'INT_PROJECTION_ROLLBACK_PROBE';
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM<>'INT_PROJECTION_ROLLBACK_PROBE' THEN RAISE; END IF;
    END;
    INSERT INTO crm.int_fact_consumer_receipt(tenant_id,consumer_code,owner_module,source_kind,source_id,digest,schema_version,disposition) VALUES(t,'projection-role-probe','SALES','ORDER_PLACED',e,repeat('a',64),2,'APPLIED');
    INSERT INTO crm.int_fact_consumer_receipt(tenant_id,consumer_code,owner_module,source_kind,source_id,digest,schema_version,disposition) VALUES(t,'projection-role-probe','SALES','ORDER_PLACED',e,repeat('a',64),2,'APPLIED') ON CONFLICT DO NOTHING;

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

DO $$
BEGIN
 IF (SELECT revision FROM crm.int_projection_revision
     WHERE tenant_id=current_setting('rm.gate_tenant')::uuid
       AND generation_id=current_setting('rm.gate_projection')::uuid) IS DISTINCT FROM 1::bigint
 THEN RAISE EXCEPTION 'INT_PROJECTION_RECEIPT_REVISION_COUNT'; END IF;
 IF EXISTS(SELECT 1 FROM crm.int_projection_revision
     WHERE tenant_id=current_setting('rm.gate_other_tenant')::uuid
       AND generation_id=current_setting('rm.gate_other_projection')::uuid)
 THEN RAISE EXCEPTION 'INT_PROJECTION_CROSS_TENANT_REVISION'; END IF;
END
$$;

-- B4.4 storage/dirty revision probe, fixture owner only; no runtime grants.
DO $$
DECLARE t UUID:=current_setting('rm.gate_tenant')::uuid;
 g UUID:=current_setting('rm.gate_projection')::uuid;
 o UUID:=current_setting('rm.gate_order')::uuid;
 l crm.sales_order_line%ROWTYPE;
 day_key DATE:=CURRENT_DATE;
 other_revisions_before jsonb;
 other_revisions_after jsonb;
BEGIN
 -- Other tenants may already have qualified generations on the populated fixture.
 -- Compare their complete revision rows before and after this probe, not their existence.
 SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.tenant_id,r.generation_id),'[]'::jsonb)
 INTO other_revisions_before FROM crm.int_dim_revision r WHERE r.tenant_id<>t;
 SELECT * INTO STRICT l FROM crm.sales_order_line WHERE tenant_id=t AND order_id=o ORDER BY id LIMIT 1;
 INSERT INTO crm.int_dim_revision(tenant_id,generation_id) VALUES(t,g);
 INSERT INTO crm.int_l2_contribution(tenant_id,generation_id,stream_code,source_id,line_id,order_id,local_day,currency,amount,sku_id)
 VALUES(t,g,'BOOKED',l.id,l.id,o,day_key,l.currency,l.quantity*l.unit_price_snapshot,l.catalog_item_id_snapshot);
 INSERT INTO crm.int_l2_contribution(tenant_id,generation_id,stream_code,source_id,line_id,order_id,local_day,currency,amount,sku_id)
 VALUES(t,g,'BOOKED',l.id,l.id,o,day_key,l.currency,l.quantity*l.unit_price_snapshot,l.catalog_item_id_snapshot) ON CONFLICT DO NOTHING;
 IF (SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=t AND generation_id=g)<>1
 THEN RAISE EXCEPTION 'INT_DIM_REPLAY_REVISION'; END IF;
 BEGIN
   DELETE FROM crm.int_l2_contribution WHERE tenant_id=t AND generation_id=g;
   RAISE EXCEPTION 'INT_DIM_ROLLBACK_PROBE';
 EXCEPTION WHEN raise_exception THEN
   IF SQLERRM<>'INT_DIM_ROLLBACK_PROBE' THEN RAISE; END IF;
 END;
 IF (SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=t AND generation_id=g)<>1
 THEN RAISE EXCEPTION 'INT_DIM_ROLLBACK_REVISION'; END IF;
 BEGIN
   UPDATE crm.int_l2_contribution SET amount=amount+1 WHERE tenant_id=t AND generation_id=g;
   RAISE EXCEPTION 'INT_L2_FORGED_AMOUNT_ACCEPTED';
 EXCEPTION WHEN check_violation THEN
   IF SQLERRM<>'INT_L2_SOURCE_MISMATCH' THEN RAISE; END IF;
 END;
 INSERT INTO crm.int_l3_period VALUES(t,g,'D',day_key,day_key+1,1);
 INSERT INTO crm.int_l3_cell(tenant_id,generation_id,period_code,from_day,cell_id,stream_code,currency,amount,qty_in,qty_out,distinct_orders)
 VALUES(t,g,'D',day_key,gen_random_uuid(),'BOOKED',l.currency,l.quantity*l.unit_price_snapshot,0,0,1);
 IF EXISTS(SELECT 1 FROM crm.int_l3_period WHERE tenant_id=t AND generation_id=g AND refreshed_revision IS NOT NULL)
 THEN RAISE EXCEPTION 'INT_L3_CELL_STAMP_NOT_INVALIDATED'; END IF;
 UPDATE crm.int_l3_period SET refreshed_revision=1 WHERE tenant_id=t AND generation_id=g;
 UPDATE crm.int_l2_contribution SET amount=amount WHERE tenant_id=t AND generation_id=g;
 BEGIN
   UPDATE crm.int_l3_period SET refreshed_revision=1 WHERE tenant_id=t AND generation_id=g;
   RAISE EXCEPTION 'INT_DIM_STALE_REFRESH_ACCEPTED';
 EXCEPTION WHEN check_violation THEN
   IF SQLERRM<>'INT_DIM_STALE_REFRESH' THEN RAISE; END IF;
 END;
 IF (SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=t AND generation_id=g)<>2
 THEN RAISE EXCEPTION 'INT_DIM_DIRTY_REVISION'; END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.tenant_id,r.generation_id),'[]'::jsonb)
 INTO other_revisions_after FROM crm.int_dim_revision r WHERE r.tenant_id<>t;
 IF other_revisions_after IS DISTINCT FROM other_revisions_before
 THEN RAISE EXCEPTION 'INT_DIM_CROSS_TENANT_REVISION'; END IF;
END
$$;
-- B4.5C.7: synthetic untrusted runtime/worker roles cannot forge proof or revision rows.
DO $dim_gate$
DECLARE role_name text;
BEGIN
 FOREACH role_name IN ARRAY ARRAY['rm_runtime_gate','rm_int_worker_gate'] LOOP
   IF has_table_privilege(role_name,'crm.int_dim_coverage_proof','INSERT,UPDATE,DELETE,TRUNCATE')
      OR has_any_column_privilege(role_name,'crm.int_dim_coverage_proof','INSERT,UPDATE')
      OR has_table_privilege(role_name,'crm.int_dim_revision','INSERT,UPDATE,DELETE,TRUNCATE')
      OR has_any_column_privilege(role_name,'crm.int_dim_revision','INSERT,UPDATE')
      OR has_function_privilege(role_name,'crm.int_dim_receipt_revision()','EXECUTE')
      OR has_function_privilege(role_name,'crm.int_dim_materialization_revision()','EXECUTE')
      OR has_function_privilege(role_name,'crm.int_dim_reservation_revision()','EXECUTE')
   THEN RAISE EXCEPTION 'INT_DIM_UNTRUSTED_ROLE_PRIVILEGES: %',role_name; END IF;
 END LOOP;
END $dim_gate$;
SET LOCAL ROLE rm_int_worker_gate;
DO $dim_gate$
BEGIN
 BEGIN
   INSERT INTO crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id,observed_at,
       source_revision,dirty_revision,receipt_revision,materialization_revision,lifecycle_revision,
       delivery_mismatches,contribution_mismatches,period_mismatches,cell_mismatches,membership_mismatches)
   VALUES(current_setting('rm.gate_tenant')::uuid,current_setting('rm.gate_projection')::uuid,
       gen_random_uuid(),CURRENT_TIMESTAMP,0,0,0,0,0,0,0,0,0,0);
   RAISE EXCEPTION 'INT_DIM_FORGED_PROOF_ACCEPTED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
   UPDATE crm.int_dim_revision SET materialization_revision=0
   WHERE tenant_id=current_setting('rm.gate_tenant')::uuid AND generation_id=current_setting('rm.gate_projection')::uuid;
   RAISE EXCEPTION 'INT_DIM_COUNTER_RESET_ACCEPTED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $dim_gate$;
RESET ROLE;

-- B4.5C.7B: separate synthetic proof writer from generic runtime roles.
-- Writer is trusted for persistence, not for source changes or activation.
CREATE ROLE rm_dim_proof_gate NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT USAGE ON SCHEMA crm TO rm_dim_proof_gate;
GRANT SELECT ON crm.int_projection_generation,crm.int_calendar_policy,crm.int_projection_lifecycle,
 crm.int_l2_consumer,crm.int_dim_revision,crm.int_source_revision,crm.int_source_revision_event,
 crm.int_canonical_fact,crm.int_fact_consumer_receipt,crm.sales_order_status_event,crm.sales_order,
 crm.sales_order_line,crm.inv_stock_movement,crm.inv_stock_reservation,crm.int_sales_line_dimension,
 crm.int_movement_dimension,crm.int_l2_contribution,crm.int_l3_period,crm.int_l3_cell,
 crm.int_l3_cell_order TO rm_dim_proof_gate;
GRANT SELECT,INSERT ON crm.int_dim_coverage_proof TO rm_dim_proof_gate;
DO $proof_role$
DECLARE t UUID:=current_setting('rm.gate_tenant')::uuid;
 g UUID:=current_setting('rm.gate_projection')::uuid;
 c text:='l2:'||g::text;
BEGIN
 INSERT INTO crm.int_fact_consumer(tenant_id,consumer_code) VALUES(t,c);
 INSERT INTO crm.int_l2_consumer(tenant_id,generation_id,consumer_code) VALUES(t,g,c);
END $proof_role$;
SET LOCAL ROLE rm_dim_proof_gate;
DO $proof_role$
DECLARE t UUID:=current_setting('rm.gate_tenant')::uuid;
 g UUID:=current_setting('rm.gate_projection')::uuid;
 p UUID:=gen_random_uuid();
BEGIN
 IF has_any_column_privilege(current_user,'crm.int_dim_revision','UPDATE,INSERT')
    OR has_table_privilege(current_user,'crm.int_dim_revision','DELETE,TRUNCATE')
    OR has_any_column_privilege(current_user,'crm.int_canonical_fact','UPDATE,INSERT')
    OR has_any_column_privilege(current_user,'crm.int_fact_consumer_receipt','UPDATE,INSERT')
    OR has_any_column_privilege(current_user,'crm.int_l2_contribution','UPDATE,INSERT')
    OR has_any_column_privilege(current_user,'crm.int_l3_period','UPDATE,INSERT')
    OR has_any_column_privilege(current_user,'crm.int_active_generation','UPDATE,INSERT')
    OR has_any_column_privilege(current_user,'crm.int_dim_coverage_proof','UPDATE')
    OR has_table_privilege(current_user,'crm.int_dim_coverage_proof','DELETE,TRUNCATE')
 THEN RAISE EXCEPTION 'INT_DIM_PROOF_ROLE_EXCESSIVE_PRIVILEGES'; END IF;
 INSERT INTO crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id,observed_at,
   source_revision,dirty_revision,receipt_revision,materialization_revision,lifecycle_revision,
   delivery_mismatches,contribution_mismatches,period_mismatches,cell_mismatches,membership_mismatches)
 SELECT t,g,p,CURRENT_TIMESTAMP,0,dirty_revision,receipt_revision,materialization_revision,0,1,1,1,1,1
 FROM crm.int_dim_revision WHERE tenant_id=t AND generation_id=g;
 IF NOT EXISTS(SELECT 1 FROM crm.int_dim_coverage_proof WHERE tenant_id=t AND generation_id=g AND proof_id=p)
 THEN RAISE EXCEPTION 'INT_DIM_PROOF_WRITER_INSERT_FAILED'; END IF;
 BEGIN
   UPDATE crm.int_dim_revision SET dirty_revision=0 WHERE tenant_id=t AND generation_id=g;
   RAISE EXCEPTION 'INT_DIM_PROOF_WRITER_REVISION_MUTATION';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
   UPDATE crm.int_dim_coverage_proof SET delivery_mismatches=0 WHERE tenant_id=t AND generation_id=g AND proof_id=p;
   RAISE EXCEPTION 'INT_DIM_PROOF_WRITER_PROOF_MUTATION';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $proof_role$;
RESET ROLE;

-- Synthetic ordinary runtime must not write feature evidence.
SET LOCAL ROLE rm_runtime_gate;
DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['crm.int_fact_availability_observation','crm.int_feature_candidate'] LOOP
  IF has_table_privilege(current_user,t,'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER')
     OR has_any_column_privilege(current_user,t,'INSERT,UPDATE')
  THEN RAISE EXCEPTION 'FEATURE_RUNTIME_EXCESSIVE_PRIVILEGE: %',t; END IF;
 END LOOP;
 BEGIN
  INSERT INTO crm.int_feature_candidate DEFAULT VALUES;
  RAISE EXCEPTION 'FEATURE_RUNTIME_INSERT_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  INSERT INTO crm.int_fact_availability_observation DEFAULT VALUES;
  RAISE EXCEPTION 'OBSERVATION_RUNTIME_INSERT_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $$;
RESET ROLE;

-- B4 shadow feature roles: synthetic privilege proof only; not deployment.
CREATE ROLE rm_observer_gate NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE rm_feature_store_gate NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA crm TO rm_observer_gate,rm_feature_store_gate;
GRANT SELECT ON crm.int_canonical_fact TO rm_observer_gate;
GRANT SELECT,INSERT ON crm.int_fact_availability_observation TO rm_observer_gate;
GRANT SELECT,INSERT ON crm.int_feature_candidate TO rm_feature_store_gate;
GRANT SELECT ON crm.int_dim_coverage_proof TO rm_feature_store_gate;

SET LOCAL ROLE rm_observer_gate;
DO $$
DECLARE t uuid:=current_setting('rm.gate_tenant')::uuid;
BEGIN
 IF has_any_column_privilege(current_user,'crm.int_feature_candidate','INSERT,UPDATE')
 OR has_any_column_privilege(current_user,'crm.int_canonical_fact','INSERT,UPDATE')
 OR has_any_column_privilege(current_user,'crm.int_fact_availability_observation','UPDATE')
 OR has_table_privilege(current_user,'crm.int_fact_availability_observation','DELETE,TRUNCATE,TRIGGER')
 THEN RAISE EXCEPTION 'OBSERVER_ROLE_EXCESSIVE_PRIVILEGE'; END IF;
 INSERT INTO crm.int_fact_availability_observation(tenant_id,owner_module,source_kind,source_id)
 SELECT tenant_id,owner_module,source_kind,source_id FROM crm.int_canonical_fact WHERE tenant_id=t
 ON CONFLICT(tenant_id,owner_module,source_kind,source_id) DO NOTHING;
 IF NOT EXISTS(SELECT 1 FROM crm.int_fact_availability_observation WHERE tenant_id=t)
 THEN RAISE EXCEPTION 'OBSERVER_ROLE_NO_OBSERVATION'; END IF;
 BEGIN
   INSERT INTO crm.int_feature_candidate DEFAULT VALUES;
   RAISE EXCEPTION 'OBSERVER_FEATURE_INSERT_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $$;
RESET ROLE;

-- Prepare synthetic zero-vector proof as fixture owner, not a real parity assertion.
DO $$
DECLARE t uuid:=current_setting('rm.gate_tenant')::uuid;
 g uuid:=current_setting('rm.gate_projection')::uuid;
 p uuid:=gen_random_uuid();
BEGIN
 INSERT INTO crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id,observed_at,
 source_revision,dirty_revision,receipt_revision,materialization_revision,lifecycle_revision,
 delivery_mismatches,contribution_mismatches,period_mismatches,cell_mismatches,membership_mismatches)
 VALUES(t,g,p,CURRENT_TIMESTAMP,0,0,0,0,0,0,0,0,0,0);
 PERFORM set_config('rm.gate_feature_proof',p::text,true);
END $$;
SET LOCAL ROLE rm_feature_store_gate;
DO $$
DECLARE t uuid:=current_setting('rm.gate_tenant')::uuid;
 g uuid:=current_setting('rm.gate_projection')::uuid;
 p uuid:=current_setting('rm.gate_feature_proof')::uuid;
 f uuid:=gen_random_uuid(); k uuid:=gen_random_uuid();
 at_time timestamptz:=clock_timestamp();
 body jsonb;
BEGIN
 IF has_any_column_privilege(current_user,'crm.int_canonical_fact','INSERT,UPDATE')
 OR has_any_column_privilege(current_user,'crm.int_fact_availability_observation','INSERT,UPDATE')
 OR has_any_column_privilege(current_user,'crm.int_dim_coverage_proof','INSERT,UPDATE')
 OR has_any_column_privilege(current_user,'crm.int_feature_candidate','UPDATE')
 OR has_table_privilege(current_user,'crm.int_feature_candidate','DELETE,TRUNCATE,TRIGGER')
 THEN RAISE EXCEPTION 'FEATURE_ROLE_EXCESSIVE_PRIVILEGE'; END IF;
 body:=jsonb_build_object('request',jsonb_build_object('generation',g,'proof',p,
 'contractVersion',1,'methodVersion',1,'numericPolicyVersion',1,'activityPolicyVersion',1),
 'sourceRevision',0,'dirtyRevision',0,'receiptRevision',0,'materializationRevision',0,'lifecycleRevision',0,
 'activity','INSUFFICIENT_HISTORY','decisionEligibility','BLOCKED','inputs',jsonb_build_array(),
 'observedAt',at_time);
 INSERT INTO crm.int_feature_candidate(tenant_id,generation_id,feature_id,contract_version,numeric_policy_version,
 method_version,observed_at,payload,availability,proof_id,source_revision,dirty_revision,receipt_revision,
 materialization_revision,lifecycle_revision,activity_policy_version,activity,payload_sha256,request_key,actor_id)
 VALUES(t,g,f,1,1,1,at_time,body,'CURRENT_SNAPSHOT_ONLY',p,0,0,0,0,0,1,'INSUFFICIENT_HISTORY',
 encode(sha256(convert_to(body::text,'UTF8')),'hex'),k,gen_random_uuid());
 IF NOT EXISTS(SELECT 1 FROM crm.int_feature_candidate WHERE tenant_id=t AND feature_id=f)
 THEN RAISE EXCEPTION 'FEATURE_ROLE_INSERT_FAILED'; END IF;
 BEGIN
   UPDATE crm.int_feature_candidate SET activity='STALE' WHERE tenant_id=t AND feature_id=f;
   RAISE EXCEPTION 'FEATURE_ROLE_MUTATION_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
   INSERT INTO crm.int_fact_availability_observation DEFAULT VALUES;
   RAISE EXCEPTION 'FEATURE_ROLE_OBSERVATION_INSERT_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $$;
RESET ROLE;

-- B4.6: no existing runtime, worker, proof, observer or feature role may change activation.
DO $$
DECLARE role_name text; relation_name text;
BEGIN
 FOREACH role_name IN ARRAY ARRAY['rm_runtime_gate','rm_int_worker_gate','rm_dim_proof_gate','rm_observer_gate','rm_feature_store_gate'] LOOP
  FOREACH relation_name IN ARRAY ARRAY['crm.int_active_generation','crm.int_projection_lifecycle'] LOOP
   IF has_table_privilege(role_name,relation_name,'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER')
    OR has_any_column_privilege(role_name,relation_name,'INSERT,UPDATE')
   THEN RAISE EXCEPTION 'ACTIVATION_ROLE_EXCESSIVE_PRIVILEGE: % %',role_name,relation_name; END IF;
  END LOOP;
 END LOOP;
END $$;
SET LOCAL ROLE rm_runtime_gate;
DO $$
BEGIN
 BEGIN
  UPDATE crm.int_projection_lifecycle SET lifecycle_state='ACTIVE',revision=1;
  RAISE EXCEPTION 'RUNTIME_ACTIVATION_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  DELETE FROM crm.int_active_generation;
  RAISE EXCEPTION 'RUNTIME_POINTER_DELETE_ALLOWED';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
END $$;
RESET ROLE;

-- Native capture: synthetic role separation, not production identity provisioning.
CREATE ROLE rm_native_capture_gate NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA crm TO rm_native_capture_gate;
GRANT SELECT ON crm.int_native_booked_capture TO rm_native_capture_gate;
GRANT UPDATE(activated_by) ON crm.int_native_booked_capture TO rm_native_capture_gate;
SET LOCAL ROLE rm_runtime_gate;
DO $native_role$
BEGIN
 IF has_any_column_privilege(current_user,'crm.int_native_booked_capture','INSERT,UPDATE')
 OR has_table_privilege(current_user,'crm.int_native_booked_capture','DELETE,TRUNCATE,TRIGGER')
 OR has_function_privilege(current_user,'crm.int_native_capture_transition()','EXECUTE')
 THEN RAISE EXCEPTION 'NATIVE_CAPTURE_RUNTIME_EXCESSIVE_PRIVILEGE'; END IF;
END $native_role$;
RESET ROLE;
SET LOCAL ROLE rm_native_capture_gate;
DO $native_role$
BEGIN
 IF NOT has_column_privilege(current_user,'crm.int_native_booked_capture','activated_by','UPDATE')
 OR has_column_privilege(current_user,'crm.int_native_booked_capture','started_at','UPDATE')
 OR has_column_privilege(current_user,'crm.int_native_booked_capture','tenant_id','UPDATE')
 OR has_column_privilege(current_user,'crm.int_native_booked_capture','policy_version','UPDATE')
 OR has_table_privilege(current_user,'crm.int_native_booked_capture','INSERT,DELETE,TRUNCATE,TRIGGER')
 OR has_any_column_privilege(current_user,'crm.sales_order','INSERT,UPDATE')
 OR has_any_column_privilege(current_user,'crm.int_canonical_fact','INSERT,UPDATE')
 THEN RAISE EXCEPTION 'NATIVE_CAPTURE_ACTIVATION_ROLE_EXCESSIVE_PRIVILEGE'; END IF;
END $native_role$;
RESET ROLE;
ROLLBACK;
