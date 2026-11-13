package app.movy.shell;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.function.Supplier;

/**
 * TV pointer mode: a native on-screen arrow moved with the D-pad (accelerating while held), scrolling the page when pushed
 * against a screen edge; CENTER clicks at the pointer through a mouse MotionEvent. Entered with a long press of BACK, left
 * the same way or with a short BACK. For elements spatial navigation cannot reach.
 */
final class TvCursor {
    private static final long TICK_MS = 16;
    private static final long SCROLL_EVERY_MS = 70;

    private final Activity activity;
    private final ViewGroup content;
    private final Supplier<WebView> webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PointerView view;
    private final boolean[] held = new boolean[4]; // left, up, right, down
    private final long[] heldSince = new long[4];
    private CursorMath math;
    private boolean active;
    private boolean ticking;
    private long lastTick;
    private long lastScroll;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticking = false;
            step();
            if (anyHeld()) schedule();
        }
    };

    TvCursor(Activity activity, ViewGroup content, Supplier<WebView> webView) {
        this.activity = activity;
        this.content = content;
        this.webView = webView;
        view = new PointerView(activity);
    }

    boolean isActive() {
        return active;
    }

    void toggle() {
        if (active) exit(); else enter();
    }

    private void enter() {
        if (active) return;
        active = true;
        int w = content.getWidth() > 0 ? content.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int h = content.getHeight() > 0 ? content.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        math = new CursorMath(w, h);
        view.sync(math);
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.bringToFront();
        Toast.makeText(activity, R.string.movy_cursor_on, Toast.LENGTH_SHORT).show();
    }

    void exit() {
        if (!active) return;
        active = false;
        java.util.Arrays.fill(held, false);
        main.removeCallbacks(tick);
        ticking = false;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        Toast.makeText(activity, R.string.movy_cursor_off, Toast.LENGTH_SHORT).show();
    }

    /** Consumes D-pad, CENTER and BACK while the pointer is active. Returns true when handled. */
    boolean handle(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int dir = dirIndex(code);
        if (dir >= 0) {
            if (down && !held[dir]) {
                held[dir] = true;
                heldSince[dir] = SystemClock.uptimeMillis();
                lastTick = heldSince[dir];
                step();
                schedule();
            } else if (!down) {
                held[dir] = false;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (down && e.getRepeatCount() == 0) click();
            return true;
        }
        return false; // BACK and everything else are routed by the controller
    }

    private static int dirIndex(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0;
            case KeyEvent.KEYCODE_DPAD_UP: return 1;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 2;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 3;
            default: return -1;
        }
    }

    private boolean anyHeld() {
        for (boolean b : held) if (b) return true;
        return false;
    }

    private void schedule() {
        if (ticking) return;
        ticking = true;
        main.postDelayed(tick, TICK_MS);
    }

    private void step() {
        if (math == null) return;
        long now = SystemClock.uptimeMillis();
        long dt = Math.max(1, Math.min(48, now - lastTick));
        lastTick = now;
        int dx = (held[2] ? 1 : 0) - (held[0] ? 1 : 0);
        int dy = (held[3] ? 1 : 0) - (held[1] ? 1 : 0);
        if (dx == 0 && dy == 0) return;
        long heldMs = 0;
        for (int i = 0; i < 4; i++) if (held[i]) heldMs = Math.max(heldMs, now - heldSince[i]);
        boolean moved = math.step(dx, dy, heldMs, dt);
        float margin = 28 * activity.getResources().getDisplayMetrics().density;
        int sy = math.edgeScrollY(dy, margin);
        int sx = math.edgeScrollX(dx, margin);
        if ((sx != 0 || sy != 0) && now - lastScroll >= SCROLL_EVERY_MS) {
            lastScroll = now;
            scroll(sx, sy);
        }
        if (moved) {
            view.sync(math);
            hover();
        }
    }

    // ---- injecting events into the WebView ----

    private float[] local() {
        WebView wv = webView.get();
        if (wv == null) return null;
        int[] a = new int[2];
        int[] b = new int[2];
        wv.getLocationInWindow(a);
        view.getLocationInWindow(b);
        return new float[] {math.x + b[0] - a[0], math.y + b[1] - a[1]};
    }

    private static MotionEvent mouse(int action, float x, float y, int buttons, long down) {
        return event(action, x, y, buttons, down, MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE);
    }

    private static MotionEvent finger(int action, float x, float y, long down) {
        return event(action, x, y, 0, down, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
    }

    private static MotionEvent event(int action, float x, float y, int buttons, long down, int tool, int source) {
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = tool;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = (buttons != 0 || tool == MotionEvent.TOOL_TYPE_FINGER) ? 1f : 0f;
        pc[0].size = 1f;
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, pp, pc, 0, buttons, 1f, 1f, 0, 0,
                source, 0);
    }

    private void hover() {
        WebView wv = webView.get();
        float[] p = local();
        if (wv == null || p == null) return;
        MotionEvent m = mouse(MotionEvent.ACTION_HOVER_MOVE, p[0], p[1], 0, SystemClock.uptimeMillis());
        wv.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    private void click() {
        WebView wv = webView.get();
        float[] p = local();
        if (wv == null || p == null) return;
        long t = SystemClock.uptimeMillis();
        MotionEvent d = finger(MotionEvent.ACTION_DOWN, p[0], p[1], t);
        wv.dispatchTouchEvent(d);
        d.recycle();
        MotionEvent u = finger(MotionEvent.ACTION_UP, p[0], p[1], t);
        wv.dispatchTouchEvent(u);
        u.recycle();
        view.pulse();
    }

    private void scroll(int sx, int sy) {
        WebView wv = webView.get();
        float[] p = local();
        if (wv == null || p == null) return;
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = p[0];
        pc[0].y = p[1];
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, -sy * 2.5f);
        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, sx * 2.5f);
        long t = SystemClock.uptimeMillis();
        MotionEvent m = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        wv.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    /** The visible arrow and the mode pill. Not focusable, never takes touches. */
    private static final class PointerView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final RectF pillRect = new RectF();
        private final float u;
        private float x;
        private float y;
        private float pulse;
        private final String label;

        PointerView(Context c) {
            super(c);
            u = c.getResources().getDisplayMetrics().density;
            setFocusable(false);
            setClickable(false);
            fill.setColor(UiTokens.WHITE);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(UiTokens.BLACK);
            edge.setStrokeWidth(2f * u);
            edge.setStrokeJoin(Paint.Join.ROUND);
            pill.setColor(0xE6202022);
            text.setColor(UiTokens.WHITE);
            text.setTextSize(15f * u * 1.25f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            label = c.getString(R.string.movy_cursor_pill);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void sync(CursorMath m) {
            x = m.x;
            y = m.y;
            invalidate();
        }

        void pulse() {
            pulse = 1f;
            animate().cancel();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
            a.setDuration(260);
            a.addUpdateListener(v -> {
                pulse = (float) v.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = 26f * u;
            arrow.reset();
            arrow.moveTo(0, 0);
            arrow.lineTo(0, s);
            arrow.lineTo(s * 0.28f, s * 0.78f);
            arrow.lineTo(s * 0.46f, s * 1.15f);
            arrow.lineTo(s * 0.62f, s * 1.07f);
            arrow.lineTo(s * 0.44f, s * 0.71f);
            arrow.lineTo(s * 0.78f, s * 0.7f);
            arrow.close();
            c.save();
            c.translate(x, y);
            if (pulse > 0f) {
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setStyle(Paint.Style.STROKE);
                ring.setColor(0xFF0A84FF);
                ring.setStrokeWidth(3f * u);
                ring.setAlpha((int) (255 * pulse));
                c.drawCircle(0, 0, (1f - pulse) * 40f * u + 6f * u, ring);
            }
            c.drawPath(arrow, edge);
            c.drawPath(arrow, fill);
            c.restore();
            float tw = text.measureText(label) + 44f * u;
            float th = 40f * u;
            float cx = getWidth() / 2f;
            float top = getHeight() - UiTokens.TV_SAFE_V * u - 40f * u;
            pillRect.set(cx - tw / 2, top, cx + tw / 2, top + th);
            c.drawRoundRect(pillRect, th / 2, th / 2, pill);
            c.drawText(label, cx, top + th / 2 - (text.ascent() + text.descent()) / 2, text);
        }
    }
}
