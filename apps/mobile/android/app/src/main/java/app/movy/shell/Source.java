package app.movy.shell;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One streaming source. Immutable. The url and hosts must never be shown in any UI string. */
public final class Source {
    public final String id;
    public final String name;
    public final String url;
    public final List<String> allowedHosts;
    public final int accent;
    public final boolean enabled;

    public Source(String id, String name, String url, List<String> allowedHosts, int accent, boolean enabled) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.allowedHosts = Collections.unmodifiableList(allowedHosts);
        this.accent = accent;
        this.enabled = enabled;
    }

    /** First letter of the name, upper case; used in the accent ring. */
    public String monogram() {
        return name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }
}
