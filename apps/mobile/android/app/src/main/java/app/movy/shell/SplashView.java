package app.movy.shell;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Native launch splash: black, icon (scale/fade-in), app name, tagline; credit and version at the bottom.
 * Never waits for the network. The red spinner appears only when the splash is still up after 400 ms.
 */
public final class SplashView extends FrameLayout {
    private static final long SPINNER_DELAY_MS = 400;
    private static final long FADE_OUT_MS = 220;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpinnerView spinner;
    private boolean dismissed;

    public SplashView(Context ctx, String appName, LiveConfig cfg, String versionName, int build, boolean showCfg, boolean intro) {
        super(ctx);
        setBackgroundColor(Ui.BLACK);
        setClickable(true); // swallow touches while up

        LinearLayout center = new LinearLayout(ctx);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        addView(center, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(ctx);
        int resId = ctx.getResources().getIdentifier("splash_icon", "drawable", ctx.getPackageName());
        if (resId != 0) icon.setImageResource(resId);
        boolean shortWindow = ctx.getResources().getConfiguration().screenHeightDp < 480; // phone landscape
        int size = Ui.dp(ctx, (shortWindow ? 80 : 112) * (Ui.isTv(ctx) ? 1.3f : 1f));
        center.addView(icon, new LinearLayout.LayoutParams(size, size));
        icon.setScaleX(0.86f);
        icon.setScaleY(0.86f);
        icon.setAlpha(0f);
        final java.util.ArrayList<View> phaseOne = new java.util.ArrayList<>();
        phaseOne.add(icon);
        icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(480).setInterpolator(new DecelerateInterpolator()).start();

        TextView name = text(ctx, appName, 40, Ui.WHITE, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = Ui.dp(ctx, 18);
        center.addView(name, np);
        Ui.fadeIn(name, 160, Ui.dp(ctx, 6));
        phaseOne.add(name);

        if (!cfg.tagline.isEmpty()) {
            TextView tag = text(ctx, cfg.tagline, 18, 0xFFB8B8BD, false);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = Ui.dp(ctx, 6);
            center.addView(tag, tp);
            Ui.fadeIn(tag, 260, Ui.dp(ctx, 6));
            phaseOne.add(tag);
        }

        spinner = new SpinnerView(ctx);
        spinner.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 36));
        sp.topMargin = Ui.dp(ctx, shortWindow ? 16 : 28);
        center.addView(spinner, sp);
        phaseOne.add(spinner);

        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        if (!intro && !cfg.credit.isEmpty()) {
            String credit = ctx.getString(R.string.movy_splash_by, cfg.credit);
            android.text.SpannableString sp2 = new android.text.SpannableString(credit);
            int heart = credit.indexOf('\u2665');
            if (heart >= 0) {
                sp2.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF2D55), heart, heart + 1, 0);
            }
            TextView by = text(ctx, "", 16, 0xFFE5E5EA, false);
            by.setText(sp2);
            foot.addView(by);
        }
        String ver = ctx.getString(R.string.movy_version, versionName, build);
        if (showCfg && cfg.contentVersion > 0) ver += ctx.getString(R.string.movy_cfg_suffix, cfg.contentVersion);
        foot.addView(text(ctx, ver, 11, 0xFF636366, false));
        LayoutParams fp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        fp.bottomMargin = Ui.dp(ctx, 22);
        addView(foot, fp);
        Ui.fadeIn(foot, 320, 0);
        phaseOne.add(foot);

        if (intro) {
            final String who = cfg.credit.isEmpty() ? "Ayush Kumar" : cfg.credit;
            final LinearLayout finale = new LinearLayout(ctx);
            finale.setOrientation(LinearLayout.VERTICAL);
            finale.setGravity(Gravity.CENTER);
            TextView by = text(ctx, "Crafted by", 18, 0xFFB8B8BD, false);
            by.setLetterSpacing(0.12f);
            finale.addView(by);
            String line = who + " \u2665";
            android.text.SpannableString sp2 = new android.text.SpannableString(line);
            sp2.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF2D55), line.length() - 1, line.length(), 0);
            TextView nm = text(ctx, "", 44, Ui.WHITE, true);
            nm.setText(sp2);
            LinearLayout.LayoutParams nmp = new LinearLayout.LayoutParams(-2, -2);
            nmp.topMargin = Ui.dp(ctx, 6);
            finale.addView(nm, nmp);
            finale.setAlpha(0f);
            addView(finale, new LayoutParams(-2, -2, Gravity.CENTER));
            finale.setCameraDistance(Ui.dp(ctx, 8000));

            SplashSound.intro();
            // 2.0 s: the app name steps aside
            main.postDelayed(() -> {
                for (View v : phaseOne) {
                    v.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(420).start();
                }
            }, 2000);
            // 2.35 s: the credit grows into the centre
            main.postDelayed(() -> {
                SplashSound.credit();
                finale.setScaleX(0.5f);
                finale.setScaleY(0.5f);
                finale.setTranslationY(Ui.dp(ctx, 18));
                finale.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(620)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.15f)).start();
            }, 2350);
            // 3.15 s: it flips away and the app is right behind it
            main.postDelayed(() -> finale.animate().rotationY(90f).alpha(0f).setDuration(280)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator()).start(), 3150);
        }

        main.postDelayed(() -> {
            if (!dismissed) spinner.setVisibility(View.VISIBLE);
        }, SPINNER_DELAY_MS);
    }

    private static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp * Ui.textScale(c));
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.NORMAL));
        t.setLetterSpacing(bold ? 0.02f : 0f);
        return t;
    }

    /** Fades out and removes itself from its parent; {@code after} runs once gone. */
    public void dismiss(Runnable after) {
        if (dismissed) return;
        dismissed = true;
        main.removeCallbacksAndMessages(null);
        animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            if (after != null) after.run();
        }).start();
    }

    public boolean isDismissed() {
        return dismissed;
    }
}
