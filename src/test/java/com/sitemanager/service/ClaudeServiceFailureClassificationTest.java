package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceFailureClassificationTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @InjectMocks
    private ClaudeService claudeService;

    // -------------------------------------------------------------------------
    // ClaudeFailureType enum
    // -------------------------------------------------------------------------

    @Test
    void enumHasBothValues() {
        assertThat(ClaudeService.ClaudeFailureType.values())
                .containsExactlyInAnyOrder(
                        ClaudeService.ClaudeFailureType.TRANSIENT,
                        ClaudeService.ClaudeFailureType.PERMANENT);
    }

    // -------------------------------------------------------------------------
    // ClaudeExecutionException
    // -------------------------------------------------------------------------

    @Test
    void exceptionStoresTypeAndAttemptNumber() {
        ClaudeService.ClaudeExecutionException ex = new ClaudeService.ClaudeExecutionException(
                "boom", ClaudeService.ClaudeFailureType.TRANSIENT, 3);
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getType()).isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
        assertThat(ex.getAttemptNumber()).isEqualTo(3);
    }

    @Test
    void exceptionIsPermanentWhenSpecified() {
        ClaudeService.ClaudeExecutionException ex = new ClaudeService.ClaudeExecutionException(
                "auth failed", ClaudeService.ClaudeFailureType.PERMANENT, 1);
        assertThat(ex.getType()).isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
    }

    @Test
    void exceptionIsRuntimeException() {
        ClaudeService.ClaudeExecutionException ex = new ClaudeService.ClaudeExecutionException(
                "msg", ClaudeService.ClaudeFailureType.TRANSIENT, 1);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    // -------------------------------------------------------------------------
    // classifyFailure — PERMANENT conditions
    // -------------------------------------------------------------------------

    @Test
    void exitCode2IsPermanent() {
        assertThat(claudeService.classifyFailure("some output", 2, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
    }

    @Test
    void successfulExitWithEmptyOutputIsPermanent() {
        assertThat(claudeService.classifyFailure("", 0, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
    }

    @Test
    void successfulExitWithBlankOutputIsPermanent() {
        assertThat(claudeService.classifyFailure("   ", 0, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
    }

    @Test
    void successfulExitWithNullOutputIsPermanent() {
        assertThat(claudeService.classifyFailure(null, 0, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"is_error\":true,\"result\":\"authentication failed\"}",
            "{\"is_error\":true,\"result\":\"Unauthorized access\"}",
            "{\"is_error\":true,\"result\":\"invalid api key\"}",
            "{\"is_error\":true,\"result\":\"model not found\"}",
            "{\"is_error\":true,\"result\":\"model_not_found\"}",
            "{\"is_error\":true,\"result\":\"invalid_api_key\"}",
            "{\"is_error\":true,\"result\":\"Not logged in · Please run /login\"}",
            "{\"is_error\":true,\"result\":\"not logged in\"}"
    })
    void isErrorWithAuthOrModelNotFoundIsPermanent(String rawOutput) {
        assertThat(claudeService.classifyFailure(rawOutput, 1, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
    }

    // -------------------------------------------------------------------------
    // classifyFailure — TRANSIENT conditions
    // -------------------------------------------------------------------------

    @Test
    void ioCauseIsTransient() {
        assertThat(claudeService.classifyFailure("", 1, new IOException("pipe broken")))
                .isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
    }

    @Test
    void causeMessageTimedOutIsTransient() {
        RuntimeException cause = new RuntimeException("process timed out");
        assertThat(claudeService.classifyFailure("some output", 1, cause))
                .isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
    }

    @Test
    void outputContainingRateLimitIsTransient() {
        assertThat(claudeService.classifyFailure("Error: rate limit exceeded", 1, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
    }

    @Test
    void outputContainingOverloadedIsTransient() {
        assertThat(claudeService.classifyFailure("Service overloaded, try again", 1, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
    }

    @Test
    void nonZeroExitCodeWithNonAuthOutputIsTransient() {
        assertThat(claudeService.classifyFailure("some random error", 1, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
    }

    @Test
    void nonZeroExitCodeWithNullOutputIsTransient() {
        // exitCode != 0 and no output — not exit code 2, so TRANSIENT
        assertThat(claudeService.classifyFailure(null, 1, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
    }

    // -------------------------------------------------------------------------
    // classifyFailure — precedence: PERMANENT checked before TRANSIENT
    // -------------------------------------------------------------------------

    @Test
    void exitCode2TakesPrecedenceOverRateLimitInOutput() {
        // Even if output says "rate limit", exit code 2 → PERMANENT
        assertThat(claudeService.classifyFailure("rate limit exceeded", 2, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
    }

    @Test
    void exitCode2TakesPrecedenceOverIoCause() {
        // Even if cause is IOException, exit code 2 → PERMANENT
        assertThat(claudeService.classifyFailure("any output", 2, new IOException("broken pipe")))
                .isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
    }

    // -------------------------------------------------------------------------
    // classifyFailure — default/edge cases
    // -------------------------------------------------------------------------

    @Test
    void normalOutputWithExitZeroAndNonBlankIsTransient() {
        // Normal successful output: exit 0, non-empty → TRANSIENT (no error to classify)
        assertThat(claudeService.classifyFailure("response text", 0, null))
                .isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
    }
}
