package kz.rm.tasks;

import jakarta.annotation.PostConstruct;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** TASK-owned single-row timing projection, used by internal callers before G2. */
@Service
public class TaskTimingQueryService {
    public record Result(Instant evaluatedAt,TaskTimingSnapshot timing){}
    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    private final Clock clock;
    private String pointSql;
    public TaskTimingQueryService(JdbcTemplate jdbc,TenantAuthorization auth,Clock clock){
        this.jdbc=jdbc;this.auth=auth;this.clock=clock;
    }
    @PostConstruct void initialize(){
        String db=jdbc.execute((ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        if(!"PostgreSQL".equals(db)&&!"H2".equals(db))throw new IllegalStateException("TASK_TIMING_UNSUPPORTED_DATABASE");
        pointSql=TaskTimingEngine.fullRelation("PostgreSQL".equals(db),true);
    }
    @Transactional
    public Result getMine(VerifiedActor actor,UUID tenant,UUID task){
        if(actor==null||tenant==null)throw new SecurityException("TASK_VERIFIED_TENANT_REQUIRED");
        UUID user=actor.userId();auth.requireForCommand(user,tenant,DomainPermission.TASK_READ);
        if(task==null)throw new IllegalArgumentException("TASK_ID_REQUIRED");
        Instant cutoff=clock.instant().truncatedTo(ChronoUnit.MICROS);
        requireSupported(cutoff);
        var rows=jdbc.query(pointSql,ps->{
            ps.setObject(1,cutoff.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setObject(2,tenant);ps.setObject(3,task);ps.setObject(4,tenant);
            ps.setObject(5,user);ps.setObject(6,user);ps.setObject(7,task);
        },(rs,n)->map(rs));
        if(rows.size()!=1)throw new IllegalArgumentException("TASK_NOT_FOUND");
        actor.userId();
        return new Result(cutoff,rows.getFirst());
    }
    static Instant instant(ResultSet rs,String column)throws SQLException{
        OffsetDateTime value=rs.getObject(column,OffsetDateTime.class);
        if(value==null)return null;
        Instant t=value.toInstant();requireSupported(t);return t;
    }
    static void requireSupported(Instant t){
        if(t.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||t.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))
            throw new IllegalArgumentException("TASK_RADAR_TIMESTAMP_RANGE_INVALID");
    }
    static TaskTimingSnapshot map(ResultSet rs)throws SQLException{
        var state=TaskState.valueOf(rs.getString("status"));
        var timing=TaskTimingSnapshot.Timeliness.valueOf(rs.getString("timeliness"));
        var evidence=TaskTimingSnapshot.CompletionEvidence.valueOf(rs.getString("completion_evidence"));
        var direction=TaskTimingSnapshot.Direction.valueOf(rs.getString("direction"));
        BigDecimal magnitude=rs.getBigDecimal("magnitude_micros");
        BigInteger micros=magnitude==null?null:magnitude.toBigIntegerExact();
        if(micros!=null&&micros.signum()<0)throw new IllegalStateException("TASK_TIMING_NEGATIVE_MAGNITUDE");
        BigDecimal p=rs.getBigDecimal("p"),saved=rs.getBigDecimal("saved");
        BigDecimal percent=p!=null&&p.signum()>0&&saved!=null&&evidence==TaskTimingSnapshot.CompletionEvidence.VALID
            ?saved.multiply(BigDecimal.valueOf(100)).divide(p,2,RoundingMode.HALF_UP):null;
        Instant due=instant(rs,"due_at");
        // Reason is an explanation of the SQL class, not a second classifier.
        var reason=switch(timing){
            case OVERDUE -> TaskTimingSnapshot.Reason.TASK_DUE_TIME_EXCEEDED;
            case UNSCHEDULED -> due==null?TaskTimingSnapshot.Reason.NO_DEADLINE:TaskTimingSnapshot.Reason.NO_ASSIGNEE;
            case UNKNOWN -> switch(evidence){
                case MISSING -> TaskTimingSnapshot.Reason.COMPLETION_MISSING;
                case INVALID -> TaskTimingSnapshot.Reason.COMPLETION_INVALID;
                case AFTER_CUTOFF -> TaskTimingSnapshot.Reason.COMPLETION_AFTER_CUTOFF;
                case VALID -> due==null?TaskTimingSnapshot.Reason.NO_DEADLINE:TaskTimingSnapshot.Reason.UNSUPPORTED_SOURCE;
                default -> TaskTimingSnapshot.Reason.UNSUPPORTED_SOURCE;
            };
            default -> TaskTimingSnapshot.Reason.NONE;
        };
        String severity=rs.getString("severity");
        return new TaskTimingSnapshot(rs.getObject("id",UUID.class),state,rs.getLong("row_version"),
            instant(rs,"created_at"),due,instant(rs,"completed_at"),evidence,instant(rs,"original_due_at"),
            TaskTimingSnapshot.OriginalEvidence.valueOf(rs.getString("original_evidence")),
            TaskTimingSnapshot.OriginalOutcome.valueOf(rs.getString("original_outcome")),
            rs.getObject("deadline_changed",Boolean.class),timing,reason,direction,micros,percent,
            severity==null?null:TaskOverdueQueryService.Severity.valueOf(severity),TaskTimingEngine.POLICY_VERSION);
    }
}
