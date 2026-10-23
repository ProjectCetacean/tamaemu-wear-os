package io.github.projectcetacean.tamaemu;

import android.os.Build;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,33},application=TamaemuApplication.class)
public class TimeVerifierAndroidTest {
    @Test public void apiGuardAndIndependentComparison(){
        TimeVerifierTest.Local local=new TimeVerifierTest.Local();
        int[] calls={0};
        AndroidTimeVerifier adapter=new AndroidTimeVerifier(Build.VERSION.SDK_INT,()->{
            calls[0]++;return java.time.Clock.fixed(java.time.Instant.ofEpochMilli(1800000000000L),java.time.ZoneOffset.UTC);
        });
        TimeVerifier v=new TimeVerifier(local,adapter);
        assertEquals(Build.VERSION.SDK_INT>=33?TimeVerifier.Outcome.MATCH:TimeVerifier.Outcome.UNAVAILABLE,v.check());
        assertEquals(Build.VERSION.SDK_INT>=33?1:0,calls[0]);
    }
    @Test public void api32GuardNeverEntersApi33(){
        TimeVerifier v=new TimeVerifier(new TimeVerifierTest.Local(),new AndroidTimeVerifier(32,()->{throw new AssertionError("API33 entered on API32");}));
        assertEquals(TimeVerifier.Outcome.UNAVAILABLE,v.check());
    }
    @Test public void obtainAndReadFailureAreUnavailable(){
        for(int failure=1;failure<=2;failure++){
            final int kind=failure;
            TimeVerifier v=new TimeVerifier(new TimeVerifierTest.Local(),new AndroidTimeVerifier(Build.VERSION.SDK_INT,()->{
                if(kind==1)throw new java.time.DateTimeException("No estimate");
                return new java.time.Clock(){
                    public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
                    public java.time.Clock withZone(java.time.ZoneId zone){return this;}
                    public java.time.Instant instant(){throw new java.time.DateTimeException("No read");}
                };
            }));
            assertEquals(TimeVerifier.Outcome.UNAVAILABLE,v.check());
        }
    }
    @Test public void timeCheckOnlyProcessNeverCreatesOwner() throws Exception {
        TamaemuApplication app=(TamaemuApplication)RuntimeEnvironment.getApplication();
        java.lang.reflect.Field f=TamaemuApplication.class.getDeclaredField("session");f.setAccessible(true);
        assertNull(f.get(app));
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        app.timeOpportunity(done::countDown);
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));
        assertNull("time check cannot initialize an emulator",f.get(app));
        assertFalse(new java.io.File(app.getFilesDir(),"pet.state").exists());
    }
}
