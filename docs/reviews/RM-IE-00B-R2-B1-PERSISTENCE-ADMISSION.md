# B1.1/B1.2 persistence admission checklist
Review SHA f428039fd15b29e5772123460fa0e99e899238dd. A2: all 15 decisions owner-approved. A3 remains HOLD; no runtime schema change yet.

A1 increment: actual migrated source columns, ordered tenant primary keys, decimal and timestamp types for seven relevant tables. Dedicated PostgreSQL 17 workflow checks exact SHA, source inventory and A2 contract and requires all eight selected tests without skips. This is clean-schema evidence, not populated-upgrade or full historical coverage proof.

B1 persistence slice after applicable A3 admission:
- Register INTELLIGENCE and EVT together with DomainStandards TENANT/MIXED retention, classified fields where applicable, no automatic grants; update exact module inventory and architecture tests.
- CalendarPolicyKey (tenantId,version); immutable CalendarPolicyEntity; tenant-qualified repository only; no generic public CRUD.
- CalendarPolicyService: verified actor, existing explicit owner governance permission for changes subject to reviewed scope; serialize revisions within tenant; expected version, reject stale update, explicit IANA zone, no implicit defaults; actor recheck before return. Never infer tenant from arbitrary claims.
- Proposed 0173+ table crm.int_calendar_policy: composite primary key, FK tenant to iam_workspace and creator to iam_user_account, version > 0, nonblank zone, effective_from and created_at non-null; immutable history; no silent default/backfill. Current-version selection and effective-time semantics must be qualified before enabling reads.
- Promote test prototypes to main source only after applicable gate. SQL owner ports remain separate.

Remaining blockers: global exact-SHA result, clean schema workflow result, populated migration and historical coverage evidence, current IAM scope races and independent admission. Production HOLD; main and design branch unchanged.
