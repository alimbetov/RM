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
    @Autowired SalesReturnRequestService returns;
    @Autowired SalesReturnReviewService returnReview;
    @Autowired SalesReturnPhysicalService returnPhysical;
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
    @Autowired kz.rm.inventory.AuthenticatedInventoryCommands inventoryCommands;
    @Autowired kz.rm.inventory.AuthenticatedInventoryReconciliation reconciliation;
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
        return waitQr06bProcess(p,output,65);
    }

    private static int waitQr06bProcess(Process p,java.nio.file.Path output,int seconds) throws Exception {
        if(!p.waitFor(seconds,TimeUnit.SECONDS)) {
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
            var readyToStart=new CountDownLatch(clients);
            var go=new CountDownLatch(1);
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
        var readyToStart=new CountDownLatch(clients+1);
        var go=new CountDownLatch(1);
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


    private record B31Target(Order order,SalesPickupHandoverService.Issued proof,long version) {}
    private record B31Probe(boolean success, long nanos) {}

    /** QR-06B/B3.1: three independently owned orders on shared stock positions,
     * three repeatable rounds at each 100/250 client profile. Never translate
     * expected business denials into infrastructure success metrics. */
    @Test void qr06bPgB31RepeatedMultiOrder100And250() throws Exception {
        requirePostgres();
        for (int clients : new int[]{100,250}) {
            for (int repetition=1;repetition<=3;repetition++) {
                Context c=context();
                List<B31Target> targets=new ArrayList<>();
                for (int n=0;n<3;n++) {
                    Order o=ready(c,true);
                    var proof=pickup.issue(c.buyer(),o.publicRef(),key());
                    targets.add(new B31Target(o,proof,version(c,o)));
                }
                var prepared=new CountDownLatch(clients);
                var go=new CountDownLatch(1);
                var responseTimes=new ArrayList<Long>();
                int successes=0,conflicts=0;
                long started=System.nanoTime();
                try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures=new ArrayList<java.util.concurrent.Future<B31Probe>>();
                    for(int i=0;i<clients;i++) {
                        B31Target target=targets.get(i%targets.size());
                        futures.add(pool.submit(()->{
                            prepared.countDown();
                            if(!go.await(30,TimeUnit.SECONDS))
                                throw new IllegalStateException("B31_START_BARRIER_TIMEOUT");
                            long began=System.nanoTime();
                            try {
                                pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                                        target.proof().rawProof(),true,target.version(),key());
                                return new B31Probe(true,System.nanoTime()-began);
                            }catch(IllegalStateException | SecurityException expected) {
                                return new B31Probe(false,System.nanoTime()-began);
                            }
                        }));
                    }
                    assertThat(prepared.await(30,TimeUnit.SECONDS)).isTrue();
                    started=System.nanoTime();
                    go.countDown();
                    for(var future:futures) {
                        B31Probe result=future.get(120,TimeUnit.SECONDS);
                        responseTimes.add(result.nanos());
                        if(result.success())successes++;else conflicts++;
                    }
                } finally {
                    go.countDown();
                }
                long wallNanos=System.nanoTime()-started;
                assertThat(successes).isEqualTo(targets.size());
                assertThat(conflicts).isEqualTo(clients-targets.size());
                for(B31Target target:targets)
                    assertExactlyOneHandover(c,target.order(),2);
                assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("5");
                assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("11");
                assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
                assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
                System.out.printf(java.util.Locale.ROOT,
                        "QR06B_B31_MULTI sha=%s clients=%d round=%d orders=3 completed=%d conflicts=%d "+
                        "attempts_per_sec=%.2f p50_ms=%d p95_ms=%d p99_ms=%d%n",
                        System.getenv().getOrDefault("GITHUB_SHA","local"),
                        clients,repetition,successes,conflicts,
                        clients*1.0/Math.max(1e-9,wallNanos/1e9),
                        b3Percentile(responseTimes,50),
                        b3Percentile(responseTimes,95),
                        b3Percentile(responseTimes,99));
            }
        }
    }

    /** B3.1 real authorized INV correction and consistent-read reconciliation
     * overlap handover. Cancellation is deliberately a DIFFERENT CONFIRMED
     * order, because READY_FOR_PICKUP cancellation is forbidden by policy. */
    @Test void qr06bPgB31MixedCorrectionsReconciliationAndSeparateCancellation() throws Exception {
        requirePostgres();
        Context c=context();
        UUID role=assignedRole(c);
        for(DomainPermission permission:List.of(
                DomainPermission.INVENTORY_ADJUST,
                DomainPermission.INVENTORY_RELEASE,
                DomainPermission.SALES_ORDER_CANCEL))
            roles.grant(c.owner(),c.tenant(),role,permission);
        List<B31Target> targets=new ArrayList<>();
        for(int n=0;n<2;n++) {
            Order order=ready(c,true);
            targets.add(new B31Target(order,pickup.issue(c.buyer(),order.publicRef(),key()),
                    version(c,order)));
        }
        Order cancellable=order(c,false);
        sales.confirm(c.staff().userId(),c.tenant(),cancellable.id(),c.warehouse());

        int contenders=40;
        var readyToStart=new CountDownLatch(contenders+4);
        var go=new CountDownLatch(1);
        var latencies=new ArrayList<Long>();
        int completed=0;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var handovers=new ArrayList<java.util.concurrent.Future<B31Probe>>();
            for(int i=0;i<contenders;i++) {
                B31Target target=targets.get(i%targets.size());
                handovers.add(pool.submit(()->{
                    readyToStart.countDown();
                    if(!go.await(30,TimeUnit.SECONDS))
                        throw new IllegalStateException("B31_MIXED_BARRIER_TIMEOUT");
                    long start=System.nanoTime();
                    try {
                        pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                                target.proof().rawProof(),true,target.version(),key());
                        return new B31Probe(true,System.nanoTime()-start);
                    }catch(IllegalStateException | SecurityException conflict) {
                        return new B31Probe(false,System.nanoTime()-start);
                    }
                }));
            }
            var correctionOne=pool.submit(()->{
                readyToStart.countDown();awaitQr06b(go);
                return inventoryCommands.correct(c.staff(),c.tenant(),c.warehouse(),
                        c.itemOne(),key(),BigDecimal.ONE);
            });
            var correctionTwo=pool.submit(()->{
                readyToStart.countDown();awaitQr06b(go);
                return inventoryCommands.correct(c.staff(),c.tenant(),c.warehouse(),
                        c.itemTwo(),key(),BigDecimal.ONE);
            });
            var inspect=pool.submit(()->{
                readyToStart.countDown();awaitQr06b(go);
                var first=reconciliation.inspect(c.staff(),c.tenant(),c.warehouse(),c.itemOne());
                var second=reconciliation.inspect(c.staff(),c.tenant(),c.warehouse(),c.itemTwo());
                return java.util.List.of(first,second);
            });
            var cancel=pool.submit(()->{
                readyToStart.countDown();awaitQr06b(go);
                return sales.cancel(c.staff().userId(),c.tenant(),cancellable.id());
            });
            assertThat(readyToStart.await(30,TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for(var result:handovers) {
                B31Probe p=result.get(120,TimeUnit.SECONDS);
                latencies.add(p.nanos());
                if(p.success())completed++;
            }
            assertThat(correctionOne.get(120,TimeUnit.SECONDS).delta())
                    .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(correctionTwo.get(120,TimeUnit.SECONDS).delta())
                    .isEqualByComparingTo(BigDecimal.ONE);
            var readings=inspect.get(120,TimeUnit.SECONDS);
            for(var reading:readings) {
                assertThat(reading.finding())
                        .isNotEqualTo(kz.rm.inventory.AuthenticatedInventoryReconciliation.Finding.RESERVED_MISMATCH)
                        .isNotEqualTo(kz.rm.inventory.AuthenticatedInventoryReconciliation.Finding.MOVEMENT_BALANCE_MISMATCH);
            }
            assertThat(cancel.get(120,TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
        }finally {
            go.countDown();
        }
        assertThat(completed).isEqualTo(2);
        for(B31Target target:targets)
            assertExactlyOneHandover(c,target.order(),2);
        assertThat(status(c,cancellable)).isEqualTo("CANCELLED");
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("11");
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("15");
        assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
        assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_balance
                WHERE tenant_id=? AND (reserved<0 OR on_hand<reserved)
                """,Integer.class,c.tenant())).isZero();
        System.out.printf(java.util.Locale.ROOT,
                "QR06B_B31_MIXED clients=%d orders=2 completed=%d conflicts=%d "+
                "corrections=2 reconciliation=2 cancellation=1 p50_ms=%d p95_ms=%d p99_ms=%d%n",
                contenders,completed,contenders-completed,b3Percentile(latencies,50),
                b3Percentile(latencies,95),b3Percentile(latencies,99));
    }


    /** B3.1: 100 and 250 callers split across two real JVMs. The worker
     * results and database ledger, not process-local success counters alone,
     * are the admission proof. */
    @Test void qr06bPgB31DistributedTwoJvm100And250() throws Exception {
        requirePostgres();
        for(int total:new int[]{100,250}) {
            Context c=context();
            Order o=ready(c,true);
            var issued=pickup.issue(c.buyer(),o.publicRef(),key());
            long initialVersion=version(c,o);
            var dir=java.nio.file.Files.createTempDirectory("qr06b-b31-jvm-");
            Process a=null,b=null;
            try {
                var secret=writeQr06bSecret(dir,issued.rawProof());
                var gate=dir.resolve("gate");
                var readyA=dir.resolve("ready-a");
                var readyB=dir.resolve("ready-b");
                a=startQr06bWorker("LOAD_"+(total/2),c,secret.toString(),
                        initialVersion,key(),readyA,gate,dir.resolve("a.log"));
                b=startQr06bWorker("LOAD_"+(total/2),c,secret.toString(),
                        initialVersion,key(),readyB,gate,dir.resolve("b.log"));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
                while(!java.nio.file.Files.exists(readyA)||!java.nio.file.Files.exists(readyB)) {
                    if(!a.isAlive()||!b.isAlive())
                        throw new AssertionError("Distributed JVM bootstrap failed. A="+
                                qr06bDiagnostics(dir.resolve("a.log"),issued.rawProof())+
                                " B="+qr06bDiagnostics(dir.resolve("b.log"),issued.rawProof()));
                    if(System.nanoTime()>deadline)
                        throw new AssertionError("Distributed JVM startup timeout");
                    Thread.sleep(30);
                }
                long begin=System.nanoTime();
                java.nio.file.Files.writeString(gate,"GO");
                int resultA=waitQr06bProcess(a,dir.resolve("a.log"));
                int resultB=waitQr06bProcess(b,dir.resolve("b.log"));
                assertThat(resultA).withFailMessage(()->
                        qr06bDiagnostics(dir.resolve("a.log"),issued.rawProof())).isZero();
                assertThat(resultB).withFailMessage(()->
                        qr06bDiagnostics(dir.resolve("b.log"),issued.rawProof())).isZero();
                assertExactlyOneHandover(c,o,2);
                assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
                assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
                System.out.printf(java.util.Locale.ROOT,
                        "QR06B_B31_DISTRIBUTED clients=%d jvms=2 completed=1 "+
                        "rejected=%d elapsed_ms=%d%n",
                        total,total-1,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));
            } finally {
                if(a!=null&&a.isAlive())a.destroyForcibly();
                if(b!=null&&b.isAlive())b.destroyForcibly();
                for(String file:List.of("credential","gate","ready-a","ready-b","a.log","b.log"))
                    java.nio.file.Files.deleteIfExists(dir.resolve(file));
                java.nio.file.Files.deleteIfExists(dir);
            }
        }
    }


    private record B32Operation(String category,boolean success,long nanos) {}
    private static void b32Print(String profile,java.util.List<B32Operation> outcomes,
                                 long wallNanos) {
        for(String group:java.util.List.of("COMPLETED","BUSINESS_REJECTED","STOCK_MUTATION")) {
            var latencies=outcomes.stream().filter(x->x.category().equals(group))
                    .map(B32Operation::nanos).toList();
            if(latencies.isEmpty())continue;
            System.out.printf(java.util.Locale.ROOT,
                    "QR06B_B32 profile=%s outcome=%s count=%d p50_ms=%d p95_ms=%d p99_ms=%d%n",
                    profile,group,latencies.size(),b3Percentile(latencies,50),
                    b3Percentile(latencies,95),b3Percentile(latencies,99));
        }
        long done=outcomes.stream().filter(x->x.category().equals("COMPLETED")).count();
        long errors=outcomes.stream().filter(x->!x.success()).count();
        System.out.printf(java.util.Locale.ROOT,
                "QR06B_B32_TOTAL profile=%s successes=%d technical_errors=%d "+
                "wall_ms=%d completed_per_sec=%.2f%n",
                profile,done,errors,TimeUnit.NANOSECONDS.toMillis(wallNanos),
                done/Math.max(1e-9,wallNanos/1e9));
    }

    /** B3.2 entry: 48 independent successful orders across TWO independent tenants.
     * Two bounded waves intentionally distinguish actual completed-order throughput
     * from B3.1's mostly-rejected same-order attempts. */
    @Test void qr06bPgB32TwoTenantIndependentSuccessfulThroughput() throws Exception {
        requirePostgres();
        var a=context();var b=context();
        var contexts=List.of(a,b);
        for(Context c:contexts) {
            roles.grant(c.owner(),c.tenant(),assignedRole(c),
                    DomainPermission.INVENTORY_RECEIVE);
            inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                    c.itemOne(),key(),new BigDecimal("200"));
            inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                    c.itemTwo(),key(),new BigDecimal("200"));
        }
        var all=new ArrayList<java.util.Map.Entry<Context,B31Target>>();
        for(Context c:contexts) {
            for(int i=0;i<24;i++) {
                var o=ready(c,true);
                var proof=pickup.issue(c.buyer(),o.publicRef(),key());
                all.add(java.util.Map.entry(c,new B31Target(o,proof,version(c,o))));
            }
        }
        var observations=new ArrayList<B32Operation>();
        long totalMeasuredNanos=0;
        for(int wave=0;wave<2;wave++) {
            // Alternate tenant A/B in EACH measured wave, rather than placing
            // the two tenant cohorts in sequential separate waves.
            var batch=new ArrayList<java.util.Map.Entry<Context,B31Target>>();
            for(int i=0;i<24;i++)
                batch.add(all.get((i%2)*24+wave*12+i/2));
            var readyGate=new CountDownLatch(batch.size());
            var release=new CountDownLatch(1);
            long began;
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var futures=new ArrayList<java.util.concurrent.Future<B32Operation>>();
                for(var entry:batch) futures.add(pool.submit(()->{
                    readyGate.countDown();
                    if(!release.await(20,TimeUnit.SECONDS))
                        throw new IllegalStateException("B32_RELEASE_TIMEOUT");
                    long start=System.nanoTime();
                    var target=entry.getValue();
                    var c=entry.getKey();
                    var response=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                            target.proof().rawProof(),true,target.version(),key());
                    if(!"COMPLETED".equals(response.status()))
                        throw new AssertionError("Unexpected handover state");
                    return new B32Operation("COMPLETED",true,System.nanoTime()-start);
                }));
                assertThat(readyGate.await(20,TimeUnit.SECONDS)).isTrue();
                began=System.nanoTime();
                release.countDown();
                for(var future:futures)
                    observations.add(future.get(120,TimeUnit.SECONDS));
            }finally {
                release.countDown();
            }
            totalMeasuredNanos+=System.nanoTime()-began;
        }
        assertThat(observations).hasSize(48).allMatch(B32Operation::success);
        // Classify a separate set of expected idempotency/business conflicts.
        // A denied attempt is never counted as a completed order.
        for(int i=0;i<12;i++) {
            var e=all.get(i);
            long start=System.nanoTime();
            assertThatThrownBy(()->pickup.complete(e.getKey().staff(),e.getKey().tenant(),
                    e.getKey().warehouse(),e.getValue().proof().rawProof(),true,
                    e.getValue().version(),key())).isInstanceOf(IllegalStateException.class);
            observations.add(new B32Operation("BUSINESS_REJECTED",true,
                    System.nanoTime()-start));
        }
        b32Print("48_independent_2tenant_2waves",observations,totalMeasuredNanos);
        for(var e:all)
            assertExactlyOneHandover(e.getKey(),e.getValue().order(),2);
        for(Context c:contexts) {
            assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("100");
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("148");
            assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
            assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_pickup_handover
                    WHERE tenant_id=?
                    """,Integer.class,c.tenant())).isEqualTo(24);
        }
    }

    /** B3.2 entry: authenticated RECEIVE, CORRECT, WRITE_OFF and TRANSFER
     * overlap a batch of distinct successful handovers in separate tenant contexts.
     * Requires no direct stock-table mutations or untrusted actor identifiers. */
    @Test void qr06bPgB32MixedStockOperationsAndTenantIsolation() throws Exception {
        requirePostgres();
        var a=context();var b=context();
        var contexts=List.of(a,b);
        var orders=new ArrayList<java.util.Map.Entry<Context,B31Target>>();
        for(Context c:contexts) {
            UUID role=assignedRole(c);
            for(var permission:List.of(DomainPermission.INVENTORY_RECEIVE,
                    DomainPermission.INVENTORY_ADJUST))
                roles.grant(c.owner(),c.tenant(),role,permission);
            inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),c.itemOne(),
                    key(),new BigDecimal("100"));
            inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),c.itemTwo(),
                    key(),new BigDecimal("100"));
            for(int i=0;i<8;i++) {
                var o=ready(c,true);
                orders.add(java.util.Map.entry(c,
                        new B31Target(o,pickup.issue(c.buyer(),o.publicRef(),key()),
                                version(c,o))));
            }
        }
        var readyGate=new CountDownLatch(orders.size()+contexts.size()*4);
        var release=new CountDownLatch(1);
        var observations=new ArrayList<B32Operation>();
        long began;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<java.util.concurrent.Future<B32Operation>>();
            for(var e:orders) futures.add(pool.submit(()->{
                readyGate.countDown();
                if(!release.await(25,TimeUnit.SECONDS))
                    throw new IllegalStateException("B32_HANDOVER_BARRIER_TIMEOUT");
                long start=System.nanoTime();
                var c=e.getKey();var target=e.getValue();
                pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                        target.proof().rawProof(),true,target.version(),key());
                return new B32Operation("COMPLETED",true,System.nanoTime()-start);
            }));
            for(Context c:contexts) {
                futures.add(pool.submit(()->{
                    readyGate.countDown();awaitQr06b(release);
                    long start=System.nanoTime();
                    inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                            c.itemOne(),key(),BigDecimal.ONE);
                    return new B32Operation("STOCK_MUTATION",true,System.nanoTime()-start);
                }));
                futures.add(pool.submit(()->{
                    readyGate.countDown();awaitQr06b(release);
                    long start=System.nanoTime();
                    inventoryCommands.correct(c.staff(),c.tenant(),c.warehouse(),
                            c.itemTwo(),key(),BigDecimal.ONE);
                    return new B32Operation("STOCK_MUTATION",true,System.nanoTime()-start);
                }));
                futures.add(pool.submit(()->{
                    readyGate.countDown();awaitQr06b(release);
                    long start=System.nanoTime();
                    inventoryCommands.writeOff(c.staff(),c.tenant(),c.warehouse(),
                            c.itemOne(),key(),BigDecimal.ONE,
                            kz.rm.inventory.InventoryMovementService.WriteOffCause.DAMAGE);
                    return new B32Operation("STOCK_MUTATION",true,System.nanoTime()-start);
                }));
                futures.add(pool.submit(()->{
                    readyGate.countDown();awaitQr06b(release);
                    long start=System.nanoTime();
                    inventoryCommands.transfer(c.staff(),c.tenant(),c.warehouse(),
                            c.otherWarehouse(),c.itemTwo(),BigDecimal.ONE,key());
                    return new B32Operation("STOCK_MUTATION",true,System.nanoTime()-start);
                }));
            }
            assertThat(readyGate.await(25,TimeUnit.SECONDS)).isTrue();
            began=System.nanoTime();
            release.countDown();
            for(var f:futures)observations.add(f.get(120,TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        b32Print("mixed_16orders_8inventory",observations,System.nanoTime()-began);
        assertThat(observations.stream().filter(x->x.category().equals("COMPLETED")).count())
                .isEqualTo(16);
        assertThat(observations.stream().filter(x->x.category().equals("STOCK_MUTATION")).count())
                .isEqualTo(8);
        for(var e:orders) assertExactlyOneHandover(e.getKey(),e.getValue().order(),2);
        for(Context c:contexts) {
            assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("80");
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("96");
            assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
            assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("""
                    SELECT on_hand FROM crm.inv_stock_balance
                    WHERE tenant_id=? AND location_id=? AND item_id=?
                    """,BigDecimal.class,c.tenant(),c.otherWarehouse(),c.itemTwo()))
                    .isEqualByComparingTo("1");
        }
    }


    /** B3.2 time-paced soak: 20 seconds, four independently fulfillable orders
     * offered per second, split evenly across two tenants.
     * This is a correctness/latency baseline at offered 4 orders/s,
     * deliberately NOT a maximum-capacity measurement. */
    @Test void qr06bPgB32TwentySecondTwoTenantPacedSoak() throws Exception {
        requirePostgres();
        Context a=context(),b=context();
        var contexts=List.of(a,b);
        for(Context c:contexts) {
            roles.grant(c.owner(),c.tenant(),assignedRole(c),
                    DomainPermission.INVENTORY_RECEIVE);
            inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                    c.itemOne(),key(),new BigDecimal("300"));
            inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                    c.itemTwo(),key(),new BigDecimal("300"));
        }
        var targets=new ArrayList<java.util.Map.Entry<Context,B31Target>>();
        // 2 orders per tenant x 20 one-second windows.
        for(int second=0;second<20;second++) {
            for(Context c:contexts) for(int k=0;k<2;k++) {
                var o=ready(c,true);
                targets.add(java.util.Map.entry(c,new B31Target(o,
                        pickup.issue(c.buyer(),o.publicRef(),key()),version(c,o))));
            }
        }
        var readyGate=new CountDownLatch(targets.size());
        var release=new CountDownLatch(1);
        var latencies=new ArrayList<Long>();
        long start;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<java.util.concurrent.Future<B32Operation>>();
            for(int i=0;i<targets.size();i++) {
                var entry=targets.get(i);
                int window=i/4;
                futures.add(pool.submit(()->{
                    readyGate.countDown();
                    if(!release.await(35,TimeUnit.SECONDS))
                        throw new IllegalStateException("B32_SOAK_RELEASE_TIMEOUT");
                    long due=System.nanoTime()+TimeUnit.SECONDS.toNanos(window);
                    while(System.nanoTime()<due)
                        java.util.concurrent.locks.LockSupport.parkNanos(
                                Math.min(TimeUnit.MILLISECONDS.toNanos(5),
                                        Math.max(1,due-System.nanoTime())));
                    long begin=System.nanoTime();
                    var c=entry.getKey();var target=entry.getValue();
                    var done=pickup.complete(c.staff(),c.tenant(),c.warehouse(),
                            target.proof().rawProof(),true,target.version(),key());
                    if(!"COMPLETED".equals(done.status()))
                        throw new IllegalStateException("B32_SOAK_WRONG_STATE");
                    return new B32Operation("COMPLETED",true,System.nanoTime()-begin);
                }));
            }
            assertThat(readyGate.await(35,TimeUnit.SECONDS)).isTrue();
            start=System.nanoTime();
            release.countDown();
            for(var f:futures)latencies.add(f.get(90,TimeUnit.SECONDS).nanos());
        } finally {
            release.countDown();
        }
        long wall=System.nanoTime()-start;
        assertThat(latencies).hasSize(80);
        assertThat(wall).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(19));
        for(var e:targets) assertExactlyOneHandover(e.getKey(),e.getValue().order(),2);
        for(Context c:contexts) {
            assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("120");
            assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("200");
            assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
            assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_pickup_handover
                    WHERE tenant_id=?
                    """,Integer.class,c.tenant())).isEqualTo(40);
        }
        System.out.printf(java.util.Locale.ROOT,
                "QR06B_B32_SOAK tenant_count=2 windows=20 offered_orders_per_sec=4 "+
                "completed=80 failures=0 wall_ms=%d completed_per_sec=%.3f "+
                "p50_success_ms=%d p95_success_ms=%d p99_success_ms=%d%n",
                TimeUnit.NANOSECONDS.toMillis(wall),80.0/(wall/1e9),
                b3Percentile(latencies,50),b3Percentile(latencies,95),
                b3Percentile(latencies,99));
    }


    /**
     * B3.3 controlled, 60-second, two-JVM, two-tenant successful-order soak.
     * 40 distinct fulfillable orders per worker; unlike B3.1 all operations
     * are expected to commit and are verified after both processes terminate.
     * Includes concurrent authenticated warehouse moves and DB lock telemetry.
     */
    @org.junit.jupiter.api.Timeout(value=240,unit=TimeUnit.SECONDS)
    @Test void qr06bPgB33TwoJvmSixtySecondSoakWithInterference() throws Exception {
        requirePostgres();
        var contexts=List.of(context(),context());
        var orders=new ArrayList<java.util.Map.Entry<Context,Order>>();
        var dir=java.nio.file.Files.createTempDirectory("qr06b-b33-");
        Process workerA=null,workerB=null;
        var manifests=new ArrayList<java.nio.file.Path>();
        long deadlocksBefore=jdbc.queryForObject("""
                SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()
                """,Long.class);
        try {
            for(int tenantIndex=0;tenantIndex<contexts.size();tenantIndex++) {
                var c=contexts.get(tenantIndex);
                UUID role=assignedRole(c);
                for(var permission:List.of(DomainPermission.INVENTORY_RECEIVE,
                        DomainPermission.INVENTORY_ADJUST))
                    roles.grant(c.owner(),c.tenant(),role,permission);
                inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                        c.itemOne(),key(),new BigDecimal("200"));
                inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                        c.itemTwo(),key(),new BigDecimal("200"));
                var manifest=dir.resolve("tenant-"+tenantIndex+".manifest");
                var lines=new ArrayList<String>();
                for(int i=0;i<40;i++) {
                    var o=ready(c,true);
                    var issue=pickup.issue(c.buyer(),o.publicRef(),key());
                    orders.add(java.util.Map.entry(c,o));
                    lines.add(issue.rawProof()+"|"+version(c,o)+"|"+key());
                }
                java.nio.file.Files.write(manifest,lines);
                try {
                    java.nio.file.Files.setPosixFilePermissions(manifest,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }catch(UnsupportedOperationException ignored) {}
                manifests.add(manifest);
            }
            var gate=dir.resolve("go");
            var readyA=dir.resolve("ready-a");
            var readyB=dir.resolve("ready-b");
            var a=contexts.get(0);var b=contexts.get(1);
            workerA=startQr06bWorker("B33_PACED",a,manifests.get(0).toString(),
                    0,key(),readyA,gate,dir.resolve("a.log"));
            workerB=startQr06bWorker("B33_PACED",b,manifests.get(1).toString(),
                    0,key(),readyB,gate,dir.resolve("b.log"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
            while(!java.nio.file.Files.exists(readyA)||!java.nio.file.Files.exists(readyB)) {
                if(!workerA.isAlive()||!workerB.isAlive())
                    throw new AssertionError("B33 child startup failure");
                if(System.nanoTime()>deadline)
                    throw new AssertionError("B33 readiness timeout");
                Thread.sleep(25);
            }
            long started=System.nanoTime();
            java.nio.file.Files.writeString(gate,"GO");
            // Interleave real inventory operations AFTER JVMs have started
            // and while their paced handovers are executing.
            long lockWaitSamples=0;
            long maxWaiting=0;
            int mutationCount=0;
            for(int second=0;second<60;second++) {
                long due=started+TimeUnit.SECONDS.toNanos(second);
                while(System.nanoTime()<due)
                    java.util.concurrent.locks.LockSupport.parkNanos(
                            Math.min(TimeUnit.MILLISECONDS.toNanos(5),
                                    Math.max(1,due-System.nanoTime())));
                if(second==2||second==14||second==27||second==41) {
                    for(var c:contexts) {
                        if(second==2)
                            inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                                    c.itemOne(),key(),BigDecimal.ONE);
                        if(second==14)
                            inventoryCommands.correct(c.staff(),c.tenant(),c.warehouse(),
                                    c.itemOne(),key(),BigDecimal.ONE);
                        if(second==27)
                            inventoryCommands.writeOff(c.staff(),c.tenant(),c.warehouse(),
                                    c.itemOne(),key(),BigDecimal.ONE,
                                    kz.rm.inventory.InventoryMovementService.WriteOffCause.DAMAGE);
                        if(second==41)
                            inventoryCommands.transfer(c.staff(),c.tenant(),
                                    c.warehouse(),c.otherWarehouse(),
                                    c.itemTwo(),BigDecimal.ONE,key());
                        mutationCount++;
                    }
                }
                Integer waiting=jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname=current_database()
                          AND wait_event_type='Lock'
                          AND pid<>pg_backend_pid()
                        """,Integer.class);
                lockWaitSamples+=waiting==null?0:waiting;
                maxWaiting=Math.max(maxWaiting,waiting==null?0:waiting);
            }
            assertThat(waitQr06bProcess(workerA,dir.resolve("a.log")))
                    .withFailMessage(()->qr06bDiagnostics(dir.resolve("a.log"),"REDACTED"))
                    .isZero();
            assertThat(waitQr06bProcess(workerB,dir.resolve("b.log")))
                    .withFailMessage(()->qr06bDiagnostics(dir.resolve("b.log"),"REDACTED"))
                    .isZero();
            // Export per-process success-only percentiles into immutable CI output.
            for(String logName:List.of("a.log","b.log")) {
                var childLog=java.nio.file.Files.readAllLines(dir.resolve(logName));
                var metrics=childLog.stream()
                        .filter(line->line.contains("QR06B_B33_CHILD ")).toList();
                assertThat(metrics).hasSize(1);
                for(String metric:metrics)
                    System.out.println(metric.substring(metric.indexOf("QR06B_B33_CHILD ")));
            }
            long elapsed=System.nanoTime()-started;
            long deadlocksAfter=jdbc.queryForObject("""
                    SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()
                    """,Long.class);
            assertThat(deadlocksAfter-deadlocksBefore).isZero();
            assertThat(mutationCount).isEqualTo(8);
            for(var e:orders)assertExactlyOneHandover(e.getKey(),e.getValue(),2);
            for(var c:contexts) {
                assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("21");
                assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("99");
                assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
                assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
                assertThat(jdbc.queryForObject("""
                        SELECT on_hand FROM crm.inv_stock_balance
                        WHERE tenant_id=? AND location_id=? AND item_id=?
                        """,BigDecimal.class,c.tenant(),c.otherWarehouse(),c.itemTwo()))
                        .isEqualByComparingTo("1");
            }
            System.out.printf(java.util.Locale.ROOT,
                    "QR06B_B33_SOAK duration_ms=%d jvms=2 tenants=2 completed=80 "+
                    "failures=0 stock_mutations=%d deadlocks=0 "+
                    "lock_wait_sample_sum=%d max_waiting_sessions=%d "+
                    "completed_per_sec=%.3f%n",
                    TimeUnit.NANOSECONDS.toMillis(elapsed),mutationCount,
                    lockWaitSamples,maxWaiting,80.0/(elapsed/1e9));
        } finally {
            if(workerA!=null&&workerA.isAlive())workerA.destroyForcibly();
            if(workerB!=null&&workerB.isAlive())workerB.destroyForcibly();
            for(var manifest:manifests)java.nio.file.Files.deleteIfExists(manifest);
            for(String path:List.of("go","ready-a","ready-b","a.log","b.log"))
                java.nio.file.Files.deleteIfExists(dir.resolve(path));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }


    /**
     * B3.4 step-load and post-peak recovery baseline with TWO JVM processes.
     * 40 separate successful orders per tenant; preallocated low/peak/recovery
     * stages prevent business rejections from masquerading as capacity.
     * Profiles do not assert saturation unless actual pool contention occurs.
     */
    @org.junit.jupiter.api.Timeout(value=750,unit=TimeUnit.SECONDS)
    @Test void qr06bPgB34TwoJvmSteppedLoadRecoversAndPreservesFairness() throws Exception {
        requirePostgres();
        int duration=Integer.getInteger("rm.qr06b.b34.durationSeconds",60);
        if(duration!=60 && duration!=300)throw new IllegalArgumentException("B34 duration 60 or 300");
        int totalOrders=duration==300?400:80;
        int extraStock=duration==300?1200:300;
        var contexts=List.of(context(),context());
        var orders=new ArrayList<java.util.Map.Entry<Context,Order>>();
        var dir=java.nio.file.Files.createTempDirectory("qr06b-b34-");
        var manifests=new ArrayList<java.nio.file.Path>();
        Process first=null,second=null;
        long deadlocksBefore=jdbc.queryForObject("""
                SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()
                """,Long.class);
        try {
            for(int tenantIndex=0;tenantIndex<2;tenantIndex++) {
                var c=contexts.get(tenantIndex);
                roles.grant(c.owner(),c.tenant(),assignedRole(c),
                        DomainPermission.INVENTORY_RECEIVE);
                inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                        c.itemOne(),key(),BigDecimal.valueOf(extraStock));
                inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                        c.itemTwo(),key(),BigDecimal.valueOf(extraStock));
                int tenantOrders=duration==300
                        ?(tenantIndex==0?240:160):(tenantIndex==0?50:30);
                int low=tenantOrders/4,peak=tenantOrders/2;
                var lines=new ArrayList<String>();
                for(int i=0;i<tenantOrders;i++) {
                    var o=ready(c,true);
                    var issue=pickup.issue(c.buyer(),o.publicRef(),key());
                    orders.add(java.util.Map.entry(c,o));
                    String stage=i<low?"LOW":i<low+peak?"PEAK":"RECOVERY";
                    long due=duration==60
                            ?(i<low?i*1200L:i<low+peak?15000L:35000L+(i-low-peak)*1500L)
                            :(i<low?i*1400L:i<low+peak?90000L:120000L+(i-low-peak)*2800L);
                    lines.add(issue.rawProof()+"|"+version(c,o)+"|"+key()+"|"+due+"|"+stage);
                }
                var manifest=dir.resolve("tenant-"+tenantIndex+".manifest");
                java.nio.file.Files.write(manifest,lines);
                try {
                    java.nio.file.Files.setPosixFilePermissions(manifest,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }catch(UnsupportedOperationException ignored){}
                manifests.add(manifest);
            }
            var gate=dir.resolve("go");
            var readyA=dir.resolve("ready-a");
            var readyB=dir.resolve("ready-b");
            first=startQr06bWorker("B34_STEPPED",contexts.get(0),
                    manifests.get(0).toString(),0,key(),
                    readyA,gate,dir.resolve("a.log"));
            second=startQr06bWorker("B34_STEPPED",contexts.get(1),
                    manifests.get(1).toString(),0,key(),
                    readyB,gate,dir.resolve("b.log"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
            while(!java.nio.file.Files.exists(readyA)||!java.nio.file.Files.exists(readyB)){
                if(!first.isAlive()||!second.isAlive())
                    throw new AssertionError("B34 child JVM exited during startup");
                if(System.nanoTime()>deadline)
                    throw new AssertionError("B34 worker startup barrier timeout");
                Thread.sleep(25);
            }
            var sampleRunning=new java.util.concurrent.atomic.AtomicBoolean(true);
            var lockWaitTotal=new java.util.concurrent.atomic.AtomicLong();
            var maxLockWaiters=new java.util.concurrent.atomic.AtomicInteger();
            var lockSamples=new java.util.concurrent.atomic.AtomicLong();
            var monitorFailures=new java.util.concurrent.atomic.AtomicInteger();
            Thread monitor=Thread.ofVirtual().start(()->{
                while(sampleRunning.get()){
                    try {
                        int waiting=jdbc.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE datname=current_database()
                                  AND wait_event_type='Lock'
                                  AND pid<>pg_backend_pid()
                                """,Integer.class);
                        lockSamples.incrementAndGet();
                        lockWaitTotal.addAndGet(waiting);
                        maxLockWaiters.accumulateAndGet(waiting,Math::max);
                    } catch(RuntimeException unexpected) {
                        monitorFailures.incrementAndGet();
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(
                            TimeUnit.MILLISECONDS.toNanos(100));
                }
            });
            int exitFirst,exitSecond;
            try {
                java.nio.file.Files.writeString(gate,"GO");
                exitFirst=waitQr06bProcess(first,dir.resolve("a.log"),duration+120);
                exitSecond=waitQr06bProcess(second,dir.resolve("b.log"),duration+120);
            }finally {
                sampleRunning.set(false);
                monitor.join(5000);
            }
            assertThat(monitorFailures.get()).isZero();
            assertThat(lockSamples.get()).isGreaterThan(0);
            System.out.printf(java.util.Locale.ROOT,
                    "QR06B_B34_DB samples=%d lock_waiter_sample_sum=%d max_lock_waiters=%d%n",
                    lockSamples.get(),lockWaitTotal.get(),maxLockWaiters.get());
            assertThat(exitFirst).isZero();
            assertThat(exitSecond).isZero();
            for(String logName:List.of("a.log","b.log")) {
                var text=java.nio.file.Files.readString(dir.resolve(logName));
                var lines=text.lines().filter(x->x.contains("QR06B_B34_"))
                        .map(x->x.substring(x.indexOf("QR06B_B34_"))).toList();
                assertThat(lines).hasSize(4);
                assertThat(lines.stream().filter(x->x.contains("LOW:COMPLETED")).count())
                        .isEqualTo(1);
                assertThat(lines.stream().filter(x->x.contains("PEAK:COMPLETED")).count())
                        .isEqualTo(1);
                assertThat(lines.stream().filter(x->x.contains("RECOVERY:COMPLETED")).count())
                        .isEqualTo(1);
                for(String metric:lines)System.out.println(metric);
            }
            for(var entry:orders)assertExactlyOneHandover(entry.getKey(),entry.getValue(),2);
            for(var c:contexts) {
                int tenantIndex=contexts.indexOf(c);
                int tenantOrders=duration==300
                        ?(tenantIndex==0?240:160):(tenantIndex==0?50:30);
                assertThat(stock(c,c.itemOne(),"on_hand"))
                        .isEqualByComparingTo(BigDecimal.valueOf(20L+extraStock-5L*tenantOrders));
                assertThat(stock(c,c.itemTwo(),"on_hand"))
                        .isEqualByComparingTo(BigDecimal.valueOf(20L+extraStock-3L*tenantOrders));
                assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
                assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM crm.sales_pickup_handover WHERE tenant_id=?
                        """,Integer.class,c.tenant())).isEqualTo(tenantOrders);
            }
            long deadlocksAfter=jdbc.queryForObject("""
                    SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()
                    """,Long.class);
            assertThat(deadlocksAfter-deadlocksBefore).isZero();
            System.out.printf("QR06B_B34_RESULT duration_profile_s=%d jvms=2 tenants=2 completed=%d deadlocks=0 unexpected_failures=0%n",
                    duration,totalOrders);
        }finally {
            if(first!=null&&first.isAlive())first.destroyForcibly();
            if(second!=null&&second.isAlive())second.destroyForcibly();
            for(var path:manifests)java.nio.file.Files.deleteIfExists(path);
            for(String name:List.of("go","ready-a","ready-b","a.log","b.log"))
                java.nio.file.Files.deleteIfExists(dir.resolve(name));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }


    /**
     * B3.5 ENTRY: time-paced four-phase successful-throughput baseline.
     * Each of the two JVMs owns 40 unique orders in a different tenant.
     * Admission checks phase-by-outcome telemetry, no hidden technical errors,
     * durable ledger correctness and recovery-phase completion.
     * This is not the full 120-300 seconds PER LEVEL capacity qualification.
     */
    @org.junit.jupiter.api.Timeout(value=1100,unit=TimeUnit.SECONDS)
    @Test void qr06bPgB35TwoJvmRateStepsAndRecoveryEntry() throws Exception {
        requirePostgres();
        int levelSeconds=Integer.getInteger("rm.qr06b.b35.levelSeconds",20);
        if(levelSeconds!=20&&levelSeconds!=120)
            throw new IllegalArgumentException("B35 stages support 20/120 seconds");
        boolean extended=levelSeconds==120;
        var tenants=List.of(context(),context());
        var all=new ArrayList<java.util.Map.Entry<Context,Order>>();
        var folder=java.nio.file.Files.createTempDirectory("qr06b-b35-");
        Process left=null,right=null;
        var manifests=new ArrayList<java.nio.file.Path>();
        long deadlocksBefore=jdbc.queryForObject("""
                SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()
                """,Long.class);
        try {
            for(int tenantIndex=0;tenantIndex<2;tenantIndex++){
                var c=tenants.get(tenantIndex);
                roles.grant(c.owner(),c.tenant(),assignedRole(c),
                        DomainPermission.INVENTORY_RECEIVE);
                inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                        c.itemOne(),key(),BigDecimal.valueOf(extended?(tenantIndex==0?1400:1000):300));
                inventoryCommands.receive(c.staff(),c.tenant(),c.warehouse(),
                        c.itemTwo(),key(),BigDecimal.valueOf(extended?(tenantIndex==0?1400:1000):300));
                var rows=new ArrayList<String>();
                int[] phaseCounts=extended
                        ?(tenantIndex==0?new int[]{30,60,120,30}:new int[]{20,40,80,20})
                        :new int[]{5,10,20,5};
                int ordersInTenant=java.util.Arrays.stream(phaseCounts).sum();
                for(int i=0;i<ordersInTenant;i++){
                    var o=ready(c,true);
                    // QR proof is issued by worker at its scheduled request time.
                    all.add(java.util.Map.entry(c,o));
                    String[] phases={"LOW","RAMP","STRESS","RECOVERY"};
                    int stage=0,prior=0;
                    while(stage<phaseCounts.length-1&&i>=prior+phaseCounts[stage]){
                        prior+=phaseCounts[stage];
                        stage++;
                    }
                    String phase=phases[stage];
                    long dueMs=stage*(levelSeconds*1000L)+
                            (i-prior)*(levelSeconds*1000L)/phaseCounts[stage];
                    rows.add("-"+"|"+version(c,o)+"|"+key()+"|"+
                            dueMs+"|"+phase+"|"+c.buyer().userId()+"|"+o.publicRef());
                }
                var manifest=folder.resolve("tenant-"+tenantIndex+".manifest");
                java.nio.file.Files.write(manifest,rows);
                try{
                    java.nio.file.Files.setPosixFilePermissions(manifest,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }catch(UnsupportedOperationException ignored){}
                manifests.add(manifest);
            }
            var readyA=folder.resolve("ready-a");
            var readyB=folder.resolve("ready-b");
            var release=folder.resolve("release");
            left=startQr06bWorker("B35_JIT",tenants.get(0),
                    manifests.get(0).toString(),0,key(),
                    readyA,release,folder.resolve("left.log"));
            right=startQr06bWorker("B35_JIT",tenants.get(1),
                    manifests.get(1).toString(),0,key(),
                    readyB,release,folder.resolve("right.log"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
            while(!java.nio.file.Files.exists(readyA)||!java.nio.file.Files.exists(readyB)){
                assertThat(left.isAlive()&&right.isAlive()).isTrue();
                if(System.nanoTime()>deadline)throw new AssertionError("B35 readiness timeout");
                Thread.sleep(20);
            }
            // The test must not preissue any time-limited pickup credential.
            // Each worker issues its own buyer proof at the scheduled arrival.
            for(var c:tenants) {
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM crm.sales_pickup_credential WHERE tenant_id=?
                        """,Integer.class,c.tenant())).isZero();
            }
            java.nio.file.Files.writeString(release,"START");
            assertThat(waitQr06bProcess(left,folder.resolve("left.log"),levelSeconds*4+150)).isZero();
            assertThat(waitQr06bProcess(right,folder.resolve("right.log"),levelSeconds*4+150)).isZero();
            for(String filename:List.of("left.log","right.log")){
                String log=java.nio.file.Files.readString(folder.resolve(filename));
                var metrics=log.lines().filter(x->x.contains("QR06B_B35_"))
                        .map(x->x.substring(x.indexOf("QR06B_B35_"))).toList();
                assertThat(metrics.stream().filter(x->x.contains("_CHILD ")).count()).isEqualTo(8);
                assertThat(metrics.stream().filter(x->x.contains("_RATE ")).count()).isEqualTo(4);
                assertThat(metrics.stream().filter(x->x.contains("_POOL ")).count()).isEqualTo(1);
                assertThat(metrics.stream().filter(x->x.contains("ISSUE_PLUS_COMPLETE")).count())
                        .isEqualTo(4);
                for(String phase:List.of("LOW","RAMP","STRESS","RECOVERY")){
                    assertThat(metrics.stream().anyMatch(x->
                            x.contains("phase_outcome="+phase+":COMPLETED"))).isTrue();
                }
                assertThat(metrics.stream().anyMatch(x->x.contains("technical_failures=0")))
                        .isTrue();
                metrics.forEach(System.out::println);
            }
            assertThat(all).hasSize(extended?400:80);
            for(var entry:all)assertExactlyOneHandover(entry.getKey(),entry.getValue(),2);
            for(var c:tenants){
                int tenantIndex=tenants.indexOf(c);
                int tenantOrders=extended?(tenantIndex==0?240:160):40;
                long added=extended?(tenantIndex==0?1400:1000):300;
                assertThat(stock(c,c.itemOne(),"on_hand"))
                        .isEqualByComparingTo(BigDecimal.valueOf(20+added-5L*tenantOrders));
                assertThat(stock(c,c.itemTwo(),"on_hand"))
                        .isEqualByComparingTo(BigDecimal.valueOf(20+added-3L*tenantOrders));
                assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("0");
                assertThat(stock(c,c.itemTwo(),"reserved")).isEqualByComparingTo("0");
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM crm.sales_pickup_handover WHERE tenant_id=?
                        """,Integer.class,c.tenant())).isEqualTo(tenantOrders);
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM crm.sales_pickup_credential
                        WHERE tenant_id=? AND state='CONSUMED'
                        """,Integer.class,c.tenant())).isEqualTo(tenantOrders);
            }
            long deadlocksAfter=jdbc.queryForObject("""
                    SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()
                    """,Long.class);
            assertThat(deadlocksAfter-deadlocksBefore).isZero();
            System.out.printf("QR06B_B35_RESULT stage_seconds=%d phases=4 jvms=2 tenants=2 completed=%d recovery_completed=%d technical_failures=0 deadlocks=0%n",
                    levelSeconds,extended?400:80,extended?50:10);
        }finally{
            if(left!=null&&left.isAlive())left.destroyForcibly();
            if(right!=null&&right.isAlive())right.destroyForcibly();
            for(var path:manifests)java.nio.file.Files.deleteIfExists(path);
            for(String name:List.of("ready-a","ready-b","release","left.log","right.log"))
                java.nio.file.Files.deleteIfExists(folder.resolve(name));
            java.nio.file.Files.deleteIfExists(folder);
        }
    }


    /** QR-07/R0 PostgreSQL: original fulfillment is immutable, a partial
     * return is only a CLAIM and must not increase inventory. */
    @Test void qr07R0ReturnPartialReplayAndOverReturnProtectsReceipt() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,true);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        pickup.complete(c.staff(),c.tenant(),c.warehouse(),issued.rawProof(),
                true,version(c,o),key());
        UUID source=jdbc.queryForObject("""
                SELECT id FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=? AND quantity=5
                """,UUID.class,c.tenant(),o.id());
        String requestKey=key();
        var first=returns.request(c.buyer(),o.publicRef(),source,2,
                "DAMAGED","UAT_1",requestKey);
        assertThat(first.state()).isEqualTo("REQUESTED");
        assertThat(returns.request(c.buyer(),o.publicRef(),source,2,
                "DAMAGED","UAT_1",requestKey).requestId())
                .isEqualTo(first.requestId());
        assertThatThrownBy(()->returns.request(c.buyer(),o.publicRef(),source,3,
                "DAMAGED","UAT_1",requestKey))
                .hasMessageContaining("RETURN_IDEMPOTENCY_CONFLICT");
        returns.request(c.buyer(),o.publicRef(),source,3,
                "DAMAGED","UAT_1",key());
        assertThatThrownBy(()->returns.request(c.buyer(),o.publicRef(),source,1,
                "DAMAGED","UAT_1",key()))
                .hasMessageContaining("RETURN_QUANTITY_EXCEEDS_FULFILLED");
        assertExactlyOneHandover(c,o,2);
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo("17");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_request
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,c.tenant(),o.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND event_type='REQUESTED'
                """,Integer.class,c.tenant())).isEqualTo(2);
    }

    @Test void qr07R0RejectsUncompletedOrForeignBuyerBeforeAnyReturnWrite() throws Exception {
        requirePostgres();
        var c=context();var other=context();var o=ready(c,true);
        assertThatThrownBy(()->returns.request(c.buyer(),o.publicRef(),
                o.lines().getFirst(),1,"DAMAGED","UAT_1",key()))
                .hasMessageContaining("RETURN_SOURCE_NOT_COMPLETED");
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        pickup.complete(c.staff(),c.tenant(),c.warehouse(),issued.rawProof(),
                true,version(c,o),key());
        assertThatThrownBy(()->returns.request(other.buyer(),o.publicRef(),
                o.lines().getFirst(),1,"DAMAGED","UAT_1",key()))
                .isInstanceOfAny(SecurityException.class,IllegalStateException.class);
        assertThatThrownBy(()->returns.request(c.buyer(),o.publicRef(),
                UUID.randomUUID(),1,"DAMAGED","UAT_1",key()))
                .hasMessageContaining("RETURN_LINE_NOT_FULFILLED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_request WHERE tenant_id=?
                """,Integer.class,c.tenant())).isZero();
    }

    @Test void qr07R0ConcurrentPartialClaimsCannotExceedFulfilledQuantity() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        pickup.complete(c.staff(),c.tenant(),c.warehouse(),issued.rawProof(),
                true,version(c,o),key());
        var barrier=new CountDownLatch(2);
        var go=new CountDownLatch(1);
        int accepted=0,rejected=0;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{
                barrier.countDown();
                if(!go.await(15,TimeUnit.SECONDS))throw new IllegalStateException("BARRIER");
                try {
                    returns.request(c.buyer(),o.publicRef(),o.lines().getFirst(),
                            4,"DAMAGED","UAT_1",key());
                    return true;
                }catch(IllegalStateException expected){
                    if(!expected.getMessage().contains("RETURN_QUANTITY_EXCEEDS_FULFILLED"))
                        throw expected;
                    return false;
                }
            }));
            assertThat(barrier.await(15,TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for(var future:futures)if(future.get(60,TimeUnit.SECONDS))accepted++;else rejected++;
        }finally{go.countDown();}
        assertThat(accepted).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(rl.quantity),0)
                FROM crm.sales_return_line rl
                WHERE tenant_id=? AND order_id=? AND order_line_id=?
                """,Integer.class,c.tenant(),o.id(),o.lines().getFirst())).isEqualTo(4);
        assertExactlyOneHandover(c,o,1);
    }


    private SalesReturnRequestService.Created qr07R1Claim(Context c,Order o,int qty) {
        var issued=pickup.issue(c.buyer(),o.publicRef(),key());
        pickup.complete(c.staff(),c.tenant(),c.warehouse(),issued.rawProof(),
                true,version(c,o),key());
        return returns.request(c.buyer(),o.publicRef(),o.lines().getFirst(),qty,
                "DAMAGED","UAT_1",key());
    }

    @Test void qr07R1ReviewApproveAndImmutableDecisionReplay() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R1Claim(c,o,2);
        UUID role=assignedRole(c);
        roles.grant(c.owner(),c.tenant(),role,DomainPermission.SALES_RETURN_REVIEW);
        String reviewKey=key(),approveKey=key();
        var reviewed=returnReview.startReview(c.staff(),c.tenant(),claim.requestId(),
                0,"UAT_1",reviewKey);
        assertThat(reviewed.state()).isEqualTo(SalesReturnState.UNDER_REVIEW);
        assertThat(reviewed.rowVersion()).isEqualTo(1);
        var approval=returnReview.approve(c.staff(),c.tenant(),claim.requestId(),
                1,"UAT_1",approveKey);
        assertThat(approval.state()).isEqualTo(SalesReturnState.APPROVED);
        assertThat(approval.rowVersion()).isEqualTo(2);
        assertThat(returnReview.approve(c.staff(),c.tenant(),claim.requestId(),
                1,"UAT_1",approveKey).decisionEventId()).isEqualTo(approval.decisionEventId());
        assertThat(returnReview.startReview(c.staff(),c.tenant(),claim.requestId(),
                0,"UAT_1",reviewKey).decisionEventId()).isEqualTo(reviewed.decisionEventId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT row_version FROM crm.sales_return_request
                WHERE tenant_id=? AND id=?
                """,Long.class,c.tenant(),claim.requestId())).isEqualTo(2);
        assertExactlyOneHandover(c,o,1);
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("15");
    }

    @Test void qr07R1RejectReleasesAllocatedPartialReturnCapacity() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R1Claim(c,o,5);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.SALES_RETURN_REVIEW);
        returnReview.startReview(c.staff(),c.tenant(),claim.requestId(),0,"UAT_1",key());
        var denied=returnReview.reject(c.staff(),c.tenant(),claim.requestId(),1,"UAT_1",key());
        assertThat(denied.state()).isEqualTo(SalesReturnState.REJECTED);
        var fresh=returns.request(c.buyer(),o.publicRef(),o.lines().getFirst(),5,
                "DAMAGED","UAT_1",key());
        assertThat(fresh.requestId()).isNotEqualTo(claim.requestId());
        assertExactlyOneHandover(c,o,1);
    }

    @Test void qr07R1FailsClosedWithoutGrantOrForCrossTenantReviewer() throws Exception {
        requirePostgres();
        var c=context();var other=context();var o=ready(c,false);
        var claim=qr07R1Claim(c,o,2);
        assertThatThrownBy(()->returnReview.startReview(c.staff(),c.tenant(),
                claim.requestId(),0,"UAT_1",key())).isInstanceOf(SecurityException.class);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.SALES_RETURN_REVIEW);
        assertThatThrownBy(()->returnReview.startReview(other.staff(),c.tenant(),
                claim.requestId(),0,"UAT_1",key())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->returnReview.startReview(c.staff(),other.tenant(),
                claim.requestId(),0,"UAT_1",key())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->returnReview.startReview(c.buyer(),c.tenant(),
                claim.requestId(),0,"UAT_1",key())).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),claim.requestId())).isEqualTo("REQUESTED");
    }

    @Test void qr07R1RejectsStalePolicyWrongVersionAndIllegalTransition() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R1Claim(c,o,1);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.SALES_RETURN_REVIEW);
        assertThatThrownBy(()->returnReview.approve(c.staff(),c.tenant(),
                claim.requestId(),0,"UAT_1",key()))
                .hasMessageContaining("RETURN_REVIEW_TRANSITION_FORBIDDEN");
        assertThatThrownBy(()->returnReview.startReview(c.staff(),c.tenant(),
                claim.requestId(),0,"UAT_2",key()))
                .hasMessageContaining("RETURN_POLICY_VERSION_CONFLICT");
        var first=returnReview.startReview(c.staff(),c.tenant(),claim.requestId(),
                0,"UAT_1",key());
        assertThat(first.rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->returnReview.approve(c.staff(),c.tenant(),
                claim.requestId(),0,"UAT_1",key()))
                .hasMessageContaining("RETURN_REVIEW_VERSION_CONFLICT");
        assertThatThrownBy(()->returnReview.approve(c.staff(),c.tenant(),
                claim.requestId(),1,"UAT_2",key()))
                .hasMessageContaining("RETURN_POLICY_VERSION_CONFLICT");
        assertThatThrownBy(()->returnReview.approve(c.staff(),c.tenant(),
                claim.requestId(),1,"UAT_1","bad key"))
                .hasMessageContaining("RETURN_REVIEW_INPUT_INVALID");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),claim.requestId())).isEqualTo("UNDER_REVIEW");
    }

    @Test void qr07R1ConcurrentApproveAndRejectCommitExactlyOneDecision() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R1Claim(c,o,2);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.SALES_RETURN_REVIEW);
        returnReview.startReview(c.staff(),c.tenant(),claim.requestId(),0,"UAT_1",key());
        var barrier=new CountDownLatch(2);
        var go=new CountDownLatch(1);
        int success=0,conflict=0;
        try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int n=0;n<2;n++){
                final boolean approval=n==0;
                futures.add(pool.submit(()->{
                    barrier.countDown();
                    if(!go.await(15,TimeUnit.SECONDS))throw new IllegalStateException("BARRIER");
                    try {
                        if(approval) returnReview.approve(c.staff(),c.tenant(),
                                claim.requestId(),1,"UAT_1",key());
                        else returnReview.reject(c.staff(),c.tenant(),
                                claim.requestId(),1,"UAT_1",key());
                        return true;
                    }catch(IllegalStateException e){
                        if(!e.getMessage().contains("RETURN_REVIEW_VERSION_CONFLICT"))
                            throw e;
                        return false;
                    }
                }));
            }
            assertThat(barrier.await(15,TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for(var f:futures)if(f.get(90,TimeUnit.SECONDS))success++;else conflict++;
        }finally{go.countDown();}
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT row_version FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,Long.class,c.tenant(),claim.requestId())).isEqualTo(2);
        assertExactlyOneHandover(c,o,1);
    }


    @Test void qr07R1RevokedReviewerGrantImmediatelyDeniesFurtherTransitions() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R1Claim(c,o,1);
        UUID role=assignedRole(c);
        roles.grant(c.owner(),c.tenant(),role,DomainPermission.SALES_RETURN_REVIEW);
        var reviewed=returnReview.startReview(c.staff(),c.tenant(),
                claim.requestId(),0,"UAT_1",key());
        assertThat(reviewed.rowVersion()).isEqualTo(1);
        roles.revoke(c.owner(),c.tenant(),role,DomainPermission.SALES_RETURN_REVIEW);
        assertThatThrownBy(()->returnReview.approve(c.staff(),c.tenant(),
                claim.requestId(),1,"UAT_1",key()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),claim.requestId())).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(2);
    }

    @Test void qr07R1DecisionEventsRejectSqlTamperingAndConflictingReplays() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R1Claim(c,o,2);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.SALES_RETURN_REVIEW);
        String key=key();
        var reviewed=returnReview.startReview(c.staff(),c.tenant(),
                claim.requestId(),0,"UAT_1",key);
        assertThatThrownBy(()->returnReview.startReview(c.staff(),c.tenant(),
                claim.requestId(),0,"UAT_1",key+"X"))
                .hasMessageContaining("RETURN_REVIEW_VERSION_CONFLICT");
        assertThatThrownBy(()->returnReview.approve(c.staff(),c.tenant(),
                claim.requestId(),0,"UAT_1",key))
                .hasMessageContaining("RETURN_DECISION_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.sales_return_event SET event_type='REJECTED'
                WHERE tenant_id=? AND id=?
                """,c.tenant(),reviewed.decisionEventId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.sales_return_event
                WHERE tenant_id=? AND id=?
                """,c.tenant(),reviewed.decisionEventId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT event_type FROM crm.sales_return_event
                WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),reviewed.decisionEventId()))
                .isEqualTo("UNDER_REVIEW");
    }


    private SalesReturnRequestService.Created qr07R2Approved(Context c,Order o,int quantity){
        var claim=qr07R1Claim(c,o,quantity);
        UUID role=assignedRole(c);
        for(var permission:new DomainPermission[]{
                DomainPermission.SALES_RETURN_REVIEW,
                DomainPermission.SALES_RETURN_RECEIVE,
                DomainPermission.SALES_RETURN_INSPECT,
                DomainPermission.SALES_RETURN_POST,
                DomainPermission.INVENTORY_RECEIVE})
            roles.grant(c.owner(),c.tenant(),role,permission);
        returnReview.startReview(c.staff(),c.tenant(),claim.requestId(),0,"UAT_1",key());
        returnReview.approve(c.staff(),c.tenant(),claim.requestId(),1,"UAT_1",key());
        return claim;
    }

    @Test void qr07R2RestockPostsOneLinkedStockMovementOnlyAfterInspection() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R2Approved(c,o,2);
        UUID item=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        BigDecimal start=stock(c,item,"on_hand");
        var received=returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),2,"SCAN_001",key());
        assertThat(received.state()).isEqualTo("RECEIVED");
        assertThat(received.version()).isEqualTo(4);
        assertThat(stock(c,item,"on_hand")).isEqualByComparingTo(start);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND return_id=? AND event_type IN('AWAITING_RECEIPT','RECEIVED')
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(2);
        var inspected=returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),4,SalesReturnPhysicalService.Disposition.RESTOCK,
                "INSPECT_001",key());
        assertThat(inspected.state()).isEqualTo("INSPECTED");
        assertThat(stock(c,item,"on_hand")).isEqualByComparingTo(start);
        String postingKey=key();
        var posted=returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),5,postingKey);
        assertThat(posted.state()).isEqualTo("RESTOCKED");
        assertThat(posted.version()).isEqualTo(6);
        assertThat(returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),5,postingKey).evidenceId()).isEqualTo(posted.evidenceId());
        assertThat(stock(c,item,"on_hand"))
                .isEqualByComparingTo(start.add(BigDecimal.valueOf(2)));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_posting p
                JOIN crm.inv_stock_movement m
                  ON m.tenant_id=p.tenant_id AND m.id=p.movement_id
                WHERE p.tenant_id=? AND p.return_id=?
                  AND m.delta_quantity=2 AND m.item_id=?
                """,Integer.class,c.tenant(),claim.requestId(),item)).isEqualTo(1);
        assertExactlyOneHandover(c,o,1);
    }

    @Test void qr07R2WriteOffAndQuarantineNeverIncreaseSellableStock() throws Exception {
        requirePostgres();
        var c=context();var first=ready(c,false);
        var claim=qr07R2Approved(c,first,1);
        BigDecimal a=stock(c,c.itemOne(),"on_hand"),b=stock(c,c.itemTwo(),"on_hand");
        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),claim.requestId(),
                2,"SCAN_002",key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),claim.requestId(),
                4,SalesReturnPhysicalService.Disposition.WRITE_OFF,"INSPECT_002",key());
        var posted=returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),5,key());
        assertThat(posted.state()).isEqualTo("DISPOSED");
        assertThat(jdbc.queryForObject("""
                SELECT movement_id FROM crm.sales_return_posting
                WHERE tenant_id=? AND return_id=?
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,
                c.tenant(),claim.requestId())).isNull();
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo(a);
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo(b);
        var otherOrder=ready(c,false);
        var other=qr07R2Approved(c,otherOrder,1);
        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),other.requestId(),
                2,"SCAN_003",key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),other.requestId(),
                4,SalesReturnPhysicalService.Disposition.QUARANTINE,"INSPECT_003",key());
        assertThatThrownBy(()->returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                other.requestId(),5,key()))
                .hasMessageContaining("RETURN_QUARANTINE_NOT_POSTABLE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_posting
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),other.requestId())).isZero();
    }

    @Test void qr07R2DeniedScopesAndPrematurePostingHaveNoSideEffects() throws Exception {
        requirePostgres();
        var c=context();var foreign=context();var o=ready(c,false);
        var claim=qr07R2Approved(c,o,1);
        assertThatThrownBy(()->returnPhysical.receive(foreign.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),2,"SCAN_004",key()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->returnPhysical.post(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),2,key()))
                .hasMessageContaining("RETURN_PHYSICAL_EVIDENCE_REQUIRED");
        scopes.revoke(c.owner(),c.tenant(),c.warehouse(),c.staff().userId());
        assertThatThrownBy(()->returnPhysical.receive(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),2,"SCAN_005",key()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),claim.requestId())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_reception WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isZero();
    }

    @Test void qr07R2ConcurrentScansCanCreateOnlyOnePhysicalReception() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R2Approved(c,o,2);
        var barrier=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        int pass=0,reject=0;
        try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++) {
                String requestKey=key();
                futures.add(pool.submit(()->{
                    barrier.countDown();
                    if(!start.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("RECEPTION_START_TIMEOUT");
                    try {
                        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                                claim.requestId(),2,"SCAN_RACE",requestKey);
                        return true;
                    }catch(IllegalStateException e){
                        if(!e.getMessage().contains("RETURN_RECEPTION_REPLAY_CONFLICT"))
                            throw e;
                        return false;
                    }
                }));
            }
            assertThat(barrier.await(15,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for(var future:futures)if(future.get(60,TimeUnit.SECONDS))pass++;else reject++;
        }finally{start.countDown();}
        assertThat(pass).isEqualTo(1);
        assertThat(reject).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_reception
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),claim.requestId())).isEqualTo("RECEIVED");
        assertExactlyOneHandover(c,o,1);
    }

}
