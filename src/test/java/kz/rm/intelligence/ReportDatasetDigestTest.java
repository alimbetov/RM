package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReportDatasetDigestTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DATASET = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-11-01T00:00:00Z");

    private static ReportDatasetDigest.Header header(UUID tenant) {
        return new ReportDatasetDigest.Header(tenant, DATASET, "0".repeat(64), "1".repeat(64));
    }

    private static ReportDatasetDigest.Row row(long key, BigDecimal value, List<ReportDatasetDigest.Dimension> dimensions) {
        return new ReportDatasetDigest.Row(key, "BOOKED_AMOUNT", 1, FROM, UNTIL, dimensions, "CURRENCY", "KZT",
            value, DataQuality.PARTIAL, "HISTORICAL_COVERAGE_UNQUALIFIED");
    }

    private static ReportDatasetDigest.Result hash(ReportDatasetDigest.Header header, ReportDatasetDigest.Row row) {
        var encoder = new ReportDatasetDigest(header);
        encoder.append(row);
        return encoder.finish();
    }

    @Test
    void independentDesignGoldenM12() {
        var result = hash(header(TENANT), row(1, new BigDecimal("10.5000"), List.of()));
        assertEquals(1, result.rows());
        assertEquals(484, result.canonicalBytes());
        assertEquals("f54eae406408e614c77b222932179052d01819dbaa7757d2e0f2470601293995", result.sha256());
    }

    @Test
    void decimalNormalizationAndNullAreDistinct() {
        var h = header(TENANT);
        assertEquals(hash(h, row(1, new BigDecimal("10.5000"), List.of())),
            hash(h, row(1, new BigDecimal("10.5"), List.of())));
        assertEquals(hash(h, row(1, new BigDecimal("1000"), List.of())),
            hash(h, row(1, new BigDecimal("1E+3"), List.of())));
        assertEquals(hash(h, row(1, new BigDecimal("-0.000"), List.of())),
            hash(h, row(1, BigDecimal.ZERO, List.of())));
        assertNotEquals(hash(h, row(1, null, List.of())), hash(h, row(1, BigDecimal.ZERO, List.of())));
    }

    @Test
    void tenantDatasetAndEvidenceBoundToDigest() {
        var row = row(1, BigDecimal.ONE, List.of());
        var original = hash(header(TENANT), row);
        assertNotEquals(original, hash(header(UUID.randomUUID()), row));
        assertNotEquals(original, hash(new ReportDatasetDigest.Header(TENANT, UUID.randomUUID(),
            "0".repeat(64), "1".repeat(64)), row));
        assertNotEquals(original, hash(new ReportDatasetDigest.Header(TENANT, DATASET,
            "2".repeat(64), "1".repeat(64)), row));
        assertNotEquals(original, hash(new ReportDatasetDigest.Header(TENANT, DATASET,
            "0".repeat(64), "2".repeat(64)), row));
    }

    @Test
    void dimensionsCanonicalOrderAndDuplicates() {
        var a = new ReportDatasetDigest.Dimension("SKU", "a", 1);
        var b = new ReportDatasetDigest.Dimension("REGION", "b", 1);
        assertEquals(hash(header(TENANT), row(1, BigDecimal.ONE, List.of(a, b))),
            hash(header(TENANT), row(1, BigDecimal.ONE, List.of(b, a))));
        assertThrows(IllegalArgumentException.class, () -> row(1, BigDecimal.ONE, List.of(a, a)));
    }

    @Test
    void sequenceAndFinalizationFailClosed() {
        var digest = new ReportDatasetDigest(header(TENANT));
        assertThrows(IllegalArgumentException.class, () -> digest.append(row(2, BigDecimal.ONE, List.of())));
        digest.append(row(1, BigDecimal.ONE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> digest.append(row(1, BigDecimal.ONE, List.of())));
        digest.append(row(2, BigDecimal.TEN, List.of()));
        assertEquals(2, digest.finish().rows());
        assertThrows(IllegalStateException.class, digest::finish);
        assertThrows(IllegalStateException.class, () -> digest.append(row(3, BigDecimal.ONE, List.of())));
        assertEquals(0, new ReportDatasetDigest(header(TENANT)).finish().rows());
    }

    @Test
    void rejectsInvalidCanonicalInputs() {
        assertThrows(IllegalArgumentException.class, () -> row(1, new BigDecimal("1E+1000000"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> row(1, new BigDecimal("1E-1000000"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReportDatasetDigest.Dimension("SKU", "a".repeat(129), 1));
        assertThrows(IllegalArgumentException.class, () -> row(1, BigDecimal.ONE,
            java.util.stream.IntStream.range(0,33).mapToObj(i->new ReportDatasetDigest.Dimension("SKU","id"+i,1)).toList()));
        var encoder = new ReportDatasetDigest(header(TENANT));
        assertThrows(IllegalArgumentException.class, () -> encoder.append(row(1, new BigDecimal("1E+16383"), List.of())));
        encoder.append(row(1, BigDecimal.ONE, List.of()));
        assertEquals(1, encoder.finish().rows());
        assertThrows(IllegalArgumentException.class, () -> new ReportDatasetDigest.Header(TENANT, DATASET, "x", "1".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ReportDatasetDigest.Dimension("SKU", "bad\"id", 1));
        assertThrows(IllegalArgumentException.class, () -> new ReportDatasetDigest.Row(1, "BOOKED_AMOUNT", 1,
            FROM.plusNanos(1), UNTIL, List.of(), "CURRENCY", "KZT", BigDecimal.ONE, DataQuality.PARTIAL, null));
        assertThrows(IllegalArgumentException.class, () -> new ReportDatasetDigest.Row(1, "BOOKED_AMOUNT", 1,
            UNTIL, FROM, List.of(), "CURRENCY", "KZT", BigDecimal.ONE, DataQuality.PARTIAL, null));
    }
    private static AuthorizedAnalyticalReadService.Query salesQuery(AuthorizedAnalyticalReadService.Metric metric){
        return new AuthorizedAnalyticalReadService.Query(metric,1,PeriodResolver.Code.M,java.time.LocalDate.of(2026,10,1),
            null,null,null,List.of(),metric==AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT?"KZT":null);
    }
    private static List<AuthorizedAnalyticalReadService.Result> salesWindow(AuthorizedAnalyticalReadService.Query q,BigDecimal value){
        return java.util.stream.IntStream.range(0,24).mapToObj(i->{
            BigDecimal v=i==0?null:value;
            return new AuthorizedAnalyticalReadService.Result(q.metric(),1,
                PeriodResolver.resolve(PeriodResolver.Code.M,q.anchor().minusMonths(23-i),
                    new PeriodResolver.Policy(java.time.ZoneId.of("Etc/UTC"),1)),v,
                v==null?DataQuality.MISSING:DataQuality.PARTIAL,AuthorizedAnalyticalReadService.Route.L0,
                "NO_MATCHING_ACTIVE_GENERATION",v==null?"NO_QUALIFIED_OBSERVATION":"HISTORICAL_COVERAGE_UNQUALIFIED",
                q.currency()==null?"COUNT":"CURRENCY",q.currency(),null,null,7L,UNTIL);
        }).toList();
    }
    @Test void r02ManifestAndDatasetMathematicalContract(){
        var amount=salesQuery(AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT);
        var orders=salesQuery(AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS);
        var av=salesWindow(amount,new BigDecimal("0.30"));
        var ov=salesWindow(orders,new BigDecimal("2.00"));
        var m=ReportSourceManifest.from(TENANT,List.of(amount,orders),List.of(av,ov));
        var reversed=ReportSourceManifest.from(TENANT,List.of(orders,amount),List.of(ov,av));
        assertEquals(m.scopeDigest(),reversed.scopeDigest());
        assertEquals(m.sha256(),reversed.sha256());
        var tuple=new ReportSourceManifest.SnapshotEvidence(4,3,null,null,null,null,null,null,null,null,"NO_MATCHING_ACTIVE_GENERATION");
        var v2=ReportSourceManifest.from(TENANT,List.of(amount,orders),List.of(av,ov),tuple);
        assertEquals(m.scopeDigest(),v2.scopeDigest());
        assertNotEquals(m.sha256(),v2.sha256());
        assertNotEquals(v2.sha256(),ReportSourceManifest.from(TENANT,List.of(amount,orders),List.of(av,ov),
            new ReportSourceManifest.SnapshotEvidence(5,2,null,null,null,null,null,null,null,null,"NO_MATCHING_ACTIVE_GENERATION")).sha256());
        assertThrows(IllegalArgumentException.class,()->ReportSourceManifest.from(TENANT,List.of(amount,orders),List.of(av,ov),
            new ReportSourceManifest.SnapshotEvidence(5,3,null,null,null,null,null,null,null,null,"NO_MATCHING_ACTIVE_GENERATION")));
        var accounting=new AuthorizedAnalyticalReadService.AccountingPolicy("KZ","KZT",1);
        var v3=ReportSourceManifest.from(TENANT,List.of(amount,orders),List.of(av,ov),tuple,accounting);
        assertEquals(v2.scopeDigest(),v3.scopeDigest());
        assertNotEquals(v2.sha256(),v3.sha256());
        assertNotEquals(v3.sha256(),ReportSourceManifest.from(TENANT,List.of(amount,orders),List.of(av,ov),tuple,
            new AuthorizedAnalyticalReadService.AccountingPolicy("KZ","KZT",2)).sha256());
        assertThrows(IllegalArgumentException.class,()->ReportSourceManifest.from(TENANT,List.of(amount,orders),List.of(av,ov),tuple,
            new AuthorizedAnalyticalReadService.AccountingPolicy("US","USD",1)));
        var plan=SalesReportDatasetPlan.from(m,DATASET);
        assertEquals(48,plan.expected().rows());
        assertEquals(plan.expected(),SalesReportDatasetPlan.from(reversed,DATASET).expected());
        assertNull(plan.rows().getFirst().value());
        assertEquals(DataQuality.MISSING,plan.rows().getFirst().quality());
        assertEquals(0,new BigDecimal("0.3").compareTo(plan.rows().get(1).value()));
        assertEquals(0,new BigDecimal("2").compareTo(plan.rows().get(25).value()));
        for(var bad:List.of(new BigDecimal("1.5"),new BigDecimal("-1")))
            assertThrows(IllegalArgumentException.class,()->ReportSourceManifest.from(TENANT,
                List.of(amount,orders),List.of(av,salesWindow(orders,bad))));
        assertThrows(IllegalArgumentException.class,()->ReportSourceManifest.from(TENANT,List.of(amount,amount),List.of(av,av)));
        assertNotEquals(m.sha256(),ReportSourceManifest.from(TENANT,List.of(amount,orders),
            List.of(av,salesWindow(orders,BigDecimal.ZERO))).sha256());
        assertNotEquals(m.sha256(),ReportSourceManifest.from(UUID.randomUUID(),List.of(amount,orders),List.of(av,ov)).sha256());
    }
}
