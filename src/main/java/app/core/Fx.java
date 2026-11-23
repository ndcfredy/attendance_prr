package app.core;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Small helpers: FXML loading, background work, dialogs. */
public final class Fx {
    public record Loaded<T>(Parent root, T controller) { }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "app-worker");
        t.setDaemon(true);
        return t;
    });
    public static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Fx() { }

    public static <T> Loaded<T> load(String name) {
        try {
            FXMLLoader l = new FXMLLoader(Fx.class.getResource("/com/mycompany/attendance_prr/" + name + ".fxml"));
            Parent p = l.load();
            return new Loaded<>(p, l.getController());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load view " + name + ": " + e.getMessage(), e);
        }
    }

    /** Runs work off the UI thread, then calls ok / err on the UI thread. */
    public static <T> void async(Callable<T> work, Consumer<T> ok, Consumer<Throwable> err) {
        POOL.submit(() -> {
            try {
                T r = work.call();
                Platform.runLater(() -> { if (ok != null) ok.accept(r); });
            } catch (Throwable e) {
                Platform.runLater(() -> { if (err != null) err.accept(e); else error(msg(e)); });
            }
        });
    }

    public static void background(Runnable r) { POOL.submit(r); }

    public static String msg(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static Alert alert(Alert.AlertType type, String text, ButtonType... buttons) {
        Alert a = buttons.length == 0 ? new Alert(type, text) : new Alert(type, text, buttons);
        a.setHeaderText(null);
        ThemeManager.style(a.getDialogPane());
        return a;
    }

    public static void info(String text) { alert(Alert.AlertType.INFORMATION, text).showAndWait(); }

    public static void error(String text) { alert(Alert.AlertType.ERROR, text).showAndWait(); }

    public static boolean confirm(String text) {
        return alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO)
                .showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    public static Optional<String> input(String title, String prompt) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(prompt);
        ThemeManager.style(d.getDialogPane());
        return d.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    public static String hm(LocalDateTime t) { return t == null ? "–" : t.format(HM); }
}
