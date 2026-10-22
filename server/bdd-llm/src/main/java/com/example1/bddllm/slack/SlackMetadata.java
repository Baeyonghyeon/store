package com.example1.bddllm.slack;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SlackMetadata {
    private final String key;

    public SlackMetadata(String key) {
        if (key.isBlank())
            throw new IllegalArgumentException("SLACK_APP_TOKEN은 버튼 메타데이터 서명 검증에 필요합니다.");
        this.key = key;
    }

    private byte[] hmac(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String sign(ObjectNode value) {
        String encoded =
            Base64.getUrlEncoder()
                .encodeToString(Json.encode(value).getBytes(StandardCharsets.UTF_8));
        return encoded + "." + HexFormat.of().formatHex(hmac(encoded));
    }

    public ObjectNode verify(String raw) {
        try {
            if (raw.length() > 3000) throw Problem.invalid();
            int dot = raw.lastIndexOf('.');
            String encoded = raw.substring(0, dot);
            if (!MessageDigest.isEqual(
                hmac(encoded), HexFormat.of().parseHex(raw.substring(dot + 1))))
                throw Problem.invalid();
            return (ObjectNode)
                Json.parse(
                    new String(
                        Base64.getUrlDecoder().decode(encoded),
                        StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new Problem(403, "invalid_metadata", "유효하지 않은 검토 정보입니다.");
        }
    }
}
