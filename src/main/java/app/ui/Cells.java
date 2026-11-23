package app.ui;

import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;

import java.util.Locale;

/** Coloured cells for the status columns. */
public final class Cells {
    private Cells() { }

    /** Cell text coloured by a CSS class "st-<value>" (PRESENT -> Present in green, etc.). */
    public static <S> void status(TableColumn<S, String> col) {
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeIf(s -> s.startsWith("st-"));
                if (empty || item == null || item.isEmpty()) { setText(null); return; }
                setText(pretty(item));
                getStyleClass().add("st-" + item.toLowerCase(Locale.ROOT));
            }
        });
    }

    public static String pretty(String code) {
        if (code == null || code.isEmpty()) return "–";
        String s = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
