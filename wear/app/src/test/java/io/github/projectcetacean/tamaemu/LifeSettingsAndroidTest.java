package io.github.projectcetacean.tamaemu;

import android.app.Application;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.ParameterizedRobolectricTestRunner;
import static org.junit.Assert.*;

/** Real Activity/settings views; the isolated owner is never resumed to native emulation. */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk = {26, 33, 36}, application = TamaemuApplication.class, qualifiers = "w240dp-h240dp-round-mdpi")
public class LifeSettingsAndroidTest {
    @ParameterizedRobolectricTestRunner.Parameters(name="overlay={0}")
    public static java.util.List<Object[]> layouts() {
        return java.util.Arrays.asList(new Object[]{false},new Object[]{true});
    }
    private final boolean overlay;
    public LifeSettingsAndroidTest(boolean overlay) { this.overlay=overlay; }

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private ViewGroup layout;
    private int workerTasks;

    @Before public void create() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("ui_presentation", 0).edit().clear().commit();
        if(overlay)new UiPreferencesStore(app).save(UiPreferences.defaults().withSize(UiPreferences.Size.LARGE)
                .with("controlSpace","OVERLAY").with("placement","RIGHT"));
        if(Build.VERSION.SDK_INT>=33)ModernBack.applyMergedManifestOptIn(app);
        EmulatorSession.Backend backend = new EmulatorSession.Backend() {
            public long open(String r, String s) { throw new AssertionError("No native open in a UI test"); }
            public void close(long h) { throw new AssertionError("No native close in a UI test"); }
            public int width(long h) { return 1; }
            public int height(long h) { return 1; }
            public void advance(long h, int ms, int[] p) { throw new AssertionError("No native advance in a UI test"); }
            public void button(long h, int b, boolean d) { throw new AssertionError("No native input in a UI test"); }
            public boolean save(long h) { throw new AssertionError("No native save in a UI test"); }
        };
        EmulatorSession.Worker worker = new EmulatorSession.Worker() {
            public void execute(Runnable task) { workerTasks++; }
            public void later(Runnable task, int delay) { workerTasks++; }
            public long nowMillis() { return 1; }
        };
        set(app, "session", new EmulatorSession(backend,worker,Runnable::run,"unused-rom","unused-save"));
        // Attach a real window without onResume, which would start the pet owner.
        controller = Robolectric.buildActivity(MainActivity.class).create().visible();
        activity = controller.get();
        set(activity,"resumed",true);
        layout = (ViewGroup) get(activity,"layout");
        measure();
    }
    @After public void destroy() { if (controller != null) controller.destroy(); }

    @Test public void foregroundBacklogRetainsLcdAndButtonsWhileRealReplayRemainsExplicit() throws Exception {
        activity.state(EmulatorSession.State.RUNNING);
        activity.lifeStatus(status(0,173,LifeStatus.OwnerState.PLAYING));
        View[] controls=(View[])get(layout,"controls");
        assertTrue("Routine foreground lag keeps care enabled",controls[1].isEnabled());
        assertEquals("No catching-up overlay on ordinary taps",View.GONE,((View)get(layout,"error")).getVisibility());
        assertFalse(LifeSettingsPanel.summary(activity,(LifeUiModel)get(activity,"lifeUi")).contains("Catching up"));
        activity.lifeStatus(status(0,3000,LifeStatus.OwnerState.CATCHING_UP));
        assertFalse(controls[1].isEnabled());
        assertEquals("Actual away replay still has explicit progress",View.VISIBLE,((View)get(layout,"error")).getVisibility());
    }

    @Test public void ongoingCheckpointRetainsLcdAndErrorsRemainExplicit() throws Exception {
        activity.state(EmulatorSession.State.RUNNING);
        activity.lifeStatus(status(0,0,LifeStatus.OwnerState.PLAYING));
        AudioPump audio=((ForegroundAudio)get(activity.getApplication(),"foregroundAudio")).pump;
        assertTrue(audio.accepting());
        activity.lifeStatus(status(0,0,LifeStatus.OwnerState.SAVING));
        assertTrue("Routine checkpoint retains continuous foreground sound",audio.accepting());
        assertEquals("Routine checkpoint must not replace the pet",View.GONE,((View)get(layout,"error")).getVisibility());
        assertFalse("Care briefly waits for durable save",((View[])get(layout,"controls"))[1].isEnabled());
        activity.lifeStatus(status(0,0,LifeStatus.OwnerState.SAVE_FAILED));
        assertEquals(View.VISIBLE,((View)get(layout,"error")).getVisibility());
    }

    // Catches failure to wire static-frame sharpness through the real Activity, persistence,
    // or appearance changes accidentally touching life ownership, audio or raw callbacks.
    @Test public void lcdSharpnessCyclesThroughActivityWithoutOwnerWork() throws Exception {
        activity.state(EmulatorSession.State.RUNNING);
        LifeStatus playing=status(0,0,LifeStatus.OwnerState.PLAYING);activity.lifeStatus(playing);
        PixelView display=(PixelView)get(layout,"display");
        int[] frame=new int[128*128];java.util.Arrays.fill(frame,0xff505050);
        activity.frame(128,128,frame);
        int width=display.getWidth(),height=display.getHeight();
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        Button sharpness=button(layout,"LCD sharpness: Slight");assertNotNull("Activity exposes LCD sharpness",sharpness);
        for(String label:new String[]{"Medium","Strong","Off","Slight"}){
            sharpness.performClick();
            UiPreferences loaded=new UiPreferencesStore(activity).load();
            assertEquals(label.toLowerCase(java.util.Locale.ROOT),loaded.encode().get("sharpness"));
            assertEquals(overlay?UiPreferences.Size.LARGE:UiPreferences.Size.BALANCED,loaded.size);
            assertArrayEquals(frame,display.copyRawFrame());
            android.graphics.Bitmap upload=(android.graphics.Bitmap)get(display,"bitmap");
            int expected=label.equals("Off")?80:label.equals("Slight")?77:label.equals("Medium")?74:71;
            assertEquals("Static Activity LCD updates immediately",expected,android.graphics.Color.red(upload.getPixel(64,64)));
            assertSame(playing,((LifeUiModel)get(activity,"lifeUi")).status());
            assertEquals(0,workerTasks);assertEquals(width,display.getWidth());assertEquals(height,display.getHeight());
        }
        button(layout,activity.getString(R.string.ui_sound,activity.getString(R.string.ui_on))).performClick();
        sharpness.performClick();button(layout,activity.getString(R.string.ui_reset)).performClick();
        assertEquals("slight",new UiPreferencesStore(activity).load().encode().get("sharpness"));
        assertFalse(new UiPreferencesStore(activity).load().sound);
        assertSame(playing,((LifeUiModel)get(activity,"lifeUi")).status());assertEquals(0,workerTasks);
    }

    @Test public void soundSettingPersistsAndAppearanceResetPreservesMute() throws Exception {
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        button(layout,activity.getString(R.string.ui_sound,activity.getString(R.string.ui_on))).performClick();
        assertFalse(new UiPreferencesStore(activity).load().sound);
        button(layout,activity.getString(R.string.ui_reset)).performClick();
        assertFalse(new UiPreferencesStore(activity).load().sound);
        assertNotNull(button(layout,activity.getString(R.string.ui_sound,activity.getString(R.string.ui_off))));
        assertNull("Opening settings never allocates playback",get(activity.getApplication(),"foregroundAudio"));
    }

    @Test public void keepAwakeCoversForegroundCatchupAndSavingWithoutEnablingCare() throws Exception {
        assertFalse(keepsScreenOn());
        activity.lifeStatus(status(0, 960000, LifeStatus.OwnerState.CATCHING_UP));
        assertTrue("Keep awake covers initial foreground catch-up before RUNNING", keepsScreenOn());
        View[] controls=(View[])get(layout,"controls");
        assertFalse("Catch-up still disables care",controls[0].isEnabled());
        activity.lifeStatus(status(0, 960000, LifeStatus.OwnerState.SAVING));
        assertTrue("Checkpoint work keeps the foreground screen awake",keepsScreenOn());
        assertFalse("Saving still disables care",controls[0].isEnabled());
        activity.state(EmulatorSession.State.RUNNING);
        activity.lifeStatus(status(0, 0, LifeStatus.OwnerState.PLAYING));
        assertTrue(keepsScreenOn());assertTrue(controls[0].isEnabled());
        activity.lifeStatus(status(0, 500, LifeStatus.OwnerState.CATCHING_UP));
        assertTrue("A later foreground backlog also stays awake",keepsScreenOn());
        assertFalse(controls[0].isEnabled());
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        assertTrue("Settings does not interrupt active catch-up",keepsScreenOn());
        assertEquals(0,workerTasks);
    }

    @Test public void systemTimeoutRemainsEffectiveDuringCatchupAndSaving() throws Exception {
        activity.lifeStatus(status(0, 960000, LifeStatus.OwnerState.CATCHING_UP));
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        button(layout,activity.getString(R.string.ui_awake,activity.getString(R.string.ui_keep_awake))).performClick();
        assertFalse("System timeout overrides active foreground work",keepsScreenOn());
        activity.lifeStatus(status(0, 960000, LifeStatus.OwnerState.SAVING));
        assertFalse("Status updates cannot override System timeout",keepsScreenOn());
        button(layout,activity.getString(R.string.ui_awake,activity.getString(R.string.ui_system_timeout))).performClick();
        assertTrue("Switching Keep awake on applies to ongoing saving",keepsScreenOn());
        assertEquals(0,workerTasks);
    }

    @Test public void keepAwakeClearsForErrorsRecoveryAndPause() throws Exception {
        for(EmulatorSession.State failure:new EmulatorSession.State[]{EmulatorSession.State.MISSING_FIRMWARE,
                EmulatorSession.State.OPEN_FAILED,EmulatorSession.State.SAVE_FAILED,EmulatorSession.State.NATIVE_FAILED,
                EmulatorSession.State.RECOVERY_REQUIRED,EmulatorSession.State.RECOVERY_MISSING_SAVE}) {
            activity.state(EmulatorSession.State.LOADING);
            activity.lifeStatus(status(0, 500, LifeStatus.OwnerState.CATCHING_UP));
            assertTrue("Active foreground catch-up before "+failure,keepsScreenOn());
            activity.state(failure);assertFalse("Error state clears Keep awake: "+failure,keepsScreenOn());
        }
        for(LifeStatus.OwnerState failure:new LifeStatus.OwnerState[]{LifeStatus.OwnerState.CLOSED,
                LifeStatus.OwnerState.SAVE_FAILED,LifeStatus.OwnerState.RECOVERY_REQUIRED,LifeStatus.OwnerState.ACCOUNTING_ERROR}) {
            activity.state(EmulatorSession.State.LOADING);
            activity.lifeStatus(status(0, 500, LifeStatus.OwnerState.CATCHING_UP));assertTrue(keepsScreenOn());
            activity.lifeStatus(status(0, 500, failure));assertFalse("Owner blocks Keep awake: "+failure,keepsScreenOn());
        }
        activity.state(EmulatorSession.State.LOADING);
        activity.lifeStatus(status(0, 500, LifeStatus.OwnerState.CATCHING_UP));assertTrue(keepsScreenOn());
        activity.onPause();assertFalse("Pause immediately clears Keep awake",keepsScreenOn());
        activity.lifeStatus(status(0, 500, LifeStatus.OwnerState.SAVING));
        assertFalse("Late work status cannot keep a paused Activity awake",keepsScreenOn());
        assertEquals("Only pause enqueues owner work",1,workerTasks);
    }

    @Test public void currentProofClearsActionButKeepsEarlierEstimateAndInvalidDate() throws Exception {
        TimeVerifierTest.Local local=new TimeVerifierTest.Local();
        TimeVerifier verifier=new TimeVerifier(local,new TimeVerifierTest.Source(local));verifier.check();
        set(activity.getApplication(),"timeVerifier",verifier);
        activity.state(EmulatorSession.State.RUNNING);
        LifeRecord record=LifeRecord.from(new long[]{1,1800000000000L,1000,1,900,1,7,1});
        LifeStatus historical=new LifeStatus(record,record,LifeStatus.OwnerState.PLAYING,1800000000000L,"",false);
        activity.lifeStatus(historical);
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        assertNotNull(text(layout,activity.getString(R.string.life_clock_estimated)));
        assertEquals("MATCH hides the actionable notice",View.GONE,text(layout,TimeVerifier.notice(TimeVerifier.Outcome.UNAVAILABLE)).getVisibility());
        assertSame(historical,((LifeUiModel)get(activity,"lifeUi")).status());assertEquals(1,historical.clockQuality);
        assertEquals(0,workerTasks);
        local.wall+=1001;activity.lifeStatus(historical);
        assertEquals(View.VISIBLE,text(layout,TimeVerifier.notice(TimeVerifier.Outcome.UNAVAILABLE)).getVisibility());
        local.wall-=1001;activity.lifeStatus(historical);
        assertEquals("old wall value cannot revive proof",View.VISIBLE,text(layout,TimeVerifier.notice(TimeVerifier.Outcome.UNAVAILABLE)).getVisibility());
        TimeVerifierTest.Source source=new TimeVerifierTest.Source(local);source.offset=9000;
        verifier=new TimeVerifier(local,source);verifier.check();set(activity.getApplication(),"timeVerifier",verifier);activity.lifeStatus(historical);
        assertEquals(View.VISIBLE,text(layout,TimeVerifier.notice(TimeVerifier.Outcome.MISMATCH)).getVisibility());
        source.offset=0;local.advance(900000);verifier.check();
        activity.lifeStatus(new LifeStatus(record,record,LifeStatus.OwnerState.PLAYING,1,"",false));
        assertEquals(View.VISIBLE,text(layout,"Saved time needs checking").getVisibility());
        assertEquals(0,workerTasks);
    }

    private boolean keepsScreenOn() {
        return (activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0;
    }

    @Test public void careRequiresSettledLifeStatusAndSettingsRemainForeground() throws Exception {
        activity.state(EmulatorSession.State.RUNNING);
        activity.lifeStatus(status(0, 0, LifeStatus.OwnerState.PLAYING));
        View[] buttons = (View[]) get(layout,"controls");
        assertTrue(buttons[0].isEnabled());
        activity.lifeStatus(status(0, 54321, LifeStatus.OwnerState.CATCHING_UP));
        assertFalse("Care cannot run through catch-up", buttons[0].isEnabled());
        Button open = button(layout,activity.getString(R.string.ui_settings));
        assertNotNull("Settings must remain reachable during catch-up",open);
        assertEquals(View.VISIBLE,((View)get(layout,"error")).getVisibility());
        open.performClick();
        assertTrue((Boolean)get(layout,"settingsOpen"));
        assertEquals("Settings opening must not resume or pause the owner",0,workerTasks);
        assertNotNull(text(layout,activity.getString(R.string.life_catching_up,LifeUiModel.duration(54321))));
        assertFalse(button(layout,activity.getString(R.string.life_protected)).isEnabled());
        activity.lifeStatus(status(0, 0, LifeStatus.OwnerState.PLAYING));
        assertFalse("Settings still owns focus after settling",buttons[0].isEnabled());
        button(layout,activity.getString(R.string.ui_done)).performClick();
        assertTrue(buttons[0].isEnabled());
        assertEquals(0,workerTasks);
    }

    @Test public void committedStatusSelectionClockAndScheduleFailuresAreVisible() throws Exception {
        activity.state(EmulatorSession.State.RUNNING);
        LifeRecord working=LifeRecord.from(new long[]{1,10000,1000,1,0,1,2,1});
        LifeRecord committed=LifeRecord.from(new long[]{1,10000,1000,1,0,0,1,0});
        activity.lifeStatus(new LifeStatus(working,committed,LifeStatus.OwnerState.SAVING,10000,"",true));
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        assertTrue(button(layout,activity.getString(R.string.life_normal)).isSelected());
        assertFalse("Working Protected candidate is not selected",button(layout,activity.getString(R.string.life_protected)).isSelected());
        assertNotNull(text(layout,activity.getString(R.string.life_clock_estimated)));
        assertNotNull(text(layout,activity.getString(R.string.life_scheduling_failed)));
        assertNotNull(text(layout,activity.getString(R.string.life_saving)));
        assertFalse(button(layout,activity.getString(R.string.life_normal)).isEnabled());
        button(layout,activity.getString(R.string.ui_done)).performClick();
        activity.state(EmulatorSession.State.MISSING_FIRMWARE);
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        SettingsPanel settings=(SettingsPanel)get(layout,"settings");
        assertNotNull("Opening Settings retains the actual startup failure",text(settings,activity.getString(R.string.missing_firmware)));
        assertEquals(0,workerTasks);
    }

    @Test public void routineSavingKeepsPresentationSettingsReachableByRealHold() throws Exception {
        activity.state(EmulatorSession.State.RUNNING);
        activity.lifeStatus(status(0,0,LifeStatus.OwnerState.PLAYING));
        LifeStatus saving=status(0,0,LifeStatus.OwnerState.SAVING);activity.lifeStatus(saving);measure();
        assertEquals(View.GONE,((View)get(layout,"error")).getVisibility());
        for(View b:(View[])get(layout,"controls"))assertFalse("Saving blocks care",b.isEnabled());
        WatchGeometry.Bounds target=((WatchGeometry)get(layout,"geometry")).displayTapTarget;
        float x=(target.left+target.right)/2f,y=(target.top+target.bottom)/2f;
        long now=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
        try{layout.dispatchTouchEvent(down);}finally{down.recycle();}
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout()+20));
        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,y,0);
        try{layout.dispatchTouchEvent(up);}finally{up.recycle();}
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("Presentation Settings stays reachable through an ongoing durable checkpoint",(Boolean)get(layout,"settingsOpen"));
        assertSame(saving,((LifeUiModel)get(activity,"lifeUi")).status());assertEquals(0,workerTasks);
    }

    @Test public void settingsSurviveRecreationAndBackReturnsToRecovery() throws Exception {
        activity.state(EmulatorSession.State.LOADING);
        activity.lifeStatus(status(0, 1500, LifeStatus.OwnerState.CATCHING_UP));
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        Bundle saved=new Bundle(); activity.onSaveInstanceState(saved);
        controller.destroy();
        controller=Robolectric.buildActivity(MainActivity.class).create(saved).visible();
        activity=controller.get();layout=(ViewGroup)get(activity,"layout");measure();
        activity.lifeStatus(status(0, 0, LifeStatus.OwnerState.RECOVERY_REQUIRED));
        activity.state(EmulatorSession.State.RECOVERY_REQUIRED);
        assertTrue("Settings choice survives state restoration",(Boolean)get(layout,"settingsOpen"));
        if(Build.VERSION.SDK_INT>=33)assertNotNull("Restored Settings registers modern Back",get(activity,"unregisterBack"));
        assertNotNull(text(layout,activity.getString(R.string.life_recovery_description)));
        activity.onBackPressed();
        assertFalse((Boolean)get(layout,"settingsOpen"));
        assertEquals(View.VISIBLE,((View)get(layout,"error")).getVisibility());
        assertEquals(View.VISIBLE,((View)get(layout,"retry")).getVisibility());
        assertEquals(0,workerTasks);
    }
    @Test public void backClosesSettingsAndOnlyInterceptsWhileSettingsIsOpen() throws Exception {
        Object systemCallback=Build.VERSION.SDK_INT>=33?ModernBack.top(activity):null;
        button(layout,activity.getString(R.string.ui_settings)).performClick();
        if(Build.VERSION.SDK_INT>=33) {
            assertNotNull("Modern callback must be registered for open Settings",get(activity,"unregisterBack"));
            Object callback=ModernBack.top(activity);
            assertNotSame("Open Settings must replace the system callback; check application Back opt-in",systemCallback,callback);
            activity.lifeStatus(status(0, 500, LifeStatus.OwnerState.CATCHING_UP));
            assertSame("Status updates must not register duplicate callbacks",callback,ModernBack.top(activity));
            ModernBack.invoke(activity);
        } else activity.onBackPressed();
        assertFalse((Boolean)get(layout,"settingsOpen"));
        assertFalse("Back from Settings stays in the pet Activity",activity.isFinishing());
        if(Build.VERSION.SDK_INT>=33) {
            assertNull("Closed Settings must return gesture handling to the system",get(activity,"unregisterBack"));
            assertSame("The dispatcher returns to its original system callback",systemCallback,ModernBack.top(activity));
        }
        activity.onBackPressed();
        assertTrue("Back outside Settings uses Activity navigation",activity.isFinishing()||Shadows.shadowOf(activity).isTaskMovedToBack());
        assertEquals("Back within Settings does not pause/resume the owner",0,workerTasks);
    }
    private static final class ModernBack {
        static void applyMergedManifestOptIn(Application app) throws Exception {
            // Robolectric 4.16.1 parses its resource APK with legacy PackageParser,
            // which drops this ApplicationInfo flag on API33/36. Propagate the
            // asserted production merged-manifest value before attaching the dispatcher.
            // AGP's android_merged_manifest is the host-test component's synthetic
            // manifest, which can omit main Application attributes. The variant API
            // supplies the actual main artifact; missing configuration must fail.
            // APK inspection independently enforces the packaged Application flag.
            String path=System.getProperty("tamaemu.productionMergedManifest");
            assertNotNull("Back fixture requires the main variant merged-manifest artifact",path);
            javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document manifest=factory.newDocumentBuilder().parse(new java.io.File(path));
            assertEquals("Production manifest package must match the tested application",app.getPackageName(),
                manifest.getDocumentElement().getAttribute("package"));
            org.w3c.dom.NodeList applications=manifest.getDocumentElement().getElementsByTagName("application");
            assertEquals("Merged manifest must contain one Application",1,applications.getLength());
            org.w3c.dom.Element application=(org.w3c.dom.Element)applications.item(0);
            assertSame("Opt-in belongs directly to the manifest Application",manifest.getDocumentElement(),application.getParentNode());
            String declared=application.getAttributeNS("http://schemas.android.com/apk/res/android","enableOnBackInvokedCallback");
            assertEquals("Actual merged Application must opt in to Back callbacks","true",declared);
            boolean enabled=Boolean.parseBoolean(declared);
            // Hidden platform methods exist at runtime but are absent from android.jar.
            Object applicationInfo=app.getApplicationInfo();
            java.lang.reflect.Method isEnabled=applicationInfo.getClass().getMethod("isOnBackInvokedCallbackEnabled");
            boolean before=(Boolean)isEnabled.invoke(applicationInfo);
            applicationInfo.getClass().getMethod("setEnableOnBackInvokedCallback",boolean.class).invoke(applicationInfo,enabled);
            // API34+ additionally shadows the platform predictive-Back feature flag.
            if(Build.VERSION.SDK_INT>=34)org.robolectric.shadows.ShadowApplication.setEnableOnBackInvokedCallback(enabled);
            assertTrue((Boolean)isEnabled.invoke(applicationInfo));
            System.out.println("Back fixture SDK="+Build.VERSION.SDK_INT+" merged Application opt-in="+declared+
                "; legacy parser runtime flag="+before+"; propagated before window attachment");
        }
        static Object top(MainActivity activity) throws Exception {
            // Activity/PhoneWindow may keep a Proxy even after attachment. Inspect
            // the attached root's actual dispatcher, where proxy registrations land.
            View decor=activity.getWindow().getDecorView();
            Object root=View.class.getMethod("getViewRootImpl").invoke(decor);
            assertNotNull("Modern Back requires an attached real window",root);
            Object dispatcher=root.getClass().getMethod("getOnBackInvokedDispatcher").invoke(root);
            java.lang.reflect.Method method=dispatcher.getClass().getMethod("getTopCallback");
            return method.invoke(dispatcher);
        }
        static void invoke(MainActivity activity) throws Exception {
            Object callback=top(activity);
            assertTrue("The real platform dispatcher must contain an enabled app callback",callback instanceof android.window.OnBackInvokedCallback);
            ((android.window.OnBackInvokedCallback)callback).onBackInvoked();
        }
    }
    private LifeStatus status(long mode,long debt,LifeStatus.OwnerState state) {
        LifeRecord r=LifeRecord.from(new long[]{1,10000,1000,1,debt,mode,1,0});
        return new LifeStatus(r,r,state,10000,"Background progress requested",false);
    }
    private void measure() { layout.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));layout.layout(0,0,240,240); }
    private static Button button(View v,String label) { TextView t=text(v,label);return t instanceof Button?(Button)t:null; }
    private static TextView text(View v,String label) {
        if(v instanceof TextView && ((TextView)v).getText().toString().equals(label))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=text(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
}
