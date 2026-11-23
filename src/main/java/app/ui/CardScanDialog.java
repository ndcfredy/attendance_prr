package app.ui;

import app.core.ThemeManager;
import app.service.WebcamScanner;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Optional;

/** "Scan your card" dialog: webcam, 2D scanner (keyboard input) or pasted text. Returns the raw QR text. */
public final class CardScanDialog {
    private CardScanDialog() { }

    public static Optional<String> prompt(String title, String message) {
        Dialog<String> d = new Dialog<>();
        d.setTitle(title);
        d.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        ThemeManager.style(d.getDialogPane());

        Label msg = new Label(message);
        msg.setWrapText(true);
        msg.setMaxWidth(360);
        ImageView view = new ImageView();
        view.setFitWidth(360);
        view.setFitHeight(270);
        view.setPreserveRatio(true);
        StackPane box = new StackPane(view);
        box.getStyleClass().add("scan-area");
        box.setPrefSize(360, 270);
        TextField input = new TextField();
        input.setPromptText("Scan with the 2D scanner (or paste the card text) then press Enter");
        ToggleButton cam = new ToggleButton("Use camera");
        cam.getStyleClass().add("btn-outline");
        HBox row = new HBox(8, input, cam);
        HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
        VBox content = new VBox(12, msg, box, row);
        content.setPadding(new Insets(16));
        d.getDialogPane().setContent(content);

        input.setOnAction(e -> {
            String t = input.getText().trim();
            if (!t.isEmpty()) { d.setResult(t); d.close(); }
        });
        cam.selectedProperty().addListener((o, was, on) -> {
            if (on) {
                String err = WebcamScanner.shared().start(view::setImage, code -> { d.setResult(code); d.close(); });
                if (err != null) { msg.setText(err); cam.setSelected(false); }
            } else {
                WebcamScanner.shared().stop();
            }
        });
        d.setOnHidden(e -> WebcamScanner.shared().stop());
        Platform.runLater(input::requestFocus);
        return d.showAndWait().filter(s -> !s.isBlank());
    }
}
