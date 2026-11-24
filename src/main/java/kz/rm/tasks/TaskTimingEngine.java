package kz.rm.tasks;

/**
 * TASK-owned single SQL timing authority, R0.1B active projection.
 * Terminal completion and original-deadline evidence are deliberately not
 * inferred from task_item; they enter this same relation in R0.1E/F.
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

    static String activeClassification() {
        return """
            CASE WHEN status='CANCELLED' THEN 'CANCELLED'
                 WHEN status IN ('COMPLETED','ARCHIVED') THEN 'UNKNOWN'
                 WHEN status='QUEUED' THEN 'QUEUED'
                 WHEN status NOT IN ('OPEN','IN_PROGRESS','BLOCKED') THEN 'UNKNOWN'
                 WHEN due_at IS NULL OR assignee_user_id IS NULL THEN 'UNSCHEDULED'
                 WHEN d<0 THEN 'OVERDUE'
                 WHEN d<=86400000000 THEN 'DUE_SOON'
                 WHEN d>86400000000 THEN 'ACTIVE'
                 ELSE 'UNKNOWN' END
            """;
    }

    static String activeRelation(boolean pg) {
        return """
            SELECT id,status,due_at,row_version,timeliness,
                   CASE WHEN timeliness='OVERDUE' THEN 'OVERDUE'
                        WHEN timeliness='DUE_SOON' AND d=0 THEN 'DUE_NOW'
                        WHEN timeliness IN ('DUE_SOON','ACTIVE') THEN 'REMAINING'
                        ELSE 'NONE' END AS direction,
                   CASE WHEN timeliness='OVERDUE' THEN -d
                        WHEN timeliness IN ('DUE_SOON','ACTIVE') THEN d
                        ELSE NULL END AS magnitude_micros,
                   CASE WHEN timeliness<>'OVERDUE' THEN NULL
                        WHEN -d<86400000000 THEN 'ATTENTION'
                        WHEN -d<259200000000 THEN 'HIGH'
                        ELSE 'CRITICAL' END AS severity
            FROM (
                SELECT f.*, %s AS timeliness
                FROM (
                    SELECT id,status,due_at,row_version,assignee_user_id,
                           %s AS d
                    FROM crm.task_item
                    WHERE tenant_id=? AND (assignee_user_id=? OR created_by=?)
                ) AS f
            ) AS classified
            """.formatted(activeClassification(),
                signedMicros(pg,"CAST(? AS TIMESTAMP(6) WITH TIME ZONE)","due_at"));
    }

    static String overduePage(boolean pg, boolean hasCursor) {
        return "SELECT id,status,due_at,row_version,magnitude_micros,severity FROM ("+
            activeRelation(pg)+") AS timing WHERE timeliness='OVERDUE' "+
            (hasCursor?" AND (due_at>? OR (due_at=? AND id>?)) ":"")+
            " ORDER BY due_at ASC,id ASC LIMIT ?";
    }
}
