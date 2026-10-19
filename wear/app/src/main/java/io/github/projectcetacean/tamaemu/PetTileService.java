package io.github.projectcetacean.tamaemu;

import android.graphics.Bitmap;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.protolayout.*;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import static androidx.wear.protolayout.DimensionBuilders.*;
import static androidx.wear.protolayout.LayoutElementBuilders.*;

/** Cached checkpoint only. This provider must never initialize a session or load JNI. */
public final class PetTileService extends TileService {
    private final ThreadPoolExecutor reads=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),
        r->{Thread t=new Thread(r,"pet-tile-cache");t.setDaemon(true);return t;});
    private final TileCallbackQueue callbacks=new TileCallbackQueue(reads,5000);
    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        int width=request.getDeviceConfiguration().getScreenWidthDp(),height=request.getDeviceConfiguration().getScreenHeightDp();
        TileBuilders.Tile neutral=buildTile(null,"neutral",width,height);
        long token=previewToken();
        return CallbackToFutureAdapter.getFuture(completer->{
            if(token<0){completer.set(neutral);return "Recovery-gated Tile";}
            Runnable cancel=callbacks.submit(()->{
                if(!previewAllowed(token))return neutral;
                PetPreviewStore store=new PetPreviewStore(getFilesDir());PetPreviewStore.Read read=store.readVerified();
                if(Thread.currentThread().isInterrupted())return neutral;
                PetPreviewSnapshot snapshot=read.snapshot;String version="neutral";
                if(snapshot!=null){version=store.pinImage(snapshot);}
                if(Thread.currentThread().isInterrupted())return neutral;
                return buildTile(snapshot,version,width,height);
            },neutral,result->completePreview(token,()->completer.set(result),()->completer.set(neutral)));
            completer.addCancellationListener(cancel,Runnable::run);
            return "Pet checkpoint Tile";
        });
    }
    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(RequestBuilders.ResourcesRequest request) {
        String version=request.getVersion();
        ResourceBuilders.Resources empty=new ResourceBuilders.Resources.Builder().setVersion(version).build();
        long token=previewToken();
        return CallbackToFutureAdapter.getFuture(completer->{
            if(token<0||"neutral".equals(version)){completer.set(empty);return "Neutral Tile resources";}
            Runnable cancel=callbacks.submit(()->{
                if(!previewAllowed(token))return empty;
                int[] pixels=new PetPreviewStore(getFilesDir()).readPinnedImage(version);
                if(Thread.currentThread().isInterrupted())return empty;
                if(pixels==null){TileService.getUpdater(this).requestUpdate(PetTileService.class);return empty;}
                Bitmap bitmap=Bitmap.createBitmap(pixels,128,128,Bitmap.Config.ARGB_8888);
                ByteArrayOutputStream png=new ByteArrayOutputStream();
                try {
                    if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,png))return empty;
                    return new ResourceBuilders.Resources.Builder().setVersion(version)
                        .addIdToImageMapping("pet_preview",new ResourceBuilders.ImageResource.Builder()
                            .setInlineResource(new ResourceBuilders.InlineImageResource.Builder().setData(png.toByteArray())
                                .setWidthPx(128).setHeightPx(128).build()).build()).build();
                } finally {bitmap.recycle();}
            },empty,result->completePreview(token,()->completer.set(result),()->completer.set(empty)));
            completer.addCancellationListener(cancel,Runnable::run);
            return "Pinned checkpoint pixels";
        });
    }
    private long previewToken() {
        android.app.Application application=getApplication();
        // Provider-only tests and other generic Application hosts retain their existing fallback.
        return application instanceof TamaemuApplication?((TamaemuApplication)application).previewToken():0;
    }
    private boolean previewAllowed(long token) { return token>=0&&token==previewToken(); }
    private void completePreview(long token,Runnable available,Runnable neutral) {
        android.app.Application application=getApplication();
        if(application instanceof TamaemuApplication)((TamaemuApplication)application).completePreview(token,available,neutral);
        else available.run();
    }
    /** Package-visible seam exercised against actual ProtoLayout objects by Android unit tests. */
    TileBuilders.Tile buildTile(PetPreviewSnapshot s,String version,int width,int height) {
        return buildTile(s,version,width,height,System.currentTimeMillis());
    }
    TileBuilders.Tile buildTile(PetPreviewSnapshot s,String version,int width,int height,long nowUtcMillis) {
        if(s!=null&&(s.committedUtcMillis<PetPreviewSnapshot.MIN_UTC_MILLIS||s.committedUtcMillis>PetPreviewSnapshot.MAX_UTC_MILLIS)){s=null;version="neutral";}
        int side=Math.min(width,height);if(side<192)side=192;
        int frameSide=heroFrame(side),textWidth=rowWidth(frameSide,side);
        ModifiersBuilders.Clickable open=new ModifiersBuilders.Clickable.Builder().setId("open_pet")
            .setOnClick(new ActionBuilders.LaunchAction.Builder().setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                .setPackageName(getPackageName()).setClassName("io.github.projectcetacean.tamaemu.MainActivity").build()).build()).build();
        String status=s==null?null:s.exceptionalStatus();
        String description=s==null?getString(R.string.tile_neutral):getString(R.string.tile_open_description,
            format("MMM d, yyyy HH:mm",s.committedUtcMillis),status==null?"":", "+status);
        // The two-dp recess surrounds the image; no border or rounded mask covers LCD pixels.
        LayoutElement preview=s==null?text(getString(R.string.tile_neutral),12,3,0xffeff4f8):
            new Image.Builder().setResourceId("pet_preview").setWidth(dp(frameSide-4)).setHeight(dp(frameSide-4))
                .setContentScaleMode(CONTENT_SCALE_MODE_FIT).build();
        Box frame=new Box.Builder().setWidth(dp(frameSide)).setHeight(dp(frameSide))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(new ModifiersBuilders.Modifiers.Builder().setBackground(surface(0xff242b35,2))
                .setBorder(new ModifiersBuilders.Border.Builder().setWidth(dp(1)).setColor(ColorBuilders.argb(0xff414c59)).build()).build())
            .addContent(preview).build();
        // The chip row is always reserved so the hero keeps one size whatever the status.
        Box.Builder chip=row(textWidth,CHIP_DP);
        if(status!=null)chip.setModifiers(new ModifiersBuilders.Modifiers.Builder().setBackground(surface(0xff242b35,12)).build())
            .addContent(text(status,10,2,0xffeff4f8));
        Box.Builder caption=row(textWidth,CAPTION_DP);
        if(s!=null)caption.addContent(new Text.Builder()
            .setText(getString(R.string.tile_saved_time,caption(s.committedUtcMillis,nowUtcMillis)))
            .setMaxLines(2).setLineHeight(sp(12)).setMultilineAlignment(TEXT_ALIGN_CENTER)
            .setFontStyle(new FontStyle.Builder().setSize(sp(12)).setColor(ColorBuilders.argb(0xffa6b1bc)).build()).build());
        Column column=new Column.Builder().setWidth(expand()).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(chip.build()).addContent(frame).addContent(caption.build()).build();
        // The whole Tile is the single open action and the single announcement.
        Box root=new Box.Builder().setWidth(expand()).setHeight(expand()).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(new ModifiersBuilders.Modifiers.Builder().setBackground(surface(0xff161b22,0)).setClickable(open)
                .setSemantics(new ModifiersBuilders.Semantics.Builder().setContentDescription(description).build()).build())
            .addContent(column).build();
        return new TileBuilders.Tile.Builder().setResourcesVersion(version).setFreshnessIntervalMillis(0)
            .setTileTimeline(new TimelineBuilders.Timeline.Builder().addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                .setLayout(new Layout.Builder().setRoot(root).build()).build()).build()).build();
    }
    private static final int CHIP_DP=24,CAPTION_DP=26,MIN_ROW_DP=68;
    /** Largest frame whose centred chip, frame and caption rows keep every corner inside radius minus 4 dp. */
    private static int heroFrame(int side) {
        double limit=side/2.0-4;
        for(int frame=side;frame>MIN_ROW_DP;frame--) {
            double half=(frame+CHIP_DP+CAPTION_DP)/2.0,edge=half-Math.min(CHIP_DP,CAPTION_DP);
            if(half<limit&&Math.hypot(frame/2.0,edge)<=limit&&rowWidth(frame,side)>=MIN_ROW_DP)return frame;
        }
        return MIN_ROW_DP;
    }
    /** Text rows match the frame width but narrow to the round edge at the top and bottom of the stack. */
    private static int rowWidth(int frame,int side) {
        double limit=side/2.0-4,half=(frame+CHIP_DP+CAPTION_DP)/2.0;
        return half>=limit?0:Math.min(frame,(int)Math.floor(2*Math.sqrt(limit*limit-half*half)));
    }
    /** Both saved time and full localized date survive a cached layout's day/year boundary. */
    private static String caption(long saved,long now) {
        SimpleDateFormat date=(SimpleDateFormat)java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT,Locale.getDefault());
        return format("HH:mm\n"+date.toPattern().replaceAll("y+","yyyy"),saved);
    }
    private static String format(String pattern,long utc) {
        return new SimpleDateFormat(pattern,Locale.getDefault()).format(new Date(utc));
    }
    private static ModifiersBuilders.Background surface(int color,float radius) {
        return new ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(color))
            .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(dp(radius)).build()).build();
    }
    private static Box.Builder row(float width,float height) {
        return new Box.Builder().setWidth(dp(width)).setHeight(dp(height)).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER);
    }
    private static Text text(String text,float size,int lines,int color) {
        return new Text.Builder().setText(text).setMaxLines(lines).setMultilineAlignment(TEXT_ALIGN_CENTER)
            .setFontStyle(new FontStyle.Builder().setSize(sp(size)).setColor(ColorBuilders.argb(color)).build()).build();
    }
    @Override public void onDestroy(){callbacks.close();super.onDestroy();}
}
