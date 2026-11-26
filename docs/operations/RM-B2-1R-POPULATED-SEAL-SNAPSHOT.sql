-- Identical comparison before and after 0181/0182; exclude only the newly added seal flag.
SELECT row_text FROM (
 SELECT 'order|'||(to_jsonb(o)-'booked_snapshot_sealed')::text AS row_text FROM crm.sales_order o
 UNION ALL SELECT 'line|'||to_jsonb(l)::text FROM crm.sales_order_line l
 UNION ALL SELECT 'event|'||to_jsonb(e)::text FROM crm.sales_order_status_event e
 UNION ALL SELECT 'fact|'||to_jsonb(f)::text FROM crm.int_canonical_fact f
 UNION ALL SELECT 'movement|'||to_jsonb(m)::text FROM crm.inv_stock_movement m
) s ORDER BY row_text COLLATE "C";
