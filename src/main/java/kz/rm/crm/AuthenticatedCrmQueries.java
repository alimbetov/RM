package kz.rm.crm;

import kz.rm.iam.VerifiedActor;
import kz.rm.crm.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Tenant-bound CRM reads with independently checked PII permission. */
@Service
public class AuthenticatedCrmQueries {
    private final CrmCustomerService service;
    private final CrmSearchService search;
    public AuthenticatedCrmQueries(CrmCustomerService service,CrmSearchService search) {
        this.service=service;this.search=search;
    }
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
    /** No person names or contact PII in customer list. */
    public Page<CustomerSummary> customers(VerifiedActor actor,UUID tenant,CustomerSearch filter,
                                            int page,int size) {
        return search.customers(id(actor),tenant,filter,page,size);
    }
    /** Encrypted contact details may be decrypted only under CRM_PII_READ. */
    public Page<ContactSummary> contacts(VerifiedActor actor,UUID tenant,ContactSearch filter,
                                          int page,int size) {
        return search.contacts(id(actor),tenant,filter,page,size);
    }

    /** Archived metadata can be inspected without decrypting PERSON or contact PII. */
    public CrmCustomerEntity.Status customerStatus(VerifiedActor actor, UUID tenant, UUID customer) {
        return service.customerStatus(id(actor),tenant,customer);
    }
    public CrmContactEntity.Status contactStatus(VerifiedActor actor, UUID tenant, UUID contact) {
        return service.contactStatus(id(actor),tenant,contact);
    }

}
