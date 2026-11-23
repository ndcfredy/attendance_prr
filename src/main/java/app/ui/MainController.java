package app.ui;

import app.core.AppContext;
import app.core.Disposable;
import app.core.Fx;
import app.core.ThemeManager;
import app.model.Models.Role;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

import java.util.LinkedHashMap;
import java.util.Map;

/** Window shell: top bar, role-aware sidebar and the content area where the views are loaded. */
public class MainController {
    private static MainController instance;

    @FXML private BorderPane shellRoot;
    @FXML private HBox topBar;
    @FXML private SVGPath appIcon;
    @FXML private Label appTitleLabel;
    @FXML private Circle connectionDot;
    @FXML private Label connectionLabel;
    @FXML private Label wifiLabel;
    @FXML private Label userLabel;
    @FXML private ToggleButton btnTheme;
    @FXML private Button btnLogout;
    @FXML private VBox sidebar;
    @FXML private Button navSession;
    @FXML private Button navTimetable;
    @FXML private Button navStudents;
    @FXML private Button navTeachingLog;
    @FXML private Button navSyllabus;
    @FXML private Button navReports;
    @FXML private Button navJustifications;
    @FXML private Button navAdmin;
    @FXML private Button navKiosk;
    @FXML private Label pendingLabel;
    @FXML private StackPane contentHost;

    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private final Map<String, String> fxml = Map.of("navSession", "session", "navTimetable", "timetable", "navStudents", "students",
            "navTeachingLog", "teachinglog", "navSyllabus", "syllabus", "navReports", "reports", "navJustifications", "justifications",
            "navAdmin", "admin");
    private Disposable current;

    public static MainController get() { return instance; }

    @FXML
    private void initialize() {
        instance = this;
        userLabel.setText(AppContext.user.name() + "  ·  " + AppContext.user.role().name().toLowerCase());
        btnTheme.setSelected(!ThemeManager.isDark());
        btnTheme.setText(ThemeManager.isDark() ? "Light theme" : "Dark theme");
        for (Button b : new Button[]{navSession, navTimetable, navStudents, navTeachingLog, navSyllabus, navReports, navJustifications, navAdmin}) {
            buttons.put(b.getId(), b);
        }
        Role r = AppContext.user.role();
        show(navSession, r == Role.LECTURER || r == Role.ADMIN, true);
        show(navTimetable, r != Role.REGISTRY, true);
        navTimetable.setText(r == Role.LECTURER ? "My timetable" : "Timetable");
        show(navStudents, true, true);
        show(navTeachingLog, r != Role.REGISTRY, true);
        show(navSyllabus, r != Role.REGISTRY, true);
        show(navReports, true, true);
        show(navJustifications, r == Role.REGISTRY || r == Role.ADMIN, true);
        show(navAdmin, r == Role.ADMIN || r == Role.REGISTRY, true);
        show(navKiosk, r == Role.ADMIN || r == Role.REGISTRY, true);

        AppContext.online.addListener((o, a, b) -> refreshStatus());
        AppContext.pending.addListener((o, a, b) -> refreshStatus());
        wifiLabel.textProperty().bind(AppContext.wifiText);
        refreshStatus();

        String first = r == Role.LECTURER ? "navSession" : r == Role.REGISTRY ? "navStudents" : "navTimetable";
        open(first);
    }

    private static void show(Node n, boolean visible, boolean managed) {
        n.setVisible(visible);
        n.setManaged(visible && managed);
    }

    private void refreshStatus() {
        boolean on = AppContext.online.get();
        connectionDot.getStyleClass().remove("online");
        if (on) connectionDot.getStyleClass().add("online");
        connectionLabel.setText(on ? "Connected – synced" : "Offline");
        int n = AppContext.pending.get();
        pendingLabel.setText(n > 0 ? n + " operation(s) waiting to sync" : "");
    }

    @FXML
    private void onNav(ActionEvent e) {
        String id = ((Node) e.getSource()).getId();
        if (id.equals("navKiosk")) {
            if (current != null) current.dispose();
            Nav.showKiosk(null);
            return;
        }
        open(id);
    }

    /** Opens a view by sidebar id (used by other screens too, e.g. "navSession"). */
    public void open(String navId) {
        String name = fxml.get(navId);
        if (name == null) return;
        if (current != null) { current.dispose(); current = null; }
        Fx.Loaded<Object> l = Fx.load(name);
        contentHost.getChildren().setAll(l.root());
        if (l.controller() instanceof Disposable d) current = d;
        buttons.forEach((id, b) -> {
            b.getStyleClass().remove("active");
            if (id.equals(navId)) b.getStyleClass().add("active");
        });
    }

    @FXML
    private void onToggleTheme() {
        ThemeManager.toggle();
        btnTheme.setText(ThemeManager.isDark() ? "Light theme" : "Dark theme");
    }

    @FXML
    private void onLogout() {
        if (current != null) current.dispose();
        Nav.showLogin();
    }
}
