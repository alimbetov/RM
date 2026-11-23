package kz.rm.fx;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Network/parse outside transaction, then delegate to an atomic persistence boundary. */
@Service
public final class TrustedNbrkIngestionService {
    public record Outcome(java.util.UUID runId, LocalDate requestedDate, int inserted,
                          int corrected, int replayed, String rawPayloadSha256) {}

    private final Function<LocalDate, byte[]> fetch;
    private final NbrkRssXmlParser parser;
    private final NbrkRateRevisionWriter writer;

    @org.springframework.beans.factory.annotation.Autowired
    public TrustedNbrkIngestionService(TrustedNbrkHttpClient client,
                                       NbrkRssXmlParser parser,
                                       NbrkRateRevisionWriter writer) {
        this(client::fetchForDate, parser, writer);
    }

    TrustedNbrkIngestionService(Function<LocalDate, byte[]> fetch,
                                NbrkRssXmlParser parser,
                                NbrkRateRevisionWriter writer) {
        this.fetch = Objects.requireNonNull(fetch);
        this.parser = Objects.requireNonNull(parser);
        this.writer = Objects.requireNonNull(writer);
    }

    /** Internal privileged operation only. No timer, REST exposure or implicit MOCK fallback. */
    public Outcome importForDate(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("NBRK_DATE_REQUIRED");
        byte[] response = Objects.requireNonNull(fetch.apply(date), "NBRK_EMPTY_RESPONSE");
        if (response.length == 0 || response.length > TrustedNbrkHttpClient.MAX_RESPONSE_BYTES)
            throw new IllegalArgumentException("NBRK_RESPONSE_SIZE_INVALID");
        List<NbrkRssXmlParser.ParsedRate> parsed = parser.parse(response, date);
        // Until official holiday/previous-business-day policy is approved, fail closed.
        if (parsed.stream().anyMatch(r -> !r.rate().validDate().equals(date)
                || !"NBRK".equals(r.rate().provider())
                || !"KZT".equals(r.rate().quoteCurrency())))
            throw new IllegalArgumentException("NBRK_EFFECTIVE_DATE_MISMATCH");
        return writer.persist(date, parsed.stream().map(NbrkRssXmlParser.ParsedRate::rate).toList(),
                sha256(response));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", ex);
        }
    }
}
