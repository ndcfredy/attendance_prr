package app.ui;

import app.core.Fx;
import app.core.ThemeManager;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.function.Supplier;

/** Tiny builder for form dialogs (label + control rows, optional validation). */
public final class Forms {
    private Forms() { }

    public static Form create(String title, String okText) { return new Form(title, okText); }

    public static final class Form {
        private final Dialog<ButtonType> dialog = new Dialog<>();
        private final GridPane grid = new GridPane();
        private final ButtonType ok;
        private int row;

        private Form(String title, String okText) {
            dialog.setTitle(title);
            ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
            ThemeManager.style(dialog.getDialogPane());
            grid.setHgap(12);
            grid.setVgap(10);
            grid.setPadding(new Insets(18));
            dialog.getDialogPane().setContent(grid);
        }

        public <T extends Node> T add(String label, T node) {
            grid.add(new Label(label), 0, row);
            grid.add(node, 1, row++);
            GridPane.setHgrow(node, Priority.ALWAYS);
            return node;
        }

        public Form note(String text) {
            Label l = new Label(text);
            l.setWrapText(true);
            l.setMaxWidth(420);
            l.getStyleClass().add("muted");
            grid.add(l, 0, row++, 2, 1);
            return this;
        }

        /** The validator returns an error message (dialog stays open) or null when the form is valid. */
        public Form validate(Supplier<String> validator) {
            dialog.getDialogPane().lookupButton(ok).addEventFilter(ActionEvent.ACTION, ev -> {
                String err = validator.get();
                if (err != null) { ev.consume(); Fx.error(err); }
            });
            return this;
        }

        public boolean show() {
            return dialog.showAndWait().filter(b -> b == ok).isPresent();
        }
    }
}
