package kz.rm.intelligence;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema="crm",name="int_canonical_fact")
@org.hibernate.annotations.Immutable
@IdClass(CanonicalFactKey.class)
public class CanonicalFactEntity {
    @Id @Column(name="tenant_id") private UUID tenantId;
    @Id @Enumerated(EnumType.STRING) @Column(name="owner_module",length=16) private CanonicalFactKey.Owner owner;
    @Id @Enumerated(EnumType.STRING) @Column(name="source_kind",length=32) private CanonicalFactKey.Kind kind;
    @Id @Column(name="source_id") private UUID sourceId;
    @Column(name="subject_id",nullable=false) private UUID subjectId;
    @Column(name="source_version") private Long sourceVersion;
    @Column(name="occurred_at",nullable=false) private Instant occurredAt;
    @Column(name="recorded_at") private Instant recordedAt;
    @Column(name="schema_version",nullable=false) private int schemaVersion;
    @Column(name="digest",nullable=false,length=64) private String digest;
    protected CanonicalFactEntity(){}
}
