package io.github.projectcetacean.tamaemu;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Actual presentation views, with no emulator, native pet state, or global settings changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36}, application = Application.class, qualifiers = "w240dp-h240dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PresentationAndroidTest {
    @Test @Config(application=TamaemuApplication.class)
    public void settingsOverlayHidesPeersAndRestoresWholeUpdatedFrame() throws Exception {
        TamaemuApplication app=(TamaemuApplication)RuntimeEnvironment.getApplication();final int[] work={0};
        EmulatorSession.Backend backend=new EmulatorSession.Backend(){
            public long open(String r,String s){throw new AssertionError("No native pet in overlay test");}
            public void close(long h){}public int width(long h){return 128;}public int height(long h){return 128;}
            public void advance(long h,int elapsed,int[] pixels){throw new AssertionError("No guest advance");}
            public void button(long h,int b,boolean down){}public boolean save(long h){throw new AssertionError("No pet save");}
        };
        EmulatorSession.Worker worker=new EmulatorSession.Worker(){
            public void execute(Runnable task){work[0]++;}public void later(Runnable task,int delay){work[0]++;}public long nowMillis(){return 1;}
        };
        assign(app,"session",new EmulatorSession(backend,worker,Runnable::run,"unused","unused"));
        ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create().visible();
        try {
            MainActivity activity=owner.get();assign(activity,"resumed",true);activity.state(EmulatorSession.State.RUNNING);
            LifeRecord record=LifeRecord.from(new long[]{1,10000,1000,1,0,0,2,0});
            LifeStatus playing=new LifeStatus(record,record,LifeStatus.OwnerState.PLAYING,10000,"",false);activity.lifeStatus(playing);
            ViewGroup shell=(ViewGroup)field(activity,"layout");PixelView lcd=(PixelView)field(shell,"display");
            View[] controls=(View[])field(shell,"controls");int[] first=new int[128*128];Arrays.fill(first,Color.GREEN);
            activity.frame(128,128,first);layout(shell,240,240);
            Rect lcdBounds=new Rect(lcd.getLeft(),lcd.getTop(),lcd.getRight(),lcd.getBottom());
            Rect[] targetBounds=new Rect[3];for(int i=0;i<3;i++)targetBounds[i]=new Rect(controls[i].getLeft(),controls[i].getTop(),controls[i].getRight(),controls[i].getBottom());
            assertTrue(lcd.performLongClick());layout(shell,240,240);
            assertEquals("Settings hides the complete LCD peer",View.INVISIBLE,lcd.getVisibility());
            for(View control:controls)assertEquals("No peer button artwork can leak below Settings",View.INVISIBLE,control.getVisibility());
            assertSame(playing,((LifeUiModel)field(activity,"lifeUi")).status());
            assertEquals(0,((LifeUiModel)field(activity,"lifeUi")).selectedMode());
            int[] updated=new int[128*128];
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)updated[y*128+x]=x<4?Color.BLUE:x>=124?Color.RED:y<4?Color.YELLOW:y>=124?Color.WHITE:0xff507050;
            activity.frame(128,128,updated);assertArrayEquals("Hidden LCD continues caching frames",updated,lcd.copyRawFrame());
            activity.onBackPressed();layout(shell,240,240);
            assertEquals(View.VISIBLE,lcd.getVisibility());assertEquals(lcdBounds,new Rect(lcd.getLeft(),lcd.getTop(),lcd.getRight(),lcd.getBottom()));
            for(int i=0;i<3;i++){assertEquals(View.VISIBLE,controls[i].getVisibility());assertTrue(controls[i].isEnabled());
                assertEquals(targetBounds[i],new Rect(controls[i].getLeft(),controls[i].getTop(),controls[i].getRight(),controls[i].getBottom()));}
            Bitmap restored=render(lcd);assertEquals(Color.BLUE,restored.getPixel(0,0));assertEquals(Color.RED,restored.getPixel(restored.getWidth()-1,0));
            assertEquals(Color.YELLOW,restored.getPixel(restored.getWidth()/2,0));assertEquals(Color.WHITE,restored.getPixel(restored.getWidth()/2,restored.getHeight()-1));
            assertTrue(lcd.performLongClick());
            ViewGroup settings=(ViewGroup)field(shell,"settings"),content=(ViewGroup)settings.getChildAt(0);List<Button> actions=new ArrayList<>();
            for(int i=0;i<content.getChildCount();i++)if(content.getChildAt(i) instanceof Button)actions.add((Button)content.getChildAt(i));
            assertTrue(find(actions,activity.getString(R.string.ui_done)).performClick());
            assertEquals(View.VISIBLE,lcd.getVisibility());assertTrue(restored.sameAs(render(lcd)));
            for(View control:controls)assertEquals(View.VISIBLE,control.getVisibility());
            assertSame(playing,((LifeUiModel)field(activity,"lifeUi")).status());assertEquals("Opening/closing Settings does not enqueue owner work",0,work[0]);
        }finally{owner.destroy();}
    }

    @Test public void disabledLifeSelectorsKeepCommittedModeVisuallyDistinct() throws Exception {
        Context context=RuntimeEnvironment.getApplication();LifeUiModel model=new LifeUiModel();
        LifeRecord record=LifeRecord.from(new long[]{1,10000,1000,1,500,0,2,0});
        model.update(new LifeStatus(record,record,LifeStatus.OwnerState.CATCHING_UP,10000,"",false));
        LifeSettingsPanel panel=new LifeSettingsPanel(model,new LifeSettingsPanel.Listener(){
            public void mode(boolean protectedAway){}public void retry(){}
        });
        panel.append(new android.widget.LinearLayout(context));
        Button normal=(Button)field(panel,"normal"),protect=(Button)field(panel,"protect");
        assertFalse(normal.isEnabled());assertFalse(protect.isEnabled());
        assertTrue(normal.isSelected());assertFalse(protect.isSelected());
        assertTrue(normal.getContentDescription().toString().contains(normal.getText()));
        Bitmap committed=Bitmap.createBitmap(160,48,Bitmap.Config.ARGB_8888);
        Bitmap alternative=Bitmap.createBitmap(160,48,Bitmap.Config.ARGB_8888);
        normal.getBackground().setBounds(0,0,160,48);protect.getBackground().setBounds(0,0,160,48);
        normal.getBackground().draw(new Canvas(committed));protect.getBackground().draw(new Canvas(alternative));
        assertFalse("Disabled selectors must still show the committed life mode",committed.sameAs(alternative));
        assertNotEquals("Disabled selected text retains its mode cue",normal.getCurrentTextColor(),protect.getCurrentTextColor());
    }

    @Test @Config(application=TamaemuApplication.class)
    public void recessedChromePreservesLcdEdgesAndFullButtonTargets() throws Exception {
        TamaemuApplication app=(TamaemuApplication)RuntimeEnvironment.getApplication();
        EmulatorSession.Backend backend=new EmulatorSession.Backend(){
            public long open(String r,String s){throw new AssertionError("No native pet in chrome test");}
            public void close(long h){}public int width(long h){return 128;}public int height(long h){return 128;}
            public void advance(long h,int elapsed,int[] pixels){throw new AssertionError("No guest advance");}
            public void button(long h,int b,boolean down){}public boolean save(long h){throw new AssertionError("No pet save");}
        };
        EmulatorSession.Worker worker=new EmulatorSession.Worker(){
            public void execute(Runnable task){}public void later(Runnable task,int delay){}public long nowMillis(){return 1;}
        };
        assign(app,"session",new EmulatorSession(backend,worker,Runnable::run,"unused","unused"));
        ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create().visible();
        try {
            MainActivity activity=owner.get();assign(activity,"resumed",true);
            activity.state(EmulatorSession.State.RUNNING);
            LifeRecord record=LifeRecord.from(new long[]{1,10000,1000,1,0,0,2,0});
            activity.lifeStatus(new LifeStatus(record,record,LifeStatus.OwnerState.PLAYING,10000,"",false));
            ViewGroup shell=(ViewGroup)field(activity,"layout");PixelView lcd=(PixelView)field(shell,"display");
            int[] source=new int[128*128];
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)source[y*128+x]=x<4?Color.BLUE:x>=124?Color.RED:y<4?Color.YELLOW:y>=124?Color.WHITE:0xff507050;
            activity.frame(128,128,source);layout(shell,240,240);Bitmap scene=render(shell);
            assertEquals(Color.BLUE,scene.getPixel(lcd.getLeft(),lcd.getTop()));
            assertEquals(Color.RED,scene.getPixel(lcd.getRight()-1,lcd.getTop()));
            assertEquals(Color.BLUE,scene.getPixel(lcd.getLeft(),lcd.getBottom()-1));
            assertEquals(Color.RED,scene.getPixel(lcd.getRight()-1,lcd.getBottom()-1));
            assertEquals(Color.YELLOW,scene.getPixel(lcd.getLeft()+lcd.getWidth()/2,lcd.getTop()));
            assertEquals(Color.WHITE,scene.getPixel(lcd.getLeft()+lcd.getWidth()/2,lcd.getBottom()-1));
            assertArrayEquals("The bezel never edits the raw LCD",source,lcd.copyRawFrame());
            int middle=lcd.getTop()+lcd.getHeight()/2;
            assertNotEquals("A recessed bezel is painted outside the complete LCD",scene.getPixel(lcd.getLeft()-6,middle),scene.getPixel(lcd.getLeft()-2,middle));
            View[] controls=(View[])field(shell,"controls");
            for(View target:controls){
                assertEquals(48,target.getWidth());assertEquals(48,target.getHeight());
                assertEquals("Artwork inset leaves the native target intact",0,Color.alpha(render(target).getPixel(24,1)));
            }
            android.view.MotionEvent down=android.view.MotionEvent.obtain(1,1,android.view.MotionEvent.ACTION_DOWN,2,24,0);
            android.view.MotionEvent up=android.view.MotionEvent.obtain(1,2,android.view.MotionEvent.ACTION_UP,2,24,0);
            try {
                assertTrue(controls[0].onTouchEvent(down));assertTrue(((ButtonState)field(shell,"input")).hasActiveInput());
                assertTrue(controls[0].onTouchEvent(up));assertFalse(((ButtonState)field(shell,"input")).hasActiveInput());
            }finally{down.recycle();up.recycle();}
            String directory=System.getProperty("tamaemu.chromeScreenshots");
            if(directory!=null){
                java.io.File file=new java.io.File(directory,"chrome-api"+Build.VERSION.SDK_INT+".png");file.getParentFile().mkdirs();
                try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){assertTrue(scene.compress(Bitmap.CompressFormat.PNG,100,output));}
            }
        }finally{owner.destroy();}
    }

    @Test public void lifePanelHidesRoutineSchedulerTextButRetainsFailureAndPetFeedback() throws Exception {
        Context context=RuntimeEnvironment.getApplication();LifeUiModel model=new LifeUiModel();
        LifeSettingsPanel panel=new LifeSettingsPanel(model,new LifeSettingsPanel.Listener(){
            public void mode(boolean protectedAway){}public void retry(){}
        });
        android.widget.LinearLayout content=new android.widget.LinearLayout(context);panel.append(content);
        TextView scheduler=(TextView)field(panel,"scheduler");
        assertEquals("Pending scheduling is not user-facing pet status",View.GONE,scheduler.getVisibility());
        LifeRecord record=LifeRecord.from(new long[]{1,10000,1000,1,500,0,2,1});
        model.update(new LifeStatus(record,record,LifeStatus.OwnerState.CATCHING_UP,10000,"Background aging scheduled",false));
        panel.refresh(null);
        assertEquals("Routine scheduler success stays out of the life surface",View.GONE,scheduler.getVisibility());
        assertEquals(context.getString(R.string.life_catching_up,LifeUiModel.duration(500)),((TextView)field(panel,"progress")).getText().toString());
        assertEquals(View.VISIBLE,((View)field(panel,"clock")).getVisibility());
        String saved=((TextView)field(panel,"saved")).getText().toString();assertTrue(saved.contains("UTC"));
        model.update(new LifeStatus(record,record,LifeStatus.OwnerState.CATCHING_UP,10000,"Unavailable",true));panel.refresh(null);
        assertEquals(View.VISIBLE,scheduler.getVisibility());assertEquals(context.getString(R.string.life_scheduling_failed),scheduler.getText().toString());
        assertEquals(saved,((TextView)field(panel,"saved")).getText().toString());
        assertEquals(View.VISIBLE,((View)field(panel,"clock")).getVisibility());
    }

    @Test public void settingsCardsKeepNativeLabelsActionsAndVisibleFocus() {
        ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            final UiPreferences[] selected={UiPreferences.defaults().with("sharpness","off")};
            SettingsPanel panel=new SettingsPanel(owner.get(),selected[0],new SettingsPanel.Listener(){
                public void changed(UiPreferences preferences,boolean sizeChanged){selected[0]=preferences;}
                public void done(){}
            },null);
            owner.get().setContentView(panel);layout(panel,200,200);
            ViewGroup content=(ViewGroup)panel.getChildAt(0);List<Button> buttons=new ArrayList<>();
            for(int i=0;i<content.getChildCount();i++)if(content.getChildAt(i) instanceof Button)buttons.add((Button)content.getChildAt(i));
            String clean=owner.get().getString(R.string.ui_effect,owner.get().getString(R.string.ui_clean));
            Button effect=find(buttons,clean);
            assertEquals("Settings cards read from the leading edge",Gravity.START,
                    effect.getGravity()&Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
            assertTrue("Native actions retain bounded ripple feedback",effect.getBackground() instanceof android.graphics.drawable.RippleDrawable);
            assertEquals(clean,effect.createAccessibilityNodeInfo().getText().toString());
            effect.setFocusableInTouchMode(true);effect.clearFocus();
            Bitmap normal=render(effect);assertTrue(effect.requestFocus());
            effect.refreshDrawableState();effect.getBackground().jumpToCurrentState();
            assertFalse("Keyboard focus remains visible in the card artwork",normal.sameAs(render(effect)));
            assertEquals(clean,effect.getText().toString());
            effect.performClick();assertEquals(UiPreferences.Effect.SOFT_LCD,selected[0].effect);
            assertEquals(owner.get().getString(R.string.ui_effect,owner.get().getString(R.string.ui_soft_lcd)),
                    effect.createAccessibilityNodeInfo().getText().toString());
        }finally{owner.pause().stop().destroy();}
    }

    @Test public void effectsAndCallerMutationsCannotRewriteTheRawFrame() {
        PixelView view = new PixelView(RuntimeEnvironment.getApplication());
        view.appearance(UiPreferences.defaults().with("sharpness","off"));
        layout(view, 32, 24);
        int[] source = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE};
        int[] expected = source.clone();
        view.frame(2, 2, source);
        for (UiPreferences.Effect effect : UiPreferences.Effect.values()) {
            view.appearance(UiPreferences.defaults().with("sharpness","off").with("effect", effect.name()));
            render(view);
            assertArrayEquals("An effect must leave the caller's pixels unchanged", expected, source);
            assertArrayEquals("Snapshot consumers receive raw colors in every effect", expected, view.copyRawFrame());
        }
        source[0] = Color.MAGENTA;
        assertArrayEquals("The renderer must own its frame after submission", expected, view.copyRawFrame());
        int[] copy = view.copyRawFrame();
        copy[1] = Color.BLACK;
        assertArrayEquals("A snapshot consumer cannot mutate the renderer", expected, view.copyRawFrame());
        view.appearance(UiPreferences.defaults().with("sharpness","off"));
        assertEquals("Caller mutation cannot alter the uploaded bitmap", Color.RED, render(view).getPixel(6, 6));
    }

    @Test public void identicalFramesSkipUploadButAppearanceAndGeometryRedraw() throws Exception {
        PixelView view = new PixelView(RuntimeEnvironment.getApplication());
        view.appearance(UiPreferences.defaults().with("sharpness","off"));
        layout(view, 24, 24);
        int[] source = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE};
        view.frame(2, 2, source);
        Bitmap uploaded = uploaded(view);
        int generation = uploaded.getGenerationId();
        Shadows.shadowOf(view).clearWasInvalidated();
        view.frame(2, 2, source.clone());
        assertSame("Equal content must reuse the upload", uploaded, uploaded(view));
        assertEquals("Equal content must not upload again", generation, uploaded.getGenerationId());
        assertFalse("Equal content needs no redraw", Shadows.shadowOf(view).wasInvalidated());
        // Equal hashes are not evidence of equal pixels: these changes collide under Arrays.hashCode.
        int originalHash = Arrays.hashCode(source);
        source[0] += 1; source[1] -= 31;
        assertEquals(originalHash, Arrays.hashCode(source));
        view.frame(2, 2, source);
        assertTrue(Shadows.shadowOf(view).wasInvalidated());
        assertNotEquals(generation, uploaded.getGenerationId());
        assertEquals(source[0], render(view).getPixel(6, 6));
        generation = uploaded.getGenerationId();
        Shadows.shadowOf(view).clearWasInvalidated();
        view.appearance(UiPreferences.defaults().with("sharpness","off").with("effect", "SOFT_LCD"));
        assertTrue("Static content must redraw when appearance changes", Shadows.shadowOf(view).wasInvalidated());
        assertEquals("Appearance is a Canvas treatment, not another upload", generation, uploaded.getGenerationId());
        view.appearance(UiPreferences.defaults().with("sharpness","off"));
        // Android's geometry path marks the view dirty, even without a new guest frame.
        view.draw(new Canvas(Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)));
        layout(view, 40, 24);
        assertTrue("New geometry must schedule a redraw", view.isDirty());
        Bitmap wide = render(view);
        assertEquals("All source pixels remain centered with no crop", source[0], wide.getPixel(9, 1));
        assertEquals(Color.TRANSPARENT, wide.getPixel(1, 1));
        assertEquals(generation, uploaded.getGenerationId());
    }

    @Test public void softwareCanvasGlassDrawsItsFallbackInsideTheWholeDisplay() {
        PixelView view = new PixelView(RuntimeEnvironment.getApplication());
        view.appearance(UiPreferences.defaults().with("sharpness","off"));
        layout(view, 40, 24);
        int[] source = new int[16];
        Arrays.fill(source, 0xff507050);
        view.frame(4, 4, source);
        view.appearance(UiPreferences.defaults().with("sharpness","off").with("effect", "SOFT_LCD"));
        Bitmap soft = render(view);
        view.appearance(UiPreferences.defaults().with("sharpness","off").with("effect", "LCD_GLASS"));
        Bitmap glass = render(view);
        assertFalse(new Canvas(glass).isHardwareAccelerated());
        assertNotEquals("Glass must render on software Canvas, including API 26", soft.getPixel(11, 3), glass.getPixel(11, 3));
        assertEquals("Opaque LCD content survives fallback", 255, Color.alpha(glass.getPixel(20, 12)));
        for (int y = 0; y < 24; y++) for (int x = 0; x < 40; x++) {
            if (x < 8 || x >= 32) assertEquals("Effects cannot spill into the shell", Color.TRANSPARENT, glass.getPixel(x, y));
        }
        assertArrayEquals(source, view.copyRawFrame());
    }

    @Test public void largeFontSettingsKeepLabeledTargetsScrollableAndDoneReachable() {
        // Keyboard focus is meaningful only outside touch mode. Use the test
        // instrumentation input-mode API before creating this isolated window.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            Context base = owner.get();
            Configuration large = new Configuration(base.getResources().getConfiguration());
            large.fontScale = 2f;
            Context context = new ContextThemeWrapper(base.createConfigurationContext(large), android.R.style.Theme_Material_NoActionBar);
            assertEquals(2f, context.getResources().getConfiguration().fontScale, 0f);
            final int[] done = {0};
            final UiPreferences[] selected = {null};
            SettingsPanel panel = new SettingsPanel(context, UiPreferences.defaults().with("sharpness","off"), new SettingsPanel.Listener() {
                public void changed(UiPreferences preferences, boolean sizeChanged) { selected[0] = preferences; }
                public void done() { done[0]++; }
            }, null);
            int viewport = Math.round(200 * context.getResources().getDisplayMetrics().density);
            owner.get().setContentView(panel, new ViewGroup.LayoutParams(viewport, viewport));
            layout(panel, viewport, viewport);
            assertTrue("Accessibility metadata requires a view attached to a real window", panel.isAttachedToWindow());
            assertFalse("Keyboard navigation requires non-touch input mode", panel.isInTouchMode());
            ViewGroup content = (ViewGroup) panel.getChildAt(0);
            assertTrue("Large text must scroll instead of shrinking to the viewport", content.getHeight() > panel.getHeight());
            List<Button> buttons = new ArrayList<>();
            for (int i = 0; i < content.getChildCount(); i++) {
                View child = content.getChildAt(i);
                if (!(child instanceof Button)) continue;
                Button button = (Button) child; buttons.add(button);
                assertTrue("Every action needs a spoken label", button.getText().toString().trim().length() > 0);
                assertTrue("Every settings action retains a 48dp target", button.getHeight() >= Math.round(48 * context.getResources().getDisplayMetrics().density));
                assertTrue(button.isFocusable());
                AccessibilityNodeInfo node = button.createAccessibilityNodeInfo();
                assertEquals("Assistive technology receives the visible label and value", button.getText().toString(), node.getText().toString());
                assertTrue(node.isClickable());
                assertTrue(node.isFocusable());
                assertTrue("Wrapped labels must not clip vertically", button.getLayout().getHeight() <= button.getHeight() - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom());
            }
            Button effect = find(buttons, context.getString(R.string.ui_effect, context.getString(R.string.ui_clean)));
            effect.performClick();
            assertEquals(UiPreferences.Effect.SOFT_LCD, selected[0].effect);
            assertEquals(context.getString(R.string.ui_effect, context.getString(R.string.ui_soft_lcd)), effect.createAccessibilityNodeInfo().getText().toString());
            Button finish = find(buttons, context.getString(R.string.ui_done));
            assertTrue("Done follows the scrollable choices", finish.getTop() >= panel.getHeight());
            assertTrue(finish.requestFocus());
            Rect target = new Rect(0, 0, finish.getWidth(), finish.getHeight());
            finish.requestRectangleOnScreen(target, true);
            assertTrue("Focus/scroll can expose the full Done target", finish.getTop() >= panel.getScrollY() && finish.getBottom() <= panel.getScrollY() + panel.getHeight());
            assertTrue(finish.performClick());
            assertEquals(1, done[0]);
            panel.focusHeading();
            assertEquals(0, panel.getScrollY());
            TextView heading = (TextView) content.getChildAt(0);
            assertTrue("Returning to the heading restores keyboard focus", heading.hasFocus());
            assertEquals(context.getString(R.string.ui_settings), heading.createAccessibilityNodeInfo().getText().toString());
            if (Build.VERSION.SDK_INT >= 28) assertTrue(heading.isAccessibilityHeading());
        } finally { owner.pause().stop().destroy(); }
    }

    private static Button find(List<Button> buttons, String text) {
        for (Button button : buttons) if (text.contentEquals(button.getText())) return button;
        throw new AssertionError("Missing labeled action: " + text);
    }
    private static Bitmap uploaded(PixelView view) throws Exception {
        Field field = PixelView.class.getDeclaredField("bitmap"); field.setAccessible(true);
        return (Bitmap) field.get(view);
    }
    private static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void assign(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Bitmap render(View view) {
        Bitmap output = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(output)); return output;
    }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
