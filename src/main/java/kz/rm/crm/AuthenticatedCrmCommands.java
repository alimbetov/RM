package kz.rm.crm;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Trusted service boundary; only IAM may construct VerifiedActor. */
@Service
public class AuthenticatedCrmCommands {
    private final CrmCustomerService service;
    public AuthenticatedCrmCommands(CrmCustomerService service) { this.service=service; }
    private UUID id(VerifiedActor actor) {
        if(actor==null) throw new SecurityException("Verified actor required");
        return actor.userId();
    }
    public CrmCustomerService.Customer createPerson(VerifiedActor actor,UUID tenant,String name,String kato) {
        return service.createPerson(id(actor),tenant,name,kato);
    }
    public CrmCustomerService.Customer createCompany(VerifiedActor actor,UUID tenant,String name,String kato) {
        return service.createCompany(id(actor),tenant,name,kato);
    }
    public CrmCustomerService.Contact addContact(VerifiedActor actor,UUID tenant,UUID customer,
                                                String name,String email,String phone) {
        return service.addContact(id(actor),tenant,customer,name,email,phone);
    }
    public UUID recordEmailConsent(VerifiedActor actor,UUID tenant,UUID contact,boolean granted,
                                   String purpose,String version,String source) {
        return service.recordEmailConsent(id(actor),tenant,contact,granted,purpose,version,source);
    }
}
