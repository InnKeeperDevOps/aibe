export const state = {
    loggedIn: false,
    username: '',
    role: '',
    setupRequired: false,
    permissions: [],
    currentSuggestion: null,
    currentStatus: null,
    settings: {},
    ws: null,
    notificationWs: null,
    notificationWsReconnectTimeout: null,
    recommendations: [],
    pendingRecommendationResultId: null,
    clarification: {
        questions: [],
        answers: [],
        currentIndex: 0,
        active: false
    },
    tasks: [],
    taskTimer: null,
    // When true, the plan text + task list show the technical fields
    // (planSummary, task.title/description) instead of the user-facing
    // display fields. Toggled from the suggestion detail page.
    showTechnicalPlan: false,
    currentSuggestionData: null,
    expertReview: {
        currentStep: -1,
        totalSteps: 0,
        experts: [],
        active: false,
        notes: []
    },
    expertClarification: {
        questions: [],
        answers: [],
        currentIndex: 0,
        active: false,
        expertName: ''
    },
    approvalPendingCount: 0,
    myDraftsMode: false,
    listFilters: {
        search: '',
        status: '',
        priority: '',
        sortBy: 'created',
        sortDir: 'desc'
    },
    searchDebounceTimer: null,
    executionQueue: { maxConcurrent: 1, activeCount: 0, queuedCount: 0, queued: [] }
};
