package io.github.projectcetacean.tamaemu;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Host models exercise the finite controller, never assert platform AudioTrack behaviour. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=36, application=TamaemuApplication.class)
public class AudioQaInputTrackProbeAndroidTest {
    static final class Time implements AudioQaInputTrackProbe.Clock {
        long t; long step=500000;
        public long now(){return t;}
        public void idle(){t+=step;}
    }
    static final class Sink implements AudioQaInputTrackProbe.Port {
        final Time time; long head,total,last; int underruns,closes,writes,maxWrite=100000,zeroAfter=100000,threshold=480,headQuantum=1;
        long readDelay;
        boolean started,flow,early=true,failWrite,reset,routeChange,grantChange,neverUnderrun,denied,closeFails,loseFocus,overAccept,throwStart,throwHead;
        AudioPump.Focus focus;
        Sink(Time t){time=t;}
        public boolean start(AudioPump.Focus f){focus=f;if(throwStart)throw new IllegalStateException();started=true;last=time.t;return !denied;}
        void advance(){
            long frames=(time.t-last)*48/1000000;last=time.t;
            if(flow){head=Math.min(total,head+frames);if(head==total){flow=false;if(!neverUnderrun)underruns++;}}
        }
        public long head(){if(throwHead)throw new IllegalStateException();advance();if(reset&&time.t>150000000)return 0;long reported=head/headQuantum*headQuantum;time.t+=readDelay;return reported;}
        public int[] grant(){return new int[]{threshold,threshold*2,threshold*2,1,routeChange&&time.t>150000000?8:7,2,underruns,3,1,grantChange&&time.t>150000000?44100:48000};}
        public int write(short[] zeros,int length){
            if(failWrite)throw new IllegalStateException();
            for(short s:zeros)assertEquals("generated zeros only",0,s);
            advance();if(loseFocus)focus.changed(false);writes++;if(overAccept)return length+1;int n=writes>zeroAfter?0:Math.min(length,maxWrite);total+=n;
            if(total-head>=threshold||(underruns>0&&early&&n>0))flow=true;
            return n;
        }
        public void close(){closes++;if(closeFails)throw new IllegalStateException();}
    }
    Time t; Sink s;
    @Before public void setup(){t=new Time();s=new Sink(t);}
    AudioQaInputTrackProbe.Result run(){return AudioQaInputTrackProbe.measure(()->s,t,()->true);}
    @Test public void earlyHeadBeforeCrossingIsOnlyTestedPathEvidence(){
        AudioQaInputTrackProbe.Result r=run();assertEquals("complete",r.reason);
        assertEquals("EARLY_RESTART_TESTED_PATH",r.restart);assertEquals(1,s.closes);assertTrue(r.released);
        assertEquals(s.total,r.accepted);assertTrue(r.count>100);assertTrue(r.count<=4096);
    }
    @Test public void nonmovingSubthresholdNeverBecomesUniversalPass(){s.early=false;assertEquals("INCONCLUSIVE",run().restart);assertEquals(1,s.closes);}
    @Test public void partialAndZeroWritesUseActualAcceptedCounts(){s.maxWrite=37;AudioQaInputTrackProbe.Result r=run();assertEquals(s.total,r.accepted);assertTrue(r.accepted>0);assertEquals(1,s.closes);}
    @Test public void zeroAcceptanceCannotSpinOrClaimSuccess(){s.maxWrite=0;AudioQaInputTrackProbe.Result r=run();assertNotEquals("complete",r.reason);assertEquals(0,r.accepted);assertEquals(1,s.closes);assertTrue(t.t<=3000000000L);}
    @Test public void deadlineAndCancellationReleaseOwnedTrack(){t.step=3100000000L;AudioQaInputTrackProbe.Result r=run();assertEquals("deadline",r.reason);assertEquals(1,s.closes);assertEquals("INCONCLUSIVE",r.restart);}
    @Test public void cancellationAfterAllocationStillCleansUp(){AudioQaInputTrackProbe.Result r=AudioQaInputTrackProbe.measure(()->s,t,()->t.t<10000000);assertEquals("cancelled",r.reason);assertEquals(1,s.closes);}
    @Test public void exceptionAndFocusDenialStillCleanUp(){s.failWrite=true;assertEquals("error",run().reason);assertEquals(1,s.closes);setup();s.denied=true;assertEquals("focus_denied",run().reason);assertEquals(1,s.closes);}
    @Test public void cleanupFailureIsReceiptNotSuccess(){s.closeFails=true;AudioQaInputTrackProbe.Result r=run();assertFalse(r.released);assertEquals("cleanup_failed",r.reason);}
    @Test public void resetInvalidatesEpoch(){s.reset=true;assertEquals("head_reset",run().reason);assertEquals(1,s.closes);}
    @Test public void routeAndGrantChangesInvalidateEpoch(){s.routeChange=true;assertEquals("grant_changed",run().reason);setup();s.grantChange=true;assertEquals("unsupported_grant",run().reason);}
    @Test public void missingUnderrunIsInconclusive(){s.neverUnderrun=true;AudioQaInputTrackProbe.Result r=run();assertEquals("missing_underrun",r.reason);assertEquals("INCONCLUSIVE",r.restart);}
    @Test public void focusLossAndInvalidAcceptanceAreTerminal(){s.loseFocus=true;assertEquals("focus_lost",run().reason);assertEquals(1,s.closes);setup();s.overAccept=true;assertEquals("invalid_acceptance",run().reason);assertEquals(1,s.closes);}
    @Test public void readGapsAreMeasuredAndLateSamplingIsNotPrecisionProof(){
        AudioQaInputTrackProbe.Result r=run();assertEquals(500000,r.maxGap);assertEquals("SAMPLED_BOUND_240",r.precision);
        setup();t.step=60000000;assertEquals("INCONCLUSIVE",run().restart);
    }
    @Test public void crossingBoundaryIsWriteStartAndTraceUsesAcceptedCounts(){
        AudioQaInputTrackProbe.Result r=run();boolean crossing=false;long accepted=0;
        for(int i=0;i<r.count;i++){
            int at=i*AudioQaInputTrackProbe.WIDTH;long[] e=r.events;
            assertEquals(i+1,e[at]);
            if(e[at+1]==3){accepted+=e[at+10];assertEquals(accepted,e[at+11]);
                if(e[at+2]==6&&!crossing){assertEquals(e[at+6],r.crossStart);crossing=true;}}
        }
        assertTrue(crossing);assertTrue(r.earlyEnd<r.crossStart);assertEquals(s.total,accepted);
    }
    @Test public void startAndHeadExceptionsReleaseAndRecordFailedOperation(){
        s.throwStart=true;AudioQaInputTrackProbe.Result r=run();assertEquals("error",r.reason);assertEquals(1,s.closes);assertEquals(1,r.events[1]);
        setup();s.throwHead=true;r=run();assertEquals("error",r.reason);assertEquals(1,s.closes);assertEquals(-1,r.events[AudioQaInputTrackProbe.WIDTH+8]);
    }
    @Test public void zeroWritesAfterPrefixRespectSubphaseDeadline(){s.zeroAfter=1;AudioQaInputTrackProbe.Result r=run();assertEquals("write_timeout",r.reason);assertEquals(240,r.accepted);assertTrue(t.t<=200000000);assertEquals(1,s.closes);}
    @Test public void intervalFormulaDistinguishesDefiniteViolationFromTimingUncertainty(){
        s.threshold=2880;s.headQuantum=480;AudioQaInputTrackProbe.Result r=run();assertEquals("complete",r.reason);assertEquals("SAMPLED_VIOLATION_240",r.precision);
        setup();s.threshold=2880;s.readDelay=6000000;r=run();assertEquals("complete",r.reason);assertEquals(6000000,r.maxBracket);assertEquals("INCONCLUSIVE",r.precision);
    }
    @Test public void ringOverflowDisarmsAndReleases(){t.step=1000;AudioQaInputTrackProbe.Result r=run();assertEquals("overflow",r.reason);assertTrue(r.count<=4096);assertEquals(1,s.closes);}
}
