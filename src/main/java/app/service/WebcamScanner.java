package app.service;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One shared webcam reader: shows the preview and decodes QR codes. The 2D scanner (keyboard input) does not need it. */
public final class WebcamScanner {
    private static final WebcamScanner SHARED = new WebcamScanner();

    private volatile boolean running;
    private Thread thread;
    private Webcam cam;

    private WebcamScanner() { }

    public static WebcamScanner shared() { return SHARED; }

    public boolean isRunning() { return running; }

    /** Starts the camera. Returns null on success or a message explaining why it could not start. */
    public synchronized String start(Consumer<Image> onFrame, Consumer<String> onCode) {
        stop();
        try {
            cam = Webcam.getDefault();
            if (cam == null) return "No webcam found";
            cam.setViewSize(WebcamResolution.VGA.getSize());
            cam.open();
        } catch (Throwable e) {
            cam = null;
            return "Webcam unavailable: " + e.getClass().getSimpleName();
        }
        running = true;
        final Webcam c = cam;
        thread = new Thread(() -> loop(c, onFrame, onCode), "webcam-loop");
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    private void loop(Webcam c, Consumer<Image> onFrame, Consumer<String> onCode) {
        AtomicBoolean busy = new AtomicBoolean(false);
        String last = null;
        long lastAt = 0;
        int n = 0;
        while (running) {
            try {
                BufferedImage img = c.getImage();
                if (img == null) { Thread.sleep(50); continue; }
                if (busy.compareAndSet(false, true)) {
                    Image fx = SwingFXUtils.toFXImage(img, null);
                    Platform.runLater(() -> { onFrame.accept(fx); busy.set(false); });
                }
                if (++n % 2 == 0) {
                    String text = QrService.decode(img);
                    long now = System.currentTimeMillis();
                    if (text != null && (!text.equals(last) || now - lastAt > 3000)) {
                        last = text;
                        lastAt = now;
                        Platform.runLater(() -> onCode.accept(text));
                    }
                }
                Thread.sleep(40);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                running = false;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) { thread.interrupt(); thread = null; }
        if (cam != null) {
            try { cam.close(); } catch (Throwable ignored) { }
            cam = null;
        }
    }
}
