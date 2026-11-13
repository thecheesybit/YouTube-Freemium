package app.movy.shell;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 10-foot source chooser: a centred title, a horizontal row of large source cards (CENTER on a card chooses and continues),
 * then one row below with "Always open this source" and Continue, both reachable with DOWN. Sizes come from {@link TvLayout}
 * so it fits 640x360, 960x540 and 1280x720 dp. Focus is trapped: LEFT/RIGHT stop at the ends, nothing escapes.
 */
final class TvChooserLayout {
    interface Callbacks {
        void onPick(Source source);

        void onAlwaysChanged(boolean always);

        void onContinue();
    }

    final LinearLayout root;
    final List<View> cards = new ArrayList<>();
    final View alwaysRow;
    final TextView cta;
    final IosSwitch alwaysSwitch;
    private final List<View> marks = new ArrayList<>();
    private final List<Source> sources;

    TvChooserLayout(Context ctx, LiveConfig cfg, String selectedId, boolean always, Callbacks cb, Consumer<View> animateIn) {
        android.content.res.Configuration conf = ctx.getResources().getConfiguration();
        int sw = conf.screenWidthDp;
        int sh = conf.screenHeightDp;
        boolean dense = TvLayout.dense(sh);
        sources = cfg.enabledSources();
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        TextView title = IosViews.text(ctx, cfg.chooserTitle, dense ? 24 : 30, UiTokens.WHITE, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        root.addView(title, new LinearLayout.LayoutParams(-2, -2));
        animateIn.accept(title);
        if (!cfg.chooserSubtitle.isEmpty()) {
            TextView sub = IosViews.text(ctx, cfg.chooserSubtitle, 15, UiTokens.LABEL_SECONDARY, false);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.topMargin = Ui.dp(ctx, 6);
            root.addView(sub, sp);
            animateIn.accept(sub);
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int cardW = Ui.dp(ctx, TvLayout.cardWidthDp(sw, sources.size()));
        int cardH = Ui.dp(ctx, dense ? 140 : sh >= 500 ? 200 : 170);
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            View card = card(ctx, cfg, s, cardW, cardH, dense, cb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) lp.leftMargin = Ui.dp(ctx, TvLayout.CARD_GAP_DP);
            row.addView(card, lp);
            cards.add(card);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = Ui.dp(ctx, dense ? 16 : 28);
        root.addView(row, rp);
        animateIn.accept(row);

        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setClipChildren(false);
        int rowW = cardW * sources.size() + Ui.dp(ctx, TvLayout.CARD_GAP_DP) * Math.max(0, sources.size() - 1);
        alwaysSwitch = new IosSwitch(ctx);
        alwaysSwitch.setChecked(always, false);
        alwaysRow = alwaysRow(ctx, cfg, cb);
        bottom.addView(alwaysRow, new LinearLayout.LayoutParams(0, -2, 1f));
        cta = IosViews.primaryButton(ctx, cfg.chooserCta, UiTokens.BLUE);
        cta.setOnClickListener(v -> cb.onContinue());
        TvFocus.scaleOnFocus(cta);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(ctx, 220), -2);
        cp.leftMargin = Ui.dp(ctx, TvLayout.CARD_GAP_DP);
        bottom.addView(cta, cp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Math.max(rowW, Ui.dp(ctx, 480)), -2);
        bp.topMargin = Ui.dp(ctx, dense ? 16 : 28);
        root.addView(bottom, bp);
        animateIn.accept(bottom);
        wireFocus(selectedId);
        select(selectedId);
    }

    private View card(Context ctx, LiveConfig cfg, Source s, int w, int h, boolean dense, Callbacks cb) {
        FrameLayout card = new FrameLayout(ctx);
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(s.id);
        card.setBackground(TvFocus.ringBackground(ctx, UiTokens.SURFACE, 0xFF3A3A3C, 22));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        card.addView(col, new FrameLayout.LayoutParams(-1, -1));
        int tile = Math.round(Math.min(w * 0.46f, h * 0.46f));
        col.addView(new IosViews.Tile(ctx, s), new LinearLayout.LayoutParams(tile, tile));
        TextView name = IosViews.text(ctx, s.name, 18, UiTokens.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = Ui.dp(ctx, dense ? 8 : 14);
        np.leftMargin = Ui.dp(ctx, 10);
        np.rightMargin = Ui.dp(ctx, 10);
        col.addView(name, np);
        boolean rec = s.id.equals(cfg.recommendedSource);
        if (rec) {
            TextView r = IosViews.text(ctx, cfg.chooserRecommended, 15, UiTokens.LABEL_SECONDARY, false);
            r.setGravity(Gravity.CENTER);
            col.addView(r, new LinearLayout.LayoutParams(-2, -2));
        }
        ImageView mark = new ImageView(ctx);
        mark.setImageResource(R.drawable.ic_check);
        mark.setColorFilter(UiTokens.BLUE);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int m = Ui.dp(ctx, 28);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(m, m, Gravity.TOP | Gravity.END);
        mp.topMargin = Ui.dp(ctx, 10);
        mp.rightMargin = Ui.dp(ctx, 10);
        card.addView(mark, mp);
        marks.add(mark);
        card.setContentDescription(rec ? s.name + ", " + cfg.chooserRecommended : s.name);
        TvFocus.scaleOnFocus(card);
        card.setOnClickListener(v -> cb.onPick(s)); // CENTER: choose and continue
        return card;
    }

    private View alwaysRow(Context ctx, LiveConfig cfg, Callbacks cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 12), Ui.dp(ctx, 22), Ui.dp(ctx, 12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(TvFocus.ringBackground(ctx, UiTokens.SURFACE, 0xFF3A3A3C, 16));
        row.addView(IosViews.text(ctx, cfg.chooserAlways, 17, UiTokens.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(alwaysSwitch);
        row.setContentDescription(cfg.chooserAlways);
        row.setOnClickListener(v -> {
            alwaysSwitch.toggle();
            cb.onAlwaysChanged(alwaysSwitch.isChecked());
        });
        TvFocus.scaleOnFocus(row);
        return row;
    }

    /** Explicit next-focus targets so focus never leaves the three groups (cards, options row, buttons). */
    private void wireFocus(String selectedId) {
        for (View c : cards) c.setId(View.generateViewId());
        alwaysRow.setId(View.generateViewId());
        cta.setId(View.generateViewId());
        for (int i = 0; i < cards.size(); i++) {
            View c = cards.get(i);
            c.setNextFocusLeftId(i == 0 ? c.getId() : cards.get(i - 1).getId());
            c.setNextFocusRightId(i == cards.size() - 1 ? c.getId() : cards.get(i + 1).getId());
            c.setNextFocusUpId(c.getId());
            c.setNextFocusDownId(alwaysRow.getId());
        }
        alwaysRow.setNextFocusLeftId(alwaysRow.getId());
        alwaysRow.setNextFocusRightId(cta.getId());
        alwaysRow.setNextFocusDownId(alwaysRow.getId());
        cta.setNextFocusLeftId(alwaysRow.getId());
        cta.setNextFocusRightId(cta.getId());
        cta.setNextFocusDownId(cta.getId());
        View sel = cards.isEmpty() ? cta : cards.get(Math.max(0, indexOf(selectedId)));
        alwaysRow.setNextFocusUpId(sel.getId());
        cta.setNextFocusUpId(sel.getId());
    }

    int indexOf(String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).id.equals(id)) return i;
        return 0;
    }

    void select(String id) {
        for (int i = 0; i < cards.size(); i++) {
            boolean sel = cards.get(i).getTag().equals(id);
            marks.get(i).setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        }
    }

    View initialFocus(String selectedId) {
        return cards.isEmpty() ? cta : cards.get(indexOf(selectedId));
    }
}
