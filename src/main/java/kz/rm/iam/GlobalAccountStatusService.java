package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Global account suspension/restoration for verified active PLATFORM_ADMIN actors.
 * Account status UPDATE is fenced against every command's account row lock.
 * Active organization OWNER accounts cannot be suspended until ownership
 * has been safely relinquished in all their organizations.
 */
@Service
public class GlobalAccountStatusService {
    private final JdbcTemplate jdbc;

    public GlobalAccountStatusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void suspendAccount(VerifiedActor admin, UUID targetUserId) {
        changeStatus(admin,targetUserId,false);
    }

    @Transactional
    public void restoreAccount(VerifiedActor admin, UUID targetUserId) {
        changeStatus(admin,targetUserId,true);
    }

    private void changeStatus(VerifiedActor admin, UUID targetUserId, boolean restore) {
        if (admin == null) throw new SecurityException("Verified administrator required");
        UUID adminId = admin.userId();
        if (targetUserId == null) throw new IllegalArgumentException("Target account required");
        if (adminId.equals(targetUserId))
            throw new SecurityException("Platform administrator cannot change own account status");

        String adminStatus = jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """,rs -> rs.next() ? rs.getString(1) : null,adminId);
        if (!"ACTIVE".equals(adminStatus)) throw new SecurityException("Administrator inactive");
        String authority = jdbc.query("""
                SELECT role FROM public.iam_global_role_assignment
                WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NULL FOR UPDATE
                """,rs -> rs.next() ? rs.getString(1) : null,adminId);
        if (!"PLATFORM_ADMIN".equals(authority))
            throw new SecurityException("Active global administrator required");

        String current = jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """,rs -> rs.next() ? rs.getString(1) : null,targetUserId);
        if (current == null) throw new SecurityException("Target account unavailable");
        String desired = restore ? "ACTIVE" : "SUSPENDED";
        if (desired.equals(current)) return;
        String expected = restore ? "SUSPENDED" : "ACTIVE";
        if (!expected.equals(current))
            throw new IllegalStateException("Unsupported account status transition");

        if (!restore) {
            Integer ownerMemberships = jdbc.queryForObject("""
                    SELECT count(*) FROM public.iam_org_membership
                    WHERE user_id=? AND role='OWNER' AND status='ACTIVE'
                    """,Integer.class,targetUserId);
            if (ownerMemberships != null && ownerMemberships > 0)
                throw new SecurityException("Active owner must relinquish ownership before suspension");
        }

        int updated = jdbc.update("""
                UPDATE public.iam_user_account
                SET status=?,row_version=row_version+1,updated_at=?
                WHERE id=? AND status=?
                """,desired,Timestamp.from(Instant.now()),targetUserId,current);
        if (updated != 1) throw new IllegalStateException("Concurrent account status transition");
        jdbc.update("""
                INSERT INTO public.iam_account_status_event
                  (id,actor_user_id,target_user_id,from_status,to_status,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,UUID.randomUUID(),adminId,targetUserId,current,desired,Timestamp.from(Instant.now()));
    }
}
