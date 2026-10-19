package io.github.projectcetacean.tamaemu;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import androidx.wear.tiles.TileService;

import java.io.File;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class TamaemuApplication extends Application {
    private EmulatorSession session;
    private TimeVerifier timeVerifier;
    private final Object timeNotifications=new Object();
    private volatile TimeVerifier.Outcome notifiedTime=TimeVerifier.Outcome.UNAVAILABLE;

    private ClockSample clockSample() {
        long boot=ClockSample.UNKNOWN_BOOT;
        try { int value=Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT);if(value>=0)boot=value; }
        catch(Settings.SettingNotFoundException|SecurityException unavailable) { /* Unknown boot cannot reuse proof. */ }
        return new ClockSample(System.currentTimeMillis(),SystemClock.elapsedRealtime(),boot);
    }
    TimeVerifier.Outcome timeOutcome(){
        TimeVerifier.Outcome result=timeVerifier.current();
        timeChanged(result);
        return result;
    }
    private void timeChanged(TimeVerifier.Outcome result) {
        synchronized(timeNotifications) {
            if(result==notifiedTime)return;
            notifiedTime=result;
        }
        previewWrites.execute(()->{
            try {TileService.getUpdater(this).requestUpdate(PetTileService.class);}
            catch(RuntimeException|LinkageError unavailable){ /* Host refresh is best effort. */ }
        });
    }
    // The existing cache executor serializes opportunistic checks independently of owner/save locks.
    void timeOpportunity(Runnable complete) {
        previewWrites.execute(()->{
            verifyTime();
            if(complete!=null)complete.run();
        });
    }
    TimeVerifier.Outcome verifyTime() {
        TimeVerifier.Outcome result=timeVerifier.check();
        timeChanged(result);
        return result;
    }
    private volatile ForegroundAudio foregroundAudio;
    void configureAudio(boolean foregroundPlaying, boolean sound) {
        ForegroundAudio audio=foregroundAudio;
        if(audio==null && foregroundPlaying && sound) {
            synchronized(this) {
                if(foregroundAudio==null)try{foregroundAudio=new ForegroundAudio(this);}
                catch(RuntimeException|LinkageError unavailable){return;}
                audio=foregroundAudio;
            }
        }
        if(audio!=null)audio.pump.configure(foregroundPlaying && sound);
    }
    private LifeScheduler scheduler;
    // Process-owned cache worker starts on demand and releases its idle thread. Never caller-runs.
    private final ThreadPoolExecutor previewWrites = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "pet-preview-publisher");
                thread.setDaemon(true);
                return thread;
            });
    private CommittedPreviewPublisher previewPublisher;
    private volatile boolean previewRecoveryBlocked = true;
    private long previewEpoch;

    @Override public void onCreate() {
        super.onCreate();
        timeVerifier=new TimeVerifier(this::clockSample,new AndroidTimeVerifier());
        // Read once at process startup: a later foreground marker belongs to this healthy process.
        setPreviewBlocked(new File(getFilesDir(), "pet.state.running").exists());
        previewPublisher = new CommittedPreviewPublisher(previewWrites,
                new PetPreviewStore(getFilesDir()),
                () -> TileService.getUpdater(this).requestUpdate(PetTileService.class));
    }

    /** Owner recovery gate: Tile callbacks must not create a Session to ask. */
    boolean previewAvailable() { return !previewRecoveryBlocked; }
    private synchronized void setPreviewBlocked(boolean blocked) {
        if(previewRecoveryBlocked!=blocked)previewEpoch++;
        previewRecoveryBlocked=blocked;
    }
    synchronized long previewToken() { return previewRecoveryBlocked?-1:previewEpoch; }
    // Check and publish under the same lock as invalidation. A request admitted before
    // recovery cannot escape after it, even if a recovery commit has since reopened.
    synchronized void completePreview(long token,Runnable available,Runnable neutral) {
        if(!previewRecoveryBlocked&&token==previewEpoch)available.run();else neutral.run();
    }

    // An optional integration cannot replace or prevent publication of a committed checkpoint.
    private volatile CommitObserver commitObserver = snapshot -> {};

    void setCommitObserver(CommitObserver observer) {
        if(observer==null)throw new IllegalArgumentException("Missing commit observer");
        commitObserver=observer;
    }

    synchronized EmulatorSession session() {
        if (session == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "tamaemu-worker");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            EmulatorSession.Worker worker = new EmulatorSession.Worker() {
                public void execute(Runnable task) { executor.execute(task); }
                public void later(Runnable task, int delay) { executor.schedule(task, delay, TimeUnit.MILLISECONDS); }
                public long nowMillis() { return SystemClock.elapsedRealtime(); }
            };
            EmulatorSession.Backend nativeBackend = new EmulatorSession.Backend() {
                public long open(String rom, String save) throws Exception { return NativeBridge.open(rom, save); }
                public void close(long handle) { NativeBridge.close(handle); }
                public int width(long handle) { return NativeBridge.width(handle); }
                public int height(long handle) { return NativeBridge.height(handle); }
                public void advance(long handle, int elapsed, int[] pixels) throws Exception { NativeBridge.advance(handle, elapsed, pixels); }
                public void headless(long handle,int elapsed) throws Exception { NativeBridge.advanceHeadless(handle,elapsed); }
                public long[] readLife(long handle) { return NativeBridge.readLife(handle); }
                public void writeLife(long handle,long[] candidate) { NativeBridge.writeLife(handle,candidate); }
                public byte[] committedPayloadHash(long handle) { return NativeBridge.committedPayloadHash(handle); }
                public void button(long handle, int button, boolean down) { NativeBridge.setButton(handle, button, down); }
                public boolean save(long handle) throws Exception { return NativeBridge.save(handle); }
            };
            nativeBackend=new AudioBackend(nativeBackend,new AudioBackend.NativeAudio() {
                public void enabled(long handle,boolean enabled){NativeBridge.setAudioEnabled(handle,enabled);}
                public short[] drain(long handle,int max){return NativeBridge.drainAudio(handle,max);}
            },()->foregroundAudio==null?null:foregroundAudio.pump,SystemClock::elapsedRealtime);
            Handler main = new Handler(Looper.getMainLooper());
            Clock clock = this::clockSample;
            session = new EmulatorSession(nativeBackend, worker, main::post,
                    new File(getFilesDir(), "firmware.bin").getAbsolutePath(),
                    new File(getFilesDir(), "pet.state").getAbsolutePath(),clock,
                    new LifeOwner.InputObserver() {
                        public void inputStats(LifeOwner.InputStats s,long inputId,int button,String reason,long guestProgress) {
                            if(Log.isLoggable("TamaemuInput",Log.DEBUG))
                                Log.d("TamaemuInput","inputId="+inputId+" button="+button+" reason="+reason
                                        +" guestProgress="+guestProgress+" ownerDownAttempts="+s.ownerDownAttempts
                                        +" admissionRefused="+s.admissionRefused+" admitted="+s.admitted
                                        +" deliveryRefusedNativeHeld="+s.deliveryRefusedNativeHeld
                                        +" deliveredNativeDowns="+s.deliveredNativeDowns
                                        +" canceledBeforeDelivery="+s.canceledBeforeDelivery
                                        +" pending="+s.pending+" heldMask="+s.heldMask);
                        }
                        public void recoveryRequired() {
                            setPreviewBlocked(true);
                            previewPublisher.refreshRequired();
                        }
                        public void committed(CommitSnapshot snapshot) {
                            // Only the authoritative owner reaches this observer after successful save.
                            // Jobs cannot acknowledge a stale foreground marker; the owner blocks them.
                            setPreviewBlocked(false);
                            timeOpportunity(null);
                            try { scheduler.reconcile(snapshot.record); }
                            catch (RuntimeException | LinkageError unavailable) { /* Cache work is independent. */ }
                            try { previewPublisher.committed(snapshot); }
                            catch (RuntimeException | LinkageError unavailable) { /* Pet durability already succeeded. */ }
                            try { commitObserver.committed(snapshot); }
                            catch (RuntimeException | LinkageError unavailable) { /* Optional observers are best effort. */ }
                        }
                    });
            scheduler=new LifeScheduler(new LifeScheduler.Port() {
                public boolean schedule() {
                    JobScheduler jobs=(JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE);
                    if(jobs==null)return false;
                    JobInfo info=new JobInfo.Builder(PetAgingJobService.JOB_ID,
                            new ComponentName(TamaemuApplication.this,PetAgingJobService.class))
                            .setPeriodic(TimeUnit.MINUTES.toMillis(15)).setPersisted(true).build();
                    // Replacing this ID also stops a running job and resets its periodic window.
                    // Inspect the platform on every reconciliation so process restarts adopt the
                    // persisted registration, while missing/changed jobs and failed calls retry.
                    JobInfo pending=jobs.getPendingJob(PetAgingJobService.JOB_ID);
                    // API26 equality distinguishes parcelled from unpacked empty bundles. Reading
                    // emptiness normalizes Binder-returned data and rejects any stale job extras.
                    if(pending!=null&&pending.getExtras().isEmpty()
                            &&pending.getTransientExtras().isEmpty()&&info.equals(pending))return true;
                    return jobs.schedule(info)==JobScheduler.RESULT_SUCCESS;
                }
                public void cancel() {
                    JobScheduler jobs=(JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE);
                    if(jobs!=null)jobs.cancel(PetAgingJobService.JOB_ID);
                }
            },session::schedulerStatus);
            session.reconcileScheduler(scheduler);
        }
        timeOpportunity(null);
        return session;
    }
}
