package kz.rm.fx;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source-byte provenance and fail-closed, per-instrument admission.
 * Date classifications in stageSource are reviewed assertions supplied by the
 * trusted ingestion boundary; a SHA of bytes alone NEVER proves their correctness.
 * No public endpoint or remote fetch is provided by this service.
 */
@Service
public class FxCalendarEvidenceService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final Clock clock;

    public FxCalendarEvidenceService(JdbcTemplate jdbc,
                                     TenantAuthorization authorization, Clock clock) {
        this.jdbc=jdbc;
        this.authorization=authorization;
        this.clock=clock;
    }

    private static URI trustedSource(String source) {
        try {
            URI uri=URI.create(source);
            if(!"https".equalsIgnoreCase(uri.getScheme())
                    ||uri.getUserInfo()!=null ||uri.getPort()!=-1
                    ||uri.getFragment()!=null ||uri.getQuery()!=null
                    ||uri.getHost()==null ||!("kase.kz".equalsIgnoreCase(uri.getHost())
                    ||"nationalbank.kz".equalsIgnoreCase(uri.getHost())))
                throw new IllegalArgumentException("CUR_R2B_CALENDAR_SOURCE_UNTRUSTED");
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("CUR_R2B_CALENDAR_SOURCE_UNTRUSTED",invalid);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * Staging only: the caller must obtain the bytes directly through a
     * pinned, audited HTTPS transport and independently review instrument dates.
     */
    @Transactional
    public UUID stageSource(String sourceUrl, byte[] rawBytes, String expectedSha,
                            List<NbrkTradingCalendarPolicy.Day> reviewedDays) {
        trustedSource(sourceUrl);
        if(rawBytes==null||rawBytes.length<1||rawBytes.length>1_048_576
                ||expectedSha==null||!expectedSha.matches("[0-9a-f]{64}")
                ||reviewedDays==null||reviewedDays.isEmpty()||reviewedDays.size()>366)
            throw new IllegalArgumentException("CUR_R2B_CALENDAR_IMPORT_INVALID");
        if(!expectedSha.equals(digest(rawBytes)))
            throw new IllegalStateException("CUR_R2B_CALENDAR_SOURCE_HASH_MISMATCH");
        for(var day:reviewedDays) {
            if(day==null ||!sourceUrl.equals(day.sourceUrl())
                    ||!expectedSha.equals(day.sha256()))
                throw new IllegalArgumentException("CUR_R2B_CALENDAR_DAY_PROVENANCE_MISMATCH");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.fx_calendar_source_evidence
              (id,source_url,raw_sha256,source_byte_count,verified_at,verification_method)
            VALUES (?,?,?,?,?,'SOURCE_SHA_VERIFIED')
            """,id,sourceUrl,expectedSha,rawBytes.length,Timestamp.from(clock.instant()));
        for(var day:reviewedDays) {
            int count=jdbc.update("""
                INSERT INTO crm.fx_calendar_day_evidence
                  (source_id,instrument,trading_date,trading_status)
                VALUES (?,?,?,?)
                """,id,day.instrument(),Date.valueOf(day.date()),day.status().name());
            if(count!=1)throw new IllegalStateException("CUR_R2B_CALENDAR_DAY_NOT_RECORDED");
        }
        return id;
    }

    /** Explicit UAT admission. Never auto-enables a tenant. */
    @Transactional
    public void enableForTenant(VerifiedActor actor, UUID tenant, UUID sourceId,
                                int maxTradingDays) {
        if(actor==null||tenant==null||sourceId==null||maxTradingDays<0||maxTradingDays>10)
            throw new IllegalArgumentException("CUR_R2B_CALENDAR_ADMISSION_INVALID");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.STOREFRONT_OFFER_MANAGE);
        UUID locked=jdbc.query("""
            SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenant);
        if(locked==null)throw new IllegalStateException("CUR_R2B_STOREFRONT_MISSING");
        Integer exists=jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_calendar_source_evidence WHERE id=?
            """,Integer.class,sourceId);
        if(exists==null||exists!=1)throw new IllegalStateException("CUR_R2B_CALENDAR_SOURCE_MISSING");
        Integer existing=jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_calendar_tenant_gate WHERE tenant_id=?
            """,Integer.class,tenant);
        if(existing!=null&&existing>0) {
            jdbc.update("""
                UPDATE crm.fx_calendar_tenant_gate
                SET enabled=TRUE,max_trading_days=?,source_id=?,approved_by=?,approved_at=?
                WHERE tenant_id=?
                """,maxTradingDays,sourceId,actor.userId(),Timestamp.from(clock.instant()),tenant);
        } else {
            jdbc.update("""
                INSERT INTO crm.fx_calendar_tenant_gate
                  (tenant_id,enabled,max_trading_days,source_id,approved_by,approved_at)
                VALUES (?,TRUE,?,?,?,?)
                """,tenant,maxTradingDays,sourceId,actor.userId(),Timestamp.from(clock.instant()));
        }
    }

    /** Run inside the same storefront-serialized approval transaction. */
    @Transactional(readOnly=true)
    public void requireAdmittedCalendar(UUID tenant, String baseCurrency,
                                        LocalDate validDate, LocalDate evaluationDate) {
        record Gate(UUID sourceId,int limit) {}
        Gate gate=jdbc.query("""
            SELECT source_id,max_trading_days FROM crm.fx_calendar_tenant_gate
            WHERE tenant_id=? AND enabled=TRUE
            """,rs->rs.next()?new Gate(rs.getObject(1,UUID.class),rs.getInt(2)):null,tenant);
        if(gate==null)return; // Existing tenants remain under their R2B calendar-day guardrail.
        String instrument=baseCurrency+"/KZT";
        Map<LocalDate,NbrkTradingCalendarPolicy.Day> days=new HashMap<>();
        jdbc.query("""
            SELECT d.trading_date,d.trading_status,s.source_url,s.raw_sha256
            FROM crm.fx_calendar_day_evidence d
            JOIN crm.fx_calendar_source_evidence s ON s.id=d.source_id
            WHERE d.source_id=? AND d.instrument=? AND d.trading_date>?
              AND d.trading_date<=?
            """,rs->{
                LocalDate date=rs.getDate(1).toLocalDate();
                days.put(date,new NbrkTradingCalendarPolicy.Day(date,instrument,
                    NbrkTradingCalendarPolicy.TradingStatus.valueOf(rs.getString(2)),
                    rs.getString(3),rs.getString(4).trim()));
            },gate.sourceId(),instrument,Date.valueOf(validDate),Date.valueOf(evaluationDate));
        var assessment=NbrkTradingCalendarPolicy.assess(validDate,evaluationDate,instrument,days);
        if(assessment.observedTradingDays()>gate.limit())
            throw new IllegalStateException("CUR_R2B_TRADING_DAY_AGE_EXCEEDED");
    }
}
