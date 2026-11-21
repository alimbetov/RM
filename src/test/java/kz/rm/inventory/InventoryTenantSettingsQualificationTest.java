package kz.rm.inventory;

import kz.rm.iam.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class InventoryTenantSettingsQualificationTest {
    @Autowired InventoryTenantSettingsService settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;

    private record Context(UUID tenant,UUID owner,UUID viewer) {}
    private Context organization() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Warehouse Settings LLP","Warehouse Settings","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        var owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"settings-owner-"+UUID.randomUUID(),"Owner"));
        var viewer=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"settings-viewer-"+UUID.randomUUID(),"Viewer"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner.getId(),OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),viewer.getId(),OrganizationMembershipEntity.Role.VIEWER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        return new Context(workspace.getId(),owner.getId(),viewer.getId());
    }

    @Test void oneTenantSettingWithDefaultSafeSwitchesAndApprovedCostPolicy() {
        var x=organization();
        var initial=settings.initialize(x.owner(),x.tenant());
        assertThat(initial.valuationMethod()).isNull();
        assertThat(initial.activePolicyId()).isNull();
        assertThat(initial.autoReserveOnConfirm()).isFalse();
        assertThat(initial.pickingStrategy()).isEqualTo(
                InventoryTenantSettingsService.PickingStrategy.FIFO);
        assertThat(settings.initialize(x.owner(),x.tenant()).rowVersion()).isEqualTo(0);
        var approved=settings.approveCostPolicy(
                x.owner(),x.tenant(),
                InventoryTenantSettingsService.ValuationMethod.MOVING_WEIGHTED_AVERAGE,
                "KZT",initial.rowVersion());
        assertThat(approved.valuationMethod()).isEqualTo(
                InventoryTenantSettingsService.ValuationMethod.MOVING_WEIGHTED_AVERAGE);
        assertThat(approved.functionalCurrency()).isEqualTo("KZT");
        assertThat(approved.policyRevision()).isEqualTo(1);
        assertThat(approved.activePolicyId()).isNotNull();
        assertThat(approved.rowVersion()).isEqualTo(1);
        var edited=settings.updatePicking(
                x.owner(),x.tenant(),InventoryTenantSettingsService.PickingStrategy.FEFO,
                false,approved.rowVersion());
        assertThat(edited.pickingStrategy()).isEqualTo(
                InventoryTenantSettingsService.PickingStrategy.FEFO);
        assertThat(edited.valuationMethod()).isEqualTo(
                InventoryTenantSettingsService.ValuationMethod.MOVING_WEIGHTED_AVERAGE);
        assertThat(edited.rowVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_cost_policy_version WHERE tenant_id=?
            """,Integer.class,x.tenant())).isEqualTo(1);
        assertThatThrownBy(()->settings.updatePicking(x.owner(),x.tenant(),
                InventoryTenantSettingsService.PickingStrategy.MANUAL,false,0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void newApprovalCreatesImmutableRevisionInsteadOfRewritingHistory() {
        var x=organization();
        var initial=settings.initialize(x.owner(),x.tenant());
        var fifo=settings.approveCostPolicy(
                x.owner(),x.tenant(),InventoryTenantSettingsService.ValuationMethod.FIFO,
                "KZT",initial.rowVersion());
        var avg=settings.approveCostPolicy(
                x.owner(),x.tenant(),
                InventoryTenantSettingsService.ValuationMethod.MOVING_WEIGHTED_AVERAGE,
                "KZT",fifo.rowVersion());
        assertThat(avg.policyRevision()).isEqualTo(2);
        assertThat(avg.activePolicyId()).isNotEqualTo(fifo.activePolicyId());
        assertThat(jdbc.queryForObject("""
            SELECT valuation_method FROM crm.inv_cost_policy_version WHERE tenant_id=? AND id=?
            """,String.class,x.tenant(),fifo.activePolicyId())).isEqualTo("FIFO");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_cost_policy_version WHERE tenant_id=?
            """,Integer.class,x.tenant())).isEqualTo(2);
    }

    @Test void viewerAndOutsiderCannotChangeOrReadPrivateSettings() {
        var x=organization();
        settings.initialize(x.owner(),x.tenant());
        assertThatThrownBy(()->settings.get(x.viewer(),x.tenant()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->settings.approveCostPolicy(
                x.viewer(),x.tenant(),InventoryTenantSettingsService.ValuationMethod.FIFO,
                "KZT",0)).isInstanceOf(SecurityException.class);
        var outsider=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"settings-outside-"+UUID.randomUUID(),"Outside"));
        assertThatThrownBy(()->settings.get(outsider.getId(),x.tenant()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void rejectsLifoUnknownCurrencyAndUnvaluedExistingStock() {
        var x=organization();
        settings.initialize(x.owner(),x.tenant());
        assertThatThrownBy(()->settings.approveCostPolicy(
                x.owner(),x.tenant(),InventoryTenantSettingsService.ValuationMethod.FIFO,
                "not-currency",0)).isInstanceOf(IllegalArgumentException.class);
        UUID item=UUID.randomUUID(),location=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        String kato=jdbc.queryForObject("""
            SELECT code FROM public.ref_kato_territory
            WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
            """,String.class);
        jdbc.update("""
            INSERT INTO public.org_location
              (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
            VALUES (?,?,'WAREHOUSE','Valuation warehouse',?,'ACTIVE',?,?,0)
            """,location,x.tenant(),kato,now,now);
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',?)
            """,x.tenant(),item,"sku-"+UUID.randomUUID(),now);
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?,10,0)
            """,x.tenant(),location,item);
        assertThatThrownBy(()->settings.approveCostPolicy(
                x.owner(),x.tenant(),InventoryTenantSettingsService.ValuationMethod.FIFO,
                "KZT",0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valuation migration");
    }

    @Test void postgresBlocksMutationOfApprovedPolicy() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var x=organization();
        var settingsRow=settings.initialize(x.owner(),x.tenant());
        var approved=settings.approveCostPolicy(
                x.owner(),x.tenant(),InventoryTenantSettingsService.ValuationMethod.FIFO,
                "KZT",settingsRow.rowVersion());
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.inv_cost_policy_version SET valuation_method='MOVING_WEIGHTED_AVERAGE'
            WHERE tenant_id=? AND id=?
            """,x.tenant(),approved.activePolicyId())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.inv_cost_policy_version WHERE tenant_id=? AND id=?
            """,x.tenant(),approved.activePolicyId())).isInstanceOf(Exception.class);
    }
}
