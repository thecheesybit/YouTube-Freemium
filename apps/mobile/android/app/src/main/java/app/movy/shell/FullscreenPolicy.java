package app.movy.shell;

/**
 * Pure decisions for web fullscreen (video). The app never forces an orientation: rotation always follows the
 * device's own auto-rotate / rotation-lock setting, in browsing and in fullscreen. A rotation inside fullscreen
 * therefore has to be survived by re-laying out the video view.
 */
final class FullscreenPolicy {
    private FullscreenPolicy() {}

    /** True when the video view no longer fills its container (a rotation the page never re-laid out for). */
    static boolean needsReattach(int viewW, int viewH, int boxW, int boxH) {
        return boxW > 0 && boxH > 0 && (viewW != boxW || viewH != boxH);
    }
}
