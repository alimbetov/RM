package kz.rm.sales;
import kz.rm.iam.*;
import kz.rm.intelligence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** SALES-owned read boundary. No caller supplied SQL or unqualified history claims. */
@Service
public class SalesIntelligenceReadPort {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    public SalesIntelligenceReadPort(JdbcTemplate jdbc,TenantAuthorization auth){this.jdbc=jdbc;this.auth=auth;}
    @Transactional
    public List<MetricResult.Cell> read(VerifiedActor actor,UUID tenant,MetricRegistry.Code code,PeriodResolver.Period period){
        if(actor==null||tenant==null)throw new SecurityException("Verified actor and tenant required");
        auth.requireForCommand(actor.userId(),tenant,DomainPermission.SALES_ORDER_READ);
        String event=switch(code){
            case PLACED_ORDERS,BOOKED_ORDER_AMOUNT -> "PLACED";
            case CONFIRMED_ORDERS -> "CONFIRMED";
            case FULFILLED_ORDERS -> "COMPLETED";
            case CANCELLED_ORDERS -> "CANCELLED";
            default -> throw new IllegalArgumentException("Not a sales metric");
        };
        if(period==null||!period.startInclusive().isBefore(period.endExclusive()))throw new IllegalArgumentException("Invalid period");
        // Cardinality is evaluated across the entire tenant history before the period filter.
        String cohort="""
            WITH eligible AS (
                SELECT tenant_id,order_id,MIN(occurred_at) AS event_at
                FROM crm.sales_order_status_event
                WHERE tenant_id=? AND event_type=?
                GROUP BY tenant_id,order_id HAVING count(*)=1
            )
            """;
        var args=new Object[]{tenant,event,tenant,Timestamp.from(period.startInclusive()),Timestamp.from(period.endExclusive())};
        List<MetricResult.Cell> rows;
        if(code==MetricRegistry.Code.BOOKED_ORDER_AMOUNT){
            rows=jdbc.query(cohort+"""
                SELECT o.currency,SUM(o.total_amount)
                FROM eligible e JOIN crm.sales_order o ON o.tenant_id=e.tenant_id AND o.id=e.order_id
                WHERE o.tenant_id=? AND e.event_at>=? AND e.event_at<?
                GROUP BY o.currency ORDER BY o.currency
                """,(rs,n)->new MetricResult.Cell(rs.getBigDecimal(2),rs.getString(1).trim(),null,null),args);
        }else{
            Long count=jdbc.queryForObject(cohort+"""
                SELECT count(*) FROM eligible e JOIN crm.sales_order o
                    ON o.tenant_id=e.tenant_id AND o.id=e.order_id
                WHERE o.tenant_id=? AND e.event_at>=? AND e.event_at<?
                """,Long.class,args);
            rows=count==null||count==0?List.of():List.of(new MetricResult.Cell(BigDecimal.valueOf(count),null,null,null));
        }
        actor.userId();return List.copyOf(rows);
    }
}
