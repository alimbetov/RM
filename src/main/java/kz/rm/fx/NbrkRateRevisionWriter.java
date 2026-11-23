package kz.rm.fx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Serializes NBRK semantic corrections under the existing FX provider lock.
 * Snapshot revisions and run metadata are append-only (PostgreSQL triggers).
 */
@Service
public class NbrkRateRevisionWriter {
    private final JdbcTemplate jdbc;

    public NbrkRateRevisionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public TrustedNbrkIngestionService.Outcome persist(
            LocalDate date, List<FxRateProvider.Rate> rates, String rawSha256) {
        if (date == null || rates == null || rates.isEmpty()
                || rawSha256 == null || !rawSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("NBRK_BATCH_INVALID");
        if (rates.stream().anyMatch(r -> !"NBRK".equals(r.provider())
                || !"KZT".equals(r.quoteCurrency()) || !date.equals(r.validDate())))
            throw new IllegalArgumentException("NBRK_BATCH_INVALID");
        if (rates.stream().map(FxRateProvider.Rate::currency).distinct().count() != rates.size())
            throw new IllegalArgumentException("NBRK_BATCH_DUPLICATE_CURRENCY");

        // This lock must exist as a seeded row in Liquibase. It serializes competing
        // NBRK writers even when two workers imported different source payloads.
        String locked = jdbc.query("""
            SELECT provider_code FROM crm.fx_rate_provider_lock
            WHERE provider_code='NBRK' FOR UPDATE
            """, rs -> rs.next() ? rs.getString(1) : null);
        if (!"NBRK".equals(locked))
            throw new IllegalStateException("NBRK_PROVIDER_LOCK_MISSING");

        int inserted = 0;
        int corrected = 0;
        int replayed = 0;
        for (FxRateProvider.Rate rate : rates) {
            String semanticHash = FxRateSnapshotService.canonicalDigest(rate);
            List<Existing> existing = jdbc.query("""
                SELECT source_revision, source_payload_sha256, nominal, rate
                FROM crm.fx_official_rate_snapshot
                WHERE provider_code='NBRK' AND base_currency=?
                  AND quote_currency='KZT' AND valid_date=?
                ORDER BY source_revision DESC
                """, (rs, row) -> new Existing(rs.getInt(1), rs.getString(2).trim(),
                    rs.getBigDecimal(3), rs.getBigDecimal(4)),
                    rate.currency(), Date.valueOf(date));

            Existing current = existing.isEmpty() ? null : existing.getFirst();
            // The canonical digest (rather than raw XML SHA) makes reordering
            // and whitespace-only changes a semantic no-op.
            if (current != null && current.sha256.equals(semanticHash)
                    && current.rate.compareTo(rate.rateKzt()) == 0
                    && current.nominal.compareTo(rate.nominal()) == 0) {
                replayed++;
                continue;
            }
            int nextRevision = current == null ? 1 : Math.addExact(current.revision, 1);
            jdbc.update("""
                INSERT INTO crm.fx_official_rate_snapshot
                  (id,provider_code,base_currency,quote_currency,nominal,rate,
                   valid_date,source_revision,source_payload_sha256,synthetic,recorded_at)
                VALUES (?,'NBRK',?,?,?,?,?,?,?,FALSE,?)
                """, UUID.randomUUID(), rate.currency(), rate.quoteCurrency(),
                rate.nominal(), rate.rateKzt(), Date.valueOf(date),
                nextRevision, semanticHash, Timestamp.from(Instant.now()));
            if (current == null) inserted++; else corrected++;
        }
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.fx_rate_import_run
              (id,provider_code,requested_date,imported_count,replayed_count,
               source_count,synthetic,completed_at,raw_payload_sha256,source_endpoint)
            VALUES (?,'NBRK',?,?,?,?,FALSE,?,?,?)
            """, runId, Date.valueOf(date), inserted + corrected, replayed, rates.size(),
                Timestamp.from(Instant.now()), rawSha256,
                TrustedNbrkHttpClient.ENDPOINT_ID);
        return new TrustedNbrkIngestionService.Outcome(runId, date,
                inserted, corrected, replayed, rawSha256);
    }

    private record Existing(int revision, String sha256,
                            java.math.BigDecimal nominal, java.math.BigDecimal rate) {}
}
