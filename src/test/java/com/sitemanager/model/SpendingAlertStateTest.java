package com.sitemanager.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SpendingAlertState}: the entity must safely default null
 * monetary values to zero so the DB-side NOT NULL constraint never breaks
 * a saved row, and the convenience constructor must populate every field
 * the unique constraint relies on.
 */
class SpendingAlertStateTest {

    @Test
    void defaultConstructor_setsSpendAndLimitToZero() {
        SpendingAlertState state = new SpendingAlertState();

        assertThat(state.getSpendAtAlert()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(state.getLimitAtAlert()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void onCreate_setsFiredAtWhenMissing() {
        SpendingAlertState state = new SpendingAlertState();
        state.setScope(SpendingAlertState.Scope.GLOBAL);
        state.setScopeKey("GLOBAL");
        state.setWindowKey("NEVER");
        state.setThresholdPercent(75);

        // Simulate the JPA lifecycle callback
        state.setFiredAt(null);
        invokePrePersist(state);

        assertThat(state.getFiredAt()).isNotNull();
    }

    @Test
    void onCreate_setsSpendAndLimitToZeroWhenNull() {
        SpendingAlertState state = new SpendingAlertState();
        state.setSpendAtAlert(null);
        state.setLimitAtAlert(null);

        invokePrePersist(state);

        assertThat(state.getSpendAtAlert()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(state.getLimitAtAlert()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void convenienceConstructor_populatesAllUniqueConstraintColumns() {
        SpendingAlertState state = new SpendingAlertState(
                SpendingAlertState.Scope.PER_SUGGESTION,
                "42",
                "LIFETIME",
                90,
                new BigDecimal("9.50"),
                new BigDecimal("10"));

        assertThat(state.getScope()).isEqualTo(SpendingAlertState.Scope.PER_SUGGESTION);
        assertThat(state.getScopeKey()).isEqualTo("42");
        assertThat(state.getWindowKey()).isEqualTo("LIFETIME");
        assertThat(state.getThresholdPercent()).isEqualTo(90);
        assertThat(state.getSpendAtAlert()).isEqualByComparingTo("9.50");
        assertThat(state.getLimitAtAlert()).isEqualByComparingTo("10");
    }

    @Test
    void convenienceConstructor_acceptsNullMonetaryValues() {
        SpendingAlertState state = new SpendingAlertState(
                SpendingAlertState.Scope.GLOBAL, "GLOBAL", "NEVER",
                100, null, null);

        assertThat(state.getSpendAtAlert()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(state.getLimitAtAlert()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static void invokePrePersist(SpendingAlertState state) {
        try {
            var method = SpendingAlertState.class.getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(state);
        } catch (Exception e) {
            throw new AssertionError("Failed to invoke @PrePersist callback", e);
        }
    }
}
