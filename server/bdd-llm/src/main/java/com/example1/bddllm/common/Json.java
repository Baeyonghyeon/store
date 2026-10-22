package com.example1.bddllm.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic JSON encoding for persisted payloads and analysis hashes.
 */
public final class Json {
    public static final ObjectMapper MAPPER =
        new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private Json() {
    }

    public static JsonNode parse(String jsonText) {
        try {
            return MAPPER.readTree(jsonText);
        } catch (Exception exception) {
            throw Problem.invalid();
        }
    }

    public static ObjectNode obj(Object... pairs) {
        ObjectNode jsonObject = MAPPER.createObjectNode();
        for (int pairIndex = 0; pairIndex < pairs.length; pairIndex += 2) jsonObject.set((String) pairs[pairIndex], value(pairs[pairIndex + 1]));
        return jsonObject;
    }

    public static JsonNode value(Object inputValue) {
        return inputValue instanceof JsonNode jsonValue ? jsonValue : MAPPER.valueToTree(inputValue);
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static String encode(Object inputValue) {
        try {
            return MAPPER.writeValueAsString(inputValue);
        } catch (Exception exception) {
            throw new IllegalStateException("json_encoding_failed");
        }
    }

    public static String canonical(JsonNode jsonValue) {
        return format(jsonValue, false, -1, 0);
    }

    public static String spaced(JsonNode jsonValue) {
        return format(jsonValue, true, -1, 0);
    }

    public static String pretty(JsonNode jsonValue) {
        return format(jsonValue, true, 2, 0);
    }

    private static int compareKeys(String leftKey, String rightKey) {
        return Arrays.compare(leftKey.codePoints().toArray(), rightKey.codePoints().toArray());
    }

    public static String insertionSpaced(JsonNode jsonValue) {
        return format(jsonValue, true, -1, 0, false);
    }

    private static String format(JsonNode jsonValue, boolean spaces, int indent, int depth) {
        return format(jsonValue, spaces, indent, depth, true);
    }

    private static String format(
        JsonNode jsonValue, boolean spaces, int indent, int depth, boolean sortKeys) {
        if (jsonValue.isObject() || jsonValue.isArray()) {
            List<String> items = new ArrayList<>();
            if (jsonValue.isObject()) {
                List<String> names = new ArrayList<>();
                jsonValue.fieldNames().forEachRemaining(names::add);
                if (sortKeys) names.sort(Json::compareKeys);
                for (String fieldName : names)
                    items.add(
                        quote(fieldName)
                            + (spaces ? ": " : ":")
                            + format(jsonValue.get(fieldName), spaces, indent, depth + 1, sortKeys));
            } else for (JsonNode element : jsonValue) items.add(format(element, spaces, indent, depth + 1, sortKeys));
            String start = jsonValue.isObject() ? "{" : "[", end = jsonValue.isObject() ? "}" : "]";
            if (items.isEmpty()) return start + end;
            if (indent >= 0)
                return start
                    + "\n"
                    + " ".repeat((depth + 1) * indent)
                    + String.join(",\n" + " ".repeat((depth + 1) * indent), items)
                    + "\n"
                    + " ".repeat(depth * indent)
                    + end;
            return start + String.join(spaces ? ", " : ",", items) + end;
        }
        if (jsonValue.isFloatingPointNumber()) {
            double numericValue = jsonValue.doubleValue();
            if (!Double.isFinite(numericValue)) throw Problem.invalid();
            if (numericValue == 0) return Double.doubleToRawLongBits(numericValue) < 0 ? "-0.0" : "0.0";
            BigDecimal decimalValue = shortestRoundTripDecimal(numericValue);
            int exp = decimalValue.precision() - decimalValue.scale() - 1;
            if (exp >= -4 && exp < 16) {
                String decimalText = decimalValue.toPlainString();
                return decimalText.contains(".") ? decimalText : decimalText + ".0";
            }
            String digits = decimalValue.unscaledValue().abs().toString();
            return (numericValue < 0 ? "-" : "")
                + digits.charAt(0)
                + (digits.length() > 1 ? "." + digits.substring(1) : "")
                + "e"
                + (exp < 0 ? "-" : "+")
                + String.format(Locale.ROOT, "%02d", Math.abs(exp));
        }
        return jsonValue.isTextual() ? quote(jsonValue.asText()) : encode(jsonValue);
    }

    private static BigDecimal shortestRoundTripDecimal(double value) {
        BigDecimal exact = new BigDecimal(value);
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal best = null, distance = null;
            for (RoundingMode mode :
                List.of(RoundingMode.HALF_EVEN, RoundingMode.DOWN, RoundingMode.UP)) {
                BigDecimal candidate =
                    exact.round(new MathContext(precision, mode)).stripTrailingZeros();
                if (Double.doubleToRawLongBits(Double.parseDouble(candidate.toString()))
                    != Double.doubleToRawLongBits(value)) continue;
                BigDecimal delta = candidate.subtract(exact).abs();
                if (best == null
                    || delta.compareTo(distance) < 0
                    || delta.compareTo(distance) == 0
                    && !candidate.unscaledValue().testBit(0)) {
                    best = candidate;
                    distance = delta;
                }
            }
            if (best != null) return best;
        }
        throw new IllegalStateException("floating_point_encoding_failed");
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int characterIndex = 0; characterIndex < text.length(); characterIndex++) {
            char character = text.charAt(characterIndex);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else if (Character.isHighSurrogate(character)) {
                        if (characterIndex + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(characterIndex + 1)))
                            throw Problem.invalid();
                        out.append(character).append(text.charAt(++characterIndex));
                    } else if (Character.isLowSurrogate(character)) throw Problem.invalid();
                    else out.append(character);
                }
            }
        }
        return out.append('"').toString();
    }

    public static boolean whitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }

    public static boolean blank(String text) {
        return text.codePoints().allMatch(Json::whitespace);
    }

    public static String strip(String text) {
        int start = 0, end = text.length();
        while (start < end && whitespace(text.codePointAt(start)))
            start += Character.charCount(text.codePointAt(start));
        while (end > start && whitespace(text.codePointBefore(end)))
            end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }

    public static String sha(String text) {
        try {
            return HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    public static String oneLine(String text) {
        return text.replaceAll("(?U)\\s+", " ").strip();
    }

    public static void fields(JsonNode jsonObject, Set<String> allowed, Set<String> required) {
        if (jsonObject == null || !jsonObject.isObject()) throw Problem.invalid();
        jsonObject.fieldNames()
            .forEachRemaining(
                fieldName -> {
                    if (!allowed.contains(fieldName)) throw Problem.invalid();
                });
        for (String fieldName : required) if (!jsonObject.has(fieldName)) throw Problem.invalid();
    }

    public static String text(JsonNode jsonObject, String fieldName) {
        JsonNode fieldValue = jsonObject.get(fieldName);
        if (fieldValue == null || !fieldValue.isTextual() || blank(fieldValue.textValue())) throw Problem.invalid();
        return fieldValue.textValue();
    }

    public static long integer(JsonNode jsonObject, String fieldName, long min, long max) {
        JsonNode fieldValue = jsonObject.get(fieldName);
        if (fieldValue == null
            || !fieldValue.isIntegralNumber()
            || !fieldValue.canConvertToLong()
            || fieldValue.longValue() < min
            || fieldValue.longValue() > max) throw Problem.invalid();
        return fieldValue.longValue();
    }
}
