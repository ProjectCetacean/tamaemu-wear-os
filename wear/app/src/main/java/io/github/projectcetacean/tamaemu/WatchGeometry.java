package io.github.projectcetacean.tamaemu;

/** Insets are removed by the caller. All four rectangle corners must fit the safe shape. */
final class WatchGeometry {
    static final class Bounds {
        final int left, top, right, bottom;
        Bounds(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        int width() { return right - left; }
        int height() { return bottom - top; }
    }
    private static final class Layout {
        final Bounds viewport;
        final Bounds[] buttons;
        Layout(Bounds viewport, Bounds[] buttons) { this.viewport = viewport; this.buttons = buttons; }
    }
    final Bounds display, settingsPanel;
    /** Safe presentation gesture region; may include shell when the LCD is below 48dp. */
    public final Bounds displayTapTarget;
    final Bounds[] button;
    final int targetPx, availableSide, integerFactor;
    final boolean valid, integerFallback, placementFallback;
    final UiPreferences.Placement placementUsed;
    // Preserve the original host integration contract.
    final int cx, cy, image, imageTop, target, buttonY, errorSquare;

    WatchGeometry(int width, int height, float density) {
        this(width, height, density, true, UiPreferences.defaults(), true);
    }
    static WatchGeometry calculate(int width, int height, float density, boolean round,
            UiPreferences prefs, boolean controlsVisible) {
        return new WatchGeometry(width, height, density, round, prefs, controlsVisible);
    }
    private WatchGeometry(int width, int height, float density, boolean round,
            UiPreferences prefs, boolean visible) {
        cx = width / 2; cy = height / 2;
        targetPx = target = (int) Math.ceil(48 * density);
        int margin = (int) Math.ceil(4 * density), gap = (int) Math.ceil(8 * density);
        double radius = Math.min(width, height) / 2.0 - margin;
        int safeSquare = Math.max(0, round ? (int) Math.floor(radius * Math.sqrt(2)) : Math.min(width, height) - 2 * margin);
        while (safeSquare > 0 && !inside(centered(cx, cy, safeSquare), width, height, margin, round)) safeSquare--;
        settingsPanel = centered(cx, cy, safeSquare);
        errorSquare = safeSquare;
        int cap = Math.max(1, Math.min((int) (Math.min(width, height) * .56f),
                (int) (Math.min(width, height) * .65f) - Math.round(28 * density)));
        boolean overlay = prefs.controlSpace == UiPreferences.ControlSpace.OVERLAY;
        int limit = !overlay && prefs.size == UiPreferences.Size.LARGE ? safeSquare : Math.min(cap, safeSquare);
        Layout layout = overlay ? findOverlayLayout(width, height, margin, round, target, prefs.placement)
                : findLayout(width, height, margin, gap, round, limit, target, prefs.placement);
        placementFallback = layout == null && prefs.placement != UiPreferences.Placement.BOTTOM;
        placementUsed = placementFallback ? UiPreferences.Placement.BOTTOM : prefs.placement;
        if (placementFallback) layout = overlay ? findOverlayLayout(width, height, margin, round, target, placementUsed)
                : findLayout(width, height, margin, gap, round, limit, target, placementUsed);
        boolean hasLayout = layout != null;
        Bounds viewport = hasLayout ? layout.viewport : centered(cx, cy, 0);
        if (overlay && hasLayout) {
            // Overlay sizes are centred and independent of control placement as well as visibility.
            int side = safeSquare;
            if (prefs.size != UiPreferences.Size.LARGE) {
                Layout balanced = findLayout(width, height, margin, gap, round, Math.min(cap, safeSquare), target, UiPreferences.Placement.BOTTOM);
                // Fitting overlay buttons does not guarantee a reserved sizing reference exists.
                hasLayout = balanced != null;
                side = hasLayout ? balanced.viewport.width() : 0;
                if (prefs.size == UiPreferences.Size.SMALL) side = (int) Math.floor(side * .85);
            }
            viewport = centered(cx, cy, side);
        } else if (prefs.size == UiPreferences.Size.SMALL) {
            viewport = centeredIn(viewport, (int) Math.floor(viewport.width() * .85));
        }
        button = hasLayout ? layout.buttons : new Bounds[]{new Bounds(0,0,0,0), new Bounds(0,0,0,0), new Bounds(0,0,0,0)};
        availableSide = viewport.width();
        integerFactor = prefs.integerPixels && availableSide >= 128 ? availableSide / 128 : 0;
        integerFallback = prefs.integerPixels && availableSide < 128;
        display = centeredIn(viewport, integerFactor > 0 ? integerFactor * 128 : availableSide);
        Bounds tap = hasLayout ? findTapTarget(display, button, target) : null;
        if (hasLayout && tap == null) tap = findTouchingTapTarget(display, button, target,
                width, height, margin, round);
        if (hasLayout && tap == null) tap = findTapTarget(settingsPanel, button, target);
        if (hasLayout && tap == null) {
            // Complete safe-screen search if neither safe square has an uncovered target.
            for (int top = margin; tap == null && top + target <= height - margin; top++) {
                for (int left = margin; left + target <= width - margin; left++) {
                    Bounds candidate = new Bounds(left, top, left + target, top + target);
                    if (inside(candidate, width, height, margin, round) && clear(candidate, button)) {
                        tap = candidate;
                        break;
                    }
                }
            }
        }
        displayTapTarget = tap == null ? centered(cx, cy, 0) : tap;
        valid = hasLayout && tap != null;
        image = display.width(); imageTop = display.top;
        buttonY = button[0].top + target / 2;
    }
    private static Bounds findTouchingTapTarget(Bounds lcd, Bounds[] buttons, int target,
            int width, int height, int margin, boolean round) {
        Bounds centered = centeredIn(lcd, target);
        if (inside(centered, width, height, margin, round) && clear(centered, buttons)) return centered;
        Bounds best = null;
        long bestArea = 0, bestDistance = Long.MAX_VALUE;
        // Enumerate every pixel-aligned target sharing LCD pixels before considering detached shell.
        // Greatest LCD coverage prefers containment; distance then keeps the target adjacent.
        for (int top = Math.max(margin, lcd.top - target + 1);
                top <= Math.min(height - margin - target, lcd.bottom - 1); top++) {
            for (int left = Math.max(margin, lcd.left - target + 1);
                    left <= Math.min(width - margin - target, lcd.right - 1); left++) {
                Bounds candidate = new Bounds(left, top, left + target, top + target);
                if (!inside(candidate, width, height, margin, round) || !clear(candidate, buttons)) continue;
                long area = (long) (Math.min(candidate.right, lcd.right) - Math.max(left, lcd.left))
                        * (Math.min(candidate.bottom, lcd.bottom) - Math.max(top, lcd.top));
                long dx = (long) left + candidate.right - lcd.left - lcd.right;
                long dy = (long) top + candidate.bottom - lcd.top - lcd.bottom;
                long distance = dx * dx + dy * dy;
                if (area > bestArea || (area == bestArea && distance < bestDistance)) {
                    best = candidate; bestArea = area; bestDistance = distance;
                }
            }
        }
        return best;
    }
    private static Bounds findTapTarget(Bounds area, Bounds[] buttons, int target) {
        if (area.width() < target || area.height() < target) return null;
        // A free square can be moved to an area edge or an obstacle edge.
        int[] xs = {area.left, area.right - target, buttons[0].right, buttons[1].right, buttons[2].right,
                buttons[0].left - target, buttons[1].left - target, buttons[2].left - target};
        int[] ys = {area.top, area.bottom - target, buttons[0].bottom, buttons[1].bottom, buttons[2].bottom,
                buttons[0].top - target, buttons[1].top - target, buttons[2].top - target};
        for (int x : xs) for (int y : ys) {
            Bounds candidate = new Bounds(x, y, x + target, y + target);
            if (x >= area.left && y >= area.top && candidate.right <= area.right
                    && candidate.bottom <= area.bottom && clear(candidate, buttons)) return candidate;
        }
        return null;
    }
    private static boolean clear(Bounds candidate, Bounds[] buttons) {
        for (Bounds b : buttons) {
            if (candidate.left < b.right && candidate.right > b.left
                    && candidate.top < b.bottom && candidate.bottom > b.top) return false;
        }
        return true;
    }
    private static Layout findOverlayLayout(int width, int height, int margin, boolean round,
            int target, UiPreferences.Placement placement) {
        boolean vertical = placement == UiPreferences.Placement.LEFT || placement == UiPreferences.Placement.RIGHT;
        int w = vertical ? height : width, h = vertical ? width : height;
        Bounds[] buttons = new Bounds[3];
        // Follow the safe edge independently so the middle target leaves more LCD uncovered.
        for (int i = 0; i < 3; i++) {
            int left = w / 2 - 3 * target / 2 + i * target;
            for (int top = h - margin - target; top >= margin; top--) {
                Bounds b = new Bounds(left, top, left + target, top + target);
                if (inside(b, w, h, margin, round)) {
                    buttons[i] = transform(b, width, height, placement);
                    break;
                }
            }
            if (buttons[i] == null) return null;
        }
        return new Layout(centered(width / 2, height / 2, 0), buttons);
    }
    private static Layout findLayout(int width, int height, int margin, int gap, boolean round,
            int limit, int target, UiPreferences.Placement placement) {
        boolean vertical = placement == UiPreferences.Placement.LEFT || placement == UiPreferences.Placement.RIGHT;
        int w = vertical ? height : width, h = vertical ? width : height;
        int center = w / 2;
        double radius = Math.min(w, h) / 2.0 - margin;
        // Search in bottom coordinates, then reflect/transpose the complete layout.
        for (int side = limit; side >= 1; side--) {
            int left = center - side / 2;
            for (int top = Math.max(margin, h / 2 - (int) Math.ceil(radius)); top <= h / 2; top++) {
                Bounds candidate = new Bounds(left, top, left + side, top + side);
                if (!inside(candidate, w, h, margin, round)) continue;
                int rowTop = top + side + gap;
                Bounds first = new Bounds(center - 3 * target / 2, rowTop, center - 3 * target / 2 + target, rowTop + target);
                Bounds last = new Bounds(first.left + 2 * target, rowTop, first.right + 2 * target, rowTop + target);
                if (!inside(first, w, h, margin, round) || !inside(last, w, h, margin, round)) continue;
                Bounds[] buttons = new Bounds[3];
                for (int i = 0; i < 3; i++) buttons[i] = transform(new Bounds(first.left + i * target, rowTop, first.right + i * target, rowTop + target), width, height, placement);
                return new Layout(transform(candidate, width, height, placement), buttons);
            }
        }
        return null;
    }
    private static Bounds transform(Bounds b, int width, int height, UiPreferences.Placement placement) {
        switch (placement) {
            case TOP: return new Bounds(b.left, height - b.bottom, b.right, height - b.top);
            case LEFT: return new Bounds(width - b.bottom, b.left, width - b.top, b.right);
            case RIGHT: return new Bounds(b.top, b.left, b.bottom, b.right);
            default: return b;
        }
    }
    private static Bounds centeredIn(Bounds b, int side) {
        int left = b.left + (b.width() - side) / 2, top = b.top + (b.height() - side) / 2;
        return new Bounds(left, top, left + side, top + side);
    }
    private static Bounds centered(int cx, int cy, int side) {
        return new Bounds(cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side);
    }
    private static boolean inside(Bounds b, int width, int height, int margin, boolean round) {
        if (!round) return b.left >= margin && b.top >= margin && b.right <= width - margin && b.bottom <= height - margin;
        double radius = Math.min(width, height) / 2.0 - margin;
        double dx = Math.max(Math.abs(b.left - width / 2.0), Math.abs(b.right - width / 2.0));
        double dy = Math.max(Math.abs(b.top - height / 2.0), Math.abs(b.bottom - height / 2.0));
        return dx * dx + dy * dy <= radius * radius;
    }
}
