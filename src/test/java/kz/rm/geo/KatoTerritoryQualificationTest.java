package kz.rm.geo;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class KatoTerritoryQualificationTest {
    @Autowired KatoTerritoryRepository kato;
    @Autowired OrganizationLocationRepository locations;
    @Autowired OrganizationLocationService service;
    @Autowired OrganizationRepository orgs;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test void sourceSnapshotPreservesActiveAndDeletedRows() {
        assertThat(kato.count()).isEqualTo(100);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ref_kato_territory WHERE is_deleted=TRUE",Integer.class))
                .isEqualTo(13);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ref_kato_territory WHERE parent_source_id IS NOT NULL " +
                "AND parent_source_id NOT IN (SELECT source_id FROM ref_kato_territory)",Integer.class))
                .isEqualTo(11);
    }

    @Test void branchesAndWarehousesBelongOnlyToOrganizationWorkspace() {
        var org=orgs.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"Example LLP","Example","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        String activeCode=jdbc.queryForObject(
                "SELECT code FROM ref_kato_territory WHERE is_deleted=FALSE ORDER BY code LIMIT 1",String.class);
        var branch=service.create(workspace.getId(),OrganizationLocationEntity.Type.BRANCH,
                "Head office",activeCode,"Office 1");
        var warehouse=service.create(workspace.getId(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Stock 1",activeCode,"Industrial zone");
        var pos=service.create(workspace.getId(),OrganizationLocationEntity.Type.POINT_OF_SALE,
                "Retail 1",activeCode,"Street 10");
        assertThat(locations.findByWorkspaceId(workspace.getId())).hasSize(3);
        assertThat(locations.findByIdAndWorkspaceId(branch.getId(),UUID.randomUUID())).isEmpty();
        assertThat(branch.getKatoCode()).isEqualTo(activeCode);
        assertThat(warehouse.getKatoCode()).isEqualTo(activeCode);
        assertThat(pos.getKatoCode()).isEqualTo(activeCode);
        String settlementCode=jdbc.queryForObject(
                "SELECT code FROM ref_kato_territory WHERE level_no > 3 AND is_deleted=FALSE ORDER BY code LIMIT 1",String.class);
        assertThatThrownBy(() -> service.create(workspace.getId(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Too detailed",settlementCode,null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("district-level");
        String deletedCode=jdbc.queryForObject(
                "SELECT code FROM ref_kato_territory WHERE is_deleted=TRUE ORDER BY code LIMIT 1",String.class);
        assertThatThrownBy(() -> service.create(workspace.getId(),OrganizationLocationEntity.Type.BRANCH,
                "Obsolete",deletedCode,null)).isInstanceOf(IllegalArgumentException.class);
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"subject-"+UUID.randomUUID(),"Private"));
        var personal=workspaces.saveAndFlush(WorkspaceEntity.personal(UUID.randomUUID(),user.getId()));
        assertThatThrownBy(() -> service.create(personal.getId(),OrganizationLocationEntity.Type.BRANCH,
                "Not permitted",activeCode,null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void databaseRejectsUnknownTerritoryCode() {
        var org=orgs.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"Second LLP","Second","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        assertThatThrownBy(() -> jdbc.update("""
             INSERT INTO org_location (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
             VALUES (?,?,'WAREHOUSE','Unknown','000000000000','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
             """,UUID.randomUUID(),workspace.getId())).isInstanceOf(Exception.class);
    }
}
