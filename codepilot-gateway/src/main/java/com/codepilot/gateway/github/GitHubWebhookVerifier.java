package com.codepilot.gateway.github;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class GitHubWebhookVerifier {

    private final String webhookSecret;

    public GitHubWebhookVerifier(String webhookSecret) {
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
    }

    public boolean verify(String payload, String signature) {
        if (webhookSecret.isBlank()) {
            return true;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] actual = hexToBytes(signature.replace("sha256=", ""));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to verify GitHub webhook signature", exception);
        }
    }

    private byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) == 1) {
            return new byte[0];
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < hex.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
        }
        return bytes;
    }
}
