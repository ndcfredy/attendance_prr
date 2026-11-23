package app.service;

import app.core.Config;
import app.db.AdminDao;
import app.model.Models.AccessPoint;
import app.model.Models.Decision;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Access-point verification. Only the BSSID of the access point is read, at scan time - no GPS, no tracking.
 * Windows: netsh wlan show interfaces. Set wifi.simulate.bssid in config.properties to test without campus Wi-Fi.
 */
public final class WifiService {
    private static final Pattern BSSID = Pattern.compile("^\\s*BSSID\\s*:\\s*([0-9a-fA-F:]{17})\\s*$", Pattern.MULTILINE);
    /** Register copy used when the database is unreachable. */
    private static final Map<String, AccessPoint> CACHE = new ConcurrentHashMap<>();

    private static volatile Integer lastKioskBuilding;

    private WifiService() { }

    /** BSSID of the access point the machine is connected to, lower case; empty when none. */
    public static String currentBssid() {
        String sim = Config.get("wifi.simulate.bssid").trim();
        if (!sim.isEmpty()) return sim.toLowerCase();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return "";
        try {
            Process p = new ProcessBuilder("netsh", "wlan", "show", "interfaces").redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(3, TimeUnit.SECONDS);
            Matcher m = BSSID.matcher(sb);
            return m.find() ? m.group(1).toLowerCase() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static AccessPoint lookup(String bssid) {
        if (bssid == null || bssid.isBlank()) return null;
        String key = bssid.toLowerCase();
        if (!app.core.AppContext.online.get()) return CACHE.get(key);
        try {
            AccessPoint ap = AdminDao.accessPoint(key);
            if (ap != null) CACHE.put(key, ap); else CACHE.remove(key);
            return ap;
        } catch (java.sql.SQLException e) {
            return CACHE.get(key);
        }
    }

    /** Wired kiosks have no Wi-Fi: they are verified by their registered IP address. Returns the building id or null. */
    public static Integer kioskBuilding() {
        if (!app.core.AppContext.online.get()) return lastKioskBuilding;
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            if (nis == null) return null;
            for (NetworkInterface ni : Collections.list(nis)) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a.getHostAddress().contains(":")) continue;
                    Integer b = AdminDao.kioskBuilding(a.getHostAddress());
                    if (b != null) { lastKioskBuilding = b; return b; }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    public static Decision decide(String bssid, int sessionBuildingId, boolean kiosk) {
        AccessPoint ap = lookup(bssid);
        if (ap != null && "ACTIVE".equals(ap.status())) {
            return ap.buildingId() == sessionBuildingId ? Decision.VERIFIED : Decision.TO_REVIEW;
        }
        if (ap == null && kiosk) {
            Integer b = kioskBuilding();
            if (b != null) return b == sessionBuildingId ? Decision.VERIFIED : Decision.TO_REVIEW;
        }
        return Decision.NOT_VERIFIED;
    }

    /** Text for the "Last scan" panel and the top bar. */
    public static String describe(String bssid) {
        AccessPoint ap = lookup(bssid);
        if (ap == null) return bssid == null || bssid.isBlank() ? "no campus Wi-Fi" : "unknown access point";
        return ap.building() + ("ACTIVE".equals(ap.status()) ? "" : " (retired AP)");
    }
}
