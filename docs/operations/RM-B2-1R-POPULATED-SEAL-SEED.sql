-- Synthetic migration fixtures. Execute only on isolated pre-0181 CI database.
DO $$
DECLARE
    source_order crm.sales_order%ROWTYPE;
    other_tenant UUID;
    unsealed_order UUID := gen_random_uuid();
    other_order UUID := gen_random_uuid();
    other_offer UUID := gen_random_uuid();
    other_store UUID := gen_random_uuid();
    shared_event UUID;
    event_time TIMESTAMPTZ := CURRENT_TIMESTAMP;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='crm'
        AND table_name='sales_order' AND column_name='booked_snapshot_sealed')
    THEN RAISE EXCEPTION 'PRE_0181_BASELINE_REQUIRED'; END IF;
    SELECT o.* INTO STRICT source_order FROM crm.sales_order o
        WHERE o.status='COMPLETED' AND EXISTS (SELECT 1 FROM crm.sales_order_line l
            WHERE l.tenant_id=o.tenant_id AND l.order_id=o.id)
        ORDER BY o.tenant_id,o.id LIMIT 1;
    SELECT w.id INTO STRICT other_tenant FROM public.iam_workspace w
        WHERE w.id<>source_order.tenant_id AND w.status='ACTIVE'
          AND NOT EXISTS (SELECT 1 FROM crm.store_front f WHERE f.tenant_id=w.id)
        ORDER BY w.id LIMIT 1;
    SELECT id INTO STRICT shared_event FROM crm.sales_order_status_event
        WHERE tenant_id=source_order.tenant_id AND order_id=source_order.id AND event_type='PLACED';
    INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,status,total_amount,currency,created_at,fulfillment_mode)
    VALUES(source_order.tenant_id,unsealed_order,source_order.pending_email_identity_id,
           'PLACED',source_order.total_amount,source_order.currency,CURRENT_TIMESTAMP,'STANDARD');
    INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot)
    SELECT tenant_id,gen_random_uuid(),unsealed_order,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot
    FROM crm.sales_order_line WHERE tenant_id=source_order.tenant_id AND order_id=source_order.id;
    INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
    VALUES(other_tenant,other_store,'migration-probe-'||other_store::text,'DRAFT',CURRENT_TIMESTAMP);
    INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state)
    VALUES(other_tenant,other_offer,'Migration probe',19,'KZT','DRAFT');
    INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,status,total_amount,currency,created_at,fulfillment_mode)
    VALUES(other_tenant,other_order,source_order.pending_email_identity_id,'PLACED',19,'KZT',CURRENT_TIMESTAMP,'STANDARD');
    INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency)
    VALUES(other_tenant,gen_random_uuid(),other_order,other_offer,'Migration probe',1,19,'KZT');
    INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at)
    VALUES(other_tenant,shared_event,other_order,'PLACED',event_time);
    -- Syntactically valid V1 marker for preservation only; not a V1 digest/replay oracle.
    INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,occurred_at,schema_version,digest)
    VALUES(other_tenant,'SALES','ORDER_PLACED',shared_event,other_order,event_time,1,repeat('b',64));
END $$;
