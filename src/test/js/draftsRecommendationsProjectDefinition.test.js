/**
 * Tests for drafts.js, recommendations.js, and projectDefinition.js modules.
 * Functions are defined inline to match the project's test pattern
 * (Jest jsdom environment does not support native ES module imports).
 */

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

function makeEsc() {
    return function esc(s) {
        if (!s) return '';
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    };
}

function makeTimeAgo() {
    return function timeAgo(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        const now = new Date();
        const diff = Math.floor((now - d) / 1000);
        if (diff < 60) return 'just now';
        if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
        if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
        return Math.floor(diff / 86400) + 'd ago';
    };
}

// ---------------------------------------------------------------------------
// drafts — renderDraftCards()
// ---------------------------------------------------------------------------

const esc = makeEsc();
const timeAgo = makeTimeAgo();

function renderDraftCards(drafts) {
    return drafts.map(s => {
        const priorityLabel = s.priority || 'MEDIUM';
        return `
        <div class="card suggestion-item" data-suggestion-id="${s.id}">
            <div class="suggestion-header">
                <div>
                    <div class="suggestion-title">${esc(s.title)}</div>
                    <div class="suggestion-meta">
                        <span>by ${esc(s.authorName || 'Anonymous')}</span>
                        <span>${timeAgo(s.createdAt)}</span>
                    </div>
                </div>
                <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
                    <span class="priority-badge priority-${priorityLabel}">${priorityLabel}</span>
                    <span class="status-badge" style="background:#f1f5f9;color:#64748b;border:1px solid #cbd5e1">DRAFT</span>
                </div>
            </div>
            <div class="suggestion-quick-actions" onclick="event.stopPropagation()" style="margin-top:0.75rem">
                <button class="btn btn-outline btn-sm" onclick="app.openEditDraftModal(${s.id})">Edit</button>
                <button class="btn btn-primary btn-sm" onclick="app.submitDraftConfirm(${s.id})">Submit</button>
            </div>
        </div>`;
    }).join('');
}

describe('renderDraftCards()', () => {
    test('returns empty string for empty array', () => {
        expect(renderDraftCards([])).toBe('');
    });

    test('renders one card per draft', () => {
        const drafts = [
            { id: 1, title: 'Draft A', priority: 'HIGH', authorName: 'Alice', createdAt: new Date(Date.now() - 60000).toISOString() },
            { id: 2, title: 'Draft B', priority: 'LOW', authorName: 'Bob', createdAt: new Date(Date.now() - 60000).toISOString() },
        ];
        const html = renderDraftCards(drafts);
        expect(html).toContain('Draft A');
        expect(html).toContain('Draft B');
        expect((html.match(/suggestion-item/g) || []).length).toBe(2);
    });

    test('uses MEDIUM as default priority when not specified', () => {
        const drafts = [{ id: 3, title: 'No Priority', authorName: 'Carol', createdAt: new Date().toISOString() }];
        const html = renderDraftCards(drafts);
        expect(html).toContain('priority-MEDIUM');
        expect(html).toContain('>MEDIUM<');
    });

    test('escapes HTML in title to prevent XSS', () => {
        const drafts = [{ id: 4, title: '<script>xss()</script>', priority: 'LOW', authorName: 'Eve', createdAt: new Date().toISOString() }];
        const html = renderDraftCards(drafts);
        expect(html).not.toContain('<script>xss()');
        expect(html).toContain('&lt;script&gt;');
    });

    test('falls back to Anonymous when authorName is missing', () => {
        const drafts = [{ id: 5, title: 'Anon Draft', priority: 'MEDIUM', createdAt: new Date().toISOString() }];
        const html = renderDraftCards(drafts);
        expect(html).toContain('Anonymous');
    });

    test('includes Edit and Submit buttons with correct IDs', () => {
        const drafts = [{ id: 7, title: 'Test', priority: 'MEDIUM', authorName: 'X', createdAt: new Date().toISOString() }];
        const html = renderDraftCards(drafts);
        expect(html).toContain('openEditDraftModal(7)');
        expect(html).toContain('submitDraftConfirm(7)');
    });

    test('shows DRAFT status badge on every card', () => {
        const drafts = [
            { id: 8, title: 'D1', priority: 'HIGH', authorName: 'A', createdAt: new Date().toISOString() },
            { id: 9, title: 'D2', priority: 'LOW', authorName: 'B', createdAt: new Date().toISOString() },
        ];
        const html = renderDraftCards(drafts);
        expect((html.match(/>DRAFT</g) || []).length).toBe(2);
    });
});

// ---------------------------------------------------------------------------
// drafts — showMyDrafts / showAllSuggestions DOM interactions
// ---------------------------------------------------------------------------

describe('showMyDrafts() and showAllSuggestions() DOM effects', () => {
    let state;
    let loadMyDraftsCalled;
    let loadSuggestionsCalled;

    function makeShowMyDrafts(st, loadMyDraftsFn, loadSuggestionsFn) {
        function showAllSuggestions() {
            st.myDraftsMode = false;
            const btn = document.getElementById('myDraftsBtn');
            if (btn) btn.textContent = 'My Drafts';
            if (btn) btn.onclick = () => showMyDrafts();
            loadSuggestionsFn();
        }
        function showMyDrafts() {
            st.myDraftsMode = true;
            const btn = document.getElementById('myDraftsBtn');
            if (btn) btn.textContent = 'All Suggestions';
            if (btn) btn.onclick = () => showAllSuggestions();
            loadMyDraftsFn();
        }
        return { showMyDrafts, showAllSuggestions };
    }

    beforeEach(() => {
        state = { myDraftsMode: false };
        loadMyDraftsCalled = false;
        loadSuggestionsCalled = false;
        document.body.innerHTML = '<button id="myDraftsBtn">My Drafts</button>';
    });

    test('showMyDrafts sets myDraftsMode to true and calls loadMyDrafts', () => {
        const { showMyDrafts } = makeShowMyDrafts(
            state,
            () => { loadMyDraftsCalled = true; },
            () => { loadSuggestionsCalled = true; }
        );
        showMyDrafts();
        expect(state.myDraftsMode).toBe(true);
        expect(loadMyDraftsCalled).toBe(true);
        expect(loadSuggestionsCalled).toBe(false);
    });

    test('showMyDrafts changes button text to "All Suggestions"', () => {
        const { showMyDrafts } = makeShowMyDrafts(
            state,
            () => {},
            () => {}
        );
        showMyDrafts();
        expect(document.getElementById('myDraftsBtn').textContent).toBe('All Suggestions');
    });

    test('showAllSuggestions sets myDraftsMode to false and calls loadSuggestions', () => {
        const { showAllSuggestions } = makeShowMyDrafts(
            state,
            () => { loadMyDraftsCalled = true; },
            () => { loadSuggestionsCalled = true; }
        );
        state.myDraftsMode = true;
        showAllSuggestions();
        expect(state.myDraftsMode).toBe(false);
        expect(loadSuggestionsCalled).toBe(true);
        expect(loadMyDraftsCalled).toBe(false);
    });

    test('showAllSuggestions changes button text to "My Drafts"', () => {
        const { showAllSuggestions } = makeShowMyDrafts(
            state,
            () => {},
            () => {}
        );
        showAllSuggestions();
        expect(document.getElementById('myDraftsBtn').textContent).toBe('My Drafts');
    });

    test('works without the button element present', () => {
        document.body.innerHTML = '';
        const { showMyDrafts } = makeShowMyDrafts(
            state,
            () => { loadMyDraftsCalled = true; },
            () => {}
        );
        expect(() => showMyDrafts()).not.toThrow();
        expect(loadMyDraftsCalled).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// drafts — loadMyDrafts()
// ---------------------------------------------------------------------------

function makeLoadMyDrafts(apiFn, renderFn) {
    return async function loadMyDrafts() {
        const list = document.getElementById('suggestionList');
        list.innerHTML = '<div class="loading">Loading drafts...</div>';
        try {
            const drafts = await apiFn('/suggestions/my-drafts');
            if (!Array.isArray(drafts) || drafts.length === 0) {
                list.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No saved drafts. Use "Save as Draft" when creating a suggestion.</div>';
                return;
            }
            list.innerHTML = renderFn(drafts);
        } catch (err) {
            list.innerHTML = '<div class="card" style="color:var(--danger)">Failed to load drafts.</div>';
        }
    };
}

describe('loadMyDrafts()', () => {
    beforeEach(() => {
        document.body.innerHTML = '<div id="suggestionList"></div>';
    });

    test('shows loading state initially', async () => {
        const api = jest.fn().mockResolvedValue([]);
        const loadMyDrafts = makeLoadMyDrafts(api, renderDraftCards);
        const promise = loadMyDrafts();
        expect(document.getElementById('suggestionList').innerHTML).toContain('Loading drafts');
        await promise;
    });

    test('shows empty state when no drafts returned', async () => {
        const api = jest.fn().mockResolvedValue([]);
        const loadMyDrafts = makeLoadMyDrafts(api, renderDraftCards);
        await loadMyDrafts();
        expect(document.getElementById('suggestionList').innerHTML).toContain('No saved drafts');
    });

    test('renders draft cards when drafts are returned', async () => {
        const drafts = [{ id: 1, title: 'My Draft', priority: 'HIGH', authorName: 'Alice', createdAt: new Date().toISOString() }];
        const api = jest.fn().mockResolvedValue(drafts);
        const loadMyDrafts = makeLoadMyDrafts(api, renderDraftCards);
        await loadMyDrafts();
        expect(document.getElementById('suggestionList').innerHTML).toContain('My Draft');
    });

    test('shows error message when API throws', async () => {
        const api = jest.fn().mockRejectedValue(new Error('Network error'));
        const loadMyDrafts = makeLoadMyDrafts(api, renderDraftCards);
        await loadMyDrafts();
        expect(document.getElementById('suggestionList').innerHTML).toContain('Failed to load drafts');
    });
});

// ---------------------------------------------------------------------------
// recommendations — renderRecommendationsError()
// ---------------------------------------------------------------------------

function renderRecommendationsError(message) {
    return `<div class="card" style="background:#fef2f2;border-color:#fecaca;color:var(--danger)">
        <strong>Could not load recommendations</strong>
        <p style="margin-top:0.5rem;font-size:0.9rem;color:var(--danger)">${esc(message)}</p>
    </div>`;
}

describe('renderRecommendationsError()', () => {
    test('includes the error message in the output', () => {
        const html = renderRecommendationsError('Something went wrong.');
        expect(html).toContain('Something went wrong.');
    });

    test('always includes the "Could not load recommendations" heading', () => {
        const html = renderRecommendationsError('any error');
        expect(html).toContain('Could not load recommendations');
    });

    test('escapes HTML in the error message', () => {
        const html = renderRecommendationsError('<script>alert(1)</script>');
        expect(html).not.toContain('<script>');
        expect(html).toContain('&lt;script&gt;');
    });
});

// ---------------------------------------------------------------------------
// recommendations — closeRecommendationsModal()
// ---------------------------------------------------------------------------

function makeCloseRecommendationsModal(state) {
    return function closeRecommendationsModal() {
        document.getElementById('recommendationsModal').style.display = 'none';
        state.recommendations = [];
    };
}

describe('closeRecommendationsModal()', () => {
    let state;

    beforeEach(() => {
        state = { recommendations: [{ title: 'Rec 1' }] };
        document.body.innerHTML = '<div id="recommendationsModal" style="display: block"></div>';
    });

    test('hides the modal', () => {
        const close = makeCloseRecommendationsModal(state);
        close();
        expect(document.getElementById('recommendationsModal').style.display).toBe('none');
    });

    test('clears recommendations from state', () => {
        const close = makeCloseRecommendationsModal(state);
        close();
        expect(state.recommendations).toHaveLength(0);
    });
});

// ---------------------------------------------------------------------------
// recommendations — prefillFromRecommendation()
// ---------------------------------------------------------------------------

function makePrefillFromRecommendation(state, closeModalFn, navigateFn) {
    return function prefillFromRecommendation(index) {
        const rec = state.recommendations && state.recommendations[index];
        if (!rec) return;
        closeModalFn();
        navigateFn('create');
        document.getElementById('createTitle').value = rec.title;
        document.getElementById('createDescription').value = rec.description;
    };
}

describe('prefillFromRecommendation()', () => {
    let state;
    let navigateCalled;
    let closeCalled;

    beforeEach(() => {
        state = { recommendations: [{ title: 'Improve caching', description: 'Use Redis for sessions.' }] };
        navigateCalled = null;
        closeCalled = false;
        document.body.innerHTML = `
            <div id="recommendationsModal"></div>
            <input id="createTitle" />
            <textarea id="createDescription"></textarea>
        `;
    });

    test('fills in title and description from the recommendation', () => {
        const prefill = makePrefillFromRecommendation(
            state,
            () => { closeCalled = true; },
            (v) => { navigateCalled = v; }
        );
        prefill(0);
        expect(document.getElementById('createTitle').value).toBe('Improve caching');
        expect(document.getElementById('createDescription').value).toBe('Use Redis for sessions.');
    });

    test('calls navigate with "create"', () => {
        const prefill = makePrefillFromRecommendation(
            state,
            () => {},
            (v) => { navigateCalled = v; }
        );
        prefill(0);
        expect(navigateCalled).toBe('create');
    });

    test('calls close modal', () => {
        const prefill = makePrefillFromRecommendation(
            state,
            () => { closeCalled = true; },
            () => {}
        );
        prefill(0);
        expect(closeCalled).toBe(true);
    });

    test('does nothing when index is out of range', () => {
        const prefill = makePrefillFromRecommendation(
            state,
            () => { closeCalled = true; },
            () => {}
        );
        prefill(99);
        expect(closeCalled).toBe(false);
        expect(document.getElementById('createTitle').value).toBe('');
    });

    test('does nothing when recommendations is empty', () => {
        state.recommendations = [];
        const prefill = makePrefillFromRecommendation(
            state,
            () => { closeCalled = true; },
            () => {}
        );
        prefill(0);
        expect(closeCalled).toBe(false);
    });
});

// ---------------------------------------------------------------------------
// recommendations — renderRecommendationsHistoryList() (with Re-run button)
// ---------------------------------------------------------------------------

function formatHistoryStatusForTest(status) {
    switch (status) {
        case 'DONE': return 'Finished';
        case 'ERROR': return 'Failed';
        case 'IN_PROGRESS': return 'In progress';
        case 'PENDING': return 'Waiting to start';
        default: return status ? esc(status) : 'Unknown';
    }
}

function formatHistoryTimestampForTest(iso) {
    if (!iso) return '';
    try {
        const d = new Date(iso);
        if (isNaN(d.getTime())) return iso;
        return d.toLocaleString();
    } catch (e) {
        return iso;
    }
}

function renderRecommendationsHistoryList(runs) {
    const content = document.getElementById('recommendationsHistoryContent');
    if (!runs.length) {
        content.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No past recommendation runs yet.</div>';
        return;
    }
    content.innerHTML = runs.map(run => {
        const statusLabel = formatHistoryStatusForTest(run.status);
        const startedLabel = formatHistoryTimestampForTest(run.createdAt);
        const finishedLabel = run.completedAt ? formatHistoryTimestampForTest(run.completedAt) : 'Not finished';
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

describe('renderRecommendationsHistoryList() with Re-run button', () => {
    beforeEach(() => {
        document.body.innerHTML = '<div id="recommendationsHistoryContent"></div>';
    });

    test('empty list still renders the placeholder message', () => {
        renderRecommendationsHistoryList([]);
        expect(document.getElementById('recommendationsHistoryContent').innerHTML)
            .toContain('No past recommendation runs yet');
    });

    test('every entry has a View button AND a Re-run button', () => {
        const runs = [
            { taskId: 't-1', status: 'DONE', createdAt: '2026-05-01T00:00:00Z', completedAt: '2026-05-01T00:05:00Z', requestedByUsername: 'alice', resultCount: 5 },
            { taskId: 't-2', status: 'ERROR', createdAt: '2026-05-02T00:00:00Z', completedAt: '2026-05-02T00:01:00Z', requestedByUsername: 'bob', resultCount: 0 },
        ];
        renderRecommendationsHistoryList(runs);
        const html = document.getElementById('recommendationsHistoryContent').innerHTML;
        expect((html.match(/viewRecommendationRun/g) || []).length).toBe(2);
        expect((html.match(/rerunRecommendationRun/g) || []).length).toBe(2);
        expect(html).toContain("rerunRecommendationRun('t-1')");
        expect(html).toContain("rerunRecommendationRun('t-2')");
    });

    test('Re-run button is rendered even for failed runs', () => {
        renderRecommendationsHistoryList([
            { taskId: 'failed-1', status: 'ERROR', createdAt: '2026-05-01T00:00:00Z', requestedByUsername: 'admin', resultCount: 0 },
        ]);
        const html = document.getElementById('recommendationsHistoryContent').innerHTML;
        expect(html).toContain("rerunRecommendationRun('failed-1')");
    });

    test('Re-run button is rendered for in-progress runs too', () => {
        renderRecommendationsHistoryList([
            { taskId: 'p-1', status: 'IN_PROGRESS', createdAt: '2026-05-01T00:00:00Z', requestedByUsername: 'admin', resultCount: 0 },
        ]);
        const html = document.getElementById('recommendationsHistoryContent').innerHTML;
        expect(html).toContain('Re-run');
        expect(html).toContain("rerunRecommendationRun('p-1')");
    });

    test('Re-run button label is "Re-run"', () => {
        renderRecommendationsHistoryList([
            { taskId: 't-1', status: 'DONE', createdAt: '2026-05-01T00:00:00Z', requestedByUsername: 'admin', resultCount: 1 },
        ]);
        const html = document.getElementById('recommendationsHistoryContent').innerHTML;
        expect(html).toContain('>Re-run<');
    });
});

// ---------------------------------------------------------------------------
// recommendations — rerunRecommendationRun()
// ---------------------------------------------------------------------------

function makeRerunRecommendationRun(fetchFn, refreshFn, toastFn) {
    return async function rerunRecommendationRun(taskId) {
        if (!taskId) return;
        try {
            const res = await fetchFn('/api/recommendations/runs/' + encodeURIComponent(taskId) + '/rerun', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            });
            if (res.status === 404) {
                toastFn('That past recommendation could not be found.');
                return;
            }
            if (res.status === 403) {
                toastFn('You need admin access to start a new recommendation run.');
                return;
            }
            if (!res.ok) {
                toastFn('Could not start a new recommendation run. Please try again.');
                return;
            }
            toastFn('A new recommendation run has started.');
            await refreshFn();
        } catch (e) {
            toastFn('Could not connect. Please try again.');
        }
    };
}

describe('rerunRecommendationRun()', () => {
    let fetchCalls;
    let refreshCalls;
    let toastMessages;

    function setup({ status = 202, ok = true, throws = false } = {}) {
        fetchCalls = [];
        refreshCalls = 0;
        toastMessages = [];
        const fetchFn = (url, opts) => {
            fetchCalls.push({ url, opts });
            if (throws) return Promise.reject(new Error('network down'));
            return Promise.resolve({
                status,
                ok,
                json: async () => ({ taskId: 'new-task' }),
            });
        };
        const refreshFn = async () => { refreshCalls++; };
        const toastFn = (msg) => { toastMessages.push(msg); };
        return makeRerunRecommendationRun(fetchFn, refreshFn, toastFn);
    }

    test('does nothing when taskId is empty', async () => {
        const rerun = setup();
        await rerun('');
        expect(fetchCalls).toHaveLength(0);
        expect(refreshCalls).toBe(0);
        expect(toastMessages).toHaveLength(0);
    });

    test('does nothing when taskId is null/undefined', async () => {
        const rerun = setup();
        await rerun(null);
        await rerun(undefined);
        expect(fetchCalls).toHaveLength(0);
    });

    test('posts to the rerun endpoint for the given taskId', async () => {
        const rerun = setup({ status: 202, ok: true });
        await rerun('abc-123');
        expect(fetchCalls).toHaveLength(1);
        expect(fetchCalls[0].url).toBe('/api/recommendations/runs/abc-123/rerun');
        expect(fetchCalls[0].opts.method).toBe('POST');
    });

    test('URL-encodes special characters in the taskId', async () => {
        const rerun = setup({ status: 202, ok: true });
        await rerun('weird id/with stuff');
        expect(fetchCalls[0].url).toBe('/api/recommendations/runs/weird%20id%2Fwith%20stuff/rerun');
    });

    test('on success, shows toast and refreshes the list', async () => {
        const rerun = setup({ status: 202, ok: true });
        await rerun('t-1');
        expect(toastMessages).toEqual(['A new recommendation run has started.']);
        expect(refreshCalls).toBe(1);
    });

    test('on 404, shows "not found" toast and does NOT refresh', async () => {
        const rerun = setup({ status: 404, ok: false });
        await rerun('t-missing');
        expect(toastMessages[0]).toContain('could not be found');
        expect(refreshCalls).toBe(0);
    });

    test('on 403, shows "admin access" toast and does NOT refresh', async () => {
        const rerun = setup({ status: 403, ok: false });
        await rerun('t-1');
        expect(toastMessages[0]).toContain('admin access');
        expect(refreshCalls).toBe(0);
    });

    test('on other failure status, shows generic toast', async () => {
        const rerun = setup({ status: 500, ok: false });
        await rerun('t-1');
        expect(toastMessages[0]).toContain('Could not start a new recommendation run');
        expect(refreshCalls).toBe(0);
    });

    test('on network failure, shows connection toast', async () => {
        const rerun = setup({ throws: true });
        await rerun('t-1');
        expect(toastMessages[0]).toContain('Could not connect');
        expect(refreshCalls).toBe(0);
    });
});

// ---------------------------------------------------------------------------
// recommendations — renderActiveRecommendations() (acted-on removal)
// ---------------------------------------------------------------------------

function renderActiveRecommendations(data) {
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

describe('renderActiveRecommendations()', () => {
    beforeEach(() => {
        document.body.innerHTML = '<div id="recommendationsContent"></div>';
    });

    test('renders one card per recommendation', () => {
        renderActiveRecommendations([
            { id: 11, title: 'A', description: 'desc A' },
            { id: 12, title: 'B', description: 'desc B' },
        ]);
        const html = document.getElementById('recommendationsContent').innerHTML;
        expect((html.match(/recommendation-card/g) || []).length).toBe(2);
    });

    test('embeds the result id as a data attribute so cards can be removed individually', () => {
        renderActiveRecommendations([
            { id: 11, title: 'A', description: 'desc A' },
            { id: 12, title: 'B', description: 'desc B' },
        ]);
        const html = document.getElementById('recommendationsContent').innerHTML;
        expect(html).toContain('data-result-id="11"');
        expect(html).toContain('data-result-id="12"');
    });

    test('shows an empty-state message when nothing is left to act on', () => {
        renderActiveRecommendations([]);
        const html = document.getElementById('recommendationsContent').innerHTML;
        expect(html).toContain('No outstanding recommendations');
    });

    test('treats null data as empty', () => {
        renderActiveRecommendations(null);
        const html = document.getElementById('recommendationsContent').innerHTML;
        expect(html).toContain('No outstanding recommendations');
    });

    test('escapes HTML in the title and description', () => {
        renderActiveRecommendations([
            { id: 1, title: '<script>x</script>', description: '<img onerror=1>' },
        ]);
        const html = document.getElementById('recommendationsContent').innerHTML;
        expect(html).not.toContain('<script>x</script>');
        expect(html).toContain('&lt;script&gt;');
    });
});

// ---------------------------------------------------------------------------
// recommendations — prefillFromRecommendation() now remembers the result id
// ---------------------------------------------------------------------------

function makePrefillFromRecommendationWithId(state, closeModalFn, navigateFn) {
    return function prefillFromRecommendation(index) {
        const rec = state.recommendations && state.recommendations[index];
        if (!rec) return;
        state.pendingRecommendationResultId = (rec.id != null) ? rec.id : null;
        closeModalFn();
        navigateFn('create');
        document.getElementById('createTitle').value = rec.title;
        document.getElementById('createDescription').value = rec.description;
    };
}

describe('prefillFromRecommendation() — remembers the recommendation id', () => {
    let state;
    beforeEach(() => {
        state = {
            recommendations: [{ id: 42, title: 'T', description: 'D' }],
            pendingRecommendationResultId: null,
        };
        document.body.innerHTML = `
            <div id="recommendationsModal"></div>
            <input id="createTitle" />
            <textarea id="createDescription"></textarea>
        `;
    });

    test('stores the recommendation result id so it can be marked acted-on later', () => {
        const prefill = makePrefillFromRecommendationWithId(state, () => {}, () => {});
        prefill(0);
        expect(state.pendingRecommendationResultId).toBe(42);
    });

    test('stores null when the recommendation has no id', () => {
        state.recommendations = [{ title: 'T', description: 'D' }];
        const prefill = makePrefillFromRecommendationWithId(state, () => {}, () => {});
        prefill(0);
        expect(state.pendingRecommendationResultId).toBeNull();
    });

    test('does not touch pending id when called with an out-of-range index', () => {
        state.pendingRecommendationResultId = 99;
        const prefill = makePrefillFromRecommendationWithId(state, () => {}, () => {});
        prefill(7);
        expect(state.pendingRecommendationResultId).toBe(99);
    });
});

// ---------------------------------------------------------------------------
// recommendations — markRecommendationActedOn()
// ---------------------------------------------------------------------------

function makeMarkRecommendationActedOn(state, fetchFn) {
    return async function markRecommendationActedOn(suggestionId) {
        const resultId = state.pendingRecommendationResultId;
        state.pendingRecommendationResultId = null;
        if (resultId == null || suggestionId == null) return;
        try {
            await fetchFn('/api/recommendations/results/' + encodeURIComponent(resultId) + '/act-on', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ suggestionId })
            });
        } catch (e) {
            // best effort
        }
    };
}

describe('markRecommendationActedOn()', () => {
    let state;
    let fetchCalls;

    function setupFetch({ throws = false } = {}) {
        fetchCalls = [];
        return (url, opts) => {
            fetchCalls.push({ url, opts });
            if (throws) return Promise.reject(new Error('network down'));
            return Promise.resolve({ ok: true, status: 200, json: async () => ({}) });
        };
    }

    beforeEach(() => {
        state = { pendingRecommendationResultId: null };
    });

    test('does nothing when no recommendation id is pending', async () => {
        const fetchFn = setupFetch();
        const mark = makeMarkRecommendationActedOn(state, fetchFn);
        await mark(123);
        expect(fetchCalls).toHaveLength(0);
    });

    test('does nothing when suggestionId is null', async () => {
        state.pendingRecommendationResultId = 7;
        const fetchFn = setupFetch();
        const mark = makeMarkRecommendationActedOn(state, fetchFn);
        await mark(null);
        expect(fetchCalls).toHaveLength(0);
    });

    test('does nothing when suggestionId is undefined', async () => {
        state.pendingRecommendationResultId = 7;
        const fetchFn = setupFetch();
        const mark = makeMarkRecommendationActedOn(state, fetchFn);
        await mark(undefined);
        expect(fetchCalls).toHaveLength(0);
    });

    test('posts to the act-on endpoint with the suggestion id in the body', async () => {
        state.pendingRecommendationResultId = 42;
        const fetchFn = setupFetch();
        const mark = makeMarkRecommendationActedOn(state, fetchFn);
        await mark(99);
        expect(fetchCalls).toHaveLength(1);
        expect(fetchCalls[0].url).toBe('/api/recommendations/results/42/act-on');
        expect(fetchCalls[0].opts.method).toBe('POST');
        expect(JSON.parse(fetchCalls[0].opts.body)).toEqual({ suggestionId: 99 });
    });

    test('clears the pending id after marking, so the next create does not double-mark', async () => {
        state.pendingRecommendationResultId = 42;
        const fetchFn = setupFetch();
        const mark = makeMarkRecommendationActedOn(state, fetchFn);
        await mark(99);
        expect(state.pendingRecommendationResultId).toBeNull();

        // A second create (with no recommendation in flight) must not call the endpoint.
        await mark(100);
        expect(fetchCalls).toHaveLength(1);
    });

    test('clears pending id even when no suggestion id is passed', async () => {
        state.pendingRecommendationResultId = 42;
        const fetchFn = setupFetch();
        const mark = makeMarkRecommendationActedOn(state, fetchFn);
        await mark(null);
        expect(state.pendingRecommendationResultId).toBeNull();
    });

    test('swallows network errors silently so the suggestion-create flow is unaffected', async () => {
        state.pendingRecommendationResultId = 42;
        const fetchFn = setupFetch({ throws: true });
        const mark = makeMarkRecommendationActedOn(state, fetchFn);
        await expect(mark(99)).resolves.toBeUndefined();
    });

    test('URL-encodes the result id', async () => {
        // The id is numeric in practice, but encodeURIComponent should still
        // be applied to keep this resilient to any future change in id type.
        state.pendingRecommendationResultId = 'a/b';
        const fetchFn = setupFetch();
        const mark = makeMarkRecommendationActedOn(state, fetchFn);
        await mark(1);
        expect(fetchCalls[0].url).toBe('/api/recommendations/results/a%2Fb/act-on');
    });
});

// ---------------------------------------------------------------------------
// recommendations — refreshRecommendationsHistory() and startFreshRecommendationsRun()
// ---------------------------------------------------------------------------

function makeRefreshRecommendationsHistory(loadFn, showFilterBarFn) {
    return async function refreshRecommendationsHistory() {
        showFilterBarFn(true);
        await loadFn();
    };
}

function makeStartFreshRecommendationsRun(closeFn, fetchFn) {
    return function startFreshRecommendationsRun() {
        closeFn();
        return fetchFn();
    };
}

describe('refreshRecommendationsHistory()', () => {
    test('shows the filter bar and reloads the list', async () => {
        let loaded = 0;
        let filterBarShown = null;
        const refresh = makeRefreshRecommendationsHistory(
            async () => { loaded++; },
            (visible) => { filterBarShown = visible; }
        );
        await refresh();
        expect(loaded).toBe(1);
        expect(filterBarShown).toBe(true);
    });
});

describe('startFreshRecommendationsRun()', () => {
    test('closes the history modal and starts a new recommendation run', () => {
        let closed = 0;
        let started = 0;
        const startFresh = makeStartFreshRecommendationsRun(
            () => { closed++; },
            () => { started++; return 'fetch-promise'; }
        );
        const result = startFresh();
        expect(closed).toBe(1);
        expect(started).toBe(1);
        expect(result).toBe('fetch-promise');
    });
});

// ---------------------------------------------------------------------------
// projectDefinition — formatFileSize()
// ---------------------------------------------------------------------------

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
}

describe('formatFileSize()', () => {
    test('formats bytes under 1KB as bytes', () => {
        expect(formatFileSize(512)).toBe('512 B');
        expect(formatFileSize(0)).toBe('0 B');
        expect(formatFileSize(1023)).toBe('1023 B');
    });

    test('formats values between 1KB and 1MB as KB', () => {
        expect(formatFileSize(1024)).toBe('1.0 KB');
        expect(formatFileSize(2048)).toBe('2.0 KB');
        expect(formatFileSize(1536)).toBe('1.5 KB');
    });

    test('formats values 1MB and above as MB', () => {
        expect(formatFileSize(1048576)).toBe('1.0 MB');
        expect(formatFileSize(2097152)).toBe('2.0 MB');
        expect(formatFileSize(1572864)).toBe('1.5 MB');
    });
});

// ---------------------------------------------------------------------------
// projectDefinition — showProjectDefinitionModal() DOM rendering
// ---------------------------------------------------------------------------

function makeShowProjectDefinitionModal() {
    let _pdCurrentSessionId = null;
    let _pdFullContent = null;

    function renderProjectDefinitionComplete(state) {
        const completeView = document.getElementById('pd-complete-view');
        if (!completeView) return;
        completeView.style.display = '';

        const prSection = document.getElementById('pd-pr-section');
        const prOpenSection = document.getElementById('pd-pr-open-section');
        const prLink = document.getElementById('pd-pr-link');
        const prOpenLink = document.getElementById('pd-pr-open-link');
        const contentText = document.getElementById('pd-content-text');
        const contentExpander = document.getElementById('pd-content-expander');
        const errSection = document.getElementById('pd-error-section');
        const actionsEl = document.getElementById('pd-actions');

        if (errSection) errSection.style.display = 'none';

        if (state.status === 'PR_OPEN') {
            if (prSection) prSection.style.display = 'none';
            if (prOpenSection) prOpenSection.style.display = '';
            if (prOpenLink && state.prUrl) prOpenLink.href = state.prUrl;
        } else if (state.prUrl) {
            if (prOpenSection) prOpenSection.style.display = 'none';
            if (prSection) prSection.style.display = '';
            if (prLink) prLink.href = state.prUrl;
        } else {
            if (prSection) prSection.style.display = 'none';
            if (prOpenSection) prOpenSection.style.display = 'none';
        }

        if (state.generatedContent && contentText) {
            const preview = state.generatedContent.substring(0, 500);
            contentText.textContent = preview;
            if (state.generatedContent.length > 500) {
                _pdFullContent = state.generatedContent;
                if (contentExpander) contentExpander.style.display = '';
            } else {
                if (contentExpander) contentExpander.style.display = 'none';
            }
        }

        if (actionsEl) {
            const hasContent = !!(state.generatedContent);
            const downloadBtn = document.getElementById('pd-download-btn');
            if (downloadBtn) downloadBtn.style.display = hasContent ? '' : 'none';
            actionsEl.style.display = 'flex';
        }

        const btn = document.getElementById('project-def-btn');
        if (btn) btn.textContent = 'View Definition';
    }

    function showProjectDefinitionModal(state) {
        const modal = document.getElementById('project-def-modal');
        if (!modal) return;
        modal.style.display = '';

        const titleEl = document.getElementById('pd-modal-title');
        if (titleEl) titleEl.textContent = state.isEdit ? 'Update Project Definition' : 'Project Definition';

        const bar = document.getElementById('pd-progress-bar');
        if (bar) bar.style.width = (state.progressPercent || 0) + '%';

        const statusEl = document.getElementById('pd-status-text');
        const spinner = document.getElementById('pd-spinner');
        const spinnerText = document.getElementById('pd-spinner-text');
        const questionArea = document.getElementById('pd-question-area');
        const completeView = document.getElementById('pd-complete-view');
        const noDefActions = document.getElementById('pd-no-def-actions');
        const actionsEl = document.getElementById('pd-actions');

        if (spinner) spinner.style.display = 'none';
        if (questionArea) questionArea.style.display = '';
        if (completeView) completeView.style.display = 'none';
        if (noDefActions) noDefActions.style.display = 'none';
        if (actionsEl) actionsEl.style.display = 'none';

        if (state.status === 'GENERATING') {
            if (statusEl) statusEl.textContent = 'Creating your project definition document...';
            if (spinner) spinner.style.display = '';
            if (spinnerText) spinnerText.textContent = 'Generating — this may take a moment';
            if (questionArea) questionArea.style.display = 'none';
        } else if (state.status === 'SAVING') {
            if (statusEl) statusEl.textContent = 'Saving and opening a pull request...';
            if (spinner) spinner.style.display = '';
            if (questionArea) questionArea.style.display = 'none';
        } else if (state.status === 'COMPLETED') {
            if (statusEl) statusEl.textContent = 'Complete';
            if (questionArea) questionArea.style.display = 'none';
            renderProjectDefinitionComplete(state);
        } else if (state.status === 'PR_OPEN') {
            if (statusEl) statusEl.textContent = 'Pull request is open and waiting for review';
            if (questionArea) questionArea.style.display = 'none';
            renderProjectDefinitionComplete(state);
        } else if (state.status === 'FAILED') {
            if (statusEl) statusEl.textContent = 'Something went wrong.';
            if (questionArea) questionArea.style.display = 'none';
            if (completeView) completeView.style.display = '';
            const errSection = document.getElementById('pd-error-section');
            if (errSection) {
                errSection.style.display = '';
            }
        } else {
            const questionEl = document.getElementById('pd-question');
            if (questionEl) questionEl.textContent = state.currentQuestion || '';

            const optionsEl = document.getElementById('pd-options');
            const textInput = document.getElementById('pd-text-input');
            const textSubmit = document.getElementById('pd-text-submit-area');

            if ((state.questionType === 'MULTIPLE_CHOICE' || state.questionType === 'multiple_choice') && state.options && state.options.length > 0) {
                if (textInput) textInput.style.display = 'none';
                if (textSubmit) textSubmit.style.display = 'none';
                if (optionsEl) {
                    optionsEl.innerHTML = '';
                    state.options.forEach(opt => {
                        const btn = document.createElement('button');
                        btn.className = 'btn btn-outline';
                        btn.textContent = opt;
                        optionsEl.appendChild(btn);
                    });
                }
            } else {
                if (optionsEl) optionsEl.innerHTML = '';
                if (textInput) { textInput.style.display = ''; textInput.value = ''; }
                if (textSubmit) textSubmit.style.display = '';
            }

            let label = '';
            if (state.status === 'ACTIVE') {
                label = state.isEdit
                    ? 'Review and update your existing project definition.'
                    : 'Answer the questions below to define your project.';
            }
            if (statusEl) statusEl.textContent = label;
        }

        _pdCurrentSessionId = state.sessionId;
    }

    return { showProjectDefinitionModal, renderProjectDefinitionComplete };
}

describe('showProjectDefinitionModal()', () => {
    function makeModalDom() {
        document.body.innerHTML = `
            <div id="project-def-modal" style="display:none">
                <div id="pd-modal-title"></div>
                <div id="pd-progress-bar" style="width:0%"></div>
                <div id="pd-status-text"></div>
                <div id="pd-spinner"></div>
                <div id="pd-spinner-text"></div>
                <div id="pd-question-area">
                    <div id="pd-question"></div>
                    <div id="pd-options"></div>
                    <input id="pd-text-input" />
                    <div id="pd-text-submit-area"></div>
                    <button id="pd-submit-btn"></button>
                </div>
                <div id="pd-complete-view">
                    <div id="pd-pr-section"></div>
                    <div id="pd-pr-open-section"></div>
                    <a id="pd-pr-link" href=""></a>
                    <a id="pd-pr-open-link" href=""></a>
                    <div id="pd-content-text"></div>
                    <div id="pd-content-expander"></div>
                    <div id="pd-error-section"></div>
                    <div id="pd-actions"><button id="pd-download-btn"></button></div>
                </div>
                <div id="pd-no-def-actions"></div>
                <button id="project-def-btn">Project Definition</button>
            </div>
        `;
    }

    test('makes the modal visible', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'ACTIVE', sessionId: 'abc', currentQuestion: 'Q1?' });
        expect(document.getElementById('project-def-modal').style.display).toBe('');
    });

    test('shows spinner and hides question area in GENERATING status', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'GENERATING', sessionId: 'abc' });
        expect(document.getElementById('pd-spinner').style.display).toBe('');
        expect(document.getElementById('pd-question-area').style.display).toBe('none');
        expect(document.getElementById('pd-status-text').textContent).toContain('Creating');
    });

    test('shows spinner in SAVING status', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'SAVING', sessionId: 'abc' });
        expect(document.getElementById('pd-spinner').style.display).toBe('');
    });

    test('shows ACTIVE question in question area', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'ACTIVE', sessionId: 'x', currentQuestion: 'What is your project?' });
        expect(document.getElementById('pd-question').textContent).toBe('What is your project?');
    });

    test('renders multiple choice options as buttons', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({
            status: 'ACTIVE', sessionId: 'x', currentQuestion: 'Pick one:',
            questionType: 'MULTIPLE_CHOICE', options: ['Option A', 'Option B', 'Option C']
        });
        const optionsEl = document.getElementById('pd-options');
        expect(optionsEl.querySelectorAll('button').length).toBe(3);
        expect(optionsEl.textContent).toContain('Option A');
        expect(optionsEl.textContent).toContain('Option B');
    });

    test('sets status text to "Complete" for COMPLETED status', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'COMPLETED', sessionId: 'x', generatedContent: 'Content here' });
        expect(document.getElementById('pd-status-text').textContent).toBe('Complete');
    });

    test('sets correct status text for PR_OPEN', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'PR_OPEN', sessionId: 'x', prUrl: 'https://github.com/pr/1' });
        expect(document.getElementById('pd-status-text').textContent).toContain('Pull request');
    });

    test('shows error section for FAILED status', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'FAILED', sessionId: 'x', errorMessage: 'AI timed out.' });
        expect(document.getElementById('pd-status-text').textContent).toContain('went wrong');
        expect(document.getElementById('pd-error-section').style.display).toBe('');
    });

    test('sets title to "Update Project Definition" for edit mode', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'ACTIVE', sessionId: 'x', isEdit: true, currentQuestion: 'Q?' });
        expect(document.getElementById('pd-modal-title').textContent).toBe('Update Project Definition');
    });

    test('sets title to "Project Definition" for new mode', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'ACTIVE', sessionId: 'x', isEdit: false, currentQuestion: 'Q?' });
        expect(document.getElementById('pd-modal-title').textContent).toBe('Project Definition');
    });

    test('sets progress bar width from progressPercent', () => {
        makeModalDom();
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        showProjectDefinitionModal({ status: 'ACTIVE', sessionId: 'x', progressPercent: 60, currentQuestion: 'Q?' });
        expect(document.getElementById('pd-progress-bar').style.width).toBe('60%');
    });

    test('does nothing when modal element does not exist', () => {
        document.body.innerHTML = '';
        const { showProjectDefinitionModal } = makeShowProjectDefinitionModal();
        expect(() => showProjectDefinitionModal({ status: 'ACTIVE', sessionId: 'x' })).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// projectDefinition — renderProjectDefinitionComplete()
// ---------------------------------------------------------------------------

describe('renderProjectDefinitionComplete()', () => {
    function makeCompleteDom() {
        document.body.innerHTML = `
            <div id="pd-complete-view" style="display:none">
                <div id="pd-pr-section" style="display:none"></div>
                <div id="pd-pr-open-section" style="display:none"></div>
                <a id="pd-pr-link" href=""></a>
                <a id="pd-pr-open-link" href=""></a>
                <div id="pd-content-text"></div>
                <div id="pd-content-expander" style="display:none"></div>
                <div id="pd-error-section"></div>
                <div id="pd-actions" style="display:none"><button id="pd-download-btn"></button></div>
            </div>
            <button id="project-def-btn">Project Definition</button>
        `;
    }

    test('makes completeView visible', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        renderProjectDefinitionComplete({ status: 'COMPLETED', generatedContent: 'Hello' });
        expect(document.getElementById('pd-complete-view').style.display).toBe('');
    });

    test('shows PR section when prUrl is present but status is not PR_OPEN', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        renderProjectDefinitionComplete({ status: 'COMPLETED', prUrl: 'https://github.com/pr/1', generatedContent: 'x' });
        expect(document.getElementById('pd-pr-section').style.display).toBe('');
        expect(document.getElementById('pd-pr-open-section').style.display).toBe('none');
        expect(document.getElementById('pd-pr-link').href).toContain('github.com/pr/1');
    });

    test('shows PR open section when status is PR_OPEN', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        renderProjectDefinitionComplete({ status: 'PR_OPEN', prUrl: 'https://github.com/pr/2', generatedContent: 'x' });
        expect(document.getElementById('pd-pr-open-section').style.display).toBe('');
        expect(document.getElementById('pd-pr-section').style.display).toBe('none');
    });

    test('hides both PR sections when no prUrl', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        renderProjectDefinitionComplete({ status: 'COMPLETED', generatedContent: 'Hello' });
        expect(document.getElementById('pd-pr-section').style.display).toBe('none');
        expect(document.getElementById('pd-pr-open-section').style.display).toBe('none');
    });

    test('renders content preview up to 500 chars', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        const shortContent = 'Short content.';
        renderProjectDefinitionComplete({ status: 'COMPLETED', generatedContent: shortContent });
        expect(document.getElementById('pd-content-text').textContent).toBe(shortContent);
    });

    test('shows expander when content exceeds 500 chars', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        const longContent = 'x'.repeat(501);
        renderProjectDefinitionComplete({ status: 'COMPLETED', generatedContent: longContent });
        expect(document.getElementById('pd-content-expander').style.display).toBe('');
    });

    test('hides expander when content is 500 chars or fewer', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        renderProjectDefinitionComplete({ status: 'COMPLETED', generatedContent: 'Short.' });
        expect(document.getElementById('pd-content-expander').style.display).toBe('none');
    });

    test('changes project def button text to "View Definition"', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        renderProjectDefinitionComplete({ status: 'COMPLETED', generatedContent: 'hello' });
        expect(document.getElementById('project-def-btn').textContent).toBe('View Definition');
    });

    test('shows download button when content exists', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        renderProjectDefinitionComplete({ status: 'COMPLETED', generatedContent: 'content' });
        expect(document.getElementById('pd-download-btn').style.display).toBe('');
    });

    test('hides download button when no content', () => {
        makeCompleteDom();
        const { renderProjectDefinitionComplete } = makeShowProjectDefinitionModal();
        renderProjectDefinitionComplete({ status: 'COMPLETED' });
        expect(document.getElementById('pd-download-btn').style.display).toBe('none');
    });
});

// ---------------------------------------------------------------------------
// projectDefinition — closeProjectDefinitionModal()
// ---------------------------------------------------------------------------

function closeProjectDefinitionModal() {
    const modal = document.getElementById('project-def-modal');
    if (modal) modal.style.display = 'none';
}

describe('closeProjectDefinitionModal()', () => {
    test('hides the modal', () => {
        document.body.innerHTML = '<div id="project-def-modal" style="display:block"></div>';
        closeProjectDefinitionModal();
        expect(document.getElementById('project-def-modal').style.display).toBe('none');
    });

    test('does not throw when modal does not exist', () => {
        document.body.innerHTML = '';
        expect(() => closeProjectDefinitionModal()).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// projectDefinition — clearImportFile()
// ---------------------------------------------------------------------------

function makeClearImportFile() {
    let _importFileContent = 'some content';
    function clearImportFile(event) {
        if (event) event.preventDefault();
        _importFileContent = null;
        const fileInfo = document.getElementById('pd-import-file-info');
        if (fileInfo) fileInfo.style.display = 'none';
        const fileInput = document.getElementById('pd-import-file');
        if (fileInput) fileInput.value = '';
    }
    return { clearImportFile, getContent: () => _importFileContent };
}

describe('clearImportFile()', () => {
    beforeEach(() => {
        document.body.innerHTML = `
            <div id="pd-import-file-info" style="display:block"></div>
            <input id="pd-import-file" value="somefile.md" />
        `;
    });

    test('hides file info element', () => {
        const { clearImportFile } = makeClearImportFile();
        clearImportFile(null);
        expect(document.getElementById('pd-import-file-info').style.display).toBe('none');
    });

    test('clears file input value', () => {
        const { clearImportFile } = makeClearImportFile();
        clearImportFile(null);
        expect(document.getElementById('pd-import-file').value).toBe('');
    });

    test('calls preventDefault on the event if provided', () => {
        const { clearImportFile } = makeClearImportFile();
        const event = { preventDefault: jest.fn() };
        clearImportFile(event);
        expect(event.preventDefault).toHaveBeenCalled();
    });

    test('does not throw when called without event', () => {
        const { clearImportFile } = makeClearImportFile();
        expect(() => clearImportFile(null)).not.toThrow();
    });
});
