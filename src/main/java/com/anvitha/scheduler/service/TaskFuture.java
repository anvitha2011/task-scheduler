package com.anvitha.scheduler.service;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import com.anvitha.scheduler.model.TaskResult;

/**
 * A thread-safe, custom Future implementation backed by ReentrantLock and Condition.
 *
 * <p><strong>ANVITHA Interview Point - Future Internals:</strong>
 * Demonstrates how {@link java.util.concurrent.Future#get()} blocks calling threads
 * using monitor condition variables until the worker thread signals completion.</p>
 *
 * @param <T> Result type.
 */
public class TaskFuture<T> implements Future<T> {

    private final String taskId;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition isCompleteCondition = lock.newCondition();

    private volatile boolean isDone = false;
    private volatile boolean isCancelled = false;
    private TaskResult<T> result;

    public TaskFuture(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Called by the scheduler when the task execution finishes.
     */
    public void complete(TaskResult<T> result) {
        lock.lock();
        try {
            this.result = result;
            this.isDone = true;
            isCompleteCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        lock.lock();
        try {
            if (isDone) {
                return false;
            }
            this.isCancelled = true;
            this.isDone = true;
            this.result = TaskResult.cancelled(taskId, "Cancelled");
            isCompleteCondition.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isCancelled() {
        return isCancelled;
    }

    @Override
    public boolean isDone() {
        return isDone;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        lock.lockInterruptibly();
        try {
            while (!isDone) {
                isCompleteCondition.await();
            }
            return resolveResult();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (!isDone) {
                if (nanos <= 0L) {
                    throw new TimeoutException("Task timed out after " + timeout + " " + unit);
                }
                nanos = isCompleteCondition.awaitNanos(nanos);
            }
            return resolveResult();
        } finally {
            lock.unlock();
        }
    }

    private T resolveResult() throws ExecutionException {
        if (isCancelled) {
            throw new CancellationException("Task was cancelled");
        }
        if (result != null && result.isFailure()) {
            throw new ExecutionException(result.getException());
        }
        return result != null ? result.getValue() : null;
    }

    public TaskResult<T> getTaskResult() {
        return result;
    }

    public String getTaskId() {
        return taskId;
    }
}
