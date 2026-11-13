package app.movy.shell;

import android.graphics.RectF;

/**
 * Layout math of the Island, in pixels, recomputed in place (no allocation) for the spring values: expansion
 * {@code p} (0 capsule, 1 expanded, may overshoot), visibility {@code show} (0 sliver, 1 capsule) and the settings
 * bubble {@code bubble} (0 tucked in, 1 out, may overshoot). The view is a full-size overlay; the island floats
 * {@code inset} from the right edge.
 */
final class IslandGeometry {
    /** Pixels per dp including the TV scale. */
    final float u;
    final float capW, capH, sliverW, inset, pillW, corner, circle, tileCorner, pitch, padTop, padBottom;
    final float gearD, gearGap, margin, hitW, shadow;

    float viewW, viewH;
    int count;
    /** Collapsed centre as a 0..1 fraction of the view height. */
    float centerFrac = 0.4f;
    float scroll;

    // results of update()
    float left, right, top, bottom, bw, cr;
    float scrollMax, pillH, showAmt = 1f;
    float[] itemY = new float[8];
    float itemX, gearX, gearY, gearAlpha, gearScale;
    boolean hasGear = true;
    final RectF card = new RectF();
    final RectF cardClose = new RectF();
    boolean hasCard;

    IslandGeometry(float density, float scale) {
        u = density * scale;
        capW = UiTokens.CAPSULE_W * u;
        capH = UiTokens.CAPSULE_H * u;
        sliverW = UiTokens.SLIVER_W * u;
        inset = UiTokens.ISLAND_INSET * u;
        pillW = UiTokens.ISLAND_W * u;
        corner = UiTokens.ISLAND_CORNER * u;
        circle = UiTokens.TILE * u;
        tileCorner = UiTokens.TILE_CORNER * u;
        pitch = 76 * u;
        padTop = 14 * u;
        padBottom = 10 * u;
        gearD = UiTokens.BUBBLE * u;
        gearGap = 10 * u;
        margin = 10 * u;
        hitW = 28 * u;
        shadow = 24 * u;
    }

    void setSize(float w, float h, int itemCount) {
        viewW = w;
        viewH = h;
        count = itemCount;
        if (itemY.length < itemCount) itemY = new float[itemCount];
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    void update(float p, float show, float bubble) {
        float natural = padTop + count * pitch + padBottom;
        float gearBlock = hasGear ? gearGap + gearD : 0;
        float availPill = viewH - 2 * margin - gearBlock;
        float minPill = Math.min(natural, 2 * pitch + padTop + padBottom);
        pillH = Math.max(Math.min(natural, availPill), minPill);
        scrollMax = Math.max(0, natural - pillH);
        scroll = clamp(scroll, 0, scrollMax);

        float pc = clamp(p, 0f, 1.2f);
        showAmt = clamp(show, 0f, 1f);
        float cC = collapsedCenter();
        float hC = capH * lerp(0.7f, 1f, showAmt);
        float wC = lerp(sliverW, capW, showAmt);
        float tC = cC - hC / 2;
        float tE = clamp(cC - pillH / 2, margin, Math.max(margin, viewH - margin - gearBlock - pillH));
        top = lerp(tC, tE, pc);
        bottom = top + lerp(hC, pillH, pc);
        bw = Math.max(sliverW, lerp(wC, pillW, pc));
        right = viewW - inset;
        left = right - bw;
        cr = lerp(wC / 2f, corner, pc);

        itemX = right - bw / 2;
        for (int i = 0; i < count; i++) itemY[i] = top + padTop - scroll + i * pitch + circle / 2;

        float bc = Math.max(0f, bubble);
        gearAlpha = clamp(bc * 1.4f, 0f, 1f);
        gearScale = clamp(bc, 0f, 1.15f);
        gearX = itemX;
        gearY = lerp(bottom - gearD / 2, bottom + gearGap + gearD / 2, clamp(bc, 0f, 1.25f));
        layoutCard();
    }

    float collapsedCenter() {
        float lo = margin + capH / 2;
        float hi = Math.max(lo, viewH - margin - capH / 2);
        return clamp(centerFrac * viewH, lo, hi);
    }

    /** Card sits left of the island, aligned with its top; height is set by the painter from its text layout. */
    float cardWidth() {
        return Math.min(230 * u, Math.max(0, viewW - pillW - inset - 28 * u));
    }

    private void layoutCard() {
        float w = cardWidth();
        float h = card.height();
        float l = left - 12 * u - w;
        card.set(l, top, l + w, top + h);
        float s = 26 * u;
        cardClose.set(card.right - s, card.top, card.right, card.top + s);
    }

    // ---- hit tests (px in view coordinates) ----

    boolean tabHit(float x, float y) {
        return showAmt > 0.3f && x >= viewW - hitW && y >= top - 8 * u && y <= bottom + 8 * u;
    }

    boolean pillHit(float x, float y) {
        return x >= left - 4 * u && y >= top && y <= bottom;
    }

    /** Index of the item under the point, or -1. */
    int itemAt(float x, float y) {
        if (!pillHit(x, y)) return -1;
        for (int i = 0; i < count; i++) {
            if (Math.abs(y - itemY[i]) <= pitch / 2 - 4 * u && y > top + 6 * u && y < bottom - 6 * u) return i;
        }
        return -1;
    }

    boolean gearHit(float x, float y) {
        if (!hasGear || gearAlpha < 0.9f) return false;
        float dx = x - gearX;
        float dy = y - gearY;
        float r = gearD / 2 + 6 * u;
        return dx * dx + dy * dy <= r * r;
    }

    boolean cardHit(float x, float y) {
        return hasCard && card.contains(x, y);
    }

    /** Bounds of everything drawn in the expanded state (shadow included), for gesture exclusion and invalidation. */
    void blockBounds(RectF out) {
        float l = viewW - inset - pillW - shadow;
        float b = bottom + shadow + (hasGear ? gearGap + gearD : 0);
        out.set(Math.max(0, l), Math.max(0, top - shadow), viewW, Math.min(viewH, b));
    }
}
