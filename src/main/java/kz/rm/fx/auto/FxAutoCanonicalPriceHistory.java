package kz.rm.fx.auto;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class FxAutoCanonicalPriceHistory {
    private FxAutoCanonicalPriceHistory(){}
    private record History(UUID id,long version,Instant at,BigDecimal oldPrice,BigDecimal newPrice,String oldCurrency,String newCurrency,boolean auto) {}
    record Window(BigDecimal baseline,List<FxAutoRollingLimitEvaluator.Change> changes,List<UUID> evidence) {}
    /** Conservative history oracle: require a recorded boundary and an unbroken
     * KZT chain from initial revision through the current canonical price. */
    static Window history(JdbcTemplate jdbc,UUID tenant,UUID offer,UUID priceRevision,BigDecimal current,Instant now,long seconds){
        Window missing=new Window(null,List.of(),List.of());
        Instant boundary;
        try{boundary=now.minusSeconds(seconds);}catch(DateTimeException|ArithmeticException e){return missing;}
        List<History> rows=jdbc.query("""
            SELECT id,offer_row_version,recorded_at,old_price,new_price,trim(old_currency),trim(new_currency),provenance
            FROM crm.store_offer_price_revision WHERE tenant_id=? AND offer_id=?
            ORDER BY offer_row_version LIMIT 10001
            """,(rs,n)->new History(rs.getObject(1,UUID.class),rs.getLong(2),rs.getTimestamp(3).toInstant(),
                rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getString(6),rs.getString(7),"SYSTEM_AUTO".equals(rs.getString(8))),tenant,offer);
        if(rows.isEmpty() || rows.size()>10000)return missing;
        BigDecimal baseline=null,previous=null;
        Instant prior=null;
        List<FxAutoRollingLimitEvaluator.Change> changes=new ArrayList<>();
        List<UUID> evidence=new ArrayList<>();
        for(History h:rows){
            if(h.at().isAfter(now) || (prior!=null && h.at().isBefore(prior))
                || !"KZT".equals(h.newCurrency()) || h.newPrice().signum()<=0)return missing;
            if(previous==null){
                if(h.version()!=0 || h.oldPrice()!=null || h.oldCurrency()!=null)return missing;
            }else{
                if(h.oldPrice()==null || h.oldPrice().compareTo(previous)!=0 || !"KZT".equals(h.oldCurrency()))return missing;
                if(h.at().isAfter(boundary))changes.add(new FxAutoRollingLimitEvaluator.Change(previous,h.newPrice(),h.auto()));
            }
            if(!h.at().isAfter(boundary))baseline=h.newPrice();
            previous=h.newPrice();prior=h.at();evidence.add(h.id());
        }
        if(!rows.getLast().id().equals(priceRevision) || previous.compareTo(current)!=0 || baseline==null)return missing;
        return new Window(baseline,List.copyOf(changes),List.copyOf(evidence));
    }


}
