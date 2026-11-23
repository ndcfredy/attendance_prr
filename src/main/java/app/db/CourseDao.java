package app.db;

import app.model.Models.Chapter;
import app.model.Models.Course;
import app.model.Models.CoverageRow;
import app.model.Models.TopicRef;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Course units, course plan (chapters / topics) and syllabus coverage. */
public final class CourseDao {
    public record Coverage(List<CoverageRow> rows, int topicsTotal, int topicsDone, double plannedHours, double deliveredHours) {
        public double topicPercent() { return topicsTotal == 0 ? 0 : 100.0 * topicsDone / topicsTotal; }
        public double hoursPercent() { return plannedHours <= 0 ? 0 : Math.min(100, 100.0 * deliveredHours / plannedHours); }
        /** Behind schedule: fewer topics covered than the hours already taught would suggest. */
        public boolean behind() { return topicsTotal > 0 && deliveredHours > 0 && topicPercent() + 15 < hoursPercent(); }
    }

    private CourseDao() { }

    public static List<Course> courses(Integer lecturerId) throws SQLException {
        return Database.call(c -> {
            String sql = "SELECT cu.id, cu.code, cu.title FROM course_unit cu "
                    + (lecturerId == null ? "" : "WHERE cu.lecturer_id=? OR EXISTS (SELECT 1 FROM course_session s WHERE s.course_id=cu.id AND s.lecturer_id=?) ")
                    + "ORDER BY cu.code";
            List<Course> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (lecturerId != null) { ps.setInt(1, lecturerId); ps.setInt(2, lecturerId); }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Course(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
            }
            return out;
        });
    }

    public static List<Chapter> chapters(int courseId) throws SQLException {
        return Database.call(c -> {
            List<Chapter> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, course_id, number, title, planned_hours FROM chapter WHERE course_id=? ORDER BY number")) {
                ps.setInt(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Chapter(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getDouble(5)));
                }
            }
            return out;
        });
    }

    public static List<TopicRef> topicRefs(int courseId) throws SQLException {
        return Database.call(c -> {
            List<TopicRef> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t.id, ch.number, t.title, ch.id FROM topic t JOIN chapter ch ON ch.id=t.chapter_id WHERE ch.course_id=? ORDER BY ch.number, t.id")) {
                ps.setInt(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new TopicRef(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getInt(4)));
                }
            }
            return out;
        });
    }

    public static void addChapter(int courseId, int number, String title, double hours) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO chapter(course_id, number, title, planned_hours) VALUES (?,?,?,?)")) {
                ps.setInt(1, courseId); ps.setInt(2, number); ps.setString(3, title); ps.setDouble(4, hours);
                ps.executeUpdate();
            }
        });
    }

    public static void addTopic(int chapterId, String title) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO topic(chapter_id, title) VALUES (?,?)")) {
                ps.setInt(1, chapterId); ps.setString(2, title); ps.executeUpdate();
            }
        });
    }

    public static void deleteChapter(int id) throws SQLException { delete("DELETE FROM chapter WHERE id=?", id); }

    public static void deleteTopic(int id) throws SQLException { delete("DELETE FROM topic WHERE id=?", id); }

    private static void delete(String sql, int id) throws SQLException {
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setInt(1, id); ps.executeUpdate(); }
        });
    }

    /** Topics delivered against planned, and hours delivered against planned, per chapter. */
    public static Coverage coverage(int courseId) throws SQLException {
        return Database.call(c -> {
            Map<Integer, double[]> byChapter = new LinkedHashMap<>(); // chapterId -> [topicsTotal, topicsDone, planned, delivered]
            Map<Integer, String[]> meta = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ch.id, ch.number, ch.title, ch.planned_hours, (SELECT COUNT(*) FROM topic t WHERE t.chapter_id=ch.id) AS n "
                            + "FROM chapter ch WHERE ch.course_id=? ORDER BY ch.number")) {
                ps.setInt(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byChapter.put(rs.getInt(1), new double[]{rs.getInt(5), 0, rs.getDouble(4), 0});
                        meta.put(rs.getInt(1), new String[]{String.valueOf(rs.getInt(2)), rs.getString(3)});
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t.chapter_id, COUNT(DISTINCT t.id) FROM log_topic lt JOIN topic t ON t.id=lt.topic_id "
                            + "JOIN teaching_log l ON l.id=lt.log_id JOIN course_session s ON s.id=l.session_id "
                            + "WHERE s.course_id=? AND s.status IN ('DELIVERED','REPLACED') GROUP BY t.chapter_id")) {
                ps.setInt(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { double[] a = byChapter.get(rs.getInt(1)); if (a != null) a[1] = rs.getInt(2); }
                }
            }
            double totalDelivered = 0;
            // hours of each delivered session, shared between the chapters of the topics covered
            Map<Integer, Double> sessionHours = new HashMap<>();
            Map<Integer, Map<Integer, Integer>> logChapters = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT l.id, GREATEST(TIMESTAMPDIFF(SECOND, s.actual_start, s.actual_end) - s.paused_seconds, 0) / 3600.0 AS h "
                            + "FROM course_session s LEFT JOIN teaching_log l ON l.session_id=s.id "
                            + "WHERE s.course_id=? AND s.status IN ('DELIVERED','REPLACED')")) {
                ps.setInt(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double h = rs.getDouble(2);
                        totalDelivered += h;
                        int logId = rs.getInt(1);
                        if (!rs.wasNull()) sessionHours.put(logId, h);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT lt.log_id, t.chapter_id, COUNT(*) FROM log_topic lt JOIN topic t ON t.id=lt.topic_id "
                            + "JOIN chapter ch ON ch.id=t.chapter_id WHERE ch.course_id=? GROUP BY lt.log_id, t.chapter_id")) {
                ps.setInt(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) logChapters.computeIfAbsent(rs.getInt(1), k -> new HashMap<>()).put(rs.getInt(2), rs.getInt(3));
                }
            }
            for (Map.Entry<Integer, Map<Integer, Integer>> e : logChapters.entrySet()) {
                Double h = sessionHours.get(e.getKey());
                if (h == null) continue;
                int sum = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
                for (Map.Entry<Integer, Integer> ce : e.getValue().entrySet()) {
                    double[] a = byChapter.get(ce.getKey());
                    if (a != null) a[3] += h * ce.getValue() / sum;
                }
            }
            List<CoverageRow> rows = new ArrayList<>();
            int tt = 0, td = 0;
            double planned = 0;
            for (Map.Entry<Integer, double[]> e : byChapter.entrySet()) {
                double[] a = e.getValue();
                String[] m = meta.get(e.getKey());
                rows.add(new CoverageRow(Integer.parseInt(m[0]), m[1], (int) a[0], (int) a[1], a[2], a[3]));
                tt += (int) a[0]; td += (int) a[1]; planned += a[2];
            }
            return new Coverage(rows, tt, td, planned, totalDelivered);
        });
    }
}
