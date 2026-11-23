package app.security;

import app.core.Config;
import app.db.AdminDao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 signed cards. Card QR text:  STU|studentCode|signature   (student)  or  STF|staffCode|signature  (staff).
 * The private key stays on the registry workstation (~/.attendance/card_private.key); the public key is stored in
 * the database (app_setting) and cached locally so verification keeps working offline.
 */
public final class CardSigner {
    public record Parsed(String kind, String code, String signature) { }

    private static PublicKey publicKey;

    private CardSigner() { }

    private static Path privateFile() { return Config.vaultDir().resolve("card_private.key"); }

    private static Path publicCache() { return Config.vaultDir().resolve("card_public.key"); }

    public static boolean hasPrivateKey() { return Files.exists(privateFile()); }

    /** Creates a new key pair. All previously issued cards become invalid. */
    public static synchronized void generateKeys() throws GeneralSecurityException, IOException, java.sql.SQLException {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(privateFile(), Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        Files.writeString(publicCache(), pub);
        AdminDao.putSetting("card_public_key", pub);
        publicKey = kp.getPublic();
    }

    private static PublicKey decodePublic(String b64) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim())));
    }

    /** Public key from memory, then database, then the local cache (offline). Null when no key exists yet. */
    public static synchronized PublicKey publicKey() {
        if (publicKey != null) return publicKey;
        try {
            String v = null;
            try { v = AdminDao.setting("card_public_key"); } catch (java.sql.SQLException ignored) { }
            if (v != null && !v.isBlank()) {
                Files.writeString(publicCache(), v);
            } else if (Files.exists(publicCache())) {
                v = Files.readString(publicCache());
            }
            if (v != null && !v.isBlank()) publicKey = decodePublic(v);
        } catch (GeneralSecurityException | IOException ignored) { }
        return publicKey;
    }

    private static String sign(String message) throws GeneralSecurityException, IOException {
        PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(privateFile()).trim())));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(pk);
        s.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }

    /** Builds the QR text of a card. Needs the private key (registry workstation only). */
    public static String payload(String kind, String code) throws GeneralSecurityException, IOException {
        return kind + "|" + code + "|" + sign(kind + "|" + code);
    }

    public static String signatureOf(String kind, String code) throws GeneralSecurityException, IOException {
        return sign(kind + "|" + code);
    }

    public static Parsed parse(String payload) {
        if (payload == null) return null;
        String[] p = payload.trim().split("\\|");
        if (p.length != 3 || !(p[0].equals("STU") || p[0].equals("STF"))) return null;
        return new Parsed(p[0], p[1], p[2]);
    }

    public static boolean verify(Parsed p) {
        PublicKey k = publicKey();
        if (p == null || k == null) return false;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(k);
            s.update((p.kind() + "|" + p.code()).getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getUrlDecoder().decode(p.signature()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
