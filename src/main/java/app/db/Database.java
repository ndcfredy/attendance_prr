package app.db;

import app.core.Config;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** JDBC access. One short-lived connection per operation (fine on a LAN, and survives network drops). */
public final class Database {
    public interface Work<T> { T run(Connection c) throws SQLException; }

    public interface VoidWork { void run(Connection c) throws SQLException; }

    private Database() { }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(Config.get("db.url"), Config.get("db.user"), Config.get("db.password"));
    }

    public static <T> T call(Work<T> w) throws SQLException {
        try (Connection c = open()) { return w.run(c); }
    }

    public static void exec(VoidWork w) throws SQLException {
        try (Connection c = open()) { w.run(c); }
    }

    public static boolean ping() {
        try (Connection c = open()) { return c.isValid(2); } catch (SQLException e) { return false; }
    }

    /** True when the failure is a connection problem (so the operation should be queued, not reported as a bug). */
    public static boolean isConnectionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException) return true;
            if (t instanceof SQLException s) {
                String st = s.getSQLState();
                if (st != null && st.startsWith("08")) return true;
                String m = String.valueOf(s.getMessage()).toLowerCase();
                if (m.contains("communications link") || m.contains("connection refused") || m.contains("could not connect")
                        || m.contains("connect timed out") || m.contains("unable to connect")) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    public static LocalDateTime dt(ResultSet rs, String col) throws SQLException { return rs.getObject(col, LocalDateTime.class); }

    public static LocalDate d(ResultSet rs, String col) throws SQLException { return rs.getObject(col, LocalDate.class); }

    public static LocalTime t(ResultSet rs, String col) throws SQLException { return rs.getObject(col, LocalTime.class); }
}
