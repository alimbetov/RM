package kz.rm.files;

import kz.rm.iam.*;
import kz.rm.crm.AuthenticatedCrmCommands;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(FilesR10bQualificationTest.SyntheticScannerConfiguration.class)
class FilesR10bQualificationTest {
    @TestConfiguration
    static class SyntheticScannerConfiguration {
        @Bean @Primary SyntheticScanner syntheticFileScanner() {return new SyntheticScanner();}
    }
    static class SyntheticScanner implements FileScanPort {
        final AtomicReference<Verdict> next=new AtomicReference<>(Verdict.CLEAN);
        final AtomicReference<Runnable> beforeVerdict=new AtomicReference<>();
        @Override public Verdict scan(String privateStagingKey) {
            Runnable hook=beforeVerdict.getAndSet(null);
            if(hook!=null)hook.run();
            return next.get();
        }
    }

    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired TenantRoleService roles;
    @Autowired AuthenticatedFilesCommands files;
    @Autowired FileUploadCompletionService completion;
    @Autowired FileStoragePort storage;
    @Autowired SyntheticScanner scanner;
    @Autowired FileRecoveryService recovery;
    @Autowired AuthenticatedCrmCommands crm;
    @Autowired AuthenticatedFilesAttachments attachments;
    @Autowired FileMetadataService metadata;
    @Autowired AuthenticatedFilesBinaryReads binaryReads;
    @Autowired JdbcTemplate jdbc;
    @Value("${rm.files.local-root:${java.io.tmpdir}/rm-files-synthetic}") String localRoot;

    private record Setup(UUID owner,UUID uploader,UUID tenant,VerifiedActor actor) {}
    private Setup setup() {
        var owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "files-b-owner-"+UUID.randomUUID(),"Owner"));
        var uploader=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "files-b-user-"+UUID.randomUUID(),"Uploader"));
        var organization=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Synthetic Storage","Synthetic","KZ"));
        var space=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),organization.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),organization.getId(),
                owner.getId(),OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),organization.getId(),
                uploader.getId(),OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",space.getId());
        var role=roles.createRole(owner.getId(),space.getId(),
                "FILE_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Uploader");
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.FILES_UPLOAD);
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.FILES_READ);
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.FILES_ATTACH);
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.FILES_DETACH);
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.CRM_READ);
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.CRM_WRITE);
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.CRM_PII_READ);
        roles.assign(owner.getId(),space.getId(),uploader.getId(),role.id());
        roles.enable(owner.getId(),space.getId());
        String sub=jdbc.queryForObject("SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,uploader.getId());
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg","RS256").subject(sub).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new Setup(owner.getId(),uploader.getId(),space.getId(),
                new OidcActorResolver(decoder,users).resolve("synthetic-only"));
    }
    private static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj\n<< >>\nendobj\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
    }
    private FileUploadIntent draft(Setup setup,byte[] bytes) {
        return files.begin(setup.actor(),setup.tenant(),"application/pdf",
                bytes.length,"file-"+UUID.randomUUID());
    }
    private int storageCount(Setup setup,UUID fileId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_storage WHERE tenant_id=? AND file_id=?
                """,Integer.class,setup.tenant(),fileId);
    }

    @Test void syntheticPdfStreamsAndBecomesAvailableOnlyAfterCleanScan() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] content=pdf();
        var intent=draft(s,content);
        assertThat(storageCount(s,intent.fileId())).isZero();
        var done=completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(content));
        assertThat(done.state()).isEqualTo("AVAILABLE");
        assertThat(done.scanStatus()).isEqualTo("CLEAN");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(jdbc.queryForObject("""
                SELECT sha256 FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),intent.fileId())).isEqualTo(sha);
        String key=jdbc.queryForObject("""
                SELECT storage_key FROM crm.file_storage WHERE tenant_id=? AND file_id=?
                """,String.class,s.tenant(),intent.fileId());
        assertThat(key).startsWith(s.tenant()+"/"+intent.fileId()+"/final-");
        assertThat(storage.exists(key)).isTrue();
        // Existing AVAILABLE file is immutable, never consumes replacement bytes.
        assertThat(completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream("overwrite".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(done);
        assertThat(storageCount(s,intent.fileId())).isEqualTo(1);
    }

    @Test void scannerInfectedAndTimeoutFailClosedWithoutStoragePromotion() throws Exception {
        var s=setup();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        scanner.next.set(FileScanPort.Verdict.INFECTED);
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes))).isInstanceOf(IOException.class);
        assertThat(storageCount(s,intent.fileId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),intent.fileId())).isEqualTo("REJECTED");
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes))).isInstanceOf(IllegalStateException.class);
        var retryable=draft(s,bytes);
        scanner.next.set(FileScanPort.Verdict.ERROR);
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),retryable.fileId(),
                new ByteArrayInputStream(bytes))).isInstanceOf(IOException.class);
        assertThat(storageCount(s,retryable.fileId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),retryable.fileId())).isEqualTo("STAGED");
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        assertThat(completion.acceptAndComplete(s.actor(),s.tenant(),retryable.fileId(),
                new ByteArrayInputStream(bytes)).state()).isEqualTo("AVAILABLE");
    }

    @Test void wrongSignatureShortReadAndOversizeNeverPublish() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] bytes=pdf();
        var bad=draft(s,bytes);
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),bad.fileId(),
                new ByteArrayInputStream(new byte[bytes.length]))).isInstanceOf(IOException.class);
        assertThat(storageCount(s,bad.fileId())).isZero();
        var truncated=draft(s,bytes);
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),truncated.fileId(),
                new ByteArrayInputStream("%PDF-".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(IOException.class);
        assertThat(storageCount(s,truncated.fileId())).isZero();
        var over=draft(s,bytes);
        byte[] extra=new byte[bytes.length+1];
        System.arraycopy(bytes,0,extra,0,bytes.length);
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),over.fileId(),
                new ByteArrayInputStream(extra))).isInstanceOf(IOException.class);
        assertThat(storageCount(s,over.fileId())).isZero();
    }

    @Test void interruptedStreamCanRetryWithSameIntent() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] content=pdf();
        var intent=draft(s,content);
        InputStream faulty=new ByteArrayInputStream(content) {
            int reads=0;
            @Override public synchronized int read(byte[] b,int off,int len) {
                if(++reads>1)throw new IllegalStateException("Synthetic read abort");
                return super.read(b,off,Math.min(len,5));
            }
        };
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),faulty))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storageCount(s,intent.fileId())).isZero();
        var retry=completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(content));
        assertThat(retry.state()).isEqualTo("AVAILABLE");
    }

    @Test void wrongTenantAndRevokedPermissionNeverReadBinaryStream() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        var foreign=setup();
        byte[] content=pdf();
        var intent=draft(s,content);
        InputStream forbidden=new InputStream() {
            @Override public int read() {throw new AssertionError("No bytes may be read");}
        };
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),foreign.tenant(),
                intent.fileId(),forbidden)).isInstanceOf(SecurityException.class);
        jdbc.update("""
                DELETE FROM public.iam_tenant_role_permission
                WHERE tenant_id=? AND permission_code='FILES_UPLOAD'
                """,s.tenant());
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),
                intent.fileId(),forbidden)).isInstanceOf(SecurityException.class);
        assertThat(storageCount(s,intent.fileId())).isZero();
    }

    @Test void restartCanReopenPersistedFinalBlobByServerKey() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes));
        String persisted=jdbc.queryForObject("""
                SELECT storage_key FROM crm.file_storage WHERE tenant_id=? AND file_id=?
                """,String.class,s.tenant(),intent.fileId());
        // Simulate a newly initialized adapter on the same persistent volume.
        var reinitialized=new LocalFileStorage(localRoot);
        assertThat(reinitialized.exists(persisted)).isTrue();
    }

    @Test void permissionRevokedAfterScanBeforeSqlCommitCannotPublish() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        scanner.beforeVerdict.set(()->jdbc.update("""
                UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?
                """,s.uploader()));
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),
                intent.fileId(),new ByteArrayInputStream(bytes)))
                .isInstanceOf(SecurityException.class);
        assertThat(storageCount(s,intent.fileId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),intent.fileId())).isEqualTo("STAGED");
    }


    @Test void concurrentCompletionIsFencedBeforeSecondStreamIsRead() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        CountDownLatch scanned=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        scanner.beforeVerdict.set(()->{
            scanned.countDown();
            try {
                if(!release.await(15,TimeUnit.SECONDS))
                    throw new IllegalStateException("Concurrent test synchronization timed out");
            } catch(InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->completion.acceptAndComplete(
                    s.actor(),s.tenant(),intent.fileId(),new ByteArrayInputStream(bytes)));
            assertThat(scanned.await(15,TimeUnit.SECONDS)).isTrue();
            InputStream forbidden=new InputStream(){
                @Override public int read(){throw new AssertionError("Concurrent loser must not read bytes");}
            };
            assertThatThrownBy(()->completion.acceptAndComplete(
                    s.actor(),s.tenant(),intent.fileId(),forbidden))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already processing");
            release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).state()).isEqualTo("AVAILABLE");
        } finally {
            release.countDown();
            scanner.beforeVerdict.set(null);
        }
        assertThat(storageCount(s,intent.fileId())).isEqualTo(1);
        assertThat(completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(new byte[0])).state()).isEqualTo("AVAILABLE");
    }

    @Test void crashAfterPromoteBeforeSqlCommitIsReconciledAndRetrySucceeds() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        var claim=metadata.claim(s.uploader(),s.tenant(),intent.fileId());
        var staged=storage.stage(s.tenant(),intent.fileId(),new ByteArrayInputStream(bytes),
                bytes.length,"application/pdf");
        String orphan=storage.promote(s.tenant(),intent.fileId(),staged);
        assertThat(new LocalFileStorage(localRoot).exists(orphan)).isTrue();
        assertThat(storageCount(s,intent.fileId())).isZero();
        // Simulates process death and passage of the configured processing TTL.
        jdbc.update("""
                UPDATE crm.file_object SET processing_started_at=?
                WHERE tenant_id=? AND id=?
                """,Timestamp.from(Instant.now().minus(Duration.ofMinutes(20))),
                s.tenant(),intent.fileId());
        var swept=recovery.reconcileOne(s.tenant(),intent.fileId());
        assertThat(swept.outcome()).isEqualTo("CLEANED");
        assertThat(swept.removed()).isGreaterThanOrEqualTo(1);
        assertThat(storage.exists(orphan)).isFalse();
        assertThatThrownBy(()->metadata.finalizeClean(s.uploader(),s.tenant(),
                intent.fileId(),claim.token(),staged,orphan))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT scan_status FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),intent.fileId())).isEqualTo("NOT_SCANNED");
        assertThat(completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes)).state()).isEqualTo("AVAILABLE");
        assertThat(storageCount(s,intent.fileId())).isEqualTo(1);
    }

    @Test void activeClaimIsNotSweptAndStaleWorkerCannotCommit() throws Exception {
        var s=setup();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        var claim=metadata.claim(s.uploader(),s.tenant(),intent.fileId());
        assertThat(recovery.reconcileOne(s.tenant(),intent.fileId()).outcome()).isEqualTo("BUSY");
        assertThatThrownBy(()->metadata.claim(s.uploader(),s.tenant(),intent.fileId()))
                .isInstanceOf(IllegalStateException.class);
        metadata.release(s.uploader(),s.tenant(),intent.fileId(),claim.token());
        assertThat(metadata.claim(s.uploader(),s.tenant(),intent.fileId()).token())
                .isNotEqualTo(claim.token());
    }

    @Test void corruptAvailableBlobIsQuarantinedWithoutFalseSuccess() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes));
        String key=jdbc.queryForObject("""
                SELECT storage_key FROM crm.file_storage WHERE tenant_id=? AND file_id=?
                """,String.class,s.tenant(),intent.fileId());
        byte[] changed=bytes.clone();
        changed[changed.length-1]^=1; // same size, different digest
        java.nio.file.Files.write(java.nio.file.Path.of(localRoot).resolve(key),changed);
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),
                intent.fileId(),new ByteArrayInputStream(bytes)))
                .isInstanceOf(IOException.class).hasMessageContaining("integrity");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),intent.fileId())).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("""
                SELECT scan_status FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),intent.fileId())).isEqualTo("ERROR");
    }

    @Test void missingAvailableBlobIsQuarantinedByRecovery() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes));
        String key=jdbc.queryForObject("""
                SELECT storage_key FROM crm.file_storage WHERE tenant_id=? AND file_id=?
                """,String.class,s.tenant(),intent.fileId());
        storage.delete(key);
        assertThat(recovery.reconcileOne(s.tenant(),intent.fileId()).outcome())
                .isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,s.tenant(),intent.fileId())).isEqualTo("REJECTED");
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),
                intent.fileId(),new ByteArrayInputStream(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }


    @Test void recoveryBatchIsTenantScopedAndSkipsActiveProcessing() throws Exception {
        var s=setup();
        var foreign=setup();
        byte[] bytes=pdf();
        var mine=draft(s,bytes);
        var theirs=draft(foreign,bytes);
        var myStage=storage.stage(s.tenant(),mine.fileId(),
                new ByteArrayInputStream(bytes),bytes.length,"application/pdf");
        var foreignStage=storage.stage(foreign.tenant(),theirs.fileId(),
                new ByteArrayInputStream(bytes),bytes.length,"application/pdf");
        assertThat(storage.exists(myStage.key())).isTrue();
        assertThat(storage.exists(foreignStage.key())).isTrue();
        var myClaim=metadata.claim(s.uploader(),s.tenant(),mine.fileId());
        var busy=recovery.reconcileBatch(s.tenant(),10);
        assertThat(busy.busy()).isEqualTo(1);
        assertThat(storage.exists(myStage.key())).isTrue();
        metadata.release(s.uploader(),s.tenant(),mine.fileId(),myClaim.token());
        var cleaned=recovery.reconcileBatch(s.tenant(),10);
        assertThat(cleaned.examined()).isEqualTo(1);
        assertThat(cleaned.cleaned()).isEqualTo(1);
        assertThat(storage.exists(myStage.key())).isFalse();
        assertThat(storage.exists(foreignStage.key())).isTrue();
        assertThatThrownBy(()->recovery.reconcileBatch(s.tenant(),101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->recovery.reconcileBatch(null,1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void boundedRecoveryCursorVisitsAllCandidateFiles() throws Exception {
        var s=setup();
        byte[] bytes=pdf();
        var first=draft(s,bytes);
        var second=draft(s,bytes);
        var stagedFirst=storage.stage(s.tenant(),first.fileId(),
                new ByteArrayInputStream(bytes),bytes.length,"application/pdf");
        var stagedSecond=storage.stage(s.tenant(),second.fileId(),
                new ByteArrayInputStream(bytes),bytes.length,"application/pdf");
        var pageOne=recovery.reconcileBatch(s.tenant(),1);
        assertThat(pageOne.examined()).isEqualTo(1);
        assertThat(pageOne.nextCreatedAt()).isNotNull();
        assertThat(pageOne.nextFileId()).isNotNull();
        var pageTwo=recovery.reconcileBatch(s.tenant(),
                pageOne.nextCreatedAt(),pageOne.nextFileId(),1);
        assertThat(pageTwo.examined()).isEqualTo(1);
        assertThat(pageTwo.nextFileId()).isNotEqualTo(pageOne.nextFileId());
        var empty=recovery.reconcileBatch(s.tenant(),
                pageTwo.nextCreatedAt(),pageTwo.nextFileId(),1);
        assertThat(empty.examined()).isZero();
        assertThat(storage.exists(stagedFirst.key())).isFalse();
        assertThat(storage.exists(stagedSecond.key())).isFalse();
    }


    @Test void crmCustomerAttachmentLifecycleRequiresTypedOwnerAndSeparatePermissions() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        var other=setup();
        UUID customer=crm.createCompany(s.actor(),s.tenant(),"Files Synthetic Customer",null).id();
        UUID otherCustomer=crm.createCompany(other.actor(),other.tenant(),
                "Foreign Customer",null).id();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        assertThatThrownBy(()->attachments.attachCustomer(s.actor(),s.tenant(),
                intent.fileId(),customer,"CONTRACT"))
                .isInstanceOf(SecurityException.class);
        completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes));
        assertThatThrownBy(()->attachments.attachCustomer(s.actor(),s.tenant(),
                intent.fileId(),otherCustomer,"CONTRACT"))
                .isInstanceOf(SecurityException.class);
        var id=attachments.attachCustomer(s.actor(),s.tenant(),
                intent.fileId(),customer,"CONTRACT");
        assertThat(attachments.attachCustomer(s.actor(),s.tenant(),
                intent.fileId(),customer,"CONTRACT")).isEqualTo(id);
        assertThat(attachments.listCustomer(s.actor(),s.tenant(),customer,10))
                .extracting(FileAttachmentSummary::attachmentId).containsExactly(id);
        assertThatThrownBy(()->attachments.listCustomer(other.actor(),other.tenant(),
                customer,10)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->attachments.attachCustomer(null,s.tenant(),
                intent.fileId(),customer,"CONTRACT")).isInstanceOf(SecurityException.class);
        attachments.detachCustomer(s.actor(),s.tenant(),id);
        attachments.detachCustomer(s.actor(),s.tenant(),id);
        assertThat(attachments.listCustomer(s.actor(),s.tenant(),customer,10)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_storage
                 WHERE tenant_id=? AND file_id=? AND state='ACTIVE'
                """,Integer.class,s.tenant(),intent.fileId())).isEqualTo(1);
    }

    @Test void crmDocumentAccessRequiresPiiAndFileReadNotOnlyTenantMembership() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        UUID customer=crm.createCompany(s.actor(),s.tenant(),"Private Documents",null).id();
        byte[] bytes=pdf();
        var intent=draft(s,bytes);
        completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes));
        attachments.attachCustomer(s.actor(),s.tenant(),intent.fileId(),customer,"INTERNAL");
        jdbc.update("""
                DELETE FROM public.iam_tenant_role_permission
                 WHERE tenant_id=? AND permission_code='CRM_PII_READ'
                """,s.tenant());
        assertThatThrownBy(()->attachments.listCustomer(s.actor(),s.tenant(),customer,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->attachments.attachCustomer(s.actor(),s.tenant(),
                intent.fileId(),customer,"INTERNAL"))
                .isInstanceOf(SecurityException.class);
        jdbc.update("""
                DELETE FROM public.iam_tenant_role_permission
                 WHERE tenant_id=? AND permission_code='FILES_READ'
                """,s.tenant());
        assertThatThrownBy(()->attachments.listCustomer(s.actor(),s.tenant(),customer,10))
                .isInstanceOf(SecurityException.class);
    }

    @Test void authorizedPlaintextCustomerReadIsScopedAndImmediatelyRevoked() throws Exception {
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        var s=setup();
        UUID customer=crm.createCompany(s.actor(),s.tenant(),"Synthetic Binary Reader",null).id();
        byte[] payload=pdf();
        var intent=draft(s,payload);
        completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(payload));
        UUID attachment=attachments.attachCustomer(s.actor(),s.tenant(),
                intent.fileId(),customer,"INVOICE");
        var download=new java.io.ByteArrayOutputStream();
        assertThat(binaryReads.copyCustomerAttachment(s.actor(),s.tenant(),attachment,download))
                .isEqualTo(payload.length);
        assertThat(download.toByteArray()).isEqualTo(payload);
        var foreign=setup();
        var denied=new java.io.ByteArrayOutputStream();
        assertThatThrownBy(()->binaryReads.copyCustomerAttachment(
                foreign.actor(),foreign.tenant(),attachment,denied))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(denied.size()).isZero();
        jdbc.update("""
                DELETE FROM public.iam_tenant_role_permission
                 WHERE tenant_id=? AND permission_code='FILES_READ'
                """,s.tenant());
        var revoked=new java.io.ByteArrayOutputStream();
        assertThatThrownBy(()->binaryReads.copyCustomerAttachment(
                s.actor(),s.tenant(),attachment,revoked))
                .isInstanceOf(SecurityException.class);
        assertThat(revoked.size()).isZero();
    }

    @Test void unavailableDefaultScannerNeverClaimsClean() {
        assertThat(new UnavailableFileScanner().scan("synthetic")).isEqualTo(FileScanPort.Verdict.ERROR);
    }
}
