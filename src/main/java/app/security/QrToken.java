package app.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Mode B: dynamic QR code. The code changes every WINDOW_SECONDS and carries an HMAC-SHA256 token
 * computed with a per-session secret, so a photo of the screen is useless a few seconds later.
 * QR text: ATT|sessionId|kind|window|token   (kind = ENTRY or EXIT)
 */
public final class QrToken {
    public static final int WINDOW_SECONDS = 10;

    private QrToken() { }

    public static long window(long epochSeconds) { return epochSeconds / WINDOW_SECONDS; }

    public static long nowWindow() { return window(System.currentTimeMillis() / 1000); }

    public static int secondsLeft() { return WINDOW_SECONDS - (int) ((System.currentTimeMillis() / 1000) % WINDOW_SECONDS); }

    public static String token(String secret, int sessionId, String kind, long window) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] full = mac.doFinal((sessionId + ":" + kind + ":" + window).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, 12));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String payload(String secret, int sessionId, String kind) {
        long w = nowWindow();
        return "ATT|" + sessionId + "|" + kind + "|" + w + "|" + token(secret, sessionId, kind, w);
    }

    /** Accepts the current window and the previous one (the student scans at the end of a window). */
    public static boolean verify(String secret, int sessionId, String kind, long window, String token) {
        long now = nowWindow();
        if (window > now || window < now - 1) return false;
        return java.security.MessageDigest.isEqual(
                token(secret, sessionId, kind, window).getBytes(StandardCharsets.UTF_8),
                token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8));
    }
}
