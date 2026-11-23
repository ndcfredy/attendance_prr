package app.ui;

import app.core.AppContext;
import app.core.Fx;
import app.db.AdminDao;
import app.db.CourseDao;
import app.db.SessionDao;
import app.db.StaffDao;
import app.model.Models.Course;
import app.model.Models.Group;
import app.model.Models.Role;
import app.model.Models.Room;
import app.model.Models.SessionInfo;
import app.model.Models.Staff;
import app.service.SessionService;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/** Timetable: sessions of the day / week, lecturer check-in, new session, replacement. */
public class TimetableController {
    @FXML private VBox timetableRoot;
    @FXML private Label titleLabel;
    @FXML private ChoiceBox<Staff> lecturerChoice;
    @FXML private ChoiceBox<String> viewChoice;
    @FXML private DatePicker datePicker;
    @FXML private Button btnRefresh;
    @FXML private TableView<SessionInfo> sessionTable;
    @FXML private TableColumn<SessionInfo, String> colDate;
    @FXML private TableColumn<SessionInfo, String> colTime;
    @FXML private TableColumn<SessionInfo, String> colCourse;
    @FXML private TableColumn<SessionInfo, String> colType;
    @FXML private TableColumn<SessionInfo, String> colGroup;
    @FXML private TableColumn<SessionInfo, String> colRoom;
    @FXML private TableColumn<SessionInfo, String> colLecturer;
    @FXML private TableColumn<SessionInfo, String> colStatus;
    @FXML private Label infoLabel;
    @FXML private Button btnNewSession;
    @FXML private Button btnReplace;
    @FXML private Button btnOpen;
    @FXML private Button btnCheckIn;

    private static final Staff ALL = new Staff(0, "", "All lecturers", "", Role.LECTURER, "", "", false, true);
    private boolean lecturerOnly;

    @FXML
    private void initialize() {
        lecturerOnly = AppContext.user.role() == Role.LECTURER;
        titleLabel.setText(lecturerOnly ? "My timetable" : "Timetable");
        colDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().date().format(Fx.DMY)));
        colTime.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().plannedStart().format(Fx.HM) + " - " + c.getValue().plannedEnd().format(Fx.HM)));
        colCourse.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseLabel()));
        colType.setCellValueFactory(c -> new SimpleStringProperty(Cells.pretty(c.getValue().type())));
        colGroup.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().groupName()));
        colRoom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().roomName()));
        colLecturer.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().lecturerName() + (c.getValue().replacement() ? " (replacement)" : "")));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        Cells.status(colStatus);
        viewChoice.getItems().setAll("Day", "Week");
        viewChoice.setValue("Week");
        datePicker.setValue(LocalDate.now());
        lecturerChoice.setVisible(!lecturerOnly);
        lecturerChoice.setManaged(!lecturerOnly);
        btnNewSession.setDisable(AppContext.can(Role.REGISTRY));
        btnReplace.setVisible(!lecturerOnly);
        btnReplace.setManaged(!lecturerOnly);
        viewChoice.valueProperty().addListener((o, a, b) -> reload());
        datePicker.valueProperty().addListener((o, a, b) -> reload());
        lecturerChoice.valueProperty().addListener((o, a, b) -> reload());
        if (!lecturerOnly) {
            Fx.async(StaffDao::lecturers, list -> {
                lecturerChoice.getItems().setAll(ALL);
                lecturerChoice.getItems().addAll(list);
                lecturerChoice.setValue(ALL);
            }, null);
        } else {
            reload();
        }
    }

    private void reload() {
        LocalDate d = datePicker.getValue() == null ? LocalDate.now() : datePicker.getValue();
        boolean week = "Week".equals(viewChoice.getValue());
        LocalDate from = week ? d.with(DayOfWeek.MONDAY) : d, to = week ? d.with(DayOfWeek.SUNDAY) : d;
        Integer lecturer = lecturerOnly ? Integer.valueOf(AppContext.user.id())
                : lecturerChoice.getValue() == null || lecturerChoice.getValue().id() == 0 ? null : lecturerChoice.getValue().id();
        Fx.async(() -> { SessionDao.markMissed(); return SessionDao.between(from, to, lecturer); },
                list -> { sessionTable.getItems().setAll(list); infoLabel.setText(list.size() + " session(s) from " + from.format(Fx.DMY) + " to " + to.format(Fx.DMY)); }, null);
    }

    @FXML
    private void onRefresh() { reload(); }

    @FXML
    private void onCheckIn() {
        SessionInfo s = sessionTable.getSelectionModel().getSelectedItem();
        if (s == null) { Fx.info("Select a session first."); return; }
        if (!s.date().equals(LocalDate.now())) { Fx.error("You can only check in to a session scheduled today."); return; }
        if (!"PLANNED".equals(s.status())) { Fx.error("This session is " + s.status().toLowerCase().replace('_', ' ') + "."); return; }
        Optional<String> payload = CardScanDialog.prompt("Lecturer check-in", "Scan your signed staff card to open " + s.courseCode() + " – " + s.groupName() + ".");
        if (payload.isEmpty()) return;
        Fx.async(() -> SessionService.checkIn(s, payload.get()), err -> {
            if (err != null) { Fx.error(err); return; }
            MainController.get().open("navSession");
        }, null);
    }

    @FXML
    private void onOpen() {
        SessionInfo s = sessionTable.getSelectionModel().getSelectedItem();
        if (s != null && ("IN_PROGRESS".equals(s.status()) || "PAUSED".equals(s.status())) && s.lecturerId() == AppContext.user.id()) {
            MainController.get().open("navSession");
        } else {
            MainController.get().open("navSession");
        }
    }

    private record Refs(List<Course> courses, List<Group> groups, List<Room> rooms, List<Staff> lecturers) { }

    @FXML
    private void onNewSession() {
        Fx.async(() -> new Refs(CourseDao.courses(null), AdminDao.groups(), AdminDao.rooms(), StaffDao.lecturers()), r -> {
            var form = Forms.create("New session", "Create");
            ChoiceBox<Course> course = form.add("Course unit", new ChoiceBox<>());
            course.getItems().setAll(r.courses());
            ChoiceBox<Group> group = form.add("Group", new ChoiceBox<>());
            group.getItems().setAll(r.groups());
            ChoiceBox<Room> room = form.add("Room", new ChoiceBox<>());
            room.getItems().setAll(r.rooms());
            ChoiceBox<Staff> lecturer = form.add("Lecturer", new ChoiceBox<>());
            lecturer.getItems().setAll(r.lecturers());
            if (lecturerOnly) { r.lecturers().stream().filter(l -> l.id() == AppContext.user.id()).findFirst().ifPresent(lecturer::setValue); lecturer.setDisable(true); }
            DatePicker date = form.add("Date", new DatePicker(LocalDate.now()));
            TextField start = form.add("Start (HH:mm)", new TextField("08:00"));
            TextField end = form.add("End (HH:mm)", new TextField("10:00"));
            ChoiceBox<String> type = form.add("Type", new ChoiceBox<>());
            type.getItems().setAll("LECTURE", "TUTORIAL", "PRACTICAL", "ASSESSMENT", "MAKEUP");
            type.setValue("LECTURE");
            form.validate(() -> {
                if (course.getValue() == null || group.getValue() == null || room.getValue() == null || lecturer.getValue() == null || date.getValue() == null)
                    return "Fill in every field.";
                try {
                    if (!LocalTime.parse(end.getText().trim()).isAfter(LocalTime.parse(start.getText().trim()))) return "The end must be after the start.";
                } catch (DateTimeParseException e) { return "Times must look like 08:00."; }
                if (room.getValue().capacity() < group.getValue().size()) return "Room capacity (" + room.getValue().capacity() + ") is smaller than the group (" + group.getValue().size() + ").";
                return null;
            });
            if (!form.show()) return;
            Fx.async(() -> SessionDao.create(course.getValue().id(), group.getValue().id(), room.getValue().id(), lecturer.getValue().id(),
                    date.getValue(), LocalTime.parse(start.getText().trim()), LocalTime.parse(end.getText().trim()), type.getValue()),
                    id -> reload(), null);
        }, null);
    }

    @FXML
    private void onReplace() {
        SessionInfo s = sessionTable.getSelectionModel().getSelectedItem();
        if (s == null || !"PLANNED".equals(s.status())) { Fx.info("Select a planned session first."); return; }
        Fx.async(StaffDao::lecturers, list -> {
            var form = Forms.create("Replace lecturer", "Replace");
            form.note(s.courseLabel() + " – " + s.date().format(Fx.DMY) + " – current lecturer: " + s.lecturerName());
            ChoiceBox<Staff> lec = form.add("Replacement", new ChoiceBox<>());
            lec.getItems().setAll(list.stream().filter(l -> l.id() != s.lecturerId()).toList());
            TextArea reason = form.add("Reason (required)", new TextArea());
            reason.setPrefRowCount(3);
            form.validate(() -> lec.getValue() == null || reason.getText().isBlank() ? "Choose the replacement and give a reason." : null);
            if (!form.show()) return;
            Fx.async(() -> {
                SessionDao.replace(s.id(), lec.getValue().id(), reason.getText().trim());
                AdminDao.audit(AppContext.user.username(), "REPLACEMENT", "session " + s.id() + " -> " + lec.getValue().username() + ": " + reason.getText().trim());
                return null;
            }, x -> reload(), null);
        }, null);
    }
}
