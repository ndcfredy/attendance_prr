package app.service;

import app.db.Database;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/** Official teaching log of a course unit as a printable HTML page (open it in the browser, then Print / Save as PDF). */
public final class LogReport {
    private LogReport() { }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    public static Path build(int courseId) throws SQLException, IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Teaching log</title><style>")
                .append("body{font-family:Segoe UI,Arial,sans-serif;margin:30px;color:#111}h1{color:#8f1d22;margin-bottom:4px}")
                .append("table{border-collapse:collapse;width:100%;font-size:12px}th,td{border:1px solid #999;padding:6px;vertical-align:top}")
                .append("th{background:#8f1d22;color:#fff}.m{color:#a00;font-weight:bold}.sig{margin-top:50px;display:flex;justify-content:space-between}")
                .append("</style></head><body>");
        Database.exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT code, title FROM course_unit WHERE id=?")) {
                ps.setInt(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) html.append("<h1>Official teaching log</h1><p><b>").append(esc(rs.getString(1))).append(" – ")
                            .append(esc(rs.getString(2))).append("</b><br>Printed on ").append(LocalDate.now()).append("</p>");
                }
            }
            html.append("<table><tr><th>Date</th><th>Lecturer / group</th><th>Time (actual)</th><th>Type</th><th>Topics covered</th>")
                    .append("<th>Activities</th><th>Assignments</th><th>Resources</th><th>Remarks</th><th>Class rep.</th></tr>");
            String sql = "SELECT s.session_date, s.session_type, s.actual_start, s.actual_end, s.status, lec.full_name AS lecturer, g.name AS grp, "
                    + "l.details, l.activities, l.assignments, l.assignment_due, l.resources, l.remarks, rep.full_name AS rep, l.countersigned_at, "
                    + "(SELECT GROUP_CONCAT(CONCAT('Ch.', ch.number, ' ', tp.title) ORDER BY ch.number, tp.id SEPARATOR '; ') FROM log_topic lt "
                    + " JOIN topic tp ON tp.id=lt.topic_id JOIN chapter ch ON ch.id=tp.chapter_id WHERE lt.log_id=l.id) AS topics "
                    + "FROM course_session s JOIN staff lec ON lec.id=s.lecturer_id JOIN study_group g ON g.id=s.group_id "
                    + "LEFT JOIN teaching_log l ON l.session_id=s.id LEFT JOIN student rep ON rep.id=l.countersigned_by "
                    + "WHERE s.course_id=? AND s.status IN ('DELIVERED','REPLACED','MISSED') ORDER BY s.session_date, s.planned_start";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, courseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean missed = "MISSED".equals(rs.getString("status"));
                        String start = rs.getString("actual_start"), end = rs.getString("actual_end");
                        String time = missed || start == null ? "<span class='m'>MISSED</span>"
                                : start.substring(11, 16) + " – " + (end == null ? "?" : end.substring(11, 16));
                        String topics = rs.getString("topics");
                        String details = rs.getString("details");
                        String assign = rs.getString("assignments");
                        if (assign != null && !assign.isBlank() && rs.getString("assignment_due") != null) assign += " (due " + rs.getString("assignment_due") + ")";
                        html.append("<tr><td>").append(rs.getString("session_date")).append("</td><td>").append(esc(rs.getString("lecturer")))
                                .append("<br>").append(esc(rs.getString("grp"))).append("</td><td>").append(time).append("</td><td>")
                                .append(esc(rs.getString("session_type"))).append("</td><td>").append(esc(topics))
                                .append(details == null || details.isBlank() ? "" : "<br><i>" + esc(details) + "</i>").append("</td><td>")
                                .append(esc(rs.getString("activities"))).append("</td><td>").append(esc(assign)).append("</td><td>")
                                .append(esc(rs.getString("resources"))).append("</td><td>").append(esc(rs.getString("remarks"))).append("</td><td>")
                                .append(rs.getString("rep") == null ? "–" : esc(rs.getString("rep")) + "<br>" + rs.getString("countersigned_at")).append("</td></tr>");
                    }
                }
            }
            html.append("</table><div class='sig'><div>Lecturer: ____________________</div><div>Head of department: ____________________</div></div></body></html>");
        });
        Path f = Files.createTempFile("teaching-log-", ".html");
        Files.writeString(f, html.toString(), StandardCharsets.UTF_8);
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(f.toUri());
        } catch (Exception ignored) { }
        return f;
    }
}
