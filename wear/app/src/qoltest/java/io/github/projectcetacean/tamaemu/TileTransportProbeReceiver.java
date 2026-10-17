package io.github.projectcetacean.tamaemu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.wear.tiles.TileService;

/** Explicit shell trigger while the already-selected Tile remains visible. QA debug only. */
public final class TileTransportProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!TileTransportProbeState.enabled(context)) return;
        String action = intent == null ? null : intent.getStringExtra("action");
        TileTransportProbeState state = TileTransportProbeState.get();
        if ("arm".equals(action)) {
            Context app = context.getApplicationContext();
            state.arm(app);
            state.event("explicit_trigger", "requested_elapsed_ms=" + SystemClock.elapsedRealtime()
                    + " requested_wall_ms=" + System.currentTimeMillis());
            // Initial fetch kick is separate from the bounded frame update counter.
            try { TileService.getUpdater(app).requestUpdate(TileTransportProbeService.class); }
            catch (RuntimeException | LinkageError unavailable) {
                state.event("explicit_trigger_error", "error=" + unavailable.getClass().getSimpleName());
            }
        } else if ("stop".equals(action)) {
            state.stop(context, "explicit_receiver_stop");
        }
    }
}
