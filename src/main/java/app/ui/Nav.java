package app.ui;

import app.Main;
import app.core.AppContext;
import app.core.Fx;
import app.model.Models.Room;
import javafx.scene.input.KeyCombination;

/** Switches the whole window between login, main shell and kiosk. */
public final class Nav {
    private Nav() { }

    public static void showLogin() {
        AppContext.user = null;
        Main.stage.setFullScreen(false);
        Main.scene.setRoot(Fx.<LoginController>load("login").root());
    }

    public static void showMain() {
        Main.stage.setFullScreen(false);
        Main.scene.setRoot(Fx.<MainController>load("main").root());
    }

    public static void showKiosk(Room room) {
        Fx.Loaded<KioskController> l = Fx.load("kiosk");
        l.controller().setRoom(room);
        Main.scene.setRoot(l.root());
        Main.stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        Main.stage.setFullScreen(true);
    }
}
