package app.ui;

import app.core.AppContext;
import app.core.Fx;
import app.db.AdminDao;
import app.db.StudentDao;
import app.model.Models.Group;
import app.model.Models.HistoryRow;
import app.model.Models.Role;
import app.model.Models.Student;
import app.security.CardSigner;
import app.service.CardImage;
import javafx.beans.property.SimpleStringProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Student register: search, edit (registry / admin), photo, signed card, history and absence rate. */
public class StudentsController {
    @FXML private HBox studentsRoot;
    @FXML private ChoiceBox<Group> groupFilter;
    @FXML private TextField searchField;
    @FXML private TableView<Student> studentTable;
    @FXML private TableColumn<Student, String> colCode;
    @FXML private TableColumn<Student, String> colName;
    @FXML private TableColumn<Student, String> colGroup;
    @FXML private TableColumn<Student, String> colRep;
    @FXML private TableColumn<Student, String> colCard;
    @FXML private TableColumn<Student, String> colPhone;
    @FXML private VBox formPane;
    @FXML private SVGPath photoPlaceholder;
    @FXML private ImageView photoView;
    @FXML private Button btnChoosePhoto;
    @FXML private Label phoneLabel;
    @FXML private Button btnResetPhone;
    @FXML private TextField codeField;
    @FXML private TextField nameField;
    @FXML private ChoiceBox<Group> groupCombo;
    @FXML private CheckBox repCheck;
    @FXML private Button btnNew;
    @FXML private Button btnSave;
    @FXML private Button btnDeactivate;
    @FXML private Button btnExportCard;
    @FXML private Button btnRevokeCard;
    @FXML private Button btnCopyCard;
    @FXML private Label absenceLabel;
    @FXML private TableView<HistoryRow> historyTable;
    @FXML private TableColumn<HistoryRow, String> hColCourse;
    @FXML private TableColumn<HistoryRow, String> hColSessions;
    @FXML private TableColumn<HistoryRow, String> hColPresent;
    @FXML private TableColumn<HistoryRow, String> hColAbsent;
    @FXML private TableColumn<HistoryRow, String> hColRate;

    private static final Group ALL = new Group(0, "All groups", 0);
    private Student selected;
    private byte[] selectedPhoto;

    @FXML
    private void initialize() {
        colCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().code()));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        colGroup.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().groupName()));
        colRep.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().classRep() ? "Yes" : ""));
        colCard.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().cardRevoked() ? "REVOKED" : "ACTIVE"));
        colPhone.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().phone() == null ? "–" : c.getValue().phone()));
        Cells.status(colCard);
        hColCourse.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().course()));
        hColSessions.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().sessions())));
        hColPresent.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().attended())));
        hColAbsent.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().absent())));
        hColRate.setCellValueFactory(c -> new SimpleStringProperty(String.format("%.0f %%", c.getValue().rate())));

        boolean canEdit = AppContext.can(Role.REGISTRY, Role.ADMIN);
        formPane.setDisable(!canEdit);
        searchField.textProperty().addListener((o, a, b) -> reload());
        groupFilter.valueProperty().addListener((o, a, b) -> reload());
        studentTable.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> show(s));
        Fx.async(AdminDao::groups, groups -> {
            groupCombo.getItems().setAll(groups);
            groupFilter.getItems().setAll(ALL);
            groupFilter.getItems().addAll(groups);
            groupFilter.setValue(ALL);
        }, null);
    }

    private void reload() {
        String q = searchField.getText();
        Group g = groupFilter.getValue();
        Integer gid = g == null || g.id() == 0 ? null : g.id();
        Fx.async(() -> StudentDao.search(q, gid), list -> studentTable.getItems().setAll(list), null);
    }

    private void show(Student s) {
        selected = s;
        selectedPhoto = null;
        photoView.setImage(null);
        photoPlaceholder.setVisible(true);
        if (s == null) { absenceLabel.setText("Select a student"); historyTable.getItems().clear(); return; }
        codeField.setText(s.code());
        nameField.setText(s.name());
        groupCombo.getItems().stream().filter(g -> g.id() == s.groupId()).findFirst().ifPresent(groupCombo::setValue);
        repCheck.setSelected(s.classRep());
        phoneLabel.setText("Phone: " + (s.phone() == null ? "–" : s.phone()));
        btnRevokeCard.setText(s.cardRevoked() ? "Restore card" : "Revoke card");
        Fx.async(() -> StudentDao.photo(s.id()), bytes -> {
            if (selected == null || selected.id() != s.id() || bytes == null || bytes.length == 0) return;
            selectedPhoto = bytes;
            photoView.setImage(new Image(new ByteArrayInputStream(bytes)));
            photoPlaceholder.setVisible(false);
        }, e -> { });
        Fx.async(() -> StudentDao.history(s.id()), list -> {
            historyTable.getItems().setAll(list);
            long over = list.stream().filter(h -> h.rate() >= Double.parseDouble(String.valueOf(app.core.Config.getInt("absence.threshold.percent", 20)))).count();
            absenceLabel.setText(list.isEmpty() ? "No delivered session yet" : over > 0 ? "⚠ " + over + " course unit(s) above the absence threshold" : "Absence rate within limits");
        }, null);
    }

    @FXML
    private void onNew() {
        studentTable.getSelectionModel().clearSelection();
        selected = null; selectedPhoto = null;
        codeField.clear(); nameField.clear(); repCheck.setSelected(false);
        photoView.setImage(null); photoPlaceholder.setVisible(true);
        phoneLabel.setText("Phone: –");
        btnRevokeCard.setText("Revoke card");
        codeField.requestFocus();
    }

    @FXML
    private void onSave() {
        String code = codeField.getText().trim(), name = nameField.getText().trim();
        Group g = groupCombo.getValue();
        if (code.isEmpty() || name.isEmpty() || g == null) { Fx.error("Student ID, name and group are required."); return; }
        int id = selected == null ? 0 : selected.id();
        boolean rep = repCheck.isSelected();
        Fx.async(() -> {
            int sid = StudentDao.save(id, code, name, g.id(), rep);
            if (CardSigner.hasPrivateKey()) StudentDao.setSignature(sid, CardSigner.signatureOf("STU", code));
            return sid;
        }, sid -> reload(), e -> Fx.error("Could not save (duplicate ID?): " + Fx.msg(e)));
    }

    @FXML
    private void onDeactivate() {
        if (selected == null || !Fx.confirm("Deactivate " + selected.name() + "? The student disappears from rosters but history is kept.")) return;
        int id = selected.id();
        Fx.async(() -> { StudentDao.deactivate(id); return null; }, x -> { onNew(); reload(); }, null);
    }

    @FXML
    private void onChoosePhoto() {
        if (selected == null) { Fx.info("Save the student first, then add the photo."); return; }
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png"));
        File f = fc.showOpenDialog(studentsRoot.getScene().getWindow());
        if (f == null) return;
        try {
            if (f.length() > 2_000_000) { Fx.error("Photo too large (max 2 MB)."); return; }
            byte[] data = Files.readAllBytes(f.toPath());
            int id = selected.id();
            Fx.async(() -> { StudentDao.setPhoto(id, data); return null; }, x -> {
                selectedPhoto = data;
                photoView.setImage(new Image(new ByteArrayInputStream(data)));
                photoPlaceholder.setVisible(false);
            }, null);
        } catch (Exception e) { Fx.error(Fx.msg(e)); }
    }

    @FXML
    private void onResetPhone() {
        if (selected == null || !Fx.confirm("Unregister the phone of " + selected.name() + "? The next mode B scan registers a new one.")) return;
        int id = selected.id();
        Fx.async(() -> { StudentDao.setPhone(id, null); return null; }, x -> { phoneLabel.setText("Phone: –"); reload(); }, null);
    }

    @FXML
    private void onRevokeCard() {
        if (selected == null) return;
        boolean revoke = !selected.cardRevoked();
        if (!Fx.confirm((revoke ? "Revoke" : "Restore") + " the card of " + selected.name() + "?")) return;
        int id = selected.id();
        String code = selected.code();
        Fx.async(() -> {
            StudentDao.setRevoked(id, revoke);
            AdminDao.audit(AppContext.user.username(), revoke ? "CARD_REVOKED" : "CARD_RESTORED", code);
            return null;
        }, x -> reload(), null);
    }

    @FXML
    private void onExportCard() {
        if (selected == null) { Fx.info("Select a student first."); return; }
        if (!CardSigner.hasPrivateKey()) { Fx.error("No card signing key on this computer. Go to Administration > Cards & keys (registry workstation)."); return; }
        FileChooser fc = new FileChooser();
        fc.setInitialFileName(selected.code() + ".png");
        File f = fc.showSaveDialog(studentsRoot.getScene().getWindow());
        if (f == null) return;
        Student s = selected;
        byte[] photo = selectedPhoto;
        Fx.async(() -> {
            BufferedImage img = CardImage.render("Student card", s.name(), s.code(), "Group " + s.groupName(), photo, CardSigner.payload("STU", s.code()));
            CardImage.save(img, f);
            return null;
        }, x -> Fx.info("Card saved: " + f.getName()), null);
    }

    /** Copies the signed QR text of the card (handy to test with the scanner field when there is no printed card). */
    @FXML
    private void onCopyCard() {
        if (selected == null) { Fx.info("Select a student first."); return; }
        if (!CardSigner.hasPrivateKey()) { Fx.error("No card signing key on this computer."); return; }
        try {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(CardSigner.payload("STU", selected.code()));
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            Fx.info("Card text copied. Paste it in the scan field of the session screen and press Enter.");
        } catch (Exception e) { Fx.error(Fx.msg(e)); }
    }
}
