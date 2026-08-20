# Multi-Threaded Task Scheduler & Job Queue (Java)

A production-grade, lightweight Task Scheduler and Job Queue implemented from scratch in Java to demonstrate core Operating Systems and Concurrency primitives for technical interviews.

---

## 🎯 Architecture Overview

```
                      +-----------------------------+
                      |   Client / Producer Threads |
                      +--------------+--------------+
                                     | submit(Task)
                                     v
                 +---------------------------------------+
                 |    CustomPriorityBlockingQueue        |
                 |  - ReentrantLock (Mutual Exclusion)   |
                 |  - Conditions: notEmpty & notFull     |
                 |  - Atomic FIFO Tie-Breaking Counter   |
                 +-------------------+-------------------+
                                     |
                                     | take() / poll()
                                     v
                   +-----------------------------------+
                   |     CustomThreadPool (Workers)    |
                   |   [Worker-1] [Worker-2] [Worker-3]|
                   +-----------------+-----------------+
                                     |
                                     v (Execute Task)
                           /-------------------\
                          /      Success?       \
                         /-----------------------\
                       YES /                   \ NO
                          /                     \
                         v                       v
               +------------------+     +-------------------+
               | Completed Tasks  |     |   RetryEngine     |
               | & Lock-Free      |     | - Exp. Backoff    |
               | SchedulerMetrics |     | - Re-enqueue timer|
               +------------------+     +---------+---------+
                                                  |
                                                  | (Retries Exhausted)
                                                  v
                                        +-------------------+
                                        | DeadLetterQueue   |
                                        | (DLQ Isolation)   |
                                        +-------------------+
```

---

## 🚀 Key Features

1. **Custom Priority Blocking Queue:**
   - Mutual exclusion via `ReentrantLock`.
   - Monitor coordination via dual `Condition` variables (`notEmpty`, `notFull`).
   - Priority levels: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`.
   - Atomic sequence stamping prevents starvation among equal-priority jobs.

2. **Managed Worker Thread Pool:**
   - Dedicated `WorkerThread` event loop with exception isolation and interrupt handling.
   - Clean lifecycle coordination: `shutdown()`, `shutdownNow()`, and `awaitTermination()`.

3. **Resilience & Retry Engine:**
   - Exponential backoff with configurable jitter and max delays.
   - Non-blocking re-enqueue avoids tying up worker threads during backoff.
   - Dead Letter Queue (DLQ) captures poison pills for inspection.

4. **Lock-Free Observability & Telemetry:**
   - High-throughput counters using `LongAdder` and `AtomicInteger`.
   - Tracks submission count, completion rate, failure rate, active threads, and average latency.

---

## 🛠️ How to Run

### 1. Launch the Interactive Web Dashboard (Localhost GUI)
```powershell
mvn compile exec:java
```
👉 Open your browser at: **`http://localhost:8080`** to interact with the live priority queue, worker threads, exponential backoff retries, dead letter queue (DLQ), real-time SSE metrics, and scenario simulations.

### 2. Run the Interactive Console Demo
```powershell
mvn compile exec:java -Dexec.mainClass=com.amazon.scheduler.demo.TaskSchedulerDemo
```

### 3. Build and Run Automated Tests
```powershell
mvn clean test
```
