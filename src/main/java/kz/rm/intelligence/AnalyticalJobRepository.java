package kz.rm.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Storage foundation only. No automatic planning, execution, role grant or public API. */
@Repository
class AnalyticalJobRepository {
    record NightSpec(UUID tenant, UUID job, UUID generation, Instant slot, Instant scheduledAt,
                     LocalDate businessDay, long calendarVersion, String zoneId,
                     LocalDate fromDay, LocalDate untilDay, UUID expectedPrevious) {
        NightSpec {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(job);
            Objects.requireNonNull(generation);
            slot=Objects.requireNonNull(slot).truncatedTo(ChronoUnit.MICROS);
            scheduledAt=Objects.requireNonNull(scheduledAt).truncatedTo(ChronoUnit.MICROS);
            Objects.requireNonNull(businessDay);
            Objects.requireNonNull(fromDay);
            Objects.requireNonNull(untilDay);
            Objects.requireNonNull(zoneId);
            ZoneId.of(zoneId);
            if(calendarVersion<1 || zoneId.length()>64 || !fromDay.isBefore(untilDay)
                || !businessDay.equals(untilDay.minusDays(1)) || scheduledAt.isBefore(slot))
                throw new IllegalArgumentException("INT_JOB_INVALID_SCOPE");
        }
    }
    enum Outcome { INSERTED, EXISTING, CONFLICT }
    record Registration(Outcome outcome, UUID job, UUID generation) {}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    AnalyticalJobRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        this.manager=manager;
    }

    /** Caller supplies an already planned scope; eligibility and scheduler identity belong to R1.1B. */
    Registration enqueueNight(NightSpec s) {
        Objects.requireNonNull(s);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("INT_JOB_OWN_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        return Objects.requireNonNull(tx.execute(status->{
            int inserted=jdbc.update("""
                INSERT INTO crm.int_analytics_job_run
                  (tenant_id,job_id,job_type,scheduled_slot,scheduled_at,business_day,
                   calendar_version,zone_id,from_day,until_day,generation_id,expected_previous,status)
                SELECT ?,?,'NIGHT',?,?,?,?,?,?,?,?,?,'PENDING'
                WHERE EXISTS(SELECT 1 FROM crm.int_calendar_policy p
                  WHERE p.tenant_id=? AND p.version=? AND p.zone_id=?)
                ON CONFLICT DO NOTHING
                """,s.tenant(),s.job(),utc(s.slot()),utc(s.scheduledAt()),s.businessDay(),
                s.calendarVersion(),s.zoneId(),s.fromDay(),s.untilDay(),s.generation(),
                s.expectedPrevious(),s.tenant(),s.calendarVersion(),s.zoneId());
            // A fresh READ COMMITTED statement observes the winning committed insert.
            // Only adopt the same immutable scope; do not mutate another open job.
            Registration existing=jdbc.query("""
                SELECT job_id,generation_id,scheduled_at,business_day,calendar_version,zone_id,
                       from_day,until_day,expected_previous,status
                FROM crm.int_analytics_job_run
                WHERE tenant_id=? AND job_type='NIGHT' AND scheduled_slot=?
                """,rs->{
                    if(!rs.next())return new Registration(Outcome.CONFLICT,null,null);
                    boolean same=rs.getObject(3,OffsetDateTime.class).toInstant().equals(s.scheduledAt())
                        && rs.getObject(4,LocalDate.class).equals(s.businessDay())
                        && rs.getLong(5)==s.calendarVersion()
                        && rs.getString(6).equals(s.zoneId())
                        && rs.getObject(7,LocalDate.class).equals(s.fromDay())
                        && rs.getObject(8,LocalDate.class).equals(s.untilDay())
                        && Objects.equals(rs.getObject(9,UUID.class),s.expectedPrevious())
                        && !"SKIPPED".equals(rs.getString(10));
                    if(!same)return new Registration(Outcome.CONFLICT,null,null);
                    return new Registration(inserted==1?Outcome.INSERTED:Outcome.EXISTING,
                        rs.getObject(1,UUID.class),rs.getObject(2,UUID.class));
                },s.tenant(),utc(s.slot()));
            return existing;
        }));
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
