package kz.rm.geo;

import kz.rm.iam.*;
import kz.rm.organization.OrganizationLocationEntity;
import kz.rm.organization.dto.LocationSearch;
import kz.rm.organization.AuthenticatedOrganizationLocations;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class KatoTerritoryQualificationTest {
    @Autowired KatoTerritoryRepository kato;
    @Autowired KatoTerritoryDirectory directory;
    @Autowired KatoLocationPolicy locationPolicy;
    @Autowired AuthenticatedOrganizationLocations service;
    @Autowired OrganizationRepository orgs;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;

    private VerifiedActor verified(UUID id) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }

    @Test void sourceSnapshotPreservesActiveAndDeletedRows() {
        assertThat(kato.count()).isEqualTo(465);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ref_kato_territory WHERE is_deleted=TRUE",Integer.class))
                .isEqualTo(13);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ref_kato_territory WHERE parent_source_id IS NOT NULL " +
                "AND parent_source_id NOT IN (SELECT source_id FROM ref_kato_territory)",Integer.class))
                .isEqualTo(10);
    }

    @Test void administrativeReleaseHasTwentyRegionsAndClosedParentChains() {
        var rows = kato.findAll();
        var byId = new java.util.HashMap<Long,KatoTerritoryEntity>();
        rows.forEach(r -> byId.put(r.getSourceId(),r));
        var retained = java.util.Set.of("110000000","111810000","430000000","431000000","431010000",
                "431900000","431910000","433200000","433220000","433220100");
        var admin = rows.stream().filter(r -> r.getSourceId() >= 1_000_000_000L || retained.contains(r.getCode())).toList();
        assertThat(admin).hasSize(375).allMatch(r -> !r.isDeleted());
        var adminIds = admin.stream().map(KatoTerritoryEntity::getSourceId).collect(java.util.stream.Collectors.toSet());
        assertThat(admin.stream().filter(r -> r.getLevel()==2).map(KatoTerritoryEntity::getCode).toList())
                .containsExactlyInAnyOrder("100000000","110000000","150000000","190000000","230000000","270000000","310000000","330000000","350000000","390000000","430000000","470000000","550000000","590000000","610000000","620000000","630000000","710000000","750000000","790000000");
        for (var r : admin) {
            var node = r;
            var seen = new java.util.HashSet<Long>();
            while (node.getLevel() > 2) {
                assertThat(seen.add(node.getSourceId())).isTrue();
                var parent = byId.get(node.getParentSourceId());
                assertThat(parent).as("parent for %s",node.getCode()).isNotNull();
                assertThat(adminIds).contains(parent.getSourceId());
                assertThat(parent.getLevel()).isLessThan(node.getLevel());
                node = parent;
            }
            assertThat(node.getLevel()).isEqualTo(2);
            assertThat(node.getParentSourceId()).isNull();
        }
        assertThat(kato.findById("111810000").orElseThrow().getSourceId()).isEqualTo(10L);
        assertThat(kato.findById("111810000").orElseThrow().getParentSourceId()).isEqualTo(1111800000L);
    }

    @Test void romanizedNamesAreLoadedAndCodeIsOnlyTechnicalReference() {
        var oblast = kato.findById("110000000").orElseThrow();
        assertThat(oblast.getNameKk()).isEqualTo("АҚМОЛА ОБЛЫСЫ");
        assertThat(oblast.getNameRu()).isEqualTo("АКМОЛИНСКАЯ ОБЛАСТЬ");
        assertThat(oblast.getNameEng()).isEqualTo("AKMOLINSKAYA OBLAST");
        assertThat(directory.eligibleForOrganizationLocation())
                .allMatch(x -> x.territoryType().locationEligible() && !x.deleted()
                        && !x.nameEng().isBlank() && !x.code().isBlank());
        assertThat(directory.eligibleForOrganizationLocation()).hasSize(375);
    }

    @Test void branchesAndWarehousesBelongOnlyToOrganizationWorkspace() {
        var org=orgs.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"Example LLP","Example","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        var actor=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"subject-"+UUID.randomUUID(),"Owner"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),actor.getId(),
                OrganizationMembershipEntity.Role.OWNER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        String activeCode=jdbc.queryForObject(
                "SELECT code FROM ref_kato_territory WHERE is_deleted=FALSE AND level_no <= 3 ORDER BY code LIMIT 1",String.class);
        var branch=service.create(verified(actor.getId()),workspace.getId(),OrganizationLocationEntity.Type.BRANCH,
                "Head office",activeCode,"Office 1");
        var warehouse=service.create(verified(actor.getId()),workspace.getId(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Stock 1",activeCode,"Industrial zone");
        var pos=service.create(verified(actor.getId()),workspace.getId(),OrganizationLocationEntity.Type.POINT_OF_SALE,
                "Retail 1",activeCode,"Street 10");
        // Cross-module tests use the verified-actor application boundary, not raw JPA.
        var ownerPrincipal=verified(actor.getId());
        assertThat(service.search(ownerPrincipal,workspace.getId(),
                new LocationSearch(null,null,null),0,10).getTotalElements()).isEqualTo(3);
        assertThat(service.get(ownerPrincipal,workspace.getId(),branch.id()).id()).isEqualTo(branch.id());
        assertThatThrownBy(() -> service.get(ownerPrincipal,workspace.getId(),UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(branch.katoCode()).isEqualTo(activeCode);
        assertThat(warehouse.katoCode()).isEqualTo(activeCode);
        assertThat(pos.katoCode()).isEqualTo(activeCode);
        // Actual city and city-district admission, no depth-based rejection.
        var cityWarehouse=service.create(ownerPrincipal,workspace.getId(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Semey warehouse","101010000","City stock");
        var cityDistrict=service.create(ownerPrincipal,workspace.getId(),OrganizationLocationEntity.Type.BRANCH,
                "Almaty district branch","751410000","District office");
        assertThat(cityWarehouse.katoCode()).isEqualTo("101010000");
        assertThat(cityDistrict.katoCode()).isEqualTo("751410000");
        var outsider=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "subject-"+UUID.randomUUID(),"Outsider"));
        assertThatThrownBy(()->service.create(verified(outsider.getId()),workspace.getId(),
                OrganizationLocationEntity.Type.BRANCH,"Forbidden",activeCode,null))
                .isInstanceOf(SecurityException.class);
        var viewer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "subject-"+UUID.randomUUID(),"Viewer"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),viewer.getId(),
                OrganizationMembershipEntity.Role.VIEWER));
        assertThatThrownBy(()->service.create(verified(viewer.getId()),workspace.getId(),
                OrganizationLocationEntity.Type.BRANCH,"Read only",activeCode,null))
                .isInstanceOf(SecurityException.class);
        String settlementCode=jdbc.queryForObject(
                "SELECT code FROM ref_kato_territory WHERE territory_type='LEGACY_OTHER' AND is_deleted=FALSE ORDER BY code LIMIT 1",String.class);
        assertThatThrownBy(() -> service.create(verified(actor.getId()),workspace.getId(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Too detailed",settlementCode,null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved administrative territories");
        String deletedCode=jdbc.queryForObject(
                "SELECT code FROM ref_kato_territory WHERE is_deleted=TRUE ORDER BY code LIMIT 1",String.class);
        assertThatThrownBy(() -> service.create(verified(actor.getId()),workspace.getId(),OrganizationLocationEntity.Type.BRANCH,
                "Obsolete",deletedCode,null)).isInstanceOf(IllegalArgumentException.class);
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"subject-"+UUID.randomUUID(),"Private"));
        var personal=workspaces.saveAndFlush(WorkspaceEntity.personal(UUID.randomUUID(),user.getId()));
        assertThatThrownBy(() -> service.create(verified(actor.getId()),personal.getId(),OrganizationLocationEntity.Type.BRANCH,
                "Not permitted",activeCode,null)).isInstanceOf(SecurityException.class);
    }

    @Test void semanticTypesAreExplicitAndLocationAdmissionIsNotNumericDepth() {
        var rows=kato.findAll();
        var counts=rows.stream().collect(java.util.stream.Collectors.groupingBy(
                KatoTerritoryEntity::getTerritoryType,java.util.stream.Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                KatoTerritoryType.OBLAST,17L,KatoTerritoryType.REPUBLICAN_CITY,3L,
                KatoTerritoryType.DISTRICT,170L,KatoTerritoryType.CITY,87L,
                KatoTerritoryType.CITY_DISTRICT,25L,KatoTerritoryType.CITY_ADMINISTRATION,71L,
                KatoTerritoryType.DISTRICT_ADMINISTRATION,2L,KatoTerritoryType.LEGACY_OTHER,90L));
        assertThat(kato.findById("620000000").orElseThrow().getTerritoryType()).isEqualTo(KatoTerritoryType.OBLAST);
        assertThat(kato.findById("790000000").orElseThrow().getTerritoryType()).isEqualTo(KatoTerritoryType.REPUBLICAN_CITY);
        assertThat(kato.findById("101010000").orElseThrow().getTerritoryType()).isEqualTo(KatoTerritoryType.CITY);
        assertThat(kato.findById("103620100").orElseThrow().getTerritoryType()).isEqualTo(KatoTerritoryType.CITY);
        assertThat(kato.findById("751410000").orElseThrow().getTerritoryType()).isEqualTo(KatoTerritoryType.CITY_DISTRICT);
        for(String code:java.util.List.of("100000000","790000000","101010000","103620100","751410000"))
            assertThatCode(()->locationPolicy.requireOrganizationLocationEligible(code)).doesNotThrowAnyException();
        for(String code:java.util.List.of("10000000","1000000000"," 100000000","10000000X"))
            assertThatThrownBy(()->locationPolicy.requireOrganizationLocationEligible(code))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nine ASCII digits");
        assertThatThrownBy(()->locationPolicy.requireOrganizationLocationEligible("000000000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown or deleted");
        assertThatThrownBy(()->locationPolicy.requireOrganizationLocationEligible("113445400"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("approved administrative");
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
