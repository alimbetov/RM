package kz.rm.iam;

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

/**
 * IAM–SELLER-R0: real JCA-signed JWT -> loopback JWKS decoder -> VerifiedActor
 * -> membership-based selected tenant -> employee domain RBAC -> CatalogItemService.
 *
 * Only fixture construction and SELECT oracles use direct SQL.
 * This qualification does NOT claim live external IdP, TLS, or HTTP admission.
 */
@SpringBootTest(properties = {
        "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class IamSellerAuthenticatedTenantAdmissionQualificationTest {
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
    @Autowired SelfServiceTenantOnboardingService onboarding;
    @Autowired CatalogItemService catalog;

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

    private UUID grants(Tenant tenant,DomainPermission... grants) throws Exception {
        VerifiedActor owner=verified(tenant.owner());
        var role=roles.createRole(owner,tenant.id(),
                "JWTR0_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "JWT seller admission role");
        for(var permission:grants)roles.grant(owner,tenant.id(),role.id(),permission);
        roles.assign(owner,tenant.id(),tenant.member(),role.id());
        roles.enable(owner,tenant.id());
        return role.id();
    }

    private int items(Tenant tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM crm.cat_item WHERE tenant_id=?",
                Integer.class,tenant.id());
    }

    private int events(Tenant tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM crm.cat_item_event WHERE tenant_id=?",
                Integer.class,tenant.id());
    }

    private void postgresOnly() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .contains("PostgreSQL"),"Requires real PostgreSQL integration");
        }
    }

    @Test void iamSellerR0RealBearerSubCannotBeOverriddenByActorOrTenantClaims()
            throws Exception {
        postgresOnly();
        UUID staff=account();
        Tenant a=tenant(staff),b=tenant(staff),foreign=tenant(account());
        grants(a,DomainPermission.CATALOG_READ,DomainPermission.CATALOG_WRITE);
        grants(b,DomainPermission.CATALOG_READ);
        grants(foreign,DomainPermission.CATALOG_WRITE);
        String token=signed(subject(staff),"Bearer",ISSUER,AUDIENCE,
                Instant.now().plusSeconds(600).getEpochSecond(),trusted.getPrivate(),
                null,a.owner(),foreign.id());
        VerifiedActor actor=new OidcActorResolver(decoder,users).resolve(token);
        assertThat(actor.userId()).isEqualTo(staff);
        assertThat(onboarding.selectTenant(actor,a.id()).tenantId()).isEqualTo(a.id());
        assertThat(onboarding.selectTenant(actor,b.id()).tenantId()).isEqualTo(b.id());
        assertThatThrownBy(()->onboarding.selectTenant(actor,foreign.id()))
                .isInstanceOf(SecurityException.class);
        var item=catalog.createDraft(actor,a.id(),"JWS-A-"+UUID.randomUUID(),
                CatalogItem.Kind.PRODUCT,"ru","JWT-verified SKU",null);
        assertThat(item.tenantId()).isEqualTo(a.id());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item_event
                WHERE tenant_id=? AND item_id=? AND actor_id=? AND action_code='CREATED'
                """,Integer.class,a.id(),item.id(),staff)).isEqualTo(1);
        assertThatThrownBy(()->catalog.createDraft(actor,b.id(),
                "FORBIDDEN-B-"+UUID.randomUUID(),CatalogItem.Kind.PRODUCT,
                "ru","Not writable in B",null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->catalog.createDraft(actor,foreign.id(),
                "FORBIDDEN-C-"+UUID.randomUUID(),CatalogItem.Kind.PRODUCT,
                "ru","Not member in C",null)).isInstanceOf(SecurityException.class);
        assertThat(items(a)).isEqualTo(1);
        assertThat(items(b)).isZero();
        assertThat(items(foreign)).isZero();
        assertThat(events(a)).isEqualTo(1);
        assertThat(events(b)).isZero();
        assertThat(events(foreign)).isZero();
    }

    @Test void iamSellerR0SignedIdRefreshWrongPurposeAndBadJwtNeverMintActor()
            throws Exception {
        postgresOnly();
        UUID staff=account();
        Tenant t=tenant(staff);
        grants(t,DomainPermission.CATALOG_WRITE);
        var resolver=new OidcActorResolver(decoder,users);
        String sub=subject(staff);
        long valid=Instant.now().plusSeconds(600).getEpochSecond();
        long expired=Instant.now().minusSeconds(300).getEpochSecond();
        String[] denied={
                signed(sub,null,ISSUER,AUDIENCE,valid,trusted.getPrivate(),null,null,null),
                signed(sub,"ID",ISSUER,AUDIENCE,valid,trusted.getPrivate(),null,null,null),
                signed(sub,"Bearer",ISSUER,AUDIENCE,valid,trusted.getPrivate(),"id",null,null),
                signed(sub,"Bearer",ISSUER,AUDIENCE,valid,trusted.getPrivate(),"refresh",null,null),
                signed(sub,"Bearer",ISSUER,"other-api",valid,trusted.getPrivate(),null,null,null),
                signed(sub,"Bearer","https://wrong-issuer.test",AUDIENCE,valid,
                        trusted.getPrivate(),null,null,null),
                signed(sub,"Bearer",ISSUER,AUDIENCE,expired,trusted.getPrivate(),null,null,null),
                signed(sub,"Bearer",ISSUER,AUDIENCE,valid,untrusted.getPrivate(),null,null,null),
                signed("unknown-"+UUID.randomUUID(),"Bearer",ISSUER,AUDIENCE,valid,
                        trusted.getPrivate(),null,null,null)
        };
        for(String token:denied) {
            assertThatThrownBy(()->resolver.resolve(token))
                    .as("JWT must fail closed before any catalog command")
                    .isInstanceOf(SecurityException.class);
        }
        assertThat(resolver.resolve(access(staff)).userId()).isEqualTo(staff);
        assertThat(items(t)).isZero();
        assertThat(events(t)).isZero();
    }

    @Test void iamSellerR0RoleRevokedAfterSignedTokenAndTenantSelectionDeniesWrite()
            throws Exception {
        postgresOnly();
        UUID staff=account();
        Tenant t=tenant(staff);
        UUID role=grants(t,DomainPermission.CATALOG_WRITE);
        String bearer=access(staff);
        VerifiedActor actor=new OidcActorResolver(decoder,users).resolve(bearer);
        onboarding.selectTenant(actor,t.id());
        var first=catalog.createDraft(actor,t.id(),"ROLE-FIRST-"+UUID.randomUUID(),
                CatalogItem.Kind.PRODUCT,"ru","Allowed before revoke",null);
        roles.revoke(verified(t.owner()),t.id(),role,DomainPermission.CATALOG_WRITE);
        assertThat(new OidcActorResolver(decoder,users).resolve(bearer).userId()).isEqualTo(staff);
        assertThatThrownBy(()->catalog.createDraft(actor,t.id(),"ROLE-DENIED-"+UUID.randomUUID(),
                CatalogItem.Kind.PRODUCT,"ru","Rejected after revoke",null))
                .isInstanceOf(SecurityException.class);
        assertThat(items(t)).isEqualTo(1);
        assertThat(events(t)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item_event
                WHERE tenant_id=? AND item_id=? AND action_code='CREATED'
                """,Integer.class,t.id(),first.id())).isEqualTo(1);
    }

    @Test void iamSellerR0MembershipSuspendedAfterBearerBlocksSelectAndMutation()
            throws Exception {
        postgresOnly();
        UUID staff=account();
        Tenant t=tenant(staff);
        grants(t,DomainPermission.CATALOG_WRITE);
        VerifiedActor actor=verified(staff);
        assertThat(onboarding.selectTenant(actor,t.id()).tenantId()).isEqualTo(t.id());
        jdbc.update("""
                UPDATE public.iam_org_membership SET status='SUSPENDED'
                WHERE organization_id=? AND user_id=?
                """,t.org(),staff);
        assertThatThrownBy(()->onboarding.selectTenant(actor,t.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->catalog.createDraft(actor,t.id(),
                "MEMBER-DENIED-"+UUID.randomUUID(),CatalogItem.Kind.PRODUCT,"ru",
                "Suspended membership denied",null)).isInstanceOf(SecurityException.class);
        assertThat(items(t)).isZero();
        assertThat(events(t)).isZero();
    }

    @Test void iamSellerR0SuspendedAccountRejectsExistingAndFreshBearer()
            throws Exception {
        postgresOnly();
        UUID staff=account();
        Tenant t=tenant(staff);
        grants(t,DomainPermission.CATALOG_WRITE);
        String bearer=access(staff);
        VerifiedActor actor=new OidcActorResolver(decoder,users).resolve(bearer);
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",staff);
        assertThatThrownBy(()->new OidcActorResolver(decoder,users).resolve(bearer))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->onboarding.selectTenant(actor,t.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->catalog.createDraft(actor,t.id(),
                "ACCOUNT-DENIED-"+UUID.randomUUID(),CatalogItem.Kind.PRODUCT,"ru",
                "Suspended account denied",null)).isInstanceOf(SecurityException.class);
        assertThat(items(t)).isZero();
        assertThat(events(t)).isZero();
    }

    @Test void iamSellerR0TenantSuspendedAfterAccessTokenDeniesCurrentSelectionAndWrite()
            throws Exception {
        postgresOnly();
        UUID staff=account();
        Tenant t=tenant(staff);
        grants(t,DomainPermission.CATALOG_WRITE);
        VerifiedActor actor=verified(staff);
        jdbc.update("UPDATE public.iam_workspace SET status='SUSPENDED' WHERE id=?",t.id());
        assertThatThrownBy(()->onboarding.selectTenant(actor,t.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->catalog.createDraft(actor,t.id(),
                "TENANT-DENIED-"+UUID.randomUUID(),CatalogItem.Kind.PRODUCT,"ru",
                "Tenant disabled",null)).isInstanceOf(SecurityException.class);
        assertThat(items(t)).isZero();
        assertThat(events(t)).isZero();
    }
}
