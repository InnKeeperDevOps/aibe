import { api } from './api.js';

// Admin-only live view of the Claude CLI request queue. Polls every 2 seconds
// while the view is active and stops itself when the user navigates away.

const POLL_INTERVAL_MS = 2000;
let pollTimer = null;

function fmtAge(ms) {
    if (ms == null || ms < 0) return '';
    if (ms < 1000) return ms + ' ms';
    if (ms < 60_000) return (ms / 1000).toFixed(1) + ' s';
    if (ms < 3_600_000) return Math.floor(ms / 60_000) + 'm ' + Math.floor((ms % 60_000) / 1000) + 's';
    return Math.floor(ms / 3_600_000) + 'h ' + Math.floor((ms % 3_600_000) / 60_000) + 'm';
}

function phaseColor(phase) {
    switch (phase) {
        case 'RUNNING':              return '#059669'; // green
        case 'AWAITING_CONCURRENCY': return '#b45309'; // amber
        case 'AWAITING_RATE_LIMIT':  return '#7c3aed'; // purple
        default:                     return 'var(--text-muted)';
    }
}

function cell(text, extraStyle) {
    const td = document.createElement('td');
    td.style.cssText = 'padding:0.5rem;border-bottom:1px solid var(--border);font-size:0.85rem;'
        + (extraStyle || '');
    td.textContent = (text == null) ? '' : String(text);
    return td;
}

function renderSummary(snap) {
    const el = document.getElementById('claudeQueueSummary');
    if (!el) return;
    el.innerHTML = '';
    const items = [
        ['Concurrency', snap.running + ' / ' + snap.maxConcurrent + ' running'
            + ' (' + snap.availablePermits + ' free)'],
        ['Rate limit', snap.rateLimitWindowUsed + ' / ' + snap.maxCallsPerMinute
            + ' calls in last 60s'],
        ['Awaiting concurrency', String(snap.awaitingConcurrency)],
        ['Awaiting rate limit',  String(snap.awaitingRateLimit)],
    ];
    items.forEach(([label, value]) => {
        const row = document.createElement('div');
        row.style.cssText = 'display:flex;justify-content:space-between;padding:0.25rem 0';
        const l = document.createElement('span');
        l.style.color = 'var(--text-muted)';
        l.textContent = label;
        const v = document.createElement('span');
        v.style.fontWeight = '600';
        v.textContent = value;
        row.appendChild(l);
        row.appendChild(v);
        el.appendChild(row);
    });
}

function renderTable(snap) {
    const body = document.getElementById('claudeQueueBody');
    if (!body) return;
    body.innerHTML = '';
    if (!snap.requests || snap.requests.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 6;
        td.style.cssText = 'padding:0.75rem;color:var(--text-muted)';
        td.textContent = 'No Claude CLI requests in flight right now.';
        tr.appendChild(td);
        body.appendChild(tr);
        return;
    }
    snap.requests.forEach(req => {
        const tr = document.createElement('tr');
        tr.appendChild(cell('#' + req.requestId));
        tr.appendChild(cell(req.operationType));
        tr.appendChild(cell(req.model));
        const ph = cell(req.phase);
        ph.style.color = phaseColor(req.phase);
        ph.style.fontWeight = '600';
        tr.appendChild(ph);
        tr.appendChild(cell(fmtAge(req.phaseAgeMs)));
        tr.appendChild(cell(fmtAge(req.ageMs)));
        body.appendChild(tr);
    });
}

async function fetchAndRender() {
    const errEl = document.getElementById('claudeQueueError');
    try {
        const snap = await api('/claude-queue');
        if (snap && snap.error) {
            if (errEl) { errEl.textContent = snap.error; errEl.style.display = ''; }
            return;
        }
        if (errEl) errEl.style.display = 'none';
        renderSummary(snap);
        renderTable(snap);
    } catch (e) {
        if (errEl) {
            errEl.textContent = 'Could not load Claude queue: ' + e.message;
            errEl.style.display = '';
        }
    }
}

function isViewActive() {
    const v = document.getElementById('claudeQueueView');
    return v && v.classList.contains('active');
}

export function loadClaudeQueue() {
    stopPolling();
    fetchAndRender();
    pollTimer = setInterval(() => {
        if (!isViewActive()) {
            stopPolling();
            return;
        }
        fetchAndRender();
    }, POLL_INTERVAL_MS);
}

export function stopClaudeQueuePolling() {
    stopPolling();
}

function stopPolling() {
    if (pollTimer != null) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}
