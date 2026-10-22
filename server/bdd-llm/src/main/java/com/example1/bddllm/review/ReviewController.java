package com.example1.bddllm.review;

import com.example1.bddllm.common.ApiErrors;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@ConditionalOnProperty(name = "bdd.role", havingValue = "api", matchIfMissing = true)
public class ReviewController {
    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/review-jobs")
    public ResponseEntity<String> list(
        @RequestParam(defaultValue = "30") int limit,
        @RequestParam(name = "before_id", required = false) Long before) {
        return ApiErrors.json(200, reviews.list(limit, before));
    }

    @GetMapping("/jobs/{id}/review")
    public ResponseEntity<String> review(@PathVariable long id) {
        return ApiErrors.json(200, reviews.detail(id));
    }

    @PostMapping(
        value = "/jobs/{id}/candidates/{number}/reviews",
        consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submit(
        @PathVariable long id, @PathVariable int number, @RequestBody String body) {
        if (id < 1 || number < 1 || number > 5) throw Problem.invalid();
        return ApiErrors.json(201, reviews.submit(id, number, Json.parse(body)));
    }

    @GetMapping("/jobs/{id}/approved.feature")
    public ResponseEntity<String> feature(@PathVariable long id) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
            .cacheControl(CacheControl.noStore())
            .header(
                "Content-Disposition",
                "attachment; filename=\"job-" + id + "-approved.feature\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(reviews.approved(id));
    }

    @GetMapping({"/reviews", "/review-assets/{file}"})
    public ResponseEntity<String> page(@PathVariable(required = false) String file)
        throws Exception {
        String name = file == null ? "reviews.html" : file;
        if (!Set.of("reviews.html", "reviews.css", "reviews.js").contains(name))
            throw Problem.missing();
        String type =
            name.endsWith(".html")
                ? "text/html"
                : name.endsWith(".css") ? "text/css" : "application/javascript";
        try (InputStream in = new ClassPathResource("review-assets/" + name).getInputStream()) {
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type + ";charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .header(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'; img-src"
                        + " 'self' data:; connect-src 'self'; base-uri 'none';"
                        + " frame-ancestors 'none'; form-action 'self'")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
