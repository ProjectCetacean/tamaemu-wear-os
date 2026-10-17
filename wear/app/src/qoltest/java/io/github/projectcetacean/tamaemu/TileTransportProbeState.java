package io.github.projectcetacean.tamaemu;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.Log;
import androidx.wear.tiles.TileService;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

/** In-memory synthetic data only. The monitor serializes publication, stop and callback snapshots. */
final class TileTransportProbeState {
    static final String TAG = "TamaemuTileProbe";
    static final String PACKAGE = "io.github.projectcetacean.tamaemu.qoltest";
    static final long HARD_EXPIRY_MS = 15_000;
    static final long PERIOD_MS = 1_000;
    static final long ARM_EXPIRY_MS = 60_000;
    // Distinct from the pet's 0x54414d41 periodic job. Never persisted.
    static final int JOB_ID = 0x54505242;
    private static final TileTransportProbeState INSTANCE = new TileTransportProbeState();

    static TileTransportProbeState get() { return INSTANCE; }
    static boolean enabled(Context context) {
        return PACKAGE.equals(context.getPackageName())
                && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    static final class Frame {
        final String run, version, phase;
        final int sequence;
        final long elapsed, wall;
        private final byte[] png;
        Frame(String run, int sequence, String phase, long elapsed, long wall) {
            this.run = run;
            this.sequence = sequence;
            this.phase = phase;
            this.elapsed = elapsed;
            this.wall = wall;
            version = run + "-" + phase + "-" + sequence;
            png = draw(sequence, phase, elapsed, wall);
        }
        byte[] png() { return png.clone(); }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object callbacks = new Object();
    private final LinkedHashMap<String, Frame> frames = new LinkedHashMap<>();
    private String run = UUID.randomUUID().toString();
    private String phase = "INACTIVE";
    private long armedElapsed, armedWall, startElapsed, startWall, deadlineElapsed;
    private boolean started;
    private int sequence, requested, tileRequests, resourceRequests;
    private Frame current;
    private TileTransportProbeJob job;
    private JobParameters parameters;

    synchronized void arm(Context context) {
        if (!enabled(context)) return;
        endWork(context, true);
        run = UUID.randomUUID().toString();
        phase = "ARMED";
        armedElapsed = SystemClock.elapsedRealtime();
        armedWall = System.currentTimeMillis();
        startElapsed = startWall = deadlineElapsed = 0;
        started = false;
        sequence = requested = tileRequests = resourceRequests = 0;
        frames.clear();
        publish("ARMED", 0);
        event("arm", "arm_elapsed_ms=" + armedElapsed + " arm_wall_ms=" + armedWall
                + " arm_expires_elapsed_ms=" + (armedElapsed + ARM_EXPIRY_MS));
        String token = run;
        later(() -> expireIfDue(context.getApplicationContext(), token), ARM_EXPIRY_MS);
        // Deliberately no requestUpdate here: the first subsequent provider request starts the run.
    }

    synchronized Frame tile(Context context) {
        ensureFrame();
        if (!enabled(context)) return current;
        expireIfDue(context, run);
        tileRequests++;
        if ("ARMED".equals(phase) && !started) {
            started = true; // Set before scheduling: duplicate requests cannot schedule another job.
            phase = "RUNNING";
            startElapsed = SystemClock.elapsedRealtime();
            startWall = System.currentTimeMillis();
            deadlineElapsed = startElapsed + HARD_EXPIRY_MS;
            handler.removeCallbacksAndMessages(callbacks);
            String token = run;
            later(() -> expireIfDue(context.getApplicationContext(), token), HARD_EXPIRY_MS);
            event("start", "start_elapsed_ms=" + startElapsed + " start_wall_ms=" + startWall
                    + " deadline_elapsed_ms=" + deadlineElapsed);
            if (!schedule(context)) expire(context, "schedule_failed");
            expireIfDue(context, run);
        }
        event("tile_served", frameFields(current) + " tile_requests=" + tileRequests);
        return current;
    }

    synchronized Frame resources(Context context, String version) {
        ensureFrame();
        if (!enabled(context)) return null;
        expireIfDue(context, run);
        resourceRequests++;
        Frame frame = frames.get(version);
        if (frame == null) {
            // Never attach current pixels to an old version. Empty resources fail closed.
            event("resources_miss", "version=" + safe(version) + " resource_requests=" + resourceRequests);
            // A prior run's valid version can arrive after rearm dropped its cache.
            // Return no mapping, without allowing that stale callback to stop this run.
            boolean priorRun = version != null && version.matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-(INACTIVE|ARMED|TEST|EXPIRED)-[0-9]+")
                    && !version.startsWith(run + "-");
            if (!priorRun) expire(context, "resources_miss");
            return null;
        }
        event("resources_served", frameFields(frame) + " resource_requests=" + resourceRequests);
        return frame;
    }

    synchronized boolean startJob(TileTransportProbeJob service, JobParameters incoming) {
        event("job_start_callback", "job_id=" + incoming.getJobId());
        String token = incoming.getExtras().getString("run");
        expireIfDue(service, run);
        if (run.equals(token) && "RUNNING".equals(phase) && job == service
                && incoming.getJobId() == JOB_ID) {
            event("job_duplicate", "worker=reused");
            return true;
        }
        if (!run.equals(token) || !"RUNNING".equals(phase) || job != null
                || incoming.getJobId() != JOB_ID) {
            event("job_rejected", "incoming_run=" + safe(token));
            return false;
        }
        job = service;
        parameters = incoming;
        tick(service.getApplicationContext(), token);
        return job == service;
    }

    private synchronized void tick(Context context, String token) {
        if (!run.equals(token) || !"RUNNING".equals(phase) || job == null) return;
        if (expireIfDue(context, token)) return;
        long elapsed = SystemClock.elapsedRealtime() - startElapsed;
        int next = (int) (elapsed / PERIOD_MS) + 1;
        // Late callbacks skip slots instead of issuing a burst of stale frames.
        if (next > sequence) {
            sequence = next;
            publish("TEST", sequence);
            // Encoding time must not admit a new update after the monotonic deadline.
            if (expireIfDue(context, token)) return;
            update(context, "frame");
        }
        long target = Math.min(deadlineElapsed, startElapsed + sequence * PERIOD_MS);
        later(() -> tick(context, token), Math.max(1, target - SystemClock.elapsedRealtime()));
    }

    synchronized void stop(Context context, String reason) {
        if (!enabled(context)) return;
        if ("ARMED".equals(phase) || "RUNNING".equals(phase)) expire(context, reason);
        else endWork(context, true); // Also cancels a stale queued job after process death.
    }

    synchronized void jobStopped(TileTransportProbeJob service, JobParameters incoming) {
        event("job_stop_callback", "incoming_run=" + safe(incoming.getExtras().getString("run")));
        if (job == service && incoming.getJobId() == JOB_ID
                && run.equals(incoming.getExtras().getString("run"))) {
            // The scheduler is already ending it; do not call jobFinished for onStopJob.
            job = null;
            parameters = null;
            expire(service, "scheduler_stop");
        }
    }

    synchronized void jobDestroyed(TileTransportProbeJob service) {
        event("job_destroy_callback", "owned=" + (job == service));
        if (job == service) {
            job = null;
            parameters = null;
            expire(service, "job_destroyed");
        }
    }

    private synchronized boolean expireIfDue(Context context, String token) {
        if (!run.equals(token)) return true;
        long now = SystemClock.elapsedRealtime();
        if ("ARMED".equals(phase) && now >= armedElapsed + ARM_EXPIRY_MS)
            expire(context, "arm_timeout");
        else if ("RUNNING".equals(phase) && now >= deadlineElapsed)
            expire(context, "hard_deadline");
        return !"RUNNING".equals(phase);
    }

    private void expire(Context context, String reason) {
        if (!"ARMED".equals(phase) && !"RUNNING".equals(phase)) return;
        phase = "EXPIRED"; // Terminal before cancellation or renderer notification can re-enter.
        endWork(context, true);
        publish("EXPIRED", sequence);
        event("expire", "reason=" + reason + " deadline_elapsed_ms=" + deadlineElapsed
                + " requested=" + requested + " tile_requests=" + tileRequests
                + " resource_requests=" + resourceRequests + " cache_size=" + frames.size());
        update(context, "final_expired"); // Exactly one terminal update attempt per arm/run.
    }

    private void endWork(Context context, boolean finish) {
        handler.removeCallbacksAndMessages(callbacks);
        TileTransportProbeJob active = job;
        JobParameters activeParameters = parameters;
        job = null;
        parameters = null;
        if (active != null && finish) active.jobFinished(activeParameters, false);
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }

    private boolean schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        if (Build.VERSION.SDK_INT >= 31 && scheduleAttempt(context, scheduler, true)) return true;
        if (expireIfDue(context, run)) return false;
        return scheduleAttempt(context, scheduler, false);
    }

    private boolean scheduleAttempt(Context context, JobScheduler scheduler, boolean expedited) {
        try {
            PersistableBundle extras = new PersistableBundle();
            extras.putString("run", run);
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, TileTransportProbeJob.class)).setExtras(extras);
            if (expedited && Build.VERSION.SDK_INT >= 31) builder.setExpedited(true);
            else builder.setOverrideDeadline(0);
            int result = scheduler.schedule(builder.build());
            event("schedule", "expedited=" + expedited + " result=" + result);
            return result == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException | LinkageError unavailable) {
            event("schedule", "expedited=" + expedited + " error=" + unavailable.getClass().getSimpleName());
            return false;
        }
    }

    private void later(Runnable task, long delay) {
        handler.postAtTime(task, callbacks, SystemClock.uptimeMillis() + delay);
    }

    private void update(Context context, String reason) {
        requested++;
        event("request_update", "reason=" + reason + " requested=" + requested
                + " version=" + current.version);
        try { TileService.getUpdater(context).requestUpdate(TileTransportProbeService.class); }
        catch (RuntimeException | LinkageError unavailable) {
            event("request_update_error", "error=" + unavailable.getClass().getSimpleName());
        }
    }

    private void ensureFrame() { if (current == null) publish("INACTIVE", 0); }

    private void publish(String label, int number) {
        current = new Frame(run, number, label, SystemClock.elapsedRealtime(), System.currentTimeMillis());
        frames.put(current.version, current);
        while (frames.size() > 17) frames.remove(frames.keySet().iterator().next());
        event("publish", frameFields(current) + " cache_size=" + frames.size());
    }

    synchronized String describe(Context context) {
        expireIfDue(context, run);
        return phase + "\nrun " + run.substring(0, 8) + " / frame " + sequence;
    }

    synchronized void event(String event, String fields) {
        Log.i(TAG, "event=" + event + " run=" + run + " seq=" + sequence
                + " elapsed_ms=" + SystemClock.elapsedRealtime() + " wall_ms=" + System.currentTimeMillis()
                + " phase=" + phase + " " + fields);
    }

    private static String frameFields(Frame frame) {
        return "frame_run=" + frame.run + " frame_seq=" + frame.sequence + " frame_phase=" + frame.phase
                + " version=" + frame.version + " frame_elapsed_ms=" + frame.elapsed + " frame_wall_ms=" + frame.wall;
    }

    private static String safe(String value) {
        if (value == null) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_").substring(0, Math.min(value.length(), 100));
    }

    private static byte[] draw(int sequence, String phase, long elapsed, long wall) {
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor("TEST".equals(phase) ? (sequence % 2 == 0 ? Color.CYAN : Color.YELLOW) : Color.LTGRAY);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(12);
            canvas.drawText("SYNTHETIC " + phase, 64, 15, paint);
            paint.setTextSize(58);
            canvas.drawText(Integer.toString(sequence), 64, 70, paint);
            paint.setTextSize(11);
            canvas.drawText(String.format(Locale.US, "t=%05d", wall % 100_000), 64, 87, paint);
            canvas.drawText("e=" + (elapsed % 100_000), 64, 101, paint);
            int x = (sequence * 7) % 100;
            canvas.drawRect(x, 107, x + 28, 128, paint);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
                throw new IllegalStateException("Synthetic PNG encode failed");
            return png.toByteArray();
        } finally { bitmap.recycle(); }
    }
}
