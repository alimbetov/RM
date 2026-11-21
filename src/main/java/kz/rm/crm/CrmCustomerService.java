package kz.rm.crm;

import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.DomainPermission;
import kz.rm.security.kms.CryptoContext;
import kz.rm.security.kms.EncryptedValueCodec;
import kz.rm.security.kms.TenantCryptoPort;
import kz.rm.security.kms.TenantKeyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * CRM-R1A application boundary. Do not expose a controller accepting arbitrary actor IDs:
 * a future REST adapter MUST derive userId from an authenticated and verified principal.
 */
@Service
public class CrmCustomerService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final TenantCryptoPort crypto;
    private final TenantKeyService keys;

    public CrmCustomerService(JdbcTemplate jdbc, TenantAuthorization authorization,
                              TenantCryptoPort crypto, TenantKeyService keys) {
        this.jdbc=jdbc; this.authorization=authorization;
        this.crypto=crypto; this.keys=keys;
    }

    public record Customer(UUID tenantId, UUID id, String type, String name, String regionZoneId) {}
    public record Contact(UUID tenantId, UUID id, UUID customerId, String name,
                          String email, String phone) {}

    @Transactional
    public Customer createPerson(UUID actorId, UUID tenantId, String fullName, String katoCode) {
        requireOrganizationMember(actorId, tenantId, true);
        if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("Name required");
        String zone = zone(katoCode);
        UUID id=UUID.randomUUID();
        // Keys are provisioned separately; never auto-generate a new unrelated DEK here.
        keys.active(tenantId);
        byte[] secret=EncryptedValueCodec.encode(crypto.encrypt(
                new CryptoContext(tenantId,"crm_customer",id,"name"),fullName));
        jdbc.update("""
            INSERT INTO crm.customer(tenant_id,id,customer_type,organization_label,region_zone_id,kato_code,status,created_at)
            VALUES (?,?,'PERSON',NULL,?,?,'ACTIVE',?)
            """,tenantId,id,zone,katoCode,Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO crm_pii.customer_secret(tenant_id,customer_id,name_envelope) VALUES (?,?,?)",
                tenantId,id,secret);
        return new Customer(tenantId,id,"PERSON",fullName,zone);
    }

    @Transactional
    public Customer createCompany(UUID actorId, UUID tenantId, String name, String katoCode) {
        requireOrganizationMember(actorId, tenantId, true);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Company name required");
        String zone=zone(katoCode);
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.customer(tenant_id,id,customer_type,organization_label,region_zone_id,kato_code,status,created_at)
            VALUES (?,?,'COMPANY',?, ?,?,'ACTIVE',?)
            """,tenantId,id,name,zone,katoCode,Timestamp.from(Instant.now()));
        return new Customer(tenantId,id,"COMPANY",name,zone);
    }

    @Transactional(readOnly=true)
    public Customer getCustomer(UUID actorId, UUID tenantId, UUID id) {
        requireOrganizationMember(actorId,tenantId,false);
        return jdbc.query("""
            SELECT customer_type,organization_label,region_zone_id FROM crm.customer
            WHERE tenant_id=? AND id=? AND status='ACTIVE'
            """,rs->{
                if (!rs.next()) throw new IllegalArgumentException("Customer not found in tenant");
                String type=rs.getString(1);
                String name=rs.getString(2);
                String zone=rs.getString(3);
                if ("PERSON".equals(type)) {
                    authorization.require(actorId,tenantId,DomainPermission.CRM_PII_READ);
                    byte[] envelope=jdbc.queryForObject("""
                        SELECT name_envelope FROM crm_pii.customer_secret
                        WHERE tenant_id=? AND customer_id=?
                        """,byte[].class,tenantId,id);
                    name=crypto.decrypt(new CryptoContext(tenantId,"crm_customer",id,"name"),
                            EncryptedValueCodec.decode(envelope));
                }
                return new Customer(tenantId,id,type,name,zone);
            },tenantId,id);
    }

    @Transactional
    public Contact addContact(UUID actorId, UUID tenantId, UUID customerId,
                              String name, String email, String phone) {
        requireOrganizationMember(actorId,tenantId,true);
        if (name==null || name.isBlank()) throw new IllegalArgumentException("Contact name required");
        // Ensures same-tenant and existing active customer before inserting a contact.
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM crm.customer WHERE tenant_id=? AND id=? AND status='ACTIVE'
            """,Integer.class,tenantId,customerId);
        if(count==null || count!=1) throw new IllegalArgumentException("Customer not found in tenant");
        keys.active(tenantId);
        UUID id=UUID.randomUUID();
        byte[] nameCipher=seal(tenantId,id,"name",name);
        byte[] emailCipher=email==null?null:seal(tenantId,id,"email",email);
        byte[] phoneCipher=phone==null?null:seal(tenantId,id,"phone",phone);
        jdbc.update("""
            INSERT INTO crm.contact(tenant_id,id,customer_id,is_primary,status,created_at)
            VALUES (?,?,?,FALSE,'ACTIVE',?)
            """,tenantId,id,customerId,Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm_pii.contact_secret(tenant_id,contact_id,name_envelope,email_envelope,phone_envelope)
            VALUES (?,?,?,?,?)
            """,tenantId,id,nameCipher,emailCipher,phoneCipher);
        return new Contact(tenantId,id,customerId,name,email,phone);
    }

    @Transactional(readOnly=true)
    public Contact getContact(UUID actorId, UUID tenantId, UUID contactId) {
        authorization.require(actorId,tenantId,DomainPermission.CRM_PII_READ);
        return jdbc.query("""
            SELECT c.customer_id,s.name_envelope,s.email_envelope,s.phone_envelope
              FROM crm.contact c JOIN crm_pii.contact_secret s
                ON c.tenant_id=s.tenant_id AND c.id=s.contact_id
             WHERE c.tenant_id=? AND c.id=? AND c.status='ACTIVE'
            """,rs->{
                if(!rs.next()) throw new IllegalArgumentException("Contact not found in tenant");
                UUID customerId=rs.getObject(1,UUID.class);
                return new Contact(tenantId,contactId,customerId,
                        open(tenantId,contactId,"name",rs.getBytes(2)),
                        open(tenantId,contactId,"email",rs.getBytes(3)),
                        open(tenantId,contactId,"phone",rs.getBytes(4)));
            },tenantId,contactId);
    }

    /**
     * Append-only consent evidence. Never assume creating a contact implies marketing consent.
     * Campaign dispatch still needs a fresh, locked eligibility check and an outbox.
     */
    @Transactional
    public UUID recordEmailConsent(UUID actorId, UUID tenantId, UUID contactId,
                                   boolean granted, String purpose,String policyVersion,String source) {
        requireOrganizationMember(actorId,tenantId,true);
        if(purpose==null||purpose.isBlank()||policyVersion==null||policyVersion.isBlank()
                ||source==null||source.isBlank()) throw new IllegalArgumentException("Consent evidence required");
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM crm.contact WHERE tenant_id=? AND id=? AND status='ACTIVE'
            """,Integer.class,tenantId,contactId);
        if(count==null||count!=1) throw new IllegalArgumentException("Contact not found in tenant");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.email_consent_event
            (tenant_id,id,contact_id,granted,purpose,policy_version,source,occurred_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,tenantId,id,contactId,granted,purpose,policyVersion,source,Timestamp.from(Instant.now()));
        return id;
    }

    @Transactional(readOnly=true)
    public boolean hasEmailConsent(UUID actorId, UUID tenantId, UUID contactId, String purpose) {
        requireOrganizationMember(actorId,tenantId,false);
        return Boolean.TRUE.equals(jdbc.query("""
            SELECT granted FROM crm.email_consent_event
             WHERE tenant_id=? AND contact_id=? AND purpose=?
             ORDER BY occurred_at DESC,id DESC
            """,rs->rs.next() && rs.getBoolean(1),tenantId,contactId,purpose));
    }

    private String zone(String katoCode) {
        if(katoCode==null) return null;
        if(katoCode.length()<2) throw new IllegalArgumentException("Invalid KATO code");
        Integer valid=jdbc.queryForObject("""
            SELECT count(*) FROM ref_kato_territory
            WHERE code=? AND is_deleted=FALSE AND level_no<=3
            """,Integer.class,katoCode);
        if(valid==null||valid!=1) throw new IllegalArgumentException("Unknown, deleted or too detailed KATO");
        return katoCode.substring(0,2);
    }

    private byte[] seal(UUID tenant,UUID id,String field,String value) {
        return EncryptedValueCodec.encode(crypto.encrypt(
                new CryptoContext(tenant,"crm_contact",id,field),value));
    }
    private String open(UUID tenant,UUID id,String field,byte[] bytes) {
        return bytes==null?null:crypto.decrypt(new CryptoContext(tenant,"crm_contact",id,field),
                EncryptedValueCodec.decode(bytes));
    }

    private void requireOrganizationMember(UUID actor, UUID tenant, boolean write) {
        if(write) authorization.requireForCommand(actor,tenant,DomainPermission.CRM_WRITE);
        else authorization.require(actor,tenant,DomainPermission.CRM_READ);
    }
}
