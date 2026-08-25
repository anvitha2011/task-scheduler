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
      if (isSimMode) {
        simState.metrics = { tasksSubmitted: 0, tasksCompleted: 0, tasksFailed: 0, tasksRetried: 0, activeWorkers: 0, totalWorkers: 4, queueSize: 0, dlqSize: 0, avgExecTimeMs: '0.0', successRate: '100.0' };
        simState.queue = [];
        simState.dlq = [];
        simState.logs = [];
        allLogs = [];
        showToast('Task Scheduler reset successfully');
        updateUI(simState);
        return;
      }
      await fetch('/api/scheduler/reset', { method: 'POST' });
      showToast('Task Scheduler reset successfully');
      fetchStatus();
    }
  });

  document.getElementById('btnReplayDlq').addEventListener('click', async () => {
    if (isSimMode) {
      const count = simState.dlq.length;
      simState.dlq.forEach(d => {
        d.task.retryCount = 0;
        d.task.status = 'QUEUED';
        d.task.scheduledTime = Date.now();
        simState.queue.push(d.task);
      });
      simState.dlq = [];
      showToast(`Replayed ${count} tasks from DLQ back to Queue`);
      updateUI(simState);
      return;
    }
    const res = await fetch('/api/dlq/replay', { method: 'POST' });
    const data = await res.json();
    showToast(`Replayed ${data.replayed} tasks from DLQ back to Queue`);
    fetchStatus();
  });

  document.getElementById('btnClearDlq').addEventListener('click', async () => {
    if (isSimMode) {
      simState.dlq = [];
      showToast('Dead Letter Queue cleared');
      updateUI(simState);
      return;
    }
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

let isSimMode = false;
let simState = {
  metrics: { tasksSubmitted: 0, tasksCompleted: 0, tasksFailed: 0, tasksRetried: 0, activeWorkers: 0, totalWorkers: 4, queueSize: 0, dlqSize: 0, avgExecTimeMs: '0.0', successRate: '100.0' },
  workers: [
    { name: 'worker-thread-1', state: 'IDLE', active: false, task: null },
    { name: 'worker-thread-2', state: 'IDLE', active: false, task: null },
    { name: 'worker-thread-3', state: 'IDLE', active: false, task: null },
    { name: 'worker-thread-4', state: 'IDLE', active: false, task: null }
  ],
  queue: [],
  dlq: [],
  logs: []
};

function initSimEngine() {
  isSimMode = true;
  const connStatus = document.getElementById('connStatusText');
  const connPill = document.getElementById('connPill');
  connStatus.innerText = 'ONLINE (BROWSER SIM)';
  connPill.style.borderColor = 'rgba(16, 185, 129, 0.4)';
  setInterval(simWorkerTick, 250);
}

function simWorkerTick() {
  if (!isSimMode) return;
  const now = Date.now();
  // Check ready queue
  simState.workers.forEach(w => {
    if (!w.active && simState.queue.length > 0) {
      // Find highest priority mature task
      simState.queue.sort((a, b) => {
        const pOrder = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
        const pd = (pOrder[b.priority] || 1) - (pOrder[a.priority] || 1);
        if (pd !== 0) return pd;
        return (a.seq || 0) - (b.seq || 0);
      });
      const readyIdx = simState.queue.findIndex(t => (t.scheduledTime || 0) <= now);
      if (readyIdx !== -1) {
        const task = simState.queue.splice(readyIdx, 1)[0];
        w.active = true;
        w.state = 'EXECUTING';
        w.task = task;
        task.status = 'RUNNING';
        simState.metrics.activeWorkers = simState.workers.filter(x => x.active).length;
        simState.metrics.queueSize = simState.queue.length;

        addSimLog('STARTED', w.name, task.id, task.name, task.priority, 'Worker dequeued task', 0, task.retryCount);
        
        setTimeout(() => {
          simFinishTask(w, task);
        }, task.durationMs || 500);
      }
    }
  });
  simState.metrics.queueSize = simState.queue.length;
  simState.metrics.dlqSize = simState.dlq.length;
  updateUI(simState);
}

function simFinishTask(worker, task) {
  const duration = task.durationMs || 500;
  let isFailure = false;
  if (task.failMode === 'transient' && task.retryCount < 2) isFailure = true;
  if (task.failMode === 'always') isFailure = true;

  if (isFailure) {
    const errorMsg = task.failMode === 'always' ? 'Fatal: Poison pill corrupted payload' : 'Transient Network Timeout (504)';
    if (task.retryCount < (task.maxRetries || 3)) {
      task.retryCount++;
      const delay = (task.backoffMs || 300) * Math.pow(2, task.retryCount - 1);
      task.scheduledTime = Date.now() + delay;
      task.status = 'RETRYING';
      simState.queue.push(task);
      simState.metrics.tasksRetried++;
      addSimLog('RETRY', worker.name, task.id, task.name, task.priority, `Task failed: ${errorMsg} (Scheduling retry #${task.retryCount} in ${delay}ms)`, duration, task.retryCount);
    } else {
      task.status = 'FAILED';
      simState.dlq.push({ task, error: errorMsg, attempts: task.retryCount, timestamp: Date.now() });
      simState.metrics.tasksFailed++;
      addSimLog('FAILURE', worker.name, task.id, task.name, task.priority, `PERMANENT FAILURE -> Moved to DLQ (${errorMsg})`, duration, task.retryCount);
    }
  } else {
    task.status = 'COMPLETED';
    simState.metrics.tasksCompleted++;
    addSimLog('SUCCESS', worker.name, task.id, task.name, task.priority, `Task completed successfully`, duration, task.retryCount);
  }

  const finished = simState.metrics.tasksCompleted + simState.metrics.tasksFailed;
  simState.metrics.successRate = finished > 0 ? ((simState.metrics.tasksCompleted / finished) * 100).toFixed(1) : '100.0';
  simState.metrics.avgExecTimeMs = duration.toFixed(1);

  worker.active = false;
  worker.state = 'IDLE';
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
  if (isSimMode) return;
  try {
    const res = await fetch('/api/status');
    if (!res.ok) {
      initSimEngine();
      return;
    }
    const data = await res.json();
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

let simSeq = 1;

async function runScenario(scenarioId) {
  showToast(`Running Scenario #${scenarioId}...`);
  if (isSimMode) {
    if (scenarioId === 1) {
      // Priority Inversion Test
      simEnqueueTask('Low-Priority-OrderSync-1', 'LOW', 700, 'none', 0, 0);
      simEnqueueTask('Low-Priority-OrderSync-2', 'LOW', 700, 'none', 0, 0);
      simEnqueueTask('Medium-Priority-InventoryCheck', 'MEDIUM', 600, 'none', 0, 0);
      simEnqueueTask('High-Priority-PaymentCapture', 'HIGH', 600, 'none', 0, 0);
      simEnqueueTask('Critical-Priority-FraudAlert', 'CRITICAL', 500, 'none', 0, 0);
      simEnqueueTask('Critical-Priority-PrimeDelivery', 'CRITICAL', 500, 'none', 0, 0);
    } else if (scenarioId === 2) {
      // Exponential Backoff Retry Test
      simEnqueueTask('PaymentGateway-Charge', 'HIGH', 400, 'transient', 3, 250);
    } else if (scenarioId === 3) {
      // Poison Pill to DLQ
      simEnqueueTask('Corrupted-Payload-Job', 'MEDIUM', 300, 'always', 2, 200);
    } else if (scenarioId === 4) {
      // High-Traffic Burst
      const priorities = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
      for (let i = 1; i <= 12; i++) {
        const p = priorities[i % 4];
        simEnqueueTask(`Burst-Job-${i}`, p, 300 + (i * 30), 'none', 0, 0);
      }
    }
    return;
  }

  await fetch('/api/scenarios/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scenario: scenarioId })
  });
  fetchStatus();
}

function simEnqueueTask(name, priority, durationMs, failMode, maxRetries, backoffMs) {
  const task = {
    id: 't-' + Math.random().toString(36).substr(2, 6),
    seq: simSeq++,
    sequenceNumber: simSeq,
    name: name,
    priority: priority,
    durationMs: parseInt(durationMs) || 500,
    failMode: failMode || 'none',
    maxRetries: parseInt(maxRetries) || 0,
    backoffMs: parseInt(backoffMs) || 300,
    retryCount: 0,
    scheduledTime: Date.now(),
    status: 'QUEUED'
  };
  simState.queue.push(task);
  simState.metrics.tasksSubmitted++;
  simState.metrics.queueSize = simState.queue.length;
  addSimLog('SUBMITTED', 'API-Client', task.id, task.name, task.priority, 'Task submitted with priority ' + task.priority, 0, 0);
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

  if (isSimMode) {
    simEnqueueTask(name, priority, durationMs, failMode, maxRetries, backoffMs);
    document.getElementById('submitModal').classList.remove('active');
    showToast(`Task '${name}' enqueued successfully!`);
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
