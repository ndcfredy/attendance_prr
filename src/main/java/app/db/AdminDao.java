package app.db;

import app.model.Models.AccessPoint;
import app.model.Models.Building;
import app.model.Models.Group;
import app.model.Models.Room;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Reference data (buildings, rooms, groups), Wi-Fi access-point register, settings, kiosks, audit. */
public final class AdminDao {
    private AdminDao() { }

    public static List<Building> buildings() throws SQLException {
        return Database.call(c -> {
            List<Building> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name FROM building ORDER BY name")) {
                while (rs.next()) out.add(new Building(rs.getInt(1), rs.getString(2)));
            }
            return out;
        });
    }

    public static List<Room> rooms() throws SQLException {
        return Database.call(c -> {
            List<Room> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT r.id, r.name, r.capacity, r.type, r.building_id, b.name AS bname FROM room r JOIN building b ON b.id=r.building_id ORDER BY r.name")) {
                while (rs.next()) out.add(new Room(rs.getInt("id"), rs.getString("name"), rs.getInt("capacity"), rs.getString("type"),
                        rs.getInt("building_id"), rs.getString("bname")));
            }
            return out;
        });
    }

    public static List<Group> groups() throws SQLException {
        return Database.call(c -> {
            List<Group> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name, size FROM study_group ORDER BY name")) {
                while (rs.next()) out.add(new Group(rs.getInt(1), rs.getString(2), rs.getInt(3)));
            }
            return out;
        });
    }

    // ---- Wi-Fi access point register -------------------------------------------------------------------------

    private static AccessPoint ap(ResultSet rs) throws SQLException {
        return new AccessPoint(rs.getInt("id"), rs.getString("bssid"), rs.getInt("building_id"), rs.getString("bname"),
                rs.getString("zone"), rs.getString("status"));
    }

    private static final String AP_SELECT = "SELECT a.id, a.bssid, a.building_id, b.name AS bname, a.zone, a.status "
            + "FROM wifi_access_point a JOIN building b ON b.id=a.building_id ";

    public static List<AccessPoint> accessPoints() throws SQLException {
        return Database.call(c -> {
            List<AccessPoint> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(AP_SELECT + "ORDER BY b.name, a.bssid")) {
                while (rs.next()) out.add(ap(rs));
            }
            return out;
        });
    }

    public static AccessPoint accessPoint(String bssid) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(AP_SELECT + "WHERE a.bssid=?")) {
                ps.setString(1, bssid.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? ap(rs) : null; }
            }
        });
    }

    public static void addAccessPoint(String bssid, int buildingId, String zone) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO wifi_access_point(bssid, building_id, zone, status) VALUES (?,?,?, 'ACTIVE')")) {
                ps.setString(1, bssid.toLowerCase()); ps.setInt(2, buildingId); ps.setString(3, zone);
                ps.executeUpdate();
            }
        });
    }

    public static void setAccessPointStatus(int id, String status) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE wifi_access_point SET status=? WHERE id=?")) {
                ps.setString(1, status); ps.setInt(2, id); ps.executeUpdate();
            }
        });
    }

    /** Building of a wired kiosk, found from its registered network address; null when unknown. */
    public static Integer kioskBuilding(String ip) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT r.building_id FROM kiosk_device k JOIN room r ON r.id=k.room_id WHERE k.ip_address=?")) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
            }
        });
    }

    // ---- settings / audit ------------------------------------------------------------------------------------

    public static String setting(String key) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM app_setting WHERE k=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
            }
        });
    }

    public static void putSetting(String key, String value) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO app_setting(k, v) VALUES (?,?) ON DUPLICATE KEY UPDATE v=VALUES(v)")) {
                ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
            }
        });
    }

    public static void audit(String actor, String action, String details) {
        try {
            Database.exec(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log(actor, action, details) VALUES (?,?,?)")) {
                    ps.setString(1, actor); ps.setString(2, action);
                    ps.setString(3, details == null ? "" : details.substring(0, Math.min(details.length(), 480)));
                    ps.executeUpdate();
                }
            });
        } catch (SQLException ignored) { /* auditing must never break the workflow */ }
    }
}
