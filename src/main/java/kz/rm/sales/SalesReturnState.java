package kz.rm.sales;

/**
 * QR-07 state contract. R0 only creates REQUESTED; later commands must
 * explicitly enforce transitions rather than changing the original order.
 */
public enum SalesReturnState {
    REQUESTED, UNDER_REVIEW, APPROVED, REJECTED, AWAITING_RECEIPT,
    RECEIVED, INSPECTED, RESTOCKED, DISPOSED, QUARANTINED, CANCELLED_BY_BUYER;

    public boolean mayTransitionTo(SalesReturnState next) {
        if (next == null) return false;
        return switch (this) {
            case REQUESTED -> next == UNDER_REVIEW;
            case UNDER_REVIEW -> next == APPROVED || next == REJECTED;
            case APPROVED -> next == AWAITING_RECEIPT || next == CANCELLED_BY_BUYER;
            case AWAITING_RECEIPT -> next == RECEIVED || next == CANCELLED_BY_BUYER;
            case RECEIVED -> next == INSPECTED;
            case INSPECTED -> next == RESTOCKED || next == DISPOSED || next == QUARANTINED;
            case QUARANTINED -> next == RESTOCKED || next == DISPOSED;
            case REJECTED, RESTOCKED, DISPOSED, CANCELLED_BY_BUYER -> false;
        };
    }
}
