package io.github.projectcetacean.tamaemu;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

/** Platform boundary only. Shadow grants do not establish hardware behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={33,36}, application=android.app.Application.class, shadows=ForegroundAudioAndroidTest.Grant.class)
public class ForegroundAudioAndroidTest {
    @Implements(AudioTrack.class)
    public static class Grant extends ShadowAudioTrack {
        static int buffer,capacity,actual,requested,sets,gets,releaseCount,writeMode,head,underruns,requestedBufferFrames;
        static boolean metricFailure;
        @Implementation protected int getBufferSizeInFrames(){requestedBufferFrames=super.native_get_buffer_size_frames();return buffer;}
        @Implementation protected int getBufferCapacityInFrames(){return capacity;}
        @Implementation protected int setStartThresholdInFrames(int frames){sets++;requested=frames;return 123;}
        @Implementation protected int getStartThresholdInFrames(){gets++;return actual;}
        @Implementation protected int getUnderrunCount(){if(metricFailure)throw new IllegalStateException("metric");return underruns;}
        @Implementation protected int getPerformanceMode(){return AudioTrack.PERFORMANCE_MODE_NONE;}
        @Implementation protected int getPlaybackHeadPosition(){return head;}
        @Implementation protected int write(short[] samples,int offset,int length,int mode){writeMode=mode;return length;}
        @Implementation protected void release(){releaseCount++;}
    }
    @Before public void reset(){Grant.buffer=2886;Grant.capacity=2886;Grant.actual=2886;Grant.requested=0;Grant.sets=0;Grant.gets=0;
        Grant.releaseCount=0;Grant.head=0;Grant.underruns=0;Grant.metricFailure=false;ShadowAudioTrack.setMinBufferSize(5760);}
    private AudioTrack track(){return new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(5772).build();}
    private Context metadataContext(String pkg,boolean debug,Object value){
        Context base=RuntimeEnvironment.getApplication();
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.applicationInfo=new ApplicationInfo();
        info.applicationInfo.packageName=pkg;info.applicationInfo.flags=debug?ApplicationInfo.FLAG_DEBUGGABLE:0;
        info.applicationInfo.metaData=new Bundle();
        String key="io.github.projectcetacean.tamaemu.audioqa.NORMAL_QUEUE_MS";
        if(value instanceof Integer)info.applicationInfo.metaData.putInt(key,(Integer)value);
        else if(value instanceof String)info.applicationInfo.metaData.putString(key,(String)value);
        else if(value instanceof Long)info.applicationInfo.metaData.putLong(key,(Long)value);
        else if(value instanceof Boolean)info.applicationInfo.metaData.putBoolean(key,(Boolean)value);
        Shadows.shadowOf(base.getPackageManager()).installPackage(info);
        return new ContextWrapper(base){
            @Override public String getPackageName(){return pkg;}
            @Override public Context getApplicationContext(){return this;}
        };
    }
    @Test public void onlyDebuggableExactQaPackageUsesIntegerMetadata(){
        String qa="io.github.projectcetacean.tamaemu.qoltest";
        for(int[] pair:new int[][]{{30,1440},{40,1920},{60,2880}}){
            assertEquals(pair[1],ForegroundAudio.normalQueueFrames(metadataContext(qa,true,pair[0])));
            assertEquals(2880,ForegroundAudio.normalQueueFrames(metadataContext(qa,false,pair[0])));
            assertEquals(2880,ForegroundAudio.normalQueueFrames(metadataContext("io.github.projectcetacean.tamaemu",true,pair[0])));
            assertEquals(2880,ForegroundAudio.normalQueueFrames(metadataContext(qa+".foreign",true,pair[0])));
        }
        for(Object invalid:new Object[]{null,"30","40",30L,true,0,-1,29,31,41,61,9600})
            assertEquals("malformed metadata "+invalid,2880,ForegroundAudio.normalQueueFrames(metadataContext(qa,true,invalid)));
        assertEquals(2880,ForegroundAudio.normalQueueFrames(RuntimeEnvironment.getApplication()));
    }
    @Test public void selectedTargetUsesActualReadbackAndReportsRequest(){
        Context app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf((AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        ShadowLog.setLoggable("TamaemuAudio",android.util.Log.DEBUG);
        for(int target:new int[]{1440,1920,2880}){
            Grant.buffer=6000;Grant.capacity=6000;Grant.actual=2886;
            ForegroundAudio.Track out=new ForegroundAudio.Track(app,target);
            try{assertTrue(out.start(granted->{}));assertEquals(target,Grant.requested);
                assertEquals(2886,out.startThresholdFrames()); // Device can grant a larger requirement.
                Grant.actual=960;assertEquals(960,out.startThresholdFrames()); // Never force a higher readback.
                out.report(new AudioPump.Latency().summary());
                String report=ShadowLog.getLogsForTag("TamaemuAudio").get(ShadowLog.getLogsForTag("TamaemuAudio").size()-1).msg;
                assertTrue(report,report.contains("normalQueueFrames="+target)&&report.contains("normalQueueMs="+(target/48))
                    &&report.contains("startThresholdRequested="+target)&&report.contains("startThresholdRequired=960"));
            }finally{out.stop();}
        }
    }
    @Test public void selectedTargetBufferRequestHonorsPlatformMinimum(){
        Context app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf((AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        for(int[] test:new int[][]{{1440,1920,1440},{1920,1920,1920},{2880,1920,2880},{1440,8000,4000},{1920,8000,4000},{2880,8000,4000}}){
            ShadowAudioTrack.setMinBufferSize(test[1]);
            ForegroundAudio.Track out=new ForegroundAudio.Track(app,test[0]);
            try{assertTrue(out.start(granted->{}));assertEquals("actual builder buffer request in frames",test[2],Grant.requestedBufferFrames);}
            finally{out.stop();}
        }
    }
    @Test @Config(sdk=26) public void selectedTargetsKeepCapacityFallbackOnOldApi(){
        Context app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf((AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        for(int target:new int[]{1440,1920,2880}){
            ForegroundAudio.Track out=new ForegroundAudio.Track(app,target);
            try{assertTrue(out.start(granted->{}));assertEquals(2886,out.startThresholdFrames());assertEquals(0,Grant.sets);assertEquals(0,Grant.gets);}
            finally{out.stop();}
        }
    }
    @Test public void requestsBoundedThresholdButUsesGetterReadback(){
        AudioTrack track=track();try{
            assertEquals(2886,ForegroundAudio.configureStartThreshold(track,2880));
            assertEquals(2880,Grant.requested);assertEquals(1,Grant.sets);assertEquals(1,Grant.gets);
            Grant.buffer=960;Grant.actual=960;
            assertEquals(960,ForegroundAudio.configureStartThreshold(track,2880));assertEquals(960,Grant.requested);
        }finally{track.release();}
    }
    @Test @Config(sdk=26) public void unsupportedThresholdApiUsesConservativeCapacity(){
        // The cached pre-31 runtime exercises the same guarded branch used on minSdk30.
        AudioTrack track=track();try{
            assertEquals(2886,ForegroundAudio.configureStartThreshold(track,2880));assertEquals(0,Grant.sets);assertEquals(0,Grant.gets);
            Grant.buffer=2880;
            try{ForegroundAudio.configureStartThreshold(track,2880);fail("capacity is unreachable through smaller writable buffer");}catch(IllegalStateException expected){}
        }finally{track.release();}
    }
    @Test public void invalidOrUnreachableReadbackFails(){
        AudioTrack track=track();try{for(int n:new int[]{0,-1,2887,9601}){Grant.actual=n;
            try{ForegroundAudio.configureStartThreshold(track,2880);fail("invalid/unreachable "+n);}catch(IllegalStateException expected){}}
        }finally{track.release();}
    }
    @Test public void realAdapterUsesNonblockingWritesAndRefreshesGrant(){
        Context app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf((AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        ForegroundAudio.Track out=new ForegroundAudio.Track(app);
        try{assertTrue(out.start(granted->{}));assertEquals(2886,out.startThresholdFrames());assertEquals(2886,out.writableFrames());
            out.write(new short[100],0,100);assertEquals(AudioTrack.WRITE_NON_BLOCKING,Grant.writeMode);assertEquals(100,out.queuedFrames());
            Grant.head=60;assertEquals(40,out.queuedFrames());Grant.actual=960;assertEquals(960,out.startThresholdFrames());
            Grant.underruns=2;assertEquals(2,out.underrunCount());
            out.suspend();assertEquals(0,out.queuedFrames());out.resume();assertEquals(960,out.startThresholdFrames());
        }finally{out.stop();}assertEquals(1,Grant.releaseCount);
    }
    @Test public void malformedOrThrowingGrantStopsReleasesAndReportsFailure(){
        Context app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf((AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        for(boolean throwing:new boolean[]{false,true}){
            Grant.actual=throwing?2886:9601;Grant.metricFailure=throwing;int releases=Grant.releaseCount;
            java.util.ArrayDeque<Runnable> tasks=new java.util.ArrayDeque<>();
            AudioPump pump=new AudioPump(tasks::add,(ms,task)->()->{},()->0,()->new ForegroundAudio.Track(app));
            pump.configure(true);pump.offer(pump.token(),new short[100]);while(!tasks.isEmpty())tasks.remove().run();
            assertFalse(pump.accepting());assertEquals(releases+1,Grant.releaseCount);
            assertEquals(1,pump.latency().summary().outputFailures);AudioPumpTest.conserved(pump.latency().summary(),"platform failure");
        }
        assertTrue(ShadowLog.getLogsForTag("TamaemuAudio").stream().anyMatch(item->item.msg.contains("Audio unavailable")));
    }
    @Test public void reportUsesActualGrantAndPerformanceMode(){
        Context app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf((AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        ShadowLog.setLoggable("TamaemuAudio",android.util.Log.DEBUG);
        ForegroundAudio.Track out=new ForegroundAudio.Track(app);
        try{assertTrue(out.start(granted->{}));out.startThresholdFrames();Grant.underruns=3;out.underrunCount();
            out.report(new AudioPump.Latency().summary());
            String report=ShadowLog.getLogsForTag("TamaemuAudio").get(ShadowLog.getLogsForTag("TamaemuAudio").size()-1).msg;
            assertTrue(report,report.contains("startThresholdKnown=true")&&report.contains("startThresholdRequired=2886")
                    &&report.contains("startThresholdRequested=2880")&&report.contains("bufferFrames=2886")&&report.contains("capacityFrames=2886")
                    &&report.contains("performanceMode=0")&&report.contains("routeId=-1")&&report.contains("platformUnderruns=3"));
        }finally{out.stop();}
    }

    @Test public void healthyReportsAfterDeniedStartDoNotWarn(){recoveredReports(false);}
    @Test public void healthyReportsAfterDeniedStartKeepDebugTelemetry(){recoveredReports(true);}
    private void recoveredReports(boolean debug){
        Context app=RuntimeEnvironment.getApplication();
        AudioManager manager=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
        ShadowLog.clear();ShadowLog.setLoggable("TamaemuAudio",debug?android.util.Log.DEBUG:android.util.Log.INFO);
        Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        java.util.ArrayDeque<Runnable> tasks=new java.util.ArrayDeque<>();
        long[] now={0};
        AudioPump pump=new AudioPump(tasks::add,(ms,task)->()->{},()->now[0],()->new ForegroundAudio.Track(app));
        try{
            pump.configure(true);pump.offer(pump.token(),new short[960]);while(!tasks.isEmpty())tasks.remove().run();
            assertFalse("denied first start",pump.accepting());assertEquals(1,pump.latency().summary().outputFailures);
            assertEquals("current failure warns once",1,audioLogs(android.util.Log.WARN));
            Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            pump.configure(false);pump.configure(true);Grant.actual=960;
            for(int at=0;at<=4200;at+=20){
                now[0]=at;Grant.head=(int)pump.latency().summary().writtenFrames;
                assertTrue(pump.offer(pump.token(),new short[960])>0);while(!tasks.isEmpty())tasks.remove().run();
            }
            AudioPump.Summary summary=pump.latency().summary();
            assertTrue(pump.accepting());assertEquals(1,summary.outputFailures);
            assertEquals(202560,summary.writtenFrames);assertEquals(960,summary.flushedFrames);
            AudioPumpTest.conserved(summary,"recovered platform output");
            long warnings=audioLogs(android.util.Log.WARN),reports=audioLogs(android.util.Log.DEBUG);
            System.out.println("recoveredReports debug="+debug+" healthyMs=4200 WARN="+warnings+" DEBUG="+reports+" "+summary.line());
            assertEquals("healthy 2s reports must not repeat the earlier unavailable warning",1,warnings);
            assertEquals("healthy reports respect DEBUG gate",debug?2:0,reports);
            if(debug)assertTrue(ShadowLog.getLogsForTag("TamaemuAudio").stream()
                    .filter(item->item.type==android.util.Log.DEBUG).allMatch(item->item.msg.contains("outputFailures=1")));
        }finally{pump.configure(false);while(!tasks.isEmpty())tasks.remove().run();}
    }
    private long audioLogs(int level){return ShadowLog.getLogsForTag("TamaemuAudio").stream().filter(item->item.type==level).count();}

}
