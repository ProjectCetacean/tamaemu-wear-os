package io.github.projectcetacean.tamaemu;
import java.util.*;
public final class AudioBackendTest {
 static final class Guest implements EmulatorSession.Backend {
  int rendered,headless,saves,buttons;public long open(String r,String s){return 7;}public void close(long h){}public int width(long h){return 1;}public int height(long h){return 1;}
  public void advance(long h,int ms,int[] p){rendered+=ms;p[0]=123;}public void headless(long h,int ms){headless+=ms;}
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
  onsetMeasuredFromAppliedInput();
  unavailableSoundObservationKeepsGuestUsable();
  invalidTransportGrantKeepsGuestUsable();
  Map<String,Edge> edges=new LinkedHashMap<>();
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
}
