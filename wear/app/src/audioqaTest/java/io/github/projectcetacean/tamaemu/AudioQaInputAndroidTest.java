package io.github.projectcetacean.tamaemu;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36, application=TamaemuApplication.class, qualifiers="w240dp-h240dp-round-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioQaInputAndroidTest {
    static final String QA="io.github.projectcetacean.tamaemu.qoltest";
    Application app; MainActivity activity; ActivityController<MainActivity> owner;
    Application.ActivityLifecycleCallbacks provider;
    List<Integer> actions=new ArrayList<>(); List<Long> times=new ArrayList<>();
    List<Long> callbackTimes=new ArrayList<>(); List<String> buttons=new ArrayList<>();
    View[] controls;
    static Object field(Object o,String name) throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value) throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Class<?> qaClass(String name) {
        try{return Class.forName("io.github.projectcetacean.tamaemu.AudioQaInput"+name);}
        catch(ClassNotFoundException e){throw new AssertionError("Missing bounded QA input implementation",e);}
    }
    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();app.getApplicationInfo().flags|=ApplicationInfo.FLAG_DEBUGGABLE;
        EmulatorSession.Backend backend=new EmulatorSession.Backend(){
            public long open(String r,String s){throw new AssertionError("No native opens");}
            public void close(long h){throw new AssertionError("No native closes");}
            public int width(long h){return 128;} public int height(long h){return 128;}
            public void advance(long h,int t,int[] p){throw new AssertionError("No native advance");}
            public void button(long h,int b,boolean d){throw new AssertionError("No native buttons");}
            public boolean save(long h){throw new AssertionError("No native saves");}
        };
        EmulatorSession.Worker worker=new EmulatorSession.Worker(){public void execute(Runnable r){}public void later(Runnable r,int d){}public long nowMillis(){return SystemClock.uptimeMillis();}};
        set(app,"session",new EmulatorSession(backend,worker,Runnable::run,"unused","unused"));
        // The manifest starts the real provider; reset only its process singleton between test sandboxes.
        ContentProvider p=(ContentProvider)qaClass("Provider").getDeclaredConstructor().newInstance();
        ProviderInfo info=new ProviderInfo(); info.authority=QA+".audioqa"; p.attachInfo(app,info);
        provider=(Application.ActivityLifecycleCallbacks)p;
        owner=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=owner.get();
        owner.windowFocusChanged(true);
        LifeRecord settled=LifeRecord.from(new long[]{1,10000,1000,1,0,0,1,0});
        activity.lifeStatus(new LifeStatus(settled,settled,LifeStatus.OwnerState.PLAYING,10000,"",false));
        activity.state(EmulatorSession.State.RUNNING);
        controls=(View[])field(field(activity,"layout"),"controls");
        View decor=activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));decor.layout(0,0,240,240);
        for(int i=0;i<3;i++) {final String b="ABC".substring(i,i+1); controls[i].setOnTouchListener((v,e)->{actions.add(e.getActionMasked());times.add(e.getEventTime());callbackTimes.add(SystemClock.uptimeMillis());buttons.add(b);return false;});}
        provider.onActivityResumed(activity);
        assertEquals(QA,activity.getPackageName());
        assertTrue("activity focused", activity.hasWindowFocus());
        assertTrue("decor focused", decor.hasWindowFocus());
        assertTrue("decor attached",decor.isAttachedToWindow());
        for(View c:controls){assertTrue("control shown",c.isShown());assertTrue("control enabled",c.isEnabled());assertTrue("control attached",c.isAttachedToWindow());}
    }
    void receive(Context context,String action,boolean explicit) throws Exception {
        BroadcastReceiver r=(BroadcastReceiver)qaClass("Receiver").getDeclaredConstructor().newInstance();
        Intent i=new Intent(action);if(explicit)i.setComponent(new ComponentName(QA,"io.github.projectcetacean.tamaemu.AudioQaInputReceiver"));r.onReceive(context,i);
    }
    void send(String action) throws Exception {receive(app,action,true);}
    void idle(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    @After public void cleanup() throws Exception {if(provider!=null){provider.onActivityPaused(activity);app.unregisterActivityLifecycleCallbacks(provider);} }
    List<Runnable> probeTasks;
    AudioQaInputTrackProbe installProbe() throws Exception {
        Field consumed=AudioQaInputTrackProbe.class.getDeclaredField("consumed");consumed.setAccessible(true);consumed.set(null,false);
        // The shared contact fixture creates foreground audio when it enters RUNNING.
        // This probe fixture represents an initial firmware-free setup process instead.
        set(app,"foregroundAudio",null);
        probeTasks=new ArrayList<>();
        AudioQaInputTrackProbeAndroidTest.Time clock=new AudioQaInputTrackProbeAndroidTest.Time();
        AudioQaInputTrackProbe probe=new AudioQaInputTrackProbe(probeTasks::add,a->new AudioQaInputTrackProbeAndroidTest.Sink(clock),clock);
        set(field(provider,"driver"),"probe",probe);return probe;
    }
    static int attachListenerCount(View view) throws Exception {
        Field listenerInfo=View.class.getDeclaredField("mListenerInfo");listenerInfo.setAccessible(true);
        Object info=listenerInfo.get(view);
        if(info==null)return 0;
        Object listeners=field(info,"mOnAttachStateChangeListeners");
        return listeners==null?0:((List<?>)listeners).size();
    }
    private void startFailureCleansUpAndAdmitsLaterContacts(boolean fatal,boolean beforePort) throws Exception {
        installProbe(); // reset process allowance and represent the original neutral/no-pump stimulus
        AudioQaInputTrackProbeAndroidTest.Time clock=new AudioQaInputTrackProbeAndroidTest.Time();
        int[] closes={0};boolean[] focusReleased={false};
        Error startError=new OutOfMemoryError("synthetic: native/Java allocation during track creation");
        RuntimeException control=new IllegalStateException("control");
        AudioQaInputTrackProbe.Port port=new AudioQaInputTrackProbe.Port(){
            public boolean start(AudioPump.Focus f){if(fatal)throw startError;throw control;}
            public long head(){return 0;}public int[] grant(){return new int[10];}public int write(short[] z,int n){return 0;}
            public void close(){closes[0]++;focusReleased[0]=true;}
        };
        AudioQaInputTrackProbe probe=new AudioQaInputTrackProbe(probeTasks::add,a->{if(beforePort)throw startError;return port;},clock);
        set(field(provider,"driver"),"probe",probe);
        activity.state(EmulatorSession.State.MISSING_FIRMWARE);
        View decor=activity.getWindow().getDecorView();int baseline=attachListenerCount(decor);
        send("track-contract");assertEquals(1,probeTasks.size());assertTrue(probe.busy());
        assertEquals(baseline+1,attachListenerCount(decor));assertNull(field(app,"foregroundAudio"));
        Throwable escaped=null;try{probeTasks.remove(0).run();}catch(Throwable failure){escaped=failure;}
        // Same Odo timing: advance beyond budget and let all scheduled main callbacks execute.
        clock.t+=4000000000L;idle(5000);
        assertEquals(beforePort?0:1,closes[0]);assertEquals(!beforePort,focusReleased[0]);
        assertFalse("worker failure must disarm",probe.busy());
        assertEquals("attach listener removed on UI main",baseline,attachListenerCount(decor));
        assertNull(field(probe,"observer"));assertNull(field(probe,"decor"));
        assertNull(((WeakReference<?>)field(probe,"owner")).get());
        List<ShadowLog.LogItem> logs=ShadowLog.getLogsForTag(AudioQaInputTrackProbe.TAG);
        assertTrue("incomplete end receipt",logs.stream().anyMatch(l->l.msg.startsWith("phase=end")&&l.msg.contains("incomplete=1")&&!l.msg.contains("reason=complete")));
        assertTrue("disarm receipt",logs.stream().anyMatch(l->l.msg.startsWith("phase=disarmed")&&l.msg.contains("active=0 listenersRemoved=1")));
        if(fatal){
            assertSame("fatal error remains fatal",startError,escaped);
            assertTrue("no invented release certainty",logs.stream().anyMatch(l->l.msg.startsWith("phase=end")&&l.msg.contains("released=0")&&l.msg.contains("incomplete=1")));
        }else assertNull(escaped);
        assertNull("no pump allocated in neutral probe",field(app,"foregroundAudio"));
        send("track-contract");assertTrue("one-shot remains consumed",probeTasks.isEmpty());
        assertFalse("fresh controller cannot repeat",new AudioQaInputTrackProbe().begin(activity));
        activity.state(EmulatorSession.State.RUNNING);send("singles");idle(2000);
        assertEquals("later contact block admitted",8,actions.size());
        send("singles");idle(2000);assertEquals("contact once quota retained",8,actions.size());
    }
    @Test public void outOfMemoryAtStartDisarmsWithIncompleteReceiptAndLaterContacts() throws Exception {
        startFailureCleansUpAndAdmitsLaterContacts(true,false);
    }
    @Test public void runtimeStartFailureControlDisarmsWithIncompleteReceiptAndLaterContacts() throws Exception {
        startFailureCleansUpAndAdmitsLaterContacts(false,false);
    }
    @Test public void allocationFailureBeforePortAlsoDisarmsWithoutInventingRelease() throws Exception {
        startFailureCleansUpAndAdmitsLaterContacts(true,true);
    }
    @Test public void trackProbeRejectsCareBusyWrongGatesAndNeverLaunches() throws Exception {
        AudioQaInputTrackProbe probe=installProbe();
        send("track-contract");assertTrue("care state refused",probeTasks.isEmpty());
        send("singles");activity.state(EmulatorSession.State.MISSING_FIRMWARE);
        send("track-contract");assertTrue("busy contacts refused",probeTasks.isEmpty());send("stop");
        receive(app,"track-contract",false);
        for(String pkg:new String[]{"io.github.projectcetacean.tamaemu",QA+".extra",QA}){
            Context wrong=new ContextWrapper(app){@Override public String getPackageName(){return pkg;}@Override public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo(super.getApplicationInfo());i.flags=pkg.equals(QA)?0:ApplicationInfo.FLAG_DEBUGGABLE;return i;}};
            receive(wrong,"track-contract",true);
        }
        Intent intent=new Intent("track-contract").setComponent(new ComponentName(QA,AudioQaInputReceiver.class.getName()));
        intent.putExtra("duration",5000);new AudioQaInputReceiver().onReceive(app,intent);
        intent.removeExtra("duration");intent.setPackage("wrong");new AudioQaInputReceiver().onReceive(app,intent);
        intent.setPackage(null).setComponent(new ComponentName(QA,"wrong"));new AudioQaInputReceiver().onReceive(app,intent);
        assertTrue(probeTasks.isEmpty());assertFalse(probe.busy());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void probeOnceAndMutualExclusionSurviveCancelAndOwnerReplacement() throws Exception {
        AudioQaInputTrackProbe probe=installProbe();activity.state(EmulatorSession.State.MISSING_FIRMWARE);
        send("track-contract");assertEquals(1,probeTasks.size());assertTrue(probe.busy());
        activity.state(EmulatorSession.State.RUNNING);send("same-c");assertTrue(actions.isEmpty());
        send("track-contract");send("stop");probeTasks.remove(0).run();idle(1);assertFalse(probe.busy());
        activity.state(EmulatorSession.State.MISSING_FIRMWARE);send("track-contract");assertTrue(probeTasks.isEmpty());
        assertFalse("another controller cannot reset process allowance",new AudioQaInputTrackProbe().begin(activity));
    }
    @Test public void lifecycleAndWindowFocusCancelBeforeWorkerAllocation() throws Exception {
        AudioQaInputTrackProbe probe=installProbe();activity.state(EmulatorSession.State.MISSING_FIRMWARE);send("track-contract");
        provider.onActivityPaused(activity);probeTasks.remove(0).run();idle(1);assertFalse(probe.busy());
        assertTrue(ShadowLog.getLogsForTag(AudioQaInputTrackProbe.TAG).stream().anyMatch(l->l.msg.contains("reason=cancelled")&&l.msg.contains("released=1")));
        probe=installProbe();provider.onActivityResumed(activity);send("track-contract");owner.windowFocusChanged(false);
        probeTasks.remove(0).run();idle(1);assertFalse(probe.busy());
    }
    @Test public void previouslyAllocatedPumpEvenIfDisabledRefusesProbe() throws Exception {
        installProbe();activity.state(EmulatorSession.State.MISSING_FIRMWARE);
        set(app,"foregroundAudio",new ForegroundAudio(app));send("track-contract");assertTrue(probeTasks.isEmpty());
    }
    @Test public void unsupportedExtrasCannotStartInput() throws Exception {
        Intent intent=new Intent("singles").setComponent(new ComponentName(QA,AudioQaInputReceiver.class.getName()));
        intent.putExtra("repeat",2);
        new AudioQaInputReceiver().onReceive(app,intent);idle(1000);
        assertTrue("unsupported extras must fail closed",actions.isEmpty());
    }
    @Test public void threeDistinctBlocksDeliverExactly16RealContactsAndNeverRepeat() throws Exception {
        send("singles");send("same-c");idle(2000);assertEquals(8,actions.size());
        send("singles");idle(1000);assertEquals("repeat must fail before process cap is reached",8,actions.size());
        send("same-c");idle(1000);send("abc");idle(1000);
        assertEquals(32,actions.size());
        for(int i=0;i<32;i++)assertEquals(i%2==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_UP,(int)actions.get(i));
        assertEquals("CCCCCCCCCCCCCCCCCCCCAABBCCAABBCC",String.join("",buttons));
        assertEquals(times,callbackTimes);
        for(String s:new String[]{"singles","same-c","abc","unknown"})send(s);idle(20000);assertEquals(32,actions.size());
    }
    @Test public void holdsAndGapsUseActualCallbackTimeNotRequestedBackdating() throws Exception {
        send("singles");assertEquals(Arrays.asList(0),actions);idle(20);assertEquals(20L,times.get(1)-times.get(0));idle(200);
        assertEquals(200L,times.get(2)-times.get(1));idle(40);assertEquals(40L,times.get(3)-times.get(2));idle(1000);
        assertEquals(60L,times.get(5)-times.get(4));assertEquals(100L,times.get(7)-times.get(6));
        assertEquals(times,callbackTimes);
        assertTrue(ShadowLog.getLogsForTag("TamaemuInputProbe").stream().anyMatch(l->l.msg.contains("elapsedMs=")&&l.msg.contains("uptimeMs=")&&l.msg.contains("requestedHoldMs=")));
    }
    @Test public void globalVisibleRectUsesRootCoordinatesEvenWhenWindowIsOffset() throws Exception {
        Field f=View.class.getDeclaredField("mAttachInfo");f.setAccessible(true);Object attach=f.get(activity.getWindow().getDecorView());
        set(attach,"mWindowLeft",40);set(attach,"mWindowTop",70);
        send("same-c");idle(1000);assertEquals(12,actions.size());
        for(String button:buttons)assertEquals("C",button);
    }
    @Test public void focusLossCancelsOldOwnRootAndNoOtherWindowGetsEvents() throws Exception {
        send("singles");owner.windowFocusChanged(false);idle(1000);
        assertEquals(Arrays.asList(0,3),actions);
        Activity other=Robolectric.buildActivity(Activity.class).create().start().resume().visible().get();
        final int[] foreign={0};other.getWindow().getDecorView().setOnTouchListener((v,e)->{foreign[0]++;return true;});
        send("same-c");idle(2000);assertEquals(0,foreign[0]);assertEquals(2,actions.size());
    }
    @Test public void pauseAndStopInvalidateAllOldCallbacksAndDoNotRevive() throws Exception {
        send("same-c");provider.onActivityPaused(activity);provider.onActivityResumed(activity);send("abc");idle(1000);
        assertEquals(Arrays.asList(0,3,0,1,0,1,0,1,0,1,0,1,0,1),actions);
        send("singles");send("stop");idle(20000);assertEquals(3,(int)actions.get(actions.size()-1));assertEquals(16,actions.size());
    }
    @Test public void elapsedDeadlineAfterSleepCancelsInsteadOfLateUp() throws Exception {
        send("singles");ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(16));idle(20);
        assertEquals(Arrays.asList(0,3),actions);idle(20000);assertEquals(2,actions.size());
    }
    @Test public void replacementContentOrHiddenControlCancelsBeforeUp() throws Exception {
        send("singles");controls[2].setVisibility(View.INVISIBLE);idle(20);assertEquals(Arrays.asList(0,3),actions);
        send("abc");idle(1000);assertEquals(2,actions.size());
    }
    @Test public void rejectsWrongPackageNonDebugImplicitUnknownAndNoResumedOwner() throws Exception {
        receive(app,"singles",false);send("unknown");
        for(String pkg:new String[]{"io.github.projectcetacean.tamaemu",QA+".extra",QA}) {
            Context wrong=new ContextWrapper(app){@Override public String getPackageName(){return pkg;}@Override public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo(super.getApplicationInfo());i.flags=pkg.equals(QA)?0:ApplicationInfo.FLAG_DEBUGGABLE;return i;}};
            receive(wrong,"singles",true);
        }
        provider.onActivityPaused(activity);send("abc");idle(1000);assertTrue(actions.isEmpty());
    }
    @Test public void staleRunnableTokenCannotTouchNewBlockAfterExplicitStop() throws Exception {
        send("singles");Object driver=field(provider,"driver");long old=(Long)field(driver,"generation");send("stop");send("abc");
        int before=actions.size();
        for(String method:new String[]{"down","up","watchdog"}) {Method m=driver.getClass().getDeclaredMethod(method,long.class);m.setAccessible(true);m.invoke(driver,old);}
        assertEquals(before,actions.size());idle(1000);assertEquals(14,actions.size());
    }
    @Test public void changedContentCannotReceiveAnOldRelease() throws Exception {
        send("singles");final List<Integer> foreign=new ArrayList<>();
        android.widget.Button replacement=new android.widget.Button(activity);replacement.setContentDescription("Button C");
        replacement.setOnTouchListener((v,e)->{foreign.add(e.getActionMasked());return true;});activity.setContentView(replacement);
        idle(1000);assertTrue(foreign.isEmpty());assertEquals(Arrays.asList(0,3),actions);
    }
    @Test public void providerIsInertOutsideQaAndExposesNoCrudData() throws Exception {
        ContentProvider p=(ContentProvider)qaClass("Provider").getDeclaredConstructor().newInstance();
        Context wrong=new ContextWrapper(app){@Override public String getPackageName(){return "io.github.projectcetacean.tamaemu";}};
        ProviderInfo info=new ProviderInfo();info.authority="wrong.audioqa";p.attachInfo(wrong,info);assertFalse(p.onCreate());
        assertNull(p.getType(android.net.Uri.parse("content://wrong.audioqa/")));
        try {p.query(null,null,null,null,null);fail("query must be unsupported");}catch(UnsupportedOperationException expected){}
        try {p.insert(null,null);fail("insert must be unsupported");}catch(UnsupportedOperationException expected){}
        try {p.update(null,null,null,null);fail("update must be unsupported");}catch(UnsupportedOperationException expected){}
        try {p.delete(null,null,null);fail("delete must be unsupported");}catch(UnsupportedOperationException expected){}
    }
    @Test public void hiddenOrDisabledControlsRejectWholeBlockBeforeFirstContact() throws Exception {
        controls[0].setVisibility(View.INVISIBLE);send("singles");idle(1000);assertTrue(actions.isEmpty());
        controls[0].setVisibility(View.VISIBLE);controls[1].setEnabled(false);send("abc");idle(1000);assertTrue(actions.isEmpty());
    }
    @Test public void activityOwnershipIsWeakAndClearedOnStopDestroy() throws Exception {
        Object driver=field(provider,"driver"); Object ref=field(driver,"resumed");assertTrue(ref instanceof WeakReference);assertSame(activity,((WeakReference<?>)ref).get());
        provider.onActivityStopped(activity);assertNull(((WeakReference<?>)field(driver,"resumed")).get());
        provider.onActivityResumed(activity);provider.onActivityDestroyed(activity);assertNull(((WeakReference<?>)field(driver,"resumed")).get());
    }
}
