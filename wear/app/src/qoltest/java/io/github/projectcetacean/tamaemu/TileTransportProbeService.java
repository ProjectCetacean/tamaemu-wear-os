package io.github.projectcetacean.tamaemu;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.protolayout.expression.VersionBuilders;
import androidx.wear.tiles.EventBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import static androidx.wear.protolayout.DimensionBuilders.*;
import static androidx.wear.protolayout.LayoutElementBuilders.*;

/** Disposable transport diagnostic. A served frame is not evidence of a visible frame. */
public final class TileTransportProbeService extends TileService {
    private TileTransportProbeState state() { return TileTransportProbeState.get(); }

    @Override public void onCreate() {
        super.onCreate();
        state().event("service_create", "enabled=" + TileTransportProbeState.enabled(this));
    }

    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        logRenderer(request);
        TileTransportProbeState.Frame frame = state().tile(this);
        return immediate(tile(frame));
    }

    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(RequestBuilders.ResourcesRequest request) {
        String version = request.getVersion();
        TileTransportProbeState.Frame frame = state().resources(this, version);
        ResourceBuilders.Resources.Builder resources = new ResourceBuilders.Resources.Builder().setVersion(version);
        if (frame != null) resources.addIdToImageMapping("probe",
                new ResourceBuilders.ImageResource.Builder().setInlineResource(
                        new ResourceBuilders.InlineImageResource.Builder().setData(frame.png())
                                .setWidthPx(128).setHeightPx(128).build()).build());
        return immediate(resources.build());
    }

    private TileBuilders.Tile tile(TileTransportProbeState.Frame frame) {
        ModifiersBuilders.Clickable open = new ModifiersBuilders.Clickable.Builder().setId("open_probe")
                .setOnClick(new ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                        new ActionBuilders.AndroidActivity.Builder().setPackageName(getPackageName())
                                .setClassName(TileTransportProbeActivity.class.getName()).build()).build()).build();
        Column column = new Column.Builder().setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                .addContent(text("Transport probe", 14))
                .addContent(new Image.Builder().setResourceId("probe").setWidth(dp(128)).setHeight(dp(128))
                        .setContentScaleMode(CONTENT_SCALE_MODE_FIT).build())
                .addContent(text(frame.phase + " frame " + frame.sequence, 12)).build();
        Box root = new Box.Builder().setWidth(expand()).setHeight(expand())
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder().setClickable(open)
                        .setBackground(new ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(0xff101820)).build())
                        .setSemantics(new ModifiersBuilders.Semantics.Builder()
                                .setContentDescription("Transport probe, synthetic " + frame.phase + " frame " + frame.sequence
                                        + ", tap to open probe controls").build()).build())
                .addContent(column).build();
        // Static TEST labels remain truthful if the remote renderer keeps a cached layout.
        return new TileBuilders.Tile.Builder().setResourcesVersion(frame.version).setFreshnessIntervalMillis(0)
                .setTileTimeline(new TimelineBuilders.Timeline.Builder().addTimelineEntry(
                        new TimelineBuilders.TimelineEntry.Builder().setLayout(
                                new LayoutElementBuilders.Layout.Builder().setRoot(root).build()).build()).build()).build();
    }

    private static Text text(String value, int size) {
        return new Text.Builder().setText(value).setMaxLines(1).setMultilineAlignment(TEXT_ALIGN_CENTER)
                .setFontStyle(new FontStyle.Builder().setSize(sp(size)).setColor(ColorBuilders.argb(0xffffffff)).build()).build();
    }

    private void logRenderer(RequestBuilders.TileRequest request) {
        String fields = "tile_id=" + request.getTileId();
        try {
            VersionBuilders.VersionInfo schema = request.getDeviceConfiguration().getRendererSchemaVersion();
            fields += " schema=" + schema.getMajor() + "." + schema.getMinor();
            fields += " minimum_freshness_ms="
                    + request.getDeviceConfiguration().getCapabilities().getMinimumFreshnessLimitMillis();
            // The method exists in Tiles 1.6.2; its data needs renderer schema >= 1.600.
            if (schema.getMajor() > 1 || (schema.getMajor() == 1 && schema.getMinor() >= 600))
                fields += " last_visible_wall_ms=" + request.getLastVisibleTime().toEpochMilli();
            else fields += " last_visible_wall_ms=unsupported";
        } catch (RuntimeException | LinkageError unavailable) {
            fields += " renderer_metadata=unavailable";
        }
        state().event("tile_request", fields);
    }

    @Override protected ListenableFuture<Void> onRecentInteractionEventsAsync(List<EventBuilders.TileInteractionEvent> events) {
        state().event("interaction_batch", "count=" + events.size());
        // Do not call super: its compatibility path can synthesize the legacy callbacks.
        // Every received event is evidence only; duplicates/reordering cannot restart work.
        for (EventBuilders.TileInteractionEvent event : events) {
            long reported = event.getTimestamp().toEpochMilli();
            long receipt = System.currentTimeMillis();
            state().event("interaction", "source=batch type=" + interaction(event.getEventType())
                    + " tile_id=" + event.getTileId() + " reported_wall_ms=" + reported
                    + " receipt_wall_ms=" + receipt + " delivery_delta_ms="
                    + (reported == 0 ? "unknown" : Long.toString(receipt - reported)));
        }
        return immediate(null);
    }

    @SuppressWarnings("deprecation")
    @Override protected void onTileEnterEvent(EventBuilders.TileEnterEvent event) {
        state().event("interaction", "source=legacy type=ENTER tile_id=" + event.getTileId()
                + " reported_wall_ms=unavailable receipt_wall_ms=" + System.currentTimeMillis());
    }

    @SuppressWarnings("deprecation")
    @Override protected void onTileLeaveEvent(EventBuilders.TileLeaveEvent event) {
        state().event("interaction", "source=legacy type=LEAVE tile_id=" + event.getTileId()
                + " reported_wall_ms=unavailable receipt_wall_ms=" + System.currentTimeMillis());
    }

    @Override protected void onTileAddEvent(EventBuilders.TileAddEvent event) {
        state().event("tile_add", "callback=received");
    }

    @Override protected void onTileRemoveEvent(EventBuilders.TileRemoveEvent event) {
        state().event("tile_remove", "callback=received");
        state().stop(this, "tile_removed");
    }

    @Override public void onDestroy() {
        state().event("service_destroy", "job_lease=independent");
        super.onDestroy();
    }

    private static String interaction(int type) {
        if (type == EventBuilders.TileInteractionEvent.ENTER) return "ENTER";
        if (type == EventBuilders.TileInteractionEvent.LEAVE) return "LEAVE";
        return "UNKNOWN";
    }

    private static <T> ListenableFuture<T> immediate(T value) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(value);
            return "Synthetic Tile probe";
        });
    }
}
