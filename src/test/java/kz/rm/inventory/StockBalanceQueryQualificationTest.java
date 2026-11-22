package kz.rm.inventory;

import kz.rm.iam.*;
import kz.rm.inventory.dto.StockSearch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class StockBalanceQueryQualificationTest {
    @Autowired AuthenticatedInventoryQueries queries;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired VerifiedTenantRoleCommands roles;

    private record Fixture(UUID tenant, UUID actor, UUID location, UUID stockItem,
                           UUID emptyItem) {}

    private Fixture fixture() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Stock Query LLP","Stock Query","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        var account=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"stock-query-"+UUID.randomUUID(),"Stock reader"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),account.getId(),
                OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        UUID location=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.org_location
                  (id,workspace_id,location_type,name,kato_code,status,
                   created_at,updated_at,row_version)
                VALUES (?,?,'WAREHOUSE','Query stock',?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """,location,tenant,kato);
        for(UUID item:new UUID[]{a,b}) {
            jdbc.update("""
                    INSERT INTO crm.cat_item
                    (tenant_id,id,sku,kind,source_locale,state,created_at)
                    VALUES (?,?,?,'PRODUCT','en','PUBLISHED',CURRENT_TIMESTAMP)
                    """,tenant,item,"search-"+UUID.randomUUID());
        }
        jdbc.update("""
                INSERT INTO crm.inv_stock_balance
                  (tenant_id,location_id,item_id,on_hand,reserved)
                VALUES (?,?,?,10,3),(?,?,?,0,0)
                """,tenant,location,a,tenant,location,b);
        return new Fixture(tenant,account.getId(),location,a,b);
    }

    private VerifiedActor actor(UUID id) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        JwtDecoder decoder=token->Jwt.withTokenValue(token).header("alg","RS256")
                .subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }

    @Test void verifiedStockSearchPaginatesFiltersAndDeniesCrossTenant() {
        Fixture one=fixture(),two=fixture();
        VerifiedActor trusted=actor(one.actor());
        var firstPage=queries.search(trusted,one.tenant(),null,0,1);
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(1);
        var secondPage=queries.search(trusted,one.tenant(),null,1,1);
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.getContent().getFirst().itemId())
                .isNotEqualTo(firstPage.getContent().getFirst().itemId());
        var available=queries.search(trusted,one.tenant(),
                new StockSearch(one.location(),null,true),0,10);
        assertThat(available.getContent()).hasSize(1);
        assertThat(available.getContent().getFirst().itemId()).isEqualTo(one.stockItem());
        assertThat(available.getContent().getFirst().available()).isEqualByComparingTo("7");
        assertThat(queries.search(trusted,one.tenant(),
                new StockSearch(null,one.emptyItem(),false),0,10).getTotalElements())
                .isEqualTo(1);
        // Known foreign location/item UUIDs must not switch the selected tenant.
        assertThat(queries.search(trusted,one.tenant(),
                new StockSearch(two.location(),null,false),0,10).getTotalElements()).isZero();
        assertThat(queries.search(trusted,one.tenant(),
                new StockSearch(null,two.stockItem(),false),0,10).getTotalElements()).isZero();
        assertThat(queries.search(trusted,one.tenant(),
                new StockSearch(two.location(),two.stockItem(),true),0,10)
                .getContent()).isEmpty();
        assertThat(firstPage.getContent()).allSatisfy(row ->
                assertThat(row.tenantId()).isEqualTo(one.tenant()));
        assertThat(secondPage.getContent()).allSatisfy(row ->
                assertThat(row.tenantId()).isEqualTo(one.tenant()));
        assertThatThrownBy(()->queries.search(null,one.tenant(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->queries.search(trusted,two.tenant(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->queries.search(trusted,one.tenant(),null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void tcrud03SharedMemberInventoryReadCannotBorrowOtherTenantGrants() {
        Fixture a=fixture(),b=fixture();
        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "tcrud03-inventory-"+UUID.randomUUID(),"Multi tenant inventory")).getId();
        UUID orgA=jdbc.queryForObject("SELECT organization_id FROM iam_workspace WHERE id=?",
                UUID.class,a.tenant());
        UUID orgB=jdbc.queryForObject("SELECT organization_id FROM iam_workspace WHERE id=?",
                UUID.class,b.tenant());
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgA,
                staff,OrganizationMembershipEntity.Role.MEMBER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgB,
                staff,OrganizationMembershipEntity.Role.MEMBER));
        UUID ownerA=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "tcrud03-ownerA-"+UUID.randomUUID(),"Inventory owner A")).getId();
        UUID ownerB=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "tcrud03-ownerB-"+UUID.randomUUID(),"Inventory owner B")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgA,
                ownerA,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgB,
                ownerB,OrganizationMembershipEntity.Role.OWNER));
        var ar=roles.createRole(verified(ownerA),a.tenant(),
                "INV_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),"A stock");
        roles.grant(verified(ownerA),a.tenant(),ar.id(),DomainPermission.INVENTORY_STOCK_READ);
        roles.assign(verified(ownerA),a.tenant(),staff,ar.id());
        roles.enable(verified(ownerA),a.tenant());
        var br=roles.createRole(verified(ownerB),b.tenant(),
                "INV_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),"B nonstock");
        roles.grant(verified(ownerB),b.tenant(),br.id(),DomainPermission.CRM_READ);
        roles.assign(verified(ownerB),b.tenant(),staff,br.id());
        roles.enable(verified(ownerB),b.tenant());
        var shared=actor(staff);
        var first=queries.search(shared,a.tenant(),null,0,1);
        var second=queries.search(shared,a.tenant(),null,1,1);
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent().getFirst().tenantId()).isEqualTo(a.tenant());
        assertThat(second.getContent().getFirst().tenantId()).isEqualTo(a.tenant());
        assertThat(queries.search(shared,a.tenant(),
                new StockSearch(b.location(),null,false),0,10).getTotalElements()).isZero();
        assertThat(queries.search(shared,a.tenant(),
                new StockSearch(null,b.stockItem(),false),0,10).getTotalElements()).isZero();
        assertThat(queries.search(shared,a.tenant(),
                new StockSearch(a.location(),a.stockItem(),true),0,10)
                .getContent()).extracting(x->x.itemId()).containsExactly(a.stockItem());
        assertThatThrownBy(()->queries.search(shared,b.tenant(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->queries.search(shared,b.tenant(),
                new StockSearch(b.location(),b.stockItem(),false),0,10))
                .isInstanceOf(SecurityException.class);
    }

}
