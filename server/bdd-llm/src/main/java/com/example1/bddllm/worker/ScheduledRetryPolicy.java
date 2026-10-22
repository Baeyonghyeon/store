package com.example1.bddllm.worker;

import java.time.Instant;
import java.util.Set;

public final class ScheduledRetryPolicy {
    public static final Set<String> RETRYABLE_ERRORS =
            Set.of("llm_rate_limited", "llm_server_error");
    private static final double MAX_RETRY_AFTER_SECONDS = 86400;

    private ScheduledRetryPolicy() {}

    public static Instant eligibleAt(int attemptCount, int maxAttempts,
                                    int scheduledRetryCount, String errorCode,
                                    Double retryAfter, Instant failedAt) {
        if (!RETRYABLE_ERRORS.contains(errorCode) || scheduledRetryCount != 0
                || attemptCount != maxAttempts || maxAttempts >= 4) return null;
        if (retryAfter != null && Double.isFinite(retryAfter) && retryAfter >= 0) {
            if (retryAfter > MAX_RETRY_AFTER_SECONDS) return null;
            return failedAt.plusNanos(Math.round(retryAfter * 1_000_000_000D));
        }
        return failedAt;
    }
}
