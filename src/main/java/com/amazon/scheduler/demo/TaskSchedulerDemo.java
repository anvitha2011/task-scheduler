package com.amazon.scheduler.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.amazon.scheduler.dlq.DeadLetterQueue;
import com.amazon.scheduler.model.Priority;
import com.amazon.scheduler.model.RetryPolicy;
import com.amazon.scheduler.model.Task;
import com.amazon.scheduler.pool.TaskEventListener;
import com.amazon.scheduler.service.TaskFuture;
import com.amazon.scheduler.service.TaskSchedulerService;

/**
 * Interactive Demonstration of the Multi-threaded Task Scheduler.
 *
 * <p>Showcases:
 * <ol>
 *   <li>Priority Precedence (CRITICAL & HIGH tasks executed before LOW).</li>
 *   <li>FIFO Tie-breaking among equal priority tasks (Starvation prevention).</li>
 *   <li>Exponential Backoff Retry on transient failures.</li>
 *   <li>Dead Letter Queue (DLQ) isolation for poison pills.</li>
 *   <li>Graceful pool shutdown and real-time telemetry metrics.</li>
 * </ol>
 * </p>
 */
public class TaskSchedulerDemo {

    public static void main(String[] args) throws Exception {
        printHeader("AMAZON INTERVIEW DEMO: MULTI-THREADED TASK SCHEDULER & JOB QUEUE");

        // 1. Initialize Scheduler with 3 worker threads and custom event listener
        TaskEventListener listener = new TaskEventListener() {
            @Override
            public void onTaskStarted(String workerName, Task<?> task) {
                System.out.printf("  [RUN  | %-15s] Started: %-25s (Priority: %-8s)%n",
                        workerName, task.getName(), task.getPriority());
            }

            @Override
            public void onTaskSuccess(String workerName, Task<?> task, Object result, long durationMs) {
                System.out.printf("  [DONE | %-15s] COMPLETED: %-23s (Time: %dms, Retries: %d, Result: %s)%n",
                        workerName, task.getName(), durationMs, task.getRetryCount(), result);
            }

            @Override
            public void onTaskFailure(String workerName, Task<?> task, Throwable error, long durationMs) {
                System.out.printf("  [FAIL | %-15s] PERMANENT FAILURE -> DLQ: %-15s (Error: %s)%n",
                        workerName, task.getName(), error.getMessage());
            }

            @Override
            public void onTaskRetry(String workerName, Task<?> task, int attempt, Throwable error) {
                System.out.printf("  [RETRY| %-15s] RETRYING: %-25s (Attempt #%d, Reason: %s)%n",
                        workerName, task.getName(), attempt, error.getMessage());
            }
        };

        try (TaskSchedulerService scheduler = new TaskSchedulerService(3, 100, false, listener)) {

            // =========================================================================
            // SCENARIO 1: Priority Inversion & FIFO Tie-Breaking
            // =========================================================================
            printSection("SCENARIO 1: Priority-Based Execution Order");
            System.out.println("Submitting 6 tasks with mixed priorities simultaneously...\n");

            List<TaskFuture<?>> futures = new ArrayList<>();

            // Submit LOW priority tasks first
            futures.add(scheduler.submit("Low-Priority-OrderSync-1", Priority.LOW, () -> {
                Thread.sleep(100);
                return "Order-101-Synced";
            }));
            futures.add(scheduler.submit("Low-Priority-OrderSync-2", Priority.LOW, () -> {
                Thread.sleep(100);
                return "Order-102-Synced";
            }));

            // Submit MEDIUM priority
            futures.add(scheduler.submit("Medium-Priority-InventoryCheck", Priority.MEDIUM, () -> {
                Thread.sleep(100);
                return "Inventory-Available";
            }));

            // Submit HIGH and CRITICAL priority last (to prove queue re-ordering)
            futures.add(scheduler.submit("High-Priority-PaymentCapture", Priority.HIGH, () -> {
                Thread.sleep(100);
                return "$149.99-Charged";
            }));
            futures.add(scheduler.submit("Critical-Priority-FraudAlert", Priority.CRITICAL, () -> {
                Thread.sleep(80);
                return "Account-Secured";
            }));
            futures.add(scheduler.submit("Critical-Priority-PrimeDelivery", Priority.CRITICAL, () -> {
                Thread.sleep(80);
                return "SameDay-Dispatched";
            }));

            // Wait for Scenario 1 tasks to finish
            for (TaskFuture<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            Thread.sleep(200);

            // =========================================================================
            // SCENARIO 2: Transient Failure & Exponential Backoff Retry
            // =========================================================================
            printSection("SCENARIO 2: Exponential Backoff Retry Handling");
            System.out.println("Submitting task 'PaymentGateway-Charge' configured to fail 2 times before succeeding...\n");

            AtomicInteger transientFailureCounter = new AtomicInteger(0);
            RetryPolicy expRetry = RetryPolicy.exponentialBackoff(3, 150, 2.0, 1000);

            TaskFuture<String> retryFuture = scheduler.submit("PaymentGateway-Charge", Priority.HIGH, () -> {
                int attempt = transientFailureCounter.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("Gateway Timeout (504) on attempt " + attempt);
                }
                return "Payment-Auth-Token-98765";
            }, expRetry);

            String paymentResult = retryFuture.get(5, TimeUnit.SECONDS);
            System.out.println("\n  >> Final Future Result: " + paymentResult);

            // =========================================================================
            // SCENARIO 3: Poison Pill & Dead Letter Queue (DLQ)
            // =========================================================================
            printSection("SCENARIO 3: Dead Letter Queue (DLQ) Isolation");
            System.out.println("Submitting poison pill task 'Corrupted-Payload-Job' with max 2 retries...\n");

            RetryPolicy dlqRetry = RetryPolicy.fixedRetry(2, 100);
            TaskFuture<Void> poisonFuture = scheduler.submit("Corrupted-Payload-Job", Priority.MEDIUM, () -> {
                throw new IllegalArgumentException("Fatal: Malformed JSON payload in customer record");
            }, dlqRetry);

            try {
                poisonFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.out.println("  >> Caller received expected failure: " + e.getCause().getMessage());
            }

            Thread.sleep(300);

            // Inspect DLQ
            DeadLetterQueue dlq = scheduler.getDeadLetterQueue();
            System.out.println("\n  [DLQ Status] Quarantined Poison Pills: " + dlq.size());
            for (DeadLetterQueue.DeadLetterRecord record : dlq.getRecords()) {
                System.out.printf("   - Task: %-22s | Total Attempts: %d | Root Cause: %s%n",
                        record.getTask().getName(), record.getTotalAttempts(), record.getFinalException().getMessage());
            }

            // =========================================================================
            // SCENARIO 4: Graceful Shutdown & Metrics Dashboard
            // =========================================================================
            printSection("SCENARIO 4: Graceful Shutdown & Telemetry Metrics");
            System.out.println("Initiating scheduler shutdown and awaiting worker termination...\n");

            scheduler.shutdown();
            boolean terminated = scheduler.awaitTermination(5, TimeUnit.SECONDS);

            System.out.println("  >> All worker threads terminated cleanly: " + terminated);
            System.out.println("  >> Final Metrics Dashboard: " + scheduler.getMetrics().getSummary());
            System.out.printf("  >> Total Jobs Submitted : %d%n", scheduler.getMetrics().getTasksSubmitted());
            System.out.printf("  >> Total Jobs Completed : %d%n", scheduler.getMetrics().getTasksCompleted());
            System.out.printf("  >> Total Jobs in DLQ    : %d%n", scheduler.getMetrics().getTasksFailed());
            System.out.printf("  >> Total Retries Fired  : %d%n", scheduler.getMetrics().getTasksRetried());
            System.out.printf("  >> Success Rate         : %.1f%%%n", scheduler.getMetrics().getSuccessRatePercentage());
            System.out.printf("  >> Avg Execution Time   : %.2f ms%n", scheduler.getMetrics().getAverageExecutionTimeMs());
        }

        printHeader("DEMONSTRATION COMPLETED SUCCESSFULLY");
    }

    private static void printHeader(String text) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("   " + text);
        System.out.println("=".repeat(80) + "\n");
    }

    private static void printSection(String text) {
        System.out.println("\n" + "-".repeat(80));
        System.out.println(" " + text);
        System.out.println("-".repeat(80));
    }
}
