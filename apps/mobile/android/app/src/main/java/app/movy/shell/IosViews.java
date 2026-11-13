package app.movy.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** View factory for the iOS-style panels (Settings, chooser, error panel). Colours and radii come from {@link UiTokens}. */
final class IosViews {
    static final int PRESSED = 0xFF3A3A3C;

    private IosViews() {}

    static TextView text(Context c, CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp * Ui.textScale(c));
        t.setTextColor(color);
        t.setLetterSpacing(-0.01f);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    static void clipRounded(View v, float radiusDp) {
        final float r = radiusDp * v.getResources().getDisplayMetrics().density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /** Rounded card (12dp) that clips its rows, as in iOS inset-grouped lists. */
    static LinearLayout groupCard(Context c, int fill) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.rounded(c, fill, UiTokens.GROUP_CORNER, 0, 0));
        clipRounded(l, UiTokens.GROUP_CORNER);
        return l;
    }

    /** 0.5dp separator inset from the left. */
    static View separator(Context c, float insetDp) {
        View v = new View(c);
        v.setBackgroundColor(UiTokens.SEPARATOR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(0.5f * c.getResources().getDisplayMetrics().density)));
        lp.leftMargin = Ui.dp(c, insetDp);
        v.setLayoutParams(lp);
        return v;
    }

    /** Pressed / focused highlight for tappable rows (focus also draws a ring for the TV). */
    static Drawable rowBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(PRESSED);
        focus.setStroke(Ui.dp(c, 2), UiTokens.WHITE);
        s.addState(new int[] {android.R.attr.state_focused}, focus);
        s.addState(new int[] {android.R.attr.state_pressed}, new ColorDrawable(PRESSED));
        s.addState(new int[] {}, new ColorDrawable(0x00000000));
        return s;
    }

    /** 29dp rounded-square badge in an iOS system colour with a white glyph. */
    static View badge(Context c, int color, int glyph) {
        int d = Ui.dp(c, UiTokens.BADGE);
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        f.setBackground(Ui.rounded(c, color, UiTokens.BADGE_CORNER, 0, 0));
        if (glyph != 0) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(glyph);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int g = Ui.dp(c, 17);
            f.addView(iv, new android.widget.FrameLayout.LayoutParams(g, g, Gravity.CENTER));
        }
        f.setLayoutParams(new LinearLayout.LayoutParams(d, d));
        return f;
    }

    /** Status dot centred in the badge slot so titles line up with badge rows. */
    static View dotSlot(Context c, int color) {
        int d = Ui.dp(c, UiTokens.BADGE);
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        View dot = new View(c);
        dot.setBackground(Ui.ring(c, color, color, 0));
        int s = Ui.dp(c, 10);
        f.addView(dot, new android.widget.FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(d, d));
        return f;
    }

    /** Full-width 14dp-corner primary button. */
    static TextView primaryButton(Context c, CharSequence label, int color) {
        TextView b = text(c, label, 17, Ui.onColor(color), true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(0, Ui.dp(c, 15), 0, Ui.dp(c, 15));
        style(b, color, false);
        b.setOnFocusChangeListener((v, f) -> style(b, color, f));
        return b;
    }

    static void style(View b, int color, boolean focused) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(Ui.dp(b.getContext(), UiTokens.BUTTON_CORNER));
        if (!focused) {
            b.setBackground(fill);
            return;
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(0x00000000);
        ring.setCornerRadius(Ui.dp(b.getContext(), UiTokens.BUTTON_CORNER));
        ring.setStroke(Ui.dp(b.getContext(), 3), UiTokens.WHITE);
        b.setBackground(new LayerDrawable(new Drawable[] {fill, ring}));
    }

    /** Squircle app-icon tile (accent gradient, white monogram, top hairline), as in the Island. */
    static final class Tile extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int accent;
        private final String letter;
        private final String sourceId;
        private final float dp;

        Tile(Context c, Source s) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            accent = s.accent;
            letter = s.monogram();
            sourceId = s.id;
            mono.setColor(UiTokens.WHITE);
            mono.setTextAlign(Paint.Align.CENTER);
            mono.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            edge.setStyle(Paint.Style.STROKE);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            fill.setShader(new LinearGradient(0, 0, 0, h, accent, IslandPainter.darken(accent, 0.25f), Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            edge.setStrokeWidth(Math.max(1f, dp * 0.75f));
            mono.setTextSize(h * 0.41f);
            float corner = h * UiTokens.TILE_CORNER / UiTokens.TILE;
            Squircle.rect(path, 0, 0, w, h, corner, 1f);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (SourceLogos.has(sourceId)) {
                Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
                white.setColor(0xFFFFFFFF);
                c.drawPath(path, white);
                SourceLogos.draw(c, sourceId, getWidth() / 2f, getHeight() / 2f, getHeight() / 2f);
                return;
            }
            c.drawPath(path, fill);
            c.drawPath(path, edge);
            c.drawText(letter, getWidth() / 2f, getHeight() / 2f - (mono.ascent() + mono.descent()) / 2f, mono);
        }
    }
}
