package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.assertj.core.api.Assertions.*;

/** Invoked by the mandatory P2 real PostgreSQL journey, no additional Spring context. */
public final class BookedDigestPgEvidence {
    private BookedDigestPgEvidence(){}
    private static final class CountingJdbc extends JdbcTemplate {
        int calls;
        CountingJdbc(JdbcTemplate original){super(Objects.requireNonNull(original.getDataSource()));}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){
            calls++;return super.query(sql,mapper,args);
        }
    }
    // Independent population-byte oracle; no calls to verifier framing helpers.
    private static String frame(Object... fields){
        var b=new StringBuilder();
        for(Object field:fields){
            if(field==null)b.append("-1:");
            else{String value=field.toString();b.append(value.length()).append(':').append(value);}
        }
        return b.toString();
    }
    private static String sha(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static String population(JdbcTemplate jdbc,UUID tenant,Instant from,Instant until){
        var rows=jdbc.query("""
            SELECT source_id,subject_id,occurred_at,recorded_at,source_version,schema_version,digest
            FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED'
                AND occurred_at>=? AND occurred_at<?
            """,(rs,n)->frame(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toInstant(),rs.getObject(5,Long.class),rs.getInt(6),rs.getString(7)),
            tenant,Timestamp.from(from),Timestamp.from(until));
        return sha(frame("RM-S1-BOOKED-POPULATION-V1",tenant,from,until,frame(rows.stream().sorted().toArray())));
    }
    public static void parity(JdbcTemplate jdbc,PlatformTransactionManager manager,UUID tenant,Instant from,Instant until,
                              long expectedFacts,long expectedBad,long expectedLegacy){
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        tx.executeWithoutResult(status->{
            var counting=new CountingJdbc(jdbc);
            var bulk=new IndependentCanonicalDigestVerifier(counting).bookedSlice(tenant,from,until,500);
            assertThat(bulk.checked()).isEqualTo(expectedFacts);
            assertThat(bulk.mismatches()).isEqualTo(expectedBad);
            assertThat(bulk.legacy()).isEqualTo(expectedLegacy);
            assertThat(bulk.budgetExceeded()).isFalse();
            assertThat(counting.calls).isEqualTo(expectedFacts==0?1:3);
            // P2's tenant contains no deliberately invalid facts outside this range at this point.
            assertThat(new IndependentCanonicalDigestVerifier(jdbc).mismatches(tenant)).isEqualTo(expectedBad);
            assertThat(bulk.populationDigest()).isEqualTo(population(jdbc,tenant,from,until));
            System.out.printf("S1_DIGEST_PARITY facts=%d mismatches=%d legacy=%d queries=%d%n",
                bulk.checked(),bulk.mismatches(),bulk.legacy(),counting.calls);
        });
    }
    public static void limits(JdbcTemplate jdbc,PlatformTransactionManager manager,UUID tenant,UUID template,Instant from){
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(status->{
            status.setRollbackOnly();
            var until=from.plusSeconds(60);
            // Exact 500 canonical identities, deliberately missing owner events.
            assertThat(jdbc.update("""
                INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,occurred_at,schema_version,digest)
                SELECT ?,'SALES','ORDER_PLACED',gen_random_uuid(),?,?,1,repeat('a',64) FROM generate_series(1,500)
                """,tenant,template,Timestamp.from(from))).isEqualTo(500);
            var counted=new CountingJdbc(jdbc);
            long start=System.nanoTime();
            var atLimit=new IndependentCanonicalDigestVerifier(counted).bookedSlice(tenant,from,until,500);
            assertThat(atLimit.checked()).isEqualTo(500);assertThat(atLimit.mismatches()).isEqualTo(500);
            assertThat(atLimit.budgetExceeded()).isFalse();assertThat(counted.calls).isEqualTo(3);
            System.out.printf("S1_DIGEST_500_FIXTURE elapsedMs=%.3f queries=%d%n",(System.nanoTime()-start)/1e6,counted.calls);
            assertThat(jdbc.update("""
                INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,occurred_at,schema_version,digest)
                VALUES (?,'SALES','ORDER_PLACED',gen_random_uuid(),?,?,1,repeat('a',64))
                """,tenant,template,Timestamp.from(from))).isEqualTo(1);
            counted.calls=0;
            var exceeded=new IndependentCanonicalDigestVerifier(counted).bookedSlice(tenant,from,until,500);
            assertThat(exceeded.budgetExceeded()).isTrue();assertThat(exceeded.checked()).isZero();
            assertThat(exceeded.populationDigest()).isNull();assertThat(counted.calls).isEqualTo(1);
            // Large order fixture, isolated from the fact-limit interval.
            UUID order=UUID.randomUUID(),event=UUID.randomUUID();
            Instant large=until.plusSeconds(1);
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
                SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',total_amount*20001,currency,?,fulfillment_mode
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,order,Timestamp.from(large),tenant,template)).isEqualTo(1);
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source)
                SELECT l.tenant_id,gen_random_uuid(),?,l.offer_id,l.title_snapshot,l.quantity,l.unit_price_snapshot,l.currency,l.catalog_item_id_snapshot,l.price_source
                FROM crm.sales_order_line l CROSS JOIN generate_series(1,20000) WHERE l.tenant_id=? AND l.order_id=?
                """,order,tenant,template)).isEqualTo(20000);
            // Deliberately incomplete owner fact while the order is not sealed yet.
            // Capacity qualification is independent of digest validity.
            assertThat(jdbc.update("""
                INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,occurred_at,schema_version,digest)
                VALUES (?,'SALES','ORDER_PLACED',?,?,?,1,repeat('a',64))
                """,tenant,event,order,Timestamp.from(large))).isEqualTo(1);
            counted.calls=0;
            var atLineLimit=new IndependentCanonicalDigestVerifier(counted).bookedSlice(tenant,large,large.plusSeconds(1),500);
            assertThat(atLineLimit.budgetExceeded()).isFalse();assertThat(atLineLimit.checked()).isEqualTo(1);
            assertThat(atLineLimit.mismatches()).isEqualTo(1);assertThat(counted.calls).isEqualTo(3);
            assertThat(atLineLimit.populationDigest()).isNotNull();
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source)
                SELECT tenant_id,gen_random_uuid(),?,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                """,order,tenant,template)).isEqualTo(1);
            assertThat(jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",
                tenant,event,order,Timestamp.from(large))).isEqualTo(1);
            counted.calls=0;
            var lineLimit=new IndependentCanonicalDigestVerifier(counted).bookedSlice(tenant,large,large.plusSeconds(1),500);
            assertThat(lineLimit.budgetExceeded()).isTrue();assertThat(lineLimit.checked()).isZero();
            assertThat(lineLimit.populationDigest()).isNull();assertThat(counted.calls).isEqualTo(3);
            System.out.println("S1_DIGEST_BUDGET_PROOF facts=500/501 lines=20000/20001 rollback=true");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND occurred_at>=? AND occurred_at<?",
            Long.class,tenant,Timestamp.from(from),Timestamp.from(from.plusSeconds(120)))).isZero();
    }
}
