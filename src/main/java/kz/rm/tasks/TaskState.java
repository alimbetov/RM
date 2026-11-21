package kz.rm.tasks;
/** Task archiving must not erase audit-worthy activity. */
public enum TaskState { OPEN, IN_PROGRESS, BLOCKED, COMPLETED, ARCHIVED }
