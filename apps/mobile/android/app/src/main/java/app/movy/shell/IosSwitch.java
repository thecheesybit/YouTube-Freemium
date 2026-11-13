package app.movy.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Switch;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * iOS-style switch: 51x31dp track (green when on, #39393D when off) and a white shadowed knob that moves with a
 * spring. Not clickable by itself; the row that hosts it toggles it.
 */
public final class IosSwitch extends View {
    interface OnChange {
        void onChange(boolean checked);
    }

    private static final FloatPropertyCompat<IosSwitch> FRACTION = new FloatPropertyCompat<IosSwitch>("fraction") {
        @Override public float getValue(IosSwitch v) { return v.fraction; }
        @Override public void setValue(IosSwitch v, float x) { v.fraction = x; v.invalidate(); }
    };

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final SpringAnimation spring;
    private final float dp;
    private float fraction;
    private boolean checked;
    private int onColor = UiTokens.GREEN;
    private OnChange listener;

    public IosSwitch(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        knob.setColor(UiTokens.WHITE);
        knob.setShadowLayer(3 * dp, 0, 1.5f * dp, 0x40000000);
        spring = new SpringAnimation(this, FRACTION);
        spring.setSpring(new SpringForce().setStiffness(650f).setDampingRatio(0.62f));
        spring.setMinimumVisibleChange(0.002f);
        setFocusable(false);
        setClickable(false);
    }

    void setOnColor(int color) {
        onColor = color;
        invalidate();
    }

    void setOnChange(OnChange l) {
        listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    /** Sets the state without notifying; animates with the spring unless {@code animate} is false. */
    void setChecked(boolean on, boolean animate) {
        checked = on;
        if (animate && isAttachedToWindow()) {
            spring.animateToFinalPosition(on ? 1f : 0f);
        } else {
            spring.cancel();
            fraction = on ? 1f : 0f;
            invalidate();
        }
    }

    void toggle() {
        setChecked(!checked, true);
        if (listener != null) listener.onChange(checked);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(Math.round(UiTokens.SWITCH_W * dp), Math.round(UiTokens.SWITCH_H * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float f = Math.max(0f, Math.min(1f, fraction));
        track.setColor(mix(UiTokens.SWITCH_OFF, onColor, f));
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, h / 2, h / 2, track);
        float d = h - 4 * dp;
        float travel = w - d - 4 * dp;
        float cx = 2 * dp + d / 2 + travel * fraction;
        c.drawCircle(cx, h / 2, d / 2, knob);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Switch.class.getName());
        info.setCheckable(true);
        info.setChecked(checked);
    }
}
