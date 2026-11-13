package app.movy.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Renders {@link SettingsSection}s as iOS inset-grouped lists (rounded 12dp cards, 0.5dp inset separators). */
final class SettingsRows {
    private static final int SIDE_DP = 16;
    private static final int TEXT_INSET_DP = 16;

    private SettingsRows() {}

    /** Fills {@code list} with the sections. TV gets taller rows. The first focusable row is tagged "first". */
    static void render(Context ctx, LinearLayout list, List<SettingsSection> sections, boolean tv) {
        boolean first = true;
        for (SettingsSection sec : sections) {
            if (sec.header != null) list.addView(header(ctx, sec.header));
            LinearLayout card = IosViews.groupCard(ctx, UiTokens.CARD);
            for (int i = 0; i < sec.rows.size(); i++) {
                SettingsRow r = sec.rows.get(i);
                View v = focusableOnTv(row(ctx, r, tv), tv);
                if (first && v.isFocusable()) {
                    v.setTag("first");
                    first = false;
                }
                if (i > 0 && r.type != SettingsRow.Type.ABOUT) {
                    card.addView(IosViews.separator(ctx, hasLeading(r) ? UiTokens.SEPARATOR_INSET : TEXT_INSET_DP));
                }
                card.addView(v, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.leftMargin = cp.rightMargin = Ui.dp(ctx, SIDE_DP);
            list.addView(card, cp);
            if (sec.footer != null) list.addView(footer(ctx, sec.footer));
            if (sec.footerAction != null) list.addView(footerAction(ctx, sec, tv));
            if (sec.header == null && sec.footer == null) list.addView(new View(ctx), new LinearLayout.LayoutParams(1, Ui.dp(ctx, 8)));
        }
    }

    private static boolean hasLeading(SettingsRow r) {
        return r.type == SettingsRow.Type.STATUS || r.glyph != 0;
    }

    private static View header(Context ctx, String title) {
        TextView t = IosViews.text(ctx, title.toUpperCase(Locale.getDefault()), 13, UiTokens.LABEL_SECONDARY, false);
        t.setPadding(Ui.dp(ctx, SIDE_DP + 16), Ui.dp(ctx, 26), Ui.dp(ctx, 16), Ui.dp(ctx, 7));
        t.setAccessibilityHeading(true);
        return t;
    }

    private static View footer(Context ctx, String text) {
        TextView t = IosViews.text(ctx, text, 13, UiTokens.LABEL_SECONDARY, false);
        t.setPadding(Ui.dp(ctx, SIDE_DP + 16), Ui.dp(ctx, 8), Ui.dp(ctx, SIDE_DP + 16), 0);
        return t;
    }

    private static View footerAction(Context ctx, SettingsSection sec, boolean tv) {
        TextView t = IosViews.text(ctx, sec.footerAction, 15, UiTokens.BLUE, false);
        t.setPadding(Ui.dp(ctx, SIDE_DP + 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 10));
        t.setMinHeight(Ui.dp(ctx, tv ? 48 : 44));
        t.setGravity(Gravity.CENTER_VERTICAL);
        tappable(t, sec.onFooterAction);
        return t;
    }

    private static void tappable(View v, Runnable click) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(IosViews.rowBackground(v.getContext()));
        v.setOnClickListener(x -> click.run());
    }

    private static LinearLayout rowBox(Context ctx, boolean tv) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(Ui.dp(ctx, tv ? 56 : UiTokens.ROW_MIN));
        l.setPadding(Ui.dp(ctx, SIDE_DP), Ui.dp(ctx, 6), Ui.dp(ctx, SIDE_DP), Ui.dp(ctx, 6));
        return l;
    }

    private static View row(Context ctx, SettingsRow r, boolean tv) {
        switch (r.type) {
            case NAV: return nav(ctx, r, tv);
            case SWITCH: return toggle(ctx, r, tv);
            case STATUS: return status(ctx, r, tv);
            case ACTION: return action(ctx, r, tv);
            case CHECK: return check(ctx, r, tv);
            case ABOUT: return about(ctx, r);
            default: return value(ctx, r, tv);
        }
    }

    /** TV: every row can take focus so the D-pad reaches (and scrolls to) the whole list. */
    private static View focusableOnTv(View v, boolean tv) {
        if (tv && !v.isFocusable()) {
            v.setFocusable(true);
            v.setBackground(IosViews.rowBackground(v.getContext()));
        }
        return v;
    }

    private static TextView label(Context ctx, String s, int color) {
        return IosViews.text(ctx, s, 17, color, false);
    }

    private static void leading(Context ctx, LinearLayout l, SettingsRow r) {
        if (r.glyph != 0) l.addView(IosViews.badge(ctx, r.color, r.glyph));
    }

    private static View nav(Context ctx, SettingsRow r, boolean tv) {
        LinearLayout l = rowBox(ctx, tv);
        leading(ctx, l, r);
        l.addView(label(ctx, r.title, UiTokens.WHITE), growFor(ctx, r));
        l.addView(IosViews.text(ctx, r.value, 17, UiTokens.LABEL_SECONDARY, false));
        ImageView chev = new ImageView(ctx);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setColorFilter(0xFF636366);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        l.addView(chev, new LinearLayout.LayoutParams(Ui.dp(ctx, 22), Ui.dp(ctx, 22)));
        tappable(l, r.onClick);
        l.setContentDescription(r.title + ", " + r.value);
        return l;
    }

    private static LinearLayout.LayoutParams growFor(Context ctx, SettingsRow r) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (hasLeading(r)) lp.leftMargin = Ui.dp(ctx, 12);
        return lp;
    }

    private static View toggle(Context ctx, SettingsRow r, boolean tv) {
        LinearLayout l = rowBox(ctx, tv);
        leading(ctx, l, r);
        l.addView(label(ctx, r.title, UiTokens.WHITE), growFor(ctx, r));
        IosSwitch sw = new IosSwitch(ctx);
        sw.setChecked(r.checked, false);
        l.addView(sw);
        tappable(l, () -> {
            sw.toggle();
            r.onChange.accept(sw.isChecked());
        });
        l.setContentDescription(r.title);
        l.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(sw.isChecked());
            }
        });
        return l;
    }

    private static View value(Context ctx, SettingsRow r, boolean tv) {
        LinearLayout l = rowBox(ctx, tv);
        leading(ctx, l, r);
        l.addView(label(ctx, r.title, UiTokens.WHITE), growFor(ctx, r));
        l.addView(IosViews.text(ctx, r.value, 17, UiTokens.LABEL_SECONDARY, false));
        l.setContentDescription(r.title + ", " + r.value);
        return l;
    }

    private static View status(Context ctx, SettingsRow r, boolean tv) {
        LinearLayout l = rowBox(ctx, tv);
        l.addView(IosViews.dotSlot(ctx, r.color));
        l.addView(label(ctx, r.title, UiTokens.WHITE), growFor(ctx, r));
        l.addView(IosViews.text(ctx, r.value, 17, UiTokens.LABEL_SECONDARY, false));
        l.setContentDescription(r.title + ", " + r.value);
        return l;
    }

    private static View action(Context ctx, SettingsRow r, boolean tv) {
        LinearLayout l = rowBox(ctx, tv);
        l.addView(label(ctx, r.title, r.tint), new LinearLayout.LayoutParams(0, -2, 1f));
        tappable(l, r.onClick);
        l.setContentDescription(r.title);
        return l;
    }

    private static View check(Context ctx, SettingsRow r, boolean tv) {
        LinearLayout l = rowBox(ctx, tv);
        l.addView(label(ctx, r.title, UiTokens.WHITE), new LinearLayout.LayoutParams(0, -2, 1f));
        if (r.checked) {
            ImageView ok = new ImageView(ctx);
            ok.setImageResource(R.drawable.ic_check);
            ok.setColorFilter(UiTokens.BLUE);
            ok.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            l.addView(ok, new LinearLayout.LayoutParams(Ui.dp(ctx, 22), Ui.dp(ctx, 22)));
        }
        tappable(l, r.onClick);
        l.setContentDescription(r.title);
        l.setSelected(r.checked);
        return l;
    }

    private static View about(Context ctx, SettingsRow r) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 22), Ui.dp(ctx, 16), Ui.dp(ctx, 22));
        if (r.icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(r.icon);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            IosViews.clipRounded(iv, 14);
            l.addView(iv, new LinearLayout.LayoutParams(Ui.dp(ctx, 64), Ui.dp(ctx, 64)));
        }
        TextView name = IosViews.text(ctx, r.title, 20, UiTokens.WHITE, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = Ui.dp(ctx, 12);
        l.addView(name, np);
        TextView tag = IosViews.text(ctx, r.value, 15, UiTokens.LABEL_SECONDARY, false);
        tag.setGravity(Gravity.CENTER);
        l.addView(tag);
        for (String line : r.lines) {
            TextView t = IosViews.text(ctx, line, 13, UiTokens.LABEL_SECONDARY, false);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(ctx, 4);
            l.addView(t, lp);
        }
        return l;
    }
}
