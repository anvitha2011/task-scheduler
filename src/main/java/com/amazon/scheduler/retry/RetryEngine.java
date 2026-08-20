package com.amazon.scheduler.retry;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.amazon.scheduler.dlq.DeadLetterQueue;
import com.amazon.scheduler.model.Task;
import com.amazon.scheduler.model.TaskStatus;
import com.amazon.scheduler.queue.CustomPriorityBlockingQueue;

/**
 * Handles task failures, exponential backoff scheduling, and DLQ routing.
 *
 * <p><strong>Amazon Interview Point - Non-blocking Worker Threads:</strong>
 * When a task fails, worker threads must NEVER sleep for backoff delays (which would starve
 * the thread pool). Instead, the RetryEngine uses a lightweight timer to delay re-enqueuing
 * the task into the main Priority Queue, immediately freeing the worker for other work.</p>
 */
public class RetryEngine {

    private final CustomPriorityBlockingQueue<Task<?>> taskQueue;
    private final DeadLetterQueue deadLetterQueue;
    private final ScheduledExecutorService retryScheduler;
    private final RetryListener retryListener;

    @FunctionalInterface
    public interface RetryListener {
        void onRetryScheduled(Task<?> task, int attempt, long delayMillis);
    }

    public RetryEngine(CustomPriorityBlockingQueue<Task<?>> taskQueue, DeadLetterQueue deadLetterQueue) {
        this(taskQueue, deadLetterQueue, null);
    }

    public RetryEngine(CustomPriorityBlockingQueue<Task<?>> taskQueue, DeadLetterQueue deadLetterQueue, RetryListener retryListener) {
        this.taskQueue = taskQueue;
        this.deadLetterQueue = deadLetterQueue;
        this.retryListener = retryListener;
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "scheduler-retry-timer-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Processes a failed task according to its retry policy.
     *
     * @param task The task that failed.
     * @param error The exception encountered during execution.
     * @return true if scheduled for retry, false if routed to DLQ.
     */
    public boolean handleFailure(Task<?> task, Throwable error) {
        task.setLastException(error);
        int currentAttempt = task.getRetryCount() + 1;

        if (task.getRetryPolicy().shouldRetry(currentAttempt, error)) {
            task.incrementRetryCount();
            task.setStatus(TaskStatus.RETRYING);

            long delayMillis = task.getRetryPolicy().calculateBackoffMillis(task.getRetryCount());
            task.setScheduledExecutionTime(System.currentTimeMillis() + delayMillis);

            if (retryListener != null) {
                retryListener.onRetryScheduled(task, task.getRetryCount(), delayMillis);
            }

            // Schedule non-blocking re-enqueue into priority queue after backoff
            retryScheduler.schedule(() -> {
                try {
                    task.setStatus(TaskStatus.QUEUED);
                    taskQueue.put(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, delayMillis, TimeUnit.MILLISECONDS);

            return true;
        } else {
            task.setStatus(TaskStatus.FAILED);
            deadLetterQueue.recordFailure(task, error);
            return false;
        }
    }

    /**
     * Shuts down the retry scheduler cleanly.
     */
    public void shutdown() {
        retryScheduler.shutdown();
    }

    public void shutdownNow() {
        retryScheduler.shutdownNow();
    }
}
