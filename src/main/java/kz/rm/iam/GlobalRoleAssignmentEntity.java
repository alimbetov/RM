package kz.rm.iam;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="iam_global_role_assignment",uniqueConstraints=@UniqueConstraint(name="uk_global_role",columnNames={"user_id","role"}))
public class GlobalRoleAssignmentEntity {
    @Id private UUID id;
    @Column(name="user_id",nullable=false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private Role role;
    @Column(name="granted_at",nullable=false) private Instant grantedAt;
    @Column(name="revoked_at") private Instant revokedAt;
    protected GlobalRoleAssignmentEntity(){}
    public GlobalRoleAssignmentEntity(UUID id,UUID userId,Role role){
        if(id==null || userId==null || role==null) throw new IllegalArgumentException("Valid global grant required");
        this.id=id;this.userId=userId;this.role=role;this.grantedAt=Instant.now();
    }
    public UUID getId(){return id;}
    public Role getRole(){return role;}
    public enum Role { PLATFORM_ADMIN, PLATFORM_AUDITOR }
}
