package com.amazon.scheduler.pool;

/**
 * Worker thread execution states.
 */
public enum WorkerState {
    IDLE,
    EXECUTING,
    STOPPED
}
