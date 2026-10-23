package io.github.projectcetacean.tamaemu;

import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic guest owner, real Android touch dispatch and presentation settings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,33,36}, application=TamaemuApplication.class, qualifiers="w240dp-h240dp-round-xhdpi")
public class ControlLayoutAndroidTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private ViewGroup layout;
    private View display;
    private View[] buttons;
    private ButtonState input;
    private final List<String> edges=new ArrayList<>();
    private int ownerTasks;
    private int executionBudget=Integer.MAX_VALUE, viewportWidth=480, viewportHeight=480;
    private long time;
    private LifeStatus playing;

    @Before public void create() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("ui_presentation",0).edit().clear().commit();
        new UiPreferencesStore(app).save(overlay());
        EmulatorSession.Backend backend=new EmulatorSession.Backend(){
            public long open(String r,String s){throw new AssertionError("No guest open");}
            public void close(long h){throw new AssertionError("No guest close");}
            public int width(long h){throw new AssertionError("No guest query");}
            public int height(long h){throw new AssertionError("No guest query");}
            public void advance(long h,int ms,int[] p){throw new AssertionError("No guest advance");}
            public boolean save(long h){throw new AssertionError("No guest save");}
            public void button(long h,int b,boolean down){edges.add(b+":"+down);}
        };
        EmulatorSession.Worker worker=new EmulatorSession.Worker(){
            long now;
            public void execute(Runnable task){
                // Only explicit input delivery is allowed to execute. Other owner work is recorded.
                ownerTasks++; if(ownerTasks<=executionBudget)task.run();
            }
            public void later(Runnable task,int delay){throw new AssertionError("Unexpected owner timer");}
            public long nowMillis(){now+=100;return now;}
        };
        EmulatorSession session=new EmulatorSession(backend,worker,Runnable::run,"synthetic","synthetic");
        set(session,"handle",1L);
        set(session,"listener",new EmulatorSession.Listener(){
            public void state(EmulatorSession.State s){} public void frame(int w,int h,int[] p){}
        });
        set(app,"session",session);
        controller=Robolectric.buildActivity(MainActivity.class).create().visible();activity=controller.get();
        set(activity,"resumed",true);
        LifeRecord record=LifeRecord.from(new long[]{1,10000,1000,1,173,0,2,0});
        playing=new LifeStatus(record,record,LifeStatus.OwnerState.PLAYING,10000,"",false);
        activity.lifeStatus(playing);activity.state(EmulatorSession.State.RUNNING);
        layout=(ViewGroup)get(activity,"layout");display=(View)get(layout,"display");
        buttons=(View[])get(layout,"controls");input=(ButtonState)get(layout,"input");
        if(activity.getResources().getDisplayMetrics().density>2.5f){viewportWidth=576;viewportHeight=555;}
        assertTrue(display.performAccessibilityAction(R.id.ui_action_controls,null));
        measure();time=SystemClock.uptimeMillis();ownerTasks=0;
    }
    @After public void destroy(){if(controller!=null)controller.destroy();}
    private UiPreferences overlay(){return UiPreferences.defaults().withSize(UiPreferences.Size.LARGE)
            .withControls(UiPreferences.Controls.RETRACTABLE).with("controlSpace","OVERLAY");}

    @Test public void disabledCareTapCannotHideControls() throws Exception { disabledCareGesture(false); }
    @Test public void disabledCareHoldCannotOpenSettings() throws Exception { disabledCareGesture(true); }
    private void disabledCareGesture(boolean hold) throws Exception {
        activity.lifeStatus(new LifeStatus(playing.record,playing.record,LifeStatus.OwnerState.SAVING,10000,"",false));measure();
        assertFalse(buttons[0].isEnabled());assertTrue(visible());
        Rect overlap=bounds(buttons[0]);assertTrue("Test must hit the control over the LCD",overlap.intersect(bounds(display)));
        gesture(overlap.exactCenterX(),overlap.exactCenterY(),hold);
        assertTrue("A disabled care tap must not reach the LCD toggle",visible());
        assertFalse("A disabled care hold must not reach LCD Settings",(Boolean)get(layout,"settingsOpen"));
        assertEquals("Disabled care must not dispatch guest input",0,ownerTasks);assertTrue(edges.isEmpty());
        tapTarget();assertFalse("Uncovered LCD tap stays usable while saving",visible());
        longTarget();assertTrue("Uncovered LCD hold stays usable while saving",(Boolean)get(layout,"settingsOpen"));
    }
    @Test public void windowFocusLossCancelsHeldPointerExactlyOnce() throws Exception {
        hold(1);activity.onWindowFocusChanged(false);releasedOnce(1);
        event(MotionEvent.ACTION_MOVE,new int[]{0},new float[]{x(1)},new float[]{y(1)});
        lift(1);releasedOnce(1);assertEquals(0,input.pressedMask());
        activity.onWindowFocusChanged(true);
    }
    @Test public void overlayLargeKeepsViewportWhenToggled() throws Exception {
        Rect before=bounds(display);Rect[] abc={bounds(buttons[0]),bounds(buttons[1]),bounds(buttons[2])};assertEquals(328,display.getWidth());
        for(int i=0;i<3;i++){boolean visible=visible();tapTarget();measure();assertEquals(!visible,visible());assertEquals(before,bounds(display));
            for(int b=0;b<3;b++)assertEquals(abc[b],bounds(buttons[b]));}
        assertEquals(0,ownerTasks);
    }
    @Test public void displayTapWhileHeldIsIgnored() throws Exception {
        hold(1);assertEquals(Arrays.asList("1:true"),edges);
        // A second real pointer on the uncovered target cannot steal B or trigger a long click.
        WatchGeometry.Bounds target=geometry().displayTapTarget;
        float x=(target.left+target.right)/2f,y=(target.top+target.bottom)/2f;
        multi(MotionEvent.ACTION_POINTER_DOWN|(1<<8),x,y);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+20));
        multi(MotionEvent.ACTION_POINTER_UP|(1<<8),x,y);
        assertTrue(visible());assertFalse((Boolean)get(layout,"settingsOpen"));assertEquals(2,input.pressedMask());
        assertEquals(Arrays.asList("1:true"),edges);
        // Tap as well as hold while B remains owned.
        multi(MotionEvent.ACTION_POINTER_DOWN|(1<<8),x,y);multi(MotionEvent.ACTION_POINTER_UP|(1<<8),x,y);
        assertTrue(visible());assertEquals(Arrays.asList("1:true"),edges);
        lift(1);tapTarget();assertFalse(visible());assertEquals(Arrays.asList("1:true","1:false"),edges);
    }
    @Test public void forcedTransitionsWhileHeldReleaseOnce() throws Exception {
        Rect before=bounds(display);hold(1);
        assertTrue(display.performAccessibilityAction(R.id.ui_action_controls,null));measure();
        assertFalse(visible());assertEquals(before,bounds(display));releasedOnce(1);lift(1);releasedOnce(1);
        for(String transition:new String[]{"position","size","back","pause"}){
            edges.clear();if(!visible())assertTrue(display.performAccessibilityAction(R.id.ui_action_controls,null));measure();hold(1);assertEquals(Arrays.asList("1:true"),edges);
            if(transition.equals("position"))apply(overlay().with("placement","RIGHT"));
            if(transition.equals("size"))apply(overlay().withSize(UiPreferences.Size.SMALL));
            if(transition.equals("back"))activity.onBackPressed();
            if(transition.equals("pause")){
                // Execute the required release edge, then record the asynchronous pause without saving.
                executionBudget=ownerTasks+1;activity.onPause();
            }
            measure();releasedOnce(1);
            lift(1);releasedOnce(1);assertEquals(0,input.pressedMask());
        }
    }
    @Test public void repositionWithSameFirstButtonReleasesInput() throws Exception {
        // Install an old geometry with the new LCD and first top, but a different C rectangle.
        // This isolates the incomplete comparison even when platform rounding changes other bounds.
        WatchGeometry old=geometry();apply(overlay().with("placement","RIGHT"));
        WatchGeometry next=geometry();
        set(old,"display",next.display);old.button[0]=next.button[0];old.button[1]=next.button[1];
        set(layout,"geometry",old);hold(2);measure();releasedOnce(2);
    }
    @Test public void eachPlacementDeliversAbc() throws Exception {
        for(UiPreferences.ControlSpace space:UiPreferences.ControlSpace.values())for(UiPreferences.Placement p:UiPreferences.Placement.values()){
            apply(overlay().withControls(UiPreferences.Controls.VISIBLE).with("controlSpace",space.name()).with("placement",p.name()));
            edges.clear();for(int b=0;b<3;b++){hold(b);lift(b);}
            assertEquals(space+"/"+p,Arrays.asList("0:true","0:false","1:true","1:false","2:true","2:false"),edges);
            for(View b:buttons){assertTrue(b.getWidth()>=96);assertTrue(b.getHeight()>=96);assertTrue(layout.indexOfChild(b)>layout.indexOfChild(display));}
        }
    }
    @Test public void uncoveredDisplayStillTogglesAndHolds() throws Exception {
        tapTarget();assertFalse(visible());longTarget();assertTrue((Boolean)get(layout,"settingsOpen"));assertEquals(0,ownerTasks);
    }
    @Test @Config(qualifiers="w192dp-h185dp-round-xxhdpi")
    public void integerSmallShellTargetTogglesAndHolds() throws Exception {
        viewportWidth=576;viewportHeight=555;
        apply(overlay().withSize(UiPreferences.Size.SMALL).with("integerPixels","true"));
        assertEquals(128,display.getWidth());WatchGeometry.Bounds t=geometry().displayTapTarget;
        float x=t.left+1,y=t.top+1;
        assertFalse("Fixture must exercise the shell outside the exact LCD",bounds(display).contains((int)x,(int)y));
        hold(1);multi(MotionEvent.ACTION_POINTER_DOWN|(1<<8),x,y);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+20));
        multi(MotionEvent.ACTION_POINTER_UP|(1<<8),x,y);
        assertTrue(visible());assertFalse((Boolean)get(layout,"settingsOpen"));assertEquals(Arrays.asList("1:true"),edges);
        lift(1);int inputWork=ownerTasks;
        gesture(x,y,false);assertFalse(visible());gesture(x,y,true);assertTrue((Boolean)get(layout,"settingsOpen"));
        assertEquals(inputWork,ownerTasks);
    }
    @Test @Config(qualifiers="w192dp-h185dp-round-xxhdpi")
    public void shellTargetExposesTheDisplayAccessibilityActions() throws Exception {
        apply(overlay().withSize(UiPreferences.Size.SMALL).with("integerPixels","true"));
        View target=(View)get(layout,"displayTapTarget");
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES,target.getImportantForAccessibility());assertTrue(target.isFocusable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,display.getImportantForAccessibility());
        List<android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction> actions=target.createAccessibilityNodeInfo().getActionList();
        boolean controls=false,settings=false;for(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction a:actions){
            controls|=a.getId()==R.id.ui_action_controls;settings|=a.getId()==R.id.ui_action_settings;
        }
        assertTrue(controls);assertTrue(settings);
        hold(1);assertTrue(target.performAccessibilityAction(R.id.ui_action_controls,null));releasedOnce(1);assertFalse(visible());
        assertTrue(target.performAccessibilityAction(R.id.ui_action_controls,null));assertTrue(visible());
        assertTrue(target.performAccessibilityAction(R.id.ui_action_settings,null));assertTrue((Boolean)get(layout,"settingsOpen"));
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,target.getImportantForAccessibility());
    }
    @Test public void displayToggleAnnouncesResultAndKeepsNextActionLabels() throws Exception {
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES,display.getImportantForAccessibility());
        assertControlFeedback(display);
    }
    @Test @Config(qualifiers="w192dp-h185dp-round-xxhdpi")
    public void shellToggleAnnouncesResultAndKeepsNextActionLabels() throws Exception {
        apply(overlay().withSize(UiPreferences.Size.SMALL).with("integerPixels","true"));
        View target=(View)get(layout,"displayTapTarget");
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES,target.getImportantForAccessibility());
        assertControlFeedback(target);
    }
    private void assertControlFeedback(View target) throws Exception {
        AccessibilityManager manager=(AccessibilityManager)activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(manager).setEnabled(true);
        List<String> announcements=new ArrayList<>();List<View> senders=new ArrayList<>();
        layout.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public boolean onRequestSendAccessibilityEvent(ViewGroup host,View child,AccessibilityEvent event){
                if(event.getEventType()==AccessibilityEvent.TYPE_ANNOUNCEMENT){
                    senders.add(child);for(CharSequence value:event.getText())announcements.add(value.toString());
                }
                return super.onRequestSendAccessibilityEvent(host,child,event);
            }
        });
        assertControlActionLabel(target,"Hide controls");hold(1);int inputWork=ownerTasks;
        assertTrue(target.performAccessibilityAction(R.id.ui_action_controls,null));
        assertFalse(visible());releasedOnce(1);lift(1);releasedOnce(1);
        assertEquals(Arrays.asList("Controls hidden"),announcements);
        assertControlActionLabel(target,"Show controls");
        assertTrue(target.performAccessibilityAction(R.id.ui_action_controls,null));
        assertTrue(visible());assertControlActionLabel(target,"Hide controls");
        assertEquals(Arrays.asList("Controls hidden","Controls shown"),announcements);
        assertEquals(Arrays.asList(target,target),senders);
        assertEquals("Only the required input release may dispatch owner work",inputWork+1,ownerTasks);
        LifeUiModel model=(LifeUiModel)get(activity,"lifeUi");assertSame(playing,model.status());
    }
    private void assertControlActionLabel(View target,String expected){
        for(AccessibilityNodeInfo.AccessibilityAction action:target.createAccessibilityNodeInfo().getActionList()){
            if(action.getId()==R.id.ui_action_controls){assertEquals(expected,action.getLabel().toString());return;}
        }
        fail("Missing control accessibility action");
    }
    @Test public void settingsHintMatchesConstructionAndBothModeTransitions() throws Exception {
        for(UiPreferences.Controls initial:UiPreferences.Controls.values()){
            UiPreferences requested=overlay().withControls(initial).with("haptics","true").with("sound","false")
                    .with("shell","SAGE").with("placement","RIGHT");
            apply(requested);open();View panel=(View)get(layout,"settings");
            String[] hints=initial==UiPreferences.Controls.RETRACTABLE
                    ?new String[]{"Tap display for controls. Hold for settings.","Hold for settings.","Tap display for controls. Hold for settings."}
                    :new String[]{"Hold for settings.","Tap display for controls. Hold for settings.","Hold for settings."};
            UiPreferences.Controls[] modes=initial==UiPreferences.Controls.RETRACTABLE
                    ?new UiPreferences.Controls[]{UiPreferences.Controls.RETRACTABLE,UiPreferences.Controls.VISIBLE,UiPreferences.Controls.RETRACTABLE}
                    :new UiPreferences.Controls[]{UiPreferences.Controls.VISIBLE,UiPreferences.Controls.RETRACTABLE,UiPreferences.Controls.VISIBLE};
            TextView hint=text(panel,hints[0]);assertNotNull("Construction hint for "+initial,hint);
            Button mode=findPrefix("Controls:");assertNotNull(mode);
            for(int i=0;i<3;i++){
                if(i>0)mode.performClick();
                assertEquals("Hint refreshes synchronously",hints[i],hint.getText().toString());
                assertSame("Keep the existing panel",panel,get(layout,"settings"));
                java.util.Map<String,String> expected=new java.util.LinkedHashMap<>(requested.encode());
                expected.put("controls",modes[i].name());assertEquals(expected,prefs().encode());
                assertEquals("Selected settings persist",expected,new UiPreferencesStore(activity).load().encode());
            }
            activity.onBackPressed();measure();
        }
        assertEquals("Mode guidance must not dispatch owner work",0,ownerTasks);assertTrue(edges.isEmpty());
        LifeUiModel model=(LifeUiModel)get(activity,"lifeUi");assertSame(playing,model.status());
    }
    @Test public void alwaysVisibleDisplayTapKeepsControlsAndHoldOpensSettings() throws Exception {
        apply(overlay().withControls(UiPreferences.Controls.VISIBLE));
        tapTarget();assertTrue(visible());assertFalse((Boolean)get(layout,"settingsOpen"));
        // Settle tap feedback before starting the independent hold.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getPressedStateDuration()));
        longTarget();assertTrue((Boolean)get(layout,"settingsOpen"));
        assertNotNull(text(layout,"Hold for settings."));
        assertEquals(0,ownerTasks);assertTrue(edges.isEmpty());
    }
    @Test public void hiddenControlsNotFocusable() throws Exception {
        tapTarget();
        for(View b:buttons){assertEquals(View.INVISIBLE,b.getVisibility());assertFalse(b.isFocusable());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,b.getImportantForAccessibility());}
        assertTrue(display.performAccessibilityAction(R.id.ui_action_controls,null));assertTrue(visible());
        for(View b:buttons){assertTrue(b.isFocusable());assertTrue(b.isEnabled());}
    }
    @Test public void integerRowStatesResult() throws Exception {
        apply(overlay().with("integerPixels","true"));open();
        int id=activity.getResources().getIdentifier("ui_integer_result","string",activity.getPackageName());
        assertTrue("Integer result resource is required",id!=0);
        assertNotNull(text(layout,activity.getString(id,2,256,328)));
    }
    @Test public void settingsRowsKeepIndependentChoicesAndExplainFallback() throws Exception {
        open();Button space=findPrefix("Control space:");assertNotNull(space);space.performClick();measure();
        assertEquals(UiPreferences.ControlSpace.RESERVED,prefs().controlSpace);
        Button position=findPrefix("Position:");assertNotNull(position);
        for(String value:new String[]{"LEFT","RIGHT","TOP","BOTTOM"}){position.performClick();measure();assertEquals(value,prefs().placement.name());}
        // Feed a real narrow valid geometry to the panel's geometry consumer contract.
        UiPreferences requested=overlay().withSize(UiPreferences.Size.SMALL).with("integerPixels","true").with("placement","RIGHT");
        apply(requested);WatchGeometry narrow=WatchGeometry.calculate(160,120,1,false,requested,true);
        assertTrue(narrow.valid);assertTrue(narrow.integerFallback);assertTrue(narrow.placementFallback);
        Object panel=get(layout,"settings");Method changed=SettingsPanel.class.getDeclaredMethod("geometryChanged",WatchGeometry.class);
        changed.setAccessible(true);changed.invoke(panel,narrow);
        assertNotNull(text(layout,"Integer pixels: On: too small, using Fit"));
        assertNotNull(text(layout,"Right unavailable here: using Bottom"));
        assertTrue(prefs().integerPixels);assertEquals(UiPreferences.Placement.RIGHT,prefs().placement);
    }
    @Test public void sizeChangeDoesNotForceControls() throws Exception {
        apply(UiPreferences.defaults().withControls(UiPreferences.Controls.VISIBLE));open();
        findPrefix("Display size:").performClick();measure();
        assertEquals(UiPreferences.Size.LARGE,prefs().size);assertEquals(UiPreferences.Controls.VISIBLE,prefs().controls);
        assertEquals(UiPreferences.ControlSpace.RESERVED,prefs().controlSpace);
    }
    @Test public void sizeChoicePreservesHiddenRetractableState() throws Exception {
        tapTarget();assertFalse(visible());open();findPrefix("Display size:").performClick();measure();
        assertEquals(UiPreferences.Controls.RETRACTABLE,prefs().controls);assertFalse(visible());
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void overlayControlsHaveOpaqueContrastBacking() throws Exception {
        View b=buttons[1];android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(b.getWidth(),b.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        b.draw(new android.graphics.Canvas(image));
        assertEquals("Overlay target backing covers the artwork inset over the LCD",255,android.graphics.Color.alpha(image.getPixel(b.getWidth()/2,1)));
    }
    @Test public void presentationDoesNotChangeOwnerModeDebtOrGuest() throws Exception {
        open();for(String prefix:new String[]{"Display size:","Control space:","Position:","Integer pixels:","Display effect:","Shell color:","Button color:","Button shape:"}){
            Button b=findPrefix(prefix);assertNotNull(prefix,b);b.performClick();measure();
        }
        activity.onBackPressed();measure();
        assertEquals("No resume/pause/advance/mode owner dispatch",0,ownerTasks);
        LifeUiModel model=(LifeUiModel)get(activity,"lifeUi");assertSame(playing,model.status());
        assertEquals(173,model.status().debtMs);assertEquals(0,model.selectedMode());
    }
    private void open(){assertTrue(display.performAccessibilityAction(R.id.ui_action_settings,null));measure();}
    private UiPreferences prefs() throws Exception{return (UiPreferences)get(activity,"preferences");}
    private void apply(UiPreferences p) throws Exception {
        // Use the real settings listener to exercise forced transitions without opening Settings first.
        boolean wasOpen=(Boolean)get(layout,"settingsOpen");
        if(get(layout,"settings")==null){Method m=layout.getClass().getDeclaredMethod("createSettings");m.setAccessible(true);m.invoke(layout);}
        Object settings=get(layout,"settings");Method update=SettingsPanel.class.getDeclaredMethod("update",UiPreferences.class,boolean.class);
        update.setAccessible(true);update.invoke(settings,p,false);measure();
        if(!wasOpen){layout.removeView((View)settings);set(layout,"settings",null);}
    }
    private void releasedOnce(int b){assertEquals(Arrays.asList(b+":true",b+":false"),edges);}
    private boolean visible() throws Exception{return (Boolean)get(layout,"controlsVisible");}
    private WatchGeometry geometry() throws Exception{return (WatchGeometry)get(layout,"geometry");}
    private void tapTarget() throws Exception{WatchGeometry.Bounds b=geometry().displayTapTarget;gesture((b.left+b.right)/2f,(b.top+b.bottom)/2f,false);}
    private void longTarget() throws Exception{WatchGeometry.Bounds b=geometry().displayTapTarget;gesture((b.left+b.right)/2f,(b.top+b.bottom)/2f,true);}
    private void gesture(float x,float y,boolean hold){event(MotionEvent.ACTION_DOWN,new int[]{7},new float[]{x},new float[]{y});
        if(hold)Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+20));
        event(MotionEvent.ACTION_UP,new int[]{7},new float[]{x},new float[]{y});}
    private float x(int b){return (buttons[b].getLeft()+buttons[b].getRight())/2f;}
    private float y(int b){return (buttons[b].getTop()+buttons[b].getBottom())/2f;}
    private void hold(int b){event(MotionEvent.ACTION_DOWN,new int[]{0},new float[]{x(b)},new float[]{y(b)});}
    private void lift(int b){event(MotionEvent.ACTION_UP,new int[]{0},new float[]{x(b)},new float[]{y(b)});}
    private void multi(int action,float x,float y){event(action,new int[]{0,1},new float[]{x(1),x},new float[]{y(1),y});}
    private void event(int action,int[] ids,float[] xs,float[] ys){
        MotionEvent.PointerProperties[] pp=new MotionEvent.PointerProperties[ids.length];MotionEvent.PointerCoords[] pc=new MotionEvent.PointerCoords[ids.length];
        for(int i=0;i<ids.length;i++){pp[i]=new MotionEvent.PointerProperties();pp[i].id=ids[i];pp[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            pc[i]=new MotionEvent.PointerCoords();pc[i].x=xs[i];pc[i].y=ys[i];pc[i].pressure=1;pc[i].size=1;}
        time+=100;MotionEvent e=MotionEvent.obtain(0,time,action,ids.length,pp,pc,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{layout.dispatchTouchEvent(e);}finally{e.recycle();}
        if((action&MotionEvent.ACTION_MASK)==MotionEvent.ACTION_UP)Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void measure(){layout.measure(View.MeasureSpec.makeMeasureSpec(viewportWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(viewportHeight,View.MeasureSpec.EXACTLY));layout.layout(0,0,viewportWidth,viewportHeight);}
    private static Rect bounds(View v){return new Rect(v.getLeft(),v.getTop(),v.getRight(),v.getBottom());}
    private Button findPrefix(String prefix){return prefix(layout,prefix);}
    private static Button prefix(View v,String prefix){if(v instanceof Button&&((Button)v).getText().toString().startsWith(prefix))return (Button)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=prefix(((ViewGroup)v).getChildAt(i),prefix);if(b!=null)return b;}return null;}
    private static TextView text(View v,String label){if(v instanceof TextView&&((TextView)v).getText().toString().equals(label))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=text(((ViewGroup)v).getChildAt(i),label);if(t!=null)return t;}return null;}
    private static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
}
