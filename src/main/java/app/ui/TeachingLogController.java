package app.ui;

import app.core.AppContext;
import app.core.Fx;
import app.db.CourseDao;
import app.db.SessionDao;
import app.model.Models.Course;
import app.model.Models.LogEntry;
import app.model.Models.LogRow;
import app.model.Models.Role;
import app.service.LogReport;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;

/** Teaching log consultation, official printable log, dispute resolution (head of department). */
public class TeachingLogController {
    @FXML private HBox logRoot;
    @FXML private ChoiceBox<Course> courseFilter;
    @FXML private Button btnRefresh;
    @FXML private Button btnPrintLog;
    @FXML private TableView<LogRow> logTable;
    @FXML private TableColumn<LogRow, String> colLDate;
    @FXML private TableColumn<LogRow, String> colLCourse;
    @FXML private TableColumn<LogRow, String> colLType;
    @FXML private TableColumn<LogRow, String> colLTopics;
    @FXML private TableColumn<LogRow, String> colLLecturer;
    @FXML private TableColumn<LogRow, String> colLSign;
    @FXML private Label topicsLabel;
    @FXML private TextArea detailsArea;
    @FXML private TextArea activitiesArea;
    @FXML private TextArea assignmentsArea;
    @FXML private TextArea resourcesArea;
    @FXML private TextArea remarksArea;
    @FXML private Label disputeLabel;
    @FXML private Button btnResolveDispute;

    private static final Course ALL = new Course(0, "", "All course units");
    private LogEntry shown;
    private boolean lecturerOnly;

    @FXML
    private void initialize() {
        lecturerOnly = AppContext.user.role() == Role.LECTURER;
        colLDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().date().format(Fx.DMY)));
        colLCourse.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().course()));
        colLType.setCellValueFactory(c -> new SimpleStringProperty(Cells.pretty(c.getValue().type())));
        colLTopics.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().topics()));
        colLLecturer.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().lecturer()));
        colLSign.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().disputed() ? "DISPUTED" : c.getValue().countersigned() ? "COUNTERSIGNED" : "PENDING"));
        Cells.status(colLSign);
        btnResolveDispute.setVisible(false);
        btnResolveDispute.setManaged(false);
        logTable.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> showEntry(r));
        courseFilter.valueProperty().addListener((o, a, b) -> reload());
        Integer lec = lecturerOnly ? AppContext.user.id() : null;
        Fx.async(() -> CourseDao.courses(lec), list -> {
            courseFilter.getItems().setAll(ALL);
            courseFilter.getItems().addAll(list);
            courseFilter.setValue(ALL);
        }, null);
    }

    private void reload() {
        Course c = courseFilter.getValue();
        Integer cid = c == null || c.id() == 0 ? null : c.id();
        Integer lec = lecturerOnly ? AppContext.user.id() : null;
        Fx.async(() -> SessionDao.logs(cid, lec), list -> logTable.getItems().setAll(list), null);
    }

    private void showEntry(LogRow r) {
        shown = null;
        btnResolveDispute.setVisible(false);
        btnResolveDispute.setManaged(false);
        if (r == null) return;
        topicsLabel.setText(r.topics().isEmpty() ? "No topic selected from the plan" : r.topics());
        Fx.async(() -> SessionDao.logById(r.logId()), e -> {
            if (e == null) return;
            shown = e;
            detailsArea.setText(e.details);
            activitiesArea.setText(e.activities);
            assignmentsArea.setText(e.assignments + (e.due == null ? "" : "\nDue: " + e.due));
            resourcesArea.setText(e.resources);
            remarksArea.setText(e.remarks);
            disputeLabel.setText(e.disputed ? "Disputed by the class representative: " + e.disputeNote : "");
            boolean canResolve = e.disputed && AppContext.can(Role.HOD, Role.ADMIN);
            btnResolveDispute.setVisible(canResolve);
            btnResolveDispute.setManaged(canResolve);
        }, null);
    }

    @FXML
    private void onRefresh() { reload(); }

    @FXML
    private void onPrintLog() {
        LogRow r = logTable.getSelectionModel().getSelectedItem();
        Course c = courseFilter.getValue();
        int courseId = c != null && c.id() != 0 ? c.id() : r != null ? r.courseId() : 0;
        if (courseId == 0) { Fx.info("Choose a course unit (or select an entry) to print its official teaching log."); return; }
        Fx.async(() -> LogReport.build(courseId), f -> { }, null);
    }

    @FXML
    private void onResolveDispute() {
        if (shown == null) return;
        Fx.input("Resolve dispute", "Resolution note (kept in the log):").ifPresent(note ->
                Fx.async(() -> { SessionDao.setDispute(shown.id, false, "Resolved by " + AppContext.user.name() + ": " + note); return null; },
                        x -> reload(), null));
    }
}
