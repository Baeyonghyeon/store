package com.example1.bddllm.event;

import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Grouping {
    private static final Pattern[] PATTERNS = {
        Pattern.compile("(?U)^(.+\\.py:)[0-9]+( in \\S+)$"),
        Pattern.compile("(?U)^(File \"[^\"\\r\\n]+\", line )[0-9]+(, in \\S+)$"),
        Pattern.compile("(?U)^(at [^\\r\\n()]+\\([^\\r\\n()]+\\.java:)[0-9]+(\\))$")
    };

    public static ObjectNode signature(ErrorEvent event) {
        ObjectNode eventPayload = event.payload();
        ArrayNode frames = Json.array();
        for (JsonNode frame : eventPayload.path("error").path("stack_trace")) {
            String raw = frame.asText();
            if (Json.blank(raw)) continue;
            String normalized = raw;
            for (Pattern pattern : PATTERNS) {
                Matcher stackFrameMatcher = pattern.matcher(Json.strip(raw));
                if (stackFrameMatcher.matches()) {
                    normalized = stackFrameMatcher.group(1) + "<line>" + stackFrameMatcher.group(2);
                    break;
                }
            }
            frames.add(normalized);
        }
        return Json.obj(
            "version",
            "v1",
            "service",
            event.service(),
            "environment",
            eventPayload.path("environment"),
            "method",
            event.method(),
            "route",
            event.route(),
            "error_type",
            eventPayload.path("error").path("type"),
            "stack_frames",
            frames,
            "message_fallback",
            frames.isEmpty() ? eventPayload.path("error").path("message") : null);
    }

    public static String fingerprint(ErrorEvent event) {
        return Json.sha(Json.canonical(signature(event)));
    }
}
