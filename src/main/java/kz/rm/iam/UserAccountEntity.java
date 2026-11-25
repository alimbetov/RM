package kz.rm.iam;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "iam_user_account")
public class UserAccountEntity {
    @Id private UUID id;
    @Column(name="external_subject", length=255, unique=true) private String externalSubject;
    // Legacy plaintext columns are excluded from JPA read/write. Encrypted
    // global IAM identity/profile services are the only supported owners.
    @Transient private String emailNormalized;
    @Transient private String phoneE164;
    @Transient private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private Status status;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;

    protected UserAccountEntity() {}
    public UserAccountEntity(UUID id, String externalSubject, String displayName) {
        if(id == null || externalSubject == null || externalSubject.isBlank()
                || displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Valid account required");
        this.id=id; this.externalSubject=externalSubject; this.displayName=displayName;
        this.status=Status.ACTIVE; this.createdAt=Instant.now(); this.updatedAt=this.createdAt;
    }
    public UUID getId(){return id;}
    public Status getStatus(){return status;}
    public enum Status { PENDING, ACTIVE, SUSPENDED, DELETED }
}
