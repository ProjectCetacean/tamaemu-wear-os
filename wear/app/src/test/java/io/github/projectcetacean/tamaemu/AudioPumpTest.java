package io.github.projectcetacean.tamaemu;
import java.util.*;
public final class AudioPumpTest {
 static final class Timer { final long due;final Runnable task;boolean cancelled,fired;Timer(long due,Runnable task){this.due=due;this.task=task;} }
 static final class Fixture {
  ArrayDeque<Runnable> tasks=new ArrayDeque<>(); long now; int creates; Runnable onCreate; final List<Timer> timers=new ArrayList<>();
  Output output=new Output(); AudioPump pump=new AudioPump(tasks::add,this::after,()->now,()->{creates++;if(onCreate!=null)onCreate.run();return output;});
  Fixture(){}
  Fixture(int frames){
   pump=new AudioPump(tasks::add,this::after,()->now,()->{creates++;return output;},frames);
  }

  Runnable after(long delay,Runnable task){Timer timer=new Timer(now+delay,task);timers.add(timer);return ()->timer.cancelled=true;}
  void run(){while(!tasks.isEmpty())tasks.remove().run();}
  int live(){int n=0;for(Timer t:timers)if(!t.cancelled&&!t.fired)n++;return n;}
  /** Virtual time: fires due retries in order, draining the executor after each. */
  void until(long end){run();while(true){Timer next=null;for(Timer t:timers)if(!t.cancelled&&!t.fired&&t.due<=end&&(next==null||t.due<next.due))next=t;
   if(next==null)break;now=Math.max(now,next.due);next.fired=true;next.task.run();run();}now=Math.max(now,end);}
 }
 static class Output implements AudioPump.Output {
  AudioPump.Focus focus; boolean allowed=true, fail, failStart; Runnable onStart; int starts,stops,suspends,resumes,accept=-1,reports; long queued; List<short[]> writes=new ArrayList<>(); List<Long> reportTimes=new ArrayList<>(); LongSupplierBox clock;
  public boolean start(AudioPump.Focus f){focus=f;starts++;if(onStart!=null)onStart.run();if(failStart)throw new IllegalStateException("start failed");return allowed;}
  public int write(short[] s,int offset,int length){if(fail)throw new IllegalStateException();int n=accept<0?length:Math.min(accept,length);if(n>0)writes.add(Arrays.copyOfRange(s,offset,offset+n));return n;}
  public long queuedFrames(){return queued;}
  public void report(AudioPump.Summary s){reports++;if(clock!=null)reportTimes.add(clock.now());}
  public void suspend(){suspends++;} public void resume(){resumes++;} public void stop(){stops++;}
 }
 interface LongSupplierBox { long now(); }
 /** Virtual 48 kHz device buffer: consumption starts at the first write; counts only, never samples. */
 static final class Sink {
  final int capacity;final java.util.function.LongSupplier clock;boolean started,starving;long last,queued,offered,consumed,lost,maxQueued;int underruns;
  Sink(int capacity,java.util.function.LongSupplier clock){this.capacity=capacity;this.clock=clock;}
  void advance(){long now=clock.getAsLong();if(!started){last=now;return;}long demand=(now-last)*48;last=now;
   if(demand>queued){if(!starving)underruns++;starving=true;consumed+=queued;queued=0;}else{consumed+=demand;queued-=demand;}}
  int accept(int frames){advance();if(!started){started=true;last=clock.getAsLong();}int accepted=(int)Math.min(frames,capacity-queued);offered+=accepted;queued+=accepted;if(accepted>0)starving=false;maxQueued=Math.max(maxQueued,queued);return accepted;}
  void conserved(String why){advance();check(offered==consumed+queued+lost,why+" sink conservation");}
 }
 /** New Output contract over the sink; occupancy 0 known, 1 unavailable, 2 implausible. Batches are tagged by sample value. */
 static final class SinkOutput extends Output {
  final Sink sink;int occupancy;long maxAfterWrite;final List<int[]> segments=new ArrayList<>();
  SinkOutput(Sink sink){this.sink=sink;}
  @Override public int write(short[] s,int offset,int length){if(fail)throw new IllegalStateException();int n=sink.accept(length);if(n>0)segments.add(new int[]{s[offset],n});maxAfterWrite=Math.max(maxAfterWrite,sink.queued);return n;}
  @Override public long queuedFrames(){sink.advance();return occupancy==0?sink.queued:occupancy==1?-1:sink.offered+1;}
 }
 /** Healthy owner cadence: each batch carries 48 frames per virtual ms elapsed since the previous one.
  * The burst adds one 150 ms gap, then 100 ms catch-up quanta issued at one instant until production equals elapsed time. Entries: {frames,time,burst}. */
 static List<int[]> trace(boolean burst){List<int[]> out=new ArrayList<>();int[] gaps={33,40,50};long t=0;int i=0;boolean stalled=false;
  while(true){if(burst&&!stalled&&t>=5000){stalled=true;t+=150;for(int owed=150*48;owed>0;owed-=4800)out.add(new int[]{Math.min(4800,owed),(int)t,1});continue;}
   int gap=gaps[i++%3];if(t+gap>10000)break;t+=gap;out.add(new int[]{gap*48,(int)t,0});}
  return out;}
 static final class Play {
  final Fixture f=new Fixture();final Sink sink=new Sink(4800,()->f.now);final SinkOutput out=new SinkOutput(sink);
  final List<Long> sequences=new ArrayList<>();final Map<Long,Integer> frames=new HashMap<>();double afterBurst=Double.NaN;int correlatedAfterBurst,inputs;
  Play(){f.output=out;out.clock=()->f.now;}
 }
 /** Inputs arrive every seventh batch and once right after a burst; the guest reacts 5 ms into the next batch.
  * Each input is a full press: its release edge is applied two batches later, after the onset. */
 static Play play(List<int[]> trace,double production,int occupancy){
  Play p=new Play();p.out.occupancy=occupancy;p.f.pump.configure(true);long token=p.f.pump.token();double progress=0;long previous=0;boolean inputNext=false;
  AudioPump.Latency latency=p.f.pump.latency();Set<Integer> releases=new HashSet<>();
  for(int k=0;k<trace.size();k++){int[] b=trace.get(k);boolean input=b[2]==0&&(inputNext||k%7==3);
   if(releases.remove(k)){p.f.now=previous+1;latency.released();}
   if(input){p.f.now=previous+1;latency.applied(previous+1,previous+1,(long)Math.floor(progress));releases.add(k+2);}
   p.f.now=b[1];int n=(int)Math.round(b[0]*production);short[] pcm=new short[n];Arrays.fill(pcm,(short)(k%32000+1));
   long seq=p.f.pump.offer(token,pcm);check(seq>=1,"healthy batch accepted");p.sequences.add(seq);p.frames.put(seq,n);
   boolean late=input&&(p.inputs++%2==1);
   if(input&&!late)latency.onset(seq,240,(long)Math.floor(progress));
   p.f.until(b[1]);
   // Production may also report after its own write: both orders correlate.
   if(late)latency.onset(seq,240,(long)Math.floor(progress));
   if(input&&inputNext){AudioPump.Onset o=latency.lastOnset();if(o!=null&&o.sequence==seq&&o.correlated){p.afterBurst=o.softwareMs;p.correlatedAfterBurst++;}}
   inputNext=b[2]==1&&(k+1<trace.size()&&trace.get(k+1)[2]==0);
   progress+=n/48.0;previous=b[1];
  }
  p.sink.conserved("trace");return p;}
 static void conserved(AudioPump.Summary s,String why){check(s.offeredFrames==s.writtenFrames+s.pendingFrames+s.inflightFrames+s.trimmedFrames+s.droppedFrames+s.expiredFrames+s.flushedFrames,why+" conservation: "+s.line());}
 /** Written segments never revisit an earlier batch and never exceed any batch. */
 static void orderedOnce(SinkOutput out,Map<Integer,Integer> sizes,String why){Map<Integer,Integer> seen=new HashMap<>();int last=0;
  for(int[] seg:out.segments){check(seg[0]>=last,why+" in order");last=seg[0];seen.merge(seg[0],seg[1],Integer::sum);}
  for(Map.Entry<Integer,Integer> e:seen.entrySet())check(e.getValue()<=sizes.get(e.getKey()),why+" no batch written twice");}
 static Map<Integer,Integer> tags(Play p,List<int[]> trace,double production){Map<Integer,Integer> sizes=new HashMap<>();for(int k=0;k<trace.size();k++)sizes.merge(k%32000+1,(int)Math.round(trace.get(k)[0]*production),Integer::sum);return sizes;}

 static long[] characterise(boolean burst){Play p=play(trace(burst),1,0);
  return new long[]{p.sink.maxQueued,p.sink.queued,p.sink.lost,p.sink.underruns,p.f.pump.latency().summary().trimmedFrames};}
 static void characteriseCurrentQueue(){
  for(boolean burst:new boolean[]{false,true}){long[] r=characterise(burst);
   System.out.println("characteriseCurrentQueue "+(burst?"burst":"healthy")+": maxQueuedFrames="+r[0]+" finalQueued="+r[1]+" lostFrames="+r[2]+" underruns="+r[3]+" trimmedFrames="+r[4]);}
 }
 static void orderedBatchesNotReplaced(){
  Fixture f=new Fixture();f.pump.configure(true);long token=f.pump.token();
  for(int i=1;i<=5;i++){short[] pcm=new short[100];Arrays.fill(pcm,(short)i);check(f.pump.offer(token,pcm)==i,"sequence "+i);}
  check(f.tasks.size()==1,"one scheduled drain bounds backpressure");f.run();
  check(f.output.writes.size()==5,"all five batches written: "+f.output.writes.size());
  for(int i=0;i<5;i++)check(f.output.writes.get(i)[0]==i+1&&f.output.writes.get(i).length==100,"batch "+(i+1)+" in order");
  AudioPump.Summary s=f.pump.latency().summary();check(s.offeredFrames==500&&s.writtenFrames==500,"offered equals written: "+s.line());
 }
 static void overflowDropsOldestAndCounts(){
  Fixture f=new Fixture();f.pump.configure(true);long token=f.pump.token();
  for(int i=1;i<=7;i++){short[] pcm=new short[1600];Arrays.fill(pcm,(short)i);f.pump.offer(token,pcm);}
  check(f.pump.latency().summary().droppedFrames==1600,"oldest overflow counted: "+f.pump.latency().summary().line());f.run();
  for(short[] w:f.output.writes)check(w[0]!=1,"oldest 1600 absent");
  AudioPump.Summary s=f.pump.latency().summary();check(s.droppedFrames==1600,"dropped exactly overflow");conserved(s,"overflow");
 }
 static void partialWriteRetainsRemainderInOrder(){
  Fixture f=new Fixture();f.pump.configure(true);long token=f.pump.token();f.output.accept=100;
  short[] first=new short[500];Arrays.fill(first,(short)1);f.pump.offer(token,first);f.run();
  check(f.output.writes.size()==1&&f.output.writes.get(0).length==100,"short write accepted 100");
  check(f.tasks.isEmpty(),"short write does not spin the executor");
  f.output.accept=-1;short[] second=new short[50];Arrays.fill(second,(short)2);f.pump.offer(token,second);f.run();
  check(f.output.writes.size()==3&&f.output.writes.get(1)[0]==1&&f.output.writes.get(1).length==400&&f.output.writes.get(2)[0]==2,"remainder written before newer batch");
  AudioPump.Summary s=f.pump.latency().summary();check(s.shortWrites==1&&s.zeroWrites==0&&s.writtenFrames==550,"short write counted: "+s.line());
  f.output.accept=0;f.pump.offer(token,new short[10]);f.run();
  check(f.pump.latency().summary().zeroWrites==1&&f.tasks.isEmpty(),"zero write counted without re-queued task");
 }
 static void zeroWriteRetriesWithoutNewAudio(){
  Fixture f=new Fixture();f.pump.configure(true);long token=f.pump.token();f.output.accept=0;
  short[] pcm=new short[100];Arrays.fill(pcm,(short)3);f.pump.offer(token,pcm);f.run();
  check(f.tasks.isEmpty()&&f.live()==1,"zero write schedules exactly one delayed retry, no spin");
  f.output.accept=-1;f.until(f.now+100);
  check(f.output.writes.size()==1&&f.output.writes.get(0).length==100&&f.output.writes.get(0)[0]==3,"retry writes once in order without new audio");
  AudioPump.Summary s=f.pump.latency().summary();check(s.retries>=1&&s.writtenFrames==100&&s.zeroWrites==1,"retry counted: "+s.line());
  Fixture refusing=new Fixture();refusing.pump.configure(true);refusing.output.accept=0;refusing.pump.offer(refusing.pump.token(),new short[100]);refusing.until(300);
  s=refusing.pump.latency().summary();check(s.expiredFrames==100&&refusing.output.writes.isEmpty()&&refusing.live()==0,"refused audio expires at250ms: "+s.line());conserved(s,"expiry");
  for(int mode=0;mode<3;mode++){Fixture c=new Fixture();c.pump.configure(true);c.output.accept=0;c.pump.offer(c.pump.token(),new short[100]);c.run();check(c.live()==1,"retry pending");
   if(mode==0)c.pump.configure(false);else if(mode==1)c.pump.invalidate();else c.output.focus.changed(false);
   check(c.live()==0,"cancel mode "+mode+" cancels the scheduled retry at once");
   c.output.accept=-1;c.until(400);check(c.output.writes.isEmpty()&&c.live()==0,"cancel mode "+mode+" prevents later write");conserved(c.pump.latency().summary(),"cancel "+mode);}
 }
 static void healthyCadenceLosesNothing(){
  List<int[]> trace=trace(false);Play p=play(trace,1,0);AudioPump.Summary s=p.f.pump.latency().summary();
  check(s.trimmedFrames==0&&s.droppedFrames==0&&s.expiredFrames==0&&s.writtenFrames==s.offeredFrames,"healthy trace loses nothing: "+s.line());
  orderedOnce(p.out,tags(p,trace,1),"healthy");check(p.sink.offered==s.writtenFrames,"every frame reaches the sink");
  check(s.maxQueuedFrames<=AudioPump.TARGET_QUEUED_FRAMES&&p.out.maxAfterWrite<=AudioPump.TARGET_QUEUED_FRAMES,"written-ahead within target: "+s.line());
  check(s.p95SoftwareMs<=100,"p95 software within budget: "+s.line());
  check(s.onsets==p.inputs&&s.uncorrelatedOnsets==0,"every full press correlates across its release edge: "+s.line());
  check(s.underruns==p.sink.underruns,"underruns match the starvation model "+p.sink.underruns+": "+s.line());
  check(p.out.reports>=4&&p.out.reports<=6,"report at most every2s: "+p.out.reports);
  for(int i=1;i<p.out.reportTimes.size();i++)check(p.out.reportTimes.get(i)-p.out.reportTimes.get(i-1)>=2000,"report spacing");
  System.out.println("healthyCadenceLosesNothing: "+s.line()+" sinkUnderruns="+p.sink.underruns);
 }
 static void underProductionCountsUnderruns(){
  Play p=play(trace(false),0.9,0);AudioPump.Summary s=p.f.pump.latency().summary();
  check(s.underruns>0&&s.underruns==p.sink.underruns,"under-production underruns counted: "+s.line()+" sink="+p.sink.underruns);
  check(s.trimmedFrames==0&&s.droppedFrames==0&&s.expiredFrames==0,"nothing trimmed to hide underruns: "+s.line());
 }
 /** Independent real-time sink: no consumption until the complete startup/refill requirement is held. */
 static class ThresholdOutput extends Output {
  final Fixture f;int capacity,required,route,platformUnderruns,quantum=1,maxWrite=Integer.MAX_VALUE;boolean running,unknown,throwMetric;
  long held,consumed,last,maxHeld;final List<Short> accepted=new ArrayList<>();
  ThresholdOutput(Fixture f,int capacity,int required){this.f=f;this.capacity=capacity;this.required=required;last=f.now;}
  void advance(){long demand=(f.now-last)*48;last=f.now;if(!running)return;
   long n=Math.min(held,demand);held-=n;consumed+=n;
   if(demand>n){running=false;platformUnderruns++;}}
  @Override public int startThresholdFrames(){if(throwMetric)throw new IllegalStateException("metric failed");return required;}
  @Override public int writableFrames(){return capacity;}
  @Override public int underrunCount(){return platformUnderruns;}
  @Override public int routeId(){return route;}
  @Override public long queuedFrames(){advance();return unknown?-1:held+consumed%quantum;}
  @Override public int write(short[] s,int offset,int length){advance();int n=(int)Math.min(Math.min(length,maxWrite),capacity-held);
   for(int i=0;i<n;i++)accepted.add(s[offset+i]);held+=n;maxHeld=Math.max(maxHeld,held);if(held>=required)running=true;return n;}
  @Override public void suspend(){super.suspend();held=0;consumed=0;running=false;last=f.now;}
 }
 static ThresholdOutput threshold(Fixture f,int capacity,int required){ThresholdOutput o=new ThresholdOutput(f,capacity,required);f.output=o;f.pump.configure(true);return o;}
 static void injectedTargetsBoundPartialZeroAndStartup(){
  for(int target:new int[]{1440,1920,2880})for(int required:new int[]{960,2886})for(boolean unknown:new boolean[]{false,true}){
   Fixture f=new Fixture(target);ThresholdOutput o=threshold(f,6000,required);o.unknown=unknown;
   short[] oracle=new short[6000];for(int i=0;i<oracle.length;i++)oracle[i]=(short)(i+1);
   o.maxWrite=0;f.pump.offer(f.pump.token(),oracle);f.run();
   check(o.accepted.isEmpty()&&f.live()==1,"zero write retains all real frames without spin");
   o.maxWrite=700;f.until(10);check(o.accepted.size()==700,"partial prefix retained");o.maxWrite=Integer.MAX_VALUE;
   f.until(20);int ceiling=Math.max(target,required);
   check(o.held==ceiling&&o.running,"actual startup threshold reached with real data target="+target+" required="+required+" unknown="+unknown+" held="+o.held+" accepted="+o.accepted.size());
   check(o.maxHeld<=ceiling,"startup bounded by max(requested, actual)");
   f.until(220);o.advance();
   check(o.accepted.size()==oracle.length,"all pending real data eventually written");
   for(int i=0;i<oracle.length;i++)check(o.accepted.get(i)==oracle[i],"partial retry exact order at "+i);
   AudioPump.Summary summary=f.pump.latency().summary();
   check(summary.zeroWrites==1&&summary.shortWrites==1&&summary.trimmedFrames==0&&summary.expiredFrames==0&&summary.droppedFrames==0,"no healthy backlog loss target="+target+" required="+required+" unknown="+unknown+" "+summary.line());
   conserved(summary,"injected target");
  }
 }
 static void injectedTargetsKeepPendingCapAgeAndCancellation(){
  for(int target:new int[]{1440,1920,2880}){
   Fixture f=new Fixture(target);ThresholdOutput o=threshold(f,6000,960);o.maxWrite=0;
   for(int i=0;i<7;i++)f.pump.offer(f.pump.token(),new short[1600]);f.run();
   AudioPump.Summary s=f.pump.latency().summary();
   check(s.droppedFrames==1600&&s.pendingFrames==9600&&s.writtenFrames==0,"pending cap independent of normal target");
   f.until(260);s=f.pump.latency().summary();
   check(s.expiredFrames==9600&&s.pendingFrames==0&&f.live()==0,"same250ms age limit and bounded retry chain");conserved(s,"target expiry");
   long stale=f.pump.token();f.pump.offer(stale,new short[100]);f.run();check(f.live()==1,"pending retry before cancellation");
   f.pump.invalidate();o.maxWrite=Integer.MAX_VALUE;f.until(400);s=f.pump.latency().summary();
   check(f.live()==0&&s.flushedFrames==100&&o.accepted.isEmpty()&&f.pump.offer(stale,new short[1])==-1,"cancellation preserves stale epoch rejection");
   conserved(s,"target cancel");
  }
 }
 static void invalidNormalTargetsRejected(){
  for(int n:new int[]{-1,0,960,1439,1441,1919,1921,2879,2881,9600,Integer.MAX_VALUE}){
   try{new Fixture(n);throw new AssertionError("invalid normal target accepted "+n);}catch(IllegalArgumentException expected){}
  }
 }
 static void thresholdLargerThanNormalTargetStarts(){
  for(int[] pair:new int[][]{{2880,2880},{2886,2886},{2886,960}}){Fixture f=new Fixture();ThresholdOutput o=threshold(f,pair[0],pair[1]);
   f.pump.offer(f.pump.token(),new short[2880]);f.run();
   check(o.consumed==0,"no invented startup consumption");f.now=16;
   f.pump.offer(f.pump.token(),new short[6]);f.run();f.until(36);o.advance();
   check(o.consumed>0,"threshold "+pair[1]+" must start, accepted="+o.accepted.size());
   check(o.maxHeld<=Math.max(2880,pair[1]),"bounded startup allowance");}
 }
 static short[] noteOracle(){short[] all=new short[38400];int pos=0,tag=1;
  int[] durations={240,144,384,192,96,480};
  while(pos<all.length){for(int j=0;j<durations.length&&pos<all.length;j++){int end=Math.min(all.length,pos+durations[j]);
   Arrays.fill(all,pos,end,j%2==0?(short)tag++:0);pos=end;}}return all;}
 static void healthyTaggedNotesAndGapsSurviveBacklog(boolean stall){
  for(int target:new int[]{1440,1920,2880}){Fixture f=new Fixture(target);ThresholdOutput o=threshold(f,6000,target);short[] oracle=noteOracle();int from=0;boolean stalled=false;
   while(from<oracle.length){int elapsed=16;if(stall&&!stalled&&from>=7680){elapsed=100;stalled=true;f.now+=elapsed;}else f.until(f.now+elapsed);
    int n=Math.min(elapsed*48,oracle.length-from);f.pump.offer(f.pump.token(),Arrays.copyOfRange(oracle,from,from+n));f.run();from+=n;}
   f.until(f.now+200);
   check(o.accepted.size()==oracle.length,"entire note/gap length stall="+stall+" expected="+oracle.length+" actual="+o.accepted.size());
   for(int i=0;i<oracle.length;i++)check(o.accepted.get(i)==oracle[i],"note/gap order and segment boundary at "+i);
   AudioPump.Summary s=f.pump.latency().summary();check(s.trimmedFrames==0&&s.droppedFrames==0&&s.expiredFrames==0&&s.flushedFrames==0,"healthy zero loss: "+s.line());
   conserved(s,"note/gap continuity");check(o.maxHeld<=target,"normal written-ahead stays at requested target "+target);}
 }
 static void thresholdRecoveryAndUnknownHead(){
  for(int mode=0;mode<5;mode++){Fixture f=new Fixture();ThresholdOutput o=threshold(f,2886,2886);o.quantum=192;
   f.pump.offer(f.pump.token(),new short[2886]);f.run();f.until(100);o.advance();check(o.consumed==2886,"initial fill plays");
   if(mode==0){o.focus.changed(false);o.focus.changed(true);f.run();}
   if(mode==1){o.required=960;o.capacity=3000;o.route++;}
   if(mode==2){o.required=2886;o.capacity=3000;o.route++;}
   if(mode==3){o.unknown=true;}
   if(mode==4){o.platformUnderruns++;o.held=0;o.running=false;}
   long before=o.consumed;o.maxWrite=700;
   f.pump.offer(f.pump.token(),new short[2886]);f.run();check(f.live()==1,"partial refill uses delayed retry");
   o.maxWrite=0;f.until(f.now+10);check(f.live()==1,"zero refill write does not spin");o.maxWrite=700;
   f.until(f.now+100);o.advance();check(o.consumed>before,"refill starts mode="+mode);
   check(o.maxHeld<=2886,"only bounded priming exception");conserved(f.pump.latency().summary(),"refill "+mode);}
  Fixture f=new Fixture();ThresholdOutput o=threshold(f,2886,2886);o.unknown=true;
  f.pump.offer(f.pump.token(),new short[1000]);f.run();f.until(100);
  check(o.consumed==0,"unknown head cannot invent pre-start consumption");
  f.pump.offer(f.pump.token(),new short[1886]);f.run();check(o.held==2886,"unknown pre-start occupancy retained until full real fill");
  f.until(120);o.advance();check(o.consumed>0,"unknown-head track eventually starts");
 }
 static void routeChangeWithUnknownHeadCannotInventRoom(){
  Fixture f=new Fixture();ThresholdOutput o=threshold(f,6000,960);o.unknown=true;
  f.pump.offer(f.pump.token(),new short[1000]);f.run();
  o.required=2886;o.route++;o.running=false;f.now=100;
  f.pump.offer(f.pump.token(),new short[2886]);f.run();
  check(o.held==2886,"unknown route-change retains1000 real queued frames, held="+o.held);
  check(f.pump.latency().summary().writtenFrames==2886,"only1886 new frames may prime the route");
 }
 static void maximumPrimingAllowanceEndsAfterFill(){
  for(int target:new int[]{1440,1920,2880}){Fixture f=new Fixture(target);ThresholdOutput o=threshold(f,9600,9600);
  f.pump.offer(f.pump.token(),new short[9600]);f.run();check(o.held==9600,"maximum valid real-data fill");
  f.pump.offer(f.pump.token(),new short[100]);f.run();check(o.held==9600&&o.accepted.size()==9600,"primed output cannot add above normal ceiling");
  f.until(190);check(o.accepted.size()==9700&&o.held<=target,"normal ceiling resumes once primed queue drains");conserved(f.pump.latency().summary(),"maximum priming");}
 }
 static void underrunBetweenObservationAndWriteRetainsNewPrefix(){
  Fixture f=new Fixture();ThresholdOutput out=new ThresholdOutput(f,6000,2886){int calls;
   @Override public int write(short[] samples,int from,int length){if(++calls==2)f.now=100;return super.write(samples,from,length);}};
  f.output=out;f.pump.configure(true);out.quantum=192;
  f.pump.offer(f.pump.token(),new short[2886]);f.run();f.now=60;
  f.pump.offer(f.pump.token(),new short[768]);f.run();
  f.pump.offer(f.pump.token(),new short[2886]);f.run();
  check(out.maxHeld<=2886,"underrun inside write retains newly accepted prefix, real queue="+out.maxHeld);
  f.until(200);conserved(f.pump.latency().summary(),"underrun/write race");
 }
 static void invalidThresholdFailsAudioOnly(){
  for(int requirement:new int[]{0,-1,2887,9601,Integer.MAX_VALUE}){Fixture f=new Fixture();ThresholdOutput o=threshold(f,2886,requirement);
   f.pump.offer(f.pump.token(),new short[100]);f.run();
   check(!f.pump.accepting()&&o.stops==1&&o.accepted.isEmpty(),"unreachable/invalid requirement stops audio "+requirement);
   conserved(f.pump.latency().summary(),"invalid requirement");}
  Fixture f=new Fixture();ThresholdOutput o=threshold(f,2886,2886);o.throwMetric=true;
  f.pump.offer(f.pump.token(),new short[100]);f.run();check(!f.pump.accepting()&&o.stops==1,"throwing platform metric safely stops audio");
 }
 static void cancellationDuringPartialWriteAccountsOnce(){
  Fixture f=new Fixture();f.output=new Output(){boolean cancelled;
   @Override public int write(short[] s,int from,int length){
    conserved(f.pump.latency().summary(),"inflight snapshot");
    if(!cancelled){cancelled=true;f.pump.configure(false);f.pump.configure(true);f.pump.offer(f.pump.token(),new short[]{77});return 40;}
    return super.write(s,from,length);}};
  f.pump.configure(true);long stale=f.pump.token();f.pump.offer(stale,new short[100]);f.run();f.until(400);
  AudioPump.Summary s=f.pump.latency().summary();check(s.writtenFrames==41&&s.flushedFrames==60&&s.pendingFrames==0&&s.inflightFrames==0,"accepted prefix and stale suffix counted once: "+s.line());
  check(f.pump.offer(stale,new short[]{1})==-1&&f.live()==0,"stale epoch cannot retry");conserved(s,"partial cancel");
 }
 static void playbackHeadWrapAndResetHandled(){
  AudioPump.Occupancy o=new AudioPump.Occupancy();int head=0xFFFFFF00;o.reset(head);o.wrote(1000);
  check(o.queued(head)==1000,"fresh epoch");head+=300;check(o.queued(head)==700,"continuous across2^32 wrap: "+o.queued(head));
  o.wrote(500);head+=600;check(o.queued(head)==600,"continued after wrap");
  o.reset(0);check(o.queued(0)==0,"flush restarts epoch");o.wrote(100);check(o.queued(40)==60,"new epoch occupancy");
  check(o.queued(5)==-1,"backward head is unknown, never negative");check(o.queued(40+(1<<20))==-1,"head beyond written is unknown, never huge");
  // Through the pump: a head crossing the wrap keeps occupancy known.
  Fixture f=new Fixture();int[] h={0xFFFFF000};AudioPump.Occupancy track=new AudioPump.Occupancy();track.reset(h[0]);
  f.output=new Output(){
   @Override public int write(short[] s,int offset,int length){track.wrote(length);return length;}
   @Override public long queuedFrames(){return track.queued(h[0]);}
   @Override public void suspend(){super.suspend();h[0]=0;track.reset(0);}
  };
  f.pump.configure(true);long token=f.pump.token();
  for(int i=0;i<10;i++){f.pump.offer(token,new short[1584]);f.run();f.now+=33;h[0]+=1584;}
  AudioPump.Summary s=f.pump.latency().summary();check(s.unknownOccupancyWrites==0&&s.maxQueuedFrames<=AudioPump.TARGET_QUEUED_FRAMES&&s.maxQueuedFrames>0,"wrap keeps occupancy known: "+s.line());
  f.output.focus.changed(false);f.run();f.output.focus.changed(true);f.run();token=f.pump.token();f.pump.offer(token,new short[100]);f.run();
  check(f.pump.latency().summary().unknownOccupancyWrites==0,"flush epoch restarts from zero");
 }
 static void unknownOccupancyBoundsWritesAndWithholdsLatency(){
  for(int mode=1;mode<=2;mode++){Play p=play(trace(true),1,mode);p.f.until(p.f.now+200);AudioPump.Summary s=p.f.pump.latency().summary();
   check(p.out.maxAfterWrite<=AudioPump.TARGET_QUEUED_FRAMES,"estimate bounds writes mode "+mode+": "+p.out.maxAfterWrite);
   check(s.unknownOccupancyWrites>0&&s.unknownOccupancyWrites==p.out.segments.size(),"unknown writes counted mode "+mode+": "+s.line());
   check(s.onsets>0&&s.uncorrelatedOnsets==s.onsets&&Double.isNaN(s.p95SoftwareMs)&&Double.isNaN(s.p50SoftwareMs)&&s.line().contains("insufficient"),"no latency invented mode "+mode+": "+s.line());
   conserved(s,"unknown "+mode);}
 }
 static void ambiguousOnsetsNotCorrelated(){
  Fixture f=new Fixture();f.pump.configure(true);long token=f.pump.token();AudioPump.Latency l=f.pump.latency();
  l.applied(10,10,0,false);long seq=f.pump.offer(token,new short[1584]);l.onset(seq,10,0);f.run();
  AudioPump.Summary s=l.summary();check(s.onsets==1&&s.uncorrelatedOnsets==1,"input while sounding is uncorrelated: "+s.line());
  l.applied(50,50,33);l.applied(60,60,33);seq=f.pump.offer(token,new short[1584]);l.onset(seq,10,33);f.run();
  s=l.summary();check(s.onsets==2&&s.uncorrelatedOnsets==2,"two edges before one onset are uncorrelated: "+s.line());
  for(int i=0;i<4;i++){f.now+=100;l.applied(f.now,f.now,100*i+66);seq=f.pump.offer(token,new short[480]);l.onset(seq,0,100*i+66);f.run();}
  s=l.summary();check(s.onsets==6&&s.uncorrelatedOnsets==2,"four correlated: "+s.line());
  check(Double.isNaN(s.p50SoftwareMs)&&Double.isNaN(s.p95SoftwareMs)&&Double.isNaN(s.maxSoftwareMs)&&Double.isNaN(s.p95FirmwareReactionMs)&&s.line().contains("insufficient"),"fewer than5 correlated is insufficient: "+s.line());
  f.now+=100;l.applied(f.now,f.now,500);seq=f.pump.offer(token,new short[480]);l.onset(seq,0,500);f.run();
  s=l.summary();check(!Double.isNaN(s.p95SoftwareMs)&&!s.line().contains("insufficient"),"five correlated report percentiles: "+s.line());
 }
 /** Review probe: an onset still waiting for its write keeps its own input's admission time. */
 static void pendingOnsetKeepsItsOwnAdmission(){
  AudioPump.Latency l=new AudioPump.Latency();
  l.applied(100,100,0,true);l.onset(1,0,10);l.applied(120,120,10,true);l.wrote(1,0,48,130,0,false);
  AudioPump.Onset o=l.lastOnset();check(o!=null&&o.sequence==1&&o.correlated&&o.softwareMs==130-100-10,"waiting onset measured from its own admission: "+(o==null?"none":o.softwareMs));
  l.onset(2,0,20);l.wrote(2,0,48,150,0,false);o=l.lastOnset();check(o.sequence==2&&o.correlated&&o.softwareMs==150-120-10,"later input measured from its own admission: "+o.softwareMs);
  // Repeated under a delayed write: percentiles never borrow the next admission or go negative.
  AudioPump.Latency r=new AudioPump.Latency();
  for(int i=0;i<5;i++){long b=1000*i;
   r.applied(b+100,b+100,b,true);r.onset(2*i+1,0,b+10);r.applied(b+145,b+145,b+10,true);r.wrote(2*i+1,0,48,b+130,0,false);
   r.onset(2*i+2,0,b+20);r.wrote(2*i+2,0,48,b+175,0,false);}
  AudioPump.Summary s=r.summary();check(s.onsets==10&&s.uncorrelatedOnsets==0&&s.p50SoftwareMs==20&&s.p95SoftwareMs==20&&s.maxSoftwareMs==20,"percentiles from own admissions: "+s.line());
 }
 /** Counts frames the track holds; suspend flushes them. */
 static final class TrackOutput extends Output {
  long held;final List<String> events=new ArrayList<>();
  @Override public int write(short[] s,int offset,int length){int n=super.write(s,offset,length);held+=n;return n;}
  @Override public long queuedFrames(){return held;}
  @Override public void suspend(){super.suspend();held=0;events.add("suspend");}
  @Override public void resume(){super.resume();events.add("resume");}
 }
 static boolean wroteValue(Output out,int value){for(short[] w:out.writes)if(w[0]==value)return true;return false;}
 /** Review probe: loss then gain before one drain still flushes audio written before the loss. */
 static void coalescedFocusLossFlushesWrittenAudio(){
  Fixture f=new Fixture();TrackOutput t=new TrackOutput();f.output=t;f.pump.configure(true);
  short[] old=new short[600];Arrays.fill(old,(short)21);f.pump.offer(f.pump.token(),old);f.run();check(t.held==600,"pre-loss frames written");
  t.focus.changed(false);t.focus.changed(true);f.run();
  check(t.suspends==1&&t.resumes==1&&t.events.equals(Arrays.asList("suspend","resume")),"coalesced loss suspends before gain resumes: "+t.events);
  check(t.held==0&&f.pump.accepting(),"pre-loss track frames flushed, gain accepts: held="+t.held);
  short[] fresh=new short[100];Arrays.fill(fresh,(short)22);f.pump.offer(f.pump.token(),fresh);f.run();
  check(t.held==100&&t.writes.size()==2&&t.writes.get(1)[0]==22,"only fresh audio after regained focus");
  // A pending zero-write retry is cancelled at once and its pre-loss remainder never plays.
  t.accept=0;short[] stale=new short[100];Arrays.fill(stale,(short)23);f.pump.offer(f.pump.token(),stale);f.run();check(f.live()==1,"retry pending");
  t.focus.changed(false);t.focus.changed(true);check(f.live()==0,"coalesced focus change cancels the retry");
  t.accept=-1;f.until(f.now+400);
  check(t.suspends==2&&t.resumes==2&&t.held==0&&!wroteValue(t,23)&&f.pump.accepting(),"retried pre-loss audio never escapes: "+t.events);
  // Loss, gain, loss: one flush and no resume.
  t.focus.changed(false);t.focus.changed(true);t.focus.changed(false);f.run();
  check(t.suspends==3&&t.resumes==2&&!f.pump.accepting(),"final loss stays suspended: "+t.events);
  conserved(f.pump.latency().summary(),"coalesced focus");
 }
 static void failureEventsAreSeparateFromHealthyReports(){
  Fixture f=new Fixture();List<AudioPump.Summary> failures=new ArrayList<>(),healthy=new ArrayList<>();
  f.output=new Output(){
   public void reportFailure(AudioPump.Summary s){failures.add(s);}
   @Override public void report(AudioPump.Summary s){healthy.add(s);}
  };
  f.output.allowed=false;f.pump.configure(true);f.pump.offer(f.pump.token(),new short[960]);f.run();
  check(!f.pump.accepting()&&f.output.stops==1,"first start fails and stops");
  f.output.allowed=true;f.pump.configure(false);f.pump.configure(true);
  for(int at=0;at<=4200;at+=20){f.now=at;f.pump.offer(f.pump.token(),new short[960]);f.run();}
  AudioPump.Summary s=f.pump.latency().summary();conserved(s,"failure then recovery");
  check(f.pump.accepting()&&s.outputFailures==1&&s.writtenFrames==202560&&s.flushedFrames==960,"recovery preserves lifetime counters: "+s.line());
  check(failures.size()==1&&healthy.size()==2,"one current failure callback and two healthy reports: failure="+failures.size()+" healthy="+healthy.size());
  check(failures.get(0).outputFailures==1&&healthy.get(1).outputFailures==1,"healthy telemetry retains lifetime failure");
  f.pump.configure(false);f.until(5000);check(f.output.stops==2&&f.live()==0,"disable stops recovered output without delayed work");
  conserved(f.pump.latency().summary(),"disabled after recovery");
 }
 static void failureReportDefaultsToLegacyReport(){
  Fixture f=new Fixture();f.output.allowed=false;f.pump.configure(true);f.pump.offer(f.pump.token(),new short[100]);f.run();
  check(f.output.reports==1,"legacy Output receives the failure summary through report");conserved(f.pump.latency().summary(),"legacy report");
 }
 static void lifecycle(){
  Fixture f=new Fixture();check(f.creates==0&&!f.pump.accepting(),"cold remains lazy");f.pump.configure(true);check(f.pump.accepting()&&f.creates==0,"foreground eligibility does not allocate");
  long token=f.pump.token();short[] pcm={1,2};f.pump.offer(token,pcm);pcm[0]=99;f.run();check(f.creates==1&&f.output.writes.get(0)[0]==1,"detached PCM delivery");
  f.pump.offer(token,new short[]{3});f.pump.configure(false);f.run();check(f.output.writes.size()==1&&f.output.stops==1,"pause discards queued audio and stops");
  check(f.pump.latency().summary().flushedFrames==1,"pause discard counted");
  f.pump.configure(true);check(f.pump.offer(token,new short[]{4})==-1,"stale generation rejected");f.run();check(f.output.writes.size()==1,"stale generation discarded");
  token=f.pump.token();f.pump.offer(token,new short[]{5});f.run();f.output.focus.changed(false);check(f.pump.offer(token,new short[]{6})==-1,"focus loss rejects");f.run();check(!f.pump.accepting()&&f.output.suspends==1&&f.output.writes.size()==2,"focus loss flushes and invalidates");
  f.output.focus.changed(true);f.run();check(f.pump.accepting()&&f.output.resumes==1,"focus gain accepts new samples only");
  f.pump.offer(token,new short[]{77});f.run();check(f.output.writes.size()==2,"pre-focus samples cannot escape after focus gain");
  token=f.pump.token();f.pump.offer(token,new short[]{7});f.now+=251;f.run();check(f.output.writes.size()==2&&f.pump.latency().summary().expiredFrames==1,"stalled PCM dropped after250ms and counted");
  check(f.pump.offer(token,new short[9601])==-1,"oversize detached buffer rejected");f.run();check(f.output.writes.size()==2,"oversize never written");
  f.output.fail=true;f.pump.offer(token,new short[]{8});f.run();check(!f.pump.accepting(),"output error disables sound only");f.pump.configure(false);f.pump.configure(true);check(f.pump.accepting(),"fresh activation can retry output");
  Fixture denied=new Fixture();denied.output.allowed=false;denied.pump.configure(true);denied.pump.offer(denied.pump.token(),new short[]{1});denied.run();check(!denied.pump.accepting()&&denied.output.writes.isEmpty(),"denied focus stays silent");
  check(denied.pump.latency().summary().outputFailures==1,"denied output is reported as audio failure");
  Fixture startingLoss=new Fixture();startingLoss.output.onStart=()->startingLoss.output.focus.changed(false);
  startingLoss.pump.configure(true);long beforeFocus=startingLoss.pump.token();startingLoss.pump.offer(beforeFocus,new short[]{11});startingLoss.run();
  check(startingLoss.output.starts==1&&startingLoss.output.stops==0&&startingLoss.output.suspends==1&&!startingLoss.pump.accepting()&&startingLoss.output.writes.isEmpty(),"focus loss during start retains suspended focus request");
  startingLoss.output.focus.changed(true);startingLoss.run();check(startingLoss.pump.accepting()&&startingLoss.output.resumes==1,"retained start request can regain focus");
  startingLoss.pump.offer(beforeFocus,new short[]{12});startingLoss.run();check(startingLoss.output.writes.isEmpty(),"start focus change discards prior PCM");
  startingLoss.pump.offer(startingLoss.pump.token(),new short[]{13});startingLoss.run();check(startingLoss.output.starts==1&&startingLoss.output.writes.size()==1,"regained request plays fresh PCM without restart");
  Fixture startLossGain=new Fixture();startLossGain.output.onStart=()->{startLossGain.output.focus.changed(false);startLossGain.output.focus.changed(true);};
  startLossGain.pump.configure(true);startLossGain.pump.offer(startLossGain.pump.token(),new short[]{14});startLossGain.run();
  check(startLossGain.pump.accepting()&&startLossGain.output.stops==0&&startLossGain.output.resumes==1&&startLossGain.output.writes.isEmpty(),"synchronous loss and gain retain output but discard old PCM");
  Fixture startDenied=new Fixture();startDenied.output.allowed=false;startDenied.output.onStart=()->startDenied.output.focus.changed(true);
  startDenied.pump.configure(true);startDenied.pump.offer(startDenied.pump.token(),new short[]{15});startDenied.run();check(!startDenied.pump.accepting()&&startDenied.output.stops==1&&startDenied.output.writes.isEmpty(),"focus callback cannot hide denied start cleanup");
  Fixture startException=new Fixture();startException.output.failStart=true;startException.output.onStart=()->startException.output.focus.changed(true);
  startException.pump.configure(true);startException.pump.offer(startException.pump.token(),new short[]{16});startException.run();check(!startException.pump.accepting()&&startException.output.stops==1&&startException.output.writes.isEmpty(),"focus callback cannot hide failed start cleanup");
  Fixture lifecycleDuringStart=new Fixture();lifecycleDuringStart.output.onStart=()->{lifecycleDuringStart.pump.configure(false);lifecycleDuringStart.pump.configure(true);};
  lifecycleDuringStart.pump.configure(true);lifecycleDuringStart.pump.offer(lifecycleDuringStart.pump.token(),new short[]{17});lifecycleDuringStart.run();
  check(lifecycleDuringStart.pump.accepting()&&lifecycleDuringStart.output.stops==1&&lifecycleDuringStart.output.writes.isEmpty(),"obsolete lifecycle start is stopped without disabling new activation");
  lifecycleDuringStart.output.focus.changed(false);lifecycleDuringStart.run();check(lifecycleDuringStart.pump.accepting(),"obsolete request callbacks cannot change new activation");
  Fixture lifecycleDuringCreate=new Fixture();lifecycleDuringCreate.onCreate=()->{lifecycleDuringCreate.pump.configure(false);lifecycleDuringCreate.pump.configure(true);};
  lifecycleDuringCreate.pump.configure(true);lifecycleDuringCreate.pump.offer(lifecycleDuringCreate.pump.token(),new short[]{18});lifecycleDuringCreate.run();
  check(lifecycleDuringCreate.pump.accepting()&&lifecycleDuringCreate.output.starts==0&&lifecycleDuringCreate.output.stops==1,"obsolete factory result never attaches a focus callback");
  Fixture invalidatedStart=new Fixture();invalidatedStart.output.onStart=invalidatedStart.pump::invalidate;
  invalidatedStart.pump.configure(true);invalidatedStart.pump.offer(invalidatedStart.pump.token(),new short[]{19});invalidatedStart.run();
  check(invalidatedStart.pump.accepting()&&invalidatedStart.output.stops==1&&invalidatedStart.output.writes.isEmpty(),"replay invalidation during start retires obsolete output");
  invalidatedStart.output.focus.changed(false);invalidatedStart.run();check(invalidatedStart.pump.accepting(),"invalidated request callbacks stay obsolete");
 }
 /** Sample-carrying threshold sink, exercised at each virtual millisecond. */
 static final class QuietRun {
  final Fixture f=new Fixture();final List<Short> oracle=new ArrayList<>(),played=new ArrayList<>();
  double firstTone=Double.NaN;long completed;boolean framed=true;long tailPending,tailHeld,headAdjustment;int partition=768,toneStart=2400,end=4000;Runnable afterWrite;
  final ThresholdOutput out=new ThresholdOutput(f,6000,2880){
   @Override void advance(){long before=consumed;super.advance();
    for(long i=before;i<consumed;i++){short value=accepted.get((int)i);played.add(value);
     if(value!=0&&Double.isNaN(firstTone))firstTone=f.now-(consumed-i)/48.0;}}
   @Override public long queuedFrames(){long q=super.queuedFrames();return q<0?q:q+headAdjustment;}
   @Override public int write(short[] samples,int from,int length){int n=super.write(samples,from,length);
    if(afterWrite!=null){Runnable action=afterWrite;afterWrite=null;action.run();}return n;}
  };
  QuietRun(){f.output=out;f.pump.configure(true);}
  void offer(int from,int to,boolean shortQuiet){short[] pcm=new short[(to-from)*48];
   for(int i=0;i<pcm.length;i++){int ms=from+i/48;
    pcm[i]=(short)((ms>=toneStart&&ms<toneStart+800||shortQuiet)&&ms%80<32?1+ms/80:0);oracle.add(pcm[i]);}
   // These fixture requests are integral milliseconds; progress comes from the request script.
   for(int off=0;off<pcm.length;off+=partition){
    int end=Math.min(pcm.length,off+partition);short[] part=Arrays.copyOfRange(pcm,off,end);
    long seq;
    if(framed){completed+=(end-off)/48;seq=f.pump.offerCompletedAdvance(f.pump.token(),completed,part);}
    else seq=f.pump.offer(f.pump.token(),part);
    check(seq>0,"quiet fixture offer");}}
  void run(boolean pause,boolean burst,boolean shortQuiet,boolean ambiguous){
   out.quantum=ambiguous?960:1;
   for(int at=1;at<=end;at++){
    f.now=at;out.advance();
    if(at%16==0&&!(burst&&at>1008&&at<1168)){
     if(burst&&at==1168){offer(1008,1108,shortQuiet);offer(1108,1168,shortQuiet);}
     else offer(at-16,at,shortQuiet);}
    if(!(pause&&at>1008&&at<1168))f.until(at);
    if((pause||burst)&&at<2100)check(f.pump.latency().summary().trimmedFrames==0,"no recovery before a full post-stall played quiet interval at "+at);
    if(at==end-1){tailPending=f.pump.latency().summary().pendingFrames;tailHeld=out.held;}
   }
   f.until(end+500);out.advance();conserved(f.pump.latency().summary(),"quiet stream");
   check(out.maxHeld<=2880,"quiet repair retains normal ceiling");
   check(out.accepted.equals(played),"sink drains every accepted frame exactly");
   check(oracle.size()-out.accepted.size()==f.pump.latency().summary().trimmedFrames,"all omitted frames honestly trimmed");
  }
 }
 static List<Short> noteSpan(List<Short> pcm){int from=0,to=pcm.size();while(from<to&&pcm.get(from)==0)from++;
  while(to>from&&pcm.get(to-1)==0)to--;return pcm.subList(from,to);}
 /** Catches the newest-call guard trimming 144 healthy frames when one delivery is split into 48-frame calls. */
 static void healthyQuietIsInvariantUnderSameTimePartitions(){
  QuietRun control=new QuietRun();control.framed=false;control.run(false,false,false,false);
  for(int part:new int[]{768,384,192,48}){QuietRun r=new QuietRun();r.framed=false;r.partition=part;r.run(false,false,false,false);
   check(r.oracle.equals(r.out.accepted)&&r.oracle.equals(r.played),"whole healthy quiet waveform exact partition="+part);
   check(r.firstTone==control.firstTone&&r.tailPending+r.tailHeld==control.tailPending+control.tailHeld,"healthy phase exact partition="+part);
   check(r.f.pump.latency().summary().trimmedFrames==0,"healthy quiet never compressed partition="+part);}
 }
 /** Catches persistent combined debt, including the 12ms residual hidden by checking pending alone. */
 static void longPlayedQuietRetiresOnlyExcessDebt(){quietRecoveryAcrossPartitions(2400,4000);}
 static void originalFourSecondQuietRecovers(){quietRecoveryAcrossPartitions(4000,10000);}
 static void quietRecoveryAcrossPartitions(int toneStart,int end){
  QuietRun control=new QuietRun();control.toneStart=toneStart;control.end=end;control.run(false,false,false,false);
  for(int part:new int[]{768,384,192,48})for(boolean burst:new boolean[]{false,true}){
   QuietRun r=new QuietRun();r.partition=part;r.toneStart=toneStart;r.end=end;r.run(!burst,burst,false,false);
   check(r.firstTone==control.firstTone,"NO QUIET RECOVERY burst="+burst+" partition="+part+" onset="+r.firstTone+" control="+control.firstTone);
   check(r.tailPending+r.tailHeld==control.tailPending+control.tailHeld,"combined phase debt retired partition="+part);
   check(noteSpan(r.oracle).equals(noteSpan(r.out.accepted))&&noteSpan(r.oracle).equals(noteSpan(r.played)),"all notes and short internal gaps exact");
   AudioPump.Summary s=r.f.pump.latency().summary();check(s.trimmedFrames>0&&s.droppedFrames==0&&s.expiredFrames==0&&s.flushedFrames==0,"only quiet trim accounts for recovery: "+s.line());}
 }
 static void shortQuietAndCoarsePlaybackCannotAuthorizeRecovery(){
  for(boolean shortQuiet:new boolean[]{false,true}){QuietRun r=new QuietRun();r.run(true,false,shortQuiet,!shortQuiet);
   check(r.oracle.equals(r.out.accepted)&&r.oracle.equals(r.played),"short quiet or out-of-bound head must preserve entire stream");
   check(r.f.pump.latency().summary().trimmedFrames==0&&r.tailPending>0,"unsafe recovery withheld");}
 }
 static QuietRun warmQuiet(){QuietRun r=new QuietRun();
  for(int at=1;at<=1600;at++){r.f.now=at;r.out.advance();if(at%16==0)r.offer(at-16,at,false);r.f.until(at);}
  check(r.f.pump.latency().summary().trimmedFrames==0,"ordinary newest batch survives quiet retries");return r;
 }
 /** Catches trimming from fresh generated zeros, partial acceptance or a changed playback epoch. */
 static void quietRecoveryVetoesUnsafeBoundaries(){
  for(String mode:new String[]{"pending-tone","written-tone","partial","zero","unknown","backward","route","refill","focus",
      "write-tone","write-unknown","write-backward","write-route","write-underrun"}){
   QuietRun r=warmQuiet();Fixture f=r.f;ThresholdOutput o=r.out;short[] old=new short[4800];
   if(mode.equals("pending-tone"))old[4799]=7;
   if(mode.equals("written-tone"))old[0]=7;
   if(mode.equals("partial"))o.maxWrite=100;
   if(mode.equals("zero"))o.maxWrite=0;
   if(mode.equals("unknown"))o.unknown=true;
   if(mode.equals("backward"))r.headAdjustment=960;
   if(mode.equals("route"))o.route++;
   if(mode.equals("refill")){o.held=0;o.running=false;o.platformUnderruns++;}
   if(mode.equals("focus")){o.focus.changed(false);o.focus.changed(true);f.run();}
   if(mode.startsWith("write-"))r.afterWrite=()->{
    if(mode.equals("write-tone"))f.pump.offer(f.pump.token(),new short[]{9});
    if(mode.equals("write-unknown"))o.unknown=true;
    if(mode.equals("write-backward"))r.headAdjustment=960;
    if(mode.equals("write-route"))o.route++;
    if(mode.equals("write-underrun")){o.platformUnderruns++;o.held=0;o.running=false;}
   };
   f.now=1616;f.pump.offer(f.pump.token(),old);f.pump.offer(f.pump.token(),new short[768]);f.run();
   check(f.pump.latency().summary().trimmedFrames==0,"quiet recovery veto "+mode);
   // A partial or zero write must restart eligibility, even when full writes resume immediately.
   if(mode.equals("partial")||mode.equals("zero")){o.maxWrite=Integer.MAX_VALUE;f.until(1632);
    check(f.pump.latency().summary().trimmedFrames==0,"partial/zero acceptance resets quiet evidence "+mode);}
   conserved(f.pump.latency().summary(),"quiet veto "+mode);
  }
 }
 static QuietRun stalledQuietBeforeRecovery(){QuietRun r=new QuietRun();
  for(int at=1;at<=2233;at++){r.f.now=at;r.out.advance();if(at%16==0)r.offer(at-16,at,false);
   if(!(at>1008&&at<1168))r.f.until(at);}
  check(r.f.pump.latency().summary().pendingFrames>0&&r.f.pump.latency().summary().trimmedFrames==0,"real stall debt awaits eligible retry");return r;
 }
 /** Catches unsafe recovery vetoes even when the stream has real positive phase debt. */
 static void actualDebtStillNeedsStableOutputAndCancellation(){
  for(String mode:new String[]{"partial","zero","pending-tone","unknown","backward","route","refill",
      "write-unknown","write-backward","write-route","write-underrun","disable","invalidate","focus"}){
   QuietRun r=stalledQuietBeforeRecovery();Fixture f=r.f;ThresholdOutput o=r.out;
   if(mode.equals("partial"))o.maxWrite=100;
   if(mode.equals("zero"))o.maxWrite=0;
   if(mode.equals("pending-tone"))f.pump.offer(f.pump.token(),new short[]{7});
   if(mode.equals("unknown"))o.unknown=true;
   if(mode.equals("backward"))r.headAdjustment=960;
   if(mode.equals("route"))o.route++;
   if(mode.equals("refill")){o.held=0;o.running=false;o.platformUnderruns++;}
   r.afterWrite=()->{
    if(mode.equals("write-unknown"))o.unknown=true;
    if(mode.equals("write-backward"))r.headAdjustment=960;
    if(mode.equals("write-route"))o.route++;
    if(mode.equals("write-underrun")){o.platformUnderruns++;o.held=0;o.running=false;}
    if(mode.equals("disable"))f.pump.configure(false);
    if(mode.equals("invalidate"))f.pump.invalidate();
    if(mode.equals("focus"))o.focus.changed(false);
   };
   f.until(2234);check(f.pump.latency().summary().trimmedFrames==0,"actual debt veto "+mode);
   if(mode.equals("partial")||mode.equals("zero")){o.maxWrite=Integer.MAX_VALUE;f.until(2250);
    check(f.pump.latency().summary().trimmedFrames==0,"actual debt short write resets proof "+mode);}
   if(mode.equals("disable")||mode.equals("invalidate")||mode.equals("focus")){int n=o.accepted.size();f.until(2500);
    check(o.accepted.size()==n&&f.live()==0,"actual debt cancellation prevents later writes "+mode);}
   conserved(f.pump.latency().summary(),"actual debt veto "+mode);
  }
 }
 static void provenQuietRecoveryStillHonorsCancellation(){
  for(int mode=0;mode<3;mode++){QuietRun r=warmQuiet();Fixture f=r.f;int cancel=mode;
   r.afterWrite=()->{if(cancel==0)f.pump.configure(false);else if(cancel==1)f.pump.invalidate();else r.out.focus.changed(false);};
   f.now=1616;f.pump.offer(f.pump.token(),new short[4800]);f.pump.offer(f.pump.token(),new short[768]);f.run();
   int accepted=r.out.accepted.size();f.until(1900);
   check(r.out.accepted.size()==accepted&&f.live()==0&&f.pump.latency().summary().trimmedFrames==0,"cancellation vetoes recovery and stale retries "+mode);
   conserved(f.pump.latency().summary(),"quiet cancel "+mode);}
 }
 /** Independent producer: actual offered frames may omit wall time, with native-like 64-frame variation.
  * Catches recovery using skipped time as debt, losing healthy margin or requiring exact head reads. */
 static final class ProductionRun {
  final Fixture f=new Fixture();final List<Short> oracle=new ArrayList<>(),played=new ArrayList<>();
  int quantum=1,partition=1584,late,ownerGap,executorGap;boolean subMs,jitter;
  long guest,lastOwner,next=33,tail;double firstTone=Double.NaN,fraction;
  final ThresholdOutput out=new ThresholdOutput(f,6000,2880){
   @Override void advance(){long before=consumed;super.advance();
    for(long i=before;i<consumed;i++){short v=accepted.get((int)i);played.add(v);
     if(v!=0&&Double.isNaN(firstTone))firstTone=f.now-(consumed-i)/48.0;}}
   @Override public long queuedFrames(){long q=super.queuedFrames();
    if(subMs&&running){fraction=(fraction+0.37)%1;return Math.max(0,q-(long)(fraction*48));}return q;}
  };
  long frames(long g){return g*48+(jitter&&(g/33)%3==1?64:0);}
  void run(){f.output=out;out.quantum=quantum;f.pump.configure(true);
   for(int at=1;at<=6600;at++){f.now=at;out.advance();
    boolean blocked=ownerGap>0&&at>=991&&at<990+ownerGap||late>0&&at>=3004&&at<3036+late;
    if(at<=6000&&at>=next&&!blocked){long elapsed=Math.min(100,at-lastOwner);lastOwner=at;
     long from=frames(guest),to=frames(guest+elapsed);guest+=elapsed;short[] pcm=new short[(int)(to-from)];
     for(int i=0;i<pcm.length;i++){long ms=(from+i)/48;pcm[i]=(short)(ms>=4000&&ms<4800&&ms%80<32?1+ms/80:0);oracle.add(pcm[i]);}
     // A completed logical request publishes its literal drain, including cumulative native variation.
     f.pump.offerCompletedAdvance(f.pump.token(),guest,pcm);
     next=at+33;}
    if(!(executorGap>0&&at>=1008&&at<1008+executorGap))f.until(at);
    if(at==3900)tail=out.held+f.pump.latency().summary().pendingFrames;
   }
   conserved(f.pump.latency().summary(),"production gap");check(out.accepted.equals(played),"production sink drained");
  }
 }
 static void skippedProductionPreservesHealthyMargin(){
  for(int late:new int[]{0,29,31,33}){ProductionRun h=new ProductionRun();h.late=late;h.run();
   ProductionRun r=new ProductionRun();r.ownerGap=198;r.late=late;r.run();
   check(r.out.platformUnderruns<=h.out.platformUnderruns+1,"skipped frames cause new post-recovery underrun late="+late);
   check(r.firstTone-h.firstTone>=98,"skipped source cannot cut healthy margin late="+late+" delta="+(r.firstTone-h.firstTone));
   check(noteSpan(r.oracle).equals(noteSpan(r.played)),"skipped producer notes/gaps exact");}
 }
 static void boundedHeadsRecoverWithoutLosingHealthyPcm(){
  for(int mode=0;mode<3;mode++)for(boolean jitter:new boolean[]{false,true}){
   ProductionRun h=new ProductionRun();h.quantum=mode==0?96:mode==1?240:1;h.subMs=mode==2;h.jitter=jitter;h.run();
   check(h.oracle.equals(h.played)&&h.f.pump.latency().summary().trimmedFrames==0,"whole healthy bounded-head PCM exact");
   ProductionRun r=new ProductionRun();r.quantum=h.quantum;r.subMs=h.subMs;r.jitter=jitter;r.executorGap=160;r.run();
   double residual=r.firstTone-h.firstTone;
   check(residual>=-1.0/48&&residual<=(mode==2?1:r.quantum/48.0)+1.0/48,"bounded-head recovery phase mode="+mode+" jitter="+jitter+" residual="+residual);
   check(r.out.platformUnderruns<=h.out.platformUnderruns+1,"bounded head new underrun");
   check(noteSpan(r.oracle).equals(noteSpan(r.played)),"bounded head notes/gaps exact");
  }
 }
 /** No producer proof means exact conservative transport, even after a real playback stall. */
 static void unknownProducerNeverCompressesQuiet(){
  for(int partition:new int[]{768,384,192,48}){QuietRun r=new QuietRun();r.framed=false;r.partition=partition;r.run(true,false,false,false);
   check(r.oracle.equals(r.played)&&r.f.pump.latency().summary().trimmedFrames==0,"raw partition keeps complete waveform "+partition);}
 }
 /** Invalid metadata must withhold recovery while preserving every valid array's notes. */
 static void incompleteProducerCannotAuthorizeRecovery(){
  for(String mode:new String[]{"null","empty","short","saturated","oversized","nonmonotonic","overflow","raw"}){
   QuietRun r=warmQuiet();Fixture f=r.f;long endpoint=r.completed+100;
   short[] pcm=new short[mode.equals("empty")?0:mode.equals("short")?1:mode.equals("saturated")?9600:mode.equals("oversized")?9601:4800];
   f.now=1700;
   if(mode.equals("raw"))f.pump.offer(f.pump.token(),pcm);
   else f.pump.offerCompletedAdvance(f.pump.token(),mode.equals("nonmonotonic")?r.completed:mode.equals("overflow")?Long.MAX_VALUE:endpoint,mode.equals("null")?null:pcm);
   f.run();f.until(2200);AudioPump.Summary s=f.pump.latency().summary();
   check(s.trimmedFrames==0,"invalid or missing PCM cannot create quiet debt "+mode);conserved(s,"invalid producer "+mode);
  }
 }
 /** Matched abandonment retires transport; obsolete producer cleanup preserves live focus. */
 static void producerRetirementMatchesEpoch(){
  Fixture f=new Fixture();f.pump.configure(true);long old=f.pump.token();
  f.pump.offerCompletedAdvance(old,16,new short[768]);f.run();AudioPump.Focus focus=f.output.focus;
  focus.changed(false);f.run();long lost=f.pump.token();
  f.pump.invalidate(old);f.run();
  check(f.output.stops==0&&f.pump.token()==lost,"stale cleanup does not replace retained focus");
  focus.changed(true);f.run();check(f.pump.accepting(),"retained gain accepted");
  long current=f.pump.token();f.pump.offerCompletedAdvance(current,16,new short[768]);
  f.pump.invalidate(current);f.run();
  check(f.output.stops==1&&f.pump.latency().summary().pendingFrames==0,"matching cleanup retires pending transport once");
  check(f.pump.offerCompletedAdvance(current,32,new short[768])<0,"retired producer cannot publish");
  long token=f.pump.token();focus.changed(false);f.run();check(f.pump.token()==token,"retired callback cannot change replacement epoch");
  conserved(f.pump.latency().summary(),"producer retirement");
 }
 /** Valid producer debt alone cannot carry a playback bound across grant changes. */
 static void framedRecoveryRechecksGrant(){
  QuietRun control=stalledQuietBeforeRecovery();control.f.until(2234);
  check(control.f.pump.latency().summary().trimmedFrames>0,"unperturbed framed control really recovers on this retry");
  for(String mode:new String[]{"threshold","capacity","write-threshold","write-capacity"}){
   QuietRun r=stalledQuietBeforeRecovery();
   Runnable change=()->{if(mode.endsWith("threshold"))r.out.required+=6;else r.out.capacity+=48;};
   if(mode.startsWith("write-"))r.afterWrite=change;else change.run();
   r.f.until(2234);
   check(r.f.pump.latency().summary().trimmedFrames==0,"grant change vetoes framed recovery "+mode);
   conserved(r.f.pump.latency().summary(),"changed grant "+mode);
  }
 }
 public static void main(String[] args){
  characteriseCurrentQueue();
  List<String> failed=new ArrayList<>();
  Map<String,Runnable> tests=new LinkedHashMap<>();
  tests.put("producerRetirementMatchesEpoch",AudioPumpTest::producerRetirementMatchesEpoch);
  tests.put("framedRecoveryRechecksGrant",AudioPumpTest::framedRecoveryRechecksGrant);
  tests.put("unknownProducerNeverCompressesQuiet",AudioPumpTest::unknownProducerNeverCompressesQuiet);
  tests.put("incompleteProducerCannotAuthorizeRecovery",AudioPumpTest::incompleteProducerCannotAuthorizeRecovery);
  tests.put("skippedProductionPreservesHealthyMargin",AudioPumpTest::skippedProductionPreservesHealthyMargin);
  tests.put("boundedHeadsRecoverWithoutLosingHealthyPcm",AudioPumpTest::boundedHeadsRecoverWithoutLosingHealthyPcm);
  tests.put("healthyQuietIsInvariantUnderSameTimePartitions",AudioPumpTest::healthyQuietIsInvariantUnderSameTimePartitions);
  tests.put("longPlayedQuietRetiresOnlyExcessDebt",AudioPumpTest::longPlayedQuietRetiresOnlyExcessDebt);
  tests.put("originalFourSecondQuietRecovers",AudioPumpTest::originalFourSecondQuietRecovers);
  tests.put("shortQuietAndCoarsePlaybackCannotAuthorizeRecovery",AudioPumpTest::shortQuietAndCoarsePlaybackCannotAuthorizeRecovery);
  tests.put("quietRecoveryVetoesUnsafeBoundaries",AudioPumpTest::quietRecoveryVetoesUnsafeBoundaries);
  tests.put("actualDebtStillNeedsStableOutputAndCancellation",AudioPumpTest::actualDebtStillNeedsStableOutputAndCancellation);
  tests.put("provenQuietRecoveryStillHonorsCancellation",AudioPumpTest::provenQuietRecoveryStillHonorsCancellation);
  tests.put("injectedTargetsBoundPartialZeroAndStartup",AudioPumpTest::injectedTargetsBoundPartialZeroAndStartup);
  tests.put("injectedTargetsKeepPendingCapAgeAndCancellation",AudioPumpTest::injectedTargetsKeepPendingCapAgeAndCancellation);
  tests.put("invalidNormalTargetsRejected",AudioPumpTest::invalidNormalTargetsRejected);
  tests.put("lifecycle",AudioPumpTest::lifecycle);
  tests.put("failureEventsAreSeparateFromHealthyReports",AudioPumpTest::failureEventsAreSeparateFromHealthyReports);
  tests.put("failureReportDefaultsToLegacyReport",AudioPumpTest::failureReportDefaultsToLegacyReport);
  tests.put("orderedBatchesNotReplaced",AudioPumpTest::orderedBatchesNotReplaced);
  tests.put("overflowDropsOldestAndCounts",AudioPumpTest::overflowDropsOldestAndCounts);
  tests.put("partialWriteRetainsRemainderInOrder",AudioPumpTest::partialWriteRetainsRemainderInOrder);
  tests.put("zeroWriteRetriesWithoutNewAudio",AudioPumpTest::zeroWriteRetriesWithoutNewAudio);
  tests.put("healthyCadenceLosesNothing",AudioPumpTest::healthyCadenceLosesNothing);
  tests.put("underProductionCountsUnderruns",AudioPumpTest::underProductionCountsUnderruns);
  tests.put("thresholdLargerThanNormalTargetStarts",AudioPumpTest::thresholdLargerThanNormalTargetStarts);
  tests.put("healthyTaggedNotesAndGaps",()->healthyTaggedNotesAndGapsSurviveBacklog(false));
  tests.put("100msStallTaggedNotesAndGaps",()->healthyTaggedNotesAndGapsSurviveBacklog(true));
  tests.put("thresholdRecoveryAndUnknownHead",AudioPumpTest::thresholdRecoveryAndUnknownHead);
  tests.put("routeChangeWithUnknownHeadCannotInventRoom",AudioPumpTest::routeChangeWithUnknownHeadCannotInventRoom);
  tests.put("maximumPrimingAllowanceEndsAfterFill",AudioPumpTest::maximumPrimingAllowanceEndsAfterFill);
  tests.put("underrunBetweenObservationAndWriteRetainsNewPrefix",AudioPumpTest::underrunBetweenObservationAndWriteRetainsNewPrefix);
  tests.put("invalidThresholdFailsAudioOnly",AudioPumpTest::invalidThresholdFailsAudioOnly);
  tests.put("cancellationDuringPartialWriteAccountsOnce",AudioPumpTest::cancellationDuringPartialWriteAccountsOnce);
  tests.put("playbackHeadWrapAndResetHandled",AudioPumpTest::playbackHeadWrapAndResetHandled);
  tests.put("unknownOccupancyBoundsWritesAndWithholdsLatency",AudioPumpTest::unknownOccupancyBoundsWritesAndWithholdsLatency);
  tests.put("ambiguousOnsetsNotCorrelated",AudioPumpTest::ambiguousOnsetsNotCorrelated);
  tests.put("pendingOnsetKeepsItsOwnAdmission",AudioPumpTest::pendingOnsetKeepsItsOwnAdmission);
  tests.put("coalescedFocusLossFlushesWrittenAudio",AudioPumpTest::coalescedFocusLossFlushesWrittenAudio);
  for(Map.Entry<String,Runnable> t:tests.entrySet()){
   try{t.getValue().run();System.out.println("PASS "+t.getKey());}
   catch(RuntimeException|AssertionError e){failed.add(t.getKey());System.out.println("FAIL "+t.getKey()+": "+e);}
  }
  if(!failed.isEmpty())throw new AssertionError("AudioPumpTest failed: "+failed);
  System.out.println("AudioPumpTest: "+tests.size()+" tests: lifecycle, ordered bounded transport, retries, occupancy and latency telemetry passed");
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}
