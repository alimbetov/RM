package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class DerivedPostgresQualificationTest {
    private JdbcTemplate jdbc(){
        return new JdbcTemplate(new DriverManagerDataSource(System.getenv("RM_TEST_DB_URL"),
            System.getenv("RM_TEST_DB_USERNAME"),System.getenv("RM_TEST_DB_PASSWORD")));
    }
    @Test void allOperatorsAgainstJavaAcrossBatch(){
        var sets=new LinkedHashMap<String,List<BigDecimal>>();
        sets.put("ordinary",List.of(new BigDecimal("100"),new BigDecimal("130"),new BigDecimal("160")));
        sets.put("zero",List.of(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        sets.put("fraction",List.of(new BigDecimal("0.000001"),new BigDecimal("0.000002"),new BigDecimal("0.000003")));
        sets.put("large",List.of(new BigDecimal("1000000000000000000"),new BigDecimal("1000000000000000001"),new BigDecimal("1000000000000000002")));
        sets.put("long",java.util.stream.IntStream.range(0,120).mapToObj(i->BigDecimal.valueOf(i+1,3)).toList());
        var actual=PostgresDerivedWindows.batch(jdbc(),sets);
        var scope=new DerivedMetricEngine.Scope(UUID.randomUUID(),UUID.randomUUID(),"TEST",1,"ALL","TEST","COUNT","NONE",
            PeriodResolver.Code.D,new PeriodResolver.Policy(ZoneId.of("UTC"),1),1);
        var anchor=LocalDate.of(2026,6,1);var asOf=Instant.parse("2026-06-02T00:00:00Z");
        for(var entry:sets.entrySet()){
            var values=entry.getValue();var periods=DerivedMetricEngine.closedWindow(scope,anchor,asOf,values.size());
            var points=new ArrayList<DerivedMetricEngine.Point>();
            for(int i=0;i<values.size();i++)points.add(new DerivedMetricEngine.Point(scope,periods.get(i),values.get(i),DataQuality.READY,asOf));
            for(var op:DerivedMetricEngine.Operator.values()){
                var expected=DerivedMetricEngine.calculate(scope,op,anchor,asOf,values.size(),points).value();
                assertTrue(DerivedNumericParity.equivalent(op,expected,actual.get(entry.getKey()).get(op)),
                    entry.getKey()+":"+op+" expected="+expected+" actual="+actual.get(entry.getKey()).get(op));
            }
        }
    }
    @Test void boundedBatchWorkload(){
        for(int[] shape:new int[][]{{1,3},{100,12},{1000,12},{100,120}}){
            var batch=new LinkedHashMap<String,List<BigDecimal>>();
            for(int i=0;i<shape[0];i++){
                final int seed=i;
                batch.put("s"+i,java.util.stream.IntStream.range(0,shape[1])
                    .mapToObj(n->BigDecimal.valueOf(seed+n+1)).toList());
            }
            long start=System.nanoTime();
            var result=PostgresDerivedWindows.batch(jdbc(),batch);
            long elapsed=System.nanoTime()-start;
            assertEquals(batch.keySet(),result.keySet());
            for(var entry:batch.entrySet()){
                var values=entry.getValue();
                var expected=values.getFirst().add(values.getLast()).divide(BigDecimal.valueOf(2));
                assertTrue(DerivedNumericParity.equivalent(DerivedMetricEngine.Operator.SMA,expected,
                    result.get(entry.getKey()).get(DerivedMetricEngine.Operator.SMA)));
            }
            System.out.printf("B4 batch rows=%d periods=%d elapsedMs=%.3f%n",shape[0],shape[1],elapsed/1_000_000.0);
        }
    }
}
