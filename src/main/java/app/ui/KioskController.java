package app.ui;

import app.core.Config;
import app.core.Fx;
import app.core.ThemeManager;
import app.db.AdminDao;
import app.db.SessionDao;
import app.db.StaffDao;
import app.db.StudentDao;
import app.model.Models.Room;
import app.model.Models.SessionInfo;
import app.model.Models.Student;
import app.model.RosterRow;
import app.service.AttendanceService;
import app.service.AttendanceService.Result;
import app.service.WebcamScanner;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Entrance kiosk for lecture halls (mode A): students scan their own card; the photo is shown at every scan. */
public class KioskController {
    @FXML private VBox kioskRoot;
    @FXML private Label kioskTitle;
    @FXML private Label kioskRoomLabel;
    @FXML private Label kioskClock;
    @FXML private StackPane kioskPreviewBox;
    @FXML private SVGPath kioskScanIcon;
    @FXML private ImageView kioskPreview;
    @FXML private VBox kioskResultBox;
    @FXML private SVGPath kioskPhotoPlaceholder;
    @FXML private ImageView kioskPhoto;
    @FXML private Label kioskName;
    @FXML private Label kioskMessage;
    @FXML private Label kioskWifi;
    @FXML private TextField kioskScanField;
    @FXML private ToggleButton kioskModeToggle;
    @FXML private Label kioskSessionLabel;
    @FXML private Button btnKioskExit;

    private Room room;
    private SessionInfo session;
    private AttendanceService att;
    private Timeline clock;
    private Timeline poll;
    private PauseTransition reset;

    @FXML
    private void initialize() {
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> kioskClock.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        reset = new PauseTransition(Duration.seconds(4));
        reset.setOnFinished(e -> idle());
        idle();
    }

    /** Called by Nav. A null room means "use the saved kiosk room, or ask". */
    public void setRoom(Room r) {
        if (r != null) { room = r; start(); return; }
        Fx.async(AdminDao::rooms, rooms -> {
            String saved = Config.get("kiosk.room");
            Room found = rooms.stream().filter(x -> x.name().equals(saved)).findFirst().orElse(null);
            if (found == null && !rooms.isEmpty()) {
                ChoiceDialog<Room> d = new ChoiceDialog<>(rooms.get(0), rooms);
                d.setHeaderText(null);
                d.setTitle("Kiosk room");
                d.setContentText("Room of this kiosk:");
                ThemeManager.style(d.getDialogPane());
                found = d.showAndWait().orElse(rooms.get(0));
            }
            room = found;
            if (room != null) { Config.set("kiosk.room", room.name()); Config.save(); start(); }
        }, null);
    }

    private void start() {
        kioskRoomLabel.setText("Room " + room.name() + " – " + room.building());
        poll = new Timeline(new KeyFrame(Duration.seconds(5), e -> refreshSession()));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
        refreshSession();
        String err = WebcamScanner.shared().start(img -> { kioskPreview.setImage(img); kioskScanIcon.setVisible(false); }, this::handle);
        if (err != null) kioskMessage.setText(err + " – use the 2D scanner.");
        kioskScanField.requestFocus();
    }

    private void refreshSession() {
        Fx.async(() -> {
            SessionInfo s = SessionDao.inProgressInRoom(room.id());
            if (s == null) return null;
            if (session != null && session.id() == s.id() && att != null) return new Object[]{s, null, null};
            List<Student> students = StudentDao.byGroup(s.groupId());
            Set<Integer> rec = new LinkedHashSet<>();
            for (RosterRow r : SessionDao.roster(s.id(), s.groupId())) if (r.recorded.get()) rec.add(r.studentId);
            return new Object[]{s, students, rec};
        }, o -> {
            if (o == null) { session = null; att = null; kioskSessionLabel.setText("No session in progress in this room"); return; }
            Object[] a = (Object[]) o;
            SessionInfo s = (SessionInfo) a[0];
            if (a[1] != null) {
                @SuppressWarnings("unchecked") List<Student> students = (List<Student>) a[1];
                @SuppressWarnings("unchecked") Set<Integer> rec = (Set<Integer>) a[2];
                att = new AttendanceService(s, students, rec, true);
            }
            session = s;
            kioskSessionLabel.setText(s.courseLabel() + " – group " + s.groupName());
        }, e -> kioskSessionLabel.setText("Waiting for the database…"));
    }

    @FXML
    private void onKioskScan() {
        String t = kioskScanField.getText().trim();
        kioskScanField.clear();
        if (!t.isEmpty()) handle(t);
    }

    @FXML
    private void onKioskMode() {
        boolean exit = kioskModeToggle.isSelected();
        kioskModeToggle.setText(exit ? "Exit scan" : "Entry scan");
        kioskTitle.setText(exit ? "Scan your card to leave" : "Scan your student card");
    }

    private void handle(String payload) {
        if (att == null) { show("kiosk-bad", null, "No session", "No session is in progress in this room.", ""); return; }
        boolean exit = kioskModeToggle.isSelected();
        Fx.async(() -> exit ? att.exit(payload) : att.scan(payload), this::onResult, e -> show("kiosk-bad", null, "Error", Fx.msg(e), ""));
    }

    private void onResult(Result r) {
        String name = r.student() == null ? "Card refused" : r.student().name();
        if (r.ok()) {
            String cls = r.decision() == app.model.Models.Decision.VERIFIED || r.decision() == app.model.Models.Decision.NA ? "kiosk-ok" : "kiosk-warn";
            String msg = "EXIT".equals(r.status()) ? "Exit recorded" : ("LATE".equals(r.status()) ? "Welcome – recorded LATE" : "Welcome – recorded present");
            show(cls, r.student(), name, msg + (r.queued() ? " (offline)" : ""), r.decision() == app.model.Models.Decision.TO_REVIEW ? "Location to be reviewed" : "");
        } else {
            show("kiosk-bad", r.student(), name, r.message(), "");
        }
    }

    private void show(String cls, Student s, String name, String msg, String wifi) {
        kioskResultBox.getStyleClass().removeAll("kiosk-ok", "kiosk-warn", "kiosk-bad", "kiosk-idle");
        kioskResultBox.getStyleClass().add(cls);
        kioskName.setText(name);
        kioskMessage.setText(msg);
        kioskWifi.setText(wifi);
        kioskPhoto.setImage(null);
        kioskPhotoPlaceholder.setVisible(true);
        if (s != null) {
            Fx.async(() -> StudentDao.photo(s.id()), bytes -> {
                if (bytes != null && bytes.length > 0) { kioskPhoto.setImage(new Image(new ByteArrayInputStream(bytes))); kioskPhotoPlaceholder.setVisible(false); }
            }, e -> { });
        }
        reset.playFromStart();
    }

    private void idle() {
        kioskResultBox.getStyleClass().removeAll("kiosk-ok", "kiosk-warn", "kiosk-bad", "kiosk-idle");
        kioskResultBox.getStyleClass().add("kiosk-idle");
        kioskName.setText("Welcome");
        kioskMessage.setText("Hold your card in front of the camera");
        kioskWifi.setText("");
        kioskPhoto.setImage(null);
        kioskPhotoPlaceholder.setVisible(true);
        kioskScanField.requestFocus();
    }

    @FXML
    private void onKioskExit() {
        var form = Forms.create("Staff exit", "Leave kiosk mode");
        TextField user = form.add("Username", new TextField());
        PasswordField pass = form.add("Password", new PasswordField());
        if (!form.show()) return;
        Fx.async(() -> StaffDao.login(user.getText().trim(), pass.getText()), staff -> {
            if (staff == null) { Fx.error("Wrong credentials."); return; }
            WebcamScanner.shared().stop();
            if (clock != null) clock.stop();
            if (poll != null) poll.stop();
            Nav.showLogin();
        }, null);
    }
}
