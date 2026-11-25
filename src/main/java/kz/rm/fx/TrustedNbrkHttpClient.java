package kz.rm.fx;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-origin NBRK transport. TLS/hostname validation uses the JVM truststore,
 * no redirect, caller-supplied URL, proxy or HTTP downgrade.
 * Runtime egress restrictions remain a deployment responsibility.
 */
@Component
public final class TrustedNbrkHttpClient {
    static final int MAX_RESPONSE_BYTES=256*1024;
    static final String ENDPOINT_ID="NBRK_HISTORIC_RSS_V1";
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final URI ORIGIN=URI.create("https://nationalbank.kz");
    private static final int MAX_ATTEMPTS=3;

    @FunctionalInterface
    interface Transport {
        HttpResponse<InputStream> send(HttpRequest request)
                throws IOException, InterruptedException;
    }
    @FunctionalInterface
    interface Pause {
        void millis(long delay) throws InterruptedException;
    }

    private final Transport transport;
    private final Pause pause;

    @Autowired
    public TrustedNbrkHttpClient() {
        HttpClient client=HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    @Override public void connectFailed(URI uri, SocketAddress address,
                                                        IOException error) {}
                }).build();
        transport=request->client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        pause=Thread::sleep;
    }

    TrustedNbrkHttpClient(Transport transport, Pause pause) {
        this.transport=Objects.requireNonNull(transport);
        this.pause=Objects.requireNonNull(pause);
    }

    public byte[] fetchForDate(LocalDate effectiveDate) {
        if(effectiveDate==null)throw new IllegalArgumentException("NBRK_DATE_REQUIRED");
        URI uri=ORIGIN.resolve("/rss/get_rates.cfm?fdate="+DATE.format(effectiveDate));
        if(!"https".equals(uri.getScheme()) || !"nationalbank.kz".equals(uri.getHost())
                || uri.getPort()!=-1 || !"/rss/get_rates.cfm".equals(uri.getPath())
                || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IllegalStateException("NBRK_ENDPOINT_NOT_ALLOWED");
        HttpRequest request=HttpRequest.newBuilder(uri).GET()
                .timeout(Duration.ofSeconds(12))
                .header("Accept","application/xml, text/xml, application/rss+xml")
                .header("Accept-Encoding","identity").build();

        for(int attempt=1;attempt<=MAX_ATTEMPTS;attempt++) {
            try {
                HttpResponse<InputStream> response=transport.send(request);
                try(InputStream stream=response.body()) {
                    if(!uri.equals(response.uri()))
                        throw new IllegalStateException("NBRK_REDIRECT_FORBIDDEN");
                    int status=response.statusCode();
                    if(status!=200) {
                        if(retryable(status) && attempt<MAX_ATTEMPTS) {
                            backoff(attempt);
                            continue;
                        }
                        throw new IllegalStateException("NBRK_HTTP_STATUS_"+status);
                    }
                    String encoding=response.headers().firstValue("content-encoding")
                            .orElse("identity");
                    if(!"identity".equalsIgnoreCase(encoding))
                        throw new IllegalStateException("NBRK_COMPRESSION_FORBIDDEN");
                    byte[] bytes=stream.readNBytes(MAX_RESPONSE_BYTES+1);
                    if(bytes.length==0 || bytes.length>MAX_RESPONSE_BYTES)
                        throw new IllegalStateException("NBRK_RESPONSE_SIZE_INVALID");
                    return bytes;
                }
            } catch(SSLException insecure) {
                // Invalid TLS peer or certificate is never transient.
                throw new IllegalStateException("NBRK_TLS_FAILED",insecure);
            } catch(IOException network) {
                if(attempt==MAX_ATTEMPTS)
                    throw new IllegalStateException("NBRK_TRANSPORT_IO_ERROR",network);
                backoff(attempt);
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("NBRK_TRANSPORT_INTERRUPTED",interrupted);
            }
        }
        throw new IllegalStateException("NBRK_RETRY_EXHAUSTED");
    }

    private void backoff(int attempt) {
        try {
            // Strict upper bound: 100ms + 200ms (network timeouts also bounded).
            pause.millis(100L*attempt);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NBRK_TRANSPORT_INTERRUPTED",interrupted);
        }
    }
    private static boolean retryable(int status) {
        return status==429 || status==500 || status==502 || status==503 || status==504;
    }
}
