package app.service;

import app.db.SessionDao;
import app.db.StudentDao;
import app.model.Models.Decision;
import app.model.Models.ModeBScan;
import app.model.Models.SessionInfo;
import app.model.Models.Student;
import app.security.CardSigner;
import app.security.CardSigner.Parsed;
import app.security.QrToken;
import app.core.AppContext;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mode B: students scan the projected dynamic QR with the phone app. The app writes a row in mode_b_scan
 * (student card text, token read from the QR, BSSID, phone id); this class validates each row for the session.
 */
public final class ModeBService {
    public record Processed(ModeBScan scan, Student student, boolean accepted, String reason) { }

    private ModeBService() { }

    public static List<Processed> process(SessionInfo s, AttendanceService att) throws SQLException {
        List<Processed> out = new ArrayList<>();
        for (ModeBScan sc : SessionDao.pendingModeB(s.id())) {
            Processed r = check(s, att, sc);
            SessionDao.markModeB(sc.id(), r.accepted() ? "ACCEPTED" : "REJECTED", r.reason());
            out.add(r);
        }
        return out;
    }

    private static Processed reject(ModeBScan sc, Student st, String why) { return new Processed(sc, st, false, why); }

    private static Processed check(SessionInfo s, AttendanceService att, ModeBScan sc) throws SQLException {
        Parsed p = CardSigner.parse(sc.cardPayload());
        if (p == null || !p.kind().equals("STU") || !CardSigner.verify(p) || !p.code().equals(sc.studentCode()))
            return reject(sc, null, "Invalid or forged card");
        Student st = att.students().get(p.code());
        if (st == null) return reject(sc, null, "Student not in this group");
        if (st.cardRevoked()) return reject(sc, st, "Card revoked");
        if (s.secret() == null || !QrToken.verify(s.secret(), s.id(), sc.kind(), sc.window(), sc.token()))
            return reject(sc, st, "QR code expired – scan again");
        String phone = st.phone();
        if (phone == null || phone.isBlank()) {
            StudentDao.setPhone(st.id(), sc.deviceId());
            att.students().put(st.code(), new Student(st.id(), st.code(), st.name(), st.groupId(), st.groupName(),
                    st.classRep(), sc.deviceId(), st.cardRevoked(), st.active()));
        } else if (!phone.equals(sc.deviceId())) {
            return reject(sc, st, "This account is registered on another phone");
        }
        Decision d = WifiService.decide(sc.bssid(), s.buildingId(), false);
        if (d == Decision.NOT_VERIFIED) return reject(sc, st, "Not connected to a registered campus access point");
        LocalDateTime now = LocalDateTime.now();
        if ("EXIT".equals(sc.kind())) {
            return SessionDao.setExit(s.id(), st.id(), now) ? new Processed(sc, st, true, "Exit recorded")
                    : reject(sc, st, "No entry scan found");
        }
        if (att.recorded().contains(st.id())) return reject(sc, st, "Already checked in");
        boolean late = s.actualStart() != null && now.isAfter(s.actualStart().plusMinutes(AppContext.lateThresholdMinutes()));
        boolean provisional = d == Decision.TO_REVIEW; // registered in another building: provisional, first in the spot check
        if (!SessionDao.insertAttendance(s.id(), st.id(), now, "B", sc.bssid(), d.name(), late ? "LATE" : "PRESENT", provisional))
            return reject(sc, st, "Already checked in");
        att.recorded().add(st.id());
        return new Processed(sc, st, true, provisional ? "Provisionally present (other building)" : "Present");
    }
}
