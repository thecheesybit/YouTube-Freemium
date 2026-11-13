package app.movy.shell;

import android.app.Activity;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * TV discoverability: a small "Hold OK or press ▶ at the edge" chip that fades in for 3 s after a home page loads, and a one-time toast
 * the same text as a toast on the first launch. Never takes focus.
 */
final class TvHint {
    private static final long SHOW_MS = 3000;

    private final Activity activity;
    private final Prefs prefs;
    private final TextView chip;
    private final Runnable hide;

    TvHint(Activity activity, Prefs prefs) {
        this.activity = activity;
        this.prefs = prefs;
        chip = IosViews.text(activity, activity.getString(R.string.movy_tv_hint_chip), 16, UiTokens.WHITE, true);
        chip.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 9), Ui.dp(activity, 18), Ui.dp(activity, 9));
        chip.setBackground(Ui.rounded(activity, 0xE6202022, 24, UiTokens.HAIRLINE, 1));
        chip.setFocusable(false);
        chip.setAlpha(0f);
        chip.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = Ui.safeH(activity);
        lp.bottomMargin = Ui.safeV(activity);
        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(chip, lp);
        hide = () -> chip.animate().alpha(0f).setDuration(240).withEndAction(() -> chip.setVisibility(android.view.View.GONE)).start();
    }

    /** Call after a home page finished loading while the Island is closed. */
    void onHomeLoaded() {
        chip.removeCallbacks(hide);
        chip.setVisibility(android.view.View.VISIBLE);
        chip.animate().cancel();
        chip.animate().alpha(1f).setDuration(240).start();
        chip.postDelayed(hide, SHOW_MS);
        if (!prefs.tvHintShown()) {
            prefs.setTvHintShown();
            Toast.makeText(activity, R.string.movy_tv_hint_first, Toast.LENGTH_LONG).show();
        }
    }

    void dismiss() {
        chip.removeCallbacks(hide);
        chip.animate().cancel();
        chip.setAlpha(0f);
        chip.setVisibility(android.view.View.GONE);
    }
}
