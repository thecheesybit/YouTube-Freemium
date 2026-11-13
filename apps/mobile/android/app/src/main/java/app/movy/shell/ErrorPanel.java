package app.movy.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * "{Name} isn't responding" with Retry, in the iOS look (same tokens as Settings and the chooser). Shows no URL or
 * host. Hosts a slot for the source-switch suggestion.
 */
public final class ErrorPanel extends FrameLayout {
    private static final int WIDTH_DP = 320;
    private static final int TV_WIDTH_DP = 480;

    private final TextView title;
    private final TextView retry;
    private final LinearLayout extras;

    public ErrorPanel(Context ctx) {
        super(ctx);
        setBackgroundColor(UiTokens.BLACK);
        setClickable(true);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ScrollView sv = new ScrollView(ctx); // short windows (landscape, large font): scroll instead of clipping
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        addView(sv, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.dp(ctx, Ui.isTv(ctx) ? TV_WIDTH_DP : WIDTH_DP), -2, Gravity.CENTER);
        FrameLayout holder = new FrameLayout(ctx);
        holder.addView(col, cp);
        sv.addView(holder, new ScrollView.LayoutParams(-1, -2));

        title = IosViews.text(ctx, "", 24, UiTokens.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView body = IosViews.text(ctx, ctx.getString(R.string.movy_error_body), 15, UiTokens.LABEL_SECONDARY, false);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.dp(ctx, 8);
        col.addView(body, bp);

        retry = IosViews.primaryButton(ctx, ctx.getString(R.string.movy_retry), UiTokens.BLUE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Ui.dp(ctx, 28);
        col.addView(retry, rp);
        if (Ui.isTv(ctx)) TvFocus.scaleOnFocus(retry);

        extras = new LinearLayout(ctx);
        extras.setOrientation(LinearLayout.VERTICAL);
        col.addView(extras, new LinearLayout.LayoutParams(-1, -2));
        setVisibility(GONE);
    }

    /** Container for extra controls (the "Switch to X" suggestion lives here). */
    public ViewGroup extrasContainer() {
        return extras;
    }

    public void show(Source source, Runnable onRetry) {
        title.setText(getContext().getString(R.string.movy_error_title, source.name));
        retry.setOnClickListener(v -> onRetry.run());
        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(180).start();
        }
        retry.post(retry::requestFocus);
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        animate().cancel();
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }
}
