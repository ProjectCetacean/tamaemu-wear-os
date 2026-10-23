package io.github.projectcetacean.tamaemu;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashSet;
import java.util.Set;

public final class MainActivity extends Activity implements EmulatorSession.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private EmulatorSession session;
    private WatchLayout layout;
    private UiPreferencesStore preferencesStore;
    private UiPreferences preferences;
    private boolean resumed, retainedPlayDuringSave;
    private SettingsPanel.Extension settingsExtension;
    private final LifeUiModel lifeUi = new LifeUiModel();
    private LifeSettingsPanel lifeSettings;
    private EmulatorSession.State sessionState = EmulatorSession.State.LOADING;
    private Runnable unregisterBack;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        session = ((TamaemuApplication) getApplication()).session();
        preferencesStore = new UiPreferencesStore(this);
        preferences = preferencesStore.load();
        lifeSettings = new LifeSettingsPanel(lifeUi, new LifeSettingsPanel.Listener() {
            @Override public void mode(boolean protectedAway) { changeLifeMode(protectedAway); }
            @Override public void retry() { restart(true); }
        }, ((TamaemuApplication)getApplication())::timeOutcome);
        layout = new WatchLayout(this);
        if (state != null) layout.settingsOpen = state.getBoolean("ui.settingsOpen", false);
        if (layout.settingsOpen) layout.createSettings();
        layout.updateTargets();
        setContentView(layout);
    }
    /** Additional controls can share the foreground settings surface. */
    void setSettingsExtension(SettingsPanel.Extension extension) { settingsExtension = extension; }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("ui.settingsOpen", layout.settingsOpen); super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true;
        ((TamaemuApplication)getApplication()).timeOpportunity(()->runOnUiThread(()->{if(resumed)refreshLifeUi();}));
        restart(false);
    }
    private void restart(boolean retry) {
        if (!resumed) return;
        state(EmulatorSession.State.LOADING);
        if (retry) session.retry(this); else session.resume(this);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && layout != null) layout.releaseControls();
    }
    @Override protected void onPause() {
        resumed = false;
        updateAudio();
        lifeUi.setForegroundReady(false);
        // Release edges while the foreground native worker is still accepting them.
        layout.releaseControls();
        session.pause();
        main.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }
    @Override protected void onStop() {
        EmulatorSession.StopResult result = session.stopAndAwait(EmulatorSession.STOP_BUDGET_MILLIS);
        if (result != EmulatorSession.StopResult.SAVED) Toast.makeText(this, R.string.stop_unconfirmed, Toast.LENGTH_LONG).show();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (unregisterBack != null) unregisterBack.run();
        super.onDestroy();
    }
    // API 26-32 use this legacy hook. API 33+ registers its platform callback only
    // while Settings is open; closing Settings unregisters it for system navigation.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        layout.releaseControls();
        if (layout.settingsOpen) layout.closeSettings(true); else super.onBackPressed();
    }
    private void updateBackNavigation() {
        if (Build.VERSION.SDK_INT < 33 || layout == null) return;
        if (layout.settingsOpen && unregisterBack == null) {
            unregisterBack = UiBackNavigation.register(this, () -> layout.closeSettings(true));
        } else if (!layout.settingsOpen && unregisterBack != null) {
            unregisterBack.run(); unregisterBack = null;
        }
    }
    @Override public void state(EmulatorSession.State state) {
        sessionState = state;
        if(state!=EmulatorSession.State.RUNNING)retainedPlayDuringSave=false;
        boolean running = state == EmulatorSession.State.RUNNING;
        lifeUi.setForegroundReady(running && resumed);
        layout.setRunning(running);
        refreshLifeUi();
    }
    @Override public void lifeStatus(LifeStatus status) {
        LifeStatus previous=lifeUi.status();
        if(status!=null) {
            if(status.state==LifeStatus.OwnerState.SAVING)
                retainedPlayDuringSave=retainedPlayDuringSave || (sessionState==EmulatorSession.State.RUNNING
                        && previous!=null && previous.state==LifeStatus.OwnerState.PLAYING);
            else retainedPlayDuringSave=false;
        }
        lifeUi.update(status);
        refreshLifeUi();
    }
    private void changeLifeMode(boolean protectedAway) {
        if (!resumed || !lifeUi.modeEnabled() || lifeUi.selectedMode() == (protectedAway ? 1 : 0)) return;
        layout.releaseControls();
        long request = lifeUi.beginModeChange();
        if (request == 0) return;
        refreshLifeUi();
        session.requestMode(protectedAway, (accepted, status) -> {
            lifeUi.modeResult(request, accepted, status);
            if (resumed) refreshLifeUi();
        });
    }
    private void refreshLifeUi() {
        layout.updateTargets();
        updateAwake();
        updateAudio();
        if (sessionState == EmulatorSession.State.RUNNING || sessionState == EmulatorSession.State.LOADING) {
            layout.message.setText(LifeSettingsPanel.summary(this, lifeUi));
            layout.retry.setVisibility(LifeSettingsPanel.retryAvailable(lifeUi.status()) ? View.VISIBLE : View.GONE);
            lifeSettings.refresh(layout.message.getText());
            return;
        }
        int message;
        switch (sessionState) {
            case MISSING_FIRMWARE: message = R.string.missing_firmware; break;
            case OPEN_FAILED: message = R.string.open_failed; break;
            case SAVE_FAILED: message = R.string.life_save_failed; break;
            case NATIVE_FAILED:
                message = lifeUi.status() != null && lifeUi.status().state == LifeStatus.OwnerState.RECOVERY_REQUIRED
                        ? R.string.life_recovery_description : R.string.native_failed;
                break;
            case RECOVERY_REQUIRED: message = R.string.life_recovery_description; break;
            case RECOVERY_MISSING_SAVE: message = R.string.recovery_missing_save; break;
            default: message = R.string.loading;
        }
        layout.message.setText(message);
        layout.retry.setVisibility(View.VISIBLE);
        lifeSettings.refresh(layout.message.getText());
    }
    private void updateAudio() {
        LifeStatus status=lifeUi.status();
        boolean playing=resumed && sessionState==EmulatorSession.State.RUNNING && status!=null
                && (status.state==LifeStatus.OwnerState.PLAYING
                        || (status.state==LifeStatus.OwnerState.SAVING && retainedPlayDuringSave))
                && layout!=null && !layout.settingsOpen;
        ((TamaemuApplication)getApplication()).configureAudio(playing,preferences.sound);
    }
    private void updateAwake() {
        LifeStatus status=lifeUi.status();
        boolean active=status!=null && (status.state==LifeStatus.OwnerState.CATCHING_UP
                || status.state==LifeStatus.OwnerState.SAVING || status.state==LifeStatus.OwnerState.PLAYING);
        boolean available=sessionState==EmulatorSession.State.LOADING || sessionState==EmulatorSession.State.RUNNING;
        // Foreground work must finish even while care input is disabled for catch-up/save.
        if (resumed && available && active && preferences.keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void frame(int width, int height, int[] pixels) { layout.display.frame(width, height, pixels); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class WatchLayout extends ViewGroup {
        final PixelView display;
        final View displayTapTarget;
        final PetButton[] controls = new PetButton[3];
        final ScrollView error;
        final TextView message;
        final Button retry;
        final ButtonState input;
        final GradientDrawable lcdBezel=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff080c10,0xff242e37,0xff46515b});
        final GradientDrawable lcdWell=new GradientDrawable();
        final Runnable[] pulseReleases = new Runnable[3];
        long pulseToken;
        boolean controlsVisible, running, settingsOpen;
        SettingsPanel settings;
        WatchGeometry geometry;
        WatchLayout(Context context) {
            super(context);
            setMotionEventSplittingEnabled(true);
            lcdBezel.setCornerRadius(dp(5));lcdBezel.setStroke(dp(1),UiChrome.BORDER);
            lcdWell.setColor(0xff060a0d);lcdWell.setCornerRadius(dp(2));
            controlsVisible = preferences.controls == UiPreferences.Controls.VISIBLE;
            input = new ButtonState((button, pressed) -> {
                if (controls[button] != null) controls[button].setPressed(pressed);
                session.button(button, pressed);
            });
            display = new PixelView(context);
            display.setContentDescription(getString(R.string.original_display));
            display.setFocusable(true);
            displayGestures(display);
            View.AccessibilityDelegate displayActions = new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.ui_action_settings, getString(R.string.ui_settings)));
                    if (preferences.controls == UiPreferences.Controls.RETRACTABLE) {
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.ui_action_controls,
                                getString(controlsVisible ? R.string.ui_hide_controls : R.string.ui_show_controls)));
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                    } else { info.setClickable(false); info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK); }
                }
                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == R.id.ui_action_settings) { openSettings(); return true; }
                    if (action == R.id.ui_action_controls) { toggleControls(true); return true; }
                    return super.performAccessibilityAction(host, action, args);
                }
            };
            display.setAccessibilityDelegate(displayActions);
            addView(display);
            // A separate transparent target keeps exact integer LCD sizing while admitting shell gestures.
            // It is below ABC; shell layouts move the single accessible presentation node here.
            displayTapTarget = new View(context);
            displayTapTarget.setContentDescription(getString(R.string.original_display));
            displayTapTarget.setAccessibilityDelegate(displayActions);
            displayTapTarget.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            displayTapTarget.setFocusable(false);
            displayGestures(displayTapTarget); addView(displayTapTarget);
            for (int i = 0; i < controls.length; i++) { controls[i] = new PetButton(context, i); addView(controls[i]); }
            error = new ScrollView(context); error.setFillViewport(true);
            GradientDrawable errorSurface=new GradientDrawable();errorSurface.setColor(UiChrome.SURFACE);errorSurface.setCornerRadius(dp(12));
            error.setBackground(errorSurface);
            LinearLayout panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER);
            panel.setPadding(dp(6),dp(4),dp(6),dp(6));
            message = new TextView(context); message.setGravity(Gravity.CENTER);UiChrome.body(message);
            panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
            retry = new Button(context); retry.setText(R.string.retry);UiChrome.action(retry,false);retry.setOnClickListener(v -> restart(true));
            panel.addView(retry, new LinearLayout.LayoutParams(-1, -2));
            Button settingsButton = new Button(context); settingsButton.setText(R.string.ui_settings);UiChrome.action(settingsButton,false);
            settingsButton.setOnClickListener(v -> openSettings()); panel.addView(settingsButton, new LinearLayout.LayoutParams(-1, -2));
            error.addView(panel, new ScrollView.LayoutParams(-1, -2)); addView(error);
            appearance(); setRunning(false);
        }
        private void displayGestures(View target) {
            target.setOnClickListener(v -> toggleControls(false));
            target.setOnLongClickListener(v -> {
                if (!input.hasActiveInput()) openSettings();
                return true;
            });
            target.setOnTouchListener(new View.OnTouchListener() {
                boolean blocked;
                @Override public boolean onTouch(View view, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) blocked = false;
                    if (input.hasActiveInput()) { blocked = true; view.cancelLongPress(); }
                    boolean consume = blocked;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) blocked = false;
                    return consume;
                }
            });
        }
        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        }
        void setRunning(boolean value) {
            running = value;
            if (!running) releaseControls();
            if (settingsOpen && settings == null) createSettings();
            updateTargets();
            if (!running) error.scrollTo(0, 0);
        }
        private void updateTargets() {
            updateBackNavigation();
            boolean ready = running && lifeUi.careEnabled();
            boolean guest = ready && !settingsOpen && (geometry == null || geometry.valid);
            if (!guest) releaseControls();
            input.setEnabled(guest);
            display.setVisibility(running && !settingsOpen ? VISIBLE : INVISIBLE);
            // Presentation actions remain available during a retained-LCD save; ABC still use the care gate.
            boolean presentation = running && !settingsOpen && (geometry == null || geometry.valid);
            display.setEnabled(presentation);
            displayTapTarget.setVisibility(display.getVisibility());
            displayTapTarget.setEnabled(presentation);
            updatePresentationAccessibility();
            for (PetButton control : controls) {
                control.setVisibility(running && controlsVisible && !settingsOpen ? VISIBLE : INVISIBLE);
                control.setEnabled(guest && controlsVisible);
                boolean visible = running && controlsVisible && !settingsOpen;
                control.setFocusable(visible);
                control.setImportantForAccessibility(visible ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            boolean petVisible=running && (lifeUi.careEnabled() || retainedPlayDuringSave);
            error.setVisibility(!settingsOpen && !(petVisible && (geometry == null || geometry.valid)) ? VISIBLE : GONE);
            if (settings != null) settings.setVisibility(settingsOpen ? VISIBLE : GONE);
            requestLayout();
        }
        private boolean shellTapTarget() {
            if (geometry == null || !geometry.valid) return false;
            WatchGeometry.Bounds tap = geometry.displayTapTarget, lcd = geometry.display;
            return tap.left < lcd.left || tap.top < lcd.top || tap.right > lcd.right || tap.bottom > lcd.bottom;
        }
        private void updatePresentationAccessibility() {
            boolean shell = shellTapTarget();
            display.setFocusable(!settingsOpen && !shell);
            displayTapTarget.setFocusable(!settingsOpen && shell);
            display.setImportantForAccessibility(settingsOpen ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : shell ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
            displayTapTarget.setImportantForAccessibility(settingsOpen ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : shell ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void releaseControls() {
            for (int i = 0; i < pulseReleases.length; i++) {
                if (pulseReleases[i] != null) main.removeCallbacks(pulseReleases[i]);
                pulseReleases[i] = null;
            }
            input.releaseAll();
            for (PetButton control : controls) if (control != null) control.activePointers.clear();
        }
        private void toggleControls(boolean forced) {
            if (!running || settingsOpen || preferences.controls != UiPreferences.Controls.RETRACTABLE || (!forced && input.hasActiveInput())) return;
            releaseControls(); controlsVisible = !controlsVisible; updateTargets();
            (shellTapTarget() ? displayTapTarget : display).announceForAccessibility(
                    getString(controlsVisible ? R.string.ui_controls_shown : R.string.ui_controls_hidden));
        }
        private void openSettings() {
            if (settingsOpen) return;
            releaseControls(); settingsOpen = true; createSettings(); updateTargets(); updateAudio();
        }
        private void createSettings() {
            settings = new SettingsPanel(getContext(), preferences, new SettingsPanel.Listener() {
                @Override public void changed(UiPreferences next, boolean sizeChosen) {
                    releaseControls(); preferences = next; preferencesStore.save(next); updateAudio();
                    if (preferences.controls == UiPreferences.Controls.VISIBLE) controlsVisible = true;
                    appearance(); updateTargets(); updateAwake();
                }
                @Override public void done() { closeSettings(true); }
            }, content -> {
                lifeSettings.append(content);
                if (settingsExtension != null) settingsExtension.append(content);
            });
            lifeSettings.refresh(message.length() == 0 ? null : message.getText());
            if (geometry != null) settings.geometryChanged(geometry);
            addView(settings); settings.post(() -> { if (settings != null && settingsOpen) settings.focusHeading(); });
        }
        void closeSettings(boolean focus) {
            releaseControls(); settingsOpen = false; updateAudio();
            if (settings != null) { removeView(settings); settings = null; }
            updateTargets();
            if (focus && running) display.post(() -> {
                View target = shellTapTarget() ? displayTapTarget : display;
                target.requestFocus(); target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            });
        }
        private void appearance() {
            int[][] shell = {{0xff151b21,0xff080c10,0xff000000},{0xff35434e,0xff26323d,0xff18222c},
                    {0xff405649,0xff30463b,0xff1e3229},{0xff533c56,0xff402b43,0xff2b1c2e}};
            setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,shell[preferences.shell.ordinal()]));
            display.appearance(preferences);
            for (PetButton control : controls) control.appearance();
        }
        @Override protected void dispatchDraw(Canvas canvas) {
            if(running&&!settingsOpen&&error.getVisibility()!=VISIBLE&&display.getVisibility()==VISIBLE) {
                int left=display.getLeft(),top=display.getTop(),right=display.getRight(),bottom=display.getBottom();
                int save=canvas.save();canvas.clipOutRect(left,top,right,bottom);
                lcdBezel.setBounds(left-dp(4),top-dp(4),right+dp(4),bottom+dp(4));lcdBezel.draw(canvas);
                lcdWell.setBounds(left-dp(1),top-dp(1),right+dp(1),bottom+dp(1));lcdWell.draw(canvas);
                canvas.restoreToCount(save);
            }
            super.dispatchDraw(canvas);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            WatchGeometry next = WatchGeometry.calculate(Math.max(0, width - getPaddingLeft() - getPaddingRight()),
                    Math.max(0, height - getPaddingTop() - getPaddingBottom()), getResources().getDisplayMetrics().density,
                    getResources().getConfiguration().isScreenRound(), preferences, controlsVisible);
            if (geometry != null && !sameLayout(geometry, next)) releaseControls();
            geometry = next;
            updatePresentationAccessibility();
            if (settings != null) settings.geometryChanged(geometry);
            if (!geometry.valid) { releaseControls(); input.setEnabled(false); message.setText(R.string.ui_geometry_failure); error.setVisibility(VISIBLE); retry.setVisibility(GONE); }
            display.measure(exact(geometry.display.width()), exact(geometry.display.height()));
            displayTapTarget.measure(exact(geometry.displayTapTarget.width()), exact(geometry.displayTapTarget.height()));
            for (PetButton control : controls) control.measure(exact(geometry.targetPx), exact(geometry.targetPx));
            error.measure(exact(geometry.settingsPanel.width()), exact(geometry.settingsPanel.height()));
            if (settings != null) settings.measure(exact(geometry.settingsPanel.width()), exact(geometry.settingsPanel.height()));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            place(display, geometry.display);
            place(displayTapTarget, geometry.displayTapTarget);
            for (int i = 0; i < controls.length; i++) place(controls[i], geometry.button[i]);
            place(error, geometry.settingsPanel); if (settings != null) place(settings, geometry.settingsPanel);
        }
        private boolean sameLayout(WatchGeometry before, WatchGeometry after) {
            if (!sameBounds(before.display, after.display)) return false;
            for (int i = 0; i < controls.length; i++) if (!sameBounds(before.button[i], after.button[i])) return false;
            return true;
        }
        private boolean sameBounds(WatchGeometry.Bounds before, WatchGeometry.Bounds after) {
            return before.left == after.left && before.top == after.top && before.right == after.right && before.bottom == after.bottom;
        }
        private void place(View view, WatchGeometry.Bounds b) {
            view.layout(getPaddingLeft() + b.left, getPaddingTop() + b.top, getPaddingLeft() + b.right, getPaddingTop() + b.bottom);
        }
        private int exact(int size) { return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY); }
    }
    private final class PetButton extends Button {
        final int button;
        boolean touchClick;
        final Set<Integer> activePointers = new HashSet<>();
        PetButton(Context context, int button) {
            super(context); this.button = button;
            setText(new String[]{"A", "B", "C"}[button]); setContentDescription(getString(R.string.button_label, getText()));
            setTextSize(14);setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            setGravity(Gravity.CENTER); setPadding(0, 0, 0, 0); setMinWidth(0); setMinHeight(0);
            setStateListAnimator(null);setElevation(0);
            setOnClickListener(v -> {
                if (touchClick || !resumed || !isEnabled()) return;
                long token = ++layout.pulseToken;
                layout.input.accessibilityPulse(button, token); lightHaptic();
                if (layout.pulseReleases[button] != null) main.removeCallbacks(layout.pulseReleases[button]);
                Runnable release = () -> { layout.input.endPulse(button, token); layout.pulseReleases[button] = null; };
                layout.pulseReleases[button] = release; main.postDelayed(release, 100);
            });
        }
        void appearance() {
            int[][] colors={{0xff515d68,0xff363f49,0xff252d35},{0xffe4eaf0,0xffc4cbd1,0xff9eaab5},
                    {0xff407eaf,0xff245b91,0xff183d65}};
            int[] palette=colors[preferences.buttons.ordinal()];
            setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},
                    new int[]{preferences.buttons==UiPreferences.Buttons.SILVER?0xff5d6871:0xff7d8a97,
                            preferences.buttons==UiPreferences.Buttons.SILVER?0xff18222b:UiChrome.TEXT}));
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{-android.R.attr.state_enabled},decoration(new int[]{0xff313942,0xff252d35},false,0xff414b56));
            states.addState(new int[]{android.R.attr.state_pressed},decoration(new int[]{palette[2],palette[1]},true,UiChrome.ACCENT));
            states.addState(new int[]{android.R.attr.state_focused},decoration(palette,true,UiChrome.ACCENT));
            states.addState(new int[]{},decoration(palette,false,0xff8794a0));
            android.graphics.drawable.Drawable artwork = new InsetDrawable(states,dp(4));
            if (preferences.controlSpace == UiPreferences.ControlSpace.OVERLAY)
                artwork = UiChrome.overlayControl(artwork, preferences.shape == UiPreferences.Shape.CIRCLE, dp(14));
            setBackgroundTintList(null);setBackground(artwork);setPadding(0,0,0,0);
        }
        private GradientDrawable decoration(int[] colors, boolean emphasized,int border) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors);
            d.setShape(preferences.shape == UiPreferences.Shape.CIRCLE ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
            if (preferences.shape == UiPreferences.Shape.ROUNDED_SQUARE) d.setCornerRadius(dp(10));
            d.setStroke(dp(emphasized ? 2 : 1),border);
            return d;
        }
        private void lightHaptic() { if (preferences.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }
        @Override public boolean performClick() { return super.performClick(); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            // A visible disabled overlay still owns its rectangle; never pass it to LCD gestures.
            if (!isEnabled() || !resumed) return true;
            int action = event.getActionMasked(); int index = event.getActionIndex();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    // Split dispatch can route a new shell pointer to this existing target.
                    // Only a contact born inside this rectangle may become an owner.
                    if (!(event.getX(index) >= 0 && event.getY(index) >= 0
                            && event.getX(index) < getWidth() && event.getY(index) < getHeight())) return true;
                    activePointers.add(event.getPointerId(index));
                    layout.input.contact(button, event.getPointerId(index), true); lightHaptic(); return true;
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        if (!activePointers.contains(event.getPointerId(i))) continue;
                        boolean inside = event.getX(i) >= 0 && event.getY(i) >= 0 && event.getX(i) < getWidth() && event.getY(i) < getHeight();
                        layout.input.contact(button, event.getPointerId(i), inside);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    boolean click = isPressed(); activePointers.remove(event.getPointerId(index)); layout.input.endContact(event.getPointerId(index));
                    if (action == MotionEvent.ACTION_UP && click) { touchClick = true; performClick(); touchClick = false; }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    layout.releaseControls(); return true;
                default: return true;
            }
        }
    }
}
