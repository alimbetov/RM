package kz.rm.architecture;

import kz.rm.audit.AuditAction;
import kz.rm.catalog.CatalogItem;
import kz.rm.files.FileObject;
import kz.rm.finance.Money;
import kz.rm.inventory.StockPosition;
import kz.rm.organization.OrganizationLocation;
import kz.rm.storefront.StorefrontSettings;
import kz.rm.subscription.PlanEntitlement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

class DomainModuleQualificationTest {
    @Test void exactlyFifteenOwnedDomainsWithUniquePrefixesAndSnakeCaseTables() {
        DomainModule.validateRegistry();
        assertThat(DomainModule.modules()).hasSize(15);
        assertThat(DomainModule.modules().stream().map(DomainModule::prefix).collect(Collectors.toSet()))
                .hasSize(15);
        assertThat(DomainModule.FILES.table("file_object")).isEqualTo("file_file_object");
        assertThat(DomainModule.FILES.table("object")).isEqualTo("file_object");
        assertThat(DomainModule.CATALOG.table("item_i18n")).isEqualTo("cat_item_i18n");
        assertThat(DomainModule.STOREFRONT.table("front")).isEqualTo("store_front");
        assertThatThrownBy(()->DomainModule.CRM.table("WrongName"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void financialAndInventoryInvariantsRemainInsideOwningDomain() {
        var ten=UUID.randomUUID();var loc=UUID.randomUUID();var sku=UUID.randomUUID();
        var p=new StockPosition(ten,loc,sku,new BigDecimal("10"),new BigDecimal("3"));
        assertThat(p.available()).isEqualByComparingTo("7");
        assertThatThrownBy(()->new StockPosition(ten,loc,sku,BigDecimal.ONE,new BigDecimal("2")))
                .isInstanceOf(IllegalArgumentException.class);
        var kzt=Currency.getInstance("KZT");
        assertThat(new Money(new BigDecimal("2.5"),kzt).add(new Money(new BigDecimal("3.5"),kzt)).amount())
                .isEqualByComparingTo("6");
        assertThatThrownBy(()->new Money(BigDecimal.ONE,kzt)
                .add(new Money(BigDecimal.ONE,Currency.getInstance("USD"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void regionsAreNotTenantIdsAndStorefrontSecurityRulesCannotBeDisabled() {
        UUID tenant=UUID.randomUUID();
        var location=new OrganizationLocation(tenant,UUID.randomUUID(),"110000000","11",
                OrganizationLocation.Kind.BRANCH);
        assertThat(location.tenantId()).isEqualTo(tenant);
        assertThat(location.regionZoneId()).isEqualTo("11");
        assertThatThrownBy(()->new OrganizationLocation(tenant,UUID.randomUUID(),"110000000","43",
                OrganizationLocation.Kind.BRANCH)).isInstanceOf(IllegalArgumentException.class);
        var store=new StorefrontSettings(tenant,"my-shop",StorefrontSettings.State.PUBLISHED,"zh-Hans",true);
        assertThat(store.canCheckout()).isTrue();
        assertThat(store.emailRequired()).isTrue();
        assertThat(store.autoProvisionBuyer()).isTrue();
        assertThat(new StorefrontSettings(tenant,"my-shop",StorefrontSettings.State.PAUSED,"kk",true)
                .canCheckout()).isFalse();
    }

    @Test void fileIsOnlyDownloadableWhenAvailableAndCatalogNeedsPublication() {
        UUID ten=UUID.randomUUID(),fid=UUID.randomUUID();
        assertThat(new FileObject(ten,fid,128,FileObject.State.STAGED).downloadable()).isFalse();
        assertThat(new FileObject(ten,fid,128,FileObject.State.AVAILABLE).downloadable()).isTrue();
        assertThatThrownBy(()->new FileObject(ten,fid,-1,FileObject.State.STAGED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CatalogItem(ten,UUID.randomUUID(),"SKU1",CatalogItem.Kind.PRODUCT,
                CatalogItem.State.PUBLISHED).orderable()).isTrue();
        assertThat(new CatalogItem(ten,UUID.randomUUID(),"SKU1",CatalogItem.Kind.PRODUCT,
                CatalogItem.State.ARCHIVED).orderable()).isFalse();
    }

    @Test void auditAndEntitlementsUseSafeStableCodes() {
        UUID ten=UUID.randomUUID();
        var audit=new AuditAction(ten,UUID.randomUUID(),"ORDER_PLACED",UUID.randomUUID(),Instant.now());
        assertThat(audit.actionCode()).isEqualTo("ORDER_PLACED");
        assertThatThrownBy(()->new AuditAction(ten,UUID.randomUUID(),"person@example.com",
                UUID.randomUUID(),Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PlanEntitlement(ten,"AUTOORDER_ENABLED",true).enabled()).isTrue();
    }
}
