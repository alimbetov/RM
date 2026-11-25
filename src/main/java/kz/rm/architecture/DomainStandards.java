package kz.rm.architecture;

import java.util.EnumMap;
import java.util.Map;

/**
 * Architecture-level standards per bounded context.
 * A policy entry is NOT proof of business implementation or security admission.
 */
public final class DomainStandards {
    public enum DataScope { GLOBAL, TENANT, MIXED }
    public enum Retention { LIFECYCLE, ARCHIVE, APPEND_ONLY, KEY_REVOKE, MIXED }
    public enum Privacy { NO_PII_BY_DESIGN, CLASSIFIED_FIELDS, ENCRYPTED_CONTENT, KEY_MATERIAL }
    public enum PartitionPlan { UNPARTITIONED, TENANT_HASH_CANDIDATE, TIME_TENANT_CANDIDATE, MIXED }

    public record Policy(DomainModule module, DataScope scope, Retention retention,
                         Privacy privacy, PartitionPlan partitionPlan) {}
    private static final Map<DomainModule,Policy> POLICIES=build();

    private static Map<DomainModule,Policy> build() {
        EnumMap<DomainModule,Policy> m=new EnumMap<>(DomainModule.class);
        add(m,DomainModule.IAM,DataScope.MIXED,Retention.LIFECYCLE,Privacy.CLASSIFIED_FIELDS,PartitionPlan.UNPARTITIONED);
        add(m,DomainModule.ORG,DataScope.MIXED,Retention.ARCHIVE,Privacy.CLASSIFIED_FIELDS,PartitionPlan.UNPARTITIONED);
        add(m,DomainModule.GEO,DataScope.GLOBAL,Retention.LIFECYCLE,Privacy.NO_PII_BY_DESIGN,PartitionPlan.UNPARTITIONED);
        add(m,DomainModule.FILES,DataScope.TENANT,Retention.MIXED,Privacy.ENCRYPTED_CONTENT,PartitionPlan.TENANT_HASH_CANDIDATE);
        add(m,DomainModule.CRM,DataScope.TENANT,Retention.MIXED,Privacy.CLASSIFIED_FIELDS,PartitionPlan.TENANT_HASH_CANDIDATE);
        add(m,DomainModule.CATALOG,DataScope.TENANT,Retention.ARCHIVE,Privacy.NO_PII_BY_DESIGN,PartitionPlan.UNPARTITIONED);
        add(m,DomainModule.SALES,DataScope.TENANT,Retention.MIXED,Privacy.CLASSIFIED_FIELDS,PartitionPlan.TIME_TENANT_CANDIDATE);
        add(m,DomainModule.INVENTORY,DataScope.TENANT,Retention.MIXED,Privacy.NO_PII_BY_DESIGN,PartitionPlan.TIME_TENANT_CANDIDATE);
        add(m,DomainModule.FINANCE,DataScope.TENANT,Retention.MIXED,Privacy.CLASSIFIED_FIELDS,PartitionPlan.TIME_TENANT_CANDIDATE);
        add(m,DomainModule.MARKETING,DataScope.TENANT,Retention.MIXED,Privacy.CLASSIFIED_FIELDS,PartitionPlan.TIME_TENANT_CANDIDATE);
        add(m,DomainModule.TASKS,DataScope.TENANT,Retention.MIXED,Privacy.CLASSIFIED_FIELDS,PartitionPlan.UNPARTITIONED);
        add(m,DomainModule.AUDIT,DataScope.TENANT,Retention.APPEND_ONLY,Privacy.NO_PII_BY_DESIGN,PartitionPlan.TIME_TENANT_CANDIDATE);
        add(m,DomainModule.KMS,DataScope.TENANT,Retention.KEY_REVOKE,Privacy.KEY_MATERIAL,PartitionPlan.UNPARTITIONED);
        add(m,DomainModule.SUBSCRIPTION,DataScope.MIXED,Retention.MIXED,Privacy.NO_PII_BY_DESIGN,PartitionPlan.UNPARTITIONED);
        add(m,DomainModule.STOREFRONT,DataScope.TENANT,Retention.LIFECYCLE,Privacy.CLASSIFIED_FIELDS,PartitionPlan.UNPARTITIONED);
        add(m,DomainModule.INTELLIGENCE,DataScope.TENANT,Retention.MIXED,Privacy.CLASSIFIED_FIELDS,PartitionPlan.TIME_TENANT_CANDIDATE);
        add(m,DomainModule.EVT,DataScope.TENANT,Retention.MIXED,Privacy.CLASSIFIED_FIELDS,PartitionPlan.TIME_TENANT_CANDIDATE);
        if(m.size()!=DomainModule.values().length)throw new IllegalStateException("Incomplete domain standards");
        return Map.copyOf(m);
    }
    private static void add(EnumMap<DomainModule,Policy> m,DomainModule module,
                            DataScope scope,Retention retention,Privacy privacy,PartitionPlan partition) {
        m.put(module,new Policy(module,scope,retention,privacy,partition));
    }
    public static Policy of(DomainModule module) {
        if(module==null)throw new IllegalArgumentException("Domain required");
        return POLICIES.get(module);
    }
    public static Map<DomainModule,Policy> all() {return POLICIES;}
    private DomainStandards() {}
}
