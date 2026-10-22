package com.example1.bddllm.common;

public class Problem extends RuntimeException {
    public final int status;
    public final String code;

    public Problem(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static Problem invalid() {
        return new Problem(422, "invalid_input", "입력 값 또는 JSON 형식이 올바르지 않습니다.");
    }

    public static Problem conflict() {
        return new Problem(409, "event_conflict", "같은 서비스·환경·event_id에 다른 내용이 이미 등록되어 있습니다.");
    }

    public static Problem missing() {
        return new Problem(404, "job_not_found", "해당 분석 작업이 없습니다.");
    }
}
