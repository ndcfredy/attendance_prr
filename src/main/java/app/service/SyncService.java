package app.service;

import app.core.AppContext;
import app.db.Database;
import app.db.SessionDao;
import app.model.Models.LogEntry;
import app.service.OfflineQueue.Op;
import javafx.application.Platform;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Background loop: tests the connection, shows the campus Wi-Fi, and writes queued operations to MySQL when it returns. */
public final class SyncService {
    private static ScheduledExecutorService ex;
    private static final Object FLUSH_LOCK = new Object();

    private SyncService() { }

    public static synchronized void start() {
        if (ex != null) return;
        ex = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sync");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(SyncService::tick, 0, 5, TimeUnit.SECONDS);
        ex.scheduleWithFixedDelay(SyncService::wifiTick, 1, 10, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (ex != null) { ex.shutdownNow(); ex = null; }
    }

    private static void wifiTick() {
        try {
            String b = WifiService.currentBssid();
            String text = b.isEmpty() ? "No campus Wi-Fi" : WifiService.describe(b);
            Platform.runLater(() -> AppContext.wifiText.set(text));
        } catch (Throwable ignored) { }
    }

    private static void tick() {
        try {
            boolean ok = Database.ping();
            if (ok && AppContext.queue.size() > 0) flush();
            int n = AppContext.queue.size();
            Platform.runLater(() -> { AppContext.online.set(ok); AppContext.pending.set(n); });
        } catch (Throwable ignored) { }
    }

    /** Called after an operation was queued so the counter updates immediately. */
    public static void refreshPending() {
        int n = AppContext.queue.size();
        Platform.runLater(() -> AppContext.pending.set(n));
    }

    private static void flush() {
        synchronized (FLUSH_LOCK) {
            List<Op> ops = AppContext.queue.snapshot();
            int done = 0;
            for (Op op : ops) {
                try {
                    apply(op);
                    done++;
                } catch (SQLException e) {
                    if (Database.isConnectionError(e)) break;
                    done++; // data error: skip this operation instead of blocking the queue forever
                }
            }
            if (done > 0) AppContext.queue.removeFirst(done);
        }
    }

    private static void apply(Op op) throws SQLException {
        switch (op.type()) {
            case "SCAN" -> SessionDao.insertAttendance(op.getInt("sessionId"), op.getInt("studentId"), LocalDateTime.parse(op.get("time")),
                    op.get("mode"), op.get("bssid"), op.get("decision"), op.get("status"), Boolean.parseBoolean(op.get("provisional")));
            case "CHECKOUT" -> SessionDao.checkOut(op.getInt("sessionId"), op.getInt("groupId"), LocalDateTime.parse(op.get("end")));
            case "LOG" -> {
                LogEntry e = new LogEntry();
                e.sessionId = op.getInt("sessionId");
                e.type = op.get("type"); e.details = op.get("details"); e.activities = op.get("activities");
                e.assignments = op.get("assignments"); e.resources = op.get("resources"); e.remarks = op.get("remarks");
                e.due = op.get("due").isEmpty() ? null : LocalDate.parse(op.get("due"));
                Set<Integer> topics = new LinkedHashSet<>();
                for (String t : op.get("topics").split(",")) if (!t.isBlank()) topics.add(Integer.parseInt(t.trim()));
                SessionDao.saveLog(e, topics);
            }
            default -> { }
        }
    }
}
