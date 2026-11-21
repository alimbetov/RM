package kz.rm.organization;

import kz.rm.iam.*;
import kz.rm.organization.dto.LocationSearch;
import kz.rm.inventory.StockBalanceQueryService;
import kz.rm.inventory.dto.StockSearch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class OrganizationLocationQueryQualificationTest {
    @Autowired OrganizationLocationService create;
    @Autowired OrganizationLocationQueryService query;
    @Autowired StockBalanceQueryService stock;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;

    private record Context(UUID actor,UUID tenant) {}
    private Context setup() {
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Location Query LLP","Location Query","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        var user=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"query-"+UUID.randomUUID(),"Location Reader"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),user.getId(),
                OrganizationMembershipEntity.Role.OWNER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        return new Context(user.getId(),workspace.getId());
    }

    private String kato() {
        return jdbc.queryForObject("""
            SELECT code FROM ref_kato_territory
            WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
            """,String.class);
    }

    @Test void paginationAndFiltersUseTenantBoundary() {
        var a=setup();
        var b=setup();
        String kato=kato();
        var first=create.create(a.actor(),a.tenant(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Central Warehouse",kato,null);
        create.create(a.actor(),a.tenant(),OrganizationLocationEntity.Type.BRANCH,
                "Central Office",kato,null);
        create.create(a.actor(),a.tenant(),OrganizationLocationEntity.Type.WAREHOUSE,
                "East Warehouse",kato,null);
        create.create(b.actor(),b.tenant(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Other Tenant",kato,null);
        var all=query.search(a.actor(),a.tenant(),null,0,2);
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getContent()).hasSize(2);
        var second=query.search(a.actor(),a.tenant(),null,1,2);
        assertThat(second.getContent()).hasSize(1);
        var warehouses=query.search(a.actor(),a.tenant(),
                new LocationSearch(OrganizationLocationEntity.Type.WAREHOUSE,null,"Warehouse"),0,10);
        assertThat(warehouses.getContent()).hasSize(2);
        assertThat(warehouses.getContent()).allSatisfy(x->
                assertThat(x.type()).isEqualTo(OrganizationLocationEntity.Type.WAREHOUSE));
        assertThat(query.get(a.actor(),a.tenant(),first.getId()).name()).isEqualTo("Central Warehouse");
        assertThatThrownBy(()->query.get(b.actor(),b.tenant(),first.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->query.search(a.actor(),a.tenant(),null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void stockQueryIsTenantScopedAndSupportsLocationAndAvailableFilter() {
        var a=setup();
        var b=setup();
        String kato=kato();
        var locA=create.create(a.actor(),a.tenant(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Warehouse A",kato,null);
        var locB=create.create(b.actor(),b.tenant(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Warehouse B",kato,null);
        UUID itemA=UUID.randomUUID(),itemB=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,a.tenant(),itemA,"stock-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,b.tenant(),itemB,"stock-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?,10,3)
            """,a.tenant(),locA.getId(),itemA);
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?,5,5)
            """,b.tenant(),locB.getId(),itemB);
        var rows=stock.search(a.actor(),a.tenant(),
                new StockSearch(locA.getId(),null,true),0,10);
        assertThat(rows.getTotalElements()).isEqualTo(1);
        assertThat(rows.getContent().getFirst().available()).isEqualByComparingTo("7");
        assertThat(stock.search(b.actor(),b.tenant(),
                new StockSearch(locB.getId(),null,true),0,10).getTotalElements()).isZero();
        assertThatThrownBy(()->stock.search(a.actor(),b.tenant(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->stock.search(a.actor(),a.tenant(),null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
