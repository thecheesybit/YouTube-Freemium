package app.movy.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Native launch splash in the Music Player design language: violet-black canvas, a soft lilac/lime glow
 * behind the icon, a two-tone wordmark and an uppercase micro-label. The system splash shows the same canvas
 * with no icon, so launch reads as one continuous screen. Never waits for the network; the lime loading line
 * appears only when the splash is still up after 400 ms.
 */
public final class SplashView extends FrameLayout {
    private static final long LOADER_DELAY_MS = 400;
    private static final long FADE_OUT_MS = 280;

    // Music Player tokens (night theme).
    static final int CANVAS = 0xFF0B0A0E;
    private static final int INK = 0xFFF5F4F7;
    private static final int INK_MUTED = 0xFFA7A5AE;
    private static final int INK_SUBTLE = 0xFF8F8D97;
    private static final int SUNKEN = 0xFF221F28;
    private static final int LIME = 0xFFD4F06C;
    private static final int LILAC = 0xFFD7A8F5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView icon;
    private final View glow;
    private final LoaderLine loader;
    private boolean dismissed;

    public SplashView(Context ctx, String appName, LiveConfig cfg, String versionName, int build, boolean showCfg) {
        super(ctx);
        setBackgroundColor(CANVAS);
        setClickable(true); // swallow touches while up

        boolean shortWindow = ctx.getResources().getConfiguration().screenHeightDp < 480; // phone landscape
        float tv = Ui.isTv(ctx) ? 1.3f : 1f;
        int iconSize = Ui.dp(ctx, (shortWindow ? 76 : 104) * tv);

        glow = new GlowView(ctx);
        int glowSize = iconSize * 4;
        LayoutParams gp = new LayoutParams(glowSize, glowSize, Gravity.CENTER);
        addView(glow, gp);

        LinearLayout center = new LinearLayout(ctx);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(center, new LayoutParams(-2, -2, Gravity.CENTER));

        icon = new ImageView(ctx);
        int resId = ctx.getResources().getIdentifier("splash_icon", "drawable", ctx.getPackageName());
        if (resId != 0) icon.setImageResource(resId);
        icon.setElevation(Ui.dp(ctx, 12));
        center.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView name = text(ctx, twoTone(appName), 30 * tv, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        name.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = Ui.dp(ctx, shortWindow ? 14 : 22);
        center.addView(name, np);

        if (!cfg.tagline.isEmpty()) {
            TextView tag = text(ctx, cfg.tagline.toUpperCase(java.util.Locale.ROOT), 11 * tv, INK_SUBTLE,
                    Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tag.setLetterSpacing(0.16f);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = Ui.dp(ctx, 10);
            center.addView(tag, tp);
            Ui.fadeIn(tag, 240, Ui.dp(ctx, 8));
        }

        loader = new LoaderLine(ctx);
        loader.setAlpha(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(ctx, 112), Ui.dp(ctx, 3));
        lp.topMargin = Ui.dp(ctx, shortWindow ? 18 : 32);
        center.addView(loader, lp);

        String ver = ctx.getString(R.string.movy_version, versionName, build);
        if (showCfg && cfg.contentVersion > 0) ver += ctx.getString(R.string.movy_cfg_suffix, cfg.contentVersion);
        TextView foot = text(ctx, ver, 11, 0xFF5E5C66, Typeface.create("sans-serif", Typeface.NORMAL));
        LayoutParams fp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        fp.bottomMargin = Ui.dp(ctx, 22);
        addView(foot, fp);

        // Staged entrance: glow blooms, icon settles, words rise. Presses and entrances never bounce.
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        glow.setAlpha(0f);
        glow.setScaleX(0.7f);
        glow.setScaleY(0.7f);
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(ease).start();
        icon.setScaleX(0.9f);
        icon.setScaleY(0.9f);
        icon.setAlpha(0f);
        icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(460).setInterpolator(ease).start();
        Ui.fadeIn(name, 130, Ui.dp(ctx, 10));
        foot.setAlpha(0f);
        foot.animate().alpha(1f).setStartDelay(360).setDuration(400).start();

        main.postDelayed(() -> {
            if (dismissed) return;
            loader.start();
            loader.animate().alpha(1f).setDuration(220).start();
        }, LOADER_DELAY_MS);
    }

    /** "YouTube Freemium": lead word in ink, the rest in ink-muted (the system's two-tone heading). */
    private static CharSequence twoTone(String s) {
        int sp = s.indexOf(' ');
        if (sp <= 0) return s;
        SpannableString out = new SpannableString(s);
        out.setSpan(new ForegroundColorSpan(INK_MUTED), sp, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private static TextView text(Context c, CharSequence s, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp * Ui.textScale(c));
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Scales the icon up a touch while the whole splash fades, then removes itself; {@code after} runs once gone. */
    public void dismiss(Runnable after) {
        if (dismissed) return;
        dismissed = true;
        main.removeCallbacksAndMessages(null);
        icon.animate().scaleX(1.08f).scaleY(1.08f).setDuration(FADE_OUT_MS).setInterpolator(new DecelerateInterpolator()).start();
        glow.animate().scaleX(1.25f).scaleY(1.25f).setDuration(FADE_OUT_MS).start();
        animate().alpha(0f).setDuration(FADE_OUT_MS).withLayer().withEndAction(() -> {
            loader.stop();
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            if (after != null) after.run();
        }).start();
    }

    public boolean isDismissed() {
        return dismissed;
    }

    /** Soft radial bloom: lilac core fading out, with a faint lime rim, never a hard edge. */
    private static final class GlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlowView(Context c) {
            super(c);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float r = Math.min(w, h) / 2f;
            paint.setShader(new RadialGradient(w / 2f, h / 2f, r,
                    new int[]{withAlpha(LILAC, 0x4A), withAlpha(LILAC, 0x1C), withAlpha(LILAC, 0x08), withAlpha(LILAC, 0)},
                    new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, paint);
        }

        private static int withAlpha(int color, int a) {
            return (color & 0x00FFFFFF) | (a << 24);
        }
    }

    /** Indeterminate loading line: a lime pill sweeping across a sunken pill track. */
    private static final class LoaderLine extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        private float t;

        LoaderLine(Context c) {
            super(c);
            track.setColor(SUNKEN);
            fill.setColor(LIME);
            anim.setDuration(1100);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        void start() {
            if (!anim.isStarted()) anim.start();
        }

        void stop() {
            anim.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, rad, rad, track);
            float seg = w * 0.36f;
            // Eased sweep from off the left edge to off the right edge, clipped to the track.
            float e = (float) (0.5 - Math.cos(Math.PI * t) / 2);
            float x = -seg + (w + seg) * e;
            r.set(Math.max(0, x), 0, Math.min(w, x + seg), h);
            if (r.width() > 0) canvas.drawRoundRect(r, rad, rad, fill);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            anim.cancel();
        }
    }
}
