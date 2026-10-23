package io.github.projectcetacean.tamaemu;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** One finite passive session. No activity, audio output, input, or guest operations. */
final class AudioQaInputPassiveScalars implements AudioPump.PassiveObserver {
    static final String TAG="TamaemuPassiveScalars";
    static final long LIMIT_MS=32000,ROW_MS=2000;
    private static final int MAX_PUMPS=4,MAX_EPOCHS=64,CAUSES=12;
    private static boolean consumed;
    private final Handler handler;
    private final LongSupplier now;
    private final List<PumpScalars> pumps=new ArrayList<>();
    private volatile boolean active;
    private long deadline;
    private int refusedBindings;
    private final Runnable tick=this::tick;

    AudioQaInputPassiveScalars() { this(new Handler(Looper.getMainLooper()),SystemClock::elapsedRealtime); }
    AudioQaInputPassiveScalars(Handler handler,LongSupplier now) { this.handler=handler;this.now=now; }
    boolean begin() {
        if(Looper.myLooper()!=handler.getLooper())return false;
        synchronized(AudioQaInputPassiveScalars.class) {
            if(consumed){Log.i(TAG,"phase=declined reason=once_per_process");return false;}
            consumed=true;
        }
        deadline=now.getAsLong()+LIMIT_MS;active=true;
        if(!AudioPump.registerPassiveObserver(this)){active=false;Log.i(TAG,"phase=declined reason=observer_busy");return false;}
        Log.i(TAG,"phase=armed budgetMs=32000");
        handler.postDelayed(tick,ROW_MS);return true;
    }
    boolean busy() { return active; }
    void stop(String reason) {
        if(!active)return;
        active=false;AudioPump.removePassiveObserver(this);handler.removeCallbacksAndMessages(null);
        printRows();
        Log.i(TAG,"phase=cleanup reason="+reason+" observerRemoved=1 callbacksRemoved=1");
    }
    private void tick() {
        if(!active)return;
        long remaining=deadline-now.getAsLong();
        if(remaining<=0){stop("deadline");return;}
        printRows();handler.postDelayed(tick,Math.min(ROW_MS,remaining));
    }
    private boolean observing() { return active&&now.getAsLong()<deadline; }
    @Override public synchronized AudioPump.PassiveObserver bind() {
        if(!observing())return null;
        if(pumps.size()==MAX_PUMPS){refusedBindings++;return null;}
        PumpScalars p=new PumpScalars(pumps.size()+1);pumps.add(p);return p;
    }
    private void printRows() {
        List<PumpScalars> copy;int refused;
        synchronized(this){copy=new ArrayList<>(pumps);refused=refusedBindings;}
        if(copy.isEmpty())Log.i(TAG,"phase=row observedPumps=0 firstOfferDebtMs=unavailable firstStablePending=unavailable");
        for(PumpScalars p:copy)for(Row row:p.snapshot())Log.i(TAG,row.line());
        if(refused>0)Log.i(TAG,"phase=capacity refusedPumps="+refused);
    }
    private static String value(long n) { return n<0?"unavailable":Long.toString(n); }
    private static String epochReason(int reason) {
        switch(reason){case CONFIGURE_ON:return "configure_on";case CONFIGURE_OFF:return "configure_off";
            case INVALIDATE:return "invalidate";case FOCUS_GAIN:return "focus_gain";case FOCUS_LOSS:return "focus_loss";default:return "unavailable";}
    }
    static final class Row {
        final int pump;final long token;
        int reason,trackStarts,trackFlushes,trackStops;
        boolean offered,stable,dirty=true;
        long debt=-1,pending=-1,lagCount,maxLag,retained,cleared,summarySamples;
        int retainedMask,clearedMask,lastReferenceCause,lastReferenceBefore,lastReferenceAfter;
        final long[] clears=new long[CAUSES];
        AudioPump.Summary totals;
        int platform=-1;
        long trimDelta=-1,dropDelta=-1,underrunDelta=-1,platformDelta=-1;
        int overflow;
        Row(int pump,long token){this.pump=pump;this.token=token;}
        Row(Row from) {
            pump=from.pump;token=from.token;reason=from.reason;trackStarts=from.trackStarts;trackFlushes=from.trackFlushes;trackStops=from.trackStops;
            offered=from.offered;stable=from.stable;debt=from.debt;pending=from.pending;lagCount=from.lagCount;maxLag=from.maxLag;
            retained=from.retained;cleared=from.cleared;retainedMask=from.retainedMask;clearedMask=from.clearedMask;
            lastReferenceCause=from.lastReferenceCause;lastReferenceBefore=from.lastReferenceBefore;lastReferenceAfter=from.lastReferenceAfter;
            System.arraycopy(from.clears,0,clears,0,CAUSES);totals=from.totals;platform=from.platform;summarySamples=from.summarySamples;
            trimDelta=from.trimDelta;dropDelta=from.dropDelta;underrunDelta=from.underrunDelta;platformDelta=from.platformDelta;overflow=from.overflow;
        }
        String line() {
            return "phase=row pump="+pump+" epoch="+token+" epochReason="+epochReason(reason)
                    +" trackStarts="+trackStarts+" trackFlushes="+trackFlushes+" trackStops="+trackStops
                    +" firstOfferSeen="+(offered?1:0)+" firstOfferDebtMs="+value(debt)+" firstStablePending="+value(pending)
                    +" headBehindFloorCount="+lagCount+" maxHeadBehindFloorFrames="+maxLag
                    +" referenceRetained="+retained+" referenceCleared="+cleared+" retainedMask="+retainedMask+" clearedMask="+clearedMask
                    +" lastReferenceCause="+lastReferenceCause+" lastReferenceBeforeMask="+lastReferenceBefore+" lastReferenceAfterMask="+lastReferenceAfter
                    +" clearGrant="+clears[GRANT_CHANGE]+" clearCounterDiscontinuity="+clears[COUNTER_DISCONTINUITY]
                    +" clearCounterAdvance="+clears[COUNTER_ADVANCE]+" clearRawHead="+clears[RAW_HEAD_INVALID]
                    +" clearQuietBackwards="+clears[QUIET_BACKWARDS]+" clearBoundContradiction="+clears[BOUND_CONTRADICTION]
                    +" clearKnownEmpty="+clears[KNOWN_EMPTY]+" clearUnknown="+clears[UNKNOWN_HEAD]+" clearUnstable="+clears[UNSTABLE_FILL]+" clearTrackReset="+clears[TRACK_RESET]
                    +" trimmedDelta="+value(trimDelta)+" droppedDelta="+value(dropDelta)+" inferredUnderrunDelta="+value(underrunDelta)
                    +" summarySamples="+summarySamples+" sampledPlatformUnderruns="+value(platform)+" sampledPlatformDelta="+value(platformDelta)
                    +" refusedEpochEvents="+overflow+" summary="+(totals==null?"unavailable":totals.line());
        }
    }
    /** Only scalar state; no pump/output/guest reference. Constant-capacity callback work. */
    final class PumpScalars implements AudioPump.PassiveObserver {
        final int ordinal;
        final Row[] rows=new Row[MAX_EPOCHS];int size,overflow;
        long previousToken=Long.MIN_VALUE;
        AudioPump.Summary previous;int previousPlatform=-1;
        PumpScalars(int ordinal){this.ordinal=ordinal;}
        private Row row(long token) {
            if(!observing())return null;
            for(int i=0;i<size;i++)if(rows[i].token==token){rows[i].dirty=true;return rows[i];}
            if(size==rows.length){overflow++;rows[size-1].dirty=true;return null;}
            Row r=new Row(ordinal,token);rows[size++]=r;return r;
        }
        @Override public synchronized void epoch(long token,int reason){Row r=row(token);if(r!=null&&r.reason==0)r.reason=reason;}
        @Override public synchronized void track(long token,int reason){Row r=row(token);if(r==null)return;
            if(reason==TRACK_START)r.trackStarts++;else if(reason==TRACK_FLUSH)r.trackFlushes++;else if(reason==TRACK_STOP)r.trackStops++;}
        @Override public synchronized void acceptedOffer(long token,long debt){Row r=row(token);if(r!=null&&!r.offered){r.offered=true;r.debt=debt;}}
        @Override public synchronized void stablePhase(long token,long pending){Row r=row(token);if(r!=null&&!r.stable){r.stable=true;r.pending=pending;}}
        @Override public synchronized void headLag(long token,long frames){Row r=row(token);if(r!=null&&frames>0){r.lagCount++;r.maxLag=Math.max(r.maxLag,frames);}}
        @Override public synchronized void references(long token,int cause,int before,int after){Row r=row(token);if(r==null)return;
            r.lastReferenceCause=cause;r.lastReferenceBefore=before;r.lastReferenceAfter=after;
            int removed=before&~after,kept=before&after;
            if(removed!=0){r.cleared++;r.clearedMask|=removed;if(cause>0&&cause<CAUSES)r.clears[cause]++;}
            if(cause==DELAYED_HEAD&&kept!=0){r.retained++;r.retainedMask|=kept;}}
        @Override public synchronized void summary(long token,AudioPump.Summary totals,int sampled){Row r=row(token);if(r==null)return;
            boolean same=previous!=null&&previousToken==token;
            r.trimDelta=same?delta(totals.trimmedFrames,previous.trimmedFrames):-1;
            r.dropDelta=same?delta(totals.droppedFrames,previous.droppedFrames):-1;
            r.underrunDelta=same?delta(totals.underruns,previous.underruns):-1;
            r.platformDelta=same&&sampled>=0&&previousPlatform>=0?delta(sampled,previousPlatform):-1;
            r.summarySamples++;r.totals=totals;r.platform=sampled;previous=totals;previousToken=token;previousPlatform=sampled;
        }
        synchronized List<Row> snapshot(){List<Row> result=new ArrayList<>();for(int i=0;i<size;i++)if(rows[i].dirty){rows[i].overflow=overflow;result.add(new Row(rows[i]));rows[i].dirty=false;}return result;}
    }
    private static long delta(long current,long previous){return current>=previous?current-previous:-1;}
}
