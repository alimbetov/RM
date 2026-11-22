package kz.rm.iam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class R4FirstTenantOnboardingQualificationTest {
    @Autowired SelfServiceTenantOnboardingService onboarding;
    @Autowired EmailIdentityService emails;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired UserAccountRepository users;
    @Autowired MyAccessService access;
    @Autowired TenantAuthorization permission;
    @Autowired JdbcTemplate jdbc;

    private UUID account() {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "r4-oidc-"+UUID.randomUUID(), "R4 signed principal")).getId();
    }
    private VerifiedActor actor(UUID id) {
        return new VerifiedActor(id,Instant.now().plusSeconds(600));
    }
    private void verifyEmail(UUID id) {
        String address="r4-"+UUID.randomUUID()+"@example.kz";
        emails.requestInitialVerification(actor(id),address);
        var proof=jdbc.query("""
                SELECT c.id,c.token_envelope
                FROM public.iam_email_verification_challenge c
                JOIN public.iam_email_identity e ON e.id=c.email_identity_id
                WHERE e.user_id=? AND c.purpose='REGISTRATION'
                ORDER BY c.created_at DESC
                """,rs->{
                    if(!rs.next())throw new IllegalStateException("Missing email challenge");
                    return new Object[]{rs.getObject(1,UUID.class),rs.getBytes(2)};
                },id);
        assertThat(emails.verifyRegistration((UUID)proof[0],
                crypto.decrypt((byte[])proof[1]))).isTrue();
    }
    private int tenantRows(UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM public.iam_workspace WHERE id=?",
                Integer.class,tenant);
    }
    private int events(UUID tenant) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event WHERE tenant_id=?
                """,Integer.class,tenant);
    }

    @Test void verifiedEmailFirstTenantIsAtomicAndExactRetryIsIdempotent() {
        UUID user=account(), tenant=UUID.randomUUID();
        verifyEmail(user);
        var original=onboarding.createFirstTenant(actor(user),tenant,
                "Example LLP","Example","KZ");
        var replay=onboarding.createFirstTenant(actor(user),tenant,
                "Example LLP","Example","KZ");
        assertThat(replay).isEqualTo(original);
        assertThat(tenantRows(tenant)).isEqualTo(1);
        assertThat(events(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT enforced FROM public.iam_tenant_rbac_config WHERE tenant_id=?
                """,Boolean.class,tenant)).isTrue();
        assertThat(onboarding.selectTenant(actor(user),tenant).tenantId()).isEqualTo(tenant);
        assertThat(access.myTenants(actor(user))).extracting(MyAccessService.Tenant::tenantId)
                .containsExactly(tenant);
        permission.require(user,tenant,DomainPermission.TENANT_RBAC_MANAGE);
        assertThatThrownBy(()->permission.require(user,tenant,DomainPermission.CRM_READ))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->onboarding.createFirstTenant(actor(user),tenant,
                "Different LLP","Example","KZ")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->onboarding.createFirstTenant(actor(user),UUID.randomUUID(),
                "Another LLP","Another","KZ")).isInstanceOf(SecurityException.class);
        assertThat(events(tenant)).isEqualTo(1);
    }

    @Test void unverifiedEmailSuspensionAndForeignTenantRejectWithoutWrites() {
        UUID a=account(), b=account(), aTenant=UUID.randomUUID();
        UUID rejected=UUID.randomUUID();
        assertThatThrownBy(()->onboarding.createFirstTenant(actor(a),rejected,
                "Denied LLP","Denied","KZ")).isInstanceOf(SecurityException.class);
        assertThat(tenantRows(rejected)).isZero();
        verifyEmail(a);
        onboarding.createFirstTenant(actor(a),aTenant,"Tenant A LLP","Tenant A","KZ");
        verifyEmail(b);
        assertThatThrownBy(()->onboarding.selectTenant(actor(b),aTenant))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->onboarding.createFirstTenant(actor(b),aTenant,
                "Tenant A LLP","Tenant A","KZ")).isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",b);
        assertThatThrownBy(()->onboarding.createFirstTenant(actor(b),rejected,
                "Denied LLP","Denied","KZ")).isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",a);
        assertThatThrownBy(()->onboarding.selectTenant(actor(a),aTenant))
                .isInstanceOf(SecurityException.class);
        assertThat(tenantRows(aTenant)).isEqualTo(1);
        assertThat(tenantRows(rejected)).isZero();
        assertThat(events(aTenant)).isEqualTo(1);
    }

    @Test void concurrentFirstTenantRequestsProduceOneOwnerAndNoOrphanRows() throws Exception {
        UUID user=account();
        verifyEmail(user);
        UUID first=UUID.randomUUID(), second=UUID.randomUUID();
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return onboarding.createFirstTenant(actor(user),first,"R4 LLP","R4","KZ");
            });
            var b=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return onboarding.createFirstTenant(actor(user),second,"R4 LLP","R4","KZ");
            });
            start.countDown();
            int success=0, denied=0;
            for(var future:java.util.List.of(a,b)) {
                try {future.get(20,TimeUnit.SECONDS);success++;}
                catch(java.util.concurrent.ExecutionException error) {
                    assertThat(error.getCause()).isInstanceOf(SecurityException.class);
                    denied++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(denied).isEqualTo(1);
        }
        assertThat(tenantRows(first)+tenantRows(second)).isEqualTo(1);
        assertThat(events(first)+events(second)).isEqualTo(1);
        assertThat(access.myTenants(actor(user))).hasSize(1);
    }
}
