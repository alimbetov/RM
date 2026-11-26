-- Post-migration assertions, no persistent changes.
DO $$
DECLARE t UUID; o UUID;
BEGIN
    IF EXISTS (
        SELECT 1 FROM crm.sales_order a
        WHERE a.booked_snapshot_sealed IS DISTINCT FROM
          EXISTS (SELECT 1 FROM crm.sales_order_status_event e WHERE e.tenant_id=a.tenant_id AND e.order_id=a.id)
    ) THEN RAISE EXCEPTION 'POPULATED_SEAL_MISMATCH'; END IF;
    IF NOT EXISTS (SELECT 1 FROM crm.sales_order WHERE NOT booked_snapshot_sealed)
       OR NOT EXISTS (SELECT 1 FROM crm.sales_order WHERE booked_snapshot_sealed AND status='COMPLETED')
       OR NOT EXISTS (SELECT 1 FROM crm.sales_order WHERE booked_snapshot_sealed AND status='CANCELLED')
       OR (SELECT count(DISTINCT tenant_id) FROM crm.sales_order WHERE booked_snapshot_sealed)<2
       OR NOT EXISTS (SELECT 1 FROM crm.int_canonical_fact WHERE schema_version=1)
       OR NOT EXISTS (SELECT 1 FROM crm.int_canonical_fact WHERE schema_version=2)
    THEN RAISE EXCEPTION 'POPULATED_SEAL_FIXTURE_INCOMPLETE'; END IF;
    SELECT a.tenant_id,a.id INTO STRICT t,o FROM crm.sales_order a
        WHERE a.booked_snapshot_sealed AND EXISTS (SELECT 1 FROM crm.sales_order_line l
            WHERE l.tenant_id=a.tenant_id AND l.order_id=a.id)
        ORDER BY a.tenant_id,a.id LIMIT 1;
    BEGIN
        INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot)
        SELECT tenant_id,gen_random_uuid(),order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot
        FROM crm.sales_order_line WHERE tenant_id=t AND order_id=o LIMIT 1;
        RAISE EXCEPTION 'LEGACY_LATE_INSERT_ACCEPTED';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM <> 'SALES_BOOKED_SNAPSHOT_SEALED' THEN RAISE; END IF;
    END;
END $$;
