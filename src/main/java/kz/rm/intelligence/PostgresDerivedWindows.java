package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;

/** PostgreSQL numeric window candidate calculator. Input already admitted at target grain.
 * No data access beyond supplied bound values; not a coverage or authorization boundary. */
final class PostgresDerivedWindows {
    private PostgresDerivedWindows(){}
    static Map<DerivedMetricEngine.Operator,BigDecimal> latest(JdbcTemplate jdbc,List<BigDecimal> values){
        return batch(jdbc,Map.of("single",values)).get("single");
    }
    static Map<String,Map<DerivedMetricEngine.Operator,BigDecimal>> batch(JdbcTemplate jdbc,Map<String,List<BigDecimal>> series){
        Objects.requireNonNull(series);
        if(series.isEmpty()||series.size()>1000||series.values().stream().mapToLong(List::size).sum()>12000)
            throw new IllegalArgumentException("DERIVED_SQL_BATCH_SIZE");
        var args=new ArrayList<Object>();
        for(var entry:series.entrySet()){
            if(entry.getKey().isBlank())throw new IllegalArgumentException("DERIVED_SQL_SERIES_KEY");
            var values=entry.getValue();
            if(values.size()<2||values.size()>120||values.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("DERIVED_SQL_WINDOW_INPUT");
            for(int i=0;i<values.size();i++){args.add(entry.getKey());args.add(i);args.add(values.get(i));}
        }
        String tuples=String.join(",",Collections.nCopies(args.size()/3,"(CAST(? AS text),CAST(? AS numeric),CAST(? AS numeric))"));
        String query="""
            WITH input(series_key,i,v) AS (VALUES %s), stats AS (
              SELECT series_key,i,v,lag(v) OVER (PARTITION BY series_key ORDER BY i) AS previous,
                count(*) OVER w AS n,sum(v) OVER w AS sy,sum(v*v) OVER w AS syy,
                sum(i) OVER w AS sx,sum(i*i) OVER w AS sxx,sum(i*v) OVER w AS sxy,
                min(v) OVER w AS lo,max(v) OVER w AS hi
              FROM input WINDOW w AS (PARTITION BY series_key ORDER BY i ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
            ), last_row AS (
              SELECT DISTINCT ON (series_key) *,sy/n AS mean,sqrt((n*syy-sy*sy)/(n::numeric*n)) AS sd
              FROM stats WHERE n>=2 ORDER BY series_key,i DESC
            )
            SELECT series_key,mean,v-previous,CASE WHEN previous>0 THEN (v-previous)/previous END,
              (n*sxy-sx*sy)/(n*sxx-sx*sx),sd,CASE WHEN mean>0 THEN sd/mean END,lo,hi
            FROM last_row
            """.formatted(tuples);
        return jdbc.query(query,rs->{
            var batch=new HashMap<String,Map<DerivedMetricEngine.Operator,BigDecimal>>();
            while(rs.next()){
            var result=new EnumMap<DerivedMetricEngine.Operator,BigDecimal>(DerivedMetricEngine.Operator.class);
            var ops=new DerivedMetricEngine.Operator[]{DerivedMetricEngine.Operator.AVG,DerivedMetricEngine.Operator.PERIOD_CHANGE,
                DerivedMetricEngine.Operator.PERIOD_GROWTH,DerivedMetricEngine.Operator.TREND,DerivedMetricEngine.Operator.STDDEV_POP,
                DerivedMetricEngine.Operator.CV,DerivedMetricEngine.Operator.MIN,DerivedMetricEngine.Operator.MAX};
            for(int i=0;i<ops.length;i++){var v=rs.getBigDecimal(i+2);if(v!=null)result.put(ops[i],v);}
            result.put(DerivedMetricEngine.Operator.SMA,result.get(DerivedMetricEngine.Operator.AVG));
            result.put(DerivedMetricEngine.Operator.FORECAST_NEXT,result.get(DerivedMetricEngine.Operator.AVG));
            batch.put(rs.getString(1),Map.copyOf(result));
            }
            if(!batch.keySet().equals(series.keySet()))throw new IllegalStateException("DERIVED_SQL_BATCH_INCOMPLETE");
            return Map.copyOf(batch);
        },args.toArray());
    }
}
