package io.github.projectcetacean.tamaemu;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Plain-JVM lifecycle regressions; no Android runtime or firmware required. */
public final class EmulatorSessionTest {
    static final class Task implements Comparable<Task> {
        final long time, order;
        final Runnable action;
        Task(long time, long order, Runnable action) { this.time = time; this.order = order; this.action = action; }
        public int compareTo(Task other) {
            int byTime = Long.compare(time, other.time);
            return byTime != 0 ? byTime : Long.compare(order, other.order);
        }
    }

    static final class Worker implements EmulatorSession.Worker {
        final PriorityQueue<Task> queue = new PriorityQueue<>();
        long time, sequence;
        public void execute(Runnable task) { later(task, 0); }
        public void later(Runnable task, int delay) { queue.add(new Task(time + delay, sequence++, task)); }
        public long nowMillis() { return time; }
        void through(long until) {
            int guard = 0;
            while (!queue.isEmpty() && queue.peek().time <= until) {
                if (++guard > 10000) throw new AssertionError("Unbounded work");
                Task task = queue.remove(); time = task.time; task.action.run();
            }
            time = until;
        }
    }

    static class Backend implements EmulatorSession.Backend {
        final List<String> events = new ArrayList<>();
        int opens, closes, advances, saves;
        boolean saveOkay = true, failOpen, failAdvance;
        int pressed, sampled;
        File path;
        public long open(String rom, String save) throws IOException {
            opens++; events.add("open");
            if (failOpen) throw new IOException("bad save");
            path = new File(save);
            return 7;
        }
        public void close(long handle) { check(handle == 7, "close handle"); closes++; events.add("close"); }
        public int width(long handle) { return 128; }
        public int height(long handle) { return 128; }
        public void advance(long handle, int elapsed, int[] pixels) throws IOException {
            if (failAdvance) throw new IOException("native failure");
            check(elapsed >= 0 && elapsed <= 100, "bounded elapsed");
            check(pixels.length == 128 * 128, "exact frame size");
            advances++; sampled |= pressed; events.add("advance");
        }
        public void button(long handle, int button, boolean down) {
            check(handle == 7, "button handle");
            if (down) pressed |= 1 << button; else pressed &= ~(1 << button);
            events.add("button" + button + ":" + down);
        }
        public boolean save(long handle) {
            check(pressed == 0, "release every key before save");
            saves++; events.add("save");
            if (saveOkay && path != null && !path.exists()) {
                try { check(path.createNewFile(), "create model checkpoint"); }
                catch (IOException failure) { throw new AssertionError(failure); }
            }
            return saveOkay;
        }
    }

    static class Listener implements EmulatorSession.Listener {
        final List<EmulatorSession.State> states = new ArrayList<>();
        int frames;
        public void state(EmulatorSession.State state) { states.add(state); }
        public void frame(int w, int h, int[] pixels) { frames++; }
    }

    static final class Fixture {
        final Backend backend = new Backend();
        final Worker worker = new Worker();
        final ArrayDeque<Runnable> ui = new ArrayDeque<>();
        final Listener listener = new Listener();
        final File directory = tempDirectory();
        final EmulatorSession session = new EmulatorSession(backend, worker, ui::add,
                new File(directory, "missing-rom").getPath(), new File(directory, "pet.state").getPath());
        void flushUi() { while (!ui.isEmpty()) ui.remove().run(); worker.through(worker.time); }
        void start() { session.resume(listener); worker.through(worker.time); flushUi(); }
    }

    public static void main(String[] args) throws Exception {
        pauseStopsTicksAndSaves();
        failedSaveKeepsLivePet();
        rapidLifecycleDropsStaleCallbacks();
        quickTapAndComboReachEmulation();
        checkedNativeErrorsStopSafely();
        foregroundCheckpointsAndRecoveryWarning();
        stoppedStateSurvivesDiscardedWorkerQueue();
        stopTimeoutNeverClaimsDurability();
        System.out.println("EmulatorSessionTest: 8 lifecycle regressions passed");
    }

    static void pauseStopsTicksAndSaves() {
        Fixture f = new Fixture(); f.start();
        f.session.button(0, true); f.worker.through(0);
        f.session.pause(); f.worker.through(0);
        int advances = f.backend.advances;
        f.worker.through(1000); f.flushUi();
        check(f.backend.advances == advances, "no hidden frames");
        check(f.backend.saves == 2 && f.backend.closes == 1, "initial checkpoint and pause save then close");
        check(f.backend.events.indexOf("save") < f.backend.events.indexOf("close"), "close only after save");
    }

    static void failedSaveKeepsLivePet() {
        Fixture f = new Fixture(); f.start(); f.backend.saveOkay = false;
        f.session.pause(); f.worker.through(0);
        check(f.backend.closes == 0, "retain failed-save handle");
        int frames = f.backend.advances;
        f.session.resume(f.listener); f.worker.through(0); f.flushUi();
        check(f.listener.states.contains(EmulatorSession.State.SAVE_FAILED), "visible save warning");
        check(f.backend.advances == frames && f.backend.opens == 1, "no silent stale reload");
        f.backend.saveOkay = true; f.session.resume(f.listener); f.worker.through(0); f.flushUi();
        check(f.backend.opens == 1 && f.backend.advances > frames, "retry same pet");
    }

    static void rapidLifecycleDropsStaleCallbacks() {
        Fixture f = new Fixture();
        f.session.resume(f.listener); f.worker.through(0);
        Listener fresh = new Listener();
        f.session.pause(); f.session.resume(fresh); f.worker.through(0); f.flushUi();
        check(f.listener.frames == 0 && f.listener.states.isEmpty(), "stale callbacks suppressed");
        check(fresh.frames == 1 && f.backend.opens == 2 && f.backend.closes == 1, "fresh lifecycle opens once");
        f.session.pause(); f.worker.through(0); f.worker.through(1000);
        check(f.backend.advances == 2, "old tick never resumes");
    }

    static void quickTapAndComboReachEmulation() {
        Fixture f = new Fixture(); f.start();
        f.session.button(0, true); f.session.button(1, true);
        f.session.button(0, false); f.session.button(1, false); f.worker.through(34);
        check(f.backend.sampled == 3, "A+B sampled even for a short tap");
        f.worker.through(41); check(f.backend.pressed == 0, "delayed key-up releases");
        f.session.button(0, true); f.session.button(0, false); f.worker.through(41);
        f.session.pause(); f.worker.through(41); f.worker.through(100);
        check(f.backend.closes == 1 && f.backend.pressed == 0, "pause cancels deferred release safely");
    }

    static void checkedNativeErrorsStopSafely() {
        Fixture open = new Fixture(); open.backend.failOpen = true; open.start();
        check(open.listener.states.contains(EmulatorSession.State.MISSING_FIRMWARE), "missing firmware instruction");
        check(open.backend.saves == 0 && open.backend.closes == 0, "failed open cannot overwrite save");
        Fixture frame = new Fixture(); frame.backend.failAdvance = true; frame.start();
        frame.worker.through(1000); frame.flushUi();
        check(frame.listener.states.contains(EmulatorSession.State.NATIVE_FAILED), "checked IOException reaches UI");
        check(frame.backend.saves == 1 && frame.backend.closes == 0, "retain native failure after initial checkpoint");
        frame.backend.failAdvance = false; frame.session.resume(frame.listener); frame.worker.through(1000); frame.flushUi();
        check(frame.backend.opens == 1 && frame.listener.frames == 1, "retry native error without reset");
    }

    static void foregroundCheckpointsAndRecoveryWarning() {
        Fixture f = new Fixture(); f.start();
        check(f.backend.saves == 1 && f.listener.frames == 1, "durable baseline before playable frame");
        f.worker.through(EmulatorSession.CHECKPOINT_MILLIS + 33);
        check(f.backend.saves == 2, "foreground periodic checkpoint");
        f.worker.queue.clear(); // Simulate process loss, including queued pause/save work.
        Backend restored = new Backend();
        restored.pressed = 3; // Native checkpoint can contain a held combination.
        Worker worker = new Worker(); Listener listener = new Listener();
        EmulatorSession recovered = new EmulatorSession(restored, worker, Runnable::run,
                new File(f.directory, "rom").getPath(), new File(f.directory, "pet.state").getPath());
        recovered.resume(listener); worker.through(0);
        check(listener.states.contains(EmulatorSession.State.RECOVERY_REQUIRED), "visible unconfirmed-stop warning");
        check(restored.opens == 0 && restored.advances == 0, "recovery requires acknowledgement");
        recovered.pause(); worker.through(0);
        recovered.resume(listener); worker.through(0);
        check(restored.opens == 0, "lifecycle resume cannot acknowledge recovery warning");
        recovered.retry(listener); worker.through(0);
        check(restored.opens == 1 && restored.pressed == 0, "reopen normalizes held inputs before checkpoint");

        File missingDir = tempDirectory();
        try { check(new File(missingDir, "pet.state.running").createNewFile(), "interrupted missing-save fixture"); }
        catch (IOException failure) { throw new AssertionError(failure); }
        Worker missingWorker = new Worker(); Backend missingBackend = new Backend(); Listener missingListener = new Listener();
        EmulatorSession missing = new EmulatorSession(missingBackend, missingWorker, Runnable::run,
                "rom", new File(missingDir, "pet.state").getPath());
        missing.resume(missingListener); missingWorker.through(0);
        missing.retry(missingListener); missingWorker.through(0);
        check(missingListener.states.contains(EmulatorSession.State.RECOVERY_MISSING_SAVE), "missing checkpoint is explicit");
        check(missingBackend.opens == 0 && !new File(missingDir, "pet.state").exists(),
                "explicit recovery retry cannot replace a missing checkpoint with a fresh pet");
    }

    static final class RealWorker implements EmulatorSession.Worker {
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        public void execute(Runnable task) { executor.execute(task); }
        public void later(Runnable task, int delay) { executor.schedule(task, delay, TimeUnit.MILLISECONDS); }
        public long nowMillis() { return System.nanoTime() / 1_000_000; }
        void discard() throws InterruptedException {
            executor.shutdownNow();
            check(executor.awaitTermination(1, TimeUnit.SECONDS), "worker terminates without pending work");
        }
    }

    static final class FileBackend extends Backend {
        long petId = 0x12345678L, progress;
        File saveFile;
        volatile CountDownLatch saveEntered, allowSave;
        @Override public long open(String rom, String save) throws IOException {
            saveFile = new File(save);
            if (saveFile.isFile()) {
                try (DataInputStream input = new DataInputStream(new FileInputStream(saveFile))) {
                    petId = input.readLong(); progress = input.readLong(); pressed = input.readInt();
                }
            }
            return super.open(rom, save);
        }
        @Override public void advance(long handle, int elapsed, int[] pixels) throws IOException {
            super.advance(handle, elapsed, pixels); progress += elapsed;
        }
        @Override public boolean save(long handle) {
            CountDownLatch gate = allowSave;
            if (gate != null) {
                saveEntered.countDown();
                try { gate.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
            if (!super.save(handle)) return false;
            try (FileOutputStream file = new FileOutputStream(saveFile);
                 DataOutputStream output = new DataOutputStream(file)) {
                output.writeLong(petId); output.writeLong(progress); output.writeInt(pressed);
                output.flush(); file.getFD().sync(); return true;
            } catch (IOException failure) { throw new AssertionError(failure); }
        }
    }

    static void stoppedStateSurvivesDiscardedWorkerQueue() throws Exception {
        File dir = tempDirectory(); File file = new File(dir, "pet.state");
        RealWorker worker = new RealWorker(); FileBackend backend = new FileBackend();
        CountDownLatch firstFrame = new CountDownLatch(1);
        EmulatorSession session = new EmulatorSession(backend, worker, Runnable::run, "rom", file.getPath());
        session.resume(new Listener() {
            @Override public void frame(int width, int height, int[] pixels) { firstFrame.countDown(); }
        });
        check(firstFrame.await(1, TimeUnit.SECONDS), "initial frame after initial disk save");
        CountDownLatch edited = new CountDownLatch(1);
        worker.execute(() -> {
            backend.progress = 12345;
            backend.saveEntered = new CountDownLatch(1);
            backend.allowSave = new CountDownLatch(1);
            edited.countDown();
        });
        check(edited.await(1, TimeUnit.SECONDS), "pet progresses after baseline");
        session.pause();
        check(backend.saveEntered.await(1, TimeUnit.SECONDS), "stop save is in flight");
        Thread releaseSave = new Thread(() -> {
            // Debug host serialization/fsync was measured at317ms; do not time out a350ms save.
            try { Thread.sleep(350); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            backend.allowSave.countDown();
        });
        releaseSave.start();
        EmulatorSession.StopResult result = session.stopAndAwait(EmulatorSession.STOP_BUDGET_MILLIS);
        worker.discard(); // No queued task is allowed to complete the save after stop.
        releaseSave.join();
        check(result == EmulatorSession.StopResult.SAVED, "stop waits for completed durable save");
        check(!new File(file + ".running").exists(), "successful stop clears incomplete-run marker");
        FileBackend restored = new FileBackend(); restored.open("rom", file.getPath());
        check(restored.petId == 0x12345678L && restored.progress == backend.progress && restored.progress >= 12345,
                "same pet and latest progress survive stopped-process queue discard");
        check(restored.pressed == 0, "stopped disk state has no pressed keys");
    }

    static void stopTimeoutNeverClaimsDurability() {
        Fixture f = new Fixture(); f.start();
        f.session.pause(); // Worker intentionally cannot run the queued save.
        long before = System.nanoTime();
        EmulatorSession.StopResult result = f.session.stopAndAwait(10);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        check(result == EmulatorSession.StopResult.TIMED_OUT, "queued save is not a completed save");
        check(elapsed < 200, "stop wait stays bounded and responsive");
        check(new File(f.directory, "pet.state.running").isFile(), "unclean marker survives timeout");
        f.worker.queue.clear();
    }

    static File tempDirectory() {
        try { return Files.createTempDirectory("tamaemu-ui-test-").toFile(); }
        catch (IOException failure) { throw new AssertionError(failure); }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
