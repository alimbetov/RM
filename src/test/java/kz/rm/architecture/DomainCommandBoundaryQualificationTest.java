package kz.rm.architecture;

import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.inventory.AuthenticatedInventoryCommands;
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
        assertThat(Modifier.isPublic(InventoryMovementService.class
                .getDeclaredMethod("adjust",UUID.class,UUID.class,UUID.class,
                        String.class,BigDecimal.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(InventoryAuthorizedCommandService.class
                .getDeclaredMethod("receive",UUID.class,UUID.class,UUID.class,UUID.class,
                        String.class,BigDecimal.class).getModifiers())).isFalse();
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
