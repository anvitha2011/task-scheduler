package com.amazon.scheduler.model;

/**
 * State machine representing the lifecycle of a task.
 */
public enum TaskStatus {
    CREATED,
    QUEUED,
    RUNNING,
    COMPLETED,
    RETRYING,
    FAILED,
    CANCELLED
}
