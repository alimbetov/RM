package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real migrated PostgreSQL journey, invoked after independent L2/L3 proof. */
public final class FeatureStoreJourneyEvidence {
    private FeatureStoreJourneyEvidence(){}
    public static Runnable qualify(ApplicationContext context,JdbcTemplate jdbc,PlatformTransactionManager manager,
            VerifiedActor actor,UUID tenant,UUID generation,UUID proof,UUID warehouse,UUID sku,LocalDate anchor,Runnable revoke,Runnable restore,VerifiedActor foreign){
        var observer=context.getBean(FactAvailabilityObserver.class);
        var store=context.getBean(FeatureCandidateStore.class);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(s->observer.observe(tenant,100)));
        var pool=Executors.newFixedThreadPool(2);
        UUID key=UUID.randomUUID();
        var filter=new L3DerivedSeriesService.Filter(null,null,sku,List.of(warehouse),null);
        var request=new FeatureCandidateStore.Request(generation,proof,L3DerivedSeriesService.Metric.STOCK_IN,
            filter,PeriodResolver.Code.D,anchor,2,DerivedMetricEngine.Operator.SMA);
        try{
            var observerStart=new CyclicBarrier(2);
            var a=pool.submit(()->{observerStart.await(15,TimeUnit.SECONDS);return observer.observe(tenant,1000);});
            var b=pool.submit(()->{observerStart.await(15,TimeUnit.SECONDS);return observer.observe(tenant,1000);});
            long count=jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=?",Long.class,tenant);
            assertEquals(count,(long)a.get(60,TimeUnit.SECONDS)+b.get(60,TimeUnit.SECONDS));
            assertEquals(0,observer.observe(tenant,1000));
            assertEquals(0,new FactAvailabilityObserver(jdbc,manager).observe(tenant,1000));
            // The trigger raises after a real insert; PostgreSQL must roll back the entire writer.
            UUID retryKey=UUID.randomUUID();
            String fault="feature_fault_"+retryKey.toString().replace("-","");
            jdbc.execute("CREATE FUNCTION crm."+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"
                +tenant+"'::uuid AND NEW.request_key='"+retryKey+"'::uuid THEN RAISE EXCEPTION 'FEATURE_TEST_FAULT'; END IF; RETURN NEW; END; $$");
            jdbc.execute("CREATE TRIGGER "+fault+" AFTER INSERT ON crm.int_feature_candidate FOR EACH ROW EXECUTE FUNCTION crm."+fault+"()");
            try{
                var error=assertThrows(org.springframework.dao.DataAccessException.class,()->store.write(actor,tenant,retryKey,request));
                assertTrue(error.getMostSpecificCause().getMessage().contains("FEATURE_TEST_FAULT"));
                assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM crm.int_feature_candidate WHERE tenant_id=? AND request_key=?",Long.class,tenant,retryKey).longValue());
            }finally{
                jdbc.execute("DROP TRIGGER "+fault+" ON crm.int_feature_candidate");
                jdbc.execute("DROP FUNCTION crm."+fault+"()");
            }
            var restarted=new FeatureCandidateStore(jdbc,manager,context.getBean(L3DerivedSeriesService.class),
                context.getBean(MultidimensionalCoverageService.class));
            var recovered=restarted.write(actor,tenant,retryKey,request);
            assertFalse(recovered.replay());
            assertEquals(recovered.id(),store.write(actor,tenant,retryKey,request).id());

            var writerStart=new CyclicBarrier(2);
            var x=pool.submit(()->{writerStart.await(15,TimeUnit.SECONDS);return store.write(actor,tenant,key,request);});
            var y=pool.submit(()->{writerStart.await(15,TimeUnit.SECONDS);return store.write(actor,tenant,key,request);});
            var first=x.get(120,TimeUnit.SECONDS);var second=y.get(120,TimeUnit.SECONDS);
            assertEquals(first.id(),second.id());assertNotEquals(first.replay(),second.replay());
            assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM crm.int_feature_candidate WHERE tenant_id=? AND request_key=?",Long.class,tenant,key).longValue());
            assertEquals(MetricActivityGate.DecisionEligibility.BLOCKED,store.read(actor,tenant,key,request).currentActivity().decisionEligibility());
            assertThrows(SecurityException.class,()->store.read(foreign,tenant,key,request));
            revoke.run();
            try{
                assertThrows(SecurityException.class,()->store.read(actor,tenant,key,request));
                assertThrows(SecurityException.class,()->store.write(actor,tenant,key,request));
            }finally{restore.run();}

            var other=new FeatureCandidateStore.Request(generation,proof,L3DerivedSeriesService.Metric.STOCK_IN,
                filter,PeriodResolver.Code.D,anchor,2,DerivedMetricEngine.Operator.MAX);
            assertThrows(IllegalStateException.class,()->store.write(actor,tenant,key,other));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
                "UPDATE crm.int_feature_candidate SET payload='{}'::jsonb WHERE tenant_id=? AND request_key=?",tenant,key));
        }catch(Exception e){throw new AssertionError("FEATURE_JOURNEY",e);}
        finally{pool.shutdownNow();}
        return ()->assertEquals(MetricActivityGate.Activity.STALE,store.read(actor,tenant,key,request).currentActivity().activity());
    }
}
