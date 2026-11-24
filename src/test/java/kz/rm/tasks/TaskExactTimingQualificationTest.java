package kz.rm.tasks;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** R0.1A: test-only candidate SQL arithmetic; no application context or business DML. */
class TaskExactTimingQualificationTest {
    private static final Instant BASE = Instant.parse("2026-11-24T12:00:00Z");
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000);
    private static final String TS = "CAST(? AS TIMESTAMP(6) WITH TIME ZONE)";

    private record Db(Connection connection, boolean pg) implements AutoCloseable {
        @Override public void close() throws SQLException { connection.close(); }
        // All aliases are test constants. H2 operands stay zoned in UTC:
        // casting to a zone-less timestamp could apply the session timezone.
        String delta(String a, String b) {
            return TaskTimingEngine.signedMicros(pg,a,b);
        }
    }

    private Db open() throws SQLException {
        String url = System.getenv("RM_TEST_DB_URL");
        boolean pg = url != null && !url.isBlank();
        String expected = System.getProperty("rm.radar.expectedDb", pg ? "postgresql" : "h2");
        assertTrue(expected.equals("postgresql") || expected.equals("h2"), "unknown expected DB");
        assertEquals(expected.equals("postgresql"), pg, "explicit DB mode mismatch; no silent fallback");
        Connection c = pg ? DriverManager.getConnection(url,
            System.getenv("RM_TEST_DB_USERNAME"), System.getenv("RM_TEST_DB_PASSWORD")) :
            DriverManager.getConnection("jdbc:h2:mem:radar_timing;MODE=PostgreSQL", "sa", "");
        try {
            assertEquals(pg ? "PostgreSQL" : "H2", c.getMetaData().getDatabaseProductName());
            if (pg) assertEquals(17, c.getMetaData().getDatabaseMajorVersion(), "qualify PG17");
            System.out.println("RADAR_TIMING_DB=" + c.getMetaData().getDatabaseProductVersion()
                + "; DRIVER=" + c.getMetaData().getDriverVersion());
            return new Db(c, pg);
        } catch (SQLException | RuntimeException | AssertionError e) {
            c.close(); throw e;
        }
    }

    private static BigInteger oracle(Instant a, Instant b) {
        Duration d = Duration.between(a,b);
        assertEquals(0,d.getNano()%1000,"microsecond-aligned fixture required");
        return BigInteger.valueOf(d.getSeconds()).multiply(MILLION)
            .add(BigInteger.valueOf(d.getNano()/1000));
    }
    private static void bind(PreparedStatement s, int pos, Instant t) throws SQLException {
        s.setObject(pos, t == null ? null : t.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }
    private BigInteger delta(Db db, Instant a, Instant b) throws SQLException {
        return delta(db, a == null ? null : a.atOffset(ZoneOffset.UTC),
            b == null ? null : b.atOffset(ZoneOffset.UTC));
    }
    private BigInteger delta(Db db, OffsetDateTime a, OffsetDateTime b) throws SQLException {
        String sql = "SELECT " + db.delta("a","b") + " FROM (SELECT " + TS + " AS a, " + TS + " AS b) AS inputs";
        try (var s = db.connection().prepareStatement(sql)) {
            s.setObject(1,a,Types.TIMESTAMP_WITH_TIMEZONE);
            s.setObject(2,b,Types.TIMESTAMP_WITH_TIMEZONE);
            try(var r=s.executeQuery()) {
                assertTrue(r.next());
                BigDecimal value=r.getBigDecimal(1);
                assertFalse(r.next());
                return value==null?null:value.toBigIntegerExact();
            }
        }
    }
    private void assertDelta(Db db, Instant a, Instant b) throws SQLException {
        assertEquals(oracle(a,b),delta(db,a,b), ()->a+" -> "+b);
    }
    @Test void q01SignedMicrosecondsAndZero() throws SQLException {
        try(var db=open()) {
            for(long us:new long[]{-1_000_001,-1_000_000,-999_999,-1001,-1000,-999,-1,0,1,999,1000,1001,999_999,1_000_000,1_000_001})
                assertDelta(db,BASE,BASE.plus(us,ChronoUnit.MICROS));
        }
    }
    @Test void q02CrossSecondAndEpoch() throws SQLException {
        try(var db=open()) {
            Instant a=Instant.parse("1969-12-31T23:59:59.999999Z");
            Instant b=Instant.parse("1970-01-01T00:00:00.000001Z");
            assertDelta(db,a,b); assertDelta(db,b,a);
            assertDelta(db,BASE.plusNanos(999_999_000),BASE.plusSeconds(1));
        }
    }
    @Test void q03LongSupportedRangeAndNegative() throws SQLException {
        try(var db=open()) {
            Instant a=Instant.parse("0001-01-01T00:00:00Z");
            Instant b=Instant.parse("9999-12-31T23:59:59.999999Z");
            assertDelta(db,a,b); assertDelta(db,b,a);
            assertDelta(db,Instant.parse("1900-01-01T00:00:00Z"),BASE);
        }
    }
    @Test void q04OffsetAndDstSpringForward() throws SQLException {
        try(var db=open()) {
            try(var s=db.connection().createStatement()){s.execute("SET TIME ZONE 'America/New_York'");}
            var a=OffsetDateTime.parse("2026-03-08T01:59:59.999999-05:00");
            var b=OffsetDateTime.parse("2026-03-08T03:00:00.000000-04:00");
            assertEquals(BigInteger.ONE,delta(db,a,b));
            assertEquals(BigInteger.ONE.negate(),delta(db,b,a));
            assertEquals(BigInteger.ZERO,delta(db,a,a.withOffsetSameInstant(ZoneOffset.UTC)));
        }
    }
    @Test void q05DstFallBackAndElapsedDays() throws SQLException {
        try(var db=open()) {
            try(var s=db.connection().createStatement()){s.execute("SET TIME ZONE 'America/New_York'");}
            var a=OffsetDateTime.parse("2026-11-01T01:30:00-04:00");
            var b=OffsetDateTime.parse("2026-11-01T01:30:00-05:00");
            assertEquals(BigInteger.valueOf(3_600_000_000L),delta(db,a,b));
            assertEquals(BigInteger.valueOf(82_800_000_000L),delta(db,
                OffsetDateTime.parse("2026-03-08T00:00:00-05:00"),OffsetDateTime.parse("2026-03-09T00:00:00-04:00")));
            assertEquals(BigInteger.valueOf(90_000_000_000L),delta(db,
                OffsetDateTime.parse("2026-11-01T00:00:00-04:00"),OffsetDateTime.parse("2026-11-02T00:00:00-05:00")));
        }
    }
    @Test void q06NullIsUnknownNotZero() throws SQLException {
        try(var db=open()) {
            assertNull(delta(db,(Instant)null,BASE));
            assertNull(delta(db,BASE,(Instant)null));
            assertNull(delta(db,(Instant)null,(Instant)null));
        }
    }
    @Test void q07CutoffTruncationAndRoundTrip() throws SQLException {
        Instant raw=BASE.plusNanos(999_999_999);
        Instant cutoff=raw.truncatedTo(ChronoUnit.MICROS);
        assertEquals(BASE.plusNanos(999_999_000),cutoff);
        try(var db=open();var s=db.connection().prepareStatement("SELECT "+TS)) {
            bind(s,1,cutoff);
            try(var r=s.executeQuery()) {
                assertTrue(r.next());
                assertEquals(cutoff,r.getObject(1,OffsetDateTime.class).toInstant());
            }
            assertDelta(db,cutoff.minusNanos(1000),cutoff);
            assertDelta(db,cutoff,cutoff.plusNanos(1000));
        }
    }
    // Candidate CASE on exact numeric inputs; not a runtime classifier.
    private String completionClass(Db db, Instant created, Instant due, Instant completed) throws SQLException {
        String sql="SELECT CASE WHEN p IS NULL OR e IS NULL OR e<0 THEN 'UNKNOWN' " +
            "WHEN e>p THEN 'COMPLETED_LATE' WHEN p>0 AND 2*e<=p THEN 'EARLY_50' " +
            "ELSE 'ON_TIME' END FROM (SELECT " + db.delta("a","b") + " AS p, " +
            db.delta("a","c") + " AS e FROM (SELECT "+TS+" AS a, "+TS+" AS b, "+TS+" AS c) AS v) AS facts";
        try(var s=db.connection().prepareStatement(sql)){
            bind(s,1,created); bind(s,2,due); bind(s,3,completed);
            try(var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }
    @Test void q08ExactFiftyPercentAndOddPlan() throws SQLException {
        try(var db=open()){
            Instant due=BASE.plus(10,ChronoUnit.DAYS), half=BASE.plus(5,ChronoUnit.DAYS);
            assertEquals("EARLY_50",completionClass(db,BASE,due,half.minusNanos(1000)));
            assertEquals("EARLY_50",completionClass(db,BASE,due,half));
            assertEquals("ON_TIME",completionClass(db,BASE,due,half.plusNanos(1000)));
            assertEquals("EARLY_50",completionClass(db,BASE,BASE.plusNanos(3000),BASE.plusNanos(1000)));
            assertEquals("ON_TIME",completionClass(db,BASE,BASE.plusNanos(3000),BASE.plusNanos(2000)));
        }
    }
    @Test void q09CompletionEqualityLateAndInvalidPlan() throws SQLException {
        try(var db=open()){
            Instant due=BASE.plusSeconds(100);
            assertEquals("ON_TIME",completionClass(db,BASE,due,due));
            assertEquals("COMPLETED_LATE",completionClass(db,BASE,due,due.plusNanos(1000)));
            assertEquals("ON_TIME",completionClass(db,BASE,BASE,BASE));
            assertEquals("COMPLETED_LATE",completionClass(db,BASE,BASE.minusSeconds(1),BASE));
            assertEquals("UNKNOWN",completionClass(db,BASE,null,BASE));
            assertEquals("UNKNOWN",completionClass(db,BASE,due,BASE.minusNanos(1000)));
            assertEquals("UNKNOWN",completionClass(db,BASE,due,null));
        }
    }
    @Test void q10ActiveBoundariesAndSeverity() throws SQLException {
        try(var db=open()){
            String sql="SELECT CASE WHEN d IS NULL THEN 'UNKNOWN' WHEN d<0 THEN 'OVERDUE' " +
                "WHEN d<=86400000000 THEN 'DUE_SOON' ELSE 'ACTIVE' END, " +
                "CASE WHEN d>=0 OR d IS NULL THEN NULL WHEN -d<86400000000 THEN 'ATTENTION' " +
                "WHEN -d<259200000000 THEN 'HIGH' ELSE 'CRITICAL' END " +
                "FROM (SELECT "+db.delta("a","b")+" AS d FROM (SELECT "+TS+" AS a, "+TS+" AS b) AS v) AS f";
            long[] offsets={-259200000001L,-259200000000L,-259199999999L,-86400000000L,-86399999999L,-1,0,1,86400000000L,86400000001L};
            String[] classes={"OVERDUE","OVERDUE","OVERDUE","OVERDUE","OVERDUE","OVERDUE","DUE_SOON","DUE_SOON","DUE_SOON","ACTIVE"};
            String[] severity={"CRITICAL","CRITICAL","HIGH","HIGH","ATTENTION","ATTENTION",null,null,null,null};
            try(var s=db.connection().prepareStatement(sql)){
                for(int i=0;i<offsets.length;i++){
                    bind(s,1,BASE); bind(s,2,BASE.plus(offsets[i],ChronoUnit.MICROS));
                    try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(classes[i],r.getString(1));assertEquals(severity[i],r.getString(2));}
                }
            }
        }
    }
    @Test void q11DeterministicRandomOracleParity() throws SQLException {
        try(var db=open()){
            Random rng=new Random(0x5241444152L);
            for(int i=0;i<256;i++){
                Instant a=BASE.plus(rng.nextLong(-2_000_000_000_000_000L,2_000_000_000_000_000L),ChronoUnit.MICROS);
                Instant b=BASE.plus(rng.nextLong(-2_000_000_000_000_000L,2_000_000_000_000_000L),ChronoUnit.MICROS);
                assertDelta(db,a,b); assertDelta(db,b,a);
            }
        }
    }
    @Test void q12LongPlanThresholdNoOverflow() throws SQLException {
        try(var db=open()){
            Instant a=Instant.parse("0001-01-01T00:00:00Z"), b=Instant.parse("9999-12-31T23:59:59.999998Z");
            BigInteger p=oracle(a,b); Instant mid=a.plus(p.divide(BigInteger.TWO).longValueExact(),ChronoUnit.MICROS);
            assertEquals("EARLY_50",completionClass(db,a,b,mid));
            assertEquals("ON_TIME",completionClass(db,a,b,mid.plusNanos(1000)));
        }
    }
}
