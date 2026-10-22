package com.example1.bddllm.event;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates without coercing types or changing the original log text.
 */
public record ErrorEvent(ObjectNode payload) {
    public static final String CHAT_ROUTE = "/subjects/{subject-id}/chats/rooms";

    public static ErrorEvent read(JsonNode input) {
        Json.fields(
            input,
            Set.of(
                "event_id",
                "service",
                "environment",
                "occurred_at",
                "deploy_sha",
                "request",
                "response",
                "error",
                "business_context"),
            Set.of(
                "event_id",
                "service",
                "environment",
                "occurred_at",
                "deploy_sha",
                "request",
                "response",
                "error"));
        for (String fieldName : List.of("event_id", "service", "environment", "occurred_at", "deploy_sha"))
            Json.text(input, fieldName);
        try {
            OffsetDateTime.parse(input.path("occurred_at").asText());
        } catch (Exception exception) {
            throw Problem.invalid();
        }
        ObjectNode eventPayload = input.deepCopy();
        JsonNode request = eventPayload.get("request");
        JsonNode errorDetails = eventPayload.get("error");
        JsonNode response = eventPayload.get("response");
        Json.fields(
            request, Set.of("method", "path", "route_template", "body"), Set.of("method", "path"));
        Json.text(request, "method");
        Json.text(request, "path");
        if (request.hasNonNull("route_template")) Json.text(request, "route_template");
        ((ObjectNode) request).putIfAbsent("route_template", NullNode.instance);
        ((ObjectNode) request).putIfAbsent("body", NullNode.instance);
        Json.fields(response, Set.of("status_code"), Set.of("status_code"));
        Json.integer(response, "status_code", 100, 599);
        Json.fields(errorDetails, Set.of("type", "message", "stack_trace"), Set.of("type", "message"));
        Json.text(errorDetails, "type");
        Json.text(errorDetails, "message");
        ((ObjectNode) errorDetails).putIfAbsent("stack_trace", Json.array());
        if (!errorDetails.path("stack_trace").isArray()) throw Problem.invalid();
        for (JsonNode line : errorDetails.get("stack_trace")) if (!line.isTextual()) throw Problem.invalid();
        if (eventPayload.hasNonNull("business_context")) {
            if (!eventPayload.path("service").asText().equals("noteu")
                || !Json.strip(request.path("method").asText()).toUpperCase(Locale.ROOT).equals("GET")
                || !route(eventPayload).equals(CHAT_ROUTE)) throw Problem.invalid();
            JsonNode businessContext = eventPayload.get("business_context");
            Json.fields(businessContext, Set.of("auth_state", "subject_state", "enrollment_state"), Set.of());
            Map<String, Set<String>> enums =
                Map.of(
                    "auth_state",
                    Set.of("unknown", "anonymous", "authenticated"),
                    "subject_state",
                    Set.of("unknown", "exists", "missing"),
                    "enrollment_state",
                    Set.of("unknown", "enrolled", "not_enrolled"));
            for (Map.Entry<String, Set<String>> pair : enums.entrySet()) {
                ((ObjectNode) businessContext).putIfAbsent(pair.getKey(), TextNode.valueOf("unknown"));
                if (!businessContext.get(pair.getKey()).isTextual()
                    || !pair.getValue().contains(businessContext.get(pair.getKey()).asText()))
                    throw Problem.invalid();
            }
        } else eventPayload.remove("business_context");
        Json.canonical(eventPayload); // Reject non-finite numbers in arbitrary request JSON as well.
        return new ErrorEvent(eventPayload);
    }

    private static String route(JsonNode eventPayload) {
        JsonNode request = eventPayload.path("request");
        return request.hasNonNull("route_template")
            ? request.get("route_template").asText()
            : request.path("path").asText();
    }

    public String route() {
        return route(payload);
    }

    public String method() {
        return Json.strip(payload.path("request").path("method").asText()).toUpperCase(Locale.ROOT);
    }

    public String service() {
        return payload.path("service").asText();
    }

    public String payloadHash() {
        return Json.sha(Json.canonical(payload));
    }

    public ObjectNode context() {
        JsonNode businessContext = payload.get("business_context");
        if (businessContext == null
            || businessContext.properties().stream().allMatch(contextEntry -> contextEntry.getValue().asText().equals("unknown")))
            return Json.obj();
        return businessContext.deepCopy();
    }

    public String conditionHash() {
        ObjectNode businessContext = context();
        return businessContext.isEmpty()
            ? "legacy"
            : Json.sha(Json.canonical(businessContext.deepCopy().put("version", "noteu-chat-rooms-v1")));
    }
}
