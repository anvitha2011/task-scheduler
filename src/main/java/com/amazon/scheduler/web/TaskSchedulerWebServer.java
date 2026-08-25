package com.amazon.scheduler.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.amazon.scheduler.dlq.DeadLetterQueue;
import com.amazon.scheduler.metrics.SchedulerMetrics;
import com.amazon.scheduler.model.Priority;
import com.amazon.scheduler.model.RetryPolicy;
import com.amazon.scheduler.model.Task;
import com.amazon.scheduler.model.TaskStatus;
import com.amazon.scheduler.pool.TaskEventListener;
import com.amazon.scheduler.pool.WorkerThread;
import com.amazon.scheduler.service.TaskSchedulerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Embedded HTTP Web Server providing a real-time Interactive Web Dashboard
 * and REST/SSE API for the Task Scheduler and Job Queue on http://localhost:8080.
 */
public class TaskSchedulerWebServer {

    public static final int DEFAULT_PORT = 8080;
    private static final int MAX_LOGS = 150;

    public static class EventLog {
        public final long timestamp;
        public final String type;
        public final String workerName;
        public final String taskId;
        public final String taskName;
        public final String priority;
        public final String message;
        public final long durationMs;
        public final int retryAttempt;

        public EventLog(String type, String workerName, String taskId, String taskName,
                        String priority, String message, long durationMs, int retryAttempt) {
            this.timestamp = System.currentTimeMillis();
            this.type = type;
            this.workerName = workerName;
            this.taskId = taskId;
            this.taskName = taskName;
            this.priority = priority;
            this.message = message;
            this.durationMs = durationMs;
            this.retryAttempt = retryAttempt;
        }
    }

    private final int port;
    private HttpServer server;
    private TaskSchedulerService scheduler;
    private final List<EventLog> eventLogs = new CopyOnWriteArrayList<>();
    private final List<HttpExchange> sseClients = new CopyOnWriteArrayList<>();
    private final AtomicInteger clientCounter = new AtomicInteger(0);

    public TaskSchedulerWebServer(int port) {
        this.port = port;
        initScheduler();
    }

    private synchronized void initScheduler() {
        if (this.scheduler != null) {
            try {
                this.scheduler.close();
            } catch (Exception ignored) {}
        }

        TaskEventListener listener = new TaskEventListener() {
            @Override
            public void onTaskStarted(String workerName, Task<?> task) {
                addLog(new EventLog("STARTED", workerName, task.getId(), task.getName(),
                        task.getPriority().name(), "Worker picked up task from queue", 0, task.getRetryCount()));
                broadcastSSE("STARTED", taskJson(task, workerName));
            }

            @Override
            public void onTaskSuccess(String workerName, Task<?> task, Object result, long durationMs) {
                addLog(new EventLog("SUCCESS", workerName, task.getId(), task.getName(),
                        task.getPriority().name(), "Completed successfully in " + durationMs + "ms. Result: " + result, durationMs, task.getRetryCount()));
                broadcastSSE("SUCCESS", taskJson(task, workerName));
            }

            @Override
            public void onTaskFailure(String workerName, Task<?> task, Throwable error, long durationMs) {
                addLog(new EventLog("FAILURE", workerName, task.getId(), task.getName(),
                        task.getPriority().name(), "Permanent failure (retries exhausted) -> Quarantined in DLQ: " + error.getMessage(), durationMs, task.getRetryCount()));
                broadcastSSE("FAILURE", taskJson(task, workerName));
            }

            @Override
            public void onTaskRetry(String workerName, Task<?> task, int attemptNumber, Throwable error) {
                addLog(new EventLog("RETRY", workerName, task.getId(), task.getName(),
                        task.getPriority().name(), "Attempt #" + attemptNumber + " failed (" + error.getMessage() + "). Scheduling backoff retry...", 0, attemptNumber));
                broadcastSSE("RETRY", taskJson(task, workerName));
            }
        };

        this.scheduler = new TaskSchedulerService(4, 300, false, listener);
    }

    private void addLog(EventLog log) {
        eventLogs.add(0, log);
        while (eventLogs.size() > MAX_LOGS) {
            eventLogs.remove(eventLogs.size() - 1);
        }
    }

    public void start() throws IOException {
        int actualPort = this.port;
        try {
            server = HttpServer.create(new InetSocketAddress(actualPort), 0);
        } catch (IOException e) {
            actualPort = this.port + 1;
            server = HttpServer.create(new InetSocketAddress(actualPort), 0);
        }

        server.setExecutor(Executors.newCachedThreadPool());

        // Routes
        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/stream", new SseStreamHandler());
        server.createContext("/api/tasks", new TaskSubmitHandler());
        server.createContext("/api/scenarios/run", new ScenarioHandler());
        server.createContext("/api/dlq/replay", new DlqReplayHandler());
        server.createContext("/api/dlq/clear", new DlqClearHandler());
        server.createContext("/api/scheduler/reset", new ResetHandler());

        server.start();

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  🚀 AMAZON TASK SCHEDULER & JOB QUEUE WEB SERVER STARTED");
        System.out.println("  🌐 Dashboard URL: http://localhost:" + actualPort);
        System.out.println("  📊 Real-Time Metrics & SSE Event Stream Active");
        System.out.println("=".repeat(80) + "\n");
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private void broadcastSSE(String eventType, String dataJson) {
        String payload = "event: " + eventType + "\ndata: " + dataJson + "\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        List<HttpExchange> toRemove = new ArrayList<>();
        for (HttpExchange client : sseClients) {
            try {
                OutputStream os = client.getResponseBody();
                os.write(bytes);
                os.flush();
            } catch (Exception e) {
                toRemove.add(client);
            }
        }
        sseClients.removeAll(toRemove);
    }

    private String taskJson(Task<?> task, String workerName) {
        return "{" +
                "\"id\":\"" + escape(task.getId()) + "\"," +
                "\"name\":\"" + escape(task.getName()) + "\"," +
                "\"priority\":\"" + task.getPriority().name() + "\"," +
                "\"status\":\"" + task.getStatus().name() + "\"," +
                "\"worker\":\"" + escape(workerName != null ? workerName : "") + "\"," +
                "\"retryCount\":" + task.getRetryCount() + "," +
                "\"sequenceNumber\":" + task.getSequenceNumber() +
                "}";
    }

    // =========================================================================
    // HTTP Handlers
    // =========================================================================

    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                byte[] content = loadStaticResource("static/index.html");
                if (content != null) {
                    sendByteResponse(exchange, 200, "text/html; charset=UTF-8", content);
                } else {
                    sendResponse(exchange, 404, "text/plain", "index.html not found");
                }
            } else if (path.equals("/style.css")) {
                byte[] content = loadStaticResource("static/style.css");
                if (content != null) {
                    sendByteResponse(exchange, 200, "text/css; charset=UTF-8", content);
                } else {
                    sendResponse(exchange, 404, "text/plain", "style.css not found");
                }
            } else if (path.equals("/app.js")) {
                byte[] content = loadStaticResource("static/app.js");
                if (content != null) {
                    sendByteResponse(exchange, 200, "application/javascript; charset=UTF-8", content);
                } else {
                    sendResponse(exchange, 404, "text/plain", "app.js not found");
                }
            } else {
                sendResponse(exchange, 404, "text/plain", "Not Found");
            }
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCors(exchange);
                return;
            }

            SchedulerMetrics metrics = scheduler.getMetrics();
            List<WorkerThread> workers = scheduler.getWorkers();
            List<Task<?>> queued = scheduler.getQueuedTasks();
            List<DeadLetterQueue.DeadLetterRecord> dlqRecords = scheduler.getDeadLetterQueue().getRecords();

            StringBuilder sb = new StringBuilder();
            sb.append("{");

            // Metrics
            sb.append("\"metrics\":{");
            sb.append("\"tasksSubmitted\":").append(metrics.getTasksSubmitted()).append(",");
            sb.append("\"tasksCompleted\":").append(metrics.getTasksCompleted()).append(",");
            sb.append("\"tasksFailed\":").append(metrics.getTasksFailed()).append(",");
            sb.append("\"tasksRetried\":").append(metrics.getTasksRetried()).append(",");
            sb.append("\"activeWorkers\":").append(metrics.getActiveWorkers()).append(",");
            sb.append("\"totalWorkers\":").append(scheduler.getThreadPoolSize()).append(",");
            sb.append("\"queueSize\":").append(scheduler.getQueueSize()).append(",");
            sb.append("\"dlqSize\":").append(dlqRecords.size()).append(",");
            sb.append("\"avgExecTimeMs\":").append(String.format("%.2f", metrics.getAverageExecutionTimeMs())).append(",");
            sb.append("\"successRate\":").append(String.format("%.1f", metrics.getSuccessRatePercentage()));
            sb.append("},");

            // Workers
            sb.append("\"workers\":[");
            for (int i = 0; i < workers.size(); i++) {
                WorkerThread w = workers.get(i);
                if (i > 0) sb.append(",");
                sb.append("{");
                sb.append("\"name\":\"").append(escape(w.getName())).append("\",");
                sb.append("\"state\":\"").append(w.getWorkerState().name()).append("\",");
                sb.append("\"isWorking\":").append(w.isWorking()).append(",");
                Task<?> current = w.getCurrentTask();
                if (current != null) {
                    sb.append("\"currentTask\":{");
                    sb.append("\"id\":\"").append(escape(current.getId())).append("\",");
                    sb.append("\"name\":\"").append(escape(current.getName())).append("\",");
                    sb.append("\"priority\":\"").append(current.getPriority().name()).append("\",");
                    sb.append("\"retryCount\":").append(current.getRetryCount()).append(",");
                    long elapsed = w.getTaskStartTime() > 0 ? (System.currentTimeMillis() - w.getTaskStartTime()) : 0;
                    sb.append("\"elapsedMs\":").append(elapsed);
                    sb.append("}");
                } else {
                    sb.append("\"currentTask\":null");
                }
                sb.append("}");
            }
            sb.append("],");

            // Queue
            sb.append("\"queue\":[");
            for (int i = 0; i < queued.size(); i++) {
                Task<?> t = queued.get(i);
                if (i > 0) sb.append(",");
                sb.append("{");
                sb.append("\"id\":\"").append(escape(t.getId())).append("\",");
                sb.append("\"name\":\"").append(escape(t.getName())).append("\",");
                sb.append("\"priority\":\"").append(t.getPriority().name()).append("\",");
                sb.append("\"status\":\"").append(t.getStatus().name()).append("\",");
                sb.append("\"sequenceNumber\":").append(t.getSequenceNumber()).append(",");
                sb.append("\"retryCount\":").append(t.getRetryCount()).append(",");
                sb.append("\"submissionTime\":").append(t.getSubmissionTime());
                sb.append("}");
            }
            sb.append("],");

            // DLQ
            sb.append("\"dlq\":[");
            for (int i = 0; i < dlqRecords.size(); i++) {
                DeadLetterQueue.DeadLetterRecord rec = dlqRecords.get(i);
                if (i > 0) sb.append(",");
                sb.append("{");
                sb.append("\"taskId\":\"").append(escape(rec.getTask().getId())).append("\",");
                sb.append("\"taskName\":\"").append(escape(rec.getTask().getName())).append("\",");
                sb.append("\"priority\":\"").append(rec.getTask().getPriority().name()).append("\",");
                sb.append("\"totalAttempts\":").append(rec.getTotalAttempts()).append(",");
                sb.append("\"recordedAt\":").append(rec.getRecordedAt()).append(",");
                sb.append("\"error\":\"").append(escape(rec.getFinalException() != null ? rec.getFinalException().getMessage() : "Unknown")).append("\"");
                sb.append("}");
            }
            sb.append("],");

            // Logs
            sb.append("\"logs\":[");
            for (int i = 0; i < eventLogs.size(); i++) {
                EventLog l = eventLogs.get(i);
                if (i > 0) sb.append(",");
                sb.append("{");
                sb.append("\"timestamp\":").append(l.timestamp).append(",");
                sb.append("\"type\":\"").append(l.type).append("\",");
                sb.append("\"workerName\":\"").append(escape(l.workerName)).append("\",");
                sb.append("\"taskId\":\"").append(escape(l.taskId)).append("\",");
                sb.append("\"taskName\":\"").append(escape(l.taskName)).append("\",");
                sb.append("\"priority\":\"").append(l.priority).append("\",");
                sb.append("\"message\":\"").append(escape(l.message)).append("\",");
                sb.append("\"durationMs\":").append(l.durationMs).append(",");
                sb.append("\"retryAttempt\":").append(l.retryAttempt);
                sb.append("}");
            }
            sb.append("]");

            sb.append("}");

            sendResponse(exchange, 200, "application/json", sb.toString());
        }
    }

    private class SseStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);

            sseClients.add(exchange);

            // Send initial connection packet
            String init = "event: CONNECTED\ndata: {\"status\":\"connected\",\"clientId\":" + clientCounter.incrementAndGet() + "}\n\n";
            OutputStream os = exchange.getResponseBody();
            os.write(init.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private class TaskSubmitHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCors(exchange);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            String body = readBody(exchange);
            Map<String, String> params = parseFormOrJson(body);

            String name = params.getOrDefault("name", "Custom-Task-" + System.currentTimeMillis() % 1000);
            String priorityStr = params.getOrDefault("priority", "MEDIUM").toUpperCase();
            Priority priority;
            try {
                priority = Priority.valueOf(priorityStr);
            } catch (Exception e) {
                priority = Priority.MEDIUM;
            }

            int durationMs = parseInt(params.get("durationMs"), 200);
            String failMode = params.getOrDefault("failMode", "none");
            int transientFailCount = parseInt(params.get("transientFailCount"), 2);
            int maxRetries = parseInt(params.get("maxRetries"), 3);
            int backoffMs = parseInt(params.get("backoffMs"), 200);
            String retryType = params.getOrDefault("retryType", "exponential");

            RetryPolicy retryPolicy;
            if ("none".equalsIgnoreCase(retryType) || maxRetries <= 0) {
                retryPolicy = RetryPolicy.noRetry();
            } else if ("fixed".equalsIgnoreCase(retryType)) {
                retryPolicy = RetryPolicy.fixedRetry(maxRetries, backoffMs);
            } else {
                retryPolicy = RetryPolicy.exponentialBackoff(maxRetries, backoffMs, 2.0, 5000);
            }

            AtomicInteger failCounter = new AtomicInteger(0);

            Task<String> task = Task.of(name, priority, () -> {
                Thread.sleep(durationMs);
                if ("permanent".equalsIgnoreCase(failMode)) {
                    throw new RuntimeException("Fatal Error: Poison Pill Payload Rejected");
                } else if ("transient".equalsIgnoreCase(failMode)) {
                    int count = failCounter.incrementAndGet();
                    if (count <= transientFailCount) {
                        throw new RuntimeException("Transient Gateway Timeout (504) attempt #" + count);
                    }
                }
                return "Completed: " + name + " (" + durationMs + "ms)";
            }, retryPolicy);

            scheduler.submit(task);

            addLog(new EventLog("SUBMITTED", "Client", task.getId(), task.getName(),
                    task.getPriority().name(), "Enqueued custom task (Duration: " + durationMs + "ms, Priority: " + priority + ")", 0, 0));
            broadcastSSE("SUBMITTED", taskJson(task, "Client"));

            sendResponse(exchange, 200, "application/json", "{\"status\":\"success\",\"taskId\":\"" + task.getId() + "\"}");
        }
    }

    private class ScenarioHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCors(exchange);
                return;
            }

            String body = readBody(exchange);
            Map<String, String> params = parseFormOrJson(body);
            String scenario = params.getOrDefault("scenario", "1");

            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    runScenario(scenario);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            sendResponse(exchange, 200, "application/json", "{\"status\":\"started\",\"scenario\":\"" + scenario + "\"}");
        }
    }

    private void runScenario(String scenarioId) throws Exception {
        switch (scenarioId) {
            case "1" -> {
                // Priority Inversion & FIFO Tie-Breaking
                addLog(new EventLog("SCENARIO", "System", "SCEN-1", "Priority Order Test", "INFO",
                        "Submitting 6 tasks: 2 LOW, 1 MEDIUM, 1 HIGH, 2 CRITICAL simultaneously to test priority re-ordering and sequence tie-breaking", 0, 0));

                scheduler.submit("Low-Priority-OrderSync-1", Priority.LOW, () -> { Thread.sleep(250); return "Order-101-Synced"; });
                scheduler.submit("Low-Priority-OrderSync-2", Priority.LOW, () -> { Thread.sleep(250); return "Order-102-Synced"; });
                Thread.sleep(30);
                scheduler.submit("Medium-Priority-InventoryCheck", Priority.MEDIUM, () -> { Thread.sleep(200); return "Inventory-Checked"; });
                Thread.sleep(30);
                scheduler.submit("High-Priority-PaymentCapture", Priority.HIGH, () -> { Thread.sleep(200); return "Charged-$199"; });
                scheduler.submit("Critical-Priority-FraudAlert", Priority.CRITICAL, () -> { Thread.sleep(150); return "Account-Secured"; });
                scheduler.submit("Critical-Priority-PrimeDelivery", Priority.CRITICAL, () -> { Thread.sleep(150); return "Dispatched-Express"; });
            }
            case "2" -> {
                // Exponential Backoff Retry Storm
                addLog(new EventLog("SCENARIO", "System", "SCEN-2", "Backoff Retry Storm", "INFO",
                        "Submitting 3 tasks with transient network timeouts (504) with exponential backoff (150ms -> 300ms -> 600ms)", 0, 0));

                AtomicInteger c1 = new AtomicInteger(0);
                RetryPolicy exp1 = RetryPolicy.exponentialBackoff(3, 200, 2.0, 2000);
                scheduler.submit("PaymentGateway-Auth", Priority.HIGH, () -> {
                    Thread.sleep(150);
                    if (c1.incrementAndGet() < 3) throw new RuntimeException("Stripe API Timeout (504) attempt #" + c1.get());
                    return "Auth-Token-XYZ789";
                }, exp1);

                AtomicInteger c2 = new AtomicInteger(0);
                scheduler.submit("ShippingRate-Calculator", Priority.MEDIUM, () -> {
                    Thread.sleep(120);
                    if (c2.incrementAndGet() < 2) throw new RuntimeException("FedEx Carrier Rate Service Unavailable (503)");
                    return "Rates-Calculated-$12.50";
                }, exp1);
            }
            case "3" -> {
                // Poison Pill DLQ Isolation
                addLog(new EventLog("SCENARIO", "System", "SCEN-3", "Poison Pill Isolation", "INFO",
                        "Submitting poison pill task 'Corrupted-Order-Payload' with 2 max retries -> will route to DLQ", 0, 0));

                RetryPolicy fixed2 = RetryPolicy.fixedRetry(2, 200);
                scheduler.submit("Corrupted-Order-Payload", Priority.CRITICAL, () -> {
                    Thread.sleep(100);
                    throw new IllegalArgumentException("Fatal: Malformed JSON at byte 4092: unexpected EOF");
                }, fixed2);

                scheduler.submit("Invalid-CreditCard-Checksum", Priority.HIGH, () -> {
                    Thread.sleep(100);
                    throw new SecurityException("Fatal: Luhn checksum verification failed for customer #4418");
                }, fixed2);
            }
            case "4" -> {
                // High Load Concurrency Surge
                addLog(new EventLog("SCENARIO", "System", "SCEN-4", "High Load Surge", "INFO",
                        "Flooding 24 concurrent mixed priority tasks across CRITICAL, HIGH, MEDIUM, LOW tiers", 0, 0));

                Priority[] priorities = { Priority.CRITICAL, Priority.HIGH, Priority.MEDIUM, Priority.LOW };
                for (int i = 1; i <= 24; i++) {
                    final int idx = i;
                    Priority p = priorities[i % priorities.length];
                    scheduler.submit("Surge-Job-" + p.name() + "-" + idx, p, () -> {
                        Thread.sleep(100 + (idx % 4) * 50);
                        return "Surge-Job-" + idx + "-Done";
                    });
                    Thread.sleep(15);
                }
            }
        }
    }

    private class DlqReplayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCors(exchange);
                return;
            }

            int replayed = scheduler.replayAllDlq();
            addLog(new EventLog("DLQ_REPLAY", "System", "DLQ", "All Tasks", "HIGH",
                    "Replayed " + replayed + " poison pill tasks from DLQ back into the priority queue", 0, 0));

            sendResponse(exchange, 200, "application/json", "{\"status\":\"success\",\"replayed\":" + replayed + "}");
        }
    }

    private class DlqClearHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCors(exchange);
                return;
            }

            scheduler.getDeadLetterQueue().clear();
            addLog(new EventLog("DLQ_CLEAR", "System", "DLQ", "All Tasks", "INFO", "Dead Letter Queue cleared by operator", 0, 0));
            sendResponse(exchange, 200, "application/json", "{\"status\":\"cleared\"}");
        }
    }

    private class ResetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCors(exchange);
                return;
            }

            initScheduler();
            eventLogs.clear();
            addLog(new EventLog("RESET", "System", "SYSTEM", "Scheduler Reset", "INFO",
                    "Task Scheduler reset to clean state with 4 fresh worker threads", 0, 0));

            sendResponse(exchange, 200, "application/json", "{\"status\":\"reset\"}");
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private void sendCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(204, -1);
    }

    private void sendResponse(HttpExchange exchange, int code, String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        sendByteResponse(exchange, code, contentType, bytes);
    }

    private void sendByteResponse(HttpExchange exchange, int code, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private byte[] loadStaticResource(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private Map<String, String> parseFormOrJson(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.trim().isEmpty()) return map;

        String trimmed = body.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            // Simple JSON parser for basic key-values
            String inner = trimmed.substring(1, trimmed.length() - 1);
            String[] pairs = inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String k = kv[0].trim().replace("\"", "");
                    String v = kv[1].trim().replace("\"", "");
                    map.put(k, v);
                }
            }
        } else {
            // URL Encoded Form
            String[] pairs = trimmed.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    try {
                        String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                        String v = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                        map.put(k, v);
                    } catch (Exception ignored) {}
                }
            }
        }
        return map;
    }

    private int parseInt(String val, int defaultVal) {
        if (val == null) return defaultVal;
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (Exception ignored) {}
        } else if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (Exception ignored) {}
        }

        try {
            TaskSchedulerWebServer webServer = new TaskSchedulerWebServer(port);
            webServer.start();

            // Keep server running
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("Fatal: Failed to start Task Scheduler Web Server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
