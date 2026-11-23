package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
 "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class OrderPackagingQualificationTest {
 @Autowired OrderPackagingService service;
 @Autowired PackagingServiceCatalogService catalog;
 @Autowired AutoOrderService checkout;
 @Autowired VerifiedTenantRoleCommands roles;
 @Autowired UserAccountRepository users;
 @Autowired OrganizationRepository organizations;
 @Autowired WorkspaceRepository workspaces;
 @Autowired OrganizationMembershipRepository memberships;
 @Autowired EmailIdentityCrypto crypto;
 @Autowired JdbcTemplate jdbc;
 @Autowired DataSource dataSource;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

 record Fixture(UUID tenant,UUID order,String ref,VerifiedActor seller,VerifiedActor buyer,
                VerifiedActor owner,UUID roleId) {}
 private static String key(){return "PKG_"+UUID.randomUUID().toString().replace("-","");}
 private VerifiedActor verified(UUID id){
  String sub=jdbc.queryForObject("SELECT external_subject FROM public.iam_user_account WHERE id=?",String.class,id);
  JwtDecoder decoder=t->Jwt.withTokenValue(t).header("alg","RS256").subject(sub)
    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
  return new OidcActorResolver(decoder,users).resolve("synthetic-packaging-r0b");
 }
 private Fixture setup(){
  var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"Packaging Company","Packaging","KZ"));
  UUID tenant=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
  UUID owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"pkg-owner-"+UUID.randomUUID(),"Owner")).getId();
  UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"pkg-staff-"+UUID.randomUUID(),"Staff")).getId();
  UUID buyer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"pkg-buyer-"+UUID.randomUUID(),"Buyer")).getId();
  memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
  memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
  jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
  var admin=verified(owner);
  var role=roles.createRole(admin,tenant,"PKG_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),"Packaging staff");
  roles.grant(admin,tenant,role.id(),DomainPermission.SALES_PACKAGING_MANAGE);
  roles.assign(admin,tenant,staff,role.id());
  roles.enable(admin,tenant);
  VerifiedActor seller=verified(staff),buyerActor=verified(buyer);
  String email="pkg-buyer-"+UUID.randomUUID()+"@example.kz";
  Timestamp now=Timestamp.from(Instant.now());
  jdbc.update("""
    INSERT INTO public.iam_email_identity
     (id,user_id,lookup_hmac,email_envelope,verified_at,created_at,
      lifecycle_state,active_lookup_hmac,current_owner_id)
    VALUES (?,?,?,?,?,?,'CURRENT',?,?)
    """,UUID.randomUUID(),buyer,crypto.lookup(email),crypto.encrypt(email),
    now,now,crypto.lookup(email),buyer);
  String slug="pkg-"+UUID.randomUUID();UUID item=UUID.randomUUID(),offer=UUID.randomUUID();
  jdbc.update("""
    INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
    VALUES (?,?,?,'PUBLISHED',?)
    """,tenant,UUID.randomUUID(),slug,now);
  jdbc.update("""
    INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
    VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',?)
    """,tenant,item,"SKU-"+UUID.randomUUID(),now);
  jdbc.update("""
    INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state,catalog_item_id)
    VALUES (?,?,'Item',100,'KZT','PUBLISHED',?)
    """,tenant,offer,item);
  UUID order=checkout.checkout(slug,email,key(),List.of(new AutoOrderService.Line(offer,1)))
    .order().orderId();
  UUID challenge=jdbc.queryForObject("""
    SELECT id FROM public.iam_email_verification_challenge
    WHERE email_identity_id=(SELECT id FROM public.iam_email_identity
      WHERE user_id=? AND lifecycle_state='CURRENT')
    ORDER BY created_at DESC,id DESC LIMIT 1
    """,UUID.class,buyer);
  byte[] envelope=jdbc.queryForObject("""
    SELECT token_envelope FROM public.iam_email_verification_challenge WHERE id=?
    """,byte[].class,challenge);
  assertThat(checkout.verifyEmail(challenge,crypto.decrypt(envelope))).isEqualTo(buyer);
  String ref=jdbc.queryForObject("""
    SELECT public_ref FROM crm.sales_order_qr_reference
    WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
    """,String.class,tenant,order);
  catalog.publish(seller,tenant,"GIFT_WRAP","Gift wrap",new BigDecimal("500"),
    "KZT",new BigDecimal("0.1200"),"UAT_1",0);
  return new Fixture(tenant,order,ref,seller,buyerActor,admin,role.id());
 }
 private OrderPackagingService.Result offer(Fixture f,String key){
  return service.offer(f.seller(),f.tenant(),f.order(),"GIFT_WRAP",key);
 }
 private OrderPackagingService.Result decide(Fixture f,UUID line,long revision,
                   OrderPackagingService.Choice choice,String key){
  return service.decide(f.buyer(),f.ref(),line,revision,choice,key);
 }
 @Test void acceptedSnapshotSurvivesCatalogPriceChangeAndNeverDebitsStock(){
  var f=setup();var offered=offer(f,key());
  var accepted=decide(f,offered.serviceLineId(),0,OrderPackagingService.Choice.ACCEPT,key());
  assertThat(accepted.state()).isEqualTo("ACCEPTED");
  assertThat(accepted.price()).isEqualByComparingTo("500");
  catalog.publish(f.seller(),f.tenant(),"GIFT_WRAP","Gift wrap",new BigDecimal("900"),
    "KZT",new BigDecimal("0.1400"),"UAT_2",1);
  assertThat(jdbc.queryForObject("""
    SELECT price_snapshot FROM crm.sales_order_service_line
    WHERE tenant_id=? AND id=?
    """,BigDecimal.class,f.tenant(),accepted.serviceLineId())).isEqualByComparingTo("500");
  assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.inv_stock_movement
    WHERE tenant_id=?
    """,Integer.class,f.tenant())).isZero();
 }
 @Test void repeatOfferAndDecisionReturnOriginalReceiptButDifferentPayloadConflicts(){
  var f=setup();String offerKey=key(),decisionKey=key();
  var original=offer(f,offerKey);
  assertThat(offer(f,offerKey)).isEqualTo(original);
  var accepted=decide(f,original.serviceLineId(),0,OrderPackagingService.Choice.ACCEPT,decisionKey);
  assertThat(decide(f,original.serviceLineId(),0,OrderPackagingService.Choice.ACCEPT,decisionKey))
    .isEqualTo(accepted);
  assertThatThrownBy(()->decide(f,original.serviceLineId(),0,
    OrderPackagingService.Choice.REJECT,decisionKey))
    .hasMessage("PACKAGING_IDEMPOTENCY_CONFLICT");
  assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.sales_order_service_event
    WHERE tenant_id=? AND service_line_id=?
    """,Integer.class,f.tenant(),original.serviceLineId())).isEqualTo(2);
 }
 @Test void staleRevisionRejectedAndCancellationIsTerminal(){
  var f=setup();var offered=offer(f,key());
  var canceled=decide(f,offered.serviceLineId(),0,OrderPackagingService.Choice.CANCEL,key());
  assertThat(canceled.state()).isEqualTo("CANCELLED");
  assertThatThrownBy(()->decide(f,offered.serviceLineId(),0,
    OrderPackagingService.Choice.ACCEPT,key()))
    .hasMessage("PACKAGING_VERSION_CONFLICT");
 }
 @Test void foreignSellerAndBuyerCannotMutateDifferentTenant(){
  var a=setup();var b=setup();var offered=offer(a,key());
  assertThatThrownBy(()->service.offer(b.seller(),a.tenant(),a.order(),"GIFT_WRAP",key()))
    .isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->decide(b,offered.serviceLineId(),0,
    OrderPackagingService.Choice.ACCEPT,key()))
    .isInstanceOf(SecurityException.class);
 }
 @Test void concurrentAcceptRejectHasExactlyOneWinnerOnPostgres() throws Exception{
  try(var c=dataSource.getConnection()){
   Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
  }
  var f=setup();var offer=offer(f,key());var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var accepted=pool.submit(()->{
    start.await(10,TimeUnit.SECONDS);
    try{return decide(f,offer.serviceLineId(),0,OrderPackagingService.Choice.ACCEPT,key()).state();}
    catch(RuntimeException ex){return "LOSER";}
   });
   var rejected=pool.submit(()->{
    start.await(10,TimeUnit.SECONDS);
    try{return decide(f,offer.serviceLineId(),0,OrderPackagingService.Choice.REJECT,key()).state();}
    catch(RuntimeException ex){return "LOSER";}
   });
   start.countDown();
   var results=List.of(accepted.get(30,TimeUnit.SECONDS),rejected.get(30,TimeUnit.SECONDS));
   assertThat(results).contains("LOSER").hasSize(2);
   assertThat(results.stream().filter(x->!"LOSER".equals(x)).count()).isEqualTo(1);
  }
  assertThat(jdbc.queryForObject("""
   SELECT count(*) FROM crm.sales_order_service_event
   WHERE tenant_id=? AND service_line_id=?
   """,Integer.class,f.tenant(),offer.serviceLineId())).isEqualTo(2);
 }
 @Test void orderAlreadyPreparingCannotReceiveNewPackagingOffer(){
  var f=setup();
  jdbc.update("""
    UPDATE crm.sales_order SET status='PREPARING',row_version=row_version+1
    WHERE tenant_id=? AND id=?
    """,f.tenant(),f.order());
  assertThatThrownBy(()->offer(f,key()))
    .hasMessage("PACKAGING_ORDER_STATE_FORBIDDEN");
  assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.sales_order_service_line WHERE tenant_id=? AND order_id=?
    """,Integer.class,f.tenant(),f.order())).isZero();
 }
 @Test void buyerCannotAcceptAfterOrderPreparationStarted(){
  var f=setup();var offered=offer(f,key());
  jdbc.update("""
    UPDATE crm.sales_order SET status='PREPARING',row_version=row_version+1
    WHERE tenant_id=? AND id=?
    """,f.tenant(),f.order());
  assertThatThrownBy(()->decide(f,offered.serviceLineId(),0,
    OrderPackagingService.Choice.ACCEPT,key()))
    .hasMessage("PACKAGING_ORDER_STATE_FORBIDDEN");
  assertThat(jdbc.queryForObject("""
    SELECT status FROM crm.sales_order_service_line
    WHERE tenant_id=? AND id=?
    """,String.class,f.tenant(),offered.serviceLineId())).isEqualTo("OFFERED");
 }
 @Test void rejectedPackagingIsNotReacceptedOrCharged(){
  var f=setup();var offered=offer(f,key());
  var rejected=decide(f,offered.serviceLineId(),0,OrderPackagingService.Choice.REJECT,key());
  assertThat(rejected.state()).isEqualTo("REJECTED");
  assertThatThrownBy(()->decide(f,offered.serviceLineId(),1,
    OrderPackagingService.Choice.ACCEPT,key()))
    .hasMessage("PACKAGING_DECISION_TERMINAL");
  assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
    """,Integer.class,f.tenant())).isZero();
 }
 @Test void sameRequestKeyCannotBeReusedForDifferentServiceLine(){
  var f=setup();String common=key();var offered=offer(f,common);
  assertThatThrownBy(()->service.offer(f.seller(),f.tenant(),f.order(),
    "OTHER_WRAP",common)).hasMessage("PACKAGING_IDEMPOTENCY_CONFLICT");
  assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.sales_order_service_line
    WHERE tenant_id=? AND order_id=?
    """,Integer.class,f.tenant(),f.order())).isEqualTo(1);
 }
 @Test void postgresSnapshotAndEvidenceAreImmutableAtDatabaseLayer() throws Exception {
  try(var c=dataSource.getConnection()){
   Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
  }
  var f=setup();var offered=offer(f,key());
  assertThatThrownBy(()->jdbc.update("""
    UPDATE crm.sales_order_service_line SET price_snapshot=100
    WHERE tenant_id=? AND id=?
    """,f.tenant(),offered.serviceLineId())).isInstanceOf(Exception.class);
  assertThatThrownBy(()->jdbc.update("""
    DELETE FROM crm.sales_order_service_event
    WHERE tenant_id=? AND service_line_id=?
    """,f.tenant(),offered.serviceLineId())).isInstanceOf(Exception.class);
 }

 @Test void absentCatalogCodeRejectsOfferWithoutCreatingLine(){
  var f=setup();
  assertThatThrownBy(()->service.offer(f.seller(),f.tenant(),f.order(),"UNKNOWN",key()))
   .hasMessage("PACKAGING_CATALOG_INACTIVE");
  assertThat(lineCount(f)).isZero();
 }
 @Test void inactiveCatalogRejectsOfferAndKeepsHistoricalVersion(){
  var f=setup();
  var head=catalog.current(f.seller(),f.tenant(),"GIFT_WRAP");
  catalog.deactivate(f.seller(),f.tenant(),"GIFT_WRAP",1,head.rowVersion());
  assertThatThrownBy(()->offer(f,key())).hasMessage("PACKAGING_CATALOG_INACTIVE");
  assertThat(lineCount(f)).isZero();
 }
 @Test void catalogCurrencyMismatchDoesNotCreateServiceLine(){
  var f=setup();
  catalog.publish(f.seller(),f.tenant(),"GIFT_WRAP","Gift wrap",
   new BigDecimal("700"),"USD",new BigDecimal("0.12"),"UAT_2",1);
  assertThatThrownBy(()->offer(f,key())).hasMessage("PACKAGING_CURRENCY_MISMATCH");
  assertThat(lineCount(f)).isZero();
 }
 @Test void unlinkedBuyerCannotBeOfferedPackaging(){
  var f=setup();
  jdbc.update("UPDATE crm.sales_order SET buyer_user_id=NULL WHERE tenant_id=? AND id=?",
   f.tenant(),f.order());
  assertThatThrownBy(()->offer(f,key())).hasMessage("PACKAGING_BUYER_NOT_LINKED");
  assertThat(lineCount(f)).isZero();
 }
 @Test void secondOfferForSameOrderAndCodeIsRejectedByUniqueConstraint(){
  var f=setup();offer(f,key());
  assertThatThrownBy(()->offer(f,key())).isInstanceOf(
   org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(lineCount(f)).isEqualTo(1);
 }
 @Test void buyerCannotUseUnknownServiceLine(){
  var f=setup();offer(f,key());
  assertThatThrownBy(()->decide(f,UUID.randomUUID(),0,
   OrderPackagingService.Choice.ACCEPT,key()))
   .hasMessage("PACKAGING_NOT_ACCESSIBLE");
  assertThat(lineCount(f)).isEqualTo(1);
 }
 @Test void completedOrderRejectsNewPackagingOffer(){
  var f=setup();
  jdbc.update("UPDATE crm.sales_order SET status='COMPLETED' WHERE tenant_id=? AND id=?",
   f.tenant(),f.order());
  assertThatThrownBy(()->offer(f,key())).hasMessage("PACKAGING_ORDER_STATE_FORBIDDEN");
  assertThat(lineCount(f)).isZero();
 }
 @Test void repeatedCancelWithNewKeyCannotCreateSecondTerminalEvent(){
  var f=setup();var offered=offer(f,key());
  decide(f,offered.serviceLineId(),0,OrderPackagingService.Choice.CANCEL,key());
  assertThatThrownBy(()->decide(f,offered.serviceLineId(),1,
   OrderPackagingService.Choice.CANCEL,key()))
   .hasMessage("PACKAGING_DECISION_TERMINAL");
  assertThat(jdbc.queryForObject("""
   SELECT count(*) FROM crm.sales_order_service_event
   WHERE tenant_id=? AND service_line_id=?
   """,Integer.class,f.tenant(),offered.serviceLineId())).isEqualTo(2);
 }
 @Test void acceptAgainstCancelHasExactlyOneWinnerOnPostgres() throws Exception {
  try(var c=dataSource.getConnection()){
   Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
  }
  var f=setup();var offered=offer(f,key());var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var a=pool.submit(()->{
    start.await(10,TimeUnit.SECONDS);
    try{return decide(f,offered.serviceLineId(),0,OrderPackagingService.Choice.ACCEPT,key()).state();}
    catch(RuntimeException ex){return "LOSER";}
   });
   var b=pool.submit(()->{
    start.await(10,TimeUnit.SECONDS);
    try{return decide(f,offered.serviceLineId(),0,OrderPackagingService.Choice.CANCEL,key()).state();}
    catch(RuntimeException ex){return "LOSER";}
   });
   start.countDown();
   var states=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
   assertThat(states.stream().filter(x->"LOSER".equals(x)).count()).isEqualTo(1);
  }
  assertThat(jdbc.queryForObject("""
   SELECT count(*) FROM crm.sales_order_service_event
   WHERE tenant_id=? AND service_line_id=?
   """,Integer.class,f.tenant(),offered.serviceLineId())).isEqualTo(2);
 }
 private int lineCount(Fixture f){
  return jdbc.queryForObject("""
   SELECT count(*) FROM crm.sales_order_service_line WHERE tenant_id=? AND order_id=?
   """,Integer.class,f.tenant(),f.order());
 }

 @Test void sellerAccountSuspensionDeniesPackagingOfferWithoutWrites(){
  var f=setup();
  jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",
   f.seller().userId());
  assertThatThrownBy(()->offer(f,key())).isInstanceOf(SecurityException.class);
  assertThat(lineCount(f)).isZero();
 }
 @Test void sellerRoleRevocationDeniesPackagingOfferWithoutWrites(){
  var f=setup();
  roles.revoke(f.owner(),f.tenant(),f.roleId(),DomainPermission.SALES_PACKAGING_MANAGE);
  assertThatThrownBy(()->offer(f,key())).isInstanceOf(SecurityException.class);
  assertThat(lineCount(f)).isZero();
 }
 @Test void duplicateOfferAfterPostCommitRetryIsNotDuplicated(){
  var f=setup();String idempotencyKey=key();var a=offer(f,idempotencyKey);
  var b=offer(f,idempotencyKey);
  assertThat(b).isEqualTo(a);
  assertThat(lineCount(f)).isEqualTo(1);
  assertThat(jdbc.queryForObject("""
   SELECT count(*) FROM crm.sales_order_service_receipt
   WHERE tenant_id=? AND request_key=?
   """,Integer.class,f.tenant(),idempotencyKey)).isEqualTo(1);
 }
 @Test void receiptWriteFaultRollsBackEntireOfferAndCanBeRetriedOnPostgres() throws Exception{
  try(var c=dataSource.getConnection()){
   Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
  }
  var f=setup();
  String suffix=UUID.randomUUID().toString().replace("-","");
  String function="crm.qr08_test_receipt_fault_"+suffix;
  String trigger="qr08_receipt_fault_"+suffix;
  String requestKey=key();
  jdbc.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'QR08_RECEIPT_FAULT'; END $$");
  jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON crm.sales_order_service_receipt FOR EACH ROW EXECUTE FUNCTION "+function+"()");
  try {
   assertThatThrownBy(()->offer(f,requestKey)).hasMessageContaining("QR08_RECEIPT_FAULT");
   assertThat(lineCount(f)).isZero();
   assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.sales_order_service_receipt
    WHERE tenant_id=? AND request_key=?
    """,Integer.class,f.tenant(),requestKey)).isZero();
  }finally{
   jdbc.execute("DROP TRIGGER IF EXISTS "+trigger+" ON crm.sales_order_service_receipt");
   jdbc.execute("DROP FUNCTION IF EXISTS "+function+"()");
  }
  assertThat(offer(f,requestKey).state()).isEqualTo("OFFERED");
  assertThat(lineCount(f)).isEqualTo(1);
 }

 @Test void r0PackagingAcceptRejectCancelCannotCreateInventoryOrFinancialPosting(){
  for(OrderPackagingService.Choice choice:OrderPackagingService.Choice.values()){
   var f=setup();
   long movementBefore=jdbc.queryForObject("""
    SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
    """,Long.class,f.tenant());
   long reservationBefore=jdbc.queryForObject("""
    SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=?
    """,Long.class,f.tenant());
   long invoiceBefore=jdbc.queryForObject("""
    SELECT count(*) FROM crm.fin_invoice WHERE tenant_id=?
    """,Long.class,f.tenant());
   BigDecimal totalBefore=jdbc.queryForObject("""
    SELECT total_amount FROM crm.sales_order WHERE tenant_id=? AND id=?
    """,BigDecimal.class,f.tenant(),f.order());
   var offered=offer(f,key());
   decide(f,offered.serviceLineId(),0,choice,key());
   assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=?
    """,Long.class,f.tenant())).isEqualTo(movementBefore);
   assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=?
    """,Long.class,f.tenant())).isEqualTo(reservationBefore);
   assertThat(jdbc.queryForObject("""
    SELECT count(*) FROM crm.fin_invoice WHERE tenant_id=?
    """,Long.class,f.tenant())).isEqualTo(invoiceBefore);
   assertThat(jdbc.queryForObject("""
    SELECT total_amount FROM crm.sales_order WHERE tenant_id=? AND id=?
    """,BigDecimal.class,f.tenant(),f.order())).isEqualByComparingTo(totalBefore);
  }
 }

 @Test void buyerDecisionRequiresCurrentActiveQrReference(){
  var f=setup();var offered=offer(f,key());
  jdbc.update("""
   UPDATE crm.sales_order_qr_reference
   SET state='REVOKED',active_order_id=NULL,revoked_at=CURRENT_TIMESTAMP,row_version=row_version+1
   WHERE tenant_id=? AND order_id=? AND public_ref=? AND state='ACTIVE'
   """,f.tenant(),f.order(),f.ref());
  assertThatThrownBy(()->decide(f,offered.serviceLineId(),0,
   OrderPackagingService.Choice.ACCEPT,key()))
   .isInstanceOf(RuntimeException.class);
  assertThat(jdbc.queryForObject("""
   SELECT status FROM crm.sales_order_service_line WHERE tenant_id=? AND id=?
   """,String.class,f.tenant(),offered.serviceLineId())).isEqualTo("OFFERED");
 }
 @Test void catalogDeactivationCommitsBeforeWaitingOfferAndOfferFailsClosedOnPostgres() throws Exception {
  try(var c=dataSource.getConnection()){
   Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
  }
  var f=setup();
  var held=new CountDownLatch(1);
  var release=new CountDownLatch(1);
  var attempts=new CountDownLatch(1);
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  try(var pool=Executors.newFixedThreadPool(2)){
   var deactivation=pool.submit(()->tx.execute(status->{
    jdbc.queryForObject("""
     SELECT current_version FROM crm.sales_packaging_catalog_head
     WHERE tenant_id=? AND service_code='GIFT_WRAP' FOR UPDATE
     """,Integer.class,f.tenant());
    jdbc.update("""
     UPDATE crm.sales_packaging_catalog_head SET active=FALSE,row_version=row_version+1
     WHERE tenant_id=? AND service_code='GIFT_WRAP'
     """,f.tenant());
    held.countDown();
    try {if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("release timed out");}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    return true;
   }));
   assertThat(held.await(15,TimeUnit.SECONDS)).isTrue();
   var proposal=pool.submit(()->{
    attempts.countDown();
    try {offer(f,key());return "INCORRECTLY_OFFERED";}
    catch(IllegalStateException e){return e.getMessage();}
   });
   assertThat(attempts.await(10,TimeUnit.SECONDS)).isTrue();
   try {
    assertThatThrownBy(()->proposal.get(250,TimeUnit.MILLISECONDS))
     .isInstanceOf(java.util.concurrent.TimeoutException.class);
   } finally {release.countDown();}
   assertThat(deactivation.get(20,TimeUnit.SECONDS)).isTrue();
   assertThat(proposal.get(20,TimeUnit.SECONDS)).isEqualTo("PACKAGING_CATALOG_INACTIVE");
  }
  assertThat(lineCount(f)).isZero();
 }

}
