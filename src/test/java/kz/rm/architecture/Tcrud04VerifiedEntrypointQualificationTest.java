package kz.rm.architecture;

import kz.rm.autoorder.AuthenticatedBuyerOrders;
import kz.rm.catalog.CatalogItemService;
import kz.rm.catalog.CatalogQueryService;
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
                CrmInteractionService.class, CatalogItemService.class, CatalogQueryService.class,
                AuthenticatedOrganizationLocations.class,
                AuthenticatedSalesCommands.class, AuthenticatedSalesQueries.class,
                AuthenticatedInventoryCommands.class, AuthenticatedInventoryQueries.class,
                AuthenticatedInventorySettings.class,
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

    @Test void inventorySettingsUuidBackendMustStayInternal() {
        assertThat(Modifier.isPublic(InventoryTenantSettingsService.class.getModifiers())).isFalse();
        var raw=java.util.Arrays.stream(InventoryTenantSettingsService.class.getDeclaredMethods())
                .filter(m->!m.isSynthetic() && !Modifier.isStatic(m.getModifiers()))
                .filter(m->m.getParameterCount()>0 && m.getParameterTypes()[0]==UUID.class).toList();
        assertThat(raw).allSatisfy(m->assertThat(Modifier.isPublic(m.getModifiers()))
                .as("Settings raw actor bypass: "+m).isFalse());
    }

    @Test void buyerOrdersRawUuidIsNoLongerPublic() throws Exception {
        var raw=AutoOrderService.class.getDeclaredMethod("buyerOrders",UUID.class);
        assertThat(Modifier.isPublic(raw.getModifiers())).isFalse();
        var trusted=AuthenticatedBuyerOrders.class.getDeclaredMethod("mine",VerifiedActor.class);
        assertThat(Modifier.isPublic(trusted.getModifiers())).isTrue();
    }

    @Test void legacyTenantRoleUuidEntryPointsAreNotPublic() {
        Set<String> legacy=Set.of("createRole","grant","revoke","assign","unassign",
                "enable","setRoleActive","listGrants","listAssignments","listRoles");
        var raw=java.util.Arrays.stream(TenantRoleService.class.getDeclaredMethods())
                .filter(m->!Modifier.isStatic(m.getModifiers()) && !m.isSynthetic())
                .filter(m->m.getParameterCount()>0 && m.getParameterTypes()[0]==UUID.class)
                .filter(m->legacy.contains(m.getName())).toList();
        assertThat(raw).extracting(m->m.getName()).containsExactlyInAnyOrderElementsOf(legacy);
        assertThat(raw).allSatisfy(m->assertThat(Modifier.isPublic(m.getModifiers()))
                .as("Untrusted actorId may not be public: "+m).isFalse());
    }

    @Test void verifiedIamFacadeCoversRoleLifecycleAndReadOperations() {
        var names=Set.of("createRole","grant","revoke","assign","unassign","enable",
                "activateRole","listRoles","listGrants","listAssignments");
        var publicMethods=java.util.Arrays.stream(VerifiedTenantRoleCommands.class.getDeclaredMethods())
                .filter(m->Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()).toList();
        assertThat(publicMethods).extracting(m->m.getName())
                .containsExactlyInAnyOrderElementsOf(names);
        assertThat(publicMethods).allSatisfy(m->assertThat(m.getParameterTypes()[0])
                .isEqualTo(VerifiedActor.class));
    }

    @Test void anonymousRegistrationPortsRemainIamInternalUntilTransportAdmission() throws Exception {
        assertThat(Modifier.isPublic(EmailIdentityService.class
                .getDeclaredMethod("registerPendingEmail",String.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(EmailIdentityService.class
                .getDeclaredMethod("verifyRegistration",UUID.class,String.class).getModifiers())).isFalse();
        // Checkout compatibility is intentionally still an INTERNAL cross-module
        // workflow; it is NOT HTTP admission or bearer-token authentication.
        var publicUuidMethods=java.util.Arrays.stream(EmailIdentityService.class.getDeclaredMethods())
                .filter(m->Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .filter(m->m.getParameterCount()>0 && m.getParameterTypes()[0]==UUID.class)
                .map(m->m.getName()).collect(java.util.stream.Collectors.toSet());
        assertThat(publicUuidMethods).containsExactlyInAnyOrder("queueVerification","verifyEmail");
    }

    @Test void globalAdministrationAndTenantRecoveryNeverAcceptRawActorIds() {
        var sensitive=List.of(
                PlatformAdminGovernanceService.class,
                PlatformTenantBootstrapService.class,
                PlatformTenantRecoveryService.class,
                GlobalAccountStatusService.class,
                TenantOwnershipTransferService.class,
                TenantMemberEnrollmentService.class
        );
        for(var type:sensitive) {
            var operations=java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(m->Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                    .filter(m->!m.isSynthetic()).toList();
            assertThat(operations).as(type.getName()).isNotEmpty();
            assertThat(operations).allSatisfy(m->{
                assertThat(m.getParameterCount()).isPositive();
                assertThat(m.getParameterTypes()[0]).as("Untrusted actorId: "+m)
                        .isEqualTo(VerifiedActor.class);
            });
        }
    }

}
