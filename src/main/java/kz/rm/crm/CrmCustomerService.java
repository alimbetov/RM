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
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
    private final CrmHistoryService history;

    public CrmCustomerService(JdbcTemplate jdbc, TenantAuthorization authorization,
                              TenantCryptoPort crypto, TenantKeyService keys, CrmHistoryService history) {
        this.jdbc=jdbc; this.authorization=authorization;
        this.crypto=crypto; this.keys=keys; this.history=history;
    }

    public record Customer(UUID tenantId, UUID id, String type, String name, String regionZoneId) {}
    public record Contact(UUID tenantId, UUID id, UUID customerId, String name,
                          String email, String phone) {}

    @Transactional
    Customer createPerson(UUID actorId, UUID tenantId, String fullName, String katoCode) {
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
    Customer createCompany(UUID actorId, UUID tenantId, String name, String katoCode) {
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

    @Transactional
    Customer getCustomer(UUID actorId, UUID tenantId, UUID id) {
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
                    authorization.requireForCommand(actorId,tenantId,DomainPermission.CRM_PII_READ);
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
    Contact addContact(UUID actorId, UUID tenantId, UUID customerId,
                              String name, String email, String phone) {
        requireOrganizationMember(actorId,tenantId,true);
        if (name==null || name.isBlank()) throw new IllegalArgumentException("Contact name required");
        // Ensures same-tenant and existing active customer before inserting a contact.
        // Consistent lock order: customer before contact; archive takes the same customer lock.
        var statuses=jdbc.query("""
            SELECT status FROM crm.customer WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,n)->rs.getString(1),tenantId,customerId);
        if(statuses.isEmpty() || !"ACTIVE".equals(statuses.getFirst()))
            throw new IllegalArgumentException("Customer not found in tenant");
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
        history.contact(tenantId,id,customerId,actorId,"CREATED",null,0);
        return new Contact(tenantId,id,customerId,name,email,phone);
    }

    @Transactional
    Contact getContact(UUID actorId, UUID tenantId, UUID contactId) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.CRM_PII_READ);
        return jdbc.query("""
            SELECT c.customer_id,s.name_envelope,s.email_envelope,s.phone_envelope
              FROM crm.contact c JOIN crm_pii.contact_secret s
                ON c.tenant_id=s.tenant_id AND c.id=s.contact_id
                JOIN crm.customer u ON u.tenant_id=c.tenant_id AND u.id=c.customer_id
             WHERE c.tenant_id=? AND c.id=? AND c.status='ACTIVE' AND u.status='ACTIVE'
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
    UUID recordEmailConsent(UUID actorId, UUID tenantId, UUID contactId,
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

    /**
     * Keyed consent command. Authorization happens before replay evaluation;
     * the original immutable event ID is returned only for an identical request.
     * Stripe locks serialize the unique tenant/request key across transactions.
     */
    @Transactional
    UUID recordEmailConsentOnce(UUID actorId, UUID tenantId, UUID contactId,
                                boolean granted, String purpose, String policyVersion,
                                String source, String requestKey) {
        requireOrganizationMember(actorId,tenantId,true);
        if(tenantId==null || contactId==null || requestKey==null
                || !requestKey.matches("[A-Za-z0-9_-]{8,100}")
                || purpose==null || purpose.isBlank() || purpose.length()>80
                || policyVersion==null || policyVersion.isBlank() || policyVersion.length()>40
                || source==null || source.isBlank() || source.length()>100)
            throw new IllegalArgumentException("Consent request fields required");
        final int stripe;
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256")
                    .digest((tenantId+":"+requestKey).getBytes(StandardCharsets.UTF_8));
            stripe=Math.floorMod(Arrays.hashCode(hash),64);
        } catch(Exception ex) {
            throw new IllegalStateException("Cannot hash consent request key",ex);
        }
        jdbc.queryForObject("SELECT bucket FROM crm.consent_request_lock WHERE bucket=? FOR UPDATE",
                Integer.class,stripe);
        var existing=jdbc.query("""
                SELECT id,contact_id,granted,purpose,policy_version,source
                FROM crm.email_consent_event
                WHERE tenant_id=? AND request_key=?
                """,(rs,n)->new ConsentReplay(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getBoolean(3),rs.getString(4),
                    rs.getString(5),rs.getString(6)),tenantId,requestKey);
        if(!existing.isEmpty()) {
            var previous=existing.getFirst();
            if(!previous.contactId().equals(contactId) || previous.granted()!=granted
                    || !previous.purpose().equals(purpose)
                    || !previous.policyVersion().equals(policyVersion)
                    || !previous.source().equals(source))
                throw new IllegalArgumentException("Consent idempotency key reused with different payload");
            return previous.id();
        }
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM crm.contact
            WHERE tenant_id=? AND id=? AND status='ACTIVE'
            """,Integer.class,tenantId,contactId);
        if(count==null || count!=1) throw new IllegalArgumentException("Contact not found in tenant");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.email_consent_event
            (tenant_id,id,contact_id,granted,purpose,policy_version,source,occurred_at,request_key)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,tenantId,id,contactId,granted,purpose,policyVersion,source,
                Timestamp.from(Instant.now()),requestKey);
        return id;
    }

    private record ConsentReplay(UUID id,UUID contactId,boolean granted,String purpose,
                                 String policyVersion,String source) {}

    @Transactional
    boolean hasEmailConsent(UUID actorId, UUID tenantId, UUID contactId, String purpose) {
        requireOrganizationMember(actorId,tenantId,false);
        return Boolean.TRUE.equals(jdbc.query("""
            SELECT granted FROM crm.email_consent_event
             WHERE tenant_id=? AND contact_id=? AND purpose=?
             ORDER BY occurred_at DESC,id DESC
            """,rs->rs.next() && rs.getBoolean(1),tenantId,contactId,purpose));
    }


    /** Versioned company metadata change; validates the KATO dictionary and never edits PERSON secrets. */
    @Transactional
    Customer updateCompany(UUID actorId, UUID tenantId, UUID customerId, String newName,
                           String katoCode, long expectedVersion) {
        requireOrganizationMember(actorId,tenantId,true);
        if(newName==null || newName.isBlank() || newName.length()>200)
            throw new IllegalArgumentException("Invalid organization name");
        String zone=zone(katoCode);
        int n=jdbc.update("""
          UPDATE crm.customer SET organization_label=?, kato_code=?,region_zone_id=?,
              row_version=row_version+1
          WHERE tenant_id=? AND id=? AND customer_type='COMPANY' AND status='ACTIVE'
             AND row_version=?
          """,newName.strip(),katoCode,zone,tenantId,customerId,expectedVersion);
        if(n!=1) throw new IllegalStateException("Customer not found, archived or stale");
        history.customer(tenantId,customerId,actorId,"METADATA_UPDATED",expectedVersion,expectedVersion+1);
        return new Customer(tenantId,customerId,"COMPANY",newName.strip(),zone);
    }

    /** Re-encrypt same stable AAD identity and change metadata atomically. No plaintext audit payloads. */
    @Transactional
    Customer updatePerson(UUID actorId, UUID tenantId, UUID customerId, String fullName,
                          String katoCode, long expectedVersion) {
        requireOrganizationMember(actorId,tenantId,true);
        if(fullName==null || fullName.isBlank() || fullName.length()>200)
            throw new IllegalArgumentException("Invalid person name");
        String zone=zone(katoCode);
        keys.active(tenantId);
        byte[] envelope=EncryptedValueCodec.encode(crypto.encrypt(
            new CryptoContext(tenantId,"crm_customer",customerId,"name"),fullName.strip()));
        int n=jdbc.update("""
          UPDATE crm.customer SET kato_code=?,region_zone_id=?,row_version=row_version+1
          WHERE tenant_id=? AND id=? AND customer_type='PERSON' AND status='ACTIVE'
            AND row_version=?
          """,katoCode,zone,tenantId,customerId,expectedVersion);
        if(n!=1) throw new IllegalStateException("Customer not found, archived or stale");
        int changed=jdbc.update("""
          UPDATE crm_pii.customer_secret SET name_envelope=?,row_version=row_version+1
          WHERE tenant_id=? AND customer_id=?
          """,envelope,tenantId,customerId);
        if(changed!=1) throw new IllegalStateException("Customer secret missing");
        history.customer(tenantId,customerId,actorId,"NAME_UPDATED",expectedVersion,expectedVersion+1);
        return new Customer(tenantId,customerId,"PERSON",fullName.strip(),zone);
    }

    /** Soft archive retains contacts, consent, contracts and historic evidence. */
    @Transactional
    void archiveCustomer(UUID actorId, UUID tenantId, UUID customerId, long expectedVersion) {
        requireOrganizationMember(actorId,tenantId,true);
        // Serialize archiving with contract creation on the same customer row.
        Integer locked=jdbc.queryForObject("""
          SELECT 1 FROM crm.customer WHERE tenant_id=? AND id=? FOR UPDATE
          """,Integer.class,tenantId,customerId);
        if(locked==null) throw new IllegalStateException("Customer not found");
        Integer activeContracts=jdbc.queryForObject("""
          SELECT count(*) FROM crm.customer_contract
          WHERE tenant_id=? AND customer_id=? AND status='ACTIVE'
          """,Integer.class,tenantId,customerId);
        if(activeContracts!=null && activeContracts>0)
            throw new IllegalStateException("Customer has active contracts");
        int n=jdbc.update("""
          UPDATE crm.customer SET status='ARCHIVED',row_version=row_version+1
          WHERE tenant_id=? AND id=? AND status='ACTIVE' AND row_version=?
          """,tenantId,customerId,expectedVersion);
        if(n!=1) throw new IllegalStateException("Customer not found, archived or stale");
        history.customer(tenantId,customerId,actorId,"ARCHIVED",expectedVersion,expectedVersion+1);
    }


    /** Complete contact PII replacement, keeping original stable per-contact AAD. */
    @Transactional
    Contact updateContact(UUID actor, UUID tenant, UUID id, String name, String email,
                          String phone, long expectedVersion) {
        requireOrganizationMember(actor,tenant,true);
        if(name==null || name.isBlank() || name.length()>200
                || (email!=null && email.length()>320) || (phone!=null && phone.length()>50))
            throw new IllegalArgumentException("Invalid contact fields");
        keys.active(tenant);
        var trimmedName=name.strip();
        var nameCipher=seal(tenant,id,"name",trimmedName);
        var emailCipher=email==null?null:seal(tenant,id,"email",email);
        var phoneCipher=phone==null?null:seal(tenant,id,"phone",phone);
        UUID customer=lockActiveContact(tenant,id,"ACTIVE",expectedVersion);
        int changed=jdbc.update("""
            UPDATE crm.contact SET row_version=row_version+1
            WHERE tenant_id=? AND id=? AND row_version=? AND status='ACTIVE'
            """,tenant,id,expectedVersion);
        if(changed!=1) throw new IllegalStateException("Stale contact revision");
        int secrets=jdbc.update("""
            UPDATE crm_pii.contact_secret
            SET name_envelope=?,email_envelope=?,phone_envelope=?,row_version=row_version+1
            WHERE tenant_id=? AND contact_id=?
            """,nameCipher,emailCipher,phoneCipher,tenant,id);
        if(secrets!=1) throw new IllegalStateException("Contact secret missing");
        history.contact(tenant,id,customer,actor,"UPDATED",expectedVersion,expectedVersion+1);
        return new Contact(tenant,id,customer,trimmedName,email,phone);
    }

    @Transactional
    void archiveContact(UUID actor, UUID tenant, UUID id, long expectedVersion) {
        changeContactStatus(actor,tenant,id,expectedVersion,"ACTIVE","ARCHIVED","ARCHIVED");
    }

    @Transactional
    void restoreContact(UUID actor, UUID tenant, UUID id, long expectedVersion) {
        changeContactStatus(actor,tenant,id,expectedVersion,"ARCHIVED","ACTIVE","RESTORED");
    }

    private void changeContactStatus(UUID actor, UUID tenant, UUID id, long version,
                                     String expected, String target, String action) {
        requireOrganizationMember(actor,tenant,true);
        UUID customer=lockActiveContact(tenant,id,expected,version);
        int changed=jdbc.update("""
            UPDATE crm.contact SET status=?,row_version=row_version+1
            WHERE tenant_id=? AND id=? AND status=? AND row_version=?
            """,target,tenant,id,expected,version);
        if(changed!=1) throw new IllegalStateException("Stale contact revision");
        history.contact(tenant,id,customer,actor,action,version,version+1);
    }


    /** Restore customer metadata without resurrecting contacts, consents or contracts. */
    @Transactional
    void restoreCustomer(UUID actor, UUID tenant, UUID customer, long expectedVersion) {
        requireOrganizationMember(actor,tenant,true);
        int changed=jdbc.update("""
            UPDATE crm.customer SET status='ACTIVE', row_version=row_version+1
            WHERE tenant_id=? AND id=? AND status='ARCHIVED' AND row_version=?
            """,tenant,customer,expectedVersion);
        if(changed!=1) throw new IllegalStateException("Customer not found, active or stale");
        history.customer(tenant,customer,actor,"RESTORED",expectedVersion,expectedVersion+1);
    }

    /** Status-aware metadata only; no decryption or plaintext PERSON names. */
    @Transactional
    CrmCustomerEntity.Status customerStatus(UUID actor, UUID tenant, UUID customer) {
        requireOrganizationMember(actor,tenant,false);
        var matches=jdbc.query("""
            SELECT status FROM crm.customer WHERE tenant_id=? AND id=?
            """,(rs,n)->CrmCustomerEntity.Status.valueOf(rs.getString(1)),tenant,customer);
        if(matches.isEmpty()) throw new IllegalArgumentException("Customer not found in tenant");
        return matches.getFirst();
    }


    /**
     * Lock parent before contact to serialize archive/create/update/restore with customer lifecycle.
     * The first contact lookup is only a locator; identity/status/version are rechecked under lock.
     */
    private UUID lockActiveContact(UUID tenant, UUID id, String status, long version) {
        var parents=jdbc.query("""
            SELECT customer_id FROM crm.contact WHERE tenant_id=? AND id=?
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,id);
        if(parents.isEmpty()) throw new IllegalStateException("Contact not found in tenant");
        UUID customer=parents.getFirst();
        var state=jdbc.query("""
            SELECT status FROM crm.customer WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,n)->rs.getString(1),tenant,customer);
        if(state.isEmpty() || !"ACTIVE".equals(state.getFirst()))
            throw new IllegalStateException("Contact parent is archived or missing");
        var locked=jdbc.query("""
            SELECT customer_id FROM crm.contact
            WHERE tenant_id=? AND id=? AND customer_id=? AND status=? AND row_version=? FOR UPDATE
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,id,customer,status,version);
        if(locked.isEmpty()) throw new IllegalStateException("Contact archived or stale");
        return customer;
    }


    @Transactional
    CrmContactEntity.Status contactStatus(UUID actor, UUID tenant, UUID contact) {
        requireOrganizationMember(actor,tenant,false);
        var matches=jdbc.query("""
            SELECT status FROM crm.contact WHERE tenant_id=? AND id=?
            """,(rs,n)->CrmContactEntity.Status.valueOf(rs.getString(1)),tenant,contact);
        if(matches.isEmpty()) throw new IllegalArgumentException("Contact not found in tenant");
        return matches.getFirst();
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
        else authorization.requireForCommand(actor,tenant,DomainPermission.CRM_READ);
    }
}
