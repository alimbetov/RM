package kz.rm.crm;

/**
 * An internal, transaction-mandatory CRM event handoff.
 * Implementations must join the CRM cancellation transaction and must
 * never cancel tasks or deliver messages.
 */
public interface CrmCancellationReviewIntentPort {
    void onCancelled(CrmCancellationEventRef event);
}
