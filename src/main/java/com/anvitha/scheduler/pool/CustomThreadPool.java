package com.anvitha.scheduler.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.anvitha.scheduler.model.Task;
import com.anvitha.scheduler.queue.CustomPriorityBlockingQueue;
import com.anvitha.scheduler.retry.RetryEngine;
import com.anvitha.scheduler.metrics.SchedulerMetrics;

/**
 * Thread Pool Manager managing worker thread lifecycles and shutdown coordination.
 *
 * <p><strong>ANVITHA Interview Point - Graceful vs Abrupt Shutdown:</strong>
 * <ul>
 *   <li>{@code shutdown()}: Stops accepting new work, lets existing workers finish tasks currently in the queue.</li>
 *   <li>{@code shutdownNow()}: Sends {@code interrupt()} to all worker threads immediately and returns unprocessed tasks.</li>
 *   <li>{@code awaitTermination()}: Uses {@code Thread.join()} coordination to block caller until workers terminate.</li>
 * </ul>
 * </p>
 */
public class CustomThreadPool {

    private final int poolSize;
    private final List<WorkerThread> workers;
    private final CustomPriorityBlockingQueue<Task<?>> taskQueue;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean isTerminated = new AtomicBoolean(false);

    public CustomThreadPool(int poolSize,
                            CustomPriorityBlockingQueue<Task<?>> taskQueue,
                            RetryEngine retryEngine,
                            SchedulerMetrics metrics,
                            TaskEventListener listener) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("Pool size must be greater than 0");
        }
        this.poolSize = poolSize;
        this.taskQueue = taskQueue;
        this.workers = new ArrayList<>(poolSize);

        for (int i = 1; i <= poolSize; i++) {
            WorkerThread worker = new WorkerThread(
                    "worker-thread-" + i,
                    taskQueue,
                    retryEngine,
                    metrics,
                    listener
            );
            workers.add(worker);
        }
    }

    /**
     * Starts all worker threads in the pool.
     */
    public synchronized void start() {
        for (WorkerThread worker : workers) {
            if (!worker.isAlive()) {
                worker.start();
            }
        }
    }

    /**
     * Initiates an orderly shutdown: workers drain the remaining tasks.
     */
    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            for (WorkerThread worker : workers) {
                worker.stopWorker();
            }
        }
    }

    /**
     * Attempts to stop all actively executing tasks and halts processing of waiting tasks.
     */
    public List<Task<?>> shutdownNow() {
        shutdown();
        for (WorkerThread worker : workers) {
            worker.interrupt();
        }
        List<Task<?>> remaining = taskQueue.snapshot();
        taskQueue.clear();
        return remaining;
    }

    /**
     * Blocks until all workers have completed execution after a shutdown request,
     * or the timeout occurs, or the current thread is interrupted.
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);

        for (WorkerThread worker : workers) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return isAllWorkersDead();
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            if (millis > 0) {
                worker.join(millis);
            } else {
                worker.join(1);
            }
        }

        boolean terminated = isAllWorkersDead();
        if (terminated) {
            isTerminated.set(true);
        }
        return terminated;
    }

    private boolean isAllWorkersDead() {
        for (WorkerThread worker : workers) {
            if (worker.isAlive()) {
                return false;
            }
        }
        return true;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getActiveCount() {
        int count = 0;
        for (WorkerThread worker : workers) {
            if (worker.isWorking()) {
                count++;
            }
        }
        return count;
    }

    public boolean isShutdown() {
        return isShutdown.get();
    }

    public boolean isTerminated() {
        return isTerminated.get() || isAllWorkersDead();
    }

    public List<WorkerThread> getWorkers() {
        return new ArrayList<>(workers);
    }
}
