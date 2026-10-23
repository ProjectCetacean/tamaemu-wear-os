package io.github.projectcetacean.tamaemu;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native, scrollable settings. Each action says its label and selected value. */
final class SettingsPanel extends ScrollView {
    interface Listener { void changed(UiPreferences preferences, boolean sizeChanged); void done(); }
    /** Later life controls/status can append real controls here without touching guest views. */
    interface Extension { void append(LinearLayout content); }
    private final LinearLayout content;
    private final TextView heading, gestures, overlayExplanation, positionFallback;
    private final Button integer;
    private WatchGeometry geometry;
    private final List<Runnable> refreshers = new ArrayList<>();
    private UiPreferences prefs;
    private final Listener listener;
    SettingsPanel(Context context, UiPreferences preferences, Listener listener, Extension extension) {
        super(context); this.prefs = preferences; this.listener = listener;
        setFillViewport(true); setBackgroundColor(UiChrome.SURFACE); setClickable(true);
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        heading = text(R.string.ui_settings, 18);
        UiChrome.heading(heading, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        heading.setFocusable(true); content.addView(heading);
        group(R.string.ui_display);
        choice(R.string.ui_size, () -> sizeLabel(prefs.size), () -> {
            UiPreferences.Size[] all = UiPreferences.Size.values();
            update(prefs.withSize(all[(prefs.size.ordinal() + 1) % all.length]), true);
        });
        choice(R.string.ui_control_mode, () -> prefs.controls == UiPreferences.Controls.VISIBLE ? R.string.ui_visible : R.string.ui_retractable,
                () -> update(prefs.withControls(prefs.controls == UiPreferences.Controls.VISIBLE ? UiPreferences.Controls.RETRACTABLE : UiPreferences.Controls.VISIBLE), false));
        gestures = text(R.string.ui_gestures, 12); content.addView(gestures);
        choice(R.string.ui_control_space, () -> prefs.controlSpace == UiPreferences.ControlSpace.RESERVED ? R.string.ui_own_space : R.string.ui_over_display,
                () -> update(prefs.with("controlSpace", prefs.controlSpace == UiPreferences.ControlSpace.RESERVED ? "OVERLAY" : "RESERVED"), false));
        choice(R.string.ui_position, () -> placementLabel(prefs.placement), () -> {
            UiPreferences.Placement[] all = UiPreferences.Placement.values();
            update(prefs.with("placement", all[(prefs.placement.ordinal() + 1) % all.length].name()), false);
        });
        positionFallback = text(R.string.ui_position_fallback, 12); content.addView(positionFallback);
        overlayExplanation = text(R.string.ui_overlay_explanation, 12); content.addView(overlayExplanation);
        integer = action(R.string.ui_integer, () -> update(prefs.with("integerPixels", Boolean.toString(!prefs.integerPixels)), false));
        choice(R.string.ui_effect, () -> new int[]{R.string.ui_clean, R.string.ui_soft_lcd, R.string.ui_lcd_glass}[prefs.effect.ordinal()],
                () -> update(prefs.with("effect", UiPreferences.Effect.values()[(prefs.effect.ordinal() + 1) % 3].name()), false));
        choice(R.string.ui_sharpness, () -> new int[]{R.string.ui_off, R.string.ui_slight, R.string.ui_medium, R.string.ui_strong}[prefs.sharpness.ordinal()],
                () -> update(prefs.with("sharpness", UiPreferences.Sharpness.values()[(prefs.sharpness.ordinal() + 1) % 4].name().toLowerCase(Locale.ROOT)), false));
        group(R.string.ui_controls);
        choice(R.string.ui_shape, () -> prefs.shape == UiPreferences.Shape.CIRCLE ? R.string.ui_circle : R.string.ui_rounded_square,
                () -> update(prefs.with("shape", prefs.shape == UiPreferences.Shape.CIRCLE ? "ROUNDED_SQUARE" : "CIRCLE"), false));
        choice(R.string.ui_buttons, () -> new int[]{R.string.ui_charcoal, R.string.ui_silver, R.string.ui_blue}[prefs.buttons.ordinal()],
                () -> update(prefs.with("buttons", UiPreferences.Buttons.values()[(prefs.buttons.ordinal() + 1) % 3].name()), false));
        group(R.string.ui_appearance);
        choice(R.string.ui_shell, () -> new int[]{R.string.ui_black, R.string.ui_slate, R.string.ui_sage, R.string.ui_plum}[prefs.shell.ordinal()],
                () -> update(prefs.with("shell", UiPreferences.Shell.values()[(prefs.shell.ordinal() + 1) % 4].name()), false));
        action(R.string.ui_reset, () -> update(prefs.resetAppearance(), true));
        group(R.string.ui_behavior);
        choice(R.string.ui_sound, () -> onOff(prefs.sound), () -> update(prefs.with("sound", Boolean.toString(!prefs.sound)), false));
        choice(R.string.ui_haptics, () -> onOff(prefs.haptics), () -> update(prefs.with("haptics", Boolean.toString(!prefs.haptics)), false));
        choice(R.string.ui_awake, () -> prefs.keepAwake ? R.string.ui_keep_awake : R.string.ui_system_timeout,
                () -> update(prefs.with("keepAwake", Boolean.toString(!prefs.keepAwake)), false));
        if (extension != null) extension.append(content);
        action(R.string.ui_done, listener::done);
        addView(content, new ScrollView.LayoutParams(-1, -2)); refresh();
    }
    void geometryChanged(WatchGeometry next) {
        geometry = next; refresh();
    }
    void focusHeading() {
        scrollTo(0, 0); heading.requestFocus();
        heading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }
    private interface Label { int value(); }
    private void choice(int label, Label value, Runnable click) {
        Button button = action(label, click);
        refreshers.add(() -> button.setText(getResources().getString(label, getResources().getString(value.value()))));
    }
    private Button action(int label, Runnable click) {
        Button b = new Button(getContext()); b.setText(label);
        UiChrome.action(b, false);
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = Math.round(4 * getResources().getDisplayMetrics().density);
        content.addView(b, layout); return b;
    }
    private TextView text(int resource, int size) {
        TextView t = new TextView(getContext()); t.setText(resource);
        UiChrome.body(t); t.setTextSize(size); t.setGravity(Gravity.START); return t;
    }
    private void group(int title) { TextView t = text(title, 15); UiChrome.heading(t, false); if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true); content.addView(t); }
    private void update(UiPreferences next, boolean sizeChanged) {
        prefs = next; refresh(); listener.changed(next, sizeChanged);
    }
    private void refresh() {
        for (Runnable update : refreshers) update.run();
        gestures.setText(prefs.controls == UiPreferences.Controls.RETRACTABLE ? R.string.ui_gestures : R.string.ui_hold_for_settings);
        overlayExplanation.setVisibility(prefs.controlSpace == UiPreferences.ControlSpace.OVERLAY ? View.VISIBLE : View.GONE);
        if (!prefs.integerPixels || geometry == null) integer.setText(getResources().getString(R.string.ui_integer, getResources().getString(onOff(prefs.integerPixels))));
        else if (geometry.integerFallback) integer.setText(R.string.ui_integer_fallback);
        else integer.setText(getResources().getString(R.string.ui_integer_result, geometry.integerFactor,
                geometry.display.width(), geometry.availableSide));
        positionFallback.setVisibility(geometry != null && geometry.placementFallback ? View.VISIBLE : View.GONE);
        if (geometry != null && geometry.placementFallback) positionFallback.setText(getResources().getString(R.string.ui_position_fallback,
                getResources().getString(placementLabel(prefs.placement)), getResources().getString(placementLabel(geometry.placementUsed))));
    }
    private static int placementLabel(UiPreferences.Placement placement) {
        return new int[]{R.string.ui_bottom, R.string.ui_left, R.string.ui_right, R.string.ui_top}[placement.ordinal()];
    }
    private static int onOff(boolean value) { return value ? R.string.ui_on : R.string.ui_off; }
    private static int sizeLabel(UiPreferences.Size size) { return new int[]{R.string.ui_small, R.string.ui_balanced, R.string.ui_large}[size.ordinal()]; }
}
