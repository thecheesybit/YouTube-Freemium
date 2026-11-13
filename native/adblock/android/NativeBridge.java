package app.movy.adblock;

/**
 * JNI bridge to libmovy_adblock.so. Handles are opaque native pointers (0 = invalid).
 * A handle must not be used during or after nativeDestroy.
 */
public final class NativeBridge {
    static {
        System.loadLibrary("movy_adblock");
    }

    private NativeBridge() {}

    /** Loads a serialized engine (build-engine output). resourcesJson may be null. Returns 0 on failure. */
    public static native long nativeCreate(byte[] engineDat, String resourcesJsonOrNull);

    /** Builds an engine from raw ABP/uBO rule text. Returns 0 on failure. */
    public static native long nativeCreateFromRules(String rules, String resourcesJsonOrNull);

    public static native void nativeDestroy(long handle);

    /** Returns null when allowed, "B" when blocked, or "R:" + data-URL when redirected. */
    public static native String nativeCheck(long handle, String url, String sourceUrl, String type);

    /** JSON: {"hide_selectors":[],"procedural_actions":[],"exceptions":[],"injected_script":"","generichide":false} */
    public static native String nativeCosmetic(long handle, String url);

    /** JSON array of CSS selectors to hide. */
    public static native String nativeHiddenSelectors(long handle, String[] classes, String[] ids, String[] exceptions);
}
