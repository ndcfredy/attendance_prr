package app.ui;

import app.core.AppContext;
import app.core.Config;
import app.core.Fx;
import app.db.ReportDao;
import app.model.Models.AbsenceRow;
import app.model.Models.HoursRow;
import app.model.Models.Role;
import app.service.Csv;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Hours delivered, absence thresholds, alerts, CSV export. */
public class ReportsController {
    @FXML private VBox reportsRoot;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private Button btnRefresh;
    @FXML private Button btnExport;
    @FXML private TabPane reportTabs;
    @FXML private Tab tabHours;
    @FXML private Tab tabAbsences;
    @FXML private Tab tabAlerts;
    @FXML private TableView<HoursRow> hoursTable;
    @FXML private TableColumn<HoursRow, String> colRLecturer;
    @FXML private TableColumn<HoursRow, String> colRCourse;
    @FXML private TableColumn<HoursRow, String> colRSessions;
    @FXML private TableColumn<HoursRow, String> colRPlanned;
    @FXML private TableColumn<HoursRow, String> colRDelivered;
    @FXML private TableColumn<HoursRow, String> colRMissed;
    @FXML private TableColumn<HoursRow, String> colRReplaced;
    @FXML private Spinner<Integer> thresholdSpinner;
    @FXML private TableView<AbsenceRow> absenceTable;
    @FXML private TableColumn<AbsenceRow, String> colAStudent;
    @FXML private TableColumn<AbsenceRow, String> colACode;
    @FXML private TableColumn<AbsenceRow, String> colACourse;
    @FXML private TableColumn<AbsenceRow, String> colASessions;
    @FXML private TableColumn<AbsenceRow, String> colAAbsent;
    @FXML private TableColumn<AbsenceRow, String> colAUnjust;
    @FXML private TableColumn<AbsenceRow, String> colARate;
    @FXML private ListView<String> alertList;

    private Integer lecturerId;

    @FXML
    private void initialize() {
        lecturerId = AppContext.user.role() == Role.LECTURER ? AppContext.user.id() : null;
        fromDate.setValue(LocalDate.now().minusMonths(3));
        toDate.setValue(LocalDate.now());
        thresholdSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, Config.getInt("absence.threshold.percent", 20)));
        colRLecturer.setCellValueFactory(c -> s(c.getValue().lecturer()));
        colRCourse.setCellValueFactory(c -> s(c.getValue().course()));
        colRSessions.setCellValueFactory(c -> s(String.valueOf(c.getValue().sessions())));
        colRPlanned.setCellValueFactory(c -> s(String.format("%.1f", c.getValue().planned())));
        colRDelivered.setCellValueFactory(c -> s(String.format("%.1f", c.getValue().delivered())));
        colRMissed.setCellValueFactory(c -> s(String.valueOf(c.getValue().missed())));
        colRReplaced.setCellValueFactory(c -> s(String.valueOf(c.getValue().replaced())));
        colAStudent.setCellValueFactory(c -> s(c.getValue().student()));
        colACode.setCellValueFactory(c -> s(c.getValue().code()));
        colACourse.setCellValueFactory(c -> s(c.getValue().course()));
        colASessions.setCellValueFactory(c -> s(String.valueOf(c.getValue().sessions())));
        colAAbsent.setCellValueFactory(c -> s(String.valueOf(c.getValue().absent())));
        colAUnjust.setCellValueFactory(c -> s(String.valueOf(c.getValue().unjustified())));
        colARate.setCellValueFactory(c -> s(String.format("%.0f %%", c.getValue().rate())));
        thresholdSpinner.valueProperty().addListener((o, a, b) -> loadAbsences());
        onRefresh();
    }

    private static SimpleStringProperty s(String v) { return new SimpleStringProperty(v); }

    @FXML
    private void onRefresh() {
        LocalDate from = fromDate.getValue(), to = toDate.getValue();
        if (from == null || to == null) return;
        Fx.async(() -> { app.db.SessionDao.markMissed(); return ReportDao.hours(from, to, lecturerId); }, l -> hoursTable.getItems().setAll(l), null);
        loadAbsences();
        Fx.async(() -> ReportDao.alerts(lecturerId), l -> {
            alertList.getItems().setAll(l.isEmpty() ? List.of("No alert – everything is on track.") : l);
        }, null);
    }

    private void loadAbsences() {
        Integer t = thresholdSpinner.getValue();
        if (t == null) return;
        Fx.async(() -> ReportDao.absences(t, lecturerId), l -> absenceTable.getItems().setAll(l), null);
    }

    @FXML
    private void onExport() {
        Tab t = reportTabs.getSelectionModel().getSelectedItem();
        List<String> head = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        if (t == tabHours) {
            head.addAll(List.of("Lecturer", "Course unit", "Sessions", "Planned (h)", "Delivered (h)", "Missed", "Replaced"));
            for (HoursRow r : hoursTable.getItems()) rows.add(List.of(r.lecturer(), r.course(), String.valueOf(r.sessions()),
                    String.format("%.2f", r.planned()), String.format("%.2f", r.delivered()), String.valueOf(r.missed()), String.valueOf(r.replaced())));
        } else if (t == tabAbsences) {
            head.addAll(List.of("Student", "ID", "Course unit", "Sessions", "Absent", "Unjustified", "Rate %"));
            for (AbsenceRow r : absenceTable.getItems()) rows.add(List.of(r.student(), r.code(), r.course(), String.valueOf(r.sessions()),
                    String.valueOf(r.absent()), String.valueOf(r.unjustified()), String.format("%.1f", r.rate())));
        } else {
            head.add("Alert");
            for (String a : alertList.getItems()) rows.add(List.of(a));
        }
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("report-" + LocalDate.now() + ".csv");
        File f = fc.showSaveDialog(reportsRoot.getScene().getWindow());
        if (f == null) return;
        try { Csv.write(f.toPath(), head, rows); Fx.info("Exported to " + f.getName()); } catch (Exception e) { Fx.error(Fx.msg(e)); }
    }
}
