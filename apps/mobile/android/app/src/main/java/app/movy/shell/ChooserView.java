package app.movy.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Native source chooser in the iOS look: large title, an inset-grouped list of squircle source tiles with a checkmark
 * for the selection, an "Always open this source" switch and a full-width primary button. D-pad navigable.
 * Shows names only, never URLs or hosts.
 */
public final class ChooserView extends FrameLayout {
    public interface Listener {
        void onChosen(Source source, boolean alwaysOpen);
    }

    private static final int WIDE_MIN_DP = 700;

    private final Listener listener;
    private final LinearLayout column;
    private final LinearLayout footer;
    private final List<View> rows = new ArrayList<>();
    private final List<View> marks = new ArrayList<>();
    private List<Source> sources = new ArrayList<>();
    private String selectedId;
    private boolean always;
    private IosSwitch alwaysSwitch;
    private TvChooserLayout tvLayout;

    public ChooserView(Context ctx, LiveConfig config, String preselectedId, boolean alwaysChecked, Listener listener) {
        super(ctx);
        this.listener = listener;
        this.selectedId = preselectedId;
        this.always = alwaysChecked;
        setBackgroundColor(UiTokens.BLACK);
        setClickable(true);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, new LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        footer = new LinearLayout(ctx); // pinned: the primary button stays on screen however short the window is
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), Ui.dp(ctx, 16));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 20);
        column.setPadding(pad, Ui.dp(ctx, 16), pad, Ui.dp(ctx, 8));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        build(config, true);
    }

    /** Live re-render when the remote config changes while the chooser is up. */
    public void setConfig(LiveConfig next) {
        build(next, false);
    }

    /** Short windows (phone landscape): tighter spacing. */
    private boolean compactHeight() {
        return getResources().getConfiguration().screenHeightDp < 480;
    }

    private LinearLayout.LayoutParams capped(int topDp) {
        int screenDp = getResources().getConfiguration().screenWidthDp;
        // never wider than the screen minus the 20 dp side padding (a fixed 460 dp overflowed phones)
        int widthDp = Math.min(screenDp >= WIDE_MIN_DP ? 520 : 460, Math.max(240, screenDp - 40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(getContext(), widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(getContext(), topDp);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    /** 10-foot variant: a row of large cards, then the options row. */
    private void buildTv(LiveConfig cfg, boolean animate) {
        Context ctx = getContext();
        this.sources = cfg.enabledSources();
        if (find(selectedId) == null) selectedId = cfg.recommended().id;
        column.removeAllViews();
        footer.removeAllViews();
        rows.clear();
        marks.clear();
        column.setPadding(Ui.safeH(ctx), Ui.safeV(ctx), Ui.safeH(ctx), Ui.safeV(ctx));
        column.setClipToPadding(false);
        column.setClipChildren(false);
        ((ViewGroup) column.getParent()).setClipChildren(false);
        ((ViewGroup) column.getParent()).setClipToPadding(false);
        column.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1f));
        tvLayout = new TvChooserLayout(ctx, cfg, selectedId, always, new TvChooserLayout.Callbacks() {
            @Override public void onPick(Source source) {
                selectedId = source.id;
                tvLayout.select(selectedId);
                confirm();
            }
            @Override public void onAlwaysChanged(boolean a) { always = a; }
            @Override public void onContinue() { confirm(); }
        }, v -> { if (animate) Ui.fadeIn(v, 80, Ui.dp(ctx, 10)); });
        column.addView(tvLayout.root, new LinearLayout.LayoutParams(-1, -2));
        column.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1f));
        View focus = tvLayout.initialFocus(selectedId);
        focus.post(focus::requestFocus);
    }

    private void build(LiveConfig cfg, boolean animate) {
        Context ctx = getContext();
        if (Ui.isTv(ctx)) {
            buildTv(cfg, animate);
            return;
        }
        this.sources = cfg.enabledSources();
        if (find(selectedId) == null) selectedId = cfg.recommended().id;
        column.removeAllViews();
        footer.removeAllViews();
        rows.clear();
        marks.clear();
        column.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1f)); // top spacer

        TextView title = IosViews.text(ctx, cfg.chooserTitle, 30, UiTokens.WHITE, false);
        title.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        title.setAccessibilityHeading(true);
        column.addView(title, capped(0));
        if (animate) Ui.fadeIn(title, 60, Ui.dp(ctx, 10));
        if (!cfg.chooserSubtitle.isEmpty()) {
            TextView sub = IosViews.text(ctx, cfg.chooserSubtitle, 15, UiTokens.LABEL_SECONDARY, false);
            column.addView(sub, capped(6));
            if (animate) Ui.fadeIn(sub, 120, Ui.dp(ctx, 10));
        }

        LinearLayout list = IosViews.groupCard(ctx, UiTokens.SURFACE);
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) list.addView(IosViews.separator(ctx, 16 + 44 + 12));
            list.addView(sourceRow(ctx, cfg, sources.get(i)), new LinearLayout.LayoutParams(-1, -2));
        }
        column.addView(list, capped(compactHeight() ? 14 : 22));
        if (animate) Ui.fadeIn(list, 180, Ui.dp(ctx, 24));

        LinearLayout opt = IosViews.groupCard(ctx, UiTokens.SURFACE);
        opt.addView(alwaysRow(ctx, cfg), new LinearLayout.LayoutParams(-1, -2));
        column.addView(opt, capped(16));
        if (animate) Ui.fadeIn(opt, 240, Ui.dp(ctx, 16));

        TextView cta = IosViews.primaryButton(ctx, cfg.chooserCta, UiTokens.BLUE);
        cta.setOnClickListener(v -> confirm());
        footer.addView(cta, capped(0));
        if (animate) Ui.fadeIn(cta, 300, Ui.dp(ctx, 16));
        column.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1f)); // bottom spacer
        refresh();
        View sel = rows.isEmpty() ? cta : rows.get(indexOf(selectedId));
        sel.post(sel::requestFocus);
    }

    private View sourceRow(Context ctx, LiveConfig cfg, Source s) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(ctx, 68));
        row.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 10));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(IosViews.rowBackground(ctx));
        row.setTag(s.id);

        int d = Ui.dp(ctx, 44);
        row.addView(new IosViews.Tile(ctx, s), new LinearLayout.LayoutParams(d, d));
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(IosViews.text(ctx, s.name, 17, UiTokens.WHITE, false));
        boolean rec = s.id.equals(cfg.recommendedSource);
        if (rec) text.addView(IosViews.text(ctx, cfg.chooserRecommended, 13, UiTokens.LABEL_SECONDARY, false));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = Ui.dp(ctx, 12);
        row.addView(text, tp);

        ImageView mark = new ImageView(ctx);
        mark.setImageResource(R.drawable.ic_check);
        mark.setColorFilter(UiTokens.BLUE);
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24)));
        marks.add(mark);
        row.setContentDescription(rec ? s.name + ", " + cfg.chooserRecommended : s.name);
        row.setOnClickListener(v -> {
            selectedId = s.id;
            refresh();
        });
        rows.add(row);
        return row;
    }

    private View alwaysRow(Context ctx, LiveConfig cfg) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(ctx, 56));
        row.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 6), Ui.dp(ctx, 16), Ui.dp(ctx, 6));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(IosViews.rowBackground(ctx));
        row.addView(IosViews.text(ctx, cfg.chooserAlways, 17, UiTokens.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1f));
        alwaysSwitch = new IosSwitch(ctx);
        alwaysSwitch.setChecked(always, false);
        row.addView(alwaysSwitch);
        row.setContentDescription(cfg.chooserAlways);
        row.setOnClickListener(v -> {
            alwaysSwitch.toggle();
            always = alwaysSwitch.isChecked();
        });
        return row;
    }

    private void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            boolean sel = rows.get(i).getTag().equals(selectedId);
            marks.get(i).setVisibility(sel ? VISIBLE : INVISIBLE);
            rows.get(i).setSelected(sel);
        }
    }

    private Source find(String id) {
        for (Source s : sources) if (s.id.equals(id)) return s;
        return null;
    }

    private int indexOf(String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).id.equals(id)) return i;
        return 0;
    }

    private void confirm() {
        Source s = find(selectedId);
        if (s != null) listener.onChosen(s, always);
    }

    /** Fades out and removes itself. */
    public void dismiss(Runnable after) {
        animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            if (after != null) after.run();
        }).start();
    }
}
