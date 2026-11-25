package kz.rm.fx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Official source evidence reader only. Never used as an implicit quote or
 * order repricing operation; callers must explicitly select NBRK policy.
 */
@Service
public class NbrkRateSnapshotReader {
    public record Revision(UUID snapshotId, UUID importRunId,
                           String currency, BigDecimal nominal, BigDecimal rateKzt,
                           LocalDate validDate, int revision, String semanticSha256,
                           String rawPayloadSha256) {}

    private final JdbcTemplate jdbc;
    public NbrkRateSnapshotReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<Revision> latest(String currency, LocalDate exactDate) {
        List<Revision> rows = revisions(currency, exactDate);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast());
    }

    @Transactional(readOnly = true)
    public List<Revision> revisions(String currency, LocalDate exactDate) {
        if (currency == null || !currency.matches("[A-Z]{3}")
                || "KZT".equals(currency) || exactDate == null)
            throw new IllegalArgumentException("NBRK_REVISION_QUERY_INVALID");
        return jdbc.query("""
            SELECT s.id,s.source_import_run_id,s.base_currency,s.nominal,s.rate,
                   s.valid_date,s.source_revision,s.source_payload_sha256,
                   r.raw_payload_sha256
            FROM crm.fx_official_rate_snapshot s
            JOIN crm.fx_rate_import_run r ON r.id=s.source_import_run_id
            WHERE s.provider_code='NBRK' AND r.provider_code='NBRK'
              AND s.synthetic=FALSE AND r.synthetic=FALSE
              AND s.base_currency=? AND s.quote_currency='KZT'
              AND s.valid_date=?
            ORDER BY s.source_revision ASC
            """, (rs,row) -> new Revision(
                    rs.getObject(1, UUID.class),rs.getObject(2,UUID.class),
                    rs.getString(3).trim(),rs.getBigDecimal(4),rs.getBigDecimal(5),
                    rs.getDate(6).toLocalDate(),rs.getInt(7),
                    rs.getString(8).trim(),rs.getString(9).trim()),
                currency,Date.valueOf(exactDate));
    }
}
