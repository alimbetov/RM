package kz.rm.organization;

import kz.rm.iam.*;
import kz.rm.organization.dto.LocationSearch;
import kz.rm.inventory.AuthenticatedInventoryQueries;
import kz.rm.inventory.dto.StockSearch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.time.Instant;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class OrganizationLocationQueryQualificationTest {
    @Autowired OrganizationLocationService create;
    @Autowired OrganizationLocationQueryService query;
    @Autowired AuthenticatedOrganizationLocations secured;
    @Autowired PlatformTransactionManager transactions;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired DataSource dataSource;
    @Autowired AuthenticatedInventoryQueries stock;
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

    private VerifiedActor verified(UUID id) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                        .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("test-only-token");
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
        var foreign=create.create(b.actor(),b.tenant(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Central Warehouse",kato,null);
        var all=query.search(a.actor(),a.tenant(),null,0,2);
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getContent()).hasSize(2);
        var second=query.search(a.actor(),a.tenant(),null,1,2);
        assertThat(second.getContent()).hasSize(1);
        var warehouses=query.search(a.actor(),a.tenant(),
                new LocationSearch(OrganizationLocationEntity.Type.WAREHOUSE,null,"Warehouse"),0,10);
        assertThat(warehouses.getContent()).extracting(x->x.id())
                .doesNotContain(foreign.getId());
        assertThat(query.search(a.actor(),a.tenant(),
                new LocationSearch(OrganizationLocationEntity.Type.WAREHOUSE,null,"Central"),0,10)
                .getContent()).extracting(x->x.id()).contains(first.getId())
                .doesNotContain(foreign.getId());
        assertThat(query.search(b.actor(),b.tenant(),
                new LocationSearch(OrganizationLocationEntity.Type.WAREHOUSE,null,"Central"),0,10)
                .getContent()).extracting(x->x.id()).containsExactly(foreign.getId());
        assertThat(warehouses.getContent()).hasSize(2);
        assertThat(warehouses.getContent()).allSatisfy(x->
                assertThat(x.type()).isEqualTo(OrganizationLocationEntity.Type.WAREHOUSE));
        assertThat(query.get(a.actor(),a.tenant(),first.getId()).name()).isEqualTo("Central Warehouse");
        assertThatThrownBy(()->query.get(b.actor(),b.tenant(),first.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->query.search(a.actor(),a.tenant(),null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test void verifiedLocationFacadeAndTransactionRollback() {
        var a=setup();
        var b=setup();
        var actor=verified(a.actor());
        assertThatThrownBy(()->secured.create(null,a.tenant(),
                OrganizationLocationEntity.Type.WAREHOUSE,"Blocked",kato(),null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secured.search(null,a.tenant(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secured.create(actor,b.tenant(),
                OrganizationLocationEntity.Type.WAREHOUSE,"Wrong tenant",kato(),null))
                .isInstanceOf(SecurityException.class);
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(s->{
            secured.create(actor,a.tenant(),OrganizationLocationEntity.Type.WAREHOUSE,
                    "Uncommitted A",kato(),null);
            secured.create(actor,a.tenant(),OrganizationLocationEntity.Type.BRANCH,
                    "Uncommitted B",kato(),null);
            throw new IllegalStateException("synthetic organization rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(secured.search(actor,a.tenant(),null,0,10).getTotalElements()).isZero();
        var created=secured.create(actor,a.tenant(),OrganizationLocationEntity.Type.WAREHOUSE,
                "Committed",kato(),null);
        assertThat(secured.get(actor,a.tenant(),created.id()).name()).isEqualTo("Committed");
        assertThat(secured.search(actor,a.tenant(),null,0,10).getTotalElements())
                .isEqualTo(1);
        assertThatThrownBy(()->secured.get(verified(b.actor()),b.tenant(),created.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test void postgresLocationCreationFencesConcurrentPermissionRevocation() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var t=setup();
        var role=roles.createRole(verified(t.actor()),t.tenant(),
                "ORG_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Organization location manager");
        roles.grant(verified(t.actor()),t.tenant(),role.id(),DomainPermission.ORGANIZATION_LOCATION_WRITE);
        roles.grant(verified(t.actor()),t.tenant(),role.id(),DomainPermission.ORGANIZATION_LOCATION_READ);

        UUID orgId=jdbc.queryForObject(
                "SELECT organization_id FROM public.iam_workspace WHERE id=?",
                UUID.class,t.tenant());
        var staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "org-staff-"+UUID.randomUUID(),"Location staff"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                orgId,staff.getId(),OrganizationMembershipEntity.Role.MEMBER));
        roles.assign(verified(t.actor()),t.tenant(),staff.getId(),role.id());
        roles.enable(verified(t.actor()),t.tenant());
        var admitted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->tx.execute(s->{
                secured.create(verified(staff.getId()),t.tenant(),
                        OrganizationLocationEntity.Type.WAREHOUSE,
                        "Admitted before revocation",kato(),null);
                admitted.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("Location write timeout");
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return true;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{
                roles.revoke(verified(t.actor()),t.tenant(),role.id(),
                        DomainPermission.ORGANIZATION_LOCATION_WRITE);
                return true;
            });
            try {
                assertThatThrownBy(()->revoke.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(writer.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(secured.search(verified(staff.getId()),t.tenant(),null,0,10)
                .getTotalElements()).isEqualTo(1);
        assertThatThrownBy(()->secured.create(verified(staff.getId()),t.tenant(),
                OrganizationLocationEntity.Type.WAREHOUSE,"After revocation",kato(),null))
                .isInstanceOf(SecurityException.class);
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
        var rows=stock.search(verified(a.actor()),a.tenant(),
                new StockSearch(locA.getId(),null,true),0,10);
        assertThat(rows.getTotalElements()).isEqualTo(1);
        assertThat(rows.getContent().getFirst().available()).isEqualByComparingTo("7");
        assertThat(stock.search(verified(b.actor()),b.tenant(),
                new StockSearch(locB.getId(),null,true),0,10).getTotalElements()).isZero();
        assertThatThrownBy(()->stock.search(verified(a.actor()),b.tenant(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->stock.search(verified(a.actor()),a.tenant(),null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void tcrud03SameGlobalMemberDifferentLocationGrantsAndForeignIdDenied() {
        var a=setup(); var b=setup(); String region=kato();
        var aOne=secured.create(verified(a.actor()),a.tenant(),
                OrganizationLocationEntity.Type.WAREHOUSE,"Repeated Warehouse",region,null);
        var aTwo=secured.create(verified(a.actor()),a.tenant(),
                OrganizationLocationEntity.Type.WAREHOUSE,"Repeated Warehouse",region,null);
        var bOne=secured.create(verified(b.actor()),b.tenant(),
                OrganizationLocationEntity.Type.WAREHOUSE,"Repeated Warehouse",region,null);
        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "tcrud03-org-"+UUID.randomUUID(),"Shared member")).getId();
        UUID orgA=jdbc.queryForObject("SELECT organization_id FROM iam_workspace WHERE id=?",
                UUID.class,a.tenant());
        UUID orgB=jdbc.queryForObject("SELECT organization_id FROM iam_workspace WHERE id=?",
                UUID.class,b.tenant());
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgA,
                staff,OrganizationMembershipEntity.Role.MEMBER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgB,
                staff,OrganizationMembershipEntity.Role.MEMBER));
        var ar=roles.createRole(verified(a.actor()),a.tenant(),
                "ORG_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),"A reader writer");
        roles.grant(verified(a.actor()),a.tenant(),ar.id(),DomainPermission.ORGANIZATION_LOCATION_READ);
        roles.grant(verified(a.actor()),a.tenant(),ar.id(),DomainPermission.ORGANIZATION_LOCATION_WRITE);
        roles.assign(verified(a.actor()),a.tenant(),staff,ar.id());
        roles.enable(verified(a.actor()),a.tenant());
        var br=roles.createRole(verified(b.actor()),b.tenant(),
                "ORG_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),"B reader");
        roles.grant(verified(b.actor()),b.tenant(),br.id(),DomainPermission.ORGANIZATION_LOCATION_READ);
        roles.assign(verified(b.actor()),b.tenant(),staff,br.id());
        roles.enable(verified(b.actor()),b.tenant());
        var actor=verified(staff);
        var filter=new LocationSearch(OrganizationLocationEntity.Type.WAREHOUSE,null,"Repeated");
        assertThat(secured.search(actor,a.tenant(),filter,0,1).getTotalElements()).isEqualTo(2);
        assertThat(secured.search(actor,a.tenant(),filter,1,1).getContent())
                .extracting(x->x.id()).containsAnyOf(aOne.id(),aTwo.id())
                .doesNotContain(bOne.id());
        assertThat(secured.search(actor,b.tenant(),filter,0,1).getTotalElements()).isEqualTo(1);
        assertThat(secured.search(actor,b.tenant(),filter,0,1).getContent())
                .extracting(x->x.id()).containsExactly(bOne.id());
        assertThatThrownBy(()->secured.get(actor,a.tenant(),bOne.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secured.create(actor,b.tenant(),
                OrganizationLocationEntity.Type.WAREHOUSE,"Unauthorized",region,null))
                .isInstanceOf(SecurityException.class);
        assertThat(secured.create(actor,a.tenant(),
                OrganizationLocationEntity.Type.BRANCH,"Authorized",region,null).id()).isNotNull();
        assertThat(secured.search(actor,b.tenant(),null,0,10).getTotalElements()).isEqualTo(1);
    }

}
