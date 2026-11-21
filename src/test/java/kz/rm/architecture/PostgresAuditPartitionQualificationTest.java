package kz.rm.architecture;

import kz.rm.iam.OrganizationEntity;
import kz.rm.iam.OrganizationRepository;
import kz.rm.iam.UserAccountEntity;
import kz.rm.iam.UserAccountRepository;
import kz.rm.iam.WorkspaceEntity;
import kz.rm.iam.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class PostgresAuditPartitionQualificationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;

    private void onlyPostgres() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
    }

    @Test void rangeAndHashPartitionsRouteEventsToCorrectYearAndTenant() throws Exception {
        onlyPostgres();
        var actor=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"partition-actor-"+UUID.randomUUID(),"Partition Actor"));
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Audit LLP","Audit","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID event2026=UUID.randomUUID(),event2027=UUID.randomUUID(),older=UUID.randomUUID();
        insert(tenant,event2026,actor.getId(),"2026-11-21T08:00:00Z");
        insert(tenant,event2027,actor.getId(),"2027-04-12T08:00:00Z");
        insert(tenant,older,actor.getId(),"2024-03-01T08:00:00Z");
        String p26=partition(tenant,event2026);
        String p27=partition(tenant,event2027);
        String pOld=partition(tenant,older);
        assertThat(p26).startsWith("audit_event_2026_h");
        assertThat(p27).startsWith("audit_event_2027_h");
        assertThat(pOld).startsWith("audit_event_other_h");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.audit_event WHERE tenant_id=? AND
                occurred_at>=? AND occurred_at<?
            """,Integer.class,tenant,Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")))).isEqualTo(1);
        Integer partitions=jdbc.queryForObject("""
            SELECT count(*) FROM pg_partition_tree('crm.audit_event'::regclass) WHERE isleaf
            """,Integer.class);
        assertThat(partitions).isEqualTo(16);
    }

    @Test void compositeKeyAndTenantForeignKeyStillApply() throws Exception {
        onlyPostgres();
        var actor=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"partition-fk-"+UUID.randomUUID(),"Partition Actor"));
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Audit FK LLP","Audit FK","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID id=UUID.randomUUID();
        insert(tenant,id,actor.getId(),"2026-02-01T00:00:00Z");
        assertThatThrownBy(()->insert(tenant,id,actor.getId(),"2026-02-01T00:00:00Z"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(()->insert(UUID.randomUUID(),UUID.randomUUID(),
                actor.getId(),"2026-02-01T00:00:00Z")).isInstanceOf(Exception.class);
    }

    private void insert(UUID tenant,UUID event,UUID actor,String occurred) {
        jdbc.update("""
            INSERT INTO crm.audit_event(tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
            VALUES (?,?,?,?,?,?)
            """,tenant,event,actor,UUID.randomUUID(),"ORDER_PLACED",
                Timestamp.from(Instant.parse(occurred)));
    }
    private String partition(UUID tenant,UUID id) {
        return jdbc.queryForObject("""
            SELECT tableoid::regclass::text FROM crm.audit_event WHERE tenant_id=? AND id=?
            """,String.class,tenant,id).replace("crm.","");
    }
}
