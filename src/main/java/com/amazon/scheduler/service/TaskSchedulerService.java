package com.amazon.scheduler.service;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.amazon.scheduler.dlq.DeadLetterQueue;
import com.amazon.scheduler.metrics.SchedulerMetrics;
import com.amazon.scheduler.model.Priority;
import com.amazon.scheduler.model.RetryPolicy;
import com.amazon.scheduler.model.Task;
import com.amazon.scheduler.model.TaskResult;
import com.amazon.scheduler.model.TaskStatus;
import com.amazon.scheduler.pool.CustomThreadPool;
import com.amazon.scheduler.pool.TaskEventListener;
import com.amazon.scheduler.queue.CustomPriorityBlockingQueue;
import com.amazon.scheduler.retry.RetryEngine;

/**
 * High-level Task Scheduler Service orchestrating queueing, worker pool execution,
 * retries with exponential backoff, dead letter queueing, and metrics telemetry.
 *
 * <p><strong>Amazon Interview 2-Minute Architecture Walkthrough:</strong>
 * <ol>
 *   <li><strong>Submission (Producer):</strong> Client submits a {@link Task} with priority. Stamped with atomic sequence ID and enqueued into {@link CustomPriorityBlockingQueue}.</li>
 *   <li><strong>Execution (Consumer):</strong> {@link CustomThreadPool} workers block-dequeue tasks and execute them.</li>
 *   <li><strong>Fault Resilience:</strong> Transient failures route through {@link RetryEngine} for delayed re-enqueue; exhausted tasks move to {@link DeadLetterQueue}.</li>
 *   <li><strong>Observability & Lifecycle:</strong> Thread-safe {@link SchedulerMetrics} and graceful shutdown with {@link #shutdown()} and {@link #awaitTermination(long, TimeUnit)}.</li>
 * </ol>
 * </p>
 */
public class TaskSchedulerService implements AutoCloseable {

    private final CustomPriorityBlockingQueue<Task<?>> taskQueue;
    private final DeadLetterQueue deadLetterQueue;
    private final RetryEngine retryEngine;
    private final SchedulerMetrics metrics;
    private final CustomThreadPool threadPool;
    private final ConcurrentHashMap<String, TaskFuture<?>> futures = new ConcurrentHashMap<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    public TaskSchedulerService(int poolSize) {
        this(poolSize, Integer.MAX_VALUE, false, null);
    }

    public TaskSchedulerService(int poolSize, int queueCapacity, boolean fairQueue, TaskEventListener userListener) {
        this.taskQueue = new CustomPriorityBlockingQueue<>(queueCapacity, fairQueue);
        this.deadLetterQueue = new DeadLetterQueue();
        this.metrics = new SchedulerMetrics();

        this.retryEngine = new RetryEngine(taskQueue, deadLetterQueue, null);

        TaskEventListener internalListener = new TaskEventListener() {
            @Override
            public void onTaskStarted(String workerName, Task<?> task) {
                if (userListener != null) userListener.onTaskStarted(workerName, task);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void onTaskSuccess(String workerName, Task<?> task, Object result, long durationMs) {
                TaskFuture<Object> future = (TaskFuture<Object>) futures.remove(task.getId());
                if (future != null) {
                    future.complete(TaskResult.success(task.getId(), task.getName(), result, task.getRetryCount(), durationMs));
                }
                if (userListener != null) userListener.onTaskSuccess(workerName, task, result, durationMs);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void onTaskFailure(String workerName, Task<?> task, Throwable error, long durationMs) {
                TaskFuture<Object> future = (TaskFuture<Object>) futures.remove(task.getId());
                if (future != null) {
                    future.complete(TaskResult.failure(task.getId(), task.getName(), error, task.getRetryCount(), durationMs));
                }
                if (userListener != null) userListener.onTaskFailure(workerName, task, error, durationMs);
            }

            @Override
            public void onTaskRetry(String workerName, Task<?> task, int attemptNumber, Throwable error) {
                if (userListener != null) userListener.onTaskRetry(workerName, task, attemptNumber, error);
            }
        };

        this.threadPool = new CustomThreadPool(poolSize, taskQueue, retryEngine, metrics, internalListener);
        this.threadPool.start();
    }

    /**
     * Submits a Task instance to the scheduler.
     */
    public <T> TaskFuture<T> submit(Task<T> task) {
        if (isShutdown.get()) {
            throw new RejectedExecutionException("Scheduler has been shut down, cannot accept new tasks");
        }

        TaskFuture<T> future = new TaskFuture<>(task.getId());
        futures.put(task.getId(), future);

        metrics.recordSubmission();
        task.setStatus(TaskStatus.QUEUED);

        boolean offered = taskQueue.offer(task);
        if (!offered) {
            futures.remove(task.getId());
            task.setStatus(TaskStatus.FAILED);
            throw new RejectedExecutionException("Task queue is full (capacity=" + taskQueue.getCapacity() + ")");
        }

        return future;
    }

    /**
     * Helper to submit a Callable task with name and priority.
     */
    public <T> TaskFuture<T> submit(String name, Priority priority, Callable<T> action) {
        return submit(Task.of(name, priority, action));
    }

    /**
     * Helper to submit a Runnable task with name and priority.
     */
    public TaskFuture<Void> submit(String name, Priority priority, Runnable runnable) {
        return submit(Task.of(name, priority, runnable));
    }

    /**
     * Helper to submit a task with custom retry policy.
     */
    public <T> TaskFuture<T> submit(String name, Priority priority, Callable<T> action, RetryPolicy retryPolicy) {
        return submit(Task.of(name, priority, action, retryPolicy));
    }

    /**
     * Initiates orderly shutdown.
     */
    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            threadPool.shutdown();
            retryEngine.shutdown();
        }
    }

    /**
     * Immediately halts task processing and returns remaining queued tasks.
     */
    public List<Task<?>> shutdownNow() {
        isShutdown.set(true);
        retryEngine.shutdownNow();
        return threadPool.shutdownNow();
    }

    /**
     * Blocks until all tasks complete after shutdown, or timeout occurs.
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return threadPool.awaitTermination(timeout, unit);
    }

    public SchedulerMetrics getMetrics() {
        return metrics;
    }

    public DeadLetterQueue getDeadLetterQueue() {
        return deadLetterQueue;
    }

    public CustomPriorityBlockingQueue<Task<?>> getTaskQueue() {
        return taskQueue;
    }

    public List<Task<?>> getQueuedTasks() {
        return taskQueue.snapshot();
    }

    public List<com.amazon.scheduler.pool.WorkerThread> getWorkers() {
        return threadPool.getWorkers();
    }

    public CustomThreadPool getThreadPool() {
        return threadPool;
    }

    public RetryEngine getRetryEngine() {
        return retryEngine;
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    public int getActiveWorkerCount() {
        return threadPool.getActiveCount();
    }

    public int getThreadPoolSize() {
        return threadPool.getPoolSize();
    }

    public boolean isShutdown() {
        return isShutdown.get();
    }

    public boolean isTerminated() {
        return threadPool.isTerminated();
    }

    /**
     * Replays a specific task from the Dead Letter Queue back into the main priority queue.
     */
    public boolean replayDlqTask(String taskId) {
        DeadLetterQueue.DeadLetterRecord record = deadLetterQueue.getRecord(taskId);
        if (record == null) return false;

        Task<?> task = record.getTask();
        // Reset status for replay
        task.setStatus(TaskStatus.QUEUED);
        taskQueue.offer(task);
        return true;
    }

    /**
     * Replays all quarantined tasks from the Dead Letter Queue back into the main priority queue.
     */
    public int replayAllDlq() {
        List<DeadLetterQueue.DeadLetterRecord> list = deadLetterQueue.getRecords();
        int count = 0;
        for (DeadLetterQueue.DeadLetterRecord rec : list) {
            Task<?> task = rec.getTask();
            task.setStatus(TaskStatus.QUEUED);
            if (taskQueue.offer(task)) {
                count++;
            }
        }
        deadLetterQueue.clear();
        return count;
    }

    @Override
    public void close() {
        shutdown();
        try {
            awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
