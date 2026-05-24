package com.sitemanager.model.enums;

/**
 * Lifecycle of a single recommendation bullet within a run.
 *
 * PENDING is the freshly-generated state, shown in the active recommendation
 * list. ACTED_ON marks a bullet that the admin has turned into a tracked
 * suggestion — it is hidden from the active list but kept in run history
 * (linked to the created suggestion) so nothing is lost.
 */
public enum RecommendationResultStatus {
    PENDING,
    ACTED_ON
}
