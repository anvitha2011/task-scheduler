package com.amazon.scheduler.model;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Represents a unit of work submitted to the Task Scheduler.
 *
 * <p><strong>Amazon Interview Talking Point - FIFO Tie-Breaking:</strong>
 * Implements {@link Comparable} such that higher priority tasks execute first.
 * If two tasks share the same priority, the {@code sequenceNumber} breaks ties
 * in FIFO order, preventing starvation of older tasks within the same priority tier.</p>
 *
 * @param <T> The return type of the task computation.
 */
public class Task<T> implements Comparable<Task<?>> {

    private final String id;
    private final String name;
    private final Priority priority;
    private final Callable<T> action;
    private final RetryPolicy retryPolicy;
    private final long submissionTime;

    private long sequenceNumber;
    private int retryCount;
    private long scheduledExecutionTime; // Epoch millis when task is ready for execution (supports delayed retries)
    private volatile TaskStatus status;
    private Throwable lastException;

    public Task(String id, String name, Priority priority, Callable<T> action, RetryPolicy retryPolicy) {
        this.id = (id != null) ? id : UUID.randomUUID().toString().substring(0, 8);
        this.name = (name != null) ? name : "Task-" + this.id;
        this.priority = (priority != null) ? priority : Priority.MEDIUM;
        this.action = action;
        this.retryPolicy = (retryPolicy != null) ? retryPolicy : RetryPolicy.noRetry();
        this.submissionTime = System.currentTimeMillis();
        this.scheduledExecutionTime = this.submissionTime;
        this.status = TaskStatus.CREATED;
        this.retryCount = 0;
    }

    public static <T> Task<T> of(String name, Priority priority, Callable<T> action) {
        return new Task<>(null, name, priority, action, RetryPolicy.noRetry());
    }

    public static Task<Void> of(String name, Priority priority, Runnable runnable) {
        return new Task<>(null, name, priority, () -> {
            runnable.run();
            return null;
        }, RetryPolicy.noRetry());
    }

    public static <T> Task<T> of(String name, Priority priority, Callable<T> action, RetryPolicy retryPolicy) {
        return new Task<>(null, name, priority, action, retryPolicy);
    }

    public static Task<Void> of(String name, Priority priority, Runnable runnable, RetryPolicy retryPolicy) {
        return new Task<>(null, name, priority, () -> {
            runnable.run();
            return null;
        }, retryPolicy);
    }

    @Override
    public int compareTo(Task<?> other) {
        if (other == null) return -1;
        // 1. Primary order: Priority (Higher priority first -> descending)
        int priorityComparison = Integer.compare(other.priority.getLevel(), this.priority.getLevel());
        if (priorityComparison != 0) {
            return priorityComparison;
        }
        // 2. Secondary order: Sequence number (Earlier sequence first -> ascending FIFO)
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Priority getPriority() {
        return priority;
    }

    public Callable<T> getAction() {
        return action;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public long getSubmissionTime() {
        return submissionTime;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public long getScheduledExecutionTime() {
        return scheduledExecutionTime;
    }

    public void setScheduledExecutionTime(long scheduledExecutionTime) {
        this.scheduledExecutionTime = scheduledExecutionTime;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Throwable getLastException() {
        return lastException;
    }

    public void setLastException(Throwable lastException) {
        this.lastException = lastException;
    }

    @Override
    public String toString() {
        return String.format("Task[id='%s', name='%s', priority=%s, status=%s, retries=%d/%d]",
                id, name, priority, status, retryCount, retryPolicy.getMaxRetries());
    }
}
