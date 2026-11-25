package kz.rm.tasks;

import jakarta.annotation.PostConstruct;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Internal calendar/list read model. All membership predicates precede LIMIT.
 * No HTTP token, UI state, mutable task operation or separate timing policy.
 */
@Service
public class TaskCalendarQueryService {
    public enum Perspective { ALL_VISIBLE,MINE_CREATED,MINE_ASSIGNED }
    public enum DateBasis { DUE,COMPLETED }
    public record Filter(Perspective perspective,Set<TaskState> states,
            Set<TaskTimingSnapshot.Timeliness> timeliness,DateBasis dateBasis,
            LocalDate dateFrom,LocalDate dateTo,String zone,boolean undated) {
        public Filter {
            states=copy(states);timeliness=copy(timeliness);
        }
    }
    public record AppliedFilter(Perspective perspective,Set<TaskState> states,
            Set<TaskTimingSnapshot.Timeliness> timeliness,DateBasis dateBasis,
            LocalDate dateFrom,LocalDate dateTo,String zone,boolean undated,
            Instant fromInclusive,Instant toExclusive) {
        public AppliedFilter {states=copy(states);timeliness=copy(timeliness);}
    }
    /** Internal cursor, not an authorization grant or transport format. */
    public record Cursor(UUID tenantId,UUID actorId,AppliedFilter filter,int limit,
                         Instant lastDate,UUID lastTaskId) {}
    public record Item(TaskTimingSnapshot timing,TaskTitlePolicy.TitleCode titleCode,Instant selectedDate) {}
    public record Page(Instant evaluatedAt,AppliedFilter appliedFilter,List<Item> items,
                       boolean hasMore,Cursor nextCursor) {
        public Page {items=List.copyOf(items);}
    }

    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    private final Clock clock;
    private boolean pg;
    public TaskCalendarQueryService(JdbcTemplate jdbc,TenantAuthorization auth,Clock clock){
        this.jdbc=jdbc;this.auth=auth;this.clock=clock;
    }
    @PostConstruct void initialize(){
        String db=jdbc.execute((ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        if(!"PostgreSQL".equals(db)&&!"H2".equals(db))throw new IllegalStateException("TASK_TIMING_UNSUPPORTED_DATABASE");
        pg="PostgreSQL".equals(db);
    }
    private static <E extends Enum<E>> Set<E> copy(Set<E> in){
        if(in==null)return null;
        if(in.isEmpty()||in.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("TASK_CALENDAR_EMPTY_OR_NULL_SELECTION");
        return Set.copyOf(in);
    }
    private static AppliedFilter normalize(Filter f,Instant cutoff){
        if(f==null)f=new Filter(null,null,null,null,null,null,null,false);
        Perspective perspective=f.perspective()==null?Perspective.ALL_VISIBLE:f.perspective();
        DateBasis basis=f.dateBasis()==null?DateBasis.DUE:f.dateBasis();
        String zone=f.zone()==null?"UTC":f.zone();
        if(!ZoneId.getAvailableZoneIds().contains(zone))
            throw new IllegalArgumentException("TASK_CALENDAR_ZONE_INVALID");
        ZoneId z=ZoneId.of(zone);
        Set<TaskState> states=f.states()==null?EnumSet.complementOf(EnumSet.of(TaskState.ARCHIVED)):f.states();
        LocalDate from=f.dateFrom(),to=f.dateTo();
        if(f.undated()){
            if(basis!=DateBasis.DUE||from!=null||to!=null)
                throw new IllegalArgumentException("TASK_CALENDAR_UNDATED_INVALID");
            return new AppliedFilter(perspective,states,f.timeliness(),basis,null,null,zone,true,null,null);
        }
        if(from==null&&to==null){
            from=cutoff.atZone(z).toLocalDate().withDayOfMonth(1);to=from.plusMonths(1);
        }
        if(from==null||to==null||!from.isBefore(to)||ChronoUnit.DAYS.between(from,to)>93)
            throw new IllegalArgumentException("TASK_CALENDAR_RANGE_INVALID");
        if(from.getYear()<1||to.getYear()>9999)
            throw new IllegalArgumentException("TASK_CALENDAR_RANGE_INVALID");
        Instant start=from.atStartOfDay(z).toInstant(),end=to.atStartOfDay(z).toInstant();
        TaskTimingQueryService.requireSupported(start);TaskTimingQueryService.requireSupported(end);
        // Entirely skipped local dates (e.g. a historic zone date-line jump)
        // may map to an empty instant interval. Return an empty dated page,
        // rather than moving tasks into an invented date.
        if(end.isBefore(start))throw new IllegalArgumentException("TASK_CALENDAR_RANGE_INVALID");
        return new AppliedFilter(perspective,states,f.timeliness(),basis,from,to,zone,false,start,end);
    }
    private static void verifyCursor(Cursor c,UUID user,UUID tenant,AppliedFilter f,int limit){
        if(c==null)return;
        if(!tenant.equals(c.tenantId())||!user.equals(c.actorId())||!f.equals(c.filter())||
            limit!=c.limit()||c.lastTaskId()==null)
            throw new IllegalArgumentException("TASK_CALENDAR_CURSOR_INVALID");
        if(f.undated()){
            if(c.lastDate()!=null)throw new IllegalArgumentException("TASK_CALENDAR_CURSOR_INVALID");
        }else{
            Instant d=c.lastDate();
            if(d==null||d.getNano()%1000!=0||d.isBefore(f.fromInclusive())||!d.isBefore(f.toExclusive()))
                throw new IllegalArgumentException("TASK_CALENDAR_CURSOR_INVALID");
            TaskTimingQueryService.requireSupported(d);
        }
    }
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static void timestamp(PreparedStatement ps,int index,Instant instant)throws SQLException{
        ps.setObject(index,instant.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
    }
    @Transactional
    public Page listMine(VerifiedActor actor,UUID tenant,Filter filter,int limit,Cursor after){
        if(actor==null||tenant==null)throw new SecurityException("TASK_VERIFIED_TENANT_REQUIRED");
        UUID user=actor.userId();auth.requireTaskRead(user,tenant);
        if(limit<1||limit>100)throw new IllegalArgumentException("TASK_CALENDAR_LIMIT_INVALID");
        Instant cutoff=clock.instant().truncatedTo(ChronoUnit.MICROS);
        TaskTimingQueryService.requireSupported(cutoff);
        AppliedFilter f=normalize(filter,cutoff);
        verifyCursor(after,user,tenant,f,limit);
        QueryPlan query=prepareQuery(pg,f,cutoff,tenant,user,limit,after);
        List<Item> rows=jdbc.query(query.sql(),query.bind(),(rs,n)->new Item(
            TaskTimingQueryService.map(rs),TaskTitlePolicy.calendarCode(rs.getString("title")),
            TaskTimingQueryService.instant(rs,"selected_date")));
        boolean more=rows.size()>limit;
        List<Item> items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        Cursor next=null;
        if(more){
            Item last=items.getLast();next=new Cursor(tenant,user,f,limit,last.selectedDate(),last.timing().taskId());
        }
        actor.userId(); // fail closed if access token expired during query
        return new Page(cutoff,f,items,more,next);
    }
    // Package-private query specification so qualification can EXPLAIN the exact
    // production statement and binds without exposing a public SQL API.
    record QueryPlan(String sql,org.springframework.jdbc.core.PreparedStatementSetter bind){}
    static QueryPlan prepareQuery(boolean pg,AppliedFilter f,Instant cutoff,UUID tenant,
                                  UUID user,int limit,Cursor after){
        return prepareQuery(pg,pg,f,cutoff,tenant,user,limit,after);
    }
    static QueryPlan prepareQuery(boolean pg,boolean optimize,AppliedFilter f,Instant cutoff,UUID tenant,
                                  UUID user,int limit,Cursor after){
        if(optimize&&!pg)throw new IllegalArgumentException("PG_OPTIMIZATION_ONLY");
        String date=f.dateBasis()==DateBasis.DUE?"due_at":"completed_at";
        // Only closed enum-selected SQL fragments. All values bound.
        var states=f.states().stream().sorted().toList();
        var timing=f.timeliness()==null?List.<TaskTimingSnapshot.Timeliness>of():f.timeliness().stream().sorted().toList();
        // DUE without a timing predicate needs no history for membership.
        boolean simple=optimize&&f.dateBasis()==DateBasis.DUE&&timing.isEmpty();
        String relation=optimize?(simple?
            "SELECT t.id,t.status,t.due_at,t.created_by,t.assignee_user_id FROM crm.task_item t WHERE tenant_id=? AND (assignee_user_id=? OR created_by=?)":
            TaskTimingEngine.calendarMembershipRelation()):TaskTimingEngine.fullRelation(pg,false);
        StringBuilder sql=new StringBuilder("SELECT "+(optimize?"cal.id":"cal.*")+", "+date+
            " AS selected_date FROM ("+relation+") cal WHERE status IN ("+marks(states.size())+")");
        if(f.perspective()==Perspective.MINE_CREATED)sql.append(" AND created_by=?");
        else if(f.perspective()==Perspective.MINE_ASSIGNED)sql.append(" AND assignee_user_id=?");
        if(!timing.isEmpty())sql.append(" AND timeliness IN ("+marks(timing.size())+")");
        if(f.undated()){
            sql.append(" AND due_at IS NULL");
            if(after!=null)sql.append(" AND id>?");
            sql.append(" ORDER BY id ASC");
        }else{
            sql.append(" AND "+date+">=? AND "+date+"<?");
            if(after!=null)sql.append(" AND ("+date+">? OR ("+date+"=? AND id>?))");
            sql.append(" ORDER BY "+date+" ASC,id ASC");
        }
        sql.append(" LIMIT ?");
        // Materialize fully filtered keys, then summarize their immutable
        // event history once. A separate optimization fence prevents a
        // parameterized nested loop from re-executing the aggregate for each
        // task. All CTEs share the same PostgreSQL statement/MVCC snapshot.
        String statement=optimize?
            "SELECT result.* FROM (WITH page_keys AS MATERIALIZED ("+sql+"), page_history AS MATERIALIZED ("+
                TaskTimingEngine.pageEventSummary()+"), enriched AS MATERIALIZED ("+
                TaskTimingEngine.fullRelation(true,false,true)+") "+
                "SELECT enriched.*,k.selected_date FROM page_keys k JOIN enriched ON enriched.id=k.id) result "+
                (f.undated()?"ORDER BY id ASC":"ORDER BY selected_date ASC,id ASC"):
            sql.toString();
        return new QueryPlan(statement,ps->{
            int i=1;
            if(!simple){timestamp(ps,i++,cutoff);ps.setObject(i++,tenant);}
            ps.setObject(i++,tenant);ps.setObject(i++,user);ps.setObject(i++,user);
            for(var state:states)ps.setString(i++,state.name());
            if(f.perspective()!=Perspective.ALL_VISIBLE)ps.setObject(i++,user);
            for(var t:timing)ps.setString(i++,t.name());
            if(f.undated()){
                if(after!=null)ps.setObject(i++,after.lastTaskId());
            }else{
                timestamp(ps,i++,f.fromInclusive());timestamp(ps,i++,f.toExclusive());
                if(after!=null){
                    timestamp(ps,i++,after.lastDate());timestamp(ps,i++,after.lastDate());
                    ps.setObject(i++,after.lastTaskId());
                }
            }
            ps.setInt(i++,limit+1);
            if(optimize){
                ps.setObject(i++,tenant); // page_history event tenant
                timestamp(ps,i++,cutoff);ps.setObject(i++,tenant); // enriched task tenant
                ps.setObject(i++,user);ps.setObject(i,user);
            }
        });
    }
}
