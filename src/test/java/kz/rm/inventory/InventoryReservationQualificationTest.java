package kz.rm.inventory;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.OrganizationEntity;
import kz.rm.iam.OrganizationRepository;
import kz.rm.iam.WorkspaceEntity;
import kz.rm.iam.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class InventoryReservationQualificationTest {
    @Autowired InventoryReservationService reservations;
    @Autowired AutoOrderService checkout;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired DataSource source;

    private record Store(UUID tenant,UUID location,UUID item,UUID offer,String slug){}
    private record OrderLine(UUID order,UUID line){}

    private Store store(int onHand) {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Reservation Shop LLP","Reservation Shop","KZ"));
        UUID tenant=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        Timestamp now=Timestamp.from(Instant.now());
        UUID location=UUID.randomUUID(),item=UUID.randomUUID(),offer=UUID.randomUUID();
        String slug="res-"+UUID.randomUUID();
        String kato=jdbc.queryForObject("""
            SELECT code FROM public.ref_kato_territory
            WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
            """,String.class);
        jdbc.update("""
            INSERT INTO public.org_location
            (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
            VALUES (?,?,'WAREHOUSE','Primary',?,'ACTIVE',?,?,0)
            """,location,tenant,kato,now,now);
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',?)
            """,tenant,item,"sku-"+UUID.randomUUID(),now);
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?, ?,0)
            """,tenant,location,item,onHand);
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',?)
            """,tenant,UUID.randomUUID(),slug,now);
        jdbc.update("""
            INSERT INTO crm.store_offer
            (tenant_id,id,title,unit_price,currency,state,catalog_item_id)
            VALUES (?,?,'Item',100,'KZT','PUBLISHED',?)
            """,tenant,offer,item);
        return new Store(tenant,location,item,offer,slug);
    }

    private OrderLine order(Store store,int qty,boolean confirm) {
        UUID order=checkout.checkout(store.slug(),
                "inv-"+UUID.randomUUID()+"@example.kz",
                "reservation-"+UUID.randomUUID(),
                List.of(new AutoOrderService.Line(store.offer(),qty))).order().orderId();
        UUID line=jdbc.queryForObject("""
            SELECT id FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
            """,UUID.class,store.tenant(),order);
        if(confirm)
            jdbc.update("UPDATE crm.sales_order SET status='CONFIRMED' WHERE tenant_id=? AND id=?",
                    store.tenant(),order);
        return new OrderLine(order,line);
    }

    private BigDecimal stock(Store s,String field) {
        if(!field.equals("on_hand") && !field.equals("reserved"))
            throw new IllegalArgumentException("Field not allowed");
        return jdbc.queryForObject("""
            SELECT """+field+"""
             FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?
            """,BigDecimal.class,s.tenant(),s.location(),s.item());
    }

    @Test void reservationReleaseAndIdempotentReplay() {
        Store s=store(8);
        OrderLine o=order(s,3,true);
        var r=reservations.reserve(s.tenant(),o.order(),o.line(),s.location(),s.item(),
                new BigDecimal("3"));
        assertThat(r.state()).isEqualTo(InventoryReservationService.State.ACTIVE);
        assertThat(stock(s,"reserved")).isEqualByComparingTo("3");
        assertThat(stock(s,"on_hand")).isEqualByComparingTo("8");
        assertThat(reservations.reserve(s.tenant(),o.order(),o.line(),s.location(),s.item(),
                new BigDecimal("3")).id()).isEqualTo(r.id());
        var released=reservations.release(s.tenant(),r.id());
        assertThat(released.state()).isEqualTo(InventoryReservationService.State.RELEASED);
        assertThat(reservations.release(s.tenant(),r.id()).state())
                .isEqualTo(InventoryReservationService.State.RELEASED);
        assertThat(stock(s,"reserved")).isEqualByComparingTo("0");
        assertThat(stock(s,"on_hand")).isEqualByComparingTo("8");
        assertThatThrownBy(()->reservations.fulfill(s.tenant(),r.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->reservations.reserve(s.tenant(),o.order(),o.line(),
                s.location(),s.item(),new BigDecimal("3")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_reservation_event
            WHERE tenant_id=? AND reservation_id=?
            """,Integer.class,s.tenant(),r.id())).isEqualTo(2);
    }

    @Test void fulfillDebitsExactlyOnceAndPostsImmutableMovement() {
        Store s=store(6);
        OrderLine o=order(s,4,true);
        var r=reservations.reserve(s.tenant(),o.order(),o.line(),s.location(),s.item(),
                new BigDecimal("4"));
        var done=reservations.fulfill(s.tenant(),r.id());
        assertThat(done.state()).isEqualTo(InventoryReservationService.State.FULFILLED);
        assertThat(stock(s,"on_hand")).isEqualByComparingTo("2");
        assertThat(stock(s,"reserved")).isEqualByComparingTo("0");
        jdbc.update("UPDATE crm.sales_order SET status='COMPLETED' WHERE tenant_id=? AND id=?",
                s.tenant(),o.order());
        assertThat(reservations.fulfill(s.tenant(),r.id()).id()).isEqualTo(r.id());
        assertThat(stock(s,"on_hand")).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_movement
            WHERE tenant_id=? AND reservation_id=? AND movement_kind='FULFILLMENT'
            """,Integer.class,s.tenant(),r.id())).isEqualTo(1);
        assertThatThrownBy(()->reservations.release(s.tenant(),r.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void orderMustBeConfirmedAndMappedToCorrectSku() {
        Store s=store(10);
        OrderLine placed=order(s,2,false);
        assertThatThrownBy(()->reservations.reserve(s.tenant(),placed.order(),placed.line(),
                s.location(),s.item(),BigDecimal.valueOf(2)))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE crm.sales_order SET status='CONFIRMED' WHERE tenant_id=? AND id=?",
                s.tenant(),placed.order());
        assertThatThrownBy(()->reservations.reserve(s.tenant(),placed.order(),placed.line(),
                s.location(),UUID.randomUUID(),BigDecimal.valueOf(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->reservations.reserve(s.tenant(),placed.order(),placed.line(),
                s.location(),s.item(),BigDecimal.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void twoConcurrentOrdersCannotOversellSameSku() throws Exception {
        Store s=store(5);
        OrderLine a=order(s,4,true), b=order(s,4,true);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {
                    reservations.reserve(s.tenant(),a.order(),a.line(),s.location(),s.item(),
                            BigDecimal.valueOf(4));
                    return true;
                } catch(IllegalStateException ex){return false;}
            });
            var second=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {
                    reservations.reserve(s.tenant(),b.order(),b.line(),s.location(),s.item(),
                            BigDecimal.valueOf(4));
                    return true;
                } catch(IllegalStateException ex){return false;}
            });
            start.countDown();
            assertThat((first.get(20,TimeUnit.SECONDS)?1:0)
                    +(second.get(20,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(stock(s,"on_hand")).isEqualByComparingTo("5");
        assertThat(stock(s,"reserved")).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=? AND state='ACTIVE'
            """,Integer.class,s.tenant())).isEqualTo(1);
    }

    @Test void tenantIsolationAndReservationEventImmutability() throws Exception {
        Store a=store(5),b=store(5);
        OrderLine order=order(a,2,true);
        var r=reservations.reserve(a.tenant(),order.order(),order.line(),a.location(),a.item(),
                BigDecimal.valueOf(2));
        assertThatThrownBy(()->reservations.release(b.tenant(),r.id()))
                .isInstanceOf(IllegalArgumentException.class);
        try(var connection=source.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.inv_reservation_event WHERE tenant_id=? AND reservation_id=?
            """,a.tenant(),r.id())).isInstanceOf(Exception.class);
    }
}
