package com.example1.bddllm.analysis;

public final class LlmFailure extends RuntimeException {
    public final String code;
    public final Double retryAfter;

    public LlmFailure(String code) {
        this(code, null);
    }

    public LlmFailure(String code, Double retryAfter) {
        super(code);
        this.code = code;
        this.retryAfter = retryAfter;
    }
}
