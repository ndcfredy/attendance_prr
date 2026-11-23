package app.ui;

import app.core.AppContext;
import app.core.Config;
import app.core.Fx;
import app.db.AdminDao;
import app.db.Database;
import app.db.StaffDao;
import app.db.StudentDao;
import app.model.Models.AccessPoint;
import app.model.Models.Building;
import app.model.Models.Role;
import app.model.Models.Staff;
import app.model.Models.Student;
import app.security.CardSigner;
import app.service.CardImage;
import app.service.WifiService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;

/** Administration: access-point register, signing keys and cards, staff accounts, settings. */
public class AdminController {
    @FXML private VBox adminRoot;
    @FXML private TabPane adminTabs;
    @FXML private Tab tabAp;
    @FXML private Tab tabCards;
    @FXML private Tab tabStaff;
    @FXML private Tab tabSettings;
    // access points
    @FXML private TableView<AccessPoint> apTable;
    @FXML private TableColumn<AccessPoint, String> colApBssid;
    @FXML private TableColumn<AccessPoint, String> colApBuilding;
    @FXML private TableColumn<AccessPoint, String> colApZone;
    @FXML private TableColumn<AccessPoint, String> colApStatus;
    @FXML private Button btnApRetire;
    @FXML private TextField apBssidField;
    @FXML private ChoiceBox<Building> apBuildingChoice;
    @FXML private TextField apZoneField;
    @FXML private Button btnApAdd;
    @FXML private Label apCurrentLabel;
    @FXML private Button btnApDetect;
    // cards
    @FXML private Label keyStatusLabel;
    @FXML private Button btnGenKeys;
    @FXML private Button btnSignAll;
    @FXML private Button btnExportAllCards;
    @FXML private Button btnExportStaffCards;
    @FXML private ProgressBar cardProgress;
    @FXML private TextArea cardLog;
    // staff
    @FXML private TableView<Staff> staffTable;
    @FXML private TableColumn<Staff, String> colSCode;
    @FXML private TableColumn<Staff, String> colSName;
    @FXML private TableColumn<Staff, String> colSUser;
    @FXML private TableColumn<Staff, String> colSRole;
    @FXML private TableColumn<Staff, String> colSEmp;
    @FXML private TextField sCodeField;
    @FXML private TextField sNameField;
    @FXML private TextField sUserField;
    @FXML private PasswordField sPassField;
    @FXML private ChoiceBox<Role> sRoleChoice;
    @FXML private ChoiceBox<String> sEmpChoice;
    @FXML private TextField sDeptField;
    @FXML private Button btnSNew;
    @FXML private Button btnSSave;
    @FXML private Button btnSCard;
    @FXML private Button btnSCopy;
    // settings
    @FXML private Spinner<Integer> lateSpinner;
    @FXML private Spinner<Integer> absSpinner;
    @FXML private TextField simBssidField;
    @FXML private TextField dbUrlField;
    @FXML private TextField dbUserField;
    @FXML private PasswordField dbPassField;
    @FXML private Button btnTestDb;
    @FXML private Button btnSaveSettings;
    @FXML private Label settingsInfoLabel;

    private Staff selectedStaff;

    @FXML
    private void initialize() {
        boolean admin = AppContext.can(Role.ADMIN);
        tabStaff.setDisable(!admin);
        tabSettings.setDisable(!admin);
        // access points
        colApBssid.setCellValueFactory(c -> s(c.getValue().bssid()));
        colApBuilding.setCellValueFactory(c -> s(c.getValue().building()));
        colApZone.setCellValueFactory(c -> s(c.getValue().zone() == null ? "" : c.getValue().zone()));
        colApStatus.setCellValueFactory(c -> s(c.getValue().status()));
        Cells.status(colApStatus);
        reloadAp();
        Fx.async(AdminDao::buildings, l -> { apBuildingChoice.getItems().setAll(l); if (!l.isEmpty()) apBuildingChoice.setValue(l.get(0)); }, null);
        // cards
        refreshKeyStatus();
        // staff
        colSCode.setCellValueFactory(c -> s(c.getValue().code()));
        colSName.setCellValueFactory(c -> s(c.getValue().name()));
        colSUser.setCellValueFactory(c -> s(c.getValue().username()));
        colSRole.setCellValueFactory(c -> s(c.getValue().role().name()));
        colSEmp.setCellValueFactory(c -> s(c.getValue().employment()));
        sRoleChoice.getItems().setAll(Role.values());
        sRoleChoice.setValue(Role.LECTURER);
        sEmpChoice.getItems().setAll("PERMANENT", "PART_TIME");
        sEmpChoice.setValue("PERMANENT");
        staffTable.getSelectionModel().selectedItemProperty().addListener((o, a, st) -> showStaff(st));
        if (admin) reloadStaff();
        // settings
        lateSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 120, Config.getInt("late.threshold.minutes", 10)));
        absSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, Config.getInt("absence.threshold.percent", 20)));
        simBssidField.setText(Config.get("wifi.simulate.bssid"));
        dbUrlField.setText(Config.get("db.url"));
        dbUserField.setText(Config.get("db.user"));
        dbPassField.setText(Config.get("db.password"));
    }

    private static SimpleStringProperty s(String v) { return new SimpleStringProperty(v); }

    // ---- access points ---------------------------------------------------------------------------------------

    private void reloadAp() { Fx.async(AdminDao::accessPoints, l -> apTable.getItems().setAll(l), null); }

    @FXML
    private void onApAdd() {
        String b = apBssidField.getText().trim().toLowerCase();
        Building bld = apBuildingChoice.getValue();
        if (!b.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}") || bld == null) { Fx.error("Enter a BSSID like aa:bb:cc:dd:ee:ff and choose the building."); return; }
        String zone = apZoneField.getText().trim();
        Fx.async(() -> { AdminDao.addAccessPoint(b, bld.id(), zone); return null; }, x -> { apBssidField.clear(); apZoneField.clear(); reloadAp(); },
                e -> Fx.error("Could not register (already registered?): " + Fx.msg(e)));
    }

    @FXML
    private void onApRetire() {
        AccessPoint ap = apTable.getSelectionModel().getSelectedItem();
        if (ap == null) return;
        String next = "ACTIVE".equals(ap.status()) ? "RETIRED" : "ACTIVE";
        Fx.async(() -> { AdminDao.setAccessPointStatus(ap.id(), next); return null; }, x -> reloadAp(), null);
    }

    @FXML
    private void onApDetect() {
        Fx.async(WifiService::currentBssid, b -> {
            if (b.isEmpty()) { apCurrentLabel.setText("Current access point: none detected (not on Wi-Fi, or not Windows)"); return; }
            apCurrentLabel.setText("Current access point: " + b);
            apBssidField.setText(b);
        }, null);
    }

    // ---- cards and keys --------------------------------------------------------------------------------------

    private void refreshKeyStatus() {
        boolean priv = CardSigner.hasPrivateKey();
        boolean pub = CardSigner.publicKey() != null;
        keyStatusLabel.setText(priv ? "Signing key present on this computer – this is the registry workstation. Keep ~/.attendance/card_private.key safe."
                : pub ? "Public key available (verification works). No private key here: cards can only be issued on the registry workstation."
                : "No signing key yet. Click \"Generate signing keys\" once, on the registry workstation.");
        btnSignAll.setDisable(!priv);
        btnExportAllCards.setDisable(!priv);
        btnExportStaffCards.setDisable(!priv);
    }

    private void log(String line) { Platform.runLater(() -> cardLog.appendText(line + "\n")); }

    @FXML
    private void onGenKeys() {
        if (CardSigner.hasPrivateKey() && !Fx.confirm("A key already exists. Generating a new one INVALIDATES every card issued so far. Continue?")) return;
        Fx.async(() -> { CardSigner.generateKeys(); return null; }, x -> { refreshKeyStatus(); log("New Ed25519 key pair generated. Now click \"Sign all cards\"."); },
                e -> Fx.error("Key generation failed: " + Fx.msg(e)));
    }

    @FXML
    private void onSignAll() {
        Fx.async(() -> {
            int n = 0;
            for (Student s : StudentDao.search(null, null)) { StudentDao.setSignature(s.id(), CardSigner.signatureOf("STU", s.code())); n++; }
            for (Staff s : StaffDao.all()) { StaffDao.setSignature(s.id(), CardSigner.signatureOf("STF", s.code())); n++; }
            return n;
        }, n -> log(n + " card signatures stored."), e -> Fx.error(Fx.msg(e)));
    }

    @FXML
    private void onExportAllCards() {
        File dir = new DirectoryChooser().showDialog(adminRoot.getScene().getWindow());
        if (dir == null) return;
        Fx.background(() -> {
            try {
                List<Student> all = StudentDao.search(null, null);
                int i = 0;
                for (Student s : all) {
                    byte[] photo = StudentDao.photo(s.id());
                    CardImage.save(CardImage.render("Student card", s.name(), s.code(), "Group " + s.groupName(), photo, CardSigner.payload("STU", s.code())),
                            new File(dir, s.code() + ".png"));
                    double p = ++i / (double) all.size();
                    Platform.runLater(() -> cardProgress.setProgress(p));
                }
                log(all.size() + " student cards written to " + dir);
            } catch (Exception e) { log("Error: " + Fx.msg(e)); }
        });
    }

    @FXML
    private void onExportStaffCards() {
        File dir = new DirectoryChooser().showDialog(adminRoot.getScene().getWindow());
        if (dir == null) return;
        Fx.background(() -> {
            try {
                List<Staff> all = StaffDao.all();
                for (Staff s : all) {
                    CardImage.save(CardImage.render("Staff card", s.name(), s.code(), s.role().name().toLowerCase(), null, CardSigner.payload("STF", s.code())),
                            new File(dir, s.code() + ".png"));
                }
                log(all.size() + " staff cards written to " + dir);
            } catch (Exception e) { log("Error: " + Fx.msg(e)); }
        });
    }

    // ---- staff -----------------------------------------------------------------------------------------------

    private void reloadStaff() { Fx.async(StaffDao::all, l -> staffTable.getItems().setAll(l), null); }

    private void showStaff(Staff st) {
        selectedStaff = st;
        if (st == null) return;
        sCodeField.setText(st.code()); sNameField.setText(st.name()); sUserField.setText(st.username());
        sPassField.clear(); sRoleChoice.setValue(st.role()); sEmpChoice.setValue(st.employment());
        sDeptField.setText(st.department() == null ? "" : st.department());
    }

    @FXML
    private void onStaffNew() {
        staffTable.getSelectionModel().clearSelection();
        selectedStaff = null;
        sCodeField.clear(); sNameField.clear(); sUserField.clear(); sPassField.clear(); sDeptField.clear();
    }

    @FXML
    private void onStaffSave() {
        String code = sCodeField.getText().trim(), name = sNameField.getText().trim(), user = sUserField.getText().trim();
        if (code.isEmpty() || name.isEmpty() || user.isEmpty()) { Fx.error("Staff ID, name and username are required."); return; }
        if (selectedStaff == null && sPassField.getText().isBlank()) { Fx.error("Give a password for a new account."); return; }
        int id = selectedStaff == null ? 0 : selectedStaff.id();
        Fx.async(() -> {
            int sid = StaffDao.save(id, code, name, user, sPassField.getText(), sRoleChoice.getValue(), sEmpChoice.getValue(), sDeptField.getText().trim());
            if (CardSigner.hasPrivateKey()) StaffDao.setSignature(sid, CardSigner.signatureOf("STF", code));
            return sid;
        }, sid -> { reloadStaff(); sPassField.clear(); }, e -> Fx.error("Could not save (duplicate ID or username?): " + Fx.msg(e)));
    }

    @FXML
    private void onStaffCard() {
        if (selectedStaff == null) { Fx.info("Select a staff member first."); return; }
        if (!CardSigner.hasPrivateKey()) { Fx.error("No signing key on this computer."); return; }
        FileChooser fc = new FileChooser();
        fc.setInitialFileName(selectedStaff.code() + ".png");
        File f = fc.showSaveDialog(adminRoot.getScene().getWindow());
        if (f == null) return;
        Staff s = selectedStaff;
        Fx.async(() -> {
            CardImage.save(CardImage.render("Staff card", s.name(), s.code(), s.role().name().toLowerCase(), null, CardSigner.payload("STF", s.code())), f);
            return null;
        }, x -> Fx.info("Card saved: " + f.getName()), null);
    }

    @FXML
    private void onStaffCopy() {
        if (selectedStaff == null) { Fx.info("Select a staff member first."); return; }
        if (!CardSigner.hasPrivateKey()) { Fx.error("No signing key on this computer."); return; }
        try {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(CardSigner.payload("STF", selectedStaff.code()));
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            Fx.info("Staff card text copied. Paste it in the check-in dialog and press Enter.");
        } catch (Exception e) { Fx.error(Fx.msg(e)); }
    }

    // ---- settings --------------------------------------------------------------------------------------------

    private void applyDbFields() {
        Config.set("db.url", dbUrlField.getText().trim());
        Config.set("db.user", dbUserField.getText().trim());
        Config.set("db.password", dbPassField.getText());
    }

    @FXML
    private void onTestDb() {
        applyDbFields();
        Fx.async(Database::ping, ok -> settingsInfoLabel.setText(ok ? "Connection OK." : "Connection failed – check the URL, user, password and that MySQL is running."), null);
    }

    @FXML
    private void onSaveSettings() {
        applyDbFields();
        Config.set("late.threshold.minutes", String.valueOf(lateSpinner.getValue()));
        Config.set("absence.threshold.percent", String.valueOf(absSpinner.getValue()));
        Config.set("wifi.simulate.bssid", simBssidField.getText().trim());
        Config.save();
        settingsInfoLabel.setText("Saved to config.properties.");
    }
}
