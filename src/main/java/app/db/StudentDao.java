package app.db;

import app.model.Models.HistoryRow;
import app.model.Models.Student;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class StudentDao {
    private static final String SELECT = "SELECT s.id, s.student_code, s.full_name, s.group_id, g.name AS gname, s.is_class_rep, "
            + "s.registered_phone, s.card_revoked, s.active FROM student s JOIN study_group g ON g.id=s.group_id ";

    private StudentDao() { }

    private static Student map(ResultSet rs) throws SQLException {
        return new Student(rs.getInt("id"), rs.getString("student_code"), rs.getString("full_name"), rs.getInt("group_id"),
                rs.getString("gname"), rs.getBoolean("is_class_rep"), rs.getString("registered_phone"),
                rs.getBoolean("card_revoked"), rs.getBoolean("active"));
    }

    public static List<Student> search(String q, Integer groupId) throws SQLException {
        return Database.call(c -> {
            StringBuilder sql = new StringBuilder(SELECT + "WHERE s.active=1 ");
            List<Object> args = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                sql.append("AND (s.full_name LIKE ? OR s.student_code LIKE ?) ");
                args.add("%" + q.trim() + "%"); args.add("%" + q.trim() + "%");
            }
            if (groupId != null) { sql.append("AND s.group_id=? "); args.add(groupId); }
            sql.append("ORDER BY s.full_name");
            List<Student> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
            }
            return out;
        });
    }

    public static List<Student> byGroup(int groupId) throws SQLException { return search(null, groupId); }

    public static Student byCode(String code) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT + "WHERE s.student_code=?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
            }
        });
    }

    public static byte[] photo(int id) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT photo FROM student WHERE id=?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getBytes(1) : null; }
            }
        });
    }

    public static void setPhoto(int id, byte[] data) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE student SET photo=? WHERE id=?")) {
                ps.setBytes(1, data); ps.setInt(2, id); ps.executeUpdate();
            }
        });
    }

    /** Insert (id == 0) or update. Returns the id. */
    public static int save(int id, String code, String name, int groupId, boolean classRep) throws SQLException {
        return Database.call(c -> {
            if (id == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO student(student_code, full_name, group_id, is_class_rep) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, code); ps.setString(2, name); ps.setInt(3, groupId); ps.setBoolean(4, classRep);
                    ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getInt(1); }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE student SET student_code=?, full_name=?, group_id=?, is_class_rep=? WHERE id=?")) {
                ps.setString(1, code); ps.setString(2, name); ps.setInt(3, groupId); ps.setBoolean(4, classRep); ps.setInt(5, id);
                ps.executeUpdate();
            }
            return id;
        });
    }

    public static void deactivate(int id) throws SQLException { flag(id, "active", false); }

    public static void setRevoked(int id, boolean revoked) throws SQLException { flag(id, "card_revoked", revoked); }

    private static void flag(int id, String col, boolean v) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE student SET " + col + "=? WHERE id=?")) {
                ps.setBoolean(1, v); ps.setInt(2, id); ps.executeUpdate();
            }
        });
    }

    public static void setPhone(int id, String phone) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE student SET registered_phone=? WHERE id=?")) {
                ps.setString(1, phone); ps.setInt(2, id); ps.executeUpdate();
            }
        });
    }

    public static void setSignature(int id, String sig) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE student SET card_signature=? WHERE id=?")) {
                ps.setString(1, sig); ps.setInt(2, id); ps.executeUpdate();
            }
        });
    }

    /** Attendance history and absence rate per course unit. */
    public static List<HistoryRow> history(int studentId) throws SQLException {
        return Database.call(c -> {
            String sql = "SELECT cu.code, cu.title, COUNT(*) AS total, "
                    + "SUM(CASE WHEN r.status IN ('PRESENT','LATE') THEN 1 ELSE 0 END) AS attended, "
                    + "SUM(CASE WHEN r.status='ABSENT' THEN 1 ELSE 0 END) AS absent, "
                    + "SUM(CASE WHEN r.status='ABSENT' AND r.justified=0 THEN 1 ELSE 0 END) AS unjust "
                    + "FROM attendance_record r JOIN course_session s ON s.id=r.session_id JOIN course_unit cu ON cu.id=s.course_id "
                    + "WHERE r.student_id=? AND s.status IN ('DELIVERED','REPLACED') GROUP BY cu.id, cu.code, cu.title ORDER BY cu.code";
            List<HistoryRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int total = rs.getInt("total"), un = rs.getInt("unjust");
                        out.add(new HistoryRow(rs.getString("code") + " – " + rs.getString("title"), total,
                                rs.getInt("attended"), rs.getInt("absent"), un, total == 0 ? 0 : 100.0 * un / total));
                    }
                }
            }
            return out;
        });
    }
}
