package io.github.projectcetacean.tamaemu;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Detached PCM only. All output calls run on one executor, never the guest worker.
 *  Pending batches stay ordered and bounded; only startup/refill may exceed the normal target. */
final class AudioPump {
    /** Optional scalar-only sink. Implementations must be bounded and never call owners.
     * Each pump binds once at construction; a late registration cannot reconstruct history. */
    interface PassiveObserver {
        int CONFIGURE_ON=1, CONFIGURE_OFF=2, INVALIDATE=3, FOCUS_GAIN=4, FOCUS_LOSS=5;
        int TRACK_START=1, TRACK_FLUSH=2, TRACK_STOP=3;
        int GRANT_CHANGE=1, COUNTER_DISCONTINUITY=2, COUNTER_ADVANCE=3,
                RAW_HEAD_INVALID=4, QUIET_BACKWARDS=5, BOUND_CONTRADICTION=6,
                UNKNOWN_HEAD=7, UNSTABLE_FILL=8, TRACK_RESET=9, DELAYED_HEAD=10, KNOWN_EMPTY=11;
        long UNAVAILABLE=-1;
        default PassiveObserver bind() { return this; }
        default void epoch(long token,int reason) {}
        default void track(long token,int reason) {}
        default void acceptedOffer(long token,long debtMs) {}
        default void stablePhase(long token,long pendingFrames) {}
        default void headLag(long token,long frames) {}
        /** Presence bits: phase=1, refill=2, playback bound=4. Normal refill consumption is excluded. */
        default void references(long token,int cause,int before,int after) {}
        default void summary(long token,Summary totals,int sampledPlatformUnderruns) {}
    }
    private static volatile PassiveObserver passiveRegistration;
    static synchronized boolean registerPassiveObserver(PassiveObserver observer) {
        if(observer==null||passiveRegistration!=null)return false;
        passiveRegistration=observer;return true;
    }
    static synchronized void removePassiveObserver(PassiveObserver observer) {
        if(passiveRegistration==observer)passiveRegistration=null;
    }
    private final PassiveObserver passiveOwner,passiveSink;
    private PassiveObserver passive() { return passiveRegistration==passiveOwner?passiveSink:null; }
    private void passiveEpoch(int reason) {
        PassiveObserver o=passive();if(o!=null)try{o.epoch(epoch,reason);}catch(Throwable ignored){}
    }
    private void passiveTrack(long token,int reason) {
        PassiveObserver o=passive();if(o!=null)try{o.track(token,reason);}catch(Throwable ignored){}
    }
    private int referenceMask() { return (phaseAt>=0?1:0)|(refillHead>=0?2:0)|(playbackBound?4:0); }
    private void passiveReferences(long token,int cause,int before) {
        PassiveObserver o=passive();if(o!=null)try{o.references(token,cause,before,referenceMask());}catch(Throwable ignored){}
    }
    private void passiveOffer(long token,long debt) {
        PassiveObserver o=passive();if(o!=null)try{o.acceptedOffer(token,debt);}catch(Throwable ignored){}
    }
    interface Focus { void changed(boolean granted); }
    interface Output {
        boolean start(Focus focus);
        /** Non-blocking: returns frames accepted (>=0) and throws when the output is unusable. */
        int write(short[] samples,int offset,int length);
        /** Frames written but not yet played, or -1 when unknown. The derived played head
         * must not lead consumption. Integer-ms reads may straddle at most 47 frames
         * within the same tick; hidden leading heads are unsupported. */
        long queuedFrames();
        /** Real startup/refill requirement and writable buffer. Platform adapters must validate their grant. */
        default int startThresholdFrames() { return 1; }
        default int writableFrames() { return MAX_PENDING_FRAMES; }
        default int underrunCount() { return -1; }
        default int routeId() { return 0; }
        default void report(Summary s) {}
        /** Current output failure; the summary's failure counter remains cumulative. */
        default void reportFailure(Summary s) { report(s); }
        void suspend(); void resume(); void stop();
    }
    interface Factory { Output create(); }
    /** Runs a task after a delay; returns its cancel action. */
    interface Delay { Runnable after(long delayMs,Runnable task); }
    /** 60 ms at 48 kHz: the normal audio ceiling written ahead of the playback head. */
    static final int TARGET_QUEUED_FRAMES=2880;
    static final int MAX_PENDING_FRAMES=9600;
    static final int FRAMES_PER_MS=48;
    private static final long QUIET_RECOVERY_FRAMES=1000L*FRAMES_PER_MS;
    static final long EXPIRE_MS=250,RETRY_MS=10,REPORT_MS=2000;

    private static final class Batch {
        final long sequence,at;final short[] samples;int offset;final int end;
        long expiryCredit,pauseAt=-1;boolean deferred;
        Batch(long sequence,long at,short[] samples,int offset,int end){this.sequence=sequence;this.at=at;this.samples=samples;this.offset=offset;this.end=end;}
        Batch(Batch from,int offset,int end){
            this(from.sequence,from.at,from.samples,offset,end);
            expiryCredit=from.expiryCredit;pauseAt=from.pauseAt;deferred=from.deferred;
        }
        int remaining(){return end-offset;}
    }

    private final Executor executor;
    private final Delay delay;
    private final LongSupplier now;
    private final Factory factory;
    private final int normalQueueFrames;
    private final Latency latency=new Latency();
    // lossPending survives a coalesced gain: written track audio predates the loss and must be flushed.
    private boolean active, focused=true, failed, queued, stop, focusDirty, lossPending;
    private long epoch, activation, sequence, pendingFrames, retryGeneration, retiredFrames;
    // Completed logical requests and literal PCM totals are separate, epoch-local observations.
    private long sourceFrames,completedMs,sourceCredit,missingPcm;
    private boolean producerSeen,producerValid=true;
    private final ArrayDeque<Batch> pending=new ArrayDeque<>();
    private Runnable cancelRetry;
    private Output output;
    private boolean prepareRequested;
    private long readyActivation=-1,readyEpoch=-1;
    // Executor only: the current track epoch, reset whenever its buffer is flushed or replaced.
    private long trackWritten, underrunObservedWritten, playedFloor, afterWrite, lastWriteAt, lastReport;
    // Executor only: accepted-frame quiet proof and the original trustworthy playback phase.
    private long quietFrom,quietHead=-1,quietAt;
    private boolean counterContinuous;
    // Unlike quietHead, raw history survives unknown occupancy behind the proven floor.
    // Strict history is required only for references retained beyond the old bounded path.
    private long rawHead=-1,rawHeadAt;
    private boolean retainedLagReference;
    private long phaseAt=-1,phaseOffset,phaseCredit,phaseRetired;
    // Learn head uncertainty only while this reference has had uninterrupted playback.
    private boolean phaseFrozen;
    // A proven drain arms the first accepted refill, regardless of restart threshold.
    // Its pre-write accepted endpoint bounds how far playback could already have advanced.
    private long refillHead=-1,playbackOffsetLower;
    private boolean playbackBound;
    // One optional coalescing window per accepted endpoint, never renewed by counter noise.
    private long coalesceEndpoint=-1,coalesceAt,coalesceUntil=-1;
    private static final int HEAD_BOUND=240;
    private int inflight;
    private boolean priming=true;
    private int required, writable, route, platformUnderruns=-1;
    AudioPump(Executor executor, Delay delay, LongSupplier now, Factory factory) {
        this(executor,delay,now,factory,TARGET_QUEUED_FRAMES);
    }
    AudioPump(Executor executor, Delay delay, LongSupplier now, Factory factory, int normalQueueFrames) {
        if(normalQueueFrames!=1440&&normalQueueFrames!=1920&&normalQueueFrames!=2880)
            throw new IllegalArgumentException("Normal audio queue must be 1440, 1920 or 2880 frames");
        this.executor=executor;this.delay=delay;this.now=now;this.factory=factory;this.normalQueueFrames=normalQueueFrames;
        passiveOwner=passiveRegistration;
        PassiveObserver sink=null;
        if(passiveOwner!=null)try{sink=passiveOwner.bind();}catch(Throwable ignored){}
        passiveSink=sink;
    }
    Latency latency() { return latency; }
    synchronized void configure(boolean value) {
        if(active==value)return;
        active=value;epoch++;passiveEpoch(value?PassiveObserver.CONFIGURE_ON:PassiveObserver.CONFIGURE_OFF);activation++;discard();stop=true;focused=true;failed=false;
        if(output!=null||queued)schedule();
    }
    synchronized boolean accepting() { return active&&focused&&!failed; }
    synchronized long token() { return epoch; }
    int normalQueueMs() { return normalQueueFrames/FRAMES_PER_MS; }
    /** Additive executor work: start output without admitting PCM or reading occupancy. */
    synchronized void prepare(long token) {
        if(token!=epoch||!accepting()||ready(token))return;
        prepareRequested=true;schedule();
    }
    /** Publication follows successful start and track initialization, including pending focus work. */
    synchronized boolean ready(long token) {
        return token==epoch&&accepting()&&output!=null&&readyActivation==activation&&readyEpoch==epoch&&!stop&&!focusDirty;
    }
    /** Returns the batch sequence, or -1 when rejected. Overflow drops the oldest pending frames. */
    synchronized long offer(long token, short[] samples) {
        if(!accepting()||token!=epoch)return -1;
        producerValid=false;
        return enqueue(token,samples,PassiveObserver.UNAVAILABLE);
    }
    /** One positive logical request returned normally. This does not claim hidden native PCM.
     * The literal detached array is the only production credited to transport. */
    synchronized long offerCompletedAdvance(long token,long cumulativeAudibleLogicalMs,short[] samples) {
        return offerCompletedAdvance(token,cumulativeAudibleLogicalMs,samples,PassiveObserver.UNAVAILABLE);
    }
    synchronized long offerCompletedAdvance(long token,long cumulativeAudibleLogicalMs,short[] samples,long debtMs) {
        if(!accepting()||token!=epoch)return -1;
        if(samples==null||samples.length>MAX_PENDING_FRAMES||cumulativeAudibleLogicalMs<=completedMs){
            producerValid=false;return enqueue(token,samples,debtMs);
        }
        try {
            long logical=Math.multiplyExact(cumulativeAudibleLogicalMs,(long)FRAMES_PER_MS);
            long actual=Math.addExact(sourceFrames,samples.length);
            producerSeen=true;
            // A short drain can withhold recovery. Logical completion never credits absent PCM.
            sourceFrames=actual;completedMs=cumulativeAudibleLogicalMs;
            // Later native surplus is not proof that previously absent PCM was returned.
            // Retain the largest observed deficit, without assigning native-sized allowances.
            missingPcm=Math.max(missingPcm,Math.subtractExact(logical,actual));
            sourceCredit=Math.subtractExact(logical,Math.multiplyExact(now.getAsLong(),(long)FRAMES_PER_MS));
            if(samples.length==MAX_PENDING_FRAMES)producerValid=false; // drain may still be saturated
        } catch(ArithmeticException overflow){producerValid=false;}
        return enqueue(token,samples,debtMs);
    }
    private long enqueue(long token,short[] samples,long debtMs) {
        if(samples==null||samples.length==0||samples.length>MAX_PENDING_FRAMES)return -1;
        Batch batch=new Batch(++sequence,now.getAsLong(),samples.clone(),0,samples.length);
        pending.addLast(batch);pendingFrames+=samples.length;latency.offered(samples.length);
        bound();passiveOffer(token,debtMs);schedule();return batch.sequence;
    }
    /** Retire only the abandoned producer, atomically with focus epoch changes. */
    synchronized void invalidate(long token) { if(epoch==token)invalidate(); }
    synchronized void invalidate() { epoch++;passiveEpoch(PassiveObserver.INVALIDATE);activation++;discard();stop=true;if(output!=null||queued)schedule(); }
    /** Explicit flushes are counted; a scheduled retry cannot outlive them. */
    private void discard() {
        prepareRequested=false;
        sourceFrames=0;completedMs=0;sourceCredit=0;missingPcm=0;producerSeen=false;producerValid=true;
        latency.flushed(pendingFrames);retiredFrames+=pendingFrames;pending.clear();pendingFrames=0;cancelRetry();latency.discard();
    }
    private void bound() { long over=pendingFrames-MAX_PENDING_FRAMES;if(over>0)latency.dropped(take(over)); }
    private long take(long frames) {
        long taken=0;
        while(taken<frames&&!pending.isEmpty()) {
            Batch head=pending.peekFirst();int n=(int)Math.min(frames-taken,head.remaining());
            head.offset+=n;taken+=n;if(head.remaining()==0)pending.removeFirst();
        }
        pendingFrames-=taken;retiredFrames+=taken;return taken;
    }
    private void finishCoalescing(long at) {
        for(Batch batch:pending)if(batch.pauseAt>=0){
            batch.expiryCredit+=Math.max(0,at-batch.pauseAt);batch.pauseAt=-1;
        }
    }
    private void expire(long at) {
        // Settle the actual executor wait once, even if the deadline ran while asleep.
        // This credit affects expiry only; the original contact/admission time is retained.
        if(coalesceUntil>=0&&(at>=coalesceUntil||at<coalesceAt))finishCoalescing(at);
        while(!pending.isEmpty()&&pending.peekFirst().pauseAt<0
                &&at-pending.peekFirst().at-pending.peekFirst().expiryCredit>EXPIRE_MS) {
            Batch old=pending.removeFirst();pendingFrames-=old.remaining();retiredFrames+=old.remaining();latency.expired(old.remaining());
        }
    }
    private void schedule() {
        if(queued)return;queued=true;
        try { executor.execute(this::drain); }
        catch(RuntimeException unavailable) {queued=false;failed=true;discard();}
    }
    private void retry() {
        if(cancelRetry!=null)return;
        final long generation=++retryGeneration;
        long wait=RETRY_MS,at=now.getAsLong();
        // A paused head keeps its original deadline even if this read finds it due.
        if(!pending.isEmpty()&&pending.peekFirst().pauseAt>=0&&at>=coalesceAt)
            wait=Math.max(0,coalesceUntil-at);
        try {
            cancelRetry=delay.after(wait,()->{
                synchronized(AudioPump.this){if(generation!=retryGeneration)return;cancelRetry=null;latency.retried();schedule();}
            });
        } catch(RuntimeException unavailable) {failed=true;discard();}
    }
    private void cancelRetry() {
        retryGeneration++;
        if(cancelRetry!=null){Runnable cancel=cancelRetry;cancelRetry=null;cancel.run();}
    }
    private synchronized void focus(long token,boolean granted) {
        if(token!=activation||!active||failed)return;
        // Invalidate already detached buffers without replacing the focus request.
        epoch++;passiveEpoch(granted?PassiveObserver.FOCUS_GAIN:PassiveObserver.FOCUS_LOSS);focused=granted;discard();focusDirty=true;if(!granted)lossPending=true;schedule();
    }
    private void restartTrack(long at,long token,int reason) { int before=referenceMask();rawHead=-1;rawHeadAt=at;retainedLagReference=false;coalesceEndpoint=-1;coalesceUntil=-1;trackWritten=0;underrunObservedWritten=0;playedFloor=0;afterWrite=0;lastWriteAt=at;priming=true;required=0;platformUnderruns=-1;counterContinuous=false;quietFrom=0;quietHead=-1;phaseAt=-1;refillHead=0;playbackBound=false;passiveReferences(token,PassiveObserver.TRACK_RESET,before);passiveTrack(token,reason); }
    private void drain() {
        Output old=null,target;long token,activationToken;boolean focusChange,hasFocus,loss,work;
        synchronized(this) {
            queued=false;cancelRetry();
            if(stop){old=output;output=null;readyActivation=-1;stop=false;focusDirty=false;lossPending=false;}
            target=output;token=epoch;activationToken=activation;
            expire(now.getAsLong());work=accepting()&&(!pending.isEmpty()||prepareRequested);prepareRequested=false;
            focusChange=focusDirty;focusDirty=false;hasFocus=focused;loss=lossPending;lossPending=false;
        }
        try {
            if(old!=null){old.stop();passiveTrack(token,PassiveObserver.TRACK_STOP);}
            if(target!=null&&loss){target.suspend();restartTrack(now.getAsLong(),token(),PassiveObserver.TRACK_FLUSH);}
            if(target!=null&&focusChange&&hasFocus)target.resume();
            if(target!=null&&focusChange)synchronized(this){
                if(epoch==token&&activation==activationToken&&!stop&&!focusDirty)readyEpoch=token;
            }
            if(!work)return;
            if(target==null) {
                target=factory.create();
                synchronized(this){
                    if(activation!=activationToken||!active||failed){old=target;target=null;}
                }
                if(target==null){old.stop();passiveTrack(token(),PassiveObserver.TRACK_STOP);return;}
                final long focusToken=activationToken;
                if(!target.start(granted->focus(focusToken,granted)))
                    throw new IllegalStateException("Audio output start unavailable");
                synchronized(this){
                    // Focus callbacks invalidate PCM, but the successful request must stay alive
                    // to receive a later gain. Only an obsolete lifecycle retires this output.
                    if(activation==activationToken&&active&&!failed){
                        long at=now.getAsLong();restartTrack(at,epoch,PassiveObserver.TRACK_START);lastReport=at;
                        output=target;readyActivation=activationToken;readyEpoch=epoch;
                    }
                    else{old=target;target=null;}
                }
                if(target==null){old.stop();passiveTrack(token(),PassiveObserver.TRACK_STOP);return;}
            }
            synchronized(this){if(epoch!=token||!accepting()||pending.isEmpty())return;}
            write(target,token);
        } catch(RuntimeException|LinkageError unavailable) {
            synchronized(this){
                latency.failedInflight(inflight);inflight=0;
                if(activation==activationToken){failed=true;discard();output=null;lossPending=false;}
            }
            if(target!=null){
                try{target.reportFailure(latency.summary());}catch(RuntimeException|LinkageError ignored){}
                try{target.stop();passiveTrack(token(),PassiveObserver.TRACK_STOP);}catch(RuntimeException|LinkageError ignored){}
            }
        }
    }
    private boolean observeUnderruns(Output target,long token) {
        int count=target.underrunCount();
        counterContinuous=count>=0&&count<Integer.MAX_VALUE&&platformUnderruns>=0
                &&platformUnderruns<Integer.MAX_VALUE&&count>=platformUnderruns;
        boolean increased=counterContinuous&&count>platformUnderruns;
        if(!counterContinuous){
            int before=referenceMask();
            if(retainedLagReference){phaseAt=-1;retainedLagReference=false;}
            quietFrom=trackWritten;quietHead=-1;playbackBound=false;refillHead=-1;
            passiveReferences(token,PassiveObserver.COUNTER_DISCONTINUITY,before);
        }
        // Only writes preceding the previous observation are proven consumed. An underrun
        // may happen between our occupancy read and a write accepting a fresh prefix.
        if(increased){
            int before=referenceMask();
            playedFloor=Math.max(playedFloor,underrunObservedWritten);priming=true;phaseFrozen=true;
            playbackBound=false;
            // A count observed after accepting a fresh prefix cannot locate the stopped head.
            refillHead=playedFloor==trackWritten?trackWritten:-1;
            passiveReferences(token,PassiveObserver.COUNTER_ADVANCE,before);
        }
        platformUnderruns=count;underrunObservedWritten=trackWritten;
        return increased;
    }
    /** Retention never turns an inferred floor into head evidence, even on the next read. */
    private boolean observeRawHead(long at,long reported,long token) {
        boolean valid=reported>=0&&reported<=trackWritten;
        long head=valid?trackWritten-reported:-1;
        boolean monotone=valid&&(rawHead<0||at>=rawHeadAt&&head>=rawHead);
        if(retainedLagReference&&!monotone){
            int before=referenceMask();
            phaseAt=-1;playbackBound=false;refillHead=-1;retainedLagReference=false;
            quietFrom=trackWritten;quietHead=-1;
            passiveReferences(token,PassiveObserver.RAW_HEAD_INVALID,before);
        }
        rawHead=head;rawHeadAt=at;
        return monotone;
    }
    /** A delayed, monotone head proves consumption of the accepted zero span itself.
     * Stable epoch/grant and an unsaturated underrun counter exclude an unseen restart.
     * Sub-ms read ordering needs a separate 47-frame clock-granularity margin.
     * The current head minus that margin proves consumed zeros, without a head-delay bound. */
    private boolean playedQuiet(long at,long reported,boolean valid,long token) {
        long head=trackWritten-reported,offset=at*FRAMES_PER_MS-head;
        valid=valid&&counterContinuous;
        boolean backwards=quietHead>=0&&(at<quietAt||head<quietHead-(at==quietAt?FRAMES_PER_MS-1:0));
        if(backwards){int before=referenceMask();valid=false;phaseAt=-1;playbackBound=false;refillHead=-1;passiveReferences(token,PassiveObserver.QUIET_BACKWARDS,before);}
        if(valid&&playbackBound&&offset<playbackOffsetLower-(FRAMES_PER_MS-1)){int before=referenceMask();playbackBound=false;passiveReferences(token,PassiveObserver.BOUND_CONTRADICTION,before);}
        if(!valid||quietHead<0)quietFrom=trackWritten;
        quietHead=valid?head:-1;quietAt=at;
        return valid&&head-(FRAMES_PER_MS-1)-quietFrom>=QUIET_RECOVERY_FRAMES;
    }
    /** Called under the pump lock, after full writes and a stable second output observation. */
    private void trimQuiet(long at,long reported) {
        if(phaseAt<0||!playbackBound)return;
        if(!producerSeen||!producerValid)return;
        long debt;
        try {
            long logical=Math.multiplyExact(completedMs,(long)FRAMES_PER_MS);
            long currentCredit=Math.subtractExact(logical,Math.multiplyExact(at,(long)FRAMES_PER_MS));
            long missing=Math.addExact(Math.max(0,Math.subtractExact(phaseCredit,currentCredit)),missingPcm);
            debt=Math.subtractExact(Math.subtractExact(Math.subtractExact(
                    playbackOffsetLower,phaseOffset),
                    retiredFrames-phaseRetired),missing);
        } catch(ArithmeticException overflow){producerValid=false;return;}
        if(debt<=0)return;
        // A tone anywhere in pending vetoes recovery, including one offered during a write.
        for(Batch batch:pending)for(int i=batch.offset;i<batch.end;i++)if(batch.samples[i]!=0)return;
        latency.trimmed(take(Math.min(debt,pendingFrames)));
    }
    /** Pending audio waits for room in sequence; quiet recovery rechecks the output after writing. */
    private void write(Output target,long token) {
        long at=now.getAsLong();
        // These observations run on the output executor. Invalid grants fail audio, never guest execution.
        int nextRequired=target.startThresholdFrames(),nextWritable=target.writableFrames();
        int nextRoute=target.routeId();
        if(nextRequired<1||nextRequired>MAX_PENDING_FRAMES||nextWritable<nextRequired)
            throw new IllegalStateException("Unreachable audio start threshold");
        boolean changed=nextRequired!=required||nextWritable!=writable||nextRoute!=route;
        if(changed&&required!=0){int before=referenceMask();playbackBound=false;refillHead=-1;retainedLagReference=false;passiveReferences(token,PassiveObserver.GRANT_CHANGE,before);}
        boolean platformStarved=observeUnderruns(target,token);
        required=nextRequired;writable=nextWritable;route=nextRoute;
        if(changed||platformStarved)priming=true;
        long reported=target.queuedFrames();
        boolean monotoneHead=observeRawHead(at,reported,token);
        boolean known=reported>=0&&reported<=trackWritten;
        long played=Math.max(0,at-lastWriteAt)*FRAMES_PER_MS;
        // Before a verified real fill, elapsed time is not evidence that playback has started.
        long occupancy=known?reported:Math.max(0,afterWrite-(priming?0:played));
        // A quantized head can trail a proven underrun boundary. Keep later accepted
        // prefixes, and exclude this inferred occupancy from onset timing.
        boolean boundedUnderrunHead=known&&trackWritten-reported<playedFloor
                &&playedFloor-(trackWritten-reported)<=HEAD_BOUND;
        boolean retainReferences=known&&trackWritten-reported<playedFloor
                &&monotoneHead&&counterContinuous&&!changed;
        if(retainReferences&&!boundedUnderrunHead)retainedLagReference=true;
        if(known&&trackWritten-reported<playedFloor){
            PassiveObserver o=passive();if(o!=null)try{o.headLag(token,playedFloor-(trackWritten-reported));}catch(Throwable ignored){}
            occupancy=trackWritten-playedFloor;known=false;}
        else if(platformStarved&&!known)occupancy=Math.min(occupancy,trackWritten-playedFloor);
        boolean starved=trackWritten>0&&occupancy==0&&played>afterWrite;
        if(known&&starved){int before=referenceMask();refillHead=trackWritten;playbackBound=false;phaseFrozen=true;passiveReferences(token,PassiveObserver.KNOWN_EMPTY,before);}
        if(changed||platformStarved||known&&starved)priming=true;
        int ceiling=Math.min(writable,priming?Math.max(normalQueueFrames,required):normalQueueFrames);
        int beforeReferences=referenceMask();
        if(changed||!known&&!boundedUnderrunHead&&!retainReferences)phaseAt=-1;
        if(!known&&!boundedUnderrunHead&&!retainReferences){playbackBound=false;refillHead=-1;retainedLagReference=false;}
        if(changed||!known&&!boundedUnderrunHead&&!retainReferences)
            passiveReferences(token,changed?PassiveObserver.GRANT_CHANGE:PassiveObserver.UNKNOWN_HEAD,beforeReferences);
        else if(retainReferences)passiveReferences(token,PassiveObserver.DELAYED_HEAD,beforeReferences);
        boolean recover=playedQuiet(at,reported,known&&!priming&&occupancy>0&&!changed&&!platformStarved,token);
        synchronized(this) {
            long offset=at*FRAMES_PER_MS-(trackWritten-reported);
            if(known&&!priming&&!changed&&!platformStarved&&phaseAt>=0&&!phaseFrozen&&Math.abs(offset-phaseOffset)<=HEAD_BOUND){
                phaseOffset=Math.max(phaseOffset,offset);
                if(producerSeen&&producerValid)phaseCredit=sourceCredit;
            }
        }
        long written=0;boolean first=true,shortWrite=false;
        while(true) {
            Batch slice;
            synchronized(this) {
                if(epoch!=token||!accepting())break;
                if(first){first=false;expire(at);}
                long room=ceiling-occupancy-written;
                // Optional quiet coalescing ends on sufficient real PCM, a pending note,
                // or one retry deadline. The endpoint key survives repeated underrun reads.
                boolean defer=refillHead>=0&&trackWritten>0&&producerSeen&&producerValid
                        &&counterContinuous&&(known||boundedUnderrunHead)&&occupancy+written+pendingFrames<required&&!pending.isEmpty();
                if(defer)for(Batch batch:pending){
                    // A partially accepted batch may drain again before its retry. Its suffix
                    // has already used its window and must not be deferred a second time.
                    if(batch.deferred&&batch.pauseAt<0){defer=false;break;}
                    for(int i=batch.offset;i<batch.end;i++)if(batch.samples[i]!=0){defer=false;break;}
                    if(!defer)break;
                }
                if(defer){
                    if(coalesceEndpoint!=trackWritten){
                        coalesceEndpoint=trackWritten;coalesceAt=at;coalesceUntil=at+RETRY_MS;
                    }
                    if(at>=coalesceAt&&at<coalesceUntil){
                        for(Batch batch:pending)if(!batch.deferred){batch.deferred=true;batch.pauseAt=at;}
                        break;
                    }
                }
                finishCoalescing(at);
                if(pending.isEmpty()||room<=0)break;
                Batch head=pending.peekFirst();int n=(int)Math.min(room,head.remaining());
                slice=new Batch(head,head.offset,head.offset+n);
                head.offset+=n;pendingFrames-=n;if(head.remaining()==0)pending.removeFirst();inflight=n;latency.detached(n);
            }
            long beforeWrite=now.getAsLong();
            int accepted=target.write(slice.samples,slice.offset,slice.remaining());
            long acceptedAt=now.getAsLong();
            if(accepted<0||accepted>slice.remaining())throw new IllegalStateException("Audio output contract");
            int rest=slice.remaining()-accepted;
            synchronized(this) {
                inflight=0;
                boolean restore=epoch==token&&accepting();
                latency.completed(slice.sequence,slice.offset,accepted,rest,at,known?occupancy+written:-1,!known,restore);
                // Only accepted samples define the track history; a refused suffix stays pending.
                for(int i=accepted-1;i>=0;i--)if(slice.samples[slice.offset+i]!=0){quietFrom=trackWritten+written+i+1;break;}
                if(refillHead>=0&&accepted>0){
                    try {
                        if(beforeWrite<at||acceptedAt<beforeWrite)throw new ArithmeticException("backward clock");
                        playbackOffsetLower=Math.subtractExact(Math.multiplyExact(beforeWrite,(long)FRAMES_PER_MS),
                                Math.addExact(trackWritten,written));
                        playbackBound=true;
                    } catch(ArithmeticException invalid){playbackBound=false;}
                    refillHead=-1;
                }
                written+=accepted;
                if(rest>0) {
                    shortWrite=true;latency.shortWrite(accepted==0);
                    if(restore){pending.addFirst(new Batch(slice,slice.offset+accepted,slice.end));pendingFrames+=rest;bound();}
                    break;
                }
            }
        }
        if(occupancy+written>=required)priming=false;
        if(written>0) {
            if(starved)latency.underrun();
            trackWritten+=written;afterWrite=occupancy+written;lastWriteAt=at;latency.queued(afterWrite);
            boolean underrun=observeUnderruns(target,token);
            if(!shortWrite&&!priming&&(recover||phaseAt<0)) {
                int checkRequired=target.startThresholdFrames(),checkWritable=target.writableFrames(),checkRoute=target.routeId();
                long checkQueued=target.queuedFrames(),checkAt=now.getAsLong();
                observeRawHead(checkAt,checkQueued,token);
                underrun=observeUnderruns(target,token)||underrun;
                boolean stable=!underrun&&checkRequired==required&&checkWritable==writable&&checkRoute==route
                        &&checkQueued>0&&checkQueued<=trackWritten&&checkAt>=at;
                if(!stable){
                    int before=referenceMask();phaseAt=-1;
                    if(retainedLagReference){playbackBound=false;refillHead=-1;retainedLagReference=false;}
                    passiveReferences(token,PassiveObserver.UNSTABLE_FILL,before);
                }
                boolean quiet=playedQuiet(checkAt,checkQueued,stable,token);
                synchronized(this) {
                    if(stable&&epoch==token&&accepting()) {
                        // Capture only after a real fill. A proven underrun keeps this reference,
                        // so its lost playback time remains measurable after quiet refill.
                        long offset=checkAt*FRAMES_PER_MS-(trackWritten-checkQueued);
                        if(phaseAt<0){phaseAt=checkAt;phaseOffset=offset;phaseRetired=retiredFrames;
                            phaseCredit=sourceCredit;phaseFrozen=false;
                            PassiveObserver o=passive();if(o!=null)try{o.stablePhase(token,pendingFrames);}catch(Throwable ignored){}
                        }
                        // Learn the conservative healthy head envelope before playback loss.
                        // An underrun freezes this reference even when its loss fits HEAD_BOUND.
                        // Only matched completed requests may establish source progress.
                        if(!phaseFrozen&&Math.abs(offset-phaseOffset)<=HEAD_BOUND){
                            phaseOffset=Math.max(phaseOffset,offset);
                            if(producerSeen&&producerValid)phaseCredit=sourceCredit;
                        }
                        if(recover&&quiet)trimQuiet(checkAt,checkQueued);
                    }
                }
            }
            if(underrun){quietFrom=trackWritten;quietHead=-1;}
        }
        if(shortWrite){quietFrom=trackWritten;quietHead=-1;}
        // Remaining audio is retried later, never by spinning; expiry ends a refused retry chain.
        synchronized(this){if(epoch==token&&accepting()&&!pending.isEmpty())retry();}
        if(at-lastReport>=REPORT_MS) {
            lastReport=at;
            PassiveObserver o=passive();if(o!=null)try{o.summary(token,latency.summary(),platformUnderruns);}catch(Throwable ignored){}
            try{target.report(latency.summary());}catch(RuntimeException|LinkageError ignored){ /* Telemetry never affects audio. */ }
        }
    }

    /** One onset's measurement; firmware reaction is a correlation, not proof of causation. */
    static final class Onset {
        final long sequence;final boolean correlated;final double firmwareReactionMs,softwareMs;
        Onset(long sequence,boolean correlated,double firmwareReactionMs,double softwareMs){
            this.sequence=sequence;this.correlated=correlated;this.firmwareReactionMs=firmwareReactionMs;this.softwareMs=softwareMs;
        }
    }

    /** Scalar telemetry only: counters plus a fixed 32-onset window. Never holds PCM. */
    static final class Latency {
        private static final int WINDOW=32,MIN_SAMPLES=5;
        private final double[] software=new double[WINDOW],firmware=new double[WINDOW];
        private int samples;
        private long pending,inflight,failures,offered,written,flushed,shortWrites,zeroWrites,retries,trimmed,dropped,expired,underruns,maxQueued,unknownWrites,onsets,uncorrelated;
        // Press-down contacts since the last onset; a release before the onset voids the candidate.
        private int downs;private boolean releasedBeforeOnset,silentAtApply;private long admittedAt,progressAt;
        // Recent writes {sequence, first frame, frames, write time, queued before or -1}, for onsets reported after their write.
        private final long[][] recent=new long[WINDOW][5];private int recentCount;
        private long waitingSequence=-1,waitingAdmitted;private int waitingIndex;private double waitingFirmware;
        private Onset last;

        /** An applied press-down edge whose silence is vouched for by the caller. */
        void applied(long admittedMs,long appliedMs,long progressMs) { applied(admittedMs,appliedMs,progressMs,true); }
        /** An applied press-down edge. Only the first contact since the last onset is a candidate. */
        synchronized void applied(long admittedMs,long appliedMs,long progressMs,boolean guestSilent) {
            if(++downs==1){admittedAt=admittedMs;progressAt=progressMs;silentAtApply=guestSilent;releasedBeforeOnset=false;}
        }
        /** A release edge never arms a candidate. Before an onset it voids the pending press; an observed onset keeps its snapshot. */
        synchronized void released() { if(downs>0)releasedBeforeOnset=true; }
        /** First non-zero sample after an applied input: its batch, index and the guest progress at batch start. */
        synchronized void onset(long sequence,int onsetIndex,long batchStartProgressMs) {
            onsets++;
            boolean candidate=downs==1&&!releasedBeforeOnset&&silentAtApply;
            double reaction=downs>0?batchStartProgressMs+onsetIndex/(double)FRAMES_PER_MS-progressAt:Double.NaN;
            downs=0;releasedBeforeOnset=false;
            // An uncorrelated onset is only counted; an earlier onset still awaiting its write keeps its slot.
            if(!candidate){uncorrelated(sequence,reaction);return;}
            loseWaiting();
            // Later edges reuse admittedAt, so the waiting onset keeps its own copy.
            waitingSequence=sequence;waitingIndex=onsetIndex;waitingFirmware=reaction;waitingAdmitted=admittedAt;
            for(int i=0;i<Math.min(recentCount,WINDOW)&&waitingSequence>=0;i++)match(recent[i]);
            // A later batch already written means the onset frames were trimmed or dropped.
            if(waitingSequence>=0)for(int i=0;i<Math.min(recentCount,WINDOW);i++)if(recent[i][0]>sequence){loseWaiting();break;}
        }
        synchronized Onset lastOnset() { return last; }
        synchronized Summary summary() {
            int n=Math.min(samples,WINDOW);
            if(n<MIN_SAMPLES)return new Summary(this,Double.NaN,Double.NaN,Double.NaN,Double.NaN);
            double[] s=Arrays.copyOf(software,n),f=Arrays.copyOf(firmware,n);Arrays.sort(s);Arrays.sort(f);
            return new Summary(this,rank(s,0.50),rank(s,0.95),s[n-1],rank(f,0.95));
        }
        private static double rank(double[] sorted,double p) { return sorted[(int)Math.ceil(p*sorted.length)-1]; }
        private void match(long[] w) {
            if(w[0]!=waitingSequence)return;
            if(waitingIndex>=w[1]&&waitingIndex<w[1]+w[2]) {
                long sequence=waitingSequence;waitingSequence=-1;
                if(w[4]<0){uncorrelated(sequence,waitingFirmware);return;}
                double scheduled=w[3]+(w[4]+waitingIndex-w[1])/(double)FRAMES_PER_MS;
                double value=scheduled-waitingAdmitted-waitingFirmware;
                software[samples%WINDOW]=value;firmware[samples%WINDOW]=waitingFirmware;samples++;
                last=new Onset(sequence,true,waitingFirmware,value);
            } else if(w[1]>waitingIndex)loseWaiting();
        }
        private void uncorrelated(long sequence,double reaction) { uncorrelated++;last=new Onset(sequence,false,reaction,Double.NaN); }
        private void loseWaiting() { if(waitingSequence>=0){long sequence=waitingSequence;waitingSequence=-1;uncorrelated(sequence,waitingFirmware);} }
        synchronized void wrote(long sequence,int from,int frames,long at,long queuedBefore,boolean unknown) {
            written+=frames;if(unknown)unknownWrites++;
            long[] w=recent[recentCount++%WINDOW];w[0]=sequence;w[1]=from;w[2]=frames;w[3]=at;w[4]=queuedBefore;
            if(waitingSequence>=0){if(sequence>waitingSequence)loseWaiting();else match(w);}
        }
        synchronized void discard() { downs=0;releasedBeforeOnset=false;loseWaiting(); }
        synchronized void detached(long n) { pending-=n;inflight+=n; }
        synchronized void completed(long sequence,int from,int accepted,int rest,long at,long queuedBefore,boolean unknown,boolean restore) {
            inflight-=accepted+rest;
            if(accepted>0)wrote(sequence,from,accepted,at,queuedBefore,unknown);
            if(restore)pending+=rest;else flushed+=rest;
        }
        synchronized void failedInflight(long n) { inflight-=n;flushed+=n;failures++; }
        synchronized void offered(long n) { offered+=n;pending+=n; }
        synchronized void flushed(long n) { flushed+=n;pending-=n; }
        synchronized void dropped(long n) { dropped+=n;pending-=n; }
        synchronized void trimmed(long n) { trimmed+=n;pending-=n; }
        synchronized void expired(long n) { expired+=n;pending-=n; }
        synchronized void shortWrite(boolean zero) { if(zero)zeroWrites++;else shortWrites++; }
        synchronized void retried() { retries++; }
        synchronized void underrun() { underruns++; }
        synchronized void queued(long frames) { maxQueued=Math.max(maxQueued,frames); }
    }

    /** Immutable scalar snapshot. Latency fields are NaN (printed insufficient) below five correlated onsets. */
    static final class Summary {
        // flushedFrames counts unsent pending/inflight only. Track flushes never count accepted frames twice.
        final long pendingFrames,inflightFrames,outputFailures,offeredFrames,writtenFrames,flushedFrames,shortWrites,zeroWrites,retries,trimmedFrames,droppedFrames,expiredFrames,
                underruns,maxQueuedFrames,unknownOccupancyWrites,onsets,uncorrelatedOnsets;
        final double p50SoftwareMs,p95SoftwareMs,maxSoftwareMs,p95FirmwareReactionMs;
        private Summary(Latency l,double p50,double p95,double max,double firmware) {
            pendingFrames=l.pending;inflightFrames=l.inflight;outputFailures=l.failures;
            offeredFrames=l.offered;writtenFrames=l.written;flushedFrames=l.flushed;shortWrites=l.shortWrites;zeroWrites=l.zeroWrites;
            retries=l.retries;trimmedFrames=l.trimmed;droppedFrames=l.dropped;expiredFrames=l.expired;underruns=l.underruns;
            maxQueuedFrames=l.maxQueued;unknownOccupancyWrites=l.unknownWrites;onsets=l.onsets;uncorrelatedOnsets=l.uncorrelated;
            p50SoftwareMs=p50;p95SoftwareMs=p95;maxSoftwareMs=max;p95FirmwareReactionMs=firmware;
        }
        private static String ms(double value) { return Double.isNaN(value)?"insufficient":String.format(Locale.ROOT,"%.1f",value); }
        String line() {
            return "pendingFrames="+pendingFrames+" inflightFrames="+inflightFrames+" outputFailures="+outputFailures+" offeredFrames="+offeredFrames+" writtenFrames="+writtenFrames+" flushedFrames="+flushedFrames+" shortWrites="+shortWrites
                    +" zeroWrites="+zeroWrites+" retries="+retries+" trimmedFrames="+trimmedFrames+" droppedFrames="+droppedFrames
                    +" expiredFrames="+expiredFrames+" underruns="+underruns+" maxQueuedFrames="+maxQueuedFrames
                    +" unknownOccupancyWrites="+unknownOccupancyWrites+" onsets="+onsets+" uncorrelatedOnsets="+uncorrelatedOnsets
                    +" p50SoftwareMs="+ms(p50SoftwareMs)+" p95SoftwareMs="+ms(p95SoftwareMs)+" maxSoftwareMs="+ms(maxSoftwareMs)
                    +" p95FirmwareReactionMs="+ms(p95FirmwareReactionMs);
        }
    }

    /** Track occupancy from a 32-bit playback head, per flush epoch. Implausible readings are unknown (-1). */
    static final class Occupancy {
        private long written,played;private int last;
        void reset(int head) { written=0;played=0;last=head; }
        void wrote(int frames) { if(frames>0)written+=frames; }
        long queued(int head) {
            long next=played+((head-last)&0xFFFFFFFFL);
            if(next>written)return -1;
            played=next;last=head;return written-played;
        }
    }
}
