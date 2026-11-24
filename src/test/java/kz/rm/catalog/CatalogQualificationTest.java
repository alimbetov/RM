package kz.rm.catalog;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class CatalogQualificationTest {
    @Autowired CatalogItemService commands;
    @Autowired CatalogQueryService queries;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;

    private record Tenant(UUID tenant,UUID owner,UUID organization) {}
    private Tenant tenant(){
        var owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "cat-owner-"+UUID.randomUUID(),"Catalog owner"));
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Catalog LLP","Catalog","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),
                owner.getId(),OrganizationMembershipEntity.Role.OWNER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        return new Tenant(workspace.getId(),owner.getId(),org.getId());
    }
    private UUID user(){
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "cat-user-"+UUID.randomUUID(),"Catalog worker")).getId();
    }
    private void member(Tenant tenant,UUID staff){
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                tenant.organization(),staff,OrganizationMembershipEntity.Role.MEMBER));
    }
    private VerifiedActor verified(UUID id){
        String subject=jdbc.queryForObject("SELECT external_subject FROM iam_user_account WHERE id=?",
                String.class,id);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }
    private void grant(Tenant tenant,UUID staff,DomainPermission... permissions){
        var actor=verified(tenant.owner());
        var role=roles.createRole(actor,tenant.tenant(),
                "CAT_"+UUID.randomUUID().toString().replace("-","").substring(0,14).toUpperCase(),
                "Catalog access");
        for(var perm:permissions)roles.grant(actor,tenant.tenant(),role.id(),perm);
        roles.assign(actor,tenant.tenant(),staff,role.id());
        roles.enable(actor,tenant.tenant());
    }

    @Test void twoTenantCatalogSkuPricePublicationAndReadOnlyBoundary() {
        var a=tenant();var b=tenant();
        UUID shared=user(),staffB=user();
        member(a,shared);member(b,shared);member(b,staffB);
        grant(a,shared,DomainPermission.CATALOG_READ,DomainPermission.CATALOG_WRITE,
                DomainPermission.CATALOG_PRICE_MANAGE,DomainPermission.CATALOG_PUBLISH);
        grant(b,shared,DomainPermission.CATALOG_READ);
        grant(b,staffB,DomainPermission.CATALOG_READ,DomainPermission.CATALOG_WRITE,
                DomainPermission.CATALOG_PRICE_MANAGE,DomainPermission.CATALOG_PUBLISH);
        var actor=verified(shared);
        var itemA=commands.createDraft(actor,a.tenant()," sku-001 ",CatalogItem.Kind.PRODUCT,
                "ru","Промышленный датчик","MOD-1");
        var itemB=commands.createDraft(verified(staffB),b.tenant(),"SKU-001",
                CatalogItem.Kind.PRODUCT,"ru","Other tenant product","MOD-1");
        assertThat(itemA.sku()).isEqualTo("SKU-001");
        assertThat(itemA.modelCode()).isEqualTo("MOD-1");
        assertThat(itemA.rowVersion()).isZero();
        assertThatThrownBy(()->commands.createDraft(actor,b.tenant(),"OTHER",
                CatalogItem.Kind.PRODUCT,"ru","Denied",null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->commands.publish(actor,a.tenant(),itemA.id(),0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->commands.addPriceRevision(actor,b.tenant(),itemB.id(),
                BigDecimal.TEN,new String("KZT"),Instant.parse("2026-11-01T00:00:00Z"),0))
                .isInstanceOf(SecurityException.class);
        Instant effective=Instant.parse("2026-11-01T00:00:00Z");
        var priced=commands.addPriceRevision(actor,a.tenant(),itemA.id(),
                new BigDecimal("1500.0000"),"KZT",effective,0);
        assertThat(priced.rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->commands.addPriceRevision(actor,a.tenant(),itemA.id(),
                BigDecimal.ONE,"KZT",effective,1))
                .isInstanceOf(IllegalArgumentException.class);
        var published=commands.publish(actor,a.tenant(),itemA.id(),1);
        assertThat(published.state()).isEqualTo(CatalogItem.State.PUBLISHED);
        assertThat(queries.get(actor,a.tenant(),itemA.id(),effective.minusSeconds(1))
                .effectivePrice()).isNull();
        assertThat(queries.get(actor,a.tenant(),itemA.id(),effective.plusSeconds(1))
                .effectivePrice().amount()).isEqualByComparingTo("1500.0000");
        assertThat(queries.search(actor,a.tenant(),CatalogItem.Kind.PRODUCT,
                CatalogItem.State.PUBLISHED,null,"MOD-1",0,1).getTotalElements())
                .isEqualTo(1);
        assertThat(queries.search(actor,b.tenant(),CatalogItem.Kind.PRODUCT,
                null,"SKU-001",null,0,10).getContent())
                .extracting(CatalogItemService.Item::id).containsExactly(itemB.id());
        assertThatThrownBy(()->queries.get(actor,a.tenant(),itemB.id(),Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->commands.archive(actor,a.tenant(),itemB.id(),0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->commands.upsertTranslation(actor,a.tenant(),itemA.id(),
                "kk","Датчик",2)).isInstanceOf(IllegalStateException.class);
        var archived=commands.archive(actor,a.tenant(),itemA.id(),2);
        assertThat(archived.state()).isEqualTo(CatalogItem.State.ARCHIVED);
        assertThat(queries.history(actor,a.tenant(),itemA.id(),10))
                .extracting(CatalogQueryService.Event::action)
                .containsExactlyInAnyOrder("CREATED","PRICE_REVISED","PUBLISHED","ARCHIVED");
        assertThat(queries.priceHistory(actor,a.tenant(),itemA.id(),0,10).getTotalElements())
                .isEqualTo(1);
    }

    @Test void draftTranslationsAndVersionCasCannotLeakOtherTenant() {
        var a=tenant();UUID staff=user();member(a,staff);
        grant(a,staff,DomainPermission.CATALOG_READ,DomainPermission.CATALOG_WRITE);
        var actor=verified(staff);
        var item=commands.createDraft(actor,a.tenant(),"SERVICE-01",CatalogItem.Kind.SERVICE,
                "en","Consultation",null);
        var updated=commands.upsertTranslation(actor,a.tenant(),item.id(),"kk","Кеңес",0);
        assertThat(updated.rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->commands.updateModelCode(actor,a.tenant(),item.id(),"MODEL-1",0))
                .isInstanceOf(IllegalStateException.class);
        var newModel=commands.updateModelCode(actor,a.tenant(),item.id(),"model_1",1);
        assertThat(newModel.modelCode()).isEqualTo("MODEL_1");
        assertThatThrownBy(()->commands.addPriceRevision(actor,a.tenant(),item.id(),
                BigDecimal.ONE,"KZT",Instant.now(),2)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->commands.publish(actor,a.tenant(),item.id(),2))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->queries.search(actor,a.tenant(),null,null,null,null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test void sellerEditR0SameSkuAcrossTenantsUsesEmployeeSpecificGrants() {
        var a=tenant();
        var b=tenant();
        UUID dual=user();
        member(a,dual);
        member(b,dual);
        grant(a,dual,DomainPermission.CATALOG_READ,DomainPermission.CATALOG_WRITE,
                DomainPermission.CATALOG_PRICE_MANAGE);
        grant(b,dual,DomainPermission.CATALOG_READ,DomainPermission.CATALOG_WRITE);
        VerifiedActor editor=verified(dual);
        String sku="DUAL-"+UUID.randomUUID();
        var productA=commands.createDraft(editor,a.tenant(),sku,
                CatalogItem.Kind.PRODUCT,"ru","Tenant A item",null);
        var productB=commands.createDraft(editor,b.tenant(),sku,
                CatalogItem.Kind.PRODUCT,"ru","Tenant B item",null);
        Instant date=Instant.parse("2026-11-01T00:00:00Z");
        assertThat(commands.addPriceRevision(editor,a.tenant(),productA.id(),
                new BigDecimal("1400.0000"),"KZT",date,0).rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->commands.addPriceRevision(editor,b.tenant(),productB.id(),
                new BigDecimal("999.0000"),"KZT",date,0))
                .isInstanceOf(SecurityException.class);

        // Even a user present in both organizations cannot write a foreign
        // item through the other tenant context with a shared SKU.
        assertThatThrownBy(()->commands.updateModelCode(editor,a.tenant(),
                productB.id(),"FOREIGN",0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_price WHERE tenant_id=? AND item_id=?
                """,Integer.class,a.tenant(),productA.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_price WHERE tenant_id=? AND item_id=?
                """,Integer.class,b.tenant(),productB.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT row_version FROM crm.cat_item WHERE tenant_id=? AND id=?
                """,Long.class,b.tenant(),productB.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item_event
                WHERE tenant_id=? AND item_id=? AND action_code='PRICE_REVISED'
                """,Integer.class,b.tenant(),productB.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item WHERE sku=? AND tenant_id IN (?,?)
                """,Integer.class,sku,a.tenant(),b.tenant())).isEqualTo(2);
    }

    @Test void sellerEditR0RevokedPriceGrantCannotAppendAnotherRevision() {
        var t=tenant();
        UUID editorId=user();
        member(t,editorId);
        VerifiedActor owner=verified(t.owner());
        var role=roles.createRole(owner,t.tenant(),
                "EDR0_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Catalog edit R0 role");
        roles.grant(owner,t.tenant(),role.id(),DomainPermission.CATALOG_WRITE);
        roles.grant(owner,t.tenant(),role.id(),DomainPermission.CATALOG_PRICE_MANAGE);
        roles.assign(owner,t.tenant(),editorId,role.id());
        roles.enable(owner,t.tenant());
        VerifiedActor editor=verified(editorId);

        var product=commands.createDraft(editor,t.tenant(),
                "REVOKE-"+UUID.randomUUID(),CatalogItem.Kind.PRODUCT,
                "ru","Scoped price",null);
        var first=commands.addPriceRevision(editor,t.tenant(),product.id(),
                new BigDecimal("450.0000"),"KZT",
                Instant.parse("2026-11-01T00:00:00Z"),0);
        assertThat(first.rowVersion()).isEqualTo(1);

        roles.revoke(owner,t.tenant(),role.id(),DomainPermission.CATALOG_PRICE_MANAGE);
        assertThatThrownBy(()->commands.addPriceRevision(editor,t.tenant(),product.id(),
                new BigDecimal("480.0000"),"KZT",
                Instant.parse("2026-11-02T00:00:00Z"),first.rowVersion()))
                .isInstanceOf(SecurityException.class);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_price WHERE tenant_id=? AND item_id=?
                """,Integer.class,t.tenant(),product.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT row_version FROM crm.cat_item WHERE tenant_id=? AND id=?
                """,Long.class,t.tenant(),product.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item_event
                WHERE tenant_id=? AND item_id=? AND action_code='PRICE_REVISED'
                """,Integer.class,t.tenant(),product.id())).isEqualTo(1);
        // A stale actor is not a stored capability: ordinary catalog edits
        // remain allowed only because this employee retains CATALOG_WRITE.
        assertThat(commands.updateModelCode(editor,t.tenant(),product.id(),
                "MODEL-A",1).rowVersion()).isEqualTo(2);
    }

    @Test void sellerEditR0PostgresTwoEditorsOneCatalogPriceRevisionWins() throws Exception {
        try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
        var t=tenant();
        UUID first=user(),second=user();
        member(t,first);
        member(t,second);
        grant(t,first,DomainPermission.CATALOG_WRITE,DomainPermission.CATALOG_PRICE_MANAGE);
        grant(t,second,DomainPermission.CATALOG_PRICE_MANAGE);
        var editorA=verified(first);
        var editorB=verified(second);
        var product=commands.createDraft(editorA,t.tenant(),
                "CAS-"+UUID.randomUUID(),CatalogItem.Kind.PRODUCT,
                "ru","Price CAS race",null);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> a=()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Price race start timed out");
                try {
                    commands.addPriceRevision(editorA,t.tenant(),product.id(),
                            new BigDecimal("1100.0000"),"KZT",
                            Instant.parse("2026-11-01T00:00:00Z"),0);
                    return true;
                } catch (IllegalStateException conflict) { return false; }
            };
            java.util.concurrent.Callable<Boolean> b=()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Price race start timed out");
                try {
                    commands.addPriceRevision(editorB,t.tenant(),product.id(),
                            new BigDecimal("1200.0000"),"KZT",
                            Instant.parse("2026-11-02T00:00:00Z"),0);
                    return true;
                } catch (IllegalStateException conflict) { return false; }
            };
            var fa=pool.submit(a);
            var fb=pool.submit(b);
            start.countDown();
            boolean winnerA=fa.get(20,java.util.concurrent.TimeUnit.SECONDS);
            boolean winnerB=fb.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat((winnerA?1:0)+(winnerB?1:0)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_price WHERE tenant_id=? AND item_id=?
                """,Integer.class,t.tenant(),product.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT row_version FROM crm.cat_item WHERE tenant_id=? AND id=?
                """,Long.class,t.tenant(),product.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item_event
                WHERE tenant_id=? AND item_id=? AND action_code='PRICE_REVISED'
                """,Integer.class,t.tenant(),product.id())).isEqualTo(1);
    }

    @Test void postgresCatalogPriceAndAuditAreAppendOnly() throws Exception {
        try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
        var t=tenant();var staff=user();member(t,staff);
        grant(t,staff,DomainPermission.CATALOG_WRITE,DomainPermission.CATALOG_PRICE_MANAGE,
                DomainPermission.CATALOG_READ);
        var actor=verified(staff);
        var item=commands.createDraft(actor,t.tenant(),"EVENT-SKU",CatalogItem.Kind.PRODUCT,
                "ru","Catalog evidence",null);
        commands.addPriceRevision(actor,t.tenant(),item.id(),BigDecimal.ONE,"KZT",
                Instant.parse("2026-11-01T00:00:00Z"),0);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.cat_price SET unit_price=999
                WHERE tenant_id=? AND item_id=?
                """,t.tenant(),item.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.cat_item_event WHERE tenant_id=? AND item_id=?
                """,t.tenant(),item.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(queries.priceHistory(actor,t.tenant(),item.id(),0,10)
                .getContent().getFirst().amount()).isEqualByComparingTo("1");
    }

}
