package kz.rm.crm;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T00 feature-OFF implementation. No policy, obligation or notification
 * intent is created until a later approved stage replaces this port.
 */
@Component
public class DisabledCrmCancellationReviewIntentPort implements CrmCancellationReviewIntentPort {
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCancelled(CrmCancellationEventRef event) {
        if (event == null) throw new IllegalArgumentException("Canonical cancellation event required");
    }
}
