package io.github.projectcetacean.tamaemu;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** One process-bound synthetic run, never persisted or retried. */
public final class TileTransportProbeJob extends JobService {
    @Override public boolean onStartJob(JobParameters parameters) {
        return TileTransportProbeState.enabled(this)
                && TileTransportProbeState.get().startJob(this, parameters);
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        TileTransportProbeState.get().jobStopped(this, parameters);
        return false;
    }

    @Override public void onDestroy() {
        TileTransportProbeState.get().jobDestroyed(this);
        super.onDestroy();
    }
}
