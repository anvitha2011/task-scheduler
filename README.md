# Multi-Threaded Task Scheduler & Job Queue (Java)

[![Java 17](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![JUnit 5](https://img.shields.io/badge/JUnit_5-Testing-25A162?style=for-the-badge&logo=junit5&logoColor=white)](https://junit.org/junit5/)
[![Build Status](https://img.shields.io/badge/Build-Passing-10B981?style=for-the-badge)](https://github.com/anvitha2011/task-scheduler)
[![GitHub Pages](https://img.shields.io/badge/Live_Demo-Interactive_UI-0284C7?style=for-the-badge&logo=github&logoColor=white)](https://anvitha2011.github.io/task-scheduler/)

> 🚀 **Live Interactive Web Demo:** [https://anvitha2011.github.io/task-scheduler/](https://anvitha2011.github.io/task-scheduler/)  
> 📦 **GitHub Repository:** [https://github.com/anvitha2011/task-scheduler](https://github.com/anvitha2011/task-scheduler)

---

## 📌 Project Overview

A production-grade, concurrent Task Scheduler and Job Queue implemented from scratch in **Java 17**. Built to demonstrate core **Operating Systems**, **Multithreading**, and **Low-Level Concurrency Primitives** without relying on high-level framework abstractions.

It solves the classic **Producer-Consumer problem** with thread synchronization, prioritzes tasks using an $O(\log n)$ min-heap, prevents starvation with atomic sequence tie-breaking, gracefully recovers from transient failures via **Exponential Backoff**, and isolates poison-pill payloads into a **Dead Letter Queue (DLQ)**.

---

## 🎯 System Architecture

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

## 🚀 Key Concurrency Features

1. **Custom Priority Blocking Queue:**
   * Thread safety guaranteed via `ReentrantLock`.
   * Precise monitor coordination using dual `Condition` variables (`notEmpty` and `notFull`).
   * 4-tier priority levels (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`).
   * Atomic sequence stamping (`AtomicLong`) prevents thread starvation across equal priorities.

2. **Managed Worker Thread Pool:**
   * Pre-allocated worker event loops with clean thread exception isolation.
   * Full lifecycle orchestration (`shutdown()`, `shutdownNow()`, `awaitTermination()`).

3. **Exponential Backoff & Resilience:**
   * Non-blocking delay scheduler: $\text{Delay} = \text{InitialDelay} \times 2^{(\text{attempt} - 1)}$ with random jitter.
   * Prevents worker threads from blocking or sleeping while waiting for retry windows.
   * Dead Letter Queue (DLQ) captures and isolates unrecoverable tasks for manual replay.

4. **Lock-Free Observability:**
   * Uses `LongAdder` and `AtomicInteger` for zero lock-contention telemetry.
   * Real-time metrics: Throughput, Queue Depth, Thread Pool Utilization, Average Latency, Success Rate.

---

## 🛠️ How to Run Locally

### 1. Launch the Interactive Web Dashboard (Local GUI)
```bash
mvn compile exec:java
```
👉 Open your browser at: **`http://localhost:8080`** to access the live dashboard, trigger priority scenarios, inspect thread pools, and monitor real-time Server-Sent Events (SSE).

### 2. Run Interactive CLI Simulation
```bash
mvn compile exec:java -Dexec.mainClass=com.amazon.scheduler.demo.TaskSchedulerDemo
```

### 3. Run Automated Concurrency Tests
```bash
mvn clean test
```
*Executes all 10 JUnit 5 concurrency, race condition, and stress test suites.*

---

## 🧪 Concurrency Test Suite

| Test Suite | Coverage |
|---|---|
| `CustomPriorityBlockingQueueTest` | Multi-threaded Producer-Consumer synchronization, Priority min-heap order, FIFO tie-breaking |
| `RetryEngineAndDLQTest` | Exponential backoff delay calculation, Retry exhaustion, DLQ quarantine & replay |
| `TaskSchedulerConcurrencyTest` | 500+ concurrent task dispatch stress test, Thread-safe shutdown barriers |

---

## 👤 Author
**Anvitha Rallabhandi**  
* GitHub: [github.com/anvitha2011](https://github.com/anvitha2011)  
* LinkedIn: [linkedin.com/in/anvitha-rallabhandi](https://linkedin.com/in/anvitha-rallabhandi)
