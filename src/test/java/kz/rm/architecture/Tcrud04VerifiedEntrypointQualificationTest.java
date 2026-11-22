package kz.rm.architecture;

import kz.rm.autoorder.AuthenticatedBuyerOrders;
import kz.rm.autoorder.AutoOrderService;
import kz.rm.crm.*;
import kz.rm.files.*;
import kz.rm.iam.*;
import kz.rm.inventory.*;
import kz.rm.organization.*;
import kz.rm.sales.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * TCRUD-04: fail CI when a trusted application port accidentally publishes an
 * actor-UUID overload instead of a VerifiedActor entrypoint. This is a Java
 * boundary rule, not a substitute for per-tenant PostgreSQL qualification.
 */
class Tcrud04VerifiedEntrypointQualificationTest {

    @Test void allProtectedBusinessPortsRequireVerifiedActorAsFirstArgument() {
        var exposed=List.of(
                AuthenticatedCrmCommands.class, AuthenticatedCrmQueries.class,
                CrmCategoryService.class, CrmContractService.class, CrmHistoryService.class,
                AuthenticatedOrganizationLocations.class,
                AuthenticatedSalesCommands.class, AuthenticatedSalesQueries.class,
                AuthenticatedInventoryCommands.class, AuthenticatedInventoryQueries.class,
                AuthenticatedFilesCommands.class, AuthenticatedFilesQueries.class,
                AuthenticatedFilesAttachments.class, AuthenticatedFilesBinaryReads.class,
                AuthenticatedBuyerOrders.class, VerifiedTenantRoleCommands.class,
                TenantAccessStatusService.class, PlatformTenantRecoveryService.class,
                MyAccessService.class, GlobalUserProfileService.class
        );
        for(Class<?> type:exposed) {
            var methods=java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(m->Modifier.isPublic(m.getModifiers()))
                    .filter(m->!Modifier.isStatic(m.getModifiers()) && !m.isSynthetic())
                    .toList();
            assertThat(methods).as("No exposed methods for "+type.getName()).isNotEmpty();
            for(var method:methods) {
                assertThat(method.getParameterCount())
                        .as("Verified actor parameter missing: "+method).isGreaterThan(0);
                assertThat(method.getParameterTypes()[0])
                        .as("Raw userId/tenant bypass at "+method)
                        .isEqualTo(VerifiedActor.class);
            }
        }
    }

    @Test void buyerOrdersRawUuidIsNoLongerPublic() throws Exception {
        var raw=AutoOrderService.class.getDeclaredMethod("buyerOrders",UUID.class);
        assertThat(Modifier.isPublic(raw.getModifiers())).isFalse();
        var trusted=AuthenticatedBuyerOrders.class.getDeclaredMethod("mine",VerifiedActor.class);
        assertThat(Modifier.isPublic(trusted.getModifiers())).isTrue();
    }

    /**
     * The existing IAM administration service has older public UUID operations
     * consumed by test and bootstrap code across packages. Lock down the
     * current legacy surface: new raw actor entrypoints fail this test. A
     * subsequent IAM migration must retire the allowlist, not grow it.
     */
    @Test void legacyTenantRoleUuidExposureCannotGrowWithoutExplicitReview() {
        Set<String> legacy=Set.of("createRole","grant","revoke","assign","unassign",
                "enable","setRoleActive","listGrants","listAssignments","listRoles");
        var raw=java.util.Arrays.stream(TenantRoleService.class.getDeclaredMethods())
                .filter(m->Modifier.isPublic(m.getModifiers()))
                .filter(m->!Modifier.isStatic(m.getModifiers()) && !m.isSynthetic())
                .filter(m->m.getParameterCount()>0 && m.getParameterTypes()[0]==UUID.class)
                .map(m->m.getName()).collect(java.util.stream.Collectors.toSet());
        assertThat(raw).as("Legacy IAM raw UUID surface must not expand")
                .isEqualTo(legacy);
    }
}
