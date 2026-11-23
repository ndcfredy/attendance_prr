package app.ui;

import app.core.ThemeManager;
import app.service.QrService;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Full-screen window showing the dynamic QR code on the classroom projector (mode B). */
public final class QrProjector {
    private final Stage stage = new Stage();
    private final ImageView view = new ImageView();
    private final Label caption = new Label();

    public QrProjector(String title) {
        view.setPreserveRatio(true);
        caption.getStyleClass().add("kiosk-title");
        VBox box = new VBox(24, caption, view);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("kiosk-root");
        Scene scene = new Scene(box, 900, 900);
        ThemeManager.apply(scene);
        view.fitHeightProperty().bind(scene.heightProperty().multiply(0.72));
        view.fitWidthProperty().bind(scene.heightProperty().multiply(0.72));
        stage.setScene(scene);
        stage.setTitle(title);
        stage.setFullScreen(true);
        stage.setFullScreenExitHint("Press Esc to leave the projection");
    }

    public void show() { stage.show(); }

    public boolean isShowing() { return stage.isShowing(); }

    public void update(String payload, String text) {
        view.setImage(QrService.fxImage(payload, 640));
        caption.setText(text);
    }

    public void close() { stage.close(); }
}
