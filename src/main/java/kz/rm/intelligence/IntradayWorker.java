package kz.rm.intelligence;

import kz.rm.iam.OidcServiceActorResolver;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Same scheduler executor, explicit workload credentials. No credentials in job rows/logs. */
@Service
@ConditionalOnProperty(prefix="rm.intelligence.scheduler",name="intraday-enabled",havingValue="true")
class IntradayWorker {
    private final IntradayResultStore store;
    private final AnalyticalJobRepository jobs;
    private final OidcServiceActorResolver resolver;
    private final ObjectMapper mapper;
    private final Map<UUID,IntradayResultStore.Scope> scopes;
    private final URI endpoint;
    private final String client,secret;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    IntradayWorker(IntradayResultStore store,AnalyticalJobRepository jobs,OidcServiceActorResolver resolver,ObjectMapper mapper,
        @Value("${rm.intelligence.scheduler.intraday-runtime-admitted:false}") boolean admitted,
        @Value("${rm.intelligence.scheduler.intraday-scopes:}") String configured,
        @Value("${rm.intelligence.scheduler.service-token-uri}") String endpoint,
        @Value("${rm.intelligence.scheduler.service-client-id}") String client,
        @Value("${rm.intelligence.scheduler.service-client-secret}") String secret){
        if(!admitted||configured.isBlank()||client.isBlank()||secret.isBlank())
            throw new IllegalArgumentException("INT_INTRADAY_ADMISSION_REQUIRED");
        this.store=store;this.jobs=jobs;this.resolver=resolver;this.mapper=mapper;
        this.endpoint=URI.create(endpoint);this.client=client;this.secret=secret;
        if(!"https".equalsIgnoreCase(this.endpoint.getScheme())||this.endpoint.getUserInfo()!=null||this.endpoint.getFragment()!=null)
            throw new IllegalArgumentException("INT_SERVICE_TOKEN_HTTPS_REQUIRED");
        var map=new TreeMap<UUID,IntradayResultStore.Scope>();
        for(String entry:configured.split(",",-1)){
            // tenant=account:version:expiry-local-day:max-rows
            var pair=entry.split("=",-1);
            if(pair.length!=2)throw new IllegalArgumentException("INT_INTRADAY_SCOPE_CONFIG");
            var fields=pair[1].split(":",-1);
            if(fields.length!=4)throw new IllegalArgumentException("INT_INTRADAY_SCOPE_CONFIG");
            UUID tenant=UUID.fromString(pair[0]);
            var scope=new IntradayResultStore.Scope(tenant,UUID.fromString(fields[0]),Long.parseLong(fields[1]),
                LocalDate.parse(fields[2]),Integer.parseInt(fields[3]));
            if(map.putIfAbsent(tenant,scope)!=null)throw new IllegalArgumentException("INT_INTRADAY_DUPLICATE_TENANT");
        }
        if(map.size()>1000)throw new IllegalArgumentException("INT_INTRADAY_SCOPE_LIMIT");
        scopes=Collections.unmodifiableMap(map);
    }
    List<UUID> tenants(){return List.copyOf(scopes.keySet());}
    void plan(UUID tenant){store.plan(Objects.requireNonNull(scopes.get(tenant)));}
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static HttpResponse.BodySubscriber<byte[]> boundedBody(){
        var delegate=HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<byte[]>(){
            private java.util.concurrent.Flow.Subscription subscription;
            private long received;
            private boolean failed;
            public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
            public void onNext(List<java.nio.ByteBuffer> buffers){
                if(failed)return;
                for(var buffer:buffers)received+=buffer.remaining();
                if(received>65536){
                    failed=true;subscription.cancel();delegate.onError(new java.io.IOException("INT_SERVICE_TOKEN_RESPONSE_LIMIT"));
                }else delegate.onNext(buffers);
            }
            public void onError(Throwable error){if(!failed)delegate.onError(error);}
            public void onComplete(){if(!failed)delegate.onComplete();}
        };
    }
    private String token(){
        try{
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id="+encode(client)+"&client_secret="+encode(secret))).build();
            var response=http.send(request,info->boundedBody());
            if(response.statusCode()!=200||response.body().length>65536)throw new IllegalStateException("INT_SERVICE_TOKEN_RESPONSE");
            var node=mapper.readTree(response.body()).path("access_token");
            if(!node.isString())throw new IllegalStateException("INT_SERVICE_TOKEN_RESPONSE");
            var token=node.asText();
            if(token.isBlank()||token.length()>16384)throw new IllegalStateException("INT_SERVICE_TOKEN_RESPONSE");
            return token;
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("INT_SERVICE_TOKEN_INTERRUPTED");}
         catch(java.io.IOException ex){throw new IllegalStateException("INT_SERVICE_TOKEN_TRANSPORT");}
    }
    AnalyticalJobService.RunResult run(AnalyticalJobRepository.Claim claim){
        var scope=scopes.get(claim.tenant());
        kz.rm.iam.VerifiedServiceActor actor=null;
        try{
            if(scope==null)throw new SecurityException("INT_INTRADAY_SCOPE_DENIED");
            actor=resolver.resolve(token());
            return store.execute(claim,scope,actor);
        }catch(RuntimeException ex){
            if(actor!=null&&scope!=null){
                try{if(store.completed(claim,scope,actor).isPresent())return AnalyticalJobService.RunResult.COMPLETED;}
                catch(RuntimeException ignored){/* no success inferred from uncertain commit */}
            }
            if(AnalyticalJobService.transientFailure(ex)||"INT_SERVICE_TOKEN_TRANSPORT".equals(ex.getMessage()))
                return jobs.retry(claim,AnalyticalJobRepository.Failure.TRANSIENT)?AnalyticalJobService.RunResult.RETRY:AnalyticalJobService.RunResult.LEASE_LOST;
            var failure="INT_INTRADAY_SOURCE_AMBIGUOUS".equals(ex.getMessage())?
                AnalyticalJobRepository.Failure.SOURCE_INCOMPLETE:
                ex instanceof SecurityException?AnalyticalJobRepository.Failure.POLICY_CHANGED:
                AnalyticalJobRepository.Failure.EXECUTION_REJECTED;
            return jobs.block(claim,failure)?AnalyticalJobService.RunResult.BLOCKED:AnalyticalJobService.RunResult.LEASE_LOST;
        }
    }
}
