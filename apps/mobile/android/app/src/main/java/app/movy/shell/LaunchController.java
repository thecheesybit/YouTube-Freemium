package app.movy.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.util.Log;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Launch flow: native splash (never waits for the network), then either the saved source or the chooser.
 * Also owns the "isn't responding" panel and the one-time soft update dialog.
 */
final class LaunchController implements SourceRepository.Listener, ChooserView.Listener {
    private static final long SPLASH_HARD_CAP_MS = 1500;
    /** Extra splash time for the one-time branded intro (about 3.5 s in total). */
    private static final long INTRO_HOLD_MS = 2900;
    private static boolean introPlayed;
    private static final int READY_PROGRESS = 70;

    private final Activity activity;
    private final ViewGroup content;
    private final SourceRepository repo;
    private final Prefs prefs;
    private final String appName;
    private final boolean remote;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ErrorPanel errorPanel;

    private SplashView splash;
    private ChooserView chooser;
    private long splashShownAt;
    private boolean launching;
    /** Debug builds only: extra time the splash stays up (screenshots). */
    private long holdMs;

    LaunchController(Activity activity, SourceRepository repo, Prefs prefs, String appName, boolean remote) {
        this.activity = activity;
        this.content = activity.findViewById(android.R.id.content);
        this.repo = repo;
        this.prefs = prefs;
        this.appName = appName;
        this.remote = remote;
        this.errorPanel = new ErrorPanel(activity);
        content.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
        repo.addListener(this);
    }

    ErrorPanel errorPanel() {
        return errorPanel;
    }

    ChooserView chooserView() {
        return chooser;
    }

    boolean isChooserShowing() {
        return chooser != null;
    }

    void setDebugHold(long ms) {
        holdMs = Math.max(0, Math.min(ms, 10_000));
    }

    /** Shows the splash, then opens the saved source or the chooser. */
    void start() {
        LiveConfig cfg = repo.config();
        boolean intro = !introPlayed;
        introPlayed = true;
        if (intro) holdMs = Math.max(holdMs, INTRO_HOLD_MS);
        splash = new SplashView(activity, appName, cfg, BuildConfig.VERSION_NAME, BuildConfig.MOVY_BUILD, remote, intro);
        content.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        splashShownAt = SystemClock.elapsedRealtime();
        Source launch = repo.launchSource();
        Log.i(KillLog.TAG, "launch flow: " + (launch != null ? "saved source " + launch.id : "chooser"));
        if (launch != null) {
            launching = true;
            repo.switchTo(launch.id);
            main.postDelayed(this::dismissSplash, SPLASH_HARD_CAP_MS + holdMs);
        } else {
            main.postDelayed(this::showChooser, cfg.splashMinMs + holdMs);
        }
        main.postDelayed(() -> checkUpdate(repo.config()), SPLASH_HARD_CAP_MS + 300);
    }

    /** Page progress (UI thread); lets the splash go once the site has started to paint. */
    void onProgress(int percent) {
        if (!launching || splash == null || percent < READY_PROGRESS) return;
        long left = repo.config().splashMinMs + holdMs - (SystemClock.elapsedRealtime() - splashShownAt);
        main.postDelayed(this::dismissSplash, Math.max(0, left));
    }

    void onMainFrameError() {
        Source a = repo.active();
        if (a == null) return;
        dismissSplash();
        errorPanel.show(a, () -> {
            errorPanel.hide();
            repo.switchTo(a.id);
        });
    }

    void onPageStarted() {
        if (errorPanel.isShowing()) errorPanel.hide();
    }

    private void dismissSplash() {
        launching = false;
        SplashView s = splash;
        splash = null;
        if (s != null) s.dismiss(null);
    }

    private void showChooser() {
        LiveConfig cfg = repo.config();
        Source pre = repo.preselected();
        chooser = new ChooserView(activity, cfg, pre.id, !prefs.askEveryLaunch(), this);
        content.addView(chooser, new FrameLayout.LayoutParams(-1, -1));
        // Chooser sits under the splash; the splash fades to reveal it.
        if (splash != null) {
            splash.bringToFront();
            dismissSplash();
        }
    }

    /** Debug builds only: shows the chooser on top of whatever is loaded (never persists anything by itself). */
    void debugShowChooser() {
        if (chooser == null) showChooser();
    }

    @Override
    public void onChosen(Source source, boolean alwaysOpen) {
        Log.i(KillLog.TAG, "chooser: chose " + source.id + " always=" + alwaysOpen);
        prefs.setAskEveryLaunch(!alwaysOpen);
        if (alwaysOpen) prefs.setDefaultSourceId(source.id);
        ChooserView c = chooser;
        chooser = null;
        repo.switchTo(source.id);
        if (c != null) c.dismiss(null);
    }

    @Override
    public void onConfigChanged(LiveConfig config) {
        if (chooser != null) chooser.setConfig(config);
        checkUpdate(config);
    }

    @Override
    public void onActiveSourceChanged(Source active) {
        errorPanel.hide();
    }

    private void checkUpdate(LiveConfig cfg) {
        if (activity.isFinishing() || cfg.minBuild <= BuildConfig.MOVY_BUILD) return;
        if (prefs.updateShownForVersion() == cfg.contentVersion) return;
        prefs.setUpdateShownForVersion(cfg.contentVersion);
        String msg = cfg.updateMessage.isEmpty() ? activity.getString(R.string.movy_update_default) : cfg.updateMessage;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.movy_update_title)
                .setMessage(msg)
                .setPositiveButton(R.string.movy_ok, null)
                .show();
    }
}
