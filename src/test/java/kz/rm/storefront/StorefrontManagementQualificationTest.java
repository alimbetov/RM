package kz.rm.storefront;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** RM-STORE-01 service-only tests: no HTTP or fake global storefront privileges. */
@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties={
        "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class StorefrontManagementQualificationTest {
    @Autowired StorefrontManagementService storefronts;
    @Autowired StorefrontOfferService offers;
    @Autowired kz.rm.autoorder.AutoOrderService autoOrders;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private record Fixture(UUID tenant, UUID owner, UUID staff, UUID organization) {}

    private Fixture fixture() {
        var owner = users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "store-owner-" + UUID.randomUUID(), "Store owner"));
        var staff = users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "store-staff-" + UUID.randomUUID(), "Store seller"));
        var org = organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Storefront LLP", "Storefront", "KZ"));
        var tenant = workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(), org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(), owner.getId(), OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(), staff.getId(), OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?", tenant.getId());
        return new Fixture(tenant.getId(), owner.getId(), staff.getId(), org.getId());
    }

    private VerifiedActor verified(UUID user) {
        String subject = jdbc.queryForObject(
                "SELECT external_subject FROM iam_user_account WHERE id=?",
                String.class, user);
        JwtDecoder decoder = token -> Jwt.withTokenValue(token)
                .header("alg", "RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder, users).resolve("synthetic-only");
    }

    private UUID grant(Fixture fixture, DomainPermission... permissions) {
        var actor = verified(fixture.owner());
        var role = roles.createRole(actor, fixture.tenant(),
                "STORE_" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 12).toUpperCase(),
                "Storefront seller");
        for (var permission : permissions)
            roles.grant(actor, fixture.tenant(), role.id(), permission);
        roles.assign(actor, fixture.tenant(), fixture.staff(), role.id());
        roles.enable(actor, fixture.tenant());
        return role.id();
    }

    @Test void sellerCanCreatePublishPauseAndResumeWithAuditAndStaleCheck() {
        var tenant = fixture();
        grant(tenant, DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var staff = verified(tenant.staff());
        String slug = "shop-" + UUID.randomUUID();
        var draft = storefronts.createDraft(staff, tenant.tenant(), slug);
        assertThat(draft.state()).isEqualTo(StorefrontSettings.State.DRAFT);
        assertThat(storefronts.get(staff, tenant.tenant(), draft.id()).slug())
                .isEqualTo(slug);

        var published = storefronts.publish(staff, tenant.tenant(), draft.id(),
                draft.rowVersion());
        assertThat(published.state()).isEqualTo(StorefrontSettings.State.PUBLISHED);
        assertThatThrownBy(() -> storefronts.pause(staff, tenant.tenant(), draft.id(),
                draft.rowVersion()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STALE");

        var paused = storefronts.pause(staff, tenant.tenant(), draft.id(),
                published.rowVersion());
        var resumed = storefronts.publish(staff, tenant.tenant(), draft.id(),
                paused.rowVersion());
        // Fresh same-state replay is a no-op: no extra audit entry.
        storefronts.publish(staff, tenant.tenant(), draft.id(),
                resumed.rowVersion());
        assertThatThrownBy(() -> storefronts.pause(staff, tenant.tenant(), draft.id(),
                published.rowVersion())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STALE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.audit_event
                WHERE tenant_id=? AND entity_id=?
                  AND action_code IN ('STOREFRONT_CREATED',
                                     'STOREFRONT_PUBLISHED','STOREFRONT_PAUSED')
                """, Integer.class, tenant.tenant(), draft.id())).isEqualTo(4);
    }

    @Test void grantsAreRequiredAndForeignStorefrontIsNotAddressable() {
        var a = fixture();
        var b = fixture();
        grant(a, DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        grant(b, DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actorA = verified(a.staff());
        var actorB = verified(b.staff());
        var own = storefronts.createDraft(actorA, a.tenant(),
                "shop-" + UUID.randomUUID());

        assertThatThrownBy(() -> storefronts.get(actorB, b.tenant(), own.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storefronts.publish(actorB, b.tenant(), own.id(),
                0L))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> storefronts.createDraft(actorB, a.tenant(),
                "shop-" + UUID.randomUUID()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> storefronts.publish(actorA, a.tenant(), own.id(),
                1L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM crm.store_front WHERE tenant_id=? AND id=?
                """, String.class, a.tenant(), own.id())).isEqualTo("DRAFT");
    }
    @Test void offerVersionCasAndCheckoutSnapshotStayImmutable() {
        var t = fixture();
        grant(t, DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var seller = verified(t.staff());
        var draftStore = storefronts.createDraft(seller, t.tenant(),
                "shop-" + UUID.randomUUID());
        var store = storefronts.publish(seller, t.tenant(), draftStore.id(),
                draftStore.rowVersion());
        var catalogId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """, t.tenant(), catalogId, "ST-" + UUID.randomUUID());
        var draft = offers.createDraft(seller, t.tenant(), catalogId,
                "Widget", new java.math.BigDecimal("10.0000"), "KZT");
        assertThat(draft.rowVersion()).isZero();
        var published = offers.publish(seller, t.tenant(), draft.id(), draft.rowVersion());
        assertThat(published.rowVersion()).isEqualTo(1L);
        assertThat(published.state()).isEqualTo(StorefrontOfferService.State.PUBLISHED);
        var order = autoOrders.checkout(store.slug(),
                "offer-" + UUID.randomUUID() + "@example.kz", "offer-key-0001",
                java.util.List.of(new kz.rm.autoorder.AutoOrderService.Line(draft.id(), 2)))
                .order();
        var changed = offers.updatePrice(seller, t.tenant(), draft.id(),
                "Widget 2026", new java.math.BigDecimal("12.0000"), "KZT",
                published.rowVersion());
        assertThat(changed.rowVersion()).isEqualTo(2L);
        assertThatThrownBy(() -> offers.updatePrice(seller, t.tenant(), draft.id(),
                "Stale", java.math.BigDecimal.ONE, "KZT", published.rowVersion()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STALE");
        assertThat(jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """, java.math.BigDecimal.class, t.tenant(), order.orderId()))
                .isEqualByComparingTo("10.0000");
        assertThat(jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """, UUID.class, t.tenant(), order.orderId())).isEqualTo(catalogId);
        var paused = offers.pause(seller, t.tenant(), draft.id(), changed.rowVersion());
        assertThat(paused.state()).isEqualTo(StorefrontOfferService.State.PAUSED);
        assertThatThrownBy(() -> autoOrders.checkout(store.slug(),
                "other-" + UUID.randomUUID() + "@example.kz", "offer-key-0002",
                java.util.List.of(new kz.rm.autoorder.AutoOrderService.Line(draft.id(), 1))))
                .isInstanceOf(IllegalArgumentException.class);
        var resumed = offers.publish(seller, t.tenant(), draft.id(), paused.rowVersion());
        assertThat(resumed.rowVersion()).isEqualTo(4L);
        assertThatThrownBy(() -> offers.hide(seller, t.tenant(), draft.id(),
                changed.rowVersion())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STALE");
    }

    @Test void pass09PausedStorefrontCannotBeBypassedThroughSecondOfferService() {
        var t=fixture();
        grant(t,DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var actor=verified(t.staff());
        var draftStore=storefronts.createDraft(actor,t.tenant(),"shop-"+UUID.randomUUID());
        var publishedStore=storefronts.publish(actor,t.tenant(),draftStore.id(),0);
        UUID sku=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,t.tenant(),sku,"sku-"+UUID.randomUUID());
        var draftOffer=offers.createDraft(actor,t.tenant(),sku,
                "Not published while shop paused",java.math.BigDecimal.TEN,"KZT");
        storefronts.pause(actor,t.tenant(),publishedStore.id(),publishedStore.rowVersion());
        assertThatThrownBy(()->offers.publish(actor,t.tenant(),draftOffer.id(),0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STOREFRONT_UNAVAILABLE");
        assertThat(offers.get(actor,t.tenant(),draftOffer.id()).state())
                .isEqualTo(StorefrontOfferService.State.DRAFT);
    }

    @Test void managementAndPublicationPermissionsAreIndependent() {
        var t = fixture();
        grant(t, DomainPermission.STOREFRONT_OFFER_MANAGE);
        var staff = verified(t.staff());
        var storefront = storefronts.createDraft(staff, t.tenant(),
                "shop-" + UUID.randomUUID());
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """, t.tenant(), sku, "ST-" + UUID.randomUUID());
        var offer = offers.createDraft(staff, t.tenant(), sku,
                "No publish permission", java.math.BigDecimal.ONE, "KZT");
        assertThatThrownBy(() -> offers.publish(staff, t.tenant(), offer.id(), 0L))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> storefronts.publish(staff, t.tenant(),
                storefront.id(), 0L)).isInstanceOf(SecurityException.class);
        assertThat(offers.get(staff, t.tenant(), offer.id()).state())
                .isEqualTo(StorefrontOfferService.State.DRAFT);
    }

    @Test void concurrentSellerPriceUpdateAndCheckoutCannotMixCommercialSnapshot() throws Exception {
        var t = fixture();
        grant(t, DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var seller = verified(t.staff());
        var storefront = storefronts.createDraft(seller, t.tenant(),
                "shop-" + UUID.randomUUID());
        storefronts.publish(seller, t.tenant(), storefront.id(), 0L);
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """, t.tenant(), sku, "RACE-" + UUID.randomUUID());
        var draft = offers.createDraft(seller, t.tenant(), sku,
                "Original", new java.math.BigDecimal("10.0000"), "KZT");
        offers.publish(seller, t.tenant(), draft.id(), 0L);
        var gate = new java.util.concurrent.CountDownLatch(1);
        String email = "race-" + UUID.randomUUID() + "@example.kz";
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var order = pool.submit(() -> {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return autoOrders.checkout(storefront.slug(), email, "offer-race-key-001",
                        java.util.List.of(new kz.rm.autoorder.AutoOrderService.Line(draft.id(), 2)))
                        .order().orderId();
            });
            var price = pool.submit(() -> {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return offers.updatePrice(seller, t.tenant(), draft.id(),
                        "Updated", new java.math.BigDecimal("15.0000"), "KZT", 1L);
            });
            gate.countDown();
            UUID orderId = order.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(price.get(30, java.util.concurrent.TimeUnit.SECONDS).rowVersion())
                    .isEqualTo(2L);
            var row = jdbc.queryForMap("""
                    SELECT title_snapshot,unit_price_snapshot,catalog_item_id_snapshot
                    FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                    """, t.tenant(), orderId);
            assertThat(row.get("catalog_item_id_snapshot")).isEqualTo(sku);
            java.math.BigDecimal amount = (java.math.BigDecimal) row.get("unit_price_snapshot");
            if (amount.compareTo(new java.math.BigDecimal("10.0000")) == 0)
                assertThat(row.get("title_snapshot")).isEqualTo("Original");
            else {
                assertThat(amount).isEqualByComparingTo("15.0000");
                assertThat(row.get("title_snapshot")).isEqualTo("Updated");
            }
            assertThat(jdbc.queryForObject("""
                    SELECT total_amount FROM crm.sales_order WHERE tenant_id=? AND id=?
                    """, java.math.BigDecimal.class, t.tenant(), orderId))
                    .isEqualByComparingTo(amount.multiply(java.math.BigDecimal.valueOf(2)));
        }
    }

    @Test void foreignOfferAndServiceCatalogCannotEnterStockBackedStorefront() {
        var a = fixture();
        var b = fixture();
        grant(a, DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        grant(b, DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var sellerA = verified(a.staff());
        var sellerB = verified(b.staff());
        storefronts.createDraft(sellerA, a.tenant(), "shop-" + UUID.randomUUID());
        storefronts.createDraft(sellerB, b.tenant(), "shop-" + UUID.randomUUID());
        UUID serviceSku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'SERVICE','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """, a.tenant(), serviceSku, "SVC-" + UUID.randomUUID());
        assertThatThrownBy(() -> offers.createDraft(sellerA, a.tenant(), serviceSku,
                "Non-stock service", java.math.BigDecimal.ONE, "KZT"))
                .isInstanceOf(IllegalStateException.class);
        UUID productSku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """, a.tenant(), productSku, "ST-" + UUID.randomUUID());
        var offerA = offers.createDraft(sellerA, a.tenant(), productSku,
                "Valid", java.math.BigDecimal.ONE, "KZT");
        assertThatThrownBy(() -> offers.get(sellerB, b.tenant(), offerA.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offers.publish(sellerB, b.tenant(), offerA.id(), 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(offers.get(sellerA, a.tenant(), offerA.id()).rowVersion())
                .isZero();
    }

    private void postgresOnly() throws Exception {
        try (var connection = dataSource.getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName().contains("PostgreSQL"),
                    "Real PostgreSQL row-lock test");
        }
    }

    @Test void postgresAdmittedSellerCommandCompletesBeforePermissionRevoke() throws Exception {
        postgresOnly();
        var t = fixture();
        UUID role = grant(t, DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var seller = verified(t.staff());
        var owner = verified(t.owner());
        var draft = storefronts.createDraft(seller, t.tenant(), "shop-" + UUID.randomUUID());
        var published = storefronts.publish(seller, t.tenant(), draft.id(), 0L);
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var attempted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var mutation = pool.submit(() -> tx.execute(status -> {
                var paused = storefronts.pause(seller, t.tenant(), draft.id(),
                        published.rowVersion());
                admitted.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Barrier timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return paused;
            }));
            var revoke = pool.submit(() -> {
                if (!admitted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Admission timeout");
                attempted.countDown();
                roles.revoke(owner, t.tenant(), role,
                        DomainPermission.STOREFRONT_OFFER_PUBLISH);
                return true;
            });
            assertThat(attempted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(mutation.get(20, java.util.concurrent.TimeUnit.SECONDS).state())
                    .isEqualTo(StorefrontSettings.State.PAUSED);
            assertThat(revoke.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> storefronts.publish(seller, t.tenant(),
                draft.id(), 2L)).isInstanceOf(SecurityException.class);
        assertThat(storefronts.get(seller, t.tenant(), draft.id()).state())
                .isEqualTo(StorefrontSettings.State.PAUSED);
    }

    @Test void postgresCommittedStorefrontPauseBlocksNewCheckoutWithoutPartialOrder() throws Exception {
        postgresOnly();
        var t = fixture();
        grant(t, DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var seller = verified(t.staff());
        var draft = storefronts.createDraft(seller, t.tenant(), "shop-" + UUID.randomUUID());
        var published = storefronts.publish(seller, t.tenant(), draft.id(), 0L);
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """, t.tenant(), product, "PG-" + UUID.randomUUID());
        var offer = offers.createDraft(seller, t.tenant(), product,
                "Concurrent", new java.math.BigDecimal("5.0000"), "KZT");
        offers.publish(seller, t.tenant(), offer.id(), 0L);
        var paused = new java.util.concurrent.CountDownLatch(1);
        var checkoutStarted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var pause = pool.submit(() -> tx.execute(status -> {
                var result = storefronts.pause(seller, t.tenant(), published.id(),
                        published.rowVersion());
                paused.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Pause barrier timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return result;
            }));
            var checkout = pool.submit(() -> {
                if (!paused.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Pause admission timeout");
                checkoutStarted.countDown();
                return autoOrders.checkout(published.slug(),
                        "pause-" + UUID.randomUUID() + "@example.kz",
                        "paused-pg-0001", java.util.List.of(
                                new kz.rm.autoorder.AutoOrderService.Line(offer.id(), 1)));
            });
            assertThat(checkoutStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(pause.get(20, java.util.concurrent.TimeUnit.SECONDS).state())
                    .isEqualTo(StorefrontSettings.State.PAUSED);
            assertThatThrownBy(() -> checkout.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        } finally {
            release.countDown();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
                """, Integer.class, t.tenant())).isZero();
    }

}
