package kz.rm.iam;

import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Local synthetic issuer; production JWT validation and actor resolution. */
public final class JourneySignedActorFixture implements AutoCloseable {
    private static final String ISSUER="https://rm-journey.test/realms/rm";
    private static final String AUDIENCE="rm-api";
    private final KeyPair key;
    private final HttpServer server;
    private final NimbusJwtDecoder decoder;
    public JourneySignedActorFixture() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);key=generator.generateKeyPair();
        var pub=(RSAPublicKey)key.getPublic();
        String jwk="{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"journey\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
            +unsigned(pub.getModulus())+"\",\"e\":\""+unsigned(pub.getPublicExponent())+"\"}]}";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks",exchange->{
            byte[] bytes=jwk.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var body=exchange.getResponseBody()){body.write(bytes);}
        });
        server.start();
        decoder=NimbusJwtDecoder.withJwkSetUri("http://127.0.0.1:"+server.getAddress().getPort()+"/jwks").build();
        OidcTestDecoderSupport.configure(decoder,ISSUER,AUDIENCE);
    }
    private static String b64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String unsigned(BigInteger number){
        byte[] bytes=number.toByteArray();
        return b64(bytes.length>1&&bytes[0]==0?Arrays.copyOfRange(bytes,1,bytes.length):bytes);
    }
    public enum InvalidToken { WRONG_ISSUER, WRONG_AUDIENCE, ID_TOKEN, EXPIRED, BAD_SIGNATURE }
    public VerifiedActor resolve(JdbcTemplate jdbc,UserAccountRepository users,UUID user){
        var actor=new OidcActorResolver(decoder,users).resolve(token(jdbc,user,null));
        if(!user.equals(actor.userId()))throw new AssertionError("Resolved user differs from fixture");
        return actor;
    }
    public VerifiedActor resolveInvalid(JdbcTemplate jdbc,UserAccountRepository users,UUID user,InvalidToken invalid){
        return new OidcActorResolver(decoder,users).resolve(token(jdbc,user,Objects.requireNonNull(invalid)));
    }
    private String token(JdbcTemplate jdbc,UUID user,InvalidToken invalid){
        try{
            String subject=jdbc.queryForObject("SELECT external_subject FROM public.iam_user_account WHERE id=?",String.class,user);
            long now=Instant.now().getEpochSecond();
            String header="{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"journey\"}";
            String body="{\"iss\":\""+(invalid==InvalidToken.WRONG_ISSUER?"https://foreign-issuer.test":ISSUER)+
                "\",\"aud\":[\""+(invalid==InvalidToken.WRONG_AUDIENCE?"foreign-api":AUDIENCE)+"\"],\"sub\":\""+subject+
                "\",\"azp\":\"rm-seller-web\",\"typ\":\""+(invalid==InvalidToken.ID_TOKEN?"ID":"Bearer")+
                "\",\"iat\":"+(now-600)+",\"nbf\":"+(now-600)+",\"exp\":"+(invalid==InvalidToken.EXPIRED?now-300:now+600)+"}";
            String joined=b64(header.getBytes(StandardCharsets.UTF_8))+"."+b64(body.getBytes(StandardCharsets.UTF_8));
            Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());
            signer.update(joined.getBytes(StandardCharsets.US_ASCII));
            byte[] signature=signer.sign();
            if(invalid==InvalidToken.BAD_SIGNATURE)signature[0]^=1;
            return joined+"."+b64(signature);
        }catch(Exception e){throw new IllegalStateException("Signed journey token creation failed",e);}
    }
    @Override public void close(){server.stop(0);}
}
