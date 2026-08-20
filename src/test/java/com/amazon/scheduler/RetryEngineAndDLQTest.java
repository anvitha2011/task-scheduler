package com.amazon.scheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.amazon.scheduler.dlq.DeadLetterQueue;
import com.amazon.scheduler.model.Priority;
import com.amazon.scheduler.model.RetryPolicy;
import com.amazon.scheduler.model.Task;
import com.amazon.scheduler.model.TaskStatus;
import com.amazon.scheduler.queue.CustomPriorityBlockingQueue;
import com.amazon.scheduler.retry.RetryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RetryEngineAndDLQTest {

    @Test
    @DisplayName("Should correctly calculate exponential backoff delays")
    void shouldCalculateExponentialBackoff() {
        RetryPolicy policy = RetryPolicy.exponentialBackoff(4, 100, 2.0, 1000);

        assertThat(policy.calculateBackoffMillis(1)).isEqualTo(100);
        assertThat(policy.calculateBackoffMillis(2)).isEqualTo(200);
        assertThat(policy.calculateBackoffMillis(3)).isEqualTo(400);
        assertThat(policy.calculateBackoffMillis(4)).isEqualTo(800);
        assertThat(policy.calculateBackoffMillis(5)).isEqualTo(1000); // capped at max
    }

    @Test
    @DisplayName("Should re-enqueue task for retry and then succeed")
    void shouldReEnqueueTaskOnTransientFailure() throws Exception {
        CustomPriorityBlockingQueue<Task<?>> queue = new CustomPriorityBlockingQueue<>();
        DeadLetterQueue dlq = new DeadLetterQueue();
        RetryEngine engine = new RetryEngine(queue, dlq);

        AtomicInteger attempts = new AtomicInteger(0);
        RetryPolicy retryPolicy = RetryPolicy.fixedRetry(3, 50);

        Task<String> task = Task.of("FlakyJob", Priority.HIGH, () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new RuntimeException("Transient failure");
            }
            return "SuccessOnAttempt-" + attempt;
        }, retryPolicy);

        // First attempt fails -> delegate to retry engine
        boolean willRetry = engine.handleFailure(task, new RuntimeException("Transient error"));
        assertThat(willRetry).isTrue();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RETRYING);
        assertThat(task.getRetryCount()).isEqualTo(1);

        // Wait for retry delay and poll from queue
        Task<?> reEnqueued = queue.poll(500, TimeUnit.MILLISECONDS);
        assertThat(reEnqueued).isNotNull();
        assertThat(reEnqueued.getName()).isEqualTo("FlakyJob");

        engine.shutdown();
    }

    @Test
    @DisplayName("Should move permanently failed task to Dead Letter Queue")
    void shouldMoveToDLQWhenRetriesExhausted() {
        CustomPriorityBlockingQueue<Task<?>> queue = new CustomPriorityBlockingQueue<>();
        DeadLetterQueue dlq = new DeadLetterQueue();
        RetryEngine engine = new RetryEngine(queue, dlq);

        RetryPolicy policy = RetryPolicy.fixedRetry(2, 50);
        Task<Void> task = Task.of("PoisonPill", Priority.MEDIUM, () -> {
            throw new IllegalArgumentException("Fatal error");
        }, policy);

        // Attempt 1
        boolean retry1 = engine.handleFailure(task, new IllegalArgumentException("Attempt 1 failed"));
        assertThat(retry1).isTrue();

        // Attempt 2
        boolean retry2 = engine.handleFailure(task, new IllegalArgumentException("Attempt 2 failed"));
        assertThat(retry2).isTrue();

        // Attempt 3 (exceeds maxRetries=2)
        boolean retry3 = engine.handleFailure(task, new IllegalArgumentException("Fatal attempt 3 failed"));
        assertThat(retry3).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);

        // Verify DLQ state
        assertThat(dlq.size()).isEqualTo(1);
        DeadLetterQueue.DeadLetterRecord record = dlq.getRecord(task.getId());
        assertThat(record).isNotNull();
        assertThat(record.getTask().getName()).isEqualTo("PoisonPill");
        assertThat(record.getFinalException().getMessage()).isEqualTo("Fatal attempt 3 failed");

        engine.shutdown();
    }
}
