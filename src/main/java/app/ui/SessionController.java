package app.ui;

import app.core.AppContext;
import app.core.Disposable;
import app.core.Fx;
import app.db.AdminDao;
import app.db.CourseDao;
import app.db.SessionDao;
import app.db.StudentDao;
import app.model.Models.Decision;
import app.model.Models.LogEntry;
import app.model.Models.SessionInfo;
import app.model.Models.SpotCandidate;
import app.model.Models.Student;
import app.model.Models.TopicRef;
import app.model.RosterRow;
import app.security.CardSigner;
import app.security.CardSigner.Parsed;
import app.security.QrToken;
import app.service.AttendanceService;
import app.service.AttendanceService.Result;
import app.service.ModeBService;
import app.service.QrService;
import app.service.SessionService;
import app.service.WebcamScanner;
import app.service.WifiService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** "Current session": live attendance (modes A and B), teaching log, class-rep countersignature, check-out. */
public class SessionController implements Disposable {
    @FXML private StackPane sessionRoot;
    @FXML private ScrollPane pageScroll;
    @FXML private VBox sessionContent;
    @FXML private Label courseLabel;
    @FXML private Label statusBadge;
    @FXML private Label sessionMetaLabel;
    @FXML private Button btnPause;
    @FXML private Button btnCheckout;
    @FXML private VBox leftColumn;
    @FXML private VBox scanCard;
    @FXML private ChoiceBox<String> modeChoice;
    @FXML private StackPane scanArea;
    @FXML private SVGPath scanPlaceholder;
    @FXML private ImageView scanPreview;
    @FXML private TextField scanInputField;
    @FXML private ToggleButton btnCamera;
    @FXML private Label scanHintLabel;
    @FXML private VBox qrCard;
    @FXML private ImageView qrView;
    @FXML private ProgressBar qrTimerBar;
    @FXML private Label qrCaptionLabel;
    @FXML private Button btnQrFullscreen;
    @FXML private Button btnExitCheck;
    @FXML private StackPane lastPhotoBox;
    @FXML private SVGPath lastPhotoPlaceholder;
    @FXML private ImageView lastPhoto;
    @FXML private Label lastNameLabel;
    @FXML private Label lastIdLabel;
    @FXML private Label lastSignatureLabel;
    @FXML private Label lastWifiLabel;
    @FXML private Label lastMessageLabel;
    @FXML private Button btnConfirm;
    @FXML private Button btnReject;
    @FXML private VBox rosterCard;
    @FXML private Label summaryLabel;
    @FXML private TextField searchField;
    @FXML private HBox statBar;
    @FXML private Region barPresent;
    @FXML private Region barLate;
    @FXML private Region barAbsent;
    @FXML private TableView<RosterRow> rosterTable;
    @FXML private TableColumn<RosterRow, String> colName;
    @FXML private TableColumn<RosterRow, String> colId;
    @FXML private TableColumn<RosterRow, String> colTime;
    @FXML private TableColumn<RosterRow, String> colStatus;
    @FXML private TableColumn<RosterRow, String> colWifi;
    @FXML private Label footerNoteLabel;
    @FXML private Button btnSpotCheck;
    @FXML private Button btnManualCorrection;
    @FXML private HBox logCard;
    @FXML private FlowPane topicFlow;
    @FXML private Button btnAddTopic;
    @FXML private Hyperlink linkDetails;
    @FXML private Label repStatusLabel;
    @FXML private Button btnCountersign;
    @FXML private Button btnSaveLog;
    @FXML private VBox noSessionBox;
    @FXML private Label noSessionLabel;
    @FXML private Button btnGoTimetable;

    private record Bundle(SessionInfo session, List<RosterRow> rows, List<Student> students, LogEntry log,
                          Set<Integer> topics, List<TopicRef> refs) { }

    private static final String MODE_A = "A – Card scan", MODE_B = "B – Dynamic QR", MODE_AB = "A + B – both";

    private SessionInfo session;
    private AttendanceService att;
    private final ObservableList<RosterRow> rows = FXCollections.observableArrayList();
    private final Map<Integer, RosterRow> byStudent = new HashMap<>();
    private final Map<Integer, Image> photos = new HashMap<>();
    private LogEntry log;
    private final Set<Integer> topicIds = new LinkedHashSet<>();
    private final Map<Integer, TopicRef> refs = new HashMap<>();
    private boolean dirty;
    private boolean paused;
    private RosterRow lastRow;
    private Timeline qrTimeline;
    private QrProjector projector;
    private String lastQrPayload = "";
    private long exitUntil;
    private int tick;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    @FXML
    private void initialize() {
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        colId.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().code));
        colTime.setCellValueFactory(c -> Bindings.createStringBinding(() -> Fx.hm(c.getValue().time.get()), c.getValue().time));
        colStatus.setCellValueFactory(c -> c.getValue().status);
        colWifi.setCellValueFactory(c -> c.getValue().wifi);
        Cells.status(colStatus);
        Cells.status(colWifi);

        FilteredList<RosterRow> filtered = new FilteredList<>(rows, r -> true);
        searchField.textProperty().addListener((o, a, q) -> {
            String s = q == null ? "" : q.trim().toLowerCase();
            filtered.setPredicate(r -> s.isEmpty() || r.name.toLowerCase().contains(s) || r.code.toLowerCase().contains(s));
        });
        SortedList<RosterRow> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(rosterTable.comparatorProperty());
        rosterTable.setItems(sorted);
        statBar.widthProperty().addListener((o, a, b) -> updateBar());

        modeChoice.getItems().setAll(MODE_A, MODE_B, MODE_AB);
        modeChoice.setValue(MODE_A);
        modeChoice.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> applyMode(true));

        clearLastScan();
        showEmpty(true);
        int uid = AppContext.user.id();
        Fx.async(() -> load(uid), this::onLoaded, e -> { showEmpty(true); noSessionLabel.setText("Cannot load the session"); Fx.error(Fx.msg(e)); });
    }

    // ---- loading ---------------------------------------------------------------------------------------------

    private Bundle load(int lecturerId) throws Exception {
        SessionInfo s = SessionDao.activeFor(lecturerId);
        if (s == null) return null;
        List<RosterRow> r = SessionDao.roster(s.id(), s.groupId());
        List<Student> students = StudentDao.byGroup(s.groupId());
        LogEntry l = SessionDao.logOfSession(s.id());
        Set<Integer> topics = l == null ? new LinkedHashSet<>() : SessionDao.logTopicIds(l.id);
        List<TopicRef> tr = CourseDao.topicRefs(s.courseId());
        WifiService.lookup(WifiService.currentBssid()); // warm the access-point cache for offline use
        return new Bundle(s, r, students, l, topics, tr);
    }

    private void showEmpty(boolean empty) {
        noSessionBox.setVisible(empty);
        noSessionBox.setManaged(empty);
        pageScroll.setVisible(!empty);
        pageScroll.setManaged(!empty);
    }

    private void onLoaded(Bundle b) {
        if (b == null) { showEmpty(true); return; }
        showEmpty(false);
        session = b.session();
        paused = "PAUSED".equals(session.status());
        rows.setAll(b.rows());
        byStudent.clear();
        rows.forEach(r -> byStudent.put(r.studentId, r));
        Set<Integer> recorded = new LinkedHashSet<>();
        rows.stream().filter(r -> r.recorded.get()).forEach(r -> recorded.add(r.studentId));
        att = new AttendanceService(session, b.students(), recorded, false);
        log = b.log();
        if (log == null) { log = new LogEntry(); log.sessionId = session.id(); log.type = session.type(); }
        topicIds.clear();
        topicIds.addAll(b.topics());
        refs.clear();
        b.refs().forEach(t -> refs.put(t.topicId(), t));
        dirty = false;
        String m = session.mode();
        modeChoice.setValue("B".equals(m) ? MODE_B : "AB".equals(m) ? MODE_AB : MODE_A);
        refreshHeader();
        refreshChips();
        refreshRep();
        updateSummary();
        updateSaveButton();
        applyMode(false);
        scanInputField.requestFocus();
    }

    private void refreshHeader() {
        courseLabel.setText(session.courseLabel());
        statusBadge.getStyleClass().setAll(paused ? "badge-warn" : "badge-ok");
        statusBadge.setText(paused ? "Paused" : "In progress");
        btnPause.setText(paused ? "Resume" : "Pause");
        StringBuilder sb = new StringBuilder(Cells.pretty(session.type()))
                .append(" - Group ").append(session.groupName()).append(" - Room ").append(session.roomName())
                .append(" - Planned ").append(session.plannedStart().format(Fx.HM)).append("-").append(session.plannedEnd().format(Fx.HM));
        if (session.actualStart() != null) {
            long late = java.time.Duration.between(session.date().atTime(session.plannedStart()), session.actualStart()).toMinutes();
            sb.append(" - Actual start ").append(session.actualStart().format(Fx.HM));
            sb.append(late > 0 ? " (late by " + late + " min)" : " (on time)");
        }
        if (session.replacement()) sb.append(" - REPLACEMENT lecturer");
        sessionMetaLabel.setText(sb.toString());
    }

    // ---- modes -----------------------------------------------------------------------------------------------

    private boolean modeA() { return !MODE_B.equals(modeChoice.getValue()); }

    private boolean modeB() { return !MODE_A.equals(modeChoice.getValue()); }

    private void applyMode(boolean persist) {
        boolean a = modeA(), b = modeB();
        scanCard.setVisible(a); scanCard.setManaged(a);
        qrCard.setVisible(b); qrCard.setManaged(b);
        if (!a) { btnCamera.setSelected(false); WebcamScanner.shared().stop(); }
        if (qrTimeline != null) { qrTimeline.stop(); qrTimeline = null; }
        if (b && session != null) {
            qrTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> qrTick()));
            qrTimeline.setCycleCount(Timeline.INDEFINITE);
            qrTimeline.play();
            qrTick();
        }
        if (persist && session != null) {
            String m = a && b ? "AB" : b ? "B" : "A";
            Fx.background(() -> { try { SessionDao.setMode(session.id(), m); } catch (Exception ignored) { } });
        }
    }

    private void qrTick() {
        if (session == null || session.secret() == null) return;
        qrTimerBar.setProgress(QrToken.secondsLeft() / (double) QrToken.WINDOW_SECONDS);
        boolean exit = System.currentTimeMillis() < exitUntil;
        String payload = QrToken.payload(session.secret(), session.id(), exit ? "EXIT" : "ENTRY");
        String caption = exit ? "EXIT CHECK – scan to confirm you are still in the room" : "Scan to check in – changes every 10 seconds";
        qrCaptionLabel.setText(caption);
        if (!payload.equals(lastQrPayload)) {
            lastQrPayload = payload;
            qrView.setImage(QrService.fxImage(payload, 460));
            if (projector != null && projector.isShowing()) projector.update(payload, caption);
        }
        if (++tick % 2 == 0 && !paused && att != null && polling.compareAndSet(false, true)) {
            Fx.async(() -> ModeBService.process(session, att), list -> {
                polling.set(false);
                if (!list.isEmpty()) {
                    long ok = list.stream().filter(ModeBService.Processed::accepted).count();
                    ModeBService.Processed lastOk = list.stream().filter(ModeBService.Processed::accepted).reduce((x, y) -> y).orElse(null);
                    footerNoteLabel.setText("Phone scans: " + ok + " accepted, " + (list.size() - ok) + " rejected"
                            + (lastOk == null ? "" : " – last: " + lastOk.student().name()));
                    refreshRoster();
                }
            }, e -> polling.set(false));
        }
    }

    @FXML
    private void onQrFullscreen() {
        if (session == null) return;
        if (projector == null) projector = new QrProjector("Attendance QR – " + session.courseCode());
        projector.show();
        lastQrPayload = "";
        qrTick();
    }

    @FXML
    private void onExitCheck() {
        exitUntil = System.currentTimeMillis() + 60_000;
        lastQrPayload = "";
        qrTick();
        Fx.info("Exit check started: for 60 seconds the QR code asks students to confirm they are still present.");
    }

    private void refreshRoster() {
        Fx.async(() -> SessionDao.roster(session.id(), session.groupId()), fresh -> {
            for (RosterRow f : fresh) {
                RosterRow r = byStudent.get(f.studentId);
                if (r == null) continue;
                r.time.set(f.time.get()); r.status.set(f.status.get()); r.wifi.set(f.wifi.get());
                r.mode.set(f.mode.get()); r.provisional.set(f.provisional.get()); r.recorded.set(f.recorded.get());
                if (f.recorded.get()) att.recorded().add(f.studentId); else att.recorded().remove(f.studentId);
            }
            updateSummary();
        }, e -> { });
    }

    // ---- Mode A scanning -------------------------------------------------------------------------------------

    @FXML
    private void onToggleCamera() {
        if (btnCamera.isSelected()) {
            String err = WebcamScanner.shared().start(img -> { scanPreview.setImage(img); scanPlaceholder.setVisible(false); }, this::handleScan);
            if (err != null) { scanHintLabel.setText(err + " – use the 2D scanner field instead."); btnCamera.setSelected(false); }
            else scanHintLabel.setText("Hold the card in front of the camera - the photo appears below");
        } else {
            WebcamScanner.shared().stop();
            scanPreview.setImage(null);
            scanPlaceholder.setVisible(true);
        }
    }

    @FXML
    private void onScanInput() {
        String text = scanInputField.getText().trim();
        scanInputField.clear();
        if (!text.isEmpty()) handleScan(text);
    }

    private void handleScan(String payload) {
        if (att == null) return;
        if (paused) { showLast(null, "Session paused – resume before scanning", "bad-text"); return; }
        Fx.async(() -> att.scan(payload), this::onScanResult, e -> showLast(null, Fx.msg(e), "bad-text"));
    }

    private void onScanResult(Result r) {
        if (!r.ok()) {
            lastRow = null;
            lastNameLabel.setText(r.student() != null ? r.student().name() : "Scan refused");
            lastIdLabel.setText(r.student() != null ? "ID: " + r.student().code() : "");
            lastSignatureLabel.setText(r.message().startsWith("Signature") ? "Signature INVALID" : "Signature checked");
            style(lastSignatureLabel, r.message().startsWith("Signature") ? "bad-text" : null);
            lastWifiLabel.setText("");
            lastMessageLabel.setText(r.message());
            style(lastMessageLabel, "bad-text");
            btnConfirm.setDisable(true);
            btnReject.setDisable(true);
            lastPhoto.setImage(null);
            lastPhotoPlaceholder.setVisible(true);
            return;
        }
        Student st = r.student();
        RosterRow row = byStudent.get(st.id());
        if (row != null) {
            row.time.set(r.time()); row.status.set(r.status()); row.wifi.set(r.decision().name());
            row.mode.set("A"); row.recorded.set(true);
        }
        lastRow = row;
        lastNameLabel.setText(st.name());
        lastIdLabel.setText("ID: " + st.code());
        lastSignatureLabel.setText("Signature valid");
        style(lastSignatureLabel, "ok-text");
        String w = r.wifiText();
        switch (r.decision()) {
            case VERIFIED -> { lastWifiLabel.setText("Wi-Fi: " + w + " verified"); style(lastWifiLabel, "ok-text"); }
            case TO_REVIEW -> { lastWifiLabel.setText("Wi-Fi: " + w + " – to review (other building)"); style(lastWifiLabel, "warn-text"); }
            default -> { lastWifiLabel.setText("Wi-Fi: not verified – flagged for the registry"); style(lastWifiLabel, "bad-text"); }
        }
        lastMessageLabel.setText(("LATE".equals(r.status()) ? "Marked LATE. " : "") + r.message());
        style(lastMessageLabel, r.queued() ? "warn-text" : null);
        btnConfirm.setDisable(false);
        btnReject.setDisable(false);
        showPhoto(st.id());
        updateSummary();
        scanInputField.requestFocus();
    }

    private void showPhoto(int studentId) {
        Image cached = photos.get(studentId);
        if (cached != null) { lastPhoto.setImage(cached); lastPhotoPlaceholder.setVisible(false); return; }
        lastPhoto.setImage(null);
        lastPhotoPlaceholder.setVisible(true);
        Fx.async(() -> StudentDao.photo(studentId), bytes -> {
            if (bytes == null || bytes.length == 0) return;
            Image img = new Image(new ByteArrayInputStream(bytes));
            photos.put(studentId, img);
            if (lastRow != null && lastRow.studentId == studentId) { lastPhoto.setImage(img); lastPhotoPlaceholder.setVisible(false); }
        }, e -> { });
    }

    private void showLast(Student s, String message, String cls) {
        lastNameLabel.setText(s == null ? "Scan refused" : s.name());
        lastMessageLabel.setText(message);
        style(lastMessageLabel, cls);
    }

    private static void style(Label l, String cls) {
        l.getStyleClass().removeAll("ok-text", "warn-text", "bad-text");
        if (cls != null) l.getStyleClass().add(cls);
    }

    private void clearLastScan() {
        lastRow = null;
        lastNameLabel.setText("[STUDENT NAME]");
        lastIdLabel.setText("ID: [STUDENT ID]");
        lastSignatureLabel.setText("");
        lastWifiLabel.setText("");
        lastMessageLabel.setText("");
        lastPhoto.setImage(null);
        lastPhotoPlaceholder.setVisible(true);
        btnConfirm.setDisable(true);
        btnReject.setDisable(true);
    }

    @FXML
    private void onConfirm() {
        if (lastRow == null || session == null) return;
        int id = lastRow.studentId;
        Fx.background(() -> { try { SessionDao.confirmPhoto(session.id(), id); } catch (Exception ignored) { } });
        clearLastScan();
        scanInputField.requestFocus();
    }

    @FXML
    private void onReject() {
        if (lastRow == null || session == null) return;
        RosterRow row = lastRow;
        Fx.async(() -> {
            SessionDao.deleteAttendance(session.id(), row.studentId);
            AdminDao.audit(AppContext.user.username(), "SCAN_REJECTED", row.code + " session " + session.id() + " (photo does not match)");
            return null;
        }, x -> {
            row.status.set("ABSENT"); row.time.set(null); row.wifi.set(""); row.recorded.set(false);
            att.recorded().remove(row.studentId);
            clearLastScan();
            updateSummary();
        }, e -> Fx.error("Reject needs a connection to the database: " + Fx.msg(e)));
    }

    // ---- roster actions --------------------------------------------------------------------------------------

    @FXML
    private void onManualCorrection() {
        RosterRow row = rosterTable.getSelectionModel().getSelectedItem();
        if (row == null) { Fx.info("Select a student in the table first."); return; }
        var form = Forms.create("Manual correction", "Apply");
        form.note("Student: " + row.name + " (" + row.code + ")");
        ChoiceBox<String> status = form.add("New status", new ChoiceBox<>());
        status.getItems().setAll("PRESENT", "LATE", "ABSENT");
        status.setValue(row.status.get());
        TextArea reason = form.add("Reason (required)", new TextArea());
        reason.setPrefRowCount(3);
        form.validate(() -> reason.getText().isBlank() ? "A manual correction needs a reason." : null);
        if (!form.show()) return;
        String st = status.getValue(), why = reason.getText().trim();
        LocalDateTime now = LocalDateTime.now();
        Fx.async(() -> {
            if (st.equals("ABSENT")) SessionDao.deleteAttendance(session.id(), row.studentId);
            else SessionDao.upsertManual(session.id(), row.studentId, st, why, now);
            AdminDao.audit(AppContext.user.username(), "MANUAL_CORRECTION", row.code + " -> " + st + " session " + session.id() + ": " + why);
            return null;
        }, x -> {
            row.status.set(st);
            if (st.equals("ABSENT")) { row.time.set(null); row.wifi.set(""); row.recorded.set(false); att.recorded().remove(row.studentId); }
            else { row.time.set(now); row.mode.set("MANUAL"); row.recorded.set(true); att.recorded().add(row.studentId); }
            updateSummary();
        }, e -> Fx.error("Manual correction needs a connection: " + Fx.msg(e)));
    }

    @FXML
    private void onSpotCheck() {
        Fx.async(() -> SessionDao.spotCandidates(session.id(), 5), list -> {
            if (list.isEmpty()) { Fx.info("Nobody is recorded present yet."); return; }
            var form = Forms.create("Spot check – verify physically", "Save");
            form.note("Call these names out loud. Tick the students who are really in the room.");
            List<CheckBox> boxes = new ArrayList<>();
            for (SpotCandidate c : list) {
                CheckBox cb = new CheckBox(c.name() + " (" + c.code() + ")" + (c.provisional() ? "  – provisional, other building" : ""));
                cb.setSelected(true);
                form.add("", cb);
                boxes.add(cb);
            }
            if (!form.show()) return;
            int missing = 0;
            for (int i = 0; i < list.size(); i++) {
                boolean here = boxes.get(i).isSelected();
                if (!here) missing++;
                int sid = list.get(i).studentId();
                String outcome = here ? "PRESENT" : "ABSENT";
                Fx.background(() -> { try { SessionDao.saveSpotCheck(session.id(), sid, outcome); } catch (Exception ignored) { } });
            }
            if (missing > 0) Fx.info(missing + " student(s) were not found. Use \"Manual correction\" to mark them absent.");
        }, e -> Fx.error(Fx.msg(e)));
    }

    private void updateSummary() {
        int p = 0, l = 0, a = 0;
        for (RosterRow r : rows) {
            switch (r.status.get()) {
                case "PRESENT" -> p++;
                case "LATE" -> l++;
                default -> a++;
            }
        }
        summaryLabel.setText("Present " + p + " - Late " + l + " - Absent " + a + " - Total " + rows.size());
        updateBar();
    }

    private void updateBar() {
        int total = rows.size();
        if (total == 0) return;
        long p = rows.stream().filter(r -> "PRESENT".equals(r.status.get())).count();
        long l = rows.stream().filter(r -> "LATE".equals(r.status.get())).count();
        double w = statBar.getWidth();
        setW(barPresent, w * p / total);
        setW(barLate, w * l / total);
        setW(barAbsent, w - w * p / total - w * l / total);
    }

    private static void setW(Region r, double w) { r.setMinWidth(0); r.setPrefWidth(Math.max(0, w)); }

    // ---- pause / check-out -----------------------------------------------------------------------------------

    @FXML
    private void onPause() {
        boolean willPause = !paused;
        Fx.async(() -> { SessionDao.setPaused(session.id(), willPause, LocalDateTime.now()); return null; }, x -> {
            paused = willPause;
            refreshHeader();
        }, e -> Fx.error(Fx.msg(e)));
    }

    @FXML
    private void onCheckout() {
        if (session == null) return;
        if (!hasLogContent()) { Fx.error("Complete the teaching log before check-out: add at least one topic or some details."); return; }
        boolean signed = log.countersignedAt != null;
        if (!signed && !log.disputed && !Fx.confirm("The class representative has not countersigned yet. Check out anyway?")) return;
        if (!Fx.confirm("Close the session now? Students without a scan will be recorded absent.")) return;
        Fx.async(() -> {
            if (dirty) SessionService.saveLog(log, topicIds);
            return SessionService.checkOut(session, LocalDateTime.now());
        }, queued -> {
            Fx.info(queued ? "Session closed offline – it will be written to the database when the network returns."
                    : "Session closed. Hours delivered and absences are recorded.");
            dispose();
            MainController.get().open("navTimetable");
        }, e -> Fx.error(Fx.msg(e)));
    }

    // ---- teaching log ----------------------------------------------------------------------------------------

    private boolean hasLogContent() {
        return !topicIds.isEmpty() || (log != null && !log.details.isBlank());
    }

    private void refreshChips() {
        topicFlow.getChildren().clear();
        for (Integer id : topicIds) {
            TopicRef t = refs.get(id);
            Label text = new Label((t == null ? "Topic " + id : "Ch." + t.chapterNo() + " - " + t.title()) + " (done)");
            Button x = new Button("×");
            x.getStyleClass().add("chip-remove");
            x.setOnAction(e -> { topicIds.remove(id); markDirty(); refreshChips(); });
            HBox chip = new HBox(4, text, x);
            chip.getStyleClass().add("chip");
            chip.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            topicFlow.getChildren().add(chip);
        }
    }

    private void markDirty() {
        dirty = true;
        updateSaveButton();
    }

    private void updateSaveButton() {
        btnSaveLog.setText(dirty ? "Save teaching log" : "Teaching log saved ✓");
    }

    private void refreshRep() {
        if (log.disputed) {
            repStatusLabel.setText("Disputed – escalated to the head of department");
            style(repStatusLabel, "bad-text");
        } else if (log.countersignedAt != null) {
            String name = att.students().values().stream().filter(s -> log.countersignedBy != null && s.id() == log.countersignedBy)
                    .map(Student::name).findFirst().orElse("class representative");
            repStatusLabel.setText("Countersigned by " + name + " at " + Fx.hm(log.countersignedAt));
            style(repStatusLabel, "ok-text");
        } else {
            repStatusLabel.setText("Waiting for countersignature");
            style(repStatusLabel, "warn-text");
        }
    }

    @FXML
    private void onAddTopic() {
        if (refs.isEmpty()) { Fx.info("This course has no plan yet. Create chapters and topics in \"Syllabus coverage\" first."); return; }
        var form = Forms.create("Topics covered today", "Done");
        ListView<TopicRef> list = form.add("Course plan", new ListView<>());
        list.getItems().setAll(refs.values().stream().sorted((a, b) -> a.chapterNo() != b.chapterNo()
                ? Integer.compare(a.chapterNo(), b.chapterNo()) : Integer.compare(a.topicId(), b.topicId())).toList());
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPrefSize(420, 300);
        list.getItems().stream().filter(t -> topicIds.contains(t.topicId())).forEach(t -> list.getSelectionModel().select(t));
        if (!form.show()) return;
        topicIds.clear();
        list.getSelectionModel().getSelectedItems().forEach(t -> topicIds.add(t.topicId()));
        markDirty();
        refreshChips();
    }

    @FXML
    private void onLogDetails() {
        var form = Forms.create("Session log", "OK");
        ChoiceBox<String> type = form.add("Session type", new ChoiceBox<>());
        type.getItems().setAll("LECTURE", "TUTORIAL", "PRACTICAL", "ASSESSMENT", "MAKEUP");
        type.setValue(log.type);
        TextArea details = area(form, "Topics – details", log.details);
        TextArea activities = area(form, "Activities", log.activities);
        TextArea assignments = area(form, "Assignments", log.assignments);
        DatePicker due = form.add("Assignment due date", new DatePicker(log.due));
        TextArea resources = area(form, "Resources (slides, links)", log.resources);
        TextArea remarks = area(form, "Remarks", log.remarks);
        if (!form.show()) return;
        log.type = type.getValue(); log.details = details.getText().trim(); log.activities = activities.getText().trim();
        log.assignments = assignments.getText().trim(); log.due = due.getValue(); log.resources = resources.getText().trim();
        log.remarks = remarks.getText().trim();
        markDirty();
    }

    private static TextArea area(Forms.Form f, String label, String value) {
        TextArea a = f.add(label, new TextArea(value));
        a.setPrefRowCount(2);
        a.setPrefWidth(420);
        return a;
    }

    @FXML
    private void onSaveLog() {
        if (!hasLogContent()) { Fx.error("Add at least one topic or some details first."); return; }
        Fx.async(() -> SessionService.saveLog(log, topicIds), queued -> {
            dirty = false;
            log.countersignedBy = null; log.countersignedAt = null; log.disputed = false;
            refreshRep();
            updateSaveButton();
            if (queued) Fx.info("Saved offline – it will be written to the database when the network returns.");
        }, e -> Fx.error(Fx.msg(e)));
    }

    @FXML
    private void onCountersign() {
        if (!hasLogContent()) { Fx.error("Fill in the teaching log first."); return; }
        try {
            if (dirty || log.id == 0) { SessionService.saveLog(log, topicIds); dirty = false; updateSaveButton(); }
        } catch (RuntimeException ex) { Fx.error(Fx.msg(ex)); return; }
        if (log.id == 0) { Fx.error("The log is only saved offline. Countersignature needs the network."); return; }
        Optional<String> payload = CardScanDialog.prompt("Class representative", "The class representative scans his or her own card to confirm the lecturer is present and the content was delivered.");
        if (payload.isEmpty()) return;
        Parsed p = CardSigner.parse(payload.get());
        if (p == null || !p.kind().equals("STU") || !CardSigner.verify(p)) { Fx.error("Invalid or forged card."); return; }
        Student rep = att.students().get(p.code());
        if (rep == null || !rep.classRep()) { Fx.error("This card does not belong to the class representative of this group."); return; }
        ButtonType yes = new ButtonType("Countersign"), dispute = new ButtonType("Dispute"), cancel = ButtonType.CANCEL;
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                rep.name() + ": was the lecture given and the content above delivered?", yes, dispute, cancel);
        a.setHeaderText(null);
        app.core.ThemeManager.style(a.getDialogPane());
        Optional<ButtonType> choice = a.showAndWait();
        if (choice.isEmpty() || choice.get() == cancel) return;
        LocalDateTime now = LocalDateTime.now();
        if (choice.get() == yes) {
            Fx.async(() -> {
                SessionDao.validateRep(session.id(), rep.id(), now);
                SessionDao.countersign(log.id, rep.id(), now);
                return null;
            }, x -> { log.countersignedBy = rep.id(); log.countersignedAt = now; log.disputed = false; refreshRep(); }, e -> Fx.error(Fx.msg(e)));
        } else {
            Optional<String> note = Fx.input("Dispute", "Reason for the disagreement:");
            if (note.isEmpty()) return;
            Fx.async(() -> { SessionDao.setDispute(log.id, true, note.get()); return null; },
                    x -> { log.disputed = true; log.disputeNote = note.get(); refreshRep(); }, e -> Fx.error(Fx.msg(e)));
        }
    }

    @FXML
    private void onGoTimetable() { MainController.get().open("navTimetable"); }

    @Override
    public void dispose() {
        WebcamScanner.shared().stop();
        if (qrTimeline != null) qrTimeline.stop();
        if (projector != null) projector.close();
    }
}
