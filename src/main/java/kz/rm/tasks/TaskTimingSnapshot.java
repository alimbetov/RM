package kz.rm.tasks;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/** Immutable safe timing facts. No raw event, title, actor or assignee. */
public record TaskTimingSnapshot(UUID taskId,TaskState state,long sourceVersion,
        Instant createdAt,Instant effectiveDueAt,Instant completedAt,
        CompletionEvidence completionEvidenceCode,Instant originalDueAt,
        OriginalEvidence originalEvidenceCode,OriginalOutcome originalDeadlineOutcome,
        Boolean deadlineChanged,Timeliness timeliness,Reason reasonCode,Direction direction,
        BigInteger magnitudeMicros,BigDecimal savingsPercent,
        TaskOverdueQueryService.Severity overdueSeverity,String policyVersion) {
    public enum CompletionEvidence { NOT_APPLICABLE,MISSING,INVALID,AFTER_CUTOFF,VALID }
    public enum OriginalEvidence { KNOWN,KNOWN_NO_DEADLINE,UNKNOWN }
    public enum OriginalOutcome { ON_TIME,LATE,NOT_COMPLETED,NO_DEADLINE,UNKNOWN }
    public enum Timeliness { CANCELLED,UNKNOWN,COMPLETED_LATE,EARLY_50,ON_TIME,QUEUED,UNSCHEDULED,OVERDUE,DUE_SOON,ACTIVE }
    public enum Direction { NONE,LATE,EARLY,EXACT_COMPLETION,OVERDUE,DUE_NOW,REMAINING }
    public enum Reason { TASK_DUE_TIME_EXCEEDED,NO_DEADLINE,NO_ASSIGNEE,COMPLETION_MISSING,
        COMPLETION_INVALID,COMPLETION_AFTER_CUTOFF,UNSUPPORTED_SOURCE,NONE }
}
