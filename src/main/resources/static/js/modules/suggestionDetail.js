import { state } from './state.js';
import { api } from './api.js';
import { esc, timeAgo, formatContent } from './utils.js';
import { renderTasks } from './tasks.js';
import { renderExpertReview, updateExpertReview, loadReviewSummary, showExpertClarificationWizard } from './expertReview.js';
import { showClarificationWizard, hideClarificationWizard, loadClarificationQuestions } from './clarification.js';
import { loadSuggestions } from './suggestions.js';

// Callbacks for functions provided by modules not yet created in this phase.
// Populated via registerSuggestionDetailCallbacks() once those modules are ready.
const _callbacks = {
    connectWs: () => {},
    showToast: (msg) => { console.warn('showToast not registered:', msg); },
    updateApprovalBanner: () => {},
};

export function registerSuggestionDetailCallbacks(cbs) {
    Object.assign(_callbacks, cbs);
}

/**
 * Render the plan section, choosing the user-facing or technical version
 * based on state.showTechnicalPlan. The "Show technical detail" toggle is
 * only meaningful when the technical and display fields actually differ
 * somewhere in the suggestion or its tasks — hidden otherwise.
 */
function renderPlanText(suggestion) {
    const planEl = document.getElementById('detailPlan');
    const planText = document.getElementById('detailPlanText');
    if (!planEl || !planText) return;
    if (!(suggestion.planDisplaySummary || suggestion.planSummary)) {
        planEl.style.display = 'none';
        return;
    }
    planEl.style.display = '';
    if (state.showTechnicalPlan) {
        if (suggestion.planSummary) {
            planText.textContent = suggestion.planSummary;
            planText.style.fontStyle = '';
            planText.style.color = '';
        } else {
            planText.textContent = '[no low-level plan summary stored]'
                + (suggestion.planDisplaySummary ? ' — high-level summary is set but low-level is empty' : '');
            planText.style.fontStyle = 'italic';
            planText.style.color = 'var(--text-muted)';
        }
    } else {
        planText.textContent = suggestion.planDisplaySummary || suggestion.planSummary || '';
        planText.style.fontStyle = '';
        planText.style.color = '';
    }

    // Only show the toggle if there's an actual technical/friendly divergence
    // somewhere — otherwise flipping it would produce identical output.
    const tasksDiffer = (state.tasks || []).some(t =>
        (t.title && t.displayTitle && t.title !== t.displayTitle)
        || (t.description && t.displayDescription && t.description !== t.displayDescription));
    const summariesDiffer = suggestion.planSummary
            && suggestion.planDisplaySummary
            && suggestion.planSummary !== suggestion.planDisplaySummary;
    const toggleWrap = document.getElementById('detailPlanDetailToggleWrap');
    if (toggleWrap) {
        toggleWrap.style.display = (tasksDiffer || summariesDiffer) ? '' : 'none';
    }
    const toggleInput = document.getElementById('detailPlanDetailToggle');
    if (toggleInput) toggleInput.checked = !!state.showTechnicalPlan;
}

/**
 * Flip between user-facing and technical plan rendering. Wired to the
 * "Show technical detail" checkbox above the plan card.
 */
export function toggleDetailedPlan(checked) {
    state.showTechnicalPlan = !!checked;
    if (state.currentSuggestionData) renderPlanText(state.currentSuggestionData);
    renderTasks();
}

export async function loadDetail(id) {
    state.currentSuggestion = id;
    const suggestion = await api('/suggestions/' + id);
    const messages = await api('/suggestions/' + id + '/messages');
    const tasks = await api('/suggestions/' + id + '/tasks');
    state.tasks = tasks || [];

    document.getElementById('detailTitle').textContent = suggestion.title;
    document.getElementById('detailDescription').textContent = suggestion.description;
    document.getElementById('detailMeta').innerHTML =
        `<span>by ${esc(suggestion.authorName || 'Anonymous')}</span>` +
        `<span>${timeAgo(suggestion.createdAt)}</span>`;

    state.currentStatus = suggestion.status;

    const statusEl = document.getElementById('detailStatus');
    statusEl.textContent = suggestion.status.replace('_', ' ');
    statusEl.className = 'status-badge status-' + suggestion.status;

    const priorityLabel = suggestion.priority || 'MEDIUM';
    const priorityBadge = document.getElementById('detailPriorityBadge');
    if (priorityBadge) {
        priorityBadge.textContent = priorityLabel;
        priorityBadge.className = 'priority-badge priority-' + priorityLabel;
    }

    const isAdmin = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
    const priorityAdminEl = document.getElementById('detailPriorityAdmin');
    const prioritySelectEl = document.getElementById('detailPrioritySelect');
    if (priorityAdminEl && prioritySelectEl) {
        priorityAdminEl.style.display = isAdmin ? '' : 'none';
        prioritySelectEl.value = priorityLabel;
    }

    document.getElementById('detailUpVotes').textContent = suggestion.upVotes;
    document.getElementById('detailDownVotes').textContent = suggestion.downVotes;
    document.getElementById('detailVoteSection').style.display =
        state.settings.allowVoting ? '' : 'none';
    if (state.settings.allowVoting) {
        const isAdminForVote = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
        const canVote = isAdminForVote || state.permissions.includes('VOTE');
        document.getElementById('voteUpBtn').style.display = canVote ? '' : 'none';
        document.getElementById('voteDownBtn').style.display = canVote ? '' : 'none';
    }

    const phaseEl = document.getElementById('detailPhase');
    const phaseText = document.getElementById('detailPhaseText');
    const phaseFinished = ['DENIED', 'TIMED_OUT', 'MERGED'].includes(suggestion.status) ||
        (suggestion.status === 'DEV_COMPLETE' && (!suggestion.currentPhase || suggestion.currentPhase.startsWith('Implementation completed')));
    if (suggestion.currentPhase && !phaseFinished) {
        phaseEl.style.display = '';
        phaseText.textContent = suggestion.currentPhase;
    } else {
        phaseEl.style.display = 'none';
    }

    state.currentSuggestionData = suggestion;
    renderPlanText(suggestion);

    // Queue status for APPROVED (queued) suggestions
    const queueInfoEl = document.getElementById('detailQueueInfo');
    if (queueInfoEl) {
        if (suggestion.status === 'APPROVED') {
            api('/suggestions/execution-queue').then(q => {
                state.executionQueue = q;
                const pos = (q.queued || []).find(item => item.id === id);
                if (pos) {
                    queueInfoEl.style.display = '';
                    queueInfoEl.innerHTML = '<strong>Queue position:</strong> ' + pos.position +
                        ' of ' + q.queuedCount + ' &mdash; ' + q.activeCount + '/' + q.maxConcurrent + ' slots in use';
                } else {
                    queueInfoEl.style.display = 'none';
                }
            });
        } else {
            queueInfoEl.style.display = 'none';
        }
    }

    // Expert review status — fetch current progress if in EXPERT_REVIEW
    if (suggestion.status === 'EXPERT_REVIEW' && suggestion.expertReviewStep != null) {
        state.expertReview.active = true;
        api('/suggestions/' + id + '/expert-review-status').then(data => {
            if (data && data.experts) {
                updateExpertReview(data);
            }
        });
    } else {
        state.expertReview.active = false;
    }
    renderExpertReview();

    // Expert review summary panel
    loadReviewSummary(id, suggestion.expertReviewNotes);

    // Tasks
    renderTasks();

    // PR link
    const prEl = document.getElementById('detailPr');
    const prLink = document.getElementById('detailPrLink');
    if (suggestion.prUrl) {
        prEl.style.display = '';
        prLink.href = suggestion.prUrl;
        prLink.textContent = suggestion.prUrl;
    } else {
        prEl.style.display = 'none';
    }

    // Changelog
    const changelogEl = document.getElementById('detailChangelog');
    const changelogText = document.getElementById('detailChangelogText');
    if (suggestion.changelogEntry) {
        changelogEl.style.display = '';
        changelogText.textContent = suggestion.changelogEntry;
    } else {
        changelogEl.style.display = 'none';
    }

    // Per-suggestion cost summary (admin only). Fetched lazily so failures
    // don't block the rest of the detail render.
    const costEl = document.getElementById('detailCostSummary');
    if (costEl) {
        costEl.style.display = 'none';
        costEl.textContent = '';
        if (isAdmin) {
            api('/costs/suggestion/' + id).then(cost => {
                if (!cost || cost.error || cost.reviewCount === 0) return;
                costEl.textContent = 'Claude spend on this suggestion: '
                    + (cost.displayCostUsd || ('$' + (cost.totalCostUsd ?? 0)))
                    + '  ·  ' + cost.reviewCount + ' expert review'
                    + (cost.reviewCount === 1 ? '' : 's')
                    + '  ·  ' + (cost.totalTokens || 0).toLocaleString() + ' tokens';
                costEl.style.display = '';
            }).catch(() => { /* ignore */ });
        }
    }

    // Admin actions
    const canApprove = ['PLANNED', 'DISCUSSING'].includes(suggestion.status);
    const canForceReApproval = ['PLANNED', 'APPROVED'].includes(suggestion.status);
    const canApprovePlan = isAdmin && suggestion.status === 'PLAN_PROPOSED';
    document.getElementById('adminActions').style.display =
        (isAdmin && (canApprove || canForceReApproval || canApprovePlan)) ? '' : 'none';
    document.getElementById('forceReApprovalBtn').style.display =
        (isAdmin && canForceReApproval) ? '' : 'none';

    // Plan-review block: approve plan + request changes + AI-review status badge.
    const planReviewActions = document.getElementById('planReviewActions');
    if (planReviewActions) {
        planReviewActions.style.display = canApprovePlan ? '' : 'none';
    }
    const approvePlanBtn = document.getElementById('approvePlanBtn');
    if (approvePlanBtn) {
        // Label reflects what the next click will trigger.
        approvePlanBtn.textContent = suggestion.expertsApprovedCurrentPlan
            ? 'Approve plan → generate tasks'
            : 'Approve plan → start AI expert review';
    }
    const planReviewBadge = document.getElementById('planReviewBadge');
    if (planReviewBadge && canApprovePlan) {
        if (suggestion.expertsApprovedCurrentPlan) {
            planReviewBadge.innerHTML =
                '<span style="display:inline-block;padding:0.15rem 0.55rem;background:#16a34a1a;color:#16a34a;border:1px solid #16a34a55;border-radius:999px;font-size:0.75rem;font-weight:600">✓ AI experts approved this plan</span>'
                + ' <span style="color:var(--text-muted);font-size:0.8rem">— approving will generate tasks.</span>';
        } else {
            planReviewBadge.innerHTML =
                '<span style="display:inline-block;padding:0.15rem 0.55rem;background:#d976061a;color:#d97706;border:1px solid #d9770655;border-radius:999px;font-size:0.75rem;font-weight:600">⟳ AI experts have not yet reviewed this plan version</span>'
                + ' <span style="color:var(--text-muted);font-size:0.8rem">— approving will send it to AI experts.</span>';
        }
    }
    // Always reset the textarea visibility when the view changes.
    const requestBox = document.getElementById('requestPlanChangesBox');
    if (requestBox && !canApprovePlan) requestBox.style.display = 'none';

    // Retry PR action
    const canRetryPr = isAdmin && suggestion.currentPhase === 'Done — review request failed';
    document.getElementById('retryPrActions').style.display = canRetryPr ? '' : 'none';

    // Retry merge action — visible while a PR is open and not yet merged
    const canRetryMerge = isAdmin && suggestion.prNumber
            && suggestion.status !== 'MERGED' && suggestion.status !== 'DENIED';
    const retryMergeActions = document.getElementById('retryMergeActions');
    if (retryMergeActions) retryMergeActions.style.display = canRetryMerge ? '' : 'none';

    // Retry execution action
    const canRetryExecution = isAdmin && suggestion.currentPhase && suggestion.currentPhase.includes('can retry');
    document.getElementById('retryExecutionActions').style.display = canRetryExecution ? '' : 'none';

    // Retry clarification — visible when the AI call after answers exploded.
    const canRetryClarification = isAdmin && suggestion.currentPhase
            && suggestion.currentPhase.includes('clarification call failed');
    const retryClarificationActions = document.getElementById('retryClarificationActions');
    if (retryClarificationActions) {
        retryClarificationActions.style.display = canRetryClarification ? '' : 'none';
    }

    // Resume from last successful step — keeps completed work, reruns the rest.
    // Shown while the plan is mid-execution OR when all tasks finished but the
    // commit/push/PR pipeline failed (status DEV_COMPLETE + a "failed" phase).
    const stuckOnPostTask = suggestion.status === 'DEV_COMPLETE'
            && suggestion.currentPhase
            && /fail/i.test(suggestion.currentPhase);
    const canResumeFromLast = isAdmin
            && (['IN_PROGRESS', 'TESTING'].includes(suggestion.status) || stuckOnPostTask);
    const resumeFromLastActions = document.getElementById('resumeFromLastActions');
    if (resumeFromLastActions) resumeFromLastActions.style.display = canResumeFromLast ? '' : 'none';

    // Restart plan action — available to admins while the plan is being implemented
    const canRestartPlan = isAdmin && ['IN_PROGRESS', 'TESTING', 'DEV_COMPLETE'].includes(suggestion.status);
    const restartPlanActions = document.getElementById('restartPlanActions');
    if (restartPlanActions) restartPlanActions.style.display = canRestartPlan ? '' : 'none';

    // Redo from scratch — available to admins on any non-merged, non-draft suggestion.
    // Wipes plan, tasks, and discussion thread, then re-runs the initial AI evaluation.
    const canRedoFromScratch = isAdmin
            && !['MERGED', 'DRAFT'].includes(suggestion.status);
    const redoFromScratchActions = document.getElementById('redoFromScratchActions');
    if (redoFromScratchActions) redoFromScratchActions.style.display = canRedoFromScratch ? '' : 'none';

    // Reply box visibility
    const statusAllowsReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(suggestion.status);
    const hasReplyPermission = isAdmin || state.permissions.includes('REPLY');
    const canReply = statusAllowsReply && hasReplyPermission;
    document.getElementById('replyBox').style.display = canReply ? '' : 'none';
    const noReplyMsg = document.getElementById('noReplyMsg');
    if (noReplyMsg) {
        noReplyMsg.style.display = (statusAllowsReply && !hasReplyPermission) ? '' : 'none';
    }

    // Render messages
    renderMessages(messages);

    // Recover the right clarification wizard on page load. Initial AI
    // evaluation uses DISCUSSING + the user-facing wizard; expert plan
    // reviews use EXPERT_REVIEW + the expert wizard (different state,
    // different submit endpoint, different header).
    if (suggestion.pendingClarificationQuestions) {
        let questions = null;
        try {
            questions = JSON.parse(suggestion.pendingClarificationQuestions);
        } catch (e) {
            // Fall through to the API fallback below.
        }
        if (questions && questions.length > 0) {
            if (suggestion.status === 'EXPERT_REVIEW') {
                // currentPhase is stored as "<Expert Name> has questions for you"
                // — extract the name so the wizard header is accurate after a
                // page reload.
                let expertName = 'Expert';
                const m = (suggestion.currentPhase || '')
                        .match(/^(.+?) has questions for you$/);
                if (m) expertName = m[1];
                showExpertClarificationWizard(questions, expertName);
            } else if (suggestion.status === 'DISCUSSING') {
                showClarificationWizard(questions);
            } else {
                hideClarificationWizard();
            }
        } else if (questions === null) {
            // Couldn't parse the stored payload — ask the server directly.
            loadClarificationQuestions(id);
        } else {
            hideClarificationWizard();
        }
    } else {
        hideClarificationWizard();
    }

    // Connect WebSocket
    _callbacks.connectWs(id);
}

export function renderMessages(messages) {
    const container = document.getElementById('threadContainer');
    container.innerHTML = messages.map(m => renderMessage(m)).join('');
    container.scrollTop = container.scrollHeight;
}

export function renderMessage(m) {
    return `
        <div class="message message-${m.senderType}">
            <div class="message-header">
                <strong>${esc(m.senderName || m.senderType)}</strong>
                <span>${timeAgo(m.createdAt)}</span>
            </div>
            <div class="message-content">${formatContent(m.content)}</div>
        </div>
    `;
}

export async function approve() {
    if (!confirm('Approve this suggestion and begin implementation?')) return;
    await api('/suggestions/' + state.currentSuggestion + '/approve', { method: 'POST' });
}

export async function deny() {
    const reason = prompt('Reason for denial (optional):');
    await api('/suggestions/' + state.currentSuggestion + '/deny', {
        method: 'POST',
        body: JSON.stringify({ reason })
    });
}

export async function changePriority(newPriority) {
    const id = state.currentSuggestion;
    if (!id) return;
    const data = await api('/suggestions/' + id + '/priority', {
        method: 'PATCH',
        body: JSON.stringify({ priority: newPriority })
    });
    if (data && data.error) {
        _callbacks.showToast(data.error);
        return;
    }
    const priorityLabel = data.priority || newPriority;
    const badge = document.getElementById('detailPriorityBadge');
    if (badge) {
        badge.textContent = priorityLabel;
        badge.className = 'priority-badge priority-' + priorityLabel;
    }
}

export async function approveSuggestion(id) {
    if (!confirm('Approve this suggestion and begin implementation?')) return;
    const data = await api('/suggestions/' + id + '/approve', { method: 'POST' });
    if (data && data.error) { _callbacks.showToast(data.error); return; }
    if (state.approvalPendingCount > 0) {
        state.approvalPendingCount--;
        _callbacks.updateApprovalBanner();
    }
    await loadSuggestions();
}

export async function denySuggestion(id) {
    const card = document.querySelector(`.suggestion-item[data-suggestion-id="${id}"]`);
    if (!card) return;
    const existing = card.querySelector('.deny-inline-form');
    if (existing) { existing.remove(); return; }
    const form = document.createElement('div');
    form.className = 'deny-inline-form';
    form.onclick = e => e.stopPropagation();
    form.innerHTML = `
        <textarea class="deny-reason-input" placeholder="Reason for denial (optional)" rows="2"
            style="width:100%;margin-top:0.5rem;padding:0.4rem;border:1px solid var(--border);border-radius:4px;resize:vertical;font-size:0.85rem;box-sizing:border-box"></textarea>
        <div style="margin-top:0.4rem;display:flex;gap:0.5rem">
            <button class="btn btn-danger btn-sm" onclick="app.submitDenySuggestion(${id})">Confirm Deny</button>
            <button class="btn btn-outline btn-sm" onclick="this.closest('.deny-inline-form').remove()">Cancel</button>
        </div>`;
    card.appendChild(form);
    form.querySelector('.deny-reason-input').focus();
}

export async function submitDenySuggestion(id) {
    const card = document.querySelector(`.suggestion-item[data-suggestion-id="${id}"]`);
    const reason = card ? (card.querySelector('.deny-reason-input').value || null) : null;
    const data = await api('/suggestions/' + id + '/deny', {
        method: 'POST',
        body: JSON.stringify({ reason })
    });
    if (data && data.error) { _callbacks.showToast(data.error); return; }
    if (state.approvalPendingCount > 0) {
        state.approvalPendingCount--;
        _callbacks.updateApprovalBanner();
    }
    await loadSuggestions();
}

export async function retryExecution() {
    if (!confirm('This will retry the execution from scratch. Continue?')) return;
    const btn = document.querySelector('#retryExecutionActions button');
    btn.disabled = true;
    btn.textContent = 'Retrying...';
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/retry-execution', { method: 'POST' });
        if (result && result.error) {
            alert('Retry failed: ' + result.error);
        }
    } catch (e) {
        alert('Retry failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Retry Execution';
    }
}

export async function retryFromLast() {
    if (!confirm('Resume from the last successful task? Completed tasks stay as-is; the failed or stuck task will be retried and execution continues from there.')) return;
    const btn = document.querySelector('#resumeFromLastActions button');
    btn.disabled = true;
    btn.textContent = 'Resuming...';
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/retry-from-last', { method: 'POST' });
        if (result && result.error) {
            alert('Resume failed: ' + result.error);
        }
    } catch (e) {
        alert('Resume failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Resume from last successful task';
    }
}

export async function restartPlan() {
    if (!confirm('This restarts the implementation from task 1 with a fresh copy of the repository (reset to main). All work done so far on this suggestion will be discarded. Continue?')) return;
    const btn = document.querySelector('#restartPlanActions button');
    btn.disabled = true;
    btn.textContent = 'Restarting...';
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/restart-plan', { method: 'POST' });
        if (result && result.error) {
            alert('Restart failed: ' + result.error);
        }
    } catch (e) {
        alert('Restart failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Restart Plan (fresh repo)';
    }
}

export async function approvePlan() {
    const expertsApproved = state.currentSuggestionData
            && state.currentSuggestionData.expertsApprovedCurrentPlan;
    const msg = expertsApproved
        ? 'Approve this plan? AI experts have already approved this version — tasks will be generated next.'
        : 'Approve this plan? The plan will be sent to AI experts for review, then come back to you for final approval.';
    if (!confirm(msg)) return;
    const btn = document.getElementById('approvePlanBtn');
    const originalText = btn ? btn.textContent : '';
    if (btn) {
        btn.disabled = true;
        btn.textContent = expertsApproved ? 'Generating tasks...' : 'Starting expert review...';
    }
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/approve-plan', { method: 'POST' });
        if (result && result.error) {
            alert('Approve plan failed: ' + result.error);
        }
    } catch (e) {
        alert('Approve plan failed: ' + e.message);
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = originalText;
        }
    }
}

export async function retryClarification() {
    const btn = document.querySelector('#retryClarificationActions button');
    if (btn) { btn.disabled = true; btn.textContent = 'Retrying...'; }
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/retry-clarification', { method: 'POST' });
        if (result && result.error) {
            alert('Retry failed: ' + result.error);
        }
    } catch (e) {
        alert('Retry failed: ' + e.message);
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = 'Retry AI call'; }
    }
}

export function toggleRequestPlanChanges(forceOpen) {
    const box = document.getElementById('requestPlanChangesBox');
    if (!box) return;
    const willOpen = (typeof forceOpen === 'boolean') ? forceOpen : box.style.display === 'none';
    box.style.display = willOpen ? '' : 'none';
    if (willOpen) {
        const ta = document.getElementById('requestPlanChangesText');
        if (ta) ta.focus();
    }
}

export async function submitRequestPlanChanges() {
    const ta = document.getElementById('requestPlanChangesText');
    const feedback = ta ? ta.value.trim() : '';
    if (!feedback) {
        alert('Please describe what should change about the plan.');
        return;
    }
    const btn = document.querySelector('#requestPlanChangesBox .btn-primary');
    if (btn) { btn.disabled = true; btn.textContent = 'Submitting...'; }
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/request-plan-changes', {
            method: 'POST',
            body: JSON.stringify({ feedback }),
        });
        if (result && result.error) {
            alert('Submit failed: ' + result.error);
            return;
        }
        if (ta) ta.value = '';
        toggleRequestPlanChanges(false);
    } catch (e) {
        alert('Submit failed: ' + e.message);
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = 'Submit feedback'; }
    }
}

export async function redoFromScratch() {
    if (!confirm('This will DELETE the current plan, all tasks, and the entire discussion thread, then re-run the AI evaluation from step 1. The suggestion title, description, votes, and priority are preserved. This cannot be undone. Continue?')) return;
    if (!confirm('Are you sure? Everything except the original suggestion text will be wiped.')) return;
    const btn = document.querySelector('#redoFromScratchActions button');
    btn.disabled = true;
    btn.textContent = 'Wiping & restarting...';
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/restart-from-scratch', { method: 'POST' });
        if (result && result.error) {
            alert('Redo failed: ' + result.error);
        }
    } catch (e) {
        alert('Redo failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Redo from scratch';
    }
}

export async function retryMerge() {
    if (!confirm('Retry merging this pull request? Claude will run the merge via git over SSH.')) return;
    const btn = document.querySelector('#retryMergeActions button');
    btn.disabled = true;
    btn.textContent = 'Merging...';
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/retry-merge', { method: 'POST' });
        if (result && !result.success) {
            alert('Merge retry failed: ' + (result.error || 'Unknown error'));
        }
    } catch (e) {
        alert('Merge retry failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Retry Merge';
    }
}

export async function retryPr() {
    const btn = document.querySelector('#retryPrActions button');
    btn.disabled = true;
    btn.textContent = 'Retrying...';
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/retry-pr', { method: 'POST' });
        if (result && !result.success) {
            alert('Retry failed: ' + (result.error || 'Unknown error'));
        }
    } catch (e) {
        alert('Retry failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Retry Review Request';
    }
}

export async function forceReApproval() {
    if (!confirm('This will restart expert reviews from scratch. All expert reviewers will re-evaluate the plan. Continue?')) return;
    const btn = document.getElementById('forceReApprovalBtn');
    btn.disabled = true;
    btn.textContent = 'Restarting...';
    try {
        const result = await api('/suggestions/' + state.currentSuggestion + '/force-re-approval', { method: 'POST' });
        if (result && result.error) {
            alert('Force re-approval failed: ' + result.error);
        }
    } catch (e) {
        alert('Force re-approval failed: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Force Re-approval';
    }
}

export async function vote(value) {
    const id = state.currentSuggestion;
    const data = await api('/suggestions/' + id + '/vote', {
        method: 'POST',
        body: JSON.stringify({ value })
    });
    if (data.upVotes !== undefined) {
        document.getElementById('detailUpVotes').textContent = data.upVotes;
        document.getElementById('detailDownVotes').textContent = data.downVotes;
    }
    if (data.error) alert(data.error);
}
