package kz.rm.fx.auto;

import kz.rm.iam.*;
import kz.rm.organization.TenantCurrencyPolicyService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpServer;
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
    @Autowired FxAutoPolicyEnrollmentService enrollment;
    @Autowired FxAutoDraftApprovalService approvals;
    @Autowired FxAutoConsentRevocationService revocations;
    @Autowired FxAutoPersistentDryRunService simulations;
    @Autowired FxAutoTenantBudgetLedger budgetLedger;
    @Autowired kz.rm.storefront.StorefrontOfferService storefront;
    @Autowired kz.rm.fx.NbrkRateRevisionWriter rateWriter;
    @Autowired kz.rm.fx.FxCalendarEvidenceService calendar;
    @Autowired TenantOwnershipTransferService transfers;
    @Autowired TenantAccessStatusService accessStatus;
    @Autowired TenantCurrencyPolicyService currencies;
    @Autowired PlatformTransactionManager txManager;

    private record Tenant(UUID id,UUID org,UUID owner,UUID member,UUID provisioningOwner) {}

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
        OidcTestDecoderSupport.configure(decoder,ISSUER,AUDIENCE);
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
        UUID provisioningOwner=account();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),provisioningOwner,OrganizationMembershipEntity.Role.OWNER));
        return new Tenant(workspace,org.getId(),owner,staff,provisioningOwner);
    }

    private UUID grants(Tenant tenant,UUID assignee,DomainPermission... grants) throws Exception {
        VerifiedActor owner=verified(tenant.provisioningOwner());
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

    @Test void ownershipTransferDoesNotTransferDomainGrant() throws Exception {
        pg();var t=tenant(account());enable(t);
        var role=grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var former=verified(t.owner());var successor=verified(t.member());
        admit(former,t,"USD");
        var request=transfers.requestTransfer(former,t.id(),t.member());
        assertThat(transfers.acceptTransfer(successor,t.id(),request.id()).state()).isEqualTo("ACCEPTED");
        assertThatThrownBy(()->admit(former,t,"USD")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->admit(successor,t,"USD")).isInstanceOf(SecurityException.class);
        roles.assign(verified(t.provisioningOwner()),t.id(),t.member(),role);
        admit(successor,t,"USD");
        assertThatThrownBy(()->admit(former,t,"USD")).isInstanceOf(SecurityException.class);
        noWrites(t);
    }

    @Test void adminAndPlatformAdminCannotSubstituteForOwner() throws Exception {
        pg();var t=tenant(account());enable(t);
        grants(t,t.member(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var staff=verified(t.member());
        jdbc.update("UPDATE public.iam_org_membership SET role='ADMIN' WHERE organization_id=? AND user_id=?",t.org(),t.member());
        assertThatThrownBy(()->admit(staff,t,"USD")).isInstanceOf(SecurityException.class);
        jdbc.update("INSERT INTO public.iam_global_role_assignment(id,user_id,role,granted_at) VALUES (?,?,'PLATFORM_ADMIN',CURRENT_TIMESTAMP)",UUID.randomUUID(),t.member());
        assertThatThrownBy(()->admit(staff,t,"USD")).isInstanceOf(SecurityException.class);
        noWrites(t);
    }

    @Test void principalExpiresWhileWaitingForCurrencyLock() throws Exception {
        pg();var t=tenant(account());enable(t);
        grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        try(var blocker=dataSource.getConnection();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try(var stmt=blocker.prepareStatement("SELECT tenant_id FROM public.tenant_currency_policy WHERE tenant_id=? FOR UPDATE")){
                stmt.setObject(1,t.id());try(var rs=stmt.executeQuery()){assertThat(rs.next()).isTrue();}
            }
            try {
                long expiry=Instant.now().plusSeconds(8).getEpochSecond();
                var actor=new OidcActorResolver(decoder,users).resolve(signed(subject(t.owner()),"Bearer",ISSUER,AUDIENCE,expiry,trusted.getPrivate(),null,null,null));
                var pid=new java.util.concurrent.CompletableFuture<Integer>();
                var waiting=pool.submit(()->new TransactionTemplate(txManager).executeWithoutResult(s->{
                    pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    consent.requireProductConsentAuthority(actor,t.id(),java.util.List.of("USD"));
                }));
                int backend=pid.get(3,java.util.concurrent.TimeUnit.SECONDS);
                boolean blocked=false;
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<deadline&&!waiting.isDone()){
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,backend))){blocked=true;break;}
                    Thread.sleep(20);
                }
                assertThat(blocked).isTrue();
                while(Instant.now().isBefore(Instant.ofEpochSecond(expiry)))Thread.sleep(25);
                blocker.commit();
                assertThatThrownBy(()->waiting.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseMessage("Authenticated principal expired");
                admit(verified(t.owner()),t,"USD");
                noWrites(t);
            } finally {blocker.rollback();}
        }
    }

    // The mutation and admission are real services; the test controls only commit order.
    private void lifecycleRace(Tenant t,VerifiedActor actor,Runnable mutation,boolean admitFirst) throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var pid=new java.util.concurrent.CompletableFuture<Integer>();
        Runnable admission=()->consent.requireProductConsentAuthority(actor,t.id(),java.util.List.of("USD"));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->new TransactionTemplate(txManager).executeWithoutResult(s->{
                jdbc.execute("SET LOCAL lock_timeout='12s'");
                (admitFirst?admission:mutation).run();
                entered.countDown();
                try {
                    if(!release.await(12,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("race timeout");
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            try {
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second=pool.submit(()->new TransactionTemplate(txManager).executeWithoutResult(s->{
                    jdbc.execute("SET LOCAL lock_timeout='12s'");
                    pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    (admitFirst?mutation:admission).run();
                }));
                int backend=pid.get(5,java.util.concurrent.TimeUnit.SECONDS);
                boolean blocked=false;
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline&&!second.isDone()){
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,backend))){blocked=true;break;}
                    Thread.sleep(20);
                }
                assertThat(blocked).as("exact backend waits for competing lifecycle transaction").isTrue();
                release.countDown();
                first.get(10,java.util.concurrent.TimeUnit.SECONDS);
                if(admitFirst)second.get(10,java.util.concurrent.TimeUnit.SECONDS);
                else assertThatThrownBy(()->second.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(SecurityException.class);
                assertThatThrownBy(()->admit(actor,t,"USD")).isInstanceOf(SecurityException.class);
                noWrites(t);
            } finally {release.countDown();}
        }
    }

    @Test void ownershipTransferAndAdmissionBothOrders() throws Exception {
        pg();
        for(boolean admitFirst:new boolean[]{true,false}){
            var t=tenant(account());enable(t);
            grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
            var former=verified(t.owner());var successor=verified(t.member());
            var request=transfers.requestTransfer(former,t.id(),t.member());
            lifecycleRace(t,former,()->transfers.acceptTransfer(successor,t.id(),request.id()),admitFirst);
            assertThatThrownBy(()->admit(successor,t,"USD")).isInstanceOf(SecurityException.class);
        }
    }

    @Test void ownerMembershipSuspensionAndAdmissionBothOrders() throws Exception {
        pg();
        for(boolean admitFirst:new boolean[]{true,false}){
            var t=tenant(account());enable(t);
            grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
            // Fixture: two active owners, so the real suspension command is legal.
            jdbc.update("UPDATE public.iam_org_membership SET role='OWNER' WHERE organization_id=? AND user_id=?",t.org(),t.member());
            var actor=verified(t.owner());var other=verified(t.member());
            lifecycleRace(t,actor,()->accessStatus.suspendMember(other,t.id(),t.owner()),admitFirst);
            assertThat(jdbc.queryForObject("SELECT status FROM public.iam_org_membership WHERE organization_id=? AND user_id=?",String.class,t.org(),t.owner())).isEqualTo("SUSPENDED");
        }
    }

    @Test void restoreWaitsForTargetAccountWithoutHoldingWorkspace() throws Exception {
        pg();var t=tenant(account());enable(t);
        grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var target=verified(t.owner());var restorer=verified(t.provisioningOwner());
        accessStatus.suspendMember(restorer,t.id(),t.owner());
        try(var blocker=dataSource.getConnection();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM public.iam_user_account WHERE id=? FOR UPDATE")){
                lock.setObject(1,t.owner());try(var rows=lock.executeQuery()){assertThat(rows.next()).isTrue();}
            }
            try {
                var pid=new java.util.concurrent.CompletableFuture<Integer>();
                var restore=pool.submit(()->new TransactionTemplate(txManager).executeWithoutResult(s->{
                    jdbc.execute("SET LOCAL lock_timeout='12s'");
                    pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    accessStatus.restoreMember(restorer,t.id(),t.owner());
                }));
                int backend=pid.get(5,java.util.concurrent.TimeUnit.SECONDS);
                boolean blocked=false;
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline&&!restore.isDone()){
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,backend))){blocked=true;break;}
                    Thread.sleep(20);
                }
                assertThat(blocked).isTrue();
                // A separate session holding the target must still be able to
                // acquire workspace SHARE: the old implementation deadlocks here.
                try(var lock=blocker.prepareStatement("SELECT id FROM public.iam_workspace WHERE id=? FOR SHARE NOWAIT")){
                    lock.setObject(1,t.id());try(var rows=lock.executeQuery()){assertThat(rows.next()).isTrue();}
                }
                blocker.commit();
                restore.get(10,java.util.concurrent.TimeUnit.SECONDS);
                admit(target,t,"USD");noWrites(t);
            } finally {blocker.rollback();}
        }
    }

    private FxAutoPolicyEnrollmentService.Target draftTarget(Tenant t,String currency) {
        UUID item=UUID.randomUUID(),offer=UUID.randomUUID(),anchor=UUID.randomUUID(),guard=UUID.randomUUID();
        if(jdbc.queryForObject("SELECT count(*) FROM crm.store_front WHERE tenant_id=?",Integer.class,t.id())==0)
            jdbc.update("INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at) VALUES (?,?,?,'PUBLISHED',now())",t.id(),UUID.randomUUID(),"draft-"+UUID.randomUUID());
        jdbc.update("INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at) VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',now())",t.id(),item,"draft-"+item);
        jdbc.update("INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state,catalog_item_id,row_version) VALUES (?,?,'FX draft',100,'KZT','PUBLISHED',?,0)",t.id(),offer,item);
        jdbc.update("INSERT INTO crm.sku_fx_policy_version(tenant_id,id,offer_id,revision,anchor_currency,anchor_amount,buffer_pct,markup_pct,rounding_step_kzt,approved_by,created_at) VALUES (?,?,?,1,?,1,0,0,1,?,now())",t.id(),anchor,offer,currency,t.owner());
        jdbc.update("INSERT INTO crm.sku_fx_guardrail_version(tenant_id,id,offer_id,revision,max_increase_pct,max_decrease_pct,min_price_kzt,max_price_kzt,max_rate_calendar_days,calculation_ttl_seconds,approved_by,created_at) VALUES (?,?,?,1,10,10,1,1000,3,60,?,now())",t.id(),guard,offer,t.owner());
        return new FxAutoPolicyEnrollmentService.Target(offer,anchor,guard,
                Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                "EA",FxAutoPolicyEnrollmentService.TaxBasis.NONE);
    }
    private FxAutoPolicyEnrollmentService.Command draftCommand(UUID policy,long expected,String key,
            FxAutoPolicyEnrollmentService.Target... targets) {
        var n=java.math.BigDecimal.TEN;
        return new FxAutoPolicyEnrollmentService.Command(policy,expected,key,
                new FxAutoPolicyEnrollmentService.Limits(86400,n,n,n,10,86400,n,10,n,10,100),
                java.util.List.of(targets));
    }
    @Test void draftPersistsCanonicalDigestAndExactReplayWithoutCommercialWrites() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var a=draftTarget(t,"USD");var b=draftTarget(t,"USD");
        UUID policy=UUID.randomUUID();
        var command=draftCommand(policy,0,"draft-1",a,b);
        var first=enrollment.createPolicyRevision(actor,t.id(),command);
        assertThat(enrollment.createPolicyRevision(actor,t.id(),draftCommand(policy,0,"draft-1",b,a))).isEqualTo(first);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT crm.fx_auto_sha_v1(CAST(payload AS jsonb))=payload_sha256 FROM crm.fx_auto_policy_revision WHERE tenant_id=? AND id=?",Boolean.class,t.id(),first.revisionId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_target_enrollment WHERE tenant_id=? AND policy_revision_id=?",Integer.class,t.id(),first.revisionId())).isEqualTo(2);
        assertThatThrownBy(()->enrollment.createPolicyRevision(actor,t.id(),draftCommand(policy,0,"draft-1",a))).hasMessageContaining("FX_AUTO_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->enrollment.createPolicyRevision(actor,t.id(),draftCommand(policy,0,"draft-2",a))).hasMessageContaining("FX_AUTO_STALE_POLICY_REVISION");
        assertThat(enrollment.createPolicyRevision(actor,t.id(),draftCommand(policy,1,"draft-2",a)).revision()).isEqualTo(2);
        for(String table:java.util.List.of("fx_auto_approval_evidence","fx_auto_policy_activation","fx_auto_simulation","store_offer_price_revision"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm."+table+" WHERE tenant_id=?",Integer.class,t.id())).isZero();
    }
    @Test void draftRejectsForeignBindingsMissingCurrencyAndInvalidTargetWithoutPartialWrites() throws Exception {
        pg();var t=tenant(account());var foreign=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var usd=draftTarget(t,"USD");var eur=draftTarget(t,"EUR");var other=draftTarget(foreign,"USD");
        assertThatThrownBy(()->enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"foreign",usd,other))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"currency",usd,eur))).isInstanceOfAny(SecurityException.class,IllegalStateException.class,IllegalArgumentException.class);
        jdbc.update("UPDATE crm.store_offer SET state='HIDDEN' WHERE tenant_id=? AND id=?",t.id(),usd.offerId());
        assertThatThrownBy(()->enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"hidden",usd))).hasMessageContaining("FX_AUTO_TARGET_BINDING_INVALID");
        noWrites(t);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_limit_policy_version WHERE tenant_id=?",Integer.class,t.id())).isZero();
    }
    @Test void concurrentDraftReplayCreatesOneRevisionAcrossTransactions() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var command=draftCommand(UUID.randomUUID(),0,"race",draftTarget(t,"USD"));
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return enrollment.createPolicyRevision(actor,t.id(),command);});
            var b=pool.submit(()->{start.await();return enrollment.createPolicyRevision(actor,t.id(),command);});
            start.countDown();
            assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_policy_revision WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_target_enrollment WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(1);
    }

    private FxAutoDraftApprovalService.Command approvalCommand(Tenant t,FxAutoPolicyEnrollmentService.Receipt draft,String key) {
        String scope=jdbc.queryForObject("SELECT crm.fx_auto_scope_sha_v1(?,?)",String.class,t.id(),draft.revisionId());
        return new FxAutoDraftApprovalService.Command(draft.revisionId(),key,draft.payloadSha256(),scope,
                Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    @Test void approvalSealsExactDraftAndCreatesOnlyPausedActivation() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var target=draftTarget(t,"USD");
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft",target));
        var command=approvalCommand(t,draft,"approve");
        var receipt=approvals.approve(actor,t.id(),command);
        assertThat(approvals.approve(actor,t.id(),command)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT state FROM crm.fx_auto_policy_activation WHERE tenant_id=? AND policy_id=?",String.class,t.id(),draft.policyId())).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT execution_epoch FROM crm.fx_auto_policy_activation WHERE tenant_id=? AND policy_id=?",Long.class,t.id(),draft.policyId())).isZero();
        assertThat(jdbc.queryForObject("SELECT sealed FROM crm.fx_auto_scope_guard WHERE tenant_id=? AND policy_revision_id=?",Boolean.class,t.id(),draft.revisionId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.store_offer_price_revision WHERE tenant_id=?",Integer.class,t.id())).isZero();
        assertThatThrownBy(()->approvals.approve(actor,t.id(),new FxAutoDraftApprovalService.Command(command.revisionId(),command.requestKey(),command.payloadSha256(),command.scopeSha256(),command.expiresAt().plusSeconds(1)))).hasMessageContaining("FX_AUTO_IDEMPOTENCY_CONFLICT");
    }
    @Test void approvalRejectsWrongDigestAndNonOwnerWithoutEvidence() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft",draftTarget(t,"USD")));
        var command=approvalCommand(t,draft,"approve");
        assertThatThrownBy(()->approvals.approve(actor,t.id(),new FxAutoDraftApprovalService.Command(command.revisionId(),command.requestKey(),"a".repeat(64),command.scopeSha256(),command.expiresAt()))).hasMessageContaining("FX_AUTO_APPROVAL_DIGEST_MISMATCH");
        var member=verified(t.member());
        assertThatThrownBy(()->approvals.approve(member,t.id(),command)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_approval_evidence WHERE tenant_id=?",Integer.class,t.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_policy_activation WHERE tenant_id=?",Integer.class,t.id())).isZero();
    }

    private FxAutoDraftApprovalService.Receipt approvedDraft(Tenant t,VerifiedActor actor) {
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft",draftTarget(t,"USD")));
        return approvals.approve(actor,t.id(),approvalCommand(t,draft,"approve"));
    }
    @Test void revokeIsTerminalAndIdempotentEvenAfterCurrencyPermissionRemoved() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var approved=approvedDraft(t,actor);
        currencies.setPricingAnchorEnabled(actor,t.id(),"USD",false,1);
        var command=new FxAutoConsentRevocationService.Command(approved.policyId(),approved.approvalId(),0);
        var receipt=revocations.revoke(actor,t.id(),command);
        assertThat(receipt.state()).isEqualTo("REVOKED");
        assertThat(receipt.executionEpoch()).isEqualTo(1);
        assertThat(revocations.revoke(actor,t.id(),command)).isEqualTo(receipt);
        assertThatThrownBy(()->revocations.revoke(actor,t.id(),new FxAutoConsentRevocationService.Command(approved.policyId(),approved.approvalId(),1))).hasMessageContaining("FX_AUTO_STALE_EXECUTION_EPOCH");
        assertThatThrownBy(()->jdbc.update("UPDATE crm.fx_auto_policy_activation SET state='PAUSED',execution_epoch=2 WHERE tenant_id=? AND policy_id=?",t.id(),approved.policyId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_revocation_evidence WHERE tenant_id=? AND policy_id=?",Integer.class,t.id(),approved.policyId())).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("DELETE FROM crm.fx_auto_revocation_evidence WHERE tenant_id=? AND policy_id=?",t.id(),approved.policyId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void concurrentRevocationHasOneFenceAndRejectsForeignOrNonOwner() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var approved=approvedDraft(t,actor);
        var command=new FxAutoConsentRevocationService.Command(approved.policyId(),approved.approvalId(),0);
        var member=verified(t.member());var other=tenant(account());var foreign=verified(other.owner());
        assertThatThrownBy(()->revocations.revoke(member,t.id(),command)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->revocations.revoke(foreign,t.id(),command)).isInstanceOf(SecurityException.class);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return revocations.revoke(actor,t.id(),command);});
            var b=pool.submit(()->{start.await();return revocations.revoke(actor,t.id(),command);});
            start.countDown();
            assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_revocation_evidence WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT execution_epoch FROM crm.fx_auto_policy_activation WHERE tenant_id=? AND policy_id=?",Long.class,t.id(),approved.policyId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.store_offer_price_revision WHERE tenant_id=?",Integer.class,t.id())).isZero();
    }

    @Test void lifecycleOwnershipTransferNeverInheritsApprovalAndSuccessorCanStop() throws Exception {
        pg();var t=tenant(account());enable(t);var role=grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var former=verified(t.owner());var successor=verified(t.member());
        var draft=enrollment.createPolicyRevision(former,t.id(),draftCommand(UUID.randomUUID(),0,"draft",draftTarget(t,"USD")));
        var command=approvalCommand(t,draft,"approve");
        var approved=approvals.approve(former,t.id(),command);
        var transfer=transfers.requestTransfer(former,t.id(),t.member());
        transfers.acceptTransfer(successor,t.id(),transfer.id());
        roles.assign(verified(t.provisioningOwner()),t.id(),t.member(),role);
        assertThatThrownBy(()->approvals.approve(former,t.id(),command)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->approvals.approve(successor,t.id(),command)).isInstanceOf(SecurityException.class);
        var stop=new FxAutoConsentRevocationService.Command(approved.policyId(),approved.approvalId(),0);
        assertThatThrownBy(()->revocations.revoke(former,t.id(),stop)).isInstanceOf(SecurityException.class);
        assertThat(revocations.revoke(successor,t.id(),stop).executionEpoch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM crm.fx_auto_revocation_evidence WHERE tenant_id=? AND policy_id=?",UUID.class,t.id(),approved.policyId())).isEqualTo(t.member());
    }

    @Test void lifecycleApprovalAndGrantRevocationSerializeBothOrders() throws Exception {
        pg();
        for(boolean approveFirst:new boolean[]{true,false}){
            var t=tenant(account());enable(t);var role=grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
            var actor=verified(t.owner());
            var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft",draftTarget(t,"USD")));
            var command=approvalCommand(t,draft,"approve");
            var locked=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            var pid=new java.util.concurrent.CompletableFuture<Integer>();
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
                var first=pool.submit(()->new TransactionTemplate(txManager).executeWithoutResult(s->{
                    jdbc.execute("SET LOCAL lock_timeout='12s'");
                    if(approveFirst)approvals.approve(actor,t.id(),command);
                    else roles.revoke(actor,t.id(),role,DomainPermission.STOREFRONT_OFFER_MANAGE);
                    locked.countDown();
                    try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                }));
                try {
                    assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    var second=pool.submit(()->{
                        try {
                            new TransactionTemplate(txManager).executeWithoutResult(s->{
                                jdbc.execute("SET LOCAL lock_timeout='12s'");
                                pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                                if(approveFirst)roles.revoke(actor,t.id(),role,DomainPermission.STOREFRONT_OFFER_MANAGE);
                                else approvals.approve(actor,t.id(),command);
                            });
                            return true;
                        } catch(SecurityException denied){return false;}
                    });
                    int backend=pid.get(5,java.util.concurrent.TimeUnit.SECONDS);
                    boolean blocked=false;
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while(System.nanoTime()<deadline&&!second.isDone()){
                        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,backend))){blocked=true;break;}
                        Thread.sleep(20);
                    }
                    assertThat(blocked).isTrue();
                    release.countDown();
                    first.get(12,java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(second.get(12,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(approveFirst);
                } finally {release.countDown();}
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_approval_evidence WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(approveFirst?1:0);
            assertThatThrownBy(()->approvals.approve(actor,t.id(),command)).isInstanceOf(SecurityException.class);
        }
    }

    @Test void lifecycleRollbackLeavesNoPartialApprovalSealOrRevocation() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft",draftTarget(t,"USD")));
        var command=approvalCommand(t,draft,"approve");
        new TransactionTemplate(txManager).executeWithoutResult(s->{approvals.approve(actor,t.id(),command);s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_approval_evidence WHERE tenant_id=?",Integer.class,t.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_policy_activation WHERE tenant_id=?",Integer.class,t.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT sealed FROM crm.fx_auto_scope_guard WHERE tenant_id=? AND policy_revision_id=?",Boolean.class,t.id(),draft.revisionId())).isFalse();
        var approved=approvals.approve(actor,t.id(),command);
        var stop=new FxAutoConsentRevocationService.Command(approved.policyId(),approved.approvalId(),0);
        new TransactionTemplate(txManager).executeWithoutResult(s->{revocations.revoke(actor,t.id(),stop);s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT state FROM crm.fx_auto_policy_activation WHERE tenant_id=? AND policy_id=?",String.class,t.id(),draft.policyId())).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_revocation_evidence WHERE tenant_id=?",Integer.class,t.id())).isZero();
        revocations.revoke(actor,t.id(),stop);
        assertThat(jdbc.queryForObject("SELECT trim(scope_sha256)=crm.fx_auto_scope_sha_v1(tenant_id,policy_revision_id) FROM crm.fx_auto_approval_evidence WHERE tenant_id=? AND id=?",Boolean.class,t.id(),approved.approvalId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT crm.fx_auto_sha_v1(CAST(payload AS jsonb))=payload_sha256 FROM crm.fx_auto_policy_revision WHERE tenant_id=? AND id=?",Boolean.class,t.id(),draft.revisionId())).isTrue();
    }

    @Test void persistentDiagnosticCalculatesSealsReplaysAndStopsAfterRevocation() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var target=draftTarget(t,"USD");
        jdbc.update("INSERT INTO crm.store_offer_price_revision(tenant_id,id,offer_id,offer_row_version,new_price,new_currency,actor_user_id,recorded_at) VALUES (?,?,?,0,100,'KZT',?,now()-interval '2 days')",t.id(),UUID.randomUUID(),target.offerId(),t.owner());
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft",target));
        var approved=approvals.approve(actor,t.id(),approvalCommand(t,draft,"approval"));
        var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        var amount=new java.math.BigDecimal("500");
        rateWriter.persist(date,java.util.List.of(new kz.rm.fx.FxRateProvider.Rate("USD","KZT",amount,java.math.BigDecimal.ONE,date,"NBRK")),"a".repeat(64));
        UUID source=jdbc.queryForObject("SELECT id FROM crm.fx_official_rate_snapshot WHERE provider_code='NBRK' AND base_currency='USD' AND valid_date=? ORDER BY source_revision DESC LIMIT 1",UUID.class,java.sql.Date.valueOf(date));
        var command=new FxAutoPersistentDryRunService.Command(approved.policyId(),approved.approvalId(),0,"USD",date,source,"sim-1");
        var first=simulations.simulate(actor,t.id(),command);
        assertThat(first.itemCount()).isEqualTo(1);
        assertThat(simulations.simulate(actor,t.id(),command)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT trim(result_sha256)=crm.fx_auto_sim_sha_v1(tenant_id,simulation_id) FROM crm.fx_auto_simulation_seal WHERE tenant_id=? AND simulation_id=?",Boolean.class,t.id(),first.simulationId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT CAST(CAST(input_payload AS jsonb)->>'candidateKzt' AS numeric) FROM crm.fx_auto_simulation_item WHERE tenant_id=? AND simulation_id=?",java.math.BigDecimal.class,t.id(),first.simulationId())).isEqualByComparingTo(amount);
        assertThat(jdbc.queryForObject("SELECT CAST(CAST(input_payload AS jsonb)->>'baselineKzt' AS numeric) FROM crm.fx_auto_simulation_item WHERE tenant_id=? AND simulation_id=?",java.math.BigDecimal.class,t.id(),first.simulationId())).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT outcome FROM crm.fx_auto_simulation_item WHERE tenant_id=? AND simulation_id=?",String.class,t.id(),first.simulationId())).isEqualTo("SOURCE_NOT_ADMITTED");
        assertThat(jdbc.queryForObject("SELECT unit_price FROM crm.store_offer WHERE tenant_id=? AND id=?",java.math.BigDecimal.class,t.id(),target.offerId())).isEqualByComparingTo("100");
        var second=new FxAutoPersistentDryRunService.Command(approved.policyId(),approved.approvalId(),0,"USD",date,source,"sim-rollback");
        new TransactionTemplate(txManager).executeWithoutResult(s->{simulations.simulate(actor,t.id(),second);s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_simulation WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(1);
        revocations.revoke(actor,t.id(),new FxAutoConsentRevocationService.Command(approved.policyId(),approved.approvalId(),0));
        assertThatThrownBy(()->simulations.simulate(actor,t.id(),second)).hasMessageContaining("FX_AUTO_AUTHORITY_HOLD");
    }

    // All rate and calendar inputs below are synthetic fixtures in an isolated
    // PostgreSQL test database; they do not qualify a real external source.
    private UUID diagnosticSource(Tenant t,VerifiedActor actor,java.time.LocalDate date,String price) throws Exception {
        byte[] raw=("isolated calendar fixture "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        String url="https://kase.kz/";
        UUID evidence=calendar.stageSource(url,raw,sha,java.util.List.of(
            new kz.rm.fx.NbrkTradingCalendarPolicy.Day(date,"USD/KZT",kz.rm.fx.NbrkTradingCalendarPolicy.TradingStatus.TRADING,url,sha)));
        calendar.enableForTenant(actor,t.id(),evidence,1);
        rateWriter.persist(date,java.util.List.of(new kz.rm.fx.FxRateProvider.Rate("USD","KZT",new java.math.BigDecimal(price),
            java.math.BigDecimal.ONE,date,"NBRK")),"b".repeat(64));
        return jdbc.queryForObject("SELECT id FROM crm.fx_official_rate_snapshot WHERE provider_code='NBRK' AND base_currency='USD' AND valid_date=? ORDER BY source_revision DESC LIMIT 1",UUID.class,java.sql.Date.valueOf(date));
    }
    private void diagnosticHistory(Tenant t,FxAutoPolicyEnrollmentService.Target target,int initial,int current,boolean insideWindow){
        jdbc.update("INSERT INTO crm.store_offer_price_revision(tenant_id,id,offer_id,offer_row_version,new_price,new_currency,actor_user_id,recorded_at) VALUES (?,?,?,0,?,'KZT',?,now()-interval '2 days')",t.id(),UUID.randomUUID(),target.offerId(),initial,t.owner());
        if(initial!=current){
            jdbc.update("INSERT INTO crm.store_offer_price_revision(tenant_id,id,offer_id,offer_row_version,old_price,new_price,old_currency,new_currency,actor_user_id,recorded_at) VALUES (?,?,?,1,?,?,'KZT','KZT',?,now()-CAST(? AS interval))",t.id(),UUID.randomUUID(),target.offerId(),initial,current,t.owner(),insideWindow?"1 hour":"25 hours");
            jdbc.update("UPDATE crm.store_offer SET unit_price=?,row_version=1 WHERE tenant_id=? AND id=?",current,t.id(),target.offerId());
        }
    }
    @Test void diagnosticAdmittedCalendarAllocatesBudgetDeterministicallyAcrossProducts() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var a=draftTarget(t,"USD");var b=draftTarget(t,"USD");
        diagnosticHistory(t,a,100,100,false);diagnosticHistory(t,b,100,100,false);
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft-budget",b,a));
        var approved=approvals.approve(actor,t.id(),approvalCommand(t,draft,"approve-budget"));
        var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        UUID source=diagnosticSource(t,actor,date,"106");
        var command=new FxAutoPersistentDryRunService.Command(approved.policyId(),approved.approvalId(),0,"USD",date,source,"budget");
        var receipt=simulations.simulate(actor,t.id(),command);
        var rows=jdbc.queryForList("""
            SELECT offer_id,outcome,CAST(input_payload AS jsonb)->>'hypotheticalOutcome' AS hypothetical,
              CAST(CAST(input_payload AS jsonb)->>'hypotheticalDeltaKzt' AS numeric) AS delta,
              CAST(CAST(input_payload AS jsonb)->>'candidateKzt' AS numeric) AS candidate
            FROM crm.fx_auto_simulation_item WHERE tenant_id=? AND simulation_id=? ORDER BY offer_id::text
            """,t.id(),receipt.simulationId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("hypothetical")).isEqualTo("WOULD_UPDATE");
        assertThat((java.math.BigDecimal)rows.get(0).get("delta")).isEqualByComparingTo("6");
        assertThat(rows.get(0).get("outcome")).isEqualTo("BLOCKED_POLICY_CONFLICT");
        assertThat(rows.get(1).get("hypothetical")).isEqualTo("BLOCKED_BUDGET_EXHAUSTED");
        assertThat((java.math.BigDecimal)rows.get(1).get("delta")).isEqualByComparingTo("0");
        for(var row:rows)assertThat((java.math.BigDecimal)row.get("candidate")).isEqualByComparingTo("106");
        assertThat(jdbc.queryForObject("SELECT sum(virtual_delta_kzt) FROM crm.fx_auto_simulation_item WHERE tenant_id=? AND simulation_id=?",java.math.BigDecimal.class,t.id(),receipt.simulationId())).isEqualByComparingTo("0");
    }
    @Test void diagnosticRollingHistoryIncludesInsideWindowAndExcludesEarlierChange() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var inside=draftTarget(t,"USD");var outside=draftTarget(t,"USD");
        diagnosticHistory(t,inside,94,100,true);diagnosticHistory(t,outside,94,100,false);
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft-window",inside,outside));
        var approved=approvals.approve(actor,t.id(),approvalCommand(t,draft,"approve-window"));
        var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        UUID source=diagnosticSource(t,actor,date,"106");
        var receipt=simulations.simulate(actor,t.id(),new FxAutoPersistentDryRunService.Command(approved.policyId(),approved.approvalId(),0,"USD",date,source,"window"));
        assertThat(jdbc.queryForObject("SELECT outcome FROM crm.fx_auto_simulation_item WHERE tenant_id=? AND simulation_id=? AND offer_id=?",String.class,t.id(),receipt.simulationId(),inside.offerId())).isEqualTo("BLOCKED_ROLLING_LIMIT");
        assertThat(jdbc.queryForObject("SELECT CAST(input_payload AS jsonb)->>'hypotheticalOutcome' FROM crm.fx_auto_simulation_item WHERE tenant_id=? AND simulation_id=? AND offer_id=?",String.class,t.id(),receipt.simulationId(),outside.offerId())).isEqualTo("WOULD_UPDATE");
    }
    @Test void diagnosticConcurrentExactReplayProducesOneSealedReport() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var target=draftTarget(t,"USD");diagnosticHistory(t,target,100,100,false);
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"draft-race",target));
        var approved=approvals.approve(actor,t.id(),approvalCommand(t,draft,"approve-race"));
        var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        UUID source=diagnosticSource(t,actor,date,"106");
        var command=new FxAutoPersistentDryRunService.Command(approved.policyId(),approved.approvalId(),0,"USD",date,source,"sim-race");
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return simulations.simulate(actor,t.id(),command);});
            var b=pool.submit(()->{start.await();return simulations.simulate(actor,t.id(),command);});
            start.countDown();
            assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_simulation WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_simulation_seal WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(1);
    }

    private FxAutoPolicyEnrollmentService.Target bindContext(Tenant t,VerifiedActor actor,FxAutoPolicyEnrollmentService.Target target){
        var context=storefront.confirmCommercialContext(actor,t.id(),target.offerId(),0,0,"EA",kz.rm.storefront.StorefrontOfferService.TaxBasis.NONE);
        return new FxAutoPolicyEnrollmentService.Target(target.offerId(),target.anchorId(),target.guardrailId(),
            target.reviewDueAt(),target.unitCode(),target.taxBasis(),context.id());
    }
    @Test void commercialBindingRejectsChangedContextWithoutRewritingConsent() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var target=bindContext(t,actor,draftTarget(t,"USD"));
        var draft=enrollment.createPolicyRevision(actor,t.id(),draftCommand(UUID.randomUUID(),0,"bound",target));
        var command=approvalCommand(t,draft,"bound-approval");
        var approved=approvals.approve(actor,t.id(),command);
        storefront.confirmCommercialContext(actor,t.id(),target.offerId(),0,1,"BOX",kz.rm.storefront.StorefrontOfferService.TaxBasis.TAX_INCLUDED);
        assertThatThrownBy(()->approvals.approve(actor,t.id(),command)).hasMessageContaining("FX_AUTO_COMMERCIAL_CONTEXT_STALE");
        assertThat(jdbc.queryForObject("SELECT trim(scope_sha256)=crm.fx_auto_scope_sha_v1(tenant_id,policy_revision_id) FROM crm.fx_auto_approval_evidence WHERE tenant_id=? AND id=?",Boolean.class,t.id(),approved.approvalId())).isTrue();
    }

    private FxAutoPolicyEnrollmentService.Command budgetCommand(UUID policy,long version,String key,int cap,FxAutoPolicyEnrollmentService.Target... targets){
        var n=java.math.BigDecimal.valueOf(cap);
        return new FxAutoPolicyEnrollmentService.Command(policy,version,key,
            new FxAutoPolicyEnrollmentService.Limits(86400,n,n,n,10,86400,n,10,n,10,100),java.util.List.of(targets));
    }

    // Test-only synthetic owner mutation. No production AUTO port or fabricated
    // system identity is registered. Existing owner service writes the price.
    private UUID fixtureBudgetWrite(Tenant t,VerifiedActor actor,FxAutoDraftApprovalService.Receipt approval,
            FxAutoPolicyEnrollmentService.Target target,UUID source,String event){
        return new TransactionTemplate(txManager).execute(status->{
            // Maintain IAM -> currency -> budget -> storefront order.
            consent.requireProductConsentAuthority(actor,t.id(),java.util.List.of("USD"));
            var balance=budgetLedger.balance(t.id(),approval.revisionId());
            UUID replay=jdbc.query("SELECT id FROM crm.fx_auto_execution_receipt WHERE tenant_id=? AND semantic_event_key=?",
                rs->rs.next()?rs.getObject(1,UUID.class):null,t.id(),event);
            if(replay!=null)return replay;
            var delta=new java.math.BigDecimal("60");
            if(!balance.fits(delta))return null;
            storefront.approveSuggestedKzt(actor,t.id(),target.offerId(),new java.math.BigDecimal("160"),0);
            UUID revision=jdbc.queryForObject("SELECT id FROM crm.store_offer_price_revision WHERE tenant_id=? AND offer_id=? AND offer_row_version=1",UUID.class,t.id(),target.offerId());
            UUID consumption=UUID.randomUUID(),receipt=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO crm.fx_auto_budget_consumption(tenant_id,id,offer_id,price_revision_id,policy_revision_id,approval_id,delta_kzt,recorded_at)
                VALUES (?,?,?,?,?,?,?,clock_timestamp())
                """,t.id(),consumption,target.offerId(),revision,approval.revisionId(),approval.approvalId(),delta);
            jdbc.update("""
                INSERT INTO crm.fx_auto_execution_receipt(tenant_id,id,semantic_event_key,consumption_id,offer_id,price_revision_id,
                policy_revision_id,approval_id,commercial_context_id,source_snapshot_id,execution_epoch,recorded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,0,clock_timestamp())
                """,t.id(),receipt,event,consumption,target.offerId(),revision,approval.revisionId(),approval.approvalId(),target.commercialContextId(),source);
            return receipt;
        });
    }
    @Test void budgetTwoSixtyOperationsOneWinnerReplayRollbackAndPolicyChange() throws Exception {
        pg();var t=tenant(account());enable(t);grants(t,t.owner(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(t.owner());var a=bindContext(t,actor,draftTarget(t,"USD"));var b=bindContext(t,actor,draftTarget(t,"USD"));
        diagnosticHistory(t,a,100,100,false);diagnosticHistory(t,b,100,100,false);
        var draft=enrollment.createPolicyRevision(actor,t.id(),budgetCommand(UUID.randomUUID(),0,"budget-draft",100,a,b));
        var approval=approvals.approve(actor,t.id(),approvalCommand(t,draft,"budget-approval"));
        var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        UUID source=diagnosticSource(t,actor,date,"160");
        new TransactionTemplate(txManager).executeWithoutResult(status->{
            assertThat(fixtureBudgetWrite(t,actor,approval,a,source,"c".repeat(64))).isNotNull();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_budget_consumption WHERE tenant_id=?",Integer.class,t.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_execution_receipt WHERE tenant_id=?",Integer.class,t.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT unit_price FROM crm.store_offer WHERE tenant_id=? AND id=?",java.math.BigDecimal.class,t.id(),a.offerId())).isEqualByComparingTo("100");
        var start=new java.util.concurrent.CountDownLatch(1);
        UUID first,second;
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var f=pool.submit(()->{start.await();return fixtureBudgetWrite(t,actor,approval,a,source,"d".repeat(64));});
            var g=pool.submit(()->{start.await();return fixtureBudgetWrite(t,actor,approval,b,source,"e".repeat(64));});
            start.countDown();first=f.get(20,java.util.concurrent.TimeUnit.SECONDS);second=g.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat((first!=null)^(second!=null)).isTrue();
        UUID winner=first!=null?first:second;
        assertThat(fixtureBudgetWrite(t,actor,approval,first!=null?a:b,source,(first!=null?"d":"e").repeat(64))).isEqualTo(winner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_execution_receipt WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(delta_kzt) FROM crm.fx_auto_budget_consumption WHERE tenant_id=?",java.math.BigDecimal.class,t.id())).isEqualByComparingTo("60");
        var next=enrollment.createPolicyRevision(actor,t.id(),budgetCommand(draft.policyId(),1,"budget-next",200,a,b));
        var remaining=new TransactionTemplate(txManager).execute(status->budgetLedger.balance(t.id(),next.revisionId()));
        assertThat(remaining.remainingKzt()).isEqualByComparingTo("40");
    }
}
