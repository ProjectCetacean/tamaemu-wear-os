package io.github.projectcetacean.tamaemu;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;

/** Automatic lifecycle callbacks must never acknowledge an interrupted session. */
public final class RecoveryLifecycleTest {
    static final class Worker implements EmulatorSession.Worker {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        public void execute(Runnable task) { queue.add(task); }
        public void later(Runnable task, int delay) { /* No elapsed foreground frames in this probe. */ }
        public long nowMillis() { return 0; }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }

    static final class Backend implements EmulatorSession.Backend {
        int opens;
        public long open(String rom, String save) { opens++; return 1; }
        public void close(long handle) {}
        public int width(long handle) { return 128; }
        public int height(long handle) { return 128; }
        public void advance(long handle, int elapsed, int[] pixels) {}
        public void button(long handle, int button, boolean down) {}
        public boolean save(long handle) { return true; }
    }

    public static void main(String[] args) throws Exception {
        automaticResumeCannotAcknowledge(true);
        automaticResumeCannotAcknowledge(false);
        System.out.println("RecoveryLifecycleTest: automatic resume preserves recovery gate with/without checkpoint");
    }

    static void automaticResumeCannotAcknowledge(boolean checkpointExists) throws Exception {
        File dir = Files.createTempDirectory("tamaemu-recovery-test-").toFile();
        File save = new File(dir, "pet.state");
        Files.write(new File(save + ".running").toPath(), new byte[] {1});
        if (checkpointExists) Files.write(save.toPath(), new byte[] {1});
        Worker worker = new Worker(); Backend backend = new Backend();
        EmulatorSession session = new EmulatorSession(backend, worker, Runnable::run, "rom", save.getPath());
        EmulatorSession.Listener listener = new EmulatorSession.Listener() {
            public void state(EmulatorSession.State state) {}
            public void frame(int width, int height, int[] pixels) {}
        };
        session.resume(listener); worker.drain();
        session.pause(); worker.drain();
        session.resume(listener); worker.drain();
        if (backend.opens != 0) {
            throw new AssertionError("Automatic resume acknowledged interrupted session; checkpointExists=" + checkpointExists);
        }
        if (!checkpointExists && save.exists()) throw new AssertionError("Recovery created a replacement pet");
    }
}
