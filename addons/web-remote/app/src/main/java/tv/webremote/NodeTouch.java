package tv.webremote;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Acting on the screen without injecting touches: finds the interface element under a point in the accessibility tree
 * and asks it to click, long-press or scroll. Used when the firmware cancels the service's touch gestures, as this TV's
 * does. Web pages are covered too: a WebView exposes its links, buttons and fields as nodes.
 */
final class NodeTouch {

    private static final int MAX_DEPTH = 60;

    private final AccessibilityService service;
    private volatile AccessibilityNodeInfo lastField;

    NodeTouch(AccessibilityService service) {
        this.service = service;
    }

    /** The text field most recently clicked, if it is still on screen; null otherwise. */
    AccessibilityNodeInfo clickedField() {
        AccessibilityNodeInfo field = lastField;
        if (field == null) return null;
        if (!field.refresh() || !field.isVisibleToUser() || !field.isEditable()) {
            lastField = null;
            return null;
        }
        return field;
    }

    /** The topmost visible element containing the point, or null. */
    private AccessibilityNodeInfo hit(float x, float y) {
        List<AccessibilityWindowInfo> windows = new ArrayList<>(service.getWindows());
        Collections.sort(windows, (a, b) -> b.getLayer() - a.getLayer());
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo window : windows) {
            window.getBoundsInScreen(bounds);
            if (!bounds.contains((int) x, (int) y)) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo found = deepest(root, x, y, 0);
            if (found != null) return found;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root == null ? null : deepest(root, x, y, 0);
    }

    /** The innermost node containing the point; later siblings are drawn on top, so they are asked first. */
    private static AccessibilityNodeInfo deepest(AccessibilityNodeInfo node, float x, float y, int depth) {
        if (depth > MAX_DEPTH || !node.isVisibleToUser()) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean inside = bounds.contains((int) x, (int) y);
        if (!bounds.isEmpty() && !inside) return null;
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = deepest(child, x, y, depth + 1);
            if (found != null) return found;
        }
        return inside ? node : null;
    }

    /** Clicks the element at the point, or the nearest enclosing one that can be clicked. */
    boolean click(float x, float y) {
        AccessibilityNodeInfo start = hit(x, y);
        if (start == null) return false;
        if (start.isEditable()) {
            // A text field: it becomes the target of typed text even if the browser never reports it as focused.
            lastField = start;
            start.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return start.performAction(AccessibilityNodeInfo.ACTION_CLICK) || true;
        }
        for (AccessibilityNodeInfo n = start; n != null; n = n.getParent()) {
            if (n.isClickable() && n.isEnabled() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        // Something that is not marked clickable but takes focus, such as a text field: focus it.
        for (AccessibilityNodeInfo n = start; n != null; n = n.getParent()) {
            if (n.isFocusable() && n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }
        return false;
    }

    boolean longPress(float x, float y) {
        for (AccessibilityNodeInfo n = hit(x, y); n != null; n = n.getParent()) {
            if (n.isLongClickable() && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true;
        }
        return false;
    }

    /**
     * Scrolls the scrollable element under the point (else the first one in the window) a step in the direction the
     * delta points: dy greater than 0 reads further down.
     */
    boolean scroll(float x, float y, float dx, float dy) {
        boolean vertical = Math.abs(dy) >= Math.abs(dx);
        int action = vertical
                ? (dy > 0 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId())
                : (dx > 0 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId());
        int fallback = (vertical ? dy : dx) > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        AccessibilityNodeInfo start = hit(x, y);
        for (AccessibilityNodeInfo n = start; n != null; n = n.getParent()) {
            if (n.isScrollable() && (n.performAction(action) || n.performAction(fallback))) return true;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo any = root == null ? null : firstScrollable(root, 0);
        return any != null && (any.performAction(action) || any.performAction(fallback));
    }

    private static AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo node, int depth) {
        if (depth > MAX_DEPTH) return null;
        if (node.isScrollable() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = firstScrollable(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
