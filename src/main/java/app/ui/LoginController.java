package app.ui;

import app.core.AppContext;
import app.core.Config;
import app.core.Fx;
import app.core.ThemeManager;
import app.db.AdminDao;
import app.db.Database;
import app.db.StaffDao;
import app.model.Models.Room;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

public class LoginController {
    @FXML private StackPane loginRoot;
    @FXML private SVGPath logoIcon;
    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label loginErrorLabel;
    @FXML private Button btnLogin;
    @FXML private ComboBox<Room> kioskRoomCombo;
    @FXML private Button btnKiosk;
    @FXML private Label dbStatusLabel;
    @FXML private ToggleButton themeToggle;

    @FXML
    private void initialize() {
        themeToggle.setSelected(!ThemeManager.isDark());
        themeToggle.setText(ThemeManager.isDark() ? "Light theme" : "Dark theme");
        Fx.async(() -> {
            if (!Database.ping()) return null;
            return AdminDao.rooms();
        }, rooms -> {
            if (rooms == null) {
                dbStatusLabel.setText("Database unreachable – check config.properties and that MySQL (XAMPP) is running");
                dbStatusLabel.getStyleClass().add("error-label");
                return;
            }
            dbStatusLabel.setText("Database connected");
            kioskRoomCombo.getItems().setAll(rooms);
            String saved = Config.get("kiosk.room");
            rooms.stream().filter(r -> r.name().equals(saved)).findFirst().ifPresent(kioskRoomCombo::setValue);
        }, e -> dbStatusLabel.setText("Database error: " + Fx.msg(e)));
        javafx.application.Platform.runLater(usernameField::requestFocus);
    }

    @FXML
    private void onLogin() {
        loginErrorLabel.setText("");
        String u = usernameField.getText().trim(), p = passwordField.getText();
        if (u.isEmpty() || p.isEmpty()) { loginErrorLabel.setText("Enter your username and password."); return; }
        btnLogin.setDisable(true);
        Fx.async(() -> StaffDao.login(u, p), staff -> {
            btnLogin.setDisable(false);
            if (staff == null) { loginErrorLabel.setText("Wrong username or password."); return; }
            AppContext.user = staff;
            Nav.showMain();
        }, e -> {
            btnLogin.setDisable(false);
            loginErrorLabel.setText("Cannot reach the database: " + Fx.msg(e));
        });
    }

    @FXML
    private void onOpenKiosk() {
        Room r = kioskRoomCombo.getValue();
        if (r == null) { loginErrorLabel.setText("Choose the room of this kiosk first."); return; }
        Config.set("kiosk.room", r.name());
        Config.save();
        Nav.showKiosk(r);
    }

    @FXML
    private void onToggleTheme() {
        ThemeManager.toggle();
        themeToggle.setText(ThemeManager.isDark() ? "Light theme" : "Dark theme");
    }
}
