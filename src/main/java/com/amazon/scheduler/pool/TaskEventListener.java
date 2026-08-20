package com.amazon.scheduler.pool;

import com.amazon.scheduler.model.Task;

/**
 * Lifecycle observer for task execution events.
 */
public interface TaskEventListener {
    default void onTaskStarted(String workerName, Task<?> task) {}
    default void onTaskSuccess(String workerName, Task<?> task, Object result, long durationMs) {}
    default void onTaskFailure(String workerName, Task<?> task, Throwable error, long durationMs) {}
    default void onTaskRetry(String workerName, Task<?> task, int attemptNumber, Throwable error) {}
}
