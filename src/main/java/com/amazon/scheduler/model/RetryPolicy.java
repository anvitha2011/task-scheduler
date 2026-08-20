package com.amazon.scheduler.model;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Defines retry policies and backoff computation for failed tasks.
 */
public class RetryPolicy {

    private final int maxRetries;
    private final long initialBackoffMillis;
    private final double backoffMultiplier;
    private final long maxBackoffMillis;
    private final Predicate<Throwable> retryCondition;

    public RetryPolicy(int maxRetries, long initialBackoffMillis, double backoffMultiplier, long maxBackoffMillis, Predicate<Throwable> retryCondition) {
        this.maxRetries = Math.max(0, maxRetries);
        this.initialBackoffMillis = Math.max(0, initialBackoffMillis);
        this.backoffMultiplier = Math.max(1.0, backoffMultiplier);
        this.maxBackoffMillis = Math.max(initialBackoffMillis, maxBackoffMillis);
        this.retryCondition = Objects.requireNonNullElse(retryCondition, t -> true);
    }

    public static RetryPolicy noRetry() {
        return new RetryPolicy(0, 0, 1.0, 0, t -> false);
    }

    public static RetryPolicy fixedRetry(int maxRetries, long delayMillis) {
        return new RetryPolicy(maxRetries, delayMillis, 1.0, delayMillis, t -> true);
    }

    public static RetryPolicy exponentialBackoff(int maxRetries, long initialDelayMillis, double multiplier, long maxDelayMillis) {
        return new RetryPolicy(maxRetries, initialDelayMillis, multiplier, maxDelayMillis, t -> true);
    }

    /**
     * Determines whether a retry attempt should be scheduled.
     *
     * @param retryAttempt The 1-based attempt index (1 for 1st retry, 2 for 2nd retry, etc.)
     * @param error The exception encountered
     */
    public boolean shouldRetry(int retryAttempt, Throwable error) {
        return retryAttempt <= maxRetries && retryCondition.test(error);
    }

    /**
     * Calculates exponential backoff delay for the given retry attempt (1-based index).
     */
    public long calculateBackoffMillis(int attempt) {
        if (attempt <= 0) return 0;
        long delay = (long) (initialBackoffMillis * Math.pow(backoffMultiplier, attempt - 1));
        return Math.min(delay, maxBackoffMillis);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getInitialBackoffMillis() {
        return initialBackoffMillis;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public long getMaxBackoffMillis() {
        return maxBackoffMillis;
    }
}
