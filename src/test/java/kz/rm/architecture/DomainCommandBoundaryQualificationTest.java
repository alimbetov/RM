package kz.rm.architecture;

import kz.rm.iam.TenantAuthorization;
import kz.rm.crm.AuthenticatedCrmCommands;
import kz.rm.crm.AuthenticatedCrmQueries;
import kz.rm.crm.CrmCustomerService;
import kz.rm.organization.AuthenticatedOrganizationLocations;
import kz.rm.organization.OrganizationLocationEntity;
import kz.rm.organization.dto.LocationSearch;
import kz.rm.iam.VerifiedActor;
import kz.rm.inventory.AuthenticatedInventoryCommands;
import kz.rm.inventory.AuthenticatedInventoryQueries;
import kz.rm.inventory.dto.StockSearch;
import kz.rm.sales.AuthenticatedSalesQueries;
import kz.rm.sales.dto.SalesOrderSearch;
import kz.rm.inventory.InventoryAuthorizedCommandService;
import kz.rm.inventory.InventoryMovementService;
import kz.rm.inventory.InventoryReservationService;
import kz.rm.sales.AuthenticatedSalesCommands;
import kz.rm.sales.SalesInventoryCommandService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Compilation-level boundary regression: a new controller cannot call
 * untrusted UUID-based domain mutations across Java packages.
 */
class DomainCommandBoundaryQualificationTest {
    @Test void rawCommandMutationsAreNotPublic() throws Exception {
        assertThat(Modifier.isPublic(SalesInventoryCommandService.class
                .getDeclaredMethod("confirm",UUID.class,UUID.class,UUID.class,UUID.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(InventoryReservationService.class
                .getDeclaredMethod("reserve",UUID.class,UUID.class,UUID.class,UUID.class,
                        UUID.class,BigDecimal.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(InventoryReservationService.class
                .getDeclaredMethod("fulfill",UUID.class,UUID.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(InventoryReservationService.class
                .getDeclaredMethod("release",UUID.class,UUID.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(SalesInventoryCommandService.class
                .getDeclaredMethod("cancel",UUID.class,UUID.class,UUID.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(SalesInventoryCommandService.class
                .getDeclaredMethod("fulfill",UUID.class,UUID.class,UUID.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(InventoryAuthorizedCommandService.class
                .getDeclaredMethod("correct",UUID.class,UUID.class,UUID.class,UUID.class,
                        String.class,BigDecimal.class).getModifiers())).isFalse();

        assertThat(Modifier.isPublic(InventoryMovementService.class
                .getDeclaredMethod("adjust",UUID.class,UUID.class,UUID.class,
                        String.class,BigDecimal.class).getModifiers())).isFalse();
        // Guard every overload, including reason-explicit movement writes.
        assertThat(InventoryMovementService.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("adjust"))
                .isNotEmpty()
                .allSatisfy(method -> assertThat(Modifier.isPublic(method.getModifiers()))
                        .as("Raw movement overload must not be public: " + method)
                        .isFalse());
        assertThat(Modifier.isPublic(InventoryAuthorizedCommandService.class
                .getDeclaredMethod("receive",UUID.class,UUID.class,UUID.class,UUID.class,
                        String.class,BigDecimal.class).getModifiers())).isFalse();
    }

    @Test void publicReadBoundariesRequireVerifiedActorAndRawBeansAreNotPublic() throws Exception {
        assertThat(AuthenticatedSalesQueries.class.getDeclaredMethod("search",
                VerifiedActor.class,UUID.class,SalesOrderSearch.class,int.class,int.class))
                .isNotNull();
        assertThat(AuthenticatedInventoryQueries.class.getDeclaredMethod("search",
                VerifiedActor.class,UUID.class,StockSearch.class,int.class,int.class))
                .isNotNull();
        assertThat(Modifier.isPublic(Class.forName("kz.rm.sales.SalesOrderQueryService").getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(Class.forName("kz.rm.sales.SalesOrderRepository").getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(Class.forName("kz.rm.inventory.StockBalanceQueryService").getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(Class.forName("kz.rm.inventory.StockBalanceRepository").getModifiers()))
                .isFalse();
    }


    @Test void crmAndOrganizationRawEntryPointsStayInternal() throws Exception {
        assertThat(AuthenticatedCrmCommands.class.getDeclaredMethod("createCompany",
                VerifiedActor.class,UUID.class,String.class,String.class)).isNotNull();
        assertThat(AuthenticatedCrmQueries.class.getDeclaredMethod("getContact",
                VerifiedActor.class,UUID.class,UUID.class)).isNotNull();
        assertThat(AuthenticatedOrganizationLocations.class.getDeclaredMethod("create",
                VerifiedActor.class,UUID.class,OrganizationLocationEntity.Type.class,
                String.class,String.class,String.class)).isNotNull();
        assertThat(AuthenticatedOrganizationLocations.class.getDeclaredMethod("search",
                VerifiedActor.class,UUID.class,LocationSearch.class,int.class,int.class))
                .isNotNull();
        for(var method:CrmCustomerService.class.getDeclaredMethods())
            if(java.util.Set.of("createPerson","createCompany","getCustomer","addContact",
                    "getContact","recordEmailConsent","hasEmailConsent").contains(method.getName()))
                assertThat(Modifier.isPublic(method.getModifiers()))
                        .as("CRM raw actor boundary: "+method).isFalse();
        assertThat(Modifier.isPublic(Class.forName(
                "kz.rm.organization.OrganizationLocationService").getModifiers())).isFalse();
        assertThat(Modifier.isPublic(Class.forName(
                "kz.rm.organization.OrganizationLocationQueryService").getModifiers())).isFalse();
    }

    @Test void exposedCommandPortsRequireVerifiedIdentity() throws Exception {
        assertThat(AuthenticatedSalesCommands.class
                .getDeclaredMethod("confirm",VerifiedActor.class,UUID.class,UUID.class,UUID.class))
                .isNotNull();
        assertThat(AuthenticatedInventoryCommands.class
                .getDeclaredMethod("receive",VerifiedActor.class,UUID.class,UUID.class,
                        UUID.class,String.class,BigDecimal.class)).isNotNull();
        assertThat(VerifiedActor.class.getConstructors()).isEmpty();
        assertThat(TenantAuthorization.Decision.class.getConstructors()).isEmpty();
    }
}
