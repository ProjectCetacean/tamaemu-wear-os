package io.github.projectcetacean.tamaemu;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** One generated-silence component experiment. No guest access, Activity launch, or persisted data. */
final class AudioQaInputTrackProbe {
    static final String TAG="TamaemuTrackProbe";
    static final long BUDGET_NS=3000000000L;
    static final int LIMIT=4096, WIDTH=20;
    private static boolean consumed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean active, cancelled;
    private long admittedAt;
    private WeakReference<MainActivity> owner=new WeakReference<>(null);
    private final java.util.concurrent.Executor executor;
    private final java.util.function.Function<MainActivity,Port> factory;
    private final Clock clock;
    AudioQaInputTrackProbe(){
        this(task->{Thread thread=new Thread(task,"qa-track-contract");thread.setDaemon(true);thread.start();},
                RealPort::new,new Clock(){
                    public long now(){return SystemClock.elapsedRealtimeNanos();}
                    public void idle(){LockSupport.parkNanos(500000);}
                });
    }
    AudioQaInputTrackProbe(java.util.concurrent.Executor executor,java.util.function.Function<MainActivity,Port> factory,Clock clock){
        this.executor=executor;this.factory=factory;this.clock=clock;
    }
    private View decor;
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnWindowFocusChangeListener focus=has->{if(!has)cancel();};
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener(){
        public void onViewAttachedToWindow(View view){}
        public void onViewDetachedFromWindow(View view){cancel();}
    };
    interface Clock { long now(); void idle(); }
    interface Port {
        boolean start(AudioPump.Focus focus);
        long head();
        // threshold, buffer, capacity, performance, internal route id, route type, underruns, play state, known, rate
        int[] grant();
        int write(short[] zeros,int length);
        void close();
    }
    interface Factory { Port create(); }
    private static final class RealPort implements Port {
        private final ForegroundAudio.Track track;
        RealPort(MainActivity activity){track=new ForegroundAudio.Track(activity.getApplicationContext(),ForegroundAudio.normalQueueFrames(activity));}
        public boolean start(AudioPump.Focus focus){return track.start(focus);}
        public long head(){return track.qaRawHead();}
        public int[] grant(){return track.qaGrant();}
        public int write(short[] zeros,int length){return track.write(zeros,0,length);}
        public void close(){track.stop();}
    }
    boolean busy(){return active;}
    void cancel(){cancelled=true;}
    /** The read-only reflection stays QA-only; no production lifecycle hooks or file probes. */
    static boolean neutral(MainActivity activity) {
        if(activity==null || !AudioQaInputProvider.enabled(activity) || !activity.hasWindowFocus())return false;
        try {
            Field state=MainActivity.class.getDeclaredField("sessionState");state.setAccessible(true);
            Field audio=TamaemuApplication.class.getDeclaredField("foregroundAudio");audio.setAccessible(true);
            return state.get(activity)==EmulatorSession.State.MISSING_FIRMWARE && audio.get(activity.getApplication())==null;
        } catch(ReflectiveOperationException|RuntimeException unavailable){return false;}
    }
    boolean begin(MainActivity activity) {
        if(Looper.myLooper()!=Looper.getMainLooper() || active || consumed || !neutral(activity))return false;
        View view=activity.getWindow().getDecorView();
        if(!view.isAttachedToWindow() || !view.hasWindowFocus())return false;
        consumed=true;active=true;cancelled=false;admittedAt=clock.now();owner=new WeakReference<>(activity);decor=view;
        observer=view.getViewTreeObserver();observer.addOnWindowFocusChangeListener(focus);
        view.addOnAttachStateChangeListener(attachment);
        // UI validity is published by main; the worker never reads View state.
        main.post(this::watch);
        try {
            executor.execute(()->{
                Result result=measure(()->factory.apply(activity),clock,()->!cancelled,admittedAt);
                try { dump(result,clock); }
                finally {main.post(this::finish);}
            });
        } catch(RuntimeException|Error failure) {
            Log.i(TAG,"phase=end reason=worker_start_failed released=1 incomplete=1");finish();
        }
        return true;
    }
    private void watch(){
        if(!active)return;
        MainActivity activity=owner.get();
        if(clock.now()-admittedAt>=BUDGET_NS || !neutral(activity) || activity.getWindow().getDecorView()!=decor || !decor.isAttachedToWindow() || !decor.hasWindowFocus())cancel();
        if(!cancelled)main.postDelayed(this::watch,1);
    }
    private void finish(){
        main.removeCallbacksAndMessages(null);
        if(observer!=null&&observer.isAlive())observer.removeOnWindowFocusChangeListener(focus);
        if(decor!=null)decor.removeOnAttachStateChangeListener(attachment);
        observer=null;decor=null;owner.clear();active=false;
        long elapsed=clock.now()-admittedAt;
        Log.i(TAG,"phase=disarmed active=0 listenersRemoved=1 elapsedNs="+elapsed+" deadlineExceeded="+(elapsed>=BUDGET_NS?1:0));
    }
    static final class Result {
        final long[] events=new long[LIMIT*WIDTH];
        int count; long accepted,begin,end,closeBegin,closeEnd;
        String reason="incomplete",restart="INCONCLUSIVE",precision="INCONCLUSIVE";
        boolean released, startupProgress, crossingProgress;
        long endpoint=-1,prefix,crossStart=-1,earlyEnd=-1,maxGap,maxUnobservedGap,maxBracket,maxPlateau,maxJump;
        long minLower=Long.MAX_VALUE,maxLower=Long.MIN_VALUE,minUpper=Long.MAX_VALUE,maxUpper=Long.MIN_VALUE;
        long previousA=-1,previousB=-1,plateauStart=-1,previousH=-1;
    }
    private static final class Halt extends RuntimeException {
        final String reason;
        Halt(String reason){super(null,null,false,false);this.reason=reason;}
    }
    private static final class Run {
        final Result r=new Result();final Clock clock;final BooleanSupplier valid;final Factory factory;
        final short[] zeros=new short[9600];
        Port port;int[] grant;int phase,routeEpoch=1,playEpoch;long lastHead;volatile boolean lostFocus;
        Run(Factory factory,Clock clock,BooleanSupplier valid,long begin){this.factory=factory;this.clock=clock;this.valid=valid;r.begin=begin;}
        long time(){return clock.now()-r.begin;}
        void check(){
            if(time()>=BUDGET_NS)throw new Halt("deadline");
            if(lostFocus)throw new Halt("focus_lost");
            if(!valid.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Halt("cancelled");
            if(r.count>=LIMIT-2)throw new Halt("overflow");
        }
        void grants(){
            check();int[] next=port.grant();check();
            if(next.length!=10)throw new Halt("unsupported_grant");
            if(next[8]!=1||next[0]<2||next[0]>zeros.length||next[1]<next[0]||next[2]<next[1]
                    ||next[4]<0||next[6]<0||next[7]!=3||next[9]!=48000){
                grant=next;event(5,time(),time(),lastHead,0,0);throw new Halt("unsupported_grant");
            }
            if(grant!=null){
                for(int i:new int[]{0,1,2,3,4,5,7,8,9})if(next[i]!=grant[i]){
                    if(next[4]!=grant[4])routeEpoch++;
                    grant=next;event(5,time(),time(),lastHead,0,0);throw new Halt("grant_changed");
                }
                if(next[6]<grant[6])throw new Halt("underrun_reset");
            }
            grant=next;
        }
        void event(int kind,long a,long b,long head,int requested,int accepted){
            if(r.count>=LIMIT-2)throw new Halt("overflow");
            int i=r.count++*WIDTH;
            long[] values={r.count,kind,phase,1,0,playEpoch,a,b,head,requested,accepted,r.accepted,
                    grant==null?-1:grant[0],grant==null?-1:grant[1],grant==null?-1:grant[2],grant==null?-1:grant[3],
                    grant==null||grant[4]<0?-1:routeEpoch,grant==null?-1:grant[5],grant==null?-1:grant[6],grant==null?-1:grant[9]};
            System.arraycopy(values,0,r.events,i,WIDTH);
        }
        long head(){
            grants();long a=time();long h;
            try {h=port.head();}
            catch(RuntimeException|LinkageError failure){event(2,a,time(),-1,0,0);throw failure;}
            long b=time();event(2,a,b,h,0,0);check();
            if(h<lastHead||h>r.accepted||h>0xffffffffL)throw new Halt("head_reset");
            lastHead=h;
            if(phase==3){
                // Integer units: frame * 1,000,000. No floating point or GCD/minimum-step inference.
                long lower=48*a-1000000*h,upper=48*b-1000000*h;
                r.minLower=Math.min(r.minLower,lower);r.maxLower=Math.max(r.maxLower,lower);
                r.minUpper=Math.min(r.minUpper,upper);r.maxUpper=Math.max(r.maxUpper,upper);
                r.maxBracket=Math.max(r.maxBracket,b-a);
                if(r.previousA>=0){r.maxGap=Math.max(r.maxGap,a-r.previousA);r.maxUnobservedGap=Math.max(r.maxUnobservedGap,a-r.previousB);
                    if(h==r.previousH)r.maxPlateau=Math.max(r.maxPlateau,b-r.plateauStart);
                    else {r.maxJump=Math.max(r.maxJump,h-r.previousH);r.plateauStart=a;}}
                else r.plateauStart=a;
                r.previousA=a;r.previousB=b;r.previousH=h;
            }
            if(phase==5&&h>r.endpoint)r.earlyEnd=b;
            return h;
        }
        int write(int count){
            grants();if(count<1||count>zeros.length)throw new Halt("invalid_write_request");
            long a=time();
            if(phase==6&&r.crossStart<0&&r.accepted+count>=r.endpoint+grant[0])r.crossStart=a;
            int n;
            try {n=port.write(zeros,count);}
            catch(RuntimeException|LinkageError failure){event(3,a,time(),lastHead,count,-1);throw failure;}
            long b=time();
            if(n<0||n>count){event(3,a,b,lastHead,count,n);throw new Halt("invalid_acceptance");}
            r.accepted+=n;event(3,a,b,lastHead,count,n);check();return n;
        }
        void idle(){check();clock.idle();check();}
        void fill(long goal,long budget){
            long until=time()+budget;
            while(r.accepted<goal){if(time()>=until)throw new Halt("write_timeout");write((int)Math.min(zeros.length,goal-r.accepted));idle();}
        }
        void observe(long duration){long until=time()+duration;while(time()<until){head();idle();}}
        void progress(long base){long until=time()+150000000;while(head()<=base){if(time()>=until)throw new Halt("no_progress");idle();}}
        void execute(){
            check();port=factory.create();check();
            long a=time();boolean started;
            try {started=port.start(gain->{if(!gain)lostFocus=true;});}
            catch(RuntimeException|LinkageError failure){event(1,a,time(),-1,0,0);throw failure;}
            long b=time();
            if(started)playEpoch=1;
            event(1,a,b,-1,0,0);check();if(!started)throw new Halt("focus_denied");
            grants();if(head()!=0)throw new Halt("head_reset");
            phase=1;int prefix=Math.min(240,grant[0]-1);
            // One first write only. A zero acceptance cannot be recast as a valid prefix.
            int accepted=write(prefix);if(accepted<=0||accepted>=grant[0])throw new Halt("invalid_prefix");
            observe(50000000);
            phase=2;fill(grant[0],100000000);progress(accepted);r.startupProgress=true;
            // Keep this epoch fed, then measure only the stable continuous window.
            fill(r.accepted+grant[0],100000000);
            phase=3;int steadyUnderruns=grant[6];long until=time()+250000000;
            while(time()<until){
                long h=head();if(grant[6]!=steadyUnderruns||h==r.accepted)throw new Halt("steady_underrun");
                long missing=grant[0]-(r.accepted-h);if(missing>0)write((int)missing);idle();
            }
            if(r.previousA<0)throw new Halt("missing_head_window");
            r.precision=r.maxUpper-r.minLower<=240000000L?"SAMPLED_BOUND_240":
                    r.maxLower-r.minUpper>240000000L?"SAMPLED_VIOLATION_240":"INCONCLUSIVE";
            phase=4;r.endpoint=r.accepted;until=time()+250000000;
            while(true){long h=head();if(h==r.endpoint&&grant[6]>steadyUnderruns)break;
                if(time()>=until)throw new Halt("missing_underrun");idle();}
            phase=5;r.prefix=write(prefix);
            if(r.prefix<=0||r.prefix>=grant[0])throw new Halt("invalid_prefix");
            observe(50000000);
            phase=6;
            // First potentially crossing write's START is conservative even if it accepts short/zero.
            long goal=r.endpoint+grant[0];fill(goal,100000000);
            progress(r.endpoint+r.prefix);r.crossingProgress=true;
            if(r.earlyEnd>=0&&r.earlyEnd<r.crossStart)r.restart="EARLY_RESTART_TESTED_PATH";
            r.reason="complete";
        }
    }
    static Result measure(Factory factory,Clock clock,BooleanSupplier valid){
        return measure(factory,clock,valid,clock.now());
    }
    private static Result measure(Factory factory,Clock clock,BooleanSupplier valid,long begin){
        Run run=new Run(factory,clock,valid,begin);Result r=run.r;
        try {run.execute();}
        catch(Halt halt){r.reason=halt.reason;}
        catch(RuntimeException|LinkageError failure){r.reason="error";}
        finally {
            if(!"complete".equals(r.reason)){
                int terminal=r.count++*WIDTH;java.util.Arrays.fill(r.events,terminal,terminal+WIDTH,-1);r.events[terminal]=r.count;r.events[terminal+1]=6;
                r.events[terminal+2]=run.phase;r.events[terminal+3]=1;r.events[terminal+5]=run.playEpoch;
                r.events[terminal+6]=run.time();r.events[terminal+7]=run.time();r.events[terminal+11]=r.accepted;
            }
            r.closeBegin=run.time();
            try {if(run.port!=null)run.port.close();r.released=true;}
            catch(RuntimeException|LinkageError failure){r.reason="cleanup_failed";}
            r.closeEnd=run.time();r.end=r.closeEnd;
            // Reserved scalar cleanup slot, including failure. stop() owns pause/flush/release/focus abandon.
            int i=r.count++*WIDTH;java.util.Arrays.fill(r.events,i,i+WIDTH,-1);r.events[i]=r.count;r.events[i+1]=4;r.events[i+2]=7;
            r.events[i+3]=1;r.events[i+4]=-1;r.events[i+5]=run.playEpoch;r.events[i+6]=r.closeBegin;r.events[i+7]=r.closeEnd;
            r.events[i+10]=r.released?1:0;r.events[i+11]=r.accepted;
            if("complete".equals(r.reason)){
                if(run.lostFocus)r.reason="focus_lost";
                else if(!valid.getAsBoolean())r.reason="cancelled";
            }
            if(r.end>=BUDGET_NS)r.reason="deadline";
            if(!"complete".equals(r.reason)){r.restart="INCONCLUSIVE";r.precision="INCONCLUSIVE";}
        }
        return r;
    }
    private static void dump(Result r,Clock clock){
        int dumped=0;
        for(int row=0;row<r.count;row++){
            if(clock.now()-r.begin>=BUDGET_NS)break;
            StringBuilder line=new StringBuilder("phase=event values=");
            for(int col=0;col<WIDTH;col++){if(col>0)line.append(',');line.append(r.events[row*WIDTH+col]);}
            Log.i(TAG,line.toString());dumped++;
        }
        boolean incomplete=clock.now()-r.begin>=BUDGET_NS||dumped!=r.count||!"complete".equals(r.reason);
        Log.i(TAG,"phase=end beginElapsedNs="+r.begin+" events="+r.count+" dumped="+dumped+" released="+(r.released?1:0)
                +" closeBeginNs="+r.closeBegin+" closeEndNs="+r.closeEnd+" reason="+r.reason+" incomplete="+(incomplete?1:0)
                +" restart="+(incomplete?"INCONCLUSIVE":r.restart)+" shallowState=UNKNOWN guestBoundary=UNOBSERVED"
                +" precision="+(incomplete?"INCONCLUSIVE":r.precision)+" accepted="+r.accepted+" B="+r.endpoint+" p="+r.prefix
                +" crossingStartNs="+r.crossStart+" earlyHeadEndNs="+r.earlyEnd+" startupProgress="+r.startupProgress+" crossingProgress="+r.crossingProgress
                +" maxReadGapNs="+r.maxGap+" maxUnobservedGapNs="+r.maxUnobservedGap+" maxBracketNs="+r.maxBracket+" maxPlateauNs="+r.maxPlateau+" maxJump="+r.maxJump
                +" minLower="+r.minLower+" maxLower="+r.maxLower+" minUpper="+r.minUpper+" maxUpper="+r.maxUpper);
    }
}
