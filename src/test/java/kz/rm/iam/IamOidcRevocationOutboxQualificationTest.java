package kz.rm.iam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class IamOidcRevocationOutboxQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired UserAccountRepository users;
    @Autowired GlobalAccountDeletionService deletion;
    @Autowired OidcRevocationOutboxWorker defaultWorker;

    private record Fixture(UUID target,UUID deletionEventId,String subject) {}
    private VerifiedActor actor(UUID id) {
        return new VerifiedActor(id,Instant.now().plusSeconds(300));
    }
    private Fixture seed() {
        UUID target=UUID.randomUUID();
        String subject="oidc-"+UUID.randomUUID();
        users.saveAndFlush(new UserAccountEntity(target,subject,"Target"));
        UUID admin=UUID.randomUUID();
        users.saveAndFlush(new UserAccountEntity(admin,"oidc-admin-"+UUID.randomUUID(),"Admin"));
        jdbc.update("""
                INSERT INTO public.iam_global_role_assignment (id,user_id,role,granted_at)
                VALUES (?,?,'PLATFORM_ADMIN',?)
                """,UUID.randomUUID(),admin,java.sql.Timestamp.from(Instant.now()));
        deletion.deleteAccount(actor(admin),target,"delete-"+UUID.randomUUID());
        UUID event=jdbc.queryForObject("""
                SELECT deletion_event_id FROM public.iam_deletion_oidc_revocation_outbox
                WHERE target_user_id=?
                """,UUID.class,target);
        return new Fixture(target,event,subject);
    }
    private OidcRevocationOutboxWorker worker(OidcSessionRevocationGateway gateway) {
        return new OidcRevocationOutboxWorker(jdbc,transactionManager,crypto,gateway);
    }
    private String state(UUID id) {
        return jdbc.queryForObject("""
                SELECT state FROM public.iam_deletion_oidc_revocation_outbox
                WHERE deletion_event_id=?
                """,String.class,id);
    }

    @Test void realProviderIsDisabledByDefaultAndDoesNotFabricateAck() {
        var row=seed();
        assertThat(defaultWorker.processOne(UUID.randomUUID()))
                .isEqualTo(OidcRevocationOutboxWorker.Outcome.RETRY_SCHEDULED);
        assertThat(state(row.deletionEventId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT acknowledged_at FROM public.iam_deletion_oidc_revocation_outbox
                WHERE deletion_event_id=?
                """,java.sql.Timestamp.class,row.deletionEventId())).isNull();
    }

    @Test void explicitProviderAcknowledgementCommitsOnceWithoutDuplicatingLogout() {
        var row=seed();
        AtomicInteger calls=new AtomicInteger();
        var worker=worker(subject->{
            assertThat(subject).isEqualTo(row.subject());
            calls.incrementAndGet();
        });
        assertThat(worker.processOne(UUID.randomUUID()))
                .isEqualTo(OidcRevocationOutboxWorker.Outcome.ACKNOWLEDGED);
        assertThat(worker.processOne(UUID.randomUUID()))
                .isEqualTo(OidcRevocationOutboxWorker.Outcome.NO_WORK);
        assertThat(calls).hasValue(1);
        assertThat(state(row.deletionEventId())).isEqualTo("ACKNOWLEDGED");
    }

    @Test void providerFailureBacksOffThenRetryCommitsAfterRecovery() {
        var row=seed();
        AtomicInteger calls=new AtomicInteger();
        var worker=worker(subject->{
            if(calls.incrementAndGet()==1)throw new IllegalStateException("synthetic provider outage");
            assertThat(subject).isEqualTo(row.subject());
        });
        assertThat(worker.processOne(UUID.randomUUID()))
                .isEqualTo(OidcRevocationOutboxWorker.Outcome.RETRY_SCHEDULED);
        assertThat(state(row.deletionEventId())).isEqualTo("PENDING");
        assertThat(worker.processOne(UUID.randomUUID()))
                .isEqualTo(OidcRevocationOutboxWorker.Outcome.NO_WORK);
        jdbc.update("""
                UPDATE public.iam_deletion_oidc_revocation_outbox
                SET next_attempt_at=? WHERE deletion_event_id=?
                """,java.sql.Timestamp.from(Instant.now().minusSeconds(1)),row.deletionEventId());
        assertThat(worker.processOne(UUID.randomUUID()))
                .isEqualTo(OidcRevocationOutboxWorker.Outcome.ACKNOWLEDGED);
        assertThat(calls).hasValue(2);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM public.iam_deletion_oidc_revocation_outbox
                WHERE deletion_event_id=?
                """,Integer.class,row.deletionEventId())).isEqualTo(2);
    }

    @Test void staleWorkerCannotAckAfterLeaseTakeover() throws Exception {
        var row=seed();
        CountDownLatch providerEntered=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        var oldWorker=worker(subject->{
            providerEntered.countDown();
            try {
                if(!release.await(12,TimeUnit.SECONDS))
                    throw new IllegalStateException("provider barrier timeout");
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        var freshWorker=worker(subject->assertThat(subject).isEqualTo(row.subject()));
        try(var pool=Executors.newSingleThreadExecutor()){
            var old=pool.submit(()->oldWorker.processOne(UUID.randomUUID()));
            assertThat(providerEntered.await(10,TimeUnit.SECONDS)).isTrue();
            // Simulate a process crash / lease expiry while the first remote
            // call is outstanding. The fresh worker must fence its late ACK.
            jdbc.update("""
                    UPDATE public.iam_deletion_oidc_revocation_outbox
                    SET leased_until=?,next_attempt_at=?
                    WHERE deletion_event_id=?
                    """,java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),row.deletionEventId());
            assertThat(freshWorker.processOne(UUID.randomUUID()))
                    .isEqualTo(OidcRevocationOutboxWorker.Outcome.ACKNOWLEDGED);
            release.countDown();
            assertThat(old.get(12,TimeUnit.SECONDS))
                    .isEqualTo(OidcRevocationOutboxWorker.Outcome.LEASE_LOST);
            assertThat(state(row.deletionEventId())).isEqualTo("ACKNOWLEDGED");
            assertThat(jdbc.queryForObject("""
                    SELECT attempt_count FROM public.iam_deletion_oidc_revocation_outbox
                    WHERE deletion_event_id=?
                    """,Integer.class,row.deletionEventId())).isEqualTo(2);
        } finally {release.countDown();}
    }

    @Test void multipleWorkersCannotClaimSamePendingOutboxRow() throws Exception {
        var row=seed();
        AtomicInteger calls=new AtomicInteger();
        CountDownLatch providerEntered=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        var worker=worker(subject->{
            calls.incrementAndGet();
            providerEntered.countDown();
            try {
                if(!release.await(12,TimeUnit.SECONDS))
                    throw new IllegalStateException("test timeout");
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        try(var pool=Executors.newSingleThreadExecutor()){
            var first=pool.submit(()->worker.processOne(UUID.randomUUID()));
            assertThat(providerEntered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(worker.processOne(UUID.randomUUID()))
                    .isEqualTo(OidcRevocationOutboxWorker.Outcome.NO_WORK);
            release.countDown();
            assertThat(first.get(12,TimeUnit.SECONDS))
                    .isEqualTo(OidcRevocationOutboxWorker.Outcome.ACKNOWLEDGED);
            assertThat(calls).hasValue(1);
            assertThat(state(row.deletionEventId())).isEqualTo("ACKNOWLEDGED");
        }finally {release.countDown();}
    }
}
