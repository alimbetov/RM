package kz.rm.organization;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Organization-owned location: a branch, warehouse, or point of sale. */
@Entity
@Table(name="org_location")
public class OrganizationLocationEntity {
    @Id private UUID id;
    @Column(name="workspace_id",nullable=false) private UUID workspaceId;
    @Enumerated(EnumType.STRING) @Column(name="location_type",nullable=false,length=20) private Type type;
    @Column(nullable=false,length=200) private String name;
    @Column(name="kato_code",nullable=false,length=16) private String katoCode;
    @Column(name="address_line",length=500) private String addressLine;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    protected OrganizationLocationEntity() {}
    public OrganizationLocationEntity(UUID id, UUID workspaceId, Type type,String name,String katoCode,String addressLine) {
        if(id==null||workspaceId==null||type==null||name==null||name.isBlank()
                ||katoCode==null||katoCode.isBlank()) throw new IllegalArgumentException("Valid organization location required");
        this.id=id;this.workspaceId=workspaceId;this.type=type;this.name=name;this.katoCode=katoCode;this.addressLine=addressLine;
        this.status=Status.ACTIVE;this.createdAt=Instant.now();this.updatedAt=this.createdAt;
    }
    public UUID getId(){return id;}
    public UUID getWorkspaceId(){return workspaceId;}
    public String getKatoCode(){return katoCode;}
    public Type getType(){return type;}
    public enum Type { BRANCH, WAREHOUSE, POINT_OF_SALE }
    public enum Status { ACTIVE, INACTIVE, CLOSED }
}
