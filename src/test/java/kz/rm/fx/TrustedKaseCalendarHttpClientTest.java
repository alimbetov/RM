package kz.rm.fx;

import org.junit.jupiter.api.Test;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class TrustedKaseCalendarHttpClientTest {
    private static final String URL=
        "https://kase.kz/ru/members/trading-and-clearing-regulation";

    private HttpResponse<InputStream> response(HttpRequest request,int status,URI uri,
                                               String type,String encoding,byte[] bytes) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return request; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() {
                return Optional.empty();
            }
            @Override public HttpHeaders headers() {
                return HttpHeaders.of(Map.of("content-type",List.of(type),
                    "content-encoding",List.of(encoding)),(a,b)->true);
            }
            @Override public InputStream body() { return new ByteArrayInputStream(bytes); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return uri; }
            @Override public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    @Test void kase01ExactOriginAndHtmlCanBeFetchedAsImmutableBytes() {
        byte[] html="<html>KASE calendar evidence</html>".getBytes(StandardCharsets.UTF_8);
        TrustedKaseCalendarHttpClient client=new TrustedKaseCalendarHttpClient(
            request->response(request,200,request.uri(),"text/html; charset=UTF-8",
                "identity",html));
        assertThat(client.fetch(URL).bytes()).isEqualTo(html);
    }

    @Test void kase02RejectsOtherHostAndRedirect() {
        AtomicInteger calls=new AtomicInteger();
        TrustedKaseCalendarHttpClient client=new TrustedKaseCalendarHttpClient(request->{
            calls.incrementAndGet();
            return response(request,200,URI.create("https://elsewhere.example/"),
                "text/html","identity",new byte[]{1});
        });
        assertThatThrownBy(()->client.fetch("https://kase.kz.evil.example/ru/members/trading-and-clearing-regulation"))
            .hasMessage("KASE_CALENDAR_ENDPOINT_NOT_ALLOWED");
        assertThat(calls).hasValue(0);
        assertThatThrownBy(()->client.fetch(URL))
            .hasMessage("KASE_CALENDAR_REDIRECT_FORBIDDEN");
    }

    @Test void kase03RejectsCompressedBinaryAndLargeBody() {
        var compressed=new TrustedKaseCalendarHttpClient(request->
            response(request,200,request.uri(),"text/html","gzip",new byte[]{1}));
        assertThatThrownBy(()->compressed.fetch(URL))
            .hasMessage("KASE_CALENDAR_ENCODING_UNTRUSTED");
        var binary=new TrustedKaseCalendarHttpClient(request->
            response(request,200,request.uri(),"application/octet-stream",
                "identity",new byte[]{1}));
        assertThatThrownBy(()->binary.fetch(URL))
            .hasMessage("KASE_CALENDAR_TYPE_UNTRUSTED");
        var big=new TrustedKaseCalendarHttpClient(request->
            response(request,200,request.uri(),"text/html","identity",
                new byte[TrustedKaseCalendarHttpClient.MAX_BYTES+1]));
        assertThatThrownBy(()->big.fetch(URL))
            .hasMessage("KASE_CALENDAR_BYTES_INVALID");
    }

    @Test void kase04InvalidTlsStopsWithoutFallback() {
        var client=new TrustedKaseCalendarHttpClient(request->{
            throw new SSLHandshakeException("bad cert");
        });
        assertThatThrownBy(()->client.fetch(URL))
            .hasMessage("KASE_CALENDAR_TLS_FAILED");
    }
}
