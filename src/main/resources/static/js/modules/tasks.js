import { state } from './state.js';
import { esc } from './utils.js';

export function renderTasks() {
    const container = document.getElementById('detailTasks');
    const listEl = document.getElementById('taskList');
    const tasks = state.tasks;
    if (!tasks || tasks.length === 0) {
        container.style.display = 'none';
        return;
    }
    container.style.display = '';

    const completed = tasks.filter(t => t.status === 'COMPLETED').length;
    const total = tasks.length;
    const pct = Math.round((completed / total) * 100);
    document.getElementById('taskProgress').textContent = `${completed}/${total} completed`;
    document.getElementById('taskProgressFill').style.width = pct + '%';

    // Start/stop elapsed time timer for in-progress tasks
    const hasActiveTask = tasks.some(t => (t.status === 'IN_PROGRESS' || t.status === 'REVIEWING') && t.startedAt);
    if (hasActiveTask && !state.taskTimer) {
        state.taskTimer = setInterval(() => renderTasks(), 10000);
    } else if (!hasActiveTask && state.taskTimer) {
        clearInterval(state.taskTimer);
        state.taskTimer = null;
    }

    listEl.innerHTML = tasks.map(t => {
        const icons = { PENDING: '○', IN_PROGRESS: '◉', REVIEWING: '⟳', COMPLETED: '✓', FAILED: '✗' };
        const icon = icons[t.status] || '○';
        const statusClass = t.status.toLowerCase();
        const titleClass = t.status === 'COMPLETED' ? 'task-title completed' : 'task-title';
        const statusLabel = t.status === 'REVIEWING' ? ' — reviewing' : (t.status === 'IN_PROGRESS' ? ' — in progress' : '');

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

        const isActive = t.status === 'IN_PROGRESS' || t.status === 'REVIEWING';
        const activityDetail = t.statusDetail ? esc(t.statusDetail) : '';

        const lowLevel = state.showTechnicalPlan;
        const titleText = lowLevel ? (t.title || '[no low-level title]') : (t.displayTitle || t.title || '');
        const descText = lowLevel ? (t.description || '') : (t.displayDescription || t.description || '');
        const titleMissing = lowLevel && !t.title;
        const descMissing = lowLevel && !t.description;
        const titleExtraStyle = titleMissing ? 'color:var(--text-muted);font-style:italic' : '';
        return `<div class="task-item ${isActive ? 'task-active' : ''}" data-task-order="${t.taskOrder}">
            <div class="task-icon ${statusClass}">${icon}</div>
            <div class="task-body">
                <div class="${titleClass}" style="${titleExtraStyle}">${t.taskOrder}. ${esc(titleText)}${statusLabel ? `<span style="font-weight:400;font-size:0.8rem;color:${t.status === 'REVIEWING' ? '#d97706' : '#2563eb'}">${statusLabel}</span>` : ''}</div>
                ${descMissing
                    ? `<div class="task-desc" style="color:var(--text-muted);font-style:italic">[no low-level description stored]</div>`
                    : (descText ? `<div class="task-desc">${esc(descText)}</div>` : '')}
                ${isActive && activityDetail ? `<div class="task-activity"><span class="task-activity-dot"></span>${activityDetail}</div>` : ''}
                ${(!isActive && t.status === 'COMPLETED' && activityDetail) ? `<div class="task-completed-detail">${activityDetail}</div>` : ''}
                ${(!isActive && t.status === 'FAILED' && activityDetail) ? `<div class="task-failed-detail">${activityDetail}</div>` : ''}
                ${meta ? `<div class="task-meta">${meta}</div>` : ''}
            </div>
        </div>`;
    }).join('');
}

export function updateTask(taskData) {
    const tasks = state.tasks;
    const idx = tasks.findIndex(t => t.taskOrder === taskData.taskOrder);
    const prevStatus = idx >= 0 ? tasks[idx].status : null;
    if (idx >= 0) {
        tasks[idx] = { ...tasks[idx], ...taskData };
    } else {
        tasks.push(taskData);
        tasks.sort((a, b) => a.taskOrder - b.taskOrder);
    }
    renderTasks();

    // Animate the changed task if status transitioned
    if (taskData.status && taskData.status !== prevStatus) {
        const taskEl = document.querySelector(`.task-item[data-task-order="${taskData.taskOrder}"]`);
        if (taskEl) {
            let animClass = '';
            if (taskData.status === 'COMPLETED') animClass = 'just-completed';
            else if (taskData.status === 'FAILED') animClass = 'just-failed';
            else if (taskData.status === 'IN_PROGRESS') animClass = 'just-started';
            if (animClass) {
                taskEl.classList.add(animClass);
                setTimeout(() => taskEl.classList.remove(animClass), 1500);
            }
            // Scroll the task into view
            taskEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
    }
}
