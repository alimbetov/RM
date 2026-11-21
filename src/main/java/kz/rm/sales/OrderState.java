package kz.rm.sales;
/** Seller-owned order state; confirmed commercial snapshots must not be rewritten. */
public enum OrderState {
    PLACED, CONFIRMED, PREPARING, READY_FOR_PICKUP, OUT_FOR_DELIVERY,
    COMPLETED, CANCELLED, REJECTED
}
