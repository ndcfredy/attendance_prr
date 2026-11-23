package app.core;

import app.model.Models.Role;
import app.model.Models.Staff;
import app.service.OfflineQueue;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** Global state shared by every screen. */
public final class AppContext {
    public static Staff user;
    public static final BooleanProperty online = new SimpleBooleanProperty(false);
    public static final IntegerProperty pending = new SimpleIntegerProperty(0);
    public static final StringProperty wifiText = new SimpleStringProperty("No campus Wi-Fi");
    public static final OfflineQueue queue = new OfflineQueue();

    private AppContext() { }

    public static boolean can(Role... roles) {
        if (user == null) return false;
        for (Role r : roles) if (user.role() == r) return true;
        return false;
    }

    public static int lateThresholdMinutes() { return Config.getInt("late.threshold.minutes", 10); }
}
