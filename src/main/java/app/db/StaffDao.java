package app.db;

import app.model.Models.Role;
import app.model.Models.Staff;
import app.security.Passwords;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class StaffDao {
    private static final String COLS = "id, staff_code, full_name, username, role, employment, department, card_revoked, active";

    private StaffDao() { }

    private static Staff map(ResultSet rs) throws SQLException {
        return new Staff(rs.getInt("id"), rs.getString("staff_code"), rs.getString("full_name"), rs.getString("username"),
                Role.valueOf(rs.getString("role")), rs.getString("employment"), rs.getString("department"),
                rs.getBoolean("card_revoked"), rs.getBoolean("active"));
    }

    /** Returns the staff member when username and password match, otherwise null. */
    public static Staff login(String username, String password) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + ", password_hash FROM staff WHERE username=? AND active=1")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && Passwords.verify(password, rs.getString("password_hash"))) return map(rs);
                }
            }
            return null;
        });
    }

    public static List<Staff> all() throws SQLException { return query("SELECT " + COLS + " FROM staff ORDER BY full_name"); }

    public static List<Staff> lecturers() throws SQLException {
        return query("SELECT " + COLS + " FROM staff WHERE role IN ('LECTURER','HOD') AND active=1 ORDER BY full_name");
    }

    private static List<Staff> query(String sql) throws SQLException {
        return Database.call(c -> {
            List<Staff> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) out.add(map(rs));
            }
            return out;
        });
    }

    public static Staff byCode(String code) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM staff WHERE staff_code=?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
            }
        });
    }

    public static Staff byId(int id) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM staff WHERE id=?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
            }
        });
    }

    /** Insert (id == 0) or update. password may be null/empty on update to keep the old one. */
    public static int save(int id, String code, String name, String username, String password, Role role,
                           String employment, String department) throws SQLException {
        return Database.call(c -> {
            if (id == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO staff(staff_code, full_name, username, password_hash, role, employment, department) VALUES (?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, code); ps.setString(2, name); ps.setString(3, username);
                    ps.setString(4, Passwords.hash(password == null || password.isBlank() ? "changeme" : password));
                    ps.setString(5, role.name()); ps.setString(6, employment); ps.setString(7, department);
                    ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getInt(1); }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE staff SET staff_code=?, full_name=?, username=?, role=?, employment=?, department=? WHERE id=?")) {
                ps.setString(1, code); ps.setString(2, name); ps.setString(3, username); ps.setString(4, role.name());
                ps.setString(5, employment); ps.setString(6, department); ps.setInt(7, id);
                ps.executeUpdate();
            }
            if (password != null && !password.isBlank()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE staff SET password_hash=? WHERE id=?")) {
                    ps.setString(1, Passwords.hash(password)); ps.setInt(2, id);
                    ps.executeUpdate();
                }
            }
            return id;
        });
    }

    public static void setSignature(int id, String sig) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE staff SET card_signature=? WHERE id=?")) {
                ps.setString(1, sig); ps.setInt(2, id); ps.executeUpdate();
            }
        });
    }
}
