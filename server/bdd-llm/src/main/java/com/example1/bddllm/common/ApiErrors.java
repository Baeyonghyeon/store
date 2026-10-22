package com.example1.bddllm.common;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    public static ResponseEntity<String> json(int status, Object value) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(Json.encode(value));
    }

    @ExceptionHandler(Problem.class)
    public ResponseEntity<String> problem(Problem problem) {
        return json(
                problem.status, Json.obj("detail", Json.obj("code", problem.code, "message", problem.getMessage())));
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<String> invalid(Exception exception) {
        return problem(Problem.invalid());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> storage(Exception exception) {
        return problem(new Problem(503, "storage_unavailable", "DB 작업을 완료할 수 없습니다."));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<String> notFound(Exception exception) {
        return problem(new Problem(404, "not_found", "해당 경로가 없습니다."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> method(Exception exception) {
        return problem(new Problem(405, "method_not_allowed", "지원하지 않는 HTTP 메서드입니다."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<String> media(Exception exception) {
        return problem(new Problem(415, "unsupported_media_type", "application/json으로 요청해 주세요."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> other(Exception exception) {
        return problem(new Problem(500, "internal_error", "저장된 자료 또는 서버 상태를 확인해 주세요."));
    }
}
