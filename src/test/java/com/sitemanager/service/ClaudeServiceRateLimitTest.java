package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.ClaudeCliLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceRateLimitTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @Mock
    private ClaudeCliLogRepository cliLogRepository;

    @InjectMocks
    private ClaudeService claudeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(claudeService, "claudeMaxCallsPerMinute", 2);
        claudeService.initRateLimiter();
    }

    @Test
    void allowsCallsUpToLimit_withoutBlocking() throws Exception {
        long start = System.currentTimeMillis();
        claudeService.acquireRateLimit();
        claudeService.acquireRateLimit();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 100, "Two calls within limit should complete in <100ms but took " + elapsed + "ms");
    }

    @Test
    void slidingWindow_expiresOldTimestamps() throws Exception {
        // Pre-fill timestamps with values 70 seconds in the past (outside the 60s window)
        long[] oldTimestamps = new long[]{
                System.currentTimeMillis() - 70_000L,
                System.currentTimeMillis() - 70_000L
        };
        ReflectionTestUtils.setField(claudeService, "callTimestamps", oldTimestamps);
        ReflectionTestUtils.setField(claudeService, "timestampHead", 0);

        long start = System.currentTimeMillis();
        claudeService.acquireRateLimit();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 100, "Expired timestamps should not cause sleeping, but took " + elapsed + "ms");
    }

    @Test
    void circularBuffer_wrapsCorrectly() throws Exception {
        // With limit=2, set head to 1 (limit-1) so next slot is index 1, then wraps to index 0
        ReflectionTestUtils.setField(claudeService, "timestampHead", 1);

        long beforeFirst = System.currentTimeMillis();
        claudeService.acquireRateLimit();
        // First call: slot = 1 % 2 = 1, callTimestamps[1] is written, head becomes 2
        claudeService.acquireRateLimit();
        // Second call: slot = 2 % 2 = 0, callTimestamps[0] is written (wraparound), head becomes 3

        long[] timestamps = (long[]) ReflectionTestUtils.getField(claudeService, "callTimestamps");
        assertNotNull(timestamps);
        // Slot 0 should have been overwritten on wraparound
        assertTrue(timestamps[0] >= beforeFirst,
                "callTimestamps[0] should be updated on circular wraparound");
        // timestampHead increments monotonically — it should now be 3, not reset to 0 or 1
        int head = (int) ReflectionTestUtils.getField(claudeService, "timestampHead");
        assertEquals(3, head, "timestampHead increments monotonically and should be 3");
    }

    @Test
    void defaultCallsPerMinute_isTen() {
        // A freshly constructed service (before initRateLimiter is called) initialises its
        // circular buffer with 10 slots — the application-level default for calls per minute.
        ClaudeService freshService = new ClaudeService(settingsRepository, cliLogRepository);
        long[] timestamps = (long[]) ReflectionTestUtils.getField(freshService, "callTimestamps");
        assertNotNull(timestamps);
        assertEquals(10, timestamps.length, "Default call-buffer size should be 10");
    }

    @Test
    void isThreadSafe_underConcurrentLoad() throws Exception {
        // Reset to a high limit so 20 threads can all call without sleeping
        ReflectionTestUtils.setField(claudeService, "claudeMaxCallsPerMinute", 1000);
        claudeService.initRateLimiter();

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    claudeService.acquireRateLimit();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Thread pool did not finish in time");

        for (Future<?> f : futures) {
            f.get(); // re-throws any unexpected exceptions
        }

        assertEquals(0, errorCount.get(), "No exceptions should occur under concurrent load");
    }

    @Test
    void rateLimiter_reinitializesOnCallToInitRateLimiter() {
        // Change limit and reinitialize
        ReflectionTestUtils.setField(claudeService, "claudeMaxCallsPerMinute", 5);
        claudeService.initRateLimiter();

        long[] timestamps = (long[]) ReflectionTestUtils.getField(claudeService, "callTimestamps");
        assertNotNull(timestamps);
        assertEquals(5, timestamps.length, "callTimestamps array length should match new limit of 5");

        int head = (int) ReflectionTestUtils.getField(claudeService, "timestampHead");
        assertEquals(0, head, "timestampHead should be reset to 0 after reinitialization");
    }

    // -------------------------------------------------------------------------
    // Rate-limit keyword in output → classified as TRANSIENT
    // -------------------------------------------------------------------------

    @Test
    void rateLimitKeywordInOutput_isClassifiedAsTransient() {
        ClaudeService.ClaudeFailureType type =
                claudeService.classifyFailure("Error: rate limit exceeded, please try again later", 1, null);
        assertEquals(ClaudeService.ClaudeFailureType.TRANSIENT, type,
                "Output containing 'rate limit' should be classified as TRANSIENT");
    }
}
