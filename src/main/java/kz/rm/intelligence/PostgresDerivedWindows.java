package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;

/** PostgreSQL numeric window candidate calculator. Input already admitted at target grain.
 * No data access beyond supplied bound values; not a coverage or authorization boundary. */
final class PostgresDerivedWindows {
    private PostgresDerivedWindows(){}
    static Map<DerivedMetricEngine.Operator,BigDecimal> latest(JdbcTemplate jdbc,List<BigDecimal> values){
        if(values.size()<2||values.size()>120||values.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("DERIVED_SQL_WINDOW_INPUT");
        String tuples=String.join(",",Collections.nCopies(values.size(),"(CAST(? AS numeric),CAST(? AS numeric))"));
        var args=new ArrayList<Object>();
        for(int i=0;i<values.size();i++){args.add(i);args.add(values.get(i));}
        String query="""
            WITH input(i,v) AS (VALUES %s), stats AS (
              SELECT i,v,lag(v) OVER (ORDER BY i) AS previous,
                count(*) OVER w AS n,sum(v) OVER w AS sy,sum(v*v) OVER w AS syy,
                sum(i) OVER w AS sx,sum(i*i) OVER w AS sxx,sum(i*v) OVER w AS sxy,
                min(v) OVER w AS lo,max(v) OVER w AS hi
              FROM input WINDOW w AS (ORDER BY i ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
            ), last_row AS (
              SELECT *,sy/n AS mean,sqrt((n*syy-sy*sy)/(n::numeric*n)) AS sd
              FROM stats ORDER BY i DESC LIMIT 1
            )
            SELECT mean,v-previous,CASE WHEN previous>0 THEN (v-previous)/previous END,
              (n*sxy-sx*sy)/(n*sxx-sx*sx),sd,CASE WHEN mean>0 THEN sd/mean END,lo,hi
            FROM last_row
            """.formatted(tuples);
        return jdbc.query(query,rs->{
            if(!rs.next())throw new IllegalStateException("DERIVED_SQL_EMPTY");
            var result=new EnumMap<DerivedMetricEngine.Operator,BigDecimal>(DerivedMetricEngine.Operator.class);
            var ops=new DerivedMetricEngine.Operator[]{DerivedMetricEngine.Operator.AVG,DerivedMetricEngine.Operator.PERIOD_CHANGE,
                DerivedMetricEngine.Operator.PERIOD_GROWTH,DerivedMetricEngine.Operator.TREND,DerivedMetricEngine.Operator.STDDEV_POP,
                DerivedMetricEngine.Operator.CV,DerivedMetricEngine.Operator.MIN,DerivedMetricEngine.Operator.MAX};
            for(int i=0;i<ops.length;i++){var v=rs.getBigDecimal(i+1);if(v!=null)result.put(ops[i],v);}
            result.put(DerivedMetricEngine.Operator.SMA,result.get(DerivedMetricEngine.Operator.AVG));
            result.put(DerivedMetricEngine.Operator.FORECAST_NEXT,result.get(DerivedMetricEngine.Operator.AVG));
            return Map.copyOf(result);
        },args.toArray());
    }
}
