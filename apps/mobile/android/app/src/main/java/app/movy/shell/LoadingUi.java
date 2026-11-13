package app.movy.shell;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.function.IntConsumer;

/** Native page-load progress bar (3dp, top of content). The launch splash is {@link SplashView}. */
public final class LoadingUi {
    private static final long FADE_MS = 180;

    private final View bar;
    private boolean suppressed;
    private IntConsumer progressListener;

    public LoadingUi(Activity activity) {
        float d = activity.getResources().getDisplayMetrics().density;
        ViewGroup content = activity.findViewById(android.R.id.content);
        bar = new View(activity);
        bar.setBackgroundColor(SpinnerView.RED);
        bar.setPivotX(0f);
        bar.setScaleX(0f);
        bar.setAlpha(0f);
        content.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(3 * d), Gravity.TOP));
    }

    /** Fullscreen video: nothing of ours may draw or animate over the video surface. */
    public void setSuppressed(boolean on) {
        suppressed = on;
        bar.animate().cancel();
        bar.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    public void setProgressListener(IntConsumer l) {
        this.progressListener = l;
    }

    /** Shows the bar immediately (e.g. on a source switch) before the WebView reports progress. */
    public void begin() {
        if (suppressed) return;
        bar.animate().cancel();
        bar.setAlpha(1f);
        bar.setScaleX(0.08f);
    }

    /** Called from WebChromeClient.onProgressChanged (UI thread). */
    public void onProgress(int percent) {
        if (progressListener != null) progressListener.accept(percent);
        if (suppressed) return;
        if (percent >= 100) {
            bar.animate().scaleX(1f).setDuration(120).withEndAction(
                    () -> bar.animate().alpha(0f).setDuration(FADE_MS).start()).start();
            return;
        }
        bar.animate().cancel();
        bar.setAlpha(1f);
        bar.animate().scaleX(Math.max(percent, 8) / 100f).setDuration(200).start();
    }
}
