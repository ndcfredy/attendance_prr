package app.service;

import app.core.AppContext;
import app.db.AdminDao;
import app.db.Database;
import app.db.SessionDao;
import app.model.Models.Decision;
import app.model.Models.SessionInfo;
import app.model.Models.Student;
import app.security.CardSigner;
import app.security.CardSigner.Parsed;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mode A: scan of a student card (webcam, 2D scanner or entrance kiosk). Works offline. */
public final class AttendanceService {
    public enum Outcome { RECORDED, DUPLICATE, INVALID, REVOKED, NOT_ENROLLED, ERROR }

    public record Result(Outcome outcome, Student student, String message, Decision decision, String status,
                         LocalDateTime time, String wifiText, boolean queued) {
        public boolean ok() { return outcome == Outcome.RECORDED; }
    }

    private final SessionInfo session;
    private final boolean kiosk;
    private final Map<String, Student> byCode = new HashMap<>();
    private final Set<Integer> recorded = new HashSet<>();

    public AttendanceService(SessionInfo session, List<Student> roster, Set<Integer> alreadyRecorded, boolean kiosk) {
        this.session = session;
        this.kiosk = kiosk;
        roster.forEach(s -> byCode.put(s.code(), s));
        recorded.addAll(alreadyRecorded);
    }

    public Map<String, Student> students() { return byCode; }

    public Set<Integer> recorded() { return recorded; }

    private Result fail(Outcome o, Student s, String msg) {
        return new Result(o, s, msg, null, null, null, "", false);
    }

    /** Checks a card QR text and records the student as present or late. */
    public synchronized Result scan(String payload) {
        Parsed p = CardSigner.parse(payload);
        if (p == null || !p.kind().equals("STU")) return fail(Outcome.INVALID, null, "Not a student card");
        if (!CardSigner.verify(p)) return fail(Outcome.INVALID, byCode.get(p.code()), "Signature invalid – forged or unknown card");
        Student st = byCode.get(p.code());
        if (st == null) return fail(Outcome.NOT_ENROLLED, null, "This student is not in group " + session.groupName());
        if (st.cardRevoked()) return fail(Outcome.REVOKED, st, "Card revoked – refer the student to the registry");
        if (recorded.contains(st.id())) {
            AdminDao.audit(userName(), "DUPLICATE_SCAN", st.code() + " session " + session.id());
            return fail(Outcome.DUPLICATE, st, "Card already scanned in this session (flagged)");
        }
        String bssid = WifiService.currentBssid();
        Decision d = WifiService.decide(bssid, session.buildingId(), kiosk);
        String wifi = kiosk && bssid.isEmpty() ? "wired kiosk" : WifiService.describe(bssid);
        LocalDateTime now = LocalDateTime.now();
        boolean late = session.actualStart() != null
                && now.isAfter(session.actualStart().plusMinutes(AppContext.lateThresholdMinutes()));
        String status = late ? "LATE" : "PRESENT";
        String mode = "A";
        boolean queued = false;
        if (AppContext.online.get()) {
            try {
                if (!SessionDao.insertAttendance(session.id(), st.id(), now, mode, bssid, d.name(), status, false)) {
                    recorded.add(st.id());
                    return fail(Outcome.DUPLICATE, st, "Card already scanned in this session (flagged)");
                }
            } catch (SQLException e) {
                if (!Database.isConnectionError(e)) return fail(Outcome.ERROR, st, "Database error: " + e.getMessage());
                queued = true;
            }
        } else {
            queued = true;
        }
        if (queued) {
            AppContext.queue.add(OfflineQueue.Op.of("SCAN", "sessionId", String.valueOf(session.id()), "studentId", String.valueOf(st.id()),
                    "time", now.toString(), "mode", mode, "bssid", bssid, "decision", d.name(), "status", status, "provisional", "false"));
            SyncService.refreshPending();
        }
        recorded.add(st.id());
        return new Result(Outcome.RECORDED, st, queued ? "Saved offline – will sync" : "Recorded", d, status, now, wifi, queued);
    }

    /** Exit scan (kiosk). */
    public synchronized Result exit(String payload) {
        Parsed p = CardSigner.parse(payload);
        if (p == null || !p.kind().equals("STU") || !CardSigner.verify(p)) return fail(Outcome.INVALID, null, "Invalid card");
        Student st = byCode.get(p.code());
        if (st == null) return fail(Outcome.NOT_ENROLLED, null, "Student not in this group");
        try {
            if (!SessionDao.setExit(session.id(), st.id(), LocalDateTime.now())) return fail(Outcome.ERROR, st, "No entry scan found for this student");
        } catch (SQLException e) {
            return fail(Outcome.ERROR, st, "Database error: " + e.getMessage());
        }
        return new Result(Outcome.RECORDED, st, "Exit recorded", Decision.NA, "EXIT", LocalDateTime.now(), "", false);
    }

    private static String userName() { return AppContext.user == null ? "kiosk" : AppContext.user.username(); }
}
