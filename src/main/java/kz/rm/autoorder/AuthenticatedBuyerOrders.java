package kz.rm.autoorder;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * TCRUD-04: Only the verified token subject can read its own orders.
 * Raw UUID lookup stays package-private for internal trusted workflows.
 */
@Service
public class AuthenticatedBuyerOrders {
    private final AutoOrderService orders;

    public AuthenticatedBuyerOrders(AutoOrderService orders) {
        this.orders=orders;
    }

    public org.springframework.data.domain.Page<AutoOrderService.OrderReceipt> history(
            VerifiedActor actor,int page,int size) {
        if(actor==null) throw new SecurityException("Verified buyer required");
        return orders.buyerOrdersPage(actor.userId(),page,size);
    }

    public List<AutoOrderService.OrderReceipt> mine(VerifiedActor actor) {
        if(actor==null) throw new SecurityException("Verified buyer required");
        return orders.buyerOrders(actor.userId());
    }
}
