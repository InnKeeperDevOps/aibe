import { state } from './state.js';
import { showToast } from './utils.js';

// Callbacks for view-loading functions provided by other modules.
// Populated via registerNavigationCallbacks() once those modules are ready.
const _callbacks = {
    loadSuggestions: () => {},
    loadDashboardView: () => {},
    loadDetail: () => {},
    loadSettings: () => {},
    loadClaudeLogs: () => {},
    loadClaudeLogDetail: () => {},
    loadClaudeQueue: () => {},
    stopClaudeQueuePolling: () => {},
    loadSpendingDashboard: () => {},
    loadPlans: () => {},
    loadPlanDetail: () => {},
    loadAllTasks: () => {},
    disconnectWs: () => {},
    updateSaveAsDraftBtn: () => {},
};

// Maps logical view name -> sidebar nav button id.
const SIDEBAR_BTN_FOR_VIEW = {
    list: 'listBtn',
    plans: 'plansBtn',
    planDetail: 'plansBtn',
    tasks: 'tasksBtn',
    dashboard: 'dashboardBtn',
    claudeQueue: 'claudeQueueBtn',
    claudeLogs: 'claudeLogsBtn',
    claudeLogDetail: 'claudeLogsBtn',
    spending: 'spendingBtn',
    settings: 'settingsBtn',
};

function _markActiveSidebar(view) {
    document.querySelectorAll('.sidebar-item').forEach(el => el.classList.remove('active'));
    const id = SIDEBAR_BTN_FOR_VIEW[view];
    if (id) {
        const btn = document.getElementById(id);
        if (btn) btn.classList.add('active');
    }
}

export function registerNavigationCallbacks(cbs) {
    Object.assign(_callbacks, cbs);
}

export function navigate(view, data) {
    // Guard: block registration when it is disabled
    if (view === 'register' && state.settings.registrationsEnabled === false) {
        showToast('Registrations are currently closed.');
        navigate('login');
        return;
    }

    // Guard: enforce suggestion creation permissions before showing the view
    if (view === 'create') {
        const allowAnon = state.settings.allowAnonymousSuggestions;
        const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
        if (!state.loggedIn && !allowAnon) {
            showToast('Please log in to create a suggestion');
            navigate('login');
            return;
        }
        if (state.loggedIn && !hasPermission && !allowAnon) {
            showToast('You do not have permission to create suggestions');
            return;
        }
    }

    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    const el = document.getElementById(view + 'View');
    if (el) {
        el.style.display = '';
        el.classList.add('active');
    }
    _markActiveSidebar(view);

    // Disconnect existing WebSocket
    _callbacks.disconnectWs();
    // Stop the Claude Queue poll when leaving its view
    _callbacks.stopClaudeQueuePolling();
    state.currentView = view;

    switch (view) {
        case 'list':
            state.myDraftsMode = false;
            _callbacks.loadSuggestions();
            break;
        case 'dashboard': _callbacks.loadDashboardView(); break;
        case 'detail': _callbacks.loadDetail(data); break;
        case 'settings': _callbacks.loadSettings(); break;
        case 'claudeLogs': _callbacks.loadClaudeLogs(); break;
        case 'claudeLogDetail': _callbacks.loadClaudeLogDetail(data); break;
        case 'claudeQueue': _callbacks.loadClaudeQueue(); break;
        case 'spending': _callbacks.loadSpendingDashboard(); break;
        case 'plans': _callbacks.loadPlans(); break;
        case 'planDetail': _callbacks.loadPlanDetail(data); break;
        case 'tasks': _callbacks.loadAllTasks(); break;
        case 'login': {
            const regDisabled = state.settings.registrationsEnabled === false;
            const createLink = document.getElementById('createAccountLink');
            const closedMsg = document.getElementById('registrationsClosedMsg');
            if (createLink) createLink.style.display = regDisabled ? 'none' : '';
            if (closedMsg) closedMsg.style.display = regDisabled ? '' : 'none';
            break;
        }
        case 'create': {
            const nameGroup = document.getElementById('anonNameGroup');
            if (nameGroup) nameGroup.style.display = state.loggedIn ? 'none' : '';
            _callbacks.updateSaveAsDraftBtn();
            break;
        }
    }
}
