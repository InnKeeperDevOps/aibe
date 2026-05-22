/**
 * ES module entry point.
 *
 * Imports all module functions, wires up inter-module callbacks, and exposes
 * window.app so that existing inline HTML onclick="app.foo()" handlers keep
 * working without modifying the templates.
 */

import { state } from './modules/state.js';
import { api } from './modules/api.js';
import { showToast } from './modules/utils.js';

import {
    registerAuthCallbacks,
    checkAuth,
    updateHeader,
    initProjectDefinition,
    setup,
    login,
    logout,
    register,
} from './modules/auth.js';

import {
    registerNavigationCallbacks,
    navigate,
} from './modules/navigation.js';

import {
    onSearchInput,
    applyFilters,
    loadSuggestions,
    restoreFiltersFromUrl,
    renderSuggestionItem,
} from './modules/suggestions.js';

import {
    registerSuggestionDetailCallbacks,
    loadDetail,
    renderMessages,
    renderMessage,
    approve,
    deny,
    changePriority,
    approveSuggestion,
    denySuggestion,
    submitDenySuggestion,
    retryPr,
    retryExecution,
    restartPlan,
    forceReApproval,
    vote,
} from './modules/suggestionDetail.js';

import { renderTasks, updateTask } from './modules/tasks.js';

import {
    renderExpertReview,
    updateExpertReview,
    addExpertNote,
    renderExpertNotes,
    loadReviewSummary,
    renderReviewSummary,
    toggleReviewSummary,
    showFullReviews,
    showExpertClarificationWizard,
    renderExpertClarificationStep,
    submitExpertClarifications,
} from './modules/expertReview.js';

import {
    showClarificationWizard,
    hideClarificationWizard,
    renderClarificationStep,
    saveClarificationAnswer,
    nextClarification,
    prevClarification,
    submitClarifications,
    loadClarificationQuestions,
} from './modules/clarification.js';

import {
    showMyDrafts,
    showAllSuggestions,
    loadMyDrafts,
    renderDraftCards,
    openEditDraftModal,
    closeEditDraftModal,
    saveEditDraft,
    submitDraftConfirm,
} from './modules/drafts.js';

import {
    fetchRecommendations,
    pollRecommendationTask,
    renderRecommendationsError,
    closeRecommendationsModal,
    prefillFromRecommendation,
} from './modules/recommendations.js';

import {
    openProjectDefinition,
    startNewProjectDefinition,
    showProjectDefinitionModal,
    submitProjectDefinitionAnswer,
    renderProjectDefinitionComplete,
    expandProjectDefinitionContent,
    closeProjectDefinitionModal,
    retryProjectDefinition,
    downloadProjectDefinition,
    openImportDefinitionModal,
    closeImportDefinitionModal,
    handleImportFileSelect,
    processImportFile,
    clearImportFile,
    formatFileSize,
    submitImportDefinition,
    onProjectDefinitionUpdate,
} from './modules/projectDefinition.js';

import {
    loadClaudeLogs,
    viewClaudeLog,
} from './modules/claudeLogs.js';

import {
    loadSettings,
    loadGroups,
    editGroup,
    cancelGroupEdit,
    saveGroup,
    deleteGroup,
    showUserTab,
    loadPendingUsers,
    approveUser,
    denyUser,
    loadAllUsers,
    assignUserGroup,
    saveSettings,
    generateGitSshKey,
    copyGitSshPublicKey,
    createAdmin,
} from './modules/settings.js';

import {
    refreshClaudeCliLogin,
    startClaudeCliLogin,
    submitClaudeCliCode,
    cancelClaudeCliLogin,
    clearClaudeCliCredentials,
} from './modules/claudeCliLogin.js';

import {
    loadDashboardView,
    renderLeaderboard,
    renderUserHistory,
} from './modules/dashboard.js';

import {
    connectWs,
    disconnectWs,
    connectNotificationsWs,
    updateApprovalBanner,
} from './modules/websocket.js';

// ---------------------------------------------------------------------------
// Functions not yet extracted to dedicated modules
// ---------------------------------------------------------------------------

async function createSuggestion(e) {
    e.preventDefault();
    const data = await api('/suggestions', {
        method: 'POST',
        body: JSON.stringify({
            title: document.getElementById('createTitle').value,
            description: document.getElementById('createDescription').value,
            authorName: document.getElementById('createAuthorName').value || undefined,
            priority: document.getElementById('createPriority').value || 'MEDIUM'
        })
    });
    if (data.error) {
        showToast(data.error);
        return;
    }
    document.getElementById('createForm').reset();
    navigate('detail', data.id);
}

async function saveAsDraft(e) {
    e.preventDefault();
    const data = await api('/suggestions', {
        method: 'POST',
        body: JSON.stringify({
            title: document.getElementById('createTitle').value,
            description: document.getElementById('createDescription').value,
            priority: document.getElementById('createPriority').value || 'MEDIUM',
            isDraft: true
        })
    });
    if (data.error) {
        showToast(data.error);
        return;
    }
    document.getElementById('createForm').reset();
    showToast('Draft saved.');
    navigate('list');
}

async function reply() {
    const input = document.getElementById('replyInput');
    const content = input.value.trim();
    if (!content) return;
    input.value = '';
    const id = state.currentSuggestion;
    await api('/suggestions/' + id + '/messages', {
        method: 'POST',
        body: JSON.stringify({ content, senderName: state.username || undefined })
    });
}

// ---------------------------------------------------------------------------
// Wire up cross-module callbacks
// ---------------------------------------------------------------------------

registerAuthCallbacks({
    connectNotificationsWs,
    showProjectDefinitionModal,
});

registerNavigationCallbacks({
    loadSuggestions,
    loadDashboardView,
    loadDetail,
    loadSettings,
    loadClaudeLogs,
    disconnectWs,
    updateSaveAsDraftBtn: () => {
        const btn = document.getElementById('saveAsDraftBtn');
        if (!btn) return;
        const allowAnon = state.settings.allowAnonymousSuggestions;
        const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
        btn.style.display = (state.loggedIn && (hasPermission || allowAnon)) ? '' : 'none';
    },
});

registerSuggestionDetailCallbacks({
    connectWs,
    showToast,
    updateApprovalBanner,
});

// ---------------------------------------------------------------------------
// Assemble window.app for inline HTML event handlers
// ---------------------------------------------------------------------------

window.app = {
    state,

    // auth
    checkAuth,
    updateHeader,
    initProjectDefinition,
    setup,
    login,
    logout,
    register,

    // navigation
    navigate,

    // suggestions list
    loadSuggestions,
    onSearchInput,
    applyFilters,
    restoreFiltersFromUrl,
    renderSuggestionItem,

    // suggestion detail
    loadDetail,
    renderMessages,
    renderMessage,
    approve,
    deny,
    changePriority,
    approveSuggestion,
    denySuggestion,
    submitDenySuggestion,
    retryPr,
    retryExecution,
    restartPlan,
    forceReApproval,
    vote,

    // tasks
    renderTasks,
    updateTask,

    // expert review
    renderExpertReview,
    updateExpertReview,
    addExpertNote,
    renderExpertNotes,
    loadReviewSummary,
    renderReviewSummary,
    toggleReviewSummary,
    showFullReviews,
    showExpertClarificationWizard,
    renderExpertClarificationStep,
    submitExpertClarifications,

    // clarification
    showClarificationWizard,
    hideClarificationWizard,
    renderClarificationStep,
    saveClarificationAnswer,
    nextClarification,
    prevClarification,
    submitClarifications,
    loadClarificationQuestions,

    // drafts
    showMyDrafts,
    showAllSuggestions,
    loadMyDrafts,
    renderDraftCards,
    openEditDraftModal,
    closeEditDraftModal,
    saveEditDraft,
    submitDraftConfirm,

    // recommendations
    fetchRecommendations,
    pollRecommendationTask,
    renderRecommendationsError,
    closeRecommendationsModal,
    prefillFromRecommendation,

    // project definition
    openProjectDefinition,
    startNewProjectDefinition,
    showProjectDefinitionModal,
    submitProjectDefinitionAnswer,
    renderProjectDefinitionComplete,
    expandProjectDefinitionContent,
    closeProjectDefinitionModal,
    retryProjectDefinition,
    downloadProjectDefinition,
    openImportDefinitionModal,
    closeImportDefinitionModal,
    handleImportFileSelect,
    processImportFile,
    clearImportFile,
    formatFileSize,
    submitImportDefinition,
    onProjectDefinitionUpdate,

    // claude logs
    loadClaudeLogs,
    viewClaudeLog,

    // settings / admin
    loadSettings,
    loadGroups,
    editGroup,
    cancelGroupEdit,
    saveGroup,
    deleteGroup,
    showUserTab,
    loadPendingUsers,
    approveUser,
    denyUser,
    loadAllUsers,
    assignUserGroup,
    saveSettings,
    generateGitSshKey,
    copyGitSshPublicKey,
    createAdmin,

    // claude cli login
    refreshClaudeCliLogin,
    startClaudeCliLogin,
    submitClaudeCliCode,
    cancelClaudeCliLogin,
    clearClaudeCliCredentials,

    // dashboard
    loadDashboardView,
    renderLeaderboard,
    renderUserHistory,

    // websocket
    connectWs,
    disconnectWs,
    connectNotificationsWs,
    updateApprovalBanner,

    // create / draft / reply (not in dedicated modules)
    createSuggestion,
    saveAsDraft,
    reply,

    // utilities
    showToast,
};

// ---------------------------------------------------------------------------
// Bootstrap
// ---------------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', () => {
    checkAuth();
});
