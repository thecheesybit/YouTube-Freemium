package app.movy.shell;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Validates a remote config JSON and, when valid, applies it over the build-time defaults.
 * Invalid input is ignored with one log line. Android-free so it can be unit tested.
 */
public final class RemoteApplier {
    private final LiveConfig defaults;
    private final Consumer<LiveConfig> sink;
    private final Consumer<String> saveLastGood;
    private final Consumer<String> log;
    private final Supplier<LiveConfig> current;

    public RemoteApplier(LiveConfig defaults, Supplier<LiveConfig> current, Consumer<LiveConfig> sink,
                         Consumer<String> saveLastGood, Consumer<String> log) {
        this.defaults = defaults;
        this.current = current;
        this.sink = sink;
        this.saveLastGood = saveLastGood;
        this.log = log;
    }

    /** @return true when the JSON was valid and applied. Empty/missing JSON is a silent no-op. */
    public boolean apply(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        LiveConfig next;
        try {
            next = LiveConfig.merge(defaults, json, UrlDecoder.IDENTITY);
        } catch (IllegalArgumentException e) {
            log.accept("remote config ignored: " + e.getMessage());
            return false;
        }
        LiveConfig prev = current.get();
        saveLastGood.accept(json);
        sink.accept(next);
        log.accept("remote config applied cfg=" + next.contentVersion + (prev != null ? " (was " + prev.contentVersion + ")" : ""));
        return true;
    }
}
