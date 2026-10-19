package io.github.projectcetacean.tamaemu;

import android.app.Application;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.proto.RequestProto;
import androidx.wear.protolayout.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.nio.file.Files;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Field;
import com.google.common.util.concurrent.ListenableFuture;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class PetTileServiceAndroidTest {
    private PetTileService service;
    private File dir;
    private TimeZone savedZone;
    private Locale savedLocale;
    @Before public void setup() throws Exception {
        savedZone=TimeZone.getDefault();savedLocale=Locale.getDefault();
        TimeZone.setDefault(ZONE);Locale.setDefault(Locale.US);
        service=Robolectric.buildService(PetTileService.class).create().get();
        dir=service.getFilesDir();for(File f:dir.listFiles())if(f.getName().startsWith("tile-")||f.getName().startsWith("pet"))f.delete();
    }
    @After public void close(){service.onDestroy();TimeZone.setDefault(savedZone);Locale.setDefault(savedLocale);}
    private ThreadPoolExecutor blockedWorker(CountDownLatch release) throws Exception {
        Field field=PetTileService.class.getDeclaredField("reads");field.setAccessible(true);
        ThreadPoolExecutor worker=(ThreadPoolExecutor)field.get(service);CountDownLatch started=new CountDownLatch(1);
        worker.execute(()->{started.countDown();try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        assertTrue(started.await(1,TimeUnit.SECONDS));return worker;
    }
    private RequestBuilders.ResourcesRequest resourceRequest(String version) {
        return RequestBuilders.ResourcesRequest.fromProto(RequestProto.ResourcesRequest.newBuilder().setVersion(version).build());
    }
    @Test public void stalledCacheWorkerCompletesEveryCallbackBeforePlatformDeadlineAndDropsExpiredQueue() throws Exception {
        CountDownLatch release=new CountDownLatch(1);ThreadPoolExecutor worker=blockedWorker(release);
        try {
            ListenableFuture<TileBuilders.Tile> tile=service.onTileRequest(RequestBuilders.TileRequest.fromProto(RequestProto.TileRequest.getDefaultInstance()));
            ListenableFuture<ResourceBuilders.Resources> image=service.onTileResourcesRequest(resourceRequest("requested-N"));
            ListenableFuture<ResourceBuilders.Resources> neutral=service.onTileResourcesRequest(resourceRequest("neutral"));
            assertEquals("neutral",tile.get(7,TimeUnit.SECONDS).getResourcesVersion());
            assertEquals("neutral",neutral.get(1,TimeUnit.SECONDS).getVersion());
            ResourceBuilders.Resources missing=image.get(1,TimeUnit.SECONDS);
            assertEquals("requested-N",missing.getVersion());assertTrue(missing.getIdToImageMapping().isEmpty());
            assertTrue("expired callback work must be removed",worker.getQueue().isEmpty());
            release.countDown();worker.submit(()->{}).get(1,TimeUnit.SECONDS);
            assertSame("late worker cannot replace timeout result",missing,image.get());
        } finally {release.countDown();}
    }
    @Test public void teardownResolvesQueuedAndSubsequentCallbacksWithoutWaitingForDeadline() throws Exception {
        CountDownLatch release=new CountDownLatch(1);ThreadPoolExecutor worker=blockedWorker(release);
        try {
            ListenableFuture<TileBuilders.Tile> tile=service.onTileRequest(RequestBuilders.TileRequest.fromProto(RequestProto.TileRequest.getDefaultInstance()));
            ListenableFuture<ResourceBuilders.Resources> image=service.onTileResourcesRequest(resourceRequest("requested-N"));
            service.onDestroy();
            assertEquals("neutral",tile.get(1,TimeUnit.SECONDS).getResourcesVersion());
            assertEquals("requested-N",image.get(1,TimeUnit.SECONDS).getVersion());
            assertTrue(image.get().getIdToImageMapping().isEmpty());assertTrue(worker.getQueue().isEmpty());
            assertEquals("after-destroy",service.onTileResourcesRequest(resourceRequest("after-destroy")).get(1,TimeUnit.SECONDS).getVersion());
        } finally {release.countDown();}
    }
    @Test public void missingSnapshotProducesNeutralLayoutAndOpenActionWithoutNative() throws Exception {
        RequestBuilders.TileRequest request=RequestBuilders.TileRequest.fromProto(RequestProto.TileRequest.getDefaultInstance());
        TileBuilders.Tile tile=service.onTileRequest(request).get(3,TimeUnit.SECONDS);
        assertEquals("neutral",tile.getResourcesVersion());
        String proto=tile.toProto().toString();
        assertTrue(proto.contains(service.getString(R.string.tile_neutral)));
        assertTrue(proto.contains(service.getPackageName()));assertTrue(proto.contains(MAIN));
        assertFalse(proto.contains("Normal"));assertFalse(proto.contains("Protected"));
        assertEquals(0,tile.getFreshnessIntervalMillis());
    }
    @Test public void heroIsLargestElement() throws Exception {
        for(PetPreviewSnapshot s:new PetPreviewSnapshot[]{saved(TODAY_1432,PetPreviewSnapshot.Mode.NORMAL,false,0,0),
                saved(TODAY_1432,PetPreviewSnapshot.Mode.PROTECTED,true,90,1)}) {
            for(int side:SIDES) {
                TileBuilders.Tile tile=service.buildTile(s,"hero-v",side,side,NOW_1440);
                List<Node> nodes=nodes(tile);
                Node hero=only(nodes,LayoutElementBuilders.Image.class);
                LayoutElementBuilders.Image image=(LayoutElementBuilders.Image)hero.element;
                float w=dp(image.getWidth()),h=dp(image.getHeight());
                assertEquals("hero is square",w,h,0);
                assertEquals(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT,image.getContentScaleMode().getValue());
                ModifiersBuilders.Modifiers mods=image.getModifiers();
                assertTrue("no mask or border over LCD pixels",mods==null||(mods.getBackground()==null&&mods.getBorder()==null));
                assertTrue("hero "+w+" must be at least 0.6 of "+side,w>=.6f*side);
                for(Node n:nodes) {
                    if(n==hero||isAncestor(n,hero))continue;
                    float[] size=size(n.element);
                    if(size!=null)assertTrue("hero area must exceed every other element at "+side,size[0]*size[1]<w*h);
                }
                assertCornersInside(tile,side);
            }
        }
    }
    @Test public void lcdFrameLeavesEveryImageEdgeClearAndKeepsTheFullSquare() throws Exception {
        PetPreviewSnapshot snapshot=saved(TODAY_1432,PetPreviewSnapshot.Mode.NORMAL,false,0,0);
        for(int side:SIDES) {
            Node hero=only(nodes(service.buildTile(snapshot,"frame-v",side,side,NOW_1440)),LayoutElementBuilders.Image.class);
            assertTrue("bezel must sit outside the complete LCD image",hero.parent.element instanceof LayoutElementBuilders.Box);
            LayoutElementBuilders.Box frame=(LayoutElementBuilders.Box)hero.parent.element;
            LayoutElementBuilders.Image image=(LayoutElementBuilders.Image)hero.element;
            float imageSide=dp(image.getWidth()),frameSide=dp(frame.getWidth());
            assertEquals(frameSide,dp(frame.getHeight()),0);
            assertEquals(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER,frame.getHorizontalAlignment().getValue());
            assertEquals(LayoutElementBuilders.VERTICAL_ALIGN_CENTER,frame.getVerticalAlignment().getValue());
            assertTrue("frame must reserve visible space outside every LCD edge",frameSide-imageSide>=4);
            assertNotNull(frame.getModifiers().getBorder());
            float border=frame.getModifiers().getBorder().getWidth().getValue();
            assertTrue("bezel stroke cannot cover source pixels",border>0&&border<=(frameSide-imageSide)/2);
        }
    }
    @Test public void onlyRootIsClickable() throws Exception {
        for(PetPreviewSnapshot s:new PetPreviewSnapshot[]{null,saved(TODAY_1432,PetPreviewSnapshot.Mode.NORMAL,false,0,0)}) {
            for(int side:SIDES) {
                TileBuilders.Tile tile=service.buildTile(s,"click-v",side,side,NOW_1440);
                List<Node> nodes=nodes(tile);int clickables=0;
                for(Node n:nodes){ModifiersBuilders.Modifiers m=modifiers(n.element);if(m!=null&&m.getClickable()!=null)clickables++;}
                assertEquals("exactly one open action",1,clickables);
                ModifiersBuilders.Clickable open=modifiers(nodes.get(0).element).getClickable();
                assertNotNull("the open action belongs to the root",open);
                ActionBuilders.AndroidActivity activity=((ActionBuilders.LaunchAction)open.getOnClick()).getAndroidActivity();
                assertEquals(service.getPackageName(),activity.getPackageName());
                assertEquals(MAIN,activity.getClassName());
                assertNotNull("the one action is announced",modifiers(nodes.get(0).element).getSemantics());
            }
        }
    }
    @Test public void normalShowsNoStatus() throws Exception {
        for(long debt:new long[]{0,1,90,86400000,Long.MAX_VALUE}) {
            PetPreviewSnapshot s=saved(TODAY_1432,PetPreviewSnapshot.Mode.NORMAL,false,debt,0);
            for(int side:SIDES) for(String text:texts(service.buildTile(s,"normal-v",side,side,NOW_1440))) {
                assertFalse(text.contains("Normal"));assertFalse(text.contains("Protected"));
                assertFalse(text.contains("Catch-up"));assertFalse(text.contains("Time needs"));
            }
        }
    }
    @Test public void exceptionalStatusesShown() throws Exception {
        PetPreviewSnapshot[] cases={saved(TODAY_1432,PetPreviewSnapshot.Mode.PROTECTED,false,0,0),
            saved(TODAY_1432,PetPreviewSnapshot.Mode.PROTECTED,true,0,0),
            saved(TODAY_1432,PetPreviewSnapshot.Mode.NORMAL,false,0,1),saved(TODAY_1432,PetPreviewSnapshot.Mode.PROTECTED,true,90,1)};
        String[][] phrases={{"Protected"},{"Protected: paused away"},{"Time needs checking"},
            {"Protected: paused away","Time needs checking"}};
        for(int i=0;i<cases.length;i++) for(int side:SIDES) {
            List<Node> nodes=nodes(service.buildTile(cases[i],"status-v",side,side,NOW_1440));
            Node chip=textNode(nodes,phrases[i][0]);
            assertNotNull("status chip shown for "+phrases[i][0],chip);
            String chipText=((LayoutElementBuilders.Text)chip.element).getText().getValue();
            for(String phrase:phrases[i])assertTrue(chipText+" contains "+phrase,chipText.contains(phrase));
            assertFalse("saved debt is not a demanded action",chipText.contains("Catch-up"));
            assertFalse(chipText.contains("Normal"));
            LayoutElementBuilders.Text caption=(LayoutElementBuilders.Text)textNode(nodes,"Saved ").element;
            assertEquals("both saved time and date have a line",2,caption.getMaxLines().getValue());
            assertTrue("chip text contrast at least 4.5:1",contrast(chip)>=4.5);
            assertTrue("caption text contrast at least 4.5:1",contrast(textNode(nodes,"Saved "))>=4.5);
        }
    }
    // Removing either saved date/year or time, or branching on build day, makes these fail.
    @Test public void cachedCaptionIdentifiesSavedInstantAcrossMidnightAndYear() throws Exception {
        long savedAt=local(2026,12,31,23,59);
        PetPreviewSnapshot s=saved(savedAt,PetPreviewSnapshot.Mode.NORMAL,false,0,0);
        TileBuilders.Tile cached=service.buildTile(s,"cached-v",192,192,savedAt+30000);
        String expected="Saved 23:59\n12/31/2026";
        assertEquals("cached layout must carry its saved date, full year and time",expected,caption(cached));
        for(long builtAt:new long[]{local(2027,1,1,0,1),local(2028,1,1,0,1)}) {
            assertEquals(expected,caption(cached));
            assertEquals("rebuilding must not reinterpret the saved instant",expected,
                caption(service.buildTile(s,"cached-v",192,192,builtAt)));
        }
    }
    @Test public void captionUsesSavedDateAndTimeInDeviceZoneAndLocale() throws Exception {
        PetPreviewSnapshot s=saved(YESTERDAY_2359,PetPreviewSnapshot.Mode.NORMAL,false,0,0);
        assertEquals("Saved 23:59\n10/15/2026",caption(service.buildTile(s,"zone-v",192,192,NOW_0001)));
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertEquals("Saved 03:59\n10/16/2026",caption(service.buildTile(s,"zone-v",192,192,NOW_0001)));
        Locale.setDefault(Locale.FRANCE);
        assertEquals("Saved 03:59\n16/10/2026",caption(service.buildTile(s,"locale-v",192,192,NOW_0001)));
    }
    @Test public void invalidSavedTimestampUsesNeutralLayoutAndOpenAction() throws Exception {
        for(long utc:new long[]{1,PetPreviewSnapshot.MIN_UTC_MILLIS-1,PetPreviewSnapshot.MAX_UTC_MILLIS+1,Long.MAX_VALUE}) {
            TileBuilders.Tile tile=service.buildTile(saved(utc,PetPreviewSnapshot.Mode.NORMAL,false,0,0),"invalid-v",192,192,NOW_1440);
            assertEquals("neutral",tile.getResourcesVersion());
            assertNull(find(nodes(tile),LayoutElementBuilders.Image.class));
            assertEquals(Collections.singletonList(service.getString(R.string.tile_neutral)),texts(tile));
            ModifiersBuilders.Modifiers root=modifiers(nodes(tile).get(0).element);
            assertEquals(service.getString(R.string.tile_neutral),root.getSemantics().getContentDescription().getValue());
            ActionBuilders.AndroidActivity open=((ActionBuilders.LaunchAction)root.getClickable().getOnClick()).getAndroidActivity();
            assertEquals(service.getPackageName(),open.getPackageName());assertEquals(MAIN,open.getClassName());
        }
    }
    @Test public void timestampHasTwoVisibleLinesAtSmallAndLargeFontSettings() throws Exception {
        android.content.res.Configuration original=new android.content.res.Configuration(service.getResources().getConfiguration());
        try {
            for(float scale:new float[]{1f,2f}) {
                android.content.res.Configuration config=new android.content.res.Configuration(original);config.fontScale=scale;
                service.getResources().updateConfiguration(config,service.getResources().getDisplayMetrics());
                for(int side:SIDES) {
                    TileBuilders.Tile tile=service.buildTile(saved(TODAY_1432,PetPreviewSnapshot.Mode.NORMAL,false,0,0),"font-v",side,side,NOW_1440);
                    Node n=textNode(nodes(tile),"Saved ");LayoutElementBuilders.Text text=(LayoutElementBuilders.Text)n.element;
                    assertEquals("Saved 14:32\n10/16/2026",text.getText().getValue());
                    assertEquals(2,text.getMaxLines().getValue());
                    assertTrue("keep a legible caption size",text.getFontStyle().getSize().getValue()>=12);
                    assertTrue("reserve both caption lines",dp(((LayoutElementBuilders.Box)n.parent.element).getHeight())>=26);
                    assertCornersInside(tile,side);
                }
            }
        } finally {service.getResources().updateConfiguration(original,service.getResources().getDisplayMetrics());}
    }
    @Test public void neutralMatchesHeroGeometry() throws Exception {
        PetPreviewSnapshot present=saved(TODAY_1432,PetPreviewSnapshot.Mode.NORMAL,false,0,0);
        for(int side:SIDES) {
            Node hero=only(nodes(service.buildTile(present,"v",side,side,NOW_1440)),LayoutElementBuilders.Image.class);
            float presentSide=dp(((LayoutElementBuilders.Box)hero.parent.element).getWidth());
            TileBuilders.Tile neutral=service.buildTile(null,"neutral",side,side,NOW_1440);
            List<Node> nodes=nodes(neutral);
            assertNull("neutral never shows pixels",find(nodes,LayoutElementBuilders.Image.class));
            Node line=textNode(nodes,service.getString(R.string.tile_neutral));
            assertNotNull(line);
            assertEquals(service.getString(R.string.tile_neutral),((LayoutElementBuilders.Text)line.element).getText().getValue());
            LayoutElementBuilders.Box frame=(LayoutElementBuilders.Box)line.parent.element;
            assertNotNull("neutral frame keeps the bezel",frame.getModifiers().getBorder());
            assertEquals("neutral frame matches the hero frame at "+side,presentSide,dp(frame.getWidth()),0);
            assertEquals(presentSide,dp(frame.getHeight()),0);
            assertCornersInside(neutral,side);
        }
    }
    @Test public void noLiveClaims() throws Exception {
        PetPreviewSnapshot combined=saved(YESTERDAY_2359,PetPreviewSnapshot.Mode.PROTECTED,true,90,1);
        for(PetPreviewSnapshot s:new PetPreviewSnapshot[]{null,saved(YESTERDAY_2359,PetPreviewSnapshot.Mode.NORMAL,false,0,0),combined}) {
            TileBuilders.Tile tile=service.buildTile(s,"claims-v",192,192,NOW_0001);
            List<String> spoken=new ArrayList<>(texts(tile));
            for(Node n:nodes(tile)){ModifiersBuilders.Modifiers m=modifiers(n.element);
                if(m!=null&&m.getSemantics()!=null)spoken.add(m.getSemantics().getContentDescription().getValue());}
            for(String text:spoken)assertFalse(text,java.util.regex.Pattern.compile("(?i)\\b(live|now|simulated)\\b").matcher(text).find());
        }
        String description=modifiers(nodes(service.buildTile(combined,"claims-v",192,192,NOW_0001)).get(0).element)
            .getSemantics().getContentDescription().getValue();
        assertTrue(description,description.startsWith("Open Tamaemu. Saved display from "));
        assertTrue("full saved date: "+description,description.contains("Oct 15, 2026"));
        assertTrue("full saved time: "+description,description.contains("23:59"));
        assertTrue("exceptional status announced: "+description,description.contains(combined.exceptionalStatus()));
        assertFalse("spoken snapshot must not demand catch-up",description.contains("Catch-up"));
    }
    @Test public void currentVerificationOverlayKeepsSavedIdentityAndEstimatedHistory() throws Exception {
        PetPreviewSnapshot s=saved(TODAY_1432,PetPreviewSnapshot.Mode.PROTECTED,true,900,1);
        byte[] before=PetPreviewStore.encode(s);
        TileBuilders.Tile tile=service.buildTile(s,"proof-v",192,192,NOW_1440,TimeVerifier.Outcome.MATCH);
        String all=texts(tile).toString();
        assertFalse(all,all.contains("Time needs checking"));assertTrue(all,all.contains("estimated"));
        assertTrue(all,all.contains("Protected: paused away"));assertEquals("proof-v",tile.getResourcesVersion());
        assertEquals("Saved 14:32\n10/16/2026",caption(tile));
        String spoken=modifiers(nodes(tile).get(0).element).getSemantics().getContentDescription().getValue();
        assertTrue("saved timestamp remains estimated",spoken.contains("estimated"));
        assertArrayEquals(before,PetPreviewStore.encode(s));assertEquals(1,s.clockQuality);
        for(TimeVerifier.Outcome result:new TimeVerifier.Outcome[]{TimeVerifier.Outcome.MISMATCH,TimeVerifier.Outcome.UNAVAILABLE}){
            assertTrue(texts(service.buildTile(s,"proof-v",192,192,NOW_1440,result)).toString().contains(TimeVerifier.notice(result)));
        }
        tile=service.buildTile(saved(1,PetPreviewSnapshot.Mode.NORMAL,false,0,1),"proof-v",192,192,NOW_1440,TimeVerifier.Outcome.MATCH);
        assertEquals("neutral",tile.getResourcesVersion());assertNull(find(nodes(tile),LayoutElementBuilders.Image.class));
    }

    private static final int[] SIDES={192,200,227,240,300};
    private static final String MAIN="io.github.projectcetacean.tamaemu.MainActivity";
    private static final TimeZone ZONE=TimeZone.getTimeZone("America/New_York");
    private static final long TODAY_1432=local(2026,10,16,14,32),NOW_1440=local(2026,10,16,14,40),
        YESTERDAY_2359=local(2026,10,15,23,59),NOW_0001=local(2026,10,16,0,1);
    private static long local(int year,int month,int day,int hour,int minute) {
        Calendar c=Calendar.getInstance(ZONE,Locale.US);c.clear();c.set(year,month-1,day,hour,minute);return c.getTimeInMillis();
    }
    /** Synthetic checkpoint: a zero digest and one flat opaque colour, never firmware or save bytes. */
    private static PetPreviewSnapshot saved(long utc,PetPreviewSnapshot.Mode mode,boolean paused,long debt,int quality) {
        int[] pixels=new int[PetPreviewSnapshot.PIXEL_COUNT];Arrays.fill(pixels,0xff123456);
        return new PetPreviewSnapshot(new byte[32],7,utc,mode,paused,debt,quality,pixels);
    }
    private static final class Node {
        final LayoutElementBuilders.LayoutElement element;final Node parent;
        Node(LayoutElementBuilders.LayoutElement element,Node parent){this.element=element;this.parent=parent;}
    }
    /** Depth-first, root first. Lookups use element roles rather than child indexes. */
    private static List<Node> nodes(TileBuilders.Tile tile) {
        List<Node> out=new ArrayList<>();
        collect(tile.getTileTimeline().getTimelineEntries().get(0).getLayout().getRoot(),null,out);return out;
    }
    private static void collect(LayoutElementBuilders.LayoutElement e,Node parent,List<Node> out) {
        Node n=new Node(e,parent);out.add(n);List<LayoutElementBuilders.LayoutElement> children=Collections.emptyList();
        if(e instanceof LayoutElementBuilders.Box)children=((LayoutElementBuilders.Box)e).getContents();
        else if(e instanceof LayoutElementBuilders.Column)children=((LayoutElementBuilders.Column)e).getContents();
        else if(e instanceof LayoutElementBuilders.Row)children=((LayoutElementBuilders.Row)e).getContents();
        for(LayoutElementBuilders.LayoutElement child:children)collect(child,n,out);
    }
    private static Node find(List<Node> nodes,Class<?> type){for(Node n:nodes)if(type.isInstance(n.element))return n;return null;}
    private static Node only(List<Node> nodes,Class<?> type) {
        Node found=null;for(Node n:nodes)if(type.isInstance(n.element)){assertNull("exactly one "+type.getSimpleName(),found);found=n;}
        assertNotNull(type.getSimpleName(),found);return found;
    }
    private static Node textNode(List<Node> nodes,String prefix) {
        for(Node n:nodes)if(n.element instanceof LayoutElementBuilders.Text&&((LayoutElementBuilders.Text)n.element).getText().getValue().startsWith(prefix))return n;
        return null;
    }
    private static List<String> texts(TileBuilders.Tile tile) {
        List<String> out=new ArrayList<>();
        for(Node n:nodes(tile))if(n.element instanceof LayoutElementBuilders.Text)out.add(((LayoutElementBuilders.Text)n.element).getText().getValue());
        return out;
    }
    private static String caption(TileBuilders.Tile tile) {
        Node n=textNode(nodes(tile),"Saved ");assertNotNull("caption present",n);
        return ((LayoutElementBuilders.Text)n.element).getText().getValue();
    }
    private static boolean isAncestor(Node candidate,Node of){for(Node p=of.parent;p!=null;p=p.parent)if(p==candidate)return true;return false;}
    private static ModifiersBuilders.Modifiers modifiers(LayoutElementBuilders.LayoutElement e) {
        if(e instanceof LayoutElementBuilders.Box)return ((LayoutElementBuilders.Box)e).getModifiers();
        if(e instanceof LayoutElementBuilders.Column)return ((LayoutElementBuilders.Column)e).getModifiers();
        if(e instanceof LayoutElementBuilders.Row)return ((LayoutElementBuilders.Row)e).getModifiers();
        if(e instanceof LayoutElementBuilders.Image)return ((LayoutElementBuilders.Image)e).getModifiers();
        if(e instanceof LayoutElementBuilders.Text)return ((LayoutElementBuilders.Text)e).getModifiers();
        if(e instanceof LayoutElementBuilders.Spacer)return ((LayoutElementBuilders.Spacer)e).getModifiers();
        return null;
    }
    private static float dp(Object dimension){return ((DimensionBuilders.DpProp)dimension).getValue();}
    /** Fixed dp size, or null for expand/wrap elements whose bounds are set by their container. */
    private static float[] size(LayoutElementBuilders.LayoutElement e) {
        Object w=null,h=null;
        if(e instanceof LayoutElementBuilders.Box){w=((LayoutElementBuilders.Box)e).getWidth();h=((LayoutElementBuilders.Box)e).getHeight();}
        else if(e instanceof LayoutElementBuilders.Image){w=((LayoutElementBuilders.Image)e).getWidth();h=((LayoutElementBuilders.Image)e).getHeight();}
        else if(e instanceof LayoutElementBuilders.Spacer){w=((LayoutElementBuilders.Spacer)e).getWidth();h=((LayoutElementBuilders.Spacer)e).getHeight();}
        if(!(w instanceof DimensionBuilders.DpProp)||!(h instanceof DimensionBuilders.DpProp))return null;
        return new float[]{dp(w),dp(h)};
    }
    /** Root fills the round screen and centres one column whose rows stack top to bottom. */
    private static void assertCornersInside(TileBuilders.Tile tile,int side) {
        LayoutElementBuilders.Column column=(LayoutElementBuilders.Column)only(nodes(tile),LayoutElementBuilders.Column.class).element;
        double total=0;for(LayoutElementBuilders.LayoutElement e:column.getContents())total+=size(e)[1];
        double y=(side-total)/2,limit=side/2.0-4;
        for(LayoutElementBuilders.LayoutElement e:column.getContents()) {
            float[] s=size(e);
            assertTrue("top corners inside radius-4 at "+side,Math.hypot(s[0]/2,y-side/2.0)<=limit);
            assertTrue("bottom corners inside radius-4 at "+side,Math.hypot(s[0]/2,y+s[1]-side/2.0)<=limit);
            y+=s[1];
        }
    }
    /** Text colour against the nearest painted ancestor surface. */
    private static double contrast(Node text) {
        double a=luminance(((LayoutElementBuilders.Text)text.element).getFontStyle().getColor().getArgb());
        for(Node p=text.parent;p!=null;p=p.parent) {
            ModifiersBuilders.Modifiers m=modifiers(p.element);
            if(m!=null&&m.getBackground()!=null){double b=luminance(m.getBackground().getColor().getArgb());return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);}
        }
        throw new AssertionError("text has no painted surface");
    }
    private static double luminance(int argb) {
        double[] rgb={(argb>>16)&255,(argb>>8)&255,argb&255};
        for(int i=0;i<3;i++){double value=rgb[i]/255;rgb[i]=value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4);}
        return .2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2];
    }
    @Test public void resourceRequestReturnsPinnedNAfterNPlusOne() throws Exception {
        byte[] save=PetPreviewStoreTest.envelope(7,0,0,1);PetPreviewStore store=new PetPreviewStore(dir);
        String n=store.pinImage(PetPreviewStoreTest.snapshot(save,7,PetPreviewSnapshot.Mode.NORMAL,false,0,0,0xff123456));
        String n1=store.pinImage(PetPreviewStoreTest.snapshot(save,8,PetPreviewSnapshot.Mode.NORMAL,false,0,0,0xff654321));
        ResourceBuilders.Resources resources=service.onTileResourcesRequest(RequestBuilders.ResourcesRequest.fromProto(RequestProto.ResourcesRequest.newBuilder().setVersion(n).build())).get(3,TimeUnit.SECONDS);
        assertEquals(n,resources.getVersion());assertEquals(1,resources.getIdToImageMapping().size());
        byte[] png=resources.getIdToImageMapping().get("pet_preview").getInlineResource().getData();
        android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(png,0,png.length);
        assertEquals(0xff123456,bitmap.getPixel(0,0));assertNotEquals(n,n1);
    }
    @Test public void unknownResourceFailsWithNoSubstituteImage() throws Exception {
        ResourceBuilders.Resources r=service.onTileResourcesRequest(RequestBuilders.ResourcesRequest.fromProto(RequestProto.ResourcesRequest.newBuilder().setVersion("unknown").build())).get(3,TimeUnit.SECONDS);
        assertEquals("unknown",r.getVersion());assertTrue(r.getIdToImageMapping().isEmpty());
    }
}
