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
        return safePerson(service.createPerson(id(actor),tenant,name,kato));
    }
    public CrmCustomerService.Customer createCompany(VerifiedActor actor,UUID tenant,String name,String kato) {
        return service.createCompany(id(actor),tenant,name,kato);
    }
    public CrmCustomerService.Contact addContact(VerifiedActor actor,UUID tenant,UUID customer,
                                                String name,String email,String phone) {
        return safeContact(service.addContact(id(actor),tenant,customer,name,email,phone));
    }
    UUID recordEmailConsent(VerifiedActor actor,UUID tenant,UUID contact,boolean granted,
                                   String purpose,String version,String source) {
        return service.recordEmailConsent(id(actor),tenant,contact,granted,purpose,version,source);
    }
    /** Public idempotent consent entrypoint for future authenticated adapters. */
    public UUID recordEmailConsentOnce(VerifiedActor actor,UUID tenant,UUID contact,
                                       boolean granted,String purpose,String version,
                                       String source,String requestKey) {
        return service.recordEmailConsentOnce(id(actor),tenant,contact,
                granted,purpose,version,source,requestKey);
    }

    public CrmCustomerService.Customer updateCompany(VerifiedActor actor, UUID tenant, UUID customer,
                                                      String name, String kato, long expectedVersion) {
        return service.updateCompany(id(actor),tenant,customer,name,kato,expectedVersion);
    }
    public CrmCustomerService.Customer updatePerson(VerifiedActor actor, UUID tenant, UUID customer,
                                                     String name, String kato, long expectedVersion) {
        return safePerson(service.updatePerson(id(actor),tenant,customer,name,kato,expectedVersion));
    }
    public void archiveCustomer(VerifiedActor actor, UUID tenant, UUID customer, long expectedVersion) {
        service.archiveCustomer(id(actor),tenant,customer,expectedVersion);
    }


    public CrmCustomerService.Contact updateContact(VerifiedActor actor, UUID tenant, UUID contact,
            String name, String email, String phone, long expectedVersion) {
        return safeContact(service.updateContact(id(actor),tenant,contact,name,email,phone,expectedVersion));
    }
    public void archiveContact(VerifiedActor actor, UUID tenant, UUID contact, long expectedVersion) {
        service.archiveContact(id(actor),tenant,contact,expectedVersion);
    }
    public void restoreContact(VerifiedActor actor, UUID tenant, UUID contact, long expectedVersion) {
        service.restoreContact(id(actor),tenant,contact,expectedVersion);
    }

    public void restoreCustomer(VerifiedActor actor, UUID tenant, UUID customer, long expectedVersion) {
        service.restoreCustomer(id(actor),tenant,customer,expectedVersion);
    }

    /**
     * TCRUD-02: mutation responses never return personal data, even if the
     * caller also happens to have CRM_PII_READ. Read-side access requires
     * a separate permission-checked query.
     *
     * Preserve existing return types for internal callers; null fields mean
     * "not returned by this command", NOT an instruction to clear PII.
     */
    private static CrmCustomerService.Customer safePerson(CrmCustomerService.Customer c) {
        return new CrmCustomerService.Customer(c.tenantId(),c.id(),c.type(),null,c.regionZoneId());
    }
    private static CrmCustomerService.Contact safeContact(CrmCustomerService.Contact c) {
        return new CrmCustomerService.Contact(c.tenantId(),c.id(),c.customerId(),null,null,null);
    }
}
