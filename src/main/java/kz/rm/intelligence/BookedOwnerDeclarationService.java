package kz.rm.intelligence;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable owner DECLARATIONS only. No assertion of complete capture or external import,
 * no arbitrary OwnerProof insertion, no READY, no HTTP boundary. */
@Service
public class BookedOwnerDeclarationService {
    public record Draft(UUID proofId,UUID runId,BookedHistoryComposition.Mode mode,String provenanceRef,
                        String provenanceDigest,long sourceRevision,long receiptRevision,long expectedOrders,
                        String expectedPopulationDigest){
        public Draft {
            Objects.requireNonNull(proofId);Objects.requireNonNull(runId);Objects.requireNonNull(mode);
            if(provenanceRef==null||provenanceRef.isBlank()||provenanceRef.length()>256||
                !hex(provenanceDigest)||!hex(expectedPopulationDigest)||sourceRevision<0||receiptRevision<0||expectedOrders<0)
                throw new IllegalArgumentException("S1_DECLARATION_DRAFT");
        }
    }
    public enum State { REVOKED,OWNER_AUTHORITY_MISSING,INCOMPATIBLE_SCOPE,STALE_REVISION,INVALID_PROVENANCE,
        OPEN_INTERVAL,UNRECONCILED_SOURCE,POPULATION_MISMATCH,MATCHED_CONTRACT_NOT_ADMITTED }
    /** No provenance text or issuer details disclosed to operational readers. */
    public record Inspection(UUID tenant,UUID actor,UUID proofId,UUID runId,BookedHistoryComposition.Mode mode,
                             State state,long sourceRevision,long receiptRevision,Instant asOf,
                             HistoricalCoverageService.Eligibility eligibility){
        public boolean admitted(){return false;}
    }
    record Stored(UUID tenant,UUID issuer,int authorityVersion,Draft draft,Instant from,Instant until,Instant issuedAt,boolean revoked){
        Stored {
            Objects.requireNonNull(tenant);Objects.requireNonNull(issuer);Objects.requireNonNull(draft);
            Objects.requireNonNull(from);Objects.requireNonNull(until);Objects.requireNonNull(issuedAt);
            if(!from.isBefore(until)||until.isAfter(issuedAt))
                throw new IllegalArgumentException("S1_STORED_DECLARATION_RANGE");
        }
        BookedOwnerAuthorityContract.Claim claim(){
            return new BookedOwnerAuthorityContract.Claim(draft.proofId(),authorityVersion,
                BookedOwnerAuthorityContract.Mode.valueOf(draft.mode().name()),"TENANT_OWNER:"+issuer,draft.provenanceRef(),
                draft.provenanceDigest(),new BookedSourceReconciliationService.RevisionBinding(tenant,draft.runId(),from,until,
                    draft.sourceRevision(),draft.receiptRevision()),from,until,issuedAt,draft.expectedOrders(),draft.expectedPopulationDigest());
        }
    }
    private record CurrentRun(Instant from,Instant until,Instant createdAt,Instant asOf,long sourceRevision,long receiptRevision){}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final TenantAuthorization authorization;
    BookedOwnerDeclarationService(JdbcTemplate jdbc,PlatformTransactionManager manager,TenantAuthorization authorization){
        this.jdbc=jdbc;this.manager=manager;this.authorization=authorization;
    }
    private static boolean hex(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    /** Explicit owner statement, expected population supplied independently; never filled from analytics.
     * The existing non-delegable OWNER gate is reused solely as ownership anchor, not SALES read authority. */
    public UUID declare(VerifiedActor owner,UUID tenant,Draft draft){
        Objects.requireNonNull(draft);
        return ownerTransaction(owner,tenant,user->{
            var existing=load(jdbc,tenant,draft.proofId());
            if(existing!=null)return idempotent(existing,tenant,user,draft);
            var run=jdbc.query("""
                SELECT r.range_start,r.range_end,w.created_at,transaction_timestamp(),
                  coalesce((SELECT revision FROM crm.int_source_revision WHERE tenant_id=r.tenant_id),0)
                    +(SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=r.tenant_id),
                  (SELECT count(*) FROM crm.int_fact_backfill_receipt x WHERE x.tenant_id=r.tenant_id AND x.run_id=r.run_id
                    AND x.owner_module=r.owner_module AND x.source_kind=r.source_kind)
                FROM crm.int_fact_backfill_run r JOIN public.iam_workspace w ON w.id=r.tenant_id
                WHERE r.tenant_id=? AND r.run_id=? AND r.owner_module='SALES' AND r.source_kind='ORDER_PLACED' AND r.time_basis='OCCURRED'
                """,rs->rs.next()?new CurrentRun(rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant(),
                    rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant(),rs.getLong(5),rs.getLong(6)):null,
                tenant,draft.runId());
            if(run==null)throw new IllegalArgumentException("S1_BOOKED_RUN_NOT_FOUND");
            if(run.until().isAfter(run.asOf())||java.time.Duration.between(run.from(),run.until()).compareTo(CanonicalFactDeliveryService.MAX_SLICE)>0)
                throw new IllegalArgumentException("S1_DECLARATION_RANGE");
            if(draft.mode()==BookedHistoryComposition.Mode.NATIVE_HISTORY&&run.from().isBefore(run.createdAt()))
                throw new IllegalArgumentException("S1_NATIVE_BOUNDARY");
            if(draft.sourceRevision()!=run.sourceRevision()||draft.receiptRevision()!=run.receiptRevision())
                throw new IllegalArgumentException("S1_DECLARATION_STALE");
            // A concurrent source commit may make this candidate stale immediately.
            // All consumer checks compare current revisions again in one authorized RR snapshot.
            jdbc.update("""
                INSERT INTO crm.int_booked_owner_declaration(tenant_id,proof_id,run_id,issuer_id,authority_version,history_mode,
                  provenance_ref,provenance_digest,source_from,source_until,source_revision,receipt_revision,expected_orders,expected_population_digest)
                VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,proof_id) DO NOTHING
                """,tenant,draft.proofId(),draft.runId(),user,draft.mode().name(),draft.provenanceRef(),draft.provenanceDigest(),
                Timestamp.from(run.from()),Timestamp.from(run.until()),draft.sourceRevision(),draft.receiptRevision(),
                draft.expectedOrders(),draft.expectedPopulationDigest());
            return idempotent(Objects.requireNonNull(load(jdbc,tenant,draft.proofId())),tenant,user,draft);
        });
    }
    private static UUID idempotent(Stored existing,UUID tenant,UUID issuer,Draft draft){
        if(!existing.tenant().equals(tenant)||!existing.issuer().equals(issuer)||
            existing.authorityVersion()!=BookedOwnerAuthorityContract.AUTHORITY_VERSION||!existing.draft().equals(draft))
            throw new IllegalArgumentException("S1_DECLARATION_ID_CONFLICT");
        return draft.proofId();
    }
    /** Irreversible per-proof revocation. A new declaration needs a new proof ID. */
    public void revoke(VerifiedActor owner,UUID tenant,UUID proofId){
        Objects.requireNonNull(proofId);
        ownerTransaction(owner,tenant,user->{
            if(load(jdbc,tenant,proofId)==null)throw new IllegalArgumentException("S1_DECLARATION_NOT_FOUND");
            jdbc.update("""
                INSERT INTO crm.int_booked_owner_revocation(tenant_id,proof_id,revoked_by)
                VALUES (?,?,?) ON CONFLICT(tenant_id,proof_id) DO NOTHING
                """,tenant,proofId,user);
            return null;
        });
    }
    private <T> T ownerTransaction(VerifiedActor owner,UUID tenant,java.util.function.Function<UUID,T> action){
        Objects.requireNonNull(owner);Objects.requireNonNull(tenant);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("S1_REQUIRES_OWN_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(60);
        return tx.execute(st->{
            UUID user=owner.userId();
            var decision=authorization.requireForCommand(user,tenant,DomainPermission.TENANT_RBAC_MANAGE);
            if(!decision.validForCurrentTransaction()||!user.equals(decision.actorId())||!tenant.equals(decision.tenantId())||
                decision.role()!=kz.rm.iam.OrganizationMembershipEntity.Role.OWNER||
                decision.permission()!=DomainPermission.TENANT_RBAC_MANAGE)
                throw new SecurityException("S1_OWNER_ADMISSION_REQUIRED");
            if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->
                c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"))))
                throw new IllegalStateException("S1_POSTGRESQL_REQUIRED");
            T result=action.apply(user);owner.userId();return result;
        });
    }
    /** Package-internal repository. Callers already hold current authorization. */
    static Stored load(JdbcTemplate jdbc,UUID tenant,UUID proofId){
        return jdbc.query("""
            SELECT d.issuer_id,d.run_id,d.history_mode,d.provenance_ref,d.provenance_digest,d.source_revision,d.receipt_revision,
              d.expected_orders,d.expected_population_digest,d.source_from,d.source_until,d.issued_at,(v.proof_id IS NOT NULL),
              d.tenant_id,d.authority_version
            FROM crm.int_booked_owner_declaration d LEFT JOIN crm.int_booked_owner_revocation v
              ON v.tenant_id=d.tenant_id AND v.proof_id=d.proof_id
            WHERE d.tenant_id=? AND d.proof_id=? AND d.authority_version=1
            """,rs->rs.next()?new Stored(rs.getObject(14,UUID.class),rs.getObject(1,UUID.class),rs.getInt(15),
                new Draft(proofId,rs.getObject(2,UUID.class),
                BookedHistoryComposition.Mode.valueOf(rs.getString(3)),rs.getString(4),rs.getString(5).trim(),rs.getLong(6),
                rs.getLong(7),rs.getLong(8),rs.getString(9).trim()),rs.getTimestamp(10).toInstant(),rs.getTimestamp(11).toInstant(),
                rs.getTimestamp(12).toInstant(),rs.getBoolean(13)):null,tenant,proofId);
    }
    static Inspection inspect(Stored stored,BookedSourceReconciliationService.Evidence current){
        Objects.requireNonNull(stored);Objects.requireNonNull(current);
        // Reject before any output, including revoked status. Do not relabel a
        // persisted declaration with the tenant or run of the current snapshot.
        if(!stored.tenant().equals(current.tenant())||!stored.draft().runId().equals(current.runId()))
            throw new IllegalArgumentException("S1_DECLARATION_SCOPE");
        var state=stored.revoked()?State.REVOKED:State.valueOf(BookedOwnerAuthorityContract.evaluate(current,stored.claim()).status().name());
        return new Inspection(current.tenant(),current.actor(),stored.draft().proofId(),current.runId(),stored.draft().mode(),
            state,current.sourceRevision(),current.receiptRevision(),current.asOf(),HistoricalCoverageService.Eligibility.BLOCKED);
    }
}
