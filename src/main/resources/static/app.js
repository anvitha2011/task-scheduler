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
