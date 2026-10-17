package io.github.projectcetacean.tamaemu;
import java.nio.file.*;
import java.util.*;
public class LifeJniProbe {
  interface Op {void run() throws Exception;}
  static int checks;
  static void check(boolean ok,String name) {if(!ok)throw new AssertionError(name);checks++;}
  static int emit(byte[] image,int offset,int... words) {
    for(int word:words){image[offset++]=(byte)word;image[offset++]=(byte)(word>>8);}return offset;
  }
  static int storeTone(byte[] image,int offset,int address,int value) {
    return emit(image,offset,0xc000|(address>>>19),0xc000|((address>>>6)&8191),0x6c00|((address&63)<<4),0xc000|(value>>>6),0x6c01|((value&63)<<4),0x3401);
  }
  static void invalid(Op op) throws Exception {try{op.run();throw new AssertionError("invalid call accepted");}catch(IllegalArgumentException expected){checks++;}}
  public static void main(String[] args) throws Exception {
    Path dir=Files.createTempDirectory("native-life-jni-");
    Path rom=args.length>0?Paths.get(args[0]):dir.resolve("synthetic.bin");
    if(args.length==0) {
      byte[] image=new byte[8388608];Arrays.fill(image,(byte)255);
      image[0x400000]=0;image[0x400001]=1;image[0x400002]=0x40;image[0x400003]=2;
      // Real S1C33 stores configure T0 for 1kHz at the 18.432MHz reset clock.
      int cursor=0x400100;
      cursor=storeTone(image,cursor,0x300780,24);
      cursor=storeTone(image,cursor,0x300782,255);
      cursor=storeTone(image,cursor,0x300783,71);
      cursor=storeTone(image,cursor,0x3007e0,8);
      cursor=storeTone(image,cursor,0x300786,5);
      emit(image,cursor,0x0080);
      Files.write(rom,image);
    }
    Path save=dir.resolve("jni.state");
    long h=NativeBridge.open(rom.toString(),save.toString());
    NativeBridge.setAudioEnabled(h,false);
    check(NativeBridge.drainAudio(h,0).length==0,"zero sample drain is empty");
    check(NativeBridge.drainAudio(h,9600).length==0,"disabled fresh audio is empty");
    NativeBridge.setAudioEnabled(h,true);NativeBridge.setAudioEnabled(h,true);
    invalid(()->NativeBridge.drainAudio(h,-1));invalid(()->NativeBridge.drainAudio(h,9601));invalid(()->NativeBridge.drainAudio(h,Integer.MAX_VALUE));
    check(NativeBridge.drainAudio(h,9600).length==0,"invalid audio drains preserve empty runtime queue");
    NativeBridge.advance(h,0,new int[128*128]);check(NativeBridge.drainAudio(h,9600).length==0,"zero-time JNI frame creates no audio");
    if(args.length==0) {
      NativeBridge.advance(h,20,new int[128*128]);
      invalid(()->NativeBridge.drainAudio(h,9601));
      short[] tone=NativeBridge.drainAudio(h,9600);int sounding=0,crosses=0;
      for(int i=1;i<tone.length;i++)if(tone[i]!=0&&tone[i-1]!=0&&tone[i]!=tone[i-1])crosses++;
      check(crosses>=38&&crosses<=41,"1kHz T0 tone retains pitch across JNI");
      for(short sample:tone){check(Math.abs((int)sample)<=4500,"JNI PCM amplitude bounded");if(sample!=0)sounding++;}
      check(sounding>800,"register-driven original tone crosses JNI as PCM");
      Arrays.fill(tone,(short)123);check(NativeBridge.drainAudio(h,9600).length==0,"PCM drains exactly once into detached Java array");
      NativeBridge.advance(h,2,new int[128*128]);short[] next=NativeBridge.drainAudio(h,9600);
      check(next.length>0,"subsequent rendered execution supplies new PCM");for(short sample:next)check(sample!=123,"caller cannot mutate native PCM");
    }
    long[] zero=new long[8];check(Arrays.equals(NativeBridge.readLife(h),zero),"fresh life zero");
    check(NativeBridge.committedPayloadHash(h)==null,"fresh receipt absent");
    long[] candidate={1,123,0,Long.MAX_VALUE,1000,1,1,1}, original=candidate.clone();
    NativeBridge.writeLife(h,candidate);check(Arrays.equals(candidate,original),"input array unchanged");
    long[] out=NativeBridge.readLife(h);out[1]=999;check(Arrays.equals(NativeBridge.readLife(h),original),"read detached");
    invalid(()->NativeBridge.writeLife(h,null));invalid(()->NativeBridge.writeLife(h,new long[7]));invalid(()->NativeBridge.writeLife(h,new long[9]));
    for(int slot=0;slot<8;slot++){long[] bad=original.clone();bad[slot]=-1;invalid(()->NativeBridge.writeLife(h,bad));}
    for(long[] bad:new long[][]{{0,1,0,0,0,0,0,0},{2,1,0,0,0,0,1,0},{1,0,0,0,0,0,1,0},{1,1,0,0,0,2,1,0},{1,1,0,0,0,0,0,0},{1,1,0,0,0,0,1,2}})invalid(()->NativeBridge.writeLife(h,bad));
    invalid(()->NativeBridge.advanceHeadless(h,-1));invalid(()->NativeBridge.advanceHeadless(h,101));invalid(()->NativeBridge.advanceHeadless(h,Integer.MAX_VALUE));
    check(Arrays.equals(NativeBridge.readLife(h),original),"invalid calls preserve candidate");
    NativeBridge.advanceHeadless(h,0);check(NativeBridge.save(h),"JNI durable commit");
    byte[] blob=Files.readAllBytes(save), receipt=NativeBridge.committedPayloadHash(h);
    check(receipt.length==32 && Arrays.equals(receipt,Arrays.copyOfRange(blob,56,88)),"receipt exact header checksum");
    receipt[0]^=1;check(Arrays.equals(NativeBridge.committedPayloadHash(h),Arrays.copyOfRange(blob,56,88)),"receipt detached");
    NativeBridge.advanceHeadless(h,100);check(NativeBridge.drainAudio(h,9600).length==0,"headless JNI advance creates no audio");
    NativeBridge.setAudioEnabled(h,false);check(NativeBridge.drainAudio(h,9600).length==0,"disabled JNI output stays empty");
    check(NativeBridge.save(h),"inclusive 100ms headless boundary commits");
    byte[] advanced=Files.readAllBytes(save);check(!Arrays.equals(Arrays.copyOfRange(blob,56,88),Arrays.copyOfRange(advanced,56,88)),"positive JNI elapsed changes committed guest payload");blob=advanced;
    candidate[6]=2;NativeBridge.writeLife(h,candidate);check(Arrays.equals(NativeBridge.committedPayloadHash(h),Arrays.copyOfRange(blob,56,88)),"working mutation preserves commit receipt");
    NativeBridge.close(h);invalid(()->NativeBridge.setAudioEnabled(h,true));invalid(()->NativeBridge.drainAudio(h,1));invalid(()->NativeBridge.readLife(h));invalid(()->NativeBridge.writeLife(h,original));invalid(()->NativeBridge.committedPayloadHash(h));invalid(()->NativeBridge.advanceHeadless(h,0));
    long reopened=NativeBridge.open(rom.toString(),save.toString());check(Arrays.equals(NativeBridge.readLife(reopened),original),"JNI restore metadata");NativeBridge.setAudioEnabled(reopened,true);check(NativeBridge.drainAudio(reopened,9600).length==0,"restored JNI tone history is silent");NativeBridge.close(reopened);
    System.out.println("PASS: "+checks+" actual host JNI checks");
  }
}
