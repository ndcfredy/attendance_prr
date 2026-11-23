package app.security;

import app.core.Config;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** AES-256-GCM encryption for the offline file (scans waiting to be written to MySQL). */
public final class LocalVault {
    private static final SecureRandom RND = new SecureRandom();
    private static SecretKey key;

    private LocalVault() { }

    private static synchronized SecretKey key() throws IOException {
        if (key != null) return key;
        Path f = Config.vaultDir().resolve("vault.key");
        byte[] raw;
        if (Files.exists(f)) {
            raw = Base64.getDecoder().decode(Files.readString(f).trim());
        } else {
            raw = new byte[32];
            RND.nextBytes(raw);
            Files.writeString(f, Base64.getEncoder().encodeToString(raw));
        }
        key = new SecretKeySpec(raw, "AES");
        return key;
    }

    public static byte[] encrypt(byte[] plain) throws IOException, GeneralSecurityException {
        byte[] iv = new byte[12];
        RND.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(plain);
        byte[] out = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(enc, 0, out, iv.length, enc.length);
        return out;
    }

    public static byte[] decrypt(byte[] data) throws IOException, GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(data, 0, 12)));
        return c.doFinal(data, 12, data.length - 12);
    }
}
