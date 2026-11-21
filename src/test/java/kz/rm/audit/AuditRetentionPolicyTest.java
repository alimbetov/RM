package kz.rm.audit;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AuditRetentionPolicyTest {
    @Test void onlyApprovedThreeToFiveYearPoliciesAllowExpiry() {
        UUID tenant=UUID.randomUUID();
        Instant now=Instant.parse("2026-11-21T00:00:00Z");
        for(int years=3;years<=5;years++) {
            var p=new AuditRetentionPolicy(tenant,years,true,now);
            assertThat(p.expired(p.cutoff(now).minusSeconds(1),now,false)).isTrue();
            assertThat(p.expired(p.cutoff(now),now,false)).isFalse();
            assertThat(p.expired(p.cutoff(now).minusSeconds(1),now,true)).isFalse();
        }
        var disabled=new AuditRetentionPolicy(tenant,3,false,null);
        assertThat(disabled.expired(now.minusSeconds(315360000),now,false)).isFalse();
        assertThatThrownBy(()->new AuditRetentionPolicy(tenant,2,true,now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AuditRetentionPolicy(tenant,5,true,null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
