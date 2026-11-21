package kz.rm.files;

import kz.rm.iam.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Explicitly enabled real-infrastructure qualification. It MUST run against a
 * real RustFS S3 daemon and real PostgreSQL, never a mocked S3 client.
 */
@SpringBootTest(properties={
    "rm.files.rustfs.enabled=true",
    "rm.files.encrypted-local.enabled=false",
    "rm.files.rustfs.endpoint=${RM_RUSTFS_E2E_ENDPOINT:http://127.0.0.1:9000}",
    "rm.files.rustfs.bucket=${RM_RUSTFS_E2E_BUCKET:rm-files-e2e}",
    "rm.files.rustfs.access-key=${RM_RUSTFS_E2E_ACCESS:rm-rustfs-ci}",
    "rm.files.rustfs.secret-key=${RM_RUSTFS_E2E_SECRET:rm-rustfs-ci-only-secret}",
    "rm.files.rustfs.region=us-east-1"
})
@Import(RustFsLiveInfrastructureE2ETest.TestScanner.class)
@EnabledIfEnvironmentVariable(named="RM_RUSTFS_LIVE_E2E",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RustFsLiveInfrastructureE2ETest {
    @TestConfiguration
    static class TestScanner {
        @Bean @Primary FileScanPort e2eSyntheticScan() {
            return key->FileScanPort.Verdict.CLEAN; // Synthetic fixture ONLY, not live AV qualification.
        }
    }
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired TenantRoleService roles;
    @Autowired AuthenticatedFilesCommands commands;
    @Autowired FileUploadCompletionService complete;
    @Autowired FileMetadataService metadata;
    @Autowired FileRecoveryService recovery;
    @Autowired FileStoragePort storage;
    @Autowired LocalFileStorage local;
    @Autowired RustFsFileStorage rust;
    @Autowired JdbcTemplate jdbc;


    @BeforeAll
    static void ensureSyntheticBucketExists() {
        try(var client=S3Client.builder()
                .endpointOverride(URI.create(System.getenv("RM_RUSTFS_E2E_ENDPOINT")))
                .region(Region.of("us-east-1")).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    System.getenv("RM_RUSTFS_E2E_ACCESS"),
                    System.getenv("RM_RUSTFS_E2E_SECRET")))).build()) {
            var bucket=System.getenv("RM_RUSTFS_E2E_BUCKET");
            try { client.createBucket(CreateBucketRequest.builder().bucket(bucket).build()); }
            catch(software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {}
        }
    }

    private record Tenant(UUID user,UUID tenant,VerifiedActor actor) {}
    private Tenant tenant() {
        var owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "rustfs-e2e-owner-"+UUID.randomUUID(),"RustFS Owner"));
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "rustfs-e2e-user-"+UUID.randomUUID(),"RustFS User"));
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "RustFS Synthetic E2E","RustFS E2E","KZ"));
        var space=workspaces.saveAndFlush(WorkspaceEntity.organization(
                UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner.getId(),
                OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),user.getId(),
                OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",space.getId());
        var role=roles.createRole(owner.getId(),space.getId(),
                "S3_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "E2E synthetic uploads");
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.FILES_UPLOAD);
        roles.grant(owner.getId(),space.getId(),role.id(),DomainPermission.FILES_READ);
        roles.assign(owner.getId(),space.getId(),user.getId(),role.id());
        roles.enable(owner.getId(),space.getId());
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,user.getId());
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        var actor=new OidcActorResolver(decoder,users).resolve("synthetic-live-rustfs");
        return new Tenant(user.getId(),space.getId(),actor);
    }

    private byte[] pdf() {
        byte[] bytes=new byte[256*1024+13];
        new java.security.SecureRandom().nextBytes(bytes);
        byte[] prefix="%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix,0,bytes,0,prefix.length);
        return bytes;
    }
    private FileUploadIntent begin(Tenant tenant,byte[] content) {
        return commands.begin(tenant.actor(),tenant.tenant(),"application/pdf",content.length,
                "rustfs-e2e-"+UUID.randomUUID());
    }
    private record Stored(String provider,String key,String sha,int format) {}
    private Stored stored(Tenant tenant,UUID file) {
        return jdbc.query("""
                SELECT s.provider,s.storage_key,f.sha256,s.encrypted_format_version
                  FROM crm.file_storage s JOIN crm.file_object f
                    ON f.tenant_id=s.tenant_id AND f.id=s.file_id
                 WHERE s.tenant_id=? AND s.file_id=? AND s.state='ACTIVE'
                """,rs->{
                    assertThat(rs.next()).isTrue();
                    return new Stored(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4));
                },tenant.tenant(),file);
    }
    private byte[] readBack(Tenant tenant,UUID file,String key) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        storage.streamVerified(tenant.tenant(),file,key,out);
        return out.toByteArray();
    }

    @Test @Order(1)
    void realRustfsUploadReadNewClientAndReverseFlagRetainBothProviders() throws Exception {
        assertThat(storage).isInstanceOf(SwitchableFileStorage.class);
        Tenant t=tenant();byte[] bytes=pdf();
        var intent=begin(t,bytes);
        assertThat(complete.acceptAndComplete(t.actor(),t.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes)).state()).isEqualTo("AVAILABLE");
        Stored ptr=stored(t,intent.fileId());
        assertThat(ptr.provider()).isEqualTo("RUSTFS");
        assertThat(ptr.key()).startsWith("rustfs/"+t.tenant()+"/"+intent.fileId()+"/final-");
        assertThat(ptr.format()).isZero();
        assertThat(storage.matches(ptr.key(),bytes.length,ptr.sha())).isTrue();
        assertThat(readBack(t,intent.fileId(),ptr.key())).isEqualTo(bytes);

        // A new S3 client models RM restarting while RustFS daemon+volume remain.
        try(var other=S3Client.builder()
                .endpointOverride(URI.create(System.getenv("RM_RUSTFS_E2E_ENDPOINT")))
                .region(Region.of("us-east-1")).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv("RM_RUSTFS_E2E_ACCESS"),
                        System.getenv("RM_RUSTFS_E2E_SECRET")))).build()) {
            var restarted=new RustFsFileStorage(other,System.getenv("RM_RUSTFS_E2E_BUCKET"));
            assertThat(restarted.matches(ptr.key(),bytes.length,ptr.sha())).isTrue();
            var read=new ByteArrayOutputStream();
            restarted.streamVerified(t.tenant(),intent.fileId(),ptr.key(),read);
            assertThat(read.toByteArray()).isEqualTo(bytes);
        }

        // Switch back to LOCAL without moving or modifying the RustFS object.
        @SuppressWarnings("unchecked")
        ObjectProvider<RustFsFileStorage> provider=
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(rust);
        var switchedBack=new SwitchableFileStorage(local,provider,false,false);
        var fresh=switchedBack.stage(t.tenant(),UUID.randomUUID(),
                new ByteArrayInputStream(bytes),bytes.length,"application/pdf");
        assertThat(fresh.key()).doesNotStartWith("rustfs/");
        assertThat(switchedBack.exists(ptr.key())).isTrue();
        assertThat(switchedBack.matches(ptr.key(),bytes.length,ptr.sha())).isTrue();
        switchedBack.delete(fresh.key());
    }

    @Test @Order(2)
    void crashAfterCopyBeforeCommitSweepOldTokenAndCompleteRetry() throws Exception {
        Tenant t=tenant();byte[] bytes=pdf();var intent=begin(t,bytes);
        var claim=metadata.claim(t.user(),t.tenant(),intent.fileId());
        var staged=storage.stage(t.tenant(),intent.fileId(),new ByteArrayInputStream(bytes),
                bytes.length,"application/pdf");
        String orphan=storage.promote(t.tenant(),intent.fileId(),staged);
        assertThat(storage.exists(orphan)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_storage WHERE tenant_id=? AND file_id=?
                """,Integer.class,t.tenant(),intent.fileId())).isZero();

        jdbc.update("""
                UPDATE crm.file_object SET processing_started_at=?
                 WHERE tenant_id=? AND id=?
                """,Timestamp.from(Instant.now().minus(Duration.ofMinutes(20))),
                t.tenant(),intent.fileId());
        var recovered=recovery.reconcileOne(t.tenant(),intent.fileId());
        assertThat(recovered.outcome()).isEqualTo("CLEANED");
        assertThat(recovered.removed()).isGreaterThanOrEqualTo(1);
        assertThat(storage.exists(orphan)).isFalse();
        assertThatThrownBy(()->metadata.finalizeClean(t.user(),t.tenant(),
                intent.fileId(),claim.token(),staged,orphan))
                .isInstanceOf(IllegalStateException.class);
        assertThat(complete.acceptAndComplete(t.actor(),t.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes)).state()).isEqualTo("AVAILABLE");
        Stored actual=stored(t,intent.fileId());
        assertThat(actual.provider()).isEqualTo("RUSTFS");
        assertThat(storage.matches(actual.key(),bytes.length,actual.sha())).isTrue();
    }

    @Test @Order(3)
    void missingObjectIsQuarantinedOnlyAfterReal404AndCrossTenantAccessIsDenied()
            throws Exception {
        Tenant t=tenant();byte[] bytes=pdf();
        var intent=begin(t,bytes);
        complete.acceptAndComplete(t.actor(),t.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes));
        Stored stored=stored(t,intent.fileId());
        Tenant foreign=tenant();
        assertThatThrownBy(()->storage.streamVerified(foreign.tenant(),
                intent.fileId(),stored.key(),new ByteArrayOutputStream()))
                .isInstanceOf(SecurityException.class);
        storage.delete(stored.key());
        assertThat(recovery.reconcileOne(t.tenant(),intent.fileId()).outcome())
                .isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.file_object WHERE tenant_id=? AND id=?
                """,String.class,t.tenant(),intent.fileId())).isEqualTo("REJECTED");
    }
}
