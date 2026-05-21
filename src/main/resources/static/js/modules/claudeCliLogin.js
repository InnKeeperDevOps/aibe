import { api } from './api.js';
import { showToast } from './utils.js';

let pollTimer = null;
let lastStatus = null;

function $(id) { return document.getElementById(id); }

function setText(el, text) {
    if (el) el.textContent = text;
}

function renderStatus(s) {
    lastStatus = s;
    const banner = $('claudeLoginBanner');
    const startBtn = $('claudeLoginStartBtn');
    const cancelBtn = $('claudeLoginCancelBtn');
    const codeRow = $('claudeLoginCodeRow');
    const urlBox = $('claudeLoginUrlBox');
    const urlLink = $('claudeLoginUrlLink');
    const logBox = $('claudeLoginLog');
    const clearBtn = $('claudeLoginClearBtn');

    if (!banner) return;

    const status = s.status || 'IDLE';
    let bannerText;
    let bannerColor;

    switch (status) {
        case 'IDLE':
            bannerText = s.hasStoredCredentials
                ? 'Logged in — credentials stored. Start again to refresh them.'
                : 'Not logged in. Click "Start Login" to begin.';
            bannerColor = s.hasStoredCredentials ? '#16a34a' : 'var(--text-muted)';
            break;
        case 'STARTING':
            bannerText = 'Starting `claude login`, waiting for OAuth URL...';
            bannerColor = '#ca8a04';
            break;
        case 'AWAITING_CODE':
            bannerText = '1) Open the URL below and sign in.  2) Paste the code returned by Claude into the form.';
            bannerColor = '#ca8a04';
            break;
        case 'SUBMITTING':
            bannerText = 'Submitting code, waiting for `claude login` to finish...';
            bannerColor = '#ca8a04';
            break;
        case 'SUCCESS':
            bannerText = 'Login complete. Credentials saved.';
            bannerColor = '#16a34a';
            break;
        case 'FAILED':
            bannerText = 'Login failed' + (s.error ? ': ' + s.error : '');
            bannerColor = '#dc2626';
            break;
        default:
            bannerText = status;
            bannerColor = 'var(--text-muted)';
    }

    setText(banner, bannerText);
    banner.style.color = bannerColor;

    const active = status === 'STARTING' || status === 'AWAITING_CODE' || status === 'SUBMITTING';
    if (startBtn) startBtn.disabled = active;
    if (cancelBtn) cancelBtn.style.display = active ? '' : 'none';
    if (clearBtn) clearBtn.style.display = (status === 'IDLE' && s.hasStoredCredentials) ? '' : 'none';

    if (urlBox && urlLink) {
        if (s.url) {
            urlBox.style.display = '';
            urlLink.href = s.url;
            urlLink.textContent = s.url;
        } else {
            urlBox.style.display = 'none';
            urlLink.href = '#';
            urlLink.textContent = '';
        }
    }

    if (codeRow) {
        codeRow.style.display = status === 'AWAITING_CODE' ? 'flex' : 'none';
    }

    if (logBox) {
        const lines = Array.isArray(s.log) ? s.log : [];
        if (lines.length) {
            logBox.textContent = lines.join('\n');
            logBox.style.display = '';
            logBox.scrollTop = logBox.scrollHeight;
        } else {
            logBox.textContent = '';
            logBox.style.display = 'none';
        }
    }

    const shouldPoll = active;
    if (shouldPoll && !pollTimer) {
        pollTimer = setInterval(refreshClaudeCliLogin, 1500);
    } else if (!shouldPoll && pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

export async function refreshClaudeCliLogin() {
    try {
        const s = await api('/claude-cli-login/status');
        renderStatus(s);
    } catch (e) {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
        const banner = $('claudeLoginBanner');
        if (banner) {
            banner.textContent = 'Could not load login status: ' + (e?.message || e);
            banner.style.color = '#dc2626';
        }
    }
}

export async function startClaudeCliLogin() {
    try {
        const s = await api('/claude-cli-login/start', { method: 'POST' });
        renderStatus(s);
    } catch (e) {
        showToast('Failed to start login: ' + (e?.message || e), 'error');
    }
}

export async function submitClaudeCliCode(event) {
    if (event) event.preventDefault();
    const input = $('claudeLoginCodeInput');
    if (!input) return;
    const code = (input.value || '').trim();
    if (!code) {
        showToast('Enter the code first', 'error');
        return;
    }
    try {
        const s = await api('/claude-cli-login/code', {
            method: 'POST',
            body: JSON.stringify({ code }),
        });
        renderStatus(s);
        input.value = '';
    } catch (e) {
        showToast('Failed to submit code: ' + (e?.message || e), 'error');
    }
}

export async function cancelClaudeCliLogin() {
    try {
        const s = await api('/claude-cli-login/cancel', { method: 'POST' });
        renderStatus(s);
    } catch (e) {
        showToast('Failed to cancel: ' + (e?.message || e), 'error');
    }
}

export async function clearClaudeCliCredentials() {
    if (!confirm('Remove stored Claude CLI credentials? The CLI will need to log in again.')) {
        return;
    }
    try {
        const s = await api('/claude-cli-login/credentials', { method: 'DELETE' });
        renderStatus(s);
        showToast('Credentials cleared', 'success');
    } catch (e) {
        showToast('Failed to clear: ' + (e?.message || e), 'error');
    }
}
