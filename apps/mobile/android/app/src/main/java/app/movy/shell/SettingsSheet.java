package app.movy.shell;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import app.movy.adblock.AdblockSwitch;
import app.movy.adblock.BlockStats;

/**
 * iOS-style Settings: a floating 420dp card on the right (tablet / landscape) or a bottom sheet with a grabber
 * (phone portrait), springing in over a 35% scrim. Inset-grouped content comes from {@link SettingsModel}; sub-pages
 * push inside the panel with a slide. One instance is reused; it is attached to the content view while open.
 */
final class SettingsSheet extends FrameLayout implements SettingsModel.Actions, HealthChecker.Listener {
    /** One page of the in-panel navigation stack. */
    private static final class Page {
        final String title;
        final Supplier<List<SettingsSection>> sections;
        final LinearLayout list;
        final ScrollView scroll;
        final View view;

        Page(String title, Supplier<List<SettingsSection>> sections, LinearLayout list, ScrollView scroll, View view) {
            this.title = title;
            this.sections = sections;
            this.list = list;
            this.scroll = scroll;
            this.view = view;
        }
    }

    private final Activity activity;
    private final SourceRepository repo;
    private final Prefs prefs;
    private final BlockStats stats;
    private final HealthChecker health;
    private final StorageManager storage;
    private final Supplier<WebView> webView;
    private final boolean adblockConfigured;
    private final String appName;
    private final boolean tv;
    private boolean sideCard;
    private final FrameLayout panel;
    private final FrameLayout pages;
    private final View scrim;
    private final Drawable icon;
    private final List<Page> stack = new ArrayList<>();
    private IosAlert alert;
    private boolean open;
    private boolean checking;
    private Long storageBytes;

    SettingsSheet(Activity activity, SourceRepository repo, Prefs prefs, BlockStats stats, HealthChecker health,
                  Supplier<WebView> webView, boolean adblockConfigured, String appName, boolean tv) {
        super(activity);
        this.activity = activity;
        this.repo = repo;
        this.prefs = prefs;
        this.stats = stats;
        this.health = health;
        this.storage = new StorageManager(activity);
        this.webView = webView;
        this.adblockConfigured = adblockConfigured;
        this.appName = appName;
        this.tv = tv;
        this.sideCard = computeSideCard();
        Drawable ic = null;
        try {
            ic = activity.getPackageManager().getApplicationIcon(activity.getPackageName());
        } catch (Exception e) {
            // no icon: the About card simply omits it
        }
        icon = ic;
        health.addListener(this);

        scrim = new View(activity);
        scrim.setBackgroundColor(UiTokens.SCRIM);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(-1, -1));

        panel = new FrameLayout(activity);
        panel.setClickable(true);
        panel.setBackground(panelBackground());
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                float r = UiTokens.PANEL_CORNER * v.getResources().getDisplayMetrics().density;
                o.setRoundRect(0, 0, v.getWidth(), sideCard ? v.getHeight() : Math.round(v.getHeight() + r), r);
            }
        });
        panel.setClipToOutline(true);
        pages = new FrameLayout(activity);
        panel.addView(pages, new LayoutParams(-1, -1));
        addView(panel, panelParams());
    }

    /** Side card on TV, tablets and in landscape; bottom sheet on a portrait phone. Re-evaluated on every show (rotation). */
    private boolean computeSideCard() {
        Configuration conf = activity.getResources().getConfiguration();
        return tv || conf.screenWidthDp >= UiTokens.COMPACT_MAX_SW_DP || conf.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private Drawable panelBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(UiTokens.SURFACE);
        return d;
    }

    private LayoutParams panelParams() {
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        if (sideCard) {
            int margin = Ui.dp(activity, UiTokens.PANEL_MARGIN);
            int w = Math.min(Ui.dp(activity, tv ? TvLayout.panelWidthDp(activity.getResources().getConfiguration().screenWidthDp) : UiTokens.PANEL_W), dm.widthPixels - 2 * margin);
            LayoutParams lp = new LayoutParams(w, -1, Gravity.END);
            lp.setMargins(margin, margin, margin, margin);
            return lp;
        }
        return new LayoutParams(-1, UiTokens.sheetHeight(availableHeight()), Gravity.BOTTOM);
    }

    /** Height of the content area (below the status bar, above the navigation bar). */
    private int availableHeight() {
        View content = activity.findViewById(android.R.id.content);
        int h = content != null ? content.getHeight() - content.getPaddingTop() - content.getPaddingBottom() : 0;
        return h > 0 ? h : activity.getResources().getDisplayMetrics().heightPixels;
    }

    boolean isOpen() {
        return open;
    }

    // ---- presentation ----

    void show() {
        if (open) return;
        open = true;
        stack.clear();
        pages.removeAllViews();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(this, new FrameLayout.LayoutParams(-1, -1));
        sideCard = computeSideCard();
        panel.setLayoutParams(panelParams()); // re-fit to the current orientation and content area
        panel.invalidateOutline();
        Page root = buildPage(activity.getString(R.string.movy_settings), true,
                () -> SettingsModel.build(activity, inputs(), this));
        stack.add(root);
        pages.addView(root.view, new LayoutParams(-1, -1));
        rebuild();
        measureStorage();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();
        panel.post(() -> {
            springIn();
            View first = root.list.findViewWithTag("first");
            if (tv && first != null) first.requestFocus();
            if (tv) TvFocusTrap.ensureFocus(panel);
        });
        panel.setVisibility(INVISIBLE);
    }

    private void springIn() {
        panel.setVisibility(VISIBLE);
        if (sideCard) {
            panel.setTranslationX(panel.getWidth() + Ui.dp(activity, UiTokens.PANEL_MARGIN));
            spring(panel, DynamicAnimation.TRANSLATION_X, 0f, UiTokens.PANEL_STIFFNESS, UiTokens.PANEL_DAMPING, null);
        } else {
            panel.setTranslationY(panel.getHeight());
            spring(panel, DynamicAnimation.TRANSLATION_Y, 0f, UiTokens.PANEL_STIFFNESS, UiTokens.PANEL_DAMPING, null);
        }
    }

    void close() {
        if (!open) return;
        open = false;
        if (alert != null) alert.dismiss();
        scrim.animate().alpha(0f).setDuration(200).start();
        Runnable done = () -> {
            if (!open && getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        };
        if (sideCard) {
            spring(panel, DynamicAnimation.TRANSLATION_X, panel.getWidth() + Ui.dp(activity, UiTokens.PANEL_MARGIN), 520f, 1f, done);
        } else {
            spring(panel, DynamicAnimation.TRANSLATION_Y, panel.getHeight(), 520f, 1f, done);
        }
        WebView wv = webView.get();
        if (wv != null) wv.requestFocus();
    }

    /** Back: dismisses an alert, pops a sub-page, else closes. Returns false when the sheet is not open. */
    boolean handleBack() {
        if (!open) return false;
        if (alert != null && !alert.isDismissed()) {
            alert.dismiss();
        } else if (stack.size() > 1) {
            pop();
        } else {
            close();
        }
        return true;
    }

    private static void spring(View v, DynamicAnimation.ViewProperty prop, float to, float stiffness, float damping, Runnable end) {
        SpringAnimation s = new SpringAnimation(v, prop, to);
        s.setSpring(new SpringForce(to).setStiffness(stiffness).setDampingRatio(damping));
        s.setMinimumVisibleChange(0.5f);
        if (end != null) s.addEndListener((a, canceled, value, vel) -> end.run());
        s.start();
    }

    // ---- pages ----

    private Page buildPage(String title, boolean root, Supplier<List<SettingsSection>> sections) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(UiTokens.SURFACE);
        View grab = null;
        if (!sideCard && root) {
            grab = grabber();
            col.addView(grab);
        }
        View head = root ? largeTitle(title) : navBar(title);
        col.addView(head);
        if (grab != null) {
            attachDrag(grab);
            attachDrag(head);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, Ui.dp(activity, 32));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return new Page(title, sections, list, scroll, col);
    }

    /** Bottom sheet: dragging the grabber or the title area down follows the finger; a long or fast pull dismisses, else it springs back. */
    private void attachDrag(View handle) {
        final float[] down = new float[2];
        final long[] t0 = new long[1];
        final boolean[] moved = new boolean[1];
        final android.view.VelocityTracker[] vt = new android.view.VelocityTracker[1];
        final int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawY();
                    moved[0] = false;
                    vt[0] = android.view.VelocityTracker.obtain();
                    vt[0].addMovement(e);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    vt[0].addMovement(e);
                    float dy = e.getRawY() - down[0];
                    if (!moved[0] && Math.abs(dy) > slop) moved[0] = true;
                    if (moved[0]) panel.setTranslationY(Math.max(0f, dy));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    vt[0].addMovement(e);
                    vt[0].computeCurrentVelocity(1000);
                    float vy = vt[0].getYVelocity();
                    vt[0].recycle();
                    float dy = panel.getTranslationY();
                    boolean up = e.getActionMasked() == android.view.MotionEvent.ACTION_UP;
                    if (!moved[0]) {
                        if (up) v.performClick();
                    } else if (up && SheetDrag.shouldDismiss(dy, panel.getHeight(), vy, activity.getResources().getDisplayMetrics().density)) {
                        close();
                    } else {
                        spring(panel, DynamicAnimation.TRANSLATION_Y, 0f, UiTokens.PANEL_STIFFNESS, UiTokens.PANEL_DAMPING, null);
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    private View grabber() {
        FrameLayout f = new FrameLayout(activity);
        View g = new View(activity);
        g.setBackground(Ui.rounded(activity, 0xFF48484A, 3, 0, 0));
        f.addView(g, new LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 5), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        ((LayoutParams) g.getLayoutParams()).topMargin = Ui.dp(activity, 8);
        f.setMinimumHeight(Ui.dp(activity, 18));
        f.setOnClickListener(v -> close());
        f.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(activity, 18)));
        f.setContentDescription(activity.getString(R.string.movy_close));
        return f;
    }

    private View largeTitle(String title) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(activity, 32), Ui.dp(activity, sideCard ? 26 : 12), Ui.dp(activity, 14), Ui.dp(activity, 6));
        TextView t = IosViews.text(activity, title, 30, UiTokens.WHITE, false);
        t.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        t.setAccessibilityHeading(true);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = IosViews.text(activity, activity.getString(R.string.movy_done), 17, UiTokens.BLUE, true);
        done.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 10), Ui.dp(activity, 14), Ui.dp(activity, 10));
        done.setFocusable(true);
        done.setClickable(true);
        done.setBackground(IosViews.rowBackground(activity));
        done.setOnClickListener(v -> close());
        row.addView(done);
        return row;
    }

    private View navBar(String title) {
        FrameLayout bar = new FrameLayout(activity);
        bar.setMinimumHeight(Ui.dp(activity, 52));
        LinearLayout back = new LinearLayout(activity);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 14), 0);
        ImageView chev = new ImageView(activity);
        chev.setImageResource(R.drawable.ic_back);
        chev.setColorFilter(UiTokens.BLUE);
        back.addView(chev, new LinearLayout.LayoutParams(Ui.dp(activity, 26), Ui.dp(activity, 26)));
        back.addView(IosViews.text(activity, activity.getString(R.string.movy_settings), 17, UiTokens.BLUE, false));
        back.setFocusable(true);
        back.setClickable(true);
        back.setBackground(IosViews.rowBackground(activity));
        back.setContentDescription(activity.getString(R.string.movy_settings));
        back.setOnClickListener(v -> pop());
        bar.addView(back, new LayoutParams(-2, Ui.dp(activity, 52), Gravity.START));
        TextView t = IosViews.text(activity, title, 17, UiTokens.WHITE, true);
        t.setAccessibilityHeading(true);
        bar.addView(t, new LayoutParams(-2, -2, Gravity.CENTER));
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(activity, 52)));
        return bar;
    }

    private void push(Page next) {
        Page prev = stack.get(stack.size() - 1);
        stack.add(next);
        pages.addView(next.view, new LayoutParams(-1, -1));
        render(next);
        int w = pages.getWidth();
        next.view.setTranslationX(w);
        spring(next.view, DynamicAnimation.TRANSLATION_X, 0f, 420f, 0.92f, null);
        spring(prev.view, DynamicAnimation.TRANSLATION_X, -w * 0.3f, 420f, 0.92f, null);
        prev.view.animate().alpha(0.5f).setDuration(220).start();
        next.view.post(() -> {
            View first = next.list.findViewWithTag("first");
            if (tv && first != null) first.requestFocus();
        });
    }

    private void pop() {
        if (stack.size() < 2) return;
        Page top = stack.remove(stack.size() - 1);
        Page prev = stack.get(stack.size() - 1);
        int w = pages.getWidth();
        spring(top.view, DynamicAnimation.TRANSLATION_X, w, 420f, 1f, () -> pages.removeView(top.view));
        spring(prev.view, DynamicAnimation.TRANSLATION_X, 0f, 420f, 0.92f, null);
        prev.view.animate().alpha(1f).setDuration(220).start();
        render(prev);
    }

    // ---- model ----

    private SettingsModel.Inputs inputs() {
        return new SettingsModel.Inputs(repo, prefs, stats, health, adblockConfigured, AdblockSwitch.isOn(), appName,
                checking, storageBytes, assetCacheBytes(), icon);
    }

    private AssetCacheInterceptor assetCache() {
        return activity instanceof MainActivity ? ((MainActivity) activity).assetCache() : null;
    }

    private Long assetCacheBytes() {
        AssetCacheInterceptor ac = assetCache();
        return ac == null ? null : ac.sizeBytes();
    }

    private void render(Page p) {
        int y = p.scroll.getScrollY();
        int focusIdx = tv ? focusIndex(p) : -1; // a rebuild must not drop the TV focus (health / storage updates arrive late)
        p.list.removeAllViews();
        SettingsRows.render(activity, p.list, p.sections.get(), tv);
        p.scroll.post(() -> p.scroll.scrollTo(0, y));
        if (focusIdx >= 0) {
            java.util.ArrayList<View> now = new java.util.ArrayList<>();
            p.list.addFocusables(now, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
            if (!now.isEmpty()) now.get(Math.min(focusIdx, now.size() - 1)).requestFocus();
        }
    }

    private static int focusIndex(Page p) {
        View f = p.list.findFocus();
        if (f == null) return -1;
        java.util.ArrayList<View> all = new java.util.ArrayList<>();
        p.list.addFocusables(all, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
        return all.indexOf(f);
    }

    /**
     * TV keys while open: arrows stay inside the panel (the page behind never sees them), LEFT on a sub-page goes back,
     * and the panel always owns exactly one focused element. Returns true when the event was consumed.
     */
    boolean handleKey(KeyEvent e) {
        if (!open) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (alert != null && !alert.isDismissed()) return TvFocusTrap.handle(e, alert);
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && stack.size() > 1) {
            if (down && e.getRepeatCount() == 0) pop();
            return true;
        }
        return TvFocusTrap.handle(e, panel);
    }

    @Override
    public void rebuild() {
        for (Page p : stack) render(p);
    }

    private void measureStorage() {
        new Thread(() -> {
            long bytes = storage.usage();
            activity.runOnUiThread(() -> {
                storageBytes = bytes;
                if (open) rebuild();
            });
        }, "movy-storage").start();
    }

    // ---- actions ----

    @Override
    public void openDefaultSource() {
        push(buildPage(activity.getString(R.string.movy_default_source), false,
                () -> SettingsModel.defaultSourcePage(activity, inputs(), this)));
    }

    @Override
    public void checkSources() {
        checking = true;
        health.refreshNow();
        rebuild();
    }

    @Override
    public void onHealthChanged() {
        checking = false;
        if (open) rebuild();
    }

    @Override
    public void setAdblock(boolean on) {
        AdblockSwitch.set(on);
        prefs.setAdblockEnabled(on);
        rebuild();
    }

    @Override
    public void confirmClearCache() {
        alert = IosAlert.show(this, activity.getString(R.string.movy_clear_cache_title),
                activity.getString(R.string.movy_clear_cache_msg), activity.getString(R.string.movy_cancel),
                activity.getString(R.string.movy_clear_cache_confirm), true, () -> {
                    WebView wv = webView.get();
                    AssetCacheInterceptor ac = assetCache();
                    if (ac != null) ac.clear();
                    if (wv != null) storage.clearCacheOnly(wv);
                    Toast.makeText(activity, R.string.movy_cache_cleared, Toast.LENGTH_SHORT).show();
                    storageBytes = null;
                    measureStorage();
                    rebuild();
                }, tv);
    }

    @Override
    public void confirmSignOut() {
        alert = IosAlert.show(this, activity.getString(R.string.movy_sign_out_title),
                activity.getString(R.string.movy_sign_out_msg), activity.getString(R.string.movy_cancel),
                activity.getString(R.string.movy_sign_out_confirm), true, this::signOut, tv);
    }

    private void signOut() {
        WebView wv = webView.get();
        if (wv == null) return;
        storage.clearAllSiteData(wv);
        Source a = repo.active();
        close();
        if (a != null) repo.openStart(a.id);
        Toast.makeText(activity, R.string.movy_signed_out, Toast.LENGTH_SHORT).show();
    }
}
