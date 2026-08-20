package com.amazon.scheduler.pool;

import java.util.concurrent.TimeUnit;
import com.amazon.scheduler.model.Task;
import com.amazon.scheduler.model.TaskStatus;
import com.amazon.scheduler.queue.CustomPriorityBlockingQueue;
import com.amazon.scheduler.retry.RetryEngine;
import com.amazon.scheduler.metrics.SchedulerMetrics;

/**
 * Worker thread that continuously consumes and executes jobs from the Priority Queue.
 *
 * <p><strong>Amazon Interview Talking Point - The Worker Loop:</strong>
 * <ol>
 *   <li><strong>Blocking Dequeue:</strong> Calls {@code taskQueue.poll(timeout)} or {@code take()}, blocking via condition variable.</li>
 *   <li><strong>Fault-Tolerance:</strong> Wraps execution in {@code try-catch (Throwable)} to guarantee that bad user code
 *       cannot kill the worker thread.</li>
 *   <li><strong>Clean Interruption Handling:</strong> Properly re-asserts interruption flag and exits the loop on shutdown.</li>
 * </ol>
 * </p>
 */
public class WorkerThread extends Thread {

    private final CustomPriorityBlockingQueue<Task<?>> taskQueue;
    private final RetryEngine retryEngine;
    private final SchedulerMetrics metrics;
    private final TaskEventListener eventListener;

    private volatile boolean running = true;
    private volatile WorkerState state = WorkerState.IDLE;
    private volatile Task<?> currentTask = null;

    private volatile long taskStartTime = 0;

    public WorkerThread(String name,
                        CustomPriorityBlockingQueue<Task<?>> taskQueue,
                        RetryEngine retryEngine,
                        SchedulerMetrics metrics,
                        TaskEventListener eventListener) {
        super(name);
        this.taskQueue = taskQueue;
        this.retryEngine = retryEngine;
        this.metrics = metrics;
        this.eventListener = eventListener;
    }

    @Override
    public void run() {
        while (running && !isInterrupted()) {
            try {
                this.state = WorkerState.IDLE;
                this.currentTask = null;
                this.taskStartTime = 0;

                // Poll with a 500ms timeout to periodically re-check the running flag during shutdown
                Task<?> task = taskQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }

                executeTask(task);

            } catch (InterruptedException e) {
                // Thread was interrupted (e.g. shutdownNow or worker pool termination)
                interrupt();
                break;
            } catch (Throwable unexpected) {
                // Safeguard: Never let unexpected errors crash the worker event loop
                System.err.println("[" + getName() + "] Unexpected error in worker loop: " + unexpected.getMessage());
            }
        }
        this.state = WorkerState.STOPPED;
        this.currentTask = null;
        this.taskStartTime = 0;
    }

    private void executeTask(Task<?> task) {
        this.state = WorkerState.EXECUTING;
        this.currentTask = task;
        this.taskStartTime = System.currentTimeMillis();
        metrics.workerStarted();
        task.setStatus(TaskStatus.RUNNING);

        if (eventListener != null) {
            eventListener.onTaskStarted(getName(), task);
        }

        long startTime = this.taskStartTime;
        try {
            Object result = (task.getAction() != null) ? task.getAction().call() : null;
            long duration = System.currentTimeMillis() - startTime;

            task.setStatus(TaskStatus.COMPLETED);
            metrics.recordSuccess(duration);

            if (eventListener != null) {
                eventListener.onTaskSuccess(getName(), task, result, duration);
            }
        } catch (Throwable error) {
            long duration = System.currentTimeMillis() - startTime;

            boolean willRetry = retryEngine.handleFailure(task, error);
            if (willRetry) {
                metrics.recordRetry();
                if (eventListener != null) {
                    eventListener.onTaskRetry(getName(), task, task.getRetryCount(), error);
                }
            } else {
                metrics.recordFailure(duration);
                if (eventListener != null) {
                    eventListener.onTaskFailure(getName(), task, error, duration);
                }
            }
        } finally {
            this.taskStartTime = 0;
            metrics.workerFinished();
        }
    }

    /**
     * Flags the worker to stop after completing the current task or next poll timeout.
     */
    public void stopWorker() {
        this.running = false;
    }

    public WorkerState getWorkerState() {
        return state;
    }

    public Task<?> getCurrentTask() {
        return currentTask;
    }

    public long getTaskStartTime() {
        return taskStartTime;
    }

    public boolean isWorking() {
        return state == WorkerState.EXECUTING;
    }
}
