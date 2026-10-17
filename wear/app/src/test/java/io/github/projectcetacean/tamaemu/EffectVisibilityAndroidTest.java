package io.github.projectcetacean.tamaemu;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Real software Canvas pixels, synthetic LCD only. Floors deliberately exclude unlit pixels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EffectVisibilityAndroidTest {
    private static final int[] SIZES = {128, 217, 256, 328};
    private static final UiPreferences.Effect[] EFFECTS = {
            UiPreferences.Effect.SOFT_LCD, UiPreferences.Effect.LCD_GLASS};

    // Catches filtering/resampling and any accidental treatment of Clean.
    @Test public void cleanAtIntegerScaleEqualsNearestNeighbour() throws Exception {
        int[] raw = fixture();
        for (int size : new int[]{128, 256}) {
            Bitmap clean = render(view(size, size), UiPreferences.Effect.CLEAN);
            int k = size / 128;
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
                assertEquals("Clean " + size + " at " + x + "," + y, raw[(y/k)*128+x/k], clean.getPixel(x,y));
            export("clean-nearest-" + size, clean, clean);
        }
    }

    // Catches faint alpha and the half-clipped, too-narrow stroked edge.
    @Test public void edgeBandVisibleAtEverySize() throws Exception {
        boolean visible=true;
        for (int size : SIZES) {
            PixelView view = view(size, size);
            Bitmap clean = render(view, UiPreferences.Effect.CLEAN);
            int band = size < 256 ? 1 : (int)Math.ceil(size / 128.0);
            for (UiPreferences.Effect effect : EFFECTS) {
                Bitmap image = render(view, effect);
                export("edge-" + size + "-" + effect, clean, image);
                double total = 0; int count = 0;
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                    if (x >= band && x < size-band && y >= band && y < size-band) continue;
                    int source = clean.getPixel(x,y);
                    if (Math.min(Color.red(source), Math.min(Color.green(source), Color.blue(source))) < 160) continue;
                    total += difference(source, image.getPixel(x,y)); count++;
                }
                double mean = total / count;
                System.out.println("edge " + size + " " + effect + " mean=" + mean);
                visible &= mean >= 16;
            }
        }
        assertTrue("Every lit edge band must differ by at least 16; see measurements",visible);
    }

    // Catches faint/missing grid, using actual lines and centres on a uniform lit patch.
    @Test public void cellGridVisibleAtTwoXOrMore() throws Exception {
        boolean visible=true;
        for (int size : new int[]{256, 328}) for (UiPreferences.Effect effect : EFFECTS) {
            Bitmap clean = render(view(size,size), UiPreferences.Effect.CLEAN);
            Bitmap image = render(view(size,size), effect);
            export("grid-" + size + "-" + effect, clean, image);
            double total = 0, minimum = 255; int count = 0;
            for (int cellY = 24; cellY < 70; cellY++) for (int cellX = 16; cellX < 55; cellX++) {
                int centreX = centre(cellX,size), centreY = centre(cellY,size);
                int lineX = (int)(cellX * size / 128f);
                double contrast=difference(image.getPixel(lineX, centreY), image.getPixel(centreX, centreY));
                total += contrast; minimum=Math.min(minimum,contrast); count++;
            }
            double mean = total/count;
            System.out.println("grid " + size + " " + effect + " mean=" + mean + " minimum=" + minimum);
            visible &= minimum >= 16;
        }
        assertTrue("Every lit grid must differ by at least 16; see measurements",visible);
    }

    // Catches a sub-2x grid or body treatment on Soft.
    @Test public void noGridBelowTwoX() throws Exception {
        for (int size : new int[]{128,217}) {
            PixelView view = view(size,size);
            Bitmap clean = render(view,UiPreferences.Effect.CLEAN), soft = render(view,UiPreferences.Effect.SOFT_LCD);
            export("no-grid-" + size,clean,soft);
            for(int y=1;y<size-1;y++) for(int x=1;x<size-1;x++)
                assertEquals("Soft interior " + size + " at " + x + "," + y,clean.getPixel(x,y),soft.getPixel(x,y));
        }
    }

    // Catches excessive glass, or grid/shadow spilling into interior source-cell centres.
    @Test public void cellCentresStayLegible() throws Exception {
        for(int size:SIZES) for(UiPreferences.Effect effect:EFFECTS) {
            PixelView view=view(size,size);
            Bitmap clean=render(view,UiPreferences.Effect.CLEAN), image=render(view,effect);
            export("centres-"+size+"-"+effect,clean,image);
            for(int y=1;y<127;y++) for(int x=1;x<127;x++) {
                int px=centre(x,size),py=centre(y,size);
                assertChannelCap("centre "+size+" "+effect+" at "+x+","+y,clean.getPixel(px,py),image.getPixel(px,py),24);
            }
        }
    }

    // Catches faint glass and a whole-view gradient on a letterboxed LCD.
    @Test public void glassBodyGradientAtEverySize() throws Exception {
        for(int size:SIZES) assertGradient(size,size);
        assertGradient(328,400);
        Bitmap square=render(view(328,328),UiPreferences.Effect.LCD_GLASS);
        Bitmap letterbox=render(view(328,400),UiPreferences.Effect.LCD_GLASS);
        for(int y=0;y<328;y++) for(int x=0;x<328;x++)
            assertEquals("Glass must translate with the destination",square.getPixel(x,y),letterbox.getPixel(x,y+36));
    }
    private void assertGradient(int width,int height) throws Exception {
        PixelView view=view(width,height); Rect d=destination(width,height);
        Bitmap clean=render(view,UiPreferences.Effect.CLEAN),glass=render(view,UiPreferences.Effect.LCD_GLASS);
        export("gradient-"+width+"x"+height,clean,glass);
        double tl=0,br=0;
        for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
            tl+=signedDifference(clean.getPixel(d.left+x,d.top+y),glass.getPixel(d.left+x,d.top+y));
            br+=signedDifference(clean.getPixel(d.right-1-x,d.bottom-1-y),glass.getPixel(d.right-1-x,d.bottom-1-y));
        }
        double gap=Math.abs(tl-br)/64;
        System.out.println("glass "+width+"x"+height+" corner delta gap="+gap);
        assertTrue("glass destination gradient "+width+"x"+height+" gap="+gap,gap>=6);
    }

    // Catches crop, masking, loss of a source cell, shell spill, and raw-frame writes.
    @Test public void fullLcdPreservedEveryEffect() throws Exception {
        int[] raw=fixture();
        for(int size:SIZES) for(int height:new int[]{size,size+72}) {
            PixelView view=view(size,height); Rect d=destination(size,height);
            Bitmap clean=render(view,UiPreferences.Effect.CLEAN);
            for(UiPreferences.Effect effect:EFFECTS) {
                Bitmap image=render(view,effect);export("full-"+size+"x"+height+"-"+effect,clean,image);

                for(int y=0;y<height;y++) for(int x=0;x<size;x++) {
                    if(!d.contains(x,y)) assertEquals("shell spill",clean.getPixel(x,y),image.getPixel(x,y));
                    else {
                        assertEquals("LCD must never be masked",255,Color.alpha(image.getPixel(x,y)));

                    }
                }
                // Exact tie-breaking at fractional boundaries is a rasterizer detail.
                // A sample strictly within each source cell proves all 16384 cells survive.
                for(int sy=0;sy<128;sy++) for(int sx=0;sx<128;sx++)
                    assertEquals("Every source cell survives Fit",raw[sy*128+sx],clean.getPixel(d.left+centre(sx,size),d.top+centre(sy,size)));
                assertArrayEquals("Treatments never rewrite raw LCD",raw,view.copyRawFrame());
            }
        }
    }

    // Catches equal-frame upload optimization suppressing appearance invalidation.
    @Test public void staticFrameToggleEachSize() throws Exception {
        for(int size:SIZES) {
            PixelView view=view(size,size);Bitmap clean=render(view,UiPreferences.Effect.CLEAN);
            for(UiPreferences.Effect effect:EFFECTS) {
                Shadows.shadowOf(view).clearWasInvalidated();
                view.appearance(UiPreferences.defaults().with("sharpness","off").with("effect",effect.name()));
                assertTrue("Appearance must invalidate a static frame",Shadows.shadowOf(view).wasInvalidated());
                Bitmap image=draw(view);export("toggle-"+size+"-"+effect,clean,image);
                assertFalse("Effect must be visible",clean.sameAs(image));
            }
            assertTrue("Clean is byte-exact after toggling",clean.sameAs(render(view,UiPreferences.Effect.CLEAN)));
        }
    }

    @Test @Config(sdk=26) public void fallbackOnApi26Software() throws Exception { assertSoftwareFallback("api26"); }
    @Test @Config(sdk=33) public void fallbackOnApi33Software() throws Exception { assertSoftwareFallback("api33"); }
    private void assertSoftwareFallback(String name) throws Exception {
        PixelView view=view(328,400);
        Bitmap clean=render(view,UiPreferences.Effect.CLEAN),soft=render(view,UiPreferences.Effect.SOFT_LCD),glass=render(view,UiPreferences.Effect.LCD_GLASS);
        assertFalse(new Canvas(glass).isHardwareAccelerated());
        assertFalse("Canvas glass must add a body gradient",soft.sameAs(glass));
        assertNotEquals(soft.getPixel(30,70),glass.getPixel(30,70));
        export("fallback-"+name,clean,glass);
    }

    // The only substituted boundary is hardware shader creation/execution; fallback is real Canvas.
    @Test public void shaderCreateFailureFallsBack() throws Exception { assertShaderFailure("create"); }
    @Test public void shaderBoundsFailureFallsBack() throws Exception { assertShaderFailure("bounds"); }
    @Test public void shaderDrawFailureFallsBackAndStaysDisabled() throws Exception { assertShaderFailure("draw"); }
    private void assertShaderFailure(String stage) throws Exception {
        FailingGlass factory=new FailingGlass(stage);
        PixelView view=new PixelView(RuntimeEnvironment.getApplication(),factory);
        view.frame(128,128,fixture());view.layout(0,0,328,400);
        Bitmap expected=render(view(328,400),UiPreferences.Effect.LCD_GLASS);
        Bitmap first=render(view,UiPreferences.Effect.LCD_GLASS);
        Bitmap second=draw(view);
        export("shader-"+stage,render(view(328,400),UiPreferences.Effect.CLEAN),second);
        assertTrue("Failed shader draws Canvas glass on the same frame",expected.sameAs(first));
        assertTrue("Failed shader remains on Canvas glass",expected.sameAs(second));
        assertEquals("Never retry failed shader creation",1,factory.creates);
        assertEquals("Never retry failed shader bounds",stage.equals("create")?0:1,factory.bounds);
        assertEquals("Never retry failed shader draw",stage.equals("draw")?1:0,factory.draws);
    }
    @Test public void shaderReceivesDestinationRect() throws Exception {
        FailingGlass factory=new FailingGlass("none");
        PixelView view=new PixelView(RuntimeEnvironment.getApplication(),factory);
        view.frame(128,128,fixture());view.layout(0,0,328,328);
        render(view,UiPreferences.Effect.LCD_GLASS);
        assertEquals(new Rect(0,0,328,328),factory.received);
        view.layout(0,0,328,400);
        Bitmap glass=render(view,UiPreferences.Effect.LCD_GLASS);
        assertEquals("Bounds must follow a changed LCD origin",new Rect(0,36,328,364),factory.received);
        assertEquals(factory.received,factory.drawn);
        assertEquals(1,factory.creates);
        assertEquals(2,factory.bounds);
        assertEquals(2,factory.draws);
        export("shader-destination",render(view(328,400),UiPreferences.Effect.CLEAN),glass);
    }
    private static final class FailingGlass implements PixelView.GlassFactory,PixelView.Glass {
        final String stage;int creates,bounds,draws;Rect received,drawn;
        FailingGlass(String stage) {this.stage=stage;}
        public boolean eligible(Canvas canvas) {return true;}
        public PixelView.Glass create() {creates++;fail("create");return this;}
        public void bounds(Rect destination) {bounds++;received=new Rect(destination);fail("bounds");}
        public void draw(Canvas canvas,Rect destination) {draws++;drawn=new Rect(destination);fail("draw");}
        private void fail(String point) {
            if(!stage.equals(point)) return;
            if(point.equals("bounds")) throw new LinkageError("synthetic shader bounds");
            throw new IllegalStateException("synthetic shader "+point);
        }
    }

    private static PixelView view(int width,int height) {
        PixelView view=new PixelView(RuntimeEnvironment.getApplication());
        view.frame(128,128,fixture());view.layout(0,0,width,height);return view;
    }
    private static Rect destination(int width,int height) {
        int side=Math.min(width,height);return new Rect((width-side)/2,(height-side)/2,(width+side)/2,(height+side)/2);
    }
    private static int centre(int cell,int size) {return (int)((cell+.5)*size/128);}
    private static Bitmap render(PixelView view,UiPreferences.Effect effect) {
        view.appearance(UiPreferences.defaults().with("sharpness","off").with("effect",effect.name()));return draw(view);
    }
    private static Bitmap draw(PixelView view) {
        Bitmap out=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(out));return out;
    }
    private static int[] fixture() {
        int[] pixels=new int[128*128];Arrays.fill(pixels,0xffc0d8c0);
        for(int y=40;y<88;y++) for(int x=72;x<112;x++) pixels[y*128+x]=0xff203020;
        for(int y=88;y<112;y++) for(int x=16;x<64;x++) pixels[y*128+x]=(x+y)%2==0?0xffe0e0d0:0xff304030;
        pixels[0]=Color.RED;pixels[127]=Color.GREEN;pixels[127*128]=Color.BLUE;pixels[128*128-1]=Color.YELLOW;
        return pixels;
    }
    private static double difference(int a,int b) {
        return (Math.abs(Color.red(b)-Color.red(a))+Math.abs(Color.green(b)-Color.green(a))+Math.abs(Color.blue(b)-Color.blue(a)))/3.0;
    }
    private static double signedDifference(int a,int b) {
        return (Color.red(b)-Color.red(a)+Color.green(b)-Color.green(a)+Color.blue(b)-Color.blue(a))/3.0;
    }
    private static void assertChannelCap(String label,int a,int b,int cap) {
        assertTrue(label+" red",Math.abs(Color.red(a)-Color.red(b))<=cap);
        assertTrue(label+" green",Math.abs(Color.green(a)-Color.green(b))<=cap);
        assertTrue(label+" blue",Math.abs(Color.blue(a)-Color.blue(b))<=cap);
    }
    private static void export(String name,Bitmap clean,Bitmap effect) throws IOException {
        File dir=new File("build/effect-review");
        // The root's fresh-Java helper runs from an isolated temp directory. AGP runs from app/.
        try(InputStream stream=EffectVisibilityAndroidTest.class.getClassLoader().getResourceAsStream("com/android/tools/test_config.properties")) {
            if(stream!=null) {
                Properties config=new Properties();config.load(stream);
                String resources=config.getProperty("android_merged_resources","");
                if(resources.endsWith("/src/main/res"))
                    dir=new File(new File(resources).getParentFile().getParentFile().getParentFile(),"build/effect-review");
            }
        }
        assertTrue(dir.isDirectory()||dir.mkdirs());
        Bitmap pair=Bitmap.createBitmap(clean.getWidth()*2,clean.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(pair);canvas.drawBitmap(clean,0,0,null);canvas.drawBitmap(effect,clean.getWidth(),0,null);
        try(FileOutputStream stream=new FileOutputStream(new File(dir,name+".png"))) {assertTrue(pair.compress(Bitmap.CompressFormat.PNG,100,stream));}
    }
}
