package io.github.projectcetacean.tamaemu;

import java.nio.file.*;
import java.util.*;

/** Public synthetic instruction fixtures only; compare complete durable payloads at common boundaries. */
public final class NativeReplayBatchTest {
  private static int emit(byte[] image,int offset,int... words) {
    for(int word:words){image[offset++]=(byte)word;image[offset++]=(byte)(word>>8);}return offset;
  }
  private static int storeTone(byte[] image,int offset,int address,int value) {
    return emit(image,offset,0xc000|(address>>>19),0xc000|((address>>>6)&8191),0x6c00|((address&63)<<4),0xc000|(value>>>6),0x6c01|((value&63)<<4),0x3401);
  }
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    interface Op{void run()throws Exception;}
    static void invalid(Op op)throws Exception{try{op.run();throw new AssertionError("invalid batch accepted");}catch(IllegalArgumentException expected){checks++;}}
    static int batch(long h,int ms)throws Exception {
        return NativeBridge.replayHeadless(h,ms);
    }
    static void same(long a,long b,Path sa,Path sb)throws Exception {
        check(NativeBridge.save(a)&&NativeBridge.save(b),"both synthetic states save");
        check(Arrays.equals(Files.readAllBytes(sa),Files.readAllBytes(sb)),"all durable bytes equal");
        int[] pa=new int[16384],pb=new int[16384];NativeBridge.advance(a,0,pa);NativeBridge.advance(b,0,pb);
        check(Arrays.equals(pa,pb),"rendered LCD equal at common boundary");
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("native-replay-batch-");Path rom=dir.resolve("public-synthetic.bin");
        byte[] image=new byte[8388608];Arrays.fill(image,(byte)255);
        image[0x400000]=0;image[0x400001]=1;image[0x400002]=0x40;image[0x400003]=2;
        int cursor=0x400100;
        cursor=storeTone(image,cursor,0x300780,24);
        cursor=storeTone(image,cursor,0x300782,255);
        cursor=storeTone(image,cursor,0x300783,71);
        cursor=storeTone(image,cursor,0x3007e0,8);
        cursor=storeTone(image,cursor,0x300786,5);
        emit(image,cursor,0x0080);Files.write(rom,image);
        Path sa=dir.resolve("literal.state"),sb=dir.resolve("batch.state");
        long a=NativeBridge.open(rom.toString(),sa.toString()),b=NativeBridge.open(rom.toString(),sb.toString());
        if(args.length>0&&args[0].equals("--compat")) {
            for(int request:new int[]{1,99,100,101,1057,60000}) {
                int done=batch(b,request);check(done==Math.min(100,request),"missing batch symbol falls back to one exact literal operation");
                NativeBridge.advanceHeadless(a,done);same(a,b,sa,sb);
            }
            NativeBridge.close(a);NativeBridge.close(b);System.out.println("NativeReplayBatchTest: old-library compatibility passed");return;
        }
        final long handle=b;
        invalid(()->NativeBridge.advanceHeadlessBatch(handle,0));invalid(()->NativeBridge.advanceHeadlessBatch(handle,-1));
        invalid(()->NativeBridge.advanceHeadlessBatch(handle,60001));invalid(()->NativeBridge.advanceHeadlessBatch(handle,Integer.MAX_VALUE));
        invalid(()->batch(handle,0));invalid(()->batch(handle,-1));invalid(()->batch(handle,60001));invalid(()->batch(handle,Integer.MAX_VALUE));
        same(a,b,sa,sb);
        int calls=0;long nativeNs=0;
        for(int requested:new int[]{1,99,100,101,1057,60000}) {
            int left=requested;
            while(left>0){long start=System.nanoTime();int done=batch(b,left);nativeNs+=System.nanoTime()-start;calls++;
                check(done>0&&done<=left,"positive exact bounded completion");
                check(done==left||done%100==0,"budget stops only between identical 100ms operations");
                for(int literal=done;literal>0;){int step=Math.min(100,literal);NativeBridge.advanceHeadless(a,step);literal-=step;}
                left-=done;
            }
            same(a,b,sa,sb);
        }
        NativeBridge.close(a);NativeBridge.close(b);
        invalid(()->batch(handle,1));
        a=NativeBridge.open(rom.toString(),sa.toString());b=NativeBridge.open(rom.toString(),sb.toString());
        same(a,b,sa,sb);int done=batch(b,1057);for(int left=done;left>0;){int step=Math.min(100,left);NativeBridge.advanceHeadless(a,step);left-=step;}
        same(a,b,sa,sb);check(NativeBridge.drainAudio(b,9600).length==0,"batch never produces PCM");
        NativeBridge.close(a);NativeBridge.close(b);
        System.out.println("NativeReplayBatchTest: "+checks+" checks; calls="+calls+" native_ns="+nativeNs+"; host latency only");
    }
}
