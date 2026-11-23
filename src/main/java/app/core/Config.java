package app.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Local settings (config.properties next to the app). Nothing secret is stored except the DB password. */
public final class Config {
    private static final Properties P = new Properties();
    private static final Path FILE = Path.of("config.properties");

    static {
        P.setProperty("db.url", "jdbc:mysql://localhost:3306/attendance_db?useUnicode=true&characterEncoding=utf8"
                + "&connectTimeout=3000&socketTimeout=8000&useSSL=false&allowPublicKeyRetrieval=true");
        P.setProperty("db.user", "root");
        P.setProperty("db.password", "");
        P.setProperty("theme", "dark");
        P.setProperty("late.threshold.minutes", "10");
        P.setProperty("absence.threshold.percent", "20");
        P.setProperty("wifi.simulate.bssid", "");
        P.setProperty("kiosk.room", "");
        if (Files.exists(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                P.load(in);
            } catch (IOException ignored) { }
        }
    }

    private Config() { }

    public static String get(String key) { return P.getProperty(key, ""); }

    public static int getInt(String key, int def) {
        try { return Integer.parseInt(get(key).trim()); } catch (NumberFormatException e) { return def; }
    }

    public static void set(String key, String value) { P.setProperty(key, value == null ? "" : value); }

    public static void save() {
        try (OutputStream out = Files.newOutputStream(FILE)) {
            P.store(out, "Attendance & Teaching Log - local settings");
        } catch (IOException ignored) { }
    }

    /** Folder for the encrypted offline queue and the card keys. */
    public static Path vaultDir() {
        Path p = Path.of(System.getProperty("user.home"), ".attendance");
        try { Files.createDirectories(p); } catch (IOException ignored) { }
        return p;
    }
}
