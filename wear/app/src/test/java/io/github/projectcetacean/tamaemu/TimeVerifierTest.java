package io.github.projectcetacean.tamaemu;

import java.time.*;
import java.util.*;

/** Independent UTC fixtures. No Android, firmware or real saved pet. */
public final class TimeVerifierTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static final long UTC=1800000000000L;
    static final class Local implements Clock {
        long wall=UTC,elapsed=1000,boot=1;
        public ClockSample sample(){return new ClockSample(wall,elapsed,boot);}
        void advance(long ms){wall+=ms;elapsed+=ms;}
    }
    static final class Source implements TimeVerifier.Provider {
        final Local local;long offset,span,jump;int calls;int failure;
        Source(Local local){this.local=local;}
        public java.time.Clock obtain(){
            calls++;if(failure==1)throw new DateTimeException("No estimate");
            if(failure==2)throw new IllegalStateException("Provider unavailable");
            if(failure==3)return null;
            return new java.time.Clock(){
                public ZoneId getZone(){return ZoneOffset.UTC;}
                public java.time.Clock withZone(ZoneId z){return this;}
                public Instant instant(){return Instant.ofEpochMilli(millis());}
                public long millis(){
                    if(failure==4)throw new DateTimeException("Read unavailable");
                    if(failure==5)throw new IllegalStateException("Read failed");
                    long utc=local.wall+offset+span/2;local.advance(span);local.wall+=jump;return utc;
                }
            };
        }
    }
    static void outcome(TimeVerifier.Outcome want,TimeVerifier.Outcome got,String reason){check(want==got,reason+": "+got);}
    static void presentation() throws Exception {
        LifeRecord record=LifeRecord.from(new long[]{1,UTC,1000,1,900,1,7,0});
        record=LifeLedger.account(record,new ClockSample(UTC+1000,100,2),false);
        for(int i=1;i<=672;i++)record=LifeLedger.account(record,new ClockSample(UTC+1000+i*900000L,100+i*900000L,2),false);
        long[] slots=record.toArray();int[] pixels=new int[16384];Arrays.fill(pixels,0xff123456);
        PetPreviewSnapshot saved=new PetPreviewSnapshot(new byte[32],7,UTC,PetPreviewSnapshot.Mode.PROTECTED,true,900,(int)record.clockQuality,pixels);
        byte[] encoded=PetPreviewStore.encode(saved);
        Local local=new Local();TimeVerifier verifier=new TimeVerifier(local,new Source(local));
        outcome(TimeVerifier.Outcome.MATCH,verifier.check(),"independent comparison");
        for(String label:new String[]{saved.statusLabel(verifier.current()),saved.exceptionalStatus(verifier.current())}){
            check(!label.contains("Time needs checking"),"ODO-B3 current warning clears after MATCH");
            check(label.contains("estimated"),"historical uncertainty stays visible");
            check(label.contains("Protected: paused away"),"Protected remains visible");
        }
        check(saved.statusLabel(verifier.current()).contains("Catch-up needed"),"full label preserves debt notice");
        check(saved.statusLabel().contains("Time needs checking"),"old overload remains conservative");
        check(Arrays.equals(slots,record.toArray())&&record.clockQuality==1&&record.debtMs==900,"all eight slots unchanged");
        check(Arrays.equals(encoded,PetPreviewStore.encode(saved)),"proof changes no cache bytes or generation");
        PetPreviewSnapshot invalid=new PetPreviewSnapshot(new byte[32],7,1,saved.mode,true,900,1,pixels);
        check(invalid.statusLabel(verifier.current()).contains("Time needs checking"),"current proof cannot certify invalid saved date");
        check(invalid.exceptionalStatus(verifier.current()).contains("Time needs checking"),"both invalid saved date paths remain");
    }
    static void presentationNeverWaitsForProvider() throws Exception {
        Local local=new Local();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        TimeVerifier v=new TimeVerifier(local,()->{
            entered.countDown();
            try {if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Timeout");}
            catch(InterruptedException interrupted){throw new IllegalStateException(interrupted);}
            return java.time.Clock.fixed(Instant.ofEpochMilli(UTC),ZoneOffset.UTC);
        });
        Thread background=new Thread(v::check);background.setDaemon(true);background.start();
        java.util.concurrent.ExecutorService reader=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"comparison entered provider");
            try {outcome(TimeVerifier.Outcome.UNAVAILABLE,reader.submit(v::current).get(1,java.util.concurrent.TimeUnit.SECONDS),"pending comparison cannot block presentation");}
            catch(java.util.concurrent.TimeoutException blocked){throw new AssertionError("Presentation waited for provider monitor",blocked);}
        } finally {release.countDown();reader.shutdownNow();background.join(2000);}
    }
    public static void main(String[] args) throws Exception {
        presentationNeverWaitsForProvider();
        presentation();
        for(long offset:new long[]{-5001,-5000,0,5000,5001}){
            Local l=new Local();Source s=new Source(l);s.offset=offset;TimeVerifier v=new TimeVerifier(l,s);
            outcome(TimeVerifier.Outcome.UNAVAILABLE,v.current(),"fresh process starts unverified");
            outcome(Math.abs(offset)<=5000?TimeVerifier.Outcome.MATCH:TimeVerifier.Outcome.MISMATCH,v.check(),"inclusive offset "+offset);
        }
        for(int failure=1;failure<=5;failure++){
            Local l=new Local();Source s=new Source(l);TimeVerifier v=new TimeVerifier(l,s);v.check();l.advance(900000);s.failure=failure;
            outcome(TimeVerifier.Outcome.UNAVAILABLE,v.check(),"obtain/read failures invalidate old proof "+failure);
            s.failure=0;v.check();check(s.calls==2,"unavailable retry throttled");l.advance(59999);v.check();check(s.calls==2,"before retry boundary");
            l.advance(1);outcome(TimeVerifier.Outcome.MATCH,v.check(),"retry at 60 seconds");check(s.calls==3,"retry count");
        }
        for(long[] scenario:new long[][]{{250,0,1},{251,0,0},{100,1000,1},{100,1001,0},{100,-1001,0},{-1,0,0}}){
            Local l=new Local();Source s=new Source(l);s.span=scenario[0];s.jump=scenario[1];TimeVerifier v=new TimeVerifier(l,s);
            outcome(scenario[2]==1?TimeVerifier.Outcome.MATCH:TimeVerifier.Outcome.UNAVAILABLE,v.check(),"bracket/discontinuity boundaries");
        }
        Local l=new Local();Source s=new Source(l);TimeVerifier v=new TimeVerifier(l,s);v.check();l.advance(899999);
        outcome(TimeVerifier.Outcome.MATCH,v.current(),"proof before expiry");l.advance(1);outcome(TimeVerifier.Outcome.UNAVAILABLE,v.current(),"proof expires at 15 minutes");
        outcome(TimeVerifier.Outcome.MATCH,v.check(),"expiry refresh");l.wall+=1001;outcome(TimeVerifier.Outcome.UNAVAILABLE,v.current(),"wall jump invalidates");
        l.wall-=1001;outcome(TimeVerifier.Outcome.UNAVAILABLE,v.current(),"old wall cannot revive proof");
        l.advance(60000);v.check();l.advance(100);v.current();l.elapsed--;outcome(TimeVerifier.Outcome.UNAVAILABLE,v.current(),"rollback even above original anchor invalidates");
        l.advance(60000);v.check();l.boot++;outcome(TimeVerifier.Outcome.UNAVAILABLE,v.current(),"boot change invalidates");
        l=new Local();l.boot=ClockSample.UNKNOWN_BOOT;s=new Source(l);v=new TimeVerifier(l,s);
        outcome(TimeVerifier.Outcome.MATCH,v.check(),"unknown boot immediate comparison");outcome(TimeVerifier.Outcome.UNAVAILABLE,v.current(),"unknown boot cannot reuse");
        l=new Local();s=new Source(l);v=new TimeVerifier(l,s);v.check();
        outcome(TimeVerifier.Outcome.UNAVAILABLE,new TimeVerifier(l,s).current(),"restart never inherits proof");
        for(Clock bad:new Clock[]{()->null,()->{throw new IllegalArgumentException("invalid");},new Clock(){int reads;public ClockSample sample(){return new ClockSample(Long.MAX_VALUE,reads++==0?0:250,1);}}}){
            Source source=new Source(new Local());source.span=2;
            outcome(TimeVerifier.Outcome.UNAVAILABLE,new TimeVerifier(bad,source).check(),"malformed/overflow rejected");
        }
        l=new Local();s=new Source(l);v=new TimeVerifier(l,s);v.check();l.wall+=1000;l.elapsed+=1;
        outcome(TimeVerifier.Outcome.MATCH,v.current(),"within continuity tolerance");l.wall+=2;
        outcome(TimeVerifier.Outcome.UNAVAILABLE,v.current(),"cumulative continuity over tolerance");
        System.out.println("TimeVerifierTest: "+checks+" checks passed");
    }
}
