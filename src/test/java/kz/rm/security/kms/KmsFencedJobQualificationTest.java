package kz.rm.security.kms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * H2 smoke + real PostgreSQL transactions in CI.
 * Two independent service instances model two pods sharing the same DB.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class KmsFencedJobQualificationTest {
    @Autowired TenantKeyService keys;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KmsFencedJobService nodeA;

    private KmsFencedJobService otherPod() {
        return new KmsFencedJobService(jdbc, transactions);
    }

    @Test
    void onlyOneOwnerCanClaimUnexpiredTenantLease() {
        UUID tenant = UUID.randomUUID();
        keys.provision(tenant);
        var ownerA = nodeA.acquire(tenant, Duration.ofMinutes(2));
        assertThat(ownerA).isNotNull();
        assertThat(otherPod().acquire(tenant, Duration.ofMinutes(2))).isNull();
        assertThat(nodeA.release(ownerA)).isTrue();
        var ownerB = otherPod().acquire(tenant, Duration.ofMinutes(2));
        assertThat(ownerB.fencingToken()).isGreaterThan(ownerA.fencingToken());
        assertThat(nodeA.release(ownerA)).isFalse();
        assertThat(otherPod().release(ownerB)).isTrue();
    }

    @Test
    void expiredOldWorkerCannotCommitAfterSuccessorAcquiresLease() {
        UUID tenant = UUID.randomUUID();
        keys.provision(tenant);
        var stale = nodeA.acquire(tenant, Duration.ofMinutes(2));
        assertThat(stale).isNotNull();
        jdbc.update("""
            UPDATE security.kms_tenant_key
               SET job_lease_until = CURRENT_TIMESTAMP - INTERVAL '10' SECOND
             WHERE tenant_id=?
            """, tenant);
        var successor = otherPod().acquire(tenant, Duration.ofMinutes(2));
        assertThat(successor).isNotNull();
        assertThat(successor.fencingToken()).isGreaterThan(stale.fencingToken());

        assertThatThrownBy(() -> nodeA.executeFenced(stale, db -> {
            db.update("UPDATE security.kms_tenant_key SET remaining_a=100 WHERE tenant_id=?", tenant);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("fencing");
        assertThat(jdbc.queryForObject(
                "SELECT remaining_a FROM security.kms_tenant_key WHERE tenant_id=?",
                Long.class, tenant)).isZero();

        otherPod().executeFenced(successor, db -> {
            db.update("UPDATE security.kms_tenant_key SET migrated_a=3 WHERE tenant_id=?", tenant);
            return null;
        });
        assertThat(jdbc.queryForObject(
                "SELECT migrated_a FROM security.kms_tenant_key WHERE tenant_id=?",
                Long.class, tenant)).isEqualTo(3);
        assertThat(otherPod().release(successor)).isTrue();
    }

    @Test
    void tenantLeaseAndTokenSurviveServiceReconstruction() {
        UUID tenant = UUID.randomUUID();
        keys.provision(tenant);
        var lease = nodeA.acquire(tenant, Duration.ofMinutes(2));
        assertThat(lease).isNotNull();
        var reconstructedPod = otherPod();
        var renewed = reconstructedPod.renew(lease, Duration.ofMinutes(3));
        assertThat(renewed.fencingToken()).isEqualTo(lease.fencingToken());
        assertThat(reconstructedPod.acquire(tenant, Duration.ofSeconds(10))).isNull();
        assertThat(reconstructedPod.release(renewed)).isTrue();
    }

    @Test
    void concurrentAcquireElectsOnlyOneWinner() throws Exception {
        UUID tenant = UUID.randomUUID();
        keys.provision(tenant);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return nodeA.acquire(tenant, Duration.ofMinutes(2));
            });
            var second = executor.submit(() -> {
                start.await();
                return otherPod().acquire(tenant, Duration.ofMinutes(2));
            });
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat((a == null) ^ (b == null)).isTrue();
            nodeA.release(a == null ? b : a);
        }
    }

    @Test
    void callbackFailureRollsBackItsDatabaseMutation() {
        UUID tenant = UUID.randomUUID();
        keys.provision(tenant);
        var lease = nodeA.acquire(tenant, Duration.ofMinutes(2));
        assertThatThrownBy(() -> nodeA.executeFenced(lease, db -> {
            db.update("UPDATE security.kms_tenant_key SET remaining_a=55 WHERE tenant_id=?", tenant);
            throw new IllegalArgumentException("synthetic failure");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT remaining_a FROM security.kms_tenant_key WHERE tenant_id=?",
                Long.class, tenant)).isZero();
        assertThat(nodeA.release(lease)).isTrue();
    }
}
