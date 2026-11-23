package app.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDateTime;

/** One line of the live attendance table. */
public final class RosterRow {
    public final int studentId;
    public final String code;
    public final String name;
    public final ObjectProperty<LocalDateTime> time = new SimpleObjectProperty<>();
    /** PRESENT, LATE or ABSENT. */
    public final StringProperty status = new SimpleStringProperty("ABSENT");
    /** VERIFIED, TO_REVIEW, NOT_VERIFIED or empty. */
    public final StringProperty wifi = new SimpleStringProperty("");
    public final StringProperty mode = new SimpleStringProperty("");
    public final BooleanProperty provisional = new SimpleBooleanProperty(false);
    public final BooleanProperty recorded = new SimpleBooleanProperty(false);

    public RosterRow(int studentId, String code, String name) {
        this.studentId = studentId;
        this.code = code;
        this.name = name;
    }
}
