package kz.rm.fx.auto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class FxAutoPolicySchemaPgTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired kz.rm.fx.NbrkRateRevisionWriter writer;
    record F(UUID tenant, UUID user, UUID member, UUID offer, UUID anchor, UUID guard, UUID limit, UUID policy, UUID revision) {}
    private static UUID id(){return UUID.randomUUID();}
    private F fixture() throws Exception {
        try(var c=dataSource.getConnection()){
            org.junit.jupiter.api.Assumptions.assumeTrue("PostgreSQL".equals(c.getMetaData().getDatabaseProductName()), "PostgreSQL-specific constraints");
        }
        var f=new F(id(),id(),id(),id(),id(),id(),id(),id(),id());
        UUID org=id(), item=id();
        jdbc.update("INSERT INTO public.iam_user_account(id,display_name,status,created_at,updated_at) VALUES (?,'FX Owner','ACTIVE',now(),now())",f.user());
        jdbc.update("INSERT INTO public.org_organization(id,legal_name,display_name,organization_type,country_code,status,created_at,updated_at) VALUES (?,'FX LLP','FX','LEGAL_ENTITY','KZ','ACTIVE',now(),now())",org);
        jdbc.update("INSERT INTO public.iam_workspace(id,kind,organization_id,status,created_at) VALUES (?,'ORGANIZATION',?,'ACTIVE',now())",f.tenant(),org);
        jdbc.update("INSERT INTO public.iam_org_membership(id,organization_id,user_id,role,status,joined_at) VALUES (?,?,?,'OWNER','ACTIVE',now())",f.member(),org,f.user());
        jdbc.update("INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at) VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',now())",f.tenant(),item,"FX-"+id());
        jdbc.update("INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at) VALUES (?,?,?,'PUBLISHED',now())",f.tenant(),id(),"fx-"+id());
        jdbc.update("INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state,catalog_item_id,row_version) VALUES (?,?,'FX',100,'KZT','PUBLISHED',?,0)",f.tenant(),f.offer(),item);
        jdbc.update("INSERT INTO crm.sku_fx_policy_version(tenant_id,id,offer_id,revision,anchor_currency,anchor_amount,buffer_pct,markup_pct,rounding_step_kzt,approved_by,created_at) VALUES (?,?,?,1,'USD',1,0,0,1,?,now())",f.tenant(),f.anchor(),f.offer(),f.user());
        jdbc.update("INSERT INTO crm.sku_fx_guardrail_version(tenant_id,id,offer_id,revision,max_increase_pct,max_decrease_pct,min_price_kzt,max_price_kzt,max_rate_calendar_days,calculation_ttl_seconds,approved_by,created_at) VALUES (?,?,?,1,10,10,1,1000,3,60,?,now())",f.tenant(),f.guard(),f.offer(),f.user());
        jdbc.update("INSERT INTO crm.fx_auto_limit_policy_version VALUES (?,?,86400,15,15,100,10,86400,1000,20,1000,10,100)",f.tenant(),f.limit());
        jdbc.update("INSERT INTO crm.fx_auto_policy_revision VALUES (?,?,?,1,?,'{}',crm.fx_auto_sha_v1('{}'::jsonb),now())",f.tenant(),f.revision(),f.policy(),f.limit());
        return f;
    }
    private void enroll(F f,UUID offer,UUID anchor,UUID guard){
        jdbc.update("INSERT INTO crm.fx_auto_target_enrollment VALUES (?,?,?,?,?,now()+interval '1 day','EA','NONE')",f.tenant(),f.revision(),offer,anchor,guard);
    }
    private UUID approve(F f,UUID actor,UUID member,int count){
        UUID approval=id();
        jdbc.update("INSERT INTO crm.fx_auto_approval_evidence(tenant_id,id,policy_revision_id,payload_sha256,owner_user_id,membership_id,request_key,approved_at,expires_at,target_count,scope_sha256) VALUES (?,?,?,crm.fx_auto_sha_v1('{}'::jsonb),?,?,?,now(),now()+interval '1 day',?,crm.fx_auto_scope_sha_v1(?,?))",f.tenant(),approval,f.revision(),actor,member,id().toString(),count,f.tenant(),f.revision());
        return approval;
    }
    @Test void immutableEvidenceAndSealedScope() throws Exception {
        var f=fixture();enroll(f,f.offer(),f.anchor(),f.guard());
        var a=approve(f,f.user(),f.member(),1);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.fx_auto_approval_evidence SET expires_at=expires_at+interval '1 day' WHERE tenant_id=? AND id=?",f.tenant(),a)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->enroll(f,f.offer(),f.anchor(),f.guard())).hasMessageContaining("FX_AUTO_SCOPE_SEALED_OR_MISSING");
        assertThatThrownBy(()->jdbc.update("DELETE FROM crm.fx_auto_target_enrollment WHERE tenant_id=? AND policy_revision_id=?",f.tenant(),f.revision())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void crossTenantAndWrongOwnerCannotApprove() throws Exception {
        var a=fixture();var b=fixture();
        assertThatThrownBy(()->enroll(a,b.offer(),b.anchor(),b.guard())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        enroll(a,a.offer(),a.anchor(),a.guard());
        assertThatThrownBy(()->approve(a,b.user(),b.member(),1)).hasMessageContaining("FX_AUTO_OWNER_EVIDENCE_INVALID");
        assertThatThrownBy(()->approve(a,a.user(),a.member(),2)).hasMessageContaining("FX_AUTO_SCOPE_COUNT_MISMATCH");
        approve(a,a.user(),a.member(),1);
    }
    @Test void activationIsInertAndRevocationTerminal() throws Exception {
        var f=fixture();enroll(f,f.offer(),f.anchor(),f.guard());var a=approve(f,f.user(),f.member(),1);
        jdbc.update("INSERT INTO crm.fx_auto_policy_activation(tenant_id,policy_id,policy_revision_id,approval_id) VALUES (?,?,?,?)",f.tenant(),f.policy(),f.revision(),a);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.fx_auto_policy_activation SET state='ACTIVE' WHERE tenant_id=? AND policy_id=?",f.tenant(),f.policy())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.fx_auto_policy_activation SET state='REVOKED',execution_epoch=2 WHERE tenant_id=? AND policy_id=?",f.tenant(),f.policy())).hasMessageContaining("FX_AUTO_EPOCH_OR_TRANSITION_INVALID");
        assertThat(jdbc.update("UPDATE crm.fx_auto_policy_activation SET state='REVOKED',execution_epoch=1 WHERE tenant_id=? AND policy_id=? AND execution_epoch=0",f.tenant(),f.policy())).isEqualTo(1);
        assertThat(jdbc.update("UPDATE crm.fx_auto_policy_activation SET state='REVOKED',execution_epoch=1 WHERE tenant_id=? AND policy_id=? AND execution_epoch=0",f.tenant(),f.policy())).isZero();
        assertThatThrownBy(()->jdbc.update("UPDATE crm.fx_auto_policy_activation SET state='PAUSED',execution_epoch=2 WHERE tenant_id=? AND policy_id=?",f.tenant(),f.policy())).hasMessageContaining("FX_AUTO_EPOCH_OR_TRANSITION_INVALID");
    }

    @Test void directGuardTamperingAndTruncateRejected() throws Exception {
        var f=fixture();enroll(f,f.offer(),f.anchor(),f.guard());approve(f,f.user(),f.member(),1);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.fx_auto_scope_guard SET sealed=false WHERE tenant_id=? AND policy_revision_id=?",f.tenant(),f.revision())).hasMessageContaining("FX_AUTO_GUARD_DIRECT_MUTATION");
        assertThatThrownBy(()->jdbc.update("DELETE FROM crm.fx_auto_scope_guard WHERE tenant_id=? AND policy_revision_id=?",f.tenant(),f.revision())).hasMessageContaining("FX_AUTO_GUARD_DIRECT_MUTATION");
        assertThatThrownBy(()->jdbc.execute("TRUNCATE crm.fx_auto_target_enrollment CASCADE")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void canonicalPayloadAndScopeDigestCannotBeForged() throws Exception {
        var f=fixture();
        assertThat(jdbc.queryForObject("SELECT crm.fx_auto_sha_v1('{\"b\":2,\"a\":1}'::jsonb)=crm.fx_auto_sha_v1('{\"a\":1,\"b\":2}'::jsonb)",Boolean.class)).isTrue();
        assertThatThrownBy(()->jdbc.update("INSERT INTO crm.fx_auto_policy_revision VALUES (?,?,?,2,?,'{}',?,now())",f.tenant(),id(),f.policy(),f.limit(),"a".repeat(64))).hasMessageContaining("FX_AUTO_PAYLOAD_DIGEST_MISMATCH");
        enroll(f,f.offer(),f.anchor(),f.guard());
        assertThatThrownBy(()->jdbc.update("INSERT INTO crm.fx_auto_approval_evidence(tenant_id,id,policy_revision_id,payload_sha256,owner_user_id,membership_id,request_key,approved_at,expires_at,target_count,scope_sha256) VALUES (?,?,?,crm.fx_auto_sha_v1('{}'::jsonb),?,?,?,now(),now()+interval '1 day',1,?)",f.tenant(),id(),f.revision(),f.user(),f.member(),id().toString(),"b".repeat(64))).hasMessageContaining("FX_AUTO_SCOPE_DIGEST_MISMATCH");
        approve(f,f.user(),f.member(),1);
    }

    private record Sql(String text,Object... args) {}
    private Sql enrollmentSql(F f) {
        return new Sql("INSERT INTO crm.fx_auto_target_enrollment VALUES (?,?,?,?,?,now()+interval '1 day','EA','NONE')",f.tenant(),f.revision(),f.offer(),f.anchor(),f.guard());
    }
    private Sql approvalSql(F f) {
        return new Sql("INSERT INTO crm.fx_auto_approval_evidence(tenant_id,id,policy_revision_id,payload_sha256,owner_user_id,membership_id,request_key,approved_at,expires_at,target_count,scope_sha256) VALUES (?,?,?,crm.fx_auto_sha_v1('{}'::jsonb),?,?,?,now(),now()+interval '1 day',1,crm.fx_auto_scope_sha_v1(?,?))",f.tenant(),id(),f.revision(),f.user(),f.member(),id().toString(),f.tenant(),f.revision());
    }
    private record Sim(UUID id, UUID price) {}
    private Sim simulation(F f) {
        var approval=approve(f,f.user(),f.member(),1);
        var date=java.time.LocalDate.of(2026,11,20);
        writer.persist(date,java.util.List.of(new kz.rm.fx.FxRateProvider.Rate("USD","KZT",new java.math.BigDecimal("500"),java.math.BigDecimal.ONE,date,"NBRK")),"f".repeat(64));
        UUID rate=jdbc.queryForObject("SELECT id FROM crm.fx_official_rate_snapshot WHERE provider_code='NBRK' AND base_currency='USD' AND valid_date=? ORDER BY source_revision DESC LIMIT 1",UUID.class,java.sql.Date.valueOf(date));
        UUID price=id(), sim=id();
        jdbc.update("INSERT INTO crm.store_offer_price_revision(tenant_id,id,offer_id,offer_row_version,new_price,new_currency,actor_user_id,recorded_at) VALUES (?,?,?,0,100,'KZT',?,now())",f.tenant(),price,f.offer(),f.user());
        jdbc.update("INSERT INTO crm.fx_auto_simulation(tenant_id,id,policy_revision_id,approval_id,source_snapshot_id,request_key,evaluation_as_of,input_payload,input_sha256,item_count) VALUES (?,?,?,?,?,?,now(),'{}',crm.fx_auto_sha_v1('{}'::jsonb),1)",f.tenant(),sim,f.revision(),approval,rate,id().toString());
        return new Sim(sim,price);
    }
    private Sql itemSql(F f,Sim s) {
        return new Sql("INSERT INTO crm.fx_auto_simulation_item VALUES (?,?,?,?,?,'{}','WOULD_UPDATE',10)",f.tenant(),s.id(),f.revision(),f.offer(),s.price());
    }
    private Sql sealSql(F f,Sim s) {
        return new Sql("INSERT INTO crm.fx_auto_simulation_seal VALUES (?,?,crm.fx_auto_sim_sha_v1(?,?))",f.tenant(),s.id(),f.tenant(),s.id());
    }
    private void execute(Sql sql) {jdbc.update(sql.text(),sql.args());}
    private static void execute(java.sql.Connection c,Sql sql) throws java.sql.SQLException {
        try(var p=c.prepareStatement(sql.text())){
            for(int i=0;i<sql.args().length;i++)p.setObject(i+1,sql.args()[i]);
            p.executeUpdate();
        }
    }
    // Establish an actual blocked PostgreSQL backend before committing the winner.
    private String race(int isolation,Sql winner,Sql loser) throws Exception {
        try(var a=dataSource.getConnection();var b=dataSource.getConnection()){
            a.setTransactionIsolation(isolation); b.setTransactionIsolation(isolation);
            a.setAutoCommit(false); b.setAutoCommit(false);
            int pid;
            try(var st=b.createStatement();var rs=st.executeQuery("SELECT pg_backend_pid()")){rs.next();pid=rs.getInt(1);}
            try(var st=b.createStatement()){st.execute("SET LOCAL lock_timeout='10s'");}
            execute(a,winner);
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var future=executor.submit(()->{
                    try {execute(b,loser);b.commit();return "COMMITTED";}
                    catch(java.sql.SQLException ex){b.rollback();return ex.getSQLState()+":"+ex.getMessage();}
                });
                boolean blocked=false;
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline && !future.isDone()){
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,pid))){blocked=true;break;}
                    Thread.sleep(20);
                }
                assertThat(blocked).as("real PostgreSQL lock wait").isTrue();
                a.commit();
                return future.get(12,java.util.concurrent.TimeUnit.SECONDS);
            } finally {a.rollback();executor.shutdownNow();}
        }
    }
    @Test void enrollmentApprovalBothOrdersAndIsolationLevels() throws Exception {
        for(int iso:new int[]{java.sql.Connection.TRANSACTION_READ_COMMITTED,java.sql.Connection.TRANSACTION_REPEATABLE_READ}){
            var f=fixture();execute(enrollmentSql(f));
            String late=race(iso,approvalSql(f),enrollmentSql(f));
            assertThat(late).containsAnyOf("FX_AUTO_SCOPE_SEALED_OR_MISSING","40001");
            var g=fixture();
            String stale=race(iso,enrollmentSql(g),approvalSql(g));
            // The digest argument may be evaluated against a pre-wait snapshot.
            // Either a consistent commit or fail-closed rejection followed by fresh retry is valid.
            if(!stale.equals("COMMITTED")){
                assertThat(stale).containsAnyOf("FX_AUTO_SCOPE_DIGEST_MISMATCH","FX_AUTO_SCOPE_COUNT_MISMATCH","40001");
                execute(approvalSql(g));
            }
            assertThat(jdbc.queryForObject("SELECT sealed FROM crm.fx_auto_scope_guard WHERE tenant_id=? AND policy_revision_id=?",Boolean.class,g.tenant(),g.revision())).isTrue();
        }
    }
    @Test void simulationSealBothOrdersAndIsolationLevels() throws Exception {
        for(int iso:new int[]{java.sql.Connection.TRANSACTION_READ_COMMITTED,java.sql.Connection.TRANSACTION_REPEATABLE_READ}){
            var f=fixture();execute(enrollmentSql(f));var s=simulation(f);execute(itemSql(f,s));
            String late=race(iso,sealSql(f,s),itemSql(f,s));
            assertThat(late).containsAnyOf("FX_AUTO_SIM_SEALED_OR_MISSING","40001");
            var g=fixture();execute(enrollmentSql(g));var t=simulation(g);
            String stale=race(iso,itemSql(g,t),sealSql(g,t));
            if(!stale.equals("COMMITTED")){
                assertThat(stale).containsAnyOf("FX_AUTO_SIM_DIGEST_MISMATCH","FX_AUTO_SIM_COUNT_MISMATCH","40001");
                execute(sealSql(g,t));
            }
            assertThat(jdbc.queryForObject("SELECT sealed FROM crm.fx_auto_simulation_guard WHERE tenant_id=? AND simulation_id=?",Boolean.class,g.tenant(),t.id())).isTrue();
            assertThatThrownBy(()->jdbc.update("UPDATE crm.fx_auto_simulation_guard SET sealed=false WHERE tenant_id=? AND simulation_id=?",g.tenant(),t.id())).hasMessageContaining("FX_AUTO_GUARD_DIRECT_MUTATION");
        }
    }
}
