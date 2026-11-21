package kz.rm.crm;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Tenant-bound CRM reads with independently checked PII permission. */
@Service
public class AuthenticatedCrmQueries {
    private final CrmCustomerService service;
    public AuthenticatedCrmQueries(CrmCustomerService service) { this.service=service; }
    private UUID id(VerifiedActor actor) {
        if(actor==null) throw new SecurityException("Verified actor required");
        return actor.userId();
    }
    public CrmCustomerService.Customer getCustomer(VerifiedActor actor,UUID tenant,UUID customer) {
        return service.getCustomer(id(actor),tenant,customer);
    }
    public CrmCustomerService.Contact getContact(VerifiedActor actor,UUID tenant,UUID contact) {
        return service.getContact(id(actor),tenant,contact);
    }
    public boolean hasEmailConsent(VerifiedActor actor,UUID tenant,UUID contact,String purpose) {
        return service.hasEmailConsent(id(actor),tenant,contact,purpose);
    }
}
