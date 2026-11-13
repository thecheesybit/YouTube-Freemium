package app.movy.adblock;

/** Runtime on/off flag for ad blocking (Settings toggle, persisted by Prefs). Network blocking and cosmetics honour it at once. */
public final class AdblockSwitch {
    private static volatile boolean on = true;

    private AdblockSwitch() {}

    public static boolean isOn() {
        return on;
    }

    public static void set(boolean enabled) {
        on = enabled;
    }
}
