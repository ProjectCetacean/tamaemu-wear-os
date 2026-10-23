package io.github.projectcetacean.tamaemu;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises real pump/backend/owner boundaries using synthetic data only. */
public final class AudioPassiveScalarsTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static class Observer implements AudioPump.PassiveObserver {
        final List<long[]> events=new ArrayList<>();boolean throwing;
        Observer(){} Observer(boolean throwing){this.throwing=throwing;}
        void add(int kind,long token,long a,long b,long c){if(throwing)throw new AssertionError("optional observer");events.add(new long[]{kind,token,a,b,c});}
        public void epoch(long t,int r){add(1,t,r,0,0);}
        public void track(long t,int r){add(2,t,r,0,0);}
        public void acceptedOffer(long t,long d){add(3,t,d,0,0);}
        public void stablePhase(long t,long p){add(4,t,p,0,0);}
        public void headLag(long t,long f){add(5,t,f,0,0);}
        public void references(long t,int c,int b,int a){add(6,t,c,b,a);}
        public void summary(long t,AudioPump.Summary s,int p){add(7,t,s.trimmedFrames,s.underruns,p);}
        List<long[]> kind(int kind){List<long[]> out=new ArrayList<>();for(long[] e:events)if(e[0]==kind)out.add(e);return out;}
    }
    static void register(Observer o){if(o!=null)check(AudioPump.registerPassiveObserver(o),"register before pump construction");}
    static void acceptedBoundary() {
        Observer o=new Observer();register(o);
        try {
            AudioPumpTest.Output output=new AudioPumpTest.Output();
            AudioPump p=new AudioPump(Runnable::run,(d,r)->()->{},()->0,()->output);
            p.configure(true);long first=p.token();
            output.onStart=()->output.focus.changed(false);
            check(p.offerCompletedAdvance(first,1,new short[0],17)==-1,"empty is not accepted");
            check(p.offerCompletedAdvance(first-1,2,new short[48],17)==-1,"stale token is not accepted");
            check(p.offerCompletedAdvance(first,3,new short[48],137)>0,"accepted before synchronous focus loss");
            check(o.kind(3).size()==1&&o.kind(3).get(0)[1]==first&&o.kind(3).get(0)[2]==137,"acceptance keeps original token/debt");
            check(p.token()!=first&&p.latency().summary().writtenFrames==0,"synchronous loss still discards accepted batch");
            check(p.offerCompletedAdvance(p.token(),4,new short[48],9)==-1,"unfocused rejection");
            output.focus.changed(true);long second=p.token();
            p.offerCompletedAdvance(second,1,new short[48]);
            check(o.kind(3).size()==2&&o.kind(3).get(1)[1]==second&&o.kind(3).get(1)[2]==-1,"legacy debt unavailable");
            check(o.kind(1).stream().anyMatch(e->e[2]==AudioPump.PassiveObserver.FOCUS_LOSS),"operation reason loss");
            check(o.kind(2).stream().anyMatch(e->e[2]==AudioPump.PassiveObserver.TRACK_START&&e[1]!=first),"track start belongs to actual post-focus epoch");
            AudioPump.removePassiveObserver(o);int count=o.events.size();p.invalidate();
            check(o.events.size()==count,"unregistration disables existing pumps");
        } finally { AudioPump.removePassiveObserver(o); }
    }
    static final class OwnerGuest extends LifeSessionTest.Model implements AudioBackend.NativeAudio {
        int ready,drains;
        @Override public void advance(long h,int ms,int[] pixels)throws java.io.IOException{super.advance(h,ms,pixels);ready=ms*48;}
        public void enabled(long h,boolean on){}
        public short[] drain(long h,int max){drains++;return new short[ready];}
    }
    static void exactOwnerDebt()throws Exception {
        Observer o=new Observer();register(o);
        try {
            ArrayDeque<Runnable> audioTasks=new ArrayDeque<>(),ui=new ArrayDeque<>();long[] clock={1000};
            AudioPump p=new AudioPump(audioTasks::add,(d,r)->()->{},()->clock[0],AudioPumpTest.Output::new);p.configure(true);
            OwnerGuest guest=new OwnerGuest();AudioBackend backend=new AudioBackend(guest,guest,()->p,()->clock[0]);
            EmulatorSessionTest.Worker worker=new EmulatorSessionTest.Worker();
            File dir=Files.createTempDirectory("passive-owner-").toFile();
            EmulatorSession session=new EmulatorSession(backend,worker,ui::add,"synthetic",new File(dir,"synthetic.state").getPath(),
                    ()->new ClockSample(clock[0]+99000,clock[0],4),s->{});
            session.resume(new LifeSessionTest.Listener());worker.through(0);
            check(o.kind(3).isEmpty(),"initial zero rendering is not an offer");
            // Startup policy v2: warm this backend epoch before the exact-debt witness.
            backend.advanceWithDebt(1,1,new int[128*128],1);
            while(!audioTasks.isEmpty())audioTasks.remove().run();
            backend.advanceWithDebt(1,1,new int[128*128],1);
            guest.progress=0;o.events.clear();
            clock[0]+=137;worker.through(16);
            List<long[]> offers=o.kind(3);
            check(offers.size()==2&&offers.get(0)[2]==137&&offers.get(1)[2]==37,"LifeOwner exact pre-consume debt 137 then 37");
            check(guest.progress==137&&!ui.isEmpty(),"guest conserved with every UI notification still delayed");
            int before=offers.size();backend.advanceWithDebt(1,0,new int[128*128],999);backend.headless(1,3);
            check(o.kind(3).size()==before&&guest.progress==140,"zero/headless do not become first audible offers");
            session.pause();worker.through(16);
        } finally {AudioPump.removePassiveObserver(o);}
    }
    static String runEquivalent(Observer o)throws Exception {
        register(o);
        try {
            AudioBackendTest.Pipeline.Rig r=new AudioBackendTest.Pipeline.Rig(true,256,1,true,false,true);
            r.run();
            // Full PCM arrays, all summary/latency fields and independent guest progress are compared.
            return Arrays.toString(Arrays.copyOf(r.sink.accepted.data,r.sink.accepted.size))+"|"
                    +Arrays.toString(Arrays.copyOf(r.sink.played.data,r.sink.played.size))+"|"
                    +r.pump.latency().summary().line()+"|"+r.source.guest+"|"+r.debt+"|"+r.describe();
        } finally {if(o!=null)AudioPump.removePassiveObserver(o);}
    }
    static void preservationAndFields()throws Exception {
        String baseline=runEquivalent(null);Observer collecting=new Observer();
        check(baseline.equals(runEquivalent(collecting)),"collecting observer preserves exact PCM, all latency totals, guest progress");
        check(baseline.equals(runEquivalent(new Observer(true))),"throwing observer preserves exact PCM, all latency totals, guest progress");
        check(!collecting.kind(4).isEmpty()&&collecting.kind(4).get(0)[2]>=0,"actual stable phase assignment has pending");
        check(!collecting.kind(7).isEmpty()&&collecting.kind(7).get(0)[4]>=0,"existing summary and separate sampled platform count");
    }
    static String measuredLatency(Observer o) {
        register(o);
        try {
            AudioPumpTest.Play play=AudioPumpTest.play(AudioPumpTest.trace(true),1,0);
            check(play.f.pump.latency().summary().onsets>=5,"nontrivial onset latency fixture");
            return play.f.pump.latency().summary().line()+"|"+play.f.pump.latency().lastOnset().softwareMs+"|"+play.sink.consumed+"|"+play.sink.lost;
        } finally {if(o!=null)AudioPump.removePassiveObserver(o);}
    }
    static String synchronousRollover(Observer o) {
        register(o);
        try {
            AudioPumpTest.Output out=new AudioPumpTest.Output();
            AudioPump p=new AudioPump(Runnable::run,(d,r)->()->{},()->0,()->out);p.configure(true);
            out.onStart=()->out.focus.changed(false);p.offerCompletedAdvance(p.token(),1,new short[48],17);
            out.focus.changed(true);p.offerCompletedAdvance(p.token(),1,new short[48],17);
            return p.token()+"|"+p.accepting()+"|"+p.latency().summary().line()+"|"+out.starts+"|"+out.stops+"|"+out.suspends+"|"+out.resumes;
        } finally {if(o!=null)AudioPump.removePassiveObserver(o);}
    }
    static void allObserverModes() {
        String measured=measuredLatency(null),sync=synchronousRollover(null);
        for(boolean throwing:new boolean[]{false,true}){
            check(measured.equals(measuredLatency(new Observer(throwing))),"all onset latency values preserved");
            check(sync.equals(synchronousRollover(new Observer(throwing))),"synchronous focus rollover identical with observer");
        }
    }
    static void backendRejections()throws Exception {
        for(String mode:new String[]{"empty","null","oversized","rollover"}) {
            Observer observer=new Observer();register(observer);
            try {
                AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();f.pump.configure(true);
                AudioBackendTest.Guest guest=new AudioBackendTest.Guest();
                boolean[] rejecting={false};
                AudioBackend bridge=new AudioBackend(guest,new AudioBackend.NativeAudio(){
                    public void enabled(long h,boolean value){}
                    public short[] drain(long h,int max){if(!rejecting[0])return new short[48];return mode.equals("null")?null:new short[mode.equals("empty")?0:mode.equals("oversized")?9601:48];}
                },()->f.pump,()->f.now);
                // Startup policy v2: reach the live rejection branches, then reset witness counters.
                bridge.advanceWithDebt(7,1,new int[1],1);f.run();
                bridge.advanceWithDebt(7,1,new int[1],1);f.run();
                guest.rendered=0;observer.events.clear();rejecting[0]=true;
                if(mode.equals("rollover"))guest.during=()->{f.pump.configure(false);f.pump.configure(true);};
                bridge.advanceWithDebt(7,16,new int[1],137);
                check(observer.kind(3).isEmpty(),"rejected backend batch cannot claim first debt: "+mode);
                check(guest.rendered==16,"rejection preserves guest progress: "+mode);
            } finally {AudioPump.removePassiveObserver(observer);}
        }
    }
    static void defaultForwarding()throws Exception {
        EmulatorSessionTest.Backend guest=new EmulatorSessionTest.Backend();
        guest.advanceWithDebt(7,17,new int[128*128],131);
        check(guest.advances==1,"compatible backend delegate executes once");
    }
    static void baselineAndReads() {
        Observer o=new Observer();register(o);
        try {
            ArrayDeque<Runnable> queue=new ArrayDeque<>();long[] now={0};int[] reads={0};
            AudioPump.Output out=new AudioPump.Output(){long accepted;
                public boolean start(AudioPump.Focus f){return true;}
                public int write(short[] s,int start,int n){accepted+=n;return n;}
                public long queuedFrames(){reads[0]++;return accepted;}
                public int underrunCount(){return 0;}
                public int startThresholdFrames(){return 2880;}
                public void stop(){}public void suspend(){}public void resume(){}
            };
            AudioPump p=new AudioPump(queue::add,(d,r)->()->{},()->now[0],()->out);p.configure(true);
            p.offerCompletedAdvance(p.token(),100,new short[4800],137);while(!queue.isEmpty())queue.remove().run();
            check(o.kind(4).size()==1&&o.kind(4).get(0)[2]==1920,"pending latched after actual fill before any trim");
            check(reads[0]==2,"observer adds no platform occupancy reads");
        } finally {AudioPump.removePassiveObserver(o);}
    }
    static void lagAndActualMasks() {
        Observer o=new Observer();register(o);
        try {
            ArrayDeque<Runnable> queue=new ArrayDeque<>();long[] now={0},head={0};int[] count={0},reads={0};
            AudioPump.Output out=new AudioPump.Output(){long written;
                public boolean start(AudioPump.Focus f){return true;}
                public int write(short[] s,int start,int n){written+=n;return n;}
                public long queuedFrames(){reads[0]++;return written-head[0];}
                public int underrunCount(){return count[0];}
                public int startThresholdFrames(){return 2880;}
                public void stop(){}public void suspend(){}public void resume(){}
            };
            AudioPump p=new AudioPump(queue::add,(d,r)->()->{},()->now[0],()->out);p.configure(true);
            p.offerCompletedAdvance(p.token(),100,new short[4800],100);while(!queue.isEmpty())queue.remove().run();
            now[0]=100;head[0]=2624;count[0]=1;
            p.offerCompletedAdvance(p.token(),101,new short[48],1);while(!queue.isEmpty())queue.remove().run();
            check(o.kind(5).size()==1&&o.kind(5).get(0)[2]==256,"raw head 256 behind proven floor before clamp");
            check(o.kind(6).stream().anyMatch(e->e[2]==AudioPump.PassiveObserver.DELAYED_HEAD&&e[3]==3&&e[4]==3),"phase and refill retained exactly");
            now[0]=101;head[0]=2000;
            p.offerCompletedAdvance(p.token(),102,new short[48],1);while(!queue.isEmpty())queue.remove().run();
            check(o.kind(6).stream().anyMatch(e->e[2]==AudioPump.PassiveObserver.RAW_HEAD_INVALID&&e[3]==5&&e[4]==0),"backward raw head clears live phase and playback bound");
            check(o.kind(6).stream().noneMatch(e->e[2]==AudioPump.PassiveObserver.RAW_HEAD_INVALID&&e[3]==0),"no invented old references");
        } finally {AudioPump.removePassiveObserver(o);}
    }
    public static void main(String[] args)throws Exception {
        acceptedBoundary();exactOwnerDebt();defaultForwarding();backendRejections();baselineAndReads();lagAndActualMasks();preservationAndFields();allObserverModes();
        System.out.println("AudioPassiveScalarsTest: "+checks+" checks passed");
    }
}
