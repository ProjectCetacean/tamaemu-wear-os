package io.github.projectcetacean.tamaemu;
import java.util.*;
public final class AudioBackendTest {
 static final class Guest implements EmulatorSession.Backend {
  int rendered,headless,saves,buttons;Runnable during;boolean throwing;public long open(String r,String s){return 7;}public void close(long h){}public int width(long h){return 1;}public int height(long h){return 1;}
  public void advance(long h,int ms,int[] p){rendered+=ms;p[0]=123;if(during!=null)during.run();if(throwing)throw new IllegalStateException("guest failed");}public void headless(long h,int ms){headless+=ms;}
  public void button(long h,int b,boolean d){buttons++;}public boolean save(long h){saves++;return true;}
 }
 /** Guest PCM script: silent, then a tone 480 frames into the second batch after the applied input. */
 static void onsetMeasuredFromAppliedInput()throws Exception {
  Guest guest=new Guest();AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();int[] batches={0};
  AudioBackend bridge=new AudioBackend(guest,new AudioBackend.NativeAudio(){
   public void enabled(long h,boolean e){}
   public short[] drain(long h,int max){short[] pcm=new short[1584];if(++batches[0]==3)Arrays.fill(pcm,480,1584,(short)900);return pcm;}
  },()->f.pump,()->f.now);
  f.pump.configure(true);int[] pixels={0};
  f.now=1000;bridge.advance(7,33,pixels);f.run();
  f.now=1034;bridge.button(7,0,true);bridge.inputApplied(7,0,true,1030);
  f.now=1067;bridge.advance(7,33,pixels);f.run();AudioPumpTest.check(f.pump.latency().summary().onsets==0,"silent batch is not an onset");
  f.now=1100;bridge.advance(7,33,pixels);f.run();
  AudioPump.Onset o=f.pump.latency().lastOnset();AudioPump.Summary s=f.pump.latency().summary();
  AudioPumpTest.check(o!=null&&s.onsets==1&&o.sequence==3&&o.correlated,"one correlated onset in the third offered batch: "+s.line());
  AudioPumpTest.check(Math.abs(o.firmwareReactionMs-43)<=1,"firmware reaction is one batch plus480 frames: "+o.firmwareReactionMs);
  AudioPumpTest.check(Math.abs(o.softwareMs-(1100+480/48.0-1030-43))<=1,"software from admission to scheduled onset: "+o.softwareMs);
  bridge.inputApplied(7,0,false,1101);bridge.headless(7,100);f.run();
  AudioPumpTest.check(f.pump.latency().summary().onsets==1&&f.output.writes.size()==3,"headless records no onset and drops pending audio");
 }
 interface Edge { void run()throws Exception; }
 /** Synthetic tone only: after a trigger, sound starts 240 frames (5 ms) into the next batch and lasts `length` batches. */
 static final class Tone implements AudioBackend.NativeAudio {
  boolean start;int left;final int length,frames;Tone(int length,int frames){this.length=length;this.frames=frames;}
  public void enabled(long h,boolean e){}
  public short[] drain(long h,int max){short[] pcm=new short[frames];
   if(start){start=false;left=length-1;Arrays.fill(pcm,240,frames,(short)900);}
   else if(left>0){left--;Arrays.fill(pcm,(short)900);}
   return pcm;}
 }
 /** The real AudioBackend and AudioPump, driven in LifeOwner's order: the native edge, then the observational hook. */
 static final class Rig {
  final AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();final Tone tone;final AudioBackend bridge;final int[] px={0};
  Rig(int toneBatches)throws Exception{this(toneBatches,1584);}
  Rig(int toneBatches,int frames)throws Exception{tone=new Tone(toneBatches,frames);bridge=new AudioBackend(new Guest(),tone,()->f.pump,()->f.now);f.pump.configure(true);f.now=1000;batches(5);}
  void batches(int n)throws Exception{for(int i=0;i<n;i++){f.now+=33;bridge.advance(7,33,px);f.run();}}
  void down(int b){bridge.button(7,b,true);bridge.inputApplied(7,b,true,f.now-1);}
  void up(int b){bridge.button(7,b,false);bridge.inputApplied(7,b,false,f.now-1);}
  /** A tap shorter than the 40 ms pulse: the up edge is applied now, the native release comes later. */
  void upApplied(int b){bridge.inputApplied(7,b,false,f.now-1);}
  /** One full press: down, the guest tone triggered by it, release after `releaseAfter` batches. */
  void press(int releaseAfter)throws Exception{down(0);tone.start=true;batches(releaseAfter);up(0);batches(30-releaseAfter);}
  AudioPump.Summary summary(){return f.pump.latency().summary();}
 }
 static long correlated(AudioPump.Summary s){return s.onsets-s.uncorrelatedOnsets;}
 /** Final-review finding: a release after the tone must not make the next press uncorrelated. */
 static void releaseAfterToneKeepsNextPressCorrelated()throws Exception {
  Rig r=new Rig(2);for(int p=0;p<20;p++)r.press(4);AudioPump.Summary s=r.summary();
  AudioPumpTest.check(s.onsets==20&&correlated(s)==20,"20 down/up presses with release at 132 ms after a 66 ms tone all correlate: "+s.line());
  AudioPumpTest.check(Math.abs(s.p95SoftwareMs-34)<=1&&Math.abs(s.p50SoftwareMs-34)<=1,"honest software p95 from each press-down admission: "+s.line());
  System.out.println("releaseAfterToneKeepsNextPressCorrelated: "+s.line());
 }
 static void releaseDuringToneStaysCorrelated()throws Exception {
  for(int[] c:new int[][]{{2,1},{6,3}}){Rig r=new Rig(c[0]);for(int p=0;p<20;p++)r.press(c[1]);AudioPump.Summary s=r.summary();
   AudioPumpTest.check(s.onsets==20&&correlated(s)==20&&Math.abs(s.p95SoftwareMs-34)<=1,"release during a "+c[0]+"-batch tone is not a new onset: "+s.line());}
 }
 /** A release before any observed onset invalidates the press; release-triggered tones never become samples. */
 static void releaseBeforeOnsetStaysUncorrelated()throws Exception {
  Rig r=new Rig(2);
  for(int p=0;p<20;p++){r.down(0);r.batches(1);
   if(p%2==0){r.up(0);r.tone.start=true;r.batches(29);}
   else{r.upApplied(0);r.tone.start=true;r.batches(1);r.bridge.button(7,0,false);r.batches(28);}}
  AudioPump.Summary s=r.summary();
  AudioPumpTest.check(s.onsets==20&&correlated(s)==0&&Double.isNaN(s.p95SoftwareMs)&&s.line().contains("insufficient"),"onset after release is uncorrelated: "+s.line());
  Rig only=new Rig(2);for(int p=0;p<20;p++){only.down(0);only.batches(4);only.up(0);only.tone.start=true;only.batches(26);}
  s=only.summary();
  AudioPumpTest.check(s.onsets==20&&correlated(s)==0&&Double.isNaN(s.p95SoftwareMs),"release-only tones stay insufficient: "+s.line());
 }
 /** A forced native release (checkpoint releaseButtons) ends the press; releasing an unheld button does not. */
 static void nativeReleaseEndsPress()throws Exception {
  Rig r=new Rig(2);r.down(0);for(int b=0;b<3;b++)r.bridge.button(7,b,false);r.tone.start=true;r.batches(30);
  AudioPump.Summary s=r.summary();AudioPumpTest.check(s.onsets==1&&correlated(s)==0,"onset after a forced native release is uncorrelated: "+s.line());
  r.down(0);r.bridge.button(7,1,false);r.tone.start=true;r.batches(4);r.up(0);r.batches(26);
  s=r.summary();AudioPumpTest.check(s.onsets==2&&correlated(s)==1,"releasing an unheld button leaves the press measurable: "+s.line());
 }
 /** Several down contacts before one onset stay ambiguous; the press after them is measurable again. */
 static void multipleContactsStayAmbiguous()throws Exception {
  Rig r=new Rig(2);
  r.down(0);r.down(1);r.tone.start=true;r.batches(4);r.up(0);r.up(1);r.batches(26);
  AudioPump.Summary s=r.summary();AudioPumpTest.check(s.onsets==1&&correlated(s)==0,"two held contacts before an onset: "+s.line());
  r.down(0);r.batches(1);r.up(0);r.batches(3);r.down(1);r.tone.start=true;r.batches(4);r.up(1);r.batches(26);
  s=r.summary();AudioPumpTest.check(s.onsets==2&&correlated(s)==0,"an unanswered earlier contact keeps the next onset ambiguous: "+s.line());
  r.press(4);s=r.summary();AudioPumpTest.check(s.onsets==3&&correlated(s)==1,"next isolated press correlates: "+s.line());
 }
 /** An onset observed before its write keeps its own admission across a release and a new press. */
 static void waitingOnsetKeepsAdmissionAcrossEdges()throws Exception {
  // 1200-frame batches: two pending stay under the target, so no onset frame is trimmed.
  Rig r=new Rig(2,1200);r.f.output.accept=0;long admitted=r.f.now-1;r.down(0);r.tone.start=true;r.batches(1);
  AudioPumpTest.check(r.f.pump.latency().lastOnset()==null&&r.f.live()==1,"onset waits for its refused write");
  // The new press lands on the still-sounding tone: its own onset is uncorrelated and must not evict the waiting one.
  r.up(0);r.down(1);r.batches(1);long writeAt=r.f.now+AudioPump.RETRY_MS;
  AudioPumpTest.check(r.f.pump.latency().summary().onsets==2,"second press counted while the first still waits");
  r.f.output.accept=-1;r.f.until(writeAt);
  AudioPump.Onset o=r.f.pump.latency().lastOnset();double expected=writeAt+240/48.0-admitted-5;
  AudioPumpTest.check(o!=null&&o.correlated&&Math.abs(o.softwareMs-expected)<=0.01,"waiting onset uses its own admission "+expected+": "+(o==null?"none":o.softwareMs));
 }
 static void unavailableSoundObservationKeepsGuestUsable()throws Exception {
  Guest guest=new Guest();AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();int[] failure={0};
  AudioBackend bridge=new AudioBackend(guest,new Tone(2,1584),()->{
   if(failure[0]==1)throw new IllegalStateException("output unavailable");
   if(failure[0]==2)throw new UnsatisfiedLinkError("output unavailable");
   return f.pump;
  },()->f.now);
  for(int kind:new int[]{1,2}){failure[0]=kind;AudioPumpTest.check(!bridge.soundAccepting(),"unavailable observation falls back safely: "+kind);}
  failure[0]=0;bridge.advance(7,16,new int[1]);bridge.button(7,0,true);
  AudioPumpTest.check(bridge.save(7)&&guest.rendered==16&&guest.buttons==1,"observational failure leaves guest advancement, input and saves usable");
 }
 /** Invalid/throwing platform observations disable audio without changing guest operations. */
 static void invalidTransportGrantKeepsGuestUsable()throws Exception {
  for(boolean throwing:new boolean[]{false,true}){
   Guest guest=new Guest();AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();
   AudioPumpTest.ThresholdOutput out=AudioPumpTest.threshold(f,2886,throwing?2886:9601);out.throwMetric=throwing;
   AudioBackend bridge=new AudioBackend(guest,new Tone(2,768),()->f.pump,()->f.now);
   bridge.advance(7,16,new int[1]);f.run();AudioPumpTest.check(!bridge.soundAccepting(),"invalid transport fails sound only");
   bridge.advance(7,16,new int[1]);bridge.button(7,0,true);bridge.headless(7,100);
   AudioPumpTest.check(bridge.save(7)&&guest.rendered==32&&guest.headless==100&&guest.buttons==1,"guest care/time/input/save remain usable");
   AudioPumpTest.conserved(f.pump.latency().summary(),"invalid transport bridge");
  }
 }
 // Stale focus or supplier identity must reject both the elapsed request and its detached PCM.
 static void completedAdvanceEpochsAndErrors()throws Exception {
  for(String mode:new String[]{"focus","replacement","throw","drain","enable","null","empty","short","oversized","headless","reopen"}){
   AudioPumpTest.Fixture f=new AudioPumpTest.Fixture(),other=new AudioPumpTest.Fixture();
   f.pump.configure(true);other.pump.configure(true);Guest guest=new Guest();AudioPump[] selected={f.pump};int[] drains={0};
   AudioBackend.NativeAudio nativeAudio=new AudioBackend.NativeAudio(){
    public void enabled(long h,boolean on){if(mode.equals("enable"))throw new IllegalStateException("enable failed");}
    public short[] drain(long h,int max){drains[0]++;if(mode.equals("drain"))throw new IllegalStateException("drain failed");
     if(mode.equals("null"))return null;if(mode.equals("empty"))return new short[0];
     return new short[mode.equals("oversized")?9601:mode.equals("short")?1:768];}
   };
   AudioBackend bridge=new AudioBackend(guest,nativeAudio,()->selected[0],()->f.now);
   if(mode.equals("focus"))guest.during=()->{f.pump.configure(false);f.pump.configure(true);};
   if(mode.equals("replacement"))guest.during=()->selected[0]=other.pump;
   guest.throwing=mode.equals("throw");
   try{bridge.advance(7,16,new int[1]);AudioPumpTest.check(!guest.throwing,"guest error propagated");}
   catch(IllegalStateException expected){AudioPumpTest.check(guest.throwing,"only guest failure escapes");}
   f.run();other.run();AudioPump.Summary summary=f.pump.latency().summary();
   boolean real=mode.equals("short")||mode.equals("headless")||mode.equals("reopen");
   AudioPumpTest.check(summary.offeredFrames==(real?(mode.equals("short")?1:768):0),"only eligible literal PCM is offered "+mode+": "+summary.line());
   AudioPumpTest.check(summary.onsets==0,"no invented onset "+mode);
   if(mode.equals("headless")){bridge.headless(7,100);f.run();}
   if(mode.equals("reopen")){bridge.open("rom","save");f.run();AudioPumpTest.check(f.output.stops==1,"reopen retires old audio");}
   AudioPumpTest.check(bridge.save(7)&&guest.rendered==16,"guest progress/save survive audio observation "+mode);
   AudioPumpTest.conserved(f.pump.latency().summary(),"backend epoch "+mode);
  }
 }
 /** Expected focus loss already ends the PCM epoch, but retains its legitimate gain callback. */
 static void retainedFocusSurvivesUnfocusedAdvance()throws Exception {
  AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();f.pump.configure(true);
  Guest guest=new Guest();AudioBackend bridge=new AudioBackend(guest,new Tone(1,768),()->f.pump,()->f.now);
  bridge.advance(7,16,new int[1]);f.run();AudioPump.Focus focus=f.output.focus;
  bridge.open("rom","save");f.run(); // This reopens an existing producer, so establish its new request.
  bridge.advance(7,16,new int[1]);f.run();focus=f.output.focus;
  long oldToken=f.pump.token(),offered=f.pump.latency().summary().offeredFrames;
  focus.changed(false);f.run();
  bridge.advance(7,33,new int[1]);bridge.advance(7,33,new int[1]);f.run();
  AudioPumpTest.check(f.pump.latency().summary().offeredFrames==offered,"unfocused requests donate no PCM");
  focus.changed(true);f.run();
  AudioPumpTest.check(bridge.soundAccepting(),"retained focus gain remains effective after unfocused advances");
  AudioPumpTest.check(f.pump.offerCompletedAdvance(oldToken,100,new short[768])<0,"old producer cannot donate to regained focus");
  bridge.advance(7,16,new int[1]);f.run();
  AudioPumpTest.check(f.pump.latency().summary().offeredFrames==offered+768,"regained producer publishes its own PCM");
  final AudioPump.Focus retained=focus;guest.during=()->{retained.changed(false);retained.changed(true);};
  bridge.advance(7,16,new int[1]);f.run();guest.during=null;
  AudioPumpTest.check(f.pump.latency().summary().offeredFrames==offered+768,"mid-call focus race rejects detached old PCM");
  retained.changed(false);f.run();retained.changed(true);f.run();
  AudioPumpTest.check(bridge.soundAccepting(),"mid-call rejection also retains legitimate focus request");
  bridge.advance(7,16,new int[1]);f.run();
  AudioPumpTest.check(f.pump.latency().summary().offeredFrames==offered+1536,"new epoch resumes after mid-call rejection");
 }
 /** Empty drains and zero-time save captures cannot erase the preceding audible silence/admission. */
 static void emptyAndZeroCapturePreserveAdmission()throws Exception {
  AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();f.pump.configure(true);int[] call={0};
  AudioBackend bridge=new AudioBackend(new Guest(),new AudioBackend.NativeAudio(){
   public void enabled(long h,boolean e){}public short[] drain(long h,int max){call[0]++;
    if(call[0]==2)return new short[0];short[] pcm=new short[768];if(call[0]==3)pcm[0]=9;return pcm;}
  },()->f.pump,()->f.now);
  bridge.advance(7,16,new int[1]);f.run();bridge.inputApplied(7,0,true,0);
  bridge.advance(7,0,new int[1]);bridge.save(7);bridge.advance(7,16,new int[1]);f.run();
  AudioPumpTest.check(f.pump.latency().summary().offeredFrames==768&&call[0]==2,"empty and render do not invent batches");
  bridge.advance(7,16,new int[1]);f.run();
  AudioPump.Onset onset=f.pump.latency().lastOnset();
  AudioPumpTest.check(onset!=null&&onset.correlated&&onset.sequence==2,"empty and capture retain real admission until actual onset");
 }
 public static void main(String[] args)throws Exception {
  Guest guest=new Guest();AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();int[] enabled={0},drains={0};boolean[] fail={false};
  AudioBackend bridge=new AudioBackend(guest,new AudioBackend.NativeAudio(){
   public void enabled(long h,boolean e){if(fail[0])throw new UnsatisfiedLinkError();enabled[0]=e?1:-1;}
   public short[] drain(long h,int max){if(fail[0])throw new IllegalStateException();AudioPumpTest.check(max==9600,"bounded native drain");drains[0]++;return new short[]{42};}
  },()->f.pump,()->f.now);
  int[] pixels={0};bridge.advance(7,20,pixels);f.run();AudioPumpTest.check(enabled[0]==-1&&drains[0]==0&&pixels[0]==123,"inactive audio does not alter rendering");
  f.pump.configure(true);bridge.advance(7,20,pixels);f.run();AudioPumpTest.check(enabled[0]==1&&drains[0]==1&&f.output.writes.size()==1,"positive foreground generates detached output");
  bridge.advance(7,0,pixels);f.run();AudioPumpTest.check(drains[0]==1&&enabled[0]==1,"zero-time render neither drains nor resets phase");
  bridge.advance(7,20,pixels);bridge.headless(7,100);f.run();AudioPumpTest.check(enabled[0]==-1&&drains[0]==2&&f.output.writes.size()==1&&guest.headless==100,"headless drops pending audio");
  fail[0]=true;bridge.advance(7,20,pixels);bridge.button(7,1,true);AudioPumpTest.check(bridge.save(7)&&guest.saves==1&&guest.buttons==1&&guest.rendered==80,"native audio failure cannot fail guest input or checkpoint");
  completedAdvanceEpochsAndErrors();emptyAndZeroCapturePreserveAdmission();
  onsetMeasuredFromAppliedInput();
  unavailableSoundObservationKeepsGuestUsable();
  invalidTransportGrantKeepsGuestUsable();
  Map<String,Edge> edges=new LinkedHashMap<>();
  edges.put("retainedFocusSurvivesUnfocusedAdvance",AudioBackendTest::retainedFocusSurvivesUnfocusedAdvance);
  edges.put("actualCallerReplayAndOmission",()->Pipeline.main(new String[]{"all"}));
  edges.put("repeatedActualCallerReplayAndOmission",Pipeline::repeated);
  edges.put("actualCallerMissingPcm",Pipeline::missingPcm);
  edges.put("trimsRespectPhysicalPlayback",Pipeline::trimsRespectPhysicalPlayback);
  edges.put("shortStallsKeepInitialHealthyPhase",Pipeline::shortStallsKeepInitialHealthyPhase);
  edges.put("releaseAfterToneKeepsNextPressCorrelated",AudioBackendTest::releaseAfterToneKeepsNextPressCorrelated);
  edges.put("releaseDuringToneStaysCorrelated",AudioBackendTest::releaseDuringToneStaysCorrelated);
  edges.put("releaseBeforeOnsetStaysUncorrelated",AudioBackendTest::releaseBeforeOnsetStaysUncorrelated);
  edges.put("nativeReleaseEndsPress",AudioBackendTest::nativeReleaseEndsPress);
  edges.put("multipleContactsStayAmbiguous",AudioBackendTest::multipleContactsStayAmbiguous);
  edges.put("waitingOnsetKeepsAdmissionAcrossEdges",AudioBackendTest::waitingOnsetKeepsAdmissionAcrossEdges);
  List<String> failed=new ArrayList<>();
  for(Map.Entry<String,Edge> t:edges.entrySet()){
   try{t.getValue().run();System.out.println("PASS "+t.getKey());}
   catch(RuntimeException|AssertionError e){failed.add(t.getKey());System.out.println("FAIL "+t.getKey()+": "+e);}
  }
  if(!failed.isEmpty())throw new AssertionError("AudioBackendTest failed: "+failed);
  System.out.println("AudioBackendTest: 6 rendered, zero-time, headless, bounded and failure isolation contracts, safe sound observation, plus "+edges.size()+" press/release edge tests passed");
 }

 // Permanent actual-caller regression: request timing and emitted waveforms are independent of the pump.
 static final class Pipeline {
    static void require(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
    static final int RATE=48, END=6500, TONE=4800;
    static final class Timer {
        final long due;final Runnable task;boolean cancelled;
        Timer(long due,Runnable task){this.due=due;this.task=task;}
    }
    static final class Samples {
        final short[] data=new short[500000];int size;
        void add(short v){data[size++]=v;}
        boolean same(Samples b){return size==b.size&&Arrays.equals(Arrays.copyOf(data,size),Arrays.copyOf(b.data,b.size));}
        short[] notes(){int a=0,b=size;while(a<b&&data[a]==0)a++;while(b>a&&data[b-1]==0)b--;return Arrays.copyOfRange(data,a,b);}
    }
    /** Implements only the guest/audio methods modeled by AudioBackendTest. */
    static final class Source implements EmulatorSession.Backend,AudioBackend.NativeAudio {
        String loss="";boolean lost;long missing;int tone=TONE;final boolean variation;final Samples expected=new Samples();
        long guest,emitted;boolean enabled;short[] ready=new short[0];
        Source(boolean variation){this.variation=variation;}
        public long open(String rom,String save){return 7;}
        public void close(long h){}
        public int width(long h){return 1;}
        public int height(long h){return 1;}
        public void button(long h,int b,boolean down){}
        public boolean save(long h){return true;}
        public void headless(long h,int ms){guest+=ms;}
        public void enabled(long h,boolean value){enabled=value;}
        // Synthetic cumulative timing: borrow 64 frames in each 99 ms cycle's middle third,
        // repay them in its last third. This is stimulus, never a pump oracle.
        long endpoint(long ms){long phase=ms%99;return ms*RATE+(variation&&phase>=33&&phase<66?64:0);}
        public void advance(long h,int ms,int[] pixels){
            require(ms>=0&&ms<=100,"bounded logical guest request");pixels[0]=123;
            if(ms==0)return;
            long from=endpoint(guest);guest+=ms;long to=endpoint(guest);
            ready=enabled?new short[(int)(to-from)]:new short[0];
            for(int i=0;i<ready.length;i++){
                long frame=from+i;
                // Three unequal note spans and short zero gaps; sample identities vary.
                long x=frame-tone*RATE;
                short v=(short)(x>=0&&x<7776&&!(x>=1536&&x<1968)&&!(x>=4512&&x<5088)?1+(x%30000):0);
                ready[i]=v;
            }
        }
        public short[] drain(long h,int max){require(ready.length<=max,"source drain fits actual bound");short[] result=ready;ready=new short[0];
            if(!lost&&!loss.isEmpty()&&guest>=2000){lost=true;int keep=loss.equals("empty")?0:result.length/2;
                missing=result.length-keep;result=Arrays.copyOf(result,keep);}
            for(short v:result)expected.add(v);emitted+=result.length;return result;}
    }
    static final class Rig {
        long now,nextOwner=16,lastAccounted,debt,skipped,replayTurn=-1,lateUntil;
        final int gap,head;final boolean omission,stall,late;boolean repeat;long secondTrim;boolean checkTrueTrim;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();final List<Timer> timers=new ArrayList<>();
        final Source source;final Sink sink;final AudioPump pump;final AudioBackend backend;
        long minResidence=Long.MAX_VALUE,maxResidence,firstTrimAt=-1;int lateUnderruns;
        Rig(boolean varied,int head,int gap,boolean stall,boolean omission,boolean late){
            this.gap=gap;this.head=head;this.stall=stall;this.omission=omission;this.late=late;
            source=new Source(varied);sink=new Sink(this);
            pump=new AudioPump(tasks::add,(delay,task)->{Timer t=new Timer(now+delay,task);timers.add(t);return ()->t.cancelled=true;},()->now,()->sink);
            backend=new AudioBackend(source,source,()->pump,()->now);pump.configure(true);
        }
        void runTasks(){int n=0;while(!tasks.isEmpty()){require(++n<1000,"executor finite");tasks.remove().run();}}
        void retries(){
            for(;;){Timer first=null;for(Timer t:timers)if(!t.cancelled&&t.due<=now&&(first==null||t.due<first.due))first=t;
                if(first==null)break;first.cancelled=true;first.task.run();runTasks();}
        }
        void owner()throws Exception {
            debt+=now-lastAccounted;lastAccounted=now;
            int quantum=(int)Math.min(100,debt);
            if(omission&&(now==2200||repeat&&now==4200)){skipped+=debt-quantum;debt=quantum;}
            backend.advance(7,quantum,new int[1]);debt-=quantum;
            require(source.guest+skipped+debt==now,"owner no-skip ledger except deliberate omitted requests");
            if(debt>0)replayTurn=now+gap;
            else {replayTurn=-1;nextOwner=now+16;backend.advance(7,0,new int[1]);}
        }
        void run()throws Exception {
            for(int at=1;at<=(repeat?8500:END)+700;at++){
                now=at;sink.advance();
                boolean blocked=stall&&(at>=2000&&at<2200||repeat&&at>=4000&&at<4200);
                if(repeat&&at==4000)secondTrim=pump.latency().summary().trimmedFrames;
                if(at<=(repeat?8500:END)&&!blocked){
                    boolean due=replayTurn>=0?at>=replayTurn:at>=nextOwner;
                    if(late&&at>=(repeat?6000:4000)&&at<(repeat?6032:4032))due=false;
                    if(due)owner();
                }
                runTasks();retries();
                AudioPump.Summary s=pump.latency().summary();
                if(firstTrimAt<0&&s.trimmedFrames>0)firstTrimAt=now;
                if(checkTrueTrim&&s.trimmedFrames>0){
                    // Independent physical oracle: healthy playback starts at 64ms, after four
                    // 16ms requests cross the 2880-frame threshold. Sink consumption is exact.
                    long excess=now*RATE-sink.consumed-64L*RATE-source.missing-skipped*RATE;
                    require(s.trimmedFrames<=excess+1,"trim exceeds true outstanding excess at="+now
                            +" trimmed="+s.trimmedFrames+" allowed="+excess+" trueHead="+sink.consumed);
                }
                if(stall&&at<3200)require(s.trimmedFrames==0,"one full second played quiet after stall required");
                if(repeat&&at>=4000&&at<5200)require(s.trimmedFrames==secondTrim,"second stall needs its own played quiet second");
                if(at>=(repeat?6500:4300)&&at<=(repeat?6850:4650)){long residence=s.pendingFrames+sink.held();minResidence=Math.min(minResidence,residence);maxResidence=Math.max(maxResidence,residence);}
                if(at==(repeat?8500:END))lateUnderruns=sink.underruns;
            }
            AudioPump.Summary s=pump.latency().summary();
            require(s.offeredFrames==s.writtenFrames+s.pendingFrames+s.inflightFrames+s.trimmedFrames+s.droppedFrames+s.expiredFrames+s.flushedFrames,"pump conservation");
            require(s.offeredFrames==source.emitted&&source.emitted==source.expected.size,"only actual emitted frames offered");
            require(s.writtenFrames==sink.accepted.size&&sink.played.size==sink.accepted.size,"sink conservation and complete drain");
            require(Arrays.equals(source.expected.notes(),sink.accepted.notes())&&Arrays.equals(source.expected.notes(),sink.played.notes()),"note identities and internal gaps unchanged");
            require(s.droppedFrames==0&&s.expiredFrames==0&&s.flushedFrames==0&&s.pendingFrames==0,"only explicit quiet trim permitted");
            require(source.expected.size-sink.accepted.size==s.trimmedFrames,"every removed actual frame counted once");
            require(sink.maxHeld<=2880,"written-ahead ceiling");
            if(!stall)require(source.expected.same(sink.accepted)&&source.expected.same(sink.played)&&s.trimmedFrames==0,"whole healthy waveform exact");
        }
        double phase(){return sink.firstTone-source.tone-skipped;}
        String describe(){AudioPump.Summary s=pump.latency().summary();return "phaseMs="+phase()+" residence="+minResidence+"/"+maxResidence+" trim="+s.trimmedFrames+" firstTrimAt="+firstTrimAt+" omittedMs="+skipped+" guestMs="+source.guest+" emitted="+source.emitted+" underrunsBeforeEnd="+lateUnderruns;}
    }
    /** Own virtual sink. Head quantization is independent of actual FIFO consumption. */
    static final class Sink implements AudioPump.Output {
        final Rig r;final Samples accepted=new Samples(),played=new Samples();
        long last,consumed,maxHeld;boolean running;int underruns;double firstTone=Double.NaN;
        Sink(Rig r){this.r=r;}
        long held(){return accepted.size-consumed;}
        void advance(){
            long demand=running?(r.now-last)*RATE:0;last=r.now;
            long n=Math.min(demand,held());
            for(int i=0;i<n;i++){short v=accepted.data[(int)consumed++];played.add(v);
                if(v!=0&&Double.isNaN(firstTone))firstTone=r.now-demand/(double)RATE+i/(double)RATE;}
            if(demand>n){underruns++;running=false;}
        }
        public boolean start(AudioPump.Focus f){last=r.now;return true;}
        public int write(short[] pcm,int offset,int count){advance();int n=(int)Math.min(count,6000-held());
            for(int i=0;i<n;i++)accepted.add(pcm[offset+i]);maxHeld=Math.max(maxHeld,held());
            if(!running&&held()>=2880){running=true;last=r.now;}return n;}
        public long queuedFrames(){advance();return accepted.size-(consumed/r.head)*r.head;}
        public int startThresholdFrames(){return 2880;}
        public int writableFrames(){return 6000;}
        public int underrunCount(){return underruns;}
        public void suspend(){throw new AssertionError("unexpected sink suspension");}
        public void resume(){}
        public void stop(){}
    }
    /** Small real underruns must not become the healthy reference, even across later stalls. */
    static void shortStallsKeepInitialHealthyPhase()throws Exception {
        for(boolean varied:new boolean[]{false,true})for(int head:new int[]{1,96,240}){
            Rig healthy=new Rig(varied,head,1,false,false,false);healthy.run();
            for(int duration:new int[]{50,52,54})for(boolean again:new boolean[]{false,true}){
                Rig r=new Rig(varied,head,1,true,false,false);
                long quietStartHead=-1,previousTrim=0;
                for(int at=1;at<=END+700;at++){
                    r.now=at;r.sink.advance();
                    boolean blocked=at>=2000&&at<2000+duration
                            ||again&&at>=3600&&at<3600+duration+4;
                    boolean due=r.replayTurn>=0?at>=r.replayTurn:at>=r.nextOwner;
                    if(at<=END&&!blocked&&due)r.owner();
                    boolean wasRunning=r.sink.running;
                    r.runTasks();r.retries();
                    if(!wasRunning&&r.sink.running)quietStartHead=r.sink.consumed;
                    AudioPump.Summary s=r.pump.latency().summary();
                    if(s.trimmedFrames>previousTrim){
                        require(quietStartHead>=0&&r.sink.consumed-quietStartHead>=48000,
                                "short stall needs one full second actually played quiet");
                        previousTrim=s.trimmedFrames;
                    }
                    if(r.sink.consumed>0&&at<=END-100){
                        long excess=at*(long)RATE-r.sink.consumed-64L*RATE;
                        require(s.trimmedFrames<=excess+1,"short stall trim exceeds physical playback loss");
                    }
                }
                String label="varied="+varied+" head="+head+" stall="+duration+" again="+again;
                AudioPump.Summary s=r.pump.latency().summary();
                require(r.debt==0&&r.skipped==0,"short stall replays every logical request");
                require(s.offeredFrames==r.source.emitted&&s.offeredFrames==s.writtenFrames+s.pendingFrames
                        +s.inflightFrames+s.trimmedFrames+s.droppedFrames+s.expiredFrames+s.flushedFrames,
                        "short stall literal PCM conservation");
                require(s.droppedFrames==0&&s.expiredFrames==0&&s.flushedFrames==0&&s.pendingFrames==0,
                        "short stall permits only explicit quiet trim");
                require(r.source.expected.size-r.sink.accepted.size==s.trimmedFrames
                        &&r.sink.accepted.same(r.sink.played),"short stall removed/played accounting");
                require(Arrays.equals(r.source.expected.notes(),r.sink.played.notes()),"short stall notes and gaps exact");
                require(r.sink.maxHeld<=2880,"short stall keeps written-ahead ceiling");
                double delta=r.phase()-healthy.phase();
                require(delta>=-1.0/RATE&&delta<=head/(double)RATE+1.0/RATE,
                        "short stall healthy phase "+label+" deltaFrames="+Math.round(delta*RATE));
                require(r.sink.underruns<=healthy.sink.underruns+(again?2:1),"short stall adds no extra underrun");
            }
        }
        System.out.println("PASS short stalls retain initial healthy phase: 36 cases");
    }
    static void trimsRespectPhysicalPlayback()throws Exception {
        for(String mode:new String[]{"repeat","empty","short"}){
            Rig r=new Rig(!mode.equals("empty"),96,1,true,false,true);r.checkTrueTrim=true;
            if(mode.equals("repeat")){r.repeat=true;r.source.tone=7000;}else r.source.loss=mode;
            r.run();
        }
    }
    /** Native drains that return fewer frames are literal omissions, never repaid logical time. */
    static void missingPcm()throws Exception {
        for(boolean varied:new boolean[]{false,true})for(int head:new int[]{1,96,240})for(int gap:new int[]{1,2,8}){
            Rig healthy=new Rig(varied,head,gap,false,false,true);healthy.run();
            for(String loss:new String[]{"empty","short"}){
                Rig r=new Rig(varied,head,gap,true,false,true);r.source.loss=loss;r.run();
                require(r.source.missing>0,"source actually omits PCM");double delta=r.phase()-healthy.phase();
                require(delta>=-1.0/RATE&&delta<=head/(double)RATE+1.0/RATE,"missing PCM preserves healthy phase "+loss+" varied="+varied+" head="+head+" gap="+gap+" delta="+delta);
                require(r.minResidence>=healthy.minResidence-1&&r.maxResidence<=healthy.maxResidence+head,"missing PCM preserves healthy margin");
                require(r.lateUnderruns<=healthy.lateUnderruns+1,"missing PCM adds no late underrun");
            }
        }
        System.out.println("PASS actual zero/short drains: 36 cases");
    }
    static void repeated()throws Exception {
        for(boolean varied:new boolean[]{false,true})for(int head:new int[]{1,96,240})for(int gap:new int[]{1,2,8}){
            Rig healthy=new Rig(varied,head,gap,false,false,true);healthy.repeat=true;healthy.source.tone=7000;healthy.run();
            for(boolean omit:new boolean[]{false,true}){
                Rig r=new Rig(varied,head,gap,true,omit,true);r.repeat=true;r.source.tone=7000;r.run();
                require(r.skipped==(omit?224:0),"two real omission intervals (116ms then108ms)");double delta=r.phase()-healthy.phase();
                require(delta>=-1.0/RATE&&delta<=head/(double)RATE+1.0/RATE,"repeated phase variation="+varied+" head="+head+" gap="+gap+" omission="+omit+" delta="+delta);
                require(r.minResidence>=healthy.minResidence-1&&r.maxResidence<=healthy.maxResidence+head,"repeated healthy margin");
                require(r.lateUnderruns<=healthy.lateUnderruns+2,"only deliberate repeated stalls underrun");
            }
        }
        System.out.println("PASS repeated actual-caller replay/omission: 36 cases");
    }
    public static void main(String[] args)throws Exception {
        String mode=args.length==0?"all":args[0];int failed=0,passed=0;
        for(boolean varied:new boolean[]{false,true})for(int head:new int[]{1,96,240}){
            Rig healthy=new Rig(varied,head,1,false,false,true);healthy.run();
            System.out.println("PASS healthy variation="+varied+" head="+head+" "+healthy.describe());passed++;
            for(int gap:new int[]{1,2,8})for(boolean omit:new boolean[]{false,true}){
                if(mode.equals("controls")&&!omit||mode.equals("replay")&&omit)continue;
                String label="variation="+varied+" head="+head+" gap="+gap+" omission="+omit;
                try {
                    Rig r=new Rig(varied,head,gap,true,omit,true);r.run();
                    System.out.println("MEASURE "+label+" "+r.describe()+" healthyPhase="+healthy.phase());
                    require(omit?r.skipped==116:r.skipped==0,"paired timing distinction retained");
                    // One physical frame negative allowance, bounded quantized-head positive allowance.
                    double delta=r.phase()-healthy.phase();
                    require(delta>=-1.0/RATE&&delta<=head/(double)RATE+1.0/RATE,"healthy tone phase lost or residual lag ms="+delta);
                    require(r.minResidence>=healthy.minResidence-1&&r.maxResidence<=healthy.maxResidence+head,"healthy residence envelope lost or excess retained");
                    require(r.lateUnderruns<=healthy.lateUnderruns+1,"added late-tick underruns beyond deliberate stall");
                    System.out.println("PASS "+label);passed++;
                }catch(AssertionError e){System.out.println("FAIL "+label+": "+e.getMessage());failed++;}
            }
        }
        System.out.println("RESULT mode="+mode+" passed="+passed+" failed="+failed);
        if(failed>0)throw new AssertionError("actual pipeline failures="+failed);
    }
}

}
