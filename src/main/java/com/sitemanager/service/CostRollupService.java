package com.sitemanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.dto.CostSummaryDto;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes per-suggestion and system-wide cost roll-ups from the review
 * cost ledger, and pushes refreshed totals out over WebSocket so any open
 * dashboards see spend update in real time as reviews complete.
 *
 * <p>Reads are pure aggregates over {@link ExpertReviewCostRepository}, so
 * the totals always reflect the current state of the ledger — there is no
 * cached number that could drift from the source of truth.
 */
@Service
public class CostRollupService {

    private static final Logger log = LoggerFactory.getLogger(CostRollupService.class);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ExpertReviewCostRepository costRepository;
    private final SuggestionWebSocketHandler webSocketHandler;
    private final UserNotificationWebSocketHandler userNotificationHandler;

    public CostRollupService(ExpertReviewCostRepository costRepository,
                             SuggestionWebSocketHandler webSocketHandler,
                             UserNotificationWebSocketHandler userNotificationHandler) {
        this.costRepository = costRepository;
        this.webSocketHandler = webSocketHandler;
        this.userNotificationHandler = userNotificationHandler;
    }

    /**
     * Totals for one suggestion: how many reviews have run, how many tokens
     * they used, and the combined dollar cost. Always returns a populated
     * object — {@code null} from the repository becomes zero.
     */
    public CostSummaryDto getSuggestionSummary(Long suggestionId) {
        if (suggestionId == null) {
            return new CostSummaryDto(null, 0L, 0L, BigDecimal.ZERO);
        }
        long count = costRepository.countBySuggestionId(suggestionId);
        BigDecimal cost = costRepository.sumCostBySuggestionId(suggestionId);
        Long tokens = costRepository.sumTokensBySuggestionId(suggestionId);
        return new CostSummaryDto(
                suggestionId,
                count,
                tokens != null ? tokens : 0L,
                cost != null ? cost : BigDecimal.ZERO
        );
    }

    /**
     * Totals across every recorded review on the system. Used by the global
     * admin dashboard and as the source for system-wide alerts.
     */
    public CostSummaryDto getGlobalSummary() {
        long count = costRepository.count();
        BigDecimal cost = costRepository.sumCostGlobal();
        Long tokens = costRepository.sumTokensGlobal();
        return new CostSummaryDto(
                null,
                count,
                tokens != null ? tokens : 0L,
                cost != null ? cost : BigDecimal.ZERO
        );
    }

    /**
     * Pushes the latest per-suggestion total to clients watching that
     * suggestion and the latest global total to every connected user, so
     * spend dashboards refresh as soon as a review finishes. Best-effort —
     * a broadcast failure must never disrupt the calling pipeline.
     */
    public void broadcastUpdatedTotals(Long suggestionId) {
        try {
            if (suggestionId != null) {
                CostSummaryDto suggestion = getSuggestionSummary(suggestionId);
                String json = objectMapper.writeValueAsString(suggestion);
                webSocketHandler.sendToSuggestion(suggestionId,
                        "{\"type\":\"cost_summary\",\"summary\":" + json + "}");
            }

            CostSummaryDto global = getGlobalSummary();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "global_cost_summary");
            payload.put("reviewCount", global.getReviewCount());
            payload.put("totalTokens", global.getTotalTokens());
            payload.put("totalCostUsd", global.getTotalCostUsd());
            payload.put("displayCostUsd", global.getDisplayCostUsd());
            userNotificationHandler.broadcastToAll(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize cost summary broadcast: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to broadcast cost totals for suggestion {}: {}",
                    suggestionId, e.getMessage());
        }
    }
}
