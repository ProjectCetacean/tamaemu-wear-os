package io.github.projectcetacean.tamaemu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** App-scoped session: native state survives Activity recreation if saving fails. */
final class EmulatorSession {
    enum State { LOADING, RUNNING, MISSING_FIRMWARE, OPEN_FAILED, SAVE_FAILED, NATIVE_FAILED,
        RECOVERY_REQUIRED, RECOVERY_MISSING_SAVE }
    enum StopResult { SAVED, SAVE_FAILED, TIMED_OUT, INTERRUPTED }
    static final long CHECKPOINT_MILLIS = 30_000;
    // Host debug save/fsync measured317ms; watch latency still needs validation.
    static final int STOP_BUDGET_MILLIS = 1000;

    private static final class StopTicket {
        final CountDownLatch completed = new CountDownLatch(1);
        volatile StopResult result = StopResult.SAVE_FAILED;
        void finish(StopResult result) { this.result = result; completed.countDown(); }
    }

    // A request ceiling, not a native quantum or a promise of completion.
    static final int MAX_REPLAY_MILLIS = 60_000;
    static int checkedReplayCompletion(int requested,int completed) {
        if(requested<=0||requested>MAX_REPLAY_MILLIS||completed<=0||completed>requested)
            throw new IllegalStateException("Invalid replay completion");
        return completed;
    }

    interface Backend {
        /** Catching-only completed-work API. Default performs one existing literal slice.
         * Success reports only completed guest ms, in 1..requested. A throw means
         * unknown guest state: no prefix is acknowledged and recovery must precede save.
         * Overrides must yield after a small wall window, except an in-flight slice.
         */
        default int replay(long handle,int requested) throws Exception {
            if(requested<=0||requested>MAX_REPLAY_MILLIS)throw new IllegalArgumentException("Invalid replay request");
            int completed=Math.min(100,requested);headless(handle,completed);return completed;
        }
        default void headless(long handle, int elapsed) throws Exception { advance(handle, elapsed, new int[width(handle)*height(handle)]); }
        default long[] readLife(long handle) { return new long[8]; }
        default void writeLife(long handle, long[] candidate) {}
        default byte[] committedPayloadHash(long handle) { return null; }
        default void inputApplied(long handle, int button, boolean down, long admittedElapsedMs) {}
        default boolean soundAccepting() { return false; }
        long open(String rom, String save) throws Exception;
        void close(long handle);
        int width(long handle);
        int height(long handle);
        void advance(long handle, int elapsed, int[] pixels) throws Exception;
        default void advanceWithDebt(long handle,int elapsed,int[] pixels,long debtMs) throws Exception {
            advance(handle,elapsed,pixels);
        }
        void button(long handle, int button, boolean down);
        boolean save(long handle) throws Exception;
    }

    interface Worker {
        void execute(Runnable task);
        void later(Runnable task, int delayMillis);
        long nowMillis();
    }

    interface Listener {
        void state(State state);
        void frame(int width, int height, int[] pixels);
        default void lifeStatus(LifeStatus status) {}
    }

    private LifeOwner life;

    private final Backend backend;
    private final Worker worker;
    private final Executor main;
    private final String romPath;
    private final String savePath;
    private final File runningMarker;
    // Changed synchronously on the UI thread, checked before every native frame.
    private final AtomicLong epoch = new AtomicLong();
    private volatile StopTicket stopTicket;
    // All remaining state belongs exclusively to worker.
    private long handle;
    private boolean pendingSave;
    private int width, height;
    private int[] pixels;
    private long lastTime;
    private long lastCheckpoint;
    private boolean markerChecked;
    private boolean recoveryRequired;
    private boolean markerOwned;
    private long framePendingEpoch = -1;
    private Listener listener;
    private final long[] pressedAt = new long[3];
    private final long[] buttonVersion = new long[3];

    EmulatorSession(Backend backend, Worker worker, Executor main, String romPath, String savePath) {
        this.backend = backend;
        this.worker = worker;
        this.main = main;
        this.romPath = romPath;
        this.savePath = savePath;
        this.runningMarker = new File(savePath + ".running");
    }

    EmulatorSession(Backend backend, Worker worker, Executor main, String romPath, String savePath,
                    Clock clock, CommitObserver observer) {
        this(backend, worker, main, romPath, savePath);
        life = new LifeOwner(backend,worker,main,romPath,savePath,clock,observer);
    }

    void requestMode(boolean protectedAway, ModeCallback callback) {
        if (life == null) throw new IllegalStateException("Life backend required");
        life.requestMode(protectedAway,callback);
    }
    void runBackground(JobLease lease,long wallBudgetMs,JobCompletion completion) {
        if (life == null) throw new IllegalStateException("Life backend required");
        life.background(lease,wallBudgetMs,completion);
    }
    void cancelBackground(JobLease lease) { lease.cancel(); if (life != null) life.cancel(lease); }
    void reconcileScheduler(LifeScheduler scheduler) { if(life!=null) life.reconcileScheduler(scheduler); }
    void schedulerStatus(String status,boolean failed) { if(life!=null) life.schedulerStatus(status,failed); }

    void resume(Listener listener) {
        resume(listener, false);
    }

    void retry(Listener listener) {
        resume(listener, true);
    }

    private void resume(Listener listener, boolean acknowledgeRecovery) {
        if (life != null) { life.resume(listener,acknowledgeRecovery); return; }
        final long token = epoch.incrementAndGet();
        stopTicket = null;
        worker.execute(() -> begin(token, listener, acknowledgeRecovery));
    }

    void pause() {
        if (life != null) { life.pause(); return; }
        epoch.incrementAndGet();
        final StopTicket ticket = new StopTicket();
        stopTicket = ticket;
        worker.execute(() -> {
            StopResult result = StopResult.SAVE_FAILED;
            try {
                listener = null;
                if (handle == 0) result = StopResult.SAVED;
                else {
                    releaseButtons();
                    // Never close a session whose newest state has not reached disk.
                    if (persist() && clearRunningMarker()) {
                        close();
                        result = StopResult.SAVED;
                    }
                }
            } finally {
                ticket.finish(result);
            }
        });
    }

    /** Called only from onStop, after pause has already started asynchronous saving. */
    StopResult stopAndAwait(int budgetMillis) {
        if (life != null) return life.stopAndAwait(budgetMillis);
        if (budgetMillis < 0 || budgetMillis > STOP_BUDGET_MILLIS) {
            throw new IllegalArgumentException("Invalid stop budget");
        }
        StopTicket ticket = stopTicket;
        if (ticket == null) {
            pause();
            ticket = stopTicket;
        }
        try {
            if (!ticket.completed.await(budgetMillis, TimeUnit.MILLISECONDS)) return StopResult.TIMED_OUT;
            return ticket.result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return StopResult.INTERRUPTED;
        }
    }

    void button(int button, boolean down) {
        if (life != null) { life.button(button,down); return; }
        if (button < 0 || button >= 3) throw new IllegalArgumentException("Invalid button");
        final long token = epoch.get();
        worker.execute(() -> {
            if (epoch.get() != token || handle == 0 || listener == null || pendingSave) return;
            try {
                long version = ++buttonVersion[button];
                if (down) {
                    pressedAt[button] = worker.nowMillis();
                    backend.button(handle, button, true);
                } else {
                    // A quick tap must survive one emulated frame before key-up.
                    int remaining = (int) Math.max(0, 40 - (worker.nowMillis() - pressedAt[button]));
                    if (remaining == 0) backend.button(handle, button, false);
                    else worker.later(() -> releaseButton(token, button, version), remaining);
                }
            } catch (Exception | LinkageError failure) {
                fail(token, State.NATIVE_FAILED);
            }
        });
    }

    private void releaseButton(long token, int button, long version) {
        if (epoch.get() != token || handle == 0 || buttonVersion[button] != version) return;
        try { backend.button(handle, button, false); }
        catch (RuntimeException | LinkageError failure) { fail(token, State.NATIVE_FAILED); }
    }

    private void begin(long token, Listener nextListener, boolean acknowledgeRecovery) {
        if (epoch.get() != token) return;
        listener = nextListener;
        state(token, State.LOADING);
        if (!markerChecked) {
            markerChecked = true;
            recoveryRequired = runningMarker.isFile();
        }
        if (recoveryRequired) {
            if (!new File(savePath).isFile()) {
                state(token, State.RECOVERY_MISSING_SAVE);
                return;
            }
            if (!acknowledgeRecovery) {
                state(token, State.RECOVERY_REQUIRED);
                return;
            }
            recoveryRequired = false;
        }
        if (pendingSave && !persist()) {
            state(token, State.SAVE_FAILED);
            return;
        }
        if (handle == 0) {
            try {
                handle = backend.open(romPath, savePath);
                if (handle == 0) throw new IllegalStateException("No native session");
                width = backend.width(handle);
                height = backend.height(handle);
                if (width <= 0 || height <= 0 || width > 1024 || height > 1024) {
                    throw new IllegalStateException("Invalid display size");
                }
                pixels = new int[width * height];
            } catch (Exception | LinkageError failure) {
                close();
                state(token, new File(romPath).isFile() ? State.OPEN_FAILED : State.MISSING_FIRMWARE);
                return;
            }
            // A checkpoint may include a held key. No physical press survives reopening.
            releaseButtons();
            if (!persist()) {
                state(token, State.SAVE_FAILED);
                return;
            }
        }
        if (!markRunning()) {
            state(token, State.SAVE_FAILED);
            return;
        }
        lastTime = worker.nowMillis();
        state(token, State.RUNNING);
        tick(token, true);
    }

    private void tick(long token, boolean initial) {
        if (epoch.get() != token || listener == null || handle == 0 || pendingSave) return;
        long now = worker.nowMillis();
        int elapsed = initial ? 0 : (int) Math.max(0, Math.min(100, now - lastTime));
        lastTime = now;
        try {
            backend.advance(handle, elapsed, pixels);
            if (now - lastCheckpoint >= CHECKPOINT_MILLIS && !persist()) {
                fail(token, State.SAVE_FAILED);
                return;
            }
            if (framePendingEpoch != token) {
                framePendingEpoch = token;
                final int[] snapshot = pixels.clone();
                final int frameWidth = width, frameHeight = height;
                final Listener target = listener;
                main.execute(() -> {
                    try {
                        if (epoch.get() == token) target.frame(frameWidth, frameHeight, snapshot);
                    } finally {
                        worker.execute(() -> {
                            if (framePendingEpoch == token) framePendingEpoch = -1;
                        });
                    }
                });
            }
            worker.later(() -> tick(token, false), 33);
        } catch (Exception | LinkageError failure) {
            fail(token, State.NATIVE_FAILED);
        }
    }

    private boolean persist() {
        pendingSave = true;
        try {
            if (!backend.save(handle)) return false;
            pendingSave = false;
            lastCheckpoint = worker.nowMillis();
            return true;
        } catch (Exception | LinkageError failure) {
            return false;
        }
    }

    private boolean markRunning() {
        if (markerOwned) return true;
        try (FileOutputStream marker = new FileOutputStream(runningMarker)) {
            marker.write(1);
            marker.getFD().sync();
            markerOwned = true;
            return true;
        } catch (IOException failure) {
            pendingSave = true;
            return false;
        }
    }

    private boolean clearRunningMarker() {
        if (!markerOwned) return true;
        if (runningMarker.exists() && !runningMarker.delete()) {
            pendingSave = true;
            return false;
        }
        markerOwned = false;
        return true;
    }

    private void releaseButtons() {
        for (int button = 0; button < 3; button++) {
            buttonVersion[button]++;
            try { backend.button(handle, button, false); }
            catch (RuntimeException | LinkageError ignored) { /* Still try the other buttons. */ }
        }
    }

    private void fail(long token, State failure) {
        releaseButtons();
        state(token, failure);
        // No further ticks are queued. Retry resumes this same in-memory pet.
    }

    private void close() {
        if (handle != 0) {
            try { backend.close(handle); }
            catch (RuntimeException | LinkageError ignored) { /* Native close has no recovery action. */ }
        }
        handle = 0;
        pixels = null;
        pendingSave = false;
    }

    private void state(long token, State state) {
        final Listener target = listener;
        main.execute(() -> {
            if (epoch.get() == token && target != null) target.state(state);
        });
    }
}
