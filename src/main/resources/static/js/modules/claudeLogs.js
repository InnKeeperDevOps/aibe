import { api } from './api.js';

// Admin-only Claude CLI log viewer: lists recent CLI invocations and, on
// click, shows the full command, prompt (input) and raw output (response).

function fmtTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d.getTime()) ? String(iso) : d.toLocaleString();
}

function fmtDuration(ms) {
    if (ms == null) return '';
    return ms < 1000 ? ms + ' ms' : (ms / 1000).toFixed(1) + ' s';
}

function cell(text, extraStyle) {
    const td = document.createElement('td');
    td.style.cssText = 'padding:0.5rem;border-bottom:1px solid var(--border);font-size:0.85rem;'
        + (extraStyle || '');
    td.textContent = (text == null) ? '' : String(text);
    return td;
}

function renderRow(entry) {
    const tr = document.createElement('tr');
    tr.style.cursor = 'pointer';
    tr.onmouseenter = () => { tr.style.background = 'var(--bg)'; };
    tr.onmouseleave = () => { tr.style.background = ''; };
    tr.onclick = () => viewClaudeLog(entry.id);
    tr.appendChild(cell(fmtTime(entry.createdAt)));
    tr.appendChild(cell(entry.operationType));
    tr.appendChild(cell(entry.model));
    const exitCell = cell(entry.exitCode == null ? '—' : entry.exitCode);
    if (entry.failed) { exitCell.style.color = 'var(--danger)'; exitCell.style.fontWeight = '600'; }
    tr.appendChild(exitCell);
    tr.appendChild(cell(fmtDuration(entry.durationMs)));
    tr.appendChild(cell(entry.promptPreview, 'color:var(--text-muted)'));
    return tr;
}

export async function loadClaudeLogs() {
    const body = document.getElementById('claudeLogsBody');
    const errEl = document.getElementById('claudeLogsError');
    const detail = document.getElementById('claudeLogDetail');
    if (!body) return;
    if (errEl) errEl.style.display = 'none';
    if (detail) detail.style.display = 'none';
    body.innerHTML = '<tr><td colspan="6" style="padding:0.75rem;color:var(--text-muted)">Loading…</td></tr>';

    try {
        const logs = await api('/claude-logs');
        if (logs && logs.error) {
            body.innerHTML = '';
            if (errEl) { errEl.textContent = logs.error; errEl.style.display = ''; }
            return;
        }
        if (!Array.isArray(logs) || logs.length === 0) {
            body.innerHTML = '<tr><td colspan="6" style="padding:0.75rem;color:var(--text-muted)">'
                + 'No Claude CLI calls have been logged yet.</td></tr>';
            return;
        }
        body.innerHTML = '';
        logs.forEach(entry => body.appendChild(renderRow(entry)));
    } catch (e) {
        body.innerHTML = '';
        if (errEl) { errEl.textContent = 'Could not load Claude logs: ' + e.message; errEl.style.display = ''; }
    }
}

function section(title, content, mono) {
    const wrap = document.createElement('div');
    wrap.style.marginBottom = '1rem';
    const h = document.createElement('h4');
    h.style.cssText = 'margin:0 0 0.35rem';
    h.textContent = title;
    wrap.appendChild(h);
    const box = document.createElement(mono ? 'pre' : 'div');
    box.textContent = (content == null || content === '') ? '(empty)' : content;
    box.style.cssText = 'margin:0;padding:0.6rem;background:var(--bg);border:1px solid var(--border);'
        + 'border-radius:4px;font-size:0.8rem;'
        + (mono ? 'white-space:pre-wrap;word-break:break-word;max-height:420px;overflow:auto;' : '');
    wrap.appendChild(box);
    return wrap;
}

export async function viewClaudeLog(id) {
    const detail = document.getElementById('claudeLogDetail');
    if (!detail) return;
    detail.style.display = '';
    detail.innerHTML = '<p style="color:var(--text-muted)">Loading…</p>';
    detail.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    try {
        const log = await api('/claude-logs/' + id);
        detail.innerHTML = '';
        if (log && log.error) {
            const p = document.createElement('p');
            p.style.color = 'var(--danger)';
            p.textContent = log.error;
            detail.appendChild(p);
            return;
        }
        const heading = document.createElement('h3');
        heading.style.marginTop = '0';
        heading.textContent = 'CLI request #' + (log.requestId != null ? log.requestId : '?')
            + ' — ' + (log.operationType || 'unknown');
        detail.appendChild(heading);

        const meta = document.createElement('p');
        meta.style.cssText = 'color:var(--text-muted);font-size:0.85rem;margin-top:0';
        meta.textContent = 'Model: ' + (log.model || '—')
            + '   ·   Exit code: ' + (log.exitCode == null ? '—' : log.exitCode)
            + '   ·   Duration: ' + fmtDuration(log.durationMs)
            + '   ·   ' + fmtTime(log.createdAt);
        detail.appendChild(meta);

        if (log.workingDir) detail.appendChild(section('Working directory', log.workingDir, true));
        detail.appendChild(section('Command', log.command, true));
        detail.appendChild(section('Prompt (input)', log.prompt, true));
        detail.appendChild(section('Raw output (response)', log.rawOutput, true));
    } catch (e) {
        detail.innerHTML = '';
        const p = document.createElement('p');
        p.style.color = 'var(--danger)';
        p.textContent = 'Could not load log entry: ' + e.message;
        detail.appendChild(p);
    }
}
