package kz.rm.inventory;

import kz.rm.iam.*;
import kz.rm.storefront.PublishedOfferQrService;
import kz.rm.storefront.PublishedOfferCardQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** QR-02B: exact-KATO read-only PUBLIC discovery from live INVENTORY balances.
 * No mocked aggregation and no multiwarehouse stock promise. */
@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class RegionalPublicAvailabilityQualificationTest {
    @Autowired RegionalPublicAvailabilityPort regional;
    @Autowired PublishedOfferCardQuery cards;
    @Autowired PublishedOfferQrService qr;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(UUID tenant,UUID item,UUID offer,String publicRef,
                           VerifiedActor staff) {}

    private VerifiedActor actor(UUID id) {
        String sub=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,id);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
                .header("alg","RS256").subject(sub).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-region");
    }
    private List<String> katoCodes() {
        return jdbc.query("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3
                ORDER BY code LIMIT 2
                """,(rs,n)->rs.getString(1));
    }
    private Fixture setup(String sku) {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Region Seller","Regional Offer","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"rg-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID staffId=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"rg-seller-"+UUID.randomUUID(),"Seller")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),staffId,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        var admin=actor(owner);
        var role=roles.createRole(admin,tenant,
                "REG_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "Region seller");
        roles.grant(admin,tenant,role.id(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.assign(admin,tenant,staffId,role.id());
        roles.enable(admin,tenant);
        UUID store=UUID.randomUUID(),item=UUID.randomUUID(),offer=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item
                 (tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,item,sku);
        jdbc.update("""
                INSERT INTO crm.cat_item_i18n(tenant_id,item_id,locale,name,description)
                VALUES (?,?,'ru','Деталь','Описанный товар')
                """,tenant,item);
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
                VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,store,"region-"+UUID.randomUUID());
        jdbc.update("""
                INSERT INTO crm.store_offer
                 (tenant_id,id,title,unit_price,currency,state,catalog_item_id,row_version)
                VALUES (?,?,'Region Offer',2000,'KZT','PUBLISHED',?,0)
                """,tenant,offer,item);
        var staff=actor(staffId);
        String ref=qr.issueOrGet(staff,tenant,offer,0,
                "REG_"+UUID.randomUUID().toString().replace("-","")).publicRef();
        return new Fixture(tenant,item,offer,ref,staff);
    }
    private UUID warehouse(Fixture f,String kato,int onHand,int reserved) {
        UUID loc=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.org_location
                  (id,workspace_id,location_type,name,kato_code,status,
                   created_at,updated_at,row_version)
                VALUES (?,?,'WAREHOUSE','Region WH',?,'ACTIVE',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """,loc,f.tenant(),kato);
        jdbc.update("""
                INSERT INTO crm.inv_stock_balance
                 (tenant_id,location_id,item_id,on_hand,reserved,row_version)
                VALUES (?,?,?,?,?,0)
                """,f.tenant(),loc,f.item(),
                BigDecimal.valueOf(onHand),BigDecimal.valueOf(reserved));
        return loc;
    }

    @Test void qr02bQuantityMustFitOneWarehouseNotSumAcrossTwo() {
        String kato=katoCodes().getFirst();
        var f=setup("R-SENSOR-"+UUID.randomUUID());
        UUID wh1=warehouse(f,kato,3,2);
        warehouse(f,kato,3,2); // 1 available each; 2 total != 2 at ONE warehouse
        assertThat(regional.summary(f.publicRef(),kato,2).band())
                .isEqualTo(RegionalPublicAvailabilityPort.Band.INSUFFICIENT_STOCK);
        assertThat(regional.search(kato,"ru",2,null,50))
                .extracting(RegionalPublicAvailabilityPort.RegionalOffer::publicOfferRef)
                .doesNotContain(f.publicRef());
        jdbc.update("""
                UPDATE crm.inv_stock_balance SET on_hand=10,row_version=row_version+1
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,f.tenant(),wh1,f.item());
        assertThat(regional.summary(f.publicRef(),kato,8).band())
                .isEqualTo(RegionalPublicAvailabilityPort.Band.INDICATIVE_AVAILABLE);
        assertThat(cards.findPublished(f.publicRef(),"ru",kato,8).availabilityBand())
                .isEqualTo("INDICATIVE_AVAILABLE");
        assertThat(regional.summary(f.publicRef(),kato,9).band())
                .isEqualTo(RegionalPublicAvailabilityPort.Band.INSUFFICIENT_STOCK);
        jdbc.update("""
                UPDATE crm.inv_stock_balance SET reserved=4,row_version=row_version+1
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,f.tenant(),wh1,f.item());
        assertThat(regional.summary(f.publicRef(),kato,8).band())
                .isEqualTo(RegionalPublicAvailabilityPort.Band.INSUFFICIENT_STOCK);
    }

    @Test void qr02bExactKatoIgnoresUnlistedAndInactiveWarehouses() {
        List<String> codes=katoCodes();
        assertThat(codes).hasSizeGreaterThan(1);
        String first=codes.get(0),second=codes.get(1);
        var f=setup("R-REGION-"+UUID.randomUUID());
        UUID warehouse=warehouse(f,second,100,0);
        assertThat(regional.summary(f.publicRef(),first,1).band())
                .isEqualTo(RegionalPublicAvailabilityPort.Band.NOT_LISTED_IN_REGION);
        assertThat(regional.summary(f.publicRef(),second,1).band())
                .isEqualTo(RegionalPublicAvailabilityPort.Band.INDICATIVE_AVAILABLE);
        jdbc.update("""
                UPDATE public.org_location SET status='INACTIVE',row_version=row_version+1
                WHERE workspace_id=? AND id=?
                """,f.tenant(),warehouse);
        assertThat(regional.summary(f.publicRef(),second,1).band())
                .isEqualTo(RegionalPublicAvailabilityPort.Band.NOT_LISTED_IN_REGION);
        jdbc.update("""
                UPDATE crm.store_offer SET state='HIDDEN',row_version=row_version+1
                WHERE tenant_id=? AND id=?
                """,f.tenant(),f.offer());
        assertThatThrownBy(()->regional.summary(f.publicRef(),second,1))
                .hasMessage("QR_UNAVAILABLE");
    }

    private List<RegionalPublicAvailabilityPort.RegionalOffer> allRegionalOffers(String kato,int quantity){
        var result=new java.util.ArrayList<RegionalPublicAvailabilityPort.RegionalOffer>();
        var seen=new java.util.HashSet<String>();
        String cursor=null;
        // The public search is global. Walk the actual SQL sort order rather
        // than Java String order (which differs under PostgreSQL collation).
        // Bound traversal and reject any duplicate or non-advancing cursor.
        for(int page=0;page<2000;page++){
            var batch=regional.search(kato,"ru",quantity,cursor,50);
            for(var offer:batch){
                assertThat(seen.add(offer.publicOfferRef())).as("unique offer across pages").isTrue();
                if(cursor!=null)
                    assertThat(jdbc.queryForObject("SELECT CASE WHEN CAST(? AS varchar)>CAST(? AS varchar) THEN 1 ELSE 0 END",
                            Integer.class,offer.publicOfferRef(),cursor)).as("database keyset order").isEqualTo(1);
            }
            result.addAll(batch);
            if(batch.size()<50)return result;
            cursor=batch.getLast().publicOfferRef();
        }
        throw new AssertionError("Regional keyset traversal exceeded fixture safety bound");
    }

    @Test void qr02bRegionSearchHasKeysetPaginationAndNoCrossSellerSkuMerge() {
        String kato=katoCodes().getFirst();
        String sameSku="REGION-SKU";
        var a=setup(sameSku);
        var b=setup(sameSku);
        warehouse(a,kato,20,1);
        warehouse(b,kato,40,30);
        List<RegionalPublicAvailabilityPort.RegionalOffer> found=allRegionalOffers(kato,5);
        assertThat(found).extracting(
                RegionalPublicAvailabilityPort.RegionalOffer::publicOfferRef)
                .contains(a.publicRef(),b.publicRef());
        assertThat(found).extracting(
                RegionalPublicAvailabilityPort.RegionalOffer::currency)
                .containsOnly("KZT");
        // Regional discovery is intentionally global: other published sellers
        // may be present from independent service-journey fixtures. Verify
        // exactly the two selected seller refs, never assert global isolation.
        var selected=found.stream().filter(x->
                x.publicOfferRef().equals(a.publicRef())
                || x.publicOfferRef().equals(b.publicRef())).toList();
        assertThat(selected).hasSize(2);
        assertThat(selected).extracting(
                RegionalPublicAvailabilityPort.RegionalOffer::unitPrice)
                .containsOnly(new BigDecimal("2000.0000"));
        String first=found.getFirst().publicOfferRef();
        var next=regional.search(kato,"ru",5,first,50);
        assertThat(next).containsExactlyElementsOf(found.subList(1,Math.min(51,found.size())));
        // Tenant A goes out of stock; tenant B's published offer remains.
        jdbc.update("""
                UPDATE crm.inv_stock_balance SET reserved=on_hand,row_version=row_version+1
                WHERE tenant_id=? AND item_id=?
                """,a.tenant(),a.item());
        var now=allRegionalOffers(kato,5);
        assertThat(now).extracting(
                RegionalPublicAvailabilityPort.RegionalOffer::publicOfferRef)
                .contains(b.publicRef()).doesNotContain(a.publicRef());
    }

    @Test void qr02bUnknownKatoQtyAndRetiredQrCannotLeakInventory() {
        String kato=katoCodes().getFirst();
        var f=setup("REGION-ERR-"+UUID.randomUUID());
        warehouse(f,kato,10,0);
        assertThatThrownBy(()->regional.summary(f.publicRef(),"NOT_A_KATO",1))
                .hasMessage("KATO_UNVERIFIED");
        assertThatThrownBy(()->regional.summary(f.publicRef(),kato,0))
                .hasMessage("QTY_INVALID");
        assertThatThrownBy(()->regional.search(kato,"ru",1001,null,50))
                .hasMessage("QTY_INVALID");
        assertThatThrownBy(()->regional.search(kato,"ru",1,"bad",50))
                .hasMessage("QR_UNAVAILABLE");
        qr.revoke(f.staff(),f.tenant(),f.offer(),0L,
                "REG_"+UUID.randomUUID().toString().replace("-",""));
        assertThatThrownBy(()->regional.summary(f.publicRef(),kato,1))
                .hasMessage("QR_UNAVAILABLE");
        assertThat(regional.search(kato,"ru",1,null,50))
                .extracting(RegionalPublicAvailabilityPort.RegionalOffer::publicOfferRef)
                .doesNotContain(f.publicRef());
    }
}
