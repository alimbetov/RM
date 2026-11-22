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
}
