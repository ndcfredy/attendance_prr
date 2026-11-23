package app.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** PBKDF2 password hashing. Stored form: pbkdf2$iterations$salt$hash (Base64). */
public final class Passwords {
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RND = new SecureRandom();

    private Passwords() { }

    private static byte[] derive(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    public static String hash(String password) {
        try {
            byte[] salt = new byte[16];
            RND.nextBytes(salt);
            Base64.Encoder b = Base64.getEncoder();
            return "pbkdf2$" + ITERATIONS + "$" + b.encodeToString(salt) + "$"
                    + b.encodeToString(derive(password, salt, ITERATIONS));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String password, String stored) {
        try {
            if (stored == null) return false;
            String[] p = stored.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = derive(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
            return MessageDigest.isEqual(expected, actual);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
