package kz.rm.intelligence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** RM-ARS-R1 canonical dataset encoding v1. Integrity only, not authorization or source completeness. */
final class ReportDatasetDigest {
    private static final String SCHEMA = "RM-ARS-DATASET-1";
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final Comparator<Dimension> DIMENSION_ORDER = Comparator.comparing(Dimension::kind)
        .thenComparing(Dimension::id).thenComparing(d -> Long.toString(d.mappingVersion()));

    record Header(UUID tenant, UUID dataset, String scopeSha256, String sourceManifestSha256) {
        Header {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(dataset);
            requireDigest(scopeSha256);
            requireDigest(sourceManifestSha256);
        }
    }

    record Dimension(String kind, String id, long mappingVersion) {
        Dimension {
            requireToken(kind);
            requireToken(id);
            if (mappingVersion < 1) throw new IllegalArgumentException("REPORT_DIMENSION_VERSION");
        }
    }

    record Row(long rowKey, String metricCode, long methodVersion, Instant fromInclusive,
               Instant untilExclusive, List<Dimension> dimensions, String unit, String currency,
               BigDecimal value, DataQuality quality, String reason) {
        Row {
            if (rowKey < 1 || methodVersion < 1) throw new IllegalArgumentException("REPORT_ROW_IDENTITY");
            requireToken(metricCode);
            requireToken(unit);
            timestamp(fromInclusive);
            timestamp(untilExclusive);
            if (!fromInclusive.isBefore(untilExclusive)) throw new IllegalArgumentException("REPORT_PERIOD");
            if (currency != null && !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("REPORT_CURRENCY");
            if (reason != null) requireToken(reason);
            Objects.requireNonNull(quality);
            var sorted = new ArrayList<>(List.copyOf(dimensions));
            sorted.sort(DIMENSION_ORDER);
            for (int i = 1; i < sorted.size(); i++) {
                if (DIMENSION_ORDER.compare(sorted.get(i - 1), sorted.get(i)) == 0)
                    throw new IllegalArgumentException("REPORT_DUPLICATE_DIMENSION");
            }
            dimensions = List.copyOf(sorted);
        }
    }

    record Result(long rows, long canonicalBytes, String sha256) {}

    private final MessageDigest digest;
    private long rows;
    private long bytes;
    private boolean finished;

    ReportDatasetDigest(Header header) {
        Objects.requireNonNull(header);
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        update(SCHEMA + "\n");
        frame("{\"dataset\":" + quote(header.dataset().toString()) + ",\"schema\":" + quote(SCHEMA)
            + ",\"scope_sha256\":" + quote(header.scopeSha256()) + ",\"source_manifest_sha256\":"
            + quote(header.sourceManifestSha256()) + ",\"tenant\":" + quote(header.tenant().toString()) + "}");
    }

    void append(Row row) {
        requireOpen();
        Objects.requireNonNull(row);
        if (row.rowKey() != Math.addExact(rows, 1)) throw new IllegalArgumentException("REPORT_ROW_SEQUENCE");
        var dimensions = new StringBuilder("[");
        for (var d : row.dimensions()) {
            if (dimensions.length() > 1) dimensions.append(',');
            dimensions.append(array(d.kind(), d.id(), Long.toString(d.mappingVersion())));
        }
        dimensions.append(']');
        String encoded = "[" + quote(Long.toString(row.rowKey())) + "," + quote(row.metricCode()) + ","
            + quote(Long.toString(row.methodVersion())) + "," + quote(timestamp(row.fromInclusive())) + ","
            + quote(timestamp(row.untilExclusive())) + "," + dimensions + "," + quote(row.unit()) + ","
            + quote(row.currency()) + "," + quote(decimal(row.value())) + "," + quote(row.quality().name())
            + "," + quote(row.reason()) + "]";
        frame(encoded);
        rows++;
    }

    Result finish() {
        requireOpen();
        frame(Long.toString(rows));
        finished = true;
        return new Result(rows, bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private void requireOpen() {
        if (finished) throw new IllegalStateException("REPORT_DIGEST_FINISHED");
    }

    private void frame(String data) {
        byte[] encoded = data.getBytes(StandardCharsets.UTF_8);
        update(Integer.toString(encoded.length) + ":");
        update(encoded);
    }

    private void update(String value) { update(value.getBytes(StandardCharsets.UTF_8)); }
    private void update(byte[] value) {
        long next = Math.addExact(bytes, value.length);
        digest.update(value);
        bytes = next;
    }

    private static String timestamp(Instant value) {
        Objects.requireNonNull(value);
        if (value.getNano() % 1000 != 0) throw new IllegalArgumentException("REPORT_TIMESTAMP_PRECISION");
        String result = TIMESTAMP.format(value);
        if (result.length() != 27) throw new IllegalArgumentException("REPORT_TIMESTAMP_RANGE");
        return result;
    }

    private static String decimal(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("REPORT_DIGEST");
    }

    private static void requireToken(String value) {
        // Closed ASCII identifier alphabet: excludes JSON escapes and localized/user text.
        if (value == null || !value.matches("[A-Za-z0-9_.:-]+"))
            throw new IllegalArgumentException("REPORT_CANONICAL_TOKEN");
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String array(String a, String b, String c) {
        return "[" + quote(a) + "," + quote(b) + "," + quote(c) + "]";
    }
}
