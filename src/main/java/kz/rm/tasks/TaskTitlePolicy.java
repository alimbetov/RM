package kz.rm.tasks;

import java.util.Map;

/** Single closed vocabulary for operational task titles and safe calendar codes. */
public final class TaskTitlePolicy {
    private TaskTitlePolicy() {}
    public enum TitleCode {
        PREPARE_CUSTOMER_CALL, REVIEW_ORDER, CHECK_INVENTORY,
        REVIEW_DOCUMENT, GENERAL_OPERATIONS, TASK_GENERIC
    }
    private static final Map<String,TitleCode> ALLOWED=Map.of(
        "Prepare customer call",TitleCode.PREPARE_CUSTOMER_CALL,
        "Review order",TitleCode.REVIEW_ORDER,
        "Check inventory",TitleCode.CHECK_INVENTORY,
        "Review document",TitleCode.REVIEW_DOCUMENT,
        "General operations",TitleCode.GENERAL_OPERATIONS
    );
    public static String requireApproved(String title) {
        if(title==null||!ALLOWED.containsKey(title))
            throw new IllegalArgumentException("TASK_TITLE_NOT_APPROVED");
        return title;
    }
    public static TitleCode calendarCode(String title){
        return title==null?TitleCode.TASK_GENERIC:ALLOWED.getOrDefault(title,TitleCode.TASK_GENERIC);
    }
}
