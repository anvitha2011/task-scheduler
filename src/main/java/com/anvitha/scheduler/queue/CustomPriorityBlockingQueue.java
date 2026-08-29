package com.anvitha.scheduler.queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import com.anvitha.scheduler.model.Task;

/**
 * A thread-safe, blocking Priority Queue implemented from first principles.
 *
 * <p><strong>ANVITHA / FAANG Interview OS Concepts:</strong>
 * <ul>
 *   <li><strong>Mutual Exclusion:</strong> Utilizes {@link ReentrantLock} to serialize access to the internal min-heap.</li>
 *   <li><strong>Producer-Consumer Synchronization:</strong> Two {@link Condition} variables:
 *       <ul>
 *         <li>{@code notEmpty}: Worker/Consumer threads block ({@code await()}) when the queue is empty until a producer calls {@code signal()}.</li>
 *         <li>{@code notFull}: Producer threads block when capacity is reached until a consumer removes a task.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Sequence Stamping (Fairness):</strong> An {@link AtomicLong} sequence number stamps incoming tasks
 *       to guarantee deterministic FIFO tie-breaking among equal-priority jobs.</li>
 * </ul>
 * </p>
 *
 * @param <E> The element type, must implement {@link Comparable}.
 */
public class CustomPriorityBlockingQueue<E extends Comparable<E>> {

    private final PriorityQueue<E> heap;
    private final int capacity;
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    /**
     * Creates an unbounded priority blocking queue.
     */
    public CustomPriorityBlockingQueue() {
        this(Integer.MAX_VALUE, false);
    }

    /**
     * Creates a bounded priority blocking queue with specified capacity and lock fairness.
     *
     * @param capacity Maximum number of elements allowed in the queue.
     * @param fair True if thread acquisition should follow FIFO fairness.
     */
    public CustomPriorityBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.heap = new PriorityQueue<>();
        this.lock = new ReentrantLock(fair);
        this.notEmpty = this.lock.newCondition();
        this.notFull = this.lock.newCondition();
    }

    /**
     * Inserts an element into the priority queue, blocking if the queue is full.
     *
     * @param item Element to insert.
     * @throws InterruptedException if interrupted while waiting.
     */
    public void put(E item) throws InterruptedException {
        if (item == null) throw new NullPointerException("Null elements not permitted");

        lock.lockInterruptibly();
        try {
            while (heap.size() >= capacity) {
                notFull.await();
            }

            // Stamp task with monotonically increasing sequence number if it is a Task
            if (item instanceof Task<?> task) {
                task.setSequenceNumber(sequenceCounter.incrementAndGet());
            }

            heap.offer(item);
            // Signal one waiting consumer thread that an element is ready
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts an element with a timeout if the queue is full.
     *
     * @return true if added, false if timeout elapsed.
     */
    public boolean offer(E item, long timeout, TimeUnit unit) throws InterruptedException {
        if (item == null) throw new NullPointerException("Null elements not permitted");

        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (heap.size() >= capacity) {
                if (nanos <= 0L) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }

            if (item instanceof Task<?> task) {
                task.setSequenceNumber(sequenceCounter.incrementAndGet());
            }

            heap.offer(item);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non-blocking insert.
     *
     * @return true if inserted, false if queue is full.
     */
    public boolean offer(E item) {
        if (item == null) throw new NullPointerException("Null elements not permitted");

        lock.lock();
        try {
            if (heap.size() >= capacity) {
                return false;
            }
            if (item instanceof Task<?> task) {
                task.setSequenceNumber(sequenceCounter.incrementAndGet());
            }
            heap.offer(item);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the highest-priority element, blocking if the queue is empty.
     *
     * @return The highest priority element.
     * @throws InterruptedException if interrupted while waiting.
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (heap.isEmpty()) {
                notEmpty.await();
            }
            E item = heap.poll();
            // Signal one waiting producer that a slot has freed up
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the highest-priority element, waiting up to timeout if empty.
     *
     * @return The element, or null if timeout elapsed.
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (heap.isEmpty()) {
                if (nanos <= 0L) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            E item = heap.poll();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non-blocking retrieval without removing.
     */
    public E peek() {
        lock.lock();
        try {
            return heap.peek();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns current number of elements in the queue.
     */
    public int size() {
        lock.lock();
        try {
            return heap.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return heap.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public int getCapacity() {
        return capacity;
    }

    /**
     * Returns a snapshot of queued elements (for debugging / monitoring).
     */
    public List<E> snapshot() {
        lock.lock();
        try {
            return new ArrayList<>(heap);
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            heap.clear();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
