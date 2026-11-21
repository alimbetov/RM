package kz.rm.iam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class IamFoundationQualificationTest {
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository members;
    @Autowired GlobalRoleAssignmentRepository globals;
    @Autowired JdbcTemplate jdbc;

    @Test void personalAndOrganizationWorkspacesAreIsolatedAndMembersAreScoped() {
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"subject-"+UUID.randomUUID(),"First"));
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"Acme LLP","Acme","KZ"));
        var personal=workspaces.saveAndFlush(WorkspaceEntity.personal(UUID.randomUUID(),user.getId()));
        var business=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        members.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),user.getId(),
                OrganizationMembershipEntity.Role.OWNER));
        assertThat(personal.getKind()).isEqualTo(WorkspaceEntity.Kind.PERSONAL);
        assertThat(business.getKind()).isEqualTo(WorkspaceEntity.Kind.ORGANIZATION);
        assertThat(personal.getId()).isNotEqualTo(business.getId());
        assertThat(workspaces.findByOwnerUserId(user.getId())).isPresent();
        assertThat(workspaces.findByOrganizationId(org.getId())).isPresent();
        assertThat(members.findByOrganizationIdAndUserId(org.getId(),user.getId())).isPresent();
        assertThat(members.findByUserId(user.getId())).hasSize(1);
    }

    @Test void platformAuditorIsGlobalAndCannotBecomeOrgOwnerImplicitly() {
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"subject-"+UUID.randomUUID(),"Auditor"));
        globals.saveAndFlush(new GlobalRoleAssignmentEntity(UUID.randomUUID(),user.getId(),
                GlobalRoleAssignmentEntity.Role.PLATFORM_AUDITOR));
        assertThat(globals.findByUserIdAndRevokedAtIsNull(user.getId()))
                .extracting(GlobalRoleAssignmentEntity::getRole)
                .containsExactly(GlobalRoleAssignmentEntity.Role.PLATFORM_AUDITOR);
        assertThat(members.findByUserId(user.getId())).isEmpty();
    }

    @Test void schemaRejectsCrossKindWorkspaceAndDuplicatePersonalOwner() {
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"subject-"+UUID.randomUUID(),"Solo"));
        workspaces.saveAndFlush(WorkspaceEntity.personal(UUID.randomUUID(),user.getId()));
        assertThatThrownBy(() -> jdbc.update("""
             INSERT INTO iam_workspace (id,kind,owner_user_id,organization_id,status,created_at,row_version)
             VALUES (?, 'PERSONAL', ?, NULL, 'ACTIVE', CURRENT_TIMESTAMP, 0)
             """,UUID.randomUUID(),user.getId())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
             INSERT INTO iam_workspace (id,kind,owner_user_id,organization_id,status,created_at,row_version)
             VALUES (?, 'ORGANIZATION', ?, NULL, 'ACTIVE', CURRENT_TIMESTAMP, 0)
             """,UUID.randomUUID(),user.getId())).isInstanceOf(Exception.class);
    }

    @Test void dbRejectsCrossRoleValues() {
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"subject-"+UUID.randomUUID(),"NoPaidRole"));
        assertThatThrownBy(() -> jdbc.update("""
              INSERT INTO iam_global_role_assignment(id,user_id,role,granted_at)
              VALUES (?,?, 'PAID', CURRENT_TIMESTAMP)
              """,UUID.randomUUID(),user.getId())).isInstanceOf(Exception.class);
    }
}
