package com.sitemanager.service;

import com.sitemanager.repository.ClaudeCliLogRepository;
import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for the per-session cost record storage exposed by
 * {@link ClaudeService#pollSessionCost(String)}.
 */
class ClaudeServiceCostTrackingTest {

    private ClaudeService service;

    @BeforeEach
    void setUp() {
        service = new ClaudeService(
                mock(SiteSettingsRepository.class),
                mock(ClaudeCliLogRepository.class)
        );
    }

    @Test
    void pollSessionCost_returnsNullWhenNothingRecorded() {
        assertThat(service.pollSessionCost("unknown")).isNull();
    }

    @Test
    void recordSessionCost_storesAndPollReturnsThenClears() {
        ClaudeCostInfo info = new ClaudeCostInfo(
                "claude-opus", 100, 50, 200, 25, new BigDecimal("0.123"), 1500);

        service.recordSessionCost("session-1", info);

        ClaudeCostInfo first = service.pollSessionCost("session-1");
        assertThat(first).isNotNull();
        assertThat(first.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.123"));
        assertThat(first.getInputTokens()).isEqualTo(100);
        assertThat(first.getDurationMs()).isEqualTo(1500);

        // poll consumes the entry — second call returns null
        assertThat(service.pollSessionCost("session-1")).isNull();
    }

    @Test
    void recordSessionCost_overwritesPriorEntryForSameSession() {
        ClaudeCostInfo first = new ClaudeCostInfo(
                "modelA", 1, 1, 0, 0, new BigDecimal("0.10"), 100);
        ClaudeCostInfo second = new ClaudeCostInfo(
                "modelB", 10, 10, 0, 0, new BigDecimal("0.20"), 200);

        service.recordSessionCost("s", first);
        service.recordSessionCost("s", second);

        ClaudeCostInfo polled = service.pollSessionCost("s");
        assertThat(polled).isNotNull();
        assertThat(polled.getModel()).isEqualTo("modelB");
        assertThat(polled.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.20"));
    }

    @Test
    void recordSessionCost_ignoresNullArguments() {
        service.recordSessionCost(null, new ClaudeCostInfo("m", 0, 0, 0, 0, BigDecimal.ZERO, 0));
        service.recordSessionCost("s", null);
        assertThat(service.pollSessionCost("s")).isNull();
    }

    @Test
    void pollSessionCost_nullSessionIdReturnsNull() {
        assertThat(service.pollSessionCost(null)).isNull();
    }

    @Test
    void recordSessionCost_isolatesDifferentSessionIds() {
        service.recordSessionCost("a", new ClaudeCostInfo(
                "m", 1, 2, 0, 0, new BigDecimal("0.05"), 10));
        service.recordSessionCost("b", new ClaudeCostInfo(
                "m", 3, 4, 0, 0, new BigDecimal("0.07"), 20));

        ClaudeCostInfo a = service.pollSessionCost("a");
        ClaudeCostInfo b = service.pollSessionCost("b");

        assertThat(a.getOutputTokens()).isEqualTo(2);
        assertThat(b.getOutputTokens()).isEqualTo(4);
    }
}
