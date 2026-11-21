package kz.rm.iam;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="org_organization")
public class OrganizationEntity {
    @Id private UUID id;
    @Column(name="legal_name",nullable=false,length=300) private String legalName;
    @Column(name="display_name",nullable=false,length=200) private String displayName;
    @Enumerated(EnumType.STRING) @Column(name="organization_type",nullable=false,length=30) private Type organizationType;
    @Column(name="country_code",nullable=false,length=2) private String countryCode;
    @Column(name="registration_number",length=40) private String registrationNumber;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;

    protected OrganizationEntity(){}
    public OrganizationEntity(UUID id,String legalName,String displayName,String countryCode){
        if(id==null || legalName==null || legalName.isBlank() || displayName==null || displayName.isBlank()
            || countryCode==null || countryCode.length()!=2) throw new IllegalArgumentException("Valid organization required");
        this.id=id;this.legalName=legalName;this.displayName=displayName;
        this.countryCode=countryCode;this.organizationType=Type.LEGAL_ENTITY;
        this.status=Status.DRAFT;this.createdAt=Instant.now();this.updatedAt=this.createdAt;
    }
    public UUID getId(){return id;}
    public enum Type { LEGAL_ENTITY, SOLE_PROPRIETOR, OTHER }
    public enum Status { DRAFT, ACTIVE, SUSPENDED, CLOSED }
}
