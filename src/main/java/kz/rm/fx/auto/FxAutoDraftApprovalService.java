package kz.rm.fx.auto;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * D.3: explicit approval of an inert PRODUCT draft. No active execution permission.
 * Same lock order as D.2; approval INSERT seals scope through existing DB triggers.
 */
@Service
public class FxAutoDraftApprovalService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final FxAutoOwnerConsentService consent;

    public FxAutoDraftApprovalService(JdbcTemplate jdbc,TenantAuthorization authorization,
                                      FxAutoOwnerConsentService consent) {
        this.jdbc=jdbc;this.authorization=authorization;this.consent=consent;
    }

    public record Command(UUID revisionId,String requestKey,String payloadSha256,
                          String scopeSha256,Instant expiresAt) {
        public Command {
            Objects.requireNonNull(revisionId);Objects.requireNonNull(expiresAt);
            if(requestKey==null || !requestKey.matches("[A-Za-z0-9_.:-]{1,100}") ||
                    !digest(payloadSha256) || !digest(scopeSha256) ||
                    !expiresAt.equals(expiresAt.truncatedTo(ChronoUnit.MICROS)))
                throw new IllegalArgumentException("FX_AUTO_INVALID_APPROVAL_COMMAND");
        }
    }
    public record Receipt(UUID approvalId,UUID policyId,UUID revisionId,String payloadSha256,
                          String scopeSha256,Instant expiresAt,int targetCount) {}
    private record Draft(UUID policy,long revision,String hash,UUID author) {}
    private record Existing(UUID approval,UUID revision,String payload,String scope,Instant expires,
                            int count,UUID membership) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt approve(VerifiedActor actor,UUID tenant,Command command) {
        if(actor==null || tenant==null)throw new SecurityException("FX_AUTO_VERIFIED_ACTOR_REQUIRED");
        Objects.requireNonNull(command);
        if(!"PostgreSQL".equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                c->c.getMetaData().getDatabaseProductName())) ||
                !"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)))
            throw new IllegalStateException("FX_AUTO_POSTGRES_READ_COMMITTED_REQUIRED");
        UUID user=actor.userId(), revision=command.revisionId();
        authorization.requireForCommand(user,tenant,DomainPermission.TENANT_RBAC_MANAGE);
        authorization.requireForCommand(user,tenant,DomainPermission.STOREFRONT_OFFER_MANAGE);
        Draft draft=jdbc.query("""
                SELECT policy_id,revision,trim(payload_sha256),CAST(CAST(payload AS jsonb)->>'author' AS uuid)
                FROM crm.fx_auto_policy_revision WHERE tenant_id=? AND id=?
                AND CAST(payload AS jsonb)->>'schema'='PRODUCT_DRAFT_V1'
                """,rs->rs.next()?new Draft(rs.getObject(1,UUID.class),rs.getLong(2),
                rs.getString(3),rs.getObject(4,UUID.class)):null,tenant,revision);
        if(draft==null || !user.equals(draft.author()))
            throw new SecurityException("FX_AUTO_DRAFT_UNAVAILABLE");
        List<String> currencies=jdbc.queryForList("""
                SELECT DISTINCT trim(a.anchor_currency) FROM crm.fx_auto_target_enrollment e
                JOIN crm.sku_fx_policy_version a ON a.tenant_id=e.tenant_id AND a.offer_id=e.offer_id AND a.id=e.anchor_id
                WHERE e.tenant_id=? AND e.policy_revision_id=?
                """,String.class,tenant,revision);
        consent.requireProductConsentAuthority(actor,tenant,currencies);
        if(jdbc.query("SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE",
                rs->rs.next()?rs.getObject(1,UUID.class):null,tenant)==null)
            throw new SecurityException("FX_AUTO_DRAFT_UNAVAILABLE");
        UUID membership=jdbc.query("""
                SELECT m.id FROM public.iam_org_membership m JOIN public.iam_workspace w
                ON w.organization_id=m.organization_id WHERE w.id=? AND m.user_id=?
                AND m.role='OWNER' AND m.status='ACTIVE'
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenant,user);
        if(membership==null)throw new SecurityException("FX_AUTO_OWNER_REQUIRED");
        actor.userId();
        Existing old=jdbc.query("""
                SELECT id,policy_revision_id,trim(payload_sha256),trim(scope_sha256),expires_at,target_count,membership_id
                FROM crm.fx_auto_approval_evidence WHERE tenant_id=? AND owner_user_id=? AND request_key=?
                """,rs->rs.next()?new Existing(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getInt(6),rs.getObject(7,UUID.class)):null,
                tenant,user,command.requestKey());
        if(old!=null) {
            if(!revision.equals(old.revision()) || !command.payloadSha256().equals(old.payload()) ||
                    !command.scopeSha256().equals(old.scope()) || !command.expiresAt().equals(old.expires()) ||
                    !membership.equals(old.membership()))
                throw new IllegalStateException("FX_AUTO_IDEMPOTENCY_CONFLICT");
            return new Receipt(old.approval(),draft.policy(),revision,old.payload(),old.scope(),old.expires(),old.count());
        }
        if(!draft.hash().equals(command.payloadSha256()) ||
                !command.scopeSha256().equals(jdbc.queryForObject("SELECT crm.fx_auto_scope_sha_v1(?,?)",String.class,tenant,revision)))
            throw new IllegalArgumentException("FX_AUTO_APPROVAL_DIGEST_MISMATCH");
        Long latest=jdbc.queryForObject("SELECT max(revision) FROM crm.fx_auto_policy_revision WHERE tenant_id=? AND policy_id=?",
                Long.class,tenant,draft.policy());
        if(latest==null || latest!=draft.revision())throw new IllegalStateException("FX_AUTO_STALE_POLICY_REVISION");
        // Verify the explicit payload target set exactly equals the enrolled rows.
        Boolean exact=jdbc.queryForObject("""
                WITH p AS (SELECT CAST(payload AS jsonb)->'targets' AS targets FROM crm.fx_auto_policy_revision WHERE tenant_id=? AND id=?),
                expected AS (SELECT CAST(x->>'offer' AS uuid) offer_id,CAST(x->>'anchor' AS uuid) anchor_id,
                    CAST(x->>'guardrail' AS uuid) guardrail_id,CAST(x->>'reviewDue' AS timestamptz) due,x->>'unit' unit,x->>'tax' tax
                    FROM p CROSS JOIN LATERAL jsonb_array_elements(p.targets) x),
                actual AS (SELECT offer_id,anchor_id,guardrail_id,anchor_review_due_at due,unit_code unit,tax_basis tax
                    FROM crm.fx_auto_target_enrollment WHERE tenant_id=? AND policy_revision_id=?)
                SELECT NOT EXISTS(SELECT * FROM expected EXCEPT ALL SELECT * FROM actual)
                   AND NOT EXISTS(SELECT * FROM actual EXCEPT ALL SELECT * FROM expected)
                """,Boolean.class,tenant,revision,tenant,revision);
        if(!Boolean.TRUE.equals(exact))throw new IllegalStateException("FX_AUTO_SCOPE_PAYLOAD_MISMATCH");
        int count=jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_target_enrollment WHERE tenant_id=? AND policy_revision_id=?",
                Integer.class,tenant,revision);
        int valid=jdbc.queryForObject("""
                SELECT count(*) FROM crm.fx_auto_target_enrollment e
                JOIN crm.store_offer o ON o.tenant_id=e.tenant_id AND o.id=e.offer_id
                JOIN crm.cat_item c ON c.tenant_id=o.tenant_id AND c.id=o.catalog_item_id
                JOIN crm.sku_fx_policy_version a ON a.tenant_id=e.tenant_id AND a.id=e.anchor_id AND a.offer_id=e.offer_id
                JOIN crm.sku_fx_guardrail_version g ON g.tenant_id=e.tenant_id AND g.id=e.guardrail_id AND g.offer_id=e.offer_id
                WHERE e.tenant_id=? AND e.policy_revision_id=? AND e.anchor_review_due_at>clock_timestamp()
                AND o.currency='KZT' AND o.unit_price>0 AND o.state='PUBLISHED' AND c.kind='PRODUCT' AND c.state='PUBLISHED'
                AND a.revision=(SELECT max(revision) FROM crm.sku_fx_policy_version WHERE tenant_id=e.tenant_id AND offer_id=e.offer_id)
                AND g.revision=(SELECT max(revision) FROM crm.sku_fx_guardrail_version WHERE tenant_id=e.tenant_id AND offer_id=e.offer_id)
                """,Integer.class,tenant,revision);
        if(count<1 || count!=valid)throw new IllegalStateException("FX_AUTO_TARGET_BINDING_INVALID");
        if(jdbc.queryForObject("SELECT count(*) FROM crm.fx_auto_policy_activation WHERE tenant_id=? AND policy_id=?",
                Integer.class,tenant,draft.policy())!=0)
            throw new IllegalStateException("FX_AUTO_POLICY_ALREADY_HAS_ACTIVATION");
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        if(!command.expiresAt().isAfter(now))throw new IllegalArgumentException("FX_AUTO_CONSENT_EXPIRED");
        actor.userId();
        UUID approval=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.fx_auto_approval_evidence(tenant_id,id,policy_revision_id,payload_sha256,
                owner_user_id,membership_id,request_key,approved_at,expires_at,target_count,scope_sha256)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,approval,revision,draft.hash(),user,membership,command.requestKey(),
                Timestamp.from(now),Timestamp.from(command.expiresAt()),count,command.scopeSha256());
        jdbc.update("""
                INSERT INTO crm.fx_auto_policy_activation(tenant_id,policy_id,policy_revision_id,approval_id,state,execution_epoch)
                VALUES (?,?,?,?,'PAUSED',0)
                """,tenant,draft.policy(),revision,approval);
        actor.userId();
        return new Receipt(approval,draft.policy(),revision,draft.hash(),command.scopeSha256(),command.expiresAt(),count);
    }

    private static boolean digest(String value) {return value!=null && value.matches("[0-9a-f]{64}");}
}
