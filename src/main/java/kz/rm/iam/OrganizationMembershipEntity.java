package kz.rm.iam;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="iam_org_membership",uniqueConstraints=@UniqueConstraint(name="uk_iam_org_member",columnNames={"organization_id","user_id"}))
public class OrganizationMembershipEntity {
    @Id private UUID id;
    @Column(name="organization_id",nullable=false) private UUID organizationId;
    @Column(name="user_id",nullable=false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Role role;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(name="joined_at",nullable=false) private Instant joinedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    protected OrganizationMembershipEntity(){}
    public OrganizationMembershipEntity(UUID id,UUID organizationId,UUID userId,Role role){
        if(id==null || organizationId==null || userId==null || role==null) throw new IllegalArgumentException("Valid membership required");
        this.id=id;this.organizationId=organizationId;this.userId=userId;this.role=role;
        this.status=Status.ACTIVE;this.joinedAt=Instant.now();
    }
    public UUID getId(){return id;}
    public UUID getOrganizationId(){return organizationId;}
    public UUID getUserId(){return userId;}
    public Role getRole(){return role;}
    public Status getStatus(){return status;}
    public enum Role { OWNER, ADMIN, MANAGER, MEMBER, VIEWER }
    public enum Status { INVITED, ACTIVE, SUSPENDED, LEFT }
}
