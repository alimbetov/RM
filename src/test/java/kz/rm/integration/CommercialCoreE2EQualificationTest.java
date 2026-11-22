package kz.rm.integration;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.catalog.CatalogItem;
import kz.rm.catalog.CatalogItemService;
import kz.rm.iam.*;
import kz.rm.inventory.AuthenticatedInventoryCommands;
import kz.rm.inventory.AuthenticatedInventoryReconciliation;
import kz.rm.inventory.AuthenticatedInventoryReconciliationRuns;
import kz.rm.inventory.InventoryMovementService;
import kz.rm.sales.AuthenticatedSalesCommands;
import kz.rm.storefront.StorefrontManagementService;
import kz.rm.storefront.StorefrontOfferService;
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

/**
 * The first full RM business core in ONE test:
 * CATALOG -> STOREFRONT -> CHECKOUT -> SALES -> INVENTORY -> RECONCILIATION.
 *
 * This is a service-only synthetic identity flow. It is not a public transport,
 * mail-delivery, real OIDC or production admission test.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class CommercialCoreE2EQualificationTest {
    @Autowired CatalogItemService catalog;
    @Autowired StorefrontManagementService storefronts;
    @Autowired StorefrontOfferService offers;
    @Autowired AutoOrderService checkout;
    @Autowired AuthenticatedSalesCommands sales;
    @Autowired AuthenticatedInventoryCommands stock;
    @Autowired AuthenticatedInventoryReconciliation reconciliation;
    @Autowired AuthenticatedInventoryReconciliationRuns reconciliationRuns;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private record Context(UUID tenant, UUID warehouse, VerifiedActor staff) {}

    private VerifiedActor verified(UUID userId) {
        String subject = jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """, String.class, userId);
        JwtDecoder decoder = token -> Jwt.withTokenValue(token).header("alg", "RS256")
                .subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600)).build();
        return new OidcActorResolver(decoder, users).resolve("synthetic-only");
    }

    private UUID user(String prefix) {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                prefix + "-" + UUID.randomUUID(), prefix)).getId();
    }

    private Context setup() {
        var organization = organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(), "Commercial E2E LLP", "Seller", "KZ"));
        UUID tenant = workspaces.saveAndFlush(WorkspaceEntity.organization(
                UUID.randomUUID(), organization.getId())).getId();
        UUID owner = user("commercial-owner");
        UUID staffId = user("commercial-staff");
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                organization.getId(), owner, OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                organization.getId(), staffId, OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?", tenant);
        String kato = jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """, String.class);
        UUID warehouse = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.org_location
                    (id,workspace_id,location_type,name,kato_code,status,
                     created_at,updated_at,row_version)
                VALUES (?,?,'WAREHOUSE','Commercial core warehouse',?,'ACTIVE',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, warehouse, tenant, kato);
        var ownerActor = verified(owner);
        var role = roles.createRole(ownerActor, tenant,
                "RMCORE_" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 12).toUpperCase(), "Commercial core seller");
        for (DomainPermission perm : List.of(
                DomainPermission.CATALOG_WRITE,
                DomainPermission.CATALOG_PRICE_MANAGE,
                DomainPermission.CATALOG_PUBLISH,
                DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH,
                DomainPermission.INVENTORY_RECEIVE,
                DomainPermission.INVENTORY_ADJUST,
                DomainPermission.INVENTORY_STOCK_READ,
                DomainPermission.SALES_ORDER_CONFIRM,
                DomainPermission.INVENTORY_RESERVE,
                DomainPermission.SALES_ORDER_FULFILL,
                DomainPermission.INVENTORY_FULFILL)) {
            roles.grant(ownerActor, tenant, role.id(), perm);
        }
        roles.assign(ownerActor, tenant, staffId, role.id());
        roles.enable(ownerActor, tenant);
        return new Context(tenant, warehouse, verified(staffId));
    }

    private CatalogItemService.Item product(Context c) {
        var draft = catalog.createDraft(c.staff(), c.tenant(),
                "RM-" + UUID.randomUUID(), CatalogItem.Kind.PRODUCT,
                "ru", "Sensor", null);
        var priced = catalog.addPriceRevision(c.staff(), c.tenant(), draft.id(),
                new BigDecimal("50.0000"), "KZT",
                Instant.now().minusSeconds(3600), draft.rowVersion());
        return catalog.publish(c.staff(), c.tenant(), priced.id(),
                priced.rowVersion());
    }

    @Test void skuToSellerOfferToCheckoutToFulfillmentToReconciliation() {
        var c = setup();
        var item = product(c);
        var draftStore = storefronts.createDraft(c.staff(), c.tenant(),
                "commercial-" + UUID.randomUUID());
        var store = storefronts.publish(c.staff(), c.tenant(), draftStore.id(),
                draftStore.rowVersion());
        var draftOffer = offers.createDraft(c.staff(), c.tenant(), item.id(),
                "Commercial sensor", new BigDecimal("75.0000"), "KZT");
        var offer = offers.publish(c.staff(), c.tenant(), draftOffer.id(),
                draftOffer.rowVersion());

        var receipt = stock.receive(c.staff(), c.tenant(), c.warehouse(), item.id(),
                "receipt-" + UUID.randomUUID(), new BigDecimal("10"));
        assertThat(receipt.after()).isEqualByComparingTo("10");
        UUID orderId = checkout.checkout(store.slug(),
                "commercial-" + UUID.randomUUID() + "@example.kz",
                "commercial-order-0001",
                List.of(new AutoOrderService.Line(offer.id(), 3))).order().orderId();

        assertThat(jdbc.queryForObject("""
                SELECT total_amount FROM crm.sales_order
                WHERE tenant_id=? AND id=?
                """, BigDecimal.class, c.tenant(), orderId)).isEqualByComparingTo("225.0000");
        assertThat(jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """, UUID.class, c.tenant(), orderId)).isEqualTo(item.id());

        // Mutating the seller's live offer cannot change buyer-accepted evidence.
        offers.updatePrice(c.staff(), c.tenant(), offer.id(),
                "New list price", new BigDecimal("90.0000"), "KZT",
                offer.rowVersion());
        assertThat(jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """, BigDecimal.class, c.tenant(), orderId)).isEqualByComparingTo("75.0000");

        var confirmed = sales.confirm(c.staff(), c.tenant(), orderId, c.warehouse());
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.affectedLines()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """, BigDecimal.class, c.tenant(), c.warehouse(), item.id()))
                .isEqualByComparingTo("3");

        var completed = sales.fulfill(c.staff(), c.tenant(), orderId);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.affectedLines()).isEqualTo(1);
        assertThat(sales.fulfill(c.staff(), c.tenant(), orderId).affectedLines()).isZero();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND item_id=? AND movement_kind='FULFILLMENT'
                  AND source_type='SALES_ORDER' AND source_id=?
                """, Integer.class, c.tenant(), item.id(), orderId)).isEqualTo(1);
        var reconciled = reconciliation.inspect(c.staff(), c.tenant(),
                c.warehouse(), item.id());
        assertThat(reconciled.finding())
                .isEqualTo(AuthenticatedInventoryReconciliation.Finding.CONSISTENT);
        assertThat(reconciled.onHand()).isEqualByComparingTo("7");
        assertThat(reconciled.reserved()).isEqualByComparingTo("0");
        assertThat(reconciled.activeReservations()).isEqualByComparingTo("0");
        assertThat(reconciled.latestMovementBalance()).isEqualByComparingTo("7");

        // Durable reconciliation is the final independent business evidence,
        // not merely an ephemeral stock query. It must not alter quantity.
        var run = reconciliationRuns.start(c.staff(), c.tenant());
        var finished = reconciliationRuns.advance(c.staff(), c.tenant(), run.id(), 20);
        assertThat(finished.run().status())
                .isEqualTo(AuthenticatedInventoryReconciliationRuns.Status.COMPLETED);
        assertThat(finished.run().inspectedPositions()).isEqualTo(1L);
        assertThat(finished.run().findingCount()).isZero();
        assertThat(reconciliationRuns.findings(c.staff(), c.tenant(), run.id(), 0, 20))
                .isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """, BigDecimal.class, c.tenant(), c.warehouse(), item.id()))
                .isEqualByComparingTo("7");

        assertThatThrownBy(() -> catalog.archive(c.staff(), c.tenant(), item.id(),
                item.rowVersion())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_ARCHIVE_BLOCKED_BY_INVENTORY");
    }

    @Test void publishedOfferAndOpenStocktakePreventUnsafeArchive() {
        var c = setup();
        var item = product(c);
        storefronts.createDraft(c.staff(), c.tenant(), "commercial-" + UUID.randomUUID());
        var draftOffer = offers.createDraft(c.staff(), c.tenant(), item.id(),
                "Published device", BigDecimal.TEN, "KZT");
        var offer = offers.publish(c.staff(), c.tenant(), draftOffer.id(),
                draftOffer.rowVersion());

        assertThatThrownBy(() -> catalog.archive(c.staff(), c.tenant(), item.id(),
                item.rowVersion())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_ARCHIVE_BLOCKED_BY_PUBLISHED_OFFER");
        offers.hide(c.staff(), c.tenant(), offer.id(), offer.rowVersion());

        stock.receive(c.staff(), c.tenant(), c.warehouse(), item.id(),
                "receipt-" + UUID.randomUUID(), new BigDecimal("4"));
        assertThatThrownBy(() -> catalog.archive(c.staff(), c.tenant(), item.id(),
                item.rowVersion())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_ARCHIVE_BLOCKED_BY_INVENTORY");
        stock.writeOff(c.staff(), c.tenant(), c.warehouse(), item.id(),
                "writeoff-" + UUID.randomUUID(), new BigDecimal("4"),
                InventoryMovementService.WriteOffCause.OTHER);

        var open = stock.startStocktake(c.staff(), c.tenant(), c.warehouse(),
                List.of(item.id()), "stocktake-" + UUID.randomUUID());
        assertThatThrownBy(() -> catalog.archive(c.staff(), c.tenant(), item.id(),
                item.rowVersion())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_ARCHIVE_BLOCKED_BY_INVENTORY");
        stock.cancelStocktake(c.staff(), c.tenant(), open.id());

        var archived = catalog.archive(c.staff(), c.tenant(), item.id(), item.rowVersion());
        assertThat(archived.state()).isEqualTo(CatalogItem.State.ARCHIVED);
        assertThatThrownBy(() -> offers.publish(c.staff(), c.tenant(), offer.id(),
                offer.rowVersion() + 1))
                .isInstanceOf(IllegalStateException.class);
    }
    private void postgresOnly() throws Exception {
        try (var connection = dataSource.getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName()
                            .toLowerCase(java.util.Locale.ROOT).contains("postgresql"),
                    "PostgreSQL isolation/row locks required");
        }
    }

    @Test void postgresArchiveWinsThenConcurrentReceiptCannotStrandStock() throws Exception {
        postgresOnly();
        var c = setup();
        var item = product(c);
        var archiveReady = new java.util.concurrent.CountDownLatch(1);
        var receiptAttempt = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var archive = pool.submit(() -> tx.execute(status -> {
                var result = catalog.archive(c.staff(), c.tenant(), item.id(),
                        item.rowVersion());
                archiveReady.countDown();
                try {
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Archive test barrier expired");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return result;
            }));
            var receipt = pool.submit(() -> {
                if (!archiveReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Archive not admitted");
                receiptAttempt.countDown();
                return stock.receive(c.staff(), c.tenant(), c.warehouse(), item.id(),
                        "archive-race-" + UUID.randomUUID(), BigDecimal.ONE);
            });
            assertThat(receiptAttempt.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            release.countDown();
            assertThat(archive.get(20, java.util.concurrent.TimeUnit.SECONDS).state())
                    .isEqualTo(CatalogItem.State.ARCHIVED);
            assertThatThrownBy(() -> receipt.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        } finally {
            release.countDown();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_balance
                WHERE tenant_id=? AND item_id=? AND on_hand<>0
                """, Integer.class, c.tenant(), item.id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND item_id=?
                """, Integer.class, c.tenant(), item.id())).isZero();
    }

    @Test void postgresReceiptWinsThenArchiveDetectsCommittedStock() throws Exception {
        postgresOnly();
        var c = setup();
        var item = product(c);
        var receiptReady = new java.util.concurrent.CountDownLatch(1);
        var archiveAttempt = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var receipt = pool.submit(() -> tx.execute(status -> {
                var result = stock.receive(c.staff(), c.tenant(), c.warehouse(), item.id(),
                        "stock-first-" + UUID.randomUUID(), new BigDecimal("2"));
                receiptReady.countDown();
                try {
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Receipt test barrier expired");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return result;
            }));
            var archive = pool.submit(() -> {
                if (!receiptReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Receipt not admitted");
                archiveAttempt.countDown();
                return catalog.archive(c.staff(), c.tenant(), item.id(), item.rowVersion());
            });
            assertThat(archiveAttempt.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            release.countDown();
            assertThat(receipt.get(20, java.util.concurrent.TimeUnit.SECONDS).after())
                    .isEqualByComparingTo("2");
            assertThatThrownBy(() -> archive.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CATALOG_ARCHIVE_BLOCKED_BY_INVENTORY");
        } finally {
            release.countDown();
        }
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.cat_item WHERE tenant_id=? AND id=?
                """, String.class, c.tenant(), item.id())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """, BigDecimal.class, c.tenant(), c.warehouse(), item.id()))
                .isEqualByComparingTo("2");
    }

    @Test void postgresArchiveAndOfferPublicationAreSerializedInBothOrders() throws Exception {
        postgresOnly();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);

        // ARCHIVE wins: the offer writer must not publish a now-archived SKU.
        {
            var c = setup();
            var item = product(c);
            storefronts.createDraft(c.staff(), c.tenant(), "commercial-" + UUID.randomUUID());
            var draft = offers.createDraft(c.staff(), c.tenant(), item.id(),
                    "Archived SKU must not sell", BigDecimal.ONE, "KZT");
            var archiveReady = new java.util.concurrent.CountDownLatch(1);
            var publishStarted = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var archive = pool.submit(() -> tx.execute(status -> {
                    var result = catalog.archive(c.staff(), c.tenant(), item.id(),
                            item.rowVersion());
                    archiveReady.countDown();
                    try {
                        if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Archive/offer barrier timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return result;
                }));
                var publish = pool.submit(() -> {
                    if (!archiveReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Archive not admitted");
                    publishStarted.countDown();
                    return offers.publish(c.staff(), c.tenant(), draft.id(), 0L);
                });
                assertThat(publishStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                release.countDown();
                assertThat(archive.get(20, java.util.concurrent.TimeUnit.SECONDS).state())
                        .isEqualTo(CatalogItem.State.ARCHIVED);
                assertThatThrownBy(() -> publish.get(20, java.util.concurrent.TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
            } finally {
                release.countDown();
            }
            assertThat(jdbc.queryForObject("""
                    SELECT state FROM crm.store_offer WHERE tenant_id=? AND id=?
                    """, String.class, c.tenant(), draft.id())).isEqualTo("DRAFT");
        }

        // PUBLISH wins: the committed commercial obligation blocks SKU archive.
        {
            var c = setup();
            var item = product(c);
            storefronts.createDraft(c.staff(), c.tenant(), "commercial-" + UUID.randomUUID());
            var draft = offers.createDraft(c.staff(), c.tenant(), item.id(),
                    "Published offer keeps SKU active", BigDecimal.TEN, "KZT");
            var offerReady = new java.util.concurrent.CountDownLatch(1);
            var archiveStarted = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var publish = pool.submit(() -> tx.execute(status -> {
                    var result = offers.publish(c.staff(), c.tenant(), draft.id(), 0L);
                    offerReady.countDown();
                    try {
                        if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Offer/archive barrier timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return result;
                }));
                var archive = pool.submit(() -> {
                    if (!offerReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Offer not admitted");
                    archiveStarted.countDown();
                    return catalog.archive(c.staff(), c.tenant(), item.id(),
                            item.rowVersion());
                });
                assertThat(archiveStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                release.countDown();
                assertThat(publish.get(20, java.util.concurrent.TimeUnit.SECONDS).state())
                        .isEqualTo(StorefrontOfferService.State.PUBLISHED);
                assertThatThrownBy(() -> archive.get(20, java.util.concurrent.TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
            } finally {
                release.countDown();
            }
            assertThat(jdbc.queryForObject("""
                    SELECT state FROM crm.cat_item WHERE tenant_id=? AND id=?
                    """, String.class, c.tenant(), item.id())).isEqualTo("PUBLISHED");
        }
    }

}
