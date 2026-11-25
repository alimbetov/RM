package kz.rm.iam;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Test-only, ephemeral RSA issuer. The loopback endpoint exposes only the
 * public JWK. No production issuer, external IdP, TLS or HTTP API admission
 * is asserted. Consumers (including child JVMs) receive only bearer and URI.
 * Every decoder reuses the actual production claim validators.
 */
public final class SignedJwtTestAuthority implements AutoCloseable {
    public static final String ISSUER="https://issuer.rm.example.test/realms/rm";
    public static final String AUDIENCE="rm-api";
    public enum SigningKey { TRUSTED, UNTRUSTED }

    private final KeyPair trusted;
    private final KeyPair untrusted;
    private final String kid;
    private final HttpServer server;
    private final URI jwksUri;
    private boolean closed;

    private SignedJwtTestAuthority(KeyPair trusted,KeyPair untrusted,String kid,
                                   HttpServer server,URI jwksUri) {
        this.trusted=trusted;this.untrusted=untrusted;this.kid=kid;
        this.server=server;this.jwksUri=jwksUri;
    }

    public static SignedJwtTestAuthority start() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var trusted=generator.generateKeyPair();
        var untrusted=generator.generateKeyPair();
        String kid="rm-test-"+UUID.randomUUID();
        var publicJwk=new RSAKey.Builder((RSAPublicKey)trusted.getPublic())
                .keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        byte[] payload=new JWKSet(publicJwk).toString().getBytes(StandardCharsets.UTF_8);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try {
            server.createContext("/jwks",exchange->{
                try {
                    if(!"GET".equals(exchange.getRequestMethod())||
                            !"/jwks".equals(exchange.getRequestURI().getPath())){
                        exchange.sendResponseHeaders(405,-1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.getResponseHeaders().set("Cache-Control","no-store");
                    exchange.sendResponseHeaders(200,payload.length);
                    try(var body=exchange.getResponseBody()){body.write(payload);}
                }finally{exchange.close();}
            });
            server.start();
            URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/jwks");
            return new SignedJwtTestAuthority(trusted,untrusted,kid,server,uri);
        }catch(RuntimeException|Error failure){
            server.stop(0);
            throw failure;
        }
    }

    public synchronized URI jwksUri(){
        ensureOpen();
        return jwksUri;
    }

    public NimbusJwtDecoder decoder(){
        return decoder(jwksUri());
    }

    /** Child-process decoder: only a loopback JWKS URI, never a private key. */
    public static NimbusJwtDecoder decoder(URI uri){
        Objects.requireNonNull(uri,"JWKS URI");
        if(!"http".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||
                uri.getPort()<1||!"/jwks".equals(uri.getPath())||
                uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("Test JWKS must be local loopback /jwks");
        var decoder=NimbusJwtDecoder.withJwkSetUri(uri.toString()).build();
        OidcTestDecoderSupport.configure(decoder,ISSUER,AUDIENCE);
        return decoder;
    }

    public String access(String subject) throws Exception {
        return issue(subject,"Bearer",ISSUER,AUDIENCE,
                Instant.now().plusSeconds(600).getEpochSecond(),SigningKey.TRUSTED,
                null,null,null);
    }

    /** Deliberately supports invalid claim/signature variants for negative tests. */
    public synchronized String issue(String subject,String purpose,String issuer,String audience,
                                     long expires,SigningKey signingKey,String tokenUse,
                                     UUID spoofedUser,UUID spoofedTenant) throws Exception {
        ensureOpen();
        Objects.requireNonNull(signingKey,"signing key");
        long issued=Math.min(Instant.now().getEpochSecond()-10,expires-300);
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience(List.of(audience))
                .subject(subject).claim("azp","rm-seller-web")
                .issueTime(Date.from(Instant.ofEpochSecond(issued)))
                .notBeforeTime(Date.from(Instant.ofEpochSecond(issued)))
                .expirationTime(Date.from(Instant.ofEpochSecond(expires)));
        if(purpose!=null)claims.claim("typ",purpose);
        if(tokenUse!=null)claims.claim("token_use",tokenUse);
        if(spoofedUser!=null)claims.claim("user_id",spoofedUser.toString());
        if(spoofedTenant!=null)claims.claim("tenant_id",spoofedTenant.toString());
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(kid).build(),claims.build());
        // Same kid in the untrusted case: rejection must be cryptographic.
        jwt.sign(new RSASSASigner((signingKey==SigningKey.TRUSTED?trusted:untrusted).getPrivate()));
        return jwt.serialize();
    }

    private void ensureOpen(){
        if(closed)throw new IllegalStateException("Test authority is closed");
    }

    @Override public synchronized void close(){
        if(!closed){
            closed=true;
            server.stop(0);
        }
    }
}
