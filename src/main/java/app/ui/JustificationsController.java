package app.ui;

import app.core.AppContext;
import app.core.Fx;
import app.db.ReportDao;
import app.db.ReportDao.AbsentSession;
import app.db.StudentDao;
import app.model.Models.JustifRow;
import app.model.Models.Student;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;

/** Registry: absence justifications (medical certificates scanned into the system), accept / reject. */
public class JustificationsController {
    @FXML private VBox justifRoot;
    @FXML private ChoiceBox<String> statusFilter;
    @FXML private Button btnNewJustif;
    @FXML private TableView<JustifRow> justifTable;
    @FXML private TableColumn<JustifRow, String> colJStudent;
    @FXML private TableColumn<JustifRow, String> colJCourse;
    @FXML private TableColumn<JustifRow, String> colJDate;
    @FXML private TableColumn<JustifRow, String> colJReason;
    @FXML private TableColumn<JustifRow, String> colJDoc;
    @FXML private TableColumn<JustifRow, String> colJStatus;
    @FXML private Button btnOpenDoc;
    @FXML private Button btnRejectJ;
    @FXML private Button btnAccept;

    @FXML
    private void initialize() {
        colJStudent.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().student()));
        colJCourse.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().course()));
        colJDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().date().format(Fx.DMY)));
        colJReason.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().reason()));
        colJDoc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().doc() == null ? "–" : new File(c.getValue().doc()).getName()));
        colJStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        Cells.status(colJStatus);
        statusFilter.getItems().setAll("PENDING", "ACCEPTED", "REJECTED", "ALL");
        statusFilter.setValue("PENDING");
        statusFilter.valueProperty().addListener((o, a, b) -> reload());
        reload();
    }

    private void reload() {
        String f = statusFilter.getValue();
        Fx.async(() -> ReportDao.justifications("ALL".equals(f) ? null : f), l -> justifTable.getItems().setAll(l), null);
    }

    @FXML
    private void onNewJustif() {
        Fx.async(() -> StudentDao.search(null, null), students -> {
            var form = Forms.create("New justification", "Submit");
            ChoiceBox<Student> student = form.add("Student", new ChoiceBox<>());
            student.getItems().setAll(students);
            ChoiceBox<AbsentSession> session = form.add("Absence", new ChoiceBox<>());
            student.valueProperty().addListener((o, a, s) -> {
                session.getItems().clear();
                if (s != null) Fx.async(() -> ReportDao.absentSessions(s.id()), l -> session.getItems().setAll(l), null);
            });
            TextArea reason = form.add("Reason", new TextArea());
            reason.setPrefRowCount(3);
            Label file = new Label("No file");
            Button choose = new Button("Attach scanned certificate…");
            choose.getStyleClass().add("btn-outline");
            final String[] path = {null};
            choose.setOnAction(e -> {
                File f = new FileChooser().showOpenDialog(justifRoot.getScene().getWindow());
                if (f != null) { path[0] = f.getAbsolutePath(); file.setText(f.getName()); }
            });
            form.add("Document", new javafx.scene.layout.HBox(10, choose, file));
            form.validate(() -> student.getValue() == null || session.getValue() == null || reason.getText().isBlank()
                    ? "Choose the student, the absence and give a reason." : null);
            if (!form.show()) return;
            Fx.async(() -> { ReportDao.addJustification(student.getValue().id(), session.getValue().sessionId(), reason.getText().trim(), path[0]); return null; },
                    x -> reload(), null);
        }, null);
    }

    @FXML
    private void onAccept() { decide(true); }

    @FXML
    private void onReject() { decide(false); }

    private void decide(boolean accept) {
        JustifRow r = justifTable.getSelectionModel().getSelectedItem();
        if (r == null || !"PENDING".equals(r.status())) { Fx.info("Select a pending justification."); return; }
        Fx.async(() -> { ReportDao.decide(r.id(), accept, AppContext.user.id()); return null; }, x -> reload(), null);
    }

    @FXML
    private void onOpenDoc() {
        JustifRow r = justifTable.getSelectionModel().getSelectedItem();
        if (r == null || r.doc() == null) { Fx.info("No document attached."); return; }
        try { Desktop.getDesktop().open(new File(r.doc())); } catch (Exception e) { Fx.error("Cannot open the file: " + Fx.msg(e)); }
    }
}
