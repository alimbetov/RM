package kz.rm.intelligence;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema="crm",name="int_calendar_policy")
@IdClass(CalendarPolicyKey.class)
@org.hibernate.annotations.Immutable
public class CalendarPolicyEntity {
    @Id @Column(name="tenant_id",nullable=false) private UUID tenantId;
    @Id @Column(name="version",nullable=false) private long version;
    @Column(name="zone_id",nullable=false,length=64) private String zoneId;
    @Column(name="effective_from",nullable=false) private Instant effectiveFrom;
    @Column(name="created_by",nullable=false) private UUID createdBy;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected CalendarPolicyEntity(){}
    CalendarPolicyEntity(UUID tenantId,long version,String zoneId,Instant now,UUID actor){
        this.tenantId=tenantId;this.version=version;this.zoneId=zoneId;
        this.effectiveFrom=now;this.createdAt=now;this.createdBy=actor;
    }
    public UUID getTenantId(){return tenantId;}
    public long getVersion(){return version;}
    public String getZoneId(){return zoneId;}
    public Instant getEffectiveFrom(){return effectiveFrom;}
    public UUID getCreatedBy(){return createdBy;}
    public Instant getCreatedAt(){return createdAt;}
}
