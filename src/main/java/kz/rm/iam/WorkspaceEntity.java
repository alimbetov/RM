package kz.rm.iam;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="iam_workspace")
public class WorkspaceEntity {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Kind kind;
    @Column(name="organization_id",unique=true) private UUID organizationId;
    @Column(name="owner_user_id",unique=true) private UUID ownerUserId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    protected WorkspaceEntity(){}
    public static WorkspaceEntity personal(UUID id, UUID userId) {
        return new WorkspaceEntity(id,Kind.PERSONAL,null,userId);
    }
    public static WorkspaceEntity organization(UUID id, UUID organizationId) {
        return new WorkspaceEntity(id,Kind.ORGANIZATION,organizationId,null);
    }
    private WorkspaceEntity(UUID id,Kind kind,UUID org,UUID user){
        if(id==null || (org==null)==(user==null)) throw new IllegalArgumentException("Exactly one workspace owner required");
        this.id=id; this.kind=kind; this.organizationId=org; this.ownerUserId=user;
        this.status=Status.PROVISIONING; this.createdAt=Instant.now();
    }
    public UUID getId(){return id;}
    public Kind getKind(){return kind;}
    public UUID getOrganizationId(){return organizationId;}
    public UUID getOwnerUserId(){return ownerUserId;}
    public Status getStatus(){return status;}
    public enum Kind { PERSONAL, ORGANIZATION }
    public enum Status { PROVISIONING, ACTIVE, SUSPENDED, CLOSED }
}
