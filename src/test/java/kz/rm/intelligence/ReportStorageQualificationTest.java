package kz.rm.intelligence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RM_TEST_DB_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(properties={"rm.mvp.service-mode.enabled=true","rm.currency.commerce-admission.enabled=true"})
class ReportStorageQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired kz.rm.iam.OrganizationRepository organizations;
    @Autowired kz.rm.iam.WorkspaceRepository workspaces;

    private static final String SCOPE="0".repeat(64), SOURCE="1".repeat(64);
    private static final OffsetDateTime LO=OffsetDateTime.parse("2026-10-01T00:00:00Z");
    private static final OffsetDateTime HI=OffsetDateTime.parse("2026-11-01T00:00:00Z");

    private UUID tenant() {
        var org=organizations.saveAndFlush(new kz.rm.iam.OrganizationEntity(UUID.randomUUID(),"Report LLP","Report","KZ"));
        return workspaces.saveAndFlush(kz.rm.iam.WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
    }
    private LocalDate day() {
        return jdbc.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date",LocalDate.class);
    }
    private void ensure(LocalDate d) {
        jdbc.queryForObject("SELECT reporting.int_report_ensure_day(?)",Boolean.class,d);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_ensure_day(?)",Boolean.class,d)).isFalse();
    }
    private String expected(UUID t,UUID a,BigDecimal value) {
        var digest=new ReportDatasetDigest(new ReportDatasetDigest.Header(t,a,SCOPE,SOURCE));
        digest.append(new ReportDatasetDigest.Row(1,"BOOKED_AMOUNT",1,LO.toInstant(),HI.toInstant(),
            List.of(),"CURRENCY","KZT",value,DataQuality.PARTIAL,"HISTORICAL_COVERAGE_UNQUALIFIED"));
        return digest.finish().sha256();
    }
    private void allocate(UUID t,UUID a,LocalDate d,UUID w,String digest) {
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_allocate(?,?,?,?,?,?,?,?)",Boolean.class,
            t,a,d,w,SCOPE,SOURCE,1L,digest)).isTrue();
    }
    private boolean append(UUID t,UUID a,LocalDate d,UUID w,long token,BigDecimal value) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT reporting.int_report_append(?,?,?,?,?,1::bigint,'BOOKED_AMOUNT',1::bigint,
             ?,?,'[]'::jsonb,'CURRENCY','KZT',?,'PARTIAL','HISTORICAL_COVERAGE_UNQUALIFIED')
            """,Boolean.class,t,a,d,w,token,LO,HI,value));
    }
    private void freeze(UUID t,UUID a,LocalDate d,UUID w) {
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_freeze(?,?,?,?,?)",Boolean.class,t,a,d,w,1L)).isTrue();
    }
    private String state(UUID t,UUID a) {
        return jdbc.queryForObject("SELECT build_state FROM reporting.int_report_dataset_attempt WHERE tenant_id=? AND dataset_id=?",
            String.class,t,a);
    }
    @Test void actualStoredBytesAndTenantFencePg() {
        UUID t=tenant(),other=tenant(),a=UUID.randomUUID(),w=UUID.randomUUID();
        LocalDate d=day(); ensure(d);
        String exp=expected(t,a,new BigDecimal("10.5"));
        allocate(t,a,d,w,exp);
        assertThatThrownBy(()->append(other,a,d,w,1,BigDecimal.ONE)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->append(t,a,d,w,2,BigDecimal.ONE)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->append(t,a,d,UUID.randomUUID(),1,BigDecimal.ONE)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->freeze(t,a,d,w)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->append(t,a,d,w,1,new BigDecimal("1E+20000"))).isInstanceOf(DataAccessException.class);
        var batchStore=new ReportDatasetBatchStore(jdbc,transactions);
        var typed=new ReportDatasetDigest.Row(1,"BOOKED_AMOUNT",1,LO.toInstant(),HI.toInstant(),
            List.of(),"CURRENCY","KZT",new BigDecimal("10.5000"),DataQuality.PARTIAL,"HISTORICAL_COVERAGE_UNQUALIFIED");
        assertThat(batchStore.append(t,a,d,w,1,List.of(typed))).isEqualTo(1);
        assertThat(batchStore.append(t,a,d,w,1,List.of(typed))).isZero();
        assertThatThrownBy(()->batchStore.append(other,a,d,w,1,List.of(typed))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->batchStore.append(t,a,d,w,2,List.of(typed))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->batchStore.append(t,a,d,w,1,List.of(typed,typed))).isInstanceOf(IllegalArgumentException.class);
        String batch = """
            [{"row_key":1,"metric_code":"BOOKED_AMOUNT","method_version":1,
            "from_inclusive":"2026-10-01T00:00:00Z","until_exclusive":"2026-11-01T00:00:00Z",
            "dimensions":[],"unit_code":"CURRENCY","currency":"KZT","metric_value":"10.5",
            "quality_code":"PARTIAL","quality_reason":"HISTORICAL_COVERAGE_UNQUALIFIED"}]
            """;
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_append_batch(?,?,?,?,?,?::jsonb)",
            Integer.class,t,a,d,w,1L,batch)).isZero();
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_append_batch(?,?,?,?,?,?::jsonb)",
            Integer.class,other,a,d,w,1L,batch)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_append_batch(?,?,?,?,?,?::jsonb)",
            Integer.class,t,a,d,w,2L,batch)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_append_batch(?,?,?,?,?,?::jsonb)",
            Integer.class,t,a,d,w,1L,batch.replace("10.5","11.5"))).isInstanceOf(DataAccessException.class);
        assertThat(append(t,a,d,w,1,new BigDecimal("10.5"))).isFalse();
        assertThatThrownBy(()->append(t,a,d,w,1,BigDecimal.TEN)).isInstanceOf(DataAccessException.class);
        freeze(t,a,d,w);
        assertThatThrownBy(()->append(t,a,d,w,1,new BigDecimal("10.5"))).isInstanceOf(DataAccessException.class);
        var result=new ReportDatasetValidationStore(jdbc,transactions).validateAndSeal(t,a,d,w,1L);
        assertThat(result.sha256()).isEqualTo(exp);
        assertThat(state(t,a)).isEqualTo("READY");
        assertThatThrownBy(()->append(t,a,d,w,1,BigDecimal.ONE)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_dataset WHERE staging_date=? AND tenant_id=? AND dataset_id=?",
            Long.class,d,other,a)).isZero();
        assertThat(jdbc.queryForObject("SELECT 'RETENTION_NOT_ADMITTED'=ANY(reporting.int_report_retention_blockers(?))",
            Boolean.class,d)).isTrue();
    }
    @Test void mismatchedObservedDigestNeverSealsPg() {
        UUID t=tenant(),a=UUID.randomUUID(),w=UUID.randomUUID();
        LocalDate d=day();ensure(d);
        allocate(t,a,d,w,expected(t,a,BigDecimal.ONE));
        append(t,a,d,w,1,BigDecimal.TEN);
        freeze(t,a,d,w);
        assertThatThrownBy(()->new ReportDatasetValidationStore(jdbc,transactions).validateAndSeal(t,a,d,w,1L))
            .isInstanceOf(IllegalStateException.class).hasMessage("REPORT_VALIDATION_MISMATCH");
        assertThat(state(t,a)).isEqualTo("VALIDATING");
        // Separate attempt: conflict on an existing ordinal must prevent
        // insertion of the other valid ordinal from the same batch.
        UUID b=UUID.randomUUID();
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_allocate(?,?,?,?,?,?,?,?)",Boolean.class,
            t,b,d,w,SCOPE,SOURCE,2L,"2".repeat(64))).isTrue();
        append(t,b,d,w,1,BigDecimal.ONE);
        var conflicting=new ReportDatasetDigest.Row(1,"BOOKED_AMOUNT",1,LO.toInstant(),HI.toInstant(),
            List.of(),"CURRENCY","KZT",BigDecimal.TEN,DataQuality.PARTIAL,"HISTORICAL_COVERAGE_UNQUALIFIED");
        var second=new ReportDatasetDigest.Row(2,"BOOKED_AMOUNT",1,LO.toInstant(),HI.toInstant(),
            List.of(),"CURRENCY","KZT",BigDecimal.ONE,DataQuality.PARTIAL,"HISTORICAL_COVERAGE_UNQUALIFIED");
        var batchStore=new ReportDatasetBatchStore(jdbc,transactions);
        assertThatThrownBy(()->batchStore.append(t,b,d,w,1,List.of(second,conflicting))).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_dataset WHERE staging_date=? AND tenant_id=? AND dataset_id=?",
            Long.class,d,t,b)).isEqualTo(1L);
        assertThat(batchStore.append(t,b,d,w,1,List.of(second))).isEqualTo(1);
        assertThat(batchStore.append(t,b,d,w,1,List.of(second))).isZero();
        assertThat(jdbc.queryForObject("SELECT observed_digest IS NULL AND ready_at IS NULL FROM reporting.int_report_dataset_attempt WHERE tenant_id=? AND dataset_id=?",
            Boolean.class,t,a)).isTrue();
    }
    @Test void consumerRegistrationAndClaimAreTenantAndStateScopedPg() {
        UUID t=tenant(),other=tenant(),a=UUID.randomUUID(),w=UUID.randomUUID(),i=UUID.randomUUID(),c=UUID.randomUUID();
        LocalDate d=day();ensure(d);
        allocate(t,a,d,w,expected(t,a,BigDecimal.ONE));
        for(UUID tenant:List.of(t,other)) jdbc.update("""
            INSERT INTO reporting.int_report_instance(tenant_id,instance_id,idempotency_key,request_digest,scope_digest,report_code,template_version)
            VALUES(?,?,?,?,?,'RM-R02','1')
            """,tenant,i,"key-"+i,SCOPE,SCOPE);
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_register_consumer(?,?,?,?,?)",
            Boolean.class,other,i,c,a,d)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_register_consumer(?,?,?,?,?)",
            Boolean.class,t,i,c,a,d)).isTrue();
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_claim_consumer(?,?,?,?,?,?)",
            Long.class,t,i,c,a,d,w)).isInstanceOf(DataAccessException.class);
        append(t,a,d,w,1,BigDecimal.ONE);freeze(t,a,d,w);
        new ReportDatasetValidationStore(jdbc,transactions).validateAndSeal(t,a,d,w,1L);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_claim_consumer(?,?,?,?,?,?)",
            Long.class,t,i,c,a,d,w)).isEqualTo(1L);
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_claim_consumer(?,?,?,?,?,?)",
            Long.class,t,i,c,a,d,UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        // Owner-only fixture clock change, not an admitted runtime lease mutation.
        jdbc.update("UPDATE reporting.int_report_consumer SET lease_until=clock_timestamp()-interval '1 second' WHERE tenant_id=? AND instance_id=? AND consumer_id=?",t,i,c);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_claim_consumer(?,?,?,?,?,?)",
            Long.class,t,i,c,a,d,UUID.randomUUID())).isEqualTo(2L);
    }
}
