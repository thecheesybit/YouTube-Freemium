package app.movy.shell;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Fullscreen video container: a clear downward drag takes the gesture from the player, the video follows the
 * finger (shrinking, backdrop fading) and, released far enough, slides away and exits fullscreen. Taps,
 * horizontal seeks and the system's top-edge swipe still reach the player and the OS.
 */
final class SwipeDownExit extends FrameLayout {
    private static final float EXIT_FRACTION = 0.18f;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private final Runnable exit;
    private final int slop;
    private final int edge;
    private final ColorDrawable backdrop = new ColorDrawable(0xFF000000);
    private float downX, downY;
    private boolean tracking, dragging, leaving;

    SwipeDownExit(Context ctx, Runnable exit) {
        super(ctx);
        this.exit = exit;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop() * 2;
        edge = Ui.dp(ctx, 32);
        super.setBackground(backdrop);
    }

    @Override
    public void setBackgroundColor(int color) {
        backdrop.setColor(color); // keep our drawable so the fade can drive its alpha
    }

    private View video() {
        return getChildCount() > 0 ? getChildAt(0) : null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (leaving) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                tracking = downY > edge && e.getPointerCount() == 1;
                dragging = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                tracking = false; // pinch-to-zoom belongs to the player
                break;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) break;
                float dy = e.getY() - downY, dx = Math.abs(e.getX() - downX);
                if (dy > slop && dy > dx * 1.5f) {
                    dragging = true; // the player now gets ACTION_CANCEL, so the lift is never read as a tap
                    return true;
                }
                if (dx > slop || dy < -slop) tracking = false;
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        View v = video();
        if (!dragging || v == null || leaving) return dragging || leaving;
        float dy = Math.max(0f, e.getY() - downY);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                follow(v, dy);
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                if (dy > getHeight() * EXIT_FRACTION) leave(v);
                else settle(v);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                settle(v);
                return true;
            default:
                return true;
        }
    }

    private void follow(View v, float dy) {
        float p = Math.min(1f, dy / Math.max(1f, getHeight()));
        v.setPivotX(getWidth() / 2f);
        v.setPivotY(0f);
        v.setTranslationY(dy);
        v.setScaleX(1f - 0.25f * p);
        v.setScaleY(1f - 0.25f * p);
        backdrop.setAlpha(Math.round(255 * (1f - 0.8f * p)));
    }

    private void settle(View v) {
        v.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(EASE)
                .setUpdateListener(a -> backdrop.setAlpha(Math.round(255 * (1f - 0.8f * Math.min(1f, v.getTranslationY() / Math.max(1f, getHeight()))))))
                .withEndAction(() -> backdrop.setAlpha(255)).start();
    }

    private void leave(View v) {
        leaving = true;
        v.animate().translationY(getHeight()).scaleX(0.7f).scaleY(0.7f).alpha(0f).setDuration(220).setInterpolator(EASE)
                .setUpdateListener(a -> backdrop.setAlpha(Math.round(255 * 0.2f * (1f - a.getAnimatedFraction()))))
                .withEndAction(() -> {
                    // Reset before the view is handed back to the page, then leave fullscreen.
                    v.setTranslationY(0f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setAlpha(1f);
                    exit.run();
                }).start();
    }
}
