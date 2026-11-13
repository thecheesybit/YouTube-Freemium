package app.movy.shell;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.animation.LinearInterpolator;

import androidx.core.view.ViewCompat;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.Collections;
import java.util.List;

/**
 * The Island (Dynamic Island style): a floating black capsule near the right edge that morphs into a rounded panel
 * with one squircle tile per source and a detached settings bubble. All motion is spring physics. Full-size
 * transparent overlay; touches outside the shape fall through to the page. Drawing lives in {@link IslandPainter},
 * layout in {@link IslandGeometry}, touch in {@link IslandGestures}.
 */
public final class IslandView extends android.view.View {
    interface Listener {
        void onSourcePicked(Source source);

        void onSettingsRequested();

        void onExpandedChanged(boolean expanded);

        void onPositionChanged(float fraction);

        void onAnnouncementDismissed(String id);
    }

    private static final long IDLE_COLLAPSE_MS = 4000;
    private static final long IDLE_COLLAPSE_TV_MS = 7000;
    private static final long CONTENT_OUT_MS = 110;

    private static final FloatPropertyCompat<IslandView> P_PROGRESS = new FloatPropertyCompat<IslandView>("progress") {
        @Override public float getValue(IslandView v) { return v.progress; }
        @Override public void setValue(IslandView v, float x) { v.onProgress(x); }
    };
    private static final FloatPropertyCompat<IslandView> P_SHOW = new FloatPropertyCompat<IslandView>("show") {
        @Override public float getValue(IslandView v) { return v.show; }
        @Override public void setValue(IslandView v, float x) { v.show = x; v.invalidateBlock(); }
    };
    private static final FloatPropertyCompat<IslandView> P_BUBBLE = new FloatPropertyCompat<IslandView>("bubble") {
        @Override public float getValue(IslandView v) { return v.bubble; }
        @Override public void setValue(IslandView v, float x) { v.bubble = x; v.invalidateBlock(); }
    };
    private static final FloatPropertyCompat<IslandView> P_RING = new FloatPropertyCompat<IslandView>("ring") {
        @Override public float getValue(IslandView v) { return v.ringPos; }
        @Override public void setValue(IslandView v, float x) { v.ringPos = x; v.invalidateBlock(); }
    };
    private static final FloatPropertyCompat<IslandView> P_PRESS = new FloatPropertyCompat<IslandView>("press") {
        @Override public float getValue(IslandView v) { return v.pressScale; }
        @Override public void setValue(IslandView v, float x) { v.pressScale = x; v.invalidateBlock(); }
    };

    final IslandGeometry g;
    private final IslandPainter painter;
    private final IslandGestures gestures;
    private final boolean tv;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idleCollapse = this::collapse;
    private final Runnable bubbleOut;
    private final SpringAnimation progressSpring;
    private final SpringAnimation showSpring;
    private final SpringAnimation bubbleSpring;
    private final SpringAnimation ringSpring;
    private final SpringAnimation pressSpring;
    private final ValueAnimator contentAnim = ValueAnimator.ofFloat(0f, 1f);
    private final ValueAnimator pulseAnim = ValueAnimator.ofFloat(0f, 1f);
    /** Touch devices: a short sideways wiggle of the collapsed capsule every {@link UiTokens#NUDGE_EVERY_MS}. */
    private final ValueAnimator nudgeAnim = ValueAnimator.ofFloat(0f, 1f);
    private final Runnable nudgeTick = this::nudgeTick;
    private final Rect dirty = new Rect();
    private final RectF tmpBounds = new RectF();
    private final Rect exclusion = new Rect();

    // state read by the painter
    List<Source> sources = Collections.emptyList();
    String[] labels = new String[0];
    String[] monograms = new String[0];
    String activeId;
    HealthChecker health = HealthChecker.NONE;
    float progress;
    float show;
    float bubble;
    float contentMs;
    float ringPos;
    int pressIndex = -1;
    float pressScale = 1f;
    float pulsePhase;
    /** 0..1 progress of the current wiggle; 0 when idle. Read by the painter. */
    float nudge;
    String pulseId;
    int focusIndex;
    StaticLayout cardLayout;
    int cardAccent = 0xFF3A3A3C;

    private Listener listener;
    private Announcement announcement;
    private boolean expanded;
    private boolean shown;
    private boolean contentStarted;
    private final IslandAccessibility access;

    public IslandView(Context ctx, boolean tv) {
        super(ctx);
        this.tv = tv;
        g = new IslandGeometry(ctx.getResources().getDisplayMetrics().density,
                tv ? TvLayout.islandScale(ctx.getResources().getConfiguration().screenHeightDp) : 1f);
        painter = new IslandPainter(ctx, g, tv);
        gestures = new IslandGestures(this, g);
        access = new IslandAccessibility(this, g);
        ViewCompat.setAccessibilityDelegate(this, access);
        setFocusable(false);
        setVisibility(INVISIBLE);
        progressSpring = spring(P_PROGRESS, UiTokens.EXPAND_STIFFNESS, UiTokens.EXPAND_DAMPING, 0.002f);
        showSpring = spring(P_SHOW, 500f, 1f, 0.004f);
        bubbleSpring = spring(P_BUBBLE, UiTokens.EXPAND_STIFFNESS, UiTokens.EXPAND_DAMPING, 0.002f);
        ringSpring = spring(P_RING, UiTokens.SOFT_STIFFNESS, UiTokens.SOFT_DAMPING, 0.002f);
        bubbleOut = () -> springTo(bubbleSpring, 1f, UiTokens.EXPAND_STIFFNESS, UiTokens.EXPAND_DAMPING);
        pressSpring = spring(P_PRESS, UiTokens.PRESS_STIFFNESS, UiTokens.PRESS_DAMPING, 0.002f);
        pressSpring.addEndListener((a, canceled, value, vel) -> {
            if (value >= 0.999f) pressIndex = -1;
        });
        showSpring.addEndListener((a, canceled, value, vel) -> {
            if (!shown && value <= 0.01f) setVisibility(INVISIBLE);
        });
        contentAnim.setInterpolator(new LinearInterpolator());
        contentAnim.addUpdateListener(a -> {
            contentMs = (float) a.getAnimatedValue();
            invalidateBlock();
        });
        contentAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                updateExclusion();
            }
        });
        nudgeAnim.setDuration(UiTokens.NUDGE_DURATION_MS);
        nudgeAnim.setInterpolator(new LinearInterpolator());
        nudgeAnim.addUpdateListener(a -> {
            nudge = (float) a.getAnimatedValue();
            invalidateBlock();
        });
        nudgeAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                nudge = 0f;
                invalidateBlock();
            }
        });
        pulseAnim.setDuration(750);
        pulseAnim.setRepeatCount(3);
        pulseAnim.addUpdateListener(a -> {
            pulsePhase = (float) a.getAnimatedValue();
            invalidateBlock();
        });
        pulseAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                pulseId = null;
                invalidateBlock();
            }
        });
    }

    private SpringAnimation spring(FloatPropertyCompat<IslandView> prop, float stiffness, float damping, float minChange) {
        SpringAnimation a = new SpringAnimation(this, prop);
        a.setSpring(new SpringForce().setStiffness(stiffness).setDampingRatio(damping));
        a.setMinimumVisibleChange(minChange);
        return a;
    }

    private static void springTo(SpringAnimation a, float target, float stiffness, float damping) {
        a.getSpring().setStiffness(stiffness).setDampingRatio(damping);
        a.animateToFinalPosition(target);
    }

    /** Content fades in once the shape has passed ~60% of its expansion. */
    private void onProgress(float x) {
        progress = x;
        if (expanded && !contentStarted && x >= UiTokens.CONTENT_START) startContentIn();
        invalidateBlock();
    }

    private void startContentIn() {
        contentStarted = true;
        float total = IslandPainter.CONTENT_MS + Math.max(0, sources.size() - 1) * UiTokens.TILE_STAGGER_MS;
        contentAnim.cancel();
        contentAnim.setFloatValues(contentMs, total);
        contentAnim.setDuration(Math.max(1L, (long) (total - contentMs)));
        contentAnim.start();
    }

    private void startContentOut() {
        contentStarted = false;
        contentAnim.cancel();
        if (contentMs <= 0f) return;
        contentAnim.setFloatValues(contentMs, 0f);
        contentAnim.setDuration(CONTENT_OUT_MS);
        contentAnim.start();
    }

    // ---- configuration ----

    void setListener(Listener l) {
        listener = l;
    }

    void setHealth(HealthChecker h) {
        health = h;
    }

    void setPositionFraction(float f) {
        g.centerFrac = Math.max(0f, Math.min(1f, f));
        refreshLayout();
    }

    /** Re-renders the item list (sources added, removed or reordered). */
    void setSources(List<Source> next, String active) {
        boolean first = sources.isEmpty();
        sources = next;
        activeId = active;
        labels = new String[next.size()];
        monograms = new String[next.size()];
        float maxW = g.pillW - 8 * g.u;
        for (int i = 0; i < next.size(); i++) {
            labels[i] = TextUtils.ellipsize(next.get(i).name, new android.text.TextPaint(painter.labelPaint()), maxW, TextUtils.TruncateAt.END).toString();
            monograms[i] = next.get(i).monogram();
        }
        painter.prepare(next);
        g.setSize(getWidth(), getHeight(), next.size());
        if (focusIndex > next.size()) focusIndex = next.size();
        int idx = Math.max(0, indexOf(active));
        if (first) {
            ringSpring.cancel();
            ringPos = idx;
        } else {
            springTo(ringSpring, idx, UiTokens.SOFT_STIFFNESS, UiTokens.SOFT_DAMPING);
        }
        refreshLayout();
    }

    /** The white ring travels from the old tile to the new one. */
    void setActive(String id) {
        if (id == null ? activeId == null : id.equals(activeId)) return;
        activeId = id;
        springTo(ringSpring, Math.max(0, indexOf(id)), UiTokens.SOFT_STIFFNESS, UiTokens.SOFT_DAMPING);
        invalidateBlock();
    }

    void setAnnouncement(Announcement a) {
        announcement = a;
        rebuildCard();
        invalidateBlock();
    }

    String announcementText() {
        return announcement != null ? announcement.text : "";
    }

    String sourceName(int index) {
        return index >= 0 && index < sources.size() ? sources.get(index).name : "";
    }

    // ---- state ----

    boolean isExpanded() {
        return expanded;
    }

    boolean isShownFully() {
        return shown;
    }

    void expand() {
        if (expanded || !shown) return;
        expanded = true;
        focusIndex = Math.max(0, indexOf(activeId));
        setFocusable(true);
        setFocusableInTouchMode(tv);
        if (tv) requestFocus();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        springTo(progressSpring, 1f, UiTokens.EXPAND_STIFFNESS, UiTokens.EXPAND_DAMPING);
        main.removeCallbacks(bubbleOut);
        main.postDelayed(bubbleOut, UiTokens.BUBBLE_DELAY_MS);
        touched();
        if (listener != null) listener.onExpandedChanged(true);
    }

    void collapse() {
        collapseInternal(true);
    }

    void collapseNow() {
        collapseInternal(false);
    }

    private void collapseInternal(boolean animate) {
        main.removeCallbacks(idleCollapse);
        main.removeCallbacks(bubbleOut);
        if (!expanded) return;
        expanded = false;
        setFocusable(false);
        if (animate) {
            startContentOut();
            springTo(bubbleSpring, 0f, UiTokens.COLLAPSE_STIFFNESS, 1f);
            springTo(progressSpring, 0f, UiTokens.COLLAPSE_STIFFNESS, UiTokens.COLLAPSE_DAMPING);
        } else {
            progressSpring.cancel();
            bubbleSpring.cancel();
            contentAnim.cancel();
            contentStarted = false;
            progress = 0f;
            bubble = 0f;
            contentMs = 0f;
            invalidateBlock();
        }
        updateExclusion();
        if (listener != null) listener.onExpandedChanged(false);
    }

    /** D-pad keys while expanded (TV): routed here by the activity so the page never steals them. */
    boolean handleKey(KeyEvent event) {
        return expanded && onKeyDown(event.getKeyCode(), event);
    }

    void toggle() {
        if (expanded) collapse(); else expand();
    }

    /** Shows as a capsule / hides by shrinking to a sliver while fading out; it never slides off-screen. */
    void setShown(boolean wantShown, boolean animate) {
        if (wantShown == shown && !(wantShown && getVisibility() != VISIBLE)) return;
        shown = wantShown;
        if (wantShown) {
            setVisibility(VISIBLE);
            if (!animate) {
                showSpring.cancel();
                show = 1f;
                invalidateBlock();
                return;
            }
            springTo(showSpring, 1f, 500f, 1f);
        } else {
            collapseNow();
            if (!animate) {
                showSpring.cancel();
                show = 0f;
                setVisibility(INVISIBLE);
                return;
            }
            springTo(showSpring, 0f, 500f, 1f);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!tv) {
            main.removeCallbacks(nudgeTick);
            main.postDelayed(nudgeTick, UiTokens.NUDGE_EVERY_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(nudgeTick);
        nudgeAnim.cancel();
        super.onDetachedFromWindow();
    }

    /** Wiggles the collapsed capsule so people notice it; skipped while expanded, hidden or being dragged. */
    private void nudgeTick() {
        main.postDelayed(nudgeTick, UiTokens.NUDGE_EVERY_MS);
        if (tv || !shown || expanded || show < 0.99f || progress > 0.01f || pressIndex >= 0) return;
        if (getWindowVisibility() != VISIBLE || nudgeAnim.isRunning()) return;
        nudgeAnim.start();
    }

    /** Gently pulses one item; the caller expands the Island first when it should be visible. */
    void pulse(String sourceId) {
        pulseId = sourceId;
        pulseAnim.cancel();
        pulseAnim.start();
    }

    // ---- interaction callbacks (from gestures, keys, accessibility) ----

    void selectItem(int index) {
        if (index < 0 || index >= sources.size()) return;
        performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
        Source s = sources.get(index);
        if (listener != null) listener.onSourcePicked(s);
        main.postDelayed(this::collapse, 250);
        touched();
    }

    void openSettings() {
        collapse();
        if (listener != null) listener.onSettingsRequested();
    }

    void dismissAnnouncement() {
        Announcement a = announcement;
        announcement = null;
        rebuildCard();
        invalidateBlock();
        if (a != null && listener != null) listener.onAnnouncementDismissed(a.id);
    }

    /** Pressed tiles spring to 0.92 with a light haptic; {@code index == sources.size()} is the bubble, -1 releases. */
    void setPressedItem(int index) {
        if (index >= 0) {
            pressIndex = index;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            springTo(pressSpring, UiTokens.PRESS_SCALE, UiTokens.PRESS_STIFFNESS, UiTokens.PRESS_DAMPING);
        } else {
            springTo(pressSpring, 1f, UiTokens.PRESS_STIFFNESS, UiTokens.PRESS_DAMPING);
        }
    }

    void setCenterFraction(float f) {
        g.centerFrac = Math.max(0f, Math.min(1f, f));
        invalidateBlock();
        updateExclusion();
    }

    void commitPosition() {
        if (listener != null) listener.onPositionChanged(g.centerFrac);
    }

    /** Any interaction postpones the idle auto-collapse. */
    void touched() {
        main.removeCallbacks(idleCollapse);
        if (expanded) main.postDelayed(idleCollapse, tv ? IDLE_COLLAPSE_TV_MS : IDLE_COLLAPSE_MS);
    }

    private int indexOf(String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).id.equals(id)) return i;
        return -1;
    }

    /** Recomputes the geometry for the state the user currently sees (hit tests, accessibility). */
    void layoutNow() {
        g.update(progress, show, bubble);
    }

    // ---- layout, drawing ----

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        g.setSize(w, h, sources.size());
        rebuildCard();
        refreshLayout();
    }

    private void refreshLayout() {
        if (getWidth() == 0) return;
        g.setSize(getWidth(), getHeight(), sources.size());
        g.update(1.2f, 1f, 1.2f);
        g.blockBounds(tmpBounds);
        float cardW = g.cardWidth();
        float l = Math.max(0, g.viewW - g.inset - g.pillW - cardW - 52 * g.u);
        dirty.set((int) l, (int) Math.max(0, tmpBounds.top - 12 * g.u), getWidth(), (int) Math.min(getHeight(), tmpBounds.bottom + 12 * g.u));
        g.update(0, 1f, 0);
        g.blockBounds(tmpBounds);
        dirty.union((int) tmpBounds.left, (int) tmpBounds.top, getWidth(), (int) tmpBounds.bottom);
        updateExclusion();
        invalidateBlock();
    }

    void invalidateBlock() {
        if (dirty.isEmpty()) invalidate(); else invalidate(dirty);
    }

    private void rebuildCard() {
        Announcement a = announcement;
        float w = g.cardWidth();
        if (a == null || a.text.isEmpty() || w < 80 * g.u) {
            cardLayout = null;
            g.hasCard = false;
            return;
        }
        int textW = (int) (w - 24 * g.u - 14 * g.u);
        cardLayout = StaticLayout.Builder.obtain(a.text, 0, a.text.length(), painter.cardTextPaint(), textW)
                .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        g.card.set(0, 0, w, cardLayout.getHeight() + 24 * g.u);
        g.hasCard = true;
        switch (a.level) {
            case "critical": cardAccent = IslandPainter.HEALTH_DOWN; break;
            case "warn": cardAccent = IslandPainter.HEALTH_SLOW; break;
            default: cardAccent = 0xFF3A3A3C; break;
        }
    }

    private void updateExclusion() {
        if (Build.VERSION.SDK_INT < 29 || getWidth() == 0) return;
        if (expanded) {
            g.update(1, 1f, 1);
            g.blockBounds(tmpBounds);
            exclusion.set((int) tmpBounds.left, (int) tmpBounds.top, getWidth(), (int) tmpBounds.bottom);
        } else {
            g.update(0, 1f, 0);
            exclusion.set((int) (g.viewW - g.hitW), (int) (g.top - 8 * g.u), getWidth(), (int) (g.bottom + 8 * g.u));
        }
        setSystemGestureExclusionRects(Collections.singletonList(new Rect(exclusion)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        painter.draw(canvas, this);
    }

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouch(e);
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent e) {
        return access.dispatchHoverEvent(e) || super.dispatchHoverEvent(e);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        invalidateBlock();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!expanded) return super.onKeyDown(keyCode, event);
        touched();
        int last = sources.size(); // settings bubble index
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                focusIndex = Math.max(0, focusIndex - 1);
                ensureVisible(focusIndex);
                invalidateBlock();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusIndex = Math.min(last, focusIndex + 1);
                ensureVisible(focusIndex);
                invalidateBlock();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                collapse(); // RIGHT at the page edge opened it; LEFT (like BACK) closes it
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true; // swallowed so a second RIGHT never closes what the first opened
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (focusIndex >= last) openSettings(); else selectItem(focusIndex);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void ensureVisible(int index) {
        if (index >= g.count || g.scrollMax <= 0) return;
        float itemTop = g.padTop + index * g.pitch;
        float viewport = g.pillH;
        if (itemTop - g.scroll < 0) g.scroll = itemTop - g.padTop;
        else if (itemTop + g.pitch - g.scroll > viewport) g.scroll = itemTop + g.pitch - viewport;
    }
}
