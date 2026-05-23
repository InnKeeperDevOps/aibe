import { state } from './state.js';
import { esc, showToast } from './utils.js';

export async function fetchRecommendations() {
    const modal = document.getElementById('recommendationsModal');
    const content = document.getElementById('recommendationsContent');
    modal.style.display = '';
    content.innerHTML = '<div class="loading" style="padding:2rem;text-align:center">Getting suggestions from AI...<br><span style="font-size:0.85rem;color:var(--text-muted)">This may take a couple of minutes</span></div>';

    try {
        const startRes = await fetch('/api/recommendations', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });

        if (!startRes.ok) {
            const err = await startRes.json().catch(() => ({}));
            content.innerHTML = renderRecommendationsError(
                (err && err.error) ? err.error : 'Unable to get recommendations right now.'
            );
            return;
        }

        const { taskId } = await startRes.json();

        const data = await pollRecommendationTask(taskId, content);
        if (!data) return;

        if (!Array.isArray(data) || data.length === 0) {
            content.innerHTML = renderRecommendationsError(
                'The AI returned an unexpected response. Please try again.'
            );
            return;
        }

        state.recommendations = data;
        renderActiveRecommendations(data);
    } catch (err) {
        content.innerHTML = renderRecommendationsError(
            'Unable to connect. Please check your connection and try again.'
        );
    }
}

export async function pollRecommendationTask(taskId, content) {
    const maxPolls = 120;
    for (let i = 0; i < maxPolls; i++) {
        await new Promise(r => setTimeout(r, 5000));

        const modal = document.getElementById('recommendationsModal');
        if (modal && modal.style.display === 'none') return null;

        try {
            const res = await fetch('/api/recommendations/status/' + taskId);
            if (!res.ok) {
                content.innerHTML = renderRecommendationsError(
                    'Lost track of the recommendation task. Please try again.'
                );
                return null;
            }
            const result = await res.json();
            if (result.status === 'pending') continue;
            if (result.status === 'error') {
                content.innerHTML = renderRecommendationsError(result.error);
                return null;
            }
            return result.data;
        } catch (e) {
            // Network blip — keep polling
        }
    }
    content.innerHTML = renderRecommendationsError(
        'The AI is taking unusually long. Please try again.'
    );
    return null;
}

export function renderRecommendationsError(message) {
    return `<div class="card" style="background:#fef2f2;border-color:#fecaca;color:var(--danger)">
        <strong>Could not load recommendations</strong>
        <p style="margin-top:0.5rem;font-size:0.9rem;color:var(--danger)">${esc(message)}</p>
    </div>`;
}

export function closeRecommendationsModal() {
    document.getElementById('recommendationsModal').style.display = 'none';
    state.recommendations = [];
}

/**
 * Render the list of pending recommendations inside the modal. The result id
 * (from the server) is stored on each card so that once the user creates a
 * suggestion from it, the matching active card can be removed without a
 * second round-trip.
 */
export function renderActiveRecommendations(data) {
    const content = document.getElementById('recommendationsContent');
    if (!content) return;
    if (!data || !data.length) {
        content.innerHTML = '<div class="card" style="color:var(--text-muted);text-align:center">No outstanding recommendations — everything has been turned into suggestions.</div>';
        return;
    }
    content.innerHTML = data.map((rec, i) => {
        const resultId = (rec && rec.id != null) ? rec.id : '';
        return `
            <div class="card recommendation-card" data-result-id="${resultId}" style="margin-bottom:0.75rem">
                <div style="font-weight:600;margin-bottom:0.25rem">${esc(rec.title)}</div>
                <div style="font-size:0.9rem;color:var(--text-muted);margin-bottom:0.75rem">${esc(rec.description)}</div>
                <button class="btn btn-outline btn-sm" onclick="app.prefillFromRecommendation(${i})">Create Suggestion</button>
            </div>
        `;
    }).join('');
}

export function prefillFromRecommendation(index) {
    const rec = state.recommendations && state.recommendations[index];
    if (!rec) return;
    // Remember which recommendation the user is acting on so that, once the
    // suggestion is successfully created, we can mark it ACTED_ON and drop it
    // from the active list. Storing the id (not the index) keeps the link
    // stable even if the list is re-rendered in the meantime.
    state.pendingRecommendationResultId = (rec.id != null) ? rec.id : null;
    closeRecommendationsModal();
    if (window.app && window.app.navigate) {
        window.app.navigate('create');
    }
    document.getElementById('createTitle').value = rec.title;
    document.getElementById('createDescription').value = rec.description;
}

/**
 * After a suggestion was successfully created from a recommendation, mark the
 * recommendation as ACTED_ON server-side. The row is not deleted — it just
 * disappears from the active list so the admin only sees outstanding items.
 *
 * Returns silently on any error; this is a follow-up cleanup, not the user's
 * main action (the suggestion itself was already created).
 */
export async function markRecommendationActedOn(suggestionId) {
    const resultId = state.pendingRecommendationResultId;
    state.pendingRecommendationResultId = null;
    if (resultId == null || suggestionId == null) return;
    try {
        await fetch('/api/recommendations/results/' + encodeURIComponent(resultId) + '/act-on', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ suggestionId })
        });
    } catch (e) {
        // Best effort — the suggestion was created successfully, so we don't
        // want to surface a follow-up cleanup error to the user.
    }
}

/**
 * Open the recommendation history view and load the list of past runs.
 * The filter controls in the modal stay where they are; this reloads the
 * list using whatever filter values they currently hold.
 */
export async function openRecommendationsHistory() {
    const modal = document.getElementById('recommendationsHistoryModal');
    modal.style.display = '';
    showHistoryFilterBar(true);
    await loadRecommendationsHistory();
}

/**
 * Read the filter inputs and reload the history list. Each filter is
 * optional and missing values are simply omitted from the request.
 * Date inputs ("YYYY-MM-DD") are widened to a full day in UTC so the
 * "to" boundary is inclusive of the chosen day.
 */
export async function applyRecommendationsHistoryFilters() {
    await loadRecommendationsHistory();
}

export function clearRecommendationsHistoryFilters() {
    const statusEl = document.getElementById('recommendationsHistoryStatusFilter');
    const fromEl = document.getElementById('recommendationsHistoryFromFilter');
    const toEl = document.getElementById('recommendationsHistoryToFilter');
    if (statusEl) statusEl.value = '';
    if (fromEl) fromEl.value = '';
    if (toEl) toEl.value = '';
    return loadRecommendationsHistory();
}

async function loadRecommendationsHistory() {
    const content = document.getElementById('recommendationsHistoryContent');
    content.innerHTML = '<div class="loading" style="padding:2rem;text-align:center">Loading past recommendations...</div>';

    const params = buildHistoryQueryParams();
    const url = '/api/recommendations/runs' + (params ? ('?' + params) : '');
    try {
        const res = await fetch(url);
        if (!res.ok) {
            let msg = 'Could not load recommendation history.';
            if (res.status === 400) {
                const err = await res.json().catch(() => ({}));
                if (err && err.error) msg = err.error;
            }
            content.innerHTML = renderRecommendationsError(msg);
            return;
        }
        const { runs } = await res.json();
        renderRecommendationsHistoryList(runs || []);
    } catch (e) {
        content.innerHTML = renderRecommendationsError('Could not connect. Please try again.');
    }
}

export function buildHistoryQueryParams() {
    const params = new URLSearchParams();
    const statusEl = document.getElementById('recommendationsHistoryStatusFilter');
    const fromEl = document.getElementById('recommendationsHistoryFromFilter');
    const toEl = document.getElementById('recommendationsHistoryToFilter');
    const status = statusEl && statusEl.value ? statusEl.value.trim() : '';
    const from = fromEl && fromEl.value ? fromEl.value.trim() : '';
    const to = toEl && toEl.value ? toEl.value.trim() : '';
    if (status) params.set('status', status);
    if (from) {
        const iso = dayStartIso(from);
        if (iso) params.set('from', iso);
    }
    if (to) {
        const iso = dayEndIso(to);
        if (iso) params.set('to', iso);
    }
    return params.toString();
}

function dayStartIso(yyyyMmDd) {
    const d = new Date(yyyyMmDd + 'T00:00:00.000Z');
    if (isNaN(d.getTime())) return null;
    return d.toISOString();
}

function dayEndIso(yyyyMmDd) {
    const d = new Date(yyyyMmDd + 'T23:59:59.999Z');
    if (isNaN(d.getTime())) return null;
    return d.toISOString();
}

function showHistoryFilterBar(visible) {
    const bar = document.getElementById('recommendationsHistoryFilters');
    if (bar) bar.style.display = visible ? '' : 'none';
}

export function renderRecommendationsHistoryList(runs) {
    const content = document.getElementById('recommendationsHistoryContent');
    if (!runs.length) {
        content.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No past recommendation runs yet.</div>';
        return;
    }
    content.innerHTML = runs.map(run => {
        const statusLabel = formatHistoryStatus(run.status);
        const startedLabel = formatHistoryTimestamp(run.createdAt);
        const finishedLabel = run.completedAt ? formatHistoryTimestamp(run.completedAt) : 'Not finished';
        const byLabel = run.requestedByUsername ? esc(run.requestedByUsername) : '(unknown)';
        const countLabel = (typeof run.resultCount === 'number') ? run.resultCount : 0;
        const taskIdSafe = esc(run.taskId);
        return `
            <div class="card" style="margin-bottom:0.75rem">
                <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:0.5rem">
                    <div>
                        <div style="font-weight:600">Run by ${byLabel}</div>
                        <div style="font-size:0.85rem;color:var(--text-muted)">Status: ${statusLabel}</div>
                        <div style="font-size:0.85rem;color:var(--text-muted)">Started: ${esc(startedLabel)}</div>
                        <div style="font-size:0.85rem;color:var(--text-muted)">Finished: ${esc(finishedLabel)}</div>
                        <div style="font-size:0.85rem;color:var(--text-muted)">Recommendations: ${countLabel}</div>
                    </div>
                    <div style="display:flex;gap:0.25rem;flex-wrap:wrap;justify-content:flex-end">
                        <button class="btn btn-outline btn-sm" onclick="app.viewRecommendationRun('${taskIdSafe}')">View</button>
                        <button class="btn btn-primary btn-sm" onclick="app.rerunRecommendationRun('${taskIdSafe}')">Re-run</button>
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

export async function viewRecommendationRun(taskId) {
    const content = document.getElementById('recommendationsHistoryContent');
    showHistoryFilterBar(false);
    content.innerHTML = '<div class="loading" style="padding:2rem;text-align:center">Loading recommendation details...</div>';
    try {
        const res = await fetch('/api/recommendations/runs/' + encodeURIComponent(taskId));
        if (res.status === 404) {
            content.innerHTML = renderRecommendationsError('That recommendation run could not be found.');
            return;
        }
        if (!res.ok) {
            content.innerHTML = renderRecommendationsError('Could not load that recommendation run.');
            return;
        }
        const run = await res.json();
        renderRecommendationRunDetail(run);
    } catch (e) {
        content.innerHTML = renderRecommendationsError('Could not connect. Please try again.');
    }
}

export function renderRecommendationRunDetail(run) {
    const content = document.getElementById('recommendationsHistoryContent');
    const recs = Array.isArray(run.recommendations) ? run.recommendations : [];
    const statusLabel = formatHistoryStatus(run.status);
    const startedLabel = formatHistoryTimestamp(run.createdAt);
    const finishedLabel = run.completedAt ? formatHistoryTimestamp(run.completedAt) : 'Not finished';
    const byLabel = run.requestedByUsername ? esc(run.requestedByUsername) : '(unknown)';
    const taskIdSafe = esc(run.taskId || '');

    let bullets = '';
    if (recs.length) {
        bullets = recs.map(rec => {
            const acted = rec && rec.status === 'ACTED_ON';
            const linkedId = (rec && rec.actedOnSuggestionId != null) ? rec.actedOnSuggestionId : null;
            const badge = acted
                ? `<span style="display:inline-block;margin-left:0.5rem;padding:0.1rem 0.4rem;border-radius:4px;background:#e0f2fe;color:#075985;font-size:0.75rem">Turned into suggestion${linkedId != null ? ' #' + esc(String(linkedId)) : ''}</span>`
                : '';
            return `
                <div class="card" style="margin-bottom:0.5rem">
                    <div style="font-weight:600;margin-bottom:0.25rem">${esc(rec.title || '')}${badge}</div>
                    <div style="font-size:0.9rem;color:var(--text-muted)">${esc(rec.description || '')}</div>
                </div>
            `;
        }).join('');
    } else if (run.status === 'ERROR') {
        bullets = `<div class="card" style="background:#fef2f2;border-color:#fecaca">
            <strong>This run did not finish.</strong>
            <p style="margin-top:0.5rem;font-size:0.9rem">${esc(run.errorMessage || 'No error details recorded.')}</p>
        </div>`;
    } else if (run.status === 'PENDING' || run.status === 'IN_PROGRESS') {
        bullets = '<div class="card" style="color:var(--text-muted)">This run is still in progress.</div>';
    } else {
        bullets = '<div class="card" style="color:var(--text-muted)">No recommendations were recorded for this run.</div>';
    }

    content.innerHTML = `
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.75rem;gap:0.5rem;flex-wrap:wrap">
            <button class="btn btn-outline btn-sm" onclick="app.openRecommendationsHistory()">&larr; Back to history</button>
            <button class="btn btn-primary btn-sm" onclick="app.rerunRecommendationRun('${taskIdSafe}')">Re-run this</button>
        </div>
        <div class="card" style="margin-bottom:1rem">
            <div style="font-weight:600">Run by ${byLabel}</div>
            <div style="font-size:0.85rem;color:var(--text-muted)">Status: ${statusLabel}</div>
            <div style="font-size:0.85rem;color:var(--text-muted)">Started: ${esc(startedLabel)}</div>
            <div style="font-size:0.85rem;color:var(--text-muted)">Finished: ${esc(finishedLabel)}</div>
        </div>
        ${bullets}
    `;
}

export function closeRecommendationsHistory() {
    document.getElementById('recommendationsHistoryModal').style.display = 'none';
}

/**
 * Re-fetch the list of past recommendation runs using whatever filter values
 * the user currently has set. Used by the explicit "Refresh" button so that
 * newly-started runs (e.g. via "Re-run" on an entry) become visible without
 * having to close and reopen the modal.
 */
export async function refreshRecommendationsHistory() {
    showHistoryFilterBar(true);
    await loadRecommendationsHistory();
}

/**
 * Close the history modal and start a brand-new recommendation run via the
 * existing "Get AI Recommendations" flow (which opens its own modal and polls
 * until the results arrive).
 */
export function startFreshRecommendationsRun() {
    closeRecommendationsHistory();
    return fetchRecommendations();
}

/**
 * Trigger a fresh recommendation run based on a past run, then reload the
 * history list so the new pending entry shows up at the top. The user gets a
 * toast confirmation and can click the new entry's "View" button to follow
 * its progress.
 */
export async function rerunRecommendationRun(taskId) {
    if (!taskId) return;
    try {
        const res = await fetch('/api/recommendations/runs/' + encodeURIComponent(taskId) + '/rerun', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });
        if (res.status === 404) {
            showToast('That past recommendation could not be found.');
            return;
        }
        if (res.status === 403) {
            showToast('You need admin access to start a new recommendation run.');
            return;
        }
        if (!res.ok) {
            showToast('Could not start a new recommendation run. Please try again.');
            return;
        }
        showToast('A new recommendation run has started.');
        await refreshRecommendationsHistory();
    } catch (e) {
        showToast('Could not connect. Please try again.');
    }
}

function formatHistoryStatus(status) {
    switch (status) {
        case 'DONE': return 'Finished';
        case 'ERROR': return 'Failed';
        case 'IN_PROGRESS': return 'In progress';
        case 'PENDING': return 'Waiting to start';
        default: return status ? esc(status) : 'Unknown';
    }
}

function formatHistoryTimestamp(iso) {
    if (!iso) return '';
    try {
        const d = new Date(iso);
        if (isNaN(d.getTime())) return iso;
        return d.toLocaleString();
    } catch (e) {
        return iso;
    }
}
