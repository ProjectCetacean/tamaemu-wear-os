package io.github.projectcetacean.tamaemu;

import java.util.Arrays;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Runs only on the existing serialized owner worker; output sees detached PCM only. */
final class AudioBackend implements EmulatorSession.Backend {
    interface NativeAudio { void enabled(long handle, boolean enabled); short[] drain(long handle,int max); }
    private final EmulatorSession.Backend guest;
    private final NativeAudio audio;
    private final Supplier<AudioPump> output;
    private final LongSupplier nowMs;
    // Owner worker only. Telemetry keeps guest progress, an onset index and silence; never sample values.
    private long progress;
    private AudioPump producerPump;
    private long producerToken,audibleCompletedMs;
    private void resetProducer(){producerPump=null;producerToken=0;audibleCompletedMs=0;}
    private boolean opened;
    private void abandonProducer(){
        AudioPump previous=producerPump;long previousToken=producerToken;resetProducer();
        // Focus/configuration already discarded an obsolete PCM epoch. Do not retire
        // the retained platform request whose gain callback belongs to that lifecycle.
        if(previous!=null)previous.invalidate(previousToken);
    }
    private AudioPump currentPump(){
        try{return output.get();}catch(RuntimeException|LinkageError unavailable){return null;}
    }
    private boolean silent, awaitingOnset;
    // Native button state, so only a real release of a held button ends a measured press.
    private final boolean[] held=new boolean[3];
    AudioBackend(EmulatorSession.Backend guest,NativeAudio audio,Supplier<AudioPump> output,LongSupplier nowMs) {
        this.guest=guest;this.audio=audio;this.output=output;this.nowMs=nowMs;
    }
    private boolean enabled(long handle,boolean enabled) {
        try{audio.enabled(handle,enabled);return true;}
        catch(RuntimeException|LinkageError unavailable){return false; /* Guest operation remains authoritative. */}
    }
    /** Silence is only known from an observed audible batch. */
    private void unobserved(){silent=false;awaitingOnset=false;}
    /** Observe live output acceptance only; unavailable audio keeps the owner's default cadence. */
    public boolean soundAccepting() {
        try { AudioPump pump=output.get();return pump!=null&&pump.accepting(); }
        catch(RuntimeException|LinkageError unavailable){return false;}
    }
    public long open(String rom,String save)throws Exception{
        boolean reopening=opened||producerPump!=null;resetProducer();unobserved();
        AudioPump pump=currentPump();if(reopening&&pump!=null)pump.invalidate();
        long handle=guest.open(rom,save);opened=true;return handle;
    }
    public void close(long handle){resetProducer();unobserved();Arrays.fill(held,false);enabled(handle,false);AudioPump pump=output.get();if(pump!=null)pump.invalidate();guest.close(handle);}
    public int width(long handle){return guest.width(handle);}
    public int height(long handle){return guest.height(handle);}
    public void advance(long handle,int elapsed,int[] pixels)throws Exception {
        advanceWithDebt(handle,elapsed,pixels,AudioPump.PassiveObserver.UNAVAILABLE);
    }
    public void advanceWithDebt(long handle,int elapsed,int[] pixels,long debtMs)throws Exception {
        AudioPump pump=currentPump();long token=pump==null?0:pump.token();
        boolean audible=elapsed>0&&pump!=null&&pump.accepting();
        if(elapsed>0&&!enabled(handle,audible)){
            if(pump!=null)pump.invalidate();audible=false;
        }
        long start=progress;
        try{guest.advance(handle,elapsed,pixels);}
        catch(Exception|LinkageError failure){resetProducer();unobserved();if(pump!=null)pump.invalidate();throw failure;}
        progress+=elapsed;
        if(elapsed<=0)return; // Render-only save captures do not drain or erase audible history.
        if(!audible){abandonProducer();unobserved();return;}
        try {
            short[] samples=audio.drain(handle,9600);
            if(currentPump()!=pump||pump.token()!=token||!pump.accepting()){
                abandonProducer();unobserved();return;
            }
            if(producerPump!=pump||producerToken!=token){
                if(producerPump!=null&&producerPump!=pump)producerPump.invalidate();
                resetProducer();producerPump=pump;producerToken=token;
            }
            audibleCompletedMs=Math.addExact(audibleCompletedMs,(long)elapsed);
            long sequence=pump.offerCompletedAdvance(token,audibleCompletedMs,samples,debtMs);
            if(samples!=null&&samples.length>0)measure(pump,sequence,samples,start);
            else if(samples==null)unobserved();
        } catch(RuntimeException|LinkageError unavailable){resetProducer();unobserved();pump.invalidate();enabled(handle,false);}
    }
    private void measure(AudioPump pump,long sequence,short[] samples,long start) {
        if(sequence<0){unobserved();return;}
        int first=-1;
        for(int i=0;i<samples.length;i++)if(samples[i]!=0){first=i;break;}
        if(awaitingOnset&&first>=0){awaitingOnset=false;pump.latency().onset(sequence,first,start);}
        silent=first<0;
    }
    public void headless(long handle,int elapsed)throws Exception {
        resetProducer();unobserved();enabled(handle,false);AudioPump pump=output.get();if(pump!=null)pump.invalidate();
        guest.headless(handle,elapsed);progress+=elapsed;
    }
    public long[] readLife(long handle){return guest.readLife(handle);}
    public void writeLife(long handle,long[] values){guest.writeLife(handle,values);}
    public byte[] committedPayloadHash(long handle){return guest.committedPayloadHash(handle);}
    /** Observed after the guest call returns: telemetry never changes native button chronology. */
    public void button(long handle,int button,boolean down){
        guest.button(handle,button,down);
        if(button<0||button>=held.length)return;
        boolean wasHeld=held[button];held[button]=down;
        if(wasHeld&&!down)released();
    }
    /** Latency is measured from press-down admission only; releases can only void a pending candidate. */
    public void inputApplied(long handle,int button,boolean down,long admittedElapsedMs) {
        if(!down){released();return;}
        try {
            AudioPump pump=output.get();
            if(pump==null||!pump.accepting())return;
            pump.latency().applied(admittedElapsedMs,nowMs.getAsLong(),progress,silent);awaitingOnset=true;
        } catch(RuntimeException unavailable){ /* Telemetry never reaches the guest worker. */ }
    }
    /** A release (applied, deferred native or forced) ends the press. A tone it triggers from silence is counted, uncorrelated. */
    private void released() {
        try {
            AudioPump pump=output.get();
            if(pump==null||!pump.accepting())return;
            pump.latency().released();if(silent)awaitingOnset=true;
        } catch(RuntimeException unavailable){ /* Telemetry never reaches the guest worker. */ }
    }
    public boolean save(long handle)throws Exception{return guest.save(handle);}
}
