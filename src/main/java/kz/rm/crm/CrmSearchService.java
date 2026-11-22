package kz.rm.crm;

import kz.rm.crm.dto.*;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.security.kms.CryptoContext;
import kz.rm.security.kms.EncryptedValueCodec;
import kz.rm.security.kms.TenantCryptoPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Internal bounded and tenant-scoped CRM read model. No transport controllers. */
@Service
class CrmSearchService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final TenantCryptoPort crypto;

    CrmSearchService(JdbcTemplate jdbc,TenantAuthorization authorization,TenantCryptoPort crypto) {
        this.jdbc=jdbc;this.authorization=authorization;this.crypto=crypto;
    }

    @Transactional
    Page<CustomerSummary> customers(UUID actor,UUID tenant,CustomerSearch filter,int page,int size) {
        authorization.requireForCommand(actor,tenant,DomainPermission.CRM_READ);
        validatePage(page,size);
        CustomerSearch f=filter==null?new CustomerSearch(null,null,null,null):filter;
        var predicate=new StringBuilder(" WHERE c.tenant_id=?");
        List<Object> args=new ArrayList<>(List.of(tenant));
        if(f.type()!=null){predicate.append(" AND c.customer_type=?");args.add(f.type());}
        if(f.status()!=null){predicate.append(" AND c.status=?");args.add(f.status());}
        if(f.regionZoneId()!=null){predicate.append(" AND c.region_zone_id=?");args.add(f.regionZoneId());}
        if(f.companyNameContains()!=null){
            // Only public company names are searchable, never encrypted person names.
            predicate.append(" AND c.customer_type='COMPANY' AND LOWER(c.organization_label) LIKE ?");
            String escaped=f.companyNameContains().toLowerCase(java.util.Locale.ROOT)
                    .replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
            predicate.append(" ESCAPE '\\'");
            args.add("%"+escaped+"%");
        }
        if (f.categoryCode() != null) {
            // EXISTS avoids duplicating customers and keeps count/page scopes identical.
            predicate.append(" ").append("""
                    AND EXISTS (SELECT 1 FROM crm.customer_category cat
                                WHERE cat.tenant_id=c.tenant_id
                                  AND cat.id=c.primary_category_id
                                  AND cat.code=?)
                    """);
            args.add(f.categoryCode());
        }
        if (f.createdFrom() != null) {
            predicate.append(" AND c.created_at>=?");
            args.add(java.sql.Timestamp.from(f.createdFrom()));
        }
        if (f.createdTo() != null) {
            predicate.append(" AND c.created_at<?");
            args.add(java.sql.Timestamp.from(f.createdTo()));
        }
        if (f.expiresWithinDays() != null) {
            // The earliest valid ACTIVE fixed-term contract determines "expires within".
            // A future/expired, DRAFT, CLOSED or open-ended contract cannot satisfy this.
            // The correlated tenant FK blocks foreign contracts even with a known customer ID.
            predicate.append(" ").append("""
                    AND EXISTS (SELECT 1 FROM crm.customer_contract ct
                                WHERE ct.tenant_id=c.tenant_id
                                  AND ct.customer_id=c.id
                                  AND ct.status='ACTIVE'
                                  AND ct.starts_on<=?
                                  AND ct.ends_on>=?
                                  AND ct.ends_on<=?)
                    """);
            args.add(java.sql.Date.valueOf(f.asOfDate()));
            args.add(java.sql.Date.valueOf(f.asOfDate()));
            args.add(java.sql.Date.valueOf(f.asOfDate().plusDays(f.expiresWithinDays())));
        }
        Long count=jdbc.queryForObject("SELECT count(*) FROM crm.customer c"+predicate,
                Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);
        pageArgs.add(size);pageArgs.add((long)page*size);
        var rows=jdbc.query("""
                SELECT c.id,c.customer_type,c.organization_label,c.region_zone_id,
                       c.status,c.created_at,c.row_version
                FROM crm.customer c
                """+predicate+"""
                 ORDER BY c.created_at DESC,c.id DESC LIMIT ? OFFSET ?
                """,(rs,n)->new CustomerSummary(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                    rs.getTimestamp(6).toInstant(),rs.getLong(7)),pageArgs.toArray());
        return new PageImpl<>(rows,PageRequest.of(page,size),count==null?0:count);
    }

    @Transactional
    Page<ContactSummary> contacts(UUID actor,UUID tenant,ContactSearch filter,int page,int size) {
        authorization.requireForCommand(actor,tenant,DomainPermission.CRM_PII_READ);
        validatePage(page,size);
        ContactSearch f=filter==null?new ContactSearch(null,null,null):filter;
        var predicate=new StringBuilder(" WHERE c.tenant_id=?");
        List<Object> args=new ArrayList<>(List.of(tenant));
        if(f.customerId()!=null){predicate.append(" AND c.customer_id=?");args.add(f.customerId());}
        if(f.status()!=null){predicate.append(" AND c.status=?");args.add(f.status());}
        if(Boolean.TRUE.equals(f.primaryOnly())) predicate.append(" AND c.is_primary=TRUE");
        Long count=jdbc.queryForObject("SELECT count(*) FROM crm.contact c"+predicate,
                Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);
        pageArgs.add(size);pageArgs.add((long)page*size);
        var rows=jdbc.query("""
                SELECT c.id,c.customer_id,c.is_primary,c.status,c.created_at,c.row_version,
                       s.name_envelope,s.email_envelope,s.phone_envelope
                FROM crm.contact c
                JOIN crm_pii.contact_secret s ON s.tenant_id=c.tenant_id AND s.contact_id=c.id
                """+predicate+"""
                 ORDER BY c.created_at DESC,c.id DESC LIMIT ? OFFSET ?
                """,(rs,n)->{
                    UUID contact=rs.getObject(1,UUID.class);
                    return new ContactSummary(contact,rs.getObject(2,UUID.class),
                            rs.getBoolean(3),rs.getString(4),
                            open(tenant,contact,"name",rs.getBytes(7)),
                            open(tenant,contact,"email",rs.getBytes(8)),
                            open(tenant,contact,"phone",rs.getBytes(9)),
                            rs.getTimestamp(5).toInstant(),rs.getLong(6));
                },pageArgs.toArray());
        return new PageImpl<>(rows,PageRequest.of(page,size),count==null?0:count);
    }

    private String open(UUID tenant,UUID contact,String field,byte[] bytes) {
        return bytes==null?null:crypto.decrypt(new CryptoContext(tenant,"crm_contact",contact,field),
                EncryptedValueCodec.decode(bytes));
    }

    private static void validatePage(int page,int size) {
        if(page<0 || size<1 || size>100) throw new IllegalArgumentException("Page must be nonnegative, size 1..100");
    }
}
