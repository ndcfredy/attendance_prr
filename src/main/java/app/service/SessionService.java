package app.service;

import app.core.AppContext;
import app.db.AdminDao;
import app.db.Database;
import app.db.SessionDao;
import app.db.StaffDao;
import app.model.Models.Decision;
import app.model.Models.LogEntry;
import app.model.Models.SessionInfo;
import app.model.Models.Staff;
import app.security.CardSigner;
import app.security.CardSigner.Parsed;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** Lecturer check-in / check-out and log saving (queued when the network is down). */
public final class SessionService {
    private SessionService() { }

    /** Returns null on success, otherwise the reason it was refused. */
    public static String checkIn(SessionInfo s, String staffPayload) throws SQLException {
        Parsed p = CardSigner.parse(staffPayload);
        if (p == null || !p.kind().equals("STF")) return "This is not a staff card";
        if (!CardSigner.verify(p)) return "Signature invalid – forged or unknown card";
        Staff lec = StaffDao.byId(s.lecturerId());
        if (lec == null || !lec.code().equals(p.code())) return "This card does not belong to the lecturer assigned to this session";
        if (lec.cardRevoked()) return "Staff card revoked";
        if (!"PLANNED".equals(s.status())) return "Session is already " + s.status();
        SessionInfo active = SessionDao.activeFor(s.lecturerId());
        if (active != null) return "A session is already in progress: " + active.courseCode() + " – close it first";
        String bssid = WifiService.currentBssid();
        Decision d = WifiService.decide(bssid, s.buildingId(), false);
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        SessionDao.checkIn(s.id(), HexFormat.of().formatHex(raw), bssid, d.name(), LocalDateTime.now());
        AdminDao.audit(lec.username(), "CHECK_IN", "session " + s.id() + " wifi=" + d);
        return null;
    }

    /** Check-out; the write is queued when MySQL is unreachable. Returns true when queued. */
    public static boolean checkOut(SessionInfo s, LocalDateTime end) {
        if (AppContext.online.get()) {
            try {
                SessionDao.checkOut(s.id(), s.groupId(), end);
                return false;
            } catch (SQLException e) {
                if (!Database.isConnectionError(e)) throw new IllegalStateException(e.getMessage(), e);
            }
        }
        AppContext.queue.add(OfflineQueue.Op.of("CHECKOUT", "sessionId", String.valueOf(s.id()), "groupId", String.valueOf(s.groupId()), "end", end.toString()));
        SyncService.refreshPending();
        return true;
    }

    /** Saves the log; queued when MySQL is unreachable. Returns true when queued. */
    public static boolean saveLog(LogEntry e, Collection<Integer> topicIds) {
        if (AppContext.online.get()) {
            try {
                e.id = SessionDao.saveLog(e, topicIds);
                return false;
            } catch (SQLException ex) {
                if (!Database.isConnectionError(ex)) throw new IllegalStateException(ex.getMessage(), ex);
            }
        }
        AppContext.queue.add(OfflineQueue.Op.of("LOG", "sessionId", String.valueOf(e.sessionId), "type", e.type, "details", e.details,
                "activities", e.activities, "assignments", e.assignments, "due", e.due == null ? "" : e.due.toString(),
                "resources", e.resources, "remarks", e.remarks,
                "topics", topicIds.stream().map(String::valueOf).collect(Collectors.joining(","))));
        SyncService.refreshPending();
        return true;
    }
}
