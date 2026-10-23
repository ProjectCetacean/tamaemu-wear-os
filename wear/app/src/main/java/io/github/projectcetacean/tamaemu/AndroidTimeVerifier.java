package io.github.projectcetacean.tamaemu;

import android.os.Build;
import android.os.SystemClock;

/** Uses Android's existing estimate; no network request, permission or synchronization guarantee. */
final class AndroidTimeVerifier implements TimeVerifier.Provider {
    private final int sdk;
    private final TimeVerifier.Provider api33;
    AndroidTimeVerifier(){this(Build.VERSION.SDK_INT,Api33::obtain);}
    AndroidTimeVerifier(int sdk,TimeVerifier.Provider api33){this.sdk=sdk;this.api33=api33;}
    @Override public java.time.Clock obtain(){return sdk>=33?api33.obtain():null;}
    private static final class Api33 {
        @android.annotation.TargetApi(33)
        static java.time.Clock obtain(){return SystemClock.currentNetworkTimeClock();}
    }
}
