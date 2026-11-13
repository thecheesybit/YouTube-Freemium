package app.movy.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** iOS-style alert: centred 14dp card with a title, a message and two hairline-separated buttons. */
final class IosAlert extends FrameLayout {
    private final LinearLayout card;
    private boolean dismissed;

    private IosAlert(Context ctx, String title, String message, String cancel, String confirm, boolean destructive,
                     Runnable onConfirm, boolean tv) {
        super(ctx);
        setBackgroundColor(0x66000000);
        setClickable(true);
        setOnClickListener(v -> dismiss());

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(ctx, UiTokens.CARD, 14, 0, 0));
        IosViews.clipRounded(card, 14);
        card.setClickable(true);
        TextView t = IosViews.text(ctx, title, 17, UiTokens.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setAccessibilityHeading(true);
        t.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 20), Ui.dp(ctx, 20), Ui.dp(ctx, 4));
        card.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView m = IosViews.text(ctx, message, 13, UiTokens.WHITE, false);
        m.setGravity(Gravity.CENTER);
        m.setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 20), Ui.dp(ctx, 18));
        card.addView(m, new LinearLayout.LayoutParams(-1, -2));
        card.addView(IosViews.separator(ctx, 0));

        LinearLayout row = new LinearLayout(ctx);
        TextView no = button(ctx, cancel, UiTokens.BLUE, tv);
        no.setOnClickListener(v -> dismiss());
        TextView yes = button(ctx, confirm, destructive ? UiTokens.RED : UiTokens.BLUE, tv);
        yes.setOnClickListener(v -> {
            dismiss();
            onConfirm.run();
        });
        row.addView(no, new LinearLayout.LayoutParams(0, -2, 1f));
        View sep = new View(ctx);
        sep.setBackgroundColor(UiTokens.SEPARATOR);
        row.addView(sep, new LinearLayout.LayoutParams(Math.max(1, Ui.dp(ctx, 1) / 2), -1));
        row.addView(yes, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addView(card, new LayoutParams(Ui.dp(ctx, tv ? 420 : 280), -2, Gravity.CENTER));
        no.post(no::requestFocus);
    }

    private static TextView button(Context ctx, String label, int color, boolean tv) {
        TextView b = IosViews.text(ctx, label, 17, color, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(Ui.dp(ctx, tv ? 56 : 46));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(IosViews.rowBackground(ctx));
        return b;
    }

    /** Shows the alert over {@code host}. Returns it so the host can dismiss it on Back. */
    static IosAlert show(ViewGroup host, String title, String message, String cancel, String confirm,
                         boolean destructive, Runnable onConfirm, boolean tv) {
        IosAlert a = new IosAlert(host.getContext(), title, message, cancel, confirm, destructive, onConfirm, tv);
        host.addView(a, new FrameLayout.LayoutParams(-1, -1));
        a.setAlpha(0f);
        a.animate().alpha(1f).setDuration(140).start();
        a.card.setScaleX(1.12f);
        a.card.setScaleY(1.12f);
        spring(a.card, DynamicAnimation.SCALE_X);
        spring(a.card, DynamicAnimation.SCALE_Y);
        return a;
    }

    private static void spring(View v, DynamicAnimation.ViewProperty p) {
        SpringAnimation s = new SpringAnimation(v, p, 1f);
        s.setSpring(new SpringForce(1f).setStiffness(UiTokens.PANEL_STIFFNESS * 1.6f).setDampingRatio(0.8f));
        s.start();
    }

    boolean isDismissed() {
        return dismissed;
    }

    void dismiss() {
        if (dismissed) return;
        dismissed = true;
        animate().alpha(0f).setDuration(120).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        }).start();
    }
}
