package com.amazon.scheduler.web;

/**
 * Embedded Single-Page Application assets (HTML, CSS, JS) for zero-dependency standalone execution.
 */
public final class EmbeddedAssets {

    private EmbeddedAssets() {}

    public static final String INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Amazon Task Scheduler & Concurrency Engine</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600;700&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="/style.css">
</head>
<body>
  <div class="ambient-glow glow-1"></div>
  <div class="ambient-glow glow-2"></div>

  <!-- TOP HEADER -->
  <header class="app-header">
    <div class="header-left">
      <div class="logo-badge">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
          <line x1="8" y1="21" x2="16" y2="21"></line>
          <line x1="12" y1="17" x2="12" y2="21"></line>
        </svg>
      </div>
      <div class="logo-text">
        <h1>Task Scheduler & Job Queue</h1>
        <span class="subtext">Multi-Threaded Concurrency Engine (Java)</span>
      </div>
    </div>

    <div class="header-center">
      <div class="connection-pill" id="connPill">
        <span class="pulse-dot"></span>
        <span id="connStatusText">CONNECTING...</span>
      </div>
    </div>

    <div class="header-right">
      <button class="btn btn-outline" id="btnOpenSubmitModal">
        <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>
        Dispatch Task
      </button>
      <button class="btn btn-primary" id="btnRunScenarios">
        <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
        Scenario Deck
      </button>
      <button class="btn btn-icon" id="btnResetScheduler" title="Reset Scheduler & Metrics">
        <svg width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path><path d="M3 3v5h5"></path></svg>
      </button>
    </div>
  </header>

  <!-- MAIN CONTAINER -->
  <main class="dashboard-container">

    <!-- KPI METRICS RIBBON -->
    <section class="metrics-grid">
      <div class="metric-card">
        <div class="metric-header">
          <span class="metric-title">Submitted</span>
          <span class="metric-icon">📥</span>
        </div>
        <div class="metric-value" id="kpiSubmitted">0</div>
        <div class="metric-footer">Total queued jobs</div>
      </div>

      <div class="metric-card card-success">
        <div class="metric-header">
          <span class="metric-title">Completed</span>
          <span class="metric-icon">✅</span>
        </div>
        <div class="metric-value" id="kpiCompleted">0</div>
        <div class="metric-footer" id="kpiSuccessRate">100.0% Success</div>
      </div>

      <div class="metric-card card-active">
        <div class="metric-header">
          <span class="metric-title">Active Workers</span>
          <span class="metric-icon">⚡</span>
        </div>
        <div class="metric-value" id="kpiActiveWorkers">0 <span class="metric-sub">/ 4</span></div>
        <div class="metric-footer" id="kpiPoolUtilization">0% Utilization</div>
      </div>

      <div class="metric-card card-queue">
        <div class="metric-header">
          <span class="metric-title">In Queue</span>
          <span class="metric-icon">⏳</span>
        </div>
        <div class="metric-value" id="kpiQueueDepth">0</div>
        <div class="metric-footer">Heap ordering active</div>
      </div>

      <div class="metric-card card-retry">
        <div class="metric-header">
          <span class="metric-title">Retries Fired</span>
          <span class="metric-icon">🔄</span>
        </div>
        <div class="metric-value" id="kpiRetries">0</div>
        <div class="metric-footer">Exp. backoff timers</div>
      </div>

      <div class="metric-card card-dlq">
        <div class="metric-header">
          <span class="metric-title">DLQ (Poison Pills)</span>
          <span class="metric-icon">☠️</span>
        </div>
        <div class="metric-value" id="kpiDlq">0</div>
        <div class="metric-footer">Quarantined failures</div>
      </div>

      <div class="metric-card">
        <div class="metric-header">
          <span class="metric-title">Avg Latency</span>
          <span class="metric-icon">⏱️</span>
        </div>
        <div class="metric-value" id="kpiAvgExec">0.0<span class="metric-sub">ms</span></div>
        <div class="metric-footer">Execution duration</div>
      </div>
    </section>

    <!-- LIVE PIPELINE VISUALIZATION (3 COLUMNS) -->
    <section class="pipeline-grid">
      
      <!-- COLUMN 1: PRIORITY BLOCKING QUEUE -->
      <div class="pipeline-column">
        <div class="column-header">
          <div class="column-title-group">
            <span class="col-indicator col-queue-ind"></span>
            <h2>Priority Blocking Queue</h2>
          </div>
          <span class="badge badge-queue" id="queueBadge">0 Pending</span>
        </div>
        <div class="column-desc">
          <span>ReentrantLock + Conditions (notEmpty / notFull)</span>
        </div>

        <div class="pipeline-card-list" id="queueList">
          <div class="empty-state">
            <div class="empty-icon">⏳</div>
            <p>Queue is empty</p>
            <span>Ready to accept priority jobs</span>
          </div>
        </div>
      </div>

      <!-- COLUMN 2: WORKER THREAD POOL -->
      <div class="pipeline-column">
        <div class="column-header">
          <div class="column-title-group">
            <span class="col-indicator col-worker-ind"></span>
            <h2>Worker Thread Pool</h2>
          </div>
          <span class="badge badge-worker" id="workersBadge">4 Threads</span>
        </div>
        <div class="column-desc">
          <span>Consumer event loops with fault-isolation</span>
        </div>

        <div class="worker-grid" id="workerGrid">
          <!-- Worker cards rendered dynamically -->
        </div>
      </div>

      <!-- COLUMN 3: DEAD LETTER QUEUE (DLQ) -->
      <div class="pipeline-column">
        <div class="column-header">
          <div class="column-title-group">
            <span class="col-indicator col-dlq-ind"></span>
            <h2>Dead Letter Queue (DLQ)</h2>
          </div>
          <div class="dlq-actions">
            <button class="btn btn-xs btn-outline" id="btnReplayDlq" title="Replay all DLQ jobs back into Priority Queue">Replay All</button>
            <button class="btn btn-xs btn-outline" id="btnClearDlq" title="Clear DLQ">Clear</button>
          </div>
        </div>
        <div class="column-desc">
          <span>Poison pills quarantined after max retry exhaustion</span>
        </div>

        <div class="pipeline-card-list" id="dlqList">
          <div class="empty-state">
            <div class="empty-icon">🛡️</div>
            <p>DLQ is clean</p>
            <span>No unhandled poison pills detected</span>
          </div>
        </div>
      </div>

    </section>

    <!-- BOTTOM DECK: SCENARIO LAUNCHER & LIVE EVENT LOG -->
    <section class="bottom-deck">
      
      <!-- SCENARIO LAUNCHER CARDS -->
      <div class="deck-panel scenario-panel">
        <div class="panel-header">
          <div class="panel-title">
            <span class="panel-icon">⚡</span>
            <h3>Interactive Scenarios</h3>
          </div>
          <span class="subtext">1-Click Live Simulations</span>
        </div>

        <div class="scenario-buttons-grid">
          <button class="scenario-card" onclick="runScenario('1')">
            <div class="scen-icon scen-1">🎯</div>
            <div class="scen-info">
              <h4>1. Priority Precedence & FIFO Tie-Breaking</h4>
              <p>Submits mixed Low, Med, High & Critical tasks to prove priority reordering & starvation prevention.</p>
            </div>
          </button>

          <button class="scenario-card" onclick="runScenario('2')">
            <div class="scen-icon scen-2">🔄</div>
            <div class="scen-info">
              <h4>2. Exponential Backoff Retry Storm</h4>
              <p>Simulates transient 504 gateway failures that backoff (200ms -> 400ms -> 800ms) and recover.</p>
            </div>
          </button>

          <button class="scenario-card" onclick="runScenario('3')">
            <div class="scen-icon scen-3">☠️</div>
            <div class="scen-info">
              <h4>3. Poison Pill Isolation (DLQ)</h4>
              <p>Submits corrupted JSON payloads that exceed 2 retries and route directly to Dead Letter Queue.</p>
            </div>
          </button>

          <button class="scenario-card" onclick="runScenario('4')">
            <div class="scen-icon scen-4">🚀</div>
            <div class="scen-info">
              <h4>4. High-Load Concurrency Surge (24 Jobs)</h4>
              <p>Floods 24 concurrent mixed-priority jobs simultaneously to stress-test high throughput drainage.</p>
            </div>
          </button>
        </div>
      </div>

      <!-- REAL-TIME EVENT STREAM LOG -->
      <div class="deck-panel logs-panel">
        <div class="panel-header">
          <div class="panel-title">
            <span class="panel-icon">📜</span>
            <h3>Real-Time Event Stream</h3>
          </div>
          <div class="log-controls">
            <button class="btn btn-xs btn-outline" id="btnFilterAll" onclick="filterLogs('ALL')">All</button>
            <button class="btn btn-xs btn-outline" id="btnFilterSuccess" onclick="filterLogs('SUCCESS')">Success</button>
            <button class="btn btn-xs btn-outline" id="btnFilterRetry" onclick="filterLogs('RETRY')">Retries</button>
            <button class="btn btn-xs btn-outline" id="btnFilterDlq" onclick="filterLogs('FAILURE')">DLQ</button>
            <button class="btn btn-xs btn-icon" id="btnClearLogs" title="Clear Log Screen">
              <svg width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 6L6 18M6 6l12 12"></path></svg>
            </button>
          </div>
        </div>

        <div class="logs-container" id="logsContainer">
          <!-- Live log items rendered dynamically -->
        </div>
      </div>

    </section>

  </main>

  <!-- CUSTOM TASK DISPATCH MODAL -->
  <div class="modal-backdrop" id="submitModal">
    <div class="modal-card">
      <div class="modal-header">
        <div class="modal-title">
          <span class="modal-icon">🚀</span>
          <h3>Dispatch Custom Task</h3>
        </div>
        <button class="btn-close" id="btnCloseModal">&times;</button>
      </div>

      <form id="taskForm" onsubmit="handleTaskSubmit(event)">
        <div class="form-group">
          <label for="taskName">Task Name</label>
          <input type="text" id="taskName" required placeholder="e.g. PaymentGateway-Charge-902" value="Manual-Payment-Task">
        </div>

        <div class="form-row">
          <div class="form-group">
            <label for="taskPriority">Priority Tier</label>
            <select id="taskPriority">
              <option value="CRITICAL">🔥 CRITICAL (Highest)</option>
              <option value="HIGH">⚡ HIGH</option>
              <option value="MEDIUM" selected>🔹 MEDIUM</option>
              <option value="LOW">🌱 LOW (Lowest)</option>
            </select>
          </div>

          <div class="form-group">
            <label for="taskDuration">Work Duration (<span id="durVal">250</span>ms)</label>
            <input type="range" id="taskDuration" min="50" max="2000" step="50" value="250" oninput="document.getElementById('durVal').innerText = this.value">
          </div>
        </div>

        <div class="form-group">
          <label>Failure & Resilience Simulation</label>
          <div class="radio-cards">
            <label class="radio-card">
              <input type="radio" name="failMode" value="none" checked onchange="toggleRetryOptions()">
              <div class="radio-content">
                <span class="radio-title">Always Succeed</span>
                <span class="radio-desc">Normal healthy execution</span>
              </div>
            </label>

            <label class="radio-card">
              <input type="radio" name="failMode" value="transient" onchange="toggleRetryOptions()">
              <div class="radio-content">
                <span class="radio-title">Transient 504 Timeout</span>
                <span class="radio-desc">Fails 2 times then succeeds</span>
              </div>
            </label>

            <label class="radio-card">
              <input type="radio" name="failMode" value="permanent" onchange="toggleRetryOptions()">
              <div class="radio-content">
                <span class="radio-title">Poison Pill</span>
                <span class="radio-desc">Permanently fails -> DLQ</span>
              </div>
            </label>
          </div>
        </div>

        <div class="form-row" id="retryConfigRow">
          <div class="form-group">
            <label for="retryType">Retry Policy</label>
            <select id="retryType">
              <option value="exponential" selected>Exponential Backoff</option>
              <option value="fixed">Fixed Delay</option>
              <option value="none">No Retry</option>
            </select>
          </div>

          <div class="form-group">
            <label for="maxRetries">Max Retries</label>
            <input type="number" id="maxRetries" min="0" max="10" value="3">
          </div>

          <div class="form-group">
            <label for="backoffMs">Base Delay (ms)</label>
            <input type="number" id="backoffMs" min="50" max="5000" step="50" value="200">
          </div>
        </div>

        <div class="modal-footer">
          <button type="button" class="btn btn-outline" id="btnCancelModal">Cancel</button>
          <button type="submit" class="btn btn-primary">Dispatch Task to Queue</button>
        </div>
      </form>
    </div>
  </div>

  <div class="toast-container" id="toastContainer"></div>

  <script src="/app.js"></script>
</body>
</html>
""";

    public static final String STYLE_CSS = """
:root {
  --bg-base: #0a0e17;
  --bg-card: #111827;
  --bg-card-hover: #162032;
  --bg-card-glass: rgba(17, 24, 39, 0.75);
  --border-subtle: rgba(255, 255, 255, 0.08);
  --border-hover: rgba(255, 255, 255, 0.16);

  --text-primary: #f9fafb;
  --text-secondary: #94a3b8;
  --text-muted: #64748b;

  --color-critical: #ff2a5f;
  --color-critical-glow: rgba(255, 42, 95, 0.25);
  --color-high: #f59e0b;
  --color-high-glow: rgba(245, 158, 11, 0.25);
  --color-medium: #00b4d8;
  --color-medium-glow: rgba(0, 180, 216, 0.25);
  --color-low: #10b981;
  --color-low-glow: rgba(16, 185, 129, 0.25);

  --color-accent: #6366f1;
  --color-accent-glow: rgba(99, 102, 241, 0.35);

  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 14px;
  --radius-xl: 20px;

  --font-main: 'Inter', system-ui, -apple-system, sans-serif;
  --font-mono: 'JetBrains Mono', monospace;
}

* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

body {
  background-color: var(--bg-base);
  color: var(--text-primary);
  font-family: var(--font-main);
  min-height: 100vh;
  position: relative;
  overflow-x: hidden;
  -webkit-font-smoothing: antialiased;
}

/* AMBIENT GLOW EFFECTS */
.ambient-glow {
  position: fixed;
  width: 600px;
  height: 600px;
  border-radius: 50%;
  pointer-events: none;
  z-index: 0;
  filter: blur(140px);
  opacity: 0.15;
}
.glow-1 {
  top: -150px;
  left: -100px;
  background: radial-gradient(circle, var(--color-accent), transparent 70%);
}
.glow-2 {
  bottom: -200px;
  right: -100px;
  background: radial-gradient(circle, var(--color-medium), transparent 70%);
}

/* HEADER */
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 2rem;
  background: rgba(10, 14, 23, 0.85);
  backdrop-filter: blur(16px);
  border-bottom: 1px solid var(--border-subtle);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 0.85rem;
}

.logo-badge {
  width: 42px;
  height: 42px;
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, #6366f1, #3b82f6);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  box-shadow: 0 4px 16px var(--color-accent-glow);
}

.logo-text h1 {
  font-size: 1.15rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  background: linear-gradient(135deg, #ffffff, #cbd5e1);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.logo-text .subtext {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.connection-pill {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.9rem;
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid rgba(16, 185, 129, 0.3);
  border-radius: 9999px;
  font-size: 0.75rem;
  font-weight: 600;
  font-family: var(--font-mono);
  color: #34d399;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  background-color: #10b981;
  border-radius: 50%;
  box-shadow: 0 0 8px #10b981;
  animation: pulse-ring 1.8s infinite;
}

@keyframes pulse-ring {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); }
  70% { transform: scale(1.1); box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
}

.header-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

/* BUTTONS */
.btn {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.55rem 1rem;
  font-size: 0.85rem;
  font-weight: 600;
  border-radius: var(--radius-md);
  border: none;
  cursor: pointer;
  transition: all 0.2s ease;
  font-family: var(--font-main);
}

.btn-primary {
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: white;
  box-shadow: 0 4px 14px var(--color-accent-glow);
}
.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px var(--color-accent-glow);
  background: linear-gradient(135deg, #4f46e5, #4338ca);
}

.btn-outline {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
}
.btn-outline:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: var(--border-hover);
}

.btn-icon {
  width: 38px;
  height: 38px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all 0.2s ease;
}
.btn-icon:hover {
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.1);
}

.btn-xs {
  padding: 0.25rem 0.6rem;
  font-size: 0.75rem;
}

/* MAIN LAYOUT */
.dashboard-container {
  max-width: 1600px;
  margin: 0 auto;
  padding: 1.5rem 2rem 3rem;
  position: relative;
  z-index: 1;
}

/* METRICS RIBBON */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 1rem;
  margin-bottom: 1.75rem;
}

.metric-card {
  background: var(--bg-card-glass);
  backdrop-filter: blur(12px);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 1.15rem;
  transition: transform 0.2s ease, border-color 0.2s ease;
}
.metric-card:hover {
  transform: translateY(-2px);
  border-color: var(--border-hover);
}

.metric-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}
.metric-title {
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.metric-icon {
  font-size: 1rem;
}
.metric-value {
  font-size: 1.85rem;
  font-weight: 800;
  font-family: var(--font-mono);
  letter-spacing: -0.03em;
  color: var(--text-primary);
}
.metric-sub {
  font-size: 1rem;
  font-weight: 500;
  color: var(--text-muted);
}
.metric-footer {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.35rem;
}

.card-success .metric-value { color: #34d399; }
.card-active .metric-value { color: #38bdf8; }
.card-queue .metric-value { color: #fbbf24; }
.card-retry .metric-value { color: #c084fc; }
.card-dlq .metric-value { color: #fb7185; }

/* 3-COLUMN PIPELINE */
.pipeline-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 1.5rem;
  margin-bottom: 1.75rem;
}

@media (max-width: 1200px) {
  .pipeline-grid {
    grid-template-columns: 1fr;
  }
}

.pipeline-column {
  background: var(--bg-card-glass);
  backdrop-filter: blur(12px);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  padding: 1.35rem;
  display: flex;
  flex-direction: column;
  min-height: 480px;
}

.column-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.25rem;
}
.column-title-group {
  display: flex;
  align-items: center;
  gap: 0.65rem;
}
.col-indicator {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.col-queue-ind { background: var(--color-high); box-shadow: 0 0 8px var(--color-high-glow); }
.col-worker-ind { background: var(--color-medium); box-shadow: 0 0 8px var(--color-medium-glow); }
.col-dlq-ind { background: var(--color-critical); box-shadow: 0 0 8px var(--color-critical-glow); }

.column-header h2 {
  font-size: 1.05rem;
  font-weight: 700;
}
.column-desc {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-bottom: 1.15rem;
}

.badge {
  padding: 0.25rem 0.65rem;
  border-radius: 9999px;
  font-size: 0.72rem;
  font-weight: 700;
  font-family: var(--font-mono);
}
.badge-queue { background: rgba(245, 158, 11, 0.15); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.3); }
.badge-worker { background: rgba(0, 180, 216, 0.15); color: #38bdf8; border: 1px solid rgba(0, 180, 216, 0.3); }
.badge-critical { background: var(--color-critical-glow); color: var(--color-critical); border: 1px solid var(--color-critical); }
.badge-high { background: var(--color-high-glow); color: var(--color-high); border: 1px solid var(--color-high); }
.badge-medium { background: var(--color-medium-glow); color: var(--color-medium); border: 1px solid var(--color-medium); }
.badge-low { background: var(--color-low-glow); color: var(--color-low); border: 1px solid var(--color-low); }

.pipeline-card-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  overflow-y: auto;
  max-height: 420px;
  padding-right: 0.35rem;
}

/* CUSTOM SCROLLBAR */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.02);
}
::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.15);
  border-radius: 3px;
}
::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.25);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 3.5rem 1rem;
  color: var(--text-muted);
}
.empty-icon {
  font-size: 2.25rem;
  margin-bottom: 0.65rem;
  opacity: 0.6;
}
.empty-state p {
  font-weight: 600;
  font-size: 0.95rem;
  color: var(--text-secondary);
}
.empty-state span {
  font-size: 0.75rem;
  margin-top: 0.25rem;
}

/* TASK CARD (QUEUE & DLQ) */
.task-card {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 0.9rem 1rem;
  transition: all 0.2s ease;
  animation: slide-in 0.25s ease-out;
}
@keyframes slide-in {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}
.task-card:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: var(--border-hover);
}

.task-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.45rem;
}
.task-card-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
}
.task-seq {
  font-size: 0.7rem;
  font-family: var(--font-mono);
  color: var(--text-muted);
}
.task-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.72rem;
  color: var(--text-muted);
}

/* WORKER CARDS */
.worker-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0.85rem;
}

.worker-card {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 1rem;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
}

.worker-card.executing {
  border-color: rgba(0, 180, 216, 0.5);
  background: rgba(0, 180, 216, 0.06);
  box-shadow: 0 0 20px rgba(0, 180, 216, 0.15);
}

.worker-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.65rem;
}
.worker-name-group {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.worker-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #64748b;
}
.worker-card.executing .worker-dot {
  background: #00b4d8;
  box-shadow: 0 0 10px #00b4d8;
  animation: pulse-ring 1.5s infinite;
}
.worker-name {
  font-size: 0.85rem;
  font-weight: 700;
  font-family: var(--font-mono);
}

.worker-state-tag {
  font-size: 0.68rem;
  font-weight: 700;
  padding: 0.15rem 0.5rem;
  border-radius: 4px;
  font-family: var(--font-mono);
}
.state-idle { background: rgba(255, 255, 255, 0.05); color: var(--text-muted); }
.state-executing { background: rgba(0, 180, 216, 0.2); color: #38bdf8; }

.worker-task-info {
  font-size: 0.8rem;
  color: var(--text-secondary);
}
.worker-task-info strong {
  color: var(--text-primary);
}
.worker-progress-bar {
  height: 4px;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 2px;
  margin-top: 0.75rem;
  overflow: hidden;
  position: relative;
}
.worker-progress-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #00b4d8, #6366f1);
  width: 100%;
  animation: shimmer 1.2s infinite linear;
}
@keyframes shimmer {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}

/* DLQ CARD */
.dlq-card {
  background: rgba(255, 42, 95, 0.05);
  border: 1px solid rgba(255, 42, 95, 0.25);
  border-radius: var(--radius-md);
  padding: 0.9rem 1rem;
}
.dlq-error-msg {
  font-size: 0.72rem;
  color: #fb7185;
  font-family: var(--font-mono);
  background: rgba(0,0,0,0.3);
  padding: 0.35rem 0.5rem;
  border-radius: 4px;
  margin-top: 0.45rem;
  word-break: break-all;
}

/* BOTTOM DECK */
.bottom-deck {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
}
@media (max-width: 1000px) {
  .bottom-deck {
    grid-template-columns: 1fr;
  }
}

.deck-panel {
  background: var(--bg-card-glass);
  backdrop-filter: blur(12px);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  padding: 1.35rem;
  display: flex;
  flex-direction: column;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.15rem;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.panel-icon {
  font-size: 1.15rem;
}
.panel-header h3 {
  font-size: 1rem;
  font-weight: 700;
}

/* SCENARIO CARDS */
.scenario-buttons-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.85rem;
}
@media (max-width: 600px) {
  .scenario-buttons-grid { grid-template-columns: 1fr; }
}

.scenario-card {
  display: flex;
  gap: 0.85rem;
  align-items: flex-start;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 1rem;
  cursor: pointer;
  text-align: left;
  transition: all 0.2s ease;
  font-family: var(--font-main);
}
.scenario-card:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: var(--color-accent);
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0,0,0,0.3);
}

.scen-icon {
  font-size: 1.5rem;
  padding: 0.5rem;
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.05);
}
.scen-info h4 {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}
.scen-info p {
  font-size: 0.72rem;
  color: var(--text-secondary);
  line-height: 1.35;
}

/* LOGS TERMINAL */
.log-controls {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.logs-container {
  background: rgba(0, 0, 0, 0.4);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 0.85rem;
  font-family: var(--font-mono);
  font-size: 0.73rem;
  height: 280px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
}

.log-entry {
  display: flex;
  align-items: baseline;
  gap: 0.6rem;
  padding: 0.25rem 0.4rem;
  border-radius: 4px;
  line-height: 1.4;
  transition: background 0.15s ease;
}
.log-entry:hover {
  background: rgba(255, 255, 255, 0.04);
}
.log-time {
  color: var(--text-muted);
  font-size: 0.68rem;
  white-space: nowrap;
}
.log-tag {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 0.1rem 0.4rem;
  border-radius: 3px;
  text-transform: uppercase;
}
.tag-STARTED { background: rgba(0, 180, 216, 0.2); color: #38bdf8; }
.tag-SUCCESS { background: rgba(16, 185, 129, 0.2); color: #34d399; }
.tag-FAILURE { background: rgba(255, 42, 95, 0.25); color: #fb7185; }
.tag-RETRY { background: rgba(245, 158, 11, 0.2); color: #fbbf24; }
.tag-SUBMITTED { background: rgba(99, 102, 241, 0.2); color: #a5b4fc; }
.tag-SCENARIO { background: rgba(192, 132, 252, 0.2); color: #c084fc; }
.tag-DLQ_REPLAY { background: rgba(236, 72, 153, 0.2); color: #f472b6; }
.tag-RESET { background: rgba(148, 163, 184, 0.2); color: #cbd5e1; }

.log-msg {
  color: var(--text-primary);
  word-break: break-word;
}

/* MODAL */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(8px);
  z-index: 1000;
  display: none;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
}
.modal-backdrop.active {
  display: flex;
}

.modal-card {
  background: var(--bg-card);
  border: 1px solid var(--border-hover);
  border-radius: var(--radius-xl);
  width: 100%;
  max-width: 540px;
  box-shadow: 0 20px 40px rgba(0,0,0,0.6);
  padding: 1.75rem;
  animation: modal-pop 0.2s ease-out;
}
@keyframes modal-pop {
  from { opacity: 0; transform: scale(0.95); }
  to { opacity: 1; transform: scale(1); }
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}
.modal-title {
  display: flex;
  align-items: center;
  gap: 0.65rem;
}
.modal-title h3 {
  font-size: 1.15rem;
  font-weight: 700;
}
.btn-close {
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 1.5rem;
  cursor: pointer;
}
.btn-close:hover { color: var(--text-primary); }

.form-group {
  margin-bottom: 1.25rem;
}
.form-group label {
  display: block;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 0.45rem;
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}

input[type="text"], input[type="number"], select {
  width: 100%;
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 0.65rem 0.85rem;
  color: var(--text-primary);
  font-family: var(--font-main);
  font-size: 0.85rem;
}
input:focus, select:focus {
  outline: none;
  border-color: var(--color-accent);
}

.radio-cards {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0.6rem;
}
.radio-card {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all 0.15s ease;
}
.radio-card:hover {
  background: rgba(255, 255, 255, 0.05);
}
.radio-card input[type="radio"] {
  accent-color: var(--color-accent);
}
.radio-title {
  display: block;
  font-size: 0.85rem;
  font-weight: 600;
}
.radio-desc {
  display: block;
  font-size: 0.72rem;
  color: var(--text-muted);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  margin-top: 1.75rem;
}

/* TOAST NOTIFICATIONS */
.toast-container {
  position: fixed;
  bottom: 2rem;
  right: 2rem;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
.toast {
  background: #1e293b;
  border: 1px solid var(--border-hover);
  color: var(--text-primary);
  padding: 0.75rem 1.25rem;
  border-radius: var(--radius-md);
  font-size: 0.85rem;
  font-weight: 500;
  box-shadow: 0 8px 24px rgba(0,0,0,0.5);
  animation: toast-in 0.2s ease-out;
}
@keyframes toast-in {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}
""";

    public static final String APP_JS = """
// Global State & Controller for Task Scheduler UI
let currentFilter = 'ALL';
let allLogs = [];
let eventSource = null;

document.addEventListener('DOMContentLoaded', () => {
  setupEventListeners();
  initSSE();
  fetchStatus();
  setInterval(fetchStatus, 1500); // Heartbeat sync
});

function setupEventListeners() {
  // Modal Handlers
  const modal = document.getElementById('submitModal');
  document.getElementById('btnOpenSubmitModal').addEventListener('click', () => {
    modal.classList.add('active');
  });
  document.getElementById('btnCloseModal').addEventListener('click', () => {
    modal.classList.remove('active');
  });
  document.getElementById('btnCancelModal').addEventListener('click', () => {
    modal.classList.remove('active');
  });

  // Action Buttons
  document.getElementById('btnResetScheduler').addEventListener('click', async () => {
    if (confirm('Reset task scheduler, pool, and metrics to fresh state?')) {
      await fetch('/api/scheduler/reset', { method: 'POST' });
      showToast('Task Scheduler reset successfully');
      fetchStatus();
    }
  });

  document.getElementById('btnReplayDlq').addEventListener('click', async () => {
    const res = await fetch('/api/dlq/replay', { method: 'POST' });
    const data = await res.json();
    showToast(`Replayed ${data.replayed} tasks from DLQ back to Queue`);
    fetchStatus();
  });

  document.getElementById('btnClearDlq').addEventListener('click', async () => {
    await fetch('/api/dlq/clear', { method: 'POST' });
    showToast('Dead Letter Queue cleared');
    fetchStatus();
  });

  document.getElementById('btnClearLogs').addEventListener('click', () => {
    allLogs = [];
    renderLogs();
  });

  document.getElementById('btnRunScenarios').addEventListener('click', () => {
    document.querySelector('.scenario-panel').scrollIntoView({ behavior: 'smooth' });
  });
}

function initSSE() {
  const connStatus = document.getElementById('connStatusText');
  const connPill = document.getElementById('connPill');

  if (window.EventSource) {
    eventSource = new EventSource('/api/stream');

    eventSource.onopen = () => {
      connStatus.innerText = 'SSE STREAM LIVE';
      connPill.style.borderColor = 'rgba(16, 185, 129, 0.4)';
    };

    eventSource.addEventListener('CONNECTED', (e) => {
      connStatus.innerText = 'ONLINE (SSE)';
    });

    eventSource.addEventListener('STARTED', (e) => { fetchStatus(); });
    eventSource.addEventListener('SUCCESS', (e) => { fetchStatus(); });
    eventSource.addEventListener('FAILURE', (e) => { fetchStatus(); });
    eventSource.addEventListener('RETRY', (e) => { fetchStatus(); });
    eventSource.addEventListener('SUBMITTED', (e) => { fetchStatus(); });

    eventSource.onerror = () => {
      connStatus.innerText = 'POLLING ACTIVE';
      connPill.style.borderColor = 'rgba(245, 158, 11, 0.4)';
    };
  } else {
    connStatus.innerText = 'POLLING ACTIVE';
  }
}

async function fetchStatus() {
  try {
    const res = await fetch('/api/status');
    if (!res.ok) return;
    const data = await res.json();
    updateUI(data);
  } catch (err) {
    console.error('Error fetching scheduler status:', err);
  }
}

function updateUI(data) {
  // Update KPI Ribbon
  const m = data.metrics || {};
  document.getElementById('kpiSubmitted').innerText = m.tasksSubmitted || 0;
  document.getElementById('kpiCompleted').innerText = m.tasksCompleted || 0;
  document.getElementById('kpiActiveWorkers').innerHTML = `${m.activeWorkers || 0} <span class="metric-sub">/ ${m.totalWorkers || 4}</span>`;
  document.getElementById('kpiQueueDepth').innerText = m.queueSize || 0;
  document.getElementById('kpiRetries').innerText = m.tasksRetried || 0;
  document.getElementById('kpiDlq').innerText = m.dlqSize || 0;
  document.getElementById('kpiAvgExec').innerHTML = `${m.avgExecTimeMs || '0.0'}<span class="metric-sub">ms</span>`;
  document.getElementById('kpiSuccessRate').innerText = `${m.successRate || '100.0'}% Success`;

  const util = m.totalWorkers ? Math.round((m.activeWorkers / m.totalWorkers) * 100) : 0;
  document.getElementById('kpiPoolUtilization').innerText = `${util}% Utilization`;

  document.getElementById('queueBadge').innerText = `${m.queueSize || 0} Pending`;
  document.getElementById('workersBadge').innerText = `${m.totalWorkers || 4} Threads`;

  // Render Queue Cards
  renderQueue(data.queue || []);

  // Render Worker Cards
  renderWorkers(data.workers || []);

  // Render DLQ Cards
  renderDLQ(data.dlq || []);

  // Sync Logs
  if (data.logs && data.logs.length > 0) {
    allLogs = data.logs;
    renderLogs();
  }
}

function renderQueue(queue) {
  const container = document.getElementById('queueList');
  if (queue.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">⏳</div>
        <p>Queue is empty</p>
        <span>Ready to accept priority jobs</span>
      </div>`;
    return;
  }

  container.innerHTML = queue.map(task => `
    <div class="task-card">
      <div class="task-card-header">
        <span class="task-card-title" title="${escapeHtml(task.name)}">${escapeHtml(task.name)}</span>
        <span class="badge badge-${task.priority.toLowerCase()}">${task.priority}</span>
      </div>
      <div class="task-card-footer">
        <span class="task-seq">Seq: #${task.sequenceNumber}</span>
        <span>Retries: ${task.retryCount}</span>
      </div>
    </div>
  `).join('');
}

function renderWorkers(workers) {
  const container = document.getElementById('workerGrid');
  container.innerHTML = workers.map(w => {
    const isExec = w.state === 'EXECUTING';
    const task = w.currentTask;

    return `
      <div class="worker-card ${isExec ? 'executing' : ''}">
        <div class="worker-card-top">
          <div class="worker-name-group">
            <span class="worker-dot"></span>
            <span class="worker-name">${escapeHtml(w.name)}</span>
          </div>
          <span class="worker-state-tag ${isExec ? 'state-executing' : 'state-idle'}">${w.state}</span>
        </div>

        <div class="worker-task-info">
          ${isExec && task ? `
            <div>Working on: <strong>${escapeHtml(task.name)}</strong></div>
            <div style="display:flex; justify-content:space-between; align-items:center; margin-top:4px;">
              <span class="badge badge-${task.priority.toLowerCase()}">${task.priority}</span>
              <span style="font-family:var(--font-mono); font-size:0.75rem; color:#38bdf8;">${task.elapsedMs || 0}ms</span>
            </div>
            <div class="worker-progress-bar">
              <div class="worker-progress-bar-fill"></div>
            </div>
          ` : `
            <span style="color:var(--text-muted); font-size:0.75rem;">Waiting for highest-priority task...</span>
          `}
        </div>
      </div>
    `;
  }).join('');
}

function renderDLQ(dlq) {
  const container = document.getElementById('dlqList');
  if (dlq.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">🛡️</div>
        <p>DLQ is clean</p>
        <span>No unhandled poison pills detected</span>
      </div>`;
    return;
  }

  container.innerHTML = dlq.map(item => `
    <div class="dlq-card task-card">
      <div class="task-card-header">
        <span class="task-card-title" title="${escapeHtml(item.taskName)}">${escapeHtml(item.taskName)}</span>
        <span class="badge badge-critical">${item.priority}</span>
      </div>
      <div class="task-card-footer">
        <span>Attempts: ${item.totalAttempts}</span>
        <span style="font-family:var(--font-mono);">${new Date(item.recordedAt).toLocaleTimeString()}</span>
      </div>
      <div class="dlq-error-msg">${escapeHtml(item.error)}</div>
    </div>
  `).join('');
}

function renderLogs() {
  const container = document.getElementById('logsContainer');
  const filtered = currentFilter === 'ALL'
    ? allLogs
    : allLogs.filter(l => l.type === currentFilter || (currentFilter === 'FAILURE' && (l.type === 'FAILURE' || l.type === 'DLQ_REPLAY')));

  if (filtered.length === 0) {
    container.innerHTML = `<div style="text-align:center; padding:2rem; color:var(--text-muted);">No log entries for filter: ${currentFilter}</div>`;
    return;
  }

  container.innerHTML = filtered.map(log => `
    <div class="log-entry">
      <span class="log-time">${new Date(log.timestamp).toLocaleTimeString()}</span>
      <span class="log-tag tag-${log.type}">${log.type}</span>
      <span class="log-msg">
        <strong>[${escapeHtml(log.workerName)}]</strong> ${escapeHtml(log.message)}
      </span>
    </div>
  `).join('');
}

function filterLogs(filterType) {
  currentFilter = filterType;
  renderLogs();
}

async function runScenario(scenarioId) {
  showToast(`Running Scenario #${scenarioId}...`);
  await fetch('/api/scenarios/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scenario: scenarioId })
  });
  fetchStatus();
}

async function handleTaskSubmit(event) {
  event.preventDefault();
  const name = document.getElementById('taskName').value;
  const priority = document.getElementById('taskPriority').value;
  const durationMs = document.getElementById('taskDuration').value;
  const failMode = document.querySelector('input[name="failMode"]:checked').value;
  const retryType = document.getElementById('retryType').value;
  const maxRetries = document.getElementById('maxRetries').value;
  const backoffMs = document.getElementById('backoffMs').value;

  const payload = {
    name,
    priority,
    durationMs,
    failMode,
    retryType,
    maxRetries,
    backoffMs
  };

  const res = await fetch('/api/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });

  if (res.ok) {
    document.getElementById('submitModal').classList.remove('active');
    showToast(`Task '${name}' enqueued successfully!`);
    fetchStatus();
  }
}

function toggleRetryOptions() {
  const failMode = document.querySelector('input[name="failMode"]:checked').value;
  const row = document.getElementById('retryConfigRow');
  if (failMode === 'none') {
    row.style.opacity = '0.5';
  } else {
    row.style.opacity = '1.0';
  }
}

function showToast(msg) {
  const container = document.getElementById('toastContainer');
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.innerText = msg;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    setTimeout(() => toast.remove(), 300);
  }, 2500);
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
}
""";
}
