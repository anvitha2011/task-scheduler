package com.anvitha.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import com.anvitha.scheduler.model.Priority;
import com.anvitha.scheduler.model.RetryPolicy;
import com.anvitha.scheduler.service.TaskFuture;
import com.anvitha.scheduler.service.TaskSchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TaskSchedulerConcurrencyTest {

    @Test
    @DisplayName("Stress Test: 10 producer threads submitting 500 tasks concurrently without race conditions")
    void shouldHandleMassiveConcurrentSubmissions() throws Exception {
        int numProducers = 10;
        int tasksPerProducer = 50;
        int totalTasks = numProducers * tasksPerProducer;
        int workerThreads = 4;

        try (TaskSchedulerService scheduler = new TaskSchedulerService(workerThreads)) {
            ExecutorService producerPool = Executors.newFixedThreadPool(numProducers);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(numProducers);

            List<TaskFuture<Integer>> allFutures = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger processedCounter = new AtomicInteger(0);

            for (int p = 0; p < numProducers; p++) {
                final int producerId = p;
                producerPool.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < tasksPerProducer; i++) {
                            final int val = i;
                            Priority prio = (i % 4 == 0) ? Priority.CRITICAL :
                                    (i % 3 == 0) ? Priority.HIGH :
                                            (i % 2 == 0) ? Priority.MEDIUM : Priority.LOW;

                            TaskFuture<Integer> f = scheduler.submit(
                                    "Job-P" + producerId + "-T" + i,
                                    prio,
                                    () -> {
                                        processedCounter.incrementAndGet();
                                        return producerId * 1000 + val;
                                    }
                            );
                            allFutures.add(f);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            // Start all producers simultaneously
            startLatch.countDown();
            finishLatch.await(5, TimeUnit.SECONDS);
            producerPool.shutdown();

            // Await all task futures
            for (TaskFuture<Integer> f : allFutures) {
                Integer result = f.get(10, TimeUnit.SECONDS);
                assertThat(result).isNotNull();
            }

            scheduler.shutdown();
            boolean cleanlyTerminated = scheduler.awaitTermination(5, TimeUnit.SECONDS);

            assertThat(cleanlyTerminated).isTrue();
            assertThat(processedCounter.get()).isEqualTo(totalTasks);
            assertThat(scheduler.getMetrics().getTasksSubmitted()).isEqualTo(totalTasks);
            assertThat(scheduler.getMetrics().getTasksCompleted()).isEqualTo(totalTasks);
            assertThat(scheduler.getMetrics().getTasksFailed()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("TaskFuture timeout test should throw TimeoutException")
    void shouldTimeoutOnSlowTaskFuture() {
        try (TaskSchedulerService scheduler = new TaskSchedulerService(2)) {
            TaskFuture<String> slowFuture = scheduler.submit("SlowTask", Priority.HIGH, () -> {
                Thread.sleep(1000);
                return "Done";
            });

            assertThatThrownBy(() -> slowFuture.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        }
    }

    @Test
    @DisplayName("TaskFuture should properly propagate exceptions to caller on get()")
    void shouldPropagateExceptionOnTaskFutureGet() {
        try (TaskSchedulerService scheduler = new TaskSchedulerService(2)) {
            TaskFuture<Void> failingFuture = scheduler.submit("FailingTask", Priority.MEDIUM, () -> {
                throw new IllegalStateException("Database connection refused");
            }, RetryPolicy.noRetry());

            assertThatThrownBy(() -> failingFuture.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Database connection refused");
        }
    }
}
