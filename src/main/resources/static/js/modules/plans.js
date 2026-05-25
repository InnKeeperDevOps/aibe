import { api } from './api.js';
import { state } from './state.js';
import { esc, timeAgo } from './utils.js';

// Cache for filtering without refetching.
let _plansCache = [];
let _allTasksCache = [];
let _planDetailCache = null;

const STATUS_LABELS = {
    PENDING: 'Pending',
    IN_PROGRESS: 'In progress',
    REVIEWING: 'Reviewing',
    COMPLETED: 'Completed',
    FAILED: 'Failed',
};
const STATUS_COLORS = {
    PENDING: '#94a3b8',
    IN_PROGRESS: '#2563eb',
    REVIEWING: '#d97706',
    COMPLETED: '#16a34a',
    FAILED: '#dc2626',
};

function statusPill(status) {
    const label = STATUS_LABELS[status] || status;
    const color = STATUS_COLORS[status] || 'var(--text-muted)';
    return `<span style="display:inline-block;padding:0.15rem 0.55rem;background:${color}1a;color:${color};border:1px solid ${color}55;border-radius:999px;font-size:0.75rem;font-weight:600">${esc(label)}</span>`;
}

// ---------------------------------------------------------------------------
// Plans index
// ---------------------------------------------------------------------------

export async function loadPlans() {
    const errEl = document.getElementById('plansError');
    if (errEl) errEl.style.display = 'none';
    try {
        const data = await api('/plans');
        if (data && data.error) {
            if (errEl) { errEl.textContent = data.error; errEl.style.display = ''; }
            _plansCache = [];
            renderPlansList();
            return;
        }
        _plansCache = Array.isArray(data) ? data : [];
        renderPlansSummary();
        renderPlansList();
    } catch (e) {
        if (errEl) { errEl.textContent = 'Failed to load plans: ' + e.message; errEl.style.display = ''; }
    }
}

function renderPlansSummary() {
    const el = document.getElementById('plansSummary');
    if (!el) return;
    const total = _plansCache.length;
    let totalTasks = 0, completed = 0, inProgress = 0, failed = 0;
    for (const p of _plansCache) {
        totalTasks += p.totalTasks || 0;
        completed += p.completedTasks || 0;
        inProgress += (p.inProgressTasks || 0) + (p.reviewingTasks || 0);
        failed += p.failedTasks || 0;
    }
    el.innerHTML = `
        <div style="display:flex;gap:2rem;flex-wrap:wrap">
            <div><div style="color:var(--text-muted);font-size:0.75rem">PLANS</div><div style="font-size:1.25rem;font-weight:600">${total}</div></div>
            <div><div style="color:var(--text-muted);font-size:0.75rem">TASKS</div><div style="font-size:1.25rem;font-weight:600">${totalTasks}</div></div>
            <div><div style="color:var(--text-muted);font-size:0.75rem">COMPLETED</div><div style="font-size:1.25rem;font-weight:600;color:${STATUS_COLORS.COMPLETED}">${completed}</div></div>
            <div><div style="color:var(--text-muted);font-size:0.75rem">IN PROGRESS</div><div style="font-size:1.25rem;font-weight:600;color:${STATUS_COLORS.IN_PROGRESS}">${inProgress}</div></div>
            <div><div style="color:var(--text-muted);font-size:0.75rem">FAILED</div><div style="font-size:1.25rem;font-weight:600;color:${STATUS_COLORS.FAILED}">${failed}</div></div>
        </div>`;
}

export function filterPlans() {
    renderPlansList();
}

function renderPlansList() {
    const listEl = document.getElementById('plansList');
    if (!listEl) return;
    const filter = (document.getElementById('plansFilter')?.value || '').trim().toLowerCase();
    const rows = _plansCache.filter(p => {
        if (!filter) return true;
        return (p.title || '').toLowerCase().includes(filter)
            || (p.authorName || '').toLowerCase().includes(filter);
    });
    if (rows.length === 0) {
        listEl.innerHTML = `<div class="card" style="text-align:center;color:var(--text-muted)">No plans${filter ? ' match this filter' : ' yet'}.</div>`;
        return;
    }
    listEl.innerHTML = rows.map(p => {
        const pct = Math.max(0, Math.min(100, p.progressPct || 0));
        const completedColor = STATUS_COLORS.COMPLETED;
        const failedBadge = (p.failedTasks > 0)
            ? `<span style="color:${STATUS_COLORS.FAILED};font-size:0.8rem;font-weight:600">${p.failedTasks} failed</span>`
            : '';
        const inFlight = (p.inProgressTasks || 0) + (p.reviewingTasks || 0);
        const inFlightBadge = inFlight > 0
            ? `<span style="color:${STATUS_COLORS.IN_PROGRESS};font-size:0.8rem;font-weight:600">${inFlight} active</span>`
            : '';
        return `<div class="card plan-card" style="cursor:pointer" onclick="app.navigate('planDetail', ${p.suggestionId})">
            <div style="display:flex;justify-content:space-between;gap:1rem;align-items:flex-start">
                <div style="min-width:0;flex:1">
                    <div style="font-weight:600;font-size:1rem">${esc(p.title || '(untitled)')}</div>
                    <div style="margin-top:0.25rem;color:var(--text-muted);font-size:0.8rem">
                        ${esc(p.authorName || 'unknown')} · ${esc(p.suggestionStatus || '')}
                        ${p.currentPhase ? ` · ${esc(p.currentPhase)}` : ''}
                        ${p.lastActivityAt ? ` · ${esc(timeAgo(p.lastActivityAt))}` : ''}
                    </div>
                </div>
                <div style="text-align:right;flex-shrink:0">
                    <div style="font-weight:600">${p.completedTasks}/${p.totalTasks}</div>
                    <div style="display:flex;gap:0.5rem;justify-content:flex-end;margin-top:0.15rem">${inFlightBadge}${failedBadge}</div>
                </div>
            </div>
            <div style="margin-top:0.6rem;height:6px;background:var(--border);border-radius:3px;overflow:hidden">
                <div style="height:100%;width:${pct}%;background:${completedColor};transition:width 0.2s"></div>
            </div>
        </div>`;
    }).join('');
}

// ---------------------------------------------------------------------------
// Plan detail
// ---------------------------------------------------------------------------

export async function loadPlanDetail(suggestionId) {
    const errEl = document.getElementById('planDetailError');
    const headEl = document.getElementById('planDetailHeader');
    const tasksEl = document.getElementById('planDetailTasks');
    if (errEl) errEl.style.display = 'none';
    if (headEl) headEl.innerHTML = '<div style="color:var(--text-muted)">Loading…</div>';
    if (tasksEl) tasksEl.innerHTML = '';
    if (suggestionId == null) {
        if (errEl) { errEl.textContent = 'Missing plan id'; errEl.style.display = ''; }
        return;
    }
    try {
        const data = await api('/plans/' + suggestionId);
        if (data && data.error) {
            if (errEl) { errEl.textContent = data.error; errEl.style.display = ''; }
            if (headEl) headEl.innerHTML = '';
            return;
        }
        _planDetailCache = data;
        renderPlanDetail(data);
    } catch (e) {
        if (errEl) { errEl.textContent = 'Failed to load plan: ' + e.message; errEl.style.display = ''; }
        if (headEl) headEl.innerHTML = '';
    }
}

function renderPlanDetail(data) {
    const headEl = document.getElementById('planDetailHeader');
    const tasksEl = document.getElementById('planDetailTasks');
    const tasks = data.tasks || [];
    const total = tasks.length;
    const completed = tasks.filter(t => t.status === 'COMPLETED').length;
    const pct = total === 0 ? 0 : Math.round((completed / total) * 100);

    if (headEl) {
        headEl.innerHTML = `
            <h2 style="margin:0">${esc(data.title || '(untitled)')}</h2>
            <div style="margin-top:0.35rem;color:var(--text-muted);font-size:0.85rem">
                ${esc(data.authorName || 'unknown')}
                · ${esc(data.suggestionStatus || '')}
                ${data.currentPhase ? ` · ${esc(data.currentPhase)}` : ''}
                ${data.lastActivityAt ? ` · ${esc(timeAgo(data.lastActivityAt))}` : ''}
            </div>
            <div style="margin-top:0.75rem;display:flex;gap:0.75rem;align-items:center">
                <div style="font-size:0.9rem;color:var(--text-muted)">${completed}/${total} completed (${pct}%)</div>
                <a href="#" onclick="app.navigate('detail', ${data.suggestionId});return false"
                   style="color:var(--primary);font-size:0.85rem;text-decoration:none">Open suggestion &rarr;</a>
                ${data.prUrl ? `<a href="${esc(data.prUrl)}" target="_blank" rel="noopener"
                   style="color:var(--primary);font-size:0.85rem;text-decoration:none">PR &rarr;</a>` : ''}
            </div>
            <div style="margin-top:0.5rem;height:6px;background:var(--border);border-radius:3px;overflow:hidden">
                <div style="height:100%;width:${pct}%;background:${STATUS_COLORS.COMPLETED}"></div>
            </div>`;
    }

    if (!tasksEl) return;
    if (tasks.length === 0) {
        tasksEl.innerHTML = '<div style="color:var(--text-muted);text-align:center">No tasks attached to this plan.</div>';
        return;
    }
    const showTech = state.showTechnicalPlan;
    tasksEl.innerHTML = tasks.map(t => {
        const title = showTech ? (t.title || t.displayTitle) : (t.displayTitle || t.title);
        const desc = showTech ? (t.description || t.displayDescription) : (t.displayDescription || t.description);
        let meta = '';
        if (t.estimatedMinutes) meta += `~${t.estimatedMinutes} min`;
        if (t.startedAt && !t.completedAt) {
            const elapsed = Math.round((Date.now() - new Date(t.startedAt).getTime()) / 60000);
            meta += (meta ? ' · ' : '') + `${elapsed} min elapsed`;
        }
        if (t.startedAt && t.completedAt) {
            const dur = Math.round((new Date(t.completedAt).getTime() - new Date(t.startedAt).getTime()) / 60000);
            meta += (meta ? ' · ' : '') + `took ${dur} min`;
        }
        if (t.retryCount && t.retryCount > 0) meta += (meta ? ' · ' : '') + `${t.retryCount} retries`;
        return `<div class="plan-detail-task" style="padding:0.75rem 0;border-bottom:1px solid var(--border)">
            <div style="display:flex;gap:0.75rem;align-items:flex-start;justify-content:space-between">
                <div style="min-width:0;flex:1">
                    <div style="font-weight:600">${t.taskOrder}. ${esc(title || '(untitled task)')}</div>
                    ${desc ? `<div style="margin-top:0.25rem;color:var(--text-muted);font-size:0.85rem;white-space:pre-wrap">${esc(desc)}</div>` : ''}
                    ${t.statusDetail ? `<div style="margin-top:0.35rem;font-size:0.8rem;color:var(--text-muted)">${esc(t.statusDetail)}</div>` : ''}
                    ${t.failureReason ? `<div style="margin-top:0.35rem;font-size:0.8rem;color:${STATUS_COLORS.FAILED}">⚠ ${esc(t.failureReason)}</div>` : ''}
                    ${meta ? `<div style="margin-top:0.25rem;font-size:0.75rem;color:var(--text-muted)">${meta}</div>` : ''}
                </div>
                <div>${statusPill(t.status)}</div>
            </div>
        </div>`;
    }).join('');
}

// ---------------------------------------------------------------------------
// All tasks (cross-plan)
// ---------------------------------------------------------------------------

export async function loadAllTasks() {
    const errEl = document.getElementById('tasksError');
    if (errEl) errEl.style.display = 'none';
    try {
        const data = await api('/plans/tasks');
        if (data && data.error) {
            if (errEl) { errEl.textContent = data.error; errEl.style.display = ''; }
            _allTasksCache = [];
            renderTasksTable();
            return;
        }
        _allTasksCache = Array.isArray(data) ? data : [];
        renderTasksStats();
        renderTasksTable();
    } catch (e) {
        if (errEl) { errEl.textContent = 'Failed to load tasks: ' + e.message; errEl.style.display = ''; }
    }
}

function renderTasksStats() {
    const el = document.getElementById('tasksStats');
    if (!el) return;
    const counts = { PENDING: 0, IN_PROGRESS: 0, REVIEWING: 0, COMPLETED: 0, FAILED: 0 };
    for (const t of _allTasksCache) {
        if (counts[t.status] != null) counts[t.status]++;
    }
    el.innerHTML = `
        <div style="display:flex;gap:1.5rem;flex-wrap:wrap">
            <div><div style="color:var(--text-muted);font-size:0.75rem">TOTAL</div><div style="font-size:1.25rem;font-weight:600">${_allTasksCache.length}</div></div>
            ${Object.entries(counts).map(([k, v]) => `
                <div>
                    <div style="color:var(--text-muted);font-size:0.75rem">${STATUS_LABELS[k].toUpperCase()}</div>
                    <div style="font-size:1.25rem;font-weight:600;color:${STATUS_COLORS[k]}">${v}</div>
                </div>`).join('')}
        </div>`;
}

export function filterAllTasks() {
    renderTasksTable();
}

function renderTasksTable() {
    const body = document.getElementById('tasksBody');
    if (!body) return;
    const statusFilter = document.getElementById('tasksStatusFilter')?.value || '';
    const search = (document.getElementById('tasksSearch')?.value || '').trim().toLowerCase();
    const showTech = state.showTechnicalPlan;
    const rows = _allTasksCache.filter(t => {
        if (statusFilter && t.status !== statusFilter) return false;
        if (search) {
            const title = (showTech ? (t.title || t.displayTitle) : (t.displayTitle || t.title)) || '';
            const sug = t.suggestionTitle || '';
            if (!title.toLowerCase().includes(search) && !sug.toLowerCase().includes(search)) return false;
        }
        return true;
    });
    if (rows.length === 0) {
        body.innerHTML = `<tr><td colspan="6" style="padding:1rem;text-align:center;color:var(--text-muted)">No tasks${statusFilter || search ? ' match the filter' : ''}.</td></tr>`;
        return;
    }
    body.innerHTML = rows.map(t => {
        const title = showTech ? (t.title || t.displayTitle) : (t.displayTitle || t.title);
        let dur = '';
        if (t.startedAt && t.completedAt) {
            const ms = new Date(t.completedAt).getTime() - new Date(t.startedAt).getTime();
            const min = Math.round(ms / 60000);
            dur = `${min} min`;
        } else if (t.startedAt) {
            const min = Math.round((Date.now() - new Date(t.startedAt).getTime()) / 60000);
            dur = `${min} min (running)`;
        }
        return `<tr class="task-row" style="cursor:pointer" onclick="app.navigate('planDetail', ${t.suggestionId})">
            <td style="padding:0.5rem;border-bottom:1px solid var(--border);font-size:0.85rem;max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(t.suggestionTitle || ('#' + t.suggestionId))}</td>
            <td style="padding:0.5rem;border-bottom:1px solid var(--border);font-size:0.85rem;color:var(--text-muted)">${t.taskOrder}</td>
            <td style="padding:0.5rem;border-bottom:1px solid var(--border);font-size:0.85rem;max-width:360px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(title || '')}</td>
            <td style="padding:0.5rem;border-bottom:1px solid var(--border)">${statusPill(t.status)}</td>
            <td style="padding:0.5rem;border-bottom:1px solid var(--border);font-size:0.8rem;color:var(--text-muted)">${t.startedAt ? esc(timeAgo(t.startedAt)) : ''}</td>
            <td style="padding:0.5rem;border-bottom:1px solid var(--border);font-size:0.8rem;color:var(--text-muted)">${dur}</td>
        </tr>`;
    }).join('');
}
