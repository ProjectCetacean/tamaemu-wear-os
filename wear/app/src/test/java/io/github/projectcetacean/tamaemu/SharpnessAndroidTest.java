package io.github.projectcetacean.tamaemu;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Synthetic frames only. Native software Canvas, not physical hardware or AGSL proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,36}, application=Application.class, qualifiers="w240dp-h240dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SharpnessAndroidTest {
    private static final String[] LEVELS={"off","slight","medium","strong"};
    private static final int[] SIZES={128,217,256,328};

    // Before implementation this records real source84 Canvas pixels, not a renderer oracle.
    @Test public void offMatchesCapturedSource84Canvas() throws Exception {
        for(UiPreferences.Effect effect:UiPreferences.Effect.values()) for(int size:SIZES){
            Bitmap image=draw(view(fixture(),size,effect,"off"));
            String hash=hash(image);
            System.out.println("BASELINE api="+Build.VERSION.SDK_INT+" "+effect+" "+size+" "+hash);
            if("true".equals(System.getenv("TAMAEMU_CAPTURE_BASELINE"))) export("baseline-"+effect+"-"+size,image);
            assertEquals("Off preserves source84 Canvas",baselineHash(effect,size),hash);
        }
    }

    // Catches missing default migration, unknown recovery, or shared preference writes.
    @Test public void storeDefaultsAndRoundTripsEveryChoice(){
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ui_presentation",0).edit().clear().putString("size","LARGE")
                .putString("effect","LCD_GLASS").putString("sound","false").commit();
        context.getSharedPreferences("life-test",0).edit().putLong("saved",12345).commit();
        UiPreferencesStore store=new UiPreferencesStore(context);
        assertEquals("slight",store.load().encode().get("sharpness"));
        for(String level:LEVELS){
            store.save(store.load().with("sharpness",level));
            assertEquals(level,new UiPreferencesStore(context).load().encode().get("sharpness"));
            assertEquals(UiPreferences.Size.LARGE,store.load().size);assertFalse(store.load().sound);
        }
        context.getSharedPreferences("ui_presentation",0).edit().putString("sharpness","bad").commit();
        assertEquals("slight",store.load().encode().get("sharpness"));
        assertEquals(12345,context.getSharedPreferences("life-test",0).getLong("saved",0));
    }

    // Catches missing choice, wrong order, size mutation, reset, or inaccessible target.
    @Test public void settingCyclesPersistsAndResetsWithNativeAccessibleTarget(){
        ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try{
            Activity activity=owner.get();UiPreferencesStore store=new UiPreferencesStore(activity);
            store.save(UiPreferences.defaults().withSize(UiPreferences.Size.LARGE).with("sound","false"));
            final UiPreferences[] selected={store.load()}; final boolean[] sizeChanged={false};
            SettingsPanel panel=new SettingsPanel(activity,selected[0],new SettingsPanel.Listener(){
                public void changed(UiPreferences p,boolean size){selected[0]=p;sizeChanged[0]=size;store.save(p);}
                public void done(){}
            },null);
            activity.setContentView(panel);layout(panel,240);
            Button choice=button(panel,"LCD sharpness: Slight");assertNotNull("LCD sharpness choice exists",choice);
            assertTrue(choice.getHeight()>=48*activity.getResources().getDisplayMetrics().density);
            for(String label:new String[]{"Medium","Strong","Off","Slight"}){
                assertTrue(choice.performClick());
                assertEquals("LCD sharpness: "+label,choice.createAccessibilityNodeInfo().getText().toString());
                assertTrue(choice.isFocusable());assertTrue(choice.isClickable());assertFalse(sizeChanged[0]);
                assertEquals(label.toLowerCase(java.util.Locale.ROOT),new UiPreferencesStore(activity).load().encode().get("sharpness"));
                assertEquals(UiPreferences.Size.LARGE,selected[0].size);assertFalse(selected[0].sound);
            }
            choice.performClick();button(panel,"Reset appearance").performClick();
            assertNotNull(button(panel,"LCD sharpness: Slight"));assertFalse(store.load().sound);
        }finally{owner.destroy();}
    }

    // Catches wrong gain, rounding, channel mixing, endpoint clipping or non-monotone mapping.
    @Test public void displayRampIsMonotoneWithExactContractSamplesAndAlphaPreserved() throws Exception {
        int[][] expected={{0,64,127,128,192,255},{0,60,127,128,196,255},
                {0,56,127,128,200,255},{0,51,127,128,205,255}};
        int[] sample={0,64,127,128,192,255};
        int[] ramp=new int[128*128];
        for(int i=0;i<ramp.length;i++){int v=i%256;ramp[i]=Color.rgb(v,v,v);}
        for(int l=0;l<LEVELS.length;l++){
            Bitmap image=draw(view(ramp,128,UiPreferences.Effect.CLEAN,LEVELS[l]));
            int previous=-1;
            for(int i=0;i<256;i++){
                int pixel=image.getPixel(i%128,i/128),v=Color.red(pixel);
                assertTrue("monotone "+LEVELS[l],v>=previous);previous=v;
                assertEquals(v,Color.green(pixel));assertEquals(v,Color.blue(pixel));
            }
            for(int i=0;i<sample.length;i++)assertEquals("gain "+LEVELS[l]+" at "+sample[i],expected[l][i],Color.red(image.getPixel(sample[i]%128,sample[i]/128)));
            int[] mixed={0xff40c080,0x0040c080,0x4040c080,0x8040c080};
            PixelView v=view(mixed,2,UiPreferences.Effect.CLEAN,LEVELS[l]);
            Bitmap upload=uploaded(v);
            assertEquals(expected[l][1],Color.red(upload.getPixel(0,0)));
            assertEquals(expected[l][4],Color.green(upload.getPixel(0,0)));
            assertEquals(128,Color.blue(upload.getPixel(0,0)));
            for(int i=0;i<4;i++)assertEquals(Color.alpha(mixed[i]),Color.alpha(upload.getPixel(i%2,i/2)));
        }
    }

    // Catches raw mutation, stale upload on a still frame, or reallocation on every frame.
    @Test public void staticFrameReprocessesDisplayOnlyAndReusesCache() throws Exception {
        int[] source=fixture(),original=source.clone();
        for(UiPreferences.Effect effect:UiPreferences.Effect.values()){
            PixelView v=view(source,328,effect,"off");Bitmap bitmap=uploaded(v);String off=hash(draw(v));
            for(String level:LEVELS){
                v.appearance(prefs(effect,level));String changed=hash(draw(v));
                if(!level.equals("off"))assertNotEquals("static appearance updates upload",off,changed);
                assertSame(bitmap,uploaded(v));assertArrayEquals(original,v.copyRawFrame());
                int generation=bitmap.getGenerationId();v.frame(128,128,source.clone());
                assertEquals("identical frame still skips upload",generation,bitmap.getGenerationId());
                int[] copy=v.copyRawFrame();copy[10]=0;assertArrayEquals(original,v.copyRawFrame());
            }
            v.appearance(prefs(effect,"off"));assertEquals(off,hash(draw(v)));
            source[100]^=1;v.frame(128,128,source);assertSame(bitmap,uploaded(v));
            assertArrayEquals(source,v.copyRawFrame());source=original.clone();
        }
    }

    // Uniform midgrey survives every LUT unchanged: only the gaps can change it.
    @Test public void gapsStartAtTwoXUseMaxAndLeaveCentresUntouched(){
        int[] uniform=new int[128*128];Arrays.fill(uniform,0xff808080);
        for(int size:SIZES) for(UiPreferences.Effect effect:UiPreferences.Effect.values()){
            Bitmap[] images=new Bitmap[4];
            for(int l=0;l<4;l++)images[l]=draw(view(uniform,size,effect,LEVELS[l]));
            for(int l=1;l<4;l++)for(int y=4;y<124;y+=7)for(int x=4;x<124;x+=7){
                int cx=centre(x,size),cy=centre(y,size);
                assertEquals("gap leaves centre "+size+effect+LEVELS[l],images[0].getPixel(cx,cy),images[l].getPixel(cx,cy));
            }
            if(size<256){for(int l=1;l<4;l++)assertTrue("no sub2x gap "+effect,images[0].sameAs(images[l]));}
            else{
                int x=(int)(40*size/128f),y=centre(50,size);
                int[] cleanValues={128,120,112,104};
                for(int l=0;l<4;l++){
                    if(effect==UiPreferences.Effect.CLEAN)assertEquals("gap alpha "+LEVELS[l],cleanValues[l],Color.red(images[l].getPixel(x,y)));
                    else if(l==1)assertTrue("22 floor uses max, not sum",images[0].sameAs(images[l]));
                    if(l>=2)assertTrue("stronger gap",Color.red(images[l].getPixel(x,y))<Color.red(images[l-1].getPixel(x,y)));
                }
            }
        }
    }

    // Catches gain omissions alongside any style, nearest-neighbour loss or changed crop at Large Fit.
    @Test public void checkerAndTextEdgeContrastIncreasesForEveryStyleAndFullFrameSurvives(){
        int[] source=fixture();
        for(UiPreferences.Effect effect:UiPreferences.Effect.values())for(int size:SIZES){
            int previousChecker=-1,previousText=-1;
            for(String level:LEVELS){
                PixelView v=view(source,size,effect,level);Bitmap image=draw(v);
                int checker=Color.red(image.getPixel(centre(20,size),centre(20,size)))-Color.red(image.getPixel(centre(21,size),centre(20,size)));
                int text=Color.red(image.getPixel(centre(18,size),centre(51,size)))-Color.red(image.getPixel(centre(16,size),centre(51,size)));
                assertTrue("checker contrast "+effect+size+level,checker>previousChecker);
                assertTrue("text contrast "+effect+size+level,text>previousText);
                previousChecker=checker;previousText=text;assertArrayEquals(source,v.copyRawFrame());
                for(int y=0;y<128;y++)for(int x=0;x<128;x++)assertEquals("full opaque LCD",255,Color.alpha(image.getPixel(centre(x,size),centre(y,size))));
                if(effect==UiPreferences.Effect.CLEAN){
                    // Every source cell's centre must match the unscaled contrast-treated cell.
                    Bitmap one=draw(view(source,128,effect,level));
                    for(int y=0;y<128;y++)for(int x=0;x<128;x++)assertEquals("every source cell survives Fit "+size,one.getPixel(x,y),image.getPixel(centre(x,size),centre(y,size)));
                }
            }
        }
    }

    @Test public void exportLabelledCanvasComparison() throws Exception {
        if(System.getenv("TAMAEMU_SHARPNESS_ARTIFACTS")==null)return;
        Bitmap sheet=Bitmap.createBitmap(4*344,2*384,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sheet);
        canvas.drawColor(0xff161b20);Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);label.setColor(Color.WHITE);label.setTextSize(18);
        UiPreferences.Effect[] effects={UiPreferences.Effect.CLEAN,UiPreferences.Effect.LCD_GLASS};
        String[] names={"Off","Slight","Medium","Strong"};
        for(int row=0;row<2;row++)for(int col=0;col<4;col++){
            Bitmap image=draw(view(fixture(),328,effects[row],LEVELS[col]));
            canvas.drawText((row==0?"Clean":"LCD Glass")+" / "+names[col],col*344+8,row*384+26,label);
            canvas.drawBitmap(image,col*344+8,row*384+40,null);
        }
        export("comparison-328",sheet);
    }

    private static String baselineHash(UiPreferences.Effect effect,int size){
        String[][] hashes=Build.VERSION.SDK_INT==26?new String[][]{
            {"995aee72f3bf28a94134e02c9d7fe9346a6893d3951255c496f73fa34bc08ce4","ed404db351cd97084199e2e908ca9508cabdee16ae208f278b76cb54d86dd502","45bab0335121c964e3582bf689d112b95397cb4b4467610193e6e15670034ff8","b2d29e6aef5a9220c742fd2b141d6ad2fcca64352b8a76801582f2fc2da8e79a"},
            {"0d21672924cd17139c111a342c7351d7b010be08c57d3593274306fdea8836aa","bfeafb2d8f4bb3cc52abee47bd8553b2ecd6192fffa92bcf207d5d8c17b35982","e2e0adb9ae53abf55802871b075a51b62b374abccf2996a2ab131ff12586741c","db889fde1b90da387b8def5964cf4be45e1d670ad905fac2e47af74d5328299a"},
            {"d664aed80bdddb91f8a6eb59a5ccd2b7d95ad4ef3889583aaa35e2d1faf3c567","46ba4012d81a2a6db8119bb595dd29b264dfd001ff1fc04673314844e26beb47","78e1dbe0791b4a1ded6203cf27c5a4f7f245d8a958de858ca7b0bda09d4bf2ff","3714a6e7cac631cf6f14dd3b2edab5b0b845c900ed78eb1940dfde1ae6e0b436"},
        }:new String[][]{
            {"995aee72f3bf28a94134e02c9d7fe9346a6893d3951255c496f73fa34bc08ce4","ed404db351cd97084199e2e908ca9508cabdee16ae208f278b76cb54d86dd502","45bab0335121c964e3582bf689d112b95397cb4b4467610193e6e15670034ff8","b2d29e6aef5a9220c742fd2b141d6ad2fcca64352b8a76801582f2fc2da8e79a"},
            {"5dc1ab9991d7f7049fbb5ca1df8c4b9bf948461163ca10b9e0e5f860b8824f49","8e77209b99ecc2de291bb15288a2aff6180e28bc9a03d76257eefde13c237b61","7a1e202cbb8beea71b84867a740e63889fe9a1c7d80ad010536e32cae6d2e30c","c596b6b30d0e415fd246b70ccae9a6fa1f1e312a656acddd72e78f55e48ee01a"},
            {"ebffe486b658f47d8ced9d6303e8b1262e9dc2f094afdb58539d2961cd257ee1","1397cef0a2a15687e7e5b844aa0af69e103a689e413d88816fa7eeb342d9c159","032ab2820059c3ad9744873bf637b4348155bf53d8e10cd6f68028728bb8077b","7cb88a36cffae598ff1e558eea9b16990ff0a33821e2c53a4c51ffc6ddbdf808"},
        };
        int index=0;while(SIZES[index]!=size)index++;
        if(Build.VERSION.SDK_INT==26&&effect==UiPreferences.Effect.LCD_GLASS){
            // Baseline measured on Linux x86_64 CI for the unchanged renderer.
            String os=System.getProperty("os.name"),arch=System.getProperty("os.arch");
            if("Linux".equals(os)&&"amd64".equals(arch))
                return new String[]{"aab6782e53728c048e7dc5a743e795aa47c7792a88ba33962f82b4776c68798a","729ec606404b18f2277b81d1e3ca0f76eec3a8a10b8913126a3e3a7d9695926c","1c16eaf8bd2908e418e414418d5df324d99a9481ab393004663fb5af95bd0601","719704c7b7903fb0303551e00dc1f74e9201115c7742793ccaefd347b4619352"}[index];
            assertTrue("Capture unchanged source84 on this native renderer before adding a golden: "+os+"/"+arch,
                    "Mac OS X".equals(os)&&"aarch64".equals(arch));
        }
        return hashes[effect.ordinal()][index];
    }

    @Test public void largeFitIntegerOffRetainsAll128CellsAt328(){
        UiPreferences p=UiPreferences.defaults().withSize(UiPreferences.Size.LARGE).with("controlSpace","OVERLAY").with("integerPixels","false");
        WatchGeometry base=WatchGeometry.calculate(480,480,2,true,p,true);
        assertEquals(328,base.display.width());assertEquals(0,base.integerFactor);
        for(String level:LEVELS){
            WatchGeometry geometry=WatchGeometry.calculate(480,480,2,true,p.with("sharpness",level),true);
            assertEquals(base.display.left,geometry.display.left);assertEquals(base.display.top,geometry.display.top);
            assertEquals(328,geometry.display.width());assertEquals(0,geometry.integerFactor);
        }
    }

    private static UiPreferences prefs(UiPreferences.Effect e,String level){return UiPreferences.defaults().with("effect",e.name()).with("sharpness",level);}
    private static PixelView view(int[] raw,int size,UiPreferences.Effect effect,String level){
        PixelView v=new PixelView(RuntimeEnvironment.getApplication());v.appearance(prefs(effect,level));
        int side=raw.length==4?2:128;v.frame(side,side,raw);layout(v,size);return v;
    }
    private static void layout(View v,int size){v.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));v.layout(0,0,size,size);}
    private static Bitmap draw(PixelView v){Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));return b;}
    private static Bitmap uploaded(PixelView v)throws Exception{Field f=PixelView.class.getDeclaredField("bitmap");f.setAccessible(true);return (Bitmap)f.get(v);}
    private static Button button(View v,String label){if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),label);if(b!=null)return b;}return null;}
    private static int centre(int cell,int size){return (int)((cell+.5)*size/128);}
    private static int[] fixture(){
        int[] p=new int[128*128];Arrays.fill(p,0xffb0b0b0);
        for(int y=8;y<40;y++)for(int x=8;x<120;x++)p[y*128+x]=((x+y)&1)==0?0xffb0b0b0:0xff505050;
        // 5x7 LCD text, repeated at 1x: "LCD".
        String[] rows={"100000111011110","100001000110001","100001000110001","100001000110001","100001000110001","100001000110001","111110111011110"};
        for(int y=0;y<7;y++)for(int x=0;x<15;x++)if(rows[y].charAt(x)=='1')p[(50+y)*128+16+x]=0xff505050;
        for(int y=80;y<112;y++)for(int x=8;x<120;x++)p[y*128+x]=Color.rgb(x*2,y*2,96);
        p[0]=Color.RED;p[127]=Color.GREEN;p[127*128]=Color.BLUE;p[p.length-1]=Color.YELLOW;return p;
    }
    private static String hash(Bitmap image)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");int[] row=new int[image.getWidth()];
        for(int y=0;y<image.getHeight();y++){image.getPixels(row,0,row.length,0,y,row.length,1);for(int p:row){digest.update((byte)(p>>>24));digest.update((byte)(p>>>16));digest.update((byte)(p>>>8));digest.update((byte)p);}}
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static void export(String name,Bitmap image)throws Exception{
        String path=System.getenv("TAMAEMU_SHARPNESS_ARTIFACTS");if(path==null)return;File dir=new File(path);assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream stream=new FileOutputStream(new File(dir,name+"-api"+Build.VERSION.SDK_INT+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}
    }
}
