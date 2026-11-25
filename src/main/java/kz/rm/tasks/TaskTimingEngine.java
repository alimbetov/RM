package kz.rm.tasks;

/**
 * TASK-owned single SQL timing authority. Active-only projection is a history-free
 * specialization; the full relation adds immutable completion/deadline evidence.
 */
final class TaskTimingEngine {
    static final String POLICY_VERSION = "TASK_TIMELINESS_V1";
    private TaskTimingEngine() {}

    // Operands are internal SQL fragments, never user input.
    static String signedMicros(boolean pg, String a, String b) {
        return pg ? "CAST(EXTRACT(EPOCH FROM ("+b+"-"+a+"))*1000000 AS NUMERIC(30,6))"
            : "CAST(DATEDIFF('MICROSECOND', "+a+" AT TIME ZONE 'UTC', "
              +b+" AT TIME ZONE 'UTC') AS NUMERIC(30,6))";
    }

    private static String activeOverduePredicate() {
        return "status IN ('OPEN','IN_PROGRESS','BLOCKED') AND assignee_user_id IS NOT NULL "+
               "AND due_at<evaluated_at";
    }

    static String activeClassification() {
        return """
            CASE WHEN status='CANCELLED' THEN 'CANCELLED'
                 WHEN status IN ('COMPLETED','ARCHIVED') THEN 'UNKNOWN'
                 WHEN status='QUEUED' THEN 'QUEUED'
                 WHEN status NOT IN ('OPEN','IN_PROGRESS','BLOCKED') THEN 'UNKNOWN'
                 WHEN due_at IS NULL OR assignee_user_id IS NULL THEN 'UNSCHEDULED'
                 WHEN %s THEN 'OVERDUE'
                 WHEN d<=86400000000 THEN 'DUE_SOON'
                 WHEN d>86400000000 THEN 'ACTIVE'
                 ELSE 'UNKNOWN' END
            """.formatted(activeOverduePredicate());
    }

    static String activeRelation(boolean pg) {
        return """
            SELECT id,status,due_at,row_version,timeliness,
                   %s AS direction,
                   %s AS magnitude_micros,
                   %s AS severity
            FROM (
                SELECT f.*, %s AS timeliness
                FROM (
                    SELECT id,status,due_at,row_version,assignee_user_id,c.evaluated_at,
                           CAST(NULL AS NUMERIC(30,6)) AS saved,%s AS d
                    FROM crm.task_item
                    CROSS JOIN (SELECT CAST(? AS TIMESTAMP(6) WITH TIME ZONE) AS evaluated_at) c
                    WHERE tenant_id=? AND (assignee_user_id=? OR created_by=?)
                ) AS f
            ) AS classified
            """.formatted(direction(), magnitude(), severity(), activeClassification(),
                signedMicros(pg,"evaluated_at","due_at"));
    }

    static String overduePage(boolean pg, boolean hasCursor) {
        if(pg){
            // Each status is disjoint. Its first K qualifying rows suffice for
            // the global first K, including ties ordered by (due_at,id).
            // Existing (tenant_id,status,due_at,id) index can serve each
            // bounded ordered scan; scope and cursor precede BOTH limits.
            return """
                SELECT id,status,due_at,row_version,-d AS magnitude_micros,%s AS severity
                FROM (
                    SELECT keys.*, 'OVERDUE' AS timeliness,%s AS d
                    FROM (
                        SELECT candidate.*,p.evaluated_at
                        FROM (SELECT CAST(? AS TIMESTAMP(6) WITH TIME ZONE) AS evaluated_at,
                                     CAST(? AS UUID) AS scope_tenant,
                                     CAST(? AS UUID) AS assignee_actor,
                                     CAST(? AS UUID) AS creator_actor,
                                     %s CAST(? AS INTEGER) AS page_limit) p
                        CROSS JOIN LATERAL (
                            SELECT part.*
                            FROM (VALUES (CAST('OPEN' AS VARCHAR(20))),
                                         (CAST('IN_PROGRESS' AS VARCHAR(20))),
                                         (CAST('BLOCKED' AS VARCHAR(20)))) AS states(active_state)
                            CROSS JOIN LATERAL (
                                SELECT id,status,due_at,row_version
                                FROM crm.task_item
                                WHERE tenant_id=p.scope_tenant AND status=states.active_state
                                  AND (assignee_user_id=p.assignee_actor OR created_by=p.creator_actor)
                                  AND (%s) %s
                                ORDER BY due_at,id LIMIT p.page_limit
                            ) part
                            ORDER BY due_at,id LIMIT p.page_limit
                        ) candidate
                    ) keys
                ) timing ORDER BY due_at,id
                """.formatted(severity(),signedMicros(true,"evaluated_at","due_at"),
                    hasCursor?"CAST(? AS TIMESTAMP(6) WITH TIME ZONE) AS last_due, CAST(? AS UUID) AS last_id,":"",
                    activeOverduePredicate(),hasCursor?" AND (due_at,id)>(p.last_due,p.last_id) ":"");
        }
        return "SELECT id,status,due_at,row_version,magnitude_micros,severity FROM ("+
            activeRelation(pg)+") AS timing WHERE timeliness='OVERDUE' "+
            (hasCursor?" AND (due_at>? OR (due_at=? AND id>?)) ":"")+
            " ORDER BY due_at ASC,id ASC LIMIT ?";
    }
    // Shared projection fragments. For active rows the terminal branches cannot match.
    static String direction() {
        return """
            CASE WHEN timeliness='COMPLETED_LATE' THEN 'LATE'
                 WHEN timeliness IN ('EARLY_50','ON_TIME') AND saved>0 THEN 'EARLY'
                 WHEN timeliness='ON_TIME' AND saved=0 THEN 'EXACT_COMPLETION'
                 WHEN timeliness='OVERDUE' THEN 'OVERDUE'
                 WHEN timeliness='DUE_SOON' AND d=0 THEN 'DUE_NOW'
                 WHEN timeliness IN ('DUE_SOON','ACTIVE') THEN 'REMAINING'
                 ELSE 'NONE' END
            """;
    }
    static String magnitude() {
        return """
            CASE WHEN timeliness='COMPLETED_LATE' THEN -saved
                 WHEN timeliness IN ('EARLY_50','ON_TIME') THEN saved
                 WHEN timeliness='OVERDUE' THEN -d
                 WHEN timeliness IN ('DUE_SOON','ACTIVE') THEN d
                 ELSE NULL END
            """;
    }
    static String severity() {
        return """
            CASE WHEN timeliness<>'OVERDUE' THEN NULL
                 WHEN -d<86400000000 THEN 'ATTENTION'
                 WHEN -d<259200000000 THEN 'HIGH'
                 ELSE 'CRITICAL' END
            """;
    }

    // Scope event evidence before windowing. Composite tenant/task is mandatory.
    // The previous due-change pointer ignores intervening non-deadline events.
    private static String eventSummary(boolean point,boolean pageOnly) {
        // For bounded calendar pages, the predecessor of a due-change
        // event is LAG within the due-change partition. Non-change rows
        // never use that predecessor. UNIQUE(tenant_id,task_id,new_version)
        // makes this exactly the same row as the legacy self-join, including
        // an invalid NULL next_due_at. Keep point/full reference SQL intact.
        String previousChange=pageOnly?"""
                    LAG(new_version) OVER(PARTITION BY tenant_id,task_id,
                        CASE WHEN action_code='DUE_DATE_CHANGED' THEN 1 ELSE 0 END
                        ORDER BY new_version) AS prev_change,
                    LAG(next_due_at) OVER(PARTITION BY tenant_id,task_id,
                        CASE WHEN action_code='DUE_DATE_CHANGED' THEN 1 ELSE 0 END
                        ORDER BY new_version) AS prior_next_due,
            """:"""
                    MAX(CASE WHEN action_code='DUE_DATE_CHANGED' THEN new_version END)
                        OVER(PARTITION BY tenant_id,task_id ORDER BY new_version
                             ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prev_change,
            """;
        String predecessor=pageOnly?"e.prior_next_due":"prior.next_due_at";
        String missing=pageOnly?"":"prior.new_version IS NULL OR ";
        String previousJoin=pageOnly?"":"""
                LEFT JOIN crm.task_event prior ON prior.tenant_id=e.tenant_id
                AND prior.task_id=e.task_id AND prior.new_version=e.prev_change
            """;
        return """
            SELECT e.tenant_id,e.task_id,COUNT(*) AS event_count,
                   MIN(e.new_version) AS first_version,MAX(e.new_version) AS last_version,
                   MAX(CASE WHEN e.new_version=e.max_version THEN e.to_state END) AS last_state,
                   SUM(CASE WHEN e.new_version=0 AND e.action_code='CREATED'
                                 AND e.previous_version IS NULL AND e.from_state IS NULL AND e.to_state='OPEN'
                            THEN 0
                            WHEN e.new_version>0 AND e.previous_version=e.new_version-1
                                 AND e.prev_version=e.new_version-1
                                 AND e.from_state=e.prev_state
                                 AND (%s)
                            THEN 0 ELSE 1 END) AS chain_bad,
                   SUM(CASE WHEN e.action_code='DUE_DATE_CHANGED' THEN 1 ELSE 0 END) AS change_count,
                   SUM(CASE WHEN e.action_code='DUE_DATE_CHANGED'
                                 AND (e.next_due_at IS NULL OR e.from_state IS NULL
                                      OR e.from_state NOT IN ('OPEN','IN_PROGRESS','BLOCKED')
                                      OR e.from_state IS DISTINCT FROM e.to_state
                                      OR (e.prev_change IS NOT NULL AND
                                          (%se.previous_due_at IS DISTINCT FROM %s)))
                            THEN 1 ELSE 0 END) AS change_bad,
                   SUM(CASE WHEN e.action_code<>'DUE_DATE_CHANGED'
                                  AND (e.previous_due_at IS NOT NULL OR e.next_due_at IS NOT NULL)
                            THEN 1 ELSE 0 END) AS unexpected_due,
                   SUM(CASE WHEN e.action_code='DUE_DATE_CHANGED'
                                 AND e.next_due_at IS NOT NULL AND e.from_state=e.to_state
                                 AND e.from_state IN ('OPEN','IN_PROGRESS','BLOCKED')
                                 AND e.previous_version=e.new_version-1 AND e.prev_version=e.new_version-1
                                 AND e.prev_state=e.from_state
                                 AND (e.prev_change IS NULL OR e.previous_due_at IS NOT DISTINCT FROM %s)
                            THEN 1 ELSE 0 END) AS valid_changes,
                   MAX(CASE WHEN e.action_code='DUE_DATE_CHANGED' AND e.prev_change IS NULL THEN e.previous_due_at END) AS first_due,
                   MAX(CASE WHEN e.action_code='DUE_DATE_CHANGED' AND e.new_version=e.last_change THEN e.next_due_at END) AS last_due,
                   %s
            FROM (
                SELECT x.*,
                    LAG(new_version) OVER(PARTITION BY tenant_id,task_id ORDER BY new_version) AS prev_version,
                    LAG(to_state) OVER(PARTITION BY tenant_id,task_id ORDER BY new_version) AS prev_state,
                    MAX(new_version) OVER(PARTITION BY tenant_id,task_id) AS max_version,
                    %s
                    MAX(CASE WHEN action_code='DUE_DATE_CHANGED' THEN new_version END)
                        OVER(PARTITION BY tenant_id,task_id) AS last_change
                FROM crm.task_event x WHERE tenant_id=? %s
            ) e %s
            GROUP BY e.tenant_id,e.task_id
            """.formatted(transition(),missing,predecessor,predecessor,terminalAggregates(),previousChange,
                point?"AND task_id=?":pageOnly?"AND task_id IN (SELECT id FROM page_keys)":"",previousJoin);
    }


    private static String terminalAggregates() {
        return """
                   SUM(CASE WHEN e.action_code='COMPLETED' OR e.to_state='COMPLETED' THEN 1 ELSE 0 END) AS completion_count,
                   SUM(CASE WHEN e.action_code='COMPLETED' AND e.from_state='IN_PROGRESS'
                                 AND e.to_state='COMPLETED' AND e.new_version>0
                                 AND e.previous_version=e.new_version-1 THEN 1 ELSE 0 END) AS completion_valid,
                   MAX(CASE WHEN e.action_code='COMPLETED' THEN e.new_version END) AS completion_version,
                   MAX(CASE WHEN e.action_code='COMPLETED' THEN e.occurred_at END) AS completion_time,
                   SUM(CASE WHEN e.action_code='ARCHIVED' OR e.to_state='ARCHIVED' THEN 1 ELSE 0 END) AS archive_count,
                   SUM(CASE WHEN e.action_code='ARCHIVED' AND e.from_state='COMPLETED'
                                 AND e.to_state='ARCHIVED' AND e.new_version>0
                                 AND e.previous_version=e.new_version-1 THEN 1 ELSE 0 END) AS archive_valid,
                   MAX(CASE WHEN e.action_code='ARCHIVED' THEN e.new_version END) AS archive_version,
                   SUM(CASE WHEN e.action_code='CANCELLED' OR e.to_state='CANCELLED'
                                 OR (e.from_state='COMPLETED' AND e.action_code<>'ARCHIVED')
                                 OR e.from_state IN ('ARCHIVED','CANCELLED') THEN 1 ELSE 0 END) AS terminal_conflicts
            """;
    }
    private static String terminalSummary() {
        return """
            SELECT e.tenant_id,e.task_id,%s
            FROM crm.task_event e
            WHERE e.tenant_id=? AND (
                e.action_code IN ('COMPLETED','ARCHIVED','CANCELLED')
                OR e.to_state IN ('COMPLETED','ARCHIVED','CANCELLED')
                OR e.from_state IN ('COMPLETED','ARCHIVED','CANCELLED'))
            GROUP BY e.tenant_id,e.task_id
            """.formatted(terminalAggregates());
    }

    private static String transition() {
        return """
            (e.action_code='STARTED' AND e.from_state='OPEN' AND e.to_state='IN_PROGRESS')
            OR (e.action_code='BLOCKED' AND e.from_state='IN_PROGRESS' AND e.to_state='BLOCKED')
            OR (e.action_code='RESUMED' AND e.from_state='BLOCKED' AND e.to_state='IN_PROGRESS')
            OR (e.action_code='COMPLETED' AND e.from_state='IN_PROGRESS' AND e.to_state='COMPLETED')
            OR (e.action_code='ARCHIVED' AND e.from_state='COMPLETED' AND e.to_state='ARCHIVED')
            OR (e.action_code='ASSIGNED' AND ((e.from_state IN ('OPEN','BLOCKED') AND e.to_state=e.from_state)
                                             OR (e.from_state='QUEUED' AND e.to_state='OPEN')))
            OR (e.action_code='DUE_DATE_CHANGED' AND e.from_state IN ('OPEN','IN_PROGRESS','BLOCKED')
                                                   AND e.to_state=e.from_state)
            OR (e.action_code='REQUEUED' AND e.from_state IN ('OPEN','BLOCKED') AND e.to_state='QUEUED')
            OR (e.action_code='CANCELLED' AND e.from_state IN ('OPEN','IN_PROGRESS','BLOCKED','QUEUED')
                                             AND e.to_state='CANCELLED')
            """;
    }

    static String completionClassification() {
        return """
            CASE WHEN completion_evidence<>'VALID' OR due_at IS NULL THEN 'UNKNOWN'
                 WHEN saved<0 THEN 'COMPLETED_LATE'
                 WHEN p>0 AND e>=0 AND 2*e<=p THEN 'EARLY_50'
                 ELSE 'ON_TIME' END
            """;
    }

    // Bind order: cutoff, event tenant[,event task], task tenant, actor, actor[,task].
    // Future calendar predicates operate on this classified relation, before LIMIT.
    static String fullRelation(boolean pg, boolean point) {
        return fullRelation(pg,point,false);
    }
    static String fullRelation(boolean pg,boolean point,boolean pageOnly) {
        String scoped="""
            SELECT t.id,t.status,t.due_at,t.row_version,t.created_at,t.title,t.assignee_user_id,
                   t.created_by,t.tenant_id,c.evaluated_at,
                   ev.completion_time,ev.first_due,ev.change_count,ev.valid_changes,
                   %s AS completion_evidence,
                   CASE WHEN ev.event_count=CAST(t.row_version AS NUMERIC(30,0))+1
                             AND ev.first_version=0 AND ev.last_version=t.row_version
                             AND ev.last_state=t.status AND ev.chain_bad=0 AND ev.change_bad=0
                             AND ev.unexpected_due=0
                             AND (ev.change_count=0 OR ev.last_due IS NOT DISTINCT FROM t.due_at)
                        THEN TRUE ELSE FALSE END AS original_valid
            FROM crm.task_item t CROSS JOIN (SELECT CAST(? AS TIMESTAMP(6) WITH TIME ZONE) AS evaluated_at) c
            LEFT JOIN (%s) ev ON ev.tenant_id=t.tenant_id AND ev.task_id=t.id
            WHERE t.tenant_id=? AND (t.assignee_user_id=? OR t.created_by=?) %s
            """.formatted(completionEvidence(),eventSummary(point,pageOnly),point?"AND t.id=?":pageOnly?"AND t.id IN (SELECT id FROM page_keys)":"");
        String dates="""
            SELECT s.*,
                   CASE WHEN completion_evidence='VALID' THEN completion_time ELSE NULL END AS completed_at,
                   CASE WHEN original_valid THEN CASE WHEN change_count=0 THEN due_at ELSE first_due END ELSE NULL END AS original_due_at,
                   CASE WHEN original_valid THEN CASE WHEN (CASE WHEN change_count=0 THEN due_at ELSE first_due END) IS NULL
                                                      THEN 'KNOWN_NO_DEADLINE' ELSE 'KNOWN' END ELSE 'UNKNOWN' END AS original_evidence,
                   CASE WHEN original_valid THEN change_count>0
                        WHEN valid_changes>0 THEN TRUE ELSE NULL END AS deadline_changed
            FROM (%s) s
            """.formatted(scoped);
        String facts="""
            SELECT dt.*, %s AS d, %s AS p, %s AS e, %s AS saved
            FROM (%s) dt
            """.formatted(signedMicros(pg,"evaluated_at","due_at"),signedMicros(pg,"created_at","due_at"),
                signedMicros(pg,"created_at","completed_at"),signedMicros(pg,"completed_at","due_at"),dates);
        return """
            SELECT q.*, %s AS direction,%s AS magnitude_micros,%s AS severity,
                   CASE WHEN original_evidence='UNKNOWN' THEN 'UNKNOWN'
                        WHEN original_evidence='KNOWN_NO_DEADLINE' THEN 'NO_DEADLINE'
                        WHEN status NOT IN ('COMPLETED','ARCHIVED') THEN 'NOT_COMPLETED'
                        WHEN completion_evidence<>'VALID' THEN 'UNKNOWN'
                        WHEN completed_at>original_due_at THEN 'LATE' ELSE 'ON_TIME' END AS original_outcome
            FROM (
                SELECT f.*,CASE WHEN status IN ('COMPLETED','ARCHIVED') THEN %s
                                ELSE %s END AS timeliness
                FROM (%s) f
            ) q
            """.formatted(direction(),magnitude(),severity(),completionClassification(),activeClassification(),facts);
    }

    private static String completionEvidence() {
        return """
                   CASE WHEN t.status NOT IN ('COMPLETED','ARCHIVED') THEN 'NOT_APPLICABLE'
                        WHEN COALESCE(ev.completion_count,0)=0 THEN 'MISSING'
                        WHEN ev.completion_count<>1 OR ev.completion_valid<>1
                             OR ev.terminal_conflicts<>0 OR ev.completion_time IS NULL
                             OR ev.completion_time<t.created_at
                             OR (t.status='COMPLETED' AND (ev.completion_version<>t.row_version OR ev.archive_count<>0))
                             OR (t.status='ARCHIVED' AND (ev.completion_version<>t.row_version-1
                                  OR ev.archive_count<>1 OR ev.archive_valid<>1 OR ev.archive_version<>t.row_version))
                        THEN 'INVALID'
                        WHEN ev.completion_time>c.evaluated_at THEN 'AFTER_CUTOFF'
                        ELSE 'VALID' END
            """;
    }
    // Original-deadline evidence is not a membership predicate. All terminal
    // validation and timing classification fragments are shared.
    static String calendarMembershipRelation() {
        String scoped="""
            SELECT t.id,t.status,t.due_at,t.created_at,t.row_version,
                   t.assignee_user_id,t.created_by,c.evaluated_at,
                   ev.completion_time,%s AS completion_evidence
            FROM crm.task_item t
            CROSS JOIN (SELECT CAST(? AS TIMESTAMP(6) WITH TIME ZONE) AS evaluated_at) c
            LEFT JOIN (%s) ev ON ev.tenant_id=t.tenant_id AND ev.task_id=t.id
            WHERE t.tenant_id=? AND (t.assignee_user_id=? OR t.created_by=?)
            """.formatted(completionEvidence(),terminalSummary());
        String dates="""
            SELECT s.*,CASE WHEN completion_evidence='VALID'
                       THEN completion_time ELSE NULL END AS completed_at
            FROM (%s) s
            """.formatted(scoped);
        String facts="""
            SELECT dt.*, %s AS d, %s AS p, %s AS e, %s AS saved
            FROM (%s) dt
            """.formatted(signedMicros(true,"evaluated_at","due_at"),signedMicros(true,"created_at","due_at"),
                signedMicros(true,"created_at","completed_at"),signedMicros(true,"completed_at","due_at"),dates);
        return """
            SELECT f.*,CASE WHEN status IN ('COMPLETED','ARCHIVED') THEN %s
                            ELSE %s END AS timeliness
            FROM (%s) f
            """.formatted(completionClassification(),activeClassification(),facts);
    }
}
