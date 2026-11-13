package app.movy.shell;

import android.content.Context;

/** Build without Firebase: no remote config. */
public final class RemoteBackends {
    private RemoteBackends() {}

    public static boolean available() {
        return false;
    }

    public static void start(Context ctx, RemoteApplier applier, String parameter, boolean tv, boolean debug,
                             String instanceId) {
        // intentionally empty
    }
}
