package kz.rm.fx;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class TrustedNbrkHttpClientTransportQualificationTest {
    private static final LocalDate DATE=LocalDate.of(2026,11,23);
    private static final byte[] BODY="<rss/>".getBytes(StandardCharsets.UTF_8);

    private static HttpResponse<InputStream> reply(HttpRequest request,int status,
                                                  URI returned,String encoding,byte[] data) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return request; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() {
                return Optional.empty();
            }
            @Override public HttpHeaders headers() {
                return HttpHeaders.of(encoding==null?Map.of():
                        Map.of("content-encoding",List.of(encoding)),(k,v)->true);
            }
            @Override public InputStream body() { return new ByteArrayInputStream(data); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return returned; }
            @Override public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    @Test void http01OnlyOfficialFixedHttpsDateUriAndNoCallerHost() {
        AtomicInteger calls=new AtomicInteger();
        TrustedNbrkHttpClient client=new TrustedNbrkHttpClient(request->{
            calls.incrementAndGet();
            assertThat(request.uri().toString()).isEqualTo(
                    "https://nationalbank.kz/rss/get_rates.cfm?fdate=23.11.2026");
            assertThat(request.timeout()).isPresent();
            return reply(request,200,request.uri(),"identity",BODY);
        },ms->{});
        assertThat(client.fetchForDate(DATE)).isEqualTo(BODY);
        assertThat(calls).hasValue(1);
    }

    @Test void http02RedirectAndMismatchedResponseOriginFailClosed() {
        TrustedNbrkHttpClient client=new TrustedNbrkHttpClient(request->
            reply(request,200,URI.create("https://evil.example/rss"),null,BODY),ms->{});
        assertThatThrownBy(()->client.fetchForDate(DATE))
                .hasMessage("NBRK_REDIRECT_FORBIDDEN");
    }

    @Test void http03Transient503RetriesTwiceThenSucceeds() {
        AtomicInteger calls=new AtomicInteger();
        TrustedNbrkHttpClient client=new TrustedNbrkHttpClient(request->
            reply(request,calls.incrementAndGet()<3?503:200,request.uri(),null,BODY),ms->{});
        assertThat(client.fetchForDate(DATE)).isEqualTo(BODY);
        assertThat(calls).hasValue(3);
    }

    @Test void http04Permanent400NeverRetries() {
        AtomicInteger calls=new AtomicInteger();
        TrustedNbrkHttpClient client=new TrustedNbrkHttpClient(request->{
            calls.incrementAndGet();
            return reply(request,400,request.uri(),null,BODY);
        },ms->{});
        assertThatThrownBy(()->client.fetchForDate(DATE))
                .hasMessage("NBRK_HTTP_STATUS_400");
        assertThat(calls).hasValue(1);
    }

    @Test void http05TlsHandshakeFailureNeverRetries() {
        AtomicInteger calls=new AtomicInteger();
        TrustedNbrkHttpClient client=new TrustedNbrkHttpClient(request->{
            calls.incrementAndGet();
            throw new SSLHandshakeException("untrusted peer");
        },ms->{});
        assertThatThrownBy(()->client.fetchForDate(DATE)).hasMessage("NBRK_TLS_FAILED");
        assertThat(calls).hasValue(1);
    }

    @Test void http06InvalidCompressionAndOversizedBodyFailClosed() {
        TrustedNbrkHttpClient zipped=new TrustedNbrkHttpClient(request->
            reply(request,200,request.uri(),"gzip",BODY),ms->{});
        assertThatThrownBy(()->zipped.fetchForDate(DATE))
                .hasMessage("NBRK_COMPRESSION_FORBIDDEN");
        TrustedNbrkHttpClient tooBig=new TrustedNbrkHttpClient(request->
            reply(request,200,request.uri(),null,
                    new byte[TrustedNbrkHttpClient.MAX_RESPONSE_BYTES+1]),ms->{});
        assertThatThrownBy(()->tooBig.fetchForDate(DATE))
                .hasMessage("NBRK_RESPONSE_SIZE_INVALID");
    }

    @Test void http07IoFailureRetriesAndStopsAfterThreeAttempts() {
        AtomicInteger calls=new AtomicInteger();
        TrustedNbrkHttpClient client=new TrustedNbrkHttpClient(request->{
            calls.incrementAndGet();
            throw new IOException("offline");
        },ms->{});
        assertThatThrownBy(()->client.fetchForDate(DATE))
                .hasMessage("NBRK_TRANSPORT_IO_ERROR");
        assertThat(calls).hasValue(3);
    }
}
