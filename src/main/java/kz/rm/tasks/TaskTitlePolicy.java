package kz.rm.tasks;

import java.util.Set;

/**
 * R2 closed vocabulary for publicly visible, non-confidential operational titles.
 * A title is a predefined label, never a user-supplied customer name, message or
 * account identifier. Confidential task text requires a separate encrypted field.
 */
public final class TaskTitlePolicy {
    private TaskTitlePolicy() {}
    private static final Set<String> ALLOWED = Set.of(
        "Prepare customer call",
        "Review order",
        "Check inventory",
        "Review document",
        "General operations"
    );

    public static String requireApproved(String title) {
        if (title == null || !ALLOWED.contains(title))
            throw new IllegalArgumentException("TASK_TITLE_NOT_APPROVED");
        return title;
    }
}
