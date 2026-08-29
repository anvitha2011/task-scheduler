package com.anvitha.scheduler.dlq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.anvitha.scheduler.model.Task;

/**
 * Dead Letter Queue (DLQ) for storing tasks that permanently failed
 * after exhausting all retry attempts.
 *
 * <p><strong>ANVITHA Interview Point:</strong>
 * In production systems (e.g., AWS SQS DLQ, ANVITHA Step Functions),
 * poison-pill messages must never block the main processing queue.
 * Isolating dead tasks in a DLQ enables alarm triggering, operational
 * inspection, and manual replay.</p>
 */
public class DeadLetterQueue {

    public static class DeadLetterRecord {
        private final Task<?> task;
        private final Throwable finalException;
        private final long recordedAt;
        private final int totalAttempts;

        public DeadLetterRecord(Task<?> task, Throwable finalException, int totalAttempts) {
            this.task = task;
            this.finalException = finalException;
            this.recordedAt = System.currentTimeMillis();
            this.totalAttempts = totalAttempts;
        }

        public Task<?> getTask() {
            return task;
        }

        public Throwable getFinalException() {
            return finalException;
        }

        public long getRecordedAt() {
            return recordedAt;
        }

        public int getTotalAttempts() {
            return totalAttempts;
        }

        @Override
        public String toString() {
            return String.format("DLQ-Record[taskId='%s', taskName='%s', attempts=%d, error='%s', time=%d]",
                    task.getId(), task.getName(), totalAttempts,
                    finalException != null ? finalException.getMessage() : "Unknown", recordedAt);
        }
    }

    private final List<DeadLetterRecord> records = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, DeadLetterRecord> indexByTaskId = new ConcurrentHashMap<>();

    /**
     * Appends a permanently failed task to the DLQ.
     */
    public void recordFailure(Task<?> task, Throwable finalException) {
        DeadLetterRecord record = new DeadLetterRecord(task, finalException, task.getRetryCount());
        records.add(record);
        indexByTaskId.put(task.getId(), record);
    }

    public List<DeadLetterRecord> getRecords() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    public DeadLetterRecord getRecord(String taskId) {
        return indexByTaskId.get(taskId);
    }

    public int size() {
        return records.size();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public void clear() {
        records.clear();
        indexByTaskId.clear();
    }
}
