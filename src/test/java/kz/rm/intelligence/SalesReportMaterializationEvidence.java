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
    public SalesReportMaterializationEvidence(ApplicationContext context,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;
        materializer=new SalesReportMaterializer(context.getBean(AuthorizedAnalyticalReadService.class),jdbc,manager);
    }
    public void denied(VerifiedActor actor,UUID tenant,long calendar,LocalDate last){
        assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last,"KZT"))
            .isInstanceOf(SecurityException.class);
    }
    public void emptyTenant(VerifiedActor actor,UUID tenant,UUID foreign,long calendar,LocalDate last){
        var r=materializer.materialize(actor,tenant,calendar,last,"KZT");
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
}
