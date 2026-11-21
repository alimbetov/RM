package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The only cross-domain entry to order-linked inventory mutations.
 * A SALES operation requires TWO IAM-issued, transaction-scoped decisions.
 * Raw inventory persistence operations are package-private.
 *
 * No HTTP endpoints and no client-supplied actor UUIDs.
 */
@Service
public class OrderInventoryPort {
    private final InventoryReservationService reservations;

    public OrderInventoryPort(InventoryReservationService reservations) {
        this.reservations=reservations;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public InventoryReservationService.Reservation reserve(
            TenantAuthorization.Decision seller,
            TenantAuthorization.Decision stock,
            UUID orderId,UUID orderLineId,UUID locationId,UUID itemId,BigDecimal quantity) {
        verify(seller,stock,DomainPermission.SALES_ORDER_CONFIRM,DomainPermission.INVENTORY_RESERVE);
        return reservations.reserve(stock.tenantId(),orderId,orderLineId,locationId,itemId,quantity);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public InventoryReservationService.Reservation release(
            TenantAuthorization.Decision seller,TenantAuthorization.Decision stock,UUID reservationId) {
        verify(seller,stock,DomainPermission.SALES_ORDER_CANCEL,DomainPermission.INVENTORY_RELEASE);
        return reservations.release(stock.tenantId(),reservationId);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public InventoryReservationService.Reservation fulfill(
            TenantAuthorization.Decision seller,TenantAuthorization.Decision stock,UUID reservationId) {
        verify(seller,stock,DomainPermission.SALES_ORDER_FULFILL,DomainPermission.INVENTORY_FULFILL);
        return reservations.fulfill(stock.tenantId(),reservationId);
    }

    private static void verify(TenantAuthorization.Decision sales,
                               TenantAuthorization.Decision inventory,
                               DomainPermission expectedSales,
                               DomainPermission expectedInventory) {
        if(sales==null || inventory==null ||
                !sales.validForCurrentTransaction() || !inventory.validForCurrentTransaction() ||
                sales.permission()!=expectedSales || inventory.permission()!=expectedInventory ||
                !sales.tenantId().equals(inventory.tenantId()) ||
                !sales.actorId().equals(inventory.actorId()))
            throw new SecurityException("Paired seller and inventory grants required");
    }
}
