package app.db;

import app.model.Models.LogEntry;
import app.model.Models.LogRow;
import app.model.Models.ModeBScan;
import app.model.Models.SessionInfo;
import app.model.Models.SpotCandidate;
import app.model.RosterRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Sessions, attendance records, teaching log, spot checks, Mode B scans. */
public final class SessionDao {
    private static final String SELECT = "SELECT s.id, s.course_id, c.code, c.title, s.group_id, g.name AS gname, s.room_id, "
            + "r.name AS rname, r.building_id, s.lecturer_id, l.full_name AS lname, s.session_date, s.planned_start, s.planned_end, "
            + "s.session_type, s.status, s.actual_start, s.actual_end, s.is_replacement, s.hmac_secret, s.attendance_mode, s.paused_seconds "
            + "FROM course_session s JOIN course_unit c ON c.id=s.course_id JOIN study_group g ON g.id=s.group_id "
            + "JOIN room r ON r.id=s.room_id JOIN staff l ON l.id=s.lecturer_id ";

    private SessionDao() { }

    private static SessionInfo map(ResultSet rs) throws SQLException {
        return new SessionInfo(rs.getInt("id"), rs.getInt("course_id"), rs.getString("code"), rs.getString("title"),
                rs.getInt("group_id"), rs.getString("gname"), rs.getInt("room_id"), rs.getString("rname"), rs.getInt("building_id"),
                rs.getInt("lecturer_id"), rs.getString("lname"), Database.d(rs, "session_date"), Database.t(rs, "planned_start"),
                Database.t(rs, "planned_end"), rs.getString("session_type"), rs.getString("status"),
                Database.dt(rs, "actual_start"), Database.dt(rs, "actual_end"), rs.getBoolean("is_replacement"),
                rs.getString("hmac_secret"), rs.getString("attendance_mode"), rs.getInt("paused_seconds"));
    }

    private static List<SessionInfo> list(String where, Object... args) throws SQLException {
        return Database.call(c -> {
            List<SessionInfo> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT + where)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
            }
            return out;
        });
    }

    public static SessionInfo byId(int id) throws SQLException {
        List<SessionInfo> l = list("WHERE s.id=?", id);
        return l.isEmpty() ? null : l.get(0);
    }

    public static List<SessionInfo> between(LocalDate from, LocalDate to, Integer lecturerId) throws SQLException {
        if (lecturerId == null) return list("WHERE s.session_date BETWEEN ? AND ? ORDER BY s.session_date, s.planned_start", from, to);
        return list("WHERE s.session_date BETWEEN ? AND ? AND s.lecturer_id=? ORDER BY s.session_date, s.planned_start", from, to, lecturerId);
    }

    public static SessionInfo activeFor(int lecturerId) throws SQLException {
        List<SessionInfo> l = list("WHERE s.lecturer_id=? AND s.status IN ('IN_PROGRESS','PAUSED') ORDER BY s.actual_start DESC LIMIT 1", lecturerId);
        return l.isEmpty() ? null : l.get(0);
    }

    public static SessionInfo inProgressInRoom(int roomId) throws SQLException {
        List<SessionInfo> l = list("WHERE s.room_id=? AND s.status='IN_PROGRESS' ORDER BY s.actual_start DESC LIMIT 1", roomId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Sessions whose slot is over and that nobody opened are missed. */
    public static void markMissed() throws SQLException {
        Database.exec(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE course_session SET status='MISSED' WHERE status='PLANNED' "
                        + "AND TIMESTAMP(session_date, planned_end) < NOW()");
            }
        });
    }

    public static int create(int courseId, int groupId, int roomId, int lecturerId, LocalDate date, LocalTime start,
                             LocalTime end, String type) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO course_session(course_id, group_id, room_id, lecturer_id, session_date, planned_start, planned_end, session_type) "
                            + "VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, courseId); ps.setInt(2, groupId); ps.setInt(3, roomId); ps.setInt(4, lecturerId);
                ps.setObject(5, date); ps.setObject(6, start); ps.setObject(7, end); ps.setString(8, type);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getInt(1); }
            }
        });
    }

    public static void checkIn(int id, String secret, String bssid, String decision, LocalDateTime start) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE course_session SET status='IN_PROGRESS', actual_start=?, hmac_secret=?, checkin_bssid=?, checkin_decision=? WHERE id=?")) {
                ps.setObject(1, start); ps.setString(2, secret); ps.setString(3, bssid); ps.setString(4, decision); ps.setInt(5, id);
                ps.executeUpdate();
            }
        });
    }

    public static void setMode(int id, String mode) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE course_session SET attendance_mode=? WHERE id=?")) {
                ps.setString(1, mode); ps.setInt(2, id); ps.executeUpdate();
            }
        });
    }

    public static void setPaused(int id, boolean paused, LocalDateTime now) throws SQLException {
        Database.exec(c -> {
            String sql = paused
                    ? "UPDATE course_session SET status='PAUSED', pause_started=? WHERE id=?"
                    : "UPDATE course_session SET status='IN_PROGRESS', paused_seconds=paused_seconds+TIMESTAMPDIFF(SECOND, pause_started, ?), pause_started=NULL WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setObject(1, now); ps.setInt(2, id); ps.executeUpdate(); }
        });
    }

    /** Check-out: stores the end time, sets the final state and records absences for everybody who never scanned. */
    public static void checkOut(int id, int groupId, LocalDateTime end) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE course_session SET paused_seconds = paused_seconds + CASE WHEN status='PAUSED' THEN TIMESTAMPDIFF(SECOND, pause_started, ?) ELSE 0 END, "
                            + "pause_started=NULL, actual_end=?, status=CASE WHEN is_replacement=1 THEN 'REPLACED' ELSE 'DELIVERED' END WHERE id=?")) {
                ps.setObject(1, end); ps.setObject(2, end); ps.setInt(3, id); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO attendance_record(session_id, student_id, scan_time, mode, wifi_decision, status) "
                            + "SELECT ?, st.id, NULL, 'AUTO', 'NA', 'ABSENT' FROM student st WHERE st.group_id=? AND st.active=1 "
                            + "AND st.id NOT IN (SELECT student_id FROM attendance_record WHERE session_id=?)")) {
                ps.setInt(1, id); ps.setInt(2, groupId); ps.setInt(3, id); ps.executeUpdate();
            }
        });
    }

    public static void replace(int sessionId, int newLecturerId, String reason) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE course_session SET original_lecturer_id=lecturer_id, lecturer_id=?, is_replacement=1, replacement_reason=? "
                            + "WHERE id=? AND status='PLANNED'")) {
                ps.setInt(1, newLecturerId); ps.setString(2, reason); ps.setInt(3, sessionId); ps.executeUpdate();
            }
        });
    }

    public static void validateRep(int sessionId, int studentId, LocalDateTime when) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE course_session SET rep_student_id=?, rep_validated_at=? WHERE id=?")) {
                ps.setInt(1, studentId); ps.setObject(2, when); ps.setInt(3, sessionId); ps.executeUpdate();
            }
        });
    }

    // ---- attendance ------------------------------------------------------------------------------------------

    public static List<RosterRow> roster(int sessionId, int groupId) throws SQLException {
        return Database.call(c -> {
            List<RosterRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT st.id, st.student_code, st.full_name, r.id AS rid, r.scan_time, r.status, r.wifi_decision, r.mode, r.provisional "
                            + "FROM student st LEFT JOIN attendance_record r ON r.student_id=st.id AND r.session_id=? "
                            + "WHERE st.group_id=? AND st.active=1 ORDER BY st.full_name")) {
                ps.setInt(1, sessionId); ps.setInt(2, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        RosterRow row = new RosterRow(rs.getInt(1), rs.getString(2), rs.getString(3));
                        rs.getInt("rid");
                        if (!rs.wasNull()) {
                            row.recorded.set(true);
                            row.time.set(Database.dt(rs, "scan_time"));
                            row.status.set(rs.getString("status"));
                            String w = rs.getString("wifi_decision");
                            row.wifi.set(w == null || w.equals("NA") ? "" : w);
                            row.mode.set(rs.getString("mode"));
                            row.provisional.set(rs.getBoolean("provisional"));
                        }
                        out.add(row);
                    }
                }
            }
            return out;
        });
    }

    /** Returns false when the student already has a record for the session (duplicate scan). */
    public static boolean insertAttendance(int sessionId, int studentId, LocalDateTime time, String mode, String bssid,
                                           String decision, String status, boolean provisional) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO attendance_record(session_id, student_id, scan_time, mode, bssid, wifi_decision, status, provisional) "
                            + "VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setInt(1, sessionId); ps.setInt(2, studentId); ps.setObject(3, time); ps.setString(4, mode);
                ps.setString(5, bssid); ps.setString(6, decision); ps.setString(7, status); ps.setBoolean(8, provisional);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public static void upsertManual(int sessionId, int studentId, String status, String reason, LocalDateTime time) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO attendance_record(session_id, student_id, scan_time, mode, wifi_decision, status, manual_reason, photo_confirmed) "
                            + "VALUES (?,?,?,'MANUAL','NA',?,?,1) ON DUPLICATE KEY UPDATE status=VALUES(status), manual_reason=VALUES(manual_reason), "
                            + "mode='MANUAL', scan_time=VALUES(scan_time), photo_confirmed=1")) {
                ps.setInt(1, sessionId); ps.setInt(2, studentId);
                if (time == null) ps.setNull(3, Types.TIMESTAMP); else ps.setTimestamp(3, Timestamp.valueOf(time));
                ps.setString(4, status); ps.setString(5, reason);
                ps.executeUpdate();
            }
        });
    }

    public static void deleteAttendance(int sessionId, int studentId) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM attendance_record WHERE session_id=? AND student_id=?")) {
                ps.setInt(1, sessionId); ps.setInt(2, studentId); ps.executeUpdate();
            }
        });
    }

    public static void confirmPhoto(int sessionId, int studentId) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE attendance_record SET photo_confirmed=1 WHERE session_id=? AND student_id=?")) {
                ps.setInt(1, sessionId); ps.setInt(2, studentId); ps.executeUpdate();
            }
        });
    }

    /** Exit scan (kiosk, or the random second QR code in mode B). Returns false when there is no entry scan. */
    public static boolean setExit(int sessionId, int studentId, LocalDateTime time) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE attendance_record SET exit_time=? WHERE session_id=? AND student_id=? AND status IN ('PRESENT','LATE')")) {
                ps.setObject(1, time); ps.setInt(2, sessionId); ps.setInt(3, studentId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public static List<SpotCandidate> spotCandidates(int sessionId, int n) throws SQLException {
        return Database.call(c -> {
            List<SpotCandidate> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT st.id, st.full_name, st.student_code, r.provisional FROM attendance_record r JOIN student st ON st.id=r.student_id "
                            + "WHERE r.session_id=? AND r.status IN ('PRESENT','LATE') ORDER BY r.provisional DESC, RAND() LIMIT ?")) {
                ps.setInt(1, sessionId); ps.setInt(2, n);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new SpotCandidate(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
                }
            }
            return out;
        });
    }

    public static void saveSpotCheck(int sessionId, int studentId, String outcome) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO spot_check(session_id, student_id, outcome, checked_at) VALUES (?,?,?,NOW())")) {
                ps.setInt(1, sessionId); ps.setInt(2, studentId); ps.setString(3, outcome); ps.executeUpdate();
            }
        });
    }

    // ---- teaching log ----------------------------------------------------------------------------------------

    private static LogEntry mapLog(ResultSet rs) throws SQLException {
        LogEntry e = new LogEntry();
        e.id = rs.getInt("id"); e.sessionId = rs.getInt("session_id"); e.type = rs.getString("session_type");
        e.details = nz(rs.getString("details")); e.activities = nz(rs.getString("activities"));
        e.assignments = nz(rs.getString("assignments")); e.due = Database.d(rs, "assignment_due");
        e.resources = nz(rs.getString("resources")); e.remarks = nz(rs.getString("remarks"));
        int by = rs.getInt("countersigned_by");
        e.countersignedBy = rs.wasNull() ? null : by;
        e.countersignedAt = Database.dt(rs, "countersigned_at");
        e.disputed = rs.getBoolean("disputed"); e.disputeNote = nz(rs.getString("dispute_note"));
        return e;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static final String LOG_COLS = "id, session_id, session_type, details, activities, assignments, assignment_due, resources, remarks, "
            + "countersigned_by, countersigned_at, disputed, dispute_note";

    public static LogEntry logOfSession(int sessionId) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + LOG_COLS + " FROM teaching_log WHERE session_id=?")) {
                ps.setInt(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapLog(rs) : null; }
            }
        });
    }

    public static LogEntry logById(int logId) throws SQLException {
        return Database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + LOG_COLS + " FROM teaching_log WHERE id=?")) {
                ps.setInt(1, logId);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapLog(rs) : null; }
            }
        });
    }

    public static Set<Integer> logTopicIds(int logId) throws SQLException {
        return Database.call(c -> {
            Set<Integer> out = new LinkedHashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT topic_id FROM log_topic WHERE log_id=? ORDER BY topic_id")) {
                ps.setInt(1, logId);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getInt(1)); }
            }
            return out;
        });
    }

    /** Creates or updates the entry of the session. Editing clears the class representative's countersignature. */
    public static int saveLog(LogEntry e, Collection<Integer> topicIds) throws SQLException {
        return Database.call(c -> {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO teaching_log(session_id, session_type, details, activities, assignments, assignment_due, resources, remarks, saved_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE session_type=VALUES(session_type), details=VALUES(details), "
                                + "activities=VALUES(activities), assignments=VALUES(assignments), assignment_due=VALUES(assignment_due), "
                                + "resources=VALUES(resources), remarks=VALUES(remarks), saved_at=NOW(), countersigned_by=NULL, countersigned_at=NULL")) {
                    ps.setInt(1, e.sessionId); ps.setString(2, e.type); ps.setString(3, e.details); ps.setString(4, e.activities);
                    ps.setString(5, e.assignments); ps.setObject(6, e.due); ps.setString(7, e.resources); ps.setString(8, e.remarks);
                    ps.executeUpdate();
                }
                int logId;
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM teaching_log WHERE session_id=?")) {
                    ps.setInt(1, e.sessionId);
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); logId = rs.getInt(1); }
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM log_topic WHERE log_id=?")) {
                    ps.setInt(1, logId); ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO log_topic(log_id, topic_id) VALUES (?,?)")) {
                    for (Integer t : topicIds) { ps.setInt(1, logId); ps.setInt(2, t); ps.addBatch(); }
                    ps.executeBatch();
                }
                c.commit();
                return logId;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        });
    }

    public static void countersign(int logId, int studentId, LocalDateTime when) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE teaching_log SET countersigned_by=?, countersigned_at=?, disputed=0, dispute_note=NULL WHERE id=?")) {
                ps.setInt(1, studentId); ps.setObject(2, when); ps.setInt(3, logId); ps.executeUpdate();
            }
        });
    }

    public static void setDispute(int logId, boolean disputed, String note) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE teaching_log SET disputed=?, dispute_note=? WHERE id=?")) {
                ps.setBoolean(1, disputed); ps.setString(2, note); ps.setInt(3, logId); ps.executeUpdate();
            }
        });
    }

    public static List<LogRow> logs(Integer courseId, Integer lecturerId) throws SQLException {
        return Database.call(c -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT l.id, l.session_id, s.course_id, s.session_date, CONCAT(cu.code,' – ',cu.title) AS course, l.session_type, "
                            + "(SELECT GROUP_CONCAT(CONCAT('Ch.', ch.number, ' ', tp.title) ORDER BY ch.number, tp.id SEPARATOR '; ') "
                            + " FROM log_topic lt JOIN topic tp ON tp.id=lt.topic_id JOIN chapter ch ON ch.id=tp.chapter_id WHERE lt.log_id=l.id) AS topics, "
                            + "st.full_name AS lecturer, l.countersigned_at, l.disputed "
                            + "FROM teaching_log l JOIN course_session s ON s.id=l.session_id JOIN course_unit cu ON cu.id=s.course_id "
                            + "JOIN staff st ON st.id=s.lecturer_id WHERE 1=1 ");
            List<Object> args = new ArrayList<>();
            if (courseId != null) { sql.append("AND s.course_id=? "); args.add(courseId); }
            if (lecturerId != null) { sql.append("AND s.lecturer_id=? "); args.add(lecturerId); }
            sql.append("ORDER BY s.session_date DESC, s.planned_start DESC");
            List<LogRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String topics = rs.getString("topics");
                        out.add(new LogRow(rs.getInt("id"), rs.getInt("session_id"), rs.getInt("course_id"), Database.d(rs, "session_date"),
                                rs.getString("course"), rs.getString("session_type"), topics == null ? "" : topics, rs.getString("lecturer"),
                                Database.dt(rs, "countersigned_at") != null, rs.getBoolean("disputed")));
                    }
                }
            }
            return out;
        });
    }

    // ---- Mode B ----------------------------------------------------------------------------------------------

    public static List<ModeBScan> pendingModeB(int sessionId) throws SQLException {
        return Database.call(c -> {
            List<ModeBScan> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, session_id, student_code, kind, token, window_idx, bssid, device_id, card_payload "
                            + "FROM mode_b_scan WHERE session_id=? AND processed=0 ORDER BY id LIMIT 200")) {
                ps.setInt(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new ModeBScan(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9)));
                }
            }
            return out;
        });
    }

    public static void markModeB(long id, String result, String reason) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE mode_b_scan SET processed=1, result=?, reason=? WHERE id=?")) {
                ps.setString(1, result); ps.setString(2, reason); ps.setLong(3, id); ps.executeUpdate();
            }
        });
    }
}
