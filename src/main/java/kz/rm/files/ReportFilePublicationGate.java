package kz.rm.files;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.intelligence.TrustedReportFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal R02 XLSX pre-publication guard. No attachment, consumer transition,
 * publication or download API. Evidence is private, short-lived and bound to
 * the exact owner capability and immutable storage pointer.
 * Provider immutability is the qualified application-level no-replace contract,
 * not a guarantee against privileged out-of-band object-store modification.
 */
@Service
public class ReportFilePublicationGate {
    private static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final long MAX_AGE=Duration.ofSeconds(30).toNanos();
    private final TenantAuthorization authorization;
    private final JdbcTemplate jdbc;
    private final ReportEncryptedStorage storage;
    private final TransactionTemplate tx;

    ReportFilePublicationGate(TenantAuthorization authorization,JdbcTemplate jdbc,
                              ReportEncryptedStorage storage,PlatformTransactionManager manager){
        this.authorization=authorization;this.jdbc=jdbc;this.storage=storage;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(20);
    }

    private record Pointer(UUID fileId,String key,String provider,long rowVersion){}
    public static final class Evidence {
        private final TrustedReportFile report;
        private final UUID actor;
        private final Pointer pointer;
        private final long issued;
        private Evidence(TrustedReportFile report,UUID actor,Pointer pointer,long issued){
            this.report=report;this.actor=actor;this.pointer=pointer;this.issued=issued;
        }
        private void requireFresh(){
            if(System.nanoTime()-issued>=MAX_AGE)throw denied("EVIDENCE_EXPIRED");
        }
    }
    public static final class Permit {
        private final VerifiedActor actor;
        private final Evidence evidence;
        private final List<TenantAuthorization.Decision> decisions;
        private Permit(VerifiedActor actor,Evidence evidence,List<TenantAuthorization.Decision> decisions){
            this.actor=actor;this.evidence=evidence;this.decisions=List.copyOf(decisions);
        }
        private void requireCurrent(){
            evidence.requireFresh();
            if(!actor.userId().equals(evidence.actor)||!TransactionSynchronizationManager.isActualTransactionActive()
                ||decisions.stream().anyMatch(d->!d.validForCurrentTransaction()
                    ||!d.actorId().equals(evidence.actor)||!d.tenantId().equals(evidence.report.tenant())))
                throw denied("ADMISSION_STALE");
        }
    }
    /** Informational identity, not an authorization capability. The caller
     * must hold the transaction and use the validated permit, not replay this record. */
    public record VerifiedFile(UUID fileId,long rowVersion,String sha256,long sizeBytes){}

    /** Phase 1: short owner/metadata transaction, followed by bounded actual
     * authentication of ciphertext outside DB locks. Never trust HTTP claims. */
    public Evidence verify(VerifiedActor actor,TrustedReportFile report,UUID fileId)throws IOException{
        Objects.requireNonNull(fileId);Objects.requireNonNull(report);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("REPORT_FILE_VERIFICATION_OUTSIDE_TRANSACTION");
        long start=System.nanoTime();
        Pointer pointer=Objects.requireNonNull(tx.execute(status->{
            admitOwner(actor,report);
            return lockPointer(actor,report,fileId);
        }));
        var expected=ReportEncryptedStorage.persisted(report.tenant(),fileId,pointer.key(),XLSX,
            report.size(),report.sha256());
        // Missing, malformed or unauthenticated content cannot create evidence.
        // Storage/KMS outages propagate; this read does not quarantine or delete.
        if(!storage.matches(expected,pointer.key(),false))throw denied("INTEGRITY");
        var proof=new Evidence(report,actor.userId(),pointer,start);
        proof.requireFresh();
        return proof;
    }

    /** Phase 2: fresh authorization and logical instance lock. P4.2 must lock
     * the intended consumer after admit and before lockVerified, then commit
     * all publication effects under the same transaction. No day/attempt lock. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Permit admit(VerifiedActor actor,Evidence proof){
        Objects.requireNonNull(proof);
        proof.requireFresh();
        if(actor==null||!proof.actor.equals(actor.userId()))throw denied("ACTOR");
        var decisions=admitOwner(actor,proof.report);
        var permit=new Permit(actor,proof,decisions);
        permit.requireCurrent();
        return permit;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public VerifiedFile lockVerified(Permit permit){
        Objects.requireNonNull(permit);
        permit.requireCurrent();
        var proof=permit.evidence;
        var actual=lockPointer(permit.actor,proof.report,proof.pointer.fileId());
        if(!actual.equals(proof.pointer))throw denied("POINTER_CHANGED");
        permit.requireCurrent();
        return new VerifiedFile(actual.fileId(),actual.rowVersion(),proof.report.sha256(),proof.report.size());
    }

    private List<TenantAuthorization.Decision> admitOwner(VerifiedActor actor,TrustedReportFile report){
        if(!TransactionSynchronizationManager.isActualTransactionActive()
            ||!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)))
            throw new IllegalStateException("REPORT_FILE_READ_COMMITTED_REQUIRED");
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '20s'");
        report.reauthorize(actor);
        var user=actor.userId();
        var tenant=report.tenant();
        var decisions=List.of(
            authorization.requireForCommand(user,tenant,DomainPermission.REPORT_GENERATE),
            authorization.requireForCommand(user,tenant,DomainPermission.FILES_ATTACH),
            authorization.requireForCommand(user,tenant,DomainPermission.FILES_READ));
        var instance=jdbc.query("""
            SELECT i.instance_id FROM reporting.int_report_instance i
            JOIN reporting.int_report_request_attempt l USING(tenant_id,instance_id)
            JOIN reporting.int_report_dataset_attempt d USING(tenant_id,dataset_id,staging_date)
            JOIN reporting.int_report_partition_day p ON p.staging_date=d.staging_date
            WHERE i.tenant_id=? AND i.instance_id=? AND l.dataset_id=? AND l.staging_date=?
              AND i.request_digest=? AND i.scope_digest=? AND i.report_code=? AND i.template_version=?
              AND l.selected_at IS NOT NULL AND l.abandoned_at IS NULL
              AND p.retired_at IS NULL AND d.build_state='READY' AND d.scope_digest=i.scope_digest
              AND d.source_manifest_digest=? AND d.expected_digest=? AND d.observed_digest=?
              AND d.expected_rows=d.observed_rows
            FOR NO KEY UPDATE OF i
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,
            tenant,report.instance(),report.dataset(),report.stagingDate(),report.requestDigest(),
            report.scopeDigest(),report.reportCode(),report.templateVersion(),report.sourceDigest(),
            report.datasetDigest(),report.datasetDigest());
        if(!report.instance().equals(instance))throw denied("OWNER_EVIDENCE");
        actor.userId();
        return decisions;
    }

    private Pointer lockPointer(VerifiedActor actor,TrustedReportFile r,UUID file){
        var rows=jdbc.query("""
            SELECT f.id,s.storage_key,s.provider,f.row_version
            FROM reporting.int_report_file_intent i
            JOIN crm.file_object f ON f.tenant_id=i.tenant_id AND f.id=i.file_id
            JOIN crm.file_storage s ON s.tenant_id=f.tenant_id AND s.file_id=f.id
            WHERE i.tenant_id=? AND i.instance_id=? AND i.dataset_id=? AND i.format_code='XLSX'
              AND i.file_id=? AND i.requester_id=? AND i.scope_digest=? AND i.source_digest=?
              AND i.dataset_digest=? AND i.renderer_version=? AND i.content_digest=? AND i.size_bytes=?
              AND f.uploaded_by=i.requester_id AND f.request_key=? AND f.mime_type=?
              AND f.sha256=i.content_digest AND f.size_bytes=i.size_bytes
              AND f.state='AVAILABLE' AND f.scan_status='CLEAN' AND f.content_class='CONFIDENTIAL'
              AND f.processing_token IS NULL AND f.processing_started_at IS NULL AND f.completed_at IS NOT NULL
              AND s.state='ACTIVE' AND s.encrypted_format_version=1
            FOR UPDATE OF f,s
            """,(rs,n)->new Pointer(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),
            r.tenant(),r.instance(),r.dataset(),file,actor.userId(),r.scopeDigest(),r.sourceDigest(),r.datasetDigest(),
            r.rendererVersion(),r.sha256(),r.size(),"report-"+r.instance()+"-XLSX",XLSX);
        if(rows.size()!=1)throw denied("UNAVAILABLE");
        var pointer=rows.getFirst();
        try{
            var expected=ReportEncryptedStorage.persisted(r.tenant(),file,pointer.key(),XLSX,r.size(),r.sha256());
            if(!("LOCAL".equals(pointer.provider())||"RUSTFS".equals(pointer.provider())))
                throw denied("PROVIDER");
            ReportStorageKey.require(pointer.key(),"RUSTFS".equals(pointer.provider()),false,expected);
        }catch(IOException e){throw denied("POINTER");}
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM crm.file_attachment
                WHERE tenant_id=? AND file_id=? AND detached_at IS NULL)
            """,Boolean.class,r.tenant(),file)))throw denied("ALREADY_BOUND");
        return pointer;
    }

    private static SecurityException denied(String reason){return new SecurityException("REPORT_FILE_PUBLICATION_"+reason);}
}
