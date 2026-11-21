package kz.rm.files;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
        @Override public Verdict scan(String privateStagingKey) {return next.get();}
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
    @Autowired JdbcTemplate jdbc;

    private record Setup(UUID owner,UUID uploader,UUID tenant,VerifiedActor actor) {}
    private Setup setup() {
        var owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "files-b-owner-"+UUID.randomUUID(),"Owner"));
        var uploader=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "files-b-user-"+UUID.randomUUID(),"Uploader"));
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Synthetic Storage","Synthetic","KZ"));
        var space=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),
                owner.getId(),OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),
                uploader.getId(),OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",space.getId());
        var role=roles.createRole(owner.getId(),space.getId(),
                "FILE_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Uploader");
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.FILES_UPLOAD);
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.FILES_READ);
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
                """,String.class,s.tenant(),intent.fileId())).isEqualTo("STAGED");
        scanner.next.set(FileScanPort.Verdict.ERROR);
        assertThatThrownBy(()->completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes))).isInstanceOf(IOException.class);
        assertThat(storageCount(s,intent.fileId())).isZero();
        scanner.next.set(FileScanPort.Verdict.CLEAN);
        assertThat(completion.acceptAndComplete(s.actor(),s.tenant(),intent.fileId(),
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

    @Test void unavailableDefaultScannerNeverClaimsClean() {
        assertThat(new UnavailableFileScanner().scan("synthetic")).isEqualTo(FileScanPort.Verdict.ERROR);
    }
}
