package io.github.projectcetacean.tamaemu;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real owner/ledger with explicit guest-operation doubles; no Android or private inputs. */
public final class ReplayBatchTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Worker implements EmulatorSession.Worker {
        final PriorityQueue<EmulatorSessionTest.Task> queue=new PriorityQueue<>();
        long now,sequence,turns;
        public void execute(Runnable r){later(r,0);}
        public void later(Runnable r,int delay){queue.add(new EmulatorSessionTest.Task(now+delay,sequence++,r));}
        public long nowMillis(){return now;}
        void one(){EmulatorSessionTest.Task t=queue.remove();now=Math.max(now,t.time);turns++;t.action.run();}
        void flush(){int guard=0;while(!queue.isEmpty()&&queue.peek().time<=now){check(++guard<1000000,"bounded worker queue");one();}}
    }
    static final class Batch extends LifeSessionTest.Model {
        int calls,limit=60000,bad;Runnable during;boolean fail;
        final List<Integer> requests=new ArrayList<>();
        @Override public int replay(long h,int requested)throws Exception {
            calls++;requests.add(requested);check(requested>0&&requested<=60000,"bounded replay request");
            if(bad!=0)return bad==1?0:bad==2?-1:requested+1;
            int done=Math.min(requested,limit);
            for(int left=done;left>0;){int step=Math.min(100,left);headless(h,step);left-=step;}
            if(during!=null){Runnable r=during;during=null;r.run();}
            if(fail){progress+=7;throw new IOException("unknown native failure");}
            return done;
        }
    }
    static final class Rig {
        final Worker worker=new Worker();final LifeSessionTest.Model backend;
        final LifeSessionTest.Listener listener=new LifeSessionTest.Listener();
        final List<CommitSnapshot> commits=new ArrayList<>();final EmulatorSession session;final File dir;
        long elapsed=1000,wall=100000;int recoveries;
        Rig(LifeSessionTest.Model model,long debt,boolean protect)throws Exception {
            backend=model;dir=Files.createTempDirectory("replay-batch-owner-").toFile();
            model.disk=LifeRecord.from(new long[]{1,100000,1000,4,debt,protect?1:0,4,0});
            model.working=model.disk;new File(dir,"pet.state").createNewFile();
            session=new EmulatorSession(model,worker,Runnable::run,"synthetic",new File(dir,"pet.state").getPath(),
                    ()->new ClockSample(wall,elapsed,4),new CommitObserver(){
                        public void committed(CommitSnapshot s){commits.add(s);}
                        public void recoveryRequired(){recoveries++;}
                    });
        }
        void time(long ms){wall+=ms;elapsed+=ms;}
        void start(){session.resume(listener);worker.flush();}
        void stop(){session.pause();worker.flush();}
    }
    static void defaultFallbackAndReduction()throws Exception {
        long debt=43200000;
        Rig fallback=new Rig(new LifeSessionTest.Model(),debt,false);
        long begin=System.nanoTime();fallback.start();long fallbackNs=System.nanoTime()-begin;
        Batch b=new Batch();Rig batch=new Rig(b,debt,false);
        begin=System.nanoTime();batch.start();long batchNs=System.nanoTime()-begin;
        check(b.calls>0,"catching owner must call completed-work API");
        check(fallback.backend.headless==432000,"default fallback retains literal 100ms operations");
        check(b.calls==720&&b.headless==432000,"batch groups identical guest operations");
        check(fallback.backend.diskProgress==debt&&b.diskProgress==debt,"both checkpoint exactly initial debt");
        check(Arrays.equals(fallback.backend.disk.toArray(),b.disk.toArray()),"common-boundary ledger equality");
        check(batch.worker.turns*100<fallback.worker.turns,"actual owner worker turn reduction over 100x");
        System.out.println("12h zero-cost synthetic: fallback turns="+fallback.worker.turns+" calls="+fallback.backend.headless+" ns="+fallbackNs+
                "; batch turns="+batch.worker.turns+" crossings="+b.calls+" literalOps="+b.headless+" ns="+batchNs);
        fallback.stop();batch.stop();
    }
    static void partialAndFrozenRetry()throws Exception {
        Batch b=new Batch();b.limit=200;Rig f=new Rig(b,1057,false);
        b.during=()->{b.saveOkay=false;f.worker.now=30000;};f.start();
        check(b.progress==200&&b.calls==1,"partial batch returns before failed wall checkpoint");
        check(b.working.debtMs==857&&f.listener.status.state==LifeStatus.OwnerState.SAVE_FAILED,"account exact completed work");
        long[] frozen=b.attempted.get(0).clone();int calls=b.calls;
        f.time(41);f.session.pause();f.worker.flush();
        check(b.calls==calls&&Arrays.equals(frozen,b.attempted.get(1)),"failed pause retries exact frozen candidate without guest work");
        b.saveOkay=true;f.session.resume(f.listener);f.worker.flush();
        check(Arrays.equals(frozen,b.attempted.get(2)),"successful retry first writes same candidate");
        check(b.diskProgress==1098&&b.disk.debtMs==0,"remaining debt and captured presence fully conserved");
        check(b.requests.get(b.requests.size()-1)==98,"final batch cannot overshoot debt");f.stop();
    }
    static void invalidAndUnknownFailures()throws Exception {
        for(int bad=1;bad<=4;bad++){
            Batch b=new Batch();b.bad=bad<=3?bad:0;b.fail=bad==4;Rig f=new Rig(b,1000,false);f.start();
            check(f.recoveries==1&&f.listener.status.state==LifeStatus.OwnerState.RECOVERY_REQUIRED,"invalid/unknown result requires recovery");
            check(b.saves==0&&b.diskProgress==0&&b.disk.debtMs==1000,"never save uncertain guest progress");
            int calls=b.calls;f.worker.flush();check(b.calls==calls,"zero/error cannot spin forever");
        }
    }
    static void pauseCancelDeadlineAndTransfer()throws Exception {
        Batch queued=new Batch();Rig q=new Rig(queued,1000,false);
        q.session.resume(q.listener);q.worker.one();q.session.pause();q.worker.flush();
        check(queued.calls==0&&queued.disk.debtMs==1000,"queued pause invalidates before native replay");
        Batch b=new Batch();b.limit=200;Rig f=new Rig(b,1000,false);b.during=()->{f.time(17);f.session.pause();};f.start();
        check(b.calls==1&&b.diskProgress==200&&b.disk.debtMs==817,"pause during batch settles only completed work and captured wall");
        for(int kind=0;kind<3;kind++){
            Batch j=new Batch();j.limit=200;Rig r=new Rig(j,1000,false);JobLease lease=new JobLease();int[] completed={0};
            final int mode=kind;j.during=()->{if(mode==0)r.session.cancelBackground(lease);else if(mode==1)r.worker.now=10;else r.session.resume(r.listener);};
            r.session.runBackground(lease,10,l->completed[0]++);r.worker.flush();
            if(kind<2){check(j.calls==1&&j.diskProgress==200&&j.disk.debtMs==800,"cancel/deadline yields at batch boundary");
                check(completed[0]==(kind==0?0:1),"cancelled completion suppressed, deadline completes once");}
            else{check(completed[0]==1&&j.diskProgress==1000&&j.disk.debtMs==0,"foreground transfer settles lease and exact debt");r.stop();}
        }
    }
    static void liveAndModeRemainLiteral()throws Exception {
        Batch b=new Batch();Rig f=new Rig(b,0,false);f.start();check(b.calls==0,"zero debt never calls replay");
        f.time(33);f.worker.one();f.worker.flush();
        f.session.button(0,true);f.worker.flush();f.session.button(0,false);f.worker.flush();
        f.time(40);f.worker.one();f.worker.flush();
        check(b.calls==0&&b.risingEdges[0]==1&&b.heldProgress[0]==40,"PLAYING and minimum input pulse stay literal");
        f.time(357);boolean[] accepted={false};f.session.requestMode(true,(ok,s)->accepted[0]=ok);f.worker.flush();
        check(accepted[0]&&b.calls==1&&b.disk.mode==1,"mode boundary catches debt before committing mode");f.stop();
    }
    static void fifoProtectedPresence()throws Exception {
        Batch b=new Batch();b.limit=200;Rig f=new Rig(b,1000,true);
        b.during=()->{f.time(10);f.session.pause();f.time(20);f.session.resume(f.listener);f.time(30);f.session.pause();};
        f.start();check(b.diskProgress+b.disk.debtMs==1040,"protected away intervals excluded, both visits retained FIFO");
        check(b.calls==1,"captured final pause prevents another batch");
    }
    static void ledgerAndDefaultRejection()throws Exception {
        LifeSessionTest.Model model=new LifeSessionTest.Model();
        for(int invalid:new int[]{0,-1,60001,Integer.MAX_VALUE}){
            try{model.replay(1,invalid);throw new AssertionError("invalid default request accepted");}
            catch(IllegalArgumentException expected){check(model.headless==0,"default rejects before guest operation");}
        }
        check(model.replay(1,1057)==100&&model.progress==100,"default reports actual single slice only");
        LifeRecord record=LifeRecord.from(new long[]{1,100000,1000,4,1057,0,4,0});
        for(int[] invalid:new int[][]{{0,100},{-1,100},{101,100},{100,1058},{100,60001}}){
            try{LifeLedger.consumeReplay(record,invalid[0],invalid[1]);throw new AssertionError("invalid ledger completion accepted");}
            catch(IllegalArgumentException expected){check(record.debtMs==1057,"ledger rejection leaves debt intact");}
        }
        try{LifeLedger.consume(record,101);throw new AssertionError("old ledger cap widened");}catch(IllegalArgumentException expected){checks++;}
        check(LifeLedger.consumeReplay(record,200,1057).debtMs==857,"separate replay ledger consumes exact completion");
    }
    public static void main(String[] args)throws Exception {
        ledgerAndDefaultRejection();defaultFallbackAndReduction();partialAndFrozenRetry();invalidAndUnknownFailures();
        pauseCancelDeadlineAndTransfer();liveAndModeRemainLiteral();fifoProtectedPresence();
        System.out.println("ReplayBatchTest: "+checks+" checks passed");
    }
}
