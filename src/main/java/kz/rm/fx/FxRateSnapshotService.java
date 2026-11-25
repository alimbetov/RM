package kz.rm.fx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * FX-R1A: persist MOCK rate evidence, not a pricing operation.
 * Import is serialized by a provider row lock and is all-or-nothing.
 * Synthetic evidence must NEVER be promoted into official NBRK data.
 */
@Service
public class FxRateSnapshotService {
    public record Snapshot(UUID id, String provider, String currency,
                           String quoteCurrency, BigDecimal rate, BigDecimal nominal,
                           LocalDate validDate, int sourceRevision, String sha256,
                           boolean synthetic) {}
    public record ImportRun(UUID id, String provider, LocalDate requestedDate,
                            int inserted, int replayed, int sourceCount,
                            boolean synthetic) {}

    private final JdbcTemplate jdbc;
    private final FxRateRefreshService refresh;

    public FxRateSnapshotService(JdbcTemplate jdbc, FxRateRefreshService refresh) {
        this.jdbc=jdbc;
        this.refresh=refresh;
    }

    /**
     * Controlled synthetic import. Requires an explicitly enabled dev/test
     * MOCK provider; no timers, HTTP, tenant membership or public price writes.
     */
    @Transactional
    public ImportRun importMock(LocalDate requestedDate) {
        // Fail closed before any SQL mutation; refresh is guarded by profile.
        FxRateRefreshService.RefreshResult result=refresh.simulateRefresh(requestedDate);
        if(!"MOCK".equals(result.source()) || !result.synthetic())
            throw new IllegalStateException("FX_R1A_UNQUALIFIED_SOURCE");
        String provider=jdbc.query("""
            SELECT provider_code FROM crm.fx_rate_provider_lock
            WHERE provider_code='MOCK' FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null);
        if(!"MOCK".equals(provider))
            throw new IllegalStateException("FX_R1A_PROVIDER_LOCK_MISSING");

        int inserted=0;
        for(FxRateProvider.Rate rate:result.rates()) {
            if(!"MOCK".equals(rate.provider()) || !"KZT".equals(rate.quoteCurrency())
                    || rate.validDate().isAfter(requestedDate))
                throw new IllegalStateException("FX_R1A_UNQUALIFIED_SOURCE");
            String sha=canonicalDigest(rate);
            Snapshot existing=findByIdentity(rate);
            if(existing==null) {
                jdbc.update("""
                    INSERT INTO crm.fx_official_rate_snapshot
                      (id,provider_code,base_currency,quote_currency,nominal,rate,
                       valid_date,source_revision,source_payload_sha256,synthetic,recorded_at)
                    VALUES (?,?,?,?,?,?,?,1,?,TRUE,?)
                    """,UUID.randomUUID(),provider,rate.currency(),rate.quoteCurrency(),
                        rate.nominal(),rate.rateKzt(),Date.valueOf(rate.validDate()),
                        sha,Timestamp.from(Instant.now()));
                inserted++;
            } else if(!existing.sha256().equals(sha)
                    || existing.rate().compareTo(rate.rateKzt())!=0
                    || existing.nominal().compareTo(rate.nominal())!=0) {
                // A rate correction must be a new explicit source revision,
                // never silently mutate the original snapshot.
                throw new IllegalStateException("FX_R1A_RATE_REVISION_CONFLICT");
            }
        }
        UUID id=UUID.randomUUID();
        int replayed=result.rates().size()-inserted;
        jdbc.update("""
            INSERT INTO crm.fx_rate_import_run
              (id,provider_code,requested_date,imported_count,replayed_count,
               source_count,synthetic,completed_at)
            VALUES (?,'MOCK',?,?,?,?,TRUE,?)
            """,id,Date.valueOf(requestedDate),inserted,replayed,
                result.rates().size(),Timestamp.from(Instant.now()));
        return new ImportRun(id,"MOCK",requestedDate,inserted,replayed,
                result.rates().size(),true);
    }

    @Transactional(readOnly=true)
    public List<Snapshot> snapshots(String currency) {
        if(currency==null || !currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Valid currency required");
        return jdbc.query("""
            SELECT id,provider_code,base_currency,quote_currency,rate,nominal,
                   valid_date,source_revision,source_payload_sha256,synthetic
            FROM crm.fx_official_rate_snapshot
            WHERE provider_code='MOCK' AND base_currency=?
            ORDER BY valid_date ASC,source_revision ASC
            """,(rs,n)->new Snapshot(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3).trim(),rs.getString(4).trim(),rs.getBigDecimal(5),
                rs.getBigDecimal(6),rs.getDate(7).toLocalDate(),rs.getInt(8),
                rs.getString(9).trim(),rs.getBoolean(10)),currency);
    }

    private Snapshot findByIdentity(FxRateProvider.Rate rate) {
        return jdbc.query("""
            SELECT id,provider_code,base_currency,quote_currency,rate,nominal,
                   valid_date,source_revision,source_payload_sha256,synthetic
            FROM crm.fx_official_rate_snapshot
            WHERE provider_code='MOCK' AND base_currency=? AND quote_currency=?
              AND valid_date=? AND source_revision=1
            """,rs->rs.next()?new Snapshot(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getString(3).trim(),rs.getString(4).trim(),rs.getBigDecimal(5),
                    rs.getBigDecimal(6),rs.getDate(7).toLocalDate(),rs.getInt(8),
                    rs.getString(9).trim(),rs.getBoolean(10)):null,
                rate.currency(),rate.quoteCurrency(),Date.valueOf(rate.validDate()));
    }

    static String canonicalDigest(FxRateProvider.Rate rate) {
        String canonical=String.join("|","FX_RATE_V1",rate.provider(),rate.currency(),
                rate.quoteCurrency(),rate.validDate().toString(),
                rate.nominal().stripTrailingZeros().toPlainString(),
                rate.rateKzt().stripTrailingZeros().toPlainString());
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable",e);
        }
    }
}
