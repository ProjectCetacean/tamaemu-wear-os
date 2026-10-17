package io.github.projectcetacean.tamaemu;

import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.proto.RequestProto;
import androidx.wear.protolayout.ResourceBuilders;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real Application, Session observer, cache codec and Tile callbacks. No native-save fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36}, application = TamaemuApplication.class)
public class AppPreviewAndroidTest {
    private TamaemuApplication app;
    private PetTileService service;
    private ExecutorService sessionExecutor;
    private File dir;
    private final QueuedExecutor publications = new QueuedExecutor();
    private int refreshes;

    @Before public void setup() throws Exception {
        app = (TamaemuApplication) RuntimeEnvironment.getApplication();
        dir = app.getFilesDir();
        for (File file : dir.listFiles()) {
            if (file.getName().startsWith("pet") || file.getName().startsWith("tile-")) {
                assertTrue(file.delete());
            }
        }
    }
    @After public void close() throws Exception {
        if (service != null) service.onDestroy();
        if (sessionExecutor != null) sessionExecutor.shutdownNow();
        Object publisher = publisher();
        if (publisher != null) {
            Object executor = get(publisher, "executor");
            if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdownNow();
        }
    }

    // Calling session() from startup or either Tile callback would initialize the owner/backend.
    @Test public void coldApplicationAndTileCallbacksLeaveSessionUninitialized() throws Exception {
        assertNull(get(app, "session"));
        service = Robolectric.buildService(PetTileService.class).create().get();
        assertEquals("neutral", service.onTileRequest(tileRequest()).get(2, TimeUnit.SECONDS).getResourcesVersion());
        assertTrue(service.onTileResourcesRequest(resources("unknown")).get(2, TimeUnit.SECONDS).getIdToImageMapping().isEmpty());
        assertNull("Tile-only work must not construct a Session", get(app, "session"));
        assertNull("Tile-only work must not construct audio",get(app,"foregroundAudio"));
        assertFalse(new File(dir, "pet.state.running").exists());
        assertFalse(new File(dir, "pet.state").exists());
    }

    // A stale marker must bypass all queued cache work, including previously pinned resources.
    @Test public void staleStartupMarkerReturnsNeutralBeforeCacheWorkAndPreservesFiles() throws Exception {
        File marker = staleStartup();
        File cache = new File(dir, "pet-preview.cache");
        Files.write(cache.toPath(), new byte[] {9, 8, 7});
        byte[] cacheBefore = Files.readAllBytes(cache.toPath());
        String version = new PetPreviewStore(dir).pinImage(preview());
        service = Robolectric.buildService(PetTileService.class).create().get();
        ThreadPoolExecutor reads = (ThreadPoolExecutor) get(service, "reads");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        reads.execute(() -> { started.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        try {
            com.google.common.util.concurrent.ListenableFuture<TileBuilders.Tile> pending = service.onTileRequest(tileRequest());
            assertTrue("Recovery gate must return before the blocked cache worker", pending.isDone());
            TileBuilders.Tile tile = pending.get(1, TimeUnit.SECONDS);
            assertEquals("neutral", tile.getResourcesVersion());
            assertTrue(tile.toProto().toString().contains(service.getString(R.string.tile_neutral)));
            assertTrue(service.onTileResourcesRequest(resources(version)).get(1, TimeUnit.SECONDS).getIdToImageMapping().isEmpty());
            assertTrue(reads.getQueue().isEmpty());
            assertNull(get(app, "session"));
            assertTrue(marker.isFile());
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(marker.toPath()));
            assertArrayEquals(cacheBefore, Files.readAllBytes(cache.toPath()));
        } finally { release.countDown(); }
    }

    // Rechecking marker existence per request would hide healthy process-owned resources.
    @Test public void markerCreatedAfterStartupDoesNotHideHealthyPinnedResources() throws Exception {
        Files.write(new File(dir, "pet.state.running").toPath(), new byte[] {1});
        String version = new PetPreviewStore(dir).pinImage(preview());
        service = Robolectric.buildService(PetTileService.class).create().get();
        ResourceBuilders.Resources result = service.onTileResourcesRequest(resources(version)).get(2, TimeUnit.SECONDS);
        assertEquals(1, result.getIdToImageMapping().size());
        assertNull(get(app, "session"));
    }

    // Removing the real Session observer, publisher dispatch or recovery release breaks this path.
    @Test public void successfulRecoveryCommitPublishesExactSnapshotAsynchronouslyAndReleasesGate() throws Exception {
        File marker = staleStartup();
        FakeBackend backend = prepareActualSession();
        app.setCommitObserver(snapshot -> { throw new IllegalStateException("Optional observer unavailable"); });
        recover();
        assertEquals(1, backend.saves);
        assertFalse("Publication must not run on the persistence worker", new File(dir, "pet-preview.cache").exists());
        assertEquals(0, refreshes);
        assertEquals(1, publications.tasks.size());
        publications.drain();
        PetPreviewSnapshot cached = PetPreviewStore.decode(Files.readAllBytes(new File(dir, "pet-preview.cache").toPath()));
        assertEquals(41, cached.generation);
        assertEquals(10000, cached.committedUtcMillis);
        assertEquals(PetPreviewSnapshot.Mode.PROTECTED, cached.mode);
        assertTrue(cached.awayPaused);
        assertEquals(0, cached.debtMillis);
        assertEquals(1, cached.clockQuality);
        assertArrayEquals(hash(), cached.savePayloadDigest());
        assertEquals(0xff123456, cached.pixelsArgb()[0]);
        assertEquals(1, refreshes);
        assertTrue("A healthy foreground owns its marker", marker.isFile());
        // Gate release is observable using pinned pixels, independently of the native-envelope reader.
        String version = new PetPreviewStore(dir).pinImage(cached);
        service = Robolectric.buildService(PetTileService.class).create().get();
        assertEquals(1, service.onTileResourcesRequest(resources(version)).get(2, TimeUnit.SECONDS).getIdToImageMapping().size());
    }

    // Cache I/O failure must neither undo the pet checkpoint nor request a Tile refresh.
    @Test public void failedDerivedCacheKeepsSuccessfulCheckpointAndSkipsRefresh() throws Exception {
        staleStartup();
        FakeBackend backend = prepareActualSession();
        File cache = new File(dir, "pet-preview.cache");
        assertTrue(cache.mkdir());
        recover();
        publications.drain();
        assertEquals(1, backend.saves);
        LifeOwner owner = (LifeOwner) get(app.session(), "life");
        assertEquals(41, ((LifeRecord) get(owner, "committed")).generation);
        assertNull(get(owner, "candidate"));
        assertEquals(LifeStatus.OwnerState.PLAYING, get(owner, "state"));
        assertTrue(cache.isDirectory());
        assertEquals(0, refreshes);
        assertTrue(new File(dir, "pet.state.running").isFile());
    }

    // A failed authoritative checkpoint must not acknowledge recovery or publish old pixels.
    @Test public void failedRecoverySaveLeavesGateClosedAndDoesNotPublish() throws Exception {
        staleStartup();
        FakeBackend backend = prepareActualSession();
        backend.saveSucceeds = false;
        recover();
        assertEquals(1, backend.saves);
        assertTrue(publications.tasks.isEmpty());
        String version = new PetPreviewStore(dir).pinImage(preview());
        service = Robolectric.buildService(PetTileService.class).create().get();
        assertTrue(service.onTileResourcesRequest(resources(version)).get(1, TimeUnit.SECONDS).getIdToImageMapping().isEmpty());
    }

    // Removing owner recovery notification leaves the healthy-process Tile gate incorrectly open.
    @Test public void liveRecoveryInvalidatesAdmissionsWithoutAnActivityListener() throws Exception {
        staleStartup();FakeBackend backend=prepareActualSession();recover();
        long admitted=app.previewToken();assertTrue(admitted>=0);
        set(get(app.session(),"life"),"listener",null);
        backend.failInput=true;app.session().button(0,true);
        ((ManualWorker)get(get(app.session(),"life"),"worker")).drain();
        assertFalse(app.previewAvailable());assertEquals(-1,app.previewToken());
        assertEquals("Recovery requests immediate neutral refresh",1,refreshes);
        publications.drain();assertFalse("Late publication cannot reopen recovery",app.previewAvailable());
        final int[] permitted={0},neutral={0};
        app.completePreview(admitted,()->permitted[0]++,()->neutral[0]++);
        assertEquals(0,permitted[0]);assertEquals(1,neutral[0]);
        backend.failInput=false;backend.saveSucceeds=false;recover();assertFalse(app.previewAvailable());
        backend.saveSucceeds=true;recover();assertTrue(app.previewAvailable());
        app.completePreview(admitted,()->permitted[0]++,()->neutral[0]++);
        assertEquals("A pre-failure admission stays invalid after recovery",0,permitted[0]);
        assertEquals(2,neutral[0]);
        app.completePreview(app.previewToken(),()->permitted[0]++,()->neutral[0]++);
        assertEquals(1,permitted[0]);assertEquals(2,neutral[0]);
        assertTrue(new File(dir,"pet.state").isFile());assertTrue(new File(dir,"pet-preview.cache").isFile());
    }

    @Test public void liveNativeFailureClosesGateUntilSuccessfulAuthorizedRecoveryCommit() throws Exception {
        staleStartup();FakeBackend backend=prepareActualSession();recover();
        assertTrue(app.previewAvailable());
        assertEquals(1,publications.tasks.size()); // Leave a previously authorized publication queued.
        String version=new PetPreviewStore(dir).pinImage(preview());
        service=Robolectric.buildService(PetTileService.class).create().get();
        set(get(app.session(),"life"),"listener",null);
        backend.failInput=true;
        app.session().button(0,true);
        ((ManualWorker)get(get(app.session(),"life"),"worker")).drain();
        assertEquals(LifeStatus.OwnerState.RECOVERY_REQUIRED,get(get(app.session(),"life"),"state"));
        assertFalse("Live native recovery closes availability without an Activity listener",app.previewAvailable());
        publications.drain();
        assertTrue("Recovery requests a refresh even before queued publication completes",refreshes>0);
        assertFalse("Old publication cannot release the recovery gate",app.previewAvailable());
        assertEquals("neutral",service.onTileRequest(tileRequest()).get(1,TimeUnit.SECONDS).getResourcesVersion());
        assertTrue(service.onTileResourcesRequest(resources(version)).get(1,TimeUnit.SECONDS).getIdToImageMapping().isEmpty());
        assertTrue(new File(dir,"pet.state").isFile());
        assertTrue(new File(dir,"pet-preview.cache").isFile());
        backend.failInput=false;backend.saveSucceeds=false;recover();
        assertFalse("Failed recovery save keeps Tile blocked",app.previewAvailable());
        assertEquals("neutral",service.onTileRequest(tileRequest()).get(1,TimeUnit.SECONDS).getResourcesVersion());
        backend.saveSucceeds=true;recover();
        assertTrue("Only successful recovery commit reopens Tile availability",app.previewAvailable());
        assertEquals(1,service.onTileResourcesRequest(resources(version)).get(2,TimeUnit.SECONDS).getIdToImageMapping().size());
    }

    // Work admitted before failure must not publish old resources, even after recovery reopens.
    @Test public void queuedTileResourcesCannotEscapeRecoveryTransition() throws Exception {
        staleStartup();FakeBackend backend=prepareActualSession();recover();publications.drain();
        String version=new PetPreviewStore(dir).pinImage(preview());
        service=Robolectric.buildService(PetTileService.class).create().get();
        ThreadPoolExecutor reads=(ThreadPoolExecutor)get(service,"reads");
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        reads.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(started.await(1,TimeUnit.SECONDS));
        try {
            com.google.common.util.concurrent.ListenableFuture<ResourceBuilders.Resources> queued=service.onTileResourcesRequest(resources(version));
            assertFalse(queued.isDone());backend.failInput=true;app.session().button(0,true);
            ((ManualWorker)get(get(app.session(),"life"),"worker")).drain();
            backend.failInput=false;recover();assertTrue(app.previewAvailable());
            release.countDown();
            assertTrue("A pre-recovery request stays neutral after gate reopens",queued.get(2,TimeUnit.SECONDS).getIdToImageMapping().isEmpty());
            assertEquals(1,service.onTileResourcesRequest(resources(version)).get(2,TimeUnit.SECONDS).getIdToImageMapping().size());
        }finally{release.countDown();}
    }

    private File staleStartup() throws Exception {
        File marker = new File(dir, "pet.state.running");
        Files.write(marker.toPath(), new byte[] {1});
        // Simulate process-start detection before any Session exists. No pet/cache is removed.
        app.onCreate();
        return marker;
    }
    private FakeBackend prepareActualSession() throws Exception {
        Object publisher = publisher();
        if (publisher != null) {
            set(publisher, "executor", publications);
            set(publisher, "refresh", (CommittedPreviewPublisher.Refresh) () -> refreshes++);
        }
        EmulatorSession session = app.session();
        LifeOwner owner = (LifeOwner) get(session, "life");
        EmulatorSession.Worker actualWorker = (EmulatorSession.Worker) get(owner, "worker");
        CountDownLatch reconciled = new CountDownLatch(1);
        actualWorker.execute(reconciled::countDown);
        assertTrue(reconciled.await(2, TimeUnit.SECONDS));
        for (Field field : actualWorker.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(actualWorker);
            if (value instanceof ExecutorService) sessionExecutor = (ExecutorService) value;
        }
        assertNotNull(sessionExecutor);
        sessionExecutor.shutdownNow();
        FakeBackend backend = new FakeBackend();
        set(owner, "backend", backend);
        set(owner, "worker", new ManualWorker());
        set(owner, "clock", (Clock) () -> new ClockSample(10000, 100, 1));
        // Recovery needs a prior file; opaque bytes are never used as positive native-envelope evidence.
        Files.write(new File(dir, "pet.state").toPath(), new byte[] {4, 2});
        return backend;
    }
    private void recover() throws Exception {
        EmulatorSession session = app.session();
        session.retry(new EmulatorSession.Listener() {
            public void state(EmulatorSession.State state) {}
            public void frame(int width, int height, int[] pixels) {}
        });
        ((ManualWorker) get(get(session, "life"), "worker")).drain();
    }
    private Object publisher() throws Exception {
        try { return get(app, "previewPublisher"); } catch (NoSuchFieldException absentBeforeHook) { return null; }
    }
    private static byte[] hash() { byte[] hash = new byte[32]; Arrays.fill(hash, (byte) 0x5a); return hash; }
    private static PetPreviewSnapshot preview() {
        int[] pixels = new int[16384]; Arrays.fill(pixels, 0xff123456);
        return new PetPreviewSnapshot(hash(), 40, 10000, PetPreviewSnapshot.Mode.PROTECTED, true, 0, 1, pixels);
    }
    private static RequestBuilders.TileRequest tileRequest() { return RequestBuilders.TileRequest.fromProto(RequestProto.TileRequest.getDefaultInstance()); }
    private static RequestBuilders.ResourcesRequest resources(String version) { return RequestBuilders.ResourcesRequest.fromProto(RequestProto.ResourcesRequest.newBuilder().setVersion(version).build()); }
    private static Object get(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
    private static final class QueuedExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void drain() { for (int i = 0; !tasks.isEmpty(); i++) { assertTrue("Bounded publication", i < 30); tasks.remove().run(); } }
    }
    private static final class ManualWorker implements EmulatorSession.Worker {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        public void later(Runnable task, int delay) { if (delay == 0) tasks.add(task); }
        public long nowMillis() { return 100; }
        void drain() { for (int i = 0; !tasks.isEmpty(); i++) { assertTrue("Bounded owner work", i < 40); tasks.remove().run(); } }
    }
    private static final class FakeBackend implements EmulatorSession.Backend {
        int saves;
        boolean saveSucceeds = true, failInput;
        long[] life = {1, 10000, 100, 1, 0, 1, 40, 1};
        public long open(String rom, String save) { return 1; }
        public void close(long handle) {}
        public int width(long handle) { return 128; }
        public int height(long handle) { return 128; }
        public void advance(long handle, int millis, int[] pixels) { Arrays.fill(pixels, 0xff123456); }
        public void button(long handle, int button, boolean down) { if(failInput)throw new IllegalStateException("native input failure"); }
        public boolean save(long handle) { saves++; return saveSucceeds; }
        public long[] readLife(long handle) { return life.clone(); }
        public void writeLife(long handle, long[] candidate) { life = candidate.clone(); }
        public byte[] committedPayloadHash(long handle) { return hash(); }
    }
}
