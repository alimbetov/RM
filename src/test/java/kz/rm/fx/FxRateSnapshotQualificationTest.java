package kz.rm.fx;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="rm.fx.rate.provider=MOCK")
class FxRateSnapshotQualificationTest {
    @Autowired FxRateSnapshotService rates;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private static final LocalDate FIRST=LocalDate.of(2026,11,20);
    private static final LocalDate SECOND=LocalDate.of(2026,11,21);

    @Test void fxR1a01ImportStoresDateNominalAndCanonicalSha() {
        var first=rates.importMock(FIRST);
        assertThat(first.provider()).isEqualTo("MOCK");
        assertThat(first.synthetic()).isTrue();
        assertThat(first.sourceCount()).isEqualTo(3);
        assertThat(first.inserted()+first.replayed()).isEqualTo(3);
        var cny=rates.snapshots("CNY").stream()
                .filter(r->r.validDate().equals(FIRST)).findFirst().orElseThrow();
        assertThat(cny.synthetic()).isTrue();
        assertThat(cny.provider()).isEqualTo("MOCK");
        assertThat(cny.quoteCurrency()).isEqualTo("KZT");
        assertThat(cny.nominal()).isEqualByComparingTo("10");
        assertThat(cny.rate()).isEqualByComparingTo("700");
        assertThat(cny.sha256()).matches("[0-9a-f]{64}");
        assertThat(cny.rate().divide(cny.nominal())).isEqualByComparingTo("70");
    }

    @Test void fxR1a02ReplayCreatesImportHistoryWithoutDuplicateRateRows() {
        rates.importMock(FIRST);
        int before=jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_official_rate_snapshot WHERE valid_date=?
            """,Integer.class,java.sql.Date.valueOf(FIRST));
        var replay=rates.importMock(FIRST);
        int after=jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_official_rate_snapshot WHERE valid_date=?
            """,Integer.class,java.sql.Date.valueOf(FIRST));
        assertThat(after).isEqualTo(before);
        assertThat(replay.inserted()).isZero();
        assertThat(replay.replayed()).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_rate_import_run WHERE id=?
            """,Integer.class,replay.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT replayed_count FROM crm.fx_rate_import_run WHERE id=?
            """,Integer.class,replay.id())).isEqualTo(3);
    }

    @Test void fxR1a03NewEffectiveDateIsANewSnapshotNotAnOverwrite() {
        rates.importMock(FIRST);
        var old=rates.snapshots("USD").stream().filter(r->r.validDate().equals(FIRST))
                .findFirst().orElseThrow();
        var run=rates.importMock(SECOND);
        assertThat(run.sourceCount()).isEqualTo(3);
        assertThat(rates.snapshots("USD")).anySatisfy(v->{
            if(v.validDate().equals(SECOND))
                assertThat(v.rate()).isEqualByComparingTo("505");
        });
        var prior=rates.snapshots("USD").stream().filter(r->r.validDate().equals(FIRST))
                .findFirst().orElseThrow();
        assertThat(prior.id()).isEqualTo(old.id());
        assertThat(prior.sha256()).isEqualTo(old.sha256());
    }

    @Test void fxR1a04AbsentMockDateRejectsWithoutHistoryWrites() {
        Integer before=jdbc.queryForObject("SELECT count(*) FROM crm.fx_rate_import_run",Integer.class);
        assertThatThrownBy(()->rates.importMock(LocalDate.of(2026,11,19)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FX_MOCK_RATE_NOT_FOUND");
        Integer after=jdbc.queryForObject("SELECT count(*) FROM crm.fx_rate_import_run",Integer.class);
        assertThat(after).isEqualTo(before);
    }

    @Test void fxR1a05ImmutableEvidencePostgresRejectsUpdateAndDelete() throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var run=rates.importMock(SECOND);
        UUID id=rates.snapshots("CNY").stream().filter(r->r.validDate().equals(SECOND))
                .findFirst().orElseThrow().id();
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.fx_official_rate_snapshot SET rate=? WHERE id=?
            """,new BigDecimal("999"),id)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.fx_rate_import_run WHERE id=?
            """,run.id())).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_rate_import_run WHERE id=?
            """,Integer.class,run.id())).isEqualTo(1);
    }

    @Test void fxR1a06DatabaseRejectsPretendOfficialRate() {
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.fx_official_rate_snapshot
             (id,provider_code,base_currency,quote_currency,nominal,rate,
              valid_date,source_revision,source_payload_sha256,synthetic,recorded_at)
            VALUES (?,'NBRK','USD','KZT',1,500,?,1,?,FALSE,CURRENT_TIMESTAMP)
            """,UUID.randomUUID(),java.sql.Date.valueOf(FIRST),"a".repeat(64)))
                .isInstanceOf(Exception.class);
    }
}
