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
import java.util.List;
import java.util.Objects;

/**
 * Fetches source bytes from exact KASE HTTPS origins without redirects.
 * Returns bytes, not a claim that dates have been independently parsed.
 * Network egress allowlisting remains a deployment responsibility.
 */
@Component
public final class TrustedKaseCalendarHttpClient {
    static final int MAX_BYTES=1024*1024;
    private static final String CALENDAR="/ru/members/trading-and-clearing-regulation";
    private static final String NOTICE_PREFIX="/ru/information/news/show/";

    @FunctionalInterface
    interface Transport {
        HttpResponse<InputStream> send(HttpRequest request)
            throws IOException, InterruptedException;
    }

    public record Document(String sourceUrl, byte[] bytes) {}
    private final Transport transport;

    @Autowired
    public TrustedKaseCalendarHttpClient() {
        HttpClient client=HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                }
                @Override public void connectFailed(URI uri,SocketAddress address,IOException error) {}
            }).build();
        transport=request->client.send(request,HttpResponse.BodyHandlers.ofInputStream());
    }

    TrustedKaseCalendarHttpClient(Transport transport) {
        this.transport=Objects.requireNonNull(transport);
    }

    public Document fetch(String sourceUrl) {
        URI uri;
        try {
            uri=URI.create(sourceUrl);
        } catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("KASE_CALENDAR_ENDPOINT_NOT_ALLOWED",invalid);
        }
        String path=uri.getPath();
        if(!"https".equals(uri.getScheme()) || !"kase.kz".equals(uri.getHost())
                ||uri.getPort()!=-1 ||uri.getUserInfo()!=null ||uri.getQuery()!=null
                ||uri.getFragment()!=null ||path==null
                ||!(CALENDAR.equals(path)
                  ||(path.startsWith(NOTICE_PREFIX)
                        &&path.substring(NOTICE_PREFIX.length()).matches("[0-9]{5,12}"))))
            throw new IllegalArgumentException("KASE_CALENDAR_ENDPOINT_NOT_ALLOWED");
        HttpRequest request=HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12)).GET()
            .header("Accept","text/html")
            .header("Accept-Encoding","identity").build();
        try {
            HttpResponse<InputStream> response=transport.send(request);
            try(InputStream stream=response.body()) {
                if(!uri.equals(response.uri()))
                    throw new IllegalStateException("KASE_CALENDAR_REDIRECT_FORBIDDEN");
                if(response.statusCode()!=200)
                    throw new IllegalStateException("KASE_CALENDAR_HTTP_"+response.statusCode());
                if(!"identity".equalsIgnoreCase(
                        response.headers().firstValue("content-encoding").orElse("identity")))
                    throw new IllegalStateException("KASE_CALENDAR_ENCODING_UNTRUSTED");
                String media=response.headers().firstValue("content-type").orElse("").toLowerCase(
                    java.util.Locale.ROOT);
                if(!media.startsWith("text/html"))
                    throw new IllegalStateException("KASE_CALENDAR_TYPE_UNTRUSTED");
                byte[] raw=stream.readNBytes(MAX_BYTES+1);
                if(raw.length<1||raw.length>MAX_BYTES)
                    throw new IllegalStateException("KASE_CALENDAR_BYTES_INVALID");
                return new Document(sourceUrl,raw);
            }
        } catch(SSLException e) {
            throw new IllegalStateException("KASE_CALENDAR_TLS_FAILED",e);
        } catch(IOException e) {
            throw new IllegalStateException("KASE_CALENDAR_TRANSPORT_FAILED",e);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("KASE_CALENDAR_INTERRUPTED",e);
        }
    }
}
