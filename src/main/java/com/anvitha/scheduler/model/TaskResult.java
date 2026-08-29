package com.anvitha.scheduler.model;

/**
 * Encapsulates the final execution result of a task.
 *
 * @param <T> The return type of the task computation.
 */
public class TaskResult<T> {

    private final String taskId;
    private final String taskName;
    private final TaskStatus status;
    private final T value;
    private final Throwable exception;
    private final int retryAttempts;
    private final long durationMillis;

    private TaskResult(String taskId, String taskName, TaskStatus status, T value, Throwable exception, int retryAttempts, long durationMillis) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.status = status;
        this.value = value;
        this.exception = exception;
        this.retryAttempts = retryAttempts;
        this.durationMillis = durationMillis;
    }

    public static <T> TaskResult<T> success(String taskId, String taskName, T value, int retryAttempts, long durationMillis) {
        return new TaskResult<>(taskId, taskName, TaskStatus.COMPLETED, value, null, retryAttempts, durationMillis);
    }

    public static <T> TaskResult<T> failure(String taskId, String taskName, Throwable exception, int retryAttempts, long durationMillis) {
        return new TaskResult<>(taskId, taskName, TaskStatus.FAILED, null, exception, retryAttempts, durationMillis);
    }

    public static <T> TaskResult<T> cancelled(String taskId, String taskName) {
        return new TaskResult<>(taskId, taskName, TaskStatus.CANCELLED, null, null, 0, 0);
    }

    public boolean isSuccess() {
        return status == TaskStatus.COMPLETED;
    }

    public boolean isFailure() {
        return status == TaskStatus.FAILED;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public T getValue() {
        return value;
    }

    public Throwable getException() {
        return exception;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    @Override
    public String toString() {
        if (isSuccess()) {
            return String.format("TaskResult[SUCCESS, id='%s', name='%s', duration=%dms, retries=%d, value=%s]",
                    taskId, taskName, durationMillis, retryAttempts, value);
        } else {
            return String.format("TaskResult[FAILURE, id='%s', name='%s', duration=%dms, retries=%d, error='%s']",
                    taskId, taskName, durationMillis, retryAttempts, exception != null ? exception.getMessage() : "none");
        }
    }
}
