package kz.rm.crm;

import kz.rm.files.FileOwnerAccessPort;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * CRM owns the customer access decision, not FILES. Conservative R1.1 admission:
 * only active COMPANY customers, CRM_PII_READ always required for document links.
 * PERSON and assignment-specific portfolios remain closed until explicit policy.
 */
@Service
class CrmFileOwnerAccess implements FileOwnerAccessPort {
    private final TenantAuthorization authorization;
    private final JdbcTemplate jdbc;
    CrmFileOwnerAccess(TenantAuthorization authorization,JdbcTemplate jdbc) {
        this.authorization=authorization;this.jdbc=jdbc;
    }
    @Override @Transactional
    public void requireCustomerRead(UUID actor,UUID tenant,UUID customerId) {
        requireCustomer(actor,tenant,customerId,false);
    }
    @Override @Transactional
    public void requireCustomerModify(UUID actor,UUID tenant,UUID customerId) {
        requireCustomer(actor,tenant,customerId,true);
    }
    private void requireCustomer(UUID actor,UUID tenant,UUID customerId,boolean modify) {
        if(customerId==null)throw new SecurityException("CRM customer required");
        authorization.requireForCommand(actor,tenant,
                modify?DomainPermission.CRM_WRITE:DomainPermission.CRM_READ);
        authorization.requireForCommand(actor,tenant,DomainPermission.CRM_PII_READ);
        // Read the actual row after the IAM/tenant locks, with tenant in predicate.
        // Lock prevents archive/tenant move during the attachment mutation.
        String type=jdbc.query("""
                SELECT customer_type FROM crm.customer
                 WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant,customerId);
        if(!"COMPANY".equals(type))
            throw new SecurityException("CRM document owner is not admitted");
    }
}
