package app;

import app.core.ThemeManager;
import app.service.SyncService;
import app.service.WebcamScanner;
import app.ui.Nav;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Main extends Application {
    public static Stage stage;
    public static Scene scene;

    @Override
    public void start(Stage primary) {
        stage = primary;
        scene = new Scene(new StackPane(), 1360, 840);
        ThemeManager.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Attendance & Teaching Log");
        stage.setMinWidth(1150);
        stage.setMinHeight(720);
        SyncService.start();
        Nav.showLogin();
        stage.show();
    }

    @Override
    public void stop() {
        WebcamScanner.shared().stop();
        SyncService.stop();
    }

    public static void main(String[] args) { launch(args); }
}
