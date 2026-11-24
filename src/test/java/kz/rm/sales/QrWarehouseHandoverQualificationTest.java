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
    @Autowired SalesReturnReceptionProofService returnProof;
    @Autowired SalesReturnQuarantineResolutionService quarantineResolution;
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
        qualifyPickScopeBothCommitOrders();
    }

    private void qualifyPickScopeBothCommitOrders() throws Exception {
        try(var issuer=new JourneySignedActorFixture()){
            for(boolean revokeFirst:List.of(false,true)){
                var c=context();var o=order(c,false);
                var owner=issuer.resolve(jdbc,users,c.owner().userId());
                var staff=issuer.resolve(jdbc,users,c.staff().userId());
                sales.confirm(staff.userId(),c.tenant(),o.id(),c.warehouse());
                prep.startPreparing(staff,c.tenant(),o.id(),c.warehouse(),version(c,o),key());
                long version=version(c,o);
                var before=JourneySqlEvidence.orderAndStock(jdbc,c.tenant(),o.id(),c.warehouse(),c.itemOne());
                String request=key();
                Runnable pick=()->prep.confirmPicked(staff,c.tenant(),o.id(),c.warehouse(),o.lines().getFirst(),5,version,request);
                Runnable revoke=()->scopes.revoke(owner,c.tenant(),c.warehouse(),staff.userId());
                PgCommitOrderEvidence.run(jdbc,transactionManager,revokeFirst?revoke:pick,revokeFirst?pick:revoke,
                    revokeFirst?"WAREHOUSE_SCOPE_REQUIRED":null);
                assertThat(status(c,o)).isEqualTo("PREPARING");
                assertThat(version(c,o)).isEqualTo(revokeFirst?version:version+1);
                assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo("20");
                assertThat(stock(c,c.itemOne(),"reserved")).isEqualByComparingTo("5");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_pick_confirmation WHERE tenant_id=? AND order_id=?",Integer.class,c.tenant(),o.id())).isEqualTo(revokeFirst?0:1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_preparation_receipt WHERE tenant_id=? AND request_key=?",Integer.class,c.tenant(),request)).isEqualTo(revokeFirst?0:1);
                if(revokeFirst)assertThat(JourneySqlEvidence.orderAndStock(jdbc,c.tenant(),o.id(),c.warehouse(),c.itemOne())).isEqualTo(before);
                var after=JourneySqlEvidence.orderAndStock(jdbc,c.tenant(),o.id(),c.warehouse(),c.itemOne());
                assertThatThrownBy(()->prep.markReadyForPickup(staff,c.tenant(),o.id(),c.warehouse(),version(c,o),key())).isInstanceOf(SecurityException.class).hasMessage("WAREHOUSE_SCOPE_REQUIRED");
                assertThat(JourneySqlEvidence.orderAndStock(jdbc,c.tenant(),o.id(),c.warehouse(),c.itemOne())).isEqualTo(after);
            }
        }
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

    private String qr07PhysicalProof(Context c, UUID requestId) {
        return returnProof.issue(c.buyer(),c.tenant(),requestId,key()).rawProof();
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
                claim.requestId(),2,qr07PhysicalProof(c,claim.requestId()),key());
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
                2,qr07PhysicalProof(c,claim.requestId()),key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),claim.requestId(),
                4,SalesReturnPhysicalService.Disposition.WRITE_OFF,"INSPECT_002",key());
        var posted=returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),5,key());
        assertThat(posted.state()).isEqualTo("DISPOSED");
        UUID unusedMovement=jdbc.query("""
                SELECT movement_id FROM crm.sales_return_posting
                WHERE tenant_id=? AND return_id=?
                """,(org.springframework.jdbc.core.ResultSetExtractor<UUID>)
                        rs->rs.next()?rs.getObject(1,UUID.class):null,
                c.tenant(),claim.requestId());
        assertThat(unusedMovement).isNull();
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo(a);
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo(b);
        var otherOrder=ready(c,false);
        var other=qr07R2Approved(c,otherOrder,1);
        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),other.requestId(),
                2,qr07PhysicalProof(c,other.requestId()),key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),other.requestId(),
                4,SalesReturnPhysicalService.Disposition.QUARANTINE,"INSPECT_003",key());
        BigDecimal stockBefore=stock(c,c.itemOne(),"on_hand");
        BigDecimal stockBeforeTwo=stock(c,c.itemTwo(),"on_hand");
        var quarantine=returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                other.requestId(),5,key());
        assertThat(quarantine.state()).isEqualTo("QUARANTINED");
        assertThat(stock(c,c.itemOne(),"on_hand")).isEqualByComparingTo(stockBefore);
        assertThat(stock(c,c.itemTwo(),"on_hand")).isEqualByComparingTo(stockBeforeTwo);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_posting p
                JOIN crm.inv_return_quarantine_movement m
                  ON m.tenant_id=p.tenant_id AND m.id=p.quarantine_movement_id
                WHERE p.tenant_id=? AND p.return_id=? AND p.disposition='QUARANTINE'
                """,Integer.class,c.tenant(),other.requestId())).isEqualTo(1);
    }

    @Test void qr07R2DeniedScopesAndPrematurePostingHaveNoSideEffects() throws Exception {
        requirePostgres();
        var c=context();var foreign=context();var o=ready(c,false);
        var claim=qr07R2Approved(c,o,1);
        assertThatThrownBy(()->returnPhysical.receive(foreign.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),2,"A".repeat(43),key()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->returnPhysical.post(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),2,key()))
                .hasMessageContaining("RETURN_PHYSICAL_EVIDENCE_REQUIRED");
        scopes.revoke(c.owner(),c.tenant(),c.warehouse(),c.staff().userId());
        assertThatThrownBy(()->returnPhysical.receive(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),2,"B".repeat(43),key()))
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
        String physicalProof=qr07PhysicalProof(c,claim.requestId());
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
                                claim.requestId(),2,physicalProof,requestKey);
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


    private SalesReturnRequestService.Created qr07R2ReadyToPost(Context c,Order o){
        var claim=qr07R2Approved(c,o,2);
        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),claim.requestId(),
                2,qr07PhysicalProof(c,claim.requestId()),key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),claim.requestId(),
                4,SalesReturnPhysicalService.Disposition.RESTOCK,
                "INSPECT_FOR_POST",key());
        return claim;
    }

    private Process startQr07R2Worker(String mode,Context c,UUID returnId,long revision,
                                     String requestKey,java.nio.file.Path ready,
                                     java.nio.file.Path release,java.nio.file.Path log)
                                     throws Exception{
        String cp=System.getenv("RM_QR06B_JAVA_CLASSPATH");
        if(cp==null||cp.isBlank())
            throw new IllegalStateException("QR07_R2_PROCESS_CLASSPATH_REQUIRED");
        String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),
                "bin","java").toString();
        return new ProcessBuilder(javaExecutable,"-cp",cp,
                "kz.rm.sales.QrReturnProcessWorker",mode,c.tenant().toString(),
                c.warehouse().toString(),c.staff().userId().toString(),
                returnId.toString(),Long.toString(revision),requestKey,
                ready.toString(),release.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    @Test void qr07R2JvmHardCrashBeforePostCommitRollsBackAndRetryPostsOnce()
            throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R2ReadyToPost(c,o);
        UUID item=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        BigDecimal before=stock(c,item,"on_hand");
        var dir=java.nio.file.Files.createTempDirectory("qr07-r2-pre-");
        Process worker=null;
        String req=key();
        try {
            worker=startQr07R2Worker("POST_HALT_BEFORE_COMMIT",c,claim.requestId(),
                    5,req,java.nio.file.Path.of("-"),java.nio.file.Path.of("-"),dir.resolve("worker.log"));
            assertThat(waitQr06bProcess(worker,dir.resolve("worker.log"),90))
                    .isEqualTo(86);
            assertThat(stock(c,item,"on_hand")).isEqualByComparingTo(before);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_return_posting
                    WHERE tenant_id=? AND return_id=?
                    """,Integer.class,c.tenant(),claim.requestId())).isZero();
            assertThat(returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                    claim.requestId(),5,req).state()).isEqualTo("RESTOCKED");
            assertThat(stock(c,item,"on_hand"))
                    .isEqualByComparingTo(before.add(BigDecimal.valueOf(2)));
        }finally{
            if(worker!=null&&worker.isAlive())worker.destroyForcibly();
            java.nio.file.Files.deleteIfExists(dir.resolve("worker.log"));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test void qr07R2JvmHardCrashAfterPostCommitReplaysOriginalMovement()
            throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R2ReadyToPost(c,o);
        UUID item=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        BigDecimal before=stock(c,item,"on_hand");
        var dir=java.nio.file.Files.createTempDirectory("qr07-r2-post-");
        Process worker=null;
        String req=key();
        try {
            worker=startQr07R2Worker("POST_HALT_AFTER_COMMIT",c,claim.requestId(),
                    5,req,java.nio.file.Path.of("-"),java.nio.file.Path.of("-"),dir.resolve("worker.log"));
            assertThat(waitQr06bProcess(worker,dir.resolve("worker.log"),90))
                    .isEqualTo(87);
            assertThat(stock(c,item,"on_hand"))
                    .isEqualByComparingTo(before.add(BigDecimal.valueOf(2)));
            var replay=returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                    claim.requestId(),5,req);
            assertThat(replay.state()).isEqualTo("RESTOCKED");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_return_posting
                    WHERE tenant_id=? AND return_id=?
                    """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
            assertThat(stock(c,item,"on_hand"))
                    .isEqualByComparingTo(before.add(BigDecimal.valueOf(2)));
        }finally{
            if(worker!=null&&worker.isAlive())worker.destroyForcibly();
            java.nio.file.Files.deleteIfExists(dir.resolve("worker.log"));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test void qr07R2TwoIndependentJvmsCannotDoubleRestockOneReturn()
            throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R2ReadyToPost(c,o);
        var dir=java.nio.file.Files.createTempDirectory("qr07-r2-race-");
        Process a=null,b=null;
        var readyA=dir.resolve("ready-a");
        var readyB=dir.resolve("ready-b");
        var release=dir.resolve("release");
        String req=key();
        try{
            a=startQr07R2Worker("POST_NORMAL",c,claim.requestId(),5,req,
                    readyA,release,dir.resolve("a.log"));
            b=startQr07R2Worker("POST_NORMAL",c,claim.requestId(),5,req,
                    readyB,release,dir.resolve("b.log"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(!java.nio.file.Files.exists(readyA)||
                  !java.nio.file.Files.exists(readyB)){
                assertThat(a.isAlive()&&b.isAlive()).isTrue();
                if(System.nanoTime()>deadline)
                    throw new AssertionError("QR07_R2_JVM_READY_TIMEOUT");
                Thread.sleep(25);
            }
            java.nio.file.Files.writeString(release,"START");
            assertThat(waitQr06bProcess(a,dir.resolve("a.log"),100)).isZero();
            assertThat(waitQr06bProcess(b,dir.resolve("b.log"),100)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_return_posting
                    WHERE tenant_id=? AND return_id=?
                    """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.inv_stock_movement m
                    JOIN crm.sales_return_posting p
                      ON p.tenant_id=m.tenant_id AND p.movement_id=m.id
                    WHERE p.tenant_id=? AND p.return_id=?
                    """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
            assertExactlyOneHandover(c,o,1);
        }finally{
            if(a!=null&&a.isAlive())a.destroyForcibly();
            if(b!=null&&b.isAlive())b.destroyForcibly();
            for(var name:List.of("ready-a","ready-b","release","a.log","b.log"))
                java.nio.file.Files.deleteIfExists(dir.resolve(name));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }


    @Test void qr07R25ProofRejectsForgeryAndRotationAndConsumesOnce() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R2Approved(c,o,1);
        var first=returnProof.issue(c.buyer(),c.tenant(),claim.requestId(),key());
        assertThatThrownBy(()->returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),2,"A".repeat(43),key()))
                .hasMessageContaining("RETURN_PROOF_UNAVAILABLE");
        var second=returnProof.issue(c.buyer(),c.tenant(),claim.requestId(),key());
        assertThat(second.generation()).isEqualTo(2);
        assertThatThrownBy(()->returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),2,first.rawProof(),key()))
                .hasMessageContaining("RETURN_PROOF_UNAVAILABLE");
        String scanKey=key();
        var receipt=returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),2,second.rawProof(),scanKey);
        assertThat(receipt.state()).isEqualTo("RECEIVED");
        assertThat(returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),2,second.rawProof(),scanKey).evidenceId())
                .isEqualTo(receipt.evidenceId());
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_return_reception_proof
                WHERE tenant_id=? AND return_id=?
                """,String.class,c.tenant(),claim.requestId())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("""
                SELECT evidence_ref FROM crm.sales_return_reception
                WHERE tenant_id=? AND return_id=?
                """,String.class,c.tenant(),claim.requestId()))
                .isNotEqualTo(second.rawProof()).hasSize(64);
        assertThatThrownBy(()->returnProof.issue(c.buyer(),c.tenant(),
                claim.requestId(),key()))
                .hasMessageContaining("RETURN_PROOF_REQUIRES_APPROVAL");
    }

    @Test void qr07R25RevokedPostIamCannotCreateQuarantineMovement() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R2Approved(c,o,1);
        var proof=qr07PhysicalProof(c,claim.requestId());
        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),2,proof,key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),4,SalesReturnPhysicalService.Disposition.QUARANTINE,
                "INSPECT_Q_R25",key());
        UUID role=assignedRole(c);
        roles.revoke(c.owner(),c.tenant(),role,DomainPermission.SALES_RETURN_POST);
        assertThatThrownBy(()->returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),5,key())).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_return_quarantine_movement
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isZero();
        roles.grant(c.owner(),c.tenant(),role,DomainPermission.SALES_RETURN_POST);
        roles.revoke(c.owner(),c.tenant(),role,DomainPermission.INVENTORY_RECEIVE);
        assertThatThrownBy(()->returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),5,key())).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_posting
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isZero();
    }

    @Test void qr07R25QuarantineLedgerConservesSameSkuAcrossReturns() throws Exception {
        requirePostgres();
        var c=context();
        var first=ready(c,false);
        var one=qr07R2Approved(c,first,1);
        var item=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),one.orderLineId());
        var second=ready(c,false);
        var two=qr07R2Approved(c,second,2);
        var itemTwo=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),two.orderLineId());
        // Fixture may choose different random SKUs: both movements must
        // agree with each item-specific immutable quarantine balance.
        for(var claim:java.util.List.of(one,two)){
            returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                    claim.requestId(),2,qr07PhysicalProof(c,claim.requestId()),key());
            returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),
                    claim.requestId(),4,SalesReturnPhysicalService.Disposition.QUARANTINE,
                    "INSPECT_Q_"+claim.requestId(),key());
            assertThat(returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                    claim.requestId(),5,key()).state()).isEqualTo("QUARANTINED");
        }
        for(UUID sku:new java.util.HashSet<>(java.util.List.of(item,itemTwo))){
            BigDecimal sum=jdbc.queryForObject("""
                    SELECT COALESCE(SUM(delta_quantity),0)
                    FROM crm.inv_return_quarantine_movement
                    WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                    """,BigDecimal.class,c.tenant(),c.warehouse(),sku);
            BigDecimal balance=jdbc.queryForObject("""
                    SELECT quantity FROM crm.inv_return_quarantine_balance
                    WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                    """,BigDecimal.class,c.tenant(),c.warehouse(),sku);
            assertThat(balance).isEqualByComparingTo(sum);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_return_quarantine_movement
                WHERE tenant_id=? AND return_id IN (?,?)
                """,Integer.class,c.tenant(),one.requestId(),two.requestId()))
                .isEqualTo(2);
    }


    @Test void qr07R25ConcurrentReturnPostingAndWarehouseRevocationIsAtomic()
            throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R2Approved(c,o,2);
        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),2,qr07PhysicalProof(c,claim.requestId()),key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),claim.requestId(),4,
                SalesReturnPhysicalService.Disposition.RESTOCK,"INSPECT_RACE_R25",key());
        UUID sku=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        BigDecimal before=stock(c,sku,"on_hand");
        var barrier=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        boolean success;
        try(var threads=Executors.newFixedThreadPool(2)){
            var postFuture=threads.submit(()->{
                barrier.countDown();
                if(!start.await(15,TimeUnit.SECONDS))throw new IllegalStateException("BARRIER");
                try{
                    returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                            claim.requestId(),5,key());
                    return true;
                }catch(SecurityException denied){
                    return false;
                }
            });
            var revokeFuture=threads.submit(()->{
                barrier.countDown();
                if(!start.await(15,TimeUnit.SECONDS))throw new IllegalStateException("BARRIER");
                scopes.revoke(c.owner(),c.tenant(),c.warehouse(),c.staff().userId());
                return true;
            });
            assertThat(barrier.await(15,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            success=postFuture.get(90,TimeUnit.SECONDS);
            assertThat(revokeFuture.get(90,TimeUnit.SECONDS)).isTrue();
        }finally{start.countDown();}
        int postings=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_posting
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId());
        assertThat(postings).isEqualTo(success?1:0);
        assertThat(stock(c,sku,"on_hand")).isEqualByComparingTo(
                before.add(BigDecimal.valueOf(success?2:0)));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement m
                JOIN crm.sales_return_posting p
                  ON p.tenant_id=m.tenant_id AND p.movement_id=m.id
                WHERE p.tenant_id=? AND p.return_id=?
                """,Integer.class,c.tenant(),claim.requestId()))
                .isEqualTo(success?1:0);
        assertThatThrownBy(()->returnPhysical.post(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),5,key()))
                .isInstanceOf(SecurityException.class);
    }


    private SalesReturnRequestService.Created qr07R25Quarantined(Context c,Order o){
        var claim=qr07R2Approved(c,o,2);
        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),2,qr07PhysicalProof(c,claim.requestId()),key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),4,SalesReturnPhysicalService.Disposition.QUARANTINE,
                "INSPECT_Q_EXIT",key());
        assertThat(returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),5,key()).state()).isEqualTo("QUARANTINED");
        return claim;
    }

    @Test void qr07R25QuarantineReleaseRestocksExactlyOnceAndLeavesOriginalReceipt()
            throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R25Quarantined(c,o);
        UUID item=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        BigDecimal sellable=stock(c,item,"on_hand");
        String requestKey=key();
        var out=quarantineResolution.resolve(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.RESTOCK,
                "QA_RELEASE_PASS",requestKey);
        assertThat(out.state()).isEqualTo("RESTOCKED");
        assertThat(out.stockMovementId()).isNotNull();
        assertThat(quarantineResolution.resolve(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.RESTOCK,
                "QA_RELEASE_PASS",requestKey).resolutionId())
                .isEqualTo(out.resolutionId());
        assertThat(stock(c,item,"on_hand"))
                .isEqualByComparingTo(sellable.add(BigDecimal.valueOf(2)));
        assertThat(jdbc.queryForObject("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,BigDecimal.class,c.tenant(),c.warehouse(),item))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_quarantine_resolution
                WHERE tenant_id=? AND return_id=? AND stock_movement_id IS NOT NULL
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
        assertExactlyOneHandover(c,o,1);
    }

    @Test void qr07R25QuarantineDisposalCannotLeakIntoSellableStock() throws Exception {
        requirePostgres();
        var c=context();var o=ready(c,false);
        var claim=qr07R25Quarantined(c,o);
        UUID item=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        BigDecimal initial=stock(c,item,"on_hand");
        assertThatThrownBy(()->quarantineResolution.resolve(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.DISPOSE,
                "QA_DESTROYED",key())).isInstanceOf(SecurityException.class);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.INVENTORY_ADJUST);
        String req=key();
        var resolution=quarantineResolution.resolve(c.staff(),c.tenant(),c.warehouse(),
                claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.DISPOSE,
                "QA_DESTROYED",req);
        assertThat(resolution.state()).isEqualTo("DISPOSED");
        assertThat(resolution.stockMovementId()).isNull();
        assertThat(stock(c,item,"on_hand")).isEqualByComparingTo(initial);
        assertThat(jdbc.queryForObject("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,BigDecimal.class,c.tenant(),c.warehouse(),item))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_quarantine_resolution
                WHERE tenant_id=? AND return_id=? AND action_code='DISPOSE'
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
        assertThatThrownBy(()->quarantineResolution.resolve(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.RESTOCK,
                "QA_DESTROYED",req))
                .hasMessageContaining("QUARANTINE_REPLAY_CONFLICT");
    }


    @Test void qr07R25ConcurrentQuarantineRestockVsDisposeSettlesExactlyOnce()
            throws Exception {
        requirePostgres();
        var c=context();var order=ready(c,false);
        var claim=qr07R25Quarantined(c,order);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.INVENTORY_ADJUST);
        UUID sku=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        BigDecimal stockBefore=stock(c,sku,"on_hand");
        var arrive=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        String[] results=new String[2];
        try(var pool=Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<java.util.concurrent.Future<String>>();
            for(int n=0;n<2;n++){
                final boolean restock=n==0;
                tasks.add(pool.submit(()->{
                    arrive.countDown();
                    if(!release.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("R25_RACE_TIMEOUT");
                    try{
                        var decision=quarantineResolution.resolve(c.staff(),c.tenant(),
                                c.warehouse(),claim.requestId(),6,
                                restock?SalesReturnQuarantineResolutionService.Action.RESTOCK:
                                    SalesReturnQuarantineResolutionService.Action.DISPOSE,
                                restock?"QA_RELEASE":"QA_DISPOSAL",key());
                        return decision.state();
                    }catch(IllegalStateException expected){
                        if(!expected.getMessage().contains("QUARANTINE_STATE_VERSION_CONFLICT")
                                && !expected.getMessage().contains("QUARANTINE_REPLAY_CONFLICT"))
                            throw expected;
                        return "CONFLICT";
                    }
                }));
            }
            assertThat(arrive.await(15,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for(int n=0;n<2;n++)results[n]=tasks.get(n).get(90,TimeUnit.SECONDS);
        }finally{release.countDown();}
        assertThat(java.util.List.of(results).stream()
                .filter(v->!v.equals("CONFLICT")).count()).isEqualTo(1);
        assertThat(java.util.List.of(results)).contains("CONFLICT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_quarantine_resolution
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,BigDecimal.class,c.tenant(),c.warehouse(),sku))
                .isEqualByComparingTo("0");
        assertThat(stock(c,sku,"on_hand")).isEqualByComparingTo(
                stockBefore.add(BigDecimal.valueOf(results[0].equals("RESTOCKED")?2:0)));
        assertExactlyOneHandover(c,order,1);
    }

    @Test void qr07R25CoreRet04RestockFirst() throws Exception {
        coreRet04CommitOrder(true);
    }

    @Test void qr07R25CoreRet04DisposeFirst() throws Exception {
        coreRet04CommitOrder(false);
    }

    private void coreRet04CommitOrder(boolean restockFirst) throws Exception {
        requirePostgres();
        var c=context();var order=ready(c,false);
        var claim=qr07R25Quarantined(c,order);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.INVENTORY_ADJUST);
        UUID sku=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        BigDecimal stockBefore=stock(c,sku,"on_hand");
        BigDecimal quarantineBefore=jdbc.queryForObject("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,BigDecimal.class,c.tenant(),c.warehouse(),sku);
        assertThat(quarantineBefore).isEqualByComparingTo("2");
        ret04BSnapshot(c);
        String restockKey=key(),disposeKey=key();
        var originalPosting=jdbc.queryForList("""
                SELECT * FROM crm.sales_return_posting WHERE tenant_id=? AND return_id=?
                """,c.tenant(),claim.requestId());
        var originalIntake=jdbc.queryForList("""
                SELECT * FROM crm.inv_return_quarantine_movement WHERE tenant_id=? AND return_id=?
                """,c.tenant(),claim.requestId());
        assertThat(originalPosting).hasSize(1);
        assertThat(originalIntake).hasSize(1);
        int movementsBefore=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
                """,Integer.class,c.tenant());
        Runnable restock=()->quarantineResolution.resolve(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.RESTOCK,"QA_RELEASE",restockKey);
        Runnable dispose=()->quarantineResolution.resolve(c.staff(),c.tenant(),
                c.warehouse(),claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.DISPOSE,"QA_DISPOSAL",disposeKey);
        PgCommitOrderEvidence.run(jdbc,transactionManager,
                restockFirst?restock:dispose,restockFirst?dispose:restock,
                IllegalStateException.class,"QUARANTINE_REPLAY_CONFLICT");
        String target=restockFirst?"RESTOCKED":"DISPOSED";
        assertThat(jdbc.queryForMap("""
                SELECT state,row_version FROM crm.sales_return_request
                WHERE tenant_id=? AND id=?
                """,c.tenant(),claim.requestId())).containsEntry("state",target);
        assertThat(jdbc.queryForObject("""
                SELECT row_version FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,Long.class,c.tenant(),claim.requestId())).isEqualTo(7L);
        var resolutions=jdbc.queryForList("""
                SELECT action_code,request_key,quantity,before_quantity,after_quantity,stock_movement_id
                FROM crm.sales_return_quarantine_resolution WHERE tenant_id=? AND return_id=?
                """,c.tenant(),claim.requestId());
        assertThat(resolutions).hasSize(1);
        var resolution=resolutions.get(0);
        assertThat(resolution).containsEntry("action_code",restockFirst?"RESTOCK":"DISPOSE")
                .containsEntry("request_key",restockFirst?restockKey:disposeKey);
        assertThat((BigDecimal)resolution.get("quantity")).isEqualByComparingTo("2");
        assertThat((BigDecimal)resolution.get("before_quantity")).isEqualByComparingTo(quarantineBefore);
        assertThat((BigDecimal)resolution.get("after_quantity")).isEqualByComparingTo("0");
        if(restockFirst)assertThat(resolution.get("stock_movement_id")).isNotNull();
        else assertThat(resolution.get("stock_movement_id")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,BigDecimal.class,c.tenant(),c.warehouse(),sku)).isEqualByComparingTo("0");
        assertThat(stock(c,sku,"on_hand")).isEqualByComparingTo(
                stockBefore.add(BigDecimal.valueOf(restockFirst?2:0)));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
                """,Integer.class,c.tenant())).isEqualTo(movementsBefore+(restockFirst?1:0));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND return_id=? AND from_state='QUARANTINED'
                """,Integer.class,c.tenant(),claim.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND return_id=? AND from_state='QUARANTINED'
                  AND to_state=? AND request_key=? AND expected_row_version=6
                """,Integer.class,c.tenant(),claim.requestId(),target,
                restockFirst?restockKey:disposeKey)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT * FROM crm.sales_return_posting WHERE tenant_id=? AND return_id=?
                """,c.tenant(),claim.requestId())).isEqualTo(originalPosting);
        assertThat(jdbc.queryForList("""
                SELECT * FROM crm.inv_return_quarantine_movement WHERE tenant_id=? AND return_id=?
                """,c.tenant(),claim.requestId())).isEqualTo(originalIntake);
        assertExactlyOneHandover(c,order,1);
        ret04BSnapshot(c);
    }

    // BSR-30A.5B: independent fixtures and durable SQL oracles for each case.
    private record Ret04B(Context c,Order order,UUID returnId,UUID sku,
                          BigDecimal stockBefore,String requestKey,
                          java.util.Map<String,List<String>> before) {}

    private Ret04B ret04BFixture() throws Exception {
        requirePostgres();
        var c=context();var order=ready(c,false);
        var claim=qr07R25Quarantined(c,order);
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.INVENTORY_ADJUST);
        UUID sku=jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND id=?
                """,UUID.class,c.tenant(),claim.orderLineId());
        var before=ret04BSnapshot(c);
        assertThat(before.get("sales_return_quarantine_resolution")).isEmpty();
        assertThat(ret04BQuarantine(c,sku)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("""
                SELECT row_version FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,Long.class,c.tenant(),claim.requestId())).isEqualTo(6L);
        return new Ret04B(c,order,claim.requestId(),sku,stock(c,sku,"on_hand"),key(),before);
    }

    private java.util.Map<String,List<String>> ret04BSnapshot(Context c) {
        var out=new java.util.LinkedHashMap<String,List<String>>();
        // Table names are a closed test-owned allowlist. JSON text preserves
        // versions, audit fields, IDs, and timestamps without JDBC array equality.
        for(String table:List.of("inv_stock_balance","inv_stock_movement",
                "inv_stock_reservation","inv_reservation_event",
                "inv_return_quarantine_balance","inv_return_quarantine_movement",
                "sales_return_request","sales_return_posting","sales_return_event",
                "sales_return_quarantine_resolution","sales_order","sales_order_line",
                "sales_order_status_event","sales_pickup_handover",
                "sales_pickup_handover_line","sales_pickup_credential","fin_invoice")){
            out.put(table,jdbc.queryForList("SELECT row_to_json(t)::text FROM crm."+table+
                    " t WHERE tenant_id=? ORDER BY 1",String.class,c.tenant()));
        }
        ret04DRecordSnapshot(c,out);
        return out;
    }

    /** Test-only evidence: full-row digests plus independent numeric/state SQL projections.
     * No raw credentials, user information or token material is exported. */
    private void ret04DRecordSnapshot(Context c,java.util.Map<String,List<String>> snapshot) {
        String directory=System.getenv("RM_RET04_EVIDENCE_DIR");
        if(directory==null||directory.isBlank())return;
        String method=StackWalker.getInstance().walk(frames->frames
                .map(StackWalker.StackFrame::getMethodName)
                .filter(name->name.startsWith("qr07R25CoreRet04")).findFirst().orElse(null));
        if(method==null)return;
        String sha=System.getenv("GITHUB_SHA");
        if(sha==null||!sha.matches("[0-9a-f]{40}"))throw new AssertionError("RET04D SHA missing");
        try{
            var dir=java.nio.file.Path.of(directory,method);
            java.nio.file.Files.createDirectories(dir);
            long sequence;
            try(var files=java.nio.file.Files.list(dir)){sequence=files.count();}
            var lines=new ArrayList<String>();
            lines.add("sha\t"+sha);
            lines.add("method\t"+method);
            lines.add("postgres\t"+jdbc.queryForObject("SELECT current_setting('server_version_num')",String.class));
            lines.add("tenant\t"+c.tenant());
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(var entry:snapshot.entrySet()){
                digest.reset();
                for(String row:entry.getValue()){
                    byte[] bytes=row.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
                lines.add("table\t"+entry.getKey()+"\t"+entry.getValue().size()+"\t"+
                        java.util.HexFormat.of().formatHex(digest.digest()));
            }
            // Explicit projections are independent reads, not service return values.
            for(String projection:List.of(
                    "SELECT id,state,row_version FROM crm.sales_return_request WHERE tenant_id=?",
                    "SELECT location_id,item_id,on_hand,reserved,row_version FROM crm.inv_stock_balance WHERE tenant_id=?",
                    "SELECT warehouse_id,item_id,quantity,row_version FROM crm.inv_return_quarantine_balance WHERE tenant_id=?",
                    "SELECT return_id,warehouse_id,item_id,quantity,before_quantity,after_quantity,action_code,stock_movement_id FROM crm.sales_return_quarantine_resolution WHERE tenant_id=?",
                    "SELECT id,location_id,item_id,delta_quantity,balance_before,balance_after FROM crm.inv_stock_movement WHERE tenant_id=?",
                    "SELECT return_id,from_state,to_state,expected_row_version FROM crm.sales_return_event WHERE tenant_id=?")){
                String table=projection.substring(projection.indexOf("FROM crm.")+9,projection.indexOf(" WHERE"));
                for(String row:jdbc.queryForList("SELECT row_to_json(p)::text FROM ("+projection+") p ORDER BY 1",
                        String.class,c.tenant()))lines.add("row\t"+table+"\t"+row);
            }
            java.nio.file.Files.write(dir.resolve(String.format(java.util.Locale.ROOT,"snapshot-%03d.tsv",sequence)),
                    lines,java.nio.charset.StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE_NEW);
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){
            throw new AssertionError("RET04D evidence write failed",e);
        }
    }

    private BigDecimal ret04BQuarantine(Context c,UUID sku) {
        return jdbc.queryForObject("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,BigDecimal.class,c.tenant(),c.warehouse(),sku);
    }

    private SalesReturnQuarantineResolutionService.Resolved ret04BResolve(Ret04B f,
            boolean restock,long version,String evidence,String requestKey) {
        var c=f.c();
        return quarantineResolution.resolve(c.staff(),c.tenant(),c.warehouse(),f.returnId(),
                version,restock?SalesReturnQuarantineResolutionService.Action.RESTOCK:
                        SalesReturnQuarantineResolutionService.Action.DISPOSE,evidence,requestKey);
    }

    private void ret04BAssertUnchanged(Ret04B f,
            java.util.Map<String,List<String>> expected) {
        assertThat(ret04BSnapshot(f.c())).isEqualTo(expected);
        assertExactlyOneHandover(f.c(),f.order(),1);
    }

    private void ret04BAssertResolved(Ret04B f,boolean restock,
            SalesReturnQuarantineResolutionService.Resolved result) {
        var c=f.c();String target=restock?"RESTOCKED":"DISPOSED";
        assertThat(result.returnId()).isEqualTo(f.returnId());
        assertThat(result.state()).isEqualTo(target);
        assertThat(result.version()).isEqualTo(7L);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),f.returnId())).isEqualTo(target);
        assertThat(jdbc.queryForObject("""
                SELECT row_version FROM crm.sales_return_request WHERE tenant_id=? AND id=?
                """,Long.class,c.tenant(),f.returnId())).isEqualTo(7L);
        var rows=jdbc.queryForList("""
                SELECT id,action_code,request_key,evidence_ref,stock_movement_id,
                       quantity,before_quantity,after_quantity
                FROM crm.sales_return_quarantine_resolution WHERE tenant_id=? AND return_id=?
                """,c.tenant(),f.returnId());
        assertThat(rows).hasSize(1);
        var row=rows.getFirst();
        assertThat(row).containsEntry("id",result.resolutionId())
                .containsEntry("action_code",restock?"RESTOCK":"DISPOSE")
                .containsEntry("request_key",f.requestKey()).containsEntry("evidence_ref","QA_B_EVIDENCE");
        assertThat(row.get("stock_movement_id")).isEqualTo(result.stockMovementId());
        assertThat((BigDecimal)row.get("quantity")).isEqualByComparingTo("2");
        assertThat((BigDecimal)row.get("before_quantity")).isEqualByComparingTo("2");
        assertThat((BigDecimal)row.get("after_quantity")).isEqualByComparingTo("0");
        assertThat(ret04BQuarantine(c,f.sku())).isEqualByComparingTo("0");
        assertThat(stock(c,f.sku(),"on_hand")).isEqualByComparingTo(
                f.stockBefore().add(BigDecimal.valueOf(restock?2:0)));
        String movementKey="R07_"+f.returnId().toString().replace("-","");
        var movements=jdbc.queryForList("""
                SELECT id,location_id,item_id,delta_quantity,balance_before,balance_after
                FROM crm.inv_stock_movement WHERE tenant_id=? AND request_key=?
                """,c.tenant(),movementKey);
        if(restock){
            assertThat(result.stockMovementId()).isNotNull();
            assertThat(movements).hasSize(1);
            var m=movements.getFirst();
            assertThat(m).containsEntry("id",result.stockMovementId())
                    .containsEntry("location_id",c.warehouse()).containsEntry("item_id",f.sku());
            assertThat((BigDecimal)m.get("delta_quantity")).isEqualByComparingTo("2");
            assertThat((BigDecimal)m.get("balance_before")).isEqualByComparingTo(f.stockBefore());
            assertThat((BigDecimal)m.get("balance_after")).isEqualByComparingTo(f.stockBefore().add(BigDecimal.valueOf(2)));
        }else{
            assertThat(result.stockMovementId()).isNull();
            assertThat(movements).isEmpty();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND return_id=? AND from_state='QUARANTINED'
                """,Integer.class,c.tenant(),f.returnId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event
                WHERE tenant_id=? AND return_id=? AND from_state='QUARANTINED'
                  AND to_state=? AND request_key=? AND expected_row_version=6
                """,Integer.class,c.tenant(),f.returnId(),target,f.requestKey())).isEqualTo(1);
        var after=ret04BSnapshot(c);
        assertThat(after.get("inv_stock_movement")).hasSize(
                f.before().get("inv_stock_movement").size()+(restock?1:0));
        assertThat(after.get("inv_stock_movement")).containsAll(f.before().get("inv_stock_movement"));
        assertThat(after.get("sales_return_event")).hasSize(f.before().get("sales_return_event").size()+1);
        assertThat(after.get("sales_return_event")).containsAll(f.before().get("sales_return_event"));
        assertThat(after.get("sales_return_quarantine_resolution")).hasSize(1);
        for(String table:f.before().keySet()){
            if(!List.of("inv_stock_balance","inv_stock_movement","inv_return_quarantine_balance",
                    "sales_return_request","sales_return_event","sales_return_quarantine_resolution").contains(table))
                assertThat(after.get(table)).as("immutable "+table).isEqualTo(f.before().get(table));
        }
        if(!restock)assertThat(after.get("inv_stock_balance")).isEqualTo(f.before().get("inv_stock_balance"));
        assertExactlyOneHandover(c,f.order(),1);
    }

    @Test void qr07R25CoreRet04B01RestockDistinctKeys() throws Exception { ret04BRace(true,false); }
    @Test void qr07R25CoreRet04B02DisposeDistinctKeys() throws Exception { ret04BRace(false,false); }
    @Test void qr07R25CoreRet04B03RestockIdenticalReplay() throws Exception { ret04BRace(true,true); }
    @Test void qr07R25CoreRet04B04DisposeIdenticalReplay() throws Exception { ret04BRace(false,true); }

    private void ret04BRace(boolean restock,boolean replay) throws Exception {
        var f=ret04BFixture();
        var first=new java.util.concurrent.atomic.AtomicReference<SalesReturnQuarantineResolutionService.Resolved>();
        var second=new java.util.concurrent.atomic.AtomicReference<SalesReturnQuarantineResolutionService.Resolved>();
        String otherKey=replay?f.requestKey():key();
        PgCommitOrderEvidence.run(jdbc,transactionManager,
                ()->first.set(ret04BResolve(f,restock,6,"QA_B_EVIDENCE",f.requestKey())),
                ()->second.set(ret04BResolve(f,restock,6,"QA_B_EVIDENCE",otherKey)),
                IllegalStateException.class,replay?null:"QUARANTINE_REPLAY_CONFLICT");
        assertThat(first.get()).isNotNull();
        if(replay)assertThat(second.get()).isEqualTo(first.get());
        else assertThat(second.get()).isNull();
        ret04BAssertResolved(f,restock,first.get());
    }

    @Test void qr07R25CoreRet04B05ChangedAction() throws Exception { ret04BConflict(5); }
    @Test void qr07R25CoreRet04B06ChangedEvidence() throws Exception { ret04BConflict(6); }
    @Test void qr07R25CoreRet04B07StaleVersionBeforeResolution() throws Exception {
        var f=ret04BFixture();
        assertThatThrownBy(()->ret04BResolve(f,true,5,"QA_B_EVIDENCE",f.requestKey()))
                .isInstanceOf(IllegalStateException.class).hasMessage("QUARANTINE_STATE_VERSION_CONFLICT");
        ret04BAssertUnchanged(f,f.before());
    }
    @Test void qr07R25CoreRet04B08ChangedVersionAfterResolution() throws Exception { ret04BConflict(8); }

    private void ret04BConflict(int variant) throws Exception {
        var f=ret04BFixture();
        var first=ret04BResolve(f,true,6,"QA_B_EVIDENCE",f.requestKey());
        ret04BAssertResolved(f,true,first);
        var after=ret04BSnapshot(f.c());
        assertThatThrownBy(()->ret04BResolve(f,variant!=5,variant==8?7:6,
                variant==6?"QA_B_CHANGED":"QA_B_EVIDENCE",f.requestKey()))
                .isInstanceOf(IllegalStateException.class).hasMessage("QUARANTINE_REPLAY_CONFLICT");
        ret04BAssertUnchanged(f,after);
    }

    @Test void qr07R25CoreRet04B09ResolutionInsertRollback() throws Exception { ret04BFault(false,false); }
    @Test void qr07R25CoreRet04B10TerminalEventInsertRollback() throws Exception { ret04BFault(true,false); }
    @Test void qr07R25CoreRet04B11ResolutionInsertSameKeyRetry() throws Exception { ret04BFault(false,true); }
    @Test void qr07R25CoreRet04B12TerminalEventInsertSameKeyRetry() throws Exception { ret04BFault(true,true); }

    private void ret04BFault(boolean terminalEvent,boolean retry) throws Exception {
        var f=ret04BFixture();var c=f.c();
        String suffix=UUID.randomUUID().toString().replace("-","");
        String fn="ret04b_fn_"+suffix,trg="ret04b_tr_"+suffix;
        String table=terminalEvent?"sales_return_event":"sales_return_quarantine_resolution";
        String marker=terminalEvent?"RET04B_TERMINAL_EVENT_FAULT":"RET04B_RESOLUTION_FAULT";
        // The failure marker is reachable only after independent in-transaction
        // SQL has observed the stock credit, movement, quarantine debit and
        // (at the later failpoint) saved resolution and terminal state.
        String function="""
                CREATE FUNCTION crm.%s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.tenant_id='%s'::uuid AND NEW.return_id='%s'::uuid THEN
                    IF NOT EXISTS (SELECT 1 FROM crm.inv_stock_movement
                        WHERE tenant_id=NEW.tenant_id AND request_key='%s'
                          AND item_id='%s'::uuid AND location_id='%s'::uuid AND delta_quantity=2)
                       OR NOT EXISTS (SELECT 1 FROM crm.inv_stock_balance
                        WHERE tenant_id=NEW.tenant_id AND location_id='%s'::uuid
                          AND item_id='%s'::uuid AND on_hand=%s)
                       OR NOT EXISTS (SELECT 1 FROM crm.inv_return_quarantine_balance
                        WHERE tenant_id=NEW.tenant_id AND warehouse_id='%s'::uuid
                          AND item_id='%s'::uuid AND quantity=0)
                       OR NOT EXISTS (SELECT 1 FROM crm.sales_return_request
                        WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id
                          AND state='%s' AND row_version=%d)
                       OR (SELECT count(*) FROM crm.sales_return_quarantine_resolution
                        WHERE tenant_id=NEW.tenant_id AND return_id=NEW.return_id)<>%d
                    THEN RAISE EXCEPTION 'RET04B_FAILPOINT_PRECONDITION_MISSING'; END IF;
                    RAISE EXCEPTION '%s';
                  END IF;
                  RETURN NEW;
                END; $$
                """.formatted(fn,c.tenant(),f.returnId(),
                "R07_"+f.returnId().toString().replace("-",""),f.sku(),c.warehouse(),
                c.warehouse(),f.sku(),f.stockBefore().add(BigDecimal.valueOf(2)).toPlainString(),
                c.warehouse(),f.sku(),terminalEvent?"RESTOCKED":"QUARANTINED",
                terminalEvent?7:6,terminalEvent?1:0,marker);
        jdbc.execute(function);
        try {
            jdbc.execute("CREATE TRIGGER "+trg+" BEFORE INSERT ON crm."+table+
                    " FOR EACH ROW EXECUTE FUNCTION crm."+fn+"()");
            assertThatThrownBy(()->ret04BResolve(f,true,6,"QA_B_EVIDENCE",f.requestKey()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .satisfies(ex->{
                        Throwable root=ex;
                        while(root.getCause()!=null)root=root.getCause();
                        assertThat(root).isInstanceOf(java.sql.SQLException.class);
                        var sql=(java.sql.SQLException)root;
                        assertThat(sql.getSQLState()).isEqualTo("P0001");
                        assertThat(sql.getMessage()).contains(marker)
                                .doesNotContain("RET04B_FAILPOINT_PRECONDITION_MISSING");
                        System.out.println("RET04D verified SQLSTATE P0001 "+marker);
                    });
            ret04BAssertUnchanged(f,f.before());
        }finally{
            try{jdbc.execute("DROP TRIGGER IF EXISTS "+trg+" ON crm."+table);}
            finally{jdbc.execute("DROP FUNCTION IF EXISTS crm."+fn+"()");}
        }
        if(retry){
            var resolved=ret04BResolve(f,true,6,"QA_B_EVIDENCE",f.requestKey());
            ret04BAssertResolved(f,true,resolved);
            var after=ret04BSnapshot(c);
            assertThat(ret04BResolve(f,true,6,"QA_B_EVIDENCE",f.requestKey())).isEqualTo(resolved);
            ret04BAssertUnchanged(f,after);
        }
    }

    // BSR-30A.5C: independent process crashes and shared quarantine ledger.
    @Test void qr07R25CoreRet04C01RestockCrashBeforeCommit() throws Exception { ret04CCrash(true,true); }
    @Test void qr07R25CoreRet04C02RestockCrashAfterCommit() throws Exception { ret04CCrash(true,false); }
    @Test void qr07R25CoreRet04C03DisposeCrashBeforeCommit() throws Exception { ret04CCrash(false,true); }
    @Test void qr07R25CoreRet04C04DisposeCrashAfterCommit() throws Exception { ret04CCrash(false,false); }

    private String ret04CMode(boolean restock,String phase){
        return "RESOLVE_"+(restock?"RESTOCK_":"DISPOSE_")+phase;
    }

    private SalesReturnQuarantineResolutionService.Resolved ret04CDurableResult(Ret04B f){
        return jdbc.queryForObject("""
                SELECT r.id,r.state,r.row_version,q.id AS resolution_id,q.stock_movement_id
                FROM crm.sales_return_request r JOIN crm.sales_return_quarantine_resolution q
                  ON q.tenant_id=r.tenant_id AND q.return_id=r.id
                WHERE r.tenant_id=? AND r.id=?
                """,(rs,n)->new SalesReturnQuarantineResolutionService.Resolved(
                rs.getObject("id",UUID.class),rs.getObject("resolution_id",UUID.class),
                rs.getString("state"),rs.getLong("row_version"),rs.getObject("stock_movement_id",UUID.class)),
                f.c().tenant(),f.returnId());
    }

    private void ret04CCrash(boolean restock,boolean precommit) throws Exception {
        var f=ret04BFixture();var dir=java.nio.file.Files.createTempDirectory("ret04c-crash-");
        var log=dir.resolve("worker.log");Process worker=null;
        try{
            worker=startQr07R2Worker(ret04CMode(restock,precommit?"HALT_BEFORE_COMMIT":"HALT_AFTER_COMMIT"),
                    f.c(),f.returnId(),6,f.requestKey(),java.nio.file.Path.of("-"),java.nio.file.Path.of("-"),log);
            assertThat(waitQr06bProcess(worker,log,120)).isEqualTo(precommit?86:87);
            String output=java.nio.file.Files.readString(log);
            SalesReturnQuarantineResolutionService.Resolved result;
            if(precommit){
                var marker=java.util.regex.Pattern.compile("RET04C_PRECOMMIT pid=(\\d+) ").matcher(output);
                assertThat(marker.find()).as("child reached halt inside active transaction").isTrue();
                int pid=Integer.parseInt(marker.group(1));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=?)",Boolean.class,pid))){
                    if(System.nanoTime()>deadline)throw new AssertionError("RET04C crashed backend did not exit");
                    Thread.sleep(25);
                }
                ret04BAssertUnchanged(f,f.before());
                result=ret04BResolve(f,restock,6,"QA_B_EVIDENCE",f.requestKey());
            }else{
                result=ret04CDurableResult(f);
                assertThat(output).contains("RET04C_COMMITTED "+result);
                ret04BAssertResolved(f,restock,result);
            }
            ret04BAssertResolved(f,restock,result);
            var after=ret04BSnapshot(f.c());
            assertThat(ret04BResolve(f,restock,6,"QA_B_EVIDENCE",f.requestKey())).isEqualTo(result);
            ret04BAssertUnchanged(f,after);
            System.out.printf("RET04C crash precommit=%s restock=%s exit=%d resolution=%s%n",
                    precommit,restock,precommit?86:87,result.resolutionId());
        }finally{ret04CCleanup(dir,worker);}
    }

    private void ret04CCleanup(java.nio.file.Path dir,Process... workers) throws Exception {
        for(var worker:workers)if(worker!=null&&worker.isAlive()){
            worker.destroyForcibly();
            assertThat(worker.waitFor(15,TimeUnit.SECONDS)).as("child terminated").isTrue();
        }
        try(var entries=java.nio.file.Files.list(dir)){
            for(var path:entries.toList())java.nio.file.Files.deleteIfExists(path);
        }
        java.nio.file.Files.deleteIfExists(dir);
    }

    private int ret04CAwaitPid(Process worker,java.nio.file.Path ready,java.nio.file.Path log) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(75);
        while(!java.nio.file.Files.exists(ready)){
            if(!worker.isAlive()||System.nanoTime()>deadline)
                throw new AssertionError("RET04C child PID unavailable: "+java.nio.file.Files.readString(log));
            Thread.sleep(25);
        }
        return Integer.parseInt(java.nio.file.Files.readString(ready));
    }

    @Test void qr07R25CoreRet04C05TwoJvmIdenticalRestock() throws Exception { ret04CTwoJvm(true); }
    @Test void qr07R25CoreRet04C06TwoJvmRestockVsDispose() throws Exception { ret04CTwoJvm(false); }

    private void ret04CTwoJvm(boolean replay) throws Exception {
        var f=ret04BFixture();var dir=java.nio.file.Files.createTempDirectory("ret04c-race-");
        var readyA=dir.resolve("ready-a");var readyB=dir.resolve("ready-b");
        var release=dir.resolve("release");var logA=dir.resolve("a.log");var logB=dir.resolve("b.log");
        Process a=null,b=null;
        try{
            a=startQr07R2Worker(ret04CMode(true,"HOLD"),f.c(),f.returnId(),6,f.requestKey(),readyA,release,logA);
            int blocker=ret04CAwaitPid(a,readyA,logA);
            b=startQr07R2Worker(ret04CMode(replay,"NORMAL"),f.c(),f.returnId(),6,
                    replay?f.requestKey():key(),readyB,java.nio.file.Path.of("-"),logB);
            int waiter=ret04CAwaitPid(b,readyB,logB);
            assertThat(waiter).isNotEqualTo(blocker);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            boolean observed=false;
            do{
                observed=Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,blocker,waiter));
                if(observed||!b.isAlive())break;
                Thread.sleep(25);
            }while(System.nanoTime()<deadline);
            assertThat(observed).as("independent JVM PostgreSQL blocking edge").isTrue();
            // The parent must still see the original committed data.
            ret04BAssertUnchanged(f,f.before());
            java.nio.file.Files.writeString(release,"COMMIT");
            assertThat(waitQr06bProcess(a,logA,120)).isZero();
            assertThat(waitQr06bProcess(b,logB,120)).isEqualTo(replay?0:88);
            var result=ret04CDurableResult(f);
            assertThat(java.nio.file.Files.readString(logA)).contains("RET04C_COMMITTED "+result);
            assertThat(java.nio.file.Files.readString(logB)).contains(replay?
                    "RET04C_COMMITTED "+result:"RET04C_DENIED QUARANTINE_REPLAY_CONFLICT");
            ret04BAssertResolved(f,true,result);
            System.out.printf("RET04C JVM schedule blocker=%d waiter=%d observed=true first=COMMITTED second=%s%n",
                    blocker,waiter,replay?"COMMITTED":"DENIED");
        }finally{ret04CCleanup(dir,a,b);}
    }

    @Test void qr07R25CoreRet04C07SharedSkuRestockRestock() throws Exception { ret04CShared(true,true); }
    @Test void qr07R25CoreRet04C08SharedSkuDisposeDispose() throws Exception { ret04CShared(false,false); }
    @Test void qr07R25CoreRet04C09SharedSkuRestockDispose() throws Exception { ret04CShared(true,false); }
    @Test void qr07R25CoreRet04C10SharedSkuDisposeRestock() throws Exception { ret04CShared(false,true); }

    private void ret04CShared(boolean restockA,boolean restockB) throws Exception {
        requirePostgres();
        var c=context();var orderA=ready(c,false);
        var claimA=qr07R25Quarantined(c,orderA);
        var orderB=ready(c,false);
        var claimB=qr07R2Approved(c,orderB,3);
        returnPhysical.receive(c.staff(),c.tenant(),c.warehouse(),claimB.requestId(),2,
                qr07PhysicalProof(c,claimB.requestId()),key());
        returnPhysical.inspect(c.staff(),c.tenant(),c.warehouse(),claimB.requestId(),4,
                SalesReturnPhysicalService.Disposition.QUARANTINE,"INSPECT_Q_SHARED",key());
        assertThat(returnPhysical.post(c.staff(),c.tenant(),c.warehouse(),claimB.requestId(),5,key()).state())
                .isEqualTo("QUARANTINED");
        roles.grant(c.owner(),c.tenant(),assignedRole(c),DomainPermission.INVENTORY_ADJUST);
        UUID sku=c.itemOne();
        assertThat(claimA.requestId()).isNotEqualTo(claimB.requestId());
        var intake=jdbc.queryForList("""
                SELECT return_id,warehouse_id,item_id,delta_quantity FROM crm.inv_return_quarantine_movement
                WHERE tenant_id=? ORDER BY delta_quantity
                """,c.tenant());
        assertThat(intake).hasSize(2);
        assertThat(intake.get(0)).containsEntry("return_id",claimA.requestId())
                .containsEntry("warehouse_id",c.warehouse()).containsEntry("item_id",sku);
        assertThat(intake.get(1)).containsEntry("return_id",claimB.requestId())
                .containsEntry("warehouse_id",c.warehouse()).containsEntry("item_id",sku);
        assertThat((BigDecimal)intake.get(0).get("delta_quantity")).isEqualByComparingTo("2");
        assertThat((BigDecimal)intake.get(1).get("delta_quantity")).isEqualByComparingTo("3");
        assertThat(ret04BQuarantine(c,sku)).isEqualByComparingTo("5");
        var before=ret04BSnapshot(c);BigDecimal sellable=stock(c,sku,"on_hand");
        var fA=new Ret04B(c,orderA,claimA.requestId(),sku,sellable,key(),before);
        var fB=new Ret04B(c,orderB,claimB.requestId(),sku,sellable,key(),before);
        var a=new java.util.concurrent.atomic.AtomicReference<SalesReturnQuarantineResolutionService.Resolved>();
        var b=new java.util.concurrent.atomic.AtomicReference<SalesReturnQuarantineResolutionService.Resolved>();
        PgCommitOrderEvidence.run(jdbc,transactionManager,
                ()->a.set(ret04BResolve(fA,restockA,6,"QA_B_EVIDENCE",fA.requestKey())),
                ()->b.set(ret04BResolve(fB,restockB,6,"QA_B_EVIDENCE",fB.requestKey())),
                IllegalStateException.class,null);
        ret04CSharedRow(fA,restockA,2,5,3,sellable,a.get());
        ret04CSharedRow(fB,restockB,3,3,0,sellable.add(BigDecimal.valueOf(restockA?2:0)),b.get());
        assertThat(a.get().resolutionId()).isNotEqualTo(b.get().resolutionId());
        assertThat(ret04BQuarantine(c,sku)).isEqualByComparingTo("0");
        assertThat(stock(c,sku,"on_hand")).isEqualByComparingTo(
                sellable.add(BigDecimal.valueOf((restockA?2:0)+(restockB?3:0))));
        var after=ret04BSnapshot(c);
        assertThat(after.get("sales_return_quarantine_resolution")).hasSize(2);
        assertThat(after.get("sales_return_event")).hasSize(before.get("sales_return_event").size()+2)
                .containsAll(before.get("sales_return_event"));
        assertThat(after.get("inv_stock_movement")).hasSize(before.get("inv_stock_movement").size()+
                (restockA?1:0)+(restockB?1:0)).containsAll(before.get("inv_stock_movement"));
        for(String table:before.keySet()){
            if(!List.of("inv_stock_balance","inv_stock_movement","inv_return_quarantine_balance",
                    "sales_return_request","sales_return_event","sales_return_quarantine_resolution").contains(table))
                assertThat(after.get(table)).as("immutable "+table).isEqualTo(before.get(table));
        }
        if(!restockA&&!restockB)assertThat(after.get("inv_stock_balance")).isEqualTo(before.get("inv_stock_balance"));
        assertThat(ret04BResolve(fA,restockA,6,"QA_B_EVIDENCE",fA.requestKey())).isEqualTo(a.get());
        assertThat(ret04BResolve(fB,restockB,6,"QA_B_EVIDENCE",fB.requestKey())).isEqualTo(b.get());
        ret04BAssertUnchanged(fA,after);
        assertExactlyOneHandover(c,orderB,1);
    }

    private void ret04CSharedRow(Ret04B f,boolean restock,int quantity,int qBefore,int qAfter,
            BigDecimal stockBefore,SalesReturnQuarantineResolutionService.Resolved result){
        assertThat(result).isNotNull().isEqualTo(ret04CDurableResult(f));
        assertThat(result.state()).isEqualTo(restock?"RESTOCKED":"DISPOSED");
        assertThat(result.version()).isEqualTo(7L);
        var row=jdbc.queryForMap("""
                SELECT action_code,request_key,evidence_ref,quantity,before_quantity,after_quantity,
                       warehouse_id,item_id,stock_movement_id
                FROM crm.sales_return_quarantine_resolution WHERE tenant_id=? AND return_id=?
                """,f.c().tenant(),f.returnId());
        assertThat(row).containsEntry("action_code",restock?"RESTOCK":"DISPOSE")
                .containsEntry("request_key",f.requestKey()).containsEntry("evidence_ref","QA_B_EVIDENCE")
                .containsEntry("warehouse_id",f.c().warehouse()).containsEntry("item_id",f.sku())
                .containsEntry("stock_movement_id",result.stockMovementId());
        assertThat((BigDecimal)row.get("quantity")).isEqualByComparingTo(BigDecimal.valueOf(quantity));
        assertThat((BigDecimal)row.get("before_quantity")).isEqualByComparingTo(BigDecimal.valueOf(qBefore));
        assertThat((BigDecimal)row.get("after_quantity")).isEqualByComparingTo(BigDecimal.valueOf(qAfter));
        var movements=jdbc.queryForList("""
                SELECT id,delta_quantity,balance_before,balance_after,location_id,item_id
                FROM crm.inv_stock_movement WHERE tenant_id=? AND request_key=?
                """,f.c().tenant(),"R07_"+f.returnId().toString().replace("-",""));
        if(restock){
            assertThat(result.stockMovementId()).isNotNull();
            assertThat(movements).hasSize(1);
            var m=movements.getFirst();
            assertThat(m).containsEntry("id",result.stockMovementId())
                    .containsEntry("location_id",f.c().warehouse()).containsEntry("item_id",f.sku());
            assertThat((BigDecimal)m.get("delta_quantity")).isEqualByComparingTo(BigDecimal.valueOf(quantity));
            assertThat((BigDecimal)m.get("balance_before")).isEqualByComparingTo(stockBefore);
            assertThat((BigDecimal)m.get("balance_after")).isEqualByComparingTo(stockBefore.add(BigDecimal.valueOf(quantity)));
        }else{
            assertThat(result.stockMovementId()).isNull();
            assertThat(movements).isEmpty();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_event WHERE tenant_id=? AND return_id=?
                    AND from_state='QUARANTINED' AND to_state=? AND request_key=? AND expected_row_version=6
                """,Integer.class,f.c().tenant(),f.returnId(),result.state(),f.requestKey())).isEqualTo(1);
    }

    @Test void qr07R25QuarantineResolutionTenantAndWarehouseScopeCannotCross()
            throws Exception {
        requirePostgres();
        var c=context();var other=context();var order=ready(c,false);
        var claim=qr07R25Quarantined(c,order);
        assertThatThrownBy(()->quarantineResolution.resolve(other.staff(),
                c.tenant(),c.warehouse(),claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.RESTOCK,
                "QA_CROSS_TENANT",key())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->quarantineResolution.resolve(c.staff(),
                other.tenant(),other.warehouse(),claim.requestId(),6,
                SalesReturnQuarantineResolutionService.Action.RESTOCK,
                "QA_CROSS_TENANT",key())).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_quarantine_resolution
                WHERE tenant_id=? AND return_id=?
                """,Integer.class,c.tenant(),claim.requestId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.sales_return_request
                WHERE tenant_id=? AND id=?
                """,String.class,c.tenant(),claim.requestId()))
                .isEqualTo("QUARANTINED");
    }

}
