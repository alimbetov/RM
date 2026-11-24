package kz.rm.fx.auto;

import kz.rm.iam.*;
import kz.rm.organization.TenantCurrencyPolicyService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpServer;
import kz.rm.catalog.CatalogItem;
import kz.rm.catalog.CatalogItemService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real signed access token to transactional PRODUCT consent admission. */
@SpringBootTest(properties = {
        "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class FxAutoOwnerAdmissionQualificationTest {
    private static final String ISSUER="https://issuer.rm.example.test/realms/rm";
    private static final String AUDIENCE="rm-api";
    private static HttpServer jwks;
    private static KeyPair trusted;
    private static KeyPair untrusted;
    private static NimbusJwtDecoder decoder;
    private static String kid;

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired FxAutoOwnerConsentService consent;
    @Autowired TenantCurrencyPolicyService currencies;
    @Autowired PlatformTransactionManager txManager;

    private record Tenant(UUID id,UUID org,UUID owner,UUID member) {}

    @BeforeAll static void localJwks() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trusted=generator.generateKeyPair();
        untrusted=generator.generateKeyPair();
        kid="rm-iam-seller-"+UUID.randomUUID();
        var publicKey=(RSAPublicKey)trusted.getPublic();
        String jwk="{\"keys\":[{\"kty\":\"RSA\",\"kid\":\""+kid
                +"\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                +unsigned(publicKey.getModulus())+"\",\"e\":\""
                +unsigned(publicKey.getPublicExponent())+"\"}]}";
        jwks=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        jwks.createContext("/jwks",exchange->{
            byte[] payload=jwk.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,payload.length);
            try(var body=exchange.getResponseBody()){body.write(payload);}
        });
        jwks.start();
        decoder=NimbusJwtDecoder.withJwkSetUri(
                "http://127.0.0.1:"+jwks.getAddress().getPort()+"/jwks").build();
        OidcJwtConfiguration.configureValidation(decoder,ISSUER,AUDIENCE);
    }

    @AfterAll static void closeJwks() {
        if(jwks!=null)jwks.stop(0);
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes=value.toByteArray();
        return b64(bytes.length>1 && bytes[0]==0
                ?java.util.Arrays.copyOfRange(bytes,1,bytes.length):bytes);
    }

    private static String signed(String subject,String purpose,String issuer,String audience,
                                 long expires,PrivateKey signingKey,String tokenUse,
                                 UUID spoofedUser,UUID spoofedTenant) throws Exception {
        long now=Instant.now().getEpochSecond();
        String header="{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\""+kid+"\"}";
        String body="{\"iss\":\""+issuer+"\",\"aud\":[\""+audience+"\"],\"sub\":\""
                +subject+"\",\"azp\":\"rm-seller-web\",\"iat\":"+(now-10)
                +",\"nbf\":"+(now-10)+",\"exp\":"+expires
                +(purpose==null?"":",\"typ\":\""+purpose+"\"")
                +(tokenUse==null?"":",\"token_use\":\""+tokenUse+"\"")
                +(spoofedUser==null?"":",\"user_id\":\""+spoofedUser+"\"")
                +(spoofedTenant==null?"":",\"tenant_id\":\""+spoofedTenant+"\"")+"}";
        String joined=b64(header.getBytes(StandardCharsets.UTF_8))+"."
                +b64(body.getBytes(StandardCharsets.UTF_8));
        Signature signature=Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(joined.getBytes(StandardCharsets.US_ASCII));
        return joined+"."+b64(signature.sign());
    }

    private String subject(UUID user) {
        return jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,user);
    }

    private String access(UUID user) throws Exception {
        return signed(subject(user),"Bearer",ISSUER,AUDIENCE,
                Instant.now().plusSeconds(600).getEpochSecond(),trusted.getPrivate(),
                null,null,null);
    }

    private VerifiedActor verified(UUID user) throws Exception {
        return new OidcActorResolver(decoder,users).resolve(access(user));
    }

    private UUID account() {
        return users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"iam-seller-r0-"+UUID.randomUUID(),"IAM seller test")).getId();
    }

    private Tenant tenant(UUID staff) {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"JWT Business LLP","OIDC tenant","KZ"));
        UUID workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=account();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",workspace);
        return new Tenant(workspace,org.getId(),owner,staff);
    }

    private UUID grants(Tenant tenant,UUID assignee,DomainPermission... grants) throws Exception {
        VerifiedActor owner=verified(tenant.owner());
        var role=roles.createRole(owner,tenant.id(),
                "JWTR0_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "JWT seller admission role");
        for(var permission:grants)roles.grant(owner,tenant.id(),role.id(),permission);
        roles.assign(owner,tenant.id(),assignee,role.id());
        roles.enable(owner,tenant.id());
        return role.id();
    }


    private void pg() throws Exception {
        try(var connection=dataSource.getConnection()){
            Assumptions.assumeTrue("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()));
        }
    }
    private void enable(Tenant t) throws Exception {
        var owner=verified(t.owner());
        currencies.initialize(owner,t.id());
        currencies.setPricingAnchorEnabled(owner,t.id(),"USD",true,0);
    }
    private void admit(VerifiedActor actor,Tenant t,String... codes) {
        new TransactionTemplate(txManager).executeWithoutResult(s->
            consent.requireProductConsentAuthority(actor,t.id(),java.util.Arrays.asList(codes)));
    }
    private void noWrites(Tenant t) {
        for(String table:java.util.List.of("fx_auto_policy_revision","fx_auto_approval_evidence","fx_auto_policy_activation","fx_auto_simulation","store_offer_price_revision"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm."+table+" WHERE tenant_id=?",Integer.class,t.id())).isZero();
    }
    @Test void ownerAndDomainGrantAreBothMandatory() throws Exception {
        pg();var t=tenant(account());enable(t);
        var owner=verified(t.owner());var staff=verified(t.member());
        assertThatThrownBy(()->admit(owner,t,"USD")).isInstanceOf(SecurityException.class);
        grants(t,t.member(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        assertThatThrownBy(()->admit(staff,t,"USD")).isInstanceOf(SecurityException.class);
        grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        admit(owner,t,"USD"); noWrites(t);
    }
    @Test void signedClaimsCannotOverrideIdentityOrTenant() throws Exception {
        pg();var t=tenant(account());var other=tenant(account());enable(t);enable(other);
        grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var token=signed(subject(t.member()),"Bearer",ISSUER,AUDIENCE,Instant.now().plusSeconds(600).getEpochSecond(),trusted.getPrivate(),null,t.owner(),t.id());
        var actor=new OidcActorResolver(decoder,users).resolve(token);
        assertThat(actor.userId()).isEqualTo(t.member());
        assertThatThrownBy(()->admit(actor,t,"USD")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->admit(verified(t.owner()),other,"USD")).isInstanceOf(SecurityException.class);
        noWrites(t);noWrites(other);
    }
    @Test void invalidSignedTokensNeverReachAdmission() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var resolver=new OidcActorResolver(decoder,users);String sub=subject(t.owner());
        long valid=Instant.now().plusSeconds(600).getEpochSecond();
        String[] denied={
            signed(sub,null,ISSUER,AUDIENCE,valid,trusted.getPrivate(),null,null,null),
            signed(sub,"ID",ISSUER,AUDIENCE,valid,trusted.getPrivate(),null,null,null),
            signed(sub,"Bearer",ISSUER,AUDIENCE,valid,trusted.getPrivate(),"refresh",null,null),
            signed(sub,"Bearer",ISSUER,"wrong-api",valid,trusted.getPrivate(),null,null,null),
            signed(sub,"Bearer","https://wrong.test",AUDIENCE,valid,trusted.getPrivate(),null,null,null),
            signed(sub,"Bearer",ISSUER,AUDIENCE,valid,untrusted.getPrivate(),null,null,null),
            signed(sub,"Bearer",ISSUER,AUDIENCE,Instant.now().minusSeconds(300).getEpochSecond(),trusted.getPrivate(),null,null,null)
        };
        for(String token:denied)assertThatThrownBy(()->resolver.resolve(token)).isInstanceOf(SecurityException.class);
        admit(resolver.resolve(access(t.owner())),t,"USD");noWrites(t);
    }
    @Test void grantRevocationAfterTokenDeniesAdmission() throws Exception {
        pg();var t=tenant(account());enable(t);var role=grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());admit(actor,t,"USD");
        roles.revoke(actor,t.id(),role,DomainPermission.STOREFRONT_OFFER_MANAGE);
        assertThatThrownBy(()->admit(actor,t,"USD")).isInstanceOf(SecurityException.class);
        noWrites(t);
    }
    @Test void anchorRevocationAndCompleteCurrencyScope() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());admit(actor,t,"USD");
        assertThatThrownBy(()->admit(actor,t,"USD","EUR")).hasMessage("TENANT_CURRENCY_PURPOSE_NOT_ALLOWED");
        assertThatThrownBy(()->admit(actor,t)).hasMessage("FX_AUTO_ANCHOR_SCOPE_REQUIRED");
        assertThatThrownBy(()->admit(actor,t,"RUB")).hasMessage("FX_AUTO_ANCHOR_SCOPE_INVALID");
        currencies.setPricingAnchorEnabled(actor,t.id(),"USD",false,1);
        assertThatThrownBy(()->admit(actor,t,"USD")).hasMessage("TENANT_CURRENCY_PURPOSE_NOT_ALLOWED");
        noWrites(t);
    }
    @Test void activeAccountTenantAndMembershipRechecked() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());
        jdbc.update("UPDATE public.iam_workspace SET status='SUSPENDED' WHERE id=?",t.id());
        assertThatThrownBy(()->admit(actor,t,"USD")).isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",t.id());
        jdbc.update("UPDATE public.iam_org_membership SET status='SUSPENDED' WHERE organization_id=? AND user_id=?",t.org(),t.owner());
        assertThatThrownBy(()->admit(actor,t,"USD")).isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE public.iam_org_membership SET status='ACTIVE' WHERE organization_id=? AND user_id=?",t.org(),t.owner());
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",t.owner());
        assertThatThrownBy(()->admit(actor,t,"USD")).isInstanceOf(SecurityException.class);
        noWrites(t);
    }
    @Test void mandatoryTransactionAndNullActor() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());
        assertThatThrownBy(()->consent.requireProductConsentAuthority(actor,t.id(),java.util.List.of("USD"))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->admit(null,t,"USD")).isInstanceOf(SecurityException.class);
        noWrites(t);
    }

    @Test void concurrentGrantRevocationBothOrders() throws Exception {
        pg();
        for(boolean admitFirst:new boolean[]{true,false}) {
            var t=tenant(account());enable(t);
            var role=grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
            var actor=verified(t.owner());
            var entered=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            var pid=new java.util.concurrent.CompletableFuture<Integer>();
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first=pool.submit(()->new TransactionTemplate(txManager).executeWithoutResult(s->{
                    if(admitFirst)consent.requireProductConsentAuthority(actor,t.id(),java.util.List.of("USD"));
                    else roles.revoke(actor,t.id(),role,DomainPermission.STOREFRONT_OFFER_MANAGE);
                    entered.countDown();
                    try {if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("race timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                }));
                try {
                    assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    var second=pool.submit(()->new TransactionTemplate(txManager).executeWithoutResult(s->{
                        pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                        if(admitFirst)roles.revoke(actor,t.id(),role,DomainPermission.STOREFRONT_OFFER_MANAGE);
                        else consent.requireProductConsentAuthority(actor,t.id(),java.util.List.of("USD"));
                    }));
                    int backend=pid.get(5,java.util.concurrent.TimeUnit.SECONDS);
                    boolean blocked=false;
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while(System.nanoTime()<deadline&&!second.isDone()){
                        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,backend))){blocked=true;break;}
                        Thread.sleep(20);
                    }
                    assertThat(blocked).as("actual competing backend blocked").isTrue();
                    release.countDown();
                    first.get(10,java.util.concurrent.TimeUnit.SECONDS);
                    if(admitFirst)second.get(10,java.util.concurrent.TimeUnit.SECONDS);
                    else assertThatThrownBy(()->second.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(SecurityException.class);
                    assertThatThrownBy(()->admit(actor,t,"USD")).isInstanceOf(SecurityException.class);
                    noWrites(t);
                } finally {release.countDown();}
            }
        }
    }
}
