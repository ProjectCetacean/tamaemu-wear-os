package io.github.projectcetacean.tamaemu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** H-S1: real backend/pump with synthetic guest samples and a deferred output executor. */
public final class AudioStartupTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static short sample(long frame){return (short)(frame/48%40<24?1+frame%30000:0);}
    static final class Guest implements EmulatorSession.Backend,AudioBackend.NativeAudio {
        long progress;boolean enabled;int drains,enables;Runnable during,enableHook,drainHook;
        final List<String> history=new ArrayList<>(),inputs=new ArrayList<>(),saves=new ArrayList<>();
        short[] pcm;
        public long open(String r,String s){return 1;}public void close(long h){}
        public int width(long h){return 1;}public int height(long h){return 1;}
        public void advance(long h,int ms,int[] pixels){history.add(progress+":"+ms);pcm=enabled?new short[ms*48]:null;
            if(pcm!=null)for(int i=0;i<pcm.length;i++)pcm[i]=sample(progress*48+i);
            progress+=ms;pixels[0]=(int)progress;if(during!=null)during.run();}
        public void button(long h,int n,boolean down){inputs.add(progress+":"+n+":"+down);}
        public boolean save(long h){saves.add(progress+":"+inputs.toString());return true;}
        public byte[] committedPayloadHash(long h){return saves.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        public void enabled(long h,boolean e){enables++;if(enableHook!=null)enableHook.run();enabled=e;}
        public short[] drain(long h,int max){drains++;if(drainHook!=null)drainHook.run();return pcm;}
    }
    static final class Sink implements AudioPump.Output {
        int starts,stops,reads,writes,resumes,suspends;boolean fail,throwStart,started;
        Runnable onStart,onResume;AudioPump.Focus focus;final List<Short> accepted=new ArrayList<>();
        public boolean start(AudioPump.Focus f){starts++;focus=f;if(onStart!=null)onStart.run();
            if(throwStart)throw new IllegalStateException("start");started=!fail;return started;}
        public int write(short[] a,int off,int n){check(started,"output started before write");writes++;for(int i=0;i<n;i++)accepted.add(a[off+i]);return n;}
        public long queuedFrames(){reads++;return 0;}
        public void stop(){stops++;started=false;}public void suspend(){suspends++;}public void resume(){resumes++;if(onResume!=null)onResume.run();}
    }
    static final class Timer {
        long due;Runnable task;boolean canceled;Timer(long due,Runnable task){this.due=due;this.task=task;}
    }
    static final class Rig {
        long now;int creates;final ArrayDeque<Runnable> tasks=new ArrayDeque<>();final List<Timer> timers=new ArrayList<>();final Sink sink=new Sink();final Guest guest=new Guest();
        final AudioPump pump;AudioPump selected;final AudioBackend backend;
        Rig(int target){pump=new AudioPump(tasks::add,(d,r)->{Timer t=new Timer(now+d,r);timers.add(t);return ()->t.canceled=true;},()->now,()->{creates++;return sink;},target);selected=pump;
            backend=new AudioBackend(guest,guest,()->selected,()->now);pump.configure(true);}
        void run(){int n=0;while(!tasks.isEmpty()){check(++n<100,"finite executor");tasks.remove().run();}}
        void until(long end){run();int n=0;while(true){Timer next=null;for(Timer t:timers)if(!t.canceled&&t.due<=end&&(next==null||t.due<next.due))next=t;
            if(next==null)break;check(++n<100,"finite delayed drain");next.canceled=true;now=Math.max(now,next.due);next.task.run();run();}now=Math.max(now,end);}
        void advance(int ms,long debt)throws Exception{backend.advanceWithDebt(1,ms,new int[1],debt);}
        void warm()throws Exception{advance(16,16);run();now+=16;advance(16,16);run();check(guest.drains==1,"eligible epoch warmed");}
    }
    // Removing the debt check admits historical PCM; removing prepare prevents finite first admission.
    static void cold()throws Exception {
        for(int target:new int[]{1440,1920,2880})for(int hops:new int[]{0,1,5}){
            Rig r=new Rig(target);Guest twin=new Guest();r.now=443;long debt=443;int turns=0;
            while(debt>target/48){int ms=(int)Math.min(100,debt);r.advance(ms,debt);twin.advance(1,ms,new int[1]);debt-=ms;
                check(r.guest.drains==0&&r.pump.latency().summary().offeredFrames==0,"owed startup has no drain/offer at "+target);
                if(turns++>=hops)r.run();}
            for(int i=turns;i<=hops;i++){r.now+=16;r.advance(16,16);twin.advance(1,16,new int[1]);
                check(r.guest.drains==0,"deferred preparation admits nothing");}
            r.run();check(r.creates==1&&r.sink.starts==1&&r.sink.reads==0&&r.sink.writes==0,"prepare starts once without occupancy or PCM");
            int ms=(int)Math.max(1,debt);long first=r.guest.progress*48;r.advance(ms,ms);twin.advance(1,ms,new int[1]);
            check(r.guest.drains==1&&r.pump.latency().summary().offeredFrames==ms*48,"first ready current turn is live at "+target);
            r.run();for(int i=0;i<ms*48;i++)check(r.sink.accepted.get(i)==sample(first+i),"literal current first samples");
            r.backend.button(1,0,true);twin.button(1,0,true);r.backend.button(1,0,false);twin.button(1,0,false);r.backend.save(1);twin.save(1);
            check(r.guest.progress==twin.progress&&r.guest.history.equals(twin.history)&&r.guest.inputs.equals(twin.inputs)&&r.guest.saves.equals(twin.saves)
                    &&Arrays.equals(r.backend.committedPayloadHash(1),twin.committedPayloadHash(1)),"audio-disabled guest/input/save/hash equality");
            AudioPump.Summary s=r.pump.latency().summary();check(s.droppedFrames==0&&s.expiredFrames==0&&s.trimmedFrames==0,"no startup loss");
        }
    }
    // Reapplying the gate after first admission loses literal stall PCM.
    static void liveStall()throws Exception {
        Rig r=new Rig(2880);r.warm();int before=r.guest.drains;long offered=r.pump.latency().summary().offeredFrames;
        r.now+=443;long debt=443;while(debt>0){int ms=(int)Math.min(100,debt);long first=r.guest.progress*48;int offset=r.sink.accepted.size();
            r.advance(ms,debt);r.until(r.now+10);for(int i=0;i<ms*48;i++)check(r.sink.accepted.get(offset+i)==sample(first+i),"after-live stall literal waveform including gaps");debt-=ms;}
        check(r.guest.drains==before+5&&r.pump.latency().summary().offeredFrames==offered+21264,"after-live 443ms every request admitted");
    }
    // Gating negative debt breaks the supported lazy legacy path.
    static void legacy()throws Exception {
        for(long debt:new long[]{-1,-7,Long.MIN_VALUE}){
            Rig r=new Rig(2880);check(r.creates==0&&r.tasks.isEmpty(),"configure stays lazy");
            r.advance(16,debt);check(r.guest.drains==1&&r.pump.latency().summary().offeredFrames==768&&r.creates==0,"unknown debt immediate legacy admission");r.run();
            check(r.sink.accepted.size()==768&&r.creates==1,"legacy literal output");
        }
        Rig r=new Rig(2880);r.backend.advance(1,16,new int[1]);r.run();check(r.sink.accepted.size()==768,"direct legacy advance");
    }
    static void prepare(AudioPump p,long token)throws Exception {p.getClass().getDeclaredMethod("prepare",long.class).invoke(p,token);}
    static boolean ready(AudioPump p,long token)throws Exception{return (Boolean)p.getClass().getDeclaredMethod("ready",long.class).invoke(p,token);}
    static void lifecycle()throws Exception {
        for(String mode:new String[]{"off","invalidate","focus","cancel"}){
            Rig r=new Rig(2880);long old=r.pump.token();r.sink.onStart=()->{
                try{check(!ready(r.pump,r.pump.token()),"no readiness during start");}catch(Exception e){throw new AssertionError(e);}
                if(mode.equals("off"))r.pump.configure(false);else if(mode.equals("focus"))r.sink.focus.changed(false);
                else r.pump.invalidate();};
            prepare(r.pump,old);prepare(r.pump,old);check(r.creates==0&&r.tasks.size()==1&&!ready(r.pump,old),"prepare deferred and idempotent");r.run();
            check(!ready(r.pump,old)&&r.sink.writes==0&&r.sink.reads==0,"retired preparation never admits PCM");
            prepare(r.pump,old);r.run();check(r.creates==1,"stale prepare cannot recreate");
            if(mode.equals("focus")){check(r.sink.stops==0&&r.sink.suspends==1,"focus request retained");
                r.sink.onResume=()->{try{check(!ready(r.pump,r.pump.token()),"resume initialization precedes readiness");}catch(Exception e){throw new AssertionError(e);}};
                r.sink.focus.changed(true);check(!ready(r.pump,r.pump.token()),"unprocessed focus gain not ready");r.run();
                check(ready(r.pump,r.pump.token()),"processed focus gain ready");r.advance(16,16);r.run();check(r.guest.drains==1,"new focus epoch live");r.pump.configure(false);r.run();}
            check(r.sink.stops==1,"retired output stopped exactly once");
        }
        Rig queued=new Rig(2880);long stale=queued.pump.token();prepare(queued.pump,stale);queued.pump.invalidate();queued.run();
        check(queued.creates==0,"queued invalidation cancels preparation");
        for(boolean throwing:new boolean[]{false,true}){Rig r=new Rig(2880);r.sink.fail=!throwing;r.sink.throwStart=throwing;
            r.advance(16,16);r.run();for(int i=0;i<5;i++){r.advance(16,16);r.run();}
            check(r.creates==1&&r.sink.stops==1&&r.pump.latency().summary().outputFailures==1&&!r.backend.soundAccepting()&&r.guest.progress==96&&r.guest.drains==0,"failed prepare never retries; guest continues");}
        Rig focus=new Rig(2880);focus.warm();long old=focus.pump.token();focus.sink.focus.changed(false);focus.sink.focus.changed(true);
        check(!ready(focus.pump,focus.pump.token()),"coalesced focus not ready");focus.run();focus.advance(16,443);
        check(focus.guest.drains==1,"new epoch debt re-evaluated");focus.advance(16,16);focus.run();check(focus.guest.drains==2&&focus.pump.token()!=old,"new epoch current and live");
    }
    static void sourceFailures()throws Exception {
        for(String mode:new String[]{"enable","drain","null","pump","token"}){Rig r=new Rig(2880);r.warm();int before=r.guest.drains;long frames=r.pump.latency().summary().offeredFrames;int[] reached={0};
            if(mode.equals("enable"))r.guest.enableHook=()->{reached[0]++;throw new IllegalStateException("enable");};
            else if(mode.equals("drain"))r.guest.drainHook=()->{reached[0]++;throw new IllegalStateException("drain");};
            else r.guest.during=()->{reached[0]++;if(mode.equals("null"))r.guest.pcm=null;else if(mode.equals("pump"))r.selected=null;else r.pump.invalidate();};
            r.advance(16,443);r.run();check(reached[0]==1&&r.guest.progress==48,"post-warm rejection branch exercised "+mode);
            check(r.pump.latency().summary().offeredFrames==frames&&r.guest.drains==before+(mode.equals("enable")?0:1),"source failure isolated without stale PCM "+mode);
        }
    }
    public static void main(String[] args)throws Exception {
        String mode=args.length==0?"all":args[0];
        if(mode.equals("all")||mode.equals("cold"))cold();if(mode.equals("all")||mode.equals("stall"))liveStall();
        if(mode.equals("all")||mode.equals("legacy"))legacy();if(mode.equals("all")){lifecycle();sourceFailures();}
        System.out.println("AudioStartupTest: "+checks+" checks passed ("+mode+")");
    }
}
