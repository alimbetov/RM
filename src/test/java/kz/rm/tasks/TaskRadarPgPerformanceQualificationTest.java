package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TaskRadarPgPerformanceQualificationTest {
    @Autowired TaskService tasks;
    @Autowired TaskOverdueQueryService radar;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private static final Path REPORT=Path.of("target/radar-performance");
    private static final int WARMUP=10, SAMPLES=50;
    private SignedJwtTestAuthority authority;

    @BeforeEach void startAuthority() throws Exception { authority=SignedJwtTestAuthority.start(); }
    @AfterEach void closeAuthority() { if(authority!=null)authority.close(); }

    private record Tenant(UUID id, VerifiedActor manager,VerifiedActor staff,UUID orgId,VerifiedActor owner) {}
    private VerifiedActor verified(UUID user) throws Exception {
        String subject=jdbc.queryForObject("SELECT external_subject FROM public.iam_user_account WHERE id=?",String.class,user);
        return new OidcActorResolver(authority.decoder(),users).resolve(authority.access(subject));
    }
    private Tenant tenant() throws Exception {
        UUID owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-owner-"+UUID.randomUUID(),"Task owner")).getId();
        UUID manager=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-manager-"+UUID.randomUUID(),"Task manager")).getId();
        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-staff-"+UUID.randomUUID(),"Task employee")).getId();
        UUID org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
            "TASK LLP","TASK workspace","KZ")).getId();
        UUID tenant=workspaces.saveAndFlush(
            WorkspaceEntity.organization(UUID.randomUUID(),org)).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org,owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org,manager,OrganizationMembershipEntity.Role.MANAGER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org,staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        VerifiedActor a=verified(owner),b=verified(staff);
        var managerRole=roles.createRole(a,tenant,
            "TASK_M_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Task manager");
        roles.grant(a,tenant,managerRole.id(),DomainPermission.TASK_MANAGE);
        roles.grant(a,tenant,managerRole.id(),DomainPermission.TASK_READ);
        roles.grant(a,tenant,managerRole.id(),DomainPermission.CRM_READ);
        roles.assign(a,tenant,manager,managerRole.id());
        var employee=roles.createRole(a,tenant,
            "TASK_E_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Task employee");
        roles.grant(a,tenant,employee.id(),DomainPermission.TASK_EXECUTE);
        roles.grant(a,tenant,employee.id(),DomainPermission.TASK_READ);
        roles.assign(a,tenant,staff,employee.id());
        roles.enable(a,tenant);
        return new Tenant(tenant,verified(manager),b,org,a);
    }


    private TaskService.Task create(Tenant t,Instant due) {
        return tasks.create(t.manager(),t.id(),"Review order",t.staff().userId(),due,
            "radar-"+UUID.randomUUID());
    }
    private List<UUID> ids(TaskOverdueQueryService.OverdueSignalPage p) {
        return p.items().stream().map(TaskOverdueQueryService.OverdueSignal::taskId).toList();
    }



    @BeforeEach void requireDedicatedPg() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("rm.radar.performance"),"explicit C3 workload only");
        try(var c=dataSource.getConnection()){
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        Files.createDirectories(REPORT);
        Files.writeString(REPORT.resolve("environment.txt"),
            "postgres="+jdbc.queryForObject("SELECT version()",String.class)+"\n"+
            "shared_buffers="+jdbc.queryForObject("SHOW shared_buffers",String.class)+"\n"+
            "work_mem="+jdbc.queryForObject("SHOW work_mem",String.class)+"\n"+
            "java="+System.getProperty("java.version")+"\n"+
            "processors="+Runtime.getRuntime().availableProcessors()+"\n"+
            "jvmMaxHeapBytes="+Runtime.getRuntime().maxMemory()+"\n"+
            "warmup="+WARMUP+"; samplesPerReadVariant="+SAMPLES+"\n"+
            "fixture=synthetic; warm steady-state; CI runner; not production SLA\n");
    }

    private record Population(Tenant dense,Tenant sparse,Instant anchor,int total){}
    private void seed(Tenant t,int count,boolean dense,Instant anchor){
        // Intentionally legacy/synthetic task rows with no fabricated history.
        // Dense and sparse distributions, mixed statuses, ownership overlap,
        // invisible rows, null deadlines and unassigned rows.
        int inserted=jdbc.update("""
            WITH p AS (SELECT CAST(? AS uuid) AS tenant,CAST(? AS uuid) AS staff,
                CAST(? AS uuid) AS manager,CAST(? AS timestamptz) AS anchor,
                CAST(? AS integer) AS n,CAST(? AS boolean) AS dense)
            INSERT INTO crm.task_item(tenant_id,id,assignee_user_id,status,due_at,
                                      created_at,row_version,created_by)
            SELECT tenant,md5(tenant::text || ':' || g::text)::uuid,
                CASE WHEN g%13=0 OR g%11=0 THEN NULL
                     WHEN g%5=0 THEN manager ELSE staff END,
                CASE WHEN g%13=0 THEN 'QUEUED' WHEN g%7=0 THEN 'COMPLETED'
                     WHEN g%3=0 THEN 'IN_PROGRESS' WHEN g%3=1 THEN 'OPEN' ELSE 'BLOCKED' END,
                CASE WHEN g%17=0 THEN NULL
                     WHEN (dense AND g%4<>0) OR (NOT dense AND g%101=1)
                     THEN anchor-(3600+g%5000)*INTERVAL '1 second'
                     ELSE anchor+(3600+g%5000)*INTERVAL '1 second' END,
                anchor-INTERVAL '10 days',0,
                CASE WHEN g%9=0 THEN staff ELSE manager END
            FROM p CROSS JOIN LATERAL generate_series(1,p.n) AS g
            """,ps->{ps.setObject(1,t.id());ps.setObject(2,t.staff().userId());
                ps.setObject(3,t.manager().userId());ps.setObject(4,anchor.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setInt(5,count);ps.setBoolean(6,dense);});
        assertThat(inserted).isEqualTo(count);
    }
    private Population populate(int total)throws Exception{
        var dense=tenant();var sparse=tenant();
        Instant anchor=Instant.now().truncatedTo(ChronoUnit.MICROS);
        seed(dense,total/2,true,anchor);seed(sparse,total-total/2,false,anchor);
        jdbc.execute("ANALYZE crm.task_item");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.task_item WHERE tenant_id IN (?,?)",
            Long.class,dense.id(),sparse.id())).isEqualTo((long)total);
        Files.writeString(REPORT.resolve("fixture-"+total+".txt"),
            "fixtureRows="+total+"; dense="+(total/2)+"; sparse="+(total-total/2)+"\n"+
            "wholeTableRows="+jdbc.queryForObject("SELECT count(*) FROM crm.task_item",Long.class)+"\n");
        return new Population(dense,sparse,anchor,total);
    }

    private record Expected(UUID id,Instant due,long version,TaskState state){}
    private List<Expected> oracle(Tenant t,Instant cutoff,int limit,TaskOverdueQueryService.OverdueCursor cursor){
        String sql="""
            SELECT id,due_at,row_version,status FROM crm.task_item
            WHERE tenant_id=? AND (assignee_user_id=? OR created_by=?)
              AND assignee_user_id IS NOT NULL AND status IN ('OPEN','IN_PROGRESS','BLOCKED')
              AND due_at<?
            """+(cursor==null?"":" AND (due_at>? OR (due_at=? AND id>?)) ")+" ORDER BY due_at,id LIMIT ?";
        return jdbc.query(sql,ps->{
            int i=1;ps.setObject(i++,t.id());ps.setObject(i++,t.staff().userId());ps.setObject(i++,t.staff().userId());
            ps.setObject(i++,cutoff.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
            if(cursor!=null){
                ps.setObject(i++,cursor.lastDueAt().atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(i++,cursor.lastDueAt().atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(i++,cursor.lastTaskId());
            }
            ps.setInt(i,limit+1);
        },(rs,n)->new Expected(rs.getObject(1,UUID.class),rs.getObject(2,java.time.OffsetDateTime.class).toInstant(),
            rs.getLong(3),TaskState.valueOf(rs.getString(4))));
    }
    private void compare(Tenant t,int limit,TaskOverdueQueryService.OverdueCursor cursor,
                         TaskOverdueQueryService.OverdueSignalPage page){
        var expected=oracle(t,page.evaluatedAt(),limit,cursor);
        assertThat(expected).isNotEmpty();
        assertThat(page.items().stream().map(x->new Expected(x.taskId(),x.dueAt(),x.sourceVersion(),x.state())).toList())
            .containsExactlyElementsOf(expected.subList(0,Math.min(limit,expected.size())));
        assertThat(page.nextCursor()!=null).isEqualTo(expected.size()>limit);
        if(page.nextCursor()!=null){
            var last=page.items().getLast();
            assertThat(page.nextCursor().lastTaskId()).isEqualTo(last.taskId());
            assertThat(page.nextCursor().lastDueAt()).isEqualTo(last.dueAt());
        }
    }
    private void plan(Tenant t,int limit,Instant cutoff,TaskOverdueQueryService.OverdueCursor cursor,String label)throws Exception{
        String sql="EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+TaskTimingEngine.overduePage(true,cursor!=null);
        String result=jdbc.query(sql,ps->{
            int i=1;ps.setObject(i++,cutoff.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setObject(i++,t.id());ps.setObject(i++,t.staff().userId());ps.setObject(i++,t.staff().userId());
            if(cursor!=null){
                ps.setObject(i++,cursor.lastDueAt().atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(i++,cursor.lastDueAt().atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(i++,cursor.lastTaskId());
            }
            ps.setInt(i,limit+1);
        },rs->{if(!rs.next())throw new IllegalStateException("missing EXPLAIN");return rs.getString(1);});
        assertThat(result).contains("Execution Time","Shared Hit Blocks");
        Files.writeString(REPORT.resolve(label+"-plan.json"),result,StandardCharsets.UTF_8);
    }
    private static double percentile(List<Long> values,double p){
        assertThat(values).isNotEmpty();
        var sorted=new ArrayList<>(values);Collections.sort(sorted);
        return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*p)-1))/1_000_000.0;
    }
    private void samples(String label,List<Long> values)throws Exception{
        StringBuilder raw=new StringBuilder("sample,nanos\n");
        for(int i=0;i<values.size();i++)raw.append(i+1).append(',').append(values.get(i)).append('\n');
        Files.writeString(REPORT.resolve(label+"-samples.csv"),raw);
        String line=String.format(Locale.ROOT,"%s,n=%d,p50Ms=%.3f,p95Ms=%.3f,maxMs=%.3f%n",
            label,values.size(),percentile(values,.50),percentile(values,.95),percentile(values,1.0));
        Files.writeString(REPORT.resolve("measurements.txt"),line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        System.out.print("RADAR_C3 "+line);
    }
    private double measureRead(Tenant t,int limit,TaskOverdueQueryService.OverdueCursor cursor,String label)throws Exception{
        var initial=radar.listMine(t.staff(),t.id(),limit,cursor);compare(t,limit,cursor,initial);
        for(int i=0;i<WARMUP;i++)radar.listMine(t.staff(),t.id(),limit,cursor);
        List<Long> values=new ArrayList<>();
        for(int i=0;i<SAMPLES;i++){
            long start=System.nanoTime();var page=radar.listMine(t.staff(),t.id(),limit,cursor);
            values.add(System.nanoTime()-start);
            assertThat(ids(page)).containsExactlyElementsOf(ids(initial));
        }
        samples(label,values);plan(t,limit,initial.evaluatedAt(),cursor,label);
        return percentile(values,.95);
    }
    private void readWorkload(Population p)throws Exception{
        List<Double> results=new ArrayList<>();
        for(var t:List.of(p.dense(),p.sparse())){
            String density=t==p.dense()?"dense":"sparse";
            for(int limit:new int[]{20,100}){
                String label=p.total()+"-"+density+"-limit"+limit;
                results.add(measureRead(t,limit,null,label+"-first"));
                var first=radar.listMine(t.staff(),t.id(),limit,null);
                if(first.nextCursor()!=null)
                    results.add(measureRead(t,limit,first.nextCursor(),label+"-next"));
                else Files.writeString(REPORT.resolve(label+"-next-not-applicable.txt"),
                    "No next page: all eligible rows fit the first page.\n");
            }
        }
        assertThat(results).allSatisfy(ms->assertThat(ms).as("warm service read p95 ms").isLessThanOrEqualTo(200.0));
    }

    private record Block(List<Long> commands,List<Long> reads,long lockSamples,long lockPositiveSamples){}
    private Block commandBlock(Tenant t,UUID task,Instant anchor,long[] version,int count,boolean mixed)throws Exception{
        var stop=new AtomicBoolean();var readerStarted=new CountDownLatch(1);
        List<Long> commands=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(2)){
            Future<List<Long>> reads=pool.submit(()->{
                List<Long> values=new ArrayList<>();
                if(!mixed){readerStarted.countDown();return values;}
                while(!stop.get()){
                    long start=System.nanoTime();
                    var page=radar.listMine(t.staff(),t.id(),100,null);
                    values.add(System.nanoTime()-start);
                    assertThat(page.items()).hasSize(100);
                    readerStarted.countDown();
                }
                return values;
            });
            // Same observer in baseline and mixed blocks. Database-wide sampling
            // in dedicated CI DB; cannot prove absence of short unsampled waits.
            Future<long[]> observer=pool.submit(()->{
                long samples=0,positive=0;
                while(!stop.get()){
                    long waits=jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname=current_database() AND pid<>pg_backend_pid()
                        AND wait_event_type='Lock'
                        """,Long.class);
                    samples++;if(waits>0)positive++;
                    Thread.sleep(25);
                }
                return new long[]{samples,positive};
            });
            try{
                assertThat(readerStarted.await(15,TimeUnit.SECONDS)).isTrue();
                for(int i=0;i<count;i++){
                    long v=version[0];
                    Instant due=anchor.plusSeconds(86400+(v%2==0?10:20));
                    String request="radar-c3-"+UUID.randomUUID();
                    long start=System.nanoTime();
                    var updated=tasks.reschedule(t.manager(),t.id(),task,due,v,request);
                    commands.add(System.nanoTime()-start);
                    assertThat(updated.version()).isEqualTo(v+1);
                    version[0]=updated.version();
                }
            }finally{stop.set(true);}
            var observed=observer.get(20,TimeUnit.SECONDS);
            var readSamples=reads.get(20,TimeUnit.SECONDS);
            assertThat(observed[0]).isPositive();
            if(mixed)assertThat(readSamples).isNotEmpty();
            return new Block(commands,readSamples,observed[0],observed[1]);
        }
    }
    private void mixedWorkload(Population p)throws Exception{
        var t=p.dense();var task=create(t,p.anchor().plusSeconds(86400));
        long[] version={task.version()};
        commandBlock(t,task.id(),p.anchor(),version,20,false); // warm commands
        commandBlock(t,task.id(),p.anchor(),version,20,true);  // warm mixed path
        List<Long> baseline=new ArrayList<>(),mixed=new ArrayList<>(),reader=new ArrayList<>();
        // Counterbalance first/last order; 120 measured commands per arm.
        boolean[] order={false,true,true,false,false,true};
        StringBuilder locks=new StringBuilder("block,mixed,lockSamples,positiveLockSamples,completedReads\n");
        for(int i=0;i<order.length;i++){
            boolean concurrent=order[i];
            var block=commandBlock(t,task.id(),p.anchor(),version,40,concurrent);
            String label="100000-command-block"+(i+1)+(concurrent?"-mixed":"-baseline");
            samples(label,block.commands());
            (concurrent?mixed:baseline).addAll(block.commands());reader.addAll(block.reads());
            locks.append(i+1).append(',').append(concurrent).append(',').append(block.lockSamples())
                .append(',').append(block.lockPositiveSamples()).append(',').append(block.reads().size()).append('\n');
        }
        Files.writeString(REPORT.resolve("100000-lock-sampling.csv"),locks);
        samples("100000-command-baseline",baseline);samples("100000-command-mixed",mixed);
        samples("100000-mixed-reader",reader);
        double base=percentile(baseline,.95),withRadar=percentile(mixed,.95);
        double delta=(withRadar/base-1)*100;
        Files.writeString(REPORT.resolve("100000-command-comparison.txt"),
            String.format(Locale.ROOT,"baselineP95Ms=%.3f%n mixedP95Ms=%.3f%n degradationPercent=%.3f%n targetMaxPercent=10%n",
                base,withRadar,delta));
        assertThat(tasks.getMine(t.manager(),t.id(),task.id()).version()).isEqualTo(version[0]);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?",
            Long.class,t.id(),task.id())).isEqualTo(version[0]+1);
        assertThat(percentile(reader,.95)).as("mixed read p95 ms").isLessThanOrEqualTo(200.0);
        assertThat(delta).as("mixed command p95 degradation percent (CI environment)").isLessThanOrEqualTo(10.0);
    }
    @Test void c301OneThousandTaskReadPlans()throws Exception{readWorkload(populate(1000));}
    @Test void c302TenThousandTaskReadPlans()throws Exception{readWorkload(populate(10000));}
    @Test void c303HundredThousandTaskReadPlansAndCommandRegression()throws Exception{
        var p=populate(100000);
        // Collect both evidence sets even when a read latency target is missed.
        org.junit.jupiter.api.Assertions.assertAll(()->readWorkload(p),()->mixedWorkload(p));
    }
}
