// Global State & Controller for Task Scheduler UI
let currentFilter = 'ALL';
let allLogs = [];
let eventSource = null;
let isSimMode = false;
let simSeq = 1;
let fastPollInterval = null;

// Permanent Render Cloud Backend URL
const DEFAULT_BACKEND_URL = 'https://task-scheduler-backend-31lo.onrender.com';

function getApiBase() {
  const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
  if (isLocal) return '';
  return DEFAULT_BACKEND_URL;
}

let simState = {
  metrics: {
    tasksSubmitted: 0,
    tasksCompleted: 0,
    tasksFailed: 0,
    tasksRetried: 0,
    activeWorkers: 0,
    totalWorkers: 4,
    queueSize: 0,
    dlqSize: 0,
    avgExecTimeMs: '0.0',
    successRate: '100.0'
  },
  workers: [
    { name: 'worker-1', state: 'IDLE', active: false, currentTask: null, task: null },
    { name: 'worker-2', state: 'IDLE', active: false, currentTask: null, task: null },
    { name: 'worker-3', state: 'IDLE', active: false, currentTask: null, task: null },
    { name: 'worker-4', state: 'IDLE', active: false, currentTask: null, task: null }
  ],
  queue: [],
  dlq: [],
  logs: []
};

document.addEventListener('DOMContentLoaded', () => {
  setupEventListeners();
  addSimLog('SUCCESS', 'System', 'init-001', 'TaskSchedulerEngine', 'CRITICAL', 'Task Scheduler active. Connecting to Java 17 backend...', 0, 0);
  updateUI(simState);
  initSSE();
  fetchStatus();
  setInterval(fetchStatus, 400); // 400ms high-responsiveness heartbeat sync
});

function setupEventListeners() {
  // Modal Handlers (Task Submit)
  const submitModal = document.getElementById('submitModal');
  document.getElementById('btnOpenSubmitModal').addEventListener('click', () => {
    submitModal.classList.add('active');
  });
  document.getElementById('btnCloseModal').addEventListener('click', () => {
    submitModal.classList.remove('active');
  });
  document.getElementById('btnCancelModal').addEventListener('click', () => {
    submitModal.classList.remove('active');
  });

  // Action Buttons
  document.getElementById('btnResetScheduler').addEventListener('click', async () => {
    if (confirm('Reset task scheduler, pool, and metrics to fresh state?')) {
      if (isSimMode) {
        simState.metrics = {
          tasksSubmitted: 0,
          tasksCompleted: 0,
          tasksFailed: 0,
          tasksRetried: 0,
          activeWorkers: 0,
          totalWorkers: 4,
          queueSize: 0,
          dlqSize: 0,
          avgExecTimeMs: '0.0',
          successRate: '100.0'
        };
        simState.queue = [];
        simState.dlq = [];
        simState.logs = [];
        allLogs = [];
        simState.workers.forEach(w => {
          w.state = 'IDLE';
          w.active = false;
          w.currentTask = null;
          w.task = null;
        });
        showToast('Task Scheduler reset successfully');
        addSimLog('SUCCESS', 'System', 'reset', 'TaskSchedulerEngine', 'LOW', 'Task Scheduler state and metrics reset.', 0, 0);
        updateUI(simState);
        return;
      }
      try {
        await fetch(getApiBase() + '/api/scheduler/reset', { method: 'POST' });
        showToast('Task Scheduler reset successfully');
        triggerFastPolling(3000);
      } catch (err) {
        showToast('Reset request sent to backend');
      }
    }
  });

  document.getElementById('btnReplayDlq').addEventListener('click', async () => {
    if (isSimMode) {
      const count = simState.dlq.length;
      if (count === 0) {
        showToast('Dead Letter Queue is empty');
        return;
      }
      simState.dlq.forEach(d => {
        const t = d.task || {
          id: d.taskId,
          name: d.taskName,
          priority: d.priority,
          durationMs: 600,
          failMode: 'none',
          maxRetries: 0,
          backoffMs: 200
        };
        t.retryCount = 0;
        t.failMode = 'none'; // reset so replayed task executes cleanly
        t.status = 'QUEUED';
        t.scheduledTime = Date.now();
        t.sequenceNumber = simSeq++;
        simState.queue.push(t);
        addSimLog('RETRY', 'Admin-DLQ', t.id, t.name, t.priority, 'Replaying quarantined task from DLQ back to Priority Queue', 0, 0);
      });
      simState.dlq = [];
      showToast(`Replayed ${count} tasks from DLQ back to Priority Queue`);
      updateUI(simState);
      return;
    }
    try {
      const res = await fetch(getApiBase() + '/api/dlq/replay', { method: 'POST' });
      const data = await res.json();
      showToast(`Replayed ${data.replayed} tasks from DLQ back to Queue`);
      triggerFastPolling(4000);
    } catch (e) {
      showToast('DLQ replay triggered on backend');
    }
  });

  document.getElementById('btnClearDlq').addEventListener('click', async () => {
    if (isSimMode) {
      simState.dlq = [];
      showToast('Dead Letter Queue cleared');
      updateUI(simState);
      return;
    }
    try {
      await fetch(getApiBase() + '/api/dlq/clear', { method: 'POST' });
      showToast('Dead Letter Queue cleared');
      fetchStatus();
    } catch (e) {
      showToast('DLQ cleared on backend');
    }
  });

  document.getElementById('btnClearLogs').addEventListener('click', () => {
    allLogs = [];
    if (isSimMode) simState.logs = [];
    renderLogs();
  });

  document.getElementById('btnRunScenarios').addEventListener('click', () => {
    document.querySelector('.scenario-panel').scrollIntoView({ behavior: 'smooth' });
  });
}

function triggerFastPolling(durationMs) {
  if (fastPollInterval) clearInterval(fastPollInterval);
  fetchStatus();
  fastPollInterval = setInterval(fetchStatus, 200);
  setTimeout(() => {
    if (fastPollInterval) clearInterval(fastPollInterval);
  }, durationMs || 6000);
}

function initSSE() {
  const connStatus = document.getElementById('connStatusText');
  const apiBase = getApiBase();

  if (eventSource) {
    try { eventSource.close(); } catch (e) {}
  }

  if (window.EventSource) {
    try {
      eventSource = new EventSource(apiBase + '/api/stream');

      eventSource.onopen = () => {
        connStatus.innerText = 'Backend: Connected';
        isSimMode = false;
      };

      eventSource.addEventListener('CONNECTED', () => {
        connStatus.innerText = 'Backend: Connected';
        isSimMode = false;
      });

      eventSource.addEventListener('STARTED', () => { fetchStatus(); });
      eventSource.addEventListener('SUCCESS', () => { fetchStatus(); });
      eventSource.addEventListener('FAILURE', () => { fetchStatus(); });
      eventSource.addEventListener('RETRY', () => { fetchStatus(); });
      eventSource.addEventListener('SUBMITTED', () => { fetchStatus(); });

      eventSource.onerror = () => {
        if (!isSimMode) initSimEngine();
      };
    } catch (e) {
      if (!isSimMode) initSimEngine();
    }
  } else {
    initSimEngine();
  }
}

function initSimEngine() {
  if (isSimMode) return;
  isSimMode = true;
  const connStatus = document.getElementById('connStatusText');
  connStatus.innerText = 'Simulation Active';
  setInterval(simWorkerTick, 250);
}

function simWorkerTick() {
  if (!isSimMode) return;
  const now = Date.now();

  simState.workers.forEach(w => {
    if (!w.active && simState.queue.length > 0) {
      // Min-heap priority order (CRITICAL > HIGH > MEDIUM > LOW) + sequence tie-breaking
      simState.queue.sort((a, b) => {
        const pOrder = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
        const pd = (pOrder[b.priority] || 1) - (pOrder[a.priority] || 1);
        if (pd !== 0) return pd;
        return (a.sequenceNumber || 0) - (b.sequenceNumber || 0);
      });

      const readyIdx = simState.queue.findIndex(t => (t.scheduledTime || 0) <= now);
      if (readyIdx !== -1) {
        const task = simState.queue.splice(readyIdx, 1)[0];
        w.active = true;
        w.state = 'EXECUTING';
        w.currentTask = task;
        w.task = task;
        task.status = 'RUNNING';
        simState.metrics.activeWorkers = simState.workers.filter(x => x.active).length;
        simState.metrics.queueSize = simState.queue.length;

        addSimLog('STARTED', w.name, task.id, task.name, task.priority, 'Worker dequeued task from priority queue', 0, task.retryCount);
        updateUI(simState);

        setTimeout(() => {
          simFinishTask(w, task);
        }, task.durationMs || 800);
      }
    }
  });

  simState.metrics.queueSize = simState.queue.length;
  simState.metrics.dlqSize = simState.dlq.length;
  updateUI(simState);
}

function simFinishTask(worker, task) {
  const duration = task.durationMs || 800;
  let isFailure = false;

  if (task.failMode === 'transient' && task.retryCount < 2) isFailure = true;
  if (task.failMode === 'always') isFailure = true;

  if (isFailure) {
    const errorMsg = task.failMode === 'always'
      ? 'Fatal: Poison pill payload rejected'
      : 'Transient Network Gateway Timeout (504)';

    if (task.retryCount < (task.maxRetries || 3)) {
      task.retryCount++;
      const delay = (task.backoffMs || 800) * Math.pow(2, task.retryCount - 1);
      task.scheduledTime = Date.now() + delay;
      task.status = 'RETRYING';
      simState.queue.push(task);
      simState.metrics.tasksRetried++;
      addSimLog('RETRY', worker.name, task.id, task.name, task.priority, `Task failed: ${errorMsg} (Backoff retry #${task.retryCount} in ${delay}ms)`, duration, task.retryCount);
    } else {
      task.status = 'FAILED';
      simState.dlq.push({
        taskId: task.id,
        taskName: task.name,
        priority: task.priority,
        task: task,
        error: errorMsg,
        totalAttempts: task.retryCount,
        attempts: task.retryCount,
        recordedAt: Date.now(),
        timestamp: Date.now()
      });
      simState.metrics.tasksFailed++;
      addSimLog('FAILURE', worker.name, task.id, task.name, task.priority, `PERMANENT FAILURE -> Quarantined in Dead Letter Queue (${errorMsg})`, duration, task.retryCount);
    }
  } else {
    task.status = 'COMPLETED';
    simState.metrics.tasksCompleted++;
    addSimLog('SUCCESS', worker.name, task.id, task.name, task.priority, `Completed task in ${duration}ms`, duration, task.retryCount);
  }

  const finished = simState.metrics.tasksCompleted + simState.metrics.tasksFailed;
  simState.metrics.successRate = finished > 0 ? ((simState.metrics.tasksCompleted / finished) * 100).toFixed(1) : '100.0';
  simState.metrics.avgExecTimeMs = duration.toFixed(1);

  worker.active = false;
  worker.state = 'IDLE';
  worker.currentTask = null;
  worker.task = null;
  simState.metrics.activeWorkers = simState.workers.filter(x => x.active).length;
  updateUI(simState);
}

function addSimLog(type, workerName, taskId, taskName, priority, message, durationMs, retryAttempt) {
  const log = { timestamp: Date.now(), type, workerName, taskId, taskName, priority, message, durationMs, retryAttempt };
  simState.logs.unshift(log);
  if (simState.logs.length > 100) simState.logs.pop();
  allLogs = simState.logs;
  renderLogs();
}

async function fetchStatus() {
  const apiBase = getApiBase();
  try {
    const res = await fetch(apiBase + '/api/status');
    if (!res.ok) {
      if (!isSimMode) initSimEngine();
      return;
    }
    const data = await res.json();
    const connStatus = document.getElementById('connStatusText');
    connStatus.innerText = 'Backend: Connected';
    isSimMode = false;
    updateUI(data);
  } catch (err) {
    if (!isSimMode) initSimEngine();
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
  if (!queue || queue.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        <p>Queue is empty</p>
        <span>Ready to accept priority jobs</span>
      </div>`;
    return;
  }

  container.innerHTML = queue.map(task => `
    <div class="task-card">
      <div class="task-card-header">
        <span class="task-card-title" title="${escapeHtml(task.name)}">${escapeHtml(task.name)}</span>
        <span class="badge badge-${(task.priority || 'medium').toLowerCase()}">${task.priority}</span>
      </div>
      <div class="task-card-footer">
        <span class="task-seq">Seq #${task.sequenceNumber || 1}</span>
        <span>Retries: ${task.retryCount || 0}</span>
      </div>
    </div>
  `).join('');
}

function renderWorkers(workers) {
  const container = document.getElementById('workerGrid');
  container.innerHTML = workers.map(w => {
    const isExec = w.state === 'EXECUTING' || w.isWorking || (w.currentTask !== null && w.currentTask !== undefined);
    const task = w.currentTask || w.task;

    return `
      <div class="worker-card ${isExec ? 'executing' : ''}">
        <div class="worker-card-top">
          <div class="worker-name-group">
            <span class="worker-dot"></span>
            <span class="worker-name">${escapeHtml(w.name)}</span>
          </div>
          <span class="worker-state-tag ${isExec ? 'state-executing' : 'state-idle'}">${isExec ? 'EXECUTING' : 'IDLE'}</span>
        </div>

        <div class="worker-task-info">
          ${isExec && task ? `
            <div>Task: <strong>${escapeHtml(task.name)}</strong></div>
            <div style="display:flex; justify-content:space-between; align-items:center; margin-top:4px;">
              <span class="badge badge-${(task.priority || 'medium').toLowerCase()}">${task.priority}</span>
              <span style="font-family:var(--font-mono); font-size:0.72rem; color:var(--text-secondary);">${task.elapsedMs || 120}ms</span>
            </div>
            <div class="worker-progress-bar">
              <div class="worker-progress-bar-fill"></div>
            </div>
          ` : `
            <span style="color:var(--text-muted); font-size:0.75rem;">Waiting for task in queue...</span>
          `}
        </div>
      </div>
    `;
  }).join('');
}

function renderDLQ(dlq) {
  const container = document.getElementById('dlqList');
  if (!dlq || dlq.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        <p>Dead Letter Queue is empty</p>
        <span>No quarantined poison pills</span>
      </div>`;
    return;
  }

  container.innerHTML = dlq.map(item => {
    const tName = item.taskName || (item.task ? item.task.name : 'Unknown-Task');
    const prio = item.priority || (item.task ? item.task.priority : 'CRITICAL');
    const attempts = item.totalAttempts || item.attempts || 1;
    const time = item.recordedAt || item.timestamp || Date.now();
    const errMsg = item.error || (item.finalException ? item.finalException.message : 'Execution Error');

    return `
      <div class="dlq-card task-card">
        <div class="task-card-header">
          <span class="task-card-title" title="${escapeHtml(tName)}">${escapeHtml(tName)}</span>
          <span class="badge badge-critical">${prio}</span>
        </div>
        <div class="task-card-footer">
          <span>Attempts: ${attempts}</span>
          <span style="font-family:var(--font-mono);">${new Date(time).toLocaleTimeString()}</span>
        </div>
        <div class="dlq-error-msg">${escapeHtml(errMsg)}</div>
      </div>
    `;
  }).join('');
}

function renderLogs() {
  const container = document.getElementById('logsContainer');
  const filtered = currentFilter === 'ALL'
    ? allLogs
    : allLogs.filter(l => l.type === currentFilter || (currentFilter === 'FAILURE' && (l.type === 'FAILURE' || l.type === 'DLQ_REPLAY')));

  if (filtered.length === 0) {
    container.innerHTML = `<div style="text-align:center; padding:1.5rem; color:var(--text-muted); font-size:0.75rem;">No log entries for filter: ${currentFilter}</div>`;
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
  const id = parseInt(scenarioId, 10);
  showToast(`Running Scenario #${id}`);
  const apiBase = getApiBase();

  if (isSimMode) {
    if (id === 1) {
      // 1. Priority Precedence & FIFO Tie-Breaking
      simEnqueueTask('Low-OrderSync-1', 'LOW', 2200, 'none', 0, 0);
      simEnqueueTask('Low-OrderSync-2', 'LOW', 2200, 'none', 0, 0);
      simEnqueueTask('Medium-InventoryCheck', 'MEDIUM', 1800, 'none', 0, 0);
      simEnqueueTask('High-PaymentCapture', 'HIGH', 1500, 'none', 0, 0);
      simEnqueueTask('Critical-FraudAlert', 'CRITICAL', 1200, 'none', 0, 0);
      simEnqueueTask('Critical-PrimeDelivery', 'CRITICAL', 1200, 'none', 0, 0);
    } else if (id === 2) {
      // 2. Exponential Backoff Retry Storm
      simEnqueueTask('PaymentGateway-Charge', 'HIGH', 1000, 'transient', 3, 800);
      simEnqueueTask('AuthToken-Refresh', 'CRITICAL', 800, 'transient', 2, 600);
      simEnqueueTask('Inventory-Reserve', 'MEDIUM', 800, 'transient', 2, 600);
    } else if (id === 3) {
      // 3. Poison Pill Isolation (DLQ)
      simEnqueueTask('Corrupted-Payload-Job-1', 'MEDIUM', 800, 'always', 2, 800);
      simEnqueueTask('Malformed-JSON-Record-2', 'HIGH', 800, 'always', 1, 600);
    } else if (id === 4) {
      // 4. High-Load Concurrency Surge (16 Jobs)
      const priorities = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
      for (let i = 1; i <= 16; i++) {
        const p = priorities[i % 4];
        simEnqueueTask(`Surge-Job-${i}`, p, 1200 + (i * 100), 'none', 0, 0);
      }
    }
    return;
  }

  try {
    await fetch(apiBase + '/api/scenarios/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scenario: id })
    });
    triggerFastPolling(8000);
  } catch (e) {
    triggerFastPolling(4000);
  }
}

function simEnqueueTask(name, priority, durationMs, failMode, maxRetries, backoffMs) {
  const task = {
    id: 't-' + Math.random().toString(36).substr(2, 6),
    sequenceNumber: simSeq++,
    name: name,
    priority: priority,
    durationMs: parseInt(durationMs) || 1000,
    failMode: failMode || 'none',
    maxRetries: parseInt(maxRetries) || 0,
    backoffMs: parseInt(backoffMs) || 600,
    retryCount: 0,
    scheduledTime: Date.now(),
    status: 'QUEUED'
  };
  simState.queue.push(task);
  simState.metrics.tasksSubmitted++;
  simState.metrics.queueSize = simState.queue.length;
  addSimLog('SUBMITTED', 'Dispatcher', task.id, task.name, task.priority, `Task submitted with [${task.priority}] priority (Seq #${task.sequenceNumber})`, 0, 0);
  updateUI(simState);
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
  const apiBase = getApiBase();

  if (isSimMode) {
    simEnqueueTask(name, priority, durationMs, failMode, maxRetries, backoffMs);
    document.getElementById('submitModal').classList.remove('active');
    showToast(`Task '${name}' enqueued successfully`);
    return;
  }

  const payload = {
    name,
    priority,
    durationMs,
    failMode,
    retryType,
    maxRetries,
    backoffMs
  };

  try {
    const res = await fetch(apiBase + '/api/tasks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    if (res.ok) {
      document.getElementById('submitModal').classList.remove('active');
      showToast(`Task '${name}' enqueued to backend`);
      triggerFastPolling(4000);
    }
  } catch (err) {
    document.getElementById('submitModal').classList.remove('active');
    showToast(`Task '${name}' enqueued`);
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
    toast.style.transform = 'translateY(6px)';
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
