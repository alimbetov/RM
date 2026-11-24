package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.tasks.TaskCalendarQueryService.*;
import static kz.rm.tasks.TaskTimingSnapshot.Timeliness.*;

@SpringBootTest
@Import({TaskTimingCompletionQualificationTest.ClockConfig.class,TaskRadarSqlFailureQualificationTest.ProbeConfig.class})
class TaskCalendarPgPerformanceQualificationTest {
    private static final Instant BASE=Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant CUT=BASE.plusSeconds(10*86400);
    private static final Path REPORT=Path.of("target/radar-calendar-performance");
    @Autowired TaskTimingCompletionQualificationTest.MutableClock clock;
    @Autowired TaskService tasks;
    @Autowired TaskCalendarQueryService calendar;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
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




    @BeforeEach void dedicatedPg()throws Exception{
        Assumptions.assumeTrue(Boolean.getBoolean("rm.radar.f2.performance"),"dedicated calendar workload");
        clock.at(CUT);
        assertThat(dataSource).isInstanceOf(RadarSqlProbe.Wrapped.class);
        try(var c=dataSource.getConnection()){
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        Files.createDirectories(REPORT);
        Files.writeString(REPORT.resolve("environment.txt"),
            "postgres="+jdbc.queryForObject("SELECT version()",String.class)+"\n"+
            "shared_buffers="+jdbc.queryForObject("SHOW shared_buffers",String.class)+"\n"+
            "work_mem="+jdbc.queryForObject("SHOW work_mem",String.class)+"\n"+
            "processors="+Runtime.getRuntime().availableProcessors()+"\n"+
            "java="+System.getProperty("java.version")+"\n"+
            "warmup=10; samplesPerReadVariant=50; synthetic canonical event chains; warm CI only\n");
    }
    private record Population(Tenant dense,Tenant sparse,int total){}
    private void seed(Tenant t,int count,boolean dense){
        // Stable synthetic histories, not business records. Every visible completed
        // task has CREATED, STARTED, DUE_DATE_CHANGED, COMPLETED; archived adds ARCHIVED.
        // Some tasks are hidden, some have no due, some are legacy missing history.
        int n=jdbc.update("""
            WITH p AS (SELECT CAST(? AS uuid) tenant,CAST(? AS uuid) staff,
                CAST(? AS uuid) manager,CAST(? AS timestamptz) base,CAST(? AS integer) n)
            INSERT INTO crm.task_item(tenant_id,id,assignee_user_id,created_by,status,due_at,created_at,row_version,title)
            SELECT tenant,md5(tenant::text||':'||g::text)::uuid,
                CASE WHEN g%17=0 THEN manager ELSE staff END,manager,
                CASE WHEN g%19=0 THEN 'ARCHIVED' WHEN g%5=0 THEN 'COMPLETED'
                     WHEN g%7=0 THEN 'IN_PROGRESS' ELSE 'OPEN' END,
                CASE WHEN g%23=0 AND g%19<>0 AND g%5<>0 THEN NULL
                     ELSE base+INTERVAL '2 days'+(g%3600)*INTERVAL '1 second' END,
                base,CASE WHEN g%19=0 THEN 4 WHEN g%5=0 THEN 3 WHEN g%7=0 THEN 1 ELSE 0 END,'Review order'
            FROM p CROSS JOIN LATERAL generate_series(1,p.n) AS g
            """,t.id(),t.staff().userId(),t.manager().userId(),java.sql.Timestamp.from(BASE),count);
        assertThat(n).isEqualTo(count);
        jdbc.update("""
            WITH p AS (SELECT CAST(? AS uuid) tenant,CAST(? AS timestamptz) base,CAST(? AS integer) n,CAST(? AS boolean) dense),
            rows AS (SELECT t.*,g,p.base,p.dense FROM p CROSS JOIN LATERAL generate_series(1,p.n) AS g
                     JOIN crm.task_item t ON t.tenant_id=p.tenant AND t.id=md5(p.tenant::text||':'||g::text)::uuid)
            INSERT INTO crm.task_event(tenant_id,id,task_id,actor_user_id,action_code,from_state,to_state,
                previous_version,new_version,occurred_at,previous_due_at,next_due_at)
            SELECT r.tenant_id,md5(r.id::text||':event:'||v::text)::uuid,r.id,r.created_by,
                CASE v WHEN 0 THEN 'CREATED' WHEN 1 THEN 'STARTED' WHEN 2 THEN 'DUE_DATE_CHANGED'
                       WHEN 3 THEN 'COMPLETED' ELSE 'ARCHIVED' END,
                CASE v WHEN 0 THEN NULL WHEN 1 THEN 'OPEN' WHEN 4 THEN 'COMPLETED' ELSE 'IN_PROGRESS' END,
                CASE v WHEN 0 THEN 'OPEN' WHEN 3 THEN 'COMPLETED' WHEN 4 THEN 'ARCHIVED' ELSE 'IN_PROGRESS' END,
                CASE WHEN v=0 THEN NULL ELSE v-1 END,v,
                CASE v WHEN 0 THEN r.base WHEN 1 THEN r.base+INTERVAL '1 hour'
                       WHEN 2 THEN r.base+INTERVAL '2 hours'
                       WHEN 3 THEN r.base+CASE WHEN (r.dense AND r.g%3<>0) OR (NOT r.dense AND r.g%101=1)
                                              THEN INTERVAL '12 hours' ELSE INTERVAL '3 days' END
                       ELSE r.base+INTERVAL '4 days' END,
                CASE WHEN v=2 THEN r.due_at-INTERVAL '1 day' ELSE NULL END,
                CASE WHEN v=2 THEN r.due_at ELSE NULL END
            FROM rows r CROSS JOIN LATERAL generate_series(0,r.row_version) AS v
            WHERE r.g%29<>0
            """,t.id(),java.sql.Timestamp.from(BASE),count,dense);
    }
    private Population populate(int n)throws Exception{
        var dense=tenant();var sparse=tenant();seed(dense,n/2,true);seed(sparse,n-n/2,false);
        jdbc.execute("ANALYZE crm.task_item");jdbc.execute("ANALYZE crm.task_event");
        long rows=jdbc.queryForObject("SELECT count(*) FROM crm.task_item WHERE tenant_id IN (?,?)",Long.class,dense.id(),sparse.id());
        long events=jdbc.queryForObject("SELECT count(*) FROM crm.task_event WHERE tenant_id IN (?,?)",Long.class,dense.id(),sparse.id());
        assertThat(rows).isEqualTo(n);assertThat(events).isGreaterThan(n);
        Files.writeString(REPORT.resolve("fixture-"+n+".txt"),"tasks="+rows+"; events="+events+"; wholeTableTasks="+
            jdbc.queryForObject("SELECT count(*) FROM crm.task_item",Long.class)+"; wholeTableEvents="+
            jdbc.queryForObject("SELECT count(*) FROM crm.task_event",Long.class)+"\n");
        return new Population(dense,sparse,n);
    }
    private Filter filter(boolean completed){
        return new Filter(Perspective.MINE_ASSIGNED,completed?Set.of(TaskState.COMPLETED,TaskState.ARCHIVED):null,
            completed?Set.of(EARLY_50):null,completed?DateBasis.COMPLETED:DateBasis.DUE,
            LocalDate.of(2026,11,1),LocalDate.of(2026,12,1),"UTC",false);
    }
    private Page page(Tenant t,Filter f,int limit,Cursor c){return calendar.listMine(t.staff(),t.id(),f,limit,c);}
    private record Expected(UUID id,Instant date,long version,String state,Instant original){}
    private List<Expected> oracle(Tenant t,boolean completed,int limit,Cursor cursor){
        // Independent simpler query, for the known canonical fixture distribution.
        String date=completed?"e.occurred_at":"t.due_at";
        String sql="SELECT t.id,"+date+",t.row_version,t.status,"+
            "CASE WHEN d.new_version IS NOT NULL AND c.new_version IS NOT NULL THEN d.previous_due_at "+
            "WHEN c.new_version IS NOT NULL AND t.row_version<2 THEN t.due_at ELSE NULL END "+
            "FROM crm.task_item t LEFT JOIN crm.task_event c ON c.tenant_id=t.tenant_id AND c.task_id=t.id AND c.new_version=0 "+
            "LEFT JOIN crm.task_event d ON d.tenant_id=t.tenant_id AND d.task_id=t.id AND d.action_code='DUE_DATE_CHANGED' "+
            "LEFT JOIN crm.task_event e ON e.tenant_id=t.tenant_id AND e.task_id=t.id AND e.action_code='COMPLETED' "+
            "WHERE t.tenant_id=? AND t.assignee_user_id=? "+
            (completed?"AND t.status IN ('COMPLETED','ARCHIVED') AND e.occurred_at<=t.created_at+(t.due_at-t.created_at)/2 ":"AND t.status<>'ARCHIVED' ")+
            "AND "+date+">=? AND "+date+"<? "+
            (cursor==null?"":"AND ("+date+">? OR ("+date+"=? AND t.id>?)) ")+" ORDER BY "+date+",t.id LIMIT ?";
        List<Object> args=new ArrayList<>(List.of(t.id(),t.staff().userId(),java.sql.Timestamp.from(BASE),java.sql.Timestamp.from(BASE.plusSeconds(30*86400))));
        if(cursor!=null){args.add(java.sql.Timestamp.from(cursor.lastDate()));args.add(java.sql.Timestamp.from(cursor.lastDate()));args.add(cursor.lastTaskId());}
        args.add(limit+1);
        return jdbc.query(sql,(rs,n)->new Expected(rs.getObject(1,UUID.class),rs.getTimestamp(2).toInstant(),rs.getLong(3),rs.getString(4),
            rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()),args.toArray());
    }
    private void compare(Tenant t,boolean completed,int limit,Cursor cursor,Page p){
        var expected=oracle(t,completed,limit,cursor);
        assertThat(p.items().stream().map(i->new Expected(i.timing().taskId(),i.selectedDate(),i.timing().sourceVersion(),
            i.timing().state().name(),i.timing().originalDueAt())).toList())
            .containsExactlyElementsOf(expected.subList(0,Math.min(limit,expected.size())));
        assertThat(p.hasMore()).isEqualTo(expected.size()>limit);
        assertThat(p.nextCursor()!=null).isEqualTo(p.hasMore());
    }
    private void explain(Tenant t,Page p,int limit,Cursor cursor,String label)throws Exception{
        var q=TaskCalendarQueryService.prepareQuery(TaskTimingEngine.fullRelation(true,false),p.appliedFilter(),
            p.evaluatedAt(),t.id(),t.staff().userId(),limit,cursor);
        String plan=jdbc.query("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+q.sql(),q.bind(),
            rs->{if(!rs.next())throw new IllegalStateException("no plan");return rs.getString(1);});
        assertThat(plan).contains("Execution Time","Shared Hit Blocks");
        Files.writeString(REPORT.resolve(label+"-plan.json"),plan);
    }
    private static double p(List<Long> values,double q){
        assertThat(values).isNotEmpty();var sorted=new ArrayList<>(values);Collections.sort(sorted);
        return sorted.get((int)Math.ceil(q*sorted.size())-1)/1_000_000.0;
    }
    private void save(String label,List<Long> values)throws Exception{
        StringBuilder csv=new StringBuilder("sample,nanos\n");for(int i=0;i<values.size();i++)csv.append(i+1).append(',').append(values.get(i)).append('\n');
        Files.writeString(REPORT.resolve(label+"-samples.csv"),csv);
        String line=String.format(Locale.ROOT,"%s,n=%d,p50Ms=%.3f,p95Ms=%.3f,maxMs=%.3f%n",label,values.size(),p(values,.5),p(values,.95),p(values,1));
        Files.writeString(REPORT.resolve("measurements.txt"),line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        System.out.print("RADAR_F2 "+line);
    }
    private double measure(Tenant t,boolean completed,int limit,Cursor cursor,String label)throws Exception{
        var f=filter(completed);Page first;
        try(var trace=RadarSqlProbe.capture(false)){
            first=page(t,f,limit,cursor);
            assertThat(trace.businessStatements()).isEqualTo(1);
            assertThat(trace.taskSelects()).isEqualTo(1);
            assertThat(trace.entries()).allSatisfy(e->{assertThat(e.select()).isTrue();assertThat(e.batch()).isFalse();});
        }
        compare(t,completed,limit,cursor,first);
        for(int i=0;i<10;i++)page(t,f,limit,cursor);
        List<Long> values=new ArrayList<>();
        for(int i=0;i<50;i++){
            long start=System.nanoTime();var observed=page(t,f,limit,cursor);values.add(System.nanoTime()-start);
            assertThat(observed.items()).isEqualTo(first.items());
        }
        save(label,values);explain(t,first,limit,cursor,label);
        return p(values,.95);
    }
    private void workload(Population population)throws Exception{
        List<Double> metrics=new ArrayList<>();
        for(var t:List.of(population.dense(),population.sparse()))for(boolean completed:new boolean[]{false,true})for(int limit:new int[]{20,100}){
            String label=population.total()+"-"+(t==population.dense()?"dense":"sparse")+"-"+(completed?"completed-early":"due")+"-limit"+limit;
            metrics.add(measure(t,completed,limit,null,label+"-first"));
            var first=page(t,filter(completed),limit,null);
            if(first.nextCursor()!=null)metrics.add(measure(t,completed,limit,first.nextCursor(),label+"-next"));
            else Files.writeString(REPORT.resolve(label+"-next-not-applicable.txt"),"No additional eligible rows.\n");
        }
        assertThat(metrics).allSatisfy(ms->assertThat(ms).as("calendar warm p95 ms").isLessThanOrEqualTo(200.0));
    }
    private record Block(List<Long> commands,List<Long> reads,long lockSamples,long lockPositive){}
    private Block commands(Tenant t,UUID task,long[] version,int count,boolean mixed)throws Exception{
        AtomicBoolean stop=new AtomicBoolean();CountDownLatch started=new CountDownLatch(1);
        List<Long> times=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(2)){
            var reader=pool.submit(()->{
                List<Long> reads=new ArrayList<>();
                if(!mixed){started.countDown();return reads;}
                while(!stop.get()){
                    long start=System.nanoTime();var p=page(t,filter(true),100,null);reads.add(System.nanoTime()-start);
                    assertThat(p.items()).hasSize(100);started.countDown();
                }
                return reads;
            });
            var observer=pool.submit(()->{
                long samples=0,positive=0;
                while(!stop.get()){
                    long waits=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock'",Long.class);
                    samples++;if(waits>0)positive++;Thread.sleep(25);
                }
                return new long[]{samples,positive};
            });
            try{
                assertThat(started.await(30,TimeUnit.SECONDS)).isTrue();
                for(int i=0;i<count;i++){
                    long v=version[0];Instant due=BASE.plusSeconds(40*86400+(v%2==0?10:20));
                    long start=System.nanoTime();var next=tasks.reschedule(t.manager(),t.id(),task,due,v,"f2-mix-"+UUID.randomUUID());
                    times.add(System.nanoTime()-start);assertThat(next.version()).isEqualTo(v+1);version[0]=next.version();
                }
            }finally{stop.set(true);}
            var locks=observer.get(30,TimeUnit.SECONDS);var reads=reader.get(30,TimeUnit.SECONDS);
            assertThat(locks[0]).isPositive();if(mixed)assertThat(reads).isNotEmpty();
            return new Block(times,reads,locks[0],locks[1]);
        }
    }
    private void mixed(Population population)throws Exception{
        var t=population.dense();var task=create(t,BASE.plusSeconds(40*86400));long[] v={task.version()};
        commands(t,task.id(),v,20,false);commands(t,task.id(),v,20,true);
        List<Long> base=new ArrayList<>(),mixed=new ArrayList<>(),reads=new ArrayList<>();
        boolean[] order={false,true,true,false,false,true};
        StringBuilder locks=new StringBuilder("block,mixed,samples,positive,reads\n");
        for(int i=0;i<order.length;i++){
            var b=commands(t,task.id(),v,40,order[i]);(order[i]?mixed:base).addAll(b.commands());reads.addAll(b.reads());
            save("100000-command-block"+(i+1),b.commands());
            locks.append(i+1).append(',').append(order[i]).append(',').append(b.lockSamples()).append(',').append(b.lockPositive()).append(',').append(b.reads().size()).append('\n');
        }
        save("100000-command-baseline",base);save("100000-command-mixed",mixed);save("100000-mixed-reader",reads);
        double delta=(p(mixed,.95)/p(base,.95)-1)*100;
        Files.writeString(REPORT.resolve("100000-command-comparison.txt"),String.format(Locale.ROOT,
            "baselineP95Ms=%.3f%n mixedP95Ms=%.3f%n degradationPercent=%.3f%n",p(base,.95),p(mixed,.95),delta));
        Files.writeString(REPORT.resolve("100000-lock-sampling.csv"),locks);
        assertThat(tasks.getMine(t.manager(),t.id(),task.id()).version()).isEqualTo(v[0]);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?",Long.class,t.id(),task.id())).isEqualTo(v[0]+1);
        assertThat(p(reads,.95)).as("calendar mixed p95").isLessThanOrEqualTo(200);
        assertThat(delta).as("command degradation p95 percent").isLessThanOrEqualTo(10);
    }
    @Test void f211OneThousandTasksWithHistory()throws Exception{workload(populate(1000));}
    @Test void f212TenThousandTasksWithHistory()throws Exception{workload(populate(10000));}
    @Test void f213HundredThousandTasksWithHistoryAndMixedCommands()throws Exception{
        var population=populate(100000);
        org.junit.jupiter.api.Assertions.assertAll(()->workload(population),()->mixed(population));
    }
}
