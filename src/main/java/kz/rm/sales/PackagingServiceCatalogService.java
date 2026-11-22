package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** QR-08/R0A. SALES-owned service versions, never physical stock. */
@Service
public class PackagingServiceCatalogService {
    public record Version(UUID tenantId, String serviceCode, int number,
                          String name, BigDecimal price, String currency,
                          BigDecimal taxRate, String policyVersion, Instant publishedAt) {}
    public record Head(Version version, boolean active, long rowVersion) {}
    private record Locked(int number,boolean active,long revision) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    public PackagingServiceCatalogService(JdbcTemplate jdbc,TenantAuthorization auth) {
        this.jdbc=jdbc;this.auth=auth;
    }

    @Transactional
    public Head publish(VerifiedActor actor,UUID tenant,String code,String name,
                        BigDecimal price,String currency,BigDecimal tax,
                        String policy,long expectedVersion) {
        validate(tenant,code,name,price,currency,tax,policy,expectedVersion);
        if(actor==null)throw new SecurityException("PACKAGING_ACTOR_REQUIRED");
        auth.requireForCommand(actor.userId(),tenant,DomainPermission.SALES_PACKAGING_MANAGE);
        Locked previous=lock(tenant,code);
        if(previous==null) {
            if(expectedVersion!=0)throw new IllegalStateException("PACKAGING_VERSION_CONFLICT");
            jdbc.update("""
                INSERT INTO crm.sales_packaging_catalog_head
                 (tenant_id,service_code,current_version,active,row_version)
                VALUES (?,?,1,TRUE,0)
                """,tenant,code);
        } else {
            if(previous.number()!=expectedVersion)
                throw new IllegalStateException("PACKAGING_VERSION_CONFLICT");
            int changed=jdbc.update("""
                UPDATE crm.sales_packaging_catalog_head
                SET current_version=?,active=TRUE,row_version=row_version+1
                WHERE tenant_id=? AND service_code=? AND current_version=? AND row_version=?
                """,expectedVersion+1,tenant,code,expectedVersion,previous.revision());
            if(changed!=1)throw new IllegalStateException("PACKAGING_VERSION_CONFLICT");
        }
        int number=Math.toIntExact(expectedVersion+1);
        Instant now=Instant.now();
        jdbc.update("""
            INSERT INTO crm.sales_packaging_catalog_version
             (tenant_id,service_code,version,display_name,unit_price,currency,
              tax_rate,policy_version,published_by,published_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,tenant,code,number,name,price,currency,tax,policy,
             actor.userId(),Timestamp.from(now));
        return new Head(new Version(tenant,code,number,name,price,currency,tax,policy,now),
                        true,previous==null?0:previous.revision()+1);
    }

    @Transactional
    public Head deactivate(VerifiedActor actor,UUID tenant,String code,
                           long expectedVersion,long expectedRevision) {
        if(actor==null||tenant==null||!codeValid(code)
           ||expectedVersion<1||expectedRevision<0)
            throw new IllegalArgumentException("PACKAGING_INPUT_INVALID");
        auth.requireForCommand(actor.userId(),tenant,DomainPermission.SALES_PACKAGING_MANAGE);
        Locked old=lock(tenant,code);
        if(old==null||old.number()!=expectedVersion||old.revision()!=expectedRevision)
            throw new IllegalStateException("PACKAGING_VERSION_CONFLICT");
        if(!old.active())throw new IllegalStateException("PACKAGING_ALREADY_INACTIVE");
        int updated=jdbc.update("""
            UPDATE crm.sales_packaging_catalog_head
            SET active=FALSE,row_version=row_version+1
            WHERE tenant_id=? AND service_code=? AND current_version=?
              AND row_version=? AND active=TRUE
            """,tenant,code,expectedVersion,expectedRevision);
        if(updated!=1)throw new IllegalStateException("PACKAGING_VERSION_CONFLICT");
        return new Head(version(tenant,code,Math.toIntExact(expectedVersion)),
                        false,expectedRevision+1);
    }

    @Transactional(readOnly=true)
    public Head current(VerifiedActor actor,UUID tenant,String code) {
        if(actor==null||tenant==null||!codeValid(code))
            throw new SecurityException("PACKAGING_NOT_ACCESSIBLE");
        auth.require(actor.userId(),tenant,DomainPermission.SALES_PACKAGING_MANAGE);
        Locked head=jdbc.query("""
            SELECT current_version,active,row_version
            FROM crm.sales_packaging_catalog_head WHERE tenant_id=? AND service_code=?
            """,rs->rs.next()?new Locked(rs.getInt(1),rs.getBoolean(2),rs.getLong(3)):null,
            tenant,code);
        if(head==null)throw new SecurityException("PACKAGING_NOT_ACCESSIBLE");
        return new Head(version(tenant,code,head.number()),head.active(),head.revision());
    }

    /** Internal SALES use only after caller-owned IAM and order admission. */
    @Transactional(readOnly=true)
    Version version(UUID tenant,String code,int number) {
        return jdbc.query("""
            SELECT display_name,unit_price,currency,tax_rate,policy_version,published_at
            FROM crm.sales_packaging_catalog_version
            WHERE tenant_id=? AND service_code=? AND version=?
            """,rs->{
                if(!rs.next())throw new SecurityException("PACKAGING_NOT_ACCESSIBLE");
                return new Version(tenant,code,number,rs.getString(1),rs.getBigDecimal(2),
                    rs.getString(3),rs.getBigDecimal(4),rs.getString(5),
                    rs.getTimestamp(6).toInstant());
            },tenant,code,number);
    }

    private Locked lock(UUID tenant,String code) {
        return jdbc.query("""
            SELECT current_version,active,row_version
            FROM crm.sales_packaging_catalog_head
            WHERE tenant_id=? AND service_code=? FOR UPDATE
            """,rs->rs.next()?new Locked(rs.getInt(1),rs.getBoolean(2),rs.getLong(3)):null,
            tenant,code);
    }
    private static boolean codeValid(String v) {
        return v!=null&&v.matches("[A-Z][A-Z0-9_]{1,39}");
    }
    private static void validate(UUID tenant,String code,String name,BigDecimal price,
                                 String currency,BigDecimal tax,String policy,long rev) {
        if(tenant==null||!codeValid(code)||name==null||name.isBlank()||name.length()>160
           ||price==null||price.signum()<0||price.scale()>4||price.precision()>19
           ||currency==null||!currency.matches("[A-Z]{3}")
           ||tax==null||tax.signum()<0||tax.compareTo(BigDecimal.ONE)>0
           ||tax.scale()>4||policy==null||!policy.matches("[A-Za-z0-9_.:-]{1,40}")
           ||rev<0||rev>=Integer.MAX_VALUE)
            throw new IllegalArgumentException("PACKAGING_INPUT_INVALID");
    }
}
