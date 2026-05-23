package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Immutable cost data parsed from a Claude CLI JSON response: model, token usage,
 * dollar cost, and wall-clock duration. Produced by {@link ClaudeService} after a
 * CLI call completes; consumed by callers (e.g. expert reviews) that need to
 * record how much each piece of work cost.
 */
public final class ClaudeCostInfo {

    private final String model;
    private final long inputTokens;
    private final long outputTokens;
    private final long cacheReadInputTokens;
    private final long cacheCreationInputTokens;
    private final BigDecimal costUsd;
    private final long durationMs;

    public ClaudeCostInfo(String model,
                          long inputTokens,
                          long outputTokens,
                          long cacheReadInputTokens,
                          long cacheCreationInputTokens,
                          BigDecimal costUsd,
                          long durationMs) {
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadInputTokens = cacheReadInputTokens;
        this.cacheCreationInputTokens = cacheCreationInputTokens;
        this.costUsd = costUsd != null ? costUsd : BigDecimal.ZERO;
        this.durationMs = durationMs;
    }

    public String getModel() { return model; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCacheReadInputTokens() { return cacheReadInputTokens; }
    public long getCacheCreationInputTokens() { return cacheCreationInputTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public long getDurationMs() { return durationMs; }

    /** Total token volume across input, output, and cache flows. */
    public long getTotalTokens() {
        return inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens;
    }

    /**
     * Parse a Claude CLI JSON result envelope into a cost record. The Claude CLI
     * emits {@code total_cost_usd}, {@code duration_ms}, and a {@code usage}
     * sub-object with token counts. Any missing field is treated as zero so a
     * partially-populated response still yields a usable record.
     *
     * @param root  the parsed JSON root, never null
     * @param model the model string from the calling site (the CLI does not
     *              always echo it back in the envelope)
     * @return a populated cost record, never null
     */
    public static ClaudeCostInfo fromCliJson(JsonNode root, String model) {
        if (root == null) {
            return new ClaudeCostInfo(model, 0, 0, 0, 0, BigDecimal.ZERO, 0);
        }
        BigDecimal cost = BigDecimal.ZERO;
        if (root.has("total_cost_usd") && root.get("total_cost_usd").isNumber()) {
            cost = BigDecimal.valueOf(root.get("total_cost_usd").asDouble());
        }
        long durationMs = root.has("duration_ms") ? root.get("duration_ms").asLong(0L) : 0L;

        long input = 0, output = 0, cacheRead = 0, cacheCreation = 0;
        JsonNode usage = root.get("usage");
        if (usage != null && usage.isObject()) {
            input = usage.has("input_tokens") ? usage.get("input_tokens").asLong(0L) : 0L;
            output = usage.has("output_tokens") ? usage.get("output_tokens").asLong(0L) : 0L;
            cacheRead = usage.has("cache_read_input_tokens")
                    ? usage.get("cache_read_input_tokens").asLong(0L) : 0L;
            cacheCreation = usage.has("cache_creation_input_tokens")
                    ? usage.get("cache_creation_input_tokens").asLong(0L) : 0L;
        }
        return new ClaudeCostInfo(model, input, output, cacheRead, cacheCreation, cost, durationMs);
    }
}
