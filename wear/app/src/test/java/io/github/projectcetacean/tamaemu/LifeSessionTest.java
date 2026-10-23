package io.github.projectcetacean.tamaemu;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Full eight-slot backend model: progress and durable ledger are checkpointed together. */
public final class LifeSessionTest {
    static int checks;
    static void check(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    static class Model implements EmulatorSession.Backend {
        LifeRecord working=LifeRecord.from(new long[8]),disk=working;
        long progress,diskProgress; int opens,closes,saves,headless,maxHandles,handles,pressed;
        final List<String> buttons=new ArrayList<>();
        final List<String> nativeEdges=new ArrayList<>();
        final int[] risingEdges=new int[3];
        final long[] heldProgress=new long[3];
        boolean saveOkay=true,uncertain,partialFailure,writeFailure; File file; Runnable onSlice,onSave,onOpen;
        final List<long[]> attempted=new ArrayList<>();
        public long open(String rom,String path) throws IOException { opens++;handles++;maxHandles=Math.max(maxHandles,handles);file=new File(path);working=disk;progress=diskProgress;if(onOpen!=null){Runnable r=onOpen;onOpen=null;r.run();}return opens; }
        public void close(long handle) { closes++;handles--; }
        public int width(long h) { return 128; } public int height(long h) { return 128; }
        public void button(long h,int key,boolean down) {
            buttons.add(key+":"+down+":"+progress);
            if(((pressed&(1<<key))!=0)!=down) {
                nativeEdges.add(key+":"+down+":"+progress);
                if(down)risingEdges[key]++;
            }
            if(down)pressed|=1<<key;else pressed&=~(1<<key);
        }
        public void advance(long h,int ms,int[] frame) throws IOException { advanceTime(ms);Arrays.fill(frame,(int)progress); }
        public void headless(long h,int ms) throws IOException { headless++;advanceTime(ms); }
        void advanceTime(int ms) throws IOException {
            check(ms>=0&&ms<=100,"strict requested quantum");
            if(partialFailure&&ms>0) { progress+=7; throw new IOException("partial"); }
            for(int key=0;key<3;key++)if((pressed&(1<<key))!=0)heldProgress[key]+=ms;
            progress+=ms;if(onSlice!=null&&ms>0){Runnable r=onSlice;onSlice=null;r.run();}
        }
        public long[] readLife(long h) { return working.toArray(); }
        public void writeLife(long h,long[] a) { if(writeFailure)throw new IllegalStateException("write failed");working=LifeRecord.from(a); }
        public byte[] committedPayloadHash(long h) { byte[] hash=new byte[32];hash[0]=(byte)disk.generation;return hash; }
        public boolean save(long h) throws IOException {
            saves++;attempted.add(working.toArray());if(onSave!=null){Runnable r=onSave;onSave=null;r.run();}
            if(saveOkay||uncertain){disk=working;diskProgress=progress;if(!file.exists())file.createNewFile();}
            return saveOkay;
        }
    }
    static final class Listener implements EmulatorSession.Listener {
        final List<EmulatorSession.State> states=new ArrayList<>();
        final List<LifeStatus.OwnerState> ownerStates=new ArrayList<>();LifeStatus status;int frames;
        public void state(EmulatorSession.State s){states.add(s);}
        public void frame(int w,int h,int[] p){frames++;}
        final List<Long> debts=new ArrayList<>();
        public void lifeStatus(LifeStatus s){status=s;ownerStates.add(s.state);debts.add(s.debtMs);}
    }
    static final class Fixture {
        final EmulatorSessionTest.Worker worker=new EmulatorSessionTest.Worker();
        final Model backend=new Model();final Listener listener=new Listener();
        final File dir;final EmulatorSession session;final List<CommitSnapshot> commits=new ArrayList<>();
        long wall=100000,elapsed=1000,boot=4;boolean observerThrows,recoveryObserverThrows;int recoveries;
        Fixture(){try{dir=Files.createTempDirectory("life-model-").toFile();}catch(IOException e){throw new AssertionError(e);}
            session=new EmulatorSession(backend,worker,Runnable::run,"rom",new File(dir,"pet.state").getPath(),
                ()->new ClockSample(wall,elapsed,boot),new CommitObserver(){
                    public void committed(CommitSnapshot s){commits.add(s);if(observerThrows)throw new IllegalStateException("cache");}
                    public void recoveryRequired(){recoveries++;if(recoveryObserverThrows)throw new IllegalStateException("refresh");}
                });}
        void time(long ms){wall+=ms;elapsed+=ms;}
        void flush(){worker.through(worker.time);}
        void start(){session.resume(listener);flush();}
        void saved(long debt,boolean protect){backend.disk=LifeRecord.from(new long[]{1,100000,1000,4,debt,protect?1:0,4,0});backend.working=backend.disk;try{new File(dir,"pet.state").createNewFile();}catch(IOException e){throw new AssertionError(e);}}
    }
    public static void main(String[] args)throws Exception {
        for(int closure=0;closure<3;closure++)settledJobClearsOwnedMarker(closure);
        failedJobRetainsOwnedMarker();
        rapidVisitDuringJobSaveRetainsCapturedPresence();
        periodicCheckpointPreservesHeldContact();
        inputDuringSaveWaitsForExactCandidate();
        failedHeldCheckpointFreezesUntilRetry();
        failedHeldCheckpointBackgroundRetryReleasesBeforeReplay();
        replayProgressPublicationHasWallTimeBound();
        delayedMainCoalescesReplayAndRespectsDeliveryTime();
        replayTransitionsBypassProgressThrottle();
        staleReplayProgressCannotCrossFailureOrResume();
        rapidRepressPreservesOriginalPulse();
        rapidContactMatrix();
        inputStatsAndBoundaryPolicy();
        inputStatsPressureAndCancellation();
        inputStatsCheckpointAndRetry();
        checkpointPreservesMinimumPulseAndQueuedContact();
        acceptingSoundUsesForegroundCadence();
        foregroundCadenceTracksActualSoundAcceptance();
        cadenceConservesLifeAtCommonBoundaries();
        cadencePreservesInputChronology();
        acceptingSoundDoesNotAccelerateHeadlessWork();
        delayedShortTapUsesGuestTime();
        foregroundTapJitterKeepsPlayingAndInputChronology();
        foregroundInputPressureAndPauseAreBounded();
        failedForegroundCheckpointRetainsProtectedPauseBoundary();
        rapidForegroundVisitCompletesBackgroundLease();
        repeatedFrozenVisitsRetainEachPresenceInterval();
        delayedPauseRetainsFullForegroundOnly();
        migrationAndFullStall();
        failedCandidateFreezesExactPet();
        foregroundJobTransferAndCancellation();
        protectedRecoveryAndNativePartialFailure();
        modeBoundaryAndObserverFailure();
        repeatedResumeAndStaleCompletion();
        transferCommitsBeforeCompletion();
        metadataFailureCannotRetryAnUninstalledCandidate();
        startupAndOldBoundaryOrdering();
        cancelDuringSaveAndModeDuringCatchup();
        periodicCheckpointKeepsGameplay();
        arithmeticFailuresStopWithoutNativeMutation();
        foregroundStallDisablesModeAndInput();
        appliedInputNotifiesBackendOnce();
        recoveredHeadlessFailureMarksNewForeground();
        slowSavesDoNotTurnForegroundDebtIntoCheckpointLoop();
        recoveryObserverFailurePreservesOwnerRecovery();
        sixtySecondBackgroundRunPreservesDebtAndStops();
        System.out.println("LifeSessionTest: "+checks+" checks passed");
    }
    // Missing marker cleanup after a durable job settlement must fail each closure path.
    static void settledJobClearsOwnedMarker(int closure)throws Exception {
        Fixture f=new Fixture();f.start();File marker=new File(f.dir,"pet.state.running");
        f.time(321);f.backend.saveOkay=false;f.session.pause();f.flush();
        check(marker.exists()&&f.backend.handles==1,"failed pause retains marker and frozen native owner");
        long[] candidate=f.backend.working.toArray();
        f.backend.saveOkay=true;f.time(1000);JobLease lease=new JobLease();int[] done={0};
        if(closure!=0)f.backend.onSlice=()->{
            if(closure==1)f.session.cancelBackground(lease);
            else f.worker.time+=100;
        };
        f.session.runBackground(lease,closure==2?1:60000,l->done[0]++);f.flush();f.worker.through(1000);
        check(Arrays.equals(candidate,f.backend.attempted.get(3)),"job first retries the exact failed pause candidate");
        check(f.backend.handles==0&&f.backend.diskProgress==f.backend.progress,"closure durably saves before releasing native");
        check(f.backend.diskProgress+f.backend.disk.debtMs==1321,"closure retains consumed progress and all remaining debt");
        check(done[0]==(closure==1?0:1),"cancel suppresses completion; other closures complete once");
        check(!marker.exists(),"durably settled background closure must clear its owned foreground marker: "+closure);
        f.start();check(!f.listener.states.contains(EmulatorSession.State.RECOVERY_REQUIRED),"settled owner reopens without false recovery");
        check(marker.exists(),"foreground return owns a new running marker");
    }
    static void rapidVisitDuringJobSaveRetainsCapturedPresence()throws Exception {
        Fixture f=new Fixture();f.saved(0,true);f.start();f.time(321);
        f.backend.saveOkay=false;f.session.pause();f.flush();
        f.time(1000);f.backend.saveOkay=true;
        // First save retries the frozen pause; the next save settles background replay.
        f.backend.onSave=()->f.backend.onSave=()->{
            f.session.resume(f.listener);f.time(200);f.session.pause();
        };
        f.session.runBackground(new JobLease(),60000,l->{});f.flush();
        check(f.backend.diskProgress+f.backend.disk.debtMs==521,"foreground presence captured during final job save must be durable before native closure");
        check(f.backend.disk.elapsedMs==2521&&f.backend.handles==0,"rapid visit keeps its exact anchor then closes after pause settlement");
        check(!new File(f.dir,"pet.state.running").exists(),"settled rapid visit clears marker only after its final save");
    }
    static void failedJobRetainsOwnedMarker()throws Exception {
        Fixture f=new Fixture();f.start();File marker=new File(f.dir,"pet.state.running");
        f.time(321);f.backend.saveOkay=false;f.backend.uncertain=true;f.session.pause();f.flush();
        long[] candidate=f.backend.working.toArray();long progress=f.backend.progress;
        JobLease job=new JobLease();f.time(1000);f.session.runBackground(job,1000,l->{});f.flush();
        check(marker.exists()&&f.backend.handles==1,"uncertain failed retry retains marker and handle");
        check(progress==f.backend.progress&&Arrays.equals(candidate,f.backend.working.toArray()),"failed retry cannot change candidate or guest");
        f.backend.saveOkay=true;f.backend.onSave=()->f.session.resume(f.listener);
        f.session.runBackground(new JobLease(),1000,l->{});f.flush();
        check(marker.exists()&&f.backend.handles==1&&f.listener.status.state==LifeStatus.OwnerState.PLAYING,"rapid foreground return retains owned marker and sole native handle");
    }
    // Removing live keys at a routine checkpoint invents a physical UP.
    static void periodicCheckpointPreservesHeldContact()throws Exception {
        InputFixture f=new InputFixture();f.worker.through(25000);
        f.session.button(0,true);f.worker.through(25000);f.worker.through(32000);
        check(f.backend.pressed==1&&f.backend.nativeEdges.equals(Arrays.asList("0:true:25000")),"periodic save preserves a continuously held contact");
        f.session.button(0,false);f.worker.through(32100);
        check(f.backend.nativeEdges.equals(Arrays.asList("0:true:25000","0:false:32000")),"only physical UP ends the held contact");
    }
    // Save callbacks may enqueue edges, but cannot touch the frozen guest or candidate.
    static void inputDuringSaveWaitsForExactCandidate()throws Exception {
        InputFixture f=new InputFixture();f.worker.through(29970);f.worker.now=29980;
        f.session.button(0,true);f.worker.through(29980);
        f.backend.onSave=()->{
            long[] candidate=f.backend.working.toArray();long progress=f.backend.progress;
            f.session.button(0,false);f.session.button(1,true);f.session.button(1,false);
            check(Arrays.equals(candidate,f.backend.working.toArray())&&progress==f.backend.progress,"input during save cannot mutate frozen candidate");
            check(f.backend.pressed==1,"save does not synthesize an UP before physical release is processed");
            LifeUiModel ui=new LifeUiModel();ui.setForegroundReady(true);ui.update(f.listener.status);
            check(ui.careEnabled()&&!ui.modeEnabled()&&ui.beginModeChange()==0,"ordinary save retains touch admission while mode changes stay gated");
        };
        f.worker.through(30100);
        check(f.backend.nativeEdges.equals(Arrays.asList("0:true:29980","1:true:30013","0:false:30020","1:false:30053")),"queued save-time input keeps chronology and minimum pulse: "+f.backend.nativeEdges);
        check(f.backend.pressed==0,"all paired releases settle after save");
    }
    static void failedHeldCheckpointBackgroundRetryReleasesBeforeReplay()throws Exception {
        InputFixture f=new InputFixture();f.worker.through(25000);f.session.button(0,true);f.worker.through(25000);
        f.backend.saveOkay=false;f.worker.through(31000);
        check(f.backend.pressed==1,"failed autosave retains held-key candidate before background retry");
        long[] candidate=f.backend.working.toArray();f.session.pause();f.worker.through(31000);
        check(Arrays.equals(candidate,f.backend.working.toArray()),"failed pause cannot mutate held-key candidate");
        f.worker.now=32000;f.backend.saveOkay=true;
        f.backend.onSlice=()->check(f.backend.pressed==0,"background retry releases saved held keys before headless replay");
        f.session.runBackground(new JobLease(),60000,l->{});f.worker.through(32000);
        check(f.backend.pressed==0&&f.backend.handles==0&&f.backend.diskProgress==32000,"background retry settles all time with released keys");
    }
    static void failedHeldCheckpointFreezesUntilRetry()throws Exception {
        InputFixture f=new InputFixture();f.worker.through(25000);f.session.button(0,true);f.worker.through(25000);
        f.backend.saveOkay=false;f.backend.uncertain=true;f.worker.through(31000);
        check(f.backend.pressed==1,"failed routine save must retain its exact held-key candidate");
        long progress=f.backend.progress;long[] candidate=f.backend.working.toArray();int edges=f.backend.nativeEdges.size();
        f.session.button(0,false);f.session.button(1,true);f.worker.through(31500);
        check(f.backend.progress==progress&&f.backend.nativeEdges.size()==edges&&Arrays.equals(candidate,f.backend.working.toArray()),"UP and new DOWN cannot mutate uncertain frozen save");
        f.session.resume(f.listener);f.worker.through(31500);
        check(f.backend.pressed==1&&f.backend.progress==progress&&Arrays.equals(candidate,f.backend.attempted.get(f.backend.attempted.size()-1)),"failed retry saves exact held-key candidate without mutation");
        f.backend.saveOkay=true;f.session.resume(f.listener);f.worker.through(32000);
        check(f.backend.pressed==0&&f.backend.nativeEdges.size()==2,"successful retry releases old held key exactly once before care resumes");
        check(f.listener.status.state==LifeStatus.OwnerState.PLAYING,"settled retry resumes care");
        Fixture reopen=new Fixture();reopen.saved(100,false);reopen.backend.onOpen=()->reopen.backend.pressed=7;
        reopen.backend.onSlice=()->check(reopen.backend.pressed==0,"reopen releases all saved held keys before replay");reopen.start();
        check(reopen.backend.pressed==0,"saved held keys remain released in reopened care");
    }
    static final class QueuedMain implements java.util.concurrent.Executor {
        final java.util.ArrayDeque<Runnable> tasks=new java.util.ArrayDeque<>();
        public void execute(Runnable task){tasks.addLast(task);}
        void drain(){while(!tasks.isEmpty())tasks.removeFirst().run();}
    }
    /** Each real owner slice costs one synthetic wall millisecond, without firmware or JNI. */
    static final class ReplayFixture {
        final InputWorker worker=new InputWorker();
        final Model backend=new Model(){
            @Override void advanceTime(int ms)throws IOException {
                super.advanceTime(ms);if(ms>0)worker.now++;
            }
        };
        final Listener listener=new Listener();final EmulatorSession session;
        ReplayFixture(long debt,java.util.concurrent.Executor main)throws Exception {
            backend.disk=LifeRecord.from(new long[]{1,100000,0,1,debt,0,4,0});backend.working=backend.disk;
            session=new EmulatorSession(backend,worker,main,"rom",Files.createTempDirectory("replay-status-").resolve("pet.state").toString(),
                    ()->new ClockSample(100000+worker.now,worker.now,1),snapshot->{});
        }
        void start(long until){session.resume(listener);worker.through(until);}
    }
    static void sixtySecondBackgroundRunPreservesDebtAndStops()throws Exception {
        ReplayFixture f=new ReplayFixture(10000000,Runnable::run);int[] completed={0};JobLease lease=new JobLease();
        f.session.runBackground(lease,60000,finished->completed[0]++);
        f.worker.through(10001);
        check(f.backend.progress>1000000&&completed[0]==0,"long allowance continues useful replay beyond ten seconds");
        for(long boundary=20000;boundary<=60000;boundary+=10000)f.worker.through(boundary);
        check(f.backend.diskProgress==6000000&&f.backend.disk.debtMs==4059999,"sixty-second bound checkpoints six million guest ms and every remaining owed ms");
        check(f.backend.disk.elapsedMs==59999&&f.backend.diskProgress+f.backend.disk.debtMs==10059999,"durable anchor retains the final unobserved millisecond for the next accounting boundary");
        check(completed[0]==1&&f.backend.closes==1&&f.backend.handles==0,"budget expiry completes once and releases its sole owner");
        f.session.cancelBackground(lease);f.worker.through(90000);
        check(completed[0]==1&&f.backend.progress==6000000&&f.backend.closes==1,"stale cancellation cannot replay or complete again");
    }
    static void replayProgressPublicationHasWallTimeBound()throws Exception {
        ReplayFixture f=new ReplayFixture(1000000,Runnable::run);f.start(999);
        int delivered=f.listener.ownerStates.size();
        System.out.println("replay-status inline: wall_ms="+f.worker.now+" native_slices="+f.backend.headless+" callbacks="+delivered);
        check(f.worker.now==1000&&f.backend.headless==1000&&f.backend.progress==100000,"one second executes one thousand actual owner/backend slices");
        check(delivered<=11,"one initial state plus at most ten replay progress callbacks in one wall second; got "+delivered);
        long previous=Long.MAX_VALUE;
        for(long debt:f.listener.debts){check(debt<=previous,"replay progress debt does not move backward");previous=debt;}
        f.session.pause();f.worker.through(f.worker.now);
        check(f.backend.diskProgress==100000&&f.backend.disk.debtMs==901000,"publication reduction preserves exact progress plus every elapsed millisecond");
    }
    static void delayedMainCoalescesReplayAndRespectsDeliveryTime()throws Exception {
        QueuedMain main=new QueuedMain();ReplayFixture f=new ReplayFixture(1000000,main);f.start(999);
        System.out.println("replay-status delayed: native_slices="+f.backend.headless+" queued_callbacks="+main.tasks.size());
        check(main.tasks.size()<=3,"stalled main holds only loading, initial state and one replay progress callback");
        main.drain();
        check(f.listener.status.debtMs==900999,"coalesced delivery contains latest completed-slice debt, not first queued debt");
        f.worker.through(1049);
        check(main.tasks.isEmpty(),"delivery clock prevents a burst immediately after main unblocks");
        f.worker.through(1149);
        check(main.tasks.size()==1,"continuing replay queues one fresh progress delivery after the wall interval");
        main.drain();
        check(f.listener.status.debtMs<900999,"subsequent progress moves toward settlement");
    }
    static void replayTransitionsBypassProgressThrottle()throws Exception {
        ReplayFixture success=new ReplayFixture(250,Runnable::run);success.start(3);
        check(success.listener.status.state==LifeStatus.OwnerState.PLAYING&&success.listener.status.debtMs==0,
                "fast replay still publishes final PLAYING inside the progress throttle window");
        check(success.listener.ownerStates.contains(LifeStatus.OwnerState.SAVING),"durable settlement announces SAVING immediately");
        check(success.backend.diskProgress==252&&success.backend.disk.debtMs==0,"final checkpoint preserves exact captured time");
        ReplayFixture failure=new ReplayFixture(250,Runnable::run);failure.backend.saveOkay=false;failure.start(3);
        check(failure.listener.status.state==LifeStatus.OwnerState.SAVE_FAILED,"save failure bypasses progress throttle");
        check(!failure.listener.ownerStates.contains(LifeStatus.OwnerState.PLAYING),"failed settlement never announces PLAYING");
        check(failure.listener.states.contains(EmulatorSession.State.SAVE_FAILED),"legacy failure notification stays immediate");
        QueuedMain main=new QueuedMain();ReplayFixture delayed=new ReplayFixture(250,main);delayed.start(3);main.drain();
        check(delayed.listener.status.state==LifeStatus.OwnerState.PLAYING&&delayed.listener.status.committedGeneration==5,
                "stalled main receives final PLAYING with the newly committed generation");
        int playing=delayed.listener.ownerStates.indexOf(LifeStatus.OwnerState.PLAYING);
        check(playing>=0&&delayed.listener.ownerStates.subList(playing,delayed.listener.ownerStates.size()).stream().noneMatch(s->s==LifeStatus.OwnerState.CATCHING_UP),
                "coalesced progress cannot revert final PLAYING to catch-up");
        ReplayFixture scheduler=new ReplayFixture(1000000,Runnable::run);scheduler.start(0);
        scheduler.session.schedulerStatus("Background scheduling unavailable",true);scheduler.worker.through(1);
        check(scheduler.listener.status.schedulingFailed&&scheduler.worker.now<100,"scheduler failure bypasses replay progress throttle");
    }
    static void staleReplayProgressCannotCrossFailureOrResume()throws Exception {
        QueuedMain main=new QueuedMain();ReplayFixture f=new ReplayFixture(1000000,main);f.start(999);
        f.backend.partialFailure=true;f.worker.next();main.drain();
        check(f.listener.status.state==LifeStatus.OwnerState.RECOVERY_REQUIRED,"queued progress cannot overwrite newer native failure");
        int error=f.listener.ownerStates.indexOf(LifeStatus.OwnerState.RECOVERY_REQUIRED);
        check(error>=0&&f.listener.ownerStates.subList(error,f.listener.ownerStates.size()).stream().noneMatch(s->s==LifeStatus.OwnerState.CATCHING_UP),
                "no stale catch-up progress arrives after recovery state");
        QueuedMain nextMain=new QueuedMain();ReplayFixture next=new ReplayFixture(1000000,nextMain);next.start(49);
        Listener replacement=new Listener();next.session.pause();next.worker.through(next.worker.now);
        next.session.resume(replacement);next.worker.through(next.worker.now);nextMain.drain();
        check(next.listener.ownerStates.isEmpty(),"paused epoch drops all old queued progress and states");
        check(replacement.status!=null&&replacement.status.state==LifeStatus.OwnerState.CATCHING_UP,"new foreground gets its own immediate state");
    }
    static final class InputWorker implements EmulatorSession.Worker {
        final java.util.PriorityQueue<EmulatorSessionTest.Task> tasks=new java.util.PriorityQueue<>();
        long now,sequence;int peak,executed;
        public void execute(Runnable task){later(task,0);}
        public void later(Runnable task,int delay){tasks.add(new EmulatorSessionTest.Task(now+delay,sequence++,task));peak=Math.max(peak,tasks.size());}
        public long nowMillis(){return now;}
        void through(long end){int turns=0;while(!tasks.isEmpty()&&tasks.peek().time<=end){
            check(++turns<20000,"foreground work yields in bounded turns");
            next();
        }now=Math.max(now,end);}
        void next(){EmulatorSessionTest.Task task=tasks.remove();now=Math.max(now,task.time);executed++;task.action.run();}
    }
    static final class InputFixture {
        final InputWorker worker=new InputWorker();final Model backend;final Listener listener=new Listener();
        final EmulatorSession session;
        InputFixture()throws Exception { this(new Model()); }
        InputFixture(Model backend)throws Exception { this(backend,snapshot->{}); }
        InputFixture(Model backend,CommitObserver observer)throws Exception {
            this.backend=backend;
            session=new EmulatorSession(backend,worker,Runnable::run,"rom",Files.createTempDirectory("input-life-").resolve("pet.state").toString(),
                ()->new ClockSample(100000+worker.now,worker.now,1),observer);
            session.resume(listener);worker.through(0);listener.ownerStates.clear();backend.buttons.clear();
        }
    }
    /** Reasserting native true while its bit is already high cannot count as another edge. */
    static void rapidRepressPreservesOriginalPulse()throws Exception {
        for(int key=0;key<3;key++)for(boolean stalled:new boolean[]{false,true}) {
            HookModel model=new HookModel();InputFixture f=new InputFixture(model);
            long[] times={0,20,30,50,60,80};boolean[] downs={true,false,true,false,true,false};
            for(int i=0;i<times.length;i++) {
                if(stalled)f.worker.now=times[i];else f.worker.through(times[i]);
                f.session.button(key,downs[i]);if(!stalled)f.worker.through(times[i]);
            }
            f.worker.now=500;f.worker.through(500);
            List<String> expected=Arrays.asList(key+":true:0",key+":false:40",key+":true:60",key+":false:100");
            check(model.nativeEdges.equals(expected),"rapid repress must release the original at40 and deliver the third at60, stalled="+stalled+" actual="+model.nativeEdges);
            check(model.risingEdges[key]==2,"two actual native mask rising edges, never backend true-call counts");
            check(model.heldProgress[key]==80&&model.pressed==0,"refused second contact cannot stretch either40ms hold");
            check(model.applied.equals(Arrays.asList(key+":true:0:0",key+":false:20:20",key+":true:60:60",key+":false:80:80")),
                    "only delivered contacts and their paired UP reach audio observation: "+model.applied);
            check(model.progress==500&&f.listener.status.debtMs==0,"stall replay consumes exactly500 elapsed milliseconds");
            check(model.progress+f.listener.status.debtMs==f.listener.status.record.elapsedMs,"rapid input conserves life");
        }
    }
    static void rapidContactMatrix()throws Exception {
        for(int hold:new int[]{20,40,60,100})for(int gap:new int[]{10,20,40})
        for(boolean abc:new boolean[]{false,true})for(int first=0;first<3;first++)
        for(boolean stalled:new boolean[]{false,true}) {
            InputFixture f=new InputFixture();List<String> expected=new ArrayList<>();
            for(int i=0;i<3;i++) {
                int key=abc?(first+i)%3:first;long at=i*(hold+gap);
                if(stalled)f.worker.now=at;else f.worker.through(at);
                f.session.button(key,true);if(!stalled)f.worker.through(at);
                if(stalled)f.worker.now=at+hold;else f.worker.through(at+hold);
                f.session.button(key,false);if(!stalled)f.worker.through(at+hold);
                // Literal policy case:20ms hold/10ms gap collides only on the middle same-button contact.
                if(!(hold==20&&gap==10&&!abc&&i==1)) {
                    expected.add(key+":true:"+at);expected.add(key+":false:"+(at+Math.max(40,hold)));
                }
            }
            if(stalled)f.worker.now=500;f.worker.through(500);
            // Different buttons can overlap; compare each button's independent native transitions.
            for(int key=0;key<3;key++) {
                List<String> actual=new ArrayList<>(),want=new ArrayList<>();
                for(String edge:f.backend.nativeEdges)if(edge.startsWith(key+":"))actual.add(edge);
                for(String edge:expected)if(edge.startsWith(key+":"))want.add(edge);
                check(actual.equals(want),"native chronology hold="+hold+" gap="+gap+" abc="+abc+" stalled="+stalled+" actual="+actual+" want="+want);
                check(f.backend.risingEdges[key]==want.size()/2,"count only mask rising edges");
            }
            check(f.backend.pressed==0,"matrix ends released");
            check(f.backend.progress+f.listener.status.debtMs==f.listener.status.record.elapsedMs,"matrix conserves elapsed life");
        }
    }
    static final class StatsEvent {
        final LifeOwner.InputStats stats;final long id,progress;final int button;final String reason;
        StatsEvent(LifeOwner.InputStats stats,long id,int button,String reason,long progress) {
            this.stats=stats;this.id=id;this.button=button;this.reason=reason;this.progress=progress;
        }
    }
    static final class StatsObserver implements LifeOwner.InputObserver {
        final List<StatsEvent> events=new ArrayList<>();final List<String> violations=new ArrayList<>();
        boolean throwsNow;int commits;
        public void committed(CommitSnapshot snapshot){commits++;}
        public void inputStats(LifeOwner.InputStats s,long id,int button,String reason,long progress) {
            events.add(new StatsEvent(s,id,button,reason,progress));
            if(s.ownerDownAttempts!=s.admitted+s.admissionRefused)violations.add("attempt denominator");
            if(s.admitted!=s.pending+s.deliveryRefusedNativeHeld+s.canceledBeforeDelivery+s.deliveredNativeDowns)violations.add("admitted denominator");
            if(s.pending<0||(s.heldMask&~7)!=0)violations.add("invalid pending or held mask");
            if(throwsNow)throw new IllegalStateException("optional input observer");
        }
        LifeOwner.InputStats last(){return events.get(events.size()-1).stats;}
        List<StatsEvent> reason(String reason){List<StatsEvent> out=new ArrayList<>();for(StatsEvent e:events)if(e.reason.equals(reason))out.add(e);return out;}
        void valid(){check(violations.isEmpty(),"every immutable callback snapshot satisfies counter math: "+violations);}
    }
    static void inputStatsAndBoundaryPolicy()throws Exception {
        for(int key=0;key<3;key++)for(int at:new int[]{30,39,40,41})for(boolean stalled:new boolean[]{false,true}) {
            StatsObserver observer=new StatsObserver();HookModel model=new HookModel();InputFixture f=new InputFixture(model,observer);
            long[] times={0,20,at,at+20};boolean[] down={true,false,true,false};
            for(int i=0;i<times.length;i++) {
                if(stalled)f.worker.now=times[i];else f.worker.through(times[i]);
                f.session.button(key,down[i]);if(!stalled)f.worker.through(times[i]);
            }
            f.worker.now=500;f.worker.through(500);observer.valid();
            LifeOwner.InputStats stats=observer.last();
            check(stats.ownerDownAttempts==2&&stats.admitted==2&&stats.admissionRefused==0&&stats.pending==0,"two admitted owner mask-edge attempts");
            check(stats.deliveryRefusedNativeHeld==(at<40?1:0)&&stats.deliveredNativeDowns==(at<40?1:2),"39 refuses,40 and41 deliver after actual release with no added low minimum");
            check(model.nativeEdges.equals(at<40?Arrays.asList(key+":true:0",key+":false:40"):
                    Arrays.asList(key+":true:0",key+":false:40",key+":true:"+at,key+":false:"+(at+40))),"boundary timing preserves ordinary40ms hold: "+model.nativeEdges);
            check(stats.heldMask==0&&stats.canceledBeforeDelivery==0,"all delivered holds end released");
            List<StatsEvent> admits=observer.reason("ADMITTED"),delivered=observer.reason("DELIVERED_DOWN"),ups=observer.reason("PAIRED_UP");
            check(admits.size()==2&&admits.get(1).id>admits.get(0).id,"owner contact IDs increase under admission lock");
            check(delivered.get(0).id==ups.get(0).id&&delivered.get(0).button==key,"delivered DOWN and observational paired UP share original ID");
            if(at<40) {
                StatsEvent refused=observer.reason("NATIVE_HELD").get(0);
                check(refused.id==admits.get(1).id&&refused.progress==at,"delivery refusal identifies the second contact at its captured boundary");
                check(ups.size()==1&&observer.reason("UNDELIVERED_UP").get(0).id==refused.id,"refused UP never reaches audio as delivered");
                check(observer.reason("RELEASED").get(0).id==delivered.get(0).id,"deferred release retains original ID");
                check(delivered.get(0).stats.heldMask==(1<<key),"immutable delivered snapshot retains held bit after later release");
            }
        }
    }
    static void inputStatsPressureAndCancellation()throws Exception {
        StatsObserver pressure=new StatsObserver();InputFixture f=new InputFixture(new Model(),pressure);
        // Fill63 edges, then accept one still-open contact at the64th edge.
        for(int i=0;i<31;i++){f.session.button(0,true);f.session.button(0,false);}
        f.session.button(1,true);f.session.button(2,true);
        f.session.button(1,true); // duplicate admission, not another native contact
        f.session.button(0,true); // queue full
        check(pressure.last().admitted==33&&pressure.last().pending==33&&pressure.last().admissionRefused==2,"64-edge bound refuses duplicate and overflow explicitly");
        f.session.button(1,false);f.session.button(2,false); // releases retained beyond the64edge bound
        check(f.worker.tasks.size()<10,"admission and refusal keep one coalesced wake");
        f.worker.now=500;f.worker.through(500);pressure.valid();
        check(pressure.last().pending==0&&pressure.last().heldMask==0&&f.backend.pressed==0,"queue pressure never loses an admitted UP");
        check(pressure.last().deliveredNativeDowns==3&&pressure.last().deliveryRefusedNativeHeld==30,"zero-time replay delivers one rising edge per independent button");
        for(int kind=0;kind<3;kind++) {
            StatsObserver observer=new StatsObserver();InputFixture c=new InputFixture(new Model(),observer);
            c.session.button(0,true);c.worker.now=20;c.session.button(0,false);
            if(kind==0)c.session.pause();
            else if(kind==1)c.session.resume(c.listener);
            else { c.session.pause();c.session.resume(c.listener); }
            check(observer.last().pending==0&&observer.last().canceledBeforeDelivery==1,"presence/epoch cancels queued DOWN once");
            c.worker.through(20);c.session.resume(c.listener);c.worker.through(20);
            c.worker.now=30;c.session.button(0,true);c.worker.through(30);
            long newId=observer.reason("DELIVERED_DOWN").get(0).id;
            check(newId>observer.reason("ADMITTED").get(0).id,"new foreground contact never reuses canceled ID");
            c.worker.through(70);check(c.backend.pressed==1,"old queued UP cannot release new epoch contact");
            c.worker.now=80;c.session.button(0,false);c.worker.through(100);
            check(c.backend.pressed==0&&observer.last().heldMask==0,"new contact paired UP releases normally");observer.valid();
        }
        StatsObserver observer=new StatsObserver();InputFixture mode=new InputFixture(new Model(),observer);
        mode.session.button(1,true);mode.worker.through(0);mode.worker.now=20;
        mode.session.requestMode(true,(ok,status)->check(ok,"mode boundary accepted with no queued edge"));mode.worker.through(20);
        check(mode.backend.pressed==0&&observer.last().heldMask==0,"mode change releases delivered old contact");
        mode.worker.now=30;mode.session.button(1,true);mode.worker.through(30);mode.worker.through(70);
        check(mode.backend.pressed==2,"old mode contact deadline cannot release new contact");
        mode.worker.now=80;mode.session.button(1,false);mode.worker.through(100);observer.valid();
        check(observer.last().deliveredNativeDowns==2&&observer.last().canceledBeforeDelivery==0,"delivered cancellation is never counted again as canceled-before-delivery");
    }
    static void inputStatsCheckpointAndRetry()throws Exception {
        StatsObserver observer=new StatsObserver();InputFixture f=new InputFixture(new Model(),observer);
        observer.throwsNow=true;f.session.button(0,true);f.worker.through(0);
        f.worker.now=20;f.session.button(0,false);f.worker.through(20);
        f.worker.now=30;f.session.button(0,true);f.worker.through(30); //refused DOWN; its UP waits across checkpoint
        f.backend.saveOkay=false;
        f.worker.now=30000;f.session.button(1,true);f.session.button(1,false);f.worker.through(30000);
        check(f.listener.status.state==LifeStatus.OwnerState.SAVE_FAILED&&f.backend.pressed==0,"elapsed physical pulse is released before failed save despite throwing input observer");
        LifeOwner.InputStats frozen=observer.last();
        check(frozen.canceledBeforeDelivery==1&&frozen.pending==0&&frozen.heldMask==0,"fresh checkpoint accounts queued unconsumed DOWN once");
        long[] candidate=f.backend.working.toArray();long progress=f.backend.progress;int callbacks=observer.events.size();
        f.session.button(2,true); //foreground save failure is an explicit admission refusal
        check(observer.last().admissionRefused==1,"failed save refuses new owner mask-edge attempt");
        f.session.resume(f.listener);f.worker.through(30000);
        check(f.backend.progress==progress&&Arrays.equals(candidate,f.backend.working.toArray()),"frozen retry never replays input or changes candidate");
        check(observer.last().canceledBeforeDelivery==1&&observer.events.size()>=callbacks,"retry never cancels the same queued contact twice");
        f.backend.saveOkay=true;f.session.resume(f.listener);f.worker.through(30000);
        f.session.button(0,false); //old refused contact UP: no current admission and no telemetry delivery
        f.worker.now=30010;f.session.button(0,true);f.worker.through(30010);f.worker.through(30050);
        check(f.backend.pressed==1,"frozen retry and old UP leave new contact intact");
        f.worker.now=30060;f.session.button(0,false);f.worker.through(30100);
        check(f.backend.pressed==0&&observer.last().heldMask==0&&observer.commits>1,"throwing observer cannot break input or committed care");observer.valid();
        check(observer.last().ownerDownAttempts==5&&observer.last().admitted==4&&observer.last().deliveryRefusedNativeHeld==1
                &&observer.last().deliveredNativeDowns==2&&observer.last().canceledBeforeDelivery==1,"cumulative counts partition attempts across checkpoint and retry");
    }
    static void checkpointPreservesMinimumPulseAndQueuedContact()throws Exception {
        StatsObserver observer=new StatsObserver();InputFixture f=new InputFixture(new Model(),observer);
        f.worker.through(29970);f.worker.now=29980;f.session.button(0,true);f.worker.through(29980);
        f.worker.now=29990;f.session.button(0,false);f.worker.through(29990);
        f.worker.now=30000;f.session.button(1,true);f.session.button(1,false);f.worker.through(30000);
        check(f.backend.nativeEdges.equals(Arrays.asList("0:true:29980","1:true:30000")),"checkpoint retains the short pulse and admits the queued independent contact");
        check(observer.last().deliveredNativeDowns==2&&observer.last().canceledBeforeDelivery==0
                &&observer.last().heldMask==3,"checkpoint preserves both live deadlines without invented cancellation");
        f.worker.through(30100);observer.valid();
        check(f.backend.nativeEdges.equals(Arrays.asList("0:true:29980","1:true:30000","0:false:30020","1:false:30040")),"both physical UPs retain their own40ms minimum across checkpoint");
        check(f.backend.pressed==0&&observer.last().pending==0&&observer.last().heldMask==0,"checkpoint-era contacts release exactly once");
    }

    static final class CadenceModel extends Model {
        final boolean accepting;
        CadenceModel(boolean accepting){this.accepting=accepting;}
        @Override public boolean soundAccepting(){return accepting;}
    }
    static void acceptingSoundUsesForegroundCadence()throws Exception {
        InputFixture f=new InputFixture(new CadenceModel(true));
        f.worker.through(15);check(f.backend.progress==0,"no guest time before the audible turn");
        f.worker.through(16);
        check(f.backend.progress==16,"accepting foreground sound must consume16ms by16ms, actual="+f.backend.progress);
        f.session.button(0,true);
        while(f.backend.pressed==0)f.worker.next();
        check(f.worker.tasks.peek().time==16,"an applied input still schedules an immediate zero-delay turn");
    }
    /** The scheduler consumes the real pump's focus/failure state, not a sound preference. */
    static final class SoundFixture {
        final InputWorker worker=new InputWorker();final Model guest=new Model();final Listener listener=new Listener();
        final AudioPumpTest.Fixture audio=new AudioPumpTest.Fixture();final EmulatorSession session;
        boolean available=true;
        SoundFixture()throws Exception {
            audio.pump.configure(true);audio.pump.offer(audio.pump.token(),new short[1]);audio.run();
            AudioBackend backend=new AudioBackend(guest,new AudioBackend.NativeAudio(){
                public void enabled(long h,boolean enabled){}
                public short[] drain(long h,int max){return new short[0];}
            },()->available?audio.pump:null,()->worker.now);
            session=new EmulatorSession(backend,worker,Runnable::run,"rom",Files.createTempDirectory("sound-cadence-").resolve("pet.state").toString(),
                    ()->new ClockSample(100000+worker.now,worker.now,1),snapshot->{});
        }
        void start(){session.resume(listener);worker.through(worker.now);}
    }
    static void foregroundCadenceTracksActualSoundAcceptance()throws Exception {
        SoundFixture f=new SoundFixture();f.start();f.worker.through(16);
        check(f.guest.progress==16,"real accepting pump enables the16ms foreground turn");
        f.audio.output.focus.changed(false);f.audio.run();f.worker.through(32);
        f.worker.through(64);check(f.guest.progress==32,"focus loss restores33ms after the already queued turn");
        f.worker.through(65);check(f.guest.progress==65,"unfocused foreground still consumes full elapsed life");
        f.audio.output.focus.changed(true);f.audio.run();f.worker.through(98);f.worker.through(114);
        check(f.guest.progress==114,"focus gain resumes16ms turns without preference changes");
        f.audio.pump.configure(false);f.audio.run();f.worker.through(130);f.worker.through(162);
        check(f.guest.progress==130,"muted foreground retains33ms scheduling");
        f.worker.through(163);check(f.guest.progress==163,"mute changes pacing only");
        SoundFixture missing=new SoundFixture();missing.available=false;missing.start();missing.worker.through(32);
        check(missing.guest.progress==0,"missing output retains default cadence");missing.worker.through(33);
        check(missing.guest.progress==33,"missing output does not stop life");
        SoundFixture failed=new SoundFixture();failed.audio.output.fail=true;
        failed.audio.pump.offer(failed.audio.pump.token(),new short[1]);failed.audio.run();failed.start();failed.worker.through(32);
        check(failed.guest.progress==0,"failed output retains default cadence");failed.worker.through(33);
        check(failed.guest.progress==33,"audio failure leaves foreground life running");
    }
    /** 528ms is a common boundary for16ms and33ms turns; a stall then reanchors both schedules. */
    static void cadenceConservesLifeAtCommonBoundaries()throws Exception {
        for(boolean protect:new boolean[]{false,true}) {
            InputFixture fast=new InputFixture(new CadenceModel(true)),normal=new InputFixture();
            for(InputFixture f:new InputFixture[]{fast,normal})if(protect){
                f.session.requestMode(true,(ok,status)->check(ok,"Protected mode accepted"));f.worker.through(0);
            }
            int[] initialSaves={fast.backend.saves,normal.backend.saves};
            for(long boundary:new long[]{528,1056,31680,32687,33215,64367}) {
                for(InputFixture f:new InputFixture[]{fast,normal}) {
                    if(boundary==32687)f.worker.now=boundary; //1007ms worker stall, consumed in bounded quanta.
                    f.worker.through(boundary);
                    check(f.backend.progress==boundary&&f.listener.status.debtMs==0,"all elapsed life consumed at common boundary "+boundary);
                    check(f.backend.progress+f.listener.status.debtMs==f.listener.status.record.elapsedMs,"guest progress plus debt conserves elapsed life");
                    check(f.listener.status.state==LifeStatus.OwnerState.PLAYING,"periodic saves and stall keep gameplay active");
                    check(f.backend.diskProgress+f.backend.disk.debtMs==f.backend.disk.elapsedMs,"periodic checkpoint conserves its own elapsed boundary");
                }
                check(Arrays.equals(fast.listener.status.record.toArray(),normal.listener.status.record.toArray()),"identical ledger at common elapsed boundary "+boundary);
                check(Math.abs(fast.backend.diskProgress-normal.backend.diskProgress)<=33,"periodic save boundaries differ by at most the default cadence");
            }
            check(fast.worker.executed>normal.worker.executed,"accepting cadence executes more turns over the same elapsed life");
            check(fast.backend.saves==initialSaves[0]+2&&normal.backend.saves==initialSaves[1]+2,"two periodic checkpoints in64seconds at either cadence");
            for(InputFixture f:new InputFixture[]{fast,normal}){f.session.pause();f.worker.through(64367);}
            check(fast.backend.diskProgress==64367&&normal.backend.diskProgress==64367,"pause saves exactly the same guest progress");
            check(Arrays.equals(fast.backend.disk.toArray(),normal.backend.disk.toArray()),"pause saves identical ledger and debt");
            System.out.println("cadence conservation mode="+(protect?"Protected":"Normal")+" elapsed=64367 progress=64367 debt=0 turns="+fast.worker.executed+"/"+normal.worker.executed);
            for(InputFixture f:new InputFixture[]{fast,normal}) {
                f.session.resume(f.listener);f.worker.through(64367);
                f.worker.now=65374;f.session.pause();f.worker.through(65374);
                check(f.backend.diskProgress==64367&&f.backend.disk.debtMs==1007,"pause retains identical unconsumed1007ms foreground debt");
            }
            check(Arrays.equals(fast.backend.disk.toArray(),normal.backend.disk.toArray()),"nonzero saved debt and all anchors match");
            for(InputFixture f:new InputFixture[]{fast,normal}) {
                f.worker.now=70374;f.session.resume(f.listener);f.worker.through(70374);
                check(f.backend.progress==(protect?65374:70374)&&f.listener.status.debtMs==0,"resume consumes saved debt once and honors Normal/Protected away time");
            }
            check(Arrays.equals(fast.backend.disk.toArray(),normal.backend.disk.toArray()),"settled saved debt remains cadence independent");
        }
    }
    static void cadencePreservesInputChronology()throws Exception {
        InputFixture fast=new InputFixture(new CadenceModel(true)),normal=new InputFixture();
        for(InputFixture f:new InputFixture[]{fast,normal}) {
            f.session.button(0,true);f.worker.now=1;f.session.button(0,false);
            f.worker.now=50;f.session.button(0,true);f.worker.now=100;f.session.button(0,false);
            f.worker.now=500;f.worker.through(500);
            check(f.backend.buttons.equals(Arrays.asList("0:true:0","0:false:40","0:true:50","0:false:100")),"cadence preserves admitted edges and minimum40ms guest pulse");
            check(f.backend.heldProgress[0]==90&&f.backend.pressed==0&&f.backend.progress==500&&f.listener.status.debtMs==0,"cadence conserves delayed tap hold and life");
        }
        check(fast.backend.buttons.equals(normal.backend.buttons),"different cadence has identical guest input chronology");
    }
    static void acceptingSoundDoesNotAccelerateHeadlessWork()throws Exception {
        InputFixture f=new InputFixture(new CadenceModel(true));f.session.pause();f.worker.through(0);
        f.worker.through(1000);int before=f.worker.executed,headless=f.backend.headless;int[] done={0};
        f.session.runBackground(new JobLease(),10000,lease->done[0]++);f.worker.through(1000);
        check(done[0]==1&&f.backend.headless-headless==10,"background retains ten100ms debt slices");
        check(f.backend.diskProgress==1000&&f.backend.disk.debtMs==0&&f.backend.handles==0,"headless checkpoints exact debt and closes");
        f.worker.through(2000);check(f.worker.executed-before==11&&f.worker.tasks.isEmpty(),"sound acceptance creates no background cadence loop");
        System.out.println("cadence: actual acceptance/focus/mute/failure, elapsed conservation, periodic/save debt,40ms input and headless contracts passed");
    }
    /** Audio telemetry hook: observes applied edges with the admitted boundary clock and never changes life accounting. */
    static final class HookModel extends Model {
        final List<String> applied=new ArrayList<>();
        @Override public void inputApplied(long h,int key,boolean down,long admittedMs) { applied.add(key+":"+down+":"+admittedMs+":"+progress); }
    }
    static void appliedInputNotifiesBackendOnce()throws Exception {
        HookModel hooked=new HookModel();InputFixture f=new InputFixture(hooked);InputFixture plain=new InputFixture();
        for(InputFixture run:new InputFixture[]{f,plain}) {
            run.worker.now=200;run.session.button(1,true);
            run.worker.now=300;run.session.button(1,false);run.worker.through(400);
        }
        check(hooked.applied.equals(Arrays.asList("1:true:200:200","1:false:300:300")),"each admitted edge notifies once with its boundary elapsedMs: "+hooked.applied);
        check(f.listener.debts.equals(plain.listener.debts)&&f.backend.buttons.equals(plain.backend.buttons)&&f.backend.progress==plain.backend.progress,
                "hook leaves ledger debt sequence and guest input identical");
    }
    static void delayedShortTapUsesGuestTime()throws Exception {
        InputFixture f=new InputFixture();f.session.button(0,true);
        f.worker.now=1;f.session.button(0,false);
        f.worker.now=50;f.session.button(0,true);
        f.worker.now=100;f.session.button(0,false);
        f.worker.now=500;f.worker.through(550);
        check(f.backend.buttons.equals(Arrays.asList("0:true:0","0:false:40","0:true:50","0:false:100")),
                "500ms worker delay cannot stretch a1ms tap beyond40guestms or reorder the next contact: "+f.backend.buttons);
        check(f.backend.heldProgress[0]==90&&f.backend.pressed==0,"two delayed contacts preserve exact40plus50ms guest hold");
        check(f.backend.progress+f.listener.status.debtMs==f.listener.status.record.elapsedMs,"delayed pulse preserves all foreground elapsed time");
    }
    static void foregroundTapJitterKeepsPlayingAndInputChronology()throws Exception {
        InputFixture f=new InputFixture();
        f.worker.now=200;f.session.button(0,true);
        f.worker.now=220;f.session.button(1,true);f.session.button(0,false);
        f.worker.now=270;f.session.button(1,false);f.worker.through(330);
        check(!f.listener.ownerStates.contains(LifeStatus.OwnerState.CATCHING_UP),"normal delayed foreground taps never become away catch-up");
        check(f.backend.buttons.contains("0:true:200"),"A down occurs after exactly200ms pre-tap elapsed, never retroactively");
        check(f.backend.buttons.contains("1:true:220"),"B down preserves its later captured boundary");
        check(f.backend.heldProgress[0]==40&&f.backend.heldProgress[1]==50,"queued short/overlapping taps retain exact guest-time minimum and captured release order");
        check(f.backend.pressed==0,"queued releases leave no key stuck");
        check(f.backend.progress+f.listener.status.debtMs==f.listener.status.record.elapsedMs,"jitter conserves every accounted millisecond");
        check(f.listener.frames>1,"foreground debt processing still publishes original display frames");
    }
    static void foregroundInputPressureAndPauseAreBounded()throws Exception {
        InputFixture pressure=new InputFixture();pressure.worker.now=1500;
        for(int i=0;i<10000;i++){pressure.session.button(i%3,true);pressure.session.button(i%3,false);}
        check(pressure.worker.tasks.size()<100,"input pressure cannot enqueue unbounded worker callbacks");
        long[] yielded={-1};pressure.worker.execute(()->yielded[0]=pressure.backend.progress);pressure.worker.through(1700);
        check(yielded[0]>=0&&yielded[0]<=100,"backlog yields after at most one native quantum");
        check(pressure.backend.buttons.size()<200,"bounded pending contacts reject excess new presses");
        check(pressure.backend.pressed==0,"queue pressure preserves admitted releases");
        check(pressure.backend.progress+pressure.listener.status.debtMs==pressure.listener.status.record.elapsedMs,"pressure retains all elapsed life");
        InputFixture paused=new InputFixture();paused.worker.now=220;paused.session.button(2,true);paused.session.pause();paused.worker.through(220);
        check(paused.backend.buttons.stream().noneMatch(edge->edge.startsWith("2:true:"))&&paused.backend.pressed==0,"pause cancels a queued tap before it can touch the guest");
        check(paused.backend.disk.debtMs+paused.backend.diskProgress==220,"pause retains pre-cancellation elapsed debt");
        paused.worker.now=300;paused.session.resume(paused.listener);paused.worker.through(400);
        check(paused.backend.pressed==0,"old epoch callbacks cannot leak a key into resumed owner");
        InputFixture stale=new InputFixture();stale.worker.now=20;stale.session.button(0,true);stale.worker.through(20);
        stale.worker.now=25;stale.session.button(0,false);stale.worker.through(25);
        stale.worker.now=30;stale.session.pause();stale.worker.through(30);
        stale.worker.now=40;stale.session.resume(stale.listener);stale.worker.through(40);
        stale.worker.now=45;stale.session.button(0,true);stale.worker.through(70);
        check(stale.backend.pressed==1,"old minimum-pulse release cannot release a contact in the new epoch");
        stale.worker.now=90;stale.session.button(0,false);stale.worker.through(130);
        check(stale.backend.pressed==0,"new epoch contact releases normally");
        Fixture away=new Fixture();away.saved(500,false);away.backend.onSlice=()->away.session.button(0,true);away.start();
        check(away.listener.ownerStates.contains(LifeStatus.OwnerState.CATCHING_UP)&&away.backend.progress==500,"real saved away debt still replays visibly and completely");
        check(away.backend.buttons.stream().noneMatch(edge->edge.startsWith("0:true:")),"real replay still refuses foreground input before settlement");
    }
    static void recoveryObserverFailurePreservesOwnerRecovery(){
        Fixture f=new Fixture();f.start();int saves=f.backend.saves;
        f.recoveryObserverThrows=true;f.backend.partialFailure=true;f.time(100);f.worker.through(33);
        check(f.recoveries==1,"native failure notifies the process observer");
        check(f.listener.status.state==LifeStatus.OwnerState.RECOVERY_REQUIRED,"unavailable refresh cannot prevent owner recovery");
        check(f.backend.saves==saves,"unknown native partial state remains unsaved after observer failure");
    }
    /** Save latency advances the same clock used by both pacing and the ledger. */
    static void slowSavesDoNotTurnForegroundDebtIntoCheckpointLoop() throws Exception {
        for(boolean protect:new boolean[]{false,true})for(int latency:new int[]{200,500}) {
            final class SlowWorker implements EmulatorSession.Worker {
                final java.util.PriorityQueue<EmulatorSessionTest.Task> tasks=new java.util.PriorityQueue<>();
                long now,sequence;
                public void execute(Runnable task){later(task,0);}
                public void later(Runnable task,int delay){tasks.add(new EmulatorSessionTest.Task(now+delay,sequence++,task));}
                public long nowMillis(){return now;}
                void through(long end){int turns=0;while(!tasks.isEmpty()&&tasks.peek().time<=end&&now<=end){
                    check(++turns<20000,"bounded slow-save owner work");
                    EmulatorSessionTest.Task task=tasks.remove();now=Math.max(now,task.time);task.action.run();
                }now=Math.max(now,end);}
            }
            SlowWorker worker=new SlowWorker();
            final class SlowBackend implements EmulatorSession.Backend {
                long progress;int saves,pressed;
                long[] life={1,100000,0,1,0,protect?1:0,1,0};
                public long open(String rom,String save){return 1;}public void close(long h){}
                public int width(long h){return 128;}public int height(long h){return 128;}
                public void advance(long h,int ms,int[] frame){progress+=ms;Arrays.fill(frame,0xff123456);}
                public void headless(long h,int ms){progress+=ms;}
                public void button(long h,int key,boolean down){if(down)pressed|=1<<key;else pressed&=~(1<<key);}
                public long[] readLife(long h){return life.clone();}public void writeLife(long h,long[] a){life=a.clone();}
                public byte[] committedPayloadHash(long h){return new byte[32];}
                public boolean save(long h){check(pressed==(saves==0?0:1),"ordinary slow checkpoints preserve physical held contact");saves++;worker.now+=latency;return true;}
            }
            SlowBackend backend=new SlowBackend();Listener listener=new Listener();
            EmulatorSession session=new EmulatorSession(backend,worker,Runnable::run,"rom",
                Files.createTempDirectory("slow-life-").resolve("pet.state").toString(),
                ()->new ClockSample(100000+worker.now,worker.now,1),snapshot->{});
            session.resume(listener);worker.through(5000);
            check(backend.saves==1,"slow foreground save must not create another checkpoint within5seconds");
            check(listener.status.state==LifeStatus.OwnerState.PLAYING,"slow-save debt returns to playable status");
            session.button(0,true);worker.through(worker.now);
            check(backend.pressed==1,"playing accepts held input");
            worker.now+=450;worker.through(worker.now);
            check(backend.pressed==1,"ordinary foreground scheduling lag preserves the held contact");
            check(backend.saves==1,"routine stall settlement does not checkpoint");
            check(listener.status.state==LifeStatus.OwnerState.PLAYING,"settled stall readmits input");
            session.button(0,true);worker.through(worker.now);check(backend.pressed==1,"new contact after catch-up is admitted");
            worker.through(65000);
            check(backend.saves==3,"two periodic checkpoints plus attachment across65seconds");
            check(listener.status.state==LifeStatus.OwnerState.PLAYING,"periodic slow saves preserve eventual gameplay");
            check(backend.progress+listener.status.debtMs==listener.status.record.elapsedMs,"every elapsed millisecond is advanced or retained as debt");
            check(worker.now-listener.status.record.elapsedMs<=33,"only next frame interval remains unaccounted");
            check(backend.pressed==1,"slow periodic saves never invent physical UP");
            session.button(0,false);worker.through(worker.now);
            check(backend.pressed==0,"physical UP ends the slow-save contact");
            System.out.println("slow-save mode="+(protect?"Protected":"Normal")+" latency="+latency+" saves="+backend.saves+" elapsed="+worker.now+" progress="+backend.progress);
        }
    }
    static void failedForegroundCheckpointRetainsProtectedPauseBoundary(){
        Fixture f=new Fixture();f.saved(0,true);f.start();
        f.backend.saveOkay=false;f.time(30000);f.worker.through(30030);
        check(f.listener.status.state==LifeStatus.OwnerState.SAVE_FAILED,"foreground checkpoint failed before Protected pause");
        long guest=f.backend.progress;long[] frozen=f.backend.working.toArray();
        int committed=f.commits.size();
        f.time(100);f.session.pause();f.flush();
        check(f.session.stopAndAwait(0)==EmulatorSession.StopResult.SAVE_FAILED,"failed pause retry reports nondurability");
        check(f.backend.progress==guest&&Arrays.equals(frozen,f.backend.working.toArray()),"failed pause retains exact native guest and generation candidate");
        check(f.commits.size()==committed&&f.backend.handles==1,"failed pause publishes nothing and retains only frozen handle");
        f.time(5000);f.backend.saveOkay=true;f.start();
        check(f.backend.progress==30100,"Protected excludes5000ms away after failed foreground checkpoint and failed pause retry");
        check(f.backend.diskProgress==30100&&f.backend.disk.debtMs==0,"recovered Protected checkpoint settles only30100ms of known foreground");
        check(f.backend.maxHandles==1&&f.backend.opens==1&&f.listener.status.state==LifeStatus.OwnerState.PLAYING,"frozen retry resumes one foreground owner after settlement");
        check(Arrays.equals(frozen,f.backend.attempted.get(f.backend.attempted.size()-2)),"successful retry first saves exact frozen record before applying deferred boundary");
    }
    static void rapidForegroundVisitCompletesBackgroundLease(){
        Fixture f=new Fixture();f.saved(1000,false);JobLease lease=new JobLease();int[] completed={0};
        long[] durableAtCompletion={-1};
        f.backend.onSlice=()->{f.session.resume(f.listener);f.session.pause();};
        f.session.runBackground(lease,10000,l->{completed[0]++;durableAtCompletion[0]=f.backend.diskProgress;});
        f.flush();f.worker.through(20000);
        check(f.backend.progress==100&&f.backend.diskProgress==100&&f.backend.disk.debtMs==900,"rapid stale foreground visit checkpoints completed slice and preserves900ms debt");
        check(completed[0]==1&&durableAtCompletion[0]==100,"stale foreground attachment still completes job exactly once after checkpoint");
        check(f.backend.handles==0&&f.backend.closes==1&&f.backend.maxHandles==1,"rapid visit cannot strand native handle after job completion");
        check(f.worker.queue.isEmpty()&&f.backend.opens==1,"no stranded lease pump or extra native owner remains past budget");
        f.session.cancelBackground(lease);f.flush();
        check(completed[0]==1,"late cancellation after rapid visit cannot complete lease twice");
    }
    static void repeatedFrozenVisitsRetainEachPresenceInterval(){
        Fixture f=new Fixture();f.saved(0,true);f.start();f.backend.saveOkay=false;
        f.time(30000);f.worker.through(30030);long[] frozen=f.backend.working.toArray();long guest=f.backend.progress;
        f.time(100);f.session.pause();f.flush();f.time(5000);f.start();
        f.time(200);f.session.pause();f.flush();
        check(f.backend.progress==guest&&Arrays.equals(frozen,f.backend.working.toArray()),"multiple failed visits never mutate the exact frozen native candidate");
        f.time(7000);f.backend.saveOkay=true;f.start();
        check(f.backend.progress==30300&&f.backend.disk.debtMs==0,"two deferred Protected visits retain30100plus200ms foreground and exclude both away intervals");
        Fixture rapid=new Fixture();rapid.saved(1000,true);int[] completed={0};
        rapid.backend.onSlice=()->{rapid.time(25);rapid.session.resume(rapid.listener);rapid.time(50);rapid.session.pause();};
        rapid.session.runBackground(new JobLease(),10000,l->completed[0]++);rapid.flush();
        check(rapid.backend.diskProgress==100&&rapid.backend.disk.debtMs==950,"stale foreground token still accounts its50ms of known Protected presence while excluding25ms away");
        check(completed[0]==1&&rapid.backend.handles==0,"Protected rapid visit also completes and closes one owner");
    }
    static void delayedPauseRetainsFullForegroundOnly(){
        Fixture f=new Fixture();f.saved(0,true);f.start();f.time(550);f.session.pause();f.time(5000);f.flush();
        check(f.backend.disk.debtMs==550,"delayed pause captures550ms foreground, excludes5000ms away");
        check(f.backend.disk.elapsedMs==1550&&f.backend.closes==1,"pause persists actual captured boundary");
        f.start();check(f.backend.progress==550&&f.backend.disk.debtMs==0,"Protected resume drains only known foreground debt");
    }
    static void migrationAndFullStall(){
        Fixture f=new Fixture();f.time(500000);f.start();check(f.backend.disk.debtMs==0&&f.backend.progress==0,"old save zero historical debt");
        f.time(1007);f.worker.through(33);check(f.backend.progress==1007,"full1007ms foreground stall without clamp loss");
        check(f.backend.disk.generation>=1&&f.backend.maxHandles==1,"initialized and single owner");
    }
    static void failedCandidateFreezesExactPet(){
        Fixture f=new Fixture();f.start();f.time(321);f.backend.saveOkay=false;f.backend.uncertain=true;f.session.pause();f.flush();
        long progress=f.backend.progress;long[] candidate=f.backend.attempted.get(f.backend.attempted.size()-1).clone();
        f.time(500);f.start();check(f.backend.progress==progress&&f.backend.opens==1&&f.backend.closes==0,"failed uncertain save freezes handle and guest");
        check(Arrays.equals(candidate,f.backend.attempted.get(f.backend.attempted.size()-1)),"retry exact candidate including generation and anchors");
        f.backend.saveOkay=true;f.start();check(f.backend.progress==821&&f.backend.maxHandles==1,"successful retry reconstructs all elapsed debt once");
    }
    static void foregroundJobTransferAndCancellation(){
        Fixture f=new Fixture();f.saved(1000,false);JobLease job=new JobLease();int[] done={0};
        f.backend.onSlice=()->f.session.resume(f.listener);f.session.runBackground(job,10000,l->{done[0]++;});f.flush();
        check(done[0]==1&&f.backend.progress==1000&&f.backend.maxHandles==1,"foreground transfers existing handle and completes job once");
        check(f.listener.status.state==LifeStatus.OwnerState.PLAYING,"foreground playing after debt settled");
        JobLease whilePlaying=new JobLease();f.session.runBackground(whilePlaying,10000,l->{done[0]++;});f.flush();
        check(done[0]==2&&f.backend.opens==1,"job during play creates no owner");
        f.session.pause();f.flush();
        Fixture c=new Fixture();c.saved(1000,false);JobLease cancelled=new JobLease();int[] completed={0};
        c.backend.onSlice=()->c.session.cancelBackground(cancelled);c.session.runBackground(cancelled,10000,l->completed[0]++);c.flush();
        check(c.backend.diskProgress==100&&c.backend.disk.debtMs==900,"cancel between slices checkpoints exact remaining debt");
        check(completed[0]==0&&c.backend.closes==1,"cancelled lease never receives late completion");
        check(!new File(c.dir,"pet.state.running").exists(),"pure headless jobs never create marker");
    }
    static void protectedRecoveryAndNativePartialFailure()throws Exception {
        Fixture f=new Fixture();f.saved(200,true);new File(f.dir,"pet.state.running").createNewFile();f.time(5000);
        JobLease lease=new JobLease();f.session.runBackground(lease,10000,l->{});f.flush();check(f.backend.opens==0,"job cannot acknowledge dirty foreground");
        f.start();check(f.backend.opens==0&&f.listener.states.contains(EmulatorSession.State.RECOVERY_REQUIRED),"resume gates stale marker");
        f.session.retry(f.listener);f.flush();check(f.backend.progress==200&&f.backend.disk.debtMs==0,"Protected recovery freezes ambiguous5000ms but retains200ms committed debt");
        f.backend.partialFailure=true;f.time(100);f.worker.through(33);int saves=f.backend.saves;f.session.pause();f.flush();
        check(f.backend.saves==saves,"partial native state never saved by pause");
        f.start();check(f.backend.opens==1,"ordinary resume cannot retry unknown native state");
        f.backend.partialFailure=false;f.session.retry(f.listener);f.flush();check(f.backend.opens==2&&f.backend.maxHandles==1,"explicit recovery safely discards then reopens durable checkpoint");
    }
    static void modeBoundaryAndObserverFailure(){
        Fixture f=new Fixture();f.start();f.time(255);boolean[] accepted={false};
        f.session.requestMode(true,(ok,s)->accepted[0]=ok);f.flush();check(accepted[0]&&f.backend.disk.mode==1&&f.backend.progress==255,"mode settles all prior Normal debt before acknowledgement");
        f.session.pause();f.flush();f.time(10000);f.start();check(f.backend.progress==255,"Protected future away excluded");
        f.observerThrows=true;f.time(10);f.session.pause();f.flush();check(f.backend.closes==2,"observer failure cannot turn durable save into failed pet commit");
        CommitSnapshot s=f.commits.get(f.commits.size()-1);byte[] h=s.payloadHash();h[0]=0;int[] raw=s.rawFrame();raw[0]=-1;
        check(s.payloadHash()[0]!=(byte)0&&s.rawFrame()[0]>=0,"snapshot arrays immutable");
        Fixture failed=new Fixture();failed.start();failed.backend.saveOkay=false;boolean[] mode={true};failed.session.requestMode(true,(ok,status)->mode[0]=ok);failed.flush();
        check(!mode[0]&&failed.listener.status.mode==0,"failed mode commit remains unacknowledged and unselected");
    }
    static void recoveredHeadlessFailureMarksNewForeground(){
        Fixture f=new Fixture();f.saved(200,false);f.backend.partialFailure=true;
        f.session.runBackground(new JobLease(),10000,lease->{});f.flush();
        check(!new File(f.dir,"pet.state.running").exists(),"failed pure headless job leaves no foreground marker");
        f.backend.partialFailure=false;f.session.retry(f.listener);f.flush();
        check(new File(f.dir,"pet.state.running").isFile(),"explicit headless recovery creates marker for the new foreground visit");
    }
    static void arithmeticFailuresStopWithoutNativeMutation(){
        Fixture debt=new Fixture();debt.saved(Long.MAX_VALUE,false);debt.time(1);debt.start();
        check(debt.listener.status.state==LifeStatus.OwnerState.ACCOUNTING_ERROR&&debt.backend.progress==0,"debt overflow is explicit before native advance");
        Fixture generation=new Fixture();generation.saved(0,false);generation.backend.disk=LifeRecord.from(new long[]{1,100000,1000,4,0,0,Long.MAX_VALUE,0});generation.start();
        check(generation.listener.status.state==LifeStatus.OwnerState.ACCOUNTING_ERROR,"generation overflow is accounting failure, never a retryable save candidate");
        int saves=generation.backend.saves;generation.time(100);generation.start();generation.session.pause();generation.flush();
        check(generation.backend.progress==0&&generation.backend.saves==saves,"accounting failure freezes advancement and cannot overwrite durable anchors");
    }
    static void foregroundStallDisablesModeAndInput(){
        Fixture f=new Fixture();f.start();boolean[] mode={true};
        f.backend.onSlice=()->{f.session.requestMode(true,(accepted,status)->mode[0]=accepted);};
        f.time(999);f.worker.through(33);
        check(!mode[0]&&f.backend.progress==999&&f.backend.disk.mode==0,"foreground backlog disables mode changes until completely settled");
    }
    static void transferCommitsBeforeCompletion(){
        Fixture f=new Fixture();f.saved(1000,false);JobLease lease=new JobLease();
        long[] progressAtCompletion={-1};
        f.backend.onSlice=()->f.session.resume(f.listener);
        f.session.runBackground(lease,10000,l->progressAtCompletion[0]=f.backend.diskProgress);f.flush();
        check(progressAtCompletion[0]>=100,"foreground transfer checkpoints completed job slice before completion");
    }
    static void metadataFailureCannotRetryAnUninstalledCandidate(){
        Fixture f=new Fixture();f.start();int saves=f.backend.saves;
        f.backend.writeFailure=true;f.time(55);f.session.pause();f.flush();
        f.backend.writeFailure=false;f.start();
        check(f.backend.saves==saves,"metadata write failure never saves a candidate that native did not install");
        check(f.listener.status.state==LifeStatus.OwnerState.RECOVERY_REQUIRED,"metadata boundary failure requires explicit checkpoint recovery");
    }
    static void startupAndOldBoundaryOrdering(){
        Fixture f=new Fixture();f.saved(0,true);int[] calls={0};
        LifeScheduler scheduler=new LifeScheduler(new LifeScheduler.Port(){public boolean schedule(){throw new AssertionError("Protected startup must cancel");}public void cancel(){calls[0]++;}},(text,failed)->{});
        f.session.reconcileScheduler(scheduler);f.flush();
        check(calls[0]==1&&f.backend.progress==0&&f.backend.saves==0&&f.backend.closes==1,"startup reads authoritative record without guest mutation or save");
        check(!new File(f.dir,"pet.state.running").exists(),"startup reconciliation never marks foreground");
        Fixture old=new Fixture();old.backend.onOpen=()->{old.time(20);old.session.pause();old.time(80);};old.start();
        check(old.backend.disk.wallUtcMs==100100&&old.backend.disk.elapsedMs==1100&&old.backend.disk.debtMs==0,"queued pre-migration pause sample cannot move first checkpoint anchors backward");
        Fixture regression=new Fixture();regression.start();regression.wall+=3000;regression.elapsed=200;regression.worker.through(33);
        check(regression.backend.progress==3000&&regression.listener.status.clockQuality==1,"genuine later clock regression uses UTC fallback in owner");
    }
    static void cancelDuringSaveAndModeDuringCatchup(){
        Fixture f=new Fixture();f.saved(1000,false);JobLease lease=new JobLease();int[] completed={0};
        f.backend.onSave=()->f.session.cancelBackground(lease);f.session.runBackground(lease,10000,l->completed[0]++);f.flush();
        check(completed[0]==0&&f.backend.diskProgress==1000&&f.backend.closes==1,"cancel in save suppresses late completion after durable checkpoint");
        Fixture mode=new Fixture();mode.saved(1000,false);boolean[] accepted={true};
        mode.backend.onSlice=()->mode.session.requestMode(true,(ok,status)->accepted[0]=ok);mode.start();
        check(!accepted[0]&&mode.backend.disk.mode==0&&mode.backend.progress==1000,"mode request during catch-up cannot erase existing Normal debt");
    }
    static void periodicCheckpointKeepsGameplay(){
        Fixture f=new Fixture();f.start();f.time(30010);f.worker.through(30050);
        check(f.listener.status.state==LifeStatus.OwnerState.PLAYING,"successful periodic save restores playable owner state");
        check(f.backend.progress==30010&&f.backend.diskProgress>0,"periodic save retains exact full foreground life");
    }
    static void repeatedResumeAndStaleCompletion(){
        Fixture f=new Fixture();f.session.resume(f.listener);f.session.resume(f.listener);f.flush();
        check(f.backend.opens==1&&f.listener.frames==1,"repeated resume has one pump and handle");
        Fixture budget=new Fixture();budget.saved(1000,false);JobLease lease=new JobLease();int[] done={0};
        budget.backend.onSlice=()->budget.worker.time+=20;budget.session.runBackground(lease,10,l->done[0]++);budget.flush();budget.worker.through(20);
        check(done[0]==1&&budget.backend.disk.debtMs==900,"wall budget preserves unconsumed debt");
        budget.session.cancelBackground(lease);budget.flush();check(done[0]==1,"stale cancellation cannot complete twice");
    }
}
