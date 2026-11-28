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
    private org.springframework.transaction.support.TransactionTemplate raceTx(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
        return tx;
    }
    private void raceLimits(LocalDate d){
        jdbc.execute("SET LOCAL lock_timeout='15s'");
        jdbc.execute("SET LOCAL statement_timeout='25s'");
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock_shared(1380794706,?::date-DATE '1970-01-01')",Integer.class,d);
    }
    private static void await(java.util.concurrent.CountDownLatch latch){
        try{if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("R02_LATCH_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private void blockedBy(int waiter,int holder) throws InterruptedException {
        assertThat(waiter).isNotEqualTo(holder);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        do {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))",Boolean.class,holder,waiter)))return;
            Thread.sleep(20);
        }while(System.nanoTime()<deadline);
        throw new AssertionError("R02_EXPECTED_POSTGRES_BLOCKING_NOT_OBSERVED");
    }
    private long rows(UUID t,UUID a,LocalDate d){
        return jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_dataset WHERE tenant_id=? AND dataset_id=? AND staging_date=?",Long.class,t,a,d);
    }
    @Test void appendFreezeCommitAndRollbackRacePg() throws Exception {
        UUID t=tenant(),w=UUID.randomUUID();LocalDate d=day();ensure(d);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            for(boolean rollback:List.of(false,true)){
                UUID a=UUID.randomUUID();String exp=expected(t,a,BigDecimal.ONE);
                allocate(t,a,d,w,exp);
                var inserted=new java.util.concurrent.CountDownLatch(1);
                var release=new java.util.concurrent.CountDownLatch(1);
                var attempting=new java.util.concurrent.CountDownLatch(1);
                var holder=new java.util.concurrent.atomic.AtomicInteger();
                var waiter=new java.util.concurrent.atomic.AtomicInteger();
                try{
                    var first=pool.submit(()->raceTx().executeWithoutResult(status->{
                        raceLimits(d);holder.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                        assertThat(append(t,a,d,w,1,BigDecimal.ONE)).isTrue();
                        inserted.countDown();await(release);
                        if(rollback)status.setRollbackOnly();
                    }));
                    await(inserted);
                    var second=pool.submit(()->{
                        try{
                            raceTx().executeWithoutResult(status->{
                                raceLimits(d);waiter.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                                attempting.countDown();freeze(t,a,d,w);
                            });
                            return true;
                        }catch(DataAccessException e){
                            assertThat(e).hasMessageContaining("INT_REPORT_ROW_COUNT");
                            return false;
                        }
                    });
                    await(attempting);blockedBy(waiter.get(),holder.get());release.countDown();
                    first.get(15,java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(second.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(!rollback);
                    if(rollback){
                        assertThat(state(t,a)).isEqualTo("BUILDING");assertThat(rows(t,a,d)).isZero();
                        assertThat(append(t,a,d,w,1,BigDecimal.ONE)).isTrue();freeze(t,a,d,w);
                    }
                    assertThat(state(t,a)).isEqualTo("VALIDATING");
                    assertThat(rows(t,a,d)).isEqualTo(1);
                    assertThatThrownBy(()->append(t,a,d,w,1,BigDecimal.ONE)).isInstanceOf(DataAccessException.class);
                    assertThat(new ReportDatasetValidationStore(jdbc,transactions).validateAndSeal(t,a,d,w,1).sha256()).isEqualTo(exp);
                    assertThat(state(t,a)).isEqualTo("READY");
                }finally{release.countDown();}
            }
        }finally{pool.shutdownNow();}
    }
    @Test void expiredBuildCannotResumeAfterWaitingPg() throws Exception {
        UUID t=tenant(),a=UUID.randomUUID(),w=UUID.randomUUID();LocalDate d=day();ensure(d);
        // Owner-only synthetic short lease, no trigger disabled and no mutation of a live lease.
        String exp=expected(t,a,BigDecimal.ONE);
        jdbc.update("""
            INSERT INTO reporting.int_report_dataset_attempt(tenant_id,dataset_id,staging_date,allocated_at,
                scope_digest,source_manifest_digest,expected_rows,expected_digest,build_state,build_owner,build_token,build_until)
            VALUES(?,?,?,clock_timestamp(),?,?,1,?,'BUILDING',?,1,clock_timestamp()+interval '5 seconds')
            """,t,a,d,SCOPE,SOURCE,exp,w);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var attempting=new java.util.concurrent.CountDownLatch(1);
        var holder=new java.util.concurrent.atomic.AtomicInteger();
        var waiter=new java.util.concurrent.atomic.AtomicInteger();
        try{
            var first=pool.submit(()->raceTx().executeWithoutResult(status->{
                raceLimits(d);holder.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                jdbc.queryForObject("SELECT build_token FROM reporting.int_report_dataset_attempt WHERE tenant_id=? AND dataset_id=? FOR NO KEY UPDATE",Long.class,t,a);
                locked.countDown();await(release);
            }));
            await(locked);
            var second=pool.submit(()->{
                assertThatThrownBy(()->raceTx().executeWithoutResult(status->{
                    raceLimits(d);waiter.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    attempting.countDown();append(t,a,d,w,1,BigDecimal.ONE);
                })).isInstanceOf(DataAccessException.class).hasMessageContaining("INT_REPORT_BUILD_FENCE");
            });
            await(attempting);blockedBy(waiter.get(),holder.get());
            assertThat(jdbc.queryForObject("SELECT build_until>clock_timestamp() FROM reporting.int_report_dataset_attempt WHERE tenant_id=? AND dataset_id=?",Boolean.class,t,a)).isTrue();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(7);
            while(Boolean.TRUE.equals(jdbc.queryForObject("SELECT build_until>clock_timestamp() FROM reporting.int_report_dataset_attempt WHERE tenant_id=? AND dataset_id=?",Boolean.class,t,a))){
                if(System.nanoTime()>deadline)throw new AssertionError("R02_LEASE_EXPIRY_TIMEOUT");
                Thread.sleep(20);
            }
            release.countDown();first.get(15,java.util.concurrent.TimeUnit.SECONDS);second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(rows(t,a,d)).isZero();assertThat(state(t,a)).isEqualTo("BUILDING");
            assertThatThrownBy(()->freeze(t,a,d,w)).isInstanceOf(DataAccessException.class).hasMessageContaining("INT_REPORT_BUILD_FENCE");
            UUID fresh=UUID.randomUUID(),nextWorker=UUID.randomUUID();String next=expected(t,fresh,BigDecimal.ONE);
            allocate(t,fresh,d,nextWorker,next);assertThat(append(t,fresh,d,nextWorker,1,BigDecimal.ONE)).isTrue();
            freeze(t,fresh,d,nextWorker);
            assertThat(new ReportDatasetValidationStore(jdbc,transactions).validateAndSeal(t,fresh,d,nextWorker,1).sha256()).isEqualTo(next);
            assertThat(state(t,fresh)).isEqualTo("READY");assertThat(state(t,a)).isEqualTo("BUILDING");
            assertThat(jdbc.queryForObject("SELECT 'BUILD_INCOMPLETE'=ANY(reporting.int_report_retention_blockers(?))",Boolean.class,d)).isTrue();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void durableRequestBindingAndLostResponseRecoveryPg(){
        UUID t=tenant(),other=tenant(),i=UUID.randomUUID(),a=UUID.randomUUID(),w=UUID.randomUUID();
        LocalDate d=day();ensure(d);String key="R02-"+i,req="3".repeat(64),exp=expected(t,a,BigDecimal.ONE);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_register_request(?,?,?,?,?,?,?)",
            UUID.class,t,i,key,req,SCOPE,"RM-R02","1")).isEqualTo(i);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_register_request(?,?,?,?,?,?,?)",
            UUID.class,t,UUID.randomUUID(),key,req,SCOPE,"RM-R02","1")).isEqualTo(i);
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_register_request(?,?,?,?,?,?,?)",
            UUID.class,t,UUID.randomUUID(),key,SOURCE,SCOPE,"RM-R02","1")).isInstanceOf(DataAccessException.class)
            .hasMessageContaining("INT_REPORT_REQUEST_CONFLICT");
        UUID foreign=UUID.randomUUID();
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_register_request(?,?,?,?,?,?,?)",
            UUID.class,other,foreign,key,req,SCOPE,"RM-R02","1")).isEqualTo(foreign);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_allocate_request(?,?,?,?,?,?,?,?,?)",
            Boolean.class,t,i,a,d,w,SCOPE,SOURCE,1L,exp)).isTrue();
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_allocate_request(?,?,?,?,?,?,?,?,?)",
            Boolean.class,t,i,UUID.randomUUID(),d,w,SCOPE,SOURCE,1L,exp)).isInstanceOf(DataAccessException.class)
            .hasMessageContaining("INT_REPORT_REQUEST_ALREADY_HAS_RESULT_OR_ACTIVE_ATTEMPT");
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_select_request(?,?,?,?)",
            UUID.class,t,i,a,d)).isInstanceOf(DataAccessException.class).hasMessageContaining("INT_REPORT_DATASET_NOT_READY");
        assertThat(append(t,a,d,w,1,BigDecimal.ONE)).isTrue();freeze(t,a,d,w);
        new ReportDatasetValidationStore(jdbc,transactions).validateAndSeal(t,a,d,w,1L);
        // Simulated lost response after seal: lookup uses the persistent binding.
        UUID found=jdbc.queryForObject("""
            SELECT l.dataset_id FROM reporting.int_report_request_attempt l
            JOIN reporting.int_report_dataset_attempt b USING(tenant_id,dataset_id,staging_date)
            WHERE l.tenant_id=? AND l.instance_id=? AND b.build_state='READY'
            """,UUID.class,t,i);
        assertThat(found).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_select_request(?,?,?,?)",UUID.class,t,i,found,d)).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_select_request(?,?,?,?)",UUID.class,t,i,found,d)).isEqualTo(a);
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_select_request(?,?,?,?)",
            UUID.class,t,foreign,a,d)).isInstanceOf(DataAccessException.class).hasMessageContaining("INT_REPORT_REQUEST_SCOPE");
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_select_request(?,?,?,?)",
            UUID.class,other,foreign,a,d)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_request_attempt WHERE tenant_id=? AND instance_id=? AND selected_at IS NOT NULL",
            Long.class,t,i)).isEqualTo(1L);
        assertThatThrownBy(()->jdbc.queryForObject("SELECT reporting.int_report_allocate_request(?,?,?,?,?,?,?,?,?)",
            Boolean.class,t,i,UUID.randomUUID(),d,w,SCOPE,SOURCE,1L,exp)).isInstanceOf(DataAccessException.class)
            .hasMessageContaining("INT_REPORT_REQUEST_ALREADY_HAS_RESULT_OR_ACTIVE_ATTEMPT");
    }
    @Test void sealAndReplacementAllocationSerializePg() throws Exception {
        UUID t=tenant(),i=UUID.randomUUID(),a=UUID.randomUUID(),w=UUID.randomUUID(),fresh=UUID.randomUUID();
        LocalDate d=day();ensure(d);String exp=expected(t,a,BigDecimal.ONE);
        assertThat(jdbc.queryForObject("SELECT reporting.int_report_register_request(?,?,?,?,?,?,?)",
            UUID.class,t,i,"race-"+i,SOURCE,SCOPE,"RM-R02","1")).isEqualTo(i);
        // Short lease on a new isolated fixture; existing attempt identity stays immutable.
        raceTx().executeWithoutResult(status->{
            raceLimits(d);
            jdbc.update("""
                INSERT INTO reporting.int_report_dataset_attempt(tenant_id,dataset_id,staging_date,allocated_at,
                    scope_digest,source_manifest_digest,expected_rows,expected_digest,build_state,build_owner,build_token,build_until)
                VALUES(?,?,?,clock_timestamp(),?,?,1,?,'BUILDING',?,1,clock_timestamp()+interval '5 seconds')
                """,t,a,d,SCOPE,SOURCE,exp,w);
            jdbc.update("INSERT INTO reporting.int_report_request_attempt(tenant_id,instance_id,dataset_id,staging_date) VALUES(?,?,?,?)",t,i,a,d);
            assertThat(append(t,a,d,w,1,BigDecimal.ONE)).isTrue();freeze(t,a,d,w);
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var sealed=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var attempting=new java.util.concurrent.CountDownLatch(1);
        var holder=new java.util.concurrent.atomic.AtomicInteger();
        var waiter=new java.util.concurrent.atomic.AtomicInteger();
        try{
            var first=pool.submit(()->raceTx().executeWithoutResult(status->{
                raceLimits(d);holder.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                assertThat(jdbc.queryForObject("SELECT reporting.int_report_seal(?,?,?,?,?,?,?)",
                    Boolean.class,t,a,d,w,1L,1L,exp)).isTrue();
                sealed.countDown();await(release);
            }));
            await(sealed);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(7);
            while(Boolean.TRUE.equals(jdbc.queryForObject("SELECT build_until>clock_timestamp() FROM reporting.int_report_dataset_attempt WHERE tenant_id=? AND dataset_id=?",Boolean.class,t,a))){
                if(System.nanoTime()>deadline)throw new AssertionError("R02_LEASE_EXPIRY_TIMEOUT");
                Thread.sleep(20);
            }
            var second=pool.submit(()->assertThatThrownBy(()->raceTx().executeWithoutResult(status->{
                raceLimits(d);waiter.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                attempting.countDown();
                jdbc.queryForObject("SELECT reporting.int_report_allocate_request(?,?,?,?,?,?,?,?,?)",
                    Boolean.class,t,i,fresh,d,UUID.randomUUID(),SCOPE,SOURCE,1L,expected(t,fresh,BigDecimal.ONE));
            })).isInstanceOf(DataAccessException.class).hasMessageContaining("INT_REPORT_REQUEST_ALREADY_HAS_RESULT_OR_ACTIVE_ATTEMPT"));
            await(attempting);blockedBy(waiter.get(),holder.get());release.countDown();
            first.get(15,java.util.concurrent.TimeUnit.SECONDS);second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(state(t,a)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_request_attempt WHERE tenant_id=? AND instance_id=?",Long.class,t,i)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT reporting.int_report_select_request(?,?,?,?)",UUID.class,t,i,a,d)).isEqualTo(a);
        }finally{release.countDown();pool.shutdownNow();}
    }
}
