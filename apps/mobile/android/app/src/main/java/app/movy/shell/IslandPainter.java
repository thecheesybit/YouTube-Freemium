package app.movy.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import androidx.core.content.res.ResourcesCompat;

import java.util.List;

/** All Island drawing. Paints, paths, shaders and matrices are built once; nothing is allocated while drawing. */
final class IslandPainter {
    static final int HEALTH_UP = UiTokens.HEALTH_UP;
    static final int HEALTH_SLOW = UiTokens.HEALTH_SLOW;
    static final int HEALTH_DOWN = UiTokens.HEALTH_DOWN;
    static final int HEALTH_UNKNOWN = UiTokens.HEALTH_UNKNOWN;
    static final float CONTENT_MS = 190f;
    private static final int SHADOW = 0x73000000;

    private final IslandGeometry g;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hilite = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint cardText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path tile = new Path();
    private final Matrix shaderMatrix = new Matrix();
    private Drawable gear;
    private final float monoBaseline;
    private final float labelSize;
    private final float shadowBlur;
    private final float shadowDy;
    private final Shader hiliteShader;
    private Shader[] tileShaders = new Shader[0];
    private float lastShowForShadow = -1f;
    private final boolean tv;

    IslandPainter(Context ctx, IslandGeometry g, boolean tv) {
        this.g = g;
        this.tv = tv;
        float tvScale = g.u / ctx.getResources().getDisplayMetrics().density;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, tv ? Math.max(15f, 11 * tvScale) : 11, dm);
        label.setTextSize(labelSize);
        label.setTextAlign(Paint.Align.CENTER);
        label.setLetterSpacing(-0.01f);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mono.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18 * tvScale, dm));
        mono.setTextAlign(Paint.Align.CENTER);
        mono.setLetterSpacing(-0.01f);
        mono.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mono.setColor(UiTokens.WHITE);
        monoBaseline = -(mono.ascent() + mono.descent()) / 2f;
        stroke.setStyle(Paint.Style.STROKE);
        hair.setStyle(Paint.Style.STROKE);
        hair.setStrokeWidth(1f);
        hair.setColor(UiTokens.WHITE);
        hilite.setStyle(Paint.Style.STROKE);
        hilite.setStrokeWidth(Math.max(1f, g.u * 0.75f));
        hiliteShader = new LinearGradient(0, 0, 0, g.circle * 0.55f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        hilite.setShader(hiliteShader);
        cardText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13 * tvScale, dm));
        cardText.setColor(0xFFE5E5EA);
        shadowBlur = 18 * g.u;
        shadowDy = 6 * g.u;
        body.setColor(UiTokens.BLACK);
        body.setShadowLayer(shadowBlur, 0, shadowDy, SHADOW);
        gear = ResourcesCompat.getDrawable(ctx.getResources(), R.drawable.ic_gear, null);
        if (gear != null) {
            gear = gear.mutate();
            int s = Math.round(22 * g.u / 2f);
            gear.setBounds(-s, -s, s, s);
        }
    }

    /** Builds one vertical gradient per source (accent to accent darkened 25%). Call when the list changes. */
    void prepare(List<Source> sources) {
        tileShaders = new Shader[sources.size()];
        for (int i = 0; i < tileShaders.length; i++) {
            int a = sources.get(i).accent;
            tileShaders[i] = new LinearGradient(0, 0, 0, g.circle, a, darken(a, 0.25f), Shader.TileMode.CLAMP);
        }
    }

    static int darken(int c, float amount) {
        float k = 1f - amount;
        return Color.argb(Color.alpha(c), Math.round(Color.red(c) * k), Math.round(Color.green(c) * k), Math.round(Color.blue(c) * k));
    }

    /** Sideways offset of the attention wiggle at progress t (0..1): three decaying swings, 0 at both ends. */
    static float nudgeOffset(float t, float amplitudePx) {
        if (t <= 0f || t >= 1f) return 0f;
        return (float) (Math.sin(t * Math.PI * 6) * (1f - t)) * amplitudePx;
    }

    /** Linear RGB blend from {@code from} (f = 0) to {@code to} (f = 1); alpha taken from {@code to}. */
    static int blend(int from, int to, float f) {
        float k = Math.max(0f, Math.min(1f, f));
        return Color.argb(Color.alpha(to),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * k),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * k),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k));
    }

    float labelSize() {
        return labelSize;
    }

    android.graphics.Paint labelPaint() {
        return label;
    }

    android.text.TextPaint cardTextPaint() {
        return cardText;
    }

    private static int a(int alpha255, float f) {
        return Math.max(0, Math.min(255, Math.round(alpha255 * f)));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    void draw(Canvas c, IslandView v) {
        g.update(v.progress, v.show, v.bubble);
        float show = g.showAmt;
        if (show <= 0.01f) return;
        if (show != lastShowForShadow) {
            body.setShadowLayer(shadowBlur, 0, shadowDy, a(0x73, show) << 24);
            lastShowForShadow = show;
        }
        float pc = clamp01(v.progress);
        Squircle.rect(shape, g.left, g.top, g.right, g.bottom, g.cr, Squircle.smoothFor(g.cr, g.u));
        float dx = tv ? 0f : nudgeOffset(v.nudge, UiTokens.NUDGE_AMPLITUDE_DP * g.u) * (1f - pc);
        if (dx != 0f) {
            c.save();
            c.translate(dx, 0f);
        }
        body.setColor(tv ? UiTokens.BLACK : blend(UiTokens.ISLAND_IDLE, UiTokens.BLACK, pc));
        body.setAlpha(a(255, show));
        c.drawPath(shape, body);
        hair.setAlpha(a(0x14, show));
        c.drawPath(shape, hair);
        if (dx != 0f) c.restore();
        body.setColor(UiTokens.BLACK);

        drawBubble(c, v);
        if (pc <= 0.01f && v.contentMs <= 0f) return;
        drawTiles(c, v);
        drawCard(c, v);
    }

    private void drawBubble(Canvas c, IslandView v) {
        if (!g.hasGear || g.gearAlpha <= 0.01f) return;
        float r = g.gearD / 2;
        float press = v.pressIndex == g.count ? v.pressScale : 1f;
        float sc = g.gearScale * press;
        c.save();
        c.translate(g.gearX, g.gearY);
        c.scale(sc, sc);
        body.setAlpha(a(255, g.gearAlpha));
        c.drawCircle(0, 0, r, body);
        hair.setAlpha(a(0x14, g.gearAlpha));
        c.drawCircle(0, 0, r, hair);
        if (gear != null) {
            gear.setAlpha(a(255, g.gearAlpha));
            gear.draw(c);
        }
        if (v.focusIndex == g.count && v.hasFocus()) {
            stroke.setColor(tv ? UiTokens.BLUE : UiTokens.WHITE);
            stroke.setAlpha(a(255, g.gearAlpha));
            stroke.setStrokeWidth(2.5f * g.u);
            c.drawCircle(0, 0, r + 4 * g.u, stroke);
        }
        c.restore();
    }

    private void drawTiles(Canvas c, IslandView v) {
        c.save();
        c.clipRect(g.left, g.top + 2 * g.u, g.right, g.bottom - 2 * g.u);
        int n = Math.min(v.sources.size(), tileShaders.length);
        for (int i = 0; i < n; i++) {
            float cy = g.itemY[i];
            if (cy + g.pitch < g.top || cy - g.pitch > g.bottom) continue;
            float t = clamp01((v.contentMs - i * UiTokens.TILE_STAGGER_MS) / CONTENT_MS);
            if (t <= 0f) continue;
            drawTile(c, v, i, cy, 1f - (1f - t) * (1f - t));
        }
        drawRing(c, v);
        c.restore();
    }

    private void drawTile(Canvas c, IslandView v, int i, float cy, float t) {
        float cx = g.itemX;
        float h = g.circle / 2;
        float near = Math.max(0f, 1f - Math.abs(i - v.ringPos));
        float scale = (UiTokens.INACTIVE_SCALE + (1f - UiTokens.INACTIVE_SCALE) * near) * (0.85f + 0.15f * t);
        if (v.pressIndex == i) scale *= v.pressScale;
        c.save();
        c.translate(cx, cy);
        c.scale(scale, scale);
        Squircle.rect(tile, -h, -h, h, h, g.tileCorner, 1f);
        shaderMatrix.setTranslate(0, -h);
        tileShaders[i].setLocalMatrix(shaderMatrix);
        tilePaint.setShader(tileShaders[i]);
        tilePaint.setAlpha(a(255, t));
        c.drawPath(tile, tilePaint);
        hilite.setAlpha(a(255, t));
        shaderMatrix.setTranslate(0, -h);
        hiliteShader.setLocalMatrix(shaderMatrix);
        c.drawPath(tile, hilite);

        String sid = v.sources.get(i).id;
        if (SourceLogos.has(sid)) {
            fill.setColor(0xFFFFFFFF);
            fill.setAlpha(a(255, t));
            c.drawPath(tile, fill);
            c.save();
            // the logo fades with the tile
            SourceLogos.draw(c, sid, 0f, 0f, h);
            c.restore();
        } else {
            mono.setAlpha(a(255, t));
            c.drawText(v.monograms[i], 0, monoBaseline, mono);
        }

        float dx = h - 4 * g.u;
        float dy = h - 4 * g.u;
        fill.setColor(UiTokens.BLACK);
        fill.setAlpha(a(255, t));
        c.drawCircle(dx, dy, 5.5f * g.u, fill);
        fill.setColor(healthColor(v.health.status(v.sources.get(i).id)));
        fill.setAlpha(a(255, t));
        c.drawCircle(dx, dy, 3.5f * g.u, fill);
        if (tv) {
            int key = TvKeys.colourFor(i);
            if (key != 0) { // colour-key hint: red, green, yellow, blue
                float kx = -h + 6 * g.u;
                fill.setColor(UiTokens.BLACK);
                fill.setAlpha(a(255, t));
                c.drawCircle(kx, -h + 6 * g.u, 6.5f * g.u, fill);
                fill.setColor(key);
                fill.setAlpha(a(255, t));
                c.drawCircle(kx, -h + 6 * g.u, 4.5f * g.u, fill);
            }
        }
        c.restore();

        label.setColor(near > 0.5f ? UiTokens.WHITE : UiTokens.LABEL_ISLAND);
        label.setAlpha(a(near > 0.5f ? 255 : 153, t));
        c.drawText(v.labels[i], cx, cy + h * scale + 5 * g.u - label.ascent(), label);

        if (v.pulseId != null && v.pulseId.equals(v.sources.get(i).id)) {
            float ph = v.pulsePhase;
            stroke.setColor(v.sources.get(i).accent);
            stroke.setAlpha(a(220, (1 - ph) * t));
            stroke.setStrokeWidth(2.5f * g.u);
            float grow = (4 + 8 * ph) * g.u;
            Squircle.rect(tile, cx - h - grow, cy - h - grow, cx + h + grow, cy + h + grow, g.tileCorner + grow, 1f);
            c.drawPath(tile, stroke);
        }
        if (v.focusIndex == i && v.hasFocus()) {
            stroke.setColor(tv ? UiTokens.BLUE : UiTokens.WHITE);
            stroke.setAlpha(a(255, t));
            stroke.setStrokeWidth((tv ? 4f : 2.5f) * g.u);
            float grow = 5 * g.u;
            Squircle.rect(tile, cx - h - grow, cy - h - grow, cx + h + grow, cy + h + grow, g.tileCorner + grow, 1f);
            c.drawPath(tile, stroke);
        }
    }

    /** The white ring (2dp, 3dp gap) that travels between tiles with the ring spring. */
    private void drawRing(Canvas c, IslandView v) {
        int n = v.sources.size();
        if (n == 0) return;
        float pos = Math.max(0f, Math.min(n - 1, v.ringPos));
        int lo = (int) Math.floor(pos);
        int hi = Math.min(n - 1, lo + 1);
        float f = pos - lo;
        float cy = g.itemY[lo] + (g.itemY[hi] - g.itemY[lo]) * f;
        int near = Math.round(pos);
        float t = clamp01((v.contentMs - near * UiTokens.TILE_STAGGER_MS) / CONTENT_MS);
        if (t <= 0f) return;
        float h = g.circle / 2 + 4 * g.u;
        float cx = g.itemX;
        stroke.setColor(UiTokens.WHITE);
        stroke.setAlpha(a(255, t));
        stroke.setStrokeWidth(2 * g.u);
        Squircle.rect(tile, cx - h, cy - h, cx + h, cy + h, g.tileCorner + 4 * g.u, 1f);
        c.drawPath(tile, stroke);
    }

    private void drawCard(Canvas c, IslandView v) {
        if (!g.hasCard || v.cardLayout == null) return;
        float t = clamp01(v.contentMs / CONTENT_MS);
        if (t < 0.3f) return;
        RectF r = g.card;
        float rad = 16 * g.u;
        fill.setColor(UiTokens.SURFACE);
        fill.setAlpha(a(255, t));
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(v.cardAccent);
        stroke.setAlpha(a(255, t));
        stroke.setStrokeWidth(1.5f * g.u);
        c.drawRoundRect(r, rad, rad, stroke);
        cardText.setAlpha(a(255, t));
        float pad = 12 * g.u;
        c.save();
        c.translate(r.left + pad, r.top + pad);
        v.cardLayout.draw(c);
        c.restore();
        float cx = g.cardClose.centerX();
        float cy = g.cardClose.centerY() + 2 * g.u;
        float k = 4 * g.u;
        stroke.setColor(UiTokens.LABEL_SECONDARY);
        stroke.setAlpha(a(255, t));
        stroke.setStrokeWidth(1.8f * g.u);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke);
        c.drawLine(cx - k, cy + k, cx + k, cy - k, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    static int healthColor(HealthChecker.Status s) {
        switch (s) {
            case UP: return HEALTH_UP;
            case SLOW: return HEALTH_SLOW;
            case DOWN: return HEALTH_DOWN;
            default: return HEALTH_UNKNOWN;
        }
    }
}
