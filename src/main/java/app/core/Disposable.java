package app.core;

/** Implemented by view controllers that must release resources (camera, timers) when the user leaves the view. */
public interface Disposable {
    void dispose();
}
