package io.github.projectcetacean.tamaemu;

import java.util.concurrent.Executor;
import java.util.Objects;

/** Best-effort derived cache only; never opens or advances an emulator or writes its save. */
final class CommittedPreviewPublisher implements CommitObserver {
    interface Store { void publish(PetPreviewSnapshot snapshot) throws Exception; }
    interface Refresh { void request() throws Exception; }
    private final Executor executor;
    private final Store store;
    private final Refresh refresh;
    private final Object lock=new Object();
    private CommitSnapshot pending;
    private boolean running;

    /** Executor must be asynchronous and serial, with no inline/caller-runs fallback. */
    CommittedPreviewPublisher(Executor executor,Store store,Refresh refresh) {
        this.executor=Objects.requireNonNull(executor);
        this.store=Objects.requireNonNull(store);
        this.refresh=Objects.requireNonNull(refresh);
    }

    /** Production adapter; refresh is supplied by the Android integration. */
    CommittedPreviewPublisher(Executor executor,PetPreviewStore store,Refresh refresh) {
        this(executor,store::publish,refresh);
    }

    public void committed(CommitSnapshot snapshot) {
        synchronized(lock) {
            pending=Objects.requireNonNull(snapshot);
            if(running) return;
            running=true;
            try {
                executor.execute(this::drain);
            } catch(RuntimeException rejected) {
                // Keep the latest pending snapshot. A later commit can restart dispatch.
                running=false;
            }
        }
    }

    /** Recovery invalidates the displayed Tile even without a new cache publication. */
    void refreshRequired() {
        try { refresh.request(); }
        catch(Exception|LinkageError unavailable) { /* Availability is already closed by the owner. */ }
    }

    private void drain() {
        boolean refreshNeeded=false;
        while(true) {
            CommitSnapshot snapshot;
            synchronized(lock) {
                snapshot=pending;
                pending=null;
                if(snapshot==null && !refreshNeeded) {
                    // Taking pending work and becoming idle share the submission lock.
                    running=false;
                    return;
                }
            }
            if(snapshot!=null) {
                try {
                    LifeRecord record=snapshot.record;
                    PetPreviewSnapshot.Mode mode=record.mode==1
                            ? PetPreviewSnapshot.Mode.PROTECTED : PetPreviewSnapshot.Mode.NORMAL;
                    store.publish(new PetPreviewSnapshot(snapshot.payloadHash(),record.generation,
                            snapshot.committedUtcMs,mode,record.mode==1,record.debtMs,
                            (int)record.clockQuality,snapshot.rawFrame()));
                    refreshNeeded=true;
                } catch(Exception unavailable) {
                    // A malformed/failed derived cache cannot change pet-save success.
                }
            } else {
                try { refresh.request(); }
                catch(Exception unavailable) { /* Later commits still publish and refresh. */ }
                refreshNeeded=false;
            }
        }
    }
}
