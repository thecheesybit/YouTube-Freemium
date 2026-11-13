package app.movy.shell;

import android.view.MotionEvent;
import android.view.ViewConfiguration;

/** Touch handling of the Island: tap/swipe/drag on the collapsed tab, taps/swipes/scroll on the expanded pill. */
final class IslandGestures {
    private final IslandView v;
    private final IslandGeometry g;
    private final int slop;

    private float downX, downY, startCenter, startScroll;
    private boolean moved, dragging, swiped, scrolling;
    private int downItem;
    private boolean downGear, downCard;

    IslandGestures(IslandView view, IslandGeometry geometry) {
        this.v = view;
        this.g = geometry;
        this.slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
    }

    boolean onTouch(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return down(x, y);
            case MotionEvent.ACTION_MOVE:
                move(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                up(x, y);
                return true;
            case MotionEvent.ACTION_CANCEL:
                v.setPressedItem(-1);
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private boolean down(float x, float y) {
        moved = dragging = swiped = scrolling = false;
        downItem = -1;
        downGear = downCard = false;
        downX = x;
        downY = y;
        v.layoutNow();
        if (!v.isExpanded()) {
            if (!g.tabHit(x, y)) return false; // never blocks touches outside the tab
            startCenter = g.collapsedCenter();
            v.touched();
            return true;
        }
        startScroll = g.scroll;
        downItem = g.itemAt(x, y);
        downGear = downItem < 0 && g.gearHit(x, y);
        downCard = downItem < 0 && !downGear && g.cardHit(x, y);
        if (downItem >= 0) v.setPressedItem(downItem);
        else if (downGear) v.setPressedItem(g.count);
        v.touched();
        return true;
    }

    private void move(float x, float y) {
        float dx = x - downX;
        float dy = y - downY;
        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
        if (!moved || swiped) return;
        if (!v.isExpanded()) {
            if (!dragging && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) dragging = true;
            if (dragging) {
                v.setCenterFraction((startCenter + dy) / Math.max(1f, g.viewH));
            } else if (dx < -slop && Math.abs(dx) > Math.abs(dy)) {
                swiped = true;
                v.expand();
            }
            return;
        }
        v.touched();
        if (dx > slop * 2 && Math.abs(dx) > Math.abs(dy)) {
            swiped = true;
            v.setPressedItem(-1);
            v.collapse();
        } else if (g.scrollMax > 0 && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
            scrolling = true;
            v.setPressedItem(-1);
            g.scroll = startScroll - dy;
            v.invalidateBlock();
        }
    }

    private void up(float x, float y) {
        if (!v.isExpanded()) {
            if (dragging) {
                v.commitPosition();
            } else if (!swiped && !moved) {
                v.expand();
            }
            return;
        }
        v.setPressedItem(-1);
        if (swiped || scrolling || moved) return;
        if (downItem >= 0) {
            v.selectItem(downItem);
        } else if (downGear) {
            v.openSettings();
        } else if (downCard) {
            v.dismissAnnouncement();
        } else if (!g.pillHit(x, y)) {
            v.collapse();
        }
    }
}
