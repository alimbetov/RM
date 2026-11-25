package kz.rm.tasks;

import jakarta.annotation.PostConstruct;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Internal TASK read entrypoint. No business writes, no HTTP adapter. */
@Service
public class TaskOverdueQueryService {
    public enum Severity { ATTENTION, HIGH, CRITICAL }
    public enum SignalType { TASK_OVERDUE }
    public enum ReasonCode { TASK_DUE_TIME_EXCEEDED }
    public record OverdueSignal(UUID taskId, SignalType type, TaskState state,
                                Instant dueAt, long sourceVersion, long overdueSeconds,
                                Severity severity, ReasonCode reasonCode) {}
    // Internal cursor only; not an HTTP token or an authorization grant.
    public record OverdueCursor(UUID tenantId, UUID actorId, int limit,
                                Instant lastDueAt, UUID lastTaskId) {}
    public record OverdueSignalPage(Instant evaluatedAt, List<OverdueSignal> items,
                                    OverdueCursor nextCursor) {
        public OverdueSignalPage { items=List.copyOf(items); }
    }

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final Clock clock;
    private boolean pg;
    private String firstPageSql;
    private String nextPageSql;

    public TaskOverdueQueryService(JdbcTemplate jdbc,TenantAuthorization authorization,Clock clock) {
        this.jdbc=jdbc; this.authorization=authorization; this.clock=clock;
    }

    @PostConstruct
    void initializeDialect() {
        String product=jdbc.execute((ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        if(!"PostgreSQL".equals(product)&&!"H2".equals(product))
            throw new IllegalStateException("TASK_TIMING_UNSUPPORTED_DATABASE");
        pg="PostgreSQL".equals(product);
        firstPageSql=TaskTimingEngine.overduePage(pg,false);
        nextPageSql=TaskTimingEngine.overduePage(pg,true);
    }

    @Transactional
    public OverdueSignalPage listMine(VerifiedActor actor, UUID tenant, int limit, OverdueCursor after) {
        if(actor==null||tenant==null)throw new SecurityException("TASK_VERIFIED_TENANT_REQUIRED");
        UUID user=actor.userId();
        authorization.requireForCommand(user,tenant,DomainPermission.TASK_READ);
        if(limit<1||limit>100)throw new IllegalArgumentException("TASK_RADAR_LIMIT_INVALID");
        if(after!=null && (!tenant.equals(after.tenantId())||!user.equals(after.actorId())
                ||limit!=after.limit()||after.lastTaskId()==null||after.lastDueAt()==null
                ||after.lastDueAt().getNano()%1000!=0))
            throw new IllegalArgumentException("TASK_RADAR_CURSOR_INVALID");
        Instant cutoff=clock.instant().truncatedTo(ChronoUnit.MICROS);
        // The spike qualified the finite 0001..9999 range, not DB infinity.
        requireSupported(cutoff);
        if(after!=null)requireSupported(after.lastDueAt());
        List<OverdueSignal> rows=jdbc.query(after==null?firstPageSql:nextPageSql, ps->{
            int i=1;
            ps.setObject(i++,cutoff.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setObject(i++,tenant); ps.setObject(i++,user); ps.setObject(i++,user);
            if(after!=null){
                var due=after.lastDueAt().atOffset(ZoneOffset.UTC);
                ps.setObject(i++,due,Types.TIMESTAMP_WITH_TIMEZONE);
                if(!pg)ps.setObject(i++,due,Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(i++,after.lastTaskId());
            }
            ps.setInt(i,limit+1);
        },(rs,n)->{
            Instant due=rs.getObject("due_at",OffsetDateTime.class).toInstant();
            requireSupported(due);
            BigInteger micros=rs.getBigDecimal("magnitude_micros").toBigIntegerExact();
            if(micros.signum()<=0)throw new IllegalStateException("TASK_RADAR_INVALID_MAGNITUDE");
            return new OverdueSignal(rs.getObject("id",UUID.class),SignalType.TASK_OVERDUE,
                TaskState.valueOf(rs.getString("status")),due,rs.getLong("row_version"),
                micros.divide(BigInteger.valueOf(1_000_000)).longValueExact(),
                Severity.valueOf(rs.getString("severity")),ReasonCode.TASK_DUE_TIME_EXCEEDED);
        });
        List<OverdueSignal> items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        OverdueCursor next=null;
        if(rows.size()>limit){
            var last=items.getLast();
            next=new OverdueCursor(tenant,user,limit,last.dueAt(),last.taskId());
        }
        actor.userId(); // recheck expiry before returning a potentially long query
        return new OverdueSignalPage(cutoff,items,next);
    }

    private static void requireSupported(Instant value) {
        if(value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||
                value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))
            throw new IllegalArgumentException("TASK_RADAR_TIMESTAMP_RANGE_INVALID");
    }
}
