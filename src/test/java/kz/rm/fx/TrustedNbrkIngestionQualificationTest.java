package kz.rm.fx;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TrustedNbrkIngestionQualificationTest {
    @Autowired NbrkRssXmlParser parser;
    @Autowired NbrkRateRevisionWriter writer;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final LocalDate BASE = LocalDate.of(2031, 1, 1);

    private static String item(String currency, LocalDate date, String rate, String nominal) {
        return "<item><title>" + currency + "</title><pubDate>" + F.format(date)
                + "</pubDate><description>" + rate + "</description><quant>"
                + nominal + "</quant></item>";
    }
    private static byte[] rss(String items) {
        return ("<?xml version=\"1.0\"?><rss><channel>" + items
                + "</channel></rss>").getBytes(StandardCharsets.UTF_8);
    }
    private TrustedNbrkIngestionService service(byte[] payload) {
        return new TrustedNbrkIngestionService(d -> payload, parser, writer);
    }
    private static LocalDate d(int delta) { return BASE.plusDays(delta); }
    private byte[] one(LocalDate date, String rate) {
        return rss(item("USD", date, rate, "1"));
    }
    private int revisions(LocalDate date) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_official_rate_snapshot
            WHERE provider_code='NBRK' AND base_currency='USD' AND valid_date=?
            """, Integer.class, java.sql.Date.valueOf(date));
    }

    @Test void nb01AcceptedOfficialTypedBatchWritesSourceEvidence() {
        var date=d(1);
        var result=service(rss(item("USD", date, "501.23", "1")
                + item("CNY", date, "704.00", "10"))).importForDate(date);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.corrected()).isZero();
        assertThat(result.rawPayloadSha256()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_rate_import_run
            WHERE id=? AND synthetic=FALSE AND provider_code='NBRK'
              AND source_endpoint='NBRK_HISTORIC_RSS_V1'
              AND raw_payload_sha256=?
            """,Integer.class,result.runId(),result.rawPayloadSha256())).isEqualTo(1);
    }

    @Test void nb02ExactReplayDoesNotAppendRevision() {
        var date=d(2);
        byte[] body=one(date,"502.20");
        service(body).importForDate(date);
        assertThat(service(body).importForDate(date).replayed()).isEqualTo(1);
        assertThat(revisions(date)).isEqualTo(1);
    }

    @Test void nb03ReorderedResponseHasDistinctRawHashAndSameSemanticRevisions() {
        var date=d(3);
        byte[] first=rss(item("USD",date,"503.00","1")+item("CNY",date,"710.0","10"));
        byte[] second=rss(item("CNY",date,"710.00","10")+"\n"+item("USD",date,"503.000","1"));
        var a=service(first).importForDate(date);
        var b=service(second).importForDate(date);
        assertThat(b.rawPayloadSha256()).isNotEqualTo(a.rawPayloadSha256());
        assertThat(b.replayed()).isEqualTo(2);
        assertThat(revisions(date)).isEqualTo(1);
    }

    @Test void nb04CorrectionAppendsImmutableRevision() {
        var date=d(4);
        service(one(date,"504.01")).importForDate(date);
        var outcome=service(one(date,"504.11")).importForDate(date);
        assertThat(outcome.corrected()).isEqualTo(1);
        assertThat(revisions(date)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_official_rate_snapshot s
            JOIN crm.fx_rate_import_run r ON r.id=s.source_import_run_id
            WHERE s.provider_code='NBRK' AND s.valid_date=?
              AND r.provider_code='NBRK' AND r.raw_payload_sha256 IS NOT NULL
            """,Integer.class,java.sql.Date.valueOf(date))).isEqualTo(2);
        assertThat(jdbc.queryForList("""
             SELECT source_revision FROM crm.fx_official_rate_snapshot
             WHERE provider_code='NBRK' AND base_currency='USD' AND valid_date=?
             ORDER BY source_revision
             """,Integer.class,java.sql.Date.valueOf(date))).containsExactly(1,2);
    }

    @Test void nb05PostgresImmutableOldRevisionCannotBeUpdated() throws Exception {
        try(var c=dataSource.getConnection()){
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
        var date=d(5);
        service(one(date,"505.01")).importForDate(date);
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.fx_official_rate_snapshot SET rate=999
            WHERE provider_code='NBRK' AND base_currency='USD' AND valid_date=?
            """,java.sql.Date.valueOf(date))).isInstanceOf(Exception.class);
    }

    @Test void nb06BadXmlCannotCreateRows() {
        var date=d(6);
        assertThatThrownBy(()->service("<rss>".getBytes(StandardCharsets.UTF_8))
                .importForDate(date)).isInstanceOf(IllegalArgumentException.class);
        assertThat(revisions(date)).isZero();
    }

    @Test void nb07DtdEntityForbidden() {
        var date=d(7);
        byte[] body=("<!DOCTYPE rss [<!ENTITY xx SYSTEM \"file:///etc/passwd\">]>"
                +"<rss><channel>"+item("USD",date,"1","1")+"</channel></rss>")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->service(body).importForDate(date))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(revisions(date)).isZero();
    }

    @Test void nb08OversizeRejectedBeforePersistence() {
        var date=d(8);
        assertThatThrownBy(()->service(new byte[TrustedNbrkHttpClient.MAX_RESPONSE_BYTES+1])
                .importForDate(date)).isInstanceOf(IllegalArgumentException.class);
        assertThat(revisions(date)).isZero();
    }

    @Test void nb09WrongPublishedDateRejected() {
        var date=d(9);
        assertThatThrownBy(()->service(one(date.minusDays(1),"509.01")).importForDate(date))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NBRK_EFFECTIVE_DATE_MISMATCH");
        assertThat(revisions(date)).isZero();
    }

    @Test void nb10InvalidNominalRejected() {
        var date=d(10);
        assertThatThrownBy(()->service(rss(item("USD",date,"510.01","0")))
                .importForDate(date)).isInstanceOf(IllegalArgumentException.class);
        assertThat(revisions(date)).isZero();
    }

    @Test void nb11ConflictingDuplicateCurrencyRejected() {
        var date=d(11);
        assertThatThrownBy(()->service(rss(item("USD",date,"511.01","1")
                +item("USD",date,"512.01","1"))).importForDate(date))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(revisions(date)).isZero();
    }

    @Test void nb12NoFallbackWhenTransportFails() {
        var date=d(12);
        var service=new TrustedNbrkIngestionService(x->{throw new IllegalStateException("offline");},
                parser,writer);
        assertThatThrownBy(()->service.importForDate(date))
                .isInstanceOf(IllegalStateException.class).hasMessage("offline");
        assertThat(revisions(date)).isZero();
    }

    @Test void nb13BatchValidationFailClosedAndNoPartialWrites() {
        var date=d(13);
        var good=new FxRateProvider.Rate("USD","KZT",new java.math.BigDecimal("513"),
                java.math.BigDecimal.ONE,date,"NBRK");
        var bad=new FxRateProvider.Rate("EUR","KZT",new java.math.BigDecimal("513"),
                java.math.BigDecimal.ONE,date.minusDays(1),"NBRK");
        assertThatThrownBy(()->writer.persist(date,List.of(good,bad),"a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(revisions(date)).isZero();
    }

    @Test void nb14ConcurrentIdenticalCorrectionsProduceOneRevision() {
        var date=d(14);
        service(one(date,"514.00")).importForDate(date);
        try(var executor=Executors.newFixedThreadPool(4)){
            List<CompletableFuture<TrustedNbrkIngestionService.Outcome>> futures=
                    IntStream.range(0,4).mapToObj(i->CompletableFuture.supplyAsync(
                            ()->service(one(date,"514.50")).importForDate(date),executor)).toList();
            var values=futures.stream().map(CompletableFuture::join).toList();
            assertThat(values.stream().mapToInt(TrustedNbrkIngestionService.Outcome::corrected).sum())
                    .isEqualTo(1);
            assertThat(revisions(date)).isEqualTo(2);
        }
    }

    @Test void nb15MockHistoryRemainsDisjoint() {
        var date=d(15);
        service(one(date,"515.0")).importForDate(date);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_official_rate_snapshot
            WHERE provider_code='MOCK' AND valid_date=?
            """,Integer.class,java.sql.Date.valueOf(date))).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.fx_official_rate_snapshot
            WHERE provider_code='NBRK' AND valid_date=? AND synthetic=FALSE
            """,Integer.class,java.sql.Date.valueOf(date))).isEqualTo(1);
    }
}
