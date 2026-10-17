package io.github.projectcetacean.tamaemu;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowJobScheduler.ShadowJobSchedulerImpl;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowJobService;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

/** Production Application adapter and Android JobInfo/JobScheduler registration, without JNI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36}, application = TamaemuApplication.class,
        shadows = LifeSchedulerAndroidTest.CountingJobs.class)
public class LifeSchedulerAndroidTest {
    private TamaemuApplication app;
    private JobScheduler jobs;
    private CountingJobs platform;
    private LifeScheduler scheduler;
    private final List<ExecutorService> executors = new ArrayList<>();
    private final List<Boolean> failures = new ArrayList<>();

    @Before public void setup() throws Exception {
        app = (TamaemuApplication) RuntimeEnvironment.getApplication();
        jobs = (JobScheduler) app.getSystemService(Application.JOB_SCHEDULER_SERVICE);
        platform = org.robolectric.shadow.api.Shadow.extract(jobs);
        jobs.cancelAll();
        scheduler = scheduler(app);
    }
    @After public void close() throws Exception {
        for (ExecutorService executor : executors) executor.shutdownNow();
    }

    // Exercise the real Service -> LifeJobs -> Session -> owner path, not its constant.
    @Test public void agingServiceContinuesPastTenSecondsAndStopsAtSixtyWithExactDebt() throws Exception {
        LifeSessionTest.ReplayFixture f = new LifeSessionTest.ReplayFixture(10000000, Runnable::run);
        ReflectionHelpers.setField(app, "session", f.session);
        PetAgingJobService service = Robolectric.buildService(PetAgingJobService.class).create().get();
        JobParameters parameters = org.robolectric.shadow.api.Shadow.newInstanceOf(JobParameters.class);
        ShadowJobService shadow = org.robolectric.shadow.api.Shadow.extract(service);
        try {
            assertTrue(service.onStartJob(parameters));
            f.worker.through(10001);
            assertTrue("The service must continue useful replay beyond its former ten-second allowance", f.backend.progress > 1000000);
            assertFalse(shadow.getIsJobFinished());
            for (long boundary = 20000; boundary <= 60000; boundary += 10000) f.worker.through(boundary);
            assertEquals(6000000, f.backend.diskProgress);
            assertEquals(4059999, f.backend.disk.debtMs);
            assertEquals(59999, f.backend.disk.elapsedMs);
            assertEquals(1, f.backend.closes);
            assertTrue(shadow.getIsJobFinished());
            assertFalse(shadow.getIsRescheduleNeeded());
            assertFalse(service.onStopJob(parameters));
            f.worker.through(90000);
            assertEquals("Finished work cannot be restarted by stale cancellation", 6000000, f.backend.progress);
            assertEquals(1, f.backend.closes);
        } finally { service.onDestroy(); }
    }

    @Test public void agingServiceStopCancelsBeforeAnotherNativeSliceAndNeverFinishesLate() throws Exception {
        LifeSessionTest.ReplayFixture f = new LifeSessionTest.ReplayFixture(10000000, Runnable::run);
        ReflectionHelpers.setField(app, "session", f.session);
        PetAgingJobService service = Robolectric.buildService(PetAgingJobService.class).create().get();
        JobParameters parameters = org.robolectric.shadow.api.Shadow.newInstanceOf(JobParameters.class);
        ShadowJobService shadow = org.robolectric.shadow.api.Shadow.extract(service);
        try {
            assertTrue(service.onStartJob(parameters));
            f.worker.through(499);
            assertEquals(50000, f.backend.progress);
            assertFalse(service.onStopJob(parameters));
            f.worker.through(20000);
            assertEquals(50000, f.backend.diskProgress);
            assertEquals(9950499, f.backend.disk.debtMs);
            assertEquals(1, f.backend.closes);
            assertFalse("Stopped jobs must not call jobFinished afterward", shadow.getIsJobFinished());
        } finally { service.onDestroy(); }
    }

    // Unconditional schedule replaces an active periodic job and resets its platform cadence.
    @Test public void repeatedCommittedNormalRecordsPreservePlatformRegistration() throws Exception {
        scheduler.reconcile(record(0, 0, 1));
        JobInfo first = platform.registered(1413565761);
        assertNotNull(first);
        assertTrue(first.isPeriodic());
        assertTrue(first.isPersisted());
        assertEquals(900000L, first.getIntervalMillis());
        assertEquals(new ComponentName(app, PetAgingJobService.class), first.getService());
        for (int i = 2; i <= 100; i++) {
            ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
            scheduler.reconcile(record(0, 0, i));
        }
        assertEquals("Ordinary commits must not replace or restart the periodic job", 1, platform.schedules);
        assertSame("Platform registration, hence its existing window, survives commits", first,
                platform.registered(1413565761));
        assertFalse(failures.get(failures.size()-1));
    }

    // A process-local remembered flag cannot preserve a persisted platform job across a new owner.
    @Test public void recreatedApplicationAdoptsExistingRegistrationWithoutReset() throws Exception {
        scheduler.reconcile(record(0, 0, 1));
        JobInfo first = platform.registered(1413565761);
        TamaemuApplication recreated = new TamaemuApplication();
        ReflectionHelpers.callInstanceMethod(recreated, "attachBaseContext",
                ClassParameter.from(android.content.Context.class, app.getBaseContext()));
        LifeScheduler restarted = scheduler(recreated);
        restarted.reconcile(record(0, 0, 2));
        assertEquals("New process adapter must inspect the existing platform job", 1, platform.schedules);
        assertSame(first, platform.registered(1413565761));
    }

    // A stale success flag must not hide a platform cancellation or a force-stop removal.
    @Test public void missingRegistrationIsRepairedOnNextAuthoritativeRecord() {
        scheduler.reconcile(record(0, 0, 1));
        JobInfo first = platform.registered(1413565761);
        jobs.cancel(1413565761);
        scheduler.reconcile(record(0, 0, 2));
        assertEquals(2, platform.schedules);
        assertNotNull(platform.registered(1413565761));
        assertNotSame(first, platform.registered(1413565761));
        scheduler.reconcile(record(0, 0, 3));
        assertEquals(2, platform.schedules);
    }

    // Matching only the ID must not accept stale service, interval, persistence or constraints.
    @Test public void changedRegistrationIsReplacedOnceAndThenPreserved() {
        JobInfo[] stale = {
            new JobInfo.Builder(1413565761, new ComponentName(app, PetTileService.class)).setPeriodic(900000L).setPersisted(true).build(),
            builder().setPeriodic(1800000L).setPersisted(true).build(),
            builder().setPeriodic(900000L).build(),
            builder().setPeriodic(900000L).setPersisted(true).setRequiresCharging(true).build(),
            builder().setMinimumLatency(1000).setPersisted(true).build(),
            builder().setPeriodic(900000L).setPersisted(true).setExtras(nonemptyExtras()).build()
        };
        for (int i = 0; i < stale.length; i++) {
            jobs.schedule(stale[i]);
            int before = platform.schedules;
            scheduler.reconcile(record(0, 0, i + 1));
            JobInfo fixed = platform.registered(1413565761);
            assertEquals(before + 1, platform.schedules);
            assertNotSame(stale[i], fixed);
            assertTrue(fixed.isPeriodic());
            assertEquals(900000L, fixed.getIntervalMillis());
            assertTrue(fixed.isPersisted());
            assertFalse(fixed.isRequireCharging());
            scheduler.reconcile(record(0, 0, i + 10));
            assertEquals(before + 1, platform.schedules);
            assertSame(fixed, platform.registered(1413565761));
        }
    }

    // A failed registration must stay retryable and failure status must clear on success.
    @Test public void failedScheduleRetriesWithoutPretendingJobExists() {
        platform.failNext = true;
        scheduler.reconcile(record(0, 0, 1));
        assertEquals(1, platform.schedules);
        assertNull(platform.registered(1413565761));
        assertTrue(failures.get(failures.size()-1));
        scheduler.reconcile(record(0, 0, 2));
        assertEquals(2, platform.schedules);
        assertNotNull(platform.registered(1413565761));
        assertFalse(failures.get(failures.size()-1));
        scheduler.reconcile(record(0, 0, 3));
        assertEquals(2, platform.schedules);
    }

    // Protected pauses only future away time: owed debt retains the same job until settled.
    @Test public void protectedDebtRetainsJobThenSettledModeCancelsOnlyAging() {
        JobInfo unrelated = new JobInfo.Builder(7, new ComponentName(app, PetTileService.class)).setMinimumLatency(1000).build();
        jobs.schedule(unrelated);
        scheduler.reconcile(record(0, 0, 1));
        JobInfo aging = platform.registered(1413565761);
        int before = platform.schedules;
        scheduler.reconcile(record(1, 1000, 2));
        assertEquals(before, platform.schedules);
        assertSame(aging, platform.registered(1413565761));
        scheduler.reconcile(record(1, 0, 3));
        assertNull(platform.registered(1413565761));
        assertSame(unrelated, platform.registered(7));
        scheduler.reconcile(record(0, 0, 4));
        assertEquals(before + 1, platform.schedules);
        scheduler.reconcile(LifeRecord.from(new long[8]));
        assertNull(platform.registered(1413565761));
        assertSame(unrelated, platform.registered(7));
    }

    private static android.os.PersistableBundle nonemptyExtras() {
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putLong("old-policy", 1);
        return extras;
    }
    private JobInfo.Builder builder() {
        return new JobInfo.Builder(1413565761, new ComponentName(app, PetAgingJobService.class));
    }
    private static LifeRecord record(int mode, long debt, long generation) {
        return LifeRecord.from(new long[] {1, 10000, 100, 1, debt, mode, generation, 0});
    }
    private LifeScheduler scheduler(TamaemuApplication application) throws Exception {
        EmulatorSession session = application.session();
        Object owner = get(session, "life");
        EmulatorSession.Worker worker = (EmulatorSession.Worker) get(owner, "worker");
        CountDownLatch idle = new CountDownLatch(1);
        worker.execute(idle::countDown);
        assertTrue(idle.await(2, TimeUnit.SECONDS));
        for (Field field : worker.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(worker);
            if (value instanceof ExecutorService) {
                executors.add((ExecutorService) value);
                ((ExecutorService) value).shutdownNow();
            }
        }
        LifeScheduler result = (LifeScheduler) get(application, "scheduler");
        Field status = LifeScheduler.class.getDeclaredField("status");
        status.setAccessible(true);
        status.set(result, (LifeScheduler.Status) (text, failed) -> failures.add(failed));
        return result;
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Delegates to Robolectric's real registration store; only observes calls and one failed IPC. */
    @Implements(className = "android.app.JobSchedulerImpl", isInAndroidSdk = false)
    public static class CountingJobs extends ShadowJobSchedulerImpl {
        int schedules;
        boolean failNext;
        JobInfo registered(int id) { return super.getPendingJob(id); }
        @org.robolectric.annotation.Implementation
        @Override public JobInfo getPendingJob(int id) {
            JobInfo stored = super.getPendingJob(id);
            if (stored == null) return null;
            // JobScheduler is a Binder service: clients receive a parcelled copy, not this object.
            android.os.Parcel parcel = android.os.Parcel.obtain();
            try {
                stored.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                return JobInfo.CREATOR.createFromParcel(parcel);
            } finally { parcel.recycle(); }
        }
        @org.robolectric.annotation.Implementation
        @Override public int schedule(JobInfo info) {
            schedules++;
            if (failNext) { failNext = false; return JobScheduler.RESULT_FAILURE; }
            return super.schedule(info);
        }
    }
}
