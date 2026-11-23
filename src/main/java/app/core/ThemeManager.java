package app.core;

import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

import java.util.ArrayList;
import java.util.List;

/** Dark / light themes (both red-accented). Base styles in base.css, colours in theme-dark.css / theme-light.css. */
public final class ThemeManager {
    private static boolean dark = !"light".equalsIgnoreCase(Config.get("theme"));
    private static final List<Scene> SCENES = new ArrayList<>();

    private ThemeManager() { }
    
private static String url(String name) {
    String path = "/com/mycompany/attendance_prr/" + name;

    var resource = ThemeManager.class.getResource(path);

    System.out.println("Looking for: " + path);
    System.out.println("Found: " + resource);

    if (resource == null) {
        throw new RuntimeException("Resource not found: " + path);
    }

    return resource.toExternalForm();
}


    private static List<String> sheets() {
        return List.of(url("base.css"), url(dark ? "theme-dark.css" : "theme-light.css"));
    }

    public static void apply(Scene scene) {
        if (!SCENES.contains(scene)) SCENES.add(scene);
        scene.getStylesheets().setAll(sheets());
    }

    public static void style(DialogPane pane) {
        pane.getStylesheets().setAll(sheets());
    }

    public static boolean isDark() { return dark; }

    public static void setDark(boolean value) {
        dark = value;
        Config.set("theme", dark ? "dark" : "light");
        Config.save();
        SCENES.forEach(s -> s.getStylesheets().setAll(sheets()));
    }

    public static void toggle() { setDark(!dark); }
}
