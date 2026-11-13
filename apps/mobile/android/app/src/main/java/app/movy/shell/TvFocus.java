package app.movy.shell;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Shared 10-foot focus treatment: spring scale, brighter fill and a 3dp white ring. */
final class TvFocus {
    static final float SCALE = 1.07f;

    private TvFocus() {}

    /** Rounded fill that turns lighter with a 3dp white ring while focused. */
    static Drawable ringBackground(Context c, int fill, int focusFill, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, shape(c, focusFill, radiusDp, true));
        s.addState(new int[] {android.R.attr.state_pressed}, shape(c, focusFill, radiusDp, false));
        s.addState(new int[] {}, fill == 0 ? new ColorDrawable(0) : shape(c, fill, radiusDp, false));
        return s;
    }

    private static GradientDrawable shape(Context c, int fill, float radiusDp, boolean ring) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(Ui.dp(c, radiusDp));
        if (ring) g.setStroke(Ui.dp(c, 3), UiTokens.WHITE);
        return g;
    }

    /** Springs the view to {@link #SCALE} while it has focus. Keeps any existing focus listener's behaviour by chaining. */
    static void scaleOnFocus(View v) {
        final View.OnFocusChangeListener prev = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener((view, focused) -> {
            if (prev != null) prev.onFocusChange(view, focused);
            float to = focused ? SCALE : 1f;
            spring(view, DynamicAnimation.SCALE_X, to);
            spring(view, DynamicAnimation.SCALE_Y, to);
            view.setElevation(focused ? Ui.dp(view.getContext(), 12) : 0);
            if (focused && view.getParent() instanceof android.view.ViewGroup) view.bringToFront();
        });
    }

    private static void spring(View v, DynamicAnimation.ViewProperty p, float to) {
        SpringAnimation a = new SpringAnimation(v, p, to);
        a.setSpring(new SpringForce(to).setStiffness(UiTokens.SOFT_STIFFNESS).setDampingRatio(UiTokens.SOFT_DAMPING));
        a.setMinimumVisibleChange(0.002f);
        a.start();
    }
}
