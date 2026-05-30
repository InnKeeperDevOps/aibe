import { state } from './state.js';
import { api } from './api.js';
import { navigate } from './navigation.js';

// Callbacks for functions provided by modules not yet created in this phase.
// Populated via registerAuthCallbacks() once those modules are ready.
const _callbacks = {
    connectNotificationsWs: () => {},
    showProjectDefinitionModal: () => {},
};

export function registerAuthCallbacks(cbs) {
    Object.assign(_callbacks, cbs);
}

export async function checkAuth() {
    const data = await api('/auth/status');
    state.setupRequired = data.setupRequired;
    state.loggedIn = data.loggedIn;
    state.username = data.username;
    state.role = data.role;
    state.permissions = data.permissions || [];
    updateHeader();

    if (data.setupRequired) {
        navigate('setup');
    } else {
        navigate('list');
        await initProjectDefinition();
    }
}

export function updateHeader() {
    const { loggedIn, username, role } = state;
    const badge = document.getElementById('userBadge');
    const loginBtn = document.getElementById('loginBtn');
    const logoutBtn = document.getElementById('logoutBtn');
    const settingsBtn = document.getElementById('settingsBtn');
    const claudeLogsBtn = document.getElementById('claudeLogsBtn');
    const claudeQueueBtn = document.getElementById('claudeQueueBtn');
    const spendingBtn = document.getElementById('spendingBtn');
    const changePasswordBtn = document.getElementById('changePasswordBtn');
    const plansBtn = document.getElementById('plansBtn');
    const tasksBtn = document.getElementById('tasksBtn');
    const adminSection = document.getElementById('adminSidebarSection');

    if (loggedIn) {
        badge.textContent = username + ' (' + role + ')';
        badge.style.display = '';
        loginBtn.style.display = 'none';
        logoutBtn.style.display = '';
        const isAdmin = (role === 'ROOT_ADMIN' || role === 'ADMIN');
        settingsBtn.style.display = isAdmin ? '' : 'none';
        if (claudeLogsBtn) claudeLogsBtn.style.display = isAdmin ? '' : 'none';
        if (claudeQueueBtn) claudeQueueBtn.style.display = isAdmin ? '' : 'none';
        if (spendingBtn) spendingBtn.style.display = isAdmin ? '' : 'none';
        if (plansBtn) plansBtn.style.display = '';
        if (tasksBtn) tasksBtn.style.display = '';
        if (adminSection) adminSection.style.display = isAdmin ? '' : 'none';
        // Change password is available to every logged-in user, not just admins.
        if (changePasswordBtn) changePasswordBtn.style.display = '';
    } else {
        badge.style.display = 'none';
        loginBtn.style.display = '';
        logoutBtn.style.display = 'none';
        settingsBtn.style.display = 'none';
        if (claudeLogsBtn) claudeLogsBtn.style.display = 'none';
        if (claudeQueueBtn) claudeQueueBtn.style.display = 'none';
        if (spendingBtn) spendingBtn.style.display = 'none';
        if (plansBtn) plansBtn.style.display = 'none';
        if (tasksBtn) tasksBtn.style.display = 'none';
        if (adminSection) adminSection.style.display = 'none';
        if (changePasswordBtn) changePasswordBtn.style.display = 'none';
    }
    updateNewSuggestionBtn();
    updateAiRecommendationsBtn();
    updateProjectDefinitionBtn();
    updateMyDraftsBtn();
    updateSaveAsDraftBtn();
}

export function updateNewSuggestionBtn() {
    const btn = document.getElementById('newSuggestionBtn');
    if (!btn) return;
    const allowAnon = state.settings.allowAnonymousSuggestions;
    const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
    btn.style.display = (allowAnon || hasPermission) ? '' : 'none';
}

export function updateAiRecommendationsBtn() {
    const historyBtn = document.getElementById('recommendationsHistoryBtn');
    const { loggedIn, role } = state;
    const visible = loggedIn && (role === 'ROOT_ADMIN' || role === 'ADMIN');
    if (historyBtn) historyBtn.style.display = visible ? '' : 'none';
}

export function updateProjectDefinitionBtn() {
    const btn = document.getElementById('project-def-btn');
    if (!btn) return;
    const { loggedIn, role } = state;
    btn.style.display = (loggedIn && (role === 'ROOT_ADMIN' || role === 'ADMIN')) ? '' : 'none';
}

export function updateMyDraftsBtn() {
    const btn = document.getElementById('myDraftsBtn');
    if (!btn) return;
    btn.style.display = state.loggedIn ? '' : 'none';
}

export function updateSaveAsDraftBtn() {
    const btn = document.getElementById('saveAsDraftBtn');
    if (!btn) return;
    btn.style.display = state.loggedIn ? '' : 'none';
}

export async function initProjectDefinition() {
    const isAdmin = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
    if (!isAdmin) return;
    try {
        const res = await fetch('/api/project-definition/state');
        if (res.status === 204) return;
        if (!res.ok) return;
        const pdState = await res.json();
        if (!pdState || !pdState.status) return;
        const btn = document.getElementById('project-def-btn');
        if (pdState.status === 'COMPLETED' || pdState.status === 'PR_OPEN') {
            if (btn) btn.textContent = 'View Definition';
        } else if (['ACTIVE', 'GENERATING', 'SAVING'].includes(pdState.status)) {
            if (btn) btn.textContent = pdState.isEdit ? 'Update Definition' : 'Project Definition';
            _callbacks.showProjectDefinitionModal(pdState);
        }
    } catch (e) {
        // Silently ignore — feature may not be configured
    }
}

export async function setup(e) {
    e.preventDefault();
    const data = await api('/auth/setup', {
        method: 'POST',
        body: JSON.stringify({
            username: document.getElementById('setupUsername').value,
            password: document.getElementById('setupPassword').value
        })
    });
    if (data.error) { alert(data.error); return; }
    state.loggedIn = true;
    state.username = data.username;
    state.role = data.role;
    state.setupRequired = false;
    updateHeader();
    navigate('list');
}

export async function login(e) {
    e.preventDefault();
    const data = await api('/auth/login', {
        method: 'POST',
        body: JSON.stringify({
            username: document.getElementById('loginUsername').value,
            password: document.getElementById('loginPassword').value
        })
    });
    if (data.error) { alert(data.error); return; }
    state.loggedIn = true;
    state.username = data.username;
    state.role = data.role;
    updateHeader();
    Notification.requestPermission();
    _callbacks.connectNotificationsWs(state.username);
    navigate('list');
}

export async function register(e) {
    e.preventDefault();
    const username = document.getElementById('registerUsername').value;
    const password = document.getElementById('registerPassword').value;
    const confirmPassword = document.getElementById('registerConfirmPassword').value;

    if (password !== confirmPassword) {
        alert('Passwords do not match');
        return;
    }

    const data = await api('/auth/register', {
        method: 'POST',
        body: JSON.stringify({ username, password })
    });

    if (data.status === 403 || (data.error && data.error.toLowerCase().includes('disabled'))) {
        alert('Registrations are currently closed. Please contact an administrator.');
        navigate('login');
        return;
    }
    if (data.error) { alert(data.error); return; }

    if (data.pending) {
        alert('Your account has been created and is pending approval.');
        navigate('login');
    } else {
        state.loggedIn = true;
        state.username = data.username;
        state.role = 'USER';
        updateHeader();
        navigate('list');
    }

    document.getElementById('registerUsername').value = '';
    document.getElementById('registerPassword').value = '';
    document.getElementById('registerConfirmPassword').value = '';
}

export async function logout() {
    await api('/auth/logout', { method: 'POST' });
    state.loggedIn = false;
    state.username = '';
    state.role = '';
    state.notificationWs?.close();
    clearTimeout(state.notificationWsReconnectTimeout);
    updateHeader();
    navigate('list');
}
