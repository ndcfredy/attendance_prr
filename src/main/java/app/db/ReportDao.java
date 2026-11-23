package app.db;

import app.core.AppContext;
import app.model.Models.AbsenceRow;
import app.model.Models.Course;
import app.model.Models.HoursRow;
import app.model.Models.JustifRow;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reports, alerts and absence justifications. */
public final class ReportDao {
    public record AbsentSession(int sessionId, String label) {
        @Override public String toString() { return label; }
    }

    private ReportDao() { }

    public static List<HoursRow> hours(LocalDate from, LocalDate to, Integer lecturerId) throws SQLException {
        return Database.call(c -> {
            String sql = "SELECT l.full_name AS lecturer, CONCAT(cu.code,' – ',cu.title) AS course, COUNT(*) AS sessions, "
                    + "SUM(TIME_TO_SEC(TIMEDIFF(s.planned_end, s.planned_start))) / 3600.0 AS planned, "
                    + "SUM(CASE WHEN s.status IN ('DELIVERED','REPLACED') THEN GREATEST(TIMESTAMPDIFF(SECOND, s.actual_start, s.actual_end) - s.paused_seconds, 0) ELSE 0 END) / 3600.0 AS delivered, "
                    + "SUM(CASE WHEN s.status='MISSED' THEN 1 ELSE 0 END) AS missed, "
                    + "SUM(CASE WHEN s.status='REPLACED' THEN 1 ELSE 0 END) AS replaced "
                    + "FROM course_session s JOIN course_unit cu ON cu.id=s.course_id JOIN staff l ON l.id=s.lecturer_id "
                    + "WHERE s.session_date BETWEEN ? AND ? AND s.status IN ('DELIVERED','REPLACED','MISSED','IN_PROGRESS','PAUSED') "
                    + (lecturerId == null ? "" : "AND s.lecturer_id=? ")
                    + "GROUP BY l.id, l.full_name, cu.id, cu.code, cu.title ORDER BY l.full_name, cu.code";
            List<HoursRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, from); ps.setObject(2, to);
                if (lecturerId != null) ps.setInt(3, lecturerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new HoursRow(rs.getString("lecturer"), rs.getString("course"), rs.getDouble("planned"),
                            rs.getDouble("delivered"), rs.getInt("sessions"), rs.getInt("missed"), rs.getInt("replaced")));
                }
            }
            return out;
        });
    }

    /** Students whose unjustified absence rate in a course unit reaches the threshold (percent), once at least 2 sessions were held. */
    public static List<AbsenceRow> absences(double thresholdPercent, Integer lecturerId) throws SQLException {
        return Database.call(c -> {
            String sql = "SELECT st.id, st.full_name, st.student_code, CONCAT(cu.code,' – ',cu.title) AS course, COUNT(*) AS total, "
                    + "SUM(CASE WHEN r.status='ABSENT' THEN 1 ELSE 0 END) AS absent, "
                    + "SUM(CASE WHEN r.status='ABSENT' AND r.justified=0 THEN 1 ELSE 0 END) AS unjust "
                    + "FROM attendance_record r JOIN course_session s ON s.id=r.session_id JOIN student st ON st.id=r.student_id "
                    + "JOIN course_unit cu ON cu.id=s.course_id WHERE s.status IN ('DELIVERED','REPLACED') "
                    + (lecturerId == null ? "" : "AND s.lecturer_id=? ")
                    + "GROUP BY st.id, st.full_name, st.student_code, cu.id, cu.code, cu.title "
                    + "HAVING COUNT(*) >= 2 AND SUM(CASE WHEN r.status='ABSENT' AND r.justified=0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*) >= ? "
                    + "ORDER BY SUM(CASE WHEN r.status='ABSENT' AND r.justified=0 THEN 1 ELSE 0 END) * 1.0 / COUNT(*) DESC, st.full_name";
            List<AbsenceRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                if (lecturerId != null) ps.setInt(i++, lecturerId);
                ps.setDouble(i, thresholdPercent);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int total = rs.getInt("total"), un = rs.getInt("unjust");
                        out.add(new AbsenceRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString("course"), total,
                                rs.getInt("absent"), un, total == 0 ? 0 : 100.0 * un / total));
                    }
                }
            }
            return out;
        });
    }

    /** Alerts for the registry / departments: missed sessions, late starts, Wi-Fi reviews, disputes, absences, delays. */
    public static List<String> alerts(Integer lecturerId) throws SQLException {
        String lect = lecturerId == null ? "" : " AND s.lecturer_id=" + lecturerId.intValue();
        int thr = AppContext.lateThresholdMinutes();
        List<String> out = new ArrayList<>();
        Database.exec(c -> {
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT s.session_date, cu.code, l.full_name FROM course_session s JOIN course_unit cu ON cu.id=s.course_id "
                        + "JOIN staff l ON l.id=s.lecturer_id WHERE s.status='MISSED' AND s.session_date >= CURDATE() - INTERVAL 30 DAY" + lect
                        + " ORDER BY s.session_date DESC LIMIT 30")) {
                    while (rs.next()) out.add("MISSED SESSION – " + rs.getString(2) + " on " + rs.getString(1) + " (" + rs.getString(3) + ")");
                }
                try (ResultSet rs = st.executeQuery("SELECT s.session_date, cu.code, l.full_name, "
                        + "TIMESTAMPDIFF(MINUTE, TIMESTAMP(s.session_date, s.planned_start), s.actual_start) AS late, "
                        + "TIMESTAMPDIFF(MINUTE, s.actual_end, TIMESTAMP(s.session_date, s.planned_end)) AS early "
                        + "FROM course_session s JOIN course_unit cu ON cu.id=s.course_id JOIN staff l ON l.id=s.lecturer_id "
                        + "WHERE s.status IN ('DELIVERED','REPLACED') AND s.session_date >= CURDATE() - INTERVAL 30 DAY" + lect
                        + " HAVING late > " + thr + " OR early > " + thr + " ORDER BY s.session_date DESC LIMIT 30")) {
                    while (rs.next()) {
                        int late = rs.getInt("late"), early = rs.getInt("early");
                        if (late > thr) out.add("LATE START – " + rs.getString(2) + " on " + rs.getString(1) + ": " + late + " min late (" + rs.getString(3) + ")");
                        if (early > thr) out.add("EARLY FINISH – " + rs.getString(2) + " on " + rs.getString(1) + ": " + early + " min early (" + rs.getString(3) + ")");
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT s.session_date, cu.code, SUM(CASE WHEN r.wifi_decision='TO_REVIEW' THEN 1 ELSE 0 END) AS rev, "
                        + "SUM(CASE WHEN r.wifi_decision='NOT_VERIFIED' THEN 1 ELSE 0 END) AS nv FROM attendance_record r JOIN course_session s ON s.id=r.session_id "
                        + "JOIN course_unit cu ON cu.id=s.course_id WHERE r.wifi_decision IN ('TO_REVIEW','NOT_VERIFIED') "
                        + "AND s.session_date >= CURDATE() - INTERVAL 30 DAY" + lect + " GROUP BY s.id, s.session_date, cu.code ORDER BY s.session_date DESC LIMIT 20")) {
                    while (rs.next()) out.add("WI-FI REVIEW – " + rs.getString(2) + " on " + rs.getString(1) + ": " + rs.getInt("rev")
                            + " to review (head of department), " + rs.getInt("nv") + " not verified (registry)");
                }
                try (ResultSet rs = st.executeQuery("SELECT s.session_date, cu.code, l.dispute_note FROM teaching_log l JOIN course_session s ON s.id=l.session_id "
                        + "JOIN course_unit cu ON cu.id=s.course_id WHERE l.disputed=1" + lect + " ORDER BY s.session_date DESC LIMIT 20")) {
                    while (rs.next()) out.add("DISPUTED LOG – " + rs.getString(2) + " on " + rs.getString(1) + ": " + rs.getString(3));
                }
            }
        });
        double abs = app.core.Config.getInt("absence.threshold.percent", 20);
        for (AbsenceRow a : absences(abs, lecturerId)) {
            out.add(String.format("ABSENCES – %s (%s) in %s: %.0f%% unjustified", a.student(), a.code(), a.course(), a.rate()));
        }
        for (Course cu : CourseDao.courses(lecturerId)) {
            CourseDao.Coverage cov = CourseDao.coverage(cu.id());
            if (cov.behind()) {
                out.add(String.format("BEHIND SCHEDULE – %s: %.0f%% of topics covered for %.0f%% of planned hours", cu.code(),
                        cov.topicPercent(), cov.hoursPercent()));
            }
        }
        return out;
    }

    // ---- justifications --------------------------------------------------------------------------------------

    public static List<JustifRow> justifications(String status) throws SQLException {
        return Database.call(c -> {
            String sql = "SELECT j.id, j.student_id, j.session_id, st.full_name, CONCAT(cu.code,' – ',cu.title) AS course, s.session_date, "
                    + "j.reason, j.document_path, j.status FROM justification j JOIN student st ON st.id=j.student_id "
                    + "JOIN course_session s ON s.id=j.session_id JOIN course_unit cu ON cu.id=s.course_id "
                    + (status == null ? "" : "WHERE j.status=? ") + "ORDER BY j.submitted_at DESC";
            List<JustifRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (status != null) ps.setString(1, status);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new JustifRow(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                            Database.d(rs, "session_date"), rs.getString(7), rs.getString(8), rs.getString(9)));
                }
            }
            return out;
        });
    }

    public static List<AbsentSession> absentSessions(int studentId) throws SQLException {
        return Database.call(c -> {
            List<AbsentSession> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.id, s.session_date, cu.code, s.planned_start FROM attendance_record r JOIN course_session s ON s.id=r.session_id "
                            + "JOIN course_unit cu ON cu.id=s.course_id WHERE r.student_id=? AND r.status='ABSENT' AND r.justified=0 "
                            + "AND s.id NOT IN (SELECT session_id FROM justification WHERE student_id=? AND status='PENDING') ORDER BY s.session_date DESC")) {
                ps.setInt(1, studentId); ps.setInt(2, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new AbsentSession(rs.getInt(1), rs.getString(2) + " " + rs.getString(4).substring(0, 5) + " – " + rs.getString(3)));
                }
            }
            return out;
        });
    }

    public static void addJustification(int studentId, int sessionId, String reason, String docPath) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO justification(student_id, session_id, reason, document_path, status) VALUES (?,?,?,?, 'PENDING')")) {
                ps.setInt(1, studentId); ps.setInt(2, sessionId); ps.setString(3, reason); ps.setString(4, docPath);
                ps.executeUpdate();
            }
        });
    }

    public static void decide(int id, boolean accept, int staffId) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE justification SET status=?, decided_by=?, decided_at=NOW() WHERE id=?")) {
                ps.setString(1, accept ? "ACCEPTED" : "REJECTED"); ps.setInt(2, staffId); ps.setInt(3, id); ps.executeUpdate();
            }
            if (accept) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE attendance_record r JOIN justification j ON j.student_id=r.student_id AND j.session_id=r.session_id "
                                + "SET r.justified=1 WHERE j.id=?")) {
                    ps.setInt(1, id); ps.executeUpdate();
                }
            }
        });
    }
}
