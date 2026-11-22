package kz.rm.crm;

import kz.rm.iam.*;
import kz.rm.security.kms.TenantKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={"RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CrmBuyerLinkQualificationTest {
    @Autowired CrmBuyerLinkService links;
    @Autowired AuthenticatedCrmCommands customers;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired TenantKeyService keys;
    @Autowired JdbcTemplate jdbc;
    @Autowired GlobalAccountStatusService accounts;
    @Autowired GlobalAccountDeletionService deletion;
    @Autowired EmailIdentityService emailIdentity;
    @Autowired EmailIdentityCrypto emailCrypto;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    record Seller(UUID tenant, UUID owner) {}
    Seller seller() {
        var user=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"crm-link-owner-"+UUID.randomUUID(),"CRM owner"));
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Link LLP","Link","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(
                UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),user.getId(),OrganizationMembershipEntity.Role.OWNER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        keys.provision(workspace.getId());
        return new Seller(workspace.getId(),user.getId());
    }
    UUID independentReviewer(Seller seller) {
        UUID org=jdbc.queryForObject("""
                SELECT organization_id FROM public.iam_workspace WHERE id=?
                """,UUID.class,seller.tenant());
        UUID reviewer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "crm-reviewer-"+UUID.randomUUID(),"Independent CRM reviewer")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org,reviewer,OrganizationMembershipEntity.Role.OWNER));
        return reviewer;
    }
    UUID platformAdmin() {
        UUID admin=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "crm-admin-"+UUID.randomUUID(),"Platform admin")).getId();
        jdbc.update("""
                INSERT INTO public.iam_global_role_assignment
                (id,user_id,role,granted_at)
                VALUES (?,?,'PLATFORM_ADMIN',?)
                """,UUID.randomUUID(),admin,java.sql.Timestamp.from(Instant.now()));
        return admin;
    }
    UUID buyer() {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "crm-link-buyer-"+UUID.randomUUID(),"Buyer")).getId();
    }
    VerifiedActor verified(UUID id) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }
    UUID seedSalesOrder(UUID tenant,UUID buyer) {
        jdbc.update("""
                INSERT INTO crm.store_front
                (tenant_id,id,slug,state,created_at,row_version)
                VALUES (?,?,?,'DRAFT',?,0)
                """,tenant,UUID.randomUUID(),"link-"+UUID.randomUUID(),java.sql.Timestamp.from(Instant.now()));
        UUID identity=jdbc.query("""
                SELECT id FROM public.iam_email_identity WHERE user_id=?
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,buyer);
        if(identity==null) {
            identity=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO public.iam_email_identity
                    (id,user_id,lookup_hmac,email_envelope,verified_at,created_at,
                     lifecycle_state,active_lookup_hmac,current_owner_id)
                    VALUES (?,?,?,?,?,?,'CURRENT',?,?)
                    """,identity,buyer,UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new byte[]{1,2,3},java.sql.Timestamp.from(Instant.now()),
                    java.sql.Timestamp.from(Instant.now()),
                    UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),buyer);
        }
        UUID order=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_order
                (tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,row_version)
                VALUES (?,?,?,?,'PLACED',0,'KZT',?,0)
                """,tenant,order,identity,buyer,java.sql.Timestamp.from(Instant.now()));
        return order;
    }

    @Test void sellerProposalIsIdempotentAndRequiresExplicitBuyerConfirmation() {
        var s=seller(); UUID b=buyer();var owner=verified(s.owner());
        var c=customers.createCompany(owner,s.tenant(),"Unlinked customer",null);
        String key="buyer-propose-"+UUID.randomUUID();
        var proposed=links.propose(owner,s.tenant(),c.id(),b,key);
        assertThat(proposed.state()).isEqualTo(CrmBuyerLinkService.State.PENDING);
        assertThat(links.propose(owner,s.tenant(),c.id(),b,key).id()).isEqualTo(proposed.id());
        assertThat(links.orderHistory(owner,s.tenant(),c.id(),20)).isEmpty();
        assertThatThrownBy(()->links.propose(owner,s.tenant(),c.id(),buyer(),key))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->links.confirm(owner,s.tenant(),proposed.id(),0))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->links.confirm(verified(b),s.tenant(),proposed.id(),0))
                .isInstanceOf(SecurityException.class); // no buyer-owned order
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link_event
                WHERE tenant_id=? AND link_id=?
                """,Integer.class,s.tenant(),proposed.id())).isEqualTo(1);
        assertThat(links.linkForCustomer(owner,s.tenant(),c.id()).rowVersion()).isZero();
    }

    @Test void verifiedBuyerConfirmsOrderAndSellerSeesOnlyTenantOrders() {
        var first=seller(); var second=seller(); UUID b=buyer();
        var owner=verified(first.owner());
        var c=customers.createCompany(owner,first.tenant(),"Qualified buyer",null);
        var linked=links.propose(owner,first.tenant(),c.id(),b,
                "buyer-link-"+UUID.randomUUID());
        UUID firstOrder=seedSalesOrder(first.tenant(),b);
        seedSalesOrder(second.tenant(),b);
        var confirmed=links.confirm(verified(b),first.tenant(),linked.id(),0);
        assertThat(confirmed.state()).isEqualTo(CrmBuyerLinkService.State.CONFIRMED);
        assertThat(confirmed.rowVersion()).isEqualTo(1);
        assertThat(links.confirm(verified(b),first.tenant(),linked.id(),1).id())
                .isEqualTo(linked.id()); // same confirmed version is harmless replay
        assertThatThrownBy(()->links.confirm(verified(b),first.tenant(),linked.id(),0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(links.orderHistory(owner,first.tenant(),c.id(),10))
                .extracting(CrmBuyerLinkService.BuyerOrder::orderId).containsExactly(firstOrder);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link_event
                WHERE tenant_id=? AND link_id=? AND action_code='CONFIRMED'
                """,Integer.class,first.tenant(),linked.id())).isEqualTo(1);
    }

    @Test void tenantSeparationAndOneToOneConstraintsPreventSilentIdentityMerge() {
        var a=seller();var b=seller(); UUID buyer=buyer();
        var ownerA=verified(a.owner());var ownerB=verified(b.owner());
        var ca=customers.createCompany(ownerA,a.tenant(),"Tenant A",null);
        var cb=customers.createCompany(ownerB,b.tenant(),"Tenant B",null);
        var aLink=links.propose(ownerA,a.tenant(),ca.id(),buyer,
                "link-a-"+UUID.randomUUID());
        assertThat(links.linkForCustomer(ownerB,b.tenant(),ca.id())).isNull();
        assertThatThrownBy(()->links.propose(ownerB,b.tenant(),ca.id(),buyer,
                "foreign-"+UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->links.confirm(verified(buyer),b.tenant(),aLink.id(),0))
                .isInstanceOf(SecurityException.class);
        var other=customers.createCompany(ownerA,a.tenant(),"Other same-tenant customer",null);
        assertThatThrownBy(()->links.propose(ownerA,a.tenant(),other.id(),buyer,
                "collision-"+UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(links.propose(ownerB,b.tenant(),cb.id(),buyer,
                "link-b-"+UUID.randomUUID()).state())
                .isEqualTo(CrmBuyerLinkService.State.PENDING);
        assertThat(links.orderHistory(ownerA,a.tenant(),ca.id(),10)).isEmpty();
    }

    @Test void archivedCustomerCannotBeLinkedOrExposeOrderHistory() {
        var s=seller();var owner=verified(s.owner()); UUID b=buyer();
        var c=customers.createCompany(owner,s.tenant(),"Archive boundary",null);
        var link=links.propose(owner,s.tenant(),c.id(),b,
                "archive-link-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        customers.archiveCustomer(owner,s.tenant(),c.id(),0);
        assertThatThrownBy(()->links.confirm(verified(b),s.tenant(),link.id(),0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(links.orderHistory(owner,s.tenant(),c.id(),10)).isEmpty();
        assertThat(links.linkForCustomer(owner,s.tenant(),c.id()).rowVersion()).isZero();
    }
    @Test void postgresBuyerLinkEventsCannotBeUpdatedOrDeleted() throws Exception {
        requirePostgres();
        var s=seller();var owner=verified(s.owner());var b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Immutable buyer evidence",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "immutable-"+UUID.randomUUID());
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.customer_buyer_link_event SET action_code='CONFIRMED'
                WHERE tenant_id=? AND link_id=?
                """,s.tenant(),link.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.customer_buyer_link_event
                WHERE tenant_id=? AND link_id=?
                """,s.tenant(),link.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link_event
                WHERE tenant_id=? AND link_id=? AND action_code='PROPOSED'
                """,Integer.class,s.tenant(),link.id())).isEqualTo(1);
    }

    @Test void postgresConcurrentProposalsPreserveOneCustomerOneBuyer() throws Exception {
        requirePostgres();
        var s=seller();var owner=verified(s.owner());
        var customer=customers.createCompany(owner,s.tenant(),"Parallel buyer linking",null);
        UUID firstBuyer=buyer(),secondBuyer=buyer();
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> first=()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Race barrier timeout");
                try {
                    links.propose(owner,s.tenant(),customer.id(),firstBuyer,
                            "parallel-one-"+UUID.randomUUID());
                    return true;
                } catch(IllegalStateException expected) { return false; }
            };
            java.util.concurrent.Callable<Boolean> second=()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Race barrier timeout");
                try {
                    links.propose(owner,s.tenant(),customer.id(),secondBuyer,
                            "parallel-two-"+UUID.randomUUID());
                    return true;
                } catch(IllegalStateException expected) { return false; }
            };
            var a=pool.submit(first);var b=pool.submit(second);
            start.countDown();
            assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)
                    ^ b.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link
                WHERE tenant_id=? AND customer_id=?
                """,Integer.class,s.tenant(),customer.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link_event e
                JOIN crm.customer_buyer_link l
                  ON l.tenant_id=e.tenant_id AND l.id=e.link_id
                WHERE l.tenant_id=? AND l.customer_id=?
                """,Integer.class,s.tenant(),customer.id())).isEqualTo(1);
    }

    @Test void postgresConcurrentBuyerConfirmHasOneCommittedEvent() throws Exception {
        requirePostgres();
        var s=seller();var owner=verified(s.owner());
        UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Confirm race",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "confirm-race-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        var verifiedBuyer=verified(b);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> attempt=()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Race barrier timeout");
                try {
                    links.confirm(verifiedBuyer,s.tenant(),link.id(),0);
                    return true;
                } catch(IllegalStateException expected) { return false; }
            };
            var first=pool.submit(attempt);
            var second=pool.submit(attempt);
            start.countDown();
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)
                    ^ second.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(links.linkForCustomer(owner,s.tenant(),customer.id()).state())
                .isEqualTo(CrmBuyerLinkService.State.CONFIRMED);
        assertThat(links.linkForCustomer(owner,s.tenant(),customer.id()).rowVersion())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link_event
                WHERE tenant_id=? AND link_id=? AND action_code='CONFIRMED'
                """,Integer.class,s.tenant(),link.id())).isEqualTo(1);
    }


    @Test void buyerRevocationStopsHistoryAndAllowsNewExplicitConsent() {
        var s=seller();var owner=verified(s.owner());UUID first=buyer(),second=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Lifecycle customer",null);
        var prior=links.propose(owner,s.tenant(),customer.id(),first,
                "lifecycle-prior-"+UUID.randomUUID());
        UUID order=seedSalesOrder(s.tenant(),first);
        links.confirm(verified(first),s.tenant(),prior.id(),0);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10))
                .extracting(CrmBuyerLinkService.BuyerOrder::orderId).containsExactly(order);
        var revoked=links.revokeByBuyer(verified(first),s.tenant(),prior.id(),1);
        assertThat(revoked.state()).isEqualTo(CrmBuyerLinkService.State.REVOKED);
        assertThat(revoked.rowVersion()).isEqualTo(2);
        assertThat(links.linkForCustomer(owner,s.tenant(),customer.id())).isNull();
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThatThrownBy(()->links.confirm(verified(first),s.tenant(),prior.id(),2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->links.revokeByBuyer(verified(first),s.tenant(),prior.id(),1))
                .isInstanceOf(IllegalStateException.class);
        var next=links.propose(owner,s.tenant(),customer.id(),second,
                "lifecycle-next-"+UUID.randomUUID());
        assertThat(next.id()).isNotEqualTo(prior.id());
        assertThat(next.state()).isEqualTo(CrmBuyerLinkService.State.PENDING);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThat(links.linkHistory(owner,s.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactly("PROPOSED","CONFIRMED","REVOKED","PROPOSED");
    }

    @Test void disputeFreezesSalesVisibilityAndSellerCanOnlyCloseFailClosed() {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Disputed identity",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "dispute-link-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        links.confirm(verified(b),s.tenant(),link.id(),0);
        assertThatThrownBy(()->links.disputeByBuyer(owner,s.tenant(),link.id(),1))
                .isInstanceOf(SecurityException.class);
        var disputed=links.disputeByBuyer(verified(b),s.tenant(),link.id(),1);
        assertThat(disputed.state()).isEqualTo(CrmBuyerLinkService.State.DISPUTED);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThatThrownBy(()->links.releaseBySeller(owner,s.tenant(),link.id(),2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->links.closeDispute(owner,s.tenant(),link.id(),2))
                .isInstanceOf(SecurityException.class);
        var reviewer=verified(independentReviewer(s));
        var closed=links.adjudicateDispute(reviewer,s.tenant(),link.id(),2,
                CrmBuyerLinkService.DisputeReason.IDENTITY_MISMATCH);
        assertThat(closed.state()).isEqualTo(CrmBuyerLinkService.State.REVOKED);
        assertThat(links.linkForCustomer(owner,s.tenant(),customer.id())).isNull();
        assertThat(links.linkHistory(owner,s.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactly("PROPOSED","CONFIRMED","DISPUTED","DISPUTE_CLOSED");
        assertThatThrownBy(()->links.closeDispute(reviewer,s.tenant(),link.id(),3))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT reason_code FROM crm.customer_buyer_dispute_decision
                WHERE tenant_id=? AND link_id=?
                """,String.class,s.tenant(),link.id())).isEqualTo("IDENTITY_MISMATCH");
    }

    @Test void sellerReleaseCannotForgeNewBuyerConsentAndHistoryStaysImmutable() {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Seller release",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "seller-release-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        links.confirm(verified(b),s.tenant(),link.id(),0);
        var released=links.releaseBySeller(owner,s.tenant(),link.id(),1);
        assertThat(released.state()).isEqualTo(CrmBuyerLinkService.State.REVOKED);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThatThrownBy(()->links.confirm(verified(b),s.tenant(),link.id(),2))
                .isInstanceOf(IllegalStateException.class);
        var fresh=links.propose(owner,s.tenant(),customer.id(),b,
                "seller-reproposal-"+UUID.randomUUID());
        assertThat(fresh.state()).isEqualTo(CrmBuyerLinkService.State.PENDING);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThat(links.linkHistory(owner,s.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactly("PROPOSED","CONFIRMED","REVOKED","PROPOSED");
    }

    @Test void foreignTenantAndStaleRevisionCannotRevokeOrResolve() {
        var a=seller();var b=seller();var sellerA=verified(a.owner());
        UUID buyer=buyer();
        var customer=customers.createCompany(sellerA,a.tenant(),"Protected link",null);
        var link=links.propose(sellerA,a.tenant(),customer.id(),buyer,
                "protected-link-"+UUID.randomUUID());
        assertThatThrownBy(()->links.releaseBySeller(verified(b.owner()),
                a.tenant(),link.id(),0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->links.revokeByBuyer(verified(buyer),
                b.tenant(),link.id(),0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->links.revokeByBuyer(verified(buyer),
                a.tenant(),link.id(),-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->links.closeDispute(verified(independentReviewer(a)),
                a.tenant(),link.id(),0)).isInstanceOf(IllegalStateException.class);
        assertThat(links.linkForCustomer(sellerA,a.tenant(),customer.id()).rowVersion()).isZero();
        assertThat(links.linkHistory(sellerA,a.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action).containsExactly("PROPOSED");
    }

    @Test void postgresOrderedConfirmVersusRevokeAdmitsOneCoherentHistory() throws Exception {
        requirePostgres();
        assertOrderedConfirmRevoke(true);
        assertOrderedConfirmRevoke(false);
    }

    private void assertOrderedConfirmRevoke(boolean confirmFirst) throws Exception {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Ordered revoke",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "ordered-link-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        var authenticatedBuyer=verified(b);
        var changed=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var secondStarted=new java.util.concurrent.CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->tx.execute(status->{
                if(confirmFirst) links.confirm(authenticatedBuyer,s.tenant(),link.id(),0);
                else links.revokeByBuyer(authenticatedBuyer,s.tenant(),link.id(),0);
                changed.countDown();
                try {
                    if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Commit barrier timeout");
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
            assertThat(changed.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{
                secondStarted.countDown();
                try {
                    if(confirmFirst) links.revokeByBuyer(authenticatedBuyer,s.tenant(),link.id(),0);
                    else links.confirm(authenticatedBuyer,s.tenant(),link.id(),0);
                    return true;
                } catch(IllegalStateException expected) {return false;}
            });
            assertThat(secondStarted.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->second.get(300,java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {release.countDown();}
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        }
        var history=links.linkHistory(owner,s.tenant(),customer.id());
        assertThat(history).hasSize(2);
        assertThat(history).extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactly("PROPOSED",confirmFirst?"CONFIRMED":"REVOKED");
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10).isEmpty())
                .isEqualTo(!confirmFirst);
    }


    @Test void suspendedBuyerHidesSellerHistoryAndPreservesLinkEvidence() {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Suspended buyer history",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "suspend-hist-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        var buyerActor=verified(b);
        links.confirm(buyerActor,s.tenant(),link.id(),0);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).hasSize(1);
        accounts.suspendAccount(verified(platformAdmin()),b);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThatThrownBy(()->links.disputeByBuyer(buyerActor,s.tenant(),link.id(),1))
                .isInstanceOf(SecurityException.class);
        assertThat(links.linkForCustomer(owner,s.tenant(),customer.id()).state())
                .isEqualTo(CrmBuyerLinkService.State.CONFIRMED);
        assertThat(links.linkHistory(owner,s.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactly("PROPOSED","CONFIRMED");
    }

    @Test void deletedAccountFailClosedWithoutDeletingLinkedHistory() {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Deleted buyer history",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "delete-fixture-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        var buyerActor=verified(b);
        links.confirm(buyerActor,s.tenant(),link.id(),0);
        // Synthetic DELETED fixture: IAM has no trusted account-deletion service
        // yet. Do not simulate a successful administrative deletion command.
        jdbc.update("""
                UPDATE public.iam_user_account SET status='DELETED',row_version=row_version+1
                WHERE id=? AND status='ACTIVE'
                """,b);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThatThrownBy(()->links.confirm(buyerActor,s.tenant(),link.id(),1))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->jdbc.update(
                "DELETE FROM public.iam_user_account WHERE id=?",b))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(links.linkHistory(owner,s.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactly("PROPOSED","CONFIRMED");
    }

    @Test void postgresAdjudicationEvidenceIsImmutableAndNotSelfReviewed() throws Exception {
        requirePostgres();
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Independent adjudication",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "adjudication-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        links.confirm(verified(b),s.tenant(),link.id(),0);
        links.disputeByBuyer(verified(b),s.tenant(),link.id(),1);
        assertThatThrownBy(()->links.adjudicateDispute(owner,s.tenant(),link.id(),2,
                CrmBuyerLinkService.DisputeReason.BUYER_DENIED))
                .isInstanceOf(SecurityException.class);
        var reviewer=verified(independentReviewer(s));
        var resolved=links.adjudicateDispute(reviewer,s.tenant(),link.id(),2,
                CrmBuyerLinkService.DisputeReason.BUYER_DENIED);
        assertThat(resolved.state()).isEqualTo(CrmBuyerLinkService.State.REVOKED);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.customer_buyer_dispute_decision SET reason_code='IDENTITY_MISMATCH'
                WHERE tenant_id=? AND link_id=?
                """,s.tenant(),link.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.customer_buyer_dispute_decision
                WHERE tenant_id=? AND link_id=?
                """,s.tenant(),link.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT reason_code FROM crm.customer_buyer_dispute_decision
                WHERE tenant_id=? AND link_id=?
                """,String.class,s.tenant(),link.id())).isEqualTo("BUYER_DENIED");
    }

    @Test void postgresBothCommitOrdersForBuyerSuspensionAndConfirmation() throws Exception {
        requirePostgres();
        assertSuspensionConfirmationOrder(true);
        assertSuspensionConfirmationOrder(false);
    }

    private void assertSuspensionConfirmationOrder(boolean confirmFirst) throws Exception {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Suspend versus confirm",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "susp-race-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        var buyerActor=verified(b);
        var administrator=verified(platformAdmin());
        var admitted=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var started=new java.util.concurrent.CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->tx.execute(status->{
                if(confirmFirst) links.confirm(buyerActor,s.tenant(),link.id(),0);
                else accounts.suspendAccount(administrator,b);
                admitted.countDown();
                try{
                    if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Suspension race timeout");
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
            assertThat(admitted.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{
                started.countDown();
                try{
                    if(confirmFirst) accounts.suspendAccount(administrator,b);
                    else links.confirm(buyerActor,s.tenant(),link.id(),0);
                    return true;
                }catch(SecurityException denied){return false;}
            });
            assertThat(started.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try{
                assertThatThrownBy(()->second.get(300,java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            }finally{release.countDown();}
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20,java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(confirmFirst);
        }
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThat(links.linkForCustomer(owner,s.tenant(),customer.id()).state())
                .isEqualTo(confirmFirst?CrmBuyerLinkService.State.CONFIRMED:
                        CrmBuyerLinkService.State.PENDING);
        assertThat(links.linkHistory(owner,s.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactlyElementsOf(confirmFirst
                        ?java.util.List.of("PROPOSED","CONFIRMED")
                        :java.util.List.of("PROPOSED"));
    }


    @Test void accountDeletionRetainsSalesAndCrmHistoryButScrubsLiveIamIdentity() {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Deletion retention",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "delete-real-"+UUID.randomUUID());
        UUID order=seedSalesOrder(s.tenant(),b);
        var buyerActor=verified(b);
        links.confirm(buyerActor,s.tenant(),link.id(),0);
        jdbc.update("""
                INSERT INTO iam_pii.user_profile_secret
                (user_id,display_name_envelope,phone_envelope,phone_lookup_hmac,
                 row_version,created_at,updated_at)
                VALUES (?,?,?,?,0,?,?)
                """,b,new byte[]{1,2,3},new byte[]{4,5,6},
                UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.sql.Timestamp.from(Instant.now()),java.sql.Timestamp.from(Instant.now()));
        UUID adminId=platformAdmin();
        var admin=verified(adminId);
        String key="delete-account-"+UUID.randomUUID();
        deletion.deleteAccount(admin,b,key);
        deletion.deleteAccount(admin,b,key); // same request is idempotent
        assertThatThrownBy(()->deletion.deleteAccount(admin,b,"new-key-"+UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,String.class,b)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_user_account
                WHERE id=? AND external_subject IS NULL AND email_normalized IS NULL
                  AND display_name IS NULL AND phone_e164 IS NULL
                """,Integer.class,b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM iam_pii.user_profile_secret WHERE user_id=?
                """,Integer.class,b)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity
                WHERE user_id=? AND lifecycle_state='HISTORICAL'
                AND current_owner_id IS NULL AND pending_owner_id IS NULL
                AND active_lookup_hmac IS NULL
                """,Integer.class,b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order
                WHERE tenant_id=? AND id=? AND buyer_user_id=?
                """,Integer.class,s.tenant(),order,b)).isEqualTo(1);
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThat(links.linkHistory(owner,s.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactly("PROPOSED","CONFIRMED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_account_deletion_event WHERE target_user_id=?
                """,Integer.class,b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM public.iam_deletion_oidc_revocation_outbox
                WHERE target_user_id=?
                """,String.class,b)).isEqualTo("PENDING");
        byte[] subjectEnvelope=jdbc.queryForObject("""
                SELECT provider_subject_envelope FROM public.iam_deletion_oidc_revocation_outbox
                WHERE target_user_id=?
                """,byte[].class,b);
        assertThat(subjectEnvelope).isNotNull();
        assertThat(emailCrypto.decrypt(subjectEnvelope)).startsWith("crm-link-buyer-");
        assertThat(new String(subjectEnvelope,java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("crm-link-buyer-");

        assertThatThrownBy(()->links.revokeByBuyer(buyerActor,s.tenant(),link.id(),1))
                .isInstanceOf(SecurityException.class);
    }

    @Test void accountDeletionRejectsUnprivilegedActorAndOwnerWithoutSideEffects() {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Privileged deletion",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "del-denied-"+UUID.randomUUID());
        assertThatThrownBy(()->deletion.deleteAccount(owner,b,
                "bad-deletion-"+UUID.randomUUID())).isInstanceOf(SecurityException.class);
        var admin=verified(platformAdmin());
        assertThatThrownBy(()->deletion.deleteAccount(admin,s.owner(),
                "owner-deletion-"+UUID.randomUUID())).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_account_deletion_event
                WHERE target_user_id IN (?,?)
                """,Integer.class,b,s.owner())).isZero();
        assertThat(links.linkForCustomer(owner,s.tenant(),customer.id()).id())
                .isEqualTo(link.id());
    }

    @Test void postgresDeletedAccountAuditCannotBeTamperedWith() throws Exception {
        requirePostgres();
        var b=buyer();var admin=verified(platformAdmin());
        deletion.deleteAccount(admin,b,"audit-deletion-"+UUID.randomUUID());
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM public.iam_account_deletion_event WHERE target_user_id=?
                """,b)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE public.iam_account_deletion_event SET request_key='forged'
                WHERE target_user_id=?
                """,b)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_account_deletion_event WHERE target_user_id=?
                """,Integer.class,b)).isEqualTo(1);
    }

    @Test void postgresBothCommitOrdersForDeleteVersusConfirm() throws Exception {
        requirePostgres();
        assertDeleteConfirmOrder(true);
        assertDeleteConfirmOrder(false);
    }

    private void assertDeleteConfirmOrder(boolean confirmFirst) throws Exception {
        var s=seller();var owner=verified(s.owner());UUID b=buyer();
        var customer=customers.createCompany(owner,s.tenant(),"Delete race",null);
        var link=links.propose(owner,s.tenant(),customer.id(),b,
                "del-race-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        var buyerActor=verified(b);
        var admin=verified(platformAdmin());
        String key="del-race-key-"+UUID.randomUUID();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var challengerStarted=new java.util.concurrent.CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->tx.execute(status->{
                if(confirmFirst) links.confirm(buyerActor,s.tenant(),link.id(),0);
                else deletion.deleteAccount(admin,b,key);
                entered.countDown();
                try {
                    if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Delete fence barrier timed out");
                }catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{
                challengerStarted.countDown();
                try{
                    if(confirmFirst) deletion.deleteAccount(admin,b,key);
                    else links.confirm(buyerActor,s.tenant(),link.id(),0);
                    return true;
                }catch(SecurityException denied){return false;}
            });
            assertThat(challengerStarted.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->second.get(300,java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            }finally {release.countDown();}
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20,java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(confirmFirst);
        }
        assertThat(jdbc.queryForObject("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,String.class,b)).isEqualTo("DELETED");
        assertThat(links.orderHistory(owner,s.tenant(),customer.id(),10)).isEmpty();
        assertThat(links.linkForCustomer(owner,s.tenant(),customer.id()).state())
                .isEqualTo(confirmFirst?CrmBuyerLinkService.State.CONFIRMED:
                        CrmBuyerLinkService.State.PENDING);
        assertThat(links.linkHistory(owner,s.tenant(),customer.id()))
                .extracting(CrmBuyerLinkService.LinkEvent::action)
                .containsExactlyElementsOf(confirmFirst
                        ?java.util.List.of("PROPOSED","CONFIRMED")
                        :java.util.List.of("PROPOSED"));
    }


    @Test void postgresDeletionOutboxFailureRollsBackIdentityScrubAndCanRetry() throws Exception {
        requirePostgres();
        var b=buyer();var admin=verified(platformAdmin());
        String before=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,b);
        jdbc.execute("""
                CREATE FUNCTION public.crm_r22e_injected_outbox_failure()
                RETURNS trigger LANGUAGE plpgsql AS $
                BEGIN
                  RAISE EXCEPTION 'injected deletion outbox failure';
                END; $
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_crm_r22e_injected_outbox_failure
                BEFORE INSERT ON public.iam_deletion_oidc_revocation_outbox
                FOR EACH ROW EXECUTE FUNCTION public.crm_r22e_injected_outbox_failure()
                """);
        try {
            assertThatThrownBy(()->deletion.deleteAccount(admin,b,
                    "failure-before-commit-"+UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("""
                    DROP TRIGGER trg_crm_r22e_injected_outbox_failure
                    ON public.iam_deletion_oidc_revocation_outbox
                    """);
            jdbc.execute("DROP FUNCTION public.crm_r22e_injected_outbox_failure()");
        }
        assertThat(jdbc.queryForObject("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,String.class,b)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,b)).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_account_deletion_event WHERE target_user_id=?
                """,Integer.class,b)).isZero();
        deletion.deleteAccount(admin,b,"retry-after-failure-"+UUID.randomUUID());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_deletion_oidc_revocation_outbox
                WHERE target_user_id=? AND state='PENDING'
                """,Integer.class,b)).isEqualTo(1);
    }

    @Test void postgresBothCommitOrdersForDeleteVersusEmailConfirmation() throws Exception {
        requirePostgres();
        assertEmailDeleteOrder(true);
        assertEmailDeleteOrder(false);
    }

    private void assertEmailDeleteOrder(boolean emailFirst) throws Exception {
        UUID target=buyer();
        var buyerActor=verified(target);
        // Create a valid verified CURRENT identity through the existing IAM schema.
        var tenant=seller();
        seedSalesOrder(tenant.tenant(),target);
        String address="new-"+UUID.randomUUID()+"@example.kz";
        emailIdentity.requestEmailChange(buyerActor,address);
        var proof=jdbc.query("""
                SELECT c.id,c.token_envelope
                FROM public.iam_email_verification_challenge c
                JOIN public.iam_email_identity i ON i.id=c.email_identity_id
                WHERE i.pending_owner_id=? AND c.purpose='CHANGE_EMAIL'
                ORDER BY c.created_at DESC LIMIT 1
                """,rs->{
                    if(!rs.next())throw new IllegalStateException("Missing email change proof");
                    return new java.util.AbstractMap.SimpleEntry<>(
                            rs.getObject(1,UUID.class),emailCrypto.decrypt(rs.getBytes(2)));
                },target);
        var admin=verified(platformAdmin());
        String request="email-delete-"+UUID.randomUUID();
        var firstEntered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var secondStarted=new java.util.concurrent.CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->tx.execute(status->{
                if(emailFirst) emailIdentity.confirmEmailChange(
                        buyerActor,proof.getKey(),proof.getValue());
                else deletion.deleteAccount(admin,target,request);
                firstEntered.countDown();
                try {
                    if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Email/delete barrier timed out");
                }catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(firstEntered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{
                secondStarted.countDown();
                try {
                    if(emailFirst) deletion.deleteAccount(admin,target,request);
                    else emailIdentity.confirmEmailChange(
                            buyerActor,proof.getKey(),proof.getValue());
                    return true;
                }catch(SecurityException expected) {return false;}
            });
            assertThat(secondStarted.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->second.get(300,java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {release.countDown();}
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20,java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(emailFirst);
        }
        assertThat(jdbc.queryForObject("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,String.class,target)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity
                WHERE user_id=? AND (active_lookup_hmac IS NOT NULL
                                    OR current_owner_id IS NOT NULL
                                    OR pending_owner_id IS NOT NULL)
                """,Integer.class,target)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_deletion_oidc_revocation_outbox
                WHERE target_user_id=? AND state='PENDING'
                """,Integer.class,target)).isEqualTo(1);
    }

    private void requirePostgres() throws Exception {
        try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
    }

}
