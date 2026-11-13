package app.movy.shell;

/** A one-time banner from remote config (shown by the Island). Immutable. */
public final class Announcement {
    public final String id;
    public final String text;
    /** "info", "warn" or "critical". */
    public final String level;
    /** Optional link target; may be empty. Never displayed. */
    public final String url;

    public Announcement(String id, String text, String level, String url) {
        this.id = id;
        this.text = text;
        this.level = level;
        this.url = url;
    }
}
