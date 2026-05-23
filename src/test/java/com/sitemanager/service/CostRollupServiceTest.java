package com.sitemanager.service;

import com.sitemanager.dto.CostSummaryDto;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CostRollupService}: the aggregate read paths plus the
 * WebSocket broadcast that keeps dashboards in sync with the ledger.
 */
class CostRollupServiceTest {

    private ExpertReviewCostRepository costRepository;
    private SuggestionWebSocketHandler suggestionWebSocket;
    private UserNotificationWebSocketHandler userNotifications;
    private CostRollupService service;

    @BeforeEach
    void setUp() {
        costRepository = mock(ExpertReviewCostRepository.class);
        suggestionWebSocket = mock(SuggestionWebSocketHandler.class);
        userNotifications = mock(UserNotificationWebSocketHandler.class);
        service = new CostRollupService(costRepository, suggestionWebSocket, userNotifications);
    }

    @Test
    void getSuggestionSummary_aggregatesFromRepository() {
        when(costRepository.countBySuggestionId(42L)).thenReturn(3L);
        when(costRepository.sumCostBySuggestionId(42L)).thenReturn(new BigDecimal("1.50"));
        when(costRepository.sumTokensBySuggestionId(42L)).thenReturn(9_000L);

        CostSummaryDto dto = service.getSuggestionSummary(42L);

        assertThat(dto.getSuggestionId()).isEqualTo(42L);
        assertThat(dto.getReviewCount()).isEqualTo(3L);
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo("1.50");
        assertThat(dto.getTotalTokens()).isEqualTo(9_000L);
        assertThat(dto.getDisplayCostUsd()).isEqualTo("$1.5000");
    }

    @Test
    void getSuggestionSummary_handlesEmptyLedgerAsZero() {
        when(costRepository.countBySuggestionId(7L)).thenReturn(0L);
        when(costRepository.sumCostBySuggestionId(7L)).thenReturn(null);
        when(costRepository.sumTokensBySuggestionId(7L)).thenReturn(null);

        CostSummaryDto dto = service.getSuggestionSummary(7L);

        assertThat(dto.getReviewCount()).isZero();
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getTotalTokens()).isZero();
    }

    @Test
    void getSuggestionSummary_nullSuggestionIdReturnsZeros() {
        CostSummaryDto dto = service.getSuggestionSummary(null);
        assertThat(dto.getSuggestionId()).isNull();
        assertThat(dto.getReviewCount()).isZero();
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getGlobalSummary_aggregatesAcrossAllRecords() {
        when(costRepository.count()).thenReturn(12L);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("42.0000"));
        when(costRepository.sumTokensGlobal()).thenReturn(123_456L);

        CostSummaryDto dto = service.getGlobalSummary();

        assertThat(dto.getSuggestionId()).isNull();
        assertThat(dto.getReviewCount()).isEqualTo(12L);
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo("42.0000");
        assertThat(dto.getTotalTokens()).isEqualTo(123_456L);
        assertThat(dto.getDisplayCostUsd()).isEqualTo("$42.0000");
    }

    @Test
    void getGlobalSummary_emptyLedgerYieldsZeros() {
        when(costRepository.count()).thenReturn(0L);
        when(costRepository.sumCostGlobal()).thenReturn(null);
        when(costRepository.sumTokensGlobal()).thenReturn(null);

        CostSummaryDto dto = service.getGlobalSummary();

        assertThat(dto.getReviewCount()).isZero();
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getTotalTokens()).isZero();
    }

    @Test
    void broadcastUpdatedTotals_sendsPerSuggestionAndGlobal() {
        when(costRepository.countBySuggestionId(5L)).thenReturn(2L);
        when(costRepository.sumCostBySuggestionId(5L)).thenReturn(new BigDecimal("0.10"));
        when(costRepository.sumTokensBySuggestionId(5L)).thenReturn(100L);
        when(costRepository.count()).thenReturn(2L);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("0.10"));
        when(costRepository.sumTokensGlobal()).thenReturn(100L);

        service.broadcastUpdatedTotals(5L);

        ArgumentCaptor<String> wsCaptor = ArgumentCaptor.forClass(String.class);
        verify(suggestionWebSocket).sendToSuggestion(eq(5L), wsCaptor.capture());
        String wsMessage = wsCaptor.getValue();
        assertThat(wsMessage).contains("\"type\":\"cost_summary\"");
        assertThat(wsMessage).contains("\"suggestionId\":5");
        assertThat(wsMessage).contains("\"reviewCount\":2");
        assertThat(wsMessage).contains("\"displayCostUsd\":\"$0.1000\"");

        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(userNotifications).broadcastToAll(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("type", "global_cost_summary");
        assertThat(payload).containsEntry("reviewCount", 2L);
        assertThat(payload).containsEntry("totalTokens", 100L);
        assertThat(payload.get("displayCostUsd")).isEqualTo("$0.1000");
    }

    @Test
    void broadcastUpdatedTotals_nullSuggestionIdSkipsPerSuggestionPush() {
        when(costRepository.count()).thenReturn(0L);
        when(costRepository.sumCostGlobal()).thenReturn(null);
        when(costRepository.sumTokensGlobal()).thenReturn(null);

        service.broadcastUpdatedTotals(null);

        verify(suggestionWebSocket, never()).sendToSuggestion(any(), anyString());
        verify(userNotifications).broadcastToAll(any());
    }

    @Test
    void broadcastUpdatedTotals_doesNotPropagateBroadcastErrors() {
        when(costRepository.countBySuggestionId(1L)).thenReturn(1L);
        when(costRepository.sumCostBySuggestionId(1L)).thenReturn(new BigDecimal("1"));
        when(costRepository.sumTokensBySuggestionId(1L)).thenReturn(1L);
        when(costRepository.count()).thenReturn(1L);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("1"));
        when(costRepository.sumTokensGlobal()).thenReturn(1L);

        doThrow(new RuntimeException("ws dead")).when(suggestionWebSocket)
                .sendToSuggestion(any(), anyString());

        // Should not throw — broadcast failures must never disrupt callers.
        service.broadcastUpdatedTotals(1L);
    }
}
