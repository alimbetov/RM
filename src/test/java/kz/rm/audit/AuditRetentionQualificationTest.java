package kz.rm.audit;

import kz.rm.iam.OrganizationEntity;
import kz.rm.iam.OrganizationRepository;
import kz.rm.iam.UserAccountEntity;
import kz.rm.iam.UserAccountRepository;
import kz.rm.iam.WorkspaceEntity;
import kz.rm.iam.WorkspaceRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class AuditRetentionQualificationTest {
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditRetentionService retention;
    @Autowired UserAccountRepository accounts;
    @Autowired OrganizationRepository orgs;
    @Autowired WorkspaceRepository workspaces;

    private void postgres() throws Exception {
        try(var conn=source.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
    }
    private UUID tenant() {
        var org=orgs.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Retention LLP","Retention","KZ"));
        return workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
    }
    private UUID actor() {
        return accounts.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"retention-"+UUID.randomUUID(),"Retention actor")).getId();
    }
    private UUID event(UUID tenant,UUID actor,String timestamp) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.audit_event(tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
            VALUES (?,?,?,?,?,?)
            """,tenant,id,actor,UUID.randomUUID(),"ORDER_PLACED",
                Timestamp.from(Instant.parse(timestamp)));
        return id;
    }
    private int count(UUID tenant,UUID id) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM crm.audit_event WHERE tenant_id=? AND id=?
            """,Integer.class,tenant,id);
    }
    private void policy(UUID tenant,int years) {
        jdbc.update("""
            INSERT INTO crm.audit_retention_policy
            (tenant_id,retention_years,enabled,approved_at,updated_at)
            VALUES (?,?,TRUE,?,?)
            """,tenant,years,Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));
    }

    @Test void threeAndFiveYearPoliciesAndLegalHoldsAreEnforced() throws Exception {
        postgres();
        UUID actor=actor(),three=tenant(),five=tenant(),notApproved=tenant();
        policy(three,3);
        policy(five,5);
        UUID expiredThree=event(three,actor,"2022-06-01T00:00:00Z");
        UUID liveThree=event(three,actor,"2025-05-01T00:00:00Z");
        UUID heldThree=event(three,actor,"2019-02-01T00:00:00Z");
        UUID expiredFive=event(five,actor,"2019-04-01T00:00:00Z");
        UUID liveFive=event(five,actor,"2023-04-01T00:00:00Z");
        UUID withoutPolicy=event(notApproved,actor,"2018-01-01T00:00:00Z");
        jdbc.update("""
            INSERT INTO crm.audit_retention_hold(tenant_id,event_id,reason_code,created_at)
            VALUES (?,?,'LEGAL_CASE',?)
            """,three,heldThree,Timestamp.from(Instant.now()));
        var result=retention.runBounded(1,10);
        assertThat(result.deletedRows()).isEqualTo(2);
        assertThat(result.batches()).isEqualTo(2);
        assertThat(count(three,expiredThree)).isZero();
        assertThat(count(five,expiredFive)).isZero();
        assertThat(count(three,liveThree)).isOne();
        assertThat(count(five,liveFive)).isOne();
        assertThat(count(three,heldThree)).isOne();
        assertThat(count(notApproved,withoutPolicy)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT outcome FROM crm.audit_retention_run WHERE id=?
            """,String.class,result.runId())).isEqualTo("DONE");
    }

    @Test void unapprovedPolicyAndBatchLimitsFailClosed() throws Exception {
        postgres();
        UUID tenant=tenant(),actor=actor();
        UUID old=event(tenant,actor,"2017-01-01T00:00:00Z");
        jdbc.update("""
            INSERT INTO crm.audit_retention_policy(tenant_id,retention_years,enabled,updated_at)
            VALUES (?,5,FALSE,?)
            """,tenant,Timestamp.from(Instant.now()));
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.audit_retention_policy SET enabled=TRUE WHERE tenant_id=?
            """,tenant)).isInstanceOf(Exception.class);
        var result=retention.runBounded(5,2);
        assertThat(result.deletedRows()).isZero();
        assertThat(count(tenant,old)).isOne();
        assertThatThrownBy(()->retention.runBounded(0,1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->retention.runBounded(1001,1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->retention.runBounded(1,101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
