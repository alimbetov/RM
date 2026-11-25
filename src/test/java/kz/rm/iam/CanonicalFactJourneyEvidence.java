package kz.rm.iam;

import kz.rm.intelligence.CanonicalFactAppender;
import kz.rm.intelligence.CanonicalFactKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real committed owner sources, isolated fixture; this does not claim producer wiring. */
final class CanonicalFactJourneyEvidence {
    static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
                       UUID tenant,UUID order,UUID foreignTenant){
        var tx=new TransactionTemplate(manager);
        UUID event=jdbc.queryForObject("""
            SELECT id FROM crm.sales_order_status_event
            WHERE tenant_id=? AND order_id=? AND event_type='PLACED'
            """,UUID.class,tenant,order);
        var key=new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event);
        assertThatThrownBy(()->appender.append(key)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        tx.executeWithoutResult(s->{assertThat(appender.append(key)).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);s.setRollbackOnly();});
        assertThat(count(jdbc,tenant,event)).isZero();
        assertThat(tx.execute(s->appender.append(key))).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
        assertThat(tx.execute(s->appender.append(key))).isEqualTo(CanonicalFactAppender.Outcome.ALREADY_PRESENT);
        assertThat(count(jdbc,tenant,event)).isEqualTo(1);
        var wrong=new CanonicalFactKey(foreignTenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event);
        assertThatThrownBy(()->tx.execute(s->appender.append(wrong))).hasMessage("CANONICAL_SOURCE_NOT_FOUND");
        assertThat(count(jdbc,foreignTenant,event)).isZero();
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        if(pg){
            assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_order_status_event SET occurred_at=occurred_at WHERE tenant_id=? AND id=?",tenant,event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_order SET total_amount=total_amount+1 WHERE tenant_id=? AND id=?",tenant,order)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=?",tenant,event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
    private static int count(JdbcTemplate jdbc,UUID tenant,UUID event){
        return jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=?",Integer.class,tenant,event);
    }
}
