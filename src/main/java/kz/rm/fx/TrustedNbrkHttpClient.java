package kz.rm.fx;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * FX-R1A.3 controlled NBRK transport. Not an HTTP endpoint or scheduler.
 * Fixed URI/authority: no caller-provided host, path, query or redirect.
 */
@Component
public final class TrustedNbrkHttpClient {
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    static final String ENDPOINT_ID = "NBRK_HISTORIC_RSS_V1";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final URI ORIGIN = URI.create("https://nationalbank.kz");
    private final HttpClient client;

    public TrustedNbrkHttpClient() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    @Override public void connectFailed(URI uri, java.net.SocketAddress address, IOException exception) {
                        // No proxy is permitted.
                    }
                })
                .build());
    }

    TrustedNbrkHttpClient(HttpClient client) {
        this.client = java.util.Objects.requireNonNull(client);
    }

    public byte[] fetchForDate(LocalDate effectiveDate) {
        if (effectiveDate == null)
            throw new IllegalArgumentException("NBRK_DATE_REQUIRED");
        URI uri = ORIGIN.resolve("/rss/get_rates.cfm?fdate=" + DATE.format(effectiveDate));
        if (!"https".equals(uri.getScheme()) || !"nationalbank.kz".equals(uri.getHost())
                || uri.getPort() != -1 || !"/rss/get_rates.cfm".equals(uri.getPath())
                || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalStateException("NBRK_ENDPOINT_NOT_ALLOWED");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET().timeout(Duration.ofSeconds(12))
                .header("Accept", "application/xml, text/xml, application/rss+xml")
                .header("Accept-Encoding", "identity").build();
        try {
            HttpResponse<java.io.InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream stream = response.body()) {
                if (response.statusCode() != 200)
                    throw new IllegalStateException("NBRK_HTTP_STATUS_" + response.statusCode());
                if (!response.uri().equals(uri))
                    throw new IllegalStateException("NBRK_REDIRECT_FORBIDDEN");
                String encoding = response.headers().firstValue("content-encoding").orElse("identity");
                if (!"identity".equalsIgnoreCase(encoding))
                    throw new IllegalStateException("NBRK_COMPRESSION_FORBIDDEN");
                byte[] data = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (data.length == 0 || data.length > MAX_RESPONSE_BYTES)
                    throw new IllegalStateException("NBRK_RESPONSE_SIZE_INVALID");
                return data;
            }
        } catch (IOException e) {
            throw new IllegalStateException("NBRK_TRANSPORT_IO_ERROR", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NBRK_TRANSPORT_INTERRUPTED", e);
        }
    }
}
