package kz.rm.files;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class FilesR10aQualificationTest {
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired TenantRoleService roles;
    @Autowired AuthenticatedFilesCommands files;
    @Autowired AuthenticatedFilesQueries queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private record Setup(UUID owner,UUID employee,UUID tenant){}
    private Setup create() {
        var owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "file-owner-"+UUID.randomUUID(),"File Owner"));
        var employee=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "file-employee-"+UUID.randomUUID(),"File Employee"));
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Synthetic Files LLC","Synthetic Files","KZ"));
        var space=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),owner.getId(),OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),employee.getId(),OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",space.getId());
        return new Setup(owner.getId(),employee.getId(),space.getId());
    }
    private VerifiedActor verified(UUID id) {
        String sub=jdbc.queryForObject("SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                        .header("alg","RS256").subject(sub).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-files-token");
    }
    private void enable(Setup s) {
        var role=roles.createRole(s.owner(),s.tenant(),
                "FILE_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Synthetic file operator");
        roles.grant(s.owner(),s.tenant(),role.id(),DomainPermission.FILES_UPLOAD);
        roles.grant(s.owner(),s.tenant(),role.id(),DomainPermission.FILES_READ);
        roles.assign(s.owner(),s.tenant(),s.employee(),role.id());
        roles.enable(s.owner(),s.tenant());
    }

    @Test void legacyModeDeniesFilesIncludingOrganizationOwner() {
        var s=create();
        assertThatThrownBy(()->files.begin(verified(s.owner()),s.tenant(),
                "application/pdf",1200,"legacykey001")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->files.begin(verified(s.employee()),s.tenant(),
                "application/pdf",1200,"legacykey001")).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_object WHERE tenant_id=?
                """,Integer.class,s.tenant())).isZero();
    }

    @Test void verifiedMetadataBeginIsIdempotentAndTenantScoped() {
        var s=create();
        var foreign=create();
        enable(s);
        var actor=verified(s.employee());
        var draft=files.begin(actor,s.tenant(),"application/pdf",1024,"intent-"+UUID.randomUUID());
        assertThat(draft.state()).isEqualTo("STAGED");
        assertThat(draft.scanStatus()).isEqualTo("NOT_SCANNED");
        assertThat(queries.ownStaged(actor,s.tenant(),draft.fileId())).isEqualTo(draft);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_storage WHERE tenant_id=? AND file_id=?
                """,Integer.class,s.tenant(),draft.fileId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_attachment WHERE tenant_id=? AND file_id=?
                """,Integer.class,s.tenant(),draft.fileId())).isZero();
        String key="retry-"+UUID.randomUUID();
        var first=files.begin(actor,s.tenant(),"image/png",500,key);
        assertThat(files.begin(actor,s.tenant(),"image/png",500,key).fileId())
                .isEqualTo(first.fileId());
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"image/jpeg",500,key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"image/png",501,key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->files.begin(null,s.tenant(),"image/png",500,"deniedkey"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->files.begin(actor,foreign.tenant(),
                "image/png",500,"foreignkey001")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->queries.ownStaged(actor,foreign.tenant(),draft.fileId()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->queries.ownStaged(actor,s.tenant(),UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_object WHERE tenant_id=?
                """,Integer.class,s.tenant())).isEqualTo(2);
    }

    @Test void invalidMetadataNeverPersistsAndRollbackFreesRequestKey() {
        var s=create();
        enable(s);
        var actor=verified(s.employee());
        String key="rollback-"+UUID.randomUUID();
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"text/html",100,key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"image/png",0,key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"image/png",20L*1024*1024+1,key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"application/pdf",25L*1024*1024+1,key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"image/png",100,"bad"))
                .isInstanceOf(IllegalArgumentException.class);
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{
            files.begin(actor,s.tenant(),"image/png",100,key);
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_object WHERE tenant_id=? AND request_key=?
                """,Integer.class,s.tenant(),key)).isZero();
        var retry=files.begin(actor,s.tenant(),"image/png",100,key);
        assertThat(retry.state()).isEqualTo("STAGED");
    }

    @Test void suspendedActorDeniedEvenWithValidExistingRequestKey() {
        var s=create();
        enable(s);
        var actor=verified(s.employee());
        String key="suspend-"+UUID.randomUUID();
        files.begin(actor,s.tenant(),"image/png",100,key);
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",s.employee());
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"image/png",100,key))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->queries.ownStaged(actor,s.tenant(),UUID.randomUUID()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void roleGrantRevocationDeniesNewMetadataOperations() {
        var s=create();
        enable(s);
        var actor=verified(s.employee());
        var current=files.begin(actor,s.tenant(),"image/png",100,"file-"+UUID.randomUUID());
        assertThat(queries.ownStaged(actor,s.tenant(),current.fileId()).state())
                .isEqualTo("STAGED");
        jdbc.update("""
                DELETE FROM public.iam_tenant_role_permission
                WHERE tenant_id=? AND permission_code='FILES_UPLOAD'
                """,s.tenant());
        assertThatThrownBy(()->files.begin(actor,s.tenant(),"image/png",100,
                "after-revoke-"+UUID.randomUUID())).isInstanceOf(SecurityException.class);
    }
}
