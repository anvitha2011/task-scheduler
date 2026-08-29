package com.anvitha.scheduler.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free, high-throughput telemetry and operational metrics.
 *
 * <p><strong>ANVITHA Interview Point - LongAdder vs AtomicLong:</strong>
 * {@link LongAdder} is preferred over {@link java.util.concurrent.atomic.AtomicLong}
 * under high-contention multi-threaded environments. LongAdder maintains separate
 * cell variables across CPU cores to avoid cache-line bouncing (false sharing),
 * summing them up only on read.</p>
 */
public class SchedulerMetrics {

    private final LongAdder tasksSubmitted = new LongAdder();
    private final LongAdder tasksCompleted = new LongAdder();
    private final LongAdder tasksFailed = new LongAdder();
    private final LongAdder tasksRetried = new LongAdder();
    private final LongAdder totalExecutionTimeMs = new LongAdder();
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    public void recordSubmission() {
        tasksSubmitted.increment();
    }

    public void recordSuccess(long durationMs) {
        tasksCompleted.increment();
        totalExecutionTimeMs.add(durationMs);
    }

    public void recordFailure(long durationMs) {
        tasksFailed.increment();
        totalExecutionTimeMs.add(durationMs);
    }

    public void recordRetry() {
        tasksRetried.increment();
    }

    public void workerStarted() {
        activeWorkers.incrementAndGet();
    }

    public void workerFinished() {
        activeWorkers.decrementAndGet();
    }

    public long getTasksSubmitted() {
        return tasksSubmitted.sum();
    }

    public long getTasksCompleted() {
        return tasksCompleted.sum();
    }

    public long getTasksFailed() {
        return tasksFailed.sum();
    }

    public long getTasksRetried() {
        return tasksRetried.sum();
    }

    public int getActiveWorkers() {
        return activeWorkers.get();
    }

    public double getAverageExecutionTimeMs() {
        long completed = tasksCompleted.sum() + tasksFailed.sum();
        return completed == 0 ? 0.0 : (double) totalExecutionTimeMs.sum() / completed;
    }

    public double getSuccessRatePercentage() {
        long finished = tasksCompleted.sum() + tasksFailed.sum();
        return finished == 0 ? 100.0 : (double) tasksCompleted.sum() / finished * 100.0;
    }

    public String getSummary() {
        return String.format(
                "Metrics: [Submitted=%d, Completed=%d, Failed=%d, Retries=%d, ActiveWorkers=%d, AvgExec=%.2fms, SuccessRate=%.1f%%]",
                getTasksSubmitted(), getTasksCompleted(), getTasksFailed(), getTasksRetried(),
                getActiveWorkers(), getAverageExecutionTimeMs(), getSuccessRatePercentage()
        );
    }
}
