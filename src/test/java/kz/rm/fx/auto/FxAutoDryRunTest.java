package kz.rm.fx.auto;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.fx.auto.FxAutoDryRun.*;
import static kz.rm.fx.auto.FxAutoRollingLimitEvaluator.Limits;

class FxAutoDryRunTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SOURCE=UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static BigDecimal d(String v){return new BigDecimal(v);}
    private static Item item(UUID tenant,int id,String candidate) {
        return new Item(new FxAutoTargetRef(tenant,FxAutoTargetRef.TargetType.PRODUCT_OFFER,
                FxAutoTargetRef.OwnerType.STOREFRONT,new UUID(0,id).toString()),
                d("100"),d(candidate),d("100"),List.of(),
                new Limits(d("100"),d("100"),d("1"),d("1000"),d("100"),d("100"),d("1000"),100));
    }
    private static Snapshot snapshot(List<Item> items, Budget b) {
        return new Snapshot(TENANT,"USD",SOURCE,1,Instant.EPOCH,b,items);
    }
    private static Budget budget(){return new Budget(d("100"),10,d("1000"),10,100);}
    @Test void sharedBudgetAndStableOrder() {
        var a=item(TENANT,1,"160"); var b=item(TENANT,2,"40");
        var first=simulate(snapshot(List.of(b,a),budget()));
        assertThat(first).isEqualTo(simulate(snapshot(List.of(a,b),budget())));
        assertThat(first.rows().get(0).outcome()).isEqualTo(Outcome.WOULD_UPDATE);
        assertThat(first.rows().get(1).outcome()).isEqualTo(Outcome.BLOCKED_BUDGET_EXHAUSTED);
        assertThat(first.virtualConsumption()).isEqualByComparingTo("60");
    }
    @Test void noChangeDoesNotSpend() {
        var r=simulate(snapshot(List.of(item(TENANT,1,"100"),item(TENANT,2,"160")),budget()));
        assertThat(r.virtualPublications()).isEqualTo(1);
        assertThat(r.rows().get(0).virtualDelta()).isZero();
    }
    @Test void immutableInputAndReplay() {
        var items=new ArrayList<Item>();items.add(item(TENANT,1,"160"));
        var s=snapshot(items,budget());items.clear();
        assertThat(simulate(s)).isEqualTo(simulate(s));
        assertThat(simulate(s).rows()).hasSize(1);
    }
    @Test void rejectsCrossTenantAndDuplicates() {
        assertThatThrownBy(()->snapshot(List.of(item(UUID.randomUUID(),1,"160")),budget()))
                .isInstanceOf(SecurityException.class);
        var a=item(TENANT,1,"160");
        assertThatThrownBy(()->snapshot(List.of(a,a),budget())).hasMessage("FX_AUTO_DUPLICATE_TARGET");
    }
    @Test void runCapAndManifestLimit() {
        var b=new Budget(d("1000"),100,d("50"),100,1);
        assertThat(simulate(snapshot(List.of(item(TENANT,1,"160")),b)).rows().get(0).outcome())
                .isEqualTo(Outcome.BLOCKED_BUDGET_EXHAUSTED);
        assertThatThrownBy(()->snapshot(List.of(item(TENANT,1,"160"),item(TENANT,2,"160")),b))
                .hasMessage("LIMIT_EXCEEDED");
    }
    @Test void tenThousandTargetsBounded() {
        var items=new ArrayList<Item>();
        for(int i=1;i<=10000;i++)items.add(item(TENANT,i,"101"));
        var r=simulate(snapshot(items,new Budget(d("10000"),10000,d("10000"),10000,10000)));
        assertThat(r.rows()).hasSize(10000);
        assertThat(r.virtualPublications()).isEqualTo(10000);
        assertThat(r.virtualConsumption()).isEqualByComparingTo("10000");
    }
}
