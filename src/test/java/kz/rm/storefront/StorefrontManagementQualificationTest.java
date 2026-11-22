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

    private void grant(Fixture fixture, DomainPermission... permissions) {
        var actor = verified(fixture.owner());
        var role = roles.createRole(actor, fixture.tenant(),
                "STORE_" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 12).toUpperCase(),
                "Storefront seller");
        for (var permission : permissions)
            roles.grant(actor, fixture.tenant(), role.id(), permission);
        roles.assign(actor, fixture.tenant(), fixture.staff(), role.id());
        roles.enable(actor, fixture.tenant());
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

}
