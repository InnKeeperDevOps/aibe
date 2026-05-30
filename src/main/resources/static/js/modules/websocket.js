import { state } from './state.js';
import { loadDetail, renderMessage } from './suggestionDetail.js';
import { loadSuggestions } from './suggestions.js';
import { renderTasks, updateTask } from './tasks.js';
import { updateExpertReview, addExpertNote, showExpertClarificationWizard } from './expertReview.js';
import { showClarificationWizard, hideClarificationWizard } from './clarification.js';
import { onProjectDefinitionUpdate } from './projectDefinition.js';

export function connectWs(suggestionId) {
    disconnectWs();
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = protocol + '//' + location.host + '/ws/suggestions/' + suggestionId;
    const ws = new WebSocket(url);

    ws.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            handleWsMessage(data);
        } catch (e) {
            console.error('WS parse error:', e);
        }
    };

    ws.onclose = () => {
        if (state.currentSuggestion === suggestionId) {
            setTimeout(() => {
                if (state.currentSuggestion === suggestionId) {
                    connectWs(suggestionId);
                }
            }, 3000);
        }
    };

    state.ws = ws;
}

export function disconnectWs() {
    if (state.taskTimer) {
        clearInterval(state.taskTimer);
        state.taskTimer = null;
    }
    if (state.ws) {
        state.ws.onclose = null;
        state.ws.close();
        state.ws = null;
    }
}

export function connectNotificationsWs(username) {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = protocol + '//' + location.host + '/ws/notifications?username=' + encodeURIComponent(username);
    const ws = new WebSocket(url);

    ws.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            handleNotificationWsMessage(data);
        } catch (e) {
            console.error('Notification WS parse error:', e);
        }
    };

    ws.onclose = () => {
        state.notificationWsReconnectTimeout = setTimeout(() => {
            if (state.loggedIn) {
                connectNotificationsWs(state.username);
            }
        }, 3000);
    };

    state.notificationWs = ws;
}

function handleNotificationWsMessage(data) {
    if (data.type === 'clarification_needed') {
        if (Notification.permission !== 'granted') {
            return;
        }
        if (state.currentSuggestion === data.suggestionId) {
            return;
        }
        const n = new Notification('Clarification Needed', {
            body: 'Your suggestion "' + data.suggestionTitle + '" needs ' + data.questionCount + ' question(s) answered.',
            tag: 'suggestion-clarification-' + data.suggestionId
        });
        n.onclick = () => { window.focus(); loadDetail(data.suggestionId); n.close(); };
    } else if (data.type === 'approval_needed') {
        state.approvalPendingCount++;
        updateApprovalBanner();
    } else if (data.type === 'execution_queue_status') {
        state.executionQueue = data;
        if (state.currentView === 'list') {
            loadSuggestions();
        }
        const queueInfoEl = document.getElementById('detailQueueInfo');
        if (queueInfoEl && state.currentSuggestion && state.currentStatus === 'APPROVED') {
            const pos = (data.queued || []).find(item => item.id === state.currentSuggestion);
            if (pos) {
                queueInfoEl.style.display = '';
                queueInfoEl.innerHTML = '<strong>Queue position:</strong> ' + pos.position +
                    ' of ' + data.queuedCount + ' &mdash; ' + data.activeCount + '/' + data.maxConcurrent + ' slots in use';
            } else {
                queueInfoEl.style.display = 'none';
            }
        }
    } else if (data.type === 'PROJECT_DEFINITION_UPDATE') {
        onProjectDefinitionUpdate(data.data || data);
    }
}

export function updateApprovalBanner() {
    const isAdmin = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
    const banner = document.getElementById('approvalPendingBanner');
    if (!banner) return;
    if (!isAdmin || state.approvalPendingCount === 0) {
        banner.style.display = 'none';
        return;
    }
    const count = state.approvalPendingCount;
    const label = count === 1 ? '1 suggestion is waiting for your approval' : count + ' suggestions are waiting for your approval';
    banner.querySelector('.approval-banner-text').textContent = label;
    banner.style.display = '';
}

function handleWsMessage(data) {
    switch (data.type) {
        case 'message': {
            const container = document.getElementById('threadContainer');
            const div = document.createElement('div');
            div.innerHTML = renderMessage(data);
            container.appendChild(div.firstElementChild);
            container.scrollTop = container.scrollHeight;
            break;
        }
        case 'status_update': {
            state.currentStatus = data.status;

            const statusEl = document.getElementById('detailStatus');
            statusEl.textContent = data.status.replace('_', ' ');
            statusEl.className = 'status-badge status-' + data.status;

            const phaseEl = document.getElementById('detailPhase');
            const phaseText = document.getElementById('detailPhaseText');
            const phaseFinished = ['DENIED', 'TIMED_OUT', 'MERGED'].includes(data.status) ||
                (data.status === 'DEV_COMPLETE' && (!data.currentPhase || data.currentPhase.startsWith('Implementation completed')));
            if (data.currentPhase && !phaseFinished) {
                phaseEl.style.display = '';
                phaseText.textContent = data.currentPhase;
            } else {
                phaseEl.style.display = 'none';
            }

            document.getElementById('detailUpVotes').textContent = data.upVotes;
            document.getElementById('detailDownVotes').textContent = data.downVotes;

            const planEl2 = document.getElementById('detailPlan');
            const planText2 = document.getElementById('detailPlanText');
            const displayPlan = data.planDisplaySummary || data.planSummary;
            if (displayPlan) {
                planEl2.style.display = '';
                planText2.textContent = displayPlan;
            }

            const isAdmin = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
            const canApprove = ['PLANNED', 'DISCUSSING'].includes(data.status);
            const canForceReApproval = ['PLANNED', 'APPROVED'].includes(data.status);
            document.getElementById('adminActions').style.display =
                (isAdmin && (canApprove || canForceReApproval)) ? '' : 'none';
            document.getElementById('forceReApprovalBtn').style.display =
                (isAdmin && canForceReApproval) ? '' : 'none';

            const canRetryPr2 = isAdmin && data.currentPhase === 'Done — review request failed';
            document.getElementById('retryPrActions').style.display = canRetryPr2 ? '' : 'none';

            const canRetryExec = isAdmin && data.currentPhase && data.currentPhase.includes('can retry');
            document.getElementById('retryExecutionActions').style.display = canRetryExec ? '' : 'none';

            const wsStatusAllowsReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(data.status);
            const wsHasReplyPermission = isAdmin || state.permissions.includes('REPLY');
            const canReply = wsStatusAllowsReply && wsHasReplyPermission;
            const wsNoReplyMsg = document.getElementById('noReplyMsg');
            if (!state.clarification.active) {
                document.getElementById('replyBox').style.display = canReply ? '' : 'none';
                if (wsNoReplyMsg) {
                    wsNoReplyMsg.style.display = (wsStatusAllowsReply && !wsHasReplyPermission) ? '' : 'none';
                }
            }

            if (!['DISCUSSING', 'EXPERT_REVIEW'].includes(data.status)) {
                hideClarificationWizard();
                document.getElementById('replyBox').style.display = canReply ? '' : 'none';
                if (wsNoReplyMsg) {
                    wsNoReplyMsg.style.display = (wsStatusAllowsReply && !wsHasReplyPermission) ? '' : 'none';
                }
            }
            break;
        }
        case 'clarification_questions': {
            if (data.questions && data.questions.length > 0) {
                showClarificationWizard(data.questions);
            }
            break;
        }
        case 'pr_created': {
            const prEl = document.getElementById('detailPr');
            const prLink = document.getElementById('detailPrLink');
            if (data.prUrl) {
                prEl.style.display = '';
                prLink.href = data.prUrl;
                prLink.textContent = data.prUrl;
            }
            break;
        }
        case 'task_update': {
            if (data.task) {
                updateTask(data.task);
            }
            break;
        }
        case 'task_activity': {
            if (data.taskOrder && data.detail) {
                const tasks = state.tasks;
                const idx = tasks.findIndex(t => t.taskOrder === data.taskOrder);
                if (idx >= 0 && (tasks[idx].status === 'IN_PROGRESS' || tasks[idx].status === 'REVIEWING')) {
                    tasks[idx].statusDetail = data.detail;
                    renderTasks();
                }
            }
            break;
        }
        case 'tasks_update': {
            if (data.tasks) {
                state.tasks = data.tasks;
                renderTasks();
            }
            break;
        }
        case 'expert_review_status': {
            updateExpertReview(data);
            break;
        }
        case 'expert_note': {
            if (data.expertName && data.note) {
                addExpertNote(data.expertName, data.note);
            }
            break;
        }
        case 'expert_clarification_questions': {
            if (data.questions && data.questions.length > 0) {
                showExpertClarificationWizard(data.questions, data.expertName || 'Expert');
            }
            break;
        }
        case 'progress':
        case 'execution_progress': {
            const phaseText = document.getElementById('detailPhaseText');
            if (data.content) {
                phaseText.textContent = data.content.substring(0, 200);
            }
            break;
        }
    }
}
