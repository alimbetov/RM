package kz.rm.intelligence;

import jakarta.persistence.EntityManager;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Owner-only calendar governance. Metric readers will use independently authorized owner ports. */
@Service
public class CalendarPolicyService {
    public record Policy(UUID tenantId,long version,String zoneId,Instant effectiveFrom,UUID createdBy,Instant createdAt){}
    private final CalendarPolicyRepository repository;
    private final EntityManager em;
    private final TenantAuthorization authorization;
    private final Clock clock;
    CalendarPolicyService(CalendarPolicyRepository repository,EntityManager em,TenantAuthorization authorization,Clock clock){
        this.repository=repository;this.em=em;this.authorization=authorization;this.clock=clock;
    }
    @Transactional
    public Policy append(VerifiedActor actor,UUID tenant,String zoneId,long expectedVersion){
        UUID user=authorize(actor,tenant);
        if(expectedVersion<0 || zoneId==null || zoneId.length()>64)
            throw new IllegalArgumentException("Invalid calendar revision");
        ZoneId zone=ZoneId.of(zoneId);
        new PeriodResolver.Policy(zone,1);
        var current=repository.findFirstByTenantIdOrderByVersionDesc(tenant);
        long actual=current.map(CalendarPolicyEntity::getVersion).orElse(0L);
        if(actual!=expectedVersion)throw new IllegalStateException("CALENDAR_STALE_VERSION");
        if(current.isPresent() && current.get().getZoneId().equals(zoneId)){
            actor.userId();return dto(current.get());
        }
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(current.isPresent() && now.isBefore(current.get().getEffectiveFrom()))
            throw new IllegalStateException("CALENDAR_CLOCK_REGRESSION");
        var row=new CalendarPolicyEntity(tenant,Math.incrementExact(actual),zoneId,now,user);
        // Composite PK arbitrates competing inserts without upgrading the IAM SHARE lock.
        em.persist(row);em.flush();
        actor.userId();return dto(row);
    }
    @Transactional
    public Policy current(VerifiedActor actor,UUID tenant){
        authorize(actor,tenant);
        var row=repository.findFirstByTenantIdOrderByVersionDesc(tenant)
            .orElseThrow(()->new IllegalStateException("CALENDAR_NOT_CONFIGURED"));
        actor.userId();return dto(row);
    }
    @Transactional
    public Policy version(VerifiedActor actor,UUID tenant,long version){
        authorize(actor,tenant);
        var row=repository.findByTenantIdAndVersion(tenant,version)
            .orElseThrow(()->new IllegalArgumentException("CALENDAR_VERSION_NOT_FOUND"));
        actor.userId();return dto(row);
    }
    private UUID authorize(VerifiedActor actor,UUID tenant){
        if(actor==null||tenant==null)throw new SecurityException("Verified actor and tenant required");
        UUID user=actor.userId();
        authorization.requireForCommand(user,tenant,DomainPermission.TENANT_RBAC_MANAGE);
        return user;
    }
    private static Policy dto(CalendarPolicyEntity r){
        return new Policy(r.getTenantId(),r.getVersion(),r.getZoneId(),r.getEffectiveFrom(),r.getCreatedBy(),r.getCreatedAt());
    }
}
