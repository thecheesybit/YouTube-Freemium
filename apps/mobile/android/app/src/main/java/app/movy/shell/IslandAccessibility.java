package app.movy.shell;

import android.graphics.Rect;
import android.os.Bundle;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import androidx.customview.widget.ExploreByTouchHelper;

import java.util.List;

/** Virtual accessibility nodes for the custom-drawn Island. Descriptions use names only. */
final class IslandAccessibility extends ExploreByTouchHelper {
    private final IslandView v;
    private final IslandGeometry g;
    private final Rect tmp = new Rect();

    IslandAccessibility(IslandView view, IslandGeometry geometry) {
        super(view);
        this.v = view;
        this.g = geometry;
    }

    private int gearId() {
        return g.count;
    }

    private int cardId() {
        return g.count + 1;
    }

    private int tabId() {
        return g.count + 2;
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        if (!v.isExpanded()) return g.tabHit(x, y) ? tabId() : HOST_ID;
        int i = g.itemAt(x, y);
        if (i >= 0) return i;
        if (g.gearHit(x, y)) return gearId();
        if (g.cardHit(x, y)) return cardId();
        return HOST_ID;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> ids) {
        if (!v.isExpanded()) {
            ids.add(tabId());
            return;
        }
        for (int i = 0; i < g.count; i++) ids.add(i);
        if (g.hasGear) ids.add(gearId());
        if (g.hasCard) ids.add(cardId());
    }

    @Override
    protected void onPopulateNodeForVirtualView(int id, AccessibilityNodeInfoCompat node) {
        String name;
        if (id == tabId()) {
            name = v.getContext().getString(R.string.movy_island_tab);
            v.layoutNow();
            tmp.set((int) (g.viewW - g.hitW), (int) g.top, (int) g.viewW, (int) g.bottom);
        } else if (id == cardId()) {
            name = v.announcementText();
            tmp.set((int) g.card.left, (int) g.card.top, (int) g.card.right, (int) g.card.bottom);
        } else if (id == gearId()) {
            name = v.getContext().getString(R.string.movy_settings);
            tmp.set((int) (g.gearX - g.gearD / 2), (int) (g.gearY - g.gearD / 2), (int) (g.gearX + g.gearD / 2), (int) (g.gearY + g.gearD / 2));
        } else {
            name = v.sourceName(id);
            float r = g.circle / 2;
            tmp.set((int) (g.itemX - r), (int) (g.itemY[id] - r), (int) (g.itemX + r), (int) (g.itemY[id] + r));
        }
        node.setContentDescription(name);
        node.setBoundsInParent(tmp);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        node.setClickable(true);
    }

    @Override
    protected boolean onPerformActionForVirtualView(int id, int action, Bundle args) {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false;
        if (id == tabId()) v.expand();
        else if (id == cardId()) v.dismissAnnouncement();
        else if (id == gearId()) v.openSettings();
        else v.selectItem(id);
        return true;
    }
}
