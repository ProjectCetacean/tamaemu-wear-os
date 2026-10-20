package io.github.projectcetacean.tamaemu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** The worker is the only native owner. Public entry points only capture boundaries and enqueue. */
final class LifeOwner {
    private final EmulatorSession.Backend backend;
    private final EmulatorSession.Worker worker;
    private final Executor main;
    private final Clock clock;
    private final CommitObserver observer;
    private final String romPath,savePath;
    private final File marker;
    private final AtomicLong epoch=new AtomicLong();
    private volatile boolean foregroundWanted;
    private volatile StopTicket stopTicket;
    private static final int INPUT_PRESS_LIMIT=64;
    // At most64 queued edges plus the three admitted contacts' releases. New
    // contacts are refused under pressure; accepted contacts retain their release.
    private final ArrayDeque<ButtonBoundary> inputBoundaries=new ArrayDeque<>();
    private final long[] admittedContact=new long[3],nativeContact=new long[3];
    private long nextContactId,ownerDownAttempts,admissionRefused,admitted,
            deliveryRefusedNativeHeld,deliveredNativeDowns,canceledBeforeDelivery,pending;
    /** Aggregate owner mask-edge attempts, not physical contacts or ROM actions. Never persisted.
     * attempts=admitted+admissionRefused; admitted=pending+heldRefused+canceled+delivered.
     * Pending means DOWN awaiting application, not outstanding UP or a delivered hold.
     * Delivered remains cumulative after release/cancellation; it is never counted again as canceled.
     */
    static final class InputStats {
        final long ownerDownAttempts,admissionRefused,admitted,deliveryRefusedNativeHeld,
                deliveredNativeDowns,canceledBeforeDelivery,pending;
        final int heldMask;
        InputStats(long attempts,long refused,long admitted,long heldRefused,long delivered,long canceled,long pending,int held) {
            ownerDownAttempts=attempts;admissionRefused=refused;this.admitted=admitted;
            deliveryRefusedNativeHeld=heldRefused;deliveredNativeDowns=delivered;
            canceledBeforeDelivery=canceled;this.pending=pending;heldMask=held;
        }
    }
    // Optional extension keeps existing CommitObserver implementations and lambdas compatible.
    interface InputObserver extends CommitObserver {
        default void inputStats(InputStats stats,long inputId,int button,String reason,long guestProgress) {}
    }
    private synchronized void publishInput(long id,int button,String reason) {
        if(!(observer instanceof InputObserver))return;
        int held=0;for(int i=0;i<3;i++)if(nativeContact[i]!=0)held|=1<<i;
        InputStats snapshot=new InputStats(ownerDownAttempts,admissionRefused,admitted,
                deliveryRefusedNativeHeld,deliveredNativeDowns,canceledBeforeDelivery,pending,held);
        try{((InputObserver)observer).inputStats(snapshot,id,button,reason,progressed);}
        catch(Throwable unavailable){/* Optional diagnostics cannot interrupt input or care. */}
    }
    private boolean inputAdmission,inputWakeQueued;
    private static final class ButtonBoundary {
        final Boundary boundary;final long token,id;final int button;final boolean down;
        ButtonBoundary(Boundary boundary,long token,long id,int button,boolean down) {
            this.boundary=boundary;this.token=token;this.id=id;this.button=button;this.down=down;
        }
    }
    // Worker-owned state follows.
    private long handle,lastCheckpoint,savedUtc,pumpGeneration,pumpDue,lastBoundary;
    private long boundarySequence;
    // Presence is independent of the native save candidate. Capture transitions even while
    // that candidate is frozen, then apply them in order only after its save succeeds.
    private final ArrayDeque<PresenceBoundary> presenceBoundaries=new ArrayDeque<>();
    private boolean knownForeground;
    private static final class PresenceBoundary {
        final Boundary boundary;final boolean activeAfter;
        PresenceBoundary(Boundary boundary,boolean activeAfter){this.boundary=boundary;this.activeAfter=activeAfter;}
    }
    private static final class Boundary {
        final ClockSample sample;final long sequence;
        Boundary(ClockSample sample,long sequence){this.sample=sample;this.sequence=sequence;}
    }
    private synchronized Boundary capture(){boundarySequence=Math.addExact(boundarySequence,1);return new Boundary(clock.sample(),boundarySequence);}
    private synchronized Boundary capturePresence(boolean activeAfter) {
        Boundary boundary=capture();
        foregroundWanted=activeAfter;
        clearPendingInput(activeAfter?"FOREGROUND_CHANGE":"PAUSED");
        presenceBoundaries.addLast(new PresenceBoundary(boundary,activeAfter));
        return boundary;
    }
    private synchronized void drainPresence() {
        if(ledger==null||candidate!=null)return;
        while(!presenceBoundaries.isEmpty()) {
            PresenceBoundary transition=presenceBoundaries.removeFirst();
            account(transition.boundary,knownForeground);
            knownForeground=transition.activeAfter;
        }
    }
    private synchronized void accountCurrent() {
        drainPresence();
        account(capture(),knownForeground);
    }
    private void account(Boundary boundary,boolean active) {
        if(boundary.sequence<=lastBoundary)return;
        ledger=LifeLedger.account(ledger,boundary.sample,active);lastBoundary=boundary.sequence;
    }
    private LifeRecord ledger,committed,candidate;
    private int[] pixels,candidateFrame;
    private int width,height;
    private boolean foreground,pumpQueued,catching,markerChecked,markerOwned,recoveryRequired,nativeBroken;
    // Attachment/recovery, jobs and mode changes need a durable settlement. Routine
    // foreground backlog (including save latency) only needs to catch up before input.
    private boolean checkpointAfterCatchup;
    private boolean inputContinuity;
    private boolean schedulerFailed,accountingBlocked;
    private String scheduler="Not scheduled";
    private LifeStatus.OwnerState state=LifeStatus.OwnerState.CLOSED;
    private EmulatorSession.Listener listener;
    private JobLease job;
    private JobCompletion jobCompletion;
    private long jobDeadline;
    private ModeCallback modeCallback;
    private Boolean nextMode;
    private final long[] pressedAt=new long[3],releaseAtProgress=new long[3];
    private volatile long progressed;
    private long framePendingEpoch=-1;
    // Progress is presentation only. State changes keep their immediate delivery;
    // a stalled main thread retains one latest progress snapshot for this state.
    private final Object progressLock=new Object();
    private ProgressPublication pendingProgress;
    private boolean progressDelivered;
    private long progressDeliveredAt;
    private static final class ProgressPublication {
        final EmulatorSession.Listener target;final long token;
        LifeStatus snapshot;
        ProgressPublication(EmulatorSession.Listener target,long token,LifeStatus snapshot) {
            this.target=target;this.token=token;this.snapshot=snapshot;
        }
    }
    private static final class StopTicket {
        final CountDownLatch done=new CountDownLatch(1);
        volatile EmulatorSession.StopResult result=EmulatorSession.StopResult.SAVE_FAILED;
        void finish(EmulatorSession.StopResult r){result=r;done.countDown();}
    }
    LifeOwner(EmulatorSession.Backend backend,EmulatorSession.Worker worker,Executor main,
              String rom,String save,Clock clock,CommitObserver observer) {
        if(clock==null||observer==null)throw new IllegalArgumentException("Missing life dependency");
        this.backend=backend;this.worker=worker;this.main=main;romPath=rom;savePath=save;
        this.clock=clock;this.observer=observer;marker=new File(save+".running");
    }
    void resume(EmulatorSession.Listener target,boolean acknowledge) {
        Boundary boundary=capturePresence(true);
        long token=epoch.incrementAndGet();stopTicket=null;
        worker.execute(()->beginForeground(token,target,boundary,acknowledge));
    }
    private void beginForeground(long token,EmulatorSession.Listener target,Boundary boundary,boolean acknowledge) {
        if(epoch.get()!=token||!foregroundWanted)return;
        listener=target;legacyState(EmulatorSession.State.LOADING);
        if(accountingBlocked){accountingError();return;}
        checkMarker();
        if(nativeBroken) {
            if(!acknowledge){recovery();return;}
            // Only an explicit recovery action may discard partially mutated guest state.
            if(!new File(savePath).isFile()){legacyState(EmulatorSession.State.RECOVERY_MISSING_SAVE);return;}
            closeNative();candidate=null;candidateFrame=null;ledger=null;committed=null;nativeBroken=false;
            recoveryRequired=true;
        }
        boolean freezeProtected=false;
        if(recoveryRequired) {
            if(!new File(savePath).isFile()){legacyState(EmulatorSession.State.RECOVERY_MISSING_SAVE);return;}
            if(!acknowledge){recovery();return;}
            freezeProtected=true;recoveryRequired=false;markerOwned=marker.isFile();
        }
        if(candidate!=null&&!persist())return;
        if(!open(boundary))return;
        try {
            if(freezeProtected&&ledger.mode==1){ledger=LifeLedger.reanchor(ledger,boundary.sample);lastBoundary=boundary.sequence;}
            drainPresence();
            foreground=true;
            if(!releaseButtons("FOREGROUND_CHANGE"))return;
            if(job!=null&&!persist()){finishJob();return;}
            finishJob();invalidatePump();catching=true;checkpointAfterCatchup=true;setState(LifeStatus.OwnerState.CATCHING_UP);ensurePump(0);
        } catch(RuntimeException error){accountingError();}
    }
    void pause() {
        capturePresence(false);epoch.incrementAndGet();
        StopTicket ticket=new StopTicket();stopTicket=ticket;
        worker.execute(()->{
            EmulatorSession.StopResult result=EmulatorSession.StopResult.SAVE_FAILED;
            try {
                if(handle==0){result=EmulatorSession.StopResult.SAVED;return;}
                if(nativeBroken||accountingBlocked)return;
                foreground=false;listener=null;catching=false;invalidatePump();
                rejectMode();
                // Presence transitions are already retained outside the frozen native candidate.
                // Do not mutate keys, guest or ledger until that exact candidate succeeds.
                if(candidate!=null&&!persist()){finishJob();return;}
                drainPresence();
                if(!releaseButtons("PAUSED")||!persist()){finishJob();return;}
                finishJob();
                if(!clearMarker())return;
                closeNative();
                result=EmulatorSession.StopResult.SAVED;
            } catch(RuntimeException failure){accountingError();}
            finally{ticket.finish(result);}
        });
    }
    EmulatorSession.StopResult stopAndAwait(int budget) {
        if(budget<0||budget>EmulatorSession.STOP_BUDGET_MILLIS)throw new IllegalArgumentException("Invalid stop budget");
        StopTicket ticket=stopTicket;if(ticket==null){pause();ticket=stopTicket;}
        try{return ticket.done.await(budget,TimeUnit.MILLISECONDS)?ticket.result:EmulatorSession.StopResult.TIMED_OUT;}
        catch(InterruptedException e){Thread.currentThread().interrupt();return EmulatorSession.StopResult.INTERRUPTED;}
    }
    void background(JobLease lease,long budget,JobCompletion completion) {
        if(lease==null||completion==null||budget<=0)throw new IllegalArgumentException("Invalid job request");
        Boundary boundary=capture();
        worker.execute(()->{
            if(lease.isCancelled())return;
            if(foregroundWanted||foreground||job!=null){complete(lease,completion);return;}
            job=lease;jobCompletion=completion;
            try{jobDeadline=Math.addExact(worker.nowMillis(),budget);}
            catch(ArithmeticException error){finishJob();return;}
            checkMarker();
            if(accountingBlocked){accountingError();finishJob();return;}
            if(recoveryRequired||nativeBroken){recovery();finishJob();return;}
            if(candidate!=null&&!persist()){finishJob();return;}
            if(!open(boundary)){finishJob();return;}
            if(!releaseButtons("BACKGROUND")){finishJob();return;}
            try{drainPresence();account(boundary,knownForeground);catching=true;checkpointAfterCatchup=true;setState(LifeStatus.OwnerState.CATCHING_UP);ensurePump(0);}
            catch(RuntimeException error){accountingError();finishJob();}
        });
    }
    void cancel(JobLease lease) {
        worker.execute(()->{if(job==lease)ensurePump(0);});
    }
    void requestMode(boolean protect,ModeCallback callback) {
        if(callback==null)throw new IllegalArgumentException("Missing mode callback");
        Boundary boundary=capture();long token=epoch.get();
        worker.execute(()->{
            if(epoch.get()!=token||!foregroundWanted||!foreground||state!=LifeStatus.OwnerState.PLAYING||candidate!=null||nextMode!=null||ledger.debtMs!=0||hasPendingInput()) {
                modeResult(callback,false);return;
            }
            try {
                drainPresence();account(boundary,knownForeground);
                if(!releaseButtons("MODE_CHANGE")){modeResult(callback,false);return;}
                nextMode=protect;modeCallback=callback;catching=true;checkpointAfterCatchup=true;invalidatePump();
                setState(LifeStatus.OwnerState.CATCHING_UP);ensurePump(0);
            }catch(RuntimeException failure){accountingError();modeResult(callback,false);}
        });
    }
    void reconcileScheduler(LifeScheduler scheduler) {
        worker.execute(()->{
            checkMarker();
            if(recoveryRequired||nativeBroken){schedulerStatus("Recovery required before aging",false);return;}
            if(handle!=0){if(candidate==null&&committed!=null)scheduler.reconcile(committed);return;}
            long probe=0;
            try {
                // Read-only startup reconciliation shares the worker; it never creates a marker,
                // initializes old metadata, releases input or advances the pet.
                probe=backend.open(romPath,savePath);
                if(probe==0)throw new IOException("No native session");
                scheduler.reconcile(LifeRecord.from(backend.readLife(probe)));
            }catch(Exception|LinkageError unavailable){schedulerStatus("Aging unavailable until pet opens",false);}
            finally{if(probe!=0)try{backend.close(probe);}catch(RuntimeException|LinkageError ignored){}}
        });
    }
    void schedulerStatus(String status,boolean failed) {
        worker.execute(()->{scheduler=status;schedulerFailed=failed;publishStatus();});
    }
    private boolean open(Boundary boundary) {
        if(handle!=0)return true;
        try {
            handle=backend.open(romPath,savePath);if(handle==0)throw new IOException("No native session");
            width=backend.width(handle);height=backend.height(handle);
            if(width!=128||height!=128)throw new IOException("Invalid LCD size");
            pixels=new int[width*height];committed=LifeRecord.from(backend.readLife(handle));ledger=committed;
            if(!releaseButtons("OPEN"))return false;
            if(ledger.schema==0) {
                // The first successful feature checkpoint establishes the baseline, with no past debt.
                committed=null;Boundary initial=capture();ledger=LifeLedger.initialize(initial.sample);lastBoundary=initial.sequence;
                if(!persist())return false;
            }
            return true;
        }catch(Exception|LinkageError error){
            if(!nativeBroken)closeNative();
            legacyState(new File(romPath).isFile()?EmulatorSession.State.OPEN_FAILED:EmulatorSession.State.MISSING_FIRMWARE);return false;
        }
    }
    private void invalidatePump(){pumpGeneration++;pumpQueued=false;}
    private void ensurePump(int delay) {
        if(handle==0||nativeBroken||accountingBlocked||candidate!=null)return;
        if(pumpQueued) {
            if(delay!=0||pumpDue<=worker.nowMillis())return;
            invalidatePump();
        }
        pumpQueued=true;pumpDue=worker.nowMillis()+delay;long generation=pumpGeneration;worker.later(()->{if(generation!=pumpGeneration)return;pumpQueued=false;pump();},delay);
    }
    private void pump() {
        if(handle==0||nativeBroken||accountingBlocked||candidate!=null)return;
        try {
            if(job!=null&&(job.isCancelled()||foregroundWanted||worker.nowMillis()>=jobDeadline)) {
                if(!persist()){finishJob();return;}
                finishJob();
                if(!foregroundWanted&&!foreground){closeSavedBackground();return;}
            }
            // Pause invalidates work before its queued boundary handler gets the worker.
            if(foreground&&!foregroundWanted)return;
            if(!foreground&&job==null)return;
            // Foreground worker jitter is not away replay. Stop accounting at the
            // oldest captured input boundary until its preceding debt is consumed.
            ButtonBoundary input=nextMode==null?accountPumpBoundary():null;
            if(ledger.debtMs>0) {
                int quantum=(int)Math.min(100,ledger.debtMs);
                for(long release:releaseAtProgress)if(release>progressed)quantum=(int)Math.min(quantum,release-progressed);
                long afterProgress=Math.addExact(progressed,quantum);
                if(catching||!foreground)backend.headless(handle,quantum);
                else backend.advanceWithDebt(handle,quantum,pixels,ledger.debtMs);
                ledger=LifeLedger.consume(ledger,quantum);progressed=afterProgress;
                releaseDueButtons();
                if(catching)publishReplayProgress();else publishStatus();
                if(!catching&&foreground&&foregroundWanted)publishFrame();
                if(worker.nowMillis()-lastCheckpoint>=EmulatorSession.CHECKPOINT_MILLIS&&!persist()) { rejectMode();finishJob();return; }
                // One native call per queued turn: lifecycle/cancel/input always gets a boundary.
                if(ledger.debtMs>0){ensurePump(0);return;}
            }
            if(!catching&&worker.nowMillis()-lastCheckpoint>=EmulatorSession.CHECKPOINT_MILLIS&&!persist())return;
            boolean inputApplied=input!=null&&applyPendingInput(input);
            if(catching) {
                if(nextMode!=null)ledger=LifeLedger.mode(ledger,nextMode);
                if(checkpointAfterCatchup&&!persist()){rejectMode();finishJob();return;}
                checkpointAfterCatchup=false;
                catching=false;
                if(nextMode!=null){ModeCallback cb=modeCallback;nextMode=null;modeCallback=null;modeResult(cb,true);}
                if(job!=null){finishJob();if(!foregroundWanted&&!foreground){closeSavedBackground();return;}}
                if(!foregroundWanted||!foreground)return;
                if(!markRunning())return;
                setState(LifeStatus.OwnerState.PLAYING);legacyState(EmulatorSession.State.RUNNING);
            }
            if(foreground&&foregroundWanted) {
                // Render without adding time for an up-to-date original LCD.
                backend.advance(handle,0,pixels);publishFrame();
                int delay=inputApplied?0:(backend.soundAccepting()?16:33);
                for(long release:releaseAtProgress)if(release>progressed)delay=(int)Math.min(delay,release-progressed);
                ensurePump(delay);
            }
        }catch(ArithmeticException overflow){accountingError();}
        catch(Exception|LinkageError failure){nativeFailure();}
    }
    /** Retry touches only save: candidate, native state and prepared frame stay exactly frozen. */
    private boolean persist() {
        if(nativeBroken||accountingBlocked||handle==0)return false;
        LifeStatus.OwnerState before=state;
        try {
            // Only ordinary foreground checkpoints may retain live contact ownership.
            // Enqueued edges wait outside the frozen candidate until this save settles.
            inputContinuity=before==LifeStatus.OwnerState.PLAYING&&candidate==null&&foreground&&foregroundWanted;
            if(candidate==null) {
                candidate=committed==null?ledger:LifeLedger.nextGeneration(ledger);
                // Prepare the exact zero-time frame immediately before the write/save pair.
                try{backend.advance(handle,0,pixels);}catch(Exception|LinkageError partial){candidate=null;nativeFailure();return false;}
                candidateFrame=pixels.clone();
                try{backend.writeLife(handle,candidate.toArray());}
                catch(RuntimeException|LinkageError invalidNative){candidate=null;candidateFrame=null;nativeFailure();return false;}
            }
            setState(LifeStatus.OwnerState.SAVING);
            if(!backend.save(handle)){inputContinuity=false;setState(LifeStatus.OwnerState.SAVE_FAILED);legacyState(EmulatorSession.State.SAVE_FAILED);return false;}
            committed=candidate;ledger=candidate;candidate=null;
            lastCheckpoint=worker.nowMillis();savedUtc=clock.sample().wallMs;
            try {
                byte[] hash=backend.committedPayloadHash(handle);
                CommitSnapshot snapshot=new CommitSnapshot(committed,hash,candidateFrame,savedUtc);
                observer.committed(snapshot);
            }catch(Exception|LinkageError cacheFailure){scheduler="Preview publication failed";}
            candidateFrame=null;inputContinuity=false;setState(before==LifeStatus.OwnerState.SAVE_FAILED?LifeStatus.OwnerState.CATCHING_UP:before);return true;
        }catch(ArithmeticException overflow){accountingError();return false;}
        catch(Exception|LinkageError failure){
            inputContinuity=false;setState(LifeStatus.OwnerState.SAVE_FAILED);legacyState(EmulatorSession.State.SAVE_FAILED);return false;
        } finally { inputContinuity=false; }
    }
    void button(int button,boolean down) {
        if(button<0||button>=3)throw new IllegalArgumentException("Invalid button");
        synchronized(this) {
            long id;
            if(down) {
                id=++nextContactId;ownerDownAttempts++;
                String refusal=!inputAdmission||!foregroundWanted?"ADMISSION_CLOSED":
                        admittedContact[button]!=0?"ADMISSION_DUPLICATE":
                        inputBoundaries.size()>=INPUT_PRESS_LIMIT?"ADMISSION_QUEUE_FULL":null;
                if(refusal!=null){admissionRefused++;publishInput(id,button,refusal);return;}
                admittedContact[button]=id;admitted++;pending++;
            } else {
                id=admittedContact[button];if(id==0)return;
                admittedContact[button]=0;
            }
            inputBoundaries.addLast(new ButtonBoundary(capture(),epoch.get(),id,button,down));
            if(down)publishInput(id,button,"ADMITTED");
            wakeInput();
        }
    }
    private synchronized void wakeInput() {
        if(inputWakeQueued)return;
        inputWakeQueued=true;
        worker.execute(()->{synchronized(this){inputWakeQueued=false;}ensurePump(0);});
    }
    private synchronized void clearPendingInput(String reason) {
        inputAdmission=false;
        for(int i=0;i<3;i++)admittedContact[i]=0;
        while(!inputBoundaries.isEmpty())cancelPending(inputBoundaries.removeFirst(),reason);
    }
    private void cancelPending(ButtonBoundary input,String reason) {
        if(input.down){pending--;canceledBeforeDelivery++;publishInput(input.id,input.button,reason);}
    }
    private synchronized boolean hasPendingInput(){return !inputBoundaries.isEmpty();}
    private synchronized ButtonBoundary accountPumpBoundary() {
        drainPresence();
        while(!inputBoundaries.isEmpty()) {
            ButtonBoundary input=inputBoundaries.peekFirst();
            if(input.token!=epoch.get()) {
                inputBoundaries.removeFirst();cancelPending(input,"STALE_EPOCH");continue;
            }
            account(input.boundary,knownForeground);return input;
        }
        accountCurrent();return null;
    }
    private synchronized boolean applyPendingInput(ButtonBoundary input) {
        if(inputBoundaries.peekFirst()!=input||input.token!=epoch.get()||!foregroundWanted
                ||state!=LifeStatus.OwnerState.PLAYING||candidate!=null||nativeBroken)return false;
        inputBoundaries.removeFirst();
        boolean awaitingDelivery=input.down;
        try {
            if(input.down) {
                if(nativeContact[input.button]!=0) {
                    pending--;deliveryRefusedNativeHeld++;
                    publishInput(input.id,input.button,"NATIVE_HELD");return true;
                }
                backend.button(handle,input.button,true);
                nativeContact[input.button]=input.id;
                pressedAt[input.button]=progressed;releaseAtProgress[input.button]=0;
                pending--;deliveredNativeDowns++;awaitingDelivery=false;
                publishInput(input.id,input.button,"DELIVERED_DOWN");
            } else {
                if(nativeContact[input.button]!=input.id) {
                    publishInput(input.id,input.button,"UNDELIVERED_UP");return true;
                }
                long release=Math.addExact(pressedAt[input.button],40);
                if(progressed>=release)releaseNativeButton(input.button,"RELEASED");
                else releaseAtProgress[input.button]=release;
                publishInput(input.id,input.button,"PAIRED_UP");
            }
            // Only delivered contacts and their real paired UP retain the existing audio semantics.
            backend.inputApplied(handle,input.button,input.down,input.boundary.sample.elapsedMs);
            return true;
        }catch(ArithmeticException overflow){
            if(awaitingDelivery)cancelPending(input,"ACCOUNTING_ERROR");accountingError();return false;
        }catch(RuntimeException|LinkageError failure){
            // No acknowledged delivery if native down throws; native recovery still owns unknown state.
            if(awaitingDelivery)cancelPending(input,"NATIVE_FAILURE");nativeFailure();return false;
        }
    }
    private void releaseNativeButton(int button,String reason) {
        long id=nativeContact[button];
        backend.button(handle,button,false);releaseAtProgress[button]=0;nativeContact[button]=0;
        if(id!=0)publishInput(id,button,reason);
    }
    private synchronized void releaseDueButtons() {
        for(int i=0;i<3;i++)if(releaseAtProgress[i]>0&&releaseAtProgress[i]<=progressed)
            releaseNativeButton(i,"RELEASED");
    }
    private synchronized boolean releaseButtons(String reason) {
        clearPendingInput(reason);
        if(handle==0)return true;
        try{for(int i=0;i<3;i++)releaseNativeButton(i,reason);return true;}
        catch(RuntimeException|LinkageError failure){nativeFailure();return false;}
    }
    private void checkMarker(){if(!markerChecked){markerChecked=true;recoveryRequired=marker.isFile();}}
    private boolean markRunning() {
        if(markerOwned)return true;
        try(FileOutputStream out=new FileOutputStream(marker)){out.write(1);out.getFD().sync();markerOwned=true;return true;}
        catch(IOException failure){setState(LifeStatus.OwnerState.SAVE_FAILED);legacyState(EmulatorSession.State.SAVE_FAILED);return false;}
    }
    private boolean clearMarker() {
        if(!markerOwned)return true;
        if(marker.exists()&&!marker.delete()){setState(LifeStatus.OwnerState.SAVE_FAILED);return false;}
        markerOwned=false;return true;
    }
    private synchronized void closeSavedBackground() {
        // A visit captured during save still belongs to its queued lifecycle settlement.
        if(foregroundWanted||foreground||!presenceBoundaries.isEmpty()||candidate!=null||ledger!=committed||nativeBroken||accountingBlocked)return;
        if(clearMarker())closeNative();
    }
    private void closeNative() {
        if(handle!=0){try{backend.close(handle);}catch(RuntimeException|LinkageError ignored){}}
        handle=0;pixels=null;foreground=false;catching=false;
        setState(LifeStatus.OwnerState.CLOSED);
    }
    private void nativeFailure() {
        nativeBroken=true;recoveryRequired=true;
        notifyRecovery();
        setState(LifeStatus.OwnerState.RECOVERY_REQUIRED);legacyState(EmulatorSession.State.NATIVE_FAILED);
        rejectMode();finishJob();
    }
    private void accountingError(){accountingBlocked=true;setState(LifeStatus.OwnerState.ACCOUNTING_ERROR);rejectMode();finishJob();}
    private void recovery(){notifyRecovery();setState(LifeStatus.OwnerState.RECOVERY_REQUIRED);legacyState(EmulatorSession.State.RECOVERY_REQUIRED);}
    private void notifyRecovery(){
        try{observer.recoveryRequired();}catch(RuntimeException|LinkageError unavailable){/* Never alter owner recovery. */}
    }
    private void finishJob() {
        JobLease lease=job;JobCompletion completion=jobCompletion;job=null;jobCompletion=null;
        if(lease!=null)complete(lease,completion);
    }
    private void complete(JobLease lease,JobCompletion completion) {
        if(lease.completeOnce())main.execute(()->{if(!lease.isCancelled())completion.complete(lease);});
    }
    private void rejectMode(){if(modeCallback!=null){ModeCallback cb=modeCallback;modeCallback=null;nextMode=null;modeResult(cb,false);}}
    private void modeResult(ModeCallback cb,boolean accepted){LifeStatus snapshot=status();main.execute(()->cb.complete(accepted,snapshot));}
    private LifeStatus status(){return new LifeStatus(ledger,committed,state,savedUtc,scheduler,schedulerFailed,inputContinuity);}
    private synchronized void setState(LifeStatus.OwnerState next){
        state=next;
        if(next==LifeStatus.OwnerState.PLAYING||(next==LifeStatus.OwnerState.SAVING&&inputContinuity))inputAdmission=true;
        else clearPendingInput("STATE_"+next);
        publishStatus();
    }
    private void publishStatus(){
        // A queued progress callback must not cross an immediate state, save,
        // scheduler or lifecycle notification, even when main is delayed.
        synchronized(progressLock){pendingProgress=null;}
        EmulatorSession.Listener target=listener;long token=epoch.get();LifeStatus snapshot=status();
        if(target!=null)main.execute(()->{if(epoch.get()==token&&foregroundWanted)target.lifeStatus(snapshot);});
    }
    private void publishReplayProgress() {
        EmulatorSession.Listener target=listener;long token=epoch.get();
        if(target==null||!foregroundWanted)return;
        ProgressPublication publication;
        synchronized(progressLock) {
            if(pendingProgress!=null&&pendingProgress.token==token&&pendingProgress.target==target) {
                pendingProgress.snapshot=status();return;
            }
            long now=worker.nowMillis();
            if(progressDelivered&&now>=progressDeliveredAt&&now-progressDeliveredAt<100)return;
            publication=new ProgressPublication(target,token,status());pendingProgress=publication;
        }
        main.execute(()->{
            LifeStatus snapshot;
            synchronized(progressLock) {
                if(pendingProgress!=publication)return;
                pendingProgress=null;
                if(epoch.get()!=publication.token||!foregroundWanted)return;
                snapshot=publication.snapshot;
                // Bound actual deliveries, not enqueue times: a delayed main
                // callback cannot be followed by a burst of stale progress.
                progressDeliveredAt=worker.nowMillis();progressDelivered=true;
            }
            publication.target.lifeStatus(snapshot);
        });
    }
    private void legacyState(EmulatorSession.State state){
        EmulatorSession.Listener target=listener;long token=epoch.get();
        if(target!=null)main.execute(()->{if(epoch.get()==token&&foregroundWanted)target.state(state);});
    }
    private void publishFrame(){
        long token=epoch.get();if(framePendingEpoch==token)return;
        framePendingEpoch=token;int[] frame=pixels.clone();EmulatorSession.Listener target=listener;
        main.execute(()->{try{if(epoch.get()==token&&foregroundWanted&&target!=null)target.frame(width,height,frame);}
            finally{worker.execute(()->{if(framePendingEpoch==token)framePendingEpoch=-1;});}});
    }
}
