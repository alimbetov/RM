package kz.rm.architecture;

import kz.rm.iam.*;
import kz.rm.organization.OrganizationLocationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class DomainPersistenceBaselineQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;

    private UUID tenant() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Baseline LLP","Baseline","KZ"));
        return workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
    }

    private UUID catalogItem(UUID tenant, String sku) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,? ,?,'PRODUCT','zh-Hans','DRAFT',?)
            """,tenant,id,sku,Timestamp.from(Instant.now()));
        return id;
    }

    @Test void allEightPreviouslyUnpersistedModulesHaveTables() {
        String[] tables={"FILE_OBJECT","FILE_STORAGE","FILE_ATTACHMENT",
                "CAT_ITEM","CAT_ITEM_I18N","CAT_PRICE","INV_STOCK_BALANCE",
                "FIN_INVOICE","MKT_CAMPAIGN","TASK_ITEM","AUDIT_EVENT",
                "SUB_PLAN","SUB_SUBSCRIPTION"};
        for (String table:tables) {
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA='CRM' AND TABLE_NAME=?
                """,Integer.class,table);
            assertThat(count).as(table).isEqualTo(1);
        }
    }

    @Test void catalogTranslationsHaveFourLocalesAndTenantScopedForeignKeys() {
        UUID a=tenant(),b=tenant();
        UUID item=catalogItem(a,"sku-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.cat_item_i18n(tenant_id,item_id,locale,name)
            VALUES (?,?,?,?)
            """,a,item,"zh-Hans","打印机");
        jdbc.update("""
            INSERT INTO crm.cat_item_i18n(tenant_id,item_id,locale,name)
            VALUES (?,?,?,?)
            """,a,item,"kk","Принтер");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.cat_item_i18n WHERE tenant_id=? AND item_id=?
            """,Integer.class,a,item)).isEqualTo(2);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.cat_item_i18n(tenant_id,item_id,locale,name)
            VALUES (?,?,?,?)
            """,b,item,"en","Wrong tenant")).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.cat_item_i18n(tenant_id,item_id,locale,name)
            VALUES (?,?,?,?)
            """,a,item,"zh-Hant","Unsupported")).isInstanceOf(Exception.class);
    }

    @Test void fileStorageAndAttachmentsCannotReferenceAnotherTenantFile() {
        UUID a=tenant(),b=tenant(),fileId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.file_object(tenant_id,id,mime_type,size_bytes,state,created_at)
            VALUES (? ,?,'application/pdf',100,'STAGED',?)
            """,a,fileId,Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm.file_storage(tenant_id,file_id,provider,storage_key,state)
            VALUES (?,?,'LOCAL','private/path','STAGED')
            """,a,fileId);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.file_storage(tenant_id,file_id,provider,storage_key,state)
            VALUES (?,?,'LOCAL','other-tenant','ACTIVE')
            """,b,fileId)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.file_attachment(tenant_id,id,file_id,owner_type,owner_id,purpose,created_at)
            VALUES (?,?,?,'ORDER',?,'INVOICE',?)
            """,b,UUID.randomUUID(),fileId,UUID.randomUUID(),Timestamp.from(Instant.now())))
                .isInstanceOf(Exception.class);
    }

    @Test void inventoryRejectsCrossTenantItemAndInvalidReserve() {
        UUID a=tenant(),b=tenant();
        UUID item=catalogItem(a,"stock-"+UUID.randomUUID());
        UUID loc=UUID.randomUUID();
        var orgB=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Warehouse LLP","Warehouse","KZ"));
        // We only need a valid KATO to insert a tenant-local warehouse.
        String kato=jdbc.queryForObject("""
            SELECT code FROM ref_kato_territory WHERE is_deleted=FALSE AND level_no <=3 ORDER BY code LIMIT 1
            """,String.class);
        jdbc.update("""
            INSERT INTO org_location
            (id,workspace_id,location_type,name,kato_code,status,created_at,updated_at,row_version)
            VALUES (?,?,'WAREHOUSE','W',?,'ACTIVE',?,?,0)
            """,loc,a,kato,Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?,?,?)
            """,a,loc,item,new BigDecimal("10"),new BigDecimal("3"));
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)
            VALUES (?,?,?,?,?)
            """,b,loc,item,BigDecimal.ONE,BigDecimal.ZERO)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.inv_stock_balance SET reserved=11 WHERE tenant_id=? AND location_id=? AND item_id=?
            """,a,loc,item)).isInstanceOf(Exception.class);
    }

    @Test void subscriptionAndMarketingHaveCorrectStateConstraints() {
        UUID t=tenant(),plan=UUID.randomUUID();
        jdbc.update("INSERT INTO crm.sub_plan(id,code,state) VALUES (?,?,?)",
                plan,"PLAN-"+UUID.randomUUID(),"ACTIVE");
        jdbc.update("""
            INSERT INTO crm.sub_subscription(tenant_id,id,plan_id,state,started_at)
            VALUES (?,?,?,'TRIAL',?)
            """,t,UUID.randomUUID(),plan,Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm.mkt_campaign(tenant_id,id,state,default_locale,created_at)
            VALUES (?,?,'DRAFT','zh-Hans',?)
            """,t,UUID.randomUUID(),Timestamp.from(Instant.now()));
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.mkt_campaign(tenant_id,id,state,default_locale,created_at)
            VALUES (?,?,'DRAFT','zh-TW',?)
            """,t,UUID.randomUUID(),Timestamp.from(Instant.now()))).isInstanceOf(Exception.class);
    }
}
