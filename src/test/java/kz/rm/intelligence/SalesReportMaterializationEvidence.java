package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL owner-side pilot; empty tenant B must never inherit tenant A facts. */
public final class SalesReportMaterializationEvidence {
    private final SalesReportMaterializer materializer;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    public SalesReportMaterializationEvidence(ApplicationContext context,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.manager=manager;
        materializer=new SalesReportMaterializer(context.getBean(AuthorizedAnalyticalReadService.class),jdbc,manager);
    }
    public void denied(VerifiedActor actor,UUID tenant,long calendar,LocalDate last){
        assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last,"KZT"))
            .isInstanceOf(SecurityException.class);
    }
    public void deniedReplay(VerifiedActor actor,UUID tenant,long calendar,LocalDate last){
        assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last,"KZT","empty-"+tenant))
            .isInstanceOf(SecurityException.class);
    }
    public void emptyTenant(VerifiedActor actor,UUID tenant,UUID foreign,long calendar,LocalDate last){
        String key="empty-"+tenant;
        var r=materializer.materialize(actor,tenant,calendar,last,"KZT",key);
        assertThat(materializer.materialize(actor,tenant,calendar,last,"KZT",key)).isEqualTo(r);
        assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last,"USD",key))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("INT_REPORT_REQUEST_CONFLICT");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_source_evidence
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
             AND encode(sha256(scope_bytes),'hex')=? AND encode(sha256(source_bytes),'hex')=?
            """,Long.class,tenant,r.dataset(),r.stagingDate(),r.scopeDigest(),r.sourceDigest())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_request_attempt l JOIN reporting.int_report_instance i
             USING(tenant_id,instance_id) WHERE l.tenant_id=? AND i.idempotency_key=? AND l.selected_at IS NOT NULL
            """,Long.class,tenant,key)).isEqualTo(1L);
        assertThat(r.tenant()).isEqualTo(tenant);
        assertThat(r.expected()).isEqualTo(r.observed());
        assertThat(r.observed().rows()).isEqualTo(48);
        assertThat(jdbc.queryForObject("""
            SELECT build_state FROM reporting.int_report_dataset_attempt
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            """,String.class,tenant,r.dataset(),r.stagingDate())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_dataset
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            AND metric_value IS NULL AND quality_code='MISSING'
            AND ((metric_code='BOOKED_AMOUNT' AND currency='KZT' AND unit_code='CURRENCY')
              OR (metric_code='BOOKED_ORDERS' AND currency IS NULL AND unit_code='COUNT'))
            """,Long.class,tenant,r.dataset(),r.stagingDate())).isEqualTo(48L);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_dataset
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            """,Long.class,foreign,r.dataset(),r.stagingDate())).isZero();
        for(String metric:java.util.List.of("BOOKED_AMOUNT","BOOKED_ORDERS"))
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reporting.int_report_dataset
                WHERE tenant_id=? AND dataset_id=? AND staging_date=? AND metric_code=?
                """,Long.class,tenant,r.dataset(),r.stagingDate(),metric)).isEqualTo(24L);
    }
    /** Synthetic legacy history, created after the original owner/projection assertions.
     * Never modifies an existing order, event or canonical fact. */
    public void historicalAndConcurrent(VerifiedActor actor,UUID tenant,long calendar,
            VerifiedActor otherActor,UUID other,long otherCalendar,LocalDate last,UUID template){
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            historicalOrder(tenant,template,last.minusMonths(1),"KZT","100","125");
            historicalOrder(tenant,template,last.minusMonths(1),"USD","20");
            historicalOrder(tenant,template,last,"KZT","150");
            historicalOrder(tenant,template,last,"KZT","50","100");
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var a=pool.submit(()->{await(start);return materializer.materialize(actor,tenant,calendar,last,"KZT");});
            var b=pool.submit(()->{await(start);return materializer.materialize(otherActor,other,otherCalendar,last,"KZT");});
            start.countDown();
            var ra=a.get(150,java.util.concurrent.TimeUnit.SECONDS);
            var rb=b.get(150,java.util.concurrent.TimeUnit.SECONDS);
            assertReceipt(ra,tenant,4);
            assertReceipt(rb,other,0);
            assertThat(ra.dataset()).isNotEqualTo(rb.dataset());
            assertThat(ra.scopeDigest()).isNotEqualTo(rb.scopeDigest());
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reporting.int_report_dataset
                WHERE tenant_id=? AND dataset_id=? AND staging_date=?
                """,Long.class,other,ra.dataset(),ra.stagingDate())).isZero();
            assertMonth(ra,last.minusMonths(1),"KZT","225","2");
            assertMonth(ra,last,"KZT","300","2");
            var usd=materializer.materialize(actor,tenant,calendar,last,"USD");
            assertReceipt(usd,tenant,3);
            assertMonth(usd,last.minusMonths(1),"USD","20","2");
            assertMonth(usd,last,"USD",null,"2");
            assertThat(usd.scopeDigest()).isNotEqualTo(ra.scopeDigest());
            System.out.println("RM_R02_HISTORICAL_CURRENCY_CONCURRENT_ORACLE_PASS");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException e){throw new AssertionError(e);}
        finally{pool.shutdownNow();}
    }
    private static void await(java.util.concurrent.CountDownLatch latch){
        try{if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("R02_START_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private void historicalOrder(UUID tenant,UUID template,LocalDate month,String currency,String... amounts){
        UUID id=UUID.randomUUID();
        var at=month.atStartOfDay().plusHours(12).atOffset(java.time.ZoneOffset.UTC);
        var total=java.util.Arrays.stream(amounts).map(java.math.BigDecimal::new)
            .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        assertThat(jdbc.update("""
            INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
            SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',?,?,?,fulfillment_mode
            FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,id,total,currency,at,tenant,template)).isEqualTo(1);
        for(String amount:amounts)
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source)
                SELECT tenant_id,?,?,offer_id,title_snapshot,1,?,?,catalog_item_id_snapshot,price_source
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                """,UUID.randomUUID(),id,new java.math.BigDecimal(amount),currency,tenant,template)).isEqualTo(1);
        assertThat(jdbc.update("""
            INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at)
            VALUES (?,?,?,'PLACED',?)
            """,tenant,UUID.randomUUID(),id,at)).isEqualTo(1);
    }
    private void assertReceipt(SalesReportMaterializer.Receipt r,UUID tenant,long nonNull){
        assertThat(r.tenant()).isEqualTo(tenant);
        assertThat(r.expected()).isEqualTo(r.observed());
        assertThat(r.observed().rows()).isEqualTo(48);
        assertThat(jdbc.queryForObject("""
            SELECT build_state FROM reporting.int_report_dataset_attempt
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            """,String.class,tenant,r.dataset(),r.stagingDate())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_dataset
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
              AND metric_value IS NOT NULL AND quality_code='PARTIAL'
            """,Long.class,tenant,r.dataset(),r.stagingDate())).isEqualTo(nonNull);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_dataset
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
              AND metric_value IS NULL AND quality_code='MISSING'
            """,Long.class,tenant,r.dataset(),r.stagingDate())).isEqualTo(48L-nonNull);
    }
    private void assertMonth(SalesReportMaterializer.Receipt r,LocalDate month,String currency,String amount,String orders){
        var lo=month.atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        var hi=month.plusMonths(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        // Independent owner-table oracle, no analytical view and no shared digest implementation.
        var count=jdbc.queryForObject("""
            SELECT count(*)::numeric FROM crm.sales_order_status_event e
            WHERE e.tenant_id=? AND e.event_type='PLACED' AND e.occurred_at>=? AND e.occurred_at<?
              AND EXISTS(SELECT 1 FROM crm.sales_order_line l WHERE l.tenant_id=e.tenant_id AND l.order_id=e.order_id)
              AND 1=(SELECT count(*) FROM crm.sales_order_status_event x
                WHERE x.tenant_id=e.tenant_id AND x.order_id=e.order_id AND x.event_type='PLACED')
            """,java.math.BigDecimal.class,r.tenant(),lo,hi);
        var sum=jdbc.queryForObject("""
            SELECT sum(l.quantity*l.unit_price_snapshot) FROM crm.sales_order_line l
            WHERE l.tenant_id=? AND trim(l.currency)=?
              AND EXISTS(SELECT 1 FROM crm.sales_order_status_event e WHERE e.tenant_id=l.tenant_id
                AND e.order_id=l.order_id AND e.event_type='PLACED' AND e.occurred_at>=? AND e.occurred_at<?)
              AND 1=(SELECT count(*) FROM crm.sales_order_status_event x
                WHERE x.tenant_id=l.tenant_id AND x.order_id=l.order_id AND x.event_type='PLACED')
            """,java.math.BigDecimal.class,r.tenant(),currency,lo,hi);
        assertThat(count).isEqualByComparingTo(orders);
        if(amount==null)assertThat(sum).isNull();else assertThat(sum).isEqualByComparingTo(amount);
        for(String metric:java.util.List.of("BOOKED_AMOUNT","BOOKED_ORDERS")){
            boolean money=metric.equals("BOOKED_AMOUNT");
            var expected=money?sum:count;
            var value=jdbc.queryForObject("""
                SELECT metric_value FROM reporting.int_report_dataset
                WHERE tenant_id=? AND dataset_id=? AND staging_date=? AND metric_code=?
                  AND from_inclusive=? AND until_exclusive=? AND currency IS NOT DISTINCT FROM ?
                  AND quality_code=?
                """,java.math.BigDecimal.class,r.tenant(),r.dataset(),r.stagingDate(),metric,lo,hi,
                money?currency:null,expected==null?"MISSING":"PARTIAL");
            if(expected==null)assertThat(value).isNull();else assertThat(value).isEqualByComparingTo(expected);
        }
    }
}
