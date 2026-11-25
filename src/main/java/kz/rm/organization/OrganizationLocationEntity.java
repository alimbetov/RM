package kz.rm.organization;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Organization-owned physical location. Tenant, identity and type never change. */
@Entity
@Table(name="org_location")
public class OrganizationLocationEntity {
    @Id private UUID id;
    @Column(name="workspace_id",nullable=false,updatable=false) private UUID workspaceId;
    @Enumerated(EnumType.STRING) @Column(name="location_type",nullable=false,length=20,updatable=false) private Type type;
    @Column(nullable=false,length=200) private String name;
    @Column(name="kato_code",nullable=false,length=16) private String katoCode;
    @Column(name="address_line",length=500) private String addressLine;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;

    protected OrganizationLocationEntity() {}

    public OrganizationLocationEntity(UUID id, UUID workspaceId, Type type,
                                      String name, String katoCode, String addressLine) {
        if (id==null || workspaceId==null || type==null || name==null || name.isBlank()
                || katoCode==null || katoCode.isBlank())
            throw new IllegalArgumentException("Valid organization location required");
        this.id=id;
        this.workspaceId=workspaceId;
        this.type=type;
        this.name=name;
        this.katoCode=katoCode;
        this.addressLine=addressLine;
        this.status=Status.ACTIVE;
        this.createdAt=Instant.now();
        this.updatedAt=this.createdAt;
    }

    boolean replaceProfile(String name, String katoCode, String addressLine) {
        if (Objects.equals(this.name,name) && Objects.equals(this.katoCode,katoCode)
                && Objects.equals(this.addressLine,addressLine)) return false;
        this.name=name;
        this.katoCode=katoCode;
        this.addressLine=addressLine;
        this.updatedAt=Instant.now();
        return true;
    }

    boolean moveTo(Status next) {
        if (this.status==next) return false;
        this.status=Objects.requireNonNull(next,"Next status required");
        this.updatedAt=Instant.now();
        return true;
    }

    public UUID getId(){return id;}
    public String getName(){return name;}
    public String getAddressLine(){return addressLine;}
    public Status getStatus(){return status;}
    public Instant getCreatedAt(){return createdAt;}
    public Instant getUpdatedAt(){return updatedAt;}
    public long getRowVersion(){return rowVersion;}
    public UUID getWorkspaceId(){return workspaceId;}
    public String getKatoCode(){return katoCode;}
    public Type getType(){return type;}

    public enum Type { BRANCH, WAREHOUSE, POINT_OF_SALE }
    public enum Status { ACTIVE, INACTIVE, CLOSED }
}
