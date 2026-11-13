package app.movy.shell;

/** Source health. Status is a hint only; it never switches anything by itself. */
public interface HealthChecker {
    enum Status { UNKNOWN, UP, SLOW, DOWN }

    /** Notified on the main thread after a check round finished. */
    interface Listener {
        void onHealthChanged();
    }

    /** Last known status for a source id. */
    Status status(String sourceId);

    /** Starts a background refresh when the last check is over 60 s old; must not block the caller. */
    void refresh();

    /** Starts a background refresh now, whatever the age of the last one. */
    void refreshNow();

    /** First UP source other than {@code excludeId} in display order, else the first SLOW one; may be null. */
    Source suggestion(String excludeId);

    void addListener(Listener l);

    /** Default: everything UNKNOWN. */
    HealthChecker NONE = new HealthChecker() {
        @Override public Status status(String sourceId) { return Status.UNKNOWN; }
        @Override public void refresh() { }
        @Override public void refreshNow() { }
        @Override public Source suggestion(String excludeId) { return null; }
        @Override public void addListener(Listener l) { }
    };
}
