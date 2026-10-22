package io.github.projectcetacean.tamaemu;

/** Replay must forward exact partial work while keeping the real audio producer silent. */
public final class AudioReplayBatchTest {
    public static void main(String[] args)throws Exception {
        ReplayBatchTest.Batch guest=new ReplayBatchTest.Batch();guest.limit=200;
        AudioPumpTest.Fixture f=new AudioPumpTest.Fixture();f.pump.configure(true);
        int[] drains={0},enabled={0};
        AudioBackend audio=new AudioBackend(guest,new AudioBackend.NativeAudio(){
            public void enabled(long h,boolean e){if(e)enabled[0]++;}
            public short[] drain(long h,int n){drains[0]++;return new short[0];}
        },()->f.pump,()->f.now);
        int done=audio.replay(1,1000);f.run();
        ReplayBatchTest.check(done==200&&guest.progress==200,"audio wrapper returns partial completion unchanged");
        ReplayBatchTest.check(drains[0]==0&&enabled[0]==0&&f.output.writes.isEmpty(),"headless batches produce no audio");
        // Existing live startup prepares output asynchronously before admitting PCM.
        audio.advanceWithDebt(1,16,new int[128*128],16);f.run();
        ReplayBatchTest.check(drains[0]==0&&enabled[0]==0,"live startup waits for existing readiness policy");
        audio.advanceWithDebt(1,16,new int[128*128],16);f.run();
        ReplayBatchTest.check(drains[0]==1&&enabled[0]==1,"ordinary live admission resumes unchanged");
        guest.bad=1;
        try{audio.replay(1,1000);throw new AssertionError("invalid completion accepted");}
        catch(IllegalStateException expected){ReplayBatchTest.check(true,"invalid batch rejected before telemetry progress");}
        System.out.println("AudioReplayBatchTest: passed");
    }
}
