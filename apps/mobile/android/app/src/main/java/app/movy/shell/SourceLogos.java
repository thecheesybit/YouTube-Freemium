package app.movy.shell;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Vector YouTube and YouTube Music marks, drawn on a white tile so people recognise what they are switching to. */
final class SourceLogos {
    private static final int YT_RED = 0xFFFF0000;
    private static final int YTM_RED = 0xFFFF0033;

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path TRI = new Path();
    private static final RectF RECT = new RectF();

    private SourceLogos() {}

    static boolean has(String id) {
        return "youtube".equals(id) || "youtube-music".equals(id);
    }

    /** Draws the mark centred on (cx, cy) inside a tile of half-size {@code h}. */
    static void draw(Canvas c, String id, float cx, float cy, float h) {
        if ("youtube-music".equals(id)) music(c, cx, cy, h);
        else video(c, cx, cy, h);
    }

    private static void triangle(Canvas c, float cx, float cy, float size, int color) {
        TRI.reset();
        TRI.moveTo(cx - size * 0.38f, cy - size * 0.52f);
        TRI.lineTo(cx - size * 0.38f, cy + size * 0.52f);
        TRI.lineTo(cx + size * 0.62f, cy);
        TRI.close();
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(color);
        c.drawPath(TRI, PAINT);
    }

    private static void video(Canvas c, float cx, float cy, float h) {
        float w = h * 1.44f;
        float ht = h * 1.02f;
        RECT.set(cx - w / 2f, cy - ht / 2f, cx + w / 2f, cy + ht / 2f);
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(YT_RED);
        c.drawRoundRect(RECT, ht * 0.30f, ht * 0.30f, PAINT);
        triangle(c, cx, cy, ht * 0.46f, 0xFFFFFFFF);
    }

    private static void music(Canvas c, float cx, float cy, float h) {
        float r = h * 0.80f;
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(YTM_RED);
        c.drawCircle(cx, cy, r, PAINT);
        PAINT.setStyle(Paint.Style.STROKE);
        PAINT.setColor(0xFFFFFFFF);
        PAINT.setStrokeWidth(r * 0.115f);
        c.drawCircle(cx, cy, r * 0.70f, PAINT);
        triangle(c, cx, cy, r * 0.62f, 0xFFFFFFFF);
    }
}
