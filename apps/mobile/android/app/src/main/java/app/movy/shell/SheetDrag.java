package app.movy.shell;

/** Pure decision for the bottom sheet's drag-to-dismiss. */
final class SheetDrag {
    static final float DISMISS_FRACTION = 0.25f;
    static final float FLING_DP_PER_S = 900f;

    private SheetDrag() {}

    /** Dismiss when pulled down past a quarter of the sheet, or flung down fast enough. */
    static boolean shouldDismiss(float dragPx, float sheetHeightPx, float velocityPxPerS, float density) {
        if (dragPx <= 0f) return false;
        if (velocityPxPerS > FLING_DP_PER_S * density) return true;
        return dragPx > sheetHeightPx * DISMISS_FRACTION && velocityPxPerS > -FLING_DP_PER_S * density;
    }
}
