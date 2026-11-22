package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * QR-05/06 real H2/PostgreSQL owner tests. Synthetic actors/owned mailbox are
 * deliberate service fixtures, NOT external OIDC, cameras or infrastructure.
 */
@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class QrWarehouseHandoverQualificationTest {
    @Autowired SalesOrderPreparationService prep;
    @Autowired SalesPickupHandoverService pickup;
    @Autowired SalesInventoryCommandService sales;
    @Autowired SalesOrderQrReferenceService orderRefs;
    @Autowired WarehouseStaffScopeService scopes;
    @Autowired AutoOrderService checkout;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired UserAccountRepository users;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    private record Context(UUID tenant,UUID warehouse,UUID otherWarehouse,
                           UUID itemOne,UUID itemTwo,UUID offerOne,UUID offerTwo,
                           String storeSlug,VerifiedActor owner,
                           VerifiedActor staff,VerifiedActor buyer,String buyerEmail) {}
    private record Order(UUID id,List<UUID> lines,String publicRef) {}

    private VerifiedActor verified(UUID id){
        String subject=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,id);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-warehouse");
    }
    private static String key(){return "PICK_"+UUID.randomUUID().toString().replace("-","");}
    private Context context(){
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "QR Warehouse Company","Warehouse","KZ"));
        UUID tenant=workspaces.saveAndFlush(WorkspaceEntity.organization(
                UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "warehouse-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "warehouse-staff-"+UUID.randomUUID(),"Staff")).getId();
        UUID buyer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "warehouse-buyer-"+UUID.randomUUID(),"Buyer")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        String buyerEmail="qr-pick-"+UUID.randomUUID()+"@example.kz";
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO public.iam_email_identity
                 (id,user_id,lookup_hmac,email_envelope,verified_at,created_at,
                  lifecycle_state,active_lookup_hmac,current_owner_id)
                VALUES (?,?,?,?,?,?,'CURRENT',?,?)
                """,UUID.randomUUID(),buyer,crypto.lookup(buyerEmail),
                crypto.encrypt(buyerEmail),now,now,crypto.lookup(buyerEmail),buyer);
        VerifiedActor ownerActor=verified(owner),staffActor=verified(staff);
        var role=roles.createRole(ownerActor,tenant,
                "PICK_"+UUID.randomUUID().toString().replace("-","")
                        .substring(0,12).toUpperCase(),"Stock pickup staff");
        for(var perm:new DomainPermission[]{
                DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_READ,DomainPermission.INVENTORY_STOCK_READ,
                DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL})
            roles.grant(ownerActor,tenant,role.id(),perm);
        roles.assign(ownerActor,tenant,staff,role.id());
        roles.enable(ownerActor,tenant);
        String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
        UUID warehouse=UUID.randomUUID(),other=UUID.randomUUID();
        for(UUID w:List.of(warehouse,other)){
            jdbc.update("""
                    INSERT INTO public.org_location
                     (id,workspace_id,location_type,name,kato_code,status,
                      created_at,updated_at,row_version)
                    VALUES (?,?,'WAREHOUSE','Warehouse',?,'ACTIVE',?,?,0)
                    """,w,tenant,kato,now,now);
        }
        scopes.assign(ownerActor,tenant,warehouse,staff);
        UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        UUID offerOne=UUID.randomUUID(),offerTwo=UUID.randomUUID();
        String slug="qr-pick-"+UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.store_front
                 (tenant_id,id,slug,state,created_at)
                VALUES (?,?,?,'PUBLISHED',?)
                """,tenant,UUID.randomUUID(),slug,now);
        for(UUID item:List.of(one,two)){
            jdbc.update("""
                    INSERT INTO crm.cat_item
                     (tenant_id,id,sku,kind,source_locale,state,created_at)
                    VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',?)
                    """,tenant,item,"SKU-"+UUID.randomUUID(),now);
            jdbc.update("""
                    INSERT INTO crm.inv_stock_balance
                     (tenant_id,location_id,item_id,on_hand,reserved,row_version)
                    VALUES (?,?,?,20,0,0)
                    """,tenant,warehouse,item);
        }
        for(int i=0;i<2;i++){
            jdbc.update("""
                    INSERT INTO crm.store_offer
                     (tenant_id,id,title,unit_price,currency,state,catalog_item_id)
                    VALUES (?,?,'Item',100,'KZT','PUBLISHED',?)
                    """,tenant,i==0?offerOne:offerTwo,i==0?one:two);
        }
        return new Context(tenant,warehouse,other,one,two,offerOne,offerTwo,
                slug,ownerActor,staffActor,verified(buyer),buyerEmail);
    }
    private Order order(Context c,boolean twoLines){
        var basket=twoLines?List.of(new AutoOrderService.Line(c.offerOne(),5),
                new AutoOrderService.Line(c.offerTwo(),3))
                :List.of(new AutoOrderService.Line(c.offerOne(),5));
        UUID id=checkout.checkout(c.storeSlug(),c.buyerEmail(),key(),basket)
                .order().orderId();
        UUID challenge=jdbc.queryForObject("""
                SELECT id FROM public.iam_email_verification_challenge
                WHERE email_identity_id=(
                    SELECT id FROM public.iam_email_identity
                    WHERE user_id=? AND lifecycle_state='CURRENT')
                ORDER BY created_at DESC,id DESC LIMIT 1
                """,UUID.class,c.buyer().userId());
        byte[] envelope=jdbc.queryForObject("""
                SELECT token_envelope FROM public.iam_email_verification_challenge
                WHERE id=?
                """,byte[].class,challenge);
        UUID verifiedBuyer=checkout.verifyEmail(challenge,crypto.decrypt(envelope));
        assertThat(verifiedBuyer).isEqualTo(c.buyer().userId());
        List<UUID> lines=jdbc.query("""
                SELECT id FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                ORDER BY catalog_item_id_snapshot,id
                """,(rs,n)->rs.getObject(1,UUID.class),c.tenant(),id);
        String orderRef=jdbc.queryForObject("""
                SELECT public_ref FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,String.class,c.tenant(),id);
        return new Order(id,lines,orderRef);
    }
    private long version(Context c,Order o){
        return jdbc.queryForObject("""
                SELECT row_version FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,Long.class,c.tenant(),o.id());
    }
    private BigDecimal stock(Context c,UUID item,String field){
        if(!List.of("on_hand","reserved").contains(field))
            throw new IllegalArgumentException("Bad stock field");
        return jdbc.queryForObject("SELECT "+field+" "+"""
                FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,c.tenant(),c.warehouse(),item);
    }
    private String status(Context c,Order o){
        return jdbc.queryForObject("""
                SELECT status FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),o.id());
    }
    private Order ready(Context c,boolean two){
        Order o=order(c,two);
        sales.confirm(c.staff().userId(),c.tenant(),o.id(),c.warehouse());
        var start=prep.startPreparing(c.staff(),c.tenant(),o.id(),c.warehouse(),
                version(c,o),key());
        assertThat(start.status()).isEqualTo("PREPARING");
        for(UUID line:o.lines()){
            int quantity=jdbc.queryForObject("""
                    SELECT quantity FROM crm.sales_order_line
                    WHERE tenant_id=? AND id=?
                    """,Integer.class,c.tenant(),line);
            prep.confirmPicked(c.staff(),c.tenant(),o.id(),c.warehouse(),line,
                    quantity,version(c,o),key());
        }
        var mark=prep.markReadyForPickup(c.staff(),c.tenant(),o.id(),
                c.warehouse(),version(c,o),key());
        assertThat(mark.status()).isEqualTo("READY_FOR_PICKUP");
        return o;
    }
    private void requirePostgres() throws Exception {
        try(var connection=dataSource.getConnection()){
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
    }

    @Test void qr05PreparingAndReadyRequireFullPickWithoutDebitingStock(){
        var c=context();var o=order(c,true);
        sales.confirm(c.staff().userId(),c.tenant(),o.id(),c.warehouse());
        long v=version(c,o);
        var started=prep.startPreparing(c.staff(),c.tenant(),o.id(),
                c.warehouse(),v,key());
        assertThat(started.rowVersion()).isEqualTo(v+1);
        assertThatThrownBy(()->prep.markReadyForPickup(c.staff(),c.tenant(),o.id(),
                c.warehouse(),version(c,o),key())).hasMessage("PICKING_INCOMPLETE");
        UUID first=o.lines().getFirst();
        int qty=jdbc.queryForObject("""
                SELECT quantity FROM crm.sales_order_line WHERE tenant_id=? AND id=?
                """,Integer.class,c.tenant(),first);
        String firstKey=key();
        var picked=prep.confirmPicked(c.staff(),c.tenant(),o.id(),c.warehouse(),
                first,qty,version(c,o),firstKey);
        assertThat(prep.confirmPicked(c.staff(),c.tenant(),o.id(),c.warehouse(),
                first,qty,picked.rowVersion()-1,firstKey)).isEqualTo(picked);
        assertThatThrownBy(()->prep.markReadyForPickup(c.staff(),c.tenant(),o.id(),
                c.warehouse(),version(c,o),key())).hasMessage("PICKING_INCOMPLETE");
        UUID last=o.lines().getLast();
        int lastQty=jdbc.queryForObject("""
                SELECT quantity FROM crm.sales_order_line WHERE tenant_id=? AND id=?
                """,Integer.class,c.tenant(),last);
        prep.confirmPicked(c.staff(),c.tenant(),o.id(),c.warehouse(),last,
                lastQty,version(c,o),key());
        prep.markReadyForPickup(c.staff(),c.tenant(),o.id(),c.warehouse(),
                version(c,o),key());
        assertThat(status(c,o)).isEqualTo("READY_FOR_PICKUP");
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("20");
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("5");
        assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND movement_kind='FULFILLMENT'
                """,Integer.class,c.tenant())).isZero();
    }

    @Test void qr05WrongWarehouseAndRevokedAssignmentBlockPreparation(){
        var c=context();var o=order(c,false);
        sales.confirm(c.staff().userId(),c.tenant(),o.id(),c.warehouse());
        assertThatThrownBy(()->prep.startPreparing(c.staff(),c.tenant(),o.id(),
                c.otherWarehouse(),version(c,o),key()))
                .isInstanceOf(SecurityException.class);
        scopes.revoke(c.owner(),c.tenant(),c.warehouse(),c.staff().userId());
        assertThatThrownBy(()->prep.startPreparing(c.staff(),c.tenant(),o.id(),
                c.warehouse(),version(c,o),key()))
                .hasMessage("WAREHOUSE_SCOPE_REQUIRED");
        assertThat(status(c,o)).isEqualTo("CONFIRMED");
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("5");
    }

    @Test void qr06CredentialRequiresVerifiedBuyerAndReadyState(){
        var c=context();var o=order(c,false);
        assertThatThrownBy(()->pickup.issue(c.buyer(),o.publicRef(),key()))
                .hasMessage("ORDER_NOT_READY");
        var ready=ready(c,false);
        var other=context();
        assertThatThrownBy(()->pickup.issue(other.buyer(),ready.publicRef(),key()))
                .isInstanceOf(SecurityException.class);
        var issued=pickup.issue(c.buyer(),ready.publicRef(),key());
        assertThat(issued.rawProof()).hasSize(43);
        assertThat(issued.generation()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND order_id=? AND state='ISSUED'
                """,Integer.class,c.tenant(),ready.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND credential_digest=?
                """,Integer.class,c.tenant(),
                issued.rawProof().getBytes(java.nio.charset.StandardCharsets.UTF_8))).isZero();
    }

    @Test void qr06PreviewDoesNotConsumeAndCompletedHandoverDebitsOnce(){
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        var preview=pickup.preview(c.staff(),c.tenant(),c.warehouse(),issued.rawProof());
        assertThat(preview.status()).isEqualTo("READY_FOR_PICKUP");
        assertThat(preview.lines()).isEqualTo(2);
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover WHERE tenant_id=?
                """,Integer.class,c.tenant())).isZero();
        long before=version(c,o);
        String handoverKey=key();
        assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),false,before,handoverKey))
                .hasMessage("EXPLICIT_HANDOVER_CONFIRM_REQUIRED");
        var completed=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,before,handoverKey);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.fulfilledLines()).isEqualTo(2);
        assertThat(pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,before,handoverKey)).isEqualTo(completed);
        assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,before,key()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(status(c,o)).isEqualTo("COMPLETED");
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
        assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND movement_kind='FULFILLMENT'
                """,Integer.class,c.tenant())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover_line
                WHERE tenant_id=? AND handover_id=?
                """,Integer.class,c.tenant(),completed.handoverId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=? AND event_type='COMPLETED'
                """,Integer.class,c.tenant(),o.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND order_id=? AND state='CONSUMED'
                """,Integer.class,c.tenant(),o.id())).isEqualTo(1);
    }

    @Test void qr06ReissueInvalidatesOldSecretAndExpiredProofNeverDebits(){
        var c=context();var o=ready(c,false);
        String firstKey=key();
        var first=pickup.issue(c.buyer(),o.publicRef(),firstKey);
        assertThatThrownBy(()->pickup.issue(c.buyer(),o.publicRef(),firstKey))
                .hasMessage("PICKUP_PROOF_RECOVERY_REQUIRED");
        var next=pickup.issue(c.buyer(),o.publicRef(),key());
        assertThat(next.generation()).isEqualTo(2);
        assertThat(next.rawProof()).isNotEqualTo(first.rawProof());
        assertThatThrownBy(()->pickup.preview(c.staff(),c.tenant(),c.warehouse(),
                first.rawProof())).isInstanceOf(SecurityException.class);
        jdbc.update("""
                UPDATE crm.sales_pickup_credential
                SET issued_at=?,expires_at=?
                WHERE tenant_id=? AND order_id=? AND state='ISSUED'
                """,Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(1)),c.tenant(),o.id());
        assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                next.rawProof(),true,version(c,o),key()))
                .hasMessage("PICKUP_CREDENTIAL_EXPIRED");
        assertThat(status(c,o)).isEqualTo("READY_FOR_PICKUP");
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("20");
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("5");
    }

    @Test void qr06ForeignWarehouseAndSuspendedStaffCannotUseProof(){
        var c=context();var o=ready(c,false);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        assertThatThrownBy(()->pickup.preview(c.staff(),c.tenant(),c.otherWarehouse(),
                issued.rawProof())).isInstanceOf(SecurityException.class);
        jdbc.update("""
                UPDATE public.iam_user_account SET status='SUSPENDED'
                WHERE id=?
                """,c.staff().userId());
        assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,version(c,o),key()))
                .isInstanceOf(SecurityException.class);
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("5");
    }

    @Test void qr06PostgresConcurrentScansWithDifferentKeysFulfillOnlyOnce() throws Exception {
        requirePostgres();var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long v=version(c,o);
        CountDownLatch latch=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{
                latch.await(10,TimeUnit.SECONDS);
                try {return pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                        issued.rawProof(),true,v,key()).handoverId();}
                catch(IllegalStateException | SecurityException invalid){return null;}
            });
            var two=pool.submit(()->{
                latch.await(10,TimeUnit.SECONDS);
                try {return pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                        issued.rawProof(),true,v,key()).handoverId();}
                catch(IllegalStateException | SecurityException invalid){return null;}
            });
            latch.countDown();
            UUID a=one.get(30,TimeUnit.SECONDS),b=two.get(30,TimeUnit.SECONDS);
            assertThat(a!=null||b!=null).isTrue();
            assertThat(a==null||b==null).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,c.tenant(),o.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND movement_kind='FULFILLMENT'
                """,Integer.class,c.tenant())).isEqualTo(2);
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
    }

    @Test void qr06PostgresLastMovementFailureRollsBackEveryLineAndProof() throws Exception {
        requirePostgres();var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long v=version(c,o);
        // Test-only trigger injected at the second stock movement. The call
        // MUST roll back its first reservation, stock write, movement and audit.
        UUID lastItem=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot
                FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                ORDER BY catalog_item_id_snapshot DESC LIMIT 1
                """,UUID.class,c.tenant(),o.id());
        String fn="test_qr06_fail_"+UUID.randomUUID().toString().replace("-","");
        String trigger="trg_qr06_fail_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION crm."+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ "+
                "BEGIN IF NEW.item_id = '"+lastItem+"'::uuid THEN "+
                "RAISE EXCEPTION 'QR06_FAULT_INJECTED'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER "+trigger+
                " BEFORE INSERT ON crm.inv_stock_movement "+
                " FOR EACH ROW EXECUTE FUNCTION crm."+fn+"()");
        try {
            assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                    issued.rawProof(),true,v,key()))
                    .isInstanceOf(Exception.class);
            assertThat(status(c,o)).isEqualTo("READY_FOR_PICKUP");
            assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("20");
            assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("5");
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("20");
            assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("3");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.inv_stock_movement
                    WHERE tenant_id=? AND movement_kind='FULFILLMENT'
                    """,Integer.class,c.tenant())).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_pickup_handover
                    WHERE tenant_id=? AND order_id=?
                    """,Integer.class,c.tenant(),o.id())).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT state FROM crm.sales_pickup_credential
                    WHERE tenant_id=? AND order_id=? AND state='ISSUED'
                    """,String.class,c.tenant(),o.id())).isEqualTo("ISSUED");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+trigger+
                    " ON crm.inv_stock_movement");
            jdbc.execute("DROP FUNCTION IF EXISTS crm."+fn+"()");
        }
        var repaired=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,v,key());
        assertThat(repaired.fulfilledLines()).isEqualTo(2);
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
    }

    @Test void qr06PostgresImmutableHandoverReceiptsRejectTampering() throws Exception {
        requirePostgres();var c=context();var o=ready(c,false);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        var completed=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,version(c,o),key());
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.sales_pickup_handover
                WHERE tenant_id=? AND id=?
                """,c.tenant(),completed.handoverId()))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.sales_pickup_handover_line SET quantity=999
                WHERE tenant_id=? AND handover_id=?
                """,c.tenant(),completed.handoverId()))
                .isInstanceOf(Exception.class);
    }

    /** QR-06B/B1: persisted effect proof, not merely a successful service return. */
    private void assertExactlyOneHandover(Context c,Order o,int expectedLines) {
        assertThat(status(c,o)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,c.tenant(),o.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=? AND event_type='COMPLETED'
                """,Integer.class,c.tenant(),o.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover_line hl
                JOIN crm.sales_pickup_handover h
                  ON h.tenant_id=hl.tenant_id AND h.id=hl.handover_id
                WHERE h.tenant_id=? AND h.order_id=?
                """,Integer.class,c.tenant(),o.id())).isEqualTo(expectedLines);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement m
                JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=m.tenant_id AND r.id=m.reservation_id
                WHERE m.tenant_id=? AND r.order_id=? AND m.movement_kind='FULFILLMENT'
                """,Integer.class,c.tenant(),o.id())).isEqualTo(expectedLines);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND order_id=? AND state='CONSUMED'
                """,Integer.class,c.tenant(),o.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_balance
                WHERE tenant_id=? AND (reserved < 0 OR on_hand < reserved)
                """,Integer.class,c.tenant())).isZero();
    }

    private void assertNoHandoverEffects(Context c,Order o) {
        assertThat(status(c,o)).isEqualTo("READY_FOR_PICKUP");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_handover
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,c.tenant(),o.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=? AND event_type='COMPLETED'
                """,Integer.class,c.tenant(),o.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement m
                JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=m.tenant_id AND r.id=m.reservation_id
                WHERE m.tenant_id=? AND r.order_id=? AND m.movement_kind='FULFILLMENT'
                """,Integer.class,c.tenant(),o.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND order_id=? AND state='ISSUED'
                """,Integer.class,c.tenant(),o.id())).isEqualTo(1);
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("20");
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_balance
                WHERE tenant_id=? AND (reserved < 0 OR on_hand < reserved)
                """,Integer.class,c.tenant())).isZero();
    }

    /** The admitted physical handover keeps its PG locks until the outer commit.
     * Warehouse scope revocation must not commit in the middle of that operation. */
    @Test void qr06bPgHandoverCommitPrecedesConcurrentScopeRevocation() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long initialVersion=version(c,o);
        var handoverDone=new CountDownLatch(1);
        var releaseCommit=new CountDownLatch(1);
        var revokeEntered=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var handover=pool.submit(()->tx.execute(t->{
                var result=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                        issued.rawProof(),true,initialVersion,key());
                handoverDone.countDown();
                try {
                    if(!releaseCommit.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("HANDOVER_COMMIT_GATE_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return result;
            }));
            assertThat(handoverDone.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{
                revokeEntered.countDown();
                scopes.revoke(c.owner(),c.tenant(),c.warehouse(),c.staff().userId());
                return true;
            });
            assertThat(revokeEntered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->revoke.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                releaseCommit.countDown();
            }
            assertThat(handover.get(20,TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseCommit.countDown();
        }
        assertExactlyOneHandover(c,o,2);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM public.iam_warehouse_staff_scope
                WHERE tenant_id=? AND warehouse_id=? AND actor_user_id=?
                """,String.class,c.tenant(),c.warehouse(),c.staff().userId()))
                .isEqualTo("REVOKED");
        assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,initialVersion,key())).isInstanceOf(SecurityException.class);
        assertExactlyOneHandover(c,o,2);
    }

    /** If revocation acquires the IAM scope row first, checkout must fail closed. */
    @Test void qr06bPgScopeRevocationCommitPrecedesHandoverAdmission() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long initialVersion=version(c,o);
        var revokedButUncommitted=new CountDownLatch(1);
        var allowRevokeCommit=new CountDownLatch(1);
        var handoverEntered=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->tx.execute(t->{
                scopes.revoke(c.owner(),c.tenant(),c.warehouse(),c.staff().userId());
                revokedButUncommitted.countDown();
                try {
                    if(!allowRevokeCommit.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("REVOKE_COMMIT_GATE_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(revokedButUncommitted.await(10,TimeUnit.SECONDS)).isTrue();
            var handover=pool.submit(()->{
                handoverEntered.countDown();
                try {
                    pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                            issued.rawProof(),true,initialVersion,key());
                    return false;
                } catch(SecurityException denied) {
                    return true;
                }
            });
            assertThat(handoverEntered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->handover.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                allowRevokeCommit.countDown();
            }
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(handover.get(20,TimeUnit.SECONDS)).isTrue();
        } finally {
            allowRevokeCommit.countDown();
        }
        assertNoHandoverEffects(c,o);
    }

    /** A lost client response after COMMIT must not cause a second debit.
     * Even retries with a different idempotency key are negative business operations. */
    @Test void qr06bPgAmbiguousResponseReplayCannotDuplicateInventory() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long initialVersion=version(c,o);
        String originalKey=key();
        var completed=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,initialVersion,originalKey);
        // Simulate loss of the response by discarding it and reconstructing a retry.
        var replay=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,initialVersion,originalKey);
        assertThat(replay).isEqualTo(completed);
        assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,initialVersion,key()))
                .isInstanceOf(IllegalStateException.class);
        assertExactlyOneHandover(c,o,2);
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
        assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
    }


    private UUID assignedRole(Context c) {
        return jdbc.queryForObject("""
                SELECT role_id FROM public.iam_tenant_user_role
                WHERE tenant_id=? AND user_id=? ORDER BY role_id LIMIT 1
                """,UUID.class,c.tenant(),c.staff().userId());
    }

    /** QR06B-01: distinct, genuinely authorized employees rather than
     * concurrent requests from a single actor. */
    @Test void qr06bPgFiveDistinctEmployeesCompeteForSingleHandover() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long initialVersion=version(c,o);
        UUID role=assignedRole(c);
        List<VerifiedActor> employees=new ArrayList<>();
        employees.add(c.staff());
        UUID organization=jdbc.queryForObject("""
                SELECT organization_id FROM public.iam_workspace WHERE id=?
                """,UUID.class,c.tenant());
        for(int i=1;i<5;i++) {
            UUID id=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                    "qr06b-worker-"+UUID.randomUUID(),"Staff "+i)).getId();
            memberships.saveAndFlush(new OrganizationMembershipEntity(
                    UUID.randomUUID(),organization,id,OrganizationMembershipEntity.Role.MEMBER));
            roles.assign(c.owner(),c.tenant(),id,role);
            var actor=verified(id);
            scopes.assign(c.owner(),c.tenant(),c.warehouse(),actor.userId());
            employees.add(actor);
        }
        var readyToRace=new CountDownLatch(employees.size());
        var begin=new CountDownLatch(1);
        int success=0;
        try(var pool=Executors.newFixedThreadPool(employees.size())) {
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(VerifiedActor actor:employees) futures.add(pool.submit(()->{
                readyToRace.countDown();
                if(!begin.await(15,TimeUnit.SECONDS))
                    throw new IllegalStateException("SCANNER_BARRIER_TIMEOUT");
                try {
                    pickup.complete(actor,c.tenant(),c.warehouse(),
                            issued.rawProof(),true,initialVersion,key());
                    return true;
                } catch(IllegalStateException | SecurityException rejected) {
                    return false;
                }
            }));
            assertThat(readyToRace.await(15,TimeUnit.SECONDS)).isTrue();
            begin.countDown();
            for(var f:futures) if(f.get(30,TimeUnit.SECONDS)) success++;
        } finally {
            begin.countDown();
        }
        assertThat(success).isEqualTo(1);
        assertExactlyOneHandover(c,o,2);
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
    }

    /** B1: if an actual handover has been authorized, a role removal must wait
     * until the handover's outer PostgreSQL transaction commits. */
    @Test void qr06bPgHandoverCommitsBeforeRolePermissionRevocation() throws Exception {
        requirePostgres(); var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long v=version(c,o); UUID role=assignedRole(c);
        var admitted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var revokeEntered=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var handover=pool.submit(()->tx.execute(status->{
                var done=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                        issued.rawProof(),true,v,key());
                admitted.countDown();
                awaitQr06b(release);
                return done;
            }));
            assertThat(admitted.await(15,TimeUnit.SECONDS)).isTrue();
            var revoked=pool.submit(()->{
                revokeEntered.countDown();
                roles.revoke(c.owner(),c.tenant(),role,DomainPermission.INVENTORY_FULFILL);
                return true;
            });
            assertThat(revokeEntered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->revoked.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(handover.get(25,TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThat(revoked.get(25,TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertExactlyOneHandover(c,o,2);
        assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,v,key())).isInstanceOf(SecurityException.class);
        assertExactlyOneHandover(c,o,2);
    }

    /** B1: commit an IAM role revocation before releasing the actual handover.
     * The handover must deny and leave every business effect untouched. */
    @Test void qr06bPgRolePermissionRevocationCommitsBeforeHandover() throws Exception {
        requirePostgres();var c=context();var o=ready(c,false);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long v=version(c,o);UUID role=assignedRole(c);
        var revokedInTransaction=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var entered=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->tx.execute(status->{
                jdbc.queryForObject("""
                        SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE
                        """,UUID.class,c.tenant());
                roles.revoke(c.owner(),c.tenant(),role,DomainPermission.INVENTORY_FULFILL);
                revokedInTransaction.countDown();
                awaitQr06b(release);
                return true;
            }));
            assertThat(revokedInTransaction.await(15,TimeUnit.SECONDS)).isTrue();
            var attempt=pool.submit(()->{
                entered.countDown();
                try {
                    pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                            issued.rawProof(),true,v,key());
                    return false;
                } catch(SecurityException expected) {
                    return true;
                }
            });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->attempt.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(revoke.get(25,TimeUnit.SECONDS)).isTrue();
            assertThat(attempt.get(25,TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertNoHandoverEffects(c,o);
    }

    /** Actual handover vs global user suspension, command-first ordering. */
    @Test void qr06bPgHandoverCommitsBeforeGlobalAccountSuspension() throws Exception {
        requirePostgres();var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long v=version(c,o);
        var admitted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var entered=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var command=pool.submit(()->tx.execute(status->{
                var done=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                        issued.rawProof(),true,v,key());
                admitted.countDown();
                awaitQr06b(release);
                return done;
            }));
            assertThat(admitted.await(15,TimeUnit.SECONDS)).isTrue();
            var suspension=pool.submit(()->tx.execute(status->{
                entered.countDown();
                jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",
                        c.staff().userId());
                return true;
            }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->suspension.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(command.get(25,TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThat(suspension.get(25,TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertExactlyOneHandover(c,o,2);
        assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,v,key())).isInstanceOf(SecurityException.class);
        assertExactlyOneHandover(c,o,2);
    }

    /** Actual handover vs global user suspension, revocation-first ordering. */
    @Test void qr06bPgGlobalAccountSuspensionCommitsBeforeHandover() throws Exception {
        requirePostgres();var c=context();var o=ready(c,false);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long v=version(c,o);
        var suspendedUncommitted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var entered=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var suspension=pool.submit(()->tx.execute(status->{
                jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",
                        c.staff().userId());
                suspendedUncommitted.countDown();
                awaitQr06b(release);
                return true;
            }));
            assertThat(suspendedUncommitted.await(15,TimeUnit.SECONDS)).isTrue();
            var command=pool.submit(()->{
                entered.countDown();
                try {
                    pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                            issued.rawProof(),true,v,key());
                    return false;
                } catch(SecurityException denied) {
                    return true;
                }
            });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->command.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(suspension.get(25,TimeUnit.SECONDS)).isTrue();
            assertThat(command.get(25,TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertNoHandoverEffects(c,o);
    }

    private static void awaitQr06b(CountDownLatch latch) {
        try {
            if(!latch.await(15,TimeUnit.SECONDS))
                throw new IllegalStateException("QR06B_TRANSACTION_BARRIER_TIMEOUT");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }


    /**
     * QR-06B/B2: fail after durable handover receipt INSERT, but before
     * credential consumption. The entire PostgreSQL transaction must rollback.
     * This models an abrupt SQL-side failure; it is NOT a process kill.
     */
    @Test void qr06bPgReceiptInsertFailureRollsBackAllEffectsThenRetrySucceeds() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long initialVersion=version(c,o);
        String suffix=UUID.randomUUID().toString().replace("-","");
        String fn="qr06b_fail_receipt_"+suffix;
        String trigger="qr06b_trg_receipt_"+suffix;
        jdbc.execute("CREATE FUNCTION crm."+fn+"() RETURNS trigger LANGUAGE plpgsql AS $qr06b$ "+
                "BEGIN IF NEW.tenant_id = '"+c.tenant()+"'::uuid "+
                "AND NEW.order_id = '"+o.id()+"'::uuid THEN "+
                "RAISE EXCEPTION 'QR06B_RECEIPT_FAULT'; END IF; RETURN NEW; END; $qr06b$");
        jdbc.execute("CREATE TRIGGER "+trigger+
                " AFTER INSERT ON crm.sales_pickup_handover "+
                " FOR EACH ROW EXECUTE FUNCTION crm."+fn+"()");
        String requestKey=key();
        try {
            assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                    issued.rawProof(),true,initialVersion,requestKey))
                    .isInstanceOf(Exception.class);
            assertNoHandoverEffects(c,o);
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("20");
            assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("3");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+trigger+" ON crm.sales_pickup_handover");
            jdbc.execute("DROP FUNCTION IF EXISTS crm."+fn+"()");
        }
        var recovered=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,initialVersion,requestKey);
        assertThat(recovered.fulfilledLines()).isEqualTo(2);
        assertExactlyOneHandover(c,o,2);
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
    }

    /**
     * QR-06B/B2: inject failure at the final credential consumption UPDATE,
     * after INVENTORY, SALES and immutable handover receipt statements.
     * Everything must rollback and authorized replay must commit once.
     */
    @Test void qr06bPgCredentialConsumptionFailureRollsBackReceiptAndInventory() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long initialVersion=version(c,o);
        String suffix=UUID.randomUUID().toString().replace("-","");
        String fn="qr06b_fail_consume_"+suffix;
        String trigger="qr06b_trg_consume_"+suffix;
        jdbc.execute("CREATE FUNCTION crm."+fn+"() RETURNS trigger LANGUAGE plpgsql AS $qr06b$ "+
                "BEGIN IF NEW.tenant_id = '"+c.tenant()+"'::uuid "+
                "AND NEW.order_id = '"+o.id()+"'::uuid "+
                "AND NEW.state='CONSUMED' THEN "+
                "RAISE EXCEPTION 'QR06B_CONSUME_FAULT'; END IF; RETURN NEW; END; $qr06b$");
        jdbc.execute("CREATE TRIGGER "+trigger+
                " AFTER UPDATE ON crm.sales_pickup_credential "+
                " FOR EACH ROW EXECUTE FUNCTION crm."+fn+"()");
        String requestKey=key();
        try {
            assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                    issued.rawProof(),true,initialVersion,requestKey))
                    .isInstanceOf(Exception.class);
            assertNoHandoverEffects(c,o);
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("20");
            assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("3");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+trigger+" ON crm.sales_pickup_credential");
            jdbc.execute("DROP FUNCTION IF EXISTS crm."+fn+"()");
        }
        var recovered=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,initialVersion,requestKey);
        assertThat(recovered.fulfilledLines()).isEqualTo(2);
        assertExactlyOneHandover(c,o,2);
        var replay=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                issued.rawProof(),true,initialVersion,requestKey);
        assertThat(replay).isEqualTo(recovered);
        assertExactlyOneHandover(c,o,2);
    }



    private static String qr06bDiagnostics(java.nio.file.Path path,String rawProof) {
        try {
            String content=java.nio.file.Files.exists(path)
                    ?java.nio.file.Files.readString(path):"<missing child log>";
            content=content.replace(rawProof,"[REDACTED_QR_SECRET]");
            return content.substring(Math.max(0,content.length()-5000));
        } catch(Exception e) {
            return "<child log unavailable: "+e.getClass().getSimpleName()+">";
        }
    }

    /** B2 process runner: new java.exe, new Spring context and PG connection pool. */
    private Process startQr06bWorker(String mode, Context c, String proofFile,
                                     long version, String requestKey,
                                     java.nio.file.Path ready,java.nio.file.Path gate,
                                     java.nio.file.Path output) throws Exception {
        String cp=System.getenv("RM_QR06B_JAVA_CLASSPATH");
        if(cp==null||cp.isBlank())
            throw new IllegalStateException("RM_QR06B_JAVA_CLASSPATH required for process qualification");
        String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),
                "bin","java").toString();
        return new ProcessBuilder(javaExecutable,"-cp",cp,
                "kz.rm.sales.QrHandoverProcessWorker",
                mode,c.tenant().toString(),c.warehouse().toString(),
                c.staff().userId().toString(),Long.toString(version),
                proofFile,requestKey,ready.toString(),gate.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private static java.nio.file.Path writeQr06bSecret(java.nio.file.Path dir,String proof)
            throws Exception {
        java.nio.file.Path path=dir.resolve("credential");
        java.nio.file.Files.writeString(path,proof);
        try {
            java.nio.file.Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch(UnsupportedOperationException ignored) { /* non-POSIX developer host */ }
        return path;
    }

    private static int waitQr06bProcess(Process p,java.nio.file.Path output) throws Exception {
        if(!p.waitFor(65,TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("QR06B child JVM timed out; log: "+output);
        }
        int exit=p.exitValue();
        // Child logs are retained as CI runner temporary diagnostics only;
        // never print a raw QR secret or subprocess arguments.
        return exit;
    }

    /** Genuine JVM halt before outer transaction COMMIT. On restart the caller
     * sees READY + original inventory, then can complete once. */
    @Test void qr06bPgJVMKilledBeforeCommitRollsBackAndRetrySucceeds() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long version=version(c,o);
        String req=key();
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("qr06b-pre-");
        Process child=null;
        try {
            var secret=writeQr06bSecret(dir,issued.rawProof());
            child=startQr06bWorker("HALT_BEFORE_COMMIT",c,secret.toString(),version,req,
                    java.nio.file.Path.of("-"),java.nio.file.Path.of("-"),dir.resolve("child.log"));
            assertThat(waitQr06bProcess(child,dir.resolve("child.log")))
                    .withFailMessage(()->"B2 pre-COMMIT worker diagnostics: "+
                            qr06bDiagnostics(dir.resolve("child.log"),issued.rawProof()))
                    .isEqualTo(86);
            assertNoHandoverEffects(c,o);
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("20");
            assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("3");
            var retried=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                    issued.rawProof(),true,version,req);
            assertThat(retried.status()).isEqualTo("COMPLETED");
            assertExactlyOneHandover(c,o,2);
        } finally {
            if(child!=null&&child.isAlive())child.destroyForcibly();
            java.nio.file.Files.deleteIfExists(dir.resolve("credential"));
            java.nio.file.Files.deleteIfExists(dir.resolve("child.log"));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    /** Actual process ends AFTER the COMMIT, but BEFORE any response is sent.
     * Another JVM (the test runner) replays and verifies the identical receipt. */
    @Test void qr06bPgJVMKilledAfterCommitReplayHasNoSecondDebit() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long version=version(c,o);
        String req=key();
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("qr06b-post-");
        Process child=null;
        try {
            var secret=writeQr06bSecret(dir,issued.rawProof());
            child=startQr06bWorker("HALT_AFTER_COMMIT",c,secret.toString(),version,req,
                    java.nio.file.Path.of("-"),java.nio.file.Path.of("-"),dir.resolve("child.log"));
            assertThat(waitQr06bProcess(child,dir.resolve("child.log")))
                    .withFailMessage(()->"B2 post-COMMIT worker diagnostics: "+
                            qr06bDiagnostics(dir.resolve("child.log"),issued.rawProof()))
                    .isEqualTo(87);
            assertExactlyOneHandover(c,o,2);
            var receipt=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                    issued.rawProof(),true,version,req);
            assertThat(receipt.status()).isEqualTo("COMPLETED");
            assertExactlyOneHandover(c,o,2);
            assertThatThrownBy(()->pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                    issued.rawProof(),true,version,key()))
                    .isInstanceOf(IllegalStateException.class);
            assertExactlyOneHandover(c,o,2);
        } finally {
            if(child!=null&&child.isAlive())child.destroyForcibly();
            java.nio.file.Files.deleteIfExists(dir.resolve("credential"));
            java.nio.file.Files.deleteIfExists(dir.resolve("child.log"));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    /** Two independently bootstrapped JVMs release a file barrier together
     * and race for the same physical order; exactly one can post stock. */
    @Test void qr06bPgTwoIndependentJVMsCompeteForOneHandover() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long version=version(c,o);
        var dir=java.nio.file.Files.createTempDirectory("qr06b-two-");
        Process a=null,b=null;
        try {
            var secret=writeQr06bSecret(dir,issued.rawProof());
            var gate=dir.resolve("gate");
            var readyA=dir.resolve("ready-a");
            var readyB=dir.resolve("ready-b");
            a=startQr06bWorker("COMPETE",c,secret.toString(),version,key(),
                    readyA,gate,dir.resolve("a.log"));
            b=startQr06bWorker("COMPETE",c,secret.toString(),version,key(),
                    readyB,gate,dir.resolve("b.log"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
            while(!java.nio.file.Files.exists(readyA)||!java.nio.file.Files.exists(readyB)) {
                if(!a.isAlive()||!b.isAlive())
                    throw new AssertionError("QR06B worker exited before start barrier: A="+
                            qr06bDiagnostics(dir.resolve("a.log"),issued.rawProof())+
                            " B="+qr06bDiagnostics(dir.resolve("b.log"),issued.rawProof()));
                if(System.nanoTime()>deadline)
                    throw new AssertionError("QR06B two JVMs did not start in time");
                Thread.sleep(30);
            }
            java.nio.file.Files.writeString(gate,"GO");
            int ea=waitQr06bProcess(a,dir.resolve("a.log"));
            int eb=waitQr06bProcess(b,dir.resolve("b.log"));
            assertThat(java.util.List.of(ea,eb)).withFailMessage(()->
                    "QR06B two JVM exits: "+qr06bDiagnostics(dir.resolve("a.log"),issued.rawProof())+
                    " ; "+qr06bDiagnostics(dir.resolve("b.log"),issued.rawProof()))
                    .containsExactlyInAnyOrder(0,2);
            assertExactlyOneHandover(c,o,2);
            assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
        } finally {
            if(a!=null&&a.isAlive())a.destroyForcibly();
            if(b!=null&&b.isAlive())b.destroyForcibly();
            for(String file:java.util.List.of("credential","gate","ready-a","ready-b","a.log","b.log"))
                java.nio.file.Files.deleteIfExists(dir.resolve(file));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }


    private record B3Attempt(boolean completed,long elapsedNanos) {}

    private static long b3Percentile(java.util.List<Long> nanos,int percentile) {
        if(nanos.isEmpty())throw new IllegalArgumentException("No B3 measurements");
        var copy=new java.util.ArrayList<>(nanos);
        copy.sort(Long::compareTo);
        int index=Math.max(0,(int)Math.ceil(percentile*copy.size()/100.0)-1);
        return TimeUnit.NANOSECONDS.toMillis(copy.get(index));
    }

    /**
     * B3 ENTRY load: real PostgreSQL with 10/50 competing attempts to spend
     * the same physical stock. Measurements are diagnostic, not production SLO.
     */
    @Test void qr06bPgB3HotOrderContentionMeasuresLatencyAndLedger() throws Exception {
        requirePostgres();
        for(int clients:new int[]{10,50}) {
            var c=context();var o=ready(c,true);
            var issued=pickup.issue(c.buyer(),o.publicRef(),key());
            long initialVersion=version(c,o);
            var readyToStart=new CountDownLatch(clients),go=new CountDownLatch(1);
            var measurements=new java.util.ArrayList<Long>();
            int completed=0,denied=0;
            try(var pool=Executors.newFixedThreadPool(clients)) {
                var futures=new java.util.ArrayList<java.util.concurrent.Future<B3Attempt>>();
                for(int n=0;n<clients;n++) futures.add(pool.submit(()->{
                    readyToStart.countDown();
                    if(!go.await(20,TimeUnit.SECONDS))
                        throw new IllegalStateException("B3_HOT_START_TIMEOUT");
                    long start=System.nanoTime();
                    try {
                        pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                                issued.rawProof(),true,initialVersion,key());
                        return new B3Attempt(true,System.nanoTime()-start);
                    }catch(IllegalStateException | SecurityException expected) {
                        return new B3Attempt(false,System.nanoTime()-start);
                    }
                }));
                assertThat(readyToStart.await(20,TimeUnit.SECONDS)).isTrue();
                go.countDown();
                for(var future:futures) {
                    var attempt=future.get(90,TimeUnit.SECONDS);
                    measurements.add(attempt.elapsedNanos());
                    if(attempt.completed())completed++; else denied++;
                }
            } finally {
                go.countDown();
            }
            assertThat(completed).isEqualTo(1);
            assertThat(denied).isEqualTo(clients-1);
            assertExactlyOneHandover(c,o,2);
            assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
            System.out.printf(java.util.Locale.ROOT,
                    "QR06B_B3_HOT clients=%d completed=%d rejected=%d p50_ms=%d p95_ms=%d p99_ms=%d%n",
                    clients,completed,denied,b3Percentile(measurements,50),
                    b3Percentile(measurements,95),b3Percentile(measurements,99));
        }
    }

    /**
     * B3 ENTRY mixed: warehouse access revocation competes with physical
     * checkout. Zero or one handover is allowed depending on commit order,
     * but never a duplicate or a partial stock mutation.
     */
    @Test void qr06bPgB3MixedHandoverAndWarehouseRevokePreservesInvariants() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        long initialVersion=version(c,o);
        int clients=20;
        var readyToStart=new CountDownLatch(clients+1),go=new CountDownLatch(1);
        var latencies=new java.util.ArrayList<Long>();
        int completed=0,denied=0;
        try(var pool=Executors.newFixedThreadPool(clients+1)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<B3Attempt>>();
            for(int n=0;n<clients;n++) futures.add(pool.submit(()->{
                readyToStart.countDown();
                if(!go.await(20,TimeUnit.SECONDS))
                    throw new IllegalStateException("B3_MIXED_START_TIMEOUT");
                long start=System.nanoTime();
                try {
                    pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                            issued.rawProof(),true,initialVersion,key());
                    return new B3Attempt(true,System.nanoTime()-start);
                }catch(IllegalStateException | SecurityException expected) {
                    return new B3Attempt(false,System.nanoTime()-start);
                }
            }));
            var revoke=pool.submit(()->{
                readyToStart.countDown();
                if(!go.await(20,TimeUnit.SECONDS))
                    throw new IllegalStateException("B3_REVOKE_START_TIMEOUT");
                scopes.revoke(c.owner(),c.tenant(),c.warehouse(),c.staff().userId());
                return true;
            });
            assertThat(readyToStart.await(20,TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for(var future:futures) {
                var result=future.get(90,TimeUnit.SECONDS);
                latencies.add(result.elapsedNanos());
                if(result.completed())completed++; else denied++;
            }
            assertThat(revoke.get(90,TimeUnit.SECONDS)).isTrue();
        }finally {
            go.countDown();
        }
        assertThat(completed).isBetween(0,1);
        assertThat(denied).isEqualTo(clients-completed);
        if(completed==1) {
            assertExactlyOneHandover(c,o,2);
            assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
        } else {
            assertNoHandoverEffects(c,o);
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("20");
            assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("3");
        }
        assertThat(jdbc.queryForObject("""
                SELECT state FROM public.iam_warehouse_staff_scope
                WHERE tenant_id=? AND warehouse_id=? AND actor_user_id=?
                """,String.class,c.tenant(),c.warehouse(),c.staff().userId()))
                .isEqualTo("REVOKED");
        System.out.printf(java.util.Locale.ROOT,
                "QR06B_B3_MIXED clients=%d completed=%d rejected=%d p50_ms=%d p95_ms=%d p99_ms=%d%n",
                clients,completed,denied,b3Percentile(latencies,50),
                b3Percentile(latencies,95),b3Percentile(latencies,99));
    }

}
