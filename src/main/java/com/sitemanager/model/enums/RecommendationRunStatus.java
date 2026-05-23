package com.sitemanager.model.enums;

/**
 * Lifecycle of a single recommendation run. PENDING is the freshly-created
 * state before any background work has started; IN_PROGRESS means the AI call
 * is running; DONE / ERROR are terminal.
 */
public enum RecommendationRunStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    ERROR
}
