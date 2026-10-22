package io.github.projectcetacean.tamaemu;

import java.util.concurrent.atomic.AtomicBoolean;

/** A single boundary sample. UTC must be positive; boot zero and elapsed zero are valid. */
final class ClockSample {
    static final long UNKNOWN_BOOT = Long.MAX_VALUE;
    final long wallMs, elapsedMs, bootCount;
    ClockSample(long wall, long elapsed, long boot) {
        if (wall <= 0 || elapsed < 0 || boot < 0) throw new IllegalArgumentException("Invalid clock sample");
        wallMs = wall; elapsedMs = elapsed; bootCount = boot;
    }
}
interface Clock { ClockSample sample(); }

/** Immutable representation of the native eight-slot ABI. */
final class LifeRecord {
    final long schema, wallUtcMs, elapsedMs, bootCount, debtMs, mode, generation, clockQuality;
    private LifeRecord(long[] a) {
        schema=a[0]; wallUtcMs=a[1]; elapsedMs=a[2]; bootCount=a[3];
        debtMs=a[4]; mode=a[5]; generation=a[6]; clockQuality=a[7];
    }
    static LifeRecord from(long[] a) {
        if (a == null || a.length != 8) throw new IllegalArgumentException("Expected eight life slots");
        for (long slot : a) if (slot < 0) throw new IllegalArgumentException("Negative life slot");
        if (a[0] == 0) {
            for (long slot : a) if (slot != 0) throw new IllegalArgumentException("Nonzero legacy record");
        } else if (a[0] != 1 || a[1] == 0 || a[5] > 1 || a[6] == 0 || a[7] > 1) {
            throw new IllegalArgumentException("Invalid life record");
        }
        return new LifeRecord(a);
    }
    long[] toArray() { return new long[]{schema,wallUtcMs,elapsedMs,bootCount,debtMs,mode,generation,clockQuality}; }
}

/** Pure checked accounting. Execution budgets never cap this ledger. */
final class LifeLedger {
    static final int MAX_REPLAY_MILLIS = 60_000;
    private LifeLedger() {}
    static LifeRecord initialize(ClockSample s) {
        return LifeRecord.from(new long[]{1,s.wallMs,s.elapsedMs,s.bootCount,0,0,1,0});
    }
    static LifeRecord account(LifeRecord r, ClockSample s, boolean foreground) {
        if (r.schema != 1) throw new IllegalArgumentException("Uninitialized ledger");
        boolean same = r.bootCount != ClockSample.UNKNOWN_BOOT && s.bootCount == r.bootCount;
        long wallDelta = Math.subtractExact(s.wallMs,r.wallUtcMs);
        long delta, quality = r.clockQuality;
        if (same && s.elapsedMs >= r.elapsedMs) {
            delta = Math.subtractExact(s.elapsedMs,r.elapsedMs);
            long difference = Math.subtractExact(wallDelta,delta);
            if (difference > 1000 || difference < -1000) quality=1;
        } else {
            delta = Math.max(0,wallDelta); quality=1;
        }
        long debt = r.debtMs;
        if (r.mode == 0 || foreground) debt = Math.addExact(debt,delta);
        return LifeRecord.from(new long[]{1,s.wallMs,s.elapsedMs,s.bootCount,debt,r.mode,r.generation,quality});
    }
    static LifeRecord mode(LifeRecord r, boolean protect) {
        long[] a=r.toArray(); a[5]=protect?1:0; return LifeRecord.from(a);
    }
    static LifeRecord consume(LifeRecord r,int ms) {
        if (ms < 0 || ms > 100 || ms > r.debtMs) throw new IllegalArgumentException("Invalid life quantum");
        long[] a=r.toArray(); a[4]-=ms; return LifeRecord.from(a);
    }
    /** Separate checked replay accounting leaves literal consume(0..100) unchanged. */
    static LifeRecord consumeReplay(LifeRecord r,int completed,int requested) {
        if(requested<=0||requested>MAX_REPLAY_MILLIS||requested>r.debtMs
                ||completed<=0||completed>requested)throw new IllegalArgumentException("Invalid replay consumption");
        long[] a=r.toArray();a[4]=Math.subtractExact(r.debtMs,completed);return LifeRecord.from(a);
    }
    static LifeRecord nextGeneration(LifeRecord r) {
        long[] a=r.toArray(); a[6]=Math.addExact(r.generation,1); return LifeRecord.from(a);
    }
    /** Protected crash recovery freezes the ambiguous interval without removing committed debt. */
    static LifeRecord reanchor(LifeRecord r,ClockSample s) {
        long[] a=r.toArray(); a[1]=s.wallMs;a[2]=s.elapsedMs;a[3]=s.bootCount;a[7]=1;return LifeRecord.from(a);
    }
}

final class LifeStatus {
    enum OwnerState { CLOSED, CATCHING_UP, PLAYING, SAVING, SAVE_FAILED, RECOVERY_REQUIRED, ACCOUNTING_ERROR }
    final LifeRecord record;
    final OwnerState state;
    final long mode,debtMs,clockQuality,committedGeneration,lastSuccessfulCommitUtcMs;
    final boolean saving, schedulingFailed, inputContinuity;
    final String schedulerStatus;
    LifeStatus(LifeRecord working,LifeRecord committed,OwnerState state,long savedUtc,String scheduler,boolean schedulingFailed) {
        this(working,committed,state,savedUtc,scheduler,schedulingFailed,false);
    }
    LifeStatus(LifeRecord working,LifeRecord committed,OwnerState state,long savedUtc,String scheduler,boolean schedulingFailed,boolean inputContinuity) {
        this.inputContinuity=state==OwnerState.SAVING&&inputContinuity;
        record=working;this.state=state;mode=committed==null?0:committed.mode;
        debtMs=working==null?0:working.debtMs;clockQuality=working==null?0:working.clockQuality;
        committedGeneration=committed==null?0:committed.generation;lastSuccessfulCommitUtcMs=savedUtc;
        saving=state==OwnerState.SAVING; schedulerStatus=scheduler;this.schedulingFailed=schedulingFailed;
    }
}
final class CommitSnapshot {
    final LifeRecord record;
    final long committedUtcMs;
    private final byte[] payloadHash;
    private final int[] rawFrame;
    CommitSnapshot(LifeRecord record,byte[] hash,int[] raw,long utc) {
        if (record==null || hash==null || hash.length!=32 || raw==null || raw.length!=128*128 || utc<=0)
            throw new IllegalArgumentException("Invalid commit snapshot");
        this.record=record;payloadHash=hash.clone();rawFrame=raw.clone();committedUtcMs=utc;
    }
    byte[] payloadHash() { return payloadHash.clone(); }
    int[] rawFrame() { return rawFrame.clone(); }
}
interface CommitObserver {
    void committed(CommitSnapshot snapshot);
    /** Authoritative owner failure, independent of foreground/UI attachment. */
    default void recoveryRequired() {}
}
interface ModeCallback { void complete(boolean accepted,LifeStatus status); }
interface JobCompletion { void complete(JobLease lease); }
final class JobLease {
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final AtomicBoolean completed=new AtomicBoolean();
    void cancel() { cancelled.set(true); }
    boolean isCancelled() { return cancelled.get(); }
    boolean completeOnce() { return completed.compareAndSet(false,true); }
}
