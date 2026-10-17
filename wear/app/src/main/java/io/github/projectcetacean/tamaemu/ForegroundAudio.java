package io.github.projectcetacean.tamaemu;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Allocated only by a foreground Activity. Platform playback is lazy and best effort. */
final class ForegroundAudio {
    final AudioPump pump;
    ForegroundAudio(Context context) {
        Context app=context.getApplicationContext();
        final int normalQueueFrames=normalQueueFrames(app);
        // One thread runs drains and their delayed retries, so output calls stay serialized.
        ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(task->{
            Thread thread=new Thread(task,"pet-audio");thread.setDaemon(true);return thread;
        });
        pump=new AudioPump(executor,(delay,task)->{
            ScheduledFuture<?> future=executor.schedule(task,delay,TimeUnit.MILLISECONDS);return ()->future.cancel(false);
        },SystemClock::elapsedRealtime,()->new Track(app,normalQueueFrames),normalQueueFrames);
    }
    /** QA overlay only: production and foreign packages always keep the 60 ms default. */
    static int normalQueueFrames(Context app) {
        if(!"io.github.projectcetacean.tamaemu.qoltest".equals(app.getPackageName()))return AudioPump.TARGET_QUEUED_FRAMES;
        try {
            ApplicationInfo info=app.getPackageManager().getApplicationInfo(app.getPackageName(),PackageManager.GET_META_DATA);
            if((info.flags&ApplicationInfo.FLAG_DEBUGGABLE)==0||info.metaData==null)return AudioPump.TARGET_QUEUED_FRAMES;
            Object value=info.metaData.get("io.github.projectcetacean.tamaemu.audioqa.NORMAL_QUEUE_MS");
            if(value instanceof Integer) {
                int ms=(Integer)value;
                if(ms==30||ms==40||ms==60)return ms*AudioPump.FRAMES_PER_MS;
            }
        } catch(PackageManager.NameNotFoundException|RuntimeException ignored) { /* Missing or malformed QA metadata keeps the default. */ }
        return AudioPump.TARGET_QUEUED_FRAMES;
    }
    /** API31 exposes the requirement; API30 uses a labeled conservative capacity requirement. */
    static int configureStartThreshold(AudioTrack track,int target) {
        int buffer=track.getBufferSizeInFrames(),capacity=track.getBufferCapacityInFrames();
        if(target<1||buffer<1||capacity<buffer)throw new IllegalStateException("Invalid audio buffer grant");
        if(Build.VERSION.SDK_INT>=31)track.setStartThresholdInFrames(Math.min(buffer,target));
        int actual=Build.VERSION.SDK_INT>=31?track.getStartThresholdInFrames():capacity;
        return reachable(actual,buffer);
    }
    private static int reachable(int required,int buffer) {
        if(required<1||required>AudioPump.MAX_PENDING_FRAMES||required>buffer)
            throw new IllegalStateException("Unreachable audio start threshold");
        return required;
    }
    static final class Track implements AudioPump.Output {
        private static final String TAG="TamaemuAudio";
        private final AudioManager manager;
        private final int normalQueueFrames;
        private final AudioPump.Occupancy occupancy=new AudioPump.Occupancy();
        private AudioFocusRequest request;
        private AudioTrack track;
        private boolean configured;
        private int required,requested,buffer,capacity,route=-1,performance,platformUnderruns=-1;
        Track(Context context){this(context,AudioPump.TARGET_QUEUED_FRAMES);}
        Track(Context context,int normalQueueFrames){
            this.normalQueueFrames=normalQueueFrames;manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        }
        public boolean start(AudioPump.Focus focus) {
            if(manager==null)return false;
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            request=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(
                            change->focus.changed(change==AudioManager.AUDIOFOCUS_GAIN),new Handler(Looper.getMainLooper())).build();
            if(manager.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return false;
            int minimum=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)return false;
            // Ask for a low-latency path and only enough buffer for the written-ahead target; the grant is reported.
            track=new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(new AudioFormat.Builder()
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(Math.max(minimum,normalQueueFrames*2)).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)return false;
            refreshGrant();track.play();occupancy.reset(track.getPlaybackHeadPosition());return true;
        }
        private void refreshGrant() {
            int size=track.getBufferSizeInFrames(),cap=track.getBufferCapacityInFrames();
            AudioDeviceInfo device=track.getRoutedDevice();int id=device==null?-1:device.getId();
            boolean changed=!configured||size!=buffer||cap!=capacity||id!=route;
            buffer=size;capacity=cap;route=id;requested=Math.min(buffer,normalQueueFrames);
            if(changed){required=configureStartThreshold(track,normalQueueFrames);configured=true;}
            else required=reachable(Build.VERSION.SDK_INT>=31?track.getStartThresholdInFrames():capacity,buffer);
            performance=track.getPerformanceMode();
        }
        public int startThresholdFrames(){refreshGrant();return required;}
        public int writableFrames(){return buffer;}
        public int routeId(){return route;}
        public int underrunCount(){platformUnderruns=track.getUnderrunCount();return platformUnderruns;}
        public int write(short[] samples,int offset,int length) {
            int written=track.write(samples,offset,length,AudioTrack.WRITE_NON_BLOCKING);
            if(written<0)throw new IllegalStateException("Audio output unavailable");
            // A full device buffer returns a short count; the pump keeps the remainder in order.
            occupancy.wrote(written);return written;
        }
        public long queuedFrames(){return track==null?-1:occupancy.queued(track.getPlaybackHeadPosition());}
        public void report(AudioPump.Summary s) {
            if(track!=null&&Log.isLoggable(TAG,Log.DEBUG))Log.d(TAG,reportLine(s));
        }
        public void reportFailure(AudioPump.Summary s) { Log.w(TAG,"Audio unavailable "+reportLine(s)); }
        private String reportLine(AudioPump.Summary s) {
            return s.line()+" normalQueueMs="+(normalQueueFrames/AudioPump.FRAMES_PER_MS)+" normalQueueFrames="+normalQueueFrames+" startThresholdKnown="+(Build.VERSION.SDK_INT>=31)+" startThresholdRequired="+required
                    +" startThresholdRequested="+requested+" bufferFrames="+buffer+" capacityFrames="+capacity
                    +" performanceMode="+performance+" routeId="+route+" platformUnderruns="+platformUnderruns;
        }
        public void suspend(){if(track!=null){track.pause();track.flush();occupancy.reset(track.getPlaybackHeadPosition());}}
        public void resume(){if(track!=null){refreshGrant();track.play();}}
        public void stop(){
            try {
                if(track!=null){
                    AudioTrack previous=track;track=null;
                    try{previous.pause();previous.flush();}finally{previous.release();}
                }
            } finally {
                if(manager!=null&&request!=null){
                    AudioFocusRequest previous=request;request=null;manager.abandonAudioFocusRequest(previous);
                }
            }
        }
    }
}
