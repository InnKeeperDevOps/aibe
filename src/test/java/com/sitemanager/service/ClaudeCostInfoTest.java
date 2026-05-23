package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for parsing Claude CLI cost data into a {@link ClaudeCostInfo} record
 * and for the simple getters/totals it exposes.
 */
class ClaudeCostInfoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fromCliJson_parsesAllFieldsWhenPresent() throws Exception {
        String json = """
                {
                  "type": "result",
                  "session_id": "abc",
                  "result": "ok",
                  "total_cost_usd": 0.12345,
                  "duration_ms": 4321,
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 50,
                    "cache_read_input_tokens": 200,
                    "cache_creation_input_tokens": 25
                  }
                }
                """;
        JsonNode root = mapper.readTree(json);

        ClaudeCostInfo info = ClaudeCostInfo.fromCliJson(root, "claude-opus-4-7");

        assertThat(info.getModel()).isEqualTo("claude-opus-4-7");
        assertThat(info.getInputTokens()).isEqualTo(100);
        assertThat(info.getOutputTokens()).isEqualTo(50);
        assertThat(info.getCacheReadInputTokens()).isEqualTo(200);
        assertThat(info.getCacheCreationInputTokens()).isEqualTo(25);
        assertThat(info.getDurationMs()).isEqualTo(4321);
        assertThat(info.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.12345"));
    }

    @Test
    void fromCliJson_handlesMissingUsageObject() throws Exception {
        String json = """
                {
                  "result": "ok",
                  "total_cost_usd": 0.5,
                  "duration_ms": 1000
                }
                """;
        JsonNode root = mapper.readTree(json);

        ClaudeCostInfo info = ClaudeCostInfo.fromCliJson(root, "modelX");

        assertThat(info.getInputTokens()).isZero();
        assertThat(info.getOutputTokens()).isZero();
        assertThat(info.getCacheReadInputTokens()).isZero();
        assertThat(info.getCacheCreationInputTokens()).isZero();
        assertThat(info.getDurationMs()).isEqualTo(1000);
        assertThat(info.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void fromCliJson_treatsMissingFieldsAsZero() throws Exception {
        String json = "{}";
        JsonNode root = mapper.readTree(json);

        ClaudeCostInfo info = ClaudeCostInfo.fromCliJson(root, null);

        assertThat(info.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(info.getDurationMs()).isZero();
        assertThat(info.getInputTokens()).isZero();
        assertThat(info.getOutputTokens()).isZero();
        assertThat(info.getCacheReadInputTokens()).isZero();
        assertThat(info.getCacheCreationInputTokens()).isZero();
        assertThat(info.getModel()).isNull();
    }

    @Test
    void fromCliJson_handlesNullRoot() {
        ClaudeCostInfo info = ClaudeCostInfo.fromCliJson(null, "modelA");
        assertThat(info.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(info.getDurationMs()).isZero();
        assertThat(info.getTotalTokens()).isZero();
        assertThat(info.getModel()).isEqualTo("modelA");
    }

    @Test
    void totalTokens_sumsAllTokenFields() {
        ClaudeCostInfo info = new ClaudeCostInfo("m", 10, 20, 30, 5, new BigDecimal("1.5"), 100);
        assertThat(info.getTotalTokens()).isEqualTo(10 + 20 + 30 + 5);
    }

    @Test
    void constructor_normalizesNullCostToZero() {
        ClaudeCostInfo info = new ClaudeCostInfo("m", 0, 0, 0, 0, null, 0);
        assertThat(info.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
