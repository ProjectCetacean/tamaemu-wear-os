package io.github.projectcetacean.tamaemu;

import android.app.Application;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises the real WatchLayout/PetButton through Android's split-pointer dispatch. */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk = {26, 33, 36}, application = TamaemuApplication.class, qualifiers = "w240dp-h240dp-round-mdpi")
public class PointerAdmissionAndroidTest {
    @ParameterizedRobolectricTestRunner.Parameters(name="overlay={0}")
    public static java.util.List<Object[]> layouts() {
        return java.util.Arrays.asList(new Object[]{false},new Object[]{true});
    }
    private final boolean overlay;
    public PointerAdmissionAndroidTest(boolean overlay) { this.overlay=overlay; }

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private ViewGroup layout;
    private View[] buttons;
    private ButtonState input;
    private final List<String> edges = new ArrayList<>();
    private long eventTime,eventStep=100;
    private static final float SHELL_X = 120, SHELL_Y = 2;

    @Before public void createRealViewsWithoutStartingNative() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("ui_presentation", 0).edit().clear().commit();
        if(overlay)new UiPreferencesStore(app).save(UiPreferences.defaults().withSize(UiPreferences.Size.LARGE)
                .with("controlSpace","OVERLAY").with("placement","RIGHT"));
        EmulatorSession.Backend backend = new EmulatorSession.Backend() {
            public long open(String rom, String save) { throw new AssertionError("Input test must not open native state"); }
            public void close(long handle) { throw new AssertionError("Input test must not close native state"); }
            public int width(long handle) { throw new AssertionError("Input test must not query native state"); }
            public int height(long handle) { throw new AssertionError("Input test must not query native state"); }
            public void advance(long handle, int elapsed, int[] pixels) { throw new AssertionError("Input test must not tick native state"); }
            public void button(long handle, int button, boolean down) { edges.add(button + ":" + down); }
            public boolean save(long handle) { throw new AssertionError("Input test must not save native state"); }
        };
        EmulatorSession.Worker worker = new EmulatorSession.Worker() {
            long now;
            public void execute(Runnable task) { task.run(); }
            public void later(Runnable task, int delay) { throw new AssertionError("Each test edge is over 40 ms apart"); }
            public long nowMillis() { now += 100; return now; }
        };
        EmulatorSession session = new EmulatorSession(backend, worker, Runnable::run, "unused-rom", "unused-save");
        set(session, "handle", 1L);
        set(session, "listener", new EmulatorSession.Listener() {
            public void state(EmulatorSession.State state) { throw new AssertionError("Unexpected session state"); }
            public void frame(int width, int height, int[] pixels) { throw new AssertionError("Unexpected frame"); }
        });
        set(app, "session", session);
        controller = Robolectric.buildActivity(MainActivity.class).create();
        activity = controller.get();
        set(activity, "resumed", true);
        LifeRecord settled = LifeRecord.from(new long[]{1, 10000, 1000, 1, 0, 0, 1, 0});
        activity.lifeStatus(new LifeStatus(settled, settled, LifeStatus.OwnerState.PLAYING, 10000, "", false));
        activity.state(EmulatorSession.State.RUNNING);
        layout = (ViewGroup) get(activity, "layout");
        layout.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        layout.layout(0, 0, 240, 240);
        buttons = (View[]) get(layout, "controls");
        input = (ButtonState) get(layout, "input");
        assertTrue(layout.isMotionEventSplittingEnabled());
        assertEquals(0, input.pressedMask());
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            assertFalse("Shell fixture must hit no visible child", child.getVisibility() == View.VISIBLE
                    && SHELL_X >= child.getLeft() && SHELL_X < child.getRight()
                    && SHELL_Y >= child.getTop() && SHELL_Y < child.getBottom());
        }
        eventTime = SystemClock.uptimeMillis();
    }

    @After public void destroy() {
        if (controller != null) controller.destroy();
    }

    @Test public void stationaryShellFingerCannotKeepAnyButtonHeld() {
        for (int button = 0; button < 3; button++) {
            edges.clear(); float x = x(button), y = y(button);
            dispatch(MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y});
            dispatch(pointerDown(1), new int[]{0, 1}, new float[]{x, SHELL_X}, new float[]{y, SHELL_Y});
            dispatch(pointerUp(0), new int[]{0, 1}, new float[]{x, SHELL_X}, new float[]{y, SHELL_Y});
            assertEquals("A stationary shell pointer must not own button " + button, 0, input.pressedMask());
            assertEquals(Arrays.asList(button + ":true", button + ":false"), edges);
            dispatch(MotionEvent.ACTION_UP, new int[]{1}, new float[]{SHELL_X}, new float[]{SHELL_Y});
        }
    }

    @Test public void shellFingerMovingInsideCannotAcquireOwnership() {
        float x = x(0), y = y(0);
        dispatch(MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y});
        dispatch(pointerDown(1), new int[]{0, 1}, new float[]{x, SHELL_X}, new float[]{y, SHELL_Y});
        dispatch(MotionEvent.ACTION_MOVE, new int[]{0, 1}, new float[]{x, x}, new float[]{y, y});
        dispatch(pointerUp(0), new int[]{0, 1}, new float[]{x, x}, new float[]{y, y});
        assertEquals("A pointer born on shell cannot acquire a button on MOVE", 0, input.pressedMask());
        assertEquals(Arrays.asList("0:true", "0:false"), edges);
        dispatch(MotionEvent.ACTION_UP, new int[]{1}, new float[]{x}, new float[]{y});
    }

    @Test public void admittedContactsRetainTheirOwnerAndMoveOutAndBack() {
        float ax = x(0), ay = y(0), bx = x(1), by = y(1);
        dispatch(MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{ax}, new float[]{ay});
        dispatch(MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{SHELL_X}, new float[]{SHELL_Y});
        assertEquals(0, input.pressedMask());
        dispatch(MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{bx}, new float[]{by});
        assertEquals("Moving over B does not transfer A ownership", 0, input.pressedMask());
        dispatch(MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{ax}, new float[]{ay});
        assertEquals(1, input.pressedMask());
        dispatch(MotionEvent.ACTION_UP, new int[]{0}, new float[]{ax}, new float[]{ay});
        assertEquals(Arrays.asList("0:true", "0:false", "0:true", "0:false"), edges);
    }

    @Test public void twoInsideContactsAndDifferentButtonsRemainIndependent() {
        float ax = x(0), ay = y(0), bx = x(1), by = y(1);
        dispatch(MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{ax}, new float[]{ay});
        dispatch(pointerDown(1), new int[]{0, 1}, new float[]{ax, ax}, new float[]{ay, ay});
        dispatch(pointerUp(0), new int[]{0, 1}, new float[]{ax, ax}, new float[]{ay, ay});
        assertEquals(1, input.pressedMask());
        dispatch(pointerDown(1), new int[]{1, 2}, new float[]{ax, bx}, new float[]{ay, by});
        assertEquals(3, input.pressedMask());
        dispatch(pointerUp(0), new int[]{1, 2}, new float[]{ax, bx}, new float[]{ay, by});
        assertEquals(2, input.pressedMask());
        dispatch(MotionEvent.ACTION_UP, new int[]{2}, new float[]{bx}, new float[]{by});
        assertEquals(0, input.pressedMask());
        assertEquals(Arrays.asList("0:true", "1:true", "0:false", "1:false"), edges);
    }

    @Test public void cancelAndReleaseAllCannotReacquireOnMove() throws Exception {
        float x = x(0), y = y(0);
        dispatch(MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y});
        dispatch(MotionEvent.ACTION_CANCEL, new int[]{0}, new float[]{x}, new float[]{y});
        assertEquals(Arrays.asList("0:true", "0:false"), edges);
        assertEquals(0, input.pressedMask());
        edges.clear();
        dispatch(MotionEvent.ACTION_DOWN, new int[]{1}, new float[]{x}, new float[]{y});
        java.lang.reflect.Method release = layout.getClass().getDeclaredMethod("releaseControls");
        release.setAccessible(true); release.invoke(layout);
        dispatch(MotionEvent.ACTION_MOVE, new int[]{1}, new float[]{x}, new float[]{y});
        assertEquals(0, input.pressedMask());
        assertEquals(Arrays.asList("0:true", "0:false"), edges);
        dispatch(MotionEvent.ACTION_UP, new int[]{1}, new float[]{x}, new float[]{y});
    }

    @Test public void rapidViewContactsReachLifeOwnerAsPairedMaskEdges() throws Exception {
        for(int key=0;key<3;key++)for(boolean stalled:new boolean[]{false,true}) {
            LifeSessionTest.StatsObserver observer=new LifeSessionTest.StatsObserver();
            LifeSessionTest.HookModel model=new LifeSessionTest.HookModel();
            LifeSessionTest.InputFixture f=new LifeSessionTest.InputFixture(model,observer);
            set(activity,"session",f.session);
            long[] times={0,20,30,50,60,80};long previous=0;
            for(int i=0;i<times.length;i++) {
                if(stalled)f.worker.now=times[i];else f.worker.through(times[i]);
                eventStep=times[i]-previous;previous=times[i];
                dispatch(i%2==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_UP,
                        new int[]{0},new float[]{x(key)},new float[]{y(key)});
                if(!stalled)f.worker.through(times[i]);
            }
            f.worker.now=500;f.worker.through(500);
            assertEquals("real View contact replay preserves first deadline and third edge",
                    Arrays.asList(key+":true:0",key+":false:40",key+":true:60",key+":false:100"),model.nativeEdges);
            assertEquals(2,model.risingEdges[key]);assertEquals(0,model.pressed);assertEquals(0,input.pressedMask());
            LifeOwner.InputStats stats=observer.last();
            assertEquals(3,stats.ownerDownAttempts);assertEquals(3,stats.admitted);
            assertEquals(1,stats.deliveryRefusedNativeHeld);assertEquals(2,stats.deliveredNativeDowns);
            assertEquals(0,stats.pending);assertEquals(0,stats.heldMask);assertTrue(observer.violations.toString(),observer.violations.isEmpty());
            assertEquals(500,model.progress+f.listener.status.debtMs);
        }
    }

    @Test public void heldTouchAndSourcePulseRemainOneOwnerAttempt() throws Exception {
        LifeSessionTest.StatsObserver observer=new LifeSessionTest.StatsObserver();
        LifeSessionTest.InputFixture f=new LifeSessionTest.InputFixture(new LifeSessionTest.Model(),observer);
        set(activity,"session",f.session);
        eventStep=0;dispatch(MotionEvent.ACTION_DOWN,new int[]{0},new float[]{x(0)},new float[]{y(0)});
        f.worker.through(0);f.worker.now=10;
        input.accessibilityPulse(0,17);f.worker.through(10);
        f.worker.now=20;eventStep=20;dispatch(MotionEvent.ACTION_UP,new int[]{0},new float[]{x(0)},new float[]{y(0)});
        assertEquals(1,input.pressedMask());
        f.worker.now=60;input.endPulse(0,17);f.worker.through(100);
        assertEquals(Arrays.asList("0:true:0","0:false:60"),f.backend.nativeEdges);
        assertEquals(1,observer.last().ownerDownAttempts);assertEquals(1,observer.last().deliveredNativeDowns);
        assertEquals(0,observer.last().deliveryRefusedNativeHeld);assertEquals(0,observer.last().heldMask);
        assertTrue(observer.violations.toString(),observer.violations.isEmpty());
    }

    private float x(int button) { return buttons[button].getLeft() + buttons[button].getWidth() / 2f; }
    private float y(int button) { return buttons[button].getTop() + buttons[button].getHeight() / 2f; }
    private static int pointerDown(int index) { return MotionEvent.ACTION_POINTER_DOWN | index << MotionEvent.ACTION_POINTER_INDEX_SHIFT; }
    private static int pointerUp(int index) { return MotionEvent.ACTION_POINTER_UP | index << MotionEvent.ACTION_POINTER_INDEX_SHIFT; }
    private void dispatch(int action, int[] ids, float[] x, float[] y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = x[i]; coordinates[i].y = y[i];
            coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        eventTime += eventStep;
        MotionEvent event = MotionEvent.obtain(0, eventTime, action, ids.length, properties, coordinates,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { assertTrue("Real ViewGroup must retain the button touch target", layout.dispatchTouchEvent(event)); }
        finally { event.recycle(); }
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
