package com.amazon.scheduler.model;

/**
 * Defines priority levels for scheduled tasks.
 * Higher numeric weight corresponds to higher priority.
 */
public enum Priority {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int level;

    Priority(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
