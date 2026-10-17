package io.github.projectcetacean.tamaemu;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/** Finite process-owned contacts in the current QA decor. No global input injection. */
final class AudioQaInputDriver {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> used = new HashSet<>();
    private WeakReference<MainActivity> resumed = new WeakReference<>(null);
    private View root, target;
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnWindowFocusChangeListener focus = focused -> { if (!focused) abort("focus_lost"); };
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { abort("detached"); }
    };
    private long generation, runId, deadline, downTime, lastUp = -1;
    private int ordinal, totalContacts, count, gap;
    private int[] holds;
    private String block;
    private boolean contact;
    private float x, y;

    void resume(MainActivity activity) {
        if (resumed.get() != activity) abort("owner_changed");
        resumed = new WeakReference<>(activity);
    }
    void depart(Activity activity, String reason) {
        if (resumed.get() == activity) {
            abort(reason);
            resumed.clear();
        }
    }
    void command(String action) {
        // BroadcastReceiver and lifecycle callbacks are main-thread callbacks. No queued command can revive a run.
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        if ("stop".equals(action)) { abort("explicit_stop"); return; }
        if (!("singles".equals(action) || "same-c".equals(action) || "abc".equals(action))) return;
        MainActivity activity = resumed.get();
        if (root != null || used.contains(action) || totalContacts >= 16 || activity == null
                || !AudioQaInputProvider.enabled(activity) || !activity.hasWindowFocus()) { declined(action); return; }
        View decor = activity.getWindow().getDecorView();
        if (!decor.isAttachedToWindow() || !allControls(decor, activity)) { declined(action); return; }
        used.add(action); block = action; root = decor; ordinal = 0; lastUp = -1;
        count = "singles".equals(action) ? 4 : 6;
        holds = "singles".equals(action) ? new int[]{20, 40, 60, 100} : new int[]{20, 20, 20, 20, 20, 20};
        gap = "singles".equals(action) ? 200 : "same-c".equals(action) ? 10 : 20;
        runId++; long token = ++generation; deadline = SystemClock.elapsedRealtime() + 15000;
        observer = root.getViewTreeObserver(); observer.addOnWindowFocusChangeListener(focus);
        root.addOnAttachStateChangeListener(attach);
        main.postDelayed(() -> watchdog(token), 50);
        down(token);
    }
    private void declined(String action) { Log.i("TamaemuInputProbe", "phase=declined block=" + action + " contacts=" + totalContacts); }
    private String button() { return "abc".equals(block) ? "ABC".substring(ordinal % 3, ordinal % 3 + 1) : "C"; }
    private boolean valid(long token) {
        if (token != generation || root == null) return false;
        String reason = null;
        MainActivity activity = resumed.get();
        if (SystemClock.elapsedRealtime() >= deadline) reason = "deadline";
        else if (activity == null || !AudioQaInputProvider.enabled(activity)) reason = "no_resumed_owner";
        else if (activity.getWindow().getDecorView() != root) reason = "root_changed";
        else if (!activity.hasWindowFocus() || !root.hasWindowFocus()) reason = "focus_lost";
        else if (!root.isAttachedToWindow()) reason = "detached";
        else if (!allControls(root, activity)) reason = "controls_unavailable";
        if (reason != null) { abort(reason); return false; }
        return true;
    }
    private void watchdog(long token) {
        if (valid(token)) main.postDelayed(() -> watchdog(token), Math.min(50, Math.max(1, deadline - SystemClock.elapsedRealtime())));
    }
    private void down(long token) {
        if (!valid(token) || totalContacts >= 16) return;
        MainActivity activity = resumed.get();
        target = find(root, activity.getString(R.string.button_label, button()));
        Rect visible = new Rect();
        if (target == null || !target.getGlobalVisibleRect(visible)) { abort("target_missing"); return; }
        Point origin = new Point();
        if (!root.getGlobalVisibleRect(new Rect(), origin)) { abort("root_hidden"); return; }
        // Global visible rectangles and this offset share root space, independent of screen/window position.
        x = visible.exactCenterX() - origin.x; y = visible.exactCenterY() - origin.y;
        downTime = SystemClock.uptimeMillis(); contact = true; totalContacts++;
        event(MotionEvent.ACTION_DOWN, "none");
        if (token == generation && root != null) main.postDelayed(() -> up(token), holds[ordinal]);
    }
    private void up(long token) {
        if (!valid(token)) return;
        View current = find(root, resumed.get().getString(R.string.button_label, button()));
        Rect visible = new Rect(); Point origin = new Point();
        if (!root.getGlobalVisibleRect(new Rect(), origin) || current != target || !current.getGlobalVisibleRect(visible)
                || !visible.contains((int)(x + origin.x), (int)(y + origin.y))) { abort("target_changed"); return; }
        contact = false;
        event(MotionEvent.ACTION_UP, "none");
        if (token != generation || root == null) return;
        ordinal++;
        if (ordinal == count) { finish("complete"); return; }
        target = null; main.postDelayed(() -> down(token), gap);
    }
    private void event(int action, String reason) {
        String label = button(), activeBlock = block;
        long id = runId; int index = ordinal + 1, requestedHold = holds[ordinal], requestedGap = gap, contacts = totalContacts;
        View owned = root;
        // Sample at the synchronous decor callback boundary. No backdated schedule timestamps.
        long uptime = SystemClock.uptimeMillis(), elapsed = SystemClock.elapsedRealtime();
        if (action == MotionEvent.ACTION_DOWN) downTime = uptime;
        long actualHold = action == MotionEvent.ACTION_DOWN ? 0 : uptime - downTime;
        long actualGap = action == MotionEvent.ACTION_DOWN && lastUp >= 0 ? uptime - lastUp : -1;
        if (action == MotionEvent.ACTION_UP) lastUp = uptime;
        MotionEvent event = MotionEvent.obtain(downTime, uptime, action, x, y, 0);
        try {
            boolean handled = owned.dispatchTouchEvent(event);
            Log.i("TamaemuInputProbe", "runId=" + id + " block=" + activeBlock + " ordinal=" + index
                    + " action=" + action + " button=" + label + " uptimeMs=" + uptime + " elapsedMs=" + elapsed
                    + " returnUptimeMs=" + SystemClock.uptimeMillis() + " returnElapsedMs=" + SystemClock.elapsedRealtime()
                    + " requestedHoldMs=" + requestedHold + " actualHoldMs=" + actualHold
                    + " requestedGapMs=" + requestedGap + " actualGapMs=" + actualGap + " contacts=" + contacts
                    + " handled=" + handled + " abort=" + reason);
        } finally { event.recycle(); }
    }
    private void abort(String reason) {
        if (root == null) return;
        // Invalidate first: a CANCEL callback or stale task cannot enqueue another edge.
        generation++; main.removeCallbacksAndMessages(null);
        if (contact) { contact = false; try { event(MotionEvent.ACTION_CANCEL, reason); } finally { finish(reason); } }
        else finish(reason);
    }
    private void finish(String reason) {
        generation++; main.removeCallbacksAndMessages(null);
        if (observer != null && observer.isAlive()) observer.removeOnWindowFocusChangeListener(focus);
        if (root != null) root.removeOnAttachStateChangeListener(attach);
        Log.i("TamaemuInputProbe", "runId=" + runId + " phase=end block=" + block + " contacts=" + totalContacts + " abort=" + reason);
        observer = null; root = null; target = null; contact = false;
    }
    private boolean allControls(View decor, MainActivity activity) {
        for (String label : new String[]{"A", "B", "C"})
            if (find(decor, activity.getString(R.string.button_label, label)) == null) return false;
        return true;
    }
    private View find(View view, String description) {
        if (!view.isShown() || !view.isEnabled() || !view.isAttachedToWindow()) return null;
        Rect bounds = new Rect();
        if (view instanceof Button && description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                && view.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = find(group.getChildAt(i), description); if (found != null) return found; }
        }
        return null;
    }
}
