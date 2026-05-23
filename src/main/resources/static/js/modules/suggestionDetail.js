import { state } from './state.js';
import { api } from './api.js';
import { esc, timeAgo, formatContent } from './utils.js';
import { renderTasks } from './tasks.js';
import { renderExpertReview, updateExpertReview, loadReviewSummary } from './expertReview.js';
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

    const planEl = document.getElementById('detailPlan');
    const planText = document.getElementById('detailPlanText');
    if (suggestion.planDisplaySummary || suggestion.planSummary) {
        planEl.style.display = '';
        planText.textContent = suggestion.planDisplaySummary || suggestion.planSummary;
    } else {
        planEl.style.display = 'none';
    }

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

    // Admin actions
    const canApprove = ['PLANNED', 'DISCUSSING'].includes(suggestion.status);
    const canForceReApproval = ['PLANNED', 'APPROVED'].includes(suggestion.status);
    document.getElementById('adminActions').style.display =
        (isAdmin && (canApprove || canForceReApproval)) ? '' : 'none';
    document.getElementById('forceReApprovalBtn').style.display =
        (isAdmin && canForceReApproval) ? '' : 'none';

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

    // Check for pending clarification questions
    if (suggestion.status === 'DISCUSSING' && suggestion.pendingClarificationQuestions) {
        try {
            const questions = JSON.parse(suggestion.pendingClarificationQuestions);
            if (questions && questions.length > 0) {
                showClarificationWizard(questions);
            }
        } catch (e) {
            // Fallback: load from API
            loadClarificationQuestions(id);
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
