package kz.rm.organization;

import kz.rm.autoorder.AutoOrderService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

@SpringBootTest(properties="rm.currency.commerce-admission.enabled=true")
class CommerceCurrencyAdmissionQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AutoOrderService orders;
    @Autowired CommerceCurrencyAdmission admission;
    @Autowired kz.rm.storefront.PublishedOfferCardQuery cards;
    @Autowired kz.rm.iam.UserAccountRepository users;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;
    @Autowired kz.rm.iam.OrganizationRepository organizations;
    @Autowired kz.rm.iam.WorkspaceRepository workspaces;

    private record Store(UUID tenant, UUID offer, String slug) {}
    private Store setup(String currency,boolean policy) {
        var org=organizations.saveAndFlush(new kz.rm.iam.OrganizationEntity(
                UUID.randomUUID(),"CUR-R1C store","Currency admission","KZ"));
        var workspace=workspaces.saveAndFlush(kz.rm.iam.WorkspaceEntity.organization(
                UUID.randomUUID(),org.getId()));
        UUID tenant=workspace.getId(),item=UUID.randomUUID(),offer=UUID.randomUUID();
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        String slug="cur-r1c-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item
              (tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,tenant,item,"CUR-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',?)
            """,tenant,UUID.randomUUID(),slug,Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm.store_offer
              (tenant_id,id,title,unit_price,currency,state,catalog_item_id)
            VALUES (?,?,? ,125.2500,?,'PUBLISHED',?)
            """,tenant,offer,"Currency product",currency,item);
        if(policy) {
            Timestamp now=Timestamp.from(Instant.now());
            jdbc.update("""
                INSERT INTO public.tenant_currency_policy
                  (tenant_id,accounting_currency,market_country,public_price_currency,
                   status,policy_version,row_version,created_at,updated_at)
                VALUES (?,'KZT','KZ','KZT','ACTIVE',1,0,?,?)
                """,tenant,now,now);
            jdbc.update("""
                INSERT INTO public.tenant_allowed_currency
                  (tenant_id,currency_code,purpose,state,row_version,created_at,updated_at)
                VALUES (?,'KZT','PUBLIC_PRICE','ENABLED',0,?,?)
                """,tenant,now,now);
        }
        return new Store(tenant,offer,slug);
    }

    private String qr(Store store) {
        UUID actor=users.saveAndFlush(new kz.rm.iam.UserAccountEntity(
                UUID.randomUUID(),"cur-qr-"+UUID.randomUUID(),"QR staff")).getId();
        String ref=UUID.randomUUID().toString().replace("-","");
        jdbc.update("""
            INSERT INTO crm.store_offer_qr_ref
            (tenant_id,id,offer_id,active_offer_id,public_ref,generation,
             state,row_version,issued_at,issued_by)
            VALUES (?,?,?,?,?,1,'ACTIVE',0,?,?)
            """,store.tenant(),UUID.randomUUID(),store.offer(),store.offer(),ref,
                Timestamp.from(Instant.now()),actor);
        return ref;
    }

    private AutoOrderService.CheckoutResult checkout(Store store) {
        return orders.checkout(store.slug(),"cur-r1c-"+UUID.randomUUID()+"@example.kz",
                "cur-r1c-"+UUID.randomUUID().toString().substring(0,15),
                List.of(new AutoOrderService.Line(store.offer(),1)));
    }
    private int orderCount(Store s) {
        return jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class,s.tenant());
    }

    @Test void curc01MissingPolicyRejectsCheckoutWithoutSideEffects() {
        Store s=setup("KZT",false);
        assertThatThrownBy(()->checkout(s)).isInstanceOf(IllegalStateException.class)
                .hasMessage("CUR_PUBLIC_POLICY_NOT_ACTIVE");
        assertThat(orderCount(s)).isZero();
    }

    @Test void curc02AllowedKztCreatesImmutableOrderSnapshot() {
        Store s=setup("KZT",true);
        var result=checkout(s);
        BigDecimal prior=jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,BigDecimal.class,s.tenant(),result.order().orderId());
        assertThat(prior).isEqualByComparingTo("125.2500");
        // Only simulate a seller revision after this accepted checkout.
        jdbc.update("""
                UPDATE crm.store_offer SET unit_price=130.0000,row_version=row_version+1
                WHERE tenant_id=? AND id=?
                """,s.tenant(),s.offer());
        assertThat(jdbc.queryForObject("""
                SELECT unit_price_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,BigDecimal.class,s.tenant(),result.order().orderId()))
                .isEqualByComparingTo("125.2500");
    }

    @Test void curc03UsdPublishedLegacyOfferRejectedWithoutOrder() {
        Store s=setup("USD",true);
        assertThatThrownBy(()->checkout(s)).isInstanceOf(IllegalStateException.class)
                .hasMessage("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
        assertThat(orderCount(s)).isZero();
    }

    @Test void curc04ReviewRequiredPolicyRejectsCheckout() {
        Store s=setup("KZT",true);
        jdbc.update("UPDATE public.tenant_currency_policy SET status='REVIEW_REQUIRED' WHERE tenant_id=?",
                s.tenant());
        assertThatThrownBy(()->checkout(s)).isInstanceOf(IllegalStateException.class)
                .hasMessage("CUR_PUBLIC_POLICY_NOT_ACTIVE");
        assertThat(orderCount(s)).isZero();
    }

    @Test void curc05PublicReadGateCannotExposeForeignCurrency() {
        Store s=setup("USD",true);
        assertThatThrownBy(()->admission.assertPublicRead(s.tenant(),"USD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
        assertThatThrownBy(()->admission.assertPublicRead(UUID.randomUUID(),"KZT"))
                .hasMessage("CUR_PUBLIC_POLICY_NOT_ACTIVE");
    }

    @Test void curc07KztQrPublicCardShowsPublishedSnapshot() {
        Store store=setup("KZT",true);
        String ref=qr(store);
        var card=cards.findPublished(ref,"ru");
        assertThat(card.currency()).isEqualTo("KZT");
        assertThat(card.unitPrice()).isEqualByComparingTo("125.2500");
    }

    @Test void curc08LegacyForeignQrCardRejectedBeforeMediaExposure() {
        Store store=setup("USD",true);
        String ref=qr(store);
        assertThatThrownBy(()->cards.findPublished(ref,"ru"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
    }

    @Test void curc06PostgresPolicyChangeVsCheckoutLinearizes() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Store s=setup("KZT",true);
        var tx=new TransactionTemplate(txManager);
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var policyChange=executor.submit(()->tx.execute(status->{
                jdbc.queryForObject("""
                    SELECT tenant_id FROM public.tenant_currency_policy
                    WHERE tenant_id=? FOR UPDATE
                    """,UUID.class,s.tenant());
                locked.countDown();
                try {
                    if(!release.await(5,TimeUnit.SECONDS))
                        throw new IllegalStateException("CUR_RACE_TIMEOUT");
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                jdbc.update("""
                    UPDATE public.tenant_currency_policy
                    SET status='REVIEW_REQUIRED',row_version=row_version+1
                    WHERE tenant_id=?
                    """,s.tenant());
                return null;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var checkout=executor.submit(()->{
                try { checkout(s); return "ACCEPTED"; }
                catch(IllegalStateException denied) { return denied.getMessage(); }
            });
            // PostgreSQL must prevent checkout from passing the policy row lock.
            Thread.sleep(150);
            assertThat(orderCount(s)).isZero();
            release.countDown();
            policyChange.get(5,TimeUnit.SECONDS);
            assertThat(checkout.get(5,TimeUnit.SECONDS))
                    .isEqualTo("CUR_PUBLIC_POLICY_NOT_ACTIVE");
            assertThat(orderCount(s)).isZero();
        } finally {
            release.countDown();
        }
    }
}
