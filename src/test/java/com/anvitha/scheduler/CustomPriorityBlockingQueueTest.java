package com.anvitha.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.anvitha.scheduler.model.Priority;
import com.anvitha.scheduler.model.Task;
import com.anvitha.scheduler.queue.CustomPriorityBlockingQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CustomPriorityBlockingQueueTest {

    @Test
    @DisplayName("Should extract tasks in strict priority order (Critical > High > Medium > Low)")
    void shouldExtractInPriorityOrder() throws InterruptedException {
        CustomPriorityBlockingQueue<Task<?>> queue = new CustomPriorityBlockingQueue<>();

        queue.put(Task.of("Low-1", Priority.LOW, () -> null));
        queue.put(Task.of("Medium-1", Priority.MEDIUM, () -> null));
        queue.put(Task.of("Critical-1", Priority.CRITICAL, () -> null));
        queue.put(Task.of("High-1", Priority.HIGH, () -> null));

        assertThat(queue.take().getName()).isEqualTo("Critical-1");
        assertThat(queue.take().getName()).isEqualTo("High-1");
        assertThat(queue.take().getName()).isEqualTo("Medium-1");
        assertThat(queue.take().getName()).isEqualTo("Low-1");
    }

    @Test
    @DisplayName("Should maintain FIFO tie-breaking for equal priority tasks (Starvation prevention)")
    void shouldMaintainFIFOTieBreakingForEqualPriority() throws InterruptedException {
        CustomPriorityBlockingQueue<Task<?>> queue = new CustomPriorityBlockingQueue<>();

        queue.put(Task.of("High-Task-A", Priority.HIGH, () -> null));
        queue.put(Task.of("High-Task-B", Priority.HIGH, () -> null));
        queue.put(Task.of("High-Task-C", Priority.HIGH, () -> null));

        assertThat(queue.take().getName()).isEqualTo("High-Task-A");
        assertThat(queue.take().getName()).isEqualTo("High-Task-B");
        assertThat(queue.take().getName()).isEqualTo("High-Task-C");
    }

    @Test
    @DisplayName("Multi-threaded Producer-Consumer concurrency test with no loss")
    void shouldHandleConcurrentProducersAndConsumers() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 100;
        int totalExpected = numProducers * itemsPerProducer;

        CustomPriorityBlockingQueue<Task<?>> queue = new CustomPriorityBlockingQueue<>(50, false);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numProducers + numConsumers);
        List<String> consumedItems = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(numProducers + numConsumers);

        // Producers
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        queue.put(Task.of("P" + producerId + "-Item-" + i, Priority.MEDIUM, () -> null));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Consumers
        for (int c = 0; c < numConsumers; c++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        Task<?> task = queue.take();
                        consumedItems.add(task.getName());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(completed).isTrue();
        assertThat(consumedItems).hasSize(totalExpected);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Should block and timeout on empty queue with poll()")
    void shouldTimeoutWhenEmpty() throws InterruptedException {
        CustomPriorityBlockingQueue<Task<?>> queue = new CustomPriorityBlockingQueue<>();
        long start = System.currentTimeMillis();
        Task<?> task = queue.poll(150, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(task).isNull();
        assertThat(elapsed).isGreaterThanOrEqualTo(140);
    }
}
