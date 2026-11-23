package app.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Plain data holders (Java records) shared by the DAOs and the screens. */
public final class Models {
    private Models() { }

    public enum Role { LECTURER, REGISTRY, HOD, ADMIN }

    /** Result of the Wi-Fi access-point check. */
    public enum Decision { VERIFIED, TO_REVIEW, NOT_VERIFIED, NA }

    public record Staff(int id, String code, String name, String username, Role role, String employment,
                        String department, boolean cardRevoked, boolean active) {
        @Override public String toString() { return name; }
    }

    public record Student(int id, String code, String name, int groupId, String groupName, boolean classRep,
                          String phone, boolean cardRevoked, boolean active) {
        @Override public String toString() { return name + " (" + code + ")"; }
    }

    public record Group(int id, String name, int size) {
        @Override public String toString() { return name; }
    }

    public record Building(int id, String name) {
        @Override public String toString() { return name; }
    }

    public record Room(int id, String name, int capacity, String type, int buildingId, String building) {
        @Override public String toString() { return name + " (" + building + ")"; }
    }

    public record Course(int id, String code, String title) {
        @Override public String toString() { return code.isEmpty() ? title : code + " – " + title; }
    }

    public record Chapter(int id, int courseId, int number, String title, double plannedHours) { }

    public record Topic(int id, int chapterId, String title) { }

    /** A topic with its chapter number, for pick lists. */
    public record TopicRef(int topicId, int chapterNo, String title, int chapterId) {
        @Override public String toString() { return "Ch." + chapterNo + " – " + title; }
    }

    public record AccessPoint(int id, String bssid, int buildingId, String building, String zone, String status) { }

    public record SessionInfo(int id, int courseId, String courseCode, String courseTitle, int groupId, String groupName,
                              int roomId, String roomName, int buildingId, int lecturerId, String lecturerName,
                              LocalDate date, LocalTime plannedStart, LocalTime plannedEnd, String type, String status,
                              LocalDateTime actualStart, LocalDateTime actualEnd, boolean replacement,
                              String secret, String mode, int pausedSeconds) {
        public String courseLabel() { return courseCode + " – " + courseTitle; }
        public double plannedHours() {
            return java.time.Duration.between(plannedStart, plannedEnd).toMinutes() / 60.0;
        }
    }

    /** Mutable teaching-log entry being edited in the session screen. */
    public static final class LogEntry {
        public int id;
        public int sessionId;
        public String type = "LECTURE";
        public String details = "";
        public String activities = "";
        public String assignments = "";
        public LocalDate due;
        public String resources = "";
        public String remarks = "";
        public Integer countersignedBy;
        public LocalDateTime countersignedAt;
        public boolean disputed;
        public String disputeNote = "";
    }

    public record ModeBScan(long id, int sessionId, String studentCode, String kind, String token, long window,
                            String bssid, String deviceId, String cardPayload) { }

    public record HoursRow(String lecturer, String course, double planned, double delivered, int sessions,
                           int missed, int replaced) { }

    public record AbsenceRow(int studentId, String student, String code, String course, int sessions, int absent,
                             int unjustified, double rate) { }

    public record HistoryRow(String course, int sessions, int attended, int absent, int unjustified, double rate) { }

    public record LogRow(int logId, int sessionId, int courseId, LocalDate date, String course, String type,
                         String topics, String lecturer, boolean countersigned, boolean disputed) { }

    public record JustifRow(int id, int studentId, int sessionId, String student, String course, LocalDate date,
                            String reason, String doc, String status) { }

    public record CoverageRow(int chapterNo, String title, int topicsTotal, int topicsDone,
                              double plannedHours, double deliveredHours) { }

    public record SpotCandidate(int studentId, String name, String code, boolean provisional) { }
}
